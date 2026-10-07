#!/usr/bin/env bash
# Seeds the Key Vault secrets the apps reference but Bicep must not own (docs/24 s6.4, s13.6):
#   aron-jwt-signing-key       ES256 private key, PKCS#8 PEM; created once, never rotated by a deploy
#   aron-jwt-kid               its key id
#   aron-fcm-service-account   Firebase service account JSON from the GitHub secret FCM_SERVICE_ACCOUNT_JSON;
#                              "{}" when the secret is not set (push disabled until it is)
#   aron-web-session-secret    48 random characters sealing the web BFF session cookies (ARON_SESSION_SECRET)
# Idempotent: an existing value is kept, except the FCM account, which follows the GitHub secret when one is given.
# Usage: infra/scripts/seed-secrets.sh <key-vault-name>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
kv="${1:?key vault name}"

exists() { az keyvault secret show --vault-name "$kv" --name "$1" --query id -o tsv >/dev/null 2>&1; }
put_file() { az keyvault secret set --vault-name "$kv" --name "$1" --file "$2" --encoding utf-8 --content-type "$3" --only-show-errors >/dev/null; }

# A new role assignment (Key Vault Secrets Officer for this deployer) can take a few minutes to apply.
for i in $(seq 1 30); do
  if az keyvault secret list --vault-name "$kv" --maxresults 1 --only-show-errors >/dev/null 2>&1; then break; fi
  [ "$i" -eq 30 ] && die "no data-plane access to Key Vault $kv after 5 minutes (role assignment not applied?)"
  sleep 10
done

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

if exists aron-jwt-signing-key; then
  note "aron-jwt-signing-key present (kept)"
else
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/jwt.pem"
  put_file aron-jwt-signing-key "$tmp/jwt.pem" application/x-pem-file
  printf 'k-%s' "$(date -u +%Y%m%d%H%M)" > "$tmp/kid"
  put_file aron-jwt-kid "$tmp/kid" text/plain
  note "aron-jwt-signing-key and aron-jwt-kid created"
fi
if ! exists aron-jwt-kid; then
  printf 'k-%s' "$(date -u +%Y%m%d%H%M)" > "$tmp/kid"
  put_file aron-jwt-kid "$tmp/kid" text/plain
  note "aron-jwt-kid created"
fi

if [ -n "${FCM_SERVICE_ACCOUNT_JSON:-}" ]; then
  printf '%s' "$FCM_SERVICE_ACCOUNT_JSON" > "$tmp/fcm.json"
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp/fcm.json" || die "FCM_SERVICE_ACCOUNT_JSON is not valid JSON"
  put_file aron-fcm-service-account "$tmp/fcm.json" application/json
  note "aron-fcm-service-account set from the GitHub secret"
elif exists aron-fcm-service-account; then
  note "aron-fcm-service-account present (kept; GitHub secret FCM_SERVICE_ACCOUNT_JSON not set)"
else
  printf '{}' > "$tmp/fcm.json"
  put_file aron-fcm-service-account "$tmp/fcm.json" application/json
  note "aron-fcm-service-account created as {} (push disabled until FCM_SERVICE_ACCOUNT_JSON is set)"
fi

if exists aron-web-session-secret; then
  note "aron-web-session-secret present (kept)"
else
  openssl rand -hex 24 > "$tmp/session"
  put_file aron-web-session-secret "$tmp/session" text/plain
  note "aron-web-session-secret created"
fi

# Dev seed accounts (db/seed: sr1001 and the other pilot test accounts) for the SR slice smoke: only when the dev
# seed is switched on (ARON_DEV_SEED=true, dev profiles). The devseed job hashes it; the smoke logs in with it.
if [ "${ARON_DEV_SEED:-false}" = true ] && [[ "${PROFILE:-}" == dev* ]]; then
  if exists aron-dev-seed-password; then
    note "aron-dev-seed-password present (kept)"
  else
    openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24 > "$tmp/seedpw"
    put_file aron-dev-seed-password "$tmp/seedpw" text/plain
    note "aron-dev-seed-password created"
  fi
fi
