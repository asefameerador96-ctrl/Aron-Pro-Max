# SR app parity audit (screens vs manual and live-app captures)

Date: 2026-10-17. Read-only checker pass. Nothing was built, recorded or called.

## What was compared

- **Reference (what the rep knows today):** the SR manual inventory `docs/evidence/manuals/manual-sr.md` (60 screens, verbatim Bangla), its delta `docs/evidence/manuals/delta-sr.md`, the live-app captures in `docs/ui-reference/sr/` (images viewed: `home-1-tiles.webp`, `home-2-kpis.webp`, `attendance.png`, `stock.webp`, `sale-select.png`, `summary.png`, `sales-submit.png`, `outlet-menu.png`, `tasks.png`; the memo capture is described in `memo.md` but not stored), spec `docs/06-sr-app.md`. The manual PDF is not in the repo, so manual wording is taken from the verbatim transcript.
- **App:** `android/app-sr`, `feature-auth`, `feature-home`, `feature-attendance`, `feature-stock`, `feature-sale`, `feature-memo`, `feature-dayclose`, `feature-outlet`, `feature-tasks`, `core-printing`, `core-ui` (code and `values`/`values-bn/strings.xml`), and the goldens in `android/ui-screenshots/src/test/screenshots` (`login_*`, `home_*`, `attendance_*`, `stock_*`, `picker_*`, `visit_*`, `sale_*`, `review_*`, `memo_*`, `summary_*`, `submit_*`, `tasks_*`, `sr_tutorial_*`).
- **Out of scope by standing rule 2 (deferred, hooks only), so not counted as divergences:** Target and Achievement card and SKU list, Astha, Loyalty Point and Redemption, outlet Points, Photo Capture (Astha gift, Campaign), the outlet eligibility dots. Slide/DRP is a promotion programme too; its divergences are listed but marked minor for that reason.
- **Deliberate changes already logged** (UI-SR-11 coordinates instead of a reverse-geocoded address, UI-SR-15 per-category stock with units, UI-SR-23 case-insensitive letters, UI-SR-35 rewritten submit notice, UI-SR-36 record-count reconciliation): listed where they show, marked "deliberate".

Severity: **blocker** = a rep cannot finish a selling day, or money/units are shown wrongly. **major** = a rep trained on the current app will hesitate, mis-tap or misread on day one. **minor** = wording or look only.
"Presentation-only = yes" means strings, ordering, formats, colours or which data is shown; no change to SR logic, the data model or sync.

> **Goldens are not always production.** Two goldens show text that the production code never shows: `review_bn_light.png` shows an outlet line that `SaleRoute.kt:67` never passes, and `submit_bn_light.png` shows an "আউটলেট" row label where production maps the same row to "ভিজিট" (`SrSaleHosts.kt:159-163`). R1 and SS2 are therefore judged on code, not on the golden.

## Headline

- **0 blockers, 11 majors, 62 minors (73 divergences)**, plus 19 rows that **match**.
- 9 of the 11 majors and 60 of the 62 minors are **presentation-only**. The two majors that need more than presentation work are the six-type QC entry screen (Q2) and the per-SKU Sale History (SH2). The two minors that do are the attendance address (A2, a logged deliberate change) and the sale-card indicators (E4, meaning unknown).
- **The top day-one risk is that production Sale entry and Review never show which outlet is being sold to** (E1, R1). The fix is presentation only: the outlet is known in `SaleHost`.

## Per-screen table

### Login, device OTP, update (SR-S-02, 04, 06-09)

| # | Screen | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|---|
| L1 | Login | button "লগইন" | "লগইন করুন" | minor | yes | `feature-auth/src/main/res/values-bn/strings.xml:9` |
| L2 | Login | footer "SR App (version - 1.0.25)" | "সংস্করণ ১.০.২৫ (১২)", "© AKTCL" (dropping the vendor credit is intended) | minor | yes | `feature-auth/.../values-bn/strings.xml:10`; `core-ui/.../values-bn/strings.xml:3` |
| L3 | OTP | title "OTP যাচাইকরণ", heading "Enter OTP", hint "Enter the 4-digit OTP provided by your TSO.", green "Verify" | title "এই ফোন যুক্ত করুন", hint "...৪ সংখ্যার কোড...", "যাচাই করুন" (navy). The word **OTP** is never shown, but the TSO portal menu is "SR Device OTP" and the TSO will say "OTP" to every rep at cutover | **major** | yes | `feature-auth/.../values-bn/strings.xml:35-37` |
| L4 | Update | "Update Available", "Download App & Install", "Please do not close the app while downloading" (English) | Bangla equivalents ("ARON-এর নতুন সংস্করণ প্রস্তুত", "ডাউনলোড", "ইনস্টল") | minor | yes | `core-system/src/main/res/values-bn/strings.xml:32-41` |

