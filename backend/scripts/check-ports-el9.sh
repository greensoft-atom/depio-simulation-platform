#!/bin/bash
# Checks docs/operations/05-ports-and-firewall.md and 06-certificates.md as written (D-82): the site behind one
# public address, nginx on the front ending TLS on 443 and 9001, two machines' platforms, gateways and arenas
# behind it on the private network, and the C# client playing through it, trusting the backend's own root alone.
# In Rocky Linux 9.5 (RHEL 9's firewalld), systemd its first process, with no network:
#   - A, the front here (10.0.0.1, and the public 198.51.100.1): MySQL with TLS (01 §9), the session store, the
#     platform, the gateway, the arena a1 and nginx, from a release with the settings it ships; and two of C's,
#     the first worker and the scratch MySQL, for their ports; a stand-in for sshd on 22, which echoes;
#   - B (10.0.0.2), a network namespace: its platform, gateway and arena b1, on A's store and MySQL;
#   - C (10.0.0.3), a stranger on the internal network (10.0.0.50) and a player (198.51.100.7), namespaces too.
# The certificate is made by private-ca.sh and installed by install-certificate.sh, as 06 says. The firewall is
# 05 §5's commands for A, taken from the page, seen stopped, as RHEL 9 has it, and after; then B's and C's, and
# the install guide's §12, each on a firewall as RHEL 9 has it.
#
#   scripts/check-ports-el9.sh      from backend/, after make-release.sh. Needs docker, .NET 8 (DOTNET, default
#                                   /opt/dotnet/dotnet) to publish the C# headless client self-contained, and the
#                                   network once, to build the image. About ten minutes.
#
# The processes run as root, not as their users, with small heaps: neither changes where they listen.
set -u
if [ "${1:-}" != --inside ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REPO=$(cd "$HERE/../.." && pwd)
    REL=$(ls -d "$REPO"/backend/target/release/backend-*/ 2> /dev/null | head -1)
    [ -n "$REL" ] || { echo "no release in backend/target/release: scripts/make-release.sh first" >&2; exit 1; }
    DOTNET=${DOTNET:-$(command -v dotnet || echo /opt/dotnet/dotnet)}
    [ -x "$DOTNET" ] || { echo "no .NET 8 at $DOTNET (DOTNET=...): it publishes the C# client" >&2; exit 1; }
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-ports.XXXXXX")
    trap 'rm -rf "$WORK"' EXIT
    echo "--- publishing the C# headless client, self-contained for linux-x64"
    DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 "$DOTNET" publish "$REPO/client/Headless/Backend.Client.Headless.csproj" \
        -c Release -r linux-x64 --self-contained true -p:InvariantGlobalization=true -o "$WORK/headless" \
        > "$WORK/publish.log" 2>&1 || { tail -20 "$WORK/publish.log"; exit 1; }
    IMAGE=backend-check-ports-el9
    docker build -q -t "$IMAGE" - > /dev/null <<'EOF' || exit 1
FROM rockylinux/rockylinux:9.5
RUN dnf install -y -q systemd firewalld iproute nmap-ncat openssl procps-ng curl-minimal && dnf clean all
CMD ["/usr/sbin/init"]
EOF
    NAME="backend-check-ports-$$"
    docker run -d --rm --privileged --network none --name "$NAME" -v "$REL:/opt/backend:ro" \
        -v "$REPO/docs/operations/05-ports-and-firewall.md:/check/ports.md:ro" \
        -v "$REPO/docs/operations/03-install-guide.md:/check/install.md:ro" \
        -v "$REPO/backend/scripts/private-ca.sh:/check/private-ca.sh:ro" \
        -v "$WORK/headless:/check/headless:ro" \
        -v "$HERE/check-ports-el9.sh:/check.sh:ro" "$IMAGE" > /dev/null || exit 1
    docker exec "$NAME" bash /check.sh --inside
    rc=$?
    docker stop "$NAME" > /dev/null
    exit "$rc"
fi

R=/opt/backend
L=/var/tmp/check
mkdir -p "$L"
FAILS=0
ok()   { echo "  ok    $*"; }
fail() { echo "  FAIL  $*"; FAILS=$((FAILS + 1)); }
expect() {   # expect <what> <wanted> <got>
    if [ "$2" = "$3" ]; then ok "$1: $3"; else fail "$1: $3, not $2"; fi
}
words() { tr ' ' '\n' <<< "$*" | sed '/^$/d' | sort | tr '\n' ' ' | sed 's/ $//'; }   # firewalld's lists, in one order
PUB=198.51.100.1                  # the site's public address, here
CA=/root/backend-ca               # the root, made where 06 §2 says: off the servers, here for the check

for _ in $(seq 60); do
    case $(systemctl is-system-running 2> /dev/null) in running|degraded) break ;; esac
    sleep 1
