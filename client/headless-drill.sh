#!/bin/bash
# The client's core, headless, against the real stack from a release: j-redis, platform, an
# arena and a worker, on ports of their own. Stops everything on the way out (docs 08 §6).
#
#   client/headless-drill.sh [scenario ...]          TLS=1 for an arena serving TLS
#   DRAIN_AFTER=<s> client/headless-drill.sh duel     stops the arena <s> seconds in: it drains
#   FAILOVER=1 client/headless-drill.sh play          the store with a replica (port 6392): the
#                                                     scenarios, the primary killed, the replica
#                                                     promoted by promote-store.sh, the scenarios
#                                                     again against the same processes (D-34);
#                                                     FAILOVER=demote for a planned handover, the old
#                                                     primary made a replica of the new by the script
#                                                     and alive, and every process's subscription
#                                                     looked for on the new primary (O-6)
#   MYSQL_FAILOVER=1 client/headless-drill.sh play duel  two MySQL servers of the drill's own (3307,
#                                                     3308, GTIDs, the replica 30 s behind), the
#                                                     primary killed, the replica promoted by
#                                                     promote-mysql.sh, the scenarios again against
#                                                     the same processes (D-35); MYSQL_FAILOVER=freeze
#                                                     stops the primary instead (SIGSTOP: TCP accepted,
#                                                     nothing answered, as a machine gone), and times
#                                                     platform's first answer after (O-9); =demote
#                                                     for a planned handover, the old primary alive
#                                                     and fenced by the script (D-29). Either way the
#                                                     old primary is then rebuilt by clone and handed
#                                                     back to, and the scenarios run a third time
#   BOTS=<n>[:<s>] client/headless-drill.sh           the load run (07 §4, plan item 14): n bots of
#                                                     tools/BotClient instead of the scenarios, playing
#                                                     <s> seconds (120) once all are in; each process's
#                                                     cores over that window, and the arena's numbers.
#                                                     ARENA_OPTS adds to the arena's JVM options, such
#                                                     as a flight recording
#   BOTS=<n>:<s> FAILOVER=1 client/headless-drill.sh  the failover under load (architecture/02 §6):
#                                                     the primary killed halfway through the bots'
#                                                     window, the bots leaving while it is down, the
#                                                     replica promoted 20 s after they have left; every
#                                                     bot's stay paid once, and how long a probe was
#                                                     refused. MYSQL_FAILOVER=1 the same for MySQL, and
#                                                     MYSQL_FAILOVER=demote the handover at half-window
#   BOTS=<n>:<s> SOAK=1 client/headless-drill.sh      the soak (07 §4, plan item 47): the bots' stays
#                                                     end and they play again (STAYS=<min>:<max> s,
#                                                     60:300), the duel scenario every 30 s, every
#                                                     process sampled every SAMPLE_EVERY s (300), and
#                                                     tools/SoakJudge judging from JUDGE_FROM s (half;
#                                                     past 900, the bots' login throttles' life)
#   BOTS=<n>:<s> WORKERS=<k> client/headless-drill.sh the workers' rate (plan item 26): k workers ready,
#                                                     paused while the bots play, resumed together once
#                                                     they have left; the backlog's stays timed
#   TAKEOVER=1 client/headless-drill.sh play          the worker killed with the results in flight,
#                                                     and a second worker taking them over (05);
#                                                     with play, as the others check their pay
#                                                     while the worker is stopped, and fail that
#
# Needs: the backend release (backend/scripts/make-release.sh; RELEASE= for a copy of one), the
# headless client built (dotnet build), python3 and the MySQL the backend's tests use. A release
# carries its own runtime, j-redis and MySQL (docs/detailed-design/09 §4), and the drill takes them
# from it, its own MySQL servers included; JAVA, JREDIS_JAR, JREDIS_CLI_JAR and MYSQLD_BIN name
# others. An older release without them: a JDK 21 (/opt/jdk21), j-redis-service built, and
# /usr/sbin/mysqld. Ports: 6390 j-redis, 8093 platform, 8094 gateway,
# 9011 arena, 9195 platform's metrics, 9196 platform's admin API, 9197 the arena's metrics,
# 9198 the gateway's metrics, 9199 the worker's metrics.
set -u
C="$(cd "$(dirname "$0")" && pwd)"
B="$(dirname "$C")"
R=${RELEASE:-$B/backend/target/release/backend-0.1.0-SNAPSHOT}
# The release's own runtime, store and MySQL when it carries them (docs/detailed-design/09 §4); a
# release made before them, the machine's.
if [ -d "$R/jredis" ]; then
  JREDIS=${JREDIS_JAR:-$R/jredis/lib/j-redis-server.jar}; JREDIS_CLI=${JREDIS_CLI_JAR:-$R/jredis/lib/j-redis-cli.jar}
else
  JREDIS=${JREDIS_JAR:-$B/j-redis-service/j-redis-server/target/j-redis-server-2.2.1-all.jar}
  JREDIS_CLI=${JREDIS_CLI_JAR:-$B/j-redis-service/j-redis-cli/target/j-redis-cli-2.2.1-all.jar}
