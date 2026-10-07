# Lane brief: db

Session model: **Opus** (docs/29 s3). Owns: `db/` (forward-only migrations `db/migrations/V####__*.sql`, seed `db/seed`, `db/held/`), the data dictionary tooling (`tools/data-dictionary`), the `dw` read views and database roles, partitioning and retention jobs.

Read `docs/lanes/README.md` first, then **`docs/status/db.md`** (its handoff: done, next rows, traps), then `docs/16` (data platform: principles P1 to P14, naming, money, business date, schemas `app`, `dw`, `cfg`, roles) only for the sections a row names, and `docs/31` s3 (data standards: the database is a product).

Binding rules:
- Migrations are **forward-only, checksum-checked, expand/contract**; a shipped migration is never edited; CI runs squawk and the checksum gate (`tools/ci`). One migration per concern, numbered in order; lanes' requests (`docs/requests/db-*.md` and requests addressed to db in other files) are answered in `docs/status/db.md` and, when done, in the request file.
- Every new column and table has a comment and an owner for the data dictionary (CI fails on an undocumented column).
- Device-originated rows are keyed by client UUID; server surrogate keys are separate; every transaction table has UTC timestamps plus an Asia/Dhaka `business_date`; money is integer milli-taka; append-only for money and audit.
- NULL semantics are part of the contract: for the integrity columns NULL means "unknown" (older phone), an empty array means "clean".
- Other products read `dw` views and the domain-event outbox, never `app` tables; keep the views and the column dictionary stable.
- Test against PostgreSQL 16 (`service postgresql start`; `ARON_TEST_PG_URL` set in the same shell command as gradle); the whole backend suite must stay green after every migration (your migrations can break other lanes' fixtures: run `:backend:masterdata`, `:backend:config`, `:backend:auth`, `:backend:app` and the db tests before pushing, and look at `docs/requests/db-masterdata-code-list-fixture.md`).
- Dev is shared: a migration reaches dev only through CI deploy. Never create fleet-sized resources; test account is pilot size (docs/28).
