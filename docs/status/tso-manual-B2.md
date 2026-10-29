# Lane B2 backend-analytics: status

Build contract: `docs/requests/tso-manual-build-2026-10.md` (s1 D-TSO-MAN-05..12, 39; s4.2, s4.3; s5 B2; s9 R-F-01, 13, 15, 18, 24).
Branch `tso-man/B2`, base `95a35b7d` (contract 1.10.0, V0130-V0140, shared/rules primitives, KPI corpus). Nothing pushed, no PR.

## Step 1: calendar and targets (2026-10-29)

### Done

- **WorkingCalendarRepo** (`backend/platform/.../WorkingCalendarRepo.kt`, new): loads a month's live `app.calendar_holiday`
  declarations of every level, the `cfg.calendar.weekend_days` rows of wings and divisions valid at an instant, and the global
  weekend; `WorkingCalendars.at(path)` resolves a node with `WorkingDays.resolve` (shared/rules), `workingDays(path)` is
  `WorkingDays.month`. Also `zonePaths`, `pathOf(level, id)`, `commonPath` (lowest common ancestor) and `marks`.
- **CR-6 global-only bug fixed** (D-TSO-MAN-08):
  - `NationalDashboard.sales`: W at the lowest common node of the effective scope (`scopePath`), with the node's weekend and
    path declarations (`workingDaysAt`). The legacy `WorkingCalendar.workingDays` (global, kept for its test) now delegates to
    the one count.
  - `NationalMaps.routeMap`: W at the route's zone (WorkingDays call only, one line).
  - `AdminCalendar` working-days page: still the global node (contract `getWorkingDays`: only global declarations), now counted
    by `WorkingDays` over `WorkingCalendarRepo.marks` (WorkingDays call only).
- **TargetAchievementApi** (`backend/analytics/.../TargetAchievementApi.kt`, new): `GET /v1/dashboards/targets`
  (`getTargetAchievement`), month, level/node_id, product_level, children=zone; item-sum till-date (R-F-01); basis switch by
  role; per-node e and n with the n = 0 null rule per node (R-F-15, R-F-24); `tilldate_target_qty` "0.000" at n = 0; Decimal3
  and `achievement_pct` kept for the AMO app; `children` absent unless asked (never null).
- **DailyTrackingApi**: `tilldate_target_achievement_pct` filled (was always null), see D-B2-13.
- **Hot files (append only):** `Wiring.kt` one line (`// tso-manual B2`); `AuthzAuditTest` flips `/v1/dashboards/targets` from
  404 to the dashboard readers plus AMO, plus one IDOR probe per node level; `scope-cases.txt` one line
  (`TargetAchievementTest#aNodeOutsideTheReachIs403AndTheZoneCardsComeFromTheReach`).
- **Tests (new):** `TargetAchievementTest` (every s4.3 fixture through the API's SQL: cards 33064 / 8871 / 81 / 137, card % 3 /
  1 / 100 / 53 and 3 / 0 / 85 / 42, bands, details 35.00 % ... 104.94 %, remaining 0, basis switch 32799 / 33114 (never 33115),
  AMO key, per-zone e/n, a zone with n = 0 beside its territory, house node, R-F-18 400s, 403 ERR_OUT_OF_SCOPE outside the
  reach, route-only caller, HTTP shape and roles), `WorkingDaysScopeTest` (s4.2 working-days fixture step by step: 26, 25,
  zone 24 / territory 25, 26; division and wing weekends; national cards at the LCA; route map at the zone),
  `WorkingDaysViewParityTest` (`dw.v_calendar` / `v_calendar_month` == WorkingDays for the global node, 365 dates and 12
  months), `DailyTrackingTillDateTest`. Fixture `backend/analytics/src/test/resources/target_status.sql` (new).

### Decisions (the contract was silent; smallest correct choice)

- **D-B2-01 Weekend at a node.** `cfg.calendar.weekend_days` allows global, wing and division: the division's row valid at the
  instant, else the wing's, else the global value. The global value comes from the injected `ServerConfig` when there is one
  (DbServerConfig in production, the test overrides of NationalDashboardTest), else from the database (global row, else the
  registry default). A malformed scoped value is skipped (the next level decides); a malformed global value is Friday.
