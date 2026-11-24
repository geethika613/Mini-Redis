# Mini-Redis: Step-by-Step Guide

For each step: **what it does** (in plain words), **where the code is**, **concepts** to know,
**trade-offs** you made, and **interview questions** with short answers.

All paths are relative to `src/main/java/com/miniredis/`.

---

## The big picture (say this first in the interview)

> "I built a small Redis from scratch in Java. A client connects over TCP and sends commands in the
> Redis protocol. My server parses them, runs them against an in-memory store, and sends replies back.
> Keys can expire, the store evicts the least-recently-used key when it's full, and every write is
> logged to a file so data survives a crash. I measured how each design choice affects performance."

What happens for one command, e.g. `SET name alice`:

1. **`MiniRedisServer`** accepted the TCP connection and gave it to a thread.
2. **`ClientHandler`** reads bytes from the socket.
3. **`RespParser`** turns the bytes into `["SET", "name", "alice"]`.
4. **`CommandExecutor`** takes the global lock.
5. **`Dispatcher`** finds `SetCommand` and checks the argument count.
6. **`SetCommand`** calls **`DataStore.set()`**, which updates the map, the TTL set and the **`LruPolicy`**.
7. Because `SET` is a write, **`AofWriter`** appends it to the log file.
8. The lock is released, and **`RespWriter`** sends `+OK` back.

---

## Step 1: TCP server

### What it does
Opens port 6380, waits for clients to connect, and gives each client its own thread.
Each thread reads commands in a loop until the client disconnects.

### Where the code is
| File | Line | What |
|---|---|---|
| `server/MiniRedisServer.java` | 64–65 | `setReuseAddress(true)` and `bind(port, 128)`: claim the port, backlog 128 |
| `server/MiniRedisServer.java` | 89 | `server.accept()`: wait for the next client |
| `server/MiniRedisServer.java` | 103 | `workers.submit(new ClientHandler(...))`: give the client to a thread |
| `server/ClientHandler.java` | 39–46 | The read → execute → reply loop |
| `server/ClientHandler.java` | 41–42 | `readCommand()` returns `null` when the client disconnects (EOF) |

### Concepts
- **Socket**: one end of a network connection. The server has a *listening* socket and one *connected* socket per client.
- **bind / listen / accept**: claim a port → let the kernel queue incoming connections → take one off the queue.
- **TCP 3-way handshake**: SYN → SYN-ACK → ACK. The kernel does this for you, before `accept()` returns.
- **4-tuple**: (client IP, client port, server IP, server port) uniquely identifies a connection.
- **Blocking I/O**: `read()` makes the thread wait until data arrives.
- **EOF**: `read()` returns -1 when the other side closed the connection cleanly.
- **File descriptor**: every socket is a number in the OS's per-process table; there is a limit (`ulimit -n`).

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| One thread per client (blocking) | Very simple code, easy to reason about | Each thread costs memory; doesn't scale to 10k clients |
| `setReuseAddress(true)` | Restart works immediately | (none in practice) |
| TCP instead of UDP | Reliable, ordered | Slightly more overhead per connection |

### Interview questions
- **What happens when you call `accept()`?**
  The kernel already finished the handshake and put the connection in a queue (the *backlog*). `accept()` takes it out and returns a new socket just for that client.
- **Why does each client get a new socket?**
  The listening socket only receives new connections. Each client's data travels on its own connected socket, identified by its 4-tuple.
- **What happens if a client crashes?**
  Clean close → `read` returns EOF. Reset → `IOException`. Machine vanished → nothing; you need a timeout (Step 4).
- **Why TCP and not UDP?** Commands must not be lost or reordered. `SET` then `GET` must arrive in that order.
- **What is the backlog?** The kernel's queue of connections that finished the handshake but haven't been `accept()`-ed yet.
- **What is `SO_REUSEADDR`?** After a restart, the old port can be stuck in `TIME_WAIT` for a while. This option lets you bind anyway.

---

## Step 2: RESP protocol (talking the Redis language)

### What it does
TCP only gives you a stream of bytes, with no "message" boundaries. RESP is the format Redis uses to
say where each command starts and ends. The parser turns bytes into a list of strings; the writer turns
replies back into bytes.

