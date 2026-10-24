# Data architecture review, 2026-10-25

The owner asked whether the data structure, database, pipelines and lake are built rightly for later reuse. Reviewed on INT `c427d842` (migrations to V0113) against CLAUDE.md, docs/10, docs/24 s7, docs/31 and docs/33 Part C. A second reviewer checked every finding against the code.

## 1. Verdict

The foundation is right: every phone record is keyed by its own ID so a retry can never double a sale, money and packet counts are exact integers that can be recomputed from each stored line, synced rows are never edited or deleted, every row carries UTC time plus the Dhaka business date, and the schema is documented and gated in CI, so nothing captured today is lost or ambiguous at source. What is not yet right is the reuse layer on top: the warehouse (`dw`) re-labels old days with today's zones, there is no tool to rebuild or reconcile it, nothing separates test data from real data, the approved reading surface stops at daily totals (no memo lines), the event feed covers 4 of 11 event types, and there is no data lake yet. Do first the two fixes that get harder every day (tag each row's origin before test and real sales mix, and make `dw` use the zone frozen on each sale, with a rebuild tool), then add memo-line views and start a pilot-size nightly Parquet export.

No finding is a blocker: every gap can be fixed forward and backfilled from stored rows, except two time-sensitive ones. Untagged test rows cannot be told apart later, and "what the dashboard showed on day X" is not kept anywhere.

## 2. What is right

- **No double counting, by construction.** Client UUID keys, the ingest registry with payload hashes, batch replay and business keys (memo number, one void, one final submit) stop duplicates at four layers (V0007, V0008, V0021), proven by `SyncConvergenceFuzzTest` and `DataFlowTraceTest` (phone counts = tables = warehouse = dashboard).
- **Exact money and units.** Integer milli-taka; memo arithmetic and `qty_base = qty_entered x pack_factor` are database CHECKs (V0007:723-792). Each line keeps its own pack factor and price, so history stayed correct when V0104 changed the lighter pack size.
- **Append-only history.** Synced rows are write-once, states move only forward, deletes are tombstones, the audit log is hash-chained (V0007, V0010).
- **One clock, one calendar.** UTC plus Dhaka business date, clock evidence and config version on every capture row; month partitions; shared `dim_date`.
- **Run like a product.** Generated data dictionary, failing CI on a missing comment or stale file (200 relations, ~3,300 columns, each tagged owner, retention, PII); forward-only migrations with checksum and lint gates; 210 database tests on real PostgreSQL.
- **Safe outside reading.** BI and web roles see only `dw.v_*` views, with no coordinates and masked phones (V0015, V0020, `DbRolesTest`).
- **Sound pipeline design.** Events commit with their record; the projector rebuilds whole route-days from source rows, so replays cannot double a figure; freshness shows the oldest pending work. Frozen capture context and route, placement and price history already exist (V0042, V0043).

## 3. Findings that hold

### Blocker
None.

