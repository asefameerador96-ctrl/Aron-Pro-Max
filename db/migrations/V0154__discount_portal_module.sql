-- V0154 discount portal, train 2 (lane portal-discount-api; D-DISC-API-03; brief s6 and s8 Q4).
--
-- 1. New admin-portal module `discounts` (its own permission module, moved off `targets` in AdminModules.kt). The
--    app.user_module_grant.module CHECK gains it (NOT VALID here, VALIDATE in V0155: separate transaction, squawk rule).
--    Today's reach is kept: /v1/admin/offers/** sat under `targets`, so every ADMIN gets `discounts` with the access they
--    hold on `targets` (r stays r, w stays w); a later narrowing in the grants screen stays (idempotent).
-- 2. app.offer_version.request_fingerprint: SHA-256 of the normalised create/update request, so a replay of the same
--    request_uuid with different content is told apart (409) from a true replay (the first result again).
-- Nothing in the field changes (memo offer line zero, bundle offers empty).

SET lock_timeout = '5s';

ALTER TABLE app.user_module_grant DROP CONSTRAINT user_module_grant_module_check;   -- widened below: every old value stays allowed
ALTER TABLE app.user_module_grant ADD CONSTRAINT user_module_grant_module_check CHECK (module IN (
  'masterdata', 'routes', 'outlets', 'products', 'prices', 'users', 'targets', 'credentials', 'mdm', 'reports', 'config',
  'data_entry', 'indent', 'leaderboard', 'daily_tracking', 'support', 'discounts')) NOT VALID;
COMMENT ON COLUMN app.user_module_grant.module IS 'Admin-portal module (contract AdminModule): masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, data_entry, indent, leaderboard, daily_tracking, support (the support desk, V0135) or discounts (the discount-programme portal, V0154).';

INSERT INTO app.user_module_grant (user_id, module, access)
SELECT g.user_id, 'discounts', g.access FROM app.user_module_grant g WHERE g.module = 'targets'
ON CONFLICT (user_id, module) DO NOTHING;

ALTER TABLE app.offer_version ADD COLUMN request_fingerprint text;
COMMENT ON COLUMN app.offer_version.request_fingerprint IS 'SHA-256 (hex) of the normalised portal request that made this version; with request_uuid it tells a true replay from a reused UUID. Null for versions not made by the portal.';