`SET name alice` arrives as:
```
*3\r\n          ← an array of 3 items
$3\r\nSET\r\n   ← a string of 3 bytes: SET
$4\r\nname\r\n
$5\r\nalice\r\n
```

Reply types: `+OK` (simple string), `-ERR ...` (error), `:5` (integer), `$5\r\nalice` (bulk string), `$-1` (nil), `*N` (array).

### Where the code is
| File | Line | What |
|---|---|---|
| `protocol/RespParser.java` | 30 | `readCommand()`: entry point; handles RESP arrays and inline commands like `PING\r\n` |
| `protocol/RespParser.java` | 51 | `readArray()`: reads `*N`, then N × `$len` + data |
| `protocol/RespParser.java` | 61 | `readNBytes(len)`: waits until **all** bytes arrive → handles partial reads |
| `protocol/RespParser.java` | 18–20 | Size limits (512 MB bulk, 1M args, 64 KB line) → protection from bad clients |
| `protocol/RespWriter.java` | 20 | `write(RespValue)`: one `case` per reply type |
| `protocol/RespWriter.java` | 31 | Bulk length = **byte** length, not `String.length()` |
| `protocol/RespWriter.java` | 47 | `encodeCommand()`: reused by the AOF and the client tools |
| `protocol/RespValue.java` | 6 | Sealed interface of reply types |
| `server/ClientHandler.java` | 35 | `setTcpNoDelay(true)` |
| `server/ClientHandler.java` | 45 | Flush only when no more input is buffered → pipelining is efficient |
| Test: `protocol/RespProtocolTest.java` | | Partial reads, binary safety, byte length, size limits |

### Concepts
- **Framing**: deciding where one message ends. Two ways: a delimiter (`\n`) or a length prefix (`$5`). RESP uses both.
- **Partial reads**: one `read()` can return half a command, or 2.5 commands. Never assume one read = one message.
- **Binary-safe**: with a length prefix, a value may contain `\r\n` and still parse correctly.
- **Bytes vs characters**: `"é"` is 1 character but 2 bytes in UTF-8. Lengths on the wire are in bytes.
- **Buffering**: `BufferedInputStream` reads big chunks from the OS so you don't make one syscall per byte.
- **Nagle's algorithm**: TCP waits to combine small packets. Good for throughput, bad for small replies. `TCP_NODELAY` turns it off.
- **Pipelining**: the client sends many commands without waiting for each reply.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| Length-prefixed strings | Binary-safe, know the size before reading, no scanning | Slightly more parsing code |
| Text-ish protocol | Easy to read and debug | Bigger than a binary format like Protobuf |
| Store values as `String` | Simple | Not truly binary-safe (`byte[]` would be) |
| Flush when input buffer is empty | Fewer syscalls when pipelining | Slightly more complex than "flush every reply" |
| Size limits | A bad client can't make us allocate GBs | Very large legit values are rejected |

### Interview questions
- **Why do you need a protocol on top of TCP?**
  TCP is a byte stream with no message boundaries. Framing tells the server where each command ends.
- **What if only half a command arrives?**
  `readNBytes` blocks until the rest arrives. With non-blocking I/O (NIO) you'd have to save the half and continue later.
- **Why length prefixes?** Values can contain any bytes, even `\r\n`, and you know how much memory to allocate.
- **What is pipelining? Do you support it?**
  Sending many commands without waiting. Yes: the parser reads commands one after another, and I flush once per batch.
- **What if a client sends `$999999999999`?** It's rejected by the size limit before allocating memory.
- **Bug story:** using `String.length()` for bulk length breaks with non-ASCII text, because the length must be in bytes. The test `bulkLengthCountsBytesNotCharacters` covers it.
- **What is Nagle's algorithm and why disable it?** It delays small packets to batch them. Combined with delayed ACKs it can add ~40 ms to a small reply.

---

## Step 3: Command pattern (clean low-level design)

### What it does
Each command is its own small class. The `Dispatcher` looks up the command by name, checks the number
of arguments, and runs it. Adding a command never requires changing existing code.

