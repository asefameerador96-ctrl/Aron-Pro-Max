# 03. Login and location permission

## Screens in the image
Two phone screenshots side by side. Left: the ARON SR login screen. Right: the same login screen dimmed, with the Android bottom sheet "Allow ARON SR to access this device's location?".

## Layout, top to bottom
Left screen:
1. Top about 40 percent: a diagonal split. Upper-left is a red-to-purple gradient (crimson at bottom-left through magenta to deep purple at top-right); below it a thick deep-indigo diagonal stripe rising left to right; the rest of the page is light grey (about #E8E8E8).
2. Centred in the gradient: a large white-to-pastel circle (green top, pink bottom) holding the "A" logo.
3. Label "ইউজারনেম" at left above the first field.
4. Username field: rounded rectangle, off-white fill, thin red-to-purple gradient outline; placeholder "ইউজারনেম" centred.
5. Label "পাসওয়ার্ড" at left.
6. Password field: same style; shows "******" (six asterisks, masked) centred.
7. Login button: full-width pill, red-to-purple horizontal gradient, soft shadow, white text "লগইন".
8. Footer, centred grey text on three lines.

Right screen: the same page darkened; a dark rounded bottom sheet with a blue location pin at top, the question, two round map previews (Precise on the left with a blue ring, Approximate on the right), then three stacked text options.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| ইউজারনেম | Username | Field label (left, above field) and placeholder inside the field |
| পাসওয়ার্ড | Password | Field label |
| ****** | Masked password characters | Inside password field |
| লগইন | Login | Button |
| SR App | Product name line | Footer line 1 |
| (version - 1.0.25) | App version | Footer line 2 |
| App Developed by Apsis Solutions | Vendor credit | Footer line 3 |
| Allow ARON SR to access this device's location? | Android location permission question ("ARON SR" bold) | Right, sheet title |
| Precise | Precise location (selected, bold, blue ring) | Right |
| Approximate | Approximate location (not selected) | Right |
| While using the app | Grant while app in use (bold) | Right, option 1 |
| Only this time | Grant once | Right, option 2 |
| Don't allow | Deny | Right, option 3 |

## Data and figures shown
- App version 1.0.25 (see the update screens: 06 and 07 offer "Version 1.0.25 available"; 08 shows "Current version SR App 1.0.1").
- The password field shows six asterisks, an example value only.
- The username field shows the placeholder, not a user ID.

## Colours and style
- Gradient: red (#D9453F-ish) to deep purple (#4B1B96-ish), diagonal; indigo stripe about #4A1A9A.
- Fields: off-white fill, 1.5 dp gradient (red to purple) outline, about 12 dp radius.
- Button: horizontal red-to-purple gradient, white Bangla label, pill shape.
- Footer: small grey Latin text. Labels in Bangla, dark grey.
- Right: Android system sheet, dark grey, white text, blue accent.

## Navigation and behaviour
- Enter the username and password and tap লগইন.
- The location prompt appears on first use; the manual says to choose Precise and "While using the app", and that the app cannot be used without location.
- The next screen on a new device is OTP verification (04).
- No "forgot password", language switch or show-password eye is visible.

## Differences between the image and the manual text
- The manual does not mention the footer: "SR App (version - 1.0.25) App Developed by Apsis Solutions". The vendor credit would not appear in the AKTCL-owned rebuild.
- The version on the login screen is 1.0.25, while the in-app update screen (08) says "Current version SR App 1.0.1"; the two version strings disagree (probably one is a build/store version, the other a name).
- The manual says "Choose Precise"; in the image Precise is already shown selected (bold, blue ring).
- The manual calls the labels "Username/Password" in English; the image shows the Bangla words ইউজারনেম and পাসওয়ার্ড (the manual also quotes them).

## Open questions for the owner
- Does the version 1.0.25 here vs "1.0.1" on the update screen (08) mean two different numbers (versionName vs build)? Which one should the new app show?
- The manual says the app is unusable without location. Confirm the new app keeps this hard rule (and the Precise requirement).
