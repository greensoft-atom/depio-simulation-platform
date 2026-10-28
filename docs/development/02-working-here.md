# Working here: the manner, the owner's standing decisions, the machine

What a person or an agent picking this work up needs before the first change: how
each unit of work is done, what the owner has decided that is not a design
choice, and the development machine's facts and traps. Written 2026-10-05 from
notes kept outside the repository until then. `CLAUDE.md` carries the same rules
for Claude.

Until 2026-10-05 this was the `background/` folder of a larger repository,
https://github.com/Jsoft871222/polyterm; this one,
https://github.com/greensoft-atom/depio-simulation-platform, starts from a copy of
that folder, without its history. The history, and the commits the documents
cite from before then (ce30aa7, 04847d8), are in the previous one,
under `background/`.

## 1. How a unit of work is done

- **In the plan's order.** [plan.md](../plan.md), "What to do next, in order",
  strictly in that order; a new item is added there before it is worked on.
- **Each unit the same way:**
  1. Design first: the design document, and a decision in the
     [decision log](../architecture/03-decision-log.md) when a choice is made.
  2. Test first: the test written and seen to fail for the right reason, then
     the code.
  3. Mutation check: each new rule broken on purpose and a test seen to catch it;
     a surviving mutant is a missing test.
  4. A live drill (`client/headless-drill.sh`) against a release when the
     behaviour crosses processes.
  5. A measurement (`TickBenchmark`) before and after when the tick, the payload
     or the bandwidth could move.
  6. The documents current: the design, [plan.md](../plan.md), the
     [defect register](../defects.md) (every defect found gets an ID), the
     [glossary](../glossary.md), a question (Q-*n*) for what is the owner's, and a
     new test's row in its module's README, with its count: the build fails without
     it (`tools`' `ReadmeTestListsTest`, DOC-29); the client's in
     [Core.Tests/README.md](../../client/Core.Tests/README.md), which its own
     `ReadmeTestListTests` checks (DOC-31); and every link in `docs/` resolving, file
     and anchor, which the build checks too (`tools`' `DocsLinksTest`, DOC-33).
  7. Committed and pushed per unit, through the word check
     ([Naming](../README.md#naming)).
- **After a fix, the whole test suite of each module it touched**, not the one
  test: twice a full build found an older test whose expectation the fix had
  changed.
- **Honest reporting:** what was measured, what failed (with its output), what
  was not verified. Latency on this machine varies run to run (§3); say so.
- **The owner's decisions** (product, balance, scope, cost) are put to the owner
  with a recommendation and recorded as a question, never taken silently; the
  exceptions are in §2.

## 2. The owner's standing decisions

| Decision | Since | Where it is recorded |
|---|---|---|
| The word the naming rule excludes is never used, anywhere | 2026-09-23 | [README §Naming](../README.md#naming) |
| Balance (item levels, gems, shop prices, rewards) is chosen by Claude, each number with its reason, without asking | 2026-10-02 | [Q-48](../requirements/01-scope-and-nfrs.md#7-open-questions), 04 §8 |
| No third-party payment provider and no third-party sign-in for now; the payment flow is simulated | 2026-10-04 | Q-52, D-68 |
| One public address for the whole site, an IP with no domain, with 80, 443 and 9001, forwarded to one machine, the front; the others on the private network alone; SSH from the internal network alone | 2026-10-19 | C-9, Q-57, D-82, [operations/05](../operations/05-ports-and-firewall.md) |
| nginx in front, on one of the three machines (every machine set up the same), ends TLS on 443 and 9001 and routes match traffic by the arena's name; the front sees players' own addresses | 2026-10-19 | D-82 |
| The certificate is from a CA of the backend's own (`private-ca.sh`), its root shipped in the app and trusted alone; no public CA, no domain. (Deferred from 2026-09 until the owner brought it back on 2026-10-19.) | 2026-10-19 | D-82, [operations/06](../operations/06-certificates.md) |
| No third-party Redis server, client library or tool, tests included: the store is j-redis | 2026-09 | this page (it was in `CLAUDE.md` only); D-6, D-7 |
| No third-party service or daemon of any kind beyond MySQL and nginx, installed or bundled: monitoring and alerting are the backend's own code (Prometheus, Alertmanager and node_exporter refused even bundled); libraries as jars stay allowed | 2026-10-24 | C-6, Q-60, plan item 100 |
| The client: the engine-free C# core, the headless driver, and the Unity layer as thin scripts; nothing is run in Unity here | 2026-10-04 | Q-51, D-73 |
| The migrations squashed to V1 (schema) and V2 (seed) once, before launch; forward-only from V3 | 2026-10-04 | D-75 |
| A result under five seconds counts for nothing but its row and a rated match's rating; a walkover is no win | 2026-10-05 | D-76 |
| The Unity editor's check and an admin panel are put off: not proposed until the owner brings them back | 2026-10-05 | Q-54, Q-55 |
| The app is for Android alone, installed from the owner's own copy, not Google Play or the App Store: no store's rules, no iOS, updates by the owner's own channel (Q-59, open); a guest nobody plays for 180 days is erased by retention (Q-61, decided 2026-10-26) | 2026-10-24 | C-10, D-1, D-84, D-87 |
| Java 21 only (no Java 8 line); MySQL 8.4 LTS; the release carries its own Java runtime, MySQL and nginx, nothing installed as an OS package | 2026-10-05 | Q-56, plan item 80 |
| The production machines run RHEL 9.x (the owner thinks 9.5) | 2026-09-22 | requirements C-1; §3 below |
| Everything a development machine builds, tests, runs and debugs stays inside the checkout (`work/`); a Java and a MySQL already installed there (the owner's: Java 8, MySQL 5) are neither used nor changed | 2026-10-17 | D-80, [03](03-build-and-run.md) |

## 3. The development machine

A shared Ubuntu 24.04 VM, 12 cores, with other users' jobs on it: the load
average sits around 5 to 9. **What this table lists under `/opt` is this VM's own**:
a clone needs none of it, and builds, tests and runs with what the repository
carries ([03 §1](03-build-and-run.md#1-what-you-need-and-what-the-repository-touches)).

| What | Where | Notes |
|---|---|---|
| JDK 21 | `/opt/jdk21` | Temurin 21.0.12.1+1, not on PATH; the work uses `vendor/jdk-21`, the same build, through `dev-env.sh` |
| Maven | `/opt/maven` | 3.9.16; the work uses `vendor/maven-3.9` |
| The development MySQL | 127.0.0.1:3314 | `vendor/`'s 8.4, its files in `work/mysql` (`dev-mysql.sh`): the tests' and the benchmarks' |
| The JDK and Maven, committed | `vendor/jdk-21`, `vendor/maven-3.9` | the same versions, usable as they are; `build-offline.sh` builds with them alone, no network ([09 §3](../detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| .NET 8 SDK | `/opt/dotnet` | for `client/`; NuGet.org is reachable. Not in `vendor/`: the owner chose the backend only (D-77) |
| MySQL | 127.0.0.1:3306 | 8.0.46 from the distribution; databases `backend_dev` and `backend_test`, user `backend` / `backend-dev-password`. Used by nothing here since O-40 (2026-10-23): the tests, the drills and a run by hand use the development MySQL on 3314 |
| Docker | `/usr/bin/docker` | other users' containers too: name ours distinctly, remove them after |
| QEMU | `/usr/bin/qemu-system-x86_64` | 8.2, with `cloud-localds`, installed 2026-10-28 for `backend/scripts/check-selinux-el9.sh`: a Rocky 9 guest with SELinux enforcing, the only way to see SELinux from this machine (its kernel's is AppArmor, and Docker shares the kernel). No KVM here, so the guest is emulated: forty minutes the first time, ten after; its image and disk in `work/el9` (D-90) |
| The internet | reachable | Maven Central, NuGet.org, GitHub, the vendors' downloads |

**Ports taken by others:** 6379 (a valkey server, not ours: start j-redis on
another port), 6381, 8082. **The drills' ports:** 6390 and 6392 (j-redis and its
replica), 8093 (platform), 8094 (gateway), 9011 (arena), 9195 to 9199 (metrics
and the admin API), 3307 to 3309 (the drills' own MySQL servers).

**A user who is not root, `devtest`**, with a clone of its own in `/home/devtest/background` and a
network namespace `devtest` (loopback alone; after a reboot, `ip netns add devtest && ip netns exec devtest
ip link set lo up` makes it again): the flow as a user who is not root, which until item 106 had only
ever run as root here (O-46). A command there: `ip netns exec devtest runuser -u devtest -- bash -lc 'cd
~/background && ./run-local.sh start'`; the unprivileged-port threshold raised there alone, to reproduce
a refusal: `ip netns exec devtest sysctl -w net.ipv4.ip_unprivileged_port_start=9100` (and back to 1024).
The namespace has no network: the clone builds (the bundle), but the client's NuGet restore does not.

**This is not the production platform.** Production is RHEL 9.x, with glibc
2.34; this machine has glibc 2.39. Anything compiled here may need newer glibc
symbols (2.38's, for one) and not start there: native code for the release is
built in a Rocky Linux 9 container, and the release is checked in Red Hat's UBI 9
image, which is RHEL 9's own userspace (plan item 80).

## 4. Traps, each met at least once

- **`ausearch` reads a piped stdin as its input**: a script fed to `bash -s` over ssh lost its own tail to the
  first `ausearch` in it, which then found no denials and let the rest never run (2026-10-28, D-90). Run such
  a script from a file with stdin closed (`bash cmd.sh < /dev/null`). And **`pgrep` without `-f` matches at
  most fifteen characters of a name**: `pgrep qemu-system-x86_64` finds nothing; `pgrep -f` it.
- **A race the owner's machine shows and this machine's twelve cores hide**: the owner's full build on their
  VM failed `ArenaServerTest.aSandboxSummonsOneGuardian`, no Guardian in 30 s, which every build here had
  passed; pinned to one CPU with two busy loops beside it, thirty runs passed here too (T-63, 2026-10-27).
  Do not wait for a reproduction: read the tick's order for the window the log fits, and write the test
  that puts the request in it by construction (there, a power sent with the join, before the Welcome).
- **Everything here runs as root, so a policy that refuses a port to a user never shows**: the
  owner's `dev-mysql.sh` and `run-local.sh` failed with `Permission denied` on 3314 and 8080, which no
  run on this VM could have met (O-46, 2026-10-26). A port above 1023 is any user's on Linux, so such a
  refusal is the machine's policy (the threshold `net.ipv4.ip_unprivileged_port_start`, a login SELinux
  confines, WSL's reserved ranges), which `check-bind.sh` names before anything starts (D-88). What must
  work for a user who is not root is run as `devtest`, in its namespace (§3).
- **A failing test reads like a plugin that could not be fetched.** Maven's last line
  after test failures is `Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:…`.
  On a clone, `build-offline.sh`'s database tests once went to 127.0.0.1:3306 and failed
  that way, which the owner took for a download error (O-38, 2026-10-17). Read the
  lines above it.
- **Maven reads `/etc/mavenrc` and `~/.mavenrc` before anything**, and either may set
  `JAVA_HOME` to another Java (a machine with a packaged Maven has one). `dev-env.sh`
  sets `MAVEN_SKIP_RC=1`.
- **The release's runtime has no debugger agent** (`jdk.jdwp.agent` is not among its
  modules): `-agentlib:jdwp` fails on it. `DEBUG=1 ./run-local.sh` runs on `vendor/jdk-21`.
- **A Unix socket's path is at most 107 bytes**, and a checkout deep in a home directory
  passes it: the development MySQL's socket is named relative to its data directory, where
  `mysqld` runs.
- **`x=$(ls … | head -1)` under `set -euo pipefail` ends the script silently** when `ls`
  finds nothing: add `|| true` and test the empty result (`run-local.sh`, 2026-10-17).
- **`git add -A` takes the owner's edits too**: the owner works in the same folder, from VS
  Code. A `.gitignore` line they had added went into a commit of Claude's (954994d,
  2026-10-19). Read `git status` before staging, and stage by name what is not one's own work.
- **A test's target process is named by its own pid (`$!`), never found by name**:
  `pgrep -n -x sleep` once found another program's `sleep 1`, a container's keep-alive
  loop's, and the test killed it (2026-10-17; the loop and its container went on).
- **`kill -0` succeeds on a zombie**: a child the test killed answers it until it is reaped.
  Judge liveness by the state, `ps -o stat=` not `Z`.
- **`a | grep -q` under `pipefail` can fail though `grep` matched**: `grep` ends first, `a`
  dies of SIGPIPE, and the pipeline's status is `a`'s. Read into a variable and match that
  (`run-local.sh`'s guard; a mutant that swapped `grep` for `true` made it fail one run in two).
- **`env -i` drops `PWD`**, so a script started through a symbolic link sees the resolved
  path: a check of "a path with a space" through a link does not test it. Use a real directory.
- **`ss` shows a JVM's `127.0.0.1:8080` as `[::ffff:127.0.0.1]:8080`**: Java's sockets are
  IPv6, so a match on `127.0.0.1:8080` finds nothing, and an arena's `0.0.0.0` is `*:9001`, both
  families, reachable over IPv6 too. The store's unit sets `preferIPv4Stack` and shows plain
  IPv4 (2026-10-19, `check-ports-el9.sh`).
- **A privileged container's systemd shares the host root's inotify watches**, and on the
  development VM VS Code's file watchers hold nearly all 65 536: the container's `dbus-broker`
  failed with `fatal error: -28` (ENOSPC), so firewalld never started, after a release rebuild
  gave VS Code more files to watch. Count them per process in `/proc/<pid>/fdinfo`. The owner
  allowed `sysctl -w fs.inotify.max_user_watches=524288`, until the next reboot (2026-10-19).
- **A heredoc's text must not hold its own end word on a line alone**: a script fed to `python3 - <<'EOF'`
  that carried a guide's `EOF` ended there, and the shell ran the rest of the text as commands on
  the VM until a syntax error stopped it (2026-10-19; it made one empty directory, removed). Write
  such a script to a file, or give the heredoc an end word the text cannot hold.
- **firewalld's `--reload` keeps connections already made**: a port closed to an address cuts
  only its new connections. A session from there goes on, and is the last (2026-10-19, measured).
  `--complete-reload` did not cut it either, in the check's container.
- **`firewall-cmd` refuses `--add-service` and `--add-port` in one command**, and applies none
  of it; several of one option are fine. The install guide's firewall step did that, and no one
  had run it (O-42, 2026-10-19).
- **Red Hat's Java extension runs on Java 25 or later since 1.57**: `java.jdt.ls.java.home`
  cannot be the repository's 21. Leave it unset; the extension carries its own ([05](05-vscode.md)).
- **`~/.m2` holds artifacts from two sources** (before D-80, when the work used it), Maven Central and the
  `java21-offline` mirror, and an offline (`-o`) build trusts only those cached
  from the repository it is using. `make-release.sh` therefore runs as
  `MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh`.
  `build-offline.sh` keeps clear of it with a local repository of its own.
- **A library in the bundle but new to the backend** is not in `~/.m2`, and the
  offline commands of `CLAUDE.md` fail on it ("has not been downloaded from it
  before"): copy its directory from `java21-offline/repository` into
  `~/.m2/repository` first (`bcpkix` and `bcutil`, 2026-10-05). `build-offline.sh`
  reads the bundle and needs nothing.
- **Maven's `-o` also blocks `file://` repositories**: building from
  `java21-offline` uses its `settings-offline.xml` as a mirror, without `-o`
  ([its README](../../java21-offline/README.md)).
- **A binary compiled here may not start on RHEL 9**: this machine's glibc is
  2.39, RHEL 9's 2.34 (§3). Native code for the release is compiled in the
  Rocky Linux 9 container (`vendor/build-nginx.sh`, with Docker), never here, and
  a release is checked in UBI 9 with nothing installed by
  `backend/scripts/check-release-el9.sh`
  ([09 §7](../detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).
- **A mutant that "survives" because no test ran.** A mutation harness that reads
  "Failures: 0" as survival calls a run in which nothing ran a survivor too: three
  engine mutants "survived" while a test file written ahead of its classes kept the
  module's tests from compiling (item 100, 2026-10-24). A harness reads the tests-run
  count, and treats none, or a compilation error, as invalid, never as a result. And it
  runs its commands under `bash`: `dev-env.sh` is bash, and `sh -c '. dev-env.sh && mvn …'`
  dies at once with "Bad substitution", which made fourteen mutants "invalid" in a row
  (item 105, 2026-10-26); Python's `subprocess.run(shell=True)` is `sh` unless told
  `executable='/bin/bash'`.
- **`pkill -f` / `pgrep -f` match the shell running them** when the pattern is in
  the command line: use the bracket trick, `pkill -f "[A]renaMain"`. And match a
  class's **full name**: `WorkerMain` alone also matches VS Code's own worker
  processes (`...WorkerMain --node-ipc`), which a stop of the stack once killed
  (2026-10-06). `pkill -f "[c]om.backend.worker.WorkerMain"`.
- **A mutation check leaves the mutant compiled**: the source put back is older than
  the mutant's class in `target/`, so Maven's incremental build keeps the class.
  Build `clean` after a mutation run, before any test, release or drill
  (2026-10-06).
- **`dotnet test` prints "Passed!" when its test host dies part-way**, counting
  only the tests that ran: read `Total tests:` at
  `--logger "console;verbosity=normal"` (`Unknown` after a crash).
- **`mvn clean install` replaces `target/*-all.jar`** under a server running from
  `target/`, which then dies with `NoClassDefFoundError`: run servers from a copy
  of the release (`RELEASE=` for the drills while a build runs).
- **The headless driver runs the build in `client/Headless/bin/Debug`**, and the
  drill script does not build it: rebuild it (`dotnet build Headless`) after any
  change to the core or the driver, or the drill runs the old code.
- **What this machine has, a minimal RHEL 9 may not**: `jlink --strip-debug` calls
  `objcopy` (binutils), present here and absent there, and a release built on a
  fresh server failed on it. Anything a build or a release runs is checked by
  `check-install-guide-el9.sh`, which builds in UBI 9 with nothing added.
- **`pkill -f <pattern>` kills the shell that runs it** when that shell's own command line holds
  the pattern, as `bash -c "pkill -f 'node server.mjs'; …"` does: the rest of the command never
  ran (exit 144, 2026-10-19). Kill by the pid kept at the start (`kill $(cat server.pid)`), or
  make the pattern one the command line cannot hold (`pkill -f '[n]ode server.mjs'`).
- **Never edit a script while it runs**: bash reads it as it goes, and an edit
  moves what it reads next. A drill edited mid-run ran its scenarios, then failed
  with a syntax error where its failover should have begun.
- **A shell variable left empty** in a backup path (`cp f $SP/x` with `SP` unset)
  writes to `/`: set paths in the same command that uses them.
- **The machine's MySQL is on CEST**, production's on UTC: anything the drills
  write by hand is stamped in UTC (`drill-sql.sh` sets the session's zone).
- **The full backend build takes about an hour**, beyond a 30-minute limit some
  tools put on a background command: run it under `nohup` and watch its log.