### Where the code is
| File | Line | What |
|---|---|---|
| `command/Command.java` | 8 | Interface: `name()`, `arity()`, `isWrite()`, `execute()` |
| `command/Dispatcher.java` | 25 | `dispatch()`: unknown command? wrong arg count? else run it |
| `command/Commands.java` | 20 | `all()`: the list of registered commands |
| `command/Commands.java` | 66 | `SetCommand` (supports `EX seconds`) |
| `command/Commands.java` | 117 | `IncrCommand` (rejects non-integers, keeps TTL) |
| `command/Commands.java` | 138–170 | `ExpireCommand`, `PExpireAtCommand`, `TtlCommand` |
| `store/DataStore.java` | whole file | The store; commands only talk to it through methods |
| Test: `command/CommandAndPersistenceTest.java` | | Arity, unknown commands, `INCR` on text |

### Concepts
- **Command pattern**: wrap each action in an object with a common interface.
- **Registry**: a map from name → command object.
- **Open/closed principle**: open for extension (new class), closed for modification (no edits to old code).
- **Single responsibility**: parser parses, dispatcher validates, command executes, store stores.
- **Encapsulation**: `DataStore` hides the `HashMap`, so TTL and LRU can be added without touching commands.
- **Arity**: number of arguments. Redis convention: positive = exact, negative = minimum.
- **Sealed interface + records** (Java 21): the compiler knows every reply type.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| One class per command | Easy to add and test each command | More classes |
| Big `switch` | Everything in one place | Grows forever, hard to test, every change touches it |
| Commands return `RespValue` | Pure logic, unit-testable without a network | One small object per reply |
| `isWrite()` flag | AOF knows which commands to log, no special cases | Must remember to set it for new commands |

### Interview questions
- **How do you add a new command, say `APPEND`?** Write `AppendCommand`, add it to `Commands.all()`. Nothing else changes.
- **Where does validation happen?** Generic checks (unknown command, arg count) in `Dispatcher`. Command-specific checks (is it a number?) inside the command.
- **How do you test without a network?** Create a `DataStore`, call `dispatch()`, check the `RespValue`. See the tests.
- **Which design patterns did you use?** Command, Registry, Strategy (eviction policy, fsync policy), Template-ish base class (`Base`).
- **Why no singletons?** Everything is passed through constructors, so tests can build fresh, independent instances.

---

## Step 4: Thread pool, limits and timeouts (OS resources)

### What it does
Instead of creating unlimited threads, the server uses a fixed pool (or virtual threads). It rejects
clients over the limit, disconnects idle clients, and shuts down cleanly.

### Where the code is
| File | Line | What |
|---|---|---|
| `server/MiniRedisServer.java` | 58–60 | Fixed thread pool or virtual threads (`--threads`) |
| `server/MiniRedisServer.java` | 93–101 | Too many clients → send `-ERR max number of clients reached`, close |
| `server/MiniRedisServer.java` | 68–77 | Shutdown hook: stop accepting, final fsync of the AOF |
| `server/MiniRedisServer.java` | 107 | `parse()`: config; max clients is capped at the pool size |
| `server/ClientHandler.java` | 36 | `setSoTimeout(idleTimeoutMs)`: read timeout |
| `server/ClientHandler.java` | 52 | `SocketTimeoutException` → close as "idle timeout" |

### Concepts
- **Thread cost**: each OS thread has its own stack (often around 1 MB reserved) and the OS must switch between threads (context switching).
- **Thread pool**: a fixed set of reusable threads. With blocking I/O, one thread is busy for the whole life of a connection.
- **Virtual threads (Java 21)**: very cheap threads managed by the JVM. When one blocks on I/O, the real OS thread runs another one.
- **C10K problem**: how to serve 10,000 connections at once. The classic answer is an event loop with `epoll`.
- **`select` / `poll` / `epoll`**: ways to ask the kernel "which of my sockets have data?" `epoll` only returns the ready ones, so it scales.
- **Read timeout (`SO_TIMEOUT`)**: give up waiting for data after N ms.
- **TCP keepalive**: the OS sends small probes to detect connections whose other side vanished.
- **Shutdown hook**: code the JVM runs on a normal stop (Ctrl-C, `kill`). Not on `kill -9`.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| Fixed pool of platform threads | Predictable resource use | Pool size = max clients; idle clients waste threads |
| Virtual threads | Blocking-style code that scales to many clients | Newer; some blocking patterns can pin carrier threads |
| NIO event loop (not built) | Scales best, how real Redis works | Much more complex: you must handle partial reads yourself |
| Reject over the limit | Server stays healthy | Clients see errors |
| Idle timeout | Frees threads held by dead or idle clients | Legit idle clients must reconnect |

