# 33. Live location of SRs

Manual (screenshot 33): "Left: the SR list. Right: the selected SR's name and phone on Google Maps at his live position. What to do: Tap Team Location, choose an SR, and zoom the map as needed." Dashboard tile: টিম লোকেশন = Team Location, "See the live location of your SRs (section 10)".

## Screens in the image
- Left: the vendor app's Team Location screen with the SR drop-down opened. The list is an overlay panel drawn over the map. The title bar and drop-down field are covered by the panel; only the deep-purple status-bar strip is visible above it. This is a Material drop-down menu of the vendor app.
- Right: the vendor app's Team Location screen (title টিম লোকেশন) with one SR selected ("Shafikul Islam") and the Google Map centred on his live position, with an info bubble (name and phone) above a rider/scooter marker.
- Both are the vendor app (Android system status bar on top, gesture bar at the bottom). Neither is a system dialog.

## Layout, top to bottom
Left screen (drop-down open):
1. Status bar (one line): 6:20, small notification icons, location pin, 4G, signal, battery 62. Deep purple.
2. Deep-purple strip above the list (the top bar is covered by the open list; title not visible).
3. White drop-down panel with a soft shadow, full width, rows separated only by spacing (no divider lines). Rows, each a single left-aligned name in a serif-looking Latin font, in this order: Mahmud Hasan; Shafikul Islam (this row is highlighted light grey = currently selected); Md.Nazrul Islam; Md. Al Amin (Mitali); Md. Razu Mia; Md.Ziul Islam; MD Arif; MD. Reza Mia; Md.Rasik Mia; N/A (last row, literal text "N/A").
4. Below the panel the Google map continues (visible: shop icons, "Gaibandha Post Office" with Bangla "প্রধান ডাকঘর", "Old Bridge, Gaibandha" with Bangla "পুরাতন ব্রিজ, গাইবান্ধা", a blue river area, Google logo bottom-left).
5. Bottom: grey gesture bar.

Right screen (SR selected):
1. Status bar (one line): 6:20, location pin, 4G, signal, battery 62. Deep purple.
2. Top bar, deep purple: back arrow at the left (white, outlined left-pointing triangle/chevron), centred white title "টিম লোকেশন" in Bangla.
3. Under the bar, on the light page background, a rounded white card containing the SR drop-down field: left text "Shafikul Islam", right a small dark down-arrow triangle. Card has a soft shadow and a very light lavender tint.
4. Full-bleed Google map (light vector style: light grey buildings, pale yellow blocks, grey roads, blue-grey water). Overlays: a compass button (white circle with red/grey needle) top-left of the map; a "my location" crosshair button (white square, grey target icon) top-right; a grey map pin at the far right edge (partly cut off, label cut: "G…").
5. Map centre: a white info bubble with bold "Shafikul Islam" and below it the plain number "1705850327" (the SR's phone, shown without leading zero as it appears in the image). Below the bubble a small orange/yellow delivery-scooter rider icon is the SR marker.
6. Other map labels (Google): "Mill" with Bangla "র মিল" (blue lock/shop icon), "Gaibandha Circle Corner" with "গাইবান্ধা গোল চত্বর" (purple icon), road badge "R555", "Mishtanno Va…" with "মিষ্টান্ন" (cut off), "Gaibandha Tennis Club" with "গাইবান্ধা টেনিস ক্লাব", "Gaibandha Post Office", Google logo bottom-left. These are map tiles, not vendor UI.
7. Bottom: grey gesture bar.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| টিম লোকেশন | Team Location | Right screen, top bar title (centred, white) |
| Shafikul Islam | SR name | Right: drop-down field value and info-bubble title; Left: highlighted list row |
| 1705850327 | SR phone number (Latin digits, no separators) | Right: info bubble under the name |
| Mahmud Hasan | SR name | Left list row 1 |
| Md.Nazrul Islam | SR name | Left list row 3 |
| Md. Al Amin (Mitali) | SR name | Left list row 4 |
| Md. Razu Mia | SR name | Left list row 5 |
| Md.Ziul Islam | SR name | Left list row 6 |
| MD Arif | SR name | Left list row 7 |
| MD. Reza Mia | SR name | Left list row 8 |
| Md.Rasik Mia | SR name | Left list row 9 |
| N/A | Not available (placeholder entry, no SR name) | Left list row 10 |
| 6:20 and 62 | Clock and battery in the status bar | Both screens (status bar only) |
| Mill / র মিল | Map label (Google) | Right map |
| Gaibandha Circle Corner / গাইবান্ধা গোল চত্বর | Map label (Google) | Right map |
| R555 | Road number badge (Google) | Right map |
| Mishtanno Va… / মিষ্টান্ন | Map label, cut off (Google) | Right map |
| Gaibandha Tennis Club / গাইবান্ধা টেনিস ক্লাব | Map label (Google) | Right map |
| Gaibandha Post Office / প্রধান ডাকঘর | Map label (Google) | Both maps |
| Old Bridge, Gaibandha / পুরাতন ব্রিজ, গাইবান্ধা | Map label (Google) | Left map (below list) |
| হাবীব ভান্ডার, esh food market | Map labels, partly cut off (Google) | Left map, just under the list, left edge |
| Google | Map attribution logo | Bottom-left of both maps |

No digits in Bangla script appear on this screen. Names are Latin script only.

## Data and figures shown
- The list is the AMO's own SRs (names). The last entry "N/A" is a record with no name (likely an SR slot without a name or a blank entry). Currently selected SR: Shafikul Islam, phone 1705850327.
- The marker is the SR's last known live position. Manual: "selected SR's name and phone on Google Maps at his live position". No timestamp, accuracy or battery is shown.
- No other figures.

## Colours and style
- Top bar: deep purple (about #4A1A9A, a solid indigo-purple, no gradient visible on this screen). White title and white back arrow.
- Page background around the card: very light lavender/off-white. SR drop-down card: white with a faint lavender tint, large rounded corners, soft shadow.
- Open list: pure white panel, black text, selected row light grey (#DDDDDD-like).
- Map: standard Google light style; marker is a small orange/yellow rider-on-scooter picture (not a pin). Info bubble white with shadow, black text (name bold).
- No packshot pictures.

## Navigation and behaviour
- Entered from the dashboard tile "টিম লোকেশন" (Team Location).
- Tap the drop-down card to open the SR list; tap a name to select it; the map moves to that SR's live position and shows the bubble with name and phone. The currently selected row is highlighted in the list.
- Map is a normal interactive Google map (zoom, pan); compass and my-location buttons are the standard Google controls.
- Back arrow (top-left) returns to the dashboard.
- Manual does not say how often the position refreshes.

## Differences between the image and the manual text
- The manual does not mention the "N/A" last list entry.
- The manual does not mention the compass and my-location buttons (standard map controls) or that the phone number is shown without a leading 0.
- The marker is a rider/scooter picture, not a standard red pin; the manual does not describe it.
- Otherwise it matches the manual.

## Open questions for the owner
- What does the "N/A" row represent, and should the new app show it? (Proposed: do not show unnamed rows.)
- How fresh is the "live" position (last sync time shown? refresh interval)? Not visible in the image.
