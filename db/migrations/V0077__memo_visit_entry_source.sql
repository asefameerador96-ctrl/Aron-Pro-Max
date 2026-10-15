-- V0077 entry source on app.memo and app.visit (F-API-038 paper backfill, docs/24 s8.5 item 4; answers
-- docs/requests/backend-core-memo-source.md). A dead-phone day keyed on the web from the printed memo is written through
-- the ingest path with entry_source = 'manual' and the web user who keyed it in entered_by_user_id (user_id stays the
-- SR's, as on a phone row); every phone row stays 'device' with no entered_by_user_id. memo_line rows follow their memo.
-- Both columns are written once at insert; the V0007 immutability guards keep them (they are not free columns).
-- The ingest registry gains the flag manual_entry. dw.fact_memo and dw.fact_visit carry entry_source (the projector
-- copies it: docs/requests/db-backend-reports-entry-source.md), and dw.v_daily_sr gains memos_entered_manually.
-- Addendum (lead ruling 2026-10-15): risk code MEMO_DUPLICATE_SUSPECT (subject memo; a second memo of the same outlet,
-- gross and business date under another memo_no, one of them manual) with integrity weight 30. A flag for review,
-- never a block; backend-core raises it.
--
-- Locks: constant defaults and nullable columns are catalogue-only on every partition; the CHECKs are added NOT VALID
-- and validated in V0078. The FKs to app.app_user cannot be NOT VALID on a partitioned table (PostgreSQL 16), so they
-- are added directly: the new column is NULL on every row, so the validation finds nothing, but it reads each
-- partition while this transaction holds ACCESS EXCLUSIVE on memo and visit (and SHARE ROW EXCLUSIVE on app_user). memo and visit are pilot-sized in the test account and empty in a new account; before the
-- staging migration with imported history, the D-DB-PART-01 pre-staging batch should add these FKs in their own
-- migration per partition.

SET lock_timeout = '5s';

ALTER TABLE app.memo ADD COLUMN entry_source text NOT NULL DEFAULT 'device';
ALTER TABLE app.memo ADD COLUMN entered_by_user_id bigint;
ALTER TABLE app.memo ADD CONSTRAINT memo_entry_source_known
  CHECK (entry_source IN ('device', 'manual') AND (entry_source = 'manual') = (entered_by_user_id IS NOT NULL)) NOT VALID;
-- squawk-ignore adding-foreign-key-constraint
ALTER TABLE app.memo ADD CONSTRAINT memo_entered_by_user_id_fkey FOREIGN KEY (entered_by_user_id) REFERENCES app.app_user(id);

ALTER TABLE app.visit ADD COLUMN entry_source text NOT NULL DEFAULT 'device';
ALTER TABLE app.visit ADD COLUMN entered_by_user_id bigint;
ALTER TABLE app.visit ADD CONSTRAINT visit_entry_source_known
  CHECK (entry_source IN ('device', 'manual') AND (entry_source = 'manual') = (entered_by_user_id IS NOT NULL)) NOT VALID;
-- squawk-ignore adding-foreign-key-constraint
ALTER TABLE app.visit ADD CONSTRAINT visit_entered_by_user_id_fkey FOREIGN KEY (entered_by_user_id) REFERENCES app.app_user(id);

ALTER TABLE app.ingest_registry DROP CONSTRAINT ingest_registry_flags_known;
ALTER TABLE app.ingest_registry ADD CONSTRAINT ingest_registry_flags_known
  CHECK (flags <@ ARRAY['resync_late', 'config_stamp_regress', 'checkout_too_early', 'manual_entry']::text[]
         AND cardinality(flags) = ('resync_late' = ANY (flags))::int + ('config_stamp_regress' = ANY (flags))::int
                                + ('checkout_too_early' = ANY (flags))::int + ('manual_entry' = ANY (flags))::int) NOT VALID;

ALTER TABLE app.risk_signal DROP CONSTRAINT risk_signal_code_check;
ALTER TABLE app.risk_signal ADD CONSTRAINT risk_signal_code_check
  CHECK (code IN ('GEO_MOCK', 'GEO_TELEPORT', 'GEO_ZERO_JITTER', 'GEO_PERFECT_ACCURACY', 'GEO_SAME_POINT',
                  'GEO_ROUTE_SINGLE_POINT', 'GEO_STALE_FIX', 'GEO_GNSS_INCONSISTENT', 'GEO_GNSS_TIME_SKEW',
                  'GEO_DEVICE_SERVER_MISMATCH', 'GEO_SHORT_VISIT_GAPS', 'DEVICE_NOT_OWNER', 'DEVICE_INTEGRITY_FAIL',
                  'DEVICE_DEBUG_ENABLED', 'DEVICE_MOCK_APP_PRESENT', 'DEVICE_POLICY_DRIFT', 'CLOCK_SKEW',
                  'GEO_OUT_OF_BOUNDS', 'CONFIG_STAMP_REGRESS', 'MEMO_DUPLICATE_SUSPECT')) NOT VALID;
UPDATE app.cfg_key SET default_value = default_value || '{"MEMO_DUPLICATE_SUSPECT": 30}'::jsonb, updated_at = now()
 WHERE key = 'cfg.geo.integrity_weight' AND NOT default_value ? 'MEMO_DUPLICATE_SUSPECT';

ALTER TABLE dw.fact_memo ADD COLUMN entry_source text NOT NULL DEFAULT 'device';
ALTER TABLE dw.fact_visit ADD COLUMN entry_source text NOT NULL DEFAULT 'device';

-- Same definition as V0015 with entry_source carried through both arms and one column appended at the end.
CREATE OR REPLACE VIEW dw.v_daily_sr AS
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
       max(x.end_at) FILTER (WHERE x.k = 'v') AS last_visit_end_at,
       count(*) FILTER (WHERE x.k = 'm' AND x.entry_source = 'manual') AS memos_entered_manually
  FROM (SELECT 'v'::text AS k, business_date, user_id, route_id, outlet_id,
               outcome_code IS DISTINCT FROM 'abandoned' AND call_declined IS NOT TRUE AS reached,
               server_verdict, geo_action, is_mock, NULL::bigint AS gross_mtk, NULL::bigint AS net_mtk,
               NULL::bigint AS due_mtk, NULL::boolean AS captured_offline, opened_at AS at, coalesce(ended_at, opened_at) AS end_at,
               entry_source
          FROM dw.fact_visit WHERE NOT voided AND visit_kind = 'sr_call'
        UNION ALL
        SELECT 'm', business_date, user_id, route_id, outlet_id, NULL, NULL, NULL, NULL, gross_mtk, net_mtk, due_mtk,
               captured_offline, NULL, NULL, entry_source
          FROM dw.fact_memo WHERE status = 'active' AND line_count > 0) x
 GROUP BY x.business_date, x.user_id;

COMMENT ON COLUMN app.memo.entry_source IS 'device = captured on the phone; manual = keyed on the web from the printed memo (paper backfill, F-API-038).';
COMMENT ON COLUMN app.memo.entered_by_user_id IS 'Web user who keyed in a manual memo (app.app_user); null on device rows.';
COMMENT ON COLUMN app.visit.entry_source IS 'device = captured on the phone; manual = keyed on the web for a dead-phone day (paper backfill, F-API-038).';
COMMENT ON COLUMN app.visit.entered_by_user_id IS 'Web user who keyed in a manual visit (app.app_user); null on device rows.';
COMMENT ON COLUMN app.ingest_registry.flags IS 'Flags on the accepted record: resync_late (re-sent after a failover or restore, accepted past cfg.sync.max_backdate_days, F-SYS-089), config_stamp_regress (stamped with an older config_version than the device had applied, F-SYS-091), checkout_too_early (check-out before cfg.day.checkout_earliest_time, BC-63), manual_entry (keyed on the web from paper, F-API-038); empty when none.';
COMMENT ON COLUMN app.risk_signal.code IS 'Risk rule that raised the signal (GEO_*, DEVICE_*, CLOCK_SKEW, CONFIG_STAMP_REGRESS, MEMO_DUPLICATE_SUSPECT); weights in cfg.geo.integrity_weight.';
COMMENT ON COLUMN dw.fact_memo.entry_source IS 'device or manual (keyed on the web from the printed memo), copied from app.memo.';
COMMENT ON COLUMN dw.fact_visit.entry_source IS 'device or manual (keyed on the web for a dead-phone day), copied from app.visit.';
COMMENT ON COLUMN dw.v_daily_sr.memos_entered_manually IS 'Active memos keyed on the web from paper (entry_source manual).';
