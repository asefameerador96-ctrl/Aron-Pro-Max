# UI parity: lane ui-sr-a (SR Home, Attendance, Stock, Sale)

Branch `lane/ui-sr-a`. Binding spec: `docs/ui-reference/PARITY-SPEC.md` (CLAUDE.md rule 14a). Kit: `android/core-ui` (lane ui-kit owns it).

## HANDOVER
(empty: session 1 of the lane, started 2026-10-18)

## Parity table
| Screen | Reference | Status | What differs | sha |
|---|---|---|---|---|
| Home | `sr/home-1-tiles.webp`, `home-2-kpis.webp` | DONE (side-by-side `docs/evidence/ui-parity/sr-home.png`) | Astha and Loyalty tiles absent on purpose (docs/27); kit icons are redraws, not the vendor art; header is slimmer (no status-bar strip); extra slim row for the sync chip and Sunlight switch; target card shows today's sales and outlet progress with no target percentage (deferred); the next-step dock is kept as a gradient button at the bottom | see git log |
| Stock | `sr/stock.webp` | NOT STARTED | letter circles, no packshot, stepper outline | - |
| Sale: choose retailer | `sr/sale-select.png` | NOT STARTED | picker is in `feature-outlet` (not this lane's module) | - |
| Sale: product list + cart | `sr/sale-select.png` + Stock card layout | NOT STARTED | expandable rows, letter circles | - |
| Attendance | `sr/attendance.png` | NOT STARTED | status card rows, bar buttons | - |

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
- 2026-10-18: the day's next-step dock is kept (behaviour) as `AronPrimaryButton` in the scaffold's bottom slot; the "needs a fresh route bundle" reason is a banner above the grid.
- 2026-10-18: the two tile group labels ("Today's work", "Others") are gone: the reference is one grid; HomeScreenTest updated accordingly.
- Goldens re-recorded: the 10 `home_*` goldens (ScreenshotTests, SrStateScreenshotTests) only.
