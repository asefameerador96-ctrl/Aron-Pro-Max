# 39. Live data by route

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app "লাইভ ডেটা" (Live Data) screen, opened from the Dashboard tile "Live Dashboard". Neither is a system dialog.
- LEFT: Live Data for AMO "AMO-GulshanAMO", route "Gulshan-1Daily". Shows the top part (Sales panel, Live Strike Rate, and two Geo Fencing Status cards). The status bar is visible (Banglalink, 5:14, 4G, 41% battery) and the screen is cut off at the bottom.
- RIGHT: Live Data for AMO "Savar BazarAMO", route "Savar Bazar(Sat, Mon, Wed)". Shows the Sales panel, Live Strike Rate, Geo Fencing Status, and the Login and Sales Submit status card. No refresh icon at top right in this capture.

## Layout, top to bottom
### Left screen
1. Status bar: one line only (carrier "Banglalink", time 5:14, icons, 41% battery).
2. Top bar: deep purple, white back arrow (hollow left triangle) at left, centred white bold title "লাইভ ডেটা", a white circular refresh arrow icon at the far right.
3. Selector row on the pale page: two white drop-down boxes with purple outline and a small filter button.
   - Box 1: "AMO-GulshanAMO" with down-arrow (select AMO).
   - Box 2: "Gulshan-1Daily" with down-arrow (select route).
   - Filter icon (purple, three horizontal lines of decreasing width) to the right of the boxes, no box around it.
4. Sales panel: large rounded purple card (purple gradient, deeper at left, lighter violet at right), white bold title "বিক্রয়" centred at the top. Below it four columns, each a large cyan/turquoise number over a white label: 44310 সিগারেট, 0 বিড়ি, 80 লাইটার, 103 ম্যাচ.
5. Card "লাইভ স্ট্রাইক রেট": white-grey rounded card with shadow, bold black centred title. Left side: "641" large with label "টার্গেট আউটলেট"; below it "259" large with label "Successful Calls" (English). Right side: a circular ring gauge (about 3/4 of the ring filled in green, grey track), inside it "40.41%" in bold and below it a small "স্ট্রাইক রেট".
6. Card "জিও ফেন্সিং স্ট্যাটাস" (first): white-grey card, bold centred title. Left: "288" with label "জিও ভ্যালিডেশন". Right: a ring gauge with a green-to-yellow gradient and "99.31%" inside (the card is cut off at the bottom so only the upper half of the ring is visible; the next card overlaps it).
7. Card "জিও ফেন্সিং স্ট্যাটাস" (second, partly visible at the bottom edge of the screen): the same content, "288" "জিও ভ্যালিডেশন" and "99.31%" in the ring, clipped by the screen bottom. It appears to be the same card repeated or a scroll overlap; the content is identical.

### Right screen
1. Top bar: deep purple, back arrow, centred white bold title "লাইভ ডেটা". No refresh icon visible.
2. Selector row: box 1 "Savar BazarAMO" with down-arrow; box 2 "Savar Bazar(Sat, Mon, Wed)" in smaller text with down-arrow; filter icon at right.
3. Sales panel (purple rounded card, gradient): title "বিক্রয়". Two columns: "38" (cyan) over "মোট বিক্রয়"; "161.50" (cyan) over "মোট টাকা". (Different from the left screen, which shows four product columns.)
4. Card "লাইভ স্ট্রাইক রেট": left "81" over "টার্গেট আউটলেট", then "1" over "Successful Calls". Right: ring gauge, almost empty grey track with a small green dot at the top-right, centre "1.23%" bold with small "স্ট্রাইক রেট" below.
5. Card "জিও ফেন্সিং স্ট্যাটাস": left stack: "81" over "টার্গেট আউটলেট"; "0" over "জিও ভ্যালিডেশন"; "1" over "ছবি ভ্যালিডেশন". Right: ring gauge (empty grey), centre "0.00%"; below the ring the label "মোট আউটলেট ভ্রমণ" and the number "1".
6. Card "লগইন & বিক্রয় জমা স্টেটাস": bold centred title. Two progress blocks:
   - Block 1: green outlined icon (a box with a down arrow) at left, label "লগইন স্টেটাস", "100%" at right, a full-width progress bar filled blue (light blue to blue gradient, 100%). Below it, two figures: left "1" over "টার্গেট রুট"; right "1" over "মোট লগইন".
   - Block 2: green outlined icon (a box with an up arrow), label "বিক্রয় জমা স্টেটাস", "0%" at right, a full-width progress bar empty (grey track). Below: left "1" over "লগইন করেছে"; right "0" over "মোট বিক্রয় জমা".
   The card is cut off close to the bottom of the screen.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| লাইভ ডেটা | Live Data | Top bar title (both screens) |
