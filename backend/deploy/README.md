# deploy

What the release installs besides the jars and the binaries it carries: the systemd units and
drop-ins, the nginx configuration, the example settings and the MySQL servers' configuration.
`scripts/make-release.sh` copies each folder here into the release as it is, `mysql/` and `nginx/`
under the names `mysql-conf/` and `nginx-conf/` ([the release](#the-release)). How a machine is
installed is [operations/01-deploy](../../docs/operations/01-deploy.md); what to do when something
breaks, the [runbook](../../docs/operations/02-runbook.md); the scripts the units run,
[scripts/README](../scripts/README.md); the pictures,
[diagrams/07](../../docs/diagrams/07-deploy-and-operations.md).

This page also holds the catalogues that cut across the files: every
[environment variable](#environment-variables), every [port](#ports) and every
[timer](#timers).

The addresses in the examples are the three machines' private ones: A `10.0.0.1`, B `10.0.0.2`,
C `10.0.0.3` ([01 §1](../../docs/operations/01-deploy.md#1-what-runs-where)). `a.example.com`
stands for a machine's public name, which is not chosen yet.

## Files

### env/

Copied to `/etc/backend/` without `.example` and filled in; read by systemd's `EnvironmentFile=`.
No secrets go in them: the passwords arrive through `LoadCredential=`
([01 §2](../../docs/operations/01-deploy.md#secrets)).

| File | Installed as | Read by | Notable values |
|---|---|---|---|
| `arena.env.example` | `/etc/backend/arena-<name>.env`, one per arena instance | `backend-arena@<name>` | `ARENA_PORT=9001` (each instance its own), `MAP_SIZE=5700`, `MAX_PLAYERS=150`, `SHAPES=1500`, `MAX_ROOMS=4`, `MATCH_SECONDS=0` (continuous), `ARENA_BIND=0.0.0.0` (clients dial the arena directly, D-5), `ADVERTISE_HOST=a.example.com`, `STORE_HOST=10.0.0.1`, `STORE_PORT=6379`, `BACKEND_METRICS_ADDR=127.0.0.1:9110` (each instance its own). Commented: `BACKEND_STORE_ADDRESSES=10.0.0.1:6379,10.0.0.2:6379`, `BACKEND_EVENTS_STORE=10.0.0.2:6380,10.0.0.3:6380` |
| `gateway.env.example` | `/etc/backend/gateway.env` | `backend-gateway` | `GATEWAY_BIND=127.0.0.1`, `GATEWAY_PORT=8090` (nginx's upstream), `PLATFORM_URL=http://127.0.0.1:8080`, store `10.0.0.1:6379`, `BACKEND_METRICS_ADDR=127.0.0.1:9103`, `BACKEND_EDGE_CERTIFICATE=/etc/backend/tls/fullchain.pem` |
| `platform.env.example` | `/etc/backend/platform.env` | `backend-platform` | `PLATFORM_BIND=127.0.0.1`, `PLATFORM_PORT=8080`, store `10.0.0.1:6379`, `BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12`, `BACKEND_DB_USER=backend`, `BACKEND_METRICS_ADDR=127.0.0.1:9101`. Commented: `BACKEND_DB_POOL_SIZE=16`, `BACKEND_ADMIN_ADDR=127.0.0.1:9120`, `BACKEND_PAYMENT_PROVIDER=simulated` |
| `worker.env.example` | `/etc/backend/worker.env` | every `backend-worker@` on the machine, and `backend-leaderboard-rebuild` | store, `BACKEND_DB_URL` and `BACKEND_DB_USER` as the platform's. Commented: `BACKEND_STORE_ADDRESSES`, `BACKEND_EVENTS_STORE`, `BACKEND_DB_POOL_SIZE=8` |
| `worker-instance.env.example` | `/etc/backend/worker-<id>.env`, one per worker instance | `backend-worker@<id>`, after `worker.env`, so it wins | `BACKEND_METRICS_ADDR=127.0.0.1:9102` (`c1`; `c2` takes `9112`). Two instances given one port: the second exits 2 |
| `store.env.example` | `/etc/backend/store-<name>.env`, one per store instance (`session`, `events`) | `backend-store@<name>` | `STORE_HEAP=8g`, both `-Xms` and `-Xmx`; the store's `maxmemory` about 40 % of it, since ZGC makes the same data estimate 25–50 % larger ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) |

With `BACKEND_STORE_ADDRESSES` set, `STORE_HOST` and `STORE_PORT` stay set all the same: the units
still pass them, and an empty port refuses the configuration (exit 2).

### systemd/

Copied to `/etc/systemd/system/`, then `systemctl daemon-reload`. Every process unit runs as
`backend`, from `/opt/backend/lib/<process>/*` with `/opt/backend/runtime/bin/java`, the release's
own Java 21 runtime ([09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)), and
`--add-opens java.base/java.nio=ALL-UNNAMED`. The four long-running ones also use ZGC, write heap
dumps and a GC log in `/var/log/backend/` (`LogsDirectory=backend`), restart on failure but not on
exit 2 (a refused configuration) or 243 (a credential file missing), and count 143 (SIGTERM) as
clean. All of them run sandboxed (`ProtectSystem=strict`,
`PrivateTmp`, `NoNewPrivileges`, an empty capability set and the rest; not
`MemoryDenyWriteExecute`, which the JIT cannot run under).

**The processes**

| Unit | Runs | Settings and credentials | Notable values |
|---|---|---|---|
| `backend-arena@.service` | `com.backend.arena.ArenaMain`, instance `%i` = the arena's name, unique across the fleet ([01 §2](../../docs/operations/01-deploy.md#2-conventions)) | `arena-%i.env`; credential `store-password` | `-Xms6g -Xmx6g`; spool in `/var/lib/backend/arena-%i/spool` (`StateDirectory`); `LimitNOFILE=65536`; `TimeoutStopSec=12min`, for an eleven-minute drain; a `CPUAffinity=` drop-in per instance |
| `backend-gateway.service` | `com.backend.gateway.GatewayMain`; its id is the host name (`%H`) | `gateway.env`; `store-password` | `-Xms3g -Xmx3g`; `LimitNOFILE=65536`; `TimeoutStopSec=30` |
| `backend-platform.service` | `com.backend.platform.PlatformMain` | `platform.env`; `db-password`, `store-password` | `-Xms3g -Xmx3g`; `-Dsun.net.httpserver.maxIdleConnections=1000` (above nginx's keepalive pool); `After=backend-mysql.service`; `TimeoutStopSec=30` |
| `backend-worker@.service` | `com.backend.worker.WorkerMain`, consumer `worker-%i`, unique across the fleet | `worker.env` then `-worker-%i.env`; `db-password`, `store-password` | `-Xms1536m -Xmx1536m` (two share 4 cores and 4 GB on C); `After=backend-mysql.service`; `TimeoutStopSec=30` |
| `backend-leaderboard-rebuild.service` | `com.backend.worker.LeaderboardRebuild`, once (`Type=oneshot`); not enabled | `worker.env`; `db-password`, `store-password` | `-Xmx512m`; started by hand after a store loses its disk ([runbook §3](../../docs/operations/02-runbook.md#3-per-component-procedures)) |

**The servers the release carries**, in place of the distribution's packages (D-77,
[09](../../docs/detailed-design/09-release-and-packaging.md)). Each runs as a system user of its own,
made at install ([01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine)).

| Unit | Runs | Settings | Notable values |
|---|---|---|---|
| `backend-store@.service` | `/opt/backend/jredis/lib/j-redis-server.jar` on `/opt/backend/runtime`, as `jredis`; instance `%i` = the store's name, `session` or `events` ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) | `/etc/backend/store-%i.conf`, from the release's `jredis/conf/j-redis.conf`; the heap from `store-%i.env` | `-Xms` and `-Xmx` `${STORE_HEAP}`, ZGC; data in `/var/lib/backend-store/%i` and logs in `/var/log/backend-store/%i`, both 0700, `UMask=0077` (the data holds every live session token); restarts on failure but not on exit 1, 2 or 3 (its configuration, its data directory locked, its data not loadable); `LimitNOFILE=65536`; a `CPUAffinity=` drop-in, as the arenas' |
| `backend-mysql.service` | `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf`, as `mysql`; on A, and on C for the replica | `backend.cnf` ([mysql/](#mysql)) | `Type=notify`: started means ready for connections; data in `/var/lib/backend-mysql` (`StateDirectory`, 0750), socket and pid file in `/run/backend-mysql/`, error log in `/var/log/backend-mysql/`; `TimeoutStartSec=infinity` (crash recovery takes as long as it takes), `TimeoutStopSec=15min`; restarts on failure but not on exit 1; `LimitNOFILE=10000` |
| `backend-nginx.service` | `/opt/backend/nginx/sbin/nginx`: the master as root, which binds 80 and 443, the workers as `nginx` | `/etc/backend/nginx/nginx.conf` ([nginx/](#nginx)) | `Type=forking`; `nginx -t` before a start, and a reload is `nginx -t` then `HUP`, so a broken file is refused and no connection dropped; stopped with `SIGQUIT`, the workers finishing what they serve; logs in `/var/log/backend-nginx/`; `LimitNOFILE=65536` |

`backend-mysql` and `backend-nginx` run with `ProtectSystem=full` (not `strict`), `PrivateTmp`,
`PrivateDevices` and `NoNewPrivileges`; `backend-store@` sets no sandboxing beyond its private
directories.

**Drop-ins**, examples installed under a unit's `.d/` directory

| File | Installed as | Configures |
|---|---|---|
| `arena-tls.conf.example` | `/etc/systemd/system/backend-arena@.service.d/tls.conf` | TLS for match traffic: credentials `arena-tls.p12` and `arena-tls-password` from `/etc/backend/credentials/`, `BACKEND_ARENA_TLS_KEYSTORE` and `BACKEND_ARENA_TLS_PASSWORD_FILE`. Installed once both files exist (the certificate is deferred) |
| `platform-admin.conf.example` | `/etc/systemd/system/backend-platform.service.d/admin.conf` | The admin API's secret: credential `admin-token`, `BACKEND_ADMIN_TOKEN_FILE`. With `BACKEND_ADMIN_ADDR` in `platform.env` ([runbook §3a](../../docs/operations/02-runbook.md#3a-the-admin-api)) |

**The backup machine**, C ([01 §10](../../docs/operations/01-deploy.md#10-copies-off-the-databases-machine)).
The scripts run as `backend-backup`, a user of its own, from `/opt/backend/scripts/`, sandboxed as
the processes are and with `MemoryDenyWriteExecute` too; their state is in `/var/lib/backend/`
(`StateDirectory`, mode 0700).

| Unit | Runs | Settings and credentials | Notable values |
|---|---|---|---|
| `backend-binlog-stream.service` | `binlog-stream.sh /var/lib/backend/binlog` | `mysql-binlog.cnf` as `BINLOG_CNF` | `Restart=always`, `RestartSec=10`: a database shutting down ends the stream with status 0 |
| `backend-mysql-backup.service` | `backup-mysql.sh backend /var/lib/backend/mysql-dumps`, once | `mysql-backup.cnf` as `MYSQL_CNF`; `BINLOG_DIR=/var/lib/backend/binlog`, `KEEP_DAYS=8` | started by its timer |
| `backend-backup-offsite.service` | `backup-offsite.sh` over the dumps, the binlog copies and `/var/lib/backend/offsite-mirror`, once | `mysql-backup.cnf`, `offsite.key` (`OFFSITE_KEY`), `offsite-ssh` (in `OFFSITE_SSH`, with `UserKnownHostsFile=/etc/backend/offsite-known-hosts`); `/etc/backend/offsite.env` (`OFFSITE_TARGET`), which must exist | `TimeoutStartSec=2h`; enabled once the owner names the host (Q-53) |
| `backend-restore-proof.service` | `restore-proof.sh` over the dumps and the copies, into `backend_proof`, once | `mysql-backup.cnf` (`SOURCE_CNF`), `mysql-scratch.cnf` (`SCRATCH_CNF`) | `Requires=` and `After=backend-scratch-mysql.service`; `TimeoutStartSec=6h` |
| `backend-restore-proof-offsite.service` | `restore-proof.sh` with `FROM_OFFSITE=1`, into `backend_proof_offsite`, once | as the weekly proof's, with `offsite.key`, `offsite-ssh` and `offsite.env` | `TimeoutStartSec=8h`; never at the same time as the weekly proof; enabled with the copy off the site |
| `backend-scratch-mysql.service` | `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/scratch-my.cnf`, as `mysql`: the release's MySQL, as the main server's | `scratch-my.cnf` (below) | `Type=notify`: started means ready for connections, so the proofs' `After=` waits for a server that answers; `RuntimeDirectory=backend-scratch-mysql` (mode 0750) for the socket and the pid file, made at every start since `/run` is emptied at boot (O-16); `TimeoutStartSec=10min`, `TimeoutStopSec=10min`, `Restart=on-failure` |

The timers are in [Timers](#timers).

### nginx/

For the release's own nginx, `/opt/backend/nginx`, run by `backend-nginx.service`; all three files go
in `/etc/backend/nginx/` (the release's `nginx-conf/`).

| File | Installed as | Configures |
|---|---|---|
| `nginx.conf` | `/etc/backend/nginx/nginx.conf` | The main file, which the distribution's package used to bring: `user nginx` for the workers, `worker_processes auto`, `worker_rlimit_nofile 65536` and `worker_connections 16384` (a lobby connection holds two descriptors, the client's and the gateway's; the unit's `LimitNOFILE` matches), pid in `/run/backend-nginx/`, logs in `/var/log/backend-nginx/`; includes `/opt/backend/nginx/conf/mime.types` and `/etc/backend/nginx/backend.conf` |
| `backend.conf` | `/etc/backend/nginx/backend.conf`, the machine's name filled in for `a.example.com` | Port 80 (IPv4 and IPv6): the CA's HTTP-01 challenge from `/var/www/acme`, 403 `HTTPS only` for the rest, no redirect. Port 443: TLS 1.2 and 1.3, ECDHE with AES-GCM or ChaCha20 only, session cache `10m` for 1 h, tickets off; certificate `/etc/backend/tls/fullchain.pem`, key `/etc/backend/credentials/privkey.pem`; `client_max_body_size 4k`; `X-Forwarded-For` appended. Routes: `= /lobby` to the gateway (`127.0.0.1:8090`, WebSocket upgrade, 150 s timeouts against the gateway's 120 s); `= /v1/sessions`, `= /v1/accounts`, `= /v1/guests`, `= /v1/accounts/upgrade` to the platform behind `limit_req` (2 a second per address, burst 30, 429); the rest of `/v1/` to the platform (`127.0.0.1:8080`, `keepalive 32`, `keepalive_timeout 20s`); `/` 404 |
| `backend-platform.conf` | `/etc/backend/nginx/backend-platform.conf` | What every platform location shares: `proxy_pass http://backend_platform`, HTTP/1.1 with keepalive, `X-Forwarded-For` and `Host`, `proxy_read_timeout 15s` |

### mysql/

For the release's MySQL 8.4, `/opt/backend/mysql`; the release's `mysql-conf/`.

| File | Installed as | Configures |
|---|---|---|
| `backend.cnf.example` | `/etc/backend/mysql/backend.cnf` on A, and on C with the replica's lines of [01 §9](../../docs/operations/01-deploy.md#9-mysql) | The database server, read by `backend-mysql.service`: `datadir=/var/lib/backend-mysql`, `socket` and `pid-file` in `/run/backend-mysql/`, `log-error` in `/var/log/backend-mysql/`, and 01 §9's settings: the private address only, `mysqlx = OFF`, TLS required (1.2 and 1.3), binlogs in `ROW` kept 7 days, UTC, `innodb_buffer_pool_size = 14G`, `max_connections = 151`; `[client]` names the socket. Its header initialises the data directory (`/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`) and sets root's password over the socket |
| `scratch-my.cnf.example` | `/etc/backend/scratch-my.cnf` on the backup machine | The scratch server the proofs restore onto: `datadir=/var/lib/backend-scratch-mysql`, `socket` and `pid-file` in `/run/backend-scratch-mysql/`, `port=3309`, `bind-address=127.0.0.1`, `mysqlx=OFF`, `skip-log-bin` (a restore here is never logged or replicated), `innodb_buffer_pool_size=4G`. Initialised once with `/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`; its root password then goes in `/etc/backend/credentials/mysql-scratch.cnf` |

The release's `mysqld` is not the distribution's `/usr/sbin/mysqld`, so the AppArmor rules once
shipped for the scratch server (O-16) are gone: Ubuntu's profile does not attach to it, and SELinux
runs a service started from `/opt` unconfined
([09 §6](../../docs/detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)).

### .gitignore

Re-includes `env/`, which the repository's top-level ignore file skips.

## The release

`scripts/make-release.sh` builds `target/release/backend-<version>/` and its `.tar.gz`, after
j-redis and a clean build ([scripts/README](../scripts/README.md#make-releasesh)). Unpacked to
`/opt/backend-<version>`, with `/opt/backend` a symlink to the one in use. It carries everything the
machines run but the operating system's base
([09 §4](../../docs/detailed-design/09-release-and-packaging.md#4-the-release)):

| Path | Holds |
|---|---|
| `runtime/` | the Java 21 runtime every Java process runs on, the stores too: made by `jlink` from `vendor/jdk-21`, 63 MB, its modules named ([09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)) |
| `lib/arena/`, `lib/platform/`, `lib/worker/`, `lib/gateway/` | each process's own jar and its runtime dependencies; one directory per process, so the MySQL driver stays off the arena and the gateway |
| `jredis/` | j-redis 2.2.1: `bin/` (the server's, the client's and the tools' launchers, which run on `$JAVA_HOME`), `lib/` (`j-redis-server.jar`, `j-redis-cli.jar`, `j-redis-tools.jar`), `conf/j-redis.conf` (the template of `/etc/backend/store-<name>.conf`) |
| `mysql/` | MySQL 8.4.11 LTS, `vendor/mysql-8.4/` as committed: `mysqld`, the client and the tools |
| `nginx/` | nginx 1.30.5 built for RHEL 9, `vendor/nginx-1.30/` as committed: `sbin/nginx`, `conf/mime.types` |
| `systemd/` | every unit, timer and drop-in example above |
| `env/` | as above |
| `mysql-conf/`, `nginx-conf/` | this folder's `mysql/` and `nginx/`, under names apart from the binaries |
| `scripts/` | `backup-mysql.sh`, `backup-store.sh`, `binlog-stream.sh`, `restore-drill.sh`, `store-restore-drill.sh`, `install-certificate.sh`, `promote-store.sh`, `promote-mysql.sh`, `record-backup.sh`, `restore-proof.sh`, `backup-offsite.sh`, `fetch-offsite.sh`; they find `mysql/`, `jredis/` and `runtime/` beside them |
| `VERSIONS.md` | what is inside, with versions: `vendor/VERSIONS.md`, then the backend's version and commit, j-redis's version, and the runtime's version and modules |
| `COMMIT` | the git commit it was built from |

Installing a machine is [01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine) (the
processes) and [01 §10](../../docs/operations/01-deploy.md#10-copies-off-the-databases-machine)
(the backup machine); a new release on running machines,
[01 §5](../../docs/operations/01-deploy.md#5-rolling-deployment).

## Environment variables

### Read by the processes

A variable ending `_FILE` names a file holding a secret, and wins over the variable without it,
which holds the value. A file that is named and cannot be read, or is empty, refuses the
configuration (exit 2), and one trailing line ending is not part of the secret. "Exit 2" below
means the process refuses to start and systemd does not restart it.

| Variable | Read by | Default | Meaning | Set in deploy |
|---|---|---|---|---|
| `BACKEND_DB_URL` | platform, worker, leaderboard rebuild | `jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true`, the development database | The JDBC URL; both hosts, primary and replica, after a replica is made ([01 §9](../../docs/operations/01-deploy.md#9-mysql)) | `platform.env.example`, `worker.env.example` |
| `BACKEND_DB_USER` | same | `backend` | The database user | same |
| `BACKEND_DB_PASSWORD_FILE` | same | none: then `BACKEND_DB_PASSWORD`, then the development password with a warning | The database password's file | `backend-platform`, `backend-worker@`, `backend-leaderboard-rebuild`: `%d/db-password` |
| `BACKEND_DB_PASSWORD` | same | none | The password itself | not set |
| `BACKEND_DB_POOL_SIZE` | same | 16 platform, 8 worker, 2 rebuild | The connection pool, 1 to 100; anything else exit 2 | commented in `platform.env.example` and `worker.env.example` |
| `BACKEND_STORE_ADDRESSES` | arena, gateway, platform, worker, leaderboard rebuild | none: the store host and port the unit passes | The `session` store with its replica, `host:port,host:port`; used instead of the passed host and port | commented in every `env/` example |
| `BACKEND_STORE_PASSWORD_FILE` | same; also `backup-store.sh`, `store-restore-drill.sh` | none: then `BACKEND_STORE_PASSWORD`, then no password, with a warning; a store that demands one means exit 2 | The store's password's file | every process unit: `%d/store-password` |
| `BACKEND_STORE_PASSWORD` | the processes | none | The password itself | not set |
| `BACKEND_EVENTS_STORE` | arena, worker | none: results share the `session` store | The `events` store, `host:port` or with its replica `host:port,host:port` | commented in `arena.env.example`, `worker.env.example` |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE` | arena, worker | none: then `BACKEND_EVENTS_STORE_PASSWORD`, then the session store's password | The `events` store's password, where it differs | not set; a drop-in if needed ([01 §2](../../docs/operations/01-deploy.md#secrets)) |
| `BACKEND_EVENTS_STORE_PASSWORD` | arena, worker | none | The password itself | not set |
| `BACKEND_METRICS_ADDR` | arena, gateway, platform, worker | none: no metrics server | `host:port` for `GET /metrics`; loopback by convention, not enforced; a port in use means exit 2 | platform `127.0.0.1:9101`, worker `9102`/`9112`, gateway `9103`, arena `9110` onwards |
| `BACKEND_ADMIN_ADDR` | platform | none: no admin API | The admin API's `host:port`; not loopback means exit 2 | commented in `platform.env.example`: `127.0.0.1:9120` |
| `BACKEND_ADMIN_TOKEN_FILE` | platform | none; required once `BACKEND_ADMIN_ADDR` is set, else exit 2 | The admin API's bearer secret's file | `platform-admin.conf.example`: `%d/admin-token` |
| `BACKEND_ADMIN_TOKEN` | platform | none | The secret itself | not set |
| `BACKEND_PAYMENT_PROVIDER` | platform | none: payments off, every payment route 503 `payments_off` | `simulated` only, which grants gems to whoever asks (development and drills); any other value exit 2 | commented in `platform.env.example` |
| `BACKEND_EDGE_CERTIFICATE` | gateway | none: no gauge | The certificate file nginx serves, reported as `backend_gateway_edge_certificate_expiry_timestamp_seconds` | `gateway.env.example` |
| `BACKEND_ARENA_TLS_KEYSTORE` | arena | none: plaintext, with a warning | The PKCS#12 keystore for match traffic | `arena-tls.conf.example`: `%d/arena-tls.p12` |
| `BACKEND_ARENA_TLS_PASSWORD_FILE` | arena | none; required with a keystore, else exit 2 | The keystore's password's file | `arena-tls.conf.example`: `%d/arena-tls-password` |
| `BACKEND_ARENA_TLS_PASSWORD` | arena | none | The password itself | not set |

### Passed as arguments by the units

The units hand these to `main` in a fixed order. systemd turns a variable missing from the env
file into an empty argument, and an empty number means exit 2.

| Variable | Read by | Default in the code | In the example |
|---|---|---|---|
| `ARENA_PORT` | arena | 9001 | 9001 |
| `MAP_SIZE` | arena | 5700 | 5700 |
| `MAX_PLAYERS` | arena | 150 | 150 |
| `SHAPES` | arena | 1500 | 1500 |
| `MAX_ROOMS` | arena | 4 | 4 |
| `MATCH_SECONDS` | arena | 0, continuous | 0 |
| `ARENA_BIND` | arena; `install-certificate.sh` | `0.0.0.0` | `0.0.0.0` |
| `ADVERTISE_HOST` | arena; `install-certificate.sh` | `127.0.0.1` | `a.example.com` |
| `STORE_HOST` | arena, gateway, platform, worker, rebuild | `127.0.0.1` | `10.0.0.1` |
| `STORE_PORT` | same | 6379 | 6379 |
| `GATEWAY_BIND` | gateway | `127.0.0.1` | `127.0.0.1` |
| `GATEWAY_PORT` | gateway | 8081 | 8090 |
| `PLATFORM_URL` | gateway | `http://127.0.0.1:8080` | the same |
| `PLATFORM_BIND` | platform | `127.0.0.1` | `127.0.0.1` |
| `PLATFORM_PORT` | platform | 8080 | 8080 |

The units also pass the instance: the arena's name (`%i`; code default `arena-<port>`) and spool
(`/var/lib/backend/arena-%i/spool`; code default `/var/lib/backend/spool/<name>`), the gateway's id
(`%H`, the host name; code default `gateway-<port>`) and the worker's (`worker-%i`; code default
`worker-1`).

### JVM properties the units set

| Property | Unit | Value | Why |
|---|---|---|---|
| `sun.net.httpserver.maxIdleConnections` | platform | 1000 (the JDK's default is 200) | Above nginx's keepalive pool, so the platform does not close a connection nginx is about to reuse |
| `io.netty.leakDetection.level` | arena | `disabled` | Netty's sampling of buffers for leaks, off in production |

`-Dlogback.configurationFile=/etc/backend/logback.xml` overrides the logging configuration without
a rebuild ([01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine)); no unit sets it.

### Read by the scripts

Each script's own settings are in [scripts/README](../scripts/README.md). Those the units set:

| Variable | Read by | Default | Set by |
|---|---|---|---|
| `BINLOG_CNF` | `binlog-stream.sh` | required | `backend-binlog-stream`: `%d/mysql-binlog.cnf` |
| `BINLOG_SERVER_ID` | `binlog-stream.sh` | 900 | not set |
| `MYSQL_CNF` | `backup-mysql.sh`, `backup-offsite.sh`, `record-backup.sh` | the client's defaults | `backend-mysql-backup`, `backend-backup-offsite`: `%d/mysql-backup.cnf` |
| `BINLOG_DIR` | `backup-mysql.sh` | none: no binlog copy pruned | `backend-mysql-backup`: `/var/lib/backend/binlog` |
| `KEEP_DAYS` | `backup-mysql.sh` | 8 | `backend-mysql-backup`: 8 |
| `RECORD` | `backup-mysql.sh`, `backup-offsite.sh` | 1 | not set |
| `RECORD_DB` | `record-backup.sh` | `backend` | `restore-proof.sh`: the source database |
| `OFFSITE_TARGET` | `backup-offsite.sh`, `fetch-offsite.sh` | none: nothing copied off the site | `/etc/backend/offsite.env`, made by the operator |
| `OFFSITE_KEY` | same | required with a target | the off-site units: `%d/offsite.key` |
| `OFFSITE_SSH` | same | `ssh -o BatchMode=yes` | the off-site units: `ssh -i %d/offsite-ssh -o UserKnownHostsFile=/etc/backend/offsite-known-hosts -o BatchMode=yes` |
| `SOURCE_CNF` | `restore-proof.sh` (required), `restore-drill.sh` | the local server | the proof units: `%d/mysql-backup.cnf` |
| `SCRATCH_CNF` | same | the local server | the proof units: `%d/mysql-scratch.cnf` |
| `FROM_OFFSITE` | `restore-proof.sh` | 0 | `backend-restore-proof-offsite`: 1 |
| `PROOF`, `TO_COPIES_END` | `restore-drill.sh` | 0 | `restore-proof.sh` |
| `STOP_AT`, `NO_SOURCE` | `restore-drill.sh` | none, 0 | by hand ([runbook](../../docs/operations/02-runbook.md#recovering-to-a-moment)) |
| `JREDIS_CLI`, `JREDIS_SERVER` | `backup-store.sh`, `promote-store.sh`, `store-restore-drill.sh` | the release's `jredis/bin/j-redis-cli` and `jredis/bin/j-redis-server` beside `scripts/`, on its `runtime/` (`JAVA_HOME`, unless set); without a `jredis/` there, `/opt/j-redis/bin/j-redis-cli` and `/opt/j-redis/bin/j-redis-server` | by hand |
| `MYSQL_FAILOVER_CNF` | `promote-mysql.sh` | `/etc/backend/credentials/mysql-failover.cnf` | by hand |
| `BACKEND_DB_NAME`, `MYSQL`, `PROMOTE_STALE_SECONDS` | `promote-mysql.sh` | `backend`, `mysql`, 300 | by hand |
| `MVN`, `JDK` | `make-release.sh` | `mvn`, `vendor/jdk-21` (the JDK whose `jmods/` the runtime is linked from) | by hand; `build-offline.sh` sets `MVN` |
| `TLS_DIR`, `CREDENTIALS_DIR`, `CONFIG_DIR`, `NGINX_CONF`, `NGINX_TEST`, `NGINX_RELOAD`, `EDGE_PORT`, `CA_FILE`, `WARN_DAYS`, `RENEWED_LINEAGE` | `install-certificate.sh` | see its header | certbot sets `RENEWED_LINEAGE` |

### Development, tests and drills

| Variable | Read by | Default |
|---|---|---|
| `JDBC_URL`, `DB_USER`, `DB_PASSWORD` | the persistence and worker tests | `jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true`, `backend`, `backend-dev-password` |
| `BACKEND_BOT_PROFILE`, `BACKEND_BOT_STAYS`, `BACKEND_BOT_VIA`, `BACKEND_BOT_TRUSTSTORE`, `BACKEND_BOT_TRUSTSTORE_PASSWORD` | `tools/BotClient` | none ([01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic)) |
| `BACKEND_DRILL_SQL`, `BACKEND_ADMIN_URL`, `BACKEND_ADMIN_TOKEN` | the headless client | set by `client/headless-drill.sh` |
| `TMPDIR`, `SEED_DB`, `SEED_PASSWORD` | `backup-drill.sh` | `/tmp`, `backend_dev`, `backend-dev-password` |
| `RELEASE` | `backup-drill.sh` | none: the machine's `/usr/sbin/mysqld` and tools; given a release with `mysql/`, its MySQL 8.4 |
| `SKIP_TESTS`, `WORK` | `build-offline.sh` | 0 (every test run), `backend-build-offline` under `TMPDIR` or `/tmp` (its settings and local repository) |
| `IMAGE` | `check-release-el9.sh` | `registry.access.redhat.com/ubi9/ubi-minimal:9.5` |
| `RELEASE`, `JREDIS_JAR`, `JREDIS_CLI_JAR`, `TOOLS_JAR`, `JAVA`, `MYSQLD_BIN`, `DOTNET`, `TLS`, `FAILOVER`, `MYSQL_FAILOVER`, `BOTS`, `ARENA_OPTS`, `SOAK`, `STAYS`, `SAMPLE_EVERY`, `JUDGE_FROM`, `WORKERS`, `TAKEOVER`, `DRAIN_AFTER`, `BACKEND_DB_PASSWORD` | `client/headless-drill.sh` | its header |

## Ports

### Production

| Port | Listener | Bound to | Where |
|---|---|---|---|
| 80, 443 | nginx | every address, IPv4 and IPv6 | A, B, C |
| 9001 in the example, one per arena | arena, match traffic (TLS once a certificate is installed) | `0.0.0.0` | A (2), B (3), C (2) |
| 8090 | gateway (`/lobby` behind nginx) | `127.0.0.1` | A, B, C |
| 8080 | platform (`/v1/` behind nginx; the gateway calls it too) | `127.0.0.1` | A, B, C |
| 9120 | platform's admin API, when named | loopback, enforced | A, B, C |
| 9101 | platform's metrics | `127.0.0.1` | A, B, C |
| 9103 | gateway's metrics | `127.0.0.1` | A, B, C |
| 9110 onwards | arenas' metrics, one per instance | `127.0.0.1` | A, B, C |
| 9102, 9112 | workers' metrics (`c1`, `c2`) | `127.0.0.1` | C |
| 6379 | j-redis `session`: primary on A, replica on B | `127.0.0.1` and the machine's private address ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) | A, B |
| 6380 | j-redis `events`: primary on B, replica on C | the same | B, C |
| 3306 | MySQL: primary on A, replica on C; TLS required | the private address (`10.0.0.1`, `10.0.0.3`); `mysqlx = OFF`, so nothing on 33060 | A, C |
| 3309 | the scratch MySQL server the proofs restore onto | `127.0.0.1`; `mysqlx=OFF` | C |

Between the machines: C streams binlogs from A's 3306, and its replica replicates and clones from
it; each store's replica connects to its primary's port; the copy off the site leaves C by SSH, to
the host the owner names.

### Drills and development

| Port | What | Used by |
|---|---|---|
| 6390 | j-redis | `client/headless-drill.sh` |
| 6392 | j-redis replica (`FAILOVER`) | `client/headless-drill.sh` |
| 8093 | platform | `client/headless-drill.sh` |
| 8094 | gateway | `client/headless-drill.sh` |
| 9011 | arena | `client/headless-drill.sh` |
| 9195, 9196 | platform's metrics, its admin API | `client/headless-drill.sh` |
| 9197, 9198, 9199 | the arena's, the gateway's and the worker's metrics | `client/headless-drill.sh` |
| 3307, 3308 | MySQL primary and replica (`MYSQL_FAILOVER`) | `client/headless-drill.sh` |
| 3307, 3309 | MySQL primary and scratch server | `backup-drill.sh` (not at the same time as `MYSQL_FAILOVER`) |
| 3306 | the development MySQL (`backend_dev`, `backend_test`) | the tests, both drills |
| 6380, 8080, 8081, 9001 | j-redis, platform, gateway, arena | the by-hand example in [backend/README](../README.md#running-it) |

All bind `127.0.0.1`. 6381 and 8082 belong to other processes on the development machine.

## Timers

All four are `Persistent=true`: a run missed while the machine was down happens at the next start.

| Timer | Starts | Schedule |
|---|---|---|
| `backend-mysql-backup.timer` | `backend-mysql-backup.service`, the nightly dump | 03:30 UTC every day, within 15 minutes after (`RandomizedDelaySec=15min`) |
| `backend-backup-offsite.timer` | `backend-backup-offsite.service`, the copy off the site | hourly, within 5 minutes after; enabled once the owner names the host (Q-53) |
| `backend-restore-proof.timer` | `backend-restore-proof.service`, the weekly proof | Sundays at 04:00 UTC |
| `backend-restore-proof-offsite.timer` | `backend-restore-proof-offsite.service`, the proof from off the site | the first Sunday of each month (`Sun *-*-01..07`) at 05:00 UTC; enabled with the copy off the site |

The binlog stream runs all the time (`backend-binlog-stream.service`). Nothing copies a store on a
timer (D-31): each has its replica. The certificate's renewal is certbot's own timer.
