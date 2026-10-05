# vendor/ — what the backend is built with and runs on

Committed, extracted and ready to use, so that the repository alone builds and
runs the backend, with no network and nothing installed from the operating
system beyond its base (D-77,
[09](../docs/detailed-design/09-release-and-packaging.md)). Every binary runs on
glibc 2.34, RHEL 9's, and on the development machine's Ubuntu 24.04. Every file
is under GitHub's 100 MB limit.

| Directory | What | Version | Upstream | Checked by | Changed from upstream |
|---|---|---|---|---|---|
| `jdk-21/` | The JDK: compiles, tests, and makes each release's runtime with `jlink` | Temurin 21.0.12.1+1 | [Adoptium](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1) | SHA-256 `ce79869e…faee94`, Adoptium's | Re-linked with `jlink --compress zip-6`, every module and tool kept, so `lib/modules` is 59 MB, not 136; `jmods/` and `NOTICE` copied; `src.zip` left out |
| `maven-3.9/` | Maven | 3.9.16 | [Apache](https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/) | SHA-512, Apache's; SHA-256 `80ffca22…a3bb` | none |
| `mysql-8.4/` | MySQL Community Server and its tools | 8.4.11 LTS, generic Linux for glibc 2.28, minimal | [Oracle](https://cdn.mysql.com/Downloads/MySQL-8.4/) | SHA-256 `383f54e1…b143`, pinned at download | Left out: `lib/mecab` (Japanese full-text dictionaries, 130 MB), the static libraries, `include/`, `pkgconfig`, `mysql_config`, and `share/dictionary.txt` (the unused password-strength component's word list). Added to `lib/private/`: `libaio.so.1` and `libnuma.so.1` from RHEL 9's packages (`lib/private/EL9-LIBRARIES.txt` names them) |
| `nginx-1.30/` | nginx | 1.30.5, with OpenSSL 3.5.9, PCRE2 10.49 and zlib 1.3.2 compiled in | built by `build-nginx.sh` | `BUILD.txt` is its `nginx -V` | Compiled in Rocky Linux 9 (GCC 11.5); links glibc alone, the newest symbol 2.34; without the modules the configuration does not use |
| `src/` | The sources `nginx-1.30/` is compiled from | as above | [nginx](https://nginx.org/download/), [OpenSSL](https://github.com/openssl/openssl/releases), [PCRE2](https://github.com/PCRE2Project/pcre2/releases), [zlib](https://zlib.net/) | SHA-256 pinned in `update.sh`; OpenSSL's against its published `.sha256` | none |

The Java libraries are `java21-offline/`, beside this directory.

## Using it, and moving a version

[README.md](README.md): how the build and the release use each piece, how a
version is moved, and where the security advisories are.
