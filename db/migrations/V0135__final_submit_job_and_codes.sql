-- V0135 midnight auto Final Submit and the day lock codes (contract docs/requests/tso-manual-build-2026-10.md s2;
-- D-TSO-MAN-18..21, D-TSO-MAN-30, D-TSO-MAN-31; gap items FS-11, FS-13, TK-05; lane F2).
--
-- 1. app.final_submit: via gains 'job' (the AutoFinalSubmitJob at 00:00 Asia/Dhaka writes a real row through
--    DayService's locked path, so every final-submit figure reads live app.final_submit rows and the dw-only
--    auto_submitted stamp goes, D-TSO-MAN-30). A job row has no submitting user: submitted_by becomes nullable, and the
--    new CHECK final_submit_job_actor ties the two together ((via = 'job') = (submitted_by IS NULL)), so an app or web
--    submit still always names its user. Dropping NOT NULL is marked for squawk (ban-drop-not-null protects readers that
--    assume the value): the only readers are DayApi (B3 changes it in this build) and the dw.v_final_submit_log view,
--    whose first_submitted_by is now null for a job submit.
-- 2. app.sync_rejected.code gains the three codes the contract 1.10 marks rejected, not retryable:
--    day_final_submitted (a memo, edit, void, return or collection captured at or after the zone-day's Final Submit),
--    ticket_state_invalid (a ticket comment or close review the ticket's state no longer allows) and issue_type_unknown
--    (an issue type that is not active on the business date). app.sync_quarantine.code gains after_final_submit (a row
--    captured before the lock that arrived after it: stored, held out of the figures, counted only when an admin
--    accepts it). Every existing code is kept, so every existing row stays valid.
-- 3. The comments of app.final_submit.late_rows and app.route_day.late_rows_after_final said the wrong thing (V0016:861
--    "still outstanding when the zone-day was submitted"): the count is of rows captured before the submit that arrived
--    after it. The dw columns that carry the same count are reworded too.
-- 4. Origin of a job submit. app.final_submit is a pass-1 table of the V0116 origin registry: its stamp and restamp read
--    only its user columns, outlet_id and route_id. A job row has no user, no outlet and no route, so the generic rule
--    would stamp every auto-submit 'production', even for a test zone (Gulshan-Banani, all of dev), and the stamp
--    overwrites what the writer sends, so the job cannot set it. The final_submit rule (app.derive_final_submit_origin):
--    a user's submit keeps the generic rule (the submitter's origin, as before V0135); a job submit takes the most
--    synthetic origin of the routes it listed in route_states, or of the zone's routes when it listed none. Only this
--    table reads the zone: app.derive_data_origin stays as it is, so a production memo or visit in a mixed zone stays
--    production. The registry gains derive_fn (null = app.derive_data_origin) and app.restamp_data_origin calls it, so
--    the insert stamp and the restamp apply the same rule (lead ruling R-F-09). The new stamp replaces V0116's trigger
--    of the same name (final_submit_stamp_origin), so one stamp runs per insert; the origin guard
--    (final_submit_guard_origin, BEFORE UPDATE) is unchanged and still refuses a direct re-tag by the API or worker.
-- 5. Admin module support (lead ruling R-F-11): /v1/support/tickets/** belongs to the new AdminModule support, so the
--    module CHECK of app.user_module_grant (V0089) gains it. Existing grants keep a listed module. Every existing ADMIN
--    gets support at w (lead ruling R-F-23: V0089 gave admins every module, and D-TSO-MAN-22 seeds the desk for ADMIN);
--    a SUPERADMIN narrows or removes it on the access page like any other module.
-- The five CHECKs are replaced or added NOT VALID here and validated in V0136 (separate transaction, squawk rule).
-- Grants: the worker runs the job; it may now insert final submits, and it may update app.push_token's revoked_at and
-- revoke_reason (a column grant, only_columns: never the token, its hash, the user or the device), so the push sender
-- can revoke a token FCM reports unregistered without stopping the other pushes (lead ruling R-F-07). It still
-- may not write app.audit_log (DbRolesTest pins that: the audit trail is written by the API and auth roles only); the
-- job's trace is the via = 'job' row itself and its zone_day.final_submitted event (R-F-07).

SET lock_timeout = '5s';

-- ---------- 1. final submit by the midnight job ----------
ALTER TABLE app.final_submit DROP CONSTRAINT final_submit_via_check;
ALTER TABLE app.final_submit ADD CONSTRAINT final_submit_via_check CHECK (via IN ('app','web','job')) NOT VALID;
-- squawk-ignore ban-drop-not-null
ALTER TABLE app.final_submit ALTER COLUMN submitted_by DROP NOT NULL;
ALTER TABLE app.final_submit ADD CONSTRAINT final_submit_job_actor CHECK ((via = 'job') = (submitted_by IS NULL)) NOT VALID;

-- ---------- 2. sync outcome codes ----------
ALTER TABLE app.sync_rejected DROP CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_rejected ADD CONSTRAINT sync_rejected_code_check CHECK (code IN (
  'scope_stale', 'config_version_unknown', 'parent_missing', 'outlet_pending_approval', 'price_list_unknown',
  'schema_invalid', 'unknown_record_type', 'unknown_sku', 'unknown_outlet', 'unknown_route', 'memo_no_invalid',
  'edit_not_allowed', 'chain_too_deep', 'voided_by_admin', 'lines_exceed_max', 'qty_invalid', 'price_invalid',
  'attendance_duplicate', 'server_error', 'retry_exhausted', 'unknown_gift', 'insufficient_points',
  'gift_photo_exists', 'day_final_submitted', 'ticket_state_invalid', 'issue_type_unknown')) NOT VALID;

ALTER TABLE app.sync_quarantine DROP CONSTRAINT sync_quarantine_code_check;
ALTER TABLE app.sync_quarantine ADD CONSTRAINT sync_quarantine_code_check CHECK (code IN (
  'arithmetic_mismatch', 'memo_no_duplicate', 'content_duplicate', 'checkout_too_early', 'payload_conflict',
  'business_date_out_of_window', 'scope_out_of_reach', 'no_assignment_on_date', 'device_integrity_failed',
  'device_not_enrolled', 'user_disabled', 'device_revoked', 'app_version_blocked', 'after_month_close',
  'programme_inactive', 'after_final_submit')) NOT VALID;

-- ---------- 4. origin of a job submit ----------
-- n is the row as jsonb. route_states is written by the server (DayApi, the job): [{route_id, state, ...}, ...]; an
-- element without a numeric route_id is ignored.
CREATE FUNCTION app.derive_final_submit_origin(n jsonb) RETURNS app.data_origin
LANGUAGE sql STABLE
AS $$
  SELECT CASE
    WHEN n ->> 'submitted_by' IS NOT NULL THEN app.derive_data_origin(n)
    ELSE GREATEST(app.derive_data_origin(n), coalesce(
      (SELECT max(r.data_origin) FROM app.route r
        WHERE r.id IN (SELECT (e ->> 'route_id')::bigint
                         FROM jsonb_array_elements(CASE WHEN jsonb_typeof(n -> 'route_states') = 'array' THEN n -> 'route_states' ELSE '[]'::jsonb END) e
                        WHERE jsonb_typeof(e) = 'object' AND e ->> 'route_id' ~ '^[0-9]{1,18}$')),
      (SELECT max(r.data_origin) FROM app.route r WHERE r.zone_id = (n ->> 'zone_id')::bigint)))
  END
$$;
COMMENT ON FUNCTION app.derive_final_submit_origin(jsonb) IS 'data_origin of a Final Submit: a user''s submit as app.derive_data_origin (its submitter); a job submit (submitted_by null) the most synthetic origin of the routes in its route_states, else of the zone''s routes (V0135).';

CREATE FUNCTION app.stamp_final_submit_origin() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.data_origin := app.derive_final_submit_origin(to_jsonb(NEW));
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.stamp_final_submit_origin() IS 'BEFORE INSERT on app.final_submit: sets data_origin by app.derive_final_submit_origin, overwriting any value the writer sent.';

CREATE OR REPLACE TRIGGER final_submit_stamp_origin BEFORE INSERT ON app.final_submit
  FOR EACH ROW EXECUTE FUNCTION app.stamp_final_submit_origin();

ALTER TABLE app.data_origin_capture ADD COLUMN derive_fn text;
COMMENT ON COLUMN app.data_origin_capture.derive_fn IS 'Function (schema-qualified, taking the row as jsonb) that derives the origin of a row of a table without a parent, when the table has its own rule; null = app.derive_data_origin. The table''s stamp trigger calls the same function.';
UPDATE app.data_origin_capture SET derive_fn = 'app.derive_final_submit_origin' WHERE table_name = 'final_submit';

-- Restamp: the registry's derive_fn where set (same function as V0121 otherwise).
CREATE OR REPLACE FUNCTION app.restamp_data_origin(p_from date DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  r       record;
  fn      text;
  args    text;
  has_bd  boolean;
  n       bigint;
  total   bigint := 0;
BEGIN
  FOR r IN SELECT * FROM app.data_origin_capture ORDER BY pass, table_name LOOP
    fn := CASE WHEN r.derive_fn IS NULL THEN 'app.derive_data_origin' ELSE to_regproc(r.derive_fn)::text END;
    IF fn IS NULL THEN
      RAISE EXCEPTION 'app.data_origin_capture: derive_fn % of app.% is not a function', r.derive_fn, r.table_name;
    END IF;
    args := CASE WHEN r.parent IS NULL THEN '' ELSE format(', %L, %L, %L', r.parent, r.child_col, r.parent_col) END;
    has_bd := EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = format('app.%I', r.table_name)::regclass
                        AND attname = 'business_date' AND NOT attisdropped);
    EXECUTE format('UPDATE app.%I x SET data_origin = %s(to_jsonb(x)%s)
                     WHERE %s data_origin IS DISTINCT FROM %s(to_jsonb(x)%s)',
                   r.table_name, fn, args, CASE WHEN has_bd THEN '($1 IS NULL OR business_date >= $1) AND' ELSE '' END, fn, args)
      USING p_from;
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
  END LOOP;
  -- dw: a write fires the table's origin trigger, which recomputes the value. Tables without one (dw.fact_memo_line,
  -- refreshed through its memo fact, V0121) are skipped.
  FOR r IN SELECT c.oid::regclass AS rel, EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = c.oid AND b.attname = 'business_date') AS bd
             FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE s.nspname = 'dw' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND a.attname = 'data_origin' AND NOT a.attisdropped
              AND EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname = c.relname || '_origin')
            ORDER BY 1 LOOP
    EXECUTE format('UPDATE %s SET data_origin = data_origin %s', r.rel, CASE WHEN r.bd THEN 'WHERE $1 IS NULL OR business_date >= $1' ELSE '' END)
      USING p_from;
  END LOOP;
  RETURN total;
END $$;
COMMENT ON FUNCTION app.restamp_data_origin(date) IS 'Re-derives data_origin on every registry table (rows on or after p_from; null = all) from the current tags of users, outlets, routes and parents, by the table''s derive_fn where the registry names one (final_submit, V0135), then refreshes dw (tables with an origin trigger; memo lines follow their memo fact). Owner only (the origin guard refuses other roles). Returns the number of app rows changed.';

-- ---------- 5. admin module support ----------
ALTER TABLE app.user_module_grant DROP CONSTRAINT user_module_grant_module_check;
ALTER TABLE app.user_module_grant ADD CONSTRAINT user_module_grant_module_check CHECK (module IN (
  'masterdata', 'routes', 'outlets', 'products', 'prices', 'users', 'targets', 'credentials', 'mdm', 'reports', 'config',
  'data_entry', 'indent', 'leaderboard', 'daily_tracking', 'support')) NOT VALID;
COMMENT ON COLUMN app.user_module_grant.module IS 'Admin-portal module (contract AdminModule): masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, data_entry, indent, leaderboard, daily_tracking or support (the support desk, V0135).';
-- R-F-23: existing ADMINs keep every module, so they keep the support desk too (idempotent; a later narrowing stays).
INSERT INTO app.user_module_grant (user_id, module, access)
SELECT u.id, 'support', 'w' FROM app.app_user u WHERE u.role = 'ADMIN'
ON CONFLICT (user_id, module) DO NOTHING;

-- ---------- grants ----------
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, only_columns, note) VALUES
  ('worker_rw', 'app', 'final_submit', 'INSERT', '{}', 'V0135: the midnight AutoFinalSubmitJob writes via=job submits (D-TSO-MAN-30)'),
  ('worker_rw', 'app', 'push_token', 'UPDATE', '{revoked_at,revoke_reason}', 'V0135: the push sender revokes a token FCM reports unregistered (R-F-07); the two revoke columns only');

-- ---------- 3. comments ----------
COMMENT ON COLUMN app.final_submit.via IS 'Channel the Final Submit came through: app (TSO phone), web (portal) or job (the midnight AutoFinalSubmitJob, V0135).';
COMMENT ON COLUMN app.final_submit.submitted_by IS 'Id of the user who made the Final Submit; null exactly when the midnight job made it (via job, CHECK final_submit_job_actor).';
COMMENT ON COLUMN app.final_submit.data_origin IS 'Origin of the row (production, pilot, test, seed): a user''s submit the submitter''s; a job submit the most synthetic of the routes in route_states (else of the zone''s routes); stamped at insert (app.derive_final_submit_origin, V0135); changes only through app.restamp_data_origin.';
COMMENT ON COLUMN app.final_submit.late_rows IS 'Rows captured before this Final Submit that arrived after it (late rows of the zone-day).';
COMMENT ON COLUMN app.route_day.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.agg_daily_route.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_daily_route.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_route_day.late_rows_after_final IS 'Rows of the route-day captured before the zone-day''s Final Submit that arrived after it.';
COMMENT ON COLUMN dw.v_final_submit_log.first_submitted_by IS 'User who made the first Final Submit (app.app_user id); null when the midnight job made it.';
COMMENT ON COLUMN app.sync_rejected.code IS 'Rejection code: a RecordOutcomeCode the contract marks rejected (price_invalid from V0112; day_final_submitted, ticket_state_invalid and issue_type_unknown from V0135) or the server-only retry_exhausted; quarantined codes go to app.sync_quarantine.';
COMMENT ON COLUMN app.sync_quarantine.code IS 'Quarantine code: a RecordOutcomeCode the contract marks quarantined (after_final_submit from V0135: captured before the zone-day''s Final Submit, arrived after it).';

SELECT app.apply_db_role_grants();
