# 30. Select the outlet

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app (Joint Call activity), from two different phones/sessions.
- LEFT: Joint Call with route and outlet chosen, but the AMO is not near the outlet: warning "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" with two buttons. Vendor app. Status bar: 5:09, 0.52 KB/s, 4G, battery 58; black Android navigation bar at the bottom.
- RIGHT: Joint Call with route and outlet chosen and within range: the tile "এসআর কল অ্যাসেসমেন্ট". Vendor app. Different phone: status bar shows two carrier lines "Banglalink Banglalink", time 4:50, alarm, location, vibrate, WhatsApp icons, VoLTE 2, 4G, signal, battery 43%. The bottom edge is a plain grey strip (no navigation bar).

## Layout, top to bottom
LEFT screen:
1. Status bar (see above).
2. Top bar: deep purple, white back arrow at left, white bold centred title "জয়েন্ট কল একটিভিটি".
3. Route drop-down field: white rounded rectangle with deep purple outline, text "DariapurDaily" (black), down arrow at right.
4. Outlet row: white rounded outlined drop-down (deep purple outline) containing a bright green filled dot at the left, then the outlet text "Basahar Store (2654199-123-Tatul Tola)", down arrow at right. To the right of this field (outside it) a "LIVE" icon: red-outlined bold word LIVE with blue/teal radio-wave arcs (live location indicator).
5. Alphabet filter bar: dark grey (about #555) rounded rectangle, two rows of letters in white: row 1 "All" (selected: lime-green gradient rounded tile with white text), A, B, D, E, F, H, J, K, M, N; row 2 (centred) P, R, S, T, U, V, m.
6. Large empty space.
7. Warning block, centred: red warning triangle icon (red triangle with white exclamation mark) and below it blue Bangla message "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।".
8. Button 1: full-width rounded rectangle, blue-to-purple gradient (deep blue at left shading to indigo), white bold text "ম্যানুয়াল ওভাররাইড". Enabled.
9. Button 2: full-width rounded rectangle, dark grey/charcoal gradient, white bold text "রিফ্রেশ". Enabled.
10. Empty page below, then black Android navigation bar.
RIGHT screen:
1. Status bar (two-line carrier).
2. Top bar: deep purple, white back arrow, title "জয়েন্ট কল একটিভিটি".
3. Route field: "Gulshan-1Daily", down arrow.
4. Outlet field: green dot, smaller text "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)", down arrow; LIVE icon at its right.
5. Alphabet bar: dark grey; row 1: All (selected, lime-green), A, B, D, E, G, H, I, J, K, M; row 2 (centred): N, R, S, T, U, V, s.
6. One tile (left-aligned under the bar, about 1/5 screen width): white card with very light grey border and very slight shadow, rounded corners; icon on top: a person in a suit/tie (purple and pink figure) with a phone handset bubble and a blue circular tick badge and curved arrows (flat illustration, pink/purple/blue); under it two lines of grey Bangla text "এসআর কল" / "অ্যাসেসমেন্ট".
7. Rest of page empty (light pink-white); bottom grey strip.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| জয়েন্ট কল একটিভিটি | Joint Call activity | Top bar title (both) |
| DariapurDaily | Route (daily) | Left, route field |
| Basahar Store (2654199-123-Tatul Tola) | Outlet: name (outlet code-mobile or id-address) | Left, outlet field |
| Gulshan-1Daily | Route (daily) | Right, route field |
| Masba Store (2689479-01865967033-Jobbar Tower Lake Par) | Outlet: name (code-mobile number-address) | Right, outlet field |
| LIVE | Live location indicator | Right of outlet field (both), red text with blue waves |
| All | All outlets filter | Alphabet bar, selected tile (both) |
| A B D E F H J K M N / P R S T U V m | First-letter filters (left) | Left alphabet bar (last item is lowercase "m") |
| A B D E G H I J K M / N R S T U V s | First-letter filters (right) | Right alphabet bar (last item lowercase "s") |
| AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই। | AMO and retailer are not within range. | Left, warning message (ends with Bangla danda) |
| ম্যানুয়াল ওভাররাইড | Manual override | Left, blue-purple button |
| রিফ্রেশ | Refresh | Left, dark grey button |
| এসআর কল অ্যাসেসমেন্ট | SR Call Assessment | Right, tile label (two lines: "এসআর কল" / "অ্যাসেসমেন্ট") |
| 5:09, 0.52 KB/s, 58 / 4:50, 43%, Banglalink, VoLTE | Status bar | Status bars (Latin digits) |
Outlet text format seen: "<Store name> (<outlet id>-<mobile or sub-id>-<address>)". Examples: 2654199-123-Tatul Tola; 2689479-01865967033-Jobbar Tower Lake Par. All digits Latin; no thousand separators. The letters on the alphabet bar are the initial letters of the route's outlets; the trailing lowercase item (m, s) is probably an outlet whose name starts with a lowercase letter.

## Data and figures shown
- Outlet identifiers (2654199, 2689479) are outlet codes; 01865967033 is a Bangladeshi mobile number in the outlet label. No totals or targets on this screen.
- LEFT: AMO outside the allowed radius of the outlet. RIGHT: inside, so assessment is possible.

## Colours and style
- Top bar deep purple; page very light pink-white.
- Drop-downs: white, rounded, 2 px deep purple border; green dot (about #22CC22) marks the outlet as active/within-list.
- Alphabet bar: dark grey (#505050), rounded, white letters; "All" selected as lime-green (yellow-green to green gradient) rounded tile.
- LIVE icon: red LIVE lettering, blue signal waves.
- Warning: red triangle, blue (#1565C0) Bangla text.
- Manual override: blue-to-indigo gradient button, white text. Refresh: charcoal gradient button, white text. Both full width with rounded corners (about 10 px).
- Tile: white card, rounded, thin light border, flat illustrative icon in pink, purple, blue (not a red-to-purple gradient); label text grey.
- No packshots.

## Navigation and behaviour
- Per the manual: select the outlet where the SR is calling. If the AMO is not near the outlet, the warning appears with Manual Override and Refresh (as in Control Call: Refresh re-reads the location; Manual Override opens the camera for an outlet photo, which also updates the outlet's location info, then continues).
- When in range, the tile "এসআর কল অ্যাসেসমেন্ট" appears; tapping it opens the SR Call Assessment (screenshot 31).
- The alphabet bar filters the outlet drop-down by first letter; All shows all.
- The back arrow returns to the previous screen.

## Differences between the image and the manual text
- The manual does not mention the green dot in the outlet field, the LIVE icon (it only says LIVE in other sections), or the alphabet filter bar for this screen.
- Left and right come from two different phones (status bars differ), and the right screen has no navigation bar.
- The warning text in the image ends with a danda "।", the manual's Annex A has it without.
- The text size of the outlet field is smaller on the right screen because the label is longer.

## Open questions for the owner
- What is the allowed radius (metres) for "within range" for the AMO at a Joint Call outlet? Not shown.
- Is the last alphabet item (lowercase m / s) intentional (outlets starting with lowercase) or a bug to reproduce?
