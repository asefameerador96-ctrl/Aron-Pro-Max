# 06. New update available

## Screens in the image
Two phone screenshots side by side. Left: the Android system settings page "Install unknown apps" (dark theme) listing apps with a switch each. Right: the ARON SR in-app "NEW UPDATE" screen with an "Update Available" message and a "Download App & Install" button.

## Layout, top to bottom
Left screen:
1. Top bar: back chevron, title "Install unknown apps", a list-search icon and a three-dot menu.
2. Grey explanatory line.
3. A rounded dark card holding rows: app icon, name, size, and a switch at right. The ARON SR row is first with its switch off (grey). Chrome and My Files have their switch on (blue).

Right screen:
1. White top strip: back chevron at left; "Network Status", a green dot and the Bangla word "অনলাইন" in green at right.
2. Light grey page.
3. An orange ribbon banner, slightly curved, with outlined white text "NEW UPDATE".
4. A purple cloud with a download arrow.
5. Heading "Update Available" in bold dark text.
6. Version line, then a two-line change summary.
7. A full-width red button (about #D9453F), white text "Download App & Install", small rounded corners.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Install unknown apps | Android setting page title | Left, top bar |
| Installing apps from this source may put your phone and data at risk. | Android warning | Left, under the title |
| ARON SR / 93.11 MB | The installed app and its size (switch off) | Left, row 1 |
| Bluetooth / 0.97 MB | System app (switch off) | Left |
| Chrome / 106 MB | App (switch on) | Left |
| Drive / 68.08 MB / Deep sleeping | App (switch off); "Deep sleeping" is a Samsung battery label | Left |
| Galaxy Store / 74.80 MB | App (switch off) | Left |
| Gmail / 220 MB | App (switch off) | Left |
| My Files / 7.27 MB | App (switch on) | Left |
| Quick Share / 1.99 MB | App (switch off) | Left |
| Quick Share Agent / 6.16 MB | App (switch off) | Left |
| Network Status | Connectivity indicator label | Right, top strip |
| অনলাইন | Online | Right, green, beside a green dot |
| NEW UPDATE | Banner | Right |
| Update Available | Heading | Right |
| Version 1.0.25 available | The version offered | Right |
| New promotion modality., Login time 2FA verification., Sync file update without deleting. | What is new in this release (three items, run together, with ".," separators) | Right, change summary |
| Download App & Install | Button | Right, red button |

## Data and figures shown
- Offered version: 1.0.25.
- Release notes: "New promotion modality", "Login time 2FA verification", "Sync file update without deleting".
- Online status: green dot with "অনলাইন".
- ARON SR size on the phone: 93.11 MB.

## Colours and style
- Left: Android dark theme; blue switch when on, grey when off.
- Right: light grey page, white top strip; the banner is orange with a darker orange ribbon tail; the cloud is purple; the button is flat red, not the gradient button of the login screen; the heading is near-black.
- Network Status label is Latin, the status word Bangla and green.

## Navigation and behaviour
- Tapping "Download App & Install" starts the download (screenshot 07). If the phone blocks installs from the app, it opens the "Install unknown apps" page where the user switches ARON SR on and goes back.
- The back chevron at the top left leaves the update screen.
- The "NEW UPDATE" page is shown when the server says a newer version exists; the version number is dynamic.

## Differences between the image and the manual text
- The manual says "Version x.x.x"; the image shows the version as "Version 1.0.25 available".
- The manual does not quote the release notes; the image shows three items (new promotion modality, login-time 2FA, sync file update without deleting).
- The manual does not mention the "Network Status / অনলাইন" indicator at the top.
- On the left, the ARON SR switch is shown off, matching the manual's "switch ARON SR on".
- Note: the update screen offers 1.0.25, and the login screen (03) already shows "version - 1.0.25", so the screenshots were taken from different builds.

## Open questions for the owner
- "New promotion modality" is a vendor release note about promotions, which docs/27 defers. Confirm no promotion feature is needed in the first cutover.
- Is "Login time 2FA verification" the TSO-given OTP of screenshot 04 only, or a second factor on every login?
