# 43. Permanent close

## Screens in the image
Two phone screens side by side, both the স্থায়ী বন্ধ (Permanent close) page. Left: initial state, nothing selected. Right: cluster "111 No Road" and retailer selected, with the shop details and the save button shown.

## Layout, top to bottom
Both screens: status bar 3:13, location pin, 4G, signal, battery 67. Header red-to-purple gradient, white back chevron, a round pink/blue badge icon with a "CLOSE" hanging sign, white title "স্থায়ী বন্ধ". Page light grey.

Left screen:
1. White card: label "ক্লাস্টার নির্বাচন করুন", drop-down with purple outline, grey placeholder "ক্লাস্টার নির্বাচন করুন", black arrow.
2. White card: label "রিটেইলার নির্বাচন করুন", box with purple outline and grey placeholder "রিটেইলার নির্বাচন করুন" (no arrow visible).
3. Centred plain text "রিটেইলার নির্বাচন করুন" below the cards (an empty-state hint).

Right screen:
1. Cluster card: drop-down shows "111 No Road" (black) with arrow.
2. Retailer card: drop-down shows a yellow/orange circle and "Mojammel Store (3700936-123-111 No Road)" with a small arrow.
3. White details card with three lines, label in dark text and value in light grey: "দোকানের নাম:" "Mojammel Store (3700936-123-111 No Ro" (cut off at the edge), "দোকানের মালিকের নাম:" "Mojammel", "মোবাইল নম্বর:" "123".
4. Wide crimson-to-purple gradient button with white save icon and "সংরক্ষণ".

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| স্থায়ী বন্ধ | Permanent close | Header |
| ক্লাস্টার নির্বাচন করুন | Select cluster | Label and placeholder |
| রিটেইলার নির্বাচন করুন | Select retailer | Label, placeholder, and centred hint (left) |
| 111 No Road | Selected cluster | Right screen cluster drop-down |
| Mojammel Store (3700936-123-111 No Road) | Selected retailer: name (outlet code - mobile - cluster) | Right screen retailer drop-down |
| দোকানের নাম: | Shop name: | Details card |
| Mojammel Store (3700936-123-111 No Ro... | Shop name value, cut off | Details card |
| দোকানের মালিকের নাম: | Shop owner's name: | Details card |
| Mojammel | Owner name value | Details card |
| মোবাইল নম্বর: | Mobile number: | Details card |
| 123 | Mobile value (test data) | Details card |
| সংরক্ষণ | Save | Button |
| 3:13 / 67 | Clock / battery | Status bar |

## Data and figures shown
- The retailer label has the pattern "<name> (<number>-<number>-<cluster>)": 3700936 likely an outlet code, 123 the mobile test value, then the cluster. This is an inference; the image does not say what 3700936 is.
- The yellow/orange dot before the retailer name is a status marker; its meaning is not stated.
- Mobile "123" is test data.

## Colours and style
- Header gradient red (about #D94A4A) to purple (about #4A1A9A); white title.
- Cards white; inputs purple-outlined (about #4B1F8C); detail values light grey (about #AAAAAA), labels dark.
- Save button: red/crimson (about #D04848) to purple (about #7A2A7E) gradient, white text.
- Yellow dot: about #F5B921.

## Navigation and behaviour
- Manual: choose the cluster and retailer; the shop details appear; tap সংরক্ষণ, then confirm "this shop will be closed permanently". The request goes to the AMO for verification.
- Flow in image: (1) pick cluster, (2) the retailer list is filled for that cluster, (3) details card appears read-only, (4) Save.
- The confirmation dialog is not in the image.

## Differences between the image and the manual text
- The manual mentions the confirmation text "this shop will be closed permanently"; it is not visible here and its Bangla wording is unknown.
- The manual does not mention the yellow dot or the centred hint line on the empty state.

## Open questions for the owner
- Exact Bangla text of the confirmation dialog and its buttons.
- What does the yellow dot on the retailer entry mean, and what is the number 3700936 in the label?
- Is a reason for closure collected? None is shown.