done
systemctl enable --now firewalld > /dev/null 2>&1
for _ in $(seq 60); do [ "$(firewall-cmd --state 2> /dev/null)" = running ] && break; sleep 1; done
if [ "$(firewall-cmd --state 2> /dev/null)" != running ]; then
    # Without it nothing below can judge a firewall. Seen once: the host's inotify watches used up
    # (fs.inotify.max_user_watches), which the container's root shares, so the system bus failed.
    echo "--- firewalld did not start, so the check cannot judge the firewall:"
    systemctl --failed --no-legend | sed 's/^/      /'
    journalctl -b --no-pager -u dbus-broker | grep -m1 'fatal error' | sed 's/^/      /'
    echo "--- 1 failed"
    exit 1
fi
echo "--- $(cat /etc/rocky-release), $(rpm -q firewalld); firewalld $(firewall-cmd --state)"
echo "--- as RHEL 9 has it: default zone $(firewall-cmd --get-default-zone), its services: $(firewall-cmd --zone=public --list-services)"

# The networks. A's private address on a bridge, with B, C and a stranger; a public link to a player, who also
# routes the private addresses through it, as a neighbour on the provider's network could.
ip link add br-priv type bridge && ip addr add 10.0.0.1/24 dev br-priv && ip link set br-priv up
peer() {
    ip netns add "$1"
    ip link add "v-$1" type veth peer name eth0 netns "$1"
    ip link set "v-$1" master br-priv up
    ip -n "$1" addr add "$2/24" dev eth0 && ip -n "$1" link set eth0 up && ip -n "$1" link set lo up
}
peer b 10.0.0.2; peer c 10.0.0.3; peer stranger 10.0.0.50
ip netns add player
ip link add pub0 type veth peer name eth0 netns player
ip addr add "$PUB/24" dev pub0 && ip link set pub0 up
ip -n player addr add 198.51.100.7/24 dev eth0 && ip -n player link set eth0 up && ip -n player link set lo up
ip -n player route add 10.0.0.0/24 via "$PUB"
ip addr add fd00:51::1/64 dev pub0 nodad && ip -n player addr add fd00:51::7/64 dev eth0 nodad

# Secrets, and MySQL's own TLS, as the install guide and 01 §9 make them.
install -d -m 0755 /etc/backend /etc/backend/tls /etc/backend/nginx /etc/backend/mysql /etc/mysql-tls
install -d -m 0700 /etc/backend/credentials
for s in db-password store-password admin-token; do
    head -c 24 /dev/urandom | base64 | tr -d '/+=' > "/etc/backend/credentials/$s"
done
DBPW=$(cat /etc/backend/credentials/db-password)
STOREPW=$(cat /etc/backend/credentials/store-password)
useradd --system --home-dir /var/lib/backend-mysql --shell /usr/sbin/nologin mysql
useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin nginx
(
    cd /etc/mysql-tls || exit 1
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=backend MySQL CA" \
        -keyout ca-key.pem -out ca.pem 2> /dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=db" -keyout server-key.pem -out server.csr 2> /dev/null
    openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -days 2 \
        -extfile <(printf "subjectAltName=IP:10.0.0.1\nextendedKeyUsage=serverAuth") -out server-cert.pem 2> /dev/null
    chown mysql: server-key.pem
    "$R/runtime/bin/keytool" -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE \
        -importcert -noprompt -alias mysql-ca -file ca.pem \
        -storetype PKCS12 -keystore /etc/backend/tls/mysql-ca.p12 -storepass unused > /dev/null 2>&1
)

# MySQL, from the shipped configuration (only its buffer pool smaller); C's scratch server likewise.
sed 's/^innodb_buffer_pool_size .*/innodb_buffer_pool_size = 256M/' "$R/mysql-conf/backend.cnf.example" \
    > /etc/backend/mysql/backend.cnf
sed 's/^innodb_buffer_pool_size=.*/innodb_buffer_pool_size=128M/' "$R/mysql-conf/scratch-my.cnf.example" \
    > /etc/backend/scratch-my.cnf
install -d -o mysql -g mysql -m 0750 /var/lib/backend-mysql /var/log/backend-mysql /run/backend-mysql \
    /var/lib/backend-scratch-mysql /run/backend-scratch-mysql
