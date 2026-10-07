-- V0015 the stable read-only dw views other products use (docs/31 s3 item 5): BI, the BOD dashboard, the later
-- indent, discount and target engines and a lakehouse export read these views (and the domain-event outbox), never
-- app tables. A view's name and columns are a contract: they change only additively (new columns at the end, new
-- views); a breaking change ships as a new view (v2) next to the old one. Every column is documented in
-- docs/data-dictionary.md. Money is integer milli-taka (*_mtk), quantities integer SKU base units (*_qty_base),
-- instants UTC, business_date the Asia/Dhaka date. Percentages are 0..100 with 2 decimals, null when the denominator
-- is 0 (docs/24 s12.4).

-- Attendance had no dw fact; the worker fills one row per user and business date from attendance_event.
CREATE TABLE dw.fact_attendance (
  business_date        date NOT NULL,
  user_id              bigint NOT NULL,
  role                 text NOT NULL,
  zone_id              bigint,
  check_in_at          timestamptz,
  check_in_lat         double precision,
  check_in_lng         double precision,
  check_in_accuracy_m  double precision,
  check_in_is_mock     boolean,
  check_out_at         timestamptz,
  check_out_lat        double precision,
  check_out_lng        double precision,
  check_out_accuracy_m double precision,
  check_out_is_mock    boolean,
  last_event_id        bigint NOT NULL DEFAULT 0,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, user_id)
);
CREATE INDEX ON dw.fact_attendance (zone_id, business_date);

CREATE FUNCTION dw.pct(p_num numeric, p_den numeric) RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT CASE WHEN p_den IS NULL OR p_den = 0 THEN NULL ELSE round(100 * p_num / p_den, 2) END $$;

CREATE VIEW dw.v_daily_route AS
SELECT a.business_date, a.route_id, g.route_code, g.route_name, g.visit_kind,
       a.zone_id, g.zone_name, g.territory_id, g.territory_name, g.division_id, g.division_name, g.wing_id, g.wing_name,
       a.planned, a.exception_approved, a.day_state, a.logged_in_at, a.sales_submitted_at, a.final_submitted_at,
       a.target_outlets, a.visited_outlets, a.successful_calls, dw.pct(a.successful_calls, a.target_outlets) AS strike_rate_pct,
       a.visits, a.geo_valid_visits, dw.pct(a.geo_valid_visits, a.visits) AS geo_valid_pct,
       a.force_sale_visits, a.mock_visits, a.suspicious_visits,
       a.active_memo_count, a.gross_mtk, a.offer_discount_mtk, a.drp_discount_mtk, a.qc_deduction_mtk, a.net_mtk,
       a.paid_mtk, a.due_mtk, a.dues_collected_mtk, a.late_rows_after_final, a.updated_at
  FROM dw.agg_daily_route a
  LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE VIEW dw.v_daily_sr AS
WITH v AS (
  SELECT business_date, user_id,
         count(*) AS visits,
         count(DISTINCT outlet_id) FILTER (WHERE outcome_code IS DISTINCT FROM 'abandoned' AND call_declined IS NOT TRUE) AS visited_outlets,
         count(*) FILTER (WHERE server_verdict = 'in_range') AS geo_valid_visits,
         count(*) FILTER (WHERE geo_action = 'force_sale') AS force_sale_visits,
         count(*) FILTER (WHERE is_mock) AS mock_visits,
         array_agg(DISTINCT route_id) FILTER (WHERE route_id IS NOT NULL) AS route_ids,
         min(opened_at) AS first_visit_at, max(coalesce(ended_at, opened_at)) AS last_visit_end_at
    FROM dw.fact_visit WHERE NOT voided AND visit_kind = 'sr_call'
   GROUP BY business_date, user_id),
m AS (
  SELECT business_date, user_id,
         count(*) FILTER (WHERE status = 'active' AND line_count > 0) AS active_memo_count,
         count(DISTINCT outlet_id) FILTER (WHERE status = 'active' AND line_count > 0) AS successful_calls,
         coalesce(sum(gross_mtk) FILTER (WHERE status = 'active'), 0) AS gross_mtk,
         coalesce(sum(net_mtk) FILTER (WHERE status = 'active'), 0) AS net_mtk,
         coalesce(sum(due_mtk) FILTER (WHERE status = 'active'), 0) AS due_mtk,
         count(*) FILTER (WHERE captured_offline) AS memos_captured_offline
    FROM dw.fact_memo GROUP BY business_date, user_id)
SELECT coalesce(v.business_date, m.business_date) AS business_date, coalesce(v.user_id, m.user_id) AS user_id,
       v.route_ids, coalesce(v.visits, 0) AS visits, coalesce(v.visited_outlets, 0) AS visited_outlets,
       coalesce(m.successful_calls, 0) AS successful_calls, coalesce(v.geo_valid_visits, 0) AS geo_valid_visits,
       dw.pct(v.geo_valid_visits, v.visits) AS geo_valid_pct, coalesce(v.force_sale_visits, 0) AS force_sale_visits,
       coalesce(v.mock_visits, 0) AS mock_visits, coalesce(m.active_memo_count, 0) AS active_memo_count,
       coalesce(m.gross_mtk, 0) AS gross_mtk, coalesce(m.net_mtk, 0) AS net_mtk, coalesce(m.due_mtk, 0) AS due_mtk,
       coalesce(m.memos_captured_offline, 0) AS memos_captured_offline, v.first_visit_at, v.last_visit_end_at
  FROM v FULL JOIN m ON m.business_date = v.business_date AND m.user_id = v.user_id;

