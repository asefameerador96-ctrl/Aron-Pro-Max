# Request (db → lead, 2026-10-07): rule on docs/24 s12.1 versus docs/16 s8.11 for capture-table partitioning (AUD-DA-06)

**Conflict.** docs/24 s12.1 (binding on build mechanics) partitions exactly six large tables: visit, memo, memo_line,
geo_fix, ingest_registry, domain_event. docs/16 s8.11 and s13.1 also partition qc_entry_line, survey_response,
content_view, due_collection, stock_movement, attendance_event, print_event, activity_log and sync_batch (design
volumes: qc, survey and content about 1.6 M rows a day; sync_batch 257 k a day). Those tables are plain tables whose
rows can never be deleted (guard triggers), so without partitions their data can never leave the primary.

**Already done without a ruling (V0044, V0045):** retention classes on `app.partition_policy`, `app.retention_policy`
(docs/16 windows), `app.archive_manifest`, `app.archive_candidates()`, `app.default_partition_rows()`.

**Options.**
- A (recommended): rule for docs/16; the db lane recreates the nine tables as monthly RANGE partitions on
  business_date (sync_batch on uploaded_on) **while the data is pilot-only** (dev, 5 to 10 users): PRIMARY KEY
  (id, business_date), UNIQUE (client_uuid, business_date), the set-based client_uuid_once trigger, registered in
  partition_policy. Each table is one migration (create new, copy, swap names, keep the old one renamed for a day).
  Needs a short freeze of writes to that table on dev, and every lane's fixtures that rely on a plain
  `UNIQUE (client_uuid)` or `ON CONFLICT (client_uuid)` on these tables must move to `(client_uuid, business_date)`
  in the same push (backend-core sync ingest, backend-reports, backend-admin). Estimate: one lane-day plus one day of
  coordination.
- B: keep docs/24; amend docs/16 s8.11 to say those tables stay unpartitioned until month 12, and accept that
  converting a populated table later is a full rewrite on a live system.

**Ask.** A or B, recorded in DECISIONS.md and docs/24 s12.1. If A, also say whether it may land before Day 10 or
waits for the final-account move.
