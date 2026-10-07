-- V0017 versioned domain-event payloads (docs/31 s3, docs/24 s6.3 and s12.5 item 4).
--
-- Every outbox row names the version of its payload, and every (event_type, payload_version) pair is a row of the
-- catalogue app.domain_event_type, so an undocumented event cannot be written. docs/data-events.md is rendered from the
-- catalogue (DataEventsTest).
--
-- Rules:
-- - A payload carries ids, codes, counts and amounts, never names, phone numbers, NIDs or coordinates, so the outbox
--   stays pii: none and a consumer reads personal fields from the source row under its own grants.
-- - Adding an optional key keeps the version. Removing or renaming a key, changing its type or meaning, or making a
--   key required needs a new version row in a new migration. A published version's schema never changes (guard).
-- - Producers write the newest version that is not deprecated; consumers handle every version that is not deprecated.
--   deprecated_at is set once no producer writes that version. Old rows stay readable for their retention.
-- - The insert trigger refuses a payload that is not a JSON object or lacks a key the schema lists as required.

CREATE TABLE app.domain_event_type (
  event_type       text NOT NULL CHECK (event_type ~ '^[a-z_]+\.[a-z_]+$'),
  payload_version  smallint NOT NULL CHECK (payload_version >= 1),
  aggregate_type   text NOT NULL CHECK (aggregate_type ~ '^[a-z_]+$'),
  aggregate_id_is  text NOT NULL,
  producer         text NOT NULL,
  description      text NOT NULL,
  payload_schema   jsonb NOT NULL CHECK (payload_schema ->> 'type' = 'object'
                                     AND jsonb_typeof(payload_schema -> 'required') = 'array'
                                     AND jsonb_typeof(payload_schema -> 'properties') = 'object'),
  introduced_in    text NOT NULL,                       -- the migration that added the version
  deprecated_at    timestamptz,
  PRIMARY KEY (event_type, payload_version)
);
-- Published versions are fixed: only the description may be reworded and deprecated_at set once; nothing is deleted.
CREATE TRIGGER domain_event_type_fixed BEFORE UPDATE OR DELETE ON app.domain_event_type
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('description', '=deprecated_at');

ALTER TABLE app.domain_event ADD COLUMN payload_version smallint NOT NULL DEFAULT 1;
ALTER TABLE app.domain_event ADD CONSTRAINT domain_event_payload_object CHECK (jsonb_typeof(payload) = 'object');

-- Refuses an event whose payload lacks a required key of its catalogued version (the foreign key below refuses an
-- uncatalogued type or version; this trigger runs first and reports that case too).
CREATE FUNCTION app.domain_event_check_payload() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  req jsonb;
BEGIN
  SELECT t.payload_schema -> 'required' INTO req
    FROM app.domain_event_type t
   WHERE t.event_type = NEW.event_type AND t.payload_version = NEW.payload_version;
  IF req IS NULL THEN
    RAISE EXCEPTION 'app.domain_event: % v% is not in app.domain_event_type', NEW.event_type, NEW.payload_version
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF jsonb_typeof(NEW.payload) = 'object'
     AND NOT (NEW.payload ?& ARRAY(SELECT jsonb_array_elements_text(req))) THEN
    RAISE EXCEPTION 'app.domain_event: % v% payload lacks required keys %', NEW.event_type, NEW.payload_version,
      ARRAY(SELECT k FROM jsonb_array_elements_text(req) k WHERE NOT NEW.payload ? k)
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER domain_event_payload_required BEFORE INSERT ON app.domain_event
  FOR EACH ROW EXECUTE FUNCTION app.domain_event_check_payload();

-- Version 1 of the outbox events of docs/24 s12.5 item 4.
INSERT INTO app.domain_event_type
  (event_type, payload_version, aggregate_type, aggregate_id_is, producer, description, payload_schema, introduced_in) VALUES
('memo.created', 1, 'memo', 'memo.client_uuid', 'backend:sync',
 'A memo was accepted (ingest or web entry). The projector adds it to the day aggregates of its route, outlet and SKUs.',
 '{"type":"object","required":["memo_uuid","route_id","outlet_id","user_id","memo_kind","net_mtk"],"properties":{
   "memo_uuid":{"type":"string","format":"uuid","description":"memo.client_uuid"},
   "visit_uuid":{"type":["string","null"],"format":"uuid","description":"memo.visit_client_uuid"},
   "route_id":{"type":"integer"},"outlet_id":{"type":"integer"},"user_id":{"type":"integer","description":"the SR who sold"},
   "acting_for_user_id":{"type":["integer","null"]},
   "memo_kind":{"type":"string","description":"memo.memo_kind"},
   "gross_mtk":{"type":"integer"},"net_mtk":{"type":"integer","description":"net amount, milli-taka"},
   "paid_mtk":{"type":"integer"},"due_mtk":{"type":"integer"},"line_count":{"type":"integer"}}}', 'V0017'),
('memo.voided', 1, 'memo', 'memo.client_uuid', 'backend:sync',
 'A memo was voided (data-void tombstone or Final Submit void). The projector removes it from the day aggregates.',
 '{"type":"object","required":["memo_uuid","route_id","voided_at"],"properties":{
   "memo_uuid":{"type":"string","format":"uuid"},"route_id":{"type":"integer"},
   "voided_at":{"type":"string","format":"date-time"},
   "reason_code":{"type":["string","null"],"description":"code_list void_reason"}}}', 'V0017'),
