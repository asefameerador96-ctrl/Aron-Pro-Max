# 61. Brand wise KPI

## Screens in the image
Two phone screens side by side, both "Brand wise KPI". Left: "KPI on - Cigarette Sales" with a brand list. Right: "KPI on - Memo" with a brand list. Deferred programme screen (KPI); every figure label recorded, no rules inferred.

## Layout, top to bottom
1. Status bar over the gradient: 3:21, screenshot icon, location pin, 4G, signals, battery 66 (both screens).
2. Top bar, red-to-purple gradient, white back chevron, centred white title "Brand wise KPI".
3. A white rounded summary card: a centred title, a vertical divider, and two columns each with a small Bangla label and a big red percentage.
4. Section header "FMC BRANDS" on a white strip (grey caps).
5. A white list: each row has the brand name (large, black) and a grey line "Target N - KPI Today ৳N" on the left; on the right two small column headings (above red percentages) and a grey chevron ">".
6. Light grey empty space, then the Android navigation bar.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Brand wise KPI | Screen title | Top bar |
| KPI on - Cigarette Sales | Card title (left screen) | Summary card |
| মোট ভলিউম | Total volume | Left card, column 1 |
| ২৯.৬৩% | 29.63 % | Left card, column 1 |
| মোট মেমো | Total memo | Left card, column 2 |
| ৩৬.০৩% | 36.03 % | Left card, column 2 |
| KPI on - Memo | Card title (right screen) | Summary card |
| মোট মেমো | Total memo | Right card, column 1 |
| ৩৬.০৩% | 36.03 % | Right card, column 1 |
| মোট ভলিউম | Total volume | Right card, column 2 |
| ২৯.৬৩% | 29.63 % | Right card, column 2 |
| FMC BRANDS | Section heading | Both screens |
| Volume, মেমো | Column headings per row (left screen order: Volume then মেমো) | Left rows |
| মেমো, Volume | Column headings per row (right screen order: মেমো then Volume) | Right rows |

Left list rows (brand; subtitle; Volume; মেমো):

| Brand | Subtitle | Volume | মেমো |
|---|---|---|---|
| ARIS | Target ১০,৮০০ - KPI Today ৳০ | ২১.৯২% | ৭৫.৬৩% |
| Avon | Target ১৬,০০০ - KPI Today ৳০ | ১২.৩৮% | ৪৬.৫৮% |
| ESSE | Target ০ - KPI Today ৳০ | ০.০০% | — (dash) |
| Maxim | Target ০ - KPI Today ৳০ | ০.০০% | ০.০০% |
| Black Diamond | Target ৪৭,০০০ - KPI Today ৳৫৩৬ | ৩৭.১১% | ৩০.৫০% |
| Supreme | Target ৫০০ - KPI Today ৳০ | ০.০০% | — (dash) |

Right list rows (brand; subtitle; মেমো; Volume):

| Brand | Subtitle | মেমো | Volume |
|---|---|---|---|
| ARIS | Target ১১৯ - KPI Today ৳৪২০ | ৭৫.৬৩% | ২১.৯২% |
| Avon | Target ১৪৬ - KPI Today ৳৪২০ | ৪৬.৫৮% | ১২.৩৮% |
| Maxim | Target ৫ - KPI Today ৳০ | ০.০০% | ০.০০% |
| Marise | Target ৫৫ - KPI Today ৳০ | ৩.৬৪% | — (dash) |
| Black Diamond | Target ৭৭৭ - KPI Today ৳০ | ৩০.৫০% | ৩৭.১১% |

Digit reading note: "৪৭,০০০", "৭৭৭" and "৩.৬৪%" are small and I read them as shown; a doubt of one digit is possible. Status bar 3:21, battery 66.

## Data and figures shown
- Left card: total volume 29.63 %, total memo 36.03 %. Right card: total memo 36.03 %, total volume 29.63 % (same figures, order swapped).
- The left list has 6 brands (matches "See all brands (৬)" on screenshot 60); the right list has 5 brands (matches "(৫)").
- ARIS and Avon appear on both with the same percentages; Black Diamond has the same percentages (37.11 %, 30.50 %) on both. Targets differ (volume-type numbers on the left, memo counts on the right).
- ESSE and Supreme have a dash "—" where a memo % is absent on the left; Marise has a dash for Volume on the right.
- "KPI Today" is a taka value (৳0, ৳536, ৳420).
- No BLM screen is shown here; the manual says "and KPI on BLM sales" exists but it is not in the image.

## Colours and style
- Top bar red (#D94A45) to purple (#4F1D9A) gradient; white title.
- Page light grey (#F0F0F0); summary card white, rounded about 16 dp, thin shadow; titles black; big percentages crimson red (#C8423F).
- List: white rows, thin separators; brand names black, 18 sp-ish; subtitle grey; column headings grey; percentages red; chevron grey.
- Fonts: Latin Roboto-like; Bangla digits in the app's Bangla font.

## Navigation and behaviour
- Manual (screenshot 61): KPI on Cigarette Sales (volume % and memo %), KPI on Memo (and KPI on BLM sales); per brand: target, KPI today (৳), volume % and memo %. The SR taps a brand for detail.
- Each row has a chevron, so it is tappable; the detail screen is not shown.
- Left and right are two modes of the same screen (cigarette sales and memo), reached from the "See all brands" buttons of screenshot 60.

## Differences between the image and the manual text
- The manual describes "KPI on Cigarette Sales, KPI on Memo, (and KPI on BLM sales)"; the image shows only two of them.
- The manual says per brand: target, KPI today, volume % and memo %; the image shows the column order differing between the two modes (Volume, মেমো on the left; মেমো, Volume on the right) and dashes where a figure is absent.
- The "FMC BRANDS" heading is not mentioned in the manual.
- The "KPI Today" figures for ARIS and Avon are ৳420 on the right and ৳0 on the left; the manual does not explain the difference.

## Open questions for the owner
- KPI is deferred (docs/27): confirm hooks-only.
- Why do the two lists have different brands and different targets for the same brand?
- What does the dash mean (no target, or not applicable)?
