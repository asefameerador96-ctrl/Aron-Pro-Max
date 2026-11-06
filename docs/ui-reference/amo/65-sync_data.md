# 65. Sync data
(Manual section 19, "Sales Submit (Bikroy Joma)". The screen title in the app is "বিক্রয় জমা".)

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, the same screen (Sales Submit / Sync data) in two states. Neither is a system dialog.
- LEFT: before sync. The "বিক্রয় জমা" (Sales submit) button at the bottom is GREY = disabled. Table figures: sales 118, stock 45,150.
- RIGHT: after sync. The "বিক্রয় জমা" button is BLUE = enabled. Table figures: sales 25, stock 11,700.
(The two screens show different data sets, so they are two separate captures, not one before/after of the same day. The manual says Sales submit is enabled only after sync.)
Status bar: only the Android gesture/nav pill at the very bottom is visible; nothing else to note.

## Layout, top to bottom
Same layout on both screens.
1. Top bar: solid deep purple, full width. Back arrow (white, outlined left-pointing chevron/triangle) at far left. Centred white bold title "বিক্রয় জমা".
2. Below the bar, right-aligned row on the page background: label "ডিভাইস স্ট্যাটাস" (black) followed by a green filled dot and the green text "অনলাইন" (online).
3. Table, no card border, white rows on the page background:
   - Header row: deep purple filled bar with rounded top corners, white bold text. Three columns: "বিষয়" (left, wide), "ডিভাইস" (middle), "সার্ভার" (right).
   - 8 or 9 data rows (see below) separated by thin light-grey lines; row label left, the two numbers centred under the "ডিভাইস" and "সার্ভার" columns. Row order: আউটলেট; বিক্রয়; স্টক; কিউসি; প্রমোশন; ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা; মূল্য সম্মতি (present on the LEFT screen only; the RIGHT screen has no price row and goes straight from the distribution row to জয়েন্ট কল); জয়েন্ট কল; সার্ভে. So LEFT = 9 rows, RIGHT = 8 rows.
4. Warning message box: light pink/rose fill, rounded corners, with a thick crimson (dark red) left and right edge accent; black body text in Bangla (three lines on left screen, two on right). Sits between the table and the buttons.
5. Button 1: full-width rounded rectangle, GREEN, white text with a "sync" icon (two arrows forming a loop, rectangular) before the label "ডাটা সিঙ্ক করুন". Enabled on both screens.
6. Button 2: full-width rounded rectangle with a white "box with down arrow / save" icon before the label "বিক্রয় জমা". LEFT: grey fill (disabled). RIGHT: dark blue fill (enabled).
7. Remaining lower area is empty page background.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় জমা | Sales submit (Bikroy Joma) | Top bar title; also the label of button 2 |
| ডিভাইস স্ট্যাটাস | Device status | Row under the top bar |
| অনলাইন | Online (green) | Right of device status |
| বিষয় | Item / subject | Table header, first column |
| ডিভাইস | Device | Table header, second column |
| সার্ভার | Server | Table header, third column |
| আউটলেট | Outlet | Row 1 label |
| বিক্রয় | Sales | Row 2 label |
| স্টক | Stock | Row 3 label |
| কিউসি | QC (product quality check) | Row 4 label |
| প্রমোশন | Promotion | Row 5 label |
| ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা | Distribution and OOS (out of stock) performance. "OOS" is written with Latin letters O,O,S in the Bangla sentence (the image shows "০০S"-like glyphs; treat as "OOS") | Row 6 label |
| মূল্য সম্মতি | Price compliance (manual lists "price") | Row 7 label, LEFT screen only |
| জয়েন্ট কল | Joint call | Row 8 label |
| সার্ভে | Survey | Row 9 label |
| ১ / ১ | 1 / 1 | Outlet row, Device and Server (both screens) |
| ১১৮ / ১১৮ | 118 / 118 | Sales row, LEFT screen |
| ২৫ / ২৫ | 25 / 25 | Sales row, RIGHT screen |
| ৪৫,১৫০ / ৪৫,১৫০ | 45,150 / 45,150 | Stock row, LEFT screen |
| ১১,৭০০ / ১১,৭০০ | 11,700 / 11,700 | Stock row, RIGHT screen |
| ০ / ০ | 0 / 0 | QC, Promotion, Distribution and OOS, Price compliance, Joint call, Survey rows (both screens) |
| আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই সেকশন থেকে বিক্রয় জমা সাবমিট করুন। | Before finishing today's work, you must sync all operation data and submit sales from this section. | Pink message box (wrapped over 3 lines on left, 2 on right) |
| ডাটা সিঙ্ক করুন | Sync data | Green button |
| বিক্রয় জমা | Sales submit | Grey (left) / blue (right) button |
All numbers are in Bangla digits (০১২৩৪৫৬৭৮৯) with Latin comma thousand separators ("৪৫,১৫০"). No units are shown. No Latin text on screen except "OOS".

## Data and figures shown
- Each row compares the count held on the phone (ডিভাইস) with the count the server has (সার্ভার). The manual: check that Device and Server figures match, then tap Sales submit.
- LEFT example: outlet 1/1, sales 118/118, stock 45,150/45,150, all others 0/0. Matched.
- RIGHT example: outlet 1/1, sales 25/25, stock 11,700/11,700, all others 0/0. Matched.
- Stock is in base units per the rebuild's rules (sticks etc.); the screen shows no unit label.
- Device status "অনলাইন" is shown in green.

## Colours and style
- Top bar and table header: deep purple (about indigo-violet #4B1F96 / dark purple).
- Page background: very light lavender-white (near #FBF5FF), not pure white.
- Table: flat, no card shadow; rows divided by light grey hairlines; text dark grey; row labels left aligned, numbers centred.
- Message box: light pink fill, crimson side bars, rounded; dark text.
- Buttons: rounded rectangles, full width, white text/icon. Sync = green (about #1AA24A). Submit = dark blue (about #0B4EA2) when enabled, mid grey (about #A0A0A0) when disabled.
- Online indicator: green circle + green text.
- No packshots, no gradient on this screen (bar is flat purple).

## Navigation and behaviour
- Back arrow returns to the Dashboard.
- "ডাটা সিঙ্ক করুন": uploads all operation data to the server and refreshes the Server column. Always enabled in the image.
- "বিক্রয় জমা": grey and disabled until a sync has been done; blue and enabled after sync. Tapping it (see 66) may first warn about unpaid dues, then submits the day's sales. After submit no more sales can be made that day.
- Per the manual: when all calls are finished tap Sync data, wait, check Device = Server, then tap Sales submit. Then do Attendance > Check Out.

## Differences between the image and the manual text
- The manual lists the rows as "outlet, sales, stock, QC, promotion, distribution and OOS performance, price, joint call, survey". The image shows "মূল্য সম্মতি" (price compliance), a slightly longer label than plain "price".
- The manual does not describe the grey versus blue state of the submit button, but states it is enabled only after sync; the image shows exactly that.
- The two screens show different figures (118 vs 25 sales), so the pair is not a single before/after run.
- The price row (মূল্য সম্মতি) appears on the left screen but not on the right screen, so the row list is configurable or version dependent.
- Otherwise none.

## Open questions for the owner
- Is the price row (মূল্য সম্মতি) shown only when the price survey feature is enabled for the AMO? The two screens differ.
- (Optional: does the Sales submit button re-grey if new data is created after a sync? The images do not show it.)
