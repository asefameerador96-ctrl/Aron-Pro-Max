# Real data on dev: AKTCL outlet list and the September 2026 geofence radii

Updated 2026-10-17 (data package, lane/ui-calm-glass worktree). Dev = the test account (`rg-aron-dev`), pilot size.

## What is on the dev database now

| Table | Before | Loaded | After | Note |
| --- | ---: | ---: | ---: | --- |
| `app.wing` | 1 | 10 | 11 | whole country; codes = the PDF's wing numbers 1..10 (Dhaka North = 6) |
| `app.division` | 1 | 50 | 51 | whole country; codes = the PDF's division subtotal codes (Mirpur = 66) |
| `app.territory` | 1 | 291 | 292 | whole country; code = the sheet's Territory Code = the PDF's "Automation Code" |
| `app.zone` | 1 | 1,050 | 1,051 | whole country; code = Zone Code |
| `app.route` | 3 | 11,315 | 11,318 | whole country; code = Route Code, visit days parsed from the name |
| `app.cluster` | 3 | 2,361 | 2,364 | Mirpur division only (needed by `outlet.cluster_id`) |
| `app.outlet` | 61 | 14,039 | 14,100 | **all outlets of division 66 Mirpur** (wing Dhaka North): 14,043 sheet rows, 4 rejected (no coordinates) |
| `app.outlet_placement_history` / `outlet_location_history` | 61 | 14,039 | 14,100 | one placement and one `migration` pin per loaded outlet |
| `app.cfg_version` | 2 | 1 | 3 | version 3 "Territory geofence radii: AKTCL research proposal, September 2026", author `aron.system`, C2 |
| `app.cfg_value` (`cfg.geo.radius_m`, territory scope) | 0 | 291 | 291 | 20 m: 51, 30 m: 108, 40 m: 71, 50 m: 34, 80 m: 5, 100 m: 22 |
| `app.route_assignment` (sr1001) | 3 | 2 | 5 | real routes 16685 and 16686 (below) next to MIR-SR-D / 3F / 2F |
| `app.sales_plan` | 39 | 39 | 78 | zone 5747 gets the Z-MIR plan (every active SKU with a non-zero outlet price) |

Mirpur division by territory (outlets, all with coordinates): 1208 Gabtoli 2,409 (2,173 confirmed pins), 1209 Mirpur
3,618 (3,589), 1210 Pallabi 2,881 (2,780), 1214 Uttara Model 2,886 (2,869), 1218 Dokhin Khan 2,245 (2,169).
459 outlets sit on a 6-decimal point shared by 3 or more outlets and are `location_confirmed = false` (DQ-48,
`cfg.outlet.placeholder_pin_min_shared` = 3); their first visit takes the correction path, not a geo-fail.

A second run of the whole load (hierarchy, outlets, seed 06, radii) changed nothing: identical counts, no row with
`version > 1`, still one radii version (job execution `caj-aron-dev-dblogins-ydmd7c9`).

