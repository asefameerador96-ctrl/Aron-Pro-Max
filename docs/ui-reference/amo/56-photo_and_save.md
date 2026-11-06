# 56. Photo and save

## Screens in the image
- Two phone screens side by side. Left: the camera capture screen (a camera viewfinder, system or camera-plugin UI, not the normal app chrome) opened by "GEO এবং ছবি ধারণ করুন". Right: the vendor AMO app "নতুন দোকান" form, dimmed, with an in-app success dialog on top.

## Layout, top to bottom
Left screen (camera):
1. Black top margin (thin).
2. Live viewfinder photo, filling most of the screen: a shopfront in a narrow Dhaka street with tangled overhead wires, a green/white building above, a shop with a Bangla signboard (red with white text, apparently "মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর"), hanging packets and drinks, a seated man on a bench, a standing man in white cap and kurta at the left edge, a closed shutter at right. Not app text.
3. Black control strip at the bottom with three round controls, left to right: camera-switch (rotate/flip arrows) in a thin white-outlined circle; large white solid shutter circle in the centre; close "X" in a thin white-outlined circle at right.
4. Bottom: gesture bar.

Right screen (dimmed):
1. Top bar: very dark purple (dimmed), back arrow at left, title "নতুন দোকান" centred.
2. Label "ক্লাস্টার নির্বাচন করুন" and cluster drop-down showing "Madrasa Road" (outlined, down triangle). The route drop-down is scrolled out of view (not visible).
3. Label "দোকানের নাম"; input filled with "Aziz Store".
4. Partly hidden by the dialog: next input label (only the first letter visible, "দো" for the owner name) and its input (value hidden); next label (only "মো" visible, for mobile number) and an input showing "01723652545" (digits partly hidden behind the dialog edge; last digits read as 01723652545).
5. Modal dialog (white, large rounded corners, shadow) over the form: green circle with white check mark; blue bold Bangla message "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" (ends with a danda); green rounded button "ওকে" with white text at the dialog's bottom-right.
6. Below the dialog (dimmed): wide dark purple button "GEO এবং ছবি ধারণ করুন", and a green gradient button with save icon and text "সংরক্ষণ".
7. Bottom: gesture bar.

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| নতুন দোকান | New shop | Top bar title (right) |
| ক্লাস্টার নির্বাচন করুন | Select a cluster | Right, label above cluster drop-down |
| Madrasa Road | Cluster name | Right, cluster drop-down value |
| দোকানের নাম | Shop name | Right, input label |
| Aziz Store | Shop name entered | Right, shop name input |
| 01723652545 | Mobile number entered (Latin digits; partly hidden by dialog) | Right, mobile input |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে। | Data saved successfully. | Right, dialog message |
| ওকে | OK | Right, dialog button |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo | Right, purple button |
| সংরক্ষণ | Save | Right, green button |
| মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর | Shop signboard in the photo (not app text; reading approximate) | Left, inside the photo |
| TO-LET | Sign in the photo (not app text) | Left, inside the photo |

## Data and figures shown
- Example new shop: cluster Madrasa Road, shop name Aziz Store, mobile 01723652545 (Latin digits). The owner-name value is hidden. No totals.

## Colours and style
- Camera: black surround, white round controls; stock camera UI.
- Right screen: top bar very dark purple (dimmed); dialog white, rounded (about 28 dp), green check circle, blue bold message, green OK button; GEO button dark purple; save button green gradient (olive/yellow-green to green) with white save icon. Page behind is dimmed grey by a scrim.
- The only photograph is the camera viewfinder (the shop photo being captured); no packshots.

## Navigation and behaviour
- Per the manual: take the photo (shutter button), tap সংরক্ষণ, then ওকে. The camera screen has switch-camera and close (X) controls. After the photo and save, the success dialog confirms; ওকে closes it (the form is then reset or returns to the previous screen, not shown).

## Differences between the image and the manual text
- The manual does not mention the camera-switch and X controls, nor that the GEO and photo capture opens a full-screen camera.
- The dialog message ends with a Bangla full stop "।"; the manual quotes it without it.
- The dialog message is in Bangla here, unlike the English dialog of screenshot 53.
- The route drop-down is not visible on the right screen (scrolled up), unlike screens 54 and 55.

## Open questions for the owner
- none
