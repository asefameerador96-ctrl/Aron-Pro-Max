# SR App — Sale: choosing the retailer (reference)

Image: `sale-select.png` (empty state with the alphabet filter). A second capture, the open outlet list, is **not stored** in this repo because it shows outlet phone numbers; the layout is described below. Manual references and existing gaps: G-man-005, G-man-008, G-man-037, `docs/05`, `docs/06` day-flow step 4.

## Business rules stated by AKTCL (2026-10-05)

1. **Geofence gate.** When the SR is at a shop and starts selling, they cannot sell unless they are within the geofence radius. The app checks the outlet's fixed latitude/longitude against the SR's location.
2. **The outlet list is the SR's route for that day.** There are three route kinds: **Daily** (visited every day), **3F** (three times a week) and **2F** (twice a week).
3. **Coloured dots before each outlet** show whether the outlet is eligible for a discount or promotional programme.
4. Once SKUs and quantities are chosen, the memo is printed on the Bluetooth printer.

## What the screens show

| Element | Shown | Reading |
|---|---|---|
| Title | `বিক্রয়` | Sale |
| Retailer picker | rounded field, placeholder `রিটেইলার নির্বাচন করুন`, drop-down arrow | "Select retailer" |
| Alphabet filter | dark bar: `All A B E J M N R S T a b c j k o s t` | one chip per **first letter present** among the route's outlet names. Upper and lower case are **separate chips** (`S` and `s`, `T` and `t`). |
| Empty state | `বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন` | "Select a retailer to continue the sale" |
| Open list (not stored) | each row: four coloured dots, then `<name> (<code>-<phone>-<cluster>)`; two codes formats on one list (`DHK-344-003` style and 7-digit); the phone appears with and without a leading 0 | label format `name (code-phone-cluster)`; clusters `Apsis Cluster` and `Test cluster` |

The four dots are red, green, magenta and a small round emblem that looks like the Astha logo. In this test route every outlet shows all four.

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-21 | **Route kinds Daily / 3F / 2F** are stated by AKTCL, but the data export has no such column: the visit days are written into the route name (`Name(Sun, Tue, Thu)`, `Name(Mon, Thu)`, `NameDaily`). Parsed from the 1 Oct retailer list: Daily 3,242 routes; 3F 6,083 routes in two alternating groups (Sun/Tue/Thu 3,041 and Sat/Mon/Wed 3,040); 2F 2,009 routes in three groups (Mon/Thu, Sun/Wed, Sat/Tue, ~670 each). | The new system needs `route.visit_kind` (`daily`, `3f`, `2f`) and `route.visit_days` as real fields, filled by the importer from the name and editable in the admin panel. "Today's route" = the routes whose days include the business date and which fall on a trading day (`docs/22` P-03). About 6,953 routes and ~460k outlets are planned per trading day; see `docs/22` P-17. | docs/16, 19, 22 |
| UI-SR-22 | The **eligibility dots** are four per outlet. Colours stand for programmes; the fourth looks like Astha. Which colour is which programme is not stated. | The bundle must carry per-outlet eligibility flags (Astha tier, Diamond League, Superstar, promotion groups). Add `cfg.ui.outlet_badges` (colour, programme, label) so the legend is admin-editable. `MUST-CONFIRM` the colour-to-programme mapping and whether a dot may be hidden when not eligible (here all four always show). | docs/16, 19 |
| UI-SR-23 | The alphabet filter is **case-sensitive** (`S` and `s` are different chips). | A bug, not a rule. Parity keeps the letters but treat case-insensitively; for Bangla names use the first Bangla letter. Recorded as a deliberate small fix. | docs/17 |
| UI-SR-24 | The list label shows the phone number, and the format varies (10 digits without the leading 0 on some rows, 11 digits with it on others). | Normalise phone numbers on import and display (G-man-037). The phone is PII (`docs/22` P-12): the list is visible only to the route's SR and supervisors. | docs/21 |
| UI-SR-25 | The geofence gate comes **after** picking the outlet (the picker has no distance shown). | Matches `docs/05`. Because 80% of outlets share a ~55 m cell with another (`docs/22` P-10), the picker should sort by distance when a fix exists, to avoid selecting a neighbour. This is an improvement, not parity: default off, on by `cfg.sale.sort_by_distance`. | docs/05, 19 |
