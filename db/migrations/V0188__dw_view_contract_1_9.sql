-- V0179 dw view contract 1.8 -> 1.9 (sr-server train 2, push B; D-SV2-03).
-- 1. dw.v_route_day gains late_rows_after_sales_submit (app.route_day, SRM-B-30) before is_effective (every view ends with it, so the view is dropped
--    and recreated; comments are saved and restored, grants re-applied; nothing depends on it).
-- 2. dw.v_survey_response (SRM-B-08, the posm-survey report): one row per answer of a survey on a visit: outlet, date, question, answer, a photo
--    flag. The media id of the photo is never in a reuse view (D-SV1-02).
-- Minor version: columns and one view added. Lock: ONE LOCK ... NOWAIT retried for about 60 s (V0144 pattern).
SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE dw.v_route_day IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0188: dw.v_route_day stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

CREATE TEMP TABLE v_rd_comments ON COMMIT DROP AS
SELECT a.attname::text AS col, col_description('dw.v_route_day'::regclass, a.attnum) AS descr
  FROM pg_attribute a WHERE a.attrelid = 'dw.v_route_day'::regclass AND a.attnum > 0 AND NOT a.attisdropped;
CREATE TEMP TABLE v_rd_view_comment ON COMMIT DROP AS SELECT obj_description('dw.v_route_day'::regclass, 'pg_class') AS descr;

-- squawk-ignore ban-drop-view
DROP VIEW dw.v_route_day;
CREATE VIEW dw.v_route_day AS
SELECT business_date, route_id, zone_id, planned, exception_approved, is_target_route, day_state, downloaded_at, logged_in_at, checked_in_at,
       successful_login, sales_submitted, sales_submitted_at, final_submitted_at, zone_final_submitted, zone_final_submitted_at, zone_auto_submitted,
       first_sale_at, last_sale_at, target_outlets, visited_outlets, successful_calls, completed_calls, completed_geo_valid_calls, visits, geo_valid_visits,
       force_sale_visits, mock_visits, suspicious_visits, active_memo_count, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk,
       paid_mtk, due_mtk, dues_collected_mtk, late_rows_after_final, late_rows_after_sales_submit, updated_at, data_origin, is_effective
  FROM (SELECT a.business_date, a.route_id, a.zone_id,
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
               coalesce(rd.late_rows_after_sales_submit, 0) AS late_rows_after_sales_submit,
               a.updated_at, a.data_origin, true AS is_effective
          FROM dw.agg_daily_route a
          LEFT JOIN dw.agg_daily_zone z ON z.business_date = a.business_date AND z.zone_id = a.zone_id
          LEFT JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date) v;

DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT col, descr FROM v_rd_comments WHERE descr IS NOT NULL LOOP
    EXECUTE format('COMMENT ON COLUMN dw.v_route_day.%I IS %L', r.col, r.descr);
  END LOOP;
  EXECUTE format('COMMENT ON VIEW dw.v_route_day IS %L', (SELECT descr FROM v_rd_view_comment));
END $$;
COMMENT ON COLUMN dw.v_route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after its Sales Submit and stored before its Final Submit (counted once per stored record, never refused). After the Final Submit a late row counts under late_rows_after_final instead.';

CREATE VIEW dw.v_survey_response AS
SELECT sr.business_date, sr.client_uuid AS response_uuid, sr.visit_client_uuid, sr.user_id, sr.route_id, rt.zone_id, v.outlet_id,
       sr.survey_id, s.kind AS survey_kind, sr.survey_version, sr.question_id,
       (SELECT q ->> 'label_en' FROM app.survey_version sv CROSS JOIN LATERAL jsonb_array_elements(sv.questions) q
         WHERE sv.survey_id = sr.survey_id AND sv.version = sr.survey_version AND q ->> 'question_id' = sr.question_id::text LIMIT 1) AS question_label_en,
       sr.answer_type, sr.answer_bool, sr.answer_num, sr.answer_option_code, sr.answer_text, (sr.photo_uuid IS NOT NULL) AS photo_taken,
       sr.captured_at, sr.data_origin, (sr.voided_at IS NULL) AS is_effective
  FROM app.survey_response sr
  JOIN app.survey s ON s.id = sr.survey_id
  LEFT JOIN app.route rt ON rt.id = sr.route_id
  LEFT JOIN LATERAL (SELECT x.outlet_id FROM app.visit x WHERE x.client_uuid = sr.visit_client_uuid ORDER BY x.business_date DESC LIMIT 1) v ON true;

COMMENT ON VIEW dw.v_survey_response IS 'Stable view: one row per answered question of a survey taken on a visit (the POSM survey of the SR, the AMO survey, the TSO visit query): outlet, date, question, answer, photo flag. The photo itself (a media id) is never in a reuse view. is_effective = not voided.
owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON COLUMN dw.v_survey_response.business_date IS 'Business date (Asia/Dhaka) of the answer.';
COMMENT ON COLUMN dw.v_survey_response.response_uuid IS 'Client uuid of the answer record (idempotency key).';
COMMENT ON COLUMN dw.v_survey_response.visit_client_uuid IS 'The visit the survey was taken on.';
COMMENT ON COLUMN dw.v_survey_response.user_id IS 'The user who took the survey (from the token).';
COMMENT ON COLUMN dw.v_survey_response.route_id IS 'Route of the record; null when none.';
COMMENT ON COLUMN dw.v_survey_response.zone_id IS 'Zone of the route; null when the record has no route.';
COMMENT ON COLUMN dw.v_survey_response.outlet_id IS 'Outlet of the visit; null when the visit row is not known.';
COMMENT ON COLUMN dw.v_survey_response.survey_id IS 'Survey definition (app.survey.id).';
COMMENT ON COLUMN dw.v_survey_response.survey_kind IS 'posm, amo_survey or tso_visit_query.';
COMMENT ON COLUMN dw.v_survey_response.survey_version IS 'Version of the survey the question belongs to.';
COMMENT ON COLUMN dw.v_survey_response.question_id IS 'Question id inside the survey version.';
COMMENT ON COLUMN dw.v_survey_response.question_label_en IS 'English label of the question in that version; null when the version no longer holds it.';
COMMENT ON COLUMN dw.v_survey_response.answer_type IS 'bool, num, option, text or photo_only.';
COMMENT ON COLUMN dw.v_survey_response.answer_bool IS 'The answer of a bool question; null otherwise.';
COMMENT ON COLUMN dw.v_survey_response.answer_num IS 'The answer of a num question; null otherwise.';
COMMENT ON COLUMN dw.v_survey_response.answer_option_code IS 'The chosen option code of an option question; null otherwise.';
COMMENT ON COLUMN dw.v_survey_response.answer_text IS 'The answer of a text question; null otherwise.';
COMMENT ON COLUMN dw.v_survey_response.photo_taken IS 'True when the answer carries a photo (the media id is not exposed).';
COMMENT ON COLUMN dw.v_survey_response.captured_at IS 'When the phone captured the answer (UTC instant).';
COMMENT ON COLUMN dw.v_survey_response.data_origin IS 'Origin of the row (production, test, ...).';
COMMENT ON COLUMN dw.v_survey_response.is_effective IS 'True unless an admin voided the row.';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.9' $$;

SELECT app.apply_db_role_grants();
