#!/usr/bin/env bash
# Compiles nginx for RHEL 9 (glibc 2.34) in a Rocky Linux 9 container, from the sources in src/, with
# OpenSSL, PCRE2 (and its JIT) and zlib compiled in, and installs it as nginx-1.30/ beside this script
# (docs/detailed-design/09 §5, D-77). Never compiled on the development machine: its glibc's newer
# symbols would keep the binary from starting on RHEL 9. Needs docker; nothing installed on the host.
#
#   vendor/build-nginx.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=rockylinux/rockylinux:9
OUT="$HERE/nginx-1.30"
work=$(mktemp -d "${TMPDIR:-/tmp}/build-nginx.XXXXXX")
trap 'rc=$?; [ "$rc" = 0 ] || tail -n 25 "$work"/*.log 2>/dev/null; rm -rf "$work"' EXIT

docker run --rm --name "backend-build-nginx-$$" -v "$HERE/src:/src:ro" -v "$work:/out" "$IMAGE" bash -c '
    set -e
    dnf -q -y install gcc make perl-core > /dev/null
    mkdir /build && cd /build
    for t in /src/*.tar.gz; do tar -xzf "$t"; done
    cd nginx-*
    # Paths outside the release: the configuration in /etc/backend/nginx, logs, temporary files and
    # the pid where systemd makes them (deploy/systemd/backend-nginx.service).
    ./configure \
        --prefix=/opt/backend/nginx \
        --conf-path=/etc/backend/nginx/nginx.conf \
        --error-log-path=/var/log/backend-nginx/error.log \
        --http-log-path=/var/log/backend-nginx/access.log \
        --pid-path=/run/backend-nginx/nginx.pid \
        --lock-path=/run/backend-nginx/nginx.lock \
        --http-client-body-temp-path=/var/cache/backend-nginx/client_body \
        --http-proxy-temp-path=/var/cache/backend-nginx/proxy \
        --user=nginx --group=nginx \
        --with-http_ssl_module --with-http_v2_module \
        --without-http_fastcgi_module --without-http_uwsgi_module --without-http_scgi_module \
        --without-http_memcached_module --without-mail_pop3_module --without-mail_imap_module \
        --without-http_auth_basic_module \
        --without-mail_smtp_module \
        --with-openssl="$(echo ../openssl-*)" --with-openssl-opt="no-shared no-tests" \
        --with-pcre="$(echo ../pcre2-*)" --with-pcre-jit --with-pcre-opt="-O2 -fPIC" \
        --with-zlib="$(echo ../zlib-*)" --with-zlib-opt="-O2 -fPIC" \
        --with-cc-opt="-O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE" \
        --with-ld-opt="-Wl,-z,relro -Wl,-z,now -pie" > /out/configure.log
    make -j"$(nproc)" > /out/make.log 2>&1
    make install DESTDIR=/out/install > /dev/null
    cp LICENSE /out/install/LICENSE
    /out/install/opt/backend/nginx/sbin/nginx -V > /out/nginx-V.txt 2>&1
    chown -R '"$(id -u):$(id -g)"' /out'

rm -rf "$OUT"
mkdir -p "$OUT/conf"
cp -a "$work/install/opt/backend/nginx/sbin" "$OUT/"
cp "$work/install/etc/backend/nginx/mime.types" "$OUT/conf/"
cp "$work/install/LICENSE" "$OUT/LICENSE"
cp "$work/nginx-V.txt" "$OUT/BUILD.txt"
strip "$OUT/sbin/nginx"

# What it links: glibc alone (auth_basic, unused, would add libcrypt.so.2, which RHEL 9 has and Ubuntu not).
readelf -d "$OUT/sbin/nginx" | grep NEEDED
# The newest glibc symbol it needs: RHEL 9 has 2.34.
newest=$(objdump -T "$OUT/sbin/nginx" | grep -oE 'GLIBC_[0-9.]+' | sort -t. -k2 -n | sort -V | tail -1)
echo "nginx built: $(head -1 "$OUT/BUILD.txt"); the newest glibc symbol it needs: $newest"
case "$newest" in GLIBC_2.3[5-9]*|GLIBC_2.[4-9]*) echo "TOO NEW FOR RHEL 9" >&2; exit 1 ;; esac