for cnf in /etc/backend/mysql/backend.cnf /etc/backend/scratch-my.cnf; do
    "$R/mysql/bin/mysqld" --defaults-file="$cnf" --initialize-insecure --user=mysql > "$L/init.log" 2>&1 \
        || { cat "$L/init.log"; exit 1; }
    nohup "$R/mysql/bin/mysqld" --defaults-file="$cnf" --user=mysql > "$L/$(basename "$cnf").log" 2>&1 &
done
for _ in $(seq 60); do
    "$R/mysql/bin/mysqladmin" -S /run/backend-mysql/mysqld.sock -u root ping > /dev/null 2>&1 \
        && "$R/mysql/bin/mysqladmin" -S /run/backend-scratch-mysql/mysqld.sock -u root ping > /dev/null 2>&1 && break
    sleep 1
done
"$R/mysql/bin/mysql" -S /run/backend-mysql/mysqld.sock -u root -e "CREATE DATABASE backend;
    CREATE USER 'backend'@'10.0.0.%' IDENTIFIED BY '$DBPW' REQUIRE SSL;
    GRANT ALL ON backend.* TO 'backend'@'10.0.0.%';" || fail "MySQL's database and user"

# The session store, from the release's template: its port, the bind 01 §3 gives A, a password.
sed -e 's/^bind .*/bind 127.0.0.1 10.0.0.1/' -e "s/^requirepass .*/requirepass $STOREPW/" \
    -e 's|^dir .*|dir /var/lib/backend-store/session|' -e 's/^maxmemory .*/maxmemory 256mb/' \
    "$R/jredis/conf/j-redis.conf" > /etc/backend/store-session.conf
mkdir -p /var/lib/backend-store/session
J=("$R/runtime/bin/java" -Xms256m -Xmx512m --add-opens java.base/java.nio=ALL-UNNAMED)
nohup "${J[@]}" -Djava.net.preferIPv4Stack=true -jar "$R/jredis/lib/j-redis-server.jar" \
    /etc/backend/store-session.conf > "$L/store.log" 2>&1 &

# The settings, from the shipped examples as an operator fills them: A's as they are, but for the site's address
# here and the admin API named; B's with B's address where a process listens; C's first worker.
cp "$R/env/platform.env.example" /etc/backend/platform.env
sed -i 's/^#BACKEND_ADMIN_ADDR=/BACKEND_ADMIN_ADDR=/' /etc/backend/platform.env
cp "$R/env/gateway.env.example" /etc/backend/gateway.env
sed "s/^ADVERTISE_HOST=.*/ADVERTISE_HOST=$PUB/" "$R/env/arena.env.example" > /etc/backend/arena-a1.env
cp "$R/env/worker.env.example" /etc/backend/worker.env
cp "$R/env/worker-instance.env.example" /etc/backend/worker-c1.env
mkdir -p /etc/backend/b
sed 's/^PLATFORM_BIND=.*/PLATFORM_BIND=10.0.0.2/' /etc/backend/platform.env > /etc/backend/b/platform.env
sed -e 's/^GATEWAY_BIND=.*/GATEWAY_BIND=10.0.0.2/' -e 's#^PLATFORM_URL=.*#PLATFORM_URL=http://10.0.0.2:8080#' \
    /etc/backend/gateway.env > /etc/backend/b/gateway.env
sed -e 's/^ARENA_BIND=.*/ARENA_BIND=10.0.0.2/' -e 's/^BACKEND_ADVERTISE_TLS_NAME=.*/BACKEND_ADVERTISE_TLS_NAME=b1.arena.internal/' \
    /etc/backend/arena-a1.env > /etc/backend/b/arena-b1.env
