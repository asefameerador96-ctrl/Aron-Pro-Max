# Held: system code lists (audit AUD-DA-04)

These files were built and checked on 2026-10-07 (Opus checker PASS after fixes). They are not applied, because the
migration breaks backend-masterdata's fixture until `docs/requests/db-masterdata-code-list-fixture.md` is done.

To ship once that request is answered:
1. `git mv db/held/code-lists/system_code_lists.sql db/migrations/V00NN__system_code_lists.sql`, with NN the next free
   version. Remove "V0019" from its text.
2. Copy `04_dev_config_and_codes.sql` to `db/seed/` and `seed_README.md` to `db/seed/README.md`. Diff each against the
   current file first; keep later changes.
3. Restore the tests: `CodeListsTest.kt.txt` → `db/src/test/kotlin/com/aktcl/aron/db/CodeListsTest.kt`, and merge the
   `aDevDatabaseSeededBeforeV0019...` test plus the `valid_to IS NULL` change from `SeedTest.kt.txt` into `SeedTest.kt`.
4. Move `db-code-list-decisions.md` to `docs/requests/`, and fix the go-live checklist and decision lines in
   `docs/status/db.md` (they say "held").
5. Run `:db:test`, `tools/ci/migrations-check.sh`, and every backend suite (CI-style, as a superuser test login).
