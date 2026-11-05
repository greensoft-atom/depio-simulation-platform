#!/bin/bash
# Runs docs/operations/10-two-machines.md as written (D-96): the launch's machine, A, installed by the install
# guide's own check; then a second, B, made its standby by the page's steps while a probe uses the site through A's
# nginx, every request timed; then the failovers: A's MySQL lost while A runs, B's promoted (the runbook's §2) and A's
# processes on it; and A lost, the runbook's steps for it on B (§4), the site served from B with nothing committed
# lost. Between the standby and the failovers, B's own arenas (D-97, the page's §10): matches played through A's
# nginx on them by the C# headless client, A's arenas stopped for it; then B frozen a minute, A's site going on.
# Two UBI 9.5 containers, systemd their first process, with no network but a link of their own: a veth pair,
# one end moved into each (A 10.0.0.1, B 10.0.0.2), so nothing of the host's network or firewall changes.
#
#   scripts/check-two-machines-el9.sh       as root; needs docker, git, ip, nsenter and .NET 8 (DOTNET, default
#                                           /opt/dotnet/dotnet) for the client; about twenty minutes on this VM
#   SAVE_A=<image> scripts/check-two-machines-el9.sh     A kept as an image once the install guide's steps pass
#   A_IMAGE=<image> scripts/check-two-machines-el9.sh    A started from that image: the install not run again
#   STANDBY_ONLY=1 scripts/check-two-machines-el9.sh     the standby alone (D-96), without B's arenas
#
# The blocks the page marks `console` are not run: the copy to B (this check copies the same files through docker),
# and the firewall (check-ports-el9.sh runs it).
set -u
GAP_LIMIT=${GAP_LIMIT:-120}       # the longest the site may refuse a request during the steps, in seconds

if [ "${1:-}" = --probe ]; then
    # Inside a machine: the site through its nginx, as a device would use it, until told to stop. Every half second
    # the inventory with a session made before (the session store and MySQL); every tenth second a registration
    # (a write on MySQL's primary). Each request is given 5 s and logged with its time and its answer.
    tok=$(cat /var/tmp/check-session)
    n=0
    while [ ! -e /var/tmp/probe.stop ]; do
        n=$((n + 1))
        c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /etc/backend/tls/ca.pem \
            -H "Authorization: Bearer $tok" https://127.0.0.1/v1/inventory)
        echo "$(date +%s.%N | cut -c1-14) inventory $c" >> /var/tmp/probe.log
        if [ $((n % 20)) = 0 ]; then
            c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /etc/backend/tls/ca.pem -X POST \
                -H 'Content-Type: application/json' -d "{\"username\":\"probe$n\",\"password\":\"install-check-1\"}" \
                https://127.0.0.1/v1/accounts)
            echo "$(date +%s.%N | cut -c1-14) register probe$n $c" >> /var/tmp/probe.log
        fi
        sleep 0.5
    done
    exit 0
fi

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
PAGE=$REPO/docs/operations/10-two-machines.md
GUIDE=$REPO/docs/operations/03-install-guide.md
RUNBOOK=$REPO/docs/operations/02-runbook.md
IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-init:9.5}
A=backend-check-two-a-$$
B=backend-check-two-b-$$
WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-two-machines.XXXXXX")
chmod 0755 "$WORK"
# Stopped on every way out, an interrupt too (O-78); the link goes with the containers' namespaces, or here if it
# was made and not yet moved.
trap 'docker unpause "$A" "$B" > /dev/null 2>&1; docker stop "$A" "$B" > /dev/null 2>&1; ip link del "tm$$a" 2> /dev/null; rm -rf "$WORK"' EXIT
FAILS=0
ok()   { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; FAILS=$((FAILS + 1)); }
on()   { local m=$1; shift; docker exec "$m" "$@"; }
sql()  { docker exec "$1" /opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf \
             -N -B -e "$2" 2>&1; }
