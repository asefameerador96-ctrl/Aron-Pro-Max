# 13. Printer connected

Source image: `13-printer_connected.jpg`. Manual section 6 (printer pairing), screenshot 13.

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, both the **Stock (স্টক)** screen with the Bluetooth printer connected.
- **Left**: Stock screen with a lime-green toast bar across the middle reading "প্রিন্টার কানেক্ট করা হয়েছে" (Printer connected). The toast is shown right after the connection succeeds.
- **Right**: the same Stock screen a moment later, toast gone, nothing else changed. The printer button at top right is green.
Neither is a system dialog. No status bar is visible (one line: status bars are cropped out on both).

## Layout, top to bottom
1. Top bar: solid deep purple (indigo-purple, flat, no gradient), about 12% of screen height. Left: a white back arrow drawn as a rounded left-pointing triangle outline. Centre: white bold Bangla title "স্টক".
2. A round pale-grey/white circular floating button overlapping the bottom edge of the top bar at the right, containing a **green printer icon**. State: green = printer connected (per the manual a red crossed-out printer icon means not connected).
3. A thin grey horizontal rule under the top bar (crossed by the printer button at the right end).
4. Column header row on a very light lavender/white page: "এসকেইউ" (left, over the pack picture and name), "ইস্যু" (middle, over the quantity stepper), "স্টক" (right, over the stock number). Plain dark text, no background.
5. Scrollable product list. Each SKU is a white rounded card with a faint shadow, laid out in one row:
   - Left: **packshot (pack picture)** about 105 x 150 px, with a small round deep-purple badge at its bottom-right corner holding a white number "০" (zero) (the count already issued/in cart for that pack).
   - Next to it: SKU name in purple bold text.
   - Middle: a stepper: a purple rounded-square button with a white minus "−" on the left, an underlined numeric entry field showing "০" in the centre, a purple rounded-square button with a white plus "+" on the right.
   - Right: the stock figure "০" in dark text.
6. Cards visible, in order (left screen): 
   - MaxR-10S: packshot is a red "MAXIM" pack (red with the gold/white "MAXIM" logo and a pink/brown textured lower half, with small "X Full Flavor" text).
   - MaxR-20S: same red MAXIM pack.
   - MaxB-10S: packshot is a blue "MAXIM" pack (blue with gold "MAXIM" logo, "X BOLT" small text and the same pink/brown lower half).
   - MaxB-20S: same blue MAXIM pack.
   - A fifth card is cut off by the bottom panel: only the top of a beige/cream pack picture with the word "AVON" is visible; its name and numbers are hidden.
   On the left screen the lime toast covers the lower part of the MaxB-10S card (its stepper row is half covered), so the MaxB-10S card is only partly visible.
