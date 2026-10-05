# 3 — Build, test and package

All commands run in the `j-redis-service` directory. They assume Maven is set
up to find libraries in the `java21-offline` bundle as in
[guide 2](02-offline-repository.md), method 1, so a plain `mvn` works. With
method 2, add `-o -Dmaven.repo.local=<path>` to each `mvn` command. In
PowerShell, quote it: `"-Dmaven.repo.local=<path>"`.

## 3.1 The modules

| Module | What it contains | Main artifact |
|---|---|---|
| `j-redis-common` | RESP encoder/decoders, `Reply`, number and glob utilities, the build version | library jar |
| `j-redis-server` | The server | `j-redis-server-<v>-all.jar` (runnable, self-contained) |
| `j-redis-client` | The Java client library | library jar (applications depend on this) |
| `j-redis-embedded` | Server + client in one process, for tests and tools | library jar |
| `j-redis-cli` | Command-line client | `j-redis-cli-<v>-all.jar` |
| `j-redis-tools` | `benchmark`, `check-aof`, `dump` | `j-redis-tools-<v>-all.jar` |
| `j-redis-examples` | Runnable usage examples (tested in every build) | `j-redis-examples-<v>-all.jar` |
| `j-redis-tests` | Integration, model-based, persistence and crash tests | none (tests only) |

The `-all.jar` files are "fat" jars that bundle their dependencies, so each can
be run with `java -jar` on any machine with Java 21.

## 3.2 Build

| Goal | Command |
|---|---|
| Build everything and run the fast tests (about 3 min) | `mvn clean install` |
| Build without tests (about 30 s) | `mvn clean install -DskipTests` |
| Also run the slow tests (crash test with `kill -9`, about 1 min more) | `mvn clean install -Pfull` |
| Build one module and what it depends on | `mvn install -pl j-redis-server -am -DskipTests` |

- `install` also copies the library jars into your local Maven repository, so
  other projects (your services) can depend on `com.jredis:j-redis-client`.
- `-pl <module>` builds one module, and `-am` ("also make") builds the modules
  it needs. Without `-am`, Maven cannot find sibling modules that are not
  installed yet.

The outputs are in each module's `target/` directory, for example
`j-redis-server/target/j-redis-server-2.1.0-all.jar`.

> Do not run a server from a `target/` jar while you rebuild: `mvn clean`
> deletes the jar under the running JVM, which then fails to load classes.
> Run servers from a distribution copy (§3.5).

## 3.3 Tests

| Tier | How | What runs |
|---|---|---|
| Fast | `mvn clean install` | Unit and property tests (RESP codec, numbers, glob, `Dict`, skip list, SipHash, list, expiry registrations), command tests, 300,000-command model-based test, protocol tests over TCP, persistence tests, client-library tests (embedded and TCP), the review regression tests, and the example tests: 137 tests |
| Full | `mvn clean install -Pfull` | The above plus `CrashTest`: a server in a child JVM killed with `kill -9` five times (138 tests) |

Useful variations:

```bash
# one test class
mvn -pl j-redis-tests -am test -Dtest=ProtocolTest -Dsurefire.failIfNoSpecifiedTests=false
# one test method
mvn -pl j-redis-tests -am test -Dtest='ProtocolTest#transactions' -Dsurefire.failIfNoSpecifiedTests=false
# a longer model-based run (default 100,000 commands per seed)
mvn -pl j-redis-tests -am test -Dtest=ModelBasedTest -Djredis.model.ops=1000000 -Dsurefire.failIfNoSpecifiedTests=false
# see the server's log output during tests (default WARN)
JREDIS_TEST_LOG_LEVEL=DEBUG mvn -pl j-redis-tests test -Dtest=PersistenceTest        # Linux
set JREDIS_TEST_LOG_LEVEL=DEBUG & mvn -pl j-redis-tests test -Dtest=PersistenceTest  # Windows cmd
```

Test reports are in `<module>/target/surefire-reports/`: one `.txt` summary
per class, and `-output.txt` files with what the tests printed.

Tests open TCP ports only on `127.0.0.1` and choose free ports automatically,
so several builds can run on one machine.

## 3.4 Check the Java 21 guarantee

Every class the build produces, and every class of every dependency inside the
fat jars, must be Java 21 bytecode or older (class-file major version ≤ 65).
To check one class:

```bash
javap -v -cp j-redis-server/target/j-redis-server-2.1.0-all.jar com.jredis.server.Main | grep major
#   major version: 65
```

To check every class in a jar, the snippet below uses Python 3. Any other
language can read the same two bytes: offsets 6–7 of each `.class` file.

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
`release` also checks the *API*, so code calling a method that does not exist
in Java 21 fails at compile time rather than at run time
([guide 7 §7.5](07-upgrade-guide.md#75-upgrade-the-jdk)).

## 3.5 Package the distribution

```bash
scripts/make-dist.sh                   # Linux: builds, then assembles
scripts/make-dist.sh --skip-build      # assemble from the jars already built
```

```bat
rem Windows (PowerShell script, callable from cmd)
powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1
powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
```

Both create `target/dist/j-redis-<version>/` and an archive (`.tar.gz` and
`.zip` on Linux, `.zip` on Windows):

```
j-redis-2.1.0/
├── bin/        j-redis-server, j-redis-cli, j-redis-tools, j-redis-examples  (+ .cmd for Windows)
├── lib/        j-redis-server.jar, j-redis-cli.jar, j-redis-tools.jar, j-redis-examples.jar
├── conf/       j-redis.conf (production, Linux), j-redis-dev.conf (workstation)
├── systemd/    j-redis.service
└── README.txt
```

The sources of `bin/`, `conf/` and `systemd/` are in the project's `dist/`
directory. Edit them there, not in `target/`.

## 3.6 Troubleshooting builds

| Symptom | Cause and fix |
|---|---|
| `Could not resolve dependencies … has not been downloaded from it before` (offline) | The artifact is not in your local repository. Check the path passed in `-Dmaven.repo.local` or your `settings.xml`. For a new dependency, see [guide 2 §2.8](02-offline-repository.md#28-when-something-is-missing) and [guide 7 §7.4](07-upgrade-guide.md#74-upgrade-a-dependency). |
| `Plugin … could not be resolved` | Same cause, for a Maven plugin: the plugin versions pinned in the parent POM must be in the repository |
| `Could not find artifact com.jredis:j-redis-common…` with `-pl` | Add `-am`, or run `mvn install` once for the whole project |
| `invalid target release: 21`, or classes with major version > 65 | Maven runs on the wrong JDK. `mvn -v` must show Java 21. Fix `JAVA_HOME`. |
| `UnsupportedClassVersionError` when running | A newer-than-8 class got in (a dependency?) or the runtime is older than 8. Check with §2.4. |
| A test fails only on a loaded machine | Tests avoid timing assumptions, but a machine at 100 % CPU can stretch timeouts. Re-run the single test (§3.3). If it fails again, it is a real failure. |
| `NoClassDefFoundError` in a running server after a build | The server ran from a `target/` jar that the build replaced. Restart it from a distribution copy. |
| PowerShell: `Unknown lifecycle phase ".repo.local=…"` | Quote the `-D` argument: `"-Dmaven.repo.local=C:\…"` |
