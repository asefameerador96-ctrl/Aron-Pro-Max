# 15. Check in

Source image: `15-check_in.jpg`. Manual section 7 (Attendance), screenshot 15.

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, Attendance (অ্যাটেনডেন্স) screen, same user (AMO-5756).
- **Left**: after tapping চেক ইন. A white bottom sheet slides up over the Attendance screen showing "চেক ইন করা হচ্ছে", the time and a large green "press and hold" half-circle button. The Attendance screen behind it is dimmed/partly covered (check-in green tile and grey check-out tile are visible at the top edge, cut by the sheet).
- **Right**: after check-in is done. Attendance screen with a "check-in done" box and a disabled (orange-warning) "Check Out will be active after 5" box.
Neither is a system dialog.

## Layout, top to bottom
Left screen:
1. Top bar: flat deep purple, white back arrow at left, white bold title "অ্যাটেনডেন্স" centred.
2. User row: pale-lavender rounded square with purple person icon; purple name "AMO-5756 (amo5756)"; smaller purple line "Savar BazarAMO, 2025-12-02".
3. Thin purple rule.
4. Location card (pale grey-lavender, soft shadow): purple map-pin in lavender square, address text over three lines, purple refresh icon at the right.
5. Message in black Bangla text, two lines: not yet checked in.
6. Two tiles, mostly covered by the sheet: green gradient "চেক ইন" tile (left) and grey gradient "চেক আউট" tile (right); only their top halves and the icons/top of labels are visible.
7. Bottom sheet, white, rounded top corners, with a small grey drag handle (short horizontal bar) at top centre.
8. In the sheet: green bold Bangla heading "চেক ইন করা হচ্ছে" (Checking in).
9. Under it a rounded rectangle with a thin green border containing a green clock icon and the time "04:05 PM" in green.
10. At the bottom of the sheet, a very large **green half-circle (dome) button**, gradient from pale green at the lower left to strong green at the upper right, with a white "enter door" icon above white text "চাপ দিয়ে ধরে রাখুন". It is cut off by the bottom of the screen (only the upper part of the circle shows).
11. Gesture bar at the very bottom.

Right screen:
1. Same top bar, user row, rule and location card (same user and address).
2. Message: "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।"
3. Two dashed-border status boxes side by side (neither is a solid button):
   - Left: dashed green/teal border, green circle-check icon above green text "চেক ইন সম্পন্ন হয়েছে" over two lines.
   - Right: dashed orange/amber border, orange warning triangle icon (triangle with exclamation mark) above orange text "চেক আউট ৫টার পরে সক্রিয় হবে" over two lines. State: disabled/not yet active.
4. Rest of the page empty, light lavender. Gesture bar at the bottom.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Top bar title, both screens |
| AMO-5756 (amo5756) | AMO name and login ID | User row, both |
| Savar BazarAMO, 2025-12-02 | Area/role text and date | User row second line, both |
| 4th Floor), 28, 4th Floor), Gulshan, Dhaka, Dhaka District, Dhaka Division, 1213, Bangladesh | Current address | Location card, both |
| আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | You have not checked in yet. Please check in before starting work. | Message, left |
| আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Please check out before finishing today's work. | Message, right |
| চেক ইন | Check In | Green tile label (partly hidden), left |
| চেক আউট | Check Out | Grey tile label (partly hidden), left |
| চেক ইন করা হচ্ছে | Checking in (check-in in progress) | Sheet heading, left |
| 04:05 PM | Current time, 12-hour with AM/PM, Latin digits, leading zero | Time chip in sheet, left |
| চাপ দিয়ে ধরে রাখুন | Press and hold | Green dome button, left |
| চেক ইন সম্পন্ন হয়েছে | Check-in completed | Dashed box, right |
| চেক আউট ৫টার পরে সক্রিয় হবে | Check Out will be active after 5 o'clock | Dashed orange box, right |

Digits: "৫" in "৫টার" is the Bangla digit five. The time "04:05 PM" and the date/IDs are Latin digits.

## Data and figures shown
- 04:05 PM is the time that will be recorded as the check-in time (the phone's current time when the sheet opened).
- "৫টার পরে" = "after 5 o'clock" (5 PM, per the manual): the Check Out cut-off time.
- Left screen user and date are identical to those in screenshot 14, left.

## Colours and style
- Top bar deep purple, page background very light lavender, location card pale grey-lavender with soft shadow.
- Sheet: white, large rounded top corners (~28 px), grey drag handle.
- Heading and time chip: green (about #00A651); chip has a thin green outline and rounded corners, a clock icon.
- Hold button: large green gradient circle (light green to strong green), white icon and text.
- Done box: dashed green border, green text; warning box: dashed orange border (about #F57C00), orange triangle icon and text.
- No packshots.

## Navigation and behaviour
- From the Attendance screen, tap চেক ইন: the bottom sheet slides up, shows the heading and the current time.
- Check the time, then **press and hold** the large green button until the check-in completes (a hold, not a tap, to avoid accidental check-in; the hold progress animation is not visible in the image).
- After completion the Attendance screen shows "চেক ইন সম্পন্ন হয়েছে" and a disabled Check Out box with the text "চেক আউট ৫টার পরে সক্রিয় হবে": Check Out becomes active at 5 PM.
- The sheet has a drag handle, so it can presumably be swiped down to cancel (not stated in the manual).

## Differences between the image and the manual text
- The manual says "the Check Out button is not active yet (it becomes active at 5 PM)". In the image it is not a button at all: it is a dashed orange warning box with the Bangla text "চেক আউট ৫টার পরে সক্রিয় হবে", using the Bangla digit ৫.
- The manual does not mention the sheet heading "চেক ইন করা হচ্ছে", the time chip format (04:05 PM) or that the hold button is a half-dome.
- The left screen shows 04:05 PM, which is before 5 PM, consistent with check out being inactive.

## Open questions for the owner
- Hold duration for the press-and-hold button is not visible (assume about 1 to 2 seconds); confirm.
- Does the sheet capture location or a photo during check-in? Nothing in the image shows it.