store_info() {   # store_info <machine> <port>: the store's INFO replication, through the release's client
    on "$1" bash -c "{ echo \"AUTH \$(cat /etc/backend/credentials/store-password)\"; echo 'INFO replication'; } \
        | JAVA_HOME=/opt/backend/runtime timeout 10 /opt/backend/jredis/bin/j-redis-cli --raw -p $2 2> /dev/null" \
        | tr -d '\r' | sed -E 's/^([^ ]+:[0-9]+> ?)+//'
}
store_cmd() {   # store_cmd <machine> <port> <command>: the store's reply to it, after AUTH
    on "$1" bash -c "{ echo \"AUTH \$(cat /etc/backend/credentials/store-password)\"; echo '$3'; } \
        | JAVA_HOME=/opt/backend/runtime timeout 10 /opt/backend/jredis/bin/j-redis-cli --raw -p $2 2> /dev/null" \
        | tr -d '\r' | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | tail -1
}
scrapes() {   # scrapes <machine>: its platform's watch, reads of a target's metrics so far: "<ok> <failed>"
    on "$1" curl -s http://127.0.0.1:9101/metrics | awk '/^backend_watch_scrapes_total\{outcome="ok"\}/ { o = $2 }
        /^backend_watch_scrapes_total\{outcome="failed"\}/ { f = $2 } END { printf "%d %d\n", o, f }'
}
watch_reads_all() {   # watch_reads_all <machine>: across 20 s, more than a round of 15, every target read and none failed
    local n o0 f0 o1 f1
    n=$(on "$1" sed -n 's/^BACKEND_WATCH_TARGETS=//p' /etc/backend/platform.env | tr ',' '\n' | grep -c .)
    read -r o0 f0 < <(scrapes "$1"); sleep 20; read -r o1 f1 < <(scrapes "$1")
    [ "$f1" = "$f0" ] && [ $((o1 - o0)) -ge "$n" ] && ok "$2's watch reads its $n targets: $((o1 - o0)) reads in 20 s, none failed" \
        || fail "$2's watch over 20 s: $((o1 - o0)) reads of its $n targets, $((f1 - f0)) failed"
}
# A match from the lobby to its reward: the arena the lobby names, the result through the events store, the worker and MySQL.
# Not the client's play, whose frame rate and bullets are timed and missed twice on this loaded host.
play() {   # play <machine> <label> <scenario>...: the C# client through that machine's nginx, trusting the backend's root alone
    local rc
    on "$1" env HOME=/tmp DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 /check/headless/Backend.Client.Headless https://127.0.0.1 \
        --lobby wss://127.0.0.1/lobby --trust /etc/backend/tls/ca.pem "${@:3}" > "$WORK/play-$2.log" 2>&1
    rc=$?
    [ "$rc" = 0 ] && grep -q 'ALL PASSED' "$WORK/play-$2.log" && ok "the C# client, $2: ${*:3}, all passed" \
        || { fail "the C# client, $2: exit $rc"; grep -E '^--- |FAIL' "$WORK/play-$2.log" | head -12 | sed 's/^/      /'; }
}
matches_to() {   # matches_to <machine> <from line>: where its nginx passed match connections since then, "<name> <address> <status>"
    on "$1" tail -n "+$2" /var/log/backend-nginx/stream.log | awk '{ gsub(/"/, "", $4); print $4, $5, $6 }' | sort | uniq -c
}
mark() { local m; echo "--- $*"; for m in "$A" "$B"; do docker exec "$m" sh -c 'echo "=== $1" >> /var/tmp/probe.log' sh "$*" 2> /dev/null; done; }

