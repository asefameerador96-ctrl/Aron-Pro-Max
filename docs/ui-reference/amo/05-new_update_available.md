# 05. New update available

## Screens in the image
- **Left:** the vendor ARON AMO app, the "Update Available" screen (a forced-update / new-version screen, reached when the app finds a newer APK).
- **Right:** an Android system screen, Samsung One UI Settings, dark theme: Settings > Apps > Special access > "Install unknown apps". It is not the vendor app. It lists apps and a per-app switch that allows installing APKs.

Status bar: not visible in either half (only the bottom gesture bar).

## Layout, top to bottom

### Left screen (vendor app)
1. Top row, no coloured top bar: a plain page. Back arrow "<" (black, thin chevron) at the top-left.
2. Top-right of the same row: the text "Network Status" (black), then a green filled dot, then the green Bangla word "অনলাইন" (online, green text).
3. Large empty space.
4. Banner illustration, centre: an orange ribbon (two shades of orange, folded tails left and right) carrying the white words "NEW UPDATE" in bold with a dark navy outline/shadow.
5. Below it, a purple (medium purple, about #6B4FA8) cloud icon with a white downward arrow (download cloud).
6. Heading "Update Available", large, bold, near-black, centred.
7. Sub-line "Version 1.0.4 available", regular weight, dark grey, centred.
8. Large gap.
9. Full-width button, deep purple (about #4A1A9E), small corner radius (about 4 dp), white text "Download App & Install", centred. State: enabled. No icon.
10. Page background: very light lavender/pink white (about #FDF6FF). Bottom: system gesture bar.

### Right screen (Android Settings, dark)
1. Header: back arrow "<", title "Install unknown apps" (italic-style device font), at the right a "search/list" icon and a three-dot overflow icon.
2. Warning line under the header: "Installing apps from this source may put your phone and data at risk."
3. A dark-grey rounded card holding rows (icon, app name, size, switch at right):
   - Android Assistant, 801 KB, switch ON (blue), small grey text "Auto disabled" under the switch.
   - ARON AMO, 92.18 MB, switch ON (blue). The app icon is a light green/pink rounded square with a stylised red "A".
   - ARON SR, 101 MB, switch OFF (grey). Same style of icon.
   - Bluetooth, 1.46 MB, switch OFF.
   - Captive portal login, 10.56 MB, switch OFF.
   - Chrome, 240 MB, switch OFF.
   - Drive, 186 MB, switch OFF.
   - Galaxy Store, 353 MB, switch OFF.
   - Gmail, 342 MB, switch OFF.
   - Messages, 57.67 MB (row cut off at the bottom), switch OFF.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Network Status | Connectivity indicator label | Left, top-right |
| অনলাইন | Online | Left, top-right, green, beside green dot |
| NEW UPDATE | Ribbon banner | Left, ribbon image (part of an image asset) |
| Update Available | Heading | Left, centre |
| Version 1.0.4 available | New version number offered (Latin digits) | Left, under heading |
| Download App & Install | Button label | Left, purple button |
| Install unknown apps | Android settings page title | Right, header |
| Installing apps from this source may put your phone and data at risk. | Android warning | Right, under header |
| Android Assistant | App name | Right, row 1 |
| 801 KB | App size | Right, row 1 |
| Auto disabled | Android note | Right, row 1 |
| ARON AMO | App name | Right, row 2 |
| 92.18 MB | App size | Right, row 2 |
| ARON SR | App name (the SR app is also on the phone) | Right, row 3 |
| 101 MB | App size | Right, row 3 |
| Bluetooth | App name | Right, row 4 |
| 1.46 MB | App size | Right, row 4 |
| Captive portal login | App name | Right, row 5 |
| 10.56 MB | App size | Right, row 5 |
| Chrome | App name | Right, row 6 |
| 240 MB | App size | Right, row 6 |
| Drive | App name | Right, row 7 |
| 186 MB | App size | Right, row 7 |
| Galaxy Store | App name | Right, row 8 |
| 353 MB | App size | Right, row 8 |
| Gmail | App name | Right, row 9 |
| 342 MB | App size | Right, row 9 |
| Messages | App name | Right, row 10 |
| 57.67 MB | App size | Right, row 10 (partly cut) |

All digits are Latin. Only one Bangla word on the vendor screen: অনলাইন. The rest of the vendor screen is English even though the app is otherwise Bangla.

## Data and figures shown
- Version 1.0.4 = the version available to download. The installed version is not shown on this screen (screen 07 shows "Current version 1.0.2" in the other update flow).
- Sizes in the right screen are Android app sizes (ARON AMO 92.18 MB), not vendor-app data.
- No business figures.

## Colours and style
- Vendor screen: near-white lavender background, no top bar, no cards, no gradients. Deep purple button (about #4A1A9E) with white text. Orange ribbon with white text. Purple cloud download icon. Green online dot and green Bangla "অনলাইন". Black text for "Network Status", heading and version.
- This is a Material 3 style Flutter screen, visibly different from the gradient-bar look of the main screens.
- No packshots.
- Right screen: Samsung One UI dark theme; blue switches when on, grey when off. Not part of our app.

## Navigation and behaviour
- Back arrow (left, top) returns to the previous screen.
- "Download App & Install": downloads the new APK from inside the app, then hands it to the Android installer. If Android has not yet allowed this app to install packages, it opens the "Install unknown apps" page (right image). The user switches ARON AMO ON and goes back. In the image ARON AMO is already ON.
- "Network Status" shows online/offline; the download needs internet.
- What happens next is shown in screenshot 06 (download progress and the install prompt).

## Differences between the image and the manual text
- Manual says "Version x.x.x available"; the image shows "Version 1.0.4 available".
- Manual does not mention the back arrow on the left screen.
- Manual does not mention that ARON SR is also installed on the same phone (visible in the right list) or the other apps in the list (system content, not part of our app).
- The manual says "switch ARON AMO on"; in the image it is already on.
- The manual calls the right screen the "Install unknown apps" page, matching the image title.

## Open questions for the owner
- None.
