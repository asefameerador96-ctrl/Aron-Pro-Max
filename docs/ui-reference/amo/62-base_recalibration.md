# 62. Base Recalibration

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, screen title বেস রিক্যালিব্রেশন (Base Recalibration). Status bar visible on both (one line: 6:19, location pin, 4G+, signal, battery 62).
- Left: the initial screen with nothing selected: route drop-down placeholder, retailer field placeholder, disabled Update base button.
- Right: the same screen with the route drop-down opened as a list of routes (list overlay).

## Layout, top to bottom
Left screen:
1. Status bar (6:19, alert icons, location, 4G+, signal, 62).
2. Top bar: deep purple, white back arrow at left (outlined rounded left-pointing triangle), centred white bold Bangla title "বেস রিক্যালিব্রেশন".
3. Route drop-down: white rounded field with 2 px purple border, grey placeholder "একটি রুট সিলেক্ট করুন", black down-arrow at right. Enabled, nothing selected.
4. Retailer field: white rounded field with purple border, grey placeholder "রিটেইলার নির্বাচন করুন", no arrow shown (inactive until a route is chosen).
5. Empty space (no alphabet bar yet).
6. Full-width "আপডেট বেস" button: flat dark grey (disabled), white small text, rounded, with shadow.
7. Rest of page empty light pink/lavender. Bottom gesture pill (grey).

Right screen:
1. Status bar, same.
2. Top bar, same title and back arrow.
3. A white list panel (drop-down menu) spans the full width below the top bar, with a drop shadow at its bottom edge, covering the route field. Rows are tall (about 100 px), plain black text, left-aligned, no icons, no dividers. First row "LaxmipurDaily" has a light grey highlight (pressed/selected state). Ten rows in total (see table).
4. Below the list: empty light lavender page. Bottom gesture pill.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 6:19 | clock | Status bar |
| 62 | battery percent | Status bar |
| বেস রিক্যালিব্রেশন | Base Recalibration | Top bar title (both) |
| একটি রুট সিলেক্ট করুন | Select a route | Left, route drop-down placeholder (grey) |
| রিটেইলার নির্বাচন করুন | Select retailer | Left, retailer field placeholder (grey) |
| আপডেট বেস | Update base | Left, disabled grey button |
| LaxmipurDaily | Route name (Laxmipur, visit frequency Daily) | Right list row 1 (highlighted) |
| Tempu StandDaily | Route: Tempu Stand, Daily | Right list row 2 |
| Kuptola(Sat, Mon, Wed) | Route: Kuptola, visited Sat, Mon, Wed | Right list row 3 |
| Miar BazerDaily | Route: Miar Bazer, Daily | Right list row 4 |
| Molla Bazer(Sun, Tue, Thu) | Route: Molla Bazer, visited Sun, Tue, Thu | Right list row 5 |
| Bera DangaDaily | Route: Bera Danga, Daily | Right list row 6 |
| Mitali BazerDaily | Route: Mitali Bazer, Daily | Right list row 7 |
| SorkerparaDaily | Route: Sorkerpara, Daily | Right list row 8 |
| KodomtolaDaily | Route: Kodomtola, Daily | Right list row 9 |
| Modern MoreDaily | Route: Modern More, Daily | Right list row 10 |

Note the route names are rendered with the frequency concatenated with no space (e.g. "LaxmipurDaily") exactly as shown. All text in the list is Latin; no Bangla digits anywhere.

## Data and figures shown
- Ten routes in the AMO's area; "Daily" routes are visited every day; others show visit days in brackets (Sat, Mon, Wed; Sun, Tue, Thu).
- No numbers other than the status bar.

## Colours and style
- Top bar: deep purple (indigo), white title and back icon.
- Page background: very light pink/lavender (near #FDF4FF).
- Fields: white, rounded (~12 px), 2 px purple border; placeholder text grey; value text black.
- Disabled button: flat dark grey (#5A5A5A-class), white text, soft shadow.
- List panel: white, square top corners, shadow below; highlighted row light grey.
- No packshots, no progress bars, no icons in list.

## Navigation and behaviour
Per the manual: choose the route (tap the drop-down, tap a row). The retailer field then becomes active and the alphabet bar and outlet selection appear (screenshot 63). Update base stays disabled (grey) until a route and retailer are selected. Back arrow returns to the previous screen.

## Differences between the image and the manual text
- The manual says only "route and retailer selection and আপডেট বেস"; the image shows the button is grey (disabled) in the initial state and the retailer field has no arrow.
- The manual does not list the ten route names or the "Daily"/day-list suffix format.
- The status bar of this screenshot is visible (the manual does not describe it).

## Open questions for the owner
- none
