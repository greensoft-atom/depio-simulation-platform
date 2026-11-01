#!/bin/bash
# Runs docs/operations/03-install-guide.md as written: every bash block between its guide-steps
# markers, in order, in Red Hat's UBI 9 init image (systemd its first process) with NO network, on a
# plain copy of the repository's files (what a clone has: tracked and new, nothing ignored). From the
# build with build-offline.sh to a registration through nginx; then the launch's one machine as D-93 lays
# it out (every unit, each arena by its name through nginx, every process's metrics watched, the result
# stream on its own store, a dump and its proof recorded and seen by the worker); all of it again
# after the container is restarted, as the machine at a reboot; and the database's disk lost, then the
# runbook's restore on one machine run as written (operations/02 §4), nothing committed lost.
#
#   scripts/check-install-guide-el9.sh        needs docker and git; about twenty-five minutes
#   IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-install-guide-el9.sh
#
# On a server that has the operating system's own Java and MySQL installed and running (the guide's
# §1), with the backend's MySQL on another port (GUIDE_VALUES: set after the guide's own values):
#
#   docker build -t backend-el9-os-packages - <<'EOF'
#   FROM rockylinux:9
#   RUN dnf install -y systemd java-1.8.0-openjdk-headless mysql-server tar gzip findutils sed openssl \
#           util-linux shadow-utils procps-ng && dnf clean all && systemctl enable mysqld
#   ENV JAVA_HOME=/usr/lib/jvm/jre-1.8.0
#   CMD ["/usr/sbin/init"]
#   EOF
#   IMAGE=backend-el9-os-packages GUIDE_VALUES="MYSQL_PORT=3307" scripts/check-install-guide-el9.sh
#
# One thing it adds that the guide does not do: systemd in a container cannot mount the private
# directory LoadCredential delivers into, so the Java units are given their secrets as files their
# user may read, by a drop-in (operations/01 §2 says what was and was not checked).
set -u
if [ "${1:-}" != --inside ] && [ "${1:-}" != --after-boot ] && [ "${1:-}" != --disk-lost ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REPO=$(cd "$HERE/../.." && pwd)
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-install-guide.XXXXXX")
    trap 'rm -rf "$WORK"' EXIT
    (cd "$REPO" && git ls-files --cached --others --exclude-standard \
        | tar -czf "$WORK/repository.tar.gz" -T -) || exit 1
    IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-init:9.5}
    NAME="backend-check-guide-$$"
    docker run -d --rm --privileged --network none --name "$NAME" -v "$WORK:/check:ro" \
        -v "$HERE/check-install-guide-el9.sh:/check.sh:ro" "$IMAGE" > /dev/null || exit 1
    sleep 5
    docker exec -e GUIDE_VALUES="${GUIDE_VALUES:-}" "$NAME" bash /check.sh --inside
    rc=$?
    # The machine restarted, as at a reboot: every unit enabled starts by itself, and the site serves again.
    echo "--- the container restarted"
    docker restart "$NAME" > /dev/null && docker exec "$NAME" bash /check.sh --after-boot || rc=1
    echo "--- the database's disk lost, and the runbook's restore"
    docker exec "$NAME" bash /check.sh --disk-lost || rc=1
    docker stop "$NAME" > /dev/null
    exit "$rc"
fi

# The launch's one machine (D-93): its units, each arena by its name, every process watched, the stores apart.
LAUNCH_UNITS="backend-mysql backend-store@session backend-store@events backend-platform backend-gateway
    backend-worker@c1 backend-worker@c2 backend-arena@a1 backend-arena@a2 backend-arena@a3 backend-nginx
    backend-binlog-stream backend-scratch-mysql"
