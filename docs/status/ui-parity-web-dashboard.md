# UI parity: web dashboard and portal (lane web-dashboard, 2026-10-18 night)

Directive: CLAUDE.md 14a and `docs/ui-reference/PARITY-SPEC.md` s4 (on `lane/lead-docs-night` until INT has it). Reference: **text only**, `docs/evidence/web-dashboard-reference-2026-10-17.md`. No web reference image is in git (only the SR images under `docs/ui-reference/sr/`), so the side-by-side PNGs are named `-noref`: ours, to be read next to the description. If the owner drops the dashboard screenshot into `docs/evidence/`, the pairs can be made properly.

Proof pictures (light, English; Bangla at 1366): `docs/evidence/ui-parity/web-home-1366-noref.png`, `web-home-1920-noref.png`, `web-home-820-noref.png`, `web-home-1366-bn-noref.png`. Data in them is the contract-typed mock; the home has not been exercised against live dev from this lane (no credentials).

| Part | Reference | Status | sha |
|---|---|---|---|
| Shell: sidebar gradient crimson #D5484A / purple #4B1C97 / crimson, white labels with line icons, round logo, collapse chevron top right, red Logout at the foot | yes | DONE (every page: dashboard, reports, admin, config; drawer below md uses the same gradient; collapsed rail keeps the icon-only red Logout) | d6227f65, goldens 453cb3c4 |
| Top bar: page title in blue, avatar and name at the right | yes | DONE (title = the menu entry owning the path; the connection chip, language switch and theme toggle stay in the bar: not in the reference, needed) | d6227f65 |
| Light grey page #E8E8E8, white cards, blue titles with a thin rule | yes | DONE (cards 12 px, soft shadow, no gradient) | d6227f65 |
| No glass: no blur, no translucent surfaces, no pastel gradient | directive | DONE (tokens solid in every theme; the blur utilities read `none`; css-contrast test now asserts "nothing blurs"; dark mode kept as an option) | d6227f65 |
| Four sales cards: total, cyan "Total sales" words, big ring blue to cyan, (i) bottom right | yes | DONE except the ring value: share of net sales and a chip vs same weekday last week, never target % (docs/27) | e5c01fdd |
| Live CPR: green ring, planned outlets and successful calls at its left | yes | DONE ("Planned outlets", not "Target Outlet") | e5c01fdd |
| By channel successful calls: list at the left, concentric radial chart | yes | PARTIAL: radial chart and list are there, but at 1366 the card is narrow so the chart sits below the list; side by side at wider widths is not checked | e5c01fdd |
| Final submit: Total Zone / Total Service Zone / Remaining and a ring | yes | PARTIAL: Total zones, Submitted, Remaining and a ring (zones submitted over zones with routes); no "Total Service Zone" (server field missing: `docs/requests/web-service-zone-count.md`) | e5c01fdd |
| Login/submit: two bars with download and upload icons | yes | DONE | 86c793e8 |
| By channel STD: 3D pie, legend at the left | yes | PARTIAL: flat donut, legend first; no 3D (kept flat on purpose: legibility) | e5c01fdd |
| Brand breakdown: gradient columns, value labels, y axis in lakh, STD/Memo toggle, gear | yes | PARTIAL: gradient columns, labels, lakh axis, toggle; **no gear** (it would be a control that does nothing) | e5c01fdd |
| Geo fencing: green ring, figures at the left | yes | DONE ("N of M visits" from `geo_valid_visits`) | e5c01fdd |
| FF Geo Location map with colour filter, clusters | yes | PARTIAL: the map loads on "Show map" (Maps daily cap); clustering and the real look not checked: no Maps key in this lane | earlier |
| Orange Filter button | yes | DONE | earlier |
| Menu: Target, Astha Gift Panel, Superstar Program | yes | NOT BUILT on purpose (docs/27) | n/a |
| Card titles on one line | yes | PARTIAL: narrow cards cut a long title with an ellipsis and a tooltip | 86c793e8 |

Not claimed: live data, a real Maps key, a side-by-side against the owner's actual image.

## Vision-QA batch (lead QA of 2026-10-19, items tagged web-dashboard)
| Item | Status |
|---|---|
| FF Geo Location map in the card | CODE DONE, NOT SEEN WITH REAL TILES: with a Maps key (server-side `MAPS_WEB_KEY`) the map mounts when the card scrolls into view (the daily cap still guards each load); red outlet and magenta SR markers, count markers from a small grid clusterer (`src/lib/maps/cluster.ts`, unit-tested), fullscreen and zoom controls, "Filter by Color" / "All Colors". Without a key the placeholder and "Show map" stay. No Maps key in this lane: the look against real Google tiles is unchecked. |
| Nav tree like the owner's | DONE: Dashboard, Retailer, Products, Route Planning, Reports, Outlet as expandable parents; Credentials, Tutorial, Performance Leaderboard, Daily Tracking Dashboard as single entries; new pages (Sync health, Exceptions, Final Submit, Leave approval, SR Efficiency ...) under the matching parent; Administration and Configuration after them. Performance Leaderboard and Outlet show only for roles that hold those reports. One line-icon set (1.75 px stroke), a distinct glyph per entry, double-chevron collapse, "Logout" label. |
| By Channel Successful Call / By Channel STD | PARTIAL: reference titles; legend left, concentric rings right (calls) and a solid pie right (STD), side by side from the 1366 layout. A 3D bevel was not added. |
| Breakdown - STD / Memo | DONE: toggle inline in the title, gear at the right (it opens the STD memo report: the report behind this chart), two-stop gradients that tint toward white of the same hue. |
| Final Submit Status | PARTIAL: Total Zone (only when this scope's children are all zones, else a dash), Total Service Zone (= zones with routes today), Remaining, ring and "Total Final Submit N"; Submit % and Day completion in a quieter row on one line each. Service-zone count from the server is still requested (`docs/requests/web-service-zone-count.md`). |
| Login Status / Submit Status, Live CPR on one line | DONE |
| Sales rings: same size, headline clear of the ring, "Total sales · Unit" on one line, gradient #1565C0 to #00B8D9, label of what the percentage is | DONE (caption "Share of net sales" under each ring; no target). The bright cyan end is below 3:1 on white; the percentage is printed as text at full contrast. |
| One blue page title; fixed status-card header height; one "As of" stamp; Lakh on Net sales; solid orange Filter | DONE (the duplicate H1 is kept for screen readers only; per-card date and As-of stay in the DOM for screen readers and show when a tile is stale; Filter is #C2570C with white text, 5:1, not #F59E0B which fails AA) |
