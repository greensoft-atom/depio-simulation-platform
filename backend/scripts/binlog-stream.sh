#!/bin/bash
# Copies the primary's binlogs to this machine as they are written, so that a point-in-time
# restore survives losing the database's own disk (06 §10: binlog shipping). Run it on a
# machine other than the database's, under backend-binlog-stream.service.
#
#   binlog-stream.sh <out-dir>
#
# The connection is a MySQL option file in BINLOG_CNF: host, user, password, and
# ssl-mode=VERIFY_IDENTITY with ssl-ca, so the password stays off the command line and the
# stream is encrypted and checked. The user needs REPLICATION SLAVE (the stream) and
# REPLICATION CLIENT (SHOW BINARY LOGS, to know where to start).
#
# It resumes from the newest file it already has. --raw rewrites that file from its start,
# so one left half-written by a crash is completed, never duplicated. If the server has
# purged that file meanwhile, mysqlbinlog fails and says so: the copies then have a gap
# that no restart can fill, and a new dump is the only way to a restorable point again.
set -euo pipefail
# The release's MySQL tools first, from mysql/ beside scripts/ in /opt/backend (docs/detailed-design/09
# §6); run from the source tree, the machine's own.
PATH="$(cd "$(dirname "$0")/.." && pwd)/mysql/bin:$PATH"
OUT=$1
: "${BINLOG_CNF:?BINLOG_CNF must name the MySQL option file for the stream}"
# Private: the binlogs are every change to every row, password hashes included.
umask 077
mkdir -p "$OUT"
cd "$OUT"

LAST=$(find . -maxdepth 1 -type f -regex '.*\.[0-9][0-9][0-9][0-9][0-9][0-9]' -printf '%f\n' \
    | sort | tail -1)
if [ -z "$LAST" ]; then
    LAST=$(mysql --defaults-extra-file="$BINLOG_CNF" -N -e "SHOW BINARY LOGS" | head -1 | cut -f1)
    [ -n "$LAST" ] || { echo "the server lists no binary logs: is log_bin on?" >&2; exit 1; }
fi
echo "streaming from $LAST into $OUT"

# A server id of its own: two connections with one id make the server drop the older.
exec mysqlbinlog --defaults-extra-file="$BINLOG_CNF" --read-from-remote-server --raw \
    --stop-never --connection-server-id="${BINLOG_SERVER_ID:-900}" --result-file="$OUT/" "$LAST"
