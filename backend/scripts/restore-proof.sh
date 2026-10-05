#!/bin/bash
# The weekly proof that the backups restore (06 §10, D-71): the newest dump and the binlog copies,
# restored onto the backup machine's scratch server while the primary keeps writing, checked as
# restore-drill.sh's PROOF=1 does, and the outcome recorded. backend-restore-proof.timer runs it.
#
#   restore-proof.sh <dump-dir> <binlog-dir> [source-db] [scratch-db]   defaults: backend, backend_proof
#
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
SOURCE=${3:-backend}
SCRATCH=${4:-backend_proof}
: "${SOURCE_CNF:?SOURCE_CNF must name the backup account's option file}"
: "${SCRATCH_CNF:?SCRATCH_CNF must name the scratch server's option file}"
HERE=$(dirname "$0")
BEGAN=$(date +%s)
LOG=$(mktemp)
FETCHED=""
finish() {
    local status=$?
    local said
    said=$(grep -m1 '^summary:' "$LOG" | cut -d' ' -f2- || true)
    # The restore and the replay together: what a last-resort restore would take (D-72).
    took=$(grep -m1 '^summary:' "$LOG" | awk '{ print $3 + $6 }' || true)
    [ "$status" = 0 ] && [ -n "$took" ] || took=""
    # `|| true`: errexit holds inside the trap too, and a log with no line to quote would end it before
    # the outcome is recorded, so a failing proof would leave no trace (O-15).
    [ "$status" = 0 ] || said="failed: $( { grep -E 'DIFFERS|MISSING|differ|refused|never|gap|not start|FAILED|ERROR' "$LOG" || tail -3 "$LOG"; } | head -3 | tr '\n' ' ' || true)${said:+ $said}"
    MYSQL_CNF=$SOURCE_CNF RECORD_DB=$SOURCE "$HERE/record-backup.sh" proof "$BEGAN" "$([ "$status" = 0 ] && echo 1 || echo 0)" \
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
NEWEST=$(find "$DUMPS" -maxdepth 1 -name "$SOURCE-*.sql.gz" -printf '%f\n' | sort | tail -1)
[ -n "$NEWEST" ] || { echo "no dump of $SOURCE in $DUMPS: refused" | tee -a "$LOG"; exit 1; }
# From off the site, the copies are closed files as old as their last copying: replayed to their end.
PROOF=1 TO_COPIES_END=${FROM_OFFSITE:-0} "$HERE/restore-drill.sh" "$DUMPS/$NEWEST" "$SOURCE" "$SCRATCH" "$BINLOGS" 2>&1 \
    | tee -a "$LOG"
