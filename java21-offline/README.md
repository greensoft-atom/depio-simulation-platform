# Java 21 Offline Library Bundle

Everything needed to build the backend on a machine with **no internet
connection**. Every library is pre-downloaded, and every jar has been verified
to contain only Java 21 or older bytecode.

**Generated 2026-09-22.** Verified with Temurin 21.0.12.1 and Maven 3.9.16 by
running a real build from an empty local repository — see
[How this was verified](#8-how-this-was-verified).

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
| `repository/` | 245 jars + every POM, in Maven layout | **The normal case** — building with Maven or Gradle |
| `lib/` | 74 jars, flat, runtime only | Compiling by hand with `javac` / `java -cp` |
| `lib-test/` | 27 jars, flat, test and benchmark only | Added to the classpath only when running tests |
| `ARTIFACTS.txt` | Every jar, grouped by purpose, with its verified bytecode level | Checking what is available and at which version |
| `manifest.json` | The same data, machine-readable | Scripting |

Total size 244 MB. The `repository/` folder is exactly the shape of
`~/.m2/repository`, so Maven can consume it directly.

**No JDK is included in the bundle.** In this repository the JDK and Maven are
committed beside it, in [`../vendor/`](../vendor/VERSIONS.md): Temurin
21.0.12.1+1 in `vendor/jdk-21` and Maven 3.9.16 in `vendor/maven-3.9`.
`../build-offline.sh` builds the backend with them and this bundle alone: the
bundle as a mirror ([method 1](#method-1--as-a-read-only-mirror-recommended),
its path rewritten to where it is) and a local repository of its own, so
nothing is installed and nothing comes from `~/.m2`.

### Why the POM files matter

A folder of jars alone is **not** enough for an offline Maven build. Maven
reads each library's POM to learn its own dependencies, so the POMs are
included too. That is why `repository/` has far more files than jars.

---

## 2. Setup

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

On Windows the mirror URL must use forward slashes and three slashes after
`file:`:

```xml
<url>file:///C:/java21-offline/repository</url>
```

---

## 3. Three ways to use it

### Method 1 — as a read-only mirror (recommended)

Maven reads from the bundle and writes downloaded artifacts into your normal
`~/.m2/repository`. **The bundle is never modified**, so it stays pristine and
can be shared, mounted read-only, or copied between machines.

`settings-offline.xml` (shipped in this bundle, adjust the path):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>java21-offline</id>
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

> **Do not add `-o` (offline mode) with this method.** Maven's offline flag
> blocks `file://` repositories as well as remote ones, so the build fails with
> "Cannot access java21-offline in offline mode". The mirror already guarantees
> nothing leaves the machine — that is what `mirrorOf *` means.

### Method 2 — as the local repository

Simplest, but Maven **writes into the bundle**, so it stops being pristine.
Use it for a throwaway machine, not for the copy you keep.

```bash
mvn -Dmaven.repo.local=/opt/java21-offline/repository clean install
```

### Method 3 — no Maven at all

```bash
javac -cp "/opt/java21-offline/lib/*" -d out $(find src -name '*.java')
java  -cp "out:/opt/java21-offline/lib/*" com.example.Main
```

Add `lib-test/*` to the classpath only when compiling or running tests.

---

## 4. What each library is for

| Library | Version | Why it is here |
|---|---|---|
| **Netty** | 4.2.18.Final | Async networking for the arena and gateway. See [§5](#5-the-two-netty-lines). |
| netty-tcnative-boringssl-static | 2.0.84.Final | OpenSSL-backed TLS, much faster than JDK TLS. Natives for Linux, macOS and Windows, x86_64 and aarch64. |
| **JCTools** | 4.0.7 | Lock-free MPSC queues for the room input queues. |
| **Agrona** | 2.6.1 | Primitive collections, ring buffers, off-heap structures. |
| **fastutil** | 8.5.19 | Primitive collections, no boxing in hot paths. |
| **HdrHistogram** | 2.2.2 | Latency histograms that do not lie about the tail. |
| **Disruptor** | 4.0.0 | Optional: bounded ring-buffer handoff between threads. |
| **Caffeine** | 3.3.0 | In-process cache for `meta`. |
| **mysql-connector-j** | 26.7.0 | MySQL JDBC driver. |
| **HikariCP** | 7.1.0 | JDBC connection pool. |
| **Flyway** | 13.7.0 | Schema migrations (`flyway-core` + `flyway-mysql`). |
| **JGroups** | 5.5.7.Final | Cluster membership and leader election without installing a daemon. |
| **Jackson** | 2.22.3 | JSON and YAML (`databind`, `dataformat-yaml`). |
| **Gson** | 2.14.0 | Lightweight JSON where Jackson is too much. |
| **SLF4J** | 2.0.20 | Logging facade. |
| **Logback** | 1.6.3 | Logging backend, async appender. |
| **Micrometer** | 1.17.1 | Metrics, with a Prometheus-format endpoint. |
| **Bouncy Castle** | 1.86 | Argon2 password hashing and certificate handling. |
| **lz4-java / xz** | 1.8.1 / 1.12 | Compression for backups and archives. |
| **Apache Commons** | lang3 3.20.0, io 2.22.0, codec 1.22.1 | The usual utilities. |
| **JUnit** | 6.1.3 | Tests. Requires Java 17+. |
| **AssertJ / Mockito / Awaitility** | 3.27.7 / 5.23.0 / 4.3.0 | Assertions, mocks, async test conditions. |
| **JMH** | 1.37 | Microbenchmarks. |

Maven plugins are included too (compiler, surefire, failsafe, jar, shade,
assembly, source, install, deploy, clean, resources, dependency, enforcer with
`extra-enforcer-rules`). A full `clean install` needs no network.

---

## 5. The two Netty lines

The bundle carries **two** Netty versions on purpose:

| Version | Use it for |
|---|---|
| **4.2.18.Final** | All new services (`gateway`, `arena`, `meta`, `worker`). Current stable line. |
| **4.1.122.Final** | `j-redis-service`, which pins this exact version. Lets j-redis build on JDK 21 **without changing its dependencies** — one variable at a time during the migration. |

Do not mix them inside one module. Pick one version per Maven module and let
`dependencyManagement` enforce it.

Note that in Netty 4.2 the `netty-codec` artifact was split, and the main jar
is now a thin aggregator with no classes of its own — that is expected, not a
broken download. The actual codecs arrive transitively.

---

## 6. Java 21 notes that will bite you

**Netty needs `--add-opens` for its fast path.** Without it Netty logs:

```
direct buffer constructor: unavailable: Reflective setAccessible(true) disabled
```

It still works — it falls back to a supported cleaner — but you lose the fast
path. Add this to every service's launch flags:

```
--add-opens java.base/java.nio=ALL-UNNAMED
```

**Use generational ZGC** for anything with a latency budget:

```
-XX:+UseZGC -XX:+ZGenerational -Xms8g -Xmx8g
```

In JDK 21 the generational mode is opt-in via `-XX:+ZGenerational`. In JDK 23
and later it is the default and the flag is gone.

**Pin the bytecode level in your build**, so a stray dependency cannot drag in
something newer than your target:

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

This is the rule that verified this bundle, and it costs nothing to keep.

**Use `maven.compiler.release`, not `source`/`target`:**

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
</properties>
```

`release` checks against the real Java 21 API. `source`/`target` do not, and
will happily compile code that fails at runtime on an older JVM.

---

## 7. Adding a library later

This needs a machine **with** internet once; you cannot conjure a jar offline.

```bash
# On a connected machine, using this bundle as the local repository:
mvn -Dmaven.repo.local=/opt/java21-offline/repository \
    dependency:get -Dartifact=group:artifact:version -Dtransitive=true

# Then re-generate the inventory and re-verify:
python3 describe.py
```

**Prefer adding it to a real build** rather than `dependency:get`. Some POMs
(Netty 4.1's, for one) use properties supplied by build extensions, and
`dependency:get` resolves them literally and fails with messages like
`Could not find artifact ... :jar:${os.detected.classifier}`. A real
`mvn install` of a module that declares the dependency resolves it correctly.
That is also how this bundle was built.

After adding anything, re-run the verification in §8. A bundle that has not
been tested offline is a bundle that does not work offline.

---

## 8. How this was verified

| Check | Result |
|---|---|
| Real build, not a download script | A probe project referencing **every** library and executing **every** plugin was built with `mvn clean install` on Temurin 21. `dependency:go-offline` was deliberately not used — it misses plugin dependencies. |
| Every library loads | 33/33 representative classes load on Java 21, and 4 tests pass exercising Netty, Jackson, Caffeine, Gson, Mockito and Awaitility through their real APIs. |
| j-redis-service compatibility | A second probe pinning j-redis's exact versions (Netty 4.1.122, JCTools 3.3.0, SLF4J 2.0.17, Logback 1.3.15, JUnit 5.14.4) compiles and passes on JDK 21. |
| Bytecode level | `maven-enforcer-plugin` + `extra-enforcer-rules` with `maxJdkVersion 21` passed on both probes, and all 244 jars were re-checked directly by reading class-file headers. **0 jars above Java 21.** |
| Genuinely offline | Both probes rebuilt from an **empty** local repository with the bundle as the only mirror: `BUILD SUCCESS`, 5 tests passed, and **0 downloads from anywhere but the bundle**. |

Bytecode distribution across the 244 jars: 148 are Java 8, 22 are Java 9, 14
are Java 7, 13 are Java 6, 11 are Java 17, 7 are Java 11, and 9 contain no
classes at all (native-library and aggregator jars).

---

## 9. Troubleshooting

**"Cannot access java21-offline in offline mode"**
You combined `-o` with the mirror method. Drop `-o`; see [§3](#method-1--as-a-read-only-mirror-recommended).

**"Could not resolve dependencies ... was cached in the local repository"**
Your `~/.m2/repository` has a failed-download marker from an earlier attempt.
Delete the offending directory under `~/.m2/repository` and rebuild.

**"Could not find artifact ... :jar:${os.detected.classifier}"**
You used `dependency:get` on an artifact whose POM needs a build extension.
Declare it in a real module instead; see [§7](#7-adding-a-library-later).

**"class file has wrong version 65.0, should be 52.0"**
You are compiling with JDK 8 against this bundle. Use JDK 21: the project builds
with Java 21 only (Q-56).

**Checksum failures**
The profile sets `checksumPolicy ignore` for exactly this reason: checksums
copied between filesystems can mismatch harmlessly. If you see one anyway, the
jar is genuinely corrupt — re-extract the bundle.

**Netty cannot load epoll**
`netty-transport-native-epoll` is included with the `linux-x86_64` classifier
only. On another architecture, add the matching classifier from a connected
machine, or fall back to NIO.