### Home (SR-S-10; `home-1-tiles.webp`, `home-2-kpis.webp`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| H1 | Header line 2 "Apsis RouteDaily, 2026-10-05" (one line, Latin ISO digits) | two lines: "Apsis RouteDaily" and "২০২৬-১০-০৫ · Daily". The kind is repeated after a name that already ends in "Daily", and "Daily" is English in the Bangla UI (`translatable="false"`, while `hm_route_daily` = "প্রতিদিন" goes unused on this path) | minor | yes | `feature-home/.../HomeScreen.kt:198-201`; `app-sr/src/main/res/values/strings.xml:52-54`; `app-sr/.../SrApp.kt:504-509` |
| H2 | One 4-column grid in fixed order: Attendance, Stock, Sale, Memo / Summary, Sales Submit, Outlet, Tutorial / Task, Astha, Loyalty, Photo Capture / Sales Journey, KPI | 3-column grid split into "আজকের কাজ" (6 tiles) and "অন্যান্য". The order is kept; the positions move | minor | yes | `feature-home/.../HomeScreen.kt:279-291` |
| H3 | Tile label "Photo Capture" (English on the live app) | "ফটো ক্যাপচার" (the tile is hidden anyway: deferred) | minor | yes | `feature-home/.../values-bn/strings.xml:24` |
| H4 | Sales card: headline "বিক্রয় (স্টিক)" with stick count; ম্যাচ / লাইটার / মোট / ডিসকাউন্ট / QC / সর্বমোট. Second card: মোট ডিসকাউন্ট, DRP ডিসকাউন্ট, মোট QC, সর্ব মোট, মোট টাকা | one card "আজকের বিক্রয়" with categories, মোট, ডিসকাউন্ট and QC (each only when non-zero), সর্বমোট. No stick headline. The DRP / total-taka card exists only on the KPI screen | minor | yes | `feature-home/.../HomeScreen.kt:384-404`; `values-bn/strings.xml:87-91` |
| H5 | Unit word: live Home "বিক্রয় (স্টিক)" | the Home stock card and KPI screen say **"শলাকা"**, while Stock, Sale, Review, Memo and Summary say **"স্টিক"** for the same unit | minor | yes | `app-sr/src/main/res/values-bn/strings.xml:43` (used at `SrApp.kt:289`, `SrSaleHosts.kt:186`) |
| H6 | KPI labels আউটলেট ভ্রমণ, স্ট্রাইক রেট, ইস্যু, বর্তমান স্টক, নন ভিজিট, বিক্রি নেই; strike "১০%" | same labels. Issue and stock are shown per category with units (deliberate, UI-SR-15). Home strike rate "১০%" | matches | – | `feature-home/.../values-bn/strings.xml:80-87`; `SrHomeState.kt:53-56` |
| H7 | (KPI tile screen) strike rate as on Home "১০%" | KPI screen shows "১০.০০%" (two decimals), so it disagrees with Home | minor | yes | `feature-memo/.../ui/HomeCards.kt:41-42,64` |

### Attendance (SR-S-12..14; `attendance.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| A1 | User card "SR - Testing Banani (sr334001)" + "Apsis RouteDaily, 2026-10-05" under the title | no user/route/date card | minor | yes | `feature-attendance/.../AttendanceScreen.kt` (content root ~140-190) | Already matches (user card via `userTitle`, `SrApp.kt`).
| A2 | Reverse-geocoded address + red refresh | coordinates "২৩.৭৯২৫৮০, ৯০.৪০৭৮৩০" + fix-quality chip "ভালো · ±১৮ মি" (deliberate, UI-SR-11, offline) | minor | no (deliberate) | `AttendanceScreen.kt:225-245` |
| A3 | Two large side-by-side buttons: **green "চেক ইন"**, grey (later **red**) "চেক আউট". Hold sheet: green half-circle to check in, red to check out | radio-style rows "চেক ইন" / "চেক আউট" plus one **navy** dock button. The hold button uses the accent colour for both. The green-in / red-out colour cue the rep uses daily is gone | **major** | yes | `AttendanceScreen.kt:160-170, 274-300`; `core-ui/.../KitStates.kt:184-185` |
| A4 | Status message centred, above the buttons | message at the bottom, above the dock | minor | yes | `AttendanceScreen.kt:149-150` | Already matches (prompt centred above the buttons).
| A5 | "চেক আউট ৫টার পরে সক্রিয় হবে" / note "বিকাল ৫ ঘটিকা থেকে" | "চেক আউট ১৭:০০ থেকে চালু হবে।" (24-hour, different verb) | minor | yes | `feature-attendance/.../values-bn/strings.xml:22`; `app-sr/.../SrApp.kt:363` |
| A6 | Hold-sheet time chip "04:47 PM" (12-hour) | "১৬:৪৭" (24-hour) | minor | yes | `AttendanceScreen.kt:99-102, 194` |
| A7 | "আপনি এখনো চেক ইন করেননি…", "…চেক আউট করেননি…", day-complete text, "চেক ইন করা হচ্ছে", "চাপ দিয়ে ধরে রাখুন" | verbatim | matches | – | `feature-attendance/.../values-bn/strings.xml:16-27` |

