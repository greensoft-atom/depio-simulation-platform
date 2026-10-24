#!/bin/bash
# Checks rotate-nginx-logs.sh (docs/operations/01-deploy.md §7, O-45, D-85) on this machine, with the release's nginx
# on a loopback port and a log directory of its own: a request logged; the logs renamed with a UTC stamp, nginx told
# to reopen them, the renamed ones compressed; the next request in a fresh access.log; the rotated files past
# KEEP_DAYS deleted and the others kept; an empty log left alone; nginx not running rotated all the same; KEEP_DAYS
# refused when it is not whole days. The systemd unit and its timer are check-units-el9.sh's.
#
#   scripts/check-nginx-logs.sh [release-dir]     default: target/release/backend-*/; a few seconds, needs curl.
#                                                  Ends "--- <n> failed", non-zero on any.
#
# ROTATE names another rotate-nginx-logs.sh, for a mutation check; PORT another loopback port than 18080.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROTATE=${ROTATE:-$HERE/rotate-nginx-logs.sh}
R=${1:-$(ls -d "$HERE"/../target/release/backend-*/ 2>/dev/null | head -1)}
NGINX=$R/nginx/sbin/nginx
PORT=${PORT:-18080}
T=$(mktemp -d "${TMPDIR:-/tmp}/check-nginx-logs.XXXXXX")
stop_nginx() { [ -f "$T/run/nginx.pid" ] && kill -QUIT "$(cat "$T/run/nginx.pid")" 2> /dev/null; sleep 0.3; }
trap 'stop_nginx; rm -rf "$T"' EXIT
fails=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }
[ -x "$NGINX" ] || { echo "no release's nginx at $NGINX: scripts/make-release.sh first" >&2; exit 2; }
command -v curl > /dev/null || { echo "needs curl" >&2; exit 2; }

mkdir -p "$T/log" "$T/run" "$T/tmp"
cat > "$T/nginx.conf" <<EOF
# The check's nginx: one worker, a loopback port, logs of its own; every path temporary. Its worker runs as
# root: after a reopen each worker opens the fresh log itself, by its path, and a temporary directory under
# root's is closed to any other user (seen: a 0-byte access.log, "Permission denied" in error.log). The
# workers as nginx, reopening under LogsDirectory=, are check-units-el9.sh's to check.
user root;
worker_processes 1;
pid $T/run/nginx.pid;
error_log $T/log/error.log warn;
events { worker_connections 64; }
http {
    access_log $T/log/access.log;
    client_body_temp_path $T/tmp; proxy_temp_path $T/tmp;     # the modules this nginx is built with (09 §5)
    server { listen 127.0.0.1:$PORT; location / { return 204; } }
}
EOF
"$NGINX" -c "$T/nginx.conf" -p "$T" -e "$T/log/error.log" || { echo "nginx did not start" >&2; exit 2; }
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -f "$T/run/nginx.pid" ] && break; sleep 0.2; done
ask() { curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/$1"; }
rotate() { LOG_DIR=$T/log PID_FILE=$T/run/nginx.pid KEEP_DAYS=${KEEP:-14} "$ROTATE"; }

[ "$(ask first)" = 204 ] && grep -q "/first" "$T/log/access.log" && pass "a request logged" || fail "the first request was not logged"
: > "$T/log/stream.log" && touch -d "20 days ago" "$T/log/stream.log"   # as on a machine that is not the front: empty, and old
touch -d "20 days ago" "$T/log/access.log.20261004T000500Z.gz"
touch -d "3 days ago" "$T/log/stream.log.20261021T000500Z.gz"

if out=$(rotate 2>&1); then pass "rotated: $(echo "$out" | tr '\n' ';' | cut -c1-120)"; else fail "rotate: $out"; fi
rotated=$(ls "$T"/log/access.log.*Z.gz 2> /dev/null | grep -v 20261004 | head -1)
[ -n "$rotated" ] && zcat "$rotated" | grep -q "/first" && pass "access.log renamed with a UTC stamp and compressed, the line in it" \
    || fail "no compressed access.log.<stamp>.gz holding the first line: $(ls "$T/log")"
[ "$(ask second)" = 204 ] && sleep 0.2
if [ -s "$T/log/access.log" ] && grep -q "/second" "$T/log/access.log" && ! grep -q "/first" "$T/log/access.log"; then
    pass "nginx reopened its logs: the next request in a fresh access.log"
else
    fail "the next request is not in a fresh access.log: $(ls -la "$T/log" | tail -n +2 | awk '{print $5, $9}' | tr '\n' ';')"
    tail -3 "$T/log/error.log" | sed 's/^/     error.log: /'
fi
[ -e "$T/log/access.log.20261004T000500Z.gz" ] && fail "a rotated file 20 days old kept past KEEP_DAYS=14" || pass "a rotated file past KEEP_DAYS deleted"
[ -e "$T/log/stream.log.20261021T000500Z.gz" ] && pass "one 3 days old kept" || fail "a rotated file 3 days old deleted"
[ -e "$T/log/stream.log" ] && [ -z "$(ls "$T"/log/stream.log.*Z* 2> /dev/null | grep -v 20261021)" ] \
    && pass "an empty log left alone, however old: not rotated, not deleted" || fail "an empty log was rotated or deleted: $(ls "$T/log")"

out=$(KEEP=0 rotate 2>&1); rc=$?
[ $rc = 2 ] && echo "$out" | grep -q "KEEP_DAYS" && pass "KEEP_DAYS=0 refused, exit 2" || fail "KEEP_DAYS=0: exit $rc, $out"
out=$(KEEP=14.5 rotate 2>&1); rc=$?
[ $rc = 2 ] && echo "$out" | grep -q "KEEP_DAYS" && pass "KEEP_DAYS=14.5 refused" || fail "KEEP_DAYS=14.5: exit $rc, $out"
[ -z "$(ls "$T"/log/access.log.*Z.gz | grep -v 20261004 | sed -n 2p)" ] && pass "a refused setting rotated nothing" \
    || fail "a refused setting rotated: $(ls "$T/log")"

stop_nginx
echo "written after nginx stopped" >> "$T/log/access.log"
if out=$(rotate 2>&1); then
    n=$(ls "$T"/log/access.log.*Z.gz | grep -vc 20261004)
    [ "$n" = 2 ] && pass "nginx not running: rotated all the same, no signal sent" || fail "nginx not running: $n rotated files, $out"
else
    fail "nginx not running: rotate failed: $out"
fi

echo "--- $fails failed"
[ "$fails" = 0 ]