7. Bottom summary panel, solid **blue** (royal/strong blue, darker than the top bar's purple is not true: it is a distinct blue) pinned above the buttons. Header row in small light text: "ক্যাটাগরি" (left), "মোট ইস্যু" (middle-right), "স্টক" (right). Then four rows with white text: "সিগারেট", "বিড়ি", "লাইটার", "ম্যাচ", each with the total-issue figure "০" in the middle-right column and the stock figure "০" in the right column.
8. Bottom button row on a deep-purple background strip: left a **green** button (lime-to-green vertical gradient, rounded) with a white save (floppy-disk) icon and the white text "সংরক্ষণ"; right a **grey** button (light-to-dark grey vertical gradient, rounded) with a printer icon and the text "প্রিন্ট". Both look enabled; the grey print button is the lighter, less prominent one.
9. Below the buttons: the Android gesture bar (thin white line on black). Page background behind the cards is very light lavender/off-white.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| স্টক | Stock | Top bar title (both screens); also right column header |
| এসকেইউ | SKU | Column header, left |
| ইস্যু | Issue | Column header, middle |
| স্টক | Stock | Column header, right; summary panel header right |
| MaxR-10S | SKU name: Maxim Red, 10 sticks pack | Card 1 |
| MaxR-20S | Maxim Red, 20 sticks pack | Card 2 |
| MaxB-10S | Maxim Bolt (blue), 10 sticks pack | Card 3 |
| MaxB-20S | Maxim Bolt (blue), 20 sticks pack | Card 4 |
| ০ | Zero (Bangla digit zero) | Packshot badge, stepper entry field, stock column, on every card |
| প্রিন্টার কানেক্ট করা হয়েছে | Printer connected | Lime-green toast, left screen only |
| ক্যাটাগরি | Category | Summary panel header, left |
| মোট ইস্যু | Total issue | Summary panel header, middle |
| স্টক | Stock | Summary panel header, right |
| সিগারেট | Cigarette | Summary panel row 1 |
| বিড়ি | Bidi | Summary panel row 2 |
| লাইটার | Lighter | Summary panel row 3 |
| ম্যাচ | Match | Summary panel row 4 |
| ০ | Zero | Summary panel, every total-issue and stock cell (8 cells) |
| সংরক্ষণ | Save | Green button, bottom left |
| প্রিন্ট | Print | Grey button, bottom right |
| + and − | Increase / decrease issue quantity | Stepper buttons on each card |
| AVON | Brand text printed on a pack picture | Partially visible fifth packshot |
| MAXIM | Brand text printed on the pack pictures | Packshots (image text, not UI text) |

All numbers are Bangla-script zero (০) in this image; no Latin digits and no thousand separators appear. The SKU names use Latin letters and Latin digits (10S, 20S).

## Data and figures shown
- Every issue quantity, stock figure and category total is ০ (zero): this is a fresh/test account with nothing issued.
- Per card: stepper = issue quantity for the SKU (editable by minus, plus or typing); right number = the SKU's current stock; the purple badge on the packshot repeats the issue count (zero).
- Summary panel: "মোট ইস্যু" = total issued per category; "স্টক" = total stock per category. Units per the manual: Cigarette and Bidi in sticks, Lighter in pieces, Match in dozens.
- The two screens show identical data; only the toast differs.

## Colours and style
- Top bar: deep purple (about #4A148C / indigo-purple), flat, white title and white back arrow.
- Page background: very light lavender / near white; cards white, rounded corners (~12 px), soft shadow.
- Stepper buttons: purple rounded squares with white glyphs; entry field has a grey underline.
- Packshots: real pack pictures (red MAXIM, blue MAXIM, cream AVON), left of each card, with a purple round count badge at the bottom-right of the picture.
- SKU names: purple bold. Stock and issue numbers: dark grey/black.
- Printer button: round, light-grey fill, green printer icon (connected).
- Toast: lime / yellow-green (about #A4C614) full-width bar with white text, square corners, slight shadow.
- Summary panel: strong blue (about #0D47A1), white text, light-blue small headers.
- Save button: lime-to-green gradient, white text and icon. Print button: grey gradient, dark text and icon.
- Bottom strip behind the buttons: deep purple, same as the top bar.

## Navigation and behaviour
- This screenshot follows section 6 of the manual: the user taps the printer icon at the top right of Stock, Android asks for the Bluetooth permission (screenshot 12), and after the printer pairs the app shows the toast "প্রিন্টার কানেক্ট করা হয়েছে" and the printer icon turns green.
- A red, crossed-out printer icon means not connected (per the manual; not visible in this image).
- The user can then print memos. The save button stores the issue quantities; the print button prints the stock/issue slip on the Bluetooth printer.
- The back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The manual says "Left: the message ... Right: the printer icon turns green." In the image the printer icon is already green on BOTH screens; the right screen simply has the toast gone.
- The manual does not describe the Stock screen layout at all in this section (packshots, steppers, blue summary panel, Save and Print buttons); they are visible in the image. The summary panel category order is Cigarette, Bidi, Lighter, Match.
- The toast wording "প্রিন্টার কানেক্ট করা হয়েছে" matches the manual.

## Open questions for the owner
- The fifth SKU card (AVON pack) is hidden; its SKU name is unreadable. Confirm the full SKU list for the AMO Stock screen (the manual's later Stock sections may show it).
