# Runbook: Apsis history migration into the final Azure account

## PHASE 1 (current): preserve and profile. Nothing is transformed, reconciled, cleaned up or deleted.
Owner decision D-OWNER-APSIS-FTP-01: the dump is copied as-is into the final account's private storage; its **structure** (table and column names, types, counts, shapes; never rows) may be used. Everything after this section ("Phase 2") is **parked**: every command except `profile` refuses unless the lead sets `APSIS_PHASE=2`.
1. **Preserve.** The owner (or the operator, after the owner's approval) copies the dump files **as they are** from the organisation's FTP server into the private container `apsis-dump` of the final account, using lane infra-final's landing wrapper (`docs/requests/apsis-dump-landing.md`). The dump never goes to a laptop, the repository, the test account, CI or a chat. Write the blob names and sizes in the hand-over note. Do not delete anything anywhere.
2. **Profile.** The operator runs the Container Apps job (or a shell next to the data) with the dump folder mounted read-only:
   `apsis-migration.py profile --dump-dir /data --out-dir /reports/apsis-profile` (add `--charset <name>` only if the owner has decided one; `--cap N` changes the distinct-value memory cap). The output directory must be **outside the repository** (the tool refuses otherwise) and is a private location in the final account.
   It writes four files: `apsis-profile.json` and `.md` (full) and `apsis-profile.sanitised.json` and `.md`. **No row and no value list is in any of them.**
3. **Exit code.** 0 = every file was read. 3 = one or more files were **refused**: the stderr lines name the table (its file stem) and the reason (REPLACE INTO, INSERT with a column list, a row-spanning INSERT, a data-only/mydumper file, a compressed or split part, text that is not UTF-8). A refusal is a result, not a failure to hide: send the lines to the lead; the dump may have to be exported again in a readable shape. Nothing is skipped silently.
4. **Hand over.** The two full files go to the **owner** only. The **sanitised pair** goes to the lead for review; it may be committed to the repository only after the lead has reviewed it. Never paste the full files into a public place (the repository is public).
5. **Stop.** Phase 1 ends with the profile. The lead and the owner read it, answer what it leaves open (design section 12: the last column says which questions the profile answers by itself), and the lead then enables phase 2.
What the profile contains, per table: engine, declared charset, row count, column names and declared types, primary key, indexes and foreign keys; per column: null share, distinct count (capped), min/max **length**, a shape histogram (digits-only, decimal, date-like, phone-like, e-mail-like, hash-like, bengali, latin, mixed, empty ...), an hour-of-day histogram for datetime columns, an **`enum_candidate` flag** (a boolean), and, in the **full file only**, min/max of numeric/date columns that are not id-, phone-, coordinate-, counter-, salary-, birth-like or near-unique; reference checks (shares of fact keys found in a master key column, exact and after normalisation). **Names** of tables and columns appear only if they are plain identifiers; anything else is printed as `[name-N]`.
6. **A value list (owner only, deliberate).** If a person decides that one flagged column is a status/type code and needs its values: `apsis-migration.py profile-values --dump-dir /data --out-dir /reports/apsis-profile --profile-json /reports/apsis-profile/apsis-profile.json --table T --column C`. It refuses unless the column carries `enum_candidate` in that profile, writes one owner-only file (`apsis-values-T-C.OWNER-ONLY.json`) in the private output directory and prints only a count. It never touches the sanitised files and the file must never be committed.
7. **Phase 2 stays off.** Every other command refuses unless `APSIS_PHASE=2` is set **and** the file `tools/apsis-migration/PHASE2_ENABLED` exists. The lead adds that file only when phase 2 is enabled; the operator never creates it.

---
# Phase 2 (PARKED: not to be run until the lead enables it)
Who runs it: the lead's operator session (or the infra-final lane) executes every command. **The owner's steps are approvals only** (marked OWNER). Nothing runs in the test account, CI or a laptop: the dump never leaves the final account.
Design and mapping: `docs/status/apsis-migration.md`. Landing and identity: `docs/requests/apsis-dump-landing.md`. Tool: `tools/apsis-migration/README.md`.

Status: written blind, not rehearsed against the real dump. Every check has a number to compare. The first real action is the dry run.

## 0. Preconditions (all must be true)
1. The final account exists; PostgreSQL has the October master data loaded (the master-data load is the first load: `docs/reference/master-data-loader.md`). Without it history keeps vendor codes with no scope ids and the reconciliation fails on the match shares.
2. The provisional migrations (as landed and renumbered by the lead: control/role, archive, dw views, job grants) are applied by the normal migrate role. Check: `SELECT count(*) FROM pg_namespace WHERE nspname IN ('apsis_ctl','apsis_map','apsis_hist','apsis_stage')` = 4 and `SELECT 1 FROM pg_roles WHERE rolname = 'apsis_migrator'`.
3. **The job's database login.** The migrate role runs `GRANT apsis_migrator TO "<entra principal of id-apsis-migration>"` (membership only; the job connects as that principal and the tool does `SET ROLE apsis_migrator`: `--db-role apsis_migrator`). The role can read only column-limited live masters and write only `apsis_ctl`, `apsis_map`, `apsis_stage` and the archive; it has no write on any live table.
4. Free storage on the server >= 250 GB, autogrow on (write the figure in the sign-off). High availability: the load runs **before any user is live on this server** (cutover is later). If the server already serves users, the lead decides explicitly how WAL volume and replica lag are handled; the runbook does not assume a quiet server.
5. The dump is in container `apsis-dump` (OWNER moved it; blob names and sizes in the sign-off): 26 files, about 52.5 GB.
6. **Reviewed column list** (`tools/apsis-migration/mapping/column_review.json`, or a private copy passed by `--review`: whether the vendor's column names may be public is the owner's call). Section 1 produces the suggestion; a person reviews **every** column; the run refuses a table with an unclassified column. **Check specifically that no column that may carry a balance (dues, credit, paid, points, targets, stock, memo number) is in `drop`**: the dump is deleted after sign-off and a dropped column is gone. `mapping/expected_counts.json` must hold the vendor's **exact `count(*)`** figures (Q18), not `information_schema` estimates.
7. **Restore point**: note the UTC time; confirm backup retention >= 7 days and a backup within 24 h; take an on-demand backup/snapshot if the SKU supports it. Write the time in the sign-off. Rollback (section 6) depends on it.
7b. **History reader (not part of the load, but the scope check below needs it):** the provisional migrations create the NOLOGIN role `hist_ro` (SELECT on the five history views only); the API's history pool login is a member of it (`docs/requests/apsis-history-reader.md`). **Report wiring is not built**: no API or web code reads the history views yet.
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
| `time_zone_hint`, `hour_histogram_stored` and **`date_column_agreement`** per table | the hint is `utc` or `dhaka_local`, the same for every table; where a table has both a `date` and a `datetime` column, one zone shows share near 1.0 and the other near 0 among `rows_where_zones_disagree` | `unclear`, different per table, or both shares similar: ask the owner; do not guess. **The OWNER signs the zone** (`UTC` or `Asia/Dhaka`); the tool has no default and never switches it |
| `mapping_coverage` / `mapping_errors` | every target column `mapped` or `intentionally_absent`; `mapping_errors` empty | an `UNMAPPED` target column: edit `mapping/columns.json` (a wrong guess of a column is the commonest silent error: read each source name) |
| `mojibake_rows`, `undecodable_rows` | 0 | non-zero: the character set is not UTF-8 or the text was double-encoded: ask the owner (Q2); never pass `--charset` as a guess |
| `columns[].suggested` | credential/personal suggestions are plausible | the **reviewer** edits the suggestion into `column_review.json`: every column in `keep` or `drop`; credential-looking columns in `keep` need an explicit ack; non-personal, non-credential columns stay (the programme archives keep them for later) |
| `zero_dates`, `undecodable_rows` | small | large: ask the owner |
| `float_money_columns` | none | any: money cannot be exact; owner decides (design Q9) |
| `has_primary_key` | true for the fact tables | false for sales: owner decision (thresholds `accept_full_row_key`) before the run |
| `duplicate_keys` | 0 or small | note it |
OWNER approves the settings, recorded in `apsis_ctl.run`: `--source-tz`, `--qty-unit` (base unless the vendor counts packs), whether imported coordinates are confirmed (`--confirm-locations`) and whether missing outlet coordinates are filled from the dump (`--fill-locations`). Thresholds in `mapping/columns.json` ("thresholds") are confirmed too.

## 2. Prepare the database (operator, platform admin)
Server parameters for the window, reverted in section 5: `max_wal_size = 64GB`, `checkpoint_timeout = 30min`, `wal_compression = on`, `maintenance_work_mem = 2GB`. (The job sets `synchronous_commit = off` for its own session in code.) Nothing else is scheduled on the server. If `--fill-locations` was approved: the migrate role runs `SELECT apsis_ctl.allow_fill_locations()` (a time-boxed UPDATE grant on four outlet columns) and `disallow_fill_locations()` after the run.

## 3. Run (restartable)
`apsis-migration.py run --dump-dir /data --dsn "<host/db/user, NO password>" --db-role apsis_migrator --review column_review.json --source-tz <signed zone> --qty-unit <approved> [--charset <only if the owner decided>] [--fill-locations --confirm-locations]` (the tool refuses without an explicit `--source-tz`, without `--db-role apsis_migrator`, and with a password in `--dsn`)
The password or Entra token comes from the environment (`PGPASSWORD`), never from the command line. Order: masters first (matched to ours by normalised code), then one fact table at a time (stage, transform month by month, verify, drop that table's staging).
- **If the job stops** (timeout, node move, SIGTERM, failover): start the same command again. It resumes at the first unverified month; a half-staged table resumes at its last complete statement; **a staged table that no longer holds the rows recorded for it is re-staged from the dump** (the tool says so). Nothing verified is repeated. No hand edit of `apsis_ctl` is ever needed or allowed.
- **A `failed` month**: `SELECT vendor_table, chunk_key, error FROM apsis_ctl.chunk WHERE state = 'failed'` (SQLSTATE and our own words, never data). `checksum mismatch`: stop, ask the lead. `staged rows differ from the recorded count`: run again. `none of the N vendor outlets matched`: the code format differs: stop, do not loosen anything, ask the lead. `missing column`: edit the mapping, rebuild the image, run again.
- **Changed settings** (source time zone or quantity unit) while verified months exist are refused. After the OWNER agrees: `apsis-migration.py reset-transform`, then run again (months are re-transformed from staging, or the table is re-staged).
- Progress: `SELECT vendor_table, chunk_key, state, stmt_ordinal, rows_parsed, rows_out, rows_skipped FROM apsis_ctl.chunk ORDER BY 1,2` (the `_stage` row shows the statements and rows staged so far, which is the progress of a multi-hour file).
- Duration: planning figure 12 h, **estimate**: measure the first month of sales and tell the owner the measured rate if it is more than 2x. Disk: stop and extend storage if free space falls below 60 GB.

## 4. Reconcile, then sign-off (staging is still there; ask for any second reconciliation NOW: after `cleanup` it can no longer run)
`apsis-migration.py reconcile --dsn ... --db-role apsis_migrator --out /reports/reconciliation.json`. Exit code non-zero and the line "reconciliation FAILED: ..." on any failed gate; the JSON holds numbers only. Read it with the owner:
| Section | Pass condition |
|---|---|
| `tables.by_table` | **every one of the 26 expected tables is present and `loaded`** (a table never staged, a refused file or an `--only` run fails here); `rows_parsed` = the vendor's `expected_rows`; loaded + skipped + rejected = staged; skipped and rejected shares within thresholds and explained (zero dates, dates outside the window); `collapsed_duplicate_rows` reported; a table without a primary key was accepted by the owner |
| `daily_money`, `sku_qty` | no differing day or SKU; totals equal |
| `money_quality` | zero-money, zero-quantity and NULL-amount shares within the limit; `negative_money_lines` explained |
| `mapping_coverage` | no `UNMAPPED` target column |
| `time_zone` | **informational**: the chosen zone and the route-day agreement. The zone was decided and signed before the run; a low agreement is a reason to ask the owner and the lead, never to switch zone by yourself |
| `masters` | match shares within the thresholds; unmatched shares explained; `vendor_codes_ambiguous` and `live_codes_ambiguous` listed (counts): those codes were deliberately not matched |
| `memo_numbers` | `applicable` false, or none on more than one outlet |
| `control` | `default_partition_rows` = 0, `chunks_not_verified` = 0 |
Then the **scope check, runnable today** (the lead, in the final database): `SET ROLE hist_ro; SELECT count(*) FROM dw.v_hist_sales_line WHERE territory_id = ANY(ARRAY[<one territory id>]::bigint[]);` returns only that territory's rows (compare with a count by territory as the migrate role); `SELECT count(*) FROM dw.v_hist_sales_line WHERE territory_id IS NULL` is the unresolved-geography share (national readers only); `SELECT count(*) FROM app.outlet` as `hist_ro` must fail with `permission denied`; and `has_table_privilege` is false for `api_rw`, `worker_rw`, `jobs_rw` on every history relation (the lane's test asserts this, including after `apply_db_role_grants()`). **Report wiring is not built**: a TSO test user cannot yet open a history report; that waits for the server lane (`docs/requests/apsis-history-reader.md`).
**OWNER signs** the reconciliation file plus the figures of section 0, and fills the sign-off file (section 5) for the opening-balance questions.

## 5. After sign-off
1. **The owner's sign-off file** `signoff.json`: `{"q21": "answered|waived", ... "q33": "answered|waived", "signed_by": "<name>"}` (design section 11 and 12). `apsis-migration.py cleanup --dsn ... --db-role apsis_migrator --signoff signoff.json` drops the staging copy; it **refuses** unless the latest reconciliation passed and every question is answered or waived. **Do not delete the dump from `apsis-dump` before this succeeds** (the owner's decision, after it).
2. `apsis-migration.py finalize --dsn ... --db-role apsis_migrator` (read indexes, autovacuum back on, ANALYZE).
3. Revert the server parameters of section 2; `disallow_fill_locations()` if it was opened; the migrate role revokes the job's membership/login.
4. Opening balances are **not** loaded by this run (design section 11): the raw archive keeps the reviewed columns of every table and `sales_line.extra` every other reviewed column of a sales row, for a later step.

