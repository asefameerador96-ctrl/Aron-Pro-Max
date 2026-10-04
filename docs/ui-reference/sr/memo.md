# SR App — Memo (reference)

Image: **not stored** in this repo (it shows an outlet's phone number); redact and add it as `memo.webp` if wanted. Manual references and existing gaps: G-man-002 (memo money), G-man-004 (slide/DRP), G-man-014 (printed memo), G-man-012 (edit), `docs/06` steps 4d and 6.

## Business rule stated by AKTCL (2026-10-05)

The Memo menu lets the SR select an **already visited outlet**, check its memo and **reprint** it.

## What the screen shows

| Element | Shown | Reading |
|---|---|---|
| Title | `মেমো` | Memo |
| Info banner | `আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন।` | "You can select an outlet and view a memo. You can reprint the memo from here." |
| Printer icon | red crossed printer over the banner | printer not connected |
| Outlet selector | four dots, `<name> (<code>-<phone>-<cluster>)`, then `: ৳ 84.00` | selected outlet with **the memo total** beside it |
| Alphabet filter | `All A B E J M N R S T a b c j k o s t` | as on the Sale screen |
| Items table | columns `এসকেইউ` / `পরিমাণ` / `দাম` (SKU / Quantity / Price); row `FB 3 84.00`; subtotal `মোট ম্যাচ` 3 84.00 | Flame Box, 3 dozen at 28.00 |
| Discount section | dark tab `ডিসকাউন্ট`; columns SKU / Quantity / `মূল্য` (Value); row `SL Match 3 0`; subtotal `মোট` 3 0.00 | a second table of discount lines in **quantity and value** |
| Totals | `মোট ডিসকাউন্ট − 0.00`, `মোট QC − 0.00`, `সর্বমোট 84.00` | total discount, total QC, grand total (net) |
| Buttons | red `প্রিন্ট` (Print), amber `এডিট` (Edit) | reprint and edit |

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-26 | The memo has a **Discount table in quantity**: `SL Match`, 3 units, value 0. The Home card showed a discount of 437.50, which equals exactly 35 lighters at 12.50. | The spec models a money discount only (`memo.discount_minor`, `memo_line.discount_minor`). The screen suggests offers can be **goods given in quantity** (free or bonus units) with a value that may be 0. Three readings fit parts of the evidence: (H1) in-kind free goods, valued at list price in the discount total; (H2) a discount amount per SKU with the quantity it applies to; (H3) offer-eligible quantity with no discount applied. The Match arithmetic (stock fell by 6 = FB 3 + SL 3, Home Match value 141 = 84 + 57) does not settle which. **`MUST-CONFIRM` with AKTCL: what does the Discount section list, and what does SL Match 3 / 0.00 mean?** Until then the schema should store discount lines as `(sku, qty, value, kind)` so any of the three works. | docs/16, 10 |
| UI-SR-27 | The totals block is `total discount`, `total QC`, `grand total`. | Confirms the money model in `home.md` (UI-SR-04..06, G-man-002): gross − offer discount − DRP/slide − QC settlement = net. Labels must be unique per term (UI-SR-07). | docs/16 |
| UI-SR-28 | The selector shows the **memo total next to the outlet** and the screen lists only visited outlets with a memo. | The list is a query on local memos for the business date. Works offline; multiple memos per outlet per day exist (`docs/22` P-06), so the selector needs memo number or time to disambiguate. | docs/17 |
| UI-SR-29 | Reprint and Edit sit on the same screen. | Edit rules (inside the geofence, before QC, one of the listed reasons) stay as in `docs/06` step 6 and G-man-012. A reprint must be marked **"duplicate"** on paper so a retailer cannot be billed twice from one slip; `MUST-CONFIRM` what the current printout shows. | docs/17 |
