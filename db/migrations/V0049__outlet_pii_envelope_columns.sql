-- V0049 AUD-DA-05 (docs/16 s4.1 M-16, D-107, D-256, docs/21 s4.3 option B): NID, TIN and trade licence become
-- envelope-encrypted bytea columns, and app.pii_key holds the wrapped data-encryption keys.
--   * app.outlet.nid, tin, trade_license (V0004, plain text) are replaced by nid_enc, tin_enc, trade_license_enc bytea
--     plus pii_key_id. Ciphertext layout (docs/21 s4.3): key_id (2 bytes) + nonce (12) + ciphertext + GCM tag (16),
--     AES-256-GCM, AAD = outlet_id || column name. They are never searched and never in bundles or dw (D-107).
--   * The old text columns are dropped, not renamed in place: nothing reads or writes them (no API field, no bundle
--     column, no backend SQL; docs/16: "nothing is built on these columns"), and a text-to-bytea rename would keep
--     plaintext semantics in the name's history. The migration refuses to run if any row holds a value, so no data
--     can be lost; the importer treats the NID placeholder 123 as null (D-256), so a legacy load never fills them.
--   * cfg.bundle.outlet_fields names the new columns in its SR exclude list.
-- The audit-writer redaction (store changed field names plus a mask, never the raw value) belongs to backend-admin and
-- backend-core: docs/requests/db-audit-pii-redaction.md.

SET lock_timeout = '5s';

-- Take the lock the DROP COLUMN needs anyway before the check, so no value can be written in between.
LOCK TABLE app.outlet IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM app.outlet WHERE nid IS NOT NULL OR tin IS NOT NULL OR trade_license IS NOT NULL) THEN
    RAISE EXCEPTION 'V0049: app.outlet holds NID, TIN or trade licence values; encrypt them into the *_enc columns first'
      USING ERRCODE = 'P0001';
  END IF;
END $$;

CREATE TABLE app.pii_key (
  key_id          smallint PRIMARY KEY CHECK (key_id > 0),           -- the first 2 bytes of every ciphertext
  wrapped_dek     bytea NOT NULL CHECK (length(wrapped_dek) BETWEEN 32 AND 1024),
  kv_key_name     text NOT NULL CHECK (kv_key_name ~ '^[0-9A-Za-z-]{1,127}$'),
  kv_key_version  text NOT NULL CHECK (length(kv_key_version) BETWEEN 1 AND 64),
  algorithm       text NOT NULL DEFAULT 'AES-256-GCM' CHECK (algorithm = 'AES-256-GCM'),
  created_at      timestamptz NOT NULL DEFAULT now(),
  rewrapped_at    timestamptz,
  retired_at      timestamptz,
  CHECK (retired_at IS NULL OR retired_at >= created_at)
);
-- One key encrypts new values at a time; retired keys stay to decrypt older ciphertext.
CREATE UNIQUE INDEX pii_key_one_active ON app.pii_key ((true)) WHERE retired_at IS NULL;

-- A key is never deleted (its ciphertext would become unreadable) and its id and algorithm never change. A Key Vault
-- rotation re-wraps the same DEK (wrapped_dek, kv_key_version, rewrapped_at change); retiring is one-way.
CREATE FUNCTION app.pii_key_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'app.pii_key rows are never deleted' USING ERRCODE = '42501';
  END IF;
  IF NEW.key_id <> OLD.key_id OR NEW.algorithm <> OLD.algorithm OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'app.pii_key: key_id, algorithm and created_at are immutable' USING ERRCODE = '42501';
  END IF;
  IF OLD.retired_at IS NOT NULL AND NEW.retired_at IS DISTINCT FROM OLD.retired_at THEN
    RAISE EXCEPTION 'app.pii_key: a retired key stays retired' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER pii_key_guard BEFORE UPDATE OR DELETE ON app.pii_key
  FOR EACH ROW EXECUTE FUNCTION app.pii_key_guard();

