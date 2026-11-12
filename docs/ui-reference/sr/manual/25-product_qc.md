# 25. Product QC

## Screens in the image
Two phone screens side by side, same page. Left: the SR app QC page (empty state, no QC lines yet). Right: the same page dimmed behind a confirmation dialog asking whether to submit QC.

## Layout, top to bottom
1. Status bar: left "3:10" (right panel "3:11") and a screen-capture icon; right: location pin, "4G", two signal bars, battery 68.
2. Top bar, red-to-purple horizontal gradient (red at left to deep violet at right). Back chevron at left, centred white title "QC".
3. Page background light grey.
4. White rounded info card with a dark "i" icon at left and a two-line grey instruction text.
5. A pale lavender rounded panel holding a horizontally scrolling row of pack tiles. Each tile is a rounded light square with the packshot above and the SKU code below in purple. Three tiles are fully visible (two blue Maxim X-Bolt packs, one green Maxim Wild Berry pack); a fourth (an "AVC" pack, white/cream) is cut off at the right edge.
6. Section heading "QC সারাংশ" (left, dark, larger).
7. Empty-state line, centred: "QC করার জন্য কোন পণ্য নেই".
8. Bottom bar, solid deep purple, holding a red-to-purple gradient button at right: "QC জমা দিন" with a right arrow.
9. Android 3-button navigation bar (grey) under it.
10. Right panel only: dim overlay and a centred white rounded dialog: blue circle with white "i" at top, blue bold title "ইনফর্মেশন", black body text, two buttons side by side (green "হ্যাঁ", red "না").

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:10 / 3:11 | Phone clock | Status bar (left / right panel) |
| 68 | Battery level | Status bar |
| QC | Quality control (page title, Latin letters) | Top bar |
| QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন | To enter QC, select the product you want to add | Info card |
| MaxB-10S | SKU code: Maxim Bolt (blue), 10 sticks | Pack tile 1 |
| MaxB-20S | SKU code: Maxim Bolt (blue), 20 sticks | Pack tile 2 |
| MaxWB-10S | SKU code: Maxim Wild Berry, 10 sticks | Pack tile 3 |
| Av (cut off, "Avc" visible) | Start of the next SKU name, cut off; the pack shows "AVC" | Pack tile 4 (uncertain reading) |
| QC সারাংশ | QC summary | Section heading |
| QC করার জন্য কোন পণ্য নেই | No product to do QC for (empty summary) | Empty state |
| QC জমা দিন → | Submit QC | Bottom button |
| ইনফর্মেশন | Information | Dialog title (right panel) |
| আপনি কি QC জমা দিতে চান? | Do you want to submit QC? | Dialog body |
| হ্যাঁ | Yes | Dialog green button |
| না | No | Dialog red button |

Text on the packs (Maxim, X BOLT, Wild Berry, Bangla health warning "ধূমপানের কারণে স্ট্রোক হয়") is packshot artwork, not UI text.

## Data and figures shown
- SKU codes: MaxB-10S, MaxB-20S, MaxWB-10S, and a cut-off fourth (AVC pack).
- Summary is empty: no QC lines, no totals.
- Battery 68, 4G.

## Colours and style
- Header and bottom bar: red (#D94A4A-ish) to deep purple (#4A1C98-ish) horizontal gradient on the header; bottom bar solid deep purple with a gradient red-to-purple button.
- Page light grey (#E8E8E8-ish); info card white with thin grey border and large radius; pack panel pale lavender with soft shadow; pack tiles near-white with rounded corners; SKU codes purple.
- Dialog: white, large radius, blue title and icon, green and red filled buttons. Right panel is darkened by a scrim, so the header and bottom bar look near-black.
- Packshots are used for SKU selection.
- Empty state is plain centred grey text, no illustration.

## Navigation and behaviour
- The manual: tap প্রোডাক্ট QC on the review page, then choose the SKU here, enter faults (screenshot 26), tap QC জমা দিন.
- Tapping a pack tile opens the QC entry page (screenshot 26).
- Tapping QC জমা দিন opens the confirm dialog; হ্যাঁ submits, না dismisses.
- The pack row scrolls sideways.
- Whether the submit button is enabled while the summary is empty is not shown (it looks enabled in both panels).

## Differences between the image and the manual text
- The manual says "choose the SKU being returned"; the image's instruction says "select the product you want to add" (to QC).
- The manual's dialog text "Do you want to submit QC?" matches আপনি কি QC জমা দিতে চান?; the dialog title is ইনফর্মেশন.
- The manual does not mention the empty-state line "QC করার জন্য কোন পণ্য নেই" or that the pack row is scrollable with packshots.
- The submit button is shown even with an empty summary.
- The SKU shown in the right panel is the first-looking Maxim packs only (the manual does not list which SKUs are offered).

## Open questions for the owner
- Does QC জমা দিন work (or warn) when no QC line has been added?
- Is the list of QC-able SKUs the full SKU list or only SKUs sold in this memo?
