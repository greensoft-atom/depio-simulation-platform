#!/bin/bash
# Checks a release on RHEL 9's own userspace with nothing installed (docs/detailed-design/09 §7): this
# script runs itself in Red Hat's UBI 9 minimal image, the release mounted read-only, and starts what
# the release carries: MySQL 8.4 (initialised), j-redis, the platform (which migrates the database and
# serves a registration and a login), the gateway, a worker, an arena, and nginx in front of the
# platform over HTTP and HTTPS; then the shipped nginx configuration's own test. It prints what the
# scripts take from the system that the image lacks: a server installation has them.
#
#   scripts/check-release-el9.sh [release-dir]       default: target/release/backend-*/; needs docker
#   IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:9.6 scripts/check-release-el9.sh   another RHEL 9.x
set -u
if [ "${1:-}" != --inside ]; then
    REL=$(cd "${1:-$(ls -d "$(dirname "$0")"/../target/release/backend-*/ | head -1)}" && pwd)
    IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-minimal:9.5}
    exec docker run --rm --name "backend-check-el9-$$" -e REL_NAME="$(basename "$REL")" \
        -v "$REL:/opt/$(basename "$REL"):ro" -v "$(cd "$(dirname "$0")" && pwd)/check-release-el9.sh:/check.sh:ro" \
        "$IMAGE" bash /check.sh --inside
fi
ln -sfn "/opt/$REL_NAME" /opt/backend
R=/opt/backend
O="--add-opens java.base/java.nio=ALL-UNNAMED"
ok() { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; FAILS=$((FAILS + 1)); }
FAILS=0
echo "--- $(cat /etc/redhat-release), glibc $(ldd --version 2>/dev/null | head -1 | awk '{print $NF}')"

echo "--- libraries the release's binaries need that this image lacks"
for b in runtime/bin/java mysql/bin/mysqld mysql/bin/mysql mysql/bin/mysqldump mysql/bin/mysqlbinlog mysql/bin/mysqladmin nginx/sbin/nginx; do
    miss=$(LD_TRACE_LOADED_OBJECTS=1 "$R/$b" 2>&1 | grep "not found" | awk '{print $1}' | tr '\n' ' ')
    [ -z "$miss" ] && ok "$b" || bad "$b lacks: $miss"
done

echo "--- MySQL 8.4"
mkdir -p /tmp/m
"$R/mysql/bin/mysqld" --no-defaults --initialize-insecure --datadir=/tmp/m/data --user=root > /tmp/m/init.log 2>&1 \
    && ok "initialised" || { bad "initialise"; tail -5 /tmp/m/init.log; }
"$R/mysql/bin/mysqld" --no-defaults --datadir=/tmp/m/data --user=root --port=3306 --bind-address=127.0.0.1 \
    --mysqlx=OFF --socket=/tmp/m/s --log-error=/tmp/m/err.log --default-time-zone=+00:00 &
for i in $(seq 60); do "$R/mysql/bin/mysqladmin" -S /tmp/m/s -u root ping > /dev/null 2>&1 && break; sleep 1; done
"$R/mysql/bin/mysql" -S /tmp/m/s -u root -e "CREATE DATABASE backend_dev; CREATE USER backend@'%' IDENTIFIED BY 'pw';
    GRANT ALL ON backend_dev.* TO backend@'%'" && ok "running: $("$R/mysql/bin/mysql" -S /tmp/m/s -u root -N -e 'SELECT VERSION()')" \
    || bad "MySQL not serving"

echo "--- j-redis"
"$R/runtime/bin/java" $O -jar "$R/jredis/lib/j-redis-server.jar" --port 6390 --dir /tmp > /tmp/jredis.log 2>&1 &
sleep 4
kill -0 $! 2>/dev/null && ok "running" || { bad "j-redis"; tail -5 /tmp/jredis.log; }

export BACKEND_DB_URL="jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true"
export BACKEND_DB_USER=backend BACKEND_DB_PASSWORD=pw

echo "--- platform"
BACKEND_PAYMENT_PROVIDER=simulated "$R/runtime/bin/java" $O -cp "$R/lib/platform/*" com.backend.platform.PlatformMain \
    127.0.0.1 8080 127.0.0.1 6390 > /tmp/platform.log 2>&1 &
for i in $(seq 90); do curl -s -o /dev/null http://127.0.0.1:8080/health && break; sleep 1; done
curl -s -o /dev/null http://127.0.0.1:8080/health && ok "serving" || { bad "platform"; tail -15 /tmp/platform.log; }
"$R/mysql/bin/mysql" -S /tmp/m/s -u root -N -e "SELECT CONCAT('migrated: ', GROUP_CONCAT(version ORDER BY installed_rank), ', ',
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'backend_dev'), ' tables')
    FROM backend_dev.flyway_schema_history" | sed 's/^/  ok   /'
