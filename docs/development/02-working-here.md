# Working here: the manner, the owner's standing decisions, the machine

This page is for a person, or an agent, who picks up work on this repository. Read it before the first change. It
covers four things:

1. how each unit of work is done (§1);
2. what the owner has decided that is not a design choice, and so is not reopened (§2);
3. the development machine: what is installed, which ports are taken, and how it differs from production (§3);
4. the traps met on that machine, each at least once, with what to do instead (§4).

It was written on 2026-10-05 from notes kept outside the repository until then. [`CLAUDE.md`](../../CLAUDE.md)
carries the same rules for Claude. Both are committed, and a new standing rule goes in both.

**Where the history is.** Until 2026-10-05 this repository was the `background/` folder of a larger one,
https://github.com/Jsoft871222/polyterm. This repository,
https://github.com/greensoft-atom/depio-simulation-platform, starts from a copy of that folder, without its
history. The older history, and the commits the documents cite from before that date (ce30aa7, 04847d8), are in
the previous repository, under `background/`.

## 1. How a unit of work is done

- **Follow the plan's order.** Take the items of [plan.md](../plan.md), "What to do next, in order", strictly in
  that order. A new item is added to that list before anyone works on it.
- **Do each unit the same way, in these steps:**
  1. **Design first.** Write or update the design document. When a choice is made, record it as a decision in the
     [decision log](../architecture/03-decision-log.md).
  2. **Test first.** Write the test, and see it fail for the right reason. Then write the code.
  3. **Mutation check.** Break each new rule on purpose, and see a test catch it. A mutant that survives means a
     test is missing: add it. A run in which no test ran (a compilation error, anywhere) is invalid, not a
     survivor. Build `clean` afterwards (§4 says why).
  4. **Live drill**, when the behaviour crosses processes: `client/headless-drill.sh` against a release.
  5. **Measure**, when the tick, the payload or the bandwidth could move: `TickBenchmark` before and after the
     change, on the same machine.
  6. **Keep the documents current:**
     - the design document and [plan.md](../plan.md);
     - the [defect register](../defects.md): every defect found gets an ID;
     - the [glossary](../glossary.md);
     - a question (Q-*n*) for anything that is the owner's to decide;
     - a new test's row in its module's README, with the count. The build fails without it (`tools`'
       `ReadmeTestListsTest`, DOC-29). The client's tests are listed in
       [Core.Tests/README.md](../../client/Core.Tests/README.md), which its own `ReadmeTestListTests` checks
       (DOC-31);
     - every link in `docs/` must resolve, file and anchor. The build checks that too (`tools`' `DocsLinksTest`,
       DOC-33).
  7. **Commit and push each unit**, through the word check ([Naming](../README.md#naming)). Push to `main`, and
     never rewrite history that has been pushed.
- **After a fix, run the whole test suite of each module the fix touched**, not only the one test. Twice, a full
  build found an older test whose expectation the fix had changed.
- **Report honestly:** what was measured, what failed (with its output), and what was not verified. Latency on the
  development machine varies from run to run (§3): say so.
- **The owner's decisions** (product, balance, scope, cost) are put to the owner with a recommendation, and
  recorded as a question. They are never taken silently. The exceptions are in §2.

## 2. The owner's standing decisions

Each row is a decision the owner made. Work within it; do not propose to undo it. "Since" is the date of the
decision; the last column says where it is recorded.

| Decision | Since | Where it is recorded |
|---|---|---|
| The word that the naming rule excludes is never used, anywhere | 2026-09-23 | [README §Naming](../README.md#naming) |
| Balance (item levels, gems, shop prices, rewards) is chosen by Claude, each number with its reason, without asking | 2026-10-02 | [Q-48](../requirements/01-scope-and-nfrs.md#7-open-questions), 04 §8 |
| No third-party payment provider and no third-party sign-in for now: the payment flow is simulated | 2026-10-04 | Q-52, D-68 |
| One public address for the whole site: an IP address with no domain, with ports 80, 443 and 9001, forwarded to one machine, the front. The other machines are on the private network alone. SSH comes from the internal network alone | 2026-10-19 | C-9, Q-57, D-82, [operations/05](../operations/05-ports-and-firewall.md) |
| nginx is in front, on one of the three machines (every machine is set up the same way). It ends TLS on 443 and 9001, and routes match traffic by the arena's name. The front sees the players' own addresses | 2026-10-19 | D-82 |
| A players' build may connect without TLS, the full client's too: no build refuses a way in clear. The site's 80 serves the API, the lobby and matches in clear. A match on 80 asks for its arena by name, `/match/<name>`, from the same list of arenas as 9001's | 2026-11-25 | Q-64, [D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included), superseding D-104 |
| The certificate comes from a CA of the backend's own (`private-ca.sh`). The app ships its root and trusts that root alone. No public CA, no domain. (Deferred from 2026-09 until the owner brought it back on 2026-10-19.) | 2026-10-19 | D-82, [operations/06](../operations/06-certificates.md) |
| No third-party Redis server, client library or tool, tests included: the store is j-redis | 2026-09 | this page (it was in `CLAUDE.md` only); D-6, D-7 |
| No third-party service or daemon of any kind beyond MySQL and nginx, whether installed or bundled. Monitoring and alerting are the backend's own code: Prometheus, Alertmanager and node_exporter were refused even bundled. Libraries embedded as jars stay allowed | 2026-10-24 | C-6, Q-60, plan item 100 |
| The client is the engine-free C# core, the headless driver, and the Unity layer as thin scripts. Nothing is run in Unity here | 2026-10-04 | Q-51, D-73 |
| The client is for Unity 2019.4 and Visual Studio 2019 first, the team's tools, and on .NET Framework 4.5 alone. Every C# project targets 4.5 at C# 7.3, with no API above it: the core, its tests, the headless driver and UnityCheck, all four in `client/Backend.Client.sln`, VS 2019's. No .NET Standard and no .NET 8: no project targets either, and nothing here runs on .NET 8. Unity 2019 takes the core's assembly at its .NET 4.x API level. The core asks for TLS 1.2 by name. There is no solution for any other version. The backend's CA signs with RSA, which Unity 2019's runtime can check. Here every C# build is Unity 2019.4.28f1's compiler against Mono's 4.5 reference assemblies, and every run is its Mono 5.11, from its editor's files in `work/unity` (`client/unity-env.sh`): the core's tests (`client/core-tests.sh`), the drills' driver and the checks (`client/unity2019-check.sh`). `client/vs2019-check.sh` builds the solution with VS 2019's MSBuild and Roslyn, in Docker: they come only in Microsoft's .NET 5 and .NET Core 3.1 SDK images, the one modern .NET left, taken as build tools alone (nothing targets them, runs on them or ships from them). Unity's Mono outside the editor speaks TLS 1.0 alone, so the C# client is driven here in clear alone | 2026-11-05; 4.5 alone since 2026-11-25 | D-108, D-109, [D-116](../architecture/03-decision-log.md#d-116--the-client-on-net-framework-45-alone-built-and-run-here-without-net-8-and-driven-in-clear) |
| A simple client for the UI team, `client-simple/`, beside the full one: the API, the lobby and a match in small files to read and paste. It uses C# 7.3 on .NET Framework 4.5 alone, with no API above it (their Unity is 2019, at its .NET 4.x level), and no `UnityEngine` in its `Runtime/`. `client-simple-no-tls/` is the same. A protocol change is made in both clients (since D-114, in all three). Checked by `client-simple/check.sh` (Unity 2019.4's compiler, and its Mono for the offline checks) and by `headless-drill.sh` with `SIMPLE=1`, live in clear alone | 2026-11-24; 4.5 alone since 2026-11-25 | D-111, D-116 |
| `client-simple`'s API runs over `HttpClient`, each call awaited. The app's certificate check is the backend's root, set through `ServicePointManager` (the only check that Unity 2019's `HttpClient` calls). A `csc.rsp` gives Unity's .NET 4.x level what it needs | 2026-11-25 | D-112 |
| `client-simple-no-tls/` is the simple client without TLS, for a backend run plain (`PLAIN=1`) and for the site's 80 (D-115). Its `ArenaMessages.cs` and `Json.cs` are `client-simple`'s byte for byte, and one check covers both | 2026-11-25 | D-114 |
| The migrations were squashed once, before launch, to V1 (the schema) and V2 (the seed). From V3 on they are forward-only | 2026-10-04 | D-75 |
| A result under five seconds counts for nothing but its row and, in a rated match, the rating. A walkover is no win | 2026-10-05 | D-76 |
| The Unity editor's check and an admin panel are put off: not proposed until the owner brings them back | 2026-10-05 | Q-54, Q-55 |
| These open questions are held: the copy off the site's host, results past 20 000 players, the app's updates, where alerts go, and the launch's machine. They are recorded, and not raised again until the owner brings them back | 2026-11-01 | Q-53, Q-58, Q-59, Q-60, Q-62 |
| The app is for Android alone, installed from the owner's own copy, not from Google Play or the App Store. No store's rules apply, iOS is not a target, and updates come by the owner's own channel (Q-59, open). A guest account that nobody plays for 180 days is erased by retention (Q-61, decided 2026-10-26) | 2026-10-24 | C-10, D-1, D-84, D-87 |
| The site launches on one machine, for up to about 5 000 players. Every role runs on it, with the three machines' names and ports on loopback, and the backups beside the database on a disk of their own. Three machines stay the design for 50 000 | 2026-11-01 | C-11, D-93, plan items 113 and 115 |
| Java 21 only (no Java 8 line), and MySQL 8.4 LTS. The release carries its own Java runtime, MySQL and nginx; nothing is installed as an OS package | 2026-10-05 | Q-56, plan item 80 |
| The production machines run RHEL 9.x (the owner thinks 9.5) | 2026-09-22 | requirements C-1; §3 below |
| Everything that a development machine builds, tests, runs and debugs stays inside the checkout (`work/`). A Java and a MySQL already installed there (the owner's: Java 8, MySQL 5) are neither used nor changed | 2026-10-17 | D-80, [03](03-build-and-run.md) |
| A run by hand is one script per service: nginx, MySQL, j-redis and the backend. Each has `start`, `status`, `stop`, `restart` and `test`, and j-redis and the backend also have `build`. The owner runs it as root. Clients on other machines (a phone's app, a laptop's Postman) reach it through nginx on 80, 443 and 9001 | 2026-11-01 | D-92, [03 §5](03-build-and-run.md#5-running-it-by-hand) |
| No secret passes through systemd's units. Each process takes its own secrets from its settings file, `/etc/backend/<process>.env`, as plain variables, and the file is root's alone (mode 0600). The backups' scripts read their own files. Nothing of systemd's is used for them but `EnvironmentFile=` | 2026-11-02 | D-102, [operations/01 §2](../operations/01-deploy.md#secrets) |

## 3. The development machine

The development machine is a shared Ubuntu 24.04 VM with 12 cores. Other users' jobs run on it, and its load
average sits around 5 to 9, so latency numbers measured on it vary between runs. **What this table lists under
`/opt` is this VM's own.** A clone needs none of it: it builds, tests and runs with what the repository carries
([03 §1](03-build-and-run.md#1-what-you-need-and-what-the-repository-touches)).

| What | Where | Notes |
|---|---|---|
| JDK 21 | `/opt/jdk21` | Temurin 21.0.12.1+1, not on the `PATH`. The work uses `vendor/jdk-21`, the same build, through `dev-env.sh` |
| Maven | `/opt/maven` | 3.9.16. The work uses `vendor/maven-3.9` |
| The development MySQL | 127.0.0.1:3314 | `vendor/`'s 8.4, its files in `work/mysql` (`dev-mysql.sh`): the tests' and the benchmarks' database server |
| The JDK and Maven, committed | `vendor/jdk-21`, `vendor/maven-3.9` | the same versions, usable as they are. `build-offline.sh` builds with them alone, with no network ([09 §3](../detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| .NET 8 SDK | `/opt/dotnet` | not used since D-116 (2026-11-25): nothing here builds or runs on .NET. It was for `client/`, and was never in `vendor/` (D-77) |
| Unity 2019.4.28f1's Linux editor | `work/unity` | every C# build here (its compiler, Roslyn 2.9.1, against Mono's .NET Framework 4.5 reference assemblies) and every C# run (its Mono 5.11). `client/unity-env.sh` fetches it once (1.8 GB, its SHA-256 checked), with the NuGet packages the tests and the driver take. The editor itself is never run: it needs a licence (D-116) |
| MySQL | 127.0.0.1:3306 | 8.0.46 from the distribution, with the databases `backend_dev` and `backend_test` and the user `backend` / `backend-dev-password`. Nothing here has used it since O-40 (2026-10-23): the tests, the drills and a run by hand use the development MySQL on 3314 |
| Docker | `/usr/bin/docker` | other users' containers run here too: give ours distinct names, and remove them afterwards |
| QEMU | `/usr/bin/qemu-system-x86_64` | 8.2, with `cloud-localds`, installed on 2026-10-28 for `backend/scripts/check-selinux-el9.sh`: a Rocky 9 guest with SELinux enforcing. That is the only way to see SELinux from this machine: its kernel's security module is AppArmor, and Docker shares the kernel. There is no KVM here, so the guest is emulated: forty minutes the first time, ten after. Its image and disk are in `work/el9` (D-90) |
| The internet | reachable | Maven Central, NuGet.org, GitHub, Unity's and the vendors' downloads |

**Ports on this machine:**

| Who | Ports |
|---|---|
| Other users' processes (not ours) | 6379 (a valkey server: start j-redis on another port), 6381, 8082 |
| A run by hand (D-92) | 3314 (MySQL), 6380 (j-redis), 8080 (platform), 8081 (gateway), 9021 (arena), and nginx on 80, 443 and 9001 on every IPv4 address |
| The drills | 6390 and 6392 (j-redis and its replica), 8093 (platform), 8094 (gateway), 9011 (arena), 9195 to 9199 (the metrics and the admin API), 9193 and 9194 (a second worker's metrics, and the alerts' receiver), 3307 to 3309 (the drills' own MySQL servers), and with `FRONT=` nginx in front on 8095, 8096 and 9095 (in place of 80, 443 and 9001; `client/headless-drill.sh`) |

**Port 80 is open to the internet on this machine.** Its firewall (ufw) lets 80 in. So while `dev-nginx.sh` runs
here on 80, anyone outside can reach the API, the lobby and, through `/match/`, the arena, in clear (D-107, D-115;
before D-107, 80 only redirected). Run `dev-nginx.sh` here on moved ports instead, which the firewall keeps closed:
`NGINX_HTTP_PORT=8088 NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443`, given to each command. 443 and 9001 are not
open to the internet either.

**A user who is not root, `devtest`.** Everything else here runs as root, so `devtest` is how the flow is tried as
a user who is not root. Until item 106 it had only ever run as root here (O-46).

- It has a clone of its own in `/home/devtest/background`, and a network namespace `devtest` with loopback alone.
  After a reboot, make the namespace again: `ip netns add devtest && ip netns exec devtest ip link set lo up`.
- To run a command there:
  `ip netns exec devtest runuser -u devtest -- bash -lc 'cd ~/background && export NGINX_HTTP_PORT=8088
  NGINX_HTTPS_PORT=8443 NGINX_MATCH_PORT=9443 && ./run-local.sh start'`. nginx's own 80 and 443 are root's, hence
  the moved ports.
- To reproduce a refused port, raise the unprivileged-port threshold in that namespace alone:
  `ip netns exec devtest sysctl -w net.ipv4.ip_unprivileged_port_start=9100`, and set it back to 1024 afterwards.
- The namespace has no network: the clone builds (from the bundle), but the client's first build does not. It
  fetches Unity's editor and the NuGet packages it takes (D-116).

**This is not the production platform.** Production is RHEL 9.x, with glibc 2.34; this machine has glibc 2.39.
Anything compiled here may need newer glibc symbols (2.38's, for one) and then not start there. So:

- native code for the release is built in a Rocky Linux 8 container, for glibc 2.28 (D-100);
- the release is checked in Red Hat's UBI 8 and UBI 9 images, which are RHEL's own userspace (plan items 80
  and 119);
- RHEL 8 runs the binaries; since D-102 it runs the units too (its systemd 239 ignores three sandbox settings);
  since D-103 it runs the certificate scripts on its OpenSSL 1.1.1. The install guide runs as written in UBI 8.10
  (Q-63);
- each certificate script's check runs on OpenSSL 1.1.1 and on 3.x (`check-certificates.sh` with `IMAGE=`).

## 4. Traps, each met at least once

Each trap below happened at least once. Each says what went wrong and what to do instead.

- **`ausearch` reads a piped stdin as its input.** A script fed to `bash -s` over ssh lost its own tail to the
  first `ausearch` in it. That `ausearch` then found no denials, and the rest of the script never ran
  (2026-10-28, D-90). Run such a script from a file, with stdin closed: `bash cmd.sh < /dev/null`. And
  **`pgrep` without `-f` matches at most fifteen characters of a name**: `pgrep qemu-system-x86_64` finds
  nothing. Use `pgrep -f` for it.
- **A race that the owner's machine shows and this machine's twelve cores hide.** The owner's full build on their
  VM failed `ArenaServerTest.aSandboxSummonsOneGuardian`: no Guardian in 30 s. Every build here had passed it.
  Pinned to one CPU with two busy loops beside it, thirty runs passed here too (T-63, 2026-10-27). Do not wait for
  a reproduction. Read the tick's order for the window that the log fits, and write a test that puts the request in
  that window by construction (there: a power sent with the join, before the Welcome).
- **Everything here runs as root, so a policy that refuses a port to a user never shows.** The owner's
  `dev-mysql.sh` and `run-local.sh` failed with `Permission denied` on 3314 and 8080, which no run on this VM could
  have met (O-46, 2026-10-26). On Linux a port above 1023 is any user's, so such a refusal is the machine's policy:
  the threshold `net.ipv4.ip_unprivileged_port_start`, a login that SELinux confines, or WSL's reserved ranges.
  `check-bind.sh` names it before anything starts (D-88). Run what must work for a user who is not root as
  `devtest`, in its namespace (§3). **The same holds for the owner's way on RHEL 9**: nginx by `sudo`, and the rest
  as the user. It is tried in a Rocky 9 container with a home of mode 0700. Run only as root here, root's nginx
  shut the user out of the development root CA and out of its own `stop` (O-91, 2026-11-02, D-98).
- **A failing test reads like a plugin that could not be fetched.** After test failures, Maven's last line is
  `Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:…`. On a clone, `build-offline.sh`'s
  database tests once went to 127.0.0.1:3306 and failed that way, and the owner took it for a download error
  (O-38, 2026-10-17). Read the lines above it.
- **Maven reads `/etc/mavenrc` and `~/.mavenrc` before anything else**, and either may set `JAVA_HOME` to another
  Java (a machine with a packaged Maven has one). `dev-env.sh` sets `MAVEN_SKIP_RC=1`.
- **The release's runtime has no debugger agent** (`jdk.jdwp.agent` is not among its modules), so
  `-agentlib:jdwp` fails on it. `DEBUG=1 ./run-local.sh` runs the processes on `vendor/jdk-21` instead.
- **A Unix socket's path is at most 107 bytes**, and a checkout deep in a home directory goes past it. So the
  development MySQL's socket is named relative to its data directory, where `mysqld` runs.
- **`x=$(ls … | head -1)` under `set -euo pipefail` ends the script silently** when `ls` finds nothing. Add
  `|| true`, and test for the empty result (`run-local.sh`, 2026-10-17).
- **`git add -A` takes the owner's edits too.** The owner works in the same folder, from VS Code. A `.gitignore`
  line they had added went into a commit of Claude's (954994d, 2026-10-19). Read `git status` before staging, and
  stage by name what is not your own work.
- **Name a test's target process by its own pid (`$!`), never find it by name.** `pgrep -n -x sleep` once found
  another program's `sleep 1`, a container's keep-alive loop's, and the test killed it (2026-10-17; the loop and its
  container went on).
- **`kill -0` succeeds on a zombie.** A child that the test killed answers it until it is reaped. Judge liveness by
  the state instead: `ps -o stat=` is not `Z`.
- **`a | grep -q` under `pipefail` can fail even though `grep` matched.** `grep` ends first, `a` dies of SIGPIPE,
  and the pipeline's status is `a`'s. Read the output into a variable and match that (`run-local.sh`'s guard; a
  mutant that swapped `grep` for `true` made it fail one run in two).
- **`env -i` drops `PWD`**, so a script started through a symbolic link sees the resolved path. A check of "a path
  with a space" through a link then does not test it. Use a real directory.
- **`ss` shows a JVM's `127.0.0.1:8080` as `[::ffff:127.0.0.1]:8080`.** Java's sockets are IPv6, so a match on
  `127.0.0.1:8080` finds nothing. And an arena's `0.0.0.0` is `*:9001`, both address families, reachable over IPv6
  too. The store's unit sets `preferIPv4Stack`, and shows plain IPv4 (2026-10-19, `check-ports-el9.sh`).
- **A privileged container's systemd shares the host root's inotify watches.** On the development VM, VS Code's
  file watchers hold nearly all 65 536 of them. The container's `dbus-broker` then failed with
  `fatal error: -28` (ENOSPC), so firewalld never started; this happened after a release rebuild gave VS Code more
  files to watch. Count the watches per process in `/proc/<pid>/fdinfo`. The owner allowed
  `sysctl -w fs.inotify.max_user_watches=524288`, until the next reboot (2026-10-19).
- **A heredoc's text must not hold its own end word on a line alone.** A script fed to `python3 - <<'EOF'` carried
  a guide's `EOF`. The heredoc ended there, and the shell ran the rest of the text as commands on the VM until a
  syntax error stopped it (2026-10-19; it made one empty directory, since removed). Write such a script to a file,
  or give the heredoc an end word that the text cannot hold.
- **firewalld's `--reload` keeps connections already made.** A port closed to an address cuts only that address's
  new connections; a session already open from there goes on, and is the last (2026-10-19, measured).
  `--complete-reload` did not cut it either, in the check's container.
- **`firewall-cmd` refuses `--add-service` and `--add-port` in one command**, and then applies none of it. Several
  of one option in one command are fine. The install guide's firewall step did that, and no one had run it (O-42,
  2026-10-19).
- **Red Hat's Java extension runs on Java 25 or later since version 1.57**, so `java.jdt.ls.java.home` cannot be
  the repository's 21. Leave it unset; the extension carries its own Java ([05](05-vscode.md)).
- **`~/.m2` holds artifacts from two sources** (before D-80, when the work used `~/.m2`): Maven Central and the
  `java21-offline` mirror. An offline (`-o`) build trusts only the artifacts cached from the repository it is
  using. So, on this machine before D-80, `make-release.sh` was run as
  `MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh`.
  `build-offline.sh` keeps clear of this with a local repository of its own, `work/m2`.
- **A library in the bundle but new to the backend** was not in `~/.m2`, and the offline commands that `CLAUDE.md`
  had before D-80 failed on it ("has not been downloaded from it before"). The fix then was to copy its directory
  from `java21-offline/repository` into `~/.m2/repository` first (`bcpkix` and `bcutil`, 2026-10-05). Today
  `CLAUDE.md`'s commands source `dev-env.sh`, which reads the bundle, and `build-offline.sh` needs nothing either.
- **Maven's `-o` also blocks `file://` repositories.** So building from `java21-offline` uses its
  `settings-offline.xml` as a mirror, without `-o` ([its README](../../java21-offline/README.md)).
- **A binary compiled here may not start on RHEL.** This machine's glibc is 2.39, RHEL 9's is 2.34 and RHEL 8's is
  2.28 (§3). So native code for the release is compiled in a Rocky Linux 8 container (`vendor/build-nginx.sh`,
  with Docker, D-100), never here. So is any library taken from a distribution's packages: RHEL 9's `libnuma`
  needed glibc 2.33 (plan item 119). `backend/scripts/check-release-el9.sh` checks a release in UBI 8 and UBI 9
  with nothing installed, every native file in it
  ([09 §7](../detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).
- **A library that RHEL 9 dropped from its base.** A file built for RHEL 8 can need a library that RHEL 9 moved to
  an extra package. SASL's `libplain.so` needs `libcrypt.so.1`, which on RHEL 9 is only in `libxcrypt-compat`
  (O-99, D-110). A check that found every library of seven programs said nothing of it: `check-release-el9.sh` now
  asks about every ELF file.
- **A mutant that "survives" because no test ran.** A mutation harness that reads "Failures: 0" as survival also
  calls a run in which nothing ran a survivor. Three engine mutants "survived" while a test file written ahead of
  its classes kept the module's tests from compiling (item 100, 2026-10-24). A harness must read the count of tests
  run, and treat none, or a compilation error, as invalid, never as a result. It must also run its commands under
  `bash`: `dev-env.sh` is bash, and `sh -c '. dev-env.sh && mvn …'` dies at once with "Bad substitution". That made
  fourteen mutants "invalid" in a row (item 105, 2026-10-26). Python's `subprocess.run(shell=True)` uses `sh`
  unless told `executable='/bin/bash'`.
- **`pkill -f` and `pgrep -f` match the shell that runs them** when the pattern is in that shell's command line.
  Use the bracket trick: `pkill -f "[A]renaMain"`. And match a class's **full name**: `WorkerMain` alone also
  matches VS Code's own worker processes (`...WorkerMain --node-ipc`), which a stop of the stack once killed
  (2026-10-06). Write `pkill -f "[c]om.backend.worker.WorkerMain"`.
- **A mutation check leaves the mutant compiled.** The source put back is older than the mutant's class in
  `target/`, so Maven's incremental build keeps the mutant's class. Build `clean` after a mutation run, before any
  test, release or drill (2026-10-06).
- **`dotnet test` printed "Passed!" when its test host died part-way** (before D-116), counting only the tests that
  ran; `Total tests:` read `Unknown` after a crash. `client/core-tests.sh`, which replaced it, prints NUnitLite's
  `Test Count:` line, and fails when NUnitLite printed none. Read the count.
- **`mvn clean install` replaces `target/*-all.jar`** under a server running from `target/`, which then dies with
  `NoClassDefFoundError`. Run servers from a copy of the release (`RELEASE=` for the drills while a build runs).
- **The headless driver ran the build in `client/Headless/bin/Debug`, and the drill script did not build it**
  (before D-116). After a change to the core or the driver, a drill ran the old code unless `dotnet build Headless`
  came first. Since D-116 the drill builds the driver afresh for each run, in its own directory.
- **What this machine has, a minimal RHEL 9 may not have.** `jlink --strip-debug` calls `objcopy` (from binutils),
  which is here and is absent there, and a release built on a fresh server failed on it.
  `check-install-guide-el9.sh` checks everything a build or a release runs, building in UBI 9 with nothing added.
- **`pkill -f <pattern>` kills the shell that runs it** when that shell's own command line holds the pattern, as
  `bash -c "pkill -f 'node server.mjs'; …"` does. The rest of the command never ran (exit 144, 2026-10-19). Kill
  by the pid kept at the start (`kill $(cat server.pid)`), or write a pattern that the command line cannot hold
  (`pkill -f '[n]ode server.mjs'`).
- **Never edit a script while it runs.** bash reads a script as it goes, and an edit moves what it reads next. A
  drill edited mid-run ran its scenarios, then failed with a syntax error where its failover should have begun.
- **A shell variable left empty in a backup path** (`cp f $SP/x` with `SP` unset) writes to `/`. Set paths in the
  same command that uses them.
- **The machine's MySQL is on CEST, production's on UTC.** Anything the drills write by hand is stamped in UTC
  (`drill-sql.sh` sets the session's time zone).
- **The full backend build takes about an hour**, longer than the 30-minute limit some tools put on a background
  command. Run it under `nohup`, and watch its log.
- **nginx run as root gives its workers to `nobody`, and `/root` is closed to them** (mode 700). A file they must
  open inside a checkout under `/root` fails with `Permission denied`, as a temporary file for a large response did
  (O-47). On loopback the sockets' buffers take a few MB, so a check that nginx writes no such file needs a response
  larger than that: 300 KB never reached one.
- **Unity 2019.4's runtime, outside its editor** (2026-11-05, D-108):
  - Its Mono 5.11 never sees a child process's output end, so `Process.Start` with redirected output hangs, even
    for `echo`. Make what a check needs before the check runs.
  - It reads no PKCS#12 file that OpenSSL 3 writes by default (AES, PBKDF2). A test's keys are exported with
    `-keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1`.
  - Its own TLS is Mono's, TLS 1.0, which nginx refuses. So a test uses a server of Mono's own, never the editor's
    or a phone's TLS. And since D-116 the drills and the checks in containers drive the C# client in clear alone:
    `TLS=1`, `FRONT=https` and the scenario `untrusted` are refused.
  - Its .NET Core compiler (`Tools/Roslyn/csc`) wants an ICU library that this machine lacks.
    `Tools/RoslynNet46/csc.exe` under its Mono is the same Roslyn, 2.9.1.
- **Two solution files in `client/`, and `dotnet test` names neither**: MSBuild refuses ("more than one project or
  solution file"). Keep one solution, and name the tests by their path. **And `dotnet test Core.Tests` without
  `-f net8.0`** also ran the .NET Framework 4.7.1 build, which off Windows wants a `mono` on the `PATH` (D-109).
  Since D-116 every project has one target, .NET Framework 4.5, and nothing here runs `dotnet test`.
- **Mono's `float` is a double** unless `--optimize=float32` is given (Unity's own fork turns it on by default). On
  Unity 2019's standalone `mono`, the own tank's step differed from the server's in its last bits (P-65). And
  **Mono's `HttpListener` has no WebSocket server** (`AcceptWebSocketAsync` throws `NotImplementedException`), so
  the tests' lobby is a server of their own.
- **`true || a && b` is `(true || a) && b`** in the shell. A mutant that puts `true ||` before a chain of `&&`
  removes only the first check, and survives on the others. Replace the whole condition (`if true; then`), as item
  110's mutant did once the first form had survived.
