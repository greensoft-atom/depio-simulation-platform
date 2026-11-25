# 3 — Build, test and package

This guide builds j-redis-service, runs its tests, checks that every class is
Java 21 bytecode, and packages the distribution. It is for an engineer who builds
or changes j-redis. It covers the modules, the build commands, the test tiers,
the Java 21 check, the distribution, and build problems with their fixes.

Run every command in the `j-redis-service` directory. The commands assume that
Maven finds its libraries in the `java21-offline` bundle through method 1 of
[guide 2](02-offline-repository.md), so a plain `mvn` works.

| Your Maven setup | What to add to each `mvn` command |
|---|---|
| Method 1 or method 3 of guide 2 | nothing |
| Method 2 of guide 2 | `-o -Dmaven.repo.local=<path>`; in PowerShell, quote it: `"-Dmaven.repo.local=<path>"` |
| Inside the backend's repository | nothing, in a shell that sourced `dev-env.sh` ([guide 2](02-offline-repository.md)). `./dev-jredis.sh build`, from the repository's root, runs `mvn -B -q clean install` here; with `SKIP_TESTS=1` it adds `-DskipTests`. |

## 3.1 The modules

| Module | What it contains | Main artifact |
|---|---|---|
| `j-redis-common` | RESP (REdis Serialization Protocol, the wire format) encoder and decoders, `Reply`, number and glob utilities, the build version | library jar |
| `j-redis-server` | The server (main class `com.jredis.server.Main`) | `j-redis-server-<v>-all.jar` (runnable, self-contained) |
| `j-redis-client` | The Java client library | library jar (applications depend on this) |
| `j-redis-embedded` | The real server and the client in one process, for tests and tools | library jar |
| `j-redis-cli` | Command-line client | `j-redis-cli-<v>-all.jar` |
| `j-redis-tools` | `benchmark`, `check-aof`, `dump` | `j-redis-tools-<v>-all.jar` |
| `j-redis-examples` | Runnable usage examples (tested in every build) | `j-redis-examples-<v>-all.jar` |
| `j-redis-tests` | Integration, model-based, persistence, streams, replication, regression and crash tests | none (tests only; Maven does not install it) |

`<v>` is the version in the parent `pom.xml`, now 2.2.1. The `-all.jar` files
are "fat" jars: each one bundles its dependencies. You can run each of them with
`java -jar` on any machine with Java 21.

## 3.2 Build

| Goal | Command |
|---|---|
| Build everything and run the fast tests (about 3 min) | `mvn clean install` |
| Build without tests (about 30 s) | `mvn clean install -DskipTests` |
| Also run the slow tests (the two crash tests with `kill -9`, about 1 min more) | `mvn clean install -Pfull` |
| Build one module and the modules it depends on | `mvn install -pl j-redis-server -am -DskipTests` |

- `install` also copies the library jars into your local Maven repository. Other
  projects, such as your services, can then depend on
  `com.jredis:j-redis-client`.
- `-pl <module>` builds one module. `-am` ("also make") also builds the modules
  it needs. Without `-am`, Maven cannot find sibling modules that are not
  installed yet.

The outputs are in each module's `target/` directory, for example
`j-redis-server/target/j-redis-server-2.2.1-all.jar`.

> Do not run a server from a `target/` jar while you rebuild. `mvn clean`
> deletes the jar under the running JVM (Java virtual machine), and the JVM then
> fails to load classes. Run servers from a distribution copy (§3.5).

## 3.3 Tests

The build has two test tiers. The `j-redis-tests` module tags its two crash tests
`slow`, and it leaves them out unless the `full` profile (`-Pfull`) is on.

