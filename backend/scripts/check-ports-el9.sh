#!/bin/bash
# Checks docs/operations/05-ports-and-firewall.md as written, for machine A: a release's processes,
# each with the settings the release ships (deploy/env, mysql, nginx), listening where §2 says and
# nowhere else; then firewalld seen from B and C (10.0.0.2, 10.0.0.3), another machine on the private
# network (10.0.0.50) and a player (198.51.100.7), each in a network namespace of its own: with the
# firewall stopped, as a RHEL 9 server has it, and after §5's commands for A, run as the page gives
# them. Then B's and C's commands, each on a firewall as RHEL 9 has it. In Rocky Linux 9.5, RHEL 9's
# firewalld, systemd its first process, with no network.
#
#   scripts/check-ports-el9.sh        from backend/, after make-release.sh; needs docker, and the
#                                     network once, to build the image; about two minutes after that
#
# Besides A's own processes it starts two of C's, the worker and the scratch MySQL, for their ports,
# and a stand-in for sshd on 22 (the image has none). The processes run as root, not as their users,
# with small heaps: neither changes where they listen.
set -u
if [ "${1:-}" != --inside ]; then
    HERE=$(cd "$(dirname "$0")" && pwd)
    REPO=$(cd "$HERE/../.." && pwd)
    REL=$(ls -d "$REPO"/backend/target/release/backend-*/ 2> /dev/null | head -1)
    [ -n "$REL" ] || { echo "no release in backend/target/release: scripts/make-release.sh first" >&2; exit 1; }
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
echo "--- the local ports outgoing connections take: $(cat /proc/sys/net/ipv4/ip_local_port_range)"

# The networks. A's private address on a bridge, with B, C and a stranger; a public link to a player,
# who also routes the private addresses through it, as a neighbour on the provider's network could.
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
ip addr add 198.51.100.1/24 dev pub0 && ip link set pub0 up
ip -n player addr add 198.51.100.7/24 dev eth0 && ip -n player link set eth0 up && ip -n player link set lo up
ip -n player route add 10.0.0.0/24 via 198.51.100.1
ip addr add fd00:51::1/64 dev pub0 nodad && ip -n player addr add fd00:51::7/64 dev eth0 nodad

# Secrets and TLS, as the install guide and 01 §9 make them.
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

# The settings, from the shipped examples as an operator fills them: the admin API named; the
# machine's one arena (D-81); C's first worker.
cp "$R/env/platform.env.example" /etc/backend/platform.env
sed -i 's/^#BACKEND_ADMIN_ADDR=/BACKEND_ADMIN_ADDR=/' /etc/backend/platform.env
cp "$R/env/gateway.env.example" /etc/backend/gateway.env
cp "$R/env/arena.env.example" /etc/backend/arena-a1.env
cp "$R/env/worker.env.example" /etc/backend/worker.env
cp "$R/env/worker-instance.env.example" /etc/backend/worker-c1.env
envfile() {   # as systemd's EnvironmentFile= reads these: NAME=value lines, the value as it is
    local line
    while IFS= read -r line; do
        case $line in ''|'#'*) continue ;; esac
        export "$line"
    done < "$1"
}
export BACKEND_DB_PASSWORD_FILE=/etc/backend/credentials/db-password
export BACKEND_STORE_PASSWORD_FILE=/etc/backend/credentials/store-password
# Each as its unit's ExecStart has it, its arguments from its settings.
platform() {
    envfile /etc/backend/platform.env
    export BACKEND_ADMIN_TOKEN_FILE=/etc/backend/credentials/admin-token
    exec "${J[@]}" -Dsun.net.httpserver.maxIdleConnections=1000 -cp "$R/lib/platform/*" \
        com.backend.platform.PlatformMain "$PLATFORM_BIND" "$PLATFORM_PORT" "$STORE_HOST" "$STORE_PORT"
}
gateway() {
    envfile /etc/backend/gateway.env
    exec "${J[@]}" -cp "$R/lib/gateway/*" com.backend.gateway.GatewayMain \
        "$GATEWAY_BIND" "$GATEWAY_PORT" "$PLATFORM_URL" "$STORE_HOST" "$STORE_PORT" "$(hostname)"
}
arena() {
    envfile "/etc/backend/arena-$1.env"
    mkdir -p "/var/lib/backend/arena-$1/spool"
    exec "${J[@]}" -XX:+UseZGC -XX:+ZGenerational -cp "$R/lib/arena/*" com.backend.arena.ArenaMain \
        "$ARENA_PORT" "$MAP_SIZE" "$MAX_PLAYERS" "$SHAPES" "$MAX_ROOMS" "$STORE_HOST" "$STORE_PORT" "$1" \
        "$MATCH_SECONDS" "/var/lib/backend/arena-$1/spool" "$ARENA_BIND" "$ADVERTISE_HOST"
}
worker() {
    envfile /etc/backend/worker.env
    envfile "/etc/backend/worker-$1.env"
    exec "${J[@]}" -cp "$R/lib/worker/*" com.backend.worker.WorkerMain "$STORE_HOST" "$STORE_PORT" "worker-$1"
}
# What listens, each address as written here: Java's sockets are IPv6, and show 127.0.0.1 as
# [::ffff:127.0.0.1], an IPv4 address on them; * is the wildcard of both families.
seen() { ss -Hltn | awk '{ print $4 }' | sed 's/^\[::ffff:\(.*\)\]:/\1:/' | sort -u; }
listening() { seen | grep -qxF "$1"; }
wait_for() {   # wait_for <address:port> <log>
    for _ in $(seq 120); do listening "$1" && return 0; sleep 1; done
    fail "nothing on $1 after 120 s; $2 ends:"; tail -5 "$2"; return 1
}
wait_for 10.0.0.1:6379 "$L/store.log"
(platform) > "$L/platform.log" 2>&1 &
wait_for 127.0.0.1:8080 "$L/platform.log"
(worker c1) > "$L/worker.log" 2>&1 &
(gateway) > "$L/gateway.log" 2>&1 &
(arena a1) > "$L/arena-a1.log" 2>&1 &

