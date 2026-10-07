#!/bin/sh
# Entry point of the dev seed image (infra/docker/devseed.Dockerfile): loads db/seed/0*.sql in file-name order (each
# file is idempotent), then gives every seeded test account (pilot) without a password the Argon2id hash of
# ARON_SEED_PASSWORD, with the backend's parameters (m = 19 MiB, t = 2, p = 1), as db's SeedLoader does.
# The password comes from Key Vault (secret reference) on stdin of argon2 and the hash reaches psql through \getenv:
# neither is ever on a command line or in the log.
set -eu
url="${ARON_DB_URL#jdbc:}"
for f in /seed/0*.sql; do
  echo "seed: $(basename "$f")"
  psql "$url" -X -q -v ON_ERROR_STOP=1 -f "$f"
done
# The smoke device's REAL public key (deploy.sh derives it from Key Vault aron-dev-smoke-device-key), so the slice smoke
# signs X-Device-Proof like an enrolled phone and no enrolment rule is relaxed (lead 2026-10-07). Only replaces the
# seed's placeholder (or the same key): a device row with any other real key is never touched.
if [ -n "${ARON_SMOKE_JWK:-}" ] && [ -n "${ARON_SMOKE_THUMB:-}" ]; then
  psql "$url" -X -q -v ON_ERROR_STOP=1 <<'SQL'
\getenv jwk ARON_SMOKE_JWK
\getenv tp ARON_SMOKE_THUMB
UPDATE app.device SET public_key_jwk = CAST(:'jwk' AS jsonb), public_key_thumbprint = :'tp'
 WHERE device_uuid = '00000000-0000-4000-8000-000000000001'
   AND public_key_thumbprint IN ('seed-dev-device-0001', :'tp');
-- N-027 gate (backend-core 2026-10-07): "enrolled" means the device row names its enrolment token. The smoke device is
-- recorded as enrolled through a single-use dev token (already used, random hash, never shown) for the seeded sr
-- release, ONLY when it carries the real smoke key above; no other device, no gate or config value is touched.
WITH dev AS (
  SELECT id FROM app.device WHERE device_uuid = '00000000-0000-4000-8000-000000000001'
     AND public_key_thumbprint = :'tp' AND enrolment_token_id IS NULL
), tok AS (
  INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, release_id,
                                   expires_at, created_by, note)
  SELECT sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'smoke0', 'sr', 'dev', 1, 1,
         (SELECT id FROM app.app_release WHERE flavour = 'sr' AND status = 'published' ORDER BY version_code DESC LIMIT 1),
         now() + interval '1 hour', (SELECT id FROM app.app_user WHERE username = 'admin1001'),
         'dev slice-smoke device, enrolled by the dev seed (its private key is in Key Vault aron-dev-smoke-device-key)'
    FROM dev
  RETURNING id
)
UPDATE app.device d SET enrolment_token_id = tok.id FROM tok, dev WHERE d.id = dev.id;
SQL
  echo "seed: smoke device key set"
fi
[ -n "${ARON_SEED_PASSWORD:-}" ] || { echo "seed: no ARON_SEED_PASSWORD, accounts keep their passwords"; exit 0; }
salt="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
ARON_SEED_HASH="$(printf '%s' "$ARON_SEED_PASSWORD" | argon2 "$salt" -id -t 2 -k 19456 -p 1 -l 32 -e)"
export ARON_SEED_HASH
psql "$url" -X -q -v ON_ERROR_STOP=1 <<'SQL'
\getenv h ARON_SEED_HASH
UPDATE app.app_user SET password_hash = :'h', password_changed_at = now() WHERE pilot AND password_hash IS NULL;
SQL
echo "seed: done"
