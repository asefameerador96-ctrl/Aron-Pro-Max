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

-- The index is built under the same NOWAIT retry as V0186..V0190 (a SHARE lock blocks writers to app.cluster for the build; the table is small).
DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.cluster IN SHARE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0191: app.cluster stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
  CREATE UNIQUE INDEX cluster_zone_name_norm_key ON app.cluster (zone_id, app.norm_cluster_name(name));
END $$;

-- 3. Per-record facts for the late-row count (review B4 R1, B5 finding 1): the table holds the EXCEPTIONS, one row for every memo or due collection that was
--    stored while its route-day was Final Submitted (stored_closed is always true; absence of a row = stored while the day was open). It is classified ONCE, under the
--    route-day lock, when the record is stored; a recount excludes exactly these rows from late_rows_after_sales_submit (they count under late_rows_after_final).
--    "Written once": api_rw keeps INSERT and SELECT only (the '*/update' row of the grant map lists the table in its except_tables below).
CREATE TABLE app.late_row_fact (
  client_uuid   uuid PRIMARY KEY,
  stored_closed boolean NOT NULL DEFAULT true CHECK (stored_closed),
  created_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE app.late_row_fact IS 'One row per memo or due collection that was stored while its route-day was Final Submitted (absence = stored while the day was open): written once, never changed (api_rw has no UPDATE or DELETE), so a replay or a recount cannot move a row from one late-row counter to the other. Rows stored before this table existed were reconstructed by the V0191 backfill (best available: received_at inside a [final_submit.submitted_at, reopened_at) window of the zone-day).
owner: backend:sync | capture: ONLINE | retention: ops | pii: none';
COMMENT ON COLUMN app.late_row_fact.client_uuid IS 'Client uuid of the memo or due collection.';
COMMENT ON COLUMN app.late_row_fact.stored_closed IS 'Always true: the record was stored while its route-day was Final Submitted (decided under the route-day lock from the committed route-day state, the state that feeds late_rows_after_final).';
COMMENT ON COLUMN app.late_row_fact.created_at IS 'When the fact was written.';

UPDATE app.db_role_grant SET except_tables = except_tables || '{late_row_fact}'::text[]
 WHERE role = 'api_rw' AND schema_name = 'app' AND object = '*/update' AND NOT ('late_row_fact' = ANY (except_tables));
SELECT app.apply_db_role_grants();

-- Backfill (B5): memos and due collections stored BEFORE this table existed while a zone-day was closed have no fact; without one the first recount of such a route-day
-- would count them under late_rows_after_sales_submit as well as under late_rows_after_final. BEST AVAILABLE RECONSTRUCTION: a row whose received_at lies inside a
-- [final_submit.submitted_at, reopened_at) window of its zone-day (reopened_at null = still closed) is marked stored_closed. The zone is the route's zone, the rule the
-- B3 code used; it is exact for a row stored once, and may mark a row of a route that changed zone in between (pilot data, accepted). Runs once per record, no lock needed
-- on a table that did not exist before this migration. At fleet size run it in batches by business_date (it is one statement here: pilot size).
INSERT INTO app.late_row_fact (client_uuid)
SELECT m.client_uuid FROM app.memo m
  JOIN app.route r ON r.id = m.route_id
  JOIN app.final_submit f ON f.zone_id = r.zone_id AND f.business_date = m.business_date
   AND m.received_at >= f.submitted_at AND (f.reopened_at IS NULL OR m.received_at < f.reopened_at)
UNION
SELECT d.client_uuid FROM app.due_collection d
  JOIN app.route r ON r.id = d.route_id
  JOIN app.final_submit f ON f.zone_id = r.zone_id AND f.business_date = d.business_date
   AND d.received_at >= f.submitted_at AND (f.reopened_at IS NULL OR d.received_at < f.reopened_at)
ON CONFLICT (client_uuid) DO NOTHING;

-- 4. The clock of a route-day Sales Submit travels WITH the record (B5 finding 2): written by the same RecordWriter statement as the day_submit row, so a retry, a
--    quarantine and its release, and a replay all keep it. NULL = phone clock; 'server' = the record's captured_at is the server's time (an older client's online
--    submit without a phone clock, or a phone clock outside the plausibility bound).
ALTER TABLE app.route_day_event ADD COLUMN anchor_clock text CHECK (anchor_clock IN ('phone', 'server'));
COMMENT ON COLUMN app.route_day_event.anchor_clock IS 'day_submit only: whose clock stamped captured_at, the anchor of the late-row count. phone (or null) = the phone''s own clock; server = the server stamped it (an online Sales Submit without a phone clock or with an implausible one). Written with the record.';

-- 5. The cluster an approval used is recorded with it (B5): a retried approval that names ANOTHER cluster is judged against this value, not against the outlet's cluster now.
ALTER TABLE app.outlet_change_request ADD COLUMN approved_cluster_id bigint;
COMMENT ON COLUMN app.outlet_change_request.approved_cluster_id IS 'The cluster the approval of a new-shop or cluster request put the outlet in (set once at the approval); null for other request types and for requests not yet approved.';
CREATE OR REPLACE TRIGGER outlet_change_request_immutable BEFORE UPDATE OR DELETE ON app.outlet_change_request
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'status', 'status_changed_at', '=verified_by', '=verified_at', '=decided_by', '=decided_at', '=decision_reason', '=created_outlet_id', '=created_cluster_id', '=approved_cluster_id', 'data_origin');

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
COMMENT ON COLUMN app.route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after the same user''s Sales Submit record (its own captured_at, phone clock against phone clock; the newest non-voided cycle is the anchor, so a second submit can LOWER the count) and not voided and not stored while the day was closed (app.late_row_fact). DERIVED from the stored rows (recounted under the route-day lock whenever a memo, a due collection or a route-day Sales Submit is stored), so it is one number under replay and any arrival order; a memo chain counts once. Rows stored after the Final Submit count under late_rows_after_final instead. Never refused.';
COMMENT ON COLUMN dw.v_route_day.late_rows_after_sales_submit IS 'Memos and due collections of the route-day captured after the same user''s Sales Submit record (its own captured_at) and stored while the zone-day was open; derived from the stored rows, one number under replay and any arrival order. After the Final Submit a late row counts under late_rows_after_final instead.';
