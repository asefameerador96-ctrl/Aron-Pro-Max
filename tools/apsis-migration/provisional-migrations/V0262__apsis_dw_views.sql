-- V0262 (PROVISIONAL, lane M-APSIS1): read views over the archive in schema dw (the reports' home), a view that joins history to live
-- sales, and the grant map entries that keep every application role away from the history relations the sync path could reach.
-- Conventions of the stable dw views: data_origin then is_effective as the last two columns (DwViewContractTest), scope columns
-- (zone_id, territory_id, division_id, wing_id) on every view so the server-side scope rule can filter; rows whose geography could not
-- be resolved have NULL scope ids and are therefore visible to national roles only.
CREATE VIEW dw.v_hist_sales_line AS
  SELECT business_date, outlet_id, outlet_code, sku_id, sku_code, route_id, route_code, zone_id, territory_id, division_id, wing_id,
         user_id, user_code, qty_base, gross_mtk, is_zero_sale, vendor_deleted,
         'production'::app.data_origin AS data_origin, NOT vendor_deleted AS is_effective
    FROM apsis_hist.sales_line;
CREATE VIEW dw.v_hist_sales_day AS
  SELECT business_date, outlet_id, outlet_code, sku_id, route_id, zone_id, territory_id, division_id, wing_id,
         sum(qty_base)::bigint AS qty_base, sum(gross_mtk)::bigint AS gross_mtk, bool_and(is_zero_sale) AS is_zero_sale,
         'production'::app.data_origin AS data_origin, true AS is_effective
    FROM apsis_hist.sales_line WHERE NOT vendor_deleted
   GROUP BY business_date, outlet_id, outlet_code, sku_id, route_id, zone_id, territory_id, division_id, wing_id;
CREATE VIEW dw.v_hist_visit_geo AS
  SELECT business_date, outlet_id, outlet_code, route_id, zone_id, territory_id, division_id, wing_id, user_id, captured_at,
         lat, lng, accuracy_m, distance_m, geo_validated, 'production'::app.data_origin AS data_origin, true AS is_effective
    FROM apsis_hist.visit_geo;
CREATE VIEW dw.v_hist_attendance AS
  SELECT business_date, user_id, user_code, zone_id, territory_id, division_id, wing_id, check_in_at, check_out_at,
         'production'::app.data_origin AS data_origin, true AS is_effective
    FROM apsis_hist.attendance;
-- Live and history side by side, one row per business date, outlet, SKU: history up to apsis_ctl.setting 'history_until', live after it.
CREATE VIEW dw.v_sales_with_history AS
  SELECT l.business_date, l.outlet_id, l.route_id, l.sku_id, z.id AS zone_id, t.id AS territory_id, d.id AS division_id, d.wing_id AS wing_id,
         l.sold_qty_base::bigint AS qty_base, 'live'::text AS source, l.data_origin, l.is_effective
    FROM dw.v_outlet_sku_daily l
    LEFT JOIN app.route r ON r.id = l.route_id LEFT JOIN app.zone z ON z.id = r.zone_id
    LEFT JOIN app.territory t ON t.id = z.territory_id LEFT JOIN app.division d ON d.id = t.division_id
   WHERE l.business_date > (SELECT value::date FROM apsis_ctl.setting WHERE key = 'history_until')
  UNION ALL
  SELECT h.business_date, h.outlet_id, h.route_id, h.sku_id, h.zone_id, h.territory_id, h.division_id, h.wing_id,
         h.qty_base, 'apsis'::text, h.data_origin, h.is_effective
    FROM dw.v_hist_sales_day h
   WHERE h.business_date <= (SELECT value::date FROM apsis_ctl.setting WHERE key = 'history_until');

-- The scoped reader of history (lead ruling): a read-only role that sees ONLY these five views. The API's analytics read pool connects as a
-- login that is a member of hist_ro and applies the server-side scope filter on the scope columns; the sync code never holds that pool.
-- hist_ro is outside the five-role grant map, so apply_db_role_grants() neither grants nor revokes it.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hist_ro') THEN
    CREATE ROLE hist_ro NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
END $$;
COMMENT ON ROLE hist_ro IS 'Read-only reader of the Apsis history views (dw.v_hist_*, dw.v_sales_with_history). Used by the API analytics read pool, never by the sync path.';
GRANT USAGE ON SCHEMA dw TO hist_ro;
GRANT SELECT ON dw.v_hist_sales_line, dw.v_hist_sales_day, dw.v_hist_visit_geo, dw.v_hist_attendance, dw.v_sales_with_history TO hist_ro;

-- Column and view comments (the data dictionary).
DO $$
DECLARE v text; c record;
  txt jsonb := jsonb_build_object(
   'business_date','Asia/Dhaka business date of the sale, visit or attendance.', 'outlet_id','app.outlet id; NULL when the vendor outlet is not in our master.',
   'outlet_code','Vendor outlet code as the dump has it.', 'sku_id','app.sku id; NULL when not in our SKU list.', 'sku_code','Vendor SKU code.',
   'route_id','app.route id of the sale (vendor route, else the outlet route); NULL when unmatched.', 'route_code','Vendor route code.',
   'zone_id','Zone of the route as of the migration load (past moves are not kept); NULL when unresolved: visible to national roles only.',
   'territory_id','Territory of the zone as of the load; scope column.','division_id','Division of the territory as of the load; scope column.',
   'wing_id','Wing of the division as of the load; scope column.','user_id','app.app_user id of the field-force code; NULL when unmatched.',
   'user_code','Vendor field-force code.','qty_base','Quantity in base units (sticks, pieces, dozens).','gross_mtk','Amount in integer milli-taka.',
   'is_zero_sale','True for a zero sale (no SKU, or quantity and amount zero, or the vendor zero-sale flag).','vendor_deleted','The vendor marked the row deleted.',
   'captured_at','Capture time (UTC).','lat','Latitude.','lng','Longitude.','accuracy_m','Reported accuracy in metres.','distance_m','Vendor-computed distance to the outlet in metres.',
   'geo_validated','Vendor geo-validation flag.','check_in_at','Check-in time (UTC).','check_out_at','Check-out time (UTC).','source','live (this system) or apsis (migrated history).',
   'data_origin','Origin of the row (production for migrated history).','is_effective','False for a vendor-deleted row.');
BEGIN
  FOREACH v IN ARRAY ARRAY['v_hist_sales_line','v_hist_sales_day','v_hist_visit_geo','v_hist_attendance','v_sales_with_history'] LOOP
    FOR c IN SELECT a.attname FROM pg_attribute a WHERE a.attrelid = ('dw.' || v)::regclass AND a.attnum > 0 AND NOT a.attisdropped LOOP
      EXECUTE format('COMMENT ON COLUMN dw.%I.%I IS %L', v, c.attname, txt ->> c.attname);
    END LOOP;
  END LOOP;
END $$;
COMMENT ON VIEW dw.v_hist_sales_line IS 'Apsis history: sales lines by outlet, SKU and day (base units, milli-taka). Migrated archive, not live data.';
COMMENT ON VIEW dw.v_hist_sales_day IS 'Apsis history: sales per outlet, SKU and day (vendor duplicates summed, deleted rows left out).';
COMMENT ON VIEW dw.v_hist_visit_geo IS 'Apsis history: visit geo rows by outlet and day. Exact coordinates: not granted to web_ro or bi_reader (decided on purpose, like fact_geo_fix).';
COMMENT ON VIEW dw.v_hist_attendance IS 'Apsis history: field-force attendance (no device data). Not granted to web_ro or bi_reader (like fact_attendance).';
COMMENT ON VIEW dw.v_sales_with_history IS 'Sales per business date, outlet and SKU across the cutover: archive up to apsis_ctl.setting history_until, live after it.';

-- The grant map is the project's single source of grants (app.apply_db_role_grants): add the history relations to the except lists of
-- every role row on schema dw, so a routine refresh can never open them to the sync roles (api_rw, worker_rw, jobs_rw). web_ro and
-- bi_reader keep the aggregate views and lose the two views with exact coordinates and per-person attendance.
UPDATE app.db_role_grant SET except_tables = except_tables ||
  ARRAY(SELECT x FROM unnest(ARRAY['v_hist_sales_line','v_hist_sales_day','v_hist_visit_geo','v_hist_attendance','v_sales_with_history']) x WHERE x <> ALL (except_tables))
 WHERE schema_name = 'dw' AND object = '*' AND role IN ('api_rw','worker_rw');
UPDATE app.db_role_grant SET except_tables = except_tables ||
  ARRAY(SELECT x FROM unnest(ARRAY['v_hist_visit_geo','v_hist_attendance']) x WHERE x <> ALL (except_tables))
 WHERE schema_name = 'dw' AND object IN ('*', 'v_*') AND role IN ('web_ro','bi_reader');     -- the real map grants these two roles 'v_*' (all views)
SELECT app.apply_db_role_grants();