**Pilot-size note.** The brief said "all outlets of the division that contains Mirpur" and "low thousands"; the
division has 14,043 rows, so the first instruction won (14,039 outlets, 3.3 MB of SQL, seconds to load; the phone
bundle only carries the SR's own routes, 61 + 61 outlets). `--territory-code 1209` would load 3,618 instead.

**PII note.** The dev database now holds real owner names, phone numbers and addresses of 14,039 Mirpur outlets
(the test account; nothing of it is in git). NID, TIN and trade licence were never emitted (the schema only has
encrypted columns since V0049 and the sheet carries the placeholder 123). When the test account is retired, drop the
database or archive the rows; the repo holds only scripts, this page and the PII-free radius CSV.

## Scripts (committed) and files (not committed)

| Path | What |
| --- | --- |
| `tools/data/retailers_to_sql.py` | xlsx -> idempotent SQL; cleaning rules, filters, `--stats`, `--nearest` (docstring) |
| `tools/data/test_retailers_to_sql.py` | 16 unit tests of the cleaning rules on synthetic values (`python -P tools/data/test_retailers_to_sql.py`) |
| `tools/data/geofence_radius_to_csv.py` | PDF (word positions, pymupdf) -> `docs/evidence/geofence-radius-2026-09.csv`, checked against every subtotal |
| `tools/data/geofence_radius_to_sql.py` | the CSV -> one audited config version with 291 territory values (`cfg.geo.radius_m`) |
| `tools/data/devload_job.py` | runs a SQL file on dev through the psql job (see "DB path") |
| `db/seed/06_dev_real_route.sql` | sr1001 primary on the two real routes, `route_planned`, zone 5747 sales plan; no-op where the routes are absent (CI, local) |
| `docs/evidence/geofence-radius-2026-09.csv` | 291 rows: wing, division, territory, territory_code, current_radius_m, proposed_radius_m, wing_code, division_code |
| `%USERPROFILE%\AronData\in\*` (laptop only) | the xlsx (95 MB) and the PDF; outputs under `AronData\out\` (SQL with PII, stats, nearest routes) |

Reproduce (laptop, about 7 minutes for the sheet):

```
python -P tools/data/geofence_radius_to_csv.py %USERPROFILE%\AronData\in\geofence-radius-2026-09.pdf docs/evidence/geofence-radius-2026-09.csv
python -P tools/data/retailers_to_sql.py %USERPROFILE%\AronData\in\retailers-2026-10-11.xlsx --out %USERPROFILE%\AronData\out\mirpur-load.sql ^
    --division Mirpur --codes-csv docs/evidence/geofence-radius-2026-09.csv --stats-out %USERPROFILE%\AronData\out\stats.md --nearest 23.8069,90.3687
python -P tools/data/geofence_radius_to_sql.py docs/evidence/geofence-radius-2026-09.csv --out %USERPROFILE%\AronData\out\radii.sql
python -P tools/data/devload_job.py %USERPROFILE%\AronData\out\mirpur-load.sql db/seed/06_dev_real_route.sql %USERPROFILE%\AronData\out\radii.sql
```

## DB path used (test account)

`psql-aron-dev-7i7g53` has **public network access disabled** (VNet-delegated subnet `snet-pg`, private DNS zone), so
no firewall rule can admit the laptop and none was created (path (a) impossible; no SKU, quota or network setting was
changed). Path (b) was used: the environment's psql job `caj-aron-dev-dblogins` (the dblogins / dev-seed job,
`infra/apps.bicep`), started with a per-execution template override by `tools/data/devload_job.py`:

1. the SQL is uploaded to a private blob container `devload` of `starondev7i7g53` with the owner's Entra login;
2. the execution's command fetches it with the job's **managed identity** `id-aron-dev-migrate` (token from the
   Container Apps identity endpoint, Bearer GET of the blob): no SAS, no key, no secret in the execution record;
   the database URL is the job's existing Key Vault reference `aron-db-direct-url`;
3. `psql -v ON_ERROR_STOP=1` runs the file (one transaction per file); the console log is read from Log Analytics
   (`log-aron-dev`, 1 to 3 minutes of ingestion); the blob is deleted after the run.

Temporary state, all removed at the end of the session: role assignments "Storage Blob Data Contributor" (owner,
account scope) and "Storage Blob Data Reader" (`id-aron-dev-migrate`, container scope), the `devload` container.
Executions (all Succeeded, 2026-10-16 17:43 to 18:08 UTC): `u1eojui` (read-only probe), `jq11gxk` (schema probe),
`bcigzjc` (hierarchy + outlets), `l48fzuo` (seed 06 + radii + verification), `ydmd7c9` (second run and the
nearest-outlet listing). Dev migrations at load time: V0083.

## Mapping decisions

- **PDF "Automation Code" = the sheet's "Territory Code"** (1001..1316). All 291 PDF codes exist in the sheet and
  all 291 sheet territories are in the PDF (0 missing either way).
- Wing and division **codes** come from the PDF (wing totals 1..10, division subtotals 21..76) by majority vote of
  each sheet division's territory codes, never by name; names are the sheet's (e.g. "Cumilla" wing, "Oxyzen",
  "Narsingdi"). Without `--codes-csv` the importer falls back to slug codes (`W-...`, `D-...`).
- The PDF prints a different division label on 8 territory rows than the subtotal they are counted in (Gabtali 1208
  under "Dhanmondi" inside subtotal 66 Mirpur; Gulshan, Badda, Tejgoan under "Mirpur" and Rampura, Basabo under
  "Dhanmondi" inside 76 Gulshan; Ramgonj 1063 under "Chatkhil" inside 37 Laxmipur; Sutrapur 1207 under
  "Jattrabari" inside 65 Dhanmondi). The CSV carries the subtotal's canonical name (that is what the national summary
  counts); the database hierarchy follows the sheet (Gabtoli 1208 is in division Mirpur there too).
- Route name pattern: `NameDaily` = daily (127); `Name(Sat, Mon, Wed)` = 3f mask 21; `Name(Sun, Tue, Thu)` = 3f
  mask 42; `Name(Sat, Tue)` = 2f; bit0 Sat .. bit6 Fri (V0002). 2 of 11,315 routes carry no pattern (mask 127, no
  `visit_kind`). `display_label` holds the printed part, `name` the base name.
- Sub channel -> channel: GT, RCC, DCC, MT, HoReCa themselves; Gold, Platinum, Diamond, Silver -> Astha with the
  V0027 `sub_channel` row; **Titanium** (15 Mirpur outlets, 1,206 nationally) is not in the code list: channel Astha,
  `sub_channel_id` null, counted. Loyalty tiers are deferred (docs/27), so no new master row was invented.
- Geo class: "Semi Urban" -> `SemiUrban`; blank stays null (4,943 of 14,039 in Mirpur).
- Phone: Bengali digits transliterated, `880` dropped, leading `0` restored; 1,225 Mirpur values do not fit
  `^01[3-9][0-9]{8}$` and are stored as null (counted, never altered).
- Outlet code: the stray `:` / `*` / danda prefix is stripped (P-07); duplicates keep the first row (0 in Mirpur).
- `location_basis = master`, `location_confirmed = true` except shared points (above); `visit_sequence` = row order
  within the route; no `house` rows (the sheet has none; `zone.house_id` stays null).
- Radii: **one** audited config version for the whole table (the db/seed/04 pattern: a version is a change set,
  291 versions would bump every phone's config version 291 times); each territory has its own `cfg_value` row,
  C2 (territory scope, docs/19 s3.2.1), bounds checked against `cfg.geo.radius_min_m` / `radius_max_m` (20 .. 2000)
  in the SQL itself. The current radius 100 is not written anywhere: it is the registry default of `cfg.geo.radius_m`
  and remains the fallback for every node without a territory value (verified: seeded outlet MIR-D-001 resolves 100).
  A re-run that finds a changed proposal closes the open row (`effective_to`, `superseded_in_version`) and inserts
  the new value under a new version; it never edits an old version.
- Resolution verified in SQL with the ConfigResolver precedence (outlet 100 > route 90 > zone 80 > geo_class 70 >
  territory 50 > division 40 > wing 30 > global 0): the Mirpur outlets below resolve **20 m, winning scope
  territory**. The bundle resolves `radius_m` per outlet with the same chain (`BundleService.outlets`) and the server
  re-check (`GeoRecheck`) again, so the phone and the server use the same number.

## Owner test: geo validation with real data (10 minutes, Mirpur 10)

Account `sr1001` on the enrolled Honor phone (dev, device day setup). Its routes and the day they are planned (the
planner takes the primary assignment and the weekday bit; Friday is not a selling day):

| Route | Name | Days | Zone | Territory | Outlets | Where |
| --- | --- | --- | --- | --- | ---: | --- |
| `16685` | Mirpur-10 | Sat, Mon, Wed | 5747 Mirpur 11 | 1209 Mirpur, radius **20 m** | 61 | centroid 23.81179 N 90.37031 E, about 400 x 470 m |
| `16686` | Jute Putty | Sun, Tue, Thu | 5747 Mirpur 11 | 1209 Mirpur, radius **20 m** | 61 | centroid 23.80910 N 90.37150 E, about 350 x 730 m |
| `MIR-SR-D` / `3F` / `2F` | seeded synthetic grid | daily / Sun Tue Thu / Mon Thu | Z-MIR | T-DHK-N, radius 100 m (fallback) | 20 each | 23.8069 N 90.3687 E |

Three outlets per real route nearest to Mirpur 10 (23.8069 N, 90.3687 E; shop name and code only, resolved radius 20 m):

| Day group | Outlet code | Shop | Visit no. | Distance from Mirpur 10 |
| --- | --- | --- | ---: | ---: |
| Sat, Mon, Wed (16685) | `4063569` | Saying general store | 42 | 442 m |
| Sat, Mon, Wed (16685) | `8972444` | bi bi enterprise | 58 | 444 m |
| Sat, Mon, Wed (16685) | `2045582` | Raza store | 1 | 446 m |
| Sun, Tue, Thu (16686) | `1766045` | Fatema Enterprise | 5 | 168 m |
| Sun, Tue, Thu (16686) | `2027455` | mama biegna tea store | 12 | 173 m |
| Sun, Tue, Thu (16686) | `7879172` | Mamun tea store | 56 | 174 m |

Dev gates in force (registry defaults, no global override): `cfg.geo.max_accuracy_m` 100, `accuracy_tolerant` false,
`fix_timeout_s` 15, `refresh_max` 3, `mock_policy` **block_sale**, `no_location_policy` force_sale_required.

Steps:

1. Log in as `sr1001` on the day's route; the bundle must list the real route (`16685` or `16686`) with its 61
   outlets and the three above in the picker (search by code). If only MIR-SR-* appear, the bundle did not carry the
   assignment: report it with the business date.
2. **In range.** Stand at the shop's door, open the visit. Expected: "You are N m from the shop (allowed 20 m)" with N
   under 20, the sale screen opens, `verdict in_range`, `geo_action sale_allowed`. GPS accuracy must be 100 m or
   better; a worse fix shows "Your location is not accurate enough. Refresh and try again." (up to 3 refreshes).
3. **Out of range.** Open the next outlet from 50 m or more away. Expected: "You are outside the shop's allowed
   distance. Move closer, refresh your location, or use Force Sale." with the distance line "(allowed 20 m)"; Force
   Sale asks for a reason (Internet problem / The shop location has changed) and a shop photo, then the sale continues
   with `verdict out_of_range`, `geo_action force_sale`, `force_reason_code` set, photo attached; the memo is never
   counted as a clean geo sale.
4. **Mock location.** Developer options -> select a mock-location app (any fake-GPS app), set a point at the shop,
   open the visit. Expected under `block_sale`: "Mock location detected. This visit is closed.", `verdict mocked`,
   `geo_action blocked`, no sale; the visit row is still recorded with `fix_is_mock = true`. Turn mocking off and
   confirm the next visit opens normally.
5. Sync (Wi-Fi or data), then read the verdicts below. **The on-device check and the server re-check must agree:**
   `verdict = server_verdict`, `radius_m_used = server_radius_m = 20`, and `distance_m` within about 1 m of
   `server_distance_m` (same haversine on the same pin and fix). Any row where they differ is a finding for
   backend-core / android-sr-a, with the visit's `client_uuid`.

Read the verdicts afterwards (save as a file, run `python -P tools/data/devload_job.py verdicts.sql`; it prints the
tables; the owner never needs a database password):

```sql
-- every visit of sr1001 in the last 7 days: the phone's verdict, the server's re-check, the force-sale flag
SELECT v.business_date, o.code AS outlet_code, v.verdict, v.server_verdict,
       round(v.distance_m::numeric, 1) AS device_m, round(v.server_distance_m::numeric, 1) AS server_m,
       v.radius_m_used, v.server_radius_m, v.fix_accuracy_m, v.fix_is_mock, v.location_basis,
       v.geo_action, v.geo_action = 'force_sale' AS force_sale, v.force_reason_code, v.force_photo_uuid IS NOT NULL AS force_photo,
       v.server_checked_at, v.client_uuid
  FROM app.visit v JOIN app.outlet o ON o.id = v.outlet_id JOIN app.app_user u ON u.id = v.user_id
 WHERE u.username = 'sr1001' AND v.business_date >= current_date - 7
 ORDER BY v.captured_at DESC;
-- rows where the phone and the server disagree (must be empty once server_checked_at is set)
SELECT v.client_uuid, o.code, v.verdict, v.server_verdict, v.radius_m_used, v.server_radius_m, v.distance_m, v.server_distance_m
  FROM app.visit v JOIN app.outlet o ON o.id = v.outlet_id JOIN app.app_user u ON u.id = v.user_id
 WHERE u.username = 'sr1001' AND v.server_checked_at IS NOT NULL
   AND (v.verdict IS DISTINCT FROM v.server_verdict OR v.radius_m_used IS DISTINCT FROM v.server_radius_m
        OR abs(coalesce(v.distance_m, 0) - coalesce(v.server_distance_m, 0)) > 1);
```

`server_checked_at` null means the re-check has not run for that row yet (it is an ingest enrichment); re-run the
query after the next sync. The seeded MIR-SR-* outlets keep radius 100 m (fallback), so a visit there from the same
spot shows the difference between the proposal and today's radius.

## Open points

- Titanium Astha tier has no master row (deferred programme); 15 Mirpur outlets carry `sub_channel_id` null.
- The two patternless route names and the 1,225 invalid phone values are counted, not corrected; the admin pages own
  corrections.
- Sub-channel and geo-class master lists are system reference data (V0027); the importer only maps onto them.
- The dev seed image copies `db/seed/0*.sql`, so `06_dev_real_route.sql` ships with the next deploy's seed run: a
  no-op until the real routes exist, the assignment itself once they do (they do on dev now).

## Sheet statistics (whole file, counts only)

Generated by `tools/data/retailers_to_sql.py` on 2026-10-16 (counts only).

- rows read: 731939
- subset outlets emitted: 14039
- hierarchy emitted: 10 wings, 50 divisions, 291 territories, 1050 zones, 11315 routes, 2361 clusters (subset only)

| Wing | Rows | With coordinates |
| --- | ---: | ---: |
| Sylhet | 98,766 | 98,541 |
| Gaibandha | 89,936 | 89,935 |
| South Bengal | 89,496 | 89,489 |
| Rangpur | 79,341 | 79,340 |
| Dhaka South | 78,163 | 78,104 |
| Dhaka North | 77,516 | 77,420 |
| Noakhali | 61,642 | 61,620 |
| Chittagong South | 55,640 | 55,593 |
| Cumilla | 55,270 | 55,159 |
| Chittagong North | 46,169 | 45,638 |

| Wing | Division | Rows |
| --- | --- | ---: |
| Chittagong North | Agrabad | 7,439 |
| Chittagong North | Hathazari | 6,718 |
| Chittagong North | Khagrachari | 7,293 |
| Chittagong North | Oxyzen | 7,813 |
| Chittagong North | Pahartali | 10,864 |
| Chittagong North | Ramgar | 6,042 |
| Chittagong South | Bandorban | 8,266 |
| Chittagong South | Chokoria | 6,732 |
| Chittagong South | Cox`sbazar | 12,770 |
| Chittagong South | Moheshkhali | 7,346 |
| Chittagong South | Nasirabad | 9,077 |
| Chittagong South | Potiya | 11,449 |
| Cumilla | Chandpur | 13,832 |
| Cumilla | Comilla | 15,789 |
| Cumilla | Hajigonj | 8,338 |
| Cumilla | Moynamoti | 17,311 |
| Dhaka North | Gazipur | 22,034 |
| Dhaka North | Gulshan | 12,322 |
| Dhaka North | Manikgonj | 11,049 |
| Dhaka North | Mirpur | 14,043 |
| Dhaka North | Savar | 18,068 |
| Dhaka South | Bhulta | 15,511 |
| Dhaka South | Dhanmondi | 11,054 |
| Dhaka South | Jattrabari | 18,612 |
| Dhaka South | Narayanganj | 16,334 |
| Dhaka South | Narsingdi | 16,652 |
| Gaibandha | Bogra | 22,051 |
| Gaibandha | Gaibandha | 7,522 |
| Gaibandha | Kurigram | 12,599 |
| Gaibandha | Lalmonirhat | 12,207 |
| Gaibandha | Polashbari | 10,500 |
| Gaibandha | Rajshahi | 25,057 |
| Noakhali | Chowmuhani | 16,255 |
| Noakhali | Dagonbhuiya | 13,645 |
| Noakhali | Feni | 12,454 |
| Noakhali | Laxmipur | 11,746 |
| Noakhali | Subarnochar | 7,542 |
| Rangpur | Dinajpur | 16,402 |
| Rangpur | Lalbag | 17,321 |
| Rangpur | Nilphamari | 13,430 |
| Rangpur | Rangpur | 12,753 |
| Rangpur | Thakurgoah | 19,435 |
| South Bengal | Barisal | 20,213 |
| South Bengal | Jhenaidah | 24,136 |
| South Bengal | Khulna | 27,536 |
| South Bengal | Sariatpur | 17,611 |
| Sylhet | B.Baria | 23,417 |
| Sylhet | Jamalpur | 20,354 |
| Sylhet | Mymensingh | 27,968 |
| Sylhet | Sylhet | 27,027 |

Quality counters:

- routes whose name carries no visit pattern (mask 127, no visit_kind): 2
- rows without coordinates (whole sheet): 1,100
- subset outlets on a shared point (location_confirmed false): 459
- subset outlets with an unmapped sub channel (channel kept, sub_channel_id null): 15
- subset outlets without a geo class: 4,943
- subset outlets without a valid 11-digit mobile: 1,225
- subset rejected: no coordinates or outside Bangladesh: 4
- subset rows: 14,043
- territories in the radius CSV absent from the sheet: 0
