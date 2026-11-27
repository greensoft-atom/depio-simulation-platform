# scripts

This page lists every script in `backend/scripts/`: the release build and its checks, the port checks, the
backups and their proofs, the promotions, the certificate, nginx's logs and the backup drill. It is for the
operators who run these scripts on the machines and for the developers who change them. For each script it says
what the script does, how to run it, what it needs, and what it prints or returns. Each script's header comment is
its full reference; this page puts them side by side.

**Where they live.** The release carries fifteen of these scripts in its `scripts/` directory, and a sixteenth, the
account service's `account-key.sh` (D-117, its reference in `account-service/README.md`); a machine has them as
`/opt/backend/scripts/` ([make-release.sh](#make-releasesh) lists them). The backup units and the log
rotation's unit run them from there. The others (`make-release.sh`, every `check-*.sh` except `check-bind.sh`, and
`backup-drill.sh`) stay in the source tree and run on a build or development machine.

**Related pages.** Every setting a script or a unit reads is also in the
[deploy README's catalogue](../deploy/README.md#environment-variables). How the scripts are used on the machines:
[operations/01-deploy](../../docs/operations/01-deploy.md) and the
[runbook](../../docs/operations/02-runbook.md). The pictures:
[diagrams/07](../../docs/diagrams/07-deploy-and-operations.md). Words such as front, standby, promotion and fence
are in the [glossary](../../docs/glossary.md).

| Script | Group | In the release | Run by |
|---|---|---|---|
| [`make-release.sh`](#make-releasesh) | Release | no | a person, on the build machine; `build-offline.sh` |
| [`check-release-el9.sh`](#check-release-el9sh) | Release | no | a person, on a machine with Docker |
| [`check-release-guest-el9.sh`](#check-release-guest-el9sh) | Release | no | a person, on a machine with QEMU, after one run of `check-selinux-el9.sh`: the release on RHEL 9's kernel with SELinux enforcing (D-110) |
| [`check-units-el9.sh`](#check-units-el9sh) | Release | no | a person, on a machine with Docker: the release's units under systemd in UBI 9 |
| [`check-install-guide-el9.sh`](#check-install-guide-el9sh) | Release | no | a person, on a machine with Docker: the install guide's blocks as written, in UBI 9 with no network; then the launch's one machine checked (D-93), again after a restart, and the runbook's restore after the database's disk is lost |
| [`check-two-machines-el9.sh`](#check-two-machines-el9sh) | Release | no | root, on a machine with Docker and Unity's editor in `work/unity`: the two-machine page's steps as written (D-96) on two UBI 9 machines joined by a private link, the site probed throughout; B's own arenas (D-97); then A's MySQL lost, then A lost |
| [`check-ports-el9.sh`](#check-ports-el9sh) | Release | no | a person, on a machine with Docker and Unity's editor in `work/unity` |
| [`check-bind.sh`](#check-bindsh) | Ports | yes | `dev-mysql.sh`, `dev-jredis.sh`, `dev-account.sh`, `dev-backend.sh`, `dev-nginx.sh` and `client/headless-drill.sh`, before they start anything; a person, on a server whose unit says `Permission denied` on its port |
| [`check-selinux-el9.sh`](#check-selinux-el9sh) | Ports | no | a person, on a machine with QEMU: `check-bind.sh`'s SELinux branch and the three grants, in a Rocky 9 guest with SELinux enforcing (D-90) |
| [`binlog-stream.sh`](#binlog-streamsh) | Backups | yes | `backend-binlog-stream.service`, always on |
| [`backup-mysql.sh`](#backup-mysqlsh) | Backups | yes | `backend-mysql-backup.timer`, nightly |
| [`record-backup.sh`](#record-backupsh) | Backups | yes | the three scripts that record an outcome |
| [`backup-offsite.sh`](#backup-offsitesh) | Backups | yes | `backend-backup-offsite.timer`, hourly, once a host is named |
| [`fetch-offsite.sh`](#fetch-offsitesh) | Backups | yes | `restore-proof.sh`, for the proof from off the site; a person |
| [`restore-proof.sh`](#restore-proofsh) | Backups | yes | `backend-restore-proof.timer`, weekly; `backend-restore-proof-offsite.timer`, monthly |
| [`restore-drill.sh`](#restore-drillsh) | Recovery | yes | `restore-proof.sh`; a person |
| [`backup-store.sh`](#backup-storesh) | Backups | yes | a person, before an upgrade |
| [`store-restore-drill.sh`](#store-restore-drillsh) | Recovery | yes | a person, monthly |
| [`promote-store.sh`](#promote-storesh) | Failover | yes | a person |
| [`promote-mysql.sh`](#promote-mysqlsh) | Failover | yes | a person |
| [`check-promote.sh`](#check-promotesh) | Failover | no | a person, on any machine: both promotions' refusals and the handover's order, with stand-in clients |
| [`private-ca.sh`](#private-cash) | Certificates | yes | a person, on the operators' machine that keeps the root |
| [`install-certificate.sh`](#install-certificatesh) | Certificates | yes | a person, on every machine |
| [`check-certificates.sh`](#check-certificatessh) | Certificates | no | a person, on any machine with openssl |
| [`rotate-nginx-logs.sh`](#rotate-nginx-logssh) | Logs | yes | `backend-nginx-logs.timer`, nightly, on every machine |
| [`check-nginx-logs.sh`](#check-nginx-logssh) | Logs | no | a person, on a machine with a release and curl |
| [`backup-drill.sh`](#backup-drillsh) | Drills | no | a person, on a development machine |

**The machines.** The examples name three machines, A, B and C
([01 §1](../../docs/operations/01-deploy.md#1-what-runs-where)). On three machines the backup machine is C, the one
with MySQL's replica, until a failover moves the backups
([runbook §2](../../docs/operations/02-runbook.md#mysql-built-2026-09-29-d-35)). The site launches on one machine
([D-93](../../docs/architecture/03-decision-log.md#d-93--the-site-launches-on-one-machine-for-up-to-about-5-000-players)):
there the backups run on that machine too, with their copies on a disk of their own. On two machines, B is A's
standby ([D-96](../../docs/architecture/03-decision-log.md#d-96--a-second-machine-is-the-first-ones-standby-the-replicas-and-a-front-ready-to-start)).

**The scripts use the release's own tools.** Seven scripts call `mysql`, `mysqldump` or `mysqlbinlog`:
`binlog-stream.sh`, `backup-mysql.sh`, `record-backup.sh`, `backup-offsite.sh`, `restore-proof.sh`,
`restore-drill.sh` and `promote-mysql.sh`. Each puts `../mysql/bin` first on `PATH`. In `/opt/backend/scripts/`
that directory is the release's MySQL 8.4
([09 §6](../../docs/detailed-design/09-release-and-packaging.md#6-mysql-from-the-release)). The source tree has
no `mysql/`, so a script run from there uses the machine's own tools. The three store scripts (`backup-store.sh`,
`store-restore-drill.sh`, `promote-store.sh`) take j-redis's client and server from the release's `jredis/bin/`, and
set `JAVA_HOME` to the release's `runtime/` unless `JAVA_HOME` is already set. When there is no `jredis/` beside
`scripts/`, they use `/opt/j-redis/bin/`.

**How the checks report.** Every `check-*.sh` but `check-bind.sh` prints one line per check, `ok` or `FAIL`,
and ends with `--- <n> failed`. Two end differently: `check-selinux-el9.sh` and `check-release-guest-el9.sh` end
with `ALL PASSED: ...` or `FAILED`. Each exits 0 when nothing failed and non-zero otherwise.

## Release

### make-release.sh

Builds the release: one directory that holds everything the machines run, and a `.tar.gz` of it. It works in this
order:

1. **j-redis first.** It runs `mvn -o -DskipTests clean install` in `../j-redis-service`. The backend builds against
   j-redis's client, and the release carries j-redis's server. The build is `clean`, as the backend's is (O-84).
2. **The account service** (D-117). It runs `mvn -o -DskipTests clean package` in `../account-service`, whose jar
   and runtime libraries land in its `target/lib`.
3. **The backend, clean.** It runs `mvn -o -DskipTests clean install` in `backend/`, offline. The clean step matters:
   a file deleted from the sources (for example a migration replaced by the baseline, D-75) would otherwise stay in
   `target/` and ship. The clean empties `backend/target/`, earlier releases included.
4. **One `lib/` per process.** For each of `arena`, `platform`, `worker` and `gateway`, it copies the module's
   runtime dependencies and its own jar into `lib/<process>/`. There is one directory per process, not one shared
   directory: `handoff`'s module graph keeps the MySQL driver off the arena's classpath, and a shared directory
   would put it back.
5. **What the processes run on**
   ([09 §4](../../docs/detailed-design/09-release-and-packaging.md#4-the-release)): j-redis's server, client and
   tools (jars and launchers) into `jredis/`, with the template of its configuration; the Java runtime, linked by
   `jlink` from the JDK's `jmods/` into `runtime/`; and `vendor/mysql-8.4` and `vendor/nginx-1.30`, as committed,
   into `mysql/` and `nginx/`; and the account service's jar and libraries into `account/lib/`.
6. **The runtime's modules are a fixed list**: `java.se`, `jdk.httpserver`, `jdk.unsupported`, `jdk.jfr`,
   `jdk.net`, `jdk.crypto.ec`, `jdk.jcmd`, `jdk.management` and `jdk.management.agent`. `jdeps` cannot compute the
   list, because the libraries are modules whose optional requirements are absent. So the script runs `jdeps` on
   the backend's own jars, j-redis's and the account service's, and **a module they use that is not in the list stops the release**, with
   "the runtime lacks …: add it to modules"
   ([09 §4.1](../../docs/detailed-design/09-release-and-packaging.md#41-the-runtime)).
7. **The configuration and the scripts.** It copies `deploy/systemd` and `deploy/env`, `deploy/mysql` as
   `mysql-conf/` and `deploy/nginx` as `nginx-conf/`, and the sixteen operational scripts, the account service's
   `account-key.sh` among them: `backup-mysql.sh`,
   `backup-store.sh`, `binlog-stream.sh`, `restore-drill.sh`, `store-restore-drill.sh`, `install-certificate.sh`,
   `promote-store.sh`, `promote-mysql.sh`, `record-backup.sh`, `restore-proof.sh`, `backup-offsite.sh`,
   `fetch-offsite.sh`, `private-ca.sh`, `rotate-nginx-logs.sh`, `check-bind.sh` and `account-key.sh`.
8. **No symbolic link.** It replaces every symbolic link in the release with a copy of the file it names
   ([D-106](../../docs/architecture/03-decision-log.md#d-106--nothing-in-the-repository-or-a-release-is-a-symbolic-link)).
   `jlink` makes such links in the runtime's `legal/`. A copy that does not keep links (Git for Windows by default,
   an archive unpacked on Windows) would leave a small text file in each one's place.
9. **What it was built from.** It writes `COMMIT`, the git commit, with `-dirty` when the tree differs from that
   commit (O-84), and `VERSIONS.md`: `vendor/VERSIONS.md`, then the backend's version and commit, j-redis's version,
   the account service's version, and the runtime's version and modules.
10. **The archive.** It packs the directory, then prints the number of jars in each `lib/` and `account/lib/`, and
    the sizes of `runtime/`, `mysql/`, `nginx/`, `jredis/` and `account/`.

The release's layout is in the [deploy README](../deploy/README.md#the-release).

```
scripts/make-release.sh    ->  target/release/backend-<version>/  and  target/release/backend-<version>.tar.gz
```

| | |
|---|---|
| Arguments | none. The version is the first `<version>` in `backend/pom.xml` (`0.1.0-SNAPSHOT`) |
| Settings | `MVN`, the Maven command (default `mvn`). A local repository filled from more than one source needs `MVN="mvn -Daether.enhancedLocalRepository.trackingFilename=_none"`. `JDK`, the JDK 21 whose `jdeps` checks the jars and whose `jmods/` the runtime is linked from (default `vendor/jdk-21`) |
| Needs | JDK 21 and Maven to run the build; a local repository that holds every dependency of the backend, of j-redis and of the account service (the `java21-offline` bundle); `../j-redis-service`, `../account-service` and `../vendor` beside `backend/`; tar; git for `COMMIT` (optional). `build-offline.sh`, at the repository's root, runs this script on the committed JDK and Maven with no network ([09 §3](../../docs/detailed-design/09-release-and-packaging.md#3-the-build-from-the-repository-alone)) |
| Runs | on the build machine, from any directory: it changes to `backend/` itself |
| Prints | `release: <dir>`, the jar count of each `lib/`, the sizes above. Exits non-zero when a step fails |

### check-release-el9.sh

Checks a release on RHEL 9's own userspace with nothing installed
([09 §7](../../docs/detailed-design/09-release-and-packaging.md#7-how-it-is-verified)). The script runs itself in
Red Hat's UBI 9 minimal image, with the release mounted read-only, and checks, each with an `ok` or `FAIL` line:

- no symbolic link in the release (D-106);
- every library of every native file in the release found (`runtime/bin/java`, `mysqld` and the MySQL tools,
  `nginx`, and the rest), every MySQL program started, and every MySQL plugin, component and client plugin loaded by
  the server
  ([D-110](../../docs/architecture/03-decision-log.md#d-110--every-native-file-in-the-release-loads-on-rhel-9-each-one-checked-rhel-8s-libcryptso1-beside-libaio-and-libnuma));
- MySQL 8.4 initialised and started; j-redis started;
- the account service from `account/lib/` on the release's runtime, its key made by the release's
  `scripts/account-key.sh` (with this machine's openssl, which the image lacks), migrating its database and signing
  an account up (D-117);
- the platform migrating the database and taking that account's access token for a session;
- the gateway, a worker and an arena running, and whether the arena uses Netty's native epoll;
- nginx passing requests to the platform over HTTP and HTTPS;
- `nginx -t` on the shipped `nginx-conf/`, installed as on a machine.

Then it lists the tools that the scripts take from the system and that the image lacks. A server installation has
them ([09 §4.2](../../docs/detailed-design/09-release-and-packaging.md#42-what-the-host-still-provides)). The
check is mutation-tested: a release without `libaio` fails it.

```
scripts/check-release-el9.sh [release-dir]       default: target/release/backend-*/
IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.6 scripts/check-release-el9.sh    another RHEL 9.x
IMAGE=registry.access.redhat.com/ubi8/ubi-minimal:8.10 scripts/check-release-el9.sh   RHEL 8, glibc 2.28 (D-100)
```

| | |
|---|---|
| Settings | `IMAGE`, the image (default `registry.access.redhat.com/ubi9/ubi-minimal:9.5`) |
| Needs | Docker, and the image, pulled the first time |
| Runs | on the build machine, from any directory |
| Prints | `ok` or `FAIL` per check, the tools the image lacks, then `--- <n> failed`; exits non-zero on any failure |

### check-release-guest-el9.sh

Checks a release on RHEL 9's kernel with SELinux enforcing
([D-110](../../docs/architecture/03-decision-log.md#d-110--every-native-file-in-the-release-loads-on-rhel-9-each-one-checked-rhel-8s-libcryptso1-beside-libaio-and-libnuma)).
The containers of `check-release-el9.sh` cannot show this: they run on this machine's kernel and have no SELinux.
This script boots the Rocky Linux 9 guest that [`check-selinux-el9.sh`](#check-selinux-el9sh) made in `work/el9`,
on a layer of its disk that is thrown away afterwards. In the guest it:

1. unpacks the release in `/opt` and sets up MySQL as the install guide does for one machine
   ([03 §4](../../docs/operations/03-install-guide.md#4-the-release-in-opt) to
   [§6](../../docs/operations/03-install-guide.md#6-mysql)), then starts `backend-mysql.service` under systemd;
2. checks MySQL serving, TLS over TCP, InnoDB's native AIO, the binary log read by `mysqlbinlog` and a dump by
   `mysqldump`, the server's process in SELinux's `unconfined_service_t` domain, and no SELinux denial since the
   boot;
3. runs `check-release-el9.sh`'s own checks as root in the guest: every native file, program and plugin, and every
   process, the account service's among them with a signing key made on the host by the release's `account-key.sh`.

**Without KVM the guest is emulated, and not wholly reliable** (T-72): in six runs (2026-11-26 and 27), three each had a
process misbehave as no real CPU has it, its files intact: a JVM jumping where no code is, a class not found in a
jar that holds it, nginx's HTTPS once not answered; each run after passed. Read a pass as it is. Read a failure
first against `check-release-el9.sh` on this machine's own CPU, which the same release passed every time, then run
the guest again; a failure that comes back is the release's. The check says what each failure was: the answer, and a
process's own last words if it died. The guest is for RHEL 9's kernel and SELinux; the containers run the release on
the real CPU.

```
backend/scripts/check-release-guest-el9.sh [release-dir]    from the checkout; the release's .tar.gz beside it
EL9_SSH_PORT=<port> backend/scripts/check-release-guest-el9.sh   another host port for the guest's ssh (default 2224)
```

| | |
|---|---|
| Needs | `qemu-system-x86_64`, `qemu-img`, ssh, the release's `.tar.gz` beside its directory, and `work/el9` (the guest's disk, key and seed) from one run of `check-selinux-el9.sh` |
| Runs | from the checkout; about half an hour without KVM |
| Prints | `ok` or `FAIL` per check, then `ALL PASSED: the release on RHEL 9's kernel, SELinux enforcing` or `FAILED`; the exit status is non-zero on any failure |

### check-units-el9.sh

Checks the release's systemd units on RHEL 9's systemd
([09 §7](../../docs/detailed-design/09-release-and-packaging.md#7-how-it-is-verified)). The script runs itself in
Red Hat's UBI 9 init image, with systemd as the first process, the release mounted read-only and this repository's
`deploy/`. It installs the machine as [01 §7](../../docs/operations/01-deploy.md#7-installing-a-machine) and
[§9](../../docs/operations/01-deploy.md#9-mysql) say: the users, the units, and the database's configuration from
its example, initialised as the example's header says. Then it checks, as one machine that is the front (D-82):

- `backend-mysql`, `backend-store@session`, `backend-account` (as its own user, its key written into its settings
  by the release's `account-key.sh`), `backend-platform`, `backend-gateway`, `backend-worker@c1`, `backend-arena@a1`
  and `backend-nginx` started;
- an account signed up at the account service through nginx over HTTPS and its access token taken for a session
  (D-117), the account service's key set not routed, and a match connection through nginx's 9001 to the arena;
- port 80 in clear (D-115): the API's health, and the arena asked for by name;
- the secrets in the settings files alone, root's, and nowhere systemd shows them, the signing key's neither (D-102);
- an arena with TLS of its own, its keystore made by this repository's `install-certificate.sh`;
- [operations/07](../../docs/operations/07-start-stop-restart.md)'s reloads, restarts and patterns, as written;
- the platform's watch: the worker stopped, `TargetDown` listed by the admin API within three minutes, and gone once
  the worker is back (D-86); the account service among its targets, read and up (`GET /admin/watch`, D-117);
- `backend-nginx-logs`, nginx's logs rotated as the unit runs it (D-85);
- nginx reloaded, and every unit stopped.

A drop-in gives the processes smaller heaps than production's: this is a check, not a machine.

```
scripts/check-units-el9.sh [release-dir]       default: target/release/backend-*/
IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-units-el9.sh   another RHEL 9.x
```

| | |
|---|---|
| Settings | `IMAGE` (default `registry.access.redhat.com/ubi9/ubi-init:9.5`) |
| Needs | Docker, a privileged container (systemd runs inside it), and the image |
| Runs | on the build machine, from any directory |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

### check-install-guide-el9.sh

Runs the [install guide](../../docs/operations/03-install-guide.md) as written: every bash block between the guide's
`guide-steps` markers, in order. It runs in Red Hat's UBI 9 init image (systemd its first process) with **no
network**, on a plain copy of the repository's files: what a clone has, tracked and new files, nothing ignored. It
covers, in order:

1. from the build with `build-offline.sh` to a sign-up at the account service through nginx and its token taken for
   a session (D-117);
2. the launch's one machine as D-93 lays it out: every unit active, the account service among them, each arena
   reached by its name through nginx's 9001 and 80, every process's metrics watched, the account service's too, the
   result stream on its own store, no secret in `systemctl show`, and a dump and its proof of both databases recorded
   and seen by the worker;
3. all of step 2 again after the container is restarted, as the machine would be at a reboot;
4. the database's disk lost, then the runbook's restore on one machine, run as written
   ([runbook §4](../../docs/operations/02-runbook.md#on-the-launchs-one-machine-d-93)), with nothing committed lost:
   an account signed up after the dump, and its player, both back, the two databases made again.

The header also shows how to run it on a server that already has the operating system's own Java and MySQL
installed and running (the guide's §1): an image with them, and the backend's MySQL on another port
(`GUIDE_VALUES="MYSQL_PORT=3307"`, set after the guide's own values).

```
scripts/check-install-guide-el9.sh
IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-install-guide-el9.sh
IMAGE=backend-el9-os-packages GUIDE_VALUES="MYSQL_PORT=3307" scripts/check-install-guide-el9.sh   (image: its header)
```

| | |
|---|---|
| Settings | `IMAGE` (default `registry.access.redhat.com/ubi9/ubi-init:9.5`); `GUIDE_VALUES`, values set after the guide's own |
| Needs | Docker and git |
| Runs | on the build machine; about twenty-five minutes |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

### check-two-machines-el9.sh

Runs [operations/10, two machines](../../docs/operations/10-two-machines.md), as written (D-96), on two UBI 9.5
containers with systemd as their first process. The containers have no network but a private link of their own: a
veth pair, one end moved into each (A `10.0.0.1`, B `10.0.0.2`), so nothing of the host's network or firewall
changes. In order:

1. A, the launch's machine, is installed by the install guide's own check;
2. B is made A's standby by the page's steps, the account service's database and user among them, while a probe uses
   the site through A's nginx twice a second and times every refusal, and beside it, in a loop of its own, signs up
   at the account service every ten seconds and takes the token for a session; the promotions raise both epochs;
3. B's own arenas (D-97, the page's [§10](../../docs/operations/10-two-machines.md#10-arenas-on-b)): matches played
   on them through A's nginx's 80, in clear, by the C# headless client on Unity 2019's Mono (D-116), with A's arenas
   stopped for it; where nginx passed each match is read from `access.log`'s `/match/` lines, answered 101; then B
   frozen for a minute while A's site goes on;
4. A's MySQL lost while A runs: B's MySQL promoted
   ([runbook §2](../../docs/operations/02-runbook.md#2-stateful-primary-failure)) and A's processes moved onto it;
5. A lost: the runbook's steps for it on B ([§4](../../docs/operations/02-runbook.md#4-whole-machine-loss)), the
   site served from B with nothing committed lost: every sign-up the probe was answered 201, whatever its session was
   answered, in B's `account` database, and no account there it did not send.

The blocks the page marks `console` are not run: the copy to B (this check copies the same files through docker)
and the firewall (`check-ports-el9.sh` runs it).

```
scripts/check-two-machines-el9.sh                        as root
SAVE_A=<image> scripts/check-two-machines-el9.sh         keeps A as an image once the install guide's steps pass
A_IMAGE=<image> scripts/check-two-machines-el9.sh        starts A from that image: the install is not run again
STANDBY_ONLY=1 scripts/check-two-machines-el9.sh         the standby alone (D-96), without B's arenas
```

| | |
|---|---|
| Settings | `IMAGE` (default `registry.access.redhat.com/ubi9/ubi-init:9.5`); `SAVE_A`, `A_IMAGE`, `STANDBY_ONLY` as above; `UNITY_DATA`, another copy of Unity's `Editor/Data` (`client/unity-env.sh`) |
| Needs | root; Docker, git, `ip`, `nsenter`, and Unity 2019.4's editor in `work/unity` (`client/unity-env.sh` fetches it once): its compiler builds the C# client for .NET Framework 4.5, and its Mono, mounted in both machines, runs it (D-116) |
| Runs | on the build machine; about twenty minutes, twelve with `A_IMAGE` |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

### check-ports-el9.sh

Checks [operations/05, ports and firewall](../../docs/operations/05-ports-and-firewall.md), and
[06, certificates](../../docs/operations/06-certificates.md), as written, for the site's front (D-82). It runs in
Rocky Linux 9.5 (RHEL 9's firewalld), with systemd as its first process and no network. It builds this layout:

- **A**, the front (`10.0.0.1`, and the public `198.51.100.1`): MySQL with TLS, the session store, the account
  service (D-117), the platform, the gateway, the arena `a1` and nginx, from a release with the settings it ships; two of C's processes, the first
  worker and the scratch MySQL, for their ports; and a stand-in for sshd on 22, which echoes. nginx has the shipped
  configuration and a certificate made by `private-ca.sh` and installed by `install-certificate.sh`.
- **B** (`10.0.0.2`), in a network namespace: its account service, platform, gateway and arena `b1`, on A's store
  and MySQL.
- **C** (`10.0.0.3`), a stranger on the internal network (`10.0.0.50`) and a player (`198.51.100.7`), each in a
  namespace too.

Then it checks:

1. every listener is where [05 §2](../../docs/operations/05-ports-and-firewall.md#2-every-port-in-production) says,
   and nowhere else;
2. B, C, the stranger and the player each try every port that matters: first with the firewall stopped, as RHEL 9
   has it, then after [05 §5](../../docs/operations/05-ports-and-firewall.md#5-the-firewall-on-rhel-9)'s commands
   for A, which the script takes from the page;
3. as a player who trusts the backend's root alone: sign-ups at the account service and their tokens taken, the
   account service's key set refused, and the lobby, through 443; match TLS through 9001
   by each arena's name, with a wrong name and a wrong root refused; 80 in clear (D-115), the API's health on it,
   and A's and B's arenas by name through `/match/`, answered 101, with an unknown name answered 404;
4. the C# headless driver, built for .NET Framework 4.5 and run on Unity 2019's Mono, playing fifteen scenarios
   through the front's 80 in clear: `http://`, `ws://`, and matches by `/match/` (D-116). Not `untrusted`: Mono's
   own TLS stops at 1.0, and nginx's is 1.2 and 1.3, so 443's and 9001's TLS are checked by `openssl s_client`
   alone (step 3). The arenas log each join from the player's address without a port, from nginx's
   `X-Forwarded-For` (D-115);
5. what nginx's logs say it routed, to both machines' account services too; the player's address in the platforms'
   and the account services' limits; A's platform and account service stopped, and B's serving;
6. the rules after a restart of firewalld, the commands run twice, B's and C's commands, and the install guide's
   [§12](../../docs/operations/03-install-guide.md#12-open-the-firewall).

It stops at once if firewalld does not start. What it showed is in
[05 §9](../../docs/operations/05-ports-and-firewall.md#9-what-was-checked-and-what-was-not).

```
scripts/check-ports-el9.sh                     from backend/, the release in target/release/backend-*/
```

| | |
|---|---|
| Settings | `UNITY_DATA`, another copy of Unity's `Editor/Data` (`client/unity-env.sh`) |
| Needs | a release (`make-release.sh` first); Docker, and the network once, to build its image (`rockylinux/rockylinux:9.5` with firewalld); Unity 2019.4's editor in `work/unity` (`client/unity-env.sh` fetches it once), whose compiler builds the C# driver for .NET Framework 4.5, and whose Mono, mounted in the container, runs it, so the image needs no Mono or .NET (D-116); `fs.inotify.max_user_watches` high enough for the container's dbus (524288; an editor's watches can use up the default) |
| Runs | on the build machine, from any directory; about ten minutes once its image is built. The processes run as root with small heaps; neither changes where they listen |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

## Ports

### check-bind.sh

Answers one question: can this user bind these TCP ports, here? It binds each port on `BIND` and lets it go.
`BIND` is `127.0.0.1` by default; `dev-nginx.sh` gives `0.0.0.0`, every address, as its nginx binds, so that a
listener on any one address is found (O-83). When a bind is refused, the script names the policy of this machine
that refuses it (the unprivileged-port threshold raised, an SELinux-confined login, WSL's reserved ranges), the
command that lifts it, and the way round it
([D-88](../../docs/architecture/03-decision-log.md#d-88--a-port-the-machine-refuses-is-named-with-its-policy-before-anything-starts)).

Why it is needed: on Linux a port above 1023 is any user's, so `Permission denied` on one is a policy, and the
process's own error does not name the policy. A port below 1024 (for example nginx's 80 and 443, for a user who is
not root) is root's by Linux's own rule, and the script says so, with the ways round it (O-48).

`dev-mysql.sh`, `dev-jredis.sh`, `dev-account.sh`, `dev-backend.sh`, `dev-nginx.sh` and `client/headless-drill.sh` run it before they
start anything; it replaced their old test of a port in use. On a server, run it as the unit's user when the
unit's journal says `Permission denied` on its port.

```
scripts/check-bind.sh 3314 8080                                        on a development machine
runuser -u backend -- /opt/backend/scripts/check-bind.sh 8080 8081     on a server, as the unit's user
```

| | |
|---|---|
| Settings | `BIND`, the address (default `127.0.0.1`) |
| Needs | python3 (RHEL 9 and Ubuntu carry it). Without python3, in the source tree, `vendor/jdk-21` compiles and runs a probe class; the release's runtime cannot (it has no `jdk.compiler`). With neither, the check says so and ends 0, so that a start goes on. `ss`, to name who holds a port in use |
| Runs | anywhere, in a second |
| Returns | 0: every port is free; 1: a port is in use or refused, and why; 2: wrong usage |
| Checked | as a user who is not root, in a network namespace with the threshold raised (both refusals the owner met were reproduced first, O-46); in Rocky Linux 9 as uid 1000; with a port in TIME_WAIT, which is free to it as to a server. The SELinux branch in a Rocky 9 guest with SELinux enforcing and a confined login, where each remedy it names was seen to work ([`check-selinux-el9.sh`](#check-selinux-el9sh), plan item 108). The WSL branch follows WSL's documentation and was not run. 80 and 443 refused to `devtest` and named root's (plan item 110) |

### check-selinux-el9.sh

Checks `check-bind.sh`'s SELinux branch, and the three grants it names, where SELinux is real: a Rocky Linux 9 guest
under QEMU, SELinux enforcing, with a login it confines (`devel`, `user_u`). This machine has no SELinux, so the
guest runs under QEMU, in software emulation where there is no KVM. As `devel`, it binds 3314, 6380, 8080, 8081 and
9001 with python3 and with Java 21's `HttpServer`, the owner's symptom
([D-90](../../docs/architecture/03-decision-log.md#d-90--the-port-checks-selinux-branch-is-proven-in-a-rocky-9-guest-and-names-the-exact-grant)).
Each stage is asserted:

| Stage | Expected |
|---|---|
| confined, the boolean `selinuxuser_tcp_server` off | every bind refused; `check-bind.sh` names the policy and its three remedies |
| permissive | every bind allowed, so SELinux is what refuses |
| the module made from 8080's own denial | 8080 allowed, the other ports still refused |
| the boolean on | every bind allowed, the labelled ports (8080, 8081, 9001) too |
| the login mapped unconfined, CentOS's default | every bind allowed, and `check-bind.sh` says "free for this user" |

At the end the guest is powered off and its disk kept, so a second run skips the image and the packages.
`check-release-guest-el9.sh` boots the same guest.

```
backend/scripts/check-selinux-el9.sh                        from the checkout
EL9_SSH_PORT=2224 backend/scripts/check-selinux-el9.sh      another host port for the guest's ssh (default 2223)
```

| | |
|---|---|
| Needs | `qemu-system-x86_64`, `qemu-img`, `cloud-localds` (Ubuntu's packages: `qemu-system-x86`, `qemu-utils`, `cloud-image-utils`), ssh, and the network for the image (650 MB, once, checked against Rocky's `CHECKSUM`) and for the guest's dnf |
| Runs | about forty minutes without KVM the first time (the guest is emulated), ten after |
| Writes | `work/el9`: the image, the guest's disk, its seed, key and console log |
| Prints | `ALL PASSED: check-bind.sh's SELinux branch and the three grants, in Rocky 9 enforcing`, or a `FAIL` line with the stage's output and `FAILED`. Returns 0 or 1 |

## Backups and recovery

These scripts protect MySQL and the stores. The design is
[06 §10](../../docs/detailed-design/06-persistence-mysql.md#10-backup-and-recovery) and
[D-71](../../docs/architecture/03-decision-log.md#d-71--backups-are-proved-by-restoring-them-every-week-under-live-writes-copied-off-the-site-encrypted-and-watched-through-mysql).
How they are installed is [01 §10](../../docs/operations/01-deploy.md#10-copies-off-the-databases-machine). The
units that run them are in the [deploy README](../deploy/README.md#systemd).

### binlog-stream.sh

Copies the primary's binary logs (binlogs) to this machine as MySQL writes them, so that a point-in-time restore
survives the loss of the database's disk. It runs `mysqlbinlog --read-from-remote-server --raw --stop-never`.

- It resumes from the newest copy it already has. `--raw` rewrites that file from its start, so a file left
  half-written by a crash is completed, never duplicated. With no copy, it starts from the first binlog the server
  lists.
- If the server has purged the file it needs, it fails and says so. The copies then have a gap that no restart can
  fill, and a new dump is the only way back to a restorable point.
- It writes its files private (`umask 077`).

```
binlog-stream.sh <out-dir>
```

| | |
|---|---|
| Settings | `BINLOG_CNF` (required): a MySQL option file, `[client]` with `host`, `user`, `password`, `ssl-mode=VERIFY_IDENTITY` and `ssl-ca`, so the password stays off the command line and the stream is encrypted and checked. `BINLOG_SERVER_ID`, the connection's server id (default 900): two connections with one id make the server drop the older one |
| Needs | `mysql`, `mysqlbinlog`. The MySQL account `binlog`, with `REPLICATION SLAVE` (the stream) and `REPLICATION CLIENT` (`SHOW BINARY LOGS`, to know where to start) ([01 §9](../../docs/operations/01-deploy.md#9-mysql)) |
| Runs | on the backup machine, never on the database's own |
| Run by | `backend-binlog-stream.service`, as `backend-backup`, into `/var/lib/backend/binlog`, with `Restart=always` every 10 s: a database that shuts down ends the stream with status 0, and the stream must resume when the database is back |

### backup-mysql.sh

Takes a consistent full dump of each database named, with the binlog position it was taken at, so that a restore can
be carried forward to any later moment. The databases are a comma-separated list, by default the backend's and the
account service's (D-117, 10 §6.3), each to a file of its own, `<database>-<UTC time>.sql.gz`, in one run.

- The dump: `mysqldump --single-transaction --source-data=2 --routines --triggers --events --set-gtid-purged=OFF
  --no-tablespaces`, gzipped. `--single-transaction` reads one snapshot without locking InnoDB tables.
- The dump has no `USE` statement, so it restores under any name. That is what lets the restore drill run beside
  production without touching it.
- It is written as `.partial` and renamed when whole. A dump without a binlog position is refused before it is
  renamed (O-67).
- After every dump has succeeded, the script deletes each database's dumps older than `KEEP_DAYS`. With `BINLOG_DIR`
  it also deletes every binlog copy before the earliest file that a remaining dump starts from. It takes the
  earliest start of all the dumps, of every database in `out-dir` (the binlogs are the server's), not the oldest
  dump's: a dump kept from another server (a primary before a failover) names a file of another series, and that
  series keeps its own start (O-66).
- It records the run's outcome, success or failure, through `record-backup.sh`, as kind `dump`: one row, a success
  only if every database's dump was, a failure naming the database that failed. The alerts read when a dump last
  succeeded, so a database that fails stops that time.
- It writes its files private (`umask 077`).

```
backup-mysql.sh [databases] [out-dir]        defaults: backend,account, /var/backups/backend
```

| | |
|---|---|
| Settings | `MYSQL_CNF`, the backup account's option file (`host`, `user`, `password`, `ssl-mode=VERIFY_IDENTITY`, `ssl-ca`; unset: the client's defaults). `KEEP_DAYS` (8). `BINLOG_DIR`, `binlog-stream.sh`'s copies (unset: no binlog copy is pruned). `RECORD` (1; 0 records nothing) |
| Needs | `mysqldump`, `mysql`, `gzip`. The account `backup`, with `SELECT`, `SHOW VIEW`, `TRIGGER`, `EVENT`, `RELOAD` and `REPLICATION CLIENT` on `*.*`, and `INSERT` on `backend.backup_run`, and nothing more. Each grant was checked by removing it: without `RELOAD` (`FLUSH TABLES`) or `REPLICATION CLIENT` (`SHOW MASTER STATUS`) the dump is refused. `PROCESS` is not needed with `--no-tablespaces`, and it would show the user every session's statements |
| Runs | on the backup machine: a dump on the disk it protects is lost with that disk |
| Run by | `backend-mysql-backup.service` (as `backend-backup`; arguments `backend,account /var/lib/backend/mysql-dumps`; `BINLOG_DIR=/var/lib/backend/binlog`, `KEEP_DAYS=8`), started by `backend-mysql-backup.timer` at 03:30 UTC, at a random moment within the 15 minutes after |

### record-backup.sh

Records one backup step's outcome in the table `backup_run`. Every worker reports that table as metrics
(`backend_backup_succeeded_timestamp_seconds{kind}`, `backend_backup_failed{kind}`,
`backend_backup_restore_seconds`), and the alerts read them: a step that failed, or never ran, shows as a time that
stops moving. The script makes the detail one line of at most 255 characters and quotes it for SQL.

```
record-backup.sh <dump|proof|offsite> <started, seconds since the epoch> <ok: 1|0> <detail> [seconds]
```

`kind` is stored as 1 (`dump`), 2 (`proof`) or 3 (`offsite`). `seconds` is a proof's restore and replay time: the
time a last-resort restore would take (D-72).

| | |
|---|---|
| Settings | `MYSQL_CNF`, the backup account's option file. `RECORD_DB`, the database (default `backend`) |
| Needs | `mysql`; `INSERT` on `backup_run` and nothing more there |
| Run by | `backup-mysql.sh`, `backup-offsite.sh`, `restore-proof.sh` |

### backup-offsite.sh

Copies the backups off the site, encrypted, so that a fire, a flood or a lost account that takes the machines does
not take the backups too.

1. It runs `FLUSH BINARY LOGS` on the primary and waits up to 30 s for the stream to open the next file. The file
   the stream was writing is then closed and copied whole: only closed binlogs leave the site, and a restore from
   them replays to their end without a half-written event.
2. It encrypts each dump and each closed binlog copy that is new or changed into the mirror directory
   (`openssl enc -aes-256-cbc -pbkdf2 -iter 200000`). It removes a mirror file whose source was pruned.
3. It pushes the mirror to the target with `rsync -a --delete`, so the target holds what the backup machine holds:
   8 days of dumps and every binlog since the oldest. It never pushes a mirror with no dump: that push is refused
   and recorded as a failure, so that a machine whose dumps are gone or set aside does not wipe the host (O-69).
4. Every binlog from the one the stream opened on, newer ones too, waits for the next run, when it is closed (O-68).
   So the copy off the site is as far behind as the time since the last run.

It records its outcome as kind `offsite`. With `OFFSITE_TARGET` unset it copies nothing and records nothing: the
owner has not named a host yet (Q-53).

```
backup-offsite.sh <dump-dir> <binlog-dir> <mirror-dir>
```

| | |
|---|---|
| Settings | `OFFSITE_TARGET`: `user@host:dir` over SSH, or a directory. `OFFSITE_KEY` (required once a target is set): the key file, also kept somewhere off the machines; without it nobody can read the copy, an attacker or the operator. `OFFSITE_SSH`, the ssh command rsync uses (default `ssh -o BatchMode=yes`). `MYSQL_CNF`, the backup account (it has `RELOAD`, which the flush needs). `RECORD` (1) |
| Needs | `mysql`, `openssl`, `rsync`, `ssh` |
| Runs | on the backup machine |
| Run by | `backend-backup-offsite.service` (as `backend-backup`; mirror `/var/lib/backend/offsite-mirror`; `OFFSITE_TARGET` from `/etc/backend/offsite.env`), started hourly by `backend-backup-offsite.timer` once the owner names the host |

### fetch-offsite.sh

Brings the copy from off the site back and decrypts it, for a restore. It writes the dumps into `<out-dir>/dumps`
and the binlogs into `<out-dir>/binlog`, the layout `restore-drill.sh` reads. By default it fetches each database's
newest dump (the backend's and the account service's) and every binlog copy: what a restore to the copies' end reads. `ALL_DUMPS=1` fetches every dump, for a restore from
an older one. It decrypts each file and removes its encrypted copy as it goes, so the disk holds the set once, not
twice (O-21). It writes its files private (`umask 077`).

```
fetch-offsite.sh <out-dir>
```

| | |
|---|---|
| Settings | `OFFSITE_TARGET` and `OFFSITE_KEY` (both required) and `OFFSITE_SSH`, as for `backup-offsite.sh`. `ALL_DUMPS` (unset: each database's newest dump only) |
| Needs | `rsync`, `ssh`, `openssl`; room for the set once, and for the largest file's encrypted copy beside it |
| Returns | non-zero, with `no dump in <target>/dumps`, when the target holds no dump |
| Run by | `restore-proof.sh` with `FROM_OFFSITE=1`; a person, for a restore from off the site ([runbook](../../docs/operations/02-runbook.md#a-last-resort-restore-with-no-source-left)) |

### restore-proof.sh

The weekly proof that the backups restore. For each database named (by default the backend's and the account
service's, D-117), it restores its newest dump and the binlog copies onto the scratch server, into
`<database><suffix>`, with `restore-drill.sh` and `PROOF=1`, while the primary keeps writing. It records the outcome
as one row of kind `proof`, a success only if every database was proved, with the restores' and the replays' seconds.
It records every outcome: a failure's detail quotes the lines of its log that say why (else the log's last three
lines), never a database's that passed.

With `FROM_OFFSITE=1` it first fetches and decrypts the copy from off the site into a temporary directory (with the
fetch's errors in its log), then replays the copies to their end. The record's detail then starts with
`from off the site:`. This proves the copy off the site as well.

```
restore-proof.sh <dump-dir> <binlog-dir> [databases] [suffix]   defaults: backend,account, _proof
```

| | |
|---|---|
| Settings | `SOURCE_CNF` (required), the backup account's option file: the primary is read there, and the outcome recorded there. `SCRATCH_CNF` (required), the scratch server's. `RECORD_DB` (`backend`), the database whose `backup_run` the outcome goes in. `FROM_OFFSITE` (0), with `fetch-offsite.sh`'s settings |
| Needs | what `restore-drill.sh` needs, and what `fetch-offsite.sh` needs for the copy off the site |
| Runs | on the backup machine, against its scratch server (`127.0.0.1:3309`) |
| Run by | `backend-restore-proof.service` (arguments `… backend,account _proof`), started by `backend-restore-proof.timer` on Sundays at 04:00 UTC; and `backend-restore-proof-offsite.service` (`FROM_OFFSITE=1`, arguments `… backend,account _proof_offsite`), started by `backend-restore-proof-offsite.timer` on the first Sunday of the month at 05:00 UTC. Both units take one lock with `flock`, so the two never run at once |

### restore-drill.sh

Restores a dump into a scratch database, carries it forward through the binlog, and checks it. A backup that has
never been restored is not a backup. The script never writes to the source.

- It restores with `sql_log_bin = 0` in every session, and replays with `mysqlbinlog --skip-gtids --rewrite-db`.
- Before it loads anything, it refuses copies that do not start at the dump's file or that have a gap, and a scratch
  database that is the source (the same name on the same server).
- It drops the scratch database first if it exists, so give it a name nothing else uses.
- The checks: every table's row count against the source's (exact only when nothing writes meanwhile), and, for the
  backend's database, the ledger reconciled with every balance, coins and gems. A database is the backend's if it
  has a `ledger` table; the account service's has none, and its own checks are its newest account and event as the
  proof's floor, and no account made or event written at or after a `STOP_AT` (D-117, 10 §6.3).
- It reads the source's position with `SHOW MASTER STATUS`, or with `SHOW BINARY LOG STATUS` on a server that lacks
  the first (8.4).
- It ends with a `summary:` line (the restore's and the replay's seconds, and how far behind the copies were), then
  `drill passed` or `DRILL FAILED`.

```
restore-drill.sh <dump.sql.gz> <source-db> <scratch-db> [binlog-dir]
```

| Mode | What changes |
|---|---|
| default | the source and the scratch database are on this machine's server, and the replay reads the source's own binlog. Or, with `SOURCE_CNF`, `SCRATCH_CNF` and a `binlog-dir`: the source is read on the primary, the restore goes onto another server, and the replay reads `binlog-stream.sh`'s copies, after waiting up to 30 s for them to reach the primary's position. This second way rehearses the recovery that matters: the database's machine is gone |
| `STOP_AT='YYYY-MM-DD HH:MM:SS'` | the replay stops at the last transaction before that UTC moment, to undo a mistake made at a known minute; row counts are not compared, and no ledger row at or after the moment may be in the copy ([runbook](../../docs/operations/02-runbook.md#recovering-to-a-moment)) |
| `PROOF=1` | the weekly proof under live writes: exact row counts cannot hold, so it checks what does: every table present, the newest migration the source's, nothing committed before the capture missing, the ledger reconciled |
| `TO_COPIES_END=1` | with a `binlog-dir`: replay to the end of the newest copy, not to the source's position now, and do not wait for the source. For copies fetched from off the site, which are closed files and as old as their last copying |
| `NO_SOURCE=1` | the last resort (D-72): no source server is left. Nothing is read from one (`TO_COPIES_END=1` is implied), the target database must not exist yet (nothing is dropped), and only the checks that need no source are made: every table restored, the newest migration named, the ledger reconciled to every balance. Restore under the production name onto the new primary ([runbook](../../docs/operations/02-runbook.md#a-last-resort-restore-with-no-source-left)) |

| | |
|---|---|
| Settings | `SOURCE_CNF`, `SCRATCH_CNF` (unset: the local server), `STOP_AT`, `PROOF` (0), `TO_COPIES_END` (0), `NO_SOURCE` (0) |
| Needs | `mysql`, `mysqlbinlog`, `zcat`, `awk`; on the scratch server, the right to drop and create the scratch database; on the source, the backup account's grants |
| Runs | on the backup machine. The dumps and copies belong to `backend-backup`, so run it as that user or as root |
| Run by | `restore-proof.sh`; a person, for the monthly drill and for a restore to a moment |

### backup-store.sh

Makes a consistent copy of a running j-redis's data directory, by j-redis's own documented procedure (j-redis-service
`docs/08-persistence.md` §12), and nothing cleverer:

1. stop automatic AOF rewrites for the length of the copy;
2. compact with `BGREWRITEAOF` and wait for it to finish;
3. copy the manifest first, then every file it lists;
4. put the rewrite setting back, even when the copy fails.

The copy is consistent because j-redis deletes files only when a rewrite commits, and step 1 prevents that. The
copy holds every live session token, so the script writes it private (`umask 077`) and removes a half-made copy.
There is no timer: no copy of the store leaves its machine on a schedule (D-31); each store has its replica instead.

```
backup-store.sh <data-dir> <out-dir> [port]       default port 6379; the copy is <out-dir>/store-<UTC time>
```

| | |
|---|---|
| Settings | `JREDIS_CLI`, the client (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`). `BACKEND_STORE_PASSWORD_FILE`, the file with the store's password, which the script sends on the client's standard input, never on its command line (every local user can read a command line) |
| Needs | the j-redis client; read access to the store's data directory and to the password file, so root |
| Runs | on the store's own machine: it reaches the store on `127.0.0.1` ([01 §3](../../docs/operations/01-deploy.md#3-j-redis-built-and-tested)) |
| Run by | a person, before an upgrade ([runbook §6](../../docs/operations/02-runbook.md#6-routine)) |

### store-restore-drill.sh

The monthly store drill, beside `restore-drill.sh` for MySQL. It starts a second j-redis on a copy made by
`backup-store.sh`, and compares what that instance holds with the running store. The all-time leaderboard lives only
in the store (MySQL keeps 90 days of matches), so this drill says whether the board survives the loss of the store.

| Family | Compared |
|---|---|
| `session` | the key count, the all-time board member by member with scores, and the names |
| `events` | the key count, the result stream and its group's pending entries, the inbox, the dead list and the deferred list |

A backup is one instance's (the store is split, D-7), so the fourth argument says which family to compare. With no
fourth argument, every family is compared, as on a single store such as a development one. (Comparing a family on
the instance that does not hold it used to pass, empty against empty: O-20.) The scratch server listens on
`127.0.0.1` only, with a random password in a 0600 configuration file, since it holds live session tokens. The
script stops it and removes its directory however the drill ends. Counts are exact only when nothing writes to the
source meanwhile.

```
store-restore-drill.sh <backup-dir> <source-port> <scratch-port> [session|events]
```

| | |
|---|---|
| Settings | `JREDIS_SERVER` (default the release's `jredis/bin/j-redis-server`, else `/opt/j-redis/bin/j-redis-server`), `JREDIS_CLI` (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`), `BACKEND_STORE_PASSWORD_FILE` (the source's password) |
| Needs | the j-redis server and client, `sha256sum`, a free port for the scratch server |
| Runs | on the store's own machine, on each store's primary; it reaches the source on `127.0.0.1` |
| Prints | `drill passed`, or `DRILL FAILED` and exit 1; exit 2 for a fourth argument that is not `session` or `events` |
| Run by | a person, monthly ([runbook](../../docs/operations/02-runbook.md#the-restore-drill)) |

## Failover

A promotion makes a replica the primary. A person runs it with one of these scripts; nothing promotes on its own
([D-8](../../docs/architecture/03-decision-log.md#d-8--failover-is-scripted-not-automatic)). Both scripts refuse to
promote while the old primary may still take writes, because two primaries taking writes is the failure they exist
to prevent. The steps are in [runbook §2](../../docs/operations/02-runbook.md#2-stateful-primary-failure).

### promote-store.sh

Promotes a j-redis replica to primary, on the replica's machine. The old primary is given as `host:port`. The
operator chooses one of two ways (D-95):

- `--old-is-down`: the operator says the old primary is down or cut off. The script refuses if the old primary
  answers as anything but a replica; an answer of `NOAUTH` (the password refused) also counts as up.
- `--demote-old`: the script first makes the old primary a replica of this one, given the address at which the old
  primary reaches this replica. It refuses an old primary it cannot reach, since one it cannot reach may still serve
  the clients that can (O-63).

Then it runs `REPLICAOF NO ONE` here, which raises the epoch and records it. It takes `replicaof` out of the
configuration file, since the role at start-up is the file's, and keeps the previous file as
`<file>.before-promotion-<UTC time>`. It prints the new role and epoch. Run on a primary already, it says so and
changes nothing. The clients are given both addresses and follow the promotion by themselves.

```
promote-store.sh <replica config file> <old primary host:port> --old-is-down
promote-store.sh <replica config file> <old primary host:port> --demote-old <this replica host:port>
```

| | |
|---|---|
| Settings | `JREDIS_CLI` (default the release's `jredis/bin/j-redis-cli`, else `/opt/j-redis/bin/j-redis-cli`). The password is the configuration file's `requirepass`, which both servers of a store share |
| Needs | root: it reads `port` and `requirepass` from the configuration file (`/etc/backend/store-<name>.conf`, `root:jredis`, 0640) and rewrites it, keeping a copy beside it in `/etc/backend/`. The j-redis client, `timeout` |
| Runs | on the store replica's machine (B for `session`, C for `events`; on two machines B for both, D-96); it reaches this machine's server on `127.0.0.1` |
| Returns | 0: promoted, or already a primary; 1: `REFUSED: …`, nothing promoted; 2: wrong arguments or no configuration file |
| Run by | a person ([runbook §2](../../docs/operations/02-runbook.md#a-j-redis-store-built-2026-09-29-d-34)) |

### promote-mysql.sh

Promotes MySQL's replica to primary, on the replica's machine. It works in this order:

1. **What a promotion would lose** (D-58, O-10). It prints the primary's last heartbeat that this replica applied,
   and its age. When that is older than `PROMOTE_STALE_SECONDS` (five minutes), or cannot be read, it refuses before
   touching anything, unless given `--stale-ok`. Compare the time with when the primary was lost: a promotion well
   after an outage is what `--stale-ok` is for. It then reads the account service's epoch, `account.ha_epoch`
   (`ACCOUNT_DB_NAME`): a server without that database, or without the table, is said and promoted with the
   backend's epoch alone; any other error, the `failover` account's grant missing among them, is refused here, before
   anything changes, naming the grant (O-103, D-117).
2. **The old primary must not serve.** With `--old-is-down`, it refuses if the old primary answers at all, even with
   an error that refuses the account or TLS (no connection, or no reply in 20 s, is no answer). With
   `--demote-old`, it refuses an old primary that does not answer, and fences one that does (D-95, O-64):
   `SET PERSIST super_read_only = ON`, which stops every write; then a wait of up to 60 s for this replica to apply
   every transaction the old primary executed; then `SET GLOBAL offline_mode = ON`, which closes every connection but
   an administrator's and refuses new ones (D-29). That closes the replica's own link too, so it comes last.
   `offline_mode` is not persisted.
3. **The replica catches up.** It waits up to 60 s for the replica to apply everything it received.
4. **The promotion.** It stops replicating and forgets its source (`STOP REPLICA`, `RESET REPLICA ALL`), becomes
   writable with `SET PERSIST` (which outranks the configuration file, so a restart keeps it), raises the failover
   epoch in `ha_epoch`, which the backend's processes follow, then the account service's, which it follows, when
   step 1 read it; and prints the epoch and the executed GTID set.

Run on a primary already, it prints the epoch and changes nothing.

```
promote-mysql.sh <old primary host:port> --old-is-down [--stale-ok]
promote-mysql.sh <old primary host:port> --demote-old [--stale-ok]
```

| | |
|---|---|
| Settings | `MYSQL_FAILOVER_CNF`, the `failover` account's option file (default `/etc/backend/credentials/mysql-failover.cnf`): `[client]` with the user, the password and `socket = /run/backend-mysql/mysqld.sock` (the file must name the socket, since the release's client defaults to `/tmp/mysql.sock`, O-50), and `[client_remote]` with the TLS settings, read only for the old primary. `BACKEND_DB_NAME` (`backend`). `ACCOUNT_DB_NAME` (`account`; empty: the account service's epoch is not asked). `MYSQL`, the client (`mysql`). `PROMOTE_STALE_SECONDS` (300; lower it for a rehearsal) |
| Needs | root, to read the option file; `mysql`, `timeout`. The account `failover`, with the grants of [01 §9](../../docs/operations/01-deploy.md#9-mysql) |
| Runs | on the MySQL replica's machine (C; on two machines B, D-96). It reaches this machine's server by its socket, and the old primary over TLS |
| Returns | 0: promoted, or already a primary; 1: `REFUSED: …`; 2: wrong arguments or an option file it cannot read |
| Run by | a person ([runbook §2](../../docs/operations/02-runbook.md#mysql-built-2026-09-29-d-35)) |

### check-promote.sh

Checks the two promotion scripts with stand-in clients and no server, in seconds:

- an old primary given without its port: refused;
- with `--old-is-down`: an old primary that answers by refusing the account or the password is refused; one that
  does not answer is promoted past; a store's old primary that answers as a replica is promoted past;
- with `--demote-old`: an old primary that does not answer, or that refuses, is refused;
- MySQL's handover in its order: read-only, the replica's wait for everything the old primary executed,
  `offline_mode`, the promotion;
- the store's handover: the old primary demoted, then this one promoted;
- a MySQL promotion raising the account service's epoch after the backend's; on a server without the account
  database, the backend's alone, the other said absent; with the epoch unreadable for want of the grant, refused
  before the old primary is touched; with `ACCOUNT_DB_NAME` empty, the account database not asked (O-103).

The real promotions are checked by the failover drills of `client/headless-drill.sh` (`MYSQL_FAILOVER=demote` and
`FAILOVER=1`).

```
scripts/check-promote.sh
PM=<another promote-mysql.sh> PS=<another promote-store.sh> scripts/check-promote.sh    a mutation check
```

| | |
|---|---|
| Settings | `PM` and `PS`, another `promote-mysql.sh` or `promote-store.sh` to check |
| Runs | anywhere, in seconds |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

## Certificates

The site has one certificate, made from the backend's own certificate authority (CA). How it is made, installed,
trusted by the app and renewed: [operations/06](../../docs/operations/06-certificates.md).

### private-ca.sh

The backend's own certificate authority (D-82). The site has no domain, so no public CA certifies it. The app ships
the root's certificate, `ca.pem`, and trusts it alone. The root's key, `ca.key`, never goes on a server.

| Command | What it does |
|---|---|
| `root <ca-dir>` | makes the root, once: `ca-dir/ca.key` (0600) and `ca-dir/ca.pem` |
| `issue <ca-dir> <out-dir> <name>...` | makes a key and a certificate for the names given, signed by the root: `out-dir/privkey.pem` and `out-dir/fullchain.pem`. Each name is an IP address (the site's public one, for 443 and 80) or a host name in lower case (an arena's TLS name, for 9001, such as `a1.arena.internal`) |
| `names <backend-arenas.conf>` | prints the TLS names in nginx's list of arenas, `backend-arenas.conf` (D-115), for `issue` |
| `show <certificate.pem>` | prints what a certificate names, who signed it, and until when |

The keys are RSA: the root's 3072 bits, a certificate's 2048, with SHA-256 signatures. RSA is the one key type that
Unity 2019's runtime can check (D-108; it was EC P-256 before; DOC-46). Nothing is overwritten: a root already in
`ca-dir`, or a key or certificate already in `out-dir`, refuses the run.

```
private-ca.sh root  <ca-dir>
private-ca.sh issue <ca-dir> <out-dir> <address> $(private-ca.sh names /etc/backend/nginx/backend-arenas.conf)
private-ca.sh show  <out-dir>/fullchain.pem
```

| | |
|---|---|
| Settings | `ROOT_DAYS` (3650), the root's validity in days; `DAYS` (398), a certificate's |
| Needs | `openssl`, the system's, 1.1.1 or later (RHEL 8's 1.1.1, RHEL 9's 3.x; D-103) |
| Runs | on the operators' machine that keeps the root's key, never on a server ([06 §2](../../docs/operations/06-certificates.md#2-making-the-root-once)) |
| Returns | 0; 1 with `refusing: …`; 2 with the usage, for wrong arguments |

### install-certificate.sh

```
install-certificate.sh <fullchain.pem> <privkey.pem>   by hand: from private-ca.sh, or any CA
install-certificate.sh                                 as a public CA's renewal hook (certbot's --deploy-hook sets
                                                       RENEWED_LINEAGE); the site uses no public CA since D-82
install-certificate.sh --check                         what each port serves now
install-certificate.sh --placeholder                   a self-signed stand-in, so that nginx can start before the
                                                       real certificate is there; devices refuse it
```

Installs a certificate for everything this machine serves to players, after checking it the way a device will. nginx
gets the PEM files and is reloaded, which drops no connection. nginx serves the certificate on 443 (logins,
purchases, the lobby) and on 9001 (match traffic, whose TLS it ends, D-82).

Nothing is installed unless every check passes, because each failure would otherwise be found by players, as a
connection their device refuses:

- the key belongs to the certificate;
- the chain reaches the backend's root (`/etc/backend/tls/ca.pem`, when it is there) through the file alone. Devices
  do not fetch a missing intermediate, so a file that holds only the leaf fails on phones while it works in a
  desktop browser. The root's expiry counts with the chain's (O-73);
- the certificate is valid now; a warning when fewer than `WARN_DAYS` remain;
- it names, each by its subject alternative name (SAN), which is all a device checks (O-74): the site's address
  (nginx's `server_name`); every TLS name in nginx's list of arenas (`backend-arenas.conf`, D-115); and the
  `ADVERTISE_HOST` of each arena that ends TLS itself. The address is required when the list names arenas (O-77).

How it installs:

- It installs the certificates the file holds and nothing else; a key inside the file is left out (O-75). It writes
  both files before either replaces its own.
- If nginx refuses them, it puts the previous files back, or leaves none where there were none. Once nginx accepts
  them, it does not keep the replaced key and certificate (O-76).
- Before nginx has ever started, it installs without the reload.
- `--check` says what 443 and 9001 serve now, for each name, and the days left.
- An arena that ends TLS itself, without the front (no `BACKEND_ADVERTISE_TLS_NAME`), gets a PKCS#12 keystore and its
  password in `TLS_DIR`, readable by root and the `backend` group, since the arena reads both itself at start
  (D-102). Each such arena serves the new certificate from its next restart, and the script says which still serve
  the old one.

| | |
|---|---|
| Settings | for the release's nginx: `NGINX_CONF` (default `/etc/backend/nginx/backend.conf`), `ARENAS_CONF` (default `/etc/backend/nginx/backend-arenas.conf`), `NGINX_TEST` (default `/opt/backend/nginx/sbin/nginx -t -q`, after it makes the directories nginx's unit would make, O-49), `NGINX_RELOAD` (default `systemctl reload backend-nginx`), `NGINX_RUNNING` (default `systemctl is-active --quiet backend-nginx`), `CA_FILE` (default `TLS_DIR/ca.pem` when it is there, else the system's roots). The others, for a machine laid out differently or a drill: `TLS_DIR` (`/etc/backend/tls`), `CREDENTIALS_DIR` (`/etc/backend/credentials`), `CONFIG_DIR` (`/etc/backend`, where the `arena-*.env` files are), `EDGE_PORT` (443), `STREAM_PORT` (9001), `WARN_DAYS` (14), `RENEWED_LINEAGE` (set by certbot). Its header has them all |
| Needs | root; `openssl` 1.1.1 or later, refused below it (D-103); the release's nginx |
| Runs | on every machine, the same certificate on each ([06 §4](../../docs/operations/06-certificates.md#4-installing-it-on-every-machine)) |
| Returns | 0: installed (or `--check` with every name served); 1: `refusing: …`, nothing installed; 2: wrong usage |

### check-certificates.sh

Checks `private-ca.sh` and `install-certificate.sh` on this machine, with openssl alone and nginx stood in for:

- the root made once and never overwritten, its key private;
- a certificate for the site's address and the seven names in the shipped list of arenas (`backend-arenas.conf`);
- refused before anything is installed: a certificate short of an arena's name or of the address, one from another
  root, and a key that is not the certificate's;
- with no root in `TLS_DIR`, the system's roots are the trust, and the backend's certificate is refused;
- installed, with the key private; and when nginx refuses the new files, the previous ones put back;
- the machine's OpenSSL (D-103): a version older than 1.1.1 refused, a later major version taken, a root in
  `SSL_CERT_DIR` kept out while the backend's root is in `TLS_DIR`, and a chain that does not verify refused in
  openssl's own words;
- the account service's `account-key.sh` on the same openssl (D-117): its two lines, the key PKCS #8 on P-256 by name
  (the form Java reads, not the SEC1 that OpenSSL 3's `genpkey` writes as DER), its public half the key's, a
  signature by the key verified by that half, and a new key each run. Two mutants, the key straight from `genpkey`
  and a public half from another key, each caught; on OpenSSL 3.0 here, 3.2.2 (UBI 9) and 1.1.1k (UBI 8).

```
scripts/check-certificates.sh
IMAGE=registry.access.redhat.com/ubi9/ubi:9.5 scripts/check-certificates.sh    # RHEL 9's OpenSSL 3.2.2
IMAGE=backend-ubi8-openssl scripts/check-certificates.sh    # RHEL 8's 1.1.1k: UBI 8 + openssl (header)
CA=<another private-ca.sh> IC=<another install-certificate.sh> AK=<another account-key.sh> scripts/check-certificates.sh    # a mutation check
```

| | |
|---|---|
| Settings | `IMAGE`, a container image to run the same checks in, on that image's openssl; `CA` and `IC`, another `private-ca.sh` or `install-certificate.sh` to check |
| Needs | `openssl`; with `IMAGE`, docker |
| Runs | anywhere, in a few seconds; its files in `TMPDIR`, removed at the end |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

## Logs

### rotate-nginx-logs.sh

Rotates nginx's logs ([D-85](../../docs/architecture/03-decision-log.md#d-85--nginxs-logs-rotated-by-a-timer-of-the-releases-own-and-kept-a-number-of-days),
O-45). For each of `access.log`, `stream.log` and `error.log` in `LOG_DIR` that holds anything:

1. it renames the file with a UTC stamp (`access.log.20261024T000500Z`);
2. it sends `USR1` to nginx's master (the process `PID_FILE` names), so that the master and its workers open fresh
   files, and gives them a second to do it;
3. it compresses the renamed files (`.gz`);
4. it deletes rotated files older than `KEEP_DAYS`. Age is counted in minutes (`-mmin`), not whole days.

It leaves an empty log alone, however old, and never deletes a live log. When nginx is not running, it rotates the
logs without a signal.

```
LOG_DIR=/var/log/backend-nginx PID_FILE=/run/backend-nginx/nginx.pid KEEP_DAYS=14 scripts/rotate-nginx-logs.sh
systemctl start backend-nginx-logs        # the same, now, on a machine
```

| | |
|---|---|
| Settings | `LOG_DIR`, `PID_FILE`, `KEEP_DAYS` (whole days, 1 to 365) |
| Needs | `gzip`, `find` (RHEL 9's base) |
| Runs | on every machine, nightly; a second or two |
| Returns | 0; 2 for a `KEEP_DAYS` that is not whole days from 1 to 365, before anything is renamed; 1 when a step fails (a rename, the compression or a deletion), so that the timer's unit shows it failed (O-72) |
| Run by | `backend-nginx-logs.timer` at 00:05 UTC on every machine, as root (nginx's master is root, and the logs are its) under the backup units' sandbox |

### check-nginx-logs.sh

Checks `rotate-nginx-logs.sh` on this machine, with the release's nginx on a loopback port (18080, or `PORT`) and a
log directory of its own:

- a request logged; the logs renamed with a UTC stamp and compressed, with the line in them;
- the next request in a fresh `access.log`, so nginx reopened its logs;
- a rotated file past `KEEP_DAYS` deleted and a newer one kept; a file an hour past `KEEP_DAYS` deleted and one an
  hour short kept;
- an empty log left alone, however old;
- `KEEP_DAYS=0` and `14.5` refused, with nothing rotated;
- nginx stopped, waited for, and the logs rotated all the same, without a signal;
- a compression that fails: exit 1 (O-72).

Its nginx's worker runs as root, since a temporary directory under root's is closed to any other user. The workers
running as `nginx` and reopening the logs under `LogsDirectory=`, and the unit with its timer, are what
`check-units-el9.sh` checks.

```
scripts/check-nginx-logs.sh [release-dir]                                default: target/release/backend-*/
ROTATE=<another rotate-nginx-logs.sh> scripts/check-nginx-logs.sh        a mutation check
```

| | |
|---|---|
| Settings | `PORT` (18080), `ROTATE` |
| Needs | a release (`make-release.sh`), `curl` |
| Runs | on this machine, in a few seconds; its files in `TMPDIR`, removed at the end |
| Prints | `ok` or `FAIL` per check, then `--- <n> failed`; exits non-zero on any failure |

## Drills

### backup-drill.sh

A live drill of the backups, on MySQL servers of its own:

- a primary on 3307 (binary log, GTIDs), seeded with a copy of `SEED_DB` and of the account service's `account_dev`
  (D-117) from the development MySQL (`dev-mysql.sh`, `127.0.0.1:3314` or `MYSQL_PORT`; started for the drill when it
  is not running, and stopped after; never the machine's own MySQL on 3306, O-40);
- a scratch server on 3309 (no binary log), which the proof restores onto.

It runs each backup script as production does, and checks what each must do and what each must refuse:

1. the binlog stream copying; the dump of both databases in one run, recorded once, naming both; writes to both
   after it, and a mistake in each at a known moment;
2. the copy off the site, encrypted, into a directory that stands in for the host;
3. the proof of both while a writer keeps writing, the accounts made after the dump in the account service's copy;
   a restore of each to the moment before the mistake;
4. the proof of both from off the site, each database's newest dump fetched; with the wrong key, refused and recorded
   as a failure (O-15); the last resort for each, the account service's with no ledger to reconcile;
5. refused, each one: a gap in the copies, a dump whose gems do not reconcile, a proof whose replay stops short of
   what was committed, a moment before the dump, a run whose second database fails (recorded as failed, naming it,
   though the first's dump was taken), and a proof with one database's dump missing (recorded so);
6. the scripts' own refusals (plan item 116): a proof without `SCRATCH_CNF` refused (O-65); a dump kept from another
   series leaving the new dump's binlogs (O-66), and another database's dump keeping those it starts from; a dump that
   records no position never named as one (O-67); a push off the site with no dump refused, and the host's copy kept
   (O-69);
7. the monthly drill's exact counts, once nothing writes, for each database.

It runs the scripts, not the units.

```
TMPDIR=<scratch directory> backend/scripts/backup-drill.sh
RELEASE=backend/target/release/backend-<version> TMPDIR=<scratch directory> backend/scripts/backup-drill.sh   # on MySQL 8.4
```

| | |
|---|---|
| Settings | `TMPDIR` (default `/tmp`), where its working directory goes. `SEED_DB` (default `backend_dev`) and `SEED_PASSWORD` (default `backend-dev-password`), the development MySQL's database and its `backend` user's password; `SEED_ACCOUNT_PASSWORD` (default `account-dev-password`), its `account` user's, for `account_dev`; `MYSQL_PORT` (3314), its port. `RELEASE`, a release directory: its `mysql/`, MySQL 8.4, runs the drill's own servers and is first on `PATH` for the scripts; unset, or a release without `mysql/`, the machine's |
| Needs | `mysqld`, `mysql`, `mysqldump`, `mysqlbinlog` (the release's with `RELEASE`, else `/usr/sbin/mysqld` and the machine's tools), `openssl`, `rsync`; ports 3307 and 3309 free (`MYSQL_FAILOVER` in `client/headless-drill.sh` uses 3307 too, so do not run both at once); the seed database at the current schema, which has `backup_run` (a drill or `./run-local.sh start` makes it) |
| Runs | on a development machine only |
| Prints | `ok` or `FAIL` per check, then `BACKUP DRILL PASSED` or `BACKUP DRILL FAILED: <n>`, with the working directory, which holds every step's log |

The rest of the stack is drilled by [`client/headless-drill.sh`](../../client/headless-drill.sh), against a release,
on the release's runtime, j-redis and MySQL when it carries them. Its ports are in the
[deploy README](../deploy/README.md#ports).
