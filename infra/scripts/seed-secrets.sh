#!/usr/bin/env bash
# Seeds the Key Vault secrets the apps reference but Bicep must not own (docs/24 s6.4, s13.6):
#   aron-jwt-signing-key       ES256 private key, PKCS#8 PEM; created once, never rotated by a deploy
#   aron-jwt-kid               its key id
#   aron-fcm-service-account   Firebase service account JSON from the GitHub secret FCM_SERVICE_ACCOUNT_JSON;
#                              "{}" when the secret is not set (push disabled until it is)
#   aron-play-integrity-service-account  Play Integrity decode account JSON from the GitHub secret
#                              PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON; " " (one space) when not set: the API reads that as
#                              absent and decodes with the FCM account. The gate cfg.device.require_integrity is NOT set here.
#   aron-web-session-secret    48 random characters sealing the web BFF session cookies (ARON_SESSION_SECRET)
#   aron-mfa-key               32 random bytes, base64, sealing web admins' TOTP secrets (ARON_MFA_KEY, api only);
#                              created once and never replaced by a deploy (rotation: docs/runbooks/rotate-mfa-key.md)
#   aron-lab-grant-key         stage and prod only (D-OWNER-MDM-02): the per-environment P-256 key (PKCS#8, DER, base64 on one
#                              line) the api signs device lab grants with (ARON_LAB_GRANT_KEY); created once, never rotated by
#                              a deploy, never printed. aron-lab-grant-public is its public half (X.509 SubjectPublicKeyInfo,
#                              DER, base64): not a secret, read by the laptop operator and built into that environment's APK
# Idempotent: an existing value is kept, except the FCM and Play Integrity accounts, which follow their GitHub secrets
# when given.
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

if [ -n "${PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON:-}" ]; then
  printf '%s' "$PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON" > "$tmp/pi.json"
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp/pi.json" || die "PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON is not valid JSON"
  put_file aron-play-integrity-service-account "$tmp/pi.json" application/json
  note "aron-play-integrity-service-account set from the GitHub secret"
elif exists aron-play-integrity-service-account; then
  note "aron-play-integrity-service-account present (kept; GitHub secret PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON not set)"
else
  printf ' ' > "$tmp/pi.json"
  put_file aron-play-integrity-service-account "$tmp/pi.json" text/plain
  note "aron-play-integrity-service-account created as a placeholder (the API decodes with the FCM account)"
fi

if exists aron-web-session-secret; then
  note "aron-web-session-secret present (kept)"
else
  openssl rand -hex 24 > "$tmp/session"
  put_file aron-web-session-secret "$tmp/session" text/plain
  note "aron-web-session-secret created"
fi

# Never regenerated: a new value would make every enrolled admin's TOTP secret unreadable (rotation is a runbook step).
# Only a definite "not found" creates it; any other error (throttling, network, rights) stops the deploy instead.
if mfa_err="$(az keyvault secret show --vault-name "$kv" --name aron-mfa-key --query id -o tsv 2>&1 >/dev/null)"; then
  note "aron-mfa-key present (kept)"
elif ! grep -q "SecretNotFound" <<<"$mfa_err"; then
  die "could not read aron-mfa-key (not creating a new one): ${mfa_err:0:300}"
else
  openssl rand -base64 32 | tr -d '\n' > "$tmp/mfa"
  put_file aron-mfa-key "$tmp/mfa" text/plain
  note "aron-mfa-key created"
fi

# Lab grant key pair, final-account environments only (dev keeps the key made by tools/make-lab-grant-key.sh). Only a
# definite "not found" creates it; any other read error stops the deploy instead of replacing a key phones may trust.
if [[ "${PROFILE:-}" == stage || "${PROFILE:-}" == prod ]]; then
  if lab_err="$(az keyvault secret show --vault-name "$kv" --name aron-lab-grant-key --query id -o tsv 2>&1 >/dev/null)"; then
    note "aron-lab-grant-key present (kept)"
  elif ! grep -q "SecretNotFound" <<<"$lab_err"; then
    die "could not read aron-lab-grant-key (not creating a new one): ${lab_err:0:300}"
  else
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -outform DER -out "$tmp/lab.der"
    base64 -w0 < "$tmp/lab.der" > "$tmp/lab.b64"
    openssl pkey -inform DER -in "$tmp/lab.der" -pubout -outform DER | base64 -w0 > "$tmp/lab-pub.b64"
    # Public half FIRST: the private key's presence is what later deploys test, so a failure between the two writes leaves
    # no private key without its public half (the next deploy simply tries again).
    put_file aron-lab-grant-public "$tmp/lab-pub.b64" text/plain
    put_file aron-lab-grant-key "$tmp/lab.b64" text/plain
    note "aron-lab-grant-public and aron-lab-grant-key created"
  fi
  # Self-heal for a vault whose public half is missing while the private key exists (never print either).
  if ! exists aron-lab-grant-public; then
    az keyvault secret show --vault-name "$kv" --name aron-lab-grant-key --query value -o tsv | tr -d '\r\n' | base64 -d > "$tmp/lab-heal.der" 2>/dev/null \
      || die "aron-lab-grant-key is unreadable; cannot derive its public half"
    openssl pkey -inform DER -in "$tmp/lab-heal.der" -pubout -outform DER | base64 -w0 > "$tmp/lab-pub.b64"
    put_file aron-lab-grant-public "$tmp/lab-pub.b64" text/plain
    note "aron-lab-grant-public re-derived from the private key"
  fi
fi

# Dev seed accounts (db/seed: sr1001 and the other pilot test accounts) for the SR slice smoke: only when the dev
# seed is switched on (deploy.sh: param devSeed = true in a dev profile). The devseed job hashes it; the smoke logs in with it.
if [ "${ARON_DEV_SEED:-false}" = true ] && [[ "${PROFILE:-}" == dev* ]]; then
  if exists aron-dev-seed-password; then
    note "aron-dev-seed-password present (kept)"
  else
    openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24 > "$tmp/seedpw"
    put_file aron-dev-seed-password "$tmp/seedpw" text/plain
    note "aron-dev-seed-password created"
  fi
  # The slice smoke's device key (ES256, P-256): its public half goes on the seed device, the smoke signs with it.
  if exists aron-dev-smoke-device-key; then
    note "aron-dev-smoke-device-key present (kept)"
  else
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/smoke.pem"
    put_file aron-dev-smoke-device-key "$tmp/smoke.pem" application/x-pem-file
    note "aron-dev-smoke-device-key created"
  fi
fi