code=$(curl -s -o /tmp/reg.json -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
    -d '{"username":"ubinine","password":"hunter2-hunter2"}' http://127.0.0.1:8080/v1/accounts)
[ "$code" = 201 ] && ok "a registration: 201" || bad "a registration: $code $(cat /tmp/reg.json)"
code=$(curl -s -o /tmp/login.json -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
    -d '{"username":"ubinine","password":"hunter2-hunter2"}' http://127.0.0.1:8080/v1/sessions)
[ "$code" = 200 ] && ok "a login: 200, a token" || bad "a login: $code $(cat /tmp/login.json)"

echo "--- gateway, worker, arena"
"$R/runtime/bin/java" $O -cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain 127.0.0.1 8094 http://127.0.0.1:8080 \
    127.0.0.1 6390 gateway-ubi > /tmp/gateway.log 2>&1 & G=$!
"$R/runtime/bin/java" $O -cp "$R/lib/worker/*" com.backend.worker.WorkerMain 127.0.0.1 6390 worker-ubi > /tmp/worker.log 2>&1 & W=$!
mkdir -p /tmp/spool
"$R/runtime/bin/java" $O -cp "$R/lib/arena/*" com.backend.arena.ArenaMain 9011 5700 150 200 2 127.0.0.1 6390 \
    arena-ubi 0 /tmp/spool 127.0.0.1 127.0.0.1 > /tmp/arena.log 2>&1 & A=$!
sleep 15
for p in "gateway $G" "worker $W" "arena $A"; do
    set -- $p
    kill -0 "$2" 2>/dev/null && ok "$1 running, $(grep -c ' ERROR ' /tmp/$1.log) errors in its log" || { bad "$1"; tail -8 /tmp/$1.log; }
done
grep -q "epoll" /tmp/arena.log && ok "the arena on Netty's native epoll" || echo "  (the arena's log names no epoll transport)"

echo "--- nginx"
mkdir -p /tmp/n /var/log/backend-nginx
cat > /tmp/n/nginx.conf <<EOF
user nobody;
pid /tmp/n/nginx.pid;
error_log /tmp/n/error.log;
events { }
http {
    include $R/nginx/conf/mime.types;
    access_log /tmp/n/access.log;
    client_body_temp_path /tmp/n/body;
    proxy_temp_path /tmp/n/proxy;
    server { listen 8081; location / { proxy_pass http://127.0.0.1:8080; } }
    server { listen 8443 ssl; ssl_certificate /tmp/m/data/server-cert.pem; ssl_certificate_key /tmp/m/data/server-key.pem;
             location / { proxy_pass http://127.0.0.1:8080; } }
}
EOF
"$R/nginx/sbin/nginx" -p /tmp/n/ -c /tmp/n/nginx.conf && sleep 1
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/health | grep -q 200 && ok "proxies HTTP to the platform" || bad "nginx HTTP"
curl -sk -o /dev/null -w "%{http_code}" https://127.0.0.1:8443/health | grep -q 200 \
    && ok "proxies HTTPS: $(curl -skv https://127.0.0.1:8443/health 2>&1 | grep -oE 'SSL connection using [^ ]+ / [^ ]+' | head -1)" || bad "nginx HTTPS"
# The shipped configuration, as installed, with a stand-in certificate (MySQL's own) and nobody for
# nginx, which this image has no user for.
mkdir -p /etc/backend/nginx /etc/backend/tls /etc/backend/credentials /var/log/backend-nginx /run/backend-nginx /var/cache/backend-nginx /var/www/acme
cp "$R"/nginx-conf/*.conf /etc/backend/nginx/
sed -i 's/^user nginx;/user nobody;/' /etc/backend/nginx/nginx.conf
for f in $(grep -ohE "ssl_certificate(_key)? +/etc/backend/[a-z/.-]+" /etc/backend/nginx/backend.conf | awk '{print $2}' | sort -u); do
    case "$f" in *key*) cp /tmp/m/data/server-key.pem "$f" ;; *) cp /tmp/m/data/server-cert.pem "$f" ;; esac
done
"$R/nginx/sbin/nginx" -t -q && ok "the shipped configuration: nginx -t accepts it" || bad "the shipped configuration"

echo "--- what the scripts take from the system"
for t in bash flock openssl ssh rsync curl tar gzip sha256sum awk sed find ps; do
    command -v "$t" > /dev/null || echo "  not in this image: $t"
done
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
