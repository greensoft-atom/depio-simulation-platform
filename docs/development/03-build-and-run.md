# Build, test, run and debug: a guide

From a copy of the repository to the backend built, tested, running and under a
debugger, on a machine with no network, using only what the repository carries:
the JDK, Maven and MySQL in [`vendor/`](../../vendor/README.md), every library in
[`java21-offline/`](../../java21-offline/README.md). Nothing is installed,
nothing is written outside the repository's folder, and of the machine's own
software only its basic tools are used (§1)
([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)).
A Java and a MySQL already on the machine (a Java 8, a MySQL 5) are neither used
nor changed. To install the backend on a server instead, see the
[install guide](../operations/03-install-guide.md). For VS Code, see
[05-vscode](05-vscode.md). The questions asked while setting up an offline machine, each
answered in full, are in [06](06-questions-and-answers.md).

**Run as written on 2026-10-17** in UBI 9.5, RHEL 9's own userspace, with no network, as an
ordinary user:
- the machine set up as the owner's is: Java 8 installed as the default (`alternatives`,
  `JAVA_HOME`, and an `/etc/mavenrc` pointing Maven at it), `/tmp` mounted `noexec`, and a
  listener on 3306 standing in for its MySQL;
- `./build-offline.sh` with every test, which passed;
- `run-local.sh` with twenty bots (the script of then, before D-92); `DEBUG=1` with `jdb` attached; `jcmd` on a running process;
- by hand, the store's install (§2) and one test class on the development MySQL (§3); the
  other by-hand lines were not run separately, though `build-offline.sh` runs them all.

No connection reached 3306, nothing was written to `~/.m2`, and outside the checkout the
JVM's own `/tmp/hsperfdata_<user>/` alone (§1). Not run as written: the drills (§7), which
need .NET, and the RHEL 9 checks (§8) other than the install guide's, which passed the same
day. Commands run from the repository's root.

## 1. What you need, and what the repository touches

| For | Needs |
|---|---|
| Building, testing, running and debugging the backend | Linux on x86-64 with bash, coreutils, findutils, `sed`, `tar` and `gzip`; for nginx in front of a run by hand (§5.0), `openssl`, `curl` for its `test`, and `hostname` for the default address. No Java, Maven, MySQL or network. |
| The checks on RHEL 9, and compiling nginx again | Docker |
| The client, the Unity package and the drills | the .NET 8 SDK, which the repository does not carry (§7) |

**Copy the repository whole**: with `git clone`, or as one `tar` archive. A copy
made file by file can lose the files' execute bits, and then nothing runs
(`dev-env.sh` says so). **Its path may hold letters, digits and `. _ - + @ /`
only**: no spaces. A symbolic link without spaces to it will do.

**Any user may run it, root or not**: nothing here needs root but nginx in front of a run by
hand, on the site's 80 and 443 (§5.0, D-92); every other port is above 1023. A port the machine
refuses to the user all the same (`Permission denied`) is a policy of that machine, which the
scripts name before they start anything (§10).

**Everything a build, a test or a run writes is in `work/`**, beside this
repository's `README.md`. It is never committed. Delete it to start afresh: the
next build copies the libraries it needs from the bundle again. It also holds the development
MySQL's data and nginx's root, `work/nginx/ca/ca.pem` (§5.0): after a delete, `dev-nginx.sh start`
makes a new root, and every client given the old one, the app's build among them, must be given
the new.

| In `work/` | What it holds | Written by |
|---|---|---|
| `settings.xml` | Maven's settings: the bundle as Maven's only repository, `work/m2` as its local one | `dev-env.sh` |
| `m2/` | Maven's local repository, copied from the bundle as the build needs it | Maven |
| `tmp/` | temporary files of Maven, the tests and MySQL (`TMPDIR`, `java.io.tmpdir`) | all of them |
| `mysql/` | the development MySQL's data, socket and logs (§3) | `dev-mysql.sh` |
| `run/` | a run by hand: each process's log and pid, the store's files, the arena's spool (§5) | `dev-jredis.sh`, `dev-backend.sh` |
| `nginx/` | nginx in front of a run by hand: its configuration, rewritten from the release's, its logs and pid, the certificate and its root (§5.0) | `dev-nginx.sh` |

**`dev-env.sh` is the environment**, and each script beside it starts by reading it:
- `JAVA_HOME` and `PATH` on `vendor/jdk-21` and `vendor/maven-3.9`, whatever the machine's `JAVA_HOME` says;
- Maven reads `work/settings.xml` (`MAVEN_ARGS`) and neither `/etc/mavenrc` nor `~/.mavenrc` (`MAVEN_SKIP_RC`), either of which may point Maven at another Java;
- temporary files in `work/tmp`;
- the tests' database is the development MySQL (`JDBC_URL`).

To work by hand in a shell, source it first:

