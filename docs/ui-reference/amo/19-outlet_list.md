# 19. Outlet list

Source image: `19-outlet_list.jpg`. Manual section 8, screenshot 19.

## Screens in the image
A single phone screen: the vendor ARON AMO app showing the open outlet drop-down list (a pop-up list panel) of the Control Call Activity screen. The app's top bar and the two drop-down fields are covered; the list panel fills the screen from just below the status bar. Not a system dialog. Status bar (one line): clock 5:08, location pin, 0.03 KB/S, 4G, signal, battery 58. Android 3-button navigation bar (black) at the bottom.

## Layout, top to bottom
1. A strip of deep-purple (the top bar colour, flat) containing only the status bar, about 6% of screen height.
2. A white list panel with rounded bottom corners and a soft shadow below it, starting right below the purple strip and ending about 12% above the bottom of the screen. A thin grey vertical scrollbar is at its right edge near the top (indicating more rows below).
3. List rows, each about 96 px tall, no divider lines, left-aligned. Each row starts with a bright green filled dot. Some rows have, after the green dot, a small round brown/bronze circular badge (a face/avatar-like emblem). Row text is black, regular, one line.
   - Row 1 (selected, grey highlight across the full row): green dot, "Basahar Store (2654199-123-Tatul Tola)".
   - Row 2: green dot + brown badge, "Tota Store (2647524-01722800485-Katpotti)".
   - Row 3: green dot, "Belal Store (2542617-1763563290-Kaca Bazar)".
   - Row 4: green dot, "Samad Store (2517657-1789523567-Mangsho Hati)".
   - Row 5: green dot, "Default_Dariapur (1750844-123-Default_Cluster)".
   - Row 6: green dot + brown badge, "Sujon Store (1456474-01725676054-Tetultola)".
   - Row 7: green dot, "Jannati Store2 (1421252-123-Murgihati)".
   - Row 8: green dot, "Ashraf Store (1421251-1735610319-Murgihati)".
   - Row 9: green dot, "Alom Store (1421250-1624540570-Murgihati)".
   - Row 10: green dot + brown badge, "Sojib Store (1421249-01925781105-Murgihati)".
   - Row 11: green dot + brown badge, "Millat Store (1421248-01660109805-Kacha Bazar)".
   - Row 12: green dot, "Shoriful Store (1421247-1739386718-Kacha Bazar)".
   - Row 13: green dot + brown badge, "Sombaru Store (1421246-01767382912-Kacha Bazar)".
   - Row 14 (cut off at the panel's bottom edge): green dot, "Shafiul Store (1421245-1732397073-Kacha Bazar)".
4. Below the panel: light lavender page, then the navigation bar.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Basahar Store (2654199-123-Tatul Tola) | Outlet "name (code-phone-cluster)" | Row 1, highlighted (current selection) |
| Tota Store (2647524-01722800485-Katpotti) | same format | Row 2 |
| Belal Store (2542617-1763563290-Kaca Bazar) | same | Row 3 |
| Samad Store (2517657-1789523567-Mangsho Hati) | same | Row 4 |
| Default_Dariapur (1750844-123-Default_Cluster) | placeholder default outlet of the route | Row 5 |
| Sujon Store (1456474-01725676054-Tetultola) | same | Row 6 |
| Jannati Store2 (1421252-123-Murgihati) | same | Row 7 |
| Ashraf Store (1421251-1735610319-Murgihati) | same | Row 8 |
| Alom Store (1421250-1624540570-Murgihati) | same | Row 9 |
| Sojib Store (1421249-01925781105-Murgihati) | same | Row 10 |
| Millat Store (1421248-01660109805-Kacha Bazar) | same | Row 11 |
| Shoriful Store (1421247-1739386718-Kacha Bazar) | same | Row 12 |
| Sombaru Store (1421246-01767382912-Kacha Bazar) | same | Row 13 |
| Shafiul Store (1421245-1732397073-Kacha Bazar) | same, partly cut off | Row 14 |
| 5:08, 58 | Clock, battery | Status bar |

No Bangla text and no Bangla digits on this screen. All digits are Latin, no separators. Phone numbers appear sometimes with a leading 0 (01722800485, 11 digits) and sometimes without (1763563290, 10 digits) and sometimes as placeholder 123: this is data, shown as stored.

## Data and figures shown
- Each row = outlet name, outlet code (7 digits, descending order 2654199 ... 1421245), phone, cluster. Rows are ordered by outlet code descending (newest first), not alphabetically.
- Rows with the brown badge (Tota, Sujon, Sojib, Millat, Sombaru) are outlets that differ from the others; the manual does not explain the badge (probably a marker such as a photo/verified/visited-today status).

## Colours and style
- White panel, black text, green dots (#22C922), brown/bronze circular badge about 28 px, selected row light grey (#DDDCE0).
- Purple strip at top is the deep purple of the app bar. No packshots, no gradients.
- Panel has a soft drop shadow at its bottom edge (elevated pop-up list).

## Navigation and behaviour
- Per the manual: "Scroll or use the alphabet bar, then tap the outlet."
- Tapping a row selects that outlet, closes the list and shows it in the outlet drop-down; the range check then runs (warning + override, or the three-tile menu).
- The list scrolls vertically (scrollbar visible); it is the full outlet list of the selected route, filtered by the alphabet bar letter if one is chosen.

## Differences between the image and the manual text
- The manual says the list shows name, code, phone and cluster: matches.
- The image shows a green dot on every row and a brown round badge on five rows, not mentioned in the manual.
- The manual mentions the alphabet bar but it is hidden behind the list in this image.
- The list order (by code, descending) is not stated in the manual.

## Open questions for the owner
- What does the brown round badge on some outlet rows mean (e.g. photo captured, base location updated, already visited today)?
- What would a non-green dot mean (e.g. red for closed or not-yet-visited outlets)? Only green is visible.
