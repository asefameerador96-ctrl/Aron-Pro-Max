# Load-test data (N-054, owner qa)

A synthetic fleet on a **scratch** database, then a day of field traffic through the real API. Never on production
or the shared dev database (docs/28: run small in the test account, full size in the final account).

1. **Fleet.** `psql -d aron_load<anything> -v srs=8500 -f tools/loadgen/generate.sql`. It refuses a database whose
   name does not start with `aron_load` (unless `-v scratch=yes`), a second load, `srs < 1` and a seed password
   that is not an Argon2 hash. It runs in one transaction, so a failure leaves nothing behind. The database must have every
   migration and `db/seed` applied, with the seed password set (`ARON_SEED_PASSWORD`). The SQL scales the fleet by
   `srs`: 8,500 SRs give 9,842 users, 11,336 routes (Daily, 3F, 2F), 464,771 outlets and 8,500 bound phones.
2. **One synthetic day.** `read -rs ARON_LOADGEN_PASSWORD; export ARON_LOADGEN_PASSWORD` (the seed password,
   kept out of shell history), then `python3 tools/loadgen/replay.py --api http://127.0.0.1:<port> --srs N`. A
   non-loopback API needs `--i-know-this-is-scratch`. Use `--run 2`, `--run 3`, ... (up to 9) to replay the same day
   on the same database again; each run sells other quantities, because the server quarantines identical content to
   one outlet on one day as a double tap.
   For each SR it runs login, then the morning bundle, then a day of visits, memos, lines, closes and collections in
   gzip batches, then the whole day resent. It reconciles device counts against server counts per SR and prints
   latency p50/p95 per phase. The reconciliation is over this run: `/v1/sync/totals` before and after must differ by
   exactly the device's records per type and its money (active memos, gross, net, paid, due, collected). Every batch
   must be answered 200, and every record of the resend `duplicate`. An SR with nothing to send today (Friday, a
   holiday, only 3F/2F routes not planned today) fails unless `--allow-empty` is given. A run with fewer than
   `--min-records` records (default 1) always fails.
3. **Self-test** (offline): `python3 tools/loadgen/test_replay.py`.

Measured on the qa container, 2026-10-17 (local PostgreSQL 16, other builds running at the same time):

| Step | Size | Result |
|---|---|---|
| generate.sql | srs=8500 | 75 s (target: under 30 min) |
| generate.sql | srs=50 | 2.5 s |
| replay.py | 50 SRs, local API (first version) | 8,187 records in 92 s; 50 of 50 SRs reconciled; resend all duplicate |
| replay.py | 40 SRs, runs 1, 2 and 3 on one database | about 3,800 records each run; 40 of 40 reconciled each run (counts and money); 14 SRs with no route planned on that Sunday listed as empty |

The latencies of the local run are not performance numbers: one shared container, with a 10,000-schedule fuzz run
in parallel. The performance and load figures belong to N-058 on Azure Load Testing.
