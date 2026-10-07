# Request (db → infra): keep the data dictionary in CI and publish it

**What exists (V0016, `tools/data-dictionary`):** every table, view and column in `app` and `dw` carries a
`COMMENT ON` with owner lane, capture mode, retention class and PII class (convention in the V0016 header).
`DataDictionaryTest` (in `:db:test`) fails when:
- a new table, view or column has no comment, or its metadata line is malformed;
- a table's PII class is not the highest class of its columns;
- `docs/data-dictionary.md` no longer matches the migrated schema.

`ci.yml` already runs `:db:build`, which includes `:db:test`, so the guard is in force today with no change.

**Asked (infra, `.github/workflows/ci.yml`):**
1. Keep `:db:build` in the required check (do not split `:db:test` out of the required set).
2. Optional: on a failure of `DataDictionaryTest.theCommittedDictionaryMatchesTheSchema`, print the hint
   "run `tools/data-dictionary/render.sh` and commit `docs/data-dictionary.md`".
3. Optional: upload `docs/data-dictionary.md` as a build artifact on `main` so the BI team can read it without a clone.

**How a lane regenerates it:** `tools/data-dictionary/render.sh` (needs `ARON_TEST_PG_URL` or Docker), then commit
`docs/data-dictionary.md` with the migration that changed the schema.
