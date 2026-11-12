# 27. Partial payment (due)

## Screens in the image
Two phone screens of the Review (নিরীক্ষণ) page. Left: dimmed, with a dialog to enter the amount paid now. Right: the same memo after the partial payment, with the due box ticked and "Baki ৬১ টাকা".

## Layout, top to bottom
Left (dimmed behind a dialog):
1. Top bar red-to-purple gradient (darkened), back chevron, a small pink/blue memo icon and the title "নিরীক্ষণ".
2. Outlet title (bold, two lines): "Kamal Store (1618408-1942222558-Ma" ... "Road)" with a red crossed-out printer icon at the right edge; below, "রুট: Savar Bazar(Sat, Mon, Wed)" and "ক্লাস্টার: Madrasa Road".
3. Table, charcoal header "এসকেইউ | পরিমাণ | মূল্য"; rows partly hidden by the dialog.
4. Dialog (lavender-white rounded): bold title, question text, an outlined text field with floating label and value, then green "হ্যাঁ" and red "না".
5. Below the dialog: a grey card with an unticked checkbox and the due text.
6. Amber button "প্রোডাক্ট QC" with a cart icon; crimson-to-purple button "প্রিন্ট" with a printer icon.
Right (clear):
1. Same top bar and outlet block.
2. Table: charcoal header; five SKU rows; then four grey subtotal rows (cigarette, bidi, lighter, match); a "সর্বমোট" grand-total row.
3. White card with a ticked red checkbox and "Baki ৬১ টাকা".
4. Amber gradient "প্রোডাক্ট QC" and crimson-purple gradient "প্রিন্ট" buttons.
5. Android gesture bar at the bottom (black strip).

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| নিরীক্ষণ | Review | Top bar (both) |
| Kamal Store (1618408-1942222558-Ma … Road) | Outlet name, code and address (middle truncated by the printer icon) | Header |
| রুট: Savar Bazar(Sat, Mon, Wed) | Route: Savar Bazar (Saturday, Monday, Wednesday) | Header line 2 |
| ক্লাস্টার: Madrasa Road | Cluster: Madrasa Road | Header line 3 |
| এসকেইউ | SKU | Table header |
| পরিমাণ | Quantity | Table header |
| মূল্য | Price / value | Table header |
| MaxR-10S | SKU | Row 1 |
| ১০ | 10 | Row 1 qty |
| ৮০.০০ | 80.00 | Row 1 value |
| SAB-25s | SKU (lighter/bidi code) | Row 2 |
| ২৫ | 25 | Row 2 qty |
| ২২.০০ | 22.00 | Row 2 value |
| Aster | SKU | Row 3 |
| ১ | 1 | Row 3 qty |
| ১২.৫০ | 12.50 | Row 3 value |
| FB | SKU (match) | Row 4 |
| ১ | 1 | Row 4 qty |
| ২৮.০০ | 28.00 | Row 4 value |
| SL | SKU (match) | Row 5 |
| ১ | 1 | Row 5 qty |
| ১৯.০০ | 19.00 | Row 5 value |
| মোট সিগারেট | Total cigarettes | Subtotal row 1 |
| ১০ / ৮০.০০ | qty 10 / 80.00 | Subtotal row 1 |
| মোট বিড়ি | Total bidi | Subtotal row 2 |
| ২৫ / ২২.০০ | qty 25 / 22.00 | Subtotal row 2 |
| মোট লাইটার | Total lighter | Subtotal row 3 |
| ১ / ১২.৫০ | qty 1 / 12.50 | Subtotal row 3 |
| মোট ম্যাচ | Total match | Subtotal row 4 |
| ২ / ৪৭.০০ | qty 2 / 47.00 | Subtotal row 4 |
| সর্বমোট | Grand total | Total row |
| ৩৮ / ১৬১.৫০ | qty 38 / 161.50 | Total row |
| Baki ৬১ টাকা | Due 61 taka (Latin "Baki" plus Bangla digits and word) | Due card (right) |
| পরিশোধিত টাকার পরিমাণ লিখুন | Enter the amount of money paid | Dialog title (left) |
| এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন? | How much money are you collecting from the retailer now? | Dialog body |
| আদায়কৃত অর্থ | Amount collected | Dialog field floating label |
| 100 | 100 (Latin digits, typed) | Dialog field value |
| বাকিঃ ৬১.৫০ | Due: 61.50 | Dialog field, right side, bold |
| হ্যাঁ | Yes | Dialog green button |
| না | No | Dialog red button |
| এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন | Mark this sale as due | Checkbox label (left) |
| প্রোডাক্ট QC | Product QC | Amber button |
| প্রিন্ট | Print | Crimson-purple button |

## Data and figures shown
- Grand total 161.50 taka, 38 units: 10 cigarette sticks/packs (80.00), 25 bidi (22.00), 1 lighter (12.50), 2 matches (47.00).
- Paid now: 100. Due shown in the dialog: 61.50. After confirming, the due card shows "Baki ৬১ টাকা" (61 taka, rounded down from 61.50).
- Checkbox: unticked on the left (before), ticked red on the right (after).
- Red crossed-out printer icon: no printer connected / not printed yet.

## Colours and style
- Same red-to-purple header; page light grey; charcoal table header; subtotal rows on light grey; grand total on light grey.
- Dialog lavender-white, bold black title, purple outlined field (purple border), green and red filled buttons.
- Product QC amber-to-orange gradient; Print crimson-to-purple gradient; the ticked checkbox is red.
- No packshots on this page.

## Navigation and behaviour
- Ticking "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" opens the dialog; the SR types the amount received now (less than the total) and taps হ্যাঁ; না cancels.
- After হ্যাঁ the checkbox is ticked and the due card shows the remaining due.
- প্রোডাক্ট QC goes to the QC page (screenshots 25 and 26); প্রিন্ট starts the confirm and print flow (screenshot 28).
- The due amount is displayed rounded to whole taka on the card while the dialog shows two decimals.

## Differences between the image and the manual text
- The manual says "remaining due (বাকি) shown. Right: review with 'Baki 61 টাকা'"; the image confirms it, but the dialog shows ৬১.৫০ and the card shows ৬১ (the fraction is dropped, or rounded; unclear).
- The manual says "amount received now (less than the total)"; the typed value 100 and total 161.50 agree.
- The manual does not mention the subtotal rows by product type (cigarette, bidi, lighter, match) or the red crossed printer icon next to the outlet name.
- The card text "Baki" is Latin transliteration, not the Bangla word "বাকি" used in the dialog.
- The outlet title text is truncated in the middle ("…-Ma" and "Road)") because of the printer icon overlapping.

## Open questions for the owner
- Is the due shown on the card rounded down (61) while the stored due is 61.50, or is the card truncated for display? Which value goes on the memo and the ledger?
- What happens if the SR enters more than the total or zero in the field?
