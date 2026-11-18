# tools/apsis-migration

Loads the vendor (Apsis) database dump into the FINAL Azure account's PostgreSQL, restartably, and proves the result with counts and
checksums only. Built blind (nobody in this repository has seen the dump). Python 3 standard library + `psql`; no other dependency.
Design: `docs/status/apsis-migration.md`. Operator steps: `docs/runbooks/apsis-history-migration.md`.

```
apsis-migration.py profile   --dump-dir D [--out profile.json]      dry run: counts only, no database
apsis-migration.py run       --dump-dir D --dsn "..." [--dry-run] [--source-tz UTC] [--qty-unit base|pack]
                             [--fill-locations --confirm-locations] [--only TABLE ...]
apsis-migration.py reconcile --dsn "..." [--run-id N] --out reconciliation.json
apsis-migration.py finalize  --dsn "..."                            indexes, autovacuum back on, ANALYZE
```

* `apsis_migration/mysqldump.py` streams mysqldump SQL (`.sql`, `.sql.gz`) or TSV; `staging.py` creates `apsis_stage.<table>` (types mapped,
  temporal values kept as text, no transformation), adds `_row_uuid` (UUID v5), `_chunk`, `_stmt`, and drops credential, device and
  personal-data columns by name; `runner.py` orchestrates; `keys.py` holds the key rule.
* `mapping/columns.json`: canonical column -> candidate vendor column names. Edit it after the schema-only profile; a required column with
  no match stops that step (nothing is invented).
* `sql/`: `10` masters match (never creates a live master), `15` optional coordinate fill, `20-25` fact and archive transforms (one chunk
  per transaction, idempotent), `reconcile.sql` (numbers-only report).
* `provisional-migrations/`: V0260..V0262 (control and id map, `apsis_hist` archive, `dw.v_hist_*` views). Deliberately NOT in `db/migrations`.
* `tests/`: synthetic data only. `ARON_TEST_PG_URL` (role with CREATEDB) or the libpq environment; run
  `python3 -I tools/apsis-migration/tests/test_apsis_migration.py`. About 3 minutes (a database per test cloned from a template built from
  V0001..V0204 + the provisional migrations).

Never commit a dump, a profile of the real dump, or a reconciliation report; none contains a data row, but they are the owner's to keep.