```bash
. ./dev-env.sh
java -version          # 21.0.12.1, vendor/'s, not the machine's
mvn -v                 # 3.9.16, vendor/'s, on that Java
```

**Other paths in these documents** are not this machine's:
- **`/opt/backend`** is where a release is installed on a server ([install guide](../operations/03-install-guide.md)).
- **`/opt/jdk21`, `/opt/maven` and `/opt/dotnet`** are the development VM's own tools ([02 §3](02-working-here.md#3-the-development-machine)). A clone does not need them.
- **`/tmp`** appears only as the default scratch directory of the drills, the RHEL 9 checks (§7, §8) and `vendor/`'s refresh scripts, which need the internet. Name your own with `TMPDIR=`.
- **The JVM still keeps two things of its own in `/tmp`**, whatever `java.io.tmpdir` says:
  - `hsperfdata_<user>/`, each running JVM's performance counters, which it removes at exit, leaving the directory empty;
  - while `jcmd` or `jdb` attaches to a process, that process's attach socket.

  Neither runs code from `/tmp`, so a `noexec` `/tmp` is no obstacle (checked).

## 2. Build

Everything, offline: `j-redis-service`, the backend with every test, then a release.

```bash
./build-offline.sh                # with every test: 34 minutes on 2026-10-17, about an hour on a loaded machine
SKIP_TESTS=1 ./build-offline.sh   # without the tests: about fifteen minutes
```

With the tests, it starts the development MySQL (§3) and stops it at the end,
unless it was already running, or `JDBC_URL` names another server. It ends with
`--- done:` and the release's directory. On a machine already holding the
development VM's `~/.m2`, it still reads nothing from it.

By hand, in a shell that sourced `dev-env.sh`: the store first, since the backend
builds against its client.

```bash
(cd j-redis-service && mvn install -DskipTests)
(cd backend && mvn install -DskipTests)
(cd backend && mvn -pl platform -am install)      # one module, with what it needs and its tests
```

No `-o`: Maven's offline flag refuses the bundle too, since it is a `file://`
repository. The settings already make the bundle Maven's only repository, so
nothing is fetched from the network.

## 3. The tests

Most of the backend's tests need nothing; those of `persistence`, `worker` and
`platform` need a MySQL. They get the **development MySQL**: `vendor/`'s 8.4, its
files in `work/mysql`, on 127.0.0.1:3314. It reads no option file, so a
`/etc/my.cnf` meant for the machine's own MySQL is ignored. It has its own port,
data and socket, so it never meets that server.

How to run it, stop it and restart it, step by step, is §3.1.

The tests read `JDBC_URL`, which `dev-env.sh` points at `backend_test` there. Unset, as
when a test runs from an editor that has not sourced `dev-env.sh`, it is the same database
(`DatabaseSettings.TEST_URL`, O-40).
`DB_USER` and `DB_PASSWORD` default to `backend` and `backend-dev-password`. They
wipe the database they are given, which must end in `_test`. So never point
`JDBC_URL` at a server whose `backend_test` you want kept. Port 3314 taken?
`MYSQL_PORT=<a free port>` for every script, sourced or run.

One class, or one module:

```bash
. ./dev-env.sh && ./dev-mysql.sh start
(cd backend && mvn -pl persistence test -Dtest=AccountRepositoryTest)
(cd backend && mvn -pl platform test)
```

A whole backend build ran 1 064 tests on 2026-10-06 (plan item 86). On 2026-10-17
`build-offline.sh` passed them all again, with j-redis's 227. The backend's count was
not kept, since the release step's `clean` removes the test reports. The counts per module
are in each module's README.

### 3.1 The development MySQL, step by step

Every command runs from the repository's root and needs nothing else first, not even
`dev-env.sh`, which the script reads itself. Each was run as written on 2026-10-19.

| Command | What it does |
|---|---|
| `./dev-mysql.sh start` | Starts it, and returns once it answers. The first time, it first makes its data in `work/mysql/data` (about 5 s). Every start makes sure of `backend_test`, `backend_dev` and the user `backend`. Already running: prints where it runs, and changes nothing else. |
| `./dev-mysql.sh status` | Says whether it runs, and on which port. Exit status 0 if it runs, 1 if not, for scripts. |
| `./dev-mysql.sh stop` | Shuts it down cleanly, and returns once it has ended. Not running: says so. |
| `./dev-mysql.sh restart` | `stop`, then `start`. The data is kept. |

```console
$ ./dev-mysql.sh start
the development MySQL runs on 127.0.0.1:3314, its files in /home/you/depio-simulation-platform/work/mysql
$ ./dev-mysql.sh restart
the development MySQL stopped
the development MySQL runs on 127.0.0.1:3314, its files in /home/you/depio-simulation-platform/work/mysql
$ ./dev-mysql.sh stop
the development MySQL stopped
$ ./dev-mysql.sh status
the development MySQL is not running
```

