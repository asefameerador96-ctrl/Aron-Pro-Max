# AMO manual build (November 2026): build contract

Owner ask (2026-11-06 20:34 UTC): "The details of AMO app is here. complete the AMO App accordingly." Inputs: `docs/evidence/manuals/manual-amo-2026-10.md` (the owner's manual, sanitised), `docs/ui-reference/amo/` (77 screenshots, one note `NN-*.md` each, `README.md` index), `docs/evidence/manuals/delta-amo-2026-11.md` (gap register G-AMO-11-01…13). Baseline: INT 756cc24c (schema 0149, contract 1.10.0, dw view contract 1.4). The AMO app on INT: `android/app-amo` (47 Kotlin files), `android/feature-amo` (57), shared feature modules; Home = 8 hubs (feature-home `RoleHome.kt:141-149`), navigation enum `feature-amo AmoApp.kt:29`, host wiring `app-amo MainActivity.kt:185-218`; 293 EN + 293 BN strings; 67 `amo_*` goldens; every acceptance DEVICE-PENDING (`docs/status/android-amo.md:102`).

Binding above this document: CLAUDE.md constraints (offline-first, idempotent sync, server-side scope, battery, Dhaka business date, Bangla-first), owner rules 5 (units), 2 (deferrals, docs/27), 14a (the screenshot wins), 16 (portal levels), D-TARGET-MIN-01 and D-TSO-MAN-02 (the target slice is built), D-LEAD-STALL-02 pacing (one building lane at a time plus the integrator while the stop rule holds; this build is the owner's direct ask).

## 1. Decisions (D-AMO-MAN-01 and on; appended to DECISIONS.md by the lead)

- **D-AMO-MAN-01 (lead, from the owner's ask and 14a).** The AMO app is finished screen for screen from the 77 screenshots: look, labels, flows, dialogs. Where the manual text and a screenshot disagree, the screenshot wins; where a screenshot and a standing owner rule disagree, the rule wins and the difference is reported (s7.2). The notes next to each screenshot are the specification lanes build from; a lane that finds the note wrong against the image fixes the note in the same push.
- **D-AMO-MAN-02 (supersedes D-AMO-HOME-01).** Home is the vendor's 3-column tile grid in the manual's order: Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation, Live Dashboard, SR Stock, Outlet, Stock, Sales, Memo, Summary, Sales Submit, [Astha hidden], Report, Sales Journey, [Campaign Enrollment hidden], Settings; header card (name + ID, "<area>, <date>"), red count badge on Outlet (pending SR requests, as today), the four target cards below the grid (s4.3), scrolling. The 360 dp / 130 % font argument of D-AMO-HOME-01 is answered by scrolling and a 3-line label wrap; the hubs stay as internal navigation groups only where a tile opens a sub-menu that the vendor also has (Outlet menu, screenshot 46; Control Call menu, 18).
- **D-AMO-MAN-03 (look).** `AronLook.AmoPurple` in core-ui `Tokens.kt`, built from the screenshots (notes 08, 14, 17, 23, 24, 34, 39, 46, 65, 74): solid deep-purple top bar with white title and the round back arrow at the left, light page, white rounded cards with a thin grey border, gradient tile icons (pink to purple to blue), green filled buttons for Save/Check In/Update base, red for Check Out, blue (navy) for choices and Proceed, light-green outline for secondary (Sale History), blue key figures, red-amber-green bands; `AronLook.AmoSunlight` when Sunlight is on. Contrast 7:1 for key figures and button labels (TokenContrastTest). Every feature-amo and app-amo screen and every shared SR body hosted by the AMO app (Sale, Stock, Memo, Summary, Attendance, Sales Submit, outlet request forms, update host, login) takes this look through the host's theme, with no change to the SR app's goldens.
- **D-AMO-MAN-04 (units; rule 5 over the manual's "sticks").** The AMO Sale, Stock, Memo, Summary and SR Stock screens enter, show, print and total in the owner's field units (cigarettes in packets of 20/10 per SKU, lighters in boxes of 25, matches in dozens; bidi per SKU) exactly as the SR app does after D-UNITS-02; the screenshot's per-SKU "rate per 1,000 sticks" figure becomes the SR Sale screen's per-packet price line. Server aggregates stay in base units.
- **D-AMO-MAN-05 (deferred tiles, rule 2 / docs/27 item 6).** Astha (screenshots 67–70) and Campaign Enrollment (seen on screenshot 8 only) are hidden tiles; the Report screen's "route info" and "shop-wise" tables (Astha targets) are not built. Hooks stay (AmoLeaf.ASTHA, /v1/programmes/astha/targets, app.astha_target). No invented numbers anywhere.
- **D-AMO-MAN-06 (targets).** Team Performance (34–36) and the sales-till-now summary's Target columns (72) read the target slice already built for the TSO (route × item monthly targets, working-day calendar; `GET /v1/dashboards/targets` with `children=zone` and `product_level`; TARGET_ROLES includes AMO, `TargetAchievementApi.kt:273`). Till Date basis = calendar days as D-TSO-MAN rulings. Bands: red < 70 %, amber < 100 %, green ≥ 100 % unless a note shows other thresholds (the note of 34 decides).
- **D-AMO-MAN-07 (Team Location and Sales Journey, battery rule).** No continuous GPS on the SR phone. "Live location" = the SR's last known fix (last sync, visit or attendance fix) with its age; the AMO's map (33) shows the pin, name, phone and "n min ago" (grey after 120 min as today). Sales Journey (73–74) is computed on the server from the SRs' visit records of the business date: start = `opened_at`, end = the visit's close time, duration, distance from the previous call's **outlet base point** (haversine, metres), running total, gap to the next call; "Edited Sale" when the visit's memo was superseded by an edit. Online reads with a kept copy, like the other team screens.
- **D-AMO-MAN-08 (force sale on the Sales path).** The Sales tile (21–23) opens route → outlet → বিক্রয়; outside the geo-fence the app asks the force-sale reason (ইন্টারনেট সমস্যা / লোকেশন চেঞ্জ) and the sale proceeds as a force sale with `force_sale_reason` on the visit (geo_action `force_sale`, server re-check unchanged, risk signal unchanged). The Control Call path (17, 20) keeps Manual Override: photo + a `location` outlet-change request for web approval (anti-spoofing; the manual's "the photo also updates the outlet's location" is **not** followed: reported in s7.2). The manual-override daily cap stays `cfg.amo.manual_override_max_per_day`.
- **D-AMO-MAN-09 (phone numbers).** The AMO sees the phone numbers of the SRs of their own zone in clear on Team Location, SR Stock, Task assign and Sales Journey (the screenshots show them); `phone_masked` stays for every other reader and in dw/exports. Server-side: the supervisor bundle's team section carries `phone` for the caller's own team only.
- **D-AMO-MAN-10 (Perf Assessment and Joint Call content).** The Perf Assessment brand list is the admin brand list `cfg.rubric.distribution_brands` (today: the product tree level=brand); the 18 brands of screenshot 27 are the dev seed order. Joint Call = the `joint_call` rubric **version 2**: seven criteria in three sections (5-stage sales call: situation, concept, how it works, reaffirm benefits, close; relationship; service quality) with the manual's guidance lines in EN and BN, stars 1–5 (1 = Low, 5 = উচ্চ), no preselected star (replaces the "star 1 preselected" assumption), Save enabled when all seven are rated. Both end with the dialog "ডেটা সফলভাবে সংরক্ষিত হয়েছে" + ওকে (28, 32), which replaces the 0.9 s inline text.
- **D-AMO-MAN-11 (Live Dashboard scope).** `GET /v1/dashboards/login-submit` accepts the AMO role with reach = the token's own zone(s); the "select AMO" drop-down of screenshot 39 lists the caller only; the response gains `geo_valid_visits`, `photo_valid_visits`, `visits_total` and `sales_by_category` for the business date. A TSO/DMO keeps today's behaviour.
- **D-AMO-MAN-12 (Verify flows).** Cancel on a verification form rejects with a হ্যাঁ/না confirmation (D-AMO-VERIFY-02 stands; it matches screenshot 50's text); the new-outlet verification form re-captures GEO + photo (48) and may correct route and cluster (contract members added); every verification and own-request save ends with the success dialog of the screenshot (49, 51, 53, 56, 58, 61).
- **D-AMO-MAN-13 (Update Base).** Supersedes the step buttons of D-AMO-BASE-01: route → outlet → আপডেট বেস → confirm (আপডেট/বাতিল) → camera → the interactive map (core-map `InteractiveMap`) with the AMO's fix as the pin, draggable within `cfg.geo.update_base_max_distance_m` → নিশ্চিত করুন. The record stays a `location` outlet-change request; the server guards (mocked fix, distance, move, monthly cap) stay at web approval and the three keys are registered in `app.cfg_key` (closes `docs/requests/android-amo-update-base-server-guards.md`).
- **D-AMO-MAN-14 (control-call menu).** The vendor menu has three tiles (Sales, SR Perf. Assessment, Survey). Price compliance stays built but hidden behind `cfg.amo.price_compliance_tile` (default false); the Survey tile shows only when the bundle carries a survey (as today).
- **D-AMO-MAN-15 (Settings, attendance, logout).** Settings = language (বাংলা / en), PDA টু সাপোর্ট, লগ আউট as screenshot 75, plus the Sunlight and Printer diagnostics rows (standing rules; reported). Check-out earliest time stays `cfg.day.checkout_earliest_time` (default 17:00) and the attendance screen shows times on the 12-hour clock as screenshots 15–16. Logout asks "আপনি কি নিশ্চিত যে লগআউট করতে চান?" (77).
- **D-AMO-MAN-16 (release).** The AMO release copy is `aron_amo_app_<dd_mm_yyyy>_v<versionCode>.apk` next to the signed artefact (tools/ci/release-apks.py gains `--amo-copy-name`; test_gates pins it), as D-TSO-MAN-42 did for the TSO.
- **D-AMO-MAN-17 (proof).** Every screenshot has one row in s8 with a CI proof (golden, host test or unit test) or the word "device"; a lane may not close a row without naming the test. The lead's Opus checker reads every T1 delta (money, sync, geofence, scope, migrations, print). After the last android train: a signed AMO APK, `docs/status/amo-manual-conformance.md` (77 rows, written by an independent checker), and the owner's device walk list.

## 2. Data model (db lane; forward-only; V0150 and on; dw additive)

- **V0150 `amo_manual_rubric_sections_force_sale_reason`:** `app.rubric_criterion` gains `section text`, `guidance_en text`, `guidance_bn text` (nullable; sort already exists or is added as `sort_no int`); `app.visit` gains `force_sale_reason text CHECK (force_sale_reason IN ('internet_problem','location_change'))` nullable with `CHECK (force_sale_reason IS NULL OR geo_action = 'force_sale')`; `app.cfg_key` rows: `cfg.amo.call_target_month` (int ≥ 0, scopes global/role/territory/zone), `cfg.amo.control_call_target_month`, `cfg.amo.joint_call_target_month`, `cfg.amo.price_compliance_tile` (bool, false), and the three update-base guard keys (`cfg.geo.update_base_max_distance_m`, `cfg.geo.update_base_max_move_m`, `cfg.geo.update_base_monthly_cap`) with the defaults the backend already uses; `apply_db_role_grants()` unchanged (no new table).
- **V0151 `dw_view_contract_1_5`:** `dw.v_visit` (or the visit view that exists) gains `force_sale_reason`; `dw.v_rubric_criterion` (if present) gains section and guidance; view contract 1.4 → 1.5, dictionary regenerated, DwViewContractTest hash updated.
- **Seed (db/seed, dev only; final account imports through the admin content API):** `joint_call` rubric version 2 (seven criteria, three sections, guidance EN/BN verbatim from manual s9 and screenshot 31's note); `cfg.rubric.distribution_brands` = the 18 brands of screenshot 27 in that order where the dev catalogue has them; Gulshan–Banani test geography unchanged.
- No new tables. Sales Journey, previous sale data, call targets and the live dashboard additions are read models over existing tables (visit, memo, memo_line, attendance_event, outlet, route_monthly_target, calendar).

## 3. Contract 1.11.0 (additive; regenerated clients, fixtures, oasdiff green)

- `VisitRecord.force_sale_reason` (optional enum `internet_problem | location_change`; only with geo_action `force_sale`).
- `RubricCriterion` gains `section` (string, nullable), `guidance_en`, `guidance_bn` (nullable); RubricAdmin in/out mirror them.
- `GET /v1/team/journey?user_id&route_id&business_date` → `TeamJourney { user, route, business_date, calls: [ { visit_uuid, outlet { id, name, code, phone_masked, cluster }, status: new_sale | edited_sale | no_sale, opened_at, closed_at, duration_s, distance_from_prev_m (nullable for the first), total_distance_m, gap_to_next_s (nullable) } ], total_distance_m }`; roles AMO (own zone), TSO/DMO/WM (reach), national read roles; SR 403.
- `GET /v1/team/members` (or the supervisor bundle section): per SR one row per route with `route_name`, `route_days`, `phone` (D-AMO-MAN-09), `total_outlets`, `vacant` for routes with no SR.
- `GET /v1/outlets/{outlet_id}/sales-by-date?business_date` → lines `{ sku, qty_report, report_unit, qty_base, value_mtk }` plus totals; reach-checked (the outlet's route in the caller's reach); 90-day window.
- `GET /v1/supervisor/call-targets?month` → `{ today: { done, target }, month: { done, target }, control: { done, target }, joint: { done, target } }` from the cfg values (resolved for the caller's zone) and the caller's visits (kinds `amo_control_call`, `amo_joint_call`; "done" = closed visits that are not `abandoned`); `target` null when the key is unset (the card prints "—").
- `GET /v1/dashboards/login-submit`: AMO allowed (reach = own zone); response gains `geo_valid_visits`, `photo_valid_visits`, `visits_total`, `sales_by_category: [ { category, qty_base, base_unit, qty_report, report_unit } ]`.
- `GET /v1/team/stock`: members include SRs with no stock rows; each member carries `route_names`, `total_outlets`, `phone`.
- `OutletRequestVerificationRecord` gains optional `route_id`, `cluster_id`, `photo_uuid`, `fix` (the re-captured GEO + photo; the server stores them on the request's verification event, nothing moves until approval rules say so).
- Reports: `sales-summary` rows gain `target_qty_base` / `target_value_mtk` per route × category and per brand (null when no target); `std-memo` keeps its `from`/`to` parameters (the phone exposes the picker). Report access set of D-AMO-REPORT-02 unchanged.
- Regeneration order as the TSO contract s3.4 (openapi → shared types → android/web clients → contract fixtures → oasdiff).

## 4. Formulas (integer arithmetic; shared/rules; every layer calls them)

- 4.1 Percent = round half up of (num × 10000) / den in basis points, printed with two decimals where the screenshot shows two (40.41 %) and none where it shows none (74 %); den = 0 → null → "—".
- 4.2 Team Performance: achievement / target per category and per brand; Remaining = max(target − achievement, 0); Till Date target = monthly target × calendar days elapsed / days in month (half up), the same primitive as the TSO Target Status; bands per D-AMO-MAN-06.
- 4.3 Target cards: today's target = `cfg.amo.call_target_month` / working days of the month (calendar table; half up; min 1); done today = closed non-abandoned visits of the caller on the business date; month figures = the same over the month; control and joint = by visit kind.
- 4.4 Sales Journey: duration_s = closed_at − opened_at; distance_from_prev_m = haversine(prev outlet base point, this outlet base point) in metres, one decimal shown with Bangla digits where the screenshot uses them (note 74 decides digits); total = running sum; gap_to_next_s = next.opened_at − this.closed_at; durations printed as "৭ মিনিট ৪৬ সেকেন্ড" (Bangla) / "7 min 46 s" (English).
- 4.5 Strike rate = successful calls / target outlets; geo % = geo-validated / total calls; login % and Bikroy Joma % as today (per route).
- 4.6 Money stays integer milli-taka; the partial payment entry (25) must be ≥ 0 and < memo total; due = total − paid.

## 5. Lanes (one building lane at a time while the stop rule holds; Sonnet + the lane's Opus checker; the lead's own Opus checker on every T1 delta)

### F amo-foundation (lane/amo-foundation from INT; contract + db + backend; 2 pushes = 2 trains)
1. Train F1: contract 1.11.0 additions (s3), V0150, V0151 + dictionary, the rubric v2 seed and brand-list seed, backend: rubric fields through admin content API and the bundle, `force_sale_reason` ingest + dw, cfg keys, `/v1/supervisor/call-targets`, `/v1/team/stock` members. Tests: migration tests (db), contract fixtures, ingest property test for `force_sale_reason` (duplicate/out-of-order uploads keep one visit), AuthzAudit rows for the new operations.
2. Train F2: `/v1/team/journey` (+ members), `/v1/outlets/{id}/sales-by-date`, Live Dashboard AMO scope + counts + sales_by_category, sales-summary target columns. Tests: scope tests with AMO/TSO/SR tokens on every new or widened route (403 outside reach), journey computation tests (ordering, edited sale, vacant route, haversine against a fixed pair), report column tests.
Budget 450k; recycle on a HANDOVER in `docs/status/amo-foundation.md`.

### A1 amo-shell (lane/android-amo, after F1 lands): look + Home + system screens
`AronLook.AmoPurple`/`AmoSunlight` tokens and TokenContrastTest; Home grid + header + badge + target cards (API of F1; "—" when unset); Login, location/camera/audio/Bluetooth permission flows and texts (3, 4, 12); update flows and texts (5–7; core-system UpdateHost strings); Attendance parity (14–16: location card + refresh, messages, press-and-hold buttons, 12-hour times, "Already Checked-In/Out"); Stock screen parity (41) with the printer icon in the top bar (12, 13) and packshots kept; Settings (75–77) and PDA toast (76); release copy name (D-AMO-MAN-16). Goldens: Bangla light + 200 % font + Sunlight for Home, Attendance ×3 states, Stock, Settings, Login; side-by-side PNGs `docs/evidence/ui-parity/amo-<screen>.png` for 3, 8, 9, 14, 15, 16, 41, 75, 77.

### A2 amo-calls (lane/android-amo, after A1): Control Call, Sales, assessments, Memo, Summary, Sales Submit
Control Call route/retailer drop-downs with the LIVE icon and alphabet bar, range warning + Manual Override + Refresh, outlet list rows (17–20); Sales path with the force-sale reason dialog (21–23; F1's field), Sale screen parity (packshots, −/+, per-packet price, discount %, stock), previous sale data with a date picker (24, 26; F2's endpoint; offline = local memos), review + due checkbox + partial payment dialog (24–25); Perf Assessment header card, brand list, saved dialog (27–28); Joint Call route/outlet, in-range tile, v2 rubric with sections and guidance, stars, saved dialog (29–32); Memo find/reprint/edit/mark paid with the due badge and dialogs (42–44); Summary (45); Sales Submit table, buttons, due warning, success (65–66); Price compliance behind the flag. Goldens + side-by-sides for 17, 18, 20, 23, 24, 25, 27, 31, 42, 43, 45, 65, 66.

### A3 amo-team (lane/android-amo, after A2): team screens, journey, reports
Team Location on the interactive map (33); Team Performance tabs, zone + route cards, bands, Details brand table, category filter (34–36); Task list + FAB + assign form with date picker and SR phone (37–38); Live Dashboard (39) on F2's data; SR Stock list with route/phone/total outlets and lifted stock (40); Sales Journey SR cards and timeline (73–74) with Bangla durations; Reports menu, STD Memo Report with the date range and horizontal categories, sales-till-now with Target and route detail (71–72). Goldens + side-by-sides for 33, 34, 35, 36, 37, 38, 39, 40, 71, 72, 73, 74.

### A4 amo-outlet (lane/android-amo, after A3): Outlet menu and forms
Outlet menu with the two groups and badges (46); verification lists and forms with Sub-Channel/Geo Classification, GEO + photo re-capture, editable route/cluster, Cancel = reject with confirmation, success dialogs (47–53); own New shop route → cluster → form → photo → save (54–56), Permanent close with its confirmation text (57–58), Change information with alphabet bar and photo (59–61), Update Base on the map (62–64), Cluster operation as the SR app (46). Form uuid kept across a swipe-kill for every own-request form (closes parity-findings item 11). Goldens + side-by-sides for 46, 47, 50, 52, 55, 57, 59, 62, 63, 64.

### C amo-conformance (after A4 lands): independent checker, read-only
Walks all 77 rows of s8 against the code and tests on the final sha, writes `docs/status/amo-manual-conformance.md` (row, proof present?, test name, verdict); unproven rows listed, not hidden. Then the lead reports the signed APK and the device walk to the owner.

## 6. Order and dependencies
F1 → (A1 may start on F1's lane head for the target-card API shape) → F2 → A1 train → A2 → A3 → A4 → C. Each android train carries its goldens recorded on Linux, the full android unit suite and lint, the APK size gate (app-amo baseline may rise; the lane states the new size against docs/04), and one all-areas CI run on the exact sha before the lead's gate. web: no lane (cfg keys appear in the GUI config portal from the registry; the admin rubric editor already exists).

## 7. Owner report

### 7.1 Questions (each with the default the build uses)
1. **Call-target cards (screenshot 9).** Default: three admin config numbers per zone (monthly call target, control-call target, joint-call target); today's target = monthly ÷ working days; a card prints "—" until its number is set. Alternative: hide the four cards until a target engine exists.
2. **Live location heartbeat.** Default: last known fix with its age, no periodic GPS (battery and data rules). Alternative: one fix every 15 minutes while checked in (costs battery; needs your yes).
3. **Packshots in the AMO Stock table (41).** Default: keep the pack pictures (your 14a ask) in the SKU column. Alternative: the vendor's plain text table.
4. **Price compliance tile.** Default: hidden (the vendor menu has three tiles); one config switch shows it.
5. **SR phone numbers in clear on the AMO's team screens.** Default: yes for the AMO's own SRs (as the screenshots), masked everywhere else.

### 7.2 Conflicts and differences (decided; reported, not asked)
- Units on the phone follow your rule of 2026-10-22 (packets, boxes of 25, dozens); the vendor manual counts sticks.
- Astha (67–70) and the Campaign Enrollment tile (8) stay hidden (deferred programmes); their hooks stay.
- Manual Override does not move the outlet at once (the manual says it does); it raises a location request for web approval, because reps defeat geofences with fake GPS.
- Team Performance and the summary report's targets come from the target slice you approved for the TSO; brand detail appears when brand targets are uploaded.
- "Select AMO" on the Live Dashboard lists only yourself for an AMO login (server-side scope); a TSO sees their AMOs.
- Settings keeps Sunlight and Printer diagnostics; logout and all dialogs use the screenshot wording.
- Check-out opens at 17:00 (config); the clock on the attendance screen is 12-hour as in the screenshots.

## 8. Acceptance per screenshot
Proofs are unit, host or golden tests in CI unless "device" is named; device rows are walked by the owner on a pilot phone against dev with the signed AMO APK. Lane F = foundation (server/contract), A1–A4 = android lanes above, C = conformance. "Verdict" is filled by lane C.

| # | Screen (manual) | File | Lane | Proof | Verdict |
|---|---|---|---|---|---|
| 1 | Find the APK and install | `01-find_the_apk_and_install.jpg` | A1 | release copy name test (test_gates) + device | open |
| 2 | Installation complete | `02-installation_complete.jpg` | A1 | AppIconTest label "ARON AMO" + device | open |
| 3 | Login and location permission | `03-login_and_location_permission.jpg` | A1 | LoginScreen golden bn/en; location permission host test; device | open |
| 4 | Camera and audio permission | `04-camera_and_audio_permission.jpg` | A1 | camera/audio permission host test (feature-outlet capture); device | open |
| 5 | New update available | `05-new_update_available.jpg` | A1 | UpdateHost golden "NEW UPDATE" + Download App & Install; device | open |
| 6 | Download and install the update | `06-download_and_install_the_update.jpg` | A1 | UpdateHost progress golden + "do not close" text test; device | open |
| 7 | Update from inside the app | `07-update_from_inside_the_app.jpg` | A1 | forced update after login golden (আপডেট, অ্যাপ আপডেট হচ্ছে); device | open |
| 8 | Dashboard menu | `08-dashboard_menu.jpg` | A1 | amo_home golden bn/en/fs2/sunlight: 17 visible tiles in order, badge; AmoMenuTest order | open |
| 9 | Target cards (bottom of the Dashboard) | `09-target_cards_bottom_of_the_dashboard.jpg` | A1 | amo_home_targets golden (values and "—"); CallTargetsTest (formulas 4.3) | open |
| 10 | Turn on Bluetooth and find the printer | `10-turn_on_bluetooth_and_find_the_printer.jpg` | A1 | device (phone Bluetooth settings) | open |
| 11 | Pair with PIN 0000 | `11-pair_with_pin_0000.jpg` | A1 | device (pairing PIN) | open |
| 12 | Allow Bluetooth in the app | `12-allow_bluetooth_in_the_app.jpg` | A1 | Bluetooth permission host test on Stock; device | open |
| 13 | Printer connected | `13-printer_connected.jpg` | A1 | Stock top-bar printer icon states golden (red/green) + toast text test; device print | open |
| 14 | Attendance screen | `14-attendance_screen.jpg` | A1 | amo_attendance golden ×2 (before/after check-in) | open |
| 15 | Check in | `15-check_in.jpg` | A1 | check-in press-and-hold host test + golden; 12-hour time format test | open |
| 16 | Check out | `16-check_out.jpg` | A1 | check-out gate test (17:00 cfg) + "Already Checked-In/Out" golden | open |
| 17 | Control Call: select route and outlet | `17-control_call_select_route_and_outlet.jpg` | A2 | amo_control_call_pick golden (drop-downs, LIVE icon, alphabet bar, range warning + buttons) | open |
| 18 | Control Call: within range | `18-control_call_within_range.jpg` | A2 | amo_control_call_menu golden (3 tiles; Price compliance hidden by flag) + ControlCallTest | open |
| 19 | Outlet list | `19-outlet_list.jpg` | A2 | outlet list golden (name, code, phone, cluster) + alphabet filter test | open |
| 20 | Manual override and Control Call menu | `20-manual_override_and_control_call_menu.jpg` | A2 | ManualOverride golden + ControlCallTest (location request queued); device camera | open |
| 21 | Sales: select route | `21-sales_select_route.jpg` | A2 | Sales route pick golden | open |
| 22 | Sales: select outlet | `22-sales_select_outlet.jpg` | A2 | Sales outlet pick golden + বিক্রয় button | open |
| 23 | Sales screen and force-sale reason | `23-sales_screen_and_force_sale_reason.jpg` | A2 | Sale screen golden (packshots, −/+, price, discount, stock) + force-sale reason dialog host test (reason stored on the visit; F1 ingest test) | open |
| 24 | Enter the sale and review | `24-enter_the_sale_and_review.jpg` | A2 | Sale entry + Review goldens; due checkbox test | open |
| 25 | Partial payment (due) | `25-partial_payment_due.jpg` | A2 | partial payment dialog test (0 ≤ paid < total; due shown) + golden | open |
| 26 | View previous sale data | `26-view_previous_sale_data.jpg` | A2 | previous sale data host test (date picker; F2 endpoint fixture; offline local memos) + golden | open |
| 27 | SR Performance Assessment | `27-sr_performance_assessment.jpg` | A2 | Perf Assessment golden (18 brands, header card) + PerfAssessmentTest | open |
| 28 | Assessment saved | `28-assessment_saved.jpg` | A2 | saved dialog host test + golden | open |
| 29 | Select the route | `29-select_the_route.jpg` | A2 | Joint Call route pick golden | open |
| 30 | Select the outlet | `30-select_the_outlet.jpg` | A2 | range warning / SR Call Assessment tile golden | open |
| 31 | SR Call Assessment | `31-sr_call_assessment.jpg` | A2 | Joint Call v2 golden (3 sections, guidance, stars) + JointCallTest (no preselected star; Save enabled at 7/7) | open |
| 32 | Save the assessment | `32-save_the_assessment.jpg` | A2 | saved dialog test | open |
| 33 | Live location of SRs | `33-live_location_of_srs.jpg` | A3 | Team Location map golden (static frame) + TeamLocationsTest (age, grey after 120 min, phone in clear); device map | open |
| 34 | Monthly and Till Date target | `34-monthly_and_till_date_target.jpg` | A3 | Team Performance Monthly/Till Date goldens + TeamPerformanceTest (bands, till-date basis) | open |
| 35 | Details by brand | `35-details_by_brand.jpg` | A3 | Details brand table golden + Remaining/percent tests | open |
| 36 | One category only | `36-one_category_only.jpg` | A3 | category filter test + golden | open |
| 37 | Assigned Tasks | `37-assigned_tasks.jpg` | A3 | Tasks list golden (status chip, FAB) | open |
| 38 | Assign a task | `38-assign_a_task.jpg` | A3 | Task assign golden (date picker, SR name + phone) + TaskDelegationTest + FormDraftsTest | open |
| 39 | Live data by route | `39-live_data_by_route.jpg` | A3 | Live Dashboard golden + LiveDashboardTest on F2 fixture (AMO token 200: backend scope test) | open |
| 40 | SR list and lifted stock | `40-sr_list_and_lifted_stock.jpg` | A3 | SR Stock list/detail goldens (route, phone, total outlets; SRs without stock) + TeamStockTest | open |
| 41 | Stock issue | `41-stock_issue.jpg` | A1 | Stock golden (SKU/Issue/Stock columns, category totals, Save/Print, packshots) + shared StockHost tests | open |
| 42 | Find and reprint a memo | `42-find_and_reprint_a_memo.jpg` | A2 | Memo find golden (due badge) + detail golden (Print/Edit) | open |
| 43 | Collect a due | `43-collect_a_due.jpg` | A2 | mark-as-paid golden + AmoRefusalHostsTest | open |
| 44 | Confirm payment | `44-confirm_payment.jpg` | A2 | confirm + "marked as paid" dialog test | open |
| 45 | Today's sales summary | `45-today_s_sales_summary.jpg` | A2 | Summary golden (texts, Print, empty "কিছু পাওয়া যায়নি") | open |
| 46 | Outlet menu | `46-outlet_menu.jpg` | A4 | Outlet menu golden (two groups, badges) | open |
| 47 | New outlet request | `47-new_outlet_request.jpg` | A4 | Verify new-outlet list + form goldens (Sub-Channel, Geo Classification) | open |
| 48 | Photo and save | `48-photo_and_save.jpg` | A4 | GEO + photo re-capture host test (members in the record; F1 fixture); device camera | open |
| 49 | New outlet saved | `49-new_outlet_saved.jpg` | A4 | success dialog test | open |
| 50 | Close request | `50-close_request.jpg` | A4 | close request list/form goldens; Cancel = reject confirmation test | open |
| 51 | Close request updated | `51-close_request_updated.jpg` | A4 | "Data Updated Successfully" dialog test | open |
| 52 | Correction request | `52-correction_request.jpg` | A4 | correction form golden (editable fields) + payload test | open |
| 53 | Correction updated | `53-correction_updated.jpg` | A4 | dialog test | open |
| 54 | Select route and cluster | `54-select_route_and_cluster.jpg` | A4 | New shop route → cluster pickers golden | open |
| 55 | New shop form | `55-new_shop_form.jpg` | A4 | New shop form golden (fields in order) | open |
| 56 | Photo and save | `56-photo_and_save.jpg` | A4 | photo + save flow host test; success dialog | open |
| 57 | Permanent close | `57-permanent_close.jpg` | A4 | Permanent close form + confirmation text golden | open |
| 58 | Close saved | `58-close_saved.jpg` | A4 | dialog test | open |
| 59 | Change information form | `59-change_information_form.jpg` | A4 | Change information form golden (alphabet bar, fields) | open |
| 60 | Photo taken | `60-photo_taken.jpg` | A4 | photo captured dialog test | open |
| 61 | Save the change | `61-save_the_change.jpg` | A4 | confirmation + success dialog tests | open |
| 62 | Base Recalibration | `62-base_recalibration.jpg` | A4 | Update Base pick golden | open |
| 63 | Select the outlet | `63-select_the_outlet.jpg` | A4 | আপডেট বেস + confirm dialog golden | open |
| 64 | Confirm the location | `64-confirm_the_location.jpg` | A4 | map confirm golden (static frame) + UpdateBasePointTest (distance cap); device map | open |
| 65 | Sync data | `65-sync_data.jpg` | A2 | Sales Submit golden (9 rows, device/server, buttons) + AmoReconciliationTest | open |
| 66 | Due warning and success | `66-due_warning_and_success.jpg` | A2 | due warning + success dialog tests | open |
| 67 | Route info: STD target | `67-route_info_std_target.jpg` | — | deferred (Astha, docs/27): hidden tile; not built | open |
| 68 | Route info: Memo target | `68-route_info_memo_target.jpg` | — | deferred (Astha) | open |
| 69 | Shop-wise info | `69-shop_wise_info.jpg` | — | deferred (Astha) | open |
| 70 | Shop-wise memo target | `70-shop_wise_memo_target.jpg` | — | deferred (Astha) | open |
| 71 | STD Memo Report | `71-std_memo_report.jpg` | A3 | Reports menu + STD Memo Report goldens (date range, horizontal categories) + ReportsTest | open |
| 72 | Sales till now summary | `72-sales_till_now_summary.jpg` | A3 | sales-till-now golden (Target column) + route detail golden | open |
| 73 | SR list | `73-sr_list.jpg` | A3 | Journey SR cards golden (route days, phone, total outlets, Vacant) | open |
| 74 | Journey timeline | `74-journey_timeline.jpg` | A3 | Journey timeline golden (Bangla durations, distance, total, gaps; Edited Sale pink) + JourneyFormatTest | open |
| 75 | Settings | `75-settings.jpg` | A1 | Settings golden (language, PDA, Log out + Sunlight, Printer rows) | open |
| 76 | Send data file to support | `76-send_data_file_to_support.jpg` | A1 | PDA toast test (core-system upload) | open |
| 77 | Log Out | `77-log_out.jpg` | A1 | logout confirmation dialog test | open |

## 9. Lead rulings after the foundation train
(to be appended)
