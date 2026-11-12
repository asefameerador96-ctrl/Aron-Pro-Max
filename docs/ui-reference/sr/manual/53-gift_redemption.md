# 53. Gift redemption

## Screens in the image
Two phone screens side by side. Left: the "লয়্যালটি পয়েন্ট" (Loyalty Point) menu with one tile "রিডিম্পশন". Right: the "Gift Redemption" page with a retailer, letter filter, campaign banner with points remaining and gift cards with - / + steppers. Manual screenshot 53.

## Layout, top to bottom
Left screen:
1. Status bar 3:18, battery 66. Gradient top bar, back chevron, white title "লয়্যালটি পয়েন্ট".
2. One light tile (white-grey, rounded) at top-left: a coloured icon (yellow star coin with a curved arrow onto a green/teal and red gift box) with red label "রিডিম্পশন" below. Rest of page empty grey.
Right screen (different scale, no status bar, black gesture bar at the bottom):
1. Gradient top bar with back chevron and English title "Gift Redemption".
2. White card with a drop-down "Bipu Store (6909084-01779223366-Apsis Cluster)" and arrow.
3. Dark-grey rounded strip of letter chips: "All" (selected, purple gradient), "A", "B", "M", "R", "S", "a", "b", then second row "j", "o", "s", "t".
4. Red banner (rounded): left "Diamond League (April)", right a pale pill "২২০ Pts Remaining".
5. Gift cards in two columns (white, rounded), each with a grey gift-box outline icon, a Bangla title, an orange-outlined points chip with a coin icon, and a stepper row: grey "-" button, a centre number "0", a green "+" button:
   - Card 1: "প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক।", chip "১", "+" green.
   - Card 2: "২ স্টেপ কিচেন র‍্যাক।", chip "২০০", "+" green.
   - Card 3: "চেয়ার।", chip "২০০", "+" green.
   - Card 4: "টর্নেডো ফ্যান।", chip "৪০০", "+" grey (disabled look).
   - A further row of cards is cut off by the bottom bar.
6. Fixed bottom button, red: "Confirm Redemption".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| লয়্যালটি পয়েন্ট | Loyalty Point | Left top bar |
| রিডিম্পশন | Redemption | Left tile label |
| Gift Redemption | Gift Redemption | Right top bar (English) |
| Bipu Store (6909084-01779223366-Apsis Cluster) | Retailer (code-phone-cluster) | Right drop-down |
| All, A, B, M, R, S, a, b, j, o, s, t | Letter filter chips (All selected); upper and lower case both present | Right |
| Diamond League (April) | Campaign name and month | Red banner |
| ২২০ Pts Remaining | 220 points remaining (Bangla digits, English words) | Banner pill |
| প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক। | 2 taka cash back for every 1 point | Card 1 title |
| ২ স্টেপ কিচেন র‍্যাক। | 2-step kitchen rack | Card 2 title |
| চেয়ার। | Chair | Card 3 title |
| টর্নেডো ফ্যান। | Tornado fan | Card 4 title |
| ১ | Points needed (card 1) | Chip |
| ২০০ | Points needed (cards 2, 3) | Chips |
| ৪০০ | Points needed (card 4) | Chip |
| 0 | Quantity | Stepper centre, all four cards |
| Confirm Redemption | Confirm Redemption | Bottom button |

## Data and figures shown
- Campaign "Diamond League (April)" with ২২০ points remaining.
- Gift costs: cash back 1 point per unit (output 2 taka per point is only in the title text), kitchen rack 200, chair 200, tornado fan 400.
- All quantities 0. The 400-point fan has a greyed "+", the others green; I record the look only and do not infer the rule.
- The retailer Bipu Store, code 6909084, phone 01779223366, cluster name "Apsis Cluster".

## Colours and style
- Gradient red-to-purple top bars; page light grey (about #E8E8E8); red banner (about #D9453F); letter strip dark grey (about #4A4A4A) with white letters; selected chip purple-red gradient; points chip pale orange with orange text and a gold star coin; "+" buttons teal-green (about #0A7F6E), "-" and disabled buttons grey; bottom button red with white text.

## Navigation and behaviour
- Manual: "Loyalty Point > রিডিম্পশন. Choose the retailer; the campaign (e.g. Diamond League) with points remaining; gifts with points needed and - / + (e.g. 2 taka cashback per point, kitchen rack, chair, fan)." What to do: "Choose the retailer and the gifts within the points available."
- Tap the tile on the left to open the page on the right. Pick the retailer, adjust quantities with - / +, tap "Confirm Redemption" (leads to the dialog in screenshot 54).

## Differences between the image and the manual text
- The title of the right page is English ("Gift Redemption") while the menu page and tile are Bangla.
- The right screen is a different capture (no status bar, black bottom bar, different scale), apparently from another phone or session than the left.
- The manual does not mention the letter filter, which here mixes capital and small letters.
- The "Apsis Cluster" text appears in the retailer name.

## Open questions for the owner
- How are points earned, and what does the cash back convert to (the title says 2 taka per point; the chip says 1)?
- Why is the "+" on the 400-point fan grey (insufficient points, 220 remaining)? Confirm the rule before building.
- Are more gifts below the fold (a row is cut off)?
- Loyalty is deferred (docs/27): hooks only; do not build points rules.
