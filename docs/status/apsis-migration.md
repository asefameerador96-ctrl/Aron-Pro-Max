# Apsis history migration: design and status (lane M-APSIS1)

## Handover (read first)
- Lane: M-APSIS1, branch `lane/apsis-migration` (from the integration branch `claude/wonderful-thompson-k6ejnf`). Started 2026-11-18.
- **The dump has never been seen** by this lane and never will be: it is not in this container, the repository, the test account, CI or any log. Everything here is built blind from `docs/reference/apsis-db-inventory-2026-11.md` and is made to adapt to the real column names when it runs inside the final account (the alias file in `tools/apsis-migration/mapping/`).
- Push 1 (this file): the design. Push 2: `tools/apsis-migration/` (loader, transformation SQL, control table, reconciliation, dry run, synthetic tests, provisional migrations). Push 3: `docs/runbooks/apsis-history-migration.md` and `docs/requests/apsis-dump-landing.md`.
- Provisional migrations are **not** in `db/migrations` (that folder is packaged into every image and run by every deploy). They sit in `tools/apsis-migration/provisional-migrations/V0260..` and are applied only by this lane's tests, on top of `db/migrations`. The lead moves them into `db/migrations` at landing and renumbers; see "Provisional migrations" below.
- `D-OWNER-FINAL-AZURE-01` is cited in the lane brief but is not in `DECISIONS.md` of this checkout; the design rests on `D-OWNER-APSIS-DUMP-01`, `D-OWNER-MASTERDATA-01`, `D-OWNER-PARITY-02`, `D-OWNER-CREDENTIALS-01/-02` and `docs/28`/`docs/33`. The lead should check that the decision text exists on the integration branch.

## 1. Principles
1. The migration runs only inside the final Azure account, as a Container Apps job next to the private PostgreSQL, and reports only counts and checksums.
2. Re-runnable by construction: every target row has a deterministic key (UUID v5 of namespace + vendor table + vendor key); every load is an upsert by that key; running twice changes nothing.
3. Restartable: per-table, per-chunk checkpoints in a control table; a killed run resumes at the first unverified chunk.
4. Reversible: every imported row is findable by marker; the archive schema can be dropped; master rows carry `external_ref = 'apsis:<table>:<id>'`; a PITR restore point is taken first.
5. No personal data in logs, reports, tests or this repository. Reports hold numbers only.
6. Money is integer milli-taka computed with exact decimal arithmetic (never float); quantities are base units via the SKU's pack factor.

## 2. (a) Questions only the dump can answer
Each has what we assume until answered. The loader reads the dump's own `CREATE TABLE` statements, so most column answers arrive automatically; the owner (or the laptop session) only needs to run the schema-only profile described in the runbook, which prints structure and counts, no rows.

