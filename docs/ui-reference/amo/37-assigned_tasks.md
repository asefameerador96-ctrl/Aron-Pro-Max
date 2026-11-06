# 37. Assigned Tasks

## Screens in the image
Single phone screen (full height, portrait). It is the vendor ARON AMO app, the Task Delegation (টাস্ক ডেলিগেশন) list screen, opened from the Dashboard tile "Task Delegation". No Android system dialog and no other app. The status bar is not visible in this image.

## Layout, top to bottom
1. Top bar: solid deep purple (indigo/violet), full width, no gradient visible. Back arrow at the far left (a hollow, outlined left-pointing triangle/arrow in white). Centered white bold Bangla title "টাস্ক ডেলিগেশন".
2. Page background below the bar: very pale pink-lavender (almost white).
3. A tab strip directly under the bar: one tab only, "Assigned Tasks". The tab is a blue gradient pill/rectangle (light blue at top to darker blue at bottom, glossy look) with white text, selected state, occupying about the left third of the strip. The rest of the strip is an empty light-grey glossy track with a thin grey underline across the full width. There is no second tab.
4. Task card (one card in the list): rounded rectangle, very light grey-lavender fill with a soft shadow, full width with side margins. Inside the card, three rows:
   - Row 1: left, outlet name "Afifa Stor" in dark text; right, the status "চলমান" in small orange text (right-aligned).
   - Row 2: task description "Test task delegated to my SR" in dark text, larger.
   - Row 3: a grey flag icon (filled flag) followed by grey text "Completion on: 2025-11-03".
5. Large empty area below the card (only one task in the list).
6. Floating action button, bottom-right: round, orange-to-amber gradient, white "+" sign in the centre, with a soft shadow. Enabled.
7. Bottom: the Android gesture bar (thin grey line) on a black strip; not part of the app.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| টাস্ক ডেলিগেশন | Task Delegation | Top bar title |
| Assigned Tasks | Assigned Tasks (English label) | Tab, selected, blue |
| Afifa Stor | Outlet name (outlet "Afifa Stor") | Task card, row 1 left |
| চলমান | Running / in progress (task status) | Task card, row 1 right, orange |
| Test task delegated to my SR | Task description entered by the AMO | Task card, row 2 |
| Completion on: 2025-11-03 | Completion date (Latin digits, ISO format YYYY-MM-DD) | Task card, row 3, after the flag icon |
| + | Add / assign a new task | Floating button, bottom right |

Note: all digits on this screen are Latin (2025-11-03). No thousand separators, no units.

## Data and figures shown
- One task: outlet "Afifa Stor", description "Test task delegated to my SR", completion date 2025-11-03, status "চলমান" (Running).
- The date is a due/completion date chosen by the AMO when assigning (see screenshot 38), shown as YYYY-MM-DD in Latin digits.
- No totals or counts are shown.

## Colours and style
- Top bar: deep purple (approx. #4A1B96 / indigo-violet), white title and white back arrow.
- Page background: very pale pink-lavender, almost white.
- Tab: glossy blue gradient (light blue to darker blue) with white text; the unselected remainder of the strip is a glossy light-grey track.
- Card: light grey-lavender, rounded corners (about 12 dp), soft drop shadow.
- Status text "চলমান": orange, small. Outlet name and description: near-black. Completion line: muted grey with a grey flag icon.
- FAB: orange to amber gradient circle with a white plus, shadow.
- No packshot images on this screen.

## Navigation and behaviour
- Back arrow returns to the Dashboard.
- Per the manual: the list shows the tasks the AMO assigned to SRs: outlet, task description and completion date, with the status (e.g. চলমান, Running).
- The orange + button opens the "Assign a task" form (screenshot 38).
- Only one tab, "Assigned Tasks", exists on this screen.

## Differences between the image and the manual text
- The manual does not mention the single blue tab "Assigned Tasks" (an English label inside a Bangla app) or its glossy gradient style.
- The manual does not say the completion date is shown with a flag icon and the prefix "Completion on:" in English.
- The status word is Bangla (চলমান) while the other labels are English; the manual translates it as "Running".
- Otherwise consistent: outlet, description, completion date, status and orange + all match.

## Open questions for the owner
- What other statuses exist besides চলমান (for example completed, overdue) and what colour does each have?
- Is there a second tab (for example "Completed Tasks") that is hidden when empty? Only "Assigned Tasks" is visible.
- Does tapping a task card open a detail view or allow edit/complete? Nothing in the manual or image shows this.
