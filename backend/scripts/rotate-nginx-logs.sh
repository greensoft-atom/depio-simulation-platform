#!/bin/bash
# Rotates nginx's logs (docs/operations/01-deploy.md §7, D-85, O-45): each log in LOG_DIR that holds anything is
# renamed with a UTC stamp, nginx is told to reopen its logs (USR1 to the master PID_FILE names) when it runs, the
# renamed files are compressed, and rotated files older than KEEP_DAYS are deleted. Run nightly by
# backend-nginx-logs.timer on every machine; by hand:
#
#   LOG_DIR=/var/log/backend-nginx PID_FILE=/run/backend-nginx/nginx.pid KEEP_DAYS=14 scripts/rotate-nginx-logs.sh
#
# Exit 2 on a setting it refuses (KEEP_DAYS not whole days, 1 to 365), before anything is renamed.
set -u
LOG_DIR=${LOG_DIR:-/var/log/backend-nginx}
PID_FILE=${PID_FILE:-/run/backend-nginx/nginx.pid}
KEEP_DAYS=${KEEP_DAYS:-14}
case $KEEP_DAYS in
    ''|*[!0-9]*) echo "KEEP_DAYS must be whole days, 1 to 365, not '$KEEP_DAYS'" >&2; exit 2 ;;
esac
[ "$KEEP_DAYS" -ge 1 ] && [ "$KEEP_DAYS" -le 365 ] || { echo "KEEP_DAYS must be whole days, 1 to 365, not '$KEEP_DAYS'" >&2; exit 2; }
[ -d "$LOG_DIR" ] || { echo "no log directory $LOG_DIR" >&2; exit 2; }

stamp=$(date -u +%Y%m%dT%H%M%SZ)
moved=()
for name in access.log stream.log error.log; do
    log=$LOG_DIR/$name
    [ -s "$log" ] || continue                        # absent, or empty: nothing to rotate
    mv "$log" "$log.$stamp" && moved+=("$log.$stamp")
done
if [ ${#moved[@]} -gt 0 ]; then
    # nginx keeps writing to the renamed files until it is told to reopen: USR1 has the master open fresh ones
    # and its workers follow, within the second given here, before the renamed files are compressed (nginx's
    # own rotation example waits the same second).
    if pid=$(cat "$PID_FILE" 2> /dev/null) && [ -n "$pid" ] && kill -0 "$pid" 2> /dev/null; then
        kill -USR1 "$pid" && sleep 1 && echo "nginx $pid told to reopen its logs"
    else
        echo "nginx is not running: rotated without a signal"
    fi
    gzip -q "${moved[@]}"
    echo "rotated: ${moved[*]##*/}"
else
    echo "nothing to rotate"
fi
# The rotated files alone, which carry a suffix after .log; the live ones do not.
find "$LOG_DIR" -maxdepth 1 -type f -name '*.log.*' -mtime +"$KEEP_DAYS" -print -delete | sed 's/^/deleted: /'
