-- V0114 risk code CHECKOUT_WITHOUT_SUBMIT (lead row 2026-10-24, D-NIGHT-CHECKOUT-01; shape in
-- docs/requests/contract-v1.9-shapes-backend-core.md row 5, backend-core #18): an SR's or AMO's check-out is stored and
-- the user has no effective day_submit for that business date (subject the user, one per user-day). backend-core raises
-- it with severity 2 (the row's own column); its integrity weight is 20. A flag for review, never a block.
-- 1. app.risk_signal.risk_signal_code_check: the V0077 list with CHECKOUT_WITHOUT_SUBMIT appended. Nothing is removed,
--    so every existing row stays valid; replaced NOT VALID here and validated in V0115 (separate transaction, squawk
--    rule; the V0077/V0078 pattern).
-- 2. cfg.geo.integrity_weight: the registry default gains {"CHECKOUT_WITHOUT_SUBMIT": 20} (guarded, as V0077). A saved
--    cfg_value of that key keeps its own map; the server's weight lookup falls back for a missing code.

SET lock_timeout = '5s';

ALTER TABLE app.risk_signal DROP CONSTRAINT risk_signal_code_check;
ALTER TABLE app.risk_signal ADD CONSTRAINT risk_signal_code_check
  CHECK (code IN ('GEO_MOCK', 'GEO_TELEPORT', 'GEO_ZERO_JITTER', 'GEO_PERFECT_ACCURACY', 'GEO_SAME_POINT',
                  'GEO_ROUTE_SINGLE_POINT', 'GEO_STALE_FIX', 'GEO_GNSS_INCONSISTENT', 'GEO_GNSS_TIME_SKEW',
                  'GEO_DEVICE_SERVER_MISMATCH', 'GEO_SHORT_VISIT_GAPS', 'DEVICE_NOT_OWNER', 'DEVICE_INTEGRITY_FAIL',
                  'DEVICE_DEBUG_ENABLED', 'DEVICE_MOCK_APP_PRESENT', 'DEVICE_POLICY_DRIFT', 'CLOCK_SKEW',
                  'GEO_OUT_OF_BOUNDS', 'CONFIG_STAMP_REGRESS', 'MEMO_DUPLICATE_SUSPECT', 'CHECKOUT_WITHOUT_SUBMIT')) NOT VALID;
UPDATE app.cfg_key SET default_value = default_value || '{"CHECKOUT_WITHOUT_SUBMIT": 20}'::jsonb, updated_at = now()
 WHERE key = 'cfg.geo.integrity_weight' AND NOT default_value ? 'CHECKOUT_WITHOUT_SUBMIT';

COMMENT ON COLUMN app.risk_signal.code IS 'Risk rule that raised the signal (GEO_*, DEVICE_*, CLOCK_SKEW, CONFIG_STAMP_REGRESS, MEMO_DUPLICATE_SUSPECT, CHECKOUT_WITHOUT_SUBMIT from V0114); weights in cfg.geo.integrity_weight.';
