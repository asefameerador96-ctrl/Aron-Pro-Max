#!/usr/bin/env bash
# Generates the lab-grant signing key pair (docs/runbooks/lab-exemption.md). The PRIVATE key is written to the file you name (mode 600) and is
# never printed; only the PUBLIC key (base64 SubjectPublicKeyInfo) is printed, for android/core-system/src/main/res/values/lab_grant.xml.
# Put the private key's base64 into the server secret ARON_LAB_GRANT_KEY (Key Vault / GitHub secret), then delete the file.
set -euo pipefail
out="${1:?usage: make-lab-grant-key.sh <private-key-output-file>}"
[ ! -e "$out" ] || { echo "refusing to overwrite $out" >&2; exit 1; }
umask 177
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -outform DER -out "$tmp"
base64 -w0 < "$tmp" > "$out"
echo "private key (base64 PKCS#8) written to $out; public key (base64 SPKI):"
openssl pkey -inform DER -in "$tmp" -pubout -outform DER | base64 -w0; echo
