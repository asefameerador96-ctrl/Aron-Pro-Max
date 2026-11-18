-- V0233 (PROVISIONAL number, lane sr-targets-server; the lead renumbers at landing, never run outside lane CI before that)
-- dw view contract 1.9 -> 1.10 (PROVISIONAL minor; the lead assigns the final number in the contract order): reuse views of the SR target tables of V0230..V0232
-- (D-TKPI-05, D-TKPI-07, D-TKPI-08). Five new views, nothing existing changes: names instead of ids, data_origin of the route, is_effective = true last.
-- Request to the lead: docs/requests/dw-view-contract-sr-targets.md (the hash line of DwViewContractTest and the version of dw.view_contract_version()).
SET lock_timeout = '5s';

CREATE VIEW dw.v_route_brand_kpi_target AS
SELECT t.month, t.route_id, t.brand_id, b.code AS brand_code, b.name AS brand_name, t.memo_target, t.kpi_alloc_mtk, t.batch_uuid, t.updated_at, r.data_origin, true AS is_effective
  FROM app.route_brand_kpi_target t JOIN app.product_node b ON b.id = t.brand_id JOIN app.route r ON r.id = t.route_id;

CREATE VIEW dw.v_route_kpi_month AS
SELECT t.month, t.route_id, t.kpi_alloc_mtk, t.cpr_target_bp, t.cpr_alloc_mtk, t.batch_uuid, t.updated_at, r.data_origin, true AS is_effective
  FROM app.route_kpi_month t JOIN app.route r ON r.id = t.route_id;

CREATE VIEW dw.v_route_programme_target AS
SELECT t.month, t.route_id, t.programme_id, g.code AS programme_code, t.target_qty, t.batch_uuid, t.updated_at, r.data_origin, true AS is_effective
  FROM app.route_programme_target t JOIN app.programme g ON g.id = t.programme_id JOIN app.route r ON r.id = t.route_id;

CREATE VIEW dw.v_outlet_campaign_target AS
SELECT t.month, t.outlet_id, o.code AS outlet_code, o.route_id, t.programme_id, g.code AS programme_code, t.std_target, t.memo_target, t.batch_uuid, t.updated_at, r.data_origin, true AS is_effective
  FROM app.outlet_campaign_target t JOIN app.programme g ON g.id = t.programme_id JOIN app.outlet o ON o.id = t.outlet_id JOIN app.route r ON r.id = o.route_id;

CREATE VIEW dw.v_outlet_campaign_sku_target AS
SELECT t.month, t.outlet_id, o.code AS outlet_code, o.route_id, t.programme_id, g.code AS programme_code, t.sku_id, s.code AS sku_code, t.qty_base, t.batch_uuid, t.updated_at, r.data_origin, true AS is_effective
  FROM app.outlet_campaign_sku_target t JOIN app.programme g ON g.id = t.programme_id JOIN app.outlet o ON o.id = t.outlet_id JOIN app.sku s ON s.id = t.sku_id JOIN app.route r ON r.id = o.route_id;

COMMENT ON VIEW dw.v_route_brand_kpi_target IS 'Stable view: memo target and allocated KPI per route, brand and month with the brand code and name (N-5).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.month IS 'Target month (first day).';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.brand_id IS 'Brand (app.product_node).';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.brand_code IS 'Brand code; null when the brand has none.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.brand_name IS 'Brand English name.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.memo_target IS 'Memo target of the month.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.kpi_alloc_mtk IS 'Allocated KPI in milli-taka.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.batch_uuid IS 'Upload batch that last wrote the row.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.updated_at IS 'UTC instant of the last write.';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.data_origin IS 'Origin of the route (app.route.data_origin).';
COMMENT ON COLUMN dw.v_route_brand_kpi_target.is_effective IS 'True: every row counts in an ordinary total.';

