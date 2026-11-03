#!/bin/bash
# nginx in front of a run by hand (decision D-92, docs/development/03-build-and-run.md §5): the release's own nginx
# with the release's own configuration (nginx-conf/) rewritten into work/nginx for this machine, on the site's ports
# and every address. The player API and the lobby over HTTPS on NGINX_HTTPS_PORT (443), NGINX_HTTP_PORT (80) a
# redirect to it, and matches over TLS on NGINX_MATCH_PORT (9001), routed by ARENA_TLS_NAME to the arena on
# 127.0.0.1:ARENA_PORT (dev-backend.sh). The certificate names PUBLIC_ADDRESS, 127.0.0.1 and the arena's name, from a
# root of the backend's own CA (private-ca.sh) made once in work/nginx/ca and kept: clients trust
# work/nginx/ca/ca.pem, and nothing else. 80 and 443 need root, or a lowered threshold; the workers run as nobody.
# PLAIN=1 (D-101), for development alone: the same without TLS, the API and the lobby on NGINX_HTTP_PORT, matches on
# NGINX_MATCH_PORT passed to the arena; no 443, no certificate.
#
#   ./dev-nginx.sh start | stop | restart | status
#   ./dev-nginx.sh test      its configuration, 80's redirect, 443's and 9001's certificate, the platform behind it;
#                            plain: its configuration and the platform behind 80
set -euo pipefail
. "$(dirname "$0")/dev-env.sh"
F=$WORK/nginx
R=$(ls -d "$B"/backend/target/release/backend-*/ 2> /dev/null | head -1 || true); R=${R%/}
NGINX=$R/nginx/sbin/nginx
CA=$F/ca/ca.pem
# The URLs, with a port only when it is not the scheme's own.
https_url=https://$PUBLIC_ADDRESS$([ "$NGINX_HTTPS_PORT" = 443 ] || echo ":$NGINX_HTTPS_PORT")
http_url=http://$PUBLIC_ADDRESS$([ "$NGINX_HTTP_PORT" = 80 ] || echo ":$NGINX_HTTP_PORT")
open=("$NGINX_HTTP_PORT" "$NGINX_HTTPS_PORT" "$NGINX_MATCH_PORT") mode=TLS
[ "$PLAIN" != 1 ] || { open=("$NGINX_HTTP_PORT" "$NGINX_MATCH_PORT") mode=plain; }
# The ports a way listens on, in words: this command's, or the running nginx's (way, below), which may differ.
ports_of() { if [ "$1" = plain ]; then echo "$NGINX_HTTP_PORT and $NGINX_MATCH_PORT"; else echo "$NGINX_HTTP_PORT, $NGINX_HTTPS_PORT and $NGINX_MATCH_PORT"; fi; }
ports=$(ports_of "$mode")

# nginx's master, from this configuration: a pid left from before a reboot may be another process's.
running() {
    local pid cmd
    pid=$(cat "$F/nginx.pid" 2> /dev/null) && cmd=$(tr '\0' ' ' 2> /dev/null < "/proc/$pid/cmdline") \
        && [[ $cmd == *"$F/conf/nginx.conf"* ]]
}
taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
all_taken() { local p; for p in "${open[@]}"; do taken "$p" || return 1; done; }
# The way the running nginx was started, plain or TLS (D-101): sudo drops PLAIN from the shell (D-98).
way() { cat "$F/mode" 2> /dev/null || echo TLS; }

