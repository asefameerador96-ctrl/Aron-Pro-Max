-- V0015 the stable read-only dw views other products use (docs/31 s3 item 5): BI, the BOD dashboard, the later
-- indent, discount and target engines and a lakehouse export read these views (and the domain-event outbox), never
-- app tables. A view's name and columns are a contract: they change only additively (new columns at the end, new
-- views); a breaking change ships as a new view (v2) next to the old one. Every column is documented in
-- docs/data-dictionary.md. Money is integer milli-taka (*_mtk), quantities integer SKU base units (*_qty_base),
-- instants UTC, business_date the Asia/Dhaka date. Percentages are 0..100 with 2 decimals, null when the denominator
-- is 0 (docs/24 s12.4).

SET lock_timeout = '5s';

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

-- One UNION ALL under one GROUP BY (not a FULL JOIN of two grouped sides), so a filter on business_date or user_id
-- reaches each fact scan and prunes partitions. Visits are SR calls; memos count when active with lines (s12.4).
CREATE VIEW dw.v_daily_sr AS
SELECT x.business_date, x.user_id,
       array_agg(DISTINCT x.route_id) FILTER (WHERE x.k = 'v' AND x.route_id IS NOT NULL) AS route_ids,
       count(*) FILTER (WHERE x.k = 'v') AS visits,
       count(DISTINCT x.outlet_id) FILTER (WHERE x.k = 'v' AND x.reached) AS visited_outlets,
       count(DISTINCT x.outlet_id) FILTER (WHERE x.k = 'm') AS successful_calls,
       count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict = 'in_range') AS geo_valid_visits,
       dw.pct(count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict = 'in_range'), count(*) FILTER (WHERE x.k = 'v')) AS geo_valid_pct,
       count(*) FILTER (WHERE x.k = 'v' AND x.geo_action = 'force_sale') AS force_sale_visits,
       count(*) FILTER (WHERE x.k = 'v' AND x.is_mock) AS mock_visits,
       count(*) FILTER (WHERE x.k = 'm') AS active_memo_count,
       coalesce(sum(x.gross_mtk), 0)::bigint AS gross_mtk,
       coalesce(sum(x.net_mtk), 0)::bigint AS net_mtk,
       coalesce(sum(x.due_mtk), 0)::bigint AS due_mtk,
       count(*) FILTER (WHERE x.k = 'm' AND x.captured_offline) AS memos_captured_offline,
       min(x.at) FILTER (WHERE x.k = 'v') AS first_visit_at,
       max(x.end_at) FILTER (WHERE x.k = 'v') AS last_visit_end_at
  FROM (SELECT 'v'::text AS k, business_date, user_id, route_id, outlet_id,
               outcome_code IS DISTINCT FROM 'abandoned' AND call_declined IS NOT TRUE AS reached,
               server_verdict, geo_action, is_mock, NULL::bigint AS gross_mtk, NULL::bigint AS net_mtk,
               NULL::bigint AS due_mtk, NULL::boolean AS captured_offline, opened_at AS at, coalesce(ended_at, opened_at) AS end_at
          FROM dw.fact_visit WHERE NOT voided AND visit_kind = 'sr_call'
        UNION ALL
        SELECT 'm', business_date, user_id, route_id, outlet_id, NULL, NULL, NULL, NULL, gross_mtk, net_mtk, due_mtk,
               captured_offline, NULL, NULL
          FROM dw.fact_memo WHERE status = 'active' AND line_count > 0) x
 GROUP BY x.business_date, x.user_id;

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

-- No coordinates and no fix accuracy: an employee's location is personal data and stays in dw.fact_attendance, which BI cannot read.
CREATE VIEW dw.v_attendance AS
SELECT f.business_date, f.user_id, f.role, f.zone_id, f.check_in_at, f.check_out_at,
       CASE WHEN f.check_in_at IS NOT NULL AND f.check_out_at IS NOT NULL
            THEN round(extract(epoch FROM f.check_out_at - f.check_in_at) / 3600.0, 2) END AS hours_in_field,
       f.check_in_is_mock, f.check_out_is_mock, f.updated_at
  FROM dw.fact_attendance f;

-- Covers every visit kind of the user (SR, AMO and TSO calls): integrity is about the person's fixes; v_daily_sr counts
-- SR calls only, so their geo_valid_pct differ for a supervisor. UNION ALL under one GROUP BY, as v_daily_sr.
CREATE VIEW dw.v_geo_integrity AS
SELECT x.business_date, x.user_id,
       count(*) FILTER (WHERE x.k = 'v') AS visits,
       count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict = 'in_range') AS server_in_range,
       dw.pct(count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict = 'in_range'), count(*) FILTER (WHERE x.k = 'v')) AS geo_valid_pct,
       count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict = 'out_of_range') AS server_out_of_range,
       count(*) FILTER (WHERE x.k = 'v' AND x.device_verdict = 'no_fix') AS no_fix,
       count(*) FILTER (WHERE x.k = 'v' AND x.device_verdict = 'accuracy_too_low') AS accuracy_too_low,
       count(*) FILTER (WHERE x.k = 'v' AND (x.device_verdict = 'mocked' OR x.is_mock)) AS mocked_visits,
       count(*) FILTER (WHERE x.k = 'v' AND x.geo_action = 'force_sale') AS force_sale_visits,
       count(*) FILTER (WHERE x.k = 'v' AND x.geo_action = 'blocked') AS blocked_visits,
       count(*) FILTER (WHERE x.k = 'v' AND x.server_verdict IS NOT NULL AND x.server_verdict <> x.device_verdict) AS device_server_mismatch,
       count(*) FILTER (WHERE x.k = 'f') AS fixes,
       count(*) FILTER (WHERE x.k = 'f' AND x.is_mock) AS mock_fixes,
       round(avg(x.accuracy_m)::numeric, 1) AS avg_accuracy_m
  FROM (SELECT 'v'::text AS k, business_date, user_id, server_verdict, device_verdict, geo_action, is_mock,
               NULL::double precision AS accuracy_m
          FROM dw.fact_visit WHERE NOT voided
        UNION ALL
        SELECT 'f', business_date, user_id, NULL, NULL, NULL, is_mock, accuracy_m FROM dw.fact_geo_fix) x
 GROUP BY x.business_date, x.user_id;

-- New dw objects get the role grants of the map (V0014).
GRANT EXECUTE ON FUNCTION dw.pct(numeric, numeric) TO api_rw, worker_rw, web_ro, bi_reader;
SELECT app.apply_db_role_grants();