envfile() {   # as systemd's EnvironmentFile= reads these: NAME=value lines, the value as it is
    local line
    while IFS= read -r line; do
        case $line in ''|'#'*) continue ;; esac
        export "$line"
    done < "$1"
}
export BACKEND_DB_PASSWORD_FILE=/etc/backend/credentials/db-password
export BACKEND_STORE_PASSWORD_FILE=/etc/backend/credentials/store-password
# Each as its unit's ExecStart has it, its arguments from its settings: platform <env>, and so on.
platform() {
    envfile "$1"
    export BACKEND_ADMIN_TOKEN_FILE=/etc/backend/credentials/admin-token
    exec "${J[@]}" -Dsun.net.httpserver.maxIdleConnections=1000 -cp "$R/lib/platform/*" \
        com.backend.platform.PlatformMain "$PLATFORM_BIND" "$PLATFORM_PORT" "$STORE_HOST" "$STORE_PORT"
}
gateway() {
    envfile "$1"
    exec "${J[@]}" -cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain \
        "$GATEWAY_BIND" "$GATEWAY_PORT" "$PLATFORM_URL" "$STORE_HOST" "$STORE_PORT" "$2"
}
arena() {
    envfile "$1"
    mkdir -p "/var/lib/backend/arena-$2/spool"
    exec "${J[@]}" -XX:+UseZGC -XX:+ZGenerational -cp "$R/lib/arena/*" com.backend.arena.ArenaMain \
        "$ARENA_PORT" "$MAP_SIZE" "$MAX_PLAYERS" "$SHAPES" "$MAX_ROOMS" "$STORE_HOST" "$STORE_PORT" "$2" \
        "$MATCH_SECONDS" "/var/lib/backend/arena-$2/spool" "$ARENA_BIND" "$ADVERTISE_HOST"
}
worker() {
    envfile /etc/backend/worker.env
    envfile "/etc/backend/worker-$1.env"
    exec "${J[@]}" -cp "$R/lib/worker/*" com.backend.worker.WorkerMain "$STORE_HOST" "$STORE_PORT" "worker-$1"
}
# What listens, each address as written here: Java's sockets are IPv6, and show 10.0.0.1 as [::ffff:10.0.0.1],
# an IPv4 address on them; * is the wildcard of both families.
seen() { ${2:-} ss -Hltn | awk '{ print $4 }' | sed 's/^\[::ffff:\(.*\)\]:/\1:/' | sort -u; }
listening() { seen "" "${2:-}" | grep -qxF "$1"; }
wait_for() {   # wait_for <address:port> <log> [ip netns exec b]
    for _ in $(seq 120); do listening "$1" "${3:-}" && return 0; sleep 1; done
    fail "nothing on $1 after 120 s; $2 ends:"; tail -5 "$2"; return 1
}
wait_for 10.0.0.1:6379 "$L/store.log"
(platform /etc/backend/platform.env) > "$L/platform.log" 2>&1 &
wait_for 10.0.0.1:8080 "$L/platform.log"
(worker c1) > "$L/worker.log" 2>&1 &
(gateway /etc/backend/gateway.env "$(hostname)") > "$L/gateway.log" 2>&1 &
(arena /etc/backend/arena-a1.env a1) > "$L/arena-a1.log" 2>&1 &

