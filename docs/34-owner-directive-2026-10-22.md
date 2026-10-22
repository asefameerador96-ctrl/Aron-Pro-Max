# 34 — Owner directive of 2026-10-22: portal user levels, national dashboard, reports, app fixes, MDM (binding)

The owner's message of 2026-10-22 ~08:44 UTC (with the dashboard screenshot and `Dashboard_Brief.docx`)
is the newest governing instruction. It adds to `CLAUDE.md` rules 14a (look parity) and 2 (deferred
programmes) and supersedes rule 5 for the **field counting units**. Rulings are logged in `DECISIONS.md`
under the ids named here. Lanes read this page first, then their lane brief.

## 1. Portal user levels and scope (D-ROLES-02)

The owner's seven levels, mapped onto the role codes of `app.role` (V0001):

| # | Owner's level | Role code | Geography scope | Product scope | Write |
|---|---|---|---|---|---|
| 1 | Super Admin | `SUPERADMIN` | all | all | everything (read and write) |
| 2 | Admins | `ADMIN` + **module grants** | all | all | per module: `read` or `write` (masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, …) |
| 3 | National User | `NATIONAL` (new code; `TOP` is retired, existing TOP users migrate) | all | all | read: dashboard, reports, exports; write: nothing but own profile and saved views |
| 4 | Brand User | `BRAND` (new) + `user_brand_scope` | all | **own brands only** | read only |
| 5 | Wing Manager | `WM` | own wing | all | read + the wing-level actions the WM has today (docs/14) |
| 6 | DMO | `DMO` | own division | all | read + DMO actions |
| 7 | TSO | `TSO` | own territory | all | read + TSO actions; also the TSO app |
| – | SR, AMO | `SR`, `AMO` | – | – | **no portal login** (the web login refuses with a clear message); apps only |

Rules:

- Scope is derived on the server and carried in the token (`CLAUDE.md` constraint 4): claims `geo` (level + ids),
  `brands` (BRAND only), `modules` (ADMIN only: `{module: "r"|"w"}`). The client never sends scope.
- The sidebar menu is built from the role and the module grants; a page not granted is not listed and its
  API returns 403.
- Brand users see the product-based cards and reports filtered to their brands; cards without a brand
  dimension (CPR, Login/Submit, Final Submit, Geo Fencing, FF Geo) are hidden for them (default; the owner can
  change it).
- `ANALYST` and `SUPPORT` stay as internal service roles (docs/21); they are not portal levels.
- Admin pages: Super Admin manages users, roles, module grants and brand scopes in the admin portal
  (lane web-admin); every change is audited (existing audit chain).

## 2. The national dashboard (D-DASH-02)

The binding spec is the owner's screenshot `docs/ui-reference/web/dashboard-national.webp` and the brief
`docs/ui-reference/web/dashboard-brief.md` (with figures 1–6 and the note `dashboard.md`, ids UI-WEB-01..14).
Every card, filter, pop-up, page and formula in the brief is built as written, in this order: filters and the
twelve cards, then the "i" pop-ups and the gear, then FF Geo (pins, colours, Filter by Color, pop-up), then
the Route map page and the Sale Position page. Role views differ only by scope (s1).

Rulings the brief forces:

- **D-TARGET-MIN-01 (minimum target slice).** The rings and the "i" pop-ups need route × SKU monthly
  targets and a working-day calendar (Fridays and holidays excluded). Built now: the `route_monthly_target`
  upload (admin page + Excel import), the `calendar` table with holidays (admin page), the daily-target
  derivation and the Target vs Achievement card of the route map. Everything else in the Target programme
  (target reports, approvals, SKU target screens, achievement programmes) stays deferred per docs/27.
- **Astha channel vs Astha programme.** GT, DCC, Astha, RCC, MT, HoReCa are **outlet channels** in the outlet
  master (`outlet.channel` NOT NULL). The channel cards are built. The Astha loyalty programme (gifts, points,
  Astha Gift Panel menu) stays deferred.