- **D-B2-02 National cards' node.** A national caller without a geography filter counts at the global node, whatever zones
  exist (so a division's emergency day never moves the national W, DataFlowTraceHolidayTest). Otherwise the LCA of the zones
  the effective scope (filter cut to the reach) selects: a territory filter whose territory has one zone counts at that zone.
- **D-B2-03 Route map.** W at the route's current zone (D-F1-18 left the route map's node open).
- **D-B2-04 Admin calendar** stays the global node, as its contract operation says; only the counting moved to WorkingDays.
- **D-B2-05 Target rows.** Default `product_level` = category (A1 asks with it). Item = variant; at `product_level=sku` a SKU row
  is its own item. Category rows list every catalogue category (the card always has its four bars, a category without target
  prints "—"); brand, variant and SKU rows list the products with T > 0 or A > 0. Order: the brief's category order, then
  segment, brand, variant and SKU by `sort`, name, id (catalogue order, D-TSO-MAN-40).
- **D-B2-06 Placement.** T and A of a route land on its current zone (`dw.dim_geo`), not the as-was zone, so a route moved
  mid-month never splits its target from its sales.
- **D-B2-07 Basis by role.** AMO reads `cfg.kpi.tilldate_basis.amo_team`; every other caller (TSO and the web readers) reads
  `cfg.kpi.tilldate_basis.tso`. An unknown value is calendar_days (TillDateBasis.fromWire).
- **D-B2-08 Roles.** The dashboard readers (TSO, DMO, WM, NATIONAL, ANALYST, ADMIN, SUPERADMIN) plus AMO (docs/24 s8.5 "R (app
  home)"). SR (targets ride its bundle), SUPPORT (sync health only) and BRAND (no brand-filtered target read yet; the portal
  guard refuses it too) get 403.
- **D-B2-09 Node path for the working basis.** A wing, division, territory or zone node chosen alone counts at its own path (s4.2
  "zone 24, territory 25"). A house, a route-only caller's routes and a caller's several top nodes read together count at the
  LCA of the zones they cover.
- **D-B2-10 Route-only caller.** Its node is its routes (route-keyed, D-BC-DASH-ROUTE-KEYED-01); `children=zone` is allowed (a
  route is below a territory, R-F-18) and gives the zones of its routes, each with only the caller's routes.
- **D-B2-11** A negative month achievement (only a data error could make one) is shown as 0: the contract's member is >= 0.
- **D-B2-12 Decimal3 members** carry the same integers ("40999.000"); `achievement_pct` is the same Percent.bp with two
  decimals (A / T, monthly).
- **D-B2-13 Daily Tracking till-date %.** The route's cigarette STD (sticks of sale lines, 1st..the tracked date) against the sum
  of its cigarette items' till-date targets on the `.tso` basis (working days at the route's zone), one Percent.bp, two
  decimals; null without a cigarette target that month or when n = 0. Cigarette only because different units are never added
  (D-TSO-MAN-11) and STD is cigarette sticks (docs/10, the By Channel STD card). The buckets stay on CPR (unchanged): see ask 1.
