# UI parity: lane ui-amo (AMO app, PARITY-SPEC 2026-10-18)

Branch `lane/ui-amo`. Modules `android/feature-amo`, `android/app-amo`. Kit: `android/core-ui` (lane ui-kit, not edited here).
Reference: AMO has NO screenshots in git. Every row is "layout from manual, needs owner screenshot"; the visual language is the SR set
(`docs/ui-reference/sr/`: home-1-tiles.webp, stock.webp, summary.png, outlet-menu.png, sale-select.png). Fields, order and flow of the screens as built stay.

## Gap seen at start (amo_home_bn_light.png vs sr/home-1-tiles.webp)
Ours: pastel gradient page, letter avatar, glass cards with money, tiles as pastel squares with Material icons, navy label pills, sync/battery text.
Existing Aron: red-to-purple header with white avatar and gear, white page, 4-column hairline tiles with gradient line-art icons and indigo labels, no status text.

## Planned composition per screen (kit names from PARITY-SPEC section 2)
| # | Screen (host) | Manual | Composition | Status |
|---|---|---|---|---|
| 1 | Home (`AmoApp`; AMO's OWN Home composed in feature-amo, NOT feature-home `RoleHomeContent`, per lead 2026-10-18) | S-06 | AronHomeHeader (`AMO - name (user)`, territory + date, gear) + AronTileGrid of the AMO tiles (8 hubs as built; deferred Astha hidden); no glass cards; Sunlight toggle moves to Settings | NOT STARTED |
| 2 | Shell of every screen (`AronScreen`/`AronTopBar` in all hosts) | all | AronScaffold + AronTopBar (gradient, centered icon + title) | NOT STARTED |
| 3 | Live dashboard + filter (`LiveDashboardScreens`) | S-29 | AronTopBar; filter pill row; white cards titled Sales / Strike rate / Geo / Login with big numbers; purple header on the sales card; gradient Filter button | NOT STARTED |
| 4 | Verify list + form (`OutletVerifyScreens`) | S-33/34/36-39 | white row cards with request type icon; form in white cards, dark-grey section headers, chips for sub-channel, geo-class chips, gradient Save, grey Cancel | NOT STARTED |
| 5 | Team stock list + detail (`TeamStockScreens`) | S-30/31 | list: white rows (SR code, route, masked phone, outlet count); detail: summary card + SummaryTable (SKU / issue) with PackCard-style rows, PackshotImage, purple totals panel | NOT STARTED |
| 6 | Route pick, control-call menu (`ControlCallScreens`) | S-13/16 | RetailerPicker-style dropdowns; menu as AronTileGrid (Sales, SR Perf. Assessment, Survey) | NOT STARTED |
| 7 | Manual override (`ManualOverrideScreen`) | S-14 | card with camera tile, gradient primary button | NOT STARTED |
| 8 | Joint call (`JointCallScreen`) | S-11/12 | white cards per criterion, chips, gradient Save | NOT STARTED |
| 9 | Exceptions list/detail (`ExceptionsScreens`) | docs/07 | white row cards, status chips, gradient action buttons | NOT STARTED |
| 10 | Tasks list + assign (`TaskScreens`) | S-27/28 | white task cards with status chip; assign form in cards, gradient Assign | NOT STARTED |
| 11 | Team hub, Outlet hub (`HubScreens`) | S-32 | AronTileGrid on `aron.page`, red tile labels (Outlet menu look), badge on verify | NOT STARTED |
| 12 | Team performance (`TeamPerformanceScreens`) | S-25/26 | SummaryTable + cards; targets stay deferred | NOT STARTED |
| 13 | Price compliance (`PriceComplianceScreen`) | S-22 | PackCard rows (packshot, price field) + gradient Save | NOT STARTED |
| 14 | Perf assessment (`PerfAssessmentScreen`) | S-22 | white cards, chips per question, gradient Save | NOT STARTED |
| 15 | Reports: STD memo, sales summary (`ReportScreens`) | S-55-58 | SummaryTable (dark header, purple SKU names, subtotals, totals block), gradient Print | NOT STARTED |
| 16 | Sale hub, Stock, STD/Memo, Sales summary, Sales submit (`app-amo` hosts over SR kit screens) | S-45/46/50/51 | SR screens restyled by lanes ui-sr-a/b; AMO only the hub list as AronTileGrid | NOT STARTED |
| 17 | Settings (AMO's OWN Settings in feature-amo; feature-home is ui-sr-a's, not edited) | S-59 | kit top bar + white cards: language, Sunlight, PDA to Support, update, logout | NOT STARTED |

Every row: layout from manual, needs owner screenshot.
