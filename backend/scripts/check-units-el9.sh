#!/bin/bash
# Checks the units on RHEL 9's systemd (docs/detailed-design/09 §7): this script runs itself in Red
# Hat's UBI 9 init image, systemd its first process, with a release (read-only) and this repository's
# deploy/; installs as docs/operations/01-deploy.md §7 and §9 say (the users, the units, the
# database's configuration from its example, initialised as its header says), then starts
# backend-mysql, backend-store@session, backend-platform, backend-gateway, backend-worker@c1,
# backend-arena@a1 and backend-nginx, registers an account through nginx over HTTPS, reloads nginx,
# and stops them all. Smaller heaps than production's, by a drop-in: this is a check, not a machine.
#
#   scripts/check-units-el9.sh [release-dir]       default: target/release/backend-*/; needs docker,
#                                                  and a privileged container (systemd inside it)
#   IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-units-el9.sh   another RHEL 9.x
set -u
if [ "${1:-}" != --inside ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REL=$(cd "${1:-$(ls -d "$HERE"/../target/release/backend-*/ | head -1)}" && pwd)
    IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-init:9.5}
    NAME="backend-check-units-$$"
    docker run -d --rm --privileged --name "$NAME" -e REL_NAME="$(basename "$REL")" \
        -v "$REL:/opt/$(basename "$REL"):ro" -v "$HERE/../deploy:/deploy:ro" -v "$HERE/check-units-el9.sh:/check.sh:ro" \
        "$IMAGE" > /dev/null || exit 1
    sleep 5
    docker exec -e REL_NAME="$(basename "$REL")" "$NAME" bash /check.sh --inside
    rc=$?
    docker stop "$NAME" > /dev/null
    exit "$rc"
fi
FAILS=0
ok() { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; FAILS=$((FAILS + 1)); }
active() { for i in $(seq "${2:-60}"); do [ "$(systemctl is-active "$1")" = active ] && return 0; sleep 1; done; return 1; }
ln -sfn "/opt/$REL_NAME" /opt/backend
R=/opt/backend
echo "--- $(cat /etc/redhat-release), systemd $(systemctl --version | head -1 | awk '{print $2}')"

echo "--- users and files, as the deploy document says"
for u in backend nginx jredis; do useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin "$u"; done
useradd --system --home-dir /var/lib/backend-mysql --shell /usr/sbin/nologin mysql
cp /deploy/systemd/*.service /etc/systemd/system/
mkdir -p /etc/backend/mysql /etc/backend/nginx /etc/backend/tls /etc/backend/credentials
chmod 0700 /etc/backend/credentials
# The database: the example, with what this test lacks taken out - a CA (MySQL's own certificates
# instead, below), a private address, 14 GB.
sed -e '/^ssl_/d' -e 's/^require_secure_transport .*/require_secure_transport = OFF/' \
    -e 's/^bind-address .*/bind-address = 127.0.0.1/' -e 's/^innodb_buffer_pool_size .*/innodb_buffer_pool_size = 256M/' \
    /deploy/mysql/backend.cnf.example > /etc/backend/mysql/backend.cnf
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql
$R/mysql/bin/mysqld --defaults-file=/etc/backend/mysql/backend.cnf --initialize-insecure --user=mysql --log-error=/tmp/init.log \
    && ok "initialised as the example's header says" || { bad "initialise"; tail -5 /tmp/init.log; }
systemctl daemon-reload

# Production's heaps add up to about 13 GB, more than a check may take: every Java unit gets smaller
# ones by a drop-in (_JAVA_OPTIONS is read after the command line, so it wins), for this check only.
for u in backend-platform backend-gateway backend-worker@ backend-arena@ backend-store@; do
    mkdir -p "/etc/systemd/system/$u.service.d"
    printf '[Service]\nEnvironment="_JAVA_OPTIONS=-Xms256m -Xmx768m -XX:-AlwaysPreTouch"\n' \
        > "/etc/systemd/system/$u.service.d/check-heap.conf"
done
systemctl daemon-reload

echo "--- backend-mysql"
systemctl start backend-mysql
active backend-mysql && ok "active (Type=notify): $($R/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root -N -e 'SELECT VERSION()')" \
    || { bad "backend-mysql"; journalctl -u backend-mysql --no-pager | tail -8; }
$R/mysql/bin/mysql -S /run/backend-mysql/mysqld.sock -u root -e "CREATE DATABASE backend;
    CREATE USER backend@'127.0.0.1' IDENTIFIED BY 'dbpw'; GRANT ALL ON backend.* TO backend@'127.0.0.1'"
echo dbpw > /etc/backend/credentials/db-password; echo storepw > /etc/backend/credentials/store-password
chmod 0600 /etc/backend/credentials/*

echo "--- backend-store@session"
sed -e 's#^dir .*#dir /var/lib/backend-store/session#' -e 's/^requirepass .*/requirepass storepw/' \
    $R/jredis/conf/j-redis.conf > /etc/backend/store-session.conf
echo "STORE_HEAP=512m" > /etc/backend/store-session.env
systemctl start backend-store@session
active backend-store@session 30 && sleep 3 && [ "$(systemctl is-active backend-store@session)" = active ] \
    && ok "active, on the release's runtime, data in $(stat -c '%U %a' /var/lib/backend-store/session)" \
    || { bad "backend-store@session"; journalctl -u backend-store@session --no-pager | tail -8; }

