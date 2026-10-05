#!/bin/bash
# Starts a second j-redis on a copy of a backup and compares what matters with the running
# store: the monthly store drill, beside restore-drill.sh for MySQL. The all-time leaderboard
# lives only here - MySQL keeps 90 days of matches - so this is the drill that says whether
# it survives losing the store.
#
#   store-restore-drill.sh <backup-dir> <source-port> <scratch-port> [session|events]
#
# A backup is one instance's: with the store split (D-7), the boards and names live on `session`
# and the results' stream and lists on `events`, so each is drilled with its own backup and port,
# naming which; with neither named, every family is compared, as on one store. Comparing a family
# on the instance that does not hold it passed empty against empty (O-20).
#
# JREDIS_SERVER and JREDIS_CLI override the launchers. The source's password comes from
# BACKEND_STORE_PASSWORD_FILE; the scratch server gets a random one, in a 0600 config file,
# because it holds a copy of live session tokens and must not be readable by other users.
# Counts are exact only if nothing writes to the source meanwhile.
set -euo pipefail
# The release's store client and runtime, beside scripts/ in /opt/backend (docs/detailed-design/09 §4);
# without them, j-redis's own installation.
REL=$(cd "$(dirname "$0")/.." && pwd)
if [ -x "$REL/jredis/bin/j-redis-cli" ]; then
    export JAVA_HOME=${JAVA_HOME:-$REL/runtime}
    JREDIS_BIN=$REL/jredis/bin
else
    JREDIS_BIN=/opt/j-redis/bin
fi
BACKUP=$1
SOURCE_PORT=$2
SCRATCH_PORT=$3
INSTANCE=${4:-both}
case "$INSTANCE" in session|events|both) ;; *) echo "the fourth argument is session or events"; exit 2 ;; esac
read -r -a SERVER <<< "${JREDIS_SERVER:-$JREDIS_BIN/j-redis-server}"
read -r -a CLI <<< "${JREDIS_CLI:-$JREDIS_BIN/j-redis-cli}"

WORK=$(mktemp -d)
# Before anything can fail: a copy of live session tokens must not outlive a failed drill. The
# trap below, once the server is started, replaces this one.
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"
SCRATCH_PW=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
cp -r "$BACKUP" "$WORK/data"                      # the server writes into its directory
cat > "$WORK/scratch.conf" <<CONF
port $SCRATCH_PORT
bind 127.0.0.1
dir $WORK/data
requirepass $SCRATCH_PW
CONF
chmod 600 "$WORK/scratch.conf"

cli() {    # cli <port> <password or empty> <command...>: the replies, and only the replies
    local port=$1 pw=$2 skip=0
    shift 2
    [ -n "$pw" ] && skip=1
    # Reading commands from standard input, the client still prints its "host:port>" prompt
    # before each reply. Left in, every comparison differed by the port; so the prompts go,
    # and so does the one reply that belongs to AUTH. An empty reply prints nothing, so the
    # next prompt follows on the same line: every prompt a line starts with goes.
    { [ -n "$pw" ] && echo "AUTH $pw"; printf '%s\n' "$@"; } | "${CLI[@]}" --raw -p "$port" \
        | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | tail -n +$((skip + 1))
}
SOURCE_PW=""
[ -n "${BACKEND_STORE_PASSWORD_FILE:-}" ] && SOURCE_PW=$(cat "$BACKEND_STORE_PASSWORD_FILE")

T0=$(date +%s.%N)
"${SERVER[@]}" "$WORK/scratch.conf" > "$WORK/server.log" 2>&1 &
PID=$!
# Keep the drill's own exit status. errexit applies inside the trap too, so the stopped
# server's 143 from wait would end the trap before it could exit with the drill's status.
trap 'status=$?; kill $PID 2>/dev/null; wait $PID 2>/dev/null || true; rm -rf "$WORK"; exit $status' EXIT
until cli "$SCRATCH_PORT" "$SCRATCH_PW" PING 2>/dev/null | grep -q PONG; do
    kill -0 $PID 2>/dev/null || { echo "the scratch server did not start:"; cat "$WORK/server.log"; exit 1; }
    sleep 0.2
done
printf 'the backup loaded and answers in %.1f s\n' "$(awk -v a="$T0" -v b="$(date +%s.%N)" 'BEGIN { print b - a }')"

FAIL=0
check() {  # check <label> <command>: the same answer from both
    local a b mark=ok
    a=$(cli "$SOURCE_PORT" "$SOURCE_PW" "$2" | sha256sum | cut -c1-12)
    b=$(cli "$SCRATCH_PORT" "$SCRATCH_PW" "$2" | sha256sum | cut -c1-12)
    local shown
    shown=$(cli "$SCRATCH_PORT" "$SCRATCH_PW" "$2" | head -1)
    [ "$a" = "$b" ] || { mark=DIFFERS; FAIL=1; }
    printf '  %-34s %-12s %s\n' "$1" "$shown" "$mark"
}
check "keys (DBSIZE)" "DBSIZE"
if [ "$INSTANCE" != events ]; then
    check "all-time board size" "ZCARD lb:score:alltime"
    check "all-time board, every member+score" "ZRANGE lb:score:alltime 0 -1 WITHSCORES"
    check "names (HLEN lb:name)" "HLEN lb:name"
fi
if [ "$INSTANCE" != session ]; then
    check "results in the stream" "XLEN s:match-result"
    check "results pending (the group)" "XPENDING s:match-result rewards"
    check "results in the inbox" "LLEN q:match-result"
    check "results set aside (dead)" "LLEN q:match-result:dead"
    check "results deferred" "LLEN q:match-result:deferred"
fi
[ "$FAIL" = 0 ] && echo "drill passed" || { echo "DRILL FAILED"; exit 1; }
