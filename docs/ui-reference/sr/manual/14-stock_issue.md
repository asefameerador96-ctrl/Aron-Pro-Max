# 14. Stock issue

## Screens in the image
Two phone screens side by side showing the same SR "স্টক" (stock issue) screen, one minute apart (status bars 3:01 and 3:02). They look identical: the list is not scrolled in either. The second may be the same screen after a save or reopen.

## Layout, top to bottom
1. Status bar over the gradient: "3:01" (or "3:02"), a screen-capture icon, a "V" app icon, pin, 4G, signal, battery 69.
2. Header, red-to-purple horizontal gradient, tall: white back chevron at the left; centred the stock icon (clipboard with a box) and white bold "স্টক"; at the right a large white circle holding a green printer icon (partly overlapping the header edge).
3. Light grey page. Column header row, dark text: "এসকেইউ" (left), "ইস্যু" (centre), "স্টক" (right).
4. SKU rows, white rounded cards, each about 200 px tall. In each: a pack picture (packshot) at the left, with a small purple round badge at its bottom right showing a number; SKU code in purple to the right of the picture; then a red round "minus" button, a number field (centred figure over a thin underline), a purple round "plus" button; then the stock figure at the right.
   - Row 1: MaxB-10S, issue ০, stock ০, badge ০ (blue Maxim pack).
   - Row 2: MaxB-20S, issue ০, stock ০, badge ০ (blue Maxim pack).
   - Row 3: MaxWB-10 / S (the name wraps to two lines, "MaxWB-10" then "S"), issue ০, stock ০, badge ০ (green Maxim pack).
   - Row 4: Avon-20S, issue ১,০০০, stock ৭২০, badge ৫০ (cream Avon pack).
   - Row 5: a green ARIS-type pack, cut off by the bottom panel.
5. Bottom panel (fixed), solid deep purple (about #4B1E96): a small header row "ক্যাটাগরি", "মোট ইস্যু", "স্টক"; then three category rows (see table); then two buttons side by side: left a red-to-purple gradient button with a white save icon and "সংরক্ষণ"; right a light grey button with a dark printer icon and "প্রিন্ট".
6. Android three-button navigation bar.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:01 / 3:02, 4G, 69 | Phone clock, network, battery (system) | Status bars |
| স্টক | Stock | Header title |
| এসকেইউ | SKU | Column header, left |
| ইস্যু | Issue | Column header, centre |
| স্টক | Stock | Column header, right |
| MaxB-10S | SKU code: Maxim B, pack of 10s | Row 1 |
| MaxB-20S | SKU code: Maxim B, pack of 20s | Row 2 |
| MaxWB-10 S | SKU code: Maxim WB (wild berry), 10s (wraps: "MaxWB-10" / "S") | Row 3 |
| Avon-20S | SKU code: Avon, 20s | Row 4 |
| ০ ০ ০ ০ ৫০ | Row badges on the packshots: 0, 0, 0, 50 (Avon) | Rows 1 to 4 |
| ০ (issue) ০ (stock) | Issue 0, stock 0 | Rows 1 to 3 |
| ১,০০০ (issue) ৭২০ (stock) | Issue 1,000, stock 720 | Row 4 |
| ক্যাটাগরি | Category | Bottom panel header, left |
| মোট ইস্যু | Total issue | Bottom panel header, centre |
| স্টক | Stock | Bottom panel header, right |
| সিগারেট ১২,০০০ ১০,৫০০ | Cigarette: total issue 12,000, stock 10,500 | Panel row 1 |
| লাইটার ১০০ ৯৯ | Lighter: total issue 100, stock 99 | Panel row 2 |
| ম্যাচ ৪৮ ৪৭ | Match: total issue 48, stock 47 | Panel row 3 |
| সংরক্ষণ | Save | Left button |
| প্রিন্ট | Print | Right button |

Pack art text on the packshots (not UI text): MAXIM, X BOLT, Wild Berry, AVON.

## Data and figures shown
- Per SKU: issue (what the house gave today, editable with minus, plus or typing) and stock (what the SR now holds, for example 720 for Avon after issuing 1,000: so the stock is not the same as the issue, it is the carry-over plus issue minus sales or similar).
- The small badge on each packshot (০, ০, ০, ৫০ for Avon) looks like a pack count: 1,000 sticks is 50 packets of 20. This suggests the figure fields are in sticks and the badge converts to packets. Uncertain.
- Category totals in the panel are in the same unit as the rows for cigarettes (12,000 sticks issued, 10,500 in stock). Lighter totals 100 and 99, match totals 48 and 47 (units not shown).
- Both screens are identical, so the 3:02 screen shows no change.

## Colours and style
- Header: red-to-purple gradient, white title; the print button is a white circle with a green printer glyph.
- Page: light grey; row cards white with a very soft shadow, about 12 dp radius.
- Minus button: red/coral rounded square with a white minus; plus button: deep purple rounded square with a white plus.
- Number field: figure centred over a thin grey underline (no box).
- SKU code in purple; stock figure dark.
- Bottom panel: solid deep purple with white text; Save = red-to-purple gradient, Print = light grey with dark text (secondary).
- Pack pictures (packshots) are present in every row, as the owner's directive requires.

## Navigation and behaviour
- Minus and plus change the issue quantity by one step (the step size is not shown); the field can also be typed.
- সংরক্ষণ saves the day's issue; প্রিন্ট prints the stock memo (the printer icon at the top right also leads to printer connection, per screenshots 15 to 17).
- The list scrolls inside the white area while the purple panel stays fixed.
- The back chevron returns to the Dashboard.

## Differences between the image and the manual text
- The manual says "Columns এসকেইউ (SKU), ইস্যু (Issue, with - / +) and স্টক (Stock)": matches; the image also shows a per-pack badge on each packshot that the manual does not mention.
- The manual says "Bottom: total issue and stock by category (Cigarette, Lighter, Match), সংরক্ষণ (Save) and প্রিন্ট (Print). Printer icon at the top right": matches, including the green printer in a white circle.
- The manual says "enter the quantity issued for each SKU (or check the issue from the house)": the image does not show which rows come pre-filled from the house.
- The manual does not name SKU codes; the image shows the code names (MaxB-10S, MaxB-20S, MaxWB-10S, Avon-20S) with a wrapped name for MaxWB-10S.
- The image includes two screens 1 minute apart that look the same; the manual treats them as one screen.

## Open questions for the owner
- What does the small purple badge on each packshot show (packets, boxes, current stock)?
- Which unit are the issue and stock figures in (sticks vs packets), given the owner's rule that the apps enter and show packets for cigarettes?
- What is the unit for lighters (100 issued: pieces or boxes of 25) and matches (48: dozens)?
- What step does plus or minus add (1, 10, a full packet or a box)?
- Does the stock figure update after a save, and how is carry-over from the previous day computed?
