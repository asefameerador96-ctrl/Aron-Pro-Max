# Delta: TSO (Territory Sales Officer) App User Manual vs the Aron spec and plan

Date: 2026-10-04. Output of the "find everything the spec does not cover" pass for the TSO manual.

**Inputs read in full:** `scratchpad/manuals/manual-tso.md` (737 lines: 24 screens, 17 flows, 50 rules, 24 message entries, 29 entities, 30 unclear items); `docs/08-tso-app.md`, `docs/03-data-model.md`, `docs/10-kpis-and-programs.md`, `docs/13-open-questions.md`, `db/schema.sql`; plan drafts `plan/lens-features.md` (F-TSO-001..020, F-SYS, G-feat-01..67) and `plan/lens-data.md` (T01..T08, M-33..M-36, D-01..D-09). Also read for context: `docs/01, 02, 04, 09, 11, 12, 22`, `plan/seed-findings.md`, the `cfg.tso.*`, `cfg.leave.*`, `cfg.feedback.*`, `cfg.target.*` rows of `plan/lens-config.md`; searched (not read in full) the AMO, SR and Web manual inventories for cross-manual checks.
**Not found:** `docs/15-feature-inventory.md` and `docs/16-data-platform.md` do not exist in the repo (only `docs/22-apsis-data-profile.md` was added after `docs/13`). "Plan" below therefore means the `plan/lens-*.md` drafts. An item covered only by a lens draft is counted COVERED but is still not in the spec of record.
**PDF spot-checks** against `eefcbecb-TSO_App_User_Manual.pdf` pages 5-7, 10-11, 13-21 (log in Appendix B): the inventory is accurate; no factual error found. Three observations in the screenshots are not in the inventory and are used below (Not Uploaded is a superset of Not Logged In; the till-date targets fit a calendar-day pro-rata; the Set Plan card shows address, not cluster).

**Verdicts.** COVERED = spec or plan states it adequately. PARTIAL = mentioned, but a field, rule, state or verbatim text is missing. MISSING = absent from both spec and plan. CONTRADICTS = spec or plan says otherwise. A message is PARTIAL when its trigger exists in the spec but the verbatim text is not there (these roll into G-man-tso-26).

---

## 0. Headline

1. **29 gaps: 0 blockers, 12 majors, 17 minors** (section 1). There is no blocker because the TSO app is mostly reads plus a few control actions; the closest is the logout wipe (G-01), which the plan already rates a blocker as G-feat-41 but `docs/08` still says "keep that behaviour".
2. **Three plan defaults are contradicted by the manual** (not by the spec): Submit % divides by logged-in routes in both the TSO and the web tile (plan D-03 makes it divide by target routes, G-02); the till-date target is a calendar-day pro-rata, not working days (plan D-07, G-03); and the detail table prints 1271.19%, so a display cap of 1000 breaks parity (G-13). Three plan defaults are invented and unsupported: a 30-outlet visit-plan cap (routes hold a median 64, max 214 outlets), a 30-day leave cap, and feedback categories "Complaint, Bug" (G-17, G-12, G-22).
3. **Four things are absent from spec and plan (or only half there):** target rows at *variant* level (schema allows only category/brand/SKU, G-04); the read behind "Get Sales Data" (the already-submitted alert fires there, before Submit, and `docs/09` has no such endpoint, G-06); a map provider decision (the manual shows Google Maps with 3D buildings, G-11); and a defined source for `Dep Name` plus a route type for the suffix on the Login & Bikroy Joma lists (the plan adds `zone.dep_id` but no route type, G-08).
4. **Four places where spec and manual disagree:** "live" SR location vs the spec's last-synced fix (G-10); logout wiping unsynced data (G-01); Select Outlets shows address, not cluster, and the address is blank for all but 13 of 734,789 outlets (G-17); the leave form has one date plus a typed day count, not a range (G-12).
5. **The manual states nothing about offline, sync, refresh, caching or errors** (U-27). The TSO offline model is therefore a rebuild requirement taken from CLAUDE.md, not from this manual: section 5 gives the matrix and G-05 asks for a TSO read model, because `docs/04` defines only the SR bundle.
6. Coverage by item: 144 inventory items (24 screens, 17 flows, 50 rules, 24 message entries, 29 entities): 32 COVERED, 88 PARTIAL, 6 MISSING, 18 CONTRADICTS; plus 77 field and control checks (section 2).
7. What only the sponsor's screenshots or the live app can settle is listed in Appendix C (populated charts, the My Feedback sub-menu, category and task-type option lists, leave statuses, whether Final Submit is time-gated, the "live" mechanism).

---

## 1. Gap table (every PARTIAL, MISSING and CONTRADICTS item, grouped into 29 gaps)

Every non-COVERED item in Appendix A and A2 points to one or more of these gap ids. Severity: major = a daily TSO workflow, a KPI figure or a data model is wrong or undefined; minor = cosmetic, confirm-only or an edge rule.

