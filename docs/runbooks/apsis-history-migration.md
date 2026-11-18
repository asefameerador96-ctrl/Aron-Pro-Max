# Runbook: Apsis history migration into the final Azure account

Owner of the run: the lead's operator session (or the infra-final lane) executes every command. **The owner's steps are approvals only** (marked OWNER). Nothing here runs in the test account, CI or a laptop: the dump never leaves the final account.
Design and mapping: `docs/status/apsis-migration.md`. Landing and identity: `docs/requests/apsis-dump-landing.md`. Tool: `tools/apsis-migration/README.md`.

Status of this runbook: written blind, not yet rehearsed against the real dump; every "check" below has a number to compare with, and the first real action is the dry run.

## 0. Before anything (preconditions, all must be true)
1. The final account exists; PostgreSQL has the October master data loaded (the master-data load is the first load: `docs/reference/master-data-loader.md`). Without it the history keeps vendor codes with no scope ids.
2. The three provisional migrations (V0260..V0262 as landed and renumbered by the lead) are applied by the normal migrate role. Check: `SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('apsis_ctl','apsis_map','apsis_hist')` = 3.
3. Free storage on the PostgreSQL server >= 250 GB, storage autogrow on. Check in the portal/CLI; write the figure in the sign-off.
4. The dump is in container `apsis-dump` (OWNER moved it; blob names and sizes written in the sign-off). Total about 52.5 GB, 26 tables.
5. **Restore point**: note the UTC time and run a PITR-capable check (backup retention >= 7 days, latest backup within 24 h) and take an on-demand backup/snapshot if the SKU supports it. Write the restore point time in the sign-off. Rollback (section 5) depends on it.
6. Time window: outside selling hours (night, Dhaka), no cutover wave in progress.
7. OWNER approvals (the only owner actions): (a) approve starting the dry run; (b) after the dry run, approve the settings below; (c) approve the sign-off of the reconciliation.