# The certificate, as 06 §2 to §4 make and install it: the root, a certificate for the site's address and every
# arena in nginx's map, installed before nginx has ever started. Another root, for the client to refuse.
cp "$R"/nginx-conf/*.conf /etc/backend/nginx/
sed -i "s/203\.0\.113\.10/$PUB/" /etc/backend/nginx/backend.conf
install -d -o nginx -g nginx /run/backend-nginx /var/log/backend-nginx /var/cache/backend-nginx
bash /check/private-ca.sh root "$CA" > "$L/ca.log" 2>&1 || { cat "$L/ca.log"; fail "private-ca.sh root"; }
bash /check/private-ca.sh root /root/another-ca > /dev/null 2>&1 || fail "private-ca.sh root, a second"
bash /check/private-ca.sh issue "$CA" /root/backend-certificate "$PUB" \
    $(bash /check/private-ca.sh names /etc/backend/nginx/backend-stream.conf) > "$L/issue.log" 2>&1 \
    || { cat "$L/issue.log"; fail "private-ca.sh issue"; }
install -m 0644 "$CA/ca.pem" /etc/backend/tls/ca.pem
export NGINX_RUNNING="pgrep -x nginx" NGINX_RELOAD="$R/nginx/sbin/nginx -s reload"
"$R/scripts/install-certificate.sh" /root/backend-certificate/fullchain.pem /root/backend-certificate/privkey.pem \
    > "$L/install-certificate.log" 2>&1 || { cat "$L/install-certificate.log"; fail "install-certificate.sh"; }
grep -q 'not running; the files are installed' "$L/install-certificate.log" \
    && ok "the certificate installed before nginx started" || fail "install-certificate.sh: $(tail -1 "$L/install-certificate.log")"
"$R/nginx/sbin/nginx" -t -q && "$R/nginx/sbin/nginx" || fail "nginx did not start"
nohup ncat -lk 22 --exec /bin/cat > /dev/null 2>&1 &         # the stand-in for sshd: it echoes
nohup ip netns exec player ncat -lk 198.51.100.7 4444 > /dev/null 2>&1 &    # somewhere outside, for A to reach

EXPECTED=(0.0.0.0:443 [::]:443 0.0.0.0:9001 [::]:9001 0.0.0.0:22 [::]:22 10.0.0.1:3306 10.0.0.1:6379 127.0.0.1:6379
          10.0.0.1:8080 10.0.0.1:8090 10.0.0.1:9011 127.0.0.1:9101 127.0.0.1:9103 127.0.0.1:9110 127.0.0.1:9120
          127.0.0.1:9102 127.0.0.1:3309)
for a in "${EXPECTED[@]}"; do
    wait_for "$a" "$L/$(case $a in *:443|*:9001) echo ../../var/log/backend-nginx/error ;; *:9011|*:9110) echo arena-a1 ;;
        *:3306) echo backend.cnf ;; *:3309) echo scratch-my.cnf ;; *:6379) echo store ;; *:8090|*:9103) echo gateway ;;
        *:9102) echo worker ;; *) echo platform ;; esac).log"
done
sleep 3

echo "--- listening on A (ss -ltnp), the stand-in for sshd left out:"
ss -Hltnp | awk '$4 !~ /:22$/ { split($6, p, "\""); printf "      %-26s %s\n", $4, p[2] }' | sort -t: -k2 -n
printf '%s\n' "${EXPECTED[@]}" | sort -u > "$L/expected"
seen > "$L/seen"
expect "listening beyond §2's" "" "$(comm -13 "$L/expected" "$L/seen" | tr '\n' ' ')"
expect "§2's not listening" "" "$(comm -23 "$L/expected" "$L/seen" | tr '\n' ' ')"
expect "the arena's rooms, as the example sets them" "maxRooms 12" "$(grep -o 'maxRooms [0-9]*' "$L/arena-a1.log" | head -1)"
grep -q "announcing as a1 at $PUB:9001, TLS as a1.arena.internal" "$L/arena-a1.log" \
    && ok "a1 announces the site's address, 9001 and its name" || fail "a1 announces: $(grep -m1 announcing "$L/arena-a1.log")"

probe() {   # probe <namespace, or - for A itself> <host> <port>: open, or what bash's connect said
    local out rc
    if [ "$1" = - ]; then out=$(timeout 3 bash -c "</dev/tcp/$2/$3" 2>&1)
    else out=$(ip netns exec "$1" timeout 3 bash -c "</dev/tcp/$2/$3" 2>&1); fi
    rc=$?
    if [ "$rc" = 0 ]; then echo open; elif [ "$rc" = 124 ]; then echo timeout; else echo "${out##*: }"; fi
}
# From, to, then what each state should give: the firewall stopped, as RHEL 9 has it, after §5. A refusal by
# firewalld reads "No route to host" over IPv4, "Permission denied" over IPv6.
O=open; N="No route to host"; N6="Permission denied"; C="Connection refused"
MATRIX=(
    "player $PUB 443|$O|$N|$O"            "player $PUB 9001|$O|$N|$O"
    "player $PUB 80|$C|$N|$N"             "player $PUB 22|$O|$O|$N"
    "player $PUB 9090|$C|$C|$N"           "player $PUB 8080|$C|$N|$N"
    "player $PUB 9011|$C|$N|$N"           "player $PUB 9120|$C|$N|$N"
    "player 10.0.0.1 6379|$O|$N|$N"       "player 10.0.0.1 3306|$O|$N|$N"
    "player 10.0.0.1 8080|$O|$N|$N"       "player 10.0.0.1 9011|$O|$N|$N"
    "player fd00:51::1 443|$O|$N6|$O"     "player fd00:51::1 9001|$O|$N6|$O"
    "player fd00:51::1 22|$O|$O|$N6"
    "b 10.0.0.1 6379|$O|$N|$O"            "b 10.0.0.1 3306|$O|$N|$O"
    "b 10.0.0.1 8080|$O|$N|$O"            "b 10.0.0.1 8090|$O|$N|$O"
    "b 10.0.0.1 9011|$O|$N|$O"            "b 10.0.0.1 22|$O|$O|$O"
    "b 10.0.0.1 443|$O|$N|$N"
    "c 10.0.0.1 6379|$O|$N|$O"            "c 10.0.0.1 9011|$O|$N|$O"
    "stranger 10.0.0.1 22|$O|$O|$O"       "stranger 10.0.0.1 6379|$O|$N|$N"
    "stranger 10.0.0.1 8080|$O|$N|$N"     "stranger 10.0.0.1 9011|$O|$N|$N"
    "stranger 10.0.0.1 443|$O|$N|$O"
    "- 127.0.0.1 9120|$O|$O|$O"           "- 127.0.0.1 6379|$O|$O|$O"
    "- 10.0.0.1 3306|$O|$O|$O"            "- 10.0.0.1 8080|$O|$O|$O"
    "- 198.51.100.7 4444|$O|$O|$O"
)
matrix() {   # matrix <column: 1 stopped, 2 as RHEL 9 has it, 3 after §5> <title>
    echo "--- $2"
    local row from host port want got
    for row in "${MATRIX[@]}"; do
        read -r from host port <<< "${row%%|*}"
        want=$(cut -d'|' -f$(($1 + 1)) <<< "$row")
        got=$(probe "$from" "$host" "$port")
        expect "$(printf '%-8s -> %s:%s' "${from/-/A}" "$host" "$port")" "$want" "$got"
    done
}
systemctl stop firewalld
matrix 1 "the firewall stopped: only the addresses decide"
systemctl start firewalld
matrix 2 "firewalld as RHEL 9 has it"

block() {   # block <name> [page]: the commands under <!-- firewall name -->
    awk -v m="<!-- firewall $1 -->" '$0 == m { on = 1; next } on && /^```(bash|console)/ { inb = 1; next }
                                     inb && /^```/ { exit } inb' "${2:-/check/ports.md}"
}
echo "--- §5's commands for A, as the page gives them, while a player's SSH session is open:"
coproc SESSION { ip netns exec player ncat "$PUB" 22; }
got=; echo before >&"${SESSION[1]}"; read -r -t 3 got <&"${SESSION[0]}"
expect "the player's session, before" before "$got"
block A > "$L/firewall-A.sh"
[ -s "$L/firewall-A.sh" ] || fail "no block for A in the page"
bash -x "$L/firewall-A.sh" 2>&1 | sed 's/^/      /'
got=; echo after >&"${SESSION[1]}"; read -r -t 3 got <&"${SESSION[0]}"
expect "the same session, after --reload: it goes on" after "$got"
kill "$SESSION_PID" 2> /dev/null
matrix 3 "after §5's commands for A"
echo "--- the zones:"
firewall-cmd --get-active-zones | sed 's/^/      /'
firewall-cmd --zone=backend --list-all | sed 's/^/      /'
expect "the public zone's services" "$(words dhcpv6-client https)" "$(words $(firewall-cmd --zone=public --list-services))"
expect "the public zone's ports" "9001/tcp" "$(firewall-cmd --zone=public --list-ports)"
firewall-cmd --zone=public --list-rich-rules | grep -q 'source address="10.0.0.0/24" service name="ssh" accept' \
    && ok "SSH from the internal network alone, by a rule" || fail "no rule for SSH from 10.0.0.0/24"
ip netns exec player ping -c 1 -W 2 "$PUB" > /dev/null 2>&1 && ok "a player's ping answered" || fail "a player's ping not answered"

echo "--- B's platform, gateway and arena b1, in B's namespace, on A's store and MySQL through the firewall:"
(ip netns exec b bash -c "$(declare -p J R BACKEND_DB_PASSWORD_FILE BACKEND_STORE_PASSWORD_FILE); $(declare -f envfile platform); platform /etc/backend/b/platform.env") > "$L/b-platform.log" 2>&1 &
wait_for 10.0.0.2:8080 "$L/b-platform.log" "ip netns exec b"
(ip netns exec b bash -c "$(declare -p J R BACKEND_DB_PASSWORD_FILE BACKEND_STORE_PASSWORD_FILE); $(declare -f envfile gateway); gateway /etc/backend/b/gateway.env gateway-b") > "$L/b-gateway.log" 2>&1 &
(ip netns exec b bash -c "$(declare -p J R BACKEND_DB_PASSWORD_FILE BACKEND_STORE_PASSWORD_FILE); $(declare -f envfile arena); arena /etc/backend/b/arena-b1.env b1") > "$L/b-arena.log" 2>&1 &
wait_for 10.0.0.2:8090 "$L/b-gateway.log" "ip netns exec b"
wait_for 10.0.0.2:9011 "$L/b-arena.log" "ip netns exec b"
sleep 4

echo "--- through the front, as a player, trusting the backend's root alone:"
curlp() { ip netns exec player curl -s --max-time 10 --cacert "$CA/ca.pem" "$@"; }
for i in 1 2 3 4; do
    code=$(curlp -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "{\"username\":\"portcheck$i\",\"password\":\"port-check-$i\"}" "https://$PUB/v1/accounts")
    expect "a registration through nginx, by its IP's certificate ($i)" 201 "$code"
done
lobby=$(curlp --http1.1 --max-time 3 -i -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "https://$PUB/lobby" 2> /dev/null | head -1 | tr -d '\r')
expect "the lobby through nginx" "HTTP/1.1 101 Switching Protocols" "$lobby"
expect "nothing on 80" "000" "$(ip netns exec player curl -s --max-time 5 -o /dev/null -w '%{http_code}' "http://$PUB/")"
# s_client prints "Verify return code: 0 (ok)" when no certificate came at all, a port speaking no TLS: the verdict
# counts only beside the certificate's subject.
# (The subject's spacing is OpenSSL's version's: "CN = backend" in 3.0, "CN=backend" in 3.2.)
verdict() { awk '/^subject=/ { s = 1 } /Verify return code/ { sub(/^ */, ""); v = $0 } END { print (s ? "a certificate, " v : "no certificate") }'; }
for name in a1.arena.internal b1.arena.internal; do
    v=$(echo | ip netns exec player openssl s_client -connect "$PUB:9001" -servername "$name" -verify_hostname "$name" \
        -CAfile "$CA/ca.pem" 2> /dev/null | verdict)
    expect "9001, as $name" "a certificate, Verify return code: 0 (ok)" "$v"
