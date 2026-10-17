# Load-test data (N-054, owner qa)

A synthetic fleet on a **scratch** database, then a day of field traffic through the real API. Never on production
or the shared dev database (docs/28: run small in the test account, full size in the final account).

1. **Fleet.** `psql -d <scratch db> -v srs=8500 -f tools/loadgen/generate.sql`. The database must have every
   migration and `db/seed` applied, with the seed password set (`ARON_SEED_PASSWORD`). The SQL scales the fleet by
   `srs`: 8,500 SRs give 9,842 users, 11,336 routes (Daily, 3F, 2F), 464,771 outlets and 8,500 bound phones.
2. **One synthetic day.** `ARON_LOADGEN_PASSWORD=<seed password> python3 tools/loadgen/replay.py --api <origin> --srs N`.
   For each SR it runs login, then the morning bundle, then a day of visits, memos, lines, closes and collections in
   gzip batches, then the whole day resent. It reconciles device counts against server counts per SR and prints
   latency p50/p95 per phase. The exit code is 0 only when every SR reconciles.
3. **Self-test** (offline): `python3 tools/loadgen/test_replay.py`.

Measured on the qa container, 2026-10-17 (local PostgreSQL 16, other builds running at the same time):

| Step | Size | Result |
|---|---|---|
| generate.sql | srs=8500 | 75 s (target: under 30 min) |
| generate.sql | srs=50 | 2.5 s |
| replay.py | 50 SRs, local API | 8,187 records in 92 s; 50 of 50 SRs reconciled; resend all duplicate |

The latencies of the local run are not performance numbers: one shared container, with a 10,000-schedule fuzz run
in parallel. The performance and load figures belong to N-058 on Azure Load Testing.
