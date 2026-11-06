# 45. Today's sales summary

## Screens in the image
Single screen (portrait phone, 738 px wide). It is the vendor ARON AMO app screen "বিক্রয় সারসংক্ষেপ" (Sales summary), shown in the empty state (no sales yet). Not a system dialog. Status bar (one line): 6:14, location pin, 4G+, signal, battery 63.

## Layout, top to bottom
1. Top bar, solid deep purple (indigo-violet), full width. Left: a back arrow (white, rounded left-pointing triangle outline). Centre: white bold Bangla title "বিক্রয় সারসংক্ষেপ".
2. Top-right, overlapping the bottom edge of the top bar: a round light-grey button (circle) holding a red printer icon with a diagonal slash through it (printer off / not connected). It looks disabled or indicates "printer not connected". It is not mentioned in the manual.
3. Page background: very light lavender-white (almost white with a pink-violet tint).
4. Info box: white rounded rectangle with a thin grey outline, full width with margins. Inside, left: a dark-grey filled round "i" info icon; then the text "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে" in dark grey, single line.
5. Centred plain dark text below the box: "কিছু পাওয়া যায়নি" (empty-state message).
6. A tab-like label (chip) at the left edge, green gradient (yellow-green to green) with rounded top corners, white text "মোট বিক্রয়". It acts as the heading of the table below.
7. Table header bar: dark charcoal grey gradient (darker at right), full width, rounded top-right corner. One column header only, right-aligned, white text "মূল্য".
8. Table row 1 (white): left label "মোট" in dark grey small text; right value "০.০০" in muted red.
9. Table row 2 (white): left label "ডিসকাউন্ট" in red; right value "(-) ০.০০" in red.
10. Table footer row, light grey background (rounded bottom corners): left bold black "সর্ব মোট"; right bold black "০.০০".
11. Wide button, full width with side margins, rounded corners, blue-to-purple gradient (blue at top-left, deep purple at bottom-right). White printer icon, then white text "প্রিন্ট", centred. Appears enabled in the image.
12. Rest of the page below is empty. At the very bottom, the Android gesture bar (grey pill).

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | where it appears |
|---|---|---|
| বিক্রয় সারসংক্ষেপ | Sales summary | top bar title |
| আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে | Your whole day's sales details will be here | info box |
| কিছু পাওয়া যায়নি | Nothing found (no sales yet) | centred empty-state text |
| মোট বিক্রয় | Total sales | green tab/chip above table |
| মূল্য | Value / price | table column header (right) |
| মোট | Total | table row 1 label |
| ০.০০ | 0.00 (Bangla digits) | row 1 value, red |
| ডিসকাউন্ট | Discount | table row 2 label, red |
| (-) ০.০০ | minus 0.00 discount (Bangla digits, "(-)" prefix) | row 2 value, red |
| সর্ব মোট | Grand total | footer row label, bold |
| ০.০০ | 0.00 | footer row value, bold |
| প্রিন্ট | Print | button label |
| 6:14, 4G+, 63 | status bar clock, network, battery % | status bar (ignore) |

## Data and figures shown
- All three amounts are zero because no sale has happened: Total 0.00, Discount (-) 0.00, Grand total 0.00.
- The figures are in Bangla digits (০.০০), with a full stop as decimal separator and two decimals. No currency symbol or unit is shown. No thousand separator is visible (value is zero).
- Discount is displayed with a "(-)" prefix and in red, i.e. as a deduction. Grand total = Total minus Discount.
- Only a single "মূল্য" (value) column exists; there is no quantity column in this empty state.

## Colours and style
- Top bar: deep purple (about #4B1A92 indigo-violet), solid, no red gradient visible on this screen.
- Page background: near-white lavender (#FBF5FF-like).
- Info box: white, 8 px rounded, 1 px grey border, no shadow.
- Green tab "মোট বিক্রয়": yellow-green to green vertical gradient, white text, rounded top corners.
- Table header: charcoal grey (#333 to #444) gradient with white text.
- Row 1 value muted red; discount label and value red; grand total row light grey (#EEE) with black bold text.
- Print button: blue (#1565C0-like) to purple (#4A1A92-like) diagonal gradient, white icon and text, rounded corners (about 6 px).
- Printer-off icon: red icon on a light-grey circle.
- No packshots or pictures on this screen.

## Navigation and behaviour
- Back arrow returns to the previous screen (Home).
- Per the manual: at the end of the day the AMO opens the Summary and prints it if needed. The screen shows the day's total sales, value, discount and grand total, with a প্রিন্ট button.
- "কিছু পাওয়া যায়নি" means there are no sales yet.
- The red slashed printer icon (top right) presumably shows the Bluetooth printer is not connected; not described in the manual.

## Differences between the image and the manual text
- The manual's title is "Today's sales summary"; the screen title is "বিক্রয় সারসংক্ষেপ" (Sales summary), which Annex A lists as সারসংক্ষেপ.
- The slashed red printer icon at top right is not mentioned in the manual.
- The info box text is quoted by the manual in English only; its Bangla is as in the table above.
- The manual says the screen shows "total sales, value, discount and grand total"; the image shows these as: a green "মোট বিক্রয়" tab, a "মূল্য" column, rows "মোট", "ডিসকাউন্ট" and "সর্ব মোট".

## Open questions for the owner
- What exactly does the red slashed printer icon show (printer not paired, or print disabled while the list is empty)? Does it become a normal printer icon when paired?
- With real sales, what columns does the table show (per SKU rows with quantity and value, or only totals)? The image shows only the empty state.