## 1. Dry run (counts only; no database writes)
Job command: `apsis-migration.py profile --dump-dir /data --out /reports/profile.json`.
Read `profile.json` (numbers only) and check:
| Check | Expected | If not |
|---|---|---|
| tables found | 26 file(s), names as the inventory | missing table: stop, ask the owner |
| rows per table | inventory counts (e.g. trans_sales_by_outlet 122,210,777) within 0 | difference: the dump is not the one counted: stop |
| `chunks` (months) | 2025-11 .. 2026-09 only; `unknown` small | many `unknown` or other months: dates parse wrongly, stop |
| `zero_dates` | small, listed | large: ask owner |
| `dropped_columns` | credential/device/personal columns | any column the lead did not expect: log it; tables that are a token/OTP store are excluded with `--only` |
| `float_money_columns` | none | any: money cannot be exact; owner decides (design Q9) |
| `has_primary_key` | true for transactional tables | false: rows are keyed by whole-row hash; accept and note |
| `duplicate_keys` | 0 or small | note the figure; the loader collapses them |
Then OWNER approves these settings, which are recorded in `apsis_ctl.run`: `--source-tz` (UTC unless the profile's hour histogram says otherwise: design Q4), `--qty-unit` (base unless the vendor counts packs: Q9), whether imported coordinates are confirmed (`--confirm-locations`, M01 ruling) and whether to fill missing outlet coordinates (`--fill-locations`).
Update `mapping/columns.json` from the profile's column list (an operator edit committed through the lane; the job image is rebuilt). A required column without a match stops that step with `missing:<column>`.

## 2. Prepare the database for the load window (operator, platform admin)
Set (server parameters; revert in section 4): `max_wal_size = 64GB`, `checkpoint_timeout = 30min`, `wal_compression = on`, `maintenance_work_mem = 2GB`. The loader role sets `synchronous_commit = off` for its own session only. Confirm no other job (nightly, partition maintenance) is scheduled in the window.
Check: `SHOW max_wal_size` returns the new value.

## 3. Run (restartable)
Command: `apsis-migration.py run --dump-dir /data --dsn <entra connection> --source-tz <as approved> --qty-unit <as approved> [--fill-locations --confirm-locations]`.
What happens, in this order: masters (small tables) -> staging of each table -> masters match -> small facts -> promotion/target archives -> geo by month -> sales by month. Each table and month is a checkpoint in `apsis_ctl.chunk` (`pending -> staged -> transformed -> verified`).
- **If the job stops** (timeout, node move, SIGTERM): start the same command again. It resumes at the first chunk not `verified`; a half-staged table resumes at its last statement. Nothing is repeated that is verified.
- **If a chunk is `failed`**: read `apsis_ctl.chunk.error` (table, chunk, statement number, no data). Typical: `checksum mismatch` (do not continue; ask the lead), `missing column` (edit the mapping, rebuild, rerun).
- Progress check at any time: `SELECT vendor_table, state, count(*), sum(rows_out) FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' GROUP BY 1,2 ORDER BY 1,2;`.
- Expected duration: planning figure 12 h (design section 4); measure the first month of sales and state the measured figure to the owner before continuing the rest if it is more than 2x.
- Disk: the staging copy of a fact or archive table is dropped as soon as all its chunks are verified (the sales staging and the master tables stay until the reconciliation, section 4); if the server storage free space falls below 60 GB stop and extend storage before resuming.

## 4. Finish
1. `apsis-migration.py finalize --dsn ...` (indexes on the archive, autovacuum back on for the archive partitions, ANALYZE).
2. Revert the server parameters of section 2. `DROP SCHEMA apsis_stage CASCADE` (staging holds no personal data, but it is a full copy of the numbers).
3. `apsis-migration.py reconcile --dsn ... --out /reports/reconciliation-<run>.json`. The command exits non-zero if any section is not ok. The file holds numbers only.
4. Check against the report (the lead reads each line to the owner):
| Section | Pass condition |
|---|---|
| `tables` | every table `rows_in` equals the dump's inventory count; `skipped_no_date_or_key` is small and explained (zero dates) |
| `months` | every transactional month `verified`; per-month counts add up to the table totals |
| `daily_money` | `days_differing` = 0 and `max_abs_diff_mtk` = 0; the two totals are equal |
| `sku_qty` | `skus_differing` = 0 |
| `memo_numbers` | `applicable` false, or `numbers_on_more_than_one_outlet` = 0 |
| `outlets_per_route`, `section_summary` | informational: the lead reviews the differing counts with the owner (the vendor's own summary is compared with our recomputation) |
| `control` | `default_partition_rows` = 0, `chunks_not_verified` = 0; `sales_rows_without_outlet_id` equals the outlets stubbed (history of outlets we do not carry) |
5. Scope check (lead): as a TSO test user and as the sync role, confirm history is invisible to the sync path (`has_table_privilege` false for `api_rw`, `worker_rw`, `jobs_rw`: the tool's test asserts it; the lead runs the same query in the final database) and that a TSO sees only their territory through `dw.v_hist_*`.
6. OWNER approves the sign-off (the reconciliation file plus the figures of section 0). Only then is the dump deleted from `apsis-dump` (owner's decision) and the job's role grants revoked.

## 5. Rollback (any time before or after sign-off)
The load is into marked rows and an archive schema, so it is deleted by marker:
1. History: `DROP SCHEMA apsis_hist CASCADE;` (drops the four dw views with it) and `DROP SCHEMA apsis_stage CASCADE;`. The id map and control tables (`apsis_map`, `apsis_ctl`) hold only keys and counts; keep them for the record or `DROP SCHEMA ... CASCADE` too.
2. Live masters: the tool changed only what `--fill-locations` filled; undo exactly from the log:
   `UPDATE app.outlet o SET lat = NULL, lng = NULL, location_basis = f.prev_location_basis, location_confirmed = COALESCE(f.prev_location_confirmed, false) FROM apsis_map.fill_log f WHERE f.outlet_id = o.id;`
   (run before dropping `apsis_map`).
3. If anything outside these schemas looks wrong (it should not: the tool writes nowhere else), restore to the PITR point written in section 0 into a new server and compare; do not restore over the live server without the lead.
4. Re-running after a rollback is allowed: the keys are deterministic, so the same dump gives the same rows.

## 6. What the operator must not do
Do not copy the dump or any `apsis_stage` content out of the final account; do not paste profile or reconciliation figures into a public place (the repository is public); do not edit a verified chunk by hand; do not disable the checksum compare; do not run the tool against the test account.
