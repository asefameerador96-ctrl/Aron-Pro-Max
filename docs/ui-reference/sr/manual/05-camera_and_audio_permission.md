# 05. Camera and audio permission

## Screens in the image
Two phone screenshots side by side, both Android permission bottom sheets over a black screen (the camera view has not opened yet). Left: camera permission. Right: microphone (audio) permission.

## Layout, top to bottom
Each screen:
1. Black background filling the screen (the camera preview is not showing).
2. At the bottom, a dark grey rounded sheet with a blue icon at top centre (left: camera; right: microphone), then the question in one line, then three stacked text options, centred.
3. Gesture bar at the very bottom.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Allow ARON SR to take pictures and record video? | Camera permission question ("ARON SR" bold) | Left sheet |
| Allow ARON SR to record audio? | Microphone permission question | Right sheet |
| While using the app | Grant while app in use | Both, option 1 |
| Only this time | Grant once | Both, option 2 |
| Don't allow | Deny | Both, option 3 |

## Data and figures shown
- None. Permission prompts only.
- The text is drawn in a handwriting-style font on this phone (a system font setting, not the app's font).

## Colours and style
- Black page, dark grey (#262626-ish) sheet, white text, blue (#1A73E8-ish) camera and microphone icons.
- Android system UI; no ARON styling.

## Navigation and behaviour
- Appear the first time the camera opens (for example for outlet or memo photos): first the camera prompt, then the audio prompt.
- The manual says to tap "While using the app" on both.
- The prompts appear because the app asks for video/audio permission along with the camera; the image shows both as separate sheets.

## Differences between the image and the manual text
- The manual matches the image.
- The image shows that the microphone prompt is a separate sheet after the camera one; the manual lists both together.
- The font is a phone-specific handwriting font, not the standard system font used in screenshots 01 to 03.

## Open questions for the owner
- Why does the SR app need audio recording (the vendor app asks for it)? The rebuild may drop the microphone permission if no feature uses it.
