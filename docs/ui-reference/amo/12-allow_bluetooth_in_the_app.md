# 12. Allow Bluetooth in the app

## Screens in the image
- Left: the vendor ARON AMO Dashboard (tile grid and blue target cards) for user amo5001. Vendor app.
- Right: the vendor Stock (স্টক) screen, dimmed, with the Android runtime-permission dialog "Allow ARON AMO to find, connect to and determine the relative position of nearby devices?" on top. Vendor app plus an Android system dialog.
- Status bar: not shown.

## Layout, top to bottom
Left screen (Dashboard):
1. Top bar: solid deep purple. White circular person icon at left; bold white title "amo5001 (amo5001)"; smaller white subtitle "AMO-AgrabadAMO, 2026-04-26".
2. Light grey page; one white rounded card with the tile grid, 3 columns, white tiles with a thin border and gradient icons:
   - Row 1: অ্যাটেনডেন্স, জয়েন্ট কল, কন্ট্রল কল.
   - Row 2: টিম লোকেশন, টিম পারফর্মেন্স, টাস্ক ডেলিগেশন.
   - Row 3: লাইভ ড্যাশবোর্ড, এস আর স্টক, আউটলেট (red round badge at top-right of the tile showing a Bangla zero, appears as "০").
   - Row 4: স্টক, বিক্রয়, মেমো.
   - Row 5: সারসংক্ষেপ, বিক্রয় জমা, আস্থা (round dark red/gold emblem).
   - Row 6: Report (donut-and-bars colourful chart icon; label in English "Report"), সেটিং (gear icon); third cell empty.
3. Dark-blue upward chevron "^" under the grid.
4. Second white card with a 2x2 grid of sky-blue cards (white icon, white label, white bold "0/0"): আজকের টার্গেট, টোটাল কল টার্গেট, কন্ট্রোল কল টার্গেট, জয়েন্ট কল টার্গেট, each "0/0".

