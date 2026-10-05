#!/bin/bash
# Brings the copy off the site back and decrypts it, for a restore (06 §10, D-71): the dumps into
# <out>/dumps and the binlogs into <out>/binlog, the layout restore-drill.sh reads.
#
#   fetch-offsite.sh <out-dir>        with OFFSITE_TARGET and OFFSITE_KEY as backup-offsite.sh's
#
# The newest dump only, and every binlog copy: what a restore to the copies' end reads. ALL_DUMPS=1
# brings every dump, for a restore from an older one. Each file is decrypted and its encrypted copy
# removed as it goes, so the disk holds the set once, not twice (O-21).
set -euo pipefail
OUT=$1
: "${OFFSITE_TARGET:?OFFSITE_TARGET must name where the copy is}"
: "${OFFSITE_KEY:?OFFSITE_KEY must name the key file}"
umask 077
mkdir -p "$OUT/enc/dumps" "$OUT/enc/binlog" "$OUT/dumps" "$OUT/binlog"
# OFFSITE_SSH: how to reach the host, its key and known_hosts named (the backup user has no home).
SSH=(); [[ "$OFFSITE_TARGET" == *:* ]] && SSH=(-e "${OFFSITE_SSH:-ssh -o BatchMode=yes}")
if [ "${ALL_DUMPS:-0}" = 1 ]; then
    rsync -a "${SSH[@]}" "$OFFSITE_TARGET/dumps/" "$OUT/enc/dumps/"
else
    # The names carry the dump's UTC time, so they sort in the order the dumps were taken.
    NEWEST=$(rsync --list-only "${SSH[@]}" "$OFFSITE_TARGET/dumps/" | awk '{ print $NF }' \
        | grep '\.sql\.gz\.enc$' | sort | tail -1 || true)
    [ -n "$NEWEST" ] || { echo "no dump in $OFFSITE_TARGET/dumps" >&2; exit 1; }
    rsync -a "${SSH[@]}" "$OFFSITE_TARGET/dumps/$NEWEST" "$OUT/enc/dumps/"
fi
rsync -a "${SSH[@]}" "$OFFSITE_TARGET/binlog/" "$OUT/enc/binlog/"
for kind in dumps binlog; do
    while read -r f; do
        openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$OFFSITE_KEY" \
            -in "$f" -out "$OUT/$kind/$(basename "$f" .enc)"
        rm -f "$f"
    done < <(find "$OUT/enc/$kind" -maxdepth 1 -type f -name '*.enc' | sort)
done
rm -rf "$OUT/enc"
echo "fetched $(find "$OUT/dumps" -type f | wc -l) dumps and $(find "$OUT/binlog" -type f | wc -l) binlogs into $OUT"