- **House level.** `house.is_active`; duplicates de-duplicated; the filter lists each active house once.
- **Route kinds.** `route.kind`: `normal`, `manual` (no-internet), `dummy_br`, `dummy_institutional`; CPR and
  Login counts use `normal` only.
- **Live summaries.** The dashboard reads summary tables kept current by the domain-event outbox (memo,
  call, check-in/download, Bikroy Joma, final submit) within a minute, and shows "last updated hh:mm".
  Past dates are final figures; today is "till time".
- **Units on the dashboard stay as the brief says:** cigarettes and bidi in sticks, lighters in pieces,
  matches in dozens (base units); the field apps count in packets, boxes and dozens (s4.2) and the base
  quantity is derived on the device at entry time.
- **Numbers.** Lakh / Crore formatting with the same rounding as the original (two decimals), Bengali digits in
  Bangla mode; colours per card as in the screenshot (blue rings for sales, green for CPR and geo-fencing).
- Menu entries that are deferred programmes (Target beyond the minimum slice, Astha Gift Panel, Superstar
  Program) are not shown (docs/27 rule 6). Performance Leaderboard and Daily Tracking Dashboard are in scope.

## 3. Reports (D-REPORTS-01)

The owner's report samples (September 1–30, Excel) and the explanation documents with the logic are on the
owner's laptop at `D:\Aron Web Portal Reports`, which no cloud session can read. The lead has asked the owner
to upload them to the lead session (as the brief was uploaded). The lead then files, per report, a
**sanitised spec** in `docs/reports/<nn>-<report>.md`: the logic text, the column list with types and
units, the filters, the sample's row shape with three synthetic rows, and the arithmetic checks derived from
the sample. The raw September workbooks are never committed (the repository is public, CLAUDE.md rule 13)
and never pasted into chat; totals quoted in a spec are rounded aggregates. Lane backend-reports builds each
report from its spec with a fixture test that reproduces the spec's checks; web-dashboard builds the page and
the Excel export with the same columns and order as the sample. Until the uploads arrive the report lanes
work on the dashboard (s2), whose eight related briefs (01, 02, 03, 06, 07, 08, 11, 12) overlap the reports.

## 4. Android apps

### 4.1 Printer: Thermal Crest MP-58N (D-PRINT-02)

The field printer is the **Thermal Crest 58 mm mobile thermal printer, model MP-58N** (Bluetooth Classic
SPP, ESC/POS, 384 dots per line). The owner reports "the printer does not work with the android app" on
build 1258. `android/core-printing` already targets MP-58N and RPP02N-class printers with raster
(`GS v 0`) bands over RFCOMM; the failure is somewhere between pairing, permission, connect and the raster
command. Lane android-print: (1) a **Printer diagnostics** screen under Settings → Printer: Bluetooth state,
runtime permission state (`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` on Android 12+, location on older), the
bonded-device list with name and address (no name filter that could hide "MP-58N" or "MTP-II"-style names),
connect (secure, then insecure fallback) with the error text, and a **test page** in three modes: raster
`GS v 0`, bit-image `ESC *` (24-dot) and plain-ASCII self-test, each logged; (2) a diagnostics export (text,
no secrets) the owner can send back; (3) whatever the first two reveal, fixed, with the memo renderer's
golden tests kept green; (4) the SR print path never blocks a sale (unchanged). The owner tests on their
phone with the real printer; the lead relays the export.

### 4.2 Counting units (D-UNITS-02, supersedes CLAUDE.md rule 5 for the field)

Field counting units: **cigarettes in packets (20s and 10s, per SKU), matches in dozens, lighters as a box of
25**. Bidi: packets too, with the stick count per SKU from the product master (the owner is asked to confirm,
s6). The schema already carries this (`sku.base_unit`, `sku.base_per_pack`, `entry_unit_default`,
`unit_entered`, `pack_factor`, `qty_base` on memo, stock and indent lines; `cfg.sale.qty_entry_unit`):

- Storage and every server-side aggregate stay in **base units** (sticks, pieces, dozens): the dashboard,
  the money maths and the memo oracle do not change.