# The pages' blocks as scripts, each with the values before it: the install guide's (§2) and this check's own for it,
# as the install guide's check gives them, then the page's for both machines.
mkdir -p "$WORK/steps"
section() {   # section <page> <heading>: the bash blocks under the heading that starts so, to the next of its level
    awk -v h="$2" '/^```/ { if (fence) { fence = 0 } else { fence = 1; bash = /^```bash/ }; next }
        fence { if (on && bash) print; next }
        index($0, h) == 1 { on = 1; split($0, w, " "); level = length(w[1]); next }
        on && /^#+ / { split($0, w, " "); if (length(w[1]) <= level) exit }' "$1"
}
section "$GUIDE" "## 2. " > "$WORK/guide-values.sh"
{
    echo "set -euo pipefail"
    cat "$WORK/guide-values.sh"
    echo "export ADDRESS=127.0.0.1 MYSQL_BUFFER_POOL=256M SCRATCH_BUFFER_POOL=256M"
} > "$WORK/values.sh"
# The page's steps: every bash block between its markers says first where it runs (# On A, # On B, # On A and on B);
# the copy to B is marked, and so is a section of the install guide's to run on a machine.
steps() {   # steps <page> <marker>: one line per item, "<where> <script>", "copy", or "guide <where> <heading>"
    awk -v m="$2" -v dir="$WORK/steps" -v pre="$3" '
        $0 == "<!-- " m " -->" { on = 1; next }
        $0 == "<!-- /" m " -->" { on = 0 }
        !on { next }
        $0 == "<!-- the copy to B -->" { print "copy"; next }
        /^<!-- the arenas. copy to B -->$/ { print "copy-arenas"; next }
        /^<!-- on [AB]: the install guide.s / { split($0, w, " "); h = $0; sub(/^<!-- on [AB]: the install guide.s /, "", h)
            sub(/ -->$/, "", h); n2 = split(h, hs, "; "); for (i = 1; i <= n2; i++) print "guide " substr(w[3], 1, 1) " " hs[i]; next }
        /^```bash/ { inb = 1; n++; f = sprintf("%s/%s%02d.sh", dir, pre, n); where = ""; next }
        inb && /^```/ { inb = 0; print (where == "" ? "?" : where) " " f; next }
        inb { if (where == "") { if ($0 ~ /^# On A and on B/) where = "both"; else if ($0 ~ /^# On A/) where = "A";
                                 else if ($0 ~ /^# On B/) where = "B" }
              print > f }
    ' "$1"
}
steps "$PAGE" two-machine-steps p > "$WORK/items"
steps "$PAGE" two-machine-arenas q > "$WORK/items-arenas"
steps "$RUNBOOK" two-machines-a-lost r > "$WORK/items-a-lost"
both=$(awk '$1 == "both" { print $2 }' "$WORK/items")
grep -q '^A ' "$WORK/items" && grep -q '^B ' "$WORK/items" \
    || { echo "  FAIL no steps for A and for B between the page's markers"; exit 1; }
[ -n "${STANDBY_ONLY:-}" ] || { grep -q '^A ' "$WORK/items-arenas" && grep -q '^B ' "$WORK/items-arenas"; } \
    || { echo "  FAIL no steps for A and for B between the page's markers for B's arenas"; exit 1; }
grep -q '^? ' "$WORK/items" "$WORK/items-arenas" "$WORK/items-a-lost" \
    && { echo "  FAIL a block says not where it runs:"; grep '^? ' "$WORK/items" "$WORK/items-arenas" "$WORK/items-a-lost"; exit 1; }
grep -q '^B ' "$WORK/items-a-lost" || { echo "  FAIL no steps on B between the runbook's markers for A lost"; exit 1; }
script() {    # script <block>: the block with every value before it
    { cat "$WORK/values.sh"; for b in $both; do cat "$b"; done; cat "$1"; } > "$1.run"
    echo "/check/steps/$(basename "$1").run"
}
guide_script() {   # guide_script <section>: the install guide's bash blocks under "## 4." for 4., or "### <its title>"
    local f h="### $1"
    [[ $1 =~ ^[0-9]+\.$ ]] && h="## $1 "
    f="$WORK/steps/guide-$(echo "$1" | tr -c 'a-zA-Z0-9' '-').sh"
    section "$GUIDE" "$h" > "$f"
    [ -s "$f" ] || { echo "  FAIL no bash block under the install guide's '$1'" >&2; return 1; }
    script "$f"
}
echo "--- the page's steps: $(grep -c . "$WORK/items") items, for B's arenas $(grep -c . "$WORK/items-arenas"); the runbook's for A lost: $(grep -c . "$WORK/items-a-lost")"

# The C# headless client, self-contained, for matches through nginx as a device plays them (D-97), as check-ports-el9.sh
# publishes it; in /check/headless on both machines.
DOTNET=${DOTNET:-/opt/dotnet/dotnet}
DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 "$DOTNET" publish "$REPO/client/Headless/Backend.Client.Headless.csproj" \
    -c Release -f net8.0 -r linux-x64 --self-contained true -p:InvariantGlobalization=true -o "$WORK/headless" \
    > "$WORK/publish.log" 2>&1 || { tail -20 "$WORK/publish.log"; echo "  FAIL the C# client not published"; exit 1; }

# The repository, as a clone has it, for A's install; A from an image needs none.
if [ -z "${A_IMAGE:-}" ]; then
    (cd "$REPO" && git ls-files --cached --others --exclude-standard | tar -czf "$WORK/repository.tar.gz" -T -) || exit 1
fi
start() {   # start <name> <image>
    docker run -d --rm --privileged --network none --name "$1" -v "$WORK:/check:ro" \
        -v "$HERE/check-two-machines-el9.sh:/check.sh:ro" -v "$HERE/check-install-guide-el9.sh:/check-guide.sh:ro" \
        "$2" > /dev/null
}
start "$A" "${A_IMAGE:-$IMAGE}" && start "$B" "$IMAGE" || exit 1
# The private network: a veth pair, an end in each container's own namespace, renamed eth1 there.
PA=$(docker inspect -f '{{.State.Pid}}' "$A")
PB=$(docker inspect -f '{{.State.Pid}}' "$B")
ip link add "tm$$a" type veth peer name "tm$$b" && ip link set "tm$$a" netns "$PA" && ip link set "tm$$b" netns "$PB" \
    || { echo "  FAIL no private link between the containers"; exit 1; }
for x in "$PA:tm$$a:10.0.0.1" "$PB:tm$$b:10.0.0.2"; do
    IFS=: read -r pid dev addr <<< "$x"
    nsenter -t "$pid" -n ip link set "$dev" name eth1 && nsenter -t "$pid" -n ip addr add "$addr/24" dev eth1 \
        && nsenter -t "$pid" -n ip link set eth1 up || { echo "  FAIL the link's end at $addr"; exit 1; }
done
sleep 5

if [ -z "${A_IMAGE:-}" ]; then
    echo "--- A: the install guide, by its own check"
    on "$A" bash /check-guide.sh --inside || { echo "  FAIL the install guide on A: the rest would prove nothing"; exit 1; }
    if [ -n "${SAVE_A:-}" ]; then docker commit "$A" "$SAVE_A" > /dev/null && echo "--- A kept as $SAVE_A"; fi
else
    echo "--- A from $A_IMAGE, after its boot"
    on "$A" bash /check-guide.sh --after-boot || { echo "  FAIL A from $A_IMAGE does not serve"; exit 1; }
fi
on "$B" sh -c 'echo "--- B: $(cat /etc/redhat-release); A over the private link: $(timeout 3 bash -c "echo > /dev/tcp/10.0.0.1/443" 2>&1 && echo reached || echo not reached)"'

docker exec -d "$A" bash /check.sh --probe
sleep 3
n=0
run_items() {   # run_items <items>: each of the page's items, in order, on the machine it names; the first failure ends them
while read -r what arg rest; do
    n=$((n + 1))
    case $what in
        copy-arenas)
            # The page's copy for B's arenas: nginx's map and the certificate issued again for their names.
            mark "step $n: the arenas' copy to B"
            on "$A" sh -c 'cd / && tar -cf - etc/backend/nginx/backend-stream.conf root/backend-certificate-b' \
                | docker exec -i "$B" sh -c 'cat > /root/from-a.tar' || { fail "the arenas' copy to B"; break; }
            ;;
        copy)
            # The page's copy, the same files: the release A was installed from, A's settings, B's MySQL certificate.
            mark "step $n: the copy to B"
            rel=$(on "$A" sh -c 'ls /srv/depio-simulation-platform/backend/target/release/backend-*.tar.gz')
            on "$B" mkdir -p /srv/depio-simulation-platform/backend/target/release
            on "$A" cat "$rel" | docker exec -i "$B" sh -c "cat > '$rel'"
            on "$A" sh -c 'cd / && tar -cf - etc/backend etc/systemd/system/backend-*.service.d root/backend-mysql-ca/ca.pem root/backend-mysql-ca/10.0.0.2' \
                | docker exec -i "$B" sh -c 'cat > /root/from-a.tar' || { fail "the copy to B"; break; }
            ;;
        guide)
            f=$(guide_script "$rest") || { FAILS=$((FAILS + 1)); break; }
            mark "step $n on $arg: the install guide's $rest"
            m=$A; [ "$arg" = B ] && m=$B
            on "$m" bash "$f" > "$WORK/step-$n.log" 2>&1 || { fail "step $n, the install guide's $rest on $arg: exit $?"; tail -25 "$WORK/step-$n.log"; break; }
            ;;
        A|B)
            m=$A; [ "$what" = B ] && m=$B
            mark "step $n on $what: $(sed -n 1p "$arg" | sed 's/^# //')"
            held=
            if [ "$what" = A ] && grep -q 'gtid_mode = ON_PERMISSIVE' "$arg"; then
                # The binlog stream behind while GTIDs go on, by construction: stopped, a registration written in the
                # file it resumes from, and started again 5 s into the step, which must wait for it. A stream resuming
                # in that file once GTIDs are on is refused for good, which A's dump and proof below show.
                on "$A" systemctl stop backend-binlog-stream
                c=$(on "$A" curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /etc/backend/tls/ca.pem -X POST \
                    -H 'Content-Type: application/json' -d '{"username":"streamheld","password":"install-check-1"}' \
                    https://127.0.0.1/v1/accounts)
                [ "$c" = 201 ] || { fail "a registration with the binlog stream stopped: $c"; break; }
                { sleep 5; on "$A" systemctl start backend-binlog-stream; } & held=$!
            fi
            on "$m" bash "$(script "$arg")" > "$WORK/step-$n.log" 2>&1 || { fail "step $n on $what: exit $?"; tail -25 "$WORK/step-$n.log"; break; }
            [ -z "$held" ] || wait "$held"
            ;;
    esac
done < <(grep -v '^both ' "$1")
}
run_items "$WORK/items"
mark "the steps done"
[ "$FAILS" = 0 ] || { echo "--- $FAILS failed"; exit 1; }

