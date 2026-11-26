# Questions and answers: building and running on an offline machine

This page is for anyone who sets the repository up on a machine with no network. It holds the owner's questions
from 2026-10-17 to 2026-10-19, asked while setting the repository up on a RHEL server that had no network and
already had Java 8 and MySQL 5 installed, which were to be left alone. Each answer is complete on its own, so you
can read only the question you have.

The step-by-step guides behind the answers are [03 — build, test, run and debug](03-build-and-run.md) and
[05 — VS Code](05-vscode.md). Every command here was run as written, and every output shown is what it printed.
Where something was not checked, the answer says so. Some answers have been brought up to date since (the run by
hand's scripts, D-92, 2026-11-01; port 80 serving matches, D-115, 2026-11-25), and say so where it matters.

1. [Why did `./build-offline.sh` fail with Maven plugin errors?](#1-why-did-build-offlinesh-fail-with-maven-plugin-errors)
2. [What are the `/opt` and `/tmp` paths in the documents? Do I copy anything there?](#2-what-are-the-opt-and-tmp-paths-in-the-documents-do-i-copy-anything-there)
3. [What does `dev-env.sh` do, line by line?](#3-what-does-dev-envsh-do-line-by-line)
4. [Why does building j-redis say a Maven plugin cannot be resolved?](#4-why-does-building-j-redis-say-a-maven-plugin-cannot-be-resolved)
5. [Was that proved from an empty cache, as on a new machine?](#5-was-that-proved-from-an-empty-cache-as-on-a-new-machine)
6. [How do I run, stop and restart the MySQL on its own?](#6-how-do-i-run-stop-and-restart-the-mysql-on-its-own)
7. [The tests print exceptions. Is something wrong?](#7-the-tests-print-exceptions-is-something-wrong)
8. [How do I test the HTTP API, the WebSocket and the arena's socket?](#8-how-do-i-test-the-http-api-the-websocket-and-the-arenas-socket)
9. [How are VS Code's Java extensions set?](#9-how-are-vs-codes-java-extensions-set)

---

## 1. Why did `./build-offline.sh` fail with Maven plugin errors?

**No download failed. Tests did.** Maven's last line names a plugin, which reads like a download error, but the
plugin it names is the one that runs the tests.

The failure was reproduced on 2026-10-17, on a fresh copy, with no network, an empty home directory, and Java 8 as
`JAVA_HOME`:
- `j-redis-service` and the backend's first five modules took every plugin and library from the repository's
  bundle, `java21-offline/`, and passed their tests.
- The build stopped at the `persistence` module. Its database tests went to a MySQL on `127.0.0.1:3306`, and 24 of
  them failed with `Communications link failure`.
- Maven ended with this line:

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.6.0:test (default-test) on project persistence
```

`maven-surefire-plugin` is the plugin that runs the tests. The line means "the tests failed", not "the plugin could
not be fetched".

On your machine, port 3306 is your MySQL 5. The tests would reach it and be refused
(`Access denied for user 'backend'`), and fail the same way. That part is inferred: no MySQL 5 was tried.

**Fixed** (defect O-38, decision D-80). The tests now use the repository's own MySQL 8.4, the *development MySQL*,
on port 3314, with its files in `work/mysql`. `./build-offline.sh` starts it for the tests and stops it at the end.
Your MySQL 5 is never touched. See §6.

## 2. What are the `/opt` and `/tmp` paths in the documents? Do I copy anything there?

**No. Nothing is copied to `/opt` or `/tmp`.** Everything stays inside the repository's folder.

| Path you may see | What it is | On your offline machine |
|---|---|---|
| `/opt/jdk21`, `/opt/maven`, `/opt/dotnet` | the tools installed on the development VM, where the backend was written | not needed: the repository carries its own JDK and Maven in `vendor/`. No .NET is used since D-116: the client is built and run with Unity's editor in `work/unity` |
| `/opt/java21-offline` | a path inside a template file: where the library bundle would go if it were carried alone to another project | not used: `dev-env.sh` replaces it with your folder's path (§3) |
| `/opt/backend` | where the install guide puts the backend on a production server, as a service | only when you install on a server, by the [install guide](../operations/03-install-guide.md), which copies it there itself |
| `/tmp` | where the old build kept Maven's files, and Java's default folder for temporary files | not used: everything goes in `work/tmp` |

What to do on the offline machine:

1. Copy the whole repository, with `git clone` or as one `tar` archive, into a folder whose path has no spaces, for
   example `/home/you/depio-simulation-platform`. A copy made file by file can lose the files' execute permission.
2. Build:

```bash
cd /home/you/depio-simulation-platform
./build-offline.sh
```

The scripts make `work/` inside that folder and put everything there: Maven's files, the temporary files, the
development MySQL, and the logs of a run by hand. Git ignores `work/`. Deleting it costs little: the next build
copies the libraries from the bundle again. (It also holds the development MySQL's data and nginx's root
certificate, which are lost with it: [03 §1](03-build-and-run.md#1-what-you-need-and-what-the-repository-touches).)

If an earlier attempt left `/tmp/backend-build-offline` or `~/.m2`, delete them: nothing uses them now.

One thing does appear in `/tmp` by itself. While any Java program runs, Java keeps a small folder
`/tmp/hsperfdata_<your user>` for its monitoring tools, and empties it when the program ends. That folder is
Java's own, wherever `java.io.tmpdir` points, and needs no setting up. Java runs no code from `/tmp`, so a `/tmp`
mounted `noexec` is no obstacle (checked).

## 3. What does `dev-env.sh` do, line by line?

[`dev-env.sh`](../../dev-env.sh) sets up the environment that every script uses, and that you use by hand after
`. ./dev-env.sh` (a dot, a space, then the script: it is *sourced*, so its settings stay in your shell). After it,
Maven works offline, takes everything from the repository, and writes only into `work/`. Two names first:

- **`B`** is the repository's folder, wherever you put it. Example: `/home/you/depio-simulation-platform`.
- **`WORK`** is `$B/work`, the folder for everything the build writes. `WORK=<another folder>` moves it; a relative
  one is made absolute.

**It finds the repository's own path first.** When you reach the folder through a symbolic link, `B` is the real
folder the link points to, so that every script finds the others' processes by one path (O-85). If the real path
holds a character the build cannot carry, `B` is the link's path instead.

**It checks the path and the files.** The paths `B` and `WORK` may hold letters, digits and `. _ - + @ /` only: no
spaces. The JDK, Maven and MySQL in `vendor/` must be executable. If either check fails, it says so and stops.

**It puts the repository's JDK and Maven first on the `PATH`:**

```bash
export JAVA_HOME=$B/vendor/jdk-21
export PATH=$JAVA_HOME/bin:$B/vendor/maven-3.9/bin:$PATH
export MAVEN_SKIP_RC=1
```

`java` and `mvn` are then the repository's: Java 21.0.12.1 and Maven 3.9.16, whatever your machine's `JAVA_HOME`
says. `MAVEN_SKIP_RC=1` stops Maven from reading `/etc/mavenrc` and `~/.mavenrc`, files that can point Maven at
another Java, such as your Java 8.

**It makes the two working folders:**

```bash
mkdir -p "$WORK/m2" "$WORK/tmp"
```

- `work/m2` is Maven's *local repository*: the copies of the jars it is using.
- `work/tmp` is the folder for temporary files.
- `-p` means: no error if they already exist, and make `work/` too if needed.

**It writes Maven's settings file:**

```bash
sed -e "s|file:///opt/java21-offline/repository|file://$B/java21-offline/repository|g" \
    -e "s|<mirrors>|<localRepository>$WORK/m2</localRepository>\n  <mirrors>|" \
    "$B/java21-offline/settings-offline.xml" > "$WORK/settings.xml"
```

`sed` reads the committed template [`java21-offline/settings-offline.xml`](../../java21-offline/settings-offline.xml)
and makes two changes. The result goes to `work/settings.xml`; the template itself is never changed.

1. **It points Maven at the bundle where your copy is.** The template says the bundle is at
   `/opt/java21-offline/repository`. Every such URL (the `g` means all three) becomes the bundle inside your copy:

   ```
   file:///opt/java21-offline/repository
      →  file:///home/you/depio-simulation-platform/java21-offline/repository
   ```

2. **It tells Maven where to keep its copies.** One line is put before `<mirrors>` (`\n` starts a new line).
   Without it, Maven would use `~/.m2/repository`, in your home folder.

   ```xml
   <localRepository>/home/you/depio-simulation-platform/work/m2</localRepository>
   ```

The file it writes, shortened:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <localRepository>/home/you/depio-simulation-platform/work/m2</localRepository>
  <mirrors>
    <mirror>
      <id>java21-offline</id>
      <url>file:///home/you/depio-simulation-platform/java21-offline/repository</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
  ... the same URL as the repository and the plugin repository ...
</settings>
```

`<mirrorOf>*</mirrorOf>` is what keeps Maven offline. Whenever Maven wants anything from the internet (Maven
Central, for example), it asks the bundle folder instead, and never tries the network. The file is written again
every time `dev-env.sh` runs, so if you move or rename the folder, its paths are right again at the next run.

**It gives every `mvn` command the settings file:**

```bash
export MAVEN_ARGS="-s $WORK/settings.xml -DargLine=-Djava.io.tmpdir=$WORK/tmp"
```

Maven 3.9 adds `MAVEN_ARGS` to every `mvn` command. So `mvn install` really runs as
`mvn -s …/work/settings.xml -DargLine=-Djava.io.tmpdir=…/work/tmp install`:
- `-s …/work/settings.xml` makes Maven use the file just written, not `~/.m2/settings.xml`.
- `-DargLine=-Djava.io.tmpdir=…/work/tmp` is for the tests. Maven runs the tests in a separate Java process,
  started by its test plugin, Surefire. `argLine` is the option Surefire gives that process, and
  `-Djava.io.tmpdir=…` moves Java's folder for temporary files from `/tmp` to `work/tmp`. The tests make many
  temporary files.

**It moves Maven's own temporary files:**

```bash
export MAVEN_OPTS="-Djava.io.tmpdir=$WORK/tmp"
```

This is the same `/tmp` change, for the Java process that runs Maven itself: the `mvn` script passes `MAVEN_OPTS`
to that process. Two settings are needed because there are two separate Java processes:

| Java process | What it does | Gets `work/tmp` from |
|---|---|---|
| Maven's own | reads the POMs, copies jars from the bundle, compiles | `MAVEN_OPTS` |
| the one Surefire starts | runs the tests | `-DargLine=…` in `MAVEN_ARGS` |

**The rest:**

| Variable | Set to | Unless |
|---|---|---|
| `TMPDIR` | `work/tmp`, for MySQL and any other program that reads it | — |
| `MYSQL_PORT` | 3314, the development MySQL's port | you set another first |
| `JDBC_URL` | the development MySQL's `backend_test` on `MYSQL_PORT`, for the tests | you set another first. A `JDBC_URL` that an earlier sourcing set, for another port, is replaced, not kept (O-85) |
| `STORE_PORT`, `PLATFORM_PORT`, `GATEWAY_PORT`, `ARENA_PORT` | 6380, 8080, 8081, 9021: a run by hand's j-redis, platform, gateway and arena, on 127.0.0.1 (D-92) | you set others first |
| `ARENA_TLS_NAME` | `a1.arena.internal`, the arena's name behind nginx | you set another first |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `NGINX_MATCH_PORT` | 80, 443, 9001: nginx's ports in front of a run by hand | you set others first |
| `PLAIN` | 0: nginx and the arena with TLS. 1 runs them without TLS, for development alone (D-101) | you set 1 first |
| `PUBLIC_ADDRESS` | the address clients dial: the machine's first IPv4 address from `hostname -I`, or 127.0.0.1 if it has none | you set another first |

**Why no spaces in the path.** Maven splits `MAVEN_ARGS` at spaces, so `/home/you/my project` would become two
arguments. The paths also go into XML and into the `sed` command as they are. That is why `dev-env.sh` checks the
path first.

## 4. Why does building j-redis say a Maven plugin cannot be resolved?

**Because Maven was not told about the bundle.** Maven does not come with its plugins: clean, compiler, the test
runner, jar, install. The first time a `pom.xml` names one, Maven downloads it from Maven Central, on the internet.
With no network, that fails.

Without `dev-env.sh`, on a new offline machine (output of 2026-10-19, shortened where `…` stands):

```console
$ cd j-redis-service && mvn install
[ERROR] Plugin org.apache.maven.plugins:maven-install-plugin:3.1.2 or one of its dependencies could not be resolved:
[ERROR]   The following artifacts could not be resolved: … Could not transfer artifact … from/to central (https://repo.maven.apache.org/maven2): repo.maven.apache.org: …
```

The end of the message says the name could not be looked up: `Name or service not known`, or `Temporary failure in
name resolution`, depending on the machine.

The plugins are already in the repository, for example
`java21-offline/repository/org/apache/maven/plugins/maven-clean-plugin/3.2.0/`. Maven looks there only when it is
given `work/settings.xml`, which `dev-env.sh` writes and passes to every `mvn` command (§3). So in every new
terminal, from the repository's root folder:

```bash
. ./dev-env.sh                        # once per terminal: a dot, a space, then the script
cd j-redis-service && mvn install     # BUILD SUCCESS, offline
```

Or run `./build-offline.sh`, which reads `dev-env.sh` itself and builds j-redis first.

The same error comes from two more places:
- **j-redis's own guides** (`j-redis-service/docs/guide/01` and `02`) set j-redis up *alone*, with things under
  `/opt`. Inside this repository, skip their setup steps; a note at the top of each says so.
- **VS Code opened on `j-redis-service/` alone.** Open the repository's root folder instead, the one with
  `build-offline.sh`: the settings in `.vscode/` apply only there. Opened alone, VS Code's Maven import also goes to
  the internet, and shows these errors in `pom.xml`.

## 5. Was that proved from an empty cache, as on a new machine?

**Yes, on 2026-10-19.** An earlier check had been run in a folder where `work/m2` could already hold files, so it
was run again from nothing:
- **the copy:** a fresh `git clone` from GitHub of the pushed commit;
- **the machine:** a new RHEL 9.5 container (Red Hat's UBI image) with no network, only loopback, and an ordinary
  user with an empty home directory;
- **its setup:** Java 8 as the machine's default, and `/tmp` mounted `noexec`;
- **the caches:** before each step, the test printed that `~/.m2` and `work/` were both absent, and it deleted them
  between steps.

| Step, each from an empty cache | Result |
|---|---|
| `cd j-redis-service && mvn install`, **without** `. ./dev-env.sh` | failed: `maven-install-plugin … could not be resolved … from/to central` (§4) |
| `. ./dev-env.sh`, then `cd j-redis-service && mvn install`, with its tests | `BUILD SUCCESS`, no test failing |
| `./build-offline.sh`, with every test, after deleting `work/` | exit 0 in 35 minutes, 0 `[ERROR]` lines, the release built |

Maven records where it got each file (`_remote.repositories`). After the full build, `work/m2` held 698 entries
from `java21-offline`, the bundle in the repository. Another 43 had no source: those are what the build installed
itself, j-redis's and the backend's own modules. Nothing came from anywhere else, and `~/.m2` was never made.

## 6. How do I run, stop and restart the MySQL on its own?

This is the **development MySQL**: the repository's own MySQL 8.4, with its files in `work/mysql`, listening on
`127.0.0.1:3314` only. It is not your MySQL 5. Run every command from the repository's root; nothing needs to be
sourced first.

| Command | What it does |
|---|---|
| `./dev-mysql.sh start` | Starts it, and returns once it answers. The first time, it makes its data first (about 5 s). Every start makes sure of the databases `backend_test` (the tests') and `backend_dev` (a run by hand's) and of the user `backend` / `backend-dev-password`. If it is already running, it prints where. |
| `./dev-mysql.sh status` | Says whether it runs, and on which port. Exit status 0 if it runs, 1 if not. |
| `./dev-mysql.sh stop` | Shuts it down cleanly, and returns once it has ended. |
| `./dev-mysql.sh restart` | `stop`, then `start`. The data is kept. |
| `./dev-mysql.sh test` | Checks it answers as the backend logs in: the user `backend` over TCP to both databases, and `backend_dev`'s schema version. |

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

**Other scripts that start and stop it:**
- `./dev-backend.sh build`, and so `./build-offline.sh`, starts it for the tests and stops it at the end. It does
  not when the MySQL was already running, when `SKIP_TESTS=1` is given, or when `JDBC_URL` names another server.
- `./run-local.sh start` starts it first, then j-redis, the backend and nginx. `./run-local.sh stop` leaves it
  running: stop it with `./dev-mysql.sh stop`.
- It does not start with the machine. After a reboot, run `./dev-mysql.sh start`.

**Connecting to it**, with the repository's own client:

```bash
# as the backend's user, over TCP, as the tests and the backend connect
vendor/mysql-8.4/bin/mysql --no-defaults -h 127.0.0.1 -P 3314 --protocol=TCP \
    -u backend -pbackend-dev-password backend_dev

# as root: no password, reached only through the socket, from the data folder
(cd work/mysql/data && ../../../vendor/mysql-8.4/bin/mysql --no-defaults -S mysqld.sock -u root)
```

`--no-defaults` keeps the client from reading `/etc/my.cnf` or `~/.my.cnf`, which may name your MySQL 5. Use the
repository's client. The `mysql` command on your machine belongs to MySQL 5, and was not tried against this 8.4
server.

**Its files**, all in `work/mysql`:

| File | What it is |
|---|---|
| `data/` | the databases; `data/mysqld.sock` is its socket while it runs |
| `mysqld.err` | its log: starts, stops, crash recovery, errors |
| `init.err` | the log of the very first start |
| `mysqld.pid` | its process id, while it runs |

**When something goes wrong:**

| What you see | What to do |
|---|---|
| `127.0.0.1:3314: in use (…)`, then `another port for it: MYSQL_PORT=…` | Another program has the port. Choose another: `MYSQL_PORT=3324 ./dev-mysql.sh start`. Then give every command the same `MYSQL_PORT`, or `export MYSQL_PORT=3324` once in the terminal, and change 3314 in `.vscode/settings.json` and `.vscode/launch.json`. `stop` and `status` find it on any port. |
| `127.0.0.1:3314: refused (Permission denied)`, and under it the policy found | It is not your rank. On Linux a port above 1023 is any user's, so a policy of this machine keeps the port from users. The lines under it name the policy (the unprivileged-port threshold raised, a login that SELinux confines, WSL's reserved ranges) with the command that lifts it. Or use another port, as above ([03 §10](03-build-and-run.md#10-when-something-fails)). |
| `already runs, on 3314, not 3324` | It runs on another port than the one you asked for. Run `./dev-mysql.sh stop` first. |
| `MySQL could not be initialised` | The first start failed: read `work/mysql/init.err`. The half-made data is removed, so the next `start` tries again from nothing. |
| `start` fails in another way | It prints the end of `work/mysql/mysqld.err`. The whole log is in that file. |

**After a crash** (the power went, or the server was killed): run `./dev-mysql.sh start` as usual. This was tried
with `kill -9`. The next start recovered by itself (`Starting XA crash recovery...`, then `ready for
connections`), and a row written before the crash was still there.

**To start again with empty databases:**

```bash
./dev-mysql.sh stop
rm -rf work/mysql           # only this server's files
./dev-mysql.sh start        # makes them again, about 5 s
```

**Your MySQL 5** is a different server, with its own systemd service
(`systemctl list-units | grep -i -E 'mysql|maria'` names it), its own files and its own port, 3306. None of these
commands reads, starts, stops or changes it. Do not stop it to free a port: give the development MySQL another
port instead.

## 7. The tests print exceptions. Is something wrong?

**Look at how `./build-offline.sh` ends.**

**It ends with `--- done: …`.** Every test passed, and the exceptions are on purpose. Several tests break
something deliberately, to check what the backend does then: a tick that throws, a spool that cannot be written, a
room that hangs. The backend logs those as it would in production. You see `ERROR` lines and stack traces such as:

```
ERROR [room-1] room-1 - tick 161 failed
ERROR [match-result-publisher] c.backend.arena.MatchResultPublisher - could not spool match …: FileSystemException …
ERROR [room-watchdog] com.backend.arena.RoomRegistry - room-1 has not ticked for 10451 ms; abandoning it
```

Nothing is wrong.

**It stops with `[ERROR] Tests run: …, Failures: …`.** A test really failed, and the build stops there. The
failing test is named under `[ERROR] Failures:` or `[ERROR] Errors:`, and its report is in
`<module>/target/surefire-reports/<TestClass>.txt`. Tests that failed only sometimes have been found before,
usually tests sensitive to timing on a busy machine, and each was fixed. If one fails, send:

1. the `[ERROR]` lines that name it;
2. its report file;
3. whether it fails every time or only sometimes.

Each such failure gets a defect number and a fix.

## 8. How do I test the HTTP API, the WebSocket and the arena's socket?

Start the backend with `./run-local.sh start` (§6 and [03 §5](03-build-and-run.md#5-running-it-by-hand)). Then:

| Interface | Address, on a run by hand | Test it with |
|---|---|---|
| the player API, HTTP | `http://127.0.0.1:8080/v1/...` on this machine; from another machine, through nginx, `https://<address>/v1/...` (or `http://<address>/v1/...` in clear) | `curl`, Postman |
| the admin API, HTTP | `http://127.0.0.1:9120/admin/...`, with `Authorization: Bearer <secret>`; on this machine alone | `curl`, Postman |
| the lobby, WebSocket | `ws://127.0.0.1:8081/lobby`; through nginx, `wss://<address>/lobby` (or `ws://<address>/lobby`) | Postman's WebSocket request; `docs/api/lobby-example.mjs` (Node 22 or later) |
| a match, binary over TCP | `<address>:9001`, TLS, through nginx; or in clear through nginx's 80, `/match/<the arena's name>` (D-115) | `./dev-backend.sh bots`. `curl` and Postman cannot speak it |

From another machine, Postman trusts `work/nginx/ca/ca.pem` (Settings, Certificates, CA certificates), and the
firewall must open 80, 443 and 9001 ([03 §5.0](03-build-and-run.md#50-clients-on-other-machines-the-front)).

**Start it with payments simulated and the admin API on.** The Postman collection needs both. `run-local.sh`
(through `dev-backend.sh`) passes these variables to the backend:

```bash
(umask 077; head -c 24 /dev/urandom | base64 > work/admin-token)    # the admin API's secret
export BACKEND_PAYMENT_PROVIDER=simulated BACKEND_ADMIN_ADDR=127.0.0.1:9120 \
       BACKEND_ADMIN_TOKEN_FILE=$PWD/work/admin-token
./run-local.sh start               # the account service among them, on 8083 (D-117)
```

**HTTP, with `curl`.** This is the output on fresh data. Your token will differ. The arena's line, from 2026-11-01
behind nginx (D-92), names this machine's address as `<address>`:

```console
$ curl -s http://127.0.0.1:8080/health
{"status":"ok"}
$ curl -s -X POST http://127.0.0.1:8083/v1/accounts -H 'Content-Type: application/json' \
       -d '{"username":"try_1","password":"example-password-1"}'          # the account service (D-117)
{"access_token":"eyJraWQiOiJwS29UcGxVR1pHb2VDV1hU...","token_type":"Bearer","expires_in":900,"refresh_token":"INh7kmxA9JugZ-m_OIcfndQfV0ChDL7w2C62o-KpSPk","account_id":"GZaZ3-aiO6cQ1xvmiqd8EA"}
$ curl -s -X POST http://127.0.0.1:8080/v1/sessions -H 'Content-Type: application/json' \
       -d '{"accessToken":"<its access_token>"}'
{"token":"GVgfvvt5RrKB72HV7BpyELyV8Zu-HctuEzlnGzpFssU","playerId":1,"expiresInSeconds":79767,"newPlayer":true}
$ curl -s http://127.0.0.1:8080/v1/inventory -H "Authorization: Bearer <the token>"
{"coins":0,"gems":0,"items":[]}
$ curl -s http://127.0.0.1:9120/admin/arenas -H "Authorization: Bearer $(cat work/admin-token)"
[{"name":"arena-1","host":"<address>","port":9001,"players":0,"maxPlayers":600,"tls":true,"rooms":1,"maxRooms":4}]
```

`/health` is the platform's own, on 127.0.0.1:8080. Through nginx, ask `/v1/health` instead: nginx passes `/v1/`,
`/lobby` and `/match/<name>` alone, and answers any other path with a 404 (D-107, D-115). Every route, with its
body and errors, is in [docs/api/01](../api/01-http-api.md) and [docs/api/03](../api/03-admin-api.md).

**HTTP, with Postman.** Import `docs/api/backend.postman_collection.json` and set four of its variables:
- `baseUrl` = `http://127.0.0.1:8080`
- `accountUrl` = `http://127.0.0.1:8083`
- `adminUrl` = `http://127.0.0.1:9120`
- `adminToken` = the contents of `work/admin-token`

Then run the whole collection, in order. It passed 131 of 131 checks on fresh data; with the account service
(D-117), 159 of 159 on 2026-11-26. **It is a scenario for one run
on fresh data**: it ends the season, closes a room and bans a test player. A second run on the same data fails a
few checks on what the first run left. To start fresh:

```bash
./run-local.sh stop && ./dev-mysql.sh stop && rm -rf work/mysql
./run-local.sh start               # both databases made again, the account service's too
```

**The lobby WebSocket, in Postman:**
1. New → WebSocket. URL `ws://127.0.0.1:8081/lobby`, then Connect.
2. Send first `{"t":"auth","id":1,"d":{"token":"<a token from a login>"}}`. It answers `auth.ok`.
3. Then send any lobby message, for example `{"t":"ping","id":2}` or
   `{"t":"queue.join","id":3,"d":{"mode":"duel"}}`. Every message is in
   [docs/api/02 §4](../api/02-lobby-websocket.md#4-what-a-client-sends).

A party or a duel needs a second connection, logged in as another player.

**The lobby WebSocket, scripted**, with two players: every frame printed, and every answer checked. It needs Node
22 or later, which the repository does not carry:

```console
$ node docs/api/lobby-example.mjs http://127.0.0.1:8080 ws://127.0.0.1:8081/lobby http://127.0.0.1:9120 "$(cat work/admin-token)"
--- two players, over http://127.0.0.1:8080
...
--- every answer as expected
```

**The arena's socket.** It is binary over TCP ([02-networking](../detailed-design/02-networking.md)). The bots
speak it as a client does: they sign in at the account service (D-117), get a seat from the lobby, connect to the
arena, play, and leave.

```console
$ ./dev-backend.sh bots 20 15
lobby: 20 of 20 bots signed in and got a ticket in 4.3 s (216 ms of wall time per bot)
20 bots connected to <address>:9001

welcomes 20   disconnects 0   kicks 0 (last reason 0)   lobby failures 0
over 15.0 s with every bot in: snapshots 4498   bytes 358932
mean frame 79.8 B   per bot 14.99 snapshots/s, 1.17 KB/s (payload only)
stays 20, each joined and left on purpose
```

The run is healthy when:
- `welcomes` equals the number of bots;
- `disconnects` and `kicks` are 0;
- each bot gets 15 snapshots a second.

To check only that the port is open: `(exec 3<>/dev/tcp/127.0.0.1/9021) && echo open` for the arena, or 9001 in
place of 9021 for nginx in front of it.

## 9. How are VS Code's Java extensions set?

The settings are committed in `.vscode/`, so there is nothing to type. [05 — VS Code](05-vscode.md) explains each
one, how the extensions were installed offline, and what was proved. In short:

| Setting | Value | Why |
|---|---|---|
| `java.configuration.maven.userSettings` | `${workspaceFolder}/work/settings.xml` | the Maven import uses the bundle, offline, as the command line does (§3) |
| `java.configuration.runtimes` | `JavaSE-21` → `${workspaceFolder}/vendor/jdk-21` | the projects compile and run on the repository's JDK 21, not on Java 8 |
| `java.jdt.ls.java.home` | **not set**, on purpose | the language server itself needs Java 25, which the Red Hat extension carries; 21 cannot be it, and Java 8 would be refused |
| `java.test.config` | the repository's Java, `work/tmp`, the development MySQL | tests from the editor run as `dev-env.sh` runs them |
| `terminal.integrated.env.linux` | `JAVA_HOME`, `PATH`, `MAVEN_SKIP_RC`, `MAVEN_ARGS`, `MAVEN_OPTS`, `JDBC_URL` | a terminal opened in VS Code already has the `dev-env.sh` environment |

Before the first opening:
1. Run `./build-offline.sh` once, or `. ./dev-env.sh`. Either writes `work/settings.xml`.
2. Open the repository's **root** folder, the one with `build-offline.sh`, not a subfolder.
3. Trust the workspace when VS Code asks.

The debugger's configurations (`.vscode/launch.json`) attach to a process started with `DEBUG=1 ./run-local.sh
start` (or to one process: `DEBUG=1 ./dev-backend.sh restart platform`), or launch one process from its sources
([05 §4](05-vscode.md#4-debugging)).