LAUNCH_TIMERS="backend-mysql-backup.timer backend-restore-proof.timer backend-nginx-logs.timer"
fail() { echo "  FAIL $*"; FAILS=$((FAILS + 1)); }
store() {   # port, command...: the store's reply, its first line, after AUTH
    local r
    exec 3<> "/dev/tcp/127.0.0.1/$1" 2> /dev/null || { echo "(no store on $1)"; return; }
    shift
    for c in "AUTH $(cat /etc/backend/credentials/store-password)" "$*"; do
        set -- $c
        printf '*%d\r\n' $# >&3
        for a in "$@"; do printf '$%d\r\n%s\r\n' "${#a}" "$a" >&3; done
        IFS= read -r -t 5 r <&3
    done
    exec 3>&-
    echo "${r%$'\r'}"
}
launch_checks() {
    local u s n listening targets
    for u in $LAUNCH_UNITS $LAUNCH_TIMERS; do
        s=$(systemctl is-active "$u")
        [ "$s" = active ] || { fail "$u is $s"; journalctl -u "$u" -n 12 --no-pager | sed 's/^/      /'; }
    done
    for u in $LAUNCH_UNITS $LAUNCH_TIMERS; do
        s=$(systemctl is-enabled "$u" 2> /dev/null); [ "$s" = enabled ] || fail "$u is $s, so not started at boot"
    done
    # A unit systemd restarted is active again, and said: how often, and the lines of its exit.
    for u in $LAUNCH_UNITS; do
        n=$(systemctl show -p NRestarts --value "$u")
        [ "${n:-0}" = 0 ] || { echo "  NOTE $u restarted $n time(s); its exit:"
            journalctl -u "$u" --no-pager | grep -E -m1 -B1 -A3 'Exception|exited, code' | sed 's/^/      /'
            journalctl -u "$u" --no-pager | grep -E 'Caused by' | tail -2 | sed 's/^/      /'; }
    done
    # Each arena by its name on 9001: the certificate verified for it, and nginx passing it to that arena's port.
    : > /var/log/backend-nginx/stream.log
    for n in 1 2 3; do
        echo | openssl s_client -connect 127.0.0.1:9001 -servername "a$n.arena.internal" \
            -verify_hostname "a$n.arena.internal" -verify_return_error -CAfile /etc/backend/tls/ca.pem > /dev/null 2>&1 \
            || fail "a$n.arena.internal: no handshake on 9001 verified for its name"
    done
    sleep 2
    for n in 1 2 3; do
        grep -q "\"a$n.arena.internal\" 127.0.0.1:901$n 200 " /var/log/backend-nginx/stream.log \
            || fail "a$n.arena.internal not passed to its arena, 127.0.0.1:901$n: $(grep "a$n\." /var/log/backend-nginx/stream.log | tail -1)"
    done
    # Every process's metrics are watched: each port listening on loopback from 9100 to 9119 a target, and no other.
    # Java listens on IPv6 sockets, 127.0.0.1 as an IPv4-mapped address. A JVM still starting on a loaded machine
    # has not opened its port yet: up to two minutes for all of them.
    for i in $(seq 120); do
        listening=$(awk 'FNR > 1 && $4 == "0A" { split($2, a, ":"); p = strtonum("0x" a[2])
            if ((a[1] == "0100007F" || a[1] == "0000000000000000FFFF00000100007F") && p >= 9100 && p < 9120) print p }' \
            /proc/net/tcp /proc/net/tcp6 | sort -un | tr '\n' ' ')
        [ "$listening" = "9101 9102 9103 9110 9111 9112 9113 " ] && break
        sleep 1
    done
    targets=$(sed -n 's/^BACKEND_WATCH_TARGETS=//p' /etc/backend/platform.env | tr ',' '\n' | sed 's/.*://' | sort -un | tr '\n' ' ')
    [ "$listening" = "9101 9102 9103 9110 9111 9112 9113 " ] || fail "metrics listening on $listening, where D-93 has 9101 9102 9103 9110 9111 9112 9113"
    [ "$targets" = "$listening" ] || fail "the watch's targets are $targets, the metrics listening $listening"
    # Each store's maxmemory under half its heap, as ZGC needs (O-60): the heap in its env, maxmemory in its file.
    for n in session events; do
        heap_mb=$(sed -n 's/^STORE_HEAP=\([0-9]*\)g$/\1/p' "/etc/backend/store-$n.env"); heap_mb=$(( ${heap_mb:-0} * 1024 ))
        max=$(sed -n 's/^maxmemory \([0-9]*\)\(mb\|gb\)$/\1 \2/p' "/etc/backend/store-$n.conf")
        max_mb=${max% *}; [ "${max#* }" = gb ] && max_mb=$(( max_mb * 1024 ))
        [ -n "$max" ] && [ "$heap_mb" -gt 0 ] && [ $(( max_mb * 2 )) -le "$heap_mb" ] \
            || fail "the $n store: maxmemory '$max' against a heap of $heap_mb MB"
    done
    # The result stream on the events store, apart from the sessions (D-7): there, not on the session store, and
    # every arena and worker connected to the events store as a store of its own.
    [ "$(store 6380 EXISTS s:match-result)" = ":1" ] || fail "no result stream on the events store, 6380"
    [ "$(store 6379 EXISTS s:match-result)" = ":0" ] || fail "the result stream on the session store, 6379"
    for port in 9110 9111 9113 9102 9112; do
        curl -s "http://127.0.0.1:$port/metrics" | grep -qx 'backend_events_store_connected 1\(\.0\)\?' \
            || fail "the process on $port has no events store of its own: its results would go to the session store"
    done
}

