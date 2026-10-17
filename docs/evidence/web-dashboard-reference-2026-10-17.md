# Reference: the current (Apsis) portal dashboard, owner screenshot 2026-10-17

The owner sent a screenshot of the live dashboard (user `asef`, a national-level login) on 2026-10-17 and wants the new web dashboard to match it in layout and function, "complete in design and functionalities", and better where that costs nothing. The image is not in git; this is the lead's description of it, top to bottom.

**Shell.** Left sidebar, full height, vertical gradient crimson (top) to purple (middle) to crimson (bottom), white labels with line icons, collapse chevron (`<<`) at the top right of the sidebar, round logo at the top, red **Logout** button at the bottom. Menu: Dashboard, Retailer (sub-menu), Products (sub), Route Planning (sub), Reports (sub), Target (sub), Outlet (sub), Credentials, Astha Gift Panel (sub), Tutorial, Performance Leaderboard, Superstar Program (sub), Daily Tracking Dashboard. Top bar: page title "Dashboard" in blue, user avatar and name at the right. Page background light grey; content is white cards with a blue title and a thin blue rule under it. An orange **Filter** button (list icon) sits top right above the cards.

**Row 1: four sales cards** (same shape): title `Sales (Cigarette)`, `Sales (Bidi)`, `Sales (Lighter in Pcs)`, `Sales (Match in Dozen)`. Left: the total in Lakh or Crore (`3.61 Cr`, `65.07 Lakh`, `1.11 Lakh`, `28,067`) and the words "Total Sales" in cyan. Right: a large ring gauge (blue to cyan gradient on a grey track) with the percentage in the middle (60.8%, 82.2%, 78.0%, 68.7%) which is achievement against target; small round info (i) button at the bottom right of the card.

**Row 2: four status cards.**
- `Live CPR`: green ring (59.3%) with `4.64 Lakh Target Outlet` and `2.75 Lakh Successful Calls` at its left.
- `By Channel Successful Call`: a list of channel counts with share (2.15 Lakh (78.3%) GT Channel, DCC, Astha, RCC, MT, HoReCa) each in its own colour, and a concentric radial chart at the right.
- `Final Submit Status`: left column Total Zone / Total Service Zone / Remaining (1,051 / 1,046 / 0), right a ring 100.0% with `Total Final Submit 1,046`.
- `Login/Submit Status`: two horizontal progress bars (Login Status 99.9%, Submit Status 99.8%) with the pairs Target Route / Successful Login and Login successfully / Submitted successfully, each with a small download or upload icon.

**Row 3.** Left card `By Channel STD`: a 3D-style pie (GT 71.2% blue, Astha 24.8% orange, other channels thin slices) with a colour-coded legend of values and shares at its left. Right, wider card `Breakdown - STD / Memo` (a toggle in the title, gear icon at the right): a vertical bar chart by brand (Maxim, Avon, ARIS, Marise, Black Diamond, Supreme, Special Abul Bidi, Ananda Bidi, ...), one colour per bar with gradient, value labels on top, y axis in Lakh.

**Row 4.** Left card `Geo Fencing`: green ring (97.28%) with `3.28 Lakh Geo Validation` and `3.37 Lakh Total Calls`. Right, wide card `FF Geo Location`: a Google map with clustered red and magenta markers carrying counts, a `Filter by Color` select (All Colors), a fullscreen button and zoom controls.

## What it means for the build (lead's rulings)

- Keep this layout and information design: sidebar shell, four sales cards, four status cards, channel pie, brand breakdown bar chart with a STD/Memo toggle, geo-fencing ring, team map with colour filter, the Filter (scope) button. Improve visual quality with the Calm Glass web tokens (`docs/design/web-glass.md`); a bit more colour is welcome (the owner liked the vibrant palette), numbers on solid surfaces, light and dark.
- **Deferred programmes (docs/27, binding):** the menu entries Target, Astha Gift Panel and Superstar Program, the Astha channel breakdown that is a programme and every target-based achievement percentage are NOT built: keep hooks only. So the four sales rings cannot show achievement against a target: show the total and a trend against the same weekday last week (or the previous period) in the ring's place, and keep the ring component ready for the day target comes back. Live CPR keeps "Successful Calls / Target Outlet" only if "Target Outlet" means the planned outlets of the routes (it is route-plan data, not the deferred target programme); otherwise show calls made / outlets planned.
- Units: cigarettes in sticks, lighters in pieces, matches in dozens; lakh/crore display for large numbers with Bengali digits in Bangla mode; money from integer milli-taka.
- Every number comes from the report engine and the scope token (the national, zone, DMO and TSO views differ only by scope).
- Check each card against the contract; where the data is missing file a request to backend-reports with the report key and columns.
