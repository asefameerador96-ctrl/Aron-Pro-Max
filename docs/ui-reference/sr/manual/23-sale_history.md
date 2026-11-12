# 23. Sale history

## Screens in the image
Two phone screens side by side. Left: the "সেল ডাটা" (Sale data) page showing one day's sales for the outlet by SKU. Right: the same page dimmed with the Android date-picker dialog open.

## Layout, top to bottom
Left screen:
1. Status bar 3:08, battery 68.
2. Header red-to-deep-purple gradient: back arrow and the title "সেল ডাটা" (no icon).
3. Date field: white rounded box with deep purple outline, a purple calendar icon and the text "November 10, 2026".
4. Purple date text "2026-11-10".
5. Table: a deep purple header row (এসকেইউ, পরিমাণ, মূল্য); three SKU rows each with a small pack picture, white and light grey alternating; a total row on a lilac-purple band.
6. Empty light grey space, navigation bar.
Right screen:
1. Same status bar (3:08), dimmed header and date field.
2. Material date-picker dialog, pale lavender: small label "Select date", large "Tue, Nov 10" with a pencil (edit) icon; a month selector "November 2026" with a down arrow and left/right arrows; a week grid S M T W T F S with days 1 to 30; day 10 in a filled purple circle (selected); day 11 with a thin purple outline (today); days 12 to 30 in light grey (future dates, disabled); buttons "Cancel" and "OK" at the bottom right. The dialog text uses a serif-like font.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| সেল ডাটা | Sale data | Header title |
| November 10, 2026 | Selected date | Date field |
| 2026-11-10 | Selected date, ISO | Purple text below the field |
| এসকেইউ | SKU | Table heading |
| পরিমাণ | Quantity | Table heading |
| মূল্য | Value (price) | Table heading |
| ARIS-A-20s, ২০, ১৩০.০০ | Quantity 20, value 130.00 | Row 1 |
| BDDF-10s, ১০, ৬৫.০০ | Quantity 10, value 65.00 | Row 2 |
| BDDF-20s, ২০, ১৩০.০০ | Quantity 20, value 130.00 | Row 3 |
| মোট, ৫০.০০, ৩২৫.০০ | Total quantity 50.00, total value 325.00 | Total row |
| Select date | Date picker label | Dialog |
| Tue, Nov 10 | Selected date, long form | Dialog headline |
| November 2026 | Month shown | Dialog |
| S M T W T F S | Weekday initials (Sunday first) | Dialog |
| 1 ... 30 | Days of November | Dialog |
| Cancel | Cancel | Dialog |
| OK | OK | Dialog |

## Data and figures shown
- Sales on 2026-11-10: ARIS-A-20s 20 for 130.00; BDDF-10s 10 for 65.00; BDDF-20s 20 for 130.00. Sum of quantities 20+10+20 = 50 (shown as "৫০.০০"); sum of values 130+65+130 = 325 (shown as "৩২৫.০০"). Consistent.
- Implied unit price: 6.50 taka per stick/unit for all three SKUs.
- The total quantity is shown with two decimals (৫০.০০) while the line quantities are integers.
- Calendar: Tue Nov 10 selected; Nov 11 outlined (today) so the screens were captured on Nov 11 (or the outline marks today and the selected day is yesterday); days after 11 are greyed out (cannot choose future dates). 2026-11-10 is a Tuesday, matching the dialog.
- The date field shows the date in English words, while the line below shows ISO digits.

## Colours and style
- Header red-to-purple gradient; page light grey; date field white with a purple border.
- Table header solid deep purple with white bold Bangla text; rows white and very light grey; total row pale purple (about #9B84C4) with white text.
- Small pack pictures (packshots) at the left of each SKU row, about 30 dp.
- Date-picker: Material 3 style, lavender background, purple selected circle, dialog text in a serif font (system default in the screenshot).

## Navigation and behaviour
- Reached from the Sale History button on the call screen. Tapping the date field opens the date-picker; choosing a day and tapping OK reloads the table for that day. Cancel closes without change. The pencil icon switches to typed date entry.
- Future dates are disabled.
- Back arrow returns to the Sales page.

## Differences between the image and the manual text
- The manual says "সেল ডাটা: the outlet's sales on a chosen date by SKU, quantity and value; a calendar to choose the date". The image matches, but the page does not show the outlet name; the manual implies it is for the selected outlet.
- The image shows the date twice (the field text in English and a purple ISO line), not mentioned in the manual.
- The manual does not mention that future dates are greyed out, nor the Sunday-first calendar.

## Open questions for the owner
- Does Sale History show only the selected outlet's sales, or all of the SR's sales for the day? (The outlet name is not shown on this page.)
- Should the date field and the picker be Bangla in the new app (here they are English)?
