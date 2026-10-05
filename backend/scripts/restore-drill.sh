#!/bin/bash
# Restores a dump into a scratch database, carries it forward through the binlog to now, and
# checks it: the monthly drill (06 §10), because a backup that has never been restored is not
# a backup. Never touches the source database.
#
#   restore-drill.sh <dump.sql.gz> <source-db> <scratch-db> [binlog-dir]
#
# By default everything is this machine's server: the source is read, the scratch database is
# restored beside it, and the binlog replayed is the source's own. The recovery that matters
# is the other one - the database's machine is gone - and it is rehearsed with:
#   SOURCE_CNF   option file for the source, read only for its position and its row counts
#   SCRATCH_CNF  option file for the server to restore onto, which can be another machine's
#   binlog-dir   binlog-stream.sh's copies, replayed instead of the source's own files
#
# The scratch database is dropped first if it exists: give it a name nothing else uses.
#
# Two ways to run it besides the drill's exact one (06 §10, D-71):
#   STOP_AT   a UTC time, '2026-10-04 14:02:59': the replay stops at the last transaction before
#             it, to undo a mistake made at a known minute. Nothing at or after it is restored.
#   PROOF=1   the weekly proof, while the source keeps writing: exact row counts cannot hold, so
#             it checks what does - every table and the newest migration restored, nothing
#             committed before the capture missing, the ledger reconciled.
#   TO_COPIES_END=1  with a binlog-dir: replay to the end of the newest copy, not to the source's
#             position now - for copies fetched from off the site, which are closed files and as
#             old as their last copying (backup-offsite.sh). The source is not waited for.
#   NO_SOURCE=1  the last resort (06 §10, D-72): no source is left, the database's machines lost.
#             From the copies fetched back, to their end (TO_COPIES_END=1 implied); nothing is read from a source, the
#             target database must not exist yet (nothing is dropped), and the checks are what holds
#             without a source: every table restored, the newest migration named, the ledger
#             reconciled to every balance. Restore under the production name onto the new primary.
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
DUMP=$1
SOURCE=$2
SCRATCH=$3
COPIES=${4:-}
NO_SOURCE=${NO_SOURCE:-0}
SRC=(mysql); [ -z "${SOURCE_CNF:-}" ] || SRC=(mysql --defaults-extra-file="$SOURCE_CNF")
DST=(mysql); [ -z "${SCRATCH_CNF:-}" ] || DST=(mysql --defaults-extra-file="$SCRATCH_CNF")
if [ "$NO_SOURCE" = 1 ]; then
    # With no source, the copies' end is the only moment there is.
    TO_COPIES_END=1
    [ -n "$COPIES" ] || { echo "NO_SOURCE needs a binlog-dir: the copies fetched back" >&2; exit 2; }
    [ "${PROOF:-0}" != 1 ] || { echo "NO_SOURCE and PROOF: a proof compares with a source" >&2; exit 2; }
    # Nothing is dropped here: with no source to compare against, a name that exists may be the
    # only copy there is (O-19).
    [ "$("${DST[@]}" -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '$SCRATCH'")" = 0 ] \
        || { echo "$SCRATCH exists on the target: drop it yourself if it is to be replaced; nothing done" >&2; exit 2; }
# The same name is refused on the same server, however the option files differ: one copied with
# its host left as it was names the primary, and the drop below, unlogged, would have removed
# the production database there and on no replica.
elif [ "$SOURCE" = "$SCRATCH" ] && { [ "${SOURCE_CNF:-}" = "${SCRATCH_CNF:-}" ] || \
        [ "$("${SRC[@]}" -N -e "SELECT @@server_uuid")" = "$("${DST[@]}" -N -e "SELECT @@server_uuid")" ]; }; then
    echo "the scratch database must not be the source" >&2; exit 2
fi
now() { date +%s.%N; }
# awk, not bc: bc is not on a minimal install, and these scripts run from units there.
secs() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }

# Every session of the drill runs with sql_log_bin=0. Without it the restore itself was written
# to the binlog - so the replay below re-applied it on top of itself - and on a primary, a
# drill would replicate a whole scratch database to every replica.
NOLOG="SET sql_log_bin = 0;"

