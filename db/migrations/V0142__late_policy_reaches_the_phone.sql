-- V0142 the late-sale policy reaches the SR and AMO phones (TSO manual build; owner decision 2026-11-04, D-TSO-MAN-49,
-- D-TSO-MAN-31; build contract docs/requests/tso-manual-build-2026-10.md s10 R-W-11, R-W-22, R-W-23).
--
-- Owner decision (2026-11-04, binding): when a phone sells after the TSO's Final Submit, the sale is ACCEPTED AND FLAGGED,
-- never refused, and the SR and AMO phones' day lock stays OFF by default. quarantine and reject remain config switches;
-- when an admin sets one of them, the phone lock built by lane X4a applies exactly as built. D-TSO-MAN-49 ships
-- cfg.day.late_sync_after_final_policy = accept_and_flag (V0134) and IngestService falls back to accept_and_flag.
--
-- The phone decides whether its Final Submit lock applies from that one key (FinalSubmitLock.lockApplies: quarantine or
-- reject lock; accept_and_flag, an absent key or an unreadable value do not). V0006 registered the key with delivery
-- 'server', so the device bundle never carried it. This migration changes only that key's delivery to 'both': the bundle
-- and every config delta now carry it (ScopedConfig.deviceValues, ConfigDelta: delivery <> 'server'), the server keeps
-- reading it as before. Nothing else of the key changes (default, bounds, scope levels, risk class, editor permission).
-- No DDL: one row update; lock_timeout as every migration (squawk).

SET lock_timeout = '5s';

UPDATE app.cfg_key SET delivery = 'both', updated_at = now()
 WHERE key = 'cfg.day.late_sync_after_final_policy';
