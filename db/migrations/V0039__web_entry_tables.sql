-- V0039 back-office web entry (docs/24 s11 and s12.1; F-API-049, F-API-050, F-API-052, F-API-038 web half, the
-- web_entry scope of F-API-048): entry unlocks, the route-day web entry with its SKU lines, and the market and
-- warehouse QC summary with its rows. Answers docs/requests/backend-admin-web-entry-tables.md.
--
-- Data void (DataVoidApi) voids web_entry_route_day, web_entry_line and qc_summary_entry by
-- "route_id = :r AND business_date = :d AND voided_at IS NULL ... RETURNING client_uuid", so each carries those four
-- columns. Lines copy route_id and business_date from their entry; a composite foreign key keeps them equal.
-- A re-save of a route-day entry is a new entry (new browser client_uuid, supersedes_client_uuid = the old one); the
-- old entry is closed by replaced_at/replaced_by in the same transaction. At most one live entry per route-day.
-- Everything here is append-only apart from those closing columns, each written once.
-- No Astha quantity table and no web_entry_outlet_sku (docs/27 trim; no row asks for per-outlet web entry).

SET lock_timeout = '5s';

-- ---------- entry unlocks (POST /v1/admin/entry-unlocks, expire) ----------
CREATE TABLE app.entry_unlock (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  scope_type  text NOT NULL CHECK (scope_type IN ('zone', 'route')),
  scope_id    bigint NOT NULL CHECK (scope_id > 0),
  from_date   date NOT NULL,
  to_date     date NOT NULL,
  reason      text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  expires_at  timestamptz NOT NULL,
  created_by  bigint NOT NULL REFERENCES app.app_user(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  expired_at  timestamptz,
  expired_by  bigint REFERENCES app.app_user(id),
  version     int NOT NULL DEFAULT 1 CHECK (version >= 1),
  CHECK (to_date >= from_date),
  CHECK ((expired_at IS NULL) = (expired_by IS NULL))
);
CREATE INDEX entry_unlock_scope ON app.entry_unlock (scope_type, scope_id, expires_at);

-- Only the early expiry is written after insert (once); version moves with it so If-Match stays meaningful.
CREATE FUNCTION app.entry_unlock_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'app.entry_unlock: unlocks are never deleted (expire them)' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF OLD.expired_at IS NOT NULL THEN
    RAISE EXCEPTION 'app.entry_unlock: an expired unlock does not change' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['expired_at', 'expired_by', 'version']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['expired_at', 'expired_by', 'version']) THEN
    RAISE EXCEPTION 'app.entry_unlock: only expired_at and expired_by may change' USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.version := OLD.version + 1;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.entry_unlock_guard() IS 'Refuses deletes and every change to an entry unlock except writing expired_at/expired_by once; moves version.';
CREATE TRIGGER entry_unlock_guard BEFORE UPDATE OR DELETE ON app.entry_unlock
  FOR EACH ROW EXECUTE FUNCTION app.entry_unlock_guard();

-- ---------- route-day web entry (GET/POST /v1/web-entry/route-day) ----------
CREATE TABLE app.web_entry_route_day (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid            uuid NOT NULL UNIQUE,
  supersedes_client_uuid uuid UNIQUE,
  route_id               bigint NOT NULL REFERENCES app.route(id),
  business_date          date NOT NULL,
  successful_calls       int NOT NULL CHECK (successful_calls BETWEEN 0 AND 100000),
  target_outlets         int NOT NULL CHECK (target_outlets BETWEEN 0 AND 100000),
  app_overlap            boolean NOT NULL DEFAULT false,
  change_reason          text CHECK (length(change_reason) BETWEEN 10 AND 500),
  source                 text NOT NULL DEFAULT 'web' CHECK (source = 'web'),
  entered_by             bigint NOT NULL REFERENCES app.app_user(id),
  entered_at             timestamptz NOT NULL DEFAULT now(),
  replaced_at            timestamptz,
  replaced_by            bigint REFERENCES app.app_user(id),
  voided_at              timestamptz,
  UNIQUE (client_uuid, route_id, business_date),
  CHECK ((replaced_at IS NULL) = (replaced_by IS NULL)),
  CHECK (supersedes_client_uuid IS NULL OR change_reason IS NOT NULL),
  FOREIGN KEY (supersedes_client_uuid, route_id, business_date)
    REFERENCES app.web_entry_route_day (client_uuid, route_id, business_date)
);
-- One live entry per route-day: the re-save closes the old entry before it inserts the new one.
CREATE UNIQUE INDEX web_entry_route_day_live ON app.web_entry_route_day (route_id, business_date)
  WHERE replaced_at IS NULL AND voided_at IS NULL;
