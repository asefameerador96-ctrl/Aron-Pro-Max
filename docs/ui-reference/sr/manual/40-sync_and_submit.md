# 40. Sync and submit

## Screens in the image
Two phone screens of বিক্রয় জমা (Sales Submit). Left: the screen before sync, with the Android status bar and navigation bar (outlet count 39). Right: a different state (outlet count 1) dimmed, with a warning dialog about a retailer who still owes.

## Layout, top to bottom
Left screen:
1. Status bar over the gradient: "3:12", screenshot icon, location pin, 4G, two signal bars, battery "67".
2. Top bar red-to-purple gradient: white back chevron, a gradient shield icon with a check mark, white bold title "বিক্রয় জমা".
3. Light grey page. At the right, a line: "ডিভাইস স্ট্যাটাস" then a green dot and green "অনলাইন".
4. A table: deep purple (about #4A1A9A) header with three white titles; five white rows with thin dividers; an empty white area below the rows.
5. A light-red (about #E8C8CC) rounded box with a red left edge and a Bangla instruction in dark text.
6. Button 1: full-width red-to-purple gradient, white sync icon (two arrows) and "ডাটা সিঙ্ক করুন".
7. Button 2: full-width grey (about #BDBDBD, disabled) with a white box-and-down-arrow icon and "বিক্রয় জমা".
8. Android navigation bar.

Right screen (dimmed overlay, gesture bar): same top bar "বিক্রয় জমা", "ডিভাইস স্ট্যাটাস ● অনলাইন", the table (different figures), the red-tinted instruction box (partly hidden), a red-to-purple button (hidden) and, below it, a blue (about #1E4FA0) button (hidden by the dialog). Centred white rounded dialog: yellow warning triangle with exclamation mark; blue bold centred text on three lines; green "হ্যাঁ" and red "না" buttons.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:12 ; 67 | Clock ; battery | Status bar (left) |
| বিক্রয় জমা | Sales submit | Title; also the second button |
| ডিভাইস স্ট্যাটাস | Device status | Status line |
| অনলাইন | Online | Green status text |
| বিষয় | Item / subject | Table header, left |
| ডিভাইস | Device | Table header |
| সার্ভার | Server | Table header |
| আউটলেট | Outlet | Row 1 |
| বিক্রয় | Sales | Row 2 |
| স্টক | Stock | Row 3 |
| কিউসি | QC | Row 4 |
| প্রমোশন | Promotion | Row 5 |
| আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন। | Before finishing today's work, you must sync all operation data and submit the sales from this option. | Red-tinted box |
| ডাটা সিঙ্ক করুন | Sync data | Button 1 |
| বিক্রয় জমা | Sales submit | Button 2 (grey) |
| আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? | You still have 1 retailer with a due. Do you want to submit your sales? | Dialog |
| হ্যাঁ | Yes | Green dialog button |
| না | No | Red dialog button |

Table figures (Device, Server):

| Row | Left screen | Right screen |
|---|---|---|
| আউটলেট | ৩৯ ; ৩৯ | ১ ; ১ |
| বিক্রয় | ১,৫২৭ ; ১,৫২৭ | ৩৮ ; ৩৮ |
| স্টক | ১২,১৪৮ ; ১২,১৪৮ | ১৪,০০০ ; ১৪,০০০ |
| কিউসি | ০ ; ০ | ০ ; ০ |
| প্রমোশন | ১,২৩৪.৫ ; ১,২৩৪.৫ | ০ ; ০ |

## Data and figures shown
- Device equals Server in every row on both screens, so the sync is complete in both captures. The two screens come from different sessions or users (39 outlets vs 1 outlet).
- Left screen: 39 outlets, 1,527 sales, stock 12,148, QC 0, promotion 1,234.5. The promotion figure matches the discount and others total of 1,234.50 in screenshot 39 (same device session, same clock 3:12).
- The unit of "বিক্রয়" (1,527) and "স্টক" (12,148) is not stated; the sales figure appears to be a quantity, not a memo count (inference).
- The status "অনলাইন" is green; no offline state is shown.
- The Submit button is grey (disabled) on the left even though Device equals Server; the right screen shows a blue third button (enabled state, hidden behind the dialog).

## Colours and style
- Top bar red-to-purple gradient; page light grey; table header deep purple; rows white. Warning box light red with a red left border. Sync button red-to-purple gradient; submit button grey when disabled, blue (about #1E4FA0) when enabled (seen partly behind the dialog). Warning dialog: white, yellow triangle (about #FDD835), blue bold text (about #1565C0), green হ্যাঁ, red না.
- Green dot and green text for online. No packshots.

## Navigation and behaviour
- Manual: the SR sees the device status (online) and a table বিষয় / ডিভাইস / সার্ভার for outlet, sales, stock, QC, promotion. After the last outlet the SR taps ডাটা সিঙ্ক করুন, checks Device = Server, then taps বিক্রয় জমা (enabled after sync). If warned that a retailer still has a due, না returns to collect it first, হ্যাঁ submits anyway.
- Image: the grey disabled বিক্রয় জমা button shows the "before sync or locked" state; the blue button behind the dialog on the right shows the enabled style (inferred).

## Differences between the image and the manual text
- The manual says the submit button is enabled after sync; on the left screen Device already equals Server yet the button is grey. The image alone cannot show whether sync was already done or whether another condition keeps it disabled.
- The manual says "Warning if any retailer still has due"; the image shows the exact text: "১টি রিটেইলারের কাছে বাকি রয়েছে" (1 retailer).
- The row names match the manual (outlet, sales, stock, QC, promotion).
- The right screen shows a blue enabled button colour that the manual does not describe.

## Open questions for the owner
- What exactly is counted in the "বিক্রয়" and "স্টক" rows (units, memos, taka)?
- What other condition keeps বিক্রয় জমা disabled after sync (for example, outlets not visited, unprinted memos, geo checks)?
- Does "প্রমোশন" stay in the table once promotions are deferred, and what does its 1,234.5 represent (taka of discount)?
