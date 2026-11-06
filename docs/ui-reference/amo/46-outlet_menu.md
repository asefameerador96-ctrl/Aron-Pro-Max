# 46. Outlet menu

## Screens in the image
Single screen (portrait phone). Vendor ARON AMO app screen titled "আউটলেট" (Outlet), a tile menu. Not a system dialog. Status bar (one line): 6:15, location pin, 4G+, signal, battery 63.

## Layout, top to bottom
1. Top bar, solid deep purple, full width. Left: white back arrow (rounded left triangle outline). Centre: white bold title "আউটলেট".
2. Page background: very light lavender-white.
3. Section heading 1 (left-aligned, dark text, no card): "এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন".
4. Row of three white rounded tiles (soft shadow), equal size, each with an icon in the upper part and a two-line centred label below in dark blue/purple text. Each tile has a small red round badge at its top-right corner containing "০":
   - Tile 1 (left): icon of a shop front with a red-to-blue gradient awning and a blue "+" sign (new shop). Label "নতুন আউটলেট / যাচাইকরণ" (two lines).
   - Tile 2 (middle): icon of a round pink-to-blue gradient disc with a "CLOSE" hanging sign. Label "আউটলেট বন্ধের / যাচাইকরণ".
   - Tile 3 (right): icon of a shop front with a pink-to-purple gradient and a mouse-pointer/cursor with sparkle (edit/correct). Label "আউটলেট সংশোধনের / যাচাইকরণ".
5. Section heading 2 (left-aligned): "দোকান সংক্রান্ত অপারেশন্স".
6. Grid row 1 of three white tiles (no badges):
   - Tile 4 (left): same new-shop icon as tile 1. Label "নতুন দোকান" (one line).
   - Tile 5 (middle): same CLOSE-sign icon as tile 2. Label "স্থায়ী বন্ধ".
   - Tile 6 (right): same edit icon as tile 3. Label "তথ্য পরিবর্তন করুন".
7. Grid row 2 of two white tiles (left and middle; the third slot is empty):
   - Tile 7 (left): coloured line icon of a shop with a red-and-white striped awning and a red map pin on top (full colour: red, blue, black outline). Label "আপডেট বেস".
   - Tile 8 (middle): blue line icon of a shop cluster (a central shop with several smaller shops, blue). Label "ক্লাস্টার".
8. Rest of the page empty; Android gesture bar at the bottom.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | where it appears |
|---|---|---|
| আউটলেট | Outlet | top bar title |
| এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন | Verify the requests coming from SRs (note the word is spelled "আশা" in the image) | section heading 1 |
| নতুন আউটলেট যাচাইকরণ | New outlet verification | tile 1 (wraps to two lines: "নতুন আউটলেট" / "যাচাইকরণ") |
| আউটলেট বন্ধের যাচাইকরণ | Outlet close verification | tile 2 (two lines: "আউটলেট বন্ধের" / "যাচাইকরণ") |
| আউটলেট সংশোধনের যাচাইকরণ | Outlet correction verification | tile 3 (two lines: "আউটলেট সংশোধনের" / "যাচাইকরণ") |
| ০ | 0 (Bangla digit zero, pending count) | red badge on each of tiles 1, 2 and 3 |
| দোকান সংক্রান্ত অপারেশন্স | Shop-related operations | section heading 2 |
| নতুন দোকান | New shop | tile 4 |
| স্থায়ী বন্ধ | Permanent close | tile 5 |
| তথ্য পরিবর্তন করুন | Change information | tile 6 |
| আপডেট বেস | Update base | tile 7 |
| ক্লাস্টার | Cluster | tile 8 |
| CLOSE | English word on the sign inside the icon (part of the picture) | icon of tiles 2 and 5 |
| 6:15, 4G+, 63 | status bar (ignore) | status bar |

## Data and figures shown
- The three red badges all show "০" (Bangla zero): no pending SR requests at the time of the screenshot. The manual says red badges show pending requests, so the badge number is a count of pending new-outlet, close and correction requests from SRs.
- Badge digits are Bangla digits. No other figures on the screen.

## Colours and style
- Top bar deep purple (indigo-violet, about #4B1A92); page lavender-white.
- Tiles: white, rounded about 8 px, subtle shadow, about 215 px wide on a 738 px screen, three per row with equal gaps.
- Label text: dark blue-purple, regular weight, centred.
- Badges: red circle with white "০", overlapping the tile's top-right corner.
- Icons: pictures (not Material icons). Tiles 1-3 and 4-6 use gradient pink/red to blue/purple illustrations (the same three pictures reused); tile 7 a full-colour red/blue/black outline shop with a pin; tile 8 a monochrome blue shop-cluster. No packshots.
- Section headings plain dark text, no background.

## Navigation and behaviour
- Per the manual: first verify the requests from SRs (top three tiles), then use "Shop operations" for the AMO's own changes.
- Tile 1 opens the list of new shops sent by SRs (screenshot 47, titled "নতুন দোকান"). Tiles 2 and 3 open the close and correction request lists (screens not shown here).
- Tiles 4 to 6 (new shop, permanent close, change information), 7 (update base) and 8 (cluster) work the same as in the SR app, per the manual ("Cluster: works the same as in the SR app").
- Back arrow returns to Home.

## Differences between the image and the manual text
- Manual quotes the heading as "এস আর থেকে আসা অনুরোধ গুলো যাচাই করুন"; the image reads "এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন" (আশা instead of আসা). Copy the image spelling for parity, or flag as a vendor typo.
- Manual heading "দোকান সংক্রান্ত অপারেশন" appears in the image with a trailing "স": "অপারেশন্স".
- The screen's top-bar title "আউটলেট" is not stated in the manual (manual title: "Outlet menu").
- The manual does not describe the tile icons, the empty third slot in the last row, or that the badges currently read ০.

## Open questions for the owner
- Do the badges also appear (and show counts) on tiles 4 to 8? In the image they are only on the top three tiles.
- Should the vendor spelling "আশা" be kept literally, or corrected to "আসা"?
