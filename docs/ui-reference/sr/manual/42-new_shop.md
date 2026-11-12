# 42. New shop

## Screens in the image
Two phone screens side by side. Left: the নতুন দোকান (New shop) form, empty. Right: the same flow's cluster picker dialog, but shown over the স্থায়ী বন্ধ (Permanent close) page (the header and the page behind the dialog belong to Permanent close, not New shop). The right-hand screen is therefore a cluster search list shared by the outlet screens.

## Layout, top to bottom
Left screen:
1. Status bar: 3:13, a screenshot/scan icon, location pin, 4G with arrows, two signal bars, battery 67.
2. Header gradient red (left) to deep purple (right), white back chevron, a shop-with-plus icon (red/blue gradient) and the white title "নতুন দোকান".
3. Page light grey (about #E8E8E8).
4. White card 1: label "ক্লাস্টার নির্বাচন করুন", then a drop-down with purple outline and grey placeholder "ক্লাস্টার নির্বাচন করুন" and a black down-arrow.
5. White card 2: three labelled text boxes with purple outline, all empty: "দোকানের নাম", "দোকানের মালিকের নাম", "মোবাইল নম্বর".
6. Button 1: wide, purple-to-crimson gradient (left purple, right crimson), white text "GEO এবং ছবি ধারণ  করুন" (the GEO word is Latin).
7. Button 2: wide, crimson-to-purple gradient, a white save/floppy icon and "সংরক্ষণ".
8. Android navigation bar (three lines, circle, back).

Right screen (dimmed): header "স্থায়ী বন্ধ" with a CLOSE sign icon; behind the dialog, the cluster card label partly visible ("ক্লাস্") and a second card ("রিটে" cut). Dialog: pale lavender (about #EBE5F0) rounded panel, bold centred title "ক্লাস্টার নির্বাচন করুন", a divider, a search row with centred placeholder "জায়গা সার্চ করুন" and a red circled-x clear icon on the right, a divider, then a centred list of 10 clusters.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| 3:13 | Phone clock | Status bar, both screens |
| 67 | Battery | Status bar |
| নতুন দোকান | New shop | Header, left screen |
| ক্লাস্টার নির্বাচন করুন | Select cluster | Field label and placeholder (left); dialog title (right) |
| দোকানের নাম | Shop name | Field label |
| দোকানের মালিকের নাম | Shop owner's name | Field label |
| মোবাইল নম্বর | Mobile number | Field label |
| GEO এবং ছবি ধারণ  করুন | Capture GEO and photo | Button 1 (double space before করুন as shown) |
| সংরক্ষণ | Save | Button 2 |
| স্থায়ী বন্ধ | Permanent close | Header of right screen (dimmed) |
| জায়গা সার্চ করুন | Search place | Dialog search placeholder |
| 111 No Road | Cluster name | Dialog list item 1 |
| 108 No Road | Cluster name | item 2 |
| Road No 108 | Cluster name | item 3 |
| Road No 108,House 14 | Cluster name | item 4 |
| Lakepar | Cluster name | item 5 |
| Lake View Sight | Cluster name | item 6 |
| Agora Road | Cluster name | item 7 |
| Pink City | Cluster name | item 8 |
| 109 No Road | Cluster name | item 9 |
| Pink City Road | Cluster name | item 10 |

## Data and figures shown
- Test data: cluster names are Latin-script street/area names (111 No Road, 108 No Road, Road No 108, Road No 108,House 14, Lakepar, Lake View Sight, Agora Road, Pink City, 109 No Road, Pink City Road). The Clusters screen (45) shows the same list with "Route: Agora GoliDaily".
- All form fields are empty; no GEO or photo yet captured, no indication of captured state.
- The list may continue below Pink City Road (not scrolled).

## Colours and style
- Header: red (about #D94A4A) to deep purple (about #4A1A9A) gradient, white title.
- Page: light grey; cards: white, rounded about 12 dp, no visible border.
- Inputs: white, purple (about #4B1F8C) 1.5 dp outline, about 10 dp radius, placeholder grey.
- GEO button: gradient from deep purple (left, about #4A1A9A) to crimson (right, about #B03A5A), white text.
- Save button: gradient from red (left, about #D04848) to purple (right, about #7A2A7E), white text.
- Dialog: lavender, about 28 dp radius, black text; scrim dark grey.

## Navigation and behaviour
- Manual: the SR fills in all fields at the shop, captures the photo and saves; the request goes to the AMO, who verifies it in the AMO app. Outlet requests start from the Outlet tile.
- The cluster field opens a searchable list dialog (right screen).
- "GEO এবং ছবি ধারণ করুন" captures the position and a photo (camera flow not shown here).
- Order of fields: cluster, shop name, owner name, mobile, then GEO+photo button, then Save.

## Differences between the image and the manual text
- The manual lists "ক্লাস্টার নির্বাচন করুন (list with search)". The right screen shows that list, but over the Permanent close page, not New shop.
- The manual's field order matches the image. The manual does not say that the mobile field is required or its format.

## Open questions for the owner
- Are all fields mandatory, and what validation applies to the mobile number (length, 01x prefix)?
- Is the photo mandatory, and how many metres from the outlet must the SR be when capturing GEO?
- Is the cluster list limited to the SR's route clusters?
