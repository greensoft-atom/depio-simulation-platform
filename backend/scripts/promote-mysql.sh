#!/bin/bash
# Promotes MySQL's replica to primary, on the replica's machine (docs detailed-design/06 §10, D-35;
# operations/02 §2).
#
#   promote-mysql.sh <old primary host:port> --old-is-down [--stale-ok]
#   promote-mysql.sh <old primary host:port> --demote-old [--stale-ok]
#
# It will not promote while the old primary may still serve the backend: either the operator says
# it is down or cut off (--old-is-down, refused if it still answers), or the script fences it first
# (--demote-old): super_read_only, persisted, which stops every write, and offline_mode, which
# closes every connection but an administrator's and refuses new ones (D-29). offline_mode is not
# persisted: a restart leaves no connection to close, and it would outlive the server's rebuild
# as a replica and close it when it is next promoted (06 §10). Then it waits until
# this replica has applied every transaction it received, stops replicating and forgets its source,
# becomes writable with SET PERSIST (which outranks the configuration file, so a restart keeps it
# so), and raises the failover epoch, which the backend's processes follow.
#
# First it says what a promotion would lose (D-58, O-10): the primary's last heartbeat this replica
# applied, and how old it is. Older than five minutes, or not readable, it refuses before touching
# anything unless given --stale-ok: a replica that stopped receiving or applying long ago is not
# promoted without a word. Compare the time with when the primary was lost; a promotion well after an outage is what
# --stale-ok is for. PROMOTE_STALE_SECONDS lowers the five minutes, for a rehearsal.
#
# MYSQL_FAILOVER_CNF: the option file of the account it uses, `failover` (operations/01 §9):
# [client] with its user and password, and [client_remote], read only for the old primary, with
# the TLS settings. This machine's server is reached as [client] says: by default, its socket.
# BACKEND_DB_NAME: the backend's database (backend). MYSQL: the client (mysql).
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
OLD=${1:?old primary host:port}
MODE=${2:?--old-is-down or --demote-old}
STALE_OK=${3:-}
[ -z "$STALE_OK" ] || [ "$STALE_OK" = --stale-ok ] || { echo "the third argument, if any, is --stale-ok"; exit 2; }
CNF=${MYSQL_FAILOVER_CNF:-/etc/backend/credentials/mysql-failover.cnf}
DB=${BACKEND_DB_NAME:-backend}
read -r -a MYSQL <<< "${MYSQL:-mysql}"
[ -r "$CNF" ] || { echo "cannot read $CNF"; exit 2; }

sql() {    # sql <host> <port> <statement>: tab-separated rows, no headers
    timeout 20 "${MYSQL[@]}" --defaults-extra-file="$CNF" --defaults-group-suffix=_remote --protocol=TCP \
        -h "$1" -P "$2" -N -B -e "$3"
}
here() {   # this machine's server; LIMIT overrides the 20 s a statement is given
    timeout "${LIMIT:-20}" "${MYSQL[@]}" --defaults-extra-file="$CNF" -N -B -e "$1"
}

SOURCE=$(here "SELECT COUNT(*) FROM performance_schema.replication_connection_configuration")
if [ "$SOURCE" = "0" ]; then
    echo "this server replicates from nothing: it is a primary already"
    here "SELECT CONCAT('epoch ', epoch) FROM $DB.ha_epoch WHERE id = 1"
    exit 0
fi

# What it would lose, before anything is touched (D-58): at is the primary's clock, in UTC.
BEAT=$(here "SELECT CONCAT(at, ' ', TIMESTAMPDIFF(SECOND, at, UTC_TIMESTAMP(6))) FROM $DB.ha_heartbeat WHERE id = 1" \
       2>/dev/null || true)
if [ -n "$BEAT" ]; then
    AGE=${BEAT##* }
    echo "the primary's last heartbeat this replica applied: ${BEAT% *} UTC, $AGE s ago"
    if [ "$AGE" -gt "${PROMOTE_STALE_SECONDS:-300}" ] && [ -z "$STALE_OK" ]; then
        echo "REFUSED: this replica holds nothing the primary wrote in the last $AGE s. If the primary was lost"
        echo "about then, run again with --stale-ok; if not, its replication stopped earlier, and that is lost."
        exit 1
    fi
else
    echo "the primary's last heartbeat could not be read (no ha_heartbeat row, or the failover account lacks its"
    echo "grant): what a promotion would lose is not known"
    if [ -z "$STALE_OK" ]; then
        echo "REFUSED: not knowing is not promoting; run again with --stale-ok to promote all the same."
        exit 1
    fi
fi

OLD_HOST=${OLD%:*}
OLD_PORT=${OLD##*:}
OLD_RO=$(sql "$OLD_HOST" "$OLD_PORT" "SELECT @@global.read_only" 2>/dev/null || true)
case "$MODE" in
    --old-is-down)
        if [ -n "$OLD_RO" ]; then
            echo "REFUSED: $OLD still answers. Stop it, cut it off, or use --demote-old."
            exit 1
        fi
        echo "the old primary $OLD does not answer: promoting"
        ;;
    --demote-old)
        if [ -n "$OLD_RO" ]; then
            FENCE=$(sql "$OLD_HOST" "$OLD_PORT" "SET PERSIST super_read_only = ON; SET GLOBAL offline_mode = ON;
                                                SELECT @@global.super_read_only + @@global.offline_mode" || true)
            [ "$FENCE" = "2" ] || { echo "REFUSED: $OLD could not be fenced"; exit 1; }
            echo "the old primary $OLD is fenced: read-only, and closed to all but administrators"
        else
            echo "the old primary $OLD does not answer: promoting"
        fi
        ;;
    *)
        echo "the second argument is --old-is-down or --demote-old"; exit 2
        ;;
esac

# Everything received applied first: what the replica has but has not run would otherwise be lost.
RECEIVED=$(here "SELECT RECEIVED_TRANSACTION_SET FROM performance_schema.replication_connection_status" | head -1)
if [ -n "$RECEIVED" ]; then
    WAITED=$(LIMIT=70 here "SELECT WAIT_FOR_EXECUTED_GTID_SET('$RECEIVED', 60)") \
        || { echo "REFUSED: could not wait for the replica to apply what it received"; exit 1; }
    [ "$WAITED" = "0" ] || { echo "REFUSED: the replica did not apply what it received within 60 s"; exit 1; }
fi
here "STOP REPLICA; RESET REPLICA ALL; SET PERSIST super_read_only = OFF; SET PERSIST read_only = OFF"
here "UPDATE $DB.ha_epoch SET epoch = epoch + 1 WHERE id = 1"
echo "now: a primary, $(here "SELECT CONCAT('epoch ', epoch) FROM $DB.ha_epoch WHERE id = 1"), executed $(here "SELECT REPLACE(@@global.gtid_executed, '\\n', '')")"