| AMO-GulshanAMO | AMO name selected (Gulshan) | Left, drop-down 1 |
| Gulshan-1Daily | Route selected (Gulshan-1, daily) | Left, drop-down 2 |
| Savar BazarAMO | AMO name selected (Savar Bazar) | Right, drop-down 1 |
| Savar Bazar(Sat, Mon, Wed) | Route with its visit days (Sat, Mon, Wed) | Right, drop-down 2 |
| বিক্রয় | Sales | Sales panel title (both) |
| 44310 | Cigarette sales figure today (Latin digits, no separator) | Left, Sales panel, column 1 |
| সিগারেট | Cigarette | Left, Sales panel label |
| 0 | Bidi sales figure | Left, Sales panel, column 2 |
| বিড়ি | Bidi | Left, Sales panel label |
| 80 | Lighter sales figure | Left, Sales panel, column 3 |
| লাইটার | Lighter | Left, Sales panel label |
| 103 | Match sales figure | Left, Sales panel, column 4 |
| ম্যাচ | Match | Left, Sales panel label |
| 38 | Total sales (count) | Right, Sales panel |
| মোট বিক্রয় | Total sales | Right, Sales panel label |
| 161.50 | Total money (taka), two decimals | Right, Sales panel |
| মোট টাকা | Total taka (amount) | Right, Sales panel label |
| লাইভ স্ট্রাইক রেট | Live Strike Rate | Card title (both) |
| 641 | Target outlets | Left, strike-rate card |
| টার্গেট আউটলেট | Target outlet | Left and right, label |
| 259 | Successful calls | Left, strike-rate card |
| Successful Calls | Successful calls (English label) | Left and right, label |
| 40.41% | Strike rate (259 / 641) | Left, ring centre |
| স্ট্রাইক রেট | Strike rate | Left and right, small caption in ring |
| 81 | Target outlets | Right, strike-rate card and geo card |
| 1 | Successful calls | Right, strike-rate card |
| 1.23% | Strike rate (1 / 81) | Right, ring centre |
| জিও ফেন্সিং স্ট্যাটাস | Geo Fencing Status | Card title (both; twice on left) |
| 288 | Geo-validated calls | Left, geo cards |
| জিও ভ্যালিডেশন | Geo validation | Left and right, label |
| 99.31% | Geo-validation percent | Left, ring centre (both geo cards) |
| 0 | Geo-validated calls | Right, geo card |
| 1 | Photo-validated calls | Right, geo card |
| ছবি ভ্যালিডেশন | Photo validation | Right, geo card label |
| 0.00% | Geo percent | Right, geo ring centre |
| মোট আউটলেট ভ্রমণ | Total outlets visited | Right, geo card, under ring |
| 1 | Total outlets visited (number) | Right, geo card, under the label |
| লগইন & বিক্রয় জমা স্টেটাস | Login and Sales Submit (Bikroy Joma) status | Right, card title |
| লগইন স্টেটাস | Login status | Right, block 1 label |
| 100% | Login percent | Right, block 1 |
| 1 | Target routes | Right, block 1 |
| টার্গেট রুট | Target route | Right, block 1 label |
| 1 | Total logins | Right, block 1 |
| মোট লগইন | Total login | Right, block 1 label |
| বিক্রয় জমা স্টেটাস | Sales Submit (Bikroy Joma) status | Right, block 2 label |
| 0% | Sales submit percent | Right, block 2 |
| 1 | Logged in (routes that have logged in) | Right, block 2 |
| লগইন করেছে | Has logged in | Right, block 2 label |
| 0 | Total sales submitted | Right, block 2 |
| মোট বিক্রয় জমা | Total sales submit | Right, block 2 label |
| Banglalink 5:14 ... 41% | Phone status bar | Left, status bar (system, ignore) |

Digits: every number in the screen is in Latin digits (no Bangla digits ০১২৩...). No thousand separators (44310). Percentages show a "%" sign directly after the number, two decimals in rings (40.41%, 1.23%, 99.31%, 0.00%), none in the login bars (100%, 0%). Spelling is as read from the image: "স্টেটাস" (with ে) in the login card, "স্ট্যাটাস" in the geo card.