# A root made once and kept, so a client given ca.pem keeps trusting it; a certificate for the address, 127.0.0.1
# and the arena's name, issued again when it does not name them, is not the root's, or ends within a day.
certificate() {
    local alt n names=("$PUBLIC_ADDRESS")
    [ "$PUBLIC_ADDRESS" = 127.0.0.1 ] || names+=(127.0.0.1)
    names+=("$ARENA_TLS_NAME")
    [ -f "$CA" ] || "$B/backend/scripts/private-ca.sh" root "$F/ca" > /dev/null
    # Its directories open to every user, the keys kept 0600: a user's run beside root's nginx reads the root that
    # clients trust, and the certificate (D-98, O-91). Only by their owner: another's are left as they are.
    [ ! -O "$F/ca" ] || chmod 755 "$F/ca"
    if [ -f "$F/tls/fullchain.pem" ] && openssl verify -CAfile "$CA" "$F/tls/fullchain.pem" > /dev/null 2>&1 \
        && openssl x509 -in "$F/tls/fullchain.pem" -noout -checkend 86400 > /dev/null; then
        alt=$(openssl x509 -in "$F/tls/fullchain.pem" -noout -ext subjectAltName 2> /dev/null | tail -n +2 \
            | tr ',' '\n' | sed 's/^[[:space:]]*//')
        for n in "${names[@]}"; do grep -q -x -F -e "IP Address:$n" -e "DNS:$n" <<< "$alt" || break; done
        grep -q -x -F -e "IP Address:$n" -e "DNS:$n" <<< "$alt" && { [ ! -O "$F/tls" ] || chmod 755 "$F/tls"; return; }
    fi
    rm -rf "$F/tls"
    "$B/backend/scripts/private-ca.sh" issue "$F/ca" "$F/tls" "${names[@]}" > /dev/null
    chmod 755 "$F/tls"
}