done
v=$(echo | ip netns exec player openssl s_client -connect "$PUB:9001" -servername nope.arena.internal \
    -verify_hostname nope.arena.internal -CAfile "$CA/ca.pem" 2> /dev/null | verdict)
expect "9001, a name not on the certificate" "a certificate, Verify return code: 62 (hostname mismatch)" "$v"
v=$(echo | ip netns exec player openssl s_client -connect "$PUB:9001" -servername a1.arena.internal \
    -CAfile /root/another-ca/ca.pem 2> /dev/null | verdict)
expect "9001, trusting another root" "a certificate, Verify return code: 21 (unable to verify the first certificate)" "$v"
"$R/scripts/install-certificate.sh" --check > "$L/certificate-check.log" 2>&1 \
    && ok "install-certificate.sh --check: $(grep -c '(ok)$' "$L/certificate-check.log") names, each ok" \
    || { cat "$L/certificate-check.log"; fail "install-certificate.sh --check"; }
sed 's/^/      /' "$L/certificate-check.log"

echo "--- the C# headless client, in the player's namespace, through 443 and 9001:"
# The scenarios that need nothing of the drill's (its database helper, its admin token, its catalogue: equip buys
# the drill's own item); replaced puts two lobby connections on two gateways, as nginx spreads them (T-60).
SCENARIOS=(play resume coldresume badticket lifecycle untrusted lobby duel walkover guest replaced badsession phrase
           decline party prediction)
