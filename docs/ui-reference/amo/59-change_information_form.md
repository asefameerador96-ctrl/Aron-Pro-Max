# 59. Change information form

## Screens in the image
- Single full screen: the vendor app's তথ্য পরিবর্তন করুন (Change information) form in the ARON AMO app (Outlet > দোকান সংক্রান্ত অপারেশন > তথ্য পরিবর্তন করুন). It is a real phone screenshot including the Android status bar (one line: clock 6:19, notification icons, location icon, 4G and signal, battery 62).

## Layout, top to bottom
1. Status bar (system): "6:19", small icons, 4G, signal, battery "62" (not part of the app).
2. Top bar: deep purple, outline back arrow at the left (white), title "তথ্য পরিবর্তন করুন" centred, white bold.
3. Page background: very light lavender-white.
4. Route drop-down: white rounded box with a thick violet/purple border, showing "LaxmipurDaily" at the left and a black down-triangle at the right. Value selected.
5. Retailer drop-down: same style box; at the left a small round thumbnail (the outlet photo, brown/red circular image), then "Billal Store (2625509-01624788895-Kacha Bazar)", black down-triangle at the right. Value selected.
6. Alphabet filter bar: dark grey (charcoal) rounded bar containing letters in white in two rows: first row "All" (selected: green rounded square, white text), "A", "B", "D", "H", "J", "L", "M", "N", "R", "S"; second row "T", "m", "s". (Letters are the first letters of outlet names on the route; lower-case m and s follow the capitals.)
7. White rounded form card with these items:
   a. Label "ক্লাস্টার নির্বাচন করুন" (appears twice, one line above the other; the duplicate is visible in the image).
   b. Cluster drop-down: white rounded box, violet border, "Kacha Bazar", black down-triangle.
   c. Label "দোকানের নাম"; text field (white, rounded, violet border) with "Billal Store" in a serif font.
   d. Label "দোকানের মালিকের নাম"; text field with "Abdur Rashid".
   e. Label "মোবাইল নম্বর"; text field with "01624788895".
8. Empty space.
9. Button 1: full width, deep purple filled, rounded, centred white text "GEO এবং ছবি ধারণ করুন" (a wider gap between "ধারণ" and "করুন"). Enabled.
10. Button 2: full width, green vertical gradient (lime-yellow to green), rounded, white save icon and "সংরক্ষণ". Appears enabled (full colour here).
11. Bottom edge: screen ends; no bottom navigation bar visible.

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| 6:19 | Time (status bar) | Status bar |
| 62 | Battery percent (status bar) | Status bar |
| তথ্য পরিবর্তন করুন | Change information | Top bar title |
| LaxmipurDaily | Route name | Route drop-down |
| Billal Store (2625509-01624788895-Kacha Bazar) | Outlet display text: shop (outlet code - owner mobile - cluster) | Retailer drop-down |
| All | All outlets | Alphabet bar, selected green chip |
| A B D H J L M N R S T m s | Initial letters of outlet names | Alphabet bar (13 letters, two rows) |
| ক্লাস্টার নির্বাচন করুন | Select a cluster | Form card, shown twice |
| Kacha Bazar | Cluster | Cluster drop-down |
| দোকানের নাম | Shop name | Label of field |
| Billal Store | Shop name value | Text field |
| দোকানের মালিকের নাম | Shop owner's name | Label of field |
| Abdur Rashid | Owner name value | Text field |
| মোবাইল নম্বর | Mobile number | Label of field |
| 01624788895 | Mobile value (Latin digits, with leading 0) | Text field |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo | Purple button |
| সংরক্ষণ | Save | Green button |

All numbers are Latin digits. The outlet code is 2625509. The text "ক্লাস্টার নির্বাচন করুন" is repeated twice. The label of the form fields has no colon.

## Data and figures shown
- Example: route LaxmipurDaily, outlet Billal Store (code 2625509), cluster Kacha Bazar, owner Abdur Rashid, mobile 01624788895. The form fields are pre-filled from the selected outlet and are editable.
- No totals or percentages.

## Colours and style
- Top bar: deep purple, flat. Page: very light lavender-white. Form card: white, rounded, faint shadow.
- Drop-downs and text fields: white, rounded, thick violet border (about 2px).
- Alphabet bar: dark grey (#4a4a4a-like) with white letters; selected chip "All" green gradient rounded square.
- GEO button: solid deep purple (same as top bar), white text. Save button: green gradient, white text and icon.
- Outlet thumbnail: small round photo in the retailer drop-down (the outlet's saved photo). No packshots.
- Values in text fields use a serif typeface; labels use the Bangla UI font.

## Navigation and behaviour
- Per the manual: select the route and the outlet, then correct the information (cluster, shop name, owner name, mobile). Tap "GEO এবং ছবি ধারণ করুন" at the shop (opens the camera, screen 60), then tap সংরক্ষণ.
- Tapping a letter in the alphabet bar filters the outlet list to names that start with it; "All" shows every outlet (as in the SR app and the Update base screen).
- Selecting an outlet fills the fields below with its current data.
- Back arrow returns to the Outlet menu.

## Differences between the image and the manual text
- The manual does not mention the round outlet photo thumbnail in the retailer drop-down.
- The manual does not mention the duplicated label "ক্লাস্টার নির্বাচন করুন" (appears twice in the image).
- The manual says "route, retailer, alphabet bar, cluster, shop name, owner name, mobile number" - matches; it does not say that the GEO button is purple and the save button green, nor that there is no বাতিল (Cancel) button here (screen 48, the new-shop form, has one).
- Alphabet bar includes lower-case letters m and s after T, not described in the manual.

## Open questions for the owner
- Is the duplicated cluster label a vendor-app bug to reproduce or to fix? (Suggest: show it once.)
- Does the alphabet bar sort lower-case initials after the capitals, or is it a case-sensitive sort bug?
