# UI parity: lane ui-sr-a (SR Home, Attendance, Stock, Sale)

Branch `lane/ui-sr-a`. Binding spec: `docs/ui-reference/PARITY-SPEC.md` (CLAUDE.md rule 14a). Kit: `android/core-ui` (lane ui-kit owns it).

## HANDOVER
(empty: session 1 of the lane, started 2026-10-18). State: Home, Stock (11433dbf, CHECKED), then fda4ed67 (Home fixes, Sale, Attendance, Settings, Tutorial). Open: restyle QC, Credit and Slide screens of the sale flow; permission onboarding and route picker in OnboardingScreens; kit tip 704183d (PackCard weights) needs a golden re-record when merged; `RoleSettingsContent` (AMO/TSO) is ui-amo/ui-tso scope.

## Parity table
| Screen | Reference | Status | What differs | sha |
|---|---|---|---|---|
| Home | `sr/home-1-tiles.webp`, `home-2-kpis.webp` | DONE (side-by-side `docs/evidence/ui-parity/sr-home.png`) | Astha and Loyalty tiles absent on purpose (docs/27); kit icons are redraws, not the vendor art; header is slimmer (no status-bar strip); extra slim row for the sync chip and Sunlight switch; target card shows today's sales and outlet progress with no target percentage (deferred); the next-step dock button is removed (lead, 2026-10-18); the sync and Sunlight row sits below the target card | see git log |
| Stock | `sr/stock.webp` | DONE except packshots (`docs/evidence/ui-parity/sr-stock.png`) | packshots show the neutral silhouette until the ui-packshots data lands (the rows already use `PackshotImage(skuId, category)`); no category group headings (the reference is one flat list); the totals panel carries the unit as a caption under each category, so the panel is taller than the reference; Print is always shown, grey until a slip is saved and a printer is connected | see git log |
| Sale: choose retailer | `sr/sale-select.png` | DONE (`docs/evidence/ui-parity/sr-sale-picker.png`) | pill field, dark A-Z panel and the empty hint as the reference; the route list opens on a tap on the field or a letter; the visited/remaining segments and the meter are gone (marks stay on rows) | see git log |
| Sale: product list + cart | `sr/stock.webp` layout, `sr/summary.png` table | PARTIAL (`sr-sale-list.png`, `sr-sale-review.png`) | packshots are silhouettes until ui-packshots lands; the price/unit/stock caption under each card and the packs + line total line are additions the reference lacks (the seller needs them); QC, Credit and Slide screens of the sale flow are NOT restyled yet | see git log |
| Tutorial list | no screenshot in git (`sr/home-1-tiles.webp` only shows the tile) | DONE as layout from the kit, needs owner screenshot (`docs/evidence/ui-parity/sr-tutorial-noref.png`) | rows are kit list rows with the tutorial art; permission onboarding and route picker of the same file are NOT restyled | see git log |
| Settings (gear page) | no screenshot in git | DONE as layout from the kit, needs owner screenshot (`docs/evidence/ui-parity/sr-settings-noref.png`) | language tabs, switch rows, list rows, red logout button; `RoleSettingsContent` (AMO and TSO, in `RoleHome.kt`) NOT touched: it sits in the ui-amo/ui-tso scope with the role Home | see git log |
| Attendance | `sr/attendance.png` | DONE (`docs/evidence/ui-parity/sr-attendance.png`) | the hold sheet (press and hold to confirm) is kept as the confirmation and is not in the reference; the refresh icon is not red; both big buttons always show, grey when not open | see git log |

## Element lists (written before the kit landed; each item maps to a kit component)

### Home (`feature-home` HomeContent)
1. `AronHomeHeader`: gradient, round white avatar + person glyph, `SR - Name (login)` 16 sp bold, line 2 `route, ISO date` 12 sp (Bangla digits in bn), white gear (right). Sunlight toggle stays reachable (inside settings gear surface, optional theme).
2. White page; `AronTileGrid` 4 columns: Attendance, Stock, Sale, Memo / Summary, Sales Submit, Outlet, Tutorial / Task delegation (badge), Photo Capture (when enabled), Sales Journey, KPI. Astha and Loyalty never listed (docs/27). Tile = white, hairline, 56 dp redrawn icon, 14 sp indigo label.
3. Bottom card `Target and achievement` shell (vertical gradient `#C74350` to `#561F91`): title, the day's sales (outlets visited / planned as the progress bar, money total), `Details` link opens Summary. No target percentage (deferred).
4. Banners (stale, expired, missing bundle, offline, reauth, update) keep as kit banners above the grid; sync chip and device-health line stay as a slim strip under the grid (no data lost).
5. Keep: next-step dock behaviour (as the accent ring on the target tile plus the existing disabled rules), KPI strip and stock/money cards move below the grid inside white cards (reference `home-2-kpis.webp`).