CREATE INDEX web_entry_route_day_date ON app.web_entry_route_day (business_date, route_id);
CREATE TRIGGER web_entry_route_day_immutable BEFORE UPDATE OR DELETE ON app.web_entry_route_day
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', '=replaced_at', '=replaced_by');

CREATE TABLE app.web_entry_line (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid       uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  entry_client_uuid uuid NOT NULL,
  route_id          bigint NOT NULL,
  business_date     date NOT NULL,
  sku_id            bigint NOT NULL REFERENCES app.sku(id),
  issue_qty_base    bigint NOT NULL CHECK (issue_qty_base BETWEEN 0 AND 10000000),
  return_qty_base   bigint NOT NULL CHECK (return_qty_base BETWEEN 0 AND 10000000),
  memo_count        int NOT NULL CHECK (memo_count BETWEEN 0 AND 100000),
  class_qty_base    jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(class_qty_base) = 'object'),
  voided_at         timestamptz,
  UNIQUE (entry_client_uuid, sku_id),
  CHECK (return_qty_base <= issue_qty_base),
  FOREIGN KEY (entry_client_uuid, route_id, business_date)
    REFERENCES app.web_entry_route_day (client_uuid, route_id, business_date)
);
CREATE INDEX web_entry_line_route_date ON app.web_entry_line (route_id, business_date);
CREATE INDEX web_entry_line_sku ON app.web_entry_line (sku_id);
CREATE TRIGGER web_entry_line_immutable BEFORE UPDATE OR DELETE ON app.web_entry_line
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at');

-- ---------- market and warehouse QC summary (POST /v1/qc-entry, web) ----------
CREATE TABLE app.qc_summary_entry (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid   uuid NOT NULL UNIQUE,
  qc_source     text NOT NULL CHECK (qc_source IN ('market', 'warehouse')),
  zone_id       bigint NOT NULL REFERENCES app.zone(id),
  route_id      bigint REFERENCES app.route(id),
  business_date date NOT NULL,
  reason        text CHECK (length(reason) BETWEEN 10 AND 500),
  source        text NOT NULL DEFAULT 'web' CHECK (source = 'web'),
  entered_by    bigint NOT NULL REFERENCES app.app_user(id),
  entered_at    timestamptz NOT NULL DEFAULT now(),
  voided_at     timestamptz,
  CHECK (qc_source <> 'market' OR route_id IS NOT NULL),
  CHECK (qc_source <> 'warehouse' OR reason IS NOT NULL)
);
CREATE INDEX qc_summary_entry_route_date ON app.qc_summary_entry (route_id, business_date) WHERE route_id IS NOT NULL;
CREATE INDEX qc_summary_entry_zone_date ON app.qc_summary_entry (zone_id, business_date);
CREATE TRIGGER qc_summary_entry_immutable BEFORE UPDATE OR DELETE ON app.qc_summary_entry
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at');