| Gap | Manual screen + pages | What the manual says | What the spec/plan says | Kind | Sev | Concrete fix | Proposed home |
|---|---|---|---|---|---|---|---|
| **G-man-tso-01** Logout wipes ALL local data with no unsynced-data guard | TSO-S-24 p21; TSO-R-46; TSO-U-26 | Dialog title "Log Out!", body "Your all app data will removed.", buttons "Cancel" (green) and "Log Out" (orange). No pending-upload check, no connectivity check. SR and AMO logout (SR p79, AMO p79) is a plain "আপনি কি নিশ্চিত যে লগআউট করতে চান?" with no wipe statement. | docs/08: "Logout wipes all local app data ... keep that behaviour." CLAUDE.md #1 and #2 forbid losing a queued record; docs/04 clears per-day data only after a clean final submit. Plan F-SYS-022 / G-feat-41 (blocker): refuse or warn with a pending count; `cfg.app.logout_wipes_data = {tso:true}`. The spec of record was not updated. | contradiction | major | Amend docs/08: on "Log Out" confirm, if any local row has `sync_state` other than `synced`, or the media queue is non-empty, block with "N items not yet sent" plus [Sync now] [Cancel]; only a fully reconciled device wipes (DB, caches, secure storage, tokens). Keep the dialog wording as a bn/en string pair; fix the grammar in the new build. TSO rows at risk are visit queries, plans, tasks, leave and feedback with images. Add a test to T-3 (kill-and-relaunch plus logout with pending rows). | docs/08 Logout; docs/13 "deliberately changed"; F-SYS-022; `cfg.app.logout_wipes_data`; `cfg.app.logout_block_when_pending` |
| **G-man-tso-02** Submit % ("Bikroy Joma Status") is submitted ÷ logged-in on both the TSO and the web tile; plan D-03 flips it | TSO-S-04 p6; TSO-R-09, R-10; TSO-U-08; WEB-S-03 p3 (R-018) | p6 card: "Login Status 25%": "4 Target Route", "1 Total Login"; "Bikroy Joma Status 0%": "1 Login Count", "0 Total Bikroy Joma". Web dashboard p3: "Submit Status 0.0%" with "1 Login successfully" and "0 Submitted successfully". Both divide submits by logged-in routes. AMO Live Dashboard (R-069): "Sales-deposit % (deposits/logged in)". | docs/08: "submit % = total submitted / login count". docs/10: "Submit % = routes uploaded ÷ target routes (apps: ÷ logged-in)"; seed SF-7 says the web divides by target routes. Plan D-03: canonical Submit % = ÷ target routes; the app figure is renamed "Upload-of-login %" and shown as a secondary line. lens-config `cfg.kpi.submit_pct_denominator` (target_routes / logged_in_routes) is documented as "web target_routes, app logged_in_routes". | contradiction | major | Parity first: set `cfg.kpi.submit_pct_denominator = logged_in_routes` for the TSO "Bikroy Joma Status" bar and the web "Login/Submit Status" bar (the key's own note assumes the web shows target_routes; the web manual says otherwise). Keep ÷ target routes as the secondary, renamed figure. Correct SF-7 and docs/10: the web manual tile does not divide by target routes. Print the four captions exactly: "Target Route", "Total Login", "Login Count", "Total Bikroy Joma". Add "Bikroy Joma" (বিক্রয় জমা) = Sales Submit to the glossary. | docs/10 KPI table; lens-data D-03; docs/08 Dashboard; `cfg.kpi.submit_pct_denominator`; strings |
| **G-man-tso-03** Till-date target is a calendar-day pro-rata with per-item rounding up (inferred); plan defaults to working days | TSO-S-21, S-22 p19; TSO-R-43; TSO-U-24 | Monthly targets 3944 / 1425 / 526 / 510 (Cigarette / Bidi / Lighter / Match), till-date targets 3420 / 1236 / 456 / 442, achievements identical in both views. The Target Status screenshot is undated; the dated screenshots in this manual run 2026-04-26 to 28 (Final Submit "Sales Date: 2026-04-26", leave picker "Sun, Apr 26"). Monthly x 26/30 = 3418.1 / 1235.0 / 455.9 / 442.0 (26 elapsed days of 30): a fit once each item is rounded up and the items summed; 27/30 or 28/30 would not fit. Working days (Friday off) would be 22/26 = 0.846 and give 3337, which does not fit. | docs/10: "Achievement % = sales ÷ target (MTD or month)"; no till-date definition. Plan D-07 and `cfg.kpi.tilldate_basis` default to working days (uses the holiday calendar, G-feat-09). | contradiction (inferred) | major | Default `cfg.kpi.tilldate_basis = calendar_days` (days elapsed including today ÷ days in month), item targets rounded up and then summed; working days stays an option. Add a golden test with these eight numbers. It is an inference from one screenshot date: confirm against a live screenshot with a known date and the Web Target Allocation Report before the default is locked. | docs/10; lens-data D-07; `cfg.kpi.tilldate_basis`; `cfg.kpi.tilldate_rounding`; F-TSO-017 |
| **G-man-tso-04** Target Details rows are variant-level; the target model has no variant level | TSO-S-22 p19; TSO-E-25; AMO S-26 p35-36 | Table "Item / Target / Achievement / Remaining / %": "ESSE Change Mango 195 / 500 / 0 / 256.41%", "Ananda Bidi 25", "Special Abul Bidi 25", "MAX", "Maxim Double Burst 136 / 0 / 136 / 0.00%". In `db/seed/sku_catalog.csv` these are variant names ("Maxim Double Burst" is one variant with two SKUs, MaxDB-20S 20HL and MaxDB-10S 10HL; "MAX" is the lighter variant). The AMO details (Avon, ARIS Apple, Marise Special Blend, Existing Abul Bidi/ 42 No. Abul Bidi) are variants too. Rows are not in category order and there is no category column. | docs/03 and docs/10: targets "per category/brand/SKU". schema.sql `target.product_level` comment: 'category' \| 'brand' \| 'sku'. F-TSO-017: "item table at SKU/variant level", with no storage level. | missing-field | major | Add `variant` to the target level list (schema, docs/03, docs/10, Set Target UI, importer mapping of Apsis target rows). Roll achievement up SKU to variant (sum of the variant's SKU base quantities, in the target's unit). Details table = variant rows for the card's scope across all four categories, no category column; row order unknown (confirm on a populated screenshot). | docs/03 Targets; docs/10; schema.sql `target`; lens-data `target_uniq`; F-ADM-014 |
| **G-man-tso-05** No TSO offline model: the manual is silent and `docs/04` defines only the SR bundle | All screens p4-21; TSO-U-27, TSO-U-30 | The manual never mentions offline, sync, retry, refresh, pull-to-refresh, "internet required", caching, permission prompts or error handling. Online dependence is only implied (Final Submit duplicate check and success, live SR map, aggregates). | docs/08: "Same offline/sync rules." docs/04: `GET /sync/bundle` is the SR's routes, outlets, prices, targets. Plan lens-features gives an Off column per F-TSO row; lens-quality T-3-40 says "TSO visit plan set offline and synced; final submit online". No TSO bundle content, no staleness display, no queued-write list. | online-only | major | Add to docs/08 the matrix in section 5 and define a TSO read model: a login snapshot (`GET /app/home?role=tso`) plus zone, route and outlet pickers for the territory (about 2,500 outlets per territory on average, 734,789 / 291; well under 1 MB gzipped), delta-refreshed. Every aggregate screen shows "as of hh:mm". Queued writes (visit query, task, plan, leave, feedback) show a sync-state badge. Online-only actions (Final Submit, maps) are disabled offline with an explicit "needs internet" message. | docs/08; docs/04 (add TSO bundle); docs/09 `/app/home`; F-TSO-*; section 5 |
| **G-man-tso-06** Final Submit needs a read endpoint: the duplicate check fires at "Get Sales Data", before Submit | TSO-S-09, S-10, S-11 p12-14; TSO-U-19 | On "Get Sales Data" for a zone already submitted today the alert "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" appears (no title, button "OK"); otherwise the screen shows "Sales Date:" and "Routes" with "FF: <SR name>" or "FF: SR Not Set". The callout words it as "if one wants to submit again". | docs/09 API has only `POST /day/final-submit {zoneId, businessDate}` with 409 on a second attempt. docs/08: "A second attempt the same day is refused". Plan M-34 adds `final_submit_attempt` and a request `client_uuid`. | missing-feature | major | Add `GET /day/final-submit/preview?zoneId=` (scope-checked server-side) returning `{salesDate, alreadySubmitted, submittedAt, routes[{routeId, name, kind, frName\|null}]}`. The app shows the alert from `alreadySubmitted`. `POST /day/final-submit` stays idempotent: the same `client_uuid` returns the same success, another uuid gets 409 with the same bn text. Both calls are online-only. | docs/09 API (F-API-009 + new); docs/08 Final Submit; lens-data M-34 |
| **G-man-tso-07** Final Submit rules: Sales Date, "SR Not Set" routes, no confirm before an irreversible action, effect of submit | TSO-S-11, S-12 p13; TSO-U-18; WEB-S-19 p20 (R-052..R-056, U-14) | "Sales Date: 2026-04-26" read-only; five or six route cards including "FF: SR Not Set" (AMO-Apsis RouteAMO shown twice, Apsis AMOAMO); one "Submit" button; no confirm prompt; then "Success / Final Submit Done Successfully...". Web page: route table with Status "exist" or "--", a red "Delete Section Data" on "exist" rows, a "Date of Data Entry" picker, and "you cannot enter data before the cut-off date; for back-dated data phone support". | docs/08: "sales date + routes with FF (SR) name or 'Not Set' → Submit → success". docs/13 Q11 (reopen). Plan G-feat-64 ("Not Set" routes) and G-feat-17 (reopen, late sync) are open decisions; no rule for Sales Date derivation or a confirm step. lens-config has `cfg.day.final_submit_allow_not_set_routes` (default `warn`, A) and `cfg.day.final_submit_earliest_time = 17:00 (A: same as check-out)`. | underspecified | major | Write: (1) Sales Date = server business date (Dhaka), read-only in the app; past dates only from the web with the support override (parity with WEB-S-19). (2) "SR Not Set" and no-data routes do not block (parity), but the preview shows counts of routes with no upload and the app asks for an explicit confirm, because the manual has none and the action is irreversible (`cfg.day.final_submit_confirm`, default on). (3) After submit, late batches for that zone and day are accepted into the closed day and flagged (G-feat-17). (4) Per-route Status and Delete Section Data stay web-only. (5) No time gate: set `cfg.day.final_submit_earliest_time` to none; the plan's 17:00 is an assumption, the manual shows no gate and the web Final Submit Log sample carries a time of 14:26:05. The plan's `warn` default for Not Set routes is compatible with (2). | docs/08; docs/13 Q11; `cfg.day.final_submit_confirm`, `cfg.day.final_submit_allow_not_set_routes`, `cfg.day.final_submit_earliest_time`; G-feat-64, G-feat-17 |
| **G-man-tso-08** Login & Bikroy Joma lists: row unit, AMO rows, "Not Uploaded" is a superset, `Dep Name`, route type suffix | TSO-S-04 p6, S-05 p7, S-11 p13; TSO-U-11, U-18; WEB-S-19 p20 | Card: "4 Target Route", "1 Total Login". "Not Logged In" tab lists 5 user-route rows (sr334002 / Apsis Route 2, amo6334 / Route 2, amo63342 / Route 2, sr334003 / Route 3, amo6334 / Route 3). "Not Uploaded" lists 6+ rows and includes sr334002 / Route 2, which is also in the first tab. The same AMO username repeats on several routes. Line 4 "Dep Name: Banani - Test" equals the zone name. Route names carry a type suffix: "Apsis RouteDaily", "AMO-Apsis RouteAMO", "Apsis AMOAMO". | docs/08: "Drill-downs 'Not Logged In' / 'Not Uploaded' listing user (SR/AMO name, username, route, dep name)". docs/03 says zone has `dep_id`; schema.sql `zone` has none; `route` has `visit_days` only. docs/10: Login % = routes whose bundle was downloaded ÷ target routes. Plan M-32 `route_day`; lens-data adds `zone.dep_id text`. | underspecified | major | Define: list row = (assigned user, planned route) for the business date, AMOs included where assigned; "Not Logged In" = no bundle that day; "Not Uploaded" = no sales submit that day regardless of login (matches the screenshot). Counts on the card are per route, list rows per user-route: say so. Add `route.route_kind` (or a display-suffix rule: name + kind as printed, no separator) and `zone.dep_id` + `dep_name` (Dep Name = zone name in the sample). Decide whether AMO routes count in Target Route and Login % (`cfg.kpi.login_include_amo_routes`). | docs/08 Dashboard; docs/03 route, zone; schema.sql; lens-data M-32; docs/10 |
| **G-man-tso-09** Dashboard sales tiles: the "that day" achievement ring needs a daily-target basis; unit and number format | TSO-S-04 p5; TSO-R-06, R-07; TSO-U-06 | Tiles "Sales (Cigarette)", "Sales (Bidi)", "Sales (Lighter in Box)", "Sales (Match in Dozen)": figure with one decimal and caption "Total Sales", ring "92.5%" (160.0 total implies a day target of about 173). Callout: that day's total sales and achievement %. Units appear only for Lighter (Box) and Match (Dozen). The 173 does not reconcile with the monthly 3944 (131 per calendar day, 152 per working day): test data, so the basis cannot be inferred. | docs/08: "Cigarette (sticks), Bidi, Lighter (pieces/box - confirm unit), Match (dozen)" with achievement %. docs/10: achievement = sales ÷ target (MTD or month); no daily target. Plan has `report_unit` / `report_factor` (lens-data D-02) but nothing for the day denominator. | underspecified | major | Define the ring: day sales ÷ (monthly target ÷ days in month; same switch as G-03, default calendar), "—" when there is no target. Tile spec: fixed order; unit in the heading for Lighter (Box, via `report_factor`; the web shows pieces, "Lighter in Pcs") and Match (Dozen); Cigarette and Bidi in sticks (docs/22 P-04); one decimal; ring one decimal. Make tile list, unit label and decimals config so Digonto or other categories can be added without a release (Q14). | docs/08; docs/10 KPI; lens-data D-02; `cfg.tso.dashboard_tiles`; `cfg.kpi.daily_target_basis`; Q8, Q14 |
| **G-man-tso-10** "My Team" promises live SR location; the spec delivers the last synced fix | TSO-S-13 p15; TSO-R-29; TSO-U-20 | Screen "My Teams": zone dropdown, then SR markers; callout "লাইভ লোকেশন" (live location) of all SRs by route. No refresh interval; marker tap not shown. AMO Team Location says the same (R-022, R-058). | docs/08: "live SR locations on a map (last synced fixes)". CLAUDE.md #3 and docs/04: no continuous GPS. Plan F-TSO-011 / F-API-023 `user_last_fix`; `cfg.tso.team_location_max_age_min = 120 (A)`. docs/13 "deliberately changed" does not list it. | contradiction | major | Add to docs/13 "deliberately changed". Each marker shows SR name, route, fix time ("12 min ago") and source (check-in / visit / sync) and greys out past `cfg.tso.team_location_max_age_min`. If the business needs fresher positions, add an opt-in supervisor "ping" (server push, one balanced fix, foreground only), never continuous tracking. Check what Apsis really records (continuous or per visit) when the dump arrives (docs/13 Q18). | docs/08 My Team; docs/13; F-TSO-011; F-API-023 |
| **G-man-tso-11** No map provider, key or data-budget decision; the manual shows Google Maps with 3D buildings | TSO-S-13, S-14 p15; TSO-R-50; AMO S-24 p33 (R-058) | Google-Maps-style map: tilted 3D buildings (My Teams), compass, my-location crosshair, recentre, English and Bangla labels (Dhaka ঢাকা, Mohakhali). AMO inventory: "Google Maps SDK + internet (inferred)". | No map SDK, API key, billing, tile-data or offline-tile decision in docs/01-13 (only "map tiles (cached?)" for Update Base, F-AMO-028). docs/04 data budget 2 GB/day. | new-config | major | Decide Google Maps SDK (key in Key Vault, restricted by package and signing SHA, billing alert) vs MapLibre/OSM. Either way: 2D only (no 3D buildings), lite mode, load a map only when its screen opens, bounded tile cache (`cfg.map.tile_cache_mb`), no map on the dashboard; budget a worst-case TSO map session in docs/04. One SDK serves TSO My Team and Retailer, AMO Team Location and Update Base. | docs/02; docs/04; docs/13 (new question); `cfg.map.*` |
| **G-man-tso-12** Leave: one date plus a typed day count, a from/to display, and a status badge that names the approver | TSO-S-07, S-08 p10-11; TSO-R-15..R-19; TSO-U-14..U-16 | List card "2026-02-25 to 2026-02-25" with "2 Days"; "2026-03-04 to 2026-03-04" "1 Day"; reason "only one day can apply!"; orange badge "DMO approval pending". Form: Leave Type (Casual, Sick, Earn), ONE "Select Date", "Number of days" (default 0), Reason, "Apply". Leave type is not shown on the card. | docs/08: "date(s), number of days, reason"; list "date range, days, reason, status". Plan M-33: `from_date`, `to_date`, `days numeric(4,1) CHECK (days>0)`, `CHECK (to_date >= from_date)`, `status text`, `approver_role`; `cfg.leave.max_consecutive_days = 30 (A)`. | underspecified | major | Model what the manual shows: form captures `from_date` (single date), integer `days` >= 1, type, reason. For new rows derive `to_date = from_date + days - 1`; import the Apsis sample as is (to = from with days = 2), never recompute or reject. `days` is authoritative. Card renders "<from> to <to>" and "<n> Day(s)". Badge pattern "<approver role> approval pending" implies a status set pending / approved / rejected / cancelled plus `approver_role`; only the pending text is verified. Drop the invented 30-day cap. Confirm whether a multi-day application is allowed (card 1 says yes; the reason text hints at a rejection message). | docs/08 Leave; lens-data M-33; `cfg.leave.*`; F-TSO-008/009 |
| **G-man-tso-13** Target Status display rules: summary capped at 100%, details uncapped; plan clamps at 1000 | TSO-S-21, S-22 p19; TSO-R-41..R-44; TSO-U-24, U-29 | Summary rows "38960/3944 100%" with full bars (achievement 988% of target prints 100%). Details: "256.41%", "765.31%", "1271.19%"; Remaining 0 or 136; headers wrap as "Achieve ment", "Remaini ng". Callout says "targets and achievements of all routes", but the cards are "Territory: Apsis" and "Zone: Banani - Test". | docs/08: "bars (achieved/target, %)", item table. Plan `cfg.target.achievement_pct_cap` default 1000 (display clamp); lens-data target remaining = GREATEST(target - achieved, 0). | contradiction | minor | Two display rules: summary label and bar capped at 100; detail % uncapped with two decimals, so 1271.19% must print as is and a global 1000 default breaks parity (set the detail cap to none). "A/B" = achievement / target. Remaining = max(target - achievement, 0). Zero target shows "—", not 0% or infinity. Each card's Details opens its own scope (Territory card: territory rows; Zone card: that zone). No route cards (the AMO app has them); confirm. | docs/08; docs/10; `cfg.target.achievement_pct_cap`; `cfg.tso.target_summary_pct_cap`; F-TSO-017 |
| **G-man-tso-14** Dashboard charts: metric naming, drill-down glyph, empty charts, brand label | TSO-S-04 p5-6; TSO-U-04, U-05, U-07 | "By Channel STD ▸" (green donut, "100.00%" "GT Channel"); "By Segment Value Contribution" (legend Value orange, Volume teal; x label "Medium"; y ticks 83 / 67 / 50 / 33 / 17 / 0%); "By Brand Call/Memo Ratio" (x label "Maxim - Platinum Series"). Callouts name "By Channel Successful Call" and "Live CPR": wording that matches the web dashboard tiles ("By Channel Successful Call", "Live Strike Rate"), not the screen titles. Segment and brand charts are empty in every screenshot. | docs/08: "By Channel STD (pie, e.g. GT 100%)", "By Segment Value Contribution (value vs volume)", "By Brand Call/Memo Ratio". Plan F-TSO-003/005/006; Q9 (BSR denominator); "which price type for value" open. | underspecified | minor | Fix a contract per card in `GET /dashboard/live`: channel[{channel, std, pct}] (metric = STD volume, per the on-screen title; the web tile is calls-based), segment[{segment, value_pct, volume_pct}], brand[{label, ratio_pct}]. Open: what the "▸" opens; whether "Maxim - Platinum Series" is a brand, a variant or a series level that the product tree lacks (the seed has no "Platinum Series"); channel display names ("GT Channel", "DCC Channel", ..., "HoReCa") need a label table because schema.sql uses an enum. Get a populated screenshot before building. | docs/08; docs/10 BSR; F-TSO-003/005/006; channel lookup |
| **G-man-tso-15** Final Submit pickers: five-level cascade, scope-bounded options, "all zones" callout | TSO-S-09 p12, p14; TSO-R-20..R-23; TSO-U-17, U-29 | Five labelled dropdowns "Select Wing / Division / Territory / House / Zone"; "Get Sales Data" grey until all five are chosen, green after. Callout: "এই মেন্যুতে সকল জোনের 'Final Submit' এর তথ্য দেখা যাবে" (information of all zones can be seen) although the form takes one zone. | docs/08 lists the cascade. docs/09: filters defaulted and bounded to the user's scope. Plan: scope-bounded pickers. | underspecified | minor | Parity: five labelled dropdowns, cascade Wing > Division > Territory > House > Zone, options from the server scope only. Auto-select a level that has one option (a TSO has one territory). Enable "Get Sales Data" only when Zone is chosen. The "all zones" callout: either a submitted / pending badge per zone in the Zone list (improvement; data from F-WEB-047) or ignore it; confirm. | docs/08 Final Submit; F-TSO-010; F-WEB-047 |
| **G-man-tso-16** Map screens: radius unit and default, centre, markers, density, empty states, names | TSO-S-13, S-14 p15; TSO-R-30, R-31; TSO-U-20 | Both screens have a Zone dropdown ("Banani - Test"); Radius list "50 100 300" with no unit and no default shown; no outlet markers in the screenshot; blue dot = own position; SR marker is an orange scooter on a pink pin. Menu "My Team" opens a screen titled "My Teams"; menu "Retailer" opens "Outlets". | docs/08: "zone + radius (50 / 100 / 300 m) → outlets around the TSO's current location". Plan `cfg.tso.periphery_radius_options_m`, F-API-019. docs/22 P-10: 80% of outlets share a 55 m cell, the densest holds 383. | underspecified | minor | Show the unit ("m"); default radius = first option (config). Centre = current location with a "location off / denied" message and a zone-centroid fallback. Marker = outlet name and code (phone only for roles allowed PII). Cluster markers and cap at `cfg.tso.periphery_max_markers`: 300 m in a dense cell returns thousands. Empty-state text. My Team: markers coloured or grouped by route with a legend; marker tap = name, route, fix time (G-10). | docs/08; F-TSO-011/012; F-API-019; `cfg.tso.periphery_*` |
| **G-man-tso-17** Set Plan: card shows address (not cluster), no search, invented 30-outlet cap, repeat Set Plan | TSO-S-19, S-20 p18; TSO-R-38, R-39; TSO-U-23 | Select Outlets card: bold name, "2689479 \| Jobbar Tower Lake Par", "Md Murad Hossain \| [phone]"; green check = selected; no search, select-all or count; button "Set Plan"; no confirmation shown. | docs/08: "multi-select outlets (name, code, cluster, owner, phone)". Plan M-35 `UNIQUE (planner_id, plan_date, route_id)`; `cfg.tso.visit_plan_max_outlets` default 30 (A). docs/22 P-12: address filled for 13 of 734,789 outlets; P-13: route median 64 outlets, 99th percentile 112, max 214. | contradiction | minor | Card shows address when present, else cluster name (address is blank in practice). Default the cap to none (a normal route has more than 30 outlets); keep it configurable. Add search and select-all / clear as an improvement for 64-214 outlet routes (not in the current app). Define a repeat Set Plan for the same date and route as a union by outlet, idempotent by client uuid; no un-planning (not documented). Plan date today or later (past dates rejected: assumption). | docs/08 Set Plan; lens-config `cfg.tso.visit_plan_max_outlets`; F-TSO-013 |
| **G-man-tso-18** Visit plan lifecycle: Pending / Completed rule, Completed card, edit and delete | TSO-S-15, S-16 p16-17; TSO-U-21 | "Pending" tab selected first, "Completed" tab; a pending card has ">" ; tapping a completed card is not shown. Callouts: status "pending/completed"; "the visit is completed from My Visit Plan". No edit, delete or re-plan. | docs/08: Pending / Completed tabs. Plan G-feat-49: completion rule is an assumption. | underspecified | minor | Write: an outlet becomes Completed when its Visit Query is submitted (delegate Yes or No), immediately on the TSO device (queued). A Completed card opens a read-only view of the answers (improvement; not shown). Pending tab is the default; empty-state texts; no edit or delete of a plan (parity). View is by (date, zone, route) of plans the TSO created. Cache the day's plan for offline. | docs/08 My Visit Plan; G-feat-49; F-TSO-014 |
| **G-man-tso-19** Visit Query: free-text answers, Bangla labels, delegate default No, outcome of "No" | TSO-S-17 p17; TSO-U-22 | Two questions in Bangla, "SR আপনার দোকান নিয়মিত ভিজিট করে?" and "SR নিয়মিত মেমো প্রিন্ট করে দেয়?", each a free-text box with placeholder "Enter your answer"; third control "Do you want to delegate the task?" radio Yes / No, "No" selected; "Submit". | docs/08: questionnaire plus "Delegate task? Yes/No → Submit". Plan `cfg.survey.tso_visit_query_questions` (types yes_no / choice / number / photo); `call_assessment.answers jsonb`; F-TSO-015. | underspecified | minor | Seed the question config with the two questions as type `text`, bn labels as printed, en translations. The delegate radio is a built-in control (default No), not a config question. Submit with No: save, mark the outlet Completed, return to the list. Yes: open Assign Task. Answers optional (not stated); free-text limit 500 characters (assumption). No GPS or photo (a TSO visit is not geo-validated, unlike an SR call). | docs/08 Visit; `cfg.survey.tso_visit_query_questions`; lens-data M-27 |
| **G-man-tso-20** Assign Task: assignee when "SR Not Set", task-type list and label, due date | TSO-S-18 p17; TSO-U-22 | Read-only card "Outlet Name / Cluster / Route"; an unlabelled dropdown showing "Irregular Visit"; date field "April 28, 2026" (two days after the plan date); "Comment" (optional); "Assign Task". Callout: Task Type must be selected, date chosen, comment optional. | docs/03: `task` with `assignee_id NOT NULL`, types oos / general / irregular_visit; docs/08: assign to the route's SR. Plan `cfg.task.types`, `cfg.task.assign_roles`, `task.route_id` (M-25). | underspecified | minor | Assignee = the SR active on that route on the due date (`route_assignment`). If the route shows "FF: SR Not Set" or is an AMO route, block with a message (new string) instead of creating an orphan task. Label the dropdown "Task Type"; options = `cfg.task.types` allowed for TSO (only "Irregular Visit" is evidenced). Due date minimum today; default unknown. Link the task to the visit-plan outlet. | docs/08 Visit; docs/03 task; `cfg.task.*`; lens-data M-25 |
| **G-man-tso-21** Leave: no validation, overlap, balance, success or cancel behaviour | TSO-S-08 p11; TSO-U-15, U-16 | No required-field, balance, overlap, past-date, success or failure message; native date picker; no balance shown; no edit or cancel of an application; only "DMO approval pending" visible. | docs/08/09 silent. Plan `cfg.leave.types`, `cfg.leave.approver_role_by_applicant_role`, `cfg.leave.max_consecutive_days`; F-WEB-046 (DMO decision on the web, assumption); G-feat-25. | missing-rule | minor | Define as config with parity defaults (all off): `cfg.leave.allow_past_days`, `cfg.leave.block_overlap`, `cfg.leave.balance_enforced`. Required fields: type, date, days >= 1, reason, with inline messages in bn and en. On Apply: success toast and a new card with the pending badge. Apply is queued offline (client uuid); the card shows "not sent yet" until synced. A DMO decision reaches the TSO on the next list refresh (no push). Edit and cancel: not in the manual; allow cancel while pending only if the business asks. | docs/08 Leave; `cfg.leave.*`; F-WEB-046; F-API-022 |
| **G-man-tso-22** Feedback: categories, the My Feedback sub-menu, image rules | TSO-S-23 p20; TSO-U-13, U-25 | "Create New Feedback": "Feedback Category" dropdown (value "Suggestion"; other options unknown), "Feedback Title", "Descriptions", a thumbnail and "Browse Gallery" (device gallery only), "Save". The drawer item "My Feedback / Manage your feedbacks" has an expand chevron, but no sub-item is shown anywhere. | docs/08: "Category (e.g. Suggestion), Title, Description, image from gallery → Save". Plan `cfg.feedback.categories` default "Suggestion, Complaint, Bug (A)"; M-36 `feedback`; F-ADM-028 inbox. | underspecified | minor | Only "Suggestion" is evidenced: keep the list as config and drop the invented defaults. The chevron implies at least a "My feedback" list sub-screen (title, category, date, status) that is not documented: build create plus a simple own-feedback list and confirm on a screenshot. Image: Android photo picker (no storage permission), compress to the docs/04 budget, upload through the media queue, one image (limit not stated). Save is queued offline; add success and failure toasts (none shown). | docs/08 My Feedback; `cfg.feedback.categories`; F-TSO-018; F-ADM-028 |
| **G-man-tso-23** TSO UI is English-first; CLAUDE.md says Bangla-first | All screens; TSO-M-08..M-24 | Labels, dialogs, drawer and field names are English. Bangla appears only in the duplicate-Final-Submit alert, the two Visit Query questions and the callouts. The login screen has no language switch. | CLAUDE.md #8: field apps Bangla-first "with English labels in places (as today)". docs/08 silent. Plan F-SYS-019 language toggle for all apps; lens-config `cfg.app.default_locale` = `bn` (scope includes ROLE). | contradiction | minor | Ship a full bn and en catalogue for the TSO build. Default language for TSO = English (parity with today) by setting `cfg.app.default_locale` per role (sr: bn, amo: bn, tso: en); the user can switch (G-24). Keep the three Bangla strings verbatim. Bundle the Bengali font (Bangla strings exist). | CLAUDE.md #8; docs/08; F-SYS-018/019; `cfg.app.default_locale` |
| **G-man-tso-24** TSO app has no Settings; plan "all-app" features are absent from its drawer | TSO-S-06 p8; TSO-U-30; SR S-58 p77; AMO S-59 p77 | The drawer has exactly seven entries. No Settings, language, PDA to Support, version or update, printer, profile, change password or notifications. The login screen shows no version. SR and AMO have Settings (language, PDA to Support, Log Out). TSO has no device-OTP screen (the OTP is read on the web portal). | Plan F-SYS-019 (language), F-SYS-020 (updater), F-SYS-021 (PDA to Support), F-SYS-004 (change password) are `all-app`. docs/08: seven drawer entries. | contradiction | minor | State in docs/08: current TSO drawer = seven entries, no Settings. To keep the plan's cross-cutting features reachable, add a Settings entry (language, app version and update check, send data file to support, change password) as a new, clearly marked improvement. No printer, no OTP for the TSO app. | docs/08 Drawer; F-SYS-019/020/021; F-SYS-004 |
| **G-man-tso-25** Distribution: per-role APK side-load, file naming, launcher name, no version shown | TSO-S-01, S-02 p2-3; TSO-R-04; TSO-U-01, U-02 | Side-load from device storage: `aron_tso_app_28_02_2026_v1.apk` (74.09 MB); AMO files `live_aron_amo_a..._2026_v1.0.3.apk` (79.23 MB) and `..._v1.old.apk` (78.75 MB); dialogs "Do you want to install this app?" and "App installed."; launcher label "ARON TSO". The second callout says "ARON SR" (copy-paste error). | docs/01: "three separate Flutter apps"; docs/02: one role-aware codebase; docs/13: "or keep three builds"; docs/11: signed APK channel, coexist with the old app (different package id); docs/04: size well below ~90 MB. | underspecified | minor | Decide: one codebase, three Android flavours (own applicationId, label "ARON TSO", icon) so install and training parity hold and the TSO build omits SR-only code (printing, DRP). File naming `aron_<role>_app_<dd_mm_yyyy>_v<n>.apk`. Show the app version on login and in the drawer (absent today; helps support). Add a runbook step "allow install from this source" (not in the manual). Target the TSO APK at or below 74 MB. | docs/02; docs/11; docs/13 (Q1 follow-up); F-SYS-033/036/044 |
| **G-man-tso-26** Verbatim message and string catalogue is not in the spec | TSO-M-01..M-24 p2-21; TSO-U-09 | 7 dialogs, alerts and statuses plus 17 groups of labels and placeholders, in two languages: English dialogs, a Bangla alert. Typos kept in the product: "Your all app data will removed.", "Final Submit Done Successfully..." (literal ellipsis). Callout typos "Terrotory", "Assing Task". No text exists for login errors, empty states, validation, save success or failure, network errors or loading. | docs/08 paraphrases only (for example "already given FINAL SUBMIT for today"). CLAUDE.md: strings live in the localization layer. Plan F-SYS-018. | missing-message | minor | Add a TSO strings appendix (key, en, bn) for all 24 entries. Keep M-03..M-24 verbatim where they are product text; fix the typos in the new build. M-01 and M-02 are Android system dialogs (not app strings). The Bangla alert needs an English twin. Author the messages the manual lacks in both languages (list in section 4). | docs/08 appendix; localization catalogue |
| **G-man-tso-27** App chrome: greeting, Home FAB, drawer subtitles, back button, pickers, date formats | TSO-S-04, S-06 p4-21; TSO-R-13, R-47, R-48; TSO-U-12 | Header: hamburger top-left, "Good day, <display name>", logout icon top-right (also in the drawer header); drawer header "TSO" plus name. Blue Home FAB (house icon, bottom centre) on Dashboard, Select Outlets, My Teams / Outlets, Visit Query and Feedback (not on the Final Submit selection). Round back "<" with a green title. Purple-outlined dropdowns; full-width green primary button, grey when disabled; native Android date picker. Dates "2026-04-26" on the Leave list and Sales Date but "April 26, 2026" on plan and task screens. Drawer subtitles: "View Dashboard", "Manage your leaves", "Submit Sales Data", "Check your team and outlet", "Create and manage plans", "Check targets", "Manage your feedbacks". | docs/08: drawer item names and a "darker themed app". No Home FAB, greeting, subtitles or date formats. | underspecified | minor | Add a "TSO app chrome" subsection to docs/08 with the list at left as the parity spec. Date display through the localization layer (ISO in lists, long form in pickers; Bangla month names and digits under bn). Home FAB returns to the Dashboard (tap behaviour is not documented). The p8 callout "hamburger at top right" is wrong: it is top-left. | docs/08; localization layer; design tokens |
| **G-man-tso-28** Outlet card data formats: phone without leading 0, code styles, channel label | TSO-S-16, S-20 p16-18; TSO-R-49; TSO-U-21, U-23 | Contact shown as 10 digits ("[phone]", "[phone]") but also "0178916371" on one row; outlet code "DHK-344-011" (S-16) vs "2689479" (S-20); "Channel: GT Channel". Web Browse Retailer shows the contact as 11 digits 01xxxxxxxxx and 7-digit codes. | docs/22 P-07 (code normalisation), P-12 (phone filled for 100%); G-feat-55 (code rule); lens-data `phone_hash`. No display or normalisation rule for phone or channel label. | underspecified | minor | Normalise `contact_number` to 11 digits "01XXXXXXXXX" on import and display (the 10-digit values are most likely a lost leading zero). Keep `outlet_code` as text. Channel display via a lookup ("GT Channel"; "HoReCa" has no suffix). Owner and phone follow the PII role matrix (G-feat-59). | docs/22 P-12; docs/11 importer; docs/03 outlet; G-feat-55, G-feat-59 |
| **G-man-tso-29** Device class and minimum Android version are not stated | All screenshots; TSO-S-01 | Every screenshot sits in a Samsung Galaxy J-series-style frame with hardware recent / home / back keys and 16:9 low-resolution screens; the date picker is the Material 2 style. The frame may be decorative. | docs/04: "cheap phone", "low-end device"; no OS, ABI or screen floor. | underspecified | minor | Set and document `minSdk`, a 360 x 640 dp baseline layout, hardware back-key handling and no gesture-only navigation. Run the TSO test pack on a J-series-class device. Evidence is weak: confirm the real fleet (Android versions, models) from the Apsis `device` table in the dump (docs/11). | docs/04; docs/11; docs/12 test plan |

## 2. Coverage tally

Counted from Appendix A (inventory items) and A2 (fields and controls inside screens). Every item has exactly one verdict.

| Group | Items | COVERED | PARTIAL | MISSING | CONTRADICTS |
|---|---|---|---|---|---|
| Screens (TSO-S) | 24 | 3 | 15 | 0 | 6 |
| Flows (TSO-FL) | 17 | 1 | 13 | 0 | 3 |
| Rules (TSO-R) | 50 | 17 | 23 | 4 | 6 |
| Messages (TSO-M) | 24 | 3 | 19 | 1 | 1 |
| Entities (TSO-E) | 29 | 8 | 18 | 1 | 2 |
| **Inventory items subtotal** | **144** | **32** | **88** | **6** | **18** |
| Fields and controls (A2) | 77 | 22 | 44 | 4 | 7 |
| **All items** | **221** | **54** | **132** | **10** | **25** |

Gaps: 29 in total, 0 blocker, 12 major, 17 minor. By kind: underspecified 15, contradiction 7, contradiction (inferred) 1, missing-field 1, online-only 1, missing-feature 1, new-config 1, missing-rule 1, missing-message 1.

Notes on the counts. (1) A message is PARTIAL when the spec names the trigger but not the verbatim text (17 of the 19 PARTIAL messages). (2) Android system dialogs M-01 and M-02 count as COVERED because nothing app-owned has to be built. (3) An item covered only by a `plan/lens-*.md` draft is COVERED (the plan is the plan of record for this task) but is listed in section 3 as a schema gap. (4) The manual's own typos and callout errors are not counted as items; they are in section 6C. (5) Pages 1 (cover) and 22 (closing slide) have no content.

---

## 3. Manual items that imply a database field or table the schema lacks

"schema.sql" = `db/schema.sql`. "Plan" = `plan/lens-data.md` migration id. A row marked "plan covers" still needs to land in the schema of record.

| # | Manual item | schema.sql today | Plan | Needed | Gap |
|---|---|---|---|---|---|
| 1 | Leave applications: type, date, days, reason, status "DMO approval pending", approver role | no table | M-33 `leave_application`, `leave_event` | Plan covers; amend semantics: single `from_date` plus typed `days` authoritative, `to_date` derived on new rows, no `CHECK` that rejects imported rows with `to_date = from_date` and `days = 2`; `status` set and `approver_role` | G-12 |
| 2 | Target rows at variant level ("Maxim Double Burst", "MAX") | `target.product_level` documented as category / brand / sku | none | Add `variant` to the level list or a CHECK on `product_level`; roll-up SKU to variant in the target and achievement aggregates; importer mapping | G-04 |
| 3 | `Dep Name` on the Not Logged In / Not Uploaded rows | `zone` has no `dep_id` although docs/03 says it does | lens-data adds `zone.dep_id text` | Add `zone.dep_id` and `zone.dep_name` (or define Dep Name = zone name) | G-08 |
| 4 | Route type suffix on route names ("Apsis RouteDaily", "AMO-Apsis RouteAMO", "Apsis AMOAMO") | `route` has `name`, `visit_days` only | none found | `route.route_kind` (daily / amo / other) or a stored display suffix; rule for AMO routes in Target Route and Login % | G-08 |
| 5 | Per user-route rows for the two lists | `route_log` is per route per day | M-32 `route_day` (primary and acting user) | List needs (user, route, date) rows: derive from `route_assignment` (SR and AMO roles) joined to `route_day`; document the join | G-08 |
| 6 | Visit plan (date, zone, route, outlets, Pending / Completed) | no table | M-35 `visit_plan`, `visit_plan_outlet` | Plan covers; add repeat-Set-Plan rule (union by outlet) and completion rule | G-17, G-18 |
| 7 | Visit Query answers (two free-text answers, delegate flag) | `call_assessment.answers jsonb`, `kind = 'retailer_questionnaire'`, no plan link | M-27 `visit_plan_outlet_id`, `delegate_task` | Plan covers; question definitions as config (type `text`) | G-19 |
| 8 | Delegated task from the visit (route, plan outlet) | `task` has no `route_id`, `client_uuid`; `assignee_id NOT NULL` | M-25 | Plan covers; assignee resolution rule for "SR Not Set" and AMO routes | G-20 |
| 9 | Feedback (category, title, description, image, status) | no table | M-36 `feedback` | Plan covers; categories as config; own-feedback list read | G-22 |
| 10 | SR last known location grouped by route (My Team) | no location table | M-17 `geo_fix` | Plan covers; fix must carry `route_id` and source for the marker label and the age grey-out | G-10 |
| 11 | Final Submit route snapshot ("FF: <name>" or "SR Not Set") and the already-submitted attempt | `final_submit` (zone, date, by, at) only | M-34 `final_submit_route`, `final_submit_attempt`, `client_uuid` | Plan covers; add the preview read (no table) | G-06, G-07 |
| 12 | Channel display names ("GT Channel", "DCC Channel", ..., "HoReCa") | `channel_type` enum ('GT', 'DCC', ...) | none | Lookup table `channel(code, label_en, label_bn, sort)` (docs/03 allows enum or lookup) | G-14 |
| 13 | By Channel STD, By Segment Value Contribution, By Brand Call/Memo Ratio | `fact_daily_route_sku` has no channel; `fact_daily_outlet` has no brand, segment or channel | dw `agg_daily_outlet_brand`, `agg_daily_zone` (D-section) | Plan covers; schema.sql facts cannot produce these three cards. Territory x day grain with channel, segment and brand | G-14 |
| 14 | Day-level target for the achievement ring | `target` is monthly only | none | View `v_target_daily` = monthly target ÷ days (basis switch); also `v_target_tilldate` with per-item round-up | G-03, G-09 |
| 15 | Password hash, history (login) | none | M-40 | Plan covers | - |
| 16 | App version per device (support) | `device` has no `app_version` | M-41 / M-44 `app_release` | Plan covers; show the version in the TSO app | G-25 |
| 17 | Outlet contact number normalised to 11 digits; address mostly null | `outlet.contact_number text`, `address text` | `phone_hash` only | Importer normalisation and a display rule | G-28 |
| 18 | "Total Service Zone" counter (web Final Submit Status tile) | `zone` has no type | none | Confirm whether a service-zone flag exists and whether the TSO Zone picker lists such zones (web manual only) | 6D |

---

## 4. Manual items that imply an admin-configurable parameter

"Exists" = already a key in `plan/lens-config.md`; "NEW" = not in the plan. Defaults are parity defaults taken from the manual; "(A)" = assumption, confirm.

| # | Manual item | Parameter | Type and parity default | Status | Gap |
|---|---|---|---|---|---|
| 1 | Radius options 50 / 100 / 300 (S-14) | `cfg.tso.periphery_radius_options_m` | list<int> [50, 100, 300] | Exists | G-16 |
| 2 | Radius default, unit label | `cfg.tso.periphery_default_radius_m` | int, first option | NEW | G-16 |
| 3 | Dense cells return thousands of outlets | `cfg.tso.periphery_max_markers` | int, 300 (A); cluster above | NEW | G-16 |
| 4 | "Live" SR position age | `cfg.tso.team_location_max_age_min` | int, 120 (A); grey out beyond | Exists | G-10 |
| 5 | Map provider, key, tile cache, 3D | `cfg.map.provider`, `cfg.map.api_key_ref`, `cfg.map.tile_cache_mb`, `cfg.map.3d_enabled` | enum / secret ref / int / bool; 3D off | NEW | G-11 |
| 6 | Leave types Casual, Sick, Earn | `cfg.leave.types` | list; Casual, Sick, Earn | Exists | G-12 |
| 7 | "DMO approval pending" approver | `cfg.leave.approver_role_by_applicant_role` | json {tso: dmo} | Exists | G-12 |
| 8 | Leave day cap | `cfg.leave.max_consecutive_days` | int; none (drop the 30 default) | Exists, wrong default | G-12 |
| 9 | Past dates, overlap, balance | `cfg.leave.allow_past_days`, `cfg.leave.block_overlap`, `cfg.leave.balance_enforced` | bool; all off at parity | NEW | G-21 |
| 10 | Visit Query questions (two, free text, bn) | `cfg.survey.tso_visit_query_questions` | list; type `text`, bn and en labels | Exists, type differs | G-19 |
| 11 | Answer length | `cfg.tso.visit_query_answer_max_chars` | int, 500 (A) | NEW | G-19 |
| 12 | Task types a TSO may assign | `cfg.task.types`, `cfg.task.assign_roles`, `cfg.tso.assignable_task_types` | list; "Irregular Visit" evidenced | Exists / NEW | G-20 |
| 13 | Outlets per visit plan | `cfg.tso.visit_plan_max_outlets` | int; none | Exists, wrong default (30) | G-17 |
| 14 | Plan date window | `cfg.tso.plan_backdate_days`, `cfg.tso.plan_max_days_ahead` | int; 0 and (A) 7 | NEW | G-17 |
| 15 | Feedback categories, images | `cfg.feedback.categories`, `cfg.feedback.max_images` | list; "Suggestion" only evidenced; int 1 | Exists / NEW | G-22 |
| 16 | Till-date basis and rounding | `cfg.kpi.tilldate_basis`, `cfg.kpi.tilldate_rounding` | enum calendar_days; enum ceil_per_item | Exists, wrong default / NEW | G-03 |
| 17 | Daily target for the tile ring | `cfg.kpi.daily_target_basis` | enum calendar_days (same switch as G-03) | NEW | G-09 |
| 18 | Which Submit % is shown | `cfg.kpi.submit_pct_denominator` | enum logged_in_routes (parity for TSO and web) / target_routes | Exists, wrong default for the web | G-02 |
| 19 | AMO routes in Login % and Target Route | `cfg.kpi.login_include_amo_routes` | bool; (A) true, to match "Target Route 4" | NEW | G-08 |
| 20 | Target % display caps | `cfg.target.achievement_pct_cap`, `cfg.tso.target_summary_pct_cap` | int none (detail); 100 (summary) | Exists, wrong default / NEW | G-13 |
| 21 | Dashboard tile list, unit label, decimals | `cfg.tso.dashboard_tiles` | list<{category, unit_label, report_factor, decimals}>; Cigarette, Bidi, Lighter (Box), Match (Dozen) | NEW | G-09 |
| 22 | Which dashboard cards are shown | `cfg.tso.dashboard_cards` | list; sales, channel, CPR, segment, brand, login | NEW | G-14 |
| 23 | Final Submit confirm, Not Set routes, time gate | `cfg.day.final_submit_confirm`, `cfg.day.final_submit_allow_not_set_routes`, `cfg.day.final_submit_earliest_time` | bool true; enum allow (plan default warn); time none (plan default 17:00 (A)) | confirm NEW; other two exist, time default unsupported | G-07 |
| 24 | Logout wipe and its guard | `cfg.app.logout_wipes_data`, `cfg.app.logout_block_when_pending` | json {tso: true}; bool true | Exists / NEW | G-01 |
| 25 | Default language per role | `cfg.app.default_locale` | enum per role: sr bn, amo bn, tso en | Exists (global default `bn`); TSO value differs | G-23 |
| 26 | Bangla and English label fixes | `cfg.i18n.overrides` | map | Exists | G-26 |
| 27 | Drawer entries per role | `cfg.app.drawer_items.tso` | list; the seven entries | NEW (cf. `cfg.app.home_tiles`) | G-27 |
| 28 | TSO product scope (Digonto assets) | `cfg.tso.product_scope` | list<category_id> | Exists | G-09 |
| 29 | Working-day calendar | `cfg.calendar.*` | weekend days, holidays | Exists (docs/22 P-03) | G-03 |
| 30 | Minimum app version, side-load channel | `cfg.release.min_version`, `cfg.release.update_url` | semver; url | Exists | G-25 |

Messages the manual does not print and the rebuild must author (bn and en, all editable through `cfg.i18n.overrides`): login failure (wrong credentials, wrong role, no network), required-field errors (login, leave, Visit Query, Assign Task, Feedback), leave success and failure, Set Plan success, Visit Query and Assign Task success and failure, Feedback success and failure, Final Submit failure (network, no scope), "needs internet" for online-only actions, empty lists (no leave, no plan, no outlets, no routes), map "location off", unsynced items on logout, "Assign Task: no SR on this route".

---

## 5. Online-only versus works offline

The manual states nothing about connectivity on pp. 1-22. "Off" uses the plan's code: Y = fully offline, P = cached read or action queued, N = network required. The recommended column is a requirement from CLAUDE.md #1, not from the manual.

| Screen | Manual says | Rec. | Behaviour to specify |
|---|---|---|---|
| S-01, S-02 install | n/a (local install) | Y | - |
| S-03 Login | not stated | N first, P after | First login on a device needs the server. Re-open with a cached session works offline; token refresh never blocks local reads (F-SYS-002). |
| S-04 Dashboard | not stated ("that day", "Live") | P | Last snapshot with "as of hh:mm"; refresh when online; no polling timer (docs/04). |
| S-05 Not Logged In / Not Uploaded | not stated | P | Cached list with "as of"; refresh online. |
| S-06 Drawer | not stated | Y | - |
| S-07 Leave list | not stated | P | Cached list plus queued applications shown as "not sent yet". |
| S-08 Leave apply | not stated | Y (queued) | Client uuid; sync later; server re-validates (G-21). |
| S-09 Final Submit pickers | not stated | P | Scope tree from the cached read model. |
| S-09 "Get Sales Data", S-10, S-11 Submit, S-12 | server check and success implied | N | Disabled offline with "needs internet"; cannot be queued (once per zone per day, first wins). |
| S-13 My Team | not stated (implied online) | P / N | Last cached fixes with age offline; map tiles need network (cached tiles optional). |
| S-14 Retailer map | not stated (implied online) | P / N | Outlets of the zone from the cached read model; tiles need network. |
| S-15, S-16 My Visit Plan | not stated | P | Cached day plan; Pending / Completed updated locally. |
| S-17 Visit Query | not stated | Y (queued) | Saved locally, outlet marked Completed locally, synced later. |
| S-18 Assign Task | not stated | Y (queued) | Assignee resolved from cached `route_assignment`; server can reject at sync (no SR on that day): surface in the sync-state badge. |
| S-19, S-20 Set Plan | not stated | P (queued) | Zones, routes, outlets from cache; plan queued. |
| S-21, S-22 Target Status | not stated | P | Cached; "as of". |
| S-23 Feedback | not stated; image upload implies network | Y (queued) | Text queued; image via the media queue, Wi-Fi preferred. |
| S-24 Logout | wipes everything | Y, but guarded | Blocked while anything is unsynced (G-01). |

---

## 6. Contradictions

### 6A. Manual versus the spec (`docs/01-13`, `docs/22`, `db/schema.sql`)

| # | Spec says | Manual says | Resolution |
|---|---|---|---|
| 1 | docs/08: "Logout wipes all local app data ... keep that behaviour". | Wipe is shown (S-24) with no unsynced check. The spec contradicts itself: CLAUDE.md #1 and #2 and docs/04 forbid losing queued records. | G-01: wipe only a reconciled device. |
| 2 | docs/08 My Team: "last synced fixes". | "Live location" (callout p15, AMO R-022). | G-10: keep last fix, show age, list as a deliberate change. |
| 3 | docs/08 Set Plan: outlets show "name, code, cluster, owner, phone". | Card shows name, "code \| address", "owner \| phone"; no cluster (p18). | G-17: address, falling back to cluster. |
| 4 | docs/08 Leave: "date(s), number of days". | One "Select Date" plus "Number of days" (p11); list shows "from to to". | G-12. |
| 5 | docs/08 Dashboard: "Lighter (pieces/box - confirm unit)". | "Sales (Lighter in Box)" (p5); the web tile says "Lighter in Pcs". | Resolved for the TSO: box. Keep `report_factor` (docs/13 Q8 part). |
| 6 | docs/10: Submit % web = ÷ target routes. | Web dashboard tile "Submit Status" divides by "Login successfully" (WEB p3). | G-02: web and app agree; correct docs/10 and SF-7. |
| 7 | docs/03 and docs/10: targets per category / brand / SKU. | Details rows are variant names (p19). | G-04. |
| 8 | docs/03: `zone` carries `dep_id`. | "Dep Name" on the lists; schema.sql `zone` has no `dep_id`. | G-08: fix schema.sql. |
| 9 | CLAUDE.md #8: Bangla-first. | English UI labels (S-03..S-24). | G-23. |
| 10 | docs/13 Q16: confirm that the DMO approves TSO leave. | Badge "DMO approval pending" (p10). | Consistent: the manual confirms the DMO as approver. |
| 11 | docs/08 header "Dashboard (territory, today)". | "Total Sales ... that day" (p5); no date picker, no refresh. | Consistent; add "as of" (G-05). |
| 12 | docs/08: "Darker themed app (as today)". | Dark navy UI (all screens). | Consistent. |

### 6B. Manual versus the plan drafts (`plan/lens-*.md`)

| # | Plan says | Manual says | Resolution |
|---|---|---|---|
| 1 | lens-data D-03: Submit % canonical = ÷ target routes. | TSO and web tiles are ÷ Login Count. | G-02. |
| 2 | lens-data D-07: till-date target on working days (default). | Till-date numbers fit calendar days (26/30). | G-03. |
| 3 | lens-config `cfg.target.achievement_pct_cap` = 1000. | Details print 1271.19%; summary prints 100%. | G-13. |
| 4 | lens-config `cfg.tso.visit_plan_max_outlets` = 30 (A). | No cap shown; routes hold a median 64 outlets. | G-17. |
| 5 | lens-config `cfg.leave.max_consecutive_days` = 30 (A). | No cap shown. | G-12. |
| 6 | lens-config `cfg.feedback.categories` = Suggestion, Complaint, Bug (A). | Only "Suggestion" shown. | G-22. |
| 7 | lens-config `cfg.survey.tso_visit_query_questions` allows yes/no and choice types. | The two questions take free text. | G-19. |
| 8 | lens-features F-SYS-019, F-SYS-020, F-SYS-021 are `all-app`. | TSO drawer has no Settings (p8). | G-24. |
| 9 | lens-features F-SYS-033: one role-aware app. | Three per-role APKs, side-loaded, launcher "ARON TSO" (p2-3). | G-25: flavours of one codebase. |
| 10 | lens-features F-TSO-019, F-ADM-022: "TSO issues OTP". | No OTP screen in the TSO app; the SR manual and web manual show the OTP generated when the SR taps login and read by the TSO on the web portal (WEB p46, R-087). | Not a TSO-app item; hand to the SR and Web deltas: the OTP is SR-initiated, TSO-read. |
| 11 | lens-features F-TSO-012: Retailer map "N (P with bundle outlets)". | Zone plus radius filter; no outlet markers shown. | Consistent; G-16 for density. |
| 12 | lens-data M-33 `CHECK (to_date >= from_date)`, `days > 0`. | Sample "2026-02-25 to 2026-02-25", "2 Days". | Valid under the CHECK; but do not derive `days` from the dates (G-12). |
| 13 | lens-config `cfg.day.final_submit_earliest_time` = 17:00 (A). | No time gate shown for Final Submit; web log sample carries a time of 14:26:05. | G-07: default none. |
| 14 | lens-config `cfg.app.default_locale` = `bn`. | TSO UI is English. | G-23: per-role value. |
| 15 | lens-config `cfg.kpi.submit_pct_denominator` note: web shows ÷ target_routes. | Web tile divides by "Login successfully". | G-02. |

### 6C. Inside the TSO manual (affects the build)

| # | Where | Conflict | Resolution used |
|---|---|---|---|
| 1 | U-01 p3 | Callout says "ARON SR" while the screen and icon say "ARON TSO". | Treat as copy-paste; the app is "ARON TSO". |
| 2 | U-04 p5 | On-screen "By Channel STD" and "CPR"; callout "By Channel Successful Call" and "Live CPR". | Screen titles win (the callout is web wording). G-14. |
| 3 | U-07 p6 | Red box on Segment chart; callout names the Brand chart. | Both charts specified separately. G-14. |
| 4 | U-09 p13 | The callout under the Success dialog describes the drawer. | Ignore; dialog text is on screen. G-26. |
| 5 | U-12 p8 | Callout "menu button at top right"; screens show top-left (top-right is logout). | Top-left. G-27. |
| 6 | U-14 p10 | "2026-02-25 to 2026-02-25" shows "2 Days". | `days` is typed, not derived. G-12. |
| 7 | U-19 p14 | Callout says the alert appears when "submit again"; the screenshot trigger is "Get Sales Data". | Check at Get Sales Data (G-06). |
| 8 | U-20 p15 | Menu "My Team" vs title "My Teams"; "Retailer" vs "Outlets". | Keep both as printed. G-16. |
| 9 | U-21 p16 | Callout 1 on the My Visit Plan page says "set" a plan. | Viewing only; setting is Set Plan. |
| 10 | U-24, U-29 p12, p19 | "All zones" and "all routes" callouts vs one-zone form and Territory / Zone cards. | G-15, G-13. |
| 11 | U-11 p6-7 | Card "4 Target Route / 1 Total Login" vs 5 and 6+ list rows. | Counts per route, rows per user-route. G-08. |
| 12 | p14 vs p16-18 | Outlet code "DHK-344-011" vs "2689479". | `outlet_code` is text; both occur. G-28. |
| 13 | p5, p6 | Same screen, y-axis 0-83% in six ticks. | Axis auto-scales; not a rule. G-14. |

### 6D. Against the other manuals (targeted search of the AMO, SR and Web inventories)

| # | TSO manual | Other manual | Resolution |
|---|---|---|---|
| 1 | "Sales (Lighter in Box)" (p5). | Web "Sales (Lighter in Pcs)" (WEB-S-03). | Two report units; `report_factor` (G-09). |
| 2 | "By Channel STD" (p5). | Web "By Channel Successful Call" (calls-based) and a separate "By Channel STD". | Different metrics; TSO tile is STD. G-14. |
| 3 | "Login Status" and "Bikroy Joma Status" (p6). | Web "Login/Submit Status" ("1 Login successfully", "0 Submitted successfully"); AMO "Sales-deposit % (deposits/logged in)". | Same formula in all three: submitted ÷ logged-in. "Bikroy Joma" = sales deposit = Sales Submit. G-02. |
| 4 | Final Submit: read-only "Sales Date", route cards, Submit (p13). | Web WEB-S-19: "Date of Data Entry" picker, per-route Status ("exist" / "--"), "Delete Section Data", back-dating blocked, "phone support". Web Final Submit Log (S-27): Done / Not Done. | The app is the day-of path; web owns back-dating and delete. G-07. |
| 5 | Zone picker (p12). | Web dashboard counts "Total Zone" and "Total Service Zone". | Confirm whether service zones exist and whether the TSO picker lists them (Section 3 row 18). |
| 6 | My Teams: zone, all SRs by route (p15). | AMO Team Location: pick one SR, map shows marker and name (AMO S-24 p33). | Different supervisor views; both on the same last-fix data. |
| 7 | Target Status: Territory and Zone cards, Details (p19). | AMO Team Performance: Zone and route cards, same toggle and table (AMO S-25, S-26 p34-36), cap 100% on cards vs uncapped details (AMO U-32). | Same pattern; the AMO inventory reaches the same inferences. Units are unlabelled in both. |
| 8 | Logout: "Log Out!" with "Your all app data will removed." (p21). | SR and AMO: "আপনি কি নিশ্চিত যে লগআউট করতে চান?" [হ্যাঁ / না]; wipe and unsynced handling unstated (SR F-41, AMO U-57). | Only the TSO states a wipe. G-01. |
| 9 | No Settings; English UI. | SR and AMO: Settings with language dropdown (default বাংলা), "PDA to Support", Log Out. | G-23, G-24. |
| 10 | No OTP screen. | SR p5-6: 4-digit OTP; the TSO reads it on the web "SR Device OTP" page (WEB p46). | OTP is not a TSO-app feature (6B row 10). |
| 11 | Date formats ISO and "MMMM D, YYYY" (p10, p16-18). | Web: ISO, but Sales Plan edit "04/12/2026" and Final Submit "2026/04/13" (WEB U-10). | Format per locale in the localization layer. G-27. |

### 6E. Corrections to the inventory (`manual-tso.md`)

No factual error found on the pages checked. Additions the inventory did not make: (1) on p7 the user sr334002 on Apsis Route 2 appears in both tabs, so "Not Uploaded" is a superset of "Not Logged In" (G-08); (2) the till-date targets on p19 fit a calendar-day pro-rata (G-03); (3) the item names on p19 are variant names (G-04); (4) p18's card line is "code \| address" and the real data has almost no address (G-17); (5) the web inventory shows the Submit Status tile divides by logged-in routes, so TSO-R-10's "denominator not confirmed" is resolved (G-02).

---

## Appendix A. Verdict for every inventory item

Columns: id, verdict, gap(s), note. A COVERED item names where the spec or plan covers it. IDs are those of `manual-tso.md`.

### Screens (TSO-S)

| ID | Verdict | Gap | Note |
|---|---|---|---|
| TSO-S-01 Install: file manager and prompt | PARTIAL | G-25 | Side-load, file naming, per-role APK not in spec. |
| TSO-S-02 Install complete, launcher icon | PARTIAL | G-25 | Launcher label "ARON TSO"; "ARON SR" callout error. |
| TSO-S-03 Login | COVERED | - | docs/08 login; forgot password and lockout: plan G-feat-34; same logins: G-feat-66. |
| TSO-S-04 Dashboard | CONTRADICTS | G-02, G-09, G-14, G-27 | Bikroy Joma tile vs plan D-03; tiles, charts, chrome partial. |
| TSO-S-05 Login & Bikroy Joma Status lists | PARTIAL | G-08 | Row unit, superset, Dep Name. |
| TSO-S-06 Navigation drawer | PARTIAL | G-24, G-27 | Seven entries covered; subtitles, groups, header, no Settings. |
| TSO-S-07 Leave Applications list | PARTIAL | G-12 | Range display, day count, badge vocabulary. |
| TSO-S-08 Leave Apply form | PARTIAL | G-12, G-21 | Single date plus typed days; no validation rules. |
| TSO-S-09 Final Submit selection | PARTIAL | G-15 | Cascade, enable state, "all zones" callout. |
| TSO-S-10 Already-submitted alert | PARTIAL | G-06, G-26 | Fires at Get Sales Data; verbatim Bangla text. |
| TSO-S-11 Final Submit routes and Submit | PARTIAL | G-06, G-07 | Preview read, Sales Date, no confirm. |
| TSO-S-12 Success dialog | PARTIAL | G-07, G-26 | Text; post-OK destination. |
| TSO-S-13 My Teams map | CONTRADICTS | G-10, G-11, G-16 | "Live" vs last fix; map provider. |
| TSO-S-14 Outlets (Retailer) map | PARTIAL | G-11, G-16 | Radius unit and default, density, markers. |
| TSO-S-15 My Visit Plan selector | COVERED | - | docs/08 My Visit Plan; date default not stated (assume today). |
| TSO-S-16 Visit Plan Outlets | PARTIAL | G-18, G-28 | Completed tap; phone and code formats. |
| TSO-S-17 Visit Query | PARTIAL | G-19 | Free text, bn labels, outcome of No. |
| TSO-S-18 Assign Task | PARTIAL | G-20 | Assignee, label, due date. |
| TSO-S-19 Set Plan selector | COVERED | - | docs/08 Set Plan. |
| TSO-S-20 Select Outlets and Set Plan | CONTRADICTS | G-17 | Spec says cluster, manual shows address; plan cap 30. |
| TSO-S-21 Target Status summary | CONTRADICTS | G-03, G-13 | Till-date basis; display caps. |
| TSO-S-22 Target Status details | CONTRADICTS | G-04, G-13 | Variant rows; uncapped % vs plan cap. |
| TSO-S-23 Create New Feedback | PARTIAL | G-22 | Categories, sub-menu, image rules. |
| TSO-S-24 Log Out! dialog | CONTRADICTS | G-01 | Wipe without unsynced guard. |

### Flows (TSO-FL)

| ID | Verdict | Gap | Note |
|---|---|---|---|
| TSO-FL-01 Install and first launch | PARTIAL | G-25 | Runbook, naming. |
| TSO-FL-02 Login to Dashboard | COVERED | - | Error alt: plan G-feat-34. |
| TSO-FL-03 Review territory performance | PARTIAL | G-09, G-14 | Includes the Bikroy Joma tile (G-02). |
| TSO-FL-04 Chase SRs and AMOs not logged in or uploaded | PARTIAL | G-08 | |
| TSO-FL-05 Open the menu and navigate | PARTIAL | G-27 | |
| TSO-FL-06 Apply for leave | PARTIAL | G-12, G-21 | |
| TSO-FL-07 Final Submit happy path | PARTIAL | G-06, G-07, G-15 | |
| TSO-FL-08 Final Submit repeat attempt | PARTIAL | G-06 | |
| TSO-FL-09 View live SR locations | CONTRADICTS | G-10 | |
| TSO-FL-10 Find retailers around the TSO | PARTIAL | G-16 | |
| TSO-FL-11 View plan, audit a pending outlet, no delegation | PARTIAL | G-18, G-19 | |
| TSO-FL-12 Visit query with delegation | PARTIAL | G-19, G-20 | |
| TSO-FL-13 Look at completed visits | PARTIAL | G-18 | |
| TSO-FL-14 Set a new visit plan | PARTIAL | G-17 | |
| TSO-FL-15 Check targets and achievements | CONTRADICTS | G-03, G-13 | Plan till-date default and cap. |
| TSO-FL-16 Submit feedback | PARTIAL | G-22 | |
| TSO-FL-17 Logout | CONTRADICTS | G-01 | |

### Rules (TSO-R)

| ID | Verdict | Gap | Note |
|---|---|---|---|
| TSO-R-01 Individually issued credentials | COVERED | - | docs/08, docs/01; G-feat-66. |
| TSO-R-02 Login leads to Dashboard | COVERED | - | |
| TSO-R-03 Username and password both needed | COVERED | - | Messages absent: section 4. |
| TSO-R-04 APK side-load, file-name pattern | PARTIAL | G-25 | |
| TSO-R-05 Territory and "that day" scope | COVERED | - | docs/08; server-side scope. |
| TSO-R-06 Four tiles, fixed units, one decimal | PARTIAL | G-09 | |
| TSO-R-07 Achievement ring formula | PARTIAL | G-09 | Day target undefined. |
| TSO-R-08 Strike Rate = successful calls ÷ target outlets | COVERED | - | docs/10 CPR. |
| TSO-R-09 Login % = total login ÷ target routes | COVERED | - | docs/08; AMO-route question in G-08. |
| TSO-R-10 Bikroy Joma % = submitted ÷ login count | CONTRADICTS | G-02 | docs/08 agrees; plan D-03 and docs/10 do not. |
| TSO-R-11 Lists scoped by territory and day | PARTIAL | G-08 | Row unit; superset. |
| TSO-R-12 Segment legend Value / Volume | PARTIAL | G-14 | |
| TSO-R-13 Drawer order, ">" vs "v" groups | PARTIAL | G-27 | |
| TSO-R-14 Leave types Casual, Sick, Earn | COVERED | - | docs/08; `cfg.leave.types`. |
| TSO-R-15 Four inputs required before Apply | PARTIAL | G-21 | No required-field rules or messages. |
| TSO-R-16 Single date, days default 0 | PARTIAL | G-12 | |
| TSO-R-17 DMO approval and badge | PARTIAL | G-12 | Status vocabulary. |
| TSO-R-18 ISO dates, Day / Days caption | PARTIAL | G-12 | |
| TSO-R-19 List shows range, reason, days, status; no type or balance | PARTIAL | G-12 | |
| TSO-R-20 Five levels before Get Sales Data | COVERED | - | docs/08. |
| TSO-R-21 Button grey until all five chosen | PARTIAL | G-15 | |
| TSO-R-22 Hierarchy order | COVERED | - | docs/03, docs/08. |
| TSO-R-23 "All zones" callout | PARTIAL | G-15 | |
| TSO-R-24 Submit after zone selection | COVERED | - | |
| TSO-R-25 Sales Date read-only, one Submit for all routes | PARTIAL | G-07 | |
| TSO-R-26 "FF: SR Not Set" routes listed | PARTIAL | G-07 | Block vs allow: G-feat-64. |
| TSO-R-27 Success dialog | PARTIAL | G-26 | Verbatim text. |
| TSO-R-28 One Final Submit per zone per day, alert | PARTIAL | G-06 | Alert timing and text. |
| TSO-R-29 My Team shows live SR location by route | CONTRADICTS | G-10 | |
| TSO-R-30 Retailer map by zone and radius | COVERED | - | docs/08; F-API-019. |
| TSO-R-31 Radius 50 / 100 / 300 | PARTIAL | G-16 | Unit, default. |
| TSO-R-32 Visit Plan: date + zone + route, Pending / Completed | COVERED | - | docs/08. |
| TSO-R-33 Visit Query for Pending outlets, as the SR's task | PARTIAL | G-18 | |
| TSO-R-34 Submit after answers | COVERED | - | |
| TSO-R-35 Delegate Yes / No, default No | PARTIAL | G-19 | |
| TSO-R-36 Task Type required, date, optional comment | PARTIAL | G-20 | |
| TSO-R-37 Read-only outlet info, later due date | PARTIAL | G-20 | |
| TSO-R-38 Set Plan flow, complete from My Visit Plan | COVERED | - | docs/08. |
| TSO-R-39 Multi-select outlets | COVERED | - | docs/08. |
| TSO-R-40 Two views, four categories, Territory and Zone, Details | COVERED | - | docs/08; callout "all routes": G-13. |
| TSO-R-41 Summary capped at 100% | CONTRADICTS | G-13 | Plan cap 1000. |
| TSO-R-42 Detail % uncapped, Remaining floor 0 | CONTRADICTS | G-13 | Plan cap 1000. |
| TSO-R-43 Till-date = pro-rated monthly | CONTRADICTS | G-03 | Plan default working days. |
| TSO-R-44 "A/B" = achievement / target | PARTIAL | G-13 | |
| TSO-R-45 Feedback: category first, optional image | COVERED | - | docs/08. |
| TSO-R-46 Logout confirm, wipe, no pending check | CONTRADICTS | G-01 | |
| TSO-R-47 Date display formats | MISSING | G-27 | Not stated anywhere. |
| TSO-R-48 Back button, Home FAB | MISSING | G-27 | Home FAB absent from spec and plan. |
| TSO-R-49 Phone shown as 10 digits | MISSING | G-28 | No normalisation rule. |
| TSO-R-50 Maps need location and Internet, Google-Maps style | MISSING | G-11 | No provider decision. |

### Messages (TSO-M)

| ID | Verdict | Gap | Note |
|---|---|---|---|
| TSO-M-01 "Do you want to install this app?" | COVERED | - | Android system dialog, not an app string. |
| TSO-M-02 "App installed." | COVERED | - | Android system dialog. |
| TSO-M-03 "Good day, <name>" | MISSING | G-27 | Greeting not in spec or plan. |
| TSO-M-04 "DMO approval pending" | COVERED | - | docs/08 quotes it; other statuses in G-12. |
| TSO-M-05 "Success" / "Final Submit Done Successfully..." | PARTIAL | G-26 | |
| TSO-M-06 Bangla "already submitted" alert | PARTIAL | G-06, G-26 | |
| TSO-M-07 "Log Out!" / "Your all app data will removed." | CONTRADICTS | G-01 | |
| TSO-M-08 Login prompts | PARTIAL | G-26 | |
| TSO-M-09 Leave form and list prompts | PARTIAL | G-26 | |
| TSO-M-10 Leave card text, Day / Days | PARTIAL | G-26 | |
| TSO-M-11 Final Submit prompts | PARTIAL | G-26 | |
| TSO-M-12 Final Submit routes strings | PARTIAL | G-26 | |
| TSO-M-13 My Visit Plan / Set Plan prompts | PARTIAL | G-26 | |
| TSO-M-14 Visit Plan Outlets labels | PARTIAL | G-26 | |
| TSO-M-15 Visit Query strings (Bangla questions) | PARTIAL | G-19 | docs/08 has English paraphrases. |
| TSO-M-16 Assign Task strings | PARTIAL | G-20 | |
| TSO-M-17 Select Outlets strings | PARTIAL | G-17 | |
| TSO-M-18 Target Status strings | PARTIAL | G-13 | |
| TSO-M-19 Detail table headers | PARTIAL | G-13 | |
| TSO-M-20 Feedback strings | PARTIAL | G-22 | |
| TSO-M-21 Dashboard titles and captions | PARTIAL | G-14 | |
| TSO-M-22 List labels (User Name, Route, Dep Name) | PARTIAL | G-08 | |
| TSO-M-23 Drawer titles and subtitles | PARTIAL | G-27 | |
| TSO-M-24 Map screen strings | PARTIAL | G-16 | |

### Entities (TSO-E)

| ID | Verdict | Gap | Note |
|---|---|---|---|
| TSO-E-01 User / credential | COVERED | - | `app_user`; password hash: M-40. |
| TSO-E-02 Org hierarchy | COVERED | - | schema wing..zone. |
| TSO-E-03 Territory | COVERED | - | Aggregates and targets. |
| TSO-E-04 Zone | PARTIAL | G-08 | `dep_id` missing in schema.sql. |
| TSO-E-05 Route | PARTIAL | G-08 | Route kind or suffix. |
| TSO-E-06 Depot | PARTIAL | G-08 | No depot attribute. |
| TSO-E-07 SR / AMO user | COVERED | - | `app_user`, `route_assignment`. |
| TSO-E-08 Daily login event | COVERED | - | `route_log`; M-32 `route_day`. |
| TSO-E-09 Bikroy Joma upload event | COVERED | - | `route_log.upload_*`; definition in G-02. |
| TSO-E-10 Daily sales by category | PARTIAL | G-09 | Day target. |
| TSO-E-11 Sales by channel | PARTIAL | G-14 | Facts lack channel (section 3 row 13). |
| TSO-E-12 Segment contribution | PARTIAL | G-14 | |
| TSO-E-13 Brand call/memo ratio | PARTIAL | G-14 | Q9; label level. |
| TSO-E-14 CPR / calls | COVERED | - | docs/10; `fact_daily_route`. |
| TSO-E-15 Leave application | PARTIAL | G-12 | Table missing in schema.sql; semantics. |
| TSO-E-16 Final Submit record | PARTIAL | G-07 | Route snapshot (M-34); preview read (G-06). |
| TSO-E-17 Sales date | PARTIAL | G-07 | Derivation. |
| TSO-E-18 SR live location | CONTRADICTS | G-10 | Spec stores last fix only. |
| TSO-E-19 Outlet master | PARTIAL | G-28 | Formats. |
| TSO-E-20 Visit plan | PARTIAL | G-18 | Table missing in schema.sql; M-35. |
| TSO-E-21 Visit query answer | PARTIAL | G-19 | |
| TSO-E-22 Task (delegated) | PARTIAL | G-20 | |
| TSO-E-23 Target | PARTIAL | G-03, G-04 | |
| TSO-E-24 Achievement | PARTIAL | G-03, G-13 | |
| TSO-E-25 Item / SKU / brand (variant rows) | MISSING | G-04 | No variant target level. |
| TSO-E-26 Feedback | PARTIAL | G-22 | Table missing in schema.sql; M-36. |
| TSO-E-27 Local app data / session | CONTRADICTS | G-01 | |
| TSO-E-28 Device location | COVERED | - | Single fix (F-TSO-012); permission handling in G-16. |
| TSO-E-29 App package | PARTIAL | G-25 | |

### A2. Field and control level

Fields and controls inside the screens above, judged one by one.

| ID | Field or control | Verdict | Gap |
|---|---|---|---|
| F-01 | Login: Username | COVERED | - |
| F-02 | Login: Password | COVERED | - |
| F-03 | Login: "Login" button | COVERED | - |
| F-04 | Login: absent controls (forgot password, remember me, language, version) | PARTIAL | G-24, G-25 |
| F-05 | Header: hamburger, greeting, logout icon | PARTIAL | G-27 |
| F-06 | Home FAB | MISSING | G-27 |
| F-07 | Tile "Sales (Cigarette)" | PARTIAL | G-09 |
| F-08 | Tile "Sales (Bidi)" | PARTIAL | G-09 |
| F-09 | Tile "Sales (Lighter in Box)" | PARTIAL | G-09 |
| F-10 | Tile "Sales (Match in Dozen)" | PARTIAL | G-09 |
| F-11 | Card "By Channel STD ▸" | PARTIAL | G-14 |
| F-12 | Card "CPR" (Target Outlet, Successful Calls, Strike Rate) | COVERED | - |
| F-13 | Card "By Segment Value Contribution" | PARTIAL | G-14 |
| F-14 | Card "By Brand Call/Memo Ratio" | PARTIAL | G-14 |
| F-15 | "Login Status" bar (Target Route, Total Login) | PARTIAL | G-08 |
| F-16 | "Bikroy Joma Status" bar (Login Count, Total Bikroy Joma) | CONTRADICTS | G-02 |
| F-17 | Tabs "Not Logged In" / "Not Uploaded" | PARTIAL | G-08 |
| F-18 | List row: name, User Name, Route, Dep Name | PARTIAL | G-08 |
| F-19 | Drawer entries 1-7: order, titles, subtitles | PARTIAL | G-27 |
| F-20 | Drawer groups: My Periphery (2), My Call (2) | COVERED | - |
| F-21 | Drawer: "My Feedback" chevron without sub-items | MISSING | G-22 |
| F-22 | Drawer header: role, name, logout icon | PARTIAL | G-27 |
| F-23 | Leave card: date range | PARTIAL | G-12 |
| F-24 | Leave card: Reason | COVERED | - |
| F-25 | Leave card: day count and caption | PARTIAL | G-12 |
| F-26 | Leave card: status badge | PARTIAL | G-12 |
| F-27 | "Apply For Leave" button | COVERED | - |
| F-28 | Leave Type dropdown (Casual, Sick, Earn) | COVERED | - |
| F-29 | Leave Date (single, calendar) | PARTIAL | G-12 |
| F-30 | Number of days (default 0) | PARTIAL | G-12 |
| F-31 | Leave Reason textarea | COVERED | - |
| F-32 | "Apply" button and absent messages | PARTIAL | G-21 |
| F-33 | Wing, Division, Territory, House, Zone dropdowns | PARTIAL | G-15 |
| F-34 | "Get Sales Data" (grey and green states) | PARTIAL | G-06, G-15 |
| F-35 | Already-submitted alert and "OK" | PARTIAL | G-06 |
| F-36 | "Sales Date:" read-only | PARTIAL | G-07 |
| F-37 | Route cards: name, "FF:" | PARTIAL | G-07, G-08 |
| F-38 | "Submit" button | PARTIAL | G-07 |
| F-39 | Success dialog and "OK" | PARTIAL | G-26 |
| F-40 | My Teams: Zone dropdown | COVERED | - |
| F-41 | My Teams: map controls (compass, my location, recentre) | MISSING | G-11 |
| F-42 | My Teams: SR marker and its tap | CONTRADICTS | G-10 |
| F-43 | Outlets: Zone dropdown | COVERED | - |
| F-44 | Outlets: Radius list 50 / 100 / 300 | PARTIAL | G-16 |
| F-45 | Outlets: map and outlet markers | PARTIAL | G-16 |
| F-46 | My Visit Plan: Select Date | COVERED | - |
| F-47 | My Visit Plan: Select Zone | COVERED | - |
| F-48 | My Visit Plan: Select Route | COVERED | - |
| F-49 | "Show Outlet" button | COVERED | - |
| F-50 | Tabs Pending / Completed | PARTIAL | G-18 |
| F-51 | Outlet card: name, Code, Owner, Contact, Channel, Cluster | PARTIAL | G-28 |
| F-52 | Card chevron and tap | PARTIAL | G-18 |
| F-53 | Visit Query: question 1 (free text, Bangla) | PARTIAL | G-19 |
| F-54 | Visit Query: question 2 (free text, Bangla) | PARTIAL | G-19 |
| F-55 | Delegate radio Yes / No, default No | PARTIAL | G-19 |
| F-56 | Visit Query "Submit" | PARTIAL | G-19 |
| F-57 | Assign Task: read-only outlet card | COVERED | - |
| F-58 | Assign Task: Task Type dropdown (unlabelled) | PARTIAL | G-20 |
| F-59 | Assign Task: date | PARTIAL | G-20 |
| F-60 | Assign Task: Comment | COVERED | - |
| F-61 | "Assign Task" button | PARTIAL | G-20 |
| F-62 | Set Plan: Select Date, Zone, Route | COVERED | - |
| F-63 | Select Outlets card and selection ring | CONTRADICTS | G-17 |
| F-64 | "Set Plan" button (no confirmation) | PARTIAL | G-17 |
| F-65 | Target Status tabs Monthly / Till Date | COVERED | - |
| F-66 | Territory card rows, "A/B", % | CONTRADICTS | G-13 |
| F-67 | Zone card rows | CONTRADICTS | G-13 |
| F-68 | "Details ->" link | COVERED | - |
| F-69 | Till-date values | CONTRADICTS | G-03 |
| F-70 | Detail table columns | PARTIAL | G-13 |
| F-71 | Detail table item rows (variant level) | MISSING | G-04 |
| F-72 | Feedback Category dropdown | PARTIAL | G-22 |
| F-73 | Feedback Title | COVERED | - |
| F-74 | Feedback Descriptions | COVERED | - |
| F-75 | "Browse Gallery" and thumbnail | PARTIAL | G-22 |
| F-76 | Feedback "Save" (no success message) | PARTIAL | G-22 |
| F-77 | Logout dialog text and buttons | CONTRADICTS | G-01 |

---

## Appendix B. PDF spot-check log (`eefcbecb-TSO_App_User_Manual.pdf`)

| Page | What was checked | Result |
|---|---|---|
| 5 | Sales tiles (160.0 / 92.5%, 0.0 / 0.0%), "Lighter in Box", "Match in Dozen"; By Channel STD, CPR 44 / 2 / 4.5%, Segment chart; callout names | Matches inventory. Callout names differ from titles (G-14). |
| 6 | Segment and Brand charts empty; "Login & Bikroy Joma Status": 25%, 4 / 1, 0%, 1 / 0 | Matches. |
| 7 | Not Logged In (5 rows), Not Uploaded (6 rows) | Matches. New: sr334002 / Apsis Route 2 appears in both tabs (G-08). |
| 10, 11 | Leave list (3 cards, "2 Days" for 2026-02-25 to 2026-02-25), form (Casual, Sick, Earn; one Date; Number of days 0) | Matches. |
| 13 | Final Submit routes, "Sales Date: 2026-04-26", "FF: SR Not Set", Success dialog | Matches. |
| 14 | Final Submit selection and the Bangla alert over the form | Matches; trigger is "Get Sales Data". |
| 15 | My Teams, Outlets (Zone, Radius 50 / 100 / 300), 3D map | Matches; Google-Maps style (G-11). |
| 16 | My Visit Plan selector, Visit Plan Outlets card | Matches. |
| 17 | Visit Query, Assign Task | Matches. |
| 18 | Set Plan, Select Outlets ("2689479 \| Jobbar Tower Lake Par") | Matches; no cluster on the card (G-17). |
| 19 | Target Status summary and details | Matches; arithmetic for G-03 and item names for G-04 taken from this page. |
| 20, 21 | Create New Feedback; logout dialog | Matches. |

Not re-read: pp. 1-4, 8, 9, 12, 22 (cover, install, login, drawer repeats, empty Final Submit form, closing).

---

## Appendix C. What only the sponsor's screenshots or the live app can settle

1. A populated Segment chart and Brand chart (type, label level, whether "Maxim - Platinum Series" is a brand or a series) and what the "▸" on "By Channel STD" opens (G-14).
2. The My Feedback sub-menu (the drawer chevron), the Feedback Category options, and whether past feedback is listed (G-22).
3. Task Type options on Assign Task, default due date, and what Submit does when "delegate" is No (G-19, G-20).
4. Leave statuses beyond "DMO approval pending" (approved, rejected, cancelled labels), whether multi-day applications are allowed, any validation message (G-12, G-21).
5. Whether Final Submit is blocked, warned or allowed with "SR Not Set" or no-data routes, and whether it is time-gated (G-07).
6. How "live" is produced: continuous tracking by the SR app, per-visit fixes, or per-sync fixes (G-10), and whether the map is 3D by choice (G-11).
7. The day-target basis behind the Dashboard ring (G-09) and the date a till-date screenshot was taken (G-03, to confirm the calendar-day inference).
8. Whether AMO routes and AMO users belong in Target Route, Total Login and the lists (G-08), and whether service zones appear in the Zone picker (section 3 row 18).
9. Behaviour offline: login without signal, dashboard with no data, any "no internet" message (G-05); none is in the manual.
10. Device list and Android versions on the TSO fleet (G-29).
