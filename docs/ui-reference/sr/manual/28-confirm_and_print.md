# 28. Confirm and print

## Screens in the image
Two phone screens of the Review (নিরীক্ষণ) page for another outlet (Hafiz Store), both dimmed by a dialog. Left: "Are you sure? Sale will be submitted". Right: "Do you want to print this sale?".

## Layout, top to bottom
1. Status bar: "3:11", screen-capture icon, location pin, 4G, signal bars, battery 67.
2. Top bar red-to-purple gradient (darkened), back chevron, small memo icon and "নিরীক্ষণ".
3. Outlet block: bold "Hafiz Store (2650259-123-Agora Road)" with a green printer icon in a white tab at the right (printer connected); below "রুট: Agora GoliDaily" and "ক্লাস্টার: Agora Road".
4. Charcoal table header "এসকেইউ | পরিমাণ | মূল্য"; row "Aster ২৫ ৩১২.৫০"; row "মোট লাইটার ২৫ ৩১২.৫০".
5. A charcoal tab "ডিসকাউন্ট" (Discount) with a second table header below it (partly hidden), rows "Ast…" (two rows), then bold subtotal rows (cut off: "মো…" and "সর্…" with values ending ".৫০" / ".০০").
6. Grey card with an unticked checkbox: "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন".
7. Amber "প্রোডাক্ট QC" button; crimson-purple "প্রিন্ট" button.
8. Android 3-button nav bar.
9. Dialog centred (left): yellow warning triangle, blue bold "আপনি কি নিশ্চিত?", black "বিক্রয় জমা হবে", green "হ্যাঁ" and red "না". Right: blue circle "i", blue bold "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?", green "হ্যাঁ", red "না"; the right dialog has a second dark rounded layer behind it (a stacked dialog shadow), suggesting it sits on top of the first dialog.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:11 | Phone clock | Status bar |
| 67 | Battery | Status bar |
| নিরীক্ষণ | Review | Top bar |
| Hafiz Store (2650259-123-Agora Road) | Outlet name, code, address | Header |
| রুট: Agora GoliDaily | Route: Agora GoliDaily | Header |
| ক্লাস্টার: Agora Road | Cluster: Agora Road | Header |
| এসকেইউ | SKU | Table header |
| পরিমাণ | Quantity | Table header |
| মূল্য | Price | Table header |
| Aster | SKU (lighter) | Row |
| ২৫ | 25 | Qty |
| ৩১২.৫০ | 312.50 | Value |
| মোট লাইটার | Total lighter | Subtotal row |
| ২৫ / ৩১২.৫০ | 25 / 312.50 | Subtotal row |
| ডিসকাউন্ট | Discount | Tab label |
| এসকেইউ / পরিমাণ / মূল্য | SKU / quantity / price | Discount table header (dimmed) |
| Ast… (two rows) | Discount rows beginning "Ast" (cut off) | Discount table |
| মো… , সর্… | Totals rows, cut off ("মোট…", "সর্বমোট") | Discount table, values end ".৫০", ".০০" |
| এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন | Mark this sale as due | Checkbox label |
| প্রোডাক্ট QC | Product QC | Button |
| প্রিন্ট | Print | Button |
| আপনি কি নিশ্চিত? | Are you sure? | Left dialog title |
| বিক্রয় জমা হবে | The sale will be submitted | Left dialog body |
| আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান? | Do you want to print this sale? | Right dialog title |
| হ্যাঁ | Yes | Both dialogs, green |
| না | No | Both dialogs, red |

## Data and figures shown
- Hafiz Store memo: 25 lighters (Aster) worth 312.50 taka, i.e. 12.50 each, matching the 1-unit price 12.50 in screenshot 27. Total lighter 25 / 312.50.
- A discount section ("ডিসকাউন্ট") exists on this memo with Aster rows and totals, hidden by the dialog; its figures are not readable (ends in .৫০ and .০০).
- Checkbox unticked: not a due sale.
- Printer icon is green (connected), versus the red crossed-out icon in screenshot 27.
- Battery 67, 4G.

## Colours and style
- Same gradient header (darkened by scrim), light-grey page, charcoal table header, rows in light grey.
- Dialogs white with large radius; warning triangle yellow (confirm), info circle blue (print question); titles blue bold; body black; buttons green (#4CAF50-ish) and red (#F44336-ish), white labels.
- Amber and crimson-purple gradient bottom buttons.
- Right dialog has a stacked grey shadow layer behind it.
- No packshots.

## Navigation and behaviour
- Tap প্রিন্ট on the review page: first dialog "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে". হ্যাঁ submits (saves) the sale; না returns.
- Then the second dialog "Do you want to print this sale?": হ্যাঁ prints the memo on the printer; না skips printing (the sale is already saved, memo can be printed later from Memo).
- Success message follows (screenshot 29).

## Differences between the image and the manual text
- Matches the manual's quoted texts. The manual does not mention that the review page here shows a "ডিসকাউন্ট" section with its own table.
- The two dialogs differ in icon type (warning vs information) and the second appears to be layered over the first; the manual treats them as left and right screens.
- The manual's example is from a different memo (Kamal Store, screenshot 24 and 27); this one is Hafiz Store with only lighters sold, which the manual does not describe.
- The green printer icon (connected) is not described in the manual; screenshot 27 has the red crossed one.
- The first dialog's body text is "বিক্রয় জমা হবে" with no "এই" or question mark.

## Open questions for the owner
- Does the discount section show real discounts on this memo (deferred programmes) or is it always empty in the vendor app? Its lines are hidden behind the dialog.
- If the SR taps না on the print question, is the sale already saved (as the success message in screenshot 29 suggests)?
