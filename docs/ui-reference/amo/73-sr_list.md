# 73. SR list

## Screens in the image
Single phone screen (portrait), vendor ARON AMO app. It is the SR-route list that opens from the Sales Journey tile. Status bar (one line): 4:47, Banglalink dual SIM, 4G VoLTE, battery 43%.

## Layout, top to bottom
1. Top bar: flat deep purple (indigo-purple), full width. Left: white outlined back arrow (rounded triangle pointing left). Centre: white bold Bangla title "বিক্রয় যাত্রা". No right-hand icons.
2. Page body: very light lavender/white background, a vertical scrolling list of white rounded cards with soft shadow, small gap between cards, 16 px-ish side margin.
3. Each card (one per SR route), left to right:
   - Left: round grey-blue person avatar icon (head and shoulders in a circle).
   - Middle column, three lines: (a) SR name in UPPERCASE dark bold (e.g. FAZLUR RAHAMAN ANTOR); (b) route name in regular dark grey with working days in brackets (e.g. Tal Tola(Sun, Tue, Thu)); (c) a black phone-handset icon followed by the phone number in purple (e.g. 01952-582092).
   - Right column: grey label "মোট আউটলেট" (small), under it a purple number (total outlets on the route), then a grey chevron "›" at the far right edge.
4. Cards in order (9 visible, list continues no further in the image):
   1. FAZLUR RAHAMAN ANTOR / Gulshan-1Daily / 01952-582092 / 67
   2. AL-AMIN / Gulshan-2Daily / 01766-673937 / 70
   3. MD. ASADUL ISLAM / Tal Tola(Sun, Tue, Thu) / 01611-826244 / 91
   4. MD. ASADUL ISLAM / Poscim Merul(Sat, Mon, Wed) / 01611-826244 / 86
   5. MD. KAZI ARIFUR RAHMAN / Agora GoliDaily / 01718-356356 / 70
   6. MD. FARID MIA / Azad Mosjid(Sat, Mon, Wed) / 01981-844796 / 85
   7. MD. FARID MIA / Police Plaza(Sun, Tue, Thu) / 01981-844796 / 60
   8. MD. SHAKHAWAT HOSSAIN / Gulshan-2 DNCC Daily / 01730-272482 / 55
   9. Vacant / BaridharaDaily / "***********" (masked phone, 11 asterisks) / 58
5. Layout quirk: the right-hand block (label, number, chevron) is not fixed-width; it shifts left or right with the name length. For long names (ANTOR, ARIFUR RAHMAN, SHAKHAWAT HOSSAIN) the label "মোট আউটলেট" touches or overlaps the end of the name and the chevron is pushed against the screen edge. The same SR (ASADUL ISLAM, FARID MIA) appears twice, once per route. The Vacant card has a bigger-looking name font.
6. No bottom bar, no search box, no alphabet bar, no filter in the image.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় যাত্রা | Sales Journey | Top bar title |
| মোট আউটলেট | Total outlets | Every card, right block, small grey label |
| 67, 70, 91, 86, 70, 85, 60, 55, 58 | Total outlets per route (Latin digits, no separator) | Every card, purple number under the label, in card order 1 to 9 |
| FAZLUR RAHAMAN ANTOR | SR name | Card 1 |
| AL-AMIN | SR name | Card 2 |
| MD. ASADUL ISLAM | SR name | Cards 3 and 4 |
| MD. KAZI ARIFUR RAHMAN | SR name | Card 5 |
| MD. FARID MIA | SR name | Cards 6 and 7 |
| MD. SHAKHAWAT HOSSAIN | SR name | Card 8 |
| Vacant | Route has no SR | Card 9 |
| Gulshan-1Daily | Route name (no space before "Daily") | Card 1 |
| Gulshan-2Daily | Route name | Card 2 |
| Tal Tola(Sun, Tue, Thu) | Route with visit days | Card 3 |
| Poscim Merul(Sat, Mon, Wed) | Route with visit days | Card 4 |
| Agora GoliDaily | Route name | Card 5 |
| Azad Mosjid(Sat, Mon, Wed) | Route with visit days | Card 6 |
| Police Plaza(Sun, Tue, Thu) | Route with visit days | Card 7 |
| Gulshan-2 DNCC Daily | Route name (space before "Daily" here) | Card 8 |
| BaridharaDaily | Route name | Card 9 |
| 01952-582092 | SR phone | Card 1 |
| 01766-673937 | SR phone | Card 2 |
| 01611-826244 | SR phone (same for cards 3 and 4) | Cards 3, 4 |
| 01718-356356 | SR phone | Card 5 |
| 01981-844796 | SR phone (same for cards 6 and 7) | Cards 6, 7 |
| 01730-272482 | SR phone | Card 8 |
| *********** | Phone hidden because route is vacant | Card 9 |

## Data and figures shown
- Each card is one SR route, not one SR: an SR with two routes has two cards (ASADUL ISLAM 91 + 86 outlets; FARID MIA 85 + 60).
- The number is the count of outlets on that route (মোট আউটলেট): range 55 to 91 in the image.
- Phone numbers are Latin digits with a hyphen after the 5th digit (NNNNN-NNNNNN). Names and routes are Latin script (English), not Bangla. All numbers are Latin digits; no Bangla digits on this screen.
- Route names carry the schedule: "Daily" or a bracketed list of three weekdays.

## Colours and style
- Top bar: deep purple (about #4A1A96), flat, no gradient visible. Title white, bold Bangla.
- Page background: near-white with a faint lavender tint.
- Cards: white, rounded corners (about 12 px), soft drop shadow, full width.
- Name: near-black bold caps; route: dark grey regular; phone number: purple (about #4A1A96); outlet count: purple; label "মোট আউটলেট": mid grey; chevron: grey; avatar: slate-blue/grey person on light circle.
- No packshots, no badges, no progress bars.

## Navigation and behaviour
- Back arrow returns to the AMO dashboard.
- Per the manual: tap a card (›) to open that SR's journey for today (the timeline, screenshot 74). An SR with more than one route has one card per route. A vacant route shows "Vacant" and no phone number (masked asterisks in the image).
- The list shows the SRs of the AMO's area; no sorting or search is visible.

## Differences between the image and the manual text
- The manual titles the screenshot "SR list" and Annex A lists এসআর লিস্ট, but the top bar of this screen says "বিক্রয় যাত্রা" (Sales Journey), not এসআর লিস্ট.
- The manual says a vacant route shows "no phone number"; the image shows a phone icon with 11 asterisks (masked), and an outlet count of 58.
- The manual does not mention that route names include weekday lists like "(Sat, Mon, Wed)" for non-daily routes (it does give an example "Tal Tola (Sun, Tue, Thu)"), nor the English "Daily" suffix glued to the route name.
- The manual does not mention that the right-hand block overlaps long names.

## Open questions for the owner
- Is the masked phone on the Vacant card always 11 asterisks, or one per digit of a real stored number?
- Should the rebuild fix the overlap of the label with long SR names, or reproduce it? (Suggest fix, since layout bug.)
