# Web: national dashboard (owner screenshot 2026-10-22, user `asef`)

Image: `dashboard-national.webp` (2000 px capture, filed at 1600 px; the login name at the top right is
painted over). The owner's brief that explains every card is `dashboard-brief.md`, with six more captures
`dashboard-brief-1..6-*.webp`. The earlier text-only reference of 2026-10-17
(`docs/evidence/web-dashboard-reference-2026-10-17.md`) stays as history; where the two differ, this page
and the brief win (D-DASH-02).

## What the screen shows

A full-height sidebar (crimson at the top, purple in the middle, crimson at the bottom; white labels with line
icons; the Aron "A" logo at the top; a round collapse chevron on the content edge; a red **Logout** button) and
a light grey page with white cards. Header "Dashboard" in dark blue, the login name with an avatar at the top
right, a yellow **Filter** button (list icon) under it that shows or hides the filter panel.

Menu, top to bottom, for this national login: Dashboard, Retailer, Products, Route Planning, Reports, Target,
Outlet, Credentials, Astha Gift Panel, Tutorial, Performance Leaderboard, Superstar Program, Daily Tracking
Dashboard. The brief's capture (user `mamun`, 2026-10-21) shows two more entries, **Indent Management** and
**Data Entry**, and no Daily Tracking Dashboard: the menu is per user (module grants), which is exactly what
the owner's "Admins with selective parts" level needs (D-ROLES-02).

## Controls

| Control | Values / behaviour |
|---|---|
| Wing, Division, Territory, House, Zone | multi-select, "All Selected (n)" with a clear × and a chevron; counts 10 / 50 / 291 / 295 / 1051; default = everything in the user's scope |
| Category, Segment, Brand, Variant | multi-select, default All; drive the product-based cards only |
| Date | one day, `YYYY-MM-DD` with a calendar icon; default today |
| Apply Filter | yellow button with a funnel icon, right-aligned; cards refresh only on click |
| "i" (sales cards) | round blue button, bottom right of each sales card: daily target pop-up |
| gear (Breakdown) | menu with two groups: By STD (Segment, Brand, Variant) and By Memo (Segment, Brand, Variant); one active |
| Filter by Color (FF Geo) | multi-select (All Colors; Red, Amber, Green) on the map card |
| Map controls | Google Maps: fullscreen, pan, zoom, keyboard shortcuts |

## Figures decoded (2026-10-22 capture) and arithmetic checks

