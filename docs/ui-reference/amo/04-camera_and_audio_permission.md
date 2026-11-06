# 04. Camera and audio permission

## Screens in the image
- Left: Android system runtime-permission dialog "Allow ARON AMO to take pictures and record video?" shown over a black screen (camera is opening). System dialog, not the vendor app UI.
- Right: Android system runtime-permission dialog "Allow ARON AMO to record audio?" over a black screen. System dialog.
- Status bar: not shown.

## Layout, top to bottom
Left screen:
1. Whole upper area plain black (the camera preview not yet shown).
2. Bottom sheet: dark-grey rounded card spanning the width, in the lower third:
   - Blue camera icon, centred at the top of the card.
   - Question centred: "Allow ARON AMO to take pictures and record video?" (ARON AMO bold).
   - Three stacked text options, centred, white bold, no fill: "While using the app", "Only this time", "Don't allow".
3. Gesture bar line at the bottom.

Right screen:
1. Plain black upper area.
2. Bottom sheet, same style:
   - Blue microphone icon, centred.
   - Question: "Allow ARON AMO to record audio?" (ARON AMO bold).
   - Options: "While using the app", "Only this time", "Don't allow".
3. Gesture bar line.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Allow ARON AMO to take pictures and record video? | Camera permission question | Left dialog |
| Allow ARON AMO to record audio? | Microphone permission question | Right dialog |
| While using the app | Grant while app is open | Both dialogs, option 1 |
| Only this time | Grant once | Both dialogs, option 2 |
| Don't allow | Deny | Both dialogs, option 3 |

No Bangla text, no digits.

## Data and figures shown
- None.

## Colours and style
- Black background; dark-grey (about #262626) rounded bottom sheet with large corner radius; blue (about #1A73E8) camera and microphone icons; white bold option text with no button fill.
- Standard Android system style (phone's handwriting-like system font), not the vendor app styling.
- No packshots.

## Navigation and behaviour
- Per the manual: shown the first time the camera opens (outlet photo, manual override). Tap "While using the app" on both dialogs. The camera dialog comes first, then the audio dialog.
- Without these permissions outlet photos cannot be taken.
- Only this time grants once; Don't allow denies.

## Differences between the image and the manual text
- none (the manual's two quoted questions and the described order match the image exactly; the manual adds the trigger context, which the image does not show).

## Open questions for the owner
- none
