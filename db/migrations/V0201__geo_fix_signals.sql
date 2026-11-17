-- V0201 android-integrity slice 3C (contract 1.19.0, D-OWNER-MDM-02; migration number provisional until the lead confirms the landing order).
-- The phone's anti-spoofing codes ride on every GeoFix as `signals`; the server keeps them next to the fix so the risk rules can read them.
-- One nullable column (no default, so no rewrite): NULL = the phone is older than 1.19.0 and sent none (unknown, not clean).
SET lock_timeout = '5s';

ALTER TABLE app.geo_fix ADD COLUMN IF NOT EXISTS signals text[];

COMMENT ON COLUMN app.geo_fix.signals IS 'Anti-spoofing codes the phone found for this fix (contract 1.19.0 GeoFix.signals): hard mock_flag, mock_app_selected, test_provider; soft test_provider_props, fake_gps_app_installed, gnss_no_sats, gnss_none, fix_stale, fix_clock_mismatch, jump, speed, provider_disagree, root_hint, emulator, hook_framework. NULL = an older phone sent none (unknown, not clean); an empty array = the phone checked and found nothing.';
COMMENT ON COLUMN app.geo_fix.is_mock IS 'True when the phone treated the fix as mocked: the platform mock flag OR an inferred hard code (a selected mock-location app, a test provider; D-AINT-06). The codes in signals tell the two apart; a fix from a phone older than contract 1.19.0 carries only this flag. A mocked fix is never geo-valid.';
