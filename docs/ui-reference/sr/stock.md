# SR App — Stock (reference)

Image: `stock.webp`. Test account `sr334001`. Manual references and existing gaps: G-man-007 (stock screen), G-man-023 (printer), G-man-001 (units), `docs/06` day-flow steps 2-3.

## Business rule stated by AKTCL (2026-10-05)

> For stock taking the phone's Bluetooth must be on and connected to the printer, because once stock taking is completed the SR gets a detailed printout of the stock and gives that printed copy to the distributor's manager.

So the stock slip is a **physical hand-over document** for the distributor, not a convenience print. The new app must produce the same slip.

## What the screen shows

| Element | Shown | Reading |
|---|---|---|
| Title bar | back arrow, stock icon, `স্টক`; top-right a red circle with a crossed-out printer | Stock; the crossed printer means **printer not connected** |
| Column headers | `এসকেইউ` / `ইস্যু` / `স্টক` | SKU / Issue / Stock |
| SKU card | pack picture, a purple circular badge `০` on the picture, SKU code (`MaxR-10S`, `MaxR-20S`, `MaxB-10S`, `MaxB-20S`, `MaxWB-10S`...), a red `−`, an underlined numeric field `০`, a purple `+`, then the stock value `০` | one row per SKU in the zone's sales plan |
| Totals panel (purple) | `ক্যাটাগরি` / `মোট ইস্যু` / `স্টক`; rows `সিগারেট` (Cigarette) 0 / 0, `বিড়ি` (Bidi) 0 / 0, `লাইটার` (Lighter) **400 / 25**, `ম্যাচ` (Match) **400 / 394** | totals per category |
| Buttons | red `সংরক্ষণ` (Save), grey `প্রিন্ট` (Print) | Print looks disabled |

Image text is Bangla; digits are Bengali (০–৯).

## Cross-checks against Home and the seed prices

| Check | Result |
|---|---|
| Home tiles `ইস্যু 800` and `বর্তমান স্টক 419` | 400 + 400 = 800 and 25 + 394 = 419: **the Home tiles add all categories together** |
| Lighter consumed | 400 − 25 = 375; × 12.50 (seed price of Aster) = 4,687.50 = the Home card's Lighter value |
| Home discount `437.50` | exactly 35 × 12.50: the discount equals the price of 35 lighters |
| Match consumed | 400 − 394 = 6; Flame Box 3 × 28 = 84 and Salmon (SL) 3 × 19 = 57 give 141.00 = the Home card's Match value |

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-15 | The Home Issue/Stock tiles sum **pieces and dozens** (Lighter in pieces, Match in dozens). Resolves UI-SR-10. | The sum has no unit. Show per category, with the unit label, or drop the combined figure. Seed catalogue confirms: Lighter `pack_type` Box, Match `pack_type` Dozen. | docs/10, 16 |
| UI-SR-16 | Stock is taken **per SKU with a typed or stepped Issue quantity**; Stock is derived. | Issue = quantity received from the distributor today; Stock = on-hand after issue and sales (G-man-007). The Stock column updates locally after each sale (offline). | docs/16, 17 |
| UI-SR-17 | **Printer dependency.** The rule above plus the crossed-printer icon. | `MUST-CONFIRM` whether Save is blocked while the printer is not connected, or only Print. Recommendation: Save always works offline and records `stock_slip_printed = false`; Print is retryable, and the day cannot be "sales submitted" with an unprinted slip unless a supervisor override is set (`cfg.stock.require_printed_slip`). This keeps the distributor paper trail without making the network or printer a sale blocker. | docs/17, 19 |
| UI-SR-18 | The purple badge on each pack picture reads `০`. | Read as "packs" (quantity ÷ pack size), per the register's hypothesis for the same badge on the Sale screen (G-man-005). `MUST-CONFIRM`. | docs/06 |
| UI-SR-19 | Each row carries a full-colour **pack picture** showing the pictorial health warning. About 40 SKUs. | Product images are a bundle-size and APK-size cost (R4). Store one compressed thumbnail per SKU, managed in the admin panel, cached with a bounded LRU (`docs/04`). Use the current approved pack art only. | docs/17, 19 |
| UI-SR-20 | The printer-status icon sits in the title bar of Stock (and Memo). | Make printer state a persistent header element on every screen that prints, with tap-to-reconnect. | docs/17 |
