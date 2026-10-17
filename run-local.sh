#!/bin/bash
# The whole backend on this machine (decision D-80, docs/development/03-build-and-run.md §5): the
# store, the platform, a worker, the gateway and an arena, from the release build-offline.sh made,
# on the release's own runtime, against the development MySQL's backend_dev (started if it is not).
# Their logs, pids and files are in work/run.
#
#   ./run-local.sh start [store platform worker gateway arena]   all five unless some are named
#   ./run-local.sh bots [count] [seconds]                        bots play through it (20 for 15 s)
#   ./run-local.sh status | stop                                 stop leaves the MySQL running
#
# Ports, each refused if taken: STORE_PORT 6380, PLATFORM_PORT 8080, GATEWAY_PORT 8081, ARENA_PORT
# 9001. DEBUG=1: each Java process waits for no one but listens for a debugger, the platform on
# 5005, the worker 5006, the gateway 5007, the arena 5008, the store 5009; it then runs on vendor/'s
# JDK, as the release's runtime has no debugger agent.
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
L=$WORK/run
STORE_PORT=${STORE_PORT:-6380} PLATFORM_PORT=${PLATFORM_PORT:-8080}
GATEWAY_PORT=${GATEWAY_PORT:-8081} ARENA_PORT=${ARENA_PORT:-9001}
export BACKEND_DB_URL=${BACKEND_DB_URL:-"jdbc:mysql://127.0.0.1:$MYSQL_PORT/backend_dev?useSSL=false&allowPublicKeyRetrieval=true"}
export BACKEND_DB_USER=${BACKEND_DB_USER:-backend} BACKEND_DB_PASSWORD=${BACKEND_DB_PASSWORD:-backend-dev-password}
ALL="store platform worker gateway arena"
declare -A DEBUG_PORT=([platform]=5005 [worker]=5006 [gateway]=5007 [arena]=5008 [store]=5009)

R=$(ls -d "$B"/backend/target/release/backend-*/ 2> /dev/null | head -1 || true); R=${R%/}
# Running, and ours: a pid left from before a reboot may be another process's by now.
# No pipe: under pipefail, a reader that ends first fails the writer, and so the test.
running() {
    local pid cmd
    pid=$(cat "$L/$1.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$B/backend/target/release/"* ]]
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }

start_one() {
    local p=$1 port args java
    if running "$p"; then echo "$p: already running"; return; fi
    case $p in
        store)    port=$STORE_PORT
                  args=(-jar "$R/jredis/lib/j-redis-server.jar" --port "$STORE_PORT" --dir "$L/store") ;;
        platform) port=$PLATFORM_PORT
                  args=(-cp "$R/lib/platform/*" com.backend.platform.PlatformMain 127.0.0.1 "$PLATFORM_PORT"
                        127.0.0.1 "$STORE_PORT") ;;
        worker)   port=
                  args=(-cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 "$STORE_PORT" worker-1) ;;
        gateway)  port=$GATEWAY_PORT
                  args=(-cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain 127.0.0.1 "$GATEWAY_PORT"
                        "http://127.0.0.1:$PLATFORM_PORT" 127.0.0.1 "$STORE_PORT" gateway-1) ;;
        arena)    port=$ARENA_PORT
                  # port, map size, max players, shapes, rooms, store, name, match seconds (0: continuous),
                  # spool, bind host, advertised host (backend/README, Running it)
                  args=(-XX:+UseZGC -XX:+ZGenerational -cp "$R/lib/arena/*" com.backend.arena.ArenaMain
                        "$ARENA_PORT" 5700 150 1500 2 127.0.0.1 "$STORE_PORT" arena-1 0 "$L/spool" 127.0.0.1 127.0.0.1) ;;
        *)        echo "no process $p: $ALL" >&2; exit 2 ;;
    esac
    if [ -n "$port" ] && taken "$port"; then
        echo "$p: port $port is taken by another process: ${p^^}_PORT=<a free port>" >&2; exit 1
    fi
    java=("$R/runtime/bin/java")
    [ "${DEBUG:-0}" = 1 ] && java=("$JAVA_HOME/bin/java"
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${DEBUG_PORT[$p]}")
    mkdir -p "$L/store" "$L/spool"
    nohup "${java[@]}" --add-opens java.base/java.nio=ALL-UNNAMED "${args[@]}" > "$L/$p.log" 2>&1 &
    echo $! > "$L/$p.pid"
    # Up when its port answers; the worker has none, and is up when it is still running.
    for _ in $(seq 90); do
        running "$p" || { echo "$p ended; its log, $L/$p.log, ends:" >&2; tail -15 "$L/$p.log" >&2; exit 1; }
        if [ -z "$port" ]; then sleep 3; running "$p" && break; elif taken "$port"; then break; fi
        sleep 1
    done
    running "$p" && { [ -z "$port" ] || taken "$port"; } \
        || { echo "$p did not come up in 90 s: $L/$p.log" >&2; exit 1; }
    local dbg=; [ "${DEBUG:-0}" = 1 ] && dbg=", its debugger on 127.0.0.1:${DEBUG_PORT[$p]}"
    echo "$p: up${port:+ on 127.0.0.1:$port}$dbg (log $L/$p.log)"
}

case "${1:-}" in
start)
    [ -n "$R" ] || { echo "no release in backend/target/release: ./build-offline.sh first" >&2; exit 1; }
    mkdir -p "$L"
    shift; procs=${*:-$ALL}
    case " $procs " in *" platform "*|*" worker "*) "$B/dev-mysql.sh" start ;; esac
    for p in $ALL; do case " $procs " in *" $p "*) start_one "$p" ;; esac; done ;;
bots)
    jar=$(ls "$B"/backend/tools/target/tools-*-all.jar 2> /dev/null | head -1 || true)
    [ -n "$jar" ] || { echo "no backend/tools/target/tools-*-all.jar: ./build-offline.sh first" >&2; exit 1; }
    "$JAVA_HOME/bin/java" -cp "$jar" com.backend.tools.BotClient \
        "${2:-20}" "${3:-15}" "http://127.0.0.1:$PLATFORM_PORT" "ws://127.0.0.1:$GATEWAY_PORT/lobby" ;;
status)
    for p in $ALL; do
        if running "$p"; then echo "$p: running, pid $(cat "$L/$p.pid")"; else echo "$p: not running"; fi
    done
    "$B/dev-mysql.sh" status || true ;;
stop)
    for p in arena gateway worker platform store; do
        running "$p" || continue
        kill "$(cat "$L/$p.pid")"
        for _ in $(seq 30); do running "$p" || break; sleep 1; done
        if running "$p"; then echo "$p: still running after 30 s, pid $(cat "$L/$p.pid")" >&2
        else rm -f "$L/$p.pid"; echo "$p: stopped"; fi
    done ;;
*)
    echo "usage: $0 start [process ...] | bots [count] [seconds] | status | stop" >&2; exit 2 ;;
esac