# nginx, the release's, with the shipped configuration; a throwaway certificate for its name.
cp "$R"/nginx-conf/*.conf /etc/backend/nginx/
install -d -o nginx -g nginx /run/backend-nginx /var/log/backend-nginx /var/cache/backend-nginx
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=a.example.com" -addext "subjectAltName=DNS:a.example.com" \
    -keyout /etc/backend/credentials/privkey.pem -out /etc/backend/tls/fullchain.pem 2> /dev/null
"$R/nginx/sbin/nginx" -t -q && "$R/nginx/sbin/nginx" || fail "nginx did not start"
nohup ncat -lk 22 > /dev/null 2>&1 &                          # the stand-in for sshd
nohup ip netns exec player ncat -lk 198.51.100.7 4444 > /dev/null 2>&1 &    # somewhere outside, for A to reach

for a in 0.0.0.0:80 [::]:80 0.0.0.0:443 [::]:443 0.0.0.0:22 '*:9001' 10.0.0.1:3306 \
         10.0.0.1:6379 127.0.0.1:6379 127.0.0.1:8080 127.0.0.1:8090 127.0.0.1:9101 127.0.0.1:9103 \
         127.0.0.1:9110 127.0.0.1:9120 127.0.0.1:9102 127.0.0.1:3309; do
    wait_for "$a" "$L/$(case $a in *:80|*:443) echo nginx ;; *:9001|*:9110) echo arena-a1 ;;
        *:3306) echo backend.cnf ;; *:3309) echo scratch-my.cnf ;;
        *:6379) echo store ;; *:8090|*:9103) echo gateway ;; *:9102) echo worker ;; *) echo platform ;; esac).log"
done
sleep 3

echo "--- listening (ss -ltnp), the stand-in for sshd left out:"
ss -Hltnp | awk '$4 !~ /:22$/ { split($6, p, "\""); printf "      %-22s %s\n", $4, p[2] }' | sort -t: -k2 -n
for a in 0.0.0.0:80 [::]:80 0.0.0.0:443 [::]:443 '*:9001' 10.0.0.1:3306 10.0.0.1:6379 \
         127.0.0.1:6379 127.0.0.1:8080 127.0.0.1:8090 127.0.0.1:9101 127.0.0.1:9103 127.0.0.1:9110 \
         127.0.0.1:9120 127.0.0.1:9102 127.0.0.1:3309 0.0.0.0:22 [::]:22; do
    echo "$a"
done | sort > "$L/expected"
seen > "$L/seen"
extra=$(comm -13 "$L/expected" "$L/seen" | tr '\n' ' ')
missing=$(comm -23 "$L/expected" "$L/seen" | tr '\n' ' ')
expect "listening beyond §2's" "" "$extra"
expect "§2's not listening" "" "$missing"
expect "the machine's one arena, its rooms as the example sets them" "maxRooms 12" \
    "$(grep -o 'maxRooms [0-9]*' "$L/arena-a1.log" | head -1)"

probe() {   # probe <namespace, or - for A itself> <host> <port>: open, or what bash's connect said
    local out rc
    if [ "$1" = - ]; then out=$(timeout 3 bash -c "</dev/tcp/$2/$3" 2>&1)
    else out=$(ip netns exec "$1" timeout 3 bash -c "</dev/tcp/$2/$3" 2>&1); fi
    rc=$?
    if [ "$rc" = 0 ]; then echo open; elif [ "$rc" = 124 ]; then echo timeout; else echo "${out##*: }"; fi
}
# From, to, then what each state should give: the firewall stopped, as RHEL 9 has it, after §5.
# A refusal by firewalld reads "No route to host" over IPv4, "Permission denied" over IPv6.
O=open; N="No route to host"; N6="Permission denied"; C="Connection refused"
MATRIX=(
    "player 198.51.100.1 443|$O|$N|$O"   "player 198.51.100.1 80|$O|$N|$O"
    "player 198.51.100.1 9001|$O|$N|$O"  "player 198.51.100.1 9002|$C|$N|$N"
    "player 198.51.100.1 22|$O|$O|$O"    "player 198.51.100.1 8080|$C|$N|$N"
    "player 198.51.100.1 9120|$C|$N|$N"  "player 10.0.0.1 6379|$O|$N|$N"
    "player 10.0.0.1 3306|$O|$N|$N"
    "player fd00:51::1 443|$O|$N6|$O"    "player fd00:51::1 9001|$O|$N6|$O"
    "player fd00:51::1 22|$O|$O|$O"      "player fd00:51::1 8080|$C|$N6|$N6"
    "b 10.0.0.1 6379|$O|$N|$O"           "b 10.0.0.1 3306|$O|$N|$O"
    "b 10.0.0.1 22|$O|$O|$O"             "b 10.0.0.1 443|$O|$N|$N"
    "b 10.0.0.1 9001|$O|$N|$N"           "b 10.0.0.1 8080|$C|$N|$N"
    "c 10.0.0.1 6379|$O|$N|$O"           "c 10.0.0.1 3306|$O|$N|$O"
    "stranger 10.0.0.1 6379|$O|$N|$N"    "stranger 10.0.0.1 3306|$O|$N|$N"
    "stranger 10.0.0.1 22|$O|$O|$O"      "stranger 10.0.0.1 443|$O|$N|$O"
    "- 127.0.0.1 8080|$O|$O|$O"          "- 127.0.0.1 9120|$O|$O|$O"
    "- 127.0.0.1 6379|$O|$O|$O"          "- 10.0.0.1 3306|$O|$O|$O"
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

echo "--- §5's commands for A, as the page gives them:"
block() {   # block <name> [page]: the commands under <!-- firewall name -->
    awk -v m="<!-- firewall $1 -->" '$0 == m { on = 1; next } on && /^```(bash|console)/ { inb = 1; next }
                                     inb && /^```/ { exit } inb' "${2:-/check/ports.md}"
}
block A > "$L/firewall-A.sh"
[ -s "$L/firewall-A.sh" ] || fail "no block for A in the page"
bash -x "$L/firewall-A.sh" 2>&1 | sed 's/^/      /'
matrix 3 "after §5's commands for A"
echo "--- the zones:"
firewall-cmd --get-active-zones | sed 's/^/      /'
firewall-cmd --zone=backend --list-all | sed 's/^/      /'
expect "the public zone's services" "cockpit dhcpv6-client http https ssh" "$(firewall-cmd --zone=public --list-services)"
expect "the public zone's ports" "9001/tcp" "$(firewall-cmd --zone=public --list-ports)"

echo "--- through the opened ports, the processes themselves answer:"
code=$(ip netns exec player curl -sk --max-time 10 --resolve a.example.com:443:198.51.100.1 -o /dev/null -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' -d '{"username":"portcheck","password":"port-check-1"}' \
    https://a.example.com/v1/accounts)
expect "a player's registration through nginx, the platform, MySQL over TLS" 201 "$code"
lobby=$(ip netns exec player curl -sk --http1.1 --max-time 3 -i --resolve a.example.com:443:198.51.100.1 \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://a.example.com/lobby 2> /dev/null | head -1 | tr -d '\r')
expect "a player's lobby through nginx and the gateway" "HTTP/1.1 101 Switching Protocols" "$lobby"
expect "a player on port 80" "HTTPS only" "$(ip netns exec player curl -s --max-time 5 http://198.51.100.1/)"
pong=$(printf 'PING\r\n' | ip netns exec b ncat -w 3 10.0.0.1 6379 2> /dev/null | head -1 | tr -d '\r')
expect "B to the session store" "-NOAUTH Authentication required." "$pong"
greeting=$(ip netns exec b ncat -w 3 10.0.0.1 3306 < /dev/null 2> /dev/null | tr -c '[:print:]' ' ' | grep -o '8\.4\.[0-9]*' | head -1)
[ -n "$greeting" ] && ok "B to MySQL: its greeting, $greeting" || fail "B to MySQL: no greeting"
T="Authorization: Bearer $(cat /etc/backend/credentials/admin-token)"
arenas=$(curl -s --max-time 5 -H "$T" http://127.0.0.1:9120/admin/arenas)
for a in a1:9001; do
    if grep -q "\"${a%%:*}\"" <<< "$arenas" && grep -q "${a##*:}" <<< "$arenas"; then
        ok "the admin API lists arena ${a%%:*} on ${a##*:}"
    else
        fail "the admin API does not list arena ${a%%:*} on ${a##*:}: $arenas"
    fi
done

echo "--- after a restart of firewalld, the rules still hold:"
systemctl restart firewalld
expect "B to 6379" open "$(probe b 10.0.0.1 6379)"
expect "a player to 443" open "$(probe player 198.51.100.1 443)"
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
        B) want_src="10.0.0.1 10.0.0.3"; want_ports="6379/tcp 6380/tcp"; want_public="9001/tcp" ;;
        C) want_src="10.0.0.1 10.0.0.2"; want_ports="6380/tcp 3306/tcp"; want_public="9001/tcp" ;;
    esac
    expect "$m: the backend zone's sources" "$(words $want_src)" "$(words $(firewall-cmd --zone=backend --list-sources))"
    expect "$m: its ports" "$(words $want_ports)" "$(words $(firewall-cmd --zone=backend --list-ports))"
    expect "$m: its services" "ssh" "$(firewall-cmd --zone=backend --list-services)"
    expect "$m: the public zone's ports" "$want_public" "$(firewall-cmd --zone=public --list-ports)"
done

echo "--- the install guide's §12, for one machine, on a firewall as RHEL 9 has it:"
rm -f /etc/firewalld/zones/*.xml /etc/firewalld/zones/*.xml.old
firewall-cmd -q --reload
block "one machine" /check/install.md > "$L/firewall-one.sh"
[ -s "$L/firewall-one.sh" ] || fail "no firewall block in the install guide"
bash "$L/firewall-one.sh" 2>&1 | sort | uniq -c | sed 's/^/      /'
expect "one machine: the public zone's services" "cockpit dhcpv6-client http https ssh" "$(firewall-cmd --zone=public --list-services)"
expect "one machine: its ports" "9001/tcp" "$(firewall-cmd --zone=public --list-ports)"
expect "one machine: a player to 443" open "$(probe player 198.51.100.1 443)"
expect "one machine: a player to 9001" open "$(probe player 198.51.100.1 9001)"

echo "--- a service and a port in one command, as the install guide's §12 had it until 2026-10-19:"
rm -f /etc/firewalld/zones/*.xml /etc/firewalld/zones/*.xml.old
firewall-cmd -q --reload
firewall-cmd --permanent --add-service=https --add-service=http --add-port=9001/tcp 2>&1 | tail -1 | sed 's/^/      /'
firewall-cmd -q --reload
expect "applied of it: services" "cockpit dhcpv6-client ssh" "$(firewall-cmd --zone=public --list-services)"
expect "applied of it: ports" "" "$(firewall-cmd --zone=public --list-ports)"

echo "--- $FAILS failed"
[ "$FAILS" = 0 ]
