#!/bin/bash
# j-redis, the store, for a run by hand (decision D-92, docs/development/03-build-and-run.md §5): from its own build
# (j-redis-service/j-redis-server/target), on vendor/'s JDK, listening on 127.0.0.1:$STORE_PORT (6380) alone, its
# data in work/run/store and its log work/run/store.log. DEBUG=1: a debugger on 127.0.0.1:5009, waiting for no one.
#
#   ./dev-jredis.sh build      j-redis-service compiled, tested, installed in work/m2 (SKIP_TESTS=1: no tests)
#   ./dev-jredis.sh start | stop | restart | status
#   ./dev-jredis.sh test       it answers: PING, then a key of its own set, read and deleted
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
L=$WORK/run
J=$B/j-redis-service
jar() { ls "$J"/j-redis-server/target/j-redis-server-*-all.jar 2> /dev/null | head -1 || true; }
# Running, and ours: a pid left from before a reboot may be another process's by now.
running() {
    local pid cmd
    pid=$(cat "$L/store.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$J/j-redis-server/target/j-redis-server-"* ]]   # its own build's: not a drill's j-redis (O-86)
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }

case "${1:-}" in
build)
    TESTS=$([ "${SKIP_TESTS:-0}" = 1 ] && echo "-DskipTests" || true)
    echo "--- j-redis-service"
    (cd "$J" && mvn -B -q clean install $TESTS)
    echo "j-redis: built, $(jar)" ;;
start)
    j=$(jar)
    [ -n "$j" ] || { echo "j-redis is not built: $0 build first" >&2; exit 1; }
    if running; then echo "j-redis: already running, pid $(cat "$L/store.pid")"; exit 0; fi
    # In use, or refused by a policy of the machine: named before anything starts (D-88)
    "$B/backend/scripts/check-bind.sh" "$STORE_PORT" \
        || { echo "another port for it: STORE_PORT=<a free port>, the same for every later command" >&2; exit 1; }
    mkdir -p "$L/store"
    java=("$JAVA_HOME/bin/java")
    [ "${DEBUG:-0}" = 1 ] && java+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5009")
    nohup "${java[@]}" --add-opens java.base/java.nio=ALL-UNNAMED -jar "$j" \
        --port "$STORE_PORT" --bind 127.0.0.1 --dir "$L/store" > "$L/store.log" 2>&1 &
    echo $! > "$L/store.pid"
    for _ in $(seq 60); do running || break; taken "$STORE_PORT" && break; sleep 1; done
    running && taken "$STORE_PORT" \
        || { echo "j-redis did not come up; its log, $L/store.log, ends:" >&2; tail -15 "$L/store.log" >&2; exit 1; }
    dbg=; [ "${DEBUG:-0}" = 1 ] && dbg=", its debugger on 127.0.0.1:5009"
    echo "j-redis: up on 127.0.0.1:$STORE_PORT, its data in $L/store$dbg (log $L/store.log)" ;;
stop)
    running || { echo "j-redis: not running"; exit 0; }
    kill "$(cat "$L/store.pid")"
    for _ in $(seq 30); do running || break; sleep 1; done
    if running; then echo "j-redis: still running after 30 s, pid $(cat "$L/store.pid")" >&2; exit 1; fi
    rm -f "$L/store.pid"; echo "j-redis: stopped" ;;
restart)
    "$B/dev-jredis.sh" stop && exec "$B/dev-jredis.sh" start ;;     # by path: bash dev-jredis.sh gave a name alone (O-86)
status)
    if running; then echo "j-redis: running, pid $(cat "$L/store.pid"), on 127.0.0.1:$STORE_PORT"
    else echo "j-redis: not running"; exit 1; fi ;;
test)
    taken "$STORE_PORT" || { echo "FAILED  j-redis is not running on 127.0.0.1:$STORE_PORT: $0 start"; exit 1; }
    exec 3<> "/dev/tcp/127.0.0.1/$STORE_PORT"
    # A command as a client sends it, an array of bulk strings, and its reply: a bulk string's value, else its line.
    ask() {
        local a r
        printf '*%d\r\n' $# >&3
        for a in "$@"; do printf '$%d\r\n%s\r\n' "${#a}" "$a" >&3; done
        IFS= read -r -t 5 r <&3 || { echo "(no reply in 5 s)"; return; }
        r=${r%$'\r'}
        if [[ $r == '$'[0-9]* ]]; then IFS= read -r -t 5 r <&3; r=${r%$'\r'}; fi
        echo "$r"
    }
    r=$(ask PING); [ "$r" = "+PONG" ] || { echo "FAILED  PING: $r"; exit 1; }
    echo "ok      PING: PONG"
    key="dev-jredis:test:$$" value="written $(date +%s)"
    r=$(ask SET "$key" "$value" EX 60); [ "$r" = "+OK" ] || { echo "FAILED  SET: $r"; exit 1; }
    r=$(ask GET "$key"); [ "$r" = "$value" ] || { echo "FAILED  GET: $r, not $value"; exit 1; }
    r=$(ask DEL "$key"); [ "$r" = ":1" ] || { echo "FAILED  DEL: $r"; exit 1; }
    echo "ok      a key set, read and deleted" ;;
*)
    echo "usage: $0 build | start | stop | restart | status | test" >&2; exit 2 ;;
esac
