# UI parity vision QA, 2026-10-19 00:05 UTC (lead)

Eight independent vision reviewers compared each lane's parity evidence image with the owner's reference screenshot
(`docs/ui-reference/sr/*`) or, for AMO/TSO/web, with the written reference; a second reviewer re-checked every
high-severity claim (REAL / OVERSTATED / WRONG shown in brackets; no claim was WRONG). Source images: the evidence on
lane heads ui-sr-a fda4ed67, ui-sr-b b53e422f, ui-packshots 564c5c3e, ui-amo f6adde08, ui-tso 810254ac, web-dashboard e5c01fdd.

Read this before you change anything:
- Most size findings compare ratios across different render widths (built: 360 dp at 1.5x; reference phone: about 411 dp).
  Re-measure at **411 dp** (the kit's reference width) before changing a token; where the kit's own measurement at 411 dp
  disagrees with a finding below, the 411 dp measurement wins and you note it in your parity file.
- Wording, labels, colours, element presence/absence, order and number formats are width-independent: those are real.
- Money correctness findings (a figure under the wrong label) are bugs, not looks.
- Each lane owns the items tagged with its name; items tagged ui-kit go into the kit window (see lane-sessions.md).


##### SR Home
VERDICT: Top half (gradient header, white 4-column tile grid, redrawn gradient icons, Bangla labels, red-to-purple progress card) reads as the existing Aron, but below the grid the owner would say it is not their app: KPI strip, the gradient sales panel and the discount panel are replaced by white shadowed cards, and extra sync, battery and sun-toggle rows appear.

- (high, ui-sr-a) KPI strip (six outlined tiles) [REAL: The structure is visible as stated. home-2-kpis shows six white tiles in a 4-column grid: ৬/৬০ আউটলেট ভ্রমণ, ১০% স্ট্রাইক রেট, ৮০০ ইস্যু, ৪১৯ বর্তমান স্টক, ৫৪ নন ভিজিট, ০ বিক্রি নেই. Each has a thin p]
    ref: home-2-kpis: six KPI cards in a 4-column grid (৬/৬০ আউটলেট ভ্রমণ, ১০% স্ট্রাইক রেট, ৮০০ ইস্যু, ৪১৯ বর্তমান স্টক / ৫৪ নন ভিজিট, ০ বিক্রি নেই), each a white card with a 1 dp indigo-purple outline and 12 dp radius, large black Bengali numeral (about 28 sp) over a small indigo label, sitting inside a pale lavender rounded container.
    built: Only three tiles in one row (১০%, ৫৪, ০): white cards with soft drop shadow and no outline, bold purple numerals about 22 sp, grey labels. আউটলেট ভ্রমণ moved into a plain text line above the progress card; ইস্যু and বর্তমান স্টক replaced by a separate white 'স্টক' card (Cigarette / Lighter / Match with units) that does not exist in the reference.
    fix: Render all six KPI tiles in the reference style: 4-column grid, white card, 1 dp #5C3DA8-ish indigo outline, 12 dp radius, no shadow, black ~28 sp Bengali numeral, indigo 12 sp label, inside a #F7F4FA rounded container; show ৬/৬০ আউটলেট ভ্রমণ, ১০% স্ট্রাইক রেট, ৮০০ ইস্যু, ৪১৯ বর্তমান স্টক, ৫৪ নন ভিজিট, ০ বিক্রি নেই. Drop the extra per-category Stoc
- (high, ui-sr-a) Sales summary panel (style and structure) [REAL: Every point is visible. The reference is a vertical gradient panel (sampled top #D2474B to bottom #4F1E95, matching the claimed #D5484A to #4B1C97) with white text. It has the headline 'বিক্রয় (স্টিক]
    ref: A vertical red-to-purple gradient panel with white text: headline 'বিক্রয় (স্টিক)' with a big ০ on the left; on the right rows ম্যাচ ১৪১.০০, লাইটার ৪,৬৮৭.৫০, মোট ৪,৮২৮.৫০, ডিসকাউন্ট - ৪৩৭.৫০, QC - ০.০০, a thin white rule, then সর্বমোট ৪,৩৯১.০০ larger. Category names in Bangla, no ৳ on the rows.
    built: A plain white shadowed card titled 'আজকের বিক্রয়' with a small purple icon, dark text, rows 'Match' and 'Lighter' in Latin script, a ৳ suffix on every row (with a space), a Unicode minus on the deduction row, a purple bold total; no 'বিক্রয় (স্টিক)' headline and no ০ stick count, no rule above the total.
    fix: Rebuild as the reference panel: vertical gradient #D5484A to #4B1C97, radius 16 dp, white text; left block 'বিক্রয় (স্টিক)' with a smaller '(স্টিক)' and the stick count below; right block with Bangla labels ম্যাচ, লাইটার, মোট, ডিসকাউন্ট, QC, a 1 dp white rule, then সর্বমোট in 22 sp; drop the ৳ suffix on these rows and use the spaced hyphen '- ৪৩৭.
- (high, ui-sr-a) Sales summary deduction rows (wrong label on a money figure) [REAL: The built panel has one deduction row, 'QC −৪৩৭.৫০ ৳', and no ডিসকাউন্ট row. The reference has ডিসকাউন্ট - ৪৩৭.৫০ and QC - ০.০০, and 4,828.50 − 437.50 − 0.00 = 4,391.00. The reference's lower panel co]
    ref: Two separate rows: ডিসকাউন্ট - ৪৩৭.৫০ and QC - ০.০০, so 4,828.50 - 437.50 - 0.00 = 4,391.00.
    built: A single deduction row labelled 'QC' showing −৪৩৭.৫০ ৳; the ডিসকাউন্ট row is absent and the QC value is wrong (reads 437.50 instead of 0.00).
    fix: Show both rows with the reference labels and values: ডিসকাউন্ট (offer discount; zero while docs/27 defers programmes) and QC (QC deduction). Bind each row to its own memo field so the discount value can never appear under the QC label.
- (medium, ui-sr-a) Second summary panel (discount, DRP, QC, totals)
    ref: A purple gradient panel (vertical, softer purple to mauve) with three columns মোট ডিসকাউন্ট ৪৩৭.৫০ / DRP ডিসকাউন্ট ০.০০ / মোট QC ০.০০, a white rule, 'সর্ব মোট' ৪,৮২৮.৫০৳ in a very large size, and 'মোট টাকা' ৪,৩৯১.০০৳, below the first panel.
    built: No such panel anywhere on Home.
    fix: Add the panel below the sales panel with the reference labels (মোট ডিসকাউন্ট, DRP ডিসকাউন্ট, মোট QC, সর্ব মোট, মোট টাকা); discount cells show ০.০০ while docs/27 defers programmes; gross and net lines carry real values with an attached ৳ (no space).
- (medium, ui-sr-a) Extra status rows not in the reference
    ref: Between the tile grid and the cards there is nothing but the progress card; at the bottom only the vendor credit line (dropped by design). No sync line, no sun icon, no battery or data text.
    built: A 'চেক ইন ০৮:৫২ · আউটলেট ভ্রমণ ৬/৬০' line under the grid, a 'সিঙ্ক হয়েছে ১০:৪২' row with a sun (Sunlight) toggle, and a footer 'ব্যাটারি ৬২% · খালি ১,২০০ এমবি · ৩ টি আপলোডের অপেক্ষায় · সর্বশেষ সিঙ্ক ১২ মিনিট আগে'.
    fix: Remove all three from Home. Move the Sunlight toggle into the Settings gear screen (spec: optional theme from the settings gear), the sync, battery, data and pending-upload text into Settings or a diagnostics screen, and the check-in time onto the Attendance screen.
- (medium, ui-kit) Home header size and wrapping
    ref: Title 'SR - Testing Banani (sr334001)' on one line in regular/medium weight, subtitle on one line; avatar about 9 percent of screen width, gear about 7 percent; header band about 21 to 25 percent of screen width tall.
    built: Title is bold and wraps to two lines ('SR - Testing Banani' / '(sr334001)'), subtitle wraps to two lines, avatar about 15 percent and gear about 9 percent of width, header band about 31 percent of width (roughly 1.3 to 1.45 times taller at equal screen width).
    fix: AronHomeHeader: title one line (maxLines 1, shrink to fit), 14 to 15 sp Medium instead of bold, subtitle 12 sp one line, avatar 40 dp and gear 28 dp, vertical padding so the band is about 88 dp on a 411 dp screen.
- (low, ui-sr-a) Header subtitle wording and digits
    ref: 'Apsis RouteDaily, 2026-10-05': route name, comma, date in Latin digits, nothing else.
    built: 'Apsis RouteDaily' then a second line '২০২৬-১০-০৫ · Daily' with Bengali digits and an extra '· Daily' suffix.
    fix: Format the subtitle as '<route name>, <yyyy-MM-dd>' with ASCII digits, as in docs/ui-reference/sr/home.md; remove the '· Daily' suffix.
- (medium, ui-kit) Tile labels (size, weight, wrapping)
    ref: Labels about half the tile width for the longest word, semi-bold deep indigo, every label on one line on a shared baseline (including টাস্ক ডেলিগেশন), plenty of air to the tile edge.
    built: Labels about 1.5 times larger relative to the tile, regular weight in a greyer indigo; 'টিউটোরিয়াল' and 'অ্যাটেনডেন্স' nearly touch the tile edges; 'টাস্ক ডেলিগেশন' wraps to two lines so its icon and label sit out of line with the rest of the row.
    fix: AronTile label: about 12 sp (reference proportion) with Medium/SemiBold weight, colour #3F2D9B, maxLines 1 with auto-shrink to a floor, fixed label slot height so icons and baselines align across the row.
- (medium, ui-sr-a) Photo Capture tile missing
    ref: Row 3, column 4: a camera-with-photographer icon and the English label 'Photo Capture' (the only English tile label).
    built: No Photo Capture tile; with Astha and Loyalty hidden, the grid ends in an incomplete third row of three tiles (Task Delegation, Sales Journey, KPI) instead of Task Delegation, Photo Capture, Sales Journey, KPI.
    fix: Add the Photo Capture tile (redrawn camera icon, label exactly 'Photo Capture') after টাস্ক ডেলিগেশন, wired to the photo-capture feature; it is in scope (not a docs/27 deferral).
- (low, ui-kit) Tile geometry
    ref: Tiles about 0.87 wide to 1 tall, outer margin about 1.8 percent of width, gap about 1.2 percent.
    built: Tiles about 0.70 wide to 1 tall (taller, narrower), outer margin about 3.5 percent, gap about 1.9 percent, corners look noticeably rounder. Corner radius in dp: cannot tell.
    fix: AronTileGrid: tile aspect about 0.87 (w:h), outer padding 8 dp, gap 5 dp, radius 8 to 10 dp unless the owner confirms 12 dp.

##### SR Stock and pack pictures
VERDICT: Not yet: the parity-shell Stock (sr-a-stock.png) matches the reference on colours, Bangla wording, column layout and totals figures, but it shows no real pack pictures (grey silhouettes on every row) and its controls are oversized. The packshots-stock-sale.png evidence still shows Stock and Sale in the old pastel Calm Glass shell, so the owner would not say either one looks like the existing Aron.

- (high, ui-packshots) Pack pictures (packshots) on every Stock row [OVERSTATED: The claim that sr-a-stock.png shows no pack art is wrong for the left panel. There, MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S and MaxWB-10S all show real full-colour MAXIM art (red, blue, green) with the]
    ref: Real full-colour MAXIM art on every row (red MaxR, blue MaxB, green MaxWB) with the pictorial warning. The art is about 12.7 percent of screen width and about 92 percent of the card height.
    built: sr-a-stock.png shows no pack art. Every row has a flat grey tile with the same generic box-with-two-tabs silhouette; MaxR 10S and Prime Gold 10S look identical. The art exists: packshots-stock-sale.png renders real MaxR/MaxB art in the older shell.
    fix: ui-packshots: bundle the approved thumbnails (about 150x240 px WebP) for the seed/pilot SKUs (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S and the other Maxim SKUs) as a pre-seeded ImageCache fallback. Make PackshotImage resolve by SKU code and have ui-sr-a's PackCard use it. Re-render the sr-a-stock golden with the art present. Keep the silho
- (high, ui-sr-a) Whole Stock screen in packshots-stock-sale.png (middle panel) [REAL: Every listed detail is visible in the middle panel. It has a white bar with a black arrow and the title স্টক, a ✓ ১০:৪২ chip, and a periwinkle-to-pink gradient page. Each card has two rows: name with ]
    ref: Gradient red-to-purple bar, light grey page, one-row white cards (packshot, code, minus, field, plus, stock), column headers এসকেইউ / ইস্যু / স্টক, solid purple totals panel, then Save and Print.
    built: The old Calm Glass shell: white bar with black arrow and title, a ✓ ১০:৪২ chip, periwinkle-to-pink gradient page. Each card has two rows (name plus 'আজ লোড ০ স্টিক' and 'মোট লোড · স্টিক', then a separate row with an outlined-circle stepper). The packshot sits top-left at about half the card height. Column headers are এসকেইউ / মোট লোড only. There ar
    fix: ui-sr-a: this screen must be the parity-shell Stock (as in sr-a-stock.png) with real packshots. Remove or redirect the old Calm Glass Stock composable and regenerate packshots-stock-sale.png from the parity shell only. Drop the 'মোট লোড' header, the category sub-headers and the ✓ time chip.
- (high, ui-sr-a) Sale product list in packshots-stock-sale.png (right panel) [OVERSTATED: The description of the right panel is accurate. It has a white bar with a black arrow, 'বিক্রয়' with a 'Banani Store' subtitle and a ✓ clock chip, a pastel gradient page, and lavender-tinted cards wi]
    ref: sale-select.png has only the gradient bar with sale icon and title বিক্রয়, a white pill retailer dropdown, the dark letter panel and a grey page. PARITY-SPEC section 3 says the product list reuses the Stock card (packshot, code, minus, field, plus) on the same grey page. The reference does not show the list itself, so list details cannot be compar
    built: The shell is the old Calm Glass one: white bar with black arrow, title বিক্রয় with a 'Banani Store' subtitle and a ✓ clock chip, pastel gradient page, lavender-tinted cards with a large ৳ price top-right, outlined circle steppers, a lone '+' circle for unselected SKUs, 'পূর্বের বিক্রয় দেখুন' panel, filter chips (খুঁজুন / সব / সিগারেট / লাইটার), a
    fix: ui-sr-a: rebuild the Sale product list in the parity shell. Use gradient AronTopBar, #E8E8E8 page, white PackCard rows identical to Stock (the Sale quantity replaces Issue), and the gradient AronPrimaryButton instead of the navy pill. Keep the cart total as a purple bottom panel. Remove the pastel tinted cards, the circle steppers and the clock chi
- (medium, ui-kit) Pack badge in the old-shell panels
    ref: Solid purple #47199C circle with a bold white digit, overlapping the packshot's bottom-right corner and sticking out past its edge.
    built: packshots-stock-sale.png middle panel: a small pale lavender pill (#DCE3FF) with a dark-blue digit, tucked inside the packshot corner. The right panel has the same pale badge on MaxR, none on the silhouette rows. (The badge in sr-a-stock is correct.)
    fix: ui-kit: expose one PackBadge (solid #4B1C97, white bold digit, circle about 20 percent of packshot width, centred on the packshot's bottom-right corner) and use it from every PackCard; no tinted or pill badge anywhere.
- (medium, ui-kit) Minus / plus buttons and quantity field
    ref: Small rounded squares (about 5 percent of screen width each, about 22 percent of card height) with a long underlined field between them (about 30 percent of screen width).
    built: Buttons about 1.9x wider relative to screen width (about 10 percent, 35 percent of card height). They squeeze the underlined field to about 13 percent of screen width, so the stepper cluster looks chunky.
    fix: ui-kit: draw AronMinus/AronPlus at about 22 dp (the reference is about 21 dp if its phone is 411 dp wide; confirm on device). Keep a 48 dp invisible touch target around each. Let the field take the remaining width with the underline spanning minus-edge to plus-edge. This replaces the 36 dp value in PARITY-SPEC 2.5, because the screenshot wins.
- (medium, ui-sr-a) SKU label text
    ref: The SKU code with a hyphen on one line, purple bold, about 7 percent of screen width for 'MaxR' (MaxR-10S, MaxR-20S, MaxB-10S...).
    built: 'MaxR 10S' (a name with a space, not the code) wrapped over 2 lines, 'Prime Gold 10S' over 3 lines, at a larger size (about 1.2 to 1.4x relative). The old-shell sale panel also uses 'MaxR 10S' while the old-shell stock panel uses 'MaxR-10S'.
    fix: ui-sr-a: bind the label to sku.code (hyphenated, Latin) on Stock and Sale. Set maxLines=1 with autosize down to 14 sp so 'MaxR-10S' never wraps. Give the label column a fixed share of the row so the stepper position is the same on every row.
- (medium, ui-kit) Top bar scale (height, icon, title, back chevron)
    ref: Slim bar, about 8.7 percent of screen width (about 36 dp if the phone is 411 dp wide). Small thin back chevron, icon about 6 percent of width, title স্টক in smallish bold white.
    built: Bar about 15.5 percent of screen width (standard 56 dp). Icon about 9.5 percent, title about 1.7x larger relative, and a chunky bold chevron. Even allowing for the built phone being narrower, the bar contents are about 1.4x too large.
    fix: ui-kit: shrink AronTopBar to about 9 percent of screen width (about 36 to 40 dp), with the icon about 25 to 28 dp, title about 16 to 18 sp bold and a thin 14 dp chevron. Confirm against the device capture at the same dp width, because the exact phone width of the reference cannot be told from the image.
- (medium, ui-kit) Printer-status chip in the top bar
    ref: Small white circle (about 6.5 percent of screen width) with a small orange-red crossed printer, straddling the bar's bottom edge at the right.
    built: Same position and glyph, but the grey #EEE circle is about 14 percent of screen width (about 2x larger relative) with a bigger saturated red printer. It looks like a floating button.
    fix: ui-kit: draw the chip at about 26 dp (circle) with a 48 dp touch target, white fill, glyph about half the circle. Keep the straddle placement.
- (medium, ui-kit) Totals panel height and category rows
    ref: Compact panel, about 26 percent of screen height. Four single-line rows about 3.6 percent of height each, with category labels the same size as the numbers.
    built: Panel about 38 percent of screen height. Each category has an extra unit line (স্টিক / স্টিক / পিস / ডজন) and about 1.7x the row height. Category labels are about 1.3x larger than the reference. In the shot only 2 list rows remain visible. (Header and number columns are correctly aligned.)
    fix: ui-kit TotalsPanel: keep the unit label (UI-SR-15) but put it inline after the category in 12 sp on the same line (for example 'লাইটার · পিস'). Use 20 sp category labels and 22 sp numbers on one baseline, with the row pitch about 3.6 percent of screen height, so the panel is about 26 to 28 percent of the screen.
- (medium, ui-kit) Save button gradient
    ref: Red #D74A4A fading only to plum #7D2B7B at the right end. The button keeps a visible right edge on the purple panel (stops: 0% #D74A4A, 29% #C54253, 58% #A83862, 79% #8D3172, 97% #7D2B7B).
    built: Gradient runs on to deep purple (about #5D228E near the right end), so the button's right end melts into the #481B9A panel and the rounded rectangle is lost.
    fix: ui-kit AronPrimaryButton: when placed on the purple panel, end the gradient at about #7D2B7B (draw only the first 55 to 60 percent of the red-to-purple ramp) so the edge stays visible. Verify the right-end pixel is within about 10 of #7D2B7B.
- (medium, ui-sr-a) Post-save banner
    ref: Nothing between the bar and the column headers (the post-save state is not in the reference, so what the original does after Save cannot be told).
    built: A tall mint-green (#BDF5D6, outside the palette) banner 'সংরক্ষিত হয়েছে। স্লিপ এখনো প্রিন্ট হয়নি।' with a check icon floating mid-height and an underlined 'প্রিন্ট' link below. It takes about 17 percent of screen height and pushes the list down.
    fix: ui-sr-a: replace the inline banner with a transient bottom snackbar in palette (dark text on white or the purple), or show the saved state on the buttons (Save becomes 'সংরক্ষিত', Print becomes active). The list and column headers must stay where the reference puts them.
- (low, ui-kit) Print button label
    ref: Grey #E8E8E8 button, small icon, regular-weight dark grey label (darkest sampled pixel about #5A5A5A). It reads as disabled.
    built: Same fill, but a near-black #0C0C0C bold label and a larger icon, so it reads as enabled while the printer is crossed out. (PARITY-SPEC's #A5A5A5 disabled text is also lighter than the reference.)
    fix: ui-kit AronSecondaryButton: disabled label about #595959, regular weight, 14 to 16 sp, icon about 16 dp. Update the token in PARITY-SPEC 1 to match the screenshot.
- (low, ui-kit) Minus button at zero
    ref: The red minus stays red on every row even at quantity ০.
    built: Minus turns flat grey #E8E8E8 with a grey dash when the quantity is 0 (Prime Gold 10S row).
    fix: ui-kit AronMinus: keep it red at zero (the tap is a no-op) to match the reference. Keep the content description for TalkBack.
- (low, ui-sr-a) Quantity and stock numerals
    ref: Bold black Bengali digits in the field and in the stock column.
    built: Field and stock digits in regular weight, so they look lighter than the reference.
    fix: ui-sr-a: set the PackCard field and stock text to Bold (Noto Sans Bengali Bold) at the current size; leave the white totals-panel digits regular, which matches.

##### SR Sale
VERDICT: The empty-state picker (title bar, white pill, dark letter panel, wording of the hint) is close enough that the owner would accept it apart from the "সব" label and the hint colour, but the product list and review would be questioned straight away because of the grey placeholder silhouettes, the missing retailer dropdown and letter strip, the oversized steppers with wrapping SKU names, and the grey minus.

- (high, ui-packshots) Packshots on the Sale list rows [REAL: In ev/sr-a-sale-list.png (right panel, zoomed crop in crops/verify-sr-sale/list-rows.png) all three visible rows (MaxR 10S, Prime Gold 10S, Aster) show a flat grey tile with a generic silhouette (pack]
    ref: Every SKU row carries the full-colour pack picture (MAXIM red and blue packs with the pictorial warning), full card height, as on the Stock reference and in the owner's directive 'packshots in Stock and Sale'.
    built: All three visible rows (MaxR 10S, Prime Gold 10S, Aster) show a flat grey placeholder tile with a generic silhouette. Even MaxR-10S, whose real art renders in the older docs evidence image packshots-stock-sale.png, is a silhouette here, so the owner sees no pack pictures at all.
    fix: Wire PackshotImage(skuId) in the Sale list to the cached SKU thumbnails and bundle or seed the approved thumbnails for the evidence and pilot data, so MaxR-10S and the other seeded cigarette SKUs render the real art. Keep the silhouette only for SKUs with no art. Re-render this screenshot to prove it.
- (high, ui-sr-a) Retailer dropdown and letter strip after a retailer is chosen [REAL: ref/sale-select.png shows the white retailer pill and the dark A-Z panel under the bar, but only in the empty state, before a retailer is chosen. The built picker screen (ev/sr-a-sale-picker.png) matc]
    ref: sale-select.png has the white retailer pill and the dark letter panel directly under the bar. The SR manual (SR-S-32 and SR-S-19, transcribed text, not an image) says the dropdown stays at the top of the SKU list showing 'name (code-phone-cluster)' with the alphabet strip (All plus letters) below it.
    built: The picker pill and letter panel are gone. The list screen starts with plain black text 'Banani Store' on the grey page, jammed about 8 dp under the bar, with no code, phone or cluster and no way to change retailer or filter by letter on this screen.
    fix: On the list screen keep the RetailerPicker pill, showing the selected label as 'name (code-phone-cluster)' in dark text (not the grey hint), and keep the dark letter panel (All plus letters) beneath it, with the same 16 dp rhythm as the picker screen. Remove the bare-text retailer line.
- (medium, ui-sr-a) Previous-sales shortcut and category chip row
    ref: SR manual SR-S-32 (text only): a full-width green button 'পূর্বের সেল ডাটা দেখুন' with a history icon sits between the letter strip and the SKU cards. No category chip row or search chip is described anywhere.
    built: An underlined purple hyperlink 'পূর্বের বিক্রয় দেখুন' (different wording, looks like a web link), then a row of white pills (খুঁজুন, সব, সিগারেট, লাইটার, ম্যাচ) with a horizontal gradient pill for the selected one, clipped at the right edge, then a sub-header 'এই আউটলেটে আগে বিক্রি'.
    fix: Replace the link with a full-width green rounded button (history icon, label 'পূর্বের সেল ডাটা দেখুন'). Remove the white category chip row and the extra sub-header; the letter strip is the filter, and its selected state is the vertical gradient rounded square, not a horizontal gradient pill.
- (medium, ui-kit) Quantity stepper proportions in the pack card
    ref: Small rounded-square minus and plus: measured 55 px on a 256 px card (about 0.21 of card height, about 0.38 of the packshot width) at the far ends of the cluster, with a wide underlined numeric field between them (about 6 button widths).
    built: Buttons are about 0.35 of card height and about 0.64 of the packshot width (49 px on a 140 px card), with chunky bold glyphs. The underlined field is only about 1.3 button widths, so the cluster looks heavy and squeezed and pushes the SKU label into a narrow column.
    fix: In PackCard shrink the visible minus and plus to about 0.4 of the packshot width (about 26 dp with thin plus and minus glyphs), keep a 48 dp hit area, and give the underlined field weight(1f) so it fills the space between the buttons and the cluster spans about 40 percent of the card width, as in the Stock reference.
- (medium, ui-kit) Minus button at quantity 0
    ref: Every Stock row is at 0 and every minus is the solid red #D84A4A rounded square with a white bar.
    built: Prime Gold 10S at quantity 0 shows a pale grey minus with a grey bar (disabled look). Rows above 0 show red, so the column flips between grey and red.
    fix: Keep AronMinus solid red #D84A4A at all times and just ignore the tap at 0 (no grey or disabled styling, no alpha change).
- (medium, ui-sr-a) SKU label in the pack card
    ref: The SKU code on one line in purple Roboto medium about 18 sp, for example 'MaxR-10S', with a hyphen, always the same size.
    built: The product name without the hyphen, broken over several lines: 'MaxR' / '10S' and 'Prime' / 'Gold' / '10S', while 'Aster' is drawn noticeably larger because it fits on one line, so the sizes are inconsistent across rows.
    fix: Show the short SKU code ('MaxR-10S') at a fixed 18 sp, maxLines 1 with ellipsis, in a label column with a minimum width (it frees up once the steppers shrink); no auto-shrinking text.
- (medium, ui-sr-a) Extra caption lines under each SKU card
    ref: The Stock card is the whole row: nothing is printed between cards, so about 4 to 5 cards plus the panel fit on a 360 dp-wide phone screen.
    built: Under each card, on the grey page, are a caption '৮.০০ ৳ / স্টিক · স্টকে ৪০০' and, for some rows, '= 8 প্যাকেট + ০' with a purple line total '৩২০.০০ ৳' at the right. They repeat the stock figure and the pack count already shown in the card and the badge, and they sit midway between cards so it is unclear which card they belong to. Only about 2 card
    fix: Remove the captions from the grey gap. Keep the badge as the packs figure. If unit price and line total are needed, put one small grey line inside the card under the SKU code and show the line total only when the quantity is above 0.
- (medium, ui-kit) Gradient button on the purple panel
    ref: The Save button on the purple panel is half width and shows only the red-to-mauve part of the gradient (sampled left #D0464B, right #7A2B7C), so its right edge stays visible against the panel #481B9A. On the Summary reference the Print button on the grey page runs the other way, purple on the left to crimson on the right.
    built: The full-width 'এগিয়ে যান' and 'প্রিন্ট' buttons run the whole red-to-purple gradient, so the right end is the panel colour (sampled #481B9A on the list, #4E1D96 on the review against the panel #481B9A). The right edge and corner vanish into the panel.
    fix: For AronPrimaryButton on a purple panel, clip the gradient to about 0 to 55 percent (red to #903171) so the right end is clearly lighter than #481B9A. On the grey page use the Summary direction, purple to crimson.
- (medium, ui-sr-a) First chip of the letter filter
    ref: The first chip reads 'All' (English), also in the manual's alphabet strip.
    built: The first chip reads 'সব' (Bangla).
    fix: Change the first filter chip string to 'All' in both bn and en catalogues; keep the case-insensitive letters and the first Bangla letter for Bangla outlet names (UI-SR-23).
- (medium, ui-kit) Retailer picker hint text colour
    ref: The placeholder 'রিটেইলার নির্বাচন করুন' is a mid grey hint (sampled #9E9E9E) and the caret is mid-dark grey (#616161).
    built: The placeholder is dark (sampled #3A3A3A) and looks like a chosen value, and the caret is nearly black.
    fix: Render the empty hint in #9E9E9E (regular weight) and the caret in #616161. Use the dark text colour only once a retailer is selected.
- (medium, ui-sr-a) Printer-state slot in the Review top bar
    ref: Screens that print show the red circle with a crossed-out printer at the right of the bar (Stock reference) or in the info card (Summary reference). The manual's review screen has a printer icon, green when connected and red slashed when not. UI-SR-20 asks for it on every printing screen.
    built: The right side of the Review (নিরীক্ষণ) top bar is empty even though the screen has a Print button.
    fix: Add the printer-state slot to the AronTopBar on the Review screen (red crossed circle when not connected, tap to connect).
- (medium, ui-sr-a) Review controls: credit toggle and Product QC
    ref: SR manual SR-S-33 (text only): a checkbox 'এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন' (unchecked by default) and a yellow 'প্রোডাক্ট QC' button, then a gradient Print button.
    built: The credit option is a Material switch with an outlined track and dark knob in a white card, and 'প্রোডাক্ট QC' is a white row with a small chevron, not a yellow button.
    fix: Use a purple checkbox for the credit option and a yellow rounded QC button labelled 'প্রোডাক্ট QC' (no chevron row), both in the same white card or above the footer.
- (low, ui-sr-a) Review header block (outlet, route, cluster)
    ref: The manual's review header (SR-S-33, text only) has the outlet name line, then separate 'রুট: ...' and 'ক্লাস্টার: ...' lines.
    built: One grey Latin line 'Banani Store (DHK-344-001) · Apsis RouteDaily · Apsis Cluster' that wraps so 'Apsis Cluster' sits alone on a second line. There are no 'রুট:' or 'ক্লাস্টার:' labels, and the route name runs straight into 'Daily'.
    fix: Render three lines: bold outlet name with '(code)', 'রুট: <route name>' and 'ক্লাস্টার: <cluster>', using the Bangla labels.
- (low, ui-sr-a) Grand total shown twice on Review
    ref: The Summary reference shows 'সর্বমোট' once, at the bottom of the table card, then the Print button on the grey page with no panel.
    built: 'সর্বমোট ১১৭.৫০ ৳' appears in the totals card and again right below it in the purple footer panel above Print.
    fix: Drop the purple total row on Review and sit the Print button on the grey page as on the Summary screen, or drop the duplicate inside the table card.
- (low, ui-sr-a) Sale list footer
    ref: SR manual SR-S-32 (text only): one purple footer row with a cart icon and the total (for example '২৮০.৫০') and the action buttons beside it.
    built: A purple panel with 'মোট' and '৬৪৫.০০ ৳' on one row and a full-width 'এগিয়ে যান' button below it, so the footer is two stacked rows. There is no cart icon, and the total has a ৳ suffix.
    fix: Make the footer a single row: cart icon, total, then the 'এগিয়ে যান →' button at the right, and drop the ৳ suffix in the footer figure.
- (low, ui-sr-a) Money format in Review table cells
    ref: The Summary table writes money with a ৳ glued to the number and thousands separators, for example '৪,৬৮৭.৫০৳'. Figures sit in a thin calligraphic Bengali numeral face.
    built: Row amounts such as '১৬০.০০' have no ৳ (the symbol is in the header '(৳)'), and totals use a spaced ' ৳' in a heavier rounded numeral face.
    fix: Append ৳ directly to each money cell and use the same grouping format as the Summary screen; keep the numeral face consistent across the two screens.
- (low, ui-kit) Latin typeface
    ref: Latin text (letters A to T, SKU codes, retailer names) is Roboto: the capital J sits on the baseline.
    built: Latin text looks like Noto Sans (an Open-Sans-like face): the capital J drops below the baseline in the letter panel, and 'Aster', 'MaxR 10S' and 'Banani Store' have wider humanist forms.
    fix: Apply the bundled Roboto to Latin glyphs in the shared Typography (fontFamily Roboto first, Noto Sans Bengali as fallback).
- (low, ui-kit) Sale icon rendering in the bar
    ref: The clock ring on the sale icon is a solid blue ring with clear hands.
    built: The same artwork, but the clock ring looks dashed and aliased and the hands are thin, like a down-scaled bitmap.
    fix: Re-export the sale icon as a vector drawable (or a 3x bitmap) so the ring stays solid at 36 dp.
- (low, ui-sr-a) Over-stock row state
    ref: No screenshot of the stock warning exists. The manual (F-SR-023) says a stock warning appears when the quantity exceeds stock.
    built: Aster shows quantity ২৬ against stock ২৫ and looks identical to the other rows, with no warning colour or hint.
    fix: Add a visible over-stock state (for example the stock number in red with a small warning glyph) and include it in the golden test.
- (low, ui-sr-a) Open retailer list
    ref: The notes describe rows with four coloured dots and the label 'name (code-phone-cluster)', but that capture is not stored.
    built: The open dropdown is not in the supplied evidence. I cannot tell whether the row layout, the dots or the label format match.
    fix: Add a screenshot of the opened retailer dropdown (with the label format and dot placeholders, no live programme data) to the evidence set.

##### SR Attendance
VERDICT: Mostly yes: the bar, grey page, red divider, Bangla wording and the two gradient Check in / Check out buttons read as the existing Aron, but the owner would spot the lighter bordered location card with Bengali coordinates and a green pill, the black refresh icon, the oversized avatar tiles and the extra footer line.

- (high, ui-sr-a) Location card surface [REAL: I confirmed this on the images and by pixel sampling. The left panel of ev/sr-a-attendance.png is the reference, scaled and pixel-identical to ref/attendance.png. The right panel is the built app, whi]
    ref: A darker grey panel (about #D7D7D7 against the page #E8E8E8) with feathered, borderless edges and a soft wide shadow, compact: about 63 dp tall, 2 lines of text.
    built: A near-white card (#F6F6F6, lighter than the page) with a crisp 16 dp rounded outline and a thin light border, about 112 dp tall (nearly twice the height) because it holds three rows.
    fix: In the attendance location card use fill #D7D7D7 (darker than the page), no border, corner radius about 12 dp and a diffuse low-alpha shadow of roughly 24 dp blur; keep the card to one text block of at most 2 lines so it is about 64 dp tall.
- (medium, ui-sr-a) Location card content (address, digits, accuracy chip)
    ref: The reverse-geocoded address in grey regular 16 sp Roboto: 'RC38+J3Q, RC38+J3Q, Gulshan, Dhaka, ... 1212, Bangladesh', Latin characters, no chip.
    built: Bengali-digit coordinates '২৩.৭৯২৫৮০, ৯০.৪০৭৮৩০' in black, wrapped on two lines far short of the refresh icon, plus a mint-green pill 'ভালো · ±১৮ মি' with a check icon that is not in the reference.
    fix: Online: show the cached or resolved address text in #444 regular 16 sp. Offline (the coordinates case in UI-SR-11): show Latin digits '23.792580, 90.407830' on one line, same grey text, and let it use the full width up to the refresh icon. Replace the green pill with a small grey 12 sp accuracy suffix such as '±18 m' (UI-SR-12 needs the accuracy sh
- (medium, ui-sr-a) Refresh (re-sample location) icon colour
    ref: Red circular arrow (darkest pixel #D74848, aron.red), at the right of the location card.
    built: Near-black navy circular arrow (darkest pixel #00000D), thinner and smaller.
    fix: Tint the refresh icon aron.red #D5484A and use the same bold circular-arrow glyph at about 24 dp.
- (medium, ui-sr-a) User and location leading tiles
    ref: Small rounded-square tiles about 32 dp, tinted lavender (user tile #D7D3DF, location tile #C7C3CF) with a purple glyph.
    built: Tiles about 56 dp (roughly 1.75 times larger, estimated from the bar-height scale) filled neutral grey #D9D9D9 with a purple glyph, so the user row and card look chunkier.
    fix: Reduce both leading tiles to about 32 to 36 dp with radius 8 dp and tint them lavender (#D7D3DF user, #C7C3CF location, i.e. aron.purple at low alpha), glyph about 18 dp.
- (medium, ui-sr-a) Route and date line digits
    ref: 'Apsis RouteDaily, 2026-10-05' with Latin digits, in Bangla mode.
    built: 'Apsis RouteDaily, ২০২৬-১০-০৫' with Bengali digits.
    fix: The screenshot wins over the 'Bengali digits in Bangla mode' token on this line: render the ISO business date and the login with Latin digits in the user header (same as Home); keep Bengali digits only where a reference shows them.
- (medium, ui-sr-a) Footer status line under the buttons
    ref: Nothing below the buttons; the lower half of the screen is empty grey.
    built: An extra small line 'ফোনে সংরক্ষিত হয়েছে। নেটওয়ার্ক এলে আপলোড হবে।' ('Saved on phone, will upload when network comes') shown before any check-in has happened.
    fix: Remove the permanent line; show the saved-offline notice only after a check-in or check-out is actually queued, as a transient snackbar or toast (offline-first status stays, the idle screen matches the reference).
- (medium, ui-kit) Top bar icon and title weight
    ref: Clean calendar-clock icon about 36 dp (3 bars on top, 3 by 2 grid of outlined squares, ringed clock with a blue edge) beside a lighter-weight white Bengali title about 78 dp wide.
    built: Icon about 29 dp and noisy: the grid and clock face are speckled with white fringe pixels and the clock hands are illegible; the title is bolder and about a third wider (about 107 dp), so title and icon proportions differ from the bar.
    fix: Re-export the attendance icon as a clean vector (or a properly mipmapped 36 dp bitmap without white fringing) and set the AronTopBar title to the lighter bold weight used in the reference (Noto Sans Bengali SemiBold, not Bold) at 20 sp so the title is about 80 dp wide; icon-to-title gap about 6 dp.
- (medium, ui-sr-a) Check in / Check out icon and label size
    ref: Glyph about 18 dp and a light-weight 14 sp label centred in a button about 100 dp tall; lots of green or grey around them.
    built: Glyph about 29 dp (roughly 1.5 times larger) and a bold label about 1.45 times wider, in a button of the same height, so the content looks crowded and heavy.
    fix: Shrink the button glyph to about 20 dp and the label to 14 sp regular weight (not bold), keep them centred as a column with about 8 dp between them.
- (low, ui-kit) User name typography
    ref: 'SR - Testing Banani (sr334001)' in Roboto Medium (500), one line, black; second line 12 sp medium.
    built: The same text in a heavier bold, wider Noto-Sans-like Latin face, so it wraps to two lines '(sr334001)' on phone width.
    fix: Use Roboto Medium 16 sp for Latin text in the user row (check that Roboto is actually bundled and not a Noto fallback), so the name fits on one line at 360 dp.
- (low, ui-sr-a) Vertical spacing between location card and message
    ref: About 67 dp of empty space between the bottom of the location card and the centred message.
    built: About 34 dp between the card and the message, so the message sits tight under the card.
    fix: Increase the gap between the location card and the message to about 56 to 64 dp (on a phone, allow it to shrink only when the message wraps).

##### SR Summary and Submit
VERDICT: Close on the shell, but not yet the existing Aron: the top bar, grey page, column wording, purple totals and red-to-purple buttons match, yet the Summary leaves out the ৳ on values and has an oversized header and tiny category rows, and the Submit has rewritten table rows, an extra status card, a rewritten red notice and the wrong shield icon, so the owner would notice within seconds.

- (medium, ui-sr-b) Summary: currency symbol placement
    ref: A ৳ suffix with no space on every Value and Discounted-value cell in SKU and category rows (৪,৬৮৭.৫০৳, ৮৪.০০৳, ৫৭.০০৳, ১৪১.০০৳, ৪,২৫০.০০৳). The Discount column, the discount line (-৪৩৭.৫০) and the grand total (৪,৩৯১.০০) carry no ৳.
    built: No ৳ in any table cell. A spaced ' ৳' is added to the discount line ('− ৪৩৭.৫০ ৳') and the grand total ('৪,৭৩০.০০ ৳'), the exact opposite of the reference.
    fix: Append ৳ directly (no space) to the Value and Discounted-value columns in SKU and category rows; remove ৳ from the discount line and grand total; leave the Discount, Quantity, Memo and Return columns bare.
- (medium, ui-kit) Summary: table header label size and wrapping
    ref: Tiny white header labels on one line (width of 'ডিসকাউন্ট' is about 0.64 of the width of a number like ৪৩৭.৫০), including 'ডিসকাউন্ট মূল্য' on a single line; header row is about two thirds of the top-bar height.
    built: Header labels as large as the figures (about 1.06 of the number width), 'ডিসকাউন্ট মূল্য' wraps onto two lines, and the header row is about as tall as the whole top bar.
    fix: Set SummaryTable header text to roughly 60 percent of the figure size (about 8 to 9 sp, spec's 12 sp is too big here), maxLines=1, and weight the six columns so 'ডিসকাউন্ট মূল্য' fits at 360 dp; cap the header row at about two thirds of the top-bar height.
- (medium, ui-sr-b) Summary: category subtotal rows (লাইটার / ম্যাচ)
    ref: Purple category label at about the same size as the figures, bare quantity (৩৭৫, ৬) in the quantity column, one-line rows with the same column positions as the SKU rows.
    built: Category labels (সিগারেট, লাইটার) are tiny, about 0.65 of the figure size, and the quantity cell carries a unit that wraps onto two lines ('৬০ / স্টিক', '৩৭৫ / পিস'), doubling the row height and breaking the column rhythm.
    fix: Make the category label the same purple size as the figures; render the quantity as a bare number in the column; if the unit is kept (UI-SR-33), set it as a 10 sp suffix on the same line with softWrap=false, or leave it off the table and print it on the slip.
- (medium, ui-sr-b) Summary: discount-and-others and grand-total lines
    ref: 'ডিসকাউন্ট এবং অন্যান্য (-)' on one line, medium-bold purple, value '-৪৩৭.৫০' with an ASCII hyphen and no space.
    built: The label wraps onto two lines ('ডিসকাউন্ট এবং / অন্যান্য (−)'), is heavier, and uses a typographic minus with a space ('− ৪৩৭.৫০') and '(−)'.
    fix: Use SemiBold at about 20 sp (so the label fits one line at 360 dp), ASCII '-' and '(-)', and format as '-৪৩৭.৫০' with no space.
- (low, ui-kit) Summary: table header fill and corner shape
    ref: Header is a horizontal grey gradient, about #4F4F4F at left to #3F3F3F in the middle to #2D2D2D at right, with a square top-left corner and only the top-right corner rounded (about 20 px).
    built: Flat #4D4D4D with both top corners rounded.
    fix: Draw the SummaryTable header with a left-to-right gradient #4F4F4F, #3F3F3F, #2D2D2D; top-left radius 0, top-right radius about 8 dp.
- (low, ui-sr-b) Summary: layout of SKU list versus totals block
    ref: The white SKU list fills the available height (blank white space below the last SKU) and the category rows, discount line and grand total sit pinned in a separate #F0F0F0 block above the Print button.
    built: Category rows and totals flow straight after the last SKU row inside one card; the first category block merges visually into the alternating-grey Aster row. Cannot tell how a long list behaves from this one render.
    fix: Make the SKU list a weight(1f) scrolling region and dock the category/discount/total block below it as its own #F0F0F0 panel.
- (low, ui-sr-b) Summary: info banner card
    ref: White card with a thin grey (about #C0C0C0) outline and no shadow, text on one line near the top, only about 6 dp gap under the top bar; the #E8E8E8 printer circle overflows and is clipped by the card's bottom edge, holding a small red printer-off glyph with a red slash.
    built: No outline (soft shadow instead), text wraps to two lines and is vertically centred, about 17 dp gap under the bar, the circle sits fully inside the card with a drop shadow and a larger glyph with a white slash.
    fix: Give the banner a 1 dp #C0C0C0 border and no shadow, top-align the text, tighten the gap under the bar to about 6 dp, and draw the printer circle clipped at the card's bottom edge with the smaller all-red crossed-printer glyph.
- (low, ui-sr-b) Summary: extra caption
    ref: Nothing between the totals block and the Print button.
    built: Added line 'আজ ২ টি মেমো' above the Print button.
    fix: Remove the caption, or move it inside the info banner; the memo count is already in the Memo column.
- (low, ui-kit) Top bar details on both screens (title weight, back chevron, title icon size)
    ref: Title in a medium weight, small thin back chevron, and the server-rack icon filling about 80 percent of the bar height as outlined slabs with a pink-to-blue gradient fill and a blue network node.
    built: Title is heavier bold, the back chevron is larger and bolder, and the rack icon fills about 65 percent of the bar height with paler fill and dotted white outlines.
    fix: AronTopBar: title SemiBold/Medium, chevron about 60 percent of current size and thinner stroke, title icon about 80 percent of bar height; redraw the rack vector with solid outlined slabs and the pink-to-blue gradient fill.
- (low, ui-kit) Summary Print button gradient end stops
    ref: Left #4A1C99 to right #A73964 (ends dusty crimson, never reaches full red).
    built: Left #531F93 to right #D1474D (full bright red).
    fix: For the Print button use stops #4A1C99 to #A73964 (the reference button is a window onto a wider gradient); same idea for the Sync button below.
- (medium, ui-sr-b) Submit: reconciliation table rows
    ref: Five rows: আউটলেট, বিক্রয় (sale quantity), স্টক (issued quantity), কিউসি, প্রমোশন (discount amount, shown as ৪৩৭.৫), with the Server column showing ০ before upload.
    built: Four rows: আউটলেট, মেমো, স্টক (count of 2), কিউসি. The বিক্রয় and প্রমোশন rows are gone and a new মেমো row is added; স্টক shows a line count, not the issued quantity.
    fix: Show the five legacy rows with the reference labels and meanings (outlet count, sale quantity, issued quantity, QC, promotion amount which shows 0.00 while discounts are deferred per docs/27); keep the exact per-entity count and milli-taka checks as the hidden gate for enabling Submit (per UI-SR-36 note).
- (medium, ui-sr-b) Submit: extra status blocks
    ref: Only the device-status row, then the table directly beneath it.
    built: Two additions: a small line 'শেষ যোগাযোগ ১০:৪২' and a white card with green text 'সব তথ্য মিলেছে', which push the table down by about a quarter of the screen.
    fix: Remove the white card and the last-contact line; express the matched/not-matched state through the enabled or disabled Submit button and the existing red notice, and derive the Online dot from last server contact (UI-SR-38) without extra text.
- (medium, ui-sr-b) Submit: red notice wording
    ref: 'আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।'
    built: 'আজকের সব তথ্য সিঙ্ক হলে বিক্রয় জমা দেওয়া যাবে। সিঙ্ক নিজে থেকেই চলছে।' (rewritten text).
    fix: Use the original sentence verbatim as the notice; if the automatic-sync hint is wanted, add it as a second sentence only when background sync is active, without changing the first.
- (medium, ui-kit) Submit: title icon (shield)
    ref: A plain shield outline with an inner circle and a check mark, pink at the top to blue at the bottom, nearly full bar height.
    built: A smaller crest-like shield with a dotted ornamental ring and tiny emblem inside; no visible check mark.
    fix: Redraw the sales-submit vector as the reference: single outline shield, inner circle, check mark, pink-to-blue vertical gradient, sized to about 80 percent of the bar height.
- (medium, ui-kit) Submit: second button style and disabled state
    ref: 'বিক্রয় জমা' is a grey #BABABA fill with a white bold label and a small archive/inbox-with-down-arrow glyph (disabled until sync completes), so only one button looks active.
    built: Render is in the all-synced state: the Submit button is the same red-to-purple gradient as Sync, with a floppy-disk glyph. The disabled look is not in the evidence, so cannot tell if it matches; the spec token aron.print.disabled (#E8E8E8 fill, #A5A5A5 text) does not equal the reference's #BABABA with white text.
    fix: Add a disabled primary-button style (#BABABA fill, white bold label) and use it for Submit while not reconciled; swap the floppy-disk glyph for the inbox-arrow glyph; provide a not-synced screenshot for review.
- (low, ui-kit) Submit: Sync button icon and gradient end
    ref: A small loop-arrows sync glyph left of the label; gradient runs red #D64848 to a mid purple about #792A7D, never reaching deep indigo.
    built: A larger bold left-right double-arrow glyph; gradient reaches deep #4E1D95.
    fix: Use the small circular sync glyph at label cap-height and end the gradient near #792A7D.
- (low, ui-sr-b) Submit: spacing and column positions
    ref: Roomy vertical spacing (gap table to notice about 0.9 top-bar heights, notice to buttons about 0.45); Device and Server columns spread wide, near 71 and 90 percent of the width.
    built: Compact spacing (about 0.4 top-bar heights table to notice) and the Device/Server columns bunched at about 60 and 82 percent of the width.
    fix: Increase the gap between table and notice to about 24 dp and push the Device and Server columns right (weights roughly 5:2:1.3).
- (low, ui-kit) Evidence render viewport
    ref: Reference screenshots are 1256x1984 and 1246x1460 (aspect about 0.63 or 0.85).
    built: Built renders are 540x1200 (360x800 dp at 1.5x, aspect 0.45), so absolute sizes and wraps cannot be compared directly; I compared same-image ratios only. Some wraps (banner text, discount line) may be viewport-induced; cannot tell how they look on the owner's actual phone.
    fix: Add a second golden render at the reference's aspect and a 412 dp width for Summary and Submit, and confirm the single-line banner and header at both widths.

##### SR Outlet menu and Tasks
VERDICT: Close but not yet: Tasks is nearly the existing Aron (only the wording and an extra sync line differ), but the Outlet menu has two extra full-width gradient buttons under the tiles that the owner's app does not have, so he would say it is not his screen.

- (high, ui-sr-b) Outlet menu: extra buttons under the tile grid [OVERSTATED: The ev image is a side-by-side: the left panel is the owner's reference scaled to 1200 px tall, and the right panel is the build. The reference (ref/outlet-menu.png) has only the four tiles on a plain]
    ref: Only the four tiles (নতুন দোকান, স্থায়ী বন্ধ, তথ্য পরিবর্তন করুন, ক্লাস্টার) on a plain grey page. The lower half of the screen is empty, with no buttons.
    built: Below the Cluster tile are two full-width red-to-purple gradient buttons, white bold: 'এই আউটলেট আমার রুটে যোগ করুন' and 'আমার অনুরোধ'. They take over the lower half of the screen and are the most eye-catching thing on it.
    fix: Remove both buttons from the Outlet menu so the screen is exactly four tiles (strings out_req_route_add and out_own_open in feature-outlet values-bn/strings.xml, laid out in OutletMenu.kt). Keep the functions, but put them where the reference shows nothing: 'add to my route' inside the outlet detail or the তথ্য পরিবর্তন করুন flow, and 'my requests'
- (medium, ui-kit) Outlet menu: tile label wraps and rows have unequal height
    ref: Every label is on one line, including 'তথ্য পরিবর্তন করুন', which fills about 79 percent of the tile width. All four tiles are the same size (about 350 x 400 px, rows 396 and 397 px high). The label sits near the tile bottom, about 13 percent from the edge.
    built: 'তথ্য পরিবর্তন করুন' wraps to two lines ('তথ্য পরিবর্তন' / 'করুন'). That stretches row one to 212 px while row two (Cluster) is 184 px, so tiles 1 and 2 have a blank band under a single-line label and the grid looks uneven.
    fix: In AronTile (menu variant) force the label to one line (maxLines 1, autosize down to about 11 sp) and give the tile a fixed aspect (height about 1.14 x width) so every row is equal. I cannot tell whether the original also wraps on a 360 dp phone, but the owner's screenshot shows one line and equal tiles, so match that.
- (medium, ui-sr-b) Tasks: empty-state wording
    ref: 'আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেননি।' The last word is করেননি, with two ন. Checked at 8x zoom.
    built: 'আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।' The last word is করেনি, with one ন. The repo has it in tsk_empty (feature-tasks values-bn/strings.xml), the same form is asserted in TaskScreenTest.kt, and docs/ui-reference/sr/tasks-and-astha.md transcribed the reference wrongly in the same way.
    fix: Change tsk_empty to 'আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেননি।', update the assertion in TaskScreenTest.kt, and correct the transcription in tasks-and-astha.md (and the English counterpart if one exists). Have a Bangla reader confirm.
- (low, ui-sr-b) Tasks: extra 'synced at' line and empty-state typography
    ref: One thin, dark, single-line sentence and nothing else. The face is a light, narrow Bangla face and the Latin 'AMO' is drawn in a serif.
    built: The sentence is larger and heavier (Noto Sans Bengali regular, sans 'AMO') and breaks onto two lines with a one-word widow ('করেনি।'). A third, smaller grey line is added below it: 'সিঙ্ক হয়েছে ২০২৬-১০-০৫ ১০:৪২'. This extra line is from note UI-SR-44 and is not in the owner's screenshot.
    fix: Show the 'সিঙ্ক হয়েছে ...' line only when the list may be stale (offline, or last sync older than the sync interval), so the default empty state is the single sentence. Centre the sentence with balanced line breaks, or a slightly smaller body size, so no one-word widow appears. Keep Noto Sans Bengali, because the font is bundled by spec. I cannot 
- (low, ui-kit) Menu tile surface: no shadow and different spacing
    ref: Tiles have a soft diffuse shadow, a halo of about #E0E0E0 for 8 to 10 px under and around each tile on the #E8E8E8 page, plus a 1 px white rim. The horizontal gap between tiles is about 50 px, which is 4.0 percent of the width. The vertical gap is about 22 px, which is 1.7 percent, so the column gap is about 2.3 times the row gap.
    built: Tiles are flat: the #E8E8E8 page runs right up to the #F5F5F5 tile edge with no shadow. Column gap and row gap are both about 14 px (2.6 percent of the width), so the grid reads as tighter and more boxy.
    fix: Give AronTile the 2 dp soft elevation shadow named in PARITY-SPEC for cards (no blur layer). On the Outlet menu make the column gap about 2 times the row gap (for example 16 dp columns and 8 dp rows) instead of equal gaps. I cannot tell whether the corner radius differs, because the capture widths differ.
- (low, ui-kit) Outlet menu: icon size
    ref: The new-shop icon is about 84 percent of the tile width and the CLOSE disc about 76 percent. The edit-shop icon is about 66 percent and the cluster icon about 61 percent wide by 69 percent high. The first two are visibly bigger than the other two.
    built: All four icons are scaled to the same height, about 68 percent of the tile width. The new-shop and CLOSE icons are therefore 10 to 20 percent smaller than in the reference.
    fix: Scale per icon rather than one uniform box: new-shop and CLOSE to about 80 percent of the tile width, edit-shop and cluster about 65 percent. This only applies if the ref icons scale with the tile, so check it against a same-width capture.
- (low, ui-kit) Outlet menu: tile label colour
    ref: A flat, lighter coral red, #D74848 (sampled), which is the #D0434A token in PARITY-SPEC.
    built: The label reads as a deeper, more saturated crimson. The darkest sampled pixels are about #C5121B to #CA272F, although antialiasing on thin strokes makes the exact hex hard to read.
    fix: Use the aron.red Outlet-menu label token #D0434A for the AronTile menu variant label. Confirm that no other red constant (such as an error red) is being used.
- (low, ui-kit) Top bar title, chevron and bar icon rendering
    ref: The title is semi-bold in a narrower face, 33 px glyph height in a 111 px bar (about 30 percent). The back chevron is 10 x 18 px (about 16 percent of bar height), 3.3 percent of the width from the left edge. The Outlet bar icon is a solid gradient silhouette with the awning and door knocked out.
    built: The title is heavier and wider (Noto Sans Bengali Bold), 29 px in an 86 px bar (about 34 percent). The chevron is 12 x 20 px (about 23 percent of bar height), 5.6 percent of the width from the left edge. The Outlet bar icon has a thin light outline halo and outlined, not knocked-out, door and awning detail.
    fix: In AronTopBar use SemiBold (600) for the title instead of Bold. Make the chevron about 20 percent of bar height and move it about 2 dp closer to the edge. Remove the outline stroke from the bar's shop vector so it is a solid gradient silhouette.
- (low, ui-kit) Top bar height and text scale (cannot tell)
    ref: The bar is 8.8 to 9.0 percent of the screen width on every reference (outlet-menu 113/1258, tasks 109/1244, stock 102/1146). Title and body text are small relative to the width. These proportions fit PARITY-SPEC tokens (56 dp bar, 20 sp title, 16 sp body) on a capture about 600 dp wide.
    built: On a 360 dp frame the bar is about 15.5 percent of the width (84 of 540 px), and the title and empty-state text are 2 to 3 times larger relative to the width. This is most likely an artefact of capturing at different widths, not a real delta. I cannot confirm it.
    fix: Do not change the bar or text tokens on this evidence. Render the built screen at the same dp width as the reference (about 600 dp), or ask the owner for a capture from a normal 360 to 411 dp phone, and re-compare bar height, title size and tile label size before touching Tokens.kt.

##### AMO and TSO Home
VERDICT: Mostly yes: the header gradient, white tile grid, indigo labels and red-to-purple card are the same family as SR Home and nothing looks like the old glass or teal design. But the header text wraps and is heavier than the reference, the TSO tiles and icons are a different flatter set, and the bottom area is inconsistent, so the owner would notice that it is not an exact sibling.

- (medium, ui-kit) Home header title (AMO and TSO)
    ref: One line 'SR - Testing Banani (sr334001)' in a small Roboto Medium style. It is visibly smaller than the tile labels and has a lot of air around it.
    built: Bold, and noticeably larger than the tile labels. It wraps to two lines on both screens: 'AMO - Testing Banani / (amo5001)' and 'TSO - টেস্টিং ঢাকা নর্থ / (tso1001)'. The Latin letters look like Noto Sans or Open Sans rather than Roboto. I cannot be certain of the face at this resolution.
    fix: In AronHomeHeader use Roboto Medium (not bold) for the title at about 14 to 16 sp with maxLines 1 and autosize down to 12 sp. This keeps 'Role - Name (login)' on one line. The header then has the same title-to-label hierarchy as the SR reference.
- (medium, ui-kit) Home header second line (date)
    ref: 'Apsis RouteDaily, 2026-10-05': route name, comma, then the date in plain ASCII digits, 12 sp bold white, directly under the title.
    built: Only the date, in Bengali digits '২০২৬-১০-০৫', tiny. There is no route or area name, so the second line is a different pattern. AMO and TSO have no screenshots, so I cannot tell what the manual's second line should be.
    fix: Make the subtitle '<area or territory name>, YYYY-MM-DD' with ASCII digits for the date, as the SR header does. Lanes pass the area or zone name from the manual. If the manual has no such name, keep the date alone but in ASCII digits.
- (medium, ui-kit) Tile proportions and label wrapping
    ref: Tiles about 1.15 times as tall as wide. A large icon sits at the top, a single-line label sits near the bottom, and the padding is even. Every label fits on one line.
    built: AMO tiles are about 1.4 times as tall as wide. TSO tiles are about 1.6 times as tall as wide, with a lot of empty space under one-line labels. TSO 'ফাইনাল সাবমিট' and 'আমার মতামত' wrap to two lines. The scale differs from the reference, so some of this may come from the narrower render width.
    fix: In AronTile fix the tile height to about 1.15 times the width. Anchor the label to the bottom with 6 dp padding. Allow a maximum of two lines but autosize the label from 14 sp down to 11 sp so the common case is one line. Do not reserve two lines of height for every tile.
- (medium, ui-tso) TSO tile 4 label 'আমার পরিধি'
    ref: Labels always have clear side padding inside the tile. Even the longest, 'টাস্ক ডেলিগেশন', has margin on both sides.
    built: 'আমার পরিধি' runs edge to edge in the tile, with about 2 to 3 px of margin each side and the final vowel sign touching the border. It is on the edge of being clipped.
    fix: Give the tile label 4 dp horizontal padding and let it wrap or autosize. Review the label wording with the manual. If the term can be shortened (for example 'পরিধি'), do that.
- (medium, ui-tso) TSO tile icons
    ref: The SR and AMO icons for the same ideas are fine rounded line art. The shield is a red-to-purple-to-cyan outline with an inner ring. The shop has a pink-to-purple awning. The calendar has a clock and a thin outline.
    built: TSO draws its own flatter, solid-filled versions. The shield 'ফাইনাল সাবমিট' is a solid magenta-to-blue fill with a white ring, unlike the AMO 'বিক্রয় জমা' shield. The shop 'আমার পরিধি' is a solid purple-blue block with no awning detail, unlike the AMO outlet icon. The calendar 'ছুটি' is a solid fill. Same concept, different drawing, inside one ap
    fix: Reuse the SR and AMO vector drawables for the shield and calendar, in the reference gradient (pink-red at the top to purple to cyan). For 'আমার পরিধি' reuse the outlet shop icon, or redraw it as the same awning-and-door line art. Do not introduce a second flat set.
- (low, ui-amo) AMO and TSO-only icons (জয়েন্ট কল, টিম, রিপোর্ট, ড্যাশবোর্ড, কন্ট্রোল কল)
    ref: Icons are fine line art with a two-tone pink-to-blue gradient and several inner details: clock ticks, awning slats, and a ring inside the shield.
    built: Same hue family, but chunkier and simpler: two dots and arcs for 'জয়েন্ট কল', three heads on a bar for 'টিম', three bars and a pie for 'রিপোর্ট'. They read as a different, flatter icon family next to the redrawn SR icons. There is no reference for these concepts, so this is a style comparison only.
    fix: Redraw these with the same stroke weight, rounded caps, a thin outline plus gradient fill, and an inner detail or two, from the SR icon grid. Keep the gradient direction identical to the SR icons.
- (medium, ui-tso) TSO bottom card 'জোন জমা হয়েছে'
    ref: The card below the tile grid on Home is the vertical red-to-purple gradient card with white text and a rounded 12 to 16 dp shape.
    built: A white card with a soft shadow on a white page, with a purple title and a black number. AMO uses the gradient card for the same slot, so the two role homes disagree with each other. TSO is the odd one out from the SR family.
    fix: Render the TSO card in the gradient (#C74350 to #561F91) with white title and number, the same as the AMO card and the SR Target card shell. Keep the number at 28 sp bold and the 'আজ ১২ টি জোনের মধ্যে' line at 14 sp white.
- (low, ui-amo) AMO sync and status lines
    ref: The SR family uses black or grey ink and purple accents only. The reference has no loose status paragraph with a different ink colour.
    built: 'সিঙ্ক হয়েছে' is bold slate navy (about #17253D), outside the token set, with a thin Material-style check. The next two grey lines are plain text. The second line breaks with a dangling '·' at the end of the first line. TSO shows the same information as a single plain grey line with no icon, so the two roles differ.
    fix: Use one shared 'sync status' footer component with token colours only: grey ink for text, aron.purple for the check, the same on AMO and TSO. Break the line between the items, not at a '·'.
- (low, ui-kit) Count badge position and size
    ref: The reference Home has no badge visible, so the badge is judged on its own. The SR built Home puts the badge on the tile corner.
    built: AMO outlet '৩' covers the right end of the shop awning. TSO ছুটি '১' covers the top-right ring of the calendar. The badge digit is about 8 sp and thin.
    fix: Move the badge slot to the tile's top-right corner, 4 dp outside the icon box. Make the digit 11 sp bold and the circle 20 dp so it is readable without hiding icon detail.
- (low, ui-kit) Tile corner radius
    ref: Tile corners are small, about 10 px on a 260 px tile (about 4 percent of tile width). They look almost square.
    built: Corners are about 12 percent of tile width. The tiles are clearly rounder than the reference on a side-by-side. This follows the spec's 12 dp number, but the screenshot looks tighter.
    fix: Use about 6 dp tile corner radius on Home tiles (screenshot wins over the 12 dp in the spec). Keep 12 dp for cards and the gradient card.
- (low, ui-kit) Tile label weight
    ref: Bangla labels look Medium or SemiBold in indigo, with strong strokes.
    built: Same indigo hue but a lighter Regular weight, so the labels look thinner next to the reference. I cannot tell whether the face itself also differs.
    fix: Set tile labels to Noto Sans Bengali Medium (500) at 14 sp; keep colour #3F2D9B.
- (low, ui-tso) Digit style in TSO footer line
    ref: The SR app shows Bengali digits for figures. It shows ASCII only in the header date.
    built: TSO 'সিঙ্ক হয়েছে 10:42' uses ASCII time next to Bengali digits ('৩', '১২', '২০২৬-১০-০৫') elsewhere on the same screen. Digit style is mixed.
    fix: Format the time with the same locale digit helper (Bengali digits in Bangla mode: '১০:৪২'). Keep the header date ASCII only if the subtitle fix above is adopted.

##### Web dashboard home
VERDICT: Shell, palette and card grid are recognisably the existing Aron dashboard, but the owner would spot within seconds that the FF Geo Location map is only a placeholder, the channel cards stack legend over a flat donut instead of legend-left beside a pie or radial chart, and the sidebar menu tree is not his.

- (high, web-dashboard) FF Geo Location card (row 4, right) [OVERSTATED: What I could see: the built crop (crops/verify-web-home/ff-geo.png) matches the claimed built state. It shows a grey plate with a map icon, '3 SRs · 4 outlets', 'The map loads only when you ask, so a ]
    ref: A wide Google map with clustered red and magenta markers carrying counts, a 'Filter by Color' select (All Colors), a fullscreen button and zoom controls.
    built: A grey placeholder box with a map icon, '3 SRs · 4 outlets', the line 'The map loads only when you ask, so a visit costs no map load.' and a 'Show map' button. There is no map, no markers, no colour filter, no fullscreen and no zoom control.
    fix: Render the map in the card on desktop web (the web dashboard users are not on the 2 GB field pack). Show clustered red and magenta count markers, add the 'Filter by Color' select (All Colors) and the fullscreen and zoom controls. If map loads must stay lazy, load it automatically when the card scrolls into view and keep at least the colour select a
- (medium, web-dashboard) By Channel Successful Call card (row 2, second card)
    ref: Title 'By Channel Successful Call'. A coloured list of channel counts with share, for example '2.15 Lakh (78.3%) GT Channel', with a concentric radial chart to the right of the list.
    built: Title truncated to 'By channel: success…' with an ellipsis. The legend sits on top (Grocery 37 100.0%, Pan 0 0.0%) and a single plain blue ring labelled 37 sits below it, not beside it and not concentric.
    fix: Use the full title 'By Channel Successful Call' (allow two lines or shrink the Details link). Put the legend at the left and a concentric multi-ring radial chart at the right, one ring per channel in that channel's legend colour.
- (medium, web-dashboard) By Channel STD card (row 3, left)
    ref: Title 'By Channel STD'. A 3D-style pie with a colour-coded legend of values and shares to its left (GT 71.2% blue, Astha 24.8% orange, other channels as thin slices).
    built: Title 'By channel: net sales'. The legend rows (Grocery 3.80 Lakh 70.0%, Pan 1.63 Lakh 30.0%) are stacked above a flat donut with '5.43 Lakh' in the centre. The donut is pushed to the right and the area left of it is empty.
    fix: Retitle to 'By Channel STD' and lay the card out as legend-left, chart-right on one row. Use a pie with a slight 3D or bevel treatment (or a solid pie with thin separators) in the same blue and orange channel colours.
- (medium, web-dashboard) Sidebar menu tree
    ref: Top-level order: Dashboard, Retailer (sub-menu), Products (sub), Route Planning (sub), Reports (sub), Outlet (sub), Credentials, Tutorial, Performance Leaderboard, Daily Tracking Dashboard. Target, Astha and Superstar are deferred.
    built: Two flat groups, 'Dashboards' (Dashboard, Daily Tracking, TSO Daily Tracking, Final Submit status, Sync health, Exceptions, Leave approval, Browse Routes, Tutorial, Credentials) and 'Reports' (Browse Retailer, Products: SKU, Task Planner, By-Route Geo Capture, STD Memo Report, SR Efficiency, Route-wise STD…). The list scrolls inside the sidebar. Th
    fix: Restructure the nav into the owner's top-level items with expandable sub-menus (Retailer, Products, Route Planning, Reports, Outlet) and move the new pages (Sync health, Exceptions, Leave approval, SR Efficiency and so on) under the matching group. Add Performance Leaderboard and keep Credentials, Tutorial and Daily Tracking Dashboard at top level.
- (medium, web-dashboard) Sales card rings (row 1)
    ref: Each ring shows percentage achievement against target (60.8%, 82.2%, 78.0%, 68.7%). The spec says target is deferred, so the ring should be an honest trend or total, clearly labelled.
    built: Rings show 80.0%, 10.0%, 6.0% and 4.0% with no label saying what they are a percentage of. Just below, a separate line reads '0.0% vs same day last week'. Cannot tell the denominator behind the ring percentages. As built, a viewer can read them as a target or achievement figure, which is deferred, or as an invented number.
    fix: Either put the real trend vs same weekday last week inside the ring with a small caption, or label the ring explicitly (for example 'of last week's total') and show only data the report engine returns. Keep the ring component ready for target.
- (medium, web-dashboard) Sales card layout at 1366 px
    ref: Total and 'Total Sales' label at the left, large ring gauge at the right, no collision.
    built: In the Bidi and Lighter cards the headline '65.00 Lakh' and '1.11 Lakh' run into the left edge of the ring track. The 'Total sales · Stick/Piece/Dozen' label wraps onto two lines in every card. The first ring is also visibly thicker (about 14 px stroke) than rings 2 to 4 (about 9 px), and the ring colour ends in a dark teal rather than the bright c
    fix: Give the ring a fixed size and stroke across all four cards (about 10 px). Reduce the headline size or stack the ring below the label when the total is wider than about 120 px, so nothing touches the ring. Keep 'Total sales · Unit' on one line. Change the ring gradient to #1565C0 into a brighter cyan such as #00B8D9.
- (medium, web-dashboard) Breakdown card title, toggle and gear
    ref: Title 'Breakdown - STD / Memo' with the STD/Memo toggle in the title and a gear icon at the right.
    built: Title 'Breakdown by brand'. The toggle is a pill reading 'Net sales | Memo' at the right and there is no gear icon. The second bar ('Other brand') fades from orange to a muddy grey-brown instead of a clean brand gradient.
    fix: Retitle to 'Breakdown - STD / Memo' with the toggle inline in the title and a gear icon at the far right for chart options. Use clean two-stop gradients per bar (brand colour to a lighter or darker tint of the same hue, never to grey).
- (medium, web-dashboard) Final Submit Status card
    ref: Left column 'Total Zone / Total Service Zone / Remaining' (1,051 / 1,046 / 0). On the right a green ring at 100.0% with the caption 'Total Final Submit 1,046'.
    built: Left column reads 'Total zones 2 / Submitted 0 / Remaining 2' and the ring is a grey empty ring at 0.0% with no 'Total Final Submit' caption. Extra blocks (Submit % 66.7%, Day completion % 25.0% with the label wrapping to two lines, two orange 'Pending' zone pills) make the card much busier than the reference.
    fix: Use the reference labels: Total Zone, Total Service Zone, Remaining. Add the 'Total Final Submit N' caption under the ring and draw the ring green as soon as there is any progress. Move Submit % and Day completion into a quieter second row and keep the label 'Day completion %' on one line.
- (low, web-dashboard) Row 2 card headers
    ref: Four status cards in one row with a uniform header.
    built: The title rule sits at different heights in each card: about 61 px in Final Submit, 75 px in By channel, 77 px in Live CPR and 84 px in Login and submit status. Login and submit status wraps onto two lines (ref 'Login/Submit Status'), and 'Details >' appears on some cards only.
    fix: Use a fixed header height for status cards (single-line title, truncate with a tooltip), and keep the 'Details >' link on the same baseline in every card.
- (low, web-dashboard) Duplicate page title
    ref: One page title, 'Dashboard' in blue, in the top bar.
    built: The top bar has a small blue 'Dashboard' and the page body repeats a large dark-navy 'Dashboard' H1 with a date line below it.
    fix: Keep the blue title in the top bar and drop the second H1, or make the top-bar title the only large element and keep the date line as a subtitle.
- (low, web-dashboard) Sidebar footer and header controls
    ref: Red button labelled 'Logout' and a double chevron '<<' collapse control at the top right.
    built: The button reads 'Sign out' with a log-out icon and a white outline. The collapse control is a single '<' chevron.
    fix: Rename the label to 'Logout' (and the Bangla equivalent already used in the app) and use a double chevron for collapse.
- (low, web-dashboard) Sidebar icons
    ref: White line icons.
    built: Mostly solid filled glyphs (Dashboard grid, flag, graduation cap, bar chart). Six Reports entries all use the same bar-chart icon, so they are hard to tell apart.
    fix: Switch to one line-icon set at a uniform 1.75 px stroke and give each Reports entry its own icon.
- (low, web-dashboard) Card footers
    ref: No per-card date footer is described in the owner's note.
    built: Every card repeats '19 October 2026' and 'As of 7 Oct 2026, 17:00'. The two dates contradict each other on one screen (19 Oct versus as of 7 Oct), which reads as a stale or broken snapshot and adds clutter.
    fix: Show one 'As of' stamp once in the page header and remove it from the cards. If the snapshot really is older than today, say so once with a clear stale badge.
- (low, web-dashboard) Filter button
    ref: An orange Filter button with a list icon at the top right.
    built: A pale cream-orange fill (#FFE9C7) with an orange outline and dark text. Cannot tell if the owner's button is solid orange.
    fix: If the reference is a solid orange button, use solid orange (about #F59E0B) with white text and keep the list icon.
- (low, web-dashboard) Login/Submit Status labels
    ref: 'Login Status 99.9%' and 'Submit Status 99.8%' with the paired counts 'Target Route / Successful Login' and 'Login successfully / Submitted successfully'.
    built: 'Login %' 75.0% and 'Submit %' 66.7%, each with a single caption sentence ('3 of 4 routes logged in', '2 of 3 logged-in routes'). The bars and the download and upload icons are present.
    fix: Rename the labels to Login Status and Submit Status and show the two paired counts under each bar using the reference wording.
- (low, web-dashboard) Live CPR card
    ref: Green ring with 'Target Outlet' and 'Successful Calls' counts at its left.
    built: The left column labels 'Planned outlets' and 'Successful calls' each wrap onto two lines. There is an extra 'Outlets visited 108/141' bar and a large empty gap above the footer. The ring is thinner and smaller than the Geo fencing ring (inconsistent ring stroke across cards).
    fix: Widen the label column or reduce the font so each label stays on one line. Use one shared ring stroke for the Live CPR, Final Submit and Geo rings.
- (low, web-dashboard) Row 5 extra cards (Net sales, Strike rate, Needs attention)
    ref: The reference note describes no such cards (they are additions).
    built: The large Net sales card is mostly empty below its figure. It shows '543,435.00 ৳' in full taka with a trailing symbol while every other card uses Lakh format (the Net sales total is 5.43 Lakh in the channel card).
    fix: Format with the same Lakh rule as the other cards, put the ৳ symbol before the number, and shrink the card or add a trend sparkline to use the space.
- (low, web-dashboard) Sidebar height in the full-page capture
    ref: Sidebar full height.
    built: The gradient sidebar ends at the first 910 px of a 2642 px page and the grey page shows below it. Cannot tell if the sidebar is position-sticky to the viewport in a real browser.
    fix: Confirm the sidebar is fixed or sticky at 100vh. If it is only the first viewport, make it span the full document height or stay pinned while scrolling.