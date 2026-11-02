#!/bin/bash
# The backend for a run by hand (decision D-92, docs/development/03-build-and-run.md §5): the platform, a worker,
# the gateway and an arena, from the release (backend/target/release) on the release's own runtime, against the
# development MySQL's backend_dev (dev-mysql.sh) and j-redis (dev-jredis.sh), which their own scripts start. Each
# listens on 127.0.0.1; the arena tells clients to dial nginx (dev-nginx.sh), PUBLIC_ADDRESS on NGINX_MATCH_PORT,
# TLS as ARENA_TLS_NAME, as the site's arenas do. Their logs, pids and files are in work/run.
#
#   ./dev-backend.sh build                                   its tests, then the release (SKIP_TESTS=1: no tests)
#   ./dev-backend.sh start [platform worker gateway arena]   all four unless some are named
#   ./dev-backend.sh stop [process ...] | restart [process ...]
#   ./dev-backend.sh status
#   ./dev-backend.sh test                  two bots through nginx, as a phone's app: the API, the lobby, a match
#   ./dev-backend.sh bots [count] [seconds]   bots play (20 for 15 s): the API and the lobby straight, matches through nginx
#
# Ports, each tried before the start (in use, or refused by a policy of the machine, it is named, D-88), and the
# address, in dev-env.sh: PLATFORM_PORT 8080, GATEWAY_PORT 8081, ARENA_PORT 9021. The bots dial nginx on 127.0.0.1
# and check every certificate against its root, work/nginx/ca/ca.pem, for the name a grant gives. DEBUG=1: each
# listens for a debugger, waiting for no one, the platform on 5005, the worker 5006, the gateway 5007, the arena 5008;
# it then runs on vendor/'s JDK, as the release's runtime has no debugger agent. PLAIN=1 (D-101): the arena announces
# nginx's port without TLS, and test and bots go through nginx plain, as an app built for it does.
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
L=$WORK/run
export BACKEND_DB_URL=${BACKEND_DB_URL:-"jdbc:mysql://127.0.0.1:$MYSQL_PORT/backend_dev?useSSL=false&allowPublicKeyRetrieval=true"}
export BACKEND_DB_USER=${BACKEND_DB_USER:-backend} BACKEND_DB_PASSWORD=${BACKEND_DB_PASSWORD:-backend-dev-password}
ALL="platform worker gateway arena"
declare -A DEBUG_PORT=([platform]=5005 [worker]=5006 [gateway]=5007 [arena]=5008)
declare -A PORT=([platform]=$PLATFORM_PORT [gateway]=$GATEWAY_PORT [arena]=$ARENA_PORT)

