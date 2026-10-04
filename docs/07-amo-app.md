# 07 — AMO App

The Area Marketing Officer works one zone (login = the zone, e.g. `amo5001`). The AMO coaches SRs, verifies outlet requests, and can also sell. Same offline/sync/geo rules as the SR app (`docs/04`, `docs/05`).

## Home

Header `amo5001 (amo5001)` + `<Zone>AMO, <date>`. Tiles: Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation (badge = pending SR requests), Live Dashboard, SR Stock, Outlet (badge), Stock, Sale, Memo, Summary, Sales Submit, Astha, Report, Settings.
Bottom KPI tiles (month to date): Today's target (done/total today), Total call target, Control-call target, Joint-call target.

## Attendance

Shows reverse-geocoded current address + refresh. Press-and-hold green = check-in; check-out (red) only from 5 pm; then buttons disable ("Already Checked-In/Out"). → `attendance`.

## Control Call (the AMO's own outlet visit)

- Select route + outlet (alphabet filter). If the AMO is outside the outlet radius → "AMO not within retailer range" → Manual Override (outlet photo, which updates location) or Refresh.
- Menu per outlet: Sale | SR Perf. Assessment | Survey.
- **SR Perf. Assessment** → `distribution_check`:
  - Distribution performance: tick brands present (or OOS) — Maxim, Avon, ARIS, Marise, Black Diamond, Sunmoon, Supreme, Special Abul Bidi, Existing Abul Bidi/42 No., Ananda Bidi, Abul Bidi Gold, Abul Bidi Style, Aster, Flame Box, Salmon.
  - OOS performance: tick brands that are OOS.
  - POSM: sticker/banner present? Yes/No. Save.

## Joint Call (AMO accompanies an SR)

- Select route + outlet; same geo gate. "SR call assessment" rubric, 1–5 stars each (1 low, 5 high) → `call_assessment(kind=joint_call)`:
  - 5-step sales call: Summarise the situation [OHS count, OOS, product-quality check, opportunity & issue]; Describe the idea [SOQ requirement, present offer, campaign communication]; Explain how it works [offer modality, retailer business benefit]; (steps 4–5 …).
  - Relationship assessment [greeting, knows retailer name, cordiality].
  - Service quality assessment [timely visit, correct memo & record keeping, resolving complaints].
  - Save → success.

## Sale (AMO can sell)

Same as the SR sale flow (`docs/06`): SKU quantities → review → optional credit + partial payment → QC → print. Plus "View previous sale data" with a date picker. Memo menu: Print | Edit | Mark paid. Summary: per-SKU memo count, qty, value, discount, discounted value, return qty (issue − sold); per-category totals (Cigarette, Bidi, Lighter, Match); grand total; Print.

## Team

- **Team Location:** SR list → live locations on a map. (Uses the SRs' last synced fixes; not continuous tracking — battery.)
- **Team Performance:** tabs Monthly Target | Till Date Target. Zone card + each route card, 4 category bars (Cigarette, Bidi, Lighter, Match: achieved/target, %; green/amber/red). Details → brand table (Item, Target, Achievement, Remaining, %).
- **Task Delegation:** Assigned Tasks list (outlet, text, status, completion date); "+" → assign (route/section, outlet, task type, due date, description) → Save. → `task`.
- **Live Dashboard:** zone + route filters; Sales (total memos, total taka); Live strike rate = successful calls / target outlets; Geo-fencing status (target outlets, geo-validated, photo-validated, total visited, geo %); Login & submit status (login % = logged-in routes / target; submit % = submitted / logged in).
- **SR Stock:** SR list (code, route, phone, total outlets) → lifted stock by SKU + total.

## Outlet (AMO verification + own operations)

- **Verify SR requests:** New outlet verification, Outlet closure verification, Outlet info-change verification (badges). On verify, the AMO also sets **Sub-Channel + Geo Classification**, can re-capture GEO + photo → Cancel (reject) | Save (approve). → updates `outlet_change_request.status`, then web approval.
- **AMO own ops:** New shop; Permanent close; Info change; **Update Base** (base-location recalibration: route + outlet → confirm → outlet photo → pick exact point on the map → Confirm).

## Sales Submit (AMO)

Device-vs-server counts: Outlet, Sale, Stock, QC, Promotion, Distribution & OOS performance, Price compliance, Joint call, Survey. Sync → then Sales Submit (warns on outstanding dues).

## Astha & Report

- **Astha:** as the SR app but with a route selector (the AMO covers several routes).
- **Report:** STD Memo Report (date range, per-route STD by category); Sales summary till now (route list with CPR + total memos → route detail: CPR, total memo, brand table Target[fractional]/Sales/%/Memo). Guard divide-by-zero on %.