fi
if [ -x "$R/runtime/bin/java" ]; then J=${JAVA:-$R/runtime/bin/java}; else J=${JAVA:-/opt/jdk21/bin/java}; fi
if [ -x "$R/mysql/bin/mysqld" ]; then MYSQLD_BIN=${MYSQLD_BIN:-$R/mysql/bin/mysqld}; PATH="$R/mysql/bin:$PATH"
else MYSQLD_BIN=${MYSQLD_BIN:-/usr/sbin/mysqld}; fi
DOTNET=${DOTNET:-/opt/dotnet/dotnet}
D=$(mktemp -d); mkdir -p "$D/data" "$D/spool"
echo "logs in $D"
# The store's jars copied, and the tools' below: a build beside a long drill deletes its target
# directory, and every sample of the store starts the command-line client afresh (T-31).
cp "$JREDIS" "$JREDIS_CLI" "$D/"; JREDIS=$D/${JREDIS##*/}; JREDIS_CLI=$D/${JREDIS_CLI##*/}
PIDS=()
cleanup() { for p in "${PIDS[@]:-}" ${REPLICA_PID:-} ${MYSQL_PIDS[@]:-}; do kill "$p" 2>/dev/null; done; sleep 2; for p in "${PIDS[@]:-}" ${REPLICA_PID:-} ${MYSQL_PIDS[@]:-}; do kill -9 "$p" 2>/dev/null; done; }
trap cleanup EXIT
# NFR-2's p50 and p95 per profile from a scrape of the arena: a histogram's buckets come in its
# labels' string order, so they are sorted by their bound first (02 §13).
nfr2_quantiles() {
  local f="$1" p
  for p in $(grep -o 'connection_bytes_per_second_count{profile="[^"]*"' "$f" | sed 's/.*profile="//; s/"$//'); do
    grep "^backend_arena_connection_bytes_per_second_bucket{profile=\"$p\"" "$f" \
      | sed -E 's/.*le="([^"]*)"\} (.*)/\1 \2/; s/^\+Inf/1e99/' | sort -g | awk -v p="$p" \
        -v sum="$(grep "^backend_arena_connection_bytes_per_second_sum{profile=\"$p\"}" "$f" | awk '{ print $NF }')" '
        { L[NR] = $1; C[NR] = $2 }
        function q(x,   t, i, prev, lo) {
          t = x * C[NR]; prev = 0; lo = 0
          for (i = 1; i <= NR; i++) {
            if (C[i] >= t) return L[i] >= 1e99 ? "over " lo : sprintf("%.0f", lo + (L[i] - lo) * (t - prev) / (C[i] - prev))
            prev = C[i]; lo = L[i]
          }
        }
        END { if (C[NR] > 0) printf "  %s: %d connections, mean %.0f, p50 %s, p95 %s (NFR-2: about 4 200 down and up together)\n", p, C[NR], sum / C[NR], q(0.5), q(0.95) }'
  done
}
OPENS="--add-opens java.base/java.nio=ALL-UNNAMED"
cli() { printf '%s\n' "$@" | $J -jar "$JREDIS_CLI" --raw -p 6390 | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d'; }

# TLS=1: the arena serves a test certificate naming 127.0.0.1, and the client trusts only it.
TLSENV=(); TRUST=()
if [ "${TLS:-0}" = 1 ]; then
  K="$(dirname "$J")/keytool"; T="$D/tls"; mkdir -p "$T"; chmod 700 "$T"
  $K -genkeypair -alias arena -keyalg EC -groupname secp256r1 -dname CN=arena-drill -ext san=ip:127.0.0.1 \
     -validity 2 -storetype PKCS12 -keystore "$T/arena.p12" -storepass drill-pass -keypass drill-pass 2>/dev/null
  $K -exportcert -alias arena -keystore "$T/arena.p12" -storepass drill-pass -file "$T/arena.crt" 2>/dev/null
  $K -genkeypair -alias other -keyalg EC -groupname secp256r1 -dname CN=someone-else -ext san=ip:127.0.0.1 \
     -validity 2 -storetype PKCS12 -keystore "$T/other.p12" -storepass other-pass 2>/dev/null
  $K -exportcert -alias other -keystore "$T/other.p12" -storepass other-pass -file "$T/other.crt" 2>/dev/null
  printf 'drill-pass\n' > "$T/arena-pass"
  TLSENV=(env BACKEND_ARENA_TLS_KEYSTORE="$T/arena.p12" BACKEND_ARENA_TLS_PASSWORD_FILE="$T/arena-pass")
  TRUST=(--trust "$T/arena.crt" --wrong-trust "$T/other.crt")
fi

DBPORT=3306
FAILOVER=${FAILOVER:-0}
case $FAILOVER in 0|1|demote) ;; *) echo "FAILOVER is 1 or demote"; exit 2 ;; esac
[ "$FAILOVER" = demote ] && [ -n "${BOTS:-}" ] && { echo "FAILOVER=demote runs the scenarios, not the bots"; exit 2; }
MYSQL_FAILOVER=${MYSQL_FAILOVER:-0}
case $MYSQL_FAILOVER in 0|1|demote|freeze) ;; *) echo "MYSQL_FAILOVER is 1, demote or freeze"; exit 2 ;; esac
[ "$MYSQL_FAILOVER" = freeze ] && [ -n "${BOTS:-}" ] && { echo "MYSQL_FAILOVER=freeze runs the scenarios, not the bots"; exit 2; }
if [ "$MYSQL_FAILOVER" != 0 ]; then
  # MySQL's primary and its GTID replica, servers of the drill's own; the backend given both hosts,
  # the replica first, so that choosing the writable one is what is tested (D-35). The replica is
  # read-only from its command line, as from its configuration in production, and applies 30 s
  # late, so that a promotion finds transactions received and not yet applied, and waits longer
  # than any one statement is given (D-30).
  mysql_run() {      # mysql_run <name> <port> <server-id> [option...]: prints the server's pid
    local dir=$D/$1 port=$2 id=$3
    shift 3
    : > "$dir/my.cnf"                                # not --no-defaults: it skips what SET PERSIST wrote
    ( cd "$dir/data" && exec "$MYSQLD_BIN" --defaults-file="$dir/my.cnf" --datadir="$dir/data" --user=root --port="$port" \
        --bind-address=127.0.0.1 --socket=m.sock --mysqlx=OFF --server-id="$id" --log-bin=bin --gtid-mode=ON \
        --enforce-gtid-consistency=ON --plugin-load-add=mysql_clone.so --log-error="$dir/error.log" "$@" ) \
        > /dev/null 2>&1 &
    echo $!
  }
  mysql_start() {    # mysql_start <name> <port> <server-id> [option...]: a new server; prints its pid
    mkdir -p "$D/$1"
    "$MYSQLD_BIN" --no-defaults --initialize-insecure --datadir="$D/$1/data" --user=root > "$D/$1/init.log" 2>&1 \
      || { echo "cannot initialize MySQL $1: see $D/$1/init.log" >&2; return 1; }
    mysql_run "$@"
  }
  msql() { mysql --protocol=TCP -h 127.0.0.1 -P "$1" -u root -N -B -e "$2"; }
  mysql_ready() { for i in $(seq 1 60); do msql "$1" "SELECT 1" > /dev/null 2>&1 && return 0; sleep 1; done; return 1; }
  MYSQL_PIDS=("$(mysql_start mysql-a 3307 1)" "$(mysql_start mysql-c 3308 2 --super-read-only=ON)")
  mysql_ready 3307; mysql_ready 3308
  msql 3307 "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl-drill'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
             CREATE USER 'backend'@'%' IDENTIFIED BY 'backend-dev-password'; CREATE DATABASE backend_dev;
             GRANT ALL ON backend_dev.* TO 'backend'@'%';
             CREATE USER 'failover'@'%' IDENTIFIED BY 'failover-drill';
             GRANT SYSTEM_VARIABLES_ADMIN, CONNECTION_ADMIN, REPLICATION_SLAVE_ADMIN, RELOAD ON *.* TO 'failover'@'%';
             GRANT SELECT ON performance_schema.* TO 'failover'@'%';
             CREATE USER 'clone'@'%' IDENTIFIED BY 'clone-drill'; GRANT BACKUP_ADMIN ON *.* TO 'clone'@'%'"
  follow() {         # follow <replica port> <primary port>: replicate by GTIDs, over TLS, as 01 §9 does
    msql "$1" "CHANGE REPLICATION SOURCE TO SOURCE_HOST='127.0.0.1', SOURCE_PORT=$2, SOURCE_USER='repl',
               SOURCE_PASSWORD='repl-drill', SOURCE_AUTO_POSITION=1, SOURCE_SSL=1; START REPLICA"
  }
  follow 3308 3307
  # Caught up with the set-up first, its accounts among it, as an established replica is; then
  # 30 s behind from here on.
  msql 3308 "SELECT WAIT_FOR_EXECUTED_GTID_SET('$(msql 3307 "SELECT @@global.gtid_executed")', 60)" > /dev/null
  msql 3308 "STOP REPLICA SQL_THREAD; CHANGE REPLICATION SOURCE TO SOURCE_DELAY=30; START REPLICA SQL_THREAD"
  # The script's own account, with the grants operations/01 §9 gives it; this server by TCP, as
  # the drill's have sockets too long a path to name.
  printf '[client]\nuser=failover\npassword=failover-drill\nprotocol=TCP\nhost=127.0.0.1\nport=3308\n' \
    > "$D/mysql-failover.cnf"
  chmod 600 "$D/mysql-failover.cnf"
  export BACKEND_DB_URL="jdbc:mysql://127.0.0.1:3308,127.0.0.1:3307/backend_dev?useSSL=false&allowPublicKeyRetrieval=true"
  export BACKEND_DB_USER=backend BACKEND_DB_PASSWORD=backend-dev-password
  echo "--- MySQL: primary 3307, replica 3308 ($(msql 3308 "SELECT SERVICE_STATE FROM performance_schema.replication_applier_status"), read-only $(msql 3308 "SELECT @@global.super_read_only"))"
fi
if [ "$FAILOVER" != 0 ]; then
  # A primary and its replica, each from a config file as in production; every process given both.
  mkdir -p "$D/data2"
  printf 'port 6390\nbind 127.0.0.1\ndir %s\n' "$D/data" > "$D/primary.conf"
  printf 'port 6392\nbind 127.0.0.1\ndir %s\nreplicaof 127.0.0.1 6390\n' "$D/data2" > "$D/replica.conf"
  $J -jar "$JREDIS" "$D/primary.conf" > "$D/jredis.log" 2>&1 & PIDS+=($!)
  sleep 2
  $J -jar "$JREDIS" "$D/replica.conf" > "$D/jredis-replica.log" 2>&1 & REPLICA_PID=$!
  export BACKEND_STORE_ADDRESSES=127.0.0.1:6390,127.0.0.1:6392