### Printer and Stock (SR-S-16, 17; `stock.webp`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| S1 | "প্রিন্টার কানেক্ট করা হয়েছে"; crossed red printer when disconnected | verbatim; printer icon in the frame | matches | – | `core-printing/.../values-bn/strings.xml:48` |
| S2 | Column header row "এসকেইউ / ইস্যু / স্টক" | "এসকেইউ … স্টক" or "মোট লোড" (before any stock exists); "ইস্যু" sits beside each stepper, not as a column | minor | yes | `feature-stock/.../StockScreen.kt:191-192`; `values-bn/strings.xml:6` |
| S3 | Purple totals panel "ক্যাটাগরি / মোট ইস্যু / স্টক" with all four rows (সিগারেট, বিড়ি, লাইটার, ম্যাচ, zeros included). The rep checks it before Save; it backs the distributor hand-over | a scrolling chip row "সিগারেট ৬০০ স্টিক · লাইটার ৪০০ পিস · ম্যাচ…" showing issue only, no stock column; at 360 dp the third chip is cut off. `stk_totals` exists only as a test tag | **major** | yes | `StockScreen.kt:371`; `values-bn/strings.xml:11` |
| S4 | Red "সংরক্ষণ", grey "প্রিন্ট" | navy primary save, light print (labels match) | minor | yes | `StockScreen.kt:412-417` | Left: owner red=No rule, same as M8 (lead 2026-10-24).
| S5 | Units unlabelled; issue/stock summed across units | stick / piece / dozen beside every number; pack badge for sticks (deliberate) | matches | – | `StockScreen.kt:88-93` |

### Sale: choose retailer (SR-S-18; `sale-select.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| P1 | Title "বিক্রয়"; field "রিটেইলার নির্বাচন করুন"; empty state "বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন" | title and heading verbatim. The empty-state string exists, but the list is always open so it is not shown | minor | yes | `feature-outlet/.../values-bn/strings.xml:3-5` |
| P2 | Dropdown with a dark alphabet bar at the top | always-open list, segments, letter chips at the **bottom** (letters case-insensitive: deliberate UI-SR-23) | minor | yes | `app-sr/.../SrApp.kt:395`; `feature-outlet/.../OutletPicker.kt` |
| P3 | Row label on one line: "name (code-phone-cluster)" | two lines: name, then "code · phone · cluster" | minor | yes | `feature-outlet/.../OutletCard.kt:23`; `OutletPicker.kt:25-29` |
| P4 | "বাকি" means **due / credit** everywhere (Memo, Review, Submit) | the picker uses "বাকি ৪" for **outlets not yet visited** and "বাকি ১,২৫০.০০ ৳" for a **due**, on the same screen | **major** | yes | `feature-outlet/.../values-bn/strings.xml:83` vs `:87` |

### Sale: outlet selected, geo, force sale, start call (SR-S-19, 22, 24, 25)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| V1 | "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।", "ফোর্স সেল", "রিফ্রেশ", reasons "আপনার ফোর্স সেলের কারণ কী?", "ইন্টারনেট সমস্যা", "লোকেশন চেঞ্জ" | verbatim | matches | – | `feature-outlet/.../values-bn/strings.xml:10,21,23,29-31` |
| V2 | Buttons "Sale History" (blue) and "Points" | text link "পূর্বের বিক্রয় দেখুন" (Points deferred) | minor | yes | `values-bn/strings.xml:109` |
| V3 | Reason buttons, then the camera opens by itself | one screen with reason + photo block + confirm "বিক্রয় চালিয়ে যান" | minor | yes | `ForceSaleScreen.kt:97-112`; `values-bn/strings.xml:37` |
| V4 | One dialog "আপনি কি কল শুরু করতে চান?" with হ্যাঁ / না | a bar button "কল শুরু করুন", **then** a dialog "কল শুরু করবেন?" / "আপনি %1$s এ আছেন। এখনই কল শুরু করবেন?" (green/red kept). One extra tap and different words | minor | yes | `feature-outlet/.../values-bn/strings.xml:106`; `feature-sale/.../values-bn/strings.xml:82-83`; `SrSaleHosts.kt:58` |
| V5 | Footer at call start: cart total, yellow "Collect DRP Discount", "এগিয়ে যান→" | no slide entry there; Slide is a row on Review (offers deferred) | minor | yes | `feature-sale/.../SaleRoute.kt:72` |

### Sale entry (SR-S-32)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| E1 | Outlet shown at the top: "Savar Metro (1618409)" with a thumbnail | **production shows no outlet**: the frame title is only "বিক্রয়" with no subtitle, and `SaleEntryScreen` has no outlet parameter. The golden's "Banani Store" subtitle comes from the test frame only | **major** | yes | `app-sr/.../SrApp.kt:408`; `feature-sale/.../SaleRoute.kt:47-52` |
| E2 | Green full-width "পূর্বের সেল ডাটা দেখুন" | text button "পূর্বের বিক্রয় দেখুন" | minor | yes | `feature-sale/.../values-bn/strings.xml:6`; `SaleScreens.kt:324` |
| E3 | Pack badge only on stick SKUs (packets of 10/20) | the badge line "= ২৬ প্যাকেট + ০" also prints for **lighters (pieces)** and any SKU with pack factor 1, so a rep reads 26 lighters as "26 packets" (golden `sale_bn_light.png`, Aster) | **major** | yes | `SaleScreens.kt:472-474`; `values-bn/strings.xml:29` |
| E4 | Three unlabelled indicators per card (cart, %, box) | price / unit · stock caption. The meaning of the live indicators is unknown (G-man-sr-05), so this cannot be compared | minor | no | `SaleScreens.kt:450-460` |
| E5 | Letter strip filters SKUs | category chips (সব / সিগারেট / লাইটার / ম্যাচ) and search | minor | yes | `SaleScreens.kt:340-360` |
| E6 | Zero sale: "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি" / "আপনি কি জিরো (০) বিক্রয় করতে চান?" green হ্যাঁ, red না | verbatim, green/red | matches | – | `values-bn/strings.xml:32-36`; `SaleScreens.kt:371` |
| E7 | Footer total + "এগিয়ে যান→" | "মোট" + total + "এগিয়ে যান" | matches | – | `SaleScreens.kt:364-367` |

