# 16. Check out

Source image: `16-check_out.jpg`. Manual section 7 (Attendance), screenshot 16.

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app, Attendance (অ্যাটেনডেন্স) screen, same user (AMO 2, ss344002).
- **Left**: after tapping চেক আউট. A white bottom sheet slides up with "চেক আউট করা হচ্ছে", the time and a large red "press and hold" half-circle button. The Attendance screen behind shows a check-in done box and the red Check Out tile, partly covered.
- **Right**: day complete. Attendance screen with two dashed boxes "Already Checked-In" (green) and "Already Checked-Out" (red) and a thank-you message.
Neither is a system dialog.

## Layout, top to bottom
Left screen:
1. Top bar: flat deep purple, white back arrow, white bold title "অ্যাটেনডেন্স" centred.
2. User row: lavender rounded square with purple person icon; purple name "AMO 2 (ss344002)"; smaller purple line "BepariparaSS, 2025-11-01".
3. Thin purple rule.
4. Location card (pale grey-lavender, soft shadow): purple pin icon, address over three lines, purple refresh icon at right.
5. Message in black Bangla text, two lines: not yet checked out.
6. Two tiles, mostly covered: left a dashed green/teal-bordered box with a green circle-check icon and the text "Already ..." (cut off; continues "Checked-In"); right a red gradient tile with a white exit icon and the label "চেক আউট" (cut off).
7. White bottom sheet with rounded top corners and a grey drag handle.
8. Red bold Bangla heading "চেক আউট করা হচ্ছে" (Checking out).
9. Rounded rectangle with thin red border containing a red clock icon and the time "12:53 PM" in red.
10. Large **red half-circle (dome) button**, gradient from pale rose at the lower left to strong red at the upper right, white "exit door" icon above white text "চাপ দিয়ে ধরে রাখুন", cut off by the bottom of the screen.
11. Gesture bar.

Right screen:
1. Same top bar, user row ("AMO 2 (ss344002)", "BepariparaSS, 2025-11-01"), rule and location card (same address).
2. Message, black Bangla text, two lines: "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" (You have completed check-in and check-out for today. Thank you for your cooperation.)
3. Two dashed status boxes side by side (no button remains):
   - Left: dashed green/teal border, green circle-check icon, green text "Already Checked-In" over two lines (English).
   - Right: dashed pink/red border, red circle-check icon, red text "Already Checked-Out" over two lines (English).
4. Rest of the page empty. Gesture bar.

## Every visible text, verbatim
| Text as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Top bar title, both |
| AMO 2 (ss344002) | AMO display name and login ID | User row, both |
| BepariparaSS, 2025-11-01 | Area/role text (joined, no space) and a date | User row second line, both |
| 1a, 1a Lalbagh Rd, Lalbagh, Dhaka, Dhaka District, Dhaka Division, 1211, Bangladesh | Current address | Location card, both |
| আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Please check out before finishing today's work. | Message, left |
| Already | First word of "Already Checked-In" (cut off by the sheet) | Dashed box, left screen |
| চেক আউট | Check Out | Red tile label (partly hidden), left |
| চেক আউট করা হচ্ছে | Checking out (check-out in progress) | Sheet heading, left |
| 12:53 PM | Current time, 12-hour, Latin digits | Time chip, left |
| চাপ দিয়ে ধরে রাখুন | Press and hold | Red dome button, left |
| আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ। | You have completed check-in and check-out for today. Thank you for your cooperation. | Message, right |
| Already Checked-In | (English label) already checked in | Dashed green box, right |
| Already Checked-Out | (English label) already checked out | Dashed red box, right |

Digits: all Latin (IDs, dates, postal code 1211, time). No Bangla digits on this image.

## Data and figures shown
- 12:53 PM is the time that will be stored as the check-out time. Note it is BEFORE 5 PM, yet the sheet opened: see differences below.
- The two boxes on the right summarise today's attendance state (in and out both done).
- User, date, address are example data.

## Colours and style
- Top bar deep purple (about #4A148C), page very light lavender, location card pale grey-lavender with shadow.
- Sheet white with large rounded top corners, grey handle.
- Check-out accent: red (about #C62828): heading, time chip outline, clock icon, time text, the dome button gradient (rose to red).
- Done boxes: dashed borders, green for Checked-In, red/pink for Checked-Out, with matching circle-check icons and text. No fill.
- Red Check Out tile (behind the sheet): red gradient with white icon and label.
- No packshots.

## Navigation and behaviour
- At the end of the day tap চেক আউট on the Attendance screen; the sheet shows "চেক আউট করা হচ্ছে" and the time; check the time and press and hold the red button until it completes.
- Per the manual: Check Out is not possible before 5 PM (screenshot 15 shows it labelled "চেক আউট ৫টার পরে সক্রিয় হবে").
- After success the Attendance screen shows the thank-you message and both "Already Checked-In" and "Already Checked-Out" boxes; no more attendance action is possible for the day.
- Back arrow returns to the Dashboard.

## Differences between the image and the manual text
- The manual says Check Out is not possible before 5 PM, but the left screen shows a check-out in progress at 12:53 PM. Either the 5 PM rule is configurable or not enforced on that (test) account, or the screenshot was taken on a build without the rule. The image wins as evidence that the sheet can open at 12:53 PM on that account.
- The manual describes the right screen as "Already Checked-In and Already Checked-Out with the message that today's attendance is complete". The image shows these two labels are in English even though the rest of the screen is Bangla (they are not localised), and the message text is the Bangla thank-you sentence quoted above.
- The manual does not mention the heading "চেক আউট করা হচ্ছে" or the time-chip format.

## Open questions for the owner
- Is the 5 PM Check Out lock fixed, or configurable per account? The image shows a check-out at 12:53 PM.
- Should "Already Checked-In" and "Already Checked-Out" stay in English in the rebuild (as in the vendor app) or be translated to Bangla? (Screen-for-screen rule suggests keeping them; Bangla-first rule suggests translating; owner to decide.)
