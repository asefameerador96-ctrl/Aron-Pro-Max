# 23. Sales screen and force-sale reason

## Screens in the image
- Left: vendor app "বিক্রয়" (Sales) screen for a chosen outlet, product list (SKU cards) scrolled to the top.
- Right: the same Sales screen with a bottom-sheet/dialog over a dim lower area asking the force-sale reason (outside the geo-fence). Vendor app, not a system dialog. Status bar (both): Banglalink, 4:52, alarm, location, 4G, battery 43% (one line only).

## Layout, top to bottom
Left screen:
1. Top bar: solid deep purple, back arrow (rounded outline chevron, white) at left, centred white bold title "বিক্রয়".
2. Light grey strip (page background behind the header card).
3. Outlet header card: white/light card with grey rounded outline (disabled/read-only look). At left a colourful shop-front icon (awning yellow/green/red, blue door). Right of it two lines: bold "খুচরা বিক্রেতাঃ" followed by grey text "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)" wrapping to 3 lines; below it bold "রুট:" followed by grey "Gulshan-1Daily".
4. "Sale History" button: full-width, pale green-grey fill with green outline, rounded; a clock-with-counter-clockwise-arrow icon at left of the centred text "Sale History" (English, black).
5. Product list: white rounded cards separated by light grey gaps, each about 300 px tall, one per SKU. Each card, left to right: packshot (pack picture) of the SKU at far left (about 85 x 140 px) with a round blue badge (white "০") overlapping its bottom-right corner; SKU name in purple bold (e.g. "Avon-20S", "ARIS-A-20s" wrapped on two lines as "ARIS-" / "A-20s", "BDDF-10s"); a green round "minus" button; a quantity box with underline showing "০"; a green round "plus" button; at the right a three-line column: line 1 a blue cart icon + blue bold "১০০০" (rate), line 2 a dark floppy/save-like icon + "০%" (discount), line 3 a dark archive-box icon + "০" (stock).
6. Cards visible: Avon-20S, ARIS-A-20s, BDDF-10s, and a fourth card cut off at the bottom showing only the top of a packshot and "২০০০" with a cart icon (SKU name not visible).
7. No bottom total bar is visible in this crop (list continues below).
Right screen:
1. Same top bar, outlet header card, and "Sale History" button as left (the layout is slightly higher: header card text lines wrap as "খুচরা বিক্রেতাঃ Masba Store" / "(2689479-01865967033-Jobbar" / "Tower Lake Par)" and "রুট: Gulshan-1Daily").
2. Below, a pale lavender dialog area: red warning triangle icon with white exclamation mark, centred.
3. Centred question in blue text: "আপনার ফোর্স সেলের কারণ কী?".
4. Two wide buttons, stacked, full width, rounded: gradient dark blue (top) to purple (bottom), white bold text: "ইন্টারনেট সমস্যা" then "লোকেশন চেঞ্জ".
5. At the bottom, a dim grey band (the dialog's scrim edge).

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| বিক্রয় | Sales | Top bar title (both) |
| খুচরা বিক্রেতাঃ | Retailer: | Outlet header card, bold label (both) |
| Masba Store (2689479-01865967033-Jobbar Tower Lake Par) | Outlet name (code-phone-area) | Header card, grey text (both) |
| রুট: | Route: | Header card, bold label (both) |
| Gulshan-1Daily | Route name | Header card (both) |
| Sale History | Sale History (English even though the app is Bangla) | Button under header (both) |
| Avon-20S | SKU: Avon, 20s pack | Left, card 1 name |
| ARIS-A-20s (shown as "ARIS-" and "A-20s") | SKU: ARIS-A, 20s pack | Left, card 2 name |
| BDDF-10s | SKU: BDDF, 10s pack | Left, card 3 name |
| ০ (in blue circle on packshot) | Number badge, zero (meaning not stated) | Left, bottom-right of each packshot (cards 1-3) |
| ০ (in quantity box) | Quantity entered, zero | Left, each card |
| ১০০০ | Rate shown with cart icon (manual: rate per 1,000 sticks) | Left, cards 1-3, line 1 |
| ২০০০ | Rate for fourth SKU (cut off) | Left, card 4, line 1 |
| ০% | Discount 0% | Left, each card, line 2 |
| ০ (beside box icon) | Stock, zero | Left, each card, line 3 |
| আপনার ফোর্স সেলের কারণ কী? | What is the reason for your force sale? | Right, dialog question |
| ইন্টারনেট সমস্যা | Internet problem | Right, button 1 |
| লোকেশন চেঞ্জ | Location change | Right, button 2 |
Digits are Bangla (০১২৩...) throughout the SKU cards: "১০০০", "২০০০", "০", "০%". Header-card codes are Latin digits. No thousand separators; no "টাকা" or unit label next to the rate (only the cart icon).

## Data and figures shown
- Outlet: Masba Store, code 2689479, phone 01865967033, area Jobbar Tower Lake Par. Route Gulshan-1Daily.
- Per SKU: rate ১০০০ (Avon-20S, ARIS-A-20s, BDDF-10s) and ২০০০ (fourth SKU) per manual "rate per 1,000 sticks"; discount ০%; stock ০; quantity ০ (nothing entered yet).
- Stock reads 0 for all visible SKUs (test data).

## Colours and style
- Top bar: deep purple. Page background: light grey behind header, pale lavender/white elsewhere.
- Header card: white with grey outline, rounded; labels bold black, values grey.
- "Sale History" button: pale green fill (#D0E0D8-like) with green (#2E9E3E-like) outline, rounded.
- SKU cards: white, rounded (~16 px), soft shadow. SKU names purple bold. Quantity "-" and "+" buttons: green gradient rounded squares/circles with white symbols. Rate (cart icon + figure): blue (#0B4A9E-like) bold. Discount and stock icons dark grey/black, figures black.
- Packshots: yes, a real pack picture for every SKU at the card's left (Avon cream/brown pack with a muscular-torso photo, ARIS green ornamental pack with a bearded face, BDDF "Black Diamond" red/black pack), with a blue circular badge overlapping.
- Force-sale dialog: red warning triangle; question text blue; buttons blue-to-purple gradient with white text.

## Navigation and behaviour
- Per manual: choose the outlet, then the Sales screen opens. If the AMO is outside the outlet's geo-fence the app asks for the force-sale reason: ইন্টারনেট সমস্যা (Internet problem) or লোকেশন চেঞ্জ (Location change). The sale is then recorded as a Force Sale with that reason.
- Choose লোকেশন চেঞ্জ only if the outlet has really moved; its base location should then be updated (manual section 18.7).
- Sale History shows what the outlet bought before.
- "-" / "+" change the SKU quantity.

## Differences between the image and the manual text
- The manual calls the right-hand elements "discount % and stock"; the image shows icons (floppy-like icon for discount, archive-box icon for stock) and a blue number badge on each packshot that the manual does not mention.
- The manual says "the outlet (retailer: name, code, phone, cluster)": the card shows name, code, phone and an area name (e.g. "Jobbar Tower Lake Par"), no separate "cluster" label.
- The manual names the Sale History button in English as Sale History; the image shows it in English ("Sale History") while the app is otherwise Bangla.
- Packshots, the red warning triangle and the dim lower band are not mentioned in the manual.

## Open questions for the owner
- What does the blue numbered badge on each packshot show (quantity in cart, stock, pack count)?
- Is the left screen's rate figure per 1,000 sticks for all SKUs, including the 10s packs?