- **D-B2-14 Test fixtures** write `dw.agg_daily_route_sku` directly (the aggregator's output the API reads); a free quantity on
  the same rows proves only `sold_qty_base` counts. The sale/free split of the aggregator itself is AggregationTest's.
- **D-B2-15 Mounting.** `TargetAchievementDeps.install(route)` so the Wiring.kt change is one appended line with fully qualified
  names and no import edit (hot-file rule).

### Deviations

- None from the owned-file list. `NationalDashboardTest` (existing, not edited) still calls `WorkingCalendar.workingDays`, which
  is kept as a global delegate for that reason.

### Asks

1. **Lead:** Daily Tracking buckets (>= 100, 90-100, 80-90, < 80) still bucket CPR (successful calls / target outlets), as built
   when targets were deferred; docs/09 says "% of sales & memo targets". Switch the bucket to the new till-date % (cigarette
   STD) or keep CPR? Default kept: CPR.
2. **F1 ask 19 (optional, NationalDto `Int?` -> `Long?`)**: deferred to step 2, where the national cards move to Percent.bp.

### Verify (this worktree, local laptop, JDK 21, UTC, local PostgreSQL 16; CI not run, nothing pushed)

| Command (through gl.sh) | Result |
|---|---|
| `:backend:analytics:compileKotlin :backend:masterdata:compileKotlin :backend:app:compileKotlin` | BUILD SUCCESSFUL |
| `:backend:analytics:test --tests TargetAchievementTest, WorkingDaysScopeTest, WorkingDaysViewParityTest, DailyTrackingTillDateTest, NationalDashboardTest, NationalMapsTest, DailyTrackingTest, NationalDashboardApiTest` | 61 tests, 2 failed (TargetAchievementTest: a future month gave e = 1; fixed in `8e9cddf0`: e/n run against today's business date); every other class green: NationalDashboardTest 22, NationalMapsTest 7, DailyTrackingTest 12, NationalDashboardApiTest 5, WorkingDaysScopeTest 2, WorkingDaysViewParityTest 1, DailyTrackingTillDateTest 3 |
| `:backend:analytics:test --tests TargetAchievementTest` (after the fix) | BUILD SUCCESSFUL, 9 tests, 0 failures |
| `:backend:masterdata:test --tests CalendarWorkingDaysTest :backend:app:test --tests AuthzAuditTest, RouteInventoryTest, ContractConformanceTest, ScopeLeakAcrossEndpointsTest, DataFlowTraceHolidayTest` | BUILD SUCCESSFUL: CalendarWorkingDaysTest 2, AuthzAuditTest 4, RouteInventoryTest 9, ContractConformanceTest 6 (223 operations, every one with a 2xx; the targets answer validates against the contract), ScopeLeakAcrossEndpointsTest 7 (targets judged by the national control; scoped callers 200 own, 403 other node), DataFlowTraceHolidayTest 3 (a division off day still leaves the national W unchanged) |

AuthzAuditTest report for the route: ROLE allowed 200 for TSO, DMO, WM, NATIONAL, ANALYST, ADMIN, SUPERADMIN and AMO; SR and
SUPPORT refused 403; IDOR node zone/territory/division of the other division refused 403 ERR_OUT_OF_SCOPE for AMO, TSO and
DMO; no FINDING or QUESTION. Not run: the full `:backend:analytics:test` and `:backend:app:test` suites (targeted filters as the
step asks). No Android screen in this step, so no screenshot goldens are needed.

### Next step (step 2 of the lane)

Done in step 2 (below).

## Step 2: national cards and parity (2026-10-29)

Commits `aaf089d8` (national cards), `8dc2fe47` (summary, autoSubmit removed), `176876d6` (new tests, docs/10), `7d26582d`
(existing test expectations), `19215d05` (final submit through the zone-day projection), plus this status file. Base for the
step: `3b21a141`.

### Done

- **TSO on every national op** (s5 B2 rule): the national cards already answered TSO (DASHBOARD_ROLES); `NationalSql` now reads a
  route-only TSO (no zone in reach, route assignments) by `g.route_id` (D-BC-DASH-ROUTE-KEYED-01) on every card, the maps' pins and
  `filter-options` (its routes' zones). `TsoDashboardParityTest#theTsoRoleGetsEveryNationalCardTheAppReadsOverHttp`: territory,
  route-only and house TSOs get 200 on sales, cpr, channel-std, channel-calls, brand-cpr, login-submit, segment-share, geo-fencing,
  final-submit, login-submit/users and filter-options.
- **NationalSalesCard** (DASH-03/04, D-TSO-MAN-08/10, R-F-13): `monthly_target_qty` T sent; `achievement_pct_bp` =
  `Percent.bp(A x W, T)` once from the integers (never A over the rounded daily target), null when W = 0, T null or T = 0;
  `daily_target` = round_half_up(1000 T / W) thousandths as Decimal3, `remaining` = max(daily - 1000 A, 0); W = 0 gives null
  daily, remaining and ring.
- **NationalLoginSubmit.submit_of_target_pct_bp** = submitted / target_routes (D-TSO-MAN-07); `submit_pct_bp` (/ successful
  login) kept for the web brief. docs/10 line 26 corrected.
- **New ops** (contract 1.10.0, mounted in `nationalDashboardRoutes`, no Wiring change): `queryNationalBrandCpr`
  (`cfg.kpi.brand_cpr_denominator.tso`, both ratios and both denominators), `queryNationalSegmentShare`,
  `queryNationalLoginSubmitUsers` (tab required, D-TSO-MAN-12 order, `truncated` at 2000, `route_count` counts every route; F1 ask 11).
- **One national route set** `NATIONAL_ROUTE_SET` (normal SR route-days, planned, not excused) for target routes, logins, Bikroy
  Joma, CPR, Brand CPR, Geo Fencing and the service zones of Final Submit, in the national cards and the summary.
- **Summary** (`DashboardApi`, D-TSO-MAN-09, CR-7): `by_category` qty_base and qty_entered are the sold quantity of sale lines only;
  login, CPR, geo (completed calls) and final-submit KPIs of the node and of every child come from the route set and the zone-day
  Final Submit (`setFigures`), so summary == national on the parity day for every caller kind.
- **D-TSO-MAN-30**: `DashboardSummaries.autoSubmit` removed; `rebuildZoneDay` sets `final_submitted_at` from the first live
  `app.final_submit` row only, `auto_submitted` false. The national Final Submit card and the summary both count a service zone-day
  as submitted by that projection (`zoneSubmittedSql`): a `via = 'job'` row counts, a reopened one not, a stamp without a live row
  is cleared by the next rebuild (D-B2-18). Before, the summary used the route-day states (`agg_daily_zone.final_submitted`).
- `pctBp` (national) and `pct` (summary) now go through F3 `Percent.bp` (same values, one formula).
- `scope-cases.txt`: one line per new route (appended under `# tso-manual B2`).
- **Tests (new)**, fixture `tso_dashboard_day.sql` (2026-10-25, aggregated by the real worker): `TsoDashboardParityTest` (traps
  present; summary == national for sales, CPR, login, submit, geo and final submit for national, territory, two zone, route-only,
  house and empty-plan callers; Not Logged In = target - successful_login, Not Uploaded = successful_login - submitted; children add
  up; TSO HTTP 200s), `NationalExactPctTest` (s4.2 sales fixtures 40 / 1.7 K / 2.4 %, 3.0 K / 1.0 K / 287.6 %, 0.0 / 4.0 / 0.0 %,
  7.0; exact 34286 bp vs 34247 from the rounded target; W = 0 prints "—"; no target; zone W 24 vs territory 25; login 40 % /
  Bikroy 20 %; CPR 2.5 %; geo 4 of 4), `BrandCprTest` (67 % / 33 %, the switch 168 / 84 bp, route set, product filter,
  route-only, BRAND 403, HTTP scope), `SegmentShareTest` (7500 / 2500 bp, volume 9868 / 132 within sticks, three unit families
  never added, brand scope, HTTP scope), `LoginSubmitUsersTest` (fan-out and order of screenshot 8, stand-in SR, vacant "SR Not
  Set", excluded AMOs, 2001 rows -> 2000 + truncated with route_count 21, 2000 -> false, tab 400, BRAND 403, HTTP scope),
  `FinalSubmitPctUnifiedTest` (one formula for card and summary: web row, reopened row, job row, route-only, no service zone; a
  stamp without a live row is cleared by the rebuild; the worker stamps nothing; the zone-day time is the live row's).

### Decisions

- **D-B2-16 Geo on the national route set.** The national Geo Fencing card and the summary's `geo_valid_pct` both count completed
  SR calls (an outcome other than abandoned / not reached) of the national route set. Before: the card counted completed calls of
  every route in scope, the summary every visit (abandoned included, against K-09 of docs/16). D-TSO-MAN-09 puts geo on the route
  set; the parity day's abandoned visit and off-set routes prove it. The FF Geo pins and the geo-validation page are unchanged.
- **D-B2-17 Summary members on the route set**: target_routes, logged_in_routes, sales_submitted_routes (successful login AND
  `sales_submitted_at`, the card's rule; was the day_state), target_outlets, successful_calls, geo_valid_pct and the zone members.
  visited_outlets, visits-based force/mock/suspicious, memos and value stay over every route (no national card shows them).
- **D-B2-18 Final submit**: zones submitted / service zone-days (a route-day of the route set, keyed by `agg_daily_route.zone_id`),
  where "submitted" is the zone-day projection `dw.agg_daily_zone.final_submitted_at`, rebuilt only from the live rows (`reopened_at`
  NULL, any `via`). Read through the projection, not `app.final_submit` directly: the national operations read summary tables only
  (contract), and the figure then moves with the aggregator like every other card. A first build read the rows directly; the full
  app suite showed why that is wrong: `ScopeLeakAcrossEndpointsTest` (house TSO and seeded-leak cases) compares reads taken before
  and after its probe's Final Submit, and a direct read (plus the 5 s summary cache) let two views of one zone disagree (1 vs 0).
  This relies on a submit, a reopen and the midnight job dirtying the zone-day (through its route-days: the existing submit path does;
  D-TSO-MAN-29/30 require it of B3's reopen and job; ask 8).
- **D-B2-19 Route-keyed national reach** only for a caller with no zone and some routes; a caller with zones reads its zones (cover
  routes outside them are not a dashboard scope, as in the summary), so the two reads keep one scope.
- **D-B2-20 Brand CPR**: brands with outlets_bought > 0 (max 100); the product filter narrows it and its successful calls (the CPR
  card's product rule); the `.tso` key for every caller; an unknown value is successful_calls; BRAND users 403 (the denominators
  have no brand dimension; fail closed).
- **D-B2-21 Segment share**: every route in scope (like the sales card), sale lines; segments with value or quantity > 0 (max 50),
  value descending then code; families listed stick, piece, dozen when present; a BRAND user's brand scope applies.
- **D-B2-22 Login / Submit users**: route-day SR = `acting_user_id`, else `assigned_user_id`; AMO coverage = an active AMO's
  `user_scope` row valid on the date at national, the zone's current wing, division, territory, house or the zone; Dep Name = the
  zone name (D-TSO-MAN-12), not `app.zone.dep_name`; ties by username in the database collation; 400 `query.tab` after the role check.
- **D-B2-23 F1 ask 19 declined** (optional): `NationalDto` keeps `Int?` for the ten `*_bp` members; values stay valid against
  int64 and moving to `Long?` would break Int-literal assertions in tests B2 does not own.

### Deviations (each marked `tso-manual B2`)

1. `AggregationWorker.kt` (not B2-owned): the call of the removed `DashboardSummaries.autoSubmit` (and its `today`) deleted, a
   comment left. The contract orders the function removed; the call could not stay.
2. Existing tests B2 does not own, whose expectations pinned the replaced behaviour: `NationalDashboardTest#theMidnightJob...`
   (the job's `via = 'job'` row counts after the worker's pass, the worker stamps nothing), `DashboardTest#nationalFiguresEqualTheHandComputedControlTotals`
   (geo 60.0 -> 75.0; zones final-submitted 1 -> 0, seed_day.sql has no `app.final_submit` row),
   `GeoValidationTest#nationalFiguresEqualTheHandComputedOnes` (the summary's geo is 25.0, no longer equal to the page's 20.0).
3. Behaviour change on the web: the national Geo Fencing card counts the route set only (D-B2-16); the summary's KPIs move as above.

### Asks

1. **Platform owner / lead:** add `Pattern("POST", "/v1/dashboards/national/segment-share")` to `PortalLevels.BRAND_DASHBOARD`
   (it has a brand dimension; the service already cuts BRAND users to their brands). Until then the guard refuses BRAND (pinned in
   `SegmentShareTest`). Brand CPR and the users list stay refused by design.
2. **F1 (1.10.x):** `CategoryVolume.qty_entered` still says "Sold plus free, like qty_base"; it is sold only now (D-TSO-MAN-09).
   `monthly_target_qty` and `submit_of_target_pct_bp` are always sent now (F1 ask 5: make them required).
3. **F2 (1.10.x):** `dw.agg_daily_zone.auto_submitted` is always false now (the dw-only stamp is retired): reword or deprecate the
   column comment (F2's own note in tso-manual-F2.md).
4. **Lead (OpsApi owner):** `OpsApi` login-submit `zones[]` and sync-health `by_zone` show `final_submitted` from route-day states
   (`agg_daily_zone.final_submitted` / `v_route_day.zone_final_submitted`), not the Final Submit rows of D-TSO-MAN-30 (booleans, not
   a %); `zoneSubmittedSql` (NationalDashboard.kt) is the one rule to reuse.
5. **Lead:** the route map's `target_vs_achievement` (NationalMaps, where B2 owns the WorkingDays call only) still divides by the
   rounded daily target (`ratioBp`); the exact rule of the sales card could apply there too.
6. **W1:** the web summary figures move with D-B2-16/17 (geo on completed calls, route-set login and CPR, final submit from the Final Submit rows); show
   the "list cut, narrow the filter" note when `truncated` (F1 ask 11).
7. Step 1 ask 1 (Daily Tracking buckets on CPR or the till-date %) is still open.
8. **B3:** `POST /v1/day/reopen` and `AutoFinalSubmitJob` must leave the zone-day dirty (`zone_day_agg`, or its route-days) in the
   same transaction, as D-TSO-MAN-29 says for reopen; otherwise the card shows the old state until the next rebuild of that day.
   A test on B3's side: submit by the job, run the worker, `queryNationalFinalSubmit` counts the zone.

No Android screen in this step: no screenshot goldens are needed.

### Verify (this worktree, local laptop, JDK 21, UTC, local PostgreSQL 16; CI not run, nothing pushed)

| Command (through gl.sh) | Head | Result |
|---|---|---|
| `:backend:analytics:test --tests` the six new classes | `aaf089d8` + tests | 28 tests, 1 failed: `SegmentShareTest` BRAND over HTTP was 403, not 200 (the platform guard `PortalLevels.BRAND_DASHBOARD`, ask 1); the test now pins 403 |
| `:backend:analytics:test` NationalDashboardTest, DashboardTest, GeoValidationTest, RouteKeyedDashboardTest, NationalDashboardApiTest, AsWasReadTest, DailyTrackingTest, OpsApiTest, SegmentShareTest, NationalMapsTest, AggregationTest | same | 100 tests, 3 failed, each pinning the replaced behaviour (midnight stamp; summary geo 60.0; summary geo == page): updated, deviation 2 |
| Full `:backend:analytics:test :backend:app:test --continue` | `7d26582d` | analytics 50 classes, 295 tests, 0 failures; app 89 classes, 464 tests, 1 failure: `ScopeLeakAcrossEndpointsTest#aHouseScopedTsoReadsItsOwnHouseByDefaultAndNeverTheSiblingHouse` (final submit 1 vs 0 between two views: the direct `app.final_submit` read, D-B2-18) |
| Affected classes after the projection read: analytics FinalSubmitPctUnifiedTest, NationalDashboardTest, DashboardTest, RouteKeyedDashboardTest, TsoDashboardParityTest, SegmentShareTest, GeoValidationTest; app ScopeLeakAcrossEndpointsTest, RouteInventoryTest, ContractConformanceTest | `19215d05` (uncommitted then) | BUILD SUCCESSFUL: 51 + 22 tests, 0 failures (ContractConformanceTest serves and validates the three new operations; RouteInventoryTest finds their scope lines) |
| **Final full** `:backend:analytics:test :backend:app:test --continue` | `19215d05` | app 89 classes, **464 tests, 0 failures** (2 skipped); analytics 50 classes, 295 tests, **1 failure**: `SuspiciousLocationTest` `fixtureSetUp() timed out after 5 minutes` (ReportFixture database setup; the run took 4 h 04 min because another lane took the machine-wide Gradle lock as stale after 75 min and built beside it) |
| `:backend:analytics:test --tests *.SuspiciousLocationTest` (alone) | `19215d05` | BUILD SUCCESSFUL, 1 test, 0 failures: the timeout was load, not this change (it also passed in the earlier full run) |

New classes in the final run: TsoDashboardParityTest 5, NationalExactPctTest 6, BrandCprTest 5, SegmentShareTest 4,
LoginSubmitUsersTest 6, FinalSubmitPctUnifiedTest 2, all green; NationalDashboardTest 22, DashboardTest 9, GeoValidationTest 4,
RouteKeyedDashboardTest 5, NationalDashboardApiTest 5, TargetAchievementTest 9 green. Not run: CI on Linux, the web suite (W1's
files untouched), device checks.

### Next step

Lane B2's s5 items are built. Left for the lead: merge (B2 after B1), the asks above, and the full CI run on Linux.