### Stock (`feature-stock` StockContent)
Top bar `AronTopBar` with stock icon, title, printer-state action (red crossed printer, tap = connect flow). Column heads `এসকেইউ / ইস্যু / স্টক`. One `PackCard` per SKU (packshot + purple badge of packs, SKU code purple, red minus, underlined issue field, purple plus, stock column). `TotalsPanel` (category, total issue, stock; unit under the number). Save (gradient) and Print (grey until printer connected). Keep banners (saved, refused, nothing entered, failed), soft ceiling warning, TalkBack merged row with custom actions.

### Sale (`feature-sale` + picker in `feature-outlet`)
Step 1 `RetailerPicker` (pill dropdown, dark A-Z panel, All = gradient square, empty state text). Step 2 `PackCard` list with the sale quantity (price caption, stock, line total, over-stock warning, QC-only link, previous sales). Step 3 review: lines in a card, `TotalsPanel`, gradient action button; memo hand-off unchanged.

### Attendance (`feature-attendance` AttendanceContent)
`AronTopBar` calendar-clock icon, user card (avatar tile, `SR - Name (login)`, route and date) over a red hairline, location card (pin tile, address or coordinates, red refresh), prompt text, two big buttons (green Check in, grey Check out) keeping the press-and-hold confirm sheet and every existing state.

## Decisions (Home)
- 2026-10-18: the bottom card is titled "আজকের অগ্রগতি / Today's progress" (not "Target and achievement") because no target exists (docs/27); it carries the day's sales total, outlets visited of planned, check-in time and a Details link to Summary.
- 2026-10-18 (lead): the next-step dock button is removed; the Sale tile is the entry. `HomeDock`/`HomeDayModel` stay in the model (unused by the screen); the "fresh bundle needed" state is the disabled selling tiles plus the bundle banner.
- 2026-10-18: the two tile group labels ("Today's work", "Others") are gone: the reference is one grid; HomeScreenTest updated accordingly.
- Goldens re-recorded: the 10 `home_*` goldens (ScreenshotTests, SrStateScreenshotTests) only.

## Decisions (Stock and host frame)
- 2026-10-18: `SrFrame` (app-sr) now draws the kit bar: back chevron, the tile art (`icon` param, drawable res of `core-ui` `aron_art_*`), white title; the printer state hangs on the bar edge (`SrPrinterBadge`: red crossed printer when not connected, tap opens the existing picker). The green/red printer banner is gone (the badge carries the state and its TalkBack text). The sync chip shows only when offline, syncing or waiting.
- 2026-10-18: Stock units: cigarettes in sticks, lighters in pieces, matches in dozens stay; the unit is the caption under each category in the totals panel (kit `TotalsLine.caption`).
- 2026-10-18: Stock TalkBack: each pack card control has its own label with the SKU name (minus, Issue field, plus); the old merged row with custom actions is gone because the kit card owns its controls.
- Goldens re-recorded: `stock_bn_light`, `stock_bn_sunlight` (ScreenshotTests, via the new `ParityFrame` test twin of the host frame; other screens keep the old frame twin until they move).

## Decisions (Sale, Attendance, Stock stock figure)
- 2026-10-18: Sale step 1 follows the reference flow: the list of the route opens after a tap on the pill field or a letter (the old always-visible list with the remaining/visited/all segments is replaced); each row keeps its visit mark, outcome word, due chip and distance.
- 2026-10-18: Sale step 2 rows are the kit PackCard; the "price / unit - in stock" caption stays under every card, with the packs equivalent and the line total once a quantity is entered; the over-stock warning stays a warning.
- 2026-10-18: Sale step 3 is the kit SummaryTable (SKU over its unit, quantity, value; category subtotals; the totals block with gross, deductions, net, paid, due) with the purple net panel and the gradient Print button.
- 2026-10-18: Stock now shows the on-hand figure ("স্টক") because `SrDay.stockLoad` passes `StockTracker` balances to `StockLoad(currentBaseBySku)`; display only, a Save still posts only the entered increments; the figure moves with a committed save.
- 2026-10-18: Attendance gets the user card through two optional parameters (`userTitle`, `userSubtitle`) filled by app-sr from `HomeModel.header`.

