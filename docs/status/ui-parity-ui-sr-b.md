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
| Sales summary | sr/summary.png, `sr-summary.png` | DONE (content) | Host top bar (icon, printer) is wired by app-sr (ui-sr-a); `printerBadge` slot is ready. Lead QA applied (taka glued on value cells, none on the discount line and total, '(-)' and '-437.50' on one line, category and total block docked on its own #F0F0F0 panel, caption removed). Quantity carries its unit as a 10 sp suffix (UI-SR-33). Info banner border, printer circle clip and table header size are kit-owned. Discount line zero while docs/27 holds. | see git log |
| Sales submit | sr/sales-submit.png, `sr-submit.png` | DONE (content) | Reference shows the clean state; ours adds gate notices (unsynced, mismatch, dues) only when they apply. The table shows the five legacy rows (outlet, sale, stock, QC, promotion) by record counts (`legacyFiveRows`); the exact per-type gate still reads every row. The reference shows quantities and the promotion amount in those rows; our device/server columns are record counts (docs/16 UI-SR-36). Red notice verbatim; last-contact line and the all-matched card removed. Hold sheet and dues dialog still use the old kit button and dialog (press-and-hold is not in the parity kit yet). | see git log |
| Outlet menu | sr/outlet-menu.png, `sr-outlet-menu.png` | DONE | Exactly the four tiles. 'Add to my route' moved into the change-information form (link under the fields), 'My requests' onto the Tasks screen (link at the bottom); app-sr wiring done in SrApp (3 lines, ui-sr-a please keep). | see git log |
| Outlet forms (new shop, close, info, cluster, add to route), Force Sale with GEO and photo block, My requests | -noref, `sr-outlet-forms-noref.png` | DONE (layout from the shell, no reference image) | Built in the same language as the reference screens; owner screenshot welcome. Visit check and retailer picker are ui-sr-a's. | see git log |
| Memo list + detail + sale history | memo.md (no image), `sr-memo-noref.png` | DONE (layout from memo.md) | No reference image. The memo selector with the A-Z letter filter of memo.md is not built: the screen lists today's memos (docs/16 UI-SR-28). Edit is amber, Mark paid green. | see git log |
| Tasks | sr/tasks.png, `sr-tasks.png` | DONE | Empty text with 'করেননি'; the synced-at line only when the last sync is older than 6 h or never. Reference shows only the empty state; the card list and the tabs are our addition in the same language. | see git log |
| Login, OTP, enrolment, location notice | -noref, `sr-login-otp-noref.png` | DONE (layout from the shell) | No reference image; gradient brand card instead of the vendor logo. Language and sun toggles keep the old kit look. | see git log |
| Tutorial list | sr/ (none) | NOT MINE | Lives in app-sr TutorialHost and feature-home OnboardingScreens (ui-sr-a). | |
| Settings gear page | sr/ (none) | NOT FOUND in my modules | Asked ui-sr-a; no composable in feature-memo, dayclose, outlet, tasks or auth. | |
| Photo capture | -noref | DONE | The capture block (`GeoPhotoBlock`) is restyled; the camera itself is the system camera. | see git log |

## Kit components now used (cycle 2, 2026-10-19)

On the kit (no private copies left for these): text field (`AronField`: Login, enrolment, outlet request forms; needs kit 89da3bf), choice chip (cluster choices), radio row (Force Sale reasons), error text, solid buttons (amber Edit, green Mark paid), purple text link (memo history, banner dismiss, notice actions, auth links), tabs (Tasks ongoing/completed), bottom sheet and press-and-hold confirm (Sales submit hold step), notice card with action (Sales submit mismatch), info and notice cards, section header (memo today, history dates), language and Sunlight toggles (Login, OTP, enrolment, location notice).

Still private, nothing in the kit yet: `FormCard`/`WhiteCard`/`AuthCard` (white 12 dp card), `FormBar`/`AuthBar` (the primary and grey secondary row above the keyboard with test tags), `OtpBoxes` (four-digit boxes), the memo and task rows. The dues `AronConfirmDialog` (Sales submit, memo Mark paid, outlet request confirm) is still the old dialog; the kit has `AronDialog` in Forms.kt for a later cycle.

## Kit requests (still open)

1. A white card container (`AronCard`) for the grey page.
2. An action row with test tags (primary and secondary at 60/40 above the keyboard).
3. A four-digit OTP box row.
4. **SummaryTable**: category subtotal labels shrink to a few sp in the first column at 360 dp.
5. Memo selector: a retailer drop-down with the A-Z letter filter (memo.md), if the owner wants the memo screen to select an outlet before showing its memo.
