# Working here: the manner, the owner's standing decisions, the machine

What a person or an agent picking this work up needs before the first change: how
each unit of work is done, what the owner has decided that is not a design
choice, and the development machine's facts and traps. Written 2026-10-05 from
notes kept outside the repository until then. `background/CLAUDE.md` carries the
same rules for Claude, but the repository's `.gitignore` leaves every `CLAUDE.md`
out, so this page is the copy that travels with a clone.

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
     [glossary](../glossary.md), and a question (Q-*n*) for what is the owner's.
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
| The certificate and the domain are deferred: not raised, not worked on, until the owner brings them back | 2026-09 | [plan.md](../plan.md), its opening |
| No third-party Redis server, client library or tool, tests included: the store is j-redis | 2026-09 | this page (it was in `CLAUDE.md` only); D-6, D-7 |
| The client: the engine-free C# core, the headless driver, and the Unity layer as thin scripts; nothing is run in Unity here | 2026-10-04 | Q-51, D-73 |
| The migrations squashed to V1 (schema) and V2 (seed) once, before launch; forward-only from V3 | 2026-10-04 | D-75 |
| A result under five seconds counts for nothing but its row and a rated match's rating; a walkover is no win | 2026-10-05 | D-76 |
| The Unity editor's check and an admin panel are put off: not proposed until the owner brings them back | 2026-10-05 | Q-54, Q-55 |
| Java 21 only (no Java 8 line); MySQL 8.4 LTS; the release carries its own Java runtime, MySQL and nginx, nothing installed as an OS package | 2026-10-05 | Q-56, plan item 80 |
| The production machines run RHEL 9.x (the owner thinks 9.5) | 2026-09-22 | requirements C-1; §3 below |

## 3. The development machine

A shared Ubuntu 24.04 VM, 12 cores, with other users' jobs on it: the load
average sits around 5 to 9.

| What | Where | Notes |
|---|---|---|
| JDK 21 | `/opt/jdk21` | Temurin 21.0.12.1+1, not on PATH: `export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:/opt/maven/bin:$PATH` |
| Maven | `/opt/maven` | 3.9.16 |
| The JDK and Maven, committed | `vendor/jdk-21`, `vendor/maven-3.9` | the same versions, usable as they are; `build-offline.sh` builds with them alone, no network ([09 §3](../detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| .NET 8 SDK | `/opt/dotnet` | for `client/`; NuGet.org is reachable. Not in `vendor/`: the owner chose the backend only (D-77) |
| MySQL | 127.0.0.1:3306 | 8.0.46 from the distribution; databases `backend_dev` and `backend_test`, user `backend` / `backend-dev-password` |
| Docker | `/usr/bin/docker` | other users' containers too: name ours distinctly, remove them after |
| The internet | reachable | Maven Central, NuGet.org, GitHub, the vendors' downloads |

**Ports taken by others:** 6379 (a valkey server, not ours: start j-redis on
another port), 6381, 8082. **The drills' ports:** 6390 and 6392 (j-redis and its
replica), 8093 (platform), 8094 (gateway), 9011 (arena), 9195 to 9199 (metrics
and the admin API), 3307 to 3309 (the drills' own MySQL servers).

**This is not the production platform.** Production is RHEL 9.x, with glibc
2.34; this machine has glibc 2.39. Anything compiled here may need newer glibc
symbols (2.38's, for one) and not start there: native code for the release is
built in a Rocky Linux 9 container, and the release is checked in Red Hat's UBI 9
image, which is RHEL 9's own userspace (plan item 80).

## 4. Traps, each met at least once

- **`~/.m2` holds artifacts from two sources**, Maven Central and the
  `java21-offline` mirror, and an offline (`-o`) build trusts only those cached
  from the repository it is using. `make-release.sh` therefore runs as
  `MVN="/opt/maven/bin/mvn -Daether.enhancedLocalRepository.trackingFilename=_none" scripts/make-release.sh`.
  `build-offline.sh` keeps clear of it with a local repository of its own.
- **Maven's `-o` also blocks `file://` repositories**: building from
  `java21-offline` uses its `settings-offline.xml` as a mirror, without `-o`
  ([its README](../../java21-offline/README.md)).
- **A binary compiled here may not start on RHEL 9**: this machine's glibc is
  2.39, RHEL 9's 2.34 (§3). Native code for the release is compiled in the
  Rocky Linux 9 container (`vendor/build-nginx.sh`, with Docker), never here, and
  a release is checked in UBI 9 with nothing installed by
  `backend/scripts/check-release-el9.sh`
  ([09 §7](../detailed-design/09-release-and-packaging.md#7-how-it-is-verified)).
- **`pkill -f` / `pgrep -f` match the shell running them** when the pattern is in
  the command line: use the bracket trick, `pkill -f "[A]renaMain"`.
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
- **Never edit a script while it runs**: bash reads it as it goes, and an edit
  moves what it reads next. A drill edited mid-run ran its scenarios, then failed
  with a syntax error where its failover should have begun.
- **A shell variable left empty** in a backup path (`cp f $SP/x` with `SP` unset)
  writes to `/`: set paths in the same command that uses them.
- **The machine's MySQL is on CEST**, production's on UTC: anything the drills
  write by hand is stamped in UTC (`drill-sql.sh` sets the session's zone).
- **The full backend build takes about an hour**, beyond a 30-minute limit some
  tools put on a background command: run it under `nohup` and watch its log.
