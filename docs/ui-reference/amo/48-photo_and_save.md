# 48. Photo and save

## Screens in the image
Two portrait phone screens side by side.
- LEFT: a camera viewfinder (full-screen camera capture UI: live photo of a street shop, black bars top and bottom, three round controls at the bottom). It is the camera screen opened by the vendor app after tapping "GEO এবং ছবি ধারণ করুন"; it looks like a standard Android/CameraX camera, not the app's own purple styling.
- RIGHT: the vendor ARON AMO app screen "নতুন দোকান" (New shop), the completed form, with the action buttons at the bottom.

## Layout, top to bottom
LEFT screen (camera):
1. Black strip at the top.
2. Large live camera preview (about 3/4 of the height, portrait 3:4): a street-side shop in a corrugated-tin-roofed building under tangled overhead wires. A signboard in Bangla (red letters on white): "মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর" (Masud Bin Habib Variety Store). A small white "TO-LET" sign hangs near the shop. Shelves with hanging snack packets, a man in a dark shirt sitting on a bench, a man in a white cap and cream kurta standing at the left edge, a closed grey roll-down shutter at right, brick-paved road in front.
3. Black bottom bar with three round controls in one row: left, a small circle-outlined "switch camera" icon (two circular arrows around a dot); centre, a large solid white circle (shutter button); right, a small circle-outlined white "X" (close/cancel).
4. Android gesture bar at the very bottom.

RIGHT screen (form):
1. Top bar, solid deep purple, white back arrow at left, white bold title "নতুন দোকান" centred.
2. Lavender-white page with one white rounded card containing:
   - label "দোকানের নাম", outlined violet-bordered field "Ashraf Store"
   - label "দোকানের মালিকের নাম", field "Ashraf"
   - label "মোবাইল নম্বর", field "01772369852"
   - label "Sub-Channel", violet-bordered drop-down with grey placeholder "Select Sub-Channel" and down-arrow
   - label "Geo Classification", violet-bordered drop-down with grey placeholder "Select Geo Classification" and down-arrow
   (The route and cluster drop-downs seen in screenshot 47 are not visible on this screen.)
3. Wide button, full width, rounded, blue-to-purple gradient (blue left, deep purple right), white text "GEO এবং ছবি ধারণ করুন" (no icon).
4. Below it, two buttons side by side with a small gap:
   - Left: solid red button, white floppy-disk (save) icon then white text "বাতিল".
   - Right: green gradient button (green to yellow-green), white floppy-disk (save) icon then white text "সংরক্ষণ".
5. Android gesture bar at the bottom (black strip).

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | where it appears |
|---|---|---|
| নতুন দোকান | New shop | right screen top bar title |
| দোকানের নাম | Shop name | right screen field label |
| Ashraf Store | shop name entered | right screen text field |
| দোকানের মালিকের নাম | Shop owner's name | right screen field label |
| Ashraf | owner name entered | right screen text field |
| মোবাইল নম্বর | Mobile number | right screen field label |
| 01772369852 | mobile number (Latin digits) | right screen text field |
| Sub-Channel | Sub-channel | right screen label |
| Select Sub-Channel | placeholder, nothing chosen | right screen drop-down |
| Geo Classification | Geo classification | right screen label |
| Select Geo Classification | placeholder, nothing chosen | right screen drop-down |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo | blue-purple button |
| বাতিল | Cancel | red button |
| সংরক্ষণ | Save | green button |
| মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর | Masud Bin Habib Variety Store (signboard in the photo, not app text) | camera preview |
| TO-LET | small hanging sign in the photo (not app text) | camera preview |

## Data and figures shown
- No totals. The form data are the outlet details: shop name Ashraf Store, owner Ashraf, mobile 01772369852 (Latin digits, 11 digits).
- The camera photo is the outlet picture taken at the outlet; the image shows one example shop (signboard names a different shop from the form, so the photo is only an example).
- Sub-Channel and Geo Classification are still unselected in this capture.

## Colours and style
- Top bar deep purple (about #4B1A92); page lavender-white; one white rounded card holds the fields.
- Input boxes white with violet/purple 1.5 px borders, rounded about 8 px.
- "GEO এবং ছবি ধারণ করুন" button: blue to deep-purple horizontal gradient, white text, rounded about 4 px.
- Cancel button: pure red (#FF0000-like), white text and white save icon.
- Save button: green to yellow-green gradient, white text and save icon.
- Camera screen: black chrome; controls are white outlines/solid white on black. No packshots.
- Note: both Cancel and Save carry the same floppy-disk icon.

## Navigation and behaviour
- Per the manual: tapping "GEO এবং ছবি ধারণ করুন" captures the current GPS location and opens the camera; the AMO takes the outlet photo at the outlet. The left screen is that camera.
- Camera controls: centre white circle takes the photo, left icon switches front/back camera, right X closes the camera without a photo.
- After the photo, the AMO taps "সংরক্ষণ" (Save) to save the new outlet; "বাতিল" (Cancel) abandons the form.
- Annex A also lists a success message "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (Data saved successfully) and a confirmation "আপনি কি নিশ্চিত?" (Are you sure?), but neither is visible here.

## Differences between the image and the manual text
- The manual says only that the left shows the camera and the right the completed form. The image shows the camera has switch-camera and close controls, and the form here has no route or cluster drop-downs visible (they are shown in 47).
- The manual's "Cancel" and "Save" are both drawn with a save (floppy-disk) icon in the image.
- No visible indication that the photo has been taken or attached on the right form (no thumbnail, no tick).
- The form data differ from screenshot 47 (Ashraf Store instead of Rakib Store): a different request or sample entry.

## Open questions for the owner
- After the photo is captured, is a thumbnail or confirmation shown on the form? None is visible here.
- Does the app check distance or mock-location when "GEO এবং ছবি ধারণ করুন" is tapped (the manual does not say)?
