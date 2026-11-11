# android-amo-team status (lane/android-amo-team; A3 of the AMO manual build)

Brief: `docs/requests/amo-manual-build-2026-11.md` s5 "A3 amo-team", rows 33 to 40 and 71 to 74. Decisions D-AMO-TEAM-01 to 07.

| Row | Screen | Built | Proof (CI) |
|---|---|---|---|
| 33 | Team Location | map host with rider marker, compass, my location, SR drop-down | `TeamScreensRulesTest.teamLocationListsNamedSrsOnlyWithPhonesInClear`, goldens `amo_team_location_*`; the live map is a device check |
| 34 | Monthly / Till Date | zone card + route cards, bands | `TeamTargetsTest`, goldens `amo_team_perf_monthly_*`, `amo_team_perf_till_*` |
| 35 | Details by brand | brand table, 2-decimal percent, Remaining | `TeamTargetsTest.detailsPercent...`, `amo_team_perf_details_*` |
| 36 | One category | tap filters, tap again clears | `TeamTargetsTest.categoryFilter...`, `amo_team_perf_category_*` |
| 37 | Assigned Tasks | chips, FAB | `TeamScreensRulesTest.statusChips...`, `amo_team_tasks_*` |
| 38 | Assign a task | one form page, date picker, banner | `TeamScreensRulesTest.taskFormRules`, `amo_team_task_form_*`, `amo_team_tasks_saved_*` |
| 39 | Live data | route and total variants | `LiveDataTest`, `AmoTeamApiTest`, `amo_team_live_route_*`, `amo_team_live_total_*` |
| 40 | SR list, lifted stock | members rows, packshots, field units | `TeamJourneyTest.membersVacantAndPhones`, `amo_team_sr_list_*`, `amo_team_lifted_*` |
| 71 | Reports, STD memo | tiles, date range, matrix | `TeamScreensRulesTest.stdMatrix...`, `reportRange...`, `amo_team_reports_menu_*`, `amo_team_std_*` |
| 72 | Sales till now | zone/route cards, route detail | `TeamScreensRulesTest.tillNowFiguresAndCpr`, `amo_team_till_now_*`, `amo_team_till_route_*` |
| 73 | SR cards (journey) | cards, vacant card | `amo_team_journey_cards_*` |
| 74 | Journey timeline | timeline, durations, distances | `TeamJourneyTest`, `amo_team_journey_timeline_*` |

Open: F2 contract names for the sales-summary target columns (D-AMO-TEAM-06); real clients are wired against the F1/F2 names of the brief and tested with fixtures; device checks for the map (33).

