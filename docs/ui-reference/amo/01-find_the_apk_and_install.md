# 01. Find the APK and install

## Screens in the image
- Left: Android system app "File Manager" (Samsung-style "My Files"), dark theme, showing the Internal storage folder list scrolled to the bottom where the APK file is. Not the vendor app.
- Right: the same File Manager list dimmed behind an Android system package-installer dialog "ARON AMO - Do you want to install this app?". Android system dialog, not the vendor app.
- Status bar: not shown; only the gesture bar line at the bottom.

## Layout, top to bottom
Left screen (File Manager, near-black background):
1. Top row: back arrow "<" at far left; search (magnifier) icon and three-dot overflow menu at right.
2. Breadcrumb row: home-in-folder icon, a right-pointing triangle, blue italic text "Internal storage".
3. File list (scrolled; top row partly cut off). Each row: folder icon at left, name on first line, date on second line (grey), item count right-aligned on second line. Rows in order:
   - (cut off row) date 27 Nov 2023 9:52 pm, 10 items
   - vendor.samsung.har....snap@1.2-service, 25 Sept 2023 1:40 am, 0 items
   - Android (folder icon with small gear badge), 9 Aug 2023 1:01 am, 5 items
   - sepolicy_extends, 9 Aug 2023 1:01 am, 1 item
   - AndroidAssistant_appbackup, 27 Jul 2023 5:41 pm, 2 items
   - Music (folder icon with blue music-note badge), 30 Jan 2023 11:43 am, 0 items
   - Audiobooks, 30 Jan 2023 11:43 am, 0 items
   - Notifications, 30 Jan 2023 11:43 am, 0 items
   - Alarms, 30 Jan 2023 11:43 am, 0 items
   - Podcasts, 30 Jan 2023 11:43 am, 0 items
   - Last row (APK): ARON AMO app icon (rounded square, pale green-to-pink gradient with a stylised blue/red letter "A") at left; file name aron_amo_app_26_11_2025_v1.apk; second line "1 Dec 11:47 am" at left and "74.39 MB" at right.
4. Bottom: gesture bar line.

Right screen (same list dimmed to about 30 percent):
1. Same top bar, breadcrumb and list, dimmed and not interactive.
2. Centered dark-grey rounded dialog card over the list (covers the area around sepolicy_extends):
   - Top row: ARON AMO app icon (same pale gradient "A") and bold title "ARON AMO".
   - Question line: "Do you want to install this app?"
   - Button row with a thin vertical divider: "Cancel" (left) and "Install" (right), both bold white text, no fill (text buttons).
3. The dimmed APK row is still visible at the bottom.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Internal storage | Phone storage root | Breadcrumb, left and right (blue) |
| vendor.samsung.har....snap@1.2-service | Folder name (system) | List row, both |
| 25 Sept 2023 1:40 am | Folder date | List row |
| 0 items | Item count | List rows |
| Android | Folder | List row |
| 9 Aug 2023 1:01 am | Date | List row |
| 5 items | Item count | Android row |
| sepolicy_extends | Folder | List row |
| 1 item | Item count | sepolicy_extends row |
| AndroidAssistant_appbackup | Folder | List row |
| 27 Jul 2023 5:41 pm | Date | List row |
| 2 items | Item count | List row |
| 27 Nov 2023 9:52 pm | Date of the cut-off top row | Top list row |
| 10 items | Item count | Top list row |
| Music | Folder | List row |
| Audiobooks | Folder | List row |
| Notifications | Folder | List row |
| Alarms | Folder | List row |
| Podcasts | Folder | List row |
| 30 Jan 2023 11:43 am | Date | Music, Audiobooks, Notifications, Alarms, Podcasts rows |
| aron_amo_app_26_11_2025_v1.apk | The APK file name (app name, date 26-11-2025, version v1) | Last list row |
| 1 Dec 11:47 am | File date (year not shown) | APK row |
| 74.39 MB | APK size | APK row, right |
| ARON AMO | App name | Installer dialog title |
| Do you want to install this app? | Android installer confirmation | Installer dialog |
| Cancel | Dismiss install | Installer dialog, left |
| Install | Start install | Installer dialog, right |

No Bangla text and no Bangla digits on this image. All digits are Latin.

## Data and figures shown
- APK file: aron_amo_app_26_11_2025_v1.apk, 74.39 MB, saved 1 Dec 11:47 am. The file name encodes date 26_11_2025 and version v1 (the manual's generic text says "aron_amo_app_<date>_v1.apk").
- Folder item counts (10, 0, 5, 1, 2, 0 ...) are incidental File Manager data, not app data.

## Colours and style
- Both screens are Android dark theme: near-black (about #121212) background, white and light-grey text, thin grey row separators.
- Breadcrumb "Internal storage" is blue italic-looking text (a Samsung handwriting-style system font is in use; the font is the phone's, not the app's).
- Installer dialog: dark grey (about #2b2b2b) rounded card, white bold text buttons, no coloured buttons.
- App icon: rounded square, pale mint-green to light-pink gradient background with a stylised capital "A" in blue outline with red/orange flame-like fill.
- No packshots.

## Navigation and behaviour
- Manual: open the File Manager, tap the ARON AMO APK, tap Install on the dialog and wait.
- Cancel closes the dialog without installing; Install starts installation.
- Manual note: if the phone blocks the install, allow "Install unknown apps" for the File Manager when asked (this prompt is not shown in the image).
- The app is distributed as an APK, not through Play Store.

## Differences between the image and the manual text
- The image shows the concrete file name aron_amo_app_26_11_2025_v1.apk and size 74.39 MB; the manual only gives the pattern "aron_amo_app_<date>_v1.apk".
- The manual says the right-hand dialog has "Cancel and Install"; matches the image exactly. Nothing else differs.

## Open questions for the owner
- none