**Who else starts and stops it.**
- `./dev-backend.sh build`, and so `./build-offline.sh`, starts it for the tests and stops it
  at the end, unless it was running, `SKIP_TESTS=1`, or `JDBC_URL` names another.
- `./run-local.sh start` starts it first, before j-redis, the backend and nginx.
  `./run-local.sh stop` leaves it running; stop it with `./dev-mysql.sh stop`.
- `./dev-mysql.sh test` checks it answers: the `backend` user logs in over TCP to
  both databases, and `backend_dev`'s schema version is named.
- It does not start with the machine. After a reboot, run `./dev-mysql.sh start`.

**Connecting to it**, with `vendor/`'s client:

```bash
# as the backend's user, over TCP, as the tests and the processes connect
vendor/mysql-8.4/bin/mysql --no-defaults -h 127.0.0.1 -P 3314 --protocol=TCP \
    -u backend -pbackend-dev-password backend_dev

# as root, which has no password and is reached only through the socket, from the data directory
(cd work/mysql/data && ../../../vendor/mysql-8.4/bin/mysql --no-defaults -S mysqld.sock -u root)
```

`--no-defaults` keeps the client from reading `/etc/my.cnf` or `~/.my.cnf`, which may
name the machine's own MySQL. Use `vendor/`'s client. The `mysql` on the machine's
`PATH` belongs to the machine's own MySQL 5, and was not tried against this 8.4 server.

**Where its files are**, all in `work/mysql`:

| File | What |
|---|---|
| `data/` | the databases; `data/mysqld.sock` is its socket while it runs |
| `mysqld.err` | its log: start, stop, crash recovery, errors |
| `init.err` | the log of the first start's initialisation |
| `mysqld.pid` | its process id, while it runs |

**When it does not start**, `start` prints the end of `work/mysql/mysqld.err`. Its
messages:

