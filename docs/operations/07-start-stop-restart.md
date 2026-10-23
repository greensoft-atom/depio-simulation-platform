# Start, stop and restart: each service

How to start, stop, restart or reload each part of the backend, and what players notice while
you do. Section 1 is a server, where systemd runs everything from the release in `/opt/backend`;
section 2 is a development machine, where the repository's scripts do. Releasing a new version is
[01 §5](01-deploy.md#5-rolling-deployment), and a failure is the [runbook](02-runbook.md).

## 1. On a server

### The units

Every service is a systemd unit. Its settings are read when it starts, so a changed setting needs a
restart. nginx is the exception: it reloads.

| Unit | What | Machines | Its settings | After changing them | A stop takes |
|---|---|---|---|---|---|
| `backend-nginx` | nginx: TLS on 443 and 9001, the front | every one | `/etc/backend/nginx/*.conf` | `systemctl reload backend-nginx` | up to 30 s |
| `backend-mysql` | MySQL | A (primary), C (replica) | `/etc/backend/mysql/backend.cnf` | restart | up to 15 min, flushing |
| `backend-scratch-mysql` | the backups' scratch MySQL | C | `/etc/backend/scratch-my.cnf` | restart | |
| `backend-store@session` | j-redis `session` | A (primary), B (replica) | `/etc/backend/store-session.conf`, `.env` | restart | up to 60 s |
| `backend-store@events` | j-redis `events` | B (primary), C (replica) | `/etc/backend/store-events.conf`, `.env` | restart | up to 60 s |
| `backend-platform` | the API | every one | `/etc/backend/platform.env` | restart | up to 30 s |
| `backend-gateway` | the lobby | every one | `/etc/backend/gateway.env` | restart | up to 30 s |
| `backend-worker@c1`, `@c2` | the workers | C | `/etc/backend/worker.env`, `worker-<id>.env` | restart | up to 30 s |
| `backend-arena@<name>` | an arena: `a1`, `a2` / `b1`, `b2`, `b3` / `c1`, `c2` | A / B / C | `/etc/backend/arena-<name>.env` | restart | up to 12 min, draining |
| `backend-binlog-stream` | the binlogs copied as written | C | the unit, `mysql-binlog.cnf` | restart | |

The backups run from timers (`backend-mysql-backup.timer`, `backend-restore-proof.timer` and the
others in [01 §10](01-deploy.md#10-copies-off-the-databases-machine)); there is nothing to restart.

### The commands

As root, on the machine the unit runs on:

```bash
systemctl status backend-platform              # running or not, and its last log lines
systemctl restart backend-platform             # stop, then start
systemctl stop backend-platform
systemctl start backend-platform
journalctl -u backend-platform -f              # its log as it is written; -n 100 for the last 100 lines
systemctl status 'backend-*'                   # every unit of the backend on this machine
```

A unit file or a drop-in changed (in `/etc/systemd/system/`): `systemctl daemon-reload` first,
then the restart. A unit that would not start says why in `systemctl status` and its journal. A
setting it refuses (exit status 2 for the Java services, 1 for MySQL, 1 to 3 for a store) is not
retried by systemd: fix the setting, then start it.

### nginx, after changing its configuration

```bash
/opt/backend/nginx/sbin/nginx -t               # "syntax is ok", "test is successful"
systemctl reload backend-nginx                 # tests it again, then serves it
```

**Reload, do not restart.** A reload starts new workers on the new configuration, and the old ones
finish what they serve: no player is cut off. A configuration nginx refuses is not loaded; the old
one keeps serving, and `systemctl status backend-nginx` says why. The same configuration is on every
machine, since any can be the front ([05](05-ports-and-firewall.md)): reload each.

**Restart it** (`systemctl restart backend-nginx`) only for a new nginx, from a new release, or a
changed unit (`daemon-reload` first), and on the front only in a maintenance window. A restart
stops nginx gently: it waits for its connections to end, and lobby and match connections do not.
After the unit's 30 seconds they are cut. Every player's app reconnects to the lobby, but a match
waits only 10 seconds for a player to come back
([D-18](../architecture/03-decision-log.md#d-18--a-lost-connection-waits-in-the-arenas-memory-not-the-store)),
so the matches in progress lose their players. This follows from the unit's settings; it was not
measured.

A new certificate needs neither: `install-certificate.sh` reloads nginx itself
([06 §4](06-certificates.md#4-installing-it-on-every-machine)). Its logs are in `/var/log/backend-nginx/`:
`error.log`, `access.log`, and `stream.log` for match traffic.

### MySQL alone

```bash
systemctl restart backend-mysql
systemctl status backend-mysql                 # "active (running)": it accepts connections
journalctl -u backend-mysql -n 50              # and its own log, /var/log/backend-mysql/
```

- **The primary, on A.** While it is down, logins, purchases and anything that reads or writes the
  database fail, and the app may retry them. Matches play on. Their results wait in the result
  stream and are applied when it is back. The platforms and the workers reconnect by themselves;
  do not restart them. Checked on a development machine (2026-10-23): straight after a restart the
  platform served a registration, and a worker applied the next four matches; and under RHEL 9's
  systemd, by `check-units-el9.sh`.
- **A stop flushes MySQL's memory to disk**, which can take minutes with a large buffer pool: let it
  finish. The unit allows 15 minutes.
- **The replica, on C.** Nothing for players. It catches up by itself, and the binlog copy on C
  reconnects within 10 seconds.
- **At a quiet hour**, for the primary. To restart it with no downtime, hand over to the replica
  first ([runbook §2](02-runbook.md#mysql-built-2026-09-29-d-35)).
- A changed `backend.cnf` takes a restart. Some settings MySQL can change while it runs, with
  `SET PERSIST`, which a restart keeps.

### A store alone

```bash
systemctl restart backend-store@session        # or backend-store@events
```

- **`session`'s primary, on A**: logins, tickets and matchmaking wait during the restart; what it
  holds comes back from its files, sessions included. The other processes reconnect by themselves:
  checked on a development machine, where the platform served at once after one, and the next four
  matches went from the arena through the store and the worker into MySQL.
- **`events`' primary, on B**: the arenas keep results on their disks and send them when it is back.
- **A setting changed on both**, the replica first, then the primary. The `events` store's size:
  [04 §3.4](04-scaling-and-performance.md#34-j-redis).

### The platform, the gateway, a worker

```bash
systemctl restart backend-platform
systemctl restart backend-gateway
systemctl restart backend-worker@c1
```

- **The platform and the gateway**, one machine at a time. The front's nginx passes over the one
  restarting to another machine's. A request in flight fails and the app retries it; a lobby
  connection on that gateway reconnects.
- **A worker**: what it had in hand stays pending in the stream, and it takes it up again when it
  is back. Nothing is lost.

### An arena

```bash
systemctl restart backend-arena@a1             # one at a time, at a quiet hour
```

**A stop drains the arena.** It takes no new players. The public arena's players go back to the
lobby with their results, and the matches already made play out, for up to 11 minutes. The other
arenas take the new matches meanwhile. So a restart can take minutes; the journal says `drained in
N s`. Tell the players a few minutes before, with a notice from the admin API
([runbook §6](02-runbook.md#6-routine)).

### Everything on one machine

**Stop**, the arenas first and the database last. A pattern matches what is running, so it serves
for stopping:

```bash
systemctl stop 'backend-arena@*' backend-gateway backend-platform 'backend-worker@*'
systemctl stop 'backend-store@*' backend-mysql backend-nginx
```

**Start**, the database first. Name each unit: systemd matches a pattern only against units in
memory, and a stopped one usually is not: `systemctl start 'backend-arena@*'` started nothing on
RHEL 9's systemd 252 (checked). On A, B and
C as the examples set them ([01 §1](01-deploy.md#1-what-runs-where)):

```bash
# A
systemctl start backend-mysql backend-store@session
systemctl start backend-platform backend-gateway backend-arena@a1 backend-arena@a2 backend-nginx
# B
systemctl start backend-store@session backend-store@events
systemctl start backend-platform backend-gateway backend-arena@b1 backend-arena@b2 backend-arena@b3 backend-nginx
# C
systemctl start backend-mysql backend-store@events
systemctl start backend-platform backend-gateway backend-worker@c1 backend-worker@c2 \
    backend-arena@c1 backend-arena@c2 backend-nginx
```

The first platform or worker to start migrates the schema when a release brings a change
([01 §5](01-deploy.md#5-rolling-deployment)). Enabled units start by themselves at boot.

**Checked as written** (2026-10-23) by `backend/scripts/check-units-el9.sh`, in UBI 9.5 with
systemd 252: a broken nginx configuration refused by the reload while the old one kept serving; MySQL
restarted, then the store, each with the platform serving again unrestarted; a pattern stopping the
arena and the worker, and the units started again by name.

On a
machine installed alone, by the install guide, the order is
[03 §10](03-install-guide.md#10-start-everything)'s.

## 2. On a development machine

The repository's scripts, from its root. Nothing here is a systemd unit, and there is no nginx: the
checks run it in a container.

```bash
./dev-mysql.sh start | stop | restart | status       # the development MySQL, 127.0.0.1:3314
./run-local.sh start                                  # the store, platform, worker, gateway, arena
./run-local.sh restart platform                       # one alone, the others untouched
./run-local.sh stop arena                             # one alone
./run-local.sh restart                                # all five
./run-local.sh stop                                   # all five; the MySQL keeps running
./run-local.sh status
```

The names are `store`, `platform`, `worker`, `gateway` and `arena`. A restart starts the
development MySQL if a platform or a worker needs it. The MySQL can be restarted under a running
backend: it reconnects. Each process's log is `work/run/<name>.log`, MySQL's
`work/mysql/mysqld.err`. Step by step: [development/03 §3.1](../development/03-build-and-run.md#31-the-development-mysql-step-by-step)
and [§5](../development/03-build-and-run.md#5-running-it-by-hand).