### Review "নিরীক্ষণ", credit, commit (SR-S-33, 34, 37)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| R1 | Header: outlet "Savar Metro (1618409)", "রুট: …", "ক্লাস্টার: …" | **production passes no `outletLine`**, so Review shows no outlet, route or cluster before the commit-and-print tap | **major** | yes | `feature-sale/.../SaleRoute.kt:67-73`; `SaleScreens.kt:506,518` |
| R2 | Labelled lines "রুট:" and "ক্লাস্টার:" | (when passed) one line "name (code) · route · cluster" with no labels | minor | yes | `SaleScreens.kt:518` |
| R3 | "এসকেইউ / পরিমাণ / মূল্য", subtotals "মোট সিগারেট" … | verbatim, plus "(৳)" and units | matches | – | `values-bn/strings.xml:38-43` |
| R4 | Slide shown as its own table (SKU / পরিমাণ / দাম) with the offer text | one line "স্লাইড − ৮০.০০ ৳" (offers deferred) | minor | yes | `SaleScreens.kt:524` |
| R5 | Credit **checkbox** "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন"; after part-payment "Baki ৬১ টাকা" | same label on a **switch**; after part-payment "বাকি ৫৭.৫০ ৳" (exact paisa, better) | minor | yes | `SaleScreens.kt:546`; `values-bn/strings.xml:51-52` | Already matches (purple checkbox; "বাকি N" after part payment).
| R6 | Part-payment dialog "পরিশোধিত টাকার পরিমাণ লিখুন" / "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?" / "আদায়কৃত অর্থ" / "বাকিঃ", **green হ্যাঁ, red না** | texts verbatim, but a full screen with "নিশ্চিত করুন" / "পরে" bar buttons (not green / red) | minor | yes | `SaleExtraScreens.kt:83-88`; `values-bn/strings.xml:60-66` |
| R7 | Yellow "প্রোডাক্ট QC" button | list row "প্রোডাক্ট QC ›" | minor | yes | `SaleScreens.kt:550-556` | Already a yellow full-width button (`SaleScreens.kt` YellowButton); nothing left to do.
| R8 | "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।"; commit dialogs "আপনি কি নিশ্চিত?" / "বিক্রয় জমা হবে", "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" with green হ্যাঁ, red না | verbatim, green/red | matches | – | `values-bn/strings.xml:53`; `core-printing/.../values-bn/strings.xml:63-65`; `PrinterUi.kt:232-240` |
| R9 | Success: tick, title **"সফল"**, "বিক্রয় সফল ভাবে জমা হয়েছে", green **"ওকে"** | no title; plain text button **"ঠিক আছে"** | minor | yes | `core-printing/.../PrinterUi.kt:207, 243-248`; `values-bn/strings.xml:61` |

### Product QC (SR-S-35, 36)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| Q1 | "QC এ প্রবেশ করতে…"; empty "QC করার জন্য কোন পণ্য নেই" | verbatim apart from a full stop and "কোনো" | matches | – | `feature-sale/.../values-bn/strings.xml:72-73` |
| Q2 | Per-SKU "QC এন্ট্রি" screen: "সর্বোচ্চ QC / QC হয়ে গেছে / বাকি আছে" in ৳; six fault rows in two groups (উৎপাদন ত্রুটি: ড্যামেজড ও ক্রাশড…, আউটার/প্যাক/স্টিক কম থাকা, অন্যান্য ত্রুটি; পরিবহন ত্রুটি: মেয়াদোত্তীর্ণ স্টক (৪ মাস+), স্টক ড্যামেজড – রুট সার্ভিস কালীন, স্বাদ সংক্রান্ত সমস্যা); সংরক্ষণ / বাতিল / ডিলিট | two steppers per SKU (উৎপাদন ত্রুটি, পরিবহন ত্রুটি); no six types, no max/done/remaining | **major** | no (fault-type model, G-man-sr-03) | `SaleExtraScreens.kt:147-170` |
| Q3 | Summary column "QC টাইপ"; "মোট QC নিষ্পত্তি ১০"; "সেটেলমেন্ট পরিমান ৮০.০০৳" | "QC ধরন"; no total-quantity line; "কর্তন: − ৮০.০০ ৳" | minor | yes | `values-bn/strings.xml:75,78`; `SaleExtraScreens.kt:180,194` |
| Q4 | "QC জমা দিন →" opens a confirm: "ইনফর্মেশন" / "আপনি কি QC জমা দিতে চান?" with green হ্যাঁ, red না | completes with no confirm | minor | yes | `SaleExtraScreens.kt:198` |
| Q5 | Horizontal SKU carousel to pick from | every memo SKU listed as a card | minor | yes | `SaleExtraScreens.kt:153-173` |

