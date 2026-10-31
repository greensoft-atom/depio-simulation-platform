#!/bin/bash
# The site's front for a run by hand (D-91, docs/development/03-build-and-run.md §5.0): the release's own nginx,
# with the release's own configuration (nginx-conf/) rewritten for this machine and no root, in front of
# ./run-local.sh, for clients on other machines. The player API and the lobby over HTTPS on FRONT_PORT (8443; the
# site's 443), matches over TLS on FRONT_MATCH_PORT (9443; the site's 9001), routed by the arena's TLS name. Its
# configuration, certificate, logs and pid are in work/front.
#
#   ./run-front.sh start [address]   the address clients dial; else this machine's first (hostname -I)
#   ./run-front.sh stop              nginx stopped, and the local arena announcing 127.0.0.1 again
#   ./run-front.sh status
#
# start needs run-local.sh's platform and gateway running, and restarts its arena announcing the address,
# FRONT_MATCH_PORT and its name. Clients trust work/front/ca/ca.pem, the root of a CA of the backend's own
# (private-ca.sh), made once and kept. Give it the PLATFORM_PORT, GATEWAY_PORT and ARENA_PORT run-local.sh has.
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
F=$WORK/front
FRONT_PORT=${FRONT_PORT:-8443} FRONT_MATCH_PORT=${FRONT_MATCH_PORT:-9443}
PLATFORM_PORT=${PLATFORM_PORT:-8080} GATEWAY_PORT=${GATEWAY_PORT:-8081} ARENA_PORT=${ARENA_PORT:-9001}
R=$(ls -d "$B"/backend/target/release/backend-*/ 2> /dev/null | head -1 || true); R=${R%/}
NGINX=$R/nginx/sbin/nginx

# nginx's master, from this front's configuration: a pid left from before a reboot may be another process's.
running() {
    local pid cmd
    pid=$(cat "$F/nginx.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$F/conf/nginx.conf"* ]]
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
announced() { grep -h 'announcing as' "$WORK/run/arena.log" 2> /dev/null | tail -1 || true; }
# What the run's arena was told to announce: the environment run-local.sh gave it. Not its log, which says so half a
# second after its port answers, when run-local.sh already calls it up.
arena_env() {
    local pid
    pid=$(cat "$WORK/run/arena.pid" 2> /dev/null) && tr '\0' '\n' 2> /dev/null < "/proc/$pid/environ" || true
}
# The first arena's name in the release's stream map: the site's first arena (a1.arena.internal).
arena_name() {
    sed 's/#.*$//' "$R/nginx-conf/backend-stream.conf" | awk '/map[ \t]+\$ssl_server_name/ { m = 1; next }
        m && /}/ { exit }  m && NF >= 2 && $1 != "default" { print $1; exit }'
}

# A root made once and kept, so a client given ca.pem keeps trusting it; a certificate for the address and the
# arena's name, issued again when it does not name them, is not the root's, or ends within a day.
certificate() {
    local addr=$1 name=$2 alt
    [ -f "$F/ca/ca.pem" ] || "$B/backend/scripts/private-ca.sh" root "$F/ca" > /dev/null
    if [ -f "$F/tls/fullchain.pem" ] && openssl verify -CAfile "$F/ca/ca.pem" "$F/tls/fullchain.pem" > /dev/null 2>&1 \
        && openssl x509 -in "$F/tls/fullchain.pem" -noout -checkend 86400 > /dev/null; then
        alt=$(openssl x509 -in "$F/tls/fullchain.pem" -noout -ext subjectAltName 2> /dev/null | tail -n +2 \
            | tr ',' '\n' | sed 's/^[[:space:]]*//')
        if grep -q -x -F -e "IP Address:$addr" -e "DNS:$addr" <<< "$alt" && grep -q -x -F "DNS:$name" <<< "$alt"; then
            return
        fi
    fi
    rm -rf "$F/tls"
    "$B/backend/scripts/private-ca.sh" issue "$F/ca" "$F/tls" "$addr" "$name" > /dev/null
}

