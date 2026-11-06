# 74. Journey timeline

## Screens in the image
Single phone screen (portrait), vendor ARON AMO app: the Sales Journey timeline of one SR for today (opened by tapping a card on screenshot 73). Status bar (one line): 5:30, Banglalink, 4G, battery 39%. At the very bottom a translucent dark-grey strip crosses the screen (the Android navigation-bar area/overlay), cutting off the fourth card.

## Layout, top to bottom
1. Top bar: flat deep purple, white back arrow at left, centred white bold Bangla title "বিক্রয় যাত্রা".
2. Body: pale lavender-to-white vertical gradient background (lavender stronger toward the bottom). A vertical timeline runs down the left side: a thin purple line (about 3 px, medium purple) with a white circular node on it for every call; each node contains a purple outline store/shop (awning) icon.
3. Call card 1 (to the right of the first node): white rounded card with shadow.
   - Row 1: empty grey radio circle (unselected) + "New Sale" in dark text.
   - Row 2: outlet name in bold purple, wraps to two lines: "Unite Tea Store (2672261-1856213762-Fojle Rabbi Park)".
   - Row 3: grey clock icon + "9:43:04 AM - 9:48:47 AM".
   - Row 4: grey stopwatch icon + "৫ মিনিট  ৪২ সেকেন্ড" (Bangla digits).
   - No distance row on this first card.
