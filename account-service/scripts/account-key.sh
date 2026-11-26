#!/bin/bash
# A signing key for the account service (D-117, docs/detailed-design/10-accounts.md §3.4): an EC P-256 key, made with
# this machine's openssl (any from 1.1.1, as the certificate scripts, D-103), printed and never written to a file of its
# own: the key's place is the account service's settings file, root's alone (D-102). Two lines:
#
#   ACCOUNT_SIGNING_KEY=<the private key, PKCS #8, base64 on one line>
#   # its public half, for ACCOUNT_RETIRED_KEYS once it is rotated out: <SubjectPublicKeyInfo, base64>
#
#   scripts/account-key.sh >> /etc/backend/account.env       as root, the file 0600; then the old line taken out
set -euo pipefail
command -v openssl > /dev/null || { echo "account-key.sh needs openssl" >&2; exit 2; }
# PKCS #8 by name: genpkey's DER is OpenSSL 3's traditional SEC1 form (RFC 5915), which Java does not read as a
# PKCS #8 key; pkcs8 -topk8 writes the form ACCOUNT_SIGNING_KEY is, on 1.1.1 and 3.x alike.
key=$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve \
      | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n')
pub=$(printf '%s' "$key" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 | tr -d '\n')
[ -n "$key" ] && [ -n "$pub" ] || { echo "account-key.sh: openssl made no key" >&2; exit 1; }
echo "ACCOUNT_SIGNING_KEY=$key"
echo "# its public half, for ACCOUNT_RETIRED_KEYS once it is rotated out: $pub"
