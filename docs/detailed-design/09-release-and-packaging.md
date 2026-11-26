# 09 — The release, and everything it runs on

This page says what the backend is built with, what it runs on, and where each piece comes from. It is for
engineers who build a release, install one, or move a version of the JDK, Maven, MySQL or nginx. It covers the
target systems (§1), the committed tools in `vendor/` (§2), the build from the repository alone (§3), what a
release contains (§4), how nginx is compiled (§5), how MySQL runs from the release (§6), and how all of it is
checked (§7). Words such as *run by hand*, *`work/`*, *drill* and *mutation check* are defined in the
[glossary](../glossary.md#9-building-checking-and-running).

**Status:** designed 2026-10-05 (plan item 80,
[D-77](../architecture/03-decision-log.md#d-77--everything-the-backend-builds-and-runs-with-is-in-the-repository-and-in-the-release)).

The owner's direction, 2026-10-05:

- Java 21 only, and MySQL 8.4 LTS ([Q-56](../requirements/01-scope-and-nfrs.md#7-open-questions)).
- The JDK, Maven, MySQL and nginx are packaged and deployed with the release. They are not installed as
  operating-system packages.
- Every component and binary that the backend needs to be built and run is committed to the repository.
- The client's toolchain (.NET) is not included: the owner chose the backend only.

## 1. The platform

| | Development | Production |
|---|---|---|
| Operating system | Ubuntu 24.04 | RHEL 9.x (9.5 expected) |
| glibc | 2.39 | 2.34, on every 9.x |
| CPU | x86-64 | x86-64 |

**Every binary in the repository runs on glibc 2.28**, the version of RHEL 8 (D-100). So each one runs on the
development machine, on RHEL 9 and on RHEL 8. Each piece reaches that floor in its own way:

| Piece | How it runs on glibc 2.28 |
|---|---|
| The JDK | Temurin's build, made for glibc 2.17 |
| MySQL | Oracle's "generic" build for glibc 2.28 |
| nginx | Compiled in a Rocky Linux 8 container, never on the development machine. A binary built there would need symbols from glibc 2.38, which RHEL does not have. |
| `libaio.so.1` and `libnuma.so.1`, the two libraries MySQL takes from the system | Copied from RHEL 8's own packages. RHEL 9's `libnuma` needs glibc 2.33. |
| `libcrypt.so.1`, for MySQL's SASL PLAIN plugin | Copied from RHEL 8's libxcrypt. RHEL 9 has this library only in an extra package (D-110). |

All of this is checked in Red Hat's UBI 8 and UBI 9 images, which are RHEL's userspace with nothing installed. It
is also checked on RHEL 9's kernel with SELinux enforcing (§7).

**The units start on systemd 239**, the version of RHEL 8 (checked in UBI 8.10). They can, because they carry no
secret (D-102). Three of their sandbox settings are newer than 239: `ProtectHostname=` (systemd 242),
`ProtectKernelLogs=` (244) and `ProtectClock=` (245). systemd 239 ignores these three and logs a warning. RHEL 9's
systemd 252 applies them all.

**The certificate scripts take OpenSSL 1.1.1**, the version of RHEL 8, as well as RHEL 9's 3.x (D-103; defect
O-94 fixed). The install guide runs as written in UBI 8.10, as it does in UBI 9.5 (Q-63).

## 2. What is in the repository: `vendor/`

| Directory | Version | From | Changed from upstream |
|---|---|---|---|
| `vendor/jdk-21/` | Temurin 21.0.12.1+1 | Adoptium's tarball; its SHA-256 is checked | The image is re-linked with `jlink --compress zip-6`, and every module and tool is kept. Its `lib/modules` is 59 MB instead of 136 MB, so it fits under GitHub's 100 MB limit for a file. `jmods/` and `NOTICE` are kept, so `jlink` can make runtimes. `src.zip` is left out. |
| `vendor/maven-3.9/` | Apache Maven 3.9.16 | Apache's tarball; its SHA-512 is checked | none |
| `vendor/mysql-8.4/` | MySQL Community 8.4.11 LTS | Oracle's "minimal" generic Linux tarball for glibc 2.28; its SHA-256 was pinned at download | Left out: the Japanese full-text dictionaries (`lib/mecab`, 130 MB), the static libraries, `include/`, `pkgconfig`, `mysql_config`, and `share/dictionary.txt` (the word list of the password-strength component, which is not used). Added to `lib/private/`, which `mysqld` searches first: `libaio.so.1` and `libnuma.so.1` from RHEL 8's packages (D-100), and `libcrypt.so.1` from RHEL 8's libxcrypt, for the SASL PLAIN plugin (`sasl2/libplain.so`; D-110). `lib/private/EL8-LIBRARIES.txt` names the three packages. |
| `vendor/nginx-1.30/` | nginx 1.30.5 | built from source (§5) | OpenSSL 3.5.9 LTS, PCRE2 10.49 and zlib 1.3.2 compiled in |
| `vendor/src/` | | the sources nginx is built from, as published; each SHA-256 is pinned | |
| `java21-offline/` | | every Java library the build uses | (existing, unchanged) |

`vendor/VERSIONS.md` lists each piece's upstream URL and checksum. Two scripts keep `vendor/` up to date:

| Script | What it does |
|---|---|
| `vendor/update.sh` | Fetches the pinned downloads, checks them, and derives the directories. It also turns every symbolic link in the archives into a copy of its file (§4.2). |
| `vendor/build-nginx.sh` | Compiles nginx in a container (§5). |

Neither script is needed to build or run the backend, because what they make is committed. How to move a version
is in [vendor/README.md](../../vendor/README.md).

**Why the binaries are committed.** The owner wants the repository alone to be enough, on machines with no
network. Every file is under GitHub's 100 MB limit as it is, so plain git holds it: there is no Git LFS server and
no split file. The cost is the repository's size, about 0.5 GB more, and every version bump adds to its history.

## 3. The build, from the repository alone

`build-offline.sh`, at the repository's root, builds everything. It needs bash, coreutils, findutils, sed, tar and
gzip. It needs no Java, no Maven, no MySQL and no network. With every test it takes about an hour.

What it does:

1. It sources `dev-env.sh` (below). So it uses the committed JDK and Maven, `java21-offline` as Maven's only
   repository (a mirror, not Maven's offline mode `-o`), and a local Maven repository of its own. Nothing comes
   from `~/.m2`.
2. It runs `dev-jredis.sh build`, which builds j-redis-service.
3. It runs `dev-backend.sh build`, which builds the backend and then the release (D-92).

Each step runs its tests, unless `SKIP_TESTS=1` is set. `WORK=<dir>` makes it write to another folder instead of
`work/`.

Everything the build writes is inside the checkout, in `work/`
([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)):

| In `work/` | What | Written by |
|---|---|---|
| `settings.xml` | Maven's settings: the bundle, at this checkout's path, as the mirror of every repository; `work/m2` as the local repository | `dev-env.sh`, each time it is sourced |
| `m2/` | Maven's local repository, filled from the bundle | Maven |
| `tmp/` | `TMPDIR`, and `java.io.tmpdir` for Maven and the tests' JVMs | Maven, the tests, MySQL |
| `mysql/` | the development MySQL: `vendor/`'s 8.4, on 127.0.0.1:3314, with its data, socket and logs | `dev-mysql.sh` |
| `run/` | a run by hand: each process's log and pid, the store's files, the arena's spool | `dev-jredis.sh`, `dev-backend.sh` |
| `nginx/` | the release's nginx in front of a run by hand: its configuration rewritten from the release's, its logs and pid, the certificate and its root (D-92) | `dev-nginx.sh` |
| `probe/` | a throwaway copy that fetches Maven's dependency plugin from the bundle, before the release is built offline | `dev-backend.sh build` |
| `el9/` | the Rocky 9 guest of the SELinux check: its image, disk, seed, key and console log | `check-selinux-el9.sh` |
| `unity/` | Unity 2019.4.28f1's Linux editor, downloaded once (1.8 GB), for the client's checks | `client/unity2019-check.sh` |

**`dev-env.sh`** is sourced, not run. It makes the environment that every script runs in, and a shell can source
it too. The machine's own Java, Maven and MySQL are neither used nor changed. It sets:

| Variable or setting | Value |
|---|---|
| `JAVA_HOME`, `PATH` | `vendor/`'s JDK and Maven |
| `MAVEN_ARGS` | `-s work/settings.xml`, and `java.io.tmpdir` in `work/tmp` for the tests' JVMs |
| `MAVEN_SKIP_RC` | set, so Maven reads neither `/etc/mavenrc` nor `~/.mavenrc` |
| `MAVEN_OPTS`, `TMPDIR` | `java.io.tmpdir` and the temporary folder in `work/tmp` |
| `JDBC_URL` | the tests' database on the development MySQL, unless it is already set |
| `MYSQL_PORT` | 3314 |
| `STORE_PORT` | 6380 |
| `PLATFORM_PORT`, `GATEWAY_PORT` | 8080, 8081 |
| `ARENA_PORT`, `ARENA_TLS_NAME` | 9021, `a1.arena.internal` |
| `NGINX_HTTP_PORT`, `NGINX_HTTPS_PORT`, `NGINX_MATCH_PORT` | 80, 443, 9001 |
| `PLAIN` | 0; with `PLAIN=1`, nginx and the arena run without TLS, for development alone (D-101) |
| `PUBLIC_ADDRESS` | the machine's first IPv4 address, unless given; 127.0.0.1 if it has none |

The ports and the address (since D-92) are the same for each script of a run by hand. Each one can be set before
sourcing. `dev-env.sh` also refuses a checkout whose path holds a character the build cannot carry, such as a
space, and a copy whose JDK, Maven or MySQL lost its execute permission.

With the tests, `dev-backend.sh build` starts the development MySQL and stops it afterwards. It does not when
MySQL was already running, or when `JDBC_URL` names another server.

## 4. The release

`make-release.sh` makes the folder `backend-<version>/`. An install unpacks it to `/opt/backend-<version>`, with
`/opt/backend` pointing at it (operations/01 §2). The sizes below were measured on 2026-11-25, on a release made
that day:

| Path | What | Size |
|---|---|---|
| `runtime/` | The Java 21 runtime that every Java process runs on, made by `jlink` from `vendor/jdk-21/jmods` (§4.1) | 63 MB |
| `lib/{arena,platform,worker,gateway}/` | Each process type's jars, one folder each (24 to 29 jars) | 45 MB |
| `jredis/` | j-redis's server, client and tools: `bin/` (its launchers, which run on `$JAVA_HOME`), `lib/`, and `conf/j-redis.conf` (the template) | 11 MB |
| `account/lib/` | the account service's jar and its runtime dependencies (D-117), built from `account-service/` as `jredis/` is from `j-redis-service/`: 14 jars | 17 MB |
| `mysql/` | `vendor/mysql-8.4/` as committed | 292 MB |
| `nginx/` | `vendor/nginx-1.30/` as committed: `sbin/nginx`, `conf/mime.types`, `LICENSE`, `BUILD.txt` | 8.5 MB |
| `systemd/` | the units and timers, from `deploy/systemd/`; they name the paths above | |
| `env/` | the example settings files, from `deploy/env/` | |
| `scripts/` | the operational scripts (below); they find `mysql/`, `jredis/` and `runtime/` beside them | |
| `mysql-conf/` | MySQL's configuration examples, from `deploy/mysql/`: `backend.cnf.example`, `scratch-my.cnf.example` | |
| `nginx-conf/` | nginx's configuration, from `deploy/nginx/`: the six files listed in §5 | |
| `VERSIONS.md` | what is inside, with versions: `vendor/VERSIONS.md`, then the backend's version and commit, j-redis's version, and the runtime's version and modules | |
| `COMMIT` | the commit the release was made from, with `-dirty` when the tree differed from that commit (O-84) | |

The configuration folders have names apart from the binaries' folders. `make-release.sh` also packs the folder as
`backend-<version>.tar.gz`, beside it (184 MB on 2026-11-25). Before it packs, it turns every symbolic link in the
release into a copy of its file (§4.2).

The scripts in `scripts/` are the ones the backup units, the log rotation and the operators run from
`/opt/backend/scripts`: `backup-mysql.sh`, `backup-store.sh`, `binlog-stream.sh`, `restore-drill.sh`,
`store-restore-drill.sh`, `install-certificate.sh`, `promote-store.sh`, `promote-mysql.sh`, `record-backup.sh`,
`restore-proof.sh`, `backup-offsite.sh`, `fetch-offsite.sh`, `private-ca.sh`, `rotate-nginx-logs.sh`,
`check-bind.sh`, and the account service's `account-key.sh`, which makes its signing key (10 §3.4).

The jars go in one folder per process, not one shared folder. The `handoff` module's dependencies are what keep
the MySQL driver off an arena's classpath, and a shared folder would put it back.

### 4.1 The runtime

**The runtime's modules are named in `make-release.sh`, not computed.** The list:

| Module | Why it is there |
|---|---|
| `java.se` | every `java.*` module except `java.smartcardio` |
| `jdk.httpserver` | the platform's API, the account service's, and every metrics endpoint |
| `jdk.unsupported` | Netty and JCTools |
| `jdk.jfr` | flight recordings |
| `jdk.net` | socket options |
| `jdk.crypto.ec` | the elliptic curves that TLS negotiates, and the account service's ES256 signatures and the platform's checks of them (D-117), which no static reading finds |
| `jdk.jcmd`, `jdk.management`, `jdk.management.agent` | to take a heap dump or a recording from a running process |

**Why named, not computed.** Computing the list was the first design. `jdeps` cannot do it: the libraries are
explicit modules whose optional requirements are absent (Netty's `codec-marshalling` requires JBoss Marshalling).
A failure there had left `jdk.httpserver` out, and nobody noticed. So `jdeps` now reads only our own jars, j-redis's
and the account service's. If they use a module outside the list, the release stops with an error. This was mutation-checked:
without `jdk.httpserver` in the list, the release is refused.

**How it is linked.** `jlink --strip-java-debug-attributes --no-man-pages --no-header-files --compress zip-6`. The
release does not use `--strip-debug`. That option also strips native symbols with `objcopy`, and a minimal RHEL 9
has no binutils: the build failed there, and the install guide's check found it. The JDK's native libraries come
stripped already, so the runtime is the same size either way. The result is 63 MB and 31 modules (the 9 named and
the modules they require). Every unit's `ExecStart` is `/opt/backend/runtime/bin/java`, the stores' too.

**What the list leaves out, a process can still need.** No static reading sees a provider that is found by name,
such as `jdk.crypto.cryptoki` (PKCS#11, keys in a hardware module). The tests do not catch it either, because they
run on the full JDK. So `crypto-examples`' `RuntimeProbe` runs on the release's runtime. On 2026-10-05, every
algorithm that the backend may use was there, and PKCS#11 was absent
([development/04 §5](../development/04-crypto.md#5-running-them)).

### 4.2 What the host still provides

**The binaries need four things from the system**, on x86-64:

| What | Version | Needed by |
|---|---|---|
| glibc | 2.28 or later | every binary; the runtime alone needs 2.15 |
| `libstdc++` | GCC 7's or later | `mysqld` (its CXXABI 1.3.11) |
| `libgcc_s` | | MySQL |
| `ncurses-libs` | 6 (`libncurses.so.6` and `libtinfo.so.6`) | the `mysql` client |

This list was read from the binaries the release runs and from every library they load (`objdump -T`,
`readelf -d`). UBI 8 minimal and UBI 9 minimal have all four. glibc and `libstdc++` keep every older version's
symbols in their newer releases, so a newer system runs the binaries and an older one does not. ncurses keeps its
soname 6, which RHEL 8 and RHEL 9 both ship.

**Everything else the binaries load is in the release:**

- OpenSSL, PCRE2 and zlib are compiled into nginx.
- MySQL's `lib/private/` holds its own OpenSSL and protobuf, and RHEL 8's `libaio` and `libnuma`.
- MySQL's Kerberos, LDAP and SASL libraries are there too. They serve only authentication plugins that the site
  does not load.
- RHEL 8's `libcrypt.so.1` is there for SASL's PLAIN plugin. RHEL 9 has only `libcrypt.so.2`, unless
  `libxcrypt-compat` is installed (D-110).
- The runtime's desktop libraries name X11's and ALSA's libraries. A server process never loads them, because it
  runs headless.

**No symbolic link**, in `vendor/` or in a release
([D-106](../architecture/03-decision-log.md#d-106--nothing-in-the-repository-or-a-release-is-a-symbolic-link)).
Upstream's archives carry links (MySQL's `lib/`, the JDK's `legal/`). `vendor/update.sh` replaces each one with a
copy of the file it names. `make-release.sh` does the same to the runtime's `legal/`, which `jlink` makes of links.
The reason: a copy that does not keep links (Git for Windows does not, by default) leaves a small text file in each
link's place. `mysqld` then stops on `libaio.so.1` with "file too short". `NoLinksTest` checks the checkout, and
`check-release-el9.sh` checks the release.

**The C library stays the system's**, by choice. On 2026-11-02 the owner asked whether the binaries could carry
everything they need, whatever the host's version. The answer is no, for these reasons:

- A binary that carried its own glibc would carry its own loader too. It would still load the system's NSS
  modules (users, groups, host names) and locale data, which are built for the system's glibc. With a different
  glibc, they fail.
- MySQL and the JDK have no static build.
- nginx alone could be built static (with musl). That frees nothing while MySQL needs glibc.

So a host other than RHEL 9 must have the following. Only the userspaces of RHEL 8 and RHEL 9 were checked.

| Need | How to check it |
|---|---|
| x86-64 | `uname -m` |
| glibc 2.28 or later | `ldd --version` |
| the two C++ libraries above | |
| systemd 245 or later, so every sandbox setting applies (239 starts the units, §1) | `systemctl --version` |
| OpenSSL 1.1.1 or later, for the certificate scripts (D-103) | `openssl version` |

**The scripts and units need some programs from the operating system.** Any RHEL 9 server installation has them;
the minimal image does not. They stay the operating system's:

| Package or program | Used for |
|---|---|
| systemd | the units |
| util-linux (`flock`) | the restore proof's units, which run under `flock` |
| `tar`, `gzip` | unpacking a release |
| `findutils`, `procps-ng` | the scripts |
| OpenSSL's command line | the backups' encryption |
| `openssh-clients`, `rsync` | the copy off the site; only on the machine that runs the backups |

## 5. nginx, compiled for glibc 2.28

**`vendor/build-nginx.sh` compiles nginx in a `rockylinux/rockylinux:8` container** (D-100; it used Rocky 9 until
2026-11-02). It installs the compiler, `make` and `perl` in the container, and nothing on the host. The script:

1. unpacks nginx, OpenSSL, PCRE2 and zlib from `vendor/src/`;
2. configures and compiles nginx with the options below;
3. strips the binary, and checks inside the container what it links (glibc alone) and the newest glibc symbol it
   needs;
4. replaces `vendor/nginx-1.30/` only when that symbol is no newer than `GLIBC_2.28` (O-87). Otherwise it stops
   and leaves the committed nginx as it was.

| Configure option | Value, and why |
|---|---|
| `--prefix` | `/opt/backend/nginx` |
| `--conf-path` | `/etc/backend/nginx/nginx.conf`: the configuration is outside the release |
| logs | `/var/log/backend-nginx/` (`error.log`, `access.log`) |
| temporary files | `/var/cache/backend-nginx/` |
| pid and lock | `/run/backend-nginx/` |
| `--user`, `--group` | `nginx`: the workers run as this user |
| HTTP modules | `--with-http_ssl_module --with-http_v2_module` |
| stream modules | `--with-stream --with-stream_ssl_module`, since [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name): the TCP proxy on 9001. It ends the TLS of match traffic and passes each connection to the arena that its handshake names. |
| left out | FastCGI, uWSGI, SCGI, memcached, basic authentication (which would add `libcrypt`), and mail (POP3, IMAP, SMTP) |
| libraries | OpenSSL (`no-shared no-tests`), PCRE2 with its JIT, and zlib, from `vendor/src/`, compiled in. So no update of the system's OpenSSL changes nginx. |
| hardening | `-O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE`; linked with `-z relro -z now -pie` |

The modules are the ones the configuration uses (`deploy/nginx/`). `nginx -V` prints them, and
`vendor/nginx-1.30/BUILD.txt` keeps that output.

**The release ships nginx's configuration as six files** in `nginx-conf/`, from `deploy/nginx/`. An install copies
them to `/etc/backend/nginx/`, and `backend-nginx.service` runs nginx with them:

| File | What it holds |
|---|---|
| `nginx.conf` | the main file; it includes `backend.conf` and `backend-stream.conf` |
| `backend.conf` | the HTTP server: 443 over TLS, and since D-115 also 80 in clear, with the same locations, limits and upstreams. On 80 it also passes a match without TLS to its arena, by the path `/match/<name>`. |
| `backend-platform.conf` | the proxy settings that every platform location shares |
| `backend-upstreams.conf` | the platforms and gateways that the API and the lobby are sent to, on each machine's private address |
| `backend-stream.conf` | match traffic on 9001: nginx ends the TLS and picks the arena by the name in the handshake (SNI) |
| `backend-arenas.conf` | new with D-115: each arena's name and address, the one list that both maps read (80's `/match/<name>` and 9001's SNI) |

**A security fix to nginx or OpenSSL is ours to take**: a new version in `vendor/src/`, `build-nginx.sh` again, and
a new release. The runbook says so.

## 6. MySQL, from the release

**`backend-mysql.service` runs MySQL from the release.** Its command is
`/opt/backend/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf`. It runs as the system user `mysql`,
which the install creates, as the operating system's package did. Its data is in `/var/lib/backend-mysql`. The
configuration of operations/01 §9 moves there unchanged. The scratch server of the weekly proof
(operations/01 §10), `backend-scratch-mysql.service`, runs the same binary.

The scripts that call `mysql`, `mysqldump` and `mysqlbinlog` put `../mysql/bin` first on their `PATH`. In the
release, that is the folder beside the scripts. Run from the source tree, they use whatever the machine has.

On Ubuntu, AppArmor's profile for the distribution's `/usr/sbin/mysqld` does not apply to
`/opt/backend/mysql/bin/mysqld`, so the rules once shipped for it (O-16) are no longer needed. On RHEL 9, SELinux
runs a service started from `/opt` unconfined (`unconfined_service_t`, §7).

## 7. How it is verified

1. **The repository builds itself with no network.** A container with no network interface
   (`docker run --network none`) and only the repository builds the release with `build-offline.sh`.

2. **The release runs on RHEL 9 with nothing installed.** `backend/scripts/check-release-el9.sh` runs the release
   in `ubi9/ubi-minimal:9.5` (another tag with `IMAGE=`). It checks:
   - that the release holds no symbolic link;
   - that the system's loader finds every library of every native file: all 249 ELF files in the release (since
     D-110; until then, seven programs). Only the runtime's desktop libraries may lack a library, and only X11's
     and ALSA's;
   - that every MySQL program starts;
   - MySQL initialised and started, with every plugin, component and client plugin loaded by the server;
   - j-redis started;
   - the platform migrating the database, and serving a registration and a login;
   - the gateway, a worker and an arena (on Netty's native epoll) started;
   - nginx passing requests to the platform over HTTP and over HTTPS;
   - `nginx -t` on the shipped configuration.

   It was mutation-checked: a release without `libaio` fails it.

   **The same check runs in `ubi8/ubi-minimal:8.10`** (D-100). There it proves that every binary runs on
   glibc 2.28. A release with RHEL 9's `libnuma` and a Rocky 9 nginx failed it.

   **Since D-110 it runs on every RHEL 9 release**, UBI 9 minimal 9.0 to 9.9, and on Rocky 9. It found that SASL's
   `libplain.so` needs `libcrypt.so.1`, which RHEL 9 lacks (O-99). It was mutation-checked again: each of these
   fails it — Ubuntu's `libnuma` in the release (glibc 2.38); a program, and a plugin, with a symbol the loader
   cannot bind; and a desktop library that lacks a library other than X11's.

3. **The release on RHEL 9's kernel, with SELinux enforcing** (D-110).
   `backend/scripts/check-release-guest-el9.sh` boots the Rocky 9 guest that `check-selinux-el9.sh` makes, on a
   disk layer that is thrown away afterwards. It sets MySQL up from the release as the install guide does, and
   checks:
   - `backend-mysql.service` started by systemd;
   - TLS over TCP;
   - InnoDB's native AIO on the guest's kernel;
   - the binary log read by `mysqlbinlog`, and a dump made by `mysqldump`;
   - `mysqld` running in `unconfined_service_t`, with no denial.

   Then it runs `check-release-el9.sh`'s checks in the guest, as root.

4. **The units run on RHEL 9's systemd.** `backend/scripts/check-units-el9.sh` runs a release and the repository's
   `deploy/` in `ubi9/ubi-init:9.5`, with systemd 252 as the container's first process. It installs as the deploy
   document says, with heaps smaller than production's (by a drop-in), and checks one machine as the front
   (D-82):
   - it starts `backend-mysql` (`Type=notify`), `backend-store@session`, `backend-platform`, `backend-gateway`,
     `backend-worker@c1`, `backend-arena@a1` and `backend-nginx`;
   - it registers an account through nginx over HTTPS, with a certificate from `private-ca.sh` and only its root
     trusted;
   - **80 in clear** (D-115): the API's health answers 200, and `/match/a1.arena.internal` is answered 101 by the
     arena. With 2 KiB of a phone's headers, the arena still answers 101: nginx passes none of those headers on;
   - a match connection on 9001 is verified as `a1.arena.internal` and seen passed to the arena on 9011;
   - nginx is reloaded, after a test of its configuration.

   It then runs operations/07's commands:
   - a broken nginx configuration is refused by the reload, and the old one still serves;
   - MySQL, then the store, is restarted under a running platform, and the platform registers an account again
     without being restarted;
   - units are stopped by a pattern and started by name.

   **The secrets** (since D-102) are where a machine has them, in the settings files, and the check proves it:
   - no unit or drop-in names a credential (`LoadCredential=` or `%d`);
   - every settings file is root's, mode 0600;
   - `systemctl show` prints no secret;
   - another user can read neither the platform's environment nor its file;
   - a second arena, `backend-arena@a2`, serves its own TLS on 9012, verified, from the keystore that
     `install-certificate.sh` wrote. The keystore uses AES-256 with a SHA-256 MAC, whichever OpenSSL made it
     (D-103).

   Until D-102 the check gave the processes their secrets by a drop-in of its own, because systemd in a container
   cannot deliver a credential.

   **The watch** (D-86): the worker is stopped, the admin API lists `TargetDown` for it within three minutes, the
   platform's journal shows the alert, and the alert goes once the worker is back.

   **nginx's log rotation**: the `backend-nginx-logs` timer is enabled, and the service, run under its sandbox,
   renames and compresses `access.log`, deletes a file older than its `KEEP_DAYS`, and the workers write the fresh
   `access.log`.

   Finally every unit is stopped, each one with the result `success`. It was mutation-checked: a store unit that
   names a jar that is not there fails it. The check runs in UBI 8.10 too.

5. **The certificate scripts on each OpenSSL** (D-103). `backend/scripts/check-certificates.sh` runs on this
   machine's OpenSSL 3.0.13, and with `IMAGE=` in UBI 9.5 (3.2.2) and in UBI 8.10 with `openssl` added (1.1.1k).
   Every check gives the same result on each.

6. **The drills** run every process on the release's runtime, and their own MySQL servers on the release's MySQL
   8.4: every scenario, the MySQL failover, and the backup drill, which had only ever run on MySQL 8.0 before.

7. **The backend's tests** run against a MySQL 8.4 server from the release.