### Slide / DRP (SR-S-26..28), deferred programme

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| D1 | Title "স্লাইড"; outlet, "রুট:", "ক্লাস্টার:"; columns "এসকেইউ / খালি প্যাকেট"; red "OFFER" ribbon; "জমা দিন" | title "স্লাইড (খালি প্যাকেট)"; card per SKU with stepper "প্যাকেট" and a reward line; no header, ribbon or submit | minor | yes | `SaleExtraScreens.kt:94-128`; `values-bn/strings.xml:68-70` |

### Memo menu and detail (SR-S-39..42; `memo.md`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| M1 | Info banner "আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। …পুনরায় প্রিন্ট করতে পারেন।" | the same sentence ("পারবেন") | matches | – | `feature-memo/.../values-bn/strings.xml:4` |
| M2 | Outlet dropdown + alphabet strip; selected row "Tasmin (1618415) : ২৯১.৫০ ৳ বাকি" (the **due amount**, in red) | list "আজকের মেমো": outlet, "মেমো ০০১ · ১০:০০", the **memo total**, and an amber "বাকি" chip with no amount | minor | yes | `MemoScreens.kt:125-139` |
| M3 | Items table with **category subtotals** "মোট সিগারেট / মোট বিড়ি / মোট লাইটার / মোট ম্যাচ" and a quantity "মোট" row (manual p44; `memo.md` "মোট ম্যাচ 3 84.00") | item rows only, then a money "মোট". The print template also has a single "মোট" line and no category subtotals | **major** | yes | `MemoScreens.kt:221-236`; `core-printing/src/main/resources/aron/print/memo.json` (`line_total`) |
| M4 | "মোট ডিসকাউন্ট − ০.০০" and "মোট QC − ০.০০" always shown | on screen each is hidden when zero (the print always shows them) | minor | yes | `MemoScreens.kt:242-243` |
| M5 | Codes in Latin ("sr334001") | memo number shown as "মেমো sr৩৩৪০০১-২৬১০০৫-০০১": Bengali digits forced inside a Latin code | minor | yes | `MemoScreens.kt:213` (`localizedDigits(memo.memoNo)`) |
| M6 | Credit box "এই বিক্রয়টি বাকিতে করা হয়েছে।" + **green** "পরিশোধিত করুন" | same text; button is the neutral secondary | minor | yes | `MemoScreens.kt:283-286` |
| M7 | Mark-paid confirm "আপনি কি নিশ্চিত?" (no body); success dialog "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" + "ওকে" | title verbatim plus a body "এই মেমোর পুরো বাকি পরিশোধ করবেন?"; success text verbatim as a banner | matches | – | `MemoScreens.kt:205, 256-258`; `values-bn/strings.xml:23-25` |
| M8 | Red "প্রিন্ট", **amber** "এডিট" | navy primary print, light secondary edit | minor | yes | `MemoScreens.kt:197-199` |
| M9 | Edit-reason dialog "দয়া করে বিক্রয় এডিট করার কারন লিখুন", a dropdown (the known option is "ভুল SKU নির্বাচিত।"), "বাতিল" / "জমা দিন" | screen titled "কেন এই বিক্রয় এডিট করছেন?"; tap-to-select rows "ভুল এসকেইউ" / "ভুল পরিমাণ" / "ভুল মূল্যের ধরন"; no cancel / submit pair | minor | yes | `feature-sale/.../CallPrompts.kt:31-58`; `values-bn/strings.xml:84-87` |

### Sale history (SR-S-20)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| SH1 | Title "সেল ডাটা"; entry "Sale History" / "পূর্বের সেল ডাটা দেখুন" | "বিক্রয়ের ইতিহাস"; entry "পূর্বের বিক্রয় দেখুন" | minor | yes | `feature-memo/.../values-bn/strings.xml:72`; `feature-sale/.../values-bn/strings.xml:6` |
| SH2 | Pick one date ("November 26, 2025"; heading "2025-11-26"), then a **per-SKU** table "এসকেইউ / পরিমাণ / মূল্য" + "মোট" | every date as a card of **memo rows** (memo no · time, total); no SKU or quantity | **major** | no (model has no lines: `SaleHistory.kt:3-4`) | `HistoryScreen.kt:36-66` |

### Sales summary (SR-S-60 stub; `summary.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| SU1 | Title "বিক্রয় সারসংক্ষেপ" | "সারসংক্ষেপ" (tile label; `summary_title` is defined but unused) | minor | yes | `app-sr/.../SrApp.kt:433` |
| SU2 | Banner "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে" + crossed printer | verbatim; printer in the frame | matches | – | `feature-memo/.../values-bn/strings.xml:32` |
| SU3 | One 6-column table: মেমো / পরিমাণ / মূল্য / ডিসকাউন্ট / ডিসকাউন্ট মূল্য / ফেরত, SKU name above each row, category rows at the bottom | a card per SKU (value beside the name; মেমো, পরিমাণ, ফেরত; then ডিসকাউন্ট, ছাড়ের পর), grouped under category headings with a "<category> মোট" block | minor | yes | `MemoScreens.kt:457-491` |
| SU4 | Column "ডিসকাউন্ট মূল্য" | "ছাড়ের পর" | minor | yes | `feature-memo/.../values-bn/strings.xml:41` |
| SU5 | "ডিসকাউন্ট এবং অন্যান্য (-)", "সর্বমোট"; mixed "437.5" / "0.0" | verbatim; always two decimals (fixes UI-SR-33) | matches | – | `values-bn/strings.xml:46-47` |