| # | Question | Assumed until answered |
|---|---|---|
| Q1 | Dump format: mysqldump `.sql`, per-table files, mydumper (schema + chunk files), CSV/TSV; compressed (`.gz`, `.zst`)? One file or many? | 26 `.sql` files, one per table (the screenshot in the inventory shows `ARON_Half_Yearly_Data`, 26 `.sql`, 52.5 GB, largest 31.1 GB); mysqldump with extended inserts; maybe `.gz`. The loader also accepts `.sql.gz` and TSV with a header. |
| Q2 | Character set and collation of the tables and of the dump (`utf8mb4`? `latin1` with Bangla stored as UTF-8 bytes?) | `utf8mb4`. The loader decodes as UTF-8 with `errors=strict` and counts and reports undecodable rows rather than guessing. |
| Q3 | MySQL version (5.7 or 8.0) | 8.0 (Azure Database for MySQL Flexible Server). Affects only dump syntax (`/*!40101 ... */` comments, `_binary` literals, generated columns); all handled. |
| Q4 | Time zone of every temporal column (`datetime` has none; Azure MySQL default is UTC) and whether any columns are `timestamp` | `datetime` columns hold UTC; `date` columns hold the Dhaka business date as is. The dry run reports the hour-of-day histogram of one `trans_*` datetime column in UTC and in Dhaka; field activity starts about 06:00-08:00 Dhaka, so the histogram that has its morning ramp at 06:00-08:00 is the Dhaka one (a UTC column ramps at 00:00-02:00). The owner confirms; the choice is one setting (`source_tz`). |
| Q5 | Soft-delete convention: `is_deleted`, `deleted_at`, `status`, `is_active` | Any of `is_deleted`, `deleted_at`, `is_active`, `status` found in a master table is carried to `status` (`closed` when deleted or inactive); history rows are never dropped for being deleted: they are archived with a `vendor_deleted` flag. |
| Q6 | Which of the 26 tables hold credentials, tokens, OTPs or device data | Inventory has none by name. Suspects to confirm in the profile: `master_field_forces` (very likely a password hash, mobile number, device id), `master_ff_attendance_records` (device id, app version), `live_geo_data_by_outlets` (device id, IMEI-like fields). **Not migrated, ever:** any column whose name matches `pass|pwd|hash|salt|token|otp|secret|key|imei|fcm|session|device` (the loader drops them at staging and reports the column name and "dropped"), and the whole table if it is a token or OTP store. Every user gets a new one-time password per D-OWNER-CREDENTIALS-01/-02; no vendor hash is ever reused or compared. |
| Q7 | Primary keys (single or composite) and any natural keys | Read from `CREATE TABLE`. A table without a primary key uses the md5 of the canonical row text as its key, so exact duplicate rows collapse into one. |
| Q8 | How a zero sale is represented in `trans_sales_by_outlet` (a row with qty 0, a flag, a null SKU) | A row per outlet/day with quantity 0 (or a flag) is a zero sale; a row with no SKU is a visit-level zero sale. Dry run counts each shape. |
| Q9 | Units and money in the vendor tables: quantity in sticks, packets or dozens? price/amount `decimal(m,n)` or float, in taka or paisa? | Quantity in the SKU's base unit; amounts decimal in taka. If the dump shows `float`/`double` amounts the loader stages them as `double precision`, the dry run flags it, and conversion goes through `numeric` rounding half up to the milli-taka and is reported as "float source" (cannot be exact). |
| Q10 | Are the 980,274 outlets / 14,524 routes / 8,078 field force the superset (inactive and historical) of the October workbook (737,159 / 11,325 / 6,954)? | Yes. Active as of 2026-09-30 maps to our active masters; the rest is archived without personal data (see section 6). |
| Q11 | Do `trans_sales_by_outlet` rows carry memo numbers, or are they daily outlet-by-SKU aggregates ("by outlet, by SKU, by day")? | Aggregates, **no memo numbers**. This decides the landing place (section 5): history is not a set of memos. If memo numbers do exist the memo-number uniqueness check (section 4) runs on them and a `memo_no` column is kept in the archive. |
| Q12 | Do any columns hold images, blobs or file paths (photos)? | Not in the 26 tables. Photos are not part of this migration. |
| Q13 | Is `live_geo_data_by_outlets` one row per outlet per day (visit geo) or a point stream? | One row per outlet per day with the visit coordinates, distance, validation flags. If it is a stream, the same table design holds; only the sizing changes. |
| Q14 | Per-day duplicates: can the vendor have two rows for the same outlet/SKU/day (re-sync)? | Possible; kept as separate vendor rows (their keys differ) in the archive, and a view `hist_sales_day` sums them. Reconciliation compares both the row count and the summed totals to the vendor's `trans_section_summary`. |
| Q15 | The owner's decision on archived inactive outlets/routes/field force (open item of the inventory) | Archive (section 6). Do not create app masters for them. |

## 3. (b) Table-by-table mapping (26 tables)
Column names are **assumed canonical names** (listed in `tools/apsis-migration/mapping/columns.json` with a list of candidate vendor names per canonical name; the first candidate present in the dump wins; a canonical column that no candidate matches is reported as "missing" and the dependent step stops, it never invents a value). **Masters are matched, never created or overwritten (D-APSIS-MIG-06):** the October master-data load (docs/reference/master-data-loader.md) creates and owns the live masters; this tool matches the dump's masters to ours by business code, fills `apsis_map` (id map and lookup tables), stubs outlets we do not have (codes only) and reports the gaps as counts. The one optional master write is `--fill-locations` (coordinates only where ours are NULL). History rows go to the new `apsis_hist` schema. Where the table below says an upsert or insert into `app.*`, read it as "match by code and record the id"; the column-level fill of a master from the dump (names, phones, created dates, M01-M22 rules) stays with the master-data loader and is requested there if the dump holds more than the October workbook.

