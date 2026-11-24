# Mini-Redis

A Redis-compatible in-memory key-value server written from scratch in Java 21: raw TCP sockets,
the RESP wire protocol, TTL expiry, LRU eviction, append-only-file persistence and thread-safe
command execution. No frameworks, no dependencies.

## Features

| Area | What it does |
|---|---|
| Networking | TCP server, RESP protocol (works with `redis-cli`), pipelining, `TCP_NODELAY` |
| Commands | `PING`, `ECHO`, `SET [EX]`, `GET`, `DEL`, `EXISTS`, `INCR`, `EXPIRE`, `PEXPIREAT`, `TTL`, `DBSIZE`, `INFO`, `INSPECT` (debug) |
| Concurrency | Thread pool or virtual threads, one global lock for all command execution |
| Limits | Max clients, idle timeout, protocol size limits |
| Expiry | Lazy expiry on read + background sweeper every second |
| Eviction | Exact LRU in O(1) (HashMap + doubly linked list) behind an `EvictionPolicy` interface |
| Persistence | Append-only file, `fsync` = `always` / `everysec` / `no`, replay on startup, truncated-record recovery |

## Architecture

```
client bytes → ClientHandler → RespParser → CommandExecutor (global lock)
                                              ├─ Dispatcher → Command.execute(DataStore)
                                              │                  └─ DataStore ─ TTL set + EvictionPolicy (LRU)
                                              └─ AofWriter.append()  (write commands only)
            ← RespWriter    ←──────────── RespValue
```

```
src/main/java/com/miniredis
├── server/        MiniRedisServer (accept loop, config), ClientHandler (per connection), CommandExecutor (lock + AOF)
├── protocol/      RespParser, RespWriter, RespValue
├── command/       Command, Dispatcher, Commands (one class per command)
├── store/         DataStore (map + TTL)   └── eviction/ EvictionPolicy, LruPolicy
├── persistence/   AofWriter, AofLoader, FsyncPolicy
├── tools/         Cli (redis-cli look-alike), LoadTest (race test + benchmark), RespClient
├── dashboard/     Dashboard (web UI backend; page in src/main/resources/dashboard/index.html)
└── util/          Log
```

## Web dashboard (live demo)

```bash
./demo-ui.sh                     # starts the server (max 5 keys) + dashboard, opens http://localhost:8080
./demo-ui.sh --unsafe-no-lock    # same, with the global lock OFF to show the race condition
```

| Panel | Shows |
|---|---|
| Header | Connection status and server config: port, threads, fsync, AOF, global lock |
| Stats | Keys vs max, connected clients, live ops/sec, commands processed, evictions, expirations |
| Console | Type any command (like `redis-cli`), with one-click sample commands |
| Activity | Live feed of LRU evictions, TTL expirations and client connections |
| Keyspace | Keys in LRU order, TTL countdown bars, "NEXT EVICTION" marker |
| Concurrency test | 50 clients × 1000 `INCR`: expected vs actual vs lost updates |
| Benchmark | SET throughput, p50/p99, bar chart comparing runs |

The dashboard is a separate process (`dashboard/Dashboard.java` + `resources/dashboard/index.html`, JDK
`HttpServer`, no dependencies). It talks to Mini-Redis over RESP like any other client, using `INFO` and the
debug command `INSPECT`. It binds to `127.0.0.1` only.

## Run

```bash
./build.sh                                                   # or: mvn package
java -cp out com.miniredis.server.MiniRedisServer            # port 6380
java -cp out com.miniredis.tools.Cli                         # interactive client (or: redis-cli -p 6380)
./demo.sh                                                    # full scripted demo
mvn test                                                     # 20 unit tests
```

Server options:

| Option | Default | Meaning |
|---|---|---|
| `--port` | `6380` | TCP port |
| `--max-keys` | `10000` | LRU eviction starts above this many keys |
| `--threads` | `platform` | `platform` (fixed pool) or `virtual` |
| `--pool` | `64` | Thread-pool size (platform mode) |
| `--max-clients` | pool size | Extra clients get `-ERR max number of clients reached` |
| `--idle-timeout` | `300000` | Milliseconds before an idle client is disconnected (0 = never) |
| `--aof` | `appendonly.aof` | AOF file path, or `off` |
| `--fsync` | `everysec` | `always`, `everysec` or `no` |
| `--unsafe-no-lock` | off | Disables the global lock. Demo of the race condition only. |

## Results (MacBook, localhost, 10 clients, SET)

| Config | Throughput | p50 | p99 |
|---|---|---|---|
| `fsync=always` | 259 ops/s | 39.9 ms | 43.9 ms |
| `fsync=everysec` | ~13–15k ops/s | ~0.7 ms | ~1–2 ms |
| AOF off | ~13k ops/s | ~0.7 ms | ~2 ms |
| `everysec` + pipeline 16 | ~68–72k ops/s | ~2 ms / batch | ~5–6 ms / batch |
| Virtual threads | ~14–15k ops/s | ~0.7 ms | ~1 ms |

Race test, 50 clients × 1000 `INCR`: without the lock, 763 updates lost. With the lock, 0 lost.

## Known limitations / future work

- Values are stored as UTF-8 `String`, so they are not fully binary-safe (`byte[]` would be).
- Eviction is by key count, not memory bytes.
- The AOF grows forever (no rewrite/compaction yet).
- One global lock: commands run one at a time. `fsync=always` blocks every client (needs group commit).
- No replication or sharding (consistent hashing is the planned next step).