ip netns exec player env HOME=/tmp DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 /check/headless/Backend.Client.Headless \
    "https://$PUB" --lobby "wss://$PUB/lobby" --trust "$CA/ca.pem" --wrong-trust /root/another-ca/ca.pem \
    "${SCENARIOS[@]}" > "$L/headless.log" 2>&1
rc=$?
grep -E '^--- |FAIL|PASSED|FAILED' "$L/headless.log" | sed 's/^/      /' | tail -30
[ "$rc" = 0 ] && grep -q 'ALL PASSED' "$L/headless.log" && ok "the C# client: ${#SCENARIOS[@]} scenarios, all passed" \
    || fail "the C# client: exit $rc"

echo "--- where nginx sent them:"
STREAM=/var/log/backend-nginx/stream.log
bad=$(awk '{ name = $4; gsub(/"/, "", name); up = $5
             want = name == "a1.arena.internal" ? "10.0.0.1:9011" : name == "b1.arena.internal" ? "10.0.0.2:9011" : ""
             if (want != "" && up != want) print name "->" up }' "$STREAM" | sort -u | tr '\n' ' ')
expect "every match connection to the arena its name gives" "" "$bad"
for pair in "a1.arena.internal 10.0.0.1:9011" "b1.arena.internal 10.0.0.2:9011"; do
    n=$(awk -v name="\"${pair% *}\"" -v up="${pair#* }" '$4 == name && $5 == up' "$STREAM" | wc -l)
    [ "$n" -gt 0 ] && ok "${pair% *}: $n connections to ${pair#* }" || fail "${pair% *}: no connection to ${pair#* }"
done
grep -q '"nope.arena.internal" ' "$STREAM" && ! grep '"nope.arena.internal" ' "$STREAM" | grep -q ' 10\.0\.0\.' \
    && ok "a name not in the map: no arena" || fail "a name not in the map reached one"
ACCESS=/var/log/backend-nginx/access.log
for up in 10.0.0.1:8080 10.0.0.2:8080 10.0.0.1:8090 10.0.0.2:8090; do
    grep -q " $up\( \|,\)" "$ACCESS" && ok "nginx sent requests to $up" || fail "no request went to $up"
done
grep -q '10\.0\.0\.3:80[89]0, ' "$ACCESS" && ok "C, which runs none, passed over: $(grep -o '10\.0\.0\.3:80[89]0, [0-9.:]*' "$ACCESS" | head -1)" \
    || fail "nothing shows C passed over"
