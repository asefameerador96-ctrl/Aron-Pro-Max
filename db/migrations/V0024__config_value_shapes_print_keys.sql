-- V0024 config registry follow-ups (docs/24 s14a R17, R18 item 5; F-SR-028, F-SR-031/066, F-SR-073):
--   1. cfg.sync.reconcile_types becomes a flat object keyed 'ROLE.row' (for example 'SR.sale') whose values are arrays
--      of record-type strings, so it fits ConfigValueJson (not widened, R17). The default and every open or future
--      stored value are reshaped (in force: closed and replaced; not yet in force: rewritten); pending and scheduled
--      config changes carrying the key are reshaped too. Registry defaults and new keys are not versioned: phones get
--      them with their next full config (day bundle), not through the delta.
--   2. cfg.bundle.outlet_fields is delivered by the server only (it masks outlet columns; never sent to the phone).
--   3. New device keys cfg.print.confirm_after_print, cfg.memo.reprint_watermark, cfg.sale.require_printer_before_sale.
--   4. Validates the two content_item constraints V0023 added NOT VALID (separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.content_item VALIDATE CONSTRAINT content_item_asset_id_fkey;
ALTER TABLE app.content_item VALIDATE CONSTRAINT content_item_assigned_scope_array;

-- ---------- 1. cfg.sync.reconcile_types: nested {ROLE: {row: [types]}} -> flat {"ROLE.row": [types]} ----------
-- Flattening rule (app.cfg_reconcile_types_flat): a member whose value is an object contributes one entry per inner
-- member ('ROLE.row'); any other member is kept, and on a role-scoped value a bare 'row' key gets the role's prefix.
-- Every entry is kept; a value that is already flat comes back unchanged.
CREATE FUNCTION app.cfg_reconcile_types_flat(v jsonb, role_name text DEFAULT NULL) RETURNS jsonb
LANGUAGE sql IMMUTABLE
AS $$
  SELECT CASE WHEN v IS NULL OR jsonb_typeof(v) <> 'object' THEN v ELSE coalesce((
    SELECT jsonb_object_agg(x.k, x.val) FROM (
      SELECT e.key || '.' || i.key AS k, i.value AS val
        FROM jsonb_each(v) e, jsonb_each(e.value) i WHERE jsonb_typeof(e.value) = 'object'
      UNION ALL
      SELECT CASE WHEN position('.' IN e.key) = 0 AND role_name IS NOT NULL THEN role_name || '.' || e.key ELSE e.key END, e.value
        FROM jsonb_each(v) e WHERE jsonb_typeof(e.value) <> 'object') x), '{}'::jsonb) END
$$;
COMMENT ON FUNCTION app.cfg_reconcile_types_flat(jsonb, text) IS 'V0024 (R17): the flat ROLE.row shape of a cfg.sync.reconcile_types value; role_name prefixes bare keys of a role-scoped value.';

UPDATE app.cfg_key SET default_value = app.cfg_reconcile_types_flat(default_value), updated_at = now()
 WHERE key = 'cfg.sync.reconcile_types' AND default_value IS DISTINCT FROM app.cfg_reconcile_types_flat(default_value);

DO $$
DECLARE
  sys      bigint := (SELECT id FROM app.app_user WHERE username = 'aron.system');
  ver      bigint;
  r        record;
  flat     jsonb;
BEGIN
  IF EXISTS (SELECT 1 FROM app.cfg_value v
              WHERE v.key = 'cfg.sync.reconcile_types' AND (v.effective_to IS NULL OR v.effective_to > now())
                AND v.value IS DISTINCT FROM app.cfg_reconcile_types_flat(v.value,
                      (SELECT rd.role FROM app.role_def rd WHERE v.scope_type = 'role' AND rd.ordinal = v.scope_id))) THEN
    ver := (SELECT coalesce(max(config_version), 0) + 1 FROM app.cfg_version);   -- one version for the whole reshape
    INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
    VALUES (ver, 'change', sys, 'V0024: cfg.sync.reconcile_types reshaped to the flat ROLE.row shape (docs/24 s14a R17)', 1);

    -- In force now: history is kept. The reshaped row takes over from now and keeps the old row's end; the old row is
    -- closed at the same instant, so the validity ranges stay gap-free and never overlap.
    FOR r IN
      SELECT v.*, rd.role AS role_name
        FROM app.cfg_value v
        LEFT JOIN app.role_def rd ON v.scope_type = 'role' AND rd.ordinal = v.scope_id
       WHERE v.key = 'cfg.sync.reconcile_types' AND v.effective_from <= now()
         AND (v.effective_to IS NULL OR v.effective_to > now())
       ORDER BY v.id
    LOOP
      flat := app.cfg_reconcile_types_flat(r.value, r.role_name);
      CONTINUE WHEN flat = r.value;
      UPDATE app.cfg_value SET effective_to = now(), superseded_in_version = ver WHERE id = r.id;
      INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, change_id, created_by, reason)
      VALUES (r.key, r.scope_type, r.scope_id, flat, now(), r.effective_to, ver, r.change_id, sys,
              left('V0024 reshape (R17) of value ' || r.id || ': ' || r.reason, 500));
    END LOOP;

    -- Not yet in force: no one ever resolved it, so it is reshaped in place and moved to the new version (a closed stub
    -- would still be listed as scheduled). Rows are close-only, so the guard is lifted for this one statement, inside
    -- the migration's transaction.
    IF EXISTS (SELECT 1 FROM app.cfg_value v
                WHERE v.key = 'cfg.sync.reconcile_types' AND v.effective_from > now()
                  AND v.value IS DISTINCT FROM app.cfg_reconcile_types_flat(v.value,
                        (SELECT rd.role FROM app.role_def rd WHERE v.scope_type = 'role' AND rd.ordinal = v.scope_id))) THEN
      ALTER TABLE app.cfg_value DISABLE TRIGGER cfg_value_immutable;
      UPDATE app.cfg_value v
         SET value = app.cfg_reconcile_types_flat(v.value, (SELECT rd.role FROM app.role_def rd WHERE v.scope_type = 'role' AND rd.ordinal = v.scope_id)),
             config_version = ver,
             reason = left('V0024 reshape (R17), not yet in force: ' || v.reason, 500)
       WHERE v.key = 'cfg.sync.reconcile_types' AND v.effective_from > now()
         AND v.value IS DISTINCT FROM app.cfg_reconcile_types_flat(v.value,
               (SELECT rd.role FROM app.role_def rd WHERE v.scope_type = 'role' AND rd.ordinal = v.scope_id));
      ALTER TABLE app.cfg_value ENABLE TRIGGER cfg_value_immutable;
    END IF;
  END IF;

  -- Changes not yet applied: reshape the item values in place (applied history stays as it was).
  UPDATE app.cfg_change c
     SET items = (SELECT jsonb_agg(
                    CASE WHEN it ->> 'key' = 'cfg.sync.reconcile_types' AND it ? 'value'
                         THEN jsonb_set(it, '{value}', app.cfg_reconcile_types_flat(it -> 'value',
                                (SELECT rd.role FROM app.role_def rd
                                  WHERE it ->> 'scope_type' = 'role' AND rd.ordinal::text = it ->> 'scope_id')))
                         ELSE it END ORDER BY n)
                  FROM jsonb_array_elements(c.items) WITH ORDINALITY AS a(it, n))
   WHERE c.status IN ('pending_approval', 'scheduled')
     AND EXISTS (SELECT 1 FROM jsonb_array_elements(c.items) it
                  WHERE it ->> 'key' = 'cfg.sync.reconcile_types' AND it ? 'value'
                    AND (it -> 'value') IS DISTINCT FROM app.cfg_reconcile_types_flat(it -> 'value',
                          (SELECT rd.role FROM app.role_def rd WHERE it ->> 'scope_type' = 'role' AND rd.ordinal::text = it ->> 'scope_id')));
END $$;

-- ---------- 2. cfg.bundle.outlet_fields: server only ----------
UPDATE app.cfg_key SET delivery = 'server', updated_at = now() WHERE key = 'cfg.bundle.outlet_fields';

-- ---------- 3. new device keys (same columns and permissions as cfg.memo.reprint_max and cfg.print.template_version) ----------
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.print.confirm_after_print', 'print', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.field',
   'After a memo prints, the SR confirms the slip came out before the sale closes (F-SR-073).'),
  ('cfg.memo.reprint_watermark', 'memo', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.field',
   'A reprinted memo carries the reprint watermark and count (F-SR-031, F-SR-066).'),
  ('cfg.sale.require_printer_before_sale', 'sale', 'S', 'bool', 'false'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'device', false, false, 'cfg.edit.field',
   'A sale can start only when a paired printer is connected (F-SR-028).');

SELECT app.apply_db_role_grants();   -- the new helper function: no PUBLIC execute, same rights as the other app functions
