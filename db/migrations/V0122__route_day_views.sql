-- V0122 route-keyed dashboard surface (lead 2026-10-28 00:28 item 2; D-BC-DASH-ROUTE-ONLY-01; D-DB-ROUTE-DAY-VIEW-01): the
-- dashboards answer a route-only TSO (route assignments, no geo node) with 403 because their totals come from
-- dw.agg_daily_zone, whose rows are per zone. dw.agg_daily_route already holds every zone-day measure at route grain
-- (the zone row is its sum, Aggregator.rebuildZoneDay); what was missing is a stable, route-keyed read of it with the
-- zone-day flags derived per route, and a by-channel split that follows the route that sold. No new table, no projector
-- change: both views read rows the projector already writes.
--   dw.v_route_day: one row per route and business date. is_target_route = planned AND NOT exception_approved (the zone's
--     target_routes counts these); successful_login = logged_in_at, downloaded_at and checked_in_at all set (the rule of
--     DashboardSummaries.successfulLoginSql on this schema, where every column it checks exists); sales_submitted =
--     day_state sales_submitted or final_submitted AND successful_login (the zone's sales_submitted_routes rule);
--     zone_final_submitted, zone_final_submitted_at and zone_auto_submitted come from the agg_daily_zone row of the zone
--     the route-day was stamped with: final_submitted is that row's flag (every target route of the zone-day in state
--     final_submitted); the midnight auto-submit sets only final_submitted_at and auto_submitted, so a zone-day can read
--     zone_final_submitted false and zone_auto_submitted true. Every money column integer milli-taka; counts and
--     money summed over a caller's routes give the zone totals' route-scoped equivalent. is_effective = true (every
--     route-day counts; the target flag says which count in the strike-rate denominator).
--   dw.v_route_day_channel: active memos (status active, at least one line) and the distinct outlets they were sold to
--     (successful_calls, the summary's by_channel "calls") per route, business date and the outlet
--     channel frozen on the memo at capture (app.memo.outlet_channel, V0042), so a route filter counts the sales of
--     that route only and never follows a later outlet move or reclassification (dw.agg_daily_outlet keys the outlet's
--     current route and current channel). Memos with no channel on record (no outlet, or captured before V0042) form a
--     NULL-channel row. data_origin = the most synthetic of the memos behind the row.
-- Labels (names) are not here: the API resolves them, as for every reuse view.

SET lock_timeout = '5s';

CREATE VIEW dw.v_route_day AS
SELECT a.business_date, a.route_id, a.zone_id,
       a.planned, a.exception_approved, (a.planned AND NOT a.exception_approved) AS is_target_route,
       a.day_state, a.downloaded_at, a.logged_in_at, a.checked_in_at,
       (a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL AND a.checked_in_at IS NOT NULL) AS successful_login,
       (a.day_state IN ('sales_submitted', 'final_submitted')
        AND a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL AND a.checked_in_at IS NOT NULL) AS sales_submitted,
       a.sales_submitted_at, a.final_submitted_at,
       coalesce(z.final_submitted, false) AS zone_final_submitted, z.final_submitted_at AS zone_final_submitted_at,
       coalesce(z.auto_submitted, false) AS zone_auto_submitted,
       a.first_sale_at, a.last_sale_at,
       a.target_outlets, a.visited_outlets, a.successful_calls, a.completed_calls, a.completed_geo_valid_calls,
       a.visits, a.geo_valid_visits, a.force_sale_visits, a.mock_visits, a.suspicious_visits,
       a.active_memo_count, a.gross_mtk, a.offer_discount_mtk, a.drp_discount_mtk, a.qc_deduction_mtk, a.net_mtk,
       a.paid_mtk, a.due_mtk, a.dues_collected_mtk, a.late_rows_after_final,
       a.updated_at, a.data_origin, true AS is_effective
  FROM dw.agg_daily_route a
  LEFT JOIN dw.agg_daily_zone z ON z.business_date = a.business_date AND z.zone_id = a.zone_id;

CREATE VIEW dw.v_route_day_channel AS
SELECT f.business_date, f.route_id, m.outlet_channel,
       count(*)::int AS active_memo_count, count(DISTINCT f.outlet_id)::int AS successful_calls, sum(f.gross_mtk)::bigint AS gross_mtk, sum(f.net_mtk)::bigint AS net_mtk,
       max(f.updated_at) AS updated_at, max(f.data_origin) AS data_origin, true AS is_effective
  FROM dw.fact_memo f
  JOIN app.memo m ON m.client_uuid = f.memo_client_uuid AND m.business_date = f.business_date
 WHERE f.status = 'active' AND f.line_count > 0 AND f.route_id IS NOT NULL
 GROUP BY f.business_date, f.route_id, m.outlet_channel;

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.2' $$;

COMMENT ON VIEW dw.v_route_day IS 'Stable view: one row per route and business date (dw.agg_daily_route) with the zone-day flags derived per route (target route, successful login, sales submitted) and the Final Submit of the zone the route-day was stamped with; summing over a caller''s routes gives the route-scoped dashboard totals. is_effective = true.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON VIEW dw.v_route_day_channel IS 'Stable view: active memos, outlets sold to, gross and net per route, business date and the outlet channel frozen on the memo at capture (dw.fact_memo, app.memo.outlet_channel); follows the route that sold, never a later outlet move. is_effective = true.
owner: worker | capture: SERVER | retention: event_fact | pii: none';

DO $$
DECLARE c record;
BEGIN
  FOR c IN SELECT a.attname, col_description(s.attrelid, s.attnum) AS d
             FROM pg_attribute a JOIN pg_attribute s ON s.attrelid = 'dw.agg_daily_route'::regclass AND s.attname = a.attname AND s.attnum > 0
            WHERE a.attrelid = 'dw.v_route_day'::regclass AND a.attnum > 0 AND col_description(s.attrelid, s.attnum) IS NOT NULL
  LOOP
    EXECUTE format('COMMENT ON COLUMN dw.v_route_day.%I IS %L', c.attname, c.d);
  END LOOP;
END $$;
COMMENT ON COLUMN dw.v_route_day.is_target_route IS 'True when the route was planned for the day and no day exception was approved (counted in the zone''s target_routes and the strike-rate denominator).';
COMMENT ON COLUMN dw.v_route_day.successful_login IS 'True when the route-day has its login, its first full bundle download and the SR''s check-in (the dashboards'' successful-login rule).';
COMMENT ON COLUMN dw.v_route_day.sales_submitted IS 'True when Sales Submit (or Final Submit) is in effect and the login was successful (the zone''s sales_submitted_routes rule).';
COMMENT ON COLUMN dw.v_route_day.zone_final_submitted IS 'dw.agg_daily_zone.final_submitted of the route-day''s stamped zone: every target route of that zone-day is final-submitted (the midnight auto-submit does not set it; see zone_auto_submitted).';
COMMENT ON COLUMN dw.v_route_day.zone_final_submitted_at IS 'UTC time of that zone-day''s first Final Submit or midnight auto-submit.';
COMMENT ON COLUMN dw.v_route_day.zone_auto_submitted IS 'True when the midnight job auto-submitted that zone-day.';
COMMENT ON COLUMN dw.v_route_day.is_effective IS 'True when the row counts in an ordinary total (the rule of this view is in its description).';
COMMENT ON COLUMN dw.v_route_day_channel.business_date IS 'Asia/Dhaka business date of the memos.';
COMMENT ON COLUMN dw.v_route_day_channel.route_id IS 'Route of the memos (dw.fact_memo.route_id).';
COMMENT ON COLUMN dw.v_route_day_channel.outlet_channel IS 'Outlet channel code frozen on the memo at capture (app.memo.outlet_channel).';
COMMENT ON COLUMN dw.v_route_day_channel.active_memo_count IS 'Active memos (not voided or superseded) with at least one line.';
COMMENT ON COLUMN dw.v_route_day_channel.successful_calls IS 'Distinct outlets with an active memo of the route on the date in that channel (the summary''s by_channel calls).';
COMMENT ON COLUMN dw.v_route_day_channel.gross_mtk IS 'Gross sales value before discounts of those memos, in milli-taka.';
COMMENT ON COLUMN dw.v_route_day_channel.net_mtk IS 'Net payable value of those memos, in milli-taka.';
COMMENT ON COLUMN dw.v_route_day_channel.updated_at IS 'Latest update of the memo facts behind the row.';
COMMENT ON COLUMN dw.v_route_day_channel.data_origin IS 'Origin (production, pilot, test, seed): the most synthetic of the memos behind the row.';
COMMENT ON COLUMN dw.v_route_day_channel.is_effective IS 'True when the row counts in an ordinary total (the rule of this view is in its description).';

SELECT app.apply_db_role_grants();
