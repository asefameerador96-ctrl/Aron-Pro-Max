# 04. OTP verification (first login on a device)

## Screens in the image
Two phone screenshots side by side of the same screen. Left: the empty OTP screen. Right: the same screen with the code 3 5 6 0 typed into the four boxes.

## Layout, top to bottom
1. Top bar: red-to-purple horizontal gradient (red at left, indigo-purple at right), about 55 dp high. White back chevron at left; centred white title "OTP যাচাইকরণ" (the "OTP" part in a serif-like Latin face, the Bangla in a Bangla font).
2. Page background: light grey (about #E8E8E8).
3. Large heading "Enter OTP", centred, red (about #D9453F), bold.
4. Subtitle line, grey, centred.
5. Four separate square boxes in a row: rounded corners, off-white fill, red outline with a soft red glow. Left: empty. Right: digits in indigo/purple.
6. Full-width green pill button (teal green, about #26806F) with white text "Verify" and a soft shadow.
7. Lower centre: a three-line red paragraph in a lighter weight.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| OTP যাচাইকরণ | OTP verification | Top bar title |
| Enter OTP | Heading | Centre, red |
| Enter the 4-digit OTP provided by your TSO. | Instruction | Under the heading |
| 3 / 5 / 6 / 0 | Example digits typed (right screen) | The four boxes |
| Verify | Verify button | Green button |
| Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process. | Explanation (grammar as shown in the app) | Red text near the bottom |

## Data and figures shown
- Four-digit OTP, example "3560" (right screen); digits are Latin, in indigo.
- No timer, no resend link, no countdown, and no phone number are shown.

## Colours and style
- Top bar: the red-to-purple gradient of the existing Aron; title white.
- Heading and notice: crimson red; subtitle grey.
- Boxes: off-white with a red outline and glow; digits indigo (about #3B1A8F).
- Verify button: green, not the red-to-purple gradient used on Login; this is an exception to the gradient look.
- The English text in this screen is mixed (English in the body, Bangla only in the title).

## Navigation and behaviour
- Shown after login on a new device or after installing a new version.
- The SR asks their TSO for the code, types 4 digits and taps Verify; success proceeds to the home dashboard.
- The back chevron returns to Login.
- No resend or expiry behaviour is visible.

## Differences between the image and the manual text
- The manual matches. The image adds the red explanation paragraph (with its non-standard English: "Its look like you are trying to login in a new device") and the Bangla title "OTP যাচাইকরণ", which the manual does not quote.
- The right screen shows filled-in digits (3560); the manual shows only that there are four boxes.
- The manual says "provided by your TSO"; the image confirms the TSO is the OTP source (not SMS).

## Open questions for the owner
- How does the TSO get the OTP (a TSO-portal screen, per docs/34)? Does it expire, and is there a retry limit?
- Should the English text be corrected and translated to Bangla in the rebuild, or kept word for word?
