# ARON TSO (Territory Sales Officer) App - Consolidated Manual Inventory

Source: "TSO USER MANUAL" (official AKTCL/Apsis document), PDF `eefcbecb-TSO_App_User_Manual.pdf`, 22 pages (16:9 slides, Bangla title bars, Samsung phone screenshots, Bangla callouts, red boxes on the area being explained).
Built from the two chunk transcripts: `tso-p01-11.md` (pages 1-11) and `tso-p12-22.md` (pages 12-22). **Both expected chunk ranges (1-11, 12-22) were present; no range is missing. Pages 1-22 are all covered.** Pages 17-20 were re-checked against the PDF image directly and the transcript matched (Visit Query, Assign Task, Set Plan, Select Outlets, Target Status summary+detail, Create New Feedback).

Conventions: "BN:" = Bangla verbatim from the manual (original typos kept); "EN:" = English meaning. English UI strings are verbatim as printed. "(stated)" = printed in the manual; "(inferred)" = derived from screenshots/numbers by the transcriber, NOT printed. "p." = manual page. Product name on screen: "ARON"; installed app/launcher name: "ARON TSO". Demo user in every screenshot: "Apsis" (greeting "Good day, Apsis"; drawer header "TSO / Apsis"). All screenshot data is test data (Apsis Wing, Apsis Division, Apsis Territory, Apsis House, zone "Banani - Test", routes "Apsis Route", "Apsis Route 2", "Apsis Route 3", users sr334001..3, amo6334 etc.). Screenshot dates April 2026 (2026-04-26/27/28); APK build date in file name 28_02_2026.

Global statement about the manual: it contains NO table of rules, NO field-length limits, NO error messages for validation, and NO mention of offline mode, sync, refresh or "internet required" anywhere on pp. 1-22. Where a field, rule or message is not stated, this file says "not stated".

Pages 1 (cover: "ARON / TSO USER MANUAL", red-purple gradient, no content) and 22 (closing slide: BN "ধন্যবাদ" = "Thank you") are non-screen pages; they contain no fields, rules or messages.

Role: every screen below is for the **TSO** role (Territory Sales Officer) unless stated. The Dashboard's Login/Bikroy Joma lists show SR and AMO users that sit under the TSO's territory.

## Common elements (apply to every screen below unless stated)

- **Back control:** round "<" button at top-left of every sub-screen header; screen title centred, in green/yellow-green text (pp. 7, 10-21).
- **Home FAB:** round blue floating "home" (house icon) button at bottom-centre on many screens (Dashboard p.4/5/9/21, Select Outlets p.18, My Teams/Outlets p.15, Visit Query p.17 partly, Create New Feedback p.20 partly). Tap behaviour not described (presumably returns to Dashboard - inferred). Not visible on Final Submit selection (p.12).
- **Header bar on Dashboard:** hamburger icon (3 lines) at top-LEFT, greeting "Good day, <name>", logout icon (arrow leaving a box) at top-right.
- **Dropdown style:** purple outline, white caret "v" at right, placeholder "Select <X>". **Primary button:** full-width green (lime-to-green gradient), small white text; grey when disabled/inactive. **Date fields:** calendar icon at left or right; native Android date picker.
- **Target hardware shown:** Samsung Android handsets with hardware recent/home/back keys (J-series style). Dark navy UI theme.
- **Languages:** UI labels are English; callouts, some alert text and some Visit Query questions are Bangla. Bangla is not used for most field labels (the Bangla-first requirement in CLAUDE.md is not reflected in TSO screens as documented).

---------------------------------------------------------------------

# A. SCREEN INVENTORY (in the order a user meets them)

Total screens: 24 (TSO-S-01 .. TSO-S-24). Android system dialogs (S-01, S-02) and in-app dialogs (S-10, S-12, S-24) are counted as screens because each has its own text/controls.

---------------------------------------------------------------------
## TSO-S-01 - Install: file manager and "install this app" prompt (OS-level)
- **Title:** BN: অ্যাপ্লিকেশান ইনস্টল করার প্রক্রিয়া / EN: Application install process
- **Manual pages:** 2 (continues on p.3, S-02)
- **Role(s):** TSO user / device holder (install is outside the app).
- **Purpose:** Side-load the TSO APK onto the device from internal storage.
- **Entry points:** Device file manager ("My Files"-style), path "Internal storage".
- **Exits:** Tap "Install" -> S-02. Tap "Cancel" -> returns to file manager (stated only as the button; result not described).
- **Controls / content:**
  - File manager top bar: back arrow "<", search icon, overflow "⋮"; breadcrumb home icon > "Internal storage".
  - Listed entries (name / date / size): (cut-off first row, probably "Movies") 15 Apr 7:18 pm / 2 items; Music 25 Jun 2024 5:44 pm / 1 item; Notifications / 0 items; Pictures 19 Apr 8:53 am / 9 items; Podcasts / 0; Recordings / 0; Ringtones / 0; **`aron_tso_app_28_02_2026_v1.apk`** 23 Apr 5:37 pm / 74.09 MB (red-boxed = the file to tap); `live_aron_amo_a..._2026_v1.0.3.apk` 14 Mar 12:09 am / 79.23 MB (name truncated); `live_aron_amo_a..._2026_v1.old.apk` 3 Mar 2:43 pm / 78.75 MB (name truncated).
  - System dialog: app name "ARON TSO" with icon; text "Do you want to install this app?"; buttons "Cancel", "Install" (Install red-boxed).
- **Callouts (verbatim):**
  - BN: ARON TSO অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার জন্য প্রথমে ফাইল ম্যানেজার ওপেন করুন। / EN: To install the ARON TSO application on the device, first open the File Manager.
  - BN: এরপর ARON TSO অ্যাপ্লিকেশন file এর উপর ক্লিক করুন। / EN: Then click on the ARON TSO application file.
  - BN: এরপর “Install” অপশন এর উপর ক্লিক করুন। / EN: Then click on the "Install" option.
- **Rules / validations:** APK is side-loaded from device storage, not the Play Store (inferred from the screens; not stated in words). APK file-name convention visible: `aron_<role>_app_<dd_mm_yyyy>_v<n>.apk`; AMO builds show `live_aron_amo_a..._2026_v1.0.3.apk` and `..._v1.old.apk` (versioning shown in file names only). APK size about 74.09 MB (TSO) vs 79.23 / 78.75 MB (AMO).
- **Messages:** "Do you want to install this app?" (M-01).
- **Hardware / permissions:** device storage/file manager; Android "install unknown apps" permission is implied but NOT mentioned.
- **Data read/written:** none (OS).
- **Online/offline:** not applicable (local install).

---------------------------------------------------------------------
## TSO-S-02 - Install complete and launcher icon
- **Title:** BN: অ্যাপ্লিকেশান ইনস্টল করার প্রক্রিয়া / EN: Application install process (2 of 2)
- **Manual pages:** 3
- **Role(s):** TSO user.
- **Purpose:** Confirm install; launch the app via "Open" or via the launcher icon "ARON TSO".
- **Entry points:** S-01 after "Install".
- **Exits:** "Open" -> app launches -> S-03; "Done" -> closes dialog; launcher icon tap -> S-03.
- **Controls / content:**
  - System dialog: app "ARON TSO", text "App installed."; buttons "Done" and "Open" (both red-boxed).
  - Android home screen with launcher icon labelled "ARON TSO" (stylised letter "A" in a circular gradient; red-boxed).
- **Callouts (verbatim):**
  - BN: অ্যাপ্লিকেশন install সম্পন্ন হয়ে যাবার পর উপরোক্ত অ্যালার্ট দেখতে পারবেন। এখান থেকে “Open” অপশন এর মাধ্যমে অ্যাপ্লিকেশনটি ওপেন করতে পারবেন। এবং “Done” অপশন এর মাধ্যমে অ্যাপ্লিকেশন install সম্পন্ন করতে পারবেন। / EN: After the application install completes you will see the above alert. From here you can open the application with "Open", and finish the install with "Done".
  - BN: ARON SR অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার পর “ARON TSO” নামে একটি icon দেখতে পারবেন। / EN: After installing the ARON SR [sic] application on the device you will see an icon named "ARON TSO". (copy-paste error: says SR; see TSO-U-01)
  - BN: Login করার জন্য icon টির উপর ক্লিক করুন। / EN: Click on the icon to log in.
- **Rules:** none beyond the above.
- **Messages:** "App installed." (M-02).
- **Hardware / permissions:** none stated.
- **Data read/written:** none.
- **Online/offline:** not applicable.

---------------------------------------------------------------------
## TSO-S-03 - Login
- **Title:** BN: লগইন করার নিয়ম / EN: Login procedure (screen has no visible title; logo only)
- **Manual pages:** 4
- **Role(s):** TSO (individually issued credentials).
- **Purpose:** Authenticate a TSO.
- **Entry points:** Launcher icon "ARON TSO" (S-02) or "Open".
- **Exits:** "Login" -> S-04 Dashboard. Logout (S-24) returns here (implied; not shown).
- **Controls:**
  | Control | Type | Label / placeholder | Required | Default | Limits |
  |---|---|---|---|---|---|
  | Username | text input | label "Username", placeholder "Username" | implied (not stated) | empty | not stated |
  | Password | text input | label "Password", placeholder "Password" (masking / eye icon not visible) | implied (not stated) | empty | not stated |
  | Login | button (blue gradient, centred) | "Login" | - | - | - |
  - Logo: circular badge with stylised "A" top-centre. No app name text, no version number.
  - NOT present on screen: "Forgot password", "Remember me", language switch, server/environment selector.
