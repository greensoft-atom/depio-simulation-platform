#!/bin/bash
# Checks private-ca.sh and install-certificate.sh (docs/operations/06-certificates.md, D-82) on this machine, with
# openssl alone and nginx stood in for: what each must make, and each refusal each promises. The root made once and
# never overwritten, its key private; a certificate for the site's address and the seven names in the shipped
# stream map; refused before anything is installed: a certificate short of an arena's name or of the address, one
# from another root, and a key that is not the certificate's; installed, with the key private; and when nginx
# refuses the new files, the previous ones put back. With no root in TLS_DIR, the system's roots are the trust,
# and the backend's certificate is refused.
#
#   scripts/check-certificates.sh      from anywhere; a few seconds. Ends "--- <n> failed", non-zero on any.
#   IMAGE=<image> scripts/check-certificates.sh   the same, in that container image, on its openssl (D-103): UBI 9's
#                                      3.x as it is, UBI 8's 1.1.1 with openssl added, which a RHEL 8 server has:
#                                      docker build -t backend-ubi8-openssl - <<< $'FROM registry.access.redhat.com/ubi8/ubi:8.10\nRUN dnf install -y -q openssl'
#
# CA and IC name another private-ca.sh or install-certificate.sh, for a mutation check (on this machine).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
DEPLOY=$HERE/../deploy
if [ -n "${IMAGE:-}" ]; then
    exec docker run --rm --network none -v "$HERE:/backend/scripts:ro" -v "$DEPLOY:/backend/deploy:ro" "$IMAGE" \
        bash -c 'openssl version; /backend/scripts/check-certificates.sh'
