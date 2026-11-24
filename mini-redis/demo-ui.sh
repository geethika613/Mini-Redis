#!/usr/bin/env bash
# Starts Mini-Redis + the web dashboard, then opens http://localhost:8080. Ctrl-C stops both.
# Extra arguments go to the server, e.g.:
#   ./demo-ui.sh                      # demo mode: max 5 keys so LRU eviction is easy to see
#   ./demo-ui.sh --unsafe-no-lock     # global lock OFF -> race test shows lost updates
#   ./demo-ui.sh --fsync always       # compare benchmark results
#   ./demo-ui.sh --max-keys 10000     # normal size
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null

java -cp out com.miniredis.server.MiniRedisServer --port 6380 --max-keys 5 --aof demo.aof "$@" > server.log 2>&1 &
SERVER_PID=$!
sleep 1
java -cp out com.miniredis.dashboard.Dashboard --http-port 8080 --redis-port 6380 &
DASH_PID=$!
trap 'kill $SERVER_PID $DASH_PID 2>/dev/null; echo; echo "stopped"' EXIT INT TERM
sleep 1

echo "Server log: $(pwd)/server.log   |   Press Ctrl-C to stop."
open http://localhost:8080 2>/dev/null || xdg-open http://localhost:8080 2>/dev/null || true
wait
