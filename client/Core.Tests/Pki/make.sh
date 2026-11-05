#!/bin/bash
# The tests' certificates (D-109), made once and committed with the test servers' keys: test keys alone, nothing of
# the site's. .NET Framework 4.7.1 and Unity 2019 have no CertificateRequest, so the tests read these. RSA, signed as
# scripts/private-ca.sh signs (D-108). Each dated where its test needs it: valid until 2049 at least, expired in 2025,
# not valid before 2090; renewed by running this again (OpenSSL 3; the tests then read the new files).
#
#   client/Core.Tests/Pki/make.sh
set -euo pipefail
cd "$(dirname "$0")"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
touch "$W/index.txt"; echo 1000 > "$W/serial"
root() {   # file, name, key: a root, its key kept in $W for the leaves
    openssl req -x509 -new -newkey "$3" -nodes -sha256 -days 27000 -subj "/CN=$2" -keyout "$W/$1.key" -out "$1.pem" \
        -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2> /dev/null
}
leaf() {   # file, root, from, until, digest, names...: a server's certificate, its key in $W
    local out=$1 ca=$2 from=$3 until=$4 md=$5 san="" n; shift 5
    for n in "$@"; do if [[ $n =~ ^[0-9.]+$ || $n == *:* ]]; then san+="IP:$n,"; else san+="DNS:$n,"; fi; done
    cat > "$W/ca.cnf" << EOF
[ca]
default_ca = d
[d]
database = $W/index.txt
new_certs_dir = $W
serial = $W/serial
policy = p
unique_subject = no
[p]
commonName = supplied
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = ${san%,}
EOF
    openssl req -new -newkey rsa:2048 -nodes -subj /CN=backend -keyout "$W/$out.key" -out "$W/$out.csr" 2> /dev/null
    openssl ca -batch -notext -config "$W/ca.cnf" -extensions ext -cert "$ca.pem" -keyfile "$W/$ca.key" -md "$md" \
        -startdate "$from" -enddate "$until" -in "$W/$out.csr" -out "$out.pem" 2> /dev/null
}
p12() {    # file: a server's certificate and key, in what Mono 5.11 and .NET Framework read (3DES, SHA-1), "test"
    openssl pkcs12 -export -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 -passout pass:test \
        -in "$1.pem" -inkey "$W/$1.key" -out "$1.p12"
}
root root "backend test root" rsa:2048
root other-root "someone else" rsa:2048
root twin-root "backend test root" rsa:2048
# A root of another key, as the backend's were before D-108: refused.
openssl req -x509 -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 27000 -subj "/CN=backend test root" \
    -keyout "$W/ec-root.key" -out ec-root.pem 2> /dev/null
ok=20200101000000Z long=20491231000000Z
leaf leaf root $ok $long sha256 a1.arena.internal 203.0.113.10 2001:db8::7
leaf other-leaf other-root $ok $long sha256 a1.arena.internal
leaf twin-leaf twin-root $ok $long sha256 a1.arena.internal
leaf expired root $ok 20250101000000Z sha256 a1.arena.internal
leaf not-yet root 20900101000000Z 20950101000000Z sha256 a1.arena.internal
leaf sha384 root $ok $long sha384 a1.arena.internal
leaf until-2035 root $ok 20350101000000Z sha256 a1.arena.internal
leaf until-2051 root $ok 20510101000000Z sha256 a1.arena.internal
leaf server-arena root $ok $long sha256 a1.arena.internal
leaf server-local root $ok $long sha256 127.0.0.1
leaf other-local other-root $ok $long sha256 127.0.0.1
for s in server-arena server-local other-local; do p12 $s; done
ls