# The proof's floor: the newest ledger row and match the source has, read before its position,
# so that everything they name was committed before it and must be in the copy.
if [ "${PROOF:-0}" = 1 ] && [ "${TO_COPIES_END:-0}" != 1 ]; then
    read -r FLOOR_LEDGER FLOOR_MATCH <<< "$("${SRC[@]}" -N -e "SELECT (SELECT COALESCE(MAX(id), 0) FROM \`$SOURCE\`.ledger),
        (SELECT COALESCE(MAX(id), 0) FROM \`$SOURCE\`.matches)")"
fi

# Where the source is now: the drill carries the restore forward to exactly this moment.
# Checked, not trusted: under pipefail an empty answer ended the script with no word said.
if [ "${TO_COPIES_END:-0}" = 1 ]; then
    [ -n "$COPIES" ] || { echo "TO_COPIES_END needs a binlog-dir" >&2; exit 2; }
    STOP_FILE=$(find "$COPIES" -maxdepth 1 -type f -printf '%f\n' | sort | tail -1)
    STOP_POS=$(stat -c %s "$COPIES/$STOP_FILE")
else
    # SHOW BINARY LOG STATUS from 8.2; SHOW MASTER STATUS, 8.0's, is gone from 8.4.
    STATUS=$("${SRC[@]}" -N -e "SHOW BINARY LOG STATUS" 2>/dev/null || "${SRC[@]}" -N -e "SHOW MASTER STATUS")
    read -r STOP_FILE STOP_POS <<< "$(awk '{print $1, $2}' <<< "$STATUS")"
fi
[ -n "${STOP_POS:-}" ] || { echo "the source reports no binlog position: is binary logging on?" >&2; exit 1; }

# Everything the replay needs is found and checked before anything is restored: where the
# dump was taken, then every binlog from that file on, to be rewritten to the scratch name.
# 8.0 writes MASTER_LOG_FILE, 8.2 and later SOURCE_LOG_FILE; head closes the pipe early.
HEADER=$( { zcat "$DUMP" | head -40; } 2>/dev/null || true)
# "|| true": a grep that matches nothing fails the assignment under pipefail, and the script
# exited silently instead of saying why on the next line.
FILE=$(grep -m1 -oE "(SOURCE|MASTER)_LOG_FILE='[^']+'" <<< "$HEADER" | cut -d"'" -f2 || true)
POS=$(grep -m1 -oE "(SOURCE|MASTER)_LOG_POS=[0-9]+" <<< "$HEADER" | cut -d= -f2 || true)
[ -n "$FILE" ] && [ -n "$POS" ] || { echo "the dump records no binlog position" >&2; exit 1; }
if [ -n "$COPIES" ]; then
    # The copies trail the source by however far the stream is behind. Wait for them to reach
    # the position read above, and say how long that took: it is the recovery point.
    W0=$(now)
    [ "${TO_COPIES_END:-0}" = 1 ] || until [ "$(stat -c %s "$COPIES/$STOP_FILE" 2>/dev/null || echo 0)" -ge "$STOP_POS" ]; do
        awk -v a="$W0" -v b="$(now)" 'BEGIN { exit !(b - a > 30) }' && { echo "the copies never reached $STOP_FILE:$STOP_POS: is the stream running?" >&2; exit 1; }
        sleep 0.1
    done
    LAG=$(secs "$W0" "$(now)")
    echo "the copies reached $STOP_FILE:$STOP_POS $LAG s after it was read"
    mapfile -t LOGS < <(find "$COPIES" -maxdepth 1 -type f -printf '%f\n' | sort \
        | awk -v a="$FILE" -v b="$STOP_FILE" '$0 >= a && $0 <= b' | sed "s|^|$COPIES/|")
    [ "$(basename "${LOGS[0]:-}")" = "$FILE" ] || { echo "the copies do not start at $FILE, where the dump does" >&2; exit 1; }
    # A file missing from the middle would be skipped without a word, and a real recovery,
    # with no source left to compare against, would lack whatever it held.
    PREV=""
    for f in "${LOGS[@]}"; do
        N=$((10#${f##*.}))
        [ -z "$PREV" ] || [ "$N" -eq $((PREV + 1)) ] || { echo "the copies have a gap before $(basename "$f"): no restore can cross it" >&2; exit 1; }
        PREV=$N
    done
else
    DATADIR=$("${SRC[@]}" -N -e "SELECT @@datadir")
    INDEX=$("${SRC[@]}" -N -e "SELECT @@log_bin_index")
    mapfile -t LOGS < <(sed -n "/$FILE/,/$STOP_FILE/p" "$INDEX" | sed "s|^\./|$DATADIR|")
fi

"${DST[@]}" -e "$NOLOG DROP DATABASE IF EXISTS \`$SCRATCH\`; CREATE DATABASE \`$SCRATCH\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

T0=$(now)
{ echo "$NOLOG"; zcat "$DUMP"; } | "${DST[@]}" "$SCRATCH"
T1=$(now)

# --database filters on the name *after* --rewrite-db has renamed it (the mysqlbinlog manual
# says so, and it is easy to miss): filtering on the source's name replays nothing at all,
# silently. That is what the first run of this drill did.
# --skip-gtids: with GTIDs on, each transaction carries the source's GTID, and a server that
# has executed it (the source's own, or one a restore has run on before) skips it silently,
# while one with GTIDs off refuses it. The replay is new writes on the target (D-31, 06 §10).
# STOP_AT, read in UTC: mysqlbinlog reads a time in its own zone, and the binlog's are UTC.
UNTIL=(); [ -z "${STOP_AT:-}" ] || UNTIL=(--stop-datetime="$STOP_AT")
{ echo "$NOLOG"; TZ=UTC mysqlbinlog --skip-gtids --rewrite-db="$SOURCE->$SCRATCH" --database="$SCRATCH" \
    --start-position="$POS" --stop-position="$STOP_POS" "${UNTIL[@]}" "${LOGS[@]}"; } | "${DST[@]}" "$SCRATCH"
T2=$(now)

echo "restored $DUMP into $SCRATCH: dump $(secs "$T0" "$T1") s, then the binlog from" \
     "$FILE:$POS to $STOP_FILE:$STOP_POS${STOP_AT:+, stopping before $STOP_AT UTC,} in $(secs "$T1" "$T2") s"

# Checks. Row counts against the source are exact only if nothing wrote to it meanwhile. Every
# table the source has, read from the schema: a list written out here missed one (equipment).
FAIL=0
if [ "$NO_SOURCE" = 1 ]; then
    # Nothing to compare with: what was restored, and the migration it stands at.
    mapfile -t TABLES < <("${DST[@]}" -N -e "SELECT table_name FROM information_schema.tables
        WHERE table_schema = '$SCRATCH' AND table_type = 'BASE TABLE' ORDER BY table_name")
    [ "${#TABLES[@]}" -gt 0 ] || { echo "nothing was restored into $SCRATCH" >&2; exit 1; }
    for t in "${TABLES[@]}"; do
        printf '  %-22s restored %8s\n' "$t" "$("${DST[@]}" -N -e "SELECT COUNT(*) FROM \`$SCRATCH\`.\`$t\`")"
    done
    printf '  %-22s %s\n' "migration" "$("${DST[@]}" -N -e "SELECT version FROM \`$SCRATCH\`.flyway_schema_history
        WHERE success ORDER BY installed_rank DESC LIMIT 1")"
    TABLES=()
else
    mapfile -t TABLES < <("${SRC[@]}" -N -e "SELECT table_name FROM information_schema.tables
        WHERE table_schema = '$SOURCE' AND table_type = 'BASE TABLE' ORDER BY table_name")
    [ "${#TABLES[@]}" -gt 0 ] || { echo "the source has no tables: is $SOURCE the right name?" >&2; exit 1; }
fi
# Exact counts only when the copy is meant to be the source as it is now: not under the proof's
# writes, and not stopped at an earlier moment. Otherwise each table is only required to be there.
EXACT=1; { [ "${PROOF:-0}" = 1 ] || [ -n "${STOP_AT:-}" ]; } && EXACT=0
for t in "${TABLES[@]}"; do
    a=$("${SRC[@]}" -N -e "SELECT COUNT(*) FROM \`$SOURCE\`.\`$t\`")
    b=$("${DST[@]}" -N -e "SELECT COUNT(*) FROM \`$SCRATCH\`.\`$t\`" 2>/dev/null || echo missing)
    mark=ok
    if [ "$b" = missing ]; then mark=MISSING; FAIL=1
    elif [ "$EXACT" = 1 ] && [ "$a" != "$b" ]; then mark=DIFFERS; FAIL=1; fi
    printf '  %-22s source %8s  restored %8s  %s\n' "$t" "$a" "$b" "$mark"
done
if [ "${PROOF:-0}" = 1 ]; then
    # The newest migration, and nothing committed before the capture missing (06 §10, D-71).
    MIGRATION="SELECT version FROM %s.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"
    a=$("${SRC[@]}" -N -e "$(printf "$MIGRATION" "\`$SOURCE\`")")
    b=$("${DST[@]}" -N -e "$(printf "$MIGRATION" "\`$SCRATCH\`")")
    mark=ok; [ "$a" = "$b" ] || { mark=DIFFERS; FAIL=1; }
    printf '  %-22s source %8s  restored %8s  %s\n' "migration" "$a" "$b" "$mark"
    read -r GOT_LEDGER GOT_MATCH <<< "$("${DST[@]}" -N -e "SELECT (SELECT COALESCE(MAX(id), 0) FROM \`$SCRATCH\`.ledger),
        (SELECT COALESCE(MAX(id), 0) FROM \`$SCRATCH\`.matches)")"
    for pair in "ledger ${FLOOR_LEDGER:-0} $GOT_LEDGER" "matches ${FLOOR_MATCH:-0} $GOT_MATCH"; do
        read -r t want got <<< "$pair"
        mark=ok; [ "$got" -ge "$want" ] || { mark=MISSING; FAIL=1; }
        printf '  %-22s newest before the capture %8s  restored %8s  %s\n' "$t" "$want" "$got" "$mark"
    done
fi
if [ -n "${STOP_AT:-}" ]; then
    # Nothing at or after the moment: the mistake is not in the copy. created_at is UTC, as the
    # application's sessions write it (connectionTimeZone +00:00), and STOP_AT is UTC: compared as
    # they are (O-14: read through UNIX_TIMESTAMP, they took this session's zone).
    LATE=$("${DST[@]}" -N -e "SELECT COUNT(*) FROM \`$SCRATCH\`.ledger WHERE created_at >= '$STOP_AT'")
    printf '  %-22s %s ledger rows at or after %s\n' "stopped" "$LATE" "$STOP_AT"
    [ "$LATE" = 0 ] || FAIL=1
fi
# The ledger must reconcile to every balance, coins and gems, as in production (06 §3; O-13: it
# summed coins alone, and gems have moved through it since item 68).
MISMATCH=$("${DST[@]}" -N -e "SELECT COUNT(*) FROM \`$SCRATCH\`.player p LEFT JOIN
    (SELECT player_id, SUM(IF(currency = 0, delta, 0)) coins, SUM(IF(currency = 1, delta, 0)) gems
     FROM \`$SCRATCH\`.ledger GROUP BY player_id) l
    ON l.player_id = p.id WHERE p.coins <> COALESCE(l.coins, 0) OR p.gems <> COALESCE(l.gems, 0)")
printf '  %-22s %s players whose coins or gems differ from their ledger\n' "reconciliation" "$MISMATCH"
[ "$MISMATCH" = 0 ] || FAIL=1
# One line a caller can record (restore-proof.sh): how long each part took, and how far behind
# the copies were.
echo "summary: restore $(secs "$T0" "$T1") s, replay $(secs "$T1" "$T2") s, recovery point ${LAG:-0.0} s"
[ "$FAIL" = 0 ] && echo "drill passed" || { echo "DRILL FAILED"; exit 1; }
