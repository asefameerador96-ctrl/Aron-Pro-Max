# 14. Attendance screen

Source image: `14-attendance_screen.jpg`. Manual section 7 (Attendance), screenshot 14.

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, both the **অ্যাটেনডেন্স (Attendance)** screen. Different test accounts.
- **Left**: before check-in. User "AMO-5756 (amo5756)". Check In (green) active, Check Out (grey) disabled.
- **Right**: after check-in. User "AMO-Dariapur (amo5864)". A dashed "check-in done" box and the active red Check Out button. This one includes the Android status bar and the three-button navigation bar.
Neither is a system dialog.

## Layout, top to bottom
1. Top bar: flat deep purple, white back arrow (rounded left-pointing triangle outline) at the left, white bold Bangla title "অ্যাটেনডেন্স" centred. (Right screen also shows the status bar on top: one line, clock 5:07, location pin, network speed, 4G signal, battery 59.)
2. User card (no box, on the light page): left a small rounded square in pale lavender with a purple person icon; right of it the user name and ID in purple medium text; under it a smaller purple line with the area/role and a date.
3. A thin purple horizontal rule across the page under the user row.
4. Location card: a rounded, pale grey-lavender card with a soft shadow. Left a pale-lavender rounded square holding a purple map-pin icon; middle the current address as dark grey text over three lines; right a purple circular refresh arrow icon (refresh button).
5. Status message, centred, black Bangla text, two lines, below a gap:
   - Left: "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।" (You have not checked in yet. Please check in before starting work.)
   - Right: "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" (You have not checked out yet. Please check out before finishing today's work.)
6. Two buttons side by side, each a rounded tile, below the message:
   - Left screen: (a) green tile, left, with horizontal green gradient (strong green to lighter green), a white "enter/arrow into door" icon above white text "চেক ইন" (enabled); (b) grey tile, right, grey gradient (dark grey to light grey), a white "arrow out of door" icon above white text "চেক আউট" (disabled look).
   - Right screen: (a) left tile is a dashed-border outline box (thin dashed green border, white/transparent fill) with a green circle-check icon above green text "চেক ইন সম্পন্ন হয়েছে" over two lines (status, not a button); (b) right tile is a red tile with gradient (strong red to lighter rose-red), white "arrow out of door" icon above white text "চেক আউট" (enabled).
7. The rest of the page below is empty light lavender-white. Bottom of left screen: the gesture bar; bottom of right screen: black three-button Android navigation bar (menu, home, back, grey icons).

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Top bar title, both |
| AMO-5756 (amo5756) | AMO display name and login ID | User row, left |
| Savar BazarAMO, 2025-12-02 | Area/role text and a date, shown with no separator between "Bazar" and "AMO" (looks like area name "Savar Bazar" joined to role "AMO", then date) | User row second line, left |
| AMO-Dariapur (amo5864) | AMO display name and login ID | User row, right |
| AMO-DariapurAMO, 2026-10-26 | Area/role text and a date (same joined pattern) | User row second line, right |
| 4th Floor), 28, 4th Floor), Gulshan, Dhaka, Dhaka District, Dhaka Division, 1213, Bangladesh | Reverse-geocoded current address (note the stray ")" and the duplicated "4th Floor)" as shown) | Location card, left |
| 8GHQ+MXF, 8GHQ+MXF, Gaibanda, Gaibandha District, Rangpur Division, Bangladesh | Reverse-geocoded address, begins with a Plus Code; "Gaibanda" and "Gaibandha" spellings both as shown | Location card, right |
| আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | You have not checked in yet. Please check in before starting work. | Message, left |
| আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Please check out before finishing today's work. | Message, right |
| চেক ইন | Check In | Green button, left |
| চেক আউট | Check Out | Grey button (left); red button (right) |
| চেক ইন সম্পন্ন হয়েছে | Check-in completed | Dashed status box, right |
| 5:07 | Phone clock | Status bar, right screen |
| 12.0 KB/S, 4G, 59 | Network speed, network type, battery % | Status bar, right screen |

Digits: the dates (2025-12-02, 2026-10-26), IDs (5756, 5864) and postal code (1213) are Latin digits. No Bangla digits on this image. The date format is YYYY-MM-DD with hyphens.

## Data and figures shown
- User row: name, login ID in brackets, then area-or-role plus a date. Per the manual: "Your name and ID". The date's meaning is not explained in the manual (it could be a join/assignment date); the two screens show different dates (2025-12-02 and 2026-10-26).
- Location card: the phone's current address from reverse geocoding (the manual says "your current location (address) with a refresh button").
- Right screen address is in Gaibandha (Rangpur Division), while the AMO name says Dariapur: a test-account mismatch, example data only.

## Colours and style
- Top bar deep purple (about #4A148C), white title. Page background very light lavender/near white.
- User name text in purple (about #4A148C), secondary line smaller purple. Icon tiles pale lavender (about #EDE7F6) with purple glyphs.
- Location card: pale grey-lavender with a soft diffuse shadow, rounded ~16 px; refresh icon purple.
- Check In tile: green gradient (about #00A651 to a lighter green), white icon and text; rounded corners (~10 px), soft shadow.
- Check Out disabled tile: grey gradient (dark grey to light grey), white icon and text.
- Check Out active tile: red gradient (about #D32030 to a lighter rose), white icon and text.
- Done box: dashed green border, green check circle and green text, no fill.
- No packshots on this screen.

## Navigation and behaviour
- Open Attendance from the Dashboard.
- Left state (not checked in): tapping চেক ইন opens the check-in sheet (screenshot 15); চেক আউট is greyed out and not usable.
- Right state (checked in): the left box confirms check-in; চেক আউট is the red active button and leads to the check-out sheet (screenshot 16). (Screenshot 15 shows that before 5 PM it is not active; the right image here shows it red/active, consistent with a time after 5 PM; the clock shows 5:07.)
- Refresh icon: if the location is wrong or empty, tap it to re-read the current address.
- Back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The manual does not mention the second line under the name (area/role plus date), the grey colour of the disabled Check Out button, or the dashed "চেক ইন সম্পন্ন হয়েছে" box being a status box rather than a button.
- The manual says right screen shows "the red Check Out button, with the reminder"; consistent with the image. It does not say the red button is active because the clock is 5:07 (after 5 PM); this is inferred from screenshot 15.
- The two screens come from different users and dates, so the examples differ.

## Open questions for the owner
- What does the date after the area name in the user row mean (join date, assignment date, today's date)? Left shows 2025-12-02, right 2026-10-26.
- Why is the area name joined to "AMO" with no space or separator ("Savar BazarAMO")? Likely a concatenation of name and designation; confirm the intended display.