- **Callout (verbatim):** BN: নির্দিষ্ট TSO এর জন্য, তাদের নির্দিষ্ট ইউজারনেম এবং পাসওয়ার্ড দিয়ে, “লগইন” বাটনে ক্লিক করুন। / EN: For a specific TSO, enter their specific username and password and click the "Login" button. Second callout: BN: “লগইন” বাটনে ক্লিক করার পর Dashboard পেজ আসবে / EN: After clicking "Login" the Dashboard page will appear.
- **Rules / validations:** each TSO has an individually issued username and password (stated). No error/validation text shown for empty fields, wrong credentials, wrong role or no network.
- **Messages:** none shown.
- **Hardware / permissions:** none stated at login (no location/camera prompt shown anywhere on pp. 1-11).
- **Data read/written:** reads user credentials -> authentication; reads user display name ("Apsis") for greeting.
- **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-04 - Dashboard (home)
- **Title:** BN: ড্যাশবোর্ড / EN: Dashboard (no in-screen title; header shows greeting)
- **Manual pages:** 4 (landing), 5, 6, 8 (menu button), 9 (menu entry), 21 (logout). Repeated on pages 4, 5, 6, 8, 9, 21.
- **Role(s):** TSO.
- **Purpose:** Territory-level summary for "that day": sales per product with achievement %, sales by channel, CPR/strike rate, segment contribution, brand call/memo ratio, login & Bikroy Joma status.
- **Entry points:** after Login; drawer "Dashboard / View Dashboard"; Home FAB.
- **Exits:** hamburger -> S-06 drawer; logout icon -> S-24; "Login & Bikroy Joma Status" card -> S-05 (tap implied; gesture not stated); "By Channel STD ▸" title has a "▸" glyph (drill-down not described).
- **Header controls:** hamburger (top-left; the callout on p.8 wrongly says "top right"), greeting text "Good day, Apsis" (format "Good day, <user display name>"), logout icon (top-right). Floating blue Home FAB bottom-centre.
- **Widgets (top to bottom, scrolls vertically):**
  1. **Sales tiles card (purple)** - four rows, each with heading, a figure with caption "Total Sales" at left and a circular ring with percentage at right:
     - "Sales (Cigarette)": "160.0" Total Sales, ring "92.5%" (p.4/5/8/9); on p.21 "0.0" and "0.0%".
     - "Sales (Bidi)": "0.0", ring "0.0%".
     - "Sales (Lighter in Box)": "0.0", "0.0%".
     - "Sales (Match in Dozen)": "0.0", "0.0%" (p.5, p.9; cut off/not visible on p.4, p.8, p.21).
     - Figures have one decimal place. Units: "in Box" (Lighter) and "in Dozen" (Match) are in the heading; Cigarette/Bidi unit not stated. Ring % = "achievement %" (BN: অ্যাচিভমেন্ট %); formula not printed (inferred = sales / target; 160/0.925 implies target about 173).
  2. **Card "By Channel STD ▸":** donut in solid green, label "100.00%" and "GT Channel" (GT not expanded in the manual; presumably General Trade). "STD" not expanded.
  3. **Card "CPR":** "44" caption "Target Outlet"; "2" caption "Successful Calls"; ring "4.5%" caption "Strike Rate" (inferred 2/44 = 4.545%).
  4. **Card "By Segment Value Contribution":** legend orange "Value", teal "Volume"; y-axis ticks 83%, 67%, 50%, 33%, 17%, 0%; x-axis label "Medium"; chart is empty in screenshots (type of chart unknown).
  5. **Card "By Brand Call/Memo Ratio":** y ticks 83% ... 0%; x label "Maxim - Platinum Series"; chart empty.
  6. **Card "Login & Bikroy Joma Status":** row "Login Status" value "25%", progress bar, "4" Target Route, "1" Total Login; row "Bikroy Joma Status" value "0%", progress bar empty, "1" Login Count, "0" Total Bikroy Joma. (Inferred: 1/4 = 25%; 0/1 = 0%.) "Bikroy Joma" = Bangla বিক্রয় জমা, transliterated: sales deposit/submission; not expanded in the manual.
- **Callouts (verbatim):**
  - p.5 left: BN: এইখানে টেরিটোরি অনুযায়ী ওইদিনের (Cigarette,Bidi, Lighter, match) এর ‘’Total Sales” এবং অ্যাচিভমেন্ট % দেখা যাবে। / EN: Here, according to the territory, that day's "Total Sales" and achievement % of (Cigarette, Bidi, Lighter, Match) will be shown.
  - p.5 right: BN: এইখানে টেরিটোরি অনুযায়ী ওইদিনের (By Channel Successful Call, Live CPR, By Segment Value Contribution) এর এবং অ্যাচিভমেন্ট % দেখা যাবে। / EN: Here, according to the territory, that day's (By Channel Successful Call, Live CPR, By Segment Value Contribution) and achievement % will be shown. (names differ from on-screen titles "By Channel STD", "CPR")
  - p.6 left: BN: এইখানে টেরিটোরি অনুযায়ী ওইদিনের (By Brand Call/Memo Ratio) এবং অ্যাচিভমেন্ট % চার্ট আকারে দেখা যাবে। / EN: ...(By Brand Call/Memo Ratio) and achievement % will be shown in chart form. (red box is on By Segment Value Contribution; mismatch)
  - p.6 right: BN: এইখানে টেরিটোরি অনুযায়ী ওইদিনের Login & Bikroy Joma Status দেখা যাবে। / EN: Here, according to the territory, that day's Login & Bikroy Joma Status will be shown.
  - p.8: BN: সকল মেন্যুলিস্ট দেখার জন্য উপরে ডান দিকের [≡ icon] বাটনে ক্লিক করুন। / EN: To see the full menu list, click the [≡] button at the top right.
  - p.9: BN: এই মেন্যুতে Dashboard সকল তথ্য পেয়ে যাবো / EN: In this menu we will get all the Dashboard information. And BN: এইখানে Dashboard এর সকল তথ্য দেখা যাবে। / EN: Here all the information of the Dashboard will be seen.
  - p.21: BN: লগআউট এর জন্য এই আইকন এ ক্লিক করতে হবে / EN: To log out, click this icon.
- **Rules:** all figures are per the TSO's territory and for "that day" (stated). Achievement % shown for sales tiles, channel, CPR, segment and brand ratio (formulas not printed).
- **Messages:** greeting "Good day, Apsis" (M-03). No error/empty/loading text shown.
- **Hardware / permissions:** none.
- **Data read:** territory sales totals per product category (Cigarette, Bidi, Lighter in Box, Match in Dozen); targets; Target Outlet count; Successful Calls; Strike Rate; sales by channel; segment value/volume; brand call/memo ratio; Target Route; Total Login; Login Count; Total Bikroy Joma.
- **Online/offline:** not stated (day figures "that day's" suggest server fetch; unknown).

---------------------------------------------------------------------
## TSO-S-05 - Login & Bikroy Joma Status (detail: Not Logged In / Not Uploaded)
- **Title:** "Login & Bikroy Joma Status" (in-app, yellow-green text). Page header BN: ড্যাশবোর্ড / EN: Dashboard.
- **Manual pages:** 7
- **Role(s):** TSO.
- **Purpose:** list which SR/AMO users (per route) have not logged in today and which have not uploaded (Bikroy Joma) today.
- **Entry points:** S-04 "Login & Bikroy Joma Status" card (tap implied).
- **Exits:** "<" back -> S-04.
- **Controls:** back "<"; two tabs "Not Logged In" (appears first/default; default not stated) and "Not Uploaded" (selected tab outlined/highlighted). No filter, sort or search visible; list scrolls vertically.
- **List item (card with person avatar):** line 1 bold display name (e.g. "SR - Testing Banani 2", "AMO-6334"); line 2 "User Name: <username>"; line 3 "Route: <route name>"; line 4 "Dep Name: <depot name>" ("Dep" not expanded).
- **Sample rows "Not Logged In":** (1) SR - Testing Banani 2 | sr334002 | Apsis Route 2 | Banani - Test; (2) AMO-6334 | amo6334 | Apsis Route 2; (3) AMO-63342 | amo63342 | Apsis Route 2; (4) SR - Testing Banani 3 | sr334003 | Apsis Route 3; (5) AMO-6334 | amo6334 | Apsis Route 3 (all Dep Name Banani - Test).
- **Sample rows "Not Uploaded":** (1) SR - Testing Banani | sr334001 | Apsis Route; (2) AMO-6334 | amo6334 | Apsis Route; (3) AMO-63342 | amo63342 | Apsis Route; (4) SR - Testing Banani 2 | sr334002 | Apsis Route 2; (5) AMO-6334 | amo6334 | Apsis Route 2; (6) AMO-63342 | amo63342 | Apsis Route 2 (all Banani - Test).
- **Callouts (verbatim):** BN: এইখানে টেরিটোরি অনুযায়ী ওইদিনের Not Logged In এর বিস্তারিত তথ্য দেখা যাবে। / EN: Here, according to the territory, the detailed information of that day's "Not Logged In" will be shown. Same sentence with "Not Uploaded" for the second tab.
- **Rules:** scoped by territory and that day (stated). Row unit is a user-route pair (inferred: same username amo6334 appears on three routes). AMO users listed alongside SRs. Meaning of "Not Logged In" = not logged in today; "Not Uploaded" = logged in but no Bikroy Joma today (inferred; not defined).
- **Messages:** none.
- **Hardware / permissions:** none.
- **Data read:** Users (SR, AMO: display name, username), Routes (route name), Depots (Dep Name), daily login events, daily upload (Bikroy Joma) events.
- **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-06 - Navigation drawer (side menu)
- **Title:** none (drawer headed "TSO" / "Apsis").
- **Manual pages:** 8 (first shown), 9, 10, 12, 15, 16, 18, 19, 20. Repeated on pp. 8, 9, 10, 12, 15, 16, 18, 19, 20.
- **Role(s):** TSO.
- **Purpose:** top-level navigation.
- **Entry points:** hamburger icon on Dashboard (S-04).
- **Exits:** each item as below; logout icon -> S-24.
- **Header:** small text "TSO" (role), bold user name ("Apsis"); logout icon at the top-right; dashboard dimmed behind.
- **Menu items, in fixed order (icon, bold title, grey subtitle, chevron):**
  | # | Title | Subtitle | Chevron | Goes to |
  |---|---|---|---|---|
  | 1 | Dashboard | View Dashboard | > | S-04 |
  | 2 | Leave | Manage your leaves | > | S-07 |
  | 3 | Final Submit | Submit Sales Data | > | S-09 |
  | 4 | My Periphery | Check your team and outlet | v (collapsed) / ^ (expanded) | expands to 4a, 4b |
  | 4a | - My Team | Periphery | > | S-13 |
  | 4b | - Retailer | Periphery | > | S-14 |
  | 5 | My Call | Create and manage plans | v / ^ | expands to 5a, 5b |
  | 5a | - My Visit Plan | View your visit plan | > | S-15 |
  | 5b | - Set Plan | Set a new visit plan | > | S-19 |
  | 6 | Target Status | Check targets | > | S-21 |
  | 7 | My Feedback | Manage your feedbacks | v | expandable per chevron, but NO sub-items shown; manual shows only "Create New Feedback" (S-23) |
- **Callout (verbatim, p.8):** BN: এইখানে Dashboard, Leave, Final Submit, My periphery, My call, Target Status, My Feedback দেখা যাবে / EN: Here you will see Dashboard, Leave, Final Submit, My periphery, My call, Target Status, My Feedback.
- **Rules:** ">" = opens a screen; "v" = expandable group (stated by transcription of chevrons). Callout spelling "My periphery" vs on-screen "My Periphery".
- **Messages:** none. **Hardware:** none. **Data:** user role/name. **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-07 - Leave Applications (list)
- **Title:** "Leave Applications"; section BN: লিভ ম্যানেজ / EN: Leave manage(ment)
- **Manual pages:** 10 (list), 11 (repeated on left)
- **Role(s):** TSO.
- **Purpose:** show the TSO's own submitted leave applications and their status.
- **Entry points:** drawer "Leave".
- **Exits:** "<" back; "Apply For Leave" -> S-08.
- **Controls / content:**
  - Cards (blue), each: top-right orange status badge "DMO approval pending"; calendar icon + "YYYY-MM-DD to YYYY-MM-DD"; "Reason: <text>"; right side number + caption "Days"/"Day".
  - Sample cards: (1) 2026-02-25 to 2026-02-25, Reason: Family emergency, "2" "Days"; (2) 2026-03-04 to 2026-03-04, Reason: test, "1" "Day"; (3) 2026-03-05 to 2026-03-05, Reason: only one day can apply!, "1" "Day". All badges "DMO approval pending".
  - Button (full width, green): "Apply For Leave".
