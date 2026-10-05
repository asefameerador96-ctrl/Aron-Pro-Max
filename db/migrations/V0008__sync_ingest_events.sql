-- V0008 sync ingest, quarantine, server generation and the domain-event outbox (row N-006, schema v1b).
-- docs/24 s3.3 (idempotency), s4.4 to s4.8, s4.13, s6.3, s12.5 item 4; contract SyncBatchResponse, QuarantineItem,
-- ServerGeneration.

-- The single global uniqueness point of every device record (s3.3 item 1). Same client_uuid and same payload hash:
-- duplicate; same client_uuid, different hash: the first stays and the second copy is quarantined payload_conflict.
-- Hash-partitioned on client_uuid so the primary key stays global (a range partition on received_at would force
-- received_at into the key and let one client_uuid be registered twice: docs/requests/db-ingest-registry-partitioning.md).
-- Pruned after cfg.retention.ingest_registry_days by the worker (index on received_at).
CREATE TABLE app.ingest_registry (
  client_uuid       uuid NOT NULL,
  record_type       text NOT NULL CHECK (record_type ~ '^[a-z][a-z_]{1,40}$'),
  payload_sha256    bytea NOT NULL CHECK (length(payload_sha256) = 32),   -- SHA-256 of the RFC 8785 form (D24-47)
  content_fp        bytea,                                                  -- content fingerprint (content_duplicate)
  family_uuid       uuid,
  status            text NOT NULL CHECK (status IN ('accepted','rejected','quarantined','parked','voided')),
  outcome_code      text,                                                   -- RecordOutcomeCode when not accepted
  server_id         bigint,
  business_date     date,
  user_id           bigint NOT NULL REFERENCES app.app_user(id),
  device_id         bigint,                                                 -- FK to app.device added in V0010
  first_batch_uuid  uuid NOT NULL,
  received_at       timestamptz NOT NULL DEFAULT now(),
  last_seen_at      timestamptz NOT NULL DEFAULT now(),
  seen_count        int NOT NULL DEFAULT 1 CHECK (seen_count >= 1),
  PRIMARY KEY (client_uuid),
  CHECK (status = 'accepted' OR status = 'voided' OR outcome_code IS NOT NULL)
) PARTITION BY HASH (client_uuid);
DO $$
BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format('CREATE TABLE app.ingest_registry_h%s PARTITION OF app.ingest_registry FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
                   lpad(i::text, 2, '0'), i);
  END LOOP;
END $$;
CREATE INDEX ON app.ingest_registry (received_at);
CREATE INDEX ingest_registry_content_fp ON app.ingest_registry (user_id, record_type, content_fp) WHERE content_fp IS NOT NULL;
CREATE INDEX ingest_registry_reconcile ON app.ingest_registry (user_id, business_date, record_type);

-- Batch replay store (s3.3 item 2): (device, batch_uuid) with the fingerprint of its record set and the full
-- response for cfg.retention.sync_batch_response_h. A different record set under the same batch_uuid is
-- 409 ERR_SYNC_BATCH_UUID_REUSED.
CREATE TABLE app.sync_batch (
  device_id         bigint NOT NULL,                  -- FK to app.device added in V0010
  batch_uuid        uuid NOT NULL,
  user_id           bigint NOT NULL REFERENCES app.app_user(id),
  fingerprint       bytea NOT NULL CHECK (length(fingerprint) = 32),
  record_count      int NOT NULL CHECK (record_count BETWEEN 1 AND 1000),
  trigger           text CHECK (trigger IN ('write_debounce','foreground','connectivity','workmanager_connectivity','periodic',
                                            'manual','day_submit','checkout','resync','digest_resend','directive')),
  attempt           int CHECK (attempt >= 1),
  app_version       text,
  pending_rows      int CHECK (pending_rows >= 0),
  counts            jsonb NOT NULL DEFAULT '{}'::jsonb,   -- accepted / duplicate / rejected / quarantined
  response_gz       bytea,                                 -- the stored SyncBatchResponse (gzip JSON)
  received_at       timestamptz NOT NULL DEFAULT now(),
  completed_at      timestamptz,
  replay_count      int NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
  expires_at        timestamptz NOT NULL,
  PRIMARY KEY (device_id, batch_uuid)
);
CREATE INDEX ON app.sync_batch (expires_at);
CREATE INDEX ON app.sync_batch (user_id, received_at);

-- Rejected records, final or parked (retryable), with the payload as received (s4.1 item 4: nothing is dropped).
CREATE TABLE app.sync_rejected (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid       uuid NOT NULL,
  record_type       text NOT NULL,
  code              text NOT NULL CHECK (code IN ('scope_stale','config_version_unknown','parent_missing','outlet_pending_approval',
                                                  'price_list_unknown','schema_invalid','unknown_record_type','unknown_sku',
                                                  'unknown_outlet','unknown_route','memo_no_invalid','edit_not_allowed',
                                                  'chain_too_deep','voided_by_admin','lines_exceed_max','qty_invalid',
                                                  'attendance_duplicate','server_error','retry_exhausted',
                                                  'unknown_gift','insufficient_points','gift_photo_exists')),
  retryable         boolean NOT NULL,
  payload_sha256    bytea NOT NULL CHECK (length(payload_sha256) = 32),
  payload           jsonb NOT NULL,
  detail            text CHECK (length(detail) <= 1000),
  user_id           bigint NOT NULL REFERENCES app.app_user(id),
  device_id         bigint,
  route_id          bigint,
  business_date     date,
  batch_uuid        uuid,
  attempts          int NOT NULL DEFAULT 1 CHECK (attempts >= 1),
  first_received_at timestamptz NOT NULL DEFAULT now(),
  last_received_at  timestamptz NOT NULL DEFAULT now(),
  parked_until      timestamptz,                      -- parked rows turn final after cfg.sync.parked_ttl_days
  finalised_at      timestamptz,
  stored_at         timestamptz,                      -- set when a later resend was accepted
  UNIQUE (client_uuid, payload_sha256)
);
CREATE INDEX sync_rejected_parked ON app.sync_rejected (parked_until) WHERE retryable AND finalised_at IS NULL AND stored_at IS NULL;
CREATE INDEX ON app.sync_rejected (user_id, business_date);