# The release's configuration for this machine (D-92): its paths to work/nginx and the release, its upstreams to
# this run's ports (each group's first server kept, the others dropped), its stream map to the arena's name alone,
# to the local arena, its listen ports, server_name, what needs root or IPv6; and the server on 80, a redirect.
# Plain (D-101): 443's server on 80 and 9001 without TLS, passed to the one arena (no name to route by) without the
# PROXY line (D-105), no redirect.
rewrite() {
    local out=$F/conf.new f user_line= to=https://\$host redirect what plain=()
    local https_listen="$NGINX_HTTPS_PORT ssl" match_listen="$NGINX_MATCH_PORT ssl"
    [ "$(id -u)" = 0 ] && user_line="user nobody $(id -gn nobody);"   # a user's nginx ignores the directive
    [ "$NGINX_HTTPS_PORT" = 443 ] || to=$to:$NGINX_HTTPS_PORT
    redirect="\n    # Plain HTTP only sends a client to HTTPS, the method and the body kept (D-92).\n    server {\n        listen $NGINX_HTTP_PORT;\n        return 308 $to\$request_uri;\n    }\n"
    what="the server on $NGINX_HTTP_PORT added"
    if [ "$PLAIN" = 1 ]; then
        https_listen=$NGINX_HTTP_PORT match_listen=$NGINX_MATCH_PORT redirect= what="TLS taken out (D-101)"
        # No PROXY line either: the plain way's arena has no TLS name, and reads none (D-105).
        plain=(-e '/^[[:space:]]*ssl_/d' -e '/^[[:space:]]*proxy_protocol on;/d' \
            -e "s|^\([[:space:]]*\)proxy_pass \$backend_arena;|\1proxy_pass 127.0.0.1:$ARENA_PORT;|")
    fi
    rm -rf "$out"; mkdir -p "$out" "$F/logs" "$F/temp"
    for f in "$R"/nginx-conf/*.conf; do
        { echo "# Written by dev-nginx.sh from the release's nginx-conf/${f##*/}, for this machine (D-92): paths,"
          echo "# addresses and ports rewritten, and $what; the rest, the comments too, is production's."
          sed -e "s|/etc/backend/nginx/|$F/conf/|g" \
              -e "s|/etc/backend/tls/fullchain\.pem|$F/tls/fullchain.pem|g" \
              -e "s|/etc/backend/credentials/privkey\.pem|$F/tls/privkey.pem|g" \
              -e "s|/var/log/backend-nginx/|$F/logs/|g" \
              -e "s|/run/backend-nginx/nginx\.pid|$F/nginx.pid|g" \
              -e "s|/opt/backend/nginx/conf/mime\.types|$R/nginx/conf/mime.types|g" \
              -e "s|^\([[:space:]]*\)listen 443 ssl;|\1listen $https_listen;|" \
              -e "s|^\([[:space:]]*\)listen 9001 ssl;|\1listen $match_listen;|" "${plain[@]}" \
              -e '/^[[:space:]]*listen \[::\]:/d' \
              -e "s|^\([[:space:]]*\)server_name 203\.0\.113\.10;|\1server_name $PUBLIC_ADDRESS;|" \
              -e '/^[[:space:]]*worker_rlimit_nofile /d' \
              -e "s|^\([[:space:]]*\)worker_connections [0-9]*;|\1worker_connections 1024;|" \
              -e "s|^user nginx;|$user_line|" \
              -e "s|^http {|http {\n    client_body_temp_path $F/temp/client_body;\n    proxy_temp_path $F/temp/proxy;\n    # The workers, nobody, may not reach a checkout under /root: no response is buffered to a file.\n    proxy_max_temp_file_size 0;\n$redirect|" "$f" \
          | awk -v gw="$GATEWAY_PORT" -v pf="$PLATFORM_PORT" -v arena="$ARENA_PORT" -v tls="$ARENA_TLS_NAME" -v file="${f##*/}" '
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
                  if (mfirst) { match($0, /^[ \t]*/); print substr($0, 1, RLENGTH) tls "  127.0.0.1:" arena ";"; mfirst = 0 }
                  next }
              { print }
              END { exit bad }'
        } > "$out/${f##*/}" || { echo "the release's nginx configuration has what this script cannot rewrite (D-92)" >&2; exit 1; }
    done
    # What a rewrite missed is refused, naming the line: a production path, address or port, or what needs root.
    local left
    left=$(cd "$out" && grep -n -H -v -E '^[[:space:]]*(#|$)' -- *.conf | awk -v F="$F" -v R="$R" -v A="$PUBLIC_ADDRESS" \
        -v hp="$NGINX_HTTP_PORT" -v sp="$NGINX_HTTPS_PORT" -v mp="$NGINX_MATCH_PORT" -v where="work/nginx/conf.new/" '
        function strip(s, p,   i) { while (p != "" && (i = index(s, p)) > 0) s = substr(s, 1, i - 1) substr(s, i + length(p)); return s }
        { t = $0; sub(/^[^:]+:[0-9]+:/, "", t); sub(/[ \t]#.*$/, "", t); t = strip(strip(t, F), R); bad = 0
          if (t ~ /(^|[^A-Za-z0-9_.])\/(etc|var|run|opt|usr|srv|tmp)\//) bad = 1
          s = t; while (match(s, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) {
              ip = substr(s, RSTART, RLENGTH); if (ip != "127.0.0.1" && ip != A) bad = 1; s = substr(s, RSTART + RLENGTH) }
          if (t ~ /^[ \t]*listen[ \t]/) { p = ""; split(t, w, /[ \t;]+/); for (k in w) if (w[k] == "listen") { p = w[k + 1] }
              if (p != hp && p != sp && p != mp) bad = 1 }
          if (t ~ /^[ \t]*(user|worker_rlimit_nofile)[ \t]/ && t !~ /^[ \t]*user nobody /) bad = 1
          if (bad) print "  " where $0 }')
    if [ -n "$left" ]; then
        echo "the release's nginx configuration has what this script cannot rewrite for this machine (D-92):" >&2
        echo "$left" >&2
        echo "nothing was started; teach dev-nginx.sh's rewrite the new line, or run the release's units on a server" >&2
        exit 1
    fi
    rm -rf "$F/conf"; mv "$out" "$F/conf"
}

firewall_line() {
    local p fw= uf=
    for p in "${open[@]}"; do fw+=" --add-port=$p/tcp"; uf+="${uf:+ && }sudo ufw allow $p/tcp"; done
    if command -v firewall-cmd > /dev/null; then
        echo "sudo firewall-cmd$fw   (again with --permanent to keep it)"
    elif command -v ufw > /dev/null; then
        echo "$uf"
    else
        echo "open $ports to the clients, if this machine has a firewall"
    fi
}

case "${1:-}" in
start)
    [[ $PUBLIC_ADDRESS =~ ^[A-Za-z0-9.-]+$ ]] \
        || { echo "PUBLIC_ADDRESS=$PUBLIC_ADDRESS: an IPv4 address or a host name, the one clients dial" >&2; exit 2; }
    [ -n "$R" ] && [ -x "$NGINX" ] || { echo "no release with nginx in backend/target/release: ./dev-backend.sh build first" >&2; exit 1; }
    # A machine whose C library is older than nginx's is told so, not that nginx refused its configuration (O-90).
    out=$("$NGINX" -v 2>&1) || { echo "$out" >&2; echo "the release's nginx does not run on this machine: it needs glibc 2.28" \
        "or later (docs/development/03 §1)" >&2; exit 1; }
    [ "$PLAIN" = 1 ] || command -v openssl > /dev/null || { echo "no openssl: it makes the certificate (private-ca.sh)" >&2; exit 1; }
    if running; then
        [ "$(way)" = "$mode" ] || { echo "nginx: already running $(way), pid $(cat "$F/nginx.pid"), and this command $mode: give both" \
            "the same PLAIN, or restart it this way (sudo drops PLAIN: sudo PLAIN=$PLAIN $0 restart)" >&2; exit 1; }
        echo "nginx: already running, pid $(cat "$F/nginx.pid"), for $(cat "$F/address" 2> /dev/null), $(way); $0 restart to change it"
        exit 0
    fi
    # In use, or refused by a policy of the machine: named before anything starts (D-88)
    BIND=0.0.0.0 "$B/backend/scripts/check-bind.sh" "${open[@]}" || {
        echo "another port for it: NGINX_HTTP_PORT, NGINX_HTTPS_PORT or NGINX_MATCH_PORT, the same for every later command" >&2
        exit 1; }
    mkdir -p "$F"
    [ "$PLAIN" = 1 ] || certificate
    rewrite
    "$NGINX" -t -q -p "$F/temp/" -c "$F/conf/nginx.conf" -e "$F/logs/error.log" \
        || { echo "nginx refused its configuration, work/nginx/conf: the lines above" >&2; exit 1; }
    "$NGINX" -p "$F/temp/" -c "$F/conf/nginx.conf" -e "$F/logs/error.log"
    for _ in $(seq 20); do
        running && all_taken && break; sleep 0.5
    done
    running || { echo "nginx did not come up; its log, $F/logs/error.log, ends:" >&2; tail -5 "$F/logs/error.log" >&2; exit 1; }
    echo "$PUBLIC_ADDRESS" > "$F/address"; echo "$mode" > "$F/mode"
    echo "nginx: up on $ports, as $(id -un), pid $(cat "$F/nginx.pid"), for $PUBLIC_ADDRESS, $mode; its configuration in $F/conf"
    if [ "$PLAIN" = 1 ]; then
        echo "  the player API   $http_url/v1/"
        echo "  the lobby        ws://${http_url#http://}/lobby"
        echo "  matches          $PUBLIC_ADDRESS:$NGINX_MATCH_PORT, plain, to the arena on 127.0.0.1:$ARENA_PORT"
        echo "  in clear         for a network you trust alone: passwords, tokens and tickets cross it readable (D-101)"
    else
        echo "  the player API   $https_url/v1/   ($http_url/ redirects there)"
        echo "  the lobby        wss://${https_url#https://}/lobby"
        echo "  matches          $PUBLIC_ADDRESS:$NGINX_MATCH_PORT, TLS as $ARENA_TLS_NAME, to the arena on 127.0.0.1:$ARENA_PORT"
        echo "  clients trust    $CA, and nothing else: copy it to them"
    fi
    echo "  the firewall     $(firewall_line)" ;;
stop)
    if running; then
        pid=$(cat "$F/nginx.pid")
        kill "$pid" 2> /dev/null \
            || { echo "nginx: runs as $(stat -c %U "/proc/$pid"), pid $pid, which $(id -un) may not stop: sudo $0 stop" >&2; exit 1; }
        for _ in $(seq 20); do running || break; sleep 0.5; done
        if running; then echo "nginx: still running after 10 s, pid $pid" >&2; exit 1; fi
        echo "nginx: stopped"
    else
        echo "nginx: not running"
    fi ;;
restart)
    "$B/dev-nginx.sh" stop && exec "$B/dev-nginx.sh" start ;;       # by path: bash dev-nginx.sh gave a name alone (O-86)
