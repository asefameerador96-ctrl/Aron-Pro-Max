-- V0207 (PROVISIONAL number, lead 2026-11-18): the waiting-item sweep (D-LOY-API-29, -30).
-- (1) the three cfg keys that stop or slow the sweep without a deploy:
--   cfg.sync.waiting_sweep_enabled    bool, default true  (off = the API process runs no pass)
--   cfg.sync.waiting_sweep_interval_s int, default 60, 10..3600 (seconds between the end of one pass and the start of the next)
--   cfg.sync.waiting_sweep_batch      int, default 200, 1..1000 (waiting items examined per pass)
-- (2) the TYPED marker of a waiting item (it replaces the detail-text prefix of the first build): a child released with an accepted parent whose OTHER parent is not stored yet.
--   waiting_since          non-null = the item waits (open items only); null = an ordinary held item
--   awaited_uuid           the parent it waits for
--   accepted_parent_uuid   the parent whose accept released it into waiting
--   waiting_release_code   the override the reviewer's accept carried for THIS child at that time (null for a child of another uploader or device, and for a matched backfill child):
--                          the automatic release carries exactly that and nothing else
--   awaited_refused        the awaited parent is refused or discarded (refreshed at every pass, so it is never stale)
--   awaited_field          the field of the awaited parent (a typed column, not read from the detail text)
--   waiting_checked_at     when the sweep last examined it: passes take the least recently examined first (no head-of-line starvation)
-- The marker belongs to ONE open wait: a trigger clears it whenever the item is closed, re-opened or changes its code (so a record returned to the device and held again starts clean and the
-- sweep can never act on a stale marker or waiver), and a CHECK says a non-open row never carries one.
-- Existing waiting items (the detail-prefix form of train 115) are backfilled with NO waiver (the safe side: a child that needed one becomes an ordinary held item for a reviewer).
-- No data guard that can raise: plain INSERT ... ON CONFLICT DO NOTHING, nullable columns without a default, a cast only on text that matches the uuid pattern.
SET lock_timeout = '5s';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.sync.waiting_sweep_enabled', 'sync', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global']::text[], 2, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Release quarantined children that wait for another parent automatically once that parent is stored (D-LOY-API-29); off leaves them to the reviewer.'),
  ('cfg.sync.waiting_sweep_interval_s', 'sync', 'S', 'int', '60'::jsonb, '{"min": 10, "max": 3600}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Seconds between two passes of the waiting-item sweep (D-LOY-API-29).'),
  ('cfg.sync.waiting_sweep_batch', 'sync', 'S', 'int', '200'::jsonb, '{"min": 1, "max": 1000}'::jsonb, NULL, ARRAY['global']::text[], 1, NULL, 'B', 'server', false, false, 'cfg.edit.ops',
   'Waiting items examined per pass of the waiting-item sweep; a larger backlog is worked off over several passes (D-LOY-API-29).')
ON CONFLICT (key) DO NOTHING;

ALTER TABLE app.sync_quarantine
  ADD COLUMN IF NOT EXISTS waiting_since timestamptz,
  ADD COLUMN IF NOT EXISTS awaited_uuid uuid,
  ADD COLUMN IF NOT EXISTS awaited_field text,
  ADD COLUMN IF NOT EXISTS accepted_parent_uuid uuid,
  ADD COLUMN IF NOT EXISTS waiting_release_code text,
  ADD COLUMN IF NOT EXISTS awaited_refused boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS waiting_checked_at timestamptz;
