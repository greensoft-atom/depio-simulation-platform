#!/bin/bash
# Installs a certificate for everything this machine serves to players, after checking it the
# way a device will (docs/operations/01-deploy.md §11 and §8):
#
#   install-certificate.sh <fullchain.pem> <privkey.pem>   by hand, from any CA
#   install-certificate.sh                                 as certbot's --deploy-hook, which
#                                                          sets RENEWED_LINEAGE
#   install-certificate.sh --check                         what each port serves right now
#   install-certificate.sh --placeholder                   a self-signed stand-in, so nginx can
#                                                          start and answer the CA's first
#                                                          challenge; devices refuse it
#
# nginx (login, purchases, the lobby) gets the PEM files and is reloaded, which drops nothing.
# The arenas get a PKCS#12 keystore, read at start: each serves the new certificate from its
# next restart, and this says which still serve the old one.
#
# Nothing is installed unless all of this holds, since every failure below is otherwise found
# by players, as a connection their device refuses:
#   - the key belongs to the certificate
#   - the chain reaches a trusted root through the intermediates in the file alone: devices
#     do not fetch a missing intermediate, so a file holding only the leaf fails on phones
#     while it works in a desktop browser
#   - valid now; a warning when fewer than WARN_DAYS remain
#   - it names every host this machine serves: nginx's server_name and each arena's
#     ADVERTISE_HOST, by subject alternative name, which is all a device checks
# If nginx then refuses the files, the previous ones are put back.
#
# Settings, for a machine laid out differently or a drill: TLS_DIR, CREDENTIALS_DIR,
# CONFIG_DIR (the arena-*.env files), NGINX_CONF, NGINX_TEST, NGINX_RELOAD, EDGE_PORT,
# CA_FILE (trust only this root instead of the system's), WARN_DAYS.
set -euo pipefail
umask 077

TLS_DIR=${TLS_DIR:-/etc/backend/tls}
CREDENTIALS_DIR=${CREDENTIALS_DIR:-/etc/backend/credentials}
CONFIG_DIR=${CONFIG_DIR:-/etc/backend}
NGINX_CONF=${NGINX_CONF:-/etc/backend/nginx/backend.conf}
# nginx -t writes its pid file and makes its temporary directories, in the directories its unit
# makes (RuntimeDirectory, CacheDirectory): on a machine where the unit has not run, they are made here.
nginx_test() {
    install -d -m 0755 /run/backend-nginx /var/cache/backend-nginx
    /opt/backend/nginx/sbin/nginx -t -q
}
NGINX_TEST=${NGINX_TEST:-nginx_test}
NGINX_RELOAD=${NGINX_RELOAD:-systemctl reload backend-nginx}
EDGE_PORT=${EDGE_PORT:-443}
CA_FILE=${CA_FILE:-}
WARN_DAYS=${WARN_DAYS:-14}

