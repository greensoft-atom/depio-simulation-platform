# 09 — The release, and everything it runs on

**Status:** designed 2026-10-05 (plan item 80,
[D-77](../architecture/03-decision-log.md#d-77--everything-the-backend-builds-and-runs-with-is-in-the-repository-and-in-the-release)).
What the backend is built with and runs on, and where each piece comes from. The
owner's direction, 2026-10-05: Java 21 only, MySQL 8.4 LTS
([Q-56](../requirements/01-scope-and-nfrs.md#7-open-questions)); the JDK, Maven,
MySQL and nginx packaged and deployed with the release instead of installed as
operating-system packages; and every component and binary the backend needs to be
built and run committed to the repository. The client's toolchain (.NET) is not
included: the owner chose the backend only.

## 1. The platform

| | Development | Production |
|---|---|---|
| Operating system | Ubuntu 24.04 | RHEL 9.x (9.5 expected) |
| glibc | 2.39 | 2.34, on every 9.x |
| CPU | x86-64 | x86-64 |

Every binary in the repository is chosen or built to run on **glibc 2.34**, so
that it runs on both: the JDK and MySQL are vendors' builds for older glibc
(Temurin's for 2.17, MySQL's "generic" for 2.28); nginx is compiled in a Rocky
Linux 9 container, never on the development machine, whose glibc 2.38 symbols
RHEL 9 lacks; the two libraries MySQL takes from the system come from RHEL 9's
own packages. Checked in Red Hat's UBI 9 image, RHEL 9's userspace with nothing
installed (§7).

## 2. What is in the repository: `vendor/`

| Directory | Version | From | Changed from upstream |
|---|---|---|---|
| `vendor/jdk-21/` | Temurin 21.0.12.1+1 | Adoptium's tarball, its SHA-256 checked | The image re-linked with `jlink --compress zip-6`, every module and tool kept: its `lib/modules` is 59 MB, not 136, under GitHub's 100 MB limit for a file. `jmods/` kept, so `jlink` can make runtimes. `src.zip` left out. |
| `vendor/maven-3.9/` | Apache Maven 3.9.16 | Apache's tarball, its SHA-512 checked | none |
| `vendor/mysql-8.4/` | MySQL Community 8.4.11 LTS | Oracle's "minimal" generic Linux tarball for glibc 2.28, SHA-256 pinned at download | Left out: the Japanese full-text dictionaries (`lib/mecab`, 130 MB), the static client libraries and headers. Added to `lib/private/`, which `mysqld` searches first: `libaio.so.1` and `libnuma.so.1` from RHEL 9's packages. |
| `vendor/nginx-1.30/` | nginx 1.30.5 | built from source (§5) | OpenSSL 3.5.9 LTS, PCRE2 10.49 and zlib 1.3.2 compiled in |
| `vendor/src/` | | the sources nginx is built from, as published, SHA-256 pinned | |
| `java21-offline/` | | every Java library the build uses | (existing, unchanged) |

`vendor/VERSIONS.md` lists each piece's upstream URL and checksum. Two scripts
keep it: `vendor/update.sh` fetches the pinned downloads, checks them and
derives the directories; `vendor/build-nginx.sh` compiles nginx in the
container. Neither is needed to build or run the backend: what they make is
committed.

**Why committed, and how:** the owner wants the repository alone to be enough,
on machines with no network. Every file stays under GitHub's 100 MB limit as it
is, so plain git holds it: no Git LFS server, no split files. The cost is the
repository's size, about 0.5 GB more, and every version bump adds to its
history.

## 3. The build, from the repository alone

`build-offline.sh`, at the repository's root: the committed JDK and Maven, `java21-offline` as
Maven's only repository (a mirror, without `-o`) and a local repository of its
own, so nothing comes from `~/.m2`; it builds `j-redis-service`, then the backend
with its tests (or without, `SKIP_TESTS=1`), then the release. It needs bash,
coreutils, findutils, sed, tar and gzip: no Java, no Maven, no network. The usual
development build, with the machine's own JDK and Maven, works as before.

## 4. The release

`make-release.sh` makes `backend-<version>/`, unpacked to `/opt/backend-<version>`
with `/opt/backend` pointing at it (operations/01 §2):

| Path | What | Size |
|---|---|---|
| `runtime/` | The Java 21 runtime every Java process runs on, made by `jlink` from `vendor/jdk-21/jmods` (§4.1) | 63 MB |
| `lib/{arena,platform,worker,gateway}/` | each process type's jars, as before | |
| `jredis/` | j-redis's server, client and tools: `bin/` (its launchers, run on `$JAVA_HOME`), `lib/`, `conf/j-redis.conf` (the template) | 11 MB |
| `mysql/` | `vendor/mysql-8.4/` as committed | 251 MB |
| `nginx/` | `vendor/nginx-1.30/` as committed: `sbin/nginx`, `conf/mime.types` | 8 MB |
| `systemd/`, `env/`, `scripts/` | as before; the units and the scripts name the paths above | |
| `mysql-conf/`, `nginx-conf/` | their configuration (`deploy/mysql`, `deploy/nginx`), under names apart from the binaries | |
| `VERSIONS.md` | what is inside, with versions | |

### 4.1 The runtime

Its modules are named, not computed: `java.se` (every `java.*` module) and the
`jdk.*` ones the processes use: `jdk.httpserver` (the platform's API and every
metrics endpoint), `jdk.unsupported` (Netty, JCTools), `jdk.jfr`, `jdk.net`,
`jdk.crypto.ec` (the elliptic curves TLS negotiates, which no static reading
finds), and `jdk.jcmd`, `jdk.management` and `jdk.management.agent`, to take a heap
dump or a recording from a running process. Computing them was the first design:
`jdeps` cannot, because the libraries are explicit modules whose optional
requirements are absent (Netty's `codec-marshalling` requires JBoss Marshalling),
and a failure there had left `jdk.httpserver` out unnoticed. So `jdeps` reads our
own jars instead, and a module they use outside the list stops the release
(mutation-checked: without `jdk.httpserver`, refused). Linked with `--strip-java-debug-attributes --no-man-pages --no-header-files --compress zip-6` (not `--strip-debug`, which also strips native symbols with `objcopy`: a minimal RHEL 9 has no binutils, and the build failed there, found by the install guide's check; the JDK's native libraries come stripped already, so the runtime is the same size): 63 MB, 31 modules. Every
unit's `ExecStart` is `/opt/backend/runtime/bin/java`, the stores' too.

What the list leaves out, a process can still need: a provider found by name
(`jdk.crypto.cryptoki`, PKCS#11, keys in a hardware module) is seen by no static
reading, and the tests pass on the full JDK. `crypto-examples`' `RuntimeProbe`
runs on the release's runtime: on 2026-10-05, every algorithm the backend may use
held there, and PKCS#11 was absent
([development/04 §5](../development/04-crypto.md#5-running-them)).

### 4.2 What the host still provides

The binaries need only glibc, `libstdc++` and `ncurses-libs` (the `mysql`
client): checked in UBI 9 minimal, which has them. The scripts and units need
what any RHEL 9 server installation has and that minimal image does not: systemd,
util-linux (`flock`), `tar` and `gzip` (unpacking a release), `findutils`,
`procps-ng`, OpenSSL's command line (the backups' encryption), and, on the backup
machine only, `openssh-clients` and `rsync` (the copy off the site). These stay
the operating system's.

## 5. nginx, compiled for RHEL 9

`vendor/build-nginx.sh` runs in a `rockylinux/rockylinux:9` container: the
compiler, `make` and `perl` installed there, nothing on the host. Configured with
`--prefix=/opt/backend/nginx` and its paths outside the release
(`--conf-path=/etc/backend/nginx/nginx.conf`, logs under `/var/log/backend-nginx`,
temporary files under `/var/cache/backend-nginx`, the pid under `/run/backend-nginx`),
`--with-http_ssl_module --with-http_v2_module`, and OpenSSL, PCRE2 (with its JIT)
and zlib from `vendor/src/` compiled in, so it links nothing the system's
OpenSSL updates would change. Its modules are the ones the configuration uses
(`deploy/nginx/`); `nginx -V` prints them. The release ships `nginx.conf`, the
main file, beside the two the distribution's nginx used to include, and
`backend-nginx.service` runs it.

A security fix to nginx or OpenSSL is ours to take: a new version in
`vendor/src/`, `build-nginx.sh` again, a release. The runbook says so.

## 6. MySQL, from the release

`backend-mysql.service` runs `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf`
as the system user `mysql` (created by the install, as the package did), its data
in `/var/lib/backend-mysql`. The configuration of operations/01 §9 moves there
unchanged. The scratch server of the weekly proof (§10) runs the same binary. The
scripts that call `mysql`, `mysqldump` and `mysqlbinlog` put `../mysql/bin`,
beside themselves in the release, first on `PATH`; run from the source tree they
use whatever the machine has.

On Ubuntu, AppArmor's profile for the distribution's `/usr/sbin/mysqld` does not
apply to `/opt/backend/mysql/bin/mysqld`: the rules shipped for it (O-16) are no
longer needed. On RHEL 9, SELinux runs a service started from `/opt` unconfined.

## 7. How it is verified

1. **The repository builds itself with no network**: a container with no network
   interface (`docker run --network none`) and only the repository builds the
   release with `build-offline.sh`.
2. **The release runs on RHEL 9 with nothing installed**:
   `backend/scripts/check-release-el9.sh` runs it in `ubi9/ubi-minimal:9.5` (another
   tag with `IMAGE=`): every binary's libraries found; MySQL initialised and
   started; j-redis; the platform migrating it and serving a registration and a
   login; the gateway, a worker and an arena (on Netty's native epoll); nginx
   proxying to the platform over HTTP and HTTPS; and `nginx -t` on the shipped
   configuration. Mutation-checked: a release without `libaio` fails it.
3. **The units run on RHEL 9's systemd**: `backend/scripts/check-units-el9.sh` runs
   a release and the repository's `deploy/` in `ubi9/ubi-init:9.5`, systemd 252 its
   first process; installs as the deploy document says, starts `backend-mysql`
   (`Type=notify`), `backend-store@session`, `backend-platform` and `backend-nginx`,
   registers an account through nginx over HTTPS, reloads nginx and stops them all.
   Mutation-checked: a store unit naming a jar that is not there fails it. One thing
   a container cannot show: `LoadCredential` for a service that is not root
   (operations/01 §2); the check gives the platform its secrets by a drop-in.
4. **The drills** run every process on the release's runtime, and their own MySQL
   servers on the release's MySQL 8.4: every scenario, the MySQL failover and the
   backup drill, which had only ever run on 8.0.
5. **The backend's tests** against a MySQL 8.4 server from the release.