## 6. Rollback (any time)
The load is in an archive schema, the id map and the control tables; only the optional coordinate fill touches live masters.
1. Coordinates (if filled), **first**: `UPDATE app.outlet o SET lat = f.prev_lat, lng = f.prev_lng, location_basis = f.prev_location_basis, location_confirmed = COALESCE(f.prev_location_confirmed, false) FROM apsis_map.fill_log f WHERE f.outlet_id = o.id AND o.lat IS NOT DISTINCT FROM f.new_lat AND o.lng IS NOT DISTINCT FROM f.new_lng;` (a point corrected after the fill is left alone).
2. **`SELECT apsis_hist.reset_history();`** as the migrate role (the job role cannot run it): empties the archive, the rejects, the id map and lookups, **the control checkpoints** and the staging copy. A re-run then reloads everything (a test does rollback and re-run end to end and compares the rows). Nothing else is needed; no schema is dropped and no migration re-applied.
3. To remove the history for good instead: `DROP SCHEMA apsis_hist CASCADE` (the five dw views go with it) and `DROP SCHEMA apsis_stage CASCADE`; the migration history still says "applied", so a later re-run needs those objects re-created by a new migration.
4. If anything outside these schemas looks wrong (it should not: the job role cannot write elsewhere), restore to the point written in section 0 into a new server and compare; never over the live server without the lead.
5. Re-runs are safe: keys are deterministic, so the same dump gives the same rows.

## 7. The operator must not
Copy the dump or any staging content out of the final account; paste profile or reconciliation figures into a public place (the repository is public); edit a control row by hand; loosen a threshold or disable a check to get a pass; set `APSIS_DEBUG_ERRORS` (it prints server error text, which can quote data); run the tool against the test account; put a password in `--dsn`.
