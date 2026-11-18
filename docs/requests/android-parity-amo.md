# Requests from lane android-parity-amo (P-AMO2) to the lead, 2026-11-13

The AMO sale entry, review and paid-amount prompt are drawn by the shared `feature-sale` screens (`SaleRoute`, `SaleEntryScreen`,
`ReviewScreen`, `CreditPrompt`) and the shared `core-ui` card (`PackCard`). This lane may not edit those modules, and they have no AMO
parameters for the differences below. Each item is a look/order change only: no sale, stock or money logic changes. Every item
defaults to the current SR behaviour, so SR goldens stay as they are.

Evidence: owner screenshots `docs/ui-reference/amo/23`, `24`, `25`, `26`; our goldens `amo_sale_entry_bn`, `amo_review_bn`,
`amo_paid_amount_bn` (recorded in this lane for comparison).

## R-PAR-AMO-01: an AMO header card on the sale entry (feature-sale, SaleEntryScreen)
Old (23, 24): a white card with a grey outline, a shop-front icon at the left, then two lines: bold "রুট:" + grey route name, bold
"খুচরা বিক্রেতাঃ" + grey `name (code-phone-area)`. Ours: the retailer pill only.
Ask: an optional `header: (@Composable () -> Unit)?` slot (or `routeLabel: String?` plus a `headerCard: Boolean`) on `SaleEntryScreen`
and `SaleRoute`; null keeps the pill. The AMO host passes the route and retailer it already has (`AmoDay.saleHeader()`).

