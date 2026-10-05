#!/bin/bash
# Copies the backups off the site (06 §10, D-71): every dump and every binlog copy, encrypted, so
# that a fire, a flood or a lost account that takes the three machines does not take them too.
#
#   backup-offsite.sh <dump-dir> <binlog-dir> <mirror-dir>
#
#   OFFSITE_TARGET  where they go: user@host:dir by SSH, or a directory. Unset, nothing is copied
#                   and nothing recorded: the owner has not named a host yet (Q-53).
#   OFFSITE_KEY     the key file, kept somewhere off the machines as well: without it the copy
#                   cannot be read, by an attacker or by the operator.
#   OFFSITE_SSH     the ssh command rsync uses, default "ssh -o BatchMode=yes".
#
# The primary's binary logs are flushed first (MYSQL_CNF, the backup account, which has RELOAD), so
# the file the stream was writing is closed and copied whole: only closed binlogs leave, and a
# restore from them replays to their end without a half-written event. The copy off the site is as
# far behind as the time since this last ran.
#
# Each file is encrypted into the mirror when it is new or has changed since, and the mirror's copy
# of a file pruned here is removed, so that rsync --delete leaves the host holding what this machine
# holds: 8 days of dumps and every binlog since the oldest. Its outcome is recorded through
# record-backup.sh (RECORD=0: not).
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
DUMPS=$1
BINLOGS=$2
MIRROR=$3
if [ -z "${OFFSITE_TARGET:-}" ]; then
    echo "no OFFSITE_TARGET: nothing copied off the site (Q-53)"
    exit 0
fi
: "${OFFSITE_KEY:?OFFSITE_KEY must name the key file}"
umask 077
BEGAN=$(date +%s)
SAID=""
finish() {
    local status=$?
    if [ "${RECORD:-1}" = 1 ]; then
        if [ "$status" = 0 ]; then
            "$(dirname "$0")/record-backup.sh" offsite "$BEGAN" 1 "$SAID" || status=1
        else
            "$(dirname "$0")/record-backup.sh" offsite "$BEGAN" 0 "failed with status $status: ${SAID:-see the journal}" || true
        fi
    fi
    exit "$status"
}
trap finish EXIT
mkdir -p "$MIRROR/dumps" "$MIRROR/binlog"
newest() { find "$BINLOGS" -maxdepth 1 -type f -regex '.*\.[0-9][0-9][0-9][0-9][0-9][0-9]' -printf '%f\n' | sort | tail -1; }
WAS=$(newest)
CNF=(); [ -z "${MYSQL_CNF:-}" ] || CNF=(--defaults-extra-file="$MYSQL_CNF")
mysql "${CNF[@]}" -e "FLUSH BINARY LOGS"
for _ in $(seq 300); do [ "$(newest)" != "$WAS" ] && break; sleep 0.1; done
[ "$(newest)" != "$WAS" ] || { SAID="the stream did not open the next binlog within 30 s"; echo "$SAID: is it running?" >&2; exit 1; }
WRITING=$(newest)

# Encrypts what is new or changed; removes from the mirror what is no longer here.
mirror() {
    local from=$1 to=$2 pattern=$3 skip=${4:-} f name
    while read -r f; do
        name=$(basename "$f")
        [ "$name" != "${skip:-}" ] || continue          # the binlog being written: next time, closed
        if [ ! -e "$to/$name.enc" ] || [ "$f" -nt "$to/$name.enc" ]; then
            openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass file:"$OFFSITE_KEY" \
                -in "$f" -out "$to/$name.enc.partial"
            mv "$to/$name.enc.partial" "$to/$name.enc"
            ENCRYPTED=$((ENCRYPTED + 1))
        fi
    done < <(find "$from" -maxdepth 1 -type f -regex "$pattern" | sort)
    while read -r f; do
        name=$(basename "$f" .enc)
        [ -e "$from/$name" ] || rm -f "$f"
    done < <(find "$to" -maxdepth 1 -type f -name '*.enc')
}
ENCRYPTED=0
mirror "$DUMPS" "$MIRROR/dumps" '.*\.sql\.gz'
mirror "$BINLOGS" "$MIRROR/binlog" '.*\.[0-9][0-9][0-9][0-9][0-9][0-9]' "$WRITING"

# OFFSITE_SSH: how to reach the host, its key and known_hosts named (the backup user has no home).
SSH=(); [[ "$OFFSITE_TARGET" == *:* ]] && SSH=(-e "${OFFSITE_SSH:-ssh -o BatchMode=yes}")
rsync -a --delete "${SSH[@]}" "$MIRROR/" "$OFFSITE_TARGET/"
SAID="$(find "$MIRROR" -type f -name '*.enc' | wc -l) files, $(du -sb "$MIRROR" | cut -f1) bytes, $ENCRYPTED encrypted anew, to $OFFSITE_TARGET"
echo "off the site: $SAID"
