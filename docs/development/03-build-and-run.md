# Build, test and run: a guide

From a clone of the repository to the backend built, tested and running on your
machine, with what the repository carries: the JDK, Maven and MySQL in
[`vendor/`](../../vendor/README.md), every library in
[`java21-offline/`](../../java21-offline/README.md). No network and nothing
installed is needed for the backend. To install it on a server instead: the
[install guide](../operations/03-install-guide.md).

Every command here was run as written on 2026-10-05 (Ubuntu 24.04; the build
also in Rocky Linux 9 and in UBI 9.5, with no network), but one:
`./build-offline.sh` with every test, an hour, was not run in one go. The
backend's tests last ran whole on 2026-10-04, and its database tests again on
MySQL 8.4 on 2026-10-05. Commands run from the repository's root.

## 1. What you need

| For | Needs |
|---|---|
| Building, testing and running the backend | Linux on x86-64 with bash, coreutils, findutils, `sed`, `tar`, `gzip`, `curl` |
| The checks on RHEL 9, and compiling nginx again | Docker |
| The client, the Unity package and the drills (which drive the backend with the headless client) | the .NET 8 SDK, which the repository does not carry |

## 2. Build

Everything, with the committed JDK and Maven and the bundle as the only source of
libraries; `j-redis-service`, the backend, then a release:

```bash
SKIP_TESTS=1 ./build-offline.sh          # about fifteen minutes; without SKIP_TESTS, the tests too (§3)
```

Its local Maven repository is its own (`WORK`, by default under `TMPDIR`), so it
never mixes with `~/.m2`. To use the committed JDK and Maven by hand instead:

```bash
export JAVA_HOME=$PWD/vendor/jdk-21 PATH=$PWD/vendor/jdk-21/bin:$PWD/vendor/maven-3.9/bin:$PATH
(cd j-redis-service && mvn -q install -DskipTests)    # the store first: the backend builds against its client
(cd backend && mvn -q install -DskipTests)
```

That `mvn` reads Maven Central unless told otherwise; to read the bundle instead,
pass its settings as `build-offline.sh` does (`java21-offline/README.md`, method 1).

## 3. The tests

Most of the backend's tests need nothing; `persistence`, `worker` and `platform`
need a MySQL. Start one of your own from `vendor/`, on a private port:

```bash
M=$PWD/vendor/mysql-8.4/bin
D=${TMPDIR:-/tmp}/backend-test-mysql
"$M/mysqld" --no-defaults --initialize-insecure --datadir="$D" --user="$(id -un)"
"$M/mysqld" --no-defaults --datadir="$D" --user="$(id -un)" --port=3314 --bind-address=127.0.0.1 \
    --mysqlx=OFF --socket=/tmp/backend-test-mysql.sock --log-error="$D.err" --default-time-zone=+00:00 &
until "$M/mysqladmin" -S /tmp/backend-test-mysql.sock -u root ping > /dev/null 2>&1; do sleep 1; done
"$M/mysql" -S /tmp/backend-test-mysql.sock -u root -e "
    CREATE DATABASE backend_test; CREATE DATABASE backend_dev;
    CREATE USER backend@'127.0.0.1' IDENTIFIED BY 'backend-dev-password';
    GRANT ALL ON backend_test.* TO backend@'127.0.0.1'; GRANT ALL ON backend_dev.* TO backend@'127.0.0.1'"
export JDBC_URL="jdbc:mysql://127.0.0.1:3314/backend_test?useSSL=false&allowPublicKeyRetrieval=true"
```

The socket's path is short on purpose: a Unix socket's may not pass 108
characters. The tests read `JDBC_URL` (`DB_USER` and `DB_PASSWORD` default to
`backend` and `backend-dev-password`), and wipe the database they are given, which
must end in `_test`. Then:

```bash
./build-offline.sh                        # everything, with every test: about an hour
(cd backend && mvn -q -pl persistence test -Dtest=AccountRepositoryTest)   # or one class, with the JDK and Maven of §2
```

A whole backend build ran 1 020 tests on 2026-10-04; the counts per module are in
each module's README. Stop the server with
`"$M/mysqladmin" -S /tmp/backend-test-mysql.sock -u root shutdown`.

## 4. A release

