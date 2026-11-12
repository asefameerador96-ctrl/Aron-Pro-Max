# 01. Find the APK and install

## Screens in the image
Two phone screenshots side by side, both system (Samsung One UI, dark theme) screens, not the ARON app itself. Left: the File Manager listing "Internal storage" with the APK at the bottom. Right: the same listing dimmed, with the Android package-installer dialog "ARON SR - Do you want to install this app?".

## Layout, top to bottom
Left screen:
1. Top bar: back chevron at left; search icon and three-dot menu at right.
2. Breadcrumb row: a home-folder icon, a right-pointing triangle, "Internal storage" in blue.
3. A scrolled folder list, black background, each row a folder icon, name and date on the left, item count at right: a partly cut first row (27 Nov 2023 9:52 pm, 10 items), then vendor.samsung.ha...e.snap@1.2-service, Android (gear badge), sepolicy_extends, AndroidAssistant_appbackup, Music (note badge), Audiobooks, Notifications, Alarms, Podcasts.
4. Last row: the ARON SR launcher icon (rounded square, pastel green-to-pink with a stylised "A") with the file name and size.
5. Bottom: gesture bar.

Right screen: same list dimmed; a rounded dark dialog in the middle with the app icon and "ARON SR" at top-left, the question, and two text buttons "Cancel" and "Install" separated by a thin divider.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Internal storage | Phone internal storage (breadcrumb) | Both, breadcrumb, blue |
| 27 Nov 2023 9:52 pm / 10 items | Date and count of a cut-off folder row | Both, first row |
| vendor.samsung.ha...e.snap@1.2-service / 25 Sept 2023 1:40 am / 0 items | System folder | Both |
| Android / 9 Aug 2023 1:01 am / 5 items | System folder | Both |
| sepolicy_extends / 9 Aug 2023 1:01 am / 1 item | System folder | Both (right: hidden behind dialog except name) |
| AndroidAssistant_appbackup / 27 Jul 2023 5:41 pm / 2 items | System folder | Left |
| Music / 30 Jan 2023 11:43 am / 0 items | Folder | Both (right: hidden) |
| Audiobooks, Notifications, Alarms, Podcasts (each 30 Jan 2023 11:43 am, 0 items) | Folders | Both |
| aron_sr_app_25_11_2025_v1.apk | The installer file (date 25 Nov 2025 in the name) | Both, last row |
| 25 Nov 2:18 pm | File date and time | Last row |
| 80.40 MB | APK size | Last row, right |
| ARON SR | App name | Dialog title, right screen |
| Do you want to install this app? | Android install confirmation | Dialog |
| Cancel | Cancel | Dialog, left button |
| Install | Install | Dialog, right button |

## Data and figures shown
- File name aron_sr_app_25_11_2025_v1.apk (name pattern aron_sr_app_dd_mm_yyyy_v1.apk), size 80.40 MB, modified 25 Nov 2:18 pm.
- The other file-manager rows are unrelated system folders; the dates (2023) are the phone's, not ARON data.

## Colours and style
- Entirely Android/Samsung system UI in dark theme: near-black background, white and grey text, blue breadcrumb.
- App icon: rounded square, soft pastel green to pink gradient, with an angular "A" in blue, red and white (ice/lightning look).
- Dialog: dark grey rounded rectangle, white bold buttons. No ARON gradient bars appear here.

## Navigation and behaviour
- The user opens the file manager, finds the APK in Internal storage and taps it; the system installer asks to confirm; Install starts installation (next screenshot is "App installed").
- Cancel dismisses.
- The manual's note about "Install unknown apps" for the File Manager is not visible in this image.

## Differences between the image and the manual text
- The manual writes the file name as "aron_sr_app_<date>_v1.apk"; the image shows the date as 25_11_2025 (underscore-separated dd_mm_yyyy).
- The image shows the file size (80.40 MB) and time, which the manual does not mention.
- The manual does not mention the unknown-apps note on this image; it is only in the text.
- The right screen is the same list dimmed behind the dialog, as described.

## Open questions for the owner
- Is the APK always about 80 MB (the Install unknown apps list in screenshot 06 shows the installed ARON SR as 93.11 MB)? This affects the lean-APK budget.
- Will the new app be distributed the same way (file in File Manager), or through the MDM/device-owner channel?