### Interview questions
- **Why not just one new thread per client forever?** Each thread costs memory and scheduling time. At thousands of threads, the OS spends more time switching than working.
- **What's the C10K problem and how is it solved?** Serving 10k connections. Solved with non-blocking sockets and `epoll`: one thread waits for "ready" events on many sockets.
- **`select` vs `epoll`?** `select` checks every socket on every call (and is often limited to 1024). `epoll` keeps the list in the kernel and only returns ready sockets.
- **Why is real Redis single-threaded?** Operations in memory take microseconds. The real cost is network and syscalls. One thread means no locks at all. (Redis 6+ uses extra threads only for socket I/O.)
- **How do you detect dead clients?** Read timeouts for idle ones; TCP keepalive for "half-open" ones whose machine disappeared.
- **What happens when you run out of file descriptors?** `accept()` fails with "Too many open files". The server must log it and keep running.
- **Process vs thread?** Threads share memory (cheap, but need locks). Processes are isolated (safer, more expensive).
- **Why is max clients capped at the pool size?** With a fixed pool, extra clients would silently wait in the queue with no thread to serve them. Rejecting is clearer.

---

## Step 5: TTL expiry

### What it does
Keys can have a time-to-live. Expired keys are removed in two ways:
1. **Lazy**: whenever someone reads a key, check if it expired; if so, delete it.
2. **Active**: a background thread runs every second and deletes expired keys nobody reads.

### Where the code is
| File | Line | What |
|---|---|---|
| `store/DataStore.java` | 136 | `live()`: lazy expiry; every read goes through it |
| `store/DataStore.java` | 72 | `expireAt()`: stores an **absolute** expiry time; past time → delete now |
| `store/DataStore.java` | 100 | `removeExpired()`: active expiry, scans only keys that have a TTL |
| `server/MiniRedisServer.java` | 53 | The sweeper thread (every 1s, takes the global lock) |
| `command/Commands.java` | 66, 138, 163 | `SET ... EX`, `EXPIRE`, `TTL` |
| Test: `store/DataStoreTest.java` | | Lazy expiry, sweeper, `SET` clears TTL |

### Concepts
- **Absolute vs relative time**: store "expires at 10:05:00", not "expires in 10s". Absolute is needed for persistence.
- **Wall clock vs monotonic clock**: wall clock (`currentTimeMillis`) can jump if the system time changes. Monotonic (`nanoTime`) can't, but means nothing after a restart.
- **Background thread**: `ScheduledExecutorService` runs a task at a fixed rate.
- **Race condition**: the sweeper and clients touch the same data, so the sweeper takes the same lock.
- **Memory leak**: without active expiry, keys that are never read again stay in memory forever.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| Lazy only | Zero CPU cost | Unread expired keys leak memory |
| Active only | Frees memory | Costs CPU; an expired key could be read between sweeps |
| **Both** (what I did, like Redis) | Reads always correct + memory freed | More code |
| Full scan of TTL keys every second | Simple, exact | Cost grows with number of TTL keys |
| Redis-style random sampling (not built) | Constant cost per run | Some expired keys live a bit longer |
| Wall-clock time | Survives restarts | Breaks if the system clock jumps |

### Interview questions
- **Can a client ever read an expired key?** No. Every read goes through `live()`, which checks expiry first.
- **Why not one timer per key?** A million timers = a lot of memory and scheduler work.
- **How does real Redis do active expiry?** Every 100 ms it samples 20 random keys with a TTL, deletes the expired ones, and repeats if more than 25% were expired, with a time budget.
- **What if the system clock jumps forward?** Many keys expire at once. Backward: keys live longer.
- **Why does `SET` remove the TTL but `INCR` keeps it?** `SET` replaces the key with a new value (new life). `INCR` modifies the existing value. Same as real Redis.
- **What if the sweeper and a client touch the same key at the same time?** Both take the global lock, so they never run at the same time.