CREATE TABLE app.qc_summary_entry_line (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entry_client_uuid uuid NOT NULL REFERENCES app.qc_summary_entry(client_uuid),
  sku_id            bigint NOT NULL REFERENCES app.sku(id),
  fault_type_code   text NOT NULL CHECK (fault_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  qty_base          bigint NOT NULL CHECK (qty_base BETWEEN 0 AND 10000000),
  UNIQUE (entry_client_uuid, sku_id, fault_type_code)
);
CREATE INDEX qc_summary_entry_line_sku ON app.qc_summary_entry_line (sku_id);
CREATE TRIGGER qc_summary_entry_line_immutable BEFORE UPDATE OR DELETE ON app.qc_summary_entry_line
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

-- ---------- data dictionary ----------
COMMENT ON TABLE app.entry_unlock IS 'One row is a time-limited unlock that lets web entry be back-dated for a zone or route over a date range.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.entry_unlock.id IS 'Server surrogate key (unlock_id in the contract).';
COMMENT ON COLUMN app.entry_unlock.scope_type IS 'What the unlock covers; allowed values are listed under constraints.';
COMMENT ON COLUMN app.entry_unlock.scope_id IS 'Id of the zone or route the unlock covers (by scope_type).';
COMMENT ON COLUMN app.entry_unlock.from_date IS 'First Asia/Dhaka business date that may be entered.';
COMMENT ON COLUMN app.entry_unlock.to_date IS 'Last Asia/Dhaka business date that may be entered (at most cfg.web.entry_unlock_max_days after from_date).';
COMMENT ON COLUMN app.entry_unlock.reason IS 'Reason the granting user gave (10 to 500 characters).';
COMMENT ON COLUMN app.entry_unlock.expires_at IS 'UTC time the unlock lapses (creation plus the TTL, cfg.web.entry_unlock_ttl_h by default).';
COMMENT ON COLUMN app.entry_unlock.created_by IS 'User who granted the unlock.';
COMMENT ON COLUMN app.entry_unlock.created_at IS 'UTC instant the row was inserted on the server.';
COMMENT ON COLUMN app.entry_unlock.expired_at IS 'UTC time the unlock was expired early (null if it was not); written once.';
COMMENT ON COLUMN app.entry_unlock.expired_by IS 'User who expired the unlock early; written once with expired_at.';
COMMENT ON COLUMN app.entry_unlock.version IS 'Row version for If-Match; the guard trigger moves it on the expiry.';

COMMENT ON TABLE app.web_entry_route_day IS 'One row is a back-office web entry of a route-day (issue, return and memos per SKU, successful calls); a re-save is a new row that closes the old one.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.web_entry_route_day.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.web_entry_route_day.client_uuid IS 'Browser-generated UUID of the save; the API is idempotent by it.';
COMMENT ON COLUMN app.web_entry_route_day.supersedes_client_uuid IS 'client_uuid of the entry this re-save replaces (null for the first save).';
COMMENT ON COLUMN app.web_entry_route_day.route_id IS 'Route of the entered route-day.';
COMMENT ON COLUMN app.web_entry_route_day.business_date IS 'Asia/Dhaka business date of the entered route-day.';
COMMENT ON COLUMN app.web_entry_route_day.successful_calls IS 'Successful calls entered (at most target_outlets when cfg.web.entry_validate_calls_le_target).';
COMMENT ON COLUMN app.web_entry_route_day.target_outlets IS 'Target outlets of the route-day at the save, as the server computed them.';
COMMENT ON COLUMN app.web_entry_route_day.app_overlap IS 'True when app memos existed for the same route-day at the save (flagged, never added).';
COMMENT ON COLUMN app.web_entry_route_day.change_reason IS 'Reason given for a re-save (required when supersedes_client_uuid is set).';
COMMENT ON COLUMN app.web_entry_route_day.source IS 'Origin of the entry; always web.';
COMMENT ON COLUMN app.web_entry_route_day.entered_by IS 'User who saved the entry.';
COMMENT ON COLUMN app.web_entry_route_day.entered_at IS 'UTC instant of the save.';
COMMENT ON COLUMN app.web_entry_route_day.replaced_at IS 'UTC time a re-save replaced this entry (null while it is live); written once.';
COMMENT ON COLUMN app.web_entry_route_day.replaced_by IS 'User whose re-save replaced this entry; written once with replaced_at.';
COMMENT ON COLUMN app.web_entry_route_day.voided_at IS 'UTC time a data void voided the entry (tombstone); written once.';

COMMENT ON TABLE app.web_entry_line IS 'One row is the per-SKU quantities of a route-day web entry.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.web_entry_line.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.web_entry_line.client_uuid IS 'Server-generated UUID of the line (the browser sends lines without one); data void reports it.';
COMMENT ON COLUMN app.web_entry_line.entry_client_uuid IS 'client_uuid of the route-day entry the line belongs to.';
COMMENT ON COLUMN app.web_entry_line.route_id IS 'Route of the entry (equal to the entry''s by foreign key).';
COMMENT ON COLUMN app.web_entry_line.business_date IS 'Asia/Dhaka business date of the entry (equal to the entry''s by foreign key).';
COMMENT ON COLUMN app.web_entry_line.sku_id IS 'SKU of the line.';
COMMENT ON COLUMN app.web_entry_line.issue_qty_base IS 'Quantity issued to the SR, in the SKU''s base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN app.web_entry_line.return_qty_base IS 'Quantity returned by the SR, in the base unit; at most the issue (sale = issue minus return).';
COMMENT ON COLUMN app.web_entry_line.memo_count IS 'Number of memos the SKU was sold on.';
COMMENT ON COLUMN app.web_entry_line.class_qty_base IS 'Sale split by web-entry class (cfg.web.entry_classes): sub-channel id to base quantity.';
COMMENT ON COLUMN app.web_entry_line.voided_at IS 'UTC time a data void voided the line (tombstone); written once.';

COMMENT ON TABLE app.qc_summary_entry IS 'One row is a back-office QC summary entered on the web: market QC for a route or warehouse QC for a zone.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.qc_summary_entry.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.qc_summary_entry.client_uuid IS 'Browser-generated UUID of the save; the API is idempotent by it.';
COMMENT ON COLUMN app.qc_summary_entry.qc_source IS 'Where the QC was done; allowed values are listed under constraints (source in the contract).';
COMMENT ON COLUMN app.qc_summary_entry.zone_id IS 'Zone of the QC.';
COMMENT ON COLUMN app.qc_summary_entry.route_id IS 'Route of a market QC (required for market, null for warehouse).';
COMMENT ON COLUMN app.qc_summary_entry.business_date IS 'Asia/Dhaka business date of the QC.';
COMMENT ON COLUMN app.qc_summary_entry.reason IS 'Reason given (required for warehouse QC).';
COMMENT ON COLUMN app.qc_summary_entry.source IS 'Origin of the entry; always web.';
COMMENT ON COLUMN app.qc_summary_entry.entered_by IS 'User who saved the entry.';
COMMENT ON COLUMN app.qc_summary_entry.entered_at IS 'UTC instant of the save.';
COMMENT ON COLUMN app.qc_summary_entry.voided_at IS 'UTC time a data void voided the entry (tombstone); written once.';

COMMENT ON TABLE app.qc_summary_entry_line IS 'One row is a faulty quantity of one SKU and fault type in a web QC summary.
owner: backend:masterdata | capture: ONLINE | retention: transaction | pii: none';
COMMENT ON COLUMN app.qc_summary_entry_line.id IS 'Server surrogate key.';
COMMENT ON COLUMN app.qc_summary_entry_line.entry_client_uuid IS 'client_uuid of the QC summary the row belongs to.';
COMMENT ON COLUMN app.qc_summary_entry_line.sku_id IS 'SKU of the row.';
COMMENT ON COLUMN app.qc_summary_entry_line.fault_type_code IS 'Fault type code (code list of QC fault types).';
COMMENT ON COLUMN app.qc_summary_entry_line.qty_base IS 'Faulty quantity in the SKU''s base unit (sticks, pieces or dozens).';

SELECT app.apply_db_role_grants();