| Tier | How | What runs |
|---|---|---|
| Fast | `mvn clean install` | Unit and property tests: RESP parsers, numbers, argument splitting, glob, `Dict`, skip list, SipHash, list, expiry, memory estimate, stream value, the replication backlog, its end mark and full-sync instant, fail-stop effects, and the client's `XADD` arguments. Command tests, the 300,000-command model-based test, protocol tests over TCP, persistence tests, client-library tests (embedded and TCP), streams and consumer groups, replication between real servers, a client following a promoted primary, the review regression tests, and the example tests: **227 tests** |
| Full | `mvn clean install -Pfull` | All of the above, plus the two crash tests, **229 tests**. `CrashTest` kills a server in a child JVM with `kill -9` five times. `ReplicationCrashTest` kills a primary with `kill -9` under a workload and promotes its replica. |

The 229 tests are: 21 in `j-redis-common`, 35 in `j-redis-server`, 1 in
`j-redis-client`, 5 in `j-redis-examples` and 167 in `j-redis-tests`.

Useful variations:

```bash
# one test class
mvn -pl j-redis-tests -am test -Dtest=ProtocolTest -Dsurefire.failIfNoSpecifiedTests=false
# one test method
mvn -pl j-redis-tests -am test -Dtest='ProtocolTest#transactions' -Dsurefire.failIfNoSpecifiedTests=false
# a longer model-based run (default 100,000 commands per seed, 3 seeds)
mvn -pl j-redis-tests -am test -Dtest=ModelBasedTest -Djredis.model.ops=1000000 -Dsurefire.failIfNoSpecifiedTests=false
# see the server's log output during tests (default WARN)
JREDIS_TEST_LOG_LEVEL=DEBUG mvn -pl j-redis-tests test -Dtest=PersistenceTest        # Linux
set JREDIS_TEST_LOG_LEVEL=DEBUG & mvn -pl j-redis-tests test -Dtest=PersistenceTest  # Windows cmd
```

| Option | What it does |
|---|---|
| `-Dtest=<Class>` or `-Dtest='<Class>#<method>'` | Runs one test class or one test method |
| `-Dsurefire.failIfNoSpecifiedTests=false` | Needed with `-am`: the other modules have no test of that name, and they must not fail for it |
| `-Djredis.model.ops=N` | Commands per seed in `ModelBasedTest` (default 100,000; the test runs seeds 1, 2 and 3) |
| `JREDIS_TEST_LOG_LEVEL` | The log level in the `j-redis-tests` tests, the server's included (default `WARN`), read by that module's `logback-test.xml` |

The last two commands have no `-am`, so they need the other modules installed
first (one `mvn install` of the whole project).

Test reports are in `<module>/target/surefire-reports/`. There is one `.txt`
summary per class, and `-output.txt` files with what the tests printed.

The tests open TCP ports only on `127.0.0.1`, and they choose free ports
automatically. Several builds can therefore run on one machine.

## 3.4 Check the Java 21 guarantee

Every class the build produces must be Java 21 bytecode or older (class-file
major version ≤ 65). The same applies to every class of every dependency inside
the fat jars. To check one class:

```bash
javap -v -cp j-redis-server/target/j-redis-server-2.2.1-all.jar com.jredis.server.Main | grep major
#   major version: 65
```

To check every class in every jar, use the snippet below. It needs Python 3.
Any other language can read the same two bytes: offsets 6–7 of each `.class`
file. The snippet skips `META-INF/versions/`, the overlays of a multi-release
jar, which load only on a newer JDK.

```bash
python3 - j-redis-*/target/*.jar <<'PY'
import sys, zipfile, struct
for jar in sys.argv[1:]:
    z = zipfile.ZipFile(jar)
    bad = [n for n in z.namelist() if n.endswith('.class') and not n.startswith('META-INF/versions/')
           and struct.unpack('>H', z.read(n)[6:8])[0] > 65]
    print(jar, 'OK' if not bad else 'NEWER THAN JAVA 21: %s' % bad[:3])
PY
```

