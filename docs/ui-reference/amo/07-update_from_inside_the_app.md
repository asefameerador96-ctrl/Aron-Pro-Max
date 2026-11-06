# 07. Update from inside the app

## Screens in the image
- **Left:** a vendor-app full-screen "forced update" page, shown after login when a newer version exists. White page, an illustration, Bangla text, a gradient button, and the current version.
- **Right:** the vendor-app "App is updating" progress page. Both are vendor screens, in Bangla. This is a different look from the English "Update Available" screen in 05 and 06 (white background, blue-indigo gradient controls).

Status bar: not visible.

## Layout, top to bottom

### Left screen
1. No top bar, no back arrow. White background.
2. Large flat-style illustration in the upper half: a tilted smartphone showing a video block, a red/pink content block and a blue block; a blue megaphone at the left, a blue chat heart badge, a coral-pink speech bubble with three dots, a thumbs-up, gears (red/pink and blue), coral and indigo leaves at the bottom.
3. Four lines of centred Bangla text in dark grey/black (serif-style font).
4. Gap, then a full-width button with a horizontal gradient from deep purple/indigo (left) to blue (right), small corner radius, white text "আপডেট" with a small phone-with-plus icon to the left of the text. State: enabled.
5. Bottom, small centred grey text "Current version 1.0.2".

### Right screen
1. No top bar, white background.
2. Illustration in the upper half: a pale grey phone outline with a magenta circular "refresh/sync" arrow symbol in the middle and three blue gears (light blue and darker blue) around it.
3. Heading "অ্যাপ আপডেট হচ্ছে" in purple, medium size, centred.
4. Horizontal progress bar full width: filled part is a blue gradient (dark blue to light cyan) from the left to about 55%, rest light grey, with rounded ends.
5. Centred caption under the bar in purple: "প্রসেসিং(৪৬/৮৪)".
6. Large gap, then near the bottom a red bold line: "টিপস: অ্যাপটি বন্ধ করবেন না".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| কিছু নতুন আপডেট পাওয়া গেছে। | Some new updates have been found. | Left, line 1 |
| এই অ্যাপটি ব্যবহার চালিয়ে যেতে, | To keep using this app, | Left, line 2 |
| আপনাকে অবশ্যই আপডেট করতে | you must update | Left, line 3 |
| হবে! | (it)! | Left, line 4 (the sentence wraps over lines 3 and 4) |
| আপডেট | Update | Left, button label |
| Current version 1.0.2 | Installed version (English words, Latin digits) | Left, bottom |
| অ্যাপ আপডেট হচ্ছে | App is updating | Right, heading |
| প্রসেসিং(৪৬/৮৪) | Processing (46/84) | Right, under progress bar; no space before the bracket; Bangla digits ৪৬ and ৮৪ |
| টিপস: অ্যাপটি বন্ধ করবেন না | Tips: do not close the app | Right, bottom, red |

Full left message as one sentence: "কিছু নতুন আপডেট পাওয়া গেছে। এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" (the line breaks above are only wrapping).

Digits: the progress count uses Bangla digits (৪৬/৮৪); the version number "1.0.2" uses Latin digits.

## Data and figures shown
- "1.0.2" = the version currently installed on the phone (manual: "the current version").
- "৪৬/৮৪" = 46 of 84 processing steps (or units) done; 46/84 = 54.8%, which matches the bar fill (about 55%). The unit of the 84 is not stated (could be steps, files or MB). The percentage is not printed.
- This is a different build state from screens 05/06 (which offer 1.0.4); the screenshots come from different moments.

## Colours and style
- White page background (not the lavender of 05/06), no top bar, no cards.
- Update button: gradient deep indigo/purple to blue with white text.
- Progress bar: blue gradient fill (dark blue to cyan) on light grey track.
- Heading and progress caption: purple. Tips line: red.
- Illustrations: flat vector art in blue, coral/pink, purple and magenta; they are decorative images, not packshots.

## Navigation and behaviour
- Left: the screen blocks the app until the update is done ("you must update"); no skip or back control is visible. Tapping আপডেট starts the update.
- Right: shows progress while the update is applied. The user must not close the app. The manual says to wait until the progress reaches 100%.
- No cancel button is visible.

## Differences between the image and the manual text
- The manual's wording ("Some new updates have been found. To keep using this app you must update it") matches the Bangla text; the image adds the exclamation mark and the sentence split over four lines.
- The manual does not mention the illustrations or the phone-with-plus icon on the button.
- The manual says "progress bar ... until the progress reaches 100%"; the image shows a step count "প্রসেসিং(৪৬/৮৪)" instead of a percentage.
- The manual does not mention that this screen is visually different from the "Update Available" screens in 05 and 06.

## Open questions for the owner
- Are the two update screens (English "Update Available" in 05/06 and Bangla "অ্যাপ আপডেট হচ্ছে" in 07) two different flows (download an APK from inside the app versus a store/in-app update), and which should the rebuild use? Both can be built; the owner may choose one.
