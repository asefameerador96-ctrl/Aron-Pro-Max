# 44. Confirm payment

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app: the same memo screen ("মেমো", outlet Siyam on Savar BazarDaily) dimmed by a grey overlay, each with a vendor dialog on top (app dialogs, not Android system dialogs).
- LEFT: confirmation dialog "আপনি কি নিশ্চিত?" with হ্যাঁ and না.
- RIGHT: success dialog "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" with ওকে.
The memo behind both dialogs is the one from screenshot 43 (still showing the due box).

## Layout, top to bottom
LEFT screen:
1. Top bar (dimmed): deep purple, back arrow, centred title "মেমো".
2. Printer circle (dimmed) at the right: red crossed-out printer icon.
3. Outlet card (dimmed, partly cropped at top): shop icon; "রুট: Savar BazarDaily"; "খুচরা বিক্রেতাঃ Siyam (1618410-1832714554-M" (cut off).
4. Table header (dimmed): "এসকেইউ" | "পরিমাণ" | "দাম".
5. Rows visible above the dialog: MaxR-10S ১০ ৮০.০০; ABS ২৫ ২০.০০; Aster ১ ১২.৫০; FB ১ (price hidden behind the dialog edge, shows "২.৩৩"); SL (row partly hidden; "৫৮" end of ১.৫৮ visible).
6. Centred white rounded dialog (about 80% width):
   - Yellow warning triangle with a white exclamation mark at the top centre.
   - Blue text "আপনি কি নিশ্চিত?".
   - Two buttons at the bottom right: green "হ্যাঁ" (white text), red "না" (white text), small rounded rectangles with shadow.
7. Below the dialog (dimmed): green "মোট ডিসকাউন্ট" with "- ০.০০"; green "সর্বমোট" with "১১৬.৪২"; the dashed red due box with the card icon, "এই বিক্রয়টি বাকিতে করা হয়েছে।" and the green "পরিশোধিত করুন" button; bottom buttons "প্রিন্ট" (blue) and "এডিট" (grey). Black bottom strip with gesture pill.
RIGHT screen: same dimmed background (identical content).
1. Same top bar, red printer circle, outlet card, table header and rows (MaxR-10S ১০ ৮০.০০; ABS ২৫ ২০.০০; Aster ১ ১২.৫০; FB ১ ...; SL).
2. Centred white rounded dialog (slightly taller, about 75% width): large green circle with a white check mark at the top centre; blue text on two lines "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে" / "চিহ্নিত হয়েছে।"; a green "ওকে" button at the bottom right.
3. Below (dimmed): same discount/grand total, the due box still visible (the success dialog is shown before the memo refreshes), প্রিন্ট and এডিট buttons.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| মেমো | Memo | Top bar title (both) |
| রুট: | Route | Dimmed outlet card |
| Savar BazarDaily | Route name | Dimmed outlet card |
| খুচরা বিক্রেতাঃ | Retailer | Dimmed outlet card |
| Siyam (1618410-1832714554-M... | Retailer name (cut off) | Dimmed outlet card |
| এসকেইউ / পরিমাণ / দাম | SKU / Quantity / Price | Dimmed table header |
| MaxR-10S, ABS, Aster, FB, SL | SKU codes | Dimmed rows |
| ১০, ২৫, ১, ১ | quantities | Dimmed rows |
| ৮০.০০, ২০.০০, ১২.৫০, ২.৩৩ | prices | Dimmed rows |
| মোট ডিসকাউন্ট / - ০.০০ | Total discount / minus 0.00 | Dimmed, below dialog |
| সর্বমোট / ১১৬.৪২ | Grand total / 116.42 | Dimmed, below dialog |
| এই বিক্রয়টি বাকিতে করা হয়েছে। | This sale was made on due. | Dimmed due box |
| পরিশোধিত করুন | Mark as paid | Dimmed green button |
| প্রিন্ট / এডিট | Print / Edit | Dimmed bottom buttons |
| আপনি কি নিশ্চিত? | Are you sure? | LEFT dialog (blue) |
| হ্যাঁ | Yes | LEFT dialog, green button |
| না | No | LEFT dialog, red button |
| এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে। | This memo has been successfully marked as paid. | RIGHT dialog (blue, two lines) |
| ওকে | OK | RIGHT dialog, green button |
Bangla digits throughout; the dimmed rows are partly hidden by the dialogs.

## Data and figures shown
- Amount being settled: grand total 116.42 taka (visible dimmed: "সর্বমোট ১১৬.৪২"), whole memo, same as in screenshot 43.
- No amount entry field: the confirmation is a plain Yes/No for the whole due.

## Colours and style
- Background memo dimmed by a grey-black translucent overlay.
- Dialogs: white, large rounded corners (about 20 px), soft shadow, centred; message text blue (#1565C0-ish), bold-ish Bangla.
- Warning icon: yellow triangle with exclamation. Success icon: green circle with white check.
- হ্যাঁ button: green (#43A047-ish), white text. না button: red (#E53935-ish), white text. ওকে: green, white text.
- Printer icon (dimmed) red crossed out. No packshots.

## Navigation and behaviour
- Reached by tapping the green "পরিশোধিত করুন" button on a due memo (screenshot 43).
- LEFT dialog: হ্যাঁ confirms; না cancels and closes the dialog.
- RIGHT dialog (after হ্যাঁ): ওকে closes the message. Per the manual, "The due mark is removed from the outlet", i.e. the "বাকি" amount disappears from the outlet drop-down and the memo is no longer marked due.
- The memo behind the success dialog still shows the due box; it refreshes after OK (not stated in the manual).

## Differences between the image and the manual text
- The manual gives the success text in English only ("This memo has been marked as paid"); the Bangla in the image is "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" (adds "সফলভাবে" = successfully).
- The manual does not mention the warning triangle, the green check icon, or the red printer icon behind.
- Otherwise consistent: "আপনি কি নিশ্চিত?" with হ্যাঁ / না, then ওকে.

## Open questions for the owner
- Does marking paid record a payment date/time and the collector for reports (the manual says only that the due mark is removed)? The rebuild needs this for the ledger.