fi
CA=${CA:-$HERE/private-ca.sh}
IC=${IC:-$HERE/install-certificate.sh}
T=$(mktemp -d "${TMPDIR:-/tmp}/check-certificates.XXXXXX")
trap 'rm -rf "$T"' EXIT
fails=0
pass() { echo "ok   $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }
expect_ok() { local d=$1; shift; if out=$("$@" 2>&1); then pass "$d"; else fail "$d: $out"; fi; }
expect_refused() {   # expect_refused <what> <the refusal's words> <command...>
    local d=$1 pat=$2; shift 2
    if out=$("$@" 2>&1); then fail "$d: accepted"
    elif echo "$out" | grep -q -- "$pat"; then pass "$d: refused, '$pat'"
    else fail "$d: refused, but not for '$pat': $out"
    fi
}
same() { [ "$(sha256sum < "$1")" = "$(sha256sum < "$2")" ]; }   # cmp is not in every image

# ---- private-ca.sh ----

NAMES=$("$CA" names "$DEPLOY/nginx/backend-stream.conf")
[ "$(echo $NAMES | wc -w)" = 7 ] && pass "names: the map's seven, $(echo $NAMES)" || fail "names: '$(echo $NAMES)'"
expect_ok "root" "$CA" root "$T/ca"
expect_refused "root again" "already holds a root" "$CA" root "$T/ca"
[ "$(stat -c %a "$T/ca/ca.key")" = 600 ] && pass "ca.key 0600" || fail "ca.key $(stat -c %a "$T/ca/ca.key")"
expect_ok "issue for the address and the names" "$CA" issue "$T/ca" "$T/site" 203.0.113.10 $NAMES
# RSA, the one key Unity 2019's runtime can check (D-108): the root's 3072 bits, a certificate's 2048, SHA-256.
key_of() { openssl x509 -in "$1" -noout -text | grep -E 'Public Key Algorithm|Public-Key:|Signature Algorithm' | sort -u | tr -s ' ' | tr '\n' ' '; }
rsa() { local k; k=$(key_of "$1"); [[ $k == *rsaEncryption* && $k == *"($2 bit)"* && $k == *sha256WithRSAEncryption* ]]; }
rsa "$T/ca/ca.pem" 3072 && pass "the root's key: RSA 3072, SHA-256" || fail "the root's key: $(key_of "$T/ca/ca.pem")"
rsa "$T/site/fullchain.pem" 2048 && pass "a certificate's key: RSA 2048, SHA-256" || fail "a certificate's key: $(key_of "$T/site/fullchain.pem")"
expect_refused "issue into a directory that holds one" "already holds" "$CA" issue "$T/ca" "$T/site" 203.0.113.10
expect_refused "issue a name in upper case" "neither an IP" "$CA" issue "$T/ca" "$T/bad" A1.Arena.Internal
expect_refused "issue with no root" "no root" "$CA" issue "$T/none" "$T/x" 203.0.113.10
expect_ok "show" "$CA" show "$T/site/fullchain.pem"
expect_ok "issue short of b3" "$CA" issue "$T/ca" "$T/short" 203.0.113.10 $(echo $NAMES | tr ' ' '\n' | grep -v '^b3\.')
expect_ok "issue without the address" "$CA" issue "$T/ca" "$T/noaddr" $NAMES
expect_ok "another root" "$CA" root "$T/other"
expect_ok "issue from another root" "$CA" issue "$T/other" "$T/foreign" 203.0.113.10 $NAMES
expect_ok "issue a second" "$CA" issue "$T/ca" "$T/site2" 203.0.113.10 $NAMES

# ---- install-certificate.sh, on the shipped nginx configuration, nginx stood in for by NT ----

mkdir -p "$T/etc/tls" "$T/etc/credentials" "$T/etc/cfg"
install_with() {   # NC and SC name another nginx and stream configuration
    TLS_DIR=$T/etc/tls CREDENTIALS_DIR=$T/etc/credentials CONFIG_DIR=$T/etc/cfg \
    NGINX_CONF=${NC:-$DEPLOY/nginx/backend.conf} STREAM_CONF=${SC:-$DEPLOY/nginx/backend-stream.conf} \
    NGINX_TEST="${NT:-true}" NGINX_RELOAD=true NGINX_RUNNING=false "$IC" "$@"
}
expect_refused "install with no root in TLS_DIR: the system's roots" "does not verify" \
    install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
cp "$T/ca/ca.pem" "$T/etc/tls/ca.pem"
expect_refused "install short of b3" "does not name b3.arena.internal" install_with "$T/short/fullchain.pem" "$T/short/privkey.pem"
expect_refused "install without the address" "does not name 203.0.113.10" install_with "$T/noaddr/fullchain.pem" "$T/noaddr/privkey.pem"
expect_refused "install from another root" "does not verify" install_with "$T/foreign/fullchain.pem" "$T/foreign/privkey.pem"
expect_refused "install with another key" "is not the key" install_with "$T/site/fullchain.pem" "$T/short/privkey.pem"
[ ! -e "$T/etc/tls/fullchain.pem" ] && [ ! -e "$T/etc/credentials/privkey.pem" ] \
    && pass "the refusals installed nothing" || fail "a refusal installed a file"
NT=false expect_refused "a first install that nginx refuses" "none where there were none" \
    install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
[ -z "$(ls -A "$T/etc/tls" | grep -v '^ca.pem$')" ] && [ -z "$(ls "$T/etc/credentials")" ] \
    && pass "and nothing left installed: no file, nothing kept (O-76)" || fail "left: $(ls -A "$T/etc/tls" "$T/etc/credentials" | tr '\n' ' ')"
expect_ok "install the site's" install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
same "$T/etc/tls/fullchain.pem" "$T/site/fullchain.pem" && pass "fullchain.pem installed" || fail "fullchain.pem not installed"
[ "$(stat -c %a "$T/etc/credentials/privkey.pem")" = 600 ] && pass "privkey.pem 0600" \
    || fail "privkey.pem $(stat -c %a "$T/etc/credentials/privkey.pem")"
NT=false expect_refused "install when nginx refuses the files" "previous files are back" \
    install_with "$T/site2/fullchain.pem" "$T/site2/privkey.pem"
same "$T/etc/tls/fullchain.pem" "$T/site/fullchain.pem" && same "$T/etc/credentials/privkey.pem" "$T/site/privkey.pem" \
    && pass "the previous certificate and key are back" || fail "the previous files were not put back"


# ---- what the scripts' review found (plan item 116) ----

cat "$T/site2/fullchain.pem" "$T/site2/privkey.pem" > "$T/bundle.pem"
expect_ok "install a file that holds the key as well as the certificate" install_with "$T/bundle.pem" "$T/site2/privkey.pem"
! grep -q "PRIVATE KEY" "$T/etc/tls/fullchain.pem" && same "$T/etc/tls/fullchain.pem" "$T/site2/fullchain.pem" \
    && pass "the certificate alone installed, readable by all; the key not with it (O-75)" || fail "fullchain.pem holds the key"
[ ! -e "$T/etc/tls/fullchain.pem.previous" ] && [ ! -e "$T/etc/credentials/privkey.pem.previous" ] \
    && pass "the replaced certificate and key not kept once nginx accepts the new (O-76)" || fail "a .previous left"
sed 's/^\([[:space:]]*\)server_name .*;/\1server_name _;/' "$DEPLOY/nginx/backend.conf" > "$T/underscore.conf"
NC=$T/underscore.conf expect_refused "install with server_name _ and the arenas' map" "no server_name" \
    install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
leaf() {   # leaf <dir> <subject CN> <subjectAltName> <days> <root dir>: as another CA might make one
    mkdir -p "$1"
    openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=$2" -keyout "$1/privkey.pem" \
        -out "$1/csr" 2> /dev/null
    printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$3" > "$1/ext"
    openssl x509 -req -in "$1/csr" -CA "$5/ca.pem" -CAkey "$5/ca.key" -set_serial 7 -days "$4" -extfile "$1/ext" \
        -out "$1/fullchain.pem" 2> /dev/null
}
printf 'map $ssl_server_name $backend_arena {\n    a1.arena.internal 127.0.0.1:9011;\n    default 127.0.0.1:9;\n}\n' \
    > "$T/one-name.conf"
leaf "$T/cn" a1.arena.internal IP:203.0.113.10 30 "$T/ca"
SC=$T/one-name.conf expect_refused "install one naming an arena in its subject alone" "does not name a1.arena.internal" \
    install_with "$T/cn/fullchain.pem" "$T/cn/privkey.pem"
ROOT_DAYS=30 expect_ok "a root of 30 days" "$CA" root "$T/brief30"
expect_refused "issue a certificate that outlives its root" "the root expires" "$CA" issue "$T/brief30" "$T/past" 203.0.113.10
ROOT_DAYS=5 expect_ok "a root of 5 days" "$CA" root "$T/brief"
leaf "$T/outlives" backend "IP:203.0.113.10,$(printf 'DNS:%s,' $NAMES | sed 's/,$//')" 398 "$T/brief"
out=$(CA_FILE=$T/brief/ca.pem install_with "$T/outlives/fullchain.pem" "$T/outlives/privkey.pem" 2>&1) \
    && grep -q "or its root expires" <<< "$out" && pass "install one whose root ends in 5 days: warned, the root counted (O-73)" \
    || fail "the root's end not warned of: $out"


# ---- the machine's OpenSSL, any from 1.1.1, and the trust the backend's root alone on each (D-103) ----

REAL=$(command -v openssl)
standin() {   # standin <dir> <what openssl's "version" says>: that openssl on PATH, the real one for the rest
    mkdir -p "$1"
    printf '#!/bin/bash\n[ "$1" = version ] && { echo "%s"; exit 0; }\nexec %s "$@"\n' "$2" "$REAL" > "$1/openssl"
    chmod +x "$1/openssl"
}
standin "$T/old" "OpenSSL 1.0.2k-fips  26 Jan 2017"
PATH=$T/old:$PATH expect_refused "install with an OpenSSL older than 1.1.1" "needs OpenSSL 1.1.1 or later; this is OpenSSL 1.0.2k" \
    install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
standin "$T/new" "OpenSSL 4.0.1 1 Jul 2026"
PATH=$T/new:$PATH expect_ok "install with an OpenSSL of a later major" install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
# A root of a name of its own, as a public CA's is: one named as the backend's is never looked for elsewhere. Its
# configuration its own, as private-ca.sh's is: 1.1.1's -addext adds to the system's openssl.cnf, a second
# basicConstraints, and the root is no root.
mkdir -p "$T/public" "$T/roots"
printf '[req]\ndistinguished_name = dn\nprompt = no\n[dn]\nCN = a public root\n[root]\nbasicConstraints = critical, CA:TRUE\nkeyUsage = critical, keyCertSign\n' \
    > "$T/public/root.cnf"
openssl req -x509 -new -config "$T/public/root.cnf" -extensions root -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
    -days 30 -keyout "$T/public/ca.key" -out "$T/public/ca.pem" 2> /dev/null
leaf "$T/publicleaf" backend "IP:203.0.113.10,$(printf 'DNS:%s,' $NAMES | sed 's/,$//')" 30 "$T/public"
cp "$T/public/ca.pem" "$T/roots/" && openssl rehash "$T/roots" 2> /dev/null
SSL_CERT_DIR=$T/roots openssl verify -purpose sslserver "$T/publicleaf/fullchain.pem" > /dev/null 2>&1 \
    && pass "a root in SSL_CERT_DIR is trusted by openssl's defaults" || fail "SSL_CERT_DIR's root not trusted even by openssl's defaults"
SSL_CERT_DIR=$T/roots expect_refused "install one of a root in SSL_CERT_DIR, the backend's in TLS_DIR" "does not verify" \
    install_with "$T/publicleaf/fullchain.pem" "$T/publicleaf/privkey.pem"
mkdir -p "$T/odd"
printf '#!/bin/bash\n[ "$1" = verify ] && { echo "verify: Unknown option: -x"; exit 1; }\nexec %s "$@"\n' "$REAL" > "$T/odd/openssl"
chmod +x "$T/odd/openssl"
PATH=$T/odd:$PATH expect_refused "a verify failing with no error line: refused in openssl's words" \
    "does not verify: verify: Unknown option: -x$" install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"

echo "--- $fails failed"
[ "$fails" = 0 ]
