#!/bin/bash
# Promotes a j-redis replica to primary, on the replica's machine (D-34, D-8; operations/02 §2).
#
#   promote-store.sh <replica config file> <old primary host:port> --old-is-down
#   promote-store.sh <replica config file> <old primary host:port> --demote-old <this replica host:port>
#
# Two primaries taking writes is the one failure the store cannot prevent, so this will not promote
# while the old primary may still take them: either the operator says it is down or cut off
# (--old-is-down, refused if it still answers as a primary), or the script demotes it first,
# making it a replica of this one (--demote-old, with the address the old primary can reach this
# one at). Then REPLICAOF NO ONE here, which raises the epoch and records it; `replicaof` is taken
# out of the config file (a copy kept), since the role at start-up is the file's; and the new
# role and epoch are printed. The clients, given both addresses, follow by themselves.
#
# JREDIS_CLI overrides the command-line client. The password is the config file's requirepass,
# the one both servers of a store share.
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
CONF=${1:?config file}
OLD=${2:?old primary host:port}
MODE=${3:?--old-is-down or --demote-old}
SELF=${4:-}
read -r -a CLI <<< "${JREDIS_CLI:-$JREDIS_BIN/j-redis-cli}"

[ -f "$CONF" ] || { echo "no config file $CONF"; exit 2; }
# Before anything is done: the promotion rewrites the file and keeps a copy beside it, and one that
# dies after REPLICAOF NO ONE, with `replicaof` still in the file, is demoted again by its next
# start. j-redis's own files are root's (its guide's install): run this as root (O-17).
if [ ! -r "$CONF" ] || [ ! -w "$CONF" ] || [ ! -w "$(dirname "$CONF")" ]; then
    echo "REFUSED: $(id -un) cannot read and rewrite $CONF and write beside it: run as root. Nothing done."
    exit 2
fi
PORT=$(awk '$1 == "port" { print $2 }' "$CONF" | tail -1)
PORT=${PORT:-6379}
PASSWORD=$(awk '$1 == "requirepass" { print $2 }' "$CONF" | tail -1)

cli() {    # cli <host> <port> <command...>: the replies only, every prompt a line starts with taken off
    local host=$1 port=$2 skip=0
    shift 2
    [ -n "$PASSWORD" ] && skip=1
    { [ -n "$PASSWORD" ] && echo "AUTH $PASSWORD"; printf '%s\n' "$@"; } \
        | timeout 10 "${CLI[@]}" --raw -h "$host" -p "$port" 2>/dev/null \
        | sed -E 's/^([^ ]+:[0-9]+> ?)+//' | sed '/^$/d' | tail -n +$((skip + 1))
}

ROLE=$(cli 127.0.0.1 "$PORT" ROLE | head -1 || true)
if [ "$ROLE" = "primary" ]; then
    echo "127.0.0.1:$PORT is a primary already: nothing to do"
    cli 127.0.0.1 "$PORT" ROLE | tail -1 | sed 's/^/epoch /'
    exit 0
fi
[ "$ROLE" = "replica" ] || { echo "127.0.0.1:$PORT does not answer as a replica (got '$ROLE'): is it running, is the password right?"; exit 1; }

OLD_HOST=${OLD%:*}
OLD_PORT=${OLD##*:}
OLD_ROLE=$(cli "$OLD_HOST" "$OLD_PORT" ROLE | head -1 || true)
case "$MODE" in
    --old-is-down)
        if [ "$OLD_ROLE" = "primary" ]; then
            echo "REFUSED: $OLD still answers as a primary. Stop it, cut it off, or use --demote-old."
            exit 1
        fi
        echo "the old primary $OLD does not answer as one (${OLD_ROLE:-no answer}): promoting"
        ;;
    --demote-old)
        [ -n "$SELF" ] || { echo "--demote-old needs this replica's address, as the old primary reaches it"; exit 2; }
        if [ "$OLD_ROLE" = "primary" ]; then
            REPLY=$(cli "$OLD_HOST" "$OLD_PORT" "REPLICAOF ${SELF%:*} ${SELF##*:}")
            [ "$REPLY" = "OK" ] || { echo "REFUSED: $OLD would not be demoted: $REPLY"; exit 1; }
            echo "the old primary $OLD is a replica of $SELF now: it takes no writes"
        else
            echo "the old primary $OLD does not answer as one (${OLD_ROLE:-no answer}): promoting"
        fi
        ;;
    *)
        echo "the third argument is --old-is-down or --demote-old"; exit 2
        ;;
esac

REPLY=$(cli 127.0.0.1 "$PORT" "REPLICAOF NO ONE")
[ "$REPLY" = "OK" ] || { echo "the promotion failed: $REPLY"; exit 1; }

COPY="$CONF.before-promotion-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$CONF" "$COPY"
sed -i -E 's/^(replicaof[[:space:]].*)$/# \1    # taken out by promote-store.sh: this server is the primary now/' "$CONF"
echo "config: replicaof taken out of $CONF (the previous file is $COPY)"

ROLE=$(cli 127.0.0.1 "$PORT" ROLE)
echo "now: $(echo "$ROLE" | head -1), epoch $(echo "$ROLE" | tail -1)"
