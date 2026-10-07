-- V0024 config registry follow-ups (docs/24 s14a R17, R18 item 5; F-SR-028, F-SR-031/066, F-SR-073):
--   1. cfg.sync.reconcile_types becomes a flat object keyed 'ROLE.row' (for example 'SR.sale') whose values are arrays
--      of record-type strings, so it fits ConfigValueJson (not widened, R17). The default and every open or future
--      stored value are reshaped; pending and scheduled config changes carrying the key are reshaped too.
--   2. cfg.bundle.outlet_fields is delivered by the server only (it masks outlet columns; never sent to the phone).
--   3. New device keys cfg.print.confirm_after_print, cfg.memo.reprint_watermark, cfg.sale.require_printer_before_sale.
--   4. Validates the two content_item constraints V0023 added NOT VALID (separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.content_item VALIDATE CONSTRAINT content_item_asset_id_fkey;
ALTER TABLE app.content_item VALIDATE CONSTRAINT content_item_assigned_scope_array;

-- ---------- 1. cfg.sync.reconcile_types: nested {ROLE: {row: [types]}} -> flat {"ROLE.row": [types]} ----------
-- Flattening rule: a member whose value is an object contributes one entry per inner member ('ROLE.row'); a member
-- whose value is an array is kept, and on a role-scoped value a bare 'row' key gets the role's prefix. Every entry is
-- kept; a value that is already flat is left as it is.
UPDATE app.cfg_key
   SET default_value = (SELECT jsonb_object_agg(e.key || '.' || i.key, i.value)
                          FROM jsonb_each(default_value) e, jsonb_each(e.value) i),
       updated_at = now()
 WHERE key = 'cfg.sync.reconcile_types'
   AND EXISTS (SELECT 1 FROM jsonb_each(default_value) e WHERE jsonb_typeof(e.value) = 'object');

DO $$
DECLARE
  sys   bigint := (SELECT id FROM app.app_user WHERE username = 'aron.system');
  ver   bigint;
  r     record;
  cut   timestamptz;
  flat  jsonb;
BEGIN
  FOR r IN
    SELECT v.*, rd.role AS role_name
      FROM app.cfg_value v
      LEFT JOIN app.role_def rd ON v.scope_type = 'role' AND rd.ordinal = v.scope_id
     WHERE v.key = 'cfg.sync.reconcile_types'
       AND (v.effective_to IS NULL OR v.effective_to > now())
       AND jsonb_typeof(v.value) = 'object'
     ORDER BY v.id
  LOOP
    SELECT coalesce(jsonb_object_agg(x.k, x.val), '{}'::jsonb) INTO flat FROM (
      SELECT e.key || '.' || i.key AS k, i.value AS val
        FROM jsonb_each(r.value) e, jsonb_each(e.value) i WHERE jsonb_typeof(e.value) = 'object'
      UNION ALL
      SELECT CASE WHEN position('.' IN e.key) = 0 AND r.role_name IS NOT NULL THEN r.role_name || '.' || e.key ELSE e.key END,
             e.value
        FROM jsonb_each(r.value) e WHERE jsonb_typeof(e.value) <> 'object') x;
    CONTINUE WHEN flat = r.value;

    IF ver IS NULL THEN                      -- one config version for the whole reshape, only when a row needs it
      ver := (SELECT coalesce(max(config_version), 0) + 1 FROM app.cfg_version);
      INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
      VALUES (ver, 'change', sys, 'V0024: cfg.sync.reconcile_types reshaped to the flat ROLE.row shape (docs/24 s14a R17)', 1);
    END IF;
    -- The reshaped row takes over from now (or from the old row's own start when that lies in the future); the old
    -- row is closed at the same instant, so the validity ranges stay gap-free and never overlap. Rows are close-only,
    -- so a future-dated old row keeps the first microsecond of its range.
    cut := greatest(now(), r.effective_from + interval '1 microsecond');
    UPDATE app.cfg_value SET effective_to = cut, superseded_in_version = ver WHERE id = r.id;
    INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, change_id, created_by, reason)
    VALUES (r.key, r.scope_type, r.scope_id, flat, cut, r.effective_to, ver, r.change_id, sys,
            left('V0024 reshape (R17) of value ' || r.id || ': ' || r.reason, 500));
  END LOOP;

  -- Changes not yet applied: reshape the item values in place (applied history stays as it was).
  UPDATE app.cfg_change c
     SET items = (SELECT jsonb_agg(
                    CASE WHEN it ->> 'key' = 'cfg.sync.reconcile_types' AND jsonb_typeof(it -> 'value') = 'object'
                              AND EXISTS (SELECT 1 FROM jsonb_each(it -> 'value') e WHERE jsonb_typeof(e.value) = 'object')
                         THEN jsonb_set(it, '{value}', (
                                SELECT jsonb_object_agg(x.k, x.val) FROM (
                                  SELECT e.key || '.' || i.key AS k, i.value AS val
                                    FROM jsonb_each(it -> 'value') e, jsonb_each(e.value) i WHERE jsonb_typeof(e.value) = 'object'
                                  UNION ALL
                                  SELECT e.key, e.value FROM jsonb_each(it -> 'value') e WHERE jsonb_typeof(e.value) <> 'object') x))
                         ELSE it END ORDER BY n)
                  FROM jsonb_array_elements(c.items) WITH ORDINALITY AS a(it, n))
   WHERE c.status IN ('pending_approval', 'scheduled')
     AND EXISTS (SELECT 1 FROM jsonb_array_elements(c.items) it
                  WHERE it ->> 'key' = 'cfg.sync.reconcile_types'
                    AND jsonb_typeof(it -> 'value') = 'object'
                    AND EXISTS (SELECT 1 FROM jsonb_each(it -> 'value') e WHERE jsonb_typeof(e.value) = 'object'));
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