R=$(ls -d "$B"/backend/target/release/backend-*/ 2> /dev/null | head -1 || true); R=${R%/}
# Running, and ours: a pid left from before a reboot may be another process's by now, another of the four's
# among them, so the process's own jars (O-86). No pipe: under pipefail, a reader that ends first fails the
# writer, and so the test.
running() {
    local pid cmd
    pid=$(cat "$L/$1.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$B/backend/target/release/"*"/lib/$1/"* ]]
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
# The processes named that are not running: what they need first, then their ports. The store for all four, the
# MySQL for the platform and the worker, each named with the script that starts it; a port in use, or one a policy
# of the machine refuses, named with its cause (D-88).
check_start() {
    local p todo=() ports=()
    for p in "$@"; do running "$p" || todo+=("$p"); done
    [ ${#todo[@]} -gt 0 ] || return 0
    taken "$STORE_PORT" || { echo "j-redis is not running on 127.0.0.1:$STORE_PORT: ./dev-jredis.sh start first" >&2; exit 1; }
    case " ${todo[*]} " in *" platform "*|*" worker "*)
        "$B/dev-mysql.sh" status > /dev/null 2>&1 \
            || { echo "the development MySQL is not running: ./dev-mysql.sh start first" >&2; exit 1; } ;;
    esac
    for p in "${todo[@]}"; do [ -z "${PORT[$p]:-}" ] || ports+=("${PORT[$p]}"); done
    [ ${#ports[@]} = 0 ] || "$B/backend/scripts/check-bind.sh" "${ports[@]}" || {
        echo "another port for it: PLATFORM_PORT, GATEWAY_PORT or ARENA_PORT, the same for every later command" >&2; exit 1; }
}

start_one() {
    local p=$1 port args java envs=() said=
    if running "$p"; then echo "$p: already running"; return; fi
    case $p in
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
                  # spool, bind host, advertised host (backend/README, Running it); nginx's port and name for it
                  args=(-XX:+UseZGC -XX:+ZGenerational -cp "$R/lib/arena/*" com.backend.arena.ArenaMain
                        "$ARENA_PORT" 5700 150 1500 4 127.0.0.1 "$STORE_PORT" arena-1 0 "$L/spool" 127.0.0.1 "$PUBLIC_ADDRESS")
                  envs=("BACKEND_ADVERTISE_PORT=$NGINX_MATCH_PORT")
                  if [ "$PLAIN" = 1 ]; then
                      said=", announcing $PUBLIC_ADDRESS:$NGINX_MATCH_PORT, plain, through nginx"   # no TLS name (D-101)
                  else
                      envs+=("BACKEND_ADVERTISE_TLS_NAME=$ARENA_TLS_NAME")
                      said=", announcing $PUBLIC_ADDRESS:$NGINX_MATCH_PORT, TLS as $ARENA_TLS_NAME, through nginx"
                  fi ;;
        *)        echo "no process $p: $ALL" >&2; exit 2 ;;
    esac
    java=("$R/runtime/bin/java")
    [ "${DEBUG:-0}" = 1 ] && java=("$JAVA_HOME/bin/java"
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${DEBUG_PORT[$p]}")
    mkdir -p "$L/spool"
    nohup env "${envs[@]}" "${java[@]}" --add-opens java.base/java.nio=ALL-UNNAMED "${args[@]}" > "$L/$p.log" 2>&1 &
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
    echo "$p: up${port:+ on 127.0.0.1:$port}$said$dbg (log $L/$p.log)"
}

# Stopped, or a failure: the arena drains its made matches first, up to its unit's twelve minutes (01 §8.6), and
# a stop that gave up at 30 s said so and went on, a restart then finding it running (O-86).
stop_one() {
    local p=$1 wait=30
    running "$p" || { echo "$p: not running"; return 0; }
    [ "$p" = arena ] && wait=720
    kill "$(cat "$L/$p.pid")"
    for i in $(seq "$wait"); do
        running "$p" || break
        [ "$i" = 5 ] && [ "$p" = arena ] && echo "arena: draining its made matches, up to 12 minutes"
        sleep 1
    done
    if running "$p"; then echo "$p: still running after $wait s, pid $(cat "$L/$p.pid")" >&2; return 1; fi
    rm -f "$L/$p.pid"; echo "$p: stopped"
}

# Refuses a name that is not one of the four; run in the script's own shell, so that its exit is the script's.
known() {
    local p
    for p in "$@"; do case " $ALL " in *" $p "*) ;; *) echo "no process $p: $ALL" >&2; exit 2 ;; esac; done
}
# The processes named, in the order $1 gives (start's or stop's); all of them when none is.
named() {
    local order=$1 p; shift
    for p in $order; do case " ${*:-$ALL} " in *" $p "*) echo "$p" ;; esac; done
}
STOP_ORDER="arena gateway worker platform"

# The bots, as a client: through nginx, on 127.0.0.1, every certificate checked against nginx's root (D-92).
bots() {
    local jar ts=$L/truststore.p12 said
    jar=$(ls "$B"/backend/tools/target/tools-*-all.jar 2> /dev/null | head -1 || true)
    [ -n "$jar" ] || { echo "no backend/tools/target/tools-*-all.jar: $0 build first" >&2; return 1; }
    # Plain (D-101): nothing to trust.
    [ "$PLAIN" != 1 ] || { BACKEND_BOT_VIA=127.0.0.1:$NGINX_MATCH_PORT "$JAVA_HOME/bin/java" -cp "$jar" com.backend.tools.BotClient "$@"; return; }
    rm -f "$ts"
    said=$("$JAVA_HOME/bin/keytool" -importcert -noprompt -alias backend-dev -file "$WORK/nginx/ca/ca.pem" -keystore "$ts" \
        -storetype PKCS12 -storepass backend-dev 2>&1) || { echo "$said" >&2; return 1; }
    BACKEND_BOT_TRUSTSTORE=$ts BACKEND_BOT_TRUSTSTORE_PASSWORD=backend-dev BACKEND_BOT_VIA=127.0.0.1:$NGINX_MATCH_PORT \
        "$JAVA_HOME/bin/java" -Djavax.net.ssl.trustStore="$ts" -Djavax.net.ssl.trustStoreType=PKCS12 \
        -Djavax.net.ssl.trustStorePassword=backend-dev -cp "$jar" com.backend.tools.BotClient "$@"
}
nginx_up() { "$B/dev-nginx.sh" status > /dev/null 2>&1; }
# nginx started this command's way, plain or TLS: sudo drops PLAIN from the shell (D-98), and nginx the other way
# passes no match on (D-101).
nginx_way() {
    local want=TLS has; [ "$PLAIN" != 1 ] || want=plain
    has=$(cat "$WORK/nginx/mode" 2> /dev/null || echo TLS)
    [ "$has" = "$want" ] || { echo "nginx runs $has, and this command $want: give both the same PLAIN (sudo drops it: sudo PLAIN=$PLAIN ./dev-nginx.sh restart)"; return 1; }
}

