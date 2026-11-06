# 20. Manual override and Control Call menu

Source image: `20-manual_override_and_control_call_menu.jpg`. Manual section 8, screenshot 20.

## Screens in the image
Two phone screens side by side (no status bars visible on either).
- **Left**: the phone's camera (an Android system camera app / camera intent), opened after tapping Manual Override, showing a live viewfinder of a shop front. This is a system screen, not the vendor app.
- **Right**: the vendor ARON AMO "Control Call Activity" screen after the override, now showing the three-tile Control Call menu.

## Layout, top to bottom
Left screen (camera):
1. Black band at the very top (about 3% of height), then the viewfinder image (square-ish, about 70% of height): a street-side Bangladeshi variety shop. A tin-roof shop with a red signboard in Bangla (partly readable: "মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর"), shelves with hanging snack packets, a man in a white cap and light kurta standing at the left edge, a man in dark clothes seated on a wooden bench in the shop front, a blue drum, a closed shutter on the right, a brick-paved road in front, tangled overhead wires above.
2. Black control area below the picture (about 25% of height) with three round controls in one row: left a thin-outlined circle with a white camera-flip (switch camera) icon; centre a large solid white circle (shutter button); right a thin-outlined circle with a white "X" (close/cancel).
3. A thin white home-gesture line at the very bottom.

Right screen (vendor app):
1. Top bar: solid deep purple, flat; white rounded left-pointing triangle back arrow at the left; white bold centred title "কন্ট্রল কল অ্যাক্টিভিটি".
2. Route drop-down: full width, white, purple outline, text "Savar BazarDaily", down-triangle.
3. Outlet drop-down: full width (no LIVE icon beside it, no green dot), text "Sujon Store (1618414--Madrasa Road)", down-triangle. Note the double hyphen: the phone field is empty.
4. Alphabet bar: charcoal panel, two rows, white letters. Row 1: selected "All" chip (lime-green gradient), A, B, C, D, E, F, G, H, I. Row 2: J, K, M, N, P, R, S, T, V, Z.
5. Three white tiles in a row, each with a gradient icon and a Bangla label: "বিক্রয়" (purple/pink parcel box with blue clock), "এসআর পারফ. অ্যাসেসমেন্ট" (two lines; circular blue-to-pink progress ring with a check mark), "সার্ভে" (red-to-blue browser-window icon).
6. Empty light page below; a thin dark bar with a white gesture line at the very bottom.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর (partly legible, read from the signboard in the photo) | Masud bin Habib Variety Store (the shop's signboard) | Photo content, left screen |
| TO-LET (small white sign, partly legible) | Sign "to let" on the shop front | Photo content, left screen |
| কন্ট্রল কল অ্যাক্টিভিটি | Control Call Activity | Right screen top bar |
| Savar BazarDaily | Route name (Savar Bazar + "Daily") | Right, route drop-down |
| Sujon Store (1618414--Madrasa Road) | Outlet "name (code-phone-cluster)" with an empty phone; code 1618414, cluster Madrasa Road | Right, outlet drop-down |
| All | No letter filter | Alphabet bar, selected |
| A B C D E F G H I | Letter filters | Alphabet bar row 1 |
| J K M N P R S T V Z | Letter filters | Alphabet bar row 2 |
| বিক্রয় | Sales | Left tile |
| এসআর পারফ. অ্যাসেসমেন্ট | SR Performance Assessment | Middle tile (two lines) |
| সার্ভে | Survey | Right tile |

The camera screen has no text labels on its buttons. All digits are Latin (1618414); no Bangla digits, no separators, no units.

## Data and figures shown
- Outlet code 1618414; cluster "Madrasa Road"; phone empty (shown as "--").
- The photo is the evidence photo of the outlet front taken for the manual override; it also updates the outlet's location information per the manual.

## Colours and style
- Camera screen: black chrome, white controls, standard Android camera look (not the vendor's design).
- Right screen: deep purple flat top bar, very light lavender page, white drop-downs with deep-purple 2 px outline, charcoal alphabet bar with lime-green "All" chip, white tiles with faint border, gradient icons (red/pink to purple/blue), dark purple labels. Same as screenshot 18.
- No packshots (the photo is of a real shop, not a pack picture).

## Navigation and behaviour
- Left: Manual Override (screenshot 17) opens the camera. Per the manual: "Take a clear photo of the outlet front." The shutter takes the photo; the flip button switches front/back camera; X cancels.
- Right: after the photo is taken the app shows the Control Call menu (override accepted, range warning gone): "Choose what to do at the outlet."
- বিক্রয় opens the sales screen (a force sale reason is asked if outside the geo-fence, per screenshot 23). The other tiles open the SR performance assessment and the survey.

## Differences between the image and the manual text
- The manual does not mention the camera's flip and X controls.
- In the image the outlet drop-down on the right has no green dot and no LIVE icon (the field is full width), unlike screenshots 17 and 18; the manual does not mention this. It suggests the LIVE indicator and dot are hidden after a manual override.
- The right screen is a different route and outlet (Savar Bazar / Sujon Store) from the left photo's shop, so the photo and the app screen are not necessarily the same outlet (screenshots from different sessions).
- The alphabet bar here includes C and I and Z, unlike the other screens (route specific).

## Open questions for the owner
- After an override, is the LIVE icon and green dot intentionally hidden, or is that a different app version?
- Is the override photo mandatory every time, and how is it stored (the manual says it also updates the outlet's location)?
