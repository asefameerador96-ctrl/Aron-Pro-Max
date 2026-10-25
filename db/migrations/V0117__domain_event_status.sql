-- V0117 domain-event status (lead row 3, 2026-10-25; D-DATA-OUTBOX-ROLE-01: events are thin notices, the lake reads the
-- dw views; data-architecture review Medium 5): the catalogue says which types have a producer today.
-- 1. app.domain_event_status (live, planned) and app.domain_event_type.status, default planned (a type added later is
--    planned until its producer ships). Live today, from the docs pass's grep of backend/: memo.created, memo.voided,
--    visit.closed, stock.moved. The other seven stay planned: day_exception.decided, due.collected, outlet.changed,
--    risk_signal.changed, route_day.state_changed, target.revised, tracking_action.created.
-- 2. The catalogue guard (V0017 guard_synced_row) lets status change (a producer ships, or is withdrawn); the published
--    payload schema stays fixed.
-- 3. New planned type memo.superseded v1: an edit replaced a memo (the superseded memo leaves the day figures, the
--    superseding memo counts once); producer backend:sync once it emits it. Required keys are not enforced (V0018 rule).
-- docs/data-events.md renders the status (DataEventsTest; tools/data-dictionary/render.sh). D-DB-EVENT-STATUS-01.

SET lock_timeout = '5s';

CREATE TYPE app.domain_event_status AS ENUM ('live', 'planned');
COMMENT ON TYPE app.domain_event_status IS 'Whether a domain-event type has a producer today (live) or not yet (planned).';
ALTER TABLE app.domain_event_type ADD COLUMN status app.domain_event_status NOT NULL DEFAULT 'planned';
COMMENT ON COLUMN app.domain_event_type.status IS 'live: a backend producer writes this type today; planned: catalogued, no producer yet (consumers must not wait for it).';

CREATE OR REPLACE TRIGGER domain_event_type_fixed BEFORE DELETE OR UPDATE ON app.domain_event_type
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('description', '=deprecated_at', 'enforce_required', 'status');

UPDATE app.domain_event_type SET status = 'live'
 WHERE event_type IN ('memo.created', 'memo.voided', 'visit.closed', 'stock.moved');

INSERT INTO app.domain_event_type
  (event_type, payload_version, aggregate_type, aggregate_id_is, producer, description, payload_schema, introduced_in) VALUES
('memo.superseded', 1, 'memo', 'memo.client_uuid', 'backend:sync',
 'An edit replaced a memo: the superseded memo leaves the day figures and the superseding memo counts once; both business dates are recomputed.',
 '{"type":"object","required":["memo_uuid","superseded_by_uuid","route_id","business_date"],"properties":{
   "memo_uuid":{"type":"string","format":"uuid","description":"the superseded memo"},
   "superseded_by_uuid":{"type":"string","format":"uuid","description":"the memo whose supersedes_client_uuid is memo_uuid (app.memo.superseded_by_client_uuid)"},
   "route_id":{"type":["integer","null"],"description":"null for a memo without a route (contract 1.9 MemoView.route_id)"},
   "business_date":{"type":"string","format":"date","description":"business date of the superseded memo"},
   "superseding_business_date":{"type":["string","null"],"format":"date","description":"business date of the superseding memo when it differs"}}}', 'V0117');

SELECT app.apply_db_role_grants();