Key rule everywhere: `row_uuid = UUIDv5(NS, "<vendor_table>:<vendor_pk>")`, `NS = UUIDv5(NAMESPACE_URL, "urn:aktcl:aron:apsis-migration:v1")`, computed by the loader (Python `hashlib.sha1`, test vector pinned in the tests). Composite keys join with `|`. Vendor ids go into `apsis_map.id_map(vendor_table, vendor_pk, our_table, our_id, row_uuid)`.

| # | Vendor table | Target | Key mapping | Conversion and rules | Dropped / no home |
|---|---|---|---|---|---|
| 20 | master_locations | `app.wing`, `app.division`, `app.territory` | by `code` (our `code` unique); `external_ref='apsis:master_locations:<id>'` on insert only | level column decides the table; parent by parent code; Bangla name into `name_bn` when the column is Bangla | none |
| 16 | master_dep_location_mapping | zone to territory chain | zone code to territory code, effective-dated | If a zone's territory differs from the current one the current master wins and the difference is listed (count) for the owner | effective-dated history of moves: no home (request R1) |
| 19 | master_dep | `app.zone` (+ `app.house` where the dep is a distribution house) | by zone `code` | `dep_name` into `zone.dep_name` | none |
| 11 | master_clusters | `app.cluster` | (zone, normalised name) as the existing importer | `cluster_type` as is | cluster-to-route link has no home (R2) |
| 14 | master_routes | `app.route` | by route `code` | `status` from active flag | vendor route attributes we have no column for (R3) |
| 13 | master_sections | `route.visit_kind`, `visit_days_mask` | route code | day patterns to bit0 Sat..bit6 Fri (docs/reference/master-data-mapping M02, M03: no plan stays "no plan") | none |
| 25 | master_section_configuration | code list of the eight visit-day patterns | by code | reference only; checked against ours, mismatches listed | none |
| 15 | master_field_forces | `app.app_user` (role by designation), `employee_code` | by `employee_code`/username as the October master data (M13) | **never** loads a password hash; names/phones only for users that are active as of 2026-09-30 and match an existing or to-be-created account; accounts are created by the credentials generator (D-OWNER-CREDENTIALS-01/-02), not by this tool; this tool only maps vendor id to our user id for history | credentials, device ids, tokens: dropped |
| 12 | master_ff_route_mapping | `app.route_assignment` | (user, route, valid_from) | primary/cover kind; `valid_from`/`valid_to` as Dhaka dates | none |
| 8 | master_outlets | `app.outlet` | by outlet `code` | existing outlet (from the October master data load) is **not overwritten** except fields that are NULL; unknown active outlets are inserted with `external_ref='apsis:master_outlets:<id>'`; created/updated dates into `source_created_at/source_updated_at` (M20); phone normalised (M08) | name/phone of inactive outlets: not loaded |
| 9 | master_outlet_section_mapping | `outlet.route_id`; history into `apsis_hist.outlet_route` | outlet code, route code, valid range | current mapping updates NULL route_id only; the history is archived | none |
| 10 | master_geo_config | `outlet.lat/lng`, `location_basis='master'` | outlet code | Bangladesh bounding box; round to 8 decimals (M01); `location_confirmed` follows the owner's switch (M01 ruling: taken at load time, flag `--confirm-locations`) | out-of-box points listed by count, not loaded |
| 5 | master_outlet_classifications | `outlet.channel`, `sub_channel_id`, `geo_class` | outlet code | M05 (strip " Channel"), M06/M07 tiers, M15 mismatches NULL tier, M16 geo class mapping | none |
| 24 | master_sku | `app.sku` | by SKU `code` | `base_per_pack` from the pack factor; entry units per rule 5 are our rule, vendor value is a check | vendor SKUs not in our 42-row list: listed (R4) |
| 23 | master_product_classifications | product category/segment/brand/variant | by name path | read to confirm the hierarchy only | none |
| 22 | master_category | code lists (channel, sub-channel, classification) | by code | feeds the M05..M07 mapping tables | none |
| 21 | master_price_list | `app.sku_price` | (sku, price_type, valid_from) | amount (taka decimal) to `amount_mtk = round_half_up(amount x 1000)`, `per_base_qty` from the vendor price basis | price groups beyond our five price types: R5 |
| 26 | master_price_group | `price_type` mapping | by code | maps group to `outlet/cc/distributor/reporting/nto` | unmatched groups listed |
| 1 | trans_sales_by_outlet | `apsis_hist.sales_line` | `row_uuid` of the vendor pk | `qty_base` (vendor qty x pack factor if the vendor unit is a pack); `gross_mtk` exact decimal; `business_date` = Dhaka date; zero sales as `is_zero_sale`; outlet/SKU/route/user resolved to our ids where the master exists, otherwise `NULL` id with the vendor code kept | none |
| 2 | live_geo_data_by_outlets | `apsis_hist.visit_geo` | `row_uuid` | `lat/lng`, accuracy, distance, validation flags, `captured_at` UTC + Dhaka `business_date` | device ids dropped |
| 4 | master_ff_attendance_records | `apsis_hist.attendance` | `row_uuid` | check-in/out UTC + Dhaka date, coordinates | device ids dropped |
| 6 | trans_section_summary | `apsis_hist.route_day_vendor` | (route, date) | kept **only** as the vendor's own figures for reconciliation; our route-day numbers are recomputed from facts | none |
| 3 | trans_by_outlet_promotion_data | `apsis_hist.promotion_raw` | `row_uuid` | deferred programme (docs/27, standing rule 2): archived as typed columns + the full raw row as `jsonb`; no behaviour built | no home in `app` by design |
| 7 | master_sr_target_scope | `apsis_hist.target_scope_raw` | `row_uuid` | deferred (docs/27); archived as raw `jsonb` + keys | R6 only when the owner un-defers targets |
| 17 | master_sr_targe_configuration | `apsis_hist.target_config_raw` | `row_uuid` | same | same |
| 18 | master_promotion | `apsis_hist.promotion_config_raw` | `row_uuid` | same | same |