echo "--- grown: B the standby"
# MySQL on B: a replica of A's, both threads running, read-only, with all A has executed.
[ "$(sql "$B" "SELECT SERVICE_STATE FROM performance_schema.replication_connection_status")" = ON ] \
    && [ "$(sql "$B" "SELECT SERVICE_STATE FROM performance_schema.replication_applier_status")" = ON ] \
    && ok "B's MySQL replicates from A, receiver and applier running" \
    || fail "B's MySQL does not replicate: $(sql "$B" "SELECT LAST_ERROR_MESSAGE FROM performance_schema.replication_connection_status")"
got=$(sql "$B" "SELECT CONCAT(@@global.super_read_only, ' ', @@global.server_id, ' ', @@global.gtid_mode)")
[ "$got" = "1 2 ON" ] && ok "B's MySQL is read-only, server 2, GTIDs on" || fail "B's MySQL: super_read_only, server_id, gtid_mode are $got"
got=$(sql "$A" "SELECT CONCAT(@@global.server_id, ' ', @@global.gtid_mode, ' ', @@global.require_secure_transport)")
[ "$got" = "1 ON 1" ] && ok "A's MySQL: server 1, GTIDs on, TLS required" || fail "A's MySQL: server_id, gtid_mode, require_secure_transport are $got"
executed=$(sql "$A" "SELECT REPLACE(@@global.gtid_executed, '\n', '')")
[ "$(sql "$B" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$executed', 30)")" = 0 ] && ok "B has applied all A executed" \
    || fail "B has not applied all A executed ($executed) within 30 s"