4. Gap marker between cards: on the timeline line, a small black walking-person icon with text "৮ মিনিট ৫৬ সেকেন্ড পরে" (dark purple/blue-grey, Bangla digits).
5. Call card 2: radio circle + "New Sale"; purple bold name "Rana Tea Store (2645667-1609690515-Kachabazar Er Samne)"; clock row "9:57:43 AM - 10:05:30 AM"; stopwatch row "৭ মিনিট  ৪৬ সেকেন্ড"; bottom row split left and right: left a small shop icon + "দূরত্ব: ২৭৭.৩২ মি", right a small route/path icon + "মোট: ২৭৭.৩২ মি" (both teal/slate-blue text, Bangla digits).
6. Gap marker: walking icon + "৪৫ সেকেন্ড পরে".
7. Call card 3: "New Sale"; "Kalam Store (2585581-1922630443-Kachabazar Road)"; "10:06:15 AM - 10:18:31 AM"; "১২ মিনিট  ১৫ সেকেন্ড"; "দূরত্ব: ১১৪.২২ মি" left, "মোট: ৩৯১.৫৪ মি" right.
8. Gap marker: walking icon + "১৩ মিনিট ৫৯ সেকেন্ড পরে".
9. Call card 4 (cut off at the bottom): radio circle + "New Sale"; the name line is partly hidden by the dark strip and reads approximately "Sub Alam? Store (1760517-1793430781..." (unreadable beyond that). Its node (store icon) is visible at the left.
10. Cards are in time order, earliest at top. No bottom bar of the app, no buttons.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় যাত্রা | Sales Journey | Top bar title |
| New Sale | Sale status of the call (English even in Bangla mode) | Row 1 of every card, next to the radio circle |
| Unite Tea Store (2672261-1856213762-Fojle Rabbi Park) | Outlet name (outlet code - phone/id number - cluster) | Card 1 name |
| Rana Tea Store (2645667-1609690515-Kachabazar Er Samne) | Outlet name (code-number-cluster) | Card 2 name |
| Kalam Store (2585581-1922630443-Kachabazar Road) | Outlet name (code-number-cluster) | Card 3 name |
| Sub Alam? Store (1760517-1793430781... (partly hidden) | Outlet name, fourth call, cut off | Card 4 name; unreadable after "1793430781" |
| 9:43:04 AM - 9:48:47 AM | Call start - end time (Latin digits, 12-hour, seconds shown) | Card 1 |
| 9:57:43 AM - 10:05:30 AM | Call start - end | Card 2 |
| 10:06:15 AM - 10:18:31 AM | Call start - end | Card 3 |
| ৫ মিনিট  ৪২ সেকেন্ড | 5 min 42 sec (call duration) | Card 1 |
| ৭ মিনিট  ৪৬ সেকেন্ড | 7 min 46 sec | Card 2 |
| ১২ মিনিট  ১৫ সেকেন্ড | 12 min 15 sec | Card 3 |
| ৮ মিনিট ৫৬ সেকেন্ড পরে | after 8 min 56 sec (walking time to next outlet) | Between cards 1 and 2 |
| ৪৫ সেকেন্ড পরে | after 45 sec | Between cards 2 and 3 |
| ১৩ মিনিট ৫৯ সেকেন্ড পরে | after 13 min 59 sec | Between cards 3 and 4 |
| দূরত্ব: ২৭৭.৩২ মি | Distance: 277.32 m (from previous call's outlet) | Card 2, bottom left |
| মোট: ২৭৭.৩২ মি | Total: 277.32 m (cumulative today) | Card 2, bottom right |
| দূরত্ব: ১১৪.২২ মি | Distance: 114.22 m | Card 3, bottom left |
| মোট: ৩৯১.৫৪ মি | Total: 391.54 m | Card 3, bottom right |

## Data and figures shown
- Time ranges are Latin digits with AM/PM; durations, gaps and distances are Bangla digits (০১২৩৪৫৬৭৮৯), decimal point is a Latin ".", unit "মি" (metres). No thousand separators appear.
- Duration check: 9:43:04 to 9:48:47 = 5 min 43 s displayed as 5 min 42 s (rounded down by the app); 9:57:43 to 10:05:30 = 7 min 47 s displayed 7 min 46 s; 10:06:15 to 10:18:31 = 12 min 16 s displayed 12 min 15 s (all off by 1 s, likely truncation of milliseconds).
- Gap check: card 1 ends 9:48:47, card 2 starts 9:57:43 = 8 min 56 s (matches "৮ মিনিট ৫৬ সেকেন্ড পরে"); card 2 ends 10:05:30, card 3 starts 10:06:15 = 45 s (matches); card 3 ends 10:18:31 and card 4 starts after 13 min 59 s (about 10:32:30).
- Distance: দূরত্ব = distance from the previous call's outlet; মোট = running total. 277.32 + 114.22 = 391.54 m (matches). Card 1 shows no distance (it is the first call), and the first running total appears to start at card 2.
- The outlet name pattern is "Name (outletCode-number-cluster)". The second number (10 digits) looks like a phone/ID number; unclear.

## Colours and style
- Top bar deep purple, white title. Page background light lavender gradient (paler at top, deeper lavender lower).
- Timeline line: medium purple, thin; nodes: white circles with purple store icon.
- Cards: white, rounded (about 16 px), soft shadow, inset right of the timeline.
- Outlet name: bold purple (about #4A1A96). Clock and stopwatch icons and their text: grey. Distance/total row text: teal-slate blue-grey with matching small icons. Gap text: dark slate with a black walking icon.
- Radio circle: grey outline, empty. The manual says Edited Sale cards are pink later in the list: not visible here, so New Sale cards are white.
- No packshots.

## Navigation and behaviour
- Back arrow returns to the SR list (screenshot 73).
- Scroll down to follow the whole day. Cards are read-only in the image (no visible tap target).
- Use: look for very short calls, long gaps between calls and long distances and discuss with the SR (manual).
- Sale status: "New Sale" (white card); Edited Sale appears in pink (per manual, not visible here).

## Differences between the image and the manual text
- The manual's example "(code-phone-cluster)" is shown as three hyphen-joined parts; the middle part is 10 digits starting with 1 (e.g. 1856213762), which is not a Bangladeshi mobile format, so it is likely an ID, not a phone.
- The manual does not mention the empty radio circle next to "New Sale", the store-icon nodes on the timeline, or that the first call has no দূরত্ব/মোট row.
- The manual says the example of Edited Sale pink; not visible in this image.
- The status label "New Sale" is English although the app is in Bangla mode.
- Image shows the duration with a double space between minutes and seconds ("৫ মিনিট  ৪২ সেকেন্ড") while gaps use a single space.

## Open questions for the owner
- Does the second number in the outlet name (e.g. 1856213762) mean a phone number, a base ID or something else?
- What other statuses besides "New Sale" and "Edited Sale" can appear (e.g. no-sale, closed)?
- Is the first running total supposed to start at the first call (it shows nothing) or at the second?