Requests (what has no home in our schema; **no column is invented**): R1 effective-dated zone/territory moves; R2 cluster-to-route link; R3 vendor route attributes unknown until Q-profile; R4 vendor SKUs outside our 42; R5 price groups beyond five types; R6 target/promotion behaviour (deferred programmes; data safe in the archive). R1-R3 and R5 get an owner/lead decision after the schema-only profile.

### Conversions (exact)
- Money: `amount_mtk = floor(amount_decimal * 1000 + 0.5)` on `numeric` (half up for non-negative; `app.div_half_up` semantics for negatives). No `float`/`double` in any conversion; the unit tests compare with `Decimal`.
- Quantity: `qty_base = qty_vendor * pack_factor` when the vendor unit is a packet/box/dozen, else as is; the pack factor comes from `app.sku.base_per_pack` (cigarettes 20s/10s, matches dozens, lighter box 25 as the field rule 5; bidi pending confirmation: flagged).
- Time: staging keeps temporal values as text; transformation does `(ts_text::timestamp AT TIME ZONE <source_tz>)` to `timestamptz` (source tz from Q4, default UTC) and `business_date = (captured_at AT TIME ZONE 'Asia/Dhaka')::date` (our `app.dhaka_date`). MySQL zero dates (`0000-00-00`) become NULL and are counted.

## 4. (c) Load order, performance, restartability
**Order** (each step a chunked, verified stage): 1 profile (schema only) - 2 geography (20, 16, 19) - 3 products/prices (26, 22, 23, 24, 21, 25) - 4 routes, clusters, sections (14, 13, 11) - 5 users map (15), route assignments (12) - 6 outlets (8, 9, 10, 5) - 7 small facts: attendance (4), section summary (6), promotion/target archives (3, 7, 17, 18) - 8 big facts by month: geo (2) then sales (1) - 9 reconcile, sign off, build dw views and indexes.

