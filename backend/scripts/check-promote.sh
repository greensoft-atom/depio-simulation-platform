#!/bin/bash
# Checks promote-mysql.sh and promote-store.sh (operations/02 §2, D-35, D-34) with stand-in clients, no server: what
# each takes for an old primary that is down and what for one that answers, and that a planned handover fences the
# old MySQL primary in the order that loses nothing. An old primary that answers, refusing the account or the
# password, is up, so --old-is-down refuses it; --demote-old refuses one it cannot fence or demote, since one it
# cannot reach may still serve the clients on its side; an old primary given without its port is refused. The real
# promotions are the failover drills' (client/headless-drill.sh, MYSQL_FAILOVER=demote and FAILOVER=1).
#
#   scripts/check-promote.sh      from anywhere; seconds. Ends "--- <n> failed", non-zero on any.
#
# PM and PS name another promote-mysql.sh or promote-store.sh, for a mutation check.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PM=${PM:-$HERE/promote-mysql.sh}
PS=${PS:-$HERE/promote-store.sh}
T=$(mktemp -d "${TMPDIR:-/tmp}/check-promote.XXXXXX")
trap 'rm -rf "$T"' EXIT
fails=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }

# MySQL's client, stood in for: this machine's server is the replica; the old primary, reached by -h, answers as
# OLD_IS says: up, down (no connection) or denied (the account refused). Every statement is logged, by server.
cat > "$T/mysql" <<'EOF'
#!/bin/bash
host="" stmt=""
while [ $# -gt 0 ]; do
    case $1 in -h) host=$2; shift ;; -e) stmt=$2; shift ;; esac
    shift
done
if [ -z "$host" ]; then
    echo "here: $stmt" >> "$LOG"
    case $stmt in
        *replication_connection_configuration*) echo 1 ;;
        *ha_heartbeat*) echo "2026-11-01 10:00:00.000000 2" ;;
        *RECEIVED_TRANSACTION_SET*) echo "aaaa:1-10" ;;
        *WAIT_FOR_EXECUTED_GTID_SET*) echo 0 ;;
        *"CONCAT('epoch '"*) echo "epoch 2" ;;
        *gtid_executed*) echo "aaaa:1-12" ;;
    esac
    exit 0
fi
echo "old: $stmt" >> "$LOG"
case $OLD_IS in
    down) echo "ERROR 2003 (HY000): Can't connect to MySQL server on '$host:3306' (111)" >&2; exit 1 ;;
    denied) echo "ERROR 1045 (28000): Access denied for user 'failover'@'10.0.0.3' (using password: YES)" >&2; exit 1 ;;
esac
case $stmt in
    "SELECT @@global.read_only") echo 0 ;;
    *offline_mode*) echo 2 ;;
    *super_read_only*) echo "1	aaaa:1-12" ;;
esac
EOF
chmod +x "$T/mysql"
printf '[client]\nuser=failover\npassword=x\n' > "$T/failover.cnf"
pm() {   # pm <old> <arguments...>: promote-mysql.sh against the stand-ins, its output; the log in $T/log
    local old=$1; shift
    : > "$T/log"
    OLD_IS=$old LOG=$T/log MYSQL=$T/mysql MYSQL_FAILOVER_CNF=$T/failover.cnf "$PM" "$@" 2>&1
}
promoted() { grep -q "^here: STOP REPLICA" "$T/log"; }

echo "--- promote-mysql.sh"
out=$(pm up 10.0.0.1 --old-is-down); rc=$?
[ "$rc" = 2 ] && ! promoted && pass "the old primary without its port: refused ($out)" || fail "without its port: rc $rc, $out"
out=$(pm denied 10.0.0.1:3306 --old-is-down)
! promoted && grep -q REFUSED <<< "$out" && pass "--old-is-down, the old one refusing the account: refused, it is up" \
    || fail "--old-is-down, the old one refusing the account: $out"
out=$(pm down 10.0.0.1:3306 --old-is-down)
promoted && pass "--old-is-down, the old one not answering: promoted" || fail "--old-is-down, not answering: $out"
out=$(pm down 10.0.0.1:3306 --demote-old)
! promoted && grep -q REFUSED <<< "$out" && pass "--demote-old, the old one not answering: refused, it may serve others" \
    || fail "--demote-old, the old one not answering: $out"
out=$(pm denied 10.0.0.1:3306 --demote-old)
! promoted && grep -q REFUSED <<< "$out" && pass "--demote-old, the old one refusing the account: refused" \
    || fail "--demote-old, the old one refusing the account: $out"