COMMENT ON COLUMN app.sync_quarantine.waiting_since IS 'Non-null = an open item that waits for another parent (D-LOY-API-30); null = an ordinary held item.';
COMMENT ON COLUMN app.sync_quarantine.awaited_uuid IS 'The parent a waiting item waits for (D-LOY-API-30).';
COMMENT ON COLUMN app.sync_quarantine.awaited_field IS 'The field of the parent a waiting item waits for, e.g. visit_client_uuid (D-LOY-API-31).';
COMMENT ON COLUMN app.sync_quarantine.accepted_parent_uuid IS 'The parent whose accept released this child into waiting (D-LOY-API-30).';
COMMENT ON COLUMN app.sync_quarantine.waiting_release_code IS 'The override the reviewer''s accept carried for this child at that time: the code of a same-uploader, same-device child; null otherwise (D-LOY-API-30). The automatic release carries exactly this.';
COMMENT ON COLUMN app.sync_quarantine.awaited_refused IS 'The awaited parent is refused or discarded; refreshed at every sweep pass (D-LOY-API-30).';
COMMENT ON COLUMN app.sync_quarantine.waiting_checked_at IS 'When the sweep last examined this waiting item; the least recently examined go first (D-LOY-API-30).';

-- backfill of the detail-prefix form: 'waits for another parent: <field> <awaited uuid> ... (parent <accepted uuid> was accepted)'; no waiver
UPDATE app.sync_quarantine
   SET waiting_since = COALESCE(received_at, now()),
       awaited_field = substring(detail FROM '^waits for another parent: ([A-Za-z0-9_]+) '),
       awaited_uuid = CAST(substring(detail FROM '^waits for another parent: [A-Za-z0-9_]+ ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})') AS uuid),
       accepted_parent_uuid = CAST(substring(detail FROM '\(parent ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) was accepted\)') AS uuid)
 WHERE status = 'open' AND detail LIKE 'waits for another parent: %'
   AND detail ~ '^waits for another parent: [A-Za-z0-9_]+ [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
   AND detail ~ '\(parent [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} was accepted\)';

-- an old-form waiting item that does not name its awaited parent cannot be swept: say so plainly (it stays an ordinary held item for a reviewer)
UPDATE app.sync_quarantine
   SET detail = 'held after its first parent was accepted; the awaited parent is not named (train 115 form)'
 WHERE status = 'open' AND waiting_since IS NULL AND detail LIKE 'waits for another parent: %';

-- the marker belongs to ONE open wait: closing, re-opening or changing the code of an item clears it (D-LOY-API-31)
CREATE OR REPLACE FUNCTION app.sync_quarantine_clear_waiting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.waiting_since := NULL; NEW.awaited_uuid := NULL; NEW.awaited_field := NULL; NEW.accepted_parent_uuid := NULL;
  NEW.waiting_release_code := NULL; NEW.awaited_refused := false; NEW.waiting_checked_at := NULL;
  RETURN NEW;
END $$;
CREATE TRIGGER sync_quarantine_clear_waiting BEFORE UPDATE ON app.sync_quarantine FOR EACH ROW
  WHEN (OLD.waiting_since IS NOT NULL AND (NEW.status <> 'open' OR OLD.status <> 'open' OR NEW.code IS DISTINCT FROM OLD.code))
  EXECUTE FUNCTION app.sync_quarantine_clear_waiting();
-- a non-open row never carries a marker (the column is new, so no existing row can break it: NOT VALID then VALIDATE in the same migration)
ALTER TABLE app.sync_quarantine ADD CONSTRAINT sync_quarantine_waiting_open CHECK (waiting_since IS NULL OR status = 'open') NOT VALID;
-- the validation reads the table once; the table holds review items only and the column is new (every row is null), so it is instant; the migration runs in one transaction
-- squawk-ignore constraint-missing-not-valid
ALTER TABLE app.sync_quarantine VALIDATE CONSTRAINT sync_quarantine_waiting_open;

-- the sweep's order (least recently examined first) and the queue's count read only waiting open items
-- Why the ignore below: the rule asks for CREATE INDEX CONCURRENTLY on an existing table; app.sync_quarantine holds review items only (a handful to some thousands of rows, so a plain build takes
-- milliseconds), the migration runs in one transaction (CONCURRENTLY is not possible there), lock_timeout above bounds the wait, and earlier migrations (V0193) use the same inline ignore.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX IF NOT EXISTS sync_quarantine_waiting ON app.sync_quarantine (waiting_checked_at NULLS FIRST, id) WHERE waiting_since IS NOT NULL AND status = 'open';
