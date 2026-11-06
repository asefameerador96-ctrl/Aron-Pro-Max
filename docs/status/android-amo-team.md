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
