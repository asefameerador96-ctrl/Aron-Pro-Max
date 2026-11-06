# 25. Partial payment (due)

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, both the Review screen (নিরীক্ষণ) of a sale.
- LEFT: the Review screen dimmed (dark overlay) with a modal dialog "পরিশোধিত টাকার পরিমাণ লিখুন" (Enter amount paid) on top. This dialog is a vendor-app dialog, not an Android system dialog.
- RIGHT: the same Review screen, undimmed, after the dialog was confirmed: the "mark as due" checkbox is ticked and shows "Baki ৪৮ টাকা".
Status bar not shown in this image.

## Layout, top to bottom
LEFT screen (dimmed behind the dialog):
1. Top bar: deep purple (dark indigo when dimmed), centred white title "নিরীক্ষণ", back arrow (outlined triangle-style arrow) at the left.
2. Round light-grey button at the top right, overlapping the lower edge of the top bar, holding a red printer icon with a diagonal slash (printer NOT connected).
3. Outlet heading (large bold text): "Babu Store (1618411-1723412771-Madrasa Road)" wrapped to two lines.
4. Below it two smaller lines: "রুট: Savar Bazar(Sat, Mon, Wed)" and "ক্লাস্টার: Savar Metro".
5. Table with dark charcoal header row: এসকেইউ (left), পরিমাণ (centre), মূল্য (right). Rows (first row visible, others partly hidden by the dialog): MaxR-10S, ১০, ৮০.০০; then rows starting "AB-", "As", "FB", "SL" with values ending ".00"/".৫০" hidden by the dialog; then four light-grey category total rows (মোট ...); then a grand-total row starting "স" with value ending "৫০".
6. Modal dialog (centred, light lavender, rounded corners, white-ish) containing:
   a. bold centred title "পরিশোধিত টাকার পরিমাণ লিখুন".
   b. centred question text "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?" (wraps to two lines).
   c. outlined text field (purple 2 px border, rounded) with floating label "আদায়কৃত অর্থ" on the border, typed value "100" (Latin digits, text cursor after it) at the left, and bold "বাকিঃ ৪৮.৫০" at the right inside the same field (live remaining due).
   d. two buttons at the bottom right: green "হ্যাঁ" (left) and red "না" (right), both small rounded with shadow.
7. Behind the dialog: a rounded card holding an UNCHECKED checkbox (empty square, dark border) and the text "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন".
8. Two wide buttons (dimmed): left orange-brown "প্রোডাক্ট QC" with a cart icon; right green "প্রিন্ট" with a printer icon.
9. At the very bottom edge, the top of another rounded outlined element is cut off (unreadable).
10. Android gesture bar at the bottom (thin white pill on black).

RIGHT screen (normal):
1. Top bar: deep purple, centred white "নিরীক্ষণ", back arrow at left.
2. Round light-grey button at top right, red crossed-out printer icon (printer not connected).
3. Outlet heading "Babu Store (1618411-1723412771-Madrasa Road)" two lines, then "রুট: Savar Bazar(Sat, Mon, Wed)" and "ক্লাস্টার: Savar Metro".
4. Table, dark charcoal header with white text: এসকেইউ | পরিমাণ | মূল্য. White item rows:
   - MaxR-10S | ১০ | ৮০.০০
   - AB-12s | ১২ | ৯.০০
   - Aster | ১ | ১২.৫০
   - FB | ১ | ২৮.০০
   - SL | ১ | ১৯.০০
   Four light-grey category total rows (smaller text):
   - মোট সিগারেট | ১০ | ৮০.০০
   - মোট বিড়ি | ১২ | ৯.০০
   - মোট লাইটার | ১ | ১২.৫০
   - মোট ম্যাচ | ২ | ৪৭.০০
   Grand total row (light grey, bold): সর্বমোট | ২৫ | ১৪৮.৫০
