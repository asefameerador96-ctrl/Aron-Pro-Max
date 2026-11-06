# 17. Control Call: select route and outlet

Source image: `17-control_call_select_route_and_outlet.jpg`. Manual section 8 (Control Call and selling), screenshot 17.

## Screens in the image
A single phone screen (not two): the vendor ARON AMO app, the "Control Call Activity" screen in its "out of range" state, with the route and outlet already selected. Not a system dialog. Status bar (one line): clock 5:09, location pin, network speed, 4G, signal bars, battery 58. The Android 3-button navigation bar (black) is at the very bottom.

## Layout, top to bottom
1. Top bar: solid deep purple (indigo-purple, flat, no gradient), about 14% of screen height. Left: white back arrow drawn as a rounded left-pointing triangle outline. Centre: white bold Bangla title "কন্ট্রল কল অ্যাক্টিভিটি".
2. Page background: very light lavender/off-white (almost white with a pink-lavender tint).
3. Route drop-down: full-width white field, rounded corners, 2 px deep-purple outline, text "DariapurDaily" in black at left, small dark down-triangle at right. State: selected.
4. Outlet (retailer) drop-down: white rounded field with 2 px purple outline, about 85% of the width (narrower than the route field) so a LIVE icon fits at its right. Content: a bright green filled dot at the left, then "Basahar Store (2654199-123-Tatul Tola)" in black, down-triangle at right. State: selected. The green dot means the outlet is a normal/active outlet (per the outlet list screenshot 19 the same dot is used on every row).
5. To the right of the outlet drop-down: a "LIVE" icon: red rounded badge with white text "LIVE" and blue broadcast/radio-wave arcs at its top right. Indicates live location is being used. Not a button per the manual (it is the live location indicator).
6. Alphabet bar: a dark grey (charcoal) rounded rectangle with two rows of letters in white. Row 1: a selected "All" chip (lime-to-green gradient rounded square, white text), then A, B, D, E, F, H, J, K, M. Row 2: N, P, R, S, T, U, V, m (lower-case m is as shown). Only first letters that exist in the route's outlets are shown (letters C, G, I, L etc. absent). "All" is the selected state.
7. Warning block, centred: a red triangle warning icon with a white exclamation mark; below it the blue Bangla text "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" (ends with the Bangla danda "।").
8. Button 1 "ম্যানুয়াল ওভাররাইড": full-width rounded button with a vertical gradient from blue (top) to purple (bottom), white bold text. State: enabled.
9. Button 2 "রিফ্রেশ": full-width rounded button with a vertical gradient from medium grey (top) to near-black dark grey (bottom), white bold text. State: enabled.
10. Rest of the screen: empty light page down to the navigation bar. No bottom bar of the app.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| কন্ট্রল কল অ্যাক্টিভিটি | Control Call Activity | Top bar title |
| DariapurDaily | Route name (route name "Dariapur" + the word "Daily" joined without space) | Route drop-down |
| Basahar Store (2654199-123-Tatul Tola) | Outlet name (outlet code 2654199 - phone 123 - cluster Tatul Tola); format is "name (code-phone-cluster)"; phone value is the placeholder 123 | Outlet drop-down |
| All | Show all outlets (no letter filter) | Alphabet bar, selected chip |
| A B D E F H J K M | First-letter filters | Alphabet bar row 1 |
| N P R S T U V m | First-letter filters (last one is lower-case m as displayed) | Alphabet bar row 2 |
| LIVE | Live location indicator | Badge right of outlet drop-down |
| AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই। | AMO and retailer are not within range. | Warning text under the alphabet bar |
| ম্যানুয়াল ওভাররাইড | Manual Override | Blue-purple button |
| রিফ্রেশ | Refresh | Dark grey button |
| 5:09 | Clock | Status bar |
| 0.02 KB/S, 4G, 58 | Network speed, network type, battery % | Status bar |

All digits in the image are Latin digits (2654199, 123, 5:09, 58, 0.02). No Bangla digits, no thousand separators, no units.

## Data and figures shown
- Outlet code 2654199 (first number inside brackets), phone field 123 (placeholder, not a real phone), cluster "Tatul Tola".
- Route "DariapurDaily" = the AMO's route for the day (daily route of the Dariapur area).
- No sales or target figures on this screen.

## Colours and style
- Top bar: deep purple (about #4B1A9A), flat, white title text, no gradient, no shadow visible.
- Page: very light lavender/white. Drop-downs: white fill, 2 px deep-purple border, rounded (about 14 px radius).
- Alphabet bar: charcoal grey (#555) rounded panel; letters white; selected "All" chip lime-green gradient (light lime top, green bottom) with white text.
- Green dot: bright green (#22C922).
- Warning icon: red triangle, white exclamation; warning text: medium blue (#1565C0-ish), Bangla serif-like face.
- Manual Override button: blue (#0D4FA0) to purple (#4B1A9A) vertical gradient. Refresh button: grey (#6B6B6B) to charcoal (#2E2E2E) vertical gradient.
- LIVE badge: red with white text, blue waves.
- No packshots on this screen. Icons are flat, not gradient red-to-purple.

## Navigation and behaviour
- Back arrow: returns to the previous screen (Dashboard).
- The manual: "Select the route and the outlet. If you are at the outlet but get the warning, tap Refresh. If it still shows, tap Manual Override."
- Refresh (রিফ্রেশ): re-reads the GPS position and re-checks the range to the selected outlet.
- Manual Override (ম্যানুয়াল ওভাররাইড): per screenshot 20 it opens the camera to photograph the outlet front; after the photo the Control Call menu (Sales, SR Performance Assessment, Survey) appears. The photo also updates the outlet's location information.
- When in range the warning and both buttons are replaced by the three-tile menu (screenshot 18).
- Tapping the outlet drop-down opens the outlet list (screenshot 19). Tapping a letter filters that list by first letter; All clears the filter.
- Manual section 8: the AMO must be within the outlet's range; otherwise a manual override with a photo is needed.

## Differences between the image and the manual text
- The manual says "Alphabet bar (All, A, B, C ...)"; in the image the bar shows only letters present in the route (no C, G, I) and ends with a lower-case "m".
- The manual does not mention the green dot in the outlet field or the red warning triangle icon (only the text).
- The manual calls the LIVE icon a "LIVE location icon"; it looks like a badge, not a button.
- The warning text in the image ends with "।" which the manual text does not show.
- The manual text calls the dark grey button Refresh and does not mention button colours.

## Open questions for the owner
- Is the LIVE icon tappable, or only an indicator?
- What range (metres) counts as "within range" for the AMO? (Not stated on this screen.)