### Sales submit "বিক্রয় জমা" (SR-S-55; `sales-submit.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| SS1 | Title, "ডিভাইস স্ট্যাটাস" + "অনলাইন", columns "বিষয় / ডিভাইস / সার্ভার", "ডাটা সিঙ্ক করুন", "বিক্রয় জমা" | verbatim | matches | – | `feature-dayclose/.../values-bn/strings.xml:3-10,18-19` |
| SS2 | Five rows: আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন | record-type rows labelled **ভিজিট**, **মেমো**, স্টক, **পণ্য কিউসি**, **ছাড়**, plus ভিজিট শেষ, বাকি আদায়, হাজিরা, বাদ দেওয়া দোকান. Counting records is deliberate (UI-SR-36); the five familiar words are not used | minor | yes | `app-sr/src/main/res/values-bn/strings.xml:28-36`; `SrSaleHosts.kt:159-163` |
| SS3 | Red notice "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন…" | "আজকের সব তথ্য সিঙ্ক হলে বিক্রয় জমা দেওয়া যাবে। সিঙ্ক নিজে থেকেই চলছে।" (deliberate, UI-SR-35) | minor | yes | `feature-dayclose/.../values-bn/strings.xml:17` | Already matches (`submit_info` carries the manual sentence in a warning notice).
| SS4 | Dues: a **dialog** "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" with green হ্যাঁ, red না; then a tap | an inline banner "%1$s জন খুচরা বিক্রেতার কাছে %2$s বাকি আছে। তবুও জমা দিতে পারবেন।", then a **press-and-hold** sheet "চাপ দিয়ে ধরে রাখুন" | **major** | yes | `values-bn/strings.xml:24-27,34` |
| SS5 | Red-gradient sync button; grey (later blue) submit | light secondary sync; navy primary submit | minor | yes | `SalesSubmitScreen.kt` (bar) | Left: owner red=No rule, same as M8 (lead 2026-10-24).
| SS6 | Success "বিক্রয় সফল ভাবে জমা হয়েছে" | "বিক্রয় সফলভাবে জমা হয়েছে।" | matches | – | `values-bn/strings.xml:36` |

### Outlet menu and forms (SR-S-46..49; `outlet-menu.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| O1 | Tiles নতুন দোকান, স্থায়ী বন্ধ, তথ্য পরিবর্তন করুন, ক্লাস্টার (live) | verbatim, in order; adds "এই আউটলেট আমার রুটে যোগ করুন" and "আমার অনুরোধ" | matches | – | `feature-outlet/.../values-bn/strings.xml:41-45`; `OutletMenu.kt:41-53` |
| O2 | "ক্লাস্টার নির্বাচন করুন", "দোকানের মালিকের নাম", "মোবাইল নম্বর" | "ক্লাস্টার", "মালিকের নাম", "মোবাইল (১১ সংখ্যা)" | minor | yes | `values-bn/strings.xml:47-49` |
| O3 | Button "GEO এবং ছবি ধারণ করুন" | "দোকানের ছবি তুলুন" | minor | yes | `values-bn/strings.xml:32` |
| O4 | Save "সংরক্ষণ" | "জমা দিন" | minor | yes | `values-bn/strings.xml:53` |
| O5 | Confirms "আপনি কি নিশ্চিত?" with kind-specific bodies: "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" (close), "এই পরিবর্তন সংরক্ষণ করা হবে" (info) | title "নিশ্চিত করুন"; one body "এই পরিবর্তন সংরক্ষণ করা হবে?" for every kind; close warning only as a banner "এই দোকান স্থায়ীভাবে বন্ধ করা হবে।" | minor | yes | `values-bn/strings.xml:51,55-56`; `OutletRequestScreen.kt:148-151` |
| O6 | Photo alert "ছবি ধারণ করা সম্পন্ন হয়েছে" + ওকে; success "সফল" + ওকে | inline "ছবি যুক্ত হয়েছে"; success banner "সংরক্ষিত হয়েছে। অনুরোধটি যাচাইয়ের অপেক্ষায় আছে।" (adds the pending state) | minor | yes | `values-bn/strings.xml:36,54` |

### Task delegation (SR-S-57; `tasks.png`)

