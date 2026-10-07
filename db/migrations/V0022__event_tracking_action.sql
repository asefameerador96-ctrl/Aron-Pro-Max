-- V0022 catalogue row for the analytics producer's tracking_action.created event (DailyTrackingApi, F-API daily
-- tracking): an uncatalogued type is refused by the V0017/V0018 insert trigger. The payload carries a free-text note,
-- which the outbox rule (ids, codes and amounts only) does not allow; it is catalogued as written so selling is not
-- blocked, and docs/requests/db-event-tracking-action-note.md asks the producer to drop it in a v2 (consumers read the
-- note from app.audit_log, which already stores it).

SET lock_timeout = '5s';

INSERT INTO app.domain_event_type
  (event_type, payload_version, aggregate_type, aggregate_id_is, producer, description, payload_schema, introduced_in) VALUES
('tracking_action.created', 1, 'tracking_action', 'tracking action uuid (audit_log.entity_id)', 'backend:analytics',
 'A supervisor recorded a daily-tracking action on a route-day; the notify module nudges the route''s TSO and AMO.',
 '{"type":"object","required":["route_id","business_date","notified_user_ids"],"properties":{
   "route_id":{"type":"integer"},"business_date":{"type":"string","format":"date"},
   "note":{"type":["string","null"],"description":"free text (to be removed in v2: personal data does not belong in the outbox)"},
   "notified_user_ids":{"type":"array","items":{"type":"integer"},"description":"TSO and AMO users nudged"}}}', 'V0022');
