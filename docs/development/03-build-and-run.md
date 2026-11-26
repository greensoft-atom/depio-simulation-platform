# Build, test, run and debug: a guide

This guide is for a developer or an operator who has a copy of the repository and wants the backend built,
tested, running and under a debugger. It works on a machine with no network, using only what the repository
carries: the JDK, Maven and MySQL in [`vendor/`](../../vendor/README.md), and every library in
[`java21-offline/`](../../java21-offline/README.md). Nothing is installed, nothing is written outside the
repository's folder, and of the machine's own software only its basic tools are used (§1)
([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)).
A Java and a MySQL already on the machine (a Java 8, a MySQL 5) are neither used nor changed.

It covers what you need (§1), the build (§2), the tests and the development MySQL (§3), a release (§4), running
the whole backend by hand and trying each interface (§5), debugging (§6), the live drills (§7), the checks on
RHEL 9 (§8), the client (§9), and what to do when something fails (§10). Run every command from the repository's
root.

Other guides:
- to install the backend on a server instead, see the [install guide](../operations/03-install-guide.md);
- for VS Code, see [05-vscode](05-vscode.md);
- the questions asked while setting up an offline machine, each answered in full, are in
  [06](06-questions-and-answers.md);
- what to rebuild, try, ship and restart after a code change is in [07](07-after-a-code-change.md).

**Run as written on 2026-10-17** in UBI 9.5 (RHEL 9's own userspace), with no network, as an ordinary user:
- the machine was set up as the owner's is: Java 8 installed as the default (`alternatives`, `JAVA_HOME`, and an
  `/etc/mavenrc` pointing Maven at it), `/tmp` mounted `noexec`, and a listener on 3306 standing in for its MySQL;
- `./build-offline.sh` with every test, which passed;
- `run-local.sh` with twenty bots (the script of then, before D-92); `DEBUG=1` with `jdb` attached; `jcmd` on a
  running process;
- by hand, the store's install (§2) and one test class on the development MySQL (§3). The other by-hand lines were
  not run separately, though `build-offline.sh` runs them all.