else
  $J -jar "$JREDIS" --port 6390 --dir "$D/data" > "$D/jredis.log" 2>&1 & PIDS+=($!)
fi
sleep 2
# The load run's arena has a room of 150 for every 150 bots, and one to spare; and every process
# the collector the units give it (deploy/systemd), with a heap this shared machine can spare.
ROOMS=2; SHAPES=200; [ -n "${BOTS:-}" ] && ROOMS=$(( ${BOTS%%:*} / 150 + 1 )) && SHAPES=1500   # as arena.env.example
[ "${SOAK:-0}" = 1 ] && ROOMS=$(( ROOMS + 2 ))   # the soak's duels: one playing, one ending (T-30)
[ -n "${BOTS:-}" ] && OPENS="$OPENS -XX:+UseZGC -XX:+ZGenerational -Xmx2g"
BACKEND_METRICS_ADDR=127.0.0.1:9197 \
"${TLSENV[@]}" $J $OPENS ${ARENA_OPTS:-} -cp "$R/lib/arena/*" com.backend.arena.ArenaMain 9011 5700 150 $SHAPES $ROOMS 127.0.0.1 6390 \
   arena-drill 0 "$D/spool" 127.0.0.1 127.0.0.1 > "$D/arena.log" 2>&1 & PIDS+=($!)
# The equip, boost and levels scenarios want items whose effect a stay measures exactly: a barrel giving 25 %
# bullet speed and a coins boost, for 1 coin each. They are added to the release's shop and item table, read from
# its platform jar, and the result put ahead of them on the classpath, for those scenarios alone; the release's
# offers stay on sale beside them, for gems and the rest of the run (T-41).
CONTENT=""
if [[ " $* " == *" equip "* || " $* " == *" boost "* || " $* " == *" levels "* ]]; then
  mkdir -p "$D/content"
  python3 - "$(ls "$R"/lib/platform/platform-*.jar)" "$D/content" <<'EOF' || { echo "the drill's content: not written"; exit 2; }
import json, sys, zipfile
jar, out = sys.argv[1], sys.argv[2]
drill = {"shop.json": [{"sku": "drill_barrel", "itemId": "drill_barrel", "price": 1},
                       {"sku": "drill_boost", "itemId": "drill_boost", "price": 1}],
         "items.json": [{"id": "drill_barrel", "type": "EQUIPMENT", "slot": "barrel",
                         "modifiers": [{"stat": "bullet_speed", "percent": 25}]},
                        {"id": "drill_boost", "type": "BOOST", "kind": "coins", "percent": 100, "minutes": 60}]}
with zipfile.ZipFile(jar) as z:
    for name, extra in drill.items():
        with open(f"{out}/{name}", "w") as f:
            json.dump(json.loads(z.read(name)) + extra, f)
EOF
  CONTENT="$D/content:"
fi
BACKEND_ADMIN_ADDR=127.0.0.1:9196 BACKEND_ADMIN_TOKEN=drill-admin-secret BACKEND_METRICS_ADDR=127.0.0.1:9195 BACKEND_PAYMENT_PROVIDER=simulated $J $OPENS -cp "$CONTENT$R/lib/platform/*" com.backend.platform.PlatformMain 127.0.0.1 8093 127.0.0.1 6390 > "$D/platform.log" 2>&1 & PIDS+=($!)
BACKEND_METRICS_ADDR=127.0.0.1:9198 $J $OPENS -cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain 127.0.0.1 8094 http://127.0.0.1:8093 127.0.0.1 6390 \
   gateway-drill > "$D/gateway.log" 2>&1 & PIDS+=($!)
BACKEND_METRICS_ADDR=127.0.0.1:9199 $J $OPENS -cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 6390 worker-drill > "$D/worker.log" 2>&1 & PIDS+=($!)
for i in $(seq 1 60); do curl -s -o /dev/null http://127.0.0.1:8093/health && break; sleep 1; done
sleep 3
for p in "${PIDS[@]}"; do kill -0 "$p" 2>/dev/null || echo "process $p died during start-up"; done

# The arena's pid is the second started: stopped mid-scenario, it drains (01 §8.6).
if [ -n "${DRAIN_AFTER:-}" ]; then
  ( sleep "$DRAIN_AFTER"; echo "--- stopping the arena ($(date +%T))"; kill -TERM "${PIDS[1]}" ) &
fi

# The worker's pid is the fifth: stopped, so the scenario's results wait in the stream.
if [ "${TAKEOVER:-0}" = 1 ]; then kill -STOP "${PIDS[4]}"; echo "--- worker-drill stopped ($(date +%T))"; fi

