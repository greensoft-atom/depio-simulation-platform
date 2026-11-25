# Java 21 Offline Library Bundle

This bundle holds every Java library and Maven plugin needed to build the backend on a machine with **no internet
connection**. Every library was downloaded in advance, and every jar was checked to contain only Java 21 or older
bytecode. The backend's build and j-redis's build use it as their only Maven repository. This page says what is in
the bundle, how to use it, what each library is for, how it was verified, and how to fix the usual problems.

**Generated 2026-09-22.** It was verified with Temurin 21.0.12.1 and Maven 3.9.16, by a real build from an empty
local repository: see [How this was verified](#8-how-this-was-verified).

> **In this repository, skip [§2 Setup](#2-setup).** Nothing is extracted to `/opt`, and nothing goes in `~/.m2`.
> `../dev-env.sh` writes Maven's settings for the bundle where it is: `work/settings.xml`, which is method 1 below,
> with `work/m2` as the local repository. `../build-offline.sh` and VS Code read those settings
> ([build and run](../docs/development/03-build-and-run.md),
> [D-80](../docs/architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)).
> The paths on this page, `/opt/java21-offline` and `C:\java21-offline`, are for the bundle when you carry it
> alone to another project.

---

## Contents

1. [What is in the box](#1-what-is-in-the-box)
2. [Setup](#2-setup)
3. [Three ways to use it](#3-three-ways-to-use-it)
4. [What each library is for](#4-what-each-library-is-for)
5. [The two Netty lines](#5-the-two-netty-lines)
6. [Java 21 notes that will bite you](#6-java-21-notes-that-will-bite-you)
7. [Adding a library later](#7-adding-a-library-later)
8. [How this was verified](#8-how-this-was-verified)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. What is in the box

| Folder / file | What it is | When you use it |
|---|---|---|
| `repository/` | 300 jar files and every POM, in Maven's layout (see below) | **The normal case**: building with Maven or Gradle |
| `lib/` | 74 jars in one flat folder, for runtime only | Compiling by hand with `javac` and running with `java -cp` |
| `lib-test/` | 27 jars in one flat folder, for tests and benchmarks only | Added to the classpath only when you run tests |
| `ARTIFACTS.txt` | The inventory: every jar, grouped by purpose, with its verified bytecode level | Checking what is there, and at which version |
| `manifest.json` | The same inventory, in a form a program can read | Scripts |
| `describe.py` | Writes `ARTIFACTS.txt` and `manifest.json` from `repository/` | After adding a library (§7) |
| `settings-offline.xml` | Maven settings that use the bundle as a read-only mirror | Method 1 (§3) |

**What `repository/` holds.** The 300 jar files are:
- the 244 jars of the inventory made on 2026-09-22, which `manifest.json` lists;
- `netty-codec-http` 4.2.18.Final, added on 2026-09-23 for the gateway's lobby connection. `ARTIFACTS.txt` lists it
  (so it counts 245 jars), and `manifest.json` does not;
- 55 jars added on 2026-09-23 for j-redis's own build: the Maven plugins at the versions j-redis pins (for example
  `maven-compiler-plugin` 3.13.0 and `maven-surefire-plugin` 3.2.5) and the libraries those plugins need. Neither
  inventory lists them. A read of their class files on 2026-11-25 found nothing above Java 9.

The whole bundle holds about 250 MB of files (measured on 2026-11-25): `repository/` 167 MB, `lib/` 65 MB and
`lib-test/` 18 MB. `repository/` has exactly the shape of `~/.m2/repository`, so Maven can read it directly.

**The bundle has no JDK.** In this repository the JDK and Maven are committed beside it, in
[`../vendor/`](../vendor/VERSIONS.md): Temurin 21.0.12.1+1 in `vendor/jdk-21`, and Maven 3.9.16 in
`vendor/maven-3.9`. `../build-offline.sh` builds the backend with them and with this bundle alone. It uses the bundle
as a mirror ([method 1](#method-1--as-a-read-only-mirror-recommended), with the path changed to where the bundle is)
and a local repository of its own. So nothing is installed, and nothing comes from `~/.m2`.

### Why the POM files matter

A folder of jars alone is **not** enough for an offline Maven build. Maven reads each library's POM to learn that
library's own dependencies. So the bundle includes the POMs too, and that is why `repository/` has many more files
than jars.

---

## 2. Setup

Do this only when you use the bundle outside this repository.

### Linux

```bash
# 1. Put the bundle somewhere permanent
sudo tar -xzf java21-offline.tar.gz -C /opt

# 2. Point Maven at it (see §3 for which method to choose)
mkdir -p ~/.m2
cp /opt/java21-offline/settings-offline.xml ~/.m2/settings.xml
#    ... or pass -s explicitly on every build
```

### Windows

```powershell
# 1. Extract to a path with no spaces
Expand-Archive java21-offline.zip -DestinationPath C:\java21-offline

# 2. Copy the settings file and fix the path inside it
New-Item -ItemType Directory -Force "$env:USERPROFILE\.m2"
Copy-Item C:\java21-offline\settings-offline.xml "$env:USERPROFILE\.m2\settings.xml"
```

On Windows the mirror's URL must use forward slashes, and three slashes after `file:`:

```xml
<url>file:///C:/java21-offline/repository</url>
```

---

## 3. Three ways to use it

### Method 1 — as a read-only mirror (recommended)

Maven reads from the bundle and writes what it resolves into your normal local repository, `~/.m2/repository`.
**The bundle itself is never changed.** So it stays clean, and you can share it, mount it read-only, or copy it
between machines.

`settings-offline.xml` ships in the bundle. Change its three URLs to where you put the bundle. Without its opening
comment, it reads:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>java21-offline</id>
      <name>java21-offline bundle</name>
      <url>file:///opt/java21-offline/repository</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>offline-bundle</id>
      <repositories>
        <repository>
          <id>java21-offline</id>
          <url>file:///opt/java21-offline/repository</url>
          <releases><enabled>true</enabled><checksumPolicy>ignore</checksumPolicy></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>java21-offline</id>
          <url>file:///opt/java21-offline/repository</url>
          <releases><enabled>true</enabled><checksumPolicy>ignore</checksumPolicy></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>offline-bundle</activeProfile></activeProfiles>
</settings>
```

```bash
mvn -s ~/.m2/settings.xml clean install
```

> **Do not add `-o` (offline mode) with this method.** Maven's offline flag blocks `file://` repositories as well
> as remote ones, so the build fails with "Cannot access java21-offline in offline mode". The mirror already makes
> sure that nothing is fetched from outside the machine: that is what `mirrorOf *` means.

### Method 2 — as the local repository

This is the simplest method, but Maven **writes into the bundle**, so the bundle no longer stays clean. Use it on a
throwaway machine, not on the copy you keep.

```bash
mvn -Dmaven.repo.local=/opt/java21-offline/repository clean install
```

### Method 3 — no Maven at all

```bash
javac -cp "/opt/java21-offline/lib/*" -d out $(find src -name '*.java')
java  -cp "out:/opt/java21-offline/lib/*" com.example.Main
```

Add `lib-test/*` to the classpath only when you compile or run tests.

---

## 4. What each library is for

"Why it is here" is the reason the library was put in the bundle. "Used today" names the modules whose POM
declares it, read from the POMs on 2026-11-25: the backend's modules, and j-redis (`j-redis-service/`) where it
pins another version. "none" means no module declares it; it may still arrive as another library's dependency.

| Library | Version | Why it is here | Used today |
|---|---|---|---|
| **Netty** | 4.2.18.Final | Asynchronous networking. See [§5](#5-the-two-netty-lines). | arena, gateway, tools; j-redis pins 4.1.122.Final |
| netty-tcnative-boringssl-static | 2.0.84.Final | TLS backed by BoringSSL (Google's fork of OpenSSL), much faster than the JDK's TLS. Native libraries for Linux, macOS and Windows, on x86_64, and for Linux and macOS on aarch64. | none: the arena's TLS uses the JDK's provider (`ArenaTls`) |
| **JCTools** | 4.0.7 | Lock-free MPSC queues (many producers, one consumer) for the rooms' input queues. | arena; j-redis pins 3.3.0 |
| **Agrona** | 2.6.1 | Primitive collections, ring buffers, off-heap structures. | none |
| **fastutil** | 8.5.19 | Primitive collections, with no boxing in hot paths. | none |
| **HdrHistogram** | 2.2.2 | Latency histograms that report the tail correctly. | common, tools; j-redis |
| **Disruptor** | 4.0.0 | Optional: a bounded ring buffer to hand work between threads. | none |
| **Caffeine** | 3.3.0 | An in-process cache, meant for the platform. | none |
| **mysql-connector-j** | 26.7.0 | The MySQL JDBC driver. | persistence |
| **HikariCP** | 7.1.0 | The JDBC connection pool. | persistence |
| **Flyway** | 13.7.0 | Schema migrations (`flyway-core` and `flyway-mysql`). | persistence |
| **JGroups** | 5.5.7.Final | Cluster membership and leader election, without installing a daemon. | none |
| **Jackson** | 2.22.3 | JSON and YAML (`databind`, `dataformat-yaml`). | gateway, handoff, platform, worker, tools (`databind` only) |
| **Gson** | 2.14.0 | Lightweight JSON, where Jackson is too much. | none |
| **SLF4J** | 2.0.20 | The logging facade. | common, arena, gateway, handoff, persistence, platform, worker; j-redis pins 2.0.17 |
| **Logback** | 1.6.3 | The logging backend, with its asynchronous appender. | common (optional), arena, gateway, handoff, persistence, platform, worker, tools; j-redis pins 1.3.15 |
| **Micrometer** | 1.17.1 | Metrics, with an endpoint in the Prometheus format. | none: `common`'s `Metrics` writes the format itself |
| **Bouncy Castle** | 1.86 | Argon2 password hashing and certificate handling. | platform (Argon2, `PasswordHasher`), crypto-examples |
| **lz4-java / xz** | 1.8.1 / 1.12 | Compression for backups and archives. | none |
| **Apache Commons** | lang3 3.20.0, io 2.22.0, codec 1.22.1 | The usual utilities. | none |
| **JUnit** | 6.1.3 | Tests. Needs Java 17 or later. | every module with tests; j-redis pins 5.14.4 |
| **AssertJ / Mockito / Awaitility** | 3.27.7 / 5.23.0 / 4.3.0 | Assertions, mocks, and conditions for asynchronous tests. | AssertJ in every module with tests; Mockito and Awaitility none |
| **JMH** | 1.37 | Microbenchmarks. | none |

The bundle also includes the Maven plugins: compiler, surefire, failsafe, jar, shade, assembly, source, install,
deploy, clean, resources, dependency, and enforcer with `extra-enforcer-rules`. A full `clean install` needs no
network.

---

## 5. The two Netty lines

The bundle carries **two** Netty versions on purpose:

| Version | Use it for |
|---|---|
| **4.2.18.Final** | The backend's processes (`arena`, `gateway`, `platform`, `worker`); `backend/pom.xml` pins it. The arena and the gateway use Netty directly; the platform and the worker only through the j-redis client. The current stable line. |
| **4.1.122.Final** | `j-redis-service`, which pins this exact version. So j-redis builds on JDK 21 **without changing its dependencies**: one variable at a time during the migration. |

Do not mix them inside one module. Choose one version for each Maven module, and let `dependencyManagement`
enforce it.

In Netty 4.2 the `netty-codec` artifact was split. Its main jar is now a thin aggregator with no classes of its
own. That is expected, not a broken download: the actual codecs arrive as its dependencies.

---

## 6. Java 21 notes that will bite you

**Netty needs `--add-opens` for its fast path.** Without it, Netty logs:

```
direct buffer constructor: unavailable: Reflective setAccessible(true) disabled
```

Netty still works: it falls back to a supported cleaner, but it loses the fast path. Add this to every service's
launch flags:

```
--add-opens java.base/java.nio=ALL-UNNAMED
```

The backend's systemd units already pass it (`backend/deploy/systemd/`).

**Use generational ZGC** for anything with a latency budget, for example:

```
-XX:+UseZGC -XX:+ZGenerational -Xms8g -Xmx8g
```

In JDK 21 the generational mode is opt-in, with `-XX:+ZGenerational`. From JDK 23 it is the default and the flag is
deprecated; from JDK 24 the flag is obsolete, and the JVM ignores it with a warning. The backend's units, as shipped,
pass `-XX:+UseZGC -XX:+ZGenerational`, each with its own heap size (the arena 6 GB, the platform and the gateway
3 GB, a worker 1.5 GB).

**Pin the bytecode level in your build**, so that a stray dependency cannot bring in bytecode newer than your
target:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.6.3</version>
  <dependencies>
    <dependency>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>extra-enforcer-rules</artifactId>
      <version>1.12.1</version>
    </dependency>
  </dependencies>
  <executions><execution><id>enforce-java21</id><phase>verify</phase>
    <goals><goal>enforce</goal></goals>
    <configuration><rules>
      <requireJavaVersion><version>[21,)</version></requireJavaVersion>
      <enforceBytecodeVersion><maxJdkVersion>21</maxJdkVersion></enforceBytecodeVersion>
    </rules></configuration>
  </execution></executions>
</plugin>
```

This is the rule that verified this bundle, and it costs nothing to keep. The backend's and j-redis's POMs do not
use it today; they set `maven.compiler.release` to 21.

**Use `maven.compiler.release`, not `source` and `target`:**

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
</properties>
```

`release` checks your code against the real Java 21 API. `source` and `target` do not: they will compile code that
then fails at runtime on an older JVM.

---

## 7. Adding a library later

You need a machine **with** an internet connection once: a jar cannot be made offline.

```bash
# On a connected machine, using this bundle as the local repository:
mvn -Dmaven.repo.local=/opt/java21-offline/repository \
    dependency:get -Dartifact=group:artifact:version -Dtransitive=true

# Then re-generate the inventory and re-verify:
python3 describe.py
```

**Prefer adding it through a real build** rather than `dependency:get`. Some POMs (Netty 4.1's, for one) use
properties that build extensions supply. `dependency:get` reads those properties literally and fails with messages
like `Could not find artifact ... :jar:${os.detected.classifier}`. A real `mvn install` of a module that declares the
dependency resolves it correctly. That is also how this bundle was built.

After adding anything, run the verification of §8 again. A bundle that has not been tested offline does not work
offline.

---

## 8. How this was verified

These checks ran on 2026-09-22, on the 244 jars of the inventory:

| Check | Result |
|---|---|
| Real build, not a download script | A probe project that references **every** library and runs **every** plugin was built with `mvn clean install` on Temurin 21. `dependency:go-offline` was not used on purpose: it misses plugins' dependencies. |
| Every library loads | 33 of 33 representative classes load on Java 21. 4 tests pass that use Netty, Jackson, Caffeine, Gson, Mockito and Awaitility through their real APIs. |
| j-redis-service compatibility | A second probe, pinning j-redis's exact versions (Netty 4.1.122, JCTools 3.3.0, SLF4J 2.0.17, Logback 1.3.15, JUnit 5.14.4), compiles and passes on JDK 21. |
| Bytecode level | `maven-enforcer-plugin` with `extra-enforcer-rules` and `maxJdkVersion 21` passed on both probes. All 244 jars were then checked again directly, by reading their class-file headers. **0 jars above Java 21.** |
| Really offline | Both probes were rebuilt from an **empty** local repository, with the bundle as the only mirror: `BUILD SUCCESS`, 5 tests passed, and **0 downloads from anywhere but the bundle**. |

The bytecode levels of the 244 jars (from `manifest.json`): 148 are Java 8, 22 Java 9, 17 Java 5, 14 Java 7,
13 Java 6, 11 Java 17, 7 Java 11, and one each Java 1.2, 1.3 and 1.4. 9 contain no classes at all: they are
native-library jars and aggregator jars.

The 56 jars added on 2026-09-23 (§1) were not part of these checks. Since then, the backend's and j-redis's
builds have used the bundle as their only repository.

---

## 9. Troubleshooting

**"Cannot access java21-offline in offline mode"**
You combined `-o` with the mirror method. Drop `-o`; see [§3](#method-1--as-a-read-only-mirror-recommended).

**"Could not resolve dependencies ... was cached in the local repository"**
Your `~/.m2/repository` holds a marker of a failed download from an earlier attempt. Delete that artifact's
directory under `~/.m2/repository` and build again.

**"Could not find artifact ... :jar:${os.detected.classifier}"**
You used `dependency:get` on an artifact whose POM needs a build extension. Declare it in a real module instead;
see [§7](#7-adding-a-library-later).

**"class file has wrong version 65.0, should be 52.0"**
You are compiling with JDK 8 against this bundle. Use JDK 21: the project builds with Java 21 only (Q-56).

**Checksum failures**
The profile sets `checksumPolicy ignore` for exactly this reason: checksums copied between file systems can differ
without harm. If you still see one, the jar really is damaged: extract the bundle again.

**Netty cannot load epoll**
The bundle has `netty-transport-native-epoll` only with the `linux-x86_64` classifier. On another architecture, add
the matching classifier from a connected machine, or fall back to NIO.
