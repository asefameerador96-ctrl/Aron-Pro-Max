# 42. Find and reprint a memo

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, screen title "মেমো" (Memo).
- LEFT: the Memo selection screen (route drop-down, outlet drop-down, alphabet bar, one tile "মেমো"). The selected outlet carries a due amount.
- RIGHT: the memo detail screen of a different outlet and route (AgrabadDaily / Ebraj St (Diamond)), with Print and Edit buttons. Printer icon is GREEN (printer connected).
No Android system dialog, no other app. Status bar mostly cropped out.

## Layout, top to bottom
LEFT screen:
1. Top bar: deep purple, white back arrow at the left, centred bold white title "মেমো".
2. Route drop-down: white field with a purple outline, rounded corners, text "Savar BazarDaily" (black), small dark down-arrow at the right.
3. Outlet (retailer) drop-down: same style, text "Siyam (1618410-1832714554-Madrasa Road)" in black, then a green ": ১১৬.৪২ ৳" followed by "বাকি" in bold purple, then a down-arrow. This outlet has a due.
4. Alphabet bar: dark charcoal rounded bar, two rows of letters in white. Row 1: "All" (highlighted: green rounded fill, selected), A B C D E F G H I. Row 2: J K M N P R S T V Z.
5. One white rounded tile at the left under the bar: a magenta/purple/blue icon (a banknote and a receipt/phone with a dollar coin) above the purple label "মেমো". Enabled; tapping opens the memo for the chosen outlet.
6. Rest of the page empty light lavender. Bottom: black system strip with a grey gesture pill.

RIGHT screen:
1. Top bar: deep purple, back arrow left, centred title "মেমো".
2. Printer button: a round light-grey circle at the right overlapping the bottom of the top bar and the card below, with a GREEN printer icon (connected).
3. Info card: white, rounded, thin grey outline, a dark "i" info icon at the left and two lines of text: "আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন।" / "এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন".
4. Outlet card: white, rounded, thin grey outline; a coloured shop/store icon (green-yellow awning, blue front) at the left; two lines: "সেকশনঃ" (bold black) + "AgrabadDaily" (grey); "খুচরা বিক্রেতাঃ" (bold black) + "Ebraj St (Diamond)" (grey).
5. Memo table header row: blue-to-purple gradient band (royal blue at left to indigo), white bold text: "এসকেইউ" (left), "পরিমাণ" (centre), "দাম" (right).
6. Six SKU rows on white, SKU name in blue at the left, quantity centred, price right-aligned, no separators (alternating white): 
   - MaxR-20S | ২০ | ১৬০.০০
   - Avon-20S | ২০ | ১৪০.০০
   - ARIS-A-20s | ২০ | ১৩০.০০
   - ABS | ২৫ | ২০.০০
   - Aster | ১ | ১২.৫০
   - SL | ১২ | ১৯.০০
7. Total row: light grey band, bold black: "মোট" | "৯৮" | "৪৮১.৫০".
8. Discount row: green bold "মোট ডিসকাউন্ট" at the left, "- ০.০০" at the right (green).
9. Grand total row: green bold "সর্বমোট" at the left, "৪৮১.৫০" at the right (green).
   (Rows 7-9 sit in a card with a soft shadow.)
