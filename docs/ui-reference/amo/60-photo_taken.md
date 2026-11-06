# 60. Photo taken

## Screens in the image
- LEFT: a camera viewfinder screen (full-screen camera, black bars top and bottom). It is the camera of the device or an in-app camera view opened by the vendor app after tapping "GEO এবং ছবি ধারণ করুন"; it is not the vendor's own form. The look is the phone's camera view with three round controls, not an app screen with a purple bar.
- RIGHT: the vendor app's তথ্য পরিবর্তন করুন (Change information) form, dimmed by a dark overlay, with the success dialog "ছবি ধারণ করা সম্পন্ন হয়েছে" on top (in-app dialog). Shown after the photo was taken.

## Layout, top to bottom
LEFT (camera):
1. Black band at the top (about 50px, empty).
2. Live view/photo: a street shopfront in Dhaka: an old grey building with windows and many overhead cables above; a shop with a red signboard in Bangla; goods hanging in the shop (packets, snacks); a man in a white cap and a long cream shirt standing at the left; another man in dark clothes sitting on a wooden bench in front of the shop; a blue barrel; a closed grey roller shutter at the right; a muddy/brown road in front. A small "TO-LET" paper sign hangs at the shop front.
3. Black band below the photo holding three round white-outlined controls on one row:
   - left: a camera-switch icon (circular arrows around a camera) in a thin white circle (switch front/rear camera);
   - centre: a large solid white circle (shutter button);
   - right: a thin white circle with a white X (close/cancel).
4. Bottom: Android gesture bar.

RIGHT (form, dimmed):
1. Top bar: dark purple (dimmed), outline back arrow, title "তথ্য পরিবর্তন করুন".
2. The form is scrolled: at the top the lower edge of a label is cut off; then a cluster drop-down "Madrasa Road" (violet border, black arrow).
3. Label "রিটেইলার নির্বাচন করুন"; retailer drop-down "Sujon Store (1618414--Madrasa Road)" (blue-ish border, grey arrow).
4. Details/fields: labels partly hidden behind the dialog ("দো…", "দো…"); label "মোবাইল নম্বর" and a text field with "01779236598".
5. Centred white rounded dialog: green circle with white tick; blue bold text "ছবি ধারণ করা সম্পন্ন হয়েছে"; bottom right a green "ওকে" button.
6. Below: purple button "GEO এবং ছবি ধারণ করুন" (dimmed, label visible) and green-gradient button "সংরক্ষণ" with save icon (dimmed).
7. Bottom: Android gesture bar.

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| তথ্য পরিবর্তন করুন | Change information | Right screen, top bar title |
| Madrasa Road | Cluster | Right screen, cluster drop-down |
| রিটেইলার নির্বাচন করুন | Select retailer | Right screen, label |
| Sujon Store (1618414--Madrasa Road) | Outlet display text; the owner-mobile part between the hyphens is empty (shown as "--") | Right screen, retailer drop-down |
| দো… (cut off) | Start of "দোকানের নাম" and "দোকানের মালিকের নাম" labels | Right screen, left of the dialog (two rows) |
| মোবাইল নম্বর | Mobile number | Right screen, label |
| 01779236598 | Mobile value (Latin digits) | Right screen, text field |
| ছবি ধারণ করা সম্পন্ন হয়েছে | Photo capture completed | Dialog message |
| ওকে | OK | Green dialog button |
| GEO এবং ছবি ধারণ করুন | Capture GEO location and photo | Purple button (dimmed) |
| সংরক্ষণ | Save | Green button (dimmed) |
| মাসুদ বিন হাবিব ভ্যারাইটিজ স্টোর | "Masud bin Habib Variety Store" - the shop's own signboard in the photo (content of the photo, not app text; read from the red signboard, small print around it unreadable) | Left screen, photo |
| TO-LET | Paper sign in the photo | Left screen, photo |

The camera screen itself has no text. Numbers are Latin digits.

## Data and figures shown
- Outlet code 1618414, owner mobile 01779236598 in the field (the outlet text in the drop-down shows no mobile between the hyphens, so the vendor builds the text as code-mobile-cluster and the mobile was empty in the list data but the field shows 01779236598; the discrepancy is unexplained).
- No totals or percentages.

## Colours and style
- Camera: black surround, white round controls, no app colour.
- Right form: dimmed deep purple top bar, lavender-white page, white cards, violet/blue bordered drop-downs and fields, purple GEO button, green-gradient save button.
- Dialog: white, big rounded corners, green circle with white tick, blue bold message, green "ওকে" button.
- The only photo is the camera view of the outlet (the picture the AMO takes of the outlet front). No packshots.

## Navigation and behaviour
- Per the manual (screens 60): tap "GEO এবং ছবি ধারণ করুন" on the Change information form; the camera opens (the same camera screen as in screens 20, 48 and 56); take the photo with the white shutter button; the app returns to the form and shows "ছবি ধারণ করা সম্পন্ন হয়েছে"; tap ওকে. Then tap সংরক্ষণ to save the corrected information with the photo and location.
- The camera-switch icon changes the camera; the X cancels without a photo.
- The photo is of the outlet front and the GPS location is taken at the same time (the button name says GEO and photo).

## Differences between the image and the manual text
- The manual says "Left: the camera. Right: photo captured"; the image also shows the three camera controls (switch camera, shutter, close X).
- The manual does not mention that the form here shows a different outlet (Sujon Store, Madrasa Road) from screen 59 (Billal Store), i.e. the two screenshots are from separate sessions.
- The retailer text "Sujon Store (1618414--Madrasa Road)" has an empty middle part; not mentioned.

## Open questions for the owner
- Is the camera in the vendor app an in-app camera view or the phone's own camera app (the left screen has no vendor chrome)?
- Is a photo mandatory before সংরক্ষণ becomes possible, or can the form be saved without GEO and photo?
