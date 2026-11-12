# 39. Sales summary (বিক্রয় সারসংক্ষেপ)

## Screens in the image
Single phone screen with Android status bar and three-button navigation bar: the বিক্রয় সারসংক্ষেপ (Sales Summary) screen showing per-SKU totals for the day.

## Layout, top to bottom
1. Status bar over the gradient: "3:12", screenshot icon, location pin, 4G, two signal bars, battery "67" in a white pill.
2. Top bar red-to-purple gradient: white back chevron, a pink/red server-stack icon, white bold title "বিক্রয় সারসংক্ষেপ".
3. Light grey page. A white card spanning the width. A dark charcoal (about #3A3A3A) table header with six white column titles; a round white circle at the right end of the header holds a green printer icon and hides part of the last title.
4. White and light-grey alternating SKU blocks: each block has the SKU name (purple) on the first line and six figures on the second line.
5. The last block "Max" has a first figures line and then three category lines (cigarette, lighter, match) with their own labels in purple.
6. Two bold purple lines: "ডিসকাউন্ট এবং অন্যান্য (-)" with its value and "সর্বমোট" with its value, on a light grey card bottom.
7. Large empty grey area.
8. A full-width button, deep purple at left to crimson at right (reversed direction compared to the other gradients), with a white printer icon and "প্রিন্ট".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:12 ; 67 | Clock ; battery | Status bar |
| বিক্রয় সারসংক্ষেপ | Sales summary | Title |
| মেমো | Memo (number of memos) | Column 1 |
| পরিমাণ | Quantity | Column 2 |
| মূল্য | Value | Column 3 |
| ডিসকাউন্ট | Discount | Column 4 |
| ডিসকাউন্ট মূল্য | Discounted (net) value | Column 5 |
| ...ফরত (first letters hidden by the printer circle; probably "ফেরত") | Return(s) or similar; uncertain | Column 6 |
| Avon-20S | SKU | Block 1 |
| ARIS-A-20s | SKU | Block 2 |
| BDDF-10s | SKU | Block 3 |
| BDDF-20s | SKU | Block 4 |
| Aster | SKU | Block 5 |
| Max | SKU | Block 6 |
| সিগারেট | Cigarette | Category line |
| লাইটার | Lighter | Category line |
| ম্যাচ | Match | Category line |
| ডিসকাউন্ট এবং অন্যান্য (-) | Discount and others (-) | Summary line |
| সর্বমোট | Grand total | Summary line |
| প্রিন্ট | Print | Bottom button |

Figures (memo, quantity, value, discount, discounted value, last column):

| SKU / line | Figures as shown |
|---|---|
| Avon-20S | ১৪ ; ২৮০ ; ১,৯৬০.০০৳ ; ০.০০ ; ১,৯৬০.০০৳ ; ৭২০ |
| ARIS-A-20s | ১৪ ; ২৮০ ; ১,৮২০.০০৳ ; ০.০০ ; ১,৮২০.০০৳ ; ৭২০ |
| BDDF-10s | ৩৯ ; ৪২০ ; ২,৭৩০.০০৳ ; ৩৬৮.৬৮ ; ২,৩৬১.৩২৳ ; ৪,৫৮০ |
| BDDF-20s | ১৭ ; ৫২০ ; ৩,৩৮০.০০৳ ; ৮৫৩.৩২ ; ২,৫২৬.৬৮৳ ; ৪,৮৮০ |
| Aster | ২ ; ২৬ ; ৩২৫.০০৳ ; ১২.৫০ ; ৩১২.৫০৳ ; ২৪ |
| Max | ০ ; ০ ; ০.০০৳ ; ০.০০ ; ০.০০৳ ; ৫০ |
| সিগারেট | (no memo count) ১,৫০০ ; ৯,৮৯০.০০৳ ; ১,২২২.০ ; ৮,৬৬৮.০০৳ ; ১০,৫০০ |
| লাইটার | ২৬ ; ৩২৫.০০৳ ; ১২.৫ ; ৩১২.৫০৳ ; ৭৪ |
| ম্যাচ | ১ ; ২৮.০০৳ ; ০.০ ; ২৮.০০৳ ; ৪৭ |
| ডিসকাউন্ট এবং অন্যান্য (-) | -১,২৩৪.৫০ |
| সর্বমোট | ৯,০০৮.৫০ |

## Data and figures shown
- Per-SKU lines for Avon-20S, ARIS-A-20s, BDDF-10s, BDDF-20s, Aster and Max, then category totals (cigarette, lighter, match), then discounts and the grand total.
- The numbers are internally consistent: cigarette total 9,890 = 1,960 + 1,820 + 2,730 + 3,380 (value); 8,668 net; discount 1,222.0 (= 368.68 + 853.32); with lighter 325 and match 28 the value is 10,243; minus discount 1,234.50 (cigarette 1,222 + lighter 12.5) gives 9,008.50, the grand total.
- The sixth column most probably is the SKU stock left on hand (the manual lists "stock"); its heading is hidden, so this is not confirmed. For "Max" it shows 50 with zero sales.
- Quantities per SKU: 280 for Avon-20S with 14 memos is 20 per memo (a 20s pack per memo) so the quantity here is in sticks (inference from 1,960.00 / 280 = 7.00 per stick); the field would count packets under the owner's rule 5.
- Money values carry "৳" directly after the number with no space.

## Colours and style
- Header: charcoal with white text. Rows alternate white and light grey (about #EFEFEF). SKU names in purple (about #4A1A9A) in larger type, figures in smaller dark text. Category labels in purple. Summary lines bold purple.
- Print button: purple-to-crimson gradient (left deep purple, right crimson), the reverse of the red-to-purple buttons elsewhere. Printer circle: green icon on white.
- No packshots.

## Navigation and behaviour
- Manual: per SKU: memos, quantity, value, discount, net value and stock; totals by category; discount and others (-) and grand total; প্রিন্ট. The SR opens it at the end of the day and prints if needed.
- The green printer circle at the top right of the card prints too (inferred).

## Differences between the image and the manual text
- The manual says the last column is stock; the visible heading looks like "...ফরত" (possibly "ফেরত", return). Not resolvable from the image.
- The manual does not mention the 'Max' row with zero sales or the 50 in the last column.
- The Discount columns are populated (368.68, 853.32, 12.50) although discounts are deferred.

## Open questions for the owner
- What is the exact title of column 6, and is it stock or returns?
- Are quantities in this summary in sticks (as the arithmetic suggests) or in packets?
- How should the "Max" row (0 sold, 50 in column 6) be understood?
- What is "অন্যান্য" (others) in "ডিসকাউন্ট এবং অন্যান্য (-)", beyond discount?
