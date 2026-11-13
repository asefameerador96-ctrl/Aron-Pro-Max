# Live device walk over ADB: report (2026-11-13)

Written by the owner's laptop session while driving the phone over ADB, following `live-walk-plan-2026-11-13.md`.
This is a **partial** report: section 0 and the SR app's home and first outlet flow are done; everything else is listed under
"Not tested yet". Stated plainly: **one phone, one Android version, test data, test account** (dev, seeded pilot user `sr1001`).
No password, OTP or token appears in this report or in the evidence kept with it.

## 0. Setup

| Item | Value |
|---|---|
| Phone | HONOR NLA-LX2P (Honor X5c Plus), Android 15 (API 35), MagicOS 9.0.0.312, security patch 2026-09-01, arm64-v8a |
| Memory and storage | RAM 3.9 GB total (about 2.2 GB free at start); 93 GB free of 102 GB. **Field phones have 2 GB**, so memory and battery budgets measured here are optimistic |
| Power | battery 100%, charging over USB, 33 C |
| Phone state | one Android user; Aron SR is the device owner (Affiliated); no SIM in service ("SOS only"); Wi-Fi |
| APKs of record | GitHub Actions run 43352079662 (INT commit `4e064555`), artifact `aron-release-signed-dev-2116`; SHA-256 of all APKs matched the artifact's `SHA256SUMS`; signer is the usual release certificate (`468d9b4e...`) |
| Installed | SR, AMO and TSO, all `0.3.116` (versionCode 2116), installed over ADB with `-r` (upgrade in place, data kept) |
| SR cold start | 467 ms (one sample, right after install) ; warm start 124 ms |
| Evidence | screenshot, UI dump and redacted log slice per step, kept outside the repository; only down-scaled screenshots without personal data are here |

## 2. SR app (login done by the owner; everything after was driven over ADB)

Legend: **opens** = the screen opens with no crash and no ERROR/FATAL line in its log slice. "Reference match" = comparison with the
owner's screenshots in `docs/ui-reference/`: **not done yet** for any row (see "Not tested yet").

| # | Step | Result | Notes | Screenshot |
|---|---|---|---|---|
| 01 | Home after launch | opens | Header "SR - Test SR (Mirpur) (sr1001)", route "Gulshan 2 (Sat, Tue)", date; 13 tiles; Target and Achievement card; KPI cards in packets | `sr-01-home.png` |
| 02 | Attendance | opens | "Check-in done"; Check Out is gated: "will be active after 5 PM" and "Sales Submit first". Location line reads "No location yet" even though phone location mode is on (3) | `sr-02-attendance.png` |
| 03 | Stock | opens | Shows "Total issue" and "No changes" | |
| 04 | Sales | opens | "Select a retailer to continue the sale", "Skip a shop" | |
| 05 | Memo | opens | Lists today's memo 1001 (14:51, 1,069.50 BDT) for one test outlet | |
| 06 | Summary | opens | SKU list in packets, amounts in BDT | |
| 07 | Sales Submit | opens, **see defect D1** | Device status Online; "6 records were rejected by the server."; one dismissed older record | `sr-07-sales-submit.png` |
| 10 | Outlet | opens | Options: Change information, New shop, Permanently closed | |
| 11 | Tutorial | opens | "No tutorial videos yet." | |
| 12 | Task Delegation | opens | "Your AMO has not assigned any task." and "My outlet requests" | |
| 13 | Astha | opens | "Coming soon" (deferred scope, matches docs/27) | |
| 14 | Loyalty Point | opens | "Coming soon" (deferred scope) | |
| 15 | Photo Capture | opens | "Coming soon: this screen comes with the next update" | |
| 16 | Sales Journey | opens | Shows the earlier visit (2:50 PM to 2:51 PM, 58 s) with "New Sale" | |
| 17 | KPI | opens | Issue and stock by category in field units (packets, dozen, boxes), outlets visited, strike rate | |
| 18 | Sale: select retailer | opens | Four seeded outlets (Gulshan 1, Gulshan 2, two in Banani), A-Z filter, search | `sr-18-select-retailer.png` |
| 19 | Sale: pick an outlet far away | **pass** (hard case "outlet far away") | Refused with "Outside the range: 556 m from the outlet, limit 100 m", a direction hint ("the outlet is to the north"), a map with the distance circle, and "Refresh (3 left)". No screenshot kept here: the map shows where the phone is | not kept |

Units: every quantity seen on SR (home cards, Stock, Sales Submit, KPI) uses field units (packets, boxes, dozen), as the owner's rule requires.
Language was English during this pass; Bangla was not checked.

## Defects and observations, in the order a field user would notice them

- **D1. Sales Submit: "6 records were rejected by the server", no reason given.** The device holds 1 outlet, a sale (Cigarette 3 packets,
  Bidi 3 packets, Lighter 2 boxes, Match 2 dozen) and stock, all marked Waiting, with "-" on the server side, while the status is Online.
  Neither the screen nor the app log says why. A rep would be stuck: the check-out is gated on submitted sales. Needs a server-side look at
  the sync log for this device (the cause is not determined here).
- **O1. Attendance says "No location yet"** although check-in is done and location is on. Possibly by design (location is sampled on demand), worth a ruling.
- **O2. Check Out is gated until 5 PM and until Sales Submit** (by design per the screen text); not exercised.

## Not tested yet (and why)

- A full sale, due collection, zero sale and memo edit: a sale needs the phone within 100 m of an outlet. The phone is about 556 m from
  the nearest test outlet, so it needs either mock location (owner's yes and a fake-GPS app, none is installed) or the force-sale path from the manual.
- Printing (section 5): needs the owner's hands to pair the printer.
- Section 4 cases that change phone settings (airplane mode, mock location, date and time, battery or data saver, permission changes): wait for the owner's yes.
- AMO and TSO walks: the apps are installed but not logged in; the owner logs in once, then they can be driven.
- Comparison of each screen with `docs/ui-reference/`, Bangla pass, rotation, font size and dark mode, Sunlight mode.
- Section 6 budgets beyond the cold start above.
