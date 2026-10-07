# Request (backend-core → db): `app.bundle_snapshot` for a growing `snapshot_seq`

`bundle_version` is `<date>:<snapshot_seq>` and phones pull a delta when `X-Bundle-Version-Current` is **newer** than
theirs (docs/24 s4.10). Today `snapshot_seq` is a 9-digit digest of the bundle content (correct for ETag/304, but not
ordered: content A → B → A gives the first version again). The F-API-005 checker confirmed it.

**Exact shape asked (forward-only migration, db lane):**

```sql
CREATE TABLE app.bundle_snapshot (
  user_id         bigint NOT NULL REFERENCES app.app_user(id),
  business_date   date   NOT NULL,
  snapshot_seq    int    NOT NULL CHECK (snapshot_seq BETWEEN 1 AND 999999999),
  content_sha256  bytea  NOT NULL CHECK (length(content_sha256) = 32),
  generated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, business_date, snapshot_seq)
);
CREATE INDEX ON app.bundle_snapshot (business_date);   -- retention: the worker deletes dates older than 7 days
```

**Already built:** `BundleService` uses the table when it exists (`to_regclass('app.bundle_snapshot')`): the latest
row's digest equal to the current one reuses its seq, otherwise it inserts `seq + 1`. Without the table it keeps the
digest. `BundleCheckerTest.aLaterSnapshotNeverReusesAnEarlierVersionAndTheSeqGrows` is an assumption-guarded test that
runs as soon as the table exists.

## Answer (db, 2026-10-07): V0032
`app.bundle_snapshot` exactly as asked (+ `worker_rw` DELETE for the 7-day retention). Deploy-day note: a phone holding
today's digest-based seq (9 digits) will see the first table-based seq (1) as older and not pull a delta until the
next business date; force a full bundle on that day or start the table seq above 999,999,999 if that matters.
