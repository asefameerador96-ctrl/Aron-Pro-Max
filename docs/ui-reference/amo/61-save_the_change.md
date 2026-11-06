# 61. Save the change

## Screens in the image
Two phone screens side by side, both the vendor ARON AMO app (the "Change information" form, screen title তথ্য পরিবর্তন করুন), each with a modal dialog over a dimmed form. No Android system dialog, no other app.
- Left: confirmation dialog "Are you sure? This change will be saved" with Yes/No.
- Right: success dialog "Data saved successfully" with OK.
Status bar is not visible (cropped); a black gesture bar strip sits at the very bottom.

## Layout, top to bottom
Both screens share the same background form (dimmed by a dark scrim, so the form colours look greyed).
1. Top bar: deep purple (indigo), full width. Back arrow at left (small outlined left-pointing triangle/arrow glyph, white, dimmed by scrim). Centred white Bangla title "তথ্য পরিবর্তন করুন".
2. Top of the scrolling form: a sliver of a label text cut off just under the top bar (unreadable; it is the label of the first drop-down, presumably the cluster/route label).
3. Card 1 (very light grey/lavender rounded card): a drop-down (outlined, purple-indigo border, rounded) showing the value "Madrasa Road" with a black down-arrow (filled triangle) at right. Enabled, value selected.
4. Card 2: label "রিটেইলার নির্বাচন করুন" (Select retailer) above a drop-down (purple border, rounded) with value "Sujon Store (1618414--Madrasa Road)" and a smaller down-arrow at right. Enabled, value selected.
5. Card 3 (partly hidden by the dialog): first field label begins with "দো" (cut off; per the manual this is দোকানের নাম, shop name) and an outlined text box beneath it (hidden by dialog); then a second label also beginning "দো" (দোকানের মালিকের নাম, owner name) and a second text box (hidden). Contents of these boxes are not visible.
6. Same card: label "মোবাইল নম্বর" (Mobile number), then an outlined text box (purple border, rounded) with value 01779236598.
7. Dialog (centred white rounded card, soft shadow, over the scrim) - see section below for each screen.
8. Bottom button 1: full-width solid deep-purple rounded button, white text "GEO এবং ছবি ধারণ  করুন" (note a double space before করুন as rendered). Enabled (dimmed by the scrim).
9. Bottom button 2: full-width rounded button with green gradient (olive/yellow-green at top to green at bottom), a white floppy-disk "save" icon at left of the white text "সংরক্ষণ". Enabled (dimmed by scrim).
10. Bottom gesture bar (black strip with grey pill).

Dialog, left screen:
 a. Blue filled circle with white "i" (info icon), centred at top.
 b. Blue Bangla heading "আপনি কি নিশ্চিত?".
 c. Black body text "এই পরিবর্তন সংরক্ষণ করা হবে".
 d. Two small buttons bottom-right: green "হ্যাঁ" (Yes) then red "না" (No), white bold text, slightly rounded, with shadow.
Dialog, right screen:
 a. Green filled circle with white tick (success icon), centred.
 b. Blue Bangla message "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" (ends with the danda).
 c. One small green button bottom-right "ওকে" (OK).

## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| তথ্য পরিবর্তন করুন | Change information | Top bar title (both screens) |
| Madrasa Road | Cluster/area name (selected value) | First drop-down, top card |
| রিটেইলার নির্বাচন করুন | Select retailer | Label above second drop-down |
| Sujon Store (1618414--Madrasa Road) | Outlet name (outlet code 1618414, area Madrasa Road) | Second drop-down value |
| দো... (cut off) | Shop name / owner name labels (cut off by dialog) | Two labels partly hidden behind dialog |
| মোবাইল নম্বর | Mobile number | Label above phone field |
| 01779236598 | Mobile number value (Latin digits, no separators) | Phone text box |
| GEO এবং ছবি ধারণ  করুন | Capture GEO location and photo | Purple button |
| সংরক্ষণ | Save | Green button (with save icon) |
| আপনি কি নিশ্চিত? | Are you sure? | Left dialog heading (blue) |
| এই পরিবর্তন সংরক্ষণ করা হবে | This change will be saved | Left dialog body (black) |
| হ্যাঁ | Yes | Left dialog, green button |
| না | No | Left dialog, red button |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে। | Data saved successfully. | Right dialog message (blue) |
| ওকে | OK | Right dialog, green button |

Digits: all numbers are Latin digits (1618414, 01779236598). English labels/values in the drop-downs are shown in English text.

## Data and figures shown
- Outlet in the drop-down: "Sujon Store", outlet id 1618414, area "Madrasa Road" (the drop-down format is Name (OutletCode--Area)).
- Mobile number 01779236598 (11 digits, Bangladeshi format).
- No totals, percentages or currency.

## Colours and style
- Top bar: deep purple/indigo (about #4A148C-class), white title.
- Page background (behind scrim): very light lavender-grey; form sections are slightly darker rounded cards. Whole form is dimmed by a ~50% dark scrim while a dialog is open.
- Inputs/drop-downs: white fill, 1-2 px purple-indigo border, rounded corners (~8 px), black text, black triangle arrows.
- Button "GEO এবং ছবি ধারণ করুন": solid deep purple, white text, rounded (~8 px).
- Button "সংরক্ষণ": green gradient (yellow-green to green), white text, save icon.
- Dialog: white, large rounded corners (~28 px), shadow; heading and success message in blue; icon blue "i" circle (left) or green tick circle (right).
- Dialog buttons: green (Yes/OK) and red (No), white text, small, rounded.
- No progress bars, no packshots.

## Navigation and behaviour
Per the manual: after correcting the information, tap সংরক্ষণ; the confirmation dialog appears; tap হ্যাঁ to save (না cancels and returns to the form); the success dialog "Data saved successfully" appears; tap ওকে to dismiss. Back arrow returns to the previous screen.

## Differences between the image and the manual text
- The manual says the right dialog reads "Data saved successfully" (English meaning); the image shows the Bangla "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" with a trailing danda (same as screenshot 58 text).
- The manual does not mention the "না" red button explicitly (it only says tap হ্যাঁ), nor the info/tick icons.
- The image shows no status bar (cropped); the form behind shows the route drop-down value "Madrasa Road" which the manual calls a "cluster"/route field (label not readable here).

## Open questions for the owner
- The first drop-down label (cut off) is unreadable here; confirm it is "Cluster" (the manual's 59 lists route, retailer, alphabet bar, cluster, shop name, owner name, mobile number).