refuse() { echo "refusing: $*" >&2; exit 1; }
warn() { echo "WARNING: $*" >&2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# What a device trusts: the system's roots, or CA_FILE's alone.
TRUST=()
[ -n "$CA_FILE" ] && TRUST=(-CAfile "$CA_FILE" -no-CApath -no-CAstore)

# ---- what this machine serves -------------------------------------------------------------

edge_names() {
    [ -f "$NGINX_CONF" ] || return 0
    sed -n 's/^[[:space:]]*server_name[[:space:]]\+\([^;]*\);.*/\1/p' "$NGINX_CONF" \
        | tr ' ' '\n' | grep -v -e '^$' -e '^_$' -e '^~' | sort -u
}

setting() {   # setting <file> <key>: the last KEY=value, quotes removed
    sed -n "s/^$2=//p" "$1" | tail -1 | tr -d "\"'"
}

arena_files() {
    compgen -G "$CONFIG_DIR/arena-*.env" || true
}

# served <host> <port> <name>: the certificate a device dialling <name> gets, as PEM, or nothing
served() {
    timeout 10 openssl s_client -connect "$1:$2" -servername "$3" </dev/null 2>/dev/null \
        | openssl x509 2>/dev/null || true
}

fingerprint() { openssl x509 -noout -fingerprint -sha256 | cut -d= -f2; }

# split <pem-file> <dir>: one file per certificate, 1.pem first
split_pem() {
    mkdir -p "$2"
    awk -v dir="$2" '/-----BEGIN CERTIFICATE-----/ { n++ } n { print > (dir "/" n ".pem") }' "$1"
}

# earliest <pem-file>: when the first of its certificates expires, in Unix seconds. A device
# refuses the chain when any link has expired, the intermediate as much as the leaf.
earliest() {
    local dir; dir=$(mktemp -d -p "$WORK")
    split_pem "$1" "$dir"
    for c in "$dir"/*.pem; do
        date -d "$(openssl x509 -in "$c" -noout -enddate | cut -d= -f2)" +%s
    done | sort -n | head -1
}

# ---- checks -------------------------------------------------------------------------------

# covers <leaf.pem> <host>
covers() {
    local how=-checkhost
    [[ $2 =~ ^[0-9.]+$ || $2 == *:* ]] && how=-checkip
    openssl x509 -in "$1" -noout "$how" "$2" | grep -q ' does match '
}

check_certificate() {   # check_certificate <fullchain> <key>
    local chain=$1 key=$2
    [ -r "$chain" ] || refuse "cannot read $chain"
    [ -r "$key" ] || refuse "cannot read $key"
    # Split the file: the leaf, then whatever intermediates came with it.
    split_pem "$chain" "$WORK/given"
    [ -f "$WORK/given/1.pem" ] || refuse "$chain holds no certificate"
    local count; count=$(compgen -G "$WORK/given/*.pem" | wc -l)
    cp "$WORK/given/1.pem" "$WORK/leaf.pem"
    : > "$WORK/intermediates.pem"
    for ((i = 2; i <= count; i++)); do cat "$WORK/given/$i.pem" >> "$WORK/intermediates.pem"; done

    openssl pkey -in "$key" -passin pass: -noout 2>/dev/null \
        || refuse "$key is not a private key, or is encrypted"
    [ "$(openssl pkey -in "$key" -passin pass: -pubout)" = "$(openssl x509 -in "$WORK/leaf.pem" -noout -pubkey)" ] \
        || refuse "$key is not the key of the certificate in $chain"

    local untrusted=()
    [ "$count" -gt 1 ] && untrusted=(-untrusted "$WORK/intermediates.pem")
    local verdict
    if ! verdict=$(openssl verify "${TRUST[@]}" -purpose sslserver "${untrusted[@]}" "$WORK/leaf.pem" 2>&1); then
        local hint=""
        [ "$count" -eq 1 ] && hint=" (the file holds the certificate alone: give the full chain, fullchain.pem)"
        refuse "$chain does not verify: $(echo "$verdict" | grep -m1 -i error)$hint"
    fi

    openssl x509 -in "$WORK/leaf.pem" -noout -ext subjectAltName 2>/dev/null | grep -q . \
        || refuse "$chain names no host (no subject alternative name), and devices check nothing else"
    local missing=()
    for name in "${NAMES[@]}"; do
        covers "$WORK/leaf.pem" "$name" || missing+=("$name")
    done
    [ ${#missing[@]} -eq 0 ] \
        || refuse "$chain does not name ${missing[*]}; it names $(openssl x509 -in "$WORK/leaf.pem" -noout -ext subjectAltName | tail -n +2 | tr -d ' ')"

    local end; end=$(earliest "$chain")
    if [ $((end - $(date +%s))) -lt $((WARN_DAYS * 86400)) ]; then
        warn "$chain expires $(date -u -d "@$end" +%FT%TZ), within $WARN_DAYS days"
    fi
}

# ---- installing ---------------------------------------------------------------------------

# put <source> <destination> <mode>: replaced whole, never half-written. The temporary file is
# made beside the destination so that it carries that directory's SELinux label; one made in
# /tmp and moved would keep a label nginx may not read.
put() {
    local tmp; tmp=$(mktemp "$(dirname "$2")/.$(basename "$2").XXXXXX")
    cat "$1" > "$tmp"
    chmod "$3" "$tmp"
    mv -f "$tmp" "$2"
    command -v restorecon >/dev/null && restorecon "$2" || true
}

keep_previous() { [ -f "$1" ] && cp -p "$1" "$1.previous" || true; }
restore_previous() { [ -f "$1.previous" ] && mv -f "$1.previous" "$1" || true; }

install_edge() {
    local fullchain=$TLS_DIR/fullchain.pem privkey=$CREDENTIALS_DIR/privkey.pem
    keep_previous "$fullchain"; keep_previous "$privkey"
    put "$1" "$fullchain" 0644
    put "$2" "$privkey" 0600
    local out
    if ! out=$($NGINX_TEST 2>&1); then
        restore_previous "$fullchain"; restore_previous "$privkey"
        refuse "nginx does not accept it, the previous files are back: $out"
    fi
    $NGINX_RELOAD || refuse "nginx did not reload. The files are installed: a running nginx still serves the previous certificate, a stopped one will serve these when started"
    local want; want=$(fingerprint < "$WORK/leaf.pem")
    for name in "${EDGE_NAMES[@]}"; do
        local got="" tries=0
        while [ "$got" != "$want" ] && [ $((tries++)) -lt 20 ]; do
            got=$(served 127.0.0.1 "$EDGE_PORT" "$name" | fingerprint 2>/dev/null || true)
            [ "$got" = "$want" ] || sleep 0.5
        done
        [ "$got" = "$want" ] || refuse "nginx reloaded, but $name on port $EDGE_PORT does not serve the new certificate"
        echo "nginx: $name serves the new certificate"
    done
}

install_arenas() {
    local keystore=$CREDENTIALS_DIR/arena-tls.p12 password=$CREDENTIALS_DIR/arena-tls-password
    if [ ! -s "$password" ]; then
        openssl rand -base64 32 > "$WORK/password"
        put "$WORK/password" "$password" 0600
        echo "arenas: a keystore password was made, $password"
    fi
    openssl pkcs12 -export -in "$1" -inkey "$2" -name arena -passout "file:$password" -out "$WORK/arena.p12"
    openssl pkcs12 -in "$WORK/arena.p12" -passin "file:$password" -noout \
        || refuse "the arena keystore just made does not open with its password"
    keep_previous "$keystore"
    put "$WORK/arena.p12" "$keystore" 0600
    echo "arenas: $keystore replaced"
    local want; want=$(fingerprint < "$WORK/leaf.pem")
    for f in $(arena_files); do
        local arena host port bind got
        arena=$(basename "$f" .env); arena=${arena#arena-}
        host=$(setting "$f" ADVERTISE_HOST); port=$(setting "$f" ARENA_PORT); bind=$(setting "$f" ARENA_BIND)
        [[ -z $bind || $bind == 0.0.0.0 || $bind == :: ]] && bind=127.0.0.1
        got=$(served "$bind" "$port" "$host" | fingerprint 2>/dev/null || true)
        if [ "$got" = "$want" ]; then
            echo "arena $arena: serves the new certificate"
        elif [ -n "$got" ]; then
            echo "arena $arena: serves the previous certificate until its next restart (runbook §6)"
        else
            echo "arena $arena: serves no TLS on $bind:$port (stopped, or not configured for it)"
        fi
    done
}

# ---- checking what is served --------------------------------------------------------------

check_served() {   # check_served <label> <host> <port> <name>
    local out verify end days
    out=$(timeout 10 openssl s_client -connect "$2:$3" -servername "$4" -verify_hostname "$4" \
        -showcerts "${TRUST[@]}" </dev/null 2>&1 || true)
    awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' <<< "$out" > "$WORK/served.pem"
    if [ ! -s "$WORK/served.pem" ]; then
        echo "$1: no TLS on $2:$3"; return 1
    fi
    verify=$(grep -m1 'Verify return code' <<< "$out" || echo 'no verdict')
    end=$(earliest "$WORK/served.pem")
    days=$(( (end - $(date +%s)) / 86400 ))
    printf '%s: %s, expires %s (%d days), %s\n' "$1" "$4" "$(date -u -d "@$end" +%FT%TZ)" "$days" "${verify#*: }"
    [[ $verify == *'(ok)'* ]] && [ "$days" -ge "$WARN_DAYS" ]
}

# ---- main ---------------------------------------------------------------------------------

mapfile -t EDGE_NAMES < <(edge_names)
ARENA_NAMES=()
for f in $(arena_files); do ARENA_NAMES+=("$(setting "$f" ADVERTISE_HOST)"); done
mapfile -t NAMES < <(printf '%s\n' "${EDGE_NAMES[@]}" "${ARENA_NAMES[@]}" | grep -v '^$' | sort -u)
[ ${#NAMES[@]} -gt 0 ] || refuse "this machine serves no name: no server_name in $NGINX_CONF and no ADVERTISE_HOST in $CONFIG_DIR/arena-*.env"

if [ "${1:-}" = "--check" ]; then
    failed=0
    for name in "${EDGE_NAMES[@]}"; do
        check_served nginx 127.0.0.1 "$EDGE_PORT" "$name" || failed=1
    done
    for f in $(arena_files); do
        arena=$(basename "$f" .env); bind=$(setting "$f" ARENA_BIND)
        [[ -z $bind || $bind == 0.0.0.0 || $bind == :: ]] && bind=127.0.0.1
        check_served "arena ${arena#arena-}" "$bind" "$(setting "$f" ARENA_PORT)" "$(setting "$f" ADVERTISE_HOST)" || failed=1
    done
    exit $failed
fi

# nginx will not start while the files its TLS server names are missing, and the CA's first
# HTTP-01 challenge needs nginx answering on port 80: a stand-in breaks the circle. Never over
# a real certificate.
if [ "${1:-}" = "--placeholder" ]; then
    [ ! -e "$TLS_DIR/fullchain.pem" ] || refuse "$TLS_DIR/fullchain.pem exists; a placeholder only goes where there is none"
    [ ${#EDGE_NAMES[@]} -gt 0 ] || refuse "no server_name in $NGINX_CONF"
    san=$(printf 'DNS:%s,' "${EDGE_NAMES[@]}")
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 7 \
        -subj "/CN=placeholder" -addext "subjectAltName=${san%,}" \
        -keyout "$WORK/key.pem" -out "$WORK/cert.pem" 2>/dev/null
    put "$WORK/cert.pem" "$TLS_DIR/fullchain.pem" 0644
    put "$WORK/key.pem" "$CREDENTIALS_DIR/privkey.pem" 0600
    $NGINX_TEST
    echo "placeholder installed for ${EDGE_NAMES[*]}: nginx can start; devices will refuse it until the real one is installed"
    exit 0
fi

if [ $# -eq 2 ]; then
    FULLCHAIN=$1; PRIVKEY=$2
elif [ $# -eq 0 ] && [ -n "${RENEWED_LINEAGE:-}" ]; then
    FULLCHAIN=$RENEWED_LINEAGE/fullchain.pem; PRIVKEY=$RENEWED_LINEAGE/privkey.pem
else
    echo "usage: $0 <fullchain.pem> <privkey.pem> | --check | --placeholder   (or as certbot's --deploy-hook)" >&2
    exit 2
fi

# One at a time: a renewal and a hand install at once would mix their files.
exec 9> "$CREDENTIALS_DIR/.install-certificate.lock"
flock -n 9 || refuse "another install is running"

check_certificate "$FULLCHAIN" "$PRIVKEY"
echo "checked: $(openssl x509 -in "$WORK/leaf.pem" -noout -subject -enddate | tr '\n' ' ')for ${NAMES[*]}"
[ ${#EDGE_NAMES[@]} -gt 0 ] && install_edge "$FULLCHAIN" "$PRIVKEY"
[ ${#ARENA_NAMES[@]} -gt 0 ] && install_arenas "$FULLCHAIN" "$PRIVKEY"
exit 0