---

## Step 6: LRU eviction

### What it does
When the store is full (`--max-keys`), adding a new key removes the key that was **used least recently**.
It uses a HashMap + doubly linked list so every operation is O(1).

```
head ⇄ [d] ⇄ [a] ⇄ [c] ⇄ tail
 most recent          least recent → evict this one
```

### Where the code is
| File | Line | What |
|---|---|---|
| `store/eviction/EvictionPolicy.java` | 4 | Interface: `onAccess`, `onInsert`, `onDelete`, `victim` |
| `store/eviction/LruPolicy.java` | 7 | The LRU implementation; head/tail are dummy "sentinel" nodes |
| `store/eviction/LruPolicy.java` | 27 | `onAccess`: move the key to the front |
| `store/eviction/LruPolicy.java` | 49 | `victim`: the node just before `tail` |
| `store/eviction/LruPolicy.java` | 53, 58 | `unlink`, `addFront`: O(1) pointer changes |
| `store/DataStore.java` | 147 | `put()`: if new key and full → `evictOne()` first |
| `store/DataStore.java` | 157 | `evictOne()` |
| Test: `store/DataStoreTest.java` | | `evictsLeastRecentlyUsedKey`, `overwritingExistingKeyDoesNotEvict` |

### Concepts
- **O(1) LRU**: the map finds the node instantly; the list keeps the order; moving a node is just pointer changes.
- **Doubly linked list**: each node knows its previous and next, so you can remove it from the middle in O(1).
- **Sentinel nodes**: dummy head and tail so you never handle "empty list" or "first node" as special cases.
- **Strategy pattern**: `DataStore` doesn't know it's LRU; it talks to `EvictionPolicy`.
- **Other policies**: LFU (least *frequently* used), FIFO, random.
- **Cache hit ratio**: % of reads that find the key. The goal of any eviction policy is to keep it high.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| Exact LRU (what I did) | Always evicts the true LRU key | 2 extra pointers per key; every `GET` changes the list |
| Approximate LRU (real Redis) | Less memory, no list | Sometimes evicts a slightly wrong key |
| LRU | Adapts fast to new popular keys | One big scan can push out all the popular keys |
| LFU | Resists scans | Slow to adapt when popularity changes |
| Limit by key count | Simple | Ignores value size (Redis limits by bytes) |

### Interview questions
- **Design an O(1) LRU cache.** HashMap key → node, doubly linked list in recency order. On access: move to front. On full: remove from tail.
- **Why doubly linked and not singly?** To remove a node from the middle you need its previous node. A singly linked list would need O(n) to find it.
- **Why sentinel nodes?** No null checks for empty list or first/last node. Fewer bugs.
- **How does LRU affect concurrency?** Every `GET` modifies the list, so reads are also writes. A read-write lock wouldn't help (see Step 8).
- **Why does real Redis approximate LRU?** To save memory and avoid list updates. It samples 5 keys and evicts the oldest of them; with 10 samples it's very close to exact.
- **Why not `LinkedHashMap(accessOrder=true)`?** It would work. I built it myself to show I understand it, and to have hooks (`onDelete`) the store needs.

---

## Step 7: Persistence (append-only file)

### What it does
Every successful write command is appended to `appendonly.aof` in RESP format. On startup, the file is
replayed command by command to rebuild the data. A half-written last record (from a crash) is cut off.

