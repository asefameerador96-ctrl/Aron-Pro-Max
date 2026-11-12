# 10. Target and achievement by SKU

## Screens in the image
Single phone screen, cropped top (no status bar) and bottom: the SR app "টার্গেট ও অ্যাচিভমেন্ট" list, opened from the Dashboard link "বিস্তারিত". Four SKU cards are fully visible and a fifth is cut off at the bottom.

## Layout, top to bottom
1. Header, red-to-purple horizontal gradient: a white back chevron at the left, centred white bold title "টার্গেট ও অ্যাচিভমেন্ট".
2. Light grey page. A single tab "এস টি ডি" in a small purple-bordered tab shape with a red-to-purple gradient fill at its bottom edge (selected tab), over a thin grey line.
3. SKU cards, one per product, stacked: white, rounded, with a red outline (about 2 dp). Left: a pack picture (packshot) with the SKU name below it in bold purple. Right: a two-row grid of four columns separated by thin grey vertical lines:
   - Row 1 (labels in purple, figures in dark): টার্গেট, অ্যাচিভ, বাকি, অর্জন. The percentage figure is in red.
   - A thin grey horizontal divider.
   - Row 2: ADS, TADS, PADS, RADS (purple labels, dark figures).
4. Fifth card (Black Diamond, packshot only visible) is cut off.
5. Home-indicator bar at the very bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| টার্গেট ও অ্যাচিভমেন্ট | Target and Achievement | Header title |
| এস টি ডি | STD (tab; phonetic Bangla of "STD") | Tab |
| টার্গেট | Target | Each card, row 1 col 1 |
| অ্যাচিভ | Achieved | Each card, row 1 col 2 |
| বাকি | Remaining | Each card, row 1 col 3 |
| অর্জন | Achievement (%) | Each card, row 1 col 4 |
| ADS | Average daily sales | Each card, row 2 col 1 |
| TADS | Target ADS | Each card, row 2 col 2 |
| PADS | Present ADS | Each card, row 2 col 3 |
| RADS | Required ADS | Each card, row 2 col 4 |
| Maxim | SKU name | Card 1 |
| Avon | SKU name | Card 2 |
| ARIS | SKU name | Card 3 |
| Marise | SKU name | Card 4 |
| ৫০০ ০ ৫০০ ০% ; ০ ৩৬ ০ ৪৫ | Maxim: target 500, achieved 0, remaining 500, 0%; ADS 0, TADS 36, PADS 0, RADS 45 | Card 1 |
| ৯০০ ০ ৯০০ ০% ; ০ ৬৪ ০ ৮২ | Avon: target 900, achieved 0, remaining 900, 0%; ADS 0, TADS 64, PADS 0, RADS 82 | Card 2 |
| ৯০০ ০ ৯০০ ০% ; ০ ৬৪ ০ ৮২ | ARIS: target 900, achieved 0, remaining 900, 0%; ADS 0, TADS 64, PADS 0, RADS 82 | Card 3 |
| ৫০০ ০ ৫০০ ০% ; ০ ৩৬ ০ ৪৫ | Marise: target 500, achieved 0, remaining 500, 0%; ADS 0, TADS 36, PADS 0, RADS 45 | Card 4 |

Pack art text (on the packshots, not UI text): MAXIM, AVON, ARIS, MARISE, Black Diamond.

## Data and figures shown
- All four SKUs have zero achieved (a test or fresh-period account), so achievement is 0% and remaining equals target.
- TADS = target spread over the working days (500 gives 36, 900 gives 64: about 14 days). RADS = remaining divided by the days left (500 gives 45, 900 gives 82: about 11 days left). ADS and PADS are 0.
- Unit of the targets is not shown on screen (sticks, packets or boxes unknown).

## Colours and style
- Header: red-to-purple gradient, white title, white back chevron.
- Page: light grey (#E8E8E8-ish). Cards: white, red 2 dp outline, about 10 dp radius, small gap between cards.
- Labels in purple (about #4B2A99), figures in near-black, the achievement percentage in red.
- Pack pictures (packshots) at the left of every card, about 85 by 130 px, with the SKU name under the picture in bold purple.
- Selected tab: purple outline with a gradient underline.

## Navigation and behaviour
- Opened by "বিস্তারিত" on the Dashboard card; the back chevron returns to the Dashboard.
- The list scrolls vertically, one card per SKU.
- The "এস টি ডি" tab implies other tabs may exist (not shown, only one tab is visible).

## Differences between the image and the manual text
- The manual describes the columns and the four ADS terms: matches. It does not mention the tab "এস টি ডি", the red card outline or the pack pictures.
- The manual says "ADS (average daily sales), TADS (target ADS), PADS (present ADS), RADS (required ADS to reach target)": the image shows only the abbreviations, no explanation on screen.
- The achievement percentage is red here (even at 0%); whether it changes colour at higher values is not shown.

## Open questions for the owner
- What unit are the targets and achieved figures in (sticks, packets, boxes)?
- Is there more than one tab next to "এস টি ডি" (the manual does not say what STD stands for, nor whether other tabs exist)?
- Target and achievement are deferred in scope (docs/27): keep this screen as a hook only?