Right screen (Stock screen, dimmed by the dialog):
1. Top bar: deep purple, white back arrow at left, centred white title "স্টক".
2. At the top right, overlapping the bar's lower edge, a round pale-red/pink circle holding a red printer icon with a diagonal strike-through (printer NOT connected). This is the printer icon the manual says to tap.
3. Table header row (on grey background, thin line above): "এসকেইউ" (left), "ইস্যু" (centre), "স্টক" (right).
4. Four SKU rows, each a white rounded card with: a packshot (pack picture: a red/black "MAXIM" cigarette pack for MaxR rows and a blue/black "MAXIM" pack for MaxB rows, with a small round purple badge with a white "o"/"০" on the pack's lower-right corner), the SKU name, a purple minus button (rounded square with white "−"), a numeric field showing "০" with an underline, a purple plus button (rounded square with white "+"), and at far right the stock figure "০":
   - MaxR-10S, 0, 0
   - MaxR-20S, 0, 0
   - MaxB-10S, 0, 0
   - MaxB-20S, 0, 0
5. Bottom: dark-grey rounded permission dialog (about the lower third of the screen):
   - Blue icon (document/phone with Bluetooth symbol) at top centre.
   - Text, centred: "Allow ARON AMO to find, connect to and determine the relative position of nearby devices?" (ARON AMO bold).
   - Two stacked full-width text options, white bold, no fill: "Allow" and "Don't allow".
   - A hint of a blue bottom bar of the app peeks out under the dialog edge (partly hidden).
6. Gesture bar.

## Every visible text, verbatim
| Bangla or English text exactly as shown | English meaning | Where it appears |
|---|---|---|
| amo5001 (amo5001) | AMO display name and username | Left, top bar title |
| AMO-AgrabadAMO, 2026-04-26 | AMO area/position (Agrabad AMO) and date (Latin digits, ISO) | Left, top bar subtitle |
| অ্যাটেনডেন্স | Attendance | Left, tile |
| জয়েন্ট কল | Joint Call | Left, tile |
| কন্ট্রল কল | Control Call (spelled as shown) | Left, tile |
| টিম লোকেশন | Team Location | Left, tile |
| টিম পারফর্মেন্স | Team Performance | Left, tile |
| টাস্ক ডেলিগেশন | Task Delegation | Left, tile |
| লাইভ ড্যাশবোর্ড | Live Dashboard | Left, tile |
| এস আর স্টক | SR Stock | Left, tile |
| আউটলেট | Outlet | Left, tile |
| ০ (appears as "o" in the stylised font) | Badge count zero (Bangla digit) | Left, red badge on আউটলেট tile |
| স্টক | Stock | Left tile; right screen title |
| বিক্রয় | Sales | Left, tile |
| মেমো | Memo | Left, tile |
| সারসংক্ষেপ | Summary | Left, tile |
| বিক্রয় জমা | Sales submit (Bikroy Joma) | Left, tile |
| আস্থা | Astha | Left, tile |
| Report | Report (English label) | Left, tile row 6 |
| সেটিং | Settings | Left, tile |
| আজকের টার্গেট | Today's Target | Left, blue card |
| টোটাল কল টার্গেট | Total Call Target | Left, blue card |
| কন্ট্রোল কল টার্গেট | Control Call Target | Left, blue card |
| জয়েন্ট কল টার্গেট | Joint Call Target | Left, blue card |
| 0/0 | completed / target (Latin digits) | Left, each blue card |
| এসকেইউ | SKU | Right, table header |
| ইস্যু | Issue (quantity issued) | Right, table header |
| স্টক | Stock | Right, table header (and title) |
| MaxR-10S | SKU name (Maxim Red, 10 sticks) | Right, row 1 |
| MaxR-20S | SKU name (Maxim Red, 20 sticks) | Right, row 2 |
| MaxB-10S | SKU name (Maxim Blue, 10 sticks) | Right, row 3 |
| MaxB-20S | SKU name (Maxim Blue, 20 sticks) | Right, row 4 |
| ০ (shown "o"; Bangla zero) | Issue quantity 0 | Right, field in each row |
| ০ | Stock quantity 0 | Right, far right of each row |
| MAXIM | Brand printed on the pack picture | Right, packshots |
| Allow ARON AMO to find, connect to and determine the relative position of nearby devices? | Android Bluetooth ("Nearby devices") permission question | Right, dialog |
| Allow | Grant permission | Right, dialog |
| Don't allow | Deny permission | Right, dialog |

## Data and figures shown
- Dashboard targets 0/0 all four (Latin digits). Outlet badge 0 (Bangla digit).
- Stock screen: four SKUs with issue 0 and stock 0, shown in Bangla digit zero "০" (the font renders it like a small "o"). Pack sizes in the names: 10S = 10 sticks, 20S = 20 sticks (Maxim Red / Maxim Blue).
- Date 2026-04-26 Latin digits.

## Colours and style
- Top bars: deep purple (flat, indigo-violet) on both screens, white text.
- Dashboard: light grey page, white rounded card, white tiles with gradient magenta/red-to-purple/blue icons; আস্থা is a dark red and gold round emblem; Report icon is multicolour (yellow/orange/blue donut with bars).
- Target cards: sky blue with white text.
- Badge: red circle with white digit.
- Stock rows: white rounded cards on grey; purple (violet) rounded-square minus and plus buttons; pack pictures (packshots) at the left of each row: red MAXIM pack and blue MAXIM pack with a small purple round badge at the pack's lower right. Printer icon: red with strike-through inside a pale pink circle (disconnected).
- Dialog: dark grey rounded sheet, white text, blue icon.

## Navigation and behaviour
- Per the manual: open the Stock (স্টক) tile on the Dashboard; the app asks for the Nearby devices permission. Tap Allow, otherwise the printer cannot connect. Then tap the printer icon at the top right of the Stock screen to connect (see screenshot 13).
- "Don't allow" denies; the printer then cannot connect.
- Stock rows: minus/plus change the issue quantity; the number at right is stock (per the manual's stock section; not described here).

## Differences between the image and the manual text
- The manual mentions only opening Stock and the permission question. The image also shows the Report tile (English label) on the Dashboard, which is not in screenshot 9, so the tile set varies (Report may be shown to some users or builds).
- Right screen shows the red crossed printer icon (not connected) in the top right, SKU rows with packshots and minus/plus controls, not mentioned in the paragraph.
- Dashboard here is amo5001 for Agrabad; screenshot 9 is amo5756 for Savar Bazar.

## Open questions for the owner
- Is the Report tile role- or release-dependent (absent in screenshot 9, present here)? Confirm when it is shown.