| # | Manual / live app says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|
| T1 | Screen title "টাস্ক" | "টাস্ক ডেলিগেশন" (the tile label) | minor | yes | `app-sr/.../SrApp.kt:465`; `feature-tasks/.../values-bn/strings.xml:3` |
| T2 | Red type tag "OOS" on each card | not shown; `taskTypeCode` is in the model but not rendered (the title takes its place) | minor | yes | `feature-tasks/.../TaskScreen.kt:173-176`; `TaskBoard.kt:13` |
| T3 | Swipe left→right reveals red "Resolve" | swipe, or a visible text button "সমাধান করুন" | minor | yes | `values-bn/strings.xml:15`; `TaskScreen.kt:183` | Left: visible Resolve button covers the swipe-revealed action; red per owner red=No rule, same as M8 (lead 2026-10-24).
| T4 | "Completion on: 2025-11-30" (ISO with the year) | "শেষ তারিখ ৩০ নভেম্বর" (Bangla month name, **no year**) | minor | yes | `TaskScreen.kt:215-221`; `values-bn/strings.xml:13,17-29` |
| T5 | Statuses "চলমান" / "সম্পন্ন"; empty "আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।" | verbatim | matches | – | `values-bn/strings.xml:4,11-12` |

### Tutorial, call content, settings, logout (SR-S-29..31, 56, 58, 59)

| # | Screen | Manual says | App does | Sev | Pres-only | File:line |
|---|---|---|---|---|---|---|
| X1 | Tutorial | empty state in a red box "কোনো টিউটোরিয়াল পাওয়া যায়নি।" | "এখনো কোনো টিউটোরিয়াল ভিডিও নেই।" (neutral empty state) | minor | yes | `feature-home/.../values-bn/strings.xml` `tut_empty` |
| X2 | AV/KV, Survey | "বন্ধ করুন"; "সার্ভে"; "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?" with green হ্যাঁ, red না | verbatim | matches | – | `app-sr/.../values-bn/strings.xml:7-14`; `CallContentHost.kt:191` |
| X3 | Survey | info card "আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে"; button "সাবমিট →" | no such info string found; button "জমা দিন" | minor | yes | `app-sr/.../values-bn/strings.xml:12` |
| X4 | Settings | title "সেটিং"; tile "লগ আউট"; "PDA টু সাপোর্ট" | "সেটিংস"; "লগআউট"; "PDA টু সাপোর্ট" verbatim | minor | yes | `feature-home/.../values-bn/strings.xml` `set_title`, `set_logout`, `set_support` |
| X5 | Logout | "আপনি কি নিশ্চিত যে লগআউট করতে চান?" with green হ্যাঁ, red না | "লগআউট করবেন?" + body; buttons "লগআউট" / "বাতিল" (green/red kept) | minor | yes | `SettingsScreen.kt:151-154`; `values-bn/strings.xml` `set_logout_*` |

### Formats across screens (manual rules R-145..R-149)