- **Callouts (verbatim):** BN: এই মেন্যুতে Leave সকল তথ্য পেয়ে যাবো / EN: In this menu we will get all the Leave information. BN: এইখানে আবেদন করা সকল Leave এর তথ্যগুলো দেখা যাবে । / EN: Here the information of all the Leaves applied for can be seen. BN (p.11): নতুন Leave এন্ট্রি করার জন্য ‘’Apply For Leave” এ ক্লিক করতে হবে। / EN: To make a new Leave entry you must click "Apply For Leave".
- **Rules:** date format ISO YYYY-MM-DD; caption "Day" for 1, "Days" for 2 (singular/plural); leave TYPE is not shown on cards; only status value visible is "DMO approval pending" (other statuses not shown); approver is the DMO (not expanded); apparent ascending date order (not stated); no leave balance shown.
- **Messages:** badge "DMO approval pending" (M-04).
- **Hardware:** none. **Data read:** Leave application (from date, to date, reason, number of days, status, approver).
- **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-08 - Leave Apply (form) with native date picker
- **Title:** "Leave Apply" (green text)
- **Manual pages:** 11
- **Role(s):** TSO.
- **Purpose:** submit a new leave application.
- **Entry points:** S-07 "Apply For Leave".
- **Exits:** "<" back; "Apply" -> (presumably back to S-07 with a new card in status "DMO approval pending"; not shown).
- **Fields (in order):**
  | # | Field | Type | Placeholder / default | Options | Required | Limits |
  |---|---|---|---|---|---|---|
  | 1 | Leave Type | dropdown | "Select leave Type" / none | Casual, Sick, Earn | implied (callout lists it as must-enter) | n/a |
  | 2 | Date | date picker (calendar icon at right) | "Select Date" / none | native Android date picker (header "Sun, Apr 26" with pencil icon, April 2026 grid, selected day = filled purple circle, two bottom-right buttons, text unreadable - presumably Cancel/OK) | implied | single date; past/weekend/before-today rules not stated |
  | 3 | Number of days | numeric input | default "0" | - | implied | typed vs auto-derived not stated; limits not stated |
  | 4 | Reason | multi-line text area | "Reason" | - | implied | not stated |
  | 5 | Apply | button (full-width green) | - | - | - | - |
- **Callout (verbatim):** BN: Apply For Leave এ ক্লিক করার পর Leave Type ( Casual, Sick, Earn ), Leave Date, Number of Days, Reason টাইপ করতে হবে এরপর Apply এ ক্লিক করতে হবে। / EN: After clicking Apply For Leave you must enter (type) Leave Type (Casual, Sick, Earn), Leave Date, Number of Days, Reason, and then click Apply.
- **Rules:** exactly the three leave types; all four inputs to be entered (stated by callout); new application starts as "DMO approval pending" (inferred from list). The form has one Date field while the list shows from/to ranges (see TSO-U-14).
- **Messages:** none shown (no required-field message, no success toast, no overlap/balance check).
- **Hardware:** none. **Data written:** Leave application (type, date, number of days, reason, applicant TSO, initial status).
- **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-09 - Final Submit: selection form
- **Title:** "Final Submit"; BN: ফাইনাল সাবমিট
- **Manual pages:** 12 (empty state), 14 (filled state). Repeated on pp. 12, 14.
- **Role(s):** TSO.
- **Purpose:** choose the Zone (via org hierarchy) whose day sales will be finally submitted.
- **Entry points:** drawer "Final Submit / Submit Sales Data".
- **Exits:** "<" back; "Get Sales Data" -> S-11 (or S-10 alert if already submitted).
- **Fields (all dropdowns, label above, placeholder inside):**
  | # | Label | Placeholder | Sample value (p.14) |
  |---|---|---|---|
  | 1 | Wing | "Select Wing" | Apsis Wing |
  | 2 | Division | "Select Division" | Apsis Division |
  | 3 | Territory | "Select Territory" | Apsis Territory |
  | 4 | House | "Select House" | Apsis House |
  | 5 | Zone | "Select Zone" | Banani - Test |
  - Button "Get Sales Data": full-width, GREY in empty state (p.12), GREEN when all five chosen (p.14) -> implied disabled until all five selected.
  - Option lists not shown; cascade (Wing > Division > Territory > House > Zone) implied only.
- **Callouts (verbatim):** BN: এই মেন্যুতে সকল জোনের "Final Submit" এর তথ্য দেখা যাবে এবং ফাইনাল সাবমিট দেওয়া যাবে। / EN: In this menu the Final Submit information of all zones can be seen and the final submit can be given. BN: ফাইনাল সাবমিট এর জন্য এইখানে Wings, Division, Terrotory, House, Zone সিলেক্ট করে ''Get Sales Data'' তে ক্লিক করতে হবে। / EN: For final submit, select Wings, Division, Territory, House, Zone here and click 'Get Sales Data'. (typos "Wings", "Terrotory" in original)
- **Rules:** all five levels must be selected before Get Sales Data (stated; disabled look inferred). Final Submit duplicate check happens here (S-10).
- **Messages:** placeholders only (M-11).
- **Hardware:** none. **Data read:** org hierarchy master (Wing, Division, Territory, House, Zone) scoped to the TSO; Final Submit status for (zone, date).
- **Online/offline:** requires server for duplicate check/sales fetch (implied).

---------------------------------------------------------------------
## TSO-S-10 - Final Submit: "already submitted" alert
- **Title:** none (modal without title)
- **Manual pages:** 14
- **Role(s):** TSO.
- **Purpose:** block a second Final Submit for the same Zone and day.
- **Entry points:** tapping "Get Sales Data" (S-09) for a Zone already final-submitted today (highlighted trigger in screenshot; the callout words it as "submit again").
- **Exits:** "OK" (blue) -> dismisses, stays on S-09.
- **Message (verbatim):** BN: আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন! / EN: You have already submitted 'FINAL SUBMIT' for today!
- **Callout (verbatim, both bubbles):** BN: Zone এর ফাইনাল সাবমিট হয়ে গেলে পরবর্তীতে সাবমিট করতে চাইলে অ্যালার্ট দিবে / EN: Once the final submit of a Zone is done, if one wants to submit again later, it will give an alert.
- **Rules:** one Final Submit per Zone per day.
- **Hardware:** none. **Data read:** Final Submit status for (zone, today) from server. **Online/offline:** online check (implied; may be set by another device/TSO).

---------------------------------------------------------------------
## TSO-S-11 - Final Submit: Routes and Submit
- **Title:** "Final Submit"
- **Manual pages:** 13
- **Role(s):** TSO.
- **Purpose:** review the zone's routes for the Sales Date and perform the Final Submit.
- **Entry points:** S-09 "Get Sales Data" (not yet submitted).
- **Exits:** "<" back; "Submit" -> S-12.
- **Content / controls:**
  - Info bar: label "Sales Date:" value "2026-04-26" (green text, YYYY-MM-DD; read-only, not an input).
  - Heading "Routes".
  - Scrollable route cards (blue): route name bold + second line "FF: <name>" (FF presumably Field Force = SR; not defined). Sample: "AMO-Apsis RouteAMO" / "FF: SR Not Set" (shown twice); "Apsis AMOAMO" / "FF: SR Not Set"; "Apsis RouteDaily" / "FF: SR - Testing Banani"; "Apsis Route 2Daily" / "FF: SR - Testing Banani 2"; "Apsis Route 3Daily" / "FF: SR - Testing Banani 3" (seen on dimmed right screenshot). The route type word ("AMO"/"Daily") appears appended to the route name (e.g. "Apsis RouteDaily").
  - No checkboxes, totals, amounts or counts. Cards are not stated as tappable.
  - Button "Submit" full-width green (red-outlined).
- **Callout (verbatim):** BN: Zone সিলেক্ট এর পর ফাইনাল সাবমিট এর জন্য ''Submit'' এ ক্লিক করতে হবে। / EN: After selecting Zone, click "Submit" for final submit.
- **Rules:** one Submit covers all routes of the selected Zone for the Sales Date (inferred); routes with "FF: SR Not Set" are still listed; whether they block submission is not stated. No confirm prompt between Submit and Success is shown.
- **Messages:** none on this state ("FF: SR Not Set" is a data string M-12).
- **Hardware:** none. **Data read:** routes of zone with assigned SR name, Sales Date. **Data written:** Final Submit record (zone + date + TSO) (inferred), probably locks the day's sales.
- **Online/offline:** online (server round trip implied by success message).

---------------------------------------------------------------------
## TSO-S-12 - Final Submit: "Success" dialog
- **Title:** "Success" (modal)
- **Manual pages:** 13
- **Role(s):** TSO.
- **Purpose:** confirm that Final Submit completed.
- **Entry points:** S-11 "Submit".
- **Exits:** "OK" (blue, right-aligned) -> dismisses (destination after OK not shown).
- **Message (verbatim, English only):** Title "Success"; body "Final Submit Done Successfully..." (literal trailing ellipsis); button "OK".
- **Callout on this screenshot (p.13 right):** BN: এইখানে Dashboard, Leave, Final Submit, My periphery, My call, Target Status, My Feedback দেখা যাবে / EN: Here Dashboard, Leave, Final Submit, My periphery, My call, Target Status, My Feedback will be seen. -> describes the drawer, NOT this dialog (copy-paste error; see TSO-U-09).
- **Rules:** none additional. **Hardware:** none. **Data:** none. **Online:** implied online.

---------------------------------------------------------------------
## TSO-S-13 - My Teams (live SR locations map)
- **Title:** "My Teams" (menu item is "My Team"); section "My Periphery".
- **Manual pages:** 15
- **Role(s):** TSO.
- **Purpose:** see live location of the TSO's SRs by route within a zone.
- **Entry points:** drawer My Periphery > "My Team / Periphery".
- **Exits:** "<" back; Home FAB.
- **Controls / content:**
  - Zone dropdown (no label; red-boxed), value "Banani - Test", white caret. Required implied; default not stated (possibly the first/only zone).
  - Map: Google-Maps-style tilted map of Dhaka (Banani / Kemal Ataturk Ave, Road No. 11, Road No. 16A). Controls: compass icon top-left, "my location" crosshair top-right, recentre icon bottom-left (partly covered), Home FAB bottom-centre.
  - SR marker: orange/yellow delivery-scooter-style icon on a pink pin (red-boxed). Marker tap behaviour (SR name/time) not shown.
- **Callouts (verbatim):** BN: My Periphery মেন্যুতে TSO এর সকল SR দের লাইভ লোকেশন এবং রিটেইলার এর লোকেশন পাওয়া যাবে । / EN: In the My Periphery menu, the live locations of all of the TSO's SRs and the locations of retailers can be found. BN: এইখানে My Team এ ক্লিক করলে রুট অনুযায়ী সকল এসআর এর লাইভ লোকেশন দেখা যাবে / EN: Here, clicking My Team will show the live location of all SRs according to route.
- **Rules:** live SR locations per route within selected zone. Refresh interval for "live" not stated.
- **Messages:** none. **Hardware / permissions:** location ("my location" control, implied), Internet (map tiles). **Data read:** SR live location (written by SR devices), zone list. **Online/offline:** online-only (implied).

