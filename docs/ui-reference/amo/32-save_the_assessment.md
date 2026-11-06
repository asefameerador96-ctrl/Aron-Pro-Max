# 32. Save the assessment

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app.
- LEFT: the SR Call Assessment screen (scrolled to the bottom) showing the Relationship and Service quality cards and the Save button. Vendor app; no status bar visible (cropped); a thin black gesture bar at the very bottom.
- RIGHT: the Joint Call activity screen with a modal success dialog over a dimmed (grey-black scrim) background. Vendor app (dialog is an in-app Flutter-style alert, not an Android system dialog). Black gesture line at the very bottom.

## Layout, top to bottom
LEFT screen:
1. Top bar: deep purple, white back arrow at left, white bold centred title "এসআর কল অ্যাসেসমেন্ট".
2. The page is scrolled: first the bottom of card 1 is cut at the top: partial text "করা, এনসিউর করা)" then a star row (star 1 dark blue, stars 2 to 5 grey) with red "Low" badge under star 1 and lime "উচ্চ" badge under star 5. This is the 5th item of the 5-step card.
3. Orange header strip "●রিলেসনশিপ এসেসম্যান্ট" on a white card; hint "(যা দেখতে হবে: সম্ভাষণ দেওয়া, দোকানদারের নাম জানা, দোকানদারের সাথে আন্তরিকতা)"; star row (1 blue, 4 grey); Low and উচ্চ badges.
4. Orange header strip "●সার্ভিস কোয়ালিটি এসেসম্যান্ট" on a white card; hint "(যা দেখতে হবে: টাইমলি দোকানে আসা, মেমো সঠিক ভাবে করা এবং সংরক্ষণ, দোকানদারের অভিযোগ সমাধান করা)" (three lines); star row (1 blue, 4 grey); Low and উচ্চ badges.
5. Save button: full-width, rounded rectangle, lime/yellow-green fill (#B2CC0E-like), white bold text with a white floppy-disk icon at the left: "সংরক্ষণ". Enabled. Sits at the bottom of the page just above the gesture bar.
RIGHT screen:
1. Top bar: deep purple (darkened by the scrim), back arrow, title "জয়েন্ট কল একটিভিটি" (dimmed).
2. Route field (dimmed): "Savar BazarDaily" with down arrow.
3. Outlet field (dimmed): "Babu Store (1618411-1723412771-Madrasa Road)" with down arrow.
4. Alphabet bar (dimmed dark grey): row 1: All (green, selected), A, B, C, D, E, F, G, H, I, J; row 2: K, M, N, P, R, S, T, V, Z.
5. Scrim over the whole screen (grey translucent).
6. Centred dialog: white rounded card (about 20 px radius, drop shadow). Content: green circle with white tick at the top centre; blue bold Bangla message "ডেটা সফলভাবে সংরক্ষিত হয়েছে।"; at the bottom right a green rounded button "ওকে" (white text, small shadow).
7. Behind the dialog no tile is visible (the tile area is hidden by the dialog or not yet drawn).

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| এসআর কল অ্যাসেসমেন্ট | SR Call Assessment | Left top bar |
| করা, এনসিউর করা) | (...) do it, ensure) end of item-5 hint | Left, cut-off text at top |
| Low | Lowest rating | Left, red badge, three times |
| উচ্চ | High | Left, lime badge, three times |
| ●রিলেসনশিপ এসেসম্যান্ট | Relationship assessment | Left, orange header |
| (যা দেখতে হবে: সম্ভাষণ দেওয়া, দোকানদারের নাম জানা, দোকানদারের সাথে আন্তরিকতা) | (Greeting, knowing the shopkeeper's name, warmth with the shopkeeper) | Left, card 2 hint |
| ●সার্ভিস কোয়ালিটি এসেসম্যান্ট | Service quality assessment | Left, orange header |
| (যা দেখতে হবে: টাইমলি দোকানে আসা, মেমো সঠিক ভাবে করা এবং সংরক্ষণ, দোকানদারের অভিযোগ সমাধান করা) | (Visiting on time, correct memo and keeping it, solving the shopkeeper's complaints) | Left, card 3 hint |
| সংরক্ষণ | Save | Left, lime-green button |
| জয়েন্ট কল একটিভিটি | Joint Call activity | Right top bar (dimmed) |
| Savar BazarDaily | Route (daily) | Right route field |
| Babu Store (1618411-1723412771-Madrasa Road) | Outlet name (id-mobile-address) | Right outlet field |
| All A B C D E F G H I J / K M N P R S T V Z | Alphabet filter | Right alphabet bar |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে। | Data saved successfully. | Right dialog message (ends with danda) |
| ওকে | OK | Right dialog button |
Digits Latin; no Bangla digits.

## Data and figures shown
- Left shows all visible ratings at 1 star (unchanged defaults in this capture).
- Right shows a different route and outlet from screenshots 30 and 31 (Savar BazarDaily, Babu Store, id 1618411, mobile 1723412771 without leading zero, Madrasa Road): the screenshots come from different sessions. No totals.

## Colours and style
- Top bar deep purple; page very light lavender-white; cards white rounded with soft shadow; orange (#E8600A-like) headers with white text.
- Stars: selected dark blue, others grey. Badges: red "Low", lime "উচ্চ".
- Save button: lime/yellow-green (the same as the উচ্চ badge colour), white text and floppy icon, full width, rounded about 6 px.
- Success dialog: white, large radius, green tick circle (#4CAF50), blue bold message, green OK button.
- Scrim: translucent dark grey over the whole screen including the top bar.
- No packshots.

## Navigation and behaviour
- Per the manual: tap সংরক্ষণ to save the seven ratings, then the success dialog appears; tap ওকে.
- After OK the dialog closes and the user is on the Joint Call outlet-selection screen (route and outlet still selected, alphabet bar), so another outlet can be chosen. The manual does not say more.
- The Save button is at the bottom of the scrolled page, not fixed.

## Differences between the image and the manual text
- The manual says the right screen shows only the success message; the image shows it as a modal over the Joint Call outlet-selection screen, with route, outlet and alphabet bar behind it, and a green tick icon.
- The Save button's colour (lime green) and floppy icon are not mentioned in the manual.
- The dialog message ends with a danda; Annex A has it without.

## Open questions for the owner
- After OK, does the app return to the outlet list (as the image suggests) or to the Dashboard? The image shows the Joint Call outlet screen behind the dialog, which implies it stays there.
- Is saving blocked when an item is not rated, or can the AMO save with defaults (all 1 star)? Not shown.