| Class | Manual | App | Verdict |
|---|---|---|---|
| Digits | Bengali digits on SR screens; Latin in codes, phones and some English screens | Bengali digits everywhere via `localizedDigits`. Two divergences: the memo number mixes scripts (M5), and the Home header date uses Bengali digits where the live app prints Latin "2026-10-05" (H1) | minor (M5, H1) |
| Money | two decimals, comma grouping, `৳` suffix; the live app is inconsistent ("437.5") | always two decimals, `৳` suffix, South-Asian grouping (`LocaleDigits.groupSouthAsian`) | matches (better) |
| Dates | ISO in headers and tables | ISO with Bengali digits, except Tasks (long-form Bangla month, no year: T4) | minor (T4) |
| Times | 12-hour "04:47 PM" | 24-hour (A5, A6; memo rows "১০:০০") | minor |
| Units | sticks in sale / QC (`শলাকা` in the manual's callouts; `স্টিক` on the live Home) | unit beside every number. Two divergences: two words for sticks (H5), and the pack badge on piece SKUs (E3) | major (E3), minor (H5) |
| Yes / No colours | green হ্যাঁ, red না in every confirm | `AronConfirmDialog` and print `YesNo` are green/red. Exceptions: part-payment (R6), QC submit (Q4, no dialog), dues (SS4, hold sheet), success "ঠিক আছে" not "ওকে" (R9) | minor / major (SS4) |

## Manual screens with no counterpart in the app

| Manual screen | Status |
|---|---|
| SR-S-11 Target and Achievement (SKU list, ADS/TADS/PADS/RADS); Home target card | deferred (rule 2) |
| SR-S-21 outlet Points | deferred |
| SR-S-27 Slide offer detail dialog "ছাড়" (offer text, validity, "বন্ধ করুন") | missing (promotion programme: deferred-adjacent) |
| SR-S-28 Slide manual-quantity dialog with ±১/৫/১০/২০/৫০ shortcuts and "সংরক্ষণ" | missing (deferred-adjacent) |
| SR-S-36 per-SKU "QC এন্ট্রি" with six fault types and max / done / remaining | partial: two groups only (Q2) |
| SR-S-35 QC submit confirm "ইনফর্মেশন / আপনি কি QC জমা দিতে চান?" | missing (Q4) |
| SR-S-43/44/45 Astha route, shop list, outlet detail | deferred |
| SR-S-50/51 Loyalty Point, Gift Redemption | deferred |
| SR-S-52/53/54 Photo Capture (Astha gift, Campaign) | deferred; tile hidden (`HomeTiles.resolve(emptySet(), …)`, `SrApp.kt:333`) |
| SR-S-09 "অ্যাপ আপডেট হচ্ছে / প্রসেসিং(n/৮৭)" post-update screen | no equal screen; the nearest is first-bundle "আপনার দিন প্রস্তুত হচ্ছে" (`feature-home/.../FirstBundleScreen.kt`) |
| Live-app tiles with no manual page (Sales Journey, KPI) | present in the app; nothing to compare against |

## Presentation-only fixes, ranked by value to a rep on day one

1. **Show the outlet on Sale entry and Review** (E1, R1): pass the outlet name as the `SrFrame` subtitle at `SrApp.kt:408`, and pass `outletLine` with "রুট:" / "ক্লাস্টার:" labels at `SaleRoute.kt:67`. Without it, the commit-and-print tap happens with no outlet on screen.
2. **Hide the pack badge for piece and dozen SKUs** (E3): show it only when pack factor > 1 (`SaleScreens.kt:472`). Today 26 lighters read as "২৬ প্যাকেট".
3. **Stop using "বাকি" for unvisited outlets** (P4): rename the segment, for example "ভিজিট বাকি" or "অবশিষ্ট" (`feature-outlet` strings `:83`), so "বাকি" means due everywhere.
4. **Restore green check-in and red check-out** on Attendance (A3): both the buttons and the hold control; also "চেক আউট ৫টার পরে সক্রিয় হবে" and 12-hour time (A5, A6).
5. **Day-end dues as a Yes/No dialog** with the manual's sentence (SS4), and the five familiar row words আউটলেট / বিক্রয় / স্টক / কিউসি / প্রমোশন for the matching rows (SS2).
6. **Stock category totals panel** with "ক্যাটাগরি / মোট ইস্যু / স্টক" for all four categories (S3), not issue-only chips.
7. **Memo layout words**: category subtotals "মোট সিগারেট …" on screen and in `memo.json` (M3); always show "মোট ডিসকাউন্ট" and "মোট QC" lines (M4); due amount in the memo list (M2).
8. **Say "OTP"** on the device-binding screen (L3), for example title "OTP যাচাইকরণ", hint "আপনার TSO-এর দেওয়া ৪ সংখ্যার OTP লিখুন।".
9. **One stick word** (H5): set `sr_unit_stick` to "স্টিক" to match the other screens and the live Home.
10. **Green/red হ্যাঁ/না and "সফল / ওকে"** where they are missing: part-payment (R6), QC submit confirm (Q4), sale success (R9), logout (X5).
11. **Smaller label and format fixes**: task type tag and title "টাস্ক" (T1, T2); Summary title "বিক্রয় সারসংক্ষেপ" and "ডিসকাউন্ট মূল্য" (SU1, SU4); task due date in ISO (T4); Latin memo number (M5); "Daily" localised or dropped from the Home header (H1); outlet-form labels (O2-O5); edit-reason dialog wording (M9); tutorial empty text (X1).

## Not presentation-only (for the lead)

- Q2 six-type QC entry with max / done / remaining (G-man-sr-03, G-man-sr-02).
- SH2 per-SKU Sale History for one date (needs memo lines in `SaleHistory`).
- A2 reverse-geocoded address (deliberate; keep coordinates offline).
- E4 the three sale-card indicators (meaning unknown, G-man-sr-05).
- Printed memo digits (`memo.json` `"digits": "latin"`) and the layout are unverified against a real printout (N-020); M3 is the only print divergence evidenced by the on-screen memo.

## Fixed by the android-tso lane (2026-10-17, first batch, goldens re-recorded)
- **E3** pack badge only when the SKU's pack factor is above 1 (lighters and matches no longer read "N packets").
- **H5** `sr_unit_stick` (bn) is "স্টিক", as on every other SR screen.
- **P4** picker segment "বাকি N" (outlets left) is now "অবশিষ্ট N"; "বাকি" stays the due word.
- **E1/R1** the outlet name and code stay on screen above Sale entry and Review (`SaleHost`).
- **Memo number** shown as issued (ASCII) on the memo detail, no more mixed script.
Still open (needs a decision or a larger change): the two non-presentation majors (QC six-type entry, per-SKU sale history), the Attendance green/red and 12-hour wording (A5, A6), day-end dues dialog, stock category totals panel, memo category subtotals, "OTP" wording on binding, Yes/No and "সফল / ওকে" dialogs, small label and format items.

## Fixed by the android-tso lane, second batch (2026-10-18, goldens re-recorded)
SU1 summary screen title "বিক্রয় সারসংক্ষেপ"; SU4 column "ডিসকাউন্ট মূল্য"; T1 Tasks screen title "টাস্ক" (the Home tile label is left, it is a kit width fixture); M4 the memo's "মোট ডিসকাউন্ট" and "মোট QC" lines always show, zeros included; H7 the KPI strike rate reads "10%" like Home.
Also done earlier: Q2 six-type QC (with an Opus checker), SH2 per-SKU Sale History, Q4 QC submit confirm.
Still open (presentation, not yet done): A3 green check-in / red check-out buttons and A5/A6 wording and 12-hour time, SS4 day-end dues Yes/No dialog and SS2 row words, S2/S3 stock category totals panel, M2 due amount in the memo list, M3 category subtotals on the memo (screen and print template), M6/M8 button colours, M9 edit-reason dialog, H1 header line, T2-T4 task tag/date, OTP wording on binding, success dialogs. E4 sale-card indicators and A2 attendance address are left on the lead's ruling.
