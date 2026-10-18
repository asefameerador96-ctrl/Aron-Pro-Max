# UI parity, lane ui-sr-b (SR: memo, summary, submit, outlet, tasks, auth, tutorial, photo, settings)

Owner directive 2026-10-18 (CLAUDE.md rule 14a, `docs/ui-reference/PARITY-SPEC.md`). Presentation only: domain logic, offline, idempotency and printing are untouched.

## Handover block (read first after a recycle)

- Branch `lane/ui-sr-b`; lead `session_01MbUQSxrP7AB9tbyANjUTPS`; kit lane `ui-kit` owns `android/core-ui` (never edit it, ask them).
- Local Gradle cannot resolve: CI is the compiler. Read failures from the `android-test-reports` artifact.
- `HardcodedStringScanTest`: no prose literals in `Text`; strings in the catalogue (values and values-bn).
- Hosts draw the top bar (`AronTopBar`, app-sr wiring owned by ui-sr-a); my screens are the content slot. Ask ui-sr-a for title/icon/printer-slot wiring.
- Push order: (1) Sales summary, (2) Sales submit, (3) Outlet menu + forms, (4) Memo view and reprint, (5) Tasks, (6) Login, enrolment, OTP, settings, tutorial list, photo capture.

## Element lists (from the references)

### 1. Sales summary (`sr/summary.png`)
Top bar: server-rack gradient icon, `বিক্রয় সারসংক্ষেপ`, printer slot. Banner card (white, hairline, info glyph, text `আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে`) with the round grey crossed-printer well at its right when no printer. `SummaryTable`: dark header `#4D4D4D` (memo, quantity, value, discount, discount value, return); per SKU a purple 20 sp name over a numbers row, alternating white / `#F0F0F0`; category subtotal rows (purple, name then numbers); block `Discount and others (-)` (purple bold, minus, zero while docs/27) and `Grand total` (purple bold, 26 sp); gradient Print button (full width, purple to red in the reference, printer glyph). Print disabled style when empty. Units beside quantities stay (docs/10).

### 2. Sales submit (`sr/sales-submit.png`)
Top bar shield icon `বিক্রয় জমা`. Right-aligned `ডিভাইস স্ট্যাটাস` + green dot + `অনলাইন` (offline: grey dot + offline word). Table: purple header rounded `বিষয় / ডিভাইস / সার্ভার`, white rows with hairline. Red notice card (pink `#E6C8CA`, red left edge). Gradient `ডাটা সিঙ্ক করুন` button; grey `বিক্রয় জমা` button disabled until the gate. Kept: three-step row becomes plain, gate banners, hold sheet (solid), settled state.

### 3. Outlet menu (`sr/outlet-menu.png`)
Top bar shop icon `আউটলেট`. Three-column grid of `#F5F5F5` tiles, large gradient line icon over a red label (`#D0434A`): new shop, permanent close, change info, cluster. The route-add and my-requests actions follow below as white rows. Outlet forms: same shell (grey page, white cards, gradient primary button).

### 4. Memo (`sr/memo.md`, no image)
Banner with crossed printer; retailer picker with letter filter and the `<name> (<code>-<phone>-<cluster>) : ৳ total` line; items table (SKU / quantity / price, subtotal `মোট ম্যাচ`); dark `ডিসকাউন্ট` tab; totals; red Print, amber Edit.

### 5. Tasks (`sr/tasks.png`)
Top bar `টাস্ক` (no icon). Grey page, centered empty text. Task cards white 12 dp, purple title, status chip.

### 6. Login, enrolment, OTP, settings, tutorial, photo: no reference image (`-noref`), built in the same language: gradient top bar, grey page, white cards, gradient primary buttons.

## Parity table

Proofs: `docs/evidence/ui-parity/sr-<screen>.png` (left reference, right ours, Bangla light, 360 dp). Local build works (SDK installed in the lane container); feature-memo, feature-dayclose, feature-outlet and ui-screenshots tests pass locally.

| Screen | Reference | Status | Differs | sha |
|---|---|---|---|---|
| Sales summary | sr/summary.png, `sr-summary.png` | DONE (content) | Host top bar (icon, printer) is wired by app-sr (ui-sr-a); `printerBadge` slot is ready. Category subtotal labels shrink in the narrow first column. Values show no taka sign (header-less, density). Discount line zero while docs/27 holds. | see git log |
| Sales submit | sr/sales-submit.png, `sr-submit.png` | DONE (content) | Reference shows the clean state; ours adds gate notices (unsynced, mismatch, dues) only when they apply, plus the reconciliation rows by record type (legacy five rows kept for the grouped view). Hold sheet and dues dialog still use the old kit button and dialog (press-and-hold is not in the parity kit yet). | see git log |
| Outlet menu | sr/outlet-menu.png, `sr-outlet-menu.png` | DONE | Our two extra actions (add to route, my requests) sit under the tiles as gradient buttons; the reference has only the four tiles. | see git log |
| Outlet forms | -noref | NOT STARTED | | |
| Memo list + detail | memo.md (no image) | NOT STARTED | | |
| Tasks | sr/tasks.png | NOT STARTED | | |
| Login / enrolment / OTP | -noref | NOT STARTED | | |
| Settings, tutorial, photo capture | -noref | NOT STARTED | | |
