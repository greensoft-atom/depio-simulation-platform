# deploy

This folder holds what the release installs on a machine besides the jars and the binaries it carries: the systemd
units, the nginx configuration, the example settings files and the MySQL servers' configuration. This page is for
operators who install and change machines, and for developers who change these files. It says, file by file, where
each file goes, what reads it and what its notable values are. It also holds three catalogues that cut across the
files: every [environment variable](#environment-variables), every [port](#ports) and every [timer](#timers).

`scripts/make-release.sh` copies each folder here into the release as it is; `mysql/` and `nginx/` go in under the
names `mysql-conf/` and `nginx-conf/` ([the release](#the-release)). Related pages:

- how a machine is installed: [operations/01-deploy](../../docs/operations/01-deploy.md), and for the launch's one
  machine, step by step, the [install guide](../../docs/operations/03-install-guide.md);
- what to do when something breaks: the [runbook](../../docs/operations/02-runbook.md);
- the scripts the units run: [scripts/README](../scripts/README.md);
- every route from the phone through nginx to a process:
  [operations/11](../../docs/operations/11-phone-to-backend-routes.md);
- the pictures: [diagrams/07](../../docs/diagrams/07-deploy-and-operations.md).

**The addresses in the examples.** The examples name three machines by their private addresses: A `10.0.0.1`,
B `10.0.0.2`, C `10.0.0.3` ([01 §1](../../docs/operations/01-deploy.md#1-what-runs-where)). `203.0.113.10` stands for
the site's one public address, which the provider forwards to one machine, the front (D-82). The arenas' names,
`a1.arena.internal` and the rest, are labels that nginx routes by; they are in no DNS. The site launches on one
machine ([D-93](../../docs/architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)).
There the [install guide](../../docs/operations/03-install-guide.md) rewrites the addresses to loopback, sets up
three arenas and two workers (each with its `worker-<id>.env`), and gives smaller heaps by a drop-in per unit,
`heap.conf`, which sets `_JAVA_OPTIONS`. The heaps shipped in the units are the three machines'.

## Files

### env/

These are the example settings files. Each is copied to `/etc/backend/` without `.example`, then filled in. systemd
reads it with `EnvironmentFile=`, as root, before the process starts. Each process's secrets are in its own file
too, as plain variables at the file's end, so every one of these files is `root:root 0600`
([01 §2](../../docs/operations/01-deploy.md#secrets),
[D-102](../../docs/architecture/03-decision-log.md#d-102--a-processs-secrets-are-in-its-own-settings-file-as-plain-variables-the-units-carry-none)).
A secret is never in a unit's `Environment=`, which `systemctl show` prints to any user.

| File | Installed as | Read by | Notable values |
|---|---|---|---|
| `arena.env.example` | `/etc/backend/arena-<name>.env`, one per arena instance: `a1`, `a2` on A, `b1` to `b3` on B, `c1`, `c2` on C ([D-82](../../docs/architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)) | `backend-arena@<name>` | `ARENA_PORT=9011` (a machine's first arena; the second 9012, the third 9013; 9001 is nginx's on every machine), `ARENA_BIND=10.0.0.1` (the machine's private address, where the front's nginx reaches the arena), `ADVERTISE_HOST=203.0.113.10` and `BACKEND_ADVERTISE_PORT=9001` (the site's address and port: what clients dial), `BACKEND_ADVERTISE_TLS_NAME=a1.arena.internal` (each arena its own name; nginx routes by it), `MAP_SIZE=5700`, `MAX_PLAYERS=150`, `SHAPES=1500`, `MAX_ROOMS=12` (12 rooms of 150 are 1 800 players, short of the 2 100 to 2 300 where an arena's two network threads fill, operations/04 §2.5), `MATCH_SECONDS=0` (continuous), `STORE_HOST=10.0.0.1`, `STORE_PORT=6379`, `BACKEND_METRICS_ADDR=127.0.0.1:9110` (each instance its own: 9110, 9111, 9113). Commented: `BACKEND_STORE_ADDRESSES=10.0.0.1:6379,10.0.0.2:6379`, `BACKEND_EVENTS_STORE=10.0.0.2:6380,10.0.0.3:6380`, `BACKEND_RESULT_RETENTION_HOURS=24`, the keystore of an arena with TLS of its own, and the secrets `BACKEND_STORE_PASSWORD`, `BACKEND_EVENTS_STORE_PASSWORD` |
| `gateway.env.example` | `/etc/backend/gateway.env` | `backend-gateway` | `GATEWAY_BIND=10.0.0.1`, `GATEWAY_PORT=8090` (nginx's upstream), `PLATFORM_URL=http://10.0.0.1:8080` (this machine's platform), `BACKEND_TRUSTED_PROXIES=10.0.0.1,10.0.0.2,10.0.0.3`, store `10.0.0.1:6379`, `BACKEND_METRICS_ADDR=127.0.0.1:9103`, `BACKEND_EDGE_CERTIFICATE=/etc/backend/tls/fullchain.pem`. Commented: `BACKEND_STORE_ADDRESSES`, the secret `BACKEND_STORE_PASSWORD` |
| `platform.env.example` | `/etc/backend/platform.env` | `backend-platform` | `PLATFORM_BIND=10.0.0.1` (the machine's private address), `PLATFORM_PORT=8080`, `BACKEND_TRUSTED_PROXIES=10.0.0.1,10.0.0.2,10.0.0.3` (the front's nginx, which can be any of the three), store `10.0.0.1:6379`, `BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12`, `BACKEND_DB_USER=backend`, `BACKEND_METRICS_ADDR=127.0.0.1:9101`, the watch's `BACKEND_WATCH_TARGETS`, `BACKEND_WATCH_PEERS=B=10.0.0.2:8080,C=10.0.0.3:8080` and `BACKEND_WATCH_MACHINE=A` (machine A's values). Commented: `BACKEND_STORE_ADDRESSES`, `BACKEND_WATCH_DISKS`, `BACKEND_ALERT_WEBHOOK_URL`, `BACKEND_ALERT_REPEAT_HOURS=4`, `BACKEND_DB_POOL_SIZE=16`, `BACKEND_ADMIN_ADDR=127.0.0.1:9120`, `BACKEND_PAYMENT_PROVIDER=simulated`, and the secrets `BACKEND_DB_PASSWORD`, `BACKEND_STORE_PASSWORD`, `BACKEND_ADMIN_TOKEN` |
| `worker.env.example` | `/etc/backend/worker.env` | every `backend-worker@` on the machine, and `backend-leaderboard-rebuild` | the store, `BACKEND_DB_URL` and `BACKEND_DB_USER`, as the platform's. Commented: `BACKEND_STORE_ADDRESSES`, `BACKEND_EVENTS_STORE`, `BACKEND_RESULT_RETENTION_HOURS=24`, `BACKEND_DB_POOL_SIZE=8`, and the secrets `BACKEND_DB_PASSWORD`, `BACKEND_STORE_PASSWORD`, `BACKEND_EVENTS_STORE_PASSWORD`. No metrics address: that is per instance |
| `worker-instance.env.example` | `/etc/backend/worker-<id>.env`, one per worker instance | `backend-worker@<id>`, after `worker.env`, so what it sets wins | `BACKEND_METRICS_ADDR=127.0.0.1:9102` (`c1`; `c2` takes `9112`). Two instances given one port: the second exits 2 |
| `store.env.example` | `/etc/backend/store-<name>.env`, one per store instance (`session`, `events`) | `backend-store@<name>` | `STORE_HEAP=8g`, used for both `-Xms` and `-Xmx`. The store's `maxmemory` is about 40 % of it, since ZGC makes the same data estimate 25–50 % larger ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)). The `events` instance's heap and `maxmemory` for a number of players are in [operations/04 §3.4](../../docs/operations/04-scaling-and-performance.md#34-j-redis) (D-83) |

With `BACKEND_STORE_ADDRESSES` set, keep `STORE_HOST` and `STORE_PORT` set all the same. The units still pass them as
arguments, and an empty port refuses the configuration (exit 2, not restarted; O-22).

### systemd/

These files are copied to `/etc/systemd/system/`, followed by `systemctl daemon-reload`.

**What every process unit shares.** Every process unit runs as the user `backend`. It runs
`/opt/backend/runtime/bin/java`, the release's own Java 21 runtime
([09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)), with the classpath
`/opt/backend/lib/<process>/*` and `--add-opens java.base/java.nio=ALL-UNNAMED`. The four long-running ones
(arena, gateway, platform, worker) also:

- use ZGC (`-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch`);
- write heap dumps and a GC log in `/var/log/backend/` (`LogsDirectory=backend`);
- restart on failure (`Restart=on-failure`, `RestartSec=2`), but not on exit 2, a refused configuration, which a
  restart does not fix;
- count exit 143 (SIGTERM) as a clean stop.

All of them run sandboxed: `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `PrivateDevices`,
`NoNewPrivileges`, an empty capability set, the kernel, clock and host-name protections, and restricted address
families and namespaces. They do not use `MemoryDenyWriteExecute`, because the JIT writes code and then runs it.

**The processes**

| Unit | Runs | Settings and credentials | Notable values |
|---|---|---|---|
| `backend-arena@.service` | `com.backend.arena.ArenaMain`; the instance `%i` is the arena's name, unique across the fleet ([01 §2](../../docs/operations/01-deploy.md#2-conventions)) | `arena-%i.env`, its secret in it (D-102) | `-Xms6g -Xmx6g`; `-Dio.netty.leakDetection.level=disabled`; the result spool in `/var/lib/backend/arena-%i/spool` (`StateDirectory`), where results stay until the store has them; `LimitNOFILE=65536`; `TimeoutStopSec=12min`, for the drain: public rooms' players sent back to the lobby, made matches played to their end for up to eleven minutes, then every result published. Pin each instance to its own cores with a drop-in that the operator makes: `systemctl edit backend-arena@a1`, then `[Service]` `CPUAffinity=2-5` (the ranges are the machine's, architecture/01 §3) |
| `backend-gateway.service` | `com.backend.gateway.GatewayMain`; its id is the host name (`%H`), which pushes are routed by | `gateway.env`, its secret in it | `-Xms3g -Xmx3g`; `LimitNOFILE=65536`; `TimeoutStopSec=30` |
| `backend-platform.service` | `com.backend.platform.PlatformMain` | `platform.env`, its secrets in it | `-Xms3g -Xmx3g`; `-Dsun.net.httpserver.maxIdleConnections=1000` (above nginx's keepalive pool); `After=backend-mysql.service`; `LimitNOFILE=65536`; `TimeoutStopSec=30` |
| `backend-worker@.service` | `com.backend.worker.WorkerMain`, consumer `worker-%i`; the instance is stable across restarts and unique across the fleet, or two workers re-drive each other's entries | `worker.env`, its secrets in it; then `worker-%i.env`, which wins where both set a variable. That second file is optional (`EnvironmentFile=-`, the `-` meaning a missing file is skipped) | `-Xms1536m -Xmx1536m` (two workers share 4 cores and 4 GB on C); `After=backend-mysql.service`; `TimeoutStopSec=30` |
| `backend-leaderboard-rebuild.service` | `com.backend.worker.LeaderboardRebuild`, once (`Type=oneshot`); not enabled | `worker.env`, its secrets in it | `-Xmx512m`; started by hand, on one worker machine, after a store loses its disk, or after an outage in which results were applied but not ranked ([runbook §3](../../docs/operations/02-runbook.md#3-per-component-procedures)). Safe on a healthy store: every write only raises a score |

**The servers the release carries**, in place of the distribution's packages (D-77,
[09](../../docs/detailed-design/09-release-and-packaging.md)). Each runs as a system user of its own, made at install
([01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine)).

| Unit | Runs | Settings | Notable values |
|---|---|---|---|
| `backend-store@.service` | `/opt/backend/jredis/lib/j-redis-server.jar` on `/opt/backend/runtime`, as `jredis`; the instance `%i` is the store's name, `session` or `events` ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) | `/etc/backend/store-%i.conf`, made from the release's `jredis/conf/j-redis.conf` (port, bind, requirepass, replicaof, dir); the heap from `store-%i.env` | `-Xms` and `-Xmx` `${STORE_HEAP}`, ZGC, `-Djava.net.preferIPv4Stack=true`; data in `/var/lib/backend-store/%i` and logs in `/var/log/backend-store/%i`, both 0700, with `UMask=0077` (the data holds every live session token, and a heap dump holds the data); restarts on failure (exit 4, fail-stop) but not on exit 1, 2 or 3 (its configuration refused, its data directory locked, its data not loadable), which need a person; `LimitNOFILE=65536`; `TimeoutStopSec=60`; a `CPUAffinity=` drop-in, made as for the arenas |
| `backend-mysql.service` | `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf`, as `mysql`; on A, and on C for the replica | `backend.cnf` ([mysql/](#mysql)) | `Type=notify`: "started" means ready for connections; data in `/var/lib/backend-mysql` (`StateDirectory`, 0750), the socket and the pid file in `/run/backend-mysql/`, the error log in `/var/log/backend-mysql/`; `UMask=0027`; `TimeoutStartSec=infinity` (crash recovery takes as long as it takes), `TimeoutStopSec=15min`; restarts on failure but not on exit 1 (its configuration refused, or its data could not start); `LimitNOFILE=10000` |
| `backend-nginx.service` | `/opt/backend/nginx/sbin/nginx`: the master runs as root, which binds 80, 443 and 9001 (D-82, D-115); the workers run as `nginx` | `/etc/backend/nginx/nginx.conf` ([nginx/](#nginx)) | `Type=forking`; `nginx -t` before a start, and a reload is `nginx -t` then `HUP`, so a broken file is refused and no connection is dropped; stopped with `SIGQUIT`, the workers finishing what they serve (a lobby socket is closed and the client reconnects), `TimeoutStopSec=30`; `Restart=on-failure`; logs in `/var/log/backend-nginx/`, rotated by `backend-nginx-logs.timer`; `LimitNOFILE=262144`, two descriptors for each player's connection |
| `backend-nginx-logs.service` | `rotate-nginx-logs.sh`, once: nginx's logs renamed with a UTC stamp, nginx told to reopen them, the renamed ones compressed, those older than `KEEP_DAYS` deleted (D-85) | `LOG_DIR`, `PID_FILE` and `KEEP_DAYS=14` (1 to 365) in the unit | started by its timer; runs as root, as nginx's master does (the log files are its, and the signal goes to it), under the backup units' sandbox, with `MemoryDenyWriteExecute`, writing in `/var/log/backend-nginx` alone. 14 days is long enough to look into an incident, and does not keep players' addresses for good |

`backend-mysql` and `backend-nginx` run with `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`PrivateDevices`, `NoNewPrivileges` and the kernel and control-group protections, and write only in the directories
their units make. `backend-store@` is sandboxed as the process units are. `backend-scratch-mysql` sets no sandbox.

**The backup machine**: C on three machines
([01 §10](../../docs/operations/01-deploy.md#10-copies-off-the-databases-machine)); the one machine at launch, with the
copies on a disk of their own (D-93). The scripts run as `backend-backup`, a user of its own, not as `backend`: the
copies are every change to every row, and the processes have no business reading them. They run from
`/opt/backend/scripts/`, sandboxed as the processes are and with `MemoryDenyWriteExecute` too (bash and the MySQL
tools need no JIT). Their state is in `/var/lib/backend/` (`StateDirectory`, mode 0700). Their option files and keys
are in `/etc/backend/backup/`, owned by the backup user, 0600; the scripts read them themselves (D-102), and each
unit names the path.

| Unit | Runs | Settings and credentials | Notable values |
|---|---|---|---|
| `backend-binlog-stream.service` | `binlog-stream.sh /var/lib/backend/binlog` | `mysql-binlog.cnf` as `BINLOG_CNF` (`[client]` `host=10.0.0.1`, `user=binlog`, `password`, `ssl-mode=VERIFY_IDENTITY`, `ssl-ca=/etc/backend/tls/mysql-ca.pem`) | `Restart=always`, `RestartSec=10`: a database that shuts down ends the stream with status 0, and the stream must resume when the database is back |
| `backend-mysql-backup.service` | `backup-mysql.sh backend /var/lib/backend/mysql-dumps`, once | `mysql-backup.cnf` as `MYSQL_CNF` (as above, `user=backup`); `BINLOG_DIR=/var/lib/backend/binlog`, `KEEP_DAYS=8` | started by its timer |
| `backend-backup-offsite.service` | `backup-offsite.sh` over the dumps, the binlog copies and `/var/lib/backend/offsite-mirror`, once | `mysql-backup.cnf`; `offsite.key` (`OFFSITE_KEY`), the key, kept off the machines too; `offsite-ssh` (in `OFFSITE_SSH`, with `UserKnownHostsFile=/etc/backend/offsite-known-hosts`), an SSH key for the host; and `/etc/backend/offsite.env` (`OFFSITE_TARGET=user@host:dir`), which must exist | `TimeoutStartSec=2h`; enabled once the owner names the host (Q-53) |
| `backend-restore-proof.service` | `restore-proof.sh` over the dumps and the copies, into `backend_proof`, once | `mysql-backup.cnf` (`SOURCE_CNF`), `mysql-scratch.cnf` (`SCRATCH_CNF`: `[client]` `host=127.0.0.1`, `port=3309`, `user=root`, `password`) | `Requires=` and `After=backend-scratch-mysql.service`; `TimeoutStartSec=6h`; one proof at a time, by `flock` on `/var/lib/backend/proof/lock` (O-18) |
| `backend-restore-proof-offsite.service` | `restore-proof.sh` with `FROM_OFFSITE=1`, into `backend_proof_offsite`, once | as the weekly proof's, with `offsite.key`, `offsite-ssh` and `offsite.env`; `TMPDIR=/var/lib/backend/offsite-fetch`, so the copy is fetched onto the backup volume, not into `/tmp` on the root filesystem (O-21) | `TimeoutStartSec=8h`; never at the same time as the weekly proof (the same lock), and a database of its own, so the weekly proof's `backend_proof` is kept when it fails, for the operator; enabled with the copy off the site |
| `backend-scratch-mysql.service` | `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/scratch-my.cnf`, as `mysql`: the release's MySQL, as for the main server | `scratch-my.cnf` ([mysql/](#mysql)) | `Type=notify`: "started" means ready for connections, so the proofs' `After=` waits for a server that answers; `RuntimeDirectory=backend-scratch-mysql` (mode 0750) for the socket and the pid file, made at every start, since `/run` is emptied at boot and mysqld makes no parent directory (O-16); `TimeoutStartSec=10min`, `TimeoutStopSec=10min`, `Restart=on-failure` |

The timers are in [Timers](#timers).

### nginx/

These files configure the release's own nginx, `/opt/backend/nginx`, run by `backend-nginx.service`. All six go in
`/etc/backend/nginx/` (the release's `nginx-conf/`), the same on every machine, since any machine may be the front
([D-82](../../docs/architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)).
On the front, nginx serves three public ports:

- **443**, over TLS: the API and the lobby;
- **80**, in clear, for a client without TLS: the same API and lobby, and matches, each asking for its arena by name
  ([D-115](../../docs/architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included));
- **9001**, over TLS: matches, each passed to the arena its TLS name gives.

What crosses 80 is readable by anyone on the way, passwords and session tokens too: that is the owner's choice
(Q-64). [operations/11](../../docs/operations/11-phone-to-backend-routes.md) walks through these files line by line.

| File | Installed as | Configures |
|---|---|---|
| `nginx.conf` | `/etc/backend/nginx/nginx.conf` | The main file, which the distribution's package used to bring: `user nginx` for the workers; `worker_processes auto`; `worker_rlimit_nofile 262144` and `worker_connections 16384` (every connection holds two descriptors, and the front holds every player's: a lobby and a match connection each, 200 000 at 50 000 players; the unit's `LimitNOFILE` matches); the pid in `/run/backend-nginx/`; the logs in `/var/log/backend-nginx/` (the access log with `$upstream_addr`, the machine that served); `keepalive_timeout 65s`. It includes `/opt/backend/nginx/conf/mime.types` and `backend.conf` in its `http` block, and `backend-stream.conf` in its `stream` block |
| `backend.conf` | `/etc/backend/nginx/backend.conf`, with the site's public address filled in for `203.0.113.10` | One server that listens on 443 with TLS and on 80 in clear, IPv4 and IPv6, with the same locations, limits and upstreams on both (D-115). TLS: 1.2 and 1.3, ECDHE with AES-GCM or ChaCha20 only, session cache `10m` for 1 h, tickets off (nginx never rotates its ticket key); certificate `/etc/backend/tls/fullchain.pem`, key `/etc/backend/credentials/privkey.pem`. `client_max_body_size 4k`; `X-Forwarded-For` appended. Routes: `= /lobby` to the gateways (WebSocket upgrade, 150 s timeouts against the gateway's own 120 s); `= /v1/sessions`, `= /v1/accounts`, `= /v1/guests` and `= /v1/accounts/upgrade` to the platforms behind `limit_req` (2 a second per address, burst 30, answered 429); the rest of `/v1/` to the platforms; `~ ^/match/<name>` to the arena of that name in `backend-arenas.conf` (D-115): the request passed on with none of the phone's headers (`proxy_pass_request_headers off`) and `X-Forwarded-For` set to `$remote_addr`, the address nginx saw, the arena's 101 back, 5 s to connect and 60 s timeouts, a name not in the list answered 404; `/` answered 404 |
| `backend-upstreams.conf` | `/etc/backend/nginx/backend-upstreams.conf` | The platforms (`10.0.0.1-3:8080`, `keepalive 32`, `keepalive_timeout 20s`: below the platform's 1 000 idle connections and its 30 s idle timeout) and the gateways (`10.0.0.1-3:8090`, `least_conn`) of all three machines. One that fails is passed over for 10 s (nginx's defaults); a `POST` already sent is not sent to another. On one machine: its own, on `127.0.0.1` ([install guide §9](../../docs/operations/03-install-guide.md#9-nginx-and-the-certificate)) |
| `backend-stream.conf` | `/etc/backend/nginx/backend-stream.conf` | Port 9001 (IPv4 and IPv6): match traffic's TLS ended with the same certificate, TLS 1.2 and 1.3; a map from each arena's name (SNI, `$ssl_server_name`) to its address, which includes `backend-arenas.conf`, a name not there closed; `proxy_protocol on`, so each connection begins with one line naming the phone's address, which the arena reads (D-105); `proxy_connect_timeout 5s`; `proxy_timeout 60s`, longer than the arena's own 30 s; `stream.log`, each connection's name and arena |
| `backend-arenas.conf` | `/etc/backend/nginx/backend-arenas.conf` | The arenas by name, one line each: `a1.arena.internal 10.0.0.1:9011;` and so on to `c2`. Each line is one arena's `BACKEND_ADVERTISE_TLS_NAME`, then its machine's private address and its `ARENA_PORT`. Both maps include this one list: 9001's (by the TLS name) and 80's `/match/` (by the name in the path); `private-ca.sh names` reads it for the certificate, and `install-certificate.sh` checks the certificate against it (D-115). On one machine: its three arenas, on `127.0.0.1` ([install guide §9](../../docs/operations/03-install-guide.md#9-nginx-and-the-certificate)) |
| `backend-platform.conf` | `/etc/backend/nginx/backend-platform.conf` | What every platform location shares: `proxy_pass http://backend_platform`, HTTP/1.1 with keepalive, `X-Forwarded-For` and `Host`, `proxy_read_timeout 15s` (a login waits for Argon2 and may queue behind 160 others; a client allows 10 s) |

### mysql/

These files configure the release's MySQL 8.4, `/opt/backend/mysql`. They are the release's `mysql-conf/`.

| File | Installed as | Configures |
|---|---|---|
| `backend.cnf.example` | `/etc/backend/mysql/backend.cnf` on A, and on C with the replica's lines of [01 §9](../../docs/operations/01-deploy.md#9-mysql) | The database server, read by `backend-mysql.service`: `datadir=/var/lib/backend-mysql`; `socket` and `pid-file` in `/run/backend-mysql/`; `log-error` in `/var/log/backend-mysql/`; and 01 §9's settings: `bind-address` the private address only; `mysqlx = OFF` (on, the X Protocol listens on 33060 on every interface, whatever `bind-address` says); TLS required (`require_secure_transport = ON`, TLS 1.2 and 1.3, the server's certificate in `/etc/mysql-tls/`); binlogs in `ROW` format kept 7 days; UTC; `innodb_buffer_pool_size = 14G`; `max_connections = 151`. `[client]` names the socket. Its header initialises the data directory: as root, `install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql /var/log/backend-mysql`, then `/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`; then start `backend-mysql.service` and set root's password over the socket |
| `scratch-my.cnf.example` | `/etc/backend/scratch-my.cnf` on the backup machine | The scratch server the proofs restore onto, a server of its own on loopback, so the replica is never touched: `datadir=/var/lib/backend-scratch-mysql`; `socket` and `pid-file` in `/run/backend-scratch-mysql/`; `port=3309`; `bind-address=127.0.0.1`; `mysqlx=OFF`; `skip-log-bin` (a restore here is never logged, so never replicated); `innodb_buffer_pool_size=4G`. Initialised once with `/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`; then start `backend-scratch-mysql.service`, set root's password through its socket, and put the password in `/etc/backend/backup/mysql-scratch.cnf`, 0600, the backup user's (D-102) |

The release's `mysqld` is not the distribution's `/usr/sbin/mysqld`. So the AppArmor rules once shipped for the
scratch server (O-16) are gone: Ubuntu's profile does not attach to the release's binary, and SELinux runs a service
started from `/opt` unconfined
([09 §6](../../docs/detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)).

### .gitignore

Re-includes `env/`. The repository's top-level ignore file skips any `env/` directory (a Python virtualenv); this one
holds the example settings the release ships.

## The release

`scripts/make-release.sh` builds `target/release/backend-<version>/` and its `.tar.gz`, after j-redis and a clean
build ([scripts/README](../scripts/README.md#make-releasesh)). On a machine it is unpacked to
`/opt/backend-<version>`, and `/opt/backend` is made a symbolic link to the version in use, so a rollback is the link
put back ([01 §5](../../docs/operations/01-deploy.md#5-rolling-deployment)). The release itself contains no
symbolic link (D-106). It carries everything the machines run except the operating system's base
([09 §4](../../docs/detailed-design/09-release-and-packaging.md#4-the-release)):

| Path | Holds |
|---|---|
| `runtime/` | the Java 21 runtime that every Java process runs on, the stores too: made by `jlink` from `vendor/jdk-21`, 63 MB, its modules named ([09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)) |
| `lib/arena/`, `lib/platform/`, `lib/worker/`, `lib/gateway/` | each process's own jar and its runtime dependencies; one directory per process, so the MySQL driver stays off the arena and the gateway |
| `jredis/` | j-redis 2.2.2: `bin/` (the server's, the client's and the tools' launchers, which run on `$JAVA_HOME`), `lib/` (`j-redis-server.jar`, `j-redis-cli.jar`, `j-redis-tools.jar`), `conf/j-redis.conf` (the template of `/etc/backend/store-<name>.conf`) |
| `mysql/` | MySQL 8.4.11 LTS, `vendor/mysql-8.4/` as committed: `mysqld`, the client and the tools |
| `nginx/` | nginx 1.30.5 built on Rocky 8 for glibc 2.28 (D-100), `vendor/nginx-1.30/` as committed: `sbin/nginx`, `conf/mime.types` |
| `systemd/` | every unit and timer above. No drop-in is shipped: the operator makes the `CPUAffinity=` ones with `systemctl edit` |
| `env/` | the example settings files above |
| `mysql-conf/`, `nginx-conf/` | this folder's `mysql/` and `nginx/`, under names that keep them apart from the binaries |
| `scripts/` | `backup-mysql.sh`, `backup-store.sh`, `binlog-stream.sh`, `restore-drill.sh`, `store-restore-drill.sh`, `install-certificate.sh`, `promote-store.sh`, `promote-mysql.sh`, `record-backup.sh`, `restore-proof.sh`, `backup-offsite.sh`, `fetch-offsite.sh`, `private-ca.sh`, `rotate-nginx-logs.sh`, `check-bind.sh`; they find `mysql/`, `jredis/` and `runtime/` beside them |
| `VERSIONS.md` | what is inside, with versions: `vendor/VERSIONS.md`, then the backend's version and commit, j-redis's version, and the runtime's version and modules |
| `COMMIT` | the git commit it was built from, with `-dirty` when the tree differed from it |

Installing a machine is [01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine) (the processes) and
[01 §10](../../docs/operations/01-deploy.md#10-copies-off-the-databases-machine) (the backup machine). A new release
on running machines is [01 §5](../../docs/operations/01-deploy.md#5-rolling-deployment).

## Environment variables

### Read by the processes

**How secrets are read.** A variable that ends in `_FILE` names a file that holds a secret. It wins over the
variable without `_FILE`, which holds the value itself. A file that is named and cannot be read, or is empty, refuses
the configuration (exit 2). One trailing line ending is not part of the secret. In the table, "exit 2" means the
process refuses to start and systemd does not restart it. On the site, the secrets are plain variables at the end of
each settings file (D-102); the `_FILE` forms are for a drill or a container's secrets.

| Variable | Read by | Default | Meaning | Set in deploy |
|---|---|---|---|---|
| `BACKEND_DB_URL` | platform, worker, leaderboard rebuild | `jdbc:mysql://127.0.0.1:3314/backend_dev?useSSL=false&allowPublicKeyRetrieval=true`, the development MySQL's database (O-40) | The JDBC URL; both hosts, primary and replica, once a replica is made ([01 §9](../../docs/operations/01-deploy.md#9-mysql)) | `platform.env.example`, `worker.env.example` |
| `BACKEND_DB_USER` | same | `backend` | The database user | same |
| `BACKEND_DB_PASSWORD` | same | none: the development password, with a warning | The database password | `platform.env` and `worker.env`, at the end (commented in the examples), by the install guide |
| `BACKEND_DB_PASSWORD_FILE` | same | none | A file holding it, which wins over `BACKEND_DB_PASSWORD`; named but unreadable or empty, exit 2 | not set: for a drill or a container's secrets |
| `BACKEND_DB_POOL_SIZE` | same | 16 platform, 8 worker, 2 rebuild | The connection pool, 1 to 100; anything else, exit 2 | commented in `platform.env.example` and `worker.env.example` |
| `BACKEND_STORE_ADDRESSES` | arena, gateway, platform, worker, leaderboard rebuild | none: the store host and port the unit passes | The `session` store with its replica, `host:port,host:port`; used instead of the passed host and port. The process uses whichever is the primary and follows a promotion by itself | commented in the arena's, gateway's, platform's and worker's examples |
| `BACKEND_STORE_PASSWORD` | same | none: no password, with a warning; a store that demands one means exit 2 | The store's password | `platform.env`, `worker.env`, `gateway.env`, each `arena-<name>.env`, at the end, by the install guide |
| `BACKEND_STORE_PASSWORD_FILE` | same; also `backup-store.sh`, `store-restore-drill.sh` | none | A file holding it, which wins over `BACKEND_STORE_PASSWORD`; named but unreadable or empty, exit 2 | not set |
| `BACKEND_EVENTS_STORE` | arena, worker | none: results share the `session` store | The `events` store, `host:port`, or with its replica `host:port,host:port` | commented in `arena.env.example`, `worker.env.example` |
| `BACKEND_RESULT_RETENTION_HOURS` | arena, worker | 24 | How long the result stream keeps an entry, read or not: whole hours, 1 to 168, else exit 2. Set the same value on every arena and worker, since each add trims and the shortest wins. It sizes the `events` store, and it is the longest the workers or MySQL may be down before a result is lost ([D-83](../../docs/architecture/03-decision-log.md#d-83--the-result-streams-retention-is-a-setting-and-the-events-store-is-sized-by-a-table), [operations/04 §3.4](../../docs/operations/04-scaling-and-performance.md#34-j-redis)) | commented in `arena.env.example`, `worker.env.example` |
| `BACKEND_EVENTS_STORE_PASSWORD` | arena, worker | none: the session store's password | The `events` store's password, where it differs | commented in `arena.env.example`, `worker.env.example` ([01 §2](../../docs/operations/01-deploy.md#secrets)) |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE` | arena, worker | none | A file holding it, which wins | not set |
| `BACKEND_TRUSTED_PROXIES` | platform, gateway | none: loopback alone | The peers whose `X-Forwarded-For` is believed besides loopback, comma-separated IP literals: the front's nginx (D-82). Anything else, exit 2 | `10.0.0.1,10.0.0.2,10.0.0.3` |
| `BACKEND_ADVERTISE_PORT` | arena | none: the port it listens on | The port the arena announces, the one players dial: the site's 9001 behind the front. Not 1 to 65535, exit 2 | `arena.env.example`: `9001` |
| `BACKEND_ADVERTISE_TLS_NAME` | arena | none: its host | The name a client's TLS handshake gives and checks, which the front's nginx routes by. With it the arena announces TLS, though the arena itself speaks plaintext. Set, the arena is behind the front: every connection must begin with what nginx sends first, either its PROXY line (from 9001, D-105) or its `GET /match/<name>` request (from 80, D-115), which give the phone's address for the logs; a connection that begins with anything else is closed and counted, `proxy_line`. Not a host name, or set with a keystore, exit 2 | `arena.env.example`: `a1.arena.internal` |
| `BACKEND_METRICS_ADDR` | arena, gateway, platform, worker | none: no metrics server | `host:port` for `GET /metrics`; loopback by convention, not enforced; a port in use, exit 2 | platform `127.0.0.1:9101`, worker `9102`/`9112`, gateway `9103`, arena `9110` |
| `BACKEND_ADMIN_ADDR` | platform | none: no admin API | The admin API's `host:port`; not loopback, exit 2 | commented in `platform.env.example`: `127.0.0.1:9120` |
| `BACKEND_WATCH_TARGETS` | platform | none: the watch is off | The metrics of this machine's processes that the watch reads every 15 s: `job=host:port` pairs, comma-separated; `arena` and `worker` may repeat ([04 §12](../../docs/detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100), D-86). A pair not of that shape, or a port that is not one, exit 2 | `platform.env.example`: this machine's four (machine A's) |
| `BACKEND_WATCH_PEERS` | platform | none | The other machines' platforms, `name=host:port`, whose `/health` the watch reads over the private network; `PeerUnreachable` after two minutes without an answer | `platform.env.example`: `B=10.0.0.2:8080,C=10.0.0.3:8080` |
| `BACKEND_WATCH_DISKS` | platform | `/,/var/lib,/var/log,/opt/backend` | The paths whose file systems the watch reads, free space against size, for `DiskFilling` and `DiskFillsWithinAWeek` | commented |
| `BACKEND_WATCH_MACHINE` | platform | the host name | This machine's name on the alerts its watch raises | `platform.env.example`: `A` |
| `BACKEND_ALERT_WEBHOOK_URL` / `_FILE` | platform | none: alerts listed by the admin API and logged, sent nowhere | Where alerts are sent with `POST`, as JSON, when they fire or resolve ([08 §4](../../docs/operations/08-monitoring.md#4-where-alerts-go)); not an http(s) URL, exit 2. The owner's to name (Q-60) | commented |
| `BACKEND_ALERT_REPEAT_HOURS` | platform | 4 | How often a firing alert is sent again: whole hours, 1 to 24, else exit 2 | commented |
| `BACKEND_ADMIN_TOKEN` | platform | none; it or its file is required once `BACKEND_ADMIN_ADDR` is set, else exit 2 | The admin API's bearer secret (made with `head -c 32 /dev/urandom \| base64`) | commented in `platform.env.example`, at the end |
| `BACKEND_ADMIN_TOKEN_FILE` | platform | none | A file holding it, which wins | not set |
| `BACKEND_PAYMENT_PROVIDER` | platform | none: payments off, every payment route answers 503 `payments_off` | `simulated` only, which grants gems to whoever asks (development and drills, never where players are); any other value, exit 2 | commented in `platform.env.example` |
| `BACKEND_EDGE_CERTIFICATE` | gateway | none: no gauge | The certificate file nginx serves, reported as `backend_gateway_edge_certificate_expiry_timestamp_seconds` (nginx exports nothing itself) | `gateway.env.example` |
| `BACKEND_ARENA_TLS_KEYSTORE` | arena | none: plaintext, with a warning | The PKCS#12 keystore for match traffic, for an arena that ends TLS itself, without the front (a drill, or a machine alone reached directly; not the site's layout, D-82). Set with `BACKEND_ADVERTISE_TLS_NAME`, exit 2 | commented in `arena.env.example`: `/etc/backend/tls/arena-tls.p12`, which `install-certificate.sh` writes, readable by the `backend` group |
| `BACKEND_ARENA_TLS_PASSWORD_FILE` | arena | none; it or the password is required with a keystore, else exit 2 | The file with the keystore's password | commented in `arena.env.example`: `/etc/backend/tls/arena-tls-password`, written the same way |
| `BACKEND_ARENA_TLS_PASSWORD` | arena | none | The password itself | not set |

### Passed as arguments by the units

The units hand these variables to `main` as arguments, in a fixed order. systemd turns a variable that is missing
from the settings file into an empty argument, and an empty number means exit 2.

| Variable | Read by | Default in the code | In the example |
|---|---|---|---|
| `ARENA_PORT` | arena | 9001 | 9011 |
| `MAP_SIZE` | arena | 5700 | 5700 |
| `MAX_PLAYERS` | arena | 150 | 150 |
| `SHAPES` | arena | 1500 | 1500 |
| `MAX_ROOMS` | arena | 4 | 12 |
| `MATCH_SECONDS` | arena | 0, continuous | 0 |
| `ARENA_BIND` | arena; `install-certificate.sh` | `0.0.0.0` | `10.0.0.1` |
| `ADVERTISE_HOST` | arena; `install-certificate.sh` | `127.0.0.1` | `203.0.113.10` |
| `STORE_HOST` | arena, gateway, platform, worker, rebuild | `127.0.0.1` | `10.0.0.1` |
| `STORE_PORT` | same | 6379 | 6379 |
| `GATEWAY_BIND` | gateway | `127.0.0.1` | `10.0.0.1` |
| `GATEWAY_PORT` | gateway | 8081 | 8090 |
| `PLATFORM_URL` | gateway | `http://127.0.0.1:8080` | `http://10.0.0.1:8080` |
| `PLATFORM_BIND` | platform | `127.0.0.1` | `10.0.0.1` |
| `PLATFORM_PORT` | platform | 8080 | 8080 |

The gateway's default port in the code is 8081, but the shipped settings give 8090, and nginx's upstreams name 8090:
a gateway started without `GATEWAY_PORT` would not be the one nginx sends the lobby to.

The units also pass each instance's own values: the arena's name (`%i`; code default `arena-<port>`) and its spool
(`/var/lib/backend/arena-%i/spool`; code default `/var/lib/backend/spool/<name>`), the gateway's id (`%H`, the host
name; code default `gateway-<port>`) and the worker's id (`worker-%i`; code default `worker-1`).

### JVM properties the units set

| Property | Unit | Value | Why |
|---|---|---|---|
| `sun.net.httpserver.maxIdleConnections` | platform | 1000 (the JDK's default is 200) | Above nginx's keepalive pool, so the platform does not close a connection nginx is about to reuse. Measured: 63 failed requests in a 3 000-login storm at 200, none at 5 000 |
| `io.netty.leakDetection.level` | arena, store | `disabled` | Netty's sampling of buffers for leaks, off in production |
| `java.net.preferIPv4Stack` | store | `true` | The store's sockets on IPv4 |

`-Dlogback.configurationFile=/etc/backend/logback.xml` replaces the logging configuration without a rebuild
([01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine)); no unit sets it.

### Read by the scripts

Each script's own settings are in [scripts/README](../scripts/README.md). These are the ones the units set, or that
an operator sets by hand:

| Variable | Read by | Default | Set by |
|---|---|---|---|
| `BINLOG_CNF` | `binlog-stream.sh` | required | `backend-binlog-stream`: `/etc/backend/backup/mysql-binlog.cnf`, the backup user's (D-102) |
| `BINLOG_SERVER_ID` | `binlog-stream.sh` | 900 | not set |
| `MYSQL_CNF` | `backup-mysql.sh`, `backup-offsite.sh`, `record-backup.sh` | the client's defaults | `backend-mysql-backup`, `backend-backup-offsite`: `/etc/backend/backup/mysql-backup.cnf` |
| `BINLOG_DIR` | `backup-mysql.sh` | none: no binlog copy pruned | `backend-mysql-backup`: `/var/lib/backend/binlog` |
| `KEEP_DAYS` | `backup-mysql.sh` | 8 | `backend-mysql-backup`: 8 |
| `KEEP_DAYS`, `LOG_DIR`, `PID_FILE` | `rotate-nginx-logs.sh` | none | `backend-nginx-logs`: 14, `/var/log/backend-nginx`, `/run/backend-nginx/nginx.pid` |
| `RECORD` | `backup-mysql.sh`, `backup-offsite.sh` | 1 | not set |
| `RECORD_DB` | `record-backup.sh` | `backend` | `restore-proof.sh`: the source database |
| `OFFSITE_TARGET` | `backup-offsite.sh`, `fetch-offsite.sh` | none: nothing copied off the site | `/etc/backend/offsite.env`, made by the operator |
| `OFFSITE_KEY` | same | required with a target | the off-site units: `/etc/backend/backup/offsite.key` |
| `OFFSITE_SSH` | same | `ssh -o BatchMode=yes` | the off-site units: `ssh -i /etc/backend/backup/offsite-ssh -o UserKnownHostsFile=/etc/backend/offsite-known-hosts -o BatchMode=yes` |
| `ALL_DUMPS` | `fetch-offsite.sh` | unset: the newest dump only | by hand |
| `SOURCE_CNF` | `restore-proof.sh` (required), `restore-drill.sh` | the local server | the proof units: `/etc/backend/backup/mysql-backup.cnf` |
| `SCRATCH_CNF` | same | the local server | the proof units: `/etc/backend/backup/mysql-scratch.cnf` |
| `FROM_OFFSITE` | `restore-proof.sh` | 0 | `backend-restore-proof-offsite`: 1 |
| `PROOF`, `TO_COPIES_END` | `restore-drill.sh` | 0 | `restore-proof.sh` |
| `STOP_AT`, `NO_SOURCE` | `restore-drill.sh` | none, 0 | by hand ([runbook](../../docs/operations/02-runbook.md#recovering-to-a-moment)) |
| `JREDIS_CLI`, `JREDIS_SERVER` | `backup-store.sh`, `promote-store.sh`, `store-restore-drill.sh` | the release's `jredis/bin/j-redis-cli` and `jredis/bin/j-redis-server` beside `scripts/`, on its `runtime/` (`JAVA_HOME`, unless set); without a `jredis/` there, `/opt/j-redis/bin/j-redis-cli` and `/opt/j-redis/bin/j-redis-server` | by hand |
| `MYSQL_FAILOVER_CNF` | `promote-mysql.sh` | `/etc/backend/credentials/mysql-failover.cnf` | by hand |
| `BACKEND_DB_NAME`, `MYSQL`, `PROMOTE_STALE_SECONDS` | `promote-mysql.sh` | `backend`, `mysql`, 300 | by hand |
| `MVN`, `JDK` | `make-release.sh` | `mvn`, `vendor/jdk-21` (the JDK whose `jmods/` the runtime is linked from) | by hand; `build-offline.sh` sets `MVN` |
| `TLS_DIR`, `CREDENTIALS_DIR`, `CONFIG_DIR`, `NGINX_CONF`, `ARENAS_CONF`, `NGINX_TEST`, `NGINX_RELOAD`, `NGINX_RUNNING`, `EDGE_PORT`, `STREAM_PORT`, `CA_FILE`, `WARN_DAYS`, `RENEWED_LINEAGE` | `install-certificate.sh` | `/etc/backend/tls`, `/etc/backend/credentials`, `/etc/backend`, `/etc/backend/nginx/backend.conf`, `/etc/backend/nginx/backend-arenas.conf`, the release's `nginx -t -q`, `systemctl reload backend-nginx`, `systemctl is-active --quiet backend-nginx`, 443, 9001, `TLS_DIR/ca.pem` when it is there (the backend's root; else the system's roots), 14, none. Its header has each | not set; certbot would set `RENEWED_LINEAGE` |
| `ROOT_DAYS`, `DAYS` | `private-ca.sh` | 3650, 398 | how long the root and a certificate are valid ([06](../../docs/operations/06-certificates.md)) |

### Development, tests and drills

| Variable | Read by | Default |
|---|---|---|
| `JDBC_URL`, `DB_USER`, `DB_PASSWORD` | the persistence, worker and platform tests | `jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true`, `backend`, `backend-dev-password`: the development MySQL's (O-40); `dev-env.sh` sets `JDBC_URL` to it, on `MYSQL_PORT` |
| `BACKEND_BOT_PROFILE`, `BACKEND_BOT_STAYS`, `BACKEND_BOT_VIA`, `BACKEND_BOT_TRUSTSTORE`, `BACKEND_BOT_TRUSTSTORE_PASSWORD` | `tools/BotClient` | none ([01 §8](../../docs/operations/01-deploy.md#8-tls-for-match-traffic)) |
| `BACKEND_DRILL_SQL`, `BACKEND_ADMIN_URL`, `BACKEND_ADMIN_TOKEN` | the headless client | set by `client/headless-drill.sh` |
| `TMPDIR`, `SEED_DB`, `SEED_PASSWORD` | `backup-drill.sh` | `/tmp`, `backend_dev`, `backend-dev-password` |
| `RELEASE` | `backup-drill.sh` | none: the machine's `/usr/sbin/mysqld` and tools; given a release with `mysql/`, its MySQL 8.4 |
| `SKIP_TESTS` | `dev-jredis.sh build`, `dev-backend.sh build`, and so `build-offline.sh` | 0 (every test runs) |
| `WORK` | `dev-env.sh`, so every script that sources it | `work/` in the checkout: Maven's settings and local repository, `TMPDIR`, the development MySQL, a run by hand (D-80) |
| `MYSQL_PORT` | `dev-env.sh`, so every script that sources it | 3314, the development MySQL's |
| `STORE_PORT`, `PLATFORM_PORT`, `GATEWAY_PORT`, `ARENA_PORT` | `dev-env.sh`: `dev-jredis.sh`, `dev-backend.sh`, `dev-nginx.sh` | 6380, 8080, 8081, 9021, each on 127.0.0.1 (D-92) |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `NGINX_MATCH_PORT` | `dev-env.sh`: `dev-nginx.sh`, and `dev-backend.sh`, whose arena announces the last | 80 (the player API and the lobby in clear, D-107; matches by name too), 443 (the same over TLS), 9001 (matches over TLS), every IPv4 address; 80 and 443 need root (D-92) |
| `PLAIN` | `dev-env.sh`: `dev-nginx.sh`, `dev-backend.sh` | 0; `PLAIN=1` runs nginx and the arena without TLS, the API and the lobby on `NGINX_HTTP_PORT`, matches on `NGINX_MATCH_PORT`, for development alone (D-101) |
| `PUBLIC_ADDRESS`, `ARENA_TLS_NAME` | `dev-env.sh`: `dev-nginx.sh` (the certificate), `dev-backend.sh` (the arena's announcement) | the machine's first IPv4 address (`hostname -I`), else 127.0.0.1; `a1.arena.internal` (D-92) |
| `DEBUG` | `dev-backend.sh`, `dev-jredis.sh` | unset; `DEBUG=1` opens debugger ports 5005 to 5009 |
| `IMAGE` | `check-release-el9.sh`, `check-units-el9.sh`, `check-install-guide-el9.sh`, `check-two-machines-el9.sh`, `check-certificates.sh` | `registry.access.redhat.com/ubi9/ubi-minimal:9.5` for the first; `registry.access.redhat.com/ubi9/ubi-init:9.5` for the next three; none for `check-certificates.sh`, which then runs on this machine |
| `GUIDE_VALUES` | `check-install-guide-el9.sh` | none: values set after the install guide's own |
| `SAVE_A`, `A_IMAGE`, `STANDBY_ONLY` | `check-two-machines-el9.sh` | none ([scripts/README](../scripts/README.md#check-two-machines-el9sh)) |
| `UNITY_DATA` | `client/unity-env.sh`, so every script that sources it: `check-ports-el9.sh`, `check-two-machines-el9.sh`, `client/headless-drill.sh`, `client/core-tests.sh`, the client's checks | `work/unity/2019.4.28f1/Editor/Data`: Unity 2019.4.28f1's Linux editor, fetched there once, whose compiler and Mono build and run the C# client (D-116). Nothing reads `DOTNET` since D-116 |
| `EL9_SSH_PORT` | `check-selinux-el9.sh`, `check-release-guest-el9.sh` | 2223, 2224 |
| `CA`, `IC`, `PM`, `PS`, `ROTATE`, `PORT` | `check-certificates.sh`, `check-promote.sh`, `check-nginx-logs.sh` | none, for a mutation check; `PORT` 18080 |
| `RELEASE`, `JREDIS_JAR`, `JREDIS_CLI_JAR`, `TOOLS_JAR`, `JAVA`, `MYSQLD_BIN`, `TLS`, `FAILOVER`, `MYSQL_FAILOVER`, `BOTS`, `ARENA_OPTS`, `SOAK`, `STAYS`, `SAMPLE_EVERY`, `JUDGE_FROM`, `WORKERS`, `TAKEOVER`, `DRAIN_AFTER`, `WATCH`, `IDLEGUEST`, `SIMPLE`, `FRONT`, `NEWMAN`, `BACKEND_DB_PASSWORD` | `client/headless-drill.sh` | its header |

## Ports

### Production

Who connects to each port, the firewall on each machine, and what must change together when a port changes:
[operations/05](../../docs/operations/05-ports-and-firewall.md).

| Port | Listener | Bound to | Where |
|---|---|---|---|
| 443 | nginx: HTTPS, the API and the lobby, to all three machines' platforms and gateways | every address, IPv4 and IPv6 | A, B, C; reached on the front alone (D-82) |
| 80 | nginx in clear, for a client without TLS: 443's server with the same locations, and `/match/<name>` to the arena of that name (D-115) | every address, IPv4 and IPv6 | A, B, C; reached on the front alone |
| 9001 | nginx's `stream`: match traffic's TLS ended, each connection passed to the arena its TLS name gives | every address, IPv4 and IPv6 | A, B, C; reached on the front alone |
| 8080 | platform (`/v1/` behind nginx; the gateway calls it too) | the machine's private address | A, B, C |
| 8090 | gateway (`/lobby` behind nginx) | the machine's private address | A, B, C |
| 9011, 9012, 9013 | the arenas, one port each ([05 §3](../../docs/operations/05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)), plaintext behind nginx | the machine's private address | A (2), B (3), C (2) |
| 9120 | platform's admin API, when it is named | loopback, enforced | A, B, C |
| 9101 | platform's metrics | `127.0.0.1` | A, B, C |
| 9103 | gateway's metrics | `127.0.0.1` | A, B, C |
| 9110, 9111, 9113 | the arenas' metrics, one each (9112 is skipped: it is a worker's on C) | `127.0.0.1` | A, B, C |
| 9102, 9112 | workers' metrics (`c1`, `c2`) | `127.0.0.1` | C |
| 6379 | j-redis `session`: the primary on A, the replica on B | `127.0.0.1` and the machine's private address ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) | A, B |
| 6380 | j-redis `events`: the primary on B, the replica on C | the same | B, C |
| 3306 | MySQL: the primary on A, the replica on C; TLS required | the private address (`10.0.0.1`, `10.0.0.3`); `mysqlx = OFF`, so nothing on 33060 | A, C |
| 3309 | the scratch MySQL server the proofs restore onto | `127.0.0.1`; `mysqlx=OFF` | C |

**Open to the internet:** the front's 80, 443 and 9001 alone, 80 in clear (D-115). SSH is reached from the internal
network alone. Cockpit's 9090, which RHEL allows by default, is closed
([05 §5](../../docs/operations/05-ports-and-firewall.md#5-the-firewall-on-rhel-9)).

**Between the machines:** the front's nginx reaches every platform, gateway and arena. C streams binlogs from A's
3306, and C's replica replicates and clones from it. Each store's replica connects to its primary's port. The copy
off the site leaves C by SSH, to the host the owner names.

**On the launch's one machine** (D-93) every port above is on that machine, with three arenas (9011 to 9013, their
metrics 9110, 9111 and 9113) and two workers (9102, 9112). Everything but nginx's 80, 443 and 9001, and SSH, is on
`127.0.0.1` alone, MySQL and both stores too
([05 §2](../../docs/operations/05-ports-and-firewall.md#on-the-launchs-one-machine)). Its firewall opens 80, 443 and
9001, and SSH to the internal network ([install guide §12](../../docs/operations/03-install-guide.md#12-open-the-firewall)).

### Drills and development

| Port | What | Used by |
|---|---|---|
| 6390 | j-redis | `client/headless-drill.sh` |
| 6392 | j-redis replica (`FAILOVER=1`) | `client/headless-drill.sh` |
| 8093 | platform | `client/headless-drill.sh` |
| 8094 | gateway | `client/headless-drill.sh` |
| 9011 | arena | `client/headless-drill.sh` |
| 9195, 9196 | platform's metrics, its admin API | `client/headless-drill.sh` |
| 9197, 9198, 9199 | the arena's, the gateway's and the worker's metrics | `client/headless-drill.sh` |
| 9193 | a second worker's metrics (`IDLEGUEST=1`) | `client/headless-drill.sh` |
| 9194 | the alerts' webhook receiver (`WATCH=1`) | `client/headless-drill.sh` |
| 8095, 8096, 9095 | the release's nginx in front of the drill (`FRONT=`), in the places of 80, 443 and 9001 | `client/headless-drill.sh` |
| 3307, 3308 | MySQL primary and replica (`MYSQL_FAILOVER`) | `client/headless-drill.sh` |
| 3307, 3309 | MySQL primary and scratch server | `backup-drill.sh` (not at the same time as `MYSQL_FAILOVER`) |
| 3314 | the development MySQL, `vendor/`'s 8.4 (`backend_test`, `backend_dev`) | `dev-mysql.sh`: the tests, the benchmarks, `run-local.sh` and both drills, which start it when it is not running; the processes' and the tests' built-in defaults (D-80, O-40). Nothing here uses a machine's own 3306 |
| 6380, 8080, 8081, 9021 | j-redis, platform, gateway, arena | `dev-jredis.sh`, `dev-backend.sh` (D-92) |
| 6380, 8080, 8081, 9001 | the same, in the by-hand example of [backend/README](../README.md#running-it), with no nginx | that example |
| 80, 443, 9001 | the release's nginx in front of a run by hand, on every IPv4 address: the API and the lobby in clear and over TLS, matches | `dev-nginx.sh` (D-92, D-107); with `PLAIN=1`, 80 and 9001 alone, without TLS (D-101) |
| 5005 to 5009 | the debugger, one port per process | `DEBUG=1 ./run-local.sh start` |
| 18080 | the release's nginx, on loopback | `check-nginx-logs.sh` (`PORT`) |

All of these bind `127.0.0.1`, except the nginx of `dev-nginx.sh` and of the drill's `FRONT=`, which bind every IPv4
address. Ports 6381 and 8082 belong to other processes on the development machine.

## Timers

All five timers are `Persistent=true`: a run missed while the machine was down happens at the next start.

| Timer | Starts | Schedule |
|---|---|---|
| `backend-nginx-logs.timer` | `backend-nginx-logs.service`: nginx's logs rotated, 14 days kept (D-85) | 00:05 UTC every day, at a random moment within the 5 minutes after (`RandomizedDelaySec=5min`), so the machines do not rotate in step; on every machine |
| `backend-mysql-backup.timer` | `backend-mysql-backup.service`: the nightly dump | 03:30 UTC every day, within the 15 minutes after (`RandomizedDelaySec=15min`) |
| `backend-backup-offsite.timer` | `backend-backup-offsite.service`: the copy off the site | hourly, within the 5 minutes after; enabled once the owner names the host (Q-53) |
| `backend-restore-proof.timer` | `backend-restore-proof.service`: the weekly proof | Sundays at 04:00 UTC |
| `backend-restore-proof-offsite.timer` | `backend-restore-proof-offsite.service`: the proof from off the site | the first Sunday of each month (`Sun *-*-01..07`) at 05:00 UTC; enabled with the copy off the site |

The binlog stream runs all the time (`backend-binlog-stream.service`). Nothing copies a store on a timer (D-31):
each store has its replica. The certificate is renewed by hand once a year, from the backend's root
([06 §6](../../docs/operations/06-certificates.md#6-renewing-every-year)).