- The product master sets `base_per_pack` per SKU: 20 or 10 for cigarettes, the SKU's stick count for bidi,
  25 for lighters ("box"), 1 for matches (dozen is the base unit). `cfg.sale.qty_entry_unit` default becomes
  `{"cigarette":"pack","bidi":"pack","lighter":"pack","match":"dozen"}`; the label per category is
  প্যাকেট / বক্স / ডজন (packet / box / dozen).
- The apps **enter, show, total, print and sync** quantities in the entry unit; every quantity on screen and on
  the memo carries its unit label; loose base units are not entered (`cfg.sale.allow_loose_base_units`,
  default false, so the quantity fields take whole packets, boxes, dozens). `qty_base = qty_entered ×
  pack_factor` is computed on the device at entry and re-checked on the server.
- Prices are shown per entry unit (packet price); the stored per-base price × pack_factor must equal the
  shown packet price exactly in milli-taka (checker test), so line amount = qty_entered × packet price.
- Stock (opening, indent, closing), sales summary, Bikroy Joma, dues memos and printed memos all use the
  entry unit; the TSO and AMO apps show the same unit where they show quantities.
- Reports and exports use the unit the report spec names (s3); until a spec says otherwise, base units.

### 4.3 Pack pictures (why not all packshots show)

Build 1258 bundles only the three development crops (MaxR, MaxB, MaxWB) because the 42 real pack pictures
cannot be committed to a public repository (D-PACK-04). The real pictures reach the phones through the
server asset store (D-PACK-03): the loader `tools/data/load_packshots.py` (runbook
`docs/runbooks/load-packshots.md`) uploads them once with an admin login, after which every SKU's picture
arrives in the bundle. The owner's choices: (a) run the loader on the laptop with their admin login
(recommended; 10 minutes), (b) upload per SKU in the admin portal (Products → SKU → picture), or (c) say
"repository private now" so the pictures can be committed. Nothing in the app needs to change.

### 4.4 Test geography: Gulshan and Banani, and the data-flow check (D-SEED-02)

The seed moves from Mirpur to **Gulshan and Banani** (the owner tests there): house `H-GUL` under
`T-DHK-N`, zones `Z-GUL` (Gulshan 1 and 2) and `Z-BAN` (Banani), 60 outlets on real street positions around
Gulshan 2 circle (23.7925 N, 90.4078 E), Gulshan 1 (23.7808 N, 90.4165 E) and Banani 11 (23.7937 N,
90.4043 E), with real-looking Bangla outlet names and channels; `sr1001`'s routes become Gulshan/Banani
routes with a Friday-aware schedule; `06_dev_real_route.sql` is replaced. The dev database is re-seeded
through the normal CI deploy. Then lane qa runs the **data-flow trace**: seed → bundle → SR app (attendance,
stock, sale in packets, memo, submit) → sync → summary tables → every dashboard card and the route map → the
reports that exist, and writes `docs/evidence/data-flow-trace-<date>.md` with the numbers matching at each
hop (device count = server count = dashboard count).

### 4.5 Sale screen: the map and the distance inline (D-MAP-02)

The owner: "in the 'sale' option it is supposed to show the map; why do I need to click 'open in maps' to
see my distance from the outlet?" Ruling: the Sale → outlet confirm step shows, without a tap, the **distance
to the outlet and the radius verdict** (always, not only when out of range) and an **inline map** with the
outlet pin, its radius circle and the SR's position. The map is the Maps SDK in **lite mode** (a static
bitmap: no gestures, no continuous rendering, negligible battery) loaded only on that step, with tile cache;
offline or without tiles the panel shows the distance and a bearing arrow instead, so the sale never waits
for the map (offline-first, docs/17 s10.5 budgets hold: ≤ 1 MB per map session). "Open in Maps" stays as a
secondary link for navigation. This supersedes N-041's "the map is its own screen" for the SR app.

## 5. MDM in the portal (D-MDM-01)

