-- Dev/test seed (backend-authz, D-ROLES-02, docs/34 s1): the seeded ADMIN accounts hold every module at `w`, as today's
-- admins do after migration B of docs/requests/db-backend-authz-roles.md (whose own seed only reaches admins that exist
-- when it runs; this seed loads after it on a fresh database). Only while the table exists (before db's migration it is a
-- no-op) and only for a seeded admin with no grant row yet, so a narrowed grant stays narrowed; idempotent.
DO $$
BEGIN
  IF to_regclass('app.user_module_grant') IS NOT NULL THEN
    INSERT INTO app.user_module_grant (user_id, module, access)
    SELECT u.id, m.module, 'w'
      FROM app.app_user u
     CROSS JOIN (VALUES ('masterdata'),('routes'),('outlets'),('products'),('prices'),('users'),('targets'),('credentials'),
                        ('mdm'),('reports'),('config'),('data_entry'),('indent'),('leaderboard'),('daily_tracking')) AS m(module)
     WHERE u.role = 'ADMIN' AND u.pilot
       AND NOT EXISTS (SELECT 1 FROM app.user_module_grant g WHERE g.user_id = u.id)
    ON CONFLICT (user_id, module) DO NOTHING;
  END IF;
END $$;