# The stores on B: each a replica of A's, its link up.
for s in 6379 6380; do
    got=$(store_info "$B" "$s" | grep -oE '^(role|primary_link_status):[a-z]+' | tr '\n' ' ')
    [ "$got" = "role:replica primary_link_status:up " ] && ok "B's store on $s: $got" || fail "B's store on $s is not a replica with its link up: $got"
done
# A's workers read both: the MySQL replica's lag and each store's replica (D-58), with no setting of their own.
sleep 10
for port in 9102 9112; do
    m=$(on "$A" curl -s "http://127.0.0.1:$port/metrics")
    grep -qE '^backend_mysql_replica_up\{host="10.0.0.2:[0-9]+"\} 1' <<< "$m" && ok "the worker on $port reads B's MySQL replica" \
        || fail "the worker on $port does not read B's MySQL replica: $(grep '^backend_mysql_replica' <<< "$m" | tr '\n' ' ')"
    for g in backend_store_replicas backend_events_store_replicas; do
        grep -qE "^$g 1(\\.0)?\$" <<< "$m" && ok "the worker on $port: $g 1" \
            || fail "the worker on $port: $(grep "^$g " <<< "$m" || echo "no $g")"
    done
done
# B's front, ready and stopped: its units installed, none running, none started at boot. Whether they start is the
# failover's, below: nginx tests its configuration as its unit starts it.
for u in backend-platform backend-gateway backend-worker@c1 backend-worker@c2 backend-arena@a1 backend-arena@a2 backend-arena@a3 \
         backend-nginx backend-binlog-stream backend-scratch-mysql; do
    a=$(on "$B" systemctl is-active "$u"); e=$(on "$B" systemctl is-enabled "$u" 2> /dev/null)
    [ "$a" != active ] && [ "$e" != enabled ] || fail "B's $u is $a and $e: the standby runs only its replicas"