-- squawk-ignore ban-drop-column
ALTER TABLE app.outlet DROP COLUMN nid;
-- squawk-ignore ban-drop-column
ALTER TABLE app.outlet DROP COLUMN tin;
-- squawk-ignore ban-drop-column
ALTER TABLE app.outlet DROP COLUMN trade_license;
ALTER TABLE app.outlet ADD COLUMN nid_enc bytea;
ALTER TABLE app.outlet ADD COLUMN tin_enc bytea;
ALTER TABLE app.outlet ADD COLUMN trade_license_enc bytea;
ALTER TABLE app.outlet ADD COLUMN pii_key_id smallint;
-- Every row is NULL in the new columns: NOT VALID skips the scan under lock; V0050 validates.
ALTER TABLE app.outlet ADD CONSTRAINT outlet_pii_key_id_fkey FOREIGN KEY (pii_key_id) REFERENCES app.pii_key(key_id) NOT VALID;
ALTER TABLE app.outlet ADD CONSTRAINT outlet_pii_enc_shape CHECK (
      (nid_enc IS NULL OR length(nid_enc) >= 31)
  AND (tin_enc IS NULL OR length(tin_enc) >= 31)
  AND (trade_license_enc IS NULL OR length(trade_license_enc) >= 31)
  AND ((pii_key_id IS NULL) = (nid_enc IS NULL AND tin_enc IS NULL AND trade_license_enc IS NULL))) NOT VALID;

UPDATE app.cfg_key
   SET default_value = '{"SR": {"exclude": ["nid_enc", "tin_enc", "trade_license_enc", "pii_key_id"]}}'::jsonb,
       updated_at = now()
 WHERE key = 'cfg.bundle.outlet_fields';

-- Grants: the API encrypts, decrypts and re-wraps (its V0014 'app.*' SELECT, INSERT and '*/update' rows cover the new
-- table; no DELETE); the worker and every read-only role never see the wrapped keys (jobs_rw has no 'app.*' row of
-- its own and reads through worker_rw, so the jobs_rw part of the UPDATE below matches nothing today).
UPDATE app.db_role_grant SET except_tables = except_tables || '{pii_key}'::text[]
 WHERE role IN ('worker_rw', 'jobs_rw') AND schema_name = 'app' AND object = '*' AND NOT ('pii_key' = ANY (except_tables));
SELECT app.apply_db_role_grants();

COMMENT ON TABLE app.pii_key IS 'One data-encryption key for outlet NID, TIN and trade licence, stored only wrapped by a Key Vault key (envelope encryption, D-107); never deleted.
owner: backend:masterdata | capture: SERVER | retention: master | pii: secret';
COMMENT ON COLUMN app.pii_key.key_id IS 'Key number; the first two bytes of every ciphertext name it.';
COMMENT ON COLUMN app.pii_key.wrapped_dek IS '[pii:secret] AES-256 data-encryption key wrapped (encrypted) by the Key Vault key; never stored unwrapped.';
COMMENT ON COLUMN app.pii_key.kv_key_name IS 'Name of the Key Vault key that wraps the DEK.';
COMMENT ON COLUMN app.pii_key.kv_key_version IS 'Key Vault key version used for the current wrap; changes when a rotation re-wraps.';
COMMENT ON COLUMN app.pii_key.algorithm IS 'Cipher of the data key: AES-256-GCM.';
COMMENT ON COLUMN app.pii_key.created_at IS 'UTC instant the key was created.';
COMMENT ON COLUMN app.pii_key.rewrapped_at IS 'UTC instant of the last re-wrap by a Key Vault rotation; null if never re-wrapped.';
COMMENT ON COLUMN app.pii_key.retired_at IS 'UTC instant the key stopped encrypting new values; it still decrypts older ciphertext. Null = the active key.';
COMMENT ON COLUMN app.outlet.nid_enc IS '[pii:sensitive] National ID of the outlet owner, envelope-encrypted (key_id + nonce + AES-256-GCM ciphertext + tag); never searched, never in bundles or dw.';
COMMENT ON COLUMN app.outlet.tin_enc IS '[pii:sensitive] Tax identification number, envelope-encrypted like nid_enc.';
COMMENT ON COLUMN app.outlet.trade_license_enc IS '[pii:sensitive] Trade licence number, envelope-encrypted like nid_enc.';
COMMENT ON COLUMN app.outlet.pii_key_id IS 'app.pii_key that encrypted this row''s *_enc values; null when none is set.';
