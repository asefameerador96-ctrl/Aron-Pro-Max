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

The rest of s5 B2: DASH-03/04/08/09/11/12/13/17 (NationalSalesCard `monthly_target_qty` and exact pct, R-F-13 at W = 0,
`submit_of_target_pct_bp`, Brand CPR, Segment share, Login/Submit users with `truncated`, summary sold-only and national route
set, route-keyed national SQL, TSO 200 on every national op), `DashboardSummaries.autoSubmit` removal and the live final-submit
% (D-TSO-MAN-30), docs/10 line 26 (D-TSO-MAN-07), F1 ask 11 and 19; tests TsoDashboardParityTest, NationalExactPctTest,
BrandCprTest, SegmentShareTest, LoginSubmitUsersTest, FinalSubmitPctUnifiedTest.
