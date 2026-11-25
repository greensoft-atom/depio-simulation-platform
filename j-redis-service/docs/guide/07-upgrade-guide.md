# 7 — Upgrade

This guide is for operators who run j-redis and for developers who release it.
It covers four kinds of upgrade: a running installation to a new j-redis
version, the project's own version number, a dependency, and the JDK (the Java
Development Kit). It also explains which data formats must stay compatible, and
ends with a release checklist.

> **Inside the backend's site**, j-redis comes inside the backend's release
> (`/opt/backend/jredis/`, run by the units `backend-store@session` and
> `backend-store@events`). A new j-redis version is a new backend release, and
> the operator rolls it out as
> [operations/01 §5](../../../docs/operations/01-deploy.md#5-rolling-deployment)
> says, j-redis only in a maintenance window. This guide's procedure in §7.2 is
> for j-redis installed on its own. Its rules on formats (§7.3) and on which to
> upgrade first, the server or the clients (§7.2), apply in both cases.

## 7.1 Versions

Versions are `MAJOR.MINOR.PATCH`. The part that changes tells you what an
upgrade can break:

| Part | Changes when | Data directory | Clients |
|---|---|---|---|
| PATCH (1.0.**1**) | bug fixes only | read and written unchanged | unchanged |
| MINOR (1.**1**.0) | new commands, directives or features | the new version reads older data; the old version may not read data written by the new one (§7.3) | old clients keep working |
| MAJOR (**2**.0.0) | incompatible changes | read the release notes; a migration may be needed | read the release notes |

Every release is listed in [../../CHANGELOG.md](../../CHANGELOG.md). The
CHANGELOG always marks a changed file format with **Format**. The current
version is 2.2.2.

To see the running version, use one of these:

| Command | Shows |
|---|---|
| `j-redis-server --version` | the version of the installed program |
| `INFO server` | the running server's version, in the field `jredis_version` |
| `CLIENT LIST` | each connected client's library version, in the field `lib-ver` |

### What each release since 2.0.0 asks of an upgrade

The general procedure in §7.2 covers every release. Some releases add a step or
change the way back. This table sums up the CHANGELOG:

| Release | Kind | Data | Going back | Extra steps |
|---|---|---|---|---|
| 2.0.0 (2026-09-23) | MAJOR: Java 21 | reads 1.0.0 data unchanged | to 1.0.0: swap the link and put the old unit back (§7.1b) | §7.1b: JDK 21, a new systemd unit, a higher `maxmemory` |
| 2.1.0 (2026-09-29) | MINOR: streams | **Format**: the base file moves to version 2 | to 2.0 only from the backup taken before the upgrade (§7.3) | none; 2.0 clients keep working |
| 2.2.0 (2026-09-29) | MINOR: replication | one new file, `replication` | to 2.1 with no backup; first remove the 2.2 directives (§7.3) | to use replication: 2.2 on the primary and the replica (§7.2) |
| 2.2.1 (2026-10-01) | PATCH: every connection of a client follows the primary | unchanged | swap the link back | none; the client gains `pubsubPingMillis` (5 s by default, 0 turns the pings off) |
| 2.2.2 (2026-11-25) | PATCH: `HELLO` reports the server's role | unchanged | swap the link back | none |

## 7.1b Upgrading 1.0.0 → 2.0.0 (the Java 21 move)

Read this section before the general procedure below. 2.0.0 reads a 1.0.0 data
directory unchanged. But three things around it must change, and two of them
stop the server from starting if you miss them.

**1. Install JDK 21 and point the unit at it.** The jar is Java 21 bytecode. A
JDK 8 JVM (Java virtual machine) refuses it with
`UnsupportedClassVersionError`.

```bash
sudo dnf install java-21-openjdk-devel      # RHEL 9; Java 8 is not packaged there
readlink -f "$(which java)"                 # confirm the path used in ExecStart
```

**2. Replace the systemd unit — the 1.0.0 one cannot start a JDK 21 JVM.** The
1.0.0 unit passes `-Xloggc`, `-XX:+PrintGCDetails`, `-XX:+PrintGCDateStamps` and
`-XX:+UseGCLogFileRotation`. JDK 9 and later removed all four. The JVM exits at
once with `Unrecognized VM option 'PrintGCDateStamps'`, and `systemctl status`
shows a start failure with no j-redis log at all. Copy the new unit, then apply
your local edits to it again (heap size, `taskset` range, configuration path):

```bash
diff /opt/j-redis-2.0.0/systemd/j-redis.service /etc/systemd/system/j-redis.service
sudo cp /opt/j-redis-2.0.0/systemd/j-redis.service /etc/systemd/system/
sudo systemctl daemon-reload
```

**3. Raise `maxmemory` by about a third.** This step does not fail at once; it
fails later. The default garbage collector is now generational ZGC (the Z
Garbage Collector). ZGC turns compressed oops (32-bit object references) off,
so every reference inside a stored object grows from 4 to 8 bytes. The *same*
dataset estimates larger:

| | 1.0.0 (G1, compressed oops) | 2.0.0 (ZGC) | change |
|---|---|---|---|
| per key | 64 B | 80 B | +25 % |
| per hash field | 40 B | 56 B | +40 % |
| per set member | 32 B | 48 B | +50 % |
| per sorted-set member | 40 B | 56 B | +40 % |

A dataset that estimated 3 GB under 1.0.0 estimates roughly 3.8–4.2 GB here,
depending on the mix of types. If `maxmemory` stays at its old value, writes
begin to fail with `-OOM` on a dataset that used to fit. After the upgrade,
check the real number and set the limit from it:

```bash
bin/j-redis-cli -a "$SECRET" info memory | grep -E 'used_memory|maxmemory'
```

The heap sizing rule keeps its shape: keep the estimate under about 40 % of the
heap ([../06 §6](../06-expiry-and-memory.md#6-jvm-heap-sizing)). But the
estimate itself is bigger, so the heap usually has to grow too.

**Rollback** is a symlink swap plus putting the old unit back, because the data
format did not change. 1.0.0 can read everything that 2.0.0 writes.

## 7.2 Upgrade an installation

### Linux (systemd)

The layout from [guide 4 §4.4](04-run-and-operate.md#44-production-on-linux-systemd)
keeps each version in its own directory, with a symlink `/opt/j-redis` to the
current one. Switching back and forth is therefore quick. In the commands
below, `1.1.0` stands for the version you install:

```bash
# 0. read CHANGELOG.md for the new version: new directives? format changes?

# 1. back up the data (guide 4 §4.8)
sudo -u jredis /usr/local/bin/backup-j-redis.sh /var/backups/j-redis

# 2. unpack the new version next to the old one
sudo tar -xzf j-redis-1.1.0.tar.gz -C /opt

# 3. compare the shipped config and unit with yours, and apply new settings you want
diff /opt/j-redis-1.1.0/conf/j-redis.conf /etc/j-redis/j-redis.conf
diff /opt/j-redis-1.1.0/systemd/j-redis.service /etc/systemd/system/j-redis.service

# 4. switch (a clean stop: nothing is lost)
sudo systemctl stop j-redis
sudo ln -sfn /opt/j-redis-1.1.0 /opt/j-redis
sudo systemctl start j-redis

# 5. verify
/opt/j-redis/bin/j-redis-cli -a "$SECRET" info server | grep jredis_version
/opt/j-redis/bin/j-redis-cli -a "$SECRET" dbsize
journalctl -u j-redis --since "5 min ago"
```

**What clients see.** Their connection drops, and they reconnect by
themselves, typically within a few seconds. Requests in flight at that moment
fail with `JRedisConnectionException`.

**Rollback.** If the release notes say that the data formats did not change
(always true for a PATCH release), stop the server, point the symlink back, and
start it. Otherwise, restore the backup from step 1 into `/var/lib/j-redis`
before you start the old version. The writes made since the upgrade are then
lost.

### Windows (development)

1. Stop the server (Ctrl+C).
2. Unpack the new distribution into a new folder.
3. Copy your `conf` changes over.
4. Start it with the same `dir`.

### Clients and server: which first?

**Upgrade the server first** when a new client version, or new application
code, sends a command that only the new server has. The protocol (RESP2, the
Redis Serialization Protocol version 2) does not change within a major
version. So any 2.x client works with any 2.x server, and any 1.x client with
any 1.x server, **as long as the server knows every command the client
sends**. 1.0.0 clients also keep working with 2.0.0 servers. Update the
`j-redis-client` version in your services' POMs as usual.

Two examples of commands only a newer server knows:

- A client that uses streams (`XADD`, `XREADGROUP` …) needs a 2.1 server or
  later.
- A 2.2 client given several addresses (`addresses(...)`) asks each server
  `ROLE` to find the primary, and only a 2.2 server knows `ROLE`. With one
  address it does not ask.

**Replication (2.2)** needs 2.2 on the primary and on the replica: a 2.1
server knows no `PSYNC`. Upgrade both, then add `replicaof` to the replica's
configuration.

## 7.3 Data format compatibility

The server persists three formats. Each is versioned or self-describing:

| File | Format | Version check |
|---|---|---|
| `manifest` | text, `format 1` line | a newer format is refused: `unsupported manifest format N (this server reads format 1)` |
| `base.N.jrdb` | binary, header `JRDB` + version (2 from 2.1, which added streams; 1 before) + CRC32 trailer | a newer version is refused: `has format version N; this server reads up to 2` |
| `incr.N.aof` | RESP commands (the logged effects) | an older server fails to replay a command it does not know |

In both refusal cases the server does not start (exit code 3) and changes
nothing, so the operator can go back.

**Rules for developers** who change a format (see also
[guide 6 §6.7](06-developer-guide.md#67-before-you-hand-in-a-change)):

1. Raise a format version only together with code that still **reads the old
   version**. Within a major version, never drop the reading of an older
   format.
2. A new command should log effects that older servers understand, as the
   worked example in guide 6 does (`J.HPOP` logs `HDEL`). Where that is
   impossible, it is a format change. Note it in the CHANGELOG, because a
   rollback then needs the backup.
3. Add a test that loads data written in the old format, and keep a small
   sample data directory in the test resources.

**2.2 adds one file**, `replication`, which holds the epoch (the number that
each promotion raises, 16 §9). The base and AOF (append-only file) formats are
unchanged, and a 2.1 server leaves the file alone. So going back from 2.2 to
2.1 needs no backup. First remove `replicaof` and the other 2.2 directives from
the configuration, because 2.1 does not know them.

**2.1 raised the base to version 2** for streams. It still reads version 1
(rule 1). The stream commands are new to the AOF, so going back to 2.0 needs
the backup (rule 2, in the CHANGELOG). `PersistenceTest.aVersionOneBaseStillLoads`
loads a version 1 base (rule 3). The test builds that base byte by byte instead
of keeping it as a resource, since the format fits in a dozen lines.

## 7.4 Upgrade a dependency

All versions live in the parent `pom.xml`: in the `<properties>` section and
`<dependencyManagement>`, and the plugin versions in `<pluginManagement>`.

1. **Check that the new version runs on Java 21.** Since 2.0.0 this is rarely
   a constraint. The dependencies are still on their Java 8 lines, so every
   newer line is available if you want it:

   | Library | Pinned now | Newer lines |
   |---|---|---|
   | Logback | 1.3.x | 1.5.x needs Java 11+ — fine here |
   | JUnit | 5.14.x | 6.x needs Java 17+ — fine here |
   | Netty | 4.1.x | 4.2.x is the current stable line |

   Read the library's release notes. Then check the bytecode as described
   below: the jar's class files are the final word.
2. **Get the artifacts.** With internet access, the build downloads them. For
   offline machines, follow [guide 2 §2.8](02-offline-repository.md#28-when-something-is-missing)
   to add them to the `java21-offline` repository.
3. **Change the version** in the parent POM, for example
   `<netty.version>4.1.123.Final</netty.version>`.
4. **Verify**: `mvn clean install -Pfull` passes, and every jar is still Java 21
   or older ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-21-guarantee)).
   For Netty, also run the benchmark before and after, on the same machine
   ([guide 4 §4.10](04-run-and-operate.md#410-the-tools)).
5. **Record it** in the constraint table in
   [../01-requirements-and-scope.md](../01-requirements-and-scope.md) (C-6),
   and in `CHANGELOG.md`.

## 7.5 Upgrade the JDK

**Building** uses `maven.compiler.release 21` in the parent POM. `release`
compiles against the real Java 21 API. So it cannot produce the classic
`-source 8 -target 8` failure, where code compiles but calls a method that does
not exist on the target, and then fails at run time with `NoSuchMethodError`.

**Moving to a newer JDK** (24, 25, …):

1. Install it and run the whole suite on it:
   `mvn clean install -Pfull -Djvm=/opt/jdk25/bin/java`. The `jvm` property
   tells Maven's test runner (Surefire) which `java` runs the tests.
2. Raise `maven.compiler.release` only when you actually want the newer
   language level. Running on a newer JDK does not require it.
3. Check the GC (garbage collector) flags again. They change between releases:
   `-XX:+ZGenerational` is opt-in on JDK 21, becomes the default on 23, and the
   flag is removed on 24+, where passing it stops the JVM from starting. The
   flag is in the launcher (`bin/j-redis-server`, `bin/j-redis-server.cmd`)
   and in the systemd unit.
4. Watch for `sun.misc.Unsafe` warnings. Netty and JCTools use it. It is
   fully supported on 21, warns from 24, and is scheduled for removal later.
5. Run the benchmark before and after on the same machine, and check
   `INFO memory` again: a collector change can move the memory estimate
   ([../06 §6](../06-expiry-and-memory.md#6-jvm-heap-sizing)).

**JDK 21 updates** (21.0.*n*) need no change: install the update and restart
the server.

## 7.6 Bump the project version

The version is defined **only in the POMs**. The server's `--version` and
`INFO`, and the client's `lib-ver`, read it from the build. One command changes
every POM. In this example the new version is `1.1.0`; use your own:

```bash
scripts/set-version.sh 1.1.0                                                 # Linux
```

```bat
powershell -ExecutionPolicy Bypass -File scripts\set-version.ps1 -Version 1.1.0 & rem Windows
```

Then:

1. Update `CHANGELOG.md`: move "Unreleased" to `1.1.0 — <date>`, and mention
   format changes and new directives.
2. Run `mvn clean install -Pfull`, and check the Java 21 guarantee.
3. Run `scripts/make-dist.sh` (or `make-dist.ps1`). It writes
   `target/dist/j-redis-1.1.0.tar.gz` and `.zip`.
4. Tag the source in version control (`git tag v1.1.0`).
5. Services that use the client update `<version>` in their POMs.

Use `-SNAPSHOT` versions (`1.1.0-SNAPSHOT`) between releases, so that nobody
can mistake a development build for a release.

## 7.7 Release checklist

Tick every item before you publish a release:

- [ ] `mvn clean install -Pfull` green on JDK 21 (Linux; also the fast tier on Windows)
- [ ] every jar Java 21 or older (guide 3 §3.4)
- [ ] `CHANGELOG.md` complete, format changes called out
- [ ] docs updated (commands 05, directives 11, decisions 14, guides)
- [ ] distribution built and smoke-tested: start, `ping`, `j-redis-examples -p <port>`, clean stop
- [ ] upgrade tested on a copy of production data: old version → new version → the data is intact
- [ ] rollback plan decided (symlink back, or restore the backup)