10. Empty space, then two buttons side by side at the bottom: LEFT blue-to-indigo gradient button with a white printer icon and white "প্রিন্ট" (enabled); RIGHT light-grey button with a dark pencil icon and dark "এডিট".
11. Bottom: black strip with a grey gesture pill.
In this memo there is NO due box (no "বাকিতে করা হয়েছে" strip): the sale is paid.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| মেমো | Memo | Top bar title (both); tile label (left) |
| Savar BazarDaily | Route name (Savar Bazar, daily) | Left route drop-down |
| Siyam (1618410-1832714554-Madrasa Road) | Outlet: name (code-phone-cluster) | Left outlet drop-down |
| : ১১৬.৪২ ৳ | due amount 116.42 taka (green, Bangla digits, decimal point, ৳ sign) | Left outlet drop-down |
| বাকি | Due / remaining | Left outlet drop-down, bold purple |
| All, A B C D E F G H I, J K M N P R S T V Z | alphabet filter letters (no L, O, Q, U, W, X, Y) | Left alphabet bar |
| আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। | You can select an outlet and view a memo. | Right info card line 1 |
| এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন | You can also reprint a memo from here | Right info card line 2 (no final danda visible) |
| সেকশনঃ | Section | Right outlet card |
| AgrabadDaily | Section/route name | Right outlet card |
| খুচরা বিক্রেতাঃ | Retailer | Right outlet card |
| Ebraj St (Diamond) | Retailer name | Right outlet card |
| এসকেইউ | SKU | Table header |
| পরিমাণ | Quantity | Table header |
| দাম | Price / amount | Table header |
| MaxR-20S | SKU code | Row 1 |
| ২০ | 20 | Row 1 quantity |
| ১৬০.০০ | 160.00 | Row 1 price |
| Avon-20S | SKU code | Row 2 |
| ২০ | 20 | Row 2 quantity |
| ১৪০.০০ | 140.00 | Row 2 price |
| ARIS-A-20s | SKU code | Row 3 |
| ২০ | 20 | Row 3 quantity |
| ১৩০.০০ | 130.00 | Row 3 price |
| ABS | SKU code | Row 4 |
| ২৫ | 25 | Row 4 quantity |
| ২০.০০ | 20.00 | Row 4 price |
| Aster | SKU code | Row 5 |
| ১ | 1 | Row 5 quantity |
| ১২.৫০ | 12.50 | Row 5 price |
| SL | SKU code | Row 6 |
| ১২ | 12 | Row 6 quantity |
| ১৯.০০ | 19.00 | Row 6 price |
| মোট | Total | Total row |
| ৯৮ | 98 (total quantity) | Total row |
| ৪৮১.৫০ | 481.50 (total value) | Total row |
| মোট ডিসকাউন্ট | Total discount | Discount row (green) |
| - ০.০০ | minus 0.00 | Discount row |
| সর্বমোট | Grand total | Grand total row (green) |
| ৪৮১.৫০ | 481.50 | Grand total row |
| প্রিন্ট | Print | Blue button |
| এডিট | Edit | Grey button |
All digits are Bangla digits; decimals use a Latin full stop with two places; no thousand separators appear (values below 1,000). Currency symbol ৳ appears only in the left drop-down.

## Data and figures shown
- Left: outlet "Siyam" has an unpaid due of 116.42 taka, shown in the drop-down so the AMO can spot due outlets.
- Right memo: quantities 20+20+20+25+1+12 = 98 (matches "৯৮"). Values 160+140+130+20+12.50+19 = 481.50 (matches "৪৮১.৫০"). Discount 0.00, grand total 481.50.
- Implied unit prices: 8.00 per unit for MaxR-20S (160/20), 7.00 Avon-20S, 6.50 ARIS-A-20s, 0.80 ABS, 12.50 Aster, about 1.58 SL (19/12).
- The left (Savar BazarDaily / Siyam) and right (AgrabadDaily / Ebraj St) screens are different outlets and need not match.

## Colours and style
- Top bar: deep purple, white title and arrow. Page background: very light lavender-white.
- Drop-downs: white, 1-2 px purple outline, rounded. Due amount text green; "বাকি" bold purple.
- Alphabet bar: charcoal grey (#555), white letters; selected "All" has a green gradient pill (lime to green).
- Memo tile: white card, rounded, soft shadow; icon in magenta, purple and blue; label purple.
- Info and outlet cards: white, rounded, thin grey border; shop icon multicolour (green/yellow awning, blue shopfront).
- Table header: blue-to-indigo gradient, white bold text. SKU names: blue (#1565A8-ish). Quantities and prices: dark blue/near-black. Total row on light grey; totals in bold black. Discount and grand total in green (#2E8B2E).
- Print button: blue-to-purple gradient, white text/icon. Edit button: light grey, dark text/icon.
- Printer icon: GREEN in a light-grey circle (connected). No packshots on this screen.

## Navigation and behaviour
- Select route (drop-down), then outlet (drop-down, filterable with the alphabet bar; an outlet with a due shows its due amount and "বাকি"), then tap the মেমো tile to open that outlet's memo (manual: "Select the route and outlet and tap মেমো").
- On the memo: the info card says the AMO can select an outlet to see a memo and reprint it from here.
- প্রিন্ট: print the memo again; requires the printer connected (green printer icon; the manual says connect the printer first).
- এডিট: edit the sale.
- Back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The image's right screen labels the first card line "সেকশনঃ" (Section) for the route; screenshot 43 labels the same line "রুট:" (Route). The manual does not mention either label.
- The manual does not mention: the info card text, the green printer-icon button on the memo screen, the shop icon outlet card, the alphabet bar, or the per-SKU memo layout details (blue header, grey total row, green discount and grand total lines).
- The manual says "totals, discount and grand total": the image shows মোট (quantity and value), মোট ডিসকাউন্ট and সর্বমোট: consistent. No category sub-totals appear in this memo (they appear in screenshot 43).
- Manual says the outlet "shows the due amount and বাকি": matches the left screen.

## Open questions for the owner
- The memo on the right shows "সেকশনঃ" while screenshot 43 shows "রুট:": is this an app-version difference or a different memo type?
- Does the due amount in the drop-down show only the outlet's total outstanding, or only the latest sale?