---------------------------------------------------------------------
## TSO-S-14 - Outlets (Retailer map by zone and radius)
- **Title:** "Outlets" (menu item "Retailer / Periphery")
- **Manual pages:** 15
- **Role(s):** TSO.
- **Purpose:** see retailer outlet locations in a zone within a radius.
- **Entry points:** drawer My Periphery > "Retailer / Periphery".
- **Exits:** "<" back; Home FAB.
- **Controls / content:**
  - Label "Zone" (left) + dropdown (red-boxed) value "Banani - Test".
  - Label "Radius" (right) + list/dropdown (red-boxed) showing options "50", "100", "300"; units NOT displayed (metres presumed); default not indicated; it looks like an expanded vertical list of three values.
  - Map: Google-Maps 2D Dhaka (labels in English and Bangla e.g. "Dhaka ঢাকা", MOHAKHALI, MALIBAGH, Shewrapara, Kazipara, Mirpur, Bashundhara City Shopping Complex, University of Dhaka, Liberation War Museum, Le Meridien Dhaka); red "H" pins (base-map hospital icons); a blue dot near the right = user's current position (implied). No outlet markers visible in the screenshot. Home FAB.
- **Callout (verbatim):** BN: Retailer এ ক্লিক করলে Zone অনুযায়ী রেডিয়াস অনুযায়ী রিটেইলার এর লোকেশন পাওয়া যাবে / EN: Clicking Retailer will show the locations of retailers according to zone and according to radius.
- **Rules:** radius fixed set 50 / 100 / 300; zone-based filter; radius centre presumably the TSO's current location (inferred).
- **Messages:** none. **Hardware / permissions:** location, Internet/map tiles. **Data read:** outlet coordinates (lat/long) per zone, zone list, device location. **Online/offline:** online (implied).

---------------------------------------------------------------------
## TSO-S-15 - My Visit Plan (selector)
- **Title:** "My Visit Plan"; section "My Call".
- **Manual pages:** 16
- **Role(s):** TSO.
- **Purpose:** choose date, zone and route to list the planned outlets and their status.
- **Entry points:** drawer My Call > "My Visit Plan / View your visit plan".
- **Exits:** "<" back; "Show Outlet" -> S-16.
- **Fields (red-boxed group):**
  | # | Label | Type | Value shown | Required |
  |---|---|---|---|---|
  | 1 | Select Date | date picker (calendar icon) | "April 26, 2026" (format "MMMM D, YYYY"); default likely today (inferred) | yes (implied) |
  | 2 | Select Zone | dropdown | "Banani - Test" | yes (implied) |
  | 3 | Select Route | dropdown | "Apsis Route 2" (placeholder on Set Plan is "Select Route") | yes (implied) |
  - Button "Show Outlet" (green, full width, red-outlined).
- **Callouts (verbatim):** BN: ''My Call'' মেন্যুতে TSO তার মার্কেট ভিজিট প্ল্যান সেট করতে পারে / EN: In the "My Call" menu the TSO can set his market visit plan. BN: My Visit Plan এ TSO তার প্ল্যান অনুযায়ী আউটলেট ভিজিটের তথ্য দেখতে পারবে এবং স্ট্যাটাস pending/completed দেখতে পারবে। / EN: In My Visit Plan the TSO can see outlet visit information according to his plan and see the status pending/completed.
- **Rules:** plan view is by Date + Zone + Route; date restrictions not stated.
- **Messages:** none. **Hardware:** none. **Data read:** visit plan (date, zone, route, outlets), visit status. **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-16 - Visit Plan Outlets (Pending / Completed)
- **Title:** "Visit Plan Outlets"
- **Manual pages:** 16, 17 (repeated)
- **Role(s):** TSO.
- **Purpose:** list planned outlets for the chosen date/zone/route by visit status.
- **Entry points:** S-15 "Show Outlet".
- **Exits:** "<" back; tap a Pending outlet card -> S-17; tapping a Completed outlet - not shown.
- **Controls / content:**
  - Two toggle tabs: "Pending" (selected, first) and "Completed" (both red-boxed).
  - Outlet card with ">" chevron (red-boxed), fields: outlet name (large) "outlet name 11"; "Code: DHK-344-011"; "Owner: customer name 11"; "Contact: [phone]" (10 digits, no leading 0); "Channel: GT Channel"; "Cluster: Apsis Cluster".
- **Callout (verbatim, p.17):** BN: Pending থাকা আউটলেট ভিজিটের সময় SR এর টাস্ক হিসেবে রিটেইলারকে কিছু প্রশ্ন করতে পারেন। / EN: During a visit to a pending outlet, as the SR's task, [the TSO] can ask the retailer some questions.
- **Rules:** status values Pending, Completed; the Visit Query + Assign Task flow is for Pending outlets (stated); visiting is completed from here after planning on Set Plan (stated on p.18).
- **Messages:** none. **Hardware:** none (no GPS/photo shown although being at the outlet is implied). **Data read:** outlet master (name, code, owner, contact, channel, cluster), visit status.
- **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-17 - Visit Query (questions to retailer)
- **Title:** "Visit Query"
- **Manual pages:** 17
- **Role(s):** TSO.
- **Purpose:** during a visit, record the retailer's answers about the SR's behaviour and optionally delegate a task.
- **Entry points:** S-16 tap on a Pending outlet.
- **Exits:** "<" back; "Submit" -> S-18 (when delegating - implied; flow for "No" not stated).
- **Fields (red-boxed):**
  | # | Label (verbatim) | Type | Placeholder / default | Required |
  |---|---|---|---|---|
  | 1 | BN: SR আপনার দোকান নিয়মিত ভিজিট করে? (EN: Does the SR visit your shop regularly?) | text input | "Enter your answer" | not stated |
  | 2 | BN: SR নিয়মিত মেমো প্রিন্ট করে দেয়? (EN: Does the SR regularly print and give the memo?) | text input | "Enter your answer" | not stated |
  | 3 | "Do you want to delegate the task?" | radio group Yes / No | "No" selected | always has a value |
  - Button "Submit" (green, full width, red-outlined); Home FAB half-visible below.
- **Callout (verbatim):** BN: আউটলেট ভিজিটে প্রশ্নের উত্তর নেওয়ার পর Submit এ ক্লিক করতে হবে। / EN: After taking the answers to the questions in the outlet visit, one must click Submit.
- **Rules:** answers are free text (no length limit shown). Question set may be server-configured (2 Bangla + 1 English; not stated). Behaviour when delegate = No is not stated (does Submit skip Assign Task?).
- **Messages:** placeholders only. **Hardware:** none shown. **Data written:** visit query answers per outlet/visit; delegate flag; probably marks outlet visit Completed (inferred). **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-18 - Assign Task
- **Title:** "Assign Task"
- **Manual pages:** 17
- **Role(s):** TSO (assigns a task to the SR of the route).
- **Purpose:** create a follow-up task for the SR about an outlet.
- **Entry points:** S-17 "Submit" (after delegating).
- **Exits:** "<" back; "Assign Task" -> presumably back to list (not shown).
- **Controls / content:**
  - Read-only info card (teal outline): "Outlet Name: outlet name 11", "Cluster: Apsis Cluster", "Route: Apsis Route 2".
  - Dropdown (no visible label; callout calls it "Task Type") value "Irregular Visit"; other options not shown. Required (callout "must select").
  - Date field with calendar icon, value "April 28, 2026" (format "MMMM D, YYYY"; later than plan date April 26; default/restrictions not stated).
  - "Comment" multi-line text area, placeholder "Comment" (optional per callout wording "can write"); no limit shown.
  - Button "Assign Task" (green, full width, red-outlined).
