#!/bin/bash
# A live drill of the backups (06 §10, D-71), on MySQL servers of its own: a primary on 3307 (binary
# log, GTIDs) seeded with a copy of SEED_DB on 3306 (default backend_dev), and a scratch server on
# 3309 (no binary log), what the proof restores onto. It runs each script as production does, and
# checks what each must do and what each must refuse:
#
#   the binlog stream copying; the dump, recorded; writes after it, and a mistake at a known moment;
#   the copy off the site, encrypted, into a directory standing in for the host; the proof while a
#   writer keeps writing; a restore to the moment before the mistake; the proof from the copy off the
#   site; and refusals - a gap in the copies, a dump whose gems do not reconcile, a proof whose replay
#   stops short of what was committed.
#
#   backup-drill.sh            work in a new directory under TMPDIR; SEED_PASSWORD for 3306's backend
set -euo pipefail
# RELEASE=<dir>: the release's MySQL, server and tools, for the drill's own servers and the scripts
# (docs/detailed-design/09 §6); without it, the machine's.
if [ -n "${RELEASE:-}" ] && [ -x "$RELEASE/mysql/bin/mysqld" ]; then
    MYSQLD_BIN=$RELEASE/mysql/bin/mysqld; PATH="$RELEASE/mysql/bin:$PATH"
else
    MYSQLD_BIN=/usr/sbin/mysqld