### Where the code is
| File | Line | What |
|---|---|---|
| `persistence/FsyncPolicy.java` | 6 | `ALWAYS`, `EVERYSEC`, `NO` |
| `persistence/AofWriter.java` | 43 | `append()`: write to the file (goes to the OS page cache) |
| `persistence/AofWriter.java` | 49 | `ALWAYS`: `force()` = fsync before replying |
| `persistence/AofWriter.java` | 36 | `EVERYSEC`: background thread fsyncs every second |
| `persistence/AofLoader.java` | 25 | `replay()`: parse the file with the same `RespParser`, re-run commands |
| `persistence/AofLoader.java` | 53 | Truncate a half-written last record |
| `server/CommandExecutor.java` | 64 | `toAofRecords()`: `EXPIRE` and `SET EX` are logged as `PEXPIREAT <absolute time>` |
| `server/CommandExecutor.java` | 37–40 | Only successful **write** commands are logged |
| Test: `command/CommandAndPersistenceTest.java` | | Replay, `PEXPIREAT`, reads not logged, truncation |

### Concepts
- **`write()` vs `fsync()`**: `write()` copies data into the kernel's **page cache** (RAM) and returns fast. `fsync()` forces it to the physical disk (slow).
- **Process crash vs power loss**: after `kill -9`, the page cache is still there, so the data survives. After a power loss, only fsynced data survives.
- **Append-only log / write-ahead log**: only add to the end; never modify the middle. Simple and fast (sequential writes).
- **Deterministic replay**: replaying the log must give the same result. Relative time ("expire in 10s") would not, so it's converted to absolute time.
- **Torn write**: a crash in the middle of writing leaves a half record at the end.
- **Group commit**: one fsync covering many writes (not built; the fix for slow `ALWAYS`).
- **Snapshot (RDB) and `fork()`**: real Redis can also dump the whole dataset; it uses `fork()` + copy-on-write to do it without stopping.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| `fsync=always` | No acknowledged write is ever lost | Measured: 259 ops/s, ~50× slower |
| `fsync=everysec` (default) | Fast (~13–15k ops/s, same as AOF off) | Can lose ~1 s of writes on power loss |
| `fsync=no` | Fastest | Can lose many seconds |
| Append-only log | Durable, simple, sequential writes | File grows forever, replay gets slow |
| Snapshot (not built) | Small file, fast restart | Loses everything since the last snapshot |
| Log only after success | No garbage commands in the log | If the log write fails after the store changed, they can differ |