MDM is a **left-menu section "MDM"** of the web portal for Super Admin and Admins with the `mdm` module
grant (not a separate portal; the section can be split out later because it has its own API prefix
`/admin/mdm/*`). It must do what the current third-party console does. The owner's reference is at
`D:\Aron MDM` on the laptop (unreachable from the cloud); the lead has asked for screenshots and the feature
list. Until they arrive the lane builds the data layer and plain pages: device list (enrolment state, owner
mode, user, app flavour and version, last seen, battery, network, policy, compliance flags: rooted, mock
location, outdated app, developer options), device detail with activity log, policies (allowed-app list, app
blocking schedules by weekday and time, kiosk/lock-task, location sampling policy), actions (lock, unlock,
message, locate, reboot, factory reset with two-person confirmation and audit), enrolment (QR / token for the
device-owner DPC already in `android/dpc`), compliance alerts. Everything rides the existing device,
enrolment and dpc tables and the dpc module's command channel (push via FCM, pull on sync).

## 6. Questions put to the owner on 2026-10-22 (defaults until answered)

| # | Question | Default applied until answered |
|---|---|---|
| Q1 | Distance Covered on the route map: metres or kilometres, along the GPS track or between calls? | metres along the SR's recorded positions in time order (the path the map draws); shown as "1,196.97 m", switching to km from 1,000 m with one decimal |
| Q2 | Bidi: packets too (how many sticks per packet per SKU), or sticks? | packets, stick count per SKU from the product master |
| Q3 | Lighters and matches on dashboards and reports: pieces and dozens as the brief says, or boxes? | pieces and dozens (the brief) |
| Q4 | Brand users: hide the cards without a brand dimension, or show them unfiltered? | hidden |
| Q5 | Reports and MDM references: upload the explanation docs, the Excel samples and the MDM screenshots to the lead session | lanes work on the dashboard and the MDM data layer meanwhile |

## 7. Wave 4 lanes (started 2026-10-22; registry in `docs/status/lane-sessions.md`)

| Lane | Branch | Model | First jobs (from this page) |
|---|---|---|---|
| backend-authz | lane/backend-authz | Opus | s1: role codes, module grants, brand scope, token claims, portal login refusal for SR/AMO, 403 per module; admin API for grants |
| db | lane/db | Opus | migrations for s1, s2 (house active flag, route kind, outlet channel NOT NULL, route_monthly_target, calendar, summary tables, call distance/within_fence, final submit log), s4.2 (pack sizes), then the Gulshan/Banani seed (s4.4) |
| backend-reports | lane/backend-reports | Sonnet, Opus checker | s2: contract first (one OpenAPI slice per card, pushed within the first hour), summary tables fed by the outbox, the twelve card queries, FF Geo, route map, sale position; then reports from `docs/reports/` as they arrive |
| web-dashboard | lane/web-dashboard | Sonnet | s2: filter panel with House, twelve cards, "i" pop-ups, gear, FF Geo map with clusters and Filter by Color, Route map page, Sale Position page, last-updated; role views |
| web-admin | lane/web-admin | Sonnet | s1 admin pages (users, roles, module grants, brand scope), s2 (target upload, calendar/holidays, house active flag, outlet channel mandatory), s4.2 (SKU pack size fields) |
| android-print | lane/android-print | Opus | s4.1 |
| android-units | lane/android-units | Sonnet, Opus checker | s4.2 across SR Stock, Sale, Memo, Summary, Submit, dues, print; AMO/TSO quantity displays |
| android-map | lane/android-map | Sonnet | s4.5 |
| mdm | lane/mdm | Sonnet | s5: API, web section, dpc command channel |
| qa | lane/qa | Sonnet | s4.4 data-flow trace once db and the backends land on INT |

Lanes start from INT plus this page (branch `lane/lead-directive-1022`, promoted docs-only). Each lane keeps
its status file in `docs/status/<lane>.md`, builds with the checker tier of docs/29, and pushes only its lane
branch; the integrator promotes heads on the lead's "checked <sha>".

## 8. What does not change

Offline-first, idempotent sync by client UUID, money in integer milli-taka, Dhaka business date, server-side
scope, Bangla-first, the perf and battery budgets, the pilot sizing of the test account, the public
repository rule, no secrets in git, the look parity rule 14a and the deferred programmes of docs/27 (except
the minimum target slice and the outlet channel named above).
