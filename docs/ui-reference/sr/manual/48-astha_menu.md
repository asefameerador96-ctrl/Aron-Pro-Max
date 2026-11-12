# 48. Astha menu

## Screens in the image
Two phone screens side by side, both titled আস্থা (Astha). Left: the Astha menu with two tiles. Right: the টার্গেট (Target) view with two tabs and Year/Quarter selectors, in an empty state.

## Layout, top to bottom
Left screen (3:15, battery 67):
1. Header red-to-purple gradient, back chevron, centred white title "আস্থা" (no icon).
2. Page light grey. Two white-grey tiles side by side, each about 12 dp radius with a thin light border: left tile has the round dark-brown/gold Astha logo (with red balls and the word আস্থা inside) and the red label "টার্গেট"; right tile has a pink/purple gift box icon and the red label "গিফট নির্বাচন।" (with a trailing danda).
3. On the right edge, a white vertical pill (about 80 px wide) with three dots "..." at the top and a music note with sound waves at the bottom. It looks like a system or overlay widget (a screenshot/media overlay), not part of the app (an inference).

Right screen (3:15, battery 67):
1. Same header "আস্থা".
2. Segmented tab bar on a pale grey strip: selected tab "রুটের তথ্য" (white text on a red rounded rectangle), unselected "দোকান ভিত্তিক তথ্য" (dark text). A thin lavender underline runs below.
3. Two bold labels: "বছর নির্বাচন করুন" (left) and "কোয়ার্টার নির্বাচন করুন" (right).
4. Two drop-downs with dark outline, rounded: left shows grey "বছর" and a black arrow; right shows grey "কোয়ার্টার" and a lighter grey arrow (looks disabled until a year is chosen; an inference).
5. Centre of page: dark text "তথ্য পাওয়া যায়নি".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| আস্থা | Astha | Header, both screens |
| টার্গেট | Target | Left tile label, red |
| গিফট নির্বাচন। | Gift selection | Right tile label, red, with a trailing danda |
| রুটের তথ্য | Route info | Selected tab, right screen |
| দোকান ভিত্তিক তথ্য | Shop-wise info | Unselected tab |
| বছর নির্বাচন করুন | Select year | Label |
| কোয়ার্টার নির্বাচন করুন | Select quarter | Label |
| বছর | Year | Drop-down placeholder |
| কোয়ার্টার | Quarter | Drop-down placeholder |
| তথ্য পাওয়া যায়নি | No data found | Empty state |
| আস্থা (inside logo) | Astha | Logo image |
| ... (three dots) | More | Overlay pill |
| 3:15 / 67 | Clock / battery | Status bar |

## Data and figures shown
- No figures: the Target screen is empty ("তথ্য পাওয়া যায়নি") because no year/quarter is selected yet.
- Targets and loyalty/gifts are a deferred programme (docs/27), so this is reference only.

## Colours and style
- Header: red (about #D94A4A) to purple (about #4A1A9A) gradient, white title.
- Menu tiles: very light grey (about #F4F4F4), light border; labels red (about #D03030).
- Astha logo: dark brown/maroon disc with gold ring and red accents (raster image).
- Gift icon: pink-to-violet (about #C040E0).
- Selected tab: red (about #D94545) with white text; unselected tab: transparent, dark text.
- Drop-downs: pale grey fill, dark (about #333) 1 dp outline, about 14 dp radius.

## Navigation and behaviour
- Manual: two tiles, টার্গেট (target) and গিফট নির্বাচন (gift selection). Target opens "রুটের তথ্য" (Route info) and "দোকান ভিত্তিক তথ্য" (Shop-wise info) with year and quarter. The SR taps টার্গেট.
- The right screen is the Target view: tab choice, then year, then quarter, then data (not shown).
- The Gift selection screen is not in this image.

## Differences between the image and the manual text
- The manual calls the right tile "গিফট নির্বাচন"; the image has a trailing "।".
- The manual does not mention the overlay pill on the right edge of the left screen.
- The manual's year/quarter selectors are shown, but only in the empty state, with no figures.

## Open questions for the owner
- What do the Route info and Shop-wise info tables contain once a year and quarter are chosen (docs/27 defers targets, so keep hooks only)?
- Is the vertical pill with the music note part of the app or a phone overlay? It looks like the latter.
