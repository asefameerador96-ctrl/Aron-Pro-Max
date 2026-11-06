# 03. Login and location permission

## Screens in the image
- Left: the vendor ARON AMO login screen (first screen of the app).
- Right: the same login screen dimmed, with the Android system runtime-permission dialog "Allow ARON AMO to access this device's location?" on top (system dialog over the vendor app).
- Status bar: not shown.

## Layout, top to bottom
Left screen (login):
1. Top header: a large gradient area, red at the bottom-left corner through magenta/plum to deep purple at the top-right, cut by a diagonal edge rising from lower-left to upper-right; a thin darker indigo/violet diagonal band runs along the bottom edge of the gradient. Below the diagonal the page is a very light lavender-white.
2. Logo: centred near the top of the gradient, an oval/ellipse (wider than tall) in pale mint-green to light-pink gradient holding the stylised "A" app logo.
3. Label "ইউজারনেম" (left-aligned, black, small) above the first field.
4. Username field: full-width rounded rectangle, light grey fill, thin red-to-purple gradient outline; centred placeholder text "ইউজারনেম". Enabled, empty (placeholder shown).
5. Label "পাসওয়ার্ড" (left-aligned) above the second field.
6. Password field: same style; centred "******" (six asterisks, masked text; no eye icon visible).
7. Login button: full-width rounded pill, horizontal gradient from coral red (left) to deep purple (right), white bold text "লগইন" centred. Enabled.
8. Footer, centred small grey text on three lines: "AMO App", "(version - 1.0.2)", "Developed by Apsis Solutions".
9. Rest of page empty lavender-white.

Right screen (permission over login):
1. Background: the login header dimmed (darkened gradient and darkened logo); the label "ইউজারনেম" visible just above the dialog; page below the diagonal is dimmed grey.
2. Dialog: large dark-grey rounded card covering the lower half:
   - Blue location-pin icon at the top centre.
   - Question text centred: "Allow ARON AMO to access this device's location?" (ARON AMO in bold).
   - Two circular map thumbnails side by side: left shows a dark map with a blue pin inside a blue ring and a blue circle outline, caption "Precise"; right shows a dark map with yellow roads, caption "Approximate".
   - Three stacked full-width text options: "While using the app", "Only this time", "Don't allow" (white bold, no fill).
3. Gesture bar line.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| ইউজারনেম | Username | Left: label above field and placeholder inside field; right: label visible above dialog |
| পাসওয়ার্ড | Password | Left: label above second field |
| ****** | Masked password (six asterisks) | Left: password field |
| লগইন | Login | Left: gradient button |
| AMO App | App name | Left footer line 1 |
| (version - 1.0.2) | App version 1.0.2 | Left footer line 2 |
| Developed by Apsis Solutions | Vendor credit | Left footer line 3 |
| Allow ARON AMO to access this device's location? | Android permission question | Right dialog |
| Precise | Exact location choice | Right dialog, left thumbnail caption |
| Approximate | Approximate location choice | Right dialog, right thumbnail caption |
| While using the app | Grant while app is open | Right dialog, option 1 |
| Only this time | Grant once | Right dialog, option 2 |
| Don't allow | Deny | Right dialog, option 3 |

Digits: "1.0.2" and "******" are Latin/ASCII. No Bangla digits appear.

## Data and figures shown
- App version shown: 1.0.2 (AMO App).
- Password field shows six asterisks as a masked example; it does not reveal the real length.

## Colours and style
- Header gradient: coral red (bottom-left) to magenta/plum to deep purple (top-right), with a darker indigo diagonal band at the bottom edge; this is the signature red-to-purple Aron gradient.
- Page background: very light lavender-white (about #FBF5FF).
- Input fields: light grey (about #F4F4F4) fill, rounded corners (about 12 px), thin red-purple gradient border, black text, centred text.
- Login button: rounded pill, red-to-purple horizontal gradient, white bold Bangla text, subtle shadow.
- Footer text mid-grey, small.
- Logo: pale mint-pink gradient oval with the stylised A.
- Permission dialog: Android dark grey card, blue pin icon, white text.
- No packshots.

## Navigation and behaviour
- Manual: enter the AMO username and password and tap লগইন.
- On the permission dialog choose Precise and tap "While using the app". The app cannot be used without location permission.
- The dialog appears over the login screen when the app first needs location.
- The app version is shown below the form.

## Differences between the image and the manual text
- The manual does not mention the footer credit "Developed by Apsis Solutions" or that the app name line reads "AMO App"; the manual only says "The app version is shown below".
- The manual does not describe the diagonal gradient header, the oval logo, or the placeholder text repeated inside the username field.
- The password field shows "******" in the image (a filled or placeholder mask); no eye/show-password icon is visible.
- The manual says "Precise / Approximate"; the image shows these as two circular map thumbnails (Precise selected by default is not visibly marked).

## Open questions for the owner
- The new app should say "Developed by Apsis Solutions"? Probably not; confirm the wording of the footer credit for the rebuild (suggest AKTCL / product name).
