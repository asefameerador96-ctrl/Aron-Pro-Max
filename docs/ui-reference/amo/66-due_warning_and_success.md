# 66. Due warning and success
(Manual section 19, Sales Submit (Bikroy Joma).)

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app: the "বিক্রয় জমা" (Sales submit) screen dimmed by a grey overlay with a white modal dialog on top.
- LEFT: confirmation dialog with a yellow warning triangle: the AMO still has due with 1 retailer; buttons Yes (green) and No (red). Behind it: table with sales 25, stock 11,700; submit button blue (enabled).
- RIGHT: success dialog with a green tick circle and one green OK button. Behind it: table with sales 118, stock 45,150; submit button blue.
These are in-app dialogs (not Android system dialogs). Status bar: only the gesture pill visible.

## Layout, top to bottom
LEFT screen (dimmed background, same as screen 65 right):
1. Top bar deep purple (dimmed), back arrow left, title "বিক্রয় জমা".
2. Device status row "ডিভাইস স্ট্যাটাস" + green dot "অনলাইন".
3. Table header (purple) "বিষয় | ডিভাইস | সার্ভার" and rows (outlet, sales, stock, QC, promotion, then further rows partly hidden by the dialog). Visible data: outlet ১/১, sales ২৫/২৫, stock ১১,৭০০/১১,৭০০, QC ০/০, promotion ০/০.
4. Dialog (white, strongly rounded corners, centred, covers table rows 6 to 9 and the top of the pink box):
   a. Yellow/amber warning triangle with white exclamation mark, centred.
   b. Message, centred, bold blue text, 3 lines.
   c. Two buttons at lower right, side by side: green "হ্যাঁ" (left), red "না" (right); small rounded rectangles with a soft shadow, white text.
5. Behind and below the dialog: pink message box (dimmed), green "ডাটা সিঙ্ক করুন" button (dimmed), blue "বিক্রয় জমা" button (dimmed).

RIGHT screen (dimmed background):
1. Same top bar, device status row, table header. Visible data: outlet ১/১, sales ১১৮/১১৮, stock ৪৫,১৫০/৪৫,১৫০, QC ০/০, promotion ০/০, distribution row ০/০.
2. Dialog (white, rounded, centred, wider than tall):
   a. Green filled circle with white tick, centred.
   b. Message, centred, blue text, one line.
   c. Green "ওকে" button at the lower right.
3. Behind: pink box, green sync button, blue submit button (dimmed).

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় জমা | Sales submit | Top bar title (both screens); also blue button behind |
| ডিভাইস স্ট্যাটাস | Device status | Row under the top bar (both) |
| অনলাইন | Online | Green text (both) |
| বিষয় / ডিভাইস / সার্ভার | Item / Device / Server | Table header (both) |
| আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন | Outlet, Sales, Stock, QC, Promotion | Table row labels (both) |
| ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা | Distribution and OOS performance | Table row label, visible on RIGHT screen |
| ১ / ১ | 1 / 1 | Outlet row (both) |
| ২৫ / ২৫ | 25 / 25 | Sales row, LEFT |
| ১১৮ / ১১৮ | 118 / 118 | Sales row, RIGHT |
| ১১,৭০০ / ১১,৭০০ | 11,700 / 11,700 | Stock row, LEFT |
| ৪৫,১৫০ / ৪৫,১৫০ | 45,150 / 45,150 | Stock row, RIGHT |
| ০ / ০ | 0 / 0 | QC, promotion (and distribution on right) rows |
| আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? | You still have due with 1 retailer. Do you want to submit your sales? | LEFT dialog message (wrapped in 3 lines: "আপনার এখনো ১টি রিটেইলারের / কাছে বাকি রয়েছে। আপনি আপনার / বিক্রয় জমা দিতে চান?") |
| হ্যাঁ | Yes | LEFT dialog, green button |
| না | No | LEFT dialog, red button |
| বিক্রয় সফল ভাবে জমা হয়েছে | Sales submitted successfully | RIGHT dialog message (note the space in "সফল ভাবে") |
| ওকে | OK | RIGHT dialog, green button |
| (partly hidden) আজকের কাজ শেষ করার আগে … সিঙ্ক করুন এবং … | the pink instruction box from screen 65 | behind both dialogs, dimmed and partly covered |
| ডাটা সিঙ্ক করুন | Sync data | Dimmed green button behind both |
Digits are Bangla (১ in "১টি" is Bangla one followed by "টি", the Bangla counter for items).

## Data and figures shown
- "১টি রিটেইলার" = the count of retailers (outlets) with an unpaid due memo: 1.
- The price row (মূল্য সম্মতি) is hidden behind the dialog on the RIGHT screen (a row starting "মূল" is visible) but absent on the LEFT screen (rows after the distribution row start with "জ" and "সা"), consistent with screen 65 where the row also appears on only one capture.
- Background table (identical to screen 65): matched Device and Server counts; left = 25 sales / 11,700 stock, right = 118 sales / 45,150 stock. Note the figures are swapped relative to screen 65's left/right order.

## Colours and style
- Grey translucent overlay dims the whole screen behind the dialog.
- Dialog: white, large corner radius (about 24 dp), soft shadow.
- Dialog text: blue (about #1565C0), bold on the warning, regular weight on the success.
- Warning icon: amber/yellow triangle, white "!" inside. Success icon: solid green circle with white check mark (about #43A047).
- Buttons: Yes and OK green (about #4CAF50), No red/orange-red (about #F44336), white text, rounded small buttons with shadow.
- Top bar: deep purple (dimmed to near #2B1060 under the overlay).

## Navigation and behaviour
- LEFT dialog appears after tapping "বিক্রয় জমা" if any retailer still has an unpaid due. "না" (No): close the dialog and go back to collect the due first (via Memo). "হ্যাঁ" (Yes): submit sales anyway.
- RIGHT dialog appears after a successful submit. "ওকে": close the dialog. After Sales submit no more sales can be made for the day (manual note).
- Dialogs are modal; the screen behind is not interactive.

## Differences between the image and the manual text
- Manual quotes the English wording "You still have due with 1 retailer. Do you want to submit sales?"; the Bangla on screen says "your sales" (আপনার বিক্রয়), a trivial wording difference.
- The manual gives the Bangla of the success message correctly; the image has a space inside "সফল ভাবে".
- Otherwise none.

## Open questions for the owner
- None. (The dialog says "1 retailer"; the plural/other counts, e.g. "২টি", presumably follow the same template.)
