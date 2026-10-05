#!/usr/bin/env bash
# Fetches the pinned downloads, checks each against its pinned SHA-256, and derives the committed
# directories beside this script (docs/detailed-design/09 §2, D-77): jdk-21, maven-3.9, mysql-8.4 and
# src. nginx-1.30 is build-nginx.sh's. Not needed to build or run the backend: what it makes is
# committed. Needs curl, sha256sum, tar, xz and docker (RHEL 9's two libraries for MySQL come from
# its packages, read in a Rocky Linux 9 container).
#
#   vendor/update.sh                  CACHE=<dir> to keep the downloads elsewhere than $TMPDIR
#
# To move a version: change its line below, run this once with the new checksum left empty to be
# told it, check that against what the publisher states, pin it, and run again.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
CACHE=${CACHE:-${TMPDIR:-/tmp}/backend-vendor-cache}
mkdir -p "$CACHE"

# name                 url                                                                                   sha256
PINS='
jdk      https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94
maven    https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz 80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb
mysql    https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.11-linux-glibc2.28-x86_64-minimal.tar.xz 383f54e124d5f325d67f0c6912a8f96814eedc761a17ea30112e52fa4cc6b143
nginx    https://nginx.org/download/nginx-1.30.5.tar.gz 6c20565aa2325cb82216ae804f4a4ff1875179014759a381c42ddc8e11c4906d
openssl  https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz 603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
pcre2    https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.49/pcre2-10.49.tar.gz 929f0b20e62879252a15886b06c89f1edef61a363cbd5826fb041080a5e557ae
zlib     https://zlib.net/zlib-1.3.2.tar.gz bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
'
# RHEL 9's packages for the two libraries mysqld takes from the system (09 §2), from Rocky Linux 9.
EL9_IMAGE=rockylinux/rockylinux:9
EL9_LIBS="libaio numactl-libs"

fetch() {                                     # fetch <name>: the file's path in the cache, checked
    local line url sum file
    line=$(awk -v n="$1" '$1 == n' <<< "$PINS")
    url=$(awk '{print $2}' <<< "$line"); sum=$(awk '{print $3}' <<< "$line")
    file="$CACHE/$(basename "${url//%2B/+}")"
    if [ ! -s "$file" ]; then
        curl -fsSL --retry 3 -o "$file.part" "$url"
        mv "$file.part" "$file"
    fi
    local got; got=$(sha256sum "$file" | awk '{print $1}')
    if [ "$got" != "$sum" ]; then
        echo "CHECKSUM MISMATCH for $1: $file is $got, pinned ${sum:-(none)}" >&2
        exit 1
    fi
    echo "$file"
}

work=$(mktemp -d "$CACHE/work.XXXXXX")
trap 'rm -rf "$work"' EXIT

# The JDK: every module and tool, re-linked compressed so lib/modules is under GitHub's 100 MB a file;
# jmods kept for jlink. src.zip left out.
echo "--- jdk"
tar -xzf "$(fetch jdk)" -C "$work"
T=$(echo "$work"/jdk-21*)
rm -rf "$HERE/jdk-21"
"$T/bin/jlink" --module-path "$T/jmods" --add-modules ALL-MODULE-PATH --compress zip-6 \
    --output "$HERE/jdk-21" | { grep -v "incubator modules" || true; }
cp -r "$T/jmods" "$HERE/jdk-21/jmods"
cp "$T/NOTICE" "$HERE/jdk-21/NOTICE"

echo "--- maven"
rm -rf "$HERE/maven-3.9"
mkdir -p "$HERE/maven-3.9"
tar -xzf "$(fetch maven)" -C "$HERE/maven-3.9" --strip-components=1

# MySQL: the minimal generic build, less what nothing here uses, and the two libraries it takes from
# the system put in lib/private, which its RUNPATH ($ORIGIN/../lib/private) searches first.
echo "--- mysql"
rm -rf "$HERE/mysql-8.4"
mkdir -p "$HERE/mysql-8.4"
tar -xJf "$(fetch mysql)" -C "$HERE/mysql-8.4" --strip-components=1
# share/dictionary.txt is the password-strength component's word list, unused here (and it holds the
# word the naming rule excludes, which the commit's word check would refuse).
rm -rf "$HERE/mysql-8.4/lib/mecab" "$HERE/mysql-8.4/lib/pkgconfig" "$HERE/mysql-8.4/include" \
       "$HERE/mysql-8.4/bin/mysql_config" "$HERE/mysql-8.4/share/dictionary.txt"
rm -f "$HERE/mysql-8.4"/lib/*.a
docker run --rm -v "$work:/out" "$EL9_IMAGE" bash -c "
    set -e
    dnf -q -y install dnf-plugins-core cpio > /dev/null
    cd /tmp && dnf -q download $EL9_LIBS --arch x86_64
    for r in *.rpm; do rpm2cpio \$r | cpio -idm --quiet; done
    cp -a usr/lib64/libaio.so.1* usr/lib64/libnuma.so.1* /out/
    rpm -qp --qf '%{NAME}-%{VERSION}-%{RELEASE}.%{ARCH}\n' *.rpm > /out/el9-libs.txt
    chown $(id -u):$(id -g) /out/*"
cp -a "$work"/libaio.so.1* "$work"/libnuma.so.1* "$HERE/mysql-8.4/lib/private/"
cp "$work/el9-libs.txt" "$HERE/mysql-8.4/lib/private/EL9-LIBRARIES.txt"

# The sources nginx is built from, as published.
echo "--- src"
mkdir -p "$HERE/src"
rm -f "$HERE"/src/*.tar.gz
for n in nginx openssl pcre2 zlib; do cp "$(fetch "$n")" "$HERE/src/"; done

echo "--- done; for new nginx sources, build-nginx.sh next; then VERSIONS.md"
du -sh "$HERE"/jdk-21 "$HERE"/maven-3.9 "$HERE"/mysql-8.4 "$HERE"/src
find "$HERE" -type f -size +95M -printf "TOO BIG FOR GITHUB: %p\n"
