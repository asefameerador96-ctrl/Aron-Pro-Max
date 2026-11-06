# 06. Download and install the update

## Screens in the image
- **Left:** the vendor ARON AMO app, the same "Update Available" screen as in 05, now in the downloading state (progress bar).
- **Right:** the same vendor screen dimmed by a dark overlay, with an Android system dialog on top ("ARON AMO / Do you want to update this app?") from the Android package installer (Samsung One UI, dark). Behind it, the vendor screen shows the "Install" button state.

Status bar: not visible.

## Layout, top to bottom

### Left screen (vendor app, downloading)
1. Top row: "Network Status" text, green dot, green "অনলাইন" at the right. No back arrow on this screen (the back arrow seen in 05 is absent).
2. Large gap, then the orange ribbon banner "NEW UPDATE" (white bold letters with dark outline).
3. Purple download-cloud icon with white down arrow.
4. Heading "Update Available" (bold, near-black).
5. "Version 1.0.4 available" (regular, dark grey).
6. Gap, then a horizontal progress bar full width: filled part deep purple (about #4A1A9E) from the left to about 53%, unfilled part light grey; thin (about 6 dp) with rounded ends. The download button of screen 05 is replaced by this bar.
7. Centred text under the bar: "Downloading 53%" (dark grey).
8. Below: an info icon (circle with "i", red outline) followed by the red text "Please do not close the app while downloading" on one line.
9. Page background: very light lavender-white. The Download button is not shown.

### Right screen (dimmed vendor screen plus system dialog)
1. Whole screen covered by a grey scrim. Back arrow "<" at top-left, "Network Status", green dot, green "অনলাইন" at top-right, all dimmed.
2. Dimmed ribbon banner "NEW UPDATE" (dark brown/orange under the scrim).
3. Dialog (dark grey rounded card, centred, overlapping the cloud icon and heading): at top-left the ARON AMO icon (light green/pink rounded square with a stylised "A") and the title "ARON AMO"; below it the question "Do you want to update this app?"; at the bottom two text buttons separated by a thin vertical line: "Cancel" (left) and "Update" (right), both white, bold. Both are enabled.
4. Below the dialog, the dimmed line "Version 1.0.4 available" is partly hidden behind the dialog edge.
5. A full-width dark purple button (about #2F1065 under the scrim) with the grey label "Install". It looks disabled/dimmed because of the overlay.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Network Status | Connectivity indicator label | Both screens, top-right |
| অনলাইন | Online | Both screens, top-right, green |
| NEW UPDATE | Ribbon banner (image asset) | Both screens |
| Update Available | Heading | Both screens |
| Version 1.0.4 available | Version being installed | Both screens (right is partly hidden) |
| Downloading 53% | Download progress | Left, under the progress bar |
| Please do not close the app while downloading | Warning | Left, red text, with info icon |
| ARON AMO | App name | Right, dialog title |
| Do you want to update this app? | Android installer question | Right, dialog body |
| Cancel | Cancel | Right, dialog, left button |
| Update | Confirm update | Right, dialog, right button |
| Install | Button label | Right, behind the dialog, dimmed button |

Digits are Latin ("53%", "1.0.4"). The percent sign is attached directly to the number, no space.

## Data and figures shown
- 53% = fraction of the APK downloaded when the screenshot was taken (the bar fill matches, about 53% of the width).
- Version 1.0.4 = the version being installed.
- No other figures.

## Colours and style
- Same as 05: lavender-white page, deep purple controls, orange ribbon, purple cloud, green online dot.
- Progress bar: deep purple fill on light grey track; no colour bands.
- Warning text and info icon: red (about #D32F2F).
- Dialog: Android system dark dialog with white text, not vendor styled.
- Scrim: mid-grey about 60% opacity over the whole vendor screen.
- No packshots.

## Navigation and behaviour
- During the download (left) the user must keep the app open and online; the warning says not to close the app. No cancel button is visible on the left screen, and there is no back arrow.
- When the download finishes, the button area shows "Install" and Android shows "Do you want to update this app?" with Cancel and Update (right).
- "Update" starts the install over the existing app; Cancel dismisses the dialog and leaves the vendor screen with the Install button. The manual says: tap Update, then Open when it is done (the "Open" step is the Android "App installed" screen, not in this image).
- A back arrow appears on the right screen (dimmed) but not on the left (during download).

## Differences between the image and the manual text
- The manual does not mention the "Install" button seen behind the dialog.
- The manual does not mention that the back arrow disappears during the download.
- The manual does not mention the app icon in the dialog.
- Otherwise the manual matches: "Downloading 53%", the red warning, and Cancel/Update.

## Open questions for the owner
- None.