if [ "${1:-}" = --after-boot ]; then
    FAILS=0
    # The platform answers once it has started, minutes on a loaded machine.
    for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
    curl -s -o /dev/null http://127.0.0.1:8080/health || fail "the platform does not answer five minutes after the restart"
    sleep 15
    launch_checks
    # The session made before the restart still holds (the session store reloaded its log), and a login works.
    code=$(curl -s --cacert /etc/backend/tls/ca.pem -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $(cat /var/tmp/check-session)" https://127.0.0.1/v1/inventory)
    [ "$code" = 200 ] || fail "the session made before the restart is not served through nginx: $code"
    code=$(curl -s --cacert /etc/backend/tls/ca.pem -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d '{"username":"installcheck","password":"install-check-1"}' https://127.0.0.1/v1/sessions)
    [ "$code" = 200 ] || fail "the account made before the restart does not log in through nginx: $code"
    echo "--- after the restart: $FAILS failed"
    [ "$FAILS" = 0 ]
    exit
fi

if [ "${1:-}" = --disk-lost ]; then
    FAILS=0
    login() {   # username: the login's status through nginx
        curl -s --cacert /etc/backend/tls/ca.pem -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
            -d "{\"username\":\"$1\",\"password\":\"install-check-1\"}" https://127.0.0.1/v1/sessions
    }
    # A write after the last dump, which only the binlog copies hold.
    code=$(curl -s --cacert /etc/backend/tls/ca.pem -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d '{"username":"afterdump","password":"install-check-1"}' https://127.0.0.1/v1/accounts)
    [ "$code" = 201 ] || fail "no account registered after the dump: $code"
    sleep 3
    # The database's disk lost: MySQL stopped, its files gone.
    systemctl stop backend-mysql && rm -rf /var/lib/backend-mysql
    awk '/<!-- one-machine-restore -->/ { on = 1 } /<!-- \/one-machine-restore -->/ { on = 0 }
         on && /^```bash/ { inb = 1; next } on && inb && /^```/ { inb = 0; next } inb' \
        /srv/depio-simulation-platform/docs/operations/02-runbook.md > /tmp/restore.sh
    echo "--- the runbook's restore: $(grep -c . /tmp/restore.sh) lines"
    bash -euo pipefail /tmp/restore.sh > /tmp/restore.log 2>&1 || { fail "the runbook's restore: exit $?"; tail -25 /tmp/restore.log; }
    grep -iE "reconcil|restored|migration" /tmp/restore.log | tail -5
    for i in $(seq 300); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
    curl -s -o /dev/null http://127.0.0.1:8080/health || fail "the platform does not answer five minutes after the restore"
    sleep 15
    for u in installcheck afterdump; do
        code=$(login $u); [ "$code" = 200 ] || fail "$u does not log in after the restore: $code"
    done
    launch_checks
    last=$(/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -N \
        -e "SELECT CONCAT(kind, ':', ok) FROM backend.backup_run ORDER BY id DESC LIMIT 1" 2> /dev/null)
    [ "$last" = "1:1" ] || fail "the new server's first dump not recorded as a success: $last"
    # The backups whole again on the new server: its first dump and the stream's copies restore, as the weekly proof does.
    systemctl start backend-restore-proof || fail "the proof after the restore failed: $(journalctl -u backend-restore-proof -n 5 --no-pager | tail -3)"
    last=$(/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -N \
        -e "SELECT CONCAT(kind, ':', ok) FROM backend.backup_run ORDER BY id DESC LIMIT 1" 2> /dev/null)
    [ "$last" = "2:1" ] || fail "the proof after the restore not recorded as a success: $last"
    echo "--- after the restore: $FAILS failed"
    [ "$FAILS" = 0 ]
    exit
fi

echo "--- $(cat /etc/redhat-release); network interfaces besides loopback: $(ls /sys/class/net | grep -vc '^lo$')"
mkdir -p /srv/depio-simulation-platform
tar -xzf /check/repository.tar.gz -C /srv/depio-simulation-platform
GUIDE=/srv/depio-simulation-platform/docs/operations/03-install-guide.md

# The guide's steps as one script: its bash blocks in order. After the first (the values), this
# check's own: loopback for the address, a small buffer pool. After the one that installs the units, the container's
# credentials.
awk '
    BEGIN { print "set -euo pipefail" }
    /<!-- guide-steps -->/ { on = 1 }
    /<!-- \/guide-steps -->/ { on = 0 }
    on && /^```bash/ { inb = 1; n++; useradd = 0; backupcnf = 0; print "echo \"=== step " n "\""; next }
    on && inb && /^```/ {
        inb = 0
        if (n == 1) print "export ADDRESS=127.0.0.1 MYSQL_BUFFER_POOL=256M SCRATCH_BUFFER_POOL=256M " ENVIRON["GUIDE_VALUES"]
        if (useradd) print "container_credentials"
        if (backupcnf) print "container_backup_credentials"
        next
    }
    inb { print; if ($0 ~ /useradd/) useradd = 1; if ($0 ~ /mysql-scratch\.cnf/) backupcnf = 1 }
' "$GUIDE" > /tmp/guide-steps.sh
echo "--- the guide's steps: $(grep -c '^echo "=== step' /tmp/guide-steps.sh) blocks"

container_credentials() {
    install -d -o backend -m 0500 /etc/backend/container-credentials
    install -o backend -m 0400 /etc/backend/credentials/db-password /etc/backend/credentials/store-password \
        /etc/backend/container-credentials/
    for u in backend-platform backend-gateway backend-worker@ backend-arena@; do
        mkdir -p "/etc/systemd/system/$u.service.d"
        printf '[Service]\nEnvironment=BACKEND_DB_PASSWORD_FILE=/etc/backend/container-credentials/db-password\nEnvironment=BACKEND_STORE_PASSWORD_FILE=/etc/backend/container-credentials/store-password\n' \
            > "/etc/systemd/system/$u.service.d/container-credentials.conf"
    done
    systemctl daemon-reload
}
export -f container_credentials
container_backup_credentials() {
    install -d -o backend-backup -m 0500 /etc/backend/container-credentials-backup
    for c in mysql-binlog mysql-backup mysql-scratch; do
        install -o backend-backup -m 0400 "/etc/backend/credentials/$c.cnf" /etc/backend/container-credentials-backup/
    done
    local d=/etc/backend/container-credentials-backup
    for u in backend-binlog-stream:BINLOG_CNF=$d/mysql-binlog.cnf backend-mysql-backup:MYSQL_CNF=$d/mysql-backup.cnf \
             "backend-restore-proof:SOURCE_CNF=$d/mysql-backup.cnf SCRATCH_CNF=$d/mysql-scratch.cnf"; do
        mkdir -p "/etc/systemd/system/${u%%:*}.service.d"
        printf '[Service]\nEnvironment=%s\n' "${u#*:}" > "/etc/systemd/system/${u%%:*}.service.d/container-credentials.conf"
    done
    systemctl daemon-reload
}
export -f container_backup_credentials

bash /tmp/guide-steps.sh 2>&1 | tee /tmp/guide.log | grep -E "^=== step|^active|^inactive|^failed|^activating|registration|ERROR|error|refus" | head -60
rc=${PIPESTATUS[0]}
echo "--- the steps ran: exit $rc"
FAILS=0
[ "$rc" = 0 ] || { FAILS=1; tail -25 /tmp/guide.log; }
grep -q "a registration through nginx: 201" /tmp/guide.log || { echo "  FAIL no registration through nginx"; FAILS=1; }
bad=$(grep -cE "^(inactive|failed|activating)$" /tmp/guide.log)
[ "$bad" = 0 ] || { echo "  FAIL $bad units not active"; FAILS=1; }
launch_checks
# The first dump and its proof, which the guide takes at once: recorded, and seen by the workers' metrics.
runs=$(/opt/backend/mysql/bin/mysql --defaults-extra-file=/etc/backend/credentials/mysql-root.cnf -N \
    -e "SELECT CONCAT(kind, ':', ok) FROM backend.backup_run ORDER BY id" 2> /dev/null | tr '\n' ' ')
case "$runs" in *"1:1 "*"2:1 "*) ;; *) fail "backup_run holds $runs, not a dump (1) and a proof (2) that succeeded" ;; esac
for port in 9102 9112; do
    for kind in dump proof; do
        curl -s "http://127.0.0.1:$port/metrics" | grep -qE "^backend_backup_succeeded_timestamp_seconds\{kind=\"$kind\"\} [1-9]" \
            || fail "the worker on $port does not report the $kind's success"
    done
done
ls /var/lib/backend/binlog/binlog.0* > /dev/null 2>&1 || fail "no binlog copy in /var/lib/backend/binlog"
# A session, kept for the check after the restart.
curl -s --cacert /etc/backend/tls/ca.pem -X POST -H 'Content-Type: application/json' \
    -d '{"username":"installcheck","password":"install-check-1"}' https://127.0.0.1/v1/sessions \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' > /var/tmp/check-session
[ -s /var/tmp/check-session ] || fail "no login through nginx"
# The operating system's own, where the image has them: still there, and still running.
if command -v java > /dev/null; then echo "--- the system's java: $(java -version 2>&1 | head -1)"; fi
if systemctl cat mysqld > /dev/null 2>&1; then
    echo "--- the system's mysqld: $(systemctl is-active mysqld)"
fi
echo "--- listening: $(awk 'FNR > 1 && $4 == "0A" { split($2, a, ":"); print strtonum("0x" a[2]) }' /proc/net/tcp /proc/net/tcp6 | sort -un | tr '\n' ' ')"
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