## Landed state and landing condition
Head d856e566a (lane run 42308568632 green; lead's Opus checker PASS on the code). F2 (journey, members, login-submit additions, report targets) is not on INT yet, so the screens would show empty lists under the banner: the head lands in train 84, right after the foundation train 83.

## HANDOVER (A3 session 1 -> A3 #2), context 498k
Continue from branch `lane/android-amo-team` (merge INT first, one push per CI run). Not done, to take in the next push (the lead's six findings):
1. Refusal is not offline. `AmoFetch.isMissing()` treats Gone (403/404) like offline in TeamPerformanceView.kt:98,121, TeamLocationView.kt:41, TeamLiveView.kt:144 and the SR list / journey views. Pass `refused = f is AmoFetch.Gone` and show a separate refusal string (en + bn in strings_team.xml).
2. AmoReportHub.kt:71-72 passes `emptyMap()` to `TeamReports.tillCard`, so the category Memo column is always "—" although sales-summary rows carry memos: map memos from `Reports.routeCards` per category.
3. AmoReportHub.kt:68 CPR period: use month-to-today (screenshot 72; lead ruling; F-AMO-033's "up to yesterday" is superseded for the AMO summary).
4. Reports.kt:104 `routeCategories` groups by route_name before route_code: group by code, display the name.
5. Note: `TeamStock.entered` / `fromBase` truncates a non-whole packet count (low risk).
6. AmoTaskHub.kt:120 draft key `task:null:<id>` until the SR is known can change when the members list replaces the bundle's choice: key by route and outlet only.

Wiring list when the lead says train 83 landed (names are in docs/status/amo-foundation.md, binding):
- `GET /v1/team/members` (AmoApi.teamMembers, TeamJourneys.members): check `phone`, `phone_masked`, `total_outlets`, `vacant`, `route_days`, `route_name`.
- `GET /v1/team/journey` (AmoApi.teamJourney, TeamJourneys.journey): `outlet.phone_masked` vs a clear `phone`; two-decimal distances.
- `GET /v1/dashboards/login-submit` additions `geo_valid_visits`, `photo_valid_visits`, `visits_total`, `sales_by_category` (all nullable, null prints "—"); `level=route` is NOT offered by the F2 server today (400): Team Performance, sales-till-now and Live Data fall back to the zone; re-enable route cards when it is.
- `GET /v1/dashboards/targets` product_level=brand (zone top node, `level=route&node_id` per route).
- sales-summary `target_qty_base` / `target_value_mtk` at route x category and route x brand grain: the screens do not read them yet (fallback from the targets endpoint is correct forever); memo counts per category from the report rows (item 2).
- `GET /v1/team/stock`: members rows gain `route_names`, `total_outlets`, `phone`; the SR Stock list uses the members list today.
- Also still open: an Opus checker pass on the fixes of D-AMO-TEAM-08; kept members copy keyed by date only; device check D-AMO-TEAM-DEV-01 (Team Location live map: rider marker, compass, my-location, bubble on SR pick).

## A3 #2 session (2026-11-07)
Step 1 done in the working tree: the six findings (D-AMO-TEAM-09) with tests `TeamScreensRulesTest.aRefusalIsNeverShownAsOffline`, `categoryMemoColumnComesFromTheBrandMemosOfTheSalesSummary`, `summaryPeriodIsMonthToTodayNotYesterday`, `routeCategoriesGroupByRouteCodeAndShowTheName`, `taskDraftKeyIsRouteAndOutletOnly`, `TeamStockUnitsTest.aNonWholePacketCountIsTruncatedAndSaidSo`. No local Android SDK in this container: compile and tests are proved by CI only. Step 2 (real F2 clients) waits for "train 83 landed".

## HANDOVER 2 -> 3 (A3 #2, 2026-11-11; code head beef33723, CI run 42945542952 green, INT 7b3cb2738 contained)
Done: the six findings, the F2 clients (journey distance names, members route_code, route_id card matching), units rule 5 on STD / Team Performance / Details / till-now / Live Data (D-AMO-TEAM-16, 19), Live Data per-route memos (D-AMO-TEAM-18), the 32 re-recorded `amo_team_*` goldens, Float/Double audit (D-AMO-TEAM-17). Opus passes: c496ae6b (FAIL, fixed), b87442707 (PASS), c1993b3c1 (PASS).
Local build works: `bash tools/android-sdk.sh` with `ANDROID_HOME=$HOME/android-sdk` and `echo sdk.dir=$HOME/android-sdk > local.properties`; `./gradlew :android:feature-amo:testDebugUnitTest`, goldens `-Proborazzi.record=true --tests '*AmoTeamScreenshotTests*'` (record, then verify without the flag). Read Gradle's own exit status and the test XML before a push, never the exit of a trailing echo. `AmoRefusalHostsTest` (stock lock) failed once in the full app-amo run and passes alone: treat as order-dependent.
Open (server hooks, foundation lane): targets rows in field units; till-now memos over the report window; `level=route` on the targets endpoint. Open (client, low, D-AMO-TEAM-13/19): journey missing distance prints 0.00 m, truncation wording on STD/till-now, zone card Details arrow does nothing, a task can go to the bundle SR on a vacant route, till-now golden still shows memo counts, kept members copy keyed by date only, device check D-AMO-TEAM-DEV-01 (Team Location live map).
For A2's awareness: `AmoLiveDashboardHostTest` now drives `AmoLiveDataHost` (the old `AmoLiveDashboard` host was replaced by A3 in D-AMO-TEAM-07), keeping its two intents (kept copy plus the kept-copy note; nothing invented without a copy); its field-unit assertion moved to `LiveDataTest` and the `amo_team_live_route_*` goldens. A3's Live Data memo strings are `amo_team_live_memos` / `amo_team_live_memos_none` (A2's `amo_live_memos` is untouched; the duplicate name was caught by the local build).
