# 43. Collect a due

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, title "মেমো" (Memo).
- LEFT: the Memo selection screen: route "Savar BazarDaily", outlet "Siyam (...) : ১১৬.৪২ ৳ বাকি", alphabet bar, tile "মেমো". (Same layout as the left screen of screenshot 42, same outlet.)
- RIGHT: the memo detail of that outlet, marked as due: the "এই বিক্রয়টি বাকিতে করা হয়েছে।" box with the green "পরিশোধিত করুন" button, plus Print and Edit buttons. Printer icon is RED (not connected).
No Android system dialog, no other app.

## Layout, top to bottom
LEFT screen:
1. Top bar: deep purple, white back arrow at the left, centred bold white title "মেমো".
2. Route drop-down: white, purple outline, rounded, "Savar BazarDaily", down-arrow.
3. Outlet drop-down: white, purple outline, "Siyam (1618410-1832714554-Madrasa Road)", green ": ১১৬.৪২ ৳", bold purple "বাকি", down-arrow.
4. Alphabet bar: charcoal rounded bar, white letters; "All" selected with a green pill. Row 1: All A B C D E F G H I. Row 2: J K M N P R S T V Z.
5. Tile "মেমো": white rounded card at the left, banknote-and-receipt icon (magenta, purple, blue) over purple label "মেমো".
6. Empty space; bottom: black strip with gesture pill.

RIGHT screen:
1. Top bar: deep purple, back arrow, centred "মেমো".
2. Printer button: round light-grey circle at the right, overlapping the top bar and the first card, with a RED crossed-out printer icon (not connected).
3. Info card: white, rounded, grey outline, dark "i" icon, two lines "আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন।" / "এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন".
4. Outlet card: shop icon at left; "রুট:" (bold) "Savar BazarDaily" (grey); "খুচরা বিক্রেতাঃ" (bold) "Siyam (1618410-1832714554-M" (grey, text runs off the card edge and is cut off).
5. Table header: blue-to-indigo gradient band, white bold: "এসকেইউ" | "পরিমাণ" | "দাম".
6. Five SKU rows (SKU name blue, quantity centre, price right):
   - MaxR-10S | ১০ | ৮০.০০
   - ABS | ২৫ | ২০.০০
   - Aster | ১ | ১২.৫০
   - FB | ১ | ২.৩৩
   - SL | ১ | ১.৫৮
   (The first price is rendered with a glyph that looks like Bangla 8 (৮০.০০); the sum rules confirm 80.00, see "Data".)
7. Category subtotal block (light grey, smaller text, grey): 
   - "মোট সিগারেট" | ১০ | ৮০.০০
   - "মোট বিড়ি" | ২৫ | ২০.০০
   - "মোট লাইটার" | ১ | ১২.৫০
   - "মোট ম্যাচ" | ২ | ৩.৯২