fi
HERE=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/backup-drill.XXXXXX")
DB=backend_dev
PIDS=()
cleanup() {
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap cleanup EXIT
FAILS=0
check() { if eval "$1"; then echo "  ok   $2"; else echo "  FAIL $2"; FAILS=$((FAILS + 1)); fi; }
msql() { mysql --protocol=TCP -h 127.0.0.1 -P "$1" -u root -N -B -e "$2"; }
ready() { for _ in $(seq 60); do msql "$1" "SELECT 1" > /dev/null 2>&1 && return 0; sleep 1; done; return 1; }
server() {    # server <name> <port> <id> [option...]
    local dir=$W/$1 port=$2 id=$3
    shift 3
    mkdir -p "$dir"
    "$MYSQLD_BIN" --no-defaults --initialize-insecure --datadir="$dir/data" --user=root > "$dir/init.log" 2>&1
    : > "$dir/my.cnf"
    ( cd "$dir/data" && exec "$MYSQLD_BIN" --defaults-file="$dir/my.cnf" --datadir="$dir/data" --user=root \
        --port="$port" --bind-address=127.0.0.1 --socket=m.sock --mysqlx=OFF --server-id="$id" --log-bin=bin \
        --gtid-mode=ON --enforce-gtid-consistency=ON --log-error="$dir/error.log" "$@" ) > /dev/null 2>&1 &
    PIDS+=($!)
    ready "$port"
}
cnf() { printf '[client]\nuser=%s\npassword=%s\nprotocol=TCP\nhost=127.0.0.1\nport=%s\n' "$2" "$3" "$4" > "$W/$1.cnf"; chmod 600 "$W/$1.cnf"; }

echo "--- servers: a primary on 3307, a scratch server on 3309 ($W)"
server primary 3307 1
server scratch 3309 3 --skip-log-bin
msql 3307 "CREATE DATABASE $DB;
    CREATE USER 'backup'@'%' IDENTIFIED BY 'backup-drill';
    GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, RELOAD, REPLICATION CLIENT ON *.* TO 'backup'@'%';
    CREATE USER 'binlog'@'%' IDENTIFIED BY 'binlog-drill';
    GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'binlog'@'%'"
mysqldump -h 127.0.0.1 -P 3306 --protocol=TCP -u backend -p"${SEED_PASSWORD:-backend-dev-password}" --single-transaction \
    --set-gtid-purged=OFF --no-tablespaces "${SEED_DB:-backend_dev}" 2>/dev/null | mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u root "$DB"
msql 3307 "GRANT INSERT ON $DB.backup_run TO 'backup'@'%'"
cnf backup backup backup-drill 3307
cnf binlog binlog binlog-drill 3307
cnf scratch root "" 3309
PLAYERS=$(msql 3307 "SELECT COUNT(*) FROM $DB.player")
echo "--- seeded: $PLAYERS players, $(msql 3307 "SELECT COUNT(*) FROM $DB.ledger") ledger rows"
WHO=$(msql 3307 "SELECT MIN(id) FROM $DB.player")

# A move of a gem through the ledger's one path, as the platform makes it: a row and its balance, its
# time in UTC as the application's sessions write theirs (O-14).
gem() {
    msql 3307 "SET time_zone = '+00:00'; START TRANSACTION;
        INSERT INTO $DB.ledger (player_id, currency, delta, balance_after, reason, ref, idem_key)
            SELECT id, 1, 1, gems + 1, 5, '$1', CONCAT('drill:', UUID()) FROM $DB.player WHERE id = $WHO;
        UPDATE $DB.player SET gems = gems + 1 WHERE id = $WHO; COMMIT"
}

echo "--- the stream and the dump"
mkdir -p "$W/backup/binlog" "$W/backup/dumps"
BINLOG_CNF=$W/binlog.cnf "$HERE/binlog-stream.sh" "$W/backup/binlog" > "$W/stream.log" 2>&1 & PIDS+=($!)
# A moment before the dump, with a write after it that the dump will hold: a restore to it must refuse.
sleep 1.2
EARLY=$(date -u '+%Y-%m-%d %H:%M:%S')
sleep 1.2
gem between-the-moment-and-the-dump
MYSQL_CNF=$W/backup.cnf RECORD_DB=$DB BINLOG_DIR=$W/backup/binlog "$HERE/backup-mysql.sh" "$DB" "$W/backup/dumps" > "$W/dump.log" 2>&1
DUMP=$(find "$W/backup/dumps" -name "$DB-*.sql.gz" | sort | tail -1)
check '[ -s "$DUMP" ]' "a dump taken ($(basename "$DUMP"))"
check '[ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 1 AND ok")" = 1 ]' "and recorded, a dump that succeeded"
for _ in 1 2 3; do gem after-the-dump; msql 3307 "FLUSH BINARY LOGS"; done

echo "--- a mistake at a known moment"
gem before-the-mistake
sleep 1.2
MOMENT=$(date -u '+%Y-%m-%d %H:%M:%S')
sleep 1.2
gem the-mistake
echo "  the mistake after $MOMENT UTC"

echo "--- off the site, into $W/offsite"
head -c 32 /dev/urandom | base64 > "$W/offsite.key"
chmod 600 "$W/offsite.key"
WRITING=$(find "$W/backup/binlog" -maxdepth 1 -type f -printf '%f\n' | sort | tail -1)
OFFSITE_TARGET=$W/offsite OFFSITE_KEY=$W/offsite.key MYSQL_CNF=$W/backup.cnf RECORD_DB=$DB \
    "$HERE/backup-offsite.sh" "$W/backup/dumps" "$W/backup/binlog" "$W/mirror" > "$W/offsite.log" 2>&1
check '[ "$(find "$W/offsite/dumps" -name "*.enc" | wc -l)" -ge 1 ] && [ "$(find "$W/offsite/binlog" -name "*.enc" | wc -l)" -ge 3 ]' \
    "the dump and the binlogs copied off the site"
check '! zcat -t "$W/offsite/dumps/$(basename "$DUMP").enc" 2>/dev/null && ! grep -q "CREATE TABLE" "$W/offsite/dumps/$(basename "$DUMP").enc"' \
    "encrypted: the copy is not the dump"
check '[ -e "$W/offsite/binlog/$WRITING.enc" ]' "the binlog being written when it began, closed and copied ($WRITING)"
NOW_WRITING=$(find "$W/backup/binlog" -maxdepth 1 -type f -printf '%f\n' | sort | tail -1)
check '[ ! -e "$W/offsite/binlog/$NOW_WRITING.enc" ]' "the one it opened is not: only closed binlogs leave ($NOW_WRITING)"
check '[ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 3 AND ok")" = 1 ]' "and recorded"

echo "--- the proof, while a writer keeps writing"
( while [ ! -e "$W/stop-writing" ]; do gem the-writer; sleep 0.2; done ) > /dev/null 2>&1 & WRITER=$!
PIDS+=("$WRITER")
sleep 1
SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-proof.sh" "$W/backup/dumps" "$W/backup/binlog" "$DB" backend_proof \
    > "$W/proof.log" 2>&1 && PROOF_OK=1 || PROOF_OK=0
check '[ "$PROOF_OK" = 1 ]' "the proof passed: $(grep -m1 '^summary:' "$W/proof.log" || tail -3 "$W/proof.log")"
check '[ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 2 AND ok AND detail LIKE \"restore %\" AND seconds > 0")" = 1 ]' \
    "and recorded, with its times and the restore's seconds, what the objective watches (D-72)"
check '[ "$(msql 3309 "SELECT COUNT(*) FROM backend_proof.ledger WHERE ref = \"the-writer\"")" -gt 0 ]' \
    "the writer's rows written before the capture are in the copy"

echo "--- to the moment before the mistake"
SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf STOP_AT=$MOMENT "$HERE/restore-drill.sh" "$DUMP" "$DB" backend_moment \
    "$W/backup/binlog" > "$W/moment.log" 2>&1 && MOMENT_OK=1 || MOMENT_OK=0
check '[ "$MOMENT_OK" = 1 ]' "restored to $MOMENT UTC"
check '[ "$(msql 3309 "SELECT COUNT(*) FROM backend_moment.ledger WHERE ref = \"before-the-mistake\"")" = 1 ]' "what came before is there"
check '[ "$(msql 3309 "SELECT COUNT(*) FROM backend_moment.ledger WHERE ref = \"the-mistake\"")" = 0 ]' "the mistake is not"

echo "--- the proof from the copy off the site"
FROM_OFFSITE=1 OFFSITE_TARGET=$W/offsite OFFSITE_KEY=$W/offsite.key SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf \
    "$HERE/restore-proof.sh" /nonexistent /nonexistent "$DB" backend_offsite > "$W/proof-offsite.log" 2>&1 && OFF_OK=1 || OFF_OK=0
check '[ "$OFF_OK" = 1 ]' "restored from off the site, decrypted: $(grep -m1 '^summary:' "$W/proof-offsite.log" || tail -3 "$W/proof-offsite.log")"
check '[ "$(msql 3309 "SELECT COUNT(*) FROM backend_offsite.ledger WHERE ref = \"the-mistake\"")" = 1 ]' \
    "to the end of its closed binlogs, the mistake included"
check '[ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 2 AND ok AND detail LIKE \"from off the site:%\"")" = 1 ]' \
    "and recorded as such"
head -c 32 /dev/urandom | base64 > "$W/wrong.key"
FROM_OFFSITE=1 OFFSITE_TARGET=$W/offsite OFFSITE_KEY=$W/wrong.key SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf \
    "$HERE/restore-proof.sh" /nonexistent /nonexistent "$DB" backend_wrongkey > "$W/proof-wrongkey.log" 2>&1 && WRONG_OK=1 || WRONG_OK=0
check '[ "$WRONG_OK" = 0 ] && [ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 2 AND NOT ok AND detail LIKE \"from off the site: failed%\"")" = 1 ]' \
    "with the wrong key: refused, and recorded as a failure (O-15)"

echo "--- the last resort: no source left, from the copy off the site (O-19)"
OFFSITE_TARGET=$W/offsite OFFSITE_KEY=$W/offsite.key "$HERE/fetch-offsite.sh" "$W/fetched" > "$W/fetched.log" 2>&1
LAST_DUMP=$(find "$W/fetched/dumps" -name "*.sql.gz" | sort | tail -1)
NO_SOURCE=1 SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$LAST_DUMP" "$DB" backend_lastresort \
    "$W/fetched/binlog" > "$W/lastresort.log" 2>&1 && LAST_OK=1 || LAST_OK=0
check '[ "$LAST_OK" = 1 ] && grep -q "reconciliation *0 players" "$W/lastresort.log"' \
    "restored with no source to read, and reconciled: $(grep -m1 '^summary:' "$W/lastresort.log" || tail -3 "$W/lastresort.log")"
check '[ "$(msql 3309 "SELECT COUNT(*) FROM backend_lastresort.ledger WHERE ref = \"the-mistake\"")" = 1 ]' \
    "to the end of the copies, as the off-site proof"
NO_SOURCE=1 SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$LAST_DUMP" "$DB" backend_lastresort \
    "$W/fetched/binlog" > "$W/lastresort-again.log" 2>&1 && AGAIN_OK=1 || AGAIN_OK=0
check '[ "$AGAIN_OK" = 0 ] && grep -q "exists on the target" "$W/lastresort-again.log" && [ "$(msql 3309 "SELECT COUNT(*) FROM backend_lastresort.ledger WHERE ref = \"the-mistake\"")" = 1 ]' \
    "and onto a name that exists: refused, nothing dropped"

echo "--- what must be refused"
mkdir -p "$W/gap" && cp "$W/backup/binlog"/* "$W/gap/"
GAPPED=$(find "$W/gap" -type f -printf '%f\n' | sort | sed -n 2p)
rm -f "$W/gap/$GAPPED"
# A copy at a moment, as one fetched back is: replayed to its own end, so it is the gap that refuses it,
# not a wait for the writer's newest position.
TO_COPIES_END=1 SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$DUMP" "$DB" backend_gap "$W/gap" \
    > "$W/gap.log" 2>&1 && GAP=passed || GAP=refused
check '[ "$GAP" = refused ] && grep -q "the copies have a gap" "$W/gap.log"' "copies with $GAPPED missing: refused, saying so"
# A player nothing writes to after the dump: the replay's row images would put the writer's own back.
SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-proof.sh" "$W/backup/dumps" "$W/gap" "$DB" backend_gap \
    > "$W/proof-gap.log" 2>&1 && PGAP=passed || PGAP=refused
check '[ "$PGAP" = refused ] && [ "$(msql 3307 "SELECT COUNT(*) FROM $DB.backup_run WHERE kind = 2 AND NOT ok AND detail LIKE \"failed:%\"")" = 1 ]' \
    "a proof that fails is recorded as failed, saying why"
{ zcat "$DUMP"; echo "UPDATE player SET gems = gems + 7 WHERE id > $WHO ORDER BY id DESC LIMIT 1;"; } | gzip > "$W/tampered.sql.gz"
PROOF=1 SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$W/tampered.sql.gz" "$DB" backend_tampered \
    "$W/backup/binlog" > "$W/tampered.log" 2>&1 && TAMPERED=passed || TAMPERED=refused
check '[ "$TAMPERED" = refused ] && grep -q "coins or gems differ" "$W/tampered.log"' "a dump whose gems do not reconcile: refused"
PROOF=1 STOP_AT=$MOMENT SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$DUMP" "$DB" backend_short \
    "$W/backup/binlog" > "$W/short.log" 2>&1 && SHORT=passed || SHORT=refused
check '[ "$SHORT" = refused ] && grep -q "MISSING" "$W/short.log"' "a proof whose replay stops short of what was committed: refused"
SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf STOP_AT=$EARLY "$HERE/restore-drill.sh" "$DUMP" "$DB" backend_early \
    "$W/backup/binlog" > "$W/early.log" 2>&1 && EARLY_OK=passed || EARLY_OK=refused
check '[ "$EARLY_OK" = refused ] && grep -qE "stopped +[1-9]" "$W/early.log"' \
    "a moment before the dump was taken: refused, the dump holding what came after it"
check '[ "$(msql 3307 "SELECT COUNT(*) FROM $DB.player p LEFT JOIN (SELECT player_id, SUM(IF(currency = 0, delta, 0)) c, SUM(IF(currency = 1, delta, 0)) g FROM $DB.ledger GROUP BY player_id) l ON l.player_id = p.id WHERE p.coins <> COALESCE(l.c, 0) OR p.gems <> COALESCE(l.g, 0)")" = 0 ]' \
    "the primary still reconciles: the drill's own writes were consistent"

echo "--- the monthly drill's exact way, once nothing writes"
# Asked to stop, not killed: a kill ends the loop but not the write it is in, which then lands
# after the copy's end and is counted in the source alone (T-47).
touch "$W/stop-writing"
wait "$WRITER" 2>/dev/null || true
SOURCE_CNF=$W/backup.cnf SCRATCH_CNF=$W/scratch.cnf "$HERE/restore-drill.sh" "$DUMP" "$DB" backend_exact "$W/backup/binlog" \
    > "$W/exact.log" 2>&1 && EXACT_OK=1 || EXACT_OK=0
check '[ "$EXACT_OK" = 1 ] && ! grep -q DIFFERS "$W/exact.log"' "every table's count the source's, row for row"
echo
[ "$FAILS" = 0 ] && { echo "BACKUP DRILL PASSED ($W)"; exit 0; } || { echo "BACKUP DRILL FAILED: $FAILS ($W)"; exit 1; }
