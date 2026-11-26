#!/bin/bash
# The account service, for a run by hand (D-117, decision D-92's way, docs/development/03-build-and-run.md §5): from its
# own build (account-service/target), on vendor/'s JDK, listening on 127.0.0.1:$ACCOUNT_PORT (8083) alone, on the
# development MySQL's account_dev, its log work/run/account.log. Its signing key is made once, by
# account-service/scripts/account-key.sh, into work/account/signing.key; its issuer is http://127.0.0.1:$ACCOUNT_PORT,
# which dev-backend.sh gives the platform; its events go to the platform on $PLATFORM_PORT. DEBUG=1: a debugger on
# 127.0.0.1:5010, waiting for no one.
#
#   ./dev-account.sh build      account-service compiled, tested on account_test, installed in work/m2 (SKIP_TESTS=1: no tests)
#   ./dev-account.sh start | stop | restart | status
#   ./dev-account.sh test       it answers: its health, its key set, and an unknown name's login refused
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
L=$WORK/run
A=$B/account-service
K=$WORK/account/signing.key
jar() { ls "$A"/target/account-service-*.jar 2> /dev/null | grep -v -- '-sources' | head -1 || true; }
# Running, and ours: a pid left from before a reboot may be another process's by now.
running() {
    local pid cmd
    pid=$(cat "$L/account.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$A/target/account-service-"* ]]   # its own build's: not a drill's account service (O-86)
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
api() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$@"; }

case "${1:-}" in
build)
    TESTS=$([ "${SKIP_TESTS:-0}" = 1 ] && echo "-DskipTests" || true)
    # The tests use the development MySQL's account_test (dev-mysql.sh makes it), started for them if it is not running.
    [ -n "$TESTS" ] || "$B/dev-mysql.sh" start > /dev/null
    echo "--- account-service"
    (cd "$A" && mvn -B -q clean install $TESTS)
    echo "the account service: built, $(jar)" ;;
start)
    j=$(jar)
    [ -n "$j" ] && [ -d "$A/target/lib" ] || { echo "the account service is not built: $0 build first" >&2; exit 1; }
    if running; then echo "the account service: already running, pid $(cat "$L/account.pid")"; exit 0; fi
    taken "$MYSQL_PORT" || { echo "the development MySQL is not running: ./dev-mysql.sh start first" >&2; exit 1; }
    # In use, or refused by a policy of the machine: named before anything starts (D-88)
    "$B/backend/scripts/check-bind.sh" "$ACCOUNT_PORT" \
        || { echo "another port for it: ACCOUNT_PORT=<a free port>, the same for every later command" >&2; exit 1; }
    if [ ! -s "$K" ]; then
        mkdir -p "$(dirname "$K")"
        (umask 077 && "$A/scripts/account-key.sh" | sed -n 's/^ACCOUNT_SIGNING_KEY=//p' > "$K")
        echo "the account service: a signing key made, $K"
    fi
    mkdir -p "$L"
    java=("$JAVA_HOME/bin/java")
    [ "${DEBUG:-0}" = 1 ] && java+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5010")
    nohup env ACCOUNT_PORT="$ACCOUNT_PORT" ACCOUNT_SIGNING_KEY_FILE="$K" ACCOUNT_ISSUER="http://127.0.0.1:$ACCOUNT_PORT" \
        ACCOUNT_EVENTS_URL="http://127.0.0.1:$PLATFORM_PORT/v1/account-events" \
        "${java[@]}" -cp "$j:$A/target/lib/*" com.backend.account.AccountMain > "$L/account.log" 2>&1 &
    echo $! > "$L/account.pid"
    for _ in $(seq 60); do running || break; [ "$(api "http://127.0.0.1:$ACCOUNT_PORT/v1/health")" = 200 ] && break; sleep 1; done
    running && [ "$(api "http://127.0.0.1:$ACCOUNT_PORT/v1/health")" = 200 ] \
        || { echo "the account service did not come up; its log, $L/account.log, ends:" >&2; tail -15 "$L/account.log" >&2; exit 1; }
    dbg=; [ "${DEBUG:-0}" = 1 ] && dbg=", its debugger on 127.0.0.1:5010"
    echo "the account service: up on 127.0.0.1:$ACCOUNT_PORT, issuer http://127.0.0.1:$ACCOUNT_PORT$dbg (log $L/account.log)" ;;
stop)
    running || { echo "the account service: not running"; exit 0; }
    kill "$(cat "$L/account.pid")"
    for _ in $(seq 30); do running || break; sleep 1; done
    if running; then echo "the account service: still running after 30 s, pid $(cat "$L/account.pid")" >&2; exit 1; fi
    rm -f "$L/account.pid"; echo "the account service: stopped" ;;
restart)
    "$B/dev-account.sh" stop && exec "$B/dev-account.sh" start ;;     # by path, as dev-jredis.sh (O-86)
status)
    if running; then echo "the account service: running, pid $(cat "$L/account.pid"), on 127.0.0.1:$ACCOUNT_PORT"
    else echo "the account service: not running"; exit 1; fi ;;
test)
    u=http://127.0.0.1:$ACCOUNT_PORT
    r=$(api "$u/v1/health"); [ "$r" = 200 ] || { echo "FAILED  its health: $r ($0 start?)"; exit 1; }
    echo "ok      its health: 200"
    keys=$(curl -s --max-time 5 "$u/.well-known/jwks.json")
    [[ $keys == *'"kty":"EC"'* && $keys == *'"crv":"P-256"'* && $keys != *'"d":'* ]] \
        || { echo "FAILED  its key set: $keys"; exit 1; }
    echo "ok      its key set: a public P-256 key"
    r=$(api -X POST -H 'Content-Type: application/json' -d '{"username":"nobody-dev-test","password":"not-a-password"}' "$u/v1/sessions")
    [ "$r" = 401 ] || { echo "FAILED  an unknown name's login: $r, not 401"; exit 1; }
    echo "ok      an unknown name's login refused: 401" ;;
*)
    echo "usage: $0 build | start | stop | restart | status | test" >&2; exit 2 ;;
esac