('visit.closed', 1, 'visit', 'visit.client_uuid', 'backend:sync',
 'A visit was accepted with its geo verdict. The projector counts calls, strike rate and geo validity.',
 '{"type":"object","required":["visit_uuid","route_id","outlet_id","user_id","visit_kind","verdict"],"properties":{
   "visit_uuid":{"type":"string","format":"uuid"},"route_id":{"type":"integer"},"outlet_id":{"type":"integer"},
   "user_id":{"type":"integer"},"visit_kind":{"type":"string","description":"visit.visit_kind"},
   "planned":{"type":"boolean"},"verdict":{"type":"string","description":"visit.verdict"},
   "fix_is_mock":{"type":["boolean","null"]},"distance_m":{"type":["number","null"]}}}', 'V0017'),
('route_day.state_changed', 1, 'route_day', 'route_day.id', 'backend:sync',
 'A route-day moved state (logged in, in field, synced, submitted, final submitted, voided).',
 '{"type":"object","required":["route_day_id","route_id","from_state","to_state"],"properties":{
   "route_day_id":{"type":"integer"},"route_id":{"type":"integer"},
   "from_state":{"type":["string","null"]},"to_state":{"type":"string"},
   "submit_cycle":{"type":"integer"},"acting_user_id":{"type":["integer","null"]}}}', 'V0017'),
('outlet.changed', 1, 'outlet', 'outlet.id', 'backend:masterdata',
 'An outlet was created, edited, moved, merged or closed. Names, phones and coordinates are not in the payload.',
 '{"type":"object","required":["outlet_id","change"],"properties":{
   "outlet_id":{"type":"integer"},
   "change":{"type":"string","enum":["created","updated","location_confirmed","route_changed","merged","closed","reopened"]},
   "route_id":{"type":["integer","null"]},"zone_id":{"type":["integer","null"]},
   "merged_into_id":{"type":["integer","null"]},
   "fields":{"type":"array","items":{"type":"string"},"description":"outlet columns that changed"}}}', 'V0017'),
('stock.moved', 1, 'stock_movement', 'stock_movement.client_uuid', 'backend:sync',
 'A stock ledger row was accepted (issue, return, adjustment, damaged, short).',
 '{"type":"object","required":["movement_uuid","route_id","user_id","sku_id","kind","qty_base"],"properties":{
   "movement_uuid":{"type":"string","format":"uuid"},"route_id":{"type":"integer"},"user_id":{"type":"integer"},
   "sku_id":{"type":"integer"},"kind":{"type":"string","description":"stock_movement.kind"},
   "qty_base":{"type":"integer","description":"signed quantity in base units"}}}', 'V0017'),
('target.revised', 1, 'target_set', 'target_set.id', 'backend:analytics',
 'A target revision was committed (Phase 2 target engine; deferred programme, docs/27).',
 '{"type":"object","required":["target_set_id","revision_id","revision_no"],"properties":{
   "target_set_id":{"type":"integer"},"revision_id":{"type":"integer"},"revision_no":{"type":"integer"},
   "months":{"type":"array","items":{"type":"string","format":"date"}}}}', 'V0017');

ALTER TABLE app.domain_event ADD CONSTRAINT domain_event_type_version
  FOREIGN KEY (event_type, payload_version) REFERENCES app.domain_event_type (event_type, payload_version);

-- Grants: the catalogue is written by migrations only; every role that writes or reads events may read it.
UPDATE app.db_role_grant SET except_tables = except_tables || '{domain_event_type}'
 WHERE role = 'api_rw' AND schema_name = 'app' AND object IN ('*', '*/update');
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, note) VALUES
  ('api_rw', 'app', 'domain_event_type', 'SELECT', '{}', 'event catalogue, checked on every outbox insert');
GRANT EXECUTE ON FUNCTION app.domain_event_check_payload() TO api_rw, worker_rw;
SELECT app.apply_db_role_grants();

COMMENT ON TABLE app.domain_event_type IS 'Catalogue of domain-event types and payload versions; every outbox row must name one (docs/data-events.md).
owner: db | capture: REFERENCE | retention: master | pii: none';
COMMENT ON COLUMN app.domain_event_type.event_type IS 'Event name, aggregate.verb, for example memo.created.';
COMMENT ON COLUMN app.domain_event_type.payload_version IS 'Version of the payload shape; a breaking change adds a new version row.';
COMMENT ON COLUMN app.domain_event_type.aggregate_type IS 'aggregate_type the producer writes with this event.';
COMMENT ON COLUMN app.domain_event_type.aggregate_id_is IS 'Which column aggregate_id holds, for example memo.client_uuid.';
COMMENT ON COLUMN app.domain_event_type.producer IS 'Backend module that writes the event.';
COMMENT ON COLUMN app.domain_event_type.description IS 'What happened and what consumers do with it.';
COMMENT ON COLUMN app.domain_event_type.payload_schema IS 'JSON Schema of the payload; its required keys are enforced on insert.';
COMMENT ON COLUMN app.domain_event_type.introduced_in IS 'Migration that added this version.';
COMMENT ON COLUMN app.domain_event_type.deprecated_at IS 'UTC instant producers stopped writing this version; null while current.';
COMMENT ON COLUMN app.domain_event.payload_version IS 'Version of the payload shape, a row of app.domain_event_type with event_type.';
COMMENT ON COLUMN app.domain_event.payload IS 'Event payload (JSON object) in the shape of its catalogued version; ids, codes and amounts only, no personal data.';
COMMENT ON FUNCTION app.domain_event_check_payload() IS 'Refuses an outbox row of an uncatalogued type or version, or whose payload lacks a required key.';
