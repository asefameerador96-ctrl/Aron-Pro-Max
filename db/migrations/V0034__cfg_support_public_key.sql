-- V0034 registry key cfg.support.public_key_spki (lead ruling 2026-10-07, docs/requests/android-sys-support-key.md): the
-- public key the phone encrypts its PDA-to-Support export with (base64 of the X.509 SubjectPublicKeyInfo DER). Global,
-- delivered to the device, risk class 2; the default empty string means "not set up" (the phone does not offer the export).
-- Only the public half lives here; the private key stays with Support, never in the database.

SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.support.public_key_spki', 'support', 'S', 'text', '""'::jsonb, '{}'::jsonb,
   'empty, or base64 of an X.509 SubjectPublicKeyInfo (DER)', ARRAY['global']::text[], 2, NULL, 'B', 'device', false, false,
   'cfg.edit.security', 'Public key (base64 SubjectPublicKeyInfo) the phone encrypts its support export with; empty = not set up.');
