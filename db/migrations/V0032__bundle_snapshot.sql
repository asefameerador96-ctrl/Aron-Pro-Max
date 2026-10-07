-- V0032 ordered bundle snapshots (docs/requests/backend-bundle-snapshot-table.md, docs/24 s4.10): bundle_version is
-- '<date>:<snapshot_seq>' and a phone pulls a delta when the server's version is newer than its own, so snapshot_seq must
-- grow per user and business date even when content returns to an earlier state (A -> B -> A gives 1, 2, 3). BundleService
-- reuses the latest seq while the content digest is unchanged and inserts seq + 1 otherwise; the worker deletes dates
-- older than 7 days.

SET lock_timeout = '5s';

CREATE TABLE app.bundle_snapshot (
  user_id         bigint NOT NULL REFERENCES app.app_user(id),
  business_date   date   NOT NULL,
  snapshot_seq    int    NOT NULL CHECK (snapshot_seq BETWEEN 1 AND 999999999),
  content_sha256  bytea  NOT NULL CHECK (length(content_sha256) = 32),
  generated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, business_date, snapshot_seq)
);
CREATE INDEX bundle_snapshot_business_date ON app.bundle_snapshot (business_date);

COMMENT ON TABLE app.bundle_snapshot IS 'One row is a distinct day-bundle content the server generated for a user and business date, numbered in order.
owner: backend:sync | capture: SERVER | retention: ops | pii: none';
COMMENT ON COLUMN app.bundle_snapshot.user_id IS 'User the bundle was generated for.';
COMMENT ON COLUMN app.bundle_snapshot.business_date IS 'Asia/Dhaka business date of the bundle.';
COMMENT ON COLUMN app.bundle_snapshot.snapshot_seq IS 'Order of the content within the user and date, from 1; the <seq> of bundle_version <date>:<seq>.';
COMMENT ON COLUMN app.bundle_snapshot.content_sha256 IS 'SHA-256 of the bundle content; equal to the latest row means the seq is reused.';
COMMENT ON COLUMN app.bundle_snapshot.generated_at IS 'UTC instant the snapshot was first generated.';

-- worker retention deletes old dates
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, note)
VALUES ('worker_rw', 'app', 'bundle_snapshot', 'DELETE', 'retention: dates older than 7 days');

SELECT app.apply_db_role_grants();