## Vision QA round (lead, 2026-10-19 00:05 UTC): what changed, what did not
Done on the clean line (INT + my commits, kit abac6ba):
- HOME: tiles directly under the header; six outlined KPI tiles in the lavender container (visited/planned, strike rate, issue, current stock, non-visit, no sale; issue and current stock add the categories as the existing app does); gradient sales panel "বিক্রয় (স্টিক)" with the sticks sold from `StockTracker` (sold, cigarette and bidi), category rows, মোট, ডিসকাউন্ট and QC as separate rows each bound to its own field (`SrHomeMoneyBindingTest` proves a discount never renders under QC; the earlier mislabel was the screenshot fixture, the real binding was correct), rule, সর্বমোট, no ৳ on rows; second panel (মোট ডিসকাউন্ট, DRP ডিসকাউন্ট, মোট QC, সর্ব মোট, মোট টাকা); the check-in line, the sync/sun row and the battery footer are gone (device line, sync chip and Sunlight toggle are in Settings; the check-in time shows on Attendance); Photo Capture tile after Task Delegation (its tap says the programmes it belongs to start later: docs/27); subtitle `route, yyyy-MM-dd` with Latin digits, no `· Daily`.
- STOCK: the label is `sku.code` (hyphenated); no mint banner after Save (the Save button says "Saved" and Print becomes active).
- SALE list: retailer pill with `name (code-phone-cluster)` (the host no longer prints a bare title); previous sales is the full-width green button "পূর্বের সেল ডাটা দেখুন"; no category chip row, no sub-header, no captions between cards; label is `sku.code`; single footer row (cart icon, total without ৳, "এগিয়ে যান"); the over-stock state is a red warning line under the card.
- SALE review: three header lines (outlet with code, রুট:, ক্লাস্টার:), purple checkbox for credit, yellow "প্রোডাক্ট QC" button, ৳ glued to money cells, the net once (no purple duplicate), Print on the grey page with the Summary gradient direction, printer badge on the visit bar (all sale-flow steps).
- PICKER: first chip "All" in Bangla too.
- ATTENDANCE: location card #D7D7D7 compact, no border, address or Latin coordinates on one line, "±18 m" grey suffix instead of the pill, red refresh icon, 32 dp lavender tiles, Latin date, the saved-offline line only once a check-in is stored, 20 dp glyph and 14 sp label, bigger gap before the message.

NOT done, and why:
- Letter strip (All + letters) on the product list screen: the pill is there, the strip is not, because it has no function on that step (the retailer is already chosen). Say if you want it as decoration.
- Category filter and search on the product list: removed as the QA asks; a 40-SKU route now scrolls without a filter.
- Line total / price / unit inside the card: needs a kit slot (below); until then the card shows quantity, stock and the packs badge only, and the price is visible on the review.
- Printer badge only on the review step: the badge is on the whole visit bar (the bar is outside the sale route).

## Kit requests (for the kit window)
1. `PackCard`: an optional caption line INSIDE the card under the SKU code (price per unit, and the line total only when quantity > 0), so nothing has to be printed between cards.
2. `PackCard`: field and stock digits Bold (done in kit 704183d, not on abac6ba); `AronMinus` solid red at zero (no grey); minus/plus about 22 to 26 dp with a 48 dp touch area; SKU code never wraps at 360 dp (packshot and stock column narrower below 400 dp) and the plus button must stay on the card at 320 dp.
3. `TotalsPanel`: unit inline after the category in 12 sp (one row per category); the kit has `TotalsLine.caption`, on abac6ba it needs no more than that.
4. `AronTopBar`: slimmer bar (about 36 to 40 dp), thin chevron, smaller printer badge circle (about 26 dp).
5. `AronPrimaryButton` on the purple panel: end the gradient at about #7D2B7B so the right edge stays visible.
6. `RetailerPickerField`: hint #9E9E9E regular, caret #616161.
7. Latin typeface Roboto in the shared typography.
8. `AronHomeHeader`: one-line title, 14 to 15 sp medium; tile label 12 sp medium on one line; tile aspect about 0.87 and 8 dp outer padding.
