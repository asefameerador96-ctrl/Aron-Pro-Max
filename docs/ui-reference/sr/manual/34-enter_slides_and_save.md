# 34. Enter slides and save

## Screens in the image
Two phone screens of the স্লাইড (Slide) screen. Left: dimmed list with a quantity pop-up open over it. Right: the list after saving, showing the entered count. Same outlet as screenshot 33.

## Layout, top to bottom
Left screen (dimmed by a dark overlay):
1. Top bar gradient (dimmed) with back chevron and title "স্লাইড". Outlet heading, route and cluster lines, purple table header (all dimmed, same as screenshot 33).
2. A centred pale lilac (about #ECE6F0) rounded dialog:
   - Row 1: Maxim pack picture at left with a small blue round badge with a white "০" on its lower-right corner; the SKU name in blue wrapped on two lines "MaxR-10" / "S"; a dark-green "−", the number "10" in dark text (Latin digits), a dark-green "+". A red "X" at the top-right corner of the dialog.
   - A darker lilac panel with ten teal (about #4DAA9A) rounded buttons in two columns (left positive, right negative), five rows.
   - A gradient (red to purple) rounded button "সংরক্ষণ", centred.
3. The bottom "জমা দিন" button is visible, dimmed.

Right screen (normal): same layout as screenshot 33 left: purple-gradient top bar "স্লাইড", outlet heading, table header, two rows with OFFER ribbons, and the gradient "জমা দিন" button at the bottom. Row 1 stepper shows "১০", row 2 shows "০".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| স্লাইড | Slide | Top bar, both screens |
| Savar Metro (1618409) | Outlet name and code | Heading |
| রুট: Savar BazarDaily | Route | Grey line |
| ক্লাস্টার: Madrasa Road | Cluster | Grey line |
| এসকেইউ | SKU | Table header |
| খালি প্যাকেট | Empty packets | Table header |
| MaxR-10 S (wrapped "MaxR-10" / "S") | SKU MaxR-10S | Dialog, blue |
| 10 | Current quantity, Latin digits | Dialog, between − and + |
| ০ (on the blue badge) | Zero, probably the previous or saved count | Badge on pack picture |
| ১ , ৫ , ১০ , ২০ , ৫০ | Quick-add buttons: 1, 5, 10, 20, 50 | Dialog, left column |
| -১ , -৫ , -১০ , -২০ , -৫০ | Quick-subtract buttons: -1, -5, -10, -20, -50 | Dialog, right column |
| সংরক্ষণ | Save | Dialog button |
| MaxR-10S | SKU | List row 1 |
| MaxR-20S | SKU | List row 2 |
| ১০ | Quantity 10 in Bangla digits | List row 1 stepper (right screen) |
| ০ | Zero | List row 2 stepper |
| OFFER | Offer tag | Row ribbons |
| জমা দিন | Submit | Bottom button |

## Data and figures shown
- Quick-entry pop-up shows 10 (Latin digits) for MaxR-10S; after saving, the list shows ১০ (Bangla digits). So the pop-up uses Latin digits and the list uses Bangla digits.
- The quick buttons are ±1, ±5, ±10, ±20, ±50 (Bangla digits). The manual says "quick buttons (1, 5, 10, 20, 50)"; the negatives are also present.
- The unit of the number is "empty packets" (খালি প্যাকেট); no stick count is shown.
- MaxR-20S remains ০.

## Colours and style
- Dialog: pale lilac, rounded corners (about 28 dp). Quick buttons: teal-green with white text. Save button: red-to-purple gradient. Close "X": red. SKU name in dialog: blue (about #2E8BD8); plus/minus in dialog dark green. Blue badge: about #2196F3.
- Background list and top bar as in screenshot 33. Packshots shown in both the dialog and the list.

## Navigation and behaviour
- Manual: the pop-up has quick buttons (1, 5, 10, 20, 50); the quantity shows on the list. The SR enters the empty packs collected and taps জমা দিন (Submit).
- Image: tapping a count opens this pop-up; the buttons add or subtract; সংরক্ষণ saves the value back to the list row (right screen shows ১০); the red X closes without saving (inferred). Final জমা দিন at the bottom of the list submits.

## Differences between the image and the manual text
- The manual says the SR taps জমা দিন in the pop-up flow; the pop-up button is actually "সংরক্ষণ" (Save). জমা দিন is on the list screen.
- The manual lists quick buttons 1, 5, 10, 20, 50 only; the image also has the negative versions.
- The manual does not mention the blue "০" badge on the pack picture; its meaning is not explained.

## Open questions for the owner
- What is the small blue "০" badge on the pack picture in the pop-up (previous count, stock, or offer count)?
- Latin digits in the pop-up but Bangla digits in the list: is this mixing intended to be kept?