## Data and figures shown
- Left (AMO-GulshanAMO, route Gulshan-1Daily): sales today cigarette 44310, bidi 0, lighter 80, match 103 (units not printed; per the manual these are today's sales by category). Strike rate = successful calls / target outlets = 259 / 641 = 40.41%. Geo validation 288 = 99.31% (per the manual "288 geo-validated = 99.31%"; the denominator, total calls, is not displayed on the left screen; 288 / 290 = 99.31%).
- Right (Savar BazarAMO, route Savar Bazar(Sat, Mon, Wed)): 38 memos/sales, 161.50 total taka (shown with two decimals; likely thousand-taka, memos or unspecified, units not printed). Strike rate 1 / 81 = 1.23%. Geo: 81 target outlets, 0 geo-validated, 1 photo-validated, 0.00%, total outlets visited 1. Login 1 of 1 target route logged in = 100%. Sales submit: 1 logged in, 0 submitted = 0%.
- The ring percentage in the strike card is successful calls / target outlets; in the geo card it is geo-validated calls / total calls (the denominator is "total outlets visited" on the right screen: 0 / 1 = 0.00%).

## Colours and style
- Top bar: deep purple, white title and icons.
- Page background: very pale lavender-pink.
- Drop-downs: white, 2 px purple outline, rounded; filter icon purple.
- Sales panel: purple gradient card (darker purple left, lighter violet right), very rounded corners (about 28 dp); title white bold; key numbers cyan/turquoise, labels white.
- Section cards: light grey-white, rounded corners (about 14 dp), soft shadow; titles bold black centred.
- Strike-rate ring: grey track with green progress (darker green at the start, lighter green at the end), round caps; on the right screen only a small green dot at 1.23%.
- Geo ring: gradient from green to yellow on the left screen (99.31%); grey on the right (0.00%).
- Login progress bar: blue (light blue to blue gradient) full for 100%; grey empty track for 0%.
- Block icons: green outlined squares with arrows (down for login, up for sales submit).
- No packshot images.

## Navigation and behaviour
- Per the manual: choose the AMO (drop-down 1) and one or more routes (drop-down 2) then tap the filter button to load the data. The data is live, updated up to the current time.
- The refresh icon at the top right (left screen) reloads the data.
- The Sales panel shows today's sales for Cigarette, Bidi, Lighter, Match (left) or total sales count and amount (right).
- Live Strike Rate card: Target Outlet, Successful Calls and the strike rate %.
- Geo Fencing Status card: geo validation, photo validation and Total Call.
- Login & Sales Submit status card: login status and Bikroy Joma status of the selected routes (right screenshot).
- The screen scrolls vertically (the left screen is cut off at the bottom of the screenshot).
- Back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The two screens have different Sales panels: the left shows four product columns (সিগারেট, বিড়ি, লাইটার, ম্যাচ), the right shows two summary figures (মোট বিক্রয় 38 and মোট টাকা 161.50). The manual mentions only the four-product version in the text and the Login/Bikroy Joma card for the right screenshot. It does not explain the right panel's two figures.
- The left screen shows the "জিও ফেন্সিং স্ট্যাটাস" card twice with identical content (probably a scroll/overlap artifact of the screenshot); the manual mentions it once.
- The manual says Geo Fencing shows "Geo validation, photo validation and Total Call"; in the image only the right screen shows photo validation (ছবি ভ্যালিডেশন) and "মোট আউটলেট ভ্রমণ" (Total outlets visited); the label is not "Total Call". The left screen's geo card shows only the geo-validation count.
- The manual says "Select AMO" and "select a route"; the right screen's second drop-down shows route with visit days "Savar Bazar(Sat, Mon, Wed)".
- The manual does not mention the refresh icon appears only on the left screen, nor that the page's Successful Calls label is in English.
- The manual example "259 / 641 = 40.41%" and "288 geo-validated = 99.31%" match the image.
- The manual does not mention the round "মোট আউটলেট ভ্রমণ" caption under the geo ring.

## Open questions for the owner
- Does the Sales panel show the four product columns when one route is chosen and the two summary figures (count and taka) when several routes or a different level is chosen, or is it a version difference?
- What is the unit for 44310 / 80 / 103 (sticks, packets, boxes, dozens) and for 161.50 (taka, or thousand taka)? The screenshots print no unit.
- What is the denominator of the geo percentage 99.31% on the left screen (total calls visited)? It is not printed.
- Is the refresh icon missing on the right screen by design (different build) or only in that capture?
