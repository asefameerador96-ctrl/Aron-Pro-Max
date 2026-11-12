# 45. Clusters

## Screens in the image
Two phone screens side by side. Left: the ক্লাস্টারসমূহ (Clusters) list. Right: the same list dimmed with the "ক্লাস্টার তৈরি করুন" (Create cluster) dialog open.

## Layout, top to bottom
Left screen (status bar 3:13, battery 67; right screen 3:14):
1. Header gradient red-to-purple, white back chevron, centred white title "ক্লাস্টারসমূহ" (no icon).
2. Page light grey. A scrolling list of white rounded cards (about #F8F9FC). Each card: a pale pink rounded square on the left with a purple building icon; to the right, bold black cluster name and below it "Route: Agora GoliDaily" in dark grey.
3. Floating action button, red (about #D94545), rounded square, white "+", at bottom right overlapping the Pink City card, with shadow.
4. The list continues below ("109 No Road" is cut off at the bottom edge by the navigation bar).

Right screen: everything dimmed. Dialog: lavender (about #EBE5F0), about 28 dp radius. Bold title "ক্লাস্টার তৈরি করুন". Text box with purple outline and placeholder "Enter cluster name" (English, serif-looking font). Two outlined pill buttons: "বাতিল" in red text and "সাবমিট" in green text.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| ক্লাস্টারসমূহ | Clusters | Header |
| 111 No Road | Cluster | List item 1 |
| 108 No Road | Cluster | List item 2 |
| Road No 108 | Cluster | List item 3 |
| Road No 108,House 14 | Cluster | List item 4 |
| Lakepar | Cluster | List item 5 |
| Lake View Sight | Cluster | List item 6 |
| Agora Road | Cluster | List item 7 |
| Pink City | Cluster | List item 8 |
| 109 No Road | Cluster (cut off) | List item 9 |
| Route: Agora GoliDaily | Route the cluster belongs to ("Route:" in English) | Under every cluster name |
| + | Add cluster | Floating button |
| ক্লাস্টার তৈরি করুন | Create cluster | Dialog title |
| Enter cluster name | Placeholder (English) | Dialog text box |
| বাতিল | Cancel | Dialog button, red text |
| সাবমিট | Submit | Dialog button, green text |
| 3:13 / 3:14 / 67 | Clock / battery | Status bar |

## Data and figures shown
- Every visible cluster has the same route, "Agora GoliDaily".
- Nine clusters are visible or partly visible; more may follow.
- The cluster names match the picker list in screenshot 42 (which also shows Pink City Road after Pink City... and 109 No Road before it).

## Colours and style
- Header gradient red (about #D94A4A) to purple (about #4A1A9A), white title.
- Cards: near-white, about 16 dp radius, subtle shadow; icon tile pale pink (about #F5E6E8) with deep purple icon (about #3B1A7A).
- FAB: red #D94545, white plus.
- Dialog buttons: pill outline, grey border; Cancel text red (about #D32F2F), Submit text green (about #2E9E44).

## Navigation and behaviour
- Manual: the Clusters list shows each cluster with its route; the + button opens "ক্লাস্টার তৈরি করুন" with a name box and বাতিল / সাবমিট. The SR taps + to create a cluster for the route.
- The dialog has only a name box: no route selection is shown (the route is presumably the SR's own).
- Cards show no tap affordance; what tapping a card does is not shown.

## Differences between the image and the manual text
- The manual says the dialog has "a name box, বাতিল / সাবমিট", which matches. The manual does not mention the "Route:" English prefix or the placeholder language.

## Open questions for the owner
- Which route is the new cluster attached to (the SR's current route, or chosen)? The dialog does not show it.
- Is a created cluster available at once or after AMO approval?
- Can a cluster be edited or deleted from this list?
