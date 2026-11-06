# 41. Stock issue

## Screens in the image
Two phone screens side by side. Both show the SAME vendor ARON AMO screen, "স্টক" (Stock), captured on two different phones.
- LEFT: Stock screen on a phone with the 3-button Android navigation bar (black strip at the bottom). Status bar shows 5:16, network speed, 4G, battery 58.
- RIGHT: Stock screen on a phone with a gesture pill (no 3-button bar). Status bar shows 6:20, battery 62.
Both are the vendor app (no Android system dialog, no other app). Only the status bar and bottom system bar differ. Neither is scrolled: the 5th SKU row (MARISE pack) is cut off behind the bottom panel.

## Layout, top to bottom
(identical on both screens unless noted)
1. Top bar: deep purple (indigo-purple), flat, no gradient visible. White back arrow (rounded triangle/arrow style) at the left. Centred bold white title "স্টক".
2. Printer button: a round light-grey circle at the right end of the top bar, overlapping the bottom edge of the bar and the page below. Inside it a RED printer icon with a diagonal slash (printer not connected = red). Tapped to connect the Bluetooth printer (manual section 6); turns green when connected.
3. Column header row on the light page, under a thin grey divider line: "এসকেইউ" (left, over the pack and name), "ইস্যু" (centre, over the quantity stepper), "স্টক" (right). Dark grey text.
4. SKU list, one white rounded card per SKU with a very light shadow; each card, left to right:
   - Pack picture (packshot) of the SKU, about 100 x 165 px, at the left edge of the card. Bottom-right corner of the picture carries a small purple circle badge with a white "০" (the current stock/issue count badge).
   - SKU name in purple/indigo text, vertically centred (wraps to two lines when long).
   - Issue stepper: a purple rounded-square button with a white minus ("-"), a centre quantity "০" over a thin grey underline (an editable number field), a purple rounded-square button with a white plus ("+").
   - Stock figure at the right: plain dark "০".
   Rows visible: (a) MaxB-20S with Maxim "X BOLT" blue-black pack picture; (b) MaxDB-20S 20HL (name wraps to two lines: "MaxDB-20S" / "20HL") with Maxim Platinum "DOUBLE BURST" black pack; (c) Avon-20S with beige/cream Avon pack; (d) ARIS-A-20s with pale green/cream ARIS pack with a smoker photo; (e) a fifth card whose top shows an orange-red "MARISE" pack, cut off by the bottom panel (name and controls hidden).
   All four full rows show picture-badge 0, quantity 0 and stock 0.
5. Bottom panel (fixed, over the list): a vertical gradient block, royal blue at the top fading to indigo/purple at the bottom. White text.
   - Header line (small): "ক্যাটাগরি" (left), "মোট ইস্যু" (centre-right), "স্টক" (right).
   - Three category rows, larger white text: "সিগারেট" ০ ০; "লাইটার" ০ ০; "ম্যাচ" ০ ০ (first number under মোট ইস্যু, second under স্টক).
   - Two buttons side by side at the bottom of the panel:
     - LEFT, about half width: green (lime-to-green gradient) rounded button with a white save/floppy icon and white "সংরক্ষণ". Enabled look.
     - RIGHT, about half width: grey (light-grey to mid-grey gradient) rounded button with a dark-grey printer icon and grey "প্রিন্ট". Looks disabled (nothing saved yet / printer not connected).
