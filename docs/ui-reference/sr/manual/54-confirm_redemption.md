# 54. Confirm redemption

## Screens in the image
Two phone screens side by side, both the Gift Redemption page dimmed behind a modal dialog. Left: confirmation dialog. Right: success dialog. Manual screenshot 54.

## Layout, top to bottom
Both screens: dark-dimmed overlay over the page of screenshot 53 (top bar "Gift Redemption", retailer drop-down "Bipu Store (6909084-01779223366-Apsis Cluster)", letter strip "All A B M R S a b j o s t", banner "Diamond League (April)" with pill "০ Pts Remaining", gift cards, bottom button "Confirm Redemption").
Left dialog (white, rounded, soft red glow): blue circle info icon with white "i"; text "Confirm redemption of these items? Points will be deducted." (bold, centred); two buttons at the bottom: green "নিশ্চিত করুন" and red "বাতিল".
Right dialog (white, larger radius): green circle check icon; blue text "Redemption successful"; one green button "ওকে" at the bottom right.
Behind the dialogs: the first row of cards has teal borders on both cards (selected look), partly hidden; the lower row shows "চেয়ার।" chip "২০০" and "টর্নেডো ফ্যান।" chip "৪০০", with "0" and greyed "-" and "+" buttons.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| Gift Redemption | Page title | Top bar (dimmed) |
| Bipu Store (6909084-01779223366-Apsis Cluster) | Retailer | Drop-down (dimmed) |
| Diamond League (April) | Campaign | Banner |
| ০ Pts Remaining | 0 points remaining | Banner pill (dimmed; Bangla zero) |
| চেয়ার। / ২০০ | Chair / 200 | Card behind dialog |
| টর্নেডো ফ্যান। / ৪০০ | Tornado fan / 400 | Card behind dialog |
| Confirm Redemption | Confirm Redemption | Bottom button (dimmed) |
| Confirm redemption of these items? Points will be deducted. | Confirmation question | Left dialog |
| নিশ্চিত করুন | Confirm | Green button |
| বাতিল | Cancel | Red button |
| Redemption successful | Redemption successful | Right dialog |
| ওকে | OK | Green button |

## Data and figures shown
- The banner behind both dialogs reads ০ Pts Remaining (it was ২২০ in screenshot 53). The dialogs do not say which items or how many points. The visible chip values are ২০০ and ৪০০; the selected items in the upper row are hidden behind the dialog, so the deduction cannot be derived from the image.
- No item list, no total, no retailer name inside the dialog.

## Colours and style
- Overlay dark (page dimmed to about 50 percent black); dialogs white; info icon blue (#1E88E5-like); success tick green (#4CAF50-like); "Redemption successful" in blue text; confirm and OK buttons green (#4CAF50), cancel red (#F44336). Dialog text in English (Roboto-like); button labels Bangla.

## Navigation and behaviour
- Manual: "Confirm redemption of these items? Points will be deducted." then "Redemption successful". What to do: "Tap নিশ্চিত করুন, then ওকে."
- Flow: tap "Confirm Redemption" on the page, the confirm dialog appears; "নিশ্চিত করুন" submits; the success dialog appears; "ওকে" closes it. "বাতিল" is shown as the cancel choice (its effect is not described in the manual).

## Differences between the image and the manual text
- The dialog texts are English while the buttons are Bangla (the manual quotes the English text).
- The manual does not mention "বাতিল".
- The manual does not mention that the banner shows ০ Pts Remaining after success.

## Open questions for the owner
- Does the success dialog appear only after the server accepts, or offline too (nothing is visible about offline behaviour)?
- Why does the banner show zero while only chips of 200 and 400 are visible? Confirm which items were selected; do not infer.
- Loyalty is deferred (docs/27): hooks only.
