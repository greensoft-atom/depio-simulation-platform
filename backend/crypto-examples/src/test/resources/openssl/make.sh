#!/bin/bash
# Makes the files beside it with the openssl command line, so that the tests prove the examples read
# and verify what another tool wrote, not only what they wrote themselves. Made 2026-10-05 with
# OpenSSL 3.0; run again only to replace them (keys and signatures come out different each time).
set -euo pipefail
cd "$(dirname "$0")"
M="a message signed by openssl"
P="a secret for rsa-oaep"

# RSA 2048: the key as PKCS#8 (PRIVATE KEY) and as PKCS#1 (RSA PRIVATE KEY), its public key, a
# self-signed certificate naming a.example.com, and an RSA-OAEP ciphertext with SHA-256 for both the
# digest and MGF1 (openssl's default MGF1 digest is the OAEP digest).
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out rsa-pkcs8.pem
openssl pkey -in rsa-pkcs8.pem -traditional -out rsa-pkcs1.pem
openssl pkey -in rsa-pkcs8.pem -pubout -out rsa-public.pem
printf %s "$P" | openssl pkeyutl -encrypt -pubin -inkey rsa-public.pem \
    -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 | base64 -w0 > rsa-oaep-sha256.b64
openssl req -x509 -key rsa-pkcs8.pem -subj "/CN=a.example.com" -days 36500 \
    -addext "subjectAltName=DNS:a.example.com,DNS:b.example.com" -out rsa-self-signed.pem
# An RSASSA-PSS signature: SHA-256, MGF1 on SHA-256 (openssl's default, the signature's digest) and a
# salt as long as the digest.
printf %s "$M" | openssl dgst -sha256 -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:digest \
    -sign rsa-pkcs8.pem | base64 -w0 > rsa-pss-sha256.sig.b64
# The same key, encrypted under a password (PKCS#8, AES-256-CBC, PBKDF2).
openssl pkcs8 -topk8 -in rsa-pkcs8.pem -v2 aes-256-cbc -passout pass:example-password -out rsa-encrypted.pem

# EC P-256: the key as SEC1 (EC PRIVATE KEY), its public key, and a SHA256withECDSA signature in DER.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out ec.pem
openssl pkey -in ec.pem -traditional -out ec-sec1.pem
openssl pkey -in ec.pem -pubout -out ec-public.pem
printf %s "$M" | openssl dgst -sha256 -sign ec.pem | base64 -w0 > ec-sha256.sig.b64
rm ec.pem

# Ed25519: its public key and a signature.
openssl genpkey -algorithm ED25519 -out ed25519.pem
openssl pkey -in ed25519.pem -pubout -out ed25519-public.pem
printf %s "$M" > message.txt
openssl pkeyutl -sign -rawin -inkey ed25519.pem -in message.txt | base64 -w0 > ed25519.sig.b64
rm ed25519.pem message.txt
