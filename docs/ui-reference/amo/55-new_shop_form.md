# 55. New shop form

## Screens in the image
- Two phone screens side by side, both the vendor AMO app "নতুন দোকান" (New shop) form, after route and cluster are chosen. Left: larger rendering (status 6:15, battery 63), scrolled to the top, the form continues below the visible area (cut off at "Geo Classification"). Right: smaller rendering (status 6:18, battery 62), shows the whole form down to the two buttons. Status bars not described further.

## Layout, top to bottom
Left screen:
1. Top bar: deep purple, flat, back arrow at left, title "নতুন দোকান" centred, white.
2. Page background: very light lavender white.
3. Label "একটি রুট সিলেক্ট করুন"; route drop-down showing "LaxmipurDaily" (white, purple outline, down triangle).
4. Label "ক্লাস্টার নির্বাচন করুন"; cluster drop-down showing "Laximpur" (white, purple outline, rounded, down triangle).
5. White-ish card group (very slightly lighter than the page) containing three labelled text inputs, each a white rounded outlined empty box with purple outline:
   a. "দোকানের নাম"
   b. "দোকানের মালিকের নাম"
   c. "মোবাইল নম্বর"
6. Next card: label "Sub-Channel", drop-down with grey placeholder "Select Sub-Channel" and a down triangle.
7. Next card: label "Geo Classification", drop-down with grey placeholder "Select Geo Classification" and a down triangle (partly cut off at the bottom of the screen).
8. Bottom: grey gesture bar (overlapping the last field).

Right screen (same elements, plus buttons):
1. Top bar as above.
2. Label "একটি রুট সিলেক্ট করুন"; route drop-down "LaxmipurDaily".
3. Label "ক্লাস্টার নির্বাচন করুন"; cluster drop-down "Laximpur".
4. Card with five empty white outlined inputs in this order: "দোকানের নাম", "দোকানের মালিকের নাম", "মোবাইল নম্বর", "দোকানের মালিকের নাম", "মোবাইল নম্বর" (owner name and mobile number appear twice).
5. Card: "Sub-Channel" with placeholder drop-down "Select Sub-Channel".
6. Card: "Geo Classification" with placeholder drop-down "Select Geo Classification".
7. Wide rounded button, dark purple (deep indigo) fill, white text "GEO এবং ছবি ধারণ করুন" centred (enabled).
8. Wide rounded button, green gradient (yellow-green at right to green at left), white save (floppy disk) icon then white text "সংরক্ষণ" centred.
9. Bottom: gesture bar.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| নতুন দোকান | New shop | Top bar title (both) |
| একটি রুট সিলেক্ট করুন | Select a route | Label above route drop-down (both) |
| LaxmipurDaily | Route name | Route drop-down value (both) |
| ক্লাস্টার নির্বাচন করুন | Select a cluster | Label above cluster drop-down (both) |
| Laximpur | Cluster name (spelled "Laximpur" in the image) | Cluster drop-down value (both) |
| দোকানের নাম | Shop name | Input label (both) |
| দোকানের মালিকের নাম | Shop owner name | Input label (both; twice on right) |
| মোবাইল নম্বর | Mobile number | Input label (both; twice on right) |
| Sub-Channel | Sub-channel (outlet sub-channel) | Card label (both) |
| Select Sub-Channel | Placeholder | Sub-Channel drop-down (both) |
| Geo Classification | Geo classification | Card label (both) |
| Select Geo Classification | Placeholder | Geo Classification drop-down (both) |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo | Purple button (right) |
| সংরক্ষণ | Save | Green button (right) |
| 6:15 / 6:18 | Clock | Status bar (left / right) |
| 63 / 62 | Battery percent | Status bar (left / right) |

## Data and figures shown
- No numeric business data. All text inputs are empty; route "LaxmipurDaily", cluster "Laximpur" are example selections. Input fields show no hint text.

## Colours and style
- Top bar deep purple (about #4A1A96), white bold title; page very light lavender white; form fields grouped in faint lighter rounded cards.
- Inputs and drop-downs: white fill, thin purple outline, rounded (about 8 to 12 dp); labels black, small; placeholders grey.
- GEO button: dark purple/indigo (about #3B1A8E) solid, white text, rounded (about 8 dp). Save button: green gradient (yellow-green to green), white text and icon.
- No packshots or progress bars.

## Navigation and behaviour
- Per the manual: fill in every field, tap "GEO এবং ছবি ধারণ করুন" while at the shop and take the photo (camera opens, see 56), then tap "সংরক্ষণ". The back arrow returns to the previous screen. Sub-Channel and Geo Classification are drop-down selections.

## Differences between the image and the manual text
- The right screen shows the owner-name and mobile inputs twice (five inputs: shop name, owner, mobile, owner, mobile); the manual lists each once, and the left screen shows each once. Probably a duplicated card on the right-hand rendering (the two screens may be different app versions: the right one has the buttons visible, the left one is cut off).
- The manual does not describe the route and cluster drop-downs staying at the top of the form, nor the green gradient on the save button, nor the save icon.
- Cluster is spelled "Laximpur" while route is spelled "LaxmipurDaily".

## Open questions for the owner
- Are the owner-name and mobile-number inputs genuinely duplicated in the vendor app form (right screen), or a rendering glitch? The rebuild will show them once unless told otherwise.
