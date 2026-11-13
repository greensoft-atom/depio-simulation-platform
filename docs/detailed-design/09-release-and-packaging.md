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

Every binary in the repository is chosen or built to run on **glibc 2.28**, RHEL 8's (D-100), so that it runs on
both and on RHEL 8: the JDK and MySQL are vendors' builds for older glibc (Temurin's for 2.17, MySQL's "generic"
for 2.28); nginx is compiled in a Rocky Linux 8 container, never on the development machine, whose glibc 2.38
symbols RHEL lacks; the two libraries MySQL takes from the system come from RHEL 8's own packages, RHEL 9's
`libnuma` needing 2.33, and so does a third, `libcrypt.so.1`, which RHEL 9 has only in an extra package (D-110).
Checked in Red Hat's UBI 8 and UBI 9 images, RHEL's userspace with nothing installed, and on RHEL 9's kernel with
SELinux enforcing (§7). **The units start on systemd 239**, RHEL 8's, since they carry no secret (D-102; checked in UBI 8.10): three of
their sandbox settings are newer, `ProtectHostname=` (242), `ProtectKernelLogs=` (244) and `ProtectClock=` (245), and
239 ignores them with a warning; RHEL 9's 252 applies them all. **The certificate scripts take OpenSSL 1.1.1**,
RHEL 8's, as well as RHEL 9's 3.x (D-103, O-94 fixed), and the install guide runs as written in UBI 8.10 as in UBI
9.5 (Q-63).

## 2. What is in the repository: `vendor/`

| Directory | Version | From | Changed from upstream |
|---|---|---|---|
| `vendor/jdk-21/` | Temurin 21.0.12.1+1 | Adoptium's tarball, its SHA-256 checked | The image re-linked with `jlink --compress zip-6`, every module and tool kept: its `lib/modules` is 59 MB, not 136, under GitHub's 100 MB limit for a file. `jmods/` kept, so `jlink` can make runtimes. `src.zip` left out. |
| `vendor/maven-3.9/` | Apache Maven 3.9.16 | Apache's tarball, its SHA-512 checked | none |
| `vendor/mysql-8.4/` | MySQL Community 8.4.11 LTS | Oracle's "minimal" generic Linux tarball for glibc 2.28, SHA-256 pinned at download | Left out: the Japanese full-text dictionaries (`lib/mecab`, 130 MB), the static client libraries and headers. Added to `lib/private/`, which `mysqld` searches first: `libaio.so.1` and `libnuma.so.1` from RHEL 8's packages (D-100), and `libcrypt.so.1` from RHEL 8's libxcrypt, for the SASL PLAIN plugin (`sasl2/libplain.so`; D-110). |
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
own, so nothing comes from `~/.m2`; it runs `dev-jredis.sh build` (j-redis-service) and then
`dev-backend.sh build` (the backend, then the release), each with its tests (or without,
`SKIP_TESTS=1`) (D-92). It needs bash,
coreutils, findutils, sed, tar and gzip: no Java, no Maven, no MySQL, no network.

Everything it writes is inside the checkout, in `work/`
([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)):

| In `work/` | What | Written by |
|---|---|---|
| `settings.xml` | Maven's settings: the bundle, at this checkout's path, as the mirror of every repository; `work/m2` as the local repository | `dev-env.sh`, each time it is sourced |
| `m2/` | Maven's local repository, filled from the bundle | Maven |
| `tmp/` | `TMPDIR`, and `java.io.tmpdir` for Maven and the tests' JVMs | Maven, the tests, MySQL |
| `mysql/` | the development MySQL: `vendor/`'s 8.4, on 127.0.0.1:3314, its data, socket and logs | `dev-mysql.sh` |
| `run/` | a run by hand: each process's log and pid, the store's files, the arena's spool | `dev-jredis.sh`, `dev-backend.sh` |
| `nginx/` | the release's nginx in front of a run by hand: its configuration rewritten from the release's, its logs and pid, the certificate and its root (D-92) | `dev-nginx.sh` |
| `probe/` | a throwaway copy that fetches the dependency plugin from the bundle before the offline release | `dev-backend.sh build` |
| `el9/` | the Rocky 9 guest of the SELinux check: its image, disk, seed, key and console log | `check-selinux-el9.sh` |

