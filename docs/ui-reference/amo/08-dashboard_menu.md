# 08. Dashboard menu

## Screens in the image
Single phone screen: the vendor ARON AMO app home (Dashboard) after login. The image is cropped at the bottom, so the last tile row is cut off and the target cards the manual mentions (screenshot 9) are not visible here.

## Layout, top to bottom
1. Status bar (one line): time "5:05", location pin, "0.00 KB/S", 4G and two signal bars, battery 59.
2. Top header, deep purple (about #5B1E9E), solid, no gradient visible. Left: a white circle with a purple person silhouette (avatar). Right of it, two lines of white text: bold "AMO-Dariapur (amo5864)" and a smaller line "AMO-DariapurAMO, 2026-10-26".
3. Page background: light grey (about #F3F3F3).
4. One large white rounded card (rounded top corners, thin shadow) holds the tile grid. Three columns; each tile is a white rounded square (about 12 dp radius) with a thin light-grey border, the icon above and a one- or two-line label below, centred. All tiles are enabled and look identical in state.
5. Tile rows, left to right:
   - Row 1: অ্যাটেনডেন্স (a calendar with a clock, pink-to-purple/blue gradient); জয়েন্ট কল (four small person circles joined by arrows around a phone); কন্ট্রোল কল (three stacked horizontal slider/toggle bars, blue, red/magenta, indigo).
   - Row 2: টিম লোকেশন (a globe with a red location pin); টিম পারফর্মেন্স (a bar chart with a rising trend arrow, magenta-to-blue); টাস্ক ডেলিগেশন (a person at top with arrows to a group of people, red/purple/blue).
   - Row 3: লাইভ ড্যাশবোর্ড (a "LIVE" broadcast badge with a play symbol and signal arcs); এস আর স্টক (a person standing at stacked boxes with a dollar coin); আউটলেট (a shop front with awning, purple gradient) with a red round badge at the top-right corner of the tile showing a small zero (looks like "০", Bangla zero, white on red).
   - Row 4: স্টক (a clipboard checklist with a small box); বিক্রয় (a box with a clock, blue-purple-magenta); মেমো (a phone/receipt with a currency note).
   - Row 5: সারসংক্ষেপ (a server-stack icon with a connector); বিক্রয় জমা (a shield with a check mark); আস্থা (a round dark brown/gold logo with red and gold ring and the word আস্থা inside; this is a raster logo, not the gradient style).
   - Row 6: Report (a donut chart with three bars, orange/blue/yellow, in the English word "Report", Latin script); বিক্রয় যাত্রা (a person in a circle linked to a basket/tick, outlined style in purple-blue); Campaign Enrollment (a megaphone with a thumbs-up and a speech lines/play symbol, blue and purple; label in English on two lines: "Campaign" / "Enrollment").
   - Row 7 (cut off): one tile visible at left with a gradient gear icon (magenta to purple). Its label is cut off and unreadable here (the manual says it is সেটিং, Settings). The other two cells of the row are not visible.
6. No bottom bar, no floating button is visible in this crop.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 5:05 | Phone clock | Status bar |
| 0.00 KB/S | Network speed (system) | Status bar |
| 59 | Battery level (system) | Status bar |
| AMO-Dariapur (amo5864) | AMO name and user ID (example) | Header line 1, bold white |
| AMO-DariapurAMO, 2026-10-26 | Area name and today's date (Latin digits, ISO format yyyy-mm-dd) | Header line 2, smaller |
| অ্যাটেনডেন্স | Attendance | Tile row 1 col 1 |
| জয়েন্ট কল | Joint Call | Tile row 1 col 2 |
| কন্ট্রোল কল | Control Call | Tile row 1 col 3 |
| টিম লোকেশন | Team Location | Tile row 2 col 1 |
| টিম পারফর্মেন্স | Team Performance | Tile row 2 col 2 |
| টাস্ক ডেলিগেশন | Task Delegation | Tile row 2 col 3 |
| লাইভ ড্যাশবোর্ড | Live Dashboard | Tile row 3 col 1 |
| এস আর স্টক | SR Stock | Tile row 3 col 2 |
| আউটলেট | Outlet | Tile row 3 col 3 |
| ০ (looks like) | Number of pending outlet requests (zero) | Red badge on Outlet tile |
| স্টক | Stock | Tile row 4 col 1 |
| বিক্রয় | Sales | Tile row 4 col 2 |
| মেমো | Memo | Tile row 4 col 3 |
| সারসংক্ষেপ | Summary | Tile row 5 col 1 |
| বিক্রয় জমা | Sales Submit (Bikroy Joma) | Tile row 5 col 2 |
| আস্থা | Astha | Tile row 5 col 3 (text is also inside the logo image) |
| Report | Report | Tile row 6 col 1, English label |
| বিক্রয় যাত্রা | Sales Journey | Tile row 6 col 2 |
| Campaign Enrollment | Campaign Enrollment | Tile row 6 col 3, English label on two lines |
| (label cut off) | Settings per the manual (সেটিং) | Row 7 col 1, unreadable |

The "LIVE" word inside the Live Dashboard icon and the "$" on the SR Stock icon are part of the icon artwork. The badge digit is small and could be "০" or "0"; I read it as the Bangla zero, with some uncertainty.

## Data and figures shown
- "amo5864" = the AMO's login ID; "AMO-Dariapur" is the name and "AMO-DariapurAMO" the area/territory string.
- "2026-10-26" = today's date in Latin digits, yyyy-mm-dd.
- Red badge on Outlet = number of outlet requests from SRs waiting for verification (here zero).
- No target figures are visible (the target cards are below the crop).
- The status bar shows 5:05, 4G, battery 59, 0.00 KB/S.

## Colours and style
- Header: deep purple, solid; white text; white circular avatar with a purple user icon.
- Page: light grey; main card: white with large rounded corners; tiles: white with a light grey 1 dp border and about 12 dp radius.
- Icons: flat vector icons in a red/magenta-to-purple-to-blue gradient style (the owner's "gradient red-to-purple" look). Exceptions: the Astha tile uses a round dark red/brown and gold logo image; Report uses orange, blue and yellow; Campaign Enrollment is blue/purple; Sales Journey is an outlined purple-blue icon.
- Labels: dark grey, Bangla in the app's Bangla font; English labels (Report, Campaign Enrollment) use a sans-serif (Roboto-like) and look slightly larger/lighter.
- Badge: red circle (rounded square) with white digit, top-right of the Outlet tile.
- No packshots on this screen.

## Navigation and behaviour
- Each tile opens its feature, per the manual: Attendance (check in/out, section 7); Joint Call (assess an SR call, 9); Control Call (own outlet call, 8); Team Location (10); Team Performance (11); Task Delegation (12); Live Dashboard (13); SR Stock (14); Outlet (verify SR outlet requests, add/close/change outlets, update base, 18); Stock (take issued stock, connect printer, 6 and 15); Sales (8); Memo (reprint/edit, collect dues, 16); Summary (17); Sales Submit (sync and submit the day, 19); Astha (outlet targets, 20); Report (21); Sales Journey (22); Settings (language, send data file to support, log out, 23).
- The red badge on Outlet counts requests waiting for verification.
- The screen scrolls: the manual says to scroll down to see Settings and the target cards (screenshot 9).
- Campaign Enrollment: not covered by the manual.

## Differences between the image and the manual text
- **Extra tile:** "Campaign Enrollment" (English, row 6 col 3) is visible in the image but is not in the manual's list of 18 tiles. Its function is not described.
- The manual's tile table lists 18 tiles; the image shows 19 tiles at most (18 visible plus the cut-off Settings tile; with Campaign Enrollment the list is 19).
- Two tile labels are English in the image: "Report" (the manual also lists it in English) and "Campaign Enrollment".
- The manual says the header shows "your area and today's date"; the image shows "AMO-DariapurAMO, 2026-10-26", the area name has no space or separator after "AMO-Dariapur".
- The Settings tile label is cut off in the image (the manual says সেটিং).
- The target cards of screenshot 9 are not in this image.
- The badge: the manual says a red badge shows the number of waiting requests; the image shows the badge reading zero.

## Open questions for the owner
- What does the "Campaign Enrollment" tile do, and is it in scope (it is not in the manual and may relate to the deferred promotion/loyalty programmes)?
