# SR App — Sales Summary (reference)

Image: `summary.png`. Test account `sr334001`. Manual references and existing gaps: G-man-002 (memo money), G-man-014 (printed layouts), G-man-054 (Summary tile has no manual page); `docs/06` Home tile `Summary`.

## Statement by AKTCL (2026-10-05)

> This is the sales summary of an SR; he can check it any time of his day.

## What the screen shows

| Element | Shown | Reading |
|---|---|---|
| Title | `বিক্রয় সারসংক্ষেপ` | Sales summary |
| Banner | `আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে` + crossed printer | "Your whole day's sales details will be here"; printer not connected |
| Columns | `মেমো` / `পরিমাণ` / `মূল্য` / `ডিসকাউন্ট` / `ডিসকাউন্ট মূল্য` / `ফেরত` | Memo / Quantity / Value / Discount / Discounted value / Return |
| Row Aster | 4 / 375 / 4,687.50 / 437.50 / 4,250.00 / 25 | lighter SKU |
| Row FB | 1 / 3 / 84.00 / 0.00 / 84.00 / 197 | Flame Box (match) |
| Row SL | 1 / 3 / 57.00 / 0.00 / 57.00 / 197 | Salmon (match) |
| Category row Lighter | 375 / 4,687.50 / 437.5 / 4,250.00 / 25 | |
| Category row Match | 6 / 141.00 / 0.0 / 141.00 / 394 | |
| Discount line | `ডিসকাউন্ট এবং অন্যান্য (-)` **− 437.50** | "Discount and others" |
| Grand total | `সর্বমোট` **4,391.00** | |
| Button | `প্রিন্ট` | prints the day summary |

## Cross-checks (all hold)

| Check | Result |
|---|---|
| Memos vs Home | 4 + 1 + 1 = **6 memos** = Home "outlets visited 6/60"; each outlet bought one SKU |
| Value vs Home | 4,687.50 + 141.00 = 4,828.50 gross; − 437.50 = **4,391.00**, the Home grand total |
| Return column | = Issue − Sold: Aster 400 − 375 = 25; FB and SL each 197, so each was issued 200 (inferred: FB 200 + SL 200 = the Match issue of 400 on the Stock screen) |
| Discounted value | Value − Discount (4,687.50 − 437.50 = 4,250.00) |
| Memo-level evidence | Rifat's memo (memo.md) is the single FB memo: FB 3 at 84.00 |

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-30 | The summary is **per SKU, then per category, then the day total**, with a **Return** column that equals closing stock (what goes back to the distributor). | The summary is a live local aggregate over the day's memos and the stock lines: it must work offline and be printable (day-summary slip). Define `return_qty = issued − sold` (and, with returns/free goods, the exact formula). | docs/16, 17 |
| UI-SR-31 | Discount applies to the lighter SKU only (437.50, which is also 35 × 12.50). Match shows discount 0.00. | The offer engine produces a **money discount per SKU** here; the earlier "free goods" reading (H1 in `memo.md`) is **weakened**: SL shows 3 units sold at 57.00 and no discount, and its stock fell by exactly 3. Store discount per line in money; keep quantity-based offers as a possibility (UI-SR-26). | docs/16, 10 |
| UI-SR-32 | The line `ডিসকাউন্ট এবং অন্যান্য (-)` groups discount **and others** (slide/DRP deduction and QC settlement, G-man-002). Today it equals the discount only (437.50). | The grand total is gross − (offer discount + slide + QC). Keep the three components separately and show their sum on this line. | docs/16 |
| UI-SR-33 | Number formats are inconsistent: `437.50` vs `437.5`, `0.00` vs `0.0`; units are not shown (Lighter in pieces, Match in dozens). | Render all money with two decimals; show the unit beside each quantity. | docs/17 |
| UI-SR-34 | The memo count column counts memos containing the SKU. | Reproduce as `count(distinct memo)` per SKU per day. Used by BSR (`docs/10`). | docs/10 |