No connection reached 3306, and nothing was written to `~/.m2`. Outside the checkout, only the JVM's own
`/tmp/hsperfdata_<user>/` was written (§1). Not run as written: the drills (§7), which then needed .NET (since D-116,
Unity 2019's editor in `work/unity` instead), and the RHEL 9 checks (§8) other than the install guide's, which passed
the same day.

## 1. What you need, and what the repository touches

| For | Needs |
|---|---|
| Building, testing, running and debugging the backend | Linux on x86-64 with bash, coreutils, findutils, `sed`, `tar` and `gzip`. For nginx in front of a run by hand (§5.0), also `openssl`, `curl` for its `test`, and `hostname` for the default address. No Java, Maven, MySQL or network. **glibc 2.28 or later**, for every binary the release carries (nginx is built on Rocky 8; MySQL's own build and its two libraries are RHEL 8's; D-100): RHEL 8 and 9 and their rebuilds, Ubuntu 20.04, Debian 10 and later. MySQL also takes `libstdc++`, and for its client the machine's `libncurses.so.6` and `libtinfo.so.6` (`ncurses-libs` on RHEL, `libncurses6` on Ubuntu), which every install but the smallest has ([DOC-39](../defects.md#7-documentation)). Run as written as a user who is not root on Ubuntu 24.04 and Rocky 9.8, from a fresh clone, with no network. |
| The checks on RHEL 9, and compiling nginx again | Docker |
| The client, the Unity package and the drills | Unity 2019.4.28f1's Linux editor in `work/unity`, which the repository does not carry. `client/unity-env.sh` fetches it once (1.8 GB, its SHA-256 checked), and the NuGet packages the client's tests and driver take, with `curl`, `xz` and `unzip`: the network once, or a copy of another machine's `work/unity`. Its compiler builds the client, for .NET Framework 4.5, and its Mono runs it. No .NET ([D-116](../architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear), §7, §9) |

**Copy the repository whole**, with `git clone` or as one `tar` archive. A copy made file by file can lose the
files' execute bits, and then nothing runs (`dev-env.sh` says so). **Its path may hold letters, digits and
`. _ - + @ /` only**: no spaces. If the path you have holds a space, make a symbolic link without spaces to it, and
work through the link.

**Any user may run it, root or not.** Nothing here needs root but nginx in front of a run by hand, on the site's 80
and 443 (§5.0, D-92); every other port is above 1023. If the machine still refuses a port to the user
(`Permission denied`), that is a policy of the machine, and the scripts name it before they start anything (§10).

**Everything a build, a test or a run writes is in `work/`**, beside this repository's `README.md`. It is never
committed. Delete it to start afresh: the next build copies the libraries it needs from the bundle again. But
`work/` also holds the development MySQL's data, and nginx's root certificate, `work/nginx/ca/ca.pem` (§5.0).
After a delete, `dev-nginx.sh start` makes a new root, and every client that was given the old one, the app's
build among them, must be given the new one.

| In `work/` | What it holds | Written by |
|---|---|---|
| `settings.xml` | Maven's settings: the bundle as Maven's only repository, `work/m2` as its local one | `dev-env.sh` |
| `m2/` | Maven's local repository, copied from the bundle as the build needs it | Maven |
| `tmp/` | the temporary files of Maven, the tests and MySQL (`TMPDIR`, `java.io.tmpdir`) | all of them |
| `mysql/` | the development MySQL's data, socket and logs (§3) | `dev-mysql.sh` |
| `run/` | a run by hand: each process's log and pid, the store's files, the arena's spool (§5) | `dev-jredis.sh`, `dev-backend.sh` |
| `nginx/` | nginx in front of a run by hand: its configuration, rewritten from the release's, its logs and pid, the certificate and its root (§5.0) | `dev-nginx.sh` |
| `unity/` | Unity 2019.4.28f1's Linux editor, whose compiler and Mono build and run the C# client, and the NuGet packages the client's tests and driver take (§1). Deleted, it is fetched again | `client/unity-env.sh` |

**`dev-env.sh` is the environment.** Every script beside it reads it first. It sets:
- `JAVA_HOME` and `PATH` on `vendor/jdk-21` and `vendor/maven-3.9`, whatever the machine's `JAVA_HOME` says;
- Maven to read `work/settings.xml` (`MAVEN_ARGS`), and neither `/etc/mavenrc` nor `~/.mavenrc`
  (`MAVEN_SKIP_RC`), either of which may point Maven at another Java;
- temporary files in `work/tmp`;
- the tests' database to the development MySQL (`JDBC_URL`);
- a run by hand's ports, address and way (TLS or `PLAIN=1`), the same for every script (§5).

What each line does is in [06 §3](06-questions-and-answers.md#3-what-does-dev-envsh-do-line-by-line). To work by
hand in a shell, source it first:

```bash
. ./dev-env.sh
java -version          # 21.0.12.1, vendor/'s, not the machine's
mvn -v                 # 3.9.16, vendor/'s, on that Java
```

**Other paths in these documents** are not this machine's:
- **`/opt/backend`** is where a release is installed on a server ([install guide](../operations/03-install-guide.md)).
- **`/opt/jdk21`, `/opt/maven` and `/opt/dotnet`** are the development VM's own tools
  ([02 §3](02-working-here.md#3-the-development-machine)). A clone does not need them.
- **`/tmp`** appears only as the default scratch directory of the drills, of the RHEL 9 checks (§7, §8), and of
  `vendor/`'s refresh scripts, which need the internet. Name your own with `TMPDIR=`.
- **The JVM still keeps two things of its own in `/tmp`**, whatever `java.io.tmpdir` says:
  - `hsperfdata_<user>/`, each running JVM's performance counters, which the JVM removes at exit, leaving the
    directory empty;
  - while `jcmd` or `jdb` attaches to a process, that process's attach socket.

  Neither runs code from `/tmp`, so a `noexec` `/tmp` is no obstacle (checked).

## 2. Build

**One command builds everything, offline**: `j-redis-service`, then the backend with every test, then a release.

```bash
./build-offline.sh                # with every test: 34 minutes on 2026-10-17, about an hour on a loaded machine
SKIP_TESTS=1 ./build-offline.sh   # without the tests: about fifteen minutes
```

- With the tests, it starts the development MySQL (§3) and stops it at the end. It leaves the MySQL alone when it
  was already running, or when `JDBC_URL` names another server.
- It ends with `--- done:` and the release's directory.
- On a machine that already holds the development VM's `~/.m2`, it still reads nothing from it.
- It is `./dev-jredis.sh build` followed by `./dev-backend.sh build` (§5).

**By hand**, in a shell that sourced `dev-env.sh`: build the store first, since the backend builds against its
client.

```bash
(cd j-redis-service && mvn install -DskipTests)
(cd backend && mvn install -DskipTests)
(cd backend && mvn -pl platform -am install)      # one module, with what it needs and its tests
```

**Do not add `-o`.** Maven's offline flag refuses the bundle too, because the bundle is a `file://` repository.
The settings already make the bundle Maven's only repository, so nothing is fetched from the network.

## 3. The tests

Most of the backend's tests need nothing. The tests of `persistence`, `worker` and `platform` need a MySQL, and
they get the **development MySQL**: `vendor/`'s 8.4, with its files in `work/mysql`, on 127.0.0.1:3314.
- It reads no option file, so a `/etc/my.cnf` meant for the machine's own MySQL is ignored.
- It has its own port, data and socket, so it never meets the machine's own MySQL.

How to start it, stop it and restart it, step by step, is §3.1.

**Which database the tests use.** The tests read `JDBC_URL`, which `dev-env.sh` points at `backend_test` on the
development MySQL. When it is unset, as when a test runs from an editor that has not sourced `dev-env.sh`, the
tests use the same database (`DatabaseSettings.TEST_URL`, O-40). `DB_USER` and `DB_PASSWORD` default to
`backend` and `backend-dev-password`.

**The tests wipe the database they are given**, so its name must end in `_test`. Never point `JDBC_URL` at a server
whose `backend_test` you want to keep. If port 3314 is taken, give `MYSQL_PORT=<a free port>` to every script,
sourced or run.

To run one class, or one module:

```bash
. ./dev-env.sh && ./dev-mysql.sh start
(cd backend && mvn -pl persistence test -Dtest=AccountRepositoryTest)
(cd backend && mvn -pl platform test)
```

**Counts.** A whole backend build ran 1 064 tests on 2026-10-06 (plan item 86). On 2026-10-17,
`build-offline.sh` passed them all again, with j-redis's 227. The backend's count was not kept then, because the
release step's `clean` removes the test reports. The counts per module are in each module's README.

### 3.1 The development MySQL, step by step

Every command runs from the repository's root and needs nothing first, not even `dev-env.sh`: the script reads it
itself. Each was run as written on 2026-10-19.

| Command | What it does |
|---|---|
| `./dev-mysql.sh start` | Starts it, and returns once it answers. The first time, it first makes its data in `work/mysql/data` (about 5 s). Every start makes sure of `backend_test`, `backend_dev` and the user `backend`. If it is already running, it prints where it runs, and changes nothing else. |
| `./dev-mysql.sh status` | Says whether it runs, and on which port. For scripts: exit status 0 if it runs, 1 if not. |
| `./dev-mysql.sh stop` | Shuts it down cleanly, and returns once it has ended. If it is not running, it says so. |
| `./dev-mysql.sh restart` | `stop`, then `start`. The data is kept. |
| `./dev-mysql.sh test` | Checks that it answers: the `backend` user logs in over TCP to both databases, and `backend_dev`'s schema version is named. |

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
- `./dev-backend.sh build`, and so `./build-offline.sh`, starts it for the tests and stops it at the end. It does
  not when the MySQL was already running, when `SKIP_TESTS=1` is given, or when `JDBC_URL` names another server.
- `./run-local.sh start` starts it first, before j-redis, the backend and nginx. `./run-local.sh stop` leaves it
  running: stop it with `./dev-mysql.sh stop`.
- It does not start with the machine. After a reboot, run `./dev-mysql.sh start`.

**Connecting to it**, with `vendor/`'s client:

```bash
# as the backend's user, over TCP, as the tests and the processes connect
vendor/mysql-8.4/bin/mysql --no-defaults -h 127.0.0.1 -P 3314 --protocol=TCP \
    -u backend -pbackend-dev-password backend_dev

# as root, which has no password and is reached only through the socket, from the data directory
(cd work/mysql/data && ../../../vendor/mysql-8.4/bin/mysql --no-defaults -S mysqld.sock -u root)
```

`--no-defaults` keeps the client from reading `/etc/my.cnf` or `~/.my.cnf`, which may name the machine's own
MySQL. Use `vendor/`'s client. The `mysql` on the machine's `PATH` belongs to the machine's own MySQL 5, and was
not tried against this 8.4 server.

**Where its files are**, all in `work/mysql`:

| File | What |
|---|---|
| `data/` | the databases; `data/mysqld.sock` is its socket while it runs |
| `mysqld.err` | its log: start, stop, crash recovery, errors |
| `init.err` | the log of the first start's initialisation |
| `mysqld.pid` | its process id, while it runs |

**When it does not start**, `start` prints the end of `work/mysql/mysqld.err`. Its messages:

| What `start` says | What to do |
|---|---|
| `127.0.0.1:3314: in use (…)`, then `another port for it: MYSQL_PORT=…` | Another program has the port. Choose another: `MYSQL_PORT=3324 ./dev-mysql.sh start`. Then give every command the same `MYSQL_PORT` (`dev-env.sh`, the tests and `run-local.sh` read it), or `export MYSQL_PORT=3324` once in the shell. VS Code's settings name 3314: change it in `.vscode/settings.json` and `.vscode/launch.json` too. `stop` and `status` find it on any port. |
| `127.0.0.1:3314: refused (Permission denied)`, and under it the policy found | A policy of this machine keeps the port from this user (on Linux, ports above 1023 are any user's). The lines under it name the policy, with the command that lifts it: the unprivileged-port threshold raised, a login that SELinux confines, or WSL's reserved ranges. Or use another port, as above (§10). |
| `already runs, on 3314, not 3324` | It runs on another port than the one asked for. Run `./dev-mysql.sh stop` first. |
| `MySQL could not be initialised` | The first start failed: read `work/mysql/init.err`. The half-made data is removed, so the next `start` tries again. |

**After a crash** (the machine lost power, or the server was killed): run `./dev-mysql.sh start` as usual. MySQL
recovers its data at start: `Starting XA crash recovery...` and `ready for connections` appear in `mysqld.err`. A
row written before the crash was still there after it.

**To start afresh**, with empty databases:

```bash
./dev-mysql.sh stop
rm -rf work/mysql           # only this server's files; the machine's MySQL is elsewhere
./dev-mysql.sh start        # makes them again, about 5 s
```

**The machine's own MySQL** (a MySQL 5 on 3306, say) is a different server. It has its own systemd service
(`systemctl list-units | grep -i -E 'mysql|maria'` names it), its own files, and its own port. None of these
commands reads, starts, stops or changes it. Do not stop it to free a port: give this one another port instead.

## 4. A release

After a change to the code, what to rebuild, try, ship and restart is in [07](07-after-a-code-change.md).

`build-offline.sh` leaves the release in `backend/target/release/`: a directory and its `.tar.gz`. The release
carries its own Java runtime, j-redis, MySQL and nginx
([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)), and its `VERSIONS.md` says what is inside.
To make the release alone, from what `work/m2` already holds, run `(cd backend && scripts/make-release.sh)` in a
shell that sourced `dev-env.sh`.

## 5. Running it by hand

A **run by hand** is the whole backend on this machine, from the release, each service started by its own script
([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)).
It runs:
1. the development MySQL;
2. j-redis, the store;
3. the backend: the platform, a worker, the gateway and an arena, on the release's own runtime, against
   `backend_dev`;
4. the release's nginx in front of them, on the site's ports (§5.0).

`run-local.sh` runs the four scripts in that order.

```bash
./run-local.sh start             # dev-mysql.sh, dev-jredis.sh, dev-backend.sh, dev-nginx.sh, each started
./run-local.sh status
./run-local.sh stop              # nginx, the backend, j-redis; the MySQL keeps running: ./dev-mysql.sh stop
```

`./run-local.sh restart` is `stop` and then `start`. Each service alone:

| Script | Commands | What it runs |
|---|---|---|
| `dev-mysql.sh` | `start` `stop` `restart` `status` `test` | the development MySQL (§3) |
| `dev-jredis.sh` | `build` `start` `stop` `restart` `status` `test` | j-redis from its own build, on `vendor/`'s JDK |
| `dev-backend.sh` | `build` `start` `stop` `restart` `status` `test` `bots` | the platform, the worker, the gateway and the arena; one or some of them by name |
| `dev-nginx.sh` | `start` `stop` `restart` `status` `test` | the release's nginx in front of them (§5.0) |

```bash
./dev-backend.sh restart platform                              # one alone, the others untouched; all: restart
./dev-backend.sh stop arena                                    # one alone
./dev-backend.sh bots                                          # twenty bots for fifteen seconds; or: bots 100 30
./dev-jredis.sh build && ./dev-jredis.sh restart               # a change to j-redis, running
./run-local.sh stop && SKIP_TESTS=1 ./dev-backend.sh build && ./run-local.sh start   # a change to the backend
```

**`build`** compiles, runs the tests and, for the backend, makes the release. `SKIP_TESTS=1` skips the tests. The
backend's `build` needs j-redis built first; `build-offline.sh` is the two in order. **The backend's `build`
empties `backend/target/`**, and with it the release that the backend and nginx are running from: stop the run
first ([07](07-after-a-code-change.md)).

**`test` checks the running service now**, through the path a client takes. It prints a line `ok` or `FAILED` for
each check (`--` for a check it cannot make), and exits with 1 on a failure:

| | `test` checks |
|---|---|
| `dev-mysql.sh` | the `backend` user logs in over TCP to both databases; `backend_dev`'s schema version |
| `dev-jredis.sh` | `PING`, then a key of its own set, read and deleted |
| `dev-backend.sh` | the four processes running; nginx running, the same way as the command (TLS or plain); and two bots through nginx: registered, logged in, the lobby, a ticket, five seconds played, and left |
| `dev-nginx.sh` | `nginx -t`; the root certificate is RSA; 80 in clear with the platform behind it; 443's certificate for the address, with the platform behind it; 9001's certificate for the arena's name; and 80's `/match/<the arena's name>`, which the arena answers with 101 (D-115). Plain: `nginx -t` and 80 alone |

**Each script refuses to start without what it needs, and names the script that gives it**: the backend's `start`
with no MySQL or no j-redis; its `test` and `bots` with no nginx; j-redis's `start` with no build. No script starts
another's service, except `dev-backend.sh build`, which starts the development MySQL for its tests and stops it
after. `run-local.sh` runs them in order.

A process restarted alone picks up what changed for it: a new release, or settings given to the command. Each
service on a server, and on this machine, is in [operations/07](../operations/07-start-stop-restart.md).

**The bots play as a client does.** They register, log in, open the lobby, ask for a match, and join the arena they
are given. With `bots`, the API and the lobby are reached straight on 8080 and 8081, each bot from an address of
its own (sent as `X-Forwarded-For`), and the match goes through nginx on 127.0.0.1:9001 (`BACKEND_BOT_VIA`), with
the certificate checked for the name the grant gives. So the bots do not show that the announced `<address>` is
reachable; a client on another machine does. The run ends with `welcomes 20 disconnects 0   kicks 0` and
`stays 20, each joined and left on purpose`.

**Logs.** Each backend process logs to `work/run/<process>.log`, j-redis to `work/run/store.log`, and nginx to
`work/nginx/logs/`.

| Service | Listens on | To change |
|---|---|---|
| MySQL | 127.0.0.1:3314 | `MYSQL_PORT` |
| j-redis | 127.0.0.1:6380 | `STORE_PORT` |
| platform (the API) | 127.0.0.1:8080, `GET /health` | `PLATFORM_PORT` |
| gateway (the lobby) | 127.0.0.1:8081, `ws://…/lobby` | `GATEWAY_PORT` |
| arena | 127.0.0.1:9021. It announces `<address>:9001`, TLS as `a1.arena.internal`, which nginx routes to it | `ARENA_PORT`, `ARENA_TLS_NAME` |
| worker | none | |
| nginx | every IPv4 address: 80, 443, 9001 (§5.0) | `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `NGINX_MATCH_PORT` |

**Give every command the same settings.** The settings are `dev-env.sh`'s, the same for every script, so give the
same ones to `start` and `bots` alike. A port already taken, or one that a policy of the machine refuses to this
user, is named before anything starts, with its cause (§10). `stop` stops only processes it started from this
checkout, whatever their pid files say. An arena still running a match drains it first, for up to eleven minutes
(D-29), and `stop` waits up to twelve minutes for it. What each process's arguments mean is in
[backend/README](../../backend/README.md#running-it).

### 5.0 Clients on other machines: the front

**nginx is the way in, as on the site** (D-82). Everything but nginx listens on 127.0.0.1. nginx is the release's
own, with the release's configuration rewritten into `work/nginx/conf`, on the site's ports and on every IPv4
address.

```bash
./dev-nginx.sh start             # for this machine's first address; another: export PUBLIC_ADDRESS=192.168.56.10 before ./run-local.sh start (both read it)
./dev-nginx.sh test
./dev-nginx.sh status
```

`start` prints the ports, the user it runs as, the address and the way (TLS or plain), then the player API's,
the lobby's and the matches' addresses, the root that clients must trust, and the firewall's line.

| | Here | On the site |
|---|---|---|
| The player API and the lobby | `https://<address>/v1/...`, `wss://<address>/lobby` | the same, 443 |
| Plain HTTP | `http://<address>/v1/...`, `ws://<address>/lobby`: the same routes in clear ([D-107](../architecture/03-decision-log.md#d-107--developments-80-serves-the-api-and-the-lobby-itself-the-apis-health-under-v1-every-route-checked-through-nginx)) | the same, 80 ([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included)) |
| Matches | `<address>:9001`, TLS, routed by the arena's name to 127.0.0.1:9021; or in clear through 80, `/match/<the arena's name>` | 9001, and 80's `/match/`, to each machine's arenas |
| The configuration | the release's `nginx-conf/`, rewritten into `work/nginx/conf` | `/etc/backend/nginx` |
| The certificate | `work/nginx/tls`, naming the address, 127.0.0.1 and the arena's name, from a root of its own, `work/nginx/ca/ca.pem` | operations/06 |

**80 and 443 need root**, or a lowered threshold; nginx's workers run as `nobody`. As another user, move them (and
9001 too, if you choose), for `dev-nginx.sh` and `dev-backend.sh` alike, because the arena announces nginx's port:
`export NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443`.

**nginx as root, the rest as yourself** (D-92,
[D-98](../architecture/03-decision-log.md#d-98--nginx-run-by-root-beside-a-users-run-by-hand-what-that-user-needs-of-it-is-theirs-to-read)),
as on RHEL 9 with a home of mode 0700:
1. `./run-local.sh start` starts the rest, and stops at nginx, naming the ports it cannot take.
2. `sudo ./dev-nginx.sh start` starts nginx.
3. At the end, `./run-local.sh stop` stops the rest and names `sudo` for nginx; then `sudo ./dev-nginx.sh stop`.

Everything else stays yours. You can read `work/nginx/ca/ca.pem` and the certificate. `./dev-nginx.sh test` checks
everything but the configuration, which `sudo ./dev-nginx.sh test` checks too.

**`sudo` drops your shell's settings**: give them on its line, as in
`sudo PUBLIC_ADDRESS=192.168.1.20 ./dev-nginx.sh start` (and `NGINX_MATCH_PORT`, `ARENA_PORT` or `ARENA_TLS_NAME`,
if you changed them). Or lower the threshold once, `sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80`, with
that line in a file in `/etc/sysctl.d/` to keep it over a reboot, and run nginx as yourself too. That changes the
threshold for every user of the machine.

**The address** is `PUBLIC_ADDRESS`. Unless you give one, it is this machine's first IPv4 address
(`hostname -I`). The certificate names it, and the arena announces it. `hostname -I` keeps no order, so read the
address that `dev-nginx.sh start` prints (`… for <address>`), and give the right one when it is not the one clients
dial. Give the same address to both scripts. To change it: `export PUBLIC_ADDRESS=<it>`, then
`./dev-backend.sh restart arena && ./dev-nginx.sh restart`.

**Clients trust `work/nginx/ca/ca.pem`, and nothing else.** The root is made once and kept, so a copy given to a
client stays good. The certificate is issued again when the address changes. How each client is given the root:

| Client | How it trusts the root |
|---|---|
| `curl` | `curl --cacert ca.pem` |
| Postman | Settings, Certificates, CA certificates |
| the headless client | `--trust`, where it runs on .NET Framework. On Unity's Mono here its TLS stops at 1.0, which nginx refuses, so here it uses 80 in clear (D-116) |
| the app on a phone | it trusts only the root it was built with, so the build for these phones carries this `ca.pem` (`BackendClient`'s root certificate) |

**The firewall is the machine's administrator's.** `start` prints the line that opens 80, 443 and 9001
(`firewall-cmd` on RHEL and CentOS, `ufw` where that is installed), and runs nothing. A VM on NAT networking can be
reached by its host alone: give it bridged networking, or have the host forward the three ports.

**Plain, through nginx without TLS**
([D-101](../architecture/03-decision-log.md#d-101--a-plain-run-by-hand-goes-through-nginx-as-the-site-does-80-and-9001-without-tls)),
is for development alone. Give `PLAIN=1` to every command. The path is the site's (nginx in front, everything else
on 127.0.0.1), and TLS is the one difference: nginx serves the API and the lobby on 80, and passes matches on 9001
to the arena, in clear; the arena announces nginx's 9001 without TLS. There is no certificate and no root CA. 80
still needs root, as above.

```bash
export PLAIN=1 PUBLIC_ADDRESS=192.168.1.20      # the address clients dial
./run-local.sh start                            # MySQL, j-redis, the backend, then nginx plain on 80 and 9001 (as root)
sudo PLAIN=1 PUBLIC_ADDRESS=192.168.1.20 ./dev-nginx.sh start    # or nginx by sudo, the rest yours: sudo drops PLAIN
./dev-backend.sh test                           # two bots through nginx: http://, ws://, a plain match
```

| | Plain, development | TLS, here and on the site |
|---|---|---|
| The player API | `http://<address>/v1/...` (80) | `https://<address>/v1/...` (443) |
| The lobby | `ws://<address>/lobby` (80) | `wss://<address>/lobby` (443) |
| Matches | `<address>:9001`, plain, to the one arena | `<address>:9001`, TLS, routed by the arena's name |
| The firewall | 80 and 9001 | 80, 443 and 9001 (80 is in clear: here, open it only to a network you trust; on the site it is open, the owner's choice, D-115) |
| What the app is given | those two URLs, no root certificate | those two URLs and `ca.pem` |

**The app goes from one way to the other by its settings alone**: `BackendClient`'s API endpoints and lobby URL,
and its root certificate. Whether a match uses TLS is the grant's to say, and the backend sets it, so nothing else
changes in the app or the backend. Without TLS there is no name to route by, so 9001 reaches one arena: the run by
hand's one.

**nginx runs one way at a time.** `dev-nginx.sh status` says which way nginx runs, and on which ports. `dev-nginx.sh
start` and `dev-backend.sh test` refuse an nginx running the other way: for example one started by `sudo` without
`PLAIN=1`, or one left from yesterday's other way. Restart it the way you want: `sudo PLAIN=1 ./dev-nginx.sh
restart`.

**Everything crosses the network in clear**: passwords, session tokens and join tickets, readable and usable by
anyone on the network. Use it on a network you trust, and only there. Any build may take the plain way, a players'
build too
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included),
[08 §5](../detailed-design/08-client.md#5-the-lobby-and-the-api)). Android may refuse plain HTTP to an app that is
not built to allow it. That is not checked here (there is no Unity here).

### 5.1 Trying each interface

| Interface | Where, on a run by hand | Try it with |
|---|---|---|
| The player API, HTTP | on this machine `http://127.0.0.1:8080/v1/...`; from another, through nginx, `https://<address>/v1/...` (§5.0) | `curl`, Postman |
| The admin API, HTTP | `http://127.0.0.1:9120/admin/...`, with `Authorization: Bearer <secret>`; this machine's alone | `curl`, Postman |
| The lobby, WebSocket | `ws://127.0.0.1:8081/lobby`; through nginx, `wss://<address>/lobby` | Postman's WebSocket request; `docs/api/lobby-example.mjs` (Node 22 or later) |
| A match, binary over TCP | `<address>:9001`, TLS, through nginx: the address a grant names; or in clear through nginx's 80, `/match/<the arena's name>` (D-115) | `./dev-backend.sh bots`. `curl` and Postman cannot speak it. |

Every route, message and frame, with real examples, is in [docs/api](../api/README.md). Each command below was run
as written on 2026-10-19, on fresh data, and the outputs shown are what it printed. The arena's line and the bots'
run were run again on 2026-11-01, behind nginx (D-92), and show this machine's address as `<address>`. Your token
will differ. The arena has four rooms of 150 places: the public arena's room, and three for matches and private
rooms.

**Start it with payments simulated and the admin API on.** The Postman collection needs both. `run-local.sh` and
`dev-backend.sh` pass the shell's environment to every process, and the platform reads these three:

```bash
(umask 077; head -c 24 /dev/urandom | base64 > work/admin-token)   # the admin API's secret, kept in work/
export BACKEND_PAYMENT_PROVIDER=simulated BACKEND_ADMIN_ADDR=127.0.0.1:9120 \
       BACKEND_ADMIN_TOKEN_FILE=$PWD/work/admin-token
./dev-account.sh build && ./dev-account.sh start   # the account service, on 8083: players sign up and log in there (D-117)
./run-local.sh start
```

**HTTP, with `curl`:**

```console
$ curl -s http://127.0.0.1:8080/health
{"status":"ok"}
$ curl -s -X POST http://127.0.0.1:8083/v1/accounts -H 'Content-Type: application/json' \
       -d '{"username":"try_1","password":"example-password-1"}'          # the account service (D-117)
{"access_token":"eyJraWQiOiJwS29UcGxVR1pHb2VDV1hU...","token_type":"Bearer","expires_in":900,"refresh_token":"INh7kmxA9JugZ-m_OIcfndQfV0ChDL7w2C62o-KpSPk","account_id":"GZaZ3-aiO6cQ1xvmiqd8EA"}
$ curl -s -X POST http://127.0.0.1:8080/v1/sessions -H 'Content-Type: application/json' \
       -d '{"accessToken":"<its access_token>"}'
{"token":"GVgfvvt5RrKB72HV7BpyELyV8Zu-HctuEzlnGzpFssU","playerId":1,"expiresInSeconds":79767,"newPlayer":true}
$ curl -s http://127.0.0.1:8080/v1/inventory -H "Authorization: Bearer vEw_g8LL08jNXDiHOeFlswTCagwkg-lykE9z9u2mNYw"
{"coins":0,"gems":0,"items":[]}
$ curl -s http://127.0.0.1:9120/admin/arenas -H "Authorization: Bearer $(cat work/admin-token)"
[{"name":"arena-1","host":"<address>","port":9001,"players":0,"maxPlayers":600,"tls":true,"rooms":1,"maxRooms":4}]
```

`/health` is the platform's own, on 127.0.0.1:8080. Through nginx, ask `/v1/health`, which gives the same answer
(D-107).

**HTTP, with Postman.** Import `docs/api/backend.postman_collection.json`, and set its variables:
- `baseUrl` to `http://127.0.0.1:8080`;
- `accountUrl` to `http://127.0.0.1:8083`, the account service;
- `adminUrl` to `http://127.0.0.1:9120`;
- `adminToken` to the contents of `work/admin-token`.

Then run the whole collection. **It is a scenario for a fresh test stack, to run once.** It ends the current
season, closes a room and bans its third player. Run again on the same data, a few checks fail on what the last run
left (`409 season_ended`, a room no longer there). To run it again from scratch:

```bash
./run-local.sh stop && ./dev-account.sh stop && ./dev-mysql.sh stop
rm -rf work/mysql            # the development MySQL's data, made again at the next start, the account service's too
./dev-mysql.sh start && ./dev-account.sh start
./run-local.sh start         # with the three variables above still exported
```

On fresh data it passed 131 of 131, run straight after the lobby example below; on 2026-11-26, with the account
service beside it (D-117), 145 requests and 159 checks, none failed.

**The lobby WebSocket.** In Postman, by hand:
1. New, WebSocket; URL `ws://127.0.0.1:8081/lobby`; Connect.
2. Send `{"t":"auth","id":1,"d":{"token":"<a token from a login>"}}` first. It answers `auth.ok`.
3. Then send any message of [02 §4](../api/02-lobby-websocket.md#4-what-a-client-sends), for example
   `{"t":"ping","id":2}` or `{"t":"queue.join","id":3,"d":{"mode":"duel"}}`.

A party or a duel needs a second connection, as another player. Or run the whole session scripted, with two
players, every frame printed and checked:

```console
$ node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby http://127.0.0.1:9120 "$(cat work/admin-token)"
--- two players, over http://127.0.0.1:8080
...
--- every answer as expected
```

It needs Node 22 or later, which the repository does not carry.

**A match, the arena's socket.** It is binary, over TCP
([02-networking](../detailed-design/02-networking.md)). The bots speak it as a client does: they log in, ask the
lobby for a seat, dial the arena that the grant names, join, send input ten times a second, and leave.

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
- `welcomes` should equal the number of bots;
- `disconnects` and `kicks` should be 0;
- each bot gets 15 snapshots a second.

The bots' options (a profile, a proxy, staying and coming back) are in
[tools/README](../../backend/tools/README.md#botclient). To check only whether the port answers:
`(exec 3<>/dev/tcp/127.0.0.1/9021) && echo open` for the arena, or 9001 in place of 9021 for nginx in front of it.

## 6. Debugging

**A process from the release, under a debugger.** `DEBUG=1` makes each Java process listen for a debugger, on
127.0.0.1 only, without waiting for one.

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

With `DEBUG=1`, the backend's processes run on `vendor/jdk-21`, the same Java version, not on the release's
runtime (j-redis always runs on `vendor/jdk-21`). The release's runtime leaves out the debugger's agent,
`jdk.jdwp.agent` ([09 §4.1](../detailed-design/09-release-and-packaging.md#41-the-runtime)). Attach VS Code
([05 §4](05-vscode.md#4-debugging)), or the JDK's own debugger:

```bash
vendor/jdk-21/bin/jdb -attach 127.0.0.1:5005       # then: threads, stop in <class>.<method>, cont
```

**A test under a debugger**: from VS Code, or in Maven's own way, which waits on port 5005 until a debugger
attaches:

```bash
(cd backend && mvn -pl platform test -Dtest=AuthServiceTest -Dmaven.surefire.debug)
```

**What a running process is doing**, without a debugger: the JDK's tools on its pid (`work/run/<process>.pid`).

```bash
vendor/jdk-21/bin/jcmd $(cat work/run/platform.pid) Thread.print       # every thread's stack
vendor/jdk-21/bin/jcmd $(cat work/run/arena.pid) GC.heap_info
```

## 7. The drills

**The drills are the live tests**: each starts a release's processes on ports of its own, and drives them with the
headless client (the C# core without Unity). They need Unity 2019's editor in `work/unity` (§1): each drill builds
the headless client with its compiler, for .NET Framework 4.5, and runs it on its Mono (D-116).

- **The C# client is driven in clear alone.** Unity's Mono outside the editor speaks TLS 1.0 alone, and nginx and an
  arena's own TLS speak 1.2 and 1.3. So `TLS=1`, `FRONT=https` and the scenario `untrusted` are refused. The core's
  TLS is tested by its own tests (§9).
- **Their database** is `backend_dev` on the development MySQL (§3.1), which each drill starts when it is not
  running, and stops after. The machine's own MySQL on 3306 is never touched (O-40).
- **Do not run a drill and a run by hand at once.** A run by hand uses `backend_dev` too, and a drill's `season`
  scenario ends the current season for both.
- **Their ports.** They start their own servers on ports 3307 to 3309, 6390 to 6392, 8093 and 8094, 9011, and
  9193 to 9199; with `FRONT=`, nginx on 8095, 8096 and 9095 too.
- **`backup-drill.sh` seeds from `backend_dev`**, so it needs a drill or a run by hand first to have made the
  schema. It says so if not.

```bash
TMPDIR=<a scratch directory> RELEASE=$PWD/backend/target/release/backend-0.1.0-SNAPSHOT \
    client/headless-drill.sh play duel                    # any of the scenarios its header lists
TMPDIR=<a scratch directory> RELEASE=... backend/scripts/backup-drill.sh
```

The scenarios and options are listed in the header of `client/headless-drill.sh`, and every command is in
[`CLAUDE.md`](../../CLAUDE.md#commands). The options most used:

| Setting | What the drill does |
|---|---|
| `FRONT=http`, `FRONT=plain` | the release's nginx in front of the drill's processes, as `dev-nginx.sh` sets it up (D-107). `http`: nginx the TLS way, and the client through 80's place alone, the API, the lobby and matches, a match by `/match/` and the arena's name (D-115, D-116). `plain`: the plain way (D-101), 80's place and 9001's. `NEWMAN=1` runs the Postman collection through it first |
| `SIMPLE=1` | `client-simple`'s live check before the scenarios, straight or with `FRONT=plain` (behind `FRONT=http` it is left out: its grants ask for TLS); then `client-simple-no-tls`'s, in every way (D-111, D-114, D-115, D-116) |
| `TLS=1`, `FRONT=https`, the scenario `untrusted` | refused since D-116, as above. They were an arena serving TLS itself, and nginx's 443 and 9001 |
| `WATCH=1` | the platform's watch on, with a webhook receiver of the drill's: after the scenarios the worker is killed, and `TargetDown` must be listed and sent (D-86) |
| `IDLEGUEST=1` | a guest aged 181 days in the database after the scenarios, erased by a fresh worker's retention a minute later (D-87) |
| `FAILOVER=1`, `MYSQL_FAILOVER=1` | the store, or MySQL, with a replica: the primary killed, the replica promoted by the script, the scenarios again |
| `BOTS=<n>[:<s>]`, `SOAK=1` | bots in place of the scenarios: a load run, or the two-hour soak |

## 8. The checks on RHEL 9

These checks need Docker, and each runs on a release:

| Script (`backend/scripts/`) | Checks |
|---|---|
| `check-release-el9.sh` | the release in UBI 9 with nothing installed (`IMAGE=` for another: 9.0 to 9.9 checked, and UBI 8.10): every native file's libraries, every MySQL program and plugin (D-110), MySQL, j-redis, the four processes, nginx |
| `check-units-el9.sh` | the units under RHEL 9's systemd, installed as the deploy document says, with nginx as one machine's front: HTTPS, and a match connection through 9001, with the backend's root trusted alone; and 80's health and its `/match/` for the arena, in clear (D-115) |
| `check-install-guide-el9.sh` | the [install guide](../operations/03-install-guide.md), every step as written, with no network: the launch's one machine (D-93), each arena by its name through nginx's 9001 and 80; again after a restart; and the runbook's restore on one machine (about twenty-five minutes) |
| `check-two-machines-el9.sh` | as root: [two machines](../operations/10-two-machines.md) as written (D-96), on two UBI 9 machines joined by a private link of their own. A is installed by the install guide's check, and a probe of the site runs through its nginx throughout. B's own arenas (D-97) play matches by the C# client through A's nginx's 80, in clear, with B frozen. Then A's MySQL is lost and B's promoted; then A is lost, and the runbook's steps for it are run on B. It needs Unity's editor in `work/unity` (§1), which builds the C# client for .NET Framework 4.5 and runs it in the machines on its Mono (D-116). It takes about twenty minutes, twelve from an image. `SAVE_A=` keeps A installed and `A_IMAGE=` reuses it; `STANDBY_ONLY=1` leaves B's arenas out |
| `check-ports-el9.sh` | in Rocky Linux 9.5 with RHEL 9's firewalld: the [ports and the firewall](../operations/05-ports-and-firewall.md) as written; the front's nginx ending TLS on 443 and 9001 with a certificate from the backend's own CA, and serving 80 in clear (D-115); and the C# headless driver playing through its 80, in clear, on Unity's Mono, with 443's and 9001's TLS checked by `openssl s_client` (D-116). It needs Unity's editor in `work/unity` too, and takes about ten minutes |

**Checks without Docker:**
- `backend/scripts/check-certificates.sh` checks the certificate scripts, with `openssl` alone, in a few seconds
  ([operations/06 §9](../operations/06-certificates.md#9-testing-and-development)). With Docker, `IMAGE=` runs it
  on RHEL 8's and RHEL 9's OpenSSL (D-103).
- `backend/scripts/check-nginx-logs.sh` checks the rotation of nginx's logs, with the release's nginx on a
  loopback port (D-85).
- `backend/scripts/check-promote.sh` checks both promotions' refusals and the MySQL handover's order, with
  stand-in clients, in seconds (D-95).

**Checks in a Rocky 9 guest under QEMU, with SELinux enforcing** (RHEL 9's kernel, which a container lacks):
- `backend/scripts/check-selinux-el9.sh` makes the guest, and checks `check-bind.sh`'s SELinux branch and its three
  grants (D-90; forty minutes the first time without KVM, ten after).
- `backend/scripts/check-release-guest-el9.sh` runs the release in that guest: `backend-mysql.service` as the
  install guide sets it up, then `check-release-el9.sh`'s own checks (D-110; about half an hour without KVM).

And `vendor/build-nginx.sh` compiles nginx again ([vendor/README](../../vendor/README.md)).

## 9. The client

All of these need Unity 2019's editor in `work/unity` (§1), and no .NET ([client/README](../../client/README.md)).
Every project targets .NET Framework 4.5. Every C# build here is Unity 2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3)
against Mono's 4.5 reference assemblies, and every run is Unity's Mono 5.11 (`client/unity-env.sh`, D-116):

| What | Command | What it does |
|---|---|---|
| The engine-free core and its tests | `client/core-tests.sh` | compiles the core and its tests for .NET Framework 4.5, and runs them on Unity's Mono by NUnitLite: 196 tests. Read its `Test Count:` line; it ends `CORE TESTS PASSED` when none failed |
| The Unity package, against stubs | `client/unity-package.sh && client/unity-check.sh` | builds the core into the package, then compiles the package's scripts against stubs of the Unity API, each by Unity's compiler |
| The Unity package, against Unity 2019 | `client/unity2019-check.sh` | Unity 2019.4.28f1's own compiler: the core for .NET Framework 4.5, and the package's scripts against Unity's assemblies at its .NET 4.x API level, with the package's `csc.rsp` as Unity reads it; then the core on Unity's Mono, and `core-tests.sh` (D-108, D-113, D-116). Nothing here runs the editor |
| The solution, as Visual Studio 2019 builds it | `client/vs2019-check.sh` | VS 2019's MSBuild and Roslyn (16.11 and 16.7, from the .NET 5 and .NET Core 3.1 SDKs' images, as build tools alone) build the core and an app of 4.5, Unity 2019's project as VS 2019 compiles it, and `Backend.Client.sln`. It needs Docker too. Nothing runs on .NET Framework itself |
| The simple client | `client-simple/check.sh`; live, `SIMPLE=1 client/headless-drill.sh play` | its offline checks, then its live check against a drill, in clear: straight or with `FRONT=plain` ([client-simple/README](../../client-simple/README.md)) |
| The simple client without TLS | `client-simple-no-tls/check.sh` | its offline checks. The same `SIMPLE=1` drill runs its live check after `client-simple`'s, through nginx's 80 or straight (D-115) ([client-simple-no-tls/README](../../client-simple-no-tls/README.md)) |

## 10. When something fails

| What you see | What it means | What to do |
|---|---|---|
| `[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:…:test … on project persistence`, and above it `Communications link failure` or `Access denied for user 'backend'` | Not a plugin that could not be fetched: tests failed. The database tests reached no MySQL, or one that is not theirs. Before D-80 they went to 127.0.0.1:3306. | `./build-offline.sh` from this version starts the development MySQL itself. By hand: `./dev-mysql.sh start`, in a shell that sourced `dev-env.sh`. |
| Stack traces and `ERROR` lines while the tests run, such as `room-1 - tick 161 failed` or `has not ticked for 10451 ms; abandoning it`, and the build still ends `--- done:` | Not failures. Tests that break something on purpose (a tick that throws, a spool that cannot be written, a room that hangs) check what the backend does then, and the backend logs it as it would in production. | Nothing. A test that fails stops the build with `[ERROR] Tests run: …, Failures: …`, and its name under `[ERROR] Failures:` or `[ERROR] Errors:`. Its report is in `<module>/target/surefire-reports/`. |
| `dev-env.sh: vendor/jdk-21/bin/java is not executable` | the copy lost the files' modes | copy the repository again, with `git clone` or `tar` |
| `dev-env.sh: … holds a character the build cannot carry` | a space or another character in the path | move the checkout, or link to it from a path without one |
| `127.0.0.1:3314: in use (…)`, then `another port for it: MYSQL_PORT=…` | another server is on the development MySQL's port | `MYSQL_PORT=<a free port>` |
| `127.0.0.1:8080: in use (…)`, then `another port for it: PLATFORM_PORT, GATEWAY_PORT or ARENA_PORT, …` | another program is on that port | `PLATFORM_PORT=<a free port>`, and the same for every later command |
| `127.0.0.1:3314: refused (Permission denied)` (or 6380, 8080, 8081, 9021, a drill's port; nginx's 9001 shows as `0.0.0.0:9001`), then `A port above 1023 is any user's on Linux: a refusal is a policy of this machine. Found:` and the policy | Not the user's rank: a policy of this machine keeps the port from users. `check-bind.sh` names the policy it finds: the unprivileged-port threshold raised above the port (`net.ipv4.ip_unprivileged_port_start`), a login that SELinux confines (`user_u`, `staff_u`) with `selinuxuser_tcp_server` off, or WSL's reserved port ranges. Or it says it knows none ([D-88](../architecture/03-decision-log.md#d-88--a-port-the-machine-refuses-is-named-with-its-policy-before-anything-starts)). Reproduced here: the threshold, as a user who is not root; and SELinux, in a Rocky 9 guest with a confined login, where each of the three remedies was seen to work (`backend/scripts/check-selinux-el9.sh`, [D-90](../architecture/03-decision-log.md#d-90--the-port-checks-selinux-branch-is-proven-in-a-rocky-9-guest-and-names-the-exact-grant)). The WSL lines are from the documentation. | Run the command printed under the policy: `sudo sysctl -w net.ipv4.ip_unprivileged_port_start=1024`; or `sudo setsebool -P selinuxuser_tcp_server on`, or the module from the port's own denial, or the login mapped to `unconfined_u`; or ports outside Windows' ranges. Or use other ports: `MYSQL_PORT`, `STORE_PORT`, `PLATFORM_PORT`, `GATEWAY_PORT`, `ARENA_PORT`, `NGINX_MATCH_PORT`. To check by hand: `backend/scripts/check-bind.sh 3314 8080` |
| `j-redis is not running on 127.0.0.1:6380: ./dev-jredis.sh start first`, or `the development MySQL is not running: ./dev-mysql.sh start first` | `dev-backend.sh start` was run before what it needs | run the script it names, or `./run-local.sh start` for all four in order |
| `j-redis is not built: …/dev-jredis.sh build first`, or from the backend's build `j-redis is not built, and the backend needs its client, the release its server: ./dev-jredis.sh build first` | `dev-jredis.sh start`, or the backend's build, was run before j-redis's build | `./dev-jredis.sh build` (`SKIP_TESTS=1` for speed) |
| `FAILED  nginx is not running: ./dev-nginx.sh start` (from `test`), or `nginx is not running, and matches go through it: ./dev-nginx.sh start first` (from `bots`) | `dev-backend.sh test` or `bots` was run with no nginx: matches go through it | `./dev-nginx.sh start` |
| From `test`: `nginx runs TLS, and this command plain: give both the same PLAIN (…)`; from `start`: `nginx: already running TLS, pid …, and this command plain: give both the same PLAIN, or restart it this way (…)`; or the same the other way round | nginx was started the other way: by `sudo` without `PLAIN=1`, or left from another run | `sudo PLAIN=1 ./dev-nginx.sh restart` for plain, or `sudo ./dev-nginx.sh restart` for TLS |
| `0.0.0.0:80: refused (Permission denied)` (and 443), then `Ports below 1024 are root's on Linux: 80 443` and `another port for it: NGINX_HTTP_PORT, …` | nginx was started by a user who is not root: 80 and 443 are below 1024 (O-48) | run it as root; or `sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80`, as the check prints; or other ports, for `dev-nginx.sh` and `dev-backend.sh` alike: `export NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443` |
| `0.0.0.0:443: in use (…)`, then `another port for it: NGINX_HTTP_PORT, NGINX_HTTPS_PORT or NGINX_MATCH_PORT` | another program is on one of nginx's ports: another web server, or an nginx of the machine's own | stop it, or use other ports, as in the row above |
| `PUBLIC_ADDRESS=…: an IPv4 address or a host name, the one clients dial` | `PUBLIC_ADDRESS` was given something else, such as an IPv6 address | `export PUBLIC_ADDRESS=<the IPv4 address clients dial>`, then `./dev-backend.sh restart arena && ./dev-nginx.sh start` |
| `the release's nginx configuration has what this script cannot rewrite for this machine (D-92):` and the lines | the release's `nginx-conf/` gained a path, an address, a port or an upstream that `dev-nginx.sh` does not rewrite. Nothing was started | teach the rewrite the new line ([D-92](../architecture/03-decision-log.md#d-92--a-script-for-each-service-of-a-run-by-hand-and-nginx-on-the-sites-ports-in-front-of-it)); the generated files are left in `work/nginx/conf.new` to read |
| `FAILED  443: the certificate verifies, but the platform behind it does not answer (502)` | nginx is up, and the platform is not | `./dev-backend.sh start` |
| `FAILED  80: /match/a1.arena.internal, but the arena behind it does not answer: ./dev-backend.sh start` | nginx is up, and the arena is not | `./dev-backend.sh start` |
| On the client: `curl: (60) SSL certificate problem: unable to get local issuer certificate` | the client does not trust nginx's root | give it `work/nginx/ca/ca.pem`: `--cacert`, Postman's CA certificates, the headless client's `--trust`, the app's build |
| On the client: `no alternative certificate subject name matches` | the client dialled an address that the certificate does not name | `export PUBLIC_ADDRESS=<the address it dials>`, then `./dev-backend.sh restart arena && ./dev-nginx.sh restart`: the certificate is issued again, and the root is kept |
| On the client: the connection times out | the machine's firewall, or a VM on NAT networking that only its host can reach | run the `firewall-cmd` line that `dev-nginx.sh start` printed; give the VM a bridged network or a port forward |
| `Cannot access java21-offline in offline mode` | `-o` was given with the bundle's settings | drop `-o` (§2) |
| `class file has wrong version 65.0, should be 52.0`, or `release version 21 not supported` | Maven ran on Java 8: `dev-env.sh` was not sourced, or `/etc/mavenrc` won | `. ./dev-env.sh` first; it sets `MAVEN_SKIP_RC` |
| `Could not resolve dependencies … com.jredis:j-redis-client` | the backend was built by hand before the store | `./dev-jredis.sh build` first, or `./build-offline.sh` |
| `no release in backend/target/release: …/dev-backend.sh build first`, or from `dev-nginx.sh` `no release with nginx in backend/target/release: ./dev-backend.sh build first` | `dev-backend.sh` or `dev-nginx.sh` was run before a build | `./dev-backend.sh build`, or `./build-offline.sh` for both (`SKIP_TESTS=1` for speed) |
