# 26. View previous sale data

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app.
- LEFT: the Sales screen (বিক্রয়) scrolled slightly, showing the button "পূর্বের সেল ডাটা দেখুন" (View previous sale data) and the SKU list with quantity steppers.
- RIGHT: the "সেল ডাটা" (Sell data) screen showing the outlet's earlier sale for a chosen date.
Status bar not shown.

## Layout, top to bottom
LEFT screen:
1. Top bar: deep purple, centred white title "বিক্রয়", back arrow at left.
2. Grey strip (page background grey) holding a white rounded card with an outline: a small shop/store icon (coloured awning) at the left; two text lines: "রুট: Savar BazarDaily" and "খুচরা বিক্রেতাঃ Siyam (1618410-1832714554-Ma" (second line cut off at the screen edge).
3. Full-width button with light grey-green fill and green outline: a history (clock with counter-clockwise arrow) icon and the text "পূর্বের সেল ডাটা দেখুন". This bar stays pinned; the SKU list scrolls under it (the first card is cut off at the top).
4. Scrollable list of white rounded SKU cards with a soft shadow. Each card: pack picture (packshot) at the left with a small round dark-blue badge at its lower right holding a number; the SKU name in purple bold (e.g. "MaxR-10S"); a green round "-" button, a numeric box with an underline in the middle (quantity), a green round "+" button; at the right of each card three lines: (a) a blue cart icon with the rate, (b) a small save/disk-like icon (brown) with discount "০%", (c) a small box/archive icon (purple) with stock number "০".
   Cards visible:
   - MaxR-10S: badge ১, quantity ১০, rate line cut off at the top (unreadable), discount ০%, stock ০.
   - Avon-20S: badge ০, quantity ০, rate ১১০০০, discount ০%, stock ০.
   - ABS (style pack picture, red): badge ১, quantity ২৫, rate ১০০০০, discount ০%, stock ০.
   - Aster (lighter pack picture): quantity ১, rate ৫৫০, discount ০%; the card is cut off by the bottom bar.
5. Bottom bar: dark blue (navy) full-width, with a white cart icon and the running total "১১৬.৪২" at the left, and a green gradient button "এগিয়ে যান →" at the right.
6. Android gesture bar.

RIGHT screen:
1. Top bar: deep purple, centred white title "সেল ডাটা", back arrow at left.
2. Page background very light lavender/white.
3. Date field: white rounded box with purple outline, calendar icon at the left and the text "November 26, 2025" (English month name, Latin digits).
4. Below it a purple text line "2025-11-26" (Latin digits, ISO format).
5. Table with purple header row (white bold text): এসকেইউ (left), পরিমাণ (centre), মূল্য (right).
6. Seven rows alternating white and very light lavender, each starting with a small pack picture (packshot) then the SKU name, then quantity (centre) and value (right):
   - MaxR-10S | ৫২০ | ৪,১৬০.০০
   - MaxR-20S | ১,০০০ | ৮,০০০.০০
   - MaxB-10S | ৫০০ | ৪,০০০.০০
   - AB-12s | ৬০০ | ৪৫০.০০
   - ABS | ১,২৫০ | ১,০০০.০০
   - Aster | ১০০ | ১,২৫০.০০ (picture is a row of coloured lighters)
   - FB | ৬০০ | ১,৮০০.০০ (matchbox picture)
