# Deployment

This page is for the engineers and operators who install the backend on servers and roll out new versions. It
explains how the system is laid out on the machines (§1), the conventions every process follows (§2), the stores
(§3), nginx (§4), a rolling deployment (§5), installing a machine (§7), TLS for match traffic (§8), MySQL and its
replica (§9), the backups (§10) and the site's certificate (§11). It gives the reasons for each choice, and records
what was checked. For the commands step by step, follow the [install guide](03-install-guide.md). To start, stop or
restart a service, see [07](07-start-stop-restart.md). Terms such as "front", "arena" and "epoch" are defined in the
[glossary](../glossary.md).

**Status: runnable, not yet run on a production machine.**

- **Written**: the release build, the systemd units and the nginx configuration, in `backend/deploy/`.
  [Its README](../../backend/deploy/README.md) describes them file by file, and holds the catalogue of every
  setting, port and timer. The scripts are in `backend/scripts/`, described one by one in
  [their README](../../backend/scripts/README.md). Every port, who connects to it and from where, and each
  machine's firewall: [05](05-ports-and-firewall.md).
- **Run for real**, sandboxed, with bots and MySQL: the four process units and the release (§7); arena TLS (§8);
  MySQL's own setup with TLS (§9); the binlog stream and the dump under systemd (§10); the scratch server's unit
  (§10); and nginx with a certificate, from the placeholder to a renewal (§4, §11).
