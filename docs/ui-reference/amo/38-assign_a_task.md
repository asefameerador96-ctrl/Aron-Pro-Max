# 38. Assign a task

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app (no system dialog).
- LEFT: the "অ্যাসাইন্ড টাস্ক" (Assign Task) form screen, filled in.
- RIGHT: the "টাস্ক ডেলিগেশন" (Task Delegation) list screen after saving, with a green-yellow "Save Successfully!" banner over the list.

## Layout, top to bottom
### Left screen (form)
1. Top bar: solid deep purple, white back arrow (hollow left-pointing triangle) at left, centred white bold title "অ্যাসাইন্ড টাস্ক".
2. Route drop-down: white field with a purple outline, rounded corners, text "AgrabadDaily", small black down-arrow at right.
3. Outlet (retailer) drop-down: white field with purple outline, text "Sifat St (Diamond)", down-arrow at right.
4. Alphabet bar: a dark grey rounded panel holding letters in white in two rows. First row: a selected green gradient pill "All", then A B D E G H I J K L. Second row (centred): M N O P R S. "All" is selected (green); the other letters are unselected (plain white text).
5. Outlet info card: light grey-lavender rounded card with soft shadow. Left: a shop/storefront icon (coloured awning in yellow and green, blue window, grey door). Right, four lines (label in black Bangla, value in grey):
   - "আউটলেটের নাম:" then "Sifat St"
   - "মালিকের নাম:" then "Nurul Kobir"
   - "এসআর নাম:" then "SR-Kakoli - 1"
   - "এসআর মোবাইল নম্বর:" with an empty value (nothing shown after the label).
6. Task type drop-down: white, purple outline, text "General Task", down-arrow at right.
7. Date field: white, purple outline, calendar icon at left (black outlined calendar), text "November 3, 2025".
8. Description box: large multi-line white text area with a purple outline, text "Improve the sales quality" at top-left, rest empty.
9. Bottom button: wide rounded button, green gradient (light green top to darker green bottom), white small Bangla text "টাস্ক বরাদ্দ করুন", enabled, with a soft shadow.
10. Bottom: Android gesture bar (not part of the app).

### Right screen (list after save)
1. Top bar: deep purple, white back arrow, centred white bold title "টাস্ক ডেলিগেশন".
2. Tab strip: blue gradient selected tab "Assigned Tasks" at the left, grey glossy track for the rest.
3. Task card 1 (top): "Sifat St" at left, "চলমান" in orange at right; description "Improve the sales quality"; grey flag icon with "Completion on: 2025-11-03".
4. Task card 2: "Afifa Stor" at left, "চলমান" orange at right; description "Test task delegated to my SR"; its third row (the completion line) is hidden behind the banner.
5. Toast/banner: a full-width lime-green (yellow-green) bar with white centred text "Save Successfully!", overlaid across the list just under the second card's description (it covers the lower part of card 2). It is flat with a slight shadow, edge to edge.
6. Empty page area below.
7. Floating action button bottom right: orange-to-amber gradient circle with white "+".

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| অ্যাসাইন্ড টাস্ক | Assign(ed) Task | Left, top bar title |
| AgrabadDaily | Route name (Agrabad, daily route) | Left, route drop-down |
| Sifat St (Diamond) | Outlet name with its category/tier "Diamond" | Left, outlet drop-down |
| All | Show all outlets (alphabet filter, selected) | Left, alphabet bar |
| A B D E G H I J K L | Alphabet filter letters (only letters that have outlets: no C, F) | Left, alphabet bar row 1 |
| M N O P R S | Alphabet filter letters (row 2; no Q) | Left, alphabet bar row 2 |
| আউটলেটের নাম: | Outlet name: | Left, info card label |
| Sifat St | Outlet name value | Left, info card |
| মালিকের নাম: | Owner name: | Left, info card label |
| Nurul Kobir | Owner name value | Left, info card |
| এসআর নাম: | SR name: | Left, info card label |
| SR-Kakoli - 1 | SR name value | Left, info card |
| এসআর মোবাইল নম্বর: | SR mobile number: (value empty in this sample) | Left, info card label |
| General Task | Task type | Left, task type drop-down |
| November 3, 2025 | Completion date (English month name, Latin digits) | Left, date field |
| Improve the sales quality | Task description typed by AMO | Left, description box |
| টাস্ক বরাদ্দ করুন | Assign Task | Left, green button |
| টাস্ক ডেলিগেশন | Task Delegation | Right, top bar title |
| Assigned Tasks | Assigned Tasks | Right, selected tab |
| Sifat St | Outlet name | Right, task card 1 |
| চলমান | Running (status) | Right, card 1 and card 2, orange |
| Improve the sales quality | Task description | Right, card 1 |
| Completion on: 2025-11-03 | Completion date, ISO, Latin digits | Right, card 1 |
| Afifa Stor | Outlet name | Right, task card 2 |
| Test task delegated to my SR | Task description | Right, card 2 |
| Save Successfully! | Save succeeded (English message) | Right, green banner |
| + | Add task | Right, floating button |