- **Callout (verbatim):** BN: এর পর Assign Task পেজ এ Task Type সিলেক্ট করতে হবে, তারিখ নির্বাচন করে Comment এ রিমার্কস লিখতে পারবে এবং Assing Task এ ক্লিক করতে হবে / EN: After this, on the Assign Task page one must select Task Type, can choose a date and write remarks in Comment, and must click Assign Task. (typo "Assing Task" in original)
- **Rules:** Task Type required; date selectable; Comment optional; task is shown as the SR's task (stated, p.17 callout 1).
- **Messages:** placeholder "Comment"; no success/failure message shown. **Hardware:** none. **Data written:** Task (outlet, route, task type, date, comment, assigned to SR/route, delegated flag). **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-19 - Set Plan (selector)
- **Title:** "Set Plan"; section "My Call".
- **Manual pages:** 18
- **Role(s):** TSO.
- **Purpose:** create a new market visit plan for a future/selected date.
- **Entry points:** drawer My Call > "Set Plan / Set a new visit plan".
- **Exits:** "<" back; "Show Outlet" -> S-20.
- **Fields:**
  | # | Label | Type | Value shown | Required |
  |---|---|---|---|---|
  | 1 | Select Date | date picker | "April 27, 2026" (a day after the other screens' April 26; no restriction stated) | yes (stated) |
  | 2 | Select Zone | dropdown | "Banani - Test" | yes (stated) |
  | 3 | Select Route | dropdown | placeholder "Select Route" (nothing selected) | yes (stated) |
  - Button "Show Outlet" (green, full width).
- **Callout (verbatim):** BN: Set Plan এ TSO তার নতুন প্ল্যান সেট করতে পারবে এর জন্য তারিখ নির্বাচন, জোন নির্বাচন, রুট নির্বাচন করে Show Outlet এ ক্লিক করতে হবে। এরপর আউটলেট সিলেক্ট করে Set Plan এ ক্লিক করতে হবে পরবর্তীতে My Visit Plan থেকে ভিজিট সম্পন্ন করতে হবে। / EN: In Set Plan the TSO can set a new plan; select date, zone, route and click Show Outlet. Then select outlets and click Set Plan; afterwards the visit must be completed from My Visit Plan.
- **Rules:** Date + Zone + Route all required; visit completed later from My Visit Plan.
- **Messages:** placeholder "Select Route". **Hardware:** none. **Data read:** zones, routes. **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-20 - Select Outlets (multi-select) and Set Plan
- **Title:** "Select Outlets"
- **Manual pages:** 18
- **Role(s):** TSO.
- **Purpose:** pick outlets to include in the plan, then save the plan.
- **Entry points:** S-19 "Show Outlet".
- **Exits:** "<" back; "Set Plan" -> saves the plan (destination/confirmation not shown).
- **Controls / content:**
  - Scrollable list of outlet cards: bold outlet name; line "<outlet code> | <address>"; line "<owner> | <phone>"; selection indicator at right (selected = green filled circle with white check + green/teal card outline; unselected = empty ring). Multi-select (inferred from plural and checkboxes).
  - Rows shown: (1) Murad Store - 2689479 | Jobbar Tower Lake Par - Md Murad Hossain | [phone] - SELECTED; (2) Jakir Store - 2689478 | Jobbar Tower Lake Par - Md. Jakir Hossain | [phone]; (3) Shamim Tea Store - 2689477 | Jobbar Tower - Md. Shamim | [phone]; (4) Ebrahim Tea Store - 2689476 | Jobbar Tower - Md. Ebrahim | [phone]; (5) Korim Store - 2689473 | Jobbar Tower - Abdul Korim | [phone]; (6) Shorif Store -2 (SP) - 2672269 | Fojle Rabbi Park - Shorif ... | 0178916371 (cut off).
  - No search box, select-all, or count badge. Button "Set Plan" (green, red-outlined); Home FAB below.
- **Callout:** same as S-19 second bullet.
- **Rules:** plan = Date + Zone + Route + selected outlets. Outlet code here is numeric (e.g. 2689479) versus "DHK-344-011" on S-16.
- **Messages:** none shown (no success toast). **Hardware:** none. **Data read:** outlets of route (name, code, address, owner, phone). **Data written:** visit plan (TSO, date, zone, route, outlet list). **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-21 - Target Status (summary)
- **Title:** "Target Status"
- **Manual pages:** 19
- **Role(s):** TSO.
- **Purpose:** show targets vs achievements for Territory and Zone, monthly and till date, per product category.
- **Entry points:** drawer "Target Status / Check targets".
- **Exits:** "<" back; "Details ->" on a card -> S-22.
- **Controls / content:**
  - Segmented tabs: "Monthly Target" (selected, green) and "Till Date Target".
  - Card "Territory: Apsis": four rows each label + "A/B" + % in green + full green bar: Cigarette "38960/3944" 100%; Bidi "10915/1425" 100%; Lighter "800/526" 100%; Match "900/510" 100%. Button "Details ->" (red-outlined).
  - Card "Zone: Banani - Test": identical four rows and values; "Details ->" (red-outlined).
- **Callouts (verbatim):** BN: Target Status মেন্যুতে সকল রুটের টার্গেট এবং অ্যাচিভমেন্ট এর তথ্য পাওয়া যাবে / EN: In the Target Status menu, information about targets and achievements of all routes can be found. BN: এখানে Monthly Target এবং Till Date Target এর বিস্তারিত দেখা যাবে। / EN: Here the details of Monthly Target and Till Date Target can be seen. BN: Details এ ক্লিক করলে Cigarette/Bidi/Lighter/Match এর Monthly Target ও Achievements এবং Till Date Target ও Achievements দেখা যাবে। / EN: Clicking Details shows the Monthly Target and Achievements and the Till Date Target and Achievements of Cigarette/Bidi/Lighter/Match.
- **Rules:** two views (Monthly, Till Date); scope cards Territory and Zone (callout says "all routes" but no route cards are shown). "A/B" = Achievement/Target (inferred: 38960 vs 3944 would be 987.8%); bars/percentages capped at 100% in summary (inferred). Units not shown.
- **Messages:** none. **Hardware:** none. **Data read:** targets (monthly, till-date) and achievements per Territory and Zone by category (Cigarette, Bidi, Lighter, Match). **Online/offline:** not stated (server aggregates).

---------------------------------------------------------------------
## TSO-S-22 - Target Status: Details (item table)
- **Title:** screen title = territory name "Apsis"
- **Manual pages:** 19
- **Role(s):** TSO.
- **Purpose:** item/SKU-level target, achievement, remaining and % for the selected scope.
- **Entry points:** S-21 "Details ->".
- **Exits:** "<" back.
- **Controls / content:**
  - Tabs "Monthly Target" | "Till Date Target" (Till Date selected, green).
  - Summary block "Territory: Apsis" (Till Date): Cigarette "38960/3420" 100%; Bidi "10915/1236" 100%; Lighter "800/456" 100%; Match "900/442" 100%. (Targets lower than Monthly 3944/1425/526/510; achieved same: consistent with a pro-rated till-date target - inferred.)
  - Table (red-boxed), columns: "Item" | "Target" | "Achievement" (printed wrapped "Achieve ment") | "Remaining" (printed "Remaini ng") | "%".
    | Item | Target | Achievement | Remaining | % |
    |---|---|---|---|---|
    | ESSE Change Mango | 195 | 500 | 0 | 256.41% |
    | Ananda Bidi 25 | 245 | 1875 | 0 | 765.31% |
    | Special Abul Bidi 25 | 118 | 1500 | 0 | 1271.19% |
    | MAX | 246 | 500 | 0 | 203.25% |
    | Maxim Double Burst | 136 | 0 | 136 | 0.00% |
  - Rows may continue below (scroll); not tappable as shown; no category column.
- **Rules:** % = Achievement / Target x 100, NOT capped (inferred from numbers); Remaining = max(Target - Achievement, 0) (inferred).
- **Messages:** none. **Hardware:** none. **Data read:** per-SKU/brand-item targets and achievements. **Online/offline:** not stated.

---------------------------------------------------------------------
## TSO-S-23 - Create New Feedback
- **Title:** "Create New Feedback"; section "My Feedback".
- **Manual pages:** 20
- **Role(s):** TSO.
- **Purpose:** send feedback/suggestion with optional image.
- **Entry points:** drawer "My Feedback / Manage your feedbacks" (the expandable chevron shows no sub-menu in the manual; list of past feedback not shown).
- **Exits:** "<" back; "Save".
- **Fields:**
  | # | Label | Type | Value shown | Required |
  |---|---|---|---|---|
  | 1 | Feedback Category | dropdown | "Suggestion" (other options not shown) | yes (callout: select first) |
  | 2 | Feedback Title | single-line text | "testing" (sample) | not stated ("can write") |
  | 3 | Descriptions (plural as printed) | multi-line text area | "testing" with cursor | not stated |
  | 4 | image attachment | thumbnail preview + button "Browse Gallery" (blue, image icon) opens device gallery | one thumbnail shown; count/size limits not stated | optional |
  | 5 | Save | button (green, floppy-disk icon) | - | - |
  - Home FAB partially visible at the bottom.
- **Callouts (verbatim):** BN: My Feedback মেন্যুতে TSO তার ফিডব্যাক দিতে পারবেন / EN: In the My Feedback menu the TSO can give his feedback. BN: My Feedback এর জন্য TSO প্রথমে Feedback Category সিলেক্ট করবেন এরপর Feedback Title এবং description এ কিছু মন্তব্য লিখতে পারেন। / EN: For My Feedback, the TSO will first select the Feedback Category, then can write some comments in Feedback Title and description.
- **Rules:** category first, then title and description; attachment picked from gallery only (camera not shown).
- **Messages:** none shown (no success/failure). **Hardware / permissions:** storage / media gallery access. **Data written:** Feedback (category, title, description, optional image). **Online/offline:** not stated; image upload implies network.

---------------------------------------------------------------------
## TSO-S-24 - Logout confirmation ("Log Out!")
- **Title:** "Log Out!" (modal)
- **Manual pages:** 21
- **Role(s):** TSO.
- **Purpose:** confirm sign-out.
- **Entry points:** logout icon at top-right of Dashboard header / drawer header.
- **Exits:** "Cancel" (green) -> dismiss; "Log Out" (orange, red-boxed) -> logs out and returns to Login (implied).
- **Message (verbatim, English only):** Title "Log Out!"; body "Your all app data will removed." (sic - missing "be").
- **Callouts (verbatim):** BN: লগআউট এর জন্য এই আইকন এ ক্লিক করতে হবে / EN: To log out, click this icon. BN: Logout এ ক্লিক করে লগআউট সম্পন্ন করতে হবে । / EN: Click Logout to complete the logout.
- **Rules:** logout requires confirmation; logout removes ALL local app data (stated); no check/warning about unsynced data is shown.
- **Hardware:** none. **Data:** local app database/cache and session/token cleared (implied). **Online/offline:** not stated.

---------------------------------------------------------------------

# B. FLOW LIST (end-to-end workflows)

Notation: S-nn = TSO-S-nn. "Alt" = alternate path. Flows not documented anywhere in the TSO manual are listed at the end of this section so nobody assumes they exist.

**TSO-FL-01 Install and first launch** (pp. 2-3)
1. Open device File Manager, Internal storage (S-01).
2. Tap `aron_tso_app_28_02_2026_v1.apk` (S-01).
3. System dialog "Do you want to install this app?" -> tap "Install" (S-01). Alt: "Cancel" aborts install.
4. Dialog "App installed." (S-02) -> "Open" launches the app now (-> S-03); Alt: "Done" closes the dialog, then tap launcher icon "ARON TSO" later (-> S-03).

**TSO-FL-02 Login to Dashboard** (p. 4)
1. Open the app (S-03). 2. Enter Username, enter Password. 3. Tap "Login". 4. Dashboard appears (S-04), greeting "Good day, <name>". Alt (error: wrong password / empty / no network): not documented.

**TSO-FL-03 Review today's territory performance** (pp. 5-6, 9)
1. S-04 top: read Sales tiles (Cigarette, Bidi, Lighter in Box, Match in Dozen) with Total Sales + achievement %. 2. Scroll: By Channel STD, CPR (Target Outlet, Successful Calls, Strike Rate), By Segment Value Contribution, By Brand Call/Memo Ratio, Login & Bikroy Joma Status. 3. Re-enter any time via drawer "Dashboard" (S-06) or Home FAB.

**TSO-FL-04 Chase SRs/AMOs who have not logged in or not uploaded** (pp. 6-7)
1. S-04 "Login & Bikroy Joma Status" card. 2. Open detail (S-05). 3. Tab "Not Logged In" (user, username, route, depot). 4. Switch to tab "Not Uploaded". 5. "<" back to S-04. (Opening gesture and any call/message action not stated.)

**TSO-FL-05 Open the menu and navigate** (pp. 8-9, 12, 15, 16, 19, 20)
1. S-04 hamburger (top-left) -> S-06 drawer. 2. Tap a ">" item to open a screen; tap a "v" group to expand (My Periphery, My Call; My Feedback chevron). 3. Sub-item opens its screen. 4. "<" or Home FAB to return.

**TSO-FL-06 Apply for leave** (pp. 10-11)
1. Drawer "Leave" -> S-07 Leave Applications (list of own applications). 2. "Apply For Leave" -> S-08. 3. Select Leave Type (Casual / Sick / Earn). 4. Tap Date field -> native date picker -> choose date -> confirm. 5. Enter Number of days (default 0). 6. Enter Reason. 7. Tap "Apply". 8. New application presumably appears on S-07 with "DMO approval pending" (not shown). Alt: "<" back without applying; picker cancel (button text unreadable). Alt (error/validation): none shown.

**TSO-FL-07 Final Submit of a zone's day sales (happy path)** (pp. 12-13)
1. Drawer "Final Submit" -> S-09. 2. Select Wing, Division, Territory, House, Zone (button turns green). 3. Tap "Get Sales Data" -> S-11 showing "Sales Date: <date>" and Routes with "FF: <SR name>". 4. Tap "Submit". 5. Dialog "Success" / "Final Submit Done Successfully..." -> "OK" (S-12).

**TSO-FL-08 Final Submit repeat attempt (alt)** (p. 14)
1. Steps 1-3 as TSO-FL-07 for a zone already submitted today. 2. On "Get Sales Data" the alert "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" appears (S-10). 3. "OK" -> stays on S-09. No second submit possible.

**TSO-FL-09 View live SR locations** (p. 15)
1. Drawer My Periphery (expand) -> "My Team" -> S-13. 2. Choose Zone. 3. Map shows SR markers per route. 4. Recentre / my-location controls on the map. 5. "<" back.

**TSO-FL-10 Find retailers around the TSO** (p. 15)
1. Drawer My Periphery -> "Retailer" -> S-14. 2. Choose Zone. 3. Choose Radius (50 / 100 / 300). 4. Map shows outlets in the zone within the radius.

**TSO-FL-11 View visit plan and audit a pending outlet, no delegation** (pp. 16-17)
1. Drawer My Call (expand) -> "My Visit Plan" -> S-15. 2. Select Date, Zone, Route -> "Show Outlet". 3. S-16 tab "Pending" (default). 4. Tap an outlet card (">") -> S-17 Visit Query. 5. Type answers to the two questions ("SR আপনার দোকান নিয়মিত ভিজিট করে?", "SR নিয়মিত মেমো প্রিন্ট করে দেয়?"). 6. "Do you want to delegate the task?" = No (default). 7. Tap "Submit". Outcome after Submit with No is not documented.

**TSO-FL-12 Visit query with task delegation to the SR** (p. 17)
1. Steps 1-5 of TSO-FL-11. 2. Set "Do you want to delegate the task?" = Yes. 3. "Submit" -> S-18 Assign Task (shows Outlet Name, Cluster, Route read-only). 4. Select Task Type (e.g. "Irregular Visit"). 5. Select date. 6. Optionally write Comment. 7. Tap "Assign Task". Outcome after Assign Task not documented.

**TSO-FL-13 Look at completed visits** (p. 16)
1. S-15 -> "Show Outlet" -> S-16. 2. Tap tab "Completed". Tapping a completed card: not documented.

**TSO-FL-14 Set a new visit plan** (p. 18)
1. Drawer My Call -> "Set Plan" -> S-19. 2. Select Date, Zone, Route. 3. "Show Outlet" -> S-20. 4. Tick one or more outlets (green check). 5. Tap "Set Plan". 6. Later complete the visits from "My Visit Plan" (TSO-FL-11/12). No confirmation shown.

**TSO-FL-15 Check targets and achievements** (p. 19)
1. Drawer "Target Status" -> S-21 (default tab Monthly Target). 2. Switch to "Till Date Target" if needed. 3. Read Territory card and Zone card (Cigarette, Bidi, Lighter, Match). 4. "Details ->" -> S-22 (item table: Target, Achievement, Remaining, %). 5. Switch tab inside details. 6. "<" back.

**TSO-FL-16 Submit feedback** (p. 20)
1. Drawer "My Feedback" -> S-23 (Create New Feedback). 2. Select Feedback Category (e.g. Suggestion). 3. Enter Feedback Title. 4. Enter Descriptions. 5. Optionally "Browse Gallery" to attach an image (thumbnail shown). 6. Tap "Save". No success message shown.

**TSO-FL-17 Logout** (p. 21)
1. Tap logout icon at top-right (S-04 / S-06). 2. Dialog "Log Out!" / "Your all app data will removed." (S-24). 3. "Log Out" -> logs out, local app data removed (returns to S-03 - implied). Alt: "Cancel" dismisses and stays in the app.

**Flows NOT documented in the TSO manual** (do not assume they exist from this source; confirm with the other manuals/screenshots): edit or cancel a submitted leave; leave balance view; zero sale / force sale / print memo / reprint (these are SR flows, not TSO); change password / forgot password; offline mode, manual sync or refresh; undo or reopen a Final Submit; list/history of past feedback; editing or deleting a visit plan; viewing a completed visit's answers; push notifications.

---------------------------------------------------------------------

# C. RULES REGISTER

"Basis": S = stated in the manual (callout/screen text), I = inferred from screenshots or numbers (not printed).

| ID | Rule | Exact wording (verbatim where printed) | Page | Screens | Basis |
|---|---|---|---|---|---|
| TSO-R-01 | Each TSO logs in with an individually issued username and password. | BN: নির্দিষ্ট TSO এর জন্য, তাদের নির্দিষ্ট ইউজারনেম এবং পাসওয়ার্ড দিয়ে, “লগইন” বাটনে ক্লিক করুন। | 4 | S-03 | S |
| TSO-R-02 | Login success leads to the Dashboard. | BN: “লগইন” বাটনে ক্লিক করার পর Dashboard পেজ আসবে | 4 | S-03, S-04 | S |
| TSO-R-03 | Username and Password both needed to log in (no validation text shown). | (not printed) | 4 | S-03 | I |
| TSO-R-04 | App is installed by side-loading the APK from device storage; file name pattern aron_<role>_app_<dd_mm_yyyy>_v<n>.apk. | `aron_tso_app_28_02_2026_v1.apk` | 2 | S-01 | I |
| TSO-R-05 | Dashboard data is scoped to the TSO's territory and to "that day". | BN: টেরিটোরি অনুযায়ী ওইদিনের ... | 5, 6, 7 | S-04, S-05 | S |
| TSO-R-06 | Four product tiles with fixed units: Cigarette, Bidi, Lighter "in Box", Match "in Dozen"; figure with one decimal; caption "Total Sales". | "Sales (Cigarette)", "Sales (Bidi)", "Sales (Lighter in Box)", "Sales (Match in Dozen)" | 5 | S-04 | S |
| TSO-R-07 | Achievement % ring per tile = sales vs target (formula not printed). | BN: অ্যাচিভমেন্ট % | 5 | S-04 | I (formula) |
| TSO-R-08 | Strike Rate = Successful Calls / Target Outlet (2/44 = 4.5%). | "Target Outlet", "Successful Calls", "Strike Rate" | 5 | S-04 | I |
| TSO-R-09 | Login Status % = Total Login / Target Route (1/4 = 25%). | "Login Status", "Target Route", "Total Login" | 6 | S-04 | I |
| TSO-R-10 | Bikroy Joma Status % = Total Bikroy Joma / Login Count (0/1 = 0%); denominator not confirmed. | "Bikroy Joma Status", "Login Count", "Total Bikroy Joma" | 6 | S-04 | I |
| TSO-R-11 | "Not Logged In" and "Not Uploaded" lists are scoped to territory and day; row = user (SR or AMO) + route + depot. | BN: ...ওইদিনের Not Logged In / Not Uploaded এর বিস্তারিত তথ্য | 7 | S-05 | S (scope), I (row unit) |
| TSO-R-12 | Segment chart legend: Value (orange) vs Volume (teal); axis 0-83% in 6 ticks. | "By Segment Value Contribution" | 5, 6 | S-04 | S (legend) |
| TSO-R-13 | Drawer menu items in fixed order; ">" opens a screen, "v" expands a group (My Periphery, My Call, My Feedback). | Dashboard, Leave, Final Submit, My Periphery, My Call, Target Status, My Feedback | 8 | S-06 | S |
| TSO-R-14 | Leave types are exactly Casual, Sick, Earn. | BN: Leave Type ( Casual, Sick, Earn ) | 11 | S-08 | S |
| TSO-R-15 | A leave application needs Leave Type, Leave Date, Number of Days and Reason before Apply. | BN: Leave Type ( Casual, Sick, Earn ), Leave Date, Number of Days, Reason টাইপ করতে হবে এরপর Apply এ ক্লিক করতে হবে। | 11 | S-08 | S |
| TSO-R-16 | Leave form has a single Date field; Number of days defaults to 0; typed vs derived not stated. | "Select Date"; "0" | 11 | S-08 | S |
| TSO-R-17 | Leave goes through DMO approval; new/pending applications show the badge. | "DMO approval pending" | 10 | S-07 | S (badge), I (initial status) |
| TSO-R-18 | Leave list unit caption is "Day" for 1 and "Days" for 2; dates ISO YYYY-MM-DD "from to to". | "2026-03-04 to 2026-03-04" ... "Day"/"Days" | 10 | S-07 | S |
| TSO-R-19 | Leave list shows from-date, to-date, reason, day count and status; leave type and balance are not shown. | (absence) | 10 | S-07 | I |
| TSO-R-20 | Final Submit needs Wing, Division, Territory, House and Zone all selected before "Get Sales Data". | BN: ফাইনাল সাবমিট এর জন্য এইখানে Wings, Division, Terrotory, House, Zone সিলেক্ট করে ''Get Sales Data'' তে ক্লিক করতে হবে। | 12 | S-09 | S |
| TSO-R-21 | "Get Sales Data" is grey (inactive) until all five dropdowns are filled, then green. | (colour only) | 12, 14 | S-09 | I |
| TSO-R-22 | Org hierarchy order Wing > Division > Territory > House > Zone (cascade implied). | labels order | 12 | S-09 | I |
| TSO-R-23 | The Final Submit screen shows information of all zones and allows final submit. | BN: এই মেন্যুতে সকল জোনের "Final Submit" এর তথ্য দেখা যাবে এবং ফাইনাল সাবমিট দেওয়া যাবে। | 12 | S-09 | S |
| TSO-R-24 | After Zone selection, final submit is done by tapping "Submit". | BN: Zone সিলেক্ট এর পর ফাইনাল সাবমিট এর জন্য ''Submit'' এ ক্লিক করতে হবে। | 13 | S-11 | S |
| TSO-R-25 | Final Submit is for one Sales Date (shown read-only "Sales Date: YYYY-MM-DD") and covers all listed routes of the zone with one Submit. | "Sales Date:" | 13 | S-11 | I |
| TSO-R-26 | Each route shows its assigned SR as "FF: <name>"; routes without SR show "FF: SR Not Set" and are still listed (blocking not stated). | "FF: SR Not Set" | 13 | S-11 | S (display), I (no block) |
| TSO-R-27 | Final Submit completion is confirmed by a success dialog. | "Success" / "Final Submit Done Successfully..." | 13 | S-12 | S |
| TSO-R-28 | A zone already final-submitted for today cannot be submitted again; an alert is shown (triggered at Get Sales Data). | BN: Zone এর ফাইনাল সাবমিট হয়ে গেলে পরবর্তীতে সাবমিট করতে চাইলে অ্যালার্ট দিবে | 14 | S-09, S-10 | S |
| TSO-R-29 | My Team shows the live location of all SRs according to route (zone selected). | BN: My Team এ ক্লিক করলে রুট অনুযায়ী সকল এসআর এর লাইভ লোকেশন দেখা যাবে | 15 | S-13 | S |
| TSO-R-30 | Retailer map shows outlets by Zone and by Radius. | BN: Retailer এ ক্লিক করলে Zone অনুযায়ী রেডিয়াস অনুযায়ী রিটেইলার এর লোকেশন দেখা যাবে | 15 | S-14 | S |
| TSO-R-31 | Radius options are fixed: 50, 100, 300 (unit not shown; metres presumed). | "50", "100", "300" | 15 | S-14 | S (values), I (unit) |
| TSO-R-32 | My Visit Plan needs Date + Zone + Route, then "Show Outlet"; outlets listed with status Pending / Completed. | BN: ...আউটলেট ভিজিটের তথ্য দেখতে পারবে এবং স্ট্যাটাস pending/completed দেখতে পারবে। | 16 | S-15, S-16 | S |
| TSO-R-33 | Visit Query is done for outlets in Pending status, as the SR's task. | BN: Pending থাকা আউটলেট ভিজিটের সময় SR এর টাস্ক হিসেবে রিটেইলারকে কিছু প্রশ্ন করতে পারেন। | 17 | S-16, S-17 | S |
| TSO-R-34 | After taking answers one must click Submit. | BN: আউটলেট ভিজিটে প্রশ্নের উত্তর নেওয়ার পর Submit এ ক্লিক করতে হবে। | 17 | S-17 | S |
| TSO-R-35 | Delegation question "Do you want to delegate the task?" is Yes/No, default No; Yes leads to Assign Task (inferred); No path not stated. | "Do you want to delegate the task?" | 17 | S-17, S-18 | S (control), I (routing) |
| TSO-R-36 | On Assign Task: Task Type must be selected; a date can be chosen; remarks in Comment are optional; then "Assign Task". | BN: Assign Task পেজ এ Task Type সিলেক্ট করতে হবে, তারিখ নির্বাচন করে Comment এ রিমার্কস লিখতে পারবে এবং Assing Task এ ক্লিক করতে হবে | 17 | S-18 | S |
| TSO-R-37 | Assign Task shows outlet name, cluster and route read-only; task due date may be later than the plan date (Apr 28 vs Apr 26). | "Outlet Name:", "Cluster:", "Route:" | 17 | S-18 | I |
| TSO-R-38 | Set Plan: select date, zone, route -> "Show Outlet"; then select outlets -> "Set Plan"; the visit is then completed from My Visit Plan. | BN: ...তারিখ নির্বাচন, জোন নির্বাচন, রুট নির্বাচন করে Show Outlet এ ক্লিক করতে হবে। এরপর আউটলেট সিলেক্ট করে Set Plan এ ক্লিক করতে হবে পরবর্তীতে My Visit Plan থেকে ভিজিট সম্পন্ন করতে হবে। | 18 | S-19, S-20, S-15 | S |
| TSO-R-39 | Outlet selection allows more than one outlet (checkbox-style circle indicators). | (plural "আউটলেট"; check marks) | 18 | S-20 | I |
| TSO-R-40 | Target Status offers two views: Monthly Target and Till Date Target; for Cigarette, Bidi, Lighter, Match; scope cards Territory and Zone; "Details" drill-down. | BN: Details এ ক্লিক করলে Cigarette/Bidi/Lighter/Match এর Monthly Target ও Achievements এবং Till Date Target ও Achievements দেখা যাবে। | 19 | S-21, S-22 | S |
| TSO-R-41 | Summary bars/percent capped at 100% even when achievement far exceeds target (38960 vs 3944 shows 100%). | "38960/3944 100%" | 19 | S-21, S-22 | I |
| TSO-R-42 | Detail table: % = Achievement / Target x 100 uncapped; Remaining = max(Target - Achievement, 0). | "256.41%", "1271.19%", Remaining 0 / 136 | 19 | S-22 | I |
| TSO-R-43 | Till Date target is a lower (pro-rated) portion of the Monthly target; achievements equal in both. | 3420 vs 3944; 1236 vs 1425; 456 vs 526; 442 vs 510 | 19 | S-21, S-22 | I |
| TSO-R-44 | In Target Status the number pair is Achievement/Target (first = achieved). | "38960/3944" | 19 | S-21 | I |
| TSO-R-45 | Feedback: choose Feedback Category first, then Title and Descriptions; optional image from gallery. | BN: প্রথমে Feedback Category সিলেক্ট করবেন এরপর Feedback Title এবং description এ কিছু মন্তব্য লিখতে পারেন। | 20 | S-23 | S |
| TSO-R-46 | Logout needs a confirmation dialog; confirming removes ALL local app data; no pending-upload check shown. | "Your all app data will removed." | 21 | S-24 | S |
| TSO-R-47 | Date display formats: leave list and Sales Date use YYYY-MM-DD; Visit Plan/Set Plan/Assign Task date fields use "MMMM D, YYYY". | "2026-04-26"; "April 26, 2026" | 10, 13, 16-18 | S-07, S-11, S-15, S-18, S-19 | S |
| TSO-R-48 | Back button "<" on every sub-screen; Home FAB at bottom centre on many screens. | (UI convention) | 7, 10-21 | all | S |
| TSO-R-49 | Phone numbers are shown as 10 digits without the leading 0 (one card shows 0178916371). | "Contact: [phone]" | 16, 18 | S-16, S-20 | S |
| TSO-R-50 | Maps are Google-Maps-style, need location/Internet; live positions come from SR devices. | (map screens) | 15 | S-13, S-14 | I |

---------------------------------------------------------------------

# D. MESSAGES REGISTER

Verbatim as printed. Languages: English dialogs are English only; the already-submitted alert is Bangla.

## D1. Dialogs, alerts, statuses and system prompts

| ID | Verbatim text | Buttons | English meaning | Screen | Page |
|---|---|---|---|---|---|
| TSO-M-01 | "Do you want to install this app?" | "Cancel", "Install" | Android install confirmation for ARON TSO | S-01 | 2 |
| TSO-M-02 | "App installed." | "Done", "Open" | Install completed | S-02 | 3 |
| TSO-M-03 | "Good day, Apsis" (format "Good day, <display name>") | - | Greeting in the Dashboard header | S-04 | 4, 5, 8, 9, 21 |
| TSO-M-04 | "DMO approval pending" (orange badge) | - | Leave awaiting DMO approval | S-07 | 10, 11 |
| TSO-M-05 | Title "Success"; body "Final Submit Done Successfully..." | "OK" | Final submit completed | S-12 | 13 |
| TSO-M-06 | BN: আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন! (no title) | "OK" | You have already submitted 'FINAL SUBMIT' for today! | S-10 | 14 |
| TSO-M-07 | Title "Log Out!"; body "Your all app data will removed." | "Cancel", "Log Out" | Confirm logout; all local app data will be removed (grammar error in original) | S-24 | 21 |

## D2. Placeholders, static labels and data strings the user reads

| ID | Verbatim text | Meaning | Screen | Page |
|---|---|---|---|---|
| TSO-M-08 | "Username", "Password" (labels and placeholders), button "Login" | Login prompts | S-03 | 4 |
| TSO-M-09 | "Select leave Type", "Select Date", "Reason", button "Apply" (title "Leave Apply"), button "Apply For Leave" (title "Leave Applications") | Leave form/list prompts | S-07, S-08 | 10, 11 |
| TSO-M-10 | "Reason: <text>", "Day" / "Days" | Leave card text and unit captions | S-07 | 10 |
| TSO-M-11 | "Select Wing", "Select Division", "Select Territory", "Select House", "Select Zone", button "Get Sales Data" | Final Submit prompts | S-09 | 12, 14 |
| TSO-M-12 | "Sales Date:", "Routes", "FF: <SR name>", "FF: SR Not Set", button "Submit" | Final Submit routes screen strings | S-11 | 13 |
| TSO-M-13 | "Select Date", "Select Zone", "Select Route", button "Show Outlet" | My Visit Plan / Set Plan prompts | S-15, S-19 | 16, 18 |
| TSO-M-14 | "Pending", "Completed", "Code:", "Owner:", "Contact:", "Channel:", "Cluster:" | Visit Plan Outlets card labels | S-16 | 16, 17 |
| TSO-M-15 | BN: SR আপনার দোকান নিয়মিত ভিজিট করে? / BN: SR নিয়মিত মেমো প্রিন্ট করে দেয়? / "Do you want to delegate the task?" / options "Yes", "No" / placeholder "Enter your answer" / button "Submit" | Visit Query questions (EN: Does the SR visit your shop regularly? / Does the SR regularly print and give the memo? / Do you want to delegate the task?) | S-17 | 17 |
| TSO-M-16 | "Outlet Name:", "Cluster:", "Route:", default type text "Irregular Visit", placeholder "Comment", button "Assign Task" | Assign Task strings | S-18 | 17 |
| TSO-M-17 | Selected-outlet row text pattern "<name>" / "<code> | <address>" / "<owner> | <phone>"; button "Set Plan" | Select Outlets strings | S-20 | 18 |
| TSO-M-18 | "Monthly Target", "Till Date Target", "Territory: <name>", "Zone: <name>", "Details ->", item rows "<label> <A>/<B> <n>%" | Target Status strings | S-21 | 19 |
| TSO-M-19 | "Item", "Target", "Achievement" (printed "Achieve ment"), "Remaining" (printed "Remaini ng"), "%" | Detail table headers | S-22 | 19 |
| TSO-M-20 | "Feedback Category", "Feedback Title", "Descriptions", "Browse Gallery", "Save", default category "Suggestion" | Feedback form strings | S-23 | 20 |
| TSO-M-21 | Dashboard card titles: "By Channel STD", "CPR", "By Segment Value Contribution", "By Brand Call/Memo Ratio", "Login & Bikroy Joma Status", "Login Status", "Bikroy Joma Status", captions "Target Outlet", "Successful Calls", "Strike Rate", "Target Route", "Total Login", "Login Count", "Total Bikroy Joma", "Value", "Volume", "GT Channel", "Total Sales" | Dashboard labels | S-04 | 5, 6 |
| TSO-M-22 | "Not Logged In", "Not Uploaded", "User Name:", "Route:", "Dep Name:" | Detail list labels | S-05 | 7 |
| TSO-M-23 | Drawer titles/subtitles: Dashboard/View Dashboard; Leave/Manage your leaves; Final Submit/Submit Sales Data; My Periphery/Check your team and outlet; My Team/Periphery; Retailer/Periphery; My Call/Create and manage plans; My Visit Plan/View your visit plan; Set Plan/Set a new visit plan; Target Status/Check targets; My Feedback/Manage your feedbacks | Menu labels | S-06 | 8, 12, 15, 16, 18-20 |
| TSO-M-24 | Map screen titles "My Teams", "Outlets"; labels "Zone", "Radius"; radius options "50", "100", "300" | Map screen strings | S-13, S-14 | 15 |

Absent from the manual (no text printed): login errors, required-field messages, leave apply success/failure, visit query/assign task/set plan/feedback success or failure, empty-list texts, network errors, sync status, permission prompts, loading texts.

---------------------------------------------------------------------

# E. DATA ENTITIES AND FIELDS REGISTER

R = read, W = written by the screen. (inf) = inferred.

| ID | Entity | Fields seen | Screens | Access |
|---|---|---|---|---|
| TSO-E-01 | User / credential | username, password, display name ("Apsis"), role (TSO / SR / AMO), session/token (inf) | S-03, S-04, S-05, S-06, S-24 | R/W (login), cleared on logout |
| TSO-E-02 | Org hierarchy | Wing, Division, Territory, House, Zone (names); TSO scope across them | S-09, S-13, S-14, S-15, S-19, S-21 | R |
| TSO-E-03 | Territory | name ("Apsis Territory"/"Apsis"), daily sales per product, targets, achievements | S-04, S-21, S-22 | R |
| TSO-E-04 | Zone | name ("Banani - Test"), final-submit status per date, targets | S-09, S-13, S-14, S-15, S-19, S-21 | R |
| TSO-E-05 | Route | route name (+ type "AMO"/"Daily" appended), assigned SR ("FF: ..."), depot ("Dep Name") | S-05, S-11, S-15, S-18, S-19 | R |
| TSO-E-06 | Depot | "Dep Name" (e.g. Banani - Test) | S-05 | R |
| TSO-E-07 | SR / AMO user (field force) | display name, username, route, depot; daily login event; daily Bikroy Joma upload event | S-05, S-11 | R |
| TSO-E-08 | Daily login event | per user-route, per day | S-04, S-05 | R |
| TSO-E-09 | Bikroy Joma / sales upload event | per user-route, per day ("Total Bikroy Joma") | S-04, S-05 | R |
| TSO-E-10 | Sales (daily, by product category) | Cigarette, Bidi, Lighter (in Box), Match (in Dozen): Total Sales, achievement % | S-04 | R |
| TSO-E-11 | Sales by channel | channel name (GT Channel), share % | S-04 | R |
| TSO-E-12 | Segment contribution | segment (e.g. Medium), Value %, Volume % | S-04 | R |
| TSO-E-13 | Brand call/memo ratio | brand (e.g. Maxim - Platinum Series), % | S-04 | R |
| TSO-E-14 | CPR / calls | Target Outlet count, Successful Calls, Strike Rate % | S-04 | R |
| TSO-E-15 | Leave application | leave type (Casual/Sick/Earn), date (from/to), number of days, reason, status ("DMO approval pending"), approver (DMO), applicant | S-07 (R), S-08 (W) | R/W |
| TSO-E-16 | Final Submit record | zone, sales date, TSO, done flag | S-09 (R status), S-10 (R), S-11 (W), S-12 | R/W |
| TSO-E-17 | Sales date | date (YYYY-MM-DD) | S-11 | R |
| TSO-E-18 | SR live location | per SR, per route/zone; lat/long (inf) | S-13 | R (written by SR app) |
| TSO-E-19 | Outlet master | outlet name, code (DHK-344-011 style and numeric style), owner name, contact/phone, channel, cluster, address, lat/long | S-14, S-16, S-18, S-20 | R |
| TSO-E-20 | Visit plan | TSO, date, zone, route, planned outlets, status Pending/Completed | S-15, S-16 (R), S-19, S-20 (W) | R/W |
| TSO-E-21 | Visit query answer | per outlet/visit: answer 1 (SR visits regularly), answer 2 (SR prints memo), delegate flag | S-17 | W |
| TSO-E-22 | Task (delegated) | outlet, cluster, route, task type (e.g. Irregular Visit), date, comment, assigned to SR | S-18 | W |
| TSO-E-23 | Target | monthly and till-date targets per category and per item/SKU, per Territory and Zone | S-21, S-22 | R |
| TSO-E-24 | Achievement | per category and per item/SKU; remaining; % | S-21, S-22 | R |
| TSO-E-25 | Item/SKU/brand | e.g. ESSE Change Mango, Ananda Bidi 25, Special Abul Bidi 25, MAX, Maxim Double Burst | S-22 | R |
| TSO-E-26 | Feedback | category (e.g. Suggestion), title, description, optional image | S-23 | W |
| TSO-E-27 | Local app data / session | whole local database and cache | S-24 | cleared |
| TSO-E-28 | Device location | TSO's own position (blue dot), used for map centre/radius (inf) | S-13, S-14 | R |
| TSO-E-29 | App package | APK name/version, size | S-01 | R |

---------------------------------------------------------------------

# F. UNCLEAR / CONFLICTING ITEMS

| ID | Pages | Issue |
|---|---|---|
| TSO-U-01 | 3 | Callout 2 says "ARON SR অ্যাপ্লিকেশনটি ... install" (ARON SR) while screen and icon say "ARON TSO": copy-paste from the SR manual. |
| TSO-U-02 | 2 | First folder row in the file manager is cut off (probably "Movies" per p.3); the two AMO APK names are truncated. |
| TSO-U-03 | 4 | Login: password masking/show-hide, any error handling, app version, language toggle, "forgot password" not shown. Online/offline login behaviour not stated. |
| TSO-U-04 | 5 | On-screen titles "By Channel STD ▸" and "CPR" differ from callout names "By Channel Successful Call" and "Live CPR". "STD" and "GT Channel" not expanded; the "▸" on "By Channel STD" suggests drill-down, not explained. |
| TSO-U-05 | 5, 6 | "By Segment Value Contribution" and "By Brand Call/Memo Ratio" charts are empty in every screenshot (chart type unknown; only x-labels "Medium", "Maxim - Platinum Series"); y-axis ticks 83/67/50/33/17/0% are odd (1/6 steps). |
| TSO-U-06 | 5 | Cigarette and Bidi units not stated; target (denominator) behind "92.5%" not shown (160.0 implies target about 173). |
| TSO-U-07 | 6 | Left screenshot red box is on "By Segment Value Contribution" but the callout names "By Brand Call/Memo Ratio". |
| TSO-U-08 | 6 | Login & Bikroy Joma formulas only inferred (25% = 1/4, 0% = 0/1); "Login Count" vs "Total Login" not defined; denominator of Bikroy Joma % unknown; "Bikroy Joma" not expanded in the manual (বিক্রয় জমা = sales deposit/submission). |
| TSO-U-09 | 13 | Right callout under the Success dialog describes the drawer menu, not the dialog: copy-paste error. |
| TSO-U-10 | 6-7 | Gesture that opens "Login & Bikroy Joma Status" detail from the Dashboard card is not stated. |
| TSO-U-11 | 7 | Card says Target Route = 4, Total Login = 1, but "Not Logged In" shows 5 rows and "Not Uploaded" 6+; same username on several routes; AMOs appear in the TSO's list with SRs. "Not Uploaded" and "Dep Name" not defined. Default tab not stated. |
| TSO-U-12 | 8 | Callout says the menu button is at the top RIGHT, but the hamburger is at the top LEFT (top-right is logout). |
| TSO-U-13 | 8, 20 | "My Feedback" has a "v" expand chevron but no sub-items are shown anywhere (only Create New Feedback is documented; there must be a list/history, not shown). "Final Submit", "Leave", "Target Status" use ">". |
| TSO-U-14 | 10, 11 | Leave: card "2026-02-25 to 2026-02-25" shows "2 Days" (same from/to but 2 days); form has a single Date plus Number of days, so range creation is unexplained; reason text "only one day can apply!" hints at a possible one-day restriction (unconfirmed); "DMO" not expanded; leave type not shown on cards; only "DMO approval pending" visible (no approved/rejected); no leave balance. |
| TSO-U-15 | 11 | Date-picker overlay tiny: bottom buttons unreadable (probably Cancel/OK); header "Sun, Apr 26". Whether past dates/weekends/before-today are allowed: not stated. |
| TSO-U-16 | 11 | No validation, success, overlap or balance messages for Apply Leave. Whether Number of days is typed or auto-derived: not stated. |
| TSO-U-17 | 12 | Dropdown option lists and cascade behaviour not visible; the disabled state of "Get Sales Data" inferred from grey vs green only; callout typos "Wings", "Terrotory". |
| TSO-U-18 | 13 | "FF:" not defined (assumed Field Force = SR); whether route cards are tappable; whether "FF: SR Not Set" routes block Submit; duplicate route name "AMO-Apsis RouteAMO" shown twice; 6th card cut off; no confirm prompt between Submit and Success; what Submit locks is not stated. |
| TSO-U-19 | 14 | Callout says alert appears when one wants to "submit again", but the highlighted trigger in the screenshot is "Get Sales Data" (check at Get Sales Data, before the routes screen). Alert has no title; part of the form values hidden behind the dialog. |
| TSO-U-20 | 15 | Menu item "My Team" vs screen title "My Teams"; radius unit, default radius, radius centre point, marker tap behaviour, "live" refresh interval not stated; the Outlets screenshot shows no outlet markers. Whether Zone default is first zone: not stated. |
| TSO-U-21 | 16 | Date default/restrictions not stated; callout 1 on p.16 says "set" a plan but screen explained is My Visit Plan (view); numeric vs "DHK-344-011" outlet code; contact shown without leading 0. |
| TSO-U-22 | 17 | Behaviour when delegate = No unknown; Task Type dropdown has no label and only "Irregular Visit" is visible; Assign Task date default/restriction unclear (April 28 vs plan April 26); no success/failure messages; Visit Query questions: 2 free-text Bangla + 1 Yes/No English, configurability unknown; callout typo "Assing Task". |
| TSO-U-23 | 18 | Select Outlets single vs multi select not stated (inferred multi); last card owner name/phone cut off ("0178916371"); numeric outlet codes (2689479) vs "DHK-344-011" format on p.16-17; plan date April 27 differs from April 26; no confirmation after Set Plan; no search/select-all. |
| TSO-U-24 | 19 | "A/B" pair (38960/3944) not explained (achievement/target presumed); summary % capped at 100; units not shown; callout says "all routes" but only Territory and Zone cards visible; detail table has no category column; Till Date targets differ from Monthly (3420 vs 3944 etc.). |
| TSO-U-25 | 20 | Feedback Category options other than "Suggestion" unknown; maximum image count/size unknown; no success/failure or validation message; label "Descriptions" plural as printed. |
| TSO-U-26 | 21 | Dashboard tile list cut off (Match tile not visible on p.21); dialog text "Your all app data will removed." ungrammatical; **no warning about unsynced/pending data before wiping all local data**. CONFLICT with CLAUDE.md constraints (offline-first, idempotent sync): the rebuild must not silently discard unsynced records on logout; log this as a decision. |
| TSO-U-27 | 1-22 | The whole manual never states offline behaviour, sync, retry, refresh/pull-to-refresh, "internet required", caching, permission prompts (location, storage), or error handling. Online dependence is only implied (Final Submit duplicate check and success; live SR map; target aggregates). |
| TSO-U-28 | 9 | Page title still "ড্যাশবোর্ড" but only covers the menu entry; no new info. |
| TSO-U-29 | 12, 19 | Scope wording: Final Submit callout says "সকল জোনের" (all zones) yet the form requires picking ONE zone; Target Status callout says targets of "all routes" but cards are Territory and Zone. |
| TSO-U-30 | 1-22 | Not covered at all by the TSO manual (check other sources): change/forgot password, profile, notifications, language switch, app version/update flow, leave edit/cancel, history of Visit Query/Assign Task/feedback, Final Submit undo, tap behaviour on Completed outlets and map markers. |

# Summary counts
Screens: 24 (TSO-S-01..24). Flows: 17 (TSO-FL-01..17). Rules: 50 (TSO-R-01..50). Messages: 7 dialogs/alerts/statuses (TSO-M-01..07) + 17 grouped string registers (TSO-M-08..24) = 24 entries. Entities: 29. Unclear items: 30.
