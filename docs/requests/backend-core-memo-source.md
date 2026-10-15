# Request: entry source on memo and visit (backend-core → db, 2026-10-15)

**Why (F-API-038 paper backfill, docs/24 s8.5 item 4, contract `createPaperBackfill`):** a dead-phone day is keyed on
the web from the printed memo and written through the ingest path. The contract says the memo "is flagged
`source = manual` in every report (Online/Offline)". Today nothing on `app.memo` or `app.visit` can say that, and the
web user who keyed the row is not stored either (the row's `user_id` is the SR's, as on a phone row).

**Ask (forward-only, after your dues_adjustment and SKU image migrations):**

```sql
ALTER TABLE app.memo  ADD COLUMN entry_source text NOT NULL DEFAULT 'device',
                      ADD COLUMN entered_by_user_id bigint REFERENCES app.app_user(id);
ALTER TABLE app.visit ADD COLUMN entry_source text NOT NULL DEFAULT 'device',
                      ADD COLUMN entered_by_user_id bigint REFERENCES app.app_user(id);
-- CHECKs (NOT VALID, then VALIDATE in the next migration, as usual):
--   entry_source IN ('device','manual')
--   (entry_source = 'manual') = (entered_by_user_id IS NOT NULL)
ALTER TABLE app.ingest_registry  -- replace the flags CHECK (V0059) to also allow 'manual_entry'
```

- `memo_line` rows follow their memo (no column needed); `dw.fact_memo` (and the Online/Offline report views) should
  carry `entry_source` so every report can filter or flag it (CLAUDE.md rule 12: the database is a product). Please
  add it to the stable `dw` view and the data dictionary.
- Both tables are range-partitioned; a constant DEFAULT adds no rewrite on PG 11+.
- Grants: `api_rw` already writes these tables; the ingest path writes the two columns.

**Backend side (backend-core, lane/backend-core):** `IngestService.ingestManual(...)` writes `entry_source = 'manual'`
and `entered_by_user_id` when the columns exist (detected once per process, like `refresh_family.amr`) and stamps the
registry flag `manual_entry` once the flags CHECK allows it. Until then manual rows are still stored correctly and
idempotently, but reports cannot tell them apart; the endpoint should not go live for the owner before this lands.