`dev-env.sh`, sourced, makes the environment every script runs in, and a shell
too: `JAVA_HOME` and `PATH` on `vendor/`'s JDK and Maven, Maven reading
`work/settings.xml` (by `MAVEN_ARGS`) and neither `/etc/mavenrc` nor `~/.mavenrc`
(`MAVEN_SKIP_RC`), `java.io.tmpdir` in `work/tmp`, and the tests' `JDBC_URL` on
the development MySQL; and, since D-92, a run by hand's ports and address, the same for each of
its scripts: `MYSQL_PORT` 3314, `STORE_PORT` 6380, `PLATFORM_PORT` 8080, `GATEWAY_PORT` 8081,
`ARENA_PORT` 9021, `ARENA_TLS_NAME` `a1.arena.internal`, `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT` and
`NGINX_MATCH_PORT` 80, 443 and 9001, and `PUBLIC_ADDRESS`, the machine's first address unless
given. The machine's own Java, Maven and MySQL are neither used nor changed. With the tests,
`dev-backend.sh build` starts the development MySQL and stops it after, unless it was running or
`JDBC_URL` names another server.

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
| `COMMIT` | the commit it was made from, `-dirty` when the tree it was made from differs from it (O-84) | |

`make-release.sh` also packs it as `backend-<version>.tar.gz`, beside the directory.

### 4.1 The runtime

Its modules are named, not computed: `java.se` (every `java.*` module but `java.smartcardio`) and the
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

The binaries need, of the system, its C library and the C++ compiler's two: glibc 2.28 or later (every binary; the
runtime alone 2.15), GCC 7's `libstdc++` or later (`mysqld`'s CXXABI 1.3.11) and `libgcc_s` (MySQL's), and
`ncurses-libs` 6 (`libncurses.so.6` and `libtinfo.so.6`, the `mysql` client), on x86-64: read from the binaries the
release runs and every library they load (`objdump -T`, `readelf -d`), checked in UBI 8 and UBI 9 minimal, which
have them. glibc and `libstdc++` keep every older version's symbols in their newer releases, so a newer system runs
the binaries and an older one does not; ncurses keeps its soname 6, which RHEL 8 and 9 both ship. Everything else
they load is in the release: OpenSSL, PCRE2 and zlib compiled into nginx, MySQL's `lib/private` (its OpenSSL and
protobuf, RHEL 8's `libaio` and `libnuma`; its Kerberos, LDAP and SASL only for authentication plugins the site does
not load, and RHEL 8's `libcrypt.so.1` for SASL's PLAIN plugin, as RHEL 9 has only `libcrypt.so.2` unless
`libxcrypt-compat` is installed, D-110). The runtime's desktop libraries name X11's and ALSA's, which a server process never loads (headless).

