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
