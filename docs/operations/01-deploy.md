# Deployment

How the system is installed and rolled out across the three machines.

**Status: runnable, not yet run on a production machine.** The release build,
the systemd units and the nginx configuration are written: `backend/deploy/`,
file by file in [its README](../../backend/deploy/README.md), which also holds
the catalogue of every setting, port and timer; and `backend/scripts/`, script
by script in [theirs](../../backend/scripts/README.md). The four process units
and the release have been run for real, sandboxed, with bots and MySQL (§7),
and so have arena TLS (§8), MySQL's own setup with TLS (§9), the binlog stream
and the dump under systemd (§10), the scratch server's unit (§10), and nginx
with a certificate from placeholder to renewal (§4, §11). The proofs and the
copy off the site are drilled as scripts (`backup-drill.sh`), not yet as
units. What remains is listed in [§6](#6-still-to-be-written): the domain and
the CA. The pictures are in
[diagrams/07](../diagrams/07-deploy-and-operations.md).

## 1. What runs where

The allocation is in
[architecture/01 §2](../architecture/01-system-topology.md#2-machine-topology).
Summary of the stateful roles, because those are the ones that constrain a
rollout:

| Role | Machine | Notes |
|---|---|---|
| MySQL primary | A | replica on C |
| j-redis `session` primary | A | replica on B |
| j-redis `events` primary | B | replica on C |
| nginx, gateway, platform | all three | stateless, interchangeable |
| arena | all three | stateful only for the lifetime of a match |
| worker | C | stateless; what it has in flight stays pending for it in the result stream, and another worker takes it over after a minute |
| backups: the binlog stream, the dumps, the proofs and their scratch server, the copy off the site | C, the MySQL replica's machine | never the primary's machine; they move with the replica after a failover ([runbook §2](02-runbook.md#mysql-built-2026-09-29-d-35)) |

The two store primaries are on different machines, each replicated to another
([D-34](../architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)),
so losing one machine costs at most one primary, and its replica is promoted.

## 2. Conventions

These apply to every process type and are settled:

- **Configuration is by name**, in `/etc/backend/<process>.env` (examples in
  `deploy/env/`): every port, host, size and name an operator sets is a named
  variable there. The units hand some of them to `main` as positional
  arguments; that order is the release's own, fixed in the unit it ships, and
  not something to edit.
- **One systemd unit per process instance**: `backend-arena@<name>` and
  `backend-worker@<id>`, and one `backend-platform` and one `backend-gateway`
  per machine. **Both instance names are unique across the fleet**, not only
  on their machine. The arena's is its name in the store: its directory entry
  (`arena:<name>`) and its command channel (`arena-admin:<name>`). Two
  `backend-arena@a1` on two machines overwrite each other's entry, and a stop
  of one withdraws the other's. The worker's is its consumer in the result
  stream's group (`worker-<id>`). Prefixing the machine's letter keeps them
  apart: arenas `a1`, `a2` on A, `b1` to `b3` on B, `c1`, `c2` on C, and
  `backend-worker@c1`, `@c2`.
- **Versioned directories with a symlink**: `/opt/backend-<version>/` with
  `/opt/backend` pointing at the current one, so a rollback is a symlink swap.
  This is the layout j-redis already uses.
- **Configuration outside the release**: `/etc/backend/` holds config; the release
  directory is read-only and replaceable.
- **State outside the release**: `/var/lib/backend/` for data and spool files,
  `/var/log/backend/` for GC logs and heap dumps, both created by systemd's
  `StateDirectory=` and `LogsDirectory=`.
- **CPU pinning with `CPUAffinity=` in a drop-in** (§7), following the core budget in
  [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model).
- **Exit codes distinguish restartable from human-needed failures**, as j-redis
  does: `RestartPreventExitStatus` for configuration and data errors, restart
  for everything else. `platform`, `worker`, `gateway` and `arena` exit **2**
  when they refuse their configuration — the old argument shape, a credential
  file that cannot be read, a store that demands a password they were not
  given — and **1** when anything else fails while starting: an unreachable
  database, a wrong database password, a port in use. Before
  2026-09-25 a failure while starting left the process running with nothing
  served, because the store client's threads are not daemons, and a unit in
  that state is never restarted.

### Secrets

The database password is never a command-line argument: every local user can
read those with `ps`, and a process given one in the old argument positions
exits 2 and says so. It comes from the environment
(`persistence/DatabaseSettings`):

| Variable | |
|---|---|
| `BACKEND_DB_URL` | JDBC URL. Default: the development database on 127.0.0.1 |
| `BACKEND_DB_USER` | Default `backend` |
| `BACKEND_DB_PASSWORD_FILE` | **Preferred.** A file holding the password; one trailing line ending is ignored. Named but unreadable or empty stops the process: no fallback |
| `BACKEND_DB_PASSWORD` | Accepted. Readable by the process owner and root only, but shown to any user by `systemctl show` if set with `Environment=` |
| `BACKEND_DB_POOL_SIZE` | The process's connection pool, 1 to 100: size it for the database host, about its cores × 2 ([06 §7](../detailed-design/06-persistence-mysql.md#7-connection-pooling)). Default 16 for `platform`, 8 for `worker`, 2 for the rebuild; anything else refuses the configuration |
| neither | The development password, with a warning on stderr |

In a unit, the file comes from `LoadCredential=` (systemd 247+), which copies
it somewhere only the service can read:

```ini
[Service]
User=backend
LoadCredential=db-password:/etc/backend/credentials/db-password
# The whole URL of §9: without the trust store, VERIFY_IDENTITY cannot connect.
Environment=BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12
Environment=BACKEND_DB_USER=backend
# %d is the credentials directory on systemd 250+. On 249 (Ubuntu 22.04), write
# the path out: /run/credentials/backend-platform.service/db-password
Environment=BACKEND_DB_PASSWORD_FILE=%d/db-password
RestartPreventExitStatus=2 243
```

**A credential reaches a service that is not root through an ACL** systemd sets on
it. Checked 2026-10-05 on systemd 255 with `User=nobody`; RHEL 9 has 252, which
does it the same way, but no RHEL host has run it here: in a container systemd
cannot mount the credentials' private directory, which stays empty. The first
start on a RHEL machine is where to look if a unit exits 2 saying it cannot read
`…/credentials/…/db-password`.

The **store password** follows the same rules, as `BACKEND_STORE_PASSWORD_FILE`
(or `BACKEND_STORE_PASSWORD`), for all four process types: production j-redis
runs with `requirepass`, as its own install notes say. Until 2026-09-26 no
process sent one ([S-8](../defects.md#5-security-and-input)). A process given
none, facing a store that wants one, exits 2 at start-up with a message naming
the variable. A **wrong** password cannot be told from a store that is not up
yet, so the process runs and the client logs `authentication … failed:
WRONGPASS` while retrying slowly; that line is the thing to look for. A store
that is simply not reachable does not stop a process: an arena runs and
spools while the store is down.

```ini
LoadCredential=store-password:/etc/backend/credentials/store-password
Environment=BACKEND_STORE_PASSWORD_FILE=%d/store-password
```

The **`events` store's password** is the session store's unless
`BACKEND_EVENTS_STORE_PASSWORD_FILE` (or `BACKEND_EVENTS_STORE_PASSWORD`)
names another; the arena and the worker read it. No shipped unit loads one.
Where the two stores' passwords differ, a drop-in on `backend-arena@` and
`backend-worker@` adds it the same way:

```ini
[Service]
LoadCredential=events-store-password:/etc/backend/credentials/events-store-password
Environment=BACKEND_EVENTS_STORE_PASSWORD_FILE=%d/events-store-password
```

The platform's other settings, in `platform.env` or a drop-in:

| Variable | |
|---|---|
| `BACKEND_ADMIN_ADDR` | The admin API's `host:port` ([runbook §3a](02-runbook.md#3a-the-admin-api)), `127.0.0.1:9120` in the example. Unset: no admin API. An address that is not loopback refuses the configuration (exit 2) |
| `BACKEND_ADMIN_TOKEN_FILE` | The admin API's bearer secret, delivered by `deploy/systemd/platform-admin.conf.example` with `LoadCredential`; `BACKEND_ADMIN_TOKEN` is accepted by the same rules as the passwords. Required once `BACKEND_ADMIN_ADDR` is set: without it the platform refuses to start (exit 2) |
| `BACKEND_PAYMENT_PROVIDER` | Unset or blank: nothing is sold, and every payment route answers 503 `payments_off`. `simulated`: the simulated provider, which grants gems to whoever asks, for development and drills only (D-68). Any other value refuses the configuration (exit 2) |

Every variable a process reads, with its default and where the shipped files
set it, is in the [deploy README's catalogue](../../backend/deploy/README.md#environment-variables).

`/etc/backend/credentials/` is `root:root 0700`, the files `0600`. **The
development URL's `useSSL=false&allowPublicKeyRetrieval=true` must not reach
production:** without TLS, key retrieval lets anyone in the path hand the
driver their own RSA key and read the password it encrypts with it
([S-6](../defects.md#5-security-and-input)).

### JVM options

Settled by [D-2](../architecture/03-decision-log.md#d-2--java-21-with-generational-zgc), and verified on Temurin 21:

```
-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch
--add-opens java.base/java.nio=ALL-UNNAMED
-Xlog:gc*:file=/var/log/backend/platform-gc.log:time,uptime:filecount=5,filesize=20M
```

Each unit names its own GC log in `/var/log/backend/`: `platform-gc.log`,
`gateway-gc.log`, `arena-<name>-gc.log`, `worker-<id>-gc.log`.

Two traps, both confirmed by running them:

- **Java 8 GC logging flags stop a JDK 21 JVM from starting.** `-Xloggc`,
  `-XX:+PrintGCDetails`, `-XX:+PrintGCDateStamps` and
  `-XX:+UseGCLogFileRotation` were removed in JDK 9+; the JVM exits with
  `Unrecognized VM option 'PrintGCDateStamps'` and writes no application log at
  all, so the failure looks like the application rather than the flags.
- **`--add-opens` belongs outside any overridable options variable.** Without
  it Netty loses its direct-buffer fast path; if it lives inside a variable an
  operator can override, tuning the heap silently drops it.

## 3. j-redis (built and tested)

Two instances from the same distribution, different ports and data directories
([D-7](../architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)).
Every process takes the `session` instance as its store; the arena and worker
also take `BACKEND_EVENTS_STORE`, for the result queue. Leave it unset to run
both on one instance.
**The release carries the store** (D-77, 09 §4): `backend-store@session` and
`backend-store@events` run `/opt/backend/jredis/lib/j-redis-server.jar` on the
release's runtime, as the user `jredis`; each instance's configuration is
`/etc/backend/store-<name>.conf`, from the release's `jredis/conf/j-redis.conf`
(its `dir` `/var/lib/backend-store/<name>`, which the unit makes private), and
its heap `/etc/backend/store-<name>.env` (`env/store.env.example`). The store's
own settings, its backup and its upgrade are
[j-redis guide 4](../../j-redis-service/docs/guide/04-run-and-operate.md)'s.

Two things to carry over when sizing:

- `maxmemory` must account for ZGC making the same dataset estimate
  **25–50 % larger** than it did under Java 8
  ([j-redis docs/06 §6](../../j-redis-service/docs/06-expiry-and-memory.md#6-jvm-heap-sizing)).
- Run from an unpacked distribution, never from a build's `target/` directory:
  rebuilding replaces the jar under a running server.

**Each store listens on loopback and on its private address**, both on one
`bind` line: `bind 127.0.0.1 10.0.0.1` for `session` on A, `bind 127.0.0.1
10.0.0.2` for `session`'s replica and for `events` on B, and `bind 127.0.0.1
10.0.0.3` for `events`' replica on C. The processes on the other machines
reach a store on its private address. The scripts run on the store's own
machine reach it on `127.0.0.1` and take no host: `promote-store.sh`,
`backup-store.sh`, `store-restore-drill.sh`, and the runbook's dead-list
commands. j-redis's own default, `bind 127.0.0.1` alone, reaches no other
machine; the private address alone leaves every one of those scripts unable to
reach it.

### Replicas (designed 2026-09-29, plan item 12)

Each store has a replica on another machine (§1, D-34), configured as j-redis
guide 4 §4.12 describes: `replicaof <primary> <port>` and `primaryauth` in the
replica's configuration, the same `requirepass` on both. Every process is given
both addresses:

| Setting | Holds |
|---|---|
| `BACKEND_STORE_ADDRESSES` | The `session` store, `host:port,host:port`: primary and replica, in any order. Set, it is used instead of the host and port on the command line. `STORE_HOST` and `STORE_PORT` stay set in the env file all the same: the units still pass them, and an empty port refuses the configuration (exit 2), which systemd does not restart |
| `BACKEND_EVENTS_STORE` | The `events` store, as before, now also `host:port,host:port` |
| `BACKEND_EVENTS_STORE_PASSWORD_FILE` | The `events` store's password, only where it differs from the session store's (§2) |

The client asks each address for its role and uses the primary with the highest
epoch; after a promotion it finds the new primary within a reconnect, and it
never goes back to a primary with a lower epoch.

**Promoting** is `scripts/promote-store.sh`, on the replica's machine, with the
store's configuration file and the old primary's address. It will not promote
while the old primary answers, unless told to demote it first; it promotes,
removes `replicaof` from the file (keeping a copy), and prints the new role and
epoch. The runbook has when to run it ([02 §2](02-runbook.md#2-stateful-primary-failure)).

**The arena and the `events` replica**
([Q-11](../requirements/01-scope-and-nfrs.md#7-open-questions)): after adding a
result, the arena waits up to 100 ms for the replica to hold it, counts the
results it did not confirm (`backend_arena_results_unreplicated_total`), and
lets the spool file go either way.

## 4. nginx

Terminates HTTPS for the lobby and the API only. **Match traffic never passes
through it** ([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)) —
arena connections go direct, with TLS terminated in Netty.

One nginx per machine, all three advertised to the client, which retries the
next endpoint on failure. No VIP and no keepalived
([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip)).

**The release's own nginx** (D-77, [09 §5](../detailed-design/09-release-and-packaging.md#5-nginx-compiled-for-rhel-9)):
1.30.5, compiled for RHEL 9 with OpenSSL 3.5.9 built in, run by
`backend-nginx.service` from `/opt/backend/nginx`, not the distribution's
package. Checked 2026-10-05 in UBI 9.5 with nothing installed: proxying to the
platform over HTTP and HTTPS (TLS 1.3), and `nginx -t` on the shipped
configuration.

**The configuration is `backend/deploy/nginx/`**, installed to
`/etc/backend/nginx/` (the release's `nginx-conf/`): `nginx.conf`, the main file,
which the distribution's package used to bring; `backend.conf`, the servers; and
the `backend-platform.conf` snippet. Its numbers answer to numbers in the code,
and the file says which:

| nginx | because |
|---|---|
| lobby `proxy_read_timeout 150s` | the gateway closes a silent socket at 120 s; the gateway should decide, not nginx |
| platform upstream `keepalive 32`, `keepalive_timeout 20s` | the platform drops idle connections at 30 s and keeps at most 1 000. The other way round, it closed connections nginx was about to reuse, and nginx does not retry a POST: a 502. Measured on the JDK server, 63 of 16 806 requests failed at its default idle limit of 200 and none at 5 000 |
| `limit_req` 2/s, burst 30 on the four that make or open an account (`/v1/sessions`, `/v1/accounts`, `/v1/guests`, `/v1/accounts/upgrade`, each matched exactly, as platform matches them: S-18) | a cheap outer guard in front of Java; the precise per-address and per-account limits are the platform's ([04 §1](../detailed-design/04-platform-services.md#1-auth-and-sessions)) |
| `client_max_body_size 4k` | the platform reads at most 4 KiB |
| `X-Forwarded-For $proxy_add_x_forwarded_for` | the platform believes the last entry, from a peer on this machine only |
| port 80: the CA's challenge, 403 for the rest | no redirect: a login sent to `http://` has already crossed in the clear, and a client that follows a 301 turns the POST into a GET. A misconfigured client should fail in testing |
| TLS 1.3 and 1.2, ECDHE with AES-GCM or ChaCha20 only | the same versions as the arenas (§8); nothing without forward secrecy |
| `ssl_session_tickets off` | nginx never rotates the ticket key, so tickets would undo forward secrecy until the next restart; resumption still works from the session cache (checked: `Reused`) |

A location that sets any `proxy_set_header` inherits none from the server
block, so the lobby location sets all four headers it needs. No HSTS header:
only browsers read it, and there is no browser client
([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build)).

**Run for real on 2026-09-26** with nginx 1.24, in front of the release, with a
test CA shaped like a public one (a root and an intermediate): `nginx -t`
passed; logins and the lobby were served with the chain verified; 10 bots went
through it into a TLS arena trusting only the root; TLS 1.2 negotiated
`ECDHE-ECDSA-AES256-GCM-SHA384` and refused a client offering only
non-forward-secret suites. The certificate is §11.

## 5. Rolling deployment

On each machine the new release is unpacked beside the running one and
switched to by the symlink (§2): `tar -xzf backend-<version>.tar.gz -C /opt`;
any unit, drop-in or nginx file that changed is copied from the release's
`systemd/` and `nginx-conf/` and followed by `systemctl daemon-reload` (and
`systemctl reload backend-nginx`, which tests the configuration first); then
`ln -sfn /opt/backend-<version> /opt/backend`. The release carries its runtime,
MySQL, nginx and j-redis too (09 §4): a new version of one is a new release,
restarted as below, MySQL last and only when it changed. A process runs the release it started from until it is
restarted, so the switch changes nothing by itself. The restarts go machine by
machine, in this order.

The ordering follows from the availability tiers in
[architecture/02 §2](../architecture/02-availability.md#2-degradation-is-graded-not-binary):

1. `worker` — invisible to players; the queue in j-redis holds its place.
2. `platform`, one instance at a time — in-flight requests fail and are retried.
3. `gateway`, one at a time — clients reconnect to another endpoint.
4. `arena`, one at a time, **drained**: stop accepting joins, let finite matches
   end, then stop. This is the slow step, and the reason `TimeoutStopSec` is
   generous: twelve minutes, for eleven of drain
   ([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)).
5. Stateful roles (MySQL, j-redis) only during a maintenance window, since
   promotion is manual
   ([D-8](../architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

Flyway migrates the schema at every `worker` and `platform` start (§9), so the
first worker restarted applies a release's migrations while the platforms of
the release before still run on them. That is why migrations are forward-only,
expand then contract (06 §8). A rollback is the symlink put back and the same
restarts; the schema stays where the release took it. Check each step as §7
says: `curl http://127.0.0.1:8080/health` on the machine, the journal, the
metrics.

**The release that puts results on a stream** (2026-09-29, plan item 10) needs
the `events` j-redis on 2.1.0 before any arena or worker of it starts: a 2.0
store refuses the stream commands. Upgrade it first, in its window
([j-redis guide 7](../../j-redis-service/docs/guide/07-upgrade-guide.md)); its
files carry over. Then the order above. A mixed fleet loses nothing: an arena
of the old release pushes to the list, which new workers drain into the stream
as an inbox, and a new arena's results wait in the stream, kept 24 hours, for
the new workers.

**The release on j-redis 2.2.1** (2026-10-01, plan item 50): both stores'
servers first, each in its window, replica before primary; then the order
above. A 2.2.1 server closes its subscribers when it is made a replica, so that
they look for the primary again, and a 2.2.1 client, in the release, pings its
subscription so that a lost machine is noticed in seconds (03 §5). Either half
without the other is no worse than 2.2.0; the files carry over.

## 6. Still to be written

| Item | Blocked by |
|---|---|
| The certificate, for nginx and the arenas | Two choices: the domain and the CA ([S-6](../defects.md#5-security-and-input)). Obtaining, checking, installing, renewing and watching it are built (§11) |

**No longer open: the store's copies off its machine.**
[D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)
put no scheduled copy before replication, since a copy on a timer recovers
nothing a store loss costs: the boards come back from MySQL
(`backend-leaderboard-rebuild`, [05 §8](../detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss)),
sessions and tickets are disposable, the results in flight are younger than
any copy, and the dead list is copied out when it alerts
([runbook §3](02-runbook.md#3-per-component-procedures)). Replication is built
(§3, D-34): each store has a replica on another machine, which holds the
results in flight too. `backup-store.sh` stays, for a copy by hand before an
upgrade.

## 7. Installing a machine

**Step by step, from a clone of the repository: the
[install guide](03-install-guide.md).** This section is the reasons and the
record of what was checked.

Built and run for real on 2026-09-26: the release, and the four units
**as shipped**, installed as runtime units on the development machine. Only the
paths, the heap sizes and the absent `backend` user differed. All four came up
under their sandbox, including the arena's native epoll transport; 20 bots
played through nginx-less loopback; 20 results reached MySQL; all four stopped
cleanly with nothing in their journals. `systemd-analyze security` rates each
unit **3.1 (OK)**, down from 9.0 before the sandbox.

1. **The release.** `backend/scripts/make-release.sh` builds
   `backend-<version>/`: one `lib/<process>/` per process type, not one shared.
   `handoff`'s module graph is what keeps the MySQL driver off an arena, and a
   shared directory would put it back (checked: none in `lib/arena` or
   `lib/gateway`). Unpack to `/opt/backend-<version>` and point `/opt/backend`
   at it (§2).
   Nothing else is installed: the release carries the Java runtime the
   processes run on, MySQL, nginx and j-redis (09 §4). What it takes from the
   system is a RHEL 9 server installation's: glibc, `libstdc++`,
   `ncurses-libs`, systemd, util-linux, `tar`, `gzip`, `findutils`,
   `procps-ng`, OpenSSL's command line, and on the backup machine
   `openssh-clients` and `rsync` (09 §4.2). `backend/scripts/check-release-el9.sh`
   runs a release in RHEL 9's userspace with nothing else installed (09 §7).
2. **The users.** `useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin backend`,
   and the same for `nginx` on every machine, `jredis` where a store runs, and
   `mysql` (its home `/var/lib/backend-mysql`) on the database's machines (§9).
3. **Credentials.** `/etc/backend/credentials/`, `root:root 0700`, holding
   `db-password` and `store-password`, each `0600` (§2, Secrets). The units
   deliver them with `LoadCredential`, so no process reads the secrets from
   `/etc`. They do read `/etc/backend/tls/`: `platform` and `worker` the MySQL
   CA (§9), the gateway nginx's certificate for its expiry metric (§11). Create
   it, `install -d -m 0755 /etc/backend/tls`, and keep `/etc/backend`
   traversable by `backend`.
4. **Settings.** `/etc/backend/<process>.env` from `deploy/env/*.env.example`,
   with no secrets in them. One `arena-<name>.env` per arena instance, each with
   its own port, and one `worker-<id>.env` per worker instance
   (`worker-instance.env.example`), with its metrics port: read after
   `worker.env`, so it wins. A drop-in's `Environment=` would not, as a file's
   settings override it; checked with systemd 255.
5. **Units.** Copy the release's `systemd/*.service` to `/etc/systemd/system/`, then
   `systemctl daemon-reload` and enable: `backend-platform`, `backend-gateway`,
   `backend-worker@<id>` and `backend-arena@<name>`, each id and name unique
   across the fleet and stable (§2). Pin each arena to its own cores with a drop-in
   (`CPUAffinity=`), since the core ranges belong to the machine. The backup
   machine's units and timers are §10's.
6. **nginx and the certificate.** The release's `nginx-conf/*.conf` into
   `/etc/backend/nginx/`, the machine's name filled in, then §11; enable
   `backend-nginx`.
7. **Check.** `curl http://127.0.0.1:8080/health` on the machine; the journal of
   each unit (`journalctl -u backend-arena@a1`) shows INFO lines and no ERROR.

**Metrics** are served in the Prometheus text format at `GET /metrics`, on the
loopback address in `BACKEND_METRICS_ADDR` (unset: no server, so a process run
by hand takes no port). The env examples use 9101 platform, 9102 and 9112 the two workers, 9103
gateway, 9110 onwards arenas. There's no library behind it, by choice: the
format is a few lines (`common/Metrics`), and Micrometer, although it is in the
offline bundle, would add a dependency for nothing. Every process reports
`backend_process_uptime_seconds`, `backend_jvm_*` and `backend_store_*`
(timeouts, fail-fasts, reconnects, connected, server errors); with
`BACKEND_EVENTS_STORE` set, the same again as `backend_events_store_*`. The rest, from each design's "what to measure" table:

| Process | Metrics |
|---|---|
| arena | `players`, `rooms`, `rooms_failed_total`, `tick_p99_seconds{room}` (last 10 s), `tick_overruns_total`, `snapshots_sent_total`, `snapshots_skipped_total`, `results_published_total`, `results_spooled_total`, `results_unreplicated_total` (not confirmed by the events replica within 100 ms, Q-11), `spool_failures_total`, `connections_dropped_total{reason}` (join_deadline, idle, stalled, tls_handshake), `clients{profile}`, `snapshot_bytes_total{profile}`, `connection_bytes_per_second{profile}` (a histogram, each connection's own rate, [02 §13](../detailed-design/02-networking.md#13-what-to-measure)), `snapshots_held_total`, `profile_steps_total{direction}` ([02 §8](../detailed-design/02-networking.md#8-traffic-profiles)), `stays_waiting`, `resumes_total`, `stays_expired_total` ([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)), `joins_total{outcome}` (joined, bad_ticket, no_room, claim_failed, removed for a player an operator took out within a ticket's life; against platform's tickets issued, the joins that never arrived), and with TLS `tls_certificate_expiry_timestamp_seconds` |
| platform | `responses_total{status}`, `request_seconds{route}` (a histogram), `logins_total{outcome}` (ok, invalid_credentials, banned, throttled, busy), `purchases_total{outcome}` (bought, already_bought, and each refusal), `hasher_line` (168 is full), `open_tickets_issued_total`; from the matcher, `matches_made_total{mode}`, `queue_wait_seconds{mode}` (a histogram), `queue_players{mode}`, `confirms_total{outcome}` (made, declined, withdrawn, lapsed, no_room) and `match_tickets_issued_total{mode}` ([04 §11](../detailed-design/04-platform-services.md#11-what-to-measure)); `pushes_unheard_total` (a push to a registered player that no gateway heard, 03 §5) |
| gateway | `connections`, `authenticated_total`, `errors_total{code}`, `pushes_total{outcome}` (delivered, not_here, malformed, broadcast for a notice to every lobby; held and dropped for a slow connection), `slow_closed_total` ([03 §8](../detailed-design/03-gateway.md#8-backpressure-and-slow-clients)), `platform_seconds{route}` (a histogram, each call to platform, [03 §10](../detailed-design/03-gateway.md#10-what-to-measure)), `unwritable_seconds{end}` (a histogram, each spell a lobby connection could take no more, drained or closed), `store_subscribed` (1 while the subscriber's connection, which every push arrives on, is up) and `resubscribed_total` ([03 §5](../detailed-design/03-gateway.md#5-push-routing)), and with `BACKEND_EDGE_CERTIFICATE` set `edge_certificate_expiry_timestamp_seconds` (nginx's certificate, §11) |
| worker | `applied_total`, `duplicates_total`, `dead_lettered_total`, `deferred_total`, `failed_total`, `unranked_total`, `queue_depth` (the stream group's lag, entries not yet delivered, plus the inbox list), `pending` (delivered, not yet acknowledged), `trimmed_unapplied_total` (results trimmed from the stream before anyone applied them), `dead_letter_depth`, `deferred_depth`, `retention_deleted_total`, `retention_seconds` (how long this worker's last retention run took, NaN before one), `tournament_failures_total` (tournament steps that failed and are tried again in 5 s), `ledger_mismatches` and `ledger_checked_timestamp_seconds` (the fleet's last daily check, the same on every worker); `pushes_unheard_total` (as platform's); the backups, read from `backup_run` at each scrape (§10, D-71): `backend_backup_succeeded_timestamp_seconds{kind}` (`dump`, `proof`, `offsite`: when each last succeeded, NaN for never), `backend_backup_failed{kind}` (1 when its last run failed) and `backend_backup_restore_seconds` (the last successful proof's restore and replay); the growth (D-72): `backend_mysql_table_rows{table}` (InnoDB's estimate of the growing tables' rows); the replicas' health (architecture/02 §7, D-58): `backend_mysql_replica_lag_seconds{host}` and `backend_mysql_replica_up{host}` (each MySQL replica, by the heartbeat, read every 5 s), and `backend_store_replicas`, `backend_store_replica_behind_bytes`, `backend_store_replica_ack_seconds` (the store's replicas, from its primary), the same under `backend_events_store_` |

Verified against a live run of 20 bots: every value matched what happened
(20 players and 20 connections mid-play, 20 logins, 20 results applied, the
queue empty after). **Alert first on:** `backend_worker_dead_letter_depth` above
0; `backend_arena_rooms_failed_total` rising; `backend_arena_tick_p99_seconds`
above 0.015 for five minutes (NFR-1b: the whole tick, simulation and snapshots;
0.040 is the deadline itself, past which ticks overrun); `backend_store_connected` at 0; `backend_worker_queue_depth`
growing for minutes; `backend_platform_hasher_line` at 168;
`backend_gateway_store_subscribed` at 0, or `backend_platform_pushes_unheard_total`
rising (a gateway's subscription is lost or astray, and every push to its lobbies
with it, 03 §5);
`backend_arena_tls_certificate_expiry_timestamp_seconds - time()` or
`backend_gateway_edge_certificate_expiry_timestamp_seconds - time()` under 14
days;
`backend_worker_failed_total` rising for more than a few minutes (an entry the
worker keeps failing on is retried, not dead-lettered, 05 §4);
`backend_worker_trimmed_unapplied_total` above 0 (a result lost: pending a whole
day, then trimmed; its ID is in the worker's log);
`backend_arena_results_unreplicated_total` rising (the `events` replica is down or
behind: results live on one machine until it is back);
`backend_worker_ledger_mismatches` above 0, or
`backend_worker_ledger_checked_timestamp_seconds` more than two days old;
`backend_worker_tournament_failures_total` rising steadily (tournaments stop
advancing); and the backups' and the growth's, which
[runbook §5](02-runbook.md#5-what-to-watch) lists with their thresholds (the
[runbook](02-runbook.md#3-per-component-procedures) says what to
do).

**What ships for watching, and what does not.** Each process serves its
metrics on loopback (above), and the worker reports the backups from
`backup_run`. No scraper, scrape configuration or alert rules are in the
release: the thresholds here and in runbook §5 are what the rules of whoever
scrapes these machines should say. Until something scrapes and alerts, the
daily checks are by hand (runbook §6). The API's
latency by route and matchmaking's waits by mode are histograms
([04 §11](../detailed-design/04-platform-services.md#11-what-to-measure)), and so
are the gateway's calls to platform by route, its connections' unwritable
spells, and each connection's snapshot bytes a second.

**Logging** goes to the journal at INFO (`common/logback.xml`). Before the
release was run as a release, it was two different mistakes. The arena and
platform had **no logging backend at runtime** (test-scoped, or missing), so
every error they logged went nowhere. And the worker and gateway had no
configuration, so logback's default of DEBUG flooded them. The development rig
hid both: its classpaths included test scope. Override the level with
`-Dlogback.configurationFile=/etc/backend/logback.xml`, no rebuild needed; an
override should keep the queue (the `QUEUED` appender with `neverBlock`).

**Lines are queued, and a stuck journal costs lines, not service**
([T-10](../defects.md#4-concurrency)). Written directly, as first shipped, a
journald that stopped reading froze a room mid-tick, the watchdog meant to
catch it, and the shutdown meant to publish its results: drilled, 31 of 60 bots
got in and the arena ignored SIGTERM for over 30 s. Queued, the same drill
played on at 15 snapshots a second and stopped in 2 s with every result in the
store. Up to 8 192 lines wait; beyond four fifths of that INFO is dropped, and
beyond all of it everything. Each process writes the queue out at the end of
its shutdown, waiting at most a second. So **a gap in the journal around a
journald problem is expected**; the metrics carry on regardless.

## 8. TLS for match traffic

**Built and run for real on 2026-09-26** ([S-6](../defects.md#5-security-and-input));
what it waits for is a certificate (§11). An arena serves TLS when
`BACKEND_ARENA_TLS_KEYSTORE` names a PKCS#12 keystore, its password in
`BACKEND_ARENA_TLS_PASSWORD_FILE` (or `_PASSWORD`, by the rules in §2). It
announces `tls` in the directory, platform's grant carries it, and the client
connects accordingly ([02 §1](../detailed-design/02-networking.md#1-transport)).
Without a keystore it serves plaintext and says so at start: fine on a
developer's machine, but in front of players a join ticket in plaintext can be
used by anyone on the path before its owner uses it.

**The certificate must name `ADVERTISE_HOST`.** Clients verify it against the
host they were told to dial, so the arena refuses to start (exit 2) when no
subject alternative name covers that host: an exact name, a wildcard for one
leftmost label, or the address itself. In practice that makes `ADVERTISE_HOST`
a DNS name, since public CAs certify IP addresses briefly, if at all: **the
machine's one public name**, the one its nginx serves (§11). The arenas on a
machine share its address, so they share its name and differ by port.

**It also refuses** a keystore it cannot read, a wrong password, a store with
no private key (a trust store given by mistake would otherwise fail every
handshake with "no cipher suites in common"), and a certificate that has
expired or is not valid yet, the intermediates served with it included. Each
was run against the release.

**The keystore** is made from the PEM files a CA issues by
`scripts/install-certificate.sh` (§11), with a password it generates once.

**The unit.** `deploy/systemd/arena-tls.conf.example` is a drop-in that loads
both files as credentials. Install it as
`/etc/systemd/system/backend-arena@.service.d/tls.conf` once the files exist,
then `systemctl daemon-reload`. Run for real with the shipped unit: the keystore
arrived through `LoadCredential`, `openssl s_client -verify_ip` accepted the
certificate, a client dialling another name was refused with "hostname
mismatch" and counted. The same run found that a missing credential file
(systemd's exit status 243) was restarted every 2 s for ever; all four units now
treat 243 like 2 and stay stopped.

**Renewal takes a restart.** The keystore is read once, at start, and an
arena's restart ends every connected player's stay (their results are
published first). So the renewal only replaces the file, and the weekly
restart ([runbook §6](02-runbook.md#6-routine)) or the next deploy picks it up:
certbot renews 30 days ahead, so that is weeks early. What each arena actually
serves is `backend_arena_tls_certificate_expiry_timestamp_seconds`, the earliest
expiry in its chain; alert when it is less than 14 days away, and the arena
also warns at start inside that window. If the certificate source turns out to
issue certificates that last days, reloading without a restart becomes worth
building.

**Failed handshakes**, and those not finished by the 10 s join deadline, are
counted in `backend_arena_connections_dropped_total{reason="tls_handshake"}`. Scanners and
plaintext clients make a steady trickle; a jump after a certificate change
means clients are refusing it, which shows nowhere else on the server.

**The bots** (`tools/BotClient`) use TLS when the grant says so. With
`BACKEND_BOT_TRUSTSTORE` (PKCS#12, password in
`BACKEND_BOT_TRUSTSTORE_PASSWORD`) they check the certificate and the host as a
client must; without it they trust any certificate and print a warning, which
is for a development arena only. `BACKEND_BOT_PROFILE` (saver, mobile or high)
makes them ask for a traffic profile, and `BACKEND_BOT_VIA=host:port` dials
there instead of the arena, such as a proxy that shapes the link
([02 §8](../detailed-design/02-networking.md#8-traffic-profiles)). They send
`Leave` at the end of a run, so their results are published at once rather than
after the minute a lost connection's stay waits
([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).

## 9. MySQL

**Checked on 2026-09-26 against a MySQL 8.0.46 instance set up from nothing
this way**, beside the development one: the release's `platform` migrated the
empty database and served a registration and a login, every pooled connection
on TLS 1.3; the backup script ran as its own user. Each grant below was checked
by taking it away. Not yet done on RHEL 9 itself, whose AppStream ships 8.0
too. Flyway runs at every `platform` and `worker` start, so a release needs no
separate migration step.

**The release's own MySQL**, 8.4.11 LTS (D-77, Q-56,
[09 §6](../detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)),
not the distribution's package. Checked 2026-10-05: every drill scenario on it,
a primary and a replica with the replica promoted halfway; the backup drill; the
backend's tests against it; and, in UBI 9.5 with nothing installed, initialised,
started and migrated by the platform.

**Install:** the system user `mysql` (§7), `/etc/backend/mysql/backend.cnf` from
the release's `mysql-conf/backend.cnf.example`, whose header initialises the data
directory (`/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`),
then `systemctl enable --now backend-mysql`. Its tools are
`/opt/backend/mysql/bin/`; the scripts find them there by themselves.

**Settings**, in `/etc/backend/mysql/backend.cnf` (the example carries them,
with the data, socket and log paths). The transport and TLS lines were
exercised; the sizes follow the design and were not:

```
[mysqld]
bind-address               = 10.0.0.1         # the private network, never a public interface
mysqlx                     = OFF              # the X Protocol, unused: on, it listens on 33060
                                              # on every interface whatever bind-address says
require_secure_transport   = ON               # TCP without TLS is refused; the socket still works
tls_version                = TLSv1.2,TLSv1.3
ssl_ca                     = /etc/mysql-tls/ca.pem
ssl_cert                   = /etc/mysql-tls/server-cert.pem
ssl_key                    = /etc/mysql-tls/server-key.pem
log_bin                    = binlog           # the default in 8.0; point-in-time recovery needs it
binlog_format              = ROW
binlog_expire_logs_seconds = 604800           # 7 days (06 §10): as far back as a restore can reach
default-time-zone          = '+00:00'         # UTC, as the processes write (06 §2): CURRENT_TIMESTAMP too
innodb_buffer_pool_size    = 14G              # of MySQL's 20 GB (architecture/01 §2)
max_connections            = 151              # pools are 16 per platform and 8 per worker, plus
                                              # the backup and whoever is investigating
```

**The certificate** comes from a private CA: nothing outside the private
network connects, so a public one buys nothing. It must name the host in
`BACKEND_DB_URL`, as an IP address or a DNS name, because the processes check.
The CA's key belongs somewhere safer than the database machine.

```
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=backend MySQL CA" \
    -keyout ca-key.pem -out ca.pem
openssl req -newkey rsa:2048 -nodes -subj "/CN=db" -keyout server-key.pem -out server.csr
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days 825 \
    -extfile <(printf "subjectAltName=IP:10.0.0.1\nextendedKeyUsage=serverAuth") -out server-cert.pem
```

**The processes' trust store** holds that CA and nothing else, as PKCS#12
with no password: it holds only a public certificate, so there is no secret to
put in a URL or a unit. `/etc/backend/tls/mysql-ca.p12`, mode 0644, on every
machine that runs `platform` or `worker`:

```
keytool -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE \
    -importcert -noprompt -alias mysql-ca -file ca.pem \
    -storetype PKCS12 -keystore /etc/backend/tls/mysql-ca.p12 -storepass unused
```

**The URL**, in `platform.env` and `worker.env`:

```
BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12
```

`sslMode=VERIFY_IDENTITY` alone, as the examples had it until this was run,
**cannot connect**: the driver checks the certificate against the JVM's public
CAs and fails with "Path does not chain with any of the trust anchors". Dialled
by a name the certificate does not carry, it fails with "Server identity
verification failed", which is the check doing its job. The trust store is
named in the URL rather than set JVM-wide (`javax.net.ssl.trustStore`), which
would replace the public CAs for every other TLS connection the process makes.
The URL never reaches a log: Flyway prints it as `********`, and the settings
print without their query string.

**Users.** The application's user may change the schema, because Flyway runs
as it; it may not drop anything, and nothing needs it to. The schema is a
baseline, `V1__schema.sql` and `V2__seed.sql`
([D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)),
which creates the tables and inserts the rows a first launch needs; later
migrations are forward-only. These grants were checked by applying the
migrations of the time (V1 to V3) from empty, not yet the baseline:

```sql
CREATE DATABASE backend;
CREATE USER 'backend'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON backend.* TO 'backend'@'10.0.0.%';

-- both from the backup machine (§10), never from the database's own
CREATE USER 'backup'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, RELOAD, REPLICATION CLIENT ON *.* TO 'backup'@'10.0.0.%';
CREATE USER 'binlog'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'binlog'@'10.0.0.%';
```

The backup account records what each backup step did (06 §10, D-71): one grant
more, after the first start has migrated, as the table must exist for it.
`RELOAD`, which it has, is also what lets the copy off the site flush the binary
logs:

```sql
GRANT INSERT ON backend.backup_run TO 'backup'@'10.0.0.%';
```

**The failover account**, for `promote-mysql.sh` on the replica's machine
(runbook §2, D-35), created on the primary, whence replication carries it to the
replica. It reaches its own server by the socket and the old primary over TLS,
so two accounts of one name and password. Each grant was checked by taking it
away (2026-09-29): `SET PERSIST` needs `SYSTEM_VARIABLES_ADMIN`; `offline_mode`,
and connecting while it is on, `CONNECTION_ADMIN`; `STOP REPLICA`
`REPLICATION_SLAVE_ADMIN`; `RESET REPLICA ALL` `RELOAD`. The epoch's and the
heartbeat's tables must exist for their grants, so those two lines come after
the first start has migrated:

```sql
CREATE USER 'failover'@'localhost' IDENTIFIED BY '…';
CREATE USER 'failover'@'10.0.0.%'  IDENTIFIED BY '…' REQUIRE SSL;
GRANT SYSTEM_VARIABLES_ADMIN, CONNECTION_ADMIN, REPLICATION_SLAVE_ADMIN, RELOAD ON *.*
      TO 'failover'@'localhost', 'failover'@'10.0.0.%';
GRANT SELECT ON performance_schema.* TO 'failover'@'localhost', 'failover'@'10.0.0.%';
GRANT SELECT, UPDATE ON backend.ha_epoch TO 'failover'@'localhost', 'failover'@'10.0.0.%';
-- The heartbeat a promotion reads to say what it would lose (plan item 59): the baseline
-- creates ha_heartbeat and its one row, as it does ha_epoch's.
GRANT SELECT ON backend.ha_heartbeat TO 'failover'@'localhost', 'failover'@'10.0.0.%';
```

Its option file is `/etc/backend/credentials/mysql-failover.cnf`, root's,
`0600`, on the replica's machine. The TLS settings are in a group of their own
that only the connection to the old primary reads: in `[client]`, the client
tries TLS on the socket too and checks the certificate against "localhost",
which it does not name, and fails.

```ini
[client]
user     = failover
password = …

[client_remote]
ssl-mode = VERIFY_IDENTITY
ssl-ca   = /etc/mysql-tls/ca.pem
```

The application's password goes in `/etc/backend/credentials/db-password`
(§2). Without `RELOAD` the dump is refused (`FLUSH TABLES`), and without
`REPLICATION CLIENT` too (`SHOW MASTER STATUS`); `PROCESS`, which the script
used to ask for, is not needed and would show the user every session's
statements. The dumps and the
store's copies are written private (0700 directories, 0600 files); until
2026-09-26 they were readable by every local user
([S-10](../defects.md#5-security-and-input)).

### The replica, on C (plan item 13, D-35)

**GTIDs, on both servers**, and the clone plugin, which makes a replica either
way round. Added to `backend.cnf`:

```
server_id                = 1              # 2 on C: each server of the pair its own
gtid_mode                = ON
enforce_gtid_consistency = ON             # the backend's statements pass: the drill's servers run
                                          # every migration and scenario with it
plugin-load-add          = mysql_clone.so
```

A primary already running without GTIDs has them turned on live, one statement
at a time; rehearsed under steady writes, 40 of 40 written, about ten seconds:

```sql
SET PERSIST enforce_gtid_consistency = WARN;   -- then the error log should say nothing new
SET PERSIST enforce_gtid_consistency = ON;
SET PERSIST gtid_mode = OFF_PERMISSIVE;
SET PERSIST gtid_mode = ON_PERMISSIVE;
SHOW STATUS LIKE 'Ongoing_anonymous_transaction_count';   -- until 0
SET PERSIST gtid_mode = ON;
```

The backups work the same with GTIDs on, once the restore's replay drops them
(defect [D-31](../defects.md), 06 §10): checked restoring onto the source's own server,
onto another with GTIDs on and one with them off, and from the stream's copies.

**C's settings** are A's, with `server_id = 2`, `bind-address = 10.0.0.3`, a
certificate of its own from the same CA naming `10.0.0.3` (the processes check
every host they are given, the replica included), and:

```
super_read_only = ON          # nothing but replication writes; a promotion's SET PERSIST outranks it
```

**Two more accounts on the primary**, which replication carries to C: one to
replicate, one to copy a whole server.

```sql
CREATE USER 'repl'@'10.0.0.%'  IDENTIFIED BY '…' REQUIRE SSL;
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'10.0.0.%';
CREATE USER 'clone'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT BACKUP_ADMIN ON *.* TO 'clone'@'10.0.0.%';
```

**Making the replica**, on C, as root by the socket. The same makes an old
primary a replica again (runbook §2, step 5), with the addresses the other way
round:

```sql
SET GLOBAL offline_mode = ON;         -- administrators only while it is writable for the copy
SET GLOBAL super_read_only = OFF;     -- the clone is refused on a read-only server
SET GLOBAL clone_valid_donor_list = '10.0.0.1:3306';
SET GLOBAL clone_ssl_ca = '/etc/mysql-tls/ca.pem';
CLONE INSTANCE FROM 'clone'@'10.0.0.1':3306 IDENTIFIED BY '…' REQUIRE SSL;
-- It replaces this server's data, accounts and GTID history with the primary's, then restarts
-- itself where systemd supervises it; otherwise it stops (error 3707), and a start completes it.
-- Neither setting above survives the restart: it comes back read-only and open.
CHANGE REPLICATION SOURCE TO SOURCE_HOST = '10.0.0.1', SOURCE_PORT = 3306,
    SOURCE_USER = 'repl', SOURCE_PASSWORD = '…', SOURCE_AUTO_POSITION = 1,
    SOURCE_SSL = 1, SOURCE_SSL_CA = '/etc/mysql-tls/ca.pem', SOURCE_SSL_VERIFY_SERVER_CERT = 1;
START REPLICA;
```

**What those TLS settings check**, tested with a CA of the drill's own
(2026-09-30): replication refuses a primary whose certificate is from another
CA, and one whose certificate names another address. The clone refuses another
CA's certificate but not one naming another address: it checks the CA only.
The CA signs nothing but the backend's MySQL servers, so a certificate it
signed is one of them; its key stays off every database machine (above).

The copy's persisted settings are the recipient's own, never the donor's.
Rehearsed in the drill (`MYSQL_FAILOVER`, client/headless-drill.sh), where each
failover ends with the old primary rebuilt this way and handed back to.

**Then every process is given both hosts**, the primary's first by convention,
in the URL of the processes above; each new connection goes to the writable one
with the highest epoch (06 §10):

```
BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306,10.0.0.3:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12
```

## 10. Copies off the database's machine

**Built and run for real on 2026-09-26.** A dump on the disk it protects is lost
with it, and so are the binlogs a point-in-time restore replays. So both are
taken from another machine, the one the replica will live on (architecture/01
§2), over TLS:

- **The binlogs, as they are written.** `backend-binlog-stream.service` runs
  `scripts/binlog-stream.sh`: `mysqlbinlog --read-from-remote-server --raw
  --stop-never`, the standard binlog server. Copies land in
  `/var/lib/backend/binlog`. Measured: a committed write reached the copy in
  **17 ms**, and every closed file was byte-for-byte the server's. The one file
  still being written differs in a single byte, the server's "in use" flag. A
  restarted stream resumes from its newest copy: `--raw` rewrites that file from
  the start, so a half-written one is completed, never duplicated.
- **The nightly dump.** `backend-mysql-backup.timer` runs
  `scripts/backup-mysql.sh` at 03:30 UTC (within 15 minutes after), with the
  connection in an option file, into
  `/var/lib/backend/mysql-dumps`. After a dump succeeds it deletes dumps older
  than `KEEP_DAYS` (8), and every binlog copy before the file the oldest
  remaining dump starts from. That rule is exact; pruning copies by age would
  delete the file an old dump starts in whenever the database had been idle
  before it.

**The recovery that matters was rehearsed:** with the full stack writing to a
TLS primary, a remote dump, 20 more matches after it, then a restore onto a
different, empty server from the dump and the copies alone. Every table matched
the primary, and the ledger reconciled with every balance. Copies with a file
missing in the middle are refused before anything is loaded
([runbook](02-runbook.md#the-restore-drill)).

**Install**, on the backup machine:

1. `useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin backend-backup`;
   the client tools are the release's (`/opt/backend/mysql/bin`), which the
   scripts find beside them.
   A user of its own: the copies are the whole database, and the `backend`
   processes on the same machine have no business reading them.
2. The CA as PEM, `/etc/backend/tls/mysql-ca.pem` (the C tools do not read the
   PKCS#12 store the processes use, §9).
3. Two option files in `/etc/backend/credentials/`, mode 0600, `[client]` with
   `host`, `user`, `password`, `ssl-mode=VERIFY_IDENTITY`, `ssl-ca`:
   `mysql-binlog.cnf` for `binlog` and `mysql-backup.cnf` for `backup` (§9).
4. `deploy/systemd/backend-binlog-stream.service`, `backend-mysql-backup.service`
   and `backend-mysql-backup.timer` into `/etc/systemd/system/`, then enable the
   stream and the timer. The release carries the scripts in `scripts/`.

Both units were run under systemd against the TLS primary, and the stream
through a restart of the database. That restart showed why the unit restarts
**always**, not on failure: `mysqlbinlog` ends with status 0 when the server
shuts down, so `on-failure` would have left the copies stopped, silently.

**Watching the stream.** The stream has no metric of its own, and records
nothing in `backup_run`. A stream that has stopped shows in two steps that do
record: the next copy off the site, which fails when the stream does not open
the next binlog within 30 s (once a host is named), and the next weekly proof,
which fails when the copies never reach the primary's position. That can be a
week after it stopped, so `systemctl status backend-binlog-stream` is in the
daily checks ([runbook §6](02-runbook.md#6-routine)). If the stream was down
longer than the server keeps binlogs (7 days, §9), the server has purged the
next file: the stream fails, saying so, and the copies have a gap no restart
fills. Take a dump at once; that is the new starting point.

### Proved every week, and kept off the site (plan item 76 (a), D-71)

**Built and drilled 2026-10-04** with `scripts/backup-drill.sh`, which runs
every script below on MySQL servers of its own, seeded with a copy of a real
database, and checks what each must refuse as well as what it must do (06 §10),
a proof from off the site with the wrong key among them: refused, and recorded
as a failure (O-15). The drill runs the scripts, not the units; the scratch
server's unit was run under systemd on its own (below).

**The proof**, on the backup machine:

1. A scratch MySQL server of its own, which holds nothing but the proofs'
   copies: `deploy/mysql/scratch-my.cnf.example` to
   `/etc/backend/scratch-my.cnf`. It keeps its configuration, data and socket
   apart from the replica's. It is the release's `mysqld`,
   `/opt/backend/mysql/bin/mysqld`, which the distribution's confinement does not
   cover: AppArmor's profile attaches to `/usr/sbin/mysqld` (the rules O-16
   shipped for it are gone with it), and SELinux runs a service started from
   `/opt` unconfined (not tested on an enforcing RHEL).
2. Initialise it once, `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/scratch-my.cnf
   --initialize-insecure --user=mysql`; enable and start
   `backend-scratch-mysql.service`; set root's password over its socket
   (`mysql -S /run/backend-scratch-mysql/mysqld.sock -u root`, then
   `ALTER USER 'root'@'localhost' IDENTIFIED BY '…'`) and put it in
   `/etc/backend/credentials/mysql-scratch.cnf` (mode 0600, `[client]` with
   `host=127.0.0.1`, `port=3309`, `user=root`, `password`). The unit is
   `Type=notify`, so it counts as started only once the server takes
   connections (up to `TimeoutStartSec=10min`), and makes
   `/run/backend-scratch-mysql` (`RuntimeDirectory`, mode 0750) at every start:
   `/run` is emptied at boot and mysqld makes no parent directory, so without it
   the server never started ("Unable to setup unix socket lock file", O-16).
   Run under systemd on Ubuntu 24.04, 2026-09-28 with the distribution's 8.0;
   2026-10-05 the release's 8.4 under `Type=notify`: "Server is operational",
   active, and stopped cleanly.
3. `backend-restore-proof.service` and `.timer`, the timer enabled: every
   Sunday at 04:00 UTC the newest dump and the binlog copies are restored onto
   it, into `backend_proof`, while the primary keeps writing, checked, and
   recorded (`backup_run`). Every outcome is recorded, a failure with the lines
   of its log that say why.

**The copy off the site**, once the owner has named the host (Q-53):

1. The key, made once and **kept somewhere other than the three machines**,
   on paper or in the owner's password manager: without it the copy cannot be
   read. `head -c 32 /dev/urandom | base64 > /etc/backend/credentials/offsite.key`,
   mode 0600.
2. An SSH key for the backup user, `/etc/backend/credentials/offsite-ssh`, its
   public half on the host; the host's own key in
   `/etc/backend/offsite-known-hosts`; and `/etc/backend/offsite.env` with
   `OFFSITE_TARGET=user@host:dir`.
3. `backend-backup-offsite.timer` enabled: hourly, the primary's binary logs
   are flushed, and the dumps and every closed binlog copy, each encrypted, are
   pushed by `rsync --delete` to the host, which so holds what the backup
   machine does. The copy off the site is at most an hour behind.
4. `backend-restore-proof-offsite.timer` enabled: on the first Sunday of each
   month at 05:00 UTC, the copy is fetched back, decrypted, and restored as the
   weekly proof is, into a database of its own, `backend_proof_offsite`, which
   proves the key, the host and the copy together. The two proofs never run at
   once. The fetch's errors go into the proof's log, so a wrong key or an
   unreachable host is recorded with what it said.

**Watch it** ([runbook §5](02-runbook.md#5-what-to-watch)): every worker
reports, from `backup_run`, when each step last succeeded and whether its last
run failed (`backend_backup_succeeded_timestamp_seconds{kind}`,
`backend_backup_failed{kind}`, §7). Those are what alerts should read. No alert
rules ship (§7), so until something alerts on them the daily check reads them,
or `backup_run`, by hand, with `systemctl status backend-binlog-stream`
(above).

## 11. The certificate

What players' devices check, for logins, purchases and the lobby (nginx, §4)
and for match traffic (the arenas, §8). **Everything but two choices is built
and was run from placeholder to renewal on 2026-09-26**: the domain, and the CA
([S-6](../defects.md#5-security-and-input)).

**One name per machine, one certificate per machine.** Machine A is
`a.<domain>`, B `b.<domain>`, C `c.<domain>`: its nginx serves that name, its
arenas advertise it (`ADVERTISE_HOST`) and differ by port, and the client's
endpoint list ([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip))
holds the three. So each machine's certificate names one host, and each
machine gets its own through the CA's HTTP-01 challenge on its own port 80.
Nothing needs a wildcard, so nothing needs DNS-01 and a DNS provider's API key
on every machine, and no certificate is copied between machines. A replacement
machine gets its own once its name points at it. A CAA record in DNS naming the
chosen CA keeps every other CA from issuing for the domain.

**The CA is a choice with one fact to weigh.** Let's Encrypt is free and
automatic, but devices trust it through the ISRG Root X1 root, which Android
added in 7.1.1: an older Android device refuses it. Whether that matters is the
client's minimum OS version, which is not chosen yet either. A CA whose root
old devices carry costs money, or an account; everything below works with any
CA that issues PEM files.

**The first time**, on each machine, after DNS points `a.<domain>` at it:

```
mkdir -p /var/www/acme
/opt/backend/scripts/install-certificate.sh --placeholder   # nginx cannot start without one
systemctl enable --now backend-nginx
dnf install certbot                                         # EPEL
certbot certonly --webroot -w /var/www/acme -d a.<domain> \
    --deploy-hook /opt/backend/scripts/install-certificate.sh
systemctl enable --now certbot-renew.timer
```

then the arena drop-in (§8) and a restart of the arenas. The placeholder is
self-signed, for the machine's name, and only goes where no certificate is:
devices refuse it, so nothing can use it by mistake. The challenge directory
is under `/var/www` because nginx may read there under SELinux's default
policy, which it may not in `/var/lib`.

**`install-certificate.sh`** is the deploy hook, and runs the same by hand for a
CA without ACME: `install-certificate.sh fullchain.pem privkey.pem`. It learns
what the machine serves from nginx's `server_name` and each
`/etc/backend/arena-*.env`'s `ADVERTISE_HOST`, and **installs nothing** unless:

- the key is the certificate's;
- the chain reaches a trusted root through the intermediates in the file
  alone. Devices do not fetch a missing intermediate, so a file holding only
  the leaf (`cert.pem` in place of `fullchain.pem`) works in a desktop browser
  and fails on phones;
- it is valid now, with a warning inside 14 days;
- it names every host the machine serves.

Then nginx gets `/etc/backend/tls/fullchain.pem` (0644) and
`/etc/backend/credentials/privkey.pem` (0600); each file is replaced whole. If
`nginx -t` refuses them the previous ones are put back; otherwise nginx is
reloaded, which drops no connection, and the script checks that nginx then
serves the new certificate. The arenas get
`/etc/backend/credentials/arena-tls.p12`, with a password made once, and the
script says which arenas still serve the previous certificate until their
restart. Two runs at once are refused. **`--check`** shows what every port
serves, as a device would check it, and fails when anything is wrong or within
14 days; run it after any change of name or certificate.

**Run for real** against nginx 1.24 and the release, with a test CA shaped like
a public one (a root and an intermediate): each refusal above, plus a key
file that was the certificate, left the installed files byte for byte as they
were; nginx started on the placeholder, devices refused it, and port 80 served
the challenge; installed as certbot's hook, the certificate was served by
nginx at once and by the arena from its start, and bots trusting only the root
went through both. Renewed under ten connected bots, none was dropped; the
arena kept the previous certificate until restarted, then served the new one.
A test nginx refusing the files got the previous ones back. **Not yet run**
on RHEL 9 with SELinux enforcing, nor against a real CA.

**Watching it.** nginx exports nothing, so the gateway reports the certificate
file nginx serves, at every scrape, as
`backend_gateway_edge_certificate_expiry_timestamp_seconds`
(`BACKEND_EDGE_CERTIFICATE` in `gateway.env`); an unreadable file reads as 0,
so the alert fires rather than the series vanishing. The arenas report what
they serve (§8). Both are **the earliest expiry in the chain**, not the leaf's:
a device refuses the chain when an intermediate has expired, too. Alert on
either under 14 days (§7). Certbot renews 30 days ahead, so an alert means
renewal has failed for over two weeks: `certbot renew --dry-run` says why.

**What the client must do**, for whoever builds it: `https://` and `wss://`
only, verified against the device's own trust store, never a plaintext
fallback. **Pin nothing**: a pinned certificate or key breaks every installed
client at the next renewal, which with ACME is every few months. On a
certificate error, try the next endpoint, then show an error; never offer to
continue.
