# 76. Send data file to support

## Screens in the image
Single phone screen (portrait): the vendor ARON AMO Settings screen dimmed behind a modal success dialog (the app's own dialog, shown after tapping "PDA টু সাপোর্ট"). No status bar visible; black gesture bar at the bottom.

## Layout, top to bottom
1. Top bar: deep purple (darker than normal because of the dark scrim), white back arrow at left, centred white bold title "সেটিং".
2. Dimmed background (grey-black scrim, about 50 percent) over the Settings tiles row, which is still visible but faded: language tile "বাংলা ▾", tile "PDA টু সাপোর্ট" (pink-to-blue gradient icon), tile "লগ আউট" (purple gradient icon).
3. Centred modal dialog (white, large rounded corners, deep shadow), about 80 percent of screen width, vertically at about 40 percent of screen height, from top to bottom:
   - Green filled circle with a white tick (success icon), centred.
   - Title "সফল" in bold blue, centred.
   - Message "সিঙ্ক ফাইল পাঠানো হয়ে গেছে" in black, centred.
   - Bottom right: green rounded button "ওকে" with white text and a light shadow.
4. Bottom: black gesture bar.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| সেটিং | Settings | Top bar title |
| বাংলা | Bangla (current language) | Language tile (dimmed) |
| PDA টু সাপোর্ট | PDA to Support | Tile caption (dimmed) |
| লগ আউট | Log Out | Tile caption (dimmed) |
| সফল | Success | Dialog title, blue bold |
| সিঙ্ক ফাইল পাঠানো হয়ে গেছে | Sync file has been sent | Dialog message |
| ওকে | OK | Green dialog button |

## Data and figures shown
No numbers. The only information is the confirmation that the sync (data) file was sent to support.

## Colours and style
- Dialog: white, strongly rounded (about 28 px) corners, heavy soft shadow; success icon green (about #4CAF50) circle with white check; title blue (about #1E6FB8) bold; message black regular; button green (same green) with white text, rounded, bottom right.
- Scrim: dark grey, makes the purple top bar look near-black purple and the page grey.
- No packshots.

## Navigation and behaviour
- Reached by tapping the PDA টু সাপোর্ট tile in Settings when support asks for the data file; the app uploads the sync/data file and shows this dialog.
- Tap ওকে to close the dialog and stay on Settings. No other choice (no cancel) is shown.

## Differences between the image and the manual text
- none (the manual's text "সফল - সিঙ্ক ফাইল পাঠানো হয়ে গেছে" matches). The image adds that the dialog has a green tick, a blue title and a green OK button, and that Settings stays visible behind it.

## Open questions for the owner
- What does the app show on failure (no network or upload error)? Only the success case is documented.