keys=$(printf 'AUTH %s\nSCAN 0 MATCH rl:login:addr:* COUNT 10000\n' "$STOREPW" \
    | JAVA_HOME="$R/runtime" "$R/jredis/bin/j-redis-cli" --raw -h 127.0.0.1 -p 6379 2> /dev/null | grep '^rl:login:addr:' | sort -u)
echo "$keys" | sed 's/^/      /'
[ -n "$keys" ] && ! grep -q 'rl:login:addr:10\.0\.0\.' <<< "$keys" && grep -q 'rl:login:addr:198\.51\.100\.7:' <<< "$keys" \
    && ok "the platforms counted the player's own address, not the front's" || fail "the login limits counted: $keys"

echo "--- A's platform stopped: nginx passes it over"
pkill -f 'com.backend.platform.PlatformMain 10.0.0.1 ' ; sleep 2
code=$(curlp -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    -d '{"username":"portcheckz","password":"port-check-z"}' "https://$PUB/v1/accounts")
expect "a registration with A's platform down" 201 "$code"

echo "--- after a restart of firewalld, the rules still hold:"
systemctl restart firewalld
expect "B to 6379" open "$(probe b 10.0.0.1 6379)"
expect "a player to 443" open "$(probe player "$PUB" 443)"
expect "the stranger to 6379" "No route to host" "$(probe stranger 10.0.0.1 6379)"
echo "--- A's commands run a second time:"
bash "$L/firewall-A.sh" 2>&1 | sed 's/^/      /'

for m in B C; do
    echo "--- §5's commands for $m, on a firewall as RHEL 9 has it:"
    rm -f /etc/firewalld/zones/*.xml /etc/firewalld/zones/*.xml.old
    firewall-cmd -q --reload
    block "$m" > "$L/firewall-$m.sh"
    bash "$L/firewall-$m.sh" 2>&1 | sort | uniq -c | sed 's/^/      /'
    case $m in
        B) want_src="10.0.0.1 10.0.0.3"; want_ports="6379/tcp 6380/tcp 8080/tcp 8090/tcp 9011-9013/tcp" ;;
        C) want_src="10.0.0.1 10.0.0.2"; want_ports="6380/tcp 3306/tcp 8080/tcp 8090/tcp 9011-9012/tcp" ;;
    esac
    expect "$m: the backend zone's sources" "$(words $want_src)" "$(words $(firewall-cmd --zone=backend --list-sources))"
    expect "$m: its ports" "$(words $want_ports)" "$(words $(firewall-cmd --zone=backend --list-ports))"
    expect "$m: its services" "ssh" "$(firewall-cmd --zone=backend --list-services)"
    expect "$m: the public zone's ports" "9001/tcp" "$(firewall-cmd --zone=public --list-ports)"
    expect "$m: the public zone's services" "$(words dhcpv6-client https)" "$(words $(firewall-cmd --zone=public --list-services))"
done

echo "--- the install guide's §12, for one machine, on a firewall as RHEL 9 has it:"
rm -f /etc/firewalld/zones/*.xml /etc/firewalld/zones/*.xml.old
firewall-cmd -q --reload
block "one machine" /check/install.md > "$L/firewall-one.sh"
[ -s "$L/firewall-one.sh" ] || fail "no firewall block in the install guide"
bash "$L/firewall-one.sh" 2>&1 | sort | uniq -c | sed 's/^/      /'
expect "one machine: the public zone's services" "$(words dhcpv6-client https)" "$(words $(firewall-cmd --zone=public --list-services))"
expect "one machine: its ports" "9001/tcp" "$(firewall-cmd --zone=public --list-ports)"
expect "one machine: a player to 443" open "$(probe player "$PUB" 443)"
expect "one machine: a player to 22" "No route to host" "$(probe player "$PUB" 22)"
expect "one machine: the internal network to 22" open "$(probe stranger 10.0.0.1 22)"

echo "--- a service and a port in one command, as the install guide's §12 had it until 2026-10-19:"
rm -f /etc/firewalld/zones/*.xml /etc/firewalld/zones/*.xml.old
firewall-cmd -q --reload
firewall-cmd --permanent --add-service=https --add-service=http --add-port=9001/tcp 2>&1 | tail -1 | sed 's/^/      /'
firewall-cmd -q --reload
expect "applied of it: services" "cockpit dhcpv6-client ssh" "$(firewall-cmd --zone=public --list-services)"
expect "applied of it: ports" "" "$(firewall-cmd --zone=public --list-ports)"

echo "--- the local ports outgoing connections take: $(cat /proc/sys/net/ipv4/ip_local_port_range)"
echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