6. Below the panel: LEFT phone has a black Android bar with three icons (menu lines, square, back triangle). RIGHT phone has a light bar with a grey gesture pill.
The status bar is not described further (clock, battery, signal).

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| স্টক | Stock | Top bar title; also right-hand column header |
| এসকেইউ | SKU | Column header (left) |
| ইস্যু | Issue (quantity issued/taken today) | Column header (centre) |
| MaxB-20S | SKU code (Maxim Bold, 20s pack) | Row 1 name |
| MaxDB-20S 20HL | SKU code (Maxim Double Burst, 20s, 20HL), wraps to 2 lines | Row 2 name |
| Avon-20S | SKU code (Avon 20s) | Row 3 name |
| ARIS-A-20s | SKU code (ARIS A, 20s) | Row 4 name |
| MARISE | brand printed on the pack picture (only the pack picture is visible, not the name text) | Row 5, cut off |
| ০ | zero (Bangla digit) | Pack badge, issue field, stock figure in every row; every number in the totals panel |
| ক্যাটাগরি | Category | Bottom panel header |
| মোট ইস্যু | Total issue | Bottom panel header |
| স্টক | Stock | Bottom panel header (right) |
| সিগারেট | Cigarette | Bottom panel row 1 |
| লাইটার | Lighter | Bottom panel row 2 |
| ম্যাচ | Match | Bottom panel row 3 |
| সংরক্ষণ | Save | Green button |
| প্রিন্ট | Print | Grey button |
Pack pictures also contain small printed text (brand logos "MAXIM", "AVON", "ARIS", "MARISE", "X BOLT", "DOUBLE BURST", a Bangla health warning strip); these are part of the images, not UI text. No "-" or "+" text: they are icons (minus in a purple square, plus in a purple square).

## Data and figures shown
- Every figure is zero: no issue entered and no stock yet (a fresh, empty issue form). All figures use the Bangla digit "০" (U+09E6), not the Latin "0".
- Per row: badge on the pack picture = 0, Issue field = 0, Stock = 0. Per the manual, Issue is the quantity the AMO takes out each morning; Stock is the running stock figure.
- Totals panel: for each category (Cigarette, Lighter, Match) the "মোট ইস্যু" (total issue) and "স্টক" (stock) are both 0. Only three categories are listed (no বিড়ি/Bidi row) in this capture.
- No thousand separators, no units, no currency shown on this screen.

## Colours and style
- Top bar: deep purple / indigo-purple (about #4B1D96), white title and icons, flat.
- Page background: very light lavender-white (about #FBF7FF).
- Column header text: dark grey. Thin grey divider above the header row.
- SKU cards: white, rounded corners (about 8 px), very soft shadow.
- Packshots: real pack pictures, one per SKU, at the left of each card, with a purple circular count badge in the bottom-right corner of the picture.
- Stepper buttons: purple (indigo, about #4B1D96) rounded squares, white minus and plus glyphs. Quantity field: plain number with a grey underline.
- SKU names: purple/indigo text. Stock figure and quantities: near-black.
- Printer button: light-grey circle, red crossed-out printer icon (not connected state).
- Bottom panel: blue (about #0B4EA2) at the top to indigo/purple (about #3A1E99) at the bottom, white text; category names larger than the headers.
- Save button: green gradient (lime #8BC53F to green #3FA535), white text and icon. Print button: grey gradient, grey text (disabled look).

## Navigation and behaviour
- Back arrow returns to the Dashboard.
- Printer icon (top right): red when no printer is connected; tapping connects the paired Bluetooth printer (manual section 6); it turns green when connected. The Bluetooth permission prompt appears the first time (manual screenshot 12).
- "-" / "+" change the issue quantity of that SKU by one; the middle field can be typed in. The category totals in the bottom panel update with the issue quantities.
- সংরক্ষণ (Save) stores the morning's issue quantities. The manual says: "Each morning enter the quantity you take for each SKU and tap Save."
- প্রিন্ট (Print) prints the stock issue slip; in the image it looks disabled (grey), presumably until saved and the printer is connected (not stated in the manual).
- The list scrolls under the fixed bottom panel (the 5th row is cut off).

## Differences between the image and the manual text
- The manual says the bottom shows totals by category "(Cigarette, Lighter, Match)": matches the image (no Bidi row).
- The manual does not mention: the pack pictures on each row, the purple count badge on each picture, the separate printer-icon circle styling, the disabled/grey look of the Print button, or that the SKU name wraps.
- The manual does not describe the "মোট ইস্যু" column header wording: the image shows "মোট ইস্যু" (Total issue) over the first total column and "স্টক" over the second.
- The manual says the three columns are SKU, Issue (with - / +) and Stock: matches.

## Open questions for the owner
- What exactly does the purple badge on the pack picture count (issue quantity, stock, or something else)? It reads 0 in every row.
- When does the Print button become active (after Save only, or only with the printer connected)?
- Is the Stock column a read-only running figure, or does it change after Save (stock = previous stock + issue)?
