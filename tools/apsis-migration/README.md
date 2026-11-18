# tools/apsis-migration

Loads the vendor (Apsis) database dump into the FINAL Azure account's PostgreSQL, restartably, and proves the result with counts and checksums
only. Built blind (nobody in this repository has seen the dump). Python 3 standard library + `psql`; no other dependency.
Design and status: `docs/status/apsis-migration.md`. Operator steps: `docs/runbooks/apsis-history-migration.md`. Dump landing and the job's
identity: `docs/requests/apsis-dump-landing.md`. Scoped reader of history: `docs/requests/apsis-history-reader.md`.

**Phase 1 (current): only `profile` (and the owner-run `profile-values`) run.** Every other command refuses unless `APSIS_PHASE=2` is set **and** the file `tools/apsis-migration/PHASE2_ENABLED` exists (the lead adds it; phase 2 is parked until the real profile exists). `profile` writes no value of the dump, only names that pass the identifier rule, counts, lengths, shapes, flags and (full file only) ranges of non-identifying columns.

```
apsis-migration.py profile   --dump-dir D --out-dir OUTSIDE_THE_REPO [--charset ..] [--cap N] [--refs ..]   four files (full + sanitised JSON/MD), exit 3 on a refused file; no database
apsis-migration.py profile-values --dump-dir D --out-dir OUT --profile-json P --table T --column C        owner-run: one enum_candidate column into an owner-only file
apsis-migration.py run       --dump-dir D --dsn "host=.. dbname=.. user=.." --db-role apsis_migrator --review column_review.json
                             --source-tz UTC|Asia/Dhaka [--qty-unit base|pack] [--charset ..] [--fill-locations --confirm-locations] [--only TABLE ...]
apsis-migration.py reconcile --dsn ... --db-role apsis_migrator [--run-id N] --out reconciliation.json     non-zero exit on any failed gate
apsis-migration.py cleanup   --dsn ... --db-role apsis_migrator --signoff signoff.json                     drops the staging copy (owner sign-off Q21-Q33)
apsis-migration.py finalize  --dsn ... --db-role apsis_migrator                                           read indexes, autovacuum, ANALYZE
apsis-migration.py reset-transform --dsn ... --db-role apsis_migrator                                     after a settings change
```
The tool refuses to run without `--db-role apsis_migrator`, with a password in `--dsn`, or without an explicit `--source-tz`.

* `apsis_migration/profiler.py` (phase 1): structure, counts, lengths, shapes, enum-candidate flags, hour histograms, reference shares, ranges of non-identifying columns in the full file; no row and no value list is ever written. `mapping/profile_refs.json`: the reference checks.
* `apsis_migration/mysqldump.py` streams mysqldump SQL (`.sql`, `.sql.gz`; one `INSERT ... VALUES (...),(...);` per line; mydumper's split layout is
  **not** supported) or TSV, strictly as UTF-8 (a decode failure stops the run; `--charset` only on the owner's decision). `staging.py` creates logged
  `apsis_stage.<table>` tables (types mapped, no transformation of values), only the columns the **reviewed list** keeps, plus `_row_uuid` (UUID v5),
  `_chunk`, `_stmt`. `runner.py` orchestrates; `gates.py` decides pass/fail; `safety.py` validates every name from the dump; `keys.py` holds the key rule.
* `mapping/columns.json`: canonical target column -> candidate vendor column names (first present wins; `{direct, ref}` when a fact carries an id);
  `absent_ok` lists columns that may be missing; any other unresolved column stops the table. `mapping/expected_counts.json`: the vendor's row counts.
  `mapping/column_review.json` (to be written after the dry run): every column of every table as keep or drop.
* `sql/`: `10` masters match (never creates a live master), `15` optional coordinate fill, `20-25` fact transforms and the raw archive (one chunk per
  transaction, idempotent), `reconcile.sql` (numbers only).
* `provisional-migrations/`: V0260..V0263 (role, control, archive, dw views, `hist_ro`, job grants). Deliberately NOT in `db/migrations`.
* `tests/`: synthetic data only. `ARON_TEST_PG_URL` (role with CREATEDB) or the libpq environment; run `python3 -I tools/apsis-migration/tests/test_apsis_migration.py`
  (`tests/test_profiler.py` (23 tests) needs no database and takes seconds; `test_apsis_migration.py` is phase 2, about 10 minutes: a database per test cloned from a template built from V0001..V0204 + the provisional migrations; the tool runs as `apsis_migrator`).

Never commit a dump, a profile of the real dump, or a reconciliation report; none contains a data row, but they are the owner's to keep.
