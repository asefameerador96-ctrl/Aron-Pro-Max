# 18. Control Call: within range

Source image: `18-control_call_within_range.jpg`. Manual section 8, screenshot 18.

## Screens in the image
A single phone screen: the vendor ARON AMO app, "Control Call Activity" in its in-range state (route and outlet chosen, no warning, menu tiles shown). Not a system dialog. Status bar (one line): "Banglalink Banglalink" carrier text, clock 4:51, alarm, location, vibrate and WhatsApp icons, VoLTE 4G signal bars, battery 43%. At the very bottom is a plain grey strip (system navigation area).

## Layout, top to bottom
1. Top bar: solid deep purple, flat, about 15% of screen height (taller than screen 17 because the status bar is inside it). Left: white rounded left-pointing triangle outline back arrow. Centre: white bold Bangla title "কন্ট্রল কল অ্যাক্টিভিটি".
2. Page background: very light lavender/white.
3. Route drop-down: full width, white, rounded, deep-purple 2 px outline, text "Gulshan-1Daily", down-triangle at right.
4. Outlet drop-down: about 85% width, same style, green dot at the left, text "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)" in a smaller font than in screen 17 (shrunk to fit the long text), down-triangle at right.
5. Right of the outlet drop-down: the red "LIVE" badge with blue waves.
6. Alphabet bar: charcoal rounded panel, two rows, white letters. Row 1: selected "All" chip (lime-to-green gradient), A, B, D, E, G, H, I, J, K. Row 2: M, N, R, S, T, U, V, s (lower-case s as displayed).
7. Menu: three equal white tiles side by side in one row (rounded corners, very faint shadow/border, about 205 px wide each), each with an icon above a Bangla label:
   - Left tile: icon of a purple/pink gradient parcel box with a bookmark ribbon on top and a blue clock face overlapping at its bottom-left; label "বিক্রয়".
   - Middle tile: icon of a circular progress ring in a blue-to-pink/red gradient with a purple circle and a check mark in the centre; label in two lines "এসআর পারফ." / "অ্যাসেসমেন্ট".
   - Right tile: icon of a browser/window card in a red-pink to blue gradient (title bar with two dots, a small box and text lines); label "সার্ভে".
   Labels are dark purple text, regular weight. All tiles enabled.
8. Rest of the screen empty light page. The range warning, Manual Override and Refresh buttons are not shown.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| কন্ট্রল কল অ্যাক্টিভিটি | Control Call Activity | Top bar title |
| Gulshan-1Daily | Route name (Gulshan-1 + "Daily") | Route drop-down |
| Masba Store (2689479-01865967033-Jobbar Tower Lake Par) | Outlet name (code 2689479 - phone 01865967033 - cluster "Jobbar Tower Lake Par") | Outlet drop-down |
| All | No letter filter | Alphabet bar, selected |
| A B D E G H I J K | Letter filters | Alphabet bar row 1 |
| M N R S T U V s | Letter filters (last is lower-case s) | Alphabet bar row 2 |
| LIVE | Live location indicator | Right of outlet drop-down |
| বিক্রয় | Sales | Left tile |
| এসআর পারফ. অ্যাসেসমেন্ট | SR Performance Assessment (two lines: "এসআর পারফ." then "অ্যাসেসমেন্ট") | Middle tile |
| সার্ভে | Survey | Right tile |
| 4:51, 43% | Clock, battery | Status bar |

Digits are all Latin (2689479, 01865967033, 4:51, 43%, "1" in Gulshan-1). No Bangla digits.

## Data and figures shown
- Outlet code 2689479, phone 01865967033 (11-digit Bangladesh mobile with leading 0), cluster "Jobbar Tower Lake Par".
- No sales or target figures.

## Colours and style
- Top bar deep purple (about #4B1A9A), flat; white title.
- Page very light lavender; tiles white with faint rounded border/shadow; labels dark purple (#3B1D7A-ish).
- Tile icons: flat vector icons with gradient fills going from red/pink to purple/blue (the "gradient red-to-purple icon" style of the existing Aron).
- Alphabet bar charcoal (#555), "All" chip lime-green gradient.
- No packshots.

## Navigation and behaviour
- Per the manual: no range warning, so the Control Call menu appears directly; "Tap the activity you want to do at this outlet."
- বিক্রয় (Sales) opens the sales screen for the outlet (manual screenshot 23: sales screen; if outside the geo-fence the force-sale reason dialog appears).
- এসআর পারফ. অ্যাসেসমেন্ট opens the SR Performance Assessment for this outlet (manual sections for screenshots 27 and 28).
- সার্ভে opens the outlet survey.
- Back arrow returns to the previous screen. Changing route/outlet via the drop-downs re-evaluates the range.

## Differences between the image and the manual text
- The manual does not describe tile icons, the green dot, the alphabet bar letters or the long cluster text.
- Manual says "route and outlet selected, with the LIVE location icon": matches.
- The outlet field uses a smaller font here than in screen 17 (auto-shrink for long text).

## Open questions for the owner
- none
