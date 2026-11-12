# 38. Collect a due

## Screens in the image
Two phone screens of the মেমো (Memo) screen for the credit outlet "Tasmin (1618415)". Left: the memo with the due box and the green পরিশোধিত করুন button. Right: the same screen dimmed with the success dialog on top.

## Layout, top to bottom
Left screen (no status bar visible):
1. Top bar red-to-purple gradient: white back chevron, small coloured memo icon, white title "মেমো".
2. A white bordered note box with an info icon and a two-line grey Bangla note, and a round grey-white circle at its top-right edge holding a dark-grey printer icon.
3. White outlet selector: "Tasmin (1618415)" in dark text, then ": ২৯১.৫০ ৳" in purple and "বাকি" in red, drop-down arrow at the right.
4. Dark-grey alphabet bar, two rows: "All" in a purple gradient chip then letters.
5. Memo table: red-to-purple gradient header (three titles), five white SKU rows, a light-grey block of four small category totals, a white bold "মোট" row.
6. Purple summary lines "মোট QC" and "সর্বমোট".
7. A red dashed-border box: at left a small amber card icon and red text on two lines; a thin red vertical divider; at right a green (about #0B7A3E) rounded button "পরিশোধিত করুন".
8. Bottom buttons: red-to-purple gradient "প্রিন্ট" with printer icon; amber "এডিট" with pencil icon.

Right screen: dimmed overlay; a red crossed printer circle at the top right; the table and the due box dimmed; a centred white rounded dialog with a green circle check mark, blue bold centred text on two lines, and a green "ওকে" button at the lower right.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| মেমো | Memo | Top bar |
| আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন। | You can choose an outlet and view a memo. You can also reprint the memo from here. | Info note |
| Tasmin (1618415) | Outlet and code | Selector |
| : ২৯১.৫০ ৳ | Memo value 291.50 taka | Selector, purple |
| বাকি | Due (credit) | Selector, red |
| All A B C D E F G H I J K M N P R S T V Z | Letter filter | Filter bar |
| এসকেইউ ; পরিমাণ ; দাম | SKU ; Quantity ; Price | Table header |
| MaxR-10S ; ১০ ; ৮০.০০ | SKU, quantity, price | Row 1 |
| MaxR-20S ; ২০ ; ১৬০.০০ | same | Row 2 |
| AB-25s ; ২৫ ; ২০.০০ | same | Row 3 |
| Aster ; ১ ; ১২.৫০ | same | Row 4 |
| SL ; ১২ ; ১৯.০০ | same | Row 5 |
| মোট সিগারেট ; ৩০ ; ২৪০.০০ | Total cigarettes | Subtotal |
| মোট বিড়ি ; ২৫ ; ২০.০০ | Total bidi | Subtotal |
| মোট লাইটার ; ১ ; ১২.৫০ | Total lighter | Subtotal |
| মোট ম্যাচ ; ১২ ; ১৯.০০ | Total match | Subtotal |
| মোট ; ৬৮ ; ২৯১.৫০ | Total | Bold row |
| মোট QC | Total QC | Value: - ০.০০ |
| সর্বমোট | Grand total | Value: ২৯১.৫০ |
| এই বিক্রয়টি বাকিতে করা হয়েছে। | This sale was made on credit. | Due box (wrapped on two lines) |
| পরিশোধিত করুন | Mark as paid | Green button |
| প্রিন্ট | Print | Button |
| এডিট | Edit | Button |
| এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে। | This memo has been successfully marked as paid. | Success dialog |
| ওকে | OK | Green dialog button |

The right screen's background filter bar shows "All A B C D E F G H I J K M" then "R S T V Z".

## Data and figures shown
- Memo total 291.50 taka (240.00 cigarettes + 20.00 bidi + 12.50 lighter + 19.00 match = 291.50, consistent); 68 total quantity units (30 + 25 + 1 + 12).
- Cigarette quantities are 10 and 20 with prices 80.00 and 160.00, so they are counted in sticks here (a 10s and a 20s pack at 8.00 per stick). Bidi 25 (a 25s pack) for 20.00. Lighter 1 for 12.50. Match 12 for 19.00. These are inferences from the price arithmetic.
- QC is -0.00; grand total equals the memo total.

## Colours and style
- Due box: dashed red (about #D32F2F) border, red text, amber card icon. Pay button: dark green with white text. Success dialog: white, green check circle, blue (about #1565C0) bold text, green OK button. Printer circle: grey (left), red crossed (right).
- Purple totals text. Table as on other memo screens. No packshots.

## Navigation and behaviour
- Manual: a due memo shows "এই বিক্রয়টি বাকিতে করা হয়েছে" with পরিশোধিত করুন (Mark as paid); after confirming, "This memo has been marked as paid". The SR chooses the outlet with a due, taps পরিশোধিত করুন, then হ্যাঁ.
- Image: the success dialog is shown on the right. The confirm dialog with হ্যাঁ that the manual mentions is not shown. On the right screen the due box and its green button are still visible behind the dialog, so the box is probably refreshed after OK.
- The printer icon turns red and crossed on the right screen.

## Differences between the image and the manual text
- The manual says the success message is in English ("This memo has been marked as paid"); the image shows Bangla text with "সফলভাবে" (successfully).
- The manual mentions a হ্যাঁ confirmation; that step is not in this image.
- The manual lists the due-sale wording in a "What you see" line; the image adds the amber card icon and the red divider.

## Open questions for the owner
- What does the printer icon state (grey vs red crossed) mean?
- Does পরিশোধিত করুন record a payment amount and method, or only a full paid flag?
- Is a part-payment possible?