# A tournament's entrants have ten rated matches (Q-43): the scenarios give their fresh players and
# teams them as a test's fixture does, straight to the drill's database, its primary of the moment.
# In UTC, as the processes write: a server on local time stamps a fixture hours off (T-46).
drill_sql() {
  local port=$DBPORT; [ "$MYSQL_FAILOVER" != 0 ] && [ "$port" = 3306 ] && port=3307
  printf '#!/bin/sh\nexec mysql -h127.0.0.1 -P%s --protocol=TCP -ubackend -p"%s" backend_dev -N -B -e "SET time_zone = '"'+00:00'"'; $1"\n' \
    "$port" "${BACKEND_DB_PASSWORD:-backend-dev-password}" > "$D/drill-sql.sh"
  chmod +x "$D/drill-sql.sh"
}
run_client() {
  drill_sql
  BACKEND_DRILL_SQL="$D/drill-sql.sh" \
  BACKEND_ADMIN_URL=http://127.0.0.1:9196 BACKEND_ADMIN_TOKEN=drill-admin-secret \
  DOTNET_NOLOGO=1 "$DOTNET" "$C/Headless/bin/Debug/net8.0/Backend.Client.Headless.dll" http://127.0.0.1:8093 --lobby ws://127.0.0.1:8094/lobby "${TRUST[@]}" "$@"
}
# The failovers in steps, so that a load run can put the bots' leaving between the kill and the
# promotion (architecture/02 §6).
store_kill() { kill -9 "${PIDS[0]}"; sleep 1; }
store_promote() {
  JREDIS_CLI="$J -jar $JREDIS_CLI" "$B/backend/scripts/promote-store.sh" "$D/replica.conf" 127.0.0.1:6390 --old-is-down \
    || { echo "FAILOVER FAILED: the promotion"; STATUS=1; }
  PIDS+=("$REPLICA_PID")
  grep -n "replicaof" "$D/replica.conf"
}
store_demote() {
  JREDIS_CLI="$J -jar $JREDIS_CLI" "$B/backend/scripts/promote-store.sh" "$D/replica.conf" 127.0.0.1:6390 \
    --demote-old 127.0.0.1:6392 || { echo "FAILOVER FAILED: the handover"; STATUS=1; }
  PIDS+=("$REPLICA_PID")
}
# Every subscriber on the new primary within 20 s: the gateway's own channel and push:all, and the
# arena's admin channel. A subscriber left on the old one hears nothing published (O-6).
store_listening() {
  local i heard
  for i in $(seq 1 20); do
    heard=$(printf 'PUBSUB NUMSUB push:gateway-drill push:all arena-admin:arena-drill\n' \
      | $J -jar "$JREDIS_CLI" --raw -p 6392 | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | paste -d' ' - - | tr '\n' ' ')
    [ "$(echo "$heard" | grep -o ' 0' | wc -l)" = 0 ] && [ -n "$heard" ] && break
    sleep 1
  done
  echo "  subscribers on the new primary: $heard; the gateway's backend_gateway_store_subscribed $(curl -s http://127.0.0.1:9198/metrics | awk '$1 == "backend_gateway_store_subscribed" { print $2 }')"
  [ "$(echo "$heard" | grep -o ' 0' | wc -l)" = 0 ] && [ -n "$heard" ] || { echo "FAILOVER FAILED: a subscriber is not on the new primary"; STATUS=1; }
}
mfail() { echo "MYSQL FAILOVER FAILED: $*"; STATUS=1; }
promote() {
  MYSQL_FAILOVER_CNF="$D/mysql-failover.cnf" BACKEND_DB_NAME=backend_dev \
    "$B/backend/scripts/promote-mysql.sh" 127.0.0.1:3307 "$1"
}
mysql_prepare() {
  msql 3307 "GRANT SELECT, UPDATE ON backend_dev.ha_epoch TO 'failover'@'%';
             GRANT SELECT ON backend_dev.ha_heartbeat TO 'failover'@'%'"           # the tables exist now
  # The grants reach the replica through replication, held 30 s behind: the script reads there.
  for i in $(seq 1 60); do
    msql 3308 "SHOW GRANTS FOR 'failover'@'%'" | grep -q ha_heartbeat && break
    sleep 1
  done
  BEFORE=$(msql 3307 "SELECT COUNT(*) FROM backend_dev.matches")
}
# The replica as the worker measures it, and a promotion past its limit refused untouched (D-58, O-10).
mysql_watched() {
  local lag up out
  lag=$(curl -s http://127.0.0.1:9199/metrics | awk '$1 == "backend_mysql_replica_lag_seconds{host=\"127.0.0.1:3308\"}" { print $2 }')
  up=$(curl -s http://127.0.0.1:9199/metrics | awk '$1 == "backend_mysql_replica_up{host=\"127.0.0.1:3308\"}" { print $2 }')
  echo "  the replica, as the worker reads it: up $up, $lag s behind (held 30 s behind on purpose)"
  [ "$up" = 1 ] && awk -v l="$lag" 'BEGIN { exit !(l >= 20 && l <= 45) }' || mfail "the worker's measure of the replica: up $up, $lag s"
  out=$(PROMOTE_STALE_SECONDS=10 promote --demote-old 2>&1) && mfail "a replica 30 s behind promoted past a 10 s limit"
  echo "$out" | grep -q "^REFUSED: this replica holds nothing" || mfail "the refusal: $out"
  echo "  $(echo "$out" | head -1)"
  [ "$(msql 3307 "SELECT @@global.super_read_only")" = 0 ] || mfail "the old primary was fenced before the refusal"
}
mysql_kill() {
  promote --old-is-down && mfail "promoted while the old primary was writable"
  kill -9 "${MYSQL_PIDS[0]}"
  sleep 1
}
mysql_promote() {
  if [ "$MYSQL_FAILOVER" != demote ]; then
    promote --old-is-down || mfail "the promotion"
  else
    promote --demote-old || mfail "the promotion"
    [ "$(msql 3307 "SELECT @@global.super_read_only + @@global.offline_mode")" = 2 ] || mfail "the old primary is not fenced"
  fi
}
dbq() { mysql -h127.0.0.1 -P"$1" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -N -B -e "$2" 2>/dev/null; }
# The bots' stays applied since match M0, and by how many players: each bot one stay, left on
# purpose, so one row a bot.
stays() { dbq "$DBPORT" "SELECT COUNT(*), COUNT(DISTINCT mp.player_id) FROM match_player mp JOIN account a ON a.id = mp.player_id
                          WHERE mp.match_id > $M0 AND a.username REGEXP '^bot[0-9]+$'"; }
if [ -n "${BOTS:-}" ]; then
  # The load run: the bots instead of the scenarios. Cores are read from /proc over the window in
  # which every bot plays, for each process, the bots' own included: the machine is shared.
  N=${BOTS%%:*}; SECS=${BOTS#*:}; [ "$SECS" = "$BOTS" ] && SECS=120
  cp "${TOOLS_JAR:-$B/backend/tools/target/tools-0.1.0-SNAPSHOT-all.jar}" "$D/tools.jar"; TOOLS=$D/tools.jar
  if [ "${SOAK:-0}" = 1 ]; then
    export BACKEND_BOT_STAYS=${STAYS:-60:300}
    M0=$(dbq "$DBPORT" "SELECT COALESCE(MAX(id), 0) FROM matches")
  fi
  $J -cp "$TOOLS" com.backend.tools.BotClient "$N" "$SECS" http://127.0.0.1:8093 ws://127.0.0.1:8094/lobby \
    > "$D/bots.log" 2>&1 & BOTS_PID=$!
  until grep -q "bots connected" "$D/bots.log" || ! kill -0 $BOTS_PID 2>/dev/null; do sleep 1; done
  if [ "${FAILOVER:-0}" = 1 ] || [ "$MYSQL_FAILOVER" != 0 ]; then
    # The failover under load (architecture/02 §6). The probe: an authenticated read every half
    # second, which needs the session store and MySQL both; each answer's time and status.
    UNDER_LOAD=1
    BOTS_IN=$(grep -oE "^[0-9]+ bots connected" "$D/bots.log" | cut -d' ' -f1)
    PRIMARY_PORT=$DBPORT; [ "$MYSQL_FAILOVER" != 0 ] && PRIMARY_PORT=3307 && mysql_prepare
    M0=$(dbq "$PRIMARY_PORT" "SELECT COALESCE(MAX(id), 0) FROM matches")
    PROBE=probe$(date +%s%N | tail -c 9)
    curl -s -o /dev/null -X POST -H 'Content-Type: application/json' http://127.0.0.1:8093/v1/accounts \
      -d "{\"username\":\"$PROBE\",\"displayName\":\"Probe\",\"password\":\"hunter2-hunter2\"}"
    PROBE_TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' http://127.0.0.1:8093/v1/sessions \
      -d "{\"username\":\"$PROBE\",\"password\":\"hunter2-hunter2\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
    while :; do                                        # stamped when answered, not when asked (T-25)
      code=$(curl -s -o /dev/null -m 2 -w '%{http_code}' -H "Authorization: Bearer $PROBE_TOKEN" \
        http://127.0.0.1:8093/v1/inventory)
      printf '%s %s\n' "$(date +%s.%N)" "$code"
      sleep 0.5
    done > "$D/probe.log" & PROBE_PID=$!
    (
      sleep $(( SECS / 2 ))
      date +%s.%N > "$D/failed.at"
      if [ "${FAILOVER:-0}" = 1 ]; then
        echo "--- the store's primary killed, the bots playing ($(date +%T))"
        store_kill
        sleep 9
        echo "  the alert, 10 s on: platform $(curl -s http://127.0.0.1:9195/metrics | awk '$1 == "backend_store_connected" { print $2 }'), arena $(curl -s http://127.0.0.1:9197/metrics | awk '$1 == "backend_store_connected" { print $2 }') (backend_store_connected)"
      elif [ "$MYSQL_FAILOVER" = 1 ]; then
        echo "--- MySQL's primary killed, the bots playing ($(date +%T))"
        mysql_kill
      else
        echo "--- MySQL handed over, the bots playing ($(date +%T))"
        mysql_promote
        date +%s.%N > "$D/promoted.at"
      fi
    ) > "$D/under-load.log" 2>&1 &
  fi
  if [ -n "${WORKERS:-}" ] && [ -z "${UNDER_LOAD:-}" ]; then
    # The workers' rate (plan item 26): every worker ready, then all paused while the bots play,
    # so that their results wait in the stream, to be drained by all of them at once.
    BOTS_IN=$(grep -oE "^[0-9]+ bots connected" "$D/bots.log" | cut -d' ' -f1)
    M0=$(dbq "$DBPORT" "SELECT COALESCE(MAX(id), 0) FROM matches")
    WPIDS=("${PIDS[4]}")
    for k in $(seq 2 "$WORKERS"); do
      $J $OPENS -cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 6390 worker-drill-$k \
        > "$D/worker-$k.log" 2>&1 & WPIDS+=($!); PIDS+=($!)
    done
    for k in $(seq 2 "$WORKERS"); do
      until grep -q "consuming s:match-result" "$D/worker-$k.log"; do sleep 1; done
    done
    kill -STOP "${WPIDS[@]}"
    echo "--- $WORKERS workers ready, and paused while the bots play ($(date +%T))"
  fi
  NAMES=(store arena platform gateway worker bots mysqld)
  WATCH=("${PIDS[0]}" "${PIDS[1]}" "${PIDS[2]}" "${PIDS[3]}" "${PIDS[4]}" "$BOTS_PID" "$(pgrep -x mysqld | head -1)")
  if [ "${SOAK:-0}" = 1 ]; then
    # The soak (07 §4): duels throughout, and every process sampled as second, series, value.
    : > "$D/duels.started"; : > "$D/duels.status"; mkdir -p "$D/histo"
    (
      while [ ! -f "$D/soak.stop" ]; do
        echo >> "$D/duels.started"
        run_client duel >> "$D/duels.log" 2>&1; echo $? >> "$D/duels.status"
        sleep 30
      done
    ) & DUELS_PID=$!
    soak_round() {
      local t=$1 i p
      for i in 0 1 2 3 4; do
        p=${PIDS[$i]}
        # The live heap: the live objects' bytes, counted after a collection. The heap's "used"
        # after one moved by ±6 MB between rounds, ZGC counting whole pages.
        "$(dirname "$J")/jcmd" "$p" GC.class_histogram > "$D/histo/${NAMES[$i]}-$t.txt" 2>/dev/null
        printf '%s\theap.%s\t%s\n' "$t" "${NAMES[$i]}" "$(awk '$1 == "Total" { print $3 }' "$D/histo/${NAMES[$i]}-$t.txt")"
        printf '%s\tthreads.%s\t%s\n' "$t" "${NAMES[$i]}" "$(awk '/^Threads/ { print $2 }' "/proc/$p/status")"
        printf '%s\tfds.%s\t%s\n' "$t" "${NAMES[$i]}" "$(ls "/proc/$p/fd" | wc -l)"
      done
      $J -jar "$JREDIS_CLI" -p 6390 --scan | sed -E 's/:.*//' | sort | uniq -c \
        | awk -v t="$t" '{ printf "%s\tkeys.%s\t%s\n", t, $2, $1 }'
      printf '%s\tstream\t%s\n' "$t" "$(cli "XLEN s:match-result")"
      printf '%s\tresults\t%s\n' "$t" "$(curl -s http://127.0.0.1:9197/metrics \
        | awk '$1 == "backend_arena_results_published_total" { print $2 }')"
      printf '%s\tduels\t%s\n' "$t" "$(wc -l < "$D/duels.started")"
      printf '%s\tmysql.connections\t%s\n' "$t" \
        "$(dbq "$DBPORT" "SELECT COUNT(*) FROM information_schema.processlist WHERE user = 'backend'")"
    }
    (
      S0=$(date +%s)
      while [ ! -f "$D/soak.stop" ]; do
        soak_round $(( $(date +%s) - S0 ))
        sleep "${SAMPLE_EVERY:-300}"
      done
    ) > "$D/soak.tsv" & SAMPLER_PID=$!
  fi
  # utime + stime, read after the name: a thread's name, in parentheses, may hold spaces.
  # 0 for a process gone, which a failover under load makes of the store's primary.
  cpu() { local v; v=$(sed 's/.*) //' "$1" 2>/dev/null | awk '{ print $12 + $13 }'); echo "${v:-0}"; }
  ticks() { for p in "${WATCH[@]}"; do cpu "/proc/$p/stat"; done; }
  threads() {    # the arena's threads by kind, numbers taken off ("room", an event loop): user, kernel
    for t in /proc/${PIDS[1]}/task/*; do
      printf '%s ' "$(sed -E 's/([-#_ ]?[0-9]+)+$//; s/ /_/g' "$t/comm")"; sed 's/.*) //' "$t/stat" | awk '{ print $12, $13 }'
    done | awk '{ u[$1] += $2; k[$1] += $3 } END { for (n in u) print n, u[n], k[n] }'
  }
  sleep 5                                              # past the joins' own burst
  A=($(ticks)); TA=$(threads); T0=$(date +%s.%N)
  sleep $(( SECS > 20 ? SECS - 15 : 5 ))
  Z=($(ticks)); TZ=$(threads); T1=$(date +%s.%N)
  HZ=$(getconf CLK_TCK)
  echo "--- cores used while every bot played ($(nproc) on this machine, shared):"
  for i in "${!NAMES[@]}"; do
    awk -v n="${NAMES[$i]}" -v a="${A[$i]}" -v z="${Z[$i]}" -v t0="$T0" -v t1="$T1" -v hz="$HZ" \
      'BEGIN { if (z < a) printf "  %-9s (stopped in the window)\n", n; else printf "  %-9s %5.2f\n", n, (z - a) / hz / (t1 - t0) }'
  done
  echo "  the arena's, by thread (user + kernel):"
  join <(sort <<< "$TA") <(sort <<< "$TZ") | awk -v t0="$T0" -v t1="$T1" -v hz="$HZ" \
    '{ u = ($4 - $2) / hz / (t1 - t0); k = ($5 - $3) / hz / (t1 - t0); if (u + k >= 0.01) printf "    %-18s %5.2f = %.2f + %.2f\n", $1, u + k, u, k }' \
    | sort -k2 -rn | head -8
  echo "  memory (MB): $(for i in 1 2 3 4; do printf '%s %d  ' "${NAMES[$i]}" $(( $(awk '/VmRSS/ { print $2 }' /proc/${PIDS[$i]}/status) / 1024 )); done)"
  echo "--- the arena, at the end of the window"
  curl -s http://127.0.0.1:9197/metrics | grep -E '^backend_arena_(players|rooms|tick_p99_seconds|tick_overruns_total|snapshots_(skipped|held)_total|connections_dropped_total|clients)' \
    | sort | awk '{ print "  " $0 }' | head -40
  wait $BOTS_PID
  STATUS=$?
  sleep 2                                              # the last connections' ends observed
  echo "--- NFR-2 per connection: each bot's own bytes a second down, as it ended (02 §13)"
  curl -s http://127.0.0.1:9197/metrics > "$D/arena-end.prom" || echo "  (no scrape)"
  nfr2_quantiles "$D/arena-end.prom"
  echo "--- the bots"
  grep -vE "^\s*$" "$D/bots.log" | tail -12
  if [ "${SOAK:-0}" = 1 ]; then
    touch "$D/soak.stop"; kill "$SAMPLER_PID" 2>/dev/null
    wait "$DUELS_PID"                                  # the last duel run played to its end
    RUNS=$(wc -l < "$D/duels.status"); BAD=$(grep -vc '^0$' "$D/duels.status")
    echo "--- the soak (07 §4): $RUNS duel runs, $BAD failed"
    [ "$BAD" = 0 ] || { echo "SOAK FAILED: a duel run failed ($D/duels.log)"; STATUS=1; }
    ENDED=$(grep -oE "^stays [0-9]+" "$D/bots.log" | cut -d' ' -f2)
    T0=$(date +%s)
    until [ "$(stays | awk '{ print $1 }')" -ge "${ENDED:-1}" ] 2>/dev/null || [ $(( $(date +%s) - T0 )) -ge 300 ]; do sleep 2; done
    ROWS=$(stays | awk '{ print $1 }')
    echo "  the bots' stays: $ENDED ended, $ROWS paid"
    [ -n "$ENDED" ] && [ "$ROWS" = "$ENDED" ] || { echo "SOAK FAILED: not every stay paid once"; STATUS=1; }
    grep -qE "kicks 0 .*lobby failures 0" "$D/bots.log" || { echo "SOAK FAILED: a bot kicked or stopped playing on"; STATUS=1; }
    ERRS=$(cat "$D"/{jredis,arena,platform,gateway,worker}.log | grep -c " ERROR ")
    echo "  ERROR lines in the processes' logs: $ERRS"
    grep -h " ERROR " "$D"/{jredis,arena,platform,gateway,worker}.log | head -5 | cut -c1-200
    [ "$ERRS" = 0 ] || { echo "SOAK FAILED: errors logged"; STATUS=1; }
    FROM=${JUDGE_FROM:-$(( SECS / 2 ))}
    echo "--- the judge, from second $FROM of the samples ($D/soak.tsv)"
    $J -cp "$TOOLS" com.backend.tools.SoakJudge "$D/soak.tsv" "$FROM" || { echo "SOAK FAILED: the judge"; STATUS=1; }
    echo "  the live heap's biggest growers over the judged hour, by class (bytes; the histograms in $D/histo):"
    for n in arena platform gateway worker store; do
      ROUNDS=$(ls "$D/histo/$n-"*.txt | sed -E 's/.*-([0-9]+)\.txt$/\1/' | sort -n)
      A=$(awk -v f="$FROM" '$1 >= f' <<< "$ROUNDS" | head -1); Z=$(tail -1 <<< "$ROUNDS")
      [ -n "$A" ] && awk 'FNR == NR { if ($1 ~ /:$/) a[$4] = $3; next } $1 ~ /:$/ { d = $3 - a[$4]; if (d > 0) print d, $4 }' \
        "$D/histo/$n-$A.txt" "$D/histo/$n-$Z.txt" | sort -rn | head -3 | awk -v n="$n" '{ printf "    %-9s %+10d  %s\n", n, $1, $2 }'
    done
    echo "  the gateway's calls to platform, warm (03 §10):"
    curl -s http://127.0.0.1:9198/metrics | grep -E '^backend_gateway_platform_seconds_(sum|count)' | awk '{ print "    " $0 }'
  fi
  if [ -n "${UNDER_LOAD:-}" ]; then
    cat "$D/under-load.log"
    grep -q "FAILED" "$D/under-load.log" && STATUS=1
    grep -qE "disconnects 0 +kicks 0 " "$D/bots.log" || { echo "UNDER LOAD FAILED: a bot was dropped"; STATUS=1; }
    if [ ! -f "$D/promoted.at" ]; then
      sleep 20                                         # the operator's minutes, shortened
      echo "--- the replica promoted, 20 s after the bots left ($(date +%T))"
      if [ "${FAILOVER:-0}" = 1 ]; then store_promote; else mysql_promote; fi
      date +%s.%N > "$D/promoted.at"
    fi
    if [ "$MYSQL_FAILOVER" != 0 ]; then
      DBPORT=3308
      [ "$(msql 3308 "SELECT epoch FROM backend_dev.ha_epoch")" = 1 ] || mfail "the epoch is not 1"
      [ "$(msql 3308 "SELECT COUNT(*) FROM backend_dev.matches")" -ge "$BEFORE" ] || mfail "the new primary lacks what the old one had"
    fi
    # Every bot's stay, once.
    # The catch-up's cost: each process's CPU a result, which says whether a result's time is
    # spent working or waiting on round trips and the disk.
    MYSQLD=${WATCH[6]}; [ "$MYSQL_FAILOVER" != 0 ] && MYSQLD=${MYSQL_PIDS[1]}
    W0=$(cpu "/proc/${PIDS[4]}/stat"); Q0=$(cpu "/proc/$MYSQLD/stat")
    T0=$(date +%s)
    until [ "$(stays | awk '{ print $1 }')" -ge "$BOTS_IN" ] 2>/dev/null || [ $(( $(date +%s) - T0 )) -ge 180 ]; do sleep 2; done
    PAID_AT=$(date +%s.%N)
    W1=$(cpu "/proc/${PIDS[4]}/stat"); Q1=$(cpu "/proc/$MYSQLD/stat")
    sleep 3                                            # a moment for a stay applied twice to show
    kill $PROBE_PID 2>/dev/null
    read -r ROWS PLAYERS <<< "$(stays)"
    AFTER_S=$(awk -v a="$(cat "$D/promoted.at")" -v b="$PAID_AT" 'BEGIN { printf "%.1f", b - a }')
    if [ "$ROWS" -ge "$BOTS_IN" ] 2>/dev/null; then WHEN="the last $AFTER_S s after the promotion"; else WHEN="given up $AFTER_S s after the promotion"; fi
    echo "--- every bot's stay: $ROWS rows for $PLAYERS of $BOTS_IN bots, $WHEN"
    [ "$ROWS" = "$BOTS_IN" ] && [ "$PLAYERS" = "$BOTS_IN" ] || { echo "UNDER LOAD FAILED: not every stay paid once"; STATUS=1; }
    awk -v w=$(( W1 - W0 )) -v q=$(( Q1 - Q0 )) -v n="$ROWS" -v hz="$HZ" 'BEGIN { if (n > 0)
      printf "  CPU a result over the catch-up: worker %.1f ms, mysqld %.1f ms\n", w / hz * 1000 / n, q / hz * 1000 / n }'
    if [ "${FAILOVER:-0}" = 1 ]; then                  # the runbook's step 4, confirmed as it says
      BACK="platform $(curl -s http://127.0.0.1:9195/metrics | awk '$1 == "backend_store_connected" { print $2 }'), arena $(curl -s http://127.0.0.1:9197/metrics | awk '$1 == "backend_store_connected" { print $2 }')"
      echo "--- the alert after the promotion: $BACK (backend_store_connected)"
      [ "$BACK" = "platform 1, arena 1" ] || { echo "UNDER LOAD FAILED: a process is not back on the store"; STATUS=1; }
    fi
    awk -v f="$(cat "$D/failed.at")" -v p="$(cat "$D/promoted.at")" '
      $2 != 200 { n++; if (!first) first = $1; if ($1 < f) early++ }
      $2 == 200 && $1 > p && !back { back = $1 }
      END {
        if (!n) print "--- the probe: never refused"
        else if (back) printf "--- the probe: refused %d times, from %.1f s after the failure; answered again %.1f s after the promotion, %.1f s after the failure\n", n, first - f, back - p, back - f
        else printf "--- the probe: refused %d times, from %.1f s after the failure, and never answered again\n", n, first - f
        if (early) { print "UNDER LOAD FAILED: the probe was refused before the failure"; exit 1 }
        if (!back) { print "UNDER LOAD FAILED: the probe was not answered after the promotion"; exit 1 }
      }' "$D/probe.log" || STATUS=1
  fi
  if [ -n "${WORKERS:-}" ] && [ -z "${UNDER_LOAD:-}" ]; then
    sleep 3                                            # the last results into the stream
    kill -CONT "${WPIDS[@]}"
    T0=$(date +%s)
    until [ "$(stays | awk '{ print $1 }')" -ge "$BOTS_IN" ] 2>/dev/null || [ $(( $(date +%s) - T0 )) -ge 300 ]; do sleep 1; done
    sleep 2                                            # a moment for a stay applied twice to show
    read -r ROWS PLAYERS <<< "$(stays)"
    # From the first stay applied to the last, by the workers' own clocks: their start-up is not in it.
    cat "$D"/worker*.log | grep "applied match" | awk -v w="$WORKERS" '{
        split($1, t, ":"); s = t[1] * 3600 + t[2] * 60 + t[3]; if (!n || s < first) first = s; if (s > last) last = s; n++ }
      END { if (n > 1) printf "--- the backlog: %d stays applied by %d workers in %.1f s, %.1f a second\n", n, w, last - first, (n - 1) / (last - first) }'
    for f in "$D"/worker*.log; do printf '  %s applied %s\n' "$(basename "$f" .log)" "$(grep -c 'applied match' "$f")"; done
    [ "$ROWS" = "$BOTS_IN" ] && [ "$PLAYERS" = "$BOTS_IN" ] || { echo "WORKERS FAILED: $ROWS rows for $PLAYERS of $BOTS_IN bots"; STATUS=1; }
  fi
  # The arena's own account of each room, which it gives when it stops (07 §4).
  kill -TERM "${PIDS[1]}"
  while kill -0 "${PIDS[1]}" 2>/dev/null; do sleep 1; done
  echo "--- each room, by the arena at its stop: the simulation, then the whole tick"
  grep -E "=== room-|^TOTAL|^collide|^encode\+write|^budget|overruns [0-9]" "$D/arena.log" | grep -v "room thread stopped" \
    | awk '{ print "  " $0 }'
  # Every bot left on purpose, so no stay should wait for a resume (P-34).
  LOST=$(grep -c "lost their connection" "$D/arena.log")
  echo "--- bots taken for lost connections: $LOST"
  [ "$LOST" = 0 ] || { echo "BOTS FAILED: a bot that left was taken for a lost connection (P-34)"; STATUS=1; }
else
run_client "$@"
STATUS=$?
fi
if [ "$FAILOVER" != 0 ] && [ -z "${UNDER_LOAD:-}" ]; then
  sleep 3                                              # the last results through the spool
  # The store's replica, as the worker reads it from the primary (D-58).
  REPLICAS=$(curl -s http://127.0.0.1:9199/metrics | awk '$1 == "backend_store_replicas" { print $2 }')
  echo "  the store's replicas, as the worker reads them: $REPLICAS connected, $(curl -s http://127.0.0.1:9199/metrics | awk '$1 == "backend_store_replica_behind_bytes" { print $2 }') bytes behind"
  [ "$REPLICAS" = 1 ] || { echo "FAILOVER FAILED: the worker counts $REPLICAS replicas of the store"; STATUS=1; }
  if [ "$FAILOVER" = 1 ]; then
    echo "--- the store's primary killed, its replica promoted, the scenarios again ($(date +%T))"
    store_kill
    store_promote
  else
    echo "--- the store handed over, the old primary demoted and alive, the scenarios again ($(date +%T))"
    store_demote
  fi
  sleep 3
  store_listening
  run_client "$@" || STATUS=1
fi
if [ "$MYSQL_FAILOVER" != 0 ] && [ -z "${UNDER_LOAD:-}" ]; then
  sleep 3                                              # the last results applied on the primary
  mysql_prepare
  mysql_watched
  if [ "$MYSQL_FAILOVER" = 1 ]; then
    echo "--- MySQL's primary killed, its replica promoted, the scenarios again ($(date +%T))"
    mysql_kill
  elif [ "$MYSQL_FAILOVER" = freeze ]; then
    # A session made while MySQL answers, for an authenticated read after (O-9).
    PROBE=probe$(date +%s%N | tail -c 9)
    curl -s -o /dev/null -X POST -H 'Content-Type: application/json' http://127.0.0.1:8093/v1/accounts \
      -d "{\"username\":\"$PROBE\",\"displayName\":\"Probe\",\"password\":\"hunter2-hunter2\"}"
    PROBE_TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' http://127.0.0.1:8093/v1/sessions \
      -d "{\"username\":\"$PROBE\",\"password\":\"hunter2-hunter2\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
    echo "--- MySQL's primary frozen (SIGSTOP), its replica promoted, the scenarios again ($(date +%T))"
    kill -STOP "${MYSQL_PIDS[0]}"
    FROZEN=$(date +%s.%N)
  else
    echo "--- MySQL handed over: the old primary fenced, the replica promoted, the scenarios again ($(date +%T))"
  fi
  mysql_promote
  if [ "$MYSQL_FAILOVER" = freeze ]; then
    PROMOTED=$(date +%s.%N)
    until [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' -H "Authorization: Bearer $PROBE_TOKEN" \
              http://127.0.0.1:8093/v1/inventory)" = 200 ]; do
      [ "$(awk -v p="$PROMOTED" -v n="$(date +%s.%N)" 'BEGIN { print (n - p > 120) }')" = 1 ] && break
      sleep 0.5
    done
    awk -v f="$FROZEN" -v p="$PROMOTED" -v n="$(date +%s.%N)" 'BEGIN {
      if (n - p > 120) print "MYSQL FAILOVER FAILED: platform did not answer within 120 s of the promotion"
      else printf "--- platform answered again %.1f s after the promotion, %.1f s after the freeze\n", n - p, n - f }' \
      | tee -a "$D/freeze.txt"
    grep -q FAILED "$D/freeze.txt" && STATUS=1
  fi
  AFTER=$(msql 3308 "SELECT COUNT(*) FROM backend_dev.matches")
  echo "matches: $BEFORE on the old primary, $AFTER on the new"
  [ "$AFTER" = "$BEFORE" ] || mfail "the new primary lacks what the old one had"
  [ "$(msql 3308 "SELECT epoch FROM backend_dev.ha_epoch")" = 1 ] || mfail "the epoch is not 1"
  promote --old-is-down | grep -q "a primary already" || mfail "a second run did not see a primary"
  [ "$(msql 3308 "SELECT epoch FROM backend_dev.ha_epoch")" = 1 ] || mfail "a second run moved the epoch"
  DBPORT=3308
  sleep 3
  run_client "$@" || STATUS=1
  if [ "$MYSQL_FAILOVER" = freeze ]; then                # gone for good, as a lost machine: rebuilt below
    kill -9 "${MYSQL_PIDS[0]}"
    while kill -0 "${MYSQL_PIDS[0]}" 2>/dev/null; do sleep 1; done
  fi
  # A restart keeps it the primary, although its command line says read-only.
  kill -TERM "${MYSQL_PIDS[1]}"
  while kill -0 "${MYSQL_PIDS[1]}" 2>/dev/null; do sleep 1; done
  MYSQL_PIDS[1]=$(mysql_run mysql-c 3308 2 --super-read-only=ON)
  mysql_ready 3308 || mfail "the new primary did not restart"
  [ "$(msql 3308 "SELECT @@global.read_only")" = 0 ] || mfail "the new primary is read-only after a restart"
  echo "the new primary restarted: read-only $(msql 3308 "SELECT @@global.read_only"), epoch $(msql 3308 "SELECT epoch FROM backend_dev.ha_epoch")"

  # Step 5 (runbook §2): the old primary rebuilt as a replica of the new by clone, started
  # read-only as its configuration now says; then handed back, and the scenarios a third time.
  echo "--- the old primary rebuilt from the new by clone, then handed back ($(date +%T))"
  if ! kill -0 "${MYSQL_PIDS[0]}" 2>/dev/null; then
    MYSQL_PIDS[0]=$(mysql_run mysql-a 3307 1 --super-read-only=ON)
    mysql_ready 3307 || mfail "the old primary did not start"
  fi
  T0=$(date +%s)
  msql 3307 "SET GLOBAL offline_mode = ON; SET GLOBAL super_read_only = OFF;
             SET GLOBAL clone_valid_donor_list = '127.0.0.1:3308';
             CLONE INSTANCE FROM 'clone'@'127.0.0.1':3308 IDENTIFIED BY 'clone-drill' REQUIRE SSL" 2>&1 \
    | grep -v "ERROR 3707"                             # no supervisor here to restart it: started below
  while kill -0 "${MYSQL_PIDS[0]}" 2>/dev/null; do sleep 1; done
  MYSQL_PIDS[0]=$(mysql_run mysql-a 3307 1 --super-read-only=ON)
  mysql_ready 3307 || mfail "the rebuilt replica did not start"
  [ "$(msql 3307 "SELECT STATE FROM performance_schema.clone_status")" = Completed ] || mfail "the clone did not complete"
  follow 3307 3308
  msql 3307 "SELECT WAIT_FOR_EXECUTED_GTID_SET('$(msql 3308 "SELECT @@global.gtid_executed")', 60)" > /dev/null
  echo "rebuilt in $(( $(date +%s) - T0 )) s: $(msql 3307 "SELECT COUNT(*) FROM backend_dev.matches") matches as the new primary's $(msql 3308 "SELECT COUNT(*) FROM backend_dev.matches"), read-only $(msql 3307 "SELECT @@global.super_read_only"), offline $(msql 3307 "SELECT @@global.offline_mode")"
  [ "$(msql 3307 "SELECT @@global.super_read_only = 1 AND @@global.offline_mode = 0")" = 1 ] \
    || mfail "the rebuilt replica is not read-only and open"
  printf '[client]\nuser=failover\npassword=failover-drill\nprotocol=TCP\nhost=127.0.0.1\nport=3307\n' \
    > "$D/mysql-failover-a.cnf"
  chmod 600 "$D/mysql-failover-a.cnf"
  MYSQL_FAILOVER_CNF="$D/mysql-failover-a.cnf" BACKEND_DB_NAME=backend_dev \
    "$B/backend/scripts/promote-mysql.sh" 127.0.0.1:3308 --demote-old || mfail "the handover back"
  [ "$(msql 3307 "SELECT epoch FROM backend_dev.ha_epoch")" = 2 ] || mfail "the epoch is not 2"
  DBPORT=3307
  sleep 3
  run_client "$@" || STATUS=1
fi
if [ "${TAKEOVER:-0}" = 1 ]; then
  echo "--- a worker killed with results in flight, and another taking them over"
  sleep 3                                              # the last results through the spool
  # Read as the stopped worker: pending for it, as if it had crashed mid-apply.
  cli "XREADGROUP GROUP rewards worker-drill COUNT 1000 STREAMS s:match-result >" > /dev/null
  UIDS=$(cli "XRANGE s:match-result - +" | grep -o '"matchUid":"[^"]*"' | sort -u | cut -d'"' -f4)
  N=$(printf '%s\n' $UIDS | grep -c .)
  kill -9 "${PIDS[4]}"
  echo "worker-drill killed ($(date +%T)): $(cli "XPENDING s:match-result rewards" | head -1) pending on it, $N results in the stream"
  $J $OPENS -cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 6390 worker-drill-b > "$D/worker-b.log" 2>&1 & PIDS+=($!)
  T0=$(date +%s)
  until [ "$(cli "XPENDING s:match-result rewards" | head -1)" = 0 ]; do
    if [ $(( $(date +%s) - T0 )) -gt 180 ]; then echo "TAKEOVER FAILED: still pending after 180 s"; STATUS=1; break; fi
    sleep 2
  done
  echo "pending 0 after $(( $(date +%s) - T0 )) s"; grep -E 're-driving|trimmed' "$D/worker-b.log" | cut -c1-160
  IN=$(mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -N -e "
    SELECT COUNT(*) FROM matches WHERE match_uid IN ('$(printf '%s\n' $UIDS | paste -sd, | sed "s/,/','/g")')" 2>/dev/null)
  echo "in MySQL: $IN of $N"; [ "$IN" = "$N" ] && [ "$N" -gt 0 ] || { echo "TAKEOVER FAILED"; STATUS=1; }
fi
echo "--- the matcher's view: platform's metrics, scraped"
curl -s http://127.0.0.1:9195/metrics | grep -E '^backend_platform_(matches_made_total|confirms_total|queue_players|queue_wait_seconds_(sum|count)|request_seconds_count|(open|match)_tickets_issued_total)' || echo "(no scrape)"
echo "--- the gateway's latency to platform, by route (03 §10)"
curl -s http://127.0.0.1:9198/metrics | grep -E '^backend_gateway_platform_seconds_(sum|count)' || echo "(no scrape)"
echo "--- the arena's view"
grep -E 'listening|joined|resumed|lost their connection|left|kick|no room|match .*(starting|over)|drain|WARN|ERROR' "$D/arena.log" \
  | grep -v 'BACKEND_STORE_PASSWORD' | tail -20
echo "--- the database's view: the last duels, as worker applied them"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.player_id, mp.placement, mp.kills, mp.xp_gained, mp.rating_delta,
         p.rating_duel, p.rated_duels
    FROM matches m JOIN match_player mp ON mp.match_id = m.id JOIN player p ON p.id = mp.player_id
   WHERE m.mode = 1 ORDER BY m.id DESC, mp.player_id LIMIT 6" 2>/dev/null
echo "--- the last ranked free-for-all"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.player_id, mp.placement, mp.score, mp.rating_delta, p.rating_rffa, p.rated_rffas
    FROM matches m JOIN match_player mp ON mp.match_id = m.id JOIN player p ON p.id = mp.player_id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 3) ORDER BY mp.placement, mp.player_id" 2>/dev/null
echo "--- the last co-op"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.team, mp.player_id, mp.placement, mp.kills, mp.deaths, mp.xp_gained, mp.rating_delta
    FROM matches m JOIN match_player mp ON mp.match_id = m.id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 4) ORDER BY mp.player_id" 2>/dev/null
echo "--- and the last team-vs-team, by team"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.team, mp.player_id, mp.placement, mp.kills, mp.xp_gained, mp.rating_delta,
         p.rating_tvt, p.rated_tvts
    FROM matches m JOIN match_player mp ON mp.match_id = m.id JOIN player p ON p.id = mp.player_id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 2) ORDER BY mp.team, mp.player_id" 2>/dev/null
echo "--- and the last domination, by team: placed by the dominators held (01 §8.7)"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.team, mp.player_id, mp.placement, mp.kills, mp.rating_delta
    FROM matches m JOIN match_player mp ON mp.match_id = m.id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 6) ORDER BY mp.team, mp.player_id" 2>/dev/null
echo "--- and the last tag, by the team each started on (01 §8.8)"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.team, mp.player_id, mp.placement, mp.kills, mp.rating_delta
    FROM matches m JOIN match_player mp ON mp.match_id = m.id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 7) ORDER BY mp.team, mp.player_id" 2>/dev/null
echo "--- and the last maze, each for themselves (01 §8.9)"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mp.player_id, mp.placement, mp.kills, mp.rating_delta
    FROM matches m JOIN match_player mp ON mp.match_id = m.id
   WHERE m.id = (SELECT MAX(id) FROM matches WHERE mode = 8) ORDER BY mp.placement, mp.player_id" 2>/dev/null
echo "--- sandboxes recorded, which must be none (01 §8.10)"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT COUNT(*) AS sandboxes_recorded FROM matches WHERE mode = 9" 2>/dev/null
echo "--- and the last team match, by side: the teams' part (04 §4, the sixth slice)"
mysql -h127.0.0.1 -P"$DBPORT" --protocol=TCP -ubackend -p"${BACKEND_DB_PASSWORD:-backend-dev-password}" backend_dev -e "
  SELECT m.match_uid, mt.side, mt.team_id, t.name, mt.placement, mt.rating_delta, t.rating, t.rated_matches,
         t.wins, t.losses, t.draws
    FROM match_team mt JOIN matches m ON m.id = mt.match_id LEFT JOIN team t ON t.id = mt.team_id
   WHERE mt.match_id = (SELECT MAX(match_id) FROM match_team) ORDER BY mt.side" 2>/dev/null
exit $STATUS
