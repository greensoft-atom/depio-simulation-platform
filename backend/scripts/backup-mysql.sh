#!/bin/bash
# A consistent full dump of each database named, with the binlog position it was taken at, so that
# a restore can be carried forward to any later moment (06 §10: point-in-time recovery).
#
#   backup-mysql.sh [databases] [out-dir]        defaults: backend,account, /var/backups/backend
#
# The databases are a comma-separated list: the backend's and the account service's (D-117, 10 §6.3), each to
# a file of its own, <database>-<UTC time>.sql.gz, in one run, recorded as one outcome: good only if every dump
# was, so a database that failed stops the time the alerts read.
#
# Take it from a machine other than the database's (backend-mysql-backup.timer does): a dump
# on the disk it protects is lost with it. MYSQL_CNF names the option file for the connection
# (host, user, password, ssl-mode=VERIFY_IDENTITY, ssl-ca); unset, the local defaults are used.
#
# After the dumps have succeeded, each database's dumps older than KEEP_DAYS (8) are deleted, and, with BINLOG_DIR
# (binlog-stream.sh's copies), every copied binlog before the earliest file a remaining dump
# starts from: nothing a kept dump could need, and nothing else is kept for ever. The earliest of
# them all, not the oldest dump's: a dump kept from another server, a primary before a failover,
# names a file of another series, and the oldest dump's start then pruned the new series (O-66).
# And of every database's dumps in out-dir, not this run's alone: the binlogs are the server's.
#
# Run as a user with SELECT, SHOW VIEW, TRIGGER, EVENT, RELOAD and REPLICATION CLIENT, and
# nothing more: each was checked by removing it, and RELOAD (FLUSH TABLES) and REPLICATION
# CLIENT (SHOW MASTER STATUS) are refused without. PROCESS is not needed with
# --no-tablespaces, and it would show the user every session's statements. The
# application's own user has none of these beyond its database, deliberately.
# --single-transaction reads one snapshot without locking InnoDB tables. The dump has no
# USE statement, so it can be restored under another name: that is what lets the restore
# drill run beside production without touching it.
#
# Its outcome is recorded in backup_run, as the backup account, through record-backup.sh: the
# alerts read when a dump last succeeded (06 §10, D-71). RECORD=0 records nothing.
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
# Private: a dump is every account, password hashes included. Without this the directory and
# the dumps were world-readable.
umask 077
DBS=${1:-backend,account}
OUT=${2:-/var/backups/backend}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FILE=""
BEGAN=$(date +%s)
SAID=""
# A dump that fails part way leaves its .partial, gigabytes of it, and retention matches only
# finished dumps: removed on the way out, whatever the way. And whatever the way, the outcome is
# recorded: a failure as much as a success, or the alert waits a day to say what this knows now.
finish() {
    local status=$?
    [ -z "$FILE" ] || rm -f "$FILE.partial"
    if [ "${RECORD:-1}" = 1 ]; then
        if [ "$status" = 0 ]; then
            "$(dirname "$0")/record-backup.sh" dump "$BEGAN" 1 "$SAID" || status=1
        else
            "$(dirname "$0")/record-backup.sh" dump "$BEGAN" 0 "failed with status $status: ${SAID:-see the journal}" || true
        fi
    fi
    exit "$status"
}
trap finish EXIT
mkdir -p "$OUT"
CNF=()
[ -z "${MYSQL_CNF:-}" ] || CNF=(--defaults-extra-file="$MYSQL_CNF")
for DB in ${DBS//,/ }; do
    [[ $DB =~ ^[A-Za-z0-9_]+$ ]] || { SAID="not a database's name: $DB"; echo "$SAID" >&2; exit 2; }
    FILE="$OUT/$DB-$STAMP.sql.gz"
    START=$(date +%s.%N)
    # Said before it runs: a dump that fails is recorded naming the database it was of.
    SAID="${SAID:+$SAID; }$DB: dumping"
    mysqldump "${CNF[@]}" --single-transaction --source-data=2 --routines --triggers --events \
        --set-gtid-purged=OFF --no-tablespaces "$DB" | gzip > "$FILE.partial"
    END=$(date +%s.%N)
    # 8.0 writes MASTER_LOG_FILE, 8.2 and later SOURCE_LOG_FILE. head closes the pipe early by
    # design, which pipefail would count as zcat failing. Read before the dump takes its name: one
    # with no position, named, was the newest the proof took and later the oldest retention read (O-67).
    POSITION=$( { zcat "$FILE.partial" | head -40; } 2>/dev/null | grep -m1 -oE "(SOURCE|MASTER)_LOG_FILE='[^']+', (SOURCE|MASTER)_LOG_POS=[0-9]+" || true)
    [ -n "$POSITION" ] || { SAID="${SAID%"$DB: dumping"}$DB: the dump records no binlog position"; echo "$DB: the dump records no binlog position: is binary logging on?" >&2; exit 1; }
    mv "$FILE.partial" "$FILE"                  # a half-written dump never has the final name
    DONE=$(printf '%s: %s bytes in %.1f s, taken at %s' "$(basename "$FILE")" "$(stat -c %s "$FILE")" \
        "$(awk -v a="$START" -v b="$END" 'BEGIN { print b - a }')" "$POSITION")
    SAID="${SAID%"$DB: dumping"}$DONE"
    echo "backup $DONE"
done
FILE=""

# Retention, and only now that every dump has succeeded. The names carry the UTC time, so they sort
# in the order they were taken; binlog names sort in the order they were written.
# -mmin, not -mtime: -mtime +8 drops the fraction of a day and matches nine days and more.
for DB in ${DBS//,/ }; do
    find "$OUT" -maxdepth 1 -name "$DB-*.sql.gz" -mmin +$(( ${KEEP_DAYS:-8} * 1440 )) -print -delete
done
if [ -n "${BINLOG_DIR:-}" ]; then
    NEEDED=""
    while read -r d; do
        start=$( { zcat "$OUT/$d" | head -40; } 2>/dev/null \
            | grep -m1 -oE "(SOURCE|MASTER)_LOG_FILE='[^']+'" | cut -d"'" -f2 || true)
        [ -n "$start" ] || { echo "cannot read where $d starts: keeping every binlog copy" >&2; exit 1; }
        if [ -z "$NEEDED" ] || [[ "$start" < "$NEEDED" ]]; then NEEDED=$start; fi
    done < <(find "$OUT" -maxdepth 1 -name "*-*.sql.gz" -printf '%f\n' | sort)
    [ -n "$NEEDED" ] || { echo "no dump left to read a start from: keeping every binlog copy" >&2; exit 1; }
    find "$BINLOG_DIR" -maxdepth 1 -type f -regex '.*\.[0-9][0-9][0-9][0-9][0-9][0-9]' -printf '%f\n' \
        | sort | while read -r f; do
            # An if, not [[ ]] &&: that would end the loop, and the script, with status 1
            # whenever the last copy is one to keep, which is always.
            if [[ "$f" < "$NEEDED" ]]; then
                rm -f "$BINLOG_DIR/$f"
                echo "pruned binlog copy $f"
            fi
        done
fi
