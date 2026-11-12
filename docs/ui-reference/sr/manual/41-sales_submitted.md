# 41. Sales submitted

## Screens in the image
Single phone screen: the বিক্রয় জমা (Sales Submit) page with a modal success dialog on top. The page behind is dimmed by a grey scrim. No status bar clock is visible (the image starts at the header); a dark system bar with a white pill is at the bottom.

## Layout, top to bottom
1. Header, gradient red (left, about #A93A3A when dimmed) to deep purple (right, about #2A1060 when dimmed). Left: white back chevron "<". Centre: a shield/target logo icon in red-blue gradient and the white title "বিক্রয় জমা".
2. Page background light grey, dimmed by the scrim.
3. Right-aligned line: "ডিভাইস স্ট্যাটাস" in dark text, then a green dot and green text "অনলাইন".
4. Comparison table. Header row: deep purple (about #3A1478) rounded bar with white text, three columns: বিষয় (left), ডিভাইস, সার্ভার. Body rows on pale grey with thin dividers: আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন, each with a Device value and a Server value.
5. Below the table, a card with a red left/top border and pale pink fill. Its text is mostly hidden by the dialog; only the first letters "আ" and "সি" of two lines and a trailing mark at the right are visible. Unreadable.
6. Below that, a red-to-purple gradient button (mostly hidden by the dialog; its label is not visible).
7. Below that, a blue gradient button with a small archive/download icon and the label "বিক্রয় জমা".
8. Modal dialog, white, large rounded corners, centred: a green circle with a white tick, blue text "বিক্রয় সফল ভাবে জমা হয়েছে", and at bottom right a green rounded button "ওকে" with white text.

## Every visible text, verbatim

| Text exactly as shown | English meaning | Where it appears |
|---|---|---|
| বিক্রয় জমা | Sales Submit (Bikroy Joma) | Header title; also the blue button label |
| ডিভাইস স্ট্যাটাস | Device status | Line above the table |
| অনলাইন | Online | Green text next to green dot |
| বিষয় | Subject / item | Table header, column 1 |
| ডিভাইস | Device | Table header, column 2 |
| সার্ভার | Server | Table header, column 3 |
| আউটলেট | Outlet | Row 1 label |
| ১ | 1 | Row 1, device and server |
| বিক্রয় | Sales | Row 2 label |
| ৬৮ | 68 | Row 2, device and server |
| স্টক | Stock | Row 3 label |
| ১৯,৭০০ | 19,700 | Row 3, device and server |
| কিউসি | QC | Row 4 label |
| ০ | 0 | Row 4, device and server |
| প্রমোশন | Promotion | Row 5 label |
| ০ | 0 | Row 5, device and server |
| বিক্রয় সফল ভাবে জমা হয়েছে | Sales submitted successfully | Dialog message, blue |
| ওকে | OK | Dialog button, green |
| আ / সি (partial) | First letters of a warning/instruction card, hidden | Behind the dialog, unreadable |

## Data and figures shown
- Device and Server columns are equal on every row (1, 68, 19,700, 0, 0): the reconcile check "Device = Server" is satisfied.
- Numbers use Bangla digits with the Bangla/Indian thousands comma (১৯,৭০০).
- Device status is Online (green).
- The meaning of 19,700 under Stock is not stated (the unit is not shown).

## Colours and style
- Header: red-to-purple horizontal gradient (dimmed by the scrim here), white text.
- Table header: deep purple (about #3A1478), white text, rounded.
- Table body: pale grey (about #EEEEEE), dark text, thin dividers.
- Online indicator: green (about #2E9E44) dot and text.
- Dialog: white, about 28 dp radius; message text blue (about #1565C0); tick green (about #43A047); OK button green (about #4CAF50) with white text and a light shadow.
- Blue button behind: dark blue gradient (about #0B3D91 to #1B5E9E).
- Scrim: dark grey about 55 percent.

## Navigation and behaviour
- Manual: the dialog says "বিক্রয় সফল ভাবে জমা হয়েছে". The SR taps ওকে, then checks out.
- Manual note: after Bikroy Joma no more sales can be made for the day.
- Preceding step (manual screenshot 40): after the last outlet tap ডাটা সিঙ্ক করুন, check Device = Server, then বিক্রয় জমা; if warned about a due, tap না to collect it first or হ্যাঁ to submit.
- The back chevron is present in the header.

## Differences between the image and the manual text
- The manual does not describe the Device/Server table, the "Device status" line or the QC and Promotion rows; they are visible in the image.
- The manual does not mention the card with the red border (likely the due warning or an instruction); its text is hidden.
- The manual says nothing about the red-to-purple button hidden behind the dialog (probably ডাটা সিঙ্ক করুন, unconfirmed).

## Open questions for the owner
- What is the text of the red-bordered card and the label of the red-purple button hidden behind the dialog (probably "sync data")?
- What unit is the Stock figure 19,700 (sticks, packets or base units), and what do QC and Promotion count? (Promotion is a deferred programme, docs/27.)
