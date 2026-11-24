#!/usr/bin/env bash
# Replays the full Mini-Redis demo. Usage: ./demo.sh   (press Enter between scenes)
set -uo pipefail
cd "$(dirname "$0")"
[ -d out ] || ./build.sh

CLI="java -cp out com.miniredis.tools.Cli"
LT="java -cp out com.miniredis.tools.LoadTest"
SERVER="java -cp out com.miniredis.server.MiniRedisServer"
run()   { printf '\n> %s\n' "$*"; $CLI "$@"; }
scene() { printf '\n\n===== %s =====\n' "$1"; read -rp "(press Enter)" _; }
start() { $SERVER "$@" > server.log 2>&1 & SERVER_PID=$!; sleep 1; }
stop()  { kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; }
trap 'pkill -f com.miniredis.server 2>/dev/null' EXIT

rm -f demo.aof
start --port 6380 --max-keys 3 --aof demo.aof --fsync everysec

scene "1. Basic commands over RESP"
run PING; run SET name alice; run GET name; run INCR visits; run INCR visits
run GET missing; run SET onlykey; run FOO bar

scene "2. TTL expiry (lazy + background sweeper)"
run SET session abc123 EX 2; run TTL session; run TTL name
echo "(sleeping 3s)"; sleep 3
run GET session; run TTL session; grep expire server.log

scene "3. LRU eviction (--max-keys 3)"
run DEL name visits; run SET a 1; run SET b 2; run SET c 3
run GET a; echo "(b is now least recently used)"
run SET d 4; run EXISTS b; run EXISTS a c d; run INFO

scene "4. Persistence: kill -9 and restart"
run SET user:1 bob; run EXPIRE user:1 3600; sleep 1.2
echo; echo "--- tail of demo.aof (EXPIRE stored as absolute PEXPIREAT) ---"; tr -d '\r' < demo.aof | tail -12
kill -9 "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null; echo "--- server killed with SIGKILL ---"
start --port 6380 --max-keys 3 --aof demo.aof; head -1 server.log
run GET user:1; run TTL user:1

scene "5. Crash in the middle of an AOF write"
kill -9 "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null
printf '*3\r\n$3\r\nSET\r\n$4\r\nhalf' >> demo.aof; echo "appended a half-written record"
start --port 6380 --max-keys 3 --aof demo.aof; head -2 server.log
run GET user:1; stop

scene "6. Race condition: global lock OFF vs ON"
start --port 6381 --aof off --unsafe-no-lock; $LT incr --port 6381 --clients 50 --ops 1000; stop
start --port 6381 --aof off;                  $LT incr --port 6381 --clients 50 --ops 1000; stop

scene "7. Benchmarks"
rm -f bench.aof
start --port 6382 --aof bench.aof --fsync always;   echo "## fsync=always";   $LT bench --port 6382 --clients 10 --ops 100;  stop; rm -f bench.aof
start --port 6382 --aof bench.aof --fsync everysec; echo "## fsync=everysec"; $LT bench --port 6382 --clients 10 --ops 20000; stop; rm -f bench.aof
start --port 6382 --aof bench.aof --fsync everysec; echo "## pipeline=16";    $LT bench --port 6382 --clients 10 --ops 20000 --pipeline 16; stop; rm -f bench.aof

scene "8. Max clients + idle timeout"
start --port 6383 --aof off --max-clients 2 --idle-timeout 2000
(sleep 4 | nc localhost 6383 >/dev/null) & (sleep 4 | nc localhost 6383 >/dev/null) & sleep 0.5
run -p 6383 PING; sleep 3; run -p 6383 PING; stop; cat server.log