### Interview questions
- **What does `write()` actually do?** Copies data into the OS page cache. It's on disk only later, when the OS flushes it or you call `fsync`.
- **What does `fsync` guarantee?** The data reached the storage device. (On macOS, plain `fsync` may still sit in the drive's cache; `F_FULLFSYNC` is stronger.)
- **Why did data survive `kill -9` with `everysec`?** `kill -9` kills the process, not the OS. The data was already in the page cache.
- **How much can you lose with `everysec`?** About 1 second of writes on a power loss or OS crash.
- **Why is `fsync=always` so slow in your benchmark?** Each fsync takes milliseconds, and it happens inside the global lock, so all clients wait in line. Fix: group commit.
- **Why convert `EXPIRE` to `PEXPIREAT`?** If you replay "expire in 3600s" a day later, the key gets a fresh hour. Absolute time keeps replay correct.
- **What if the file is half-written?** The loader detects the incomplete record, keeps everything before it, and truncates the rest.
- **The file grows forever. How would you fix it?** AOF rewrite: write the current data as the minimum set of commands to a new file, then atomically `rename()` it over the old one.
- **How does Redis snapshot without stopping?** `fork()` creates a child sharing the same memory pages (copy-on-write). The child writes the snapshot; pages are only copied if the parent changes them.

---

## Step 8: Concurrency correctness (the global lock)

### What it does
All command execution runs under **one lock**, so the map, the TTL set, the LRU list and the AOF are always
updated together. Network reading, parsing and writing replies happen outside the lock, in parallel.

### Where the code is
| File | Line | What |
|---|---|---|
| `server/CommandExecutor.java` | 19 | The class; holds the `ReentrantLock` |
| `server/CommandExecutor.java` | 34–50 | `execute()`: lock → dispatch → append to AOF → unlock (in `finally`) |
| `server/CommandExecutor.java` | 54 | `runLocked()`: used by the expiry sweeper |
| `store/DataStore.java` | 12–16 (comment) | Store is **not** thread-safe on purpose; the executor protects it |
| `server/MiniRedisServer.java` | 113 | `--unsafe-no-lock`: demo of the race |
| Test: `command/CommandAndPersistenceTest.java` | | `concurrentIncrLosesNoUpdatesWithGlobalLock` |

### Concepts
- **Race condition**: the result depends on the timing of threads.
- **Read-modify-write**: `INCR` = read value, add 1, write back. Two threads can both read 5 and both write 6, so one update is lost.
- **Atomicity**: a group of steps that happens as one, with no other thread in between.
- **Critical section**: code only one thread may run at a time.
- **`ReentrantLock`**: a mutex; `unlock()` must go in `finally` so it's released even on an exception.
- **`ConcurrentHashMap`**: each single call is atomic, but a *sequence* of calls is not.
- **Lock granularity**: one big lock (simple) vs many small locks (more parallel, more complex).
- **Deadlock**: two threads each wait for a lock the other holds. Avoid it by always taking locks in the same order.
- **Happens-before / visibility**: a lock also guarantees that the next thread sees the previous thread's writes.

### Trade-offs
| Choice | Good | Bad |
|---|---|---|
| **One global lock** (what I did) | Simple, correct; all structures stay consistent | Commands run one at a time; a slow fsync blocks everyone |
| Striped locks (per key group) | Different keys run in parallel | The LRU list is shared; multi-key `DEL a b` needs ordered locking |
| Single command thread (like Redis) | No locks in data structures | Handoff between threads costs a bit |
| `ReadWriteLock` | Parallel readers | Useless here: every `GET` updates the LRU list |
| Only `ConcurrentHashMap.compute()` | Fixes `INCR` alone | Doesn't keep map + TTL + LRU + AOF consistent together |

### Interview questions
- **Show me a race condition you found.** Without the lock: 50 clients × 1000 `INCR` → 763 updates lost. With the lock: 0.
- **Why is `INCR` racy even with `ConcurrentHashMap`?** `get` and `put` are each atomic, but the pair is not.
- **Why not a read-write lock?** In LRU, every read moves the key in the list, so every read is also a write.
- **Isn't one global lock slow?** Each command takes microseconds in memory, and all network I/O is outside the lock. The benchmarks show the bottleneck is the network round trip, not the lock. The exception is `fsync=always`.
- **How would you scale beyond one lock?** Split keys into N shards (by hash), each with its own map, LRU list and lock.
- **How do you avoid deadlock with multi-key commands?** Always lock keys in a fixed (sorted) order.
- **Why does `unlock()` go in `finally`?** If the command throws, the lock must still be released, or every other client hangs forever.

---

## Step 9: Benchmarks and tools

### What it does
Measures how fast the server is and proves the race condition, using tools written in the project.

### Where the code is
| File | Line | What |
|---|---|---|
| `tools/LoadTest.java` | 40 | `incrRace()`: 50 clients × 1000 `INCR`, compares expected vs actual |
| `tools/LoadTest.java` | 65 | `bench()`: N clients send `SET`s, measures throughput and p50/p99 latency, supports pipelining |
| `tools/RespClient.java` | 16 | Minimal RESP client |
| `tools/Cli.java` | 14 | `redis-cli` look-alike (one-shot or interactive) |
| `demo.sh` | | Runs all 8 demo scenes |

### Results (10 clients, SET, MacBook)
| Config | Throughput | p50 | Why |
|---|---|---|---|
| `fsync=always` | 259 ops/s | 39.9 ms | Every write waits for the disk, inside the lock |
| `fsync=everysec` | ~13–15k ops/s | ~0.7 ms | `write()` only goes to the page cache |
| AOF off | ~13k ops/s | ~0.7 ms | Same as `everysec` → the AOF isn't the bottleneck |
| Pipelining ×16 | ~68–72k ops/s | ~2 ms / batch | Far fewer network round trips and syscalls |
| Virtual threads | ~14–15k ops/s | ~0.7 ms | Same as platform at only 10 clients |

### Concepts
- **Throughput vs latency**: how many per second vs how long each one takes.
- **Percentiles**: p50 = typical request, p99 = the slow 1%. Averages hide slow requests.
- **Warmup**: the JIT compiles hot code after a while, so early runs are slower.
- **Round trip time (RTT)**: one request/reply over the network. Pipelining removes most of them.
- **Noise**: GC, JIT, other programs. Run each test more than once.

### Interview questions
- **Why p99 and not average?** Users notice the slow requests. An average of 1 ms can hide 1% of requests taking 100 ms.
- **Why does pipelining help so much?** Without it, each command waits for a full network round trip and costs separate syscalls.
- **What's your bottleneck?** Per-command network round trips. Proof: AOF off ≈ `everysec`, and pipelining gives ~5×.
- **Why were your first benchmark runs strange?** They were too short; JIT warmup made AOF-off look slower than `everysec`. Longer, repeated runs fixed it.
- **Limits of your benchmark?** Localhost only (no real network latency), one machine, client and server compete for CPU.

---

## Step 10: Tests

| Test file | What it proves |
|---|---|
| `src/test/.../store/DataStoreTest.java` | LRU order, no eviction on overwrite, lazy expiry, sweeper, `SET` vs `INCR` TTL behavior (uses a fake clock) |
| `src/test/.../protocol/RespProtocolTest.java` | RESP arrays, inline commands, binary-safe values, **one-byte-at-a-time partial reads**, size limits, byte length |
| `src/test/.../command/CommandAndPersistenceTest.java` | Arity checks, `INCR` errors, AOF replay, `PEXPIREAT` in the log, truncation, 50-thread race test |

**Interview point:** "I used a fake clock (`LongSupplier`) in `DataStore`, so TTL tests are instant and deterministic instead of calling `Thread.sleep`."

---

## Step 11: Web dashboard (for the live demo)

### What it does
A browser page that shows the server live: stats, a command console, the keyspace in LRU order with TTL
countdowns, an activity feed (evictions, expirations, connections), and buttons for the race test and benchmarks.
Start it with `./demo-ui.sh`.

### Where the code is
| File | What |
|---|---|
| `dashboard/Dashboard.java` | Small HTTP server (JDK `HttpServer`). Endpoints: `/api/state`, `/api/command`, `/api/race`, `/api/bench` |
| `src/main/resources/dashboard/index.html` | The page: plain HTML/CSS/JavaScript, polls `/api/state` every 500 ms |
| `command/Commands.java` | `INFO` (now with server config + stats) and the debug command `INSPECT` |
| `command/ServerInfo.java`, `server/ServerStats.java` | Live counters (clients, commands) shown by `INFO` |
| `store/DataStore.java` | `inspect()`: read-only view of keys in LRU order (does not count as an access) |

### Design points worth mentioning
- **The dashboard is just another client.** It speaks RESP to the server, so the server code has no HTTP or UI logic (separation of concerns).
- **Polling vs push.** The page asks for state every 500 ms. Simple and good enough for a demo. Push (WebSockets / server-sent events) would be more efficient.
- **`INSPECT` is read-only.** Looking at keys must not change the LRU order, or the demo would change what it is showing.
- **Security.** The dashboard can run commands, so it binds to `127.0.0.1` only and limits request sizes and test sizes.

## Known limitations (say these yourself, before they ask)

1. Values are `String`, not `byte[]`, so not fully binary-safe.
2. Eviction counts keys, not memory bytes.
3. The AOF grows forever (no rewrite yet).
4. One global lock; `fsync=always` blocks every client (fix: group commit).
5. Thread-per-connection instead of an `epoll` event loop.
6. No replication or sharding (next step: consistent hashing).

---

## Concepts to revise before the interview

| Area | Topics |
|---|---|
| Networking | TCP handshake, `TIME_WAIT`, backlog, framing, partial reads, Nagle / `TCP_NODELAY`, keepalive, TCP vs UDP |
| OS | Threads vs processes, context switching, file descriptors, blocking vs non-blocking I/O, `epoll`, page cache, `fsync`, `fork` + copy-on-write |
| Concurrency | Race conditions, atomicity, locks, deadlock, happens-before, thread pools, virtual threads |
| LLD | SOLID, Command, Strategy, Registry, dependency injection, testable design |
| HLD | Caching, eviction policies, durability trade-offs, sharding, consistent hashing, replication |
