# Runbook: Apsis history migration into the final Azure account

Who runs it: the lead's operator session (or the infra-final lane) executes every command. **The owner's steps are approvals only** (marked OWNER). Nothing runs in the test account, CI or a laptop: the dump never leaves the final account.
Design and mapping: `docs/status/apsis-migration.md`. Landing and identity: `docs/requests/apsis-dump-landing.md`. Tool: `tools/apsis-migration/README.md`.

Status: written blind, not rehearsed against the real dump. Every check has a number to compare. The first real action is the dry run.

## 0. Preconditions (all must be true)
1. The final account exists; PostgreSQL has the October master data loaded (the master-data load is the first load: `docs/reference/master-data-loader.md`). Without it history keeps vendor codes with no scope ids and the reconciliation fails on the match shares.
2. The provisional migrations (as landed and renumbered by the lead: control/role, archive, dw views, job grants) are applied by the normal migrate role. Check: `SELECT count(*) FROM pg_namespace WHERE nspname IN ('apsis_ctl','apsis_map','apsis_hist','apsis_stage')` = 4 and `SELECT 1 FROM pg_roles WHERE rolname = 'apsis_migrator'`.
3. **The job's database login.** The migrate role runs `GRANT apsis_migrator TO "<entra principal of id-apsis-migration>"` (membership only; the job connects as that principal and the tool does `SET ROLE apsis_migrator`: `--db-role apsis_migrator`). The role can read only column-limited live masters and write only `apsis_ctl`, `apsis_map`, `apsis_stage` and the archive; it has no write on any live table.
4. Free storage on the server >= 250 GB, autogrow on (write the figure in the sign-off). High availability: the load runs **before any user is live on this server** (cutover is later). If the server already serves users, the lead decides explicitly how WAL volume and replica lag are handled; the runbook does not assume a quiet server.
5. The dump is in container `apsis-dump` (OWNER moved it; blob names and sizes in the sign-off): 26 files, about 52.5 GB.
6. **Reviewed column list** committed as `tools/apsis-migration/mapping/column_review.json` (section 1 produces the suggestion; a person reviews it; the run refuses a table with an unclassified column). `mapping/expected_counts.json` holds the vendor's row counts (the inventory).
7. **Restore point**: note the UTC time; confirm backup retention >= 7 days and a backup within 24 h; take an on-demand backup/snapshot if the SKU supports it. Write the time in the sign-off. Rollback (section 6) depends on it.
8. Window: Dhaka night, no cutover wave in progress. OWNER approvals (the only owner actions): (a) start of the dry run; (b) the settings and the column list after the dry run; (c) the sign-off.

## 1. Dry run (counts only; no database writes)
`apsis-migration.py profile --dump-dir /data --out /reports/profile.json --suggest-review /reports/column_review.suggested.json`
Read `profile.json` (numbers and column **names** only):
| Check | Expected | If not |
|---|---|---|
| tables found / `tables_missing` / `files_refused_not_on_allow_list` | all 26, none refused | a missing or refused file: stop, ask the owner |
| `rows` vs `rows_expected_by_vendor` | equal for every table | different: not the dump that was counted: stop. (A file cut between two INSERT statements parses cleanly; this is the check that catches it.) |
| `chunks` | 2025-11 .. 2026-09; `unknown` small | other months or many `unknown`: the date column is wrong: stop |
| `chunk_date_column_found` | true for every fact table | false: edit `mapping/columns.json` (there is no fallback to another column) |
| `time_zone_hint` and `hour_histogram_stored` per fact table | the hint is `utc` or `dhaka_local`, the same for every table | `unclear` or different per table: ask the owner; do not guess. The hint chooses `--source-tz` (`UTC` or `Asia/Dhaka`) |
| `columns[].suggested` | credential/personal suggestions are plausible | the **reviewer** edits the suggestion into `column_review.json`: every column in `keep` or `drop`; credential-looking columns in `keep` need an explicit ack; non-personal, non-credential columns stay (the programme archives keep them for later) |
| `zero_dates`, `undecodable_rows` | small | large: ask the owner |
| `float_money_columns` | none | any: money cannot be exact; owner decides (design Q9) |
| `has_primary_key` | true for the fact tables | false for sales: owner decision (thresholds `accept_full_row_key`) before the run |
| `duplicate_keys` | 0 or small | note it |
OWNER approves the settings, recorded in `apsis_ctl.run`: `--source-tz`, `--qty-unit` (base unless the vendor counts packs), whether imported coordinates are confirmed (`--confirm-locations`) and whether missing outlet coordinates are filled from the dump (`--fill-locations`). Thresholds in `mapping/columns.json` ("thresholds") are confirmed too.

## 2. Prepare the database (operator, platform admin)
Server parameters for the window, reverted in section 5: `max_wal_size = 64GB`, `checkpoint_timeout = 30min`, `wal_compression = on`, `maintenance_work_mem = 2GB`. (The job sets `synchronous_commit = off` for its own session in code.) Nothing else is scheduled on the server. If `--fill-locations` was approved: the migrate role runs `SELECT apsis_ctl.allow_fill_locations()` (a time-boxed UPDATE grant on four outlet columns) and `disallow_fill_locations()` after the run.

