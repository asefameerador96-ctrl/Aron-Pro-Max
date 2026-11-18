-- V0202 android-integrity slice 3B (D-OWNER-MDM-02 iii; number provisional until the lead confirms the landing order).
-- A lab exemption lets one enrolled phone run with Developer options on for a short time (the ADB device walks). The row is only the
-- server's record; the phone honours it solely through a grant the server SIGNS over the device uuid and the expiry (the private key is a
-- server environment secret, never in this repository), so nothing stored on a phone can create or extend one. At most 14 days.
SET lock_timeout = '5s';

CREATE TABLE app.device_lab_exemption (
  device_id  bigint PRIMARY KEY REFERENCES app.device (id),
  granted_by bigint NOT NULL REFERENCES app.app_user (id),
  granted_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  note       text CHECK (note IS NULL OR length(note) <= 300),
  grant_token text NOT NULL CHECK (length(grant_token) BETWEEN 40 AND 600),
  CHECK (expires_at > granted_at AND expires_at <= granted_at + interval '14 days')
);

COMMENT ON TABLE app.device_lab_exemption IS 'The lab exemption of one enrolled phone (D-OWNER-MDM-02): while unexpired the server hands the phone a signed grant that lets it run with Developer options on; one row per device, at most 14 days.
owner: backend:analytics | capture: ONLINE | retention: audit | pii: none';
COMMENT ON COLUMN app.device_lab_exemption.device_id IS 'The enrolled phone the exemption is for; one row per device (a new grant replaces the row).';
COMMENT ON COLUMN app.device_lab_exemption.granted_by IS 'The portal user (Super Admin or an Admin with the mdm module write grant) who granted it.';
COMMENT ON COLUMN app.device_lab_exemption.granted_at IS 'UTC time the exemption was granted.';
COMMENT ON COLUMN app.device_lab_exemption.expires_at IS 'UTC time the exemption ends; at most 14 days after granted_at. The signed grant carries the same time, and the phone also caps it.';
COMMENT ON COLUMN app.device_lab_exemption.grant_token IS 'The server-signed grant (ECDSA P-256 over the device uuid, granted_at and expires_at), signed ONCE when the exemption is granted and stored so the bundle that carries it is the same bytes on every fetch (a fresh signature each time would change the bundle every time). Not a secret: it only works on the one phone it names and the phone verifies it with a public key.';
COMMENT ON COLUMN app.device_lab_exemption.note IS 'Why the phone needs it (for example the walk it is for); free text, at most 300 characters.';

-- A new app table is covered by the role grant map only after this call (DbRolesTest: every app table is in the least-privilege map).
SELECT app.apply_db_role_grants();