All digits are Latin. Date format differs between form ("November 3, 2025") and list ("2025-11-03").

## Data and figures shown
- Example task created: route AgrabadDaily, outlet Sifat St (Diamond), owner Nurul Kobir, SR SR-Kakoli - 1, type General Task, completion date 3 Nov 2025, description "Improve the sales quality".
- SR mobile number is blank in this sample.
- After saving, the new task is the first row in the list, ahead of the earlier "Afifa Stor" task; both show status চলমান.
- Note: the outlet "Sifat St" is on route "AgrabadDaily" but its SR is "SR-Kakoli - 1"; this is just sample data.

## Colours and style
- Top bar: deep purple with white text on both screens.
- Page background: very pale pink-lavender.
- Input fields: white fill, 2 px purple outline, rounded corners (about 8 dp), black text, small black dropdown arrow.
- Alphabet bar: dark grey (about #4F4F4F) rounded panel, white letters; selected "All" is a green gradient rounded chip.
- Outlet info card: light grey-lavender, rounded, soft shadow; labels black, values grey; storefront illustration icon (flat, colourful).
- Assign button: green gradient, white text.
- Success banner: lime/yellow-green (about #AFCB0A) with white text.
- FAB: orange to amber gradient.
- Status text: orange. Flag icon: grey.
- No packshot pictures.

## Navigation and behaviour
- Reached by tapping the orange + on the Assigned Tasks list (screenshot 37).
- The AMO chooses the route (drop-down), then the outlet (drop-down). The alphabet bar filters the outlet list by first letter; "All" shows every outlet of the route. Choosing an outlet fills the info card (outlet name, owner name, SR name, SR mobile).
- Then choose the Task type (e.g. General Task) and the completion date (date picker, calendar icon), type a description, and tap টাস্ক বরাদ্দ করুন (Assign Task).
- On success the app returns to the Task Delegation list, shows the green "Save Successfully!" banner, and the new task appears at the top of the list with status চলমান.
- Back arrow leaves the form without saving.

## Differences between the image and the manual text
- The manual does not mention the alphabet filter bar (All, A to S), the outlet info card's four fields in detail beyond "outlet name, owner, SR name and SR mobile", or the shop icon.
- The manual names the outlet drop-down but the image shows the outlet name with a tier in brackets: "Sifat St (Diamond)".
- The manual says "completion date" only; the image shows the form date as "November 3, 2025" (English month) and the list date as "2025-11-03".
- The success message is English ("Save Successfully!") on a lime-green banner; the manual quotes the same text but not its colour or position.
- The task type drop-down is shown only as "General Task"; the manual says "e.g. General Task" and does not list the other types.
- The info card in the image has the SR mobile value empty; the manual implies it shows the SR mobile.

## Open questions for the owner
- What are all the Task types in the drop-down besides "General Task"?
- Is the description mandatory, and is there a maximum length?
- Can the completion date be in the past? Is today the default (it appears to default or be set to 3 Nov 2025)?
- Why is the SR mobile number empty here: missing data for that SR, or does the app not fill it?