out=$(pm up 10.0.0.1:3306 --demote-old)
order=$(grep -n -e "^old: SET PERSIST super_read_only" -e "^here: SELECT WAIT_FOR_EXECUTED_GTID_SET('aaaa:1-12'" \
    -e "^old: SET GLOBAL offline_mode" -e "^here: STOP REPLICA" "$T/log" | cut -d: -f3 | tr -d ' ' | cut -c1-12 | tr '\n' ' ')
[ "$order" = "SETPERSISTsu SELECTWAIT_F SETGLOBALoff STOPREPLICA; " ] \
    && pass "--demote-old: read-only, then the replica waits for all the old one executed, then closed, then promoted" \
    || fail "--demote-old: the order was '$order': $out"

# j-redis's client, stood in for: this replica on 6392; the old primary on 6390 answers as OLD_IS says.
cat > "$T/cli" <<'EOF'
#!/bin/bash
port=""
while [ $# -gt 0 ]; do case $1 in -p) port=$2; shift ;; esac; shift; done
[ "$port" != 6392 ] && [ "$OLD_IS" = down ] && exit 1           # no connection: not a word
while read -r line; do
    echo "$port: $line" >> "$LOG"
    case $line in AUTH*) echo OK; continue ;; esac
    if [ "$port" = 6392 ]; then
        case $line in
            ROLE) if grep -q "^6392: REPLICAOF NO ONE" "$LOG"; then printf 'primary\n0\n2\n'; else printf 'replica\n10.0.0.1\n6390\nconnected\n100\n1\n'; fi ;;
            "REPLICAOF NO ONE") echo OK ;;
        esac
        continue
    fi
    case $OLD_IS in
        noauth) echo "NOAUTH Authentication required." ;;
        primary) case $line in ROLE) printf 'primary\n100\n1\n' ;; REPLICAOF*) echo OK ;; esac ;;
        replica) case $line in ROLE) printf 'replica\n10.0.0.2\n6392\nconnected\n100\n2\n' ;; esac ;;
    esac
done
EOF
chmod +x "$T/cli"
ps_() {   # ps_ <old> <arguments...>: promote-store.sh against the stand-ins; the log in $T/log
    local old=$1; shift
    : > "$T/log"
    printf 'port 6392\nrequirepass s3cret\nreplicaof 10.0.0.1 6390\n' > "$T/replica.conf"
    OLD_IS=$old LOG=$T/log JREDIS_CLI=$T/cli "$PS" "$T/replica.conf" "$@" 2>&1
}
store_promoted() { grep -q "^6392: REPLICAOF NO ONE" "$T/log"; }

echo "--- promote-store.sh"
out=$(ps_ primary 10.0.0.1 --old-is-down); rc=$?
[ "$rc" = 2 ] && ! store_promoted && pass "the old primary without its port: refused ($out)" || fail "without its port: rc $rc, $out"
out=$(ps_ noauth 10.0.0.1:6390 --old-is-down)
! store_promoted && grep -q REFUSED <<< "$out" && pass "--old-is-down, the old one answering NOAUTH: refused, it is up" \
    || fail "--old-is-down, the old one answering NOAUTH: $out"
out=$(ps_ down 10.0.0.1:6390 --old-is-down)
store_promoted && pass "--old-is-down, the old one not answering: promoted" || fail "--old-is-down, not answering: $out"
out=$(ps_ replica 10.0.0.1:6390 --old-is-down)
store_promoted && pass "--old-is-down, the old one a replica already: promoted" || fail "--old-is-down, a replica: $out"
out=$(ps_ down 10.0.0.1:6390 --demote-old 10.0.0.2:6392)
! store_promoted && grep -q REFUSED <<< "$out" && pass "--demote-old, the old one not answering: refused, it may serve others" \
    || fail "--demote-old, the old one not answering: $out"
out=$(ps_ noauth 10.0.0.1:6390 --demote-old 10.0.0.2:6392)
! store_promoted && grep -q REFUSED <<< "$out" && pass "--demote-old, the old one answering NOAUTH: refused" \
    || fail "--demote-old, the old one answering NOAUTH: $out"
out=$(ps_ primary 10.0.0.1:6390 --demote-old 10.0.0.2:6392)
grep -q "^6390: REPLICAOF 10.0.0.2 6392" "$T/log" && store_promoted && pass "--demote-old, the old one a primary: demoted, then promoted" \
    || fail "--demote-old, the old one a primary: $out"

echo "--- $fails failed"
[ "$fails" = 0 ]
