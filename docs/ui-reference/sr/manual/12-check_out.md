# 12. Check out

## Screens in the image
Two phone screens side by side, both the SR "অ্যাটেনডেন্স" screen after a successful check-in, from two different accounts. Left (no status bar): check-out not yet available, shown as an amber dashed box. Right (status bar 3:00): check-out available, shown as a red active button.

## Layout, top to bottom
Both screens share the layout of screenshot 11 (header, identity row, red line, address card, message) and differ in the lower area.
1. Header, red-to-purple gradient, white back chevron, calendar icon and white bold "অ্যাটেনডেন্স". Right screen also has a status bar: "3:00", a small app icon, pin, 4G, signal, battery 69.
2. Identity row (lavender square with person icon). Left: "SR-16858 (sr16858)" and "Savar Bazar(Sat, Mon, Wed), 2025-11-26". Right: "MD. ABDUR RAHMAN EMON (sr22233)" and "Agora GoliDaily, 2026-11-11".
3. Red line, then the address card with refresh arrow (left: "Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh"; right: "39a, 39a Rd 115, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh").
4. Centred two-line Bangla message.
5. Two boxes side by side:
   - Left: a green dashed rounded box with a green circled check mark and green two-line text.
   - Right, left screen: an amber (orange) dashed box with a warning triangle and amber two-line text (not tappable).
   - Right, right screen: a solid red gradient button (crimson to rose) with a white arrow-out icon and white label "চেক আউট".
6. Rest of the page empty; right screen has the Android three-button bar.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাটেনডেন্স | Attendance | Header title |
| SR-16858 (sr16858) | SR name and ID (example) | Left identity row |
| Savar Bazar(Sat, Mon, Wed), 2025-11-26 | Route with visit days, date | Left identity row |
| MD. ABDUR RAHMAN EMON (sr22233) | SR name and ID | Right identity row |
| Agora GoliDaily, 2026-11-11 | Route name ("Agora Goli") and "Daily", date | Right identity row |
| Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh | Current address | Left address card |
| 39a, 39a Rd 115, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh | Current address | Right address card |
| আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Before finishing today's work, please check out. | Message, both screens |
| চেক ইন সম্পন্ন হয়েছে | Check-in completed | Green dashed box, both screens |
| চেক আউট ৫টার পরে সক্রিয় হবে | Check out will become active after 5 o'clock | Amber dashed box, left screen |
| চেক আউট | Check out | Red active button, right screen |
| 3:00, 4G, 69 | Clock, network, battery (system) | Right status bar |

## Data and figures shown
- Check-in is done, check-out is not. The left screen shows the earlier cut-off rule: check-out unlocks at 5 PM (the text says ৫টা, five o'clock, in Bangla digit).
- The right screen is after 5 PM (the phone shows 3:00, a 12-hour clock without AM/PM, so it is most likely 15:00 as shown, which is before 5 PM: see Open questions).
- Dates in Latin digits yyyy-mm-dd. Different accounts and days on the two screens (2025-11-26 and 2026-11-11).

## Colours and style
- Header red-to-purple gradient; page light grey.
- Check-in done box: green dashed outline (about 1.5 dp), green text, green circled check.
- Waiting box: amber/orange dashed outline, amber text and triangle (warning, not error).
- Active check-out: solid red gradient with white text and icon, soft shadow; large rounded corners (about 12 dp).

## Navigation and behaviour
- The check-out button is not tappable until the cut-off time is reached; before that the amber box explains why.
- Once active, tapping চেক আউট opens the press-and-hold sheet (screenshot 13).
- Back chevron returns to the Dashboard.
- The address refresh arrow is still available.

## Differences between the image and the manual text
- The manual says the left box reads "Check out will be active after the time" (amber). The image gives the exact time: "চেক আউট ৫টার পরে সক্রিয় হবে" (after 5), so the cut-off is shown in the text.
- The manual's note "Check out is not possible before 5 PM" agrees with the amber box.
- The right screen is described as "red চেক আউট button active": matches. It shows a status bar clock of 3:00 with the button active; this does not square with a 5 PM cut-off if the clock is 3:00 PM (the clock may be 3:00 AM or the account may have a different rule). See questions.
- The manual says "Left: after check-in ... Right: check-in done": the image shows it in the opposite sense (left screen = waiting, right screen = active), the pair is two accounts, not one flow.

## Open questions for the owner
- Is the check-out cut-off fixed at 5 PM for everyone, or set per user or per area? The right screen shows an active button at a phone time of 3:00.
- What is the status bar "3:00" (AM or PM)? If PM, then the cut-off may be configurable.
- What happens when the SR forgets to check out (auto check-out at midnight, or the next day is blocked)?