### High
1. **`dw` rewrites old days into today's geography.** A rebuilt old day (late upload, void, geo re-check) is stamped with the route's current zone, and views join the current hierarchy, although each memo and visit stores its zone at sale time. After a route moves, last month's zone trend changes and `v_memo_flags` disagrees with `v_daily_route`. History keys and `dim_user` are never filled. Evidence: `Aggregator.kt:69,103,119,149`; V0042:66-69; V0043:150-151; request `docs/requests/db-backend-reports-scd2-keys.md` open since 2026-10-07. Fix: frozen zone, `*_key_on(id, date)` keys, fill `dim_user`, as-of view columns, a move-a-route test, one rebuild. Effort: days.
2. **No rebuild, reconcile or restatement record.** `requestRebuild` is called only from tests; the designed nightly reconcile, restatement log and month snapshots (docs/16:1725-1729) are not built. Every pipeline miss below is permanent, a late row silently changes a closed day, and the final-account move has no range rebuild. Evidence: `AggregationWorker.kt:182-195`; no `agg_reconcile` or `agg_restatement_log` migration. Fix: audited `rebuild(from, to, scope)`, nightly 7-day reconcile, restatement rows, month snapshots. Effort: hours (endpoint) to days.
3. **Test data is not marked.** Only `app_user.pilot` exists, set on seed test accounts and real pilot users alike; the move runbook copies the whole test database into production, checked only by row counts. Seed, test-geography and pilot test sales would become production history. Evidence: V0002:184; no origin column; `docs/setup/move-to-final-account.md:62-72`. Fix: `data_origin` (production, pilot, test, seed) on outlet, route and user, stamped onto capture rows and carried into `dw`; tag tonight's three owner outlets (D-NIGHT-SEED-OUTLETS-01) `test`; the runbook drops test and seed rows and reconciles by origin. Effort: days.
4. **The approved reading surface is dashboard-shaped.** BI may read only `dw.v_*`, finest grain route x SKU x day: no memo, line, visit, dues, stock or outlet x SKU view, no product, geography or user dimension view, and the designed `fact_memo_line` was never built. Forecasting or basket analysis would have to break the docs/31 rule. Not lossy: `app.memo_line` keeps everything. Evidence: V0020:60-63; V0099:16-26; docs/16:1569. Fix: `fact_memo_line` and stable `v_memo`, `v_memo_line`, `v_visit`, `v_due_ledger`, `v_stock_movement`, `v_outlet_sku_daily`, `v_dim_*` (no names). Effort: days.

### Medium
5. **Events are a projector trigger, not a change feed.** 7 of 11 catalogued types have no producer (docs/data-events.md calls all current); memo edits emit nothing, so summing `memo.created` double-counts edits; no lines; no type enforces its keys. Evidence: `DomainEventProducer.kt:16,21-23`; `DuesLedger.kt:50`. Fix: rule that events are thin notices and the lake reads views (amend docs/31 s3.5); mark unbuilt types `planned`; enforce keys on the 4 live types; add `memo.superseded`. Effort: days.
6. **A void or edit on a later day leaves the sale's day overstated**: only the void's date is rebuilt. Latent except around midnight. Evidence: `DomainEventProducer.kt:24-25`; `AggregationWorker.kt:84-87`. Fix: use the memo's date, dirty both days. Effort: hours.
7. **Check-ins, geo fixes and web entry reach `dw` by polling or not at all.** The scan's position lives in memory and compares batch-start time, so an outage over 10 minutes or a slow commit drops rows; web entry is not in `dw`. Evidence: `AggregationWorker.kt:159-170`; `DashboardSummaries.kt:219-246`. Fix: database triggers that dirty the route-day in the writing transaction. Effort: days.
8. **Failures and lag are invisible.** A failing rebuild retries every 5 s forever; dead-letter columns are unused; phone-to-tile time is unmeasured (owner item 1: 5 s poll, rebuild, 30 s cache). Evidence: V0033; `AggregationWorker.kt:122-145`; `DashboardApi.kt:110`. Fix: back-off, dead state, re-drive, lag gauges, a device-lab timing. Effort: days.
9. **Some master data is overwritten in place**: geography parents, SKU attributes, outlet class (V0002:314-321, V0003:50-74, V0104:15-17); `dw` history is dated by projector runs and cannot be rebuilt. Fix: app-side history tables like `route_zone_history`, seeded from the audit log. Effort: days.
10. **Partitioning and retention unfinished**: nine capture tables unpartitioned and unnamed, no registry pruning or archive export, events kept forever (D-DB-PART-01, part-c:797, V0051:31-37). Fix: name them, partition while the final database is empty, build the archive job. Effort: days.
11. **Blob retention**: the `exports` container is not in Bicep (async exports likely fail), has no delete rule, and photos never delete despite the 730-day design (`ExportWiring.kt:6`, `storage.bicep:70-120`). Effort: hours.
12. **No read identity for BI or a lake job**: no `bi_reader` login, no brand or wing row scope (D-374), per-app logins off in dev-lite, stage and prod templates (`runtime-logins.sql:41-43`, `prod.apps.bicepparam:25`). Effort: days.

