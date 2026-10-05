#!/bin/bash
# Records a backup step's outcome in backup_run, which every worker reports and the alerts read
# (06 §10, D-71): a step that failed, or never ran, shows as a time that stops moving.
#
#   record-backup.sh <dump|proof|offsite> <started, seconds since the epoch> <ok: 1|0> <detail> [seconds]
#
# seconds: a proof's restore and replay, the time a last-resort restore would take (D-72).
#
# As the backup account, which may insert into backup_run and nothing more there: MYSQL_CNF names
# its option file, RECORD_DB the database (default backend).
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
case $1 in dump) KIND=1 ;; proof) KIND=2 ;; offsite) KIND=3 ;; *) echo "no such step: $1" >&2; exit 2 ;; esac
STARTED=$2
OK=$3
# One line of at most 255 characters, quoted for SQL: what a step says when it fails is anything.
DETAIL=$(printf '%s' "$4" | tr '\n\t' '  ' | cut -c1-255 | sed "s/\\\\/\\\\\\\\/g; s/'/''/g")
CNF=(); [ -z "${MYSQL_CNF:-}" ] || CNF=(--defaults-extra-file="$MYSQL_CNF")
SECONDS_TAKEN=${5:-NULL}
[[ "$SECONDS_TAKEN" =~ ^([0-9]+(\.[0-9]+)?|NULL)$ ]] || { echo "seconds must be a number: $SECONDS_TAKEN" >&2; exit 2; }
mysql "${CNF[@]}" "${RECORD_DB:-backend}" -e "INSERT INTO backup_run (kind, started_at, finished_at, ok, detail, seconds)
    VALUES ($KIND, FROM_UNIXTIME($STARTED), NOW(3), $OK, '$DETAIL', $SECONDS_TAKEN)"