The parent POM sets `maven.compiler.release 21`. Unlike `source`/`target`,
`release` also checks the *API*. Code that calls a method missing from Java 21
therefore fails at compile time, not at run time
([guide 7 §7.5](07-upgrade-guide.md#75-upgrade-the-jdk)).

## 3.5 Package the distribution

The scripts below assemble the distribution: the launchers, the fat jars, the
sample configurations and the systemd unit, in one directory and one archive.

```bash
scripts/make-dist.sh                   # Linux: builds, then assembles
scripts/make-dist.sh --skip-build      # assemble from the jars already built
```

```bat
rem Windows (PowerShell script, callable from cmd)
powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1
powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
```

**The scripts build in Maven's offline mode.** Without `--skip-build` (or
`-SkipBuild`), they run `mvn -B -o clean install`. Offline mode also blocks the
`file://` mirror of method 1, so Maven must find everything in the local
repository already. Choose one of these:

1. Run `mvn clean install` first (method 1 then fills the local repository), and
   assemble with `--skip-build`. This is the README's quick start.
2. Point the script at a copy of the bundle's repository (method 2), with the
   setting in the table below.

| Script | Setting | What it does |
|---|---|---|
| `make-dist.sh` | `--skip-build` | Assembles from the jars already built; no Maven run |
| `make-dist.sh` | `MAVEN_REPO=<path>` (environment) | Adds `-Dmaven.repo.local=<path>` to the build |
| `make-dist.sh` | `MVN=<command>` (environment) | The Maven command (default `mvn`) |
| `make-dist.ps1` | `-SkipBuild` | Assembles from the jars already built; no Maven run |
| `make-dist.ps1` | `-MavenRepo <path>` | Adds `-Dmaven.repo.local=<path>` to the build |

Both scripts create `target/dist/j-redis-<version>/` and an archive.
`make-dist.sh` always writes a `.tar.gz`, and also a `.zip` when the JDK's `jar`
command is on the `PATH`. `make-dist.ps1` writes a `.zip`.

```
j-redis-2.2.1/
├── bin/        j-redis-server, j-redis-cli, j-redis-tools, j-redis-examples  (+ .cmd for Windows)
├── lib/        j-redis-server.jar, j-redis-cli.jar, j-redis-tools.jar, j-redis-examples.jar
├── conf/       j-redis.conf (production, Linux), j-redis-dev.conf (workstation)
├── systemd/    j-redis.service
└── README.txt
```

The scripts copy `bin/`, `conf/`, `systemd/` and `README.txt` from the project's
`dist/` directory. Edit them there, not in `target/`. The four jars in `lib/` are
the modules' `-all.jar` files, renamed without the version.

## 3.6 Troubleshooting builds

| Symptom | Cause and fix |
|---|---|
| `Could not resolve dependencies … has not been downloaded from it before` (offline) | The artifact is not in your local repository. Check the path passed in `-Dmaven.repo.local`, or your `settings.xml`. For a new dependency, see [guide 2 §2.8](02-offline-repository.md#28-when-something-is-missing) and [guide 7 §7.4](07-upgrade-guide.md#74-upgrade-a-dependency). The same error from `make-dist.sh` without `--skip-build` means its offline build found the local repository empty; see §3.5. |
| `Plugin … could not be resolved` | The same cause, for a Maven plugin. The parent POM pins every plugin version, and each of those versions must be in the repository. |
| `Could not find artifact com.jredis:j-redis-common…` with `-pl` | Add `-am`, or run `mvn install` once for the whole project. |
| `invalid target release: 21`, or classes with major version > 65 | Maven runs on the wrong JDK. `mvn -v` must show Java 21. Fix `JAVA_HOME`. |
| `UnsupportedClassVersionError` when running | A class newer than Java 21 got in (from a dependency?), or the runtime is older than Java 21. Check the jars with §3.4, and the runtime with `java -version`. |
| A test fails only on a loaded machine | The tests avoid timing assumptions, but a machine at 100 % CPU can stretch timeouts. Run the single test again (§3.3). If it fails again, it is a real failure. |
| `NoClassDefFoundError` in a running server after a build | The server ran from a `target/` jar that the build replaced. Restart it from a distribution copy. |
| PowerShell: `Unknown lifecycle phase ".repo.local=…"` | Quote the `-D` argument: `"-Dmaven.repo.local=C:\…"`. |
