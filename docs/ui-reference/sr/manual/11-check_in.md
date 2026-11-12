# 11. Check in

## Screens in the image
Two phone screens side by side (no status bars), both the SR "অ্যাটেনডেন্স" screen before check-in. Left: the idle screen with a green চেক ইন and a grey, disabled চেক আউট. Right: the same screen after tapping চেক ইন, with a white bottom sheet "চেক ইন করা হচ্ছে" holding the time and a big green press-and-hold button (the sheet is cut off at the bottom of the crop).

## Layout, top to bottom
1. Header, red-to-purple horizontal gradient. White back chevron at the left. Centre: the small calendar-with-clock icon followed by white bold "অ্যাটেনডেন্স".
2. Light grey page. Identity row: a rounded lavender square with a purple person icon; to its right bold "SR-16858 (sr16858)" and below it a small line "Savar Bazar(Sat, Mon, Wed), 2025-11-26".
3. A thin red horizontal line.
4. Location row: a soft grey shadowed card with a lavender square holding a purple map pin, the address text on three lines "Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh", and a red circular refresh arrow at the right.
5. Centred two-line Bangla message in dark text.
6. Two large rounded buttons side by side. Left: green gradient (dark green to light green), white icon (arrow into a door) and label "চেক ইন". Right: grey gradient (dark to light grey), white icon (arrow out of a door) and label "চেক আউট" (disabled look).
7. Rest of the page empty.
Right screen, in addition: the buttons are covered by a white bottom sheet with large rounded top corners and a small grey drag handle:
   - Green bold title "চেক ইন করা হচ্ছে".
   - A green-outlined rounded rectangle with a clock icon and "04:47 PM".
   - A very large half-circle green gradient button (cut off at the bottom) with a white check-in icon and white text "চাপ দিয়ে ধরে রাখুন".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Header title (both screens) |
| SR-16858 (sr16858) | SR name and ID (example) | Identity row line 1 |
| Savar Bazar(Sat, Mon, Wed), 2025-11-26 | Route name with its visit days, and today's date (Latin digits yyyy-mm-dd) | Identity row line 2 |
| Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh | Current address from the phone location (reverse-geocoded) | Location card |
| আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | You have not checked in yet. Please check in before starting work. | Centre message |
| চেক ইন | Check in | Green button (and sheet icon) |
| চেক আউট | Check out | Grey button |
| চেক ইন করা হচ্ছে | Checking in (in progress) | Sheet title (right screen) |
| 04:47 PM | Current time, 12-hour with AM/PM, Latin digits | Sheet time chip |
| চাপ দিয়ে ধরে রাখুন | Press and hold | Big green button (right screen) |

## Data and figures shown
- User: SR-16858 (sr16858). Route "Savar Bazar" with visit days Sat, Mon, Wed. Date 2025-11-26.
- The address line is the phone's current position as text; the refresh arrow re-reads it.
- Time 04:47 PM on the sheet is the phone clock at the moment of check-in (a late check-in in this example).

## Colours and style
- Header red-to-purple gradient; page light grey (#E8E8E8-ish); dark text.
- Check-in: green gradient (about #0FA94F to #6CC18B); check-out disabled: grey gradient; the thin divider is red.
- Sheet: white, large corner radius, green title and green time chip with a green outline.
- Bangla text in the app's Bangla font; Latin text (name, route, address) in a grotesque sans.
- Icon squares in lavender with purple glyphs.

## Navigation and behaviour
- Tap চেক ইন opens the bottom sheet with the live time; press and hold the big green button to confirm (a hold prevents accidental taps).
- Tap the red refresh arrow to re-read the address.
- Back chevron returns to the Dashboard.
- Check-out stays grey (disabled) until the check-in is done; before 5 PM it is not available either (see screenshot 12).

## Differences between the image and the manual text
- The manual says "Buttons চেক ইন / চেক আউট": matches. It does not say the check-out button is greyed out before check-in.
- The manual says "Right: the time and a big green press and hold": matches; the sheet title "চেক ইন করা হচ্ছে" and the drag handle are not mentioned.
- The manual says "current address with refresh": matches; the image shows a long reverse-geocoded address in English, not a name from the outlet list.
- The image identity row shows the route with its visit days in brackets, the manual says only "name, route".

## Open questions for the owner
- Is the check-in time taken from the phone clock or from the server, and what if the phone clock is wrong?
- Is there a latest allowed check-in time?
- Is the position at check-in stored, and does a failed address lookup (no network) block check-in?
