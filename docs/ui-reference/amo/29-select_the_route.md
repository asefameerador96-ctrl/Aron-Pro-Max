# 29. Select the route

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app (Joint Call activity), both captured at 5:07 on the same phone.
- LEFT: the Joint Call screen with the route drop-down list open (a pop-up list of routes over the page). Vendor app.
- RIGHT: the empty Joint Call screen before any route is chosen: two empty outlined fields and a hint line. Vendor app.
Status bar (both): time 5:07, location pin, network speed (0.08 KB/s left, 0.14 KB/s right), 4G, signal bars, battery 59. Bottom: black Android three-button navigation bar (menu, home square, back triangle), shown on both.

## Layout, top to bottom
LEFT screen:
1. Status bar on the deep purple area (one line, see above).
2. Top bar: solid deep purple (indigo-violet, about #4B1A94), back arrow (outlined left-pointing triangle, white) at the far left, centred white bold Bangla title "জয়েন্ট কল একটিভিটি".
3. Below the top bar a very light pinkish-white page (near white, faint lavender tint) with a soft shadow band at the top of the list.
4. Drop-down list panel: full width, white/very light lavender card with a drop shadow at its bottom edge, with 10 rows of black left-aligned English route names, each row about the same height with generous spacing. The first row ("Math Bazer(Sun, Tue, Thu)") is highlighted with a light grey-lavender background (the currently focused/selected row). No dividers between rows.
5. Below the panel: empty page, then the black Android navigation bar.
RIGHT screen:
1. Status bar.
2. Top bar: same deep purple, back arrow left, same title "জয়েন্ট কল একটিভিটি".
3. Page: very light pinkish-white.
4. Route field: rounded rectangle, white fill, 2 px deep purple outline, full width with side margins, hint text in grey Bangla "একটি রুট সিলেক্ট করুন" at left, small dark down-pointing triangle (drop-down arrow) at the right end. State: empty, enabled.
5. Outlet field (directly below route field, small gap): rounded rectangle, white fill, deep purple outline, grey hint text "রিটেইলার নির্বাচন করুন". No arrow shown. State: empty (no route yet, so nothing to choose).
6. Centre of the page (about 65 percent down): centred blue bold Bangla message "একটি রুট সিলেক্ট করুন" (prompt to select a route first).
7. No alphabet bar, no tiles, no buttons, no LIVE icon on this empty state.
8. Black Android navigation bar at the bottom.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| জয়েন্ট কল একটিভিটি | Joint Call activity | Top bar title, left and right screens (white) |
| Math Bazer(Sun, Tue, Thu) | Route name, visits Sun, Tue, Thu | Left list row 1 (highlighted) |
| DariapurDaily | Route, daily | Left list row 2 |
| Volar Bazar(Sun, Tue, Thu) | Route | Left list row 3 |
| Hashem Bazar(Sat, Mon, Wed) | Route, Sat Mon Wed | Left list row 4 |
| Chowrasta(Sat, Mon, Wed) | Route | Left list row 5 |
| Chandia(Sun, Tue, Thu) | Route | Left list row 6 |
| GoderhatDaily | Route, daily | Left list row 7 |
| TaltolaDaily | Route, daily | Left list row 8 |
| Moyna Bazer(Sat, Mon, Wed) | Route | Left list row 9 |
| Ruper BazerDaily | Route, daily | Left list row 10 |
| একটি রুট সিলেক্ট করুন | Select a route | Right: hint inside route field (grey) and centre message (blue) |
| রিটেইলার নির্বাচন করুন | Select retailer (outlet) | Right: hint inside outlet field (grey) |
| 5:07, 0.08 KB/s, 0.14 KB/s, 4G, 59 | Clock, speed, network, battery | Status bars (Latin digits) |
Route names are English only, spelled as shown (Bazer / Bazar inconsistent in the source). Daily routes have the suffix "Daily" glued to the name with no space (DariapurDaily); weekly routes carry the visit days in brackets. No Bangla digits appear.

## Data and figures shown
- The list holds 10 routes assigned to this AMO (example: AMO-Dariapur area). Three routes visit Sun/Tue/Thu (Math Bazer, Volar Bazar, Chandia), three visit Sat/Mon/Wed (Hashem Bazar, Chowrasta, Moyna Bazer), four are Daily (Dariapur, Goderhat, Taltola, Ruper Bazer).
- No other figures. All digits are Latin.

## Colours and style
- Top bar: deep purple (indigo), flat, no red-to-purple gradient visible on this screen; white title and white back arrow.
- Page background: very light pink-white (near #FDF7FF).
- Fields: white, rounded corners (about 12 px), 2 px deep purple border.
- List panel: material drop-down menu, light, square corners at left and right edges (edge to edge), soft shadow below. Selected row light grey-lavender.
- Hint text grey; route names near-black; the centre prompt is medium blue (#1565C0-like), bold.
- No packshots, no icons other than the back arrow and the drop-down arrow.

## Navigation and behaviour
- Per the manual: tap Joint Call on the Dashboard and choose the route. The route field opens a drop-down (left screen) listing the AMO's routes; tapping one fills the route field and then the outlet drop-down becomes usable (see screenshot 30).
- Until a route is chosen the outlet field is empty and the screen shows the blue message "একটি রুট সিলেক্ট করুন".
- Back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The manual says the right screen shows the two fields "Select a route" and "Select retailer"; it does not mention the extra blue centre message "একটি রুট সিলেক্ট করুন" under the fields. Visible in the image only.
- The manual does not list the route names or the list highlight on the first row.
- The route drop-down arrow is present only on the route field, not on the outlet field.

## Open questions for the owner
- Is the highlighted first row a real "selected" state or only the touch/focus highlight? (Assumed focus highlight.)
- Is the list order fixed by the server or alphabetical? (Not alphabetical in the image.)