CREATE VIEW dw.v_daily_outlet AS
SELECT a.business_date, a.outlet_id, o.outlet_code, o.outlet_name, coalesce(a.route_id, o.route_id) AS route_id,
       o.cluster_id, o.zone_id, o.channel, o.sub_channel_id, o.geo_class, o.outlet_kind, o.location_confirmed,
       a.visited, a.geo_valid, a.active_memo_count, a.sold_qty_base, a.net_mtk, a.due_mtk, a.dues_collected_mtk, a.updated_at
  FROM dw.agg_daily_outlet a
  LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id;

CREATE VIEW dw.v_daily_sku AS
SELECT a.business_date, a.route_id, g.route_code, g.zone_id, g.territory_id, a.sku_id, p.sku_code, p.short_name,
       p.base_unit, p.base_per_pack, p.brand_id, p.brand_name, p.category_code,
       a.sold_qty_base, a.free_qty_base, a.issued_qty_base, a.returned_qty_base, a.gross_mtk, a.memo_count, a.updated_at
  FROM dw.agg_daily_route_sku a
  LEFT JOIN dw.dim_product p ON p.sku_id = a.sku_id
  LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE VIEW dw.v_collections AS
SELECT a.business_date, a.outlet_id, o.outlet_code, o.outlet_name, coalesce(a.route_id, o.route_id) AS route_id, o.zone_id,
       a.net_mtk AS sales_net_mtk, a.due_mtk AS new_due_mtk, a.dues_collected_mtk, a.updated_at
  FROM dw.agg_daily_outlet a
  LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id
 WHERE a.due_mtk <> 0 OR a.dues_collected_mtk <> 0;

CREATE VIEW dw.v_attendance AS
SELECT f.business_date, f.user_id, f.role, f.zone_id, f.check_in_at, f.check_out_at,
       CASE WHEN f.check_in_at IS NOT NULL AND f.check_out_at IS NOT NULL
            THEN round(extract(epoch FROM f.check_out_at - f.check_in_at) / 3600.0, 2) END AS hours_in_field,
       f.check_in_lat, f.check_in_lng, f.check_in_accuracy_m, f.check_in_is_mock,
       f.check_out_lat, f.check_out_lng, f.check_out_accuracy_m, f.check_out_is_mock, f.updated_at
  FROM dw.fact_attendance f;

CREATE VIEW dw.v_geo_integrity AS
WITH v AS (
  SELECT business_date, user_id,
         count(*) AS visits,
         count(*) FILTER (WHERE server_verdict = 'in_range') AS server_in_range,
         count(*) FILTER (WHERE server_verdict = 'out_of_range') AS server_out_of_range,
         count(*) FILTER (WHERE device_verdict = 'no_fix') AS no_fix,
         count(*) FILTER (WHERE device_verdict = 'accuracy_too_low') AS accuracy_too_low,
         count(*) FILTER (WHERE device_verdict = 'mocked' OR is_mock) AS mocked_visits,
         count(*) FILTER (WHERE geo_action = 'force_sale') AS force_sale_visits,
         count(*) FILTER (WHERE geo_action = 'blocked') AS blocked_visits,
         count(*) FILTER (WHERE server_verdict IS NOT NULL AND server_verdict <> device_verdict) AS device_server_mismatch
    FROM dw.fact_visit WHERE NOT voided GROUP BY business_date, user_id),
f AS (
  SELECT business_date, user_id, count(*) AS fixes, count(*) FILTER (WHERE is_mock) AS mock_fixes,
         round(avg(accuracy_m)::numeric, 1) AS avg_accuracy_m
    FROM dw.fact_geo_fix GROUP BY business_date, user_id)
SELECT coalesce(v.business_date, f.business_date) AS business_date, coalesce(v.user_id, f.user_id) AS user_id,
       coalesce(v.visits, 0) AS visits, coalesce(v.server_in_range, 0) AS server_in_range,
       dw.pct(v.server_in_range, v.visits) AS geo_valid_pct, coalesce(v.server_out_of_range, 0) AS server_out_of_range,
       coalesce(v.no_fix, 0) AS no_fix, coalesce(v.accuracy_too_low, 0) AS accuracy_too_low,
       coalesce(v.mocked_visits, 0) AS mocked_visits, coalesce(v.force_sale_visits, 0) AS force_sale_visits,
       coalesce(v.blocked_visits, 0) AS blocked_visits, coalesce(v.device_server_mismatch, 0) AS device_server_mismatch,
       coalesce(f.fixes, 0) AS fixes, coalesce(f.mock_fixes, 0) AS mock_fixes, f.avg_accuracy_m
  FROM v FULL JOIN f ON f.business_date = v.business_date AND f.user_id = v.user_id;

-- New dw objects get the role grants of the map (V0014).
GRANT EXECUTE ON FUNCTION dw.pct(numeric, numeric) TO api_rw, worker_rw, web_ro, bi_reader;
SELECT app.apply_db_role_grants();
