-- V0263 (PROVISIONAL, lane M-APSIS1): exactly what the job role may read in the live schema. Column-limited SELECT on the masters it matches
-- against; no INSERT/UPDATE/DELETE anywhere in app (the optional coordinate fill is a separate, time-boxed grant below); nothing on
-- transactions (memo, visit, due_ledger ...), nothing on credentials. app.apply_db_role_grants() covers only the five application roles, so
-- it neither grants nor revokes these.
GRANT SELECT (id, code, route_id, zone_id, lat, lng, location_basis, location_confirmed) ON app.outlet TO apsis_migrator;
GRANT SELECT (id, code, zone_id) ON app.route TO apsis_migrator;
GRANT SELECT (id, territory_id) ON app.zone TO apsis_migrator;
GRANT SELECT (id, division_id) ON app.territory TO apsis_migrator;
GRANT SELECT (id, wing_id) ON app.division TO apsis_migrator;
GRANT SELECT (id, code, base_per_pack) ON app.sku TO apsis_migrator;
GRANT SELECT (id, username, employee_code, home_zone_id) ON app.app_user TO apsis_migrator;
GRANT EXECUTE ON FUNCTION app.dhaka_date(timestamptz) TO apsis_migrator;

-- The coordinate fill is the tool's only write to a live master. The migrate role opens it for the run and closes it after (runbook).
CREATE FUNCTION apsis_ctl.allow_fill_locations() RETURNS void LANGUAGE sql AS
  $$ GRANT UPDATE (lat, lng, location_basis, location_confirmed) ON app.outlet TO apsis_migrator $$;
CREATE FUNCTION apsis_ctl.disallow_fill_locations() RETURNS void LANGUAGE sql AS
  $$ REVOKE UPDATE (lat, lng, location_basis, location_confirmed) ON app.outlet FROM apsis_migrator $$;
REVOKE ALL ON FUNCTION apsis_ctl.allow_fill_locations(), apsis_ctl.disallow_fill_locations() FROM PUBLIC;