| Card | Figures | Check |
|---|---|---|
| Sales (Cigarette) | 3.64 Cr Total Sales, ring 48.5% | implied daily target 7.5 Cr sticks |
| Sales (Bidi) | 34.88 Lakh, 38.3% | implied 91 Lakh |
| Sales (Lighter in Pcs) | 1.06 Lakh, 80.1% | implied 1.32 Lakh |
| Sales (Match in Dozen) | 11,165, 17.3% | implied 64,500 dozen |
| Live CPR | 4.60 Lakh Target Outlet, 1.74 Lakh Successful Calls, 37.9% | 1.74 / 4.60 = 37.8% (rounding of the displayed lakh values) |
| By Channel Successful Call | GT 1.36 Lakh (78.7%), DCC 2,786 (1.6%), Astha 29,894 (17.3%), RCC 3,835 (2.2%), MT 124 (0.1%), HoReCa 41 (0.0%) | sum 172,680 vs 1.74 Lakh successful calls: about 1,300 calling outlets have no channel in the master (the brief's gap; channel becomes mandatory) |
| Final Submit Status | Total Zone 1,051, Total Service Zone 1,046, Remaining 1,046, Total Final Submit 0, 0.0% | 1,046 − 0 = 1,046 |
| Login/Submit Status | Login 99.8% (6,933 Target Route, 6,920 Successful Login); Submit 0.1% (6,920 Login successfully, 6 Submitted successfully) | 6,920 / 6,933 = 99.81%; 6 / 6,920 = 0.09% |
| By Channel STD | GT 2.64 Cr (72.5%), DCC 5.60 Lakh (1.5%), Astha 85.48 Lakh (23.5%), RCC 8.90 Lakh (2.4%), MT 16,780 (0.0%), HoReCa 6,260 (0.0%) | sum 364.2 Lakh = 3.64 Cr = Sales (Cigarette): cigarette only, as the brief says |
| Breakdown – STD / **Memo** (By Memo, By Brand) | Maxim 14,850; Avon 4,905; ARIS 9,446; Marise 1.06 Lakh; Black Diamond 1.47 Lakh; Supreme 7,393; Special Abul Bidi 6,832 (bar) / 24,277 (orange bar); Ananda Bidi 5,101; Abul Bidi Gold 413; Abul Bidi Style 2,450 / 1,401; Flame Box 7,735; Salmon 536; ESSE 4,061; MAX 157 / 1,583 | memo counts per brand, not additive across brands; the x-axis labels overlap in the original (14 brands), ours must not |
| Geo Fencing | 1.96 Lakh Geo Validation, 2.00 Lakh Total Calls, 97.96% | 1.96 / 2.00 = 98% |
| FF Geo Location | clusters of 474, 893, 167, 340, 272, 292, 1449, 1397, 203, 966, 458 pins over Bangladesh; red clusters, magenta for the two largest | one pin per route (6,933 routes) |

## Differences from the current spec (`UI-WEB-nn`)

| Id | Finding | Consequence |
|---|---|---|
| UI-WEB-01 | The sidebar menu differs per user (Indent Management, Data Entry, Daily Tracking Dashboard appear for some users) | per-module grants per user (D-ROLES-02); the menu is built from the grants in the token |
| UI-WEB-02 | Ten filter selects plus a date and an explicit Apply; House level exists between Territory and Zone | filter panel component with House; the house list is active-only and de-duplicated (UI-WEB-14) |
| UI-WEB-03 | Sales rings are % of the **daily target**, with an "i" pop-up | the dashboard needs route monthly targets and a working-day calendar: built as the minimum target slice (D-TARGET-MIN-01); the rest of the Target programme stays deferred |
| UI-WEB-04 | Live CPR counts routes scheduled today, excluding manual and dummy BR / Institutional routes | route flags `is_manual`, `is_dummy` (or a route kind) in the master |
| UI-WEB-05 | Channel split uses the outlet master channel (GT, DCC, Astha, RCC, MT, HoReCa) and no "Other" | `outlet.channel` NOT NULL; the Astha **channel** is an outlet attribute, not the loyalty programme (docs/27 still defers the programme) |
| UI-WEB-06 | Final Submit counts service zones and ignores second submits; midnight auto-submit | final submit log keyed by (zone, business date), first submit wins; a midnight job |
| UI-WEB-07 | Login = check-in **and** download; Submit = Bikroy Joma done | download/upload log per route per day |
| UI-WEB-08 | By Channel STD is a 3D pie in the original | ours is a flat donut/pie per the `dataviz` skill, same colours and legend order |
| UI-WEB-09 | Breakdown measure and dimension from a gear menu; STD mode mixes units | STD mode limited to one category or unit label per bar |
| UI-WEB-10 | Geo Fencing counts completed calls with or without a memo | call record with `within_fence` and `completed` |
| UI-WEB-11 | FF Geo map: pin per route at the SR's latest position, colour by today's geo-fencing %, Filter by Color, pop-up | user location log (latest position per route), cluster rendering |
| UI-WEB-12 | Route map page ("Map Details") with five marker kinds, path line, four cards | new route `/dashboard/route-map/[routeId]?date=` |
| UI-WEB-13 | Sale Position page per outlet and day (hit/miss, three sale marker kinds, force-sale photo) | new route `/dashboard/sale-position/[outletId]?date=`; haversine distance stored on the call |
| UI-WEB-14 | 295 houses listed vs 239 real: duplicates and deactivated | `house.is_active`; dedupe by (territory, name); the filter lists active houses once |

## PII rule applied

The filed captures hide: the login names (`asef`, `mamun`), the route pop-up (SR name and phone), the SR name
in the Map Details header, and the outlet pop-up (outlet name and phone). Everything else is aggregate or
place names. The originals stay in the owner's upload only.