`build-offline.sh` leaves it in `backend/target/release/`: a directory and its
`.tar.gz`, with its own Java runtime, j-redis, MySQL and nginx
([09 §4](../detailed-design/09-release-and-packaging.md#4-the-release)); its
`VERSIONS.md` says what is inside. With the JDK and Maven of §2 on the `PATH`,
`backend/scripts/make-release.sh` makes it alone (`MVN=` for the bundle's
settings, as its header says).

## 5. Running it by hand

The whole chain on one machine from a release: the store, the platform, a worker,
the gateway and an arena, on the release's own runtime, against the MySQL of §3
(its `backend_dev`). Then twenty bots play through it as a client does: register,
log in, open the lobby, ask for a match, join the arena they are given.

```bash
R=$(ls -d backend/target/release/backend-*/ | head -1); R=${R%/}
J="$R/runtime/bin/java --add-opens java.base/java.nio=ALL-UNNAMED"
L=${TMPDIR:-/tmp}/backend-run; mkdir -p "$L/spool"
export BACKEND_DB_URL="jdbc:mysql://127.0.0.1:3314/backend_dev?useSSL=false&allowPublicKeyRetrieval=true"
export BACKEND_DB_USER=backend BACKEND_DB_PASSWORD=backend-dev-password
$J -jar "$R/jredis/lib/j-redis-server.jar" --port 6380 --dir "$L" > "$L/store.log" 2>&1 &
sleep 3
$J -cp "$R/lib/platform/*" com.backend.platform.PlatformMain 127.0.0.1 8080 127.0.0.1 6380 > "$L/platform.log" 2>&1 &
$J -cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 6380 worker-1 > "$L/worker.log" 2>&1 &
$J -cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain 127.0.0.1 8081 http://127.0.0.1:8080 127.0.0.1 6380 gateway-1 > "$L/gateway.log" 2>&1 &
$J -cp "$R/lib/arena/*" com.backend.arena.ArenaMain 9001 5700 150 1500 2 127.0.0.1 6380 arena-1 0 "$L/spool" 127.0.0.1 127.0.0.1 > "$L/arena.log" 2>&1 &
until curl -s -o /dev/null http://127.0.0.1:8080/health; do sleep 1; done
# twenty bots for fifteen seconds
$J -cp backend/tools/target/tools-*-all.jar com.backend.tools.BotClient 20 15 http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby
```

It ends with `welcomes 20   disconnects 0` and each bot's stay paid by the worker.
What each process's arguments mean: [backend/README](../../backend/README.md#running-it).
Stop them with `kill $(jobs -p)` in the same shell. Ports 6380, 8080, 8081 and
9001 must be free; any others do, given to each process alike.

## 6. The drills

They start the whole stack from a release and drive it with the headless client,
so they need the .NET 8 SDK and the client built (`cd client && dotnet build Headless`):

```bash
TMPDIR=<a scratch directory> RELEASE=$PWD/backend/target/release/backend-0.1.0-SNAPSHOT \
    client/headless-drill.sh play duel                    # any of the scenarios its header lists
TMPDIR=<a scratch directory> RELEASE=... MYSQL_FAILOVER=1 client/headless-drill.sh play duel
TMPDIR=<a scratch directory> RELEASE=... backend/scripts/backup-drill.sh
```

They take every process, the store and their MySQL servers from the release, and
the MySQL 3306 the tests use for `backend_dev` (`client/headless-drill.sh`'s
header has the rest: ports, scenarios, TLS, soak).

## 7. The checks on RHEL 9

With Docker, each on a release:

| Script (`backend/scripts/`) | Checks |
|---|---|
| `check-release-el9.sh` | the release in UBI 9 with nothing installed: every binary's libraries, MySQL, j-redis, the four processes, nginx |
| `check-units-el9.sh` | the units under RHEL 9's systemd, installed as the deploy document says |
| `check-install-guide-el9.sh` | the [install guide](../operations/03-install-guide.md), every step as written, with no network |

And `vendor/build-nginx.sh` compiles nginx again ([vendor/README](../../vendor/README.md)).

## 8. The client

The engine-free core, its tests and the headless driver, with the .NET 8 SDK:
`cd client && dotnet test` (read `Total tests:`); the Unity package:
`client/unity-package.sh && client/unity-check.sh` (compiled against stubs; nothing
here runs Unity). [client/README](../../client/README.md).