7. Total row: lighter purple (lilac) with white text: "মোট" | ৪,৫৭০.০০ | ২০,২৬০.০০ (as read; see data section).
8. Empty page below. Android gesture bar.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় | Sales | Left top bar |
| রুট: Savar BazarDaily | Route: Savar Bazar Daily | Left card |
| খুচরা বিক্রেতাঃ Siyam (1618410-1832714554-Ma | Retailer: Siyam (code-phone-... cut off) | Left card |
| পূর্বের সেল ডাটা দেখুন | View previous sale data | Left button |
| MaxR-10S, Avon-20S, ABS, Aster | SKU names | Left SKU cards |
| ১০ ; ০ ; ২৫ ; ১ | quantities entered | Left quantity boxes (Bangla digits) |
| ১ ; ০ ; ১ | numbers in the blue badges on pack pictures | Left, lower right of pack picture |
| ১১০০০ ; ১০০০০ ; ৫৫০ | rate per 1,000 sticks | Left, next to cart icon |
| ০% | discount | Left, each card |
| ০ | stock | Left, box icon, each card |
| ১১৬.৪২ | running total | Left bottom bar |
| এগিয়ে যান → | Proceed | Left bottom button |
| সেল ডাটা | Sell data | Right top bar |
| November 26, 2025 | selected date | Right date field |
| 2025-11-26 | same date, ISO | Right, under date field |
| এসকেইউ | SKU | Right table header |
| পরিমাণ | Quantity | Right table header |
| মূল্য | Price / value | Right table header |
| MaxR-10S, MaxR-20S, MaxB-10S, AB-12s, ABS, Aster, FB | SKU names | Right table rows |
| ৫২০, ১,০০০, ৫০০, ৬০০, ১,২৫০, ১০০, ৬০০ | quantities | Right table, quantity column |
| ৪,১৬০.০০, ৮,০০০.০০, ৪,০০০.০০, ৪৫০.০০, ১,০০০.০০, ১,২৫০.০০, ১,৮০০.০০ | line values | Right table, value column |
| মোট | Total | Right total row |
| ৪,৫৭০.০০ | total quantity | Right total row |
| ২০,২৬০.০০ | total value (as read) | Right total row |

## Data and figures shown
- Left: the quantity stepper per SKU, the rate per 1,000 sticks next to the cart icon (e.g. 11000 and 10000 per 1,000 sticks; 550 for Aster per the manual's note), discount percentage and available stock; running total ১১৬.৪২.
- Right: the outlet's sale of 2025-11-26 by SKU. Quantities add up to 4,570 (520+1,000+500+600+1,250+100+600), matching the total row. The line values add up to 20,660.00 arithmetically; the total row appears to read ২০,২৬০.০০, so either the image digit is misread or the vendor total is inconsistent: flag for the owner.
- Digits are Bangla (০১২৩৪৫৬৭৮৯) in every figure, with Latin-style thousand separators (comma) and decimal point. The dates are in Latin digits.

## Colours and style
- Top bar deep purple, flat, white bold title.
- Left page background light grey in the header area, SKU cards white rounded with a soft shadow; SKU names purple bold; quantity +/- buttons green with white symbols; rate (cart) text blue; discount and stock icons brown/purple.
- History button: pale grey-green fill with green outline.
- Bottom bar navy blue; button green-to-yellow-green gradient with white text.
- Right: lavender-white background, purple outlined date field, purple table header, alternating white/lavender rows, lilac total row.
- Packshots: yes. Left: a pack picture at the left of every SKU card (Maxim red pack, Avon, ABS red "STYLE" pack, Aster lighters). Right: a small pack picture in every row.

## Navigation and behaviour
Per the manual: on the Sales screen tap "পূর্বের সেল ডাটা দেখুন" to open "সেল ডাটা", then choose any date with the date field at the top to see what the outlet bought that day (SKU, quantity, value). Back arrow returns to the Sales screen. The date field opens a date picker (not shown).

## Differences between the image and the manual text
- The manual says the right screen shows "SKU, quantity and value for the date chosen"; the image also shows a total row, the date repeated in ISO form (2025-11-26) and pack pictures per row, none mentioned.
- Left screen: the manual calls the pinned button just "পূর্বের সেল ডাটা দেখুন"; the image shows it with a history icon and the SKU rows' stock and discount icons, which the manual describes only generally.
- Total-value discrepancy described above.

## Open questions for the owner
- Is the total ২০,২৬০.০০ really what the vendor app displays (lines sum to 20,660)? Should the rebuild show the true sum?
- Is the date default today or the last visit? The image only shows an example date (2025-11-26).
- Does this screen show only that outlet's sales by this AMO or all sales to the outlet?
