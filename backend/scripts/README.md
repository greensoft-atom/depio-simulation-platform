# scripts

The backend's operational scripts: the release build and its check on RHEL 9, the backups and
their proofs, the promotions, the certificate, and a drill of the backups. Each script's header is
its own reference; this page lists them together, with what each needs and what runs it.

The release ships twelve of them in `scripts/`, installed as `/opt/backend/scripts/`
([make-release.sh](#make-releasesh)); the backup units and certbot's deploy hook run them from
there. `make-release.sh`, `check-release-el9.sh` and `backup-drill.sh` stay in the source tree.
Every setting a script or unit reads is also in the
[deploy README's catalogue](../deploy/README.md#environment-variables). How they are used:
[operations/01-deploy](../../docs/operations/01-deploy.md) and the
[runbook](../../docs/operations/02-runbook.md); drawn in
[diagrams/07](../../docs/diagrams/07-deploy-and-operations.md).

| Script | Group | In the release | Run by |
|---|---|---|---|
| [`make-release.sh`](#make-releasesh) | Release | no | hand, on the build machine; `build-offline.sh` |
| [`check-release-el9.sh`](#check-release-el9sh) | Release | no | hand, on a machine with Docker |
| [`binlog-stream.sh`](#binlog-streamsh) | Backups | yes | `backend-binlog-stream.service`, always on |
| [`backup-mysql.sh`](#backup-mysqlsh) | Backups | yes | `backend-mysql-backup.timer`, nightly |
| [`record-backup.sh`](#record-backupsh) | Backups | yes | the three scripts that record |
| [`backup-offsite.sh`](#backup-offsitesh) | Backups | yes | `backend-backup-offsite.timer`, hourly, once a host is named |
| [`fetch-offsite.sh`](#fetch-offsitesh) | Backups | yes | `restore-proof.sh` from off the site; hand |
| [`restore-proof.sh`](#restore-proofsh) | Backups | yes | `backend-restore-proof.timer`, weekly; `backend-restore-proof-offsite.timer`, monthly |
| [`restore-drill.sh`](#restore-drillsh) | Recovery | yes | `restore-proof.sh`; hand |
| [`backup-store.sh`](#backup-storesh) | Backups | yes | hand, before an upgrade |
| [`store-restore-drill.sh`](#store-restore-drillsh) | Recovery | yes | hand, monthly |
| [`promote-store.sh`](#promote-storesh) | Failover | yes | hand |
| [`promote-mysql.sh`](#promote-mysqlsh) | Failover | yes | hand |
| [`install-certificate.sh`](#install-certificatesh) | Certificates | yes | certbot's deploy hook; hand |
| [`backup-drill.sh`](#backup-drillsh) | Drills | no | hand, on a development machine |

The machines are A, B and C ([01 §1](../../docs/operations/01-deploy.md#1-what-runs-where));
the backup machine is C, the MySQL replica's, until a failover moves the backups
([runbook §2](../../docs/operations/02-runbook.md#mysql-built-2026-09-29-d-35)).

**The tools are the release's.** The seven scripts that call `mysql`, `mysqldump` or `mysqlbinlog`
(`binlog-stream.sh`, `backup-mysql.sh`, `record-backup.sh`, `backup-offsite.sh`, `restore-proof.sh`,
`restore-drill.sh`, `promote-mysql.sh`) put `../mysql/bin` first on `PATH`: in
`/opt/backend/scripts/` that is the release's MySQL 8.4
([09 §6](../../docs/detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)); run from
the source tree, which has none, they use the machine's. The store's three (`backup-store.sh`,
`store-restore-drill.sh`, `promote-store.sh`) take j-redis's client and server from the release's
`jredis/bin/` and set `JAVA_HOME` to its `runtime/` unless it is set; without a `jredis/` beside
`scripts/`, from `/opt/j-redis/bin/`.

## Release

### make-release.sh

Builds the release. First `j-redis-service`, `mvn -o -DskipTests install` there: the backend
builds against its client, and the release carries its server. Then `mvn clean install -DskipTests`
offline, so that a file deleted from the sources (a migration replaced by the baseline, D-75) cannot
linger in `target/` and ship; the clean empties `backend/target/`, earlier releases included. Then,
for each of `arena`, `platform`, `worker` and `gateway`, it copies the runtime dependencies and the
module's own jar into `lib/<process>/`. One directory per process, not one shared: `handoff`'s
module graph keeps the MySQL driver off the arena's classpath, and a shared directory would put it
back.

Then what the processes run on
([09 §4](../../docs/detailed-design/09-release-and-packaging.md#4-the-release)): j-redis's server,
client and tools, jars and launchers, into `jredis/`, with its configuration's template; the Java
runtime, linked by `jlink` from the JDK's `jmods/` into `runtime/`; and `vendor/mysql-8.4` and
`vendor/nginx-1.30`, as committed, into `mysql/` and `nginx/`. The runtime's modules are a named
list: `java.se`, `jdk.httpserver`, `jdk.unsupported`, `jdk.jfr`, `jdk.net`, `jdk.crypto.ec`,
`jdk.jcmd`, `jdk.management` and `jdk.management.agent`. `jdeps` cannot compute them, since the
libraries are modules whose optional requirements are absent; so it reads our own jars and
j-redis's, and **a module they use outside the list stops the release** ("the runtime lacks …: add
it to modules"; [09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)).

Last it copies `deploy/systemd` and `deploy/env`, `deploy/mysql` as `mysql-conf/` and
`deploy/nginx` as `nginx-conf/`, the twelve operational scripts, `COMMIT` (the git commit) and
`VERSIONS.md` (`vendor/VERSIONS.md`, then the backend's, j-redis's and the runtime's versions),
packs the directory, and prints the jars in each `lib/` and the sizes of `runtime/`, `mysql/`,
`nginx/` and `jredis/`. The layout is in the [deploy README](../deploy/README.md#the-release).

```
scripts/make-release.sh    ->  target/release/backend-<version>/  and  target/release/backend-<version>.tar.gz
```

| | |
|---|---|
| Arguments | none. The version is the first `<version>` in `backend/pom.xml` (`0.1.0-SNAPSHOT`) |
| Environment | `MVN`, the Maven command (default `mvn`). A local repository filled from more than one source needs `MVN="mvn -Daether.enhancedLocalRepository.trackingFilename=_none"`. `JDK`, the JDK 21 whose `jdeps` checks the jars and whose `jmods/` the runtime is linked from (default `vendor/jdk-21`) |
| Needs | JDK 21 and Maven to run the build; a local repository holding every dependency of the backend and of j-redis (the `java21-offline` bundle); `../j-redis-service` and `../vendor` beside `backend/`; tar; git for `COMMIT` (optional). `build-offline.sh`, at `background/`, runs it on the committed JDK and Maven with no network ([09 §3](../../docs/detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| Runs | on the build machine, from anywhere: it changes to `backend/` itself |

### check-release-el9.sh

Checks a release on RHEL 9's own userspace with nothing installed
([09 §7](../../docs/detailed-design/09-release-and-packaging.md#7-how-it-is-verified)): it runs itself
in Red Hat's UBI 9 minimal image, the release mounted read-only, and starts what the release
carries. Each check prints `ok` or `FAIL`: every binary's libraries found (`runtime/bin/java`,
`mysqld` and the MySQL tools, `nginx`); MySQL 8.4 initialised and started; j-redis; the platform
migrating the database and serving a registration and a login; the gateway, a worker and an arena
running, and whether the arena is on Netty's native epoll; nginx proxying to the platform over HTTP
and HTTPS; and `nginx -t` on the shipped `nginx-conf/`, installed as on a machine. Then it lists the
tools the scripts take from the system that the image lacks, which a server installation has
([09 §4.2](../../docs/detailed-design/09-release-and-packaging.md#42-what-the-host-still-provides)).
It ends `--- <n> failed` and exits non-zero on any failure. Mutation-checked: a release without
`libaio` fails it.

```
scripts/check-release-el9.sh [release-dir]       default: target/release/backend-*/
```

| | |
|---|---|
| Environment | `IMAGE`, the image (default `registry.access.redhat.com/ubi9/ubi-minimal:9.5`; another RHEL 9.x tag to check that one) |
| Needs | Docker, and the image, pulled the first time |
| Runs | on the build machine, from anywhere |

## Backups and recovery

### binlog-stream.sh

Copies the primary's binlogs to this machine as they are written, with `mysqlbinlog
--read-from-remote-server --raw --stop-never`, so that a point-in-time restore survives losing
the database's disk. It resumes from the newest copy it has (`--raw` rewrites that file from its
start, so a half-written one is completed); with none, from the first binlog the server lists. If
the server has purged the file it needs, it fails and says so: the copies then have a gap, and a
new dump is the only way to a restorable point again. Files are written private (`umask 077`).

```
binlog-stream.sh <out-dir>
```

| | |
|---|---|
| Environment | `BINLOG_CNF` (required): a MySQL option file, `[client]` with `host`, `user`, `password`, `ssl-mode=VERIFY_IDENTITY`, `ssl-ca`. `BINLOG_SERVER_ID`, the connection's server id (default 900): two connections with one id make the server drop the older |
| Needs | `mysql`, `mysqlbinlog`. The account `binlog`: `REPLICATION SLAVE` and `REPLICATION CLIENT` ([01 §9](../../docs/operations/01-deploy.md#9-mysql)) |
| Runs | on the backup machine, never the database's |
| Run by | `backend-binlog-stream.service`, as `backend-backup`, into `/var/lib/backend/binlog`, `Restart=always` every 10 s (a database shutting down ends the stream with status 0) |

### backup-mysql.sh

A consistent full dump of one database (`mysqldump --single-transaction --source-data=2 --routines
--triggers --events --set-gtid-purged=OFF --no-tablespaces`, gzipped), with the binlog position it
was taken at and no `USE` statement, so it restores under any name. It is written as `.partial` and
renamed when whole; a dump without a binlog position is refused. After a dump has succeeded it
deletes dumps older than `KEEP_DAYS` and, with `BINLOG_DIR`, every binlog copy before the file the
oldest remaining dump starts from. Its outcome, success or failure, is recorded through
`record-backup.sh` as kind `dump`. Files are written private (`umask 077`).

```
backup-mysql.sh [database] [out-dir]        defaults: backend, /var/backups/backend
```

| | |
|---|---|
| Environment | `MYSQL_CNF`, the backup account's option file (unset: the client's defaults). `KEEP_DAYS` (8). `BINLOG_DIR`, `binlog-stream.sh`'s copies (unset: no binlog copy is pruned). `RECORD` (1; 0 records nothing) |
| Needs | `mysqldump`, `mysql`, `gzip`. The account `backup`: `SELECT`, `SHOW VIEW`, `TRIGGER`, `EVENT`, `RELOAD`, `REPLICATION CLIENT` on `*.*`, and `INSERT` on `backend.backup_run` |
| Runs | on the backup machine: a dump on the disk it protects is lost with it |
| Run by | `backend-mysql-backup.service` (as `backend-backup`; `backend /var/lib/backend/mysql-dumps`, `BINLOG_DIR=/var/lib/backend/binlog`, `KEEP_DAYS=8`), started by `backend-mysql-backup.timer` at 03:30 UTC, within 15 minutes after |

### record-backup.sh

Records one backup step's outcome in `backup_run`, which every worker reports as metrics
(`backend_backup_succeeded_timestamp_seconds{kind}`, `backend_backup_failed{kind}`,
`backend_backup_restore_seconds`). The detail is made one line of at most 255 characters and quoted
for SQL.

```
record-backup.sh <dump|proof|offsite> <started, seconds since the epoch> <ok: 1|0> <detail> [seconds]
```

`kind` is stored as 1 (`dump`), 2 (`proof`) or 3 (`offsite`); `seconds` is a proof's restore and
replay.

| | |
|---|---|
| Environment | `MYSQL_CNF`, the backup account's option file. `RECORD_DB`, the database (default `backend`) |
| Needs | `mysql`; `INSERT` on `backup_run` and nothing more there |
| Run by | `backup-mysql.sh`, `backup-offsite.sh`, `restore-proof.sh` |

### backup-offsite.sh

Copies the backups off the site, encrypted. It first runs `FLUSH BINARY LOGS` on the primary and
waits up to 30 s for the stream to open the next file, so the one it was writing is closed and
copied whole: only closed binlogs leave. Each dump and closed binlog copy that is new or changed is
encrypted into the mirror (`openssl enc -aes-256-cbc -pbkdf2 -iter 200000`); a mirror file whose
source was pruned is removed; then `rsync -a --delete` pushes the mirror to the target, which so
holds what the backup machine holds. Recorded as kind `offsite`. With `OFFSITE_TARGET` unset it
copies and records nothing (Q-53: no host named yet).

```
backup-offsite.sh <dump-dir> <binlog-dir> <mirror-dir>
```

| | |
|---|---|
| Environment | `OFFSITE_TARGET`, `user@host:dir` by SSH or a directory. `OFFSITE_KEY` (required once a target is set), the key file, kept off the three machines too. `OFFSITE_SSH`, the ssh command rsync uses (default `ssh -o BatchMode=yes`). `MYSQL_CNF`, the backup account (it has `RELOAD`, which the flush needs). `RECORD` (1) |
| Needs | `mysql`, `openssl`, `rsync`, `ssh` |
| Runs | on the backup machine |
| Run by | `backend-backup-offsite.service` (as `backend-backup`; mirror `/var/lib/backend/offsite-mirror`; `OFFSITE_TARGET` from `/etc/backend/offsite.env`), started hourly by `backend-backup-offsite.timer` once the owner names the host |

### fetch-offsite.sh

Brings the copy off the site back and decrypts it: the dumps into `<out-dir>/dumps`, the binlogs
into `<out-dir>/binlog`, the layout `restore-drill.sh` reads. It fetches everything the host holds.
Files are written private (`umask 077`).

```
fetch-offsite.sh <out-dir>
```

| | |
|---|---|
| Environment | `OFFSITE_TARGET` and `OFFSITE_KEY` (both required), `OFFSITE_SSH`, as `backup-offsite.sh`'s |
| Needs | `rsync`, `ssh`, `openssl`; room for the whole copy twice, encrypted and decrypted |
| Run by | `restore-proof.sh` with `FROM_OFFSITE=1`; by hand for a restore from off the site ([runbook](../../docs/operations/02-runbook.md#a-last-resort-restore-with-no-source-left)) |

### restore-proof.sh

The proof that the backups restore: the newest dump of the source and the binlog copies, restored
onto the scratch server by `restore-drill.sh` with `PROOF=1` while the primary keeps writing, and
the outcome recorded as kind `proof` with the restore's and the replay's seconds. Every outcome is
recorded: a failure's detail quotes the lines of its log that say why (else its last three lines).
With `FROM_OFFSITE=1` it first fetches and decrypts the copy off the site into a temporary
directory, with the fetch's errors in its log, and replays the copies to their end; the record then
starts `from off the site:`.

```
restore-proof.sh <dump-dir> <binlog-dir> [source-db] [scratch-db]   defaults: backend, backend_proof
```

| | |
|---|---|
| Environment | `SOURCE_CNF` (required), the backup account's option file: the primary, read, and the outcome recorded there. `SCRATCH_CNF` (required), the scratch server's. `FROM_OFFSITE` (0), with `fetch-offsite.sh`'s settings |
| Needs | as `restore-drill.sh`, and `fetch-offsite.sh`'s from off the site |
| Runs | on the backup machine, against its scratch server (`127.0.0.1:3309`) |
| Run by | `backend-restore-proof.service` (`… backend backend_proof`), started by `backend-restore-proof.timer` on Sundays at 04:00 UTC; `backend-restore-proof-offsite.service` (`FROM_OFFSITE=1`, `… backend backend_proof_offsite`), started by `backend-restore-proof-offsite.timer` on the first Sunday of the month at 05:00 UTC. The two never run at once |

### restore-drill.sh

Restores a dump into a scratch database (every session with `sql_log_bin = 0`), carries it forward
through the binlog with `mysqlbinlog --skip-gtids --rewrite-db`, and checks it. It never writes to
the source. Before loading anything it refuses copies that do not start at the dump's file or that
have a gap, and a scratch database that is the source (the same name on the same server). The
scratch database is dropped first if it exists. The checks: every table's row count against the
source's (exact only when nothing writes meanwhile), and the ledger reconciled with every balance,
coins and gems. The source's position is read with `SHOW MASTER STATUS`, or `SHOW BINARY LOG
STATUS` on a server without it (8.4). It ends with a `summary:` line (the restore's and the
replay's seconds, and how far behind the copies were) and `drill passed` or `DRILL FAILED`.

```
restore-drill.sh <dump.sql.gz> <source-db> <scratch-db> [binlog-dir]
```

| Mode | What changes |
|---|---|
| default | the source and the scratch database on this machine's server, replayed from the source's own binlog; or, with `SOURCE_CNF`, `SCRATCH_CNF` and a `binlog-dir`, read from the primary, restored onto another server, replayed from `binlog-stream.sh`'s copies after waiting up to 30 s for them to reach the primary's position |
| `STOP_AT='YYYY-MM-DD HH:MM:SS'` | the replay stops at the last transaction before that UTC moment; row counts are not compared, and no ledger row at or after the moment may be in the copy ([runbook](../../docs/operations/02-runbook.md#recovering-to-a-moment)) |
| `PROOF=1` | the weekly proof under live writes: every table present, the newest migration the source's, nothing committed before the capture missing, the ledger reconciled |
| `TO_COPIES_END=1` | with a `binlog-dir`: replay to the end of the newest copy, not to the source's position now (copies fetched from off the site) |
| `NO_SOURCE=1` | no source server is left: nothing is read from one, and only the checks that need none are made ([runbook](../../docs/operations/02-runbook.md#a-last-resort-restore-with-no-source-left)) |

| | |
|---|---|
| Environment | `SOURCE_CNF`, `SCRATCH_CNF` (unset: the local server), `STOP_AT`, `PROOF` (0), `TO_COPIES_END` (0), `NO_SOURCE` (0) |
| Needs | `mysql`, `mysqlbinlog`, `zcat`, `awk`; on the scratch server, the right to drop and create the scratch database; on the source, the backup account's grants |
| Runs | on the backup machine; a dump and copies are `backend-backup`'s, so as that user or root |
| Run by | `restore-proof.sh`; by hand for the monthly drill and a restore to a moment |

### backup-store.sh

A consistent copy of a running j-redis's data directory, by j-redis's own procedure
(j-redis-service `docs/08` §12): automatic AOF rewrites stopped, `BGREWRITEAOF` and a wait for it,
the manifest copied first and then every file it lists, the setting put back even when the copy
fails. The copy holds every live session token, so it is written private (`umask 077`), and a
half-made copy is removed. There is no timer: no copy of the store leaves its machine on one
(D-31); each store has its replica instead.

```
backup-store.sh <data-dir> <out-dir> [port]       default port 6379; the copy is <out-dir>/store-<UTC time>
```

| | |
|---|---|
| Environment | `JREDIS_CLI`, the client (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`). `BACKEND_STORE_PASSWORD_FILE`, the store's password, sent on the client's standard input |
| Needs | the j-redis client; read access to the store's data directory and the password file, so root |
| Runs | on the store's own machine: it reaches the store on `127.0.0.1` ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) |
| Run by | hand, before an upgrade ([runbook §6](../../docs/operations/02-runbook.md#6-routine)) |

### store-restore-drill.sh

Starts a second j-redis on a copy of `backup-store.sh`'s and compares what the instance holds with
the running store: on `session`, the key count, the all-time board member by member with scores,
and the names; on `events`, the key count, the result stream and its group's pending entries, the
inbox, the dead list and the deferred list. A backup is one instance's, so the fourth argument says
which; with none, every family is compared, as on a single store such as a development one (O-20).
The scratch server listens on `127.0.0.1` only, with a random password in a
0600 configuration file, since it holds live session tokens; it is stopped and its directory
removed however the drill ends.

```
store-restore-drill.sh <backup-dir> <source-port> <scratch-port> [session|events]
```

| | |
|---|---|
| Environment | `JREDIS_SERVER` (default the release's `jredis/bin/j-redis-server`, else `/opt/j-redis/bin/j-redis-server`), `JREDIS_CLI` (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`), `BACKEND_STORE_PASSWORD_FILE` (the source's password) |
| Needs | the j-redis server and client, `sha256sum`, a free port for the scratch server |
| Runs | on the store's own machine, on each store's primary; the source is reached on `127.0.0.1` |
| Run by | hand, monthly ([runbook](../../docs/operations/02-runbook.md#the-restore-drill)) |

## Failover

### promote-store.sh

Promotes a j-redis replica to primary on the replica's machine. It will not promote while the old
primary may still take writes: with `--old-is-down` it refuses if the old primary still answers as
one; with `--demote-old` it first makes the old primary a replica of this one. Then `REPLICAOF NO
ONE` here, which raises the epoch and records it; `replicaof` is taken out of the configuration
file (the previous file kept as `<file>.before-promotion-<UTC time>`), since the role at start-up is
the file's; and the new role and epoch are printed. Run on a primary already, it says so and changes
nothing. The clients, given both addresses, follow by themselves.

```
promote-store.sh <replica config file> <old primary host:port> --old-is-down
promote-store.sh <replica config file> <old primary host:port> --demote-old <this replica host:port>
```

| | |
|---|---|
| Environment | `JREDIS_CLI` (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`) |
| Needs | root: it reads `port` and `requirepass` from the configuration file (`/etc/backend/store-<name>.conf`, `root:jredis`, 0640) and rewrites it, keeping a copy beside it in `/etc/backend/`. The j-redis client, `timeout` |
| Runs | on the store replica's machine (B for `session`, C for `events`); it reaches this machine's server on `127.0.0.1` |
| Run by | hand ([runbook §2](../../docs/operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)) |

### promote-mysql.sh

Promotes MySQL's replica to primary, on the replica's machine. First it prints the primary's last
heartbeat that this replica applied and its age: older than `PROMOTE_STALE_SECONDS`, or not
readable, it refuses before touching anything unless given `--stale-ok`. It will not promote while
the old primary may still serve: `--old-is-down` refuses if the old primary answers at all;
`--demote-old` fences it first (`SET PERSIST super_read_only = ON`, `SET GLOBAL offline_mode =
ON`). Then it waits up to 60 s for the replica to apply everything it received, stops replicating
and forgets its source (`STOP REPLICA`, `RESET REPLICA ALL`), becomes writable with `SET PERSIST`,
raises the failover epoch in `ha_epoch`, which the processes follow, and prints the epoch and the
executed GTID set. Run on a primary already, it prints the epoch and changes nothing.

```
promote-mysql.sh <old primary host:port> --old-is-down [--stale-ok]
promote-mysql.sh <old primary host:port> --demote-old [--stale-ok]
```

| | |
|---|---|
| Environment | `MYSQL_FAILOVER_CNF`, the `failover` account's option file (default `/etc/backend/credentials/mysql-failover.cnf`: `[client]` with the user and password, `[client_remote]` with the TLS settings, read only for the old primary). `BACKEND_DB_NAME` (`backend`). `MYSQL`, the client (`mysql`). `PROMOTE_STALE_SECONDS` (300) |
| Needs | root, to read the option file; `mysql`, `timeout`. The account `failover` with the grants of [01 §9](../../docs/operations/01-deploy.md#9-mysql) |
| Runs | on the MySQL replica's machine (C); this machine's server by its socket, the old primary over TLS |
| Run by | hand ([runbook §2](../../docs/operations/02-runbook.md#mysql-built-2026-09-29-d-35)) |

## Certificates

### install-certificate.sh

```
install-certificate.sh <fullchain.pem> <privkey.pem> | --check | --placeholder   (or as certbot's --deploy-hook)
```

The certificate is deferred by the owner; the script's header and
[01 §11](../../docs/operations/01-deploy.md#11-the-certificate) describe it.

| | |
|---|---|
| Environment | for the release's nginx: `NGINX_CONF` (default `/etc/backend/nginx/backend.conf`), `NGINX_TEST` (default `/opt/backend/nginx/sbin/nginx -t -q`, after making the directories its unit would), `NGINX_RELOAD` (default `systemctl reload backend-nginx`). The rest are in its header |

## Drills

### backup-drill.sh

A live drill of the backups on MySQL servers of its own: a primary on 3307 (binary log, GTIDs),
seeded with a copy of `SEED_DB` from the development server on 3306, and a scratch server on 3309.
It runs each backup script as production does and checks what each must do and what each must
refuse: the stream copying; the dump, recorded; a mistake at a known moment; the copy off the site,
encrypted, into a directory standing in for the host; the proof while a writer keeps writing; a
restore to the moment before the mistake; the proof from off the site, and with the wrong key,
refused and recorded as a failure (O-15); a gap in the copies, a dump whose gems do not reconcile,
a proof whose replay stops short, and a moment before the dump, each refused; and the monthly
drill's exact counts once nothing writes. It runs the scripts, not the units. It ends `BACKUP DRILL
PASSED` or `BACKUP DRILL FAILED: <n>`, with the working directory, which holds every step's log.

```
TMPDIR=<scratch directory> backend/scripts/backup-drill.sh
RELEASE=backend/target/release/backend-<version> TMPDIR=<scratch directory> backend/scripts/backup-drill.sh   # on MySQL 8.4
```

| | |
|---|---|
| Environment | `TMPDIR` (default `/tmp`), where its working directory goes. `SEED_DB` (default `backend_dev`) and `SEED_PASSWORD` (default `backend-dev-password`), the development database and the `backend` user's password on 3306. `RELEASE`, a release directory: its `mysql/`, MySQL 8.4, runs the drill's own servers and is first on `PATH` for the scripts; unset, or a release without one, the machine's |
| Needs | `mysqld`, `mysql`, `mysqldump`, `mysqlbinlog` (the release's with `RELEASE`, else `/usr/sbin/mysqld` and the machine's tools), `openssl`, `rsync`; ports 3307 and 3309 free (`MYSQL_FAILOVER` in `client/headless-drill.sh` uses 3307 too: not both at once); the seed database at the current schema, which has `backup_run` |
| Runs | on a development machine only |

The rest of the stack is drilled by [`client/headless-drill.sh`](../../client/headless-drill.sh),
against a release, on the release's runtime, j-redis and MySQL when it carries them; its ports are
in the [deploy README](../deploy/README.md#ports).