### Low
13. View contract is a convention: V0109 dropped a column in place; add a golden-columns test and a contract version. Hours.
14. Two void flags: give every view one `is_effective` flag. Hours.
15. The memo number contains the SR login yet is tagged "pii: none" (V0111:12-14); export the memo UUID instead. Hours.
16. Prices can be edited in place by the API role (V0003:91-106); add a guard trigger. Hours.
17. The projector ignores the commit-order horizon (V0033); loss needs a transaction held over 60 s. Hours.
18. The worker ignores the admin suspicious-visit threshold, so dashboard and reports disagree once it changes (`Main.kt:62`). One line.
19. One pack level and fixed category lists (V0003:54-63); carton or case for Phase 2 needs a units table. Days, additive.
20. docs/16 offers Fabric mirroring, which cannot work with partitioned tables, views and weekly column adds; remove it. Hours.

## 4. Data-lake path

- **Test account now (pilot size, docs/28):** after items 1 and 3, one Standard LRS storage account with hierarchical namespace, Hot tier, no zone redundancy, container `lake`. A nightly job, signed in as a `lake_export` login that reads only `dw.v_*`, writes Parquet to `lake/v1/<view>/business_date=YYYY-MM-DD/` with a manifest (row counts, SHA-256, migration and view-contract versions). It re-exports the dates the projector rebuilt, not a fixed window. A test matches Parquet totals to `v_daily_route`; one drill restores a day.
- **Final account later:** the same files plus archived partitions past the 13-month hot window (first around 2027-11); a query engine (Fabric shortcut or Synapse serverless) over them; log-based CDC only if a consumer needs minute-level feeds.
- **Cost:** pilot storage of a few MB a day is well under US$1 a month inside the existing worker; in the final account the query engine is the real cost, sized with the sponsor then.

## 5. Refuted or corrected (do not re-raise)

- "No date dimension view": `v_calendar`, `v_calendar_month`, `v_working_day` exist (V0097); `v_outlet_masked` is an outlet view.
- "Every fact table is partitioned": `fact_attendance` is not.
- "The lake design depends on mirroring": it was a hedged alternative; nothing relies on it.
- "Exports are kept forever": they likely fail first (missing container).
- "Edits need the family ID to count once": `status = 'active'` already excludes edited originals.
- Over-privileged logins apply to the dev-lite, stage and prod templates, not the current test account.
- Projector starvation needs 200+ failing keys of one kind; unlikely.

## 6. Lead action list

1. **db (Opus), "data origin":** column, trigger, `dw` carry-through, tag seed rows and the three owner outlets `test`; infra updates the move runbook. Before new pilot sales.
2. **backend-reports #6 (Sonnet, Opus checker), "as-was attribution":** answer `db-backend-reports-scd2-keys.md` (frozen zone, keys, `dim_user`, as-of brand tree, move test, one pilot rebuild). Slice 2: `rebuild(from, to, scope)`, nightly reconcile, both-dates dirtying, back-off, lag gauges.
3. **db (Opus), "reuse surface":** `fact_memo_line`, stable memo, line, visit, ledger, stock and outlet x SKU views, `v_dim_*`, view-contract test, `is_effective`, memo-number class, reconcile and restatement tables.
4. **lead, rulings:** D-DATA-OUTBOX-ROLE-01 (thin notices; amend docs/31 s3.5); D-DATA-LAKE-01 (this path; supersedes the mirroring part of D-560); correct docs/data-events.md and docs/33 C3.4. Then backend-admin and backend-core mark types `planned`, enforce keys, add `memo.superseded`.
5. **infra (Opus), "lake and blobs":** pilot lake account, `exports` container with delete rule, photo delete rule, `bi_reader` and `lake_export` logins. Pilot size only.
6. **backend-core, after 1 to 3:** nightly Parquet export and reconciliation test; db names the nine tables for the pre-staging partition batch.