# The release's configuration for one machine and no root (D-91): its paths to work/front and the release, its
# upstreams to this run's ports (each group's first server kept, the others dropped), its stream map's first
# name to the local arena (the others dropped), its listen ports, server_name, and what needs root or IPv6.
rewrite() {
    local addr=$1 name=$2 out=$F/conf.new f user_line=
    [ "$(id -u)" = 0 ] && user_line="user nobody $(id -gn nobody);"   # a user's nginx ignores the directive
    rm -rf "$out"; mkdir -p "$out" "$F/logs" "$F/temp"
    for f in "$R"/nginx-conf/*.conf; do
        { echo "# Written by run-front.sh from the release's nginx-conf/${f##*/}, for this machine (D-91): paths,"
          echo "# addresses and ports rewritten; the rest, the comments too, is production's."
          sed -e "s|/etc/backend/nginx/|$F/conf/|g" \
              -e "s|/etc/backend/tls/fullchain\.pem|$F/tls/fullchain.pem|g" \
              -e "s|/etc/backend/credentials/privkey\.pem|$F/tls/privkey.pem|g" \
              -e "s|/var/log/backend-nginx/|$F/logs/|g" \
              -e "s|/run/backend-nginx/nginx\.pid|$F/nginx.pid|g" \
              -e "s|/opt/backend/nginx/conf/mime\.types|$R/nginx/conf/mime.types|g" \
              -e "s|^\([[:space:]]*\)listen 443 ssl;|\1listen $FRONT_PORT ssl;|" \
              -e "s|^\([[:space:]]*\)listen 9001 ssl;|\1listen $FRONT_MATCH_PORT ssl;|" \
              -e '/^[[:space:]]*listen \[::\]:/d' \
              -e "s|^\([[:space:]]*\)server_name 203\.0\.113\.10;|\1server_name $addr;|" \
              -e '/^[[:space:]]*worker_rlimit_nofile /d' \
              -e "s|^\([[:space:]]*\)worker_connections [0-9]*;|\1worker_connections 1024;|" \
              -e "s|^user nginx;|$user_line|" \
              -e "s|^http {|http {\n    client_body_temp_path $F/temp/client_body;\n    proxy_temp_path $F/temp/proxy;|" "$f" \
          | awk -v gw="$GATEWAY_PORT" -v pf="$PLATFORM_PORT" -v arena="$ARENA_PORT" -v file="${f##*/}" '
              BEGIN { port["backend_gateway"] = gw; port["backend_platform"] = pf }
              /^[ \t]*upstream[ \t]+[A-Za-z0-9_]+[ \t]*\{/ { up = $2; first = 1; print; next }
              up != "" && /^[ \t]*}/ { up = ""; print; next }
              up != "" && /^[ \t]*server[ \t]/ {
                  if (!(up in port)) { print file ": upstream " up ": not one this script knows" > "/dev/stderr"; bad = 1 }
                  else if (first) { match($0, /^[ \t]*/); print substr($0, 1, RLENGTH) "server 127.0.0.1:" port[up] ";"; first = 0 }
                  next }
              /^[ \t]*map[ \t]+\$ssl_server_name/ { inmap = 1; mfirst = 1; print; next }
              inmap && /^[ \t]*}/ { inmap = 0; print; next }
              inmap && /^[ \t]*[A-Za-z0-9.-]+[ \t]+[^;]+;/ && $1 != "default" {
                  if (mfirst) { match($0, /^[ \t]*[^ \t]+/); print substr($0, 1, RLENGTH) "  127.0.0.1:" arena ";"; mfirst = 0 }
                  next }
              { print }
              END { exit bad }'
        } > "$out/${f##*/}" || { echo "the release's nginx configuration has what this script cannot rewrite (D-91)" >&2; exit 1; }
    done
    # What a rewrite missed is refused, naming the line: a production path, address or port, or what needs root.
    local left
    left=$(cd "$out" && grep -n -H -v -E '^[[:space:]]*(#|$)' -- *.conf | awk -v F="$F" -v R="$R" -v A="$addr" \
        -v fp="$FRONT_PORT" -v mp="$FRONT_MATCH_PORT" -v where="work/front/conf.new/" '
        function strip(s, p,   i) { while (p != "" && (i = index(s, p)) > 0) s = substr(s, 1, i - 1) substr(s, i + length(p)); return s }
        { t = $0; sub(/^[^:]+:[0-9]+:/, "", t); sub(/[ \t]#.*$/, "", t); t = strip(strip(t, F), R); bad = 0
          if (t ~ /(^|[^A-Za-z0-9_.])\/(etc|var|run|opt|usr|srv|tmp)\//) bad = 1
          s = t; while (match(s, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) {
              ip = substr(s, RSTART, RLENGTH); if (ip != "127.0.0.1" && ip != A) bad = 1; s = substr(s, RSTART + RLENGTH) }
          if (t ~ /^[ \t]*listen[ \t]/) { p = ""; split(t, w, /[ \t;]+/); for (k in w) if (w[k] == "listen") { p = w[k + 1] }
              if (p != fp && p != mp) bad = 1 }
          if (t ~ /^[ \t]*(user|worker_rlimit_nofile)[ \t]/ && t !~ /^[ \t]*user nobody /) bad = 1
          if (bad) print "  " where $0 }')
    if [ -n "$left" ]; then
        echo "the release's nginx configuration has what this script cannot rewrite for this machine (D-91):" >&2
        echo "$left" >&2
        echo "nothing was started; teach run-front.sh's rewrite the new line, or run the release's units on a server" >&2
        exit 1
    fi
    rm -rf "$F/conf"; mv "$out" "$F/conf"
}

firewall_line() {
    if command -v firewall-cmd > /dev/null; then
        echo "sudo firewall-cmd --add-port=$FRONT_PORT/tcp --add-port=$FRONT_MATCH_PORT/tcp   (again with --permanent to keep it)"
    elif command -v ufw > /dev/null; then
        echo "sudo ufw allow $FRONT_PORT/tcp && sudo ufw allow $FRONT_MATCH_PORT/tcp"
    else
        echo "open $FRONT_PORT and $FRONT_MATCH_PORT to the clients, if this machine has a firewall"
    fi
}

case "${1:-}" in
start)
    addr=${2:-$(hostname -I 2> /dev/null | awk '{print $1}' || true)}
    [ -n "$addr" ] || { echo "no address: give the one clients dial, $0 start <address>" >&2; exit 2; }
    [[ $addr =~ ^[A-Za-z0-9.-]+$ ]] || { echo "$addr: an IPv4 address or a host name, the one clients dial" >&2; exit 2; }
    [ -n "$R" ] && [ -x "$NGINX" ] || { echo "no release with nginx in backend/target/release: ./build-offline.sh first" >&2; exit 1; }
    command -v openssl > /dev/null || { echo "no openssl: it makes the certificate (private-ca.sh)" >&2; exit 1; }
    if running; then
        echo "nginx: already running, pid $(cat "$F/nginx.pid"), for $(cat "$F/address" 2> /dev/null); $0 stop first to change it"
        exit 0
    fi
    st=$("$B/run-local.sh" status 2> /dev/null || true)
    grep -q '^platform: running' <<< "$st" && grep -q '^gateway: running' <<< "$st" \
        || { echo "the platform or the gateway is not running from this checkout's release: ./run-local.sh start first" >&2; exit 1; }
    "$B/backend/scripts/check-bind.sh" "$FRONT_PORT" "$FRONT_MATCH_PORT" \
        || { echo "another port for it: FRONT_PORT or FRONT_MATCH_PORT, the same for every later command" >&2; exit 1; }
    name=$(arena_name)
    [ -n "$name" ] || { echo "no arena name in the release's nginx-conf/backend-stream.conf map (D-91)" >&2; exit 1; }
    mkdir -p "$F"
    certificate "$addr" "$name"
    rewrite "$addr" "$name"
    "$NGINX" -t -q -p "$F/temp/" -c "$F/conf/nginx.conf" -e "$F/logs/error.log" \
        || { echo "nginx refused its configuration, work/front/conf: the lines above" >&2; exit 1; }
    "$NGINX" -p "$F/temp/" -c "$F/conf/nginx.conf" -e "$F/logs/error.log"
    for _ in $(seq 20); do running && taken "$FRONT_PORT" && taken "$FRONT_MATCH_PORT" && break; sleep 0.5; done
    running || { echo "nginx did not come up; its log, $F/logs/error.log, ends:" >&2; tail -5 "$F/logs/error.log" >&2; exit 1; }
    echo "$addr" > "$F/address"
    echo "nginx: up, the release's, as $(id -un), pid $(cat "$F/nginx.pid"), its configuration in $F/conf"
    # The arena tells clients to dial the front: the address, the match port and its name (BACKEND_ADVERTISE_*).
    told=$(arena_env)
    if grep -q '^arena: running' <<< "$st" && grep -q -x "ARENA_ADVERTISE_HOST=$addr" <<< "$told" \
        && grep -q -x "BACKEND_ADVERTISE_PORT=$FRONT_MATCH_PORT" <<< "$told" && grep -q -x "BACKEND_ADVERTISE_TLS_NAME=$name" <<< "$told"; then
        echo "arena: already announcing $addr:$FRONT_MATCH_PORT"
    else
        ARENA_ADVERTISE_HOST=$addr BACKEND_ADVERTISE_PORT=$FRONT_MATCH_PORT BACKEND_ADVERTISE_TLS_NAME=$name \
            "$B/run-local.sh" restart arena
    fi
    echo "  the player API   https://$addr:$FRONT_PORT/v1/"
    echo "  the lobby        wss://$addr:$FRONT_PORT/lobby"
    echo "  matches          $addr:$FRONT_MATCH_PORT, TLS as $name: the address the arena announces"
    echo "  clients trust    $F/ca/ca.pem, and nothing else: copy it to them"
    echo "  the firewall     $(firewall_line)" ;;
stop)
    if running; then
        pid=$(cat "$F/nginx.pid"); kill "$pid"
        for _ in $(seq 20); do running || break; sleep 0.5; done
        if running; then echo "nginx: still running after 10 s, pid $pid" >&2; exit 1; fi
        echo "nginx: stopped"
    else
        echo "nginx: not running"
    fi
    # The arena back to announcing 127.0.0.1, if it announces a front.
    if "$B/run-local.sh" status 2> /dev/null | grep -q '^arena: running' && grep -q '^BACKEND_ADVERTISE_TLS_NAME=.' <<< "$(arena_env)"; then
        env -u ARENA_ADVERTISE_HOST -u BACKEND_ADVERTISE_PORT -u BACKEND_ADVERTISE_TLS_NAME "$B/run-local.sh" restart arena
    fi ;;
status)
    if running; then
        addr=$(cat "$F/address" 2> /dev/null)
        echo "nginx: running, pid $(cat "$F/nginx.pid"), for $addr: https://$addr:$FRONT_PORT/v1/, wss://$addr:$FRONT_PORT/lobby, matches $addr:$FRONT_MATCH_PORT"
        echo "  certificate: $(openssl x509 -in "$F/tls/fullchain.pem" -noout -ext subjectAltName 2> /dev/null | tail -n +2 | sed 's/^[[:space:]]*//'), until $(openssl x509 -in "$F/tls/fullchain.pem" -noout -enddate | cut -d= -f2)"
        echo "  clients trust $F/ca/ca.pem"
    else
        echo "nginx: not running"
    fi
    a=$(announced)
    if [ -n "$a" ]; then echo "arena: ${a#*ArenaAnnouncer - }"; else echo "arena: no announcement in work/run/arena.log"; fi ;;
*)
    echo "usage: $0 start [address] | stop | status" >&2
    exit 2 ;;
esac