| What `start` says | What to do |
|---|---|
| `127.0.0.1:3314: in use (…)`, then `another port for it: MYSQL_PORT=…` | Another program has the port. Choose another: `MYSQL_PORT=3324 ./dev-mysql.sh start`. Then give every command the same `MYSQL_PORT` (`dev-env.sh`, the tests and `run-local.sh` read it), or `export MYSQL_PORT=3324` once in the shell. VS Code's settings name 3314: change it in `.vscode/settings.json` and `.vscode/launch.json` too. `stop` and `status` find it on any port. |
| `127.0.0.1:3314: refused (Permission denied)`, and under it the policy found | A policy of this machine keeps the port from this user (ports above 1023 are any user's on Linux): the lines under it name it, the unprivileged-port threshold raised, an SELinux-confined login or WSL's reserved ranges, with the command that lifts it; or another port, as above (§10). |
| `already runs, on 3314, not 3324` | It runs on another port than the one asked: `./dev-mysql.sh stop` first. |
| `MySQL could not be initialised` | the first start failed; read `work/mysql/init.err`. The half-made data is removed, so the next `start` tries again. |

**After a crash** (the machine lost power, or the server was killed): `./dev-mysql.sh
start` as usual. MySQL recovers its data at start: `Starting XA crash recovery...`
and `ready for connections` in `mysqld.err`. A row written before the crash was still
there after it.

**To start afresh**, with empty databases:

```bash
./dev-mysql.sh stop
rm -rf work/mysql           # only this server's files; the machine's MySQL is elsewhere
./dev-mysql.sh start        # makes them again, about 5 s
```

**The machine's own MySQL** (a MySQL 5 on 3306, say) is a different server: it has its
own systemd service (`systemctl list-units | grep -i -E 'mysql|maria'` names it), its own
files, and its own port.
None of these commands reads, starts, stops or changes it. Do not stop it to free a
port; give this one another port instead.

## 4. A release

After a change to the code, what to rebuild, try, ship and restart:
[07](07-after-a-code-change.md).

`build-offline.sh` leaves it in `backend/target/release/`: a directory and its
`.tar.gz`, with its own Java runtime, j-redis, MySQL and nginx
([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)); its
`VERSIONS.md` says what is inside. In a shell that sourced `dev-env.sh`,
`(cd backend && scripts/make-release.sh)` makes it alone, from what `work/m2`
already holds.

## 5. Running it by hand

The whole backend on this machine, from the release, each service by its own script
([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)):
the development MySQL, j-redis, the backend (the platform, a worker, the gateway and an arena,
on the release's own runtime, against `backend_dev`), and the release's nginx in front of
them on the site's ports (§5.0). `run-local.sh` runs the four in order.

```bash
./run-local.sh start             # dev-mysql.sh, dev-jredis.sh, dev-backend.sh, dev-nginx.sh, each started
./run-local.sh status
./run-local.sh stop              # nginx, the backend, j-redis; the MySQL keeps running: ./dev-mysql.sh stop
```

Each service alone:

| Script | Commands | What it runs |
|---|---|---|
| `dev-mysql.sh` | `start` `stop` `restart` `status` `test` | the development MySQL (§3) |
| `dev-jredis.sh` | `build` `start` `stop` `restart` `status` `test` | j-redis from its own build, on `vendor/`'s JDK |
| `dev-backend.sh` | `build` `start` `stop` `restart` `status` `test` `bots` | the platform, the worker, the gateway and the arena; one or some by name |
| `dev-nginx.sh` | `start` `stop` `restart` `status` `test` | the release's nginx in front of them (§5.0) |

```bash
./dev-backend.sh restart platform                              # one alone, the others untouched; all: restart
./dev-backend.sh stop arena                                    # one alone
./dev-backend.sh bots                                          # twenty bots for fifteen seconds; or: bots 100 30
./dev-jredis.sh build && ./dev-jredis.sh restart               # a change to j-redis, running
./run-local.sh stop && SKIP_TESTS=1 ./dev-backend.sh build && ./run-local.sh start   # a change to the backend
```

**`build`** compiles, runs the tests and, for the backend, makes the release; `SKIP_TESTS=1`
skips the tests. The backend's needs j-redis built first; `build-offline.sh` is the two in
order. **The backend's `build` empties `backend/target/`**, the running release with it, nginx's
too: stop the run first ([07](07-after-a-code-change.md)). **`test` checks the running service now**, through the path a client takes, a line
`ok` or `FAILED` for each check, and exit 1 on a failure:

| | `test` checks |
|---|---|
| `dev-mysql.sh` | the `backend` user logs in over TCP to both databases; `backend_dev`'s schema version |
| `dev-jredis.sh` | `PING`, then a key of its own set, read and deleted |
| `dev-backend.sh` | the four running, nginx running, and two bots through nginx: registered, logged in, the lobby, a ticket, five seconds played and left |
| `dev-nginx.sh` | `nginx -t`; 80 a 308 to `https`; 443's certificate for the address and the platform behind it; 9001's for the arena's name |

**Each refuses what it needs and does not have**, naming the script that gives it: the
backend's `start` with no MySQL or no j-redis, its `test` and `bots` with no nginx, j-redis's
`start` with no build. None starts another's service but `dev-backend.sh build`, which starts the
development MySQL for its tests and stops it after; `run-local.sh` does them in order.

A process restarted alone picks up what changed for it: a new release, or settings given
to the command. Each service on a server, and on this machine, is in
[operations/07](../operations/07-start-stop-restart.md).

The bots play as a client does: they register, log in, open the lobby, ask for a
match and join the arena they are given, the API and the lobby straight on 8080 and 8081,
each bot from an address of its own, and the match through nginx on 127.0.0.1:9001
(`BACKEND_BOT_VIA`), the certificate checked for the name the grant gives: they do not show the
announced `<address>` reachable, a client on another machine does. The run ends with `welcomes 20
disconnects 0   kicks 0` and `stays 20, each joined and left on purpose`. Each backend process logs
to `work/run/<process>.log`, j-redis to `work/run/store.log`, nginx to `work/nginx/logs/`.

| Service | Listens on | To change |
|---|---|---|
| MySQL | 127.0.0.1:3314 | `MYSQL_PORT` |
| j-redis | 127.0.0.1:6380 | `STORE_PORT` |
| platform (the API) | 127.0.0.1:8080, `GET /health` | `PLATFORM_PORT` |
| gateway (the lobby) | 127.0.0.1:8081, `ws://…/lobby` | `GATEWAY_PORT` |
| arena | 127.0.0.1:9021; it announces `<address>:9001`, TLS as `a1.arena.internal`, which nginx routes to it | `ARENA_PORT`, `ARENA_TLS_NAME` |
| worker | none | |
| nginx | every IPv4 address: 80, 443, 9001 (§5.0) | `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `NGINX_MATCH_PORT` |

The settings are `dev-env.sh`'s, the same for every script: give every command the same,
`start` and `bots` alike. A port already taken, or one a policy of the machine refuses to
this user, is named before anything starts, with its cause (§10). `stop` stops only
processes it started from this checkout, whatever their pid files say. An arena still
running a match drains it first, for up to eleven minutes (D-29). What each process's
arguments mean: [backend/README](../../backend/README.md#running-it).

### 5.0 Clients on other machines: the front

Everything but nginx listens on 127.0.0.1. nginx is the way in, as on the site (D-82): the
release's own, with the release's configuration rewritten into `work/nginx/conf`, on the
site's ports and every IPv4 address.

```bash
./dev-nginx.sh start             # for this machine's first address; another: export PUBLIC_ADDRESS=192.168.56.10 before ./run-local.sh start (both read it)
./dev-nginx.sh test
./dev-nginx.sh status
```

| | Here | On the site |
|---|---|---|
| The player API and the lobby | `https://<address>/v1/...`, `wss://<address>/lobby` | the same, 443 |
| Plain HTTP | `http://<address>/...`: a 308 to `https`, the method and the body kept | closed (D-82) |
| Matches | `<address>:9001`, TLS, routed by the arena's name to 127.0.0.1:9021 | 9001, to each machine's arenas |
| The configuration | the release's `nginx-conf/`, rewritten into `work/nginx/conf` | `/etc/backend/nginx` |
| The certificate | `work/nginx/tls`, naming the address, 127.0.0.1 and the arena's name, from a root of its own, `work/nginx/ca/ca.pem` | operations/06 |

**80 and 443 need root**, or a lowered threshold; its workers run as `nobody`. As another
user, move them (9001 too, by choice), for `dev-nginx.sh` and `dev-backend.sh` alike, whose arena announces
nginx's: `export NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443`.

**The address** is `PUBLIC_ADDRESS`, this machine's first IPv4 address (`hostname -I`) unless
given: the certificate names it and the arena announces it. `hostname -I` keeps no order, so read
the address `dev-nginx.sh start` prints (`… for <address>`), and give it when it is not the one
clients dial. Give both the same; another:
`export PUBLIC_ADDRESS=<it>`, then `./dev-backend.sh restart arena && ./dev-nginx.sh restart`.

**Clients trust `work/nginx/ca/ca.pem`, and nothing else.** The root is made once and kept,
so a copy given to a client stays good; the certificate is issued again when the address
changes. `curl --cacert ca.pem`; Postman's Settings, Certificates, CA certificates; the
headless client's `--trust`; the app on a phone trusts the root it was built with alone, so
the build for these phones carries this `ca.pem` (`BackendClient`'s root certificate).

**The firewall is the machine's administrator's**: `start` prints the line that opens 80,
443 and 9001 (`firewall-cmd` on RHEL and CentOS) and runs nothing. A VM on NAT networking
is reached by its host alone: give it bridged networking, or have the host forward the
three ports.

### 5.1 Trying each interface

| Interface | Where, on a run by hand | Try it with |
|---|---|---|
| The player API, HTTP | on this machine `http://127.0.0.1:8080/v1/...`; from another, through nginx, `https://<address>/v1/...` (§5.0) | `curl`, Postman |
| The admin API, HTTP | `http://127.0.0.1:9120/admin/...`, with `Authorization: Bearer <secret>`; this machine's alone | `curl`, Postman |
| The lobby, WebSocket | `ws://127.0.0.1:8081/lobby`; through nginx, `wss://<address>/lobby` | Postman's WebSocket request; `docs/api/lobby-example.mjs` (Node 22 or later) |
| A match, binary over TCP | `<address>:9001`, TLS, through nginx: the address a grant names | `./dev-backend.sh bots`. `curl` and Postman cannot speak it. |

Every route, message and frame, with real examples: [docs/api](../api/README.md). Each
command below was run as written on 2026-10-19, on fresh data, and the outputs shown are what
it printed; the arena's line and the bots' run again on 2026-11-01, behind nginx (D-92), this
machine's address shown as `<address>`. Your token will differ. The arena has four rooms of 150 places, the public
area's and three for matches and private rooms.

**Start it with payments simulated and the admin API on.** The Postman collection needs
both. `run-local.sh` and `dev-backend.sh` pass the shell's environment to every process,
and these three are read by the platform:

```bash
(umask 077; head -c 24 /dev/urandom | base64 > work/admin-token)   # the admin API's secret, kept in work/
export BACKEND_PAYMENT_PROVIDER=simulated BACKEND_ADMIN_ADDR=127.0.0.1:9120 \
       BACKEND_ADMIN_TOKEN_FILE=$PWD/work/admin-token
./run-local.sh start
```

**HTTP, with `curl`:**

```console
$ curl -s http://127.0.0.1:8080/health
{"status":"ok"}
$ curl -s -X POST http://127.0.0.1:8080/v1/accounts -H 'Content-Type: application/json' \
       -d '{"username":"try_1","password":"example-password-1"}'
{"playerId":1}
$ curl -s -X POST http://127.0.0.1:8080/v1/sessions -H 'Content-Type: application/json' \
       -d '{"username":"try_1","password":"example-password-1"}'
{"token":"vEw_g8LL08jNXDiHOeFlswTCagwkg-lykE9z9u2mNYw","playerId":1,"expiresInSeconds":94879}
$ curl -s http://127.0.0.1:8080/v1/inventory -H "Authorization: Bearer vEw_g8LL08jNXDiHOeFlswTCagwkg-lykE9z9u2mNYw"
{"coins":0,"gems":0,"items":[]}
$ curl -s http://127.0.0.1:9120/admin/arenas -H "Authorization: Bearer $(cat work/admin-token)"
[{"name":"arena-1","host":"<address>","port":9001,"players":0,"maxPlayers":600,"tls":true,"rooms":1,"maxRooms":4}]
```

**HTTP, with Postman**: import `docs/api/backend.postman_collection.json`, set its
variables:
- `baseUrl` to `http://127.0.0.1:8080`;
- `adminUrl` to `http://127.0.0.1:9120`;
- `adminToken` to the contents of `work/admin-token`.

Then run the collection whole. **It is a scenario for a fresh test stack, to run once**:
it ends the current season, closes a room and bans its third player. Run again on the same
data, a few checks fail on what the last run left (`409 season_ended`, a room no longer
there). To run it again from scratch:

```bash
./run-local.sh stop && ./dev-mysql.sh stop
rm -rf work/mysql            # the development MySQL's data, made again at the next start
./run-local.sh start         # with the three variables above still exported
```

On fresh data it passed 131 of 131, run straight after the lobby example below.

**The lobby WebSocket.** Postman's WebSocket request, by hand:
1. New, WebSocket; URL `ws://127.0.0.1:8081/lobby`; Connect.
2. Send `{"t":"auth","id":1,"d":{"token":"<a token from a login>"}}` first. It answers `auth.ok`.
3. Then any message of [02 §4](../api/02-lobby-websocket.md#4-what-a-client-sends), for example
   `{"t":"ping","id":2}` or `{"t":"queue.join","id":3,"d":{"mode":"duel"}}`.

A party or a duel needs a second connection, as another player. Or the whole session
scripted, with two players, every frame printed and checked:

```console
$ node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby http://127.0.0.1:9120 "$(cat work/admin-token)"
--- two players, over http://127.0.0.1:8080
...
--- every answer as expected
```

It needs Node 22 or later, which the repository does not carry.

**A match, the arena's socket.** It is binary, over TCP
([02-networking](../detailed-design/02-networking.md)). The bots speak it as a client
does: they log in, ask the lobby for a seat, dial the arena the grant names, join, send
input ten times a second, and leave.

```console
$ ./dev-backend.sh bots 20 15
lobby: 20 of 20 bots registered, logged in and got a ticket in 4.4 s (220 ms of wall time per bot)
20 bots connected to <address>:9001

welcomes 20   disconnects 0   kicks 0 (last reason 0)   lobby failures 0
over 15.0 s with every bot in: snapshots 4499   bytes 364981
mean frame 81.1 B   per bot 15.00 snapshots/s, 1.19 KB/s (payload only)
stays 20, each joined and left on purpose
```

Read it as follows:
- `welcomes` should equal the bots;
- `disconnects` and `kicks` should be 0;
- each bot gets 15 snapshots a second.

Its options (a profile, a proxy, staying and coming back) are in
[tools/README](../../backend/tools/README.md#botclient). Whether the port answers at all:
`(exec 3<>/dev/tcp/127.0.0.1/9021) && echo open` for the arena, 9001 for nginx in front of it.

## 6. Debugging

**A process from the release, under a debugger**: `DEBUG=1` makes each Java
process listen for one, on 127.0.0.1 only, without waiting for it.

```bash
DEBUG=1 ./run-local.sh start                  # every Java process; one alone: DEBUG=1 ./dev-backend.sh restart platform
```

| Process | Debugger port |
|---|---|
| platform | 5005 |
| worker | 5006 |
| gateway | 5007 |
| arena | 5008 |
| j-redis, the store | 5009 |

With `DEBUG=1` the backend's processes run on `vendor/jdk-21`, the same Java version, not
the release's runtime (j-redis always does): its modules leave out the debugger's agent,
`jdk.jdwp.agent` ([09 §4.1](../detailed-design/09-release-and-packaging.md#41-the-runtime)). Attach VS Code ([05 §4](05-vscode.md#4-debugging)), or the
JDK's own debugger:

```bash
vendor/jdk-21/bin/jdb -attach 127.0.0.1:5005       # then: threads, stop in <class>.<method>, cont
```

**A test under a debugger**: from VS Code, or Maven's own way, which waits on 5005
until a debugger attaches:

```bash
(cd backend && mvn -pl platform test -Dtest=AuthServiceTest -Dmaven.surefire.debug)
```

**What a running process is doing**, without a debugger, with the JDK's tools on its pid
(`work/run/<process>.pid`):

```bash
vendor/jdk-21/bin/jcmd $(cat work/run/platform.pid) Thread.print       # every thread's stack
vendor/jdk-21/bin/jcmd $(cat work/run/arena.pid) GC.heap_info
```

## 7. The drills

The drills are the live tests that drive a release with the headless client.
They need the .NET 8 SDK and the client built
(`cd client && dotnet build Headless`), which the repository does not carry. Their
database is `backend_dev` on the development MySQL (§3.1), which each starts when it
is not running and stops after; the machine's own MySQL on 3306 is never touched
(O-40). A run by hand uses `backend_dev` too, so do not run both at once: a drill's
`season` scenario ends the current season for both. They start their own servers on
ports 3307 to 3309, 6390 to 6392, 8093 and 8094, 9011 and 9195 to 9199.
`backup-drill.sh` seeds from `backend_dev`, so it needs a drill or a run by hand
first to have made its schema; it says so if not.

```bash
TMPDIR=<a scratch directory> RELEASE=$PWD/backend/target/release/backend-0.1.0-SNAPSHOT \
    client/headless-drill.sh play duel                    # any of the scenarios its header lists
TMPDIR=<a scratch directory> RELEASE=... backend/scripts/backup-drill.sh
```

## 8. The checks on RHEL 9

With Docker, each on a release:

| Script (`backend/scripts/`) | Checks |
|---|---|
| `check-release-el9.sh` | the release in UBI 9 with nothing installed: every binary's libraries, MySQL, j-redis, the four processes, nginx |
| `check-units-el9.sh` | the units under RHEL 9's systemd, installed as the deploy document says, nginx as one machine's front: HTTPS and a match connection through 9001, the backend's root trusted alone |
| `check-install-guide-el9.sh` | the [install guide](../operations/03-install-guide.md), every step as written, with no network |
| `check-ports-el9.sh` | in Rocky Linux 9.5 with RHEL 9's firewalld: the [ports and the firewall](../operations/05-ports-and-firewall.md) as written, the front's nginx ending TLS with a certificate from the backend's own CA, and the C# headless driver playing through 443 and 9001 (.NET 8 too; about ten minutes) |

Without Docker, `backend/scripts/check-certificates.sh` checks the certificate scripts, with
openssl alone, in a few seconds ([operations/06 §9](../operations/06-certificates.md#9-testing-and-development)),
and `backend/scripts/check-nginx-logs.sh` the rotation of nginx's logs, with the release's nginx on a
loopback port (D-85).
And `vendor/build-nginx.sh` compiles nginx again ([vendor/README](../../vendor/README.md)).

## 9. The client

The engine-free core, its tests and the headless driver, with the .NET 8 SDK:
`cd client && dotnet test` (read `Total tests:`); the Unity package:
`client/unity-package.sh && client/unity-check.sh` (compiled against stubs; nothing
here runs Unity). [client/README](../../client/README.md).

## 10. When something fails

| What you see | What it means | What to do |
|---|---|---|
| `[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:…:test … on project persistence`, above it `Communications link failure` or `Access denied for user 'backend'` | Not a plugin that could not be fetched: tests failed. The database tests reached no MySQL, or one that is not theirs. Before D-80 they went to 127.0.0.1:3306. | `./build-offline.sh` from this version starts the development MySQL itself. By hand: `./dev-mysql.sh start`, in a shell that sourced `dev-env.sh`. |
| Stack traces and `ERROR` lines while the tests run, such as `room-1 - tick 161 failed` or `has not ticked for 10451 ms; abandoning it`, and the build still ends `--- done:` | Not failures. Tests that break something on purpose (a tick that throws, a spool that cannot be written, a room that hangs) check what the backend does then, and the backend logs it as it would in production. | Nothing. A test that fails stops the build with `[ERROR] Tests run: …, Failures: …` and its name under `[ERROR] Failures:` or `[ERROR] Errors:`, and its report is in `<module>/target/surefire-reports/`. |
| `dev-env.sh: vendor/jdk-21/bin/java is not executable` | the copy lost the files' modes | copy again with `git clone` or `tar` |
| `dev-env.sh: … holds a character the build cannot carry` | a space or another character in the path | move the checkout, or link to it from a path without one |
| `127.0.0.1:3314: in use (…)`, then `another port for it: MYSQL_PORT=…` | another server on the development MySQL's port | `MYSQL_PORT=<a free port>` |
| `127.0.0.1:8080: in use (…)`, then `another port for it: PLATFORM_PORT, GATEWAY_PORT or ARENA_PORT, …` | another program on that port | `PLATFORM_PORT=<a free port>`, and the same for every later command |
| `127.0.0.1:3314: refused (Permission denied)` (or 6380, 8080, 8081, 9021, nginx's 9001, the drill's), then `A port above 1023 is any user's on Linux: a refusal is a policy of this machine. Found:` and the policy | Not the user's rank: a policy of this machine keeps the port from users. `check-bind.sh` names the one it finds, the unprivileged-port threshold raised above the port (`net.ipv4.ip_unprivileged_port_start`), a login SELinux confines (`user_u`, `staff_u`) with `selinuxuser_tcp_server` off, or WSL's reserved port ranges; or says it knows none ([D-88](../architecture/03-decision-log.md#d-88--a-port-the-machine-refuses-is-named-with-its-policy-before-anything-starts)). Reproduced here: the threshold, as a user who is not root, and SELinux, in a Rocky 9 guest with a confined login, where the three remedies were each seen to work (`backend/scripts/check-selinux-el9.sh`, [D-90](../architecture/03-decision-log.md#d-90--the-port-checks-selinux-branch-is-proven-in-a-rocky-9-guest-and-names-the-exact-grant)); the WSL lines are from the documentation. | the command under the policy (`sudo sysctl -w net.ipv4.ip_unprivileged_port_start=1024`; `sudo setsebool -P selinuxuser_tcp_server on`, or the module from the port's own denial, or the login mapped `unconfined_u`; ports outside Windows' ranges), or other ports: `MYSQL_PORT`, `STORE_PORT`, `PLATFORM_PORT`, `GATEWAY_PORT`, `ARENA_PORT`, `NGINX_MATCH_PORT`. By hand: `backend/scripts/check-bind.sh 3314 8080` |
| `j-redis is not running on 127.0.0.1:6380: ./dev-jredis.sh start first`, or `the development MySQL is not running: ./dev-mysql.sh start first` | `dev-backend.sh start` before what it needs | the script it names, or `./run-local.sh start` for all four in order |
| `j-redis is not built: …/dev-jredis.sh build first`, or from the backend's build `j-redis is not built, and the backend needs its client, the release its server: ./dev-jredis.sh build first` | `dev-jredis.sh start`, or the backend's build, before j-redis's | `./dev-jredis.sh build` (`SKIP_TESTS=1` for speed) |
| `FAILED  nginx is not running: ./dev-nginx.sh start` (from `test`), or `nginx is not running, and matches go through it: ./dev-nginx.sh start first` (from `bots`) | `dev-backend.sh test` or `bots` with no nginx: matches go through it | `./dev-nginx.sh start` |
| `127.0.0.1:80: refused (Permission denied)` (and 443), then `Ports below 1024 are root's on Linux: 80 443` and `another port for it: NGINX_HTTP_PORT, …` | nginx started by a user who is not root: 80 and 443 are below 1024 (O-48) | as root; or `sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80`, as the check prints; or other ports, for `dev-nginx.sh` and `dev-backend.sh` alike: `export NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443` |
| `127.0.0.1:443: in use (…)`, then `another port for it: NGINX_HTTP_PORT, NGINX_HTTPS_PORT or NGINX_MATCH_PORT` | another program on one of nginx's ports: another web server, or an nginx of the machine's own | stop it, or other ports, as the line above |
| `PUBLIC_ADDRESS=…: an IPv4 address or a host name, the one clients dial` | `PUBLIC_ADDRESS` given something else, an IPv6 address | `export PUBLIC_ADDRESS=<the IPv4 address clients dial>`, then `./dev-backend.sh restart arena && ./dev-nginx.sh start` |
| `the release's nginx configuration has what this script cannot rewrite for this machine (D-92):` and the lines | the release's `nginx-conf/` gained a path, an address, a port or an upstream that `dev-nginx.sh` does not rewrite; nothing was started | teach the rewrite the new line ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)); the generated files are left in `work/nginx/conf.new` to read |
| `FAILED  443: the certificate verifies, but the platform behind it does not answer (502)` | nginx is up, the platform is not | `./dev-backend.sh start` |
| On the client: `curl: (60) SSL certificate problem: unable to get local issuer certificate` | the client does not trust nginx's root | give it `work/nginx/ca/ca.pem`: `--cacert`, Postman's CA certificates, the headless client's `--trust`, the app's build |
| On the client: `no alternative certificate subject name matches` | it dialled an address the certificate does not name | `export PUBLIC_ADDRESS=<the address it dials>`, then `./dev-backend.sh restart arena && ./dev-nginx.sh restart`: the certificate is issued again, the root kept |
| On the client: the connection times out | the machine's firewall, or a VM on NAT networking that only its host can reach | the `firewall-cmd` line `dev-nginx.sh start` printed; a bridged network or a port forward for the VM |
| `Cannot access java21-offline in offline mode` | `-o` with the bundle's settings | drop `-o` (§2) |
| `class file has wrong version 65.0, should be 52.0`, or `release version 21 not supported` | Maven ran on Java 8: `dev-env.sh` was not sourced, or `/etc/mavenrc` won | `. ./dev-env.sh` first; it sets `MAVEN_SKIP_RC` |
| `Could not resolve dependencies … com.jredis:j-redis-client` | the backend built before the store, by hand | `./dev-jredis.sh build` first, or `./build-offline.sh` |
| `no release in backend/target/release: …/dev-backend.sh build first`, or from `dev-nginx.sh` `no release with nginx in backend/target/release: ./dev-backend.sh build first` | `dev-backend.sh` or `dev-nginx.sh` before a build | `./dev-backend.sh build`, or `./build-offline.sh` for both (`SKIP_TESTS=1` for speed) |
