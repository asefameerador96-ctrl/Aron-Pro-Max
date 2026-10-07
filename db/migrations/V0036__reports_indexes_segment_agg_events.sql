-- V0036 backend-reports asks (docs/requests/backend-reports-db-indexes-and-events.md, F-SYS-015 checker):
-- (1) route-day rebuild reads due_collection and stock_movement by route and date;
-- (2) "is this route excused on this date": approved day exceptions by date range, and by route_ids;
-- (3) dw.agg_hourly_zone rebuilds read fact_visit / fact_memo by zone and date;
-- (4) dw.agg_daily_route_segment: memos counted once per product segment (contract by_segment; not derivable from the
--     brand or SKU aggregates);
-- (5) catalogue rows for the events the projector needs: due.collected, day_exception.decided, risk_signal.changed
--     (required keys documented, enforced only once their producers send them, V0018).
-- Index builds: these tables are small at pilot size and empty in a new (final) account; the partitioned facts cannot
-- be indexed CONCURRENTLY, so each statement is marked for squawk.

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX due_collection_route_date ON app.due_collection (route_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX stock_movement_route_date ON app.stock_movement (route_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX day_exception_approved_dates ON app.day_exception (from_date, to_date) WHERE status = 'approved';
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX day_exception_route_ids ON app.day_exception USING gin (route_ids) WHERE status = 'approved';
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX fact_visit_zone_date ON dw.fact_visit (zone_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX fact_memo_zone_date ON dw.fact_memo (zone_id, business_date);

CREATE TABLE dw.agg_daily_route_segment (
  business_date  date NOT NULL,
  route_id       bigint NOT NULL,
  segment_id     bigint NOT NULL,
  memo_count     int NOT NULL DEFAULT 0 CHECK (memo_count >= 0),
  sold_qty_base  bigint NOT NULL DEFAULT 0,
  gross_mtk      bigint NOT NULL DEFAULT 0,
  last_event_id  bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, segment_id)
);
CREATE INDEX agg_daily_route_segment_segment ON dw.agg_daily_route_segment (segment_id, business_date);

COMMENT ON TABLE dw.agg_daily_route_segment IS 'Per route, product segment and date: memo count containing the segment (each memo once) and its sales.
owner: backend:analytics | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.agg_daily_route_segment.business_date IS 'Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it.';
COMMENT ON COLUMN dw.agg_daily_route_segment.route_id IS 'Route (app.route) being worked.';
COMMENT ON COLUMN dw.agg_daily_route_segment.segment_id IS 'Product segment (app.product_node of level segment).';
COMMENT ON COLUMN dw.agg_daily_route_segment.memo_count IS 'Active memos with at least one line in the segment, each memo counted once.';
COMMENT ON COLUMN dw.agg_daily_route_segment.sold_qty_base IS 'Quantity sold in the segment, in each SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN dw.agg_daily_route_segment.gross_mtk IS 'Gross sales of the segment in integer milli-taka.';
COMMENT ON COLUMN dw.agg_daily_route_segment.last_event_id IS 'Last outbox event folded in (informational; the row is recomputed by dirty key).';
COMMENT ON COLUMN dw.agg_daily_route_segment.updated_at IS 'UTC instant of the last recompute.';

INSERT INTO app.domain_event_type
  (event_type, payload_version, aggregate_type, aggregate_id_is, producer, description, payload_schema, introduced_in) VALUES
('due.collected', 1, 'due_collection', 'due_collection.client_uuid', 'backend:sync',
 'A due collection was accepted; the route-day and outlet dues are recomputed.',
 '{"type":"object","required":["route_id","business_date","outlet_id","amount_mtk"],"properties":{
   "route_id":{"type":"integer"},"business_date":{"type":"string","format":"date"},"outlet_id":{"type":"integer"},
   "amount_mtk":{"type":"integer","description":"collected amount, integer milli-taka"}}}', 'V0036'),
('day_exception.decided', 1, 'day_exception', 'day_exception.client_uuid', 'backend:masterdata',
 'A day exception was approved or rejected; one event per affected route.',
 '{"type":"object","required":["route_id","from_date","to_date","status"],"properties":{
   "route_id":{"type":"integer"},"from_date":{"type":"string","format":"date"},"to_date":{"type":"string","format":"date"},
   "status":{"type":"string","enum":["approved","rejected"]}}}', 'V0036'),
('risk_signal.changed', 1, 'risk_signal', 'risk_signal.id', 'backend:masterdata',
 'A risk signal was raised or reviewed; the user''s day figures and integrity views are recomputed.',
 '{"type":"object","required":["user_id","business_date","signal_code"],"properties":{
   "user_id":{"type":"integer"},"business_date":{"type":"string","format":"date"},"signal_code":{"type":"string"},
   "review_state":{"type":["string","null"]}}}', 'V0036');

SELECT app.apply_db_role_grants();