**Staging.** Each vendor table is loaded as text-typed columns into `apsis_stage.<table>` (UNLOGGED, no constraints, no indexes), COPY in 50k-100k-row batches, parallel one process per table (4 in parallel on the job's vCPUs). After a table's transformation is verified its staging copy is truncated, so disk never holds both fully: peak staging is the largest unfinished table's chunk set (geo, about 31 GB of SQL text, loaded and transformed month by month).

**Sizing (estimates, to be replaced by the measured dry run, never promised).** Dump 52.5 GB. In PostgreSQL: `sales_line` about 122 M rows x about 90 bytes = 11-14 GB heap + indexes 8-10 GB; `visit_geo` about 89 M rows x about 110 bytes = 10-13 GB + indexes 6-8 GB; the rest under 5 GB. Final database growth 45-60 GB. During the load add staging (peak one month of the largest table, about 4-6 GB) and WAL; plan **250 GB free storage** and the storage autogrow on (the final account's choice; test account never gets this).
**Duration (estimate).** Python parse of extended-insert text about 8-15 MB/s/process: 52.5 GB over 4 processes = 3-5 hours of loading; transformation SQL (set-based, per month) 1-2 hours; index builds 1-2 hours; reconciliation 30 minutes. Plan a **12-hour window**, no peak-selling-hours. A larger Container Apps job (8 vCPU) lowers it; measured in the final account on the first month of data before the full run.

**Load-window database settings** (set by the runbook for the load role only where possible, reverted after): `max_wal_size` 64 GB, `checkpoint_timeout` 30 min, `synchronous_commit = off` for the loader session, `maintenance_work_mem` 2 GB, `wal_compression = on`, `autovacuum` disabled **per archive table** (`ALTER TABLE ... SET (autovacuum_enabled = false)`) during the load and one `VACUUM (ANALYZE)` after; `bulk` facts are loaded into the partitions **without indexes**, indexes created after the month verifies (`CREATE INDEX CONCURRENTLY` is not needed on tables nothing reads yet). Foreign keys: none on archive tables (resolved ids are checked by reconciliation, not by constraint).

**Restartable.** `apsis_ctl.run` and `apsis_ctl.chunk(run_id, vendor_table, chunk_key, state, rows_in, rows_out, checksum, started_at, finished_at, error)`; chunk keys are `all` (masters) or `YYYY-MM` (facts). State machine `pending -> staged -> transformed -> verified`. The runner picks the first chunk not `verified`; a chunk re-run first deletes its staging rows and its archive rows by chunk key then reloads (idempotent either way: the upsert is by `row_uuid`). A SIGTERM at any point leaves the chunk in a state the next run repairs. Tests prove: kill mid-load and resume gives identical counts and checksums.

## 5. (e) Where the history lands
**Recommendation (decision D-APSIS-MIG-02): an archive schema `apsis_hist`, not the live `app.memo` / `app.visit` tables, for the 122 M and 89 M-row facts.** Reasons from our schema as it is (V0001-V0204):
1. `app.memo` / `app.memo_line` / `app.visit` require values history cannot supply: `memo_no` matching `^[a-z][a-z0-9]{3,31}-[0-9]{6}-[0-9]{3,4}$`, `config_version`, `sig`, a `visit` parent, `verdict`, `radius_m_used`, `geo_action`, `sequence_no`, `line_kind`, `unit_entered`; checks like `memo_net_equation` and `memo_paid_due`. Faking them would put invented values into the system of record.
2. Those tables carry write-once guard triggers, `data_origin` stamping, ingest-registry and dirty-key projection triggers: 122 M rows would enqueue 122 M aggregate events and slow the live system.
3. The vendor sales table is aggregated by outlet/SKU/day, not by memo (Q11), so there is no memo to rebuild.
4. Deleting by marker is a `DROP`/`TRUNCATE` of an isolated schema: no touching of live partitions, no autovacuum or bloat on live tables.
Masters (outlets, routes, assignments, prices) go to the existing `app` tables because the live system needs them, upserted by business code, tagged `external_ref = 'apsis:<table>:<id>'` and `data_origin = 'production'` (no new enum value needed).

**Layout.** `apsis_hist.*` monthly range-partitioned on `business_date` (the `app.partition_policy` mechanism is not used; partitions are created by the loader for the months present: 2025-11 to 2026-09, plus a default partition that must stay empty, which reconciliation checks). Read access only through `dw.v_hist_*` views, granted to `bi_reader` and `web_ro`; **no grant to `api_rw`, `worker_rw`, `jobs_rw`** for the sync path: the sync engine cannot read history, by privilege, and a test asserts it (`has_table_privilege`). Server-side scope: every archive row carries `zone_id`, `route_id`, `territory_id`, `division_id`, `wing_id` (resolved at load), and the dw views are filtered by the same scope columns the existing dw views use, in the analytics layer, so a TSO sees only their territory's history; rows whose geography could not be resolved are visible to national roles only.
**Reports.** Reports and dw views that **must** include history (docs/reports: monthly trend, same-month-last-year, outlet sales history, route productivity Dec-Sep) read `dw.v_hist_*` unioned in a separate view family (`dw.v_sales_with_history`); the standard day/week/live reports and anything computing "today", dues, targets or loyalty do **not** include history. Phones never download history: the bundle rules are untouched (outlet last-N-visits remains whatever the bundle already allows); no migration here touches the bundle.

## 6. (d) Reconciliation and report
`tools/apsis-migration/sql/reconcile.sql` runs in the job and produces one numbers-only file `reconciliation-<run_id>.json` (and a text twin), signed off by the owner approving the runbook step: every figure has the vendor value (from the staging copy, which equals the dump) and ours:
- rows per table, and per month for the transactional tables (`tables`, `months`);
- per-day money totals: sum of `gross_mtk` per `business_date` against the staging sum x 1000 (difference must be exactly 0);
- per-SKU quantity totals in base units;
- outlets per route (masters) against the vendor's `master_outlet_section_mapping`;
- memo-number uniqueness (when Q11 says memo numbers exist): duplicates count must be 0 or equal to the vendor's own duplicate count, listed by count only;
- `trans_section_summary` target outlets / visited / successful calls per route-day against our recomputation (differences counted, not named);
- control: default partitions empty, no row with `outlet_id IS NULL` beyond the declared "unresolved" count, row_uuid duplicates 0.
No names, phones, memo text or codes in the report: only counts, sums and the checksum per chunk (md5 of the ordered `row_uuid`s and the sum of `qty_base` and `gross_mtk`).

## 7. (f) Privacy
- **This tool loads no personal data at all (D-APSIS-MIG-06).** Because masters are matched by code and history is loaded by code, the loader never needs a name, phone, address, owner, e-mail or identity number: any column whose name matches `owner|phone|mobile|contact|nid|tin|trade_licen|email|address|name` is dropped at staging, with credential-looking columns (Q6), device ids included (`device`). The staging copy therefore holds none, and a test searches every migration schema with `pg_dump` for the synthetic names, phones, hashes and device ids and fails if one is found. Rep and outlet personal data of active masters reach the final database only through the master-data load and the credentials generator (D-OWNER-CREDENTIALS-01/-02), as already planned.
- The loader never prints a row; errors print the table, chunk and statement number only; the dry run and the reconciliation report hold counts and sums only (tests assert no synthetic code or name appears in either).
- Inactive outlets: archived by code only (`apsis_hist.outlet_stub`: code, route code, zone code, active flag).
- Credentials: dropped at staging (column-name rule, Q6, D-APSIS-MIG-05); the dry run lists dropped column names so the owner can confirm; a table that is a token/OTP store is not migrated (`--only` excludes it; the runbook has the explicit exclusion list). No vendor hash is ever reused.
- Test fixtures are invented (`SYN-*`, `0000000xxxx`).
- The job's managed identity reads the dump from a private container (`docs/requests/apsis-dump-landing.md`); no key is printed or stored; the dump is deleted from the landing container only after the owner signs the reconciliation (retention is the owner's decision).

## 8. Provisional migrations (for the lead)
In `tools/apsis-migration/provisional-migrations/`, numbered from V0260; forward-only; **not** in `db/migrations`, so they cannot run outside lane CI. At landing the lead moves them (renumbering to the next free versions) together with the data-dictionary regeneration.
- `V0260__apsis_migration_control.sql`: schemas `apsis_ctl`, `apsis_map`; tables `run`, `chunk`, `id_map`; function guards. No effect on live tables.
- `V0261__apsis_hist_tables.sql`: schema `apsis_hist` and its partitioned fact tables, `outlet_stub`, raw archives, ownership and grants (nothing to the sync roles).
- `V0262__apsis_dw_views.sql`: `dw.v_hist_*` views and `dw.v_sales_with_history`.
Marker on masters: no migration (existing `external_ref`, `source_created_at`, `data_origin`).

## 9. Decisions logged (DECISIONS.md, appended at the end)
D-APSIS-MIG-01 (blind tooling, schema-adaptive, Python stdlib + psql); D-APSIS-MIG-02 (archive schema, not live tables); D-APSIS-MIG-03 (UUID v5 computed by the loader); D-APSIS-MIG-04 (provisional migrations kept outside `db/migrations`); D-APSIS-MIG-05 (credentials never staged); D-APSIS-MIG-06 (masters matched not created; no personal data staged).

## 10. Status (push 2 and 3)
| Item | State |
|---|---|
| 1 Design (this file) | pushed |
| 2 Tooling `tools/apsis-migration/` | built: parser (mysqldump, .gz, TSV), staging loader, control table with per-chunk checkpoints and statement-level resume, masters match, optional coordinate fill with rollback log, fact transforms (sales, geo, attendance, section summary, outlet-route history), raw archives for the deferred programmes, reconciliation (numbers only), dry run (`profile`), `finalize`, provisional migrations V0260-V0262 |
| 3 Runbook | `docs/runbooks/apsis-history-migration.md`; dump landing: `docs/requests/apsis-dump-landing.md` |
| Tests | 20 synthetic-data tests, all pass **locally** (PostgreSQL 16.15, V0001..V0204 + provisional applied, about 3 minutes): re-run twice gives identical counts and checksums (also after wiping the control table and staging); kill mid-load and resume; duplicate vendor row (same key, and a table without a primary key) does not create a second record; money and unit conversions exact (half-up milli-taka, pack factor); Dhaka date boundary (17:59:59 / 18:00:00 UTC and month end); credentials, device ids, names and phones never in any migration schema; live masters byte-identical after a run; sync roles have no privilege on history; reconciliation numbers-only; a missing required column stops the step and invents nothing; memo number on two outlets fails the report |
| Lane CI | **not wired**: `ci.yml` does not run these tests; request in `docs/requests/apsis-ci-step.md` |

### NOT done (honest list)
- Column-level mapping for the master tables beyond the ones the transform needs: `master_locations`, `master_dep`, `master_clusters`, `master_ff_route_mapping`, `master_field_forces`, `master_price_list`, `master_price_group`, `master_category`, `master_product_classifications`, `master_section_configuration`, `master_sections`, `master_dep_location_mapping`, `master_outlet_classifications` are **staged and counted** but not mapped into `app.*`: by decision D-APSIS-MIG-06 live masters are owned by the October master-data load, and these need the schema-only profile first. Requests R1-R6 stay open.
- User mapping for history (`apsis_map.user_map`) matches `user_code` to our `employee_code`/username; it is not exercised on real codes.
- Performance and duration are estimates; nothing was measured at scale (the largest synthetic dump is a few rows). A load-test generator for a month of synthetic rows is a follow-up.
- mydumper layout (schema and data in separate files) is not supported; the loader needs the `CREATE TABLE` in the same file as the `INSERT`s (Q1).
- The tooling was not run in CI and not against a managed PostgreSQL; the Container Apps job image and its Dockerfile belong to infra-final.
- `V0260..V0262` are provisional and sit outside `db/migrations`; the data dictionary was not regenerated for them (it will be at landing).

### Open owner questions (assumption in section 2 holds until answered)
Q1 dump format and compression; Q2 character set; Q4 time zone of the stored timestamps; Q6 which tables hold credentials, tokens or OTPs (their names go in the exclusion list); Q8-Q9 zero-sale representation, vendor quantity unit (base or pack), float money; Q10-Q11 whether inactive rows are the superset and whether sales rows carry memo numbers; and the schema-only export (CREATE TABLE statements, no rows) that lets the mapping file be completed now.
