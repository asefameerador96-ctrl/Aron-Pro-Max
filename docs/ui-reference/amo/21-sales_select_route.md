# 21. Sales: select route

## Screens in the image
- Left: vendor app "বিক্রয়" (Sales) screen, initial state, nothing selected (route drop-down and retailer field both showing placeholder text).
- Right: the same Sales screen with the route drop-down opened, showing the route list as a dropdown panel overlaid under the top bar. Both are vendor-app screens (no system dialog). Status bar: 6:13, location pin, 4G+, signal, battery 63 (one line only).

## Layout, top to bottom
Left screen:
1. Top bar: solid deep purple, full width. Back arrow (rounded left-pointing outline triangle/chevron, white) at left; centred white bold title "বিক্রয়".
2. Page background below the bar: very light lavender/off-white (near white with pink tint).
3. Route drop-down field: white rounded rectangle with purple (about 2 px) outline; placeholder text "একটি রুট সিলেক্ট করুন" in grey at left; small dark down-arrow (caret) at right. State: enabled, empty.
4. Retailer field directly below: white rounded rectangle, purple outline, placeholder "রিটেইলার নির্বাচন করুন" in grey. No drop-down arrow is shown (field is inactive until a route is chosen). State: empty/disabled-looking.
5. Below, left-aligned: one square white tile (very light shadow/border, rounded) containing a gradient icon (pink/magenta-to-blue box with a bookmark flag at top and a blue clock badge at lower left plus lines) and, beneath it, the label "বিক্রয়" in small purple text. This is the Sales action tile/button.
6. Rest of page empty. A grey home-gesture bar at the very bottom.
Right screen:
1. Same purple top bar with back arrow and "বিক্রয়".
2. Directly under the bar: a white dropdown panel with soft shadow, full width, listing 9 routes as rows (each about 100 px tall) with left-aligned black text, no icons. The first row "LaxmipurDaily" has a grey highlighted background (pressed/focused row). The panel covers the route and retailer fields and the Sales tile.
3. Below the panel: empty light page; home bar at bottom.

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| বিক্রয় | Sales | Top bar title (both screens); label under tile (left) |
| একটি রুট সিলেক্ট করুন | Select a route | Left, route drop-down placeholder |
| রিটেইলার নির্বাচন করুন | Select retailer (outlet) | Left, retailer field placeholder |
| LaxmipurDaily | Route name (Laxmipur, daily visit) | Right, list row 1 (highlighted) |
| Tempu StandDaily | Route name | Right, row 2 |
| Kuptola(Sat, Mon, Wed) | Route name with visit days | Right, row 3 |
| Miar BazerDaily | Route name | Right, row 4 |
| Molla Bazer(Sun, Tue, Thu) | Route name with visit days | Right, row 5 |
| Bera DangaDaily | Route name | Right, row 6 |
| Mitali BazerDaily | Route name | Right, row 7 |
| SorkerparaDaily | Route name | Right, row 8 |
| KodomtolaDaily | Route name | Right, row 9 |
| Modern MoreDaily | Route name | Right, row 10 |
Note: the list has 10 rows (LaxmipurDaily to Modern MoreDaily). Route names are English; "Daily" is appended with no space to the area name (e.g. "LaxmipurDaily"); other routes append the visit days in brackets with ", " separators and English day abbreviations. Status bar text: "6:13", battery "63" (ignore).

## Data and figures shown
- No numbers except the status bar (6:13, 63). Routes shown are the AMO's assigned routes: 10 in this example. Visit-day suffixes: Kuptola Sat/Mon/Wed; Molla Bazer Sun/Tue/Thu; others Daily. No Bangla digits.

## Colours and style
- Top bar: deep purple (about #4A1A94 / indigo-violet), flat, no gradient visible. White title and back arrow.
- Page background: very pale lavender/white (#FDF7FF-like).
- Fields: white fill, rounded corners (~12 px), purple (#4A1A94-like) 2 px outline; placeholders grey.
- Dropdown panel: white, no rounding at top, drop shadow below; highlighted row grey (#DEDBE0-like). Row text black, regular weight.
- Sales tile: white card, rounded, faint border/shadow; icon is a gradient (magenta/pink at top to blue at bottom); label purple.
- No packshots on this screen.

## Navigation and behaviour
- Per manual: the Sales (বিক্রয়) tile also opens route and retailer selection. Tap "একটি রুট সিলেক্ট করুন" and choose the route from the list. After a route is chosen the retailer field becomes selectable (next screenshot, 22).
- The back arrow returns to the previous screen (Control Call/Dashboard).

## Differences between the image and the manual text
- The manual says the right screen is "the route list (e.g. LaxmipurDaily, Kuptola (Sat, Mon, Wed))"; the image shows 10 routes, the two different suffix styles and the list drawn as a panel under the bar. In the image the name is "Kuptola(Sat, Mon, Wed)" with no space before the bracket.
- The manual does not mention the Sales tile under the fields, nor that the retailer field has no arrow until a route is chosen.

## Open questions for the owner
- none