echo "--- backend-platform"
cat > /etc/backend/platform.env <<EOF
PLATFORM_BIND=127.0.0.1
PLATFORM_PORT=8080
STORE_HOST=127.0.0.1
STORE_PORT=6379
BACKEND_DB_URL=jdbc:mysql://127.0.0.1:3306/backend?useSSL=false&allowPublicKeyRetrieval=true
BACKEND_DB_USER=backend
BACKEND_PAYMENT_PROVIDER=simulated
EOF
# systemd in a container cannot mount the private directory LoadCredential delivers into (it stays
# empty and root's): the secrets come as files the user may read, by a drop-in, for this test only.
# On a host it works for a non-root user (checked on systemd 255; RHEL 9's 252 uses the same ACLs).
install -d -o backend -m 0500 /etc/backend/test-credentials
install -o backend -m 0400 /etc/backend/credentials/db-password /etc/backend/credentials/store-password /etc/backend/test-credentials/
for u in backend-platform backend-gateway backend-worker@ backend-arena@; do
    mkdir -p "/etc/systemd/system/$u.service.d"
    printf '[Service]\nEnvironment=BACKEND_DB_PASSWORD_FILE=/etc/backend/test-credentials/db-password\nEnvironment=BACKEND_STORE_PASSWORD_FILE=/etc/backend/test-credentials/store-password\n' \
        > "/etc/systemd/system/$u.service.d/test-credentials.conf"
done
systemctl daemon-reload
systemctl start backend-platform
for i in $(seq 90); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
curl -s -o /dev/null http://127.0.0.1:8080/health && ok "active and serving, migrated by Flyway at its start" \
    || { bad "backend-platform"; journalctl -u backend-platform --no-pager | tail -12; }

echo "--- backend-gateway, backend-worker@c1, backend-arena@a1"
cat > /etc/backend/gateway.env <<EOF
GATEWAY_BIND=127.0.0.1
GATEWAY_PORT=8090
PLATFORM_URL=http://127.0.0.1:8080
STORE_HOST=127.0.0.1
STORE_PORT=6379
EOF
cat > /etc/backend/worker.env <<EOF
STORE_HOST=127.0.0.1
STORE_PORT=6379
BACKEND_DB_URL=jdbc:mysql://127.0.0.1:3306/backend?useSSL=false&allowPublicKeyRetrieval=true
BACKEND_DB_USER=backend
EOF
cat > /etc/backend/arena-a1.env <<EOF
ARENA_PORT=9001
MAP_SIZE=5700
MAX_PLAYERS=150
SHAPES=1500
MAX_ROOMS=2
MATCH_SECONDS=0
ARENA_BIND=127.0.0.1
ADVERTISE_HOST=127.0.0.1
STORE_HOST=127.0.0.1
STORE_PORT=6379
EOF
systemctl start backend-gateway backend-worker@c1 backend-arena@a1
sleep 20
for u in backend-gateway backend-worker@c1 backend-arena@a1; do
    errors=$(journalctl -u "$u" --no-pager | grep -c ' ERROR ')
    [ "$(systemctl is-active "$u")" = active ] && ok "$u active, $errors errors in its journal" \
        || { bad "$u"; journalctl -u "$u" --no-pager | tail -8; }
done

echo "--- backend-nginx"
cp /deploy/nginx/*.conf /etc/backend/nginx/
# A stand-in certificate for a.example.com: MySQL's own, generated at initialisation.
cp /var/lib/backend-mysql/server-cert.pem /etc/backend/tls/fullchain.pem
cp /var/lib/backend-mysql/server-key.pem /etc/backend/credentials/privkey.pem
mkdir -p /var/www/acme
systemctl start backend-nginx
active backend-nginx 20 && ok "active, workers as $(ps -o user= -C nginx 2>/dev/null | sort -u | tr '\n' ' ')" \
    || { bad "backend-nginx"; journalctl -u backend-nginx --no-pager | tail -8; }
code=$(curl -sk -o /tmp/reg -w "%{http_code}" --resolve a.example.com:443:127.0.0.1 -X POST \
    -H 'Content-Type: application/json' -d '{"username":"unitsnine","password":"hunter2-hunter2"}' https://a.example.com/v1/accounts)
[ "$code" = 201 ] && ok "a registration through nginx over HTTPS: 201" || bad "a registration through nginx: $code $(head -c 200 /tmp/reg)"
code=$(curl -s -o /dev/null -w "%{http_code}" --resolve a.example.com:80:127.0.0.1 http://a.example.com/v1/accounts)
[ "$code" = 403 ] && ok "plain HTTP refused: 403" || bad "plain HTTP: $code"
systemctl reload backend-nginx && sleep 1 && [ "$(systemctl is-active backend-nginx)" = active ] && ok "reloaded (tested first)" \
    || bad "reload"

echo "--- stopping"
for u in backend-nginx backend-arena@a1 backend-worker@c1 backend-gateway backend-platform backend-store@session backend-mysql; do
    systemctl stop "$u"; s=$(systemctl show -p Result --value "$u"); [ "$s" = success ] && ok "$u stopped: $s" || bad "$u stopped: $s"
done
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