## 3. Run (restartable)
`apsis-migration.py run --dump-dir /data --dsn "<host/db/user, NO password>" --db-role apsis_migrator --review column_review.json --source-tz <approved> --qty-unit <approved> [--fill-locations --confirm-locations]`
The password or Entra token comes from the environment (`PGPASSWORD`), never from the command line. Order: masters first (matched to ours by normalised code), then one fact table at a time (stage, transform month by month, verify, drop that table's staging).
- **If the job stops** (timeout, node move, SIGTERM, failover): start the same command again. It resumes at the first unverified month; a half-staged table resumes at its last complete statement; **a staged table that no longer holds the rows recorded for it is re-staged from the dump** (the tool says so). Nothing verified is repeated. No hand edit of `apsis_ctl` is ever needed or allowed.
- **A `failed` month**: `SELECT vendor_table, chunk_key, error FROM apsis_ctl.chunk WHERE state = 'failed'` (SQLSTATE and our own words, never data). `checksum mismatch`: stop, ask the lead. `staged rows differ from the recorded count`: run again. `none of the N vendor outlets matched`: the code format differs: stop, do not loosen anything, ask the lead. `missing column`: edit the mapping, rebuild the image, run again.
- **Changed settings** (source time zone or quantity unit) while verified months exist are refused. After the OWNER agrees: `apsis-migration.py reset-transform`, then run again (months are re-transformed from staging, or the table is re-staged).
- Progress: `SELECT vendor_table, state, count(*), sum(rows_out), sum(rows_skipped) FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' GROUP BY 1,2 ORDER BY 1,2`.
- Duration: planning figure 12 h, **estimate**: measure the first month of sales and tell the owner the measured rate if it is more than 2x. Disk: stop and extend storage if free space falls below 60 GB.

## 4. Reconcile, then sign-off (staging is still there; do not drop it yet)
`apsis-migration.py reconcile --dsn ... --db-role apsis_migrator --out /reports/reconciliation.json`. Exit code non-zero and the line "reconciliation FAILED: ..." on any failed gate; the JSON holds numbers only. Read it with the owner:
| Section | Pass condition |
|---|---|
| `tables.by_table` | `rows_parsed` = the vendor's `expected_rows`; `rows_loaded + rows_skipped` = staged rows; skipped share within the threshold and explained (zero dates); `collapsed_duplicate_rows` reported; a table without a primary key was accepted by the owner |
| `daily_money` | `days_differing` = 0, `max_abs_diff_mtk` = 0, totals equal |
| `sku_qty` | `skus_differing` = 0 |
| `time_zone` | `agreement` >= 0.80 (the vendor's own route-day visited counts against our recomputation). **If it fails, the source time zone or date column is probably wrong: stop, `reset-transform`, rerun with the other zone after the OWNER agrees** |
| `masters` | match shares within the thresholds; the unmatched-row shares are explained (vendor history holds more outlets than the October master data) |
| `memo_numbers` | `applicable` false, or none on more than one outlet |
| `control` | `default_partition_rows` = 0, `chunks_not_verified` = 0 |
Then: scope check by the lead (as a TSO test user through `dw.v_hist_*` / `dw.v_sales_with_history`, and `has_table_privilege` false for `api_rw`, `worker_rw`, `jobs_rw` on every history relation: the lane's test asserts it; the lead runs the same query in the final database). **OWNER signs** the reconciliation file plus the figures of section 0.

## 5. After sign-off
1. `apsis-migration.py cleanup --dsn ... --db-role apsis_migrator` drops the staging copy (refuses unless the latest reconciliation report is ok).
2. `apsis-migration.py finalize --dsn ... --db-role apsis_migrator` (read indexes, autovacuum back on, ANALYZE).
3. Revert the server parameters of section 2; `disallow_fill_locations()` if it was opened; the migrate role revokes the job's membership/login. The dump is deleted from `apsis-dump` by the owner's decision.
4. Opening balances are **not** loaded by this run (design section 11, questions Q21-Q27): the raw programme archives keep the vendor rows for a later step.

## 6. Rollback (any time)
The load is in an archive schema and the id map; only the optional coordinate fill touches live masters.
1. Coordinates (if filled), **before** dropping `apsis_map`: `UPDATE app.outlet o SET lat = f.prev_lat, lng = f.prev_lng, location_basis = f.prev_location_basis, location_confirmed = COALESCE(f.prev_location_confirmed, false) FROM apsis_map.fill_log f WHERE f.outlet_id = o.id AND o.lat IS NOT DISTINCT FROM f.new_lat AND o.lng IS NOT DISTINCT FROM f.new_lng;` (a point corrected after the fill is left alone).
2. `DROP SCHEMA apsis_hist CASCADE` (the five dw views go with it) and `DROP SCHEMA apsis_stage CASCADE`; keep or drop `apsis_map` / `apsis_ctl` (keys and counts only). **Re-running after a rollback needs the archive objects back**: they are created by a migration, so the lead re-applies that migration's DDL (the migration history still says "applied"); the runbook does not drop `apsis_ctl`, which keeps the checkpoints.
3. If anything outside these schemas looks wrong (it should not: the job role cannot write elsewhere), restore to the point written in section 0 into a new server and compare; never over the live server without the lead.
4. Re-runs are safe: keys are deterministic, so the same dump gives the same rows.

## 7. The operator must not
Copy the dump or any staging content out of the final account; paste profile or reconciliation figures into a public place (the repository is public); edit a control row by hand; loosen a threshold or disable a check to get a pass; set `APSIS_DEBUG_ERRORS` (it prints server error text, which can quote data); run the tool against the test account; put a password in `--dsn`.
