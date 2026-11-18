# Request to the lead / infra lane: run the Apsis tooling tests in CI

`tools/apsis-migration/tests/test_apsis_migration.py` (Python 3 standard library, needs PostgreSQL 16 and `psql`, role with CREATEDB) is not run by `.github/workflows/ci.yml` today. Proposed step, in the job that already has the PostgreSQL service container (`ARON_TEST_PG_URL`):

```
- name: Apsis migration tooling tests (synthetic data)
  run: python3 -I tools/apsis-migration/tests/test_apsis_migration.py
```
About 3 minutes. Until it is wired, the proof that the tests pass is a local run (PostgreSQL 16, V0001..V0204 applied) stated in the status file. I did not edit `ci.yml` (not this lane's file).
