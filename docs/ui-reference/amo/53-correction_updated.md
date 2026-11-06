# 53. Correction updated

## Screens in the image
- Single phone screen (full height, no status bar visible; a thin black system gesture bar at the very bottom). It is the vendor AMO app, screen "তথ্য পরিবর্তন করুন" (Change information), shown dimmed behind a success dialog (an in-app dialog, not an Android system dialog).

## Layout, top to bottom
1. Top bar: very dark purple (deep indigo-purple), flat colour, full width. Back arrow (outlined triangle-style "back" glyph, pale/greyed because the screen is dimmed) at the left. Title "তথ্য পরিবর্তন করুন" centred, white (dimmed to light grey), large.
2. Page background: light grey (dimmed by the dialog scrim; underneath it is the normal light page).
3. One rounded card (slightly lighter than the dimmed page, no strong shadow) holding five label : value rows. Labels at left, a colon ":" column in the middle, values at right in bold dark text:
   - রুট : Savar BazarDaily
   - ক্লাস্টার : Madrasa Road
   - খুচরা বিক্রেতার নাম (label wraps onto two lines: "খুচরা বিক্রেতার" / "নাম") : Tasmin
   - মালিকের নাম : Jubaer
   - যোগাযোগ : 01719789036
4. Scrim: the rest of the screen below the card is dimmed grey; it is empty.
5. Centred modal dialog (white, large rounded corners, soft dark shadow), about 90% of screen width, vertically around the middle:
   a. Green filled circle with white check mark, centred at top.
   b. Message "Data Updated Successfully" centred, blue, bold, English.
   c. Green rounded button "ওকে" with white text, bottom-right of the dialog (enabled).
6. Bottom: dark system gesture bar.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| তথ্য পরিবর্তন করুন | Change information | Top bar title |
| রুট | Route | Card row 1 label |
| Savar BazarDaily | Route name (Savar Bazar, daily route; name is English, no space before "Daily") | Card row 1 value |
| ক্লাস্টার | Cluster | Card row 2 label |
| Madrasa Road | Cluster name | Card row 2 value |
| খুচরা বিক্রেতার নাম | Retailer name | Card row 3 label (wrapped on two lines) |
| Tasmin | Retailer (shop) name | Card row 3 value |
| মালিকের নাম | Owner name | Card row 4 label |
| Jubaer | Owner name | Card row 4 value |
| যোগাযোগ | Contact (mobile) | Card row 5 label |
| 01719789036 | Mobile number, Latin digits, no separators | Card row 5 value |
| : | Colon separator | Between label and value in every row |
| Data Updated Successfully | Success message (English text, not Bangla) | Dialog body |
| ওকে | OK | Dialog button |

## Data and figures shown
- Only one number: mobile 01719789036 (Latin digits, 11 digits, Bangladesh mobile format). All values are example data (route "Savar BazarDaily", cluster "Madrasa Road", retailer "Tasmin", owner "Jubaer").
- The card is a read-only summary of the correction just saved (route, cluster, retailer, owner, mobile).

## Colours and style
- Top bar: very dark purple / deep indigo (about #25104F as seen, darker than the purple of screens 54 and 55), flat, no gradient visible.
- Page: light grey, dimmed by a ~50 percent black scrim.
- Card: light grey-white, rounded corners (about 16 dp), no obvious shadow; text black, values semi-bold.
- Dialog: white, strongly rounded (about 28 dp), large soft shadow. Check icon green (#4CAF50 type). Message blue (#1E7BC0 type) bold. OK button green (#4CAF50 type), rounded, white text, small shadow.
- No packshots, no tiles, no progress bars on this screen.

## Navigation and behaviour
- Per the manual: the screen says "Data Updated Successfully"; the AMO taps ওকে. This follows screenshot 52 (correction request: AMO corrects shop name, owner, mobile, Sub-Channel and Geo Classification, taps সংরক্ষণ and confirms with হ্যাঁ). After ওকে the dialog closes (return to the previous list/screen is implied, not shown).

## Differences between the image and the manual text
- The manual does not mention the summary card (route, cluster, retailer name, owner name, contact) behind the dialog, nor the screen title "তথ্য পরিবর্তন করুন".
- The dialog message is English "Data Updated Successfully" while other success dialogs (screenshot 56) use Bangla; the manual quotes it in English, which matches.

## Open questions for the owner
- none
