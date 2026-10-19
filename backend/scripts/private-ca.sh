#!/bin/bash
# The backend's own certificate authority (D-82, docs/operations/06-certificates.md). The site has no domain, so
# no public CA certifies it: this makes a root once, and from it the one certificate nginx serves, naming the
# site's public address (443) and every arena's TLS name (9001). The app ships with the root's certificate,
# ca.pem, and trusts it alone. The root's key, ca.key, never goes on a server.
#
#   private-ca.sh root  <ca-dir>                        the root, once: ca-dir/ca.key (0600) and ca-dir/ca.pem
#   private-ca.sh issue <ca-dir> <out-dir> <name>...    a key and a certificate for those addresses and names,
#                                                       signed by the root: out-dir/privkey.pem, fullchain.pem
#   private-ca.sh names <backend-stream.conf>           the arenas' names in nginx's map, for issue
#   private-ca.sh show  <certificate.pem>               what a certificate names, by whom, until when
#
# Each name is an IP address (the site's public one) or a host name (an arena's, a1.arena.internal). Keys are EC
# P-256, signatures SHA-256. Nothing is overwritten: a root already in ca-dir, or a key or certificate already in
# out-dir, refuses the run. ROOT_DAYS (3650) and DAYS (398) set how long the root and a certificate are valid.
# Needs openssl alone: the system's, 3.0 on RHEL 9.
set -euo pipefail
umask 077

ROOT_DAYS=${ROOT_DAYS:-3650}
DAYS=${DAYS:-398}

refuse() { echo "refusing: $*" >&2; exit 1; }
usage() { sed -n '/^#   private-ca.sh root/,/^#$/p' "$0" | sed -e '/^#$/d' -e 's/^#   /  /' >&2; exit 2; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

is_ip() { [[ $1 =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || [[ $1 == *:* && $1 =~ ^[0-9A-Fa-f:.]+$ ]]; }
is_name() { [[ ${#1} -le 253 && $1 =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$ ]]; }

case "${1:-}" in
root)
    [ $# -eq 2 ] || usage
    dir=$2
    mkdir -p "$dir"
    [ ! -e "$dir/ca.key" ] && [ ! -e "$dir/ca.pem" ] || refuse "$dir already holds a root; a new one means a client release"
    cat > "$WORK/root.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = backend root CA
[root]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
    openssl req -x509 -new -config "$WORK/root.cnf" -extensions root -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
        -nodes -sha256 -days "$ROOT_DAYS" -keyout "$dir/ca.key" -out "$dir/ca.pem" 2> "$WORK/err" \
        || { cat "$WORK/err" >&2; refuse "openssl could not make the root"; }
    chmod 0600 "$dir/ca.key"
    chmod 0644 "$dir/ca.pem"
    echo "root made: $dir/ca.pem (the app ships it; every machine keeps a copy, /etc/backend/tls/ca.pem)"
    echo "           $dir/ca.key (the root's key: keep it off every server, with a copy apart)"
    openssl x509 -in "$dir/ca.pem" -noout -subject -enddate -fingerprint -sha256
    ;;
issue)
    [ $# -ge 4 ] || usage
    dir=$2 out=$3
    shift 3
    [ -r "$dir/ca.key" ] && [ -r "$dir/ca.pem" ] || refuse "no root in $dir: private-ca.sh root $dir first"
    mkdir -p "$out"
    [ ! -e "$out/privkey.pem" ] && [ ! -e "$out/fullchain.pem" ] || refuse "$out already holds a key or a certificate"
    san=()
    for n in "$@"; do
        if is_ip "$n"; then san+=("IP:$n")
        elif is_name "$n"; then san+=("DNS:$n")
        else refuse "'$n' is neither an IP address nor a host name in lower case, as a1.arena.internal"
        fi
    done
    alt=$(IFS=,; echo "${san[*]}")
    cat > "$WORK/leaf.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = backend
[leaf]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
subjectAltName = $alt
EOF
    openssl req -new -config "$WORK/leaf.cnf" -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -keyout "$WORK/privkey.pem" -out "$WORK/leaf.csr" 2> "$WORK/err" \
        || { cat "$WORK/err" >&2; refuse "openssl could not make the key"; }
    openssl x509 -req -in "$WORK/leaf.csr" -CA "$dir/ca.pem" -CAkey "$dir/ca.key" \
        -set_serial "0x$(openssl rand -hex 16)" -sha256 -days "$DAYS" \
        -extfile "$WORK/leaf.cnf" -extensions leaf -out "$WORK/fullchain.pem" 2> "$WORK/err" \
        || { cat "$WORK/err" >&2; refuse "openssl could not sign the certificate"; }
    openssl verify -CAfile "$dir/ca.pem" -purpose sslserver "$WORK/fullchain.pem" > /dev/null \
        || refuse "the certificate made does not verify against $dir/ca.pem"
    install -m 0600 "$WORK/privkey.pem" "$out/privkey.pem"
    install -m 0644 "$WORK/fullchain.pem" "$out/fullchain.pem"
    echo "certificate made: $out/fullchain.pem and its key $out/privkey.pem, for ${san[*]}"
    openssl x509 -in "$out/fullchain.pem" -noout -enddate
    echo "install it on each machine: CA_FILE=/etc/backend/tls/ca.pem /opt/backend/scripts/install-certificate.sh \\"
    echo "    $out/fullchain.pem $out/privkey.pem"
    ;;
names)
    [ $# -eq 2 ] || usage
    [ -r "$2" ] || refuse "cannot read $2"
    # The map's names, its default left out: lines "    a1.arena.internal  10.0.0.1:9011;".
    awk '/^map \$ssl_server_name/ { on = 1; next } on && /^}/ { exit }
         on && $1 != "default" && $1 !~ /^#/ && NF >= 2 { print $1 }' "$2"
    ;;
show)
    [ $# -eq 2 ] || usage
    openssl x509 -in "$2" -noout -subject -issuer -startdate -enddate -ext subjectAltName
    ;;
*)
    usage
    ;;
esac