**No symbolic link**, in `vendor/` or a release ([D-106](../architecture/03-decision-log.md#d-106--nothing-in-the-repository-or-a-release-is-a-symbolic-link)): each link upstream's archives carry (MySQL's `lib/`, the JDK's `legal/`) is a copy of the file it named, made by
`vendor/update.sh`, and `make-release.sh` does the same to the runtime's `legal/`, which `jlink` makes of links. A copy
that does not keep links, Git for Windows by default, leaves a text file in each one's place, and `mysqld` stops on
`libaio.so.1`: "file too short". `NoLinksTest` checks the checkout and `check-release-el9.sh` the release.

**The C library stays the system's**, by choice (the owner asking, 2026-11-02, whether the binaries could carry
everything, whatever the host's version). A binary that carried its own glibc would carry its loader too and still
load the system's NSS modules (users, groups, host names) and locale data, built for the system's glibc, on which a
different one fails; MySQL and the JDK have no static build; nginx alone could be static (musl), which frees nothing
while MySQL needs glibc. So a host other than RHEL 9 needs x86-64 (`uname -m`), glibc 2.28 or later
(`ldd --version`), the two libraries above, for the units systemd 245 or later, every sandbox setting applied
(`systemctl --version`, §1; 239 starts them), and OpenSSL 1.1.1 or later for the certificate scripts (`openssl version`, D-103);
checked on RHEL 8 and 9's userspaces alone.

The scripts and units need
what any RHEL 9 server installation has and that minimal image does not: systemd,
util-linux (`flock`), `tar` and `gzip` (unpacking a release), `findutils`,
`procps-ng`, OpenSSL's command line (the backups' encryption), and, on the backup
machine only, `openssh-clients` and `rsync` (the copy off the site). These stay
the operating system's.

## 5. nginx, compiled for glibc 2.28

`vendor/build-nginx.sh` runs in a `rockylinux/rockylinux:8` container (D-100; Rocky 9 until 2026-11-02): the
compiler, `make` and `perl` installed there, nothing on the host. Configured with
`--prefix=/opt/backend/nginx` and its paths outside the release
(`--conf-path=/etc/backend/nginx/nginx.conf`, logs under `/var/log/backend-nginx`,
temporary files under `/var/cache/backend-nginx`, the pid under `/run/backend-nginx`),
`--with-http_ssl_module --with-http_v2_module`, and since
[D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name)
`--with-stream --with-stream_ssl_module`, the TCP proxy on 9001 that ends match traffic's TLS
and passes each connection to the arena its handshake names. OpenSSL, PCRE2 (with its JIT) and zlib from
`vendor/src/` are compiled in, so it links nothing the system's OpenSSL updates would
change. Its modules are the ones the configuration uses (`deploy/nginx/`); `nginx -V`
prints them. The release ships `nginx.conf`, the main file, beside the files it
includes, and `backend-nginx.service` runs it.

A security fix to nginx or OpenSSL is ours to take: a new version in
`vendor/src/`, `build-nginx.sh` again, a release. The runbook says so.

## 6. MySQL, from the release

`backend-mysql.service` runs `/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf`
as the system user `mysql` (created by the install, as the package did), its data
in `/var/lib/backend-mysql`. The configuration of operations/01 §9 moves there
unchanged. The scratch server of the weekly proof (operations/01 §10) runs the same binary. The
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
   tag with `IMAGE=`): every native file's libraries found, all 249 ELF files in the release, by the system's loader
   (D-110: until then seven programs; the runtime's desktop libraries may lack X11's and ALSA's alone); every MySQL
   program started; MySQL initialised and started, and every plugin, component and client plugin loaded by the server; j-redis; the platform migrating it and serving a registration and a
   login; the gateway, a worker and an arena (on Netty's native epoll); nginx
   proxying to the platform over HTTP and HTTPS; and `nginx -t` on the shipped
   configuration. Mutation-checked: a release without `libaio` fails it. The same in `ubi8/ubi-minimal:8.10`
   (D-100): every binary on glibc 2.28, which a release with RHEL 9's `libnuma` and a Rocky 9 nginx failed.
   Since D-110, run on every RHEL 9 release, UBI 9 minimal 9.0 to 9.9, and on Rocky 9. It found SASL's
   `libplain.so` needing `libcrypt.so.1`, which RHEL 9 lacks (O-99). Mutation-checked: Ubuntu's `libnuma` in the
   release (glibc 2.38), a program and a plugin with a symbol the loader cannot bind, and a desktop library lacking
   a library that is not X11's, each fails it.
3. **The release on RHEL 9's kernel, SELinux enforcing** (D-110): `backend/scripts/check-release-guest-el9.sh` boots
   the Rocky 9 guest `check-selinux-el9.sh` makes, on a layer thrown away after, and sets MySQL up from the release
   as the install guide does: `backend-mysql.service` started by systemd, TLS over TCP, InnoDB's native AIO on the
   guest's kernel, the binary log read by `mysqlbinlog` and a dump by `mysqldump`, `mysqld` in `unconfined_service_t`,
   no denial. Then `check-release-el9.sh`'s checks in the guest, as root.
4. **The units run on RHEL 9's systemd**: `backend/scripts/check-units-el9.sh` runs
   a release and the repository's `deploy/` in `ubi9/ubi-init:9.5`, systemd 252 its
   first process; installs as the deploy document says, starts `backend-mysql`
   (`Type=notify`), `backend-store@session`, `backend-platform` and `backend-nginx`,
   registers an account through nginx over HTTPS, reloads nginx and stops them all.
   Since D-82 as one machine's front: the arena on 9011 behind nginx's 9001, a
   certificate from `private-ca.sh` and its root trusted alone, a match connection
   verified as `a1.arena.internal` and seen passed to the arena, and nothing on 80. Then
   operations/07's commands: a broken nginx configuration refused by the reload, MySQL and the
   store each restarted under a running platform, units stopped by pattern and started by name.
   Mutation-checked: a store unit naming a jar that is not there fails it. Since
   D-102 the secrets are where a machine has them, in the settings files, and
   checked: no unit naming a credential, every settings file root's 0600, no secret
   in `systemctl show`, neither the platform's environment nor its file readable by
   another user; and an arena of its own TLS serving, verified, the keystore
   `install-certificate.sh` wrote (until D-102 the check gave the processes their
   secrets by a drop-in of its own: systemd in a container cannot deliver a credential),
   AES-256 with a SHA-256 MAC whichever OpenSSL made it (D-103). Run in UBI 8.10 too.
5. **The certificate scripts on each OpenSSL** (D-103): `backend/scripts/check-certificates.sh`
   on this machine's 3.0.13, and with `IMAGE=` in UBI 9.5 (3.2.2) and in UBI 8.10 with `openssl`
   added (1.1.1k), every check the same on each.
6. **The drills** run every process on the release's runtime, and their own MySQL
   servers on the release's MySQL 8.4: every scenario, the MySQL failover and the
   backup drill, which had only ever run on 8.0.
7. **The backend's tests** against a MySQL 8.4 server from the release.
