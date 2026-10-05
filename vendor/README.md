# vendor/ — the JDK, Maven, MySQL and nginx, committed

What the backend is built with and runs on, in the repository itself, so that a
clone builds and runs it with no network and nothing installed from the
operating system but its base (D-77,
[09](../docs/detailed-design/09-release-and-packaging.md)). What each piece is,
its version and where it came from: [VERSIONS.md](VERSIONS.md).

| Directory | Used by | For |
|---|---|---|
| `jdk-21/` | `build-offline.sh`, `make-release.sh`, you | compiling and testing; `jlink` makes each release's runtime from its `jmods/` |
| `maven-3.9/` | `build-offline.sh`, you | the build |
| `mysql-8.4/` | `make-release.sh` (the release's `mysql/`), the tests, the drills | the database, its server and its tools |
| `nginx-1.30/` | `make-release.sh` (the release's `nginx/`) | the TLS edge |
| `src/` | `build-nginx.sh` | compiling nginx again |
| `update.sh`, `build-nginx.sh` | whoever moves a version | refreshing the above |

Every binary here runs on RHEL 9 (glibc 2.34) and on Ubuntu 24.04, and every
file is under GitHub's 100 MB limit.

## Using it

Building everything, with nothing else: `../build-offline.sh`
([build and run](../docs/development/03-build-and-run.md)). To use the JDK and
Maven by hand, from `background/`:

```bash
export JAVA_HOME=$PWD/vendor/jdk-21 PATH=$PWD/vendor/jdk-21/bin:$PWD/vendor/maven-3.9/bin:$PATH
java -version && mvn -v
```

MySQL for the tests, a server of its own on a private port:
[build and run §3](../docs/development/03-build-and-run.md#3-the-tests). On a
server, nothing here is used directly: the release carries what it needs, and the
[install guide](../docs/operations/03-install-guide.md) installs that.

## Moving a version

For a security fix, or a new release of any of them. Needs the internet, and
Docker for nginx.

1. **Change its line** in `update.sh` (`PINS`): the new URL, and the checksum left
   empty.
2. **Run `vendor/update.sh`.** It downloads, stops on the checksum and prints the
   file's SHA-256: compare it with the one the publisher states (Adoptium's
   release page, Apache's `.sha512`, OpenSSL's `.sha256`; for MySQL and nginx, the
   download page and the PGP signature), then pin it in `update.sh`.
3. **Run it again**: it derives `jdk-21/`, `maven-3.9/`, `mysql-8.4/` and `src/` (the
   old ones are replaced). Downloads are kept in `CACHE` (default under `TMPDIR`).
4. **For nginx, OpenSSL, PCRE2 or zlib**, then `vendor/build-nginx.sh`: it compiles
   in a Rocky Linux 9 container and refuses a binary that needs a newer glibc than
   RHEL 9's.
5. **Check, then commit**: update [VERSIONS.md](VERSIONS.md); build a release
   (`../build-offline.sh`); run `backend/scripts/check-release-el9.sh`,
   `check-units-el9.sh` and `check-install-guide-el9.sh`, and the drills; then
   commit `vendor/` with the change, and deploy as
   [01 §5](../docs/operations/01-deploy.md#5-rolling-deployment) says.

Never edit a file here by hand: everything is what the two scripts make, so the
next refresh makes it again. A directory's name carries the version line
(`jdk-21`, `mysql-8.4`), not the exact version, so the paths in the scripts do
not change with a fix.

## Security fixes

The operating system's updates do not reach anything here: watching for fixes is
ours, monthly ([runbook §6](../docs/operations/02-runbook.md#6-routine)).

| What | Where its advisories are |
|---|---|
| Temurin 21 | https://adoptium.net/temurin/release-notes (quarterly updates) |
| MySQL 8.4 | Oracle's Critical Patch Updates, https://www.oracle.com/security-alerts/ (quarterly) |
| nginx | https://nginx.org/en/security_advisories.html |
| OpenSSL 3.5 | https://openssl-library.org/news/vulnerabilities/ |
| PCRE2, zlib | their release notes, on GitHub and zlib.net |
| libaio, numactl (RHEL 9's) | Red Hat's errata for those packages |
