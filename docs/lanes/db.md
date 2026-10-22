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

## Wave 4 (owner directive 2026-10-22, docs/34 s1, s2, s4.2, s4.4)

Migrations (forward-only, one concern each, with `dw` views and the data dictionary updated): role codes
`NATIONAL` and `BRAND` (+ TOP migration as backend-authz requests), `user_module_grant`, `user_brand_scope`,
`house.is_active` plus a one-off de-duplication of houses by (territory, name), `route.kind` (normal, manual,
dummy_br, dummy_institutional), `outlet.channel` NOT NULL (GT, DCC, Astha, RCC, MT, HoReCa; backfill rule
documented), `route_monthly_target` (route × SKU × month, base units), `calendar` (date, is_friday,
is_holiday, name), the dashboard summary tables backend-reports specifies, call `distance_m` / `within_fence`
/ `completed` where missing, `final_submit_log` (zone × business date, first wins), `device_command` for
mdm, and the SKU pack sizes (`base_per_pack` 20/10 per cigarette SKU, per-SKU for bidi, 25 for lighters, 1
for matches; `entry_unit_default` pack/pack/pack/dozen; `cfg.sale.qty_entry_unit` default updated;
`cfg.sale.allow_loose_base_units` false). Then the seed: Gulshan and Banani replace Mirpur (docs/34 s4.4:
house `H-GUL`, zones `Z-GUL`, `Z-BAN`, 60 outlets on real street positions with channels, `sr1001`'s routes,
Friday-aware schedule, a September-like `route_monthly_target` and a `calendar` with the Bangladesh holidays
of the current quarter); `06_dev_real_route.sql` replaced; dev re-seeded through the CI deploy. Write the
seed → table map in `docs/evidence/seed-gulshan-banani.md` for qa's data-flow trace.