done
on "$B" systemctl cat backend-platform > /dev/null 2>&1 || fail "B has no platform unit to start"
# A as the launch's checks have it, and its backups still whole on the server with TLS and GTIDs: a dump and its proof.
on "$A" bash /check-guide.sh --launch-checks || fail "A's launch checks after the steps"
on "$A" systemctl start backend-mysql-backup backend-restore-proof || fail "A's backups after the steps: $(on "$A" journalctl -u backend-restore-proof -n 3 --no-pager | tail -2)"
got=$(sql "$A" "SELECT GROUP_CONCAT(CONCAT(kind, ':', ok) ORDER BY id) FROM (SELECT id, kind, ok FROM backend.backup_run ORDER BY id DESC LIMIT 2) r")
[ "$got" = "1:1,2:1" ] && ok "A's dump and proof after the steps: recorded, ok" || fail "A's last dump and proof: $got"

if [ -z "${STANDBY_ONLY:-}" ]; then
mark "B's arenas"
run_items "$WORK/items-arenas"
mark "B's arenas made"
[ "$FAILS" = 0 ] || { echo "--- $FAILS failed"; exit 1; }
echo "--- grown: B's arenas (D-97)"
for a in b1 b2 b3; do
    s=$(on "$B" systemctl is-active "backend-arena@$a"); e=$(on "$B" systemctl is-enabled "backend-arena@$a" 2> /dev/null)
    [ "$s $e" = "active enabled" ] && ok "B's arena $a: $s, $e" || fail "B's arena $a is $s and $e"
done
on "$B" sh -c 'ls /etc/backend/arena-a*.env' > /dev/null 2>&1 && fail "B keeps A's arenas' settings: the runbook would start them there"
# Each of B's arenas by its name on A's 9001: the certificate verified for that name, and nginx passing it to B.
from=$(( $(on "$A" sh -c 'wc -l < /var/log/backend-nginx/stream.log') + 1 ))
for n in 1 2 3; do
    echo | on "$A" openssl s_client -connect 127.0.0.1:9001 -servername "b$n.arena.internal" -verify_hostname "b$n.arena.internal" \
        -verify_return_error -CAfile /etc/backend/tls/ca.pem > /dev/null 2>&1 \
        && ok "b$n.arena.internal on A's 9001: the certificate verified for it" || fail "b$n.arena.internal: no handshake on A's 9001 verified for its name"
done
sleep 2
for n in 1 2 3; do
    on "$A" tail -n "+$from" /var/log/backend-nginx/stream.log | grep -q "\"b$n.arena.internal\" 10.0.0.2:901$n 200 " \
        && ok "b$n.arena.internal passed by A's nginx to B, 10.0.0.2:901$n" \
        || fail "b$n.arena.internal not passed to B's 10.0.0.2:901$n: $(on "$A" tail -n "+$from" /var/log/backend-nginx/stream.log | grep "b$n\." | tail -1)"
done
for a in b1 b2 b3; do
    [ "$(store_cmd "$A" 6379 "EXISTS arena:$a")" = 1 ] && ok "$a in A's directory" || fail "$a is not in A's directory"
done
watch_reads_all "$A" A
on "$A" bash /check-guide.sh --launch-checks || fail "A's launch checks with B's arenas"
# Matches on B's arenas, through A's nginx: A's own stopped meanwhile, so the directory has only B's to offer.
on "$A" systemctl stop backend-arena@a1 backend-arena@a2 backend-arena@a3
from=$(( $(on "$A" sh -c 'wc -l < /var/log/backend-nginx/stream.log') + 1 ))
play "$A" "on B's arenas" lobby
got=$(matches_to "$A" "$from")
echo "$got" | sed 's/^/      /'
awk '$4 == 200 && $3 ~ /^10\.0\.0\.2:901[1-3]$/' <<< "$got" | grep -q . && ! awk '$3 !~ /^10\.0\.0\.2:/' <<< "$got" | grep -q . \
    && ok "every match connection passed to B" || fail "match connections not all to B: see above"
on "$A" systemctl start backend-arena@a1 backend-arena@a2 backend-arena@a3
for port in 9110 9111 9113; do
    for i in $(seq 120); do on "$A" curl -s -o /dev/null "http://127.0.0.1:$port/metrics" && break; sleep 1; done
done

