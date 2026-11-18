-- V0203 android-integrity slice 3B (D-OWNER-MDM-02 iii; number provisional like V0201/V0202, renumbered at landing): the signed grant is stored with the exemption row.
-- The grant is signed ONCE when it is made, so the bundle that carries it is the same bytes on every fetch (a fresh ECDSA signature per request would change the bundle every time).
-- Nullable: a row without a token (none exists outside tests) simply delivers nothing. Adds one column to a new, empty-in-production table: no rewrite, no data guard.
SET lock_timeout = '5s';

ALTER TABLE app.device_lab_exemption ADD COLUMN grant_token text;

COMMENT ON COLUMN app.device_lab_exemption.grant_token IS 'The server-signed grant (ECDSA P-256 over the device uuid, the API host, granted_at and expires_at), signed ONCE when the exemption is granted and stored so the bundle that carries it is the same bytes on every fetch. NULL delivers nothing. Not a secret: it only works on the one phone and environment it names and the phone verifies it with a public key.';