-- Quarantined records awaiting a human (QuarantineItem). A payload_conflict copy shares its client_uuid with the
-- stored row, so the key is (client_uuid, payload_sha256).
CREATE TABLE app.sync_quarantine (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid       uuid NOT NULL,
  record_type       text NOT NULL,
  code              text NOT NULL CHECK (code IN ('arithmetic_mismatch','memo_no_duplicate','content_duplicate','checkout_too_early',
                                                  'payload_conflict','business_date_out_of_window','scope_out_of_reach',
                                                  'no_assignment_on_date','device_integrity_failed','device_not_enrolled',
                                                  'user_disabled','device_revoked','app_version_blocked','after_month_close',
                                                  'programme_inactive')),
  status            text NOT NULL DEFAULT 'open' CHECK (status IN ('open','accepted','accepted_with_fix','discarded')),
  payload_sha256    bytea NOT NULL CHECK (length(payload_sha256) = 32),
  payload           jsonb NOT NULL,                   -- the SyncRecord as received
  fixed_payload     jsonb,                            -- accept_with_fix: what was re-ingested
  detail            text CHECK (length(detail) <= 1000),
  user_id           bigint NOT NULL REFERENCES app.app_user(id),
  device_id         bigint,
  route_id          bigint,
  business_date     date NOT NULL,
  batch_uuid        uuid,
  received_at       timestamptz NOT NULL DEFAULT now(),
  resolution_uuid   uuid UNIQUE,                      -- idempotent resolve command
  resolved_by_user_id bigint REFERENCES app.app_user(id),
  approved_by_user_id bigint REFERENCES app.app_user(id),   -- maker-checker for accept_with_fix of data-entry classes
  resolved_at       timestamptz,
  resolution_note   text CHECK (length(resolution_note) <= 500),
  UNIQUE (client_uuid, payload_sha256),
  CHECK ((status = 'open') = (resolved_at IS NULL)),
  CHECK (status <> 'accepted_with_fix' OR fixed_payload IS NOT NULL),
  CHECK (approved_by_user_id IS NULL OR approved_by_user_id <> resolved_by_user_id)
);
CREATE INDEX sync_quarantine_open ON app.sync_quarantine (business_date, code) WHERE status = 'open';
CREATE INDEX ON app.sync_quarantine (user_id, business_date);

-- Database lineage (s4.8): a new generation after creation, a failover that may have lost acknowledged writes, or
-- a point-in-time restore. Exactly one is current.
CREATE TABLE app.server_generation (
  generation         uuid PRIMARY KEY,
  kind               text NOT NULL CHECK (kind IN ('created','failover','pitr_restore')),
  started_at         timestamptz NOT NULL DEFAULT now(),
  restore_point_utc  timestamptz,
  lost_after_utc     timestamptz,
  is_current         boolean NOT NULL DEFAULT true,
  note               text,
  CHECK (kind = 'created' OR lost_after_utc IS NOT NULL)
);
CREATE UNIQUE INDEX server_generation_current ON app.server_generation (is_current) WHERE is_current;
INSERT INTO app.server_generation (generation, kind, note) VALUES (gen_random_uuid(), 'created', 'minted by V0008');

-- Domain-event outbox (s6.3, s12.5 item 4): every write emits rows here in its own transaction; the aggregate
-- projector and Phase 2 consumers read it in id order. Append-only.
CREATE TABLE app.domain_event (
  id               bigint GENERATED ALWAYS AS IDENTITY,
  event_type       text NOT NULL CHECK (event_type ~ '^[a-z_]+\.[a-z_]+$'),   -- memo.created, visit.closed, ...
  aggregate_type   text NOT NULL,
  aggregate_id     text NOT NULL,                     -- server id or client_uuid
  business_date    date NOT NULL,
  payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
  source_client_uuid uuid,                            -- the record that caused it, when device-originated
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX domain_event_id ON app.domain_event (id);
CREATE INDEX ON app.domain_event (aggregate_type, aggregate_id);
CREATE TRIGGER domain_event_append_only BEFORE UPDATE OR DELETE ON app.domain_event FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();
INSERT INTO app.partition_policy (parent) VALUES ('app.domain_event');

-- Read position of each outbox consumer (the projector, Phase 2 subscribers).
CREATE TABLE app.event_consumer (
  consumer        text PRIMARY KEY CHECK (consumer ~ '^[a-z][a-z0-9_.-]{1,60}$'),
  last_event_id   bigint NOT NULL DEFAULT 0 CHECK (last_event_id >= 0),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Monthly partitions for every range-partitioned parent of V0007 and V0008, from the first build month to the end
-- of 2027; the worker keeps them three months ahead after that (app.ensure_partitions()).
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');
