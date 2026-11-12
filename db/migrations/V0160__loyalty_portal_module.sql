-- V0160 loyalty portal, admin module (lane portal-loyalty-api; D-LOY-API-01; brief s6 and s10 lead decisions).
-- New admin-portal module `loyalty` (its own permission module, moved off `targets` in AdminModules.kt for the programme,
-- gift and enrolment paths). The app.user_module_grant.module CHECK gains it (NOT VALID here, VALIDATE in V0161).
-- Today's reach is kept: /v1/admin/programmes/**, /gifts/** sat under `targets`, so every ADMIN gets `loyalty` with the
-- access they hold on `targets` (r stays r, w stays w); a later narrowing in the grants screen stays (idempotent).
SET lock_timeout = '5s';

ALTER TABLE app.user_module_grant DROP CONSTRAINT user_module_grant_module_check;   -- widened below: every old value stays allowed
ALTER TABLE app.user_module_grant ADD CONSTRAINT user_module_grant_module_check CHECK (module IN (
  'masterdata', 'routes', 'outlets', 'products', 'prices', 'users', 'targets', 'credentials', 'mdm', 'reports', 'config',
  'data_entry', 'indent', 'leaderboard', 'daily_tracking', 'support', 'discounts', 'loyalty')) NOT VALID;
COMMENT ON COLUMN app.user_module_grant.module IS 'Admin-portal module (contract AdminModule): masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, data_entry, indent, leaderboard, daily_tracking, support (the support desk, V0135), discounts (the discount-programme portal, V0154) or loyalty (the loyalty-programme portal, V0160).';

INSERT INTO app.user_module_grant (user_id, module, access)
SELECT g.user_id, 'loyalty', g.access FROM app.user_module_grant g WHERE g.module = 'targets'
ON CONFLICT (user_id, module) DO NOTHING;