mark "B frozen, A going on"
read -r _ f0 < <(scrapes "$A")
nsenter -t "$PB" -n ip link set eth1 down && docker pause "$B" > /dev/null && t_frozen=$(date +%s)
sleep 12
gone=0
for a in b1 b2 b3; do [ "$(store_cmd "$A" 6379 "EXISTS arena:$a")" = 0 ] && gone=$((gone + 1)); done
[ "$gone" = 3 ] && ok "B's arenas gone from A's directory 12 s after B froze" || fail "$((3 - gone)) of B's arenas still in A's directory"
from=$(( $(on "$A" sh -c 'wc -l < /var/log/backend-nginx/stream.log') + 1 ))
play "$A" "with B frozen" lobby
got=$(matches_to "$A" "$from")
echo "$got" | sed 's/^/      /'
awk '$4 == 200 && $3 ~ /^127\.0\.0\.1:901[1-3]$/' <<< "$got" | grep -q . && ok "the match on A's own arenas" || fail "no match on A's arenas"
# A's watch saw B's arenas go: their reads failing, three a round, over at least one round of 15 s.
sleep $(( 20 - ($(date +%s) - t_frozen) > 0 ? 20 - ($(date +%s) - t_frozen) : 0 ))
read -r _ f1 < <(scrapes "$A")
[ $((f1 - f0)) -ge 3 ] && ok "A's watch: $((f1 - f0)) reads failed while B was frozen, its arenas' metrics" \
    || fail "A's watch did not see B's arenas go: $((f1 - f0)) reads failed while B was frozen"
docker unpause "$B" > /dev/null && nsenter -t "$PB" -n ip link set eth1 up
mark "B back"
executed=$(sql "$A" "SELECT REPLACE(@@global.gtid_executed, '\n', '')")
[ "$(sql "$B" "SELECT WAIT_FOR_EXECUTED_GTID_SET('$executed', 120)")" = 0 ] && ok "B's MySQL, back, has applied all A executed" \
    || fail "B's MySQL has not caught up with A within 120 s: $(sql "$B" "SELECT LAST_ERROR_MESSAGE FROM performance_schema.replication_connection_status")"
for s in 6379 6380; do
    for i in $(seq 60); do
        got=$(store_info "$B" "$s" | grep -oE '^(role|primary_link_status):[a-z]+' | tr '\n' ' ')
        [ "$got" = "role:replica primary_link_status:up " ] && break; sleep 1
    done
    [ "$got" = "role:replica primary_link_status:up " ] && ok "B's store on $s, back: its link up" || fail "B's store on $s, back: $got"
done
for i in $(seq 30); do
    back=0; for a in b1 b2 b3; do [ "$(store_cmd "$A" 6379 "EXISTS arena:$a")" = 1 ] && back=$((back + 1)); done
    [ "$back" = 3 ] && break; sleep 1
done
[ "$back" = 3 ] && ok "B's arenas in A's directory again, ${i} s after B was back" || fail "$((3 - back)) of B's arenas not back in A's directory"
fi

mark "A's MySQL lost, A running"
on "$A" systemctl disable --now backend-mysql > /dev/null 2>&1
out=$(on "$B" /opt/backend/scripts/promote-mysql.sh 10.0.0.1:3306 --old-is-down 2>&1)
echo "$out" | sed 's/^/      /'
grep -q '^now: a primary' <<< "$out" && ok "B's MySQL promoted" || fail "B's MySQL not promoted"
for i in $(seq 60); do
    c=$(on "$A" curl -s -o /dev/null -w '%{http_code}' --max-time 5 --cacert /etc/backend/tls/ca.pem -X POST -H 'Content-Type: application/json' \
        -d '{"username":"onbsql","password":"install-check-1"}' https://127.0.0.1/v1/accounts)
    [ "$c" = 201 ] && break; sleep 1
done
[ "$c" = 201 ] && ok "a registration through A's nginx, written on B's MySQL, ${i} s after the promotion" \
    || fail "no registration through A after the promotion: $c"
[ "$(sql "$B" "SELECT COUNT(*) FROM backend.account WHERE username = 'onbsql'")" = 1 ] && ok "the account is on B" || fail "the account is not on B"

mark "A lost"
tok=$(on "$A" cat /var/tmp/check-session)
on "$A" touch /var/tmp/probe.stop
sleep 2
on "$A" cat /var/tmp/probe.log > "$WORK/probe-a.log"
nsenter -t "$PA" -n ip link set eth1 down && docker pause "$A" > /dev/null && t_cut=$(date +%s)
n=0
while read -r what arg rest; do
    n=$((n + 1))
    case $what in
        guide)
            f=$(guide_script "$rest") || { FAILS=$((FAILS + 1)); break; }
            echo "--- A lost, $n on $arg: the install guide's $rest"
            on "$B" bash "$f" > "$WORK/a-lost-$n.log" 2>&1 || { fail "A lost, the install guide's $rest on B: exit $?"; tail -25 "$WORK/a-lost-$n.log"; break; }
            ;;
        B)
            echo "--- A lost, $n on B: $(sed -n 1p "$arg" | sed 's/^# //')"
            on "$B" bash "$(script "$arg")" > "$WORK/a-lost-$n.log" 2>&1 || { fail "A lost, $n on B: exit $?"; tail -25 "$WORK/a-lost-$n.log"; break; }
            sed 's/^/      /' "$WORK/a-lost-$n.log" | grep -E 'now:|primary|does not answer|REFUSED' | head -8
            ;;
        *) fail "the runbook's block for A lost runs on $what, not B" ;;
    esac