status)
    running || { echo "nginx: not running"; exit 1; }
    echo "nginx: running, pid $(cat "$F/nginx.pid"), on $(ports_of "$(way)"), for $(cat "$F/address" 2> /dev/null), $(way)"
    if [ "$(way)" = TLS ]; then
        echo "  certificate: $(openssl x509 -in "$F/tls/fullchain.pem" -noout -ext subjectAltName 2> /dev/null | tail -n +2 \
            | sed 's/^[[:space:]]*//'), until $(openssl x509 -in "$F/tls/fullchain.pem" -noout -enddate | cut -d= -f2)"
        echo "  clients trust $CA"
    fi ;;
test)
    running || { echo "FAILED  nginx is not running: $0 start"; exit 1; }
    [ "$(way)" = "$mode" ] || { echo "FAILED  nginx runs $(way), and this command $mode: give both the same PLAIN (sudo drops it: sudo PLAIN=$PLAIN $0 restart)"; exit 1; }
    # nginx -t reads what nginx's own user may (its key, its pid file): as that user alone (D-98, O-91).
    owner=$(stat -c %U "/proc/$(cat "$F/nginx.pid")")
    if [ "$owner" = "$(id -un)" ]; then
        "$NGINX" -t -q -p "$F/temp/" -c "$F/conf/nginx.conf" -e "$F/logs/error.log" \
            || { echo "FAILED  the configuration: nginx -t, the lines above"; exit 1; }
        echo "ok      the configuration: nginx -t"
    else
        echo "--      the configuration: nginx runs as $owner, whose nginx -t it is: sudo $0 test"
    fi
    # Each as a client dialling the address would see it, the connection made to 127.0.0.1.
    to127() { echo "--connect-to $PUBLIC_ADDRESS:$1:127.0.0.1:$1"; }
    if [ "$PLAIN" = 1 ]; then
        got=$(curl -sS --max-time 10 $(to127 "$NGINX_HTTP_PORT") -o /dev/null -w '%{http_code}' "$http_url/v1/content/classes" 2>&1 || true)
        case $got in
            200) echo "ok      $NGINX_HTTP_PORT: plain, and the platform answers through it (200)" ;;
            502|504) echo "FAILED  $NGINX_HTTP_PORT: plain, but the platform behind it does not answer ($got): ./dev-backend.sh start"; exit 1 ;;
            *) echo "FAILED  $NGINX_HTTP_PORT: $got"; exit 1 ;;
        esac
        echo "--      $NGINX_MATCH_PORT: plain, nothing to check without a match: ./dev-backend.sh test plays one through it"
        exit 0
    fi
    got=$(curl -sS --max-time 10 $(to127 "$NGINX_HTTP_PORT") -o /dev/null -w '%{http_code} %{redirect_url}' \
        "$http_url/v1/content/classes" 2>&1 || true)
    [ "$got" = "308 $https_url/v1/content/classes" ] || { echo "FAILED  $NGINX_HTTP_PORT: $got, not a 308 to $https_url/v1/content/classes"; exit 1; }
    echo "ok      $NGINX_HTTP_PORT: 308 to $https_url/v1/content/classes"
    got=$(curl -sS --max-time 10 --cacert "$CA" $(to127 "$NGINX_HTTPS_PORT") -o /dev/null -w '%{http_code}' \
        "$https_url/v1/content/classes" 2>&1 || true)
    case $got in
        200) echo "ok      $NGINX_HTTPS_PORT: the certificate verifies for $PUBLIC_ADDRESS, and the platform answers through it (200)" ;;
        502|504) echo "FAILED  $NGINX_HTTPS_PORT: the certificate verifies, but the platform behind it does not answer ($got): ./dev-backend.sh start"; exit 1 ;;
        *) echo "FAILED  $NGINX_HTTPS_PORT: $got"; exit 1 ;;
    esac
    got=$(openssl s_client -connect "127.0.0.1:$NGINX_MATCH_PORT" -servername "$ARENA_TLS_NAME" -verify_hostname "$ARENA_TLS_NAME" \
        -CAfile "$CA" -verify_return_error < /dev/null 2>&1) \
        || { echo "FAILED  $NGINX_MATCH_PORT: the handshake for $ARENA_TLS_NAME:"; tail -3 <<< "$got" | sed 's/^/        /'; exit 1; }
    echo "ok      $NGINX_MATCH_PORT: the certificate verifies for $ARENA_TLS_NAME" ;;
*)
    echo "usage: $0 start | stop | restart | status | test" >&2; exit 2 ;;
esac