COMMENT ON VIEW dw.v_route_kpi_month IS 'Stable view: month card of the SR KPI dashboard per route (allocated KPI, CPR target) (N-5).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_route_kpi_month.month IS 'Target month (first day).';
COMMENT ON COLUMN dw.v_route_kpi_month.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.v_route_kpi_month.kpi_alloc_mtk IS 'Allocated KPI in milli-taka.';
COMMENT ON COLUMN dw.v_route_kpi_month.cpr_target_bp IS 'CPR target in basis points.';
COMMENT ON COLUMN dw.v_route_kpi_month.cpr_alloc_mtk IS 'KPI money allocated to CPR in milli-taka.';
COMMENT ON COLUMN dw.v_route_kpi_month.batch_uuid IS 'Upload batch that last wrote the row.';
COMMENT ON COLUMN dw.v_route_kpi_month.updated_at IS 'UTC instant of the last write.';
COMMENT ON COLUMN dw.v_route_kpi_month.data_origin IS 'Origin of the route (app.route.data_origin).';
COMMENT ON COLUMN dw.v_route_kpi_month.is_effective IS 'True: every row counts in an ordinary total.';

COMMENT ON VIEW dw.v_route_programme_target IS 'Stable view: monthly target of a route for a programme (Match Box Lifting Star) in the programme unit (N-8).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_route_programme_target.month IS 'Target month (first day).';
COMMENT ON COLUMN dw.v_route_programme_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.v_route_programme_target.programme_id IS 'Programme (app.programme).';
COMMENT ON COLUMN dw.v_route_programme_target.programme_code IS 'Programme code.';
COMMENT ON COLUMN dw.v_route_programme_target.target_qty IS 'Target in the programme unit, three decimals.';
COMMENT ON COLUMN dw.v_route_programme_target.batch_uuid IS 'Upload batch that last wrote the row.';
COMMENT ON COLUMN dw.v_route_programme_target.updated_at IS 'UTC instant of the last write.';
COMMENT ON COLUMN dw.v_route_programme_target.data_origin IS 'Origin of the route (app.route.data_origin).';
COMMENT ON COLUMN dw.v_route_programme_target.is_effective IS 'True: every row counts in an ordinary total.';

COMMENT ON VIEW dw.v_outlet_campaign_target IS 'Stable view: campaign STD and memo target per outlet and month; the row is the outlet''s membership of the campaign (N-3).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_outlet_campaign_target.month IS 'Target month (first day).';
COMMENT ON COLUMN dw.v_outlet_campaign_target.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_outlet_campaign_target.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.route_id IS 'The outlet''s current route.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.programme_id IS 'Campaign programme (app.programme).';
COMMENT ON COLUMN dw.v_outlet_campaign_target.programme_code IS 'Programme code.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.std_target IS 'STD target of the month, three decimals.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.memo_target IS 'Memo target of the month.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.batch_uuid IS 'Upload batch that last wrote the row.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.updated_at IS 'UTC instant of the last write.';
COMMENT ON COLUMN dw.v_outlet_campaign_target.data_origin IS 'Origin of the route (app.route.data_origin).';
COMMENT ON COLUMN dw.v_outlet_campaign_target.is_effective IS 'True: every row counts in an ordinary total.';

COMMENT ON VIEW dw.v_outlet_campaign_sku_target IS 'Stable view: campaign SKU target per outlet and month in base units (N-3).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.month IS 'Target month (first day).';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.route_id IS 'The outlet''s current route.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.programme_id IS 'Campaign programme (app.programme).';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.programme_code IS 'Programme code.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.sku_code IS 'SKU code.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.qty_base IS 'Target quantity in the SKU base unit.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.batch_uuid IS 'Upload batch that last wrote the row.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.updated_at IS 'UTC instant of the last write.';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.data_origin IS 'Origin of the route (app.route.data_origin).';
COMMENT ON COLUMN dw.v_outlet_campaign_sku_target.is_effective IS 'True: every row counts in an ordinary total.';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.10' $$;

SELECT app.apply_db_role_grants();
