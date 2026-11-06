# 57. Permanent close

## Screens in the image
- LEFT: the vendor app's স্থায়ী বন্ধ (Permanent close) screen, in its normal state, with a cluster chosen, a retailer chosen and the shop details shown.
- RIGHT: the same screen dimmed by a dark overlay, with the vendor app's own "Are you sure?" confirmation dialog on top (an in-app dialog, not an Android system dialog). The dialog is shown after tapping সংরক্ষণ.
- Both are the ARON AMO app (Outlet > দোকান সংক্রান্ত অপারেশন > স্থায়ী বন্ধ). Status bar not visible; only the bottom gesture bar shows.

## Layout, top to bottom
LEFT screen:
1. Top bar: deep purple (flat, no visible gradient), a hollow/outline left-pointing back arrow at the left (white), title "স্থায়ী বন্ধ" centred in bold white.
2. Page background: very light lavender/pink-white.
3. Card 1 (white, rounded corners, soft): label "ক্লাস্টার নির্বাচন করুন" (dark text, top left of card), below it a drop-down box with a purple (violet) 1px rounded border showing "Madrasa Road" in larger dark text and a black down-triangle at the right. Enabled, value selected.
4. Card 2 (white, rounded): label "রিটেইলার নির্বাচন করুন", below it a drop-down box with a thinner blue-ish border showing "Kamal Store (1618408-1942222558-Madrasa Road)" in smaller dark text and a grey down-triangle at the right. Value selected.
5. Card 3 (white, rounded) with three details lines, label in Bangla then value in Latin text:
   - "দোকানের নাম:" then "Kamal Store (1618408-1942222558-Madrasa Road)" (wraps onto three lines, value column indented).
   - "দোকানের মালিকের নাম:" then "Kamal".
   - "মোবাইল নম্বর:" then "1942222558".
6. Empty space.
7. Full-width save button: rounded rectangle, green gradient (yellow-green at top to a fresher green at bottom, appears vertical gradient lime to green), white save (floppy disk) icon and white label "সংরক্ষণ" centred. Enabled.
8. Bottom: Android gesture bar only. No bottom navigation bar.

RIGHT screen (everything in the left screen, dimmed by a ~50% dark grey overlay; top bar darker purple):
1. Same top bar, title "স্থায়ী বন্ধ" (dimmed).
2. Same cards (dimmed). Card 3 is partly hidden behind the dialog; only the first letters of labels ("দো", "দো", "মো") peek out at the left of the dialog.
3. Centred white rounded dialog: a blue filled circle with a white "i" (info icon) at the top centre; below it the blue bold title "আপনি কি নিশ্চিত?"; below it black body text "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে"; at the bottom right two small buttons side by side with a soft shadow: green "হ্যাঁ" (left of the pair) and red "না" (right of the pair), white labels.
4. Dimmed green gradient সংরক্ষণ button below the dialog.

## Every visible text, verbatim
| Text | English meaning | Where it appears |
|---|---|---|
| স্থায়ী বন্ধ | Permanent close | Top bar title (both screens) |
| ক্লাস্টার নির্বাচন করুন | Select a cluster | Label of card 1 |
| Madrasa Road | Cluster name | Drop-down 1 value |
| রিটেইলার নির্বাচন করুন | Select retailer (outlet) | Label of card 2 |
| Kamal Store (1618408-1942222558-Madrasa Road) | Outlet display text: shop name (outlet code - owner mobile without leading 0 - cluster) | Drop-down 2 value; also in details card |
| দোকানের নাম: | Shop name: | Details card, line 1 label |
| দোকানের মালিকের নাম: | Shop owner's name: | Details card, line 2 label |
| Kamal | Owner name | Details card, line 2 value |
| মোবাইল নম্বর: | Mobile number: | Details card, line 3 label |
| 1942222558 | Owner mobile (Latin digits, no leading 0 shown) | Details card, line 3 value |
| সংরক্ষণ | Save | Green button (both screens) |
| আপনি কি নিশ্চিত? | Are you sure? | Dialog title (right) |
| এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে | This shop is going to be closed permanently | Dialog body (right) |
| হ্যাঁ | Yes | Green dialog button (right) |
| না | No | Red dialog button (right) |

All numbers are in Latin digits. No units, thousand separators or percent signs. In the outlet text, "1618408" looks like the outlet code and "1942222558" the owner mobile with the leading 0 dropped (manual does not explain; see questions).

## Data and figures shown
- Example cluster: Madrasa Road. Example outlet: Kamal Store, code 1618408, owner Kamal, mobile 1942222558.
- The details card repeats the selected retailer's name, owner and mobile (read-only text, not editable fields).
- No totals or percentages.

## Colours and style
- Top bar: deep purple/indigo (flat), white bold title, outline back arrow. On the right screen it is the same purple darkened by the overlay.
- Page background: very light lavender-white. Cards: white, rounded corners, very light shadow.
- Drop-down boxes: white fill, rounded, 1px border (violet for cluster, blue-ish for retailer), black arrow.
- Save button: green vertical gradient (lime-yellow top, green bottom), rounded corners, white text and icon.
- Dialog: white, large rounded corners, blue info icon and blue bold title, black body text, green "হ্যাঁ" and red "না" buttons with white text and light drop-shadow.
- Overlay: dark grey about 50% opacity.
- No packshots or photos.

## Navigation and behaviour
- Per the manual (18.5 Close a shop permanently): choose the cluster and the retailer, tap সংরক্ষণ, then হ্যাঁ. After the retailer is selected, the shop name, owner and mobile appear in the details card.
- Tapping সংরক্ষণ opens the confirmation dialog; হ্যাঁ confirms (leads to screen 58 "Data saved successfully"); না is the cancel choice (dialog closes; manual does not say more).
- Back arrow at the top left returns to the Outlet menu.
- This is the AMO's own close of a shop; the SR-requested closes are handled in a separate verification screen (screens 50/51).

## Differences between the image and the manual text
- The manual says "shop details with সংরক্ষণ"; the image also shows that the details card has three Bangla-labelled lines (shop name, owner name, mobile).
- The manual does not say what "না" does and does not mention that it is red.
- The manual does not mention that the retailer drop-down uses a different (blue) border and smaller text from the cluster drop-down.
- The mobile number is displayed without a leading zero (1942222558); the manual does not mention this.

## Open questions for the owner
- Is the missing leading 0 in the mobile (1942222558) and in the outlet text a data quirk of the vendor app or intended? (Screen 59 shows 01624788895 with the 0.)
- Is "না" simply close-dialog-and-stay on the screen?
