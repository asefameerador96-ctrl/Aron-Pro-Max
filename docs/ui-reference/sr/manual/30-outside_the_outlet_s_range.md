# 30. Outside the outlet's range

## Screens in the image
Two phone screens, visually identical (the same Sales page with the "outside range" warning), side by side.

## Layout, top to bottom
1. Top bar red-to-purple gradient, back chevron at left, centred memo icon (pink/blue) and white title "বিক্রয়". (No status bar in this crop.)
2. Light grey page. A white rounded dropdown box: "Moin Store (DHK-344-005-01913654095-Apsis Cluster)" with a down arrow at right.
3. A dark grey rounded bar holding alphabet filter chips: "All" (selected, red-to-purple gradient chip with white text), "A", "B", "M", "R", "S", "a", "b", "j", "o", "s" on one row, "t" alone on a second row.
4. Two outlined rounded buttons: left light blue "Sale History" with a clock-arrow icon; right light peach "Points" with an orange medal icon.
5. Centred red warning triangle with "!".
6. Blue message, centred: "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।"
7. Full-width violet-to-crimson gradient button "ফোর্স সেল".
8. Full-width dark charcoal button "রিফ্রেশ".
9. Rest of the page empty; black gesture bar at the bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় | Sales | Top bar |
| Moin Store (DHK-344-005-01913654095-Apsis Cluster) | Outlet name, code (with a phone-number-like part) and cluster | Dropdown |
| All | All (filter) | Chip, selected |
| A B M R S a b j o s t | First letters of outlet names (filter chips) | Chip bar |
| Sale History | Sale history | Blue button |
| Points | Points (loyalty, deferred feature) | Peach button |
| আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই। | You are not within the selected retailer's range. | Warning message |
| ফোর্স সেল | Force sale | Button |
| রিফ্রেশ | Refresh | Button |

## Data and figures shown
- Outlet: Moin Store, code "DHK-344-005-01913654095", cluster "Apsis Cluster" (the cluster name contains the vendor name; test data).
- No distance in metres, accuracy or GPS status is shown; the message gives no number.
- Chip letters show the filter set of first letters of the outlet names in the route (uppercase and lowercase listed separately, so case matters in the vendor's grouping).

## Colours and style
- Header gradient red-to-violet; page light grey; dropdown white with a large radius.
- Alphabet bar dark grey with white letters; selected chip a red-to-purple gradient square.
- Sale History light blue fill with blue outline and blue icon/text; Points light peach fill with orange outline and orange icon, dark text.
- Warning triangle red; the message in blue (same blue as dialog titles).
- Force sale gradient violet (left) to crimson (right); Refresh flat charcoal gradient.
- No packshots.

## Navigation and behaviour
- This is the Sales page when the SR's position is outside the outlet's geofence.
- রিফ্রেশ re-reads the location and re-checks the range; the manual says to tap it first.
- ফোর্স সেল opens the reason screen (screenshot 31).
- Sale History and Points remain available; the product list is not shown while out of range.
- Chips filter the outlet dropdown by first letter (inferred).

## Differences between the image and the manual text
- The manual says: "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই" with ফোর্স সেল and রিফ্রেশ; the image shows a trailing danda "।" and the buttons exactly as described.
- The manual does not mention the outlet dropdown, the alphabet chip bar, Sale History or Points buttons on this page.
- The manual does not give the geofence radius; the image shows no distance either.
- The two panels are identical (a duplicate), the second adds no state.

## Open questions for the owner
- What is the range (metres) and the location accuracy limit that trigger this message?
- Why are uppercase and lowercase letters shown as separate chips?
- Does the vendor app show distance or a map here? None is visible in this screenshot (the new app plans an inline map with distance on Sale).
