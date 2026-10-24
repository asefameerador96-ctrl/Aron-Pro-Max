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
| 1 | Home (`AmoApp`; AMO's OWN Home composed in feature-amo, NOT feature-home `RoleHomeContent`, per lead 2026-10-18) | S-06 | AronHomeHeader (`AMO - name (user)`, territory + date, gear) + AronTileGrid of the AMO tiles (8 hubs as built; deferred Astha hidden); no glass cards; Sunlight toggle moves to Settings | PARTIAL: AmoHomeContent (header, 4-column tile grid, day card, quiet status lines). AMO's tiles are the 8 hubs, not the manual's 16 (D-AMO-HOME-01); sync chip and bundle line kept as a quiet row under the tiles. Golden amo_home_bn_light/_sunlight/_fs2 re-recorded; `docs/evidence/ui-parity/amo-home-noref.png`. Layout from manual, needs owner screenshot |
| 2 | Shell of every screen (`AronScreen`/`AronTopBar` in all hosts) | all | AronScaffold + AronTopBar (gradient, centered icon + title) | PARTIAL: AmoFrame (kit AronScaffold + AronTopBar gradient, back chevron, centred icon + title) on every AMO host. Differs: screens without a tile icon show title only; printer badge on Stock/Memo/Summary is the old PrinterIcon (shared core-printing) |
| 3 | Live dashboard + filter (`LiveDashboardScreens`) | S-29 | AronTopBar; filter pill row; white cards titled Sales / Strike rate / Geo / Login with big numbers; purple header on the sales card; gradient Filter button | PARTIAL: purple-header cards (Sales, Strike rate with bar, Location checks, Login with bars), gradient Filter, filter list as row cards. Differs: no donut charts (bars instead); `amo-live-dashboard-noref.png` (closest SR style: sale-select) |
| 4 | Verify list + form (`OutletVerifyScreens`) | S-33/34/36-39 | white row cards with request type icon; form in white cards, dark-grey section headers, chips for sub-channel, geo-class chips, gradient Save, grey Cancel | PARTIAL: white field cards, gradient Save, outlined Cancel, dark purple heading, chips for geo class. Differs: sub-channel list and text field use row cards / the feature-outlet KitTextField (lavender fill), not the manual's dropdowns; `amo-verify-noref.png` |
| 5 | Team stock list + detail (`TeamStockScreens`) | S-30/31 | list: white rows (SR code, route, masked phone, outlet count); detail: summary card + SummaryTable (SKU / issue) with PackCard-style rows, PackshotImage, purple totals panel | PARTIAL: SR list as row cards, detail with PackshotImage rows (kit provider, silhouette until ui-packshots wires thumbnails) and purple totals block. Differs: not the manual's SummaryTable (units differ per row so no summed footer); `amo-stock-correction-noref.png` shows the stock-correction screen against sr/stock.webp |
| 6 | Route pick, control-call menu (`ControlCallScreens`) | S-13/16 | RetailerPicker-style dropdowns; menu as AronTileGrid (Sales, SR Perf. Assessment, Survey) | PARTIAL: route list as row cards, call menu as gradient buttons. Differs: manual's dropdown pickers not built, menu not a tile grid; `amo-control-call-noref.png` |
| 7 | Manual override (`ManualOverrideScreen`) | S-14 | card with camera tile, gradient primary button | PARTIAL: white card, gradient Continue; camera button still the old outlined style |
| 8 | Joint call (`JointCallScreen`) | S-11/12 | white cards per criterion, chips, gradient Save | PARTIAL: row cards, star chips, gradient Save (stars are the old rating control) |
| 9 | Exceptions list/detail (`ExceptionsScreens`) | docs/07 | white row cards, status chips, gradient action buttons | PARTIAL: row cards with severity, evidence cards, outlined review/dismiss, red confirm |
| 10 | Tasks list + assign (`TaskScreens`) | S-27/28 | white task cards with status chip; assign form in cards, gradient Assign | PARTIAL: white task cards with status text, assign form as row cards + gradient Assign; `amo-tasks-noref.png`. Differs: status is text, not a chip; description field is the old KitTextField |
| 11 | Team hub, Outlet hub (`HubScreens`) | S-32 | AronTileGrid on `aron.page`, red tile labels (Outlet menu look), badge on verify | DONE for look: kit AronTile menu grid, 3 per row, red labels on Outlet, red count badge; Sale hub as 4 tiles. Icons: ui-kit line icons (team, location, performance, live, SR stock, exceptions, update base) and the owner art for the shared ones; `amo-outlet-hub-noref.png` |
| 12 | Team performance (`TeamPerformanceScreens`) | S-25/26 | SummaryTable + cards; targets stay deferred | PARTIAL: monthly/till-date chips, progress bars. Differs: not card-per-route as the manual |
| 13 | Price compliance (`PriceComplianceScreen`) | S-22 | PackCard rows (packshot, price field) + gradient Save | PARTIAL: row cards with price field and gradient Save. Differs: no PackCard/packshot yet |
| 14 | Perf assessment (`PerfAssessmentScreen`) | S-22 | white cards, chips per question, gradient Save | PARTIAL: checkboxes and Yes/No (purple/red) with gradient Save; checkboxes are the old control |
| 15 | Reports: STD memo, sales summary (`ReportScreens`) | S-55-58 | SummaryTable (dark header, purple SKU names, subtotals, totals block), gradient Print | PARTIAL: row cards and a plain memo table; `amo-report-route-summary-noref.png` (route summary, not the SR day summary). Differs: SummaryTable not used; Print button n/a |
| 16 | Sale hub, Stock, STD/Memo, Sales summary, Sales submit (`app-amo` hosts over SR kit screens) | S-45/46/50/51 | SR screens restyled by lanes ui-sr-a/b; AMO only the hub list as AronTileGrid | NOT STARTED here: Stock, Sale, Memo, Summary, Sales Submit bodies are shared SR screens (ui-sr-a/b); AMO only changed their frame to AmoFrame and the Sale hub |
| 17 | Settings (AMO's OWN Settings in feature-amo; feature-home is ui-sr-a's, not edited) | S-59 | kit top bar + white cards: language, Sunlight, PDA to Support, update, logout | PARTIAL: AmoSettingsContent built (white row cards, gradient Logout); no Settings golden yet |

Every row: layout from manual, needs owner screenshot.

## Notes (2026-10-18 night)
- Pushes: d8e41a41 (Home + Settings, green run 39175027598); b52a117b (frame, restyle, hubs, green run 39176138483); f6adde08 (icons, run pending at time of writing). Report memo table now has the dark header and alternating rows (local, next push). Earlier plan for the next push: frame on every host, row/card parity restyle of all feature-amo screens, hubs as tile grids.
- Shims in `AmoParts.kt` (`AmoListRow`, `AmoBanner`, ...) keep each screen's call shape and render the parity look; they are public so app-amo can use them.
- AMO goldens re-recorded (Linux); the harness frame in `AmoTsoScreenshotTests`/`AmoCoreScreenshotTests` now uses `AmoFrame` (the 4 tso_ shots in that file changed with it: ui-tso should re-record on merge).
- Asked ui-kit for icons: joint_call, control_call, team, team_location, team_performance, live_dashboard, sr_stock, exceptions, report, price_compliance, assessment, survey, verify, update_base.
- Open: feature-outlet KitTextField (lavender) and rating stars/checkboxes are old-look controls; ui-packshots provider wiring into AMO screens: DONE (verified 2026-10-24 by android-tso #4: AMO SessionModule provides SkuPackshots and PackshotPreload, MainActivity sets LocalPackshotProvider, Stock and Sale hosts render the shared PackCard/PackshotImage rows; PackshotWiringTest covers them).

## Status at push 2026-10-19 00:00 UTC
Merged INT 970662c1 + ui-sr-a fda4ed67; AMO goldens re-recorded (no pixel change vs the merged render); full local verify, lint, tests and the three debug APKs green. Run ids: d8e41a41 -> 39175027598 green; b52a117b -> 39176138483 green; f6adde08 -> 39177067752 green.
Rows: 1 Home PARTIAL (icons now owner art + kit line icons); 2 Shell PARTIAL; 3 Live dashboard PARTIAL (fs2 stacks); 4 Verify PARTIAL; 5 Team stock PARTIAL; 6 Control-call PARTIAL; 7 Manual override PARTIAL (GeoPhotoBlock restyled by ui-sr-b); 8 Joint call PARTIAL; 9 Exceptions PARTIAL; 10 Tasks PARTIAL; 11 Hubs DONE for look (icons wired); 12-14 PARTIAL; 15 Reports PARTIAL (dark-header memo table); 16 shared SR bodies: ui-sr-a/b; 17 Settings PARTIAL (no golden yet).

## Kit requests (ui-kit reads this section; no merge of lane/ui-kit beyond abac6ba4 until the kit window)
- AronField (kit text field) in the parity look to replace feature-outlet `KitTextField` (lavender fill) in Verify note, Exceptions note, Task description, Price compliance, Stock correction.
- Rating stars and checkbox in the parity look (Joint Call, SR Perf. Assessment checkboxes).
- A Yes/No pair (purple/red) and an outlined secondary button for grey pages (AMO has local `AmoYes`/`AmoNo`/`AmoSecondary`; the kit `AronSecondaryButton` is `#E8E8E8` and vanishes on the grey page).
- A read-only pack row with PackshotImage (no stepper) for Team Stock detail and Price compliance (AMO has local `AmoPackRow`).
- A settings tag hook on `AronHomeHeader` (gear) so tests can find it by tag.
- (QA 2026-10-19, kit) AMO-only icons (joint call, team, report, live dashboard, control call) are chunkier than the SR set: redraw with the SR stroke weight, rounded caps, thin outline + pink-to-blue gradient fill and an inner detail.
- (QA 2026-10-19, kit) Tile count badge covers the awning: top-right corner, 4 dp outside the icon box, 11 sp bold, 20 dp circle.
- (QA 2026-10-19, kit) One shared sync-status footer for AMO and TSO; AMO Home will adopt it via INT (AMO now has a token-colour local line: purple check, grey ink, one device figure per line).

## Status after the kit window (INT 186279f4)
- AMO Home now uses the shared `AronSyncFooter` (sync state + bundle line + device figures, token colours); tap still opens the sync sheet. Gear has `AronHomeSettingsTag` and the 20 dp badge from the kit (INT).
- Goldens: INT's amo_* goldens taken; re-recorded only amo_home_bn_light and amo_home_bn_sunlight (changed by the footer).
- NOT DONE: AMO-only icon redraw (joint call, team, report, live dashboard, control call): no approach yet that reuses the SR icon grid and the owner art crops without hand-drawing new art; left with ui-kit (Kit requests).
- Still PARTIAL as listed in the table above; every row "layout from manual, needs owner screenshot".

## Kit controls swap (cycle ~02:30 UTC)
Moved to kit components (`core/ui/aron/Controls.kt`, `Forms.kt`): text fields -> `AronField` (Verify note, Exceptions note, Task description, Price compliance, Stock correction; via `AmoField`), choice chips -> `AronChoiceChip` (geo class, Team Performance tabs), outlined buttons -> `AronOutlinedButton` (`AmoSecondary`), checkboxes -> `AronCheckboxRow` (SR Perf. Assessment), rating stars -> `AronRatingStars` (Joint Call; per-star test tags replaced by one row tag `JointTags.rating`), read-only pack rows -> `AronPackRow` (Team Stock detail, Price compliance now shows the packshot), section headers -> `AronSectionHeader`, loading -> `AronLoadingCard` (Verify details).
Kept local on purpose: Yes/No pair (`AmoYes`/`AmoNo`: two separate test tags per question in Survey/Assessment; the kit `AronYesNo` is one control), `AmoCard` purple-strip cards, `AmoListRow` (needs trailing content), `AmoNotice`.
