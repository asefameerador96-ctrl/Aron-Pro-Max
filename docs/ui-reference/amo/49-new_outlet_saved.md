# 49. New outlet saved
## Screens in the image
Single screen, vendor app (ARON AMO), phone portrait. It is the "নতুন দোকান" (New shop) request screen opened from Outlet > Verify new outlet. A modal success dialog is shown on top and the whole page behind is dimmed by a dark scrim. One line on the status bar: not shown (only the black Android gesture bar at the very bottom).
## Layout, top to bottom
1. Top bar (dimmed by the scrim, so it looks very dark/deep indigo-purple), height about 130 px. Left: a white outline back arrow (triangle-style "◁" icon). Centre: white bold Bangla title "নতুন দোকান".
2. Page background: grey (dimmed light grey).
3. One info card (rounded corners, slightly lighter grey than the page because of the scrim) with 5 label : value rows. Labels on the left in regular Bangla, a colon in the middle column, values on the right in bold Latin text:
   - রুট : Court Bari(Sun, Tue, Thu)
   - ক্লাস্টার : Madrasa Road
   - খুচরা বিক্রেতার নাম (label wraps onto two lines: "খুচরা বিক্রেতার" / "নাম") : Rajib Store
   - মালিকের নাম : Rajib
   - যোগাযোগ : 01772369852
4. Empty grey area below (no Sub-Channel or Geo fields visible at this moment).
5. Centred modal dialog (white, large rounded corners, drop shadow), about 70 percent of screen height position:
   a. Green filled circle with a white check mark, centred at top of dialog.
   b. Message text, centred, blue (medium blue), bold Bangla: "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" (ends with a danda).
   c. Bottom-right: green rounded button "ওকে" with white text and a small shadow (enabled).
6. Bottom: black Android gesture bar.
## Every visible text, verbatim
| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| নতুন দোকান | New shop | Top bar title |
| রুট | Route | Card row 1 label |
| Court Bari(Sun, Tue, Thu) | Route name with visit days (no space before the bracket) | Card row 1 value |
| ক্লাস্টার | Cluster | Card row 2 label |
| Madrasa Road | Cluster name | Card row 2 value |
| খুচরা বিক্রেতার নাম | Retailer (shop) name | Card row 3 label (wraps to two lines) |
| Rajib Store | Shop name | Card row 3 value |
| মালিকের নাম | Owner name | Card row 4 label |
| Rajib | Owner name | Card row 4 value |
| যোগাযোগ | Contact (phone) | Card row 5 label |
| 01772369852 | Mobile number (Latin digits, 11 digits, no separators) | Card row 5 value |
| ডেটা সফলভাবে সংরক্ষিত হয়েছে। | Data saved successfully. | Dialog message |
| ওকে | OK | Dialog button |
| : | Colon separator | Between label and value in every row |
All digits in the image are Latin (0-9); no Bangla digits.
## Data and figures shown
- Example new-outlet request (sent earlier by an SR): route Court Bari with visit days Sunday, Tuesday, Thursday; cluster Madrasa Road; shop Rajib Store; owner Rajib; phone 01772369852.
- No amounts, percentages or counts.
## Colours and style
- Top bar: deep purple/indigo (dimmed to near-black purple by scrim); flat colour, no gradient visible. White title and back arrow.
- Page: light grey (dimmed). Card: rounded corners, light grey-white, no strong shadow.
- Dialog: white, big radius, soft dark shadow. Tick icon green (#4CAF50-like). Message text blue (#1E78C0-like), bold. OK button green with white text.
- Values bold black; labels regular dark grey/black.
- No packshot images.
## Navigation and behaviour
- Per the manual: after the AMO saves the new outlet (the Save button of the form, screenshot 48), this success dialog appears. Tap "ওকে" to dismiss it. The manual does not say where the app goes next (presumably back to the request list).
- The back arrow in the top bar returns to the previous screen.
## Differences between the image and the manual text
- The image shows the underlying request card (route, cluster, shop name, owner, contact) behind the dialog; the manual mentions only the dialog.
- Dialog text ends with a danda "।", the manual quotes it without.
- Title is "নতুন দোকান"; the manual names the section "New outlet" / "New shop" (Annex A: নতুন দোকান).
## Open questions for the owner
- Where does the app navigate after tapping ওকে (back to the new-outlet request list, or the Outlet menu)?
- Are Sub-Channel and Geo Classification shown on this card after save (not visible here)?