5. White rounded card with soft shadow containing a TICKED checkbox (purple fill, white tick) and the text "Baki ৪৮ টাকা" (replaces the label "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" once ticked).
6. Two wide buttons side by side: left orange-to-amber gradient "প্রোডাক্ট QC" (cart-with-tick icon at the left of the label); right green gradient "প্রিন্ট" (printer icon). Both enabled-looking.
7. At the bottom edge the top of another rounded outlined element is cut off (unreadable).
8. Android gesture bar.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| নিরীক্ষণ | Review | Top bar title (both screens) |
| Babu Store (1618411-1723412771-Madrasa Road) | Outlet name (code-phone-cluster/area) | Heading, both screens; digits Latin |
| রুট: Savar Bazar(Sat, Mon, Wed) | Route: Savar Bazar (visit days Sat, Mon, Wed) | Under heading |
| ক্লাস্টার: Savar Metro | Cluster: Savar Metro | Under route |
| এসকেইউ | SKU | Table header, left |
| পরিমাণ | Quantity | Table header, centre |
| মূল্য | Price / value | Table header, right |
| MaxR-10S | SKU name | Row 1 |
| AB-12s | SKU name | Row 2 (right screen) |
| Aster | SKU name | Row 3 (right screen) |
| FB | SKU name | Row 4 (right screen) |
| SL | SKU name | Row 5 (right screen) |
| ১০ ; ১২ ; ১ ; ১ ; ১ | quantities of the five SKUs | পরিমাণ column, Bangla digits |
| ৮০.০০ ; ৯.০০ ; ১২.৫০ ; ২৮.০০ ; ১৯.০০ | line values | মূল্য column, Bangla digits, 2 decimals |
| মোট সিগারেট | Total cigarette | Category total row |
| মোট বিড়ি | Total bidi | Category total row |
| মোট লাইটার | Total lighter | Category total row |
| মোট ম্যাচ | Total match | Category total row |
| ১০ / ১২ / ১ / ২ | category quantities | category total rows |
| ৮০.০০ / ৯.০০ / ১২.৫০ / ৪৭.০০ | category values | category total rows |
| সর্বমোট | Grand total | Bottom row of table |
| ২৫ | total quantity | Grand total row |
| ১৪৮.৫০ | grand total value | Grand total row |
| এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন | Mark this sale as due | Checkbox label (left screen, unticked) |
| Baki ৪৮ টাকা | Due 48 taka (Latin word "Baki", Bangla digits, "টাকা") | Checkbox label (right screen, ticked) |
| পরিশোধিত টাকার পরিমাণ লিখুন | Enter the amount paid | Dialog title |
| এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন? | How much money are you collecting from the retailer now? | Dialog body |
| আদায়কৃত অর্থ | Amount collected | Floating label of the text field |
| 100 | amount typed (Latin digits) | Text field value |
| বাকিঃ ৪৮.৫০ | Due: 48.50 | Right side of the text field, bold, Bangla digits |
| হ্যাঁ | Yes | Green dialog button |
| না | No | Red dialog button |
| প্রোডাক্ট QC | Product QC | Orange button (QC in Latin letters) |
| প্রিন্ট | Print | Green button |

## Data and figures shown
- Sale: MaxR-10S 10 (80.00), AB-12s 12 (9.00), Aster 1 (12.50), FB 1 (28.00), SL 1 (19.00). Category totals: cigarette 10 / 80.00, bidi 12 / 9.00, lighter 1 / 12.50, match 2 / 47.00. Grand total 25 units, 148.50 taka. (Cigarette 80+9+12.5+47 = 148.50, consistent. Note: the digit in "৮০.০০" and "৪৮"/"৪৭" is easy to confuse; values were reconciled against the grand total.)
- Amount paid entered: 100 (Latin digits). Remaining due shown live: ৪৮.৫০ (148.50 - 100).
- After confirming, the checkbox label shows "Baki ৪৮ টাকা": the due is shown truncated to whole taka (48), not 48.50. Whether it rounds down or truncates is not determinable from one example.
- Numbers in the table and totals are Bangla digits with a Latin-style decimal point and two decimals; the typed amount in the field is Latin digits.
- Printer icon: red and crossed out (not connected) on both screens.

## Colours and style
- Top bar deep purple (indigo, approx #4A1D96-like), flat, white title.
- Page background very light lavender/near white on the right screen; light grey when dimmed on the left.
- Table header dark charcoal with white text; item rows white; category total rows light grey; grand total row light grey with bold text.
- Checkbox card: white, rounded, soft shadow; ticked checkbox purple.
- Dialog: light lavender (#EDE7F3-like) with large rounded corners and shadow; text field purple outline; "বাকিঃ ৪৮.৫০" bold dark; হ্যাঁ green; না red.
- Buttons: প্রোডাক্ট QC orange-to-amber gradient; প্রিন্ট green gradient (yellow-green to green), rounded corners, white label.
- Printer icon button: grey circle, red icon.
- No packshots on this screen.

## Navigation and behaviour
Per the manual: after ticking the due checkbox on the Review screen the dialog asks how much was collected now. Enter the amount received (must be less than the total) and tap হ্যাঁ. The remaining due is shown live inside the field. After হ্যাঁ the dialog closes and the checkbox label shows the due ("Baki N টাকা"). না cancels the dialog (inferred; the manual does not state what happens to the checkbox). The remaining due stays on the outlet until collected later (manual section 16, Memo > পরিশোধিত করুন). প্রিন্ট saves and prints the memo; প্রোডাক্ট QC opens the product quality check (not described on this page).

## Differences between the image and the manual text
- The manual says the review shows "Baki 48 টাকা"; the image shows "Baki ৪৮ টাকা" (Bangla digits) and the dialog shows the exact remaining "বাকিঃ ৪৮.৫০" with decimals.
- The manual does not mention the field label "আদায়কৃত অর্থ", the question text, the না (No) button, the live remaining-due display inside the field, or the red crossed printer icon at the top right.
- The manual says only "tap হ্যাঁ (Yes)"; the dialog also has a red না button.
- On the left screen the checkbox is still unticked while the dialog is open (the tick is applied only after হ্যাঁ, or the dialog opens on tick and the box is reverted until confirmed; cannot be told from the image).

## Open questions for the owner
- Does "Baki ৪৮ টাকা" round down, truncate or round to nearest from 48.50?
- What exactly does না do: untick the box and keep the sale fully paid, or just close the dialog?
- Is the entered amount validated (must be 0 < amount < total)? The manual says "less than the total".
