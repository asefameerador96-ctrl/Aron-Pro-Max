# 58. Close saved

## Screens in the image
- Single screen: the vendor app's স্থায়ী বন্ধ screen (Permanent close) dimmed under a dark overlay, with a success dialog on top. It is an in-app dialog of the ARON AMO app, shown after হ্যাঁ was tapped on the confirmation of screen 57. No Android system dialog.

## Layout, top to bottom
1. Top bar: very dark purple (dimmed by overlay), outline back arrow at the left, title "স্থায়ী বন্ধ" centred (dimmed grey-white).
2. Page and cards dimmed by a dark grey overlay (about 50%): card 1 label "ক্লাস্টার নির্বাচন করুন" with drop-down "Madrasa Road" (violet border, black arrow); card 2 label "রিটেইলার নির্বাচন করুন" with drop-down "Kamal Store (1618408-1942222558-Madrasa Road)" (blue border, grey arrow). Both still show the earlier selections.
3. Card 3 (details) is partly hidden behind the dialog; only parts of the first letters of labels ("দো", "দো") and the lower line "মোবাইল নম্বর: 1942222558" (partly covered) are visible.
4. Centred white rounded dialog: a green filled circle with a white check mark at the top centre; below it the blue bold text "ডেটা সফলভাবে সংরক্ষিত হয়েছে." (ends with a period-like mark); at the bottom right one small green button "ওকে" (white label, soft shadow). Enabled.
5. Below the dialog, the dimmed green-gradient "সংরক্ষণ" button with a save icon (dimmed, olive-green in the overlay).
6. Bottom: Android gesture bar only.

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| স্থায়ী বন্ধ | Permanent close | Top bar title |
| ক্লাস্টার নির্বাচন করুন | Select a cluster | Card 1 label (dimmed) |
| Madrasa Road | Cluster | Drop-down 1 (dimmed) |
| রিটেইলার নির্বাচন করুন | Select retailer | Card 2 label (dimmed) |
| Kamal Store (1618408-1942222558-Madrasa Road) | Outlet display text | Drop-down 2 (dimmed) |
| দো… | Start of "দোকানের নাম" / "দোকানের মালিকের নাম" labels (cut off by the dialog) | Details card, left edge, partly visible |
| মোবাইল নম্বর: 1942222558 | Mobile number: 1942222558 (partly covered) | Details card, last line, partly visible |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে. | Data saved successfully. | Dialog message |
| ওকে | OK | Green dialog button |
| সংরক্ষণ | Save | Dimmed button below the dialog |

Digits are Latin. The final mark of the message looks like a full stop "." rather than the Bangla danda "।"; the manual's Annex A writes it without a mark.

## Data and figures shown
- The same example as screen 57: cluster Madrasa Road, outlet Kamal Store (code 1618408), owner mobile 1942222558. No totals.

## Colours and style
- Overlay dark grey about 50%; top bar very dark purple under the overlay.
- Dialog: white, large rounded corners, green circle with white tick, blue bold message text, green rounded "ওকে" button with white text.
- Background form as in screen 57 (lavender-white page, white cards, violet/blue bordered drop-downs).
- No packshots or photos.

## Navigation and behaviour
- Per the manual: tap ওকে. The dialog closes (the manual does not say what is shown next; the form is probably reset or the screen closes).
- The shop is thereby marked permanently closed; the data is saved (offline-first rebuild: save locally and sync).

## Differences between the image and the manual text
- none (the manual only says the message "ডেটা সফলভাবে সংরক্ষিত হয়েছে" and ওকে; the image adds the green tick icon and the end mark).

## Open questions for the owner
- After ওকে, does the app return to the Outlet menu or stay on the cleared Permanent close form?