8. Discount row: green bold "মোট ডিসকাউন্ট" | "- ০.০০".
9. Grand total row: green bold "সর্বমোট" | "১১৬.৪২".
10. DUE BOX: a dashed red-outlined rectangle (rounded) with a small yellow-orange card icon at the top-left, red text "এই বিক্রয়টি বাকিতে করা হয়েছে।" (two lines), a thin vertical red divider, then a green (dark green) rounded button with white text "পরিশোধিত করুন" at the right.
11. Bottom buttons: LEFT blue-to-indigo gradient "প্রিন্ট" with white printer icon; RIGHT light-grey "এডিট" with dark pencil icon.
12. Bottom: black strip with gesture pill.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| মেমো | Memo | Top bar title (both); tile label (left) |
| Savar BazarDaily | Route | Left drop-down; right outlet card |
| Siyam (1618410-1832714554-Madrasa Road) | Outlet name (code-phone-cluster) | Left outlet drop-down |
| : ১১৬.৪২ ৳ | due 116.42 taka (green) | Left outlet drop-down |
| বাকি | Due | Left outlet drop-down, bold purple |
| All, A B C D E F G H I, J K M N P R S T V Z | alphabet filter | Left alphabet bar |
| আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। | You can select an outlet and view a memo. | Right info card |
| এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন | You can also reprint a memo from here | Right info card |
| রুট: | Route | Right outlet card |
| খুচরা বিক্রেতাঃ | Retailer | Right outlet card |
| Siyam (1618410-1832714554-M... | Retailer name (cut off) | Right outlet card |
| এসকেইউ | SKU | Table header |
| পরিমাণ | Quantity | Table header |
| দাম | Price / amount | Table header |
| MaxR-10S | SKU code (Maxim R, 10s) | Row 1 |
| ১০ | 10 | Row 1 quantity |
| ৮০.০০ | 80.00 | Row 1 price |
| ABS | SKU code | Row 2 |
| ২৫ | 25 | Row 2 quantity |
| ২০.০০ | 20.00 | Row 2 price |
| Aster | SKU code | Row 3 |
| ১ | 1 | Row 3 quantity |
| ১২.৫০ | 12.50 | Row 3 price |
| FB | SKU code (Flame Box) | Row 4 |
| ১ | 1 | Row 4 quantity |
| ২.৩৩ | 2.33 | Row 4 price |
| SL | SKU code | Row 5 |
| ১ | 1 | Row 5 quantity |
| ১.৫৮ | 1.58 | Row 5 price |
| মোট সিগারেট | Total cigarette | Subtotal row 1 |
| ১০ / ৮০.০০ | 10 / 80.00 | Subtotal row 1 |
| মোট বিড়ি | Total bidi | Subtotal row 2 |
| ২৫ / ২০.০০ | 25 / 20.00 | Subtotal row 2 |
| মোট লাইটার | Total lighter | Subtotal row 3 |
| ১ / ১২.৫০ | 1 / 12.50 | Subtotal row 3 |
| মোট ম্যাচ | Total match | Subtotal row 4 |
| ২ / ৩.৯২ | 2 / 3.92 | Subtotal row 4 |
| মোট ডিসকাউন্ট | Total discount | Green row |
| - ০.০০ | minus 0.00 | Green row |
| সর্বমোট | Grand total | Green row |
| ১১৬.৪২ | 116.42 | Green row |
| এই বিক্রয়টি বাকিতে করা হয়েছে। | This sale was made on due. | Due box (red text, two lines) |
| পরিশোধিত করুন | Mark as paid | Green button in due box |
| প্রিন্ট | Print | Blue button |
| এডিট | Edit | Grey button |
Digits are Bangla throughout; decimals with a Latin full stop; ৳ sign only in the left drop-down. Cut-off text on the right outlet card is unreadable after "-M".

## Data and figures shown
- Due: 116.42 taka for outlet Siyam, equal to the memo grand total (the whole sale was marked due; no part payment).
- Quantities and values: cigarette 10 for 80.00; bidi 25 for 20.00; lighter 1 for 12.50; match 2 (FB 1 + SL 1) for 3.92 (2.33 + 1.58 = 3.91 by line; the app shows 3.92, a rounding difference). 80.00 + 20.00 + 12.50 + 3.92 = 116.42 = grand total. The 80.00 reading of the first price is required for the totals to add up.
- Discount 0.00.

## Colours and style
- Top bar deep purple; page lavender-white; drop-downs white with purple outline; alphabet bar charcoal with a green "All" pill.
- Table header blue-to-indigo gradient, white text. SKU names blue. Category subtotal block light grey with smaller grey text. Discount and grand-total lines green bold.
- Due box: dashed RED border, red text, small yellow/orange card icon; vertical thin red divider. "পরিশোধিত করুন" is a green (dark green #1B7F3B-ish) pill button with white text.
- Print: blue-to-indigo gradient; Edit: light grey. Printer icon in the top-right circle is RED crossed out (not connected).
- No packshots on this screen.

## Navigation and behaviour
- Per the manual: select the outlet with a due (it shows the due amount and "বাকি"), tap the মেমো tile, then on the memo tap "পরিশোধিত করুন".
- Tapping পরিশোধিত করুন opens a confirmation (screenshot 44: "আপনি কি নিশ্চিত?" with হ্যাঁ / না), then a success message, and the due mark is removed from the outlet.
- প্রিন্ট reprints the memo (printer must be connected; here it is red = not connected). এডিট edits the sale.
- The due box appears only on a memo that was marked as due; the memo in screenshot 42 has none.

## Differences between the image and the manual text
- The manual mentions only "এই বিক্রয়টি বাকিতে করা হয়েছে" and the green button. The image also shows: category subtotal lines (মোট সিগারেট, মোট বিড়ি, মোট লাইটার, মোট ম্যাচ), the dashed red box, the red printer icon, the info card, and a "রুট:" label (screenshot 42 shows "সেকশনঃ").
- The image text ends with a danda: "এই বিক্রয়টি বাকিতে করা হয়েছে।" (the manual quote has no danda).
- The manual says nothing about part payments appearing on the memo; the image shows no paid amount, only the full total.

## Open questions for the owner
- When part of a sale was paid (screenshot 25), does the memo show the paid amount and remaining due, or only this box?
- Is the line sum 3.91 versus the displayed ম্যাচ total 3.92 an intended rounding rule (round per category)? The rebuild should state its rule.