done < "$WORK/items-a-lost"
served=$(date +%s)
echo "--- B serving, $((served - t_cut)) s after A was cut off"
https_b() { on "$B" curl -s -o /dev/null -w '%{http_code}' --max-time 10 --cacert /etc/backend/tls/ca.pem "$@"; }
c=$(https_b -H "Authorization: Bearer $tok" https://127.0.0.1/v1/inventory)
[ "$c" = 200 ] && ok "the session made on A, before, served by B" || fail "the session made on A is not served by B: $c"
for u in installcheck onbsql $(awk '$2 == "register" && $4 == 201 { print $3 }' "$WORK/probe-a.log" | tail -3); do
    c=$(https_b -X POST -H 'Content-Type: application/json' -d "{\"username\":\"$u\",\"password\":\"install-check-1\"}" https://127.0.0.1/v1/sessions)
    [ "$c" = 200 ] && ok "$u, made on A, logs in on B" || fail "$u, made on A, does not log in on B: $c"
done
c=$(https_b -X POST -H 'Content-Type: application/json' -d '{"username":"onb","password":"install-check-1"}' https://127.0.0.1/v1/accounts)
[ "$c" = 201 ] && ok "a registration on B" || fail "no registration on B: $c"
on "$B" bash /check-guide.sh --launch-checks || fail "B's launch checks after A was lost"
watch_reads_all "$B" B
play "$B" "on B, A lost" lobby
got=$(sql "$B" "SELECT GROUP_CONCAT(CONCAT(kind, ':', ok) ORDER BY id) FROM (SELECT id, kind, ok FROM backend.backup_run ORDER BY id DESC LIMIT 2) r")
[ "$got" = "1:1,2:1" ] && ok "B's first dump and its proof: recorded, ok" || fail "B's last dump and proof: $got"

echo "--- the site through A's nginx while B was made and A's MySQL lost: every refusal"
awk -v limit="$GAP_LIMIT" '
    /^=== / { label = substr($0, 5); next }
    $2 == "inventory" { total++
        if ($3 == 200) { if (bad) { d = $1 - start; printf "      %5.1f s, %d refused (%s), in: %s\n", d, bad, codes, at
                                    if (d > max) max = d; bad = 0; codes = "" } }
        else { if (!bad) { start = $1; at = label }; bad++; if (index(codes, $3) == 0) codes = codes (codes == "" ? "" : " ") $3; refused++ } }
    $2 == "register" { regs++; if ($4 == 201) made++ }
    END { printf "      %d requests, %d refused; %d registrations, %d made\n", total, refused, regs, made
          if (bad) { printf "      still refused at the end, since: %s\n", at; exit 2 }
          if (max > limit) { printf "      the longest gap, %.1f s, is past %d s\n", max, limit; exit 1 } }
' "$WORK/probe-a.log" && ok "the site served throughout, each gap under $GAP_LIMIT s" || fail "the site through A: see above"
made=$(awk '$2 == "register" && $4 == 201 { print $3 }' "$WORK/probe-a.log" | sort)
onb=$(sql "$B" "SELECT username FROM backend.account WHERE username LIKE 'probe%' ORDER BY username" | sort)
[ -n "$made" ] && [ "$made" = "$onb" ] && ok "every probe registration on B: $(grep -c . <<< "$made")" \
    || fail "the probe's registrations on B differ: made $(grep -c . <<< "$made"), on B $(grep -c . <<< "$onb")"

docker unpause "$A" > /dev/null 2>&1
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