- **Drilled as scripts, not yet as units**: the restore proofs and the copy off the site (`backup-drill.sh`).
- **Nothing of the certificate remains to be decided** ([§6](#6-still-to-be-written)):
  [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
  settled its source and the site's address (§11).

The pictures are in [diagrams/07](../diagrams/07-deploy-and-operations.md).

## 1. What runs where

The full allocation is in
[architecture/01 §2](../architecture/01-system-topology.md#2-machine-topology). This table summarises the roles.
The stateful roles come first, because they are the ones that constrain a rollout:

| Role | Machine | Notes |
|---|---|---|
| MySQL primary | A | replica on C |
| j-redis `session` primary | A | replica on B |
| j-redis `events` primary | B | replica on C |
| nginx, gateway, platform | all three | stateless and interchangeable. nginx is set up on every machine as the front: the one machine the provider forwards the site's public address to ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)) |
| account service | all three | stateless but for its own database, `account`, on the backend's MySQL; one per machine as the platform, behind the front's nginx at `/account`, the same signing key on each ([D-117](../architecture/03-decision-log.md#d-117--accounts-in-an-account-service-of-their-own-which-issues-signed-tokens-the-backend-takes-once-for-a-session), [10 §6](../detailed-design/10-accounts.md#6-the-site)) |
| arena | all three | stateful only for the lifetime of a match |
| worker | C | stateless: what it has in flight stays pending for it in the result stream, and another worker takes it over after a minute |
| backups: the binlog stream, the dumps, the proofs and their scratch server, the copy off the site | C, the MySQL replica's machine | never on the primary's machine; after a failover they move with the replica ([runbook §2](02-runbook.md#mysql-built-2026-09-29-d-35)) |

The two store primaries are on different machines, and each is replicated to another machine
([D-34](../architecture/03-decision-log.md#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)).
So losing one machine costs at most one primary, and its replica is promoted.

**At launch, one machine has every role**
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)),
for up to about 5 000 players:

- the primaries, with no replica;
- three arenas (`a1` to `a3`) and two workers (`c1`, `c2`);
- the backups, beside the database. This breaks the rule in the table above, so their copies are on a disk of
  their own: they outlive the database's disk, though not the machine.

The [install guide](03-install-guide.md) installs it. Everything this page says of A, B and C holds on it too, on
loopback, except what needs a second machine: a replica, a promotion, a front to move to. A second machine can be
added as the first one's standby, with arenas of its own
([D-96](../architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start),
[D-97](../architecture/03-decision-log.md#d-97--arenas-on-the-standby-three-of-its-own-behind-as-nginx-watched-from-a)):
[10](10-two-machines.md).

## 2. Conventions

These conventions apply to every process type, and are settled:

- **Configuration is by name.** Each process reads `/etc/backend/<process>.env` (examples in `deploy/env/`). Every
  port, host, size and name that an operator sets is a named variable there. The units pass some of them to `main`
  as positional arguments. That order belongs to the release and is fixed in the unit it ships: do not edit it.
- **One systemd unit per process instance**: `backend-arena@<name>` and `backend-worker@<id>`, and one
  `backend-platform` and one `backend-gateway` per machine.
- **Both instance names are unique across the fleet**, not only on their machine.
  - An arena's name is its name in the store: its directory entry (`arena:<name>`) and its command channel
    (`arena-admin:<name>`). Two `backend-arena@a1` on two machines overwrite each other's entry, and stopping one
    withdraws the other's.
  - A worker's id is its consumer name in the result stream's group (`worker-<id>`).
  - Prefixing the machine's letter keeps them apart: arenas `a1`, `a2` on A, `b1` to `b3` on B, `c1`, `c2` on C,
    and workers `backend-worker@c1` and `@c2`. Each arena's TLS name is unique too, since nginx routes by it
    ([05 §3](05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)).
- **Versioned directories with a symbolic link**: each release is unpacked in `/opt/backend-<version>/`, and
  `/opt/backend` is a link to the current one, so a rollback is a link swapped back. j-redis already uses this
  layout.
- **Configuration outside the release**: `/etc/backend/` holds the configuration. The release directory is
  read-only and can be replaced.
- **State outside the release**: `/var/lib/backend/` for data and spool files, `/var/log/backend/` for GC logs and
  heap dumps. systemd creates both, by `StateDirectory=` and `LogsDirectory=`. The stores, MySQL and nginx have
  directories of their own: `/var/lib/backend-store/<name>`, `/var/lib/backend-mysql` and
  `/var/log/backend-nginx`.
- **CPU pinning with `CPUAffinity=` in a drop-in** (§7), following the core budget in
  [architecture/01 §3](../architecture/01-system-topology.md#3-capacity-model).
- **Exit codes tell a failure that a restart can fix from one that needs a person**, as j-redis does. The units
  name the second kind in `RestartPreventExitStatus`, and restart on everything else. `platform`, `worker`,
  `gateway` and `arena` exit:
  - **2** when they refuse their configuration: the old argument shape, a credential file that cannot be read, a
    store that demands a password they were not given;
  - **1** when anything else fails while starting: an unreachable database, a wrong database password, a port in
    use.

  Before 2026-09-25, a failure while starting left the process running with nothing served, because the store
  client's threads are not daemons, and systemd never restarts a unit in that state.

### Secrets

**A secret is never a command-line argument**: every local user can read a process's arguments with `ps`. A
process given the database password in the old argument positions exits 2 and says so.

**Each process takes its secrets from its own settings file, as plain variables**
([D-102](../architecture/03-decision-log.md#d-102--a-processs-secrets-are-in-its-own-settings-file-as-plain-variables-the-units-carry-none)):
`/etc/backend/<process>.env`, which its unit already loads with `EnvironmentFile=`. The file is root's alone, mode
`0600`. This keeps the secrets safe because:

- systemd reads the file as root before the process starts, so the process itself needs no access to it;
- `systemctl show` prints the file's name, never its values;
- a value then sits in the process's environment, `/proc/<pid>/environ`, which only root and the process's own
  user can read;
- it needs nothing of systemd but `EnvironmentFile=`, which every version has.

| Variable | In | |
|---|---|---|
| `BACKEND_DB_PASSWORD` | `platform.env`, `worker.env` (the leaderboard rebuild's too) | The database password |
| `BACKEND_STORE_PASSWORD` | `platform.env`, `worker.env`, `gateway.env`, each `arena-<name>.env` | The store's password, j-redis's `requirepass` |
| `BACKEND_EVENTS_STORE_PASSWORD` | `worker.env`, each `arena-<name>.env`, where it differs | The `events` store's; unset, the session store's |
| `BACKEND_ADMIN_TOKEN` | `platform.env`, with `BACKEND_ADMIN_ADDR` | The admin API's bearer secret |

```ini
# /etc/backend/platform.env, root:root 0600; at its end, the values the install made in /etc/backend/credentials/
BACKEND_DB_PASSWORD=3f9c0d…
BACKEND_STORE_PASSWORD=8a41e7…
```

**Three rules:**

1. A secret is never an `Environment=` line, in a unit or in a drop-in: `systemctl show` prints those to any user.
2. Every `/etc/backend/*.env` stays `root:root 0600`, a new one and a copy too. After you make one with `>`, run
   `chmod 0600` (`sed -i` and `tar -p` keep the mode).
3. A value is written as it is, without quotes. The install makes only hex and base64 values.

The install guide's check fails a machine that breaks rule 1 or rule 2.

**Each variable also has a `_FILE` form** (`BACKEND_DB_PASSWORD_FILE`, …). It names a file that holds the value,
and it wins over the plain variable. One line ending at the end of the file is ignored. It is for a drill or for a
container's secrets; the shipped units do not use it. A file that is named but cannot be read, or is empty, stops
the process (exit 2), with no fallback.

The database's other settings (`persistence/DatabaseSettings`):

| Variable | |
|---|---|
| `BACKEND_DB_URL` | JDBC URL. Default: the development database on 127.0.0.1 |
| `BACKEND_DB_USER` | Default `backend` |
| `BACKEND_DB_POOL_SIZE` | The process's connection pool, 1 to 100: size it for the database host, about its cores × 2 ([06 §7](../detailed-design/06-persistence-mysql.md#7-connection-pooling)). Default 16 for `platform`, 8 for `worker`, 2 for the rebuild; anything else refuses the configuration |
| no password | The development password, with a warning on stderr |

**The store password is for all four process types**: production j-redis runs with `requirepass`, as its own
install notes say. Until 2026-09-26 no process sent one ([S-8](../defects.md#5-security-and-input)).

- **No password given**, and the store wants one: the process exits 2 at start-up, with a message that names the
  variable.
- **A wrong password** cannot be told apart from a store that is not up yet. So the process runs, and the store
  client logs `authentication … failed: WRONGPASS` while it retries slowly. That line is the thing to look for.
- **A store that cannot be reached** does not stop a process: an arena runs, and spools its results to its disk,
  while the store is down.

The platform's other settings, in `platform.env`:

| Variable | |
|---|---|
| `BACKEND_ADMIN_ADDR` | The admin API's `host:port` ([runbook §3a](02-runbook.md#3a-the-admin-api)), `127.0.0.1:9120` in the example. Unset: no admin API. An address that is not loopback refuses the configuration (exit 2) |
| `BACKEND_ADMIN_TOKEN` | The admin API's bearer secret, with the secrets above (or `BACKEND_ADMIN_TOKEN_FILE`). Required once `BACKEND_ADMIN_ADDR` is set: without it the platform refuses to start (exit 2) |
| `BACKEND_PAYMENT_PROVIDER` | Unset or blank: nothing is sold, and every payment route answers 503 `payments_off`. `simulated`: the simulated provider, which grants gems to whoever asks, for development and drills only (D-68). Any other value refuses the configuration (exit 2) |

Every variable a process reads, with its default and the shipped files that set it, is in the
[deploy README's catalogue](../../backend/deploy/README.md#environment-variables).

**`/etc/backend/credentials/`** is `root:root 0700`, and its files `0600`. It holds the values the install made.
From them the install writes the settings files and the backups' option files, and the operator reads them: the
store's `AUTH`, the admin API's `curl`, MySQL's root. No process reads this directory.

**The development URL's `useSSL=false&allowPublicKeyRetrieval=true` must not reach production.** Without TLS, key
retrieval lets anyone on the network path hand the driver their own RSA key, and then read the password the driver
encrypts with it ([S-6](../defects.md#5-security-and-input)).

### JVM options

Settled by [D-2](../architecture/03-decision-log.md#d-2--java-21-with-generational-zgc), and verified on Temurin 21:

```
-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch
--add-opens java.base/java.nio=ALL-UNNAMED
-Xlog:gc*:file=/var/log/backend/platform-gc.log:time,uptime:filecount=5,filesize=20M
```

Each unit names its own GC log in `/var/log/backend/`: `platform-gc.log`, `gateway-gc.log`, `arena-<name>-gc.log`
and `worker-<id>-gc.log`.

Two traps, both confirmed by running them:

- **Java 8's GC logging flags stop a JDK 21 JVM from starting.** `-Xloggc`, `-XX:+PrintGCDetails`,
  `-XX:+PrintGCDateStamps` and `-XX:+UseGCLogFileRotation` were removed in JDK 9. The JVM exits with
  `Unrecognized VM option 'PrintGCDateStamps'` and writes no application log at all, so the failure looks like the
  application's rather than the flags'.
- **`--add-opens` belongs outside any options variable an operator can override.** Without it, Netty loses its
  fast path for direct buffers. If it lived inside such a variable, tuning the heap would silently drop it.

## 3. j-redis (built and tested)

The site runs two j-redis instances from the same distribution, on different ports and with different data
directories ([D-7](../architecture/03-decision-log.md#d-7--two-j-redis-instances-not-one)): `session` on 6379 and
`events` on 6380. Every process takes the `session` instance as its store. The arena and the worker also take
`BACKEND_EVENTS_STORE`, for the result stream. Leave it unset to run both on one instance.

**The release carries the store** (D-77,
[09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)):

- `backend-store@session` and `backend-store@events` run `/opt/backend/jredis/lib/j-redis-server.jar` on the
  release's runtime, as the user `jredis`;
- each instance's configuration is `/etc/backend/store-<name>.conf`, made from the release's
  `jredis/conf/j-redis.conf`, with its `dir` set to `/var/lib/backend-store/<name>`, which the unit makes private;
- its heap is set in `/etc/backend/store-<name>.env` (`env/store.env.example`).

The store's own settings, its backup and its upgrade are in
[j-redis guide 4](../../j-redis-service/docs/guide/04-run-and-operate.md).

Two things to carry over when sizing:

- `maxmemory` must allow for ZGC, which makes the same dataset's estimate **25–50 % larger** than it was under
  Java 8 ([j-redis docs/06 §6](../../j-redis-service/docs/06-expiry-and-memory.md#6-jvm-heap-sizing)).
- Run the store from an unpacked distribution, never from a build's `target/` directory: a rebuild replaces the jar
  under a running server.

**Each store listens on loopback and on its private address**, both on one `bind` line:

| Store | Machine | `bind` |
|---|---|---|
| `session` | A | `bind 127.0.0.1 10.0.0.1` |
| `session`'s replica, and `events` | B | `bind 127.0.0.1 10.0.0.2` |
| `events`' replica | C | `bind 127.0.0.1 10.0.0.3` |

The processes on the other machines reach a store on its private address. The scripts that run on the store's own
machine reach it on `127.0.0.1` and take no host: `promote-store.sh`, `backup-store.sh`, `store-restore-drill.sh`,
and the runbook's dead-list commands. j-redis's own default, `bind 127.0.0.1` alone, reaches no other machine. The
private address alone would leave every one of those scripts unable to reach the store.

### Replicas (designed 2026-09-29, plan item 12)

Each store has a replica on another machine (§1, D-34), configured as j-redis guide 4 §4.12 describes:
`replicaof <primary> <port>` and `primaryauth` in the replica's configuration, and the same `requirepass` on both.
Every process is given both addresses:

| Setting | Holds |
|---|---|
| `BACKEND_STORE_ADDRESSES` | The `session` store, `host:port,host:port`: the primary and the replica, in any order. When set, it is used instead of the host and port on the command line. `STORE_HOST` and `STORE_PORT` stay set in the env file all the same: the units still pass them, and an empty port refuses the configuration (exit 2), which systemd does not restart |
| `BACKEND_EVENTS_STORE` | The `events` store, as before, now also `host:port,host:port` |
| `BACKEND_EVENTS_STORE_PASSWORD` (or its `_FILE` form) | The `events` store's password, only where it differs from the session store's (§2) |

The store client asks each address for its role, and uses the primary with the highest epoch. After a promotion it
finds the new primary within a reconnect, and it never goes back to a primary with a lower epoch.

**Promoting** is `scripts/promote-store.sh`, run on the replica's machine with the store's configuration file and
the old primary's address. It will not promote while the old primary answers, unless it is told to demote it first.
It promotes, removes `replicaof` from the file (keeping a copy), and prints the new role and epoch. When to run it
is in the runbook ([02 §2](02-runbook.md#2-stateful-primary-failure)).

**The arena and the `events` replica**
([Q-11](../requirements/01-scope-and-nfrs.md#7-open-questions)): after adding a result, the arena waits up to
100 ms for the replica to hold it. It counts the results the replica did not confirm
(`backend_arena_results_unreplicated_total`), and deletes the result's spool file either way.

## 4. nginx

**The front**
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)):
the site has one public address. The provider forwards it to one machine, the front, and that machine's nginx takes
everything players send:

- **443**, over TLS: the API (`/v1/`), the account service (`/account/v1/`, D-117) and the lobby (`/lobby`). nginx
  passes them to the platforms (8080), the account services (8070, their `/v1/`) and the gateways (8090) of all
  three machines, over the private network (`backend-upstreams.conf`). One that fails is passed over. The account
  service's key set is not routed: the platforms read it on the private network.
- **9001**, over TLS: match traffic, by nginx's `stream` module. nginx ends the TLS, and passes the connection in
  plaintext to the arena whose name the TLS handshake gave (the SNI), by the list in `backend-arenas.conf`
  ([05 §3](05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)).
- **80**, in clear, for a client without TLS
  ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)):
  the same API, account service and lobby as 443, and matches. A match without TLS asks for its arena by name, with
  `GET /match/<the grant's tlsName>` and an HTTP upgrade. nginx finds the arena in the same `backend-arenas.conf`
  and passes the connection on, as it passes the lobby's WebSocket. What crosses 80 is readable by anyone on the
  way, passwords and tokens included: the owner's choice (Q-64).

Every machine runs the same nginx and configuration, so another machine can be made the front at once, and the
client holds the one endpoint
([D-13](../architecture/03-decision-log.md#d-13--the-client-holds-an-endpoint-list-no-vip)). The certificate is
[06](06-certificates.md)'s: from the backend's own CA, one certificate for the site's address and every arena's
name. Before D-82, every machine's nginx served its own machine, and the clients dialled the arenas directly
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).

**The release's own nginx** (D-77,
[09 §5](../detailed-design/09-release-and-packaging.md#5-nginx-compiled-for-glibc-228)): nginx 1.30.5, compiled on
Rocky 8 for glibc 2.28 (D-100), with OpenSSL 3.5.9 built in. `backend-nginx.service` runs it from
`/opt/backend/nginx`; the distribution's package is not used. Checked 2026-10-05 in UBI 9.5 with nothing else
installed: proxying to the platform over HTTP and HTTPS (TLS 1.3), and `nginx -t` on the shipped configuration.

**Its logs** are in `/var/log/backend-nginx/`: `access.log`, `stream.log` (where match traffic on 9001 was routed)
and `error.log`. `backend-nginx-logs.timer`, enabled on every machine, rotates them nightly
([D-85](../architecture/03-decision-log.md#d-85--nginxs-logs-rotated-by-a-timer-of-the-releases-own-and-kept-a-number-of-days)).
Its script, `rotate-nginx-logs.sh`:

1. renames each log with a UTC time stamp;
2. has nginx reopen its logs (`USR1`);
3. compresses the renamed files;
4. deletes those older than `KEEP_DAYS`: 14 in the unit, 1 to 365 (change it with `systemctl edit`).

The front's `access.log` grows by about a thousand lines a second at 10 000 players. Without the rotation the disk
fills, and players' addresses are kept for good. Checked as written by `backend/scripts/check-nginx-logs.sh` (the
release's nginx on a loopback port, a few seconds), and under systemd's sandbox by `check-units-el9.sh`.

**The configuration is `backend/deploy/nginx/`**, installed to `/etc/backend/nginx/` (the release's
`nginx-conf/`):

| File | What it holds |
|---|---|
| `nginx.conf` | the main file, which the distribution's package used to bring: the workers, the logs, and the `http` and `stream` blocks |
| `backend.conf` | the server on 443 (TLS) and on 80 (in clear): the API, the account service (`/account/v1/`), the lobby and `/match/`, with `/match/`'s map |
| `backend-upstreams.conf` | the three machines' platforms, account services and gateways |
| `backend-stream.conf` | match traffic's server on 9001, and its map from the TLS name |
| `backend-arenas.conf` | the arenas' names and private addresses, the one list both maps include (D-115) |
| `backend-platform.conf` | the proxy settings that every platform location shares |

Its numbers answer to numbers in the code, and the files say which:

| nginx | because |
|---|---|
| lobby `proxy_read_timeout 150s` | the gateway closes a silent socket at 120 s; the gateway should decide, not nginx |
| platform upstream `keepalive 32`, `keepalive_timeout 20s` | the platform drops idle connections at 30 s and keeps at most 1 000. The other way round, it closed connections nginx was about to reuse, and nginx does not retry a POST: a 502. Measured on the JDK server, 63 of 16 806 requests failed at its default idle limit of 200 and none at 5 000 |
| `limit_req` 2/s, burst 30 on the four that make or open a player's session (`/v1/sessions`, `/v1/accounts`, `/v1/guests`, `/v1/accounts/link`, each matched exactly, as platform matches them: S-18) | a cheap outer guard in front of Java; the precise per-address limit on guests is the platform's ([04 §1](../detailed-design/04-platform-services.md#1-auth-and-sessions)), the limits on passwords the account service's (D-117) |
| `= /v1/account-events` answered 404 | the account service's events reach a platform on the private network; the internet has no business posting them (D-117) |
| `client_max_body_size 4k` | the platform reads at most 4 KiB |
| `X-Forwarded-For $proxy_add_x_forwarded_for` | the platform believes the last entry, from a peer on this machine or one in `BACKEND_TRUSTED_PROXIES`, the front's nginx (D-82) |
| port 80: the same server, in clear, and `/match/<name>` to the arena by `backend-arenas.conf` | for the clients without TLS, the owner's choice ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included), Q-64): what they send crosses in clear. No redirect to 443: a login sent to `http://` has already crossed, and a client that follows a 301 turns the POST into a GET |
| `/match/`: `proxy_pass_request_headers off`, `X-Forwarded-For $remote_addr`, `proxy_read_timeout 60s`; a name not in the list, 404 | the arena reads the request line and nginx's four headers alone, so the phone's address is the one nginx saw, never one the phone sent; 60 s is longer than the arena's own 30 s without a frame, as on 9001 (D-115) |
| the platforms' and gateways' upstreams, all three machines | the front passes requests over the private network; one that fails is passed over (`max_fails`, 10 s) |
| `stream` on 9001: TLS ended, `proxy_timeout 60s` | longer than the arena's own 30 s without a frame, so the arena decides a connection is dead |
| `worker_rlimit_nofile` and the unit's `LimitNOFILE` 262 144 | the front holds every player's lobby and match connection, two descriptors each |
| TLS 1.3 and 1.2, ECDHE with AES-GCM or ChaCha20 only | the same versions as the arenas (§8); nothing without forward secrecy |
| `ssl_session_tickets off` | nginx never rotates the ticket key, so tickets would undo forward secrecy until the next restart; resumption still works from the session cache (checked: `Reused`) |

A location that sets any `proxy_set_header` inherits none from the server block. So the lobby's location, and
`/match/`'s, set every header they need. There is no HSTS header: only browsers read it, and there is no browser
client ([D-1](../architecture/03-decision-log.md#d-1--unity-native-mobile-client-no-browser-build)).

**Run for real on 2026-09-26** with nginx 1.24, in front of the release, with a test CA shaped like a public one (a
root and an intermediate):

- `nginx -t` passed;
- logins and the lobby were served, with the chain verified;
- 10 bots went through it into a TLS arena that trusted only the root;
- TLS 1.2 negotiated `ECDHE-ECDSA-AES256-GCM-SHA384`, and refused a client that offered only suites without
  forward secrecy.

The certificate is §11.

## 5. Rolling deployment

Which processes a change reaches, and how to roll out a change to the schema or to the wire:
[development/07](../development/07-after-a-code-change.md).

**On each machine**, unpack the new release beside the running one, and switch to it by the link (§2):

1. `tar -xzf backend-<version>.tar.gz -C /opt`.
2. A unit or a drop-in that changed: copy it from the release's `systemd/`.
3. An nginx file that changed: merge it by hand into `/etc/backend/nginx/`. Keep this site's `server_name`, its
   upstreams and its list of arenas (`backend-arenas.conf`), which a copy would overwrite.
4. `systemctl daemon-reload`, then `systemctl reload backend-nginx`, which tests the configuration first.
5. `ln -sfn /opt/backend-<version> /opt/backend`.

The release carries its runtime, MySQL, nginx and j-redis too
([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)). A new version of any of them is a new
release, restarted as below: MySQL last, and only when it changed. A process runs the release it started from until
it is restarted, so the switch changes nothing by itself. Then restart the processes machine by machine, in this
order.

The order follows from the availability tiers in
[architecture/02 §2](../architecture/02-availability.md#2-degradation-is-graded-not-binary):

1. `worker`: players do not notice; the result stream in j-redis holds its place.
2. `platform`, one instance at a time: requests in flight fail, and the app retries them.
3. `gateway`, one at a time: clients reconnect to another endpoint.
4. `arena`, one at a time, **drained**: it stops accepting joins, lets matches with an end finish, then stops. This
   is the slow step, and the reason `TimeoutStopSec` is generous: twelve minutes, for eleven of drain
   ([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)). Meanwhile the
   other arenas, on the same machine and on the others, take the new players and matches.
5. The stateful roles (MySQL, j-redis) only in a maintenance window, since promotion is manual
   ([D-8](../architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)).

**Flyway migrates the schema at every `worker` and `platform` start** (§9). So the first worker restarted applies a
release's migrations while the platforms of the release before still run on them. That is why migrations are
forward-only, expand then contract
([06 §8](../detailed-design/06-persistence-mysql.md#8-migrations)). A rollback is the link put back and the same
restarts; the schema stays where the new release took it.

Check each step as §7 says: `curl http://10.0.0.1:8080/health` on the machine, at its own private address, where
the platform listens since D-82; then the journal, and the metrics.

**The release that put results on a stream** (2026-09-29, plan item 10) needs the `events` j-redis on 2.1.0 before
any arena or worker of that release starts: a 2.0 store refuses the stream commands. Upgrade the store first, in
its window ([j-redis guide 7](../../j-redis-service/docs/guide/07-upgrade-guide.md)); its files carry over. Then
follow the order above. A mixed fleet loses nothing. An arena of the old release pushes to the list, which the new
workers drain into the stream as an inbox. A new arena's results wait in the stream, kept 24 hours, for the new
workers.

**The release on j-redis 2.2.1** (2026-10-01, plan item 50): upgrade both stores' servers first, each in its window,
the replica before the primary. Then follow the order above.

- A 2.2.1 server closes its subscribers when it is made a replica, so that they look for the primary again.
- A 2.2.1 client, in the release, pings its subscription, so that a lost machine is noticed in seconds
  ([03 §5](../detailed-design/03-gateway.md#5-push-routing)).

Either half without the other is no worse than 2.2.0, and the files carry over.

**The release on j-redis 2.2.2** (2026-11-25, plan item 139): only `HELLO`'s reply changes, its `role` now `primary` or
`replica` (D-52), which nothing in the backend reads. The stores upgrade as in any release, with no step of their own,
and the files carry over.

## 6. Still to be written

| Item | Blocked by |
|---|---|
| The certificate | Settled 2026-10-19 ([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)): no domain, the backend's own CA, on the front's nginx alone ([S-6](../defects.md#5-security-and-input)); issuing, checking, installing, renewing and watching it are built (§11, [06](06-certificates.md)) |

**No longer open: the store's copies off its machine.**
[D-31](../architecture/03-decision-log.md#d-31--the-store-is-not-copied-off-its-machine-on-a-timer-before-replication)
put no scheduled copy in place before replication, because a copy on a timer recovers nothing that a store loss
costs:

- the boards come back from MySQL (`backend-leaderboard-rebuild`,
  [05 §8](../detailed-design/05-worker-and-events.md#8-rebuilding-after-a-store-loss));
- sessions and tickets can be thrown away: players log in again;
- the results in flight are younger than any copy;
- the dead list is copied out when it alerts ([runbook §3](02-runbook.md#3-per-component-procedures)).

Replication is built (§3, D-34): each store has a replica on another machine, which holds the results in flight
too. `backup-store.sh` stays, for a copy by hand before an upgrade.

## 7. Installing a machine

**Step by step, from a clone of the repository: the [install guide](03-install-guide.md).** This section gives the
reasons, and records what was checked.

**Built and run for real on 2026-09-26**: the release, and the four units **as shipped**, installed as runtime
units on the development machine. Only the paths, the heap sizes and the absent `backend` user differed.

- All four came up under their sandbox, including the arena's native epoll transport.
- 20 bots played over loopback, with no nginx; 20 results reached MySQL.
- All four stopped cleanly, with nothing in their journals.
- `systemd-analyze security` rates each unit **3.1 (OK)**, down from 9.0 before the sandbox.

The steps:

1. **The release.** `backend/scripts/make-release.sh` builds `backend-<version>/`, with one `lib/<process>/` per
   process type, not one shared directory. `handoff`'s module graph keeps the MySQL driver off an arena, and a
   shared directory would put it back (checked: none in `lib/arena` or `lib/gateway`). Unpack it to
   `/opt/backend-<version>` and point `/opt/backend` at it (§2).
   Nothing else is installed: the release carries the Java runtime the processes run on, MySQL, nginx and j-redis
   ([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)). What it takes from the system is what
   a RHEL 9 server installation has: glibc, `libstdc++`, `ncurses-libs`, systemd, util-linux, `tar`, `gzip`,
   `findutils`, `procps-ng`, OpenSSL's command line, and on the backup machine `openssh-clients` and `rsync`
   ([09 §4.2](../detailed-design/09-release-and-packaging.md#42-what-the-host-still-provides)).
   `backend/scripts/check-release-el9.sh` runs a release in RHEL 9's userspace with nothing else installed
   ([09 §7](../detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).
2. **The users.** `useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin backend`, and the same for
   `backend-account` (the account service's, D-117) and `nginx` on every machine, for `jredis` where a store runs, and for `mysql` (with its home
   `/var/lib/backend-mysql`) on the database's machines (§9). The backup machine's user, `backend-backup`, is
   §10's.
3. **Credentials.** `/etc/backend/credentials/`, `root:root 0700`, holding `db-password`, `account-db-password` (the
   account service's database user's) and `store-password`, each `0600` (§2, Secrets). These are the values that step 4 writes into the settings files; no process reads this
   directory. The install guide also makes MySQL's root password and the backups' there (`mysql-root`,
   `backup-password`, `binlog-password`, `scratch-root`).
   The processes do read `/etc/backend/tls/`: `platform`, `worker` and the account service read the MySQL CA (§9), and the gateway reads
   nginx's certificate for its expiry metric (§11). Create it with `install -d -m 0755 /etc/backend/tls`, and keep
   `/etc/backend` traversable by `backend`.
4. **Settings.** `/etc/backend/<process>.env` from `deploy/env/*.env.example`, every one `root:root 0600`, with the
   process's secrets at its end (§2, Secrets; D-102). The account service's, `account.env`, ends with its signing key
   too, the line `scripts/account-key.sh` prints, made once and copied to every machine (10 §6.1).
   - One `arena-<name>.env` per arena instance, each with its own port.
   - One `worker-<id>.env` per worker instance (`worker-instance.env.example`), with its metrics port. It is read
     after `worker.env`, so it wins. A drop-in's `Environment=` would not win, since a file's settings override it
     (checked with systemd 255).
5. **Units.** Copy the release's `systemd/*.service` and `systemd/*.timer` to `/etc/systemd/system/` (as the install
   guide's step 5 does), then run `systemctl daemon-reload` and enable:
   - `backend-store@<name>` where a store runs (§3);
   - `backend-mysql` on A and C (§9);
   - `backend-account`, `backend-platform` and `backend-gateway`;
   - `backend-worker@<id>` and `backend-arena@<name>`, each id and name unique across the fleet, and stable (§2).

   Pin each arena to its own cores with a drop-in (`CPUAffinity=`), since the core ranges belong to the machine.
   The backup machine's units and timers are §10's.
6. **nginx and the certificate.** Copy the release's `nginx-conf/*.conf` into `/etc/backend/nginx/`, the same on
   every machine. Then set this site's addresses: the site's public address in `backend.conf`'s `server_name`, the
   three machines' private addresses in `backend-upstreams.conf` (the platforms, the account services and the
   gateways), and each arena's name and private address in
   `backend-arenas.conf` (D-82, D-115, [05 §3](05-ports-and-firewall.md#3-the-arenas-their-ports-and-their-names)).
   Then install the certificate, by §11 and [06](06-certificates.md). Enable `backend-nginx`, and
   `backend-nginx-logs.timer`, which rotates its logs nightly (§4).
7. **Check.** Run `curl http://10.0.0.1:8080/health` on the machine, at its own private address (D-82). The journal
   of each unit (for example `journalctl -u backend-arena@a1`) shows INFO lines and no ERROR.

**Metrics** are served in the Prometheus text format at `GET /metrics`, on the loopback address that
`BACKEND_METRICS_ADDR` names. When it is unset there is no metrics server, so a process run by hand takes no port.
The env examples use these ports:

| Port | Process |
|---|---|
| 9101 | the platform |
| 9103 | the gateway |
| 9102, 9112 | the first and the second worker (on C) |
| 9110, 9111, 9113 | a machine's first, second and third arena |

No library serves them, by choice: the format is a few lines (`common/Metrics`). Micrometer is in the offline
bundle, but it would add a dependency for nothing. Every process reports `backend_process_uptime_seconds`,
`backend_jvm_*` and `backend_store_*` (timeouts, fail-fasts, reconnects, connected, server errors). With
`BACKEND_EVENTS_STORE` set, it reports the same again as `backend_events_store_*`. The rest come from each design's
"what to measure" table:

| Process | Metrics |
|---|---|
| arena | `players`, `rooms`, `rooms_failed_total`, `tick_p99_seconds{room}` (last 10 s), `tick_overruns_total`, `snapshots_sent_total`, `snapshots_skipped_total`, `results_published_total`, `results_spooled_total`, `results_unreplicated_total` (not confirmed by the events replica within 100 ms, Q-11), `spool_failures_total`, `connections_dropped_total{reason}` (join_deadline, idle, stalled, tls_handshake, and behind the front proxy_line, D-105), `clients{profile}`, `snapshot_bytes_total{profile}`, `connection_bytes_per_second{profile}` (a histogram, each connection's own rate, [02 §13](../detailed-design/02-networking.md#13-what-to-measure)), `snapshots_held_total`, `profile_steps_total{direction}` ([02 §8](../detailed-design/02-networking.md#8-traffic-profiles)), `stays_waiting`, `resumes_total`, `stays_expired_total` ([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)), `joins_total{outcome}` (joined, bad_ticket, no_room, claim_failed, removed for a player an operator took out within a ticket's life; against platform's tickets issued, the joins that never arrived), and with TLS `tls_certificate_expiry_timestamp_seconds` |
| platform | `responses_total{status}`, `request_seconds{route}` (a histogram), `logins_total{outcome}` (ok, invalid_credentials, banned, throttled, busy), `purchases_total{outcome}` (bought, already_bought, and each refusal), `hasher_line` (168 is full), `open_tickets_issued_total`; from the matcher, `matches_made_total{mode}`, `queue_wait_seconds{mode}` (a histogram), `queue_players{mode}`, `confirms_total{outcome}` (made, declined, withdrawn, lapsed, no_room) and `match_tickets_issued_total{mode}` ([04 §11](../detailed-design/04-platform-services.md#11-what-to-measure)); `pushes_unheard_total` (a push to a registered player that no gateway heard, 03 §5) |
| gateway | `connections`, `authenticated_total`, `errors_total{code}`, `pushes_total{outcome}` (delivered, not_here, malformed, broadcast for a notice to every lobby; held and dropped for a slow connection), `slow_closed_total` ([03 §8](../detailed-design/03-gateway.md#8-backpressure-and-slow-clients)), `platform_seconds{route}` (a histogram, each call to platform, [03 §10](../detailed-design/03-gateway.md#10-what-to-measure)), `unwritable_seconds{end}` (a histogram, each spell a lobby connection could take no more, drained or closed), `store_subscribed` (1 while the subscriber's connection, which every push arrives on, is up) and `resubscribed_total` ([03 §5](../detailed-design/03-gateway.md#5-push-routing)), and with `BACKEND_EDGE_CERTIFICATE` set `edge_certificate_expiry_timestamp_seconds` (nginx's certificate, §11) |
| worker | `applied_total`, `duplicates_total`, `dead_lettered_total`, `deferred_total`, `failed_total`, `unranked_total`, `queue_depth` (the stream group's lag, entries not yet delivered, plus the inbox list), `pending` (delivered, not yet acknowledged), `trimmed_unapplied_total` (results trimmed from the stream before anyone applied them), `dead_letter_depth`, `deferred_depth`, `retention_deleted_total`, `retention_seconds` (how long this worker's last retention run took, NaN before one), `tournament_failures_total` (tournament steps that failed and are tried again in 5 s), `ledger_mismatches` and `ledger_checked_timestamp_seconds` (the fleet's last daily check, the same on every worker); `pushes_unheard_total` (as platform's); the backups, read from `backup_run` at each scrape (§10, D-71): `backend_backup_succeeded_timestamp_seconds{kind}` (`dump`, `proof`, `offsite`: when each last succeeded, NaN for never), `backend_backup_failed{kind}` (1 when its last run failed) and `backend_backup_restore_seconds` (the last successful proof's restore and replay); the growth (D-72): `backend_mysql_table_rows{table}` (InnoDB's estimate of the growing tables' rows); the replicas' health (architecture/02 §7, D-58): `backend_mysql_replica_lag_seconds{host}` and `backend_mysql_replica_up{host}` (each MySQL replica, by the heartbeat, read every 5 s), and `backend_store_replicas`, `backend_store_replica_behind_bytes`, `backend_store_replica_ack_seconds` (the store's replicas, from its primary), the same under `backend_events_store_` |

The metrics were verified against a live run of 20 bots: every value matched what happened (20 players and 20
connections mid-play, 20 logins, 20 results applied, and the queue empty afterwards).

**Alert first on:**

- `backend_worker_dead_letter_depth` above 0;
- `backend_arena_rooms_failed_total` rising;
- `backend_arena_tick_p99_seconds` above 0.015 for five minutes (NFR-1b: the whole tick, simulation and snapshots;
  0.040 is the deadline itself, past which ticks overrun);
- `backend_store_connected` at 0;
- `backend_worker_queue_depth` growing for minutes;
- `account_hasher_line` at 168, the account service's password hasher (D-117);
- `backend_gateway_store_subscribed` at 0, or `backend_platform_pushes_unheard_total` rising: a gateway's
  subscription is lost or astray, and every push to its lobbies with it
  ([03 §5](../detailed-design/03-gateway.md#5-push-routing));
- `backend_arena_tls_certificate_expiry_timestamp_seconds - time()` or
  `backend_gateway_edge_certificate_expiry_timestamp_seconds - time()` under 14 days;
- `backend_worker_failed_total` rising for more than a few minutes: an entry the worker keeps failing on is
  retried, not dead-lettered ([05 §4](../detailed-design/05-worker-and-events.md#4-failure-handling));
- `backend_worker_trimmed_unapplied_total` above 0: a result was lost, pending longer than the stream keeps one
  (`BACKEND_RESULT_RETENTION_HOURS`) and then trimmed; its ID is in the worker's log;
- `backend_arena_results_unreplicated_total` rising: the `events` replica is down or behind, and results live on
  one machine until it is back;
- `backend_worker_ledger_mismatches` above 0, or `backend_worker_ledger_checked_timestamp_seconds` more than two
  days old;
- `backend_worker_tournament_failures_total` rising steadily: tournaments stop advancing;
- and the backups' and the growth's metrics, which [runbook §5](02-runbook.md#5-what-to-watch) lists with their
  thresholds. The [runbook](02-runbook.md#3-per-component-procedures) says what to do.

**What watches the metrics.** Each process serves its metrics on loopback (above), and the worker reports the
backups from `backup_run`. Every 15 s, every platform's watch:

1. reads its machine's metrics, the machine's disks and memory, and the other machines' `/health`;
2. evaluates the runbook's §5 as rules;
3. puts what fires on the admin API and in its journal, and, once the owner names one (Q-60), at the webhook
   ([08](08-monitoring.md),
   [04 §12](../detailed-design/04-platform-services.md#12-the-watch-monitoring-and-alerts-designed-2026-10-24-plan-item-100),
   D-86).

Nothing is installed or bundled for it (C-6). Until a webhook is named, alerts are read on a machine, and the daily
checks ([runbook §6](02-runbook.md#6-routine)) stand. The API's latency by route and matchmaking's waits by mode are
histograms ([04 §11](../detailed-design/04-platform-services.md#11-what-to-measure)). So are the gateway's calls
to platform by route, its connections' unwritable spells, and each connection's snapshot bytes a second.

**Logging** goes to the journal at INFO (`common/logback.xml`). Before the release was run as a release, it had two
different mistakes:

- The arena and the platform had **no logging backend at runtime** (test-scoped, or missing), so every error they
  logged went nowhere.
- The worker and the gateway had no logging configuration, so logback's default of DEBUG flooded them.

The development rig hid both, because its classpaths included test scope. To change the level, override the file
with `-Dlogback.configurationFile=/etc/backend/logback.xml`; no rebuild is needed. An override should keep the
queue: the `QUEUED` appender with `neverBlock`.

**Lines are queued, and a stuck journal costs lines, not service** ([T-10](../defects.md#4-concurrency)).

- Written directly, as first shipped, a journald that stopped reading froze a room mid-tick. It also froze the
  watchdog meant to catch that, and the shutdown meant to publish the room's results. In the drill, 31 of 60 bots
  got in, and the arena ignored SIGTERM for over 30 s.
- Queued, the same drill played on at 15 snapshots a second, and stopped in 2 s with every result in the store.
- Up to 8 192 lines wait in the queue. Beyond four fifths of that, INFO lines are dropped; beyond all of it,
  everything is. Each process writes the queue out at the end of its shutdown, waiting at most a second.

So **a gap in the journal around a journald problem is expected**. The metrics carry on regardless.

## 8. TLS for match traffic

**On the site, the front's nginx ends match traffic's TLS**
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)).
The arenas behind it speak plaintext on the private network. Each arena announces the name that the client's TLS
handshake gives (`BACKEND_ADVERTISE_TLS_NAME`), which nginx routes by. These arenas have no keystore, and an arena
given both a TLS name and a keystore refuses to start.

**Before the phone's own bytes, nginx sends the arena one of two things**, and an arena with a TLS name reads it
first:

- from 9001, a line naming the phone's address (`proxy_protocol on`,
  [D-105](../architecture/03-decision-log.md#d-105--an-arena-behind-the-front-knows-the-phones-address-nginxs-proxy-line-read-once));
- from 80, for a match without TLS
  ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)),
  the request `GET /match/<name> HTTP/1.1` with nginx's own headers, 1 KiB at most, the phone's address in
  `X-Forwarded-For`. The arena answers `101 Switching Protocols`.

The arena's log names the phone's address: `player 123 joined from 198.51.100.7:51234`, or, through 80, the
address alone, since nginx's HTTP proxy does not pass the port. A connection that begins with anything else is
closed and counted (`connections_dropped_total{reason="proxy_line"}`).

**The rest of this section is the arena's own TLS**, for an arena reached directly: a drill, or a machine alone
with no nginx in front. It is not the site's layout.

**Built and run for real on 2026-09-26** ([S-6](../defects.md#5-security-and-input)); what it waits for is a
certificate (§11).

- An arena serves TLS when `BACKEND_ARENA_TLS_KEYSTORE` names a PKCS#12 keystore, with its password in
  `BACKEND_ARENA_TLS_PASSWORD_FILE` (or `_PASSWORD`, by the rules in §2).
- It then announces `tls` in the directory, platform's grant carries it, and the client connects accordingly
  ([02 §1](../detailed-design/02-networking.md#1-transport)).
- Without a keystore it serves plaintext, and says so at start. That is fine on a developer's machine. In front of
  players, a join ticket sent in plaintext can be used by anyone on the network path before its owner uses it. (For
  the clients without TLS on the site's 80, D-115 accepts that cost, by the owner's choice.)

**The certificate must name `ADVERTISE_HOST`.** Clients verify the certificate against the host they were told to
dial. So the arena refuses to start (exit 2) when no subject alternative name covers that host: an exact name, a
wildcard for one leftmost label, or the address itself. In practice `ADVERTISE_HOST` is then a DNS name, since
public CAs certify IP addresses briefly if at all, or an address that a CA of the backend's own certifies
([06](06-certificates.md)).

**It also refuses**, each checked against the release:

- a keystore it cannot read;
- a wrong password;
- a store with no private key (a trust store given by mistake would otherwise fail every handshake with "no cipher
  suites in common");
- a certificate that has expired or is not valid yet, the intermediates served with it included.

**The keystore** is made from the PEM files a CA issues, by `scripts/install-certificate.sh` (§11), with a password
the script generates once. It uses AES-256 for the key and the certificates and a SHA-256 MAC, named explicitly, so
the keystore is the same whichever OpenSSL the machine has (D-103).

**The settings.** The script writes both files to `/etc/backend/tls/`: `arena-tls.p12` and `arena-tls-password`,
readable by root and the `backend` group (0640). The arena reads them itself, at start (D-102). The arena's
`arena-<name>.env` names them, in `BACKEND_ARENA_TLS_KEYSTORE` and `BACKEND_ARENA_TLS_PASSWORD_FILE`, as its example
shows. This was run with the shipped unit in the units' check
([09 §7](../detailed-design/09-release-and-packaging.md#7-how-it-is-verified)): the keystore made by the script,
the arena started by its unit, and `openssl s_client -verify_ip` accepting the certificate on the arena's port.
Earlier, by hand, a client dialling another name was refused with "hostname mismatch", and counted.

**A renewal takes a restart.** The arena reads its keystore once, at start, and an arena's restart ends every
connected player's stay (their results are published first). So a renewal only replaces the file, and the weekly
restart ([runbook §6](02-runbook.md#6-routine)) or the next deploy picks it up. What each arena actually serves is
`backend_arena_tls_certificate_expiry_timestamp_seconds`, the earliest expiry in its chain. Alert when it is less
than 14 days away; the arena also warns at start inside that window. If a certificate source turned out to issue
certificates that last only days, reloading without a restart would become worth building.

**Failed handshakes**, and handshakes not finished by the 10 s join deadline, are counted in
`backend_arena_connections_dropped_total{reason="tls_handshake"}`. Scanners and plaintext clients make a steady
trickle. A jump after a certificate change means clients are refusing the new certificate, which shows nowhere else
on the server.

**The bots** (`tools/BotClient`) use TLS when the grant says so.

- With `BACKEND_BOT_TRUSTSTORE` (a PKCS#12 file, its password in `BACKEND_BOT_TRUSTSTORE_PASSWORD`), they check the
  certificate and the host, as a client must. Without it, they trust any certificate and print a warning: that is
  for a development arena only.
- `BACKEND_BOT_PROFILE` (`saver`, `mobile` or `high`) makes them ask for a traffic profile.
- `BACKEND_BOT_VIA=host:port` makes them dial there instead of the arena, for example a proxy that shapes the link
  ([02 §8](../detailed-design/02-networking.md#8-traffic-profiles)).
- They send `Leave` at the end of a run, so their results are published at once, rather than after the minute a
  lost connection's stay waits ([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).

## 9. MySQL

**Checked on 2026-09-26 against a MySQL 8.0.46 instance set up from nothing this way**, beside the development one:
the release's `platform` migrated the empty database and served a registration and a login, with every pooled
connection on TLS 1.3, and the backup script ran as its own user. Each grant below was checked by taking it away.
This was not yet done on RHEL 9 itself, whose AppStream ships 8.0 too. Flyway runs at every `platform` and `worker`
start, so a release needs no separate migration step.

**The release's own MySQL**, 8.4.11 LTS (D-77, Q-56,
[09 §6](../detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)), is used, not the distribution's
package. Checked 2026-10-05: every drill scenario on it; a primary and a replica, with the replica promoted halfway;
the backup drill; the backend's tests against it; and, in UBI 9.5 with nothing else installed, initialised, started
and migrated by the platform.

**Install:**

1. the system user `mysql` (§7);
2. `/etc/backend/mysql/backend.cnf`, from the release's `mysql-conf/backend.cnf.example`, whose header initialises
   the data directory (`/opt/backend/mysql/bin/mysqld … --initialize-insecure --user=mysql`);
3. `systemctl enable --now backend-mysql`.

Its tools are in `/opt/backend/mysql/bin/`; the scripts find them there by themselves.

**Settings**, in `/etc/backend/mysql/backend.cnf` (the example carries them, with the data, socket and log paths).
The transport and TLS lines were exercised; the sizes follow the design and were not:

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

**The certificate** comes from a private CA: nothing outside the private network connects, so a public CA buys
nothing. It must name the host in `BACKEND_DB_URL`, as an IP address or a DNS name, because the processes check it.
The CA's key belongs somewhere safer than the database machine.

```
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=backend MySQL CA" \
    -keyout ca-key.pem -out ca.pem
openssl req -newkey rsa:2048 -nodes -subj "/CN=db" -keyout server-key.pem -out server.csr
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days 825 \
    -extfile <(printf "subjectAltName=IP:10.0.0.1\nextendedKeyUsage=serverAuth") -out server-cert.pem
```

On the database machine (A, and C for the replica), put the three files where `backend.cnf` reads them, with the
key readable by `mysqld` alone. `ca-key.pem` stays off the machine:

```
install -d -m 0755 /etc/mysql-tls
install -m 0644 ca.pem server-cert.pem /etc/mysql-tls/
install -m 0640 -o root -g mysql server-key.pem /etc/mysql-tls/
```

**The processes' trust store** holds that CA and nothing else, as PKCS#12 with no password. It holds only a public
certificate, so there is no secret to put in a URL or a unit. It is `/etc/backend/tls/mysql-ca.p12`, mode 0644, on
every machine that runs `platform` or `worker`:

```
/opt/backend/runtime/bin/keytool -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE \
    -importcert -noprompt -alias mysql-ca -file ca.pem \
    -storetype PKCS12 -keystore /etc/backend/tls/mysql-ca.p12 -storepass unused
```

**The URL**, in `platform.env` and `worker.env`:

```
BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12
```

- **`sslMode=VERIFY_IDENTITY` alone**, as the examples had it until this was run, **cannot connect**: the driver
  checks the certificate against the JVM's public CAs, and fails with "Path does not chain with any of the trust
  anchors".
- **Dialled by a name the certificate does not carry**, it fails with "Server identity verification failed". That
  is the check doing its job.
- **The trust store is named in the URL** rather than set for the whole JVM (`javax.net.ssl.trustStore`), which
  would replace the public CAs for every other TLS connection the process makes.
- **The URL never reaches a log**: Flyway prints it as `********`, and the settings are printed without their query
  string.

**Users.** The application's user may change the schema, because Flyway runs as that user. It may not drop
anything, and nothing needs it to. The schema is a baseline, `V1__schema.sql` and `V2__seed.sql`
([D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)),
which creates the tables and inserts the rows a first launch needs. Later migrations are forward-only. These grants
were checked by applying the migrations of the time (V1 to V3) from empty, not yet the baseline:

```sql
CREATE DATABASE backend;
CREATE USER 'backend'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON backend.* TO 'backend'@'10.0.0.%';

-- the account service's own (D-117, 10 §6.3): the same grants, on its database alone; Flyway runs as it too
CREATE DATABASE account;
CREATE USER 'account'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON account.* TO 'account'@'10.0.0.%';

-- both from the backup machine (§10), never from the database's own
CREATE USER 'backup'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, RELOAD, REPLICATION CLIENT ON *.* TO 'backup'@'10.0.0.%';
CREATE USER 'binlog'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'binlog'@'10.0.0.%';
```

The backup account records what each backup step did
([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery), D-71). That needs one grant more,
given after the first start has migrated, because the table must exist for it. `RELOAD`, which the account already
has, is also what lets the copy off the site flush the binary logs:

```sql
GRANT INSERT ON backend.backup_run TO 'backup'@'10.0.0.%';
```

**The failover account** is for `promote-mysql.sh` on the replica's machine (runbook §2, D-35). Create it on the
primary; replication carries it to the replica. The script reaches its own server by the socket and the old primary
over TLS, so there are two accounts of one name and password. Each grant was checked by taking it away
(2026-09-29):

| Grant | Needed for |
|---|---|
| `SYSTEM_VARIABLES_ADMIN` | `SET PERSIST` |
| `CONNECTION_ADMIN` | `offline_mode`, and connecting while it is on |
| `REPLICATION_SLAVE_ADMIN` | `STOP REPLICA` |
| `RELOAD` | `RESET REPLICA ALL` |

The epoch's and the heartbeat's tables must exist for their grants, so those three lines come after the first starts,
the platform's and the account service's, have migrated. The account service's own epoch is raised by a promotion
too (O-103), and a promotion refuses, before it touches anything, when it cannot read it:

```sql
CREATE USER 'failover'@'localhost' IDENTIFIED BY '…';
CREATE USER 'failover'@'10.0.0.%'  IDENTIFIED BY '…' REQUIRE SSL;
GRANT SYSTEM_VARIABLES_ADMIN, CONNECTION_ADMIN, REPLICATION_SLAVE_ADMIN, RELOAD ON *.*
      TO 'failover'@'localhost', 'failover'@'10.0.0.%';
GRANT SELECT ON performance_schema.* TO 'failover'@'localhost', 'failover'@'10.0.0.%';
GRANT SELECT, UPDATE ON backend.ha_epoch TO 'failover'@'localhost', 'failover'@'10.0.0.%';
GRANT SELECT, UPDATE ON account.ha_epoch TO 'failover'@'localhost', 'failover'@'10.0.0.%';
-- The heartbeat a promotion reads to say what it would lose (plan item 59): the baseline
-- creates ha_heartbeat and its one row, as it does ha_epoch's.
GRANT SELECT ON backend.ha_heartbeat TO 'failover'@'localhost', 'failover'@'10.0.0.%';
```

Its option file is `/etc/backend/credentials/mysql-failover.cnf`, root's, `0600`, on the replica's machine. The TLS
settings are in a group of their own, which only the connection to the old primary reads. Put in `[client]`, they
would make the client try TLS on the socket too, check the certificate against "localhost", which it does not name,
and fail.

```ini
[client]
user     = failover
password = …
socket   = /run/backend-mysql/mysqld.sock

[client_remote]
ssl-mode = VERIFY_IDENTITY
ssl-ca   = /etc/mysql-tls/ca.pem
```

**The passwords and the grants' reasons:**

- The application's password goes in `/etc/backend/credentials/db-password`, and from there into the settings
  files (§2).
- Without `RELOAD`, the dump is refused (`FLUSH TABLES`); without `REPLICATION CLIENT`, too
  (`SHOW MASTER STATUS`).
- `PROCESS`, which the script used to ask for, is not needed, and would show the user every session's statements.
- The dumps and the store's copies are written private (0700 directories, 0600 files). Until 2026-09-26 every
  local user could read them ([S-10](../defects.md#5-security-and-input)).

### The replica, on C (plan item 13, D-35)

**GTIDs on both servers, and the clone plugin**, which makes a replica in either direction. Add to `backend.cnf`:

```
server_id                = 1              # 2 on C: each server of the pair its own
gtid_mode                = ON
enforce_gtid_consistency = ON             # the backend's statements pass: the drill's servers run
                                          # every migration and scenario with it
plugin-load-add          = mysql_clone.so
```

A primary already running without GTIDs has them turned on live, one statement at a time. This was rehearsed under
steady writes: 40 of 40 writes kept, in about ten seconds:

```sql
SET PERSIST enforce_gtid_consistency = WARN;   -- then the error log should say nothing new
SET PERSIST enforce_gtid_consistency = ON;
SET PERSIST gtid_mode = OFF_PERMISSIVE;
SET PERSIST gtid_mode = ON_PERMISSIVE;
SHOW STATUS LIKE 'Ongoing_anonymous_transaction_count';   -- until 0
FLUSH BINARY LOGS;
SHOW BINARY LOG STATUS;   -- until the binlog stream's /var/lib/backend/binlog holds this file
SET PERSIST gtid_mode = ON;
```

**Why the flush, and the wait for the stream.** The binlog stream must be on a binary log begun after the last
transaction without a GTID before the mode reaches `ON`. Otherwise a stream that is behind, or restarting, resumes in
a file written without GTIDs. The server refuses to send that file once GTIDs are on, and the stream stops for good
([DOC-38](../defects.md#7-documentation)). MySQL begins a new binary log at each change of the mode. But a
transaction begun before `ON_PERMISSIVE` stays without a GTID, and is written in the log that change began: hence
the flush, once none is left. The two-machine page does the same, and its check runs it with the stream stopped
([10 §3](10-two-machines.md#3-on-a-mysql-and-the-stores-on-the-private-network)).

The backups work the same with GTIDs on, since the restore's replay drops them
(defect [D-31](../defects.md), [06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)).
Checked: restoring onto the source's own server, onto another server with GTIDs on and one with them off, and from
the stream's copies.

**C's settings** are A's, with `server_id = 2`, `bind-address = 10.0.0.3`, and a certificate of its own from the same
CA, naming `10.0.0.3` (the processes check every host they are given, the replica included). And:

```
super_read_only = ON          # nothing but replication writes; a promotion's SET PERSIST outranks it
```

**Two more accounts on the primary**, which replication carries to C: one to replicate, and one to copy a whole
server. The replication account's password is 32 characters at most: `CHANGE REPLICATION SOURCE` refuses a longer
one (error 3056). `openssl rand -hex 16` makes 32 characters (DOC-37).

```sql
CREATE USER 'repl'@'10.0.0.%'  IDENTIFIED BY '…' REQUIRE SSL;
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'10.0.0.%';
CREATE USER 'clone'@'10.0.0.%' IDENTIFIED BY '…' REQUIRE SSL;
GRANT BACKUP_ADMIN ON *.* TO 'clone'@'10.0.0.%';
```

**Making the replica**, on C, as root, by the socket. The same statements make an old primary a replica again
(runbook §2, step 5), with the addresses the other way round:

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

**What those TLS settings check**, tested with a CA of the drill's own (2026-09-30):

- replication refuses a primary whose certificate is from another CA, and one whose certificate names another
  address;
- the clone refuses another CA's certificate, but not one naming another address: it checks the CA only.

The CA signs nothing but the backend's MySQL servers, so a certificate it signed is one of them. Its key stays off
every database machine (above).

The copy's persisted settings are the recipient's own, never the donor's. This is rehearsed in the drill
(`MYSQL_FAILOVER`, `client/headless-drill.sh`), where each failover ends with the old primary rebuilt this way and
handed back to.

**Then every process is given both hosts**, the primary's first by convention, in the URL of the processes above.
Each new connection goes to the writable server with the highest epoch
([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)):

```
BACKEND_DB_URL=jdbc:mysql://10.0.0.1:3306,10.0.0.3:3306/backend?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/etc/backend/tls/mysql-ca.p12&trustCertificateKeyStoreType=PKCS12
```

## 10. Copies off the database's machine

**Built and run for real on 2026-09-26.** A dump on the disk it protects is lost with that disk, and so are the
binlogs a point-in-time restore replays. So both are taken from another machine, the one the replica lives on
([architecture/01 §2](../architecture/01-system-topology.md#2-machine-topology)), over TLS:

- **The binlogs, as they are written.** `backend-binlog-stream.service` runs `scripts/binlog-stream.sh`, which runs
  `mysqlbinlog --read-from-remote-server --raw --stop-never`, the standard binlog server. The copies land in
  `/var/lib/backend/binlog`.
  - Measured: a committed write reached the copy in **17 ms**, and every closed file was byte for byte the
    server's. The one file still being written differs in a single byte, the server's "in use" flag.
  - A restarted stream resumes from its newest copy. `--raw` rewrites that file from the start, so a half-written
    file is completed, never duplicated.
- **The nightly dump.** `backend-mysql-backup.timer` runs `scripts/backup-mysql.sh` at 03:30 UTC (up to 15 minutes
  later), with the connection in an option file, into `/var/lib/backend/mysql-dumps`: each database's, the
  backend's and the account service's (D-117, 10 §6.3), a file each, recorded as one run that succeeded only if both
  did. After it succeeds, the script deletes each database's dumps older than `KEEP_DAYS` (8), and every binlog copy
  before the file that the oldest remaining dump of either database starts from: the binlogs are the server's. That rule is exact. Pruning the copies by age would delete the file an old dump starts
  in, whenever the database had been idle before that dump.

**The recovery that matters was rehearsed**: with the full stack writing to a TLS primary, a remote dump, 20 more
matches after it, then a restore onto a different, empty server, from the dump and the copies alone. Every table
matched the primary, and the ledger reconciled with every balance. Copies with a file missing in the middle are
refused before anything is loaded ([runbook](02-runbook.md#the-restore-drill)).

**Install**, on the backup machine. On the launch's one machine the install guide does the same over loopback,
without TLS ([03 §6](03-install-guide.md#its-backups-on-this-machine)).

1. `useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin backend-backup`. The client tools are the
   release's (`/opt/backend/mysql/bin`), which the scripts find beside them. The backups get a user of their own
   because the copies are the whole database, and the `backend` processes on the same machine have no business
   reading them.
2. The CA as PEM, `/etc/backend/tls/mysql-ca.pem`. The MySQL client tools, written in C, do not read the PKCS#12
   store the processes use (§9).
3. Two option files in `/etc/backend/backup/`, which belong to the backup user and are read by its scripts
   (D-102). Make the directory with `install -d -o backend-backup -g backend-backup -m 0700`; each file is 0600 and
   `backend-backup`'s. Each has `[client]` with `host`, `user`, `password`, `ssl-mode=VERIFY_IDENTITY` and
   `ssl-ca`: `mysql-binlog.cnf` for the `binlog` account, and `mysql-backup.cnf` for `backup` (§9).
4. Copy `deploy/systemd/backend-binlog-stream.service`, `backend-mysql-backup.service` and
   `backend-mysql-backup.timer` into `/etc/systemd/system/`, then enable the stream and the timer. The release
   carries the scripts in `scripts/`.

Both units were run under systemd against the TLS primary, and the stream through a restart of the database. That
restart showed why the stream's unit restarts **always**, not only on failure: `mysqlbinlog` ends with status 0 when
the server shuts down, so `on-failure` would have left the copies stopped, without a word.

**Watching the stream.** The stream has no metric of its own, and records nothing in `backup_run`. A stream that has
stopped shows in two steps that do record:

- the next copy off the site (once a host is named), which fails when the stream does not open the next binlog
  within 30 s;
- the next weekly proof, which fails when the copies never reach the primary's position.

That can be a week after the stream stopped, so `systemctl status backend-binlog-stream` is in the daily checks
([runbook §6](02-runbook.md#6-routine)). If the stream was down longer than the server keeps binlogs (7 days, §9),
the server has purged the next file. The stream then fails, saying so, and the copies have a gap that no restart
fills. Take a dump at once: it is the new starting point.

### Proved every week, and kept off the site (plan item 76 (a), D-71)

**Built and drilled 2026-10-04** with `scripts/backup-drill.sh`. The drill runs every script below on MySQL servers
of its own, seeded with a copy of a real database, and checks what each script must refuse as well as what it must
do ([06 §10](../detailed-design/06-persistence-mysql.md#10-backup-and-recovery)). Among the refusals: a proof from
off the site with the wrong key, refused and recorded as a failure (O-15). The drill runs the scripts, not the
units; the scratch server's unit was run under systemd on its own (below).

**The proof**, on the backup machine:

1. **A scratch MySQL server of its own**, which holds nothing but the proofs' copies:
   `deploy/mysql/scratch-my.cnf.example` to `/etc/backend/scratch-my.cnf`. It keeps its configuration, data and
   socket apart from the replica's. It is the release's `mysqld`, `/opt/backend/mysql/bin/mysqld`, which the
   distribution's confinement does not cover: AppArmor's profile attaches to `/usr/sbin/mysqld` (the rules O-16
   shipped for it are gone with it), and SELinux runs a service started from `/opt` unconfined (not tested on an
   enforcing RHEL).
2. **Initialise it once**:
   - `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/scratch-my.cnf --initialize-insecure --user=mysql`;
   - enable and start `backend-scratch-mysql.service`;
   - set root's password over its socket (`/opt/backend/mysql/bin/mysql -S /run/backend-scratch-mysql/mysqld.sock
     -u root`, then `ALTER USER 'root'@'localhost' IDENTIFIED BY '…'`);
   - put that password in `/etc/backend/backup/mysql-scratch.cnf` (mode 0600, the backup user's, `[client]` with
     `host=127.0.0.1`, `port=3309`, `user=root`, `password`).

   The unit is `Type=notify`, so it counts as started only once the server takes connections (up to
   `TimeoutStartSec=10min`). It makes `/run/backend-scratch-mysql` (`RuntimeDirectory`, mode 0750) at every start:
   `/run` is emptied at boot and mysqld makes no parent directory, so without it the server never started ("Unable
   to setup unix socket lock file", O-16). Run under systemd on Ubuntu 24.04: on 2026-09-28 with the distribution's
   8.0; on 2026-10-05 with the release's 8.4 under `Type=notify`: "Server is operational", active, and stopped
   cleanly.
3. **`backend-restore-proof.service` and `.timer`**, the timer enabled. Every Sunday at 04:00 UTC, each database's
   newest dump and the binlog copies are restored onto the scratch server, into `backend_proof` and `account_proof`,
   while the primary keeps writing; then checked, each as its database is (the ledger's reconciliation the
   backend's alone), and recorded in `backup_run` as one proof. Every outcome is recorded, a failure with the lines of its
   log that say why.

**The copy off the site**, once the owner has named the host (Q-53):

1. **The key**, made once and **kept somewhere other than the three machines**, on paper or in the owner's password
   manager: without it the copy cannot be read. `head -c 32 /dev/urandom | base64 >
   /etc/backend/backup/offsite.key`, mode 0600, the backup user's.
2. **An SSH key for the backup user**, `/etc/backend/backup/offsite-ssh`, mode 0600 and the backup user's, with its
   public half on the host; the host's own key in `/etc/backend/offsite-known-hosts`; and `/etc/backend/offsite.env`
   with `OFFSITE_TARGET=user@host:dir`.
3. **`backend-backup-offsite.timer` enabled.** Every hour, the primary's binary logs are flushed, and the dumps and
   every closed binlog copy, each encrypted, are pushed by `rsync --delete` to the host. So the host holds what the
   backup machine holds, at most an hour behind.
4. **`backend-restore-proof-offsite.timer` enabled.** On the first Sunday of each month at 05:00 UTC, the copy is
   fetched back, decrypted, and restored as the weekly proof is, into a database of its own,
   `backend_proof_offsite`. This proves the key, the host and the copy together. The two proofs never run at once.
   The fetch's errors go into the proof's log, so a wrong key or an unreachable host is recorded with what it said.

**Watch it** ([runbook §5](02-runbook.md#5-what-to-watch)). Every worker reports, from `backup_run`, when each step
last succeeded and whether its last run failed (`backend_backup_succeeded_timestamp_seconds{kind}` and
`backend_backup_failed{kind}`, §7). The watch's rules read them (`BackupDumpStale`, `BackupProofStale`,
`BackupOffsiteStale`, `BackupFailed`, `RestoreTooSlow`; [08](08-monitoring.md), D-86), on C's platform, whose
`BACKEND_WATCH_TARGETS` names both workers. The stream records nothing, so `systemctl status backend-binlog-stream`
stays in the daily check (above).

## 11. The certificate

**Since [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
the site's certificate comes from the backend's own CA**, as [06](06-certificates.md) describes step by step:

1. a root, made once with `scripts/private-ca.sh`, whose certificate the app ships and trusts alone;
2. from it, one certificate for the site's public address and every arena's name (the names in
   `backend-arenas.conf`);
3. installed on every machine by `scripts/install-certificate.sh`, and served by nginx on 443 and 9001.

The site has no domain, so a public CA cannot certify it.

`install-certificate.sh` still works as certbot's deploy hook (`RENEWED_LINEAGE`), and with any CA's PEM files. A
public CA's certificate would need a domain, its names in DNS, and port 80 open for the HTTP-01 challenge. Since
D-115, 80 serves the backend in clear, and has no path for that challenge. How the public CA's way was built and run
before D-82 is in this file's history (git).

**Watching it.** nginx exports no metrics, so the gateway reports the certificate file nginx serves, at every
scrape, as `backend_gateway_edge_certificate_expiry_timestamp_seconds` (`BACKEND_EDGE_CERTIFICATE` in
`gateway.env`). An unreadable file reads as 0, so the alert fires rather than the series vanishing. The value is the
earliest expiry in the chain, not the leaf's: a device refuses the chain when an intermediate has expired, too. The
watch's `CertificateExpiring` fires under 14 days ([08](08-monitoring.md)). Renew a month before the end, by the
yearly routine ([06 §6](06-certificates.md#6-renewing-every-year)), not on the alert.

**What the client must do**, for whoever builds it ([06 §5](06-certificates.md#5-in-the-app)):

- **A client with TLS** uses `https://` and `wss://`, and TLS on 9001, with no fallback to plaintext. It verifies
  against the backend's root alone, with the name checked: the address dialled on 443, and the grant's `tlsName` on
  9001.
- **Pin nothing but the root**, so the yearly renewal needs no new release of the app.
- **On a certificate error, show an error**; never offer to continue.
- **A build in clear** (D-115) is given `http://` and `ws://`, and uses 80 for the API and the lobby; what it sends
  there crosses in clear. The full client and `client-simple` still take a match's TLS on 9001, as the grant asks,
  and check the root there. Only `client-simple-no-tls` (D-114) reaches its match in clear, through
  `/match/<the grant's tlsName>` on 80 (§4), with no certificate to check.
