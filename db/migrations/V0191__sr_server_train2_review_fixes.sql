-- V0191 sr-server train 2, push B3 (the lead's Opus pass on 448e59eb; D-SV2-05 to D-SV2-07).
-- 1. Cluster names are unique per zone IN THE DATABASE (review item a): ONE normalisation function, app.norm_cluster_name (NFC, every Unicode space collapsed to one
--    space, trimmed, lower-cased), used by the unique index below, the ingest check, the approval check and the open-request check, so a concurrent duplicate fails
--    cleanly (the application turns the unique violation into cluster_name_exists). If rows that already differ only by case, spacing or composition exist the migration
--    FAILS LOUDLY at the check (nothing is renamed or merged silently); the table is small (clusters of a zone). The V0002 UNIQUE (zone_id, name) stays.
-- 2. dw.v_survey_response (review item d): joins app.visit on the answer's business_date (partition pruning) and is labelled personal: a free-text answer can hold a
--    phone number. bi_reader keeps the view WITHOUT answer_text (column grant, the V0118 rule); the columns are unchanged, so dw.view_contract_version() stays 1.9.

SET lock_timeout = '5s';

CREATE OR REPLACE FUNCTION app.norm_cluster_name(t text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT lower(btrim(regexp_replace(normalize(t, NFC), '[\s   -     　]+', ' ', 'g'))) $$;
COMMENT ON FUNCTION app.norm_cluster_name(text) IS 'The one normalisation of a cluster name (NFC, Unicode whitespace collapsed to one space, trimmed, lower-cased): the unique index per zone, the ingest check, the approval check and the open-request check all use it (V0191).';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM app.cluster GROUP BY zone_id, app.norm_cluster_name(name) HAVING count(*) > 1) THEN
    RAISE EXCEPTION 'V0191: clusters of one zone whose names differ only by case, spacing or composition exist; merge or rename them (admin portal) and run the migration again';
  END IF;
END $$;

-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX cluster_zone_name_norm_key ON app.cluster (zone_id, app.norm_cluster_name(name));

CREATE OR REPLACE VIEW dw.v_survey_response AS
SELECT sr.business_date, sr.client_uuid AS response_uuid, sr.visit_client_uuid, sr.user_id, sr.route_id, rt.zone_id, v.outlet_id,
       sr.survey_id, s.kind AS survey_kind, sr.survey_version, sr.question_id,
       (SELECT q ->> 'label_en' FROM app.survey_version sv CROSS JOIN LATERAL jsonb_array_elements(sv.questions) q
         WHERE sv.survey_id = sr.survey_id AND sv.version = sr.survey_version AND q ->> 'question_id' = sr.question_id::text LIMIT 1) AS question_label_en,
       sr.answer_type, sr.answer_bool, sr.answer_num, sr.answer_option_code, sr.answer_text, (sr.photo_uuid IS NOT NULL) AS photo_taken,
       sr.captured_at, sr.data_origin, (sr.voided_at IS NULL) AS is_effective
  FROM app.survey_response sr
  JOIN app.survey s ON s.id = sr.survey_id
  LEFT JOIN app.route rt ON rt.id = sr.route_id
  LEFT JOIN LATERAL (SELECT x.outlet_id FROM app.visit x WHERE x.client_uuid = sr.visit_client_uuid AND x.business_date = sr.business_date LIMIT 1) v ON true;

COMMENT ON VIEW dw.v_survey_response IS 'Stable view: one row per answered question of a survey taken on a visit (the POSM survey of the SR, the AMO survey, the TSO visit query), every survey kind: outlet, date, question, answer, photo flag. A text answer is free text and can hold personal data (a phone number): the view is personal and bi_reader reads it without answer_text. The photo itself (a media id) is never in a reuse view. is_effective = not voided.
owner: backend:sync | capture: OFFLINE | retention: transaction | pii: personal';
COMMENT ON COLUMN dw.v_survey_response.answer_text IS '[pii:personal] The answer of a text question (free text, may hold a phone number); null otherwise. Not granted to bi_reader.';

UPDATE app.db_role_grant SET except_tables = except_tables || '{v_survey_response}'::text[]
 WHERE role = 'bi_reader' AND schema_name = 'dw' AND object = 'v_*';
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, except_columns, only_columns, note) VALUES
  ('bi_reader', 'dw', 'v_survey_response', 'SELECT', '{}', '{answer_text}', '{}', 'survey answers without the free-text answer (personal, V0191)');
SELECT app.apply_db_role_grants();

-- Review R1: the count is derived (recounted from the stored rows), no longer incremented per stored record.
COMMENT ON COLUMN app.route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after the same user''s Sales Submit record (its own captured_at, phone clock against phone clock) and stored while the zone-day was open. DERIVED from the stored rows (recounted under the route-day lock whenever a memo, a due collection or a route-day Sales Submit is stored), so it is one number under replay and any arrival order; a memo chain counts once. Rows stored after the Final Submit count under late_rows_after_final instead. Never refused.';
COMMENT ON COLUMN dw.v_route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after the same user''s Sales Submit record (its own captured_at) and stored while the zone-day was open; derived from the stored rows, one number under replay and any arrival order. After the Final Submit a late row counts under late_rows_after_final instead.';
