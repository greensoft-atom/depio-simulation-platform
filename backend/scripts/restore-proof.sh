#!/bin/bash
# The weekly proof that the backups restore (06 §10, D-71): each database's newest dump and the binlog copies,
# restored onto the backup machine's scratch server while the primary keeps writing, checked as
# restore-drill.sh's PROOF=1 does, and the outcome recorded. backend-restore-proof.timer runs it.
#
#   restore-proof.sh <dump-dir> <binlog-dir> [databases] [suffix]   defaults: backend,account, _proof
#
# The databases are a comma-separated list, the backend's and the account service's (D-117, 10 §6.3), each
# restored into <database><suffix>, and recorded as one outcome: good only if every one was proved.
#   RECORD_DB     the database whose backup_run the outcome goes in (default backend)
#   SOURCE_CNF    the backup account's option file: the primary, read, and the outcome recorded there
#   SCRATCH_CNF   the scratch server's
#   FROM_OFFSITE=1  restore from the copy off the site instead, fetched back and decrypted first
#                 (fetch-offsite.sh, with its OFFSITE_TARGET and OFFSITE_KEY): proving that copy too.
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
DUMPS=$1
BINLOGS=$2
DBS=${3:-backend,account}
SUFFIX=${4:-_proof}
# No apostrophe in these words: one inside "${...}" pairs with the next, and the second check vanished (O-65).
: "${SOURCE_CNF:?SOURCE_CNF must name the option file of the backup account}"
: "${SCRATCH_CNF:?SCRATCH_CNF must name the option file of the scratch server}"
HERE=$(dirname "$0")
BEGAN=$(date +%s)
LOG=$(mktemp)
FETCHED=""
finish() {
    local status=$?
    local said
    # Each database's summary, and the restores and replays together: what a last-resort restore would take (D-72).
    said=$(grep '^summary:' "$LOG" | cut -d' ' -f2- | paste -sd ';' || true)
    took=$(grep '^summary:' "$LOG" | awk '{ s += $3 + $6 } END { if (NR) print s }' || true)
    [ "$status" = 0 ] && [ -n "$took" ] || took=""
    # `|| true`: errexit holds inside the trap too, and a log with no line to quote would end it before
    # the outcome is recorded, so a failing proof would leave no trace (O-15).
    # A database that passed says "0 players ... differ": not a reason, and before the one that failed it took the record.
    [ "$status" = 0 ] || said="failed: $( { grep -E 'DIFFERS|MISSING|differ|refused|never|gap|not start|FAILED|ERROR' "$LOG" \
        | grep -v ' 0 players whose coins or gems differ' || tail -3 "$LOG"; } | head -3 | tr '\n' ' ' || true)${said:+ $said}"
    MYSQL_CNF=$SOURCE_CNF RECORD_DB=${RECORD_DB:-backend} "$HERE/record-backup.sh" proof "$BEGAN" "$([ "$status" = 0 ] && echo 1 || echo 0)" \
        "${FROM_OFFSITE:+from off the site: }$said" $took || status=1
    rm -f "$LOG"
    [ -z "$FETCHED" ] || rm -rf "$FETCHED"
    exit "$status"
}
trap finish EXIT
if [ "${FROM_OFFSITE:-0}" = 1 ]; then
    FETCHED=$(mktemp -d)
    "$HERE/fetch-offsite.sh" "$FETCHED" 2>&1 | tee -a "$LOG"     # its errors too: they are what the record quotes
    DUMPS=$FETCHED/dumps
    BINLOGS=$FETCHED/binlog
fi
for SOURCE in ${DBS//,/ }; do
    [[ $SOURCE =~ ^[A-Za-z0-9_]+$ ]] || { echo "not a database's name: $SOURCE: refused" | tee -a "$LOG"; exit 2; }
    NEWEST=$(find "$DUMPS" -maxdepth 1 -name "$SOURCE-*.sql.gz" -printf '%f\n' | sort | tail -1)
    [ -n "$NEWEST" ] || { echo "no dump of $SOURCE in $DUMPS: refused" | tee -a "$LOG"; exit 1; }
    echo "--- $SOURCE, from $NEWEST, into $SOURCE$SUFFIX" | tee -a "$LOG"
    # From off the site, the copies are closed files as old as their last copying: replayed to their end.
    PROOF=1 TO_COPIES_END=${FROM_OFFSITE:-0} "$HERE/restore-drill.sh" "$DUMPS/$NEWEST" "$SOURCE" "$SOURCE$SUFFIX" "$BINLOGS" 2>&1 \
        | tee -a "$LOG"
done
