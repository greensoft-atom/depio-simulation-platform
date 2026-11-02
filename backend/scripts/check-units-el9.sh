#!/bin/bash
# Checks the units on RHEL 9's systemd (docs/detailed-design/09 §7): this script runs itself in Red
# Hat's UBI 9 init image, systemd its first process, with a release (read-only) and this repository's
# deploy/; installs as docs/operations/01-deploy.md §7 and §9 say (the users, the units, the
# database's configuration from its example, initialised as its header says), then starts
# backend-mysql, backend-store@session, backend-platform, backend-gateway, backend-worker@c1,
# backend-arena@a1 and backend-nginx, as one machine's front (D-82): registers an account through nginx
# over HTTPS, opens a match connection through its 9001 to the arena, reloads nginx, and stops them
# all. The secrets are in the settings files (D-102), and an arena of its own TLS takes its keystore from
# this repository's install-certificate.sh. Smaller heaps than production's, by a drop-in: this is a
# check, not a machine.
#
#   scripts/check-units-el9.sh [release-dir]       default: target/release/backend-*/; needs docker,
#                                                  and a privileged container (systemd inside it)
#   IMAGE=registry.access.redhat.com/ubi9/ubi-init:9.6 scripts/check-units-el9.sh   another RHEL 9.x
set -u
if [ "${1:-}" != --inside ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REL=${1:-$(ls -d "$HERE"/../target/release/backend-*/ 2> /dev/null | head -1)}
    # Checked: cd "" stays where it is, and the caller's directory was taken for the release (O-79).
    [ -n "$REL" ] && REL=$(cd "$REL" && pwd) || { echo "no release: scripts/make-release.sh first, or name one" >&2; exit 1; }
    IMAGE=${IMAGE:-registry.access.redhat.com/ubi9/ubi-init:9.5}
    NAME="backend-check-units-$$"
    docker run -d --rm --privileged --name "$NAME" -e REL_NAME="$(basename "$REL")" \
        -v "$REL:/opt/$(basename "$REL"):ro" -v "$HERE/../deploy:/deploy:ro" -v "$HERE:/scripts:ro" -v "$HERE/check-units-el9.sh:/check.sh:ro" \
        "$IMAGE" > /dev/null || exit 1
    # Stopped on every way out, an interrupt too: the docker client exits, and the container went on (O-78).
    trap 'docker stop "$NAME" > /dev/null 2>&1' EXIT
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
cp /deploy/systemd/*.service /deploy/systemd/*.timer /etc/systemd/system/
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
    CREATE USER backend@'127.0.0.1' IDENTIFIED BY 'db-secret-1f3a'; GRANT ALL ON backend.* TO backend@'127.0.0.1'"

echo "--- backend-store@session"
sed -e 's#^dir .*#dir /var/lib/backend-store/session#' -e 's/^requirepass .*/requirepass store-secret-7c2e/' \
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
BACKEND_METRICS_ADDR=127.0.0.1:9101
BACKEND_ADMIN_ADDR=127.0.0.1:9120
BACKEND_WATCH_TARGETS=platform=127.0.0.1:9101,gateway=127.0.0.1:9103,arena=127.0.0.1:9110,worker=127.0.0.1:9102
BACKEND_WATCH_MACHINE=one
BACKEND_DB_PASSWORD=db-secret-1f3a
BACKEND_STORE_PASSWORD=store-secret-7c2e
BACKEND_ADMIN_TOKEN=admin-secret-9b4d
EOF
# The secrets are in the settings files, root's alone (D-102): systemd reads them before the process starts.
chmod 0600 /etc/backend/*.env
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
BACKEND_METRICS_ADDR=127.0.0.1:9103
BACKEND_STORE_PASSWORD=store-secret-7c2e
EOF
cat > /etc/backend/worker.env <<EOF
STORE_HOST=127.0.0.1
STORE_PORT=6379
BACKEND_DB_URL=jdbc:mysql://127.0.0.1:3306/backend?useSSL=false&allowPublicKeyRetrieval=true
BACKEND_DB_USER=backend
BACKEND_METRICS_ADDR=127.0.0.1:9102
BACKEND_DB_PASSWORD=db-secret-1f3a
BACKEND_STORE_PASSWORD=store-secret-7c2e
EOF
cat > /etc/backend/arena-a1.env <<EOF
ARENA_PORT=9011
MAP_SIZE=5700
MAX_PLAYERS=150
SHAPES=1500
MAX_ROOMS=2
MATCH_SECONDS=0
ARENA_BIND=127.0.0.1
ADVERTISE_HOST=127.0.0.1
BACKEND_ADVERTISE_PORT=9001
BACKEND_ADVERTISE_TLS_NAME=a1.arena.internal
STORE_HOST=127.0.0.1
STORE_PORT=6379
BACKEND_METRICS_ADDR=127.0.0.1:9110
BACKEND_STORE_PASSWORD=store-secret-7c2e
EOF
chmod 0600 /etc/backend/*.env
systemctl start backend-gateway backend-worker@c1 backend-arena@a1
sleep 20
for u in backend-gateway backend-worker@c1 backend-arena@a1; do
    errors=$(journalctl -u "$u" --no-pager | grep -c ' ERROR ')
    [ "$(systemctl is-active "$u")" = active ] && ok "$u active, $errors errors in its journal" \
        || { bad "$u"; journalctl -u "$u" --no-pager | tail -8; }
done

echo "--- the secrets (D-102): in the settings files alone, root's, and nowhere systemd shows them"
named=$(grep -lE 'LoadCredential|%d/' /etc/systemd/system/backend-*.service /etc/systemd/system/backend-*.service.d/*.conf 2> /dev/null)
[ -z "$named" ] && ok "no unit or drop-in has LoadCredential= or %d" || bad "a credential still named in: $named"
loose=$(for f in /etc/backend/*.env; do [ "$(stat -c '%U %G %a' "$f")" = "root root 600" ] || echo "$f $(stat -c '%U:%G %a' "$f")"; done)
[ -z "$loose" ] && ok "every settings file root's alone, 0600: $(ls /etc/backend/*.env | wc -l)" || bad "settings files others may read: $loose"
shown=$(for u in backend-platform backend-gateway backend-worker@c1 backend-arena@a1; do
    systemctl show "$u" | grep -qE 'db-secret|store-secret|admin-secret' && echo "$u"; done)
[ -z "$shown" ] && ok "systemctl show prints no secret of the four" || bad "systemctl show prints a secret of: $shown"
pid=$(systemctl show -p MainPID --value backend-platform)
runuser -u nobody -- cat "/proc/$pid/environ" > /dev/null 2>&1 && bad "another user reads the platform's environment" \
    || ok "another user reads neither the platform's environment ($(stat -c '%U %a' "/proc/$pid/environ")) nor its file: $(runuser -u nobody -- cat /etc/backend/platform.env 2>&1 | head -1)"

echo "--- backend-nginx"
cp /deploy/nginx/*.conf /etc/backend/nginx/
# One machine, as the install guide's §9 makes it: this machine's platform, gateway and arena, on loopback.
printf '%s\n' 'upstream backend_gateway {' '    least_conn;' '    server 127.0.0.1:8090;' '}' \
    'upstream backend_platform {' '    server 127.0.0.1:8080;' '    keepalive 32;' '    keepalive_timeout 20s;' '}' \
    > /etc/backend/nginx/backend-upstreams.conf
sed -i -e '/^    [a-c][0-9]\.arena\.internal /d' \
    -e 's/^    default .*/    a1.arena.internal  127.0.0.1:9011;\n    default            "";/' \
    /etc/backend/nginx/backend-stream.conf
# The certificate from a root of its own, for this machine's address and the arena's name (06 §2, §3).
$R/scripts/private-ca.sh root /tmp/ca > /dev/null
$R/scripts/private-ca.sh issue /tmp/ca /tmp/site 127.0.0.1 a1.arena.internal > /dev/null
cp /tmp/site/fullchain.pem /etc/backend/tls/fullchain.pem
cp /tmp/site/privkey.pem /etc/backend/credentials/privkey.pem
systemctl start backend-nginx
active backend-nginx 20 && ok "active, workers as $(ps -o user= -C nginx 2>/dev/null | sort -u | tr '\n' ' ')" \
    || { bad "backend-nginx"; journalctl -u backend-nginx --no-pager | tail -8; }
code=$(curl -s --cacert /tmp/ca/ca.pem -o /tmp/reg -w "%{http_code}" -X POST \
    -H 'Content-Type: application/json' -d '{"username":"unitsnine","password":"hunter2-hunter2"}' https://127.0.0.1/v1/accounts)
[ "$code" = 201 ] && ok "a registration through nginx over HTTPS, its root trusted alone: 201" \
    || bad "a registration through nginx: $code $(head -c 200 /tmp/reg)"
curl -s -o /dev/null --max-time 5 http://127.0.0.1/ && bad "something answers on port 80" || ok "nothing on port 80 (D-82)"
# s_client says "Verify return code: 0 (ok)" with no certificate at all, on a port that speaks no TLS: the
# certificate's subject must be there too.
v=$(openssl s_client -connect 127.0.0.1:9001 -servername a1.arena.internal -verify_hostname a1.arena.internal \
    -CAfile /tmp/ca/ca.pem < /dev/null 2> /dev/null | grep -E '^subject=|Verify return code' | sed 's/^ *//' | paste -sd ' ')
[[ $v == subject=* && $v == *"Verify return code: 0 (ok)" ]] && ok "9001 as a1.arena.internal: $v" \
    || bad "9001 as a1.arena.internal: ${v:-nothing}"
sleep 1
grep -q '"a1.arena.internal" 127.0.0.1:9011 ' /var/log/backend-nginx/stream.log \
    && ok "and passed to the arena, 127.0.0.1:9011" || bad "not passed to the arena: $(tail -2 /var/log/backend-nginx/stream.log)"
systemctl reload backend-nginx && sleep 1 && [ "$(systemctl is-active backend-nginx)" = active ] && ok "reloaded (tested first)" \
    || bad "reload"

echo "--- an arena of its own TLS (01 §8, D-102, D-103): its keystore made by install-certificate.sh, read from /etc/backend/tls"
sed -i 's/^\([[:space:]]*\)server_name .*;/\1server_name 127.0.0.1;/' /etc/backend/nginx/backend.conf
cp /tmp/ca/ca.pem /etc/backend/tls/ca.pem
sed -e 's/^ARENA_PORT=.*/ARENA_PORT=9012/' -e '/^BACKEND_ADVERTISE_/d' -e 's/^BACKEND_METRICS_ADDR=.*/BACKEND_METRICS_ADDR=127.0.0.1:9111/' \
    /etc/backend/arena-a1.env > /etc/backend/arena-a2.env
printf 'BACKEND_ARENA_TLS_KEYSTORE=/etc/backend/tls/arena-tls.p12\nBACKEND_ARENA_TLS_PASSWORD_FILE=/etc/backend/tls/arena-tls-password\n' \
    >> /etc/backend/arena-a2.env
chmod 0600 /etc/backend/arena-a2.env
if /scripts/install-certificate.sh /tmp/site/fullchain.pem /tmp/site/privkey.pem > /tmp/install-certificate.log 2>&1; then
    ok "install-certificate.sh: $(grep '^arenas:' /tmp/install-certificate.log | paste -sd ';')"
else bad "install-certificate.sh: $(tail -3 /tmp/install-certificate.log)"; fi
modes=$(stat -c '%U %G %a' /etc/backend/tls/arena-tls.p12 /etc/backend/tls/arena-tls-password 2>&1 | paste -sd ' ')
[ "$modes" = "root backend 640 root backend 640" ] && ok "the keystore and its password: root's, the backend group's to read" \
    || bad "the keystore's files: $modes"
# The same keystore whichever OpenSSL made it (D-103): 3's defaults, not 1.1.1's 40-bit RC2 and SHA-1.
p12=$(openssl pkcs12 -in /etc/backend/tls/arena-tls.p12 -passin file:/etc/backend/tls/arena-tls-password -noout -info 2>&1)
[ "$(grep -c 'PBES2, PBKDF2, AES-256-CBC' <<< "$p12")" = 2 ] && grep -q '^MAC: sha256' <<< "$p12" \
    && ok "the keystore: AES-256-CBC for the key and the certificates, a SHA-256 MAC" \
    || bad "the keystore's algorithms: $(grep -E '^MAC:|Encrypted data|Keybag' <<< "$p12" | paste -sd ';')"
systemctl start backend-arena@a2
v=""
for i in $(seq 40); do
    v=$(openssl s_client -connect 127.0.0.1:9012 -verify_ip 127.0.0.1 -CAfile /tmp/ca/ca.pem < /dev/null 2> /dev/null \
        | grep -E '^subject=|Verify return code' | sed 's/^ *//' | paste -sd ' ')
    [[ $v == subject=* ]] && break; sleep 1
done
[[ $v == subject=* && $v == *"Verify return code: 0 (ok)" ]] && ok "backend-arena@a2 serves its own TLS on 9012: $v" \
    || { bad "backend-arena@a2 on 9012: ${v:-nothing}"; journalctl -u backend-arena@a2 --no-pager | tail -8; }
systemctl stop backend-arena@a2

echo "--- operations/07 as written: reloads, restarts, patterns"
register() {   # register <name>: the registration's status through nginx, retried for 15 s
    local code
    for _ in $(seq 15); do
        code=$(curl -s --cacert /tmp/ca/ca.pem -o /dev/null -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
            -d "{\"username\":\"$1\",\"password\":\"hunter2-hunter2\"}" https://127.0.0.1/v1/accounts)
        [ "$code" = 201 ] && break
        sleep 1
    done
    echo "$code"
}
cp /etc/backend/nginx/backend-upstreams.conf /tmp/upstreams.good
echo 'upstream broken {' >> /etc/backend/nginx/backend-upstreams.conf
if systemctl reload backend-nginx 2> /dev/null; then bad "nginx reloaded a configuration it cannot read"
else ok "a broken configuration: the reload refused"; fi
[ "$(systemctl is-active backend-nginx)" = active ] && [ "$(register unitsseven1)" = 201 ] \
    && ok "and the old one still serving: a registration, 201" || bad "nginx after a refused reload"
cp /tmp/upstreams.good /etc/backend/nginx/backend-upstreams.conf
systemctl reload backend-nginx && ok "the configuration put back, reloaded" || bad "the reload after the fix"
systemctl restart backend-mysql
code=$(register unitsseven2)
[ "$code" = 201 ] && [ "$(systemctl is-active backend-platform)" = active ] \
    && ok "MySQL restarted alone: the platform, not restarted, registers again: $code" || bad "after MySQL's restart: $code"
systemctl restart backend-store@session
code=$(register unitsseven3)
[ "$code" = 201 ] && ok "the store restarted alone: a registration, $code" || bad "after the store's restart: $code"
systemctl stop 'backend-arena@*' 'backend-worker@*'
[ "$(systemctl is-active backend-arena@a1)" != active ] && [ "$(systemctl is-active backend-worker@c1)" != active ] \
    && ok "a pattern stops what runs: the arena and the worker stopped" || bad "the stop by pattern"
systemctl start 'backend-arena@*' 2> /dev/null
echo "  ..   a pattern to start: the arena $(systemctl is-active backend-arena@a1)"
systemctl start backend-arena@a1 backend-worker@c1
active backend-arena@a1 30 && active backend-worker@c1 30 && ok "named, they start" || bad "the start by name"

echo "--- the watch (04 §12, D-86): the worker stopped, TargetDown listed by the admin API within three minutes, gone once it is back"
# An answer, or nothing: an admin API that does not answer listed no alert, and so the end of one passed (O-79).
admin() { curl -sf -H "Authorization: Bearer admin-secret-9b4d" "http://127.0.0.1:9120/admin/$1" || echo "(no answer)"; }
# The worker's: another target down would have passed for it.
down() { admin alerts | grep -q '"name":"TargetDown","labels":{[^}]*127.0.0.1:9102'; }
systemctl stop backend-worker@c1
T0=$(date +%s)
until down; do [ $(( $(date +%s) - T0 )) -gt 180 ] && break; sleep 5; done
down \
    && ok "TargetDown listed after $(( $(date +%s) - T0 )) s: $(admin alerts | grep -o '"labels":{[^}]*}' | head -1)" \
    || { bad "no TargetDown listed after 180 s: $(admin alerts | cut -c1-200)"; journalctl -u backend-platform --no-pager | grep -iE "watch|ALERT" | tail -5; }
journalctl -u backend-platform --no-pager | grep -q "ALERT firing TargetDown" && ok "and in the platform's journal" || bad "no ALERT line in the platform's journal"
systemctl start backend-worker@c1
T0=$(date +%s)
while down || ! admin alerts | grep -q '"alerts":\['; do [ $(( $(date +%s) - T0 )) -gt 60 ] && break; sleep 5; done
if down; then bad "TargetDown still listed 60 s after the worker's start"
elif ! admin alerts | grep -q '"alerts":\['; then bad "the admin API does not answer: $(admin alerts | cut -c1-120)"
else ok "resolved within $(( $(date +%s) - T0 )) s of the worker's start"; fi

echo "--- backend-nginx-logs: nginx's logs rotated as the unit runs it (D-85, O-45)"
systemctl enable backend-nginx-logs.timer 2> /dev/null && systemctl list-timers --all --no-pager | grep -q backend-nginx-logs \
    && ok "the timer enabled, listed" || bad "the timer: $(systemctl status backend-nginx-logs.timer 2>&1 | tail -3)"
touch -d "20 days ago" /var/log/backend-nginx/access.log.20261004T000500Z.gz
if systemctl start backend-nginx-logs.service; then
    ok "the service ran under its sandbox: $(journalctl -u backend-nginx-logs --no-pager -o cat | grep -E 'reopen|rotated|deleted' | paste -sd ';' | cut -c1-110)"
else
    bad "backend-nginx-logs"; journalctl -u backend-nginx-logs --no-pager | tail -6
fi
rotated=$(ls /var/log/backend-nginx/access.log.*Z.gz 2> /dev/null | grep -v 20261004 | head -1)
[ -n "$rotated" ] && zcat "$rotated" | grep -q 'POST /v1/accounts' && ok "access.log renamed, compressed, the registrations in it" \
    || bad "no rotated access.log: $(ls /var/log/backend-nginx | paste -sd ' ')"
[ -e /var/log/backend-nginx/access.log.20261004T000500Z.gz ] && bad "a rotated file past KEEP_DAYS kept" || ok "one past KEEP_DAYS deleted"
[ "$(register unitslogs)" = 201 ] && sleep 1 && grep -q 'POST /v1/accounts' /var/log/backend-nginx/access.log \
    && ok "the workers, as $(stat -c %U /var/log/backend-nginx/access.log), write the fresh access.log after the reopen" \
    || bad "the fresh access.log after the reopen: $(ls -la /var/log/backend-nginx/access.log 2>&1) $(tail -2 /var/log/backend-nginx/error.log)"

echo "--- stopping"
for u in backend-nginx backend-arena@a1 backend-worker@c1 backend-gateway backend-platform backend-store@session backend-mysql; do
    systemctl stop "$u"; s=$(systemctl show -p Result --value "$u"); [ "$s" = success ] && ok "$u stopped: $s" || bad "$u stopped: $s"
done
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
