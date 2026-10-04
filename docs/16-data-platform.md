# 16 — Data Platform: Schema v2, Facts, Dimensions, Aggregation

| What this doc decides |
| --- |
| 1. Money and quantities in sticks, pieces and dozens: money is bigint milli-taka (D-15), quantities are qty_entered + unit_entered + pack_factor + qty_base with cigarette and bidi in sticks (D-16, D-17), and a memo's net has three deductions, a stored rounding adjustment and a line-rounding rule (D-18, D-19, D-350) (s1, s3, s5). |
| 2. Idempotency: three layers on a global hash-partitioned ingest registry with tombstones and a content fingerprint (D-21, D-22, D-352, D-353), one stated key for every device-originated table, and rows that cannot be rewritten after sync (s6). |
| 3. Schema v2 and the migration map: four schemas (D-24), forward-only migrations in reserved number blocks with the sub-milestone each lands in (D-376), monthly partitions (D-23, D-361), seven database roles (D-368) and 26 DQ rules added to the first 43 (s2 to s7, s13). |
| 4. The dw layer: SCD2 dimensions, event, daily and month grains, a dirty-key recompute worker with coalescing and dead-letter handling, as-reported snapshots and a restatement log, source and fidelity on every row (D-61, D-140, D-356 to D-358) (s8). |
| 5. KPI and report data: K-01 to K-18 as SQL on dw (D-44 to D-58), the ReportQuery contract and a dw-only query for every report (D-365), the importer with path B for aggregate-only months (D-367), retention, PII and BI access (s9, s12, s13); the 25 analyst questions validate it (s15). |

Date: 2026-10-04. Status: planning, binding on the build once the doc 14 author merges it; changed only through DECISIONS.md. Owner: data platform lead (doc 16 author). Inputs: the plan skeleton (binding), the data lens and the analyst critic (primary), the manual register s1, s2.2, s2.7 and s2.8, the six verification files, docs/22, docs/ui-reference.

How to read: s1 to s6 define the tables that capture and keep data; s7 the rules at the door; s8 and s9 what is derived and how every number is computed; s10 and s11 programmes and the calendar; s12 migration; s13 retention, PII and BI; s14 closes the 65 gaps this document owns; s15 proves the design against the 25 questions an analyst will ask in year one. Every DDL block in this document was loaded, in the order of s2.3, on a fresh PostgreSQL 16.14 server with zero errors (PostGIS lines are commented out because the check server had no PostGIS; D-355). The DDL is a planning draft: the build authors the migration files from it and may rename a constraint but not change a rule.

Markers used: PARITY, IMPROVEMENT, DELIBERATE CHANGE, ASSUMPTION and "unknown; confirm with the business" as in the plan skeleton. "Lands" is a sub-milestone code (0a to 7e). Capture class: OFFLINE (captured on the phone, queued), QUEUED (captured on the phone or web and sent as an event), ONLINE-ONLY (web or admin write), SERVER (derived on the server).

## 1 Principles and conventions

### 1.1 The fourteen principles as review checkboxes

A migration or a pull request that touches data is reviewed against this table. Each row is a yes or no question; "Proved by" names the gate.

| ID | Check | What it prevents | Proved by |
| --- | --- | --- | --- |
| P1 | Every device-originated row has `client_uuid uuid NOT NULL` (v4, generated on the device) registered in `app.ingest_registry`; every child row also carries its parent's client_uuid (D-21) | a retried or split upload creating a second sale (G-data-01) | T-0-06, T-1-01 |
| P2 | A synced row changes only in the named enrichment columns of s6.6; nothing is deleted; every correction is a new row or an event (supersede, void, adjustment, tombstone) (D-22, D-98) | silent history rewrite, edit abuse, non-deterministic re-aggregation | T-0-07 |
| P3 | Capture context is stored on the row: route_id, route_assignment_id, zone_id, cluster_id, price_list_date, price_type, config_version, radius_m_used, pack_factor, bundle_version (D-87) | history changing when an outlet moves route, an SR is reassigned or a price changes (G-data-02) | T-1-03, T-3-100 |
| P4 | Device verdict and server verdict are separate columns; disagreement is stored (`geo_mismatch`); a mocked fix is never geo-valid, enforced by a CHECK (D-48) | a single flag hiding spoofing (G-data-09) | T-2-03 |
| P5 | Money is `bigint` milli-taka, suffix `_mtk`, `MONEY_SCALE = 1000`; line values are rounded once at mtk, memo totals once to the paisa, the difference stored as `round_adj_mtk` (D-15, D-19, D-350) | 7.935 Tk losing a digit; printed totals that cannot be reproduced (G-data-03, G-sync-04) | T-0-08, T-1-06 |
| P6 | Anything whose meaning changes over time is effective-dated with an exclusion constraint against overlap: price, sales plan, route and its pattern, assignment, supervisor scope, outlet placement, class and location, target, config, programme enrolment | "MTD last month" computed with this month's structure | T-3-100, T-4-100 |
| P7 | `business_date` is the Asia/Dhaka date of the trusted capture time (D-20); the server recomputes it (`business_date_server`) and flags a mismatch; `dw.dim_date` is the only calendar | a 23:55 sale landing on the wrong day; skewed phones | T-1-102 |
| P8 | Every quantity-bearing row stores `qty_entered`, `unit_entered`, `pack_factor`, `qty_base` (D-16); aggregates sum `qty_base` only inside one SKU or one category (D-49) | every volume KPI being unitless (G-data-04) | T-2-07 |
| P9 | Nothing is dropped: invalid rows go to `sync_rejected`, changed payloads to `sync_conflict`, voided uuids stay as tombstones (D-65) | a rejected row vanishing so counts cannot reconcile (G-data-06) | T-1-02 |
| P10 | Every device row carries @PROV (s1.6): device, app version, captured time, trusted time and its basis, received time, batch, entry source, config version, flags | impossible "Online/Offline Sales" and sync forensics | T-0-06 |
| P11 | Large tables are monthly RANGE partitions on `business_date` created three months ahead; there are no foreign keys between partitioned event tables (D-23, D-361) | an unpartitioned 44 M-visit table; FK cost at 3 M rows a day | T-0-05 |
| P12 | Masters carry @AUDIT and write `app.audit_log`; masters with KPI effect are future-dated only (D-98) | back-dated price or target changes moving reported numbers | T-6-05 |
| P13 | Schemas app, cfg, dw, stg (D-24); dw is written only by the worker; dashboards, reports, app home KPIs and BI read dw only; operational work-queue pages read app through scoped repositories (D-368) | a new report needing a scan of the transaction log (R1) | T-0-07, T-4-01 |
| P14 | A business list (reasons, types, categories) is a `cfg.code_item` row with a text code, never a PostgreSQL enum; enums remain only for structural states (D-375, G-cfg-10) | a fourth edit reason needing a migration | T-0-06 |

Proved by: T-0-05, T-0-06, T-0-07, T-0-08, T-1-02, T-1-03, T-1-06, T-1-102, T-2-03, T-2-07, T-3-100, T-4-01, T-4-100, T-6-05.

### 1.2 Naming and typing conventions

| Item | Convention |
| --- | --- |
| Schemas | `app` transactions and reference, `cfg` configuration and code lists, `dw` analytics, `stg` migration staging (D-24) |
| Tables | singular snake_case nouns; dw prefixes `dim_`, `fact_`, `agg_` (grain in the name: `agg_daily_route`, `agg_month_zone_product`), `snap_`, `bridge_`, `v_` for views; `stg_`-free names in `stg` |
| Keys | surrogate `bigint GENERATED ALWAYS AS IDENTITY`; device rows add `client_uuid uuid`; natural codes (`outlet_code`, `zone.code`, `sku.code`) are `text` unique columns, never keys |
| Money | `bigint`, suffix `_mtk`; never `numeric`, never float (D-15) |
| Quantity | `qty_entered int`, `unit_entered app.qty_unit`, `pack_factor int`, `qty_base int` (generated); targets `numeric(16,3)` because fractional targets exist (88,235.29 on the AMO report) |
| Time | `timestamptz` UTC, suffix `_at`; `business_date date` Dhaka; trusted-time columns s1.5; `_s`, `_min`, `_ms` for durations |
| Percent | suffix `_pct`, numeric on a 0 to 100 scale, NULL (a dash on screen) for a zero, negative or missing denominator; `dw.pct()` and `dw.safe_div()` (D-28, D-50) |
| Booleans | `is_` or `has_` prefix, or a past participle (`logged_in`, `photo_validated`) |
| Codes | `*_code text` referencing `cfg.code_item(list_key, code)` by convention; integrity by DQ-28 at ingest and by retiring codes (valid_to) instead of deleting them |
| Enums kept | `app.role`, `app.price_type`, `app.request_type`, `app.request_status`, `app.day_state`, `app.qty_unit`, `app.sync_state`, `app.visit_kind`, `app.entry_source`: states and base units that drive code paths |
| Text identity | outlet and route names are labels; nothing is keyed or joined on a name or on a visit-day label (D-242); `outlet_code` is text because "DHK-344-011" and "2689479" both occur (D-237, D-251) |
| Vendor strings | never stored in master data or labels: "Apsis", "Firefly Outlets Reports", the example password (D-244) |

### 1.3 Money, rounding and the printed total

Money is `bigint` milli-taka: 1 Tk = 1,000 mtk, 1 paisa = 10 mtk. The seed catalogue needs it: 23 price values on 20 of the 42 SKUs carry a third decimal (seed-findings counts "23 of 42 prices"; the count is values, not SKUs, verified by script), for example the distributor price 7.935 Tk per stick, which is 7,935 mtk. A paisa column cannot hold it. All five price types are per base unit; prices of 0.0 (all 42 `price_nto` values) mean "no price" and are not loaded as rows.

Rounding is decided in three places and never anywhere else (D-350, resolves G-sync-04 together with D-19):

1. Line value `gross_mtk = div_half_up(qty_base * base_price_mtk, price_per_qty)`: integer arithmetic; `price_per_qty` is 1 for every seed price and is the escape hatch for a list that quotes a price per N base units. The line value is rounded once, to mtk, half away from zero. Nothing coarser than mtk is ever stored on a line.
2. Memo net = gross - offer_discount - drp_discount - qc_deduction, summed from the mtk lines, then rounded once to a whole paisa (`app.round_to_paisa`, mode `half_up_paisa`, future-dated only); `round_adj_mtk` stores the difference (|adj| at most 5 mtk) so the printed total is reproducible from the row.
3. Display: Bangla or Latin digits, two decimals, the paisa-rounded total; per-line display rounding is never stored and never summed.

| Fixture (source) | Arithmetic | Result |
| --- | --- | --- |
| AMO p28 / p43 memo (V-money) | lines 80,000 + 20,000 + 12,500 + 2,333 (FB 1 piece at 28.00 per dozen) + 1,583 (SL 1 piece at 19.00 per dozen) = 116,416 mtk; round to paisa +4 | 116,420 mtk = 116.42; summing paisa-rounded display lines gives 116.41, which is the wrong rule |
| SR p34 memo with slide (V-money) | gross 360,500 - drp 80,000 = 280,500; quantity total 65 unchanged | net 280.50 |
| SR p35 credit (V-money) | 161,500 total, 100,000 collected | due 61.50 (the label that printed "61" is a truncation bug, D-213) |
| AMO p29 credit | 148,500 - 100,000 | due 48.50 |
| Home card (UI-SR-04/05) | 141.00 + 4,687.50 = 4,828.50 gross; - 437.50 offer - 0.00 DRP - 0.00 QC | net 4,391.00 |
| Seed distributor price | 20 sticks at 7,935 mtk | 158,700 mtk = 158.70 Tk, exact |

ASSUMPTION (why): mtk resolution per line differs from an exact rational sum by at most 0.5 mtk a line (0.0005 Tk), which can flip a paisa tie only when the exact sum lies within n x 0.5 mtk of a half-paisa. The four golden fixtures of this section pass. D-19 is MUST-CONFIRM (by 2a): the memo must equal Apsis to the paisa on the baseline memo corpus (T-0-49). If the corpus shows one divergence, line precision moves to micro-milli-taka (`_umtk`) by migration before 2a exits: cheap before native data exists, expensive after (gap G-16-02).

Money columns by table (all `_mtk`): `sku_price.amount_mtk`; `memo.gross`, `offer_discount`, `drp_discount`, `qc_deduction`, `round_adj`, `net`, `paid`, `due`, `outstanding_before`, `printed_total_due`; `memo_line.base_price`, `gross`, `discount`, `net`, `list_price`; `memo_offer.value`; `qc_entry.settlement`, `max_qc`; `drp_collection.reward_value`; `due_collection.amount`; `due_adjustment.amount`; `redemption.cash`, `redemption_line.cash`; `gift_catalog.cash_rate_mtk_per_point`; `cash_handover.declared`, `counted`, `variance`; `price_compliance_check.observed_price`, `listed_price`; and every dw column of the same names.

### 1.4 Quantity and unit handling

| Category | Base unit (`qty_base`) | Entry unit default | Price basis | Report unit by surface | Pack badge | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Cigarette | stick | stick | per stick (MaxR 8.00 on the manual, seed outlet 9.2) | stick everywhere | `qty_base / base_per_pack`, read-only (6,500 sticks shows 650) | LOCKED-BY-SPEC (docs/22 P-04, SR p21); stepper step and loose sticks MUST-CONFIRM (by 2a, MQ-02) |
| Bidi | stick | stick | per stick (0.515 to 0.88 on the seed) | stick | as cigarette | LOCKED-BY-SPEC; the manual's bidi 6,500 versus 7,300 is a display glitch (D-228) |
| Lighter | piece | piece | per piece (Aster 12.50) | Pcs on the web, Box on the TSO app: `report_unit = box`, `report_factor = 1 / box size` | none | MUST-CONFIRM (by 2a): box size (D-16) |
| Match | dozen | dozen (whole dozens) | per dozen (FB 28.00, SL 19.00); never 2.33 per piece | dozen | none | MUST-CONFIRM (by 2a): pieces or dozens are typed (MQ-01); SR p34 shows 12 and p35 shows 1 for the same SKU |

If the business answers "pieces" for Match, the base unit becomes piece, `report_factor` 1/12 and the price a `price_per_qty = 12` row: a data change that must happen before the first native Match memo exists (2a entry condition; gap G-16-15). The seed marks Match as `pack_type = Dozen`, `pack_size = 1` and Lighter as `pack_type = Box`, `pack_size = 1`, which agrees.

| Worked example | Stored on `memo_line` | Shown |
| --- | --- | --- |
| MaxR-10S, 20 sticks typed | qty_entered 20, unit stick, pack_factor 1, qty_base 20 | pack badge 2 |
| MaxR-20S in pack-entry mode (`cfg.sale.qty_entry_unit = pack`, off by default) | qty_entered 3, unit pack, pack_factor 20, qty_base 60 | 60 sticks |
| Aster, 1 piece | qty_entered 1, unit piece, pack_factor 1, qty_base 1, price 12,500 | 12.50; TSO app shows boxes via report_factor |
| FB, 2 dozen | qty_entered 2, unit dozen, pack_factor 1, qty_base 2 | 2 dozen, 56.00 |

Rules: STD (K-07) sums `qty_base` only inside one SKU or one category; a cross-category total is not a number and is replaced by `net_mtk` (D-49). The legacy Sales Submit rows add unlike quantities (Sale 381 is 375 lighters plus 6 dozen, Stock 800 is 400 plus 400, UI-SR-36); the new reconciliation compares record counts per entity and money per category, and the legacy five rows may be shown for parity but never gate a submit (doc 17). A slide reward line (`line_kind = drp_reward`) is valued at list price, deducted in `drp_discount_mtk`, and counted in `qty_base`, so volume and stock include it (D-18, confirm); `free_qty_base` is reported beside it so free goods are visible.

### 1.5 Business date and trusted time

| Column | Meaning | Rule |
| --- | --- | --- |
| `captured_at` | device wall clock at capture | stored verbatim, never used for a verdict |
| `captured_elapsed_ms`, `boot_id` | monotonic elapsed time and boot identity | anchor inputs (D-20) |
| `captured_at_trusted`, `time_basis` | server-computed trusted capture time | `anchor` (server time at last contact plus elapsed, boot_id unchanged), else `offset` (clock offset at last contact), else `device`; `time_untrusted` is set when the basis is `device` after a clock change |
| `business_date` | Asia/Dhaka date of the trusted time, computed on the device | cutoff 00:00 (MUST-CONFIRM by 1a, Q29) |
| `business_date_server` | generated from `captured_at_trusted` | a mismatch raises `business_date_mismatch` (DQ-10); it is a real disagreement, no longer a skew artefact (G-analyst-14) |
| `received_at`, `sync_batch_id` | server clock and the batch | sync latency = received_at - captured_at_trusted |

`app.dhaka_date(ts)` is declared IMMUTABLE so it can sit in generated columns and indexes. ASSUMPTION (why): Bangladesh has had a fixed +06:00 offset since the 2009 daylight-saving trial ended, so the wrapper is safe; a CI test fails if the tzdata build changes the Asia/Dhaka offset (D-351, gap G-16-01). A row whose business date is outside `[today - cfg.sync.max_backdate_days, today + 1]` is rejected (DQ-09); a row claiming a month closed more than `cfg.day.month_close_grace_days` ago on untrusted time is parked (DQ-40). `hour_of_day` and every `*_local` column use the trusted time in Asia/Dhaka. SCD2 validity is day-granular: an intra-day change takes effect for the whole business date it is made on, and the capture-time columns on the transaction row carry the intra-day truth (P3).

### 1.6 Standard column blocks

@PROV is on every device-originated table. `sig bytea` (the ES256 record signature, D-104) is stored on header record types only; children are covered by the family signature verified at ingest, and `sig_status` on every row records the outcome. ASSUMPTION (why): signing every row would add about 190 MB a day at 3.0 M rows against about 650 MB a day of growth; doc 21 confirms (D-354).

```sql
-- @PROV (the DDL files write /*@PROV*/ and /*@SIG*/ where this block belongs)
client_uuid uuid NOT NULL, device_id bigint, app_version text,
captured_at timestamptz NOT NULL, captured_elapsed_ms bigint, boot_id uuid,
captured_at_trusted timestamptz NOT NULL, time_basis text NOT NULL DEFAULT 'device' CHECK (time_basis IN ('anchor','offset','device')),
time_untrusted boolean NOT NULL DEFAULT false,
received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, captured_offline boolean,
entry_source app.entry_source NOT NULL DEFAULT 'app', config_version int,
business_date date NOT NULL, business_date_server date GENERATED ALWAYS AS (app.dhaka_date(captured_at_trusted)) STORED,
sig_status smallint NOT NULL DEFAULT 0, flags text[] NOT NULL DEFAULT '{}'      -- header types add: sig bytea
```

@AUDIT on every master: `created_at`, `created_by`, `updated_at`, `updated_by`, `version`, `status`; every change also writes `app.audit_log`. @SCD on every effective-dated table: `valid_from date NOT NULL`, `valid_to date`, and `EXCLUDE USING gist (<key> WITH =, daterange(valid_from, valid_to, '[)') WITH &&)`.

### 1.7 Partitions, keys and foreign keys

- Partitioned parents have `PRIMARY KEY (id, business_date)` and `UNIQUE (client_uuid, business_date)`; global uniqueness of `client_uuid` is the registry's job (s6.1), because a unique index on a partitioned table must include the partition key and a retry may compute a different business date after a clock correction.
- No foreign key joins two partitioned event tables (D-361): at 3.0 M rows a day each FK adds an index probe and a partition lock. Children carry `*_client_uuid` plus the server id; ingest resolves the parent in the same transaction, parks the child if the parent is absent (DQ-03), and a nightly `app.check_orphans()` job reports any child without a parent (T-1-02). Foreign keys to non-partitioned masters (user, outlet, route, sku) stay.
- `app.partition_policy` lists every partitioned parent with its retention class; `app.ensure_partitions()` creates the current month and three ahead plus a `_default` partition per parent; a row landing in a default partition raises an alert (D-131).

Proved by: T-0-05, T-0-06, T-0-08, T-1-02, T-1-06, T-1-102.

## 2 Schema overview and migration map

### 2.1 Schemas and database roles

| Schema | Holds | Written by | Read by |
| --- | --- | --- | --- |
| `app` | reference and master data, field transactions, sync, identity and audit tables (about 140 tables, 23 of them partitioned) | `api_rw` (ingest, admin), `worker_rw` (enrichment columns of s6.6 only) | API through scoped repositories; `pii_reader` (PII columns); `support_ro` (device tables) |
| `cfg` | config registry and values, code lists, QC fault types, calendar | `api_rw` (admin console) | everyone through views and the config service |
| `dw` | dimensions, facts, aggregates, snapshots, the dirty queue (43 tables, 14 of them partitioned, listed in s8) | `worker_rw` only | `web_ro`, `bi_reader` (replica), the API read path |
| `stg` | importer staging, crosswalk, quarantine, control totals | the importer job | engineers and the finance reviewer during 7a |

Roles (D-368, D-566): ONE role map, s13.4b, owned by this document and cited (never redefined) by docs 18, 20 and 21. In short: privilege roles `api_rw`, `auth_rw`, `worker_rw`, `jobs_rw`, `web_ro`, `export_ro`, `bi_reader`, `pii_reader`, `support_ro` and the owners `app_owner`, `dw_owner`, `migrator`; the pooled LOGIN identities of doc 18 (`app_api`, `app_auth`, `app_worker`, `app_jobs`, `app_web`, `app_export`, `bi_reader`, `app_migrator`) are members of them. Grants are GENERATED from the map and proved by a positive and a negative test (T-0-158). Row-level security shells ship in M-39; the policies and the `SET LOCAL app.user_id` protocol belong to doc 21 (D-106).

### 2.2 Migration rules and numbering

- Forward-only SQL files `/db/migrations/NNNN_<snake_name>.sql`, checksummed, never edited after shipping, expand then contract over three releases (D-14). The migration id `M-nn` is the file number (M-07 is `0007_*`).
- `0000_baseline` is `db/schema.sql` loaded unchanged into `public` (47 tables). M-01 moves every object into `app` and renames the money columns. The baseline tables that v2 replaces are dropped in the migration that creates their partitioned successor; that is legal only because no production data exists at 0b. From 1a on every change is expand/contract.
- M-01 to M-45 are authored in 0b. A later migration keeps its reserved number inside its block (the blocks of s2.3) and carries a Lands sub-milestone; the runner applies pending files in ascending number even when a lower number arrives after a higher one was applied (dbmate behaviour) and CI rejects a reused number or a number outside the block of its range (D-376, open item OI-16-01 for doc 20 to confirm the runner).
- Every migration states the table's capture class, its partition or retention class, and appears in the rtm catalogue (doc 20 s8).

### 2.3 Migration map

| M-id | Content (tables created or altered) | Lands | Capture class | Gaps closed |
| --- | --- | --- | --- | --- |
| M-01 | schemas, extensions (btree_gist, pgcrypto, pg_trgm, citext; PostGIS allow-listed), functions `app.dhaka_date`, `dw.safe_div`, `app.normalise_digits`, `app.div_half_up`, `app.round_to_paisa`, enums, baseline move, money rename (s2.5) | 0b | - | G-data-03, G-data-24, G-field-10 |
| M-02 to M-04 | `sku` base unit and status, `sku_price` effective dating, `sales_plan` effective dating (s3.2, s3.3) | 0b | ONLINE-ONLY | G-data-04, G-data-03, G-man-038 |
| M-05 to M-12 | `visit`, `memo`, `memo_line`, `memo_offer`, `qc_entry`, `survey` and `survey_response`, `drp_collection` and lines, `stock_movement`, `attendance_event`, `due_collection`, `due_adjustment` (s5) | 0b | OFFLINE | G-data-01, G-data-02, G-data-09, G-data-13, G-man-002 |
| M-13 | assignment resolution rule: ingest function `app.resolve_assignment(route, user, date)`; no table | 1b | SERVER | G-data-02 |
| M-14 | `route_assignment` v2: kind primary, cover or ss, no overlap, one primary per route-day (s3.4) | 0b | ONLINE-ONLY | G-man-100 |
| M-15 | `outlet_change_request` v2, `outlet_change_event`, `outlet_photo` v2, `outlet_location_provisional`, `outlet_change_evidence` (s4, D-545) | 0b | QUEUED | G-data-18, G-man-033, G-qa-76 |
| M-16 | `outlet` v2, placement, class and location histories, trigger (s4) | 0b | ONLINE-ONLY | G-man-038, G-field-10 |
| M-17 | `geo_fix`, `device_integrity` (s5.2, s6.4) | 0b | OFFLINE | G-data-09 |
| M-18, M-19 | zone contact fields, route kind, visit pattern, label, `route_history`; audit columns on masters; `app_user` v2 (s3.1, s3.4) | 0b | ONLINE-ONLY | G-man-100 |
| M-20 | `offer`, `offer_product`, `offer_scope`, `offer_version` (s3.7) | 0b | ONLINE-ONLY | G-data-14 |
| M-21 to M-24 | targets, programmes, Diamond League, gift assignment (s10) | 0b | ONLINE-ONLY except redemption (OFFLINE) | G-data-15, G-data-14 |
| M-25 to M-29 | `task` v2 and events, distribution check, assessments, price compliance, `outlet_suggestion` (s5.10) | 0b | QUEUED | G-data-26 |
| M-30 | `ingest_registry` (64 hash partitions), `sync_rejected`, `sync_conflict` (s6.1) | 0b | SERVER | G-data-06, G-sre-26 |
| M-31 | `sync_batch` v2, `sync_batch_key`, `bundle_download`, `bundle_snapshot`, `job_run`, `media_object`, `reconcile_snapshot` (s6.3) | 0b | SERVER | G-data-06 |
| M-32 | `route_day`, `supervisor_day`, `day_exception`, `tracking_action` (s11) | 0b | QUEUED | G-data-10, G-sync-07 |
| M-33 to M-36 | `leave_application`, `final_submit` v2 with route snapshot and attempts, `visit_plan`, `feedback` (s5.10, s5.12) | 0b | QUEUED | G-data-17 |
| M-37 | `app.partition_policy`, `app.ensure_partitions()`, `app.retention_policy`, `archive_manifest`, `archive_candidates()` (s2.4, s13.1) | 0b | SERVER | G-data-24 |
| M-38 | `dw.agg_dirty`, `dw.enqueue`, `dw.claim`, `dw.complete`, `dw.fail`, `agg_run`, `agg_reconcile` (s8.6) | 0b | SERVER | G-scale-02, G-sre-17 |
| M-39 | database roles, grants, immutability triggers, RLS shells (s6.6, s13.4) | 0b | SERVER | G-data-23 |
| M-40, M-41 | credentials, refresh tokens, device v2, OTP, integrity, capability snapshot (s6.4) | 0c | ONLINE-ONLY | G-data-16 |
| M-42 | `app.opening_balance`, `stg.import_run`, `stg.id_crosswalk` (minimal; s12) | 0b | SERVER | G-data-19 |
| M-43 | `audit_log`, `report_export_log`, `security_event`, `risk_signal`, `activity_log` (s6.5) | 0b | SERVER | G-data-23, G-scale-07 |
| M-44 | `content_item`, `outlet_content_assignment`, `content_view`, `tutorial_asset`, `app_release`, `support_upload` (s5.10) | 0b | QUEUED | G-data-27 |
| M-45 | `cfg.config_item`, `config_value`, `config_change_audit`, `config_ack`, `wave`, `wave_member`, `dq_rule`, code lists, `qc_fault_type`, `territory_geo_config` view (s3.6, s3.9) | 0b | ONLINE-ONLY | G-data-20, G-cfg-10 |
| M-46 | `user_scope` effective-dated (s3.4) | 3a | ONLINE-ONLY | G-analyst-04 |
| M-47 | `opening_balance.age_basis_date` (in M-42's DDL), dues ageing inputs | 2b | SERVER | G-feat-45 |
| M-48, M-49, M-51 | memo `offer_version_set`, `rounding_mode_used`; visit `outcome_code`; printed-due snapshot (all in the M-05 and M-06 DDL) | 2a | OFFLINE | G-analyst-15, G-analyst-11 |
| M-50 | `outlet_merge` (s4) | 3a | ONLINE-ONLY | G-analyst-12 |
| M-52, M-53 | `day_exception` routes and `cfg.route_day_override`; `visit_outcome` list and `visit_skip` (s5.8, s11) | 2a to 2e | QUEUED | G-field-02, G-field-03 |
| M-54 | `memo_void`, `print_event`, `due_dispute`, `attribution_event` (s5.8); `app.submit_void_event` (s11.4, D-539) and `app.paper_backfill` (s5.12, D-543), both ONLINE-ONLY | 2b | QUEUED | G-feat-45, G-qa-70, G-qa-74 |
| M-55 | `supervisor_day` (in M-32's DDL) | 3a | QUEUED | G-man-032 |
| M-56, M-57 | `risk_signal` shell columns (doc 21), `app.rollout_wave` and `app.rollout_wave_member` (in M-45) | 2d | SERVER | doc 21 |
| M-58 | `app.device_day`, `app.user_last_fix` (in M-31's DDL) | 2e | QUEUED | G-analyst-16 |
| M-59 | typed promotion rule tables (`offer_tier` and one table per rule kind found in the catalogue) and the importer fill of `app.offer.rule`, authored at 2a entry when the catalogue arrives (s3.7, D-501) | 2a | SERVER | G-qa-26 |
| M-60 | `dw.dim_date`, `dw.build_dim_date()` (s8.2) | 0b | SERVER | G-feat-09 |
| M-61 | dimensions `dim_geo`, `dim_outlet`, `dim_outlet_pii`, `dim_user`, `dim_supervisor_assignment`, `dim_product` | 1c | SERVER | G-data-07 |
| M-62 | event facts `fact_visit`, `fact_memo_line`, `fact_due_ledger` and the rest of s8.4 | 1c | SERVER | G-data-07 |
| M-63 | daily aggregates route, route_sku, zone, outlet, outlet_brand, zone_category | 1c | SERVER | G-scale-02 |
| M-64 | month aggregates and balances (route, zone, outlet programme, outlet category) | 2a | SERVER | G-data-07 |
| M-65 | programme aggregates and period snapshots | 5a | SERVER | G-analyst-13 |
| M-66 | views: effective source, rollups above zone | 1c | SERVER | G-data-07 |
| M-67 | KPI functions and views (`dw.pct`, `v_kpi_zone_day`, `tilldate_target`, `sku_target_progress`) (s9) | 1c | SERVER | G-data-22 |
| M-68 | `v_outlet_masked`, `bi_user_scope`, bi grants | 4c | SERVER | G-data-23 |
| M-69 | `dw.fact_device_day` | 2e | SERVER | G-analyst-16 |
| M-70 | `dw.fact_memo` | 1c | SERVER | G-analyst-01 |
| M-71 | `dim_target`, `snap_month_zone_product`, `snap_period_outlet_program`, `agg_restatement_log` | 1c | SERVER | G-analyst-02 |
| M-72 | `agg_daily_user`, `agg_daily_user_sku` | 3a | SERVER | G-analyst-03 |
| M-73 | `fact_due_allocation`, `agg_memo_due_open`, `agg_outlet_due_ageing_daily` | 2b | SERVER | G-feat-45 |
| M-74 | `dim_sku_price`, `fact_price_change`, price columns on `fact_memo_line` | 2a | SERVER | G-analyst-06, G-feat-52 |
| M-75 | `fact_dq_flag` | 2a | SERVER | G-analyst-07 |
| M-76 | `fact_memo_offer`, `bridge_offer_scope`, `bridge_offer_product`, `bridge_outlet_effective` | 2a | SERVER | G-analyst-08, G-analyst-12 |
| M-77 to M-99 | further dw: the capture facts of s8.9 (`fact_qc_line` M-78, `fact_survey_answer` M-79, `fact_geo_fix` M-80, `fact_media` M-81, `bridge_memo_offer_version` M-82, the other 17 in M-83 to M-93, landing 2a to 4a with their capture tables, D-500), `agg_hourly_zone` (4a), `agg_daily_zone_offer` (2a), `agg_outlet_visit_streak` (4a), `agg_month_outlet_category` (4b), `dim_app_version`, `dim_reason`, `dim_gift`, `dim_program_period`, `dim_device` (2a to 5a), `bridge_holiday_scope`, `bridge_route_day_override` (4a), `fact_web_entry_line` (4c), event facts for tasks, assessments, distribution, price compliance, redemption and gifts (3a to 5a), `fact_config_change` (1c) | as stated | SERVER | G-analyst-10, G-analyst-16 |
| M-100 to M-117 | the manual register's S-01 to S-18 blocks, mapped one to one in s2.6 | 0b to 6a | as stated | G-man-001 to G-man-100 |
| M-118 | `app.report_def`, `app.report_alias` (s9.6) | 4b | ONLINE-ONLY | G-man-095 |
| M-119, M-120 | `cash_handover`, stock confirmation columns; `due_dispute` (in M-54's DDL) | 2b | QUEUED | G-field-01 |
| M-121 | `dw.agg_outlet_density`, placeholder-pin detection job tables | 2d | SERVER | G-cfg-23 |
| M-122 | text helpers: `name_sort_key` generation, phone normalisation, `phone_hash` function | 1a | SERVER | G-field-10 |
| M-123 | `dw.fact_device_integrity` (s8.9.5) | 2d | SERVER | G-qa-91 |
| M-124 | `dw.fact_activity`, `dw.agg_daily_screen_use` (s8.9.5) | 2e | SERVER | G-qa-91 |
| M-125 | `dw.fact_consent` (s8.9.5) | 2e | SERVER | G-qa-91 |
| M-126 | `app.device_config_seen` (doc 19 s2.3b) | 2d | SERVER | G-qa-107 |
| M-127 | `app.enqueue_route_day()`, the route-day and web-entry triggers, `app.settle_due_route_days()` (s8.6b) | 1c | SERVER | G-qa-102 |
| M-128 | `app.retention_hold` (s13.1b) | 6c (table in 1b so the jobs can read it) | SERVER | G-qa-139 |
| M-129 | `app.route_day_void_barrier`, `app.route_day_voided()` (s6.1) | 4c (the table in 1b) | SERVER | G-qa-113 |
| M-130 to M-136 | `stg.import_run`, `id_crosswalk`, `raw_sales`, `raw_retailer`, `sales_norm`, `quarantine`, `control_total`, rollback function (s12) | 7a (tables in 0b) | SERVER | G-data-19, G-data-34, G-data-35, G-data-36 |
| M-137 | `app.role_grant_map`, `app.apply_role_grants()`, `app.apply_policy_grants()`, the owner roles and the SECURITY DEFINER ownership of s8.6 (s13.4b) | 0c | SERVER | G-qa-100, G-qa-101 |
| M-138 | `app.device_directive` (doc 19 s5.4b, D-594) | 2e | ONLINE-ONLY | G-qa-133 |
| M-139 | `app.support_ticket`, `app.support_contact_log` (doc 19 s5.4b, D-599) | 2e | ONLINE-ONLY | G-qa-138 |
| M-140 to M-149 | free | - | - | - |

Proved by: T-0-01, T-0-02, T-0-03, T-0-04, T-0-05, T-0-06.

### 2.4 What changes in `db/schema.sql` (the 47 baseline tables)

| Baseline table | Disposition | Where |
| --- | --- | --- |
| wing, division, territory, house, zone, cluster | kept in `app`; @AUDIT; zone gains dep_id, dep_name, email, address, pda_contact_no, data_entry_date (no `is_service` flag: service zones are derived, D-536) | s3.1 |
| route | kept; `visit_days` renamed `display_label`; gains kind, visit_kind, visit_days_mask, status; `route_history` added | s3.1 |
| territory_geo_config | dropped; replaced by `cfg.territory_geo_config` view over `cfg.config_value` | s3.9 |
| app_user, user_scope, route_assignment, device | kept and extended (citext username, designation, SCD, kinds, device fields) | s3.4, s6.4 |
| product_category, product_segment, product_brand, product_variant | kept; `status` added | s3.2 |
| sku | kept; `unit` becomes `base_unit app.qty_unit`; base_per_pack, entry_unit_default, report_unit, report_factor, status | s3.2 |
| sku_price | kept; `amount_minor` renamed `amount_mtk`; no-overlap exclusion; `per_base_qty` | s3.3 |
| sales_plan | rebuilt effective-dated with an identity key | s3.3 |
| sub_channel | kept; `channel` becomes text referencing the new `app.channel`; nine seed rows | s3.5 |
| outlet | kept; enums replaced by code tables; PII columns encrypted; location_confirmed, kind, price_type, phone_hash, name_sort_key | s4 |
| outlet_change_request, outlet_photo | kept and extended | s4 |
| attendance | dropped and recreated as the derived one-row-per-user-day; `attendance_event` is the stored fact | s5.7 |
| stock_issue | dropped; `stock_movement` events replace it | s5.7 |
| visit, memo, memo_line, qc_entry, survey_response, drp_collection, due_collection | dropped and recreated partitioned with @PROV | s5 |
| loyalty_ledger, redemption, gift_photo, task, call_assessment, target, target_revision, final_submit | kept and extended | s10, s5.10, s5.12 |
| distribution_check | dropped; header plus lines | s5.10 |
| sync_batch | dropped and recreated partitioned by upload date | s6.3 |
| route_log | dropped; the Data Entry Log is a report on `dw.agg_daily_route` and `dw.fact_bundle_download` | s9.6 |
| activity_log | dropped and recreated partitioned | s6.5 |
| fact_daily_route_sku, fact_daily_outlet, fact_daily_route | dropped from `public`; replaced by `dw.agg_daily_route_sku`, `agg_daily_outlet`, `agg_daily_route` with source and fidelity | s8.4 |

### 2.5 M-01 in full

```sql
-- M-01 foundations: schemas, extensions, functions, enums, baseline move, money rename
CREATE SCHEMA app;  CREATE SCHEMA cfg;  CREATE SCHEMA dw;  CREATE SCHEMA stg;
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- EXCLUDE constraints on date ranges
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS pg_trgm;      -- duplicate-outlet name similarity (FS-03)
CREATE EXTENSION IF NOT EXISTS citext;       -- case-insensitive usernames (G-man-091)
-- CREATE EXTENSION IF NOT EXISTS postgis;   -- allow-listed on the server (D-355); not loaded in this check

-- 0000_baseline (db/schema.sql) is loaded unchanged into public; M-01 moves every object into app
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
    EXECUTE format('ALTER TABLE public.%I SET SCHEMA app', r.tablename);
  END LOOP;
  FOR r IN SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
           WHERE n.nspname = 'public' AND t.typtype = 'e' LOOP
    EXECUTE format('ALTER TYPE public.%I SET SCHEMA app', r.typname);
  END LOOP;
END $$;

-- money is bigint milli-taka (D-15); the two baseline tables that survive are renamed here
ALTER TABLE app.sku_price   RENAME COLUMN amount_minor TO amount_mtk;
ALTER TABLE app.redemption  RENAME COLUMN cash_minor   TO cash_mtk;

-- structural enums only (P14); business codes become cfg.code_item rows
CREATE TYPE app.qty_unit     AS ENUM ('stick','piece','dozen','pack','box','carton');
CREATE TYPE app.sync_state   AS ENUM ('accepted','rejected','conflict','parked','voided');
CREATE TYPE app.visit_kind   AS ENUM ('sr_call','amo_control_call','amo_joint_call','tso_visit','web_entry');   -- D-26
CREATE TYPE app.entry_source AS ENUM ('app','web','migration','manual','support_replay','revoked_device');   -- manual = supervised paper-memo backfill (D-543); support_replay and revoked_device are used by doc 21 s5.4 and doc 17 s10.4
ALTER TYPE app.role         ADD VALUE IF NOT EXISTS 'wmo';          -- approves target sets (D-31)
ALTER TYPE app.request_type ADD VALUE IF NOT EXISTS 'base_update';
ALTER TYPE app.request_type ADD VALUE IF NOT EXISTS 'location';
ALTER TYPE app.request_type ADD VALUE IF NOT EXISTS 'cluster';      -- UI-SR-40, Q-UI-06

-- Asia/Dhaka has no DST; the wrapper makes the expression usable in generated columns and indexes (D-351)
CREATE FUNCTION app.dhaka_date(ts timestamptz) RETURNS date
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT (ts AT TIME ZONE 'Asia/Dhaka')::date $$;
-- NULL for a zero, negative or missing denominator: a percentage is a dash, never 0 (D-28, D-50)
CREATE FUNCTION dw.safe_div(n numeric, d numeric) RETURNS numeric
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT CASE WHEN d IS NULL OR d <= 0 THEN NULL ELSE n / d END $$;
-- Bengali digits to ASCII at ingest and import (G-field-10, DQ-44)
CREATE FUNCTION app.normalise_digits(t text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT translate(t, '০১২৩৪৫৬৭৮৯', '0123456789') $$;
-- integer division rounded half away from zero; the only rounding primitive (D-350)
CREATE FUNCTION app.div_half_up(n bigint, d bigint) RETURNS bigint
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT CASE WHEN n >= 0 THEN (2*n + d) / (2*d) ELSE -((-2*n + d) / (2*d)) END $$;
CREATE FUNCTION app.round_to_paisa(mtk bigint) RETURNS bigint      -- 1 paisa = 10 mtk
  LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT app.div_half_up(mtk, 10) * 10 $$;
```

### 2.6 The manual register's schema blocks S-01 to S-18

Each S-block of the register (section 2.8) maps to exactly one migration and to the table that absorbed it; none is dropped.

| Block | Migration | Absorbed by or new | Register entry |
| --- | --- | --- | --- |
| S-01 quantity unit | M-100 | M-02, M-07 (qty model); `pack_stick_count` is `sku.base_per_pack` | G-man-001 |
| S-02 memo total, QC money | M-101 | M-06 columns and CHECK, M-08 `qc_entry.settlement_mtk`, `max_qc_mtk` | G-man-002, G-man-014 |
| S-03 QC fault taxonomy | M-102 | `cfg.qc_fault_type` (11 codes), `app.qc_entry_line`, `app.qc_summary_entry` | G-man-003, G-man-089 |
| S-04 offers and DRP | M-103 | M-20 plus typed DRP fields, `drp_collection_line` | G-man-004 |
| S-05 visit start and kind | M-104 | M-05 `call_started_at`, `call_declined`, `kind` | G-man-008, G-man-015 |
| S-06 loyalty expiry | M-105 | `program_period.redeem_until`, `loyalty_ledger.expires_at`, `survey_question.points_reward` | G-man-040, G-man-041 |
| S-07 content assignment, thumbnail | M-106 | `outlet_content_assignment`, `outlet.thumbnail_media_id`, mobile CHECK | G-man-043, G-man-037 |
| S-08 outlet requests | M-107 | M-15 cluster-first, lifecycle, location requests | G-man-017, G-man-033, G-man-034 |
| S-09 wholesale flag | M-108 | `outlet.outlet_kind`, `outlet_class_history` (batch_uuid), bulk operation audit in `audit_log` | G-man-036 |
| S-10 users, designation, OTP | M-109 | `app_user.designation`, role `wmo`, `device_otp` | G-man-021, G-man-055 |
| S-11 supervisor day, route kind, channel | M-110 | `supervisor_day`, `route.kind`, zone fields, `app.channel`, product `status` | G-man-032, G-man-072, G-man-088, G-man-100, G-man-038 |
| S-12 targets | M-111 | `target_set`, `target_approval_event`, variant level | G-man-068, G-man-090 |
| S-13 TSO tables | M-112 | M-33, M-35, M-36 | G-man-074, G-man-080, G-man-083 |
| S-14 final submit | M-113 | M-34 | G-man-070, G-man-071, G-man-086 |
| S-15 web entry, void, unlock | M-114 | `web_entry_*`, `data_void`, `entry_unlock_grant`, registry state `voided` | G-man-085, G-man-046, G-man-086, G-man-087 |
| S-16 tasks, assessments | M-115 | M-25 to M-27 | G-man-049, G-man-050, G-man-051, G-man-052, G-man-082 |
| S-17 reconciliation, attendance, collector | M-116 | `sync_batch.device_counts`, `server_totals`; `due_collection.collector_id` | G-man-031, G-man-029, G-man-011 |
| S-18 derived views | M-117 | `dw.sku_target_progress`, `dw.tilldate_target` | G-man-053, G-man-067 |

Proved by: T-0-01, T-0-06.

## 3 Reference and master data

Master data is ONLINE-ONLY to write (web admin or importer) and CACHED on the phone through the bundle (doc 17). Every table here is a master: @AUDIT columns, `status` instead of deletion, and a row in `app.audit_log` per change (P12).

### 3.1 Geography, routes and the visit pattern

The spine is unchanged (docs/03): wing, division, territory, house, zone, cluster, route; 10 wings, 50 divisions, 291 territories, 1,051 zones, 11,336 routes (docs/22 P-13). Changes: the zone gets the fields the manuals show (D-180, G-man-088), and the route stops being a name with a label glued on (D-242, I-35): `name` is the name, `display_label` is the printed visit-day text, `visit_days_mask` and `visit_kind` are the data, and `kind` separates SR routes from AMO routes (D-29, G-man-100).

```sql
-- M-18 geography: zone contact fields (D-180, G-man-088) and route kind, visit pattern and label (D-29, D-242, docs/22 P-17)
ALTER TABLE app.zone
  ADD COLUMN dep_id text, ADD COLUMN dep_name text,                 -- 'Dep Name' is the zone name in the sample
  ADD COLUMN email text, ADD COLUMN address text, ADD COLUMN pda_contact_no text,
  ADD COLUMN data_entry_date date,                                   -- meaning unconfirmed (MQ-44); may drive the web back-date cut-off
  -- no is_service column (D-536): 'Total Service Zone' is DERIVED as the zones with at least one target (planned sr-kind) route on the date, `agg_daily_zone.target_routes > 0`; a hand-kept flag would make every zone a non-service zone by default
  ADD COLUMN apsis_id text;
ALTER TABLE app.route RENAME COLUMN visit_days TO display_label;   -- '(Sat, Mon, Wed)' or 'Daily': a label, never a key
ALTER TABLE app.route
  ADD COLUMN kind text NOT NULL DEFAULT 'sr' CHECK (kind IN ('sr','amo')),
  ADD COLUMN visit_kind text CHECK (visit_kind IN ('daily','3f','2f')),
  ADD COLUMN visit_days_mask smallint NOT NULL DEFAULT 127 CHECK (visit_days_mask BETWEEN 1 AND 127),  -- bit0 Sat .. bit6 Fri
  ADD COLUMN sequence_no int, ADD COLUMN apsis_id text,
  ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  ADD CONSTRAINT route_kind_matches_mask CHECK (
        visit_kind IS NULL
     OR (visit_kind = 'daily' AND visit_days_mask = 127)
     OR (visit_kind = '3f'    AND bit_count(visit_days_mask::int::bit(7)) = 3)
     OR (visit_kind = '2f'    AND bit_count(visit_days_mask::int::bit(7)) = 2));
CREATE TABLE app.route_history (          -- SCD2: zone, name, label and mask by period
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, route_id bigint NOT NULL REFERENCES app.route(id),
  zone_id bigint NOT NULL, name text NOT NULL, display_label text, visit_kind text, visit_days_mask smallint NOT NULL,
  valid_from date NOT NULL, valid_to date, changed_by bigint,
  EXCLUDE USING gist (route_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
```

| Pattern (docs/22 P-17) | `visit_kind` | `visit_days_mask` (bit0 Sat, 1 Sun, 2 Mon, 3 Tue, 4 Wed, 5 Thu, 6 Fri) | Routes | Outlets | Planned on |
| --- | --- | --- | --- | --- | --- |
| Daily | daily | 127 | 3,242 | 228,904 | every working day |
| 3F group A, Sun Tue Thu | 3f | 42 | 3,041 | 380,829 across both 3F groups | Sun, Tue, Thu |
| 3F group B, Sat Mon Wed | 3f | 21 | 3,040 | | Sat, Mon, Wed |
| 2F group A, Mon Thu | 2f | 36 | about 670 | 125,049 across the three 2F groups | Mon, Thu |
| 2F group B, Sun Wed | 2f | 18 | about 670 | | Sun, Wed |
| 2F group C, Sat Tue | 2f | 9 | about 670 | | Sat, Tue |
| AMO-named, no pattern | NULL | 127 | 2 | | per the calendar |

Arithmetic check of the plan function: each of Saturday to Thursday activates all Daily routes, exactly one 3F group and exactly one 2F group, so 3,242 + about 3,040 + about 670 = about 6,952 routes and 228,904 + about 190,400 + about 41,700 = about 461,000 outlets, which is docs/22's "about 6,953 routes and 459,800 to 462,500 outlets" per trading day; Friday plans none. The importer parses the pattern out of the route name once (`Name(Sun, Tue, Thu)`, `NameDaily`) and the admin may edit it; `route_history` keeps the change by date. The 3F groups alternate, so an SR holding one route of each group has a full week; the plan is driven by pattern and assignment history, never "one SR = one route" (D-257).

Rules (D-29): target routes for Login %, "Submit % (of logged-in)" and Final Submit counts are the planned `kind = 'sr'` routes (`cfg.kpi.target_route_kinds`, default `[sr]`); an AMO route carries an AMO and no SR, so "SR Not Set" is a normal state, not a defect; list rows are (assigned user, planned route) pairs, so an AMO assigned to an SR-kind route appears; whether AMO routes count is MUST-CONFIRM (by 3b, MQ-38) and changes only the config key. "SS" is a designation of the supervisor tier seen on the AMO build (`ss344002`), not a substitute SR and not a role value; the plan wording "SS = substitute" (F-ADM-003) and "SS = sales supervisor" (M-14) are both corrected (D-187, MQ-19).

### 3.2 Products and units

The tree is category, segment, brand, variant, sku, each level with `status` (active or inactive) so the web "Active Status" filter has a source (G-man-038). `sort` is per parent, not unique, ties broken by name: the manual shows duplicate sort values (variant 2,2 / 3,3 / 13,13,13; SKU 4,4,4,4) and a segment sort starting at 3 (D-370). The seed catalogue loads as: 4 categories (Cigarette 31 SKUs, Bidi 7, Lighter 2, Match 2), 6 segments, 17 brands, 30 variants, 42 SKUs, keyed on `sku.code` because two ARIS codes (`ARIS-O-20s`, `ARIS-A-20s`) both occur (D-227). Prices load as `round(price x 1000)`: 156 rows (42 SKUs x 4 priced types, minus 12 zero prices); `price_nto` is 0.0 on all 42 SKUs and loads no rows.

```sql
-- M-02 products: status on every level, units on sku
ALTER TABLE app.product_category ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive'));
ALTER TABLE app.product_segment  ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive'));
ALTER TABLE app.product_brand    ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive'));
ALTER TABLE app.product_variant  ADD COLUMN status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive'));
ALTER TABLE app.sku ALTER COLUMN unit DROP DEFAULT;
ALTER TABLE app.sku ALTER COLUMN unit TYPE app.qty_unit USING unit::app.qty_unit;
ALTER TABLE app.sku RENAME COLUMN unit TO base_unit;               -- stick | piece | dozen (D-16)
ALTER TABLE app.sku
  ALTER COLUMN base_unit SET DEFAULT 'stick',
  ADD COLUMN status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  ADD COLUMN base_per_pack      int  NOT NULL DEFAULT 1 CHECK (base_per_pack > 0),   -- sticks per pack: the read-only pack badge (D-17)
  ADD COLUMN entry_unit_default app.qty_unit NOT NULL DEFAULT 'stick',               -- cfg.sale.qty_entry_unit may override per category
  ADD COLUMN report_unit        app.qty_unit,                                        -- Lighter: piece on web, box on the TSO app
  ADD COLUMN report_factor      numeric(12,6) NOT NULL DEFAULT 1 CHECK (report_factor > 0),  -- base units to report units
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN created_by bigint,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_by bigint, ADD COLUMN version int NOT NULL DEFAULT 1;
```

`base_unit` follows D-16 (s1.4); `base_per_pack` is the stick count behind the pack badge (10, 20 or 25 for cigarette and bidi SKUs, 1 otherwise); `report_unit` and `report_factor` convert for a surface that reports in another unit (Lighter in boxes on the TSO app). The importer keeps the export's `sku_id` as the crosswalk key (1 to 43 with gaps at 19, 20 and 40; `SupSty-10S` and `SupSty-20S` were not sold in the sample, D-259).

### 3.3 Prices, price type and the sales plan

```sql
-- M-03 sku_price: effective-dated, price per base unit (D-15, D-32, D-350)
ALTER TABLE app.sku_price
  ADD COLUMN per_base_qty smallint NOT NULL DEFAULT 1 CHECK (per_base_qty > 0),   -- price is for N base units; 1 unless a list quotes per dozen of pieces
  ADD COLUMN source     text NOT NULL DEFAULT 'admin' CHECK (source IN ('admin','seed','migration')),
  ADD COLUMN created_by bigint, ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT sku_price_nonneg  CHECK (amount_mtk >= 0),
  ADD CONSTRAINT sku_price_uniq    UNIQUE (sku_id, price_type, valid_from),
  ADD CONSTRAINT sku_price_no_overlap
      EXCLUDE USING gist (sku_id WITH =, price_type WITH =, daterange(valid_from, valid_to, '[)') WITH &&);
CREATE INDEX ON app.sku_price (sku_id, price_type, valid_from DESC);

-- M-04 sales_plan: effective-dated (a plan change is a new row, not an overwrite)
ALTER TABLE app.sales_plan DROP CONSTRAINT sales_plan_pkey;
ALTER TABLE app.sales_plan
  ADD COLUMN id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ADD COLUMN valid_from date NOT NULL DEFAULT current_date, ADD COLUMN valid_to date,
  ADD COLUMN created_by bigint, ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT sales_plan_no_overlap
      EXCLUDE USING gist (zone_id WITH =, sku_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&);
```

- Five price types per SKU (`outlet`, `cc`, `distributor`, `reporting`, `nto`), effective-dated, per base unit. A memo stores `price_list_date`, `price_type` and per line `price_valid_from`, `base_price_mtk`, so a price change at 11:00 never rewrites an earlier memo (G-feat-52, resolved: the price valid on the memo's trusted capture date applies; a stale bundle prices on the older list and is flagged `bundle_stale`, DQ-13).
- The price type is an outlet attribute resolved on the server (`outlet.price_type`, default `outlet`; `cc` for wholesale outlets) and delivered with the bundle; the SR never chooses (D-32, G-field-08). Which outlets are priced `cc` or `distributor` today is MUST-CONFIRM (by 2a, Q46).
- Role-visible prices (G-man-038): the TSO web pages show only `outlet`, `cc` and `distributor`; `reporting` and `nto` are for admin and finance. This is a read-layer rule over the same table, keyed by the role matrix of doc 19.
- `sales_plan` is effective-dated; DQ-08 checks the plan as of the memo's business date, so selling a SKU outside the plan is a flag, not a reject, and the Q21 question is answerable.

### 3.4 Users, assignments and supervisor scope

| Table | Key, constraints and change | Columns added or changed |
| --- | --- | --- |
| `app_user` | `username` becomes `citext` (case-insensitive: "tso-apsis", "TSO-1012", "AMO-6334" occur); `employee_code` unique; for memo-producing roles (`sr`, `amo`) a CHECK `username ~ '^[a-z][a-z0-9]{3,31}$'` keeps the hyphen free for the memo number `<username>-<yyMMdd>-<seq3>` (D-488, OI-17-10); imported usernames that violate it are quarantined with a rename map | designation, email, home_zone_id, `locale` (bn or en), `pilot`, apsis_id, @AUDIT; role enum gains `wmo` |
| `route_assignment` | EXCLUDE (route, user, daterange) and, for `primary`, EXCLUDE (route, daterange): one primary per route-day | `assignment_kind` primary, cover, ss; reason, created_by, ended_by, ended_at |
| `user_scope` | EXCLUDE (user, node_type, node_id, daterange) replaces the plain unique | valid_from, valid_to, changed_by, reason (M-46, lands 3a) |
| `device` | see s6.4 | model, OS, app version, ABI, RAM, printer, public key, trust level |


- `route_assignment.assignment_kind`: `primary` (one per route per day, enforced by the second exclusion), `cover` (same-day cover assigned by an AMO, up to seven days, D-85) and `ss` (supervisor-tier designation, meaning unconfirmed). Ingest resolves `visit.route_assignment_id` as the row whose route and user match and whose range contains the business date; none found keeps the route as sent and raises `unassigned_route` (accept, never reject: an offline SR cannot be blocked for cover, DQ-07).
- `user_scope` is effective-dated (M-46, lands 3a) so "by AMO" and "by TSO" reports stay right after a transfer (G-analyst-04, D-363); the dw copy is `dw.dim_supervisor_assignment`.
- The role enum gains `wmo` (approves target sets, D-31); DMO, WM, WMO and Top are web-only roles whose menus are data (Q16). `pilot` flags accounts excluded from national rollups (D-373).
- Credentials, devices, OTP and refresh tokens are in s6.4.

### 3.5 Classifications

| Table | Content |
| --- | --- |
| `app.channel` (code, label_en, label_bn, sort) | GT, DCC, Astha, RCC, MT, HoReCa; `outlet.channel` references it |
| `app.sub_channel` (channel, name) | nine rows: GT, RCC, DCC, MT, HoReCa and the Astha tiers Gold, Platinum, Diamond, Silver (D-258) |
| `app.geo_class` (code, label_en, label_bn, sort) | Hill, Urban, SemiUrban, Rural; `outlet.geo_classification` is nullable |


`geo_classification` is nullable (19 percent of outlets are blank, P-14) and the importer maps "Semi Urban" to `SemiUrban`; the AMO fills the gap at verification and the density view (s4.6) shows what is left. The channel labels the apps print ("GT Channel", "HoReCa") are collected by doc 15 and loaded into `label_en` and `label_bn`; the seed carries only the codes.

### 3.6 Code tables: reasons, types and the QC fault taxonomy

```sql
-- business code lists (G-cfg-10): text codes, effective-dated, never an enum, retired not deleted
CREATE TABLE cfg.code_list (list_key text PRIMARY KEY, description text NOT NULL,
  is_closed boolean NOT NULL DEFAULT false,               -- closed list: code set drives logic, only labels are editable
  owner_bundle text NOT NULL DEFAULT 'config_editor');
CREATE TABLE cfg.code_item (
  list_key text NOT NULL REFERENCES cfg.code_list, code text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,40}$'),
  label_bn text, label_en text NOT NULL, sort int NOT NULL DEFAULT 0, attrs jsonb NOT NULL DEFAULT '{}',
  valid_from date NOT NULL DEFAULT date '2000-01-01', valid_to date,
  created_by bigint, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (list_key, code));
```

| List key | Seeded codes (evidence) | Closed | Used by |
| --- | --- | --- | --- |
| force_reason | internet_problem, location_change (docs/05); no_outlet_location (D-95); manual_override (AMO) | no | `visit.force_reason_code` |
| edit_reason | wrong_sku ("ভুল SKU নির্বাচিত।", SR p47); the other two are captured from the live app (MQ-18); wrong_outlet is dropped because the outlet is read-only on edit (D-200) | no | `memo.edit_reason_code` |
| void_reason | authored with AKTCL (G-field-04) | no | `memo_void.reason_code` |
| task_type, task_status | oos, general, irregular_visit; ongoing (চলমান), completed (সম্পন্ন), cancelled (D-167) | status yes | `task` |
| leave_type | casual, sick, earn | no | `leave_application` |
| drp_kind | empty_pack, slide | no | `drp_collection` |
| feedback_category | suggestion only (D-197); no invented defaults | no | `feedback` |
| visit_outcome | sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned (D-38) | yes | `visit.outcome_code`, `visit_skip` |
| day_exception_reason | rain_flood, hartal, market_closed, dh_out_of_stock, breakdown, sick (D-39) | no | `day_exception` |
| photo_purpose | capture, base_update, info, force_sale, manual_override, verification | yes | `outlet_photo` |
| payment_mode, stock_variance_reason | cash; authored | no | `due_collection`, `stock_movement` |

```sql
-- QC fault taxonomy: union of the 6 app and 10 web labels, 11 codes (G-man-003, G-man-089, D-34)
CREATE TABLE cfg.qc_fault_type (
  code text PRIMARY KEY, grp text NOT NULL CHECK (grp IN ('MFC','MKT')),   -- MFC = production / manufacturing, MKT = transport / marketing
  label_bn text, label_en text NOT NULL, applies_to text[] NOT NULL DEFAULT '{app}',
  sort int NOT NULL DEFAULT 0, active boolean NOT NULL DEFAULT true);
```

The QC taxonomy is a typed table because `qc_entry_line` references it: 5 MFC (production) and 6 MKT (transport or marketing) codes, the union of the 6 app labels and the 10 web labels. App group "উৎপাদন ত্রুটি" is MFC and "পরিবহন ত্রুটি" is MKT; the web calls them Manufacturing and Marketing Fault; the stable codes are the group codes. Overlap map: damaged_crushed, short_outer_pack_stick, other_mfc, expired_stock (4 months and over: `cfg.qc.expired_stock_months`) and damaged_in_transit exist in both; taste is app only; brand_mix_up, visual_fault, damp_stick, spotting and other_mkt are web only.

### 3.7 Offers and promotions

```sql
-- M-20 / M-103 offers: typed fields for the one rule we have evidence for, jsonb for the rest (D-33, G-man-004)
CREATE TABLE app.offer (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, code text NOT NULL UNIQUE,
  group_code text NOT NULL,                                          -- the ~22 promotion groups behind the Discount Report (Q13: catalogue unknown)
  offer_type text NOT NULL CHECK (offer_type IN ('pct_discount','amount_discount','free_qty','drp_slide','free_sample','bundle')),
  level text NOT NULL CHECK (level IN ('line','memo')),
  title_bn text, title_en text,                                      -- "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s"
  valid_from date NOT NULL, valid_to date NOT NULL, CHECK (valid_to >= valid_from),
  threshold_qty_base int CHECK (threshold_qty_base > 0),             -- drp_slide: 100 sticks of empty packs
  reward_sku_id bigint REFERENCES app.sku(id), reward_qty_base int CHECK (reward_qty_base > 0),   -- 1 pack MaxR-10S = 10 sticks
  rule jsonb NOT NULL DEFAULT '{}',                                  -- every other type, until the catalogue is supplied: sanctioned jsonb exception J-3 (s8.10) with a promotion lifecycle, typed at 2a entry (D-501)
  stacking text NOT NULL DEFAULT 'exclusive' CHECK (stacking IN ('exclusive','stackable')),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, updated_at timestamptz NOT NULL DEFAULT now(), updated_by bigint, version int NOT NULL DEFAULT 1);
CREATE TABLE app.offer_product (offer_id bigint NOT NULL REFERENCES app.offer(id), product_level text NOT NULL CHECK (product_level IN ('category','brand','variant','sku')),
  product_id bigint NOT NULL, role text NOT NULL DEFAULT 'qualifier' CHECK (role IN ('qualifier','reward')), PRIMARY KEY (offer_id, product_level, product_id, role));
CREATE TABLE app.offer_scope (offer_id bigint NOT NULL REFERENCES app.offer(id),
  node_type text NOT NULL CHECK (node_type IN ('wing','division','territory','house','zone','route','channel','sub_channel')),
  node_key text NOT NULL, PRIMARY KEY (offer_id, node_type, node_key));
CREATE TABLE app.offer_version (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, offer_id bigint NOT NULL REFERENCES app.offer(id),
  version int NOT NULL, snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), created_by bigint, UNIQUE (offer_id, version));
```

The promotion catalogue (about 22 groups behind the Discount Report) is unknown (Q13); it is the 2a entry condition (D-33, D-143). Lifecycle of the exception J-3: when the catalogue arrives, migration M-59 adds typed rule tables (`app.offer_tier(offer_id, tier_no, min_qty_base, min_value_mtk, discount_bp, discount_mtk, free_sku_id, free_qty_base)` and typed columns for each rule kind found), the importer fills them from `rule`, and `rule` becomes read-only and is dropped in the next contract release; `dw.fact_memo_offer` and the Discount Report never read `rule` (D-501, G-qa-26). Until it arrives only the DRP slide rule has typed columns, from the one fixture the manuals show: 10 empty MaxR-10S packets (100 sticks of empties) earn 1 pack of MaxR-10S (10 sticks x 8.00 = 80.00), valid 2025-11-25 to 2025-12-30, shown as a deduction with the quantity total unchanged. Every other offer type lives in `rule jsonb` and is typed when the catalogue is supplied. A memo stores `offer_version_set` (the versions it applied) so a report can say which rule produced a discount. Where auto-applied offer discounts render on Review is unknown (MQ-06); the schema stores them as `memo_offer` rows of kind `offer_discount` and `memo.offer_discount_mtk`, so any rendering works.

### 3.8 Definitions that the apps download

| Definition | Tables | Notes |
| --- | --- | --- |
| Survey and questions | `app.survey`, `app.survey_question` (answer type, conditional parent, `points_reward`) | POSM survey Q1.1 photo earns 50 points for an SR, never for an AMO survey (D-41); AMO survey screen unconfirmed (G-man-044) |
| Assessment rubric and criteria | `app.assessment_rubric`, `app.assessment_criterion` (section five_step, relationship, service_quality, visit_query) | Joint-call items 4 and 5 are not in the manual: seeded as two disabled placeholders so adding them is a data change (G-man-051); Visit Query is two free-text criteria plus a built-in delegate radio (D-199) |
| Content | `app.content_item`, `app.outlet_content_assignment` (scope outlet, cluster, route or channel; order AV, KV, survey, sale) | G-man-043 |
| Tutorial, release, support | `app.tutorial_asset`, `app.app_release`, `app.support_upload` | G-data-27 |

DDL for these is in s5.10.

### 3.9 Runtime configuration: the data contract

Doc 19 owns the key registry, risk classes, rails and propagation; this document owns the tables they live in (G-data-20). Resolution precedence is outlet, route, zone, geo_class, house, territory, division, wing, wave, role, global; every captured row stores `config_version` and the resolved values it used (`radius_m_used`); the server re-checks as of the capture time (D-87).

| Table | Purpose and constraint |
| --- | --- |
| `cfg.config_item` | one row per key: value type, default, bounds (`constraints`), allowed scope levels, risk class C0 to C3, delivery (bundle, token or server only), `requires_ack` |
| `cfg.config_value` | scoped, effective-dated values; `EXCLUDE (key, scope_type, scope_key, tstzrange)` forbids overlapping values; every row carries the `config_version` it created and a mandatory `change_reason` |
| `cfg.config_change_audit` | append-only, hash-chained (D-113): old and new value, risk class, `is_revert_of`, `break_glass`, approver |
| `cfg.config_ack` | which device acknowledged which version; feeds `dw.fact_config_change.acked_pct` (R6 reach) |
| `app.rollout_wave`, `app.rollout_wave_member` | wave scope for flags and radius canaries (D-87); `(wave_id, scope_type territory or zone, scope_id)`, a territory belongs to at most one live wave; names as in doc 19 s9.3 (the first draft wrote `cfg.wave`) |
| `cfg.config_version_scope`, `app.role_ref` | per-scope version rows that answer "relevant version of a chain" and "behind by n" (DDL in doc 19 s2); the role code table that scope_type `role` points at (roles become a code table beside the enum, ASSUMPTION of doc 19 s2.2) |
| `cfg.territory_geo_config` (view) | compatibility view that resolves `cfg.geo.radius_m` at territory then global level; replaces the baseline table |
| `cfg.holiday`, `cfg.route_day_override` | the calendar as data (s11.1) |


Proved by: T-0-01, T-0-02, T-0-08, T-0-09, T-1-03.

## 4 Outlet book

The outlet table is the largest master (734,789 rows on the 1 October list plus 175,031 archived stubs) and the one the geo gate, the dues and every channel report join to. Three rules shape it: an outlet is never deleted (D-25), its history is kept as SCD rows so past sales stay in the cluster, channel and tier they were made in (UI-SR-40), and PII is encrypted or confined (D-107).

### 4.1 Outlet v2

```sql
-- M-16 outlet v2 (D-25, D-32, D-42, D-107, D-253, G-field-10, G-field-08)
ALTER TABLE app.outlet
  DROP COLUMN nid, DROP COLUMN tin, DROP COLUMN trade_license,
  ADD COLUMN nid_enc bytea, ADD COLUMN tin_enc bytea, ADD COLUMN trade_license_enc bytea,   -- envelope-encrypted, never searched (D-107)
  ADD COLUMN pii_key_id text,
  ADD COLUMN outlet_kind text NOT NULL DEFAULT 'retail' CHECK (outlet_kind IN ('retail','wholesale')),
  ADD COLUMN price_type app.price_type NOT NULL DEFAULT 'outlet',          -- resolved on the server, never chosen by the SR (D-32)
  ADD COLUMN location_confirmed boolean NOT NULL DEFAULT false,            -- false for missing and placeholder pins (D-253)
  ADD COLUMN location_source text, ADD COLUMN location_updated_at timestamptz, ADD COLUMN location_accuracy_m double precision,
  ADD COLUMN phone_hash bytea, ADD COLUMN phone_hash_pepper_version smallint,            -- HMAC of the normalised phone (G-analyst-12)
  ADD COLUMN name_sort_key text,                                           -- server-generated ICU sort key shipped in the bundle (G-field-10)
  ADD COLUMN thumbnail_media_id bigint,
  ADD COLUMN merged_into_outlet_id bigint REFERENCES app.outlet(id),
  ADD COLUMN archived_reason text,
  ADD COLUMN extra jsonb NOT NULL DEFAULT '{}',                            -- web "business" / "additional detail" sections until the field list is confirmed: sanctioned jsonb exception J-4 (s8.10), typed by 4c (D-501)
  ADD COLUMN apsis_id text, ADD COLUMN apsis_code_raw text,
  ADD COLUMN created_by bigint, ADD COLUMN updated_by bigint, ADD COLUMN version int NOT NULL DEFAULT 1,
  ADD CONSTRAINT outlet_status_chk CHECK (status IN ('active','closed','merged','archived')),
  ADD CONSTRAINT outlet_code_form  CHECK (outlet_code ~ '^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$'),   -- text, never a number (D-251)
  ADD CONSTRAINT outlet_mobile_chk CHECK (contact_number IS NULL OR contact_number ~ '^01[3-9][0-9]{8}$') NOT VALID,  -- importer fixes legacy rows, then VALIDATE
  ADD CONSTRAINT outlet_merge_chk  CHECK ((status = 'merged') = (merged_into_outlet_id IS NOT NULL));
CREATE INDEX ON app.outlet (cluster_id);   CREATE INDEX ON app.outlet (status);
CREATE INDEX ON app.outlet (channel, sub_channel_id);   CREATE INDEX ON app.outlet (phone_hash) WHERE phone_hash IS NOT NULL;
CREATE INDEX outlet_name_trgm ON app.outlet USING gin (name gin_trgm_ops);
-- ALTER TABLE app.outlet ADD COLUMN geom geography(Point,4326) GENERATED ALWAYS AS
--   (CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL THEN ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography END) STORED;
-- CREATE INDEX ON app.outlet USING gist (geom);                          -- ST_DWithin: periphery radius, density, duplicate check (D-355)
```

- `status`: active, closed, merged, archived; closing with open dues warns (`cfg.outlet.close_block_if_dues = warn`, D-25). Archived stubs hold history for outlets that exist only in the sales file (s4.6).
- `outlet_code` is text with a form check; leading symbols (`:`, `*`, `।`) are stripped by the importer, never stored (P-07).
- `contact_number` is canonical 11 digits `01[3-9]xxxxxxxx`, displayed with the leading 0 (the apps show 10 digits, the web 11; G-man-037); the CHECK is `NOT VALID` until the importer has normalised legacy rows, then validated. Bengali digits typed in the field are converted by `app.normalise_digits()` on the device and at ingest and raise DQ-44 when the text changed (G-field-10).
- `nid_enc`, `tin_enc`, `trade_license_enc` are envelope-encrypted, never searched and never in bundles or dw; the sample shows NID is the placeholder `123` for 589k outlets and blank for 145k, TIN and licence are empty, so the importer treats `123` as null and nothing is built on these columns (P-12, D-256).
- `name_sort_key` is the server-generated ICU sort key shipped in the bundle so SQLite orders Bangla and Latin names the same way as PostgreSQL's `bn-x-icu` collation (G-field-10); the trigram index serves duplicate-outlet detection (FS-03).
- `price_type` and `outlet_kind` are resolved on the server (D-32, D-42); wholesale marking is a bulk, idempotent, audited operation with a `batch_uuid` on `outlet_class_history`.

### 4.2 Histories and the trigger

```sql
CREATE TABLE app.outlet_placement_history (                                -- SCD2: route and cluster by period (UI-SR-40)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  route_id bigint REFERENCES app.route(id), cluster_id bigint REFERENCES app.cluster(id),
  valid_from date NOT NULL, valid_to date, change_request_id bigint, changed_by bigint, reason text,
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
CREATE TABLE app.outlet_class_history (                                    -- channel, tier, geo class, kind, price type by period
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  channel text, sub_channel_id bigint, geo_classification text, outlet_kind text, price_type app.price_type, status text,
  valid_from date NOT NULL, valid_to date, change_request_id bigint, batch_uuid uuid, changed_by bigint, reason text,
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&));
CREATE TABLE app.outlet_location_history (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  latitude double precision NOT NULL, longitude double precision NOT NULL, accuracy_m double precision,
  source text NOT NULL CHECK (source IN ('migration','capture','force_sale','manual_override','base_update','web_edit','verification')),
  source_ref_uuid uuid, fix_id bigint, changed_by bigint, valid_from timestamptz NOT NULL, valid_to timestamptz);
CREATE INDEX ON app.outlet_location_history (outlet_id, valid_from DESC);
CREATE TABLE app.outlet_location_provisional (        -- device and server use it until the location request is decided (D-95, D-163)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  request_id bigint NOT NULL, user_id bigint NOT NULL, latitude double precision NOT NULL, longitude double precision NOT NULL,
  valid_from timestamptz NOT NULL DEFAULT now(), valid_to timestamptz);
```

An `AFTER INSERT OR UPDATE` trigger on `app.outlet` (`app.outlet_history_sync`) closes the open row and opens a new one when route or cluster, the class columns, or the coordinates change; the location row takes its `source` from `outlet.location_source`.

Placement history carries route and cluster (a cluster change with the route unchanged opens a new row); class history carries channel, sub-channel (the Astha tier), geo class, kind, price type and status; location history carries every coordinate the outlet ever had with its source. A same-day second change replaces the open row instead of creating a zero-length one. A back-dated admin correction does not use the trigger: it inserts history with an explicit `valid_from`, enqueues `dim_backdate` dirty keys for the affected days and shows "N days will be re-aggregated" before it applies (s8.6).

### 4.3 Merge model and change requests

| Table | Key and constraints | Columns beyond the baseline |
| --- | --- | --- |
| `outlet_merge` (M-50) | `UNIQUE (from_outlet_id)`, from differs from into; the merged outlet gets `status = merged` and `merged_into_outlet_id` (CHECK ties the two) | merged_by, reason, change_request_id; dues and points move by a `transfer_out` and `transfer_in` pair, history is never re-pointed (G-analyst-12) |
| `outlet_change_request` (M-15, M-107) | `client_uuid` NOT NULL (web rows get a server uuid); status enum pending, verified, approved, rejected | cluster_id, route_id, origin (sr, amo, web), origin_role, purpose (force_sale, manual_override, base_update), verified_via (app, web), verified_at, verifier_payload, verifier_fix_id, verifier_photo_media_id, remote_verification, approved_at, rejected_by, rejected_at, rejection_reason, moved_m, requires_tso, resulting_outlet_id, request_fix_id, requester_photo_media_id, @PROV subset |
| `outlet_change_event` | append-only: requested, verified, rejected, approved, reopened, discarded | actor, at, payload |
| `outlet_photo` (M-15) | `outlet_id` nullable; CHECK (outlet_id or change_request_id) | client_uuid unique, change_request_id, visit_client_uuid, media_id, fix_id, taken_by, business_date |
| `outlet_location_provisional` | one open row per outlet and request | latitude, longitude, user_id, valid_from, valid_to |


Lifecycle (D-43, D-172; evidence is the Web manual, the AMO transitions are inference):

| Step | Actor and surface | Effect |
| --- | --- | --- |
| requested | SR or AMO app (OFFLINE capture, QUEUED upload), or web | `status = pending`; photo and fix attached; types new, close, info, base_update, location, cluster |
| verified | AMO app Save, or web Verify (`verified_via`) | `status = verified`; AMO sets sub-channel and geo class (required on new shop, G-man-035) and may re-capture GEO and photo |
| discarded | AMO "বাতিল" | no server call (event `discarded` only if the form was already uploaded); confirm with the business (MQ-29) |
| approved | web Approve (confirm dialog evidenced) | new: code assigned, `resulting_outlet_id` set, outlet visible on the route; close: `status = closed`, never deleted; info: `proposed` applied; location: coordinates change, history row, provisional row closed |
| rejected | web Reject on a verified row | `rejection_reason` (cfg.outlet.reject_requires_reason), shown to the SR (IMPROVEMENT; the manual shows no rejected state) |

Requests store both `route_id` and `cluster_id`: SR and AMO forms pick a cluster and the route is derived (the SR's route that day; NULL for AMO-created outlets until the approver sets it, MQ-31); AMO verification forms show both. GEO and photo are required on info change (the manual writes "must") and are an IMPROVEMENT on new shop (D-43; the Save button is visible without them in the manual). An outlet photo can precede the outlet it creates, so `outlet_photo.outlet_id` is nullable with an owner check (G-data-18).

### 4.4 Location change policy: data that makes it enforceable

| Situation | Path | Stored |
| --- | --- | --- |
| Outlet has no coordinates or a placeholder pin (`location_confirmed = false`) | the first force-sale or capture sets the location immediately (D-95) | `outlet_location_history` row, `location_confirmed = true` |
| Confirmed outlet, SR force-sale or AMO manual override photo | the call proceeds (`photo_validated`, not geo-valid); a `location` request is raised; the device and the server use a provisional point until the request is decided (D-163) | `outlet_change_request` (purpose force_sale or manual_override), `outlet_location_provisional`, `visit.location_basis = provisional` |
| Move above 300 m on a confirmed outlet | needs TSO approval (D-111, DQ-37) | `moved_m`, `requires_tso` |
| Verifier's own fix is outside the radius of the proposed point | flagged `remote_verification` | `remote_verification` |
| Proposed point within 500 m of the requester's check-in centroid | flagged `moved_to_home` (doc 21) | flag on the request |
| AMO Update Base | within `cfg.geo.update_base_max_distance_m` (100 m) of the chosen point, a move limit, no mocked fix, a monthly cap (D-95) | `base_update` request and history |

This is a DELIBERATE CHANGE from the observed behaviour (the manual says "location information will be updated" on photo capture and shows no approval, D-163): the integrity decision stands, the field cost is removed by the provisional point.

Volume control (D-545, G-qa-76): D-163 raises a request on every force sale or manual override at a confirmed outlet, and with 80 percent of outlets in a shared 55 m cell and about 460,000 planned visits a day the queue could reach tens of thousands a day (the P-11 basis of 500 a day is today's outlet-creation trickle, not these requests). The data therefore enforces: (1) one OPEN request per outlet and purpose (`UNIQUE (outlet_id) WHERE status IN ('pending','verified') AND purpose IN ('force_sale','manual_override')`); a repeat force sale appends a row to `outlet_change_evidence (request_id, visit_id, fix_id, accuracy_m, at)` and updates `evidence_count` and the median proposed point instead of creating a request; (2) a minimum move: no request is raised when the proposed point is within `cfg.outlet.location_request_min_move_m` (default 25) of the current or provisional point; (3) an accuracy filter: a proposal from a fix with accuracy above `cfg.outlet.location_request_max_accuracy_m` (default 50) or a mocked fix raises no request (the visit is still a force sale, flagged); (4) aging and escalation: `age_h` is derived, a request unresolved after `cfg.sla.location_request_escalate_h` (default 72) goes to the TSO queue and one unresolved after `cfg.sla.location_request_lapse_days` (default 14) lapses as `lapsed` with the provisional point retired; (5) a bulk approve for supervisors: "approve all proposals consistent within X m" (`cfg.outlet.bulk_approve_consistent_m`, default 30) where at least `cfg.outlet.bulk_approve_min_evidence` (default 3) independent visits agree. The expected volume is modelled in doc 18 s1.11 and gated by T-4-152.

### 4.5 Archived stubs (docs/22 P-08)

175,031 outlets (2.6 M rows, 277 M sticks) sit in the sales file and not in the 1 October list. The importer creates one `status = archived` stub per code: `outlet_code` as normalised, `name = '[archived] <code>'`, no coordinates, `location_confirmed = false`, `valid_from` of the class history = the first sale date. A stub owns history, dues and loyalty and is never counted in target outlets. The sales file has no route or zone for a stub, so its history rolls up to `dw.dim_geo` member 0 "Unmapped" until the dump supplies each outlet's last route (D-367, gap G-16-10): a wing-level year-on-year chart over the cutover therefore omits those 277 M sticks and says so (s15 Q24).

### 4.6 Spatial index, density and placeholder pins

D-355: PostGIS is adopted for outlet geometry (`geography(Point, 4326)` generated from latitude and longitude, GiST index, `ST_DWithin`). It serves the TSO "Retailer" radius screen (50, 100 or 300 m), the density view, the duplicate check and the offline-equivalent recheck. The extension must be on the Flexible Server allow-list (infrastructure item for doc 18); the fallback is `earthdistance`, which is less accurate near the poles of its cube and not needed in Bangladesh.

| Finding (docs/22) | Data consequence |
| --- | --- |
| 1,093 outlets have no coordinates | `location_confirmed = false`; opens as a force sale with reason `no_outlet_location` (D-95) |
| 34,454 outlets sit on 11,222 identical points | importer sets `location_confirmed = false` for a group of at least `cfg.outlet.placeholder_pin_min_shared` (default 3, ASSUMPTION: two shops in one building are legitimate; the older key `cfg.geo.placeholder_min_shared` is retired, D-525) outlets on one 6-decimal point; the first visit goes through the correction path, not a geo-fail |
| 80 percent of outlets share a 55 m cell with another; the densest cell holds 383 | `dw.agg_outlet_density(n_within_55m, n_within_100m, nearest_m, shared_point_group)` computed nightly with `ST_DWithin` (these are the `neighbors_within_<r>` measures of doc 19 s6, one column per radius of `cfg.geo.density_neighbour_radii_m`); the admin density view and the radius what-if read it (doc 19) |

Proved by: T-0-01, T-1-04, T-2-08, T-7-06.

## 5 Field transaction tables

Everything a rep, an AMO or a TSO captures lands here. These tables are written by the sync ingest in one transaction per batch: one savepoint per batch on the fast path and bisection of a failing batch on the slow path, never a savepoint per record (D-65, D-411, D-521), are immutable after sync except the named enrichment columns (s6.6), and carry @PROV (s1.6). The sync record type is the table name; doc 17 owns the enum and generates it from `/packages/contract`.

```sql
-- the baseline tables that v2 replaces are dropped; legal only because no production data exists at 0b (from 1a on every change is expand/contract)
DROP TABLE app.memo_line, app.qc_entry, app.survey_response, app.drp_collection, app.due_collection, app.memo, app.visit, app.stock_issue;
```

### 5.1 Map of the capture tables

| Group | Tables | Capture class | Partitioned | Design rows a day (D-125, ASSUMPTION for the split) |
| --- | --- | --- | --- | --- |
| Visit | `visit`, `geo_fix`, `visit_skip` | OFFLINE | yes (skip no) | 0.5 M visits; fixes about one per visit plus attendance |
| Sale | `memo`, `memo_line`, `memo_offer`, `qc_entry`, `qc_entry_line`, `drp_collection`, `drp_collection_line`, `survey_response`, `content_view` | OFFLINE | yes | 0.45 M memos, about 1.1 M lines at 2.5 lines per call |
| Money | `due_collection`, `due_adjustment`, `due_dispute`, `cash_handover` | OFFLINE and ONLINE-ONLY | collection yes | collections a small fraction of memos |
| Stock and attendance | `stock_movement`, `attendance_event`, derived `attendance` | OFFLINE | events yes | about 6 stock rows and 2 attendance events per SR-day |
| Corrections | `memo_void`, `print_event`, `attribution_event` | QUEUED, admin | void and print yes | prints about one per memo |
| Supervisor captures | `task`, `task_event`, `distribution_check` and lines, `call_assessment` and answers, `price_compliance_check`, `leave_application`, `visit_plan`, `visit_plan_outlet`, `feedback` | QUEUED | no | low volume |
| Web back office | `web_entry_route_day`, `web_entry_line`, `web_entry_line_class`, `web_entry_outlet_sku`, `qc_summary_entry`, `data_void`, `entry_unlock_grant` | ONLINE-ONLY | no | low volume |
| Day control | `route_day`, `supervisor_day`, `day_exception`, `final_submit` and snapshot | QUEUED | no | 7,000 route-days |

About 350 rows per SR-day (47 visits, 41 memos, 82 lines, the rest QC, survey, DRP, stock and events, skeleton s6.1) is the load all partition and index choices are sized for.

### 5.2 Position samples

One fused balanced-power fix per event, never a stream (D-74); the `purpose` says which event the fix belongs to. `radio_env` holds the raw passive cell identities and hashed Wi-Fi BSSIDs read at the same moment (D-110) and the ingest parser also writes the typed columns `cell_mcc`, `cell_mnc`, `cell_tac`, `cell_ci`, `cell_rssi_dbm`, `cell_neighbour_count`, `wifi_count` so that no radio feature exists only inside a blob (D-501, G-qa-26); `fix_age_ms` and `satellites` let the server detect reused or synthetic fixes (DQ-35); the enrichment columns are filled by the worker after sync. Raw fixes stay on the primary for six months (s13.1).

```sql
-- M-17 geo_fix: every position sample, one table (D-110, D-74)
CREATE TABLE app.geo_fix (
  id bigint GENERATED ALWAYS AS IDENTITY,
  /*@PROV*/,
  user_id bigint NOT NULL REFERENCES app.app_user(id),
  purpose text NOT NULL CHECK (purpose IN ('attendance_in','attendance_out','visit_open','force_sale','memo_edit','memo_void','due_collection','outlet_capture','base_update','verification','refresh')),
  ref_type text, ref_client_uuid uuid,                              -- the record this fix belongs to
  fixed_at timestamptz NOT NULL,                                    -- timestamp reported by the provider
  lat double precision NOT NULL CHECK (lat BETWEEN -90 AND 90), lng double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  accuracy_m double precision, altitude_m double precision, speed_mps double precision, bearing double precision,
  provider text, is_mock boolean NOT NULL DEFAULT false, fix_age_ms int, satellites smallint,
  radio_env jsonb,                                                  -- RAW passive cell ids and hashed Wi-Fi BSSIDs (D-110); sanctioned jsonb exception J-2 (s8.10); the typed projection below is what reports read
  cell_mcc smallint, cell_mnc smallint, cell_tac int, cell_ci bigint, cell_rssi_dbm smallint, cell_neighbour_count smallint,   -- typed radio features, parsed at ingest from radio_env (D-501)
  wifi_count smallint, wifi_hash_known_count smallint,              -- visible APs and how many hashed BSSIDs were seen before at this outlet (set by the worker)
  prev_fix_id bigint, dist_from_prev_m double precision, secs_from_prev int, implied_speed_mps double precision,   -- server enrichment
  plausibility_flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.geo_fix (user_id, fixed_at);   CREATE INDEX ON app.geo_fix (ref_client_uuid);
CREATE INDEX ON app.geo_fix (business_date) WHERE is_mock;
```

### 5.3 Visit

```sql
-- M-05 visit v2 (D-26, D-38, D-48, D-78, D-85, D-87)
CREATE TABLE app.visit (
  id bigint GENERATED ALWAYS AS IDENTITY,
  /*@PROV*/, /*@SIG*/
  kind app.visit_kind NOT NULL DEFAULT 'sr_call',
  user_id bigint NOT NULL REFERENCES app.app_user(id),
  acting_for_user_id bigint REFERENCES app.app_user(id),            -- the route's assignee when someone else captured (D-85)
  outlet_id bigint NOT NULL REFERENCES app.outlet(id),
  route_id bigint NOT NULL REFERENCES app.route(id),                -- route being worked, not the outlet's current route (G-data-02)
  route_assignment_id bigint REFERENCES app.route_assignment(id),   -- NULL raises flag unassigned_route (DQ-07)
  zone_id bigint NOT NULL REFERENCES app.zone(id), cluster_id bigint REFERENCES app.cluster(id),
  opened_at timestamptz NOT NULL,                                   -- outlet opened: the visit exists from here
  call_started_at timestamptz, call_declined boolean NOT NULL DEFAULT false,   -- after the start-call prompt (D-78)
  ended_at timestamptz,
  outcome_code text,                                                -- cfg.code_item visit_outcome; NULL while open (D-38)
  open_fix_id bigint,                                               -- app.geo_fix used for the check
  device_geo_verdict text NOT NULL CHECK (device_geo_verdict IN ('in_range','out_of_range','no_fix','mocked')),
  device_distance_m double precision, radius_m_used int,            -- value and the config_version above (D-87)
  location_basis text NOT NULL DEFAULT 'master' CHECK (location_basis IN ('master','provisional','placeholder','none')),
  gps_retry_count smallint NOT NULL DEFAULT 0,
  device_geo_valid boolean NOT NULL DEFAULT false, is_mock boolean NOT NULL DEFAULT false,
  server_distance_m double precision, server_radius_m_used int,     -- server verdict: authoritative, written once by ingest
  server_geo_valid boolean, server_checked_at timestamptz,
  geo_mismatch boolean GENERATED ALWAYS AS (server_geo_valid IS NOT NULL AND server_geo_valid <> device_geo_valid) STORED,
  plausibility_flags text[] NOT NULL DEFAULT '{}', suspicion_score smallint CHECK (suspicion_score BETWEEN 0 AND 100),
  photo_validated boolean NOT NULL DEFAULT false,                   -- kept apart from geo validity everywhere (docs/05)
  force_reason_code text, force_photo_media_id bigint, location_request_id bigint,
  is_zero_sale boolean NOT NULL DEFAULT false, qc_completed_at timestamptz,
  suggestion_snapshot jsonb, bundle_version text, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date),
  CONSTRAINT visit_mock_never_valid CHECK (NOT (is_mock AND COALESCE(server_geo_valid, false)))   -- invariant (D-48)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.visit (route_id, business_date);  CREATE INDEX ON app.visit (outlet_id, business_date);
CREATE INDEX ON app.visit (user_id, business_date);   CREATE INDEX ON app.visit (received_at);
CREATE INDEX ON app.visit (business_date) WHERE cardinality(plausibility_flags) > 0 OR is_mock;
```

- Kinds follow D-26: `sr_call`, `amo_control_call`, `amo_joint_call`, `tso_visit`, `web_entry`. Route-level KPIs include every active memo or visit on the route whoever sold; AMO control-call sales are counted apart (`amo_successful_calls`) so they never inflate the SR strike rate; user-level KPIs use `coalesce(acting_for_user_id, user_id)` (D-26, G-analyst-03).
- The visit exists from outlet open (`opened_at`); the start-call prompt sets `call_started_at` or `call_declined` and a declined call is not counted visited (D-78). `outcome_code` is NULL while the visit is open and one of the eight outcomes at the end (D-38); an abandoned visit (no activity for `cfg.visit.abandon_min`) is closed by the app and excluded from visited and CPR (`cfg.kpi.count_abandoned_visits` false).
- `route_id` is the route being worked, taken at capture, not the outlet's current route (seed finding 3). Re-attribution by an admin is an `attribution_event` applied at aggregation, never an UPDATE (s5.8).
- Geo: the device writes its verdict (`in_range`, `out_of_range`, `no_fix`, `mocked`), the distance it computed and the radius it used with the `config_version` it ran under; the server writes `server_geo_valid`, `server_distance_m` and `server_radius_m_used` (the radius in force at the trusted capture time, which differs when the phone had not yet pulled a change). `geo_mismatch` is generated. A device that claims valid while the server says not is the detection, never an overwrite (P4).
- `location_basis` records which point the device used: `master`, `provisional` (a pending location request, s4.4), `placeholder` or `none`. Force sale keeps `photo_validated` apart from geo validity: a force sale never counts as a clean geo sale in any KPI (docs/05).
- Zero sale is an outcome plus a zero memo (D-36); it is a visit and a no-sale and is not a successful call.

### 5.4 Memo

```sql
-- M-06 memo v2 (D-18, D-19, D-35, D-36, D-350)
CREATE TABLE app.memo (
  id bigint GENERATED ALWAYS AS IDENTITY,
  /*@PROV*/, /*@SIG*/
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL,
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), user_id bigint NOT NULL REFERENCES app.app_user(id),
  acting_for_user_id bigint REFERENCES app.app_user(id),
  route_id bigint NOT NULL REFERENCES app.route(id), zone_id bigint NOT NULL REFERENCES app.zone(id),
  memo_no text NOT NULL,                                            -- '<username>-<yyMMdd>-<seq3>' composed on the device (D-35)
  memo_serial bigint,                                               -- optional server series for Apsis continuity (Q5)
  price_list_date date NOT NULL, price_type app.price_type NOT NULL, offer_version_set jsonb, rounding_mode_used text NOT NULL DEFAULT 'half_up_paisa',   -- offer_version_set: sanctioned exception J-6, the device's snapshot; typed form is memo_offer.offer_version_id (s8.10)
  committed_at timestamptz NOT NULL,                                -- the commit is the second dialog, not the print (D-77)
  gross_mtk bigint NOT NULL, offer_discount_mtk bigint NOT NULL DEFAULT 0, drp_discount_mtk bigint NOT NULL DEFAULT 0,
  qc_deduction_mtk bigint NOT NULL DEFAULT 0, round_adj_mtk bigint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL,
  is_credit boolean NOT NULL DEFAULT false, paid_mtk bigint NOT NULL DEFAULT 0, due_mtk bigint NOT NULL DEFAULT 0,
  outstanding_before_mtk bigint, printed_total_due_mtk bigint, due_snapshot_asof timestamptz,   -- what the paper said (G-analyst-15)
  line_count smallint NOT NULL DEFAULT 0, is_zero_memo boolean GENERATED ALWAYS AS (line_count = 0) STORED,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','superseded','void')),
  supersedes_memo_id bigint, supersedes_client_uuid uuid, edit_reason_code text, edit_fix_id bigint, edit_seq smallint NOT NULL DEFAULT 0,
  superseded_at timestamptz, voided_at timestamptz,                 -- server enrichment
  printed_at timestamptz, print_count smallint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (memo_no, business_date),
  CONSTRAINT memo_net_chk    CHECK (net_mtk = gross_mtk - offer_discount_mtk - drp_discount_mtk - qc_deduction_mtk + round_adj_mtk),
  CONSTRAINT memo_paisa_chk  CHECK (net_mtk % 10 = 0 AND abs(round_adj_mtk) <= 5),               -- printed total is a whole paisa
  CONSTRAINT memo_pay_chk    CHECK (paid_mtk >= 0 AND paid_mtk + due_mtk = net_mtk AND (due_mtk >= 0 OR net_mtk < 0)),
  CONSTRAINT memo_credit_chk CHECK (is_credit = (due_mtk > 0)),
  CONSTRAINT memo_zero_chk   CHECK (line_count > 0 OR gross_mtk = 0)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo (outlet_id, business_date);  CREATE INDEX ON app.memo (route_id, business_date);
CREATE INDEX ON app.memo (visit_id, business_date);
CREATE INDEX ON app.memo (outlet_id) WHERE due_mtk > 0 AND status = 'active';                      -- open dues lookup
CREATE SEQUENCE app.memo_serial_seq;   -- START set at import to max(Apsis memo no) + 1 if Q5 says continue
```

- `memo_no` is `<username>-<yyMMdd>-<seq3>` composed on the device inside the same transaction as the insert, with disjoint blocks of 500 numbers per device-bind ordinal (two devices per user at most, so seq3 stays below 1000); the server keeps it verbatim and `UNIQUE (memo_no, business_date)` is the second line of defence. `memo_serial` is an optional server series so Apsis continuity can be switched on later; imported memos keep their original number (D-35, MUST-CONFIRM by 1a, Q5). A burned number (a sale abandoned after the number was drawn) is explained by an activity row; an unexplained gap is a nightly `memo_seq_gap` (DQ-45, G-field-14).
- A zero-sale memo has `line_count = 0` and consumes a number (D-36). Negative net is allowed when the SR enters QC on a zero sale (`cfg.memo.allow_negative_net`, default true): `due_mtk` may be negative only when `net_mtk` is negative, stored as a credit (D-18, MUST-CONFIRM by 2a).
- `committed_at` is the second confirm dialog ("আপনি কি নিশ্চিত?"), before and independent of printing; `printed_at` stays NULL when the SR declines the print and the memo is reprinted from the Memo screen (D-77). Print events are rows in `print_event`; `print_count` and `printed_at` on the memo are server enrichment from them.
- `outstanding_before_mtk` and `printed_total_due_mtk` store what the paper said so a disputed balance can be adjudicated (G-analyst-15); the server writes `server_balance_at_print_mtk` into dw and flags `due_snapshot_mismatch` when they differ by more than `cfg.credit.snapshot_tolerance_mtk` (default 0, NEW key for doc 19).
- Status machine (server enrichment only; the memo row itself never changes):

| From | To | Trigger | Effects |
| --- | --- | --- | --- |
| active | superseded | a new memo with `supersedes_client_uuid` lands (edit) | dues reversed by `memo_superseded_reversal`, loyalty earned on the old memo reversed, the new memo counts once in K-06 |
| active | void | a `memo_void` event | dues reversed by `void_reversal`, stock effect reversed, cancel slip printed, excluded from K-06 |
| any | (none) | edit after outlet QC, other day, outside the geofence, after Sales Submit, chain deeper than 3 | rejected `edit_not_allowed` (DQ-16, D-201: the lock is at outlet level once any QC was done there) |

### 5.5 Memo lines

```sql
-- M-07 memo_line v2 (D-16, D-17, D-18, D-350)
CREATE TABLE app.memo_line (
  id bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid uuid NOT NULL, business_date date NOT NULL,
  memo_id bigint NOT NULL, memo_client_uuid uuid NOT NULL, line_no smallint NOT NULL,
  sku_id bigint NOT NULL REFERENCES app.sku(id),
  line_kind text NOT NULL DEFAULT 'sale' CHECK (line_kind IN ('sale','drp_reward','promo_free','free_sample')),
  is_free boolean GENERATED ALWAYS AS (line_kind <> 'sale') STORED,
  qty_entered int NOT NULL CHECK (qty_entered > 0), unit_entered app.qty_unit NOT NULL,
  pack_factor int NOT NULL CHECK (pack_factor > 0),                 -- base units per entered unit at capture
  qty_base int GENERATED ALWAYS AS (qty_entered * pack_factor) STORED,
  price_type app.price_type NOT NULL, price_valid_from date NOT NULL,
  base_price_mtk bigint NOT NULL CHECK (base_price_mtk >= 0), price_per_qty smallint NOT NULL DEFAULT 1 CHECK (price_per_qty > 0),
  gross_mtk bigint NOT NULL,                                        -- div_half_up(qty_base * base_price_mtk, price_per_qty)
  discount_mtk bigint NOT NULL DEFAULT 0 CHECK (discount_mtk >= 0),
  net_mtk bigint GENERATED ALWAYS AS (gross_mtk - discount_mtk) STORED,
  offer_id bigint REFERENCES app.offer(id),
  list_price_mtk bigint,                                            -- server enrichment: expected base price (DQ-13)
  flags text[] NOT NULL DEFAULT '{}',
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (memo_id, line_no, business_date),
  CONSTRAINT memo_line_gross_chk CHECK (gross_mtk = app.div_half_up(qty_entered::bigint * pack_factor * base_price_mtk, price_per_qty))
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo_line (memo_id, business_date);  CREATE INDEX ON app.memo_line (sku_id, business_date);
```

- `line_kind`: `sale`; `drp_reward` (valued at list and deducted by `drp_discount_mtk`, so it enters volume and stock); `promo_free`; `free_sample` (price 0). `is_free` is generated. The sum of the gross of free lines equals the memo's slide plus free-goods value (DQ-55).
- `pack_factor` is a copy of the SKU's factor at capture; the server flags a mismatch (`pack_factor_mismatch`, DQ-12) and stores the device's value. The CHECK recomputes the line from the stored price, so a line that does not equal `div_half_up(qty x base_price / price_per_qty)` cannot be inserted (D-117: reject on arithmetic, flag on price).
- No zero-quantity lines exist: a zero-volume line in the import (8.7 percent of the sample rows) is not a sale and is not loaded (D-249).
- A memo supports at least 60 lines (`cfg.sale.max_lines_per_memo`, D-246); the July average is 1.95 lines per outlet-day with a maximum of 40.

### 5.6 Offers, QC and slide collection

```sql
-- M-08 memo_offer: every discount, slide and free-goods component as (sku, qty, value, kind) (D-18, Q-UI-01)
CREATE TABLE app.memo_offer (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  memo_id bigint NOT NULL, memo_client_uuid uuid NOT NULL, offer_id bigint REFERENCES app.offer(id), offer_version_id bigint REFERENCES app.offer_version(id),   -- the typed form of memo.offer_version_set (D-501)
  kind text NOT NULL CHECK (kind IN ('offer_discount','drp_discount','free_goods','free_sample')),
  sku_id bigint REFERENCES app.sku(id), qty_base int, value_mtk bigint NOT NULL DEFAULT 0 CHECK (value_mtk >= 0),
  basis jsonb,                                                      -- what triggered it, e.g. {"empties_qty_base":100}; sanctioned exception J-1 (s8.10): the typed trigger quantity is `qty_base` and `value_mtk`
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.memo_offer (memo_id, business_date);

-- M-08 / M-102 QC: one entry per visit and SKU, fault lines by type, money settlement (D-34, G-man-002, G-man-003)
CREATE TABLE app.qc_entry (
  id bigint GENERATED ALWAYS AS IDENTITY,
  /*@PROV*/,
  visit_id bigint NOT NULL, visit_client_uuid uuid NOT NULL, memo_id bigint, memo_client_uuid uuid,
  sku_id bigint NOT NULL REFERENCES app.sku(id), user_id bigint NOT NULL REFERENCES app.app_user(id),
  production_fault_qty_base int NOT NULL DEFAULT 0 CHECK (production_fault_qty_base >= 0),     -- derived: sum of MFC lines
  transport_fault_qty_base  int NOT NULL DEFAULT 0 CHECK (transport_fault_qty_base >= 0),      -- derived: sum of MKT lines
  settlement_mtk bigint NOT NULL DEFAULT 0 CHECK (settlement_mtk >= 0),   -- defect sticks x price at capture (inferred from one example)
  max_qc_mtk bigint,                                                      -- per-SKU cap shown on screen; basis unknown (594.50 is not 10 x 8.00)
  after_memo_commit boolean NOT NULL DEFAULT false,                       -- QC saved after the memo was committed: reported apart, never edits the memo
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (visit_id, sku_id, business_date)
) PARTITION BY RANGE (business_date);
CREATE TABLE app.qc_entry_line (
  id bigint GENERATED ALWAYS AS IDENTITY, client_uuid uuid NOT NULL, business_date date NOT NULL,
  qc_entry_id bigint NOT NULL, qc_entry_client_uuid uuid NOT NULL,
  fault_type_code text NOT NULL REFERENCES cfg.qc_fault_type(code), qty_base int NOT NULL CHECK (qty_base >= 0),
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date), UNIQUE (qc_entry_id, fault_type_code, business_date)
) PARTITION BY RANGE (business_date);
```

- `memo_offer` stores every component as (sku, qty, value, kind), so any reading of "SL Match 3 / 0.00" (Q-UI-01) fits: kinds `offer_discount`, `drp_discount`, `free_goods`, `free_sample`. `memo.offer_discount_mtk` and `drp_discount_mtk` equal the sums of the matching kinds (DQ-14).
- QC is a money deduction, not only quantities: `settlement_mtk` = defect sticks x the price at capture (inferred from one example, 10 x 8.00 = 80.00) and `max_qc_mtk` is the per-SKU taka cap the screen shows (594.50 for MaxR-10S, basis unknown, not 10 x 8.00, so DQ-18 does not use it as a hard rule). `production_fault_qty_base` and `transport_fault_qty_base` are the sums of the MFC and MKT lines.
- QC can be saved after the memo is committed (QC and Print are independent buttons). The memo is immutable, so a QC entry saved after commit is stored with `after_memo_commit = true` and reported as `qc_late_settlement_mtk` in dw; whether the printed memo is then reissued is unknown; confirm with the business (D-372, gap G-16-06, MQ-03).
- `drp_collection` is the header per visit (kind, offer, reward SKU, quantity and value of the reward pack), `drp_collection_line` one row per empties SKU with the unit entered ("খালি প্যাকেট": packs or sticks is unconfirmed, MQ-06). Reward value is the list price of the reward, 10 x 8.00 = 80.00 in the fixture.

### 5.7 Dues

```sql
-- M-12 / M-116 due collection: whole-memo settlement is parity, partial is a default-off enhancement (D-37)
CREATE TABLE app.due_collection (
  id bigint GENERATED ALWAYS AS IDENTITY,
  /*@PROV*/, /*@SIG*/
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), user_id bigint NOT NULL REFERENCES app.app_user(id),
  collector_id bigint REFERENCES app.app_user(id), route_id bigint REFERENCES app.route(id),
  against_memo_id bigint, against_memo_client_uuid uuid, against_memo_business_date date,
  amount_mtk bigint NOT NULL CHECK (amount_mtk > 0), is_full_settlement boolean NOT NULL DEFAULT true,
  payment_mode text NOT NULL DEFAULT 'cash', outstanding_before_mtk bigint,
  collected_at timestamptz NOT NULL, fix_id bigint,
  parallel boolean NOT NULL DEFAULT false,                          -- captured in parallel_run_mode: excluded from balances (D-152)
  PRIMARY KEY (id, business_date), UNIQUE (client_uuid, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.due_collection (outlet_id, business_date);  CREATE INDEX ON app.due_collection (against_memo_id);
CREATE TABLE app.due_adjustment (                                   -- finance write-off, correction, merge transfer (G-feat-45)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), memo_id bigint, business_date date NOT NULL DEFAULT app.dhaka_date(now()),
  kind text NOT NULL CHECK (kind IN ('write_off','correction','transfer_out','transfer_in','opening')),
  amount_mtk bigint NOT NULL CHECK (amount_mtk <> 0), reason text NOT NULL, requested_by bigint NOT NULL, approved_by bigint,
  created_at timestamptz NOT NULL DEFAULT now(), CHECK (approved_by IS NULL OR approved_by <> requested_by));
CREATE TABLE app.due_dispute (                                       -- retailer disputes a balance at the shop (G-field-09)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, business_date date NOT NULL,
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), user_id bigint NOT NULL REFERENCES app.app_user(id),
  claimed_paid_mtk bigint, claimed_date date, claimed_collector_user_id bigint, note text, fix_id bigint,
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), sync_batch_id bigint, status text NOT NULL DEFAULT 'open');
```

- Parity: mark-as-paid settles the whole memo, no amount field, confirm dialog, the due tag disappears (SR p44 to p45). A `due_collection` row is still written for every action (`amount_mtk` = the remaining due, `is_full_settlement` true) so the action is idempotent and auditable; partial later collection is behind `cfg.credit.allow_partial_collection` (default off, IMPROVEMENT) (D-37, D-161).
- Credit at sale time: the collected amount must be strictly below the payable total (the manual's one visible rule); due = payable - collected, exact to the paisa on every screen (the label that shows "61" for 61.50 is a defect, D-213); zero collected is allowed behind `cfg.credit.allow_zero_payment` (MUST-CONFIRM by 2a).
- An AMO's due label and the deposit-time dues count cover the AMO's own credit memos only; SR credit stays with the SR (D-37, MUST-CONFIRM by 3a, MQ-17). `collector_id` makes AMO-collects-SR-dues possible without a schema change.
- The ledger that answers "what was owed when" is derived in dw (`fact_due_ledger`, s8.4) from memos, collections, adjustments and `opening_balance`; allocation of a collection to memos is FIFO for reporting regardless of the retailer's choice, and opening balances land in an "opening" bucket aged from `age_basis_date` (D-359, G-feat-45).
- Collections captured in `parallel_run_mode` carry `parallel = true` and are excluded from balances (D-152). `due_dispute` is the field capture of a retailer's claim (claimed paid amount, date, collector) that becomes an AMO task and a finance queue item (G-field-09). `due_adjustment` is finance only: write-off, correction, merge transfer; the approver differs from the requester by CHECK.

### 5.8 Stock, attendance, corrections and exceptions

| Table | Key and constraints | Columns beyond @PROV | Notes |
| --- | --- | --- | --- |
| `stock_movement` | `(id, business_date)`, `UNIQUE (client_uuid, business_date)` | user_id, zone_id, route_id (NULL when an SR covers several), kind (issue, return, adjustment, damaged, short, qc_return), sku_id, qty_entered, unit_entered, pack_factor, qty_base, printed_at, note, requested_qty_base, counted_qty_base, variance_reason_code, confirmed_by, confirmation_client_uuid | an event, not a sum: a second load the same day adds, a retry replays (seed idempotency hole); "Issue" on the SR home is the day's issue events, "Current stock" is computed locally (D-56); distribution-house confirmation columns serve the AMO proxy (D-304 of DECISIONS, F-AMO-042) |
| `attendance_event` | `(id, business_date)`, `UNIQUE (client_uuid, business_date)` | user_id, kind (check_in, check_out), at, fix_id | first event per user, day and kind wins (DQ-20); no reverse-geocoded address is stored in `app` (display only on the device, UI-SR-11); reports show the coordinates and a `locality_hint` that the worker derives in `dw.fact_attendance` from the stored fix (nearest cluster name within 500 m of the fix, else the zone name), with no external geocoder, no quota and no cost; a stored address from a provider is MUST-CONFIRM by 4b (D-538, G-qa-68) |
| `attendance` (derived) | PK `(user_id, business_date)` | check_in/out event id, time and fix, flags | first check-in, last check-out; check-out is accepted from `cfg.day.checkout_earliest_time` (17:00 inclusive, I-02) on corrected time; a check-out stores its own fix |
| `visit_skip` | `(id, business_date)` | user_id, outlet_id, route_id, reason_code (visit_outcome list), captured and trusted time | an outlet marked not reached from the list: no fix, no geo gate, not a visit (D-38); three consecutive `closed` outcomes raise a task to the AMO |
| `memo_void` | `(id, business_date)` | memo_client_uuid, reason_code, note, fix_id, retailer_ack, slip_printed | same guards as an edit; effects in s5.4 (D-86) |
| `print_event` | `(id, business_date)` | memo_client_uuid, kind (memo, reprint, void_slip, due_receipt, stock_slip, day_summary), printer_id, template_version, user_confirmed | the "ছাপা ঠিক আছে?" answer; a "no" marks the job failed and the next print carries no duplicate marker (G-field-20) |
| `attribution_event` | server key; `CHECK (from <> to)` | route_id, business_date, from_user_id, to_user_id, reason, requested_by, approved_by | admin re-attribution of a route-day for a wrong-user capture on a shared phone; applied at aggregation, app rows untouched (G-field-05, D-85) |
| `cash_handover` | `UNIQUE (client_uuid)` | user_id, declared_mtk, counted_mtk, variance_mtk, counted_by, fix_id | F-SR-052; fields are an ASSUMPTION until Q41 is answered (G-field-01) |

### 5.9 Surveys and content

`survey_response` stores one row per question and visit with a TYPED answer and the photo as a media reference: `answer_type` (`bool`, `num`, `option`, `text`, `photo_only`), `answer_bool boolean`, `answer_num numeric(14,3)`, `answer_option_code text` (a `cfg.survey_option` code) and `answer_text text`, with `CHECK` that exactly the column of `answer_type` is set; there is no `answer jsonb` (D-501, G-qa-26); `UNIQUE (visit_id, question_id, survey_version, business_date)` makes a retry harmless. The earn rule "POSM Q1.1 photo = +50 points" is applied by the worker from `survey_question.points_reward` into `loyalty_ledger` with `source_type = 'survey_response'` and `source_id` = the response's client_uuid, so a replay cannot credit twice (D-41). `content_view` (AV and KV watch time) is telemetry, partitioned, three months hot.

### 5.10 Supervisor captures

| Table | Key | Columns beyond the baseline | Rules |
| --- | --- | --- | --- |
| `task` | `client_uuid` unique | route_id, type_code, resolved_by, resolution_note, business_date, source, visit_plan_outlet_id, device and batch fields | status ongoing (চলমান) or completed (সম্পন্ন); resolved tasks stay on the SR list; the assignee is the outlet's SR active on the route on the due date, and a route with "SR Not Set" cannot be assigned (G-man-082) |
| `task_event` | `client_uuid` unique | task_client_uuid, event (assigned, resolved, reopened, cancelled), actor, note, fix | resolve is offline with no confirm (G-man-049) |
| `distribution_check` and `_line` | header `client_uuid`; `UNIQUE (check_id, brand_id)` | posm_present tri-state (NULL, true, false); per brand present and oos | `CHECK (NOT oos OR present)`; 15 brands from the product-tree flag in the manual's order (G-man-050) |
| `call_assessment` and `call_assessment_answer` | header `client_uuid`; `UNIQUE (assessment_id, criterion_id)` | rubric_id, visit and plan links, total_score, max_score, delegate_task; per criterion score 1 to 5, answer text or bool | kinds joint_call and retailer_questionnaire; the assessed SR is the route's active assignee on the date (acting user for cover), read-only on the form (G-man-052); answers flattened so BI never parses JSON |
| `price_compliance_check` | `client_uuid` unique | sku, observed and listed price, is_compliant, note | fields are an ASSUMPTION: the AMO reconciliation counts a "price compliance" type with no screen in any manual (G-feat-01, gap G-16-14) |
| `leave_application` and `leave_event` | `client_uuid` unique | leave_type_code, from_date, to_date, `days int >= 1`, reason, status, approver_role (default dmo), acting_user_id | `days` is typed and authoritative; `to_date = from_date + days - 1` for new rows; imported rows are never recomputed or rejected; no 30-day cap (D-176, D-197); badge text "<approver> approval pending" |
| `visit_plan` and `visit_plan_outlet` | `UNIQUE (planner_id, plan_date, route_id)`; `UNIQUE (plan_id, outlet_id)` | status pending or completed, completed_at, assessment link | a repeat Set Plan adds outlets (union); an outlet completes when its Visit Query is submitted; no cap on outlets per plan (D-197) |
| `feedback` | `client_uuid` unique | category_code, title, description, media_id, status, handled_by | only "Suggestion" is evidenced; image through the media queue |
| `outlet_suggestion` | PK `(outlet_id, sku_id, business_date)` | suggested_qty_base, method, inputs | the geo-triggered volume hook, delivered empty and off (`cfg.sale.suggested_qty_enabled` false, D-300, Q6) |
| `content_item`, `outlet_content_assignment`, `tutorial_asset`, `app_release`, `support_upload` | identity or `client_uuid` | scope, validity, order AV, KV, survey; release flavour sr, amo or tso, SHA-256, wave percentage, blocked flag; support upload with app version and last sync | `app_release` is the updater's registry (D-79, D-10) |

### 5.11 Programme capture

Redemption, loyalty ledger, gift assignment and gift photo are in s10; they follow the same @PROV and idempotency rules.

### 5.12 Day control and web back-office data

```sql
-- M-34 / M-113 final submit: once per zone per day by primary key, route snapshot, attempts, reopen (D-55, D-262, G-man-070)
ALTER TABLE app.final_submit
  ADD COLUMN client_uuid uuid UNIQUE, ADD COLUMN kind text NOT NULL DEFAULT 'manual' CHECK (kind IN ('manual','delegated','auto')),
  ADD COLUMN route_count int, ADD COLUMN routes_set int, ADD COLUMN memo_count_at_submit int, ADD COLUMN net_mtk_at_submit bigint,
  ADD COLUMN reopened_by bigint REFERENCES app.app_user(id), ADD COLUMN reopened_at timestamptz, ADD COLUMN reopen_reason text, ADD COLUMN resubmitted_at timestamptz,
  ADD COLUMN device_id bigint, ADD COLUMN received_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE app.final_submit_route (
  zone_id bigint NOT NULL, business_date date NOT NULL, route_id bigint NOT NULL REFERENCES app.route(id),
  ff_user_id bigint REFERENCES app.app_user(id),                    -- NULL = 'SR Not Set' (a normal state of an AMO route)
  sr_name_snapshot text, had_data boolean NOT NULL, memo_count int, net_mtk bigint, state_at_submit app.day_state,
  PRIMARY KEY (zone_id, business_date, route_id), FOREIGN KEY (zone_id, business_date) REFERENCES app.final_submit(zone_id, business_date));
CREATE TABLE app.final_submit_attempt (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE, zone_id bigint NOT NULL REFERENCES app.zone(id), business_date date NOT NULL,
  user_id bigint NOT NULL REFERENCES app.app_user(id), outcome text NOT NULL CHECK (outcome IN ('accepted','already_submitted','rejected')), at timestamptz NOT NULL DEFAULT now());
```

| Table | Key and constraints | Rules |
| --- | --- | --- |
| `web_entry_route_day` | `UNIQUE (route_id, business_date)`; `CHECK (successful_calls <= target_outlets_snapshot)` | one entry per route-day, a re-save replaces and is audited; status active or void; a web submission carries a client uuid so a double click cannot double it (D-40) |
| `web_entry_line`, `web_entry_line_class` | `UNIQUE (entry_id, sku_id)`; class rows per sub-channel | issue, return, sale (inferred = issue - return, not a generated column until confirmed), memo count; the class columns sum to sale |
| `web_entry_outlet_sku` | PK `(outlet_id, sku_id, business_date)` | Astha Web Entry (outlet x SKU, sticks); feeds Astha quarter achievement and `agg_daily_outlet_brand` as source `web_entry` |
| `qc_summary_entry` | `UNIQUE NULLS NOT DISTINCT (kind, zone_id, route_id, business_date, sku_id, fault_type_code)`; `CHECK ((kind = 'warehouse') = (route_id IS NULL))` | web Market QC (route) and Warehouse QC (zone only); a separate source from app QC, never added to it (D-40, G-man-089) |
| `data_void` | server key; scope web_entry, app_memos or all | "Delete Section Data" is an audited void with a reason and a summary, allowed only before Final Submit, default scope web entry rows; app memos need a higher role; every voided `client_uuid` the server has seen becomes a registry tombstone, AND the void writes a route-day BARRIER `app.route_day_void_barrier` (below) so a late upload of a uuid the server has NOT seen is rejected `voided_by_admin` too (D-22, D-182, D-577) |
| `entry_unlock_grant` | server key | replaces "call support, who edits the DB": scope zone or route, date range, expiry, reason, grantor (D-97) |

Supervised paper-memo backfill (D-543, G-qa-74): when a phone is dead or lost with unsynced rows, the printed paper memo is the evidence. `app.paper_backfill` (`id`, `printed_memo_no`, `outlet_id`, `route_id`, `business_date`, `entered_by`, `approved_by`, `reason_code`, `device_id` of the dead phone, `memo_uuid`, `created_at`, `CHECK (approved_by <> entered_by)`) is the control row; the memo it writes is an ordinary `app.memo` with `entry_source = 'manual'`, its lines, its `memo_offer` components and the due it creates, so retailer dues, outlet-level STD and loyalty are restored at memo level (the aggregate Web Entry of B1 cannot do this). The memo uuid is a deterministic UUIDv5 of `(namespace 'aron-paper', printed_memo_no)`, so a re-keyed or double-keyed memo is a registry replay and creates nothing twice. Four-eyes: support enters, the zone TSO approves; `memo_no` is the printed number, the price and offer arithmetic is recomputed by the server from the lines and must equal the printed total within DQ-14, else the entry is held. If the original phone later uploads the same `memo_no` the server keeps the device row, marks the manual row `superseded_by_device`, raises DQ-70 `paper_then_device` and the dw effective-source view prefers native over manual (priority native, migration_memo, manual, web_entry, migration_aggregate).

Web rows and app rows for the same route-day are mutually exclusive by default: the effective-source view uses the app rows, keeps the web rows in their own facts and raises `web_app_overlap`; they are never added (D-40, D-362, gap G-16-11).

Proved by: T-0-01, T-1-01, T-1-06, T-2-01, T-2-02, T-2-03, T-2-05, T-2-06, T-2-07, T-2-09, T-3-05.

## 6 Sync, integrity and audit tables

### 6.1 The ingest registry and the three idempotency layers

D-21: layer 1 is the row's `client_uuid` in a global registry with a payload hash; layer 2 is `batch_uuid` replay with the stored response (s6.3); layer 3 is a content fingerprint that catches a replay with regenerated uuids (A-40, G-fraud-05). UUID v7 is not adopted: a time-ordered key would cure the hot index of G-sre-26 but is a deviation from CLAUDE.md's v4 convention; the cure here is the partitioning below (D-352).

```sql
-- M-30 ingest registry: the single global uniqueness point (D-21, D-352, D-353)
CREATE TABLE app.ingest_registry (
  client_uuid    uuid NOT NULL,
  record_type    text NOT NULL,                       -- the sync record type = the table name (doc 17 owns the enum)
  server_id      bigint, business_date date,
  payload_sha256 bytea NOT NULL,                      -- same uuid, different hash = conflict (DQ-01)
  content_fp     bytea,                               -- natural-identity fingerprint, header record types only (s6.1)
  user_id bigint NOT NULL, device_id bigint, first_batch_id bigint NOT NULL,
  first_seen_at  timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(), seen_count int NOT NULL DEFAULT 1,
  state          app.sync_state NOT NULL,             -- accepted | rejected | conflict | parked | voided (tombstone, D-22)
  PRIMARY KEY (client_uuid)
) PARTITION BY HASH (client_uuid);
DO $$ BEGIN FOR i IN 0..63 LOOP
  EXECUTE format('CREATE TABLE app.ingest_registry_h%s PARTITION OF app.ingest_registry FOR VALUES WITH (MODULUS 64, REMAINDER %s)', lpad(i::text, 2, '0'), i);
END LOOP; END $$;
CREATE INDEX ON app.ingest_registry (user_id, record_type, content_fp) WHERE content_fp IS NOT NULL;   -- DQ-41 within 48 h
CREATE INDEX ON app.ingest_registry (last_seen_at) WHERE state <> 'voided';                            -- pruning at cfg.retention.ingest_registry_days
```

```sql
-- the registry step of ingest, inside the batch savepoint or the bisection half that holds the record (D-21, D-65, D-521): the verdict decides what the caller does next
CREATE FUNCTION app.register_record(p_uuid uuid, p_type text, p_hash bytea, p_user bigint, p_device bigint, p_batch bigint,
                                    p_date date, p_fp bytea DEFAULT NULL) RETURNS text LANGUAGE plpgsql AS $$
DECLARE r app.ingest_registry%ROWTYPE; v_state app.sync_state := 'accepted';
BEGIN
  IF p_fp IS NOT NULL AND EXISTS (SELECT 1 FROM app.ingest_registry x                       -- regenerated uuids, same content (DQ-41)
        WHERE x.user_id = p_user AND x.record_type = p_type AND x.content_fp = p_fp AND x.client_uuid <> p_uuid
          AND x.state = 'accepted' AND x.first_seen_at > now() - interval '48 hours') THEN
    v_state := 'conflict';
  END IF;
  INSERT INTO app.ingest_registry(client_uuid, record_type, business_date, payload_sha256, content_fp, user_id, device_id, first_batch_id, state)
  VALUES (p_uuid, p_type, p_date, p_hash, p_fp, p_user, p_device, p_batch, v_state)
  ON CONFLICT (client_uuid) DO NOTHING;
  IF FOUND THEN
    RETURN CASE v_state WHEN 'accepted' THEN 'new' ELSE 'content_duplicate' END;
  END IF;
  UPDATE app.ingest_registry SET seen_count = seen_count + 1, last_seen_at = now() WHERE client_uuid = p_uuid RETURNING * INTO r;
  IF r.state = 'voided' THEN RETURN 'voided'; END IF;                                          -- tombstone: rejected as voided_by_admin (D-22)
  IF r.state = 'conflict' THEN RETURN 'content_duplicate'; END IF;                              -- the uuid that was refused stays refused
  IF r.payload_sha256 <> p_hash THEN RETURN 'conflict'; END IF;                                -- same uuid, other payload: first wins, second goes to sync_conflict
  RETURN 'replay';                                                                              -- counted as accepted, nothing written
END $$;
```

- One global primary key on `client_uuid`, hash-partitioned 64 ways so the random-key index of 3.0 M inserts a day (2,600 a second at the evening peak) is 64 small indexes that stay in memory instead of one large one (G-sre-26). A partitioned table cannot hold a global unique index without the partition key, and a retry may compute a different business date after a clock correction, which is why the registry is keyed on the uuid alone.
- Pruning: rows older than `cfg.retention.ingest_registry_days` (default 45, NEW key for doc 19) are deleted in 1,000-row chunks nightly, except `voided` tombstones, which are kept. 45 days exceeds the 7-day business-date window (DQ-09), the 24-hour DR re-send window (D-63) and the clock tolerance, so a replay older than the registry is already rejected as out of window. At 3.0 M rows a day the registry holds about 135 M rows, about 2 M per partition (ASSUMPTION: 90 bytes per row with index).
- Ingest order per record: parent resolution, `app.register_record()`, then `INSERT ... ON CONFLICT (client_uuid, business_date) DO NOTHING` into the target table. The fast path runs the whole batch inside ONE savepoint; when any record raises, the batch is bisected (the failing batch is split in halves and each half retried in its own savepoint, depth at most 6, so a lone poison record is isolated after at most 6 rounds) and a savepoint is never opened per record, so a transaction never holds more than `cfg.sync.max_savepoints_per_tx` (default 60, bound 8 to 60) subtransactions and the 64-entry subtransaction cache of a backend is never overflowed (a PGPROC overflow forces every concurrent snapshot onto pg_subtrans). A failed record still rolls its registry row back with its savepoint (D-65, D-411, D-521, G-qa-45). This supersedes the per-record-savepoint wording of the first draft; doc 17 s4.2 and doc 18 s4.2 carry the same rule.
- Verdicts: `new` (insert), `replay` (counted as accepted, nothing written), `conflict` (same uuid, different payload: the first payload wins and the second goes to `sync_conflict`), `voided` (rejected `voided_by_admin`), `content_duplicate` (refused; the device treats it as acknowledged-as-duplicate, doc 17 wire contract; the worker raises FS-24).
- Route-day void barrier (D-577, G-qa-113). A tombstone covers only uuids already in the registry. A TSO who voids a route-day (scope `include_app_memos` or `all`) while the SR's phone still holds UNSENT rows for that day would see those rows return as `new`, be accepted and be aggregated again. `data_void` therefore also inserts `app.route_day_void_barrier(route_id, business_date, voided_at, scope, data_void_id, PRIMARY KEY (route_id, business_date, voided_at))`, and ingest calls `app.route_day_voided(route_id, business_date, captured_at_trusted)` BEFORE `register_record()`: a record whose route and business date match a barrier and whose `captured_at_trusted` is EARLIER than `voided_at` returns the verdict `voided_barrier`, rejected `voided_by_admin` (DQ-72), whether or not its uuid is known. A record captured AFTER `voided_at` is legitimate (the SR kept selling) and passes. The void confirmation shows the route's pending-row count (the last `X-Pending-Rows` or the SH-24 entry). The barrier is never pruned with the registry; it is audit data (7 years, doc 21 s8). Gate T-4-166.

| Content fingerprint (header record types only, SHA-256 of the canonical string) | Fields |
| --- | --- |
| visit | user_id, outlet_id, opened_at_trusted to the second |
| memo | user_id, outlet_id, committed_at to the second, net_mtk, line_count |
| due_collection | user_id, outlet_id, collected_at to the second, amount_mtk |
| stock_movement | user_id, sku_id, kind, captured_at to the second, qty_base |
| attendance_event | user_id, kind, business_date |
| redemption | user_id, outlet_id, captured_at to the second, points_spent |
| outlet_change_request | user_id, type, hash of proposed, captured_at to the second |
| task, leave_application, feedback, visit_plan | user_id, type-specific key, captured_at to the second |

DQ-41 differs from the fraud critic's FLAG policy on purpose: a flagged duplicate would still double the sale, so the second record is refused (REJECT, non-retryable) and the signal FS-24 is raised on the first (D-353).

### 6.2 Idempotency key of every device-originated table

"Key" is what makes a retried, duplicated or partial upload harmless. A child's parent link is the parent's `client_uuid`; if the parent has not landed the child is parked (DQ-03).

| Table (record type) | Idempotency key | Parent link and natural uniqueness |
| --- | --- | --- |
| visit | `client_uuid` | fingerprint (user, outlet, opened_at) |
| visit_skip | `client_uuid` | none |
| memo | `client_uuid` | `visit_client_uuid`; `UNIQUE (memo_no, business_date)`; fingerprint |
| memo_line | `client_uuid` | `memo_client_uuid`; `UNIQUE (memo_id, line_no)` |
| memo_offer | `client_uuid` | `memo_client_uuid` |
| memo_void | `client_uuid` | `memo_client_uuid`; at most one void per memo |
| print_event | `client_uuid` | `memo_client_uuid` |
| qc_entry | `client_uuid` | `visit_client_uuid`; `UNIQUE (visit_id, sku_id)` |
| qc_entry_line | `client_uuid` | `qc_entry_client_uuid`; `UNIQUE (qc_entry_id, fault_type_code)` |
| drp_collection, drp_collection_line | `client_uuid` | visit, then collection |
| survey_response | `client_uuid` | `visit_client_uuid`; `UNIQUE (visit_id, question_id, survey_version)` |
| content_view | `client_uuid` | visit (telemetry; a duplicate is tolerated) |
| due_collection | `client_uuid` | `against_memo_client_uuid`; fingerprint |
| due_dispute, cash_handover | `client_uuid` | none |
| stock_movement | `client_uuid` | none; fingerprint (the same user, SKU, kind and quantity again within `cfg.stock.resave_guard_window_min` is refused as a double tap, D-580; otherwise a second load is a second event, not an overwrite; a "correct total" is a signed `adjustment` event, never an overwrite) |
| attendance_event | `client_uuid` | first event per (user, date, kind) wins (DQ-20) |
| geo_fix | `client_uuid` | `ref_client_uuid` (the record the fix belongs to) |
| outlet_change_request, outlet_change_event | `client_uuid` (web rows: server uuid) | request; event to request |
| outlet_photo, media_object | `client_uuid` | change request or visit; `content_sha256` dedupes bytes |
| redemption, redemption_line | `client_uuid` | `redemption_client_uuid`; `UNIQUE (redemption_id, gift_id)` |
| gift_photo | `client_uuid` | `UNIQUE (gift_assignment_id)`: one photo per assignment |
| task, task_event | `client_uuid` | `task_client_uuid` |
| call_assessment (+ answers) | `client_uuid` | visit or plan outlet; answers `UNIQUE (assessment_id, criterion_id)` |
| distribution_check (+ lines) | `client_uuid` | visit; lines `UNIQUE (check_id, brand_id)` |
| price_compliance_check | `client_uuid` | visit |
| visit_plan, visit_plan_outlet | `client_uuid` | `UNIQUE (planner_id, plan_date, route_id)`; `UNIQUE (plan_id, outlet_id)` |
| leave_application, feedback, day_exception, support_upload | `client_uuid` | none |
| day_open, day_submit (route_day) | PK `(route_id, business_date)`; `submit_client_uuid` and `submit_seq` | first event of a cycle wins; a retried submit returns the same state; after a submit void (D-539) the next submit carries a new uuid for cycle `submit_seq + 1` and a retry of the voided uuid returns `voided` |
| final_submit | PK `(zone_id, business_date)`; request `client_uuid` in `final_submit_attempt` | a retry returns the same success, a second attempt by another uuid is `already_submitted` (D-262) |
| cover assignment | request uuid | `route_assignment` exclusion on (route, user, range) |
| device telemetry (`device_day`) | PK `(device_id, business_date)` | counters merge by upsert |
| sync_batch | `(device_id, batch_uuid)` in `sync_batch_key` | a repeat replays the stored response; another row set under the same uuid is 409 |
| activity_log | `client_uuid` | telemetry, duplicates tolerated |
| web entries (`web_entry_route_day`, `web_entry_outlet_sku`, `qc_summary_entry`), `target_set`, `target_revision` | browser-supplied `client_uuid` | route-day, outlet-sku-day or unique tuple; stops a double click doubling a number |
| outlet wholesale marking | `batch_uuid` | one audit row per outlet |

Server-derived rows need no device key: `loyalty_ledger` is idempotent on `(source_type, source_id)`, dw rows on their primary keys, `route_day` on its primary key.

### 6.3 Batches, quarantine and conflicts

```sql
-- M-30 quarantine and conflicts: rejected rows never vanish (D-65, P9)
CREATE TABLE app.sync_rejected (
  id bigint GENERATED ALWAYS AS IDENTITY, business_date date NOT NULL,
  sync_batch_id bigint NOT NULL, user_id bigint NOT NULL, device_id bigint,
  record_type text NOT NULL, client_uuid uuid NOT NULL, payload jsonb NOT NULL,
  reason_code text NOT NULL, reason_detail text, dq_rule text,
  retryable boolean NOT NULL DEFAULT false,
  status text NOT NULL DEFAULT 'parked' CHECK (status IN ('parked','retried','accepted','discarded','fixed_manually')),
  retry_count smallint NOT NULL DEFAULT 0, next_retry_at timestamptz,
  received_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz, resolved_by bigint, resolution_note text,
  PRIMARY KEY (id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.sync_rejected (status, next_retry_at);  CREATE INDEX ON app.sync_rejected (client_uuid);
CREATE TABLE app.sync_conflict (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, client_uuid uuid NOT NULL, record_type text NOT NULL,
  reason text NOT NULL DEFAULT 'payload_changed' CHECK (reason IN ('payload_changed','content_duplicate')),
  first_payload_sha256 bytea NOT NULL, second_payload jsonb NOT NULL, second_batch_id bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(), reviewed_by bigint, reviewed_at timestamptz, decision text);
```

```sql
-- M-31 sync_batch v2: counts live forever, the replayable response lives 48 h (D-62)
DROP TABLE app.sync_batch, app.route_log;
CREATE TABLE app.sync_batch (
  id bigint GENERATED ALWAYS AS IDENTITY, uploaded_on date NOT NULL DEFAULT app.dhaka_date(now()),
  batch_uuid uuid NOT NULL, device_id bigint, user_id bigint NOT NULL, uploaded_at timestamptz NOT NULL DEFAULT now(),
  business_date date, app_version text, network_type text, battery_pct smallint, trigger text,
  attempt smallint NOT NULL DEFAULT 1, payload_bytes int, compressed_bytes int,
  device_counts jsonb, accepted_counts jsonb, server_totals jsonb,
  rejected_count int NOT NULL DEFAULT 0, conflict_count int NOT NULL DEFAULT 0, replayed_count int NOT NULL DEFAULT 0, parked_count int NOT NULL DEFAULT 0,
  processing_ms int, oldest_captured_at timestamptz, newest_captured_at timestamptz,           -- sync latency = uploaded_at - captured_at
  response jsonb,                                                                              -- stored in the ingest transaction; NULLed by a job after 48 h
  PRIMARY KEY (id, uploaded_on)) PARTITION BY RANGE (uploaded_on);
CREATE INDEX ON app.sync_batch (user_id, uploaded_at);  CREATE INDEX ON app.sync_batch (device_id, uploaded_at);
CREATE TABLE app.sync_batch_key (                                    -- (device_id, batch_uuid) is unique even across a midnight retry (D-62)
  device_id bigint NOT NULL, batch_uuid uuid NOT NULL, batch_id bigint NOT NULL, uploaded_on date NOT NULL,
  rowset_sha256 bytea NOT NULL,                                       -- same batch_uuid with another row set returns 409
  created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (device_id, batch_uuid));
```

| Table | Key | Columns | Notes |
| --- | --- | --- | --- |
| `sync_batch` (above) | `(id, uploaded_on)` | counts per type, accepted, rejected, conflict, replayed, parked, `server_totals`, latency inputs, trigger, attempt, network type, battery, `response` | counts live for 13 months; `response` is NULLed after 48 hours by a job (`cfg.retention.sync_batch_response_h`, D-369): at about 1 KB a response and 240,000 trickle batches a day the replay payload would otherwise add about 240 MB a day |
| `sync_batch_key` (above) | `(device_id, batch_uuid)` | batch id, rowset hash | kept 7 days; keeps D-62 true across a retry after midnight |
| `bundle_download` | identity | user, device, `business_date` (Dhaka date of the request), `for_business_date`, `is_prefetch` (generated), full or delta, `not_modified`, bytes, duration, bundle and config versions | the login event source: a download counts only when its request falls on the route's business date; a pre-fetch of D+1 on the evening of D does not (D-30) |
| `bundle_snapshot` | `(user_id, for_business_date, kind full or delta_base)` | `version`, `etag`, `blob_path`, `size_gz`, `row_counts` jsonb, `scope_version`, `config_version`, `generated_at`, `valid_for_business_date` | one row per pre-generated snapshot (D-71, D-129); the 04:30 coverage check counts rows with `valid_for_business_date = D` (doc 18 s4.7) |
| `job_run` | `(job, business_date, chunk)` | `status`, `coverage`, `started_at`, `finished_at`, `attempt`, `error` | the gate row of every nightly job (D-418); a job starts only when its predecessor rows are complete |
| `media_object` | `client_uuid` | purpose, ref, `blob_path` (`photos/{business_date}/{device_uuid}/{client_uuid}.jpg`, never a SAS URL), SHA-256, perceptual hash, bytes, size, fix, state, retention class | the record syncs first and the photo follows; a missing photo never blocks a sale (D-75) |
| `reconcile_snapshot` | `(user_id, business_date, record_type)` | device count, server count, as_of, last batch | the Server column of the reconcile screen is the last `server_totals`, blank with a timestamp before the first sync (I-15) |

`sync_rejected` carries `reason_code`, `retryable`, the DQ rule and the payload; an admin can retry, fix-and-accept (the edit is recorded in `resolution_note` and the rules re-run) or discard (reason required). A parked row retries when its parent lands (a trigger on the registry insert) or every 10 minutes for 7 days, then becomes a REJECT.

### 6.4 Identity and device tables

Doc 21 owns the token model, the OTP model and device proof; this document owns the storage (G-data-16).

| Table | Key | Columns | Notes |
| --- | --- | --- | --- |
| `user_credential` | user | `password_hash` (Argon2id), algo, `must_change`, `temp_expires_at`, failed attempts, `locked_until`, `apsis_hash_algo` | `apsis_hash_algo` marks an imported hash to be verified then re-hashed at first login (D-119) |
| `password_history` | identity | user, hash, changed_by | the last 10 are kept for the web rule "not one of the last 10, not within 24 h" (docs/09) |
| `refresh_token` | `token_hash` unique | family_id, issued, expires, absolute expiry, rotated_at, revoked_at, scope_version | stored hashed, rotated on use with reuse detection (D-101) |
| `device` | `device_uuid` unique | model, manufacturer, OS, app and verified app version, ABI, RAM, printer, public key, hw_backed, trust_level, bound and unbound fields | one row per bound phone; max 3 users per device and 2 devices per user (D-66) |
| `device_otp` | identity | user, device_uuid, purpose (new_device, new_version), `code_enc` and `key_id`, expiry, attempts, status | server-created, TSO-readable, so encrypted and reversible, never a hash (D-103, C-09) |
| `device_integrity` | identity | device, user, business_date, mock-app, developer-options, rooted hint, Play Integrity verdict, `time_skew_s`, `clock_changed_count` | one row per login or bundle |
| `device_capability_snapshot` | identity | permissions JSON, location services, battery saver, free storage | explains "no GPS fix" days |

### 6.5 Audit, exports, security events and activity

| Table | Written by | Shape | Notes |
| --- | --- | --- | --- |
| `audit_log` | API, jobs, importer | `(id, at)` partitioned by month; actor, role, `via` (web, api, job, migration), entity, entity_id, action, before, after, diff, request id, ip, `prev_hash`, `row_hash` | append-only (REVOKE UPDATE and DELETE plus the trigger of s6.6, hash chain, daily WORM export, D-113); every master change and every PII read |
| `report_export_log` | web API | user, report code, filters, format (json, xlsx, pdf, print), row count, `includes_pii`, `watermark_id` | every Excel export of an outlet list is logged (D-108, G-man-039) |
| `security_event` | auth service | event, actor, target, device, ip, request id, detail | shell; the catalogue is doc 21 |
| `risk_signal` | worker | `(id, business_date)` partitioned; signal_code (FS-nn), user, route, outlet, record, weight, status open, reviewed, dismissed or confirmed, reviewer | shell; thresholds `cfg.sec.fraud.*` (D-267); the SR never sees any signal except the mock warning (D-123) |
| `activity_log` | device (batched inside the sync) and API | partitioned by month; user, device, screen, action, entity, `client_uuid` | three months hot (G-scale-07); never on the hot path |

### 6.6 Immutability of synced rows

```sql
-- synced rows are immutable except named columns; deletes never happen (D-22, D-98, A-64, D-524)
-- AFTER UPDATE, so generated columns hold their final values when compared
CREATE FUNCTION app.tx_no_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE allow text[] := TG_ARGV;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'synced rows are never deleted (%)', TG_TABLE_NAME USING ERRCODE = '42501'; END IF;
  IF (to_jsonb(NEW) - allow) IS DISTINCT FROM (to_jsonb(OLD) - allow) THEN
    RAISE EXCEPTION 'synced rows are immutable (%): only % may change', TG_TABLE_NAME, array_to_string(allow, ', ') USING ERRCODE = '42501';
  END IF;
  RETURN NULL;
END $$;
-- the policy is data: one row per capture table, the mutable columns named explicitly (D-524, G-qa-49)
CREATE TABLE app.immutability_policy (tbl text PRIMARY KEY, mutable_cols text[] NOT NULL, writer text NOT NULL CHECK (writer IN ('worker','api','both','none')), note text);
INSERT INTO app.immutability_policy(tbl, mutable_cols, writer, note) VALUES
  ('visit',              ARRAY['server_distance_m','server_radius_m_used','server_geo_valid','server_checked_at','geo_mismatch','plausibility_flags','suspicion_score','flags','sig_status','location_request_id','qc_completed_at'], 'worker', 'server geo re-check and signals'),
  ('memo',               ARRAY['status','superseded_at','voided_at','flags','print_count','printed_at','memo_serial','sig_status'], 'both', 'supersede, void, print counters'),
  ('memo_line',          ARRAY['flags','list_price_mtk'], 'worker', 'price mismatch enrichment'),
  ('memo_offer',         ARRAY['flags'], 'worker', ''),
  ('qc_entry',           ARRAY['flags'], 'worker', ''),
  ('qc_entry_line',      ARRAY[]::text[], 'none', 'nothing may change'),
  ('drp_collection',     ARRAY['flags'], 'worker', ''),
  ('drp_collection_line',ARRAY[]::text[], 'none', ''),
  ('survey_response',    ARRAY['flags','points_credited_at'], 'worker', 'earn rule applied once (D-41)'),
  ('content_view',       ARRAY[]::text[], 'none', 'telemetry'),
  ('due_collection',     ARRAY['flags','sig_status'], 'worker', ''),
  ('due_dispute',        ARRAY['status','closed_at','closed_by','flags'], 'api', 'lifecycle of a dispute (G-field-09)'),
  ('cash_handover',      ARRAY['flags'], 'worker', ''),
  ('stock_movement',     ARRAY['flags','confirmed_by','confirmation_client_uuid'], 'both', 'distribution-house confirmation (F-AMO-042)'),
  ('attendance_event',   ARRAY['flags'], 'worker', ''),
  ('visit_skip',         ARRAY['flags'], 'worker', ''),
  ('memo_void',          ARRAY['flags','slip_printed'], 'both', ''),
  ('print_event',        ARRAY['flags'], 'worker', ''),
  ('geo_fix',            ARRAY['prev_fix_id','dist_from_prev_m','secs_from_prev','implied_speed_mps','plausibility_flags','flags','wifi_hash_known_count'], 'worker', 'plausibility enrichment'),
  ('outlet_change_request', ARRAY['status','verified_by','verified_at','verified_via','verifier_payload','verifier_fix_id','verifier_photo_media_id','remote_verification','approved_at','approved_by','rejected_by','rejected_at','rejection_reason','moved_m','requires_tso','resulting_outlet_id','flags'], 'api', 'lifecycle; every step also appends outlet_change_event'),
  ('task',               ARRAY['status','resolved_at','resolved_by','resolution_note','flags'], 'api', 'lifecycle; every step also appends task_event'),
  ('task_event',         ARRAY[]::text[], 'none', 'append-only'),
  ('leave_application',  ARRAY['status','approver_user_id','decided_at','flags'], 'api', 'lifecycle; leave_event is append-only'),
  ('visit_plan',         ARRAY['status','completed_at'], 'api', ''),
  ('visit_plan_outlet',  ARRAY['status','completed_at','assessment_id','completed_visit_id'], 'api', ''),
  ('feedback',           ARRAY['status','handled_by','handled_at'], 'api', ''),
  ('call_assessment',    ARRAY['flags'], 'worker', ''),
  ('call_assessment_answer', ARRAY[]::text[], 'none', ''),
  ('distribution_check', ARRAY['flags'], 'worker', ''),
  ('price_compliance_check', ARRAY['flags'], 'worker', ''),
  ('supervisor_day',     ARRAY['first_sync_at','sales_submitted_at','submitted_with_dues','flags'], 'api', 'day-state timestamps'),
  ('day_exception',      ARRAY['status','approved_by','approved_at','flags'], 'api', ''),
  ('redemption',         ARRAY['status','verified_at','flags'], 'both', ''),
  ('loyalty_ledger',     ARRAY[]::text[], 'none', 'append-only; a correction is a new row'),
  ('gift_photo',         ARRAY['flags'], 'worker', ''),
  ('media_object',       ARRAY['state','uploaded_at','blob_path','content_sha256','upload_path','attempts'], 'api', 'upload progress is the one legitimate mutation of a capture row');
DO $$ DECLARE t record; BEGIN
  FOR t IN SELECT tbl, mutable_cols FROM app.immutability_policy LOOP
    EXECUTE format('CREATE TRIGGER %I AFTER UPDATE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.tx_no_rewrite(%s)',
                   t.tbl || '_no_rewrite', t.tbl, (SELECT coalesce(string_agg(quote_literal(a), ','), '') FROM unnest(t.mutable_cols) a));
    EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.tx_no_rewrite()', t.tbl || '_no_delete', t.tbl);
  END LOOP;
END $$;
```

Every OFFLINE or QUEUED capture table of s5.1 is a row of `app.immutability_policy`, so the first draft's protection of five tables (visit, memo, memo_line, due_collection, geo_fix) now covers all of them: a bug or a hand edit can no longer rewrite stock, attendance, QC, DRP, survey, offer, skip, void, print or request rows that dw has already aggregated. A column listed for a lifecycle table (status, approver) is a legitimate state change and is also written to the table's append-only event row; a table whose list is empty may not change at all. The migration lint (T-0-06) fails when a capture table has no policy row, and T-0-07 iterates over `app.immutability_policy` instead of a hand-written list, attempting an UPDATE of every non-listed column and a DELETE on each table (D-524). The allow-list is the enrichment the server writes after sync: the server geo verdict, plausibility flags, supersede and void markers, print counters, the memo serial. A trigger is the last line of defence; the first is that `api_rw` has no DELETE grant on any transactional table (s13.4) and the API only INSERTs. Archival drops whole partitions (s13.1), which this trigger does not see.

Proved by: T-0-05, T-0-07, T-1-01, T-1-02, T-1-05, T-1-04, T-0-06.

### 6.7 Tables and columns that doc 21 designs (inventory added at the editorial merge, OI-21-15)

Doc 21 holds the DDL or the column list of record for the objects below; this document carries the inventory so that the migration blocks (s2.2) and the schema contract lint (T-0-06) see them. The objects named here that doc 21 already treats as existing (`device.public_key`, `hw_backed`, `trust_level`, `ingest_registry.content_fp`, `geo_fix.radio_env`, `tx_no_rewrite`) are in s5, s6.1, s6.4 and s6.6.

| Object | What it holds | Migration | Defined in |
| --- | --- | --- | --- |
| `app.geo_closure` | `(anc_type, anc_id, desc_type, desc_id)`, maintained by triggers on the geography tables; about 68,000 rows | M-40 | doc 21 s3.1 |
| `app.user_route_reach` | `(user_id, route_id, scope_version)`, rebuilt on every change to `user_scope`, `route_assignment` or role and nightly for assignment roll-overs; the target of the RLS policies (D-106) | M-40 | doc 21 s3.1 |
| `app.auth_attempt` | `(username, device_uuid, ip_class, window, failures, locked_until)`; PostgreSQL is the lockout authority and Redis only accelerates (D-475) | M-41 | doc 21 s2.4 |
| `app.refresh_token` additions | `grant` (full or upload), `family_id`, `replaced_by_id`, `replaced_at`, `replay_response` for the 60 s rotation grace (D-101, D-471) | M-41 | doc 21 s2.3 |
| `app.device_otp` additions | daily failed-OTP counter and a bind-locked state cleared by a TSO with an audit row (D-473) | M-41 | doc 21 s2.5 |
| `app.pii_key` | wrapped data-encryption keys for NID, TIN and trade licence (`key_id`, wrapped DEK, Key Vault key version, created and retired times); ciphertext columns store `key_id + nonce + ct + tag` as `bytea` (D-107); the `pii_key_id` column of s5 points here | M-41 | doc 21 s4.3 |
| `app.user_consent` | `(user_id, policy_version, accepted_at)`, a queued record; a new policy version re-prompts (D-120) | M-43 | doc 21 s4.7 |
| `app.risk_review` | append-only supervisor actions with a `client_uuid` (reviewed, dismissed, confirmed); `risk_signal.status` is derived from them by the worker | M-56 | doc 21 s7.1 |
| `app.user_risk` | `(user_id, score_30d, confirmed_30d, open_signals, updated_at)` | M-56 | doc 21 s7.1 |
| `dw.fact_risk_signal` | mirror of `app.risk_signal` for the Exceptions report | M-62 | doc 21 s7.1 |
| `app.cell_centroid` | `(mcc, mnc, tac, ci, lat, lng, radius_m, samples, updated_at)` learned from fixes of devices with `trust_level` normal and no flags | M-57 | doc 21 s7.2 |
| `stg.apsis_credential` | importer-stored verifiable Apsis hash with column grants for the `auth` app only; deleted at the user's first login or 60 days after the wave | M-130 to M-149 | doc 21 s2.9 |

Proved by: T-0-06 (every object has a migration inside its block), T-0-07, T-1-77, T-3-78.

## 7 Data-quality rules

Rules run at the door (ingest), after it (worker enrichment), nightly and on import. They exist so that no row vanishes (G-data-06), every anomaly is a named, countable flag (G-analyst-07), and the analyst can see how much of a number is suspect. 69 rules: DQ-01 to DQ-34 from the data lens (adapted to the decisions of this plan), DQ-35 to DQ-43 from the fraud critic, DQ-44 to DQ-69 new in this document (manual-derived).

### 7.1 Policies, stages and the one principle

| Policy | What happens | Visible to | Retry |
| --- | --- | --- | --- |
| REJECT | The record is not stored in its table. A row goes to `app.sync_rejected` with `reason_code` and `dq_rule`; the batch response lists it in `rejected[]`; the device keeps it `failed` and shows it on the device-versus-server screen with the reason in Bangla and English | the SR, the quarantine page (doc 19) | only after a fix (`fix and accept`, doc 19 s8) |
| PARK | As REJECT, `status = 'parked'`, `retryable = true`, `next_retry_at` set; retried when the missing parent lands or every 10 minutes; becomes REJECT after `cfg.sync.parked_ttl_days` (7; NEW key for doc 19) | support and the quarantine page, not shown as the device's fault | automatic |
| FLAG | The record is accepted and its `flags text[]` gains the flag code; the worker copies each flag to `dw.fact_dq_flag` | dashboards, Exceptions page, reports | not applicable |
| CLAMP | The value is corrected deterministically (digits normalised, divide by zero becomes NULL) and flagged where a human could be misled | the row | not applicable |
| ACCEPT | An exact replay: nothing is written, `seen_count` is incremented, the record counts as accepted | nobody | not applicable |
| INFO | Reported in a nightly quality report only | admin | not applicable |

THE PRINCIPLE (ASSUMPTION, why: a rejected sale is a lost sale, and the retailer has already paid and received a printed memo): a record that carries money already handed over is FLAGGED or PARKED for a business-plausibility failure, never rejected. REJECT is used only for identity (who, which uuid, which scope), structure (arithmetic, code, type) and duplication. The rules that break the principle on purpose are DQ-14 and DQ-26, which refuse a record whose own arithmetic cannot be true; both come with a `fix and accept` action so the sale is recovered by a person.

| Stage | Where it runs | Rules |
| --- | --- | --- |
| envelope (E) | the batch request, before any record | DQ-04, DQ-34, DQ-39 |
| record (R) | per record inside the per-record savepoint of the ingest function (s6.1) | most rules |
| enrich (W) | the worker after the batch, reading `app` and writing the enrichment columns of s6.6 and `dw` | DQ-17, DQ-24, DQ-25, DQ-35, DQ-36, DQ-54, DQ-68 |
| nightly (N) | the 00:30 Dhaka job | DQ-30, DQ-32, DQ-45, DQ-47, DQ-48, DQ-64 |
| import (I) | the importer (s12) and admin uploads | DQ-31, DQ-44, DQ-46, DQ-47, DQ-49, DQ-50, DQ-62, DQ-63 |

Policy is data: `cfg.dq_rule` holds the policy, flag code and owner of each rule, so a change from FLAG to PARK is an audited config change and not a deploy (D-378). Rules whose REJECT protects idempotency or scope are `policy_locked`.

```sql
-- M-45 the rule registry: policy is data, so moving a rule from FLAG to PARK is a config change with an audit row, not a deploy (D-378)
CREATE TABLE cfg.dq_rule (
  rule_id text PRIMARY KEY CHECK (rule_id ~ '^DQ-[0-9]{2}$'),
  stage text NOT NULL CHECK (stage IN ('envelope','record','enrich','nightly','import','admin')),
  policy text NOT NULL CHECK (policy IN ('REJECT','PARK','FLAG','CLAMP','INFO')),
  policy_locked boolean NOT NULL DEFAULT false,            -- true for rules whose REJECT protects idempotency or scope (DQ-01, 04, 06, 41): not editable
  flag_code text NOT NULL, retryable boolean NOT NULL DEFAULT false, severity smallint NOT NULL DEFAULT 2 CHECK (severity BETWEEN 1 AND 4),
  owner_role text NOT NULL DEFAULT 'support', since_phase text NOT NULL, description text NOT NULL,
  CHECK (NOT (policy_locked AND policy = 'FLAG')));
```

The 69 rows are seeded by the migration from the three tables below. T-0-06 fails the build if a flag string emitted anywhere in `/api` is missing from `cfg.dq_rule`, or a rule below has no row.

### 7.2 DQ-01 to DQ-34 (data lens, adapted)

Config keys use the canonical names of the plan: `cfg.sync.max_backdate_days` (7), `cfg.sync.max_clock_skew_min` (5), `cfg.geo.max_accuracy_m` (100), `cfg.geo.max_speed_kmh` (60), `cfg.media.photo_max_kb` (D-75), `cfg.sale.max_line_qty_base`, `cfg.sale.max_lines_per_memo` (60, D-246). A threshold written as a number is the default; its bounds are in doc 19 s3.

| Rule | Check | Policy | Code | Stage |
| --- | --- | --- | --- | --- |
| DQ-01 | `client_uuid` is a valid v4 UUID and is not registered with a different `payload_sha256` | REJECT, to `sync_conflict`; first payload wins; locked | `conflict` | R |
| DQ-02 | `client_uuid` already registered with the same hash | ACCEPT silently, `seen_count + 1` (idempotent replay, D-21) | none | R |
| DQ-03 | Parent (`visit_client_uuid`, `memo_client_uuid`, `plan_client_uuid`, `against_memo_client_uuid`) exists | PARK; retried when the parent registers or every 10 minutes; REJECT after `cfg.sync.parked_ttl_days` | `parent_missing` | R |
| DQ-04 | The payload user equals the token user and the device is bound to that user | REJECT; locked | `user_mismatch` | E |
| DQ-05 | The outlet exists and was active on the business date | REJECT if unknown; FLAG if inactive or closed (archived and merged: DQ-51) | `outlet_unknown`, `outlet_inactive` | R |
| DQ-06 | The outlet's route is inside the token's scope | REJECT; locked | `out_of_scope` | R |
| DQ-07 | The route has an assignment (primary, cover or ss) for the user on the business date | FLAG, accept: an offline SR is never blocked for a cover (D-85) | `unassigned_route` | R |
| DQ-08 | The SKU exists and is sales-enabled; it is in the zone's sales plan as of the business date | REJECT unknown; FLAG not in plan (answers Q21) | `sku_unknown`, `sku_not_in_plan` | R |
| DQ-09 | The business date, taken from trusted time, lies in `[today - cfg.sync.max_backdate_days, today + 1]`, counted in working days (D-584). For a batch with `trigger = resync` (doc 17 s4.12) the window is measured from the announced `restore_point_utc`, not from receipt: `[business_date(restore_point_utc) - cfg.sync.max_backdate_days, today + 1]`, and a row inside `cfg.sync.resync_late_max_days` of that anchor but outside the ordinary window is ACCEPTED and flagged `resync_late` (DQ-71), because those are exactly the sales the server lost and the device is restoring (D-572) | REJECT outside (QUARANTINE, not drop, for the ordinary case; `resync_late` for the resync case) | `business_date_out_of_window`, `resync_late` | R |
| DQ-10 | The business date equals the server's date computed from `captured_at_trusted` | FLAG | `business_date_mismatch` | R |
| DQ-11 | `captured_at` is not later than `received_at + cfg.sync.max_clock_skew_min` | FLAG; `time_skew_s` kept on `device_integrity` | `clock_skew` | R |
| DQ-12 | `qty_entered > 0`; `pack_factor` equals the SKU factor for `unit_entered` on the date; `qty_base <= cfg.sale.max_line_qty_base` | REJECT on zero, negative or unknown unit; FLAG on factor mismatch (device value stored) and on huge quantity | `qty_invalid`, `pack_factor_mismatch`, `qty_outlier` | R |
| DQ-13 | `base_price_mtk` equals the price valid on `price_list_date` for the outlet's price type | FLAG with the expected value in `flags_detail`; never reject (the retailer already paid the printed price) | `price_mismatch` | R |
| DQ-14 | Memo arithmetic: gross = sum of line gross; net = gross - offer discount - DRP discount - QC deduction (the slide and QC amounts of D-18); the total is rounded once to the paisa and `round_adj_mtk` records the difference (D-350); paid + due = net; `due >= 0` unless net is negative; `line_count` equals the number of lines; lines <= `cfg.sale.max_lines_per_memo` | REJECT the memo, PARK its lines; the quarantine page offers `fix and accept` | `memo_arith` | R |
| DQ-15 | `abs(round_adj_mtk) <= 5` (half a paisa is 5 mtk) | FLAG otherwise | `rounding_anomaly` | R |
| DQ-16 | An edit's `supersedes_client_uuid` exists, belongs to the same outlet and business date, the outlet has no QC done, the chain is at most 3 deep (D-201) | REJECT | `edit_not_allowed` | R |
| DQ-17 | The edit fix is within the radius of the outlet (server recompute) | FLAG | `edit_out_of_range` | W |
| DQ-18 | QC faults for a SKU do not exceed the quantity sold in the visit's active memos | FLAG | `qc_exceeds_sold` | R |
| DQ-19 | A collection's `against_memo` belongs to the outlet; the amount is at most the memo's outstanding; the outlet balance after is not below zero | REJECT if the memo is not the outlet's; FLAG overpayment (the money was taken) | `due_memo_mismatch`, `overpayment` | R |
| DQ-20 | Attendance: check-out later than check-in; not before `cfg.day.checkout_earliest_time`; one event per kind per user and day (first wins) | REJECT out of order; FLAG early check-out and a later duplicate | `attendance_order`, `early_checkout`, `duplicate_event` | R |
| DQ-21 | Stock: returned <= issued for user, day and SKU; sold + returned <= issued | FLAG | `stock_variance` | W |
| DQ-22 | Position inside the box of `cfg.geo.country_bbox` (the one owner of the box, D-576; default latitude 20.3 to 26.9, longitude 87.9 to 92.8; doc 21 s6.4 reads the same key); accuracy <= `cfg.geo.max_accuracy_m` | FLAG; outside the box also sets `server_geo_valid = false` | `geo_out_of_country`, `poor_accuracy` | R |
| DQ-23 | `is_mock = true` makes `server_geo_valid = false`; under `cfg.geo.mock_policy = 'block'` the visit is still accepted and its memos carry the flag | FLAG | `mock_location` | R |
| DQ-24 | Server Haversine distance to the outlet location valid at `captured_at` is within the radius resolved from config at that time | sets `server_geo_valid`; FLAG when it differs from the device verdict | `geo_mismatch` | W |
| DQ-25 | Plausibility per user and day: implied speed above `cfg.geo.max_speed_kmh` (60); at least 5 identical fixes to 6 decimals; accuracy identical across 5 fixes or below 1 m; 80 percent of a route's outlets visited from within 20 m of one point | FLAG on the visits; `suspicion_score` is the weighted sum (weights `cfg.geo.integrity_weight`, doc 21) | `teleport`, `zero_jitter`, `perfect_accuracy`, `single_point_route` | W |
| DQ-26 | Redemption: sum of `points_each x qty` equals `points_spent`; spent <= balance; gifts valid for the period | REJECT arithmetic; FLAG overdraw (accepted, balance goes negative, redemption blocked until reviewed) | `redemption_arith`, `points_overdrawn` | R |
| DQ-27 | Survey and assessment answers match the type and options of the question or criterion version | REJECT | `answer_invalid` | R |
| DQ-28 | Every code (edit reason, force reason, leave type, task type, DRP kind) exists in `cfg.code_item` for the business date | REJECT unknown | `code_unknown` | R |
| DQ-29 | Photo: bytes <= `cfg.media.photo_max_kb`, JPEG or WebP, longest side <= 1600 px, `content_sha256` matches | REJECT the upload; the device recompresses | `media_invalid` | R |
| DQ-30 | A visit with no memo, not zero-sale, still open 24 hours later | FLAG | `incomplete_visit` | N |
| DQ-31 | Targets (admin and import): `std_target >= 0`, `memo_target >= 0`, scope and product exist, month valid, revision values >= 0 | REJECT at the API and by CHECK; import rows go to `stg.quarantine` | `target_negative` | I |
| DQ-32 | Master quality: zone without house, outlet without location, planned route without assignment, SKU without a price for a type | INFO: one `dw.fact_dq_flag` row per failing master record (record type outlet, route, zone or SKU), counted per zone on the admin master-quality page | none | N |
| DQ-33 | Every percentage uses `dw.safe_div`; a zero, negative or null denominator is NULL and renders as a dash | CLAMP to NULL (D-28, D-50) | none | W |
| DQ-34 | Batch `device_counts[type]` equals accepted + rejected + conflict + replayed per type | FLAG the batch, `reconcile_mismatch = true` | `count_mismatch` | E |

### 7.3 DQ-35 to DQ-43 (fraud critic, adapted)

| Rule | Check | Policy | Code | Stage |
| --- | --- | --- | --- | --- |
| DQ-35 | Fix freshness and shape: `fix_age_ms` above `(cfg.geo.fix_reuse_max_age_s + 5) x 1000`; `fixed_at` earlier than the user's previous fix; provider `gps` with null satellites and zero altitude, speed and bearing on 3 or more fixes in a day | FLAG | `stale_fix`, `fix_time_regress`, `synthetic_fix_shape` | W |
| DQ-36 | GNSS time against device time: `abs(fixed_at - captured_at) > cfg.geo.gps_time_skew_max_s` (60) | FLAG | `gps_time_anomaly` | W |
| DQ-37 | A proposed location more than `cfg.sec.fraud.location_move_alert_m` (300; D-267) from the confirmed one; a verifier fix outside the radius of the proposed point | FLAG and set `requires_tso` (s4.4, D-111) | `location_move_large`, `remote_verification` | R |
| DQ-38 | The proposed outlet phone matches an `app_user.phone` or a phone used in another user's request in 90 days | FLAG | `outlet_phone_is_staff` | R |
| DQ-39 | `X-App-Version` is in `cfg.release.blocked_versions` and the trusted capture time is after the block's `effective_from` | PARK | `blocked_version_capture` | E |
| DQ-40 | `time_untrusted` or `clock_skew_flag` and the claimed business date is in a month closed more than `cfg.day.month_close_grace_days` (3) ago | PARK for supervisor acceptance; never aggregated automatically | `closed_month_untrusted` | R |
| DQ-41 | `ingest_registry.content_fp` equals another accepted record of the same user and type in 48 hours (regenerated uuids, A-40) | REJECT the second record, non-retryable, as `content_duplicate`; locked; FS-24 raised on the first. DELIBERATE CHANGE from the critic's FLAG, why: a flagged duplicate still doubles the sale (D-353) | `content_replay` | R |
| DQ-42 | `cfg.day.multi_visit_same_outlet_policy`: a second sale visit by the same user to the same outlet on the same day | `allow_after_zero_sale` (default): allowed when the first was zero-sale, else FLAG; `block`: REJECT; `allow`: no rule (D-250) | `repeat_sale_visit` | R |
| DQ-43 | Record signature missing or invalid under `cfg.sec.record_signature_mode = 'enforce'` | PARK and raise FS-18 | `sig_invalid` | R |

### 7.4 DQ-44 to DQ-69 (new, manual-derived)

Each rule cites the manual or profile finding it comes from. All 26 are new ids reserved for this document.

| Rule | Check | Policy | Code | Stage | Source |
| --- | --- | --- | --- | --- | --- |
| DQ-44 | Bengali or Arabic digits in a phone, quantity or amount field are converted by `app.normalise_digits()`; a phone that is not 11 digits `01[3-9]xxxxxxxx` after conversion fails `cfg.outlet.mobile_regex` | CLAMP the digits and flag; REJECT an invalid phone on a request, CLAMP-to-NULL on import | `digits_normalised`, `phone_invalid` | R, I | G-field-10, G-man-037 |
| DQ-45 | The memo numbers of one user and business date (`<username>-<yyMMdd>-<seq3>`) have a gap that no activity row (burned number) explains | FLAG on the day, shown in the sync-health page: a free detector of lost rows and "clear data" days | `memo_seq_gap` | N | G-field-14 |
| DQ-46 | Outlet code normalised (trim, strip stray leading symbols, upper case, no thousands separators); two Apsis codes mapping to one normalised key | CLAMP; REJECT the collision to quarantine | `outlet_code_collision` | I | D-251, G-data-35 |
| DQ-47 | Placeholder identity values: NID "123", phone all one digit, address under 5 characters | CLAMP to NULL for dedupe and reports; counted on the admin master-quality page | `placeholder_id` | N, I | D-256 |
| DQ-48 | An outlet with no coordinates, or on a 6-decimal point shared by at least `cfg.outlet.placeholder_pin_min_shared` (3) outlets, is `location_confirmed = false` | FLAG on the outlet and on its first visit; the cheap correction path of s4.4 applies, no punitive geo-fail (D-253) | `location_unconfirmed` | N | docs/22 P-09, G-cfg-23 |
| DQ-49 | A zero-volume line is not a sale; an outlet-day with only zero lines is a zero-sale call (D-249); a native line with `qty_entered = 0` is DQ-12 | CLAMP: dropped on import and counted in the control total | `zero_volume_line` | I | D-249, G-data-36 |
| DQ-50 | Two import rows with the same (date, outlet, SKU) and different volumes | CLAMP: volumes summed, `duplicate_rows` kept, never overwritten (D-250) | `duplicate_key_merged` | I | D-250, G-data-36 |
| DQ-51 | A transaction for an outlet that is `archived` (stub) or `merged` | REJECT archived (a stub cannot sell); FLAG merged and attribute through `bridge_outlet_effective` | `outlet_archived`, `outlet_merged` | R | D-252, G-analyst-12 |
| DQ-52 | A QC settlement per SKU is not above the per-SKU maximum shown on the screen (`max_qc_mtk`); the slide deduction equals the DRP discount lines | FLAG | `qc_max_exceeded`, `slide_mismatch` | R | G-man-002, D-158 |
| DQ-53 | The printed total (`printed_total_mtk`) equals the stored net after the single paisa rounding | FLAG | `print_total_mismatch` | R | D-19, G-sync-04 |
| DQ-54 | The printed due snapshot differs from the server balance at ingest by more than `cfg.credit.snapshot_tolerance_mtk` (0) | FLAG; both numbers kept for the dispute screen | `due_snapshot_mismatch` | W | G-analyst-15 |
| DQ-55 | The gross of the free lines equals the memo's slide plus free-goods value | FLAG | `free_value_mismatch` | R | G-man-002, D-158 |
| DQ-56 | The outstanding after a credit memo exceeds the outlet credit limit (`cfg.credit.max_due_mtk`, 0 means no limit; limit semantics unknown; confirm with the business) | FLAG; the device already warned (G-feat-45) | `credit_limit_exceeded` | R | G-feat-45, MQ-17 |
| DQ-57 | A record captured for a route-day that is already final-submitted | FLAG and aggregate (the sale happened); the late row shows in the Final Submit Log. A final-submitted zone-day NEVER rejects a row: `day_closed` is not a reject code and is not a code the device must know (D-579); the only closed state that parks a row is a month closed by finance (DQ-40) | `after_final_submit` | R | D-55, K-15, D-579 |
| DQ-58 | A route-day holds both app rows and web-entry rows | FLAG; the effective-source view uses the app rows and the web rows stay in their own facts; never added (D-40, D-362) | `web_app_overlap` | W | G-man-046, D-362 |
| DQ-59 | Web entry: the class columns sum to Sale; Sale = Issue - Return; successful calls <= target outlets when `cfg.web.entry_validate_calls_le_target` | REJECT | `web_entry_arith` | R | F-WEB-050 |
| DQ-60 | Web entry or Final Submit for a date older than the back-date cut-off unless an `entry_unlock_grant` exists | REJECT | `backdate_cutoff` | R | G-man-087 |
| DQ-61 | A web QC cell: a fault code that applies to web, quantity <= `cfg.qc.max_qty_per_cell`, SKU enabled in the zone | REJECT | `qc_cell_invalid` | R | G-man-089, D-34 |
| DQ-62 | A target upload row: duplicate (scope, product, month), unknown scope, negative value, month outside the set | REJECT the row to quarantine, the file continues | `target_invalid` | I | DQ-31, G-man-090 |
| DQ-63 | A price edit: amount >= 0 with at most three decimals; the effective date is today or later unless a break-glass approval exists; overlap is blocked by the exclusion constraint | REJECT | `price_backdated` | I | D-98, G-feat-52 |
| DQ-64 | An SR assigned to a route whose kind is `amo`, or an AMO route with a primary SR ("SR Not Set" on an AMO route is normal and is not flagged) | FLAG at assignment write and nightly | `route_kind_mismatch` | N | G-man-100 |
| DQ-65 | The visit `kind` is compatible with the user's role or an active cover (`sr_call` by an SR or an acting user; `amo_call` by an AMO) | REJECT | `kind_role_mismatch` | R | D-26 |
| DQ-66 | A day opened offline on a cached bundle (`login_source = day_open_offline`) or on a bundle older than `cfg.bundle.stale_max_days`: the route counts as logged in | FLAG `offline_start` and `bundle_stale`; K-01 includes the route and the dashboards show how many were offline starts | `offline_start`, `bundle_stale` | R | G-sync-07, D-30 |
| DQ-67 | A second memo with the same `memo_no` and business date and a different uuid | CLAMP: stored with the suffix `-D<n>` and flagged; the original keeps its number (a sale is never refused for a number) | `memo_no_collision` | R | F-SYS-027 |
| DQ-68 | A superseded or voided memo's loyalty points are reversed by a ledger row; a missing reversal after 1 hour | FLAG and enqueue the reversal | `loyalty_unreversed` | W | G-analyst-13 |
| DQ-69 | A force sale carries the outlet photo (`media_id`); photos trickle after the sale, so absence is parked | PARK 48 hours, then FLAG | `photo_pending`, `force_photo_missing` | R | G-man-016, G-data-25 |
| DQ-71 | A row of a `resync` batch captured before the ordinary window but within `cfg.sync.resync_late_max_days` of the restore anchor (DQ-09) | FLAG and accept; counted on the restore report | `resync_late` | R | D-572 |
| DQ-72 | A record whose route-day carries a void barrier newer than its `captured_at_trusted` (s6.1) | REJECT | `voided_by_admin` | R | D-577 |
| DQ-73 | A row stamped with a `config_version` below one the server delivered to the device more than `cfg.sys.config_apply_grace_min` before the capture (doc 19 s2.3b) | FLAG; judged under the as-of value; FS-34 after `cfg.sec.fraud.stamp_regress_min_rows` rows | `config_stamp_regress` | R | D-571 |

### 7.5 Where a result lands, and what is never lost

| Output | Table or field | Used by |
| --- | --- | --- |
| rejected or parked record | `app.sync_rejected` (payload kept in full, partitioned, 12 months then Blob) | device-versus-server screen, quarantine page, T-1-04 |
| conflict | `app.sync_conflict` | support, doc 21 |
| flag | `flags text[]` on the row, then `dw.fact_dq_flag (record_type, record_id, business_date, flag)` | Exceptions page, "Online/Offline Sales", data-quality report |
| count per flag | `dw.agg_daily_route.flag_counts jsonb`, `dw.agg_daily_zone` through the sum | sync-health tile, AMO Exceptions |
| the three hottest flags as booleans | `price_mismatch`, `sku_not_in_plan`, `qty_outlier` on `fact_memo_line`; `unassigned_route`, `after_final_submit`, `clock_skew` on `fact_visit` | the daily tiles without a join |
| rule policy | `cfg.dq_rule` | the worker and the ingest function |

A flag never changes a number silently: flagged rows stay in the aggregates unless the rule says otherwise (DQ-40, DQ-41), and the dashboard shows how many rows are flagged next to the figure. Proved by: T-0-06, T-1-02, T-1-04, T-1-06, T-2-02, T-2-03, T-2-05, T-7-109.

## 8 Analytics layer (dw)

The dw layer makes "any dashboard, anytime" true: the web database role has no SELECT on `app`, every dashboard, report and app home figure is a query on `dw` (R1, D-61, D-128, G-data-07). Nothing here is written by the API; the worker (s8.6) is the only writer.

### 8.1 Layers and rules

| Rule | Statement |
| --- | --- |
| Shape | Star schema: dimensions (SCD2, day-granular), event facts (one row per transaction, monthly partitions), daily aggregates (small, indexed, what dashboards read), month aggregates, balances, snapshots and the restatement log (D-356) |
| Keys | A fact stores the dimension key valid on its business date and also the natural id, so a re-parented outlet never rewrites history and a join by natural id stays possible |
| Recompute, never increment | An aggregate row is replaced from its source by key; `+= delta` is forbidden, so a retried or late item converges to the same value (D-61) |
| Provenance | Every fact and aggregate that can hold history or web data has `source` (`native`, `web_entry`, `migration_memo`, `migration_aggregate`), `fidelity` (3 memo level, 2 outlet-day aggregate, 1 zone-month totals) and `import_run_id` (D-357, G-analyst-09) |
| Trusted time | `business_date` is the Dhaka date of the trusted capture time; `hour_of_day` and every local-time column derive from the same timestamp (G-analyst-14, Q20) |
| Pilot | `dim_user.pilot` marks pilot accounts; rollups above route exclude them and report `pilot_routes_excluded` (D-373) |
| Reads | The API read path, the web, the app home KPIs and Power BI read `dw` only. Dashboards, reports, Excel and BI read the in-region replica with an "as of" stamp; live-state tiles (route-day, Final Submit validation, device page) read the primary (D-128). Operational work queues (the approval panel, quarantine) read `app` through scoped repositories (D-368) |
| Writes | `worker_rw` only; `api_rw` may call `dw.enqueue()` and nothing else in dw |

### 8.2 Inventory

43 tables are in the tested DDL of this document, created in the sub-milestone of the last column of s2.3, and 22 further event facts and bridges (65 objects in all) are listed in s8.9 so that every field the field apps capture has a dw object (D-500, G-qa-25, G-qa-40). Capture class: all SERVER (worker-written, online read).

| Kind | Tables | Grain and key | Lands |
| --- | --- | --- | --- |
| Dimensions (9) | `dim_date`, `dim_geo`, `dim_outlet`, `dim_outlet_pii`, `dim_user`, `dim_supervisor_assignment`, `dim_target`, `dim_product`, `dim_sku_price` | day; route; outlet; outlet (PII); user; supervisor-node; target version; SKU version; SKU, price type, valid_from | 0b, 1c, 2a |
| Event facts (10) | `fact_visit`, `fact_memo`, `fact_memo_line`, `fact_memo_offer`, `fact_due_ledger`, `fact_due_allocation`, `fact_price_change`, `fact_dq_flag`, `fact_config_change`, `fact_device_day` | one row per visit, memo, line, offer component, ledger event, allocation, price edit, flag, config change, device-day | 1c to 2e |
| Daily aggregates (9) | `agg_daily_route_sku`, `agg_daily_route`, `agg_daily_zone`, `agg_daily_outlet`, `agg_daily_outlet_brand`, `agg_daily_user`, `agg_daily_user_sku`, `agg_daily_zone_category`, `agg_hourly_zone` | business_date x route x SKU; x route; x zone; x outlet; x outlet x brand; x user; x user x SKU; x zone x category; x zone x Dhaka hour | 1c, 3a, 4a |
| Month aggregates (4) | `agg_month_zone_product`, `agg_month_route_product`, `agg_month_outlet_category`, `agg_month_outlet_program` | month x zone or route x product; month x outlet x category; period x outlet x product | 2a, 4b, 5a |
| Balances (6) | `agg_memo_due_open`, `agg_outlet_balance`, `agg_outlet_balance_daily`, `agg_outlet_due_ageing_daily`, `agg_outlet_visit_streak`, `agg_outlet_density` | open memo; outlet now; outlet x changed day; outlet x day x bucket; outlet; outlet | 2b, 2d, 4a |
| Snapshots (2) | `snap_month_zone_product`, `agg_restatement_log` | as_of_date x month x zone x product; one row per changed aggregate key | 1c |
| Operations (3) | `agg_dirty`, `agg_run`, `agg_reconcile` | queue key; worker pass; reconcile check | 0b |

Landing ratchet (D-558, G-qa-90). The 65 objects land from 1c to 5a (M-61 to M-99; the programme aggregates at 5a, `fact_web_entry_line` at 4c), so a gate that required all 65 at the 0b exit could never be green and the first phase exit could not be signed. `/plan/dw-landing.yaml` is GENERATED from the "Lands" columns of s2.3, s8.2 and s8.9.1 (object, migration, sub-milestone). T-0-156 (0b) asserts the inventory: every object has a landing row and the generator output equals this document. T-0-100 is a RATCHET that runs at every sub-milestone exit from 1c to 5a and requires exactly the objects whose landing sub-milestone is at or before the current one (with `source` and `fidelity` columns where history can be imported), so the check is green when it is first meaningful and cannot decay.

Later dw objects, each created in the sub-milestone shown and following the same rules (the key column set and `source`/`fidelity` columns are those of s8.3):

| Object | Grain | Lands | Answers |
| --- | --- | --- | --- |
| `bridge_offer_scope`, `bridge_offer_product` | offer x node x validity; offer x product | 2a | Q13 offer attribution |
| `bridge_outlet_effective` | outlet x effective outlet x valid_from (merges) | 3a | Q16 |
| `bridge_holiday_scope`, `bridge_route_day_override` | scoped holiday; route-day override mirror | 4a | Q10, Q15 |
| `agg_daily_zone_offer` | date x zone x offer | 2a | Discount report |
| `dim_app_version`, `dim_reason`, `dim_gift`, `dim_program_period`, `dim_device` | one row per version, code, gift, period, device | 2a to 5a | Q9, Q7, Q14 |
| `fact_attendance`, `fact_stock_movement`, `fact_bundle_download`, `fact_outlet_request`, `fact_task`, `fact_assessment`, `fact_distribution`, `fact_price_compliance` | one row per event | 2a to 3a | GIGO, Q17, Q15, Q16 |
| `fact_redemption_line`, `fact_loyalty_ledger`, `fact_gift_assignment` | one row per line, ledger entry, assignment | 5a | Q14, Q25 |
| `fact_web_entry_line` | web route-day x SKU line | 4c | Web Entry, DSS |
| the 22 capture facts and `bridge_memo_offer_version` of s8.9 | one row per captured event, answer, line, fix or media object | 2a to 4c | every field captured on a device (R1) |

### 8.3 Dimensions

```sql
-- M-60 dim_date: the only calendar; rebuilt from cfg.holiday and cfg.calendar.weekend_days whenever the admin edits the calendar (D-28)
CREATE TABLE dw.dim_date (
  date_key int PRIMARY KEY,                                         -- yyyymmdd of the Dhaka business date
  business_date date NOT NULL UNIQUE,
  year smallint NOT NULL, quarter smallint NOT NULL, quarter_label text NOT NULL,    -- calendar quarters, e.g. 'Q4 Oct-Dec' (cfg.astha.quarter_start_month unconfirmed)
  month smallint NOT NULL, month_start date NOT NULL, month_end date NOT NULL, days_in_month smallint NOT NULL, day_of_month smallint NOT NULL,
  iso_dow smallint NOT NULL, dow_mask smallint NOT NULL,                              -- dow_mask matches app.route.visit_days_mask (bit0 Sat .. bit6 Fri)
  is_weekend boolean NOT NULL, is_holiday boolean NOT NULL, holiday_name text, is_working_day boolean NOT NULL,   -- global calendar; scoped holidays in bridge_holiday_scope
  working_day_of_month smallint NOT NULL, working_days_in_month smallint NOT NULL,   -- for the per-surface till-date bases (D-51)
  fiscal_year text, week_of_year smallint, structure_basis text NOT NULL DEFAULT 'native' CHECK (structure_basis IN ('native','cutover_snapshot')));
```

`dw.build_dim_date(from, to)` (tested, in the migration) rebuilds the rows from `cfg.holiday`, `cfg.calendar.weekend_days` and the working-day function of s11; the admin calendar editor calls it for the touched range and the worker re-enqueues the affected route-days. `structure_basis = 'cutover_snapshot'` labels pre-cutover months, whose geography is the 1 October structure (G-analyst-09, D-379).

```sql
-- M-61 dimensions (SCD2, day-granular; facts store the key valid on the business date, never only the natural id)
CREATE TABLE dw.dim_geo (          -- grain: route; the whole chain to wing is flattened; member 0 = 'Unmapped' for archived stubs (D-367)
  geo_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, route_id bigint NOT NULL, route_code text, route_name text, route_kind text, visit_kind text, visit_days_mask smallint,
  zone_id bigint, zone_code text, zone_name text, dep_id text, house_id bigint, house_name text,
  territory_id bigint, territory_name text, division_id bigint, division_name text, wing_id bigint, wing_name text,
  cutover_wave smallint, on_new_system_from date,                   -- the wave that switched this route and the first business date it sells on Aron; NULL = still on Apsis (D-548)
  valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL DEFAULT true, UNIQUE (route_id, valid_from));
CREATE INDEX ON dw.dim_geo (route_id, valid_from, valid_to);  CREATE INDEX ON dw.dim_geo (zone_id) WHERE is_current;
CREATE TABLE dw.dim_outlet (       -- grain: outlet; no PII (D-107)
  outlet_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, outlet_id bigint NOT NULL, outlet_code text, outlet_name text,
  status text, outlet_kind text, price_type text, channel text, sub_channel_id bigint, sub_channel_name text, astha_tier text, geo_classification text,
  route_id bigint, zone_id bigint, cluster_id bigint, cluster_name text, cluster_type text,
  latitude double precision, longitude double precision, location_confirmed boolean, location_source text,
  merged_into_outlet_id bigint, created_date date, valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL DEFAULT true, UNIQUE (outlet_id, valid_from));
CREATE INDEX ON dw.dim_outlet (outlet_id, valid_from, valid_to);  CREATE INDEX ON dw.dim_outlet (route_id) WHERE is_current;
CREATE TABLE dw.dim_outlet_pii (outlet_id bigint PRIMARY KEY, owner_name text, contact_number text, address text, updated_at timestamptz);   -- separate grants (s13)
CREATE TABLE dw.dim_user (user_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id bigint NOT NULL, username text, full_name text, role text, designation text,
  employee_code text, status text, pilot boolean NOT NULL DEFAULT false, home_zone_id bigint, valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL DEFAULT true, UNIQUE (user_id, valid_from));
CREATE TABLE dw.dim_supervisor_assignment (assignment_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id bigint NOT NULL, user_key bigint, role text NOT NULL,
  node_type text NOT NULL, node_id bigint NOT NULL, valid_from date NOT NULL, valid_to date, UNIQUE (user_id, node_type, node_id, valid_from));
```

Rules for the dimensions:

- SCD2 is day-granular: one row per `(natural id, valid_from)`; a change opens a row from the change date, so history stays right. A back-dated correction (an admin sets `valid_from` in the past) enqueues every affected `(outlet, date)` and the admin page says "N days will be re-aggregated" first (s8.7).
- `dim_geo` is route-grain and flattens the chain to wing; member 0 "Unmapped" holds the 175,031 archived stubs until the dump supplies their last route (D-367, G-16-10).
- `dim_outlet` holds no PII (D-107). PII is `dim_outlet_pii`, one row per outlet, readable only by `pii_reader` (s13.2, s13.4). Sub-channel, Astha tier and cluster are in `dim_outlet`, so "Astha achievement by tier" reads the tier valid on the date (Q25).
- `dim_supervisor_assignment` (user, role, node type, node, validity) is a copy of `app.user_scope` (M-46); "by AMO" and "by TSO" use it (G-analyst-04, Q5).
- `dim_target` mirrors `app.target_version`, so "the target in force on the 12th" is a dw lookup (G-analyst-02, Q6). `dim_product` and `dim_sku_price` close G-analyst-06 and G-feat-52:

```sql
-- M-61/M-74/M-76/M-69 the product, price, offer and device-day tables the facts above key into (G-analyst-06, G-analyst-08, G-feat-52, G-analyst-16)
CREATE TABLE dw.dim_product (          -- grain: sku; the whole tree flattened; one row per version of a name, status, unit or sort
  product_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, sku_id bigint NOT NULL, sku_code text, sku_name text, status text,
  variant_id bigint, variant_name text, brand_id bigint, brand_name text, segment_id bigint, segment_name text, category_id bigint, category_name text,
  base_unit text NOT NULL, base_per_pack int NOT NULL, report_unit text, report_factor numeric(12,6) NOT NULL DEFAULT 1,
  valid_from date NOT NULL, valid_to date, is_current boolean NOT NULL DEFAULT true, UNIQUE (sku_id, valid_from));
CREATE INDEX ON dw.dim_product (sku_id, valid_from, valid_to);
CREATE TABLE dw.dim_sku_price (        -- every price of every type with its validity, so list, outlet and reporting values are joinable on the memo's price_list_date
  sku_id bigint NOT NULL, price_type text NOT NULL, valid_from date NOT NULL, valid_to date, per_base_qty smallint NOT NULL DEFAULT 1, amount_mtk bigint NOT NULL,
  PRIMARY KEY (sku_id, price_type, valid_from));
CREATE TABLE dw.fact_price_change (    -- one row per price edit: the evidence for "which price did this memo use" (G-feat-52)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, sku_id bigint NOT NULL, price_type text NOT NULL, effective_from date NOT NULL,
  old_amount_mtk bigint, new_amount_mtk bigint NOT NULL, changed_at timestamptz NOT NULL, changed_by_user_key bigint,
  memos_after_change_on_old_price int, lines_price_mismatch int);                     -- filled by the worker the day after: how many devices still sold the old price
CREATE TABLE dw.fact_memo_offer (      -- memo-level offers, one row per component (G-analyst-08)
  memo_offer_id bigint NOT NULL, business_date date NOT NULL, memo_id bigint NOT NULL, offer_id bigint, offer_version_id bigint, offer_scope_type text,
  zone_id bigint, route_id bigint, outlet_id bigint, kind text NOT NULL, sku_id bigint, qty_base int, value_mtk bigint NOT NULL,
  source text NOT NULL DEFAULT 'native', fidelity smallint NOT NULL DEFAULT 3, import_run_id bigint,
  PRIMARY KEY (memo_offer_id, business_date)) PARTITION BY RANGE (business_date);
CREATE TABLE dw.fact_device_day (      -- telemetry as a fact: battery, retries and storage per device-day, never a stream (D-136, G-analyst-16)
  business_date date NOT NULL, device_id bigint NOT NULL, user_key bigint, app_version text, android_sdk smallint,
  battery_drop_pct numeric(5,2), gps_fixes int, wake_lock_ms int, sync_attempts int, sync_failures jsonb, pending_rows int, free_storage_mb int, crash_count int,
  clock_offset_ms bigint, mock_capable boolean, PRIMARY KEY (business_date, device_id));
```

### 8.4 Event facts

| Fact | Grain and key | Partition | What it adds beyond `app` | Gap |
| --- | --- | --- | --- | --- |
| `fact_visit` | visit; `(visit_id, business_date)` | monthly | dimension keys and `effective_user_key = coalesce(acting_for_user_id, user_id)` (D-26); Dhaka `hour_of_day`; outcome; `is_planned`, `is_abandoned`, `is_successful`, `is_zero_sale`; both geo verdicts, `device_distance_m`, `server_distance_m`, `gps_accuracy_m` and `location_confirmed` of the outlet at capture (the what-if and density tools of doc 19 s6 read them, G-19-04); `radius_m_used` and `config_version`; `app_version`; `captured_offline`, `bundle_stale`, `sync_latency_s`; `flags` | G-analyst-11, G-analyst-14 |
| `fact_memo` | memo; `(memo_id, business_date)` | monthly | one row per memo with edits (`supersedes_memo_id`, `edit_reason_code`, `edit_seq`, `edit_delay_s`), prints (`printed_at`, `time_to_print_s`, `print_count`, `unprinted`), the three deductions, `round_adj_mtk`, the printed due snapshot and its mismatch, offer-version set, `entry_source`, `delay_days` | G-analyst-01, G-analyst-15 |
| `fact_memo_line` | memo line | monthly | one row per line: `qty_entered`, `unit_entered`, `pack_factor`, `qty_base`, `qty_report`; price at capture and `list_price_mtk`; free flag; offer key; `price_mismatch`, `sku_not_in_plan`, `qty_outlier` | G-analyst-06, G-analyst-07 |
| `fact_memo_offer` | memo-level offer component | monthly | offer, version, scope and value of slide, DRP, free goods and free samples | G-analyst-08 |
| `fact_due_ledger` | signed ledger event (`opening`, `credit_created`, `collection`, `memo_superseded_reversal`, `void_reversal`, `write_off`, `adjustment`, `transfer_out`, `transfer_in`, `qc_credit`) | monthly | "what did the ledger say at that moment" for a disputed memo (Q22) | G-feat-45 |
| `fact_due_allocation` | collection to memo allocation, `method` `as_recorded` or `fifo` | monthly | FIFO allocation for reporting regardless of the retailer's choice (D-359) | G-feat-45 |
| `fact_price_change` | price edit | none | the date of a change and how many devices still sold the old price the day after | G-feat-52 |
| `fact_dq_flag` | `(record_type, record_id, business_date, flag)` | monthly | every flag of s7 with its dimension keys | G-analyst-07 |
| `fact_config_change` | config audit row | none | acknowledged percentage and the revert link (Q23) | G-analyst-11 |
| `fact_device_day` | device-day | none | battery drop, GPS fixes, wake time, retries, free storage, clock offset | G-analyst-16 |

The wide tables are in the migration, not repeated here; the one that is new in kind, `fact_memo`, is below.

```sql
-- M-70 memo grain (G-analyst-01): edits, prints, entry source and the printed due snapshot in one row
CREATE TABLE dw.fact_memo (
  memo_id bigint NOT NULL, business_date date NOT NULL, date_key int NOT NULL, client_uuid uuid NOT NULL, visit_id bigint,
  outlet_key bigint NOT NULL, geo_key bigint NOT NULL, user_key bigint NOT NULL, effective_user_key bigint NOT NULL,
  outlet_id bigint, route_id bigint, zone_id bigint, user_id bigint, app_version text, memo_no text, memo_serial bigint,
  entry_source text, captured_offline boolean, bundle_stale boolean, status text NOT NULL, supersedes_memo_id bigint, superseded_by_memo_id bigint,
  edit_reason_code text, edit_seq smallint NOT NULL DEFAULT 0, edit_geo_valid boolean, edit_delay_s int,
  is_credit boolean, is_zero_memo boolean, line_count smallint, distinct_sku smallint, distinct_brand smallint,
  gross_mtk bigint, offer_discount_mtk bigint, drp_discount_mtk bigint, qc_deduction_mtk bigint, qc_late_settlement_mtk bigint, round_adj_mtk bigint, net_mtk bigint,
  paid_mtk bigint, due_mtk bigint, outstanding_before_mtk bigint, printed_total_due_mtk bigint, server_balance_at_print_mtk bigint, due_snapshot_mismatch boolean,
  price_list_date date, price_type text, offer_version_count smallint, config_version int, price_mismatch_lines smallint, flags text[],   -- the offer versions of a memo are rows of dw.bridge_memo_offer_version (s8.9), never a blob (D-501)
  captured_at_trusted timestamptz, committed_at timestamptz, printed_at timestamptz, time_to_print_s int, print_count smallint, unprinted boolean,
  received_at timestamptz, sync_latency_s int, delay_days smallint,                                       -- delay_days = Dhaka date of received_at minus business_date
  source text NOT NULL DEFAULT 'native', fidelity smallint NOT NULL DEFAULT 3, import_run_id bigint,
  PRIMARY KEY (memo_id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_memo (business_date, effective_user_key);  CREATE INDEX ON dw.fact_memo (outlet_id, business_date);
CREATE INDEX ON dw.fact_memo (supersedes_memo_id) WHERE supersedes_memo_id IS NOT NULL;
```

Fact rows are written by the worker from `app` rows after ingest, by key; they are never written by the API. Row counts at design volume (500,000 visits, 460,000 memos, 1.1 M lines a day; ASSUMPTION for memos, ratio of 450,000 successful calls plus edits): `fact_visit` 182 M a year, `fact_memo` 168 M, `fact_memo_line` 400 M, so the 25-month hot window of s13.1 holds about 380 M, 350 M and 830 M rows.

### 8.5 Aggregates

| Aggregate | Key | Rows a day | Read by |
| --- | --- | --- | --- |
| `agg_daily_route` | `(business_date, route_id, source)` | 11,336 | Login/Submit Status, Daily Tracking, CPR and BSR, sync health, the SR home strip |
| `agg_daily_route_sku` | `(business_date, route_id, sku_id, source)` | about 340,000 (ASSUMPTION: 30 SKUs sold per route-day of the 40 SKUs in the profile) | STD, Route-wise STD, DSS, free sample |
| `agg_daily_zone` | `(business_date, zone_id)` | 1,051 | dashboards, Final Submit status, territory and wing views above it |
| `agg_daily_outlet`, `agg_daily_outlet_brand` | date x outlet (x brand) | about 450,000 | By Outlet, dormant outlets, BSR |
| `agg_daily_user`, `agg_daily_user_sku` | date x user (x SKU) | 8,500; about 170,000 | SR Efficiency, stock leaks, "by SR" (G-analyst-03) |
| `agg_daily_zone_category` | date x zone x category | 4,204 | Sales (Cigarette), Sales (Bidi) tiles |
| `agg_month_*` | month x scope x product | small | achievement, till-date, leaderboard |

`agg_daily_route` is the row every day-level tile reads; its columns carry numerators and denominators, never percentages, so a rollup sums first and divides once (D-364). The control-call rule is in the columns: AMO calls count in `amo_visits` and `amo_successful_calls` and never in `successful_calls` (D-26).

```sql
CREATE TABLE dw.agg_daily_route (
  business_date date NOT NULL, route_id bigint NOT NULL, source text NOT NULL DEFAULT 'native', fidelity smallint NOT NULL DEFAULT 3, import_run_id bigint,
  geo_key bigint NOT NULL, zone_id bigint NOT NULL, is_pilot boolean NOT NULL DEFAULT false, on_aron boolean NOT NULL DEFAULT true,   -- on_aron: the route had been switched to Aron on this date (dim_geo.on_new_system_from <= business_date, D-548)
  is_planned boolean NOT NULL, planned_source text, exception_reason text, assigned_user_id bigint, acting_user_ids bigint[],
  day_state app.day_state NOT NULL, logged_in boolean, logged_in_at timestamptz, login_source text, offline_start boolean, bundle_stale boolean, bundle_version_at_open text,
  checked_in_at timestamptz, first_visit_at timestamptz, last_visit_at timestamptz, first_sync_at timestamptz, last_sync_at timestamptz,
  sales_submitted boolean, sales_submitted_at timestamptz, submit_pending_rows int, submitted_with_dues boolean, final_submitted boolean, final_submitted_at timestamptz,
  target_outlets int NOT NULL DEFAULT 0, outlets_visited int NOT NULL DEFAULT 0, visits int NOT NULL DEFAULT 0, successful_calls int NOT NULL DEFAULT 0,
  zero_sale_calls int NOT NULL DEFAULT 0, abandoned_calls int NOT NULL DEFAULT 0, unplanned_visits int NOT NULL DEFAULT 0,
  amo_visits int NOT NULL DEFAULT 0, amo_successful_calls int NOT NULL DEFAULT 0,                       -- control calls never inflate the SR strike rate (D-26)
  geo_valid_calls int NOT NULL DEFAULT 0, photo_valid_calls int NOT NULL DEFAULT 0, mock_calls int NOT NULL DEFAULT 0, suspicious_calls int NOT NULL DEFAULT 0, geo_mismatch_calls int NOT NULL DEFAULT 0,
  memo_count int NOT NULL DEFAULT 0, edited_memos int NOT NULL DEFAULT 0, voided_memos int NOT NULL DEFAULT 0, unprinted_memos int NOT NULL DEFAULT 0, reprints int NOT NULL DEFAULT 0,
  credit_memos int NOT NULL DEFAULT 0, price_mismatch_lines int NOT NULL DEFAULT 0, edit_net_reduction_mtk bigint NOT NULL DEFAULT 0,
  gross_mtk bigint NOT NULL DEFAULT 0, offer_discount_mtk bigint NOT NULL DEFAULT 0, drp_discount_mtk bigint NOT NULL DEFAULT 0, qc_deduction_mtk bigint NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0,
  credit_created_mtk bigint NOT NULL DEFAULT 0, collected_mtk bigint NOT NULL DEFAULT 0,
  offline_memos int NOT NULL DEFAULT 0, online_memos int NOT NULL DEFAULT 0, web_entry_memos int NOT NULL DEFAULT 0,
  sync_batches int NOT NULL DEFAULT 0, sync_bytes bigint NOT NULL DEFAULT 0, p50_sync_latency_s int, max_sync_latency_s int, rejected_rows int NOT NULL DEFAULT 0, flag_counts jsonb NOT NULL DEFAULT '{}',   -- flag_counts: sanctioned jsonb exception J-5, a derived convenience; the typed truth is dw.fact_dq_flag (s8.10)
  skips int NOT NULL DEFAULT 0, qc_lines int NOT NULL DEFAULT 0, drp_lines int NOT NULL DEFAULT 0, survey_answers int NOT NULL DEFAULT 0, media_objects int NOT NULL DEFAULT 0,   -- counters whose detail lives in the 8.9 facts
  qc_fault_base int NOT NULL DEFAULT 0, surveys int NOT NULL DEFAULT 0, drp_qty_base int NOT NULL DEFAULT 0, tasks_resolved int NOT NULL DEFAULT 0,
  computed_at timestamptz NOT NULL DEFAULT now(), source_max_received_at timestamptz,
  PRIMARY KEY (business_date, route_id, source)) WITH (fillfactor = 80);
```

Rollups above zone are views (`dw.v_daily_territory` sums zone rows) until a gate fails (T-4-01); an aggregate over route-days is never an average of percentages. The view that picks one source per route-day:

```sql
-- M-67 effective source per route-day and rollups above zone as views (no table until a gate fails, T-4-01)
CREATE VIEW dw.v_agg_daily_route AS
  SELECT DISTINCT ON (business_date, route_id) *
    FROM dw.agg_daily_route
   ORDER BY business_date, route_id, array_position(ARRAY['native','migration_memo','manual','web_entry','migration_aggregate','apsis_parallel'], source);
```

Priority is native, then memo-level migration, then manual paper backfill (D-543), then web entry, then aggregate-only migration, then the nightly Apsis feed for unswitched routes (`apsis_parallel`, D-548); the other rows stay in their own source and are never added (D-40, D-357, D-362). A web-entry row beside app rows raises DQ-58.

### 8.6 The worker: dirty keys, claims, coalescing and dead letters

Ingest marks keys and never aggregates inline, so the sync response is not delayed (R3). The `/sync/batch` transaction ends by calling `dw.enqueue()` for each distinct `(business_date, route)`, `(business_date, outlet)`, `(business_date, user)` it touched, with `ON CONFLICT` incrementing `gen`. About 4 queue rows per batch.

```sql
-- M-38 the dirty queue: ingest marks keys, the worker recomputes them (D-61, D-140, G-sre-17)
CREATE TABLE dw.agg_dirty (
  grain text NOT NULL, business_date date NOT NULL, key1 bigint NOT NULL, key2 bigint NOT NULL DEFAULT 0,
  reason text NOT NULL DEFAULT 'ingest', priority smallint NOT NULL DEFAULT 0,          -- 0 live, 9 rebuild
  gen bigint NOT NULL DEFAULT 1,                                                          -- bumped by every re-enqueue; a finished item is deleted only if gen is unchanged
  enqueued_at timestamptz NOT NULL DEFAULT now(), not_before timestamptz NOT NULL DEFAULT now(),
  claimed_at timestamptz, claimed_by text, claimed_gen bigint, attempts smallint NOT NULL DEFAULT 0, last_error text, dead_at timestamptz,
  PRIMARY KEY (grain, business_date, key1, key2));
CREATE INDEX ON dw.agg_dirty (priority, enqueued_at) WHERE dead_at IS NULL;
CREATE FUNCTION dw.enqueue(p_grain text, p_date date, p_key1 bigint, p_key2 bigint DEFAULT 0, p_delay_s int DEFAULT 0, p_reason text DEFAULT 'ingest', p_priority smallint DEFAULT 0)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, dw AS $$
  INSERT INTO dw.agg_dirty(grain, business_date, key1, key2, reason, priority, not_before)
  VALUES (p_grain, p_date, p_key1, p_key2, p_reason, p_priority, now() + make_interval(secs => p_delay_s))
  ON CONFLICT (grain, business_date, key1, key2) DO UPDATE
     SET gen = dw.agg_dirty.gen + 1, priority = LEAST(dw.agg_dirty.priority, EXCLUDED.priority),
         not_before = LEAST(dw.agg_dirty.not_before, EXCLUDED.not_before), dead_at = NULL $$;
CREATE FUNCTION dw.claim(p_worker text, p_limit int DEFAULT 500, p_claim_timeout_s int DEFAULT 300)
RETURNS SETOF dw.agg_dirty LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, dw AS $$
  UPDATE dw.agg_dirty d SET claimed_at = now(), claimed_by = p_worker, claimed_gen = d.gen, attempts = d.attempts + 1
   WHERE (d.grain, d.business_date, d.key1, d.key2) IN (
         SELECT grain, business_date, key1, key2 FROM dw.agg_dirty
          WHERE dead_at IS NULL AND not_before <= now() AND (claimed_at IS NULL OR claimed_at < now() - make_interval(secs => p_claim_timeout_s))
          ORDER BY priority, enqueued_at LIMIT p_limit FOR UPDATE SKIP LOCKED)
  RETURNING d.* $$;
CREATE FUNCTION dw.complete(p_grain text, p_date date, p_key1 bigint, p_key2 bigint, p_claimed_gen bigint) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, dw AS $$
DECLARE n int;
BEGIN
  DELETE FROM dw.agg_dirty WHERE grain = p_grain AND business_date = p_date AND key1 = p_key1 AND key2 = p_key2 AND gen = p_claimed_gen;
  GET DIAGNOSTICS n = ROW_COUNT;
  IF n = 0 THEN   -- re-enqueued while it ran: release the claim so the next pass takes it at once
    UPDATE dw.agg_dirty SET claimed_at = NULL, claimed_by = NULL WHERE grain = p_grain AND business_date = p_date AND key1 = p_key1 AND key2 = p_key2;
  END IF;
  RETURN n = 1;
END $$;
CREATE FUNCTION dw.fail(p_grain text, p_date date, p_key1 bigint, p_key2 bigint, p_error text, p_max_attempts int DEFAULT 5) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, dw AS $$
  UPDATE dw.agg_dirty SET last_error = p_error, claimed_at = NULL, claimed_by = NULL,
         dead_at = CASE WHEN attempts >= p_max_attempts THEN now() END,                  -- dead items surface on sync-health with a re-drive action
         not_before = now() + make_interval(secs => LEAST(600, 15 * attempts * attempts))
   WHERE grain = p_grain AND business_date = p_date AND key1 = p_key1 AND key2 = p_key2 $$;
-- D-566 (G-qa-100): the four queue functions are SECURITY DEFINER, owned by the NOLOGIN role dw_owner (who owns dw.agg_dirty), with a pinned search_path.
-- Before this, dw.enqueue ran with the caller's rights: api_rw had no INSERT or UPDATE on dw.agg_dirty, so the last statement of every /sync/batch transaction failed with
-- "permission denied", the batch rolled back, nothing synced and the retry loop repeated.
ALTER FUNCTION dw.enqueue(text,date,bigint,bigint,int,text,smallint) OWNER TO dw_owner;
ALTER FUNCTION dw.claim(text,int,int)                                  OWNER TO dw_owner;
ALTER FUNCTION dw.complete(text,date,bigint,bigint,bigint)             OWNER TO dw_owner;
ALTER FUNCTION dw.fail(text,date,bigint,bigint,text,int)               OWNER TO dw_owner;
REVOKE EXECUTE ON FUNCTION dw.enqueue(text,date,bigint,bigint,int,text,smallint), dw.claim(text,int,int), dw.complete(text,date,bigint,bigint,bigint), dw.fail(text,date,bigint,bigint,text,int) FROM PUBLIC;
GRANT  EXECUTE ON FUNCTION dw.enqueue(text,date,bigint,bigint,int,text,smallint) TO api_rw, worker_rw, app_owner;   -- app_owner: the trigger functions of s8.6b run as it
GRANT  EXECUTE ON FUNCTION dw.claim(text,int,int), dw.complete(text,date,bigint,bigint,bigint), dw.fail(text,date,bigint,bigint,text,int) TO worker_rw;
```

#### 8.6b Every writer of `route_day` and the web-entry tables marks a dirty key (D-567, G-qa-102)

Ingest was documented as the only caller of `dw.enqueue()`, plus the `data_void` path. These mutations never enqueued: the first bundle download of the day (`logged_in_at`, D-30), the settle-timeout setting of `sales_submitted_at`, Final Submit, a submit void or reopen (D-539), exception approval, cover assignment and web-entry saves and supersedes. So the TSO app's "not logged in" list and the zone Login % stayed stale until the first batch for that route arrived, and a submit void showed the old Submit % until some batch touched the route-day; T-1-43 ran on a fuzz day made only of batches and could not see it. The fix is structural, so a new write path cannot forget:

| Element | Rule |
| --- | --- |
| Trigger, not convention | An AFTER INSERT OR UPDATE trigger on each of `app.route_day`, `app.supervisor_day`, `app.day_exception`, `app.day_exception_route`, `app.final_submit`, `app.final_submit_route`, `app.submit_void_event`, `app.route_assignment`, `app.web_entry_route_day`, `app.web_entry_line`, `app.web_entry_line_class`, `app.web_entry_outlet_sku` and `app.data_void` calls `app.enqueue_route_day()`, which calls `dw.enqueue('route', business_date, route_id, 0, 0, 'route_day', 0)` once per affected route-day. The function is SECURITY DEFINER, owned by `app_owner`, `SET search_path = pg_catalog, app, dw`, EXECUTE granted to the owners only; the table triggers fire whatever role wrote the row |
| Settle timer | `app.settle_due_route_days()` (SECURITY DEFINER, owned by `app_owner`, EXECUTE granted to `worker_rw` only) sets `sales_submitted_at` for route-days whose settle timeout passed (D-64, `cfg.day.submit_settle_timeout_min`); `worker_rw` therefore needs NO UPDATE privilege on `route_day`. The update fires the trigger above |
| One source for K-01 and K-02 | Login % and Submit % (of logged-in) are read from `dw.agg_daily_zone.logged_in_routes` and `submitted_routes`, which the worker recomputes from `app.route_day` for each dirty `route` key. Their staleness bound is the aggregation lag SLO (p95 60 s, p99 5 min: doc 18 s6.1). SH-01 and SH-02 and the TSO and AMO live tiles read the SAME dw columns on the PRIMARY, as the one declared exception to the replica rule of D-128 (small rows, 1,051 zones, refreshed every 60 s), with their own capacity line in doc 18 s1.8; none reads `app.route_day`, and `web_ro` has no SELECT on `app` (R1(b)) |
| Gates | T-1-155 (a bundle-only login with no batch lights the tile within 60 s; a coverage test that every route_day-writing endpoint and trigger target produces a dirty row), T-3-158 (a submit void changes Submit % with no further batch) |

| Mechanic | Rule | Why |
| --- | --- | --- |
| Poll | every `cfg.agg.poll_interval_s` (5 s, 06:00 to 23:00 Dhaka) and `cfg.agg.poll_interval_night_s` (60 s, NEW key for doc 19); claims up to `cfg.agg.claim_batch` (500, D-140) with `FOR UPDATE SKIP LOCKED`, replicas are safe. DELIBERATE CHANGE from the data lens (20 s and 120 s): D-140 and D-263 fix 5 s and 60 s because the 60 s p95 freshness target of D-135 needs a 5 s poll in the evening storm (D-360) | R5 immediate sync, tile freshness |
| Coalescing | a re-enqueue within the window moves `not_before` earlier only and bumps `gen`; the evening window is `cfg.agg.coalesce_s` (default 20; NEW key for doc 19; measured by T-4-54): 24.5 M aggregate writes a day if written per memo are not allowed (D-61) | hot rows |
| Recompute | one key is recomputed with an upsert plus a delete of vanished rows (`dw.recompute_route_sku`, s8.6); `dw.complete()` deletes the queue row only if `gen` still equals the claimed value, else it releases the claim for an immediate second pass | a late edit during the run is never lost |
| Cascade | route finish enqueues zone and month items; depth is fixed (route, zone, month); a 40-visit batch makes about 1 route, 1 zone and 2 month items | no fan-out |
| Failure | `dw.fail()` sets a back-off of `min(600, 15 x attempts squared)` seconds; at `attempts >= 5` the item is dead-lettered (`dead_at`), shown on the sync-health page with a re-drive action and alerting at the first dead item (G-sre-17) | dead items never leave a tile stale silently |
| Stale claims | a claim older than 300 s is reclaimable, so a worker deploy or crash never strands keys | G-sre-17 |
| Lag | `max(received_at) - max(computed_at)` for today is the freshness gauge, p95 at most 60 s in the evening (`agg_run.max_lag_s`); doc 18 owns the alert | R5 |
| Priority | live 0, rebuild 9: `dw.rebuild(from, to, scope)` enqueues every route-date in range at priority 9 and runs only when the live queue is empty | a KPI-formula fix never starves a dashboard |
| Reconcile | nightly for the last 7 business dates: sum of `net_mtk` and the memo count per zone from `app.memo` against `agg_daily_zone`; a difference is written to `agg_reconcile` and the key re-enqueued; an alert if it survives two runs; aggregate-only migration rows are excluded | self-healing, T-2-04 |

The recompute of one route-day-SKU key, tested on PostgreSQL 16 (an idempotent re-run changes nothing; a late memo and a superseded memo change exactly the rows that moved). Capacity: the first draft created and dropped a temp table per key, which at 100 or more keys a second churns `pg_class` and `pg_attribute` and bloats the catalog; the function above is one statement with no DDL. The worker writes about 9 daily aggregates, about 3 facts and one restatement row per changed key, so ms per key is MEASURED on a synthetic day, not assumed: gate T-4-54 asserts p95 ms per key, catalog bloat (`pg_class` and `pg_attribute` row counts flat across the run) and lag, and the measured value replaces the 15 ms of doc 18 s4.5 (D-528).

```sql
-- M-63 the recompute of one key: replace, never add (D-61, D-356). ONE statement, no temp table: the old rows are a MATERIALIZED CTE (every CTE sees the pre-statement snapshot), so the catalog is never churned by a CREATE and DROP per key (D-528, G-qa-53).
-- Rows that vanished (a superseded memo) are deleted; a change to a day that has already closed is logged (G-analyst-02)
CREATE FUNCTION dw.recompute_route_sku(p_route bigint, p_date date, p_source text DEFAULT 'native', p_reason text DEFAULT 'late_batch') RETURNS int
LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  WITH old AS MATERIALIZED (
    SELECT sku_id, qty_base, memo_count, net_mtk FROM dw.agg_daily_route_sku WHERE business_date = p_date AND route_id = p_route AND source = p_source),
  src AS (
    SELECT l.sku_id, min(l.geo_key) AS geo_key, min(l.product_key) AS product_key,
           sum(l.qty_base) FILTER (WHERE NOT l.is_free)  AS qty_base, sum(l.qty_report) FILTER (WHERE NOT l.is_free) AS qty_report,
           coalesce(sum(l.qty_base) FILTER (WHERE l.is_free), 0) AS free_qty_base,
           count(DISTINCT l.memo_id) AS memo_count, count(DISTINCT l.outlet_id) FILTER (WHERE NOT l.is_free) AS outlets_bought,
           sum(l.gross_mtk) AS gross_mtk, sum(l.discount_mtk) AS discount_mtk, sum(l.net_mtk) AS net_mtk,
           coalesce(sum(l.qty_base) FILTER (WHERE l.server_geo_valid), 0) AS geo_valid_qty_base,
           coalesce(sum(l.qty_base) FILTER (WHERE l.is_suspicious), 0) AS suspicious_qty_base, max(l.received_at) AS max_rx
      FROM dw.fact_memo_line l
     WHERE l.business_date = p_date AND l.route_id = p_route AND l.source = p_source AND l.memo_status = 'active'
     GROUP BY l.sku_id),
  up AS (
    INSERT INTO dw.agg_daily_route_sku AS a (business_date, route_id, sku_id, source, geo_key, product_key, qty_base, qty_report, free_qty_base, memo_count, outlets_bought,
                                             gross_mtk, discount_mtk, net_mtk, geo_valid_qty_base, suspicious_qty_base, computed_at, source_max_received_at)
    SELECT p_date, p_route, s.sku_id, p_source, s.geo_key, s.product_key, coalesce(s.qty_base,0), coalesce(s.qty_report,0), s.free_qty_base, s.memo_count, s.outlets_bought,
           coalesce(s.gross_mtk,0), coalesce(s.discount_mtk,0), coalesce(s.net_mtk,0), s.geo_valid_qty_base, s.suspicious_qty_base, now(), s.max_rx FROM src s
    ON CONFLICT (business_date, route_id, sku_id, source) DO UPDATE
       SET geo_key = EXCLUDED.geo_key, product_key = EXCLUDED.product_key, qty_base = EXCLUDED.qty_base, qty_report = EXCLUDED.qty_report, free_qty_base = EXCLUDED.free_qty_base,
           memo_count = EXCLUDED.memo_count, outlets_bought = EXCLUDED.outlets_bought, gross_mtk = EXCLUDED.gross_mtk, discount_mtk = EXCLUDED.discount_mtk, net_mtk = EXCLUDED.net_mtk,
           geo_valid_qty_base = EXCLUDED.geo_valid_qty_base, suspicious_qty_base = EXCLUDED.suspicious_qty_base, computed_at = now(), source_max_received_at = EXCLUDED.source_max_received_at
    RETURNING a.sku_id),
  gone AS (
    DELETE FROM dw.agg_daily_route_sku a
     WHERE a.business_date = p_date AND a.route_id = p_route AND a.source = p_source AND a.sku_id NOT IN (SELECT sku_id FROM src)
    RETURNING a.sku_id),
  logged AS (                                                                  -- a closed day changed: record exactly what moved (old rows against the recomputed src rows)
    INSERT INTO dw.agg_restatement_log(grain, business_date, key1, key2, reason, old_row, new_row, delta)
    SELECT 'route_sku', p_date, p_route, coalesce(nw.sku_id, o.sku_id), p_reason,
           CASE WHEN o.sku_id IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('qty_base', o.qty_base, 'memo_count', o.memo_count, 'net_mtk', o.net_mtk) END,
           CASE WHEN nw.sku_id IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('qty_base', nw.qty_base, 'memo_count', nw.memo_count, 'net_mtk', nw.net_mtk) END,
           jsonb_build_object('qty_base', coalesce(nw.qty_base,0) - coalesce(o.qty_base,0), 'net_mtk', coalesce(nw.net_mtk,0) - coalesce(o.net_mtk,0))
      FROM old o FULL JOIN (SELECT sku_id, coalesce(qty_base,0) AS qty_base, memo_count, coalesce(net_mtk,0) AS net_mtk FROM src) nw USING (sku_id)
     WHERE p_date < app.dhaka_date(now()) AND (o.qty_base, o.memo_count, o.net_mtk) IS DISTINCT FROM (nw.qty_base, nw.memo_count, nw.net_mtk)
    RETURNING 1)
  SELECT (SELECT count(*) FROM up) + (SELECT count(*) FROM gone) INTO n;
  RETURN n;
END $$;

-- M-77 the "same time yesterday" comparator: one row per zone and Dhaka hour, so a live tile needs no scan of the memo fact (G-field-19)
CREATE TABLE dw.agg_hourly_zone (
  business_date date NOT NULL, zone_id bigint NOT NULL, hour_of_day smallint NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),
  visits int NOT NULL DEFAULT 0, successful_calls int NOT NULL DEFAULT 0, memo_count int NOT NULL DEFAULT 0, net_mtk bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (business_date, zone_id, hour_of_day)) PARTITION BY RANGE (business_date);
CREATE FUNCTION dw.prior_working_day(d date) RETURNS date LANGUAGE sql STABLE AS $$       -- the comparator day: yesterday, or the last working day before a weekend or holiday
  SELECT max(business_date) FROM dw.dim_date WHERE business_date < d AND is_working_day $$;
```

### 8.7 Late-arriving data, edits and restatement

| Event | What is recomputed | What is logged |
| --- | --- | --- |
| A batch lands on D+3 for business date D | only `(route, D)`, then the zone and month of D (nothing about "today" is special); `cfg.agg.late_data_recompute_days` (7) must be at least `cfg.sync.max_backdate_days`, else an accepted late row would never reach the facts and the admin editor warns | `agg_restatement_log` row for every key whose figures changed, reason `late_batch` (T-1-101) |
| A memo is edited | both the superseded memo's date and the new memo's date; the superseding memo counts once (D-53) | reason `memo_superseded` |
| A memo is voided | the memo's route-day; stock and dues reversed | reason `memo_voided` |
| A target revision applies to month M | `agg_month_*` of M for the scope only (bounded) | reason `target_revision`; `snap_month_zone_product` keeps what was reported |
| A back-dated dimension correction | every affected `(outlet, date)`, bounded and shown to the admin first | reason `dim_backdate` |
| A web entry or an import | the route-day with its own `source` | reason `web_entry` or `import` |
| A formula fix | `dw.rebuild()` of the range | reason `rebuild` |

`snap_month_zone_product` is written nightly (about 2 M rows a month: 1,051 zones x about 63 products x 30 nights) so that "what did we report on the 12th" is a lookup; route grain is reproducible from the restatement log. The log is partitioned by month and kept with the audit class (s13.1). Rows of a closed month that arrive with `time_untrusted` are parked by DQ-40 and never reach this path automatically.

### 8.8 Health and staleness

| Check | Source | Alert owner |
| --- | --- | --- |
| Oldest queue row age, queue depth, dead items | `dw.agg_dirty` | doc 18 |
| Worker pass time and `max_lag_s` | `dw.agg_run` | doc 18 |
| Reconcile difference after two runs | `dw.agg_reconcile` | doc 18 |
| Tile staleness shown to the user ("as of hh:mm") | `computed_at` and `source_max_received_at` on the aggregate | doc 15 |
| Partitions present for the next 3 months | `app.partition_policy` | doc 18 |

Proved by: T-0-05, T-1-01, T-1-03, T-1-100, T-1-101, T-1-105, T-2-04, T-2-102, T-2-103, T-3-100, T-4-01, T-4-100.

### 8.9 Capture-to-dw coverage matrix and the capture facts (D-500, G-qa-25, G-qa-40)

Rule (R1): every OFFLINE or QUEUED `app` capture table AND every record type of the doc 17 s2.3 enum (the types a phone uploads, including `device_integrity`, `activity_log`, `consent_accept` and `config_ack`, which are not tables of s5.1; D-559, G-qa-91) has a dw object in the table below, or an exclusion row EX-nn in s8.9.2 signed by the sponsor's delegate. The web role has no SELECT on `app` (D-374, T-4-43), so a report on a captured field that had no dw object would need a new fact and a backfill from `app`: the log re-derivation R1(c) forbids. Before this section the dw had no object for QC fault lines, DRP lines, survey answers, geo fixes, skips, memo voids and prints, cash handover, due disputes, leave, visit plans, feedback, supervisor days, day exceptions, tracking actions, media or content views.

Rules common to every fact of this section (they follow s8.1): the worker is the only writer; a row stores the dimension keys valid on its business date and the natural ids; `source`, `fidelity` and `import_run_id` are present wherever history can be imported; `business_date` is the Dhaka date of trusted capture time; partitioned monthly where the table has more than 10 million rows a year. Late data and recompute: the fact rows of one `(route, business_date)` are replaced in the same worker transaction that recomputes the aggregates of that key (delete by key, insert from `app`, never update in place), so a batch that lands on D+3 converges to the from-scratch result and logs to `agg_restatement_log` only when an aggregate figure moved (s8.7). Supervisor-captured facts that have no route (leave, plan, feedback) are replaced by `(user_id, business_date)` through a `grain = 'user_day'` dirty item.

#### 8.9.1 The matrix: one row per capture table

| App capture table | dw object | Grain | Late-data and recompute rule | Lands | A question it answers |
| --- | --- | --- | --- | --- | --- |
| `visit` | `fact_visit` | visit | s8.4 | 1c | s15 Q3, Q5, Q19 |
| `geo_fix` | `fact_geo_fix` (new) | position sample | monthly partition, hot window `cfg.retention.geo_fix_archive_months` (6) to match the primary; replaced by `(route, date)`; the typed radio columns and the plausibility columns are copied once the worker has enriched the row; the per-visit summary (`device_distance_m`, `server_distance_m`, `gps_accuracy_m`, `is_mock`, `max_implied_speed_mps`) stays on `fact_visit` for ever | 2d | Q30 |
| `visit_skip` | `fact_visit_skip` (new) | skip | key `(skip_id, business_date)`; replaced by route-date; counts in `agg_daily_route.skips` | 2a | Q31 |
| `memo` | `fact_memo` | memo | s8.4 | 1c | Q7, Q8, Q22 |
| `memo_line` | `fact_memo_line` | line | s8.4 | 1c | Q11 |
| `memo_offer` | `fact_memo_offer`, `bridge_memo_offer_version` (new) | offer component; memo x offer version | replaced with the memo | 2a | Q13 |
| `qc_entry`, `qc_entry_line`, `qc_summary_entry` | `fact_qc_line` (new) | memo x SKU x fault type (app QC) or zone or route x SKU x fault type (web QC, `source = 'web_qc'`, never added to app QC, D-40) | replaced by route-date; `after_memo_commit` rows keep their own flag and feed `qc_late_settlement_mtk` | 2a | Q27 |
| `drp_collection`, `drp_collection_line` | `fact_drp_line` (new) | visit x empties SKU, header columns repeated | replaced by route-date | 2a | Q28 |
| `survey_response` | `fact_survey_answer` (new) | visit x question x survey version | replaced by route-date; typed answer columns | 2a | Q26 |
| `content_view` | `fact_content_view` (new) | view | telemetry class, 3 months hot; replaced by user-day | 2e | Q39 |
| `media_object` (photo and file evidence of any record) | `fact_media` (new) | media object | row per object; `uploaded_at`, `upload_lag_s`, `upload_path` (wifi, mobile, fallback), `bytes`, `kind`, linked `ref_type` and `ref_client_uuid`; replaced when the upload ACK arrives (a second pass, not a second row) | 2c | Q29 |
| `due_collection`, `due_adjustment` | `fact_due_ledger`, `fact_due_allocation` | ledger event; allocation | s8.4 | 2b | Q4, Q12 |
| `due_dispute` | `fact_due_dispute` (new) | dispute | status changes replace the row; `age_h` computed daily | 2b | Q34 |
| `cash_handover` | `fact_cash_handover` (new) | handover | one row per client uuid; variance columns typed | 2e | Q33 |
| `stock_movement` | `fact_stock_movement` | movement | s8.2 | 2a | Q17 |
| `attendance_event`, derived `attendance` | `fact_attendance` | event | s8.2; `locality_hint` is derived here, never stored in `app` (D-538) | 2e | GIGO |
| `memo_void`, `print_event` | `fact_memo_event` (new; the "print job" fact) | one row per void, print, reprint, void slip, due receipt, stock slip or day summary | replaced with the memo or the user-day; `kind`, `printer_id`, `template_version`, `user_confirmed`, `retailer_ack`, `slip_printed` | 2b | Q32 |
| `attribution_event` | `fact_attribution_event` (new) | route-day re-attribution | append-only | 2e | Q5 by AMO |
| `outlet_change_request` and history | `fact_outlet_request` | request | s8.2 | 2c | Q16 |
| `task` | `fact_task` | task | status replaces the row | 5c | Q40 |
| `task_event` | `fact_task_event` (new) | event | append-only | 5c | Q40 |
| `distribution_check` and lines | `fact_distribution` | check x brand | s8.2 | 3a | Q15 of the AMO set |
| `call_assessment` | `fact_assessment` | assessment | s8.2 | 3a | Q35 |
| `call_assessment_answer` | `fact_assessment_answer` (new) | assessment x criterion | replaced with the assessment; score 1 to 5 typed | 3a | Q35 |
| `price_compliance_check` | `fact_price_compliance` | check | s8.2 | 3a | Q11 |
| `leave_application`, `leave_event` | `fact_leave` (new) | application, with approval events folded into `approved_at`, `approval_lag_h` | status replaces the row | 3b | Q36 |
| `visit_plan`, `visit_plan_outlet` | `fact_visit_plan` (new) | plan outlet; plan header columns repeated | completion replaces the row; `completed_visit_id` links to `fact_visit` | 3b | Q35 |
| `feedback` | `fact_feedback` (new) | feedback | status replaces the row; `has_media` | 3b | Q37 |
| `supervisor_day` | `fact_supervisor_day` (new) | user x date | replaced by user-day | 3a | Q38 |
| `day_exception`, `day_exception_route` | `fact_day_exception` (new) | exception x route-day | replaced with the exception; `approved_at`, `approval_lag_h` | 2e | Q38 |
| `final_submit`, `final_submit_route`, `final_submit_attempt` | `fact_final_submit` (new) | zone x date; routes snapshot counted | reopen and resubmit append a new version row (`version`) | 3b | Q41 |
| `route_day` submit void and reopen events | `fact_submit_void` (new) | void or reopen event | append-only (D-539) | 3b | Q41 |
| `tracking_action` | `fact_tracking_action` (new) | action | append-only | 4a | Daily Tracking review |
| `redemption`, `loyalty_ledger`, `gift_assignment`, `gift_photo` | `fact_redemption_line`, `fact_loyalty_ledger`, `fact_gift_assignment`, `fact_media` | line, entry, assignment, object | s8.2 | 5a | Q14, Q25 |
| `web_entry_route_day`, `web_entry_line`, `web_entry_line_class`, `web_entry_outlet_sku` | `fact_web_entry_line` | web line | s8.2 | 4c | Web Entry, DSS |
| `risk_signal`, `risk_review` | `fact_risk_signal` (doc 21 s7.1) | signal | s6.7 | 2d | Exceptions report |
| `device_integrity` (a record type of doc 17 s2.3; storage `app.device_integrity`, s6.4) | `fact_device_integrity` (new, s8.9.5) | one row per login or bundle: mock-app present, developer options, rooted hint, Play Integrity verdict, `time_skew_s`, `clock_changed_count`, device model, OS, app version, the readiness block of doc 17 s7.1 | event fact class, 25 months hot; replaced by `(device, business_date, observed_at)` | 2d | Q42 (devices with low trust, by model) |
| `activity_log` (docs/03 audit table; record type of doc 17 s2.3) | `fact_activity` (new), `agg_daily_screen_use` (new) | screen or action event; user x role x screen x day | telemetry class, 3 months hot (`cfg.retention.activity_log_days` 90), the daily rollup kept for ever | 2e | Q43 (which screens do SRs use) |
| `consent_accept` (`app.user_consent`, doc 21 s4.7) | `fact_consent` (new, s8.9.5) | user x policy version | legal class, kept for the life of the account plus the retention of doc 21 s4.8 | 2e | Q44 (acceptance by policy version) |
| `config_ack` | `cfg.config_ack` and `fact_config_change` (existing) | device x version | s8.4 | 1c | reach reports (doc 19 s4.3) |
| `verification_event`, `cover_request`, `day_open`, `day_submit`, `visit_close`, `print_job`, `distribution_check_line`, `outlet_photo_meta`, `gift_photo_meta`, `media_meta`, `qc_entry_line` | folded into `fact_outlet_request`, `fact_day_exception` (kind `cover`, ASSUMPTION: confirmed by the lint), `agg_daily_route` and `fact_final_submit`, `fact_visit`, `fact_memo_event`, `fact_distribution`, `fact_media`, `fact_qc_line` | as the parent rows above | as the parent | as the parent | the generated map names the exact column of each; an unmapped type fails T-0-150 |

#### 8.9.2 Exclusion list (sponsor signature required, EX-nn)

An excluded table or column is not a gap only while its row is signed (name and date) by the sponsor's delegate at the 0b exit; an unsigned row fails T-0-150. The sponsor may sign "not now" for a row, which makes it a planned fact with a sub-milestone.

| Id | Table or column | Reason for exclusion | Where it is reported instead |
| --- | --- | --- | --- |
| EX-01 | `outlet_suggestion` | server-computed hook, delivered empty and off (D-300); no field captured | nothing to report until Q6 is answered; its inputs are in `visit.suggestion_snapshot` (J-7) |
| EX-02 | `support_upload` | an opaque diagnostics file; the row carries no business fact | support desk page (doc 19 P19) |
| EX-03 | `app_release` | a registry the release console owns; not captured on a device | release console |
| EX-04 | `data_void`, `entry_unlock_grant` | audit class: administrator actions, not field capture | audit viewer (doc 19 s8) and `fact_config_change`; `fact_submit_void` for submit voids |
| EX-05 | `ingest_registry`, `sync_conflict`, `sync_rejected`, `sync_batch` (payload columns); EX-05 lists ONLY these sync tables, never a device-captured record type (D-559) | sync evidence, not business capture; payloads are operational blobs (J-8) | `fact_device_day`, `agg_daily_route.rejected_rows`, sync-health |
| EX-06 | technical columns of every capture table: surrogate `id`, `client_uuid` (mapped as `source_uuid`), `payload_sha256`, `sig`, `sig_status`, `sync_batch_id`, `fix_id` (mapped as the join to `fact_geo_fix`) | no business meaning | rtm-check rule 9 treats this pattern as automatic and signed once |

#### 8.9.3 DDL of the facts whose columns are new in kind

```sql
-- M-78 fact_qc_line: the fault mix by SKU, brand, zone and type (D-34, D-500)
CREATE TABLE dw.fact_qc_line (
  qc_line_id bigint NOT NULL, business_date date NOT NULL, source text NOT NULL DEFAULT 'native', fidelity smallint NOT NULL DEFAULT 3, import_run_id bigint,
  source_uuid uuid, memo_id bigint, visit_id bigint, outlet_key bigint, geo_key bigint NOT NULL, user_key bigint, effective_user_key bigint, product_key bigint NOT NULL,
  zone_id bigint, route_id bigint, outlet_id bigint, sku_id bigint NOT NULL, brand_id bigint, fault_type_code text NOT NULL, fault_family text NOT NULL CHECK (fault_family IN ('MFC','MKT')),
  qty_base int NOT NULL, settlement_mtk bigint, after_memo_commit boolean NOT NULL DEFAULT false, app_version text,
  PRIMARY KEY (qc_line_id, business_date, source)) PARTITION BY RANGE (business_date);
-- M-79 fact_survey_answer: POSM and survey answers typed, one column per answer type (D-501)
CREATE TABLE dw.fact_survey_answer (
  answer_id bigint NOT NULL, business_date date NOT NULL, source text NOT NULL DEFAULT 'native', fidelity smallint NOT NULL DEFAULT 3, import_run_id bigint,
  visit_id bigint, outlet_key bigint, geo_key bigint NOT NULL, user_key bigint, zone_id bigint, route_id bigint, outlet_id bigint,
  survey_code text NOT NULL, survey_version int NOT NULL, question_id bigint NOT NULL, question_code text, answer_type text NOT NULL,
  answer_bool boolean, answer_num numeric(14,3), answer_option_code text, answer_text text, has_photo boolean NOT NULL DEFAULT false, media_id bigint, points_awarded int NOT NULL DEFAULT 0,
  PRIMARY KEY (answer_id, business_date, source)) PARTITION BY RANGE (business_date);
-- M-80 fact_geo_fix: positions with typed radio features; six months hot like app.geo_fix (D-500)
CREATE TABLE dw.fact_geo_fix (
  fix_id bigint NOT NULL, business_date date NOT NULL, user_key bigint, geo_key bigint, device_id bigint, app_version text, purpose text NOT NULL, ref_type text, ref_client_uuid uuid,
  fixed_at timestamptz NOT NULL, hour_of_day smallint NOT NULL, lat double precision NOT NULL, lng double precision NOT NULL, accuracy_m double precision, provider text, is_mock boolean NOT NULL, fix_age_ms int, satellites smallint,
  cell_mcc smallint, cell_mnc smallint, cell_tac int, cell_ci bigint, cell_rssi_dbm smallint, cell_neighbour_count smallint, wifi_count smallint, wifi_hash_known_count smallint,
  dist_from_prev_m double precision, secs_from_prev int, implied_speed_mps double precision, plausibility_flags text[] NOT NULL DEFAULT '{}', android_sdk smallint,
  PRIMARY KEY (fix_id, business_date)) PARTITION BY RANGE (business_date);
-- M-81 fact_media: every photo and file, with the upload lag R5 and R4 care about (D-500, D-510)
CREATE TABLE dw.fact_media (
  media_id bigint NOT NULL, business_date date NOT NULL, kind text NOT NULL, ref_type text, ref_client_uuid uuid, user_key bigint, geo_key bigint, zone_id bigint, outlet_id bigint,
  captured_at timestamptz NOT NULL, queued_at timestamptz, uploaded_at timestamptz, upload_lag_s int, upload_path text CHECK (upload_path IN ('wifi','mobile_fallback','mobile_manual')), bytes int, width_px smallint, height_px smallint,
  state text NOT NULL, attempts smallint, PRIMARY KEY (media_id, business_date));
-- M-82 the typed offer versions of a memo: replaces the dw blob offer_version_set (D-501)
CREATE TABLE dw.bridge_memo_offer_version (memo_id bigint NOT NULL, business_date date NOT NULL, offer_id bigint NOT NULL, offer_version_id bigint NOT NULL, offer_version int NOT NULL, PRIMARY KEY (memo_id, business_date, offer_version_id));
```

The remaining new facts follow the same pattern; their columns are fixed here so the migration blocks M-83 to M-99 and the capture map (rule 9) have one source:

| Fact | Columns beyond the common block (keys, dates, provenance) |
| --- | --- |
| `fact_drp_line` | visit_id, memo_id, offer_id, empties_sku_id, brand_id, qty_entered, unit_entered, qty_base, reward_sku_id, reward_qty_base, reward_value_mtk |
| `fact_visit_skip` | skip_id, user_key, outlet_key, route_id, reason_code, captured_at, trusted_at |
| `fact_content_view` | content_item_id, content_kind (av, kv, survey), visit_id, started_at, watch_s, completed |
| `fact_memo_event` | memo_id, kind, at, printer_id, template_version, user_confirmed, retailer_ack, slip_printed, reason_code, fix_id, print_seq |
| `fact_cash_handover` | user_key, zone_id, declared_mtk, counted_mtk, variance_mtk, counted_by, fix_id, handover_at |
| `fact_due_dispute` | outlet_key, user_key, claimed_paid_mtk, claimed_date, claimed_collector_user_id, status, opened_at, closed_at, age_h |
| `fact_task_event` | task_id, event (assigned, resolved, reopened, cancelled), actor_user_key, at |
| `fact_assessment_answer` | assessment_id, rubric_id, criterion_id, score smallint, answer_bool, answer_text |
| `fact_leave` | user_key, leave_type_code, from_date, to_date, days, status, approver_role, applied_at, decided_at, approval_lag_h |
| `fact_visit_plan` | plan_id, planner_user_key, plan_date, route_id, outlet_id, status, completed_at, completed_visit_id, assessment_id |
| `fact_feedback` | user_key, category_code, status, has_media, created_at, handled_at |
| `fact_supervisor_day` | user_key, role, check_in_at, first_sync_at, sales_submitted_at, submitted_with_dues |
| `fact_day_exception` | exception_id, route_id, reason_code, raised_by_user_key, approved_by_user_key, raised_at, approved_at, approval_lag_h, status |
| `fact_final_submit` | zone_id, version, kind, submitted_by_user_key, submitted_at, route_count, routes_set, memo_count_at_submit, net_mtk_at_submit, attempts, reopened_at, reopen_reason |
| `fact_submit_void` | route_id, kind (void, reopen), actor_user_key, confirmer_user_key, reason_code, voided_at, state_before, capture_rows_since |
| `fact_tracking_action` | zone_id, action_by_user_key, bucket, at, note_len |
| `fact_attribution_event` | route_id, from_user_key, to_user_key, reason, requested_by, approved_by |

#### 8.9.5 The three device-captured facts added in round 3 (D-559, G-qa-91; migrations M-123 to M-125)

```sql
-- M-123 one row per login or bundle download (doc 17 s2.3 record type device_integrity; typed, no jsonb)
CREATE TABLE dw.fact_device_integrity (
  device_key bigint NOT NULL, user_key bigint NOT NULL, business_date date NOT NULL, observed_at timestamptz NOT NULL,
  app_version text, os_version text, device_model text, battery_capacity_mah int,
  mock_app_present boolean, developer_options boolean, rooted_hint boolean, play_integrity_verdict text, attestation_level text,
  trust_level smallint, time_skew_s int, clock_changed_count smallint,
  ready_bound boolean, ready_bundle_next_day boolean, ready_printer_paired boolean, ready_test_print boolean, ready_permissions boolean, free_storage_mb int, battery_pct smallint,
  source_uuid uuid NOT NULL, source text NOT NULL DEFAULT 'native', import_run_id bigint,
  PRIMARY KEY (device_key, business_date, observed_at)) PARTITION BY RANGE (business_date);
-- M-124 screen and action events (telemetry class) and the daily rollup that answers "which screens do SRs use" without scanning the events
CREATE TABLE dw.fact_activity (user_key bigint NOT NULL, device_key bigint NOT NULL, business_date date NOT NULL, occurred_at timestamptz NOT NULL, role text NOT NULL,
  screen text NOT NULL, action text NOT NULL, seq int NOT NULL, source_uuid uuid NOT NULL, PRIMARY KEY (user_key, business_date, occurred_at, seq)) PARTITION BY RANGE (business_date);
CREATE TABLE dw.agg_daily_screen_use (business_date date NOT NULL, role text NOT NULL, screen text NOT NULL, action text NOT NULL, users int NOT NULL, events int NOT NULL,
  PRIMARY KEY (business_date, role, screen, action));
-- M-125 consent (employee-location notice, D-120)
CREATE TABLE dw.fact_consent (user_key bigint NOT NULL, policy_version text NOT NULL, accepted_at timestamptz NOT NULL, device_key bigint, business_date date NOT NULL,
  text_sha256 bytea, source_uuid uuid NOT NULL, PRIMARY KEY (user_key, policy_version, accepted_at));
```

Volume: `fact_device_integrity` about 25,000 rows a day (a login or bundle per device, a few per day), `fact_activity` up to 850,000 rows a day at an assumed 100 events per user (the device cap of 50 rows per batch bounds it), 3 months hot, about 75 M rows; the rollup is about 2,000 rows a day. These widths join the table of s8.11 at the next measurement (T-1-150). The analyst questions Q42 to Q44 are added to s15 and to T-4-151 (gate T-4-168).

#### 8.9.4 Capture map and the lint

`/plan/capture-map.yaml` is generated from the migrated DDL AND from the full record-type enum of doc 17 s2.3 (D-559: the first draft generated it from the tables of s5.1 only, which let three device-captured types through with neither a dw object nor an exclusion row) and holds one row per column of every capture table and one row per record type: `app.<table>.<column> -> dw.<object>.<column>` or `excluded: EX-nn`. The lint (T-0-150, rtm-check rule 9 of doc 20 s8.2) fails when (a) an OFFLINE or QUEUED table of s5.1 has neither a dw object nor an EX row, (b) a column has neither a mapped dw column nor an EX-06 pattern match, (c) a dw column is mapped from a column that does not exist, (d) an EX row is unsigned. T-0-06 keeps its schema-contract checks and calls the same lint. Adding a column to a capture table in a later release without a map row fails the build, so "any report at any time" cannot decay.

Proved by: T-0-150, T-0-06, T-4-150, T-4-151.

### 8.10 Sanctioned jsonb exceptions (D-501, G-qa-26)

R1(a) of doc 14 reads "every captured field is a typed column; none only in a JSON blob". These are the only jsonb columns that may exist, each with the typed form reports use and a lifecycle to remove or promote it. T-0-151 fails on any jsonb column outside this table. The sponsor signs the list at the 0b exit (D-501). The list covers captured fields in the `app` and `dw` schemas only; `cfg` values, audit payloads and `stg` import staging are configuration and import data, not captured fields, and are governed by doc 19 and doc 16 s12 (the lint ignores `cfg` and `stg`).

| Id | Column | Why it is jsonb today | Typed form reports read | Lifecycle |
| --- | --- | --- | --- | --- |
| J-1 | `app.memo_offer.basis` | trigger evidence for an offer (what quantity triggered it); never aggregated | `memo_offer.qty_base` and `value_mtk`, `dw.fact_memo_offer` | stays; not exposed to dw |
| J-2 | `app.geo_fix.radio_env` | raw passive cell and hashed Wi-Fi payload as sent | typed `cell_*` and `wifi_*` columns on `app.geo_fix` and `dw.fact_geo_fix` | raw blob dropped with the partition at `cfg.retention.geo_fix_archive_months`; typed columns kept as long as the fact |
| J-3 | `app.offer.rule` (and `offer_version.snapshot`) | the promotion catalogue (about 22 groups) is unknown (Q13) | typed rule tables created at 2a entry (s3.7) | read-only from the catalogue migration, dropped in the next contract release; `snapshot` stays as an audit copy, never read by dw |
| J-4 | `app.outlet.extra` | web "business" and "additional detail" fields until the list is confirmed (G-feat-54, F-WEB-003) | typed columns per confirmed field | each field promoted by a migration when the web Browse Retailer field list is confirmed, by 4c; the blob is then frozen |
| J-5 | `dw.agg_daily_route.flag_counts` | a convenience count per flag for the sync-health tile | `dw.fact_dq_flag` (typed, one row per flag) | stays as a derived cache; never the source of any report |
| J-6 | `app.memo.offer_version_set` | the device's snapshot of the offer versions it applied | `app.memo_offer.offer_version_id`, `dw.bridge_memo_offer_version` | stays as evidence; not exposed to dw |
| J-7 | `app.visit.suggestion_snapshot` | inputs of the geo-triggered suggestion hook, off (D-300) | none until Q6 is answered | typed when the formula is known |
| J-8 | operational evidence: `sync_batch.device_counts`, `accepted_counts`, `server_totals`, `sync_rejected.payload`, `sync_conflict.second_payload`, `bundle_snapshot.row_counts`, `fact_device_day.sync_failures`, `route_day.submit_device_counts`, importer `stg.*` raw and counts | evidence of a transfer, not a business fact | `fact_device_day` and sync-health typed columns | stays; excluded from the capture map (EX-05) |

### 8.11 Row widths, growth and storage (D-520, G-qa-44)

The first sizing (about 295 bytes a row, 1.26 GB a day, 585 GB in year 1) did not follow the widths of the DDL in this document. The table below is the bottom-up estimate from the design volumes of s8.4 and the column counts of s5 and s8. Every width is an ASSUMPTION until gate T-1-150 measures `pg_total_relation_size / rows` on the 1c to 2e synthetic load and rewrites this table.

| Object | Rows a day (design) | Bytes per row with indexes (ASSUMPTION) | MB a day |
| --- | --- | --- | --- |
| `app.visit` | 500,000 | 640 | 320 |
| `app.memo` | 460,000 | 710 | 327 |
| `app.memo_line` | 1,100,000 | 220 | 242 |
| `app.memo_offer` | 600,000 | 170 | 102 |
| `app.qc_entry`, `qc_entry_line`, `drp_collection` and lines, `survey_response`, `content_view` | about 1.6 M | about 110 | 180 |
| `app.geo_fix` (with `radio_env` and typed radio columns) | 525,000 | 420 | 220 |
| `app.due_collection`, `stock_movement`, `attendance_event`, `memo_void`, `print_event`, `task`, supervisor tables, events | about 0.35 M | 250 | 88 |
| `app.sync_batch` and telemetry (response blob kept 48 h) | 257,000 batches | 700 | 180 |
| **app total** | | | **about 1,660** |
| `dw.fact_visit` | 500,000 | 330 | 165 |
| `dw.fact_memo` (70 columns) | 460,000 | 600 | 276 |
| `dw.fact_memo_line`, `fact_memo_offer`, `fact_dq_flag` | 2.0 M | 150 | 316 |
| the s8.9 capture facts (`fact_geo_fix`, `fact_qc_line`, `fact_survey_answer`, the rest) | about 2.0 M | 110 | 220 |
| daily aggregates (`agg_daily_route_sku` 340,000 x 160, `agg_daily_outlet` and `_brand` about 700,000 x 140, `agg_daily_user_sku` 170,000 x 120, route, zone, hourly, snapshots) | about 1.3 M | about 150 | 200 |
| **dw total** | | | **about 1,180** |
| **app and dw per day** | | | **about 2,800 (design); 2,300 if memos per call stay at the July average** |

| Quantity | Value | Basis |
| --- | --- | --- |
| Year-1 growth | about 1.0 TB (2.8 GB x 365) plus about 20 GB for the imported Apsis history (37.3 M sales rows at about 200 bytes) plus the registry steady state of about 12 GB (135 M rows x 90 bytes) | the table above |
| Steady state after month 25 | about 1.6 TB: app 13 months hot (about 650 GB), dw 25 months hot (about 900 GB), plus indexes of the small tables | `cfg.retention.transactions_hot_months` 13, `event_fact_months` 25 |
| Registry | about 135 M rows and about 12 GB at the 45-day prune (the "1.2 B rows, 120 GB" of the first draft assumed no prune, doc 18 s4.6, OI-18-04) | s6.1 |
| Lever | `cfg.retention.event_fact_months` 25 to 13 for `fact_memo_line` and the capture facts halves the dw steady state; the Parquet archive keeps the rest (D-371) | s13.1 |

The single sizing decision (D-568, G-qa-103): the production server that receives the Apsis import is created at 7a with 1,024 GiB of Premium SSD v2 and the matching IOPS tier, on the primary, the in-region replica and the cross-region replica alike (doc 18 s1.9 and s3.1 carry the same number; rtm-check rule 14 compares them). Arithmetic in GiB (1 GB = 0.931 GiB; 2.8 GB a day = 2.6 GiB): usable before the 95 percent read-only point is 972 GiB; the import is 19 to 121 GiB (20 to 130 GB) and the registry 11 GiB; at the full-fleet design rate the 60 percent alert (614 GiB) falls after about 185 days (worst import) to 225 days (small import), the 70 percent alert (717 GiB) after about 225 days and read-only after about 323 days. The first grow, to 2,048 GiB, is therefore planned by month 6 of full-fleet running (the 60 percent alert is the trigger; D-131 and its change-log row, doc 18 FM-29 and OI-18-03 are corrected to month 6), and 2,048 GiB covers the steady state of about 1.6 TB to month 25. The alert table adds 60 percent and "projected full within 90 days" next to 70 and 90 percent. Re-run this table from the measured widths at 2e (T-1-150, T-2-150). Proved by: T-1-150, T-2-150.

## 9 KPI definitions and report data sources

Every number on every surface is a query on `dw`. This section fixes the formulas (verbatim from the plan), gives them as SQL, states the per-surface differences that the manuals show, and maps every report to a dw-only query (R1, D-365, G-data-22).

### 9.1 Rules that hold for every KPI

| Rule | Statement |
| --- | --- |
| Numerators first | Aggregates store counts and sums; a rollup adds numerators and denominators and divides once. An average of percentages is wrong and never used (D-364) |
| Dash | `dw.pct(n, d)` is NULL when `d` is NULL, zero or negative; the UI renders NULL as a dash, never 0 and never infinity (D-28, D-50, DQ-33) |
| Day key | Day-level rollups key off the Dhaka business date; "today" is the business date of trusted time |
| Non-working day | `target_outlets = 0` and every percentage is NULL, so Eid and Friday are not red (D-28, G-analyst-10) |
| Names | The name is always "Submit % (of logged-in)" or "Day-completion %". No bare "retention" on a tile, no "STD total" across categories, and a quantity always names its unit (sticks, pieces, dozens, boxes) |
| Fidelity | A KPI reads rows of `fidelity >= the report's minimum`; migrated aggregate-only months never feed a route-level or SR-level KPI (D-366) |
| One engine | The app home tiles, the TSO and AMO dashboards, the web and Power BI call the same views; "Live Strike Rate" in the app is the same formula on today's `agg_daily_route` (worker lag at most 60 s) |

### 9.2 The canonical KPI table (K-01 to K-18)

| KPI id | Canonical name (UI label in quotes) | Code (API and dw field) | Formula | Basis and notes | Decision |
| --- | --- | --- | --- | --- | --- |
| K-01 | Login % | login_pct | logged_in_routes / target_routes | target_routes = planned sr-kind routes on a working day; logged-in per the login event (first bundle download that Dhaka day, or an offline day_open flagged offline_start). Source: `dw.agg_daily_zone.logged_in_routes`, recomputed on the route-day trigger of s8.6b (lag p95 60 s), read on the primary by the live tiles | D-44, D-29, D-30, D-567 |
| K-02 | "Submit % (of logged-in)" | submit_pct_of_logged_in | sales_submitted_routes / logged_in_routes | PARITY: TSO "Bikroy Joma Status", AMO live tile, web "Login/Submit Status"; a route counts as submitted only after the settle rule (server totals at or above device counts, or 30 min timeout); source `dw.agg_daily_zone.submitted_routes`, recomputed on the route-day trigger, so a submit void changes it with no further batch (D-567) | D-45, D-64, D-567 |
| K-03 | "Day-completion %" | day_completion_pct | sales_submitted_routes / target_routes | The docs/10 definition kept as secondary on Daily Tracking and sync-health; never labelled "Submit %" without the qualifier. cfg.kpi.submit_pct_denominator picks which one a tile captioned "Submit %" shows (default logged_in_routes); the API always returns both with their basis | D-45 |
| K-04 | CPR ("Strike rate") | cpr_pct | successful_calls / target_outlets | successful call = sr_call visit with at least one active memo of net_mtk > 0 or qty_base > 0; zero-sale and abandoned visits excluded | D-46, D-57 |
| K-05 | Target outlets (day) | target_outlets | active outlets on routes planned that day, plus visited unplanned outlets shown as unplanned_visits | plan fixed at bundle time; 0 on a non-working day | D-57, D-28 |
| K-06 | Memo count | memo_count | active memos with line_count > 0 | superseded, void and zero memos excluded; the superseding memo counts once | D-53 |
| K-07 | STD (API STT) | std_qty | sum of qty_base in the SKU's base unit | no cross-category total; values in net_mtk; Lighter report unit differs by surface | D-49 |
| K-08 | Net sales value | net_mtk | gross - offer_discount - drp_discount - qc_deduction | totals from unrounded line values, rounded once to the paisa | D-18, D-19 |
| K-09 | Geo-validation % | geo_valid_pct | server_geo_valid calls / (sr_call visits - abandoned) | force sales photo-valid and not in the numerator; mocked fix never valid; companions photo_valid %, mock %, suspicious %, geo_mismatch % | D-48 |
| K-10 | BSR | bsr_pct | memos containing the brand / total active memos | secondary "brand reach" = outlets that bought the brand / target outlets; leaderboard uses the primary | D-47 |
| K-11 | Achievement % | achievement_pct | achieved / target | card and bar: printed value capped at 100 on AMO and TSO; detail tables uncapped, 2 decimals; SR home card uncapped; target <= 0 shows a dash; remaining = max(target - achieved, 0) | D-50 |
| K-12 | Till-date target | tilldate_target | per-surface basis: TSO Target Status = ceil(item target x elapsed calendar days / days in month) summed (26/30); AMO Team Performance about 25/30; AMO Sales Summary Up To Now = monthly x 15/17; SR ADS = working-day split (14 elapsed, 11 remaining) | keys cfg.kpi.tilldate_basis.<surface> and cfg.kpi.tilldate_rounding.<surface>; one global basis is REJECTED (R-rows in s2.13) | D-51 |
| K-13 | ADS, TADS, PADS, RADS | ads, tads, pads, rads | ADS = achieved / elapsed_working_days(as_of); TADS = target / elapsed_working_days(as_of) (the meaning of "target ADS" rests on one data point and stays MQ-27); RADS = remaining / remaining_working_days(as_of), both rounded half up; the day counts come from `dw.dim_date` for the as-of date (working days, Friday off, holidays as data), never from constants. The numbers 14 and 11 are the elapsed and remaining working days of the 2026-04-18 manual capture and exist only as the golden fixture "as of 2026-04-18 with the April 2026 calendar" (500 gives TADS 36 and RADS 45); PADS undefined until a non-zero sample is captured (D-58, G-qa-66) | local computation from bundle target plus local sales | D-58 |
| K-14 | Visited / Non-visit / No-sale | visited, non_visit, no_sale | visited = opened, non-abandoned visits; non_visit = target_outlets - visited; no_sale = zero-sale calls | SR home strip | D-56 |
| K-15 | Final-submit status | final_submit_status | zones final-submitted / zones having target routes, with the list of remaining zones. Named counters of the web dashboard (D-536): Total Zone = active zones in scope; Total Service Zone = zones with at least one target route that day (the denominator of this KPI); Remaining = Total Service Zone minus final-submitted; Final Submit % (the ring) = final-submitted zones / Total Service Zone | once per zone per day; late batches flagged after_final_submit; one derivation only, no manual flag | D-55, D-536 |
| K-16 | Retention candidate | retention_candidate_pct | outlets that bought the category in M-1 and M / outlets that bought in M-1 | never labelled "retention" on a tile until Q10 is answered | D-54 |
| K-17 | User-level efficiency (SR Efficiency, GIGO, risk score) | sr_efficiency, gigo | computed on coalesce(acting_for_user_id, user_id) | route-level KPIs include every active memo or visit regardless of seller; AMO control-call sales counted as amo_successful_calls and never inflate K-04 | D-26 |
| K-18 | Overdue / dues ageing | dues_aged_* | outstanding due by memo date bucket (0-7, 8-30, 31-60, 61+ days; ASSUMPTION buckets, cfg.kpi.dues_buckets) from the dues allocation ledger | opening balances land in an "opening" bucket, not aged | D-37 |

Colour bands (D-52): two sets, not one. cfg.kpi.bands is the 4-band achievement set (>=100, 90 to 100, 80 to 90, <80) for KPI tables; cfg.kpi.bar_bands is the bar colour set on AMO and TSO cards (green from 80, amber from 40, red below 40; the 40 boundary is a placeholder pending one capture between 25 and 55 percent).

### 9.3 KPI to dw objects

| KPI | dw objects and columns | SQL object |
| --- | --- | --- |
| K-01 Login % | `agg_daily_zone.logged_in_routes / target_routes`; route level `agg_daily_route.logged_in`, `logged_in_at`, `login_source`, `offline_start` | `v_kpi_zone_day.login_pct` |
| K-02 "Submit % (of logged-in)" | `submitted_routes / logged_in_routes`; a route is submitted only after the settle rule (s11.4) | `v_kpi_zone_day.submit_pct_of_logged_in` |
| K-03 "Day-completion %" | `submitted_routes / target_routes`; the API returns both pairs with their basis; `cfg.kpi.submit_pct_denominator` picks the tile caption | `v_kpi_zone_day.day_completion_pct` |
| K-04 CPR | `successful_calls / target_outlets` | `v_kpi_zone_day.cpr_pct` |
| K-05 Target outlets | `agg_daily_route.target_outlets` = the plan frozen at bundle time plus distinct unplanned outlets visited (D-57); `unplanned_visits` reported beside it | column |
| K-06 Memo count | `memo_count` of active memos with `line_count > 0` (superseded, void and zero memos out; the superseding memo once) | column |
| K-07 STD | `agg_daily_route_sku.qty_base` (one SKU or one category only); `qty_report = qty_base x report_factor` | `agg_daily_zone_category` |
| K-08 Net sales value | `net_mtk` (gross - offer - DRP - QC, one rounding to the paisa) | column |
| K-09 Geo-validation % | `geo_valid_calls / (visits - abandoned_calls)`; a visit with `location_basis = provisional` is outside the numerator until its location request is approved, then re-evaluated (D-487, OI-21-10); companions photo, mock, suspicious, geo-mismatch | `v_kpi_zone_day` |
| K-10 BSR | `agg_daily_outlet_brand.memo_count` over zone memos (primary), outlets bought over target outlets (brand reach) | `v_kpi_brand_zone_day` |
| K-11 Achievement % | `agg_month_zone_product` with `dim_target` basis; card capped, detail uncapped | `v_kpi_achievement_zone_month` |
| K-12 Till-date target | per-surface basis keys (s9.5) | `dw.tilldate_target()` |
| K-13 ADS, TADS, PADS, RADS | working-day split of `dim_date` | `dw.sku_target_progress()` (computed on the device from the bundle; the server function is the test oracle) |
| K-14 Visited, non-visit, no-sale | `outlets_visited`, `target_outlets - outlets_visited`, `zero_sale_calls` | `v_kpi_route_strip` |
| K-15 Final-submit status | `agg_daily_zone.final_submitted`, zones with `target_routes > 0` (`total_zones`, `service_zones`, `final_submitted_zones`, `remaining_zones`, `final_submit_pct` are columns of `v_daily_territory`, D-536) | `v_daily_territory.final_submitted_zones / zones_with_target_routes` |
| K-16 Retention candidate | `agg_month_outlet_category` M-1 and M | `v_kpi_retention_candidate` |
| K-17 SR Efficiency, GIGO | `agg_daily_user` (effective user), `fact_attendance` | `agg_daily_user` columns |
| K-18 Dues ageing | `agg_memo_due_open` from the FIFO allocation | `v_kpi_dues_ageing_zone`, `dw.dues_bucket()` |

### 9.4 The SQL (tested on PostgreSQL 16)

K-01 to K-04, K-09, K-15 for a zone-day, the till-date function (K-12) and the SKU target progress (K-13):

```sql
-- percent on the 0 to 100 scale; NULL (a dash on screen) for a zero, negative or missing denominator (D-28, D-50)
CREATE FUNCTION dw.pct(n numeric, d numeric) RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT 100 * dw.safe_div(n, d) $$;

-- K-01 to K-04, K-09, K-15 for one zone-day (rollups sum numerators first, then divide: never an average of percentages)
CREATE VIEW dw.v_kpi_zone_day AS
SELECT business_date, zone_id, territory_id, division_id, wing_id,
       dw.pct(logged_in_routes,  target_routes)        AS login_pct,                    -- K-01
       dw.pct(submitted_routes,  logged_in_routes)     AS submit_pct_of_logged_in,      -- K-02
       dw.pct(submitted_routes,  target_routes)        AS day_completion_pct,           -- K-03
       dw.pct(successful_calls,  target_outlets)       AS cpr_pct,                      -- K-04
       dw.pct(geo_valid_calls,   visits - abandoned_calls) AS geo_valid_pct,            -- K-09
       dw.pct(photo_valid_calls, visits - abandoned_calls) AS photo_valid_pct,
       dw.pct(mock_calls,        visits - abandoned_calls) AS mock_pct,
       dw.pct(suspicious_calls,  visits - abandoned_calls) AS suspicious_pct,
       final_submitted                                  AS final_submitted               -- K-15 per zone
  FROM dw.agg_daily_zone;

-- K-12 till-date target: the basis is a per-surface key (D-51); the caller sums per item
CREATE FUNCTION dw.tilldate_target(p_basis text, p_rounding text, p_monthly numeric, p_as_of date, p_num numeric DEFAULT NULL, p_den numeric DEFAULT NULL)
RETURNS numeric LANGUAGE sql STABLE AS $$
  WITH d AS (SELECT day_of_month, days_in_month, working_day_of_month, working_days_in_month FROM dw.dim_date WHERE business_date = p_as_of),
       x AS (SELECT CASE p_basis
               WHEN 'calendar_incl_today'        THEN p_monthly * day_of_month / days_in_month                    -- TSO Target Status: 26/30
               WHEN 'calendar_through_yesterday' THEN p_monthly * (day_of_month - 1) / days_in_month              -- AMO Team Performance: about 25/30
               WHEN 'working_incl_today'         THEN p_monthly * working_day_of_month / working_days_in_month
               WHEN 'fixed_ratio'                THEN p_monthly * p_num / p_den                                    -- AMO Sales Summary Up To Now: 15/17
             END AS v FROM d)
  SELECT CASE p_rounding WHEN 'ceil' THEN ceil(v) WHEN 'half_up' THEN round(v) ELSE round(v, 2) END FROM x $$;

-- D-58: SR SKU Target and Achievement. Elapsed and remaining selling days come from dim_date (working days, Friday off, holidays as data)
CREATE FUNCTION dw.sku_target_progress(p_target numeric, p_achieved numeric, p_as_of date)
RETURNS TABLE (ads numeric, tads numeric, rads numeric, remaining numeric, achievement_pct numeric) LANGUAGE sql STABLE AS $$
  WITH d AS (SELECT working_day_of_month AS elapsed, working_days_in_month - working_day_of_month AS remain FROM dw.dim_date WHERE business_date = p_as_of)
  SELECT dw.safe_div(p_achieved, elapsed)::numeric, round(dw.safe_div(p_target, elapsed)),
         round(dw.safe_div(GREATEST(p_target - p_achieved, 0), remain)), GREATEST(p_target - p_achieved, 0), dw.pct(p_achieved, p_target)
    FROM d $$;
```

K-10, K-11, K-14, K-16 and K-18:

```sql
-- K-10 BSR: primary = memos containing the brand / active memos of the zone; secondary = brand reach. Memo-level only: fidelity 3 (D-47, D-366)
CREATE VIEW dw.v_kpi_brand_zone_day AS
SELECT b.business_date, g.zone_id, b.brand_id,
       sum(b.memo_count)                    AS memos_with_brand,  z.memo_count AS zone_memos,
       dw.pct(sum(b.memo_count), z.memo_count)                     AS bsr_pct,
       count(DISTINCT b.outlet_id)          AS outlets_bought,   z.target_outlets,
       dw.pct(count(DISTINCT b.outlet_id), z.target_outlets)       AS brand_reach_pct
  FROM dw.agg_daily_outlet_brand b
  JOIN dw.dim_geo g USING (geo_key)
  JOIN dw.agg_daily_zone z ON z.business_date = b.business_date AND z.zone_id = g.zone_id
 WHERE b.fidelity = 3
 GROUP BY b.business_date, g.zone_id, b.brand_id, z.memo_count, z.target_outlets;

-- K-11 achievement: card capped at 100, detail uncapped with two decimals, a zero or negative target is a dash, remaining never below zero (D-50, D-52)
CREATE FUNCTION dw.band(p numeric, p_bounds numeric[] DEFAULT '{100,90,80}') RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p IS NULL THEN NULL WHEN p >= p_bounds[1] THEN 'ge100' WHEN p >= p_bounds[2] THEN '90to100' WHEN p >= p_bounds[3] THEN '80to90' ELSE 'lt80' END $$;
CREATE VIEW dw.v_kpi_achievement_zone_month AS
SELECT month, zone_id, product_level, product_id, source, fidelity, qty_base_mtd, std_target_current AS target_base, through_date,
       round(dw.pct(qty_base_mtd, std_target_current), 2)                 AS achievement_pct,         -- detail tables and the SR home card: uncapped
       round(dw.pct(qty_base_mtd, std_target_current) - GREATEST(dw.pct(qty_base_mtd, std_target_current) - 100, 0), 2) AS achievement_pct_card,   -- AMO and TSO cards and bars: capped at 100; LEAST() would turn a dash into 100
       GREATEST(std_target_current - qty_base_mtd, 0)                     AS remaining,
       dw.band(dw.pct(qty_base_mtd, std_target_current))                  AS band
  FROM dw.agg_month_zone_product;

-- K-16 retention candidate: bought the category in month M-1 and again in M, over bought in M-1. Not labelled "retention" on a tile until Q10 is answered (D-54)
CREATE VIEW dw.v_kpi_retention_candidate AS
SELECT p.month AS base_month, p.category_id, count(*) AS bought_in_base, count(c.outlet_id) AS bought_again,
       dw.pct(count(c.outlet_id), count(*)) AS retention_candidate_pct
  FROM dw.agg_month_outlet_category p
  LEFT JOIN dw.agg_month_outlet_category c
         ON c.outlet_id = p.outlet_id AND c.category_id = p.category_id AND c.month = (p.month + interval '1 month')::date AND c.qty_base > 0
 WHERE p.qty_base > 0
 GROUP BY p.month, p.category_id;

-- K-18 dues ageing: the bucket bounds are cfg.kpi.dues_buckets (ASSUMPTION 0-7, 8-30, 31-60, 61+); an opening balance sits in its own bucket and is not aged
CREATE FUNCTION dw.dues_bucket(p_age_days int, p_is_opening boolean DEFAULT false, p_bounds int[] DEFAULT '{7,30,60}') RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN p_is_opening THEN 'opening'
              WHEN p_age_days <= p_bounds[1] THEN '0-' || p_bounds[1]
              WHEN p_age_days <= p_bounds[2] THEN (p_bounds[1] + 1) || '-' || p_bounds[2]
              WHEN p_age_days <= p_bounds[3] THEN (p_bounds[2] + 1) || '-' || p_bounds[3]
              ELSE (p_bounds[3] + 1) || '+' END $$;
CREATE VIEW dw.v_kpi_dues_ageing_zone AS
SELECT g.zone_id, o.bucket, sum(o.outstanding_mtk) AS outstanding_mtk, count(*) AS open_memos, count(DISTINCT o.outlet_id) AS outlets
  FROM dw.agg_memo_due_open o JOIN dw.dim_geo g USING (geo_key) WHERE o.outstanding_mtk > 0 GROUP BY g.zone_id, o.bucket;

-- K-14 the SR home strip, from the route-day row: visited, non-visit, no-sale (D-56); K-05 target outlets already include unplanned visited outlets (D-57)
CREATE VIEW dw.v_kpi_route_strip AS
SELECT business_date, route_id, source, target_outlets, outlets_visited AS visited,
       GREATEST(target_outlets - outlets_visited, 0) AS non_visit, zero_sale_calls AS no_sale, unplanned_visits,
       dw.pct(successful_calls, target_outlets) AS cpr_pct
  FROM dw.agg_daily_route;
```

Fixtures run against these objects (all checked; the arithmetic is by hand in the gate):

| Fixture | Input | Expected | Source |
| --- | --- | --- | --- |
| Login % | 1 of 4 target routes | 25 | TSO and web (D-44) |
| CPR | 6 successful of 60 target outlets; the web tile 1 of 43 | 10; 2.3 | UI-SR-09; docs web tile |
| Zero target | achieved 136, target 0 | dash (NULL), remaining 0 | D-50 |
| Negative target | target -20 (migration slip) | dash and `target_flag = invalid` at read; CHECK refuses new rows | D-50, DQ-31 |
| Card cap | 1,500 of 118 | detail 1271.19, card 100 | D-50 |
| BSR | 2 of 6 zone memos contain brand 1 | 33.33; brand reach 2 of 60 outlets 3.33 | D-47 |
| Retention candidate | 3 outlets bought category in September, 2 again in October | 66.67 | D-54 |
| Till-date, AMO Sales Summary | monthly 100,000 at 15/17 | 88,235.29 | verified, V-targets |
| Till-date, TSO Target Status | category totals 3,944 / 1,425 / 526 / 510 at 26/30 | 3,418.13 / 1,235.00 / 455.87 / 442.00 before rounding; printed 3,420 / 1,236 / 456 / 442 because each item is rounded up before the sum (the golden test needs the item targets) | verified, V-targets |
| Till-date, AMO Team Performance | monthly 2,972,900 at 25/30 | about 2,477,417 against the printed 2,475,477 (within 0.1 percent; AMO rounding unknown) | V-targets |
| TADS and RADS, April 2026 (Friday off, 14 April assumed a holiday: ASSUMPTION), as of 18 April | target 500; target 900 | 36 and 45; 64 and 82 (14 elapsed working days, 11 remaining) | D-58 |
| Dues buckets | ages 3, 8, 30, 45, 61 days; an opening balance | 0-7, 8-30, 8-30, 31-60, 61+; opening | D-37 |

The AMO "136 of 0" row of the AMO report has no settled reading (a zero target with sales 136 prints a dash under D-50; the manual's value is ambiguous); it stays an open item (OI-16-07).

### 9.5 Per-surface differences (D-51, D-52, D-50)

There is no single till-date basis. The basis and its rounding are keys per surface, with the observed behaviour as the default:

| Surface | Figure | Basis key `cfg.kpi.tilldate_basis.<surface>` | Rounding key | Cap on printed percent | Unit shown |
| --- | --- | --- | --- | --- | --- |
| TSO app, Target Status | till-date target, achievement | `.tso_target_status` = `calendar_incl_today` (26/30 on the capture) | `cfg.kpi.tilldate_rounding.tso_target_status` = `ceil` per item, then summed | 100 on cards and bars | category base units; Lighter in boxes via `report_factor` |
| AMO app, Team Performance | till-date tab | `.amo_team_performance` = `calendar_through_yesterday` (about 25/30) | `.amo_team_performance` = `none` (rounding unknown) | 100 on cards | sticks, pieces, dozens |
| AMO app, Sales Summary Up To Now | printed target | `.amo_sales_summary` = `fixed_ratio` 15 over 17 (meaning of 17 and 15 unknown; confirm with the business, MQ-09, MQ-33) | `half_up`, 2 decimals | none, detail table | sticks |
| SR app, SKU Target and Achievement | ADS, TADS, PADS, RADS | `.sr_ads` = `working_incl_today` (14 elapsed, 11 remaining) | `half_up` for TADS and RADS | none (SR home card uncapped) | the SKU's base unit |
| Web dashboard and Daily Tracking | achievement ring and 4-band buckets | no `web` key: D-51 names four surfaces; the web month-to-date figure uses the basis of the equivalent app surface (`.tso_target_status` for territory and zone views, ASSUMPTION: the web basis is not evidenced) | the rounding of that surface | 100 on the ring | Lighter in pieces, Match in dozens (D-49) |

The canonical enum of `cfg.kpi.tilldate_basis.<surface>` is exactly the value set of `dw.tilldate_target`: `calendar_incl_today` (TSO 26/30), `calendar_through_yesterday` (AMO about 25/30), `working_incl_today` (SR 14 elapsed and 11 remaining) and `fixed_ratio` (AMO report 15/17); doc 19 uses these four spellings and the registry-versus-SQL lint of T-6-60 fails on a value that exists in only one document (D-525, G-qa-50). The basis value for `fixed_ratio` is an object (`{"basis": "fixed_ratio", "num": 15, "den": 17}`); ASSUMPTION, doc 19 to confirm the key shape. Bar colours (`cfg.kpi.bar_bands`: green from 80, amber from 40, red below 40, the 40 boundary a placeholder) and the 4-band set (`cfg.kpi.bands`) are two keys; `dw.band()` takes the bounds as a parameter so every page agrees.

Units (D-16, D-17, D-49): cigarette and bidi in sticks, lighter in pieces (web) or boxes (TSO app, `report_factor`), match in dozens (MUST-CONFIRM by 2a for lighter and match). A cross-category total is never shown as a quantity: it is shown in `net_mtk`. A zero-volume line is not a sale (D-249).

### 9.6 Dashboard date semantics (G-field-19)

| Question | Rule |
| --- | --- |
| Default date | today's Dhaka business date; the date range Filter (orange, top right) defaults to today with MTD optional |
| Non-working day | a banner names the reason (Friday off, Eid, an exception, an emergency declaration) and every percentage is a dash; the page does not silently show yesterday |
| One default, no rule key | `today` is the single default for every tile, sales tiles included; the key `cfg.ops.dashboard_default_date_rule` ("last Final Submitted for sales tiles") is RETIRED because Final Submit is a late-evening act and the rule would show yesterday all through the selling day, the opposite of R5 (D-544, G-qa-75) |
| Date stamp and reason chip | every tile shows its business date and an "as of hh:mm" stamp (`computed_at`); a chip from the fixed enum `replica_lag`, `agg_stale`, `non_working_day`, `no_data_yet`, `degraded_mode` explains any tile that is not live today; the API returns `date_reason` beside every figure and the support page (doc 19 P19) reads the same field |
| "Same time yesterday" | the comparator day is `dw.prior_working_day(d)`; the value is cumulative to the current Dhaka hour from `dw.agg_hourly_zone`, labelled "up to hh:00" |
| "Live" | today's aggregate with a "data as of hh:mm" stamp from `computed_at`; never a promise of real time |
| After 17:00 | the Daily Tracking "take action" appears (cfg.day.take_action_after) and writes `app.tracking_action` |

### 9.7 ReportQuery and the report registry

One parameter object serves every report, so a new report adds a row and a view, not a code path (D-365, G-man-095). The client sends narrowing selectors only: the server intersects every node it receives with the token's reach and answers 403 `out_of_scope` (and a `security_event`) for a node outside it; a missing selector means the whole reach (CLAUDE.md constraint 4).

```jsonc
{
  "report": "std-memo",
  "period": { "from": "2026-04-01", "to": "2026-04-12" },          // or { "date": "2026-04-12" } or { "month": "2026-04" }
  "dateGrouping": "total",                                         // total | day | week | month
  "location": "wing",                                              // group-by level: wing | division | territory | zone | route | outlet
  "geo": { "wing": [1], "division": [], "territory": [], "zone": [], "route": [] },   // narrowing only
  "category": [1, 2], "productType": "sku", "products": [11, 12], "activeStatus": "all",
  "classificationType": "total", "subChannels": [],
  "reportType": "std_memo", "fieldForceType": "sr",
  "stdCriteria": { "op": ">", "value": 0 }, "memoCriteria": { "op": ">", "value": 0 },
  "outletCode": null, "extras": {},
  "output": { "format": "json", "page": 1, "pageSize": 10, "sort": [{ "col": "route_code", "dir": "asc" }] }
}
```

Rules: ISO dates only (month pickers show "Month YYYY"); `format` is `json`, `xlsx`, `pdf` or `print` and the single export label is "Get Excel" (the manual uses four); `pageSize` is bounded by `cfg.report.page_size_options`; an export over `cfg.ops.report_export_max_rows` is refused with a hint to narrow; every export writes `report_export_log` and carries a watermark and a formula sanitiser (doc 21). The response carries `asOf` (the oldest `computed_at` of the sources), `fidelityUsed` and `excluded` (months or sources left out for fidelity, so a screen can say so).

```sql
-- M-118 the report registry: a new report is a row and a view over dw, never a code path that scans app (D-365, G-man-095)
CREATE TABLE app.report_def (
  report_key text PRIMARY KEY CHECK (report_key ~ '^[a-z][a-z0-9-]{2,40}$'),
  feature_id text NOT NULL CHECK (feature_id ~ '^F-(WEB|TSO|AMO|SR|SYS)-[0-9]{3}$'),
  title_key text NOT NULL,                                          -- localisation key; the Bangla and English titles live in the message catalogue (doc 15 s11)
  area text NOT NULL, grain text NOT NULL,
  source_objects text[] NOT NULL CHECK (cardinality(source_objects) > 0 AND array_to_string(source_objects, ',') !~ '(^|,)(app|cfg|stg)\.'),   -- dw only (R1)
  min_fidelity smallint NOT NULL DEFAULT 3 CHECK (min_fidelity BETWEEN 1 AND 3),
  params_schema jsonb NOT NULL, columns jsonb NOT NULL DEFAULT '[]', default_sort jsonb,
  roles text[] NOT NULL, pii_columns text[] NOT NULL DEFAULT '{}',
  columns_known boolean NOT NULL DEFAULT true,                       -- false until a real sample workbook fixes the column set (G-feat-53)
  status text NOT NULL DEFAULT 'planned' CHECK (status IN ('planned','built','retired')), since_phase text NOT NULL,
  version int NOT NULL DEFAULT 1, updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app.report_alias (                                      -- the display names the manual shows for one report ("Route wise BSR & CPR Report" and two more)
  alias text PRIMARY KEY, report_key text NOT NULL REFERENCES app.report_def(report_key), lang text NOT NULL DEFAULT 'en', is_display boolean NOT NULL DEFAULT false);
```

`source_objects` cannot name an `app`, `cfg` or `stg` object (a CHECK), so R1 is enforced by the registry itself. `columns_known = false` marks the 11 Excel-only reports whose column sets are unknown until real workbooks are captured (G-feat-53; OI-16-09). Display aliases are rows: the BSR and CPR report has three names in the manual and one `report_key`.

### 9.8 Report catalogue: every report is a dw-only query

`min_fidelity` is the lowest fidelity the report may read (3 memo level, 2 outlet-day aggregate, 1 zone-month totals); where it differs by group-by level the lower value applies from zone upward and 3 at route and SR level. Surfaces of doc 15 s6 (F-WEB ids) are the features; the work-queue pages (Outlet Approval Panel, Target Revise List, Web Entry, Web Final Submit, QC Entry, SR Device OTP, Set Target) read `app` through scoped repositories and are not reports (D-368).

| Report key | Feature | dw objects | Grain | min fidelity | Lands |
| --- | --- | --- | --- | --- | --- |
| dashboard | F-WEB-001 | `v_daily_territory`, `agg_daily_zone`, `agg_daily_zone_category`, `agg_hourly_zone`, `v_kpi_brand_zone_day` | zone x day | 1 | 1c, 4a |
| browse-retailer | F-WEB-002 | `dim_outlet`, `dim_outlet_pii` (PII roles) | outlet | n/a | 4b |
| products | F-WEB-004 to 009 | `dim_product`, `dim_sku_price` | SKU | n/a | 4b |
| browse-routes | F-WEB-010 | `dim_geo`, `dim_supervisor_assignment`, `agg_daily_route` | route | n/a | 4b |
| task-planner | F-WEB-011 | `fact_task` | task | 3 | 4b |
| by-route-geo-capture | F-WEB-012 | `dim_outlet`, `agg_outlet_density` | route | n/a | 4b |
| std-memo | F-WEB-013 | `agg_daily_route_sku`, `agg_daily_zone`, `agg_daily_zone_category` | location x date grouping x product | 2 (zone up), 3 (route) | 4b |
| sr-efficiency | F-WEB-014 | `agg_daily_user`, `fact_attendance` | user x day | 3 | 4b |
| route-std | F-WEB-015 | `agg_daily_route_sku` | route x SKU | 3 | 4b |
| data-entry-log | F-WEB-016 | `agg_daily_route` (`logged_in_at`, `first_sync_at`, `last_sync_at`, `sync_batches`), `fact_bundle_download` | route x day | 3 | 4b |
| final-submit-log | F-WEB-017 | `agg_daily_zone`, `agg_daily_route` (`sales_submitted_at`) | zone x day | 3 | 4b |
| route-bsr-cpr | F-WEB-018 | `agg_daily_route`, `v_kpi_brand_zone_day` | route x day | 3 | 4b |
| by-outlet | F-WEB-019 | `agg_daily_outlet`, `agg_outlet_balance` | outlet | 2 | 4b |
| astha | F-WEB-020 | `agg_month_outlet_program`, `dim_outlet` | outlet x brand x quarter | 2 | 5a |
| gigo | F-WEB-021 | `fact_attendance` | user x day | 3 | 4b |
| campaign-gift-redemption | F-WEB-022 | `fact_redemption_line`, `fact_gift_assignment` | redemption line | 3 | 5a |
| discount | F-WEB-023 | `fact_memo_offer`, `fact_memo_line`, `agg_daily_zone_offer` | offer group x scope x period | 3 | 4b |
| by-outlet-by-day | F-WEB-024 | `agg_daily_outlet` | outlet x day | 2 | 4b |
| online-offline | F-WEB-025 | `agg_daily_route` (`offline_memos`, `online_memos`, `web_entry_memos`), `fact_memo.entry_source`, `captured_offline` | scope x day | 3 | 4b |
| free-sample | F-WEB-026 | `agg_daily_route_sku.free_qty_base` | route x SKU | 3 | 5b |
| tso-top-sheet | F-WEB-027 | `v_daily_territory`, `agg_month_zone_product` | territory | 1 | 4b |
| daily-tracking | F-WEB-028, 038 | `agg_daily_route`, `v_kpi_achievement_zone_month`, `dim_target` | route x day | 3 | 4a |
| target-allocation | F-WEB-029 | `dim_target` | route or zone x product | n/a | 5c |
| sr-outlets | F-WEB-031 | `fact_outlet_request` | request | 3 | 4b |
| gift-choice | F-WEB-034 | `fact_gift_assignment` | outlet x period | 3 | 5a |
| leaderboard | F-WEB-036 | `agg_month_zone_product`, `v_kpi_achievement_zone_month` | zone x product x month | 1 | 4a |
| superstar | F-WEB-037 | `agg_month_outlet_program` | outlet x category | 2 | 5b |
| suspicious-location | F-WEB-044 | `fact_dq_flag`, `agg_daily_route` (`mock_calls`, `suspicious_calls`), `fact_visit` | visit | 3 | 4b |
| sync-health | F-WEB-045 | `agg_daily_route`, `agg_run`, `fact_device_day`, `agg_dirty` | route x day | 3 | 4a |
| final-submit-status | F-WEB-047 | `v_kpi_zone_day`, `v_daily_territory` | zone x day | 1 | 4a |
| diamond-league | F-WEB-049 | `fact_loyalty_ledger`, `agg_outlet_balance`, `agg_outlet_balance_daily` | outlet x period | 3 | 5a |
| ds-rrs | F-WEB-053 | `fact_memo`, `fact_memo_offer`, `agg_daily_route_sku` | route x day | 3 | 4b |
| amo-call | F-WEB-054 | `fact_visit` (kinds `amo_control_call`, `amo_joint_call`), `fact_assessment`, `fact_distribution` | AMO x day or month | 3 | 4b |
| dss | F-WEB-055 | `agg_daily_route_sku`, `dim_outlet` (sub-channel as of the sale date), `agg_daily_outlet`, `fact_web_entry_line` (source `web_entry`; the web-entry inclusion lands at 4c, D-578) | route x SKU, outlet drill-down | 3 | 4b (app data), 4c (web entry) |
| route-memo | F-WEB-056 | `agg_daily_route_sku.memo_count` | route x product | 3 | 4b |
| dues-ageing | F-WEB-058 | `agg_memo_due_open`, `v_kpi_dues_ageing_zone`, `agg_outlet_due_ageing_daily` | outlet, route, zone x bucket | n/a (ledger) | 4b |
| settlement | F-WEB-059 | `agg_daily_user_sku`, `agg_daily_user`, `fact_due_ledger` | user x day | 3 | 4b |
| qc-report | F-WEB-061, 062 | `agg_daily_route_sku` (QC fault columns), `fact_web_entry_line` (web QC, a separate source) | zone or route x day | 3 | 4c |

DSS has a rule of its own: it is the check before Final Submit, so it must include web-entered data and refresh within a minute (G-man-092). D-578 (G-qa-114) makes that testable: the `dss` row lists `fact_web_entry_line` as a source, a Web Entry save for a route-day dirty-keys that route-day through the trigger of s8.6b and appears in the DSS totals and the drill-down within 60 s, DSS equals the Web Entry totals for that route-day (gate T-4-167), the report shows a "data as of hh:mm:ss" stamp from `max(computed_at)`, and a staleness alert fires when the stamp is older than `cfg.sla.dss_stale_alert_s` (120) during selling hours (the aggregation-lag SLO of p95 60 s alone does not give the one-minute guarantee); its summary rows are per sub-channel as of the sale date, read from `dim_outlet` history. "Online/Offline Sales" counts a memo as offline when `captured_offline` is true, meaning the device had no connectivity at commit; the business definition is unknown (G-feat-58, confirm with the business; the default is stated on the report). Data Entry Log: MIN is the first and MAX the last event time in Dhaka time, never the swapped sample (G-man-097). The Final Submit Log shows first and last route sales-submit times and the routes submitted while the zone was Not Done. The report definitions that touch master data use the field differences of G-man-038: status per level, role-visible prices, one sort semantics, no vendor strings (D-244).

Proved by: T-1-04, T-1-106, T-3-107, T-4-03, T-4-100, T-4-104.

### 9.10 Mixed estate: switched-route scope and coverage (D-548, G-qa-80)

Wave 1 switches one territory per wing, about 3.4 percent of routes, and waves 2 to 4 run for weeks. `route_day` is created for every planned route, so without a rule Login %, Submit %, Day-completion %, the leaderboard, Daily Tracking buckets and target achievement would divide Aron data by the whole estate and show 3 to 20 percent of reality, and the Sev1 "Login % below 70 percent for any wing" alert would fire on every wave.

| Rule | Statement |
| --- | --- |
| Wave membership is a dimension | `dw.dim_geo.cutover_wave` and `on_new_system_from` (s8.3) are set by the wave job and the release console; `agg_daily_route.on_aron` is true when the route was switched on that date |
| Default scope | every KPI of s9.2 and every dashboard defaults to `scope = 'switched'`: numerators and denominators count only `on_aron` route-days; the page shows a coverage banner \"X percent of planned routes are on Aron\" (`coverage_pct = on_aron planned routes / planned routes`) and a toggle to `scope = 'all'` |
| All-routes view | `scope = 'all'` adds the nightly Apsis feed (DL-1 to DL-6 of s12.6, `source = 'apsis_parallel'`, `fidelity` 2) so wing and national totals stay complete; the feed is labelled and never mixed into SR-level, outlet-level or BSR reports (`min_fidelity`) |
| Alerts | sync-health SH-01, the Sev1 login alert and the anomaly alerts evaluate over switched routes only (doc 18 s6.6); an unswitched route can never be \"not logged in\" |
| Leaderboard | ranks only territories with at least `cfg.kpi.leaderboard_min_switched_pct` (default 80) of routes switched, else shows \"partial\" |
| Cutover dates | achievement before `on_new_system_from` comes from the imported history (source `migration_memo` or `migration_aggregate`), so month-to-date bars stay continuous across the switch |

Proved by: T-4-153, T-7-152.

## 10 Targets and programmes data

Targets, Astha, Diamond League and Superstar are tables with history, not columns that get overwritten (G-data-15, G-data-14, D-31, D-41). Everything here is ONLINE-ONLY to write (admin web) and CACHED on the device through the bundle; redemption lines and gift photos are OFFLINE captures with the idempotency keys of s6.2.

### 10.1 Targets

```sql
-- M-21 / M-111 targets v2: set header with approval events, variant level, non-negative by constraint (D-31, G-man-068, G-man-090)
CREATE TABLE app.target_set (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, client_uuid uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  name text NOT NULL,                                               -- '<Territory> of <Month>-<Year>'
  territory_id bigint NOT NULL REFERENCES app.territory(id),
  product_type text NOT NULL DEFAULT 'variant', target_type text NOT NULL DEFAULT 'stt',           -- 'stt' = STD (other types unconfirmed)
  month date NOT NULL CHECK (month = date_trunc('month', month)::date), start_date date NOT NULL, end_date date NOT NULL, CHECK (end_date >= start_date),
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','submitted','wmo_pending','approved','rejected','returned')),
  source text NOT NULL CHECK (source IN ('manual','excel')), source_media_id bigint,              -- the uploaded workbook in Blob
  submitted_by bigint REFERENCES app.app_user(id), submitted_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app.target_approval_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, set_id bigint NOT NULL REFERENCES app.target_set(id), level int NOT NULL, role app.role NOT NULL,
  actor_id bigint REFERENCES app.app_user(id), decision text NOT NULL CHECK (decision IN ('submit','approve','reject','return')), reason text, at timestamptz NOT NULL DEFAULT now());
ALTER TABLE app.target ALTER COLUMN std_target TYPE numeric(16,3);   -- fractional targets exist (88,235.29 on the AMO report)
ALTER TABLE app.target
  ADD COLUMN set_id bigint REFERENCES app.target_set(id), ADD COLUMN std_unit app.qty_unit NOT NULL DEFAULT 'stick',
  ADD COLUMN allocation_method text, ADD COLUMN parent_target_id bigint REFERENCES app.target(id),
  ADD COLUMN version int NOT NULL DEFAULT 1, ADD COLUMN source text NOT NULL DEFAULT 'admin' CHECK (source IN ('admin','excel','revision','formula','migration')),
  ADD COLUMN is_live boolean NOT NULL DEFAULT false, ADD COLUMN set_by bigint, ADD COLUMN set_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT target_nonneg    CHECK (std_target >= 0 AND memo_target >= 0),                    -- the -20 target bug
  ADD CONSTRAINT target_level_chk CHECK (product_level IN ('category','brand','variant','sku')),
  ADD CONSTRAINT target_scope_chk CHECK (scope_type IN ('route','zone')),
  ADD CONSTRAINT target_month_chk CHECK (month = date_trunc('month', month)::date);
CREATE UNIQUE INDEX target_live_uq ON app.target (scope_type, scope_id, product_level, product_id, month) WHERE is_live;
CREATE TABLE app.target_version (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, target_id bigint NOT NULL REFERENCES app.target(id), version int NOT NULL,
  std_target numeric(16,3) NOT NULL CHECK (std_target >= 0), memo_target int NOT NULL CHECK (memo_target >= 0),
  valid_from timestamptz NOT NULL, valid_to timestamptz, changed_by bigint, revision_id bigint, UNIQUE (target_id, version));
ALTER TABLE app.target_revision
  ADD COLUMN client_uuid uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN month date, ADD COLUMN requested_by bigint REFERENCES app.app_user(id), ADD COLUMN reason text,
  ADD COLUMN current_level int NOT NULL DEFAULT 1, ADD COLUMN required_levels int NOT NULL DEFAULT 1, ADD COLUMN applied_at timestamptz;
CREATE TABLE app.target_revision_line (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, revision_id bigint NOT NULL REFERENCES app.target_revision(id),
  target_id bigint REFERENCES app.target(id), product_level text NOT NULL, product_id bigint NOT NULL,
  old_std numeric(16,3), new_std numeric(16,3) CHECK (new_std >= 0), old_memo int, new_memo int CHECK (new_memo >= 0));
CREATE TABLE app.target_revision_event (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, revision_id bigint NOT NULL REFERENCES app.target_revision(id),
  level int NOT NULL, actor_id bigint NOT NULL, decision text NOT NULL CHECK (decision IN ('submitted','approved','rejected','returned')), comment text, at timestamptz NOT NULL DEFAULT now());
-- AMO home tiles "Today's target", "Total call target", "Control-call target", "Joint-call target" (docs/07, F-AMO-002, F-ADM-025, G-feat-57): added at the editorial merge
CREATE TABLE app.supervisor_target (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id bigint NOT NULL REFERENCES app.app_user(id),
  month date NOT NULL CHECK (month = date_trunc('month', month)::date),
  daily_call_target int NOT NULL DEFAULT 0 CHECK (daily_call_target >= 0), call_target int NOT NULL DEFAULT 0 CHECK (call_target >= 0),
  control_call_target int NOT NULL DEFAULT 0 CHECK (control_call_target >= 0), joint_call_target int NOT NULL DEFAULT 0 CHECK (joint_call_target >= 0),
  source text NOT NULL DEFAULT 'admin' CHECK (source IN ('admin','excel','migration')), set_by bigint, set_at timestamptz NOT NULL DEFAULT now(), UNIQUE (user_id, month));
```

| Rule | Statement | Decision |
| --- | --- | --- |
| Supervisor targets | `supervisor_target` holds the AMO's monthly call, control-call and joint-call targets and the daily call target that the AMO home tiles show beside the done counts (display only); non-negative by CHECK; the origin of the figures in Apsis is unknown (G-feat-57, D-349), so an admin or Excel entry is the default | D-349 |
| Level | `product_level` is category, brand, variant or SKU; SKU rolls up to variant, brand and category (the manual's target rows are variant names such as "Maxim Double Burst") | D-31 |
| Scope | a route or a zone, one live row per (scope, level, product, month): the partial unique index `target_live_uq` | G-data-15 |
| Non-negative | CHECK on `std_target`, `memo_target` and revision values; the -20 bug cannot be stored; a migrated negative is flagged at read and shows a dash | D-50, DQ-31 |
| Fractional | `numeric(16,3)`; storing is never rounded (88,235.29 and 264.71 are till-date results, not stored targets); the till-date value is computed at read | V-targets |
| Set header | `target_set` (name "Territory of Month-Year", territory, product type variant, target type stt, start and end date, status, source manual or excel, submitted by); status draft, submitted, wmo_pending, approved, rejected, returned | D-31 |
| Approval | default one level, role WMO, a configurable list (`cfg.target.approval_levels`); the level order is not evidenced; revisions reuse the same events; whether the live target stays while a new set is pending: the live row stays (default); confirm with the business (MQ-39) | D-31, D-179 |
| Entry | manual route x variant grid or the Excel workbook (route code, route name, variant code, STD target, optional memo target); the workbook is stored in Blob and linked by `source_media_id`; validation per row is DQ-62, all-or-nothing with a downloadable error sheet | G-man-090 |
| Split | no automatic split is evidenced: `cfg.target.split_method = manual`; a formula split exists only behind that key | D-31 |
| History | `target_version` keeps every past value with validity; `dim_target` mirrors it, so "the target in force on the 12th" and "what we reported then" are lookups (Q6) | G-analyst-02 |
| Unit | `std_unit` per row; the worker converts to base units when it writes `agg_month_*` (`target_unit` records the original) | D-16, D-49 |
| Restatement | a revision approved for month M enqueues `(month, scope)` only; `agg_month_*` carries `std_target_current`, `std_target_original` and `target_version`; the nightly snapshot keeps what was reported | D-358 |

### 10.2 Programmes: Astha, Diamond League, Superstar

| Programme | Data | Rules | Decision |
| --- | --- | --- | --- |
| Astha | `program` (kind astha, period quarter), `program_period`, `program_enrolment` (outlet, tier, category, incentive slab, base target, validity, no overlap by exclusion), `program_outlet_target` (per outlet, per brand or `all_brand` for the memo target, `std_target >= 0`) | tiers are sub-channels of channel Astha (D-258); quarters are calendar quarters unless `cfg.astha.quarter_start_month` says otherwise (unknown; confirm with the business); the achievement uses the tier valid on the sale date (`dim_outlet.astha_tier`); `cfg.astha.tier_attribution` is `enrolment` (default), `period_end` or `pro_rata` and the report shows the choice; the memo target is one row "All Brand" and ignores the month chips (parity) | G-analyst-13, G-man-045 |
| Astha gift choice | `gift_assignment` (one per program, period and outlet; status chosen, handed_over, verified, cancelled), `gift_photo` (one per assignment) | the choice locks once the SR hand-over photo exists; the gift catalogue is per tier and quarter | G-man-047 |
| Diamond League | `loyalty_earn_rule`, `gift_catalog` (gift or cash back, points cost, `cash_rate_mtk_per_point`, `max_points`), `loyalty_ledger` (source type and id), `redemption` (batch with a client uuid) and `redemption_line` | points are computed on the server and never accepted from the client; a replay never double-credits because `(source_type, source_id)` is unique; the only known earning rule is +50 for the POSM survey photo (not for an AMO survey), the rest is unknown; confirm with the business (MQ-20 to MQ-22); the April league redeems until 2026-05-07 (`program_period.redeem_until`); expiry is a nightly ledger row (`cfg.loyalty.expiry_days`; rule unknown); the 199-point cash cap scope (per redemption, per month or per outlet) is unknown, so `cfg.loyalty.cash_max_points` is a per-redemption limit by default | D-41 |
| Redemption overdraw | `redemption.points_balance_before`, `flags` | a redemption over the balance is accepted and flagged `points_overdrawn` when the device's bundle balance was sufficient at capture, and rejected `insufficient_points` when the device knew it was not | D-266, DQ-26 |
| Reversal | `loyalty_ledger.source_type = 'memo_superseded_reversal'` | the points earned on a superseded or voided memo are reversed by a ledger row; a missing reversal is DQ-68 | G-analyst-13 |
| Superstar | `program` (kind superstar), enrolment with category, incentive slab and base target, `criteria_met` computed by the worker from `program.rules` | enrolment and slab rules unknown; confirm with the business (G-feat-22); the report shows `criteria_met` as computed and never a payout | D-41 |
| Freeze | `program_period.freeze_after_days` | after that many days the period's aggregates are not recomputed except by an audited reopen | G-analyst-13 |

The liability question (Q14, "points outstanding at month end by tier") is answered by `agg_outlet_balance_daily` (the last row on or before the date) joined to `dim_outlet.astha_tier`, and the earned, redeemed and verified columns by `fact_loyalty_ledger` and `fact_redemption_line`. Programme aggregates (`agg_month_outlet_program`, plus `snap_period_outlet_program`: as-of date, period, outlet, tier, quantity, target, criteria met) land in 5a (M-65). Offer and promotion definitions are in s3.7; their effect on memos is in `fact_memo_offer` (s8.4).

Proved by: T-0-02, T-1-04, T-5-01, T-5-10, T-4-100.

## 11 Calendar, day state and planning data

The calendar and the day state are data so that Login %, "Submit % (of logged-in)" and Daily Tracking have denominators from midnight and survive a holiday, a rain day and a cover (D-27, D-28, D-30, G-data-10, G-feat-09).

### 11.1 The working-day calendar

```sql
-- calendar as data (D-28, docs/22 P-03)
CREATE TABLE cfg.holiday (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, holiday_date date NOT NULL,
  scope_type text NOT NULL DEFAULT 'global' CHECK (scope_type IN ('global','wing','division','territory','zone')),
  scope_key text NOT NULL DEFAULT '0', name_en text NOT NULL, name_bn text,
  kind text NOT NULL CHECK (kind IN ('holiday','makeup_day','emergency_off')), selling_day boolean NOT NULL,
  declared_at timestamptz, declared_by bigint, is_retroactive boolean NOT NULL DEFAULT false, second_approver bigint,   -- emergency_off only (D-542)
  reason_code text, effective_at timestamptz,                                                                          -- emergency_off: effective at declared_at, never at the next midnight
  created_by bigint, created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (holiday_date, scope_type, scope_key), CHECK ((kind IN ('holiday','emergency_off')) = (NOT selling_day)),
  CHECK (kind <> 'emergency_off' OR (declared_at IS NOT NULL AND reason_code IS NOT NULL AND (NOT is_retroactive OR second_approver IS NOT NULL))));
CREATE TABLE cfg.route_day_override (     -- absolute value, so a later calendar edit cannot silently invert it
  route_id bigint NOT NULL REFERENCES app.route(id), business_date date NOT NULL, planned boolean NOT NULL,
  reason_code text NOT NULL, source text NOT NULL CHECK (source IN ('admin','day_exception')), ref_id bigint,
  created_by bigint, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (route_id, business_date));
INSERT INTO cfg.config_item(key, value_type, default_value, scope_levels, risk_class, description_en)
  VALUES ('cfg.calendar.weekend_days','list','[5]','{global,wing,zone}',2,'ISO weekday numbers that are not selling days; default Friday');

CREATE FUNCTION app.dow_bit(d date) RETURNS int LANGUAGE sql IMMUTABLE PARALLEL SAFE   -- bit0 Sat .. bit6 Fri
  AS $$ SELECT 1 << ((extract(isodow FROM d)::int + 1) % 7) $$;
CREATE FUNCTION cfg.weekend_days(d date) RETURNS int[] LANGUAGE sql STABLE AS $$     -- global value in force on d, else the registry default
  SELECT ARRAY(SELECT w::int
                 FROM cfg.config_item ci
                 LEFT JOIN LATERAL (SELECT v.value FROM cfg.config_value v
                                     WHERE v.key = ci.key AND v.scope_type = 'global'
                                       AND tstzrange(v.effective_from, v.effective_to, '[)') @> (d::timestamp AT TIME ZONE 'Asia/Dhaka')
                                     LIMIT 1) g ON true,
                      jsonb_array_elements_text(COALESCE(g.value, ci.default_value)) w
                WHERE ci.key = 'cfg.calendar.weekend_days') $$;
CREATE FUNCTION app.is_working_day(d date, p_zone bigint) RETURNS boolean LANGUAGE sql STABLE AS $$
  WITH z AS (SELECT z.id AS zone_id, t.id AS territory_id, dv.id AS division_id, dv.wing_id
               FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id
              WHERE z.id = p_zone),
       h AS (SELECT bool_or(hd.selling_day) AS any_selling, bool_or(NOT hd.selling_day) AS any_off
               FROM cfg.holiday hd LEFT JOIN z ON true                   -- p_zone NULL: global calendar only (dw.dim_date)
              WHERE hd.holiday_date = d AND ( hd.scope_type = 'global'
                 OR (hd.scope_type = 'zone'      AND hd.scope_key = z.zone_id::text)
                 OR (hd.scope_type = 'territory' AND hd.scope_key = z.territory_id::text)
                 OR (hd.scope_type = 'division'  AND hd.scope_key = z.division_id::text)
                 OR (hd.scope_type = 'wing'      AND hd.scope_key = z.wing_id::text)))
  SELECT CASE WHEN h.any_selling THEN true                       -- a make-up day wins over a weekend
              WHEN h.any_off     THEN false
              ELSE NOT (extract(isodow FROM d)::int = ANY (cfg.weekend_days(d))) END
    FROM h $$;
CREATE FUNCTION app.route_planned(p_route bigint, d date) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT COALESCE( (SELECT o.planned FROM cfg.route_day_override o WHERE o.route_id = r.id AND o.business_date = d),
                   (r.visit_days_mask & app.dow_bit(d)) <> 0 AND app.is_working_day(d, r.zone_id) )
    FROM app.route r WHERE r.id = p_route $$;
```

| Rule | Statement | Decision |
| --- | --- | --- |
| Weekly off | `cfg.calendar.weekend_days` (ISO weekday numbers), default Friday (5); scoped to global, wing or zone; whether Saturday is a selling day is unknown; confirm with the business (Q27); Friday off is observed in the profile (one Friday, 22 May, traded just before Eid) | D-28, D-247 |
| Holidays | `cfg.holiday` rows with a scope (global, wing, division, territory, zone), `kind` holiday or make-up day, `selling_day`; a make-up day wins over a weekend, a holiday wins over a plan; the Eid break (27 to 31 May) and single holidays (13 and 19 June) are the evidence | D-28 |
| Route-day override | `cfg.route_day_override` stores an absolute `planned` value per route and date (source admin or day exception), so a later calendar edit cannot silently invert it | G-field-02 |
| Plan | `app.route_planned(route, date)` is the override if one exists, else visit-day mask matches the weekday AND the date is a working day for the route's zone; `dw.dim_date` carries the global flags and `bridge_holiday_scope` the scoped ones | D-28, D-29 |
| Editing | an edit rebuilds `dim_date` for the range and re-enqueues the affected route-days; a past date needs the C3 approval path of doc 19 | D-98 |
| Emergency non-working day (D-542, G-qa-73) | `kind = 'emergency_off'` is a same-day or short-notice declaration (hartal, cyclone, curfew, a sudden holiday) at wing, division, territory or zone scope, exempt from the change-freeze windows of doc 19 s7.10, takes effect AT `declared_at`: `route_day.planned` becomes false with `planned_source = 'override'` and `exception_reason = 'emergency_off'` for every route-day of the scope that is not yet logged in, so the denominators of K-01 to K-03, Daily Tracking and the dashboards drop them and label them "declared off"; a route already logged in or checked in keeps its state and still counts (it worked); a bundle pre-generated at 22:00 stays valid and a day opened on a declared-off day is accepted and flagged `worked_on_off_day`, never rejected; devices learn it from the config delta (a banner, no forced logout, no bundle invalidation); sync-health SH-01, the Sev1 login alert and the anomaly alerts are suppressed for the scope for that date (doc 18 s6.6); every declaration is audited with its reason, and one person may declare in time, but a RETROACTIVE declaration (for a date whose day has started or ended) needs a second approver | D-542 |

### 11.2 route_day

```sql
-- M-32 route_day: the day-state entity for a route and a date (D-27, D-30, D-64, D-71)
CREATE TABLE app.route_day (
  route_id bigint NOT NULL REFERENCES app.route(id), business_date date NOT NULL,
  zone_id bigint NOT NULL REFERENCES app.zone(id),
  planned boolean NOT NULL,                                         -- app.route_planned(route, date) at creation (00:05 job, safety net on first contact)
  planned_source text NOT NULL DEFAULT 'calendar' CHECK (planned_source IN ('calendar','override','exception','safety_net')),
  exception_reason text, exception_pending boolean NOT NULL DEFAULT false,
  assigned_user_id bigint REFERENCES app.app_user(id), acting_user_id bigint REFERENCES app.app_user(id),
  target_outlets int, plan_frozen_at timestamptz,                    -- frozen at the first bundle of the day (D-57)
  logged_in_at timestamptz,                                          -- first bundle on this Dhaka date or an offline day_open; deltas never move it (D-30)
  login_source text CHECK (login_source IN ('bundle','day_open_offline')),
  offline_start boolean NOT NULL DEFAULT false, bundle_stale boolean NOT NULL DEFAULT false, bundle_version_at_open text,
  first_check_in_at timestamptz, first_visit_at timestamptz, last_visit_at timestamptz, first_sync_at timestamptz, last_sync_at timestamptz,
  submit_requested_at timestamptz, submit_client_uuid uuid, submit_device_counts jsonb,   -- day_submit is the last record of the outbox (D-64)
  submit_pending_rows int, sales_submitted_at timestamptz,                                -- set only after the settle rule
  submit_seq smallint NOT NULL DEFAULT 1,                                                  -- the submit cycle: a void starts cycle n+1, so a retry of the voided cycle replays 'voided' and a new submit is accepted (D-539)
  submit_voided_at timestamptz, submit_voided_by bigint, submit_void_reason text, submit_void_count smallint NOT NULL DEFAULT 0,
  submit_count_mismatch boolean, submitted_with_dues boolean, dues_at_submit_mtk bigint,
  final_submitted_at timestamptz, final_submit_kind text CHECK (final_submit_kind IN ('manual','delegated','auto')),
  reopened_at timestamptz, reopened_by bigint, reopen_reason text,
  state app.day_state GENERATED ALWAYS AS (
    CASE WHEN final_submitted_at IS NOT NULL AND (reopened_at IS NULL OR final_submitted_at > reopened_at) THEN 'final_submitted'::app.day_state
         WHEN sales_submitted_at IS NOT NULL AND (submit_voided_at IS NULL OR sales_submitted_at > submit_voided_at) THEN 'sales_submitted'::app.day_state
         WHEN first_sync_at      IS NOT NULL THEN 'synced'::app.day_state
         WHEN first_check_in_at IS NOT NULL OR first_visit_at IS NOT NULL THEN 'in_field'::app.day_state
         WHEN logged_in_at       IS NOT NULL THEN 'logged_in'::app.day_state
         ELSE 'not_started'::app.day_state END) STORED,             -- derived from timestamps, so a retry or a late batch can never regress it
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (route_id, business_date));
CREATE INDEX ON app.route_day (business_date, zone_id, state);
```

| Fact | Rule |
| --- | --- |
| Creation | the 00:05 Dhaka job creates a `route_day` for every route planned that day, so the denominators exist from midnight (D-71); a safety-net insert on first contact covers a missed run (`planned_source = 'safety_net'`) |
| Owner | the route-day owns not started to sales submitted; the user owns attendance; the zone owns Final Submit and projects it onto every route-day of the zone; an SR on two routes has two route-days flipped by one bundle (D-27) |
| Derived state | `state` is a generated column from timestamps, so a retry or a late batch can never move a route backwards |
| Frozen plan | `target_outlets` and `plan_frozen_at` are set at the first bundle of the day (D-57); the dw adds the distinct unplanned outlets visited |

| State | Derived when | Counted as |
| --- | --- | --- |
| `not_started` | no timestamp below is set | in K-01 denominator only |
| `logged_in` | `logged_in_at` is set | K-01 numerator |
| `in_field` | `first_check_in_at` or `first_visit_at` is set | in field |
| `synced` | `first_sync_at` is set (first accepted batch for the route-day) | synced |
| `sales_submitted` | `sales_submitted_at` is set, only after the settle rule, and is later than `submit_voided_at` when a void exists (D-539) | K-02 and K-03 numerator |
| `final_submitted` | `final_submitted_at` is set and later than `reopened_at` | K-15 (zone) |

An approved day exception (`exception_reason` set, `day_exception_route` rows) removes the route from the denominators of K-01, K-02, K-03 and Daily Tracking and labels it "exception", a bucket distinct from "not logged in" (D-39).

### 11.3 Login event, offline start and the Login % gap

| Case | Rule | Decision |
| --- | --- | --- |
| Normal | `logged_in_at` is the first `bundle_download` of the route's user whose request falls on the Dhaka business date D, full or delta; later deltas never change it; a pre-fetch of D+1 on the evening of D does not count | D-30 |
| Offline start | a `day_open` event captured offline on a cached bundle (`login_source = 'day_open_offline'`) counts as logged in and sets `offline_start`, `bundle_stale` and `bundle_version_at_open`, so Login % does not undercount exactly the reps with the worst coverage (G-sync-07, DQ-66); the zone row carries `offline_start_routes` | D-30, G-analyst-16 |
| Outage morning | "which routes fell below 70 percent Login and were offline starts counted" (Q15) is a query on `agg_daily_route` and `agg_daily_zone`; the answer shows both the number with and without offline starts | G-analyst-16 |

### 11.4 Submit, settle and Final Submit

| Step | Rule | Decision |
| --- | --- | --- |
| Sales Submit | an outbox event that completes offline; `day_submit` is always the last record of the day's outbox sequence | D-64 |
| Settle | `sales_submitted_at` is set only when the server's totals are at least the device counts claimed in the event, or after `cfg.day.submit_settle_timeout_min` (30); `submit_pending_rows` shows meanwhile; `submit_count_mismatch` is evaluated after settle only, and the rollback trigger reads it after settle (doc 17) | D-64 |
| Final Submit | once per zone per day by primary key; the route snapshot, attempts and reopen trail are in s5.12; late batches for a closed zone-day are accepted, aggregated into their business date and flagged `after_final_submit` (DQ-57); reopening is an audited admin action; delegation to an acting TSO or DMO and auto-close are off until confirmed | D-55, D-262 |
| Submit void (D-539, G-qa-70) | an audited server event `app.submit_void_event` (`id`, `route_id`, `business_date`, `kind` void or reopen, `actor_user_id`, `confirmer_user_id`, `reason_code`, `state_before`, `submit_seq_before`, `window_min_used`, `at`) clears the effective `sales_submitted_at` of ONE route-day, so the generated state falls back to `synced` or `in_field` and the phone unlocks capture on its next response (doc 17 s4.11); allowed to a TSO of the zone, to L1 support with TSO confirmation (`confirmer_user_id`) inside `cfg.day.submit_undo_window_min` of the submit, and to ops_admin at any time before Final Submit; refused for a final-submitted zone unless the zone is first reopened (the existing audited reopen); a void is not a delete: the old `day_submit` uuid stays in the registry, the next submit has a new uuid and `submit_seq + 1`; `cfg.day.sales_submit_locks_capture` (default true) says whether a submit locks capture at all | D-539, D-55 |
| K-15 | zones final-submitted over zones having target routes, with the list of remaining zones | D-55 |

### 11.5 Supervisors, covers and planning tables

| Table | Use | Decision |
| --- | --- | --- |
| `supervisor_day` | AMO and TSO own no route, so they have no route-day: check-in, sync, sales submit and dues flag per user and date | D-27, G-man-032 |
| `route_assignment` (kind primary, cover, ss) | one primary per route-day by exclusion; a cover of up to 7 days is created by an AMO action; the visit carries `acting_for_user_id` and the dw `effective_user_key` | D-85, D-26 |
| `day_exception`, `day_exception_route` | rain, hartal, market closed, DH out of stock, breakdown, sick: an offline event raised by an SR or an AMO, approved by the TSO in the app (`cfg.day.exception_requires_approval`) | D-39 |
| `tracking_action` | the Daily Tracking "take action" after 17:00; contents unconfirmed, default a note and a notification to the TSO | G-feat-29 |
| `visit_plan`, `leave_application` | TSO visit plans and leave (s5.10); Pending and Completed are derived from the visit link | G-data-17 |

The dw reads: `agg_daily_route` takes `is_planned`, `planned_source`, `exception_reason`, the day-state timestamps and `submit_pending_rows` straight from `route_day`; `agg_daily_zone` takes `final_submitted` from the zone row. Daily Tracking buckets are the till-date achievement (basis `.web`, s9.5) at 100, 90 to 100, 80 to 90 and below 80 percent for STD and for memos, with exception and not-logged-in as their own buckets; the same engine serves the TSO Top Sheet.

Proved by: T-1-01, T-3-01, T-3-02, T-3-107, T-4-41.

## 12 Importer and migration data design

The importer reads files the business gives us and writes our own tables. It never contacts Apsis's running backend or app, and any credential found in the dump is data to migrate or rotate, never reused (CLAUDE.md guardrails, D-119). The importer is a job (doc 20 s7 owns the cutover tooling and the parallel run); this section fixes the data it reads, the tables it writes and the rules that make a re-run safe. Everything here is SERVER-side and runs before 7a; the tables exist from 0b so the shape is proven early (G-data-19).

### 12.1 What the sample proves and what it cannot

The two files received (docs/22) are an aggregate: one row per outlet x SKU x day (37.3 M rows, 2 May to 30 July 2026) and the retailer list of 1 October (734,789 outlets). They carry no SR, route, memo number, price, discount, paid or due, time of day, GPS fix, flag or photo. The importer therefore has two paths (D-367, G-analyst-09):

| Path | Input | Writes | Fidelity | Used for |
| --- | --- | --- | --- | --- |
| A, memo level | the full dump request of s12.2 | `app` rows (memo, memo line, visit, due, ...) with `entry_source = 'migration'`, a deterministic uuid and registry rows; then `dw` rows of source `migration_memo` | 3 | every KPI and report, parallel-run comparison, disputes about old memos |
| B, aggregate only | outlet x SKU x day volume (the shape received) | no `app` rows; `dw` rows of source `migration_aggregate` directly: `agg_daily_outlet`, `agg_daily_route_sku` (route from the retailer list as of import; a stub goes to the Unmapped member), `agg_daily_zone_category`, `agg_month_zone_product`, `agg_month_outlet_category` | 2 | year-on-year at zone level and above, retention candidate, outlet history; never a route-level, SR-level or BSR report |
| Totals only | zone x month totals from an Apsis report | `agg_month_zone_product` | 1 | the leaderboard and the year-on-year chart |

Rules for path B: an outlet-day with a positive row is a visited, successful call; an all-zero outlet-day is a zero-sale call (ASSUMPTION D-249, to confirm against memo-level data, which gives a strike rate of 84 to 90 percent by month and is not comparable with a native CPR); an outlet with no row that day is unknown, not "not visited", so migrated months carry no non-visit and no Login % (the columns are NULL). Rows are excluded from `dw.reconcile()` and from the restatement log because they have no source rows. `dw.dim_date.structure_basis = 'cutover_snapshot'` labels pre-cutover months: their roll-ups use the 1 October structure (D-379).

**Data-profile findings P-01 to P-16 and where each lands in this document** (added at the editorial merge; decision D = 244 + k, docs/22 holds the numbers).

| P | Finding in one line | Decision | Where in doc 16 | Gap |
| --- | --- | --- | --- | --- |
| P-01 | About 350k successful calls a day, peak 346,772; the export may include sales not made in the apps | D-245 | s12.1 | G-data-33 (new), G-sre-05, G-data-37 (new) |
| P-02 | 1.95 lines per call and rising, max 40 | D-246 | s5.5, s7.2 | G-feat-69 (new) |
| P-03 | Friday off, Eid break, single holidays | D-247 | s11.1 | G-feat-09 |
| P-04 | Volume is in sticks and pieces, exact pack multiples | D-248 | s1.4 | G-data-04 |
| P-05 | 8.7 percent zero-volume lines; all-zero outlet-days | D-249 | s5.5, s7.4, s9.5, s12.3, s12.4 | G-data-36 (new), G-field-03 |
| P-06 | 68 duplicate (date, outlet, SKU) keys | D-250 | s7.3, s7.4, s12.4 | G-feat-08, G-data-36 |
| P-07 | Outlet codes are text, with letters and stray symbols | D-251 | s1.2, s4.1, s7.4, s12.3, s12.4 | G-data-35 (new) |
| P-08 | 175,031 outlets in sales but not in the 1 October list | D-252 | s4.5, s7.4, s12.3, s12.4 | G-data-34 (new) |
| P-09 | 34,454 outlets share 11,222 points; 1,093 without coordinates | D-253 | s4.1, s7.4, s12.4 | G-cfg-23 (new), G-fraud-06 |
| P-10 | A 100 m geofence proves the market, not the shop | D-254 | s13.2 | G-cfg-23 |
| P-11 | Created At is hour-precision 12-hour text without timezone | D-255 | s12.3, s12.4 | G-data-35 |
| P-12 | Phone and owner filled 100 percent; NID is placeholder 123 or blank; TIN and licence empty | D-256 | s4.1, s7.4, s13.2 | G-sec-04, G-data-23 |
| P-13 | Hierarchy matches spec; 11,336 routes; an SR covers several routes | D-257 | s3.1, s12.4 | G-sync-21 (new), G-feat-40 |
| P-14 | geo_class null 19 percent; nine sub-channels; clusters 99.7 percent Transit Hub | D-258 | s3.5, s10.2, s12.4 | G-data-35 |
| P-15 | All 40 SKUs match the seed sku_code; sku_id 1 to 43 with gaps | D-259 | s3.2, s12.4 | G-data-19 |
| P-16 | Wholesale and C&C buyers with 10,000-stick lines | D-260 | s12.1 | G-feat-68 (new), G-field-08 |

### 12.2 The dump request (memo-level fields; docs/11 lists the groups; the letter is D-303)

| Group | Fields we need | Used for | If it cannot be supplied |
| --- | --- | --- | --- |
| Reference | wing to zone with codes; routes with visit days and the route-to-SR assignment history; clusters; classifications; the five price lists with validity; the sales plan; per-territory radius; users and roles | masters, the crosswalk | seed data keeps the build moving; the cutover waits |
| Outlets | code, owner, contact, address, latitude, longitude, cluster, sub-channel, geo class, status, created and updated time, **closure and merge history, last route of every closed outlet** | outlet book; closes the Unmapped gap of the 175,031 stubs | stubs stay in Unmapped; year-on-year omits them and says so |
| Memos | memo number and serial, date and time, outlet code, user, acting user, route, price type; per line SKU id, quantity, unit price, discount, offer id, free flag; gross, discount, net, paid, due; status (active, edited, void) and the edit chain; print flag | path A | path B for those months |
| Visits | id, outlet, user, start and end time, outcome, GPS fix with the mock flag, force-sale flag, photo id | geo history, CPR, suspicious counts | none; geo history starts at cutover |
| Other transactions | QC, attendance, stock issue and return, open dues per memo with dates, loyalty ledger and redemptions, tasks, surveys | opening balances, ledgers | opening balances only |
| Targets | monthly by route or zone and product (variant) plus revision history and approval status | achievement bars on day one | targets re-entered by the TSOs |
| Media | outlet, force-sale and gift photos with the ids that link them | evidence | none |
| Credentials | user table with the password-hash algorithm, if verifiable hashes exist (D-119) | same logins where possible | accounts are bound fresh (D-126) |
| Delta (D-514, G-qa-37) | the delta contract of s12.6: per wave and per night, the dues per memo, the loyalty ledger, outlet changes, target changes, user and route-assignment changes and the same-date control totals | wave-night import, parallel run, the nightly feed for unswitched routes | the AKTCL-staff fallback of s12.6; if neither runs, the wave is deferred |
| Format | CSV or a Postgres dump per table, a data dictionary, the id relationships, the Apsis memo-number rule | all | the importer parses text columns defensively |

### 12.3 Staging and the tables the importer writes

```sql
-- M-42 / M-130 to M-136 importer data design (D-251, D-252, D-255, D-153, G-sre-21); the importer itself is a job, these are its tables
ALTER TABLE app.sync_batch ADD COLUMN import_run_id bigint;           -- imported rows hang off a synthetic batch per chunk; rollback deletes by it
CREATE TABLE app.opening_balance (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, kind text NOT NULL CHECK (kind IN ('due','loyalty_points')),
  outlet_id bigint NOT NULL REFERENCES app.outlet(id), program_id bigint REFERENCES app.program(id), as_of_date date NOT NULL,
  amount_mtk bigint, points int, age_basis_date date, source_memo_ref text,        -- age_basis_date from the Apsis dues report; NULL ages from as_of_date (G-analyst-05)
  import_run_id bigint, UNIQUE NULLS NOT DISTINCT (kind, outlet_id, program_id, as_of_date));

CREATE TABLE stg.import_run (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, kind text NOT NULL CHECK (kind IN ('full','delta','path_b','retry')),
  source_label text NOT NULL, delta_since timestamptz, started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
  status text NOT NULL DEFAULT 'running' CHECK (status IN ('running','loaded','reconciled','failed','rolled_back')),
  counts jsonb, control_totals jsonb, quarantine_counts jsonb, rolled_back_at timestamptz, rolled_back_by bigint);
CREATE TABLE stg.id_crosswalk (                                        -- Apsis id to new id; makes every re-import and delta idempotent
  entity text NOT NULL CHECK (entity IN ('wing','division','territory','house','zone','cluster','route','user','outlet','sku','offer','program')),
  apsis_id text NOT NULL, new_id bigint NOT NULL, normalised_key text, import_run_id bigint, imported_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (entity, apsis_id));
CREATE INDEX ON stg.id_crosswalk (entity, normalised_key);
CREATE TABLE stg.raw_sales (                                           -- the aggregate sample shape: one row per outlet x SKU x day (path B)
  import_run_id bigint NOT NULL, file_id text NOT NULL, line_no bigint NOT NULL,
  sale_date text, outlet_code text, sku_id text, sku_name text, volume text, PRIMARY KEY (import_run_id, file_id, line_no));
CREATE TABLE stg.raw_retailer (                                        -- one row per outlet as of the list date; text everywhere so a bad cell never aborts the load
  import_run_id bigint NOT NULL, file_id text NOT NULL, line_no bigint NOT NULL,
  wing text, zone text, route text, outlet_code text, outlet_name text, owner_name text, phone text, cluster_name text, cluster_type text,
  latitude text, longitude text, geo_class text, sub_channel text, created_at text, updated_at text, other jsonb, PRIMARY KEY (import_run_id, file_id, line_no));
CREATE TABLE stg.sales_norm (                                          -- normalised and deduplicated; zero volumes are not stored here (D-249)
  import_run_id bigint NOT NULL, business_date date NOT NULL, outlet_id bigint, outlet_code_norm text NOT NULL, sku_id bigint NOT NULL,
  volume_base bigint NOT NULL CHECK (volume_base > 0), duplicate_rows smallint NOT NULL DEFAULT 1, zero_outlet_day boolean NOT NULL DEFAULT false,
  PRIMARY KEY (import_run_id, business_date, outlet_code_norm, sku_id));
CREATE TABLE stg.quarantine (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, import_run_id bigint NOT NULL, table_name text NOT NULL, row_ref text NOT NULL,
  reason_code text NOT NULL, raw jsonb NOT NULL, status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','explained','fixed','accepted')), note text, resolved_by bigint, resolved_at timestamptz);
CREATE INDEX ON stg.quarantine (import_run_id, table_name, reason_code);
CREATE TABLE stg.control_total (                                       -- per-zone reconciliation against the Apsis reports (docs/11)
  import_run_id bigint NOT NULL, zone_id bigint NOT NULL, metric text NOT NULL CHECK (metric IN ('outlets','mtd_std_base','memo_count','due_outstanding_mtk','loyalty_points')),
  apsis_value numeric, new_value numeric, diff numeric GENERATED ALWAYS AS (new_value - apsis_value) STORED, status text NOT NULL DEFAULT 'open',
  PRIMARY KEY (import_run_id, zone_id, metric));

-- importer helpers (D-251, D-255): pure functions, so the same code runs in the load job and in the unit gate T-7-109
CREATE FUNCTION stg.normalise_outlet_code(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$       -- text, never a number; stray leading symbols dropped; NULL = quarantine
  SELECT CASE WHEN length(x) BETWEEN 3 AND 20 THEN x END
    FROM (SELECT regexp_replace(upper(btrim(app.normalise_digits(t))), '^[^A-Z0-9]+', '') AS x) q $$;
CREATE FUNCTION stg.parse_created_at(t text) RETURNS timestamptz LANGUAGE sql IMMUTABLE AS $$     -- "2025-12-19 01AM": hour precision, 12-hour clock, no zone; Asia/Dhaka assumed (ASSUMPTION)
  SELECT CASE WHEN t ~ '^\d{4}-\d{2}-\d{2} \d{1,2}(AM|PM)$'
              THEN ((substr(t, 1, 10)::date + make_interval(hours => (substring(t FROM ' (\d{1,2})(?:AM|PM)$')::int % 12) + CASE WHEN t LIKE '%PM' THEN 12 ELSE 0 END))::timestamp) AT TIME ZONE 'Asia/Dhaka' END $$;
-- rollback of one run: dw rows and opening balances carrying the run id are retracted; masters are handled by the importer job (referenced rows are quarantined, never deleted)
CREATE FUNCTION stg.rollback_import(p_run bigint) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r jsonb := '{}'; n bigint; t text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM stg.import_run WHERE id = p_run AND status <> 'rolled_back') THEN RAISE EXCEPTION 'import run % is unknown or already rolled back', p_run; END IF;
  FOREACH t IN ARRAY ARRAY['dw.fact_visit','dw.fact_memo','dw.fact_memo_line','dw.fact_memo_offer','dw.fact_due_ledger','dw.agg_daily_route','dw.agg_daily_route_sku','dw.agg_daily_outlet',
                           'dw.agg_daily_outlet_brand','dw.agg_daily_zone_category','dw.agg_month_zone_product','dw.agg_month_route_product','dw.agg_month_outlet_category','app.opening_balance'] LOOP
    EXECUTE format('DELETE FROM %s WHERE import_run_id = $1', t) USING p_run;
    GET DIAGNOSTICS n = ROW_COUNT;  r := r || jsonb_build_object(t, n);
  END LOOP;
  UPDATE stg.import_run SET status = 'rolled_back', rolled_back_at = now() WHERE id = p_run;
  RETURN r;
END $$;

-- a deterministic id for an imported record: a re-import produces the same uuid, so the ingest registry makes it a no-op (not a device id, so not v4: ASSUMPTION)
CREATE FUNCTION stg.import_uuid(p_entity text, p_apsis_id text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('aron-import:' || p_entity || ':' || p_apsis_id)::uuid $$;
```

| Table | Role | Phase |
| --- | --- | --- |
| `stg.import_run` | one row per run: kind full, delta, path_b or retry; status running, loaded, reconciled, failed, rolled_back; counts, control totals, quarantine counts | tables 0b, used 7a |
| `stg.id_crosswalk` | Apsis id to new id per entity (wing, division, territory, house, zone, cluster, route, user, outlet, sku, offer, program) plus the normalised key; the key to idempotent re-import and delta | 0b, 7a |
| `stg.raw_sales`, `stg.raw_retailer` | the files as text columns, so a bad cell never aborts the load; `file_id` and `line_no` keep every row traceable | 7a |
| `stg.sales_norm` | normalised and de-duplicated sales; zero volumes are not stored (D-249); `duplicate_rows` and `zero_outlet_day` kept | 7a |
| `stg.quarantine` | every row the importer would not load, with a reason code, the raw row and a status (open, explained, fixed, accepted) | 7a |
| `stg.control_total` | per zone and metric: Apsis value, new value, difference, status | 7a |
| `stg.apsis_route_day` | the per-route daily Apsis figures of the parallel run (D-154): route, business date, memo count, STD per SKU, net value, dues, source (machine-readable feed or manual keying, pilot only); read by `dw.v_parallel_compare`, the daily comparison of F-SYS-043 | 7b |
| `app.opening_balance` | open dues and loyalty points per outlet at the import date, with `age_basis_date` from the Apsis dues report, so opening dues age from their real date and not from the cutover (G-analyst-05) | 0b, 7a |

### 12.4 Normalisation and mapping

| Item | Rule | Decision |
| --- | --- | --- |
| Outlet code | text, never a number: Bengali digits converted, trimmed, stray leading symbols (`:`, `*`, `।`) removed, upper case; 693k are 7 digits and others run 8 to 13 characters with letters (probably field-created, ASSUMPTION); a code of fewer than 3 characters or two Apsis codes with one key go to quarantine (DQ-46); `stg.normalise_outlet_code()` | D-251 |
| Created At | `2025-12-19 01AM` is hour precision, 12-hour clock, no zone; parsed by `stg.parse_created_at()` as Asia/Dhaka (ASSUMPTION); `Updated At` is partial (281k) and is kept as given | D-255 |
| Archived stubs | one `status = archived` outlet per code in the sales file and not in the 1 October list, named `[archived] <code>`, with history, dues and loyalty and never counted in target outlets (s4.5) | D-252 |
| Zero volume | not a sale: STD and KPIs exclude it; an all-zero outlet-day is a zero-sale call (ASSUMPTION) | D-249, DQ-49 |
| Duplicate keys | the 68 (date, outlet, SKU) duplicates are summed and flagged; one outlet can be visited twice a day | D-250, DQ-50 |
| SKU | the export's `sku_id` (1 to 43 with gaps at 19, 20 and 40) is the crosswalk key; all 40 sold SKUs match the seed `sku_code`; the two SupSty SKUs were not sold | D-259 |
| Geography | 10 wings, 50 divisions, 291 territories, 1,051 zones, 11,336 routes; the visit-day pattern is parsed from the route name once (s3.1) | D-257 |
| Classification | nine sub-channels (GT, Gold, Platinum, RCC, Diamond, DCC, Silver, MT, HoReCa); geo class may be null (19 percent blank); "Semi Urban" maps to `SemiUrban` | D-258 |
| Coordinates | 1,093 outlets without a position and 34,454 on 11,222 shared points: `location_confirmed = false` (DQ-48) | D-253 |
| Identity | a deterministic id, `stg.import_uuid(entity, apsis_id)`, becomes the row's `client_uuid` and is registered, so a re-import is a no-op through the registry and a voided row stays voided | D-21, D-367 |
| Memo number | an imported memo keeps its Apsis number (`memo_no`) and serial; new native memos use the new series (D-35) | D-35 |
| Passwords | if the dump has verifiable hashes of a known algorithm they are verified once at first login and re-hashed; otherwise accounts bind fresh | D-119 |

### 12.5 Control totals, quarantine and the stop rule

| Control | Rule | Decision |
| --- | --- | --- |
| Per zone, five metrics | outlets, month-to-date STD in base units, memo count, due outstanding (mtk) and loyalty points are compared with the Apsis reports of the same date; the report is a table in `stg.control_total` and a downloadable sheet | docs/11 |
| Tolerance | zero for outlets, memo count, dues and points; STD exact in base units (ASSUMPTION); every difference is explained row by row (a DQ-49 or DQ-50 count, a stub) or the zone is not cut over | D-377 |
| Quarantine | at most 1 percent of rows per table, with every reason counted and explained; a higher rate raises a gap and stops the run | D-153 |
| Re-import | the same dump twice changes nothing (crosswalk, deterministic uuid, `UNIQUE NULLS NOT DISTINCT` on opening balances) | T-7-02 |
| Rollback | `stg.rollback_import(run)` retracts every dw row and opening balance carrying the run id and marks the run `rolled_back`; a second call is refused; masters created by a run are retracted by the job only where no device row references them, else they are listed in `stg.quarantine` (reason `referenced_by_device_row`); never a DELETE of a referenced row (G-sre-21, gap G-16-16) | D-367 |
| Delta | before each wave the latest Apsis delta is imported (`kind = 'delta'`, `delta_since`) and reconciled by 23:00 or the wave is deferred (D-71); what the delta contains, who delivers it, by when, in which format and what happens on a breach is the contract of s12.6 (D-514) | docs/11 |
| Parallel run | collections taken in `parallel_run_mode` carry a `parallel` flag and are excluded from the opening balance; the daily comparison (memos, STD, dues, geo %, Submit % (of logged-in)) reads `dw` for the pilot routes against the Apsis figures | docs/11 |
| Housekeeping | `stg.raw_*` and `stg.sales_norm` are dropped 90 days after the last wave; `control_total`, `id_crosswalk` and `import_run` are kept (provenance) | D-371 |

### 12.6 The Apsis delta contract (D-514, G-qa-37)

Every wave night needs a delta imported, reconciled and finance-signed by 23:00 at T-1 (D-71), and the first-day balances (opening dues, loyalty, outlet changes) depend on it. The first draft asked Apsis for one historical dump and a per-route pilot feed only, with no ask, owner, format, cadence or deadline for deltas. PROJECT-CONTEXT says the only thing expected from Apsis is the dump, so the delta is a request that is not yet made and that Apsis may refuse. The contract below goes into the dump-request letter (doc 20 T-0-141) and into the MUST-CONFIRM list (doc 14 s4.2, D-514); a fallback that needs no Apsis cooperation is part of it.

| Item | Content | Format and key | Cadence and deadline | Delivered by | On breach |
| --- | --- | --- | --- | --- | --- |
| DL-1 Dues | every open memo of the wave scope's outlets: memo number, outlet code, memo date, total, paid, due, status, collection rows since the last delta | `delta_dues_<wave>_<yyyymmdd>.csv`, UTF-8, one row per memo or collection, plus a manifest (row count, SHA-256, `exported_at`) | dry run T-2, final T-1 by 20:00 Dhaka | the named Apsis delta owner (AKTCL IT or sales operations data owner, named at the 0c exit); Apsis supplies the files under the handover contract or AKTCL staff extract them (fallback) | fallback by 21:00; not reconciled by 23:00: the wave is deferred one day (D-71); two deferrals in a row: re-plan by the sponsor's delegate |
| DL-2 Loyalty | the loyalty ledger entries and redemptions since the last delta, and the balance per outlet at `exported_at` | same | same | same | same |
| DL-3 Outlet changes | new outlets, closures, location and info changes, route moves since the last delta | same | same | same | same |
| DL-4 Targets and assignments | target and revision changes, user and route-assignment changes in scope | same | same | same | same |
| DL-5 Control totals | the same-date report totals per zone (outlets, month-to-date STD, memo count, dues outstanding, loyalty points) from the Apsis reports of T-1 | `delta_controls_<wave>_<yyyymmdd>.csv` | T-1 by 20:00 | same | the zone is not cut over (s12.5) |
| DL-6 Nightly aggregates for unswitched routes (D-548) | per route-day: target outlets, calls, successful calls, memos, STD by SKU, net value, logged in, submitted | `delta_route_day_<yyyymmdd>.csv`, loaded as `dw.agg_daily_route` rows of `source = 'apsis_parallel'` | nightly by 04:00 Dhaka from the first wave until decommission | same | national totals show a banner "Apsis feed late"; no wave is blocked by this feed |
| DL-1b Late delta (D-556, G-qa-88, G-qa-122) | the same content as DL-1 to DL-4, restricted to rows created or changed in Apsis AFTER the final cut (T-1 20:00): credit memos, collections, Sales Submit rows and outlet changes that an SR uploads in Apsis after 20:00 on T-1 or on the morning of T from a phone that was offline | same shape, `delta_late_<wave>_<yyyymmdd>.csv` plus a manifest | day T at `cfg.cutover.late_delta_time` (06:00), then again each morning T+1 to T+3 (DL-2b) until the straggler sheet is empty | the named Apsis delta owner or the staff fallback | the delta reaches the phones as a bundle delta before the first sale with the marker "balance as of <time>" (F-SYS-068); a row that cannot be imported goes to the straggler sheet (s12.6b) |


Cut rule and stragglers (D-556). The Apsis app uploads once, at end of day, and AKTCL cannot set it read-only (D-148, RK-22), so a rep can still upload after the cut. (1) The cut is a CHECKLIST LINE AND A GO/NO-GO CONDITION (doc 20 s7.6): every wave SR has completed the Apsis end-of-day upload and Sales Submit and the Apsis TSO Final Submit is done for 100 percent of the wave's zones by `cfg.cutover.apsis_complete_by_time` (19:00 on T-1); the cut-off itself is set from the upload-time distribution of baseline-pack item h. (2) DL-1b picks up everything after the cut. (3) A named AKTCL clerk owns a 3-day STRAGGLER RECONCILIATION SHEET (`stg.straggler`: outlet, memo or collection, Apsis timestamp, amount, state, owner, closed_at), reviewed by finance each morning. (4) T-7-150 and T-7-151 inject an Apsis upload at 21:30 on T-1 and another at 07:00 on T and assert that both reach the phone before the first sale or the straggler sheet (T-7-163).

Fallback (no Apsis cooperation): AKTCL staff produce DL-1 to DL-5 from AKTCL's own Apsis admin and report exports (the web dashboard Excel exports used for the baseline pack) into a fixed sheet template with the same columns; the importer's `kind = 'delta'` run accepts the sheet and marks it `source_channel = 'staff_extract'`. Second fallback: the previous delta plus the reconciliation against the Apsis reports of T-1; any unexplained difference defers the wave. Gate: the delta path runs successfully twice before wave 1, once on the pilot scope during 7b and once as a full rehearsal on the wave-1 scope one week before T-1 (T-7-150, T-7-151); both are 7a and 7c entry conditions. The decommission delta (the last import before Apsis is switched off) uses the same contract (T-7-88).

### 12.6b Switched routes keep selling in Apsis: detection (D-556, G-qa-122)

Nothing stopped or detected continued Apsis selling on a switched route. DL-6 covers UNSWITCHED routes only, and an SR who keeps selling or collecting dues in Apsis after the switch is invisible, so dues diverge across two systems (docs/11 calls this the most dangerous case). Rules: (1) DL-6 is EXTENDED to switched routes as `source = 'apsis_residual'`: any Apsis memo or collection on a route whose `route_day.system = 'on_aron'` raises a per-route alert to the AMO, TSO and L1 and a tile SH-26 (threshold `cfg.sla.apsis_residual_alert`, default 0); (2) a bind-at-the-distribution-house list at T-1 23:00 names every wave SR; no route switches with an unbound SR unless the TSO holds it (the route stays on Apsis and is shown as `held`); (3) if Apsis cannot supply feeds, "the old app is disabled or uninstalled on every phone of the wave, ticked by the TSO on the readiness screen" is a go/no-go line; (4) SRs absent on the pre-bind day stay `held` and are not switched; (5) runbook RB-44 (doc 18). Gates T-7-164 and T-7-163.

Proved by: T-0-01, T-7-01, T-7-02, T-7-06, T-7-109, T-7-150, T-7-151.

## 13 Retention, archival, PII classification, BI access

### 13.1 Retention classes and archival

Classes are rows (`app.retention_policy`), the hot window of a class is a config value, and a partition leaves the primary only after its export is verified (D-23, D-131, D-371).

```sql
-- partition policy as data; monthly RANGE partitions on business_date, created 3 months ahead (D-23, D-131)
CREATE TABLE app.partition_policy (
  parent text PRIMARY KEY, key_column text NOT NULL DEFAULT 'business_date',
  retention_class text NOT NULL CHECK (retention_class IN ('transaction','fix','telemetry','quarantine','audit','event_fact')),
  ahead_months int NOT NULL DEFAULT 3);
CREATE FUNCTION app.ensure_partitions(p_today date DEFAULT app.dhaka_date(now())) RETURNS int LANGUAGE plpgsql AS $$
DECLARE p record; m date; n int := 0; part text;
BEGIN
  FOR p IN SELECT * FROM app.partition_policy LOOP
    EXECUTE format('CREATE TABLE IF NOT EXISTS %s_default PARTITION OF %s DEFAULT', p.parent, p.parent);   -- rows landing here raise an alert
    FOR i IN 0..p.ahead_months LOOP
      m := date_trunc('month', p_today)::date + make_interval(months => i);
      part := format('%s_y%sm%s', p.parent, to_char(m, 'YYYY'), to_char(m, 'MM'));
      IF to_regclass(part) IS NULL THEN
        EXECUTE format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)', part, p.parent, m, (m + interval '1 month')::date);
        n := n + 1;
      END IF;
    END LOOP;
  END LOOP;
  RETURN n;
END $$;
```

```sql
INSERT INTO app.partition_policy(parent, key_column, retention_class) VALUES
  ('app.visit','business_date','transaction'), ('app.memo','business_date','transaction'), ('app.memo_line','business_date','transaction'),
  ('app.memo_offer','business_date','transaction'), ('app.qc_entry','business_date','transaction'), ('app.qc_entry_line','business_date','transaction'),
  ('app.drp_collection','business_date','transaction'), ('app.drp_collection_line','business_date','transaction'), ('app.survey_response','business_date','transaction'),
  ('app.due_collection','business_date','transaction'), ('app.stock_movement','business_date','transaction'), ('app.attendance_event','business_date','transaction'),
  ('app.visit_skip','business_date','transaction'), ('app.memo_void','business_date','transaction'), ('app.print_event','business_date','transaction'),
  ('app.geo_fix','business_date','fix'), ('app.content_view','business_date','telemetry'), ('app.activity_log','business_date','telemetry'),
  ('app.sync_batch','uploaded_on','telemetry'), ('app.sync_rejected','business_date','quarantine'), ('app.risk_signal','business_date','transaction'),
  ('app.audit_log','at','audit'),
  ('dw.fact_visit','business_date','event_fact'), ('dw.fact_memo','business_date','event_fact'), ('dw.fact_memo_line','business_date','event_fact'),
  ('dw.fact_due_ledger','business_date','event_fact'), ('dw.fact_due_allocation','business_date','event_fact'),
  ('dw.agg_daily_outlet','business_date','event_fact'), ('dw.agg_daily_outlet_brand','business_date','event_fact'), ('dw.fact_dq_flag','business_date','event_fact'), ('dw.agg_hourly_zone','business_date','event_fact'), ('dw.agg_daily_route_sku','business_date','event_fact'), ('dw.agg_daily_user_sku','business_date','event_fact'), ('dw.fact_memo_offer','business_date','event_fact'),
  ('dw.snap_month_zone_product','as_of_date','event_fact'), ('dw.agg_restatement_log','changed_at','audit');
```

```sql
-- M-37 retention and archival (D-23, D-371): the hot window per class is data; a partition is dropped only after its export is verified
CREATE TABLE app.retention_policy (
  retention_class text PRIMARY KEY CHECK (retention_class IN ('transaction','fix','telemetry','quarantine','audit','event_fact')),
  hot_months int CHECK (hot_months > 0),                       -- NULL = never leaves the primary (audit)
  keep_months int CHECK (keep_months IS NULL OR keep_months >= hot_months),   -- NULL = kept for ever in the archive
  note text);
INSERT INTO app.retention_policy VALUES
  ('transaction', 13, 84, 'dues disputes and audits reach back a year; statutory period unknown, 7 years assumed (G-data-32)'),
  ('fix',          6, 24, 'raw positions; the verdicts and flags live on in fact_visit'),
  ('telemetry',    3, 12, 'activity_log, content_view, sync_batch'),
  ('quarantine',  12, 24, 'parked rows are never archived while status is parked'),
  ('audit',     NULL, NULL, 'never leaves the primary, never deleted'),
  ('event_fact',  25, 84, 'dw event grain and the 25-month daily aggregates; month aggregates are kept for ever');
CREATE TABLE app.archive_manifest (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, parent text NOT NULL, partition_name text NOT NULL UNIQUE, month date NOT NULL,
  row_count bigint, sha256 text, blob_url text, format text CHECK (format IN ('parquet','sql_gz')),
  status text NOT NULL DEFAULT 'planned' CHECK (status IN ('planned','exported','verified','dropped','restored')),
  exported_at timestamptz, verified_at timestamptz, dropped_at timestamptz);
CREATE FUNCTION app.archive_candidates(p_today date DEFAULT app.dhaka_date(now()))
RETURNS TABLE (parent text, partition_name text, month date, retention_class text) LANGUAGE sql STABLE AS $$
  SELECT pp.parent, c.relname::text, to_date(substring(c.relname FROM 'y(\d{4}m\d{2})$'), 'YYYY"m"MM'), pp.retention_class
    FROM app.partition_policy pp
    JOIN app.retention_policy rp ON rp.retention_class = pp.retention_class AND rp.hot_months IS NOT NULL
    JOIN pg_inherits i ON i.inhparent = to_regclass(pp.parent)
    JOIN pg_class c ON c.oid = i.inhrelid
   WHERE c.relname ~ 'y\d{4}m\d{2}$'
     AND to_date(substring(c.relname FROM 'y(\d{4}m\d{2})$'), 'YYYY"m"MM') < (date_trunc('month', p_today) - make_interval(months => rp.hot_months))::date
     AND NOT EXISTS (SELECT 1 FROM app.archive_manifest m WHERE m.partition_name = c.relname AND m.status = 'dropped') $$;
```

| Data | Where | Hot in the primary | Then | Deleted | Keys |
| --- | --- | --- | --- | --- | --- |
| Transactions (visit, memo, line, offer, QC, DRP, survey, due collection, stock, attendance, skip, void, print) | `app.*` monthly partitions | 13 months | detach, export to Blob Cool as Parquet and compressed SQL, drop after the verified manifest row | the archive is kept 84 months (7 years: ASSUMPTION; statutory period unknown, G-data-32) | `cfg.retention.transactions_hot_months` (NEW) |
| `geo_fix` | monthly partitions | 6 months | verdicts live on in `fact_visit`; raw to Archive tier | 24 months | `cfg.retention.geo_fix_months` (hot) and `cfg.retention.geo_fix_archive_months` (24, registered at the merge) |
| `activity_log`, `content_view` | partitions | 3 months | Blob Cool | 12 months | `cfg.retention.activity_log_days` |
| `sync_rejected` | partitions | 12 months; a row with status `parked` is never archived | Blob | 24 months | `cfg.retention.quarantine_months` (NEW) |
| `sync_batch` | monthly by `uploaded_on` | counts for 13 months; the `response` column is NULLed after 48 hours | - | - | `cfg.retention.sync_batch_response_h` |
| `ingest_registry` | 64 hash partitions | rows older than 45 days are deleted in 1,000-row chunks, except `voided` tombstones, which are kept | - | - | `cfg.retention.ingest_registry_days` (NEW) |
| `audit_log`, `config_change_audit`, `target_version`, `agg_restatement_log`, every `*_event` table | tables | for ever | - | never | not editable |
| Photos | Blob, `media_object` | lifecycle Hot, Cool at 30 days, Cold at 90, Archive at 365 (D-132); reference photos (an active outlet's) are exempt | - | `cfg.retention.media_days` (730 default; Q21 unknown) | doc 18 s8 |
| `dw.fact_*` event grain and the daily aggregates at route x SKU, outlet and user x SKU grain | monthly partitions | 25 months | Parquet in Blob, Hive-partitioned by `business_date` | 84 months | `cfg.retention.event_fact_months` (NEW) |
| `dw.agg_daily_route`, `agg_daily_zone`, `agg_daily_user`, `agg_month_*`, balances, snapshots | tables | for ever (small) | - | never | not editable |
| `stg` control totals, crosswalk, `app.opening_balance` | tables | for ever | - | never | provenance |

`app.archive_candidates(today)` lists the partitions past their hot window; the monthly job (the API scheduler, doc 18) exports each, writes an `archive_manifest` row with the row count, SHA-256 and blob URL, verifies the upload and only then drops the partition. A restore is a `pg_restore` or Parquet read into an `_archive` copy of the table. A route x SKU daily figure older than 25 months is available from the lake only, while its month aggregate stays online for ever; whether the business needs day-level SKU history online beyond 25 months is unknown (gap G-16-12, OI-16-31).

#### 13.1b Legal hold (D-600, G-qa-139)

Raw fixes are hot for 6 months and archived to 24, photos keep 730 days and quarantine rows 12 months, but a fraud case or a retailer dispute can outlast those windows and nothing suspended deletion. `app.retention_hold(id, scope_type (user, outlet, route, zone, date_range, all_of_user), scope_id, date_from, date_to, data_classes text[] (fix, media, quarantine, audit_export, transaction), reason, case_id, approver_id, second_approver_id, created_at, expires_at, lifted_at, lifted_by)` is checked by `app.archive_partition()`, `dw.archive_partition()`, the photo lifecycle sweep (J8) and the quarantine purge before they drop, archive or delete: a partition or blob that contains a row covered by an open hold is skipped and reported on P16 and in `job_run`. Holds are placed by `security_admin` or `pii_officer` with a second approver, expire at most `cfg.retention.hold_max_days` (365) after creation (renewable), and every placement and lift is audited. A hold never extends access: it only suspends deletion. Gate T-6-152.

### 13.2 Classification of personal and sensitive data

| Field | Class | Where | Rule | Decision |
| --- | --- | --- | --- | --- |
| NID, TIN, trade licence | Sensitive, unused in practice (NID is the placeholder 123 for 589k outlets and blank for 145k; TIN and licence empty) | `app.outlet`, envelope-encrypted | nullable and classified; never searched, never in a bundle, never in `dw`; column grant to `pii_reader` only; the placeholder is treated as NULL (DQ-47) | D-107, D-256 |
| Outlet phone, owner name, address | Personal (100 percent filled: the real exposure) | `app.outlet`; `dw.dim_outlet_pii` | in the bundle for the route screens (SR and AMO need them); in `dw` only in `dim_outlet_pii`; `phone_hash` (with a pepper version) for de-duplication; BI sees a masked form | D-107, D-108 |
| SR phone | Personal | `app.app_user` | not returned to an AMO view (11 asterisks, parity); `dim_user` holds the name and not the phone | D-108 |
| User position | Personal (location) | `app.geo_fix`, `app.user_last_fix` | a supervisor sees the last fix of the own team only; raw fixes age out at 6 months; no export except to the fraud role | D-110 |
| Outlet coordinates | Business location | `dw.dim_outlet` | visible to BI for density and geo analysis (ASSUMPTION: a small retailer's shop is also a home; doc 21 confirms, gap G-16-13) | D-254 |
| Passwords, tokens, OTP | Secret | `user_credential`, `refresh_token`, `device_otp` | hashes only; the OTP encrypted and revealable to the TSO | doc 21 |
| Photos | Personal (may show people) | Blob, private | SAS URLs per request with a short expiry; no public URL stored | doc 21 |
| Logs | none allowed | App Insights | ids only; a memo number is allowed, a phone is not | doc 21 |

The role-by-field matrix (`cfg.pii.field_roles`, `cfg.pii.mask_style`, `cfg.pii.export_allowed_roles`) is data owned by doc 19; every Excel export is logged in `report_export_log` (G-data-23, F-WEB-042).

### 13.3 BI and the read replica

| Item | Design | Decision |
| --- | --- | --- |
| Replica | an Azure Database for PostgreSQL Flexible Server read replica in region; dashboards, reports, Excel and BI read it with an "as of" stamp; live-state tiles read the primary (D-128, D-268) | D-374 |
| BI role | `bi_reader`: USAGE on `dw` only, SELECT on every dw table except `dim_outlet_pii`, nothing on `app` or `cfg`; private endpoint; the replica host name is the only one given to analysts | D-374 |
| Masking | `dw.v_outlet_masked` shows the phone as `01*****` plus the last three digits; the unmasked table is `pii_reader`'s | D-107 |
| Power BI | Import nightly for history; incremental refresh hourly on `agg_daily_*` partitioned by `business_date`; DirectQuery allowed only against `agg_*` tables; refresh windows exclude 17:30 to 21:00 Dhaka (the sync storm) | G-data-23 |
| Row level security | `dw.bi_user_scope (bi_login, node_type, node_id)` maintained from `app.user_scope`; Power BI RLS filters `dim_geo` by the viewer's nodes | D-374 |
| Lag | monitored on the replica; the alert and the sync-health degraded mode (zone level) both start above 60 s (D-128, D-427; the 120 s of the first draft is retired, OI-20-20) | doc 18 |
| Lake and engine (D-560, G-qa-92) | Event facts and large daily aggregates leave the database after `cfg.retention.event_fact_months` (25) into Parquet in Blob, and raw fixes after 6 to 24 months. The query engine is Microsoft Fabric (a Lakehouse with OneLake shortcuts to the Blob container, Parquet partitioned by `business_date`) read by Power BI; Fabric is chosen over Synapse serverless because Power BI needs a gateway-free path to it. The hot path is Power BI service to the replica through a VNet data gateway (a managed gateway in `snet-gw`, because the replica is private-endpoint only and public access is denied by policy), or Fabric mirroring of the replica where the Flexible Server is supported. Licences, the gateway and the Fabric capacity are costed in doc 18 s3.3 with their quotas. R1(d) is therefore stated honestly: any report over the HOT window (25 months) is a query on the replica; data older than that is a query on the lake, slower and with the same schema, proved by a 3-year range query through the lake (T-4-169) | D-371, D-560 |

### 13.4 Grants

```sql
-- M-39 database roles and grants (D-106, D-128, D-107): RLS policies themselves are doc 21
DO $$ DECLARE r text; BEGIN
  FOREACH r IN ARRAY ARRAY['api_rw','worker_rw','web_ro','bi_reader','pii_reader','support_ro'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN EXECUTE format('CREATE ROLE %I NOLOGIN', r); END IF;
  END LOOP; END $$;
GRANT USAGE ON SCHEMA app, cfg, dw, stg TO api_rw, worker_rw;
GRANT USAGE ON SCHEMA dw, cfg TO web_ro;    GRANT USAGE ON SCHEMA dw TO bi_reader;    GRANT USAGE ON SCHEMA app TO pii_reader, support_ro;
-- api_rw: ingest and admin writes; no DELETE on transactional tables (D-98)
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA app TO api_rw;   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA cfg TO api_rw;   -- UPDATE on app tables: master and lifecycle tables by the role map (s13.4b), capture tables by the policy generator
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO api_rw;                      -- EXECUTE on dw.enqueue comes with the SECURITY DEFINER block of s8.6
GRANT USAGE ON ALL SEQUENCES IN SCHEMA app, cfg TO api_rw;
-- worker_rw: reads app, owns dw
GRANT SELECT ON ALL TABLES IN SCHEMA app, cfg TO worker_rw;             GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA dw TO worker_rw;
-- worker_rw and api_rw UPDATE rights on capture tables are GENERATED from app.immutability_policy by app.apply_policy_grants() (D-566, G-qa-100): the literal GRANT UPDATE lists of the first
-- draft omitted columns the policy names (app.visit.sig_status and location_request_id, memo.sig_status, due_collection.sig_status and the 'both' writers of stock_movement, redemption and memo_void).
SELECT app.apply_policy_grants();       -- see s13.4b: writer 'worker' -> UPDATE(cols) to worker_rw only; 'api' -> UPDATE(cols) to api_rw only; 'both' -> both; 'none' -> neither
-- web_ro: every dashboard and report reads dw only; no SELECT on app.* (R1)
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO web_ro;                      REVOKE SELECT ON dw.dim_outlet_pii FROM web_ro;
GRANT SELECT ON cfg.code_list, cfg.code_item, cfg.qc_fault_type, cfg.holiday TO web_ro;
-- bi_reader: replica only, no PII, no app, no cfg
GRANT SELECT ON ALL TABLES IN SCHEMA dw TO bi_reader;                   REVOKE SELECT ON dw.dim_outlet_pii FROM bi_reader;
CREATE VIEW dw.v_outlet_masked AS
  SELECT o.outlet_key, o.outlet_id, o.outlet_code, o.outlet_name, o.channel, o.sub_channel_name, o.geo_classification, o.route_id, o.zone_id,
         '01*****' || right(p.contact_number, 3) AS contact_masked
    FROM dw.dim_outlet o LEFT JOIN dw.dim_outlet_pii p USING (outlet_id) WHERE o.is_current;
GRANT SELECT ON dw.v_outlet_masked TO bi_reader, web_ro;
-- pii_reader: column grants; NID, TIN and licence stay encrypted and are decrypted only by the API
GRANT SELECT (id, outlet_code, owner_name, contact_number, address) ON app.outlet TO pii_reader;    GRANT SELECT ON dw.dim_outlet_pii TO pii_reader;
-- support_ro: device lookup for L1 support (D-138)
GRANT SELECT ON app.device, app.device_day, app.bundle_download, app.reconcile_snapshot TO support_ro;
GRANT SELECT (id, uploaded_on, batch_uuid, device_id, user_id, uploaded_at, app_version, network_type, battery_pct, trigger, attempt,
              device_counts, accepted_counts, server_totals, rejected_count, conflict_count, parked_count) ON app.sync_batch TO support_ro;
-- partitions created later by app.ensure_partitions() inherit these through default privileges of the owner role
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA app GRANT SELECT, INSERT ON TABLES TO api_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE dw_owner IN SCHEMA dw GRANT SELECT ON TABLES TO web_ro, bi_reader, export_ro;
```

The DDL above is the first-draft shape of M-39, kept as the readable example. Its authority is s13.4b: the role map is data, the GRANT script is generated from it, and any difference between this text and the generated output is a defect fixed in the map. Row-level security policies and the `SET LOCAL app.user_id` protocol belong to doc 21 (D-106); T-0-07 attempts each forbidden read and write, and T-0-158 asserts every permitted one.

### 13.4b The one role map, generated grants and the positive privilege test (D-566, G-qa-100, G-qa-101)

Doc 16 defined seven roles (`api_rw`, `worker_rw`, `web_ro`, `bi_reader`, `pii_reader`, `support_ro`, `migrator`); doc 18 s2.6 defined eight different pooled names (`app_api`, `app_auth`, `app_worker`, `app_web`, `app_jobs`, `app_export`, `bi_reader`, `app_migrator`); doc 21 s3 named `worker_rw` and the migrator as the BYPASSRLS roles; and no document gave grants for `app_auth`, `app_jobs` or `app_export`, or a role able to run `ensure_partitions` and the archive-and-drop jobs. A superuser test connection would have passed every gate while production failed on the first `dw.enqueue`. The map below replaces all three statements; docs 18, 20 and 21 cite it and do not redefine a role.

Privilege roles are NOLOGIN groups; the login identities (Entra managed identities, doc 21 s5.1) are members. An owner role owns objects and is never used at run time.

| Login identity (doc 18 pool) | Privilege role | Rights (summary; the exact rows are `app.role_grant_map`) | RLS | Timeouts and pool |
| --- | --- | --- | --- | --- |
| `app_api` | `api_rw` | SELECT and INSERT on `app`; UPDATE only on master and lifecycle tables and on the policy columns whose writer is `api` or `both`; no DELETE on a transactional table; SELECT on `dw`; EXECUTE on `dw.enqueue`; SELECT, INSERT, UPDATE, DELETE on `cfg`; `SET ROLE pii_reader` or `support_ro` inside an audited transaction only | subject to RLS (FORCE) | api 15 s, pool 8 per replica |
| `app_auth` | `auth_rw` | SELECT, INSERT, UPDATE on `app.app_user`, `password_history`, `refresh_token`, `device`, `device_otp`, `device_integrity`; INSERT on `app.audit_log` and `security_event`; SELECT on `cfg`; EXECUTE on the `app.issue_*` and `app.bind_*` functions; nothing on capture tables | subject to RLS | auth 5 s, pool 2 |
| `app_worker` | `worker_rw` | SELECT on `app` and `cfg`; SELECT, INSERT, UPDATE, DELETE on `dw`; UPDATE only on the policy columns whose writer is `worker` or `both` (generated); EXECUTE on `dw.claim`, `dw.complete`, `dw.fail`, `dw.enqueue` and `app.settle_due_route_days()`; no direct UPDATE on `app.route_day` | BYPASSRLS | worker 10 min, pool 4 |
| `app_jobs` | `jobs_rw` (member of `worker_rw`) | everything of `worker_rw` plus EXECUTE on `app.ensure_partitions()`, `app.archive_partition()`, `dw.ensure_partitions()`, `dw.archive_partition()` (SECURITY DEFINER, owned by `app_owner` or `dw_owner`, `SET search_path = pg_catalog, app` or `dw`, which hold the only CREATE and DROP rights on partitioned tables), INSERT and UPDATE on `app.job_run`, `app.bundle_snapshot`, the `stg` schema for the importer, SELECT on `app.retention_hold` (the jobs obey it, D-600) | BYPASSRLS | jobs 30 min, pool 2 |
| `app_web` | `web_ro` | SELECT on `dw` and the code lists; no SELECT on `app` (R1); no PII table | none needed | web 60 s, pool 4 |
| `app_export` | `export_ro` | SELECT on `dw` except PII tables; INSERT on `app.report_export_log` | none | export 10 min |
| `bi_reader` (replica) | `bi_reader` | USAGE on `dw`, SELECT on every `dw` table except `dim_outlet_pii`; nothing on `app` or `cfg` | none | private endpoint |
| none (NOLOGIN) | `pii_reader`, `support_ro` | column grants of s13.4, reached only by `SET LOCAL ROLE` from `app_api` | n/a | n/a |
| `app_migrator` (direct 5432) | `migrator`, member of `app_owner` and `dw_owner` | DDL in CI only, `lock_timeout` | BYPASSRLS | n/a |
| none (NOLOGIN) | `app_owner`, `dw_owner` | own the tables, partitions, functions and default privileges; never granted to a login at run time | n/a | n/a |

Rules. (1) No login identity is a superuser or holds BYPASSRLS except `app_worker`, `app_jobs` and `app_migrator`; the Azure `azure_pg_admin` role is break-glass, two-person, rotated after use (doc 21 s5.1). (2) Every SECURITY DEFINER function pins `search_path`, has EXECUTE revoked from PUBLIC and is owned by a NOLOGIN owner. (3) `app.role_grant_map(role, object_type, object, privilege, columns, note)` is the data; `app.apply_role_grants()` (M-137) generates every GRANT and REVOKE from it, and `app.apply_policy_grants()` generates the capture-table column grants from `app.immutability_policy`, so the text and the policy cannot diverge. (4) The generator also emits `/plan/roles.yaml` for rtm-check.

```sql
-- M-137: the policy-driven part (the map-driven part is a loop over app.role_grant_map in the same shape)
CREATE FUNCTION app.apply_policy_grants() RETURNS void LANGUAGE plpgsql AS $$
DECLARE p record; cols text;
BEGIN
  FOR p IN SELECT tbl, mutable_cols, writer FROM app.immutability_policy LOOP
    cols := (SELECT string_agg(quote_ident(c), ',') FROM unnest(p.mutable_cols) c);
    EXECUTE format('REVOKE UPDATE ON app.%I FROM api_rw, worker_rw', p.tbl);
    IF cols IS NOT NULL AND p.writer IN ('worker','both') THEN EXECUTE format('GRANT UPDATE (%s) ON app.%I TO worker_rw', cols, p.tbl); END IF;
    IF cols IS NOT NULL AND p.writer IN ('api','both')    THEN EXECUTE format('GRANT UPDATE (%s) ON app.%I TO api_rw', cols, p.tbl); END IF;
  END LOOP;
END $$;
```

Tests. The first draft's gates asserted only FORBIDDEN operations (web_ro cannot read `app`, api_rw cannot DELETE), and the Testcontainers pyramid did not require tests to connect as the runtime roles. T-0-158 is the POSITIVE and complete matrix: for every row of `app.role_grant_map` and every capture table of the policy it compares `has_table_privilege`, `has_column_privilege`, `has_function_privilege`, `has_schema_privilege` and `pg_has_role` with the expected value and fails on a MISSING privilege as well as on an EXTRA one, and it fails if any login is a superuser or holds BYPASSRLS beyond the three above. T-1-153 runs the whole Phase 1 slice under the real login identities through PgBouncer in transaction mode, in CI and on staging: `/sync/batch` (including the enqueue), the settle timer, the dw worker, the partition and archive jobs, auth refresh and an export; it fails if `session_user` is a superuser. T-1-154 is the single regression for the bug above: a batch under `app_api` leaves a dirty row and the worker claims it under `app_worker`.

Proved by: T-0-05, T-0-07, T-4-02, T-4-108, T-6-05.

## 14 Gaps owned, decisions and gates minted

### 14.1 Decisions minted by this document (D-350 to D-379)

Status follows the plan legend. The doc 14 author copies each row into DECISIONS.md with date 2026-10-04.

| Id | Decision | Status | Why | Applied in docs |
| --- | --- | --- | --- | --- |
| D-350 | Line values are exact rationals of price x quantity rounded half-up to a milli-taka; the memo net is summed from those lines and rounded once to the paisa, half away from zero; the difference is stored as `round_adj_mtk`; `rounding_mode_used` is stored on the memo | DEFAULT; MUST-CONFIRM (by 2a) with D-19 | The memo must equal Apsis to the paisa; line precision moves to micro-milli-taka by migration if the baseline corpus diverges (gap G-16-02) | 16, 17, 20 |
| D-351 | `app.dhaka_date(timestamptz)` is declared IMMUTABLE so it can sit in generated columns and indexes; a CI test fails if the tzdata build changes the Asia/Dhaka offset | DEFAULT | Bangladesh has had a fixed +06:00 offset since 2009 (gap G-16-01) | 16 |
| D-352 | The ingest registry is global, hash-partitioned into 64 on `client_uuid`; UUID v4 is kept (v7 not adopted); uniqueness is enforced by the registry, not per partition | DEFAULT | CLAUDE.md v4 convention; the hot index of G-sre-26 is cured by partitioning | 16, 17, 18 |
| D-353 | DQ-41 refuses a content duplicate (REJECT, non-retryable) instead of flagging it; FS-24 is raised on the first record | DEFAULT (DELIBERATE CHANGE from the fraud critic's FLAG) | A flagged duplicate still doubles the sale | 16, 17, 21 |
| D-354 | The record signature (`sig`) is stored on header record types only; children are covered by the family signature; `sig_status` is on every row | DEFAULT | Signing every row adds about 190 MB a day at 3.0 M rows | 16, 21 |
| D-355 | PostGIS is adopted for outlet geometry (`geography(Point, 4326)`, GiST, `ST_DWithin`) | DEFAULT | TSO radius screen, density view, duplicate check, offline-equivalent recheck; the extension must be allow-listed on the Flexible Server (checked in 0b) | 16, 18 |
| D-356 | dw is a star schema: SCD2 dimensions, event facts, daily and month aggregates, balances, snapshots; every aggregate is recomputed by key and replaced, never incremented | DEFAULT | Hot rows and double counts (G-scale-02) | 16, 18 |
| D-357 | Every fact and aggregate that can hold history or web data has `source`, `fidelity` and `import_run_id`; a view picks one source per route-day by priority and the others are never added | DEFAULT | Migrated and web data must stay distinguishable (G-analyst-09) | 16, 19 |
| D-358 | As-reported snapshots and the restatement log exist from 1c, not 4a | DEFAULT | History that was never captured cannot be rebuilt (G-analyst-02) | 16, 20 |
| D-359 | Dues allocation for reporting is FIFO regardless of the retailer's choice; opening balances sit in an "opening" bucket aged from `age_basis_date` | DEFAULT | D-37; Q4 and Q12 of the analyst | 16 |
| D-360 | Worker: `gen` and `claimed_gen` coalescing, a claim timeout, dead-letter after 5 attempts with a sync-health alert, 5 s daytime poll and 60 s night | DEFAULT | D-140, D-263, G-sre-17 | 16, 18 |
| D-361 | No foreign key joins two partitioned event tables; children carry the parent client uuid and the server id; a nightly orphan check runs | DEFAULT | An FK at 3.0 M rows a day adds an index probe and a partition lock | 16 |
| D-362 | Web rows and app rows for one route-day are mutually exclusive by default: the effective-source view uses the app rows and the overlap is flagged (`web_app_overlap`) | DEFAULT | D-40 | 16, 19 |
| D-363 | `app.user_scope` is effective-dated and mirrored in `dw.dim_supervisor_assignment` | DEFAULT | "By AMO" after a transfer (G-analyst-04) | 16 |
| D-364 | Aggregates store numerators and denominators; a rollup sums first and divides once through `dw.pct()` | DEFAULT | An average of percentages is wrong | 16, 15 |
| D-365 | One `ReportQuery` object serves every report; `app.report_def` is the registry and a CHECK forbids a report from reading `app`, `cfg` or `stg` | DEFAULT | R1 enforced by the registry (G-man-095) | 16, 15, 19 |
| D-366 | Every report declares `min_fidelity`; route-level, SR-level and BSR reports require 3; migrated aggregates never feed them | DEFAULT | Path B has no route or SR (G-analyst-09) | 16, 15 |
| D-367 | Importer paths A (memo level) and B (aggregate only); archived stubs roll up to the Unmapped geography member until the dump supplies their last route; a re-import is a no-op through deterministic uuids | DEFAULT | docs/22 P-08; D-252 | 16, 20 |
| D-368 | Seven run-time roles: `migrator`, `api_rw`, `worker_rw`, `web_ro` (no SELECT on `app`), `bi_reader`, `pii_reader`, `support_ro`; operational queues read `app` through scoped repositories | DEFAULT | R1; D-106 | 16, 21 |
| D-369 | The replayable batch response is NULLed after 48 hours; counts live 13 months | DEFAULT | About 240,000 batches a day would add about 240 MB a day | 16, 17, 18 |
| D-370 | Product `sort` is per parent, not unique, ties broken by name; a segment sort may start at 3 | DEFAULT (PARITY) | The manual shows duplicate sort values | 16, 15 |
| D-371 | Retention classes are rows (`app.retention_policy`); a partition is dropped only after a verified export manifest; hot windows 13, 6, 3, 12 and 25 months by class | DEFAULT; MUST-CONFIRM (by 6c): statutory period | L-data s5.1; D-23, D-131 | 16, 18 |
| D-372 | A QC entry saved after the memo is committed is stored with `after_memo_commit` and reported as `qc_late_settlement_mtk`; whether the printed memo is reissued is unknown | DEFAULT; MUST-CONFIRM (by 2a) | The memo is immutable (gap G-16-06) | 16, 15 |
| D-373 | `pilot` accounts are excluded from national rollups and counted in `pilot_routes_excluded` | DEFAULT | Pilot numbers must not move the national tiles | 16, 20 |
| D-374 | BI reads the in-region replica as `bi_reader` (no PII table, no `app`), with a masked outlet view, `bi_user_scope` row-level security and Import plus incremental refresh outside 17:30 to 21:00 | DEFAULT | D-128, D-268 | 16, 18, 21 |
| D-375 | Business lists are `cfg.code_item` rows with text codes; enums remain only for structural states | DEFAULT | A fourth edit reason must not need a migration (G-cfg-10) | 16, 19 |
| D-376 | Migration numbers are reserved in blocks and the runner applies pending files in ascending order; CI rejects a reused number or one outside its block | DEFAULT | Several authors working in parallel | 16, 20 |
| D-377 | Import control totals per zone: zero tolerance for outlets, memo count, dues and points, STD exact in base units; every difference explained or the zone is not cut over | DEFAULT | docs/11 "no cutover until it matches" | 16, 20 |
| D-378 | The policy of each data-quality rule is data (`cfg.dq_rule`); rules protecting idempotency or scope are locked | DEFAULT | A FLAG to PARK change is an audited config change, not a deploy | 16, 19, 21 |
| D-379 | `dim_date.structure_basis = cutover_snapshot` labels months before the cutover, whose geography is the 1 October 2026 structure | DEFAULT | Q24, G-analyst-09 | 16, 15 |

### 14.2 The 65 gaps owned by this document

Severity and phase come from the plan register. Resolved means the text and objects named in the fourth and fifth columns exist in this document; the gate proves it.

| Gap | Severity | Phase | How it is resolved | Where | Gate |
| --- | --- | --- | --- | --- | --- |
| G-data-01 | blocker | 0b | `client_uuid` on every device-originated row, three idempotency layers on the global `ingest_registry`, the key of every table in s6.2 (alias G-data-11) | s6.1, s6.2 | T-1-01 |
| G-data-02 | blocker | 0b | capture context (route, assignment, zone, cluster, price list date, price type, config version, radius used, bundle version) stored on the row, @PROV block | s1.6, s5.3, s5.4 | T-1-03, T-3-100 |
| G-data-03 | blocker | 0b | bigint milli-taka, three-decimal prices, rounding rule, golden fixtures | s1.3 | T-0-02, T-0-08 |
| G-data-04 | blocker | 0b | `qty_entered`, `unit_entered`, `pack_factor`, `qty_base` on every quantity row; base unit per category; report unit (alias G-man-001) | s1.4, s3.2, s9.5 | T-2-07 |
| G-data-05 | blocker | 0b | two named KPIs: "Submit % (of logged-in)" (K-02) and "Day-completion %" (K-03), both returned with their basis (alias G-man-066) | s9.2, s9.3, s11.4 | T-1-106, T-3-01 |
| G-data-06 | blocker | 1b | `sync_rejected`, `sync_conflict`, registry and quarantine with the policy table `cfg.dq_rule`; no row vanishes | s6.1, s6.3, s7.1, s7.5 | T-1-02, T-1-04 |
| G-analyst-01 | blocker | 1c | `dw.fact_memo`, one row per memo with edits, prints, deductions and the printed due | s8.4 | T-1-100 |
| G-data-07 | blocker | 1c | dimensions, event facts, daily and month aggregates, balances, the dirty queue and the worker | s8 | T-0-100, T-4-01 |
| G-scale-02 | blocker | 1c | recompute by dirty key (never increment), coalescing, cascade of fixed depth | s8.6 | T-1-105 |
| G-man-002 | blocker | 2a | net = gross - offer - DRP - QC deduction stored as separate columns, QC settlement and per-SKU maximum, DQ-14, DQ-52, DQ-55 | s1.3, s5.4, s5.6, s7.2, s7.4 | T-1-06, T-2-05 |
| G-sync-04 | blocker | 2a | line values at mtk, one rounding to the paisa half away from zero, `round_adj_mtk` stored, DQ-15 and DQ-53 (alias G-feat-46) | s1.3, s7 | T-0-08, T-1-06 |
| G-analyst-02 | blocker | 4a | `dim_target`, nightly as-reported snapshots, `agg_restatement_log`, built in 1c so no history is lost | s8.3, s8.7, s10.1 | T-4-100 |
| G-cfg-10 | major | 0b | business lists are `cfg.code_list` and `cfg.code_item` rows, never enums; QC fault taxonomy table | s3.6, s1.2 | T-0-06 |
| G-data-13 | major | 0b | `entry_source`, `captured_offline`, `bundle_stale` on visit and memo and carried to dw | s5.3, s5.4, s8.4 | T-4-104 |
| G-data-24 | major | 0b | monthly partitions with `app.partition_policy`, a global hash-partitioned registry instead of per-partition uniqueness, no FKs between partitioned tables (alias G-scale-08) | s1.7, s6.1, s13.1 | T-0-05 |
| G-field-10 | major | 0b | `app.normalise_digits()` on device and server, phone canonical form, `phone_hash` with pepper version, DQ-44 | s1.2, s4.1, s7.4 | T-0-06 |
| G-data-16 | major | 0c | `user_credential`, `password_history`, `refresh_token`, `device_otp`, `device_integrity` (storage here, behaviour in doc 21) | s6.4 | T-0-07 |
| G-data-10 | major | 1c | `app.route_day` with a generated `state`, `supervisor_day`, `final_submit_route` | s11.2 | T-3-01 |
| G-data-22 | major | 1c | successful call, memo count, target outlets, till-date target and login event defined as SQL | s9.2, s9.4, s11.3 | T-1-106 |
| G-data-33 | major | 1c | `source` and `fidelity` on every fact and aggregate; imported sales are labelled so a sale not made in the apps stays visible (MUST-CONFIRM D-245) | s8.1, s12.1 | T-7-109 |
| G-scale-07 | major | 1c | `activity_log` is a monthly partition with a 3-month hot window, never on the hot path | s6.5, s13.1 | T-0-05 |
| G-sre-17 | major | 1c | dead-letter after 5 attempts with a sync-health alert, claim timeout, `gen` and `claimed_gen`, lag as a gauge | s8.6, s8.8 | T-1-105 |
| G-sync-07 | major | 1c | an offline `day_open` on a stale bundle counts as logged in and is flagged `offline_start`, DQ-66 | s11.3, s7.4 | T-1-106 |
| G-analyst-06 | major | 2a | `dim_sku_price`, `fact_price_change`, `list_price_mtk` and `price_mismatch` on the line | s8.3, s8.4 | T-2-103 |
| G-analyst-08 | major | 2a | `fact_memo_offer`, `bridge_offer_scope`, offer version set on the memo | s3.7, s8.2, s8.4 | T-2-103 |
| G-feat-52 | major | 2a | a memo stores `price_list_date` and per-line `price_valid_from`; DQ-13 flags a stale price, never rejects | s3.3, s5.5, s7.2 | T-2-103 |
| G-feat-45 | major | 2b | signed `fact_due_ledger`, FIFO `fact_due_allocation`, `agg_memo_due_open`, opening balance with `age_basis_date` (aliases G-analyst-05, G-field-09) | s5.7, s8.4, s9.3 | T-2-06, T-2-101 |
| G-data-18 | major | 2c | `outlet_photo.outlet_id` nullable with an owner check; `outlet_location_provisional` | s4.3 | T-2-08 |
| G-data-09 | major | 2d | device and server geo verdicts, `server_distance_m`, plausibility flags and `suspicion_score` stored separately | s5.2, s5.3, s7.2 | T-2-03 |
| G-analyst-03 | major | 3a | `agg_daily_user` by effective user, `amo_*` columns separate from `successful_calls` (alias G-field-16) | s8.5 | T-2-100 |
| G-analyst-04 | major | 3a | `app.user_scope` effective-dated, `dim_supervisor_assignment` | s3.4, s8.3 | T-3-100 |
| G-man-067 | major | 3a | till-date target as a per-surface basis and rounding key; `dw.tilldate_target()` | s9.5 | T-3-107 |
| G-data-17 | major | 3b | `leave_application`, `visit_plan`, `feedback`, `final_submit_route` snapshot, attempts and reopen trail | s5.10, s5.12, s11.5 | T-3-05 |
| G-man-100 | major | 3b | `route.kind` sr or amo, "SR Not Set" as a normal state, `cfg.kpi.target_route_kinds`, DQ-64 | s3.1, s7.4, s11.2 | T-3-01 |
| G-analyst-07 | major | 4a | `flags` on every row copied to `dw.fact_dq_flag`; three flags as columns | s7.5, s8.4 | T-2-102 |
| G-feat-09 | major | 4a | working-day calendar as data: `cfg.holiday`, weekend days, route-day override, `dim_date` (aliases G-data-30, G-analyst-10) | s11.1, s8.3 | T-3-107 |
| G-data-21 | major | 4b | dw-only queries for Free Sample, Discount, By-Route Geo Capture, Data Entry Log and Campaign Gift Redemption | s9.8 | T-4-104 |
| G-man-095 | major | 4b | one `ReportQuery` schema, `app.report_def` registry, filter vocabulary and outputs | s9.7 | T-4-104 |
| G-data-23 | major | 4c | PII classification, column grants, `report_export_log`, `v_outlet_masked`, `bi_reader` | s13.2 to s13.4, s6.5 | T-4-02, T-4-108 |
| G-data-14 | major | 5a | `program`, enrolment, per-outlet targets, gift catalogue and assignment, earn rules, ledger | s10.2 | T-5-10 |
| G-data-15 | major | 5c | `target_set`, `target_version`, revision lines, live-uniqueness index, non-negative CHECKs | s10.1 | T-5-01 |
| G-analyst-09 | major | 7a | `source`, `fidelity`, `import_run_id`; importer path B with its landing tables | s8.1, s12.1 | T-7-109 |
| G-data-19 | major | 7a | `stg.id_crosswalk`, `import_run`, `opening_balance`, deterministic uuid, `rollback_import` (alias G-sre-21) | s12.3, s12.5 | T-7-02, T-7-109 |
| G-data-34 | major | 7a | archived outlet stubs with history, dues and loyalty, mapped to the Unmapped member | s4.5, s12.4 | T-7-06 |
| G-data-35 | major | 7a | `stg.normalise_outlet_code()`, `stg.parse_created_at()`, quarantine for what cannot be parsed, DQ-46 | s12.4, s7.4 | T-7-109 |
| G-data-36 | major | 7a | duplicate keys summed and flagged, zero-volume lines dropped and counted, DQ-49 and DQ-50 | s12.4, s7.4 | T-7-109 |
| G-analyst-14 | minor | 1b | one trusted timestamp; `business_date_server`, `hour_of_day` and local columns derived from it | s1.5, s8.1 | T-1-102 |
| G-sre-26 | minor | 1b | registry hash-partitioned into 64 so the random-uuid index is bounded; UUID v7 not adopted | s6.1 | T-0-05 |
| G-analyst-15 | minor | 2a | `outstanding_before_mtk` and `printed_total_due_mtk` on the memo; `due_snapshot_mismatch`, DQ-54 | s5.4, s7.4 | T-1-100 |
| G-data-25 | minor | 2c | `ended_at`, `gps_retry_count`, suggestion snapshot and the force-sale photo link on the visit | s5.3 | T-2-03 |
| G-data-27 | minor | 2e | `support_upload`, `app_release` and `tutorial_asset` tables | s3.8, s5.10 | T-0-01 |
| G-analyst-12 | minor | 3a | `outlet_merge`, `phone_hash_pepper_version`, `bridge_outlet_effective` | s4.3, s8.2 | T-2-08 |
| G-data-26 | minor | 3a | `distribution_check` as header plus lines with a visit link; rubric definitions | s5.10, s3.8 | T-3-05 |
| G-feat-55 | minor | 3a | outlet code assigned at approval; closure and info approvals on the one request queue | s4.3 | T-2-08 |
| G-man-060 | minor | 3a | card capped, detail uncapped, band function, remaining clamp, zero or negative target is a dash | s9.2, s9.4, s9.5 | T-1-106 |
| G-man-064 | minor | 3a | AMO Sales Summary: CPR as K-04, zero-target dash, `fixed_ratio` basis | s9.5 | T-3-107 |
| G-analyst-11 | minor | 4a | `fact_visit.outcome`, `app_version` on facts, `fact_config_change.is_revert_of` | s8.4 | T-1-100 |
| G-analyst-16 | minor | 4a | `offline_start`, `bundle_version_at_open` on the route-day; `fact_device_day` | s8.4, s11.3 | T-1-106 |
| G-field-14 | minor | 4a | nightly memo-sequence gap detector, DQ-45 | s7.4 | T-2-05 |
| G-field-19 | minor | 4a | default date, comparator day `dw.prior_working_day()`, `agg_hourly_zone`, non-working-day banner | s9.6 | T-4-41 |
| G-data-28 | minor | 4b | candidate definitions shipped under non-committal names: retention candidate (K-16), both BSR denominators (K-10); TSO product scope open | s9.2, s9.4 | T-1-106 |
| G-feat-58 | minor | 4b | Online/Offline Sales from `captured_offline` and `entry_source`; business definition unknown | s9.8 | T-4-104 |
| G-man-038 | minor | 4b | status per product level, role-visible prices, sort per parent, no vendor strings | s3.2, s9.8 | T-4-104 |
| G-analyst-13 | minor | 5a | `cfg.astha.tier_attribution`, loyalty reversal rows, `freeze_after_days` | s10.2 | T-5-10 |
| G-data-32 | minor | 6c | retention classes as data with 7 years assumed; statutory period is an open item | s13.1 | T-6-05 |

### 14.3 New gaps minted (G-16-01 to G-16-20)

| Gap id | Severity | Phase | The gap | Where | Decision and handling | Gate |
| --- | --- | --- | --- | --- | --- | --- |
| G-16-01 | minor | 0b | `app.dhaka_date()` is IMMUTABLE on the assumption of a fixed +06:00 offset | s1.5 | D-351; CI timezone test | T-0-06 |
| G-16-02 | major | 2a | Per-line precision of one milli-taka may differ from Apsis by a paisa on a tie | s1.3 | D-350; baseline corpus T-0-49; move to micro-milli-taka before 2a exit if it diverges | T-0-08 |
| G-16-03 | major | 1c | The lens left route x SKU, outlet and user x SKU daily aggregates unpartitioned (about 340,000, 450,000 and 170,000 rows a day) | s8.5, s13.1 | monthly partitions and a 25-month hot window | T-0-05 |
| G-16-04 | minor | 1b | No place for the policy of a data-quality rule | s7.1 | D-378, `cfg.dq_rule` | T-0-06 |
| G-16-05 | major | 1b | The 13-month registry prune of the lens would keep 2.2 M rows a day for a year; replays beyond 48 hours cannot happen | s6.1, s13.1 | prune at 45 days, keep tombstones; `cfg.retention.ingest_registry_days` | T-0-05 |
| G-16-06 | minor | 2a | QC saved after the memo is committed: is the printed memo reissued? | s5.6 | D-372; unknown; confirm with the business (MQ-03) | T-2-05 |
| G-16-07 | minor | 4b | Nothing stops a report from reading `app` | s9.7 | D-365 CHECK on `report_def.source_objects` | T-4-104 |
| G-16-08 | minor | 4a | "Same time yesterday" needs history by hour; the memo fact is too big to scan per tile | s9.6 | `dw.agg_hourly_zone`, `dw.prior_working_day()` | T-4-41 |
| G-16-09 | major | 7a | Pre-cutover months have the 1 October structure, not the structure of their time | s12.1, s8.3 | D-379 label; the year-on-year chart says so | T-7-109 |
| G-16-10 | major | 7a | The sales file gives no route or zone for an archived stub, so 277 M sticks cannot roll up | s4.5, s12.1 | D-367 Unmapped member 0 until the dump gives the last route | T-7-06 |
| G-16-11 | major | 4c | Web entry and app memos for one route-day could be added together | s5.12, s8.5 | D-362 effective-source view, DQ-58 | T-4-104 |
| G-16-12 | minor | 6c | Route x SKU daily history older than 25 months is only in the lake | s13.1 | month aggregates stay online; OI-16-31 asks whether day level is needed online | T-6-05 |
| G-16-13 | minor | 4c | BI sees exact outlet coordinates | s13.2 | ASSUMPTION; doc 21 confirms or rounds them for `bi_reader` (OI-16-32) | T-4-108 |
| G-16-14 | minor | 3a | The AMO reconciliation counts "price compliance" with no screen in any manual; the fields are an assumption | s5.10 | `price_compliance_check` with assumed fields; confirm with the business | T-3-05 |
| G-16-15 | major | 2a | Match entry unit and Lighter box size decide every volume figure for those categories | s1.4 | D-16 MUST-CONFIRM by 2a; a data change before the first native Match memo | T-2-07 |
| G-16-16 | major | 7a | `rollback_import` cannot delete a master a device row already references | s12.5 | quarantine with reason `referenced_by_device_row`; roll forward | T-7-109 |
| G-16-17 | minor | 7a | Imported memos keep Apsis numbers while native ones use the new series: two number spaces in one report | s12.4, s5.4 | D-35; `memo_no` is unique per business date only | T-7-109 |
| G-16-18 | minor | 5a | Loyalty earning rules, expiry and the 199-point cap scope are unknown | s10.2 | D-41 MUST-CONFIRM by 5a; the cap is per redemption by default | T-5-10 |
| G-16-19 | minor | 5a | Astha tier attribution when an outlet changes tier mid-quarter is not evidenced | s10.2 | `cfg.astha.tier_attribution` default `enrolment`; the report shows the choice | T-5-10 |
| G-16-20 | minor | 2b | The credit limit semantics are unknown, so DQ-56 only flags | s7.4 | `cfg.credit.max_due_mtk` 0 means no limit; confirm with the business (MQ-17) | T-2-06 |

### 14.4 Gates minted (T-x-05 to T-x-09 and T-x-104 to T-x-109)

Doc 20 s5 copies these into the gate register. The analyst gates T-0-100, T-1-100 to T-1-102, T-2-100 to T-2-103, T-3-100 and T-4-100 are the renumbered analyst gates of the plan (s5.4) and are defined there.

| Gate | Proves | Phase |
| --- | --- | --- |
| T-0-05 | Registry and partition shape: 64 hash partitions, monthly partitions three months ahead, no foreign key between partitioned event tables, `app.check_orphans()` empty, 2,600 registry inserts a second on the reference server without p95 drift; `archive_candidates` honours each class | 0 |
| T-0-06 | Schema contract lint in CI: every device-originated table has `client_uuid`, @PROV and a row in s6.2; every flag emitted by `/api` is a `cfg.dq_rule` row; no enum for a business list; every migration number is inside its block | 0 |
| T-0-07 | Roles and immutability: `web_ro` cannot read `app`, `api_rw` cannot DELETE a transactional row, an UPDATE outside the allow-list raises 42501, `bi_reader` cannot read `dim_outlet_pii` | 0 |
| T-0-08 | Money at SQL level: the golden memo fixtures of s1.3 and D-19, the 7.935 round trip, and a property test of `app.div_half_up` and `app.round_to_paisa` | 0 |
| T-0-09 | Config data contract: every key read by ingest or dw has a registry row with bounds; the effective-dated lookup returns the value in force at a timestamp; `cfg.dq_rule` holds all 69 rules | 0 |
| T-1-05 | Worker enrichment grants: `worker_rw` updates only the allow-listed columns; replaying an enrichment changes nothing | 1 |
| T-1-06 | Memo arithmetic at ingest: DQ-14, DQ-15 and DQ-53 on 10,000 fuzzed memos; `round_adj_mtk` within 5 mtk; the printed total reproducible from stored columns | 1 |
| T-2-05 | Offer, DRP and QC decomposition: `memo_offer` components sum to the memo deductions; DQ-52, DQ-55 and DQ-45 fire on their fixtures | 2 |
| T-2-06 | Dues ledger: signs, supersede and void reversal, FIFO allocation equal to a reference model, opening bucket | 2 |
| T-2-07 | Unit handling: `qty_base` for every unit fixture (pack, stick, dozen, box), report-unit conversion, pack-factor mismatch flag | 2 |
| T-2-08 | Outlet data: merge keeps history, closure never deletes, location history and request lifecycle rows, nullable photo owner | 2 |
| T-2-09 | Void and tombstone: a voided `client_uuid` uploaded late is rejected `voided_by_admin`; a memo void reverses dues and stock as events | 2 |
| T-3-05 | Supervisor captures: idempotent upsert under duplicate and out-of-order fuzz for tasks, assessments, distribution, price compliance, leave, visit plan and feedback | 3 |
| T-6-05 | Masters with KPI effect are future-dated only: a past-dated price or target edit is refused or goes through approval with a blast-radius preview and an audit row | 6 |
| T-7-06 | Archived stubs: one per code, never in target outlets, history and dues preserved, rolled up to the Unmapped member | 7 |
| T-1-105 | Dirty queue: coalescing, `gen` and `claimed_gen` (a re-enqueue during a run releases the claim), claim timeout after a worker deploy, dead-letter after 5 attempts raises the alert, a rebuild never starves live work | 1 |
| T-1-106 | KPI SQL fixtures of s9.4 for K-01 to K-18, including every dash case (zero, negative and null denominators) | 1 |
| T-3-107 | Calendar and till-date: the April 2026 working-day fixtures (14 elapsed, 11 remaining, 25 working days), scoped holiday, make-up day, route-day override, and the per-surface till-date fixtures | 3 |
| T-4-104 | Report registry: every report of s9.8 runs as a dw-only query (the CHECK holds), `min_fidelity` excludes migrated aggregates from route, SR and BSR reports, the effective-source view picks one source per route-day | 4 |
| T-4-108 | BI: `bi_reader` grants, the masked view, `bi_user_scope` row-level security, replica lag alert at 60 s (D-128) | 4 |
| T-7-109 | Importer: the normalisation functions, deterministic uuid, re-import is a no-op, `rollback_import`, control-total difference report, quarantine at most 1 percent | 7 |
| T-0-150 | Capture-to-dw coverage lint: rtm-check rule 9 over the migrated DDL (every OFFLINE or QUEUED capture table has a dw object or a signed EX row; every column is mapped or matches EX-06) (D-500) | 0 |
| T-0-151 | jsonb lint: any jsonb column outside the sanctioned list J-1 to J-8 fails the build (D-501) | 0 |
| T-1-150, T-2-150 | Row-width measurement: bytes per row of every table on the synthetic load at 1c and 2e, fed into s8.11 and doc 18 s1.9 (D-520) | 1, 2 |
| T-4-150, T-4-151 | R1 drill and the capture-type analyst questions Q26 to Q41 under a role with no SELECT on `app` (D-500; doc 20 s10) | 4 |

Proved by: T-0-150, T-0-151, T-1-150, T-2-150, T-4-150, T-4-151, T-0-05, T-0-06, T-0-07, T-0-08, T-0-09, T-1-05, T-1-06, T-1-105, T-1-106, T-2-05, T-2-06, T-2-07, T-2-08, T-2-09, T-3-05, T-3-107, T-4-104, T-4-108, T-6-05, T-7-06, T-7-109.

### 14.5 Round-2 amendments (skeptic review, D-500 to D-553)

Three skeptics reviewed documents 14 to 21 against R1 to R6 and the process requirement. The gaps below changed this document; every other skeptic gap is handled in the document named in DECISIONS.md section M3. Gate ids are minted in doc 20 (T-x-150 and above).

| Gap | Decision | What changed here | Where | Gate |
| --- | --- | --- | --- | --- |
| G-qa-25, G-qa-40 | D-500 | 22 capture facts and one bridge, exclusion list EX-01 to EX-06, capture map and rule 9, analyst questions Q26 to Q41 | s8.2, s8.9, s15 | T-0-150, T-4-150, T-4-151 |
| G-qa-26 | D-501 | typed survey answer, typed radio columns, offer-version bridge, sanctioned jsonb list J-1 to J-8 | s3.7, s4.1, s5.2, s5.6, s5.9, s8.10 | T-0-151 |
| G-qa-37 | D-514 | the Apsis delta contract and its fallback | s12.2, s12.5, s12.6 | T-7-150, T-7-151 |
| G-qa-44 | D-520 | bottom-up row widths, 2.8 GB a day, 1.0 TB in year 1, registry 135 M rows | s8.11 | T-1-150, T-2-150 |
| G-qa-45 | D-521 | bisection instead of a savepoint per record, `cfg.sync.max_savepoints_per_tx` | s5 preamble, s6.1 | T-1-53 (extended), T-1-151 |
| G-qa-49 | D-524 | `app.immutability_policy` for every capture table, worker grants equal the allow-lists | s6.6, s13.4 | T-0-07 (extended), T-1-05 |
| G-qa-50 | D-525 | one placeholder-pin key, one till-date enum | s4.6, s7.4, s9.5 | T-6-60 (extended) |
| G-qa-53 | D-528 | recompute without a temp table, measured ms per key | s8.6 | T-4-54 (extended) |
| G-qa-65 | D-536 | Total Service Zone is derived, Final Submit ring | s3.1, s9.2 | T-4-41 |
| G-qa-66 | D-58 | K-13 stated symbolically | s9.2 | T-2-124 |
| G-qa-68 | D-538 | coordinates only, `locality_hint` derived | s5.8 | T-4-40 |
| G-qa-70 | D-539 | submit void on `route_day`, `submit_seq` | s11.2, s11.4, s6.2 | T-3-61 (extended), T-3-150 |
| G-qa-73 | D-542 | `emergency_off` holiday kind | s11.1 | T-4-154 |
| G-qa-74 | D-543 | paper-memo backfill, `entry_source` extended | s5.12, s2.3 | T-2-151 |
| G-qa-75 | D-544 | one default date, date stamp and reason chip | s9.6 | T-4-41 |
| G-qa-76 | D-545 | one open request per outlet, minimum move, accuracy filter, aging, bulk approve | s4.4 | T-4-152 |
| G-qa-80 | D-548 | switched-route dimension and coverage | s8.3, s8.5, s9.10 | T-4-153, T-7-152 |

### 14.6 Round-3 amendments (skeptic review, D-554 to D-601)

A third skeptic pass changed this document in the places below; every other gap is handled in the document named in DECISIONS.md section M4. Gate ids are minted in doc 20 s3.15.

| Gap | Decision | What changed here | Where | Gate |
| --- | --- | --- | --- | --- |
| G-qa-88, G-qa-122 | D-556 | DL-1b late delta and DL-2b, cut rule, straggler sheet, `apsis_residual` extension of DL-6 | s12.6, s12.6b | T-7-163, T-7-164 |
| G-qa-90 | D-558 | dw landing ratchet, `/plan/dw-landing.yaml` | s8.2 | T-0-156, T-0-100 (ratchet) |
| G-qa-91 | D-559 | `fact_device_integrity`, `fact_activity`, `agg_daily_screen_use`, `fact_consent`; capture map from the full record-type enum; Q42 to Q44 | s8.9, s15 | T-0-150 (extended), T-4-168 |
| G-qa-92 | D-560 | lake query engine and BI network path; R1(d) restated | s13.3 | T-4-169 |
| G-qa-100, G-qa-101 | D-566 | SECURITY DEFINER queue functions, generated grants, one role map, positive privilege test | s2.1, s8.6, s13.4, s13.4b | T-0-158, T-1-153, T-1-154 |
| G-qa-102 | D-567 | route-day and web-entry triggers; one source for K-01 and K-02 | s8.6b, s9.2 | T-1-155, T-3-158 |
| G-qa-103 | D-568 | one storage sizing (1,024 GiB), first grow by month 6 | s8.11 | T-0-159 |
| G-qa-108 | D-572 | resync window anchored at the restore point; `resync_late` | s7 DQ-09, DQ-71 | T-1-156 |
| G-qa-112 | D-576 | one bounding-box key | s7 DQ-22 | T-0-159 |
| G-qa-113 | D-577 | route-day void barrier | s5.12, s6.1, DQ-72 | T-4-166 |
| G-qa-114 | D-578 | DSS includes web-entered data; one-minute proof; as-of stamp | s9.8 | T-4-167 |
| G-qa-115 | D-579 | a final-submitted zone-day never rejects | DQ-57 | T-3-159 |
| G-qa-116 | D-580 | stock re-save guard; correction is a signed adjustment | s6.2 | T-2-161 |
| G-qa-139 | D-600 | legal hold | s13.1b | T-6-152 |

## 15 Appendix: validation by the 25 analyst questions

The 25 questions of the analyst critic are the acceptance test of the dw layer: each must be answerable from `dw` alone, with correct history and correct provenance. "Answered" means the objects named exist in s8 and the gap in the fourth column is closed by this document; the gate is T-0-100 for the inventory and the gate of each gap in s14.2 for the figures.

| Q | Question | dw objects that answer it | Gap closed | Verdict |
| --- | --- | --- | --- | --- |
| Q1 | Which outlets stopped buying brand X after the price change on a date? | `fact_price_change` for the date, `dim_sku_price` brand roll-up, `agg_daily_outlet_brand` before and after, `agg_daily_outlet.visited` to separate "visited, did not buy", `dim_outlet` status as of the after-window | G-analyst-06 | answered |
| Q2 | SR-level strike-rate trend by weekday | `agg_daily_user` joined to `dim_date.iso_dow`; `kind` filter and `effective_user_key` in the columns | G-analyst-03 | answered |
| Q3 | Geo-validation % by territory before and after a radius change | `agg_daily_route.geo_valid_calls`, `fact_visit.radius_m_used` and `config_version`, `fact_config_change`; the what-if uses `server_distance_m` | none | answered (already correct) |
| Q4 | Dues ageing by outlet channel | `agg_memo_due_open` with `dim_outlet.channel` as of today, `fact_due_allocation`, `dw.dues_bucket()` | G-analyst-05, G-feat-45 | answered |
| Q5 | Mock-GPS sales last month by AMO | `fact_visit.is_mock`, `active_memo_count`, `dim_supervisor_assignment` as of each day | G-analyst-04 | answered |
| Q6 | Target versus achievement restated after a revision: what was reported on the 12th, and now | `snap_month_zone_product` (as reported), `dim_target` (in force), `agg_restatement_log` (the revision) | G-analyst-02 | answered |
| Q7 | Memo edits per SR, reasons and value reduction | `fact_memo` self-join on `supersedes_memo_id`, `edit_reason_code`, `edit_net_reduction` | G-analyst-01 | answered |
| Q8 | Time from outlet open to memo print | `fact_memo.time_to_print_s`, `print_count`, `unprinted`, trusted time | G-analyst-01, G-analyst-14 | answered |
| Q9 | Late-synced sales by days of delay, territory and app version | `fact_memo.delay_days`, `app_version`, `dim_geo` as of the date, restatement log | G-analyst-11 | answered |
| Q10 | Planned outlets not visited for N consecutive planned days | `agg_daily_outlet(is_planned, visited)` and `agg_outlet_visit_streak`, planned by the calendar and overrides | G-analyst-10 | answered |
| Q11 | How often the printed price differs from the list price, per SR | `fact_memo_line.list_price_mtk`, `price_mismatch`, `agg_daily_route.price_mismatch_lines` | G-analyst-06, G-analyst-07 | answered |
| Q12 | Credit created in a month and collected within 30 days, by channel | `fact_due_allocation` with the memo date and collection date, `dim_outlet.channel` | G-analyst-05 | answered |
| Q13 | Did offer X work: STD in zones with and without it | `bridge_offer_scope`, `fact_memo_offer`, `agg_daily_route_sku` before, during and after | G-analyst-08 | answered |
| Q14 | Diamond League points earned, redeemed, verified and outstanding at month end by tier | `fact_loyalty_ledger`, `fact_redemption_line`, `agg_outlet_balance_daily`, `dim_outlet.astha_tier`, reversal rows | G-analyst-13 | answered |
| Q15 | On the outage morning, which routes fell below 70 percent Login, and were offline starts counted | `agg_daily_route.logged_in`, `offline_start`, `bundle_stale`; `agg_daily_zone.offline_start_routes` | G-analyst-16, G-sync-07 | answered |
| Q16 | New outlets approved per month by territory and how many merged or closed within 90 days | `fact_outlet_request`, `dim_outlet` status history, `outlet_merge`, `bridge_outlet_effective` | G-analyst-12 | answered |
| Q17 | Stock variance per SR per day and the chronic leakers | `agg_daily_user_sku.variance_base` (generated), `fact_stock_movement` | none | answered |
| Q18 | Outlet moved route on the 15th: whose month-to-date, and did anyone's achievement change retroactively | facts keep the route at capture (`route_id`, `geo_key`); `dim_outlet` valid by date; restatement log for the second half | G-analyst-02 | answered |
| Q19 | Average and p95 call duration and calls per worked hour per SR, excluding abandoned | `fact_visit.duration_s`, `outcome`; `agg_daily_user.avg_duration_s`, `p95_duration_s`, `calls_per_hour` | G-analyst-11 | answered |
| Q20 | Sales between 23:00 and 01:00: which business date and which hour | business date and `hour_of_day` from one trusted Dhaka timestamp | G-analyst-14 | answered |
| Q21 | SKUs sold in zones where they were not in the sales plan, by month | `fact_dq_flag` flag `sku_not_in_plan` and the column on `fact_memo_line` | G-analyst-07 | answered |
| Q22 | A retailer disputes a memo: what did the ledger say at that moment, and now | `fact_memo.printed_total_due_mtk`, `server_balance_at_print_mtk`, `fact_due_ledger` events up to the instant and after | G-analyst-15 | answered |
| Q23 | Visits validated against a radius changed and reverted within the day | `fact_visit.radius_m_used`, `config_version`; `fact_config_change.is_revert_of` | G-analyst-11 | answered |
| Q24 | Wing-level STD this October against last October across the cutover | `agg_month_zone_product` with `source`, `fidelity` and `structure_basis`; stubs in the Unmapped member are omitted and the screen says so | G-analyst-09, G-data-34 | answered with a stated caveat (G-16-10) |
| Q25 | Astha achievement by tier for Q3 against Q2 where an outlet moved tier mid-quarter | `agg_month_outlet_program.tier_code`, `enrolment_id`, `tier_attribution`; `dim_outlet.astha_tier` as of the date | G-analyst-13 | answered |

Capture-type questions added by D-500 (G-qa-25, G-qa-40): the first 25 questions are sales, dues, geo and configuration questions and hid the fact that no dw object existed for much of what the field captures. The questions below cover one capture type each and are run by gate T-4-151 under a database role that has no SELECT on `app` (the `bi_reader` role); a question that needs `app` fails the gate.

| Q | Question | dw objects that answer it | Verdict |
| --- | --- | --- | --- |
| Q26 | POSM compliance (sticker or banner present, photo taken) by wing and month | `fact_survey_answer` (`answer_bool`, `has_photo`) with `dim_geo` | answered (D-500) |
| Q27 | QC fault mix (the 11 MFC and MKT fault types) by brand, SKU and zone, and the late-settled share | `fact_qc_line` (`fault_type_code`, `fault_family`, `after_memo_commit`) | answered |
| Q28 | Empty packs collected against rewards given, by route and offer | `fact_drp_line` | answered |
| Q29 | Photo evidence upload lag by SR, zone and upload path (Wi-Fi, mobile fallback) | `fact_media` | answered |
| Q30 | Fix accuracy, mock rate and cell or Wi-Fi match by device model and app version | `fact_geo_fix` joined to `dim_device` | answered |
| Q31 | Visit skips by reason by territory and week | `fact_visit_skip`, `agg_daily_route.skips` | answered |
| Q32 | Memo voids, reprints and unconfirmed prints per SR | `fact_memo_event` | answered |
| Q33 | Cash handover variance by zone and week | `fact_cash_handover` | answered |
| Q34 | Due disputes by collector, amount and age | `fact_due_dispute` | answered |
| Q35 | TSO visit-plan completion by territory, and the assessments that followed | `fact_visit_plan`, `fact_assessment`, `fact_assessment_answer` | answered |
| Q36 | Leave days by type and approver lag | `fact_leave` | answered |
| Q37 | Feedback by category and handling time | `fact_feedback` | answered |
| Q38 | Day exceptions by reason and wing, supervisor days without a check-in | `fact_day_exception`, `fact_supervisor_day` | answered |
| Q39 | Content watch time by item | `fact_content_view` | answered |
| Q40 | Task assignment to resolution lead time by AMO | `fact_task`, `fact_task_event` | answered |
| Q41 | Final Submit lead time, resubmits and submit voids by zone | `fact_final_submit`, `fact_submit_void` | answered |
| Q42 | Which devices have low trust or a rooted hint, by model and app version | `fact_device_integrity`, `dim_device`, `dim_app_version` | answered (D-559) |
| Q43 | Which screens do SRs, AMOs and TSOs use, by week | `agg_daily_screen_use`, `fact_activity` (3 months) | answered (D-559) |
| Q44 | How many users accepted each version of the employee-location notice, and who has not | `fact_consent`, `dim_user` | answered (D-559) |

Ten checks that every answer above must pass (the analyst's conditions, adapted): the right dimension version for the date, supersession handled once, the business date from trusted time, quantities in different base units never added, a dash on a zero denominator, provenance shown, flags visible, restated history logged, pilot accounts excluded, and no read of `app`. Proved by: T-0-100, T-0-150, T-1-100, T-1-101, T-1-102, T-2-100, T-2-101, T-2-102, T-2-103, T-3-100, T-4-100, T-4-150, T-4-151.

## Open items

| Id | Item | Why open | Owner role | Needed by | What proceeds on the default meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-16-01 | Does the migration runner apply a lower number that arrives after a higher one (dbmate behaviour), and does CI enforce the number blocks? | D-376 depends on the runner; section 2.2 | doc 20 author | 0a | the blocks and CI check are written as stated |
| OI-16-02 | Lighter box size and Match entry unit (pieces or dozens); loose sticks and the stepper (D-16, D-17, D-194, D-248; MQ-01, MQ-02) | only the business knows | product owner, AKTCL sales ops | 2a entry | cigarette and bidi in sticks, lighter in pieces with `report_factor`, match in dozens |
| OI-16-03 | The memo must equal Apsis to the paisa (D-19, D-350, D-158); sample prints needed | rounding tie cases only show on a real corpus | finance reviewer | 1a (samples), 2a (gate) | half-up per line at mtk, one paisa rounding; micro-milli-taka migration ready (G-16-02) |
| OI-16-04 | Memo-number series: new series or continue Apsis (D-35; Q5, MQ-56) | retailer expectation | product owner | 1a | new series `<username>-<yyMMdd>-<seq3>`, imported memos keep their numbers; `memo_serial` optional |
| OI-16-05 | Business-date cutoff 00:00 (D-20; Q29) | observed, not confirmed | AKTCL operations | 1a | 00:00 Dhaka |
| OI-16-06 | Statutory retention of sales records and photos (D-23, D-371, G-data-32) | a legal question | AKTCL legal | 6c | 7 years archive, 13 months hot, photos per `cfg.retention.media_days` 730 |
| OI-16-07 | The AMO report row "136 of 0": a zero target with sales prints a dash under D-50, the manual value is ambiguous (G-man-064) | one capture is not enough | plan owner | 3a | dash |
| OI-16-08 | Do all sales in the export come through the SR and AMO apps, or does it include direct distributor billing (D-245, G-data-33) | the export has no source column | AKTCL data owner | 1c | every imported row is labelled; native-only CPR shown beside it |
| OI-16-09 | Sample .xlsx files for the 11 Excel-only reports so the column sets match (D-191, G-feat-53, MQ-47) | the manual shows none | AKTCL TSO desk | 4b | `columns_known = false` reports ship with the documented columns of s9.8 |
| OI-16-10 | Meaning of 17 and 15 in the AMO report, and the TSO capture date for the 26 of 30 basis (D-51, D-195; MQ-09, MQ-33) | two bases match one number each | AKTCL sales ops | 3a | `fixed_ratio` 15 over 17, TSO calendar including today |
| OI-16-11 | Web basis of Submit % (of logged-in) (D-45, D-177; MQ-34) | unproven until a day with real submits | plan owner | 3b | "Submit % (of logged-in)" captioned tiles; both figures returned |
| OI-16-12 | Is a free-sample-only memo a successful call, and is an all-zero outlet-day a zero-sale call (D-46, D-249) | needs memo-level data | AKTCL sales ops | 4a | yes and yes (ASSUMPTION) |
| OI-16-13 | BSR denominator and the meaning of retention (D-47, D-54; Q9, Q10) | not stated anywhere | AKTCL sales ops | 4b | both BSR figures; `retention_candidate_pct` under that name |
| OI-16-14 | Is Saturday a selling day (D-28; Q27) | the profile shows Saturday trading, the calendar says Friday only is off | AKTCL operations | 4a | Friday off, Saturday selling |
| OI-16-15 | Loyalty earning rules, expiry, cash-cap scope and catalogue (D-41; MQ-20 to MQ-22) | one rule is known (+50) | AKTCL marketing | 5a | +50 rule only; cap per redemption; expiry per `program_period.redeem_until` |
| OI-16-16 | Target approver chain, and whether the live target stays while a new set is pending (D-31, D-179; MQ-39) | level order not evidenced | AKTCL sales ops | 5c | one level, role WMO; live stays |
| OI-16-17 | Whose dues an AMO sees and settles (D-37; MQ-17); credit limit semantics (DQ-56) | not stated | AKTCL sales ops | 3a | the AMO's own credit memos only; no limit (0) |
| OI-16-18 | What web Delete and Status "exist" remove, whether Final Submit locks edits, how the back-date cut-off is produced (D-40, D-97, D-182; MQ-43, MQ-44, MQ-46) | not evidenced | AKTCL TSO desk | 4c | void with reason before Final Submit; cut-off 0 days with unlock grants |
| OI-16-19 | The live promotion catalogue (about 22 groups), QC settlement formula and per-SKU cap, where auto discounts render (D-33, D-34, D-166; MQ-03 to MQ-06); QC saved after commit (D-372) | the formulas are inferred from one example | AKTCL sales ops | 2a entry | settlement = defect sticks x price; cap shown as given; late QC reported separately |
| OI-16-20 | Which outlets are priced cc or distributor today (D-32; Q46) | price type is an outlet attribute | AKTCL sales ops | 2a | retail outlets use `outlet`, wholesale `cc` |
| OI-16-21 | Visit outcome codes: parity or addition (D-38; Q50) | whether the current app records a closed-shop outcome is unknown | plan owner | 2a | the codes of D-38 |
| OI-16-22 | Wholesale and C&C effects on price type, target outlets and the geo gate (D-42; Q17) | not specified | AKTCL sales ops | 6a | `outlet_kind` stored; no effect on counting until defined |
| OI-16-23 | Outlet request lifecycle: AMO reject and the cancel button, route of AMO-created outlets (D-43; MQ-29, MQ-31) | not evidenced | plan owner | 3a | pending, verified, approved or rejected (web only) |
| OI-16-24 | Do AMO routes count in Login %, and what "SS" means (D-29, D-187; MQ-38, MQ-19) | not stated | plan owner | 3b | `cfg.kpi.target_route_kinds = [sr]`; SS is a supervisor-tier designation |
| OI-16-25 | Legal opinion on NID, TIN and phone sensitivity and localisation; whether the live Excel carries NID (D-107, D-108); privacy sign-off for radio data (D-110) | legal | AKTCL legal, doc 21 owner | 7c, 2d | columns nullable and classified; masked BI view |
| OI-16-26 | Password-hash algorithm in the dump (D-119; Q18); quarantine threshold sign-off (D-153; Q39) | the dump is not seen | AKTCL IT | 7a | fresh binding; 1 percent threshold |
| OI-16-27 | Who may reopen a Final Submit and what may change (D-55; Q11); memo void after print (D-86; Q43) | not evidenced | AKTCL operations | 3b, 2b | audited admin reopen; void as an event under the edit rules |
| OI-16-28 | New config keys requested of doc 19: `cfg.sync.parked_ttl_days` (7), `cfg.agg.poll_interval_night_s` (60), `cfg.agg.coalesce_s` (20), `cfg.credit.snapshot_tolerance_mtk` (0), `cfg.retention.transactions_hot_months` (13), `cfg.retention.quarantine_months` (12), `cfg.retention.ingest_registry_days` (45), `cfg.retention.event_fact_months` (25), a web till-date basis key, the object shape of `cfg.kpi.tilldate_basis.<surface>`, `cfg.astha.tier_attribution` | doc 19 owns the registry | doc 19 author | merge | the defaults above apply. Resolved at the editorial merge: registered in doc 19 s3.2.6 and s3.2.8 as `cfg.sync.parked_ttl_days`, `cfg.agg.coalesce_s`, `cfg.credit.snapshot_tolerance_mtk`, `cfg.retention.quarantine_months`, `cfg.retention.ingest_registry_days`, `cfg.retention.event_fact_months`, `cfg.retention.geo_fix_archive_months`, `cfg.outlet.placeholder_pin_min_shared` (first named `cfg.geo.placeholder_min_shared`, retired by D-525), `cfg.astha.tier_attribution`; the first-draft name transaction_months is `cfg.retention.transactions_hot_months`; no web till-date key is created (D-51 names four surfaces) |
| OI-16-29 | DQ-41 refuses a content duplicate where the fraud critic flags it (D-353); doc 21 to confirm FS-24 handling | a deliberate disagreement with a critic | doc 21 author | 1b | REJECT, non-retryable, FS-24 on the first record |
| OI-16-30 | The worker poll, claim batch and 60 s freshness depend on the load model of doc 18 (D-140, D-263) | doc 18 derives the rates | doc 18 author | 1c | 5 s, 500, 300 s |
| OI-16-31 | Is day-level route x SKU history needed online beyond 25 months (G-16-12) | cost against use | AKTCL analytics | 6c | lake only; month aggregates online for ever |
| OI-16-32 | May `bi_reader` see exact outlet coordinates (G-16-13) | privacy | doc 21 author | 4c | yes |
| OI-16-33 | The skeleton claims 23 of 42 seed prices carry three decimals; the data shows 23 price values on 20 SKUs (D-15) | a counting slip, no design effect | doc 14 author | merge | milli-taka stands. Resolved: D-15, doc 14 s5 row 14 and doc 20 T-0-02 now say "23 price values on 20 of the 42 SKUs" |
| OI-16-34 | This document is about 3 times the length budget of the plan (80 to 130 KB): about 100 KB is tested DDL, which the build needs as the schema specification, and the rest carries 65 gaps, 30 decisions, 26 new rules and the traceability tables | the scope is larger than the skeleton assumed | doc 14 author | merge | the document stands whole; the DDL blocks can move to an appendix file at merge without changing any id |
| OI-16-35 | The sponsor signs the sanctioned jsonb list J-1 to J-8 and the exclusion list EX-01 to EX-06 (D-500, D-501) | R1(a) changes from "none in a blob" to "none except the list" | sponsor's delegate | 0b exit | the build proceeds on the lists as written; 0b cannot exit unsigned |
| OI-16-36 | Will Apsis supply the delta contract of s12.6, and who is the named delta owner (D-514) | nobody has asked; Apsis is being exited | sponsor, legal, sales operations | 0c exit | the AKTCL-staff fallback of s12.6 |
| OI-16-37 | Does the business want stored street addresses in GIGO, with which provider (D-538) | cost and quota unknown | sales operations | 4b | coordinates plus `locality_hint` |
| OI-16-38 | Is a six-month hot window for `fact_geo_fix` (about 90 M rows) acceptable against a 3-month window (D-500) | storage against forensic use | analytics, fraud owner | 2d | six months, matching `app.geo_fix` |
| OI-16-39 | The `app.paper_backfill` fields and the DQ-70 rule are an ASSUMPTION until support has handled a real dead-phone case (D-543) | no manual describes the case | support lead | 6c | the fields of s5.12 |
| OI-16-40 | The BI path: whether the Fabric capacity, the VNet data gateway or Fabric mirroring of the Flexible Server replica is available and licensed as assumed in s13.3, and the Power BI licence count (D-560) | Product availability and AKTCL licensing | engineering, IT | 4c | the replica read through a VNet data gateway; the lake query waits |
| OI-16-41 | Real zone and outlet counts for the programme tiers (Astha about 76,100 outlets) to size the programme aggregates and the programme pilot (D-561) | the dump | data lead | 5a | the profile estimate |
| OI-16-42 | Whether Apsis keeps usage logs that give per-day activity by screen (D-565); `fact_activity` is the NEW system's own record | Apsis admin views unknown | IT | 0c | observation log |

## Traceability

### Features (doc 15 ids)

| Ids | Handled in |
| --- | --- |
| F-ADM-003 | s3 |
| F-AMO-042, F-SR-052 | s5 |
| F-SYS-027, F-WEB-050 | s7 |
| F-WEB-001, F-WEB-002, F-WEB-004, F-WEB-010, F-WEB-011, F-WEB-012, F-WEB-013, F-WEB-014, F-WEB-015, F-WEB-016, F-WEB-017, F-WEB-018, F-WEB-019, F-WEB-020, F-WEB-021, F-WEB-022, F-WEB-023, F-WEB-024, F-WEB-025, F-WEB-026, F-WEB-027, F-WEB-028, F-WEB-029, F-WEB-031, F-WEB-034, F-WEB-036, F-WEB-037, F-WEB-044, F-WEB-045, F-WEB-047, F-WEB-049, F-WEB-053, F-WEB-054, F-WEB-055, F-WEB-056, F-WEB-058, F-WEB-059, F-WEB-061 | s9 |
| F-WEB-042 | s13 |

### Gaps

| Ids | Handled in |
| --- | --- |
| G-data-01, G-data-03, G-data-04, G-data-09 | s1, s2, s14 |
| G-16-01, G-16-15 | s1, s14 |
| G-16-02 | s1, s14, Open items |
| G-analyst-14 | s1, s8, s14, s15 |
| G-cfg-10 | s1, s2, s3, s14 |
| G-data-02 | s1, s2, s5, s14 |
| G-data-06 | s1, s2, s7, s14 |
| G-sync-04 | s1, s7, s14 |
| G-field-03, G-man-008, G-man-011, G-man-014, G-man-015, G-man-017, G-man-021, G-man-029, G-man-031, G-man-033, G-man-034, G-man-036, G-man-040, G-man-041, G-man-053, G-man-055, G-man-071, G-man-072, G-man-074, G-man-080, G-man-083, G-man-085, G-man-086, G-man-091 | s2 |
| G-data-13, G-data-24, G-data-26, G-man-001, G-man-067, G-scale-02 | s2, s14 |
| G-field-01, G-man-049, G-man-050, G-man-052, G-man-070, G-man-082 | s2, s5 |
| G-data-20, G-man-004, G-man-043, G-man-051, G-man-088 | s2, s3 |
| G-analyst-01, G-analyst-06, G-analyst-08, G-analyst-11 | s2, s8, s14, s15 |
| G-cfg-23, G-man-046, G-man-087 | s2, s7 |
| G-data-10, G-data-17, G-feat-09 | s2, s11, s14 |
| G-data-16, G-scale-07, G-sre-26 | s2, s6, s14 |
| G-analyst-15, G-feat-45 | s2, s5, s7, s8, s14, s15 |
| G-data-07, G-sre-17 | s2, s8, s14 |
| G-data-14, G-data-15 | s2, s10, s14 |
| G-data-22, G-man-095 | s2, s9, s14 |
| G-data-35, G-data-36 | s2, s7, s14 |
| G-field-02, G-man-032 | s2, s11 |
| G-analyst-02 | s2, s8, s10, s14, s15 |
| G-analyst-03 | s2, s5, s8, s14, s15 |
| G-analyst-04 | s2, s3, s8, s14, s15 |
| G-analyst-07 | s2, s7, s8, s14, s15 |
| G-analyst-10 | s2, s9, s14, s15 |
| G-analyst-12 | s2, s4, s7, s14, s15 |
| G-analyst-13 | s2, s7, s10, s14, s15 |
| G-analyst-16 | s2, s8, s11, s14, s15 |
| G-data-18 | s2, s4, s14 |
| G-data-19 | s2, s12, s14 |
| G-data-23 | s2, s13, s14 |
| G-data-27 | s2, s3, s14 |
| G-data-34 | s2, s14, s15 |
| G-feat-52 | s2, s3, s7, s8, s14 |
| G-field-10 | s2, s4, s7, s14 |
| G-man-002 | s2, s5, s7, s14 |
| G-man-003 | s2, s3, s5 |
| G-man-037 | s2, s4, s7 |
| G-man-038 | s2, s3, s9, s14 |
| G-man-068 | s2, s10 |
| G-man-089 | s2, s3, s5, s7 |
| G-man-090 | s2, s7, s10 |
| G-man-100 | s2, s3, s7, s14 |
| G-sync-07 | s2, s7, s11, s14, s15 |
| G-field-04, G-man-044 | s3 |
| G-field-08 | s3, s4 |
| G-16-10 | s4, s8, s14, s15 |
| G-man-035 | s4 |
| G-16-06, G-16-11, G-16-14, G-field-09 | s5, s14 |
| G-feat-01, G-field-05, G-field-20 | s5 |
| G-field-14 | s5, s7, s14 |
| G-fraud-05, G-man-039 | s6 |
| G-data-25 | s7, s14 |
| G-man-016 | s7 |
| G-analyst-09 | s8, s12, s14, s15 |
| G-field-19 | s8, s9, s14 |
| G-man-092, G-man-097 | s9 |
| G-feat-53 | s9, Open items |
| G-feat-58 | s9, s14 |
| G-feat-22, G-man-045, G-man-047 | s10 |
| G-feat-29 | s11 |
| G-16-16, G-sre-21 | s12, s14 |
| G-analyst-05 | s12, s14, s15 |
| G-16-12, G-16-13, G-data-32 | s13, s14, Open items |
| G-16-03, G-16-04, G-16-05, G-16-07, G-16-08, G-16-09, G-16-17, G-16-18, G-16-19, G-16-20, G-data-05, G-data-11, G-data-21, G-data-28, G-data-30, G-feat-46, G-feat-55, G-field-16, G-man-060, G-man-066, G-scale-08 | s14 |
| G-data-33, G-man-064 | s14, Open items |

### Decisions

| Ids | Handled in |
| --- | --- |
| D-44, D-58 | box, s9 |
| D-140 | box, s8, s14, Open items |
| D-15 | box, s1, s2, s3, Open items |
| D-16 | box, s1, s3, s5, s9, s10, s14, Open items |
| D-17 | box, s3, s5, s9, Open items |
| D-18 | box, s1, s5, s7, s9 |
| D-19 | box, s1, s5, s7, s9, s14, Open items |
| D-21 | box, s1, s6, s7, s12 |
| D-22 | box, s1, s5, s6 |
| D-23 | box, s1, s13, s14, Open items |
| D-24 | box, s1 |
| D-350 | box, s1, s2, s3, s5, s7, s14, Open items |
| D-352 | box, s6, s14 |
| D-353 | box, s6, s7, s14, Open items |
| D-355 | box, s2, s4, s14 |
| D-356 | box, s8, s14 |
| D-358 | box, s10, s14 |
| D-361 | box, s1, s14 |
| D-365 | box, s9, s14 |
| D-367 | box, s4, s8, s12, s14 |
| D-368 | box, s1, s2, s8, s9, s14 |
| D-376 | box, s2, s14, Open items |
| D-61 | box, s8 |
| D-104, D-228, D-237 | s1 |
| D-354, D-375 | s1, s14 |
| D-131 | s1, s13, s14 |
| D-20 | s1, Open items |
| D-213 | s1, s5 |
| D-242 | s1, s3 |
| D-244 | s1, s9 |
| D-251 | s1, s4, s7, s12 |
| D-28 | s1, s2, s7, s8, s9, s11, Open items |
| D-351 | s1, s2, s14 |
| D-48 | s1, s5, s9 |
| D-49 | s1, s9, s10 |
| D-50 | s1, s2, s7, s9, s10, Open items |
| D-65 | s1, s5, s6 |
| D-87 | s1, s3, s5 |
| D-98 | s1, s6, s7, s11, s13 |
| D-106 | s2, s13, s14 |
| D-138 | s2, s13 |
| D-14 | s2 |
| D-26 | s2, s5, s7, s8, s9, s11 |
| D-31 | s2, s3, s10, Open items |
| D-143, D-167, D-180, D-199, D-200, D-227 | s3 |
| D-257, D-259 | s3, s12 |
| D-33, D-187 | s3, Open items |
| D-363, D-370 | s3, s14 |
| D-113 | s3, s6 |
| D-197 | s3, s5 |
| D-258 | s3, s10, s12 |
| D-29 | s3, s9, s11, Open items |
| D-32 | s3, s4, Open items |
| D-34 | s3, s5, s7, Open items |
| D-373 | s3, s8, s14 |
| D-38 | s3, s5, Open items |
| D-39 | s3, s11 |
| D-41 | s3, s5, s10, s14, Open items |
| D-85 | s3, s5, s7, s11 |
| D-95 | s3, s4 |
| D-25, D-163, D-172 | s4 |
| D-42, D-43 | s4, Open items |
| D-107 | s4, s8, s13, Open items |
| D-111 | s4, s7 |
| D-253 | s4, s7, s12 |
| D-256 | s4, s7, s13 |
| D-10, D-36, D-74, D-77, D-78, D-79, D-117, D-125, D-152, D-161, D-176, D-300, D-304 | s5 |
| D-86, D-97, D-182 | s5, Open items |
| D-201, D-246 | s5, s7 |
| D-110 | s5, s13, Open items |
| D-249 | s5, s7, s9, s12, Open items |
| D-262 | s5, s6, s11 |
| D-35 | s5, s12, s14, Open items |
| D-359 | s5, s8, s14 |
| D-362 | s5, s7, s8, s14 |
| D-37 | s5, s9, s14, Open items |
| D-372 | s5, s14, Open items |
| D-40 | s5, s7, s8, s14, Open items |
| D-55 | s5, s7, s9, s11, Open items |
| D-56 | s5, s9 |
| D-62, D-63, D-66, D-101, D-103, D-123 | s6 |
| D-75, D-267 | s6, s7 |
| D-108 | s6, s13, Open items |
| D-119 | s6, s12, Open items |
| D-30 | s6, s7, s9, s11 |
| D-369 | s6, s14 |
| D-158 | s7, Open items |
| D-250 | s7, s12 |
| D-252 | s7, s12, s14 |
| D-378 | s7, s14 |
| D-135, D-136 | s8 |
| D-357, D-360 | s8, s14 |
| D-128 | s8, s13, s14 |
| D-263 | s8, s14, Open items |
| D-364 | s8, s9, s14 |
| D-379 | s8, s12, s14 |
| D-51 | s8, s9, Open items |
| D-53 | s8, s9 |
| D-45, D-46, D-47, D-54 | s9, Open items |
| D-57, D-64 | s9, s11 |
| D-366 | s9, s14 |
| D-52 | s9 |
| D-179 | s10, Open items |
| D-266 | s10 |
| D-27, D-247 | s11 |
| D-71 | s11, s12 |
| D-126, D-255, D-303 | s12 |
| D-153 | s12, Open items |
| D-371 | s12, s13, s14, Open items |
| D-377 | s12, s14 |
| D-132, D-254 | s13 |
| D-268, D-374 | s13, s14 |
| D-245 | s14, Open items |
| D-166, D-177, D-191, D-194, D-195, D-248 | Open items |

### Gates

| Ids | Handled in |
| --- | --- |
| T-1-06, T-2-03 | s1, s5, s7, s14 |
| T-0-05 | s1, s2, s6, s8, s13, s14 |
| T-0-06 | s1, s2, s7, s14 |
| T-0-07 | s1, s6, s13, s14 |
| T-0-08 | s1, s3, s14 |
| T-0-49 | s1, s14 |
| T-1-01 | s1, s5, s6, s8, s11, s14 |
| T-1-02 | s1, s6, s7, s14 |
| T-1-03 | s1, s3, s8, s14 |
| T-1-102 | s1, s14, s15 |
| T-2-07 | s1, s5, s14 |
| T-3-100 | s1, s8, s14, s15 |
| T-4-01 | s1, s8, s14 |
| T-4-100 | s1, s8, s9, s10, s14, s15 |
| T-6-05 | s1, s13, s14 |
| T-0-03, T-0-04 | s2 |
| T-0-01 | s2, s3, s4, s5, s12, s14 |
| T-0-02 | s2, s3, s10, s14 |
| T-0-09 | s3, s14 |
| T-1-04 | s4, s6, s7, s9, s10, s14 |
| T-2-08 | s4, s14 |
| T-7-06 | s4, s12, s14 |
| T-2-06, T-2-09, T-3-05 | s5, s14 |
| T-2-01 | s5 |
| T-2-02 | s5, s7 |
| T-2-05 | s5, s7, s14 |
| T-1-05 | s6, s14 |
| T-7-109 | s7, s12, s14 |
| T-1-100, T-2-102, T-2-103 | s8, s14, s15 |
| T-2-04, T-4-54 | s8 |
| T-1-101 | s8, s15 |
| T-1-105 | s8, s14 |
| T-1-106, T-4-104 | s9, s14 |
| T-3-107 | s9, s11, s14 |
| T-4-03 | s9 |
| T-5-01, T-5-10 | s10, s14 |
| T-3-01, T-4-41 | s11, s14 |
| T-3-02 | s11 |
| T-7-01 | s12 |
| T-7-02 | s12, s14 |
| T-4-02, T-4-108 | s13, s14 |
| T-0-100, T-2-100, T-2-101 | s14, s15 |

### Config keys

| Ids | Handled in |
| --- | --- |
| cfg.code_item | s1, s2, s3, s5, s7, s13, s14 |
| cfg.day.month_close_grace_days | s1, s7 |
| cfg.sale.qty_entry_unit | s1, s3 |
| cfg.sync.max_backdate_days | s1, s7, s8 |
| cfg.config_item, cfg.config_value, cfg.route_day_override | s2, s3, s11 |
| cfg.territory_geo_config, app.rollout_wave | s2, s3 |
| cfg.qc_fault_type | s2, s3, s5, s13 |
| cfg.config_ack, cfg.config_change_audit, cfg.geo.radius_m, cfg.qc.expired_stock_months, app.rollout_wave_member, cfg.config_version_scope | s3 |
| cfg.code_list | s3, s13, s14 |
| cfg.holiday | s3, s8, s11, s13, s14 |
| cfg.kpi.target_route_kinds | s3, s14, Open items |
| cfg.geo.update_base_max_distance_m, cfg.outlet.close_block_if_dues, cfg.outlet.reject_requires_reason | s4 |
| cfg.outlet.placeholder_pin_min_shared (retires cfg.geo.placeholder_min_shared, D-525) | s4, s7 |
| cfg.credit.allow_partial_collection, cfg.credit.allow_zero_payment, cfg.kpi.count_abandoned_visits, cfg.memo.allow_negative_net, cfg.sale.suggested_qty_enabled, cfg.visit.abandon_min | s5 |
| cfg.day.checkout_earliest_time, cfg.sale.max_lines_per_memo | s5, s7 |
| cfg.credit.snapshot_tolerance_mtk | s5, s7, Open items |
| cfg.retention.ingest_registry_days | s6, s13, s14, Open items |
| cfg.retention.sync_batch_response_h | s6, s13 |
| cfg.sec.fraud.* | s6 |
| cfg.bundle.stale_max_days, cfg.day.multi_visit_same_outlet_policy, cfg.geo.fix_reuse_max_age_s, cfg.geo.gps_time_skew_max_s, cfg.geo.integrity_weight, cfg.geo.max_accuracy_m, cfg.geo.max_speed_kmh, cfg.geo.mock_policy, cfg.media.photo_max_kb, cfg.outlet.mobile_regex, cfg.qc.max_qty_per_cell, cfg.release.blocked_versions, cfg.sale.max_line_qty_base, cfg.sec.fraud.location_move_alert_m, cfg.sec.record_signature_mode, cfg.sync.max_clock_skew_min, cfg.web.entry_validate_calls_le_target | s7 |
| cfg.credit.max_due_mtk, cfg.dq_rule | s7, s14 |
| cfg.sync.parked_ttl_days | s7, Open items |
| cfg.agg.claim_batch, cfg.agg.late_data_recompute_days, cfg.agg.poll_interval_s | s8 |
| cfg.agg.coalesce_s, cfg.agg.poll_interval_night_s | s8, Open items |
| cfg.astha.quarter_start_month | s8, s10 |
| cfg.calendar.weekend_days | s8, s11 |
| cfg.day.take_action_after, cfg.kpi.bands, cfg.kpi.bar_bands, cfg.kpi.dues_buckets, cfg.kpi.submit_pct_denominator, cfg.kpi.tilldate_rounding.<surface>, cfg.ops.report_export_max_rows, cfg.report.page_size_options | s9 |
| cfg.kpi.tilldate_basis.<surface> | s9, Open items |
| cfg.loyalty.cash_max_points, cfg.loyalty.expiry_days, cfg.target.approval_levels, cfg.target.split_method | s10 |
| cfg.astha.tier_attribution | s10, s14, Open items |
| cfg.day.exception_requires_approval, cfg.day.submit_settle_timeout_min, cfg.weekend_days | s11 |
| cfg.pii.export_allowed_roles, cfg.pii.field_roles, cfg.pii.mask_style, cfg.retention.activity_log_days, cfg.retention.geo_fix_months, cfg.retention.geo_fix_archive_months | s13 |
| cfg.retention.media_days, cfg.retention.event_fact_months, cfg.retention.quarantine_months, cfg.retention.transactions_hot_months | s13, Open items |

### Requirements

| Requirement | Handled in |
| --- | --- |
| R1 DATA: every captured field has a typed column with provenance; the web role has no SELECT on transactional tables; every dashboard and report is a query on dw | s1.6, s5, s6, s8, s9.7, s9.8, s13.4 |
| R2 FEATURES: every report, KPI, programme and day-state rule of the manuals has a data home | s9, s10, s11 |
| R3 SCALE: partitions, a hash-partitioned registry, recompute by dirty key, retention and archival, sized on the load model | s1.7, s6.1, s8.5, s8.6, s13.1 |
| R4 BATTERY: one position sample per event, compact rows, the replayable response dropped after 48 hours, device telemetry inside the sync batch | s5.2, s6.3, s8.4 |
| R5 OFFLINE AND IMMEDIATE SYNC: idempotent upsert for every device table, a 5-second worker poll and a 60-second freshness target, the settle rule for Submit | s6, s8.6, s11.4 |
| R6 ADMIN CONFIG: config data contract, rule policy as data, retention as data, report registry, calendar as data | s3.9, s7.1, s9.7, s11.1, s13.1 |
| Process requirement (plan first, then build phase by phase) | s2.3 (sub-milestone of every table), s14.4 (gates), Open items |

### Added at the editorial merge

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-02, D-03, D-118, D-150, D-157, D-159, D-162, D-165, D-168, D-178, D-183, D-189, D-193, D-196, D-202, D-211, D-215, D-231, D-236, D-240, D-260, D-309. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-25, G-qa-40 | s8.2, s8.9, s15 |
| Gaps | G-qa-26 | s3.7, s4.1, s5.2, s5.6, s5.9, s8.10 |
| Gaps | G-qa-37 | s12.2, s12.5, s12.6 |
| Gaps | G-qa-44 | s8.11 |
| Gaps | G-qa-45, G-qa-49, G-qa-50, G-qa-53 | s5, s6.1, s6.6, s13.4, s4.6, s9.5, s8.6 |
| Gaps | G-qa-65, G-qa-66, G-qa-68, G-qa-70, G-qa-73, G-qa-74, G-qa-75, G-qa-76, G-qa-80 | s3.1, s9.2, s5.8, s11, s5.12, s9.6, s4.4, s9.10 |
| Decisions | D-500, D-501, D-514, D-520, D-521, D-524, D-525, D-528, D-536, D-538, D-539, D-542, D-543, D-544, D-545, D-548 | s8.9 to s8.11, s12.6, s6, s9, s11, s4.4 |
| Gates | T-0-150, T-0-151, T-1-150, T-2-150, T-4-150, T-4-151, T-4-152, T-4-153, T-7-150, T-7-151, T-7-152, T-2-151, T-3-150, T-4-154 | s8.9 to s8.11, s9.10, s12.6, s14.4, s14.5 |
| Config keys | cfg.sync.max_savepoints_per_tx, cfg.outlet.placeholder_pin_min_shared, cfg.outlet.location_request_min_move_m, cfg.outlet.location_request_max_accuracy_m, cfg.outlet.bulk_approve_consistent_m, cfg.outlet.bulk_approve_min_evidence, cfg.sla.location_request_escalate_h, cfg.sla.location_request_lapse_days, cfg.day.sales_submit_locks_capture, cfg.day.submit_undo_window_min, cfg.kpi.leaderboard_min_switched_pct, cfg.retention.geo_fix_archive_months | s4.4, s4.6, s6.1, s9.10, s11 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-88, G-qa-90, G-qa-91, G-qa-92, G-qa-100, G-qa-101, G-qa-102, G-qa-103, G-qa-108, G-qa-112, G-qa-113, G-qa-114, G-qa-115, G-qa-116, G-qa-122, G-qa-139 | s14.6 |
| Decisions | D-556, D-558, D-559, D-560, D-566, D-567, D-568, D-572, D-576, D-577, D-578, D-579, D-580, D-600 | s14.6 |
| Migrations | M-123 to M-129, M-137 to M-139 | s2.3 |
| DQ rules | DQ-71, DQ-72, DQ-73 | s7 |
| Gates | T-0-156, T-0-158, T-0-159, T-1-153, T-1-154, T-1-155, T-1-156, T-2-161, T-3-158, T-3-159, T-4-166, T-4-167, T-4-168, T-4-169, T-6-152, T-7-163, T-7-164 | s14.6 |
| Analyst questions | Q42 to Q44 | s15 |
