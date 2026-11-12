# 17. Connect in the app

## Screens in the image
Two phone screens side by side, both the SR app Stock (স্টক) page. Left: Android system permission dialog over a dimmed Stock page. Right: Stock page after allowing, with a green toast "প্রিন্টার কানেক্ট করা হয়েছে" and a green printer icon. Both are cropped at the top (no status bar visible).

## Layout, top to bottom
Left screen:
1. Header, gradient dark red to deep purple (dimmed by the overlay): back arrow "<", a small gradient building/stock icon, title "স্টক", at the right a grey circle with a red crossed-out printer icon (printer not connected).
2. Column headings on light grey: এসকেইউ (left), ইস্যু (centre), স্টক (right).
3. SKU cards (white, rounded, dimmed): pack picture with a small purple round badge showing 0 at its bottom-right, SKU name in purple (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S), a red rounded minus button, a number field showing 0 with an underline, a purple rounded plus button, and the stock figure 0 at the right.
4. Bottom: dark system dialog (rounded) with a blue diamond icon, text "Allow ARON SR to find, connect to and determine the relative position of nearby devices?" (ARON SR in bold), buttons "Allow" and "Don't allow".
Right screen:
1. Header, gradient red to purple, same title; at the right a light circle with a green printer icon (connected).
2. Same column headings and SKU cards (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, then the top of a fifth card with an AVON pack, cut off).
3. A full-width green toast bar with white text over the third and fourth cards.
4. Purple totals panel: heading row ক্যাটাগরি / মোট ইস্যু / স্টক, then four rows সিগারেট, বিড়ি, লাইটার, ম্যাচ with zeros in both columns.
5. Two buttons: left red-to-purple gradient "সংরক্ষণ" with a save (floppy) icon; right light grey "প্রিন্ট" with a printer icon.
6. Android gesture bar at the bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| স্টক | Stock | Header title, both screens |
| এসকেইউ | SKU | Column heading |
| ইস্যু | Issue (stock issued to the SR) | Column heading |
| স্টক | Stock (on hand) | Column heading, right |
| MaxR-10S | SKU name, Maxim red 10 sticks | Card 1 |
| MaxR-20S | SKU name, Maxim red 20 sticks | Card 2 |
| MaxB-10S | SKU name, Maxim blue 10 sticks | Card 3 |
| MaxB-20S | SKU name, Maxim blue 20 sticks | Card 4 |
| 0 (rendered as Bangla-style zero) | Quantity zero | Pack badge, issue field, stock figure on every card |
| Allow ARON SR to find, connect to and determine the relative position of nearby devices? | Android Nearby devices (Bluetooth) permission prompt | Left screen dialog |
| Allow | Allow | Left dialog button |
| Don't allow | Deny | Left dialog button |
| প্রিন্টার কানেক্ট করা হয়েছে | Printer connected | Green toast, right screen |
| ক্যাটাগরি | Category | Totals panel heading |
| মোট ইস্যু | Total issue | Totals panel heading |
| স্টক | Stock | Totals panel heading |
| সিগারেট | Cigarette | Totals row 1 |
| বিড়ি | Bidi | Totals row 2 |
| লাইটার | Lighter | Totals row 3 |
| ম্যাচ | Match | Totals row 4 |
| সংরক্ষণ | Save | Button, bottom left |
| প্রিন্ট | Print | Button, bottom right |
| MAXIM / AVON | Brand names printed on the pack pictures | Pack artwork |

## Data and figures shown
- All quantities are zero: issue 0, stock 0 on all four SKUs, and 0 in all four category totals (cigarette, bidi, lighter, match).
- Pack-badge number is 0 on each card (purpose not stated; likely the pack count or stock in packs).
- A fifth SKU card (AVON pack) is partly visible and cut off.
- No status bar in this image (cropped).

## Colours and style
- Header: red-to-deep-purple gradient (dimmed on the left); light grey page; white SKU cards with rounded corners and light shadow.
- Pack pictures (packshots) at the left of every card, 80 to 90 dp wide.
- Minus button: red-orange rounded square with white bar; plus button: deep purple rounded square with white plus.
- Totals panel: solid deep purple (about #4B1C9B) with white text.
- Save button red-to-purple gradient with white text; Print button light grey with dark text.
- Toast: solid green with white Bangla text.
- Printer icon: red crossed-out in a grey circle when not connected, green on a white circle when connected.
- Permission dialog is the standard dark Android system dialog.

## Navigation and behaviour
- On opening Stock, Android asks for the Nearby devices (Bluetooth) permission. Allow lets the app find the paired printer.
- Tapping the printer icon at top right connects to the printer; on success the icon turns green and the toast "প্রিন্টার কানেক্ট করা হয়েছে" shows.
- The issue field is edited with the minus and plus buttons or by typing; সংরক্ষণ saves the issue, প্রিন্ট prints the stock slip.
- The page scrolls; the totals panel and buttons stay fixed at the bottom.

## Differences between the image and the manual text
- The manual says "tap Allow, tap the printer icon until it is green". The image shows only the two end states (before: crossed-out printer in grey circle; after: green icon with toast), not the tap step.
- The image shows the full Stock page with SKU cards, issue fields, a category totals panel and the Save and Print buttons; the manual text does not describe them here.
- The manual's quoted permission text matches the image ("Allow ARON SR to find, connect to... nearby devices?").
- The left (grey) printer icon is crossed out and red inside a grey circle; the manual does not mention this disconnected state.

## Open questions for the owner
- What does the small purple number badge on each pack picture mean (pack count, or issued boxes)?
- Is the Don't allow path handled (printing then fails silently)? What should the new app show?
