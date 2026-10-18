# PARITY SPEC: the new apps and web look like the existing Aron (OWNER DIRECTIVE 2026-10-18, binding)

**Owner, 2026-10-18 (verbatim intent):** the apps (SR, AMO, TSO) and the web dashboard must look like the existing Aron app and the existing web dashboard: the same design, the same functions, the same screens, the packshots (pack pictures) in Stock and Sale. "Why you didn't follow the design of the existing one from the screenshots I provided?" This **supersedes docs/32 (Calm Glass) for the look**: wherever docs/32 or docs/design/* disagree with a reference screenshot, **the screenshot wins**. What stays from docs/32: Bangla-first, offline-first, the performance and battery budgets (docs/04, docs/31), accessibility (touch targets, font scaling to 200 percent, axe A/AA on web), the Sunlight mode as an optional one-tap theme (it is NOT the default look), no hardcoded strings.

The visual ground truth is, in order: (1) the screenshots in `docs/ui-reference/sr/` and the note next to each (`*.md`); (2) `docs/evidence/web-dashboard-reference-2026-10-17.md` for the web dashboard; (3) the manuals in `docs/evidence/manuals/` for any screen with no screenshot (AMO and TSO have **no screenshots in git**: build them in the same visual language as the SR screens, and list every AMO/TSO screen in your parity file as "layout from manual, needs owner screenshot"). Unit rules still win over pictures: cigarettes in sticks, lighters in pieces, matches in dozens; money in integer milli-taka; deferred programmes (docs/27: target, Astha, loyalty, every discount) show no live data (a tile may stay hidden; never invent numbers).

## 1. Tokens (sampled from the screenshots; use these exact values, put them in `core-ui` Tokens.kt and `web` CSS variables)
| Token | Value | Where |
|---|---|---|
| `aron.gradient.bar` | horizontal, 0 percent `#D5484A`, 50 percent `#903171`, 100 percent `#4B1C97` | every top bar, the home header, the selected letter pill (vertical there), big action buttons |
| `aron.red` | `#D5484A` (minus button `#D84A4A`, Save left stop `#D2474B`) | minus buttons, record dot, Outlet menu tile labels `#D0434A` |
| `aron.purple` | `#4B1C97` (totals panel `#481B9A`, plus button, SKU codes, section titles) | primary |
| `aron.page` | `#E8E8E8` (light grey) for list, form and menu screens; `#FFFFFF` for the Home tile grid | page background |
| `aron.card` | `#FFFFFF`, radius 12 dp, soft shadow (elevation 2 dp), no blur, no gradient background | cards, rows |
| `aron.tile` | `#FFFFFF` on Home (hairline `#EEEEEE`), `#F5F5F5` on menu screens, radius 12 dp, icon 56 dp over a label 14 sp in indigo `#3F2D9B` (Outlet menu: label red) | tile grids |
| `aron.panel.dark` | `#505050` radius 12 dp, white 20 sp letters | the A-Z retailer filter; table header rows `#4D4D4D` with white 12 sp text |
| `aron.target` | vertical gradient `#C74350` to `#561F91`, white text, progress track `#EFEFEF`, fill `#FF0100` | the Home "Target and achievement" card (deferred: show the card shell with the day's sales, no target %) |
| `aron.print.disabled` | `#E8E8E8` fill, `#A5A5A5` text | Print before a printer is connected |
| Type | Roboto (Latin) and Noto Sans Bengali (already bundled), title 20 sp bold white, body 16 sp, numbers 22 to 28 sp, Bengali digits in Bangla mode | all |
| Sunlight | optional theme from the settings gear: solid white bars, black text, 7:1 key figures | not default |

## 2. Shared components (core-ui; build these FIRST, every screen uses only these)
1. `AronTopBar`: full-width `aron.gradient.bar`, back chevron (white, left), centered **gradient line icon (36 dp) + white bold title**, optional right slot (printer state: red circle with crossed printer when not connected, tap to connect). No status chip row, no glass.
2. `AronScaffold`: `aron.page` background, content, optional bottom purple panel, optional bottom button row.
3. `AronHomeHeader`: gradient, white round avatar with person glyph (left), name `Role - Name (login)` 16 sp bold white, second line route and date 12 sp, white settings gear (right).
4. `AronTile` and `AronTileGrid` (4 columns on a phone, 2 on narrow): see tokens; badge slot (count) top-right; locked or deferred tiles hidden, never greyed filler.
5. `PackCard` (Stock, Sale, review): white card; at the left a **packshot** (about 68 x 120 dp, the product picture, full height of the card, 4 dp radius) with a **purple round badge (`aron.purple`, white number) overlapping its bottom-right** (the packs equivalent, UI-SR-18); then the SKU code in purple 18 sp bold (Latin), then **red rounded-square minus (36 dp)**, an underlined numeric field (the quantity, tap to type), **purple rounded-square plus (36 dp)**, then the stock number in black at the right. Column headers above the list: `এসকেইউ` / `ইস্যু` (or the sale column) / `স্টক`, grey 20 sp.
6. `PackshotImage(skuId)`: shows the cached thumbnail for the SKU (ImageCache, bounded LRU, thumbnails over mobile data allowed, never blocks a sale, never a spinner); if none is cached yet, a neutral pack silhouette in `aron.page` grey with the SKU's category glyph, never a letter circle.
7. `TotalsPanel`: solid `aron.panel` purple `#481B9A` docked above the buttons: header row `ক্যাটাগরি / মোট ইস্যু / স্টক` (12 sp), then one row per category (`সিগারেট`, `বিড়ি`, `লাইটার`, `ম্যাচ`) 22 sp white, numbers right-aligned.
8. Buttons: `AronPrimaryButton` (gradient red to purple, 8 dp radius, white label with leading icon, 48 dp), `AronSecondaryButton` (grey `#E8E8E8` with `#A5A5A5` text when disabled, dark text when enabled), `AronPlus` / `AronMinus`.
9. `RetailerPicker`: white pill dropdown "রিটেইলার নির্বাচন করুন" with a caret; below it `aron.panel.dark` with the letter filter (`All`, then letters; the selected one is a gradient rounded square).
10. `SummaryTable`: dark header row, SKU name in purple 20 sp over a second line of numbers, alternating white and `#F0F0F0` rows, category subtotal rows in purple, a totals block, then a full-width gradient Print button.
11. Icon set: redraw every Home and menu icon as **vector drawables in the reference style** (rounded line art with a pink to blue gradient fill): attendance (calendar with clock), stock (clipboard with boxes), sale (box with clock), memo (banknote and phone), summary (server rack), sales submit (shield with check), outlet (shop), tutorial (monitor with play), task delegation (people flow), photo capture (camera), sales journey, KPI (gauge), plus the Outlet menu set (new shop, closed sign, edit shop, cluster). AKTCL owns these assets (CLAUDE.md: AKTCL owns the IP); the originals are visible in `docs/ui-reference/sr/home-1-tiles.webp` and `outlet-menu.png`: redraw them, do not guess a different style, and do not use stock Material icons for these.

## 3. Screens, SR app (each one is a parity item; the reference image is the acceptance test)
| Screen | Reference | Module |
|---|---|---|
| Home (header, 4 x N tile grid, target card) | `sr/home-1-tiles.webp`, `sr/home-2-kpis.webp`, `sr/home.md` | feature-home |
| Attendance | `sr/attendance.png`, `.md` | feature-attendance |
| Stock (packshots, issue stepper, totals panel, Save and Print) | `sr/stock.webp`, `.md` | feature-stock |
| Sale: choose retailer (dropdown + letter filter) then the product list with packshots and the cart | `sr/sale-select.png`, `.md` (the product list uses the Stock card layout with the sale quantity) | feature-sale |
| Memo (view, reprint) | `sr/memo.md` | feature-memo |
| Sales summary | `sr/summary.png`, `sr/sales-summary.md` | feature-memo / feature-dayclose |
| Sales submit | `sr/sales-submit.png`, `.md` | feature-dayclose |
| Outlet menu (new shop, permanent close, change info, cluster) | `sr/outlet-menu.png`, `.md` | feature-outlet |
| Tasks, tutorial, photo capture, login, settings | `sr/tasks.png` and the notes | feature-tasks, feature-auth |
AMO and TSO: the same shell (top bar, home header, tiles, cards, tables, panels) on every screen of `feature-amo` and `feature-tso` and the two app modules; layouts from the manuals until the owner sends screenshots.

## 4. Web dashboard and portal (reference: `docs/evidence/web-dashboard-reference-2026-10-17.md`)
Left sidebar full height, **vertical gradient crimson (top) to purple (middle) to crimson (bottom)** (`#D5484A` / `#4B1C97` / `#D5484A`), white labels with line icons, a collapse chevron at the top right of the sidebar, the round logo at the top, a **red Logout button at the bottom**; top bar with the page title in blue and the user's avatar and name at the right; light grey page; white cards with blue titles and a thin rule; the four sales cards, the four status cards, the channel pie, the brand breakdown with the STD/Memo toggle, the geo-fencing ring, the team map: as in the reference note. This replaces the Calm Glass shell on every page (dashboard, reports, admin); content components keep their data, their states and their accessibility. Target, Astha and Superstar entries stay out (docs/27).

## 5. How each lane proves parity (the owner reviews pictures, not claims)
- For every screen you change: a screenshot test in Bangla light (and English where the repo already has one), plus **one side-by-side PNG** `docs/evidence/ui-parity/<role>-<screen>.png` (left: the reference image, right: ours, same height). No reference image (AMO, TSO, most web admin pages): say so in the file name `-noref`.
- A parity file `docs/status/ui-parity-<lane>.md`: one row per screen: reference, status (DONE / PARTIAL with what differs / NOT STARTED), the sha. Be exact about what still differs.
- Keep the golden tests, empty/loading/offline/error states, font scaling, TalkBack labels and the Sunlight theme working; do not delete a test to pass.
- Push early and often (one push per finished screen group, each with its own green run), send the lead `ready <sha>`; the integrator promotes. The owner installs the newest signed dev APK from the INT run in the morning.