case "${1:-}" in
build)
    TESTS=$([ "${SKIP_TESTS:-0}" = 1 ] && echo "-DskipTests" || true)
    ls "$B"/j-redis-service/j-redis-server/target/j-redis-server-*-all.jar > /dev/null 2>&1 || {
        echo "j-redis is not built, and the backend needs its client, the release its server: ./dev-jredis.sh build first" >&2
        exit 1; }
    # The tests' MySQL is the development one, started for them and stopped after when it was not running.
    if [ -z "$TESTS" ] && [ "$JDBC_URL" = "$DEV_JDBC_URL" ] && ! "$B/dev-mysql.sh" status > /dev/null; then
        "$B/dev-mysql.sh" start
        trap '"$B/dev-mysql.sh" stop' EXIT
    fi
    MVN="mvn -B -q"       # dev-env.sh's MAVEN_ARGS name the settings
    echo "--- backend"
    (cd "$B/backend" && $MVN clean install $TESTS)
    # The release builds offline (-o) from the local repository: what it runs that the build above does not
    # (the clean plugin there, the dependency plugin here) is fetched from the bundle first.
    (cd "$B/backend" && $MVN -pl common dependency:copy-dependencies -DoutputDirectory="$WORK/probe" > /dev/null)
    echo "--- release"
    (cd "$B/backend" && MVN="$MVN" scripts/make-release.sh)
    echo "--- done: $(ls -d "$B"/backend/target/release/backend-*/ | head -1)" ;;
start)
    shift; known "$@"
    [ -n "$R" ] || { echo "no release in backend/target/release: $0 build first" >&2; exit 1; }
    mkdir -p "$L"
    procs=$(named "$ALL" "$@")
    check_start $procs
    for p in $procs; do start_one "$p"; done ;;
stop)
    shift; known "$@"
    rc=0
    for p in $(named "$STOP_ORDER" "$@"); do stop_one "$p" || rc=1; done
    exit $rc ;;
restart)
    shift; known "$@"
    [ -n "$R" ] || { echo "no release in backend/target/release: $0 build first" >&2; exit 1; }
    for p in $(named "$STOP_ORDER" "$@"); do stop_one "$p" || exit 1; done
    mkdir -p "$L"
    procs=$(named "$ALL" "$@")
    check_start $procs
    for p in $procs; do start_one "$p"; done ;;
status)
    down=0
    for p in $ALL; do
        if running "$p"; then echo "$p: running, pid $(cat "$L/$p.pid")"; else echo "$p: not running"; down=1; fi
    done
    exit $down ;;
test)
    down=0
    for p in $ALL; do running "$p" || { echo "FAILED  $p is not running: $0 start"; down=1; }; done
    [ $down = 0 ] || exit 1
    echo "ok      platform, worker, gateway and arena running"
    nginx_up || { echo "FAILED  nginx is not running: ./dev-nginx.sh start"; exit 1; }
    said=$(nginx_way) || { echo "FAILED  $said"; exit 1; }
    echo "ok      nginx running"
    match=$PUBLIC_ADDRESS:$NGINX_MATCH_PORT
    if [ "$PLAIN" = 1 ]; then
        api=http://127.0.0.1$([ "$NGINX_HTTP_PORT" = 80 ] || echo ":$NGINX_HTTP_PORT")
        lobby=ws://${api#http://}/lobby
        how="plain through nginx, $api/: registered, logged in, the lobby, a ticket for $match, plain"
    else
        api=https://127.0.0.1$([ "$NGINX_HTTPS_PORT" = 443 ] || echo ":$NGINX_HTTPS_PORT")
        lobby=wss://${api#https://}/lobby
        how="through nginx, $api/: registered, logged in, the lobby, a ticket for $match, TLS as $ARENA_TLS_NAME"
    fi
    out=$(bots 2 5 "$api" "$lobby" 2>&1) || true
    if grep -q -E '^lobby: 2 of 2 bots' <<< "$out" && grep -q -F "2 bots connected to $match" <<< "$out" \
        && grep -q -E '^welcomes 2 +disconnects 0 +kicks 0 .*lobby failures 0$' <<< "$out" && grep -q -E '^stays 2,' <<< "$out"; then
        echo "ok      2 bots $how, 5 s played and left"
    else
        echo "FAILED  2 bots ${how%%:*}; they said:"; sed 's/^/        /' <<< "$out"; exit 1
    fi ;;
bots)
    nginx_up || { echo "nginx is not running, and matches go through it: ./dev-nginx.sh start first" >&2; exit 1; }
    said=$(nginx_way) || { echo "$said" >&2; exit 1; }
    bots "${2:-20}" "${3:-15}" "http://127.0.0.1:$PLATFORM_PORT" "ws://127.0.0.1:$GATEWAY_PORT/lobby" ;;
*)
    echo "usage: $0 build | start [process ...] | stop [process ...] | restart [process ...] | status | test | bots [count] [seconds]" >&2
    exit 2 ;;
esac