## R-PAR-AMO-02: the right-hand rate / discount / stock column and the blue quantity badge (core-ui PackCard)
Old (23, 24, 26): at the right of each SKU card three lines: a blue cart icon + rate (bold blue), a discount icon + "০%", a box icon +
stock; a round blue badge on the packshot's lower right. Ours: one stock figure; the badge exists (`packBadge`) but the AMO passes none.
Ask: an optional `sideColumn: (@Composable ColumnScope.() -> Unit)?` on `PackCard` (null = today's stock figure), and on `SaleEntryScreen`
an `amoCard: Boolean` (or a per-SKU `badge` and `side` provider) so the AMO shows rate, the discount (always ০% while discounts are
deferred, CLAUDE.md rule 2) and stock. Green round minus/plus (old) versus our red/purple: legibility first, the lead decides; the
green must hold 3:1 against the white card (`primaryStart` #00642F does).

## R-PAR-AMO-03: review: print-later note (feature-sale, ReviewScreen)
Old (24): under the two buttons an info box with an "i" icon: "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।". Ours: none.
Ask: `printLaterNote: Boolean = false` on `ReviewScreen` / `SaleRoute` with the string in the sale string layer (bn and en
"You can print later from the Memo section."). The crossed-out printer on the bar is done in this lane (AmoFrame trailing on the sale
step, D-PAR-AMO-05).

## R-PAR-AMO-04: paid-amount prompt as the old dialog (feature-sale, CreditPrompt)
Old (25): a modal dialog over the dimmed review: bold title, the question, an outlined field with the floating label "আদায়কৃত অর্থ" and
the live "বাকিঃ n" inside the field at the right, then green "হ্যাঁ" and red "না" at the bottom right. Ours (`amo_paid_amount_bn`): a
full page with the total under the field and the buttons in the bottom bar (না left, হ্যাঁ right, disabled until typed).
Ask: an AMO presentation of the same `CreditPrompt` logic (`asDialog: Boolean = false`), same `PaidInput` parsing and bounds.

## R-PAR-AMO-05: review totals with quantities (feature-sale, ReviewScreen)
Old (24, 25): category rows "মোট সিগারেট / বিড়ি / লাইটার / ম্যাচ" and "সর্বমোট" carry the quantity column too; ours has value only and a
doubled "মোট". Ask: quantities on the category and grand-total rows when the host asks (`totalsWithQty: Boolean = false`).

## R-PAR-AMO-06: AMO totals-panel column words to 7:1 (core-ui look token, for P-SR1)
`AronLook.AmoPurple.onPanelMuted` (0xE6DDF7) on the blue end of the AMO totals-panel ramp (`panelTop` 0x0B4EA2) measures 6.1:1; the figures (white) measure 8.0:1 and the indigo end 8.8:1. The words are small column heads, not key figures, so `AmoStockContrastTest` pins AA (4.5) today. Ask: lighten `onPanelMuted` in the AMO look (for example 0xF1EBFB, about 7.2:1) and tighten the test to 7. Not changed in this lane (core-ui belongs to P-SR1).

## R-PAR-AMO-07: TotalsPanel hides the AMO blue-to-indigo ramp (core-ui Tables.kt, for P-SR1)
`TotalsPanel` draws `.background(look.panel)` (solid 0x4A1A96) over the host Column's `panelBackground(look)` ramp, so on the AMO Stock screen only the button row shows the screenshot-41 ramp (0B4EA2 to 3A1E99); the totals block above it is flat purple (pixels in `amo_stock_connected_saved_bn.png`: top of panel 74,26,150). Ask: `TotalsPanel` uses `panelBackground(look)` (the same function the Column uses) so a look with `panelTop` set shows the ramp and every other look keeps the solid panel (SR, TSO, Sunlight unchanged). `AmoStockContrastTest` already pins both ends of the ramp at 8.0:1 and 11.5:1 for the figures.

## R-PAR-AMO-08: the AMO corrects shop name, owner and mobile while verifying (LOGIC round with the server lane; lead ruled 2026-11-17: it WILL be built)
Raised by P-AMO3; no look change is made for it in the look lane. No personal data is quoted: the examples are the owner's screenshots' placeholder shops, described by role only.

**What the old screen shows** (screenshots 47 right "নতুন দোকান", 52 right "তথ্য পরিবর্তন করুন"; manual p48 does not mention it, `docs/ui-reference/amo/47-*.md` line 99 lists it as an open question, now answered by the lead):
- Under the route and cluster drop-downs, three labelled outlined fields, purple border, typed text: "দোকানের নাম" (shop name), "দোকানের মালিকের নাম" (owner name), "মোবাইল নম্বর" (mobile, Latin digits with the leading 0). Their values are the SR's proposal, pre-filled.
- On the NEW-shop and CHANGE-information forms they are EDITABLE. On the permanent-close form (50 right) they are read-only grey (disabled look), so only those two request kinds take edits.
- Then Sub-Channel and Geo Classification drop-downs (already built), then red "বাতিল" (reject) and green "সংরক্ষণ" (save, approve) as built in D-PAR-AMO-14.
- No validation message is visible in the screenshots. The own-request forms of this app already use the shared rules for these three fields (`RequestError.NAME_INVALID` 2 to 120 characters, `OWNER_INVALID`, `MOBILE_INVALID`: `feature-outlet/.../OutletRequest.kt:49,111`, mapped to the form errors in `feature-amo/.../OutletForms.kt:39-41` and shown by `shopTextItems`, `OutletForms.kt:80`), so the same validator is the proposal for this form.
- Save approves WITH the corrections; Cancel rejects and carries none.

**Where our screen and writer are today**
- Screen: `app-amo/.../AmoOutletVerifyHost.kt:193` builds the three rows with `shopReadOnlyItems(...)` (`feature-amo/.../OutletForms.kt:105`, read-only `AmoFormItem.ReadOnly`). The editable kit already exists: `ShopText` (`OutletForms.kt:62`) and `shopTextItems` (`OutletForms.kt:80`, used by the own new-shop and change-info forms). The verify host needs local state seeded from the proposal, the same validator, and the three values in the decision call.
- Local validation: `OutletVerify.validate(...)` (`feature-amo/.../OutletVerify.kt:100`) knows route, cluster, sub-channel, geo class and photo only.
- Writer: `AmoRecordWriter.decideRequest` (`app-amo/.../AmoRecordWriter.kt:59`) takes `VerifyChoice` (`app-amo/.../VerifyDecision.kt:7`: verify, subChannelId, geoClass, photoUuid, fix, routeId, clusterId, newOutlet). The record uuid is fixed when the form opens, so a replay queues one record; a decided request is not queued twice (`decidedRequests()` guard, line 63).
- Payload builder: `AmoPayloads.outletRequestVerification` (`core-database/.../record/AmoPayloads.kt:98`).

**What the verify payload carries today** (`outlet_request_verification`, shape in `backend/sync/.../PayloadShapes.kt:41`, server handling `backend/sync/.../RecordWriter.kt:60`): `request_uuid`, `decision` (`verified` | `discarded`), `fix` (required), and optional `sub_channel_id`, `geo_class`, `photo_uuid`, `note` (max 500), `route_id`, `cluster_id` (contract 1.11.0; the route and cluster columns mean "the form changed it", only the payload says so, never the envelope). Nothing carries a corrected name, owner or mobile; the server trail records the event, the actor, the time and the changed route and cluster only.

**What the logic round needs (server lane + app, for the lead's design)**
1. Contract: optional `shop_name`, `owner_name`, `contact_number` on the verification payload (only the values the AMO changed, or all three with the proposal as the baseline: decide), added to `PayloadShapes` and the OpenAPI/contract version, forward-only; the app sends them only on `verified` for kinds `new` and `info`, never on `close`.
2. Server: validate with the same text rules as the SR request (length, mobile normalisation to the 11-digit form), scope-check the request to the AMO's zone as the decision already is, apply the corrected values to the outlet created or changed by the approval (not to the SR's stored request row, which stays as the SR proposed), and keep an audit trail that records old value, new value, actor and time per corrected field (the request trail is the place, next to `event`, `route_id`, `cluster_id`).
3. Idempotency: the decision stays one record per request uuid; a replay with the same corrected values is a no-op, a replay with different values after the decision is refused (the existing "decided" guard), so a retried upload never applies two corrections.
4. App: seed the three fields from the proposal, validate before Save, include the corrected values in the decision, show the same field errors as the own forms; tests for kill-and-relaunch (the draft of the form keeps the typed values), replay and the three kinds (new, info, close).
5. Tests on the server: scope, validation, audit rows, replay, and that a close decision ignores the fields.
