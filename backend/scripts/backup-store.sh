#!/bin/bash
# A consistent copy of a running j-redis's data directory, by j-redis's own documented
# procedure (j-redis-service docs/08-persistence.md §12), and nothing cleverer:
#   1. stop automatic AOF rewrites for the length of the copy,
#   2. compact with BGREWRITEAOF and wait for it to finish,
#   3. copy the manifest first, then every file it lists,
#   4. put the rewrite setting back, even if the copy fails.
# Consistent because files are deleted only when a rewrite commits, and step 1 prevents that.
#
#   backup-store.sh <data-dir> <out-dir> [port]           JREDIS_CLI overrides the client
#
# The store's password, if it has one, is read from BACKEND_STORE_PASSWORD_FILE and sent on
# the client's standard input, never its command line, which every local user can read.
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
# Private: the copy holds every live session token, and anyone who can read one is that
# player until it expires. Without this the copies were world-readable.
umask 077
DATA=$1
OUT=$2
PORT=${3:-6379}
read -r -a CLI <<< "${JREDIS_CLI:-$JREDIS_BIN/j-redis-cli}"

cli() {    # one session: AUTH if configured, then each argument; prints the replies only
    local skip=0
    [ -n "${BACKEND_STORE_PASSWORD_FILE:-}" ] && skip=1
    {
        if [ -n "${BACKEND_STORE_PASSWORD_FILE:-}" ]; then
            echo "AUTH $(cat "$BACKEND_STORE_PASSWORD_FILE")"   # echo is a builtin: no process
        fi
        printf '%s\n' "$@"
    } | "${CLI[@]}" --raw -p "$PORT" | sed -E 's/^[^ ]+:[0-9]+> ?//' | sed '/^$/d' \
        | tail -n +$((skip + 1))
}

PREVIOUS=$(cli "CONFIG GET auto-aof-rewrite-percentage" | sed -n 2p | grep -oE '^[0-9]+$' || true)
[ -n "$PREVIOUS" ] || { echo "cannot read auto-aof-rewrite-percentage: is the store up, the password right?" >&2; exit 1; }
cli "CONFIG SET auto-aof-rewrite-percentage 0" > /dev/null
trap 'cli "CONFIG SET auto-aof-rewrite-percentage $PREVIOUS" > /dev/null' EXIT

START=$(date +%s.%N)
cli "BGREWRITEAOF" > /dev/null
until cli "INFO persistence" | grep -q 'aof_rewrite_in_progress:0'; do
    sleep 0.2
done

DEST="$OUT/store-$(date -u +%Y%m%dT%H%M%SZ)"
# A copy that fails part way is removed, as well as the setting put back: nothing else would
# ever delete a .partial, and it is every session token the store held.
trap 'rm -rf "$DEST.partial"; cli "CONFIG SET auto-aof-rewrite-percentage $PREVIOUS" > /dev/null' EXIT
mkdir -p "$DEST.partial"
cp "$DATA/manifest" "$DEST.partial/"                  # the manifest first: it names the rest
for f in $(awk '$1 == "base" || $1 == "incr" { print $2 }' "$DEST.partial/manifest"); do
    cp "$DATA/$f" "$DEST.partial/"
done
mv "$DEST.partial" "$DEST"                             # a half-copied backup never has the name
printf 'backup %s: %s in %.1f s\n' "$DEST" "$(du -sh "$DEST" | cut -f1)" \
    "$(awk -v a="$START" -v b="$(date +%s.%N)" 'BEGIN { print b - a }')"
