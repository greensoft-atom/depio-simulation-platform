# Start, stop and restart: each service

This page is for operators. It says how to start, stop, restart or reload each part of the backend, and what
players notice while you do it. Section 1 is for a server: there, systemd runs every service from the release
installed in `/opt/backend`. Section 2 is for a development machine: there, the repository's own scripts run the
services. To roll out a new version, follow [01 §5](01-deploy.md#5-rolling-deployment). When something has failed,
use the [runbook](02-runbook.md). Words such as "front", "drain" and "stay" are defined in the
[glossary](../glossary.md).

## 1. On a server

### The units

**Every service is a systemd unit.** A unit reads its settings only when it starts, so after you change a setting,
restart the unit. nginx is the exception: it reloads its configuration without a restart.

The "Machines" column follows the three-machine examples, A, B and C
([01 §1](01-deploy.md#1-what-runs-where)). On the launch's one machine every unit below runs on that one machine,
and its arenas are `a1`, `a2` and `a3`.

| Unit | What | Machines | Its settings | After changing them | A stop takes |
|---|---|---|---|---|---|
| `backend-nginx` | nginx, the front: the API and the lobby on 443 (TLS) and on 80 (in clear); matches on 9001 (TLS) and through 80's `/match/` (in clear) | every one | `/etc/backend/nginx/*.conf` | `systemctl reload backend-nginx` | up to 30 s |
| `backend-mysql` | MySQL | A (primary), C (replica) | `/etc/backend/mysql/backend.cnf` | restart | up to 15 min, while it flushes to disk |
| `backend-scratch-mysql` | the backups' scratch MySQL, which the restore proofs use | C | `/etc/backend/scratch-my.cnf` | restart | up to 10 min |
| `backend-store@session` | j-redis `session` | A (primary), B (replica) | `/etc/backend/store-session.conf`, `.env` | restart | up to 60 s |
| `backend-store@events` | j-redis `events` | B (primary), C (replica) | `/etc/backend/store-events.conf`, `.env` | restart | up to 60 s |
| `backend-account` | the account service (D-117): sign-up, login, the tokens | every one | `/etc/backend/account.env` | restart | up to 30 s |
| `backend-platform` | the API, and the watch ([08](08-monitoring.md)) | every one | `/etc/backend/platform.env` | restart | up to 30 s |
| `backend-gateway` | the lobby | every one | `/etc/backend/gateway.env` | restart | up to 30 s |
| `backend-worker@c1`, `@c2` | the workers | C | `/etc/backend/worker.env`, `worker-<id>.env` | restart | up to 30 s |
| `backend-arena@<name>` | an arena: `a1`, `a2` / `b1`, `b2`, `b3` / `c1`, `c2` | A / B / C | `/etc/backend/arena-<name>.env` | restart | up to 12 min, while it drains |
| `backend-binlog-stream` | the MySQL binlogs, copied as they are written | C | the unit, and `/etc/backend/backup/mysql-binlog.cnf` | restart | systemd's default, 90 s |

**The timers need no restart.** Each timer starts a one-shot service, and the service ends when its job is done.
The backups' timers are described in [01 §10](01-deploy.md#10-copies-off-the-databases-machine):

| Timer | Starts | When (UTC) |
|---|---|---|
| `backend-mysql-backup.timer` | the nightly dump | every day at 03:30, up to 15 min later |
| `backend-restore-proof.timer` | the weekly restore proof, onto the scratch server | Sundays at 04:00 |
| `backend-restore-proof-offsite.timer` | the monthly proof from the copy off the site | the first Sunday of the month at 05:00 |
| `backend-backup-offsite.timer` | the copy off the site, once the owner names a host (Q-53) | every hour, up to 5 min later |
| `backend-nginx-logs.timer` | nginx's log rotation (below) | every day at 00:05, up to 5 min later |

To run one now, start its service, for example `systemctl start backend-mysql-backup`.
`backend-leaderboard-rebuild` is a one-shot service with no timer: you start it by hand after a store is lost
([runbook §3](02-runbook.md#3-per-component-procedures)).

### The commands

Run these as root, on the machine the unit runs on:

```bash
systemctl status backend-platform              # running or not, and its last log lines
systemctl restart backend-platform             # stop, then start
systemctl stop backend-platform
systemctl start backend-platform
journalctl -u backend-platform -f              # its log as it is written; -n 100 for the last 100 lines
systemctl status 'backend-*'                   # every unit of the backend on this machine
```

- **A unit file or a drop-in changed** (in `/etc/systemd/system/`): run `systemctl daemon-reload` first, then the
  restart.
- **A unit that does not start** says why in `systemctl status` and in its journal.
- **A setting the service refuses** makes it exit with a status that systemd does not retry
  (`RestartPreventExitStatus=`): 2 for the Java services, 1 for MySQL, 1 to 3 for a store. Fix the setting, then
  start the unit.

### nginx, after changing its configuration

```bash
/opt/backend/nginx/sbin/nginx -t               # "syntax is ok", "test is successful"
systemctl reload backend-nginx                 # tests it again, then serves it
```

**Reload, do not restart.** A reload starts new nginx workers on the new configuration, and the old workers finish
the connections they serve, so no player is cut off. If nginx refuses the new configuration, it does not load it:
the old configuration keeps serving, and `systemctl status backend-nginx` says why. Every machine has the same
configuration, since any machine can be made the front ([05](05-ports-and-firewall.md)), so reload nginx on each
machine.

**Restart nginx** (`systemctl restart backend-nginx`) only for a new nginx from a new release, or for a changed
unit (run `daemon-reload` first). On the front, restart it only in a maintenance window. This is what a restart
does, and what players notice:

1. nginx stops gently: it stops accepting new connections at once and waits for its open connections to end.
2. Lobby and match connections do not end by themselves. After the unit's 30 seconds (`TimeoutStopSec`), they are
   cut. Only then does the new nginx start. So for up to 30 s the front takes no new connection: no login, no
   lobby, no resume.
3. Then every app reconnects to the lobby and resumes its match. A tank waits 10 s where it was, and its stay
   waits a minute
   ([D-18](../architecture/03-decision-log.md#d-18--a-lost-connection-waits-in-the-arenas-memory-not-the-store),
   [02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)). A player who is back
   within the minute plays on.

This follows from nginx's settings and the unit's; it was not measured.

**A new certificate needs neither a reload by you nor a restart**: `install-certificate.sh` reloads nginx itself
([06 §4](06-certificates.md#4-installing-it-on-every-machine)).

**nginx's logs** are in `/var/log/backend-nginx/`: `error.log`, `access.log` (the API and the lobby, and 80's
matches) and `stream.log` (match traffic on 9001). `backend-nginx-logs.timer` rotates them every night and keeps
them 14 days (`KEEP_DAYS` in `backend-nginx-logs.service`, D-85). The timer needs no restart;
`systemctl start backend-nginx-logs` rotates the logs now.

### MySQL alone

```bash
systemctl restart backend-mysql
systemctl status backend-mysql                 # "active (running)": it accepts connections
journalctl -u backend-mysql -n 50              # and its own log, /var/log/backend-mysql/
```

- **The primary, on A.** While it is down, logins, purchases and anything else that reads or writes the database
  fail, and the app may retry them. Matches play on. Their results wait in the result stream and are applied when
  MySQL is back. The platforms and the workers reconnect by themselves: do not restart them. This was checked on a
  development machine (2026-10-23): straight after a restart the platform served a registration, and a worker
  applied the next four matches. It was checked under RHEL 9's systemd too, by `check-units-el9.sh`.
- **A stop flushes MySQL's memory to disk.** With a large buffer pool this can take minutes: let it finish. The
  unit allows 15 minutes.
- **The replica, on C.** Players notice nothing. The replica catches up by itself, and the binlog stream on C
  reconnects within 10 seconds.
- **Restart the primary at a quiet hour.** To restart it with no downtime, hand over to the replica first
  ([runbook §2](02-runbook.md#mysql-built-2026-09-29-d-35)).
- **A changed `backend.cnf` needs a restart.** MySQL can change some settings while it runs, with `SET PERSIST`,
  and a restart keeps what `SET PERSIST` set.

### A store alone

```bash
systemctl restart backend-store@session        # or backend-store@events
```

- **`session`'s primary, on A.** Logins, tickets and matchmaking wait during the restart. The store reloads what it
  holds from its files, sessions included. The other processes reconnect by themselves. This was checked on a
  development machine: the platform served at once after a restart, and the next four matches went from the arena
  through the store and the worker into MySQL.
- **`events`' primary, on B.** The arenas keep their results on their own disks (the spool) and send them when the
  store is back.
- **A setting changed on both servers of a store**: restart the replica first, then the primary. How to size the
  `events` store: [04 §3.4](04-scaling-and-performance.md#34-j-redis).

### The platform, the gateway, a worker

```bash
systemctl restart backend-platform
systemctl restart backend-gateway
systemctl restart backend-worker@c1
```

- **The platform and the gateway**: restart them one machine at a time. The front's nginx sends requests past the
  one that is restarting, to another machine's. A request in flight fails and the app retries it. A lobby
  connection on that gateway is cut, and the app reconnects.
- **On the launch's one machine** there is no other platform or gateway to send to. The API or the lobby refuses
  for the few seconds the restart takes ([09](09-rehearsal.md#on-the-launchs-one-machine-d-93) asks the rehearsal
  to record how long).
- **A worker**: the results it had in hand stay pending in the result stream, and it takes them up again when it is
  back. Nothing is lost.

### An arena

```bash
systemctl restart backend-arena@a1             # one at a time, at a quiet hour
```

**A stop drains the arena**, so a restart can take minutes:

1. The arena takes no new players.
2. The public arena's players go back to the lobby, with their results.
3. The matches already made play to their end, for up to 11 minutes. Meanwhile the other arenas take the new
   matches.
4. The journal says `drained in N s`, and the arena stops.

Tell the players a few minutes before, with a notice from the admin API
([runbook §6](02-runbook.md#6-routine)).

### Everything on one machine

**To stop everything**, stop the arenas first and the database last. A pattern such as `'backend-arena@*'` matches
the units that are running, so a pattern works for stopping:

```bash
systemctl stop 'backend-arena@*' backend-gateway backend-platform backend-account 'backend-worker@*'
systemctl stop 'backend-store@*' backend-mysql backend-nginx
systemctl stop backend-binlog-stream backend-scratch-mysql          # on C, the backups' machine
```

**To start everything**, start the database first, and name each unit. A pattern does not work for starting:
systemd matches a pattern only against the units it has loaded, and a stopped unit is usually not loaded.
`systemctl start 'backend-arena@*'` started nothing on RHEL 9's systemd 252 (checked). On A, B and C, with the units
the examples give each machine ([01 §1](01-deploy.md#1-what-runs-where)):

```bash
# A
systemctl start backend-mysql backend-store@session
systemctl start backend-account backend-platform backend-gateway backend-arena@a1 backend-arena@a2 backend-nginx
# B
systemctl start backend-store@session backend-store@events
systemctl start backend-account backend-platform backend-gateway backend-arena@b1 backend-arena@b2 backend-arena@b3 backend-nginx
# C
systemctl start backend-mysql backend-store@events backend-scratch-mysql backend-binlog-stream
systemctl start backend-account backend-platform backend-gateway backend-worker@c1 backend-worker@c2 \
    backend-arena@c1 backend-arena@c2 backend-nginx
```

When a release brings a change to the schema, the first platform or worker to start migrates it
([01 §5](01-deploy.md#5-rolling-deployment)). The units are enabled, so they also start by themselves at boot.

**Checked as written** (2026-10-23) by `backend/scripts/check-units-el9.sh`, in UBI 9.5 with systemd 252:

- a broken nginx configuration, refused by the reload while the old one kept serving;
- MySQL restarted, then the store, and after each the platform serving again without a restart of its own;
- a pattern stopping the arena and the worker, and the units started again by name.

**On the launch's one machine**
([D-93](../architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)),
installed by the install guide, every role runs on the one machine. Start the units in the order of the install
guide's [§10](03-install-guide.md#10-start-everything). The stop above works as it is.

```bash
systemctl start backend-mysql backend-store@session backend-store@events backend-scratch-mysql backend-binlog-stream
systemctl start backend-account backend-platform backend-gateway backend-worker@c1 backend-worker@c2 \
    backend-arena@a1 backend-arena@a2 backend-arena@a3 backend-nginx
```

When the machine itself restarts, every unit starts again by itself, since every one is enabled. The install
guide's check proves this: it restarts its machine.

## 2. On a development machine

On a development machine the repository's scripts run the services: one script for each service, as the site has
one unit for each
([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)).
Run them from the repository's root. Nothing here is a systemd unit.

```bash
./dev-mysql.sh  start | stop | restart | status | test    # the development MySQL, 127.0.0.1:3314
./dev-jredis.sh start | stop | restart | status | test    # j-redis, 127.0.0.1:6380
./dev-account.sh start | stop | restart | status | test   # the account service, 127.0.0.1:8083 (D-117)
./dev-backend.sh restart platform                         # one alone, the others untouched
./dev-backend.sh stop arena                               # one alone
./dev-backend.sh restart                                  # all four
./dev-backend.sh status
./dev-nginx.sh  start | stop | restart | status | test    # the release's nginx, 80, 443, 9001
./run-local.sh  start | stop | restart | status           # the five in order; stop leaves the MySQL running
```

- **The backend's four processes** are named `platform`, `worker`, `gateway` and `arena`. `./dev-backend.sh` acts
  on the ones you name, or on all four. `./dev-jredis.sh build`, `./dev-account.sh build` and `./dev-backend.sh
  build` build them first. The platform takes the tokens of the account service that `./dev-account.sh` runs, so
  players and bots sign in there (D-117). `./dev-backend.sh test` sends two bots through nginx: signed in at the
  account service by nginx's `/account`, the API, the lobby and a match. `./dev-backend.sh bots`
  plays with more bots, their matches through nginx.
- **What each process needs must be running first.** `./dev-backend.sh start` refuses while j-redis is not running,
  or while the development MySQL is not running (for a platform or a worker). The message names the script that
  starts it. `restart` stops the processes you named and then refuses in the same way.
- **The MySQL and j-redis can be restarted under a running backend**: the backend reconnects (checked, plan item
  110).
- **nginx needs root for 80 and 443.** `dev-nginx.sh`, and so `./run-local.sh start`, runs as root, or is given
  ports above 1023 in `NGINX_HTTP_PORT` and `NGINX_HTTPS_PORT` (D-92). As another user, `./run-local.sh start` stops
  at nginx, with the other four running.
- **`PLAIN=1`**, given to every command, runs nginx and the arena without TLS: the API and the lobby on 80, matches
  on 9001, and no 443
  ([D-101](../architecture/03-decision-log.md#d-101--a-plain-run-by-hand-goes-through-nginx-as-the-site-does-80-and-9001-without-tls)).
- **The logs**: each process's is `work/run/<name>.log`, j-redis's `work/run/store.log`, nginx's in
  `work/nginx/logs/`, and MySQL's `work/mysql/mysqld.err`.

Step by step: [development/03 §3.1](../development/03-build-and-run.md#31-the-development-mysql-step-by-step) and
[§5](../development/03-build-and-run.md#5-running-it-by-hand).
