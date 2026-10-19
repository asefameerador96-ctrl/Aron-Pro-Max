# UI parity: web dashboard and portal (lane web-dashboard, 2026-10-18 night)

Directive: CLAUDE.md 14a and `docs/ui-reference/PARITY-SPEC.md` s4 (on `lane/lead-docs-night` until INT has it). Reference: **text only**, `docs/evidence/web-dashboard-reference-2026-10-17.md`. No web reference image is in git (only the SR images under `docs/ui-reference/sr/`), so the side-by-side PNGs are named `-noref`: ours, to be read next to the description. If the owner drops the dashboard screenshot into `docs/evidence/`, the pairs can be made properly.

Proof pictures (light, English; Bangla at 1366): `docs/evidence/ui-parity/web-home-1366-noref.png`, `web-home-820-noref.png`, `web-home-1366-bn-noref.png`. Data in them is the contract-typed mock; the home has not been exercised against live dev from this lane (no credentials).

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
