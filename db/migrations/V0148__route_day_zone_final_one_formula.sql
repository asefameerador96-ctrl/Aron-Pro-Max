-- V0148 dw.v_route_day.zone_final_submitted: ONE Final Submit formula (D-DB-ZONE-FINAL-ONE-01; D-TSO-MAN-30, D-TSO-MAN-53).
-- Before: the view read dw.agg_daily_zone.final_submitted (every target route of the zone-day in state final_submitted),
-- while the backend's zoneSubmittedSql (national card, summary, final-submit-status report) reads the zone-day projection
-- of the live app.final_submit rows: final_submitted_at IS NOT NULL AND NOT auto_submitted. Now the view uses the
-- backend's formula, so a view reader and the cards cannot disagree. Same columns and types (view contract 1.3 -> 1.4: a
-- semantic change is announced by the version; the column table is unchanged). zone_final_submitted_at and
-- zone_auto_submitted are unchanged. CREATE OR REPLACE keeps owner and grants.
SET lock_timeout = '5s';

CREATE OR REPLACE VIEW dw.v_route_day AS
SELECT a.business_date, a.route_id, a.zone_id,
       a.planned, a.exception_approved, (a.planned AND NOT a.exception_approved) AS is_target_route,
       a.day_state, a.downloaded_at, a.logged_in_at, a.checked_in_at,
       (a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL AND a.checked_in_at IS NOT NULL) AS successful_login,
       (a.day_state IN ('sales_submitted', 'final_submitted')
        AND a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL AND a.checked_in_at IS NOT NULL) AS sales_submitted,
       a.sales_submitted_at, a.final_submitted_at,
       coalesce(z.final_submitted_at IS NOT NULL AND NOT z.auto_submitted, false) AS zone_final_submitted, z.final_submitted_at AS zone_final_submitted_at,
       coalesce(z.auto_submitted, false) AS zone_auto_submitted,
       a.first_sale_at, a.last_sale_at,
       a.target_outlets, a.visited_outlets, a.successful_calls, a.completed_calls, a.completed_geo_valid_calls,
       a.visits, a.geo_valid_visits, a.force_sale_visits, a.mock_visits, a.suspicious_visits,
       a.active_memo_count, a.gross_mtk, a.offer_discount_mtk, a.drp_discount_mtk, a.qc_deduction_mtk, a.net_mtk,
       a.paid_mtk, a.due_mtk, a.dues_collected_mtk, a.late_rows_after_final,
       a.updated_at, a.data_origin, true AS is_effective
  FROM dw.agg_daily_route a
  LEFT JOIN dw.agg_daily_zone z ON z.business_date = a.business_date AND z.zone_id = a.zone_id;

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.4' $$;

COMMENT ON COLUMN dw.v_route_day.zone_final_submitted IS 'True when the zone-day this route-day belongs to has a live Final Submit, whoever closed it, the midnight job included: dw.agg_daily_zone.final_submitted_at is set and the retired auto_submitted flag is false. The same rule as the national card, the summary and the final-submit-status report (backend zoneSubmittedSql, D-TSO-MAN-30); since V0148 (contract 1.4) it no longer reads agg_daily_zone.final_submitted.';

SELECT app.apply_db_role_grants();
