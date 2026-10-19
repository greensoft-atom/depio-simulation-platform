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
#
# CA and IC name another private-ca.sh or install-certificate.sh, for a mutation check.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
DEPLOY=$HERE/../deploy
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

# ---- private-ca.sh ----

NAMES=$("$CA" names "$DEPLOY/nginx/backend-stream.conf")
[ "$(echo $NAMES | wc -w)" = 7 ] && pass "names: the map's seven, $(echo $NAMES)" || fail "names: '$(echo $NAMES)'"
expect_ok "root" "$CA" root "$T/ca"
expect_refused "root again" "already holds a root" "$CA" root "$T/ca"
[ "$(stat -c %a "$T/ca/ca.key")" = 600 ] && pass "ca.key 0600" || fail "ca.key $(stat -c %a "$T/ca/ca.key")"
expect_ok "issue for the address and the names" "$CA" issue "$T/ca" "$T/site" 203.0.113.10 $NAMES
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
install_with() {
    TLS_DIR=$T/etc/tls CREDENTIALS_DIR=$T/etc/credentials CONFIG_DIR=$T/etc/cfg \
    NGINX_CONF=$DEPLOY/nginx/backend.conf STREAM_CONF=$DEPLOY/nginx/backend-stream.conf \
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
expect_ok "install the site's" install_with "$T/site/fullchain.pem" "$T/site/privkey.pem"
cmp -s "$T/etc/tls/fullchain.pem" "$T/site/fullchain.pem" && pass "fullchain.pem installed" || fail "fullchain.pem not installed"
[ "$(stat -c %a "$T/etc/credentials/privkey.pem")" = 600 ] && pass "privkey.pem 0600" \
    || fail "privkey.pem $(stat -c %a "$T/etc/credentials/privkey.pem")"
NT=false expect_refused "install when nginx refuses the files" "previous files are back" \
    install_with "$T/site2/fullchain.pem" "$T/site2/privkey.pem"
cmp -s "$T/etc/tls/fullchain.pem" "$T/site/fullchain.pem" && cmp -s "$T/etc/credentials/privkey.pem" "$T/site/privkey.pem" \
    && pass "the previous certificate and key are back" || fail "the previous files were not put back"

echo "--- $fails failed"
[ "$fails" = 0 ]
