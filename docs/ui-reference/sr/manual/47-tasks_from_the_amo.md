# 47. Tasks from the AMO

## Screens in the image
Two phone screens side by side, both the টাস্ক (Task) list. Left: a single task card "Poor Brand Visibility". Right (taller crop, no status bar): the swipe state, where a task card has been dragged right to reveal a red "Resolve" action, with a second task card below. The right image looks like a different, scaled capture.

## Layout, top to bottom
Left screen:
1. Status bar 3:22, location pin, 4G, signals, battery 66.
2. Header red-to-purple gradient, white back chevron, centred white title "টাস্ক" (no icon).
3. Page light grey (about #E8E8E8).
4. One task card, very pale lavender (about #F5F0F8), about 16 dp radius, light shadow. Lines: red bold "Poor Brand Visibility" (type); "Ruku Store" (outlet, black) with orange "চলমান" at the right; Bangla description "রুকু স্টোরে দ্রুত প্রোডাক্ট দেও"; a grey flag icon and grey "Completion on: 2026-11-11".

Right screen:
1. Header "টাস্ক" with back chevron.
2. Top task card slid to the right: on the left a solid red (about #D64B4B) block with a white circled-tick icon and "Resolve" in white; the card itself, cut off at the right edge, shows red "OOS", "Babu Store", "Babu Store দোকানে Maxim এ" (cut), flag and "Completion on: 2025-11" (cut).
3. Below, a second card: red "OOS", "Babu Store" with orange "চলমান", "Babu Store দোকানে Avon এ OOS আছে", flag and "Completion on: 2025-11-30".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| টাস্ক | Task | Header |
| Poor Brand Visibility | Task type | Left card, red |
| Ruku Store | Outlet name | Left card |
| চলমান | Running / in progress | Left card, orange, right-aligned |
| রুকু স্টোরে দ্রুত প্রোডাক্ট দেও | Quickly give product at Ruku Store | Left card description |
| Completion on: 2026-11-11 | Completion date (Latin digits, ISO) | Left card |
| OOS | Out of stock (task type) | Right cards, red |
| Babu Store | Outlet name | Right cards |
| Babu Store দোকানে Maxim এ (cut off) | Description, truncated at the screen edge | Right, swiped card |
| Babu Store দোকানে Avon এ OOS আছে | At Babu Store, Avon is OOS | Right, second card |
| Completion on: 2025-11-30 | Completion date | Right, second card |
| Completion on: 2025-11 (cut off) | Date, truncated | Right, swiped card |
| Resolve | Resolve (English) | Red swipe action |
| 3:22 / 66 | Clock / battery | Status bar, left |

## Data and figures shown
- Task types seen: Poor Brand Visibility, OOS.
- Status seen: চলমান on all visible cards; the done status সম্পন্ন (from the manual) is not shown.
- Dates are Latin digits ISO. The left card's date is 2026-11-11, the right cards' dates are in 2025, so the right capture is likely from an older build or test (an inference).
- No badge on the tile is visible in this image (the manual says a red badge on the tile shows new tasks).

## Colours and style
- Header red-to-purple gradient, white title.
- Cards: very pale lavender, about 16 dp radius; type text red (about #C84B4B); status text orange (about #E08A1E); flag icon grey-blue (about #7A7F99); date text grey.
- Resolve action: red block, white icon and text, light serif-like font for "Resolve".

## Navigation and behaviour
- Manual: each task shows type, outlet, description, completion date and status (চলমান running / সম্পন্ন done). The SR does the task at the outlet, then swipes the task from left to right and taps Resolve. A red badge on the Task tile shows new tasks.
- Swipe direction in the image: the card moves right and the Resolve action appears on the left, matching the manual.
- No resolve confirmation or note/photo entry is shown.

## Differences between the image and the manual text
- The manual says status is চলমান or সম্পন্ন; only চলমান appears.
- The manual's "Poor Brand Visibility, OOS" examples match the image.
- The tile badge is not shown here.
- The right screen has no status bar and different proportions from the left, so the two shots came from different devices or sessions.

## Open questions for the owner
- Does Resolve need a confirmation, note or photo, and does it work offline?
- Are completed tasks listed (সম্পন্ন) or removed? What happens to overdue tasks?
- Does the list scroll with many tasks, and is there a filter?
