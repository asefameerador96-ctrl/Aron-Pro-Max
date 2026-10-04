# ARON SR App (Apsis build) - consolidated inventory from the "SR (Sales Representative) App User Manual"

Source: `/root/.claude/uploads/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/0965d940-SR_App_User_Manual.pdf` (80 pages, slide format). Built from the seven chunk transcripts `sr-p01-12.md`, `sr-p13-24.md`, `sr-p25-36.md`, `sr-p37-48.md`, `sr-p49-60.md`, `sr-p61-72.md`, `sr-p73-80.md`.

**COVERAGE CHECK: all seven expected chunk ranges (1-12, 13-24, 25-36, 37-48, 49-60, 61-72, 73-80) were present and read in full. No range is missing.** Re-checked against the PDF directly: p73 (device/server table row 4) and p37 (QC entry).

**Totals:** 60 screens (SR-S-01..60; SR-S-60 is an undocumented tile stub), 33 flows (SR-F-01..33), 150 rules (SR-R-001..150), 99 messages (SR-M-001..099), 39 entities (SR-E-01..39), 51 unclear items (F-GEN, F-01..F-50).

Conventions
- "p." = PDF page number. Bangla text is verbatim as printed (typos kept). `[EN: ...]` = translation. Bangla digits (০১২৩৪৫৬৭৮৯ = 0-9) are given as printed with Western digits in brackets where useful.
- Item tags: **(stated)** = written in a callout/note/on-screen text; **(observed)** = visible in a screenshot only; **(inferred)** = deduced by the transcribers or by me, NOT stated by the manual. Treat (inferred) items as questions to confirm, not requirements.
- Every manual page 2-79 is a slide with two Samsung (J-series style, low-end) phone mock-ups, speech-bubble callouts and red boxes marking the control being explained. p1 is the cover ("ARON / SR USER MANUAL"), p80 is "ধন্যবাদ" (Thank you) with no UI. Neither contains a screen.
- Role: every screen below is an **SR** screen unless marked. The manual never mentions a role other than SR on-device, except (a) the TSO web portal (OTP, gift assignment), (b) AMO as the assigner of tasks.
- Screens that are Android system UI (installer, permission dialogs, Bluetooth settings) are inventoried because the flows depend on them, and are tagged **[SYSTEM]**.
- The manual shows NO error text for any validation failure (wrong password, wrong OTP, quantity over stock, etc.), no loading/offline banners, and no sync-pending indicators anywhere except the "বিক্রয় জমা" screen. Absence of these in the manual is itself a finding (see Section F, item F-GEN).

---------------------------------------------------------------------
# SECTION A - SCREEN INVENTORY (in the order a user meets them)
---------------------------------------------------------------------

Order used: install -> login/permissions -> OTP -> update -> dashboard -> then each dashboard tile in manual order (Target detail, Attendance, printer setup, Stock, Sale and everything inside a sale, Memo, Astha, Outlet, Loyalty/Redemption, Photo Capture, Sales Deposit, Tutorial, Task Delegation, Settings, Logout). Dashboard tile "সারসংক্ষেপ" (Summary) has no manual page (stub SR-S-60).

## SR-S-01 APK install (Android file manager + package installer) [SYSTEM]
- **Pages:** 2, 3
- **Title:** "অ্যাপ্লিকেশান ইনস্টল করার প্রক্রিয়া" [EN: Application installation process]
- **Purpose:** Sideload the ARON SR APK. The app is NOT installed from Play Store; it is installed from a file.
- **Entry:** Device file manager (Samsung "My Files" style, "Internal storage"). **Exit:** Installer "Open" or launcher icon -> SR-S-02.
- **Role:** SR.
- **Controls / content:**
  - APK file shown: `aron_sr_app_25_11_2025_v1.apk`, 25 Nov 2:18 pm, 80.40 MB (tap to install).
  - Installer dialog: app name "ARON SR"; buttons "Cancel", "Install".
  - Completion dialog: "App installed."; buttons "Done", "Open".
  - Launcher icon labelled "ARON SR" (stylised "A" in a circle).
- **Rules:** none beyond the sequence. Install via file manager is the documented route (stated).
- **Messages (verbatim):** callout "ARON SR অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার জন্য প্রথমে ফাইল ম্যানেজার ওপেন করুন।"; "এরপর ARON SR অ্যাপ্লিকেশন file এর উপর ক্লিক করুন।"; "এরপর "Install" অপশন এর উপর ক্লিক করুন।"; "অ্যাপ্লিকেশন install সম্পন্ন হয়ে যাবার পর উপরোক্ত অ্যালার্ট দেখতে পারবেন। এখান থেকে "Open" অপশন এর মাধ্যমে অ্যাপ্লিকেশনটি ওপেন করতে পারবেন। এবং "Done" অপশন এর মাধ্যমে অ্যাপ্লিকেশন install সম্পন্ন করতে পারবেন।"; "ARON SR অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার পর "ARON SR" নামে একটি icon দেখতে পারবেন।"; "Login করার জন্য icon টির উপর ক্লিক করুন।"; system text "Do you want to install this app?", "App installed."
- **Hardware / permissions:** storage (APK file); "Install unknown apps" for the file-manager source is implied for first install (explicitly shown only for updates, SR-S-07).
- **Data:** none (app package; APK size 80.40 MB; installed size 93.11 MB shown on p7).
- **Offline/online:** local install; no network involved.

## SR-S-02 Login
- **Pages:** 4 (also referenced p5)
- **Title:** "লগইন এবং অনুমতি প্রদান" [EN: Login and granting permission]; screen has no title bar (logo screen).
- **Purpose:** Authenticate the SR with a personal username and password.
- **Entry:** Launcher icon / installer "Open" / after Logout (SR-S-59, inferred). **Exit:** location permission dialog (SR-S-03) then OTP screen (SR-S-04) for a new device/new version, else dashboard (SR-S-10).
- **Role:** SR.
- **Controls:**
  | Control | Type | Required | Notes |
  |---|---|---|---|
  | "ইউজারনেম" [EN: Username] | text input (placeholder "ইউজারনেম") | required (implied) | no length/format limit stated |
  | "পাসওয়ার্ড" [EN: Password] | password input, masked ("******") | required (implied) | no show/hide toggle, no "forgot password", no "remember me" visible; no complexity/lockout rule stated |
  | "লগইন" [EN: Login] | button (red-purple gradient) | - | submits credentials |
  - Footer text (small print): "SR App" / "(version - 1.0.25)" / "App Developed by Apsis Solutions". Circular app logo ("A") centred; top half red-purple gradient.
- **Rules:** each SR logs in with their own username and password (stated): "নির্দিষ্ট SR এর জন্য, তাদের নির্দিষ্ট ইউজারনেম এবং পাসওয়ার্ড দিয়ে, "লগইন" বাটনে ক্লিক করুন।"
- **Messages:** no error message for wrong credentials is shown in the manual.
- **Hardware / permissions:** location permission requested immediately after Login tap (SR-S-03).
- **Data read/written:** SR user credentials; (server-side) session. Login requires the server (OTP/2FA step follows; implied online).
- **Offline/online:** not stated. Release note "Login time 2FA verification" (p7) implies a server round trip at login on a new device.

## SR-S-03 Location permission prompt [SYSTEM]
- **Pages:** 4
- **Purpose:** Obtain mandatory location permission.
- **Entry:** after tapping "লগইন". **Exit:** SR-S-04 or SR-S-10.
- **Content:** "Allow ARON SR to access this device's location?"; map previews "Precise" (default selected) and "Approximate"; buttons "While using the app" (instructed), "Only this time", "Don't allow".
- **Rules (stated):** "অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যই ইউজারকে লোকেশন পারমিশন দিতে হবে।" Select "While using the app".
- **Messages (verbatim):** callout ""লগইন" বাটনে ক্লিক করার পর লোকেশন পারমিশন এর একটি অ্যালার্ট দেখতে পারবেন। "While using the app" অপশনটি নির্বাচন করুন। অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যই ইউজারকে লোকেশন পারমিশন দিতে হবে।"
- **Not stated:** what the app does on "Don't allow"/"Only this time".

## SR-S-04 OTP device verification (SR app)
- **Pages:** 5, 6
- **Title:** "OTP যাচাইকরণ" [EN: OTP verification]
- **Purpose:** Bind the SR login to a device. 4-digit OTP supplied by the TSO.
- **Entry:** after Login on a new device or after installing a new app version. **Exit:** on success -> update check / data update (SR-S-06..09) -> dashboard (SR-S-10). Back arrow "<" in header.
- **Role:** SR (OTP is read by TSO in SR-S-05).
- **Controls:**
  | Control | Type | Required | Notes |
  |---|---|---|---|
  | Heading "Enter OTP" (red) + hint "Enter the 4-digit OTP provided by your TSO." | text | - | |
  | OTP input | four separate single-digit boxes, numeric, empty default | required | 4 digits |
  | "Verify" | green button | - | submits OTP |
  - Footer red message: "Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process."
- **Rules (stated):** "এই OTP একটি ডিভাইসে প্রথমবার লগইন করার সময় দিতে হবে।" OTP page appears after SR-ID login ("SR আইডি দিয়ে লগইন করার পরে একটি OTP যাচাইকরণ পেজ আসবে") and, per the on-screen message, also after installing a new version.
- **Messages (verbatim):** the footer message above; "Enter OTP"; "Enter the 4-digit OTP provided by your TSO."; callouts "SR আইডি দিয়ে লগইন করার পরে একটি OTP যাচাইকরণ পেজ আসবে। এই OTP একটি ডিভাইসে প্রথমবার লগইন করার সময় দিতে হবে। OTP দেখার জন্য TSO পোর্টালে লগইন করুন।" and "উক্ত লিস্ট থেকে সঠিক রুট এর জন্য সঠিক OTP নিয়ে Enter OTP ফিল্ডে ইনপুট করুন। এরপর "Verify" অপশনে ক্লিক করুন।"
- **Not stated:** OTP validity/expiry, single use, retry limit, wrong-OTP message.
- **Data:** SR device OTP (per SR/device), device binding. **Offline/online:** online (server verification; implied).

## SR-S-05 TSO web portal - "SR Device OTP" / "SR OTP Panel" (external to the SR app; TSO role)
- **Pages:** 5, 6
- **Purpose:** The TSO looks up the SR's OTP to read out to the SR.
- **Role:** TSO (web portal, mobile view; demo user "tso-apsis").
- **Entry:** TSO portal side menu (items: Dashboard, Retailer, QC, Sales Plan, Credentials, Products, Route Planning, Data Entry, Reports, Target, Supervisory Module, Outlet, "SR Device OTP" - the last item - and a "Logout" button; "x" closes the menu). **Exit:** none.
- **Controls:** header: hamburger, title "SR OTP Panel", profile avatar. Filter card with five multi-selects, each default "All Selected (1)" with clear "x": "Wing", "Division", "Territory", "Distribution House", "Zone". Button "View". Result table columns: "Sr No.", "Field Force ID", "Field Force Name", "Username", "Zone ID", "Zone", "Create Time", "OTP".
  - Sample rows: 106825 | 9892 | SR - Testing Banani 2 | sr334002 | 6334 | Banani - Test | 2026-01-20 | 6818; 106823 | 6066 | SR - Testing Banani | sr334001 | 6334 | Banani - Test | 2026-01-14 | 3560.
- **Rules (stated):** callouts "TSO পোর্টালে লগইন করার পর মেনু লিস্ট এর সর্বশেষ মেনু "SR Device OTP" তে ক্লিক করুন।"; "এরপর যে টেরিটোরি বা জোন এর OTP প্রয়োজন সেটি নির্বাচন করে "View" অপশনে ক্লিক করুন। এরপর আপনার উক্ত টেরিটোরি বা জোন এর সকল রুট এর OTP লিস্ট দেখতে পারবেন।" (callout says "routes" but rows are per SR/username.) Scope hierarchy Wing > Division > Territory > Distribution House > Zone.
- **Data read:** SR users in scope, OTP value, creation time. **Offline/online:** online web portal.

## SR-S-06 Update Available (in-app update page)
- **Pages:** 7, 8
- **Title:** "Update Available"
- **Purpose:** Download and install the new APK in-app.
- **Entry:** after login/OTP when a newer version exists. **Exit:** Android installer (SR-S-07) -> relaunch -> SR-S-08/09.
- **Content:** top bar: back arrow "<", "Network Status" + green dot + "অনলাইন" [EN: Online]; "NEW UPDATE" banner; cloud-download icon; "Update Available"; "Version 1.0.25 available"; release note "New promotion modality., Login time 2FA verification., Sync file update without deleting."; red button "Download App & Install"; during download a red progress bar "Downloading 24%", then "Download completed"; red warning with (i) "Please do not close the app while downloading".
- **Rules (stated):** "ডাউনলোড সম্পন্ন হওয়া পর্যন্ত অপেক্ষা করুন। এবং ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন।" ; Tapping "Download App & Install" automatically takes the user to Android "Install unknown apps".
- **Messages (verbatim):** all text above; callouts "এরপর অ্যাপ্লিকেশন এর ভার্শন আপডেট এর একটি পেজ দেখতে পারবেন। অ্যাপ্লিকেশন এর ভার্শন আপডেট করতে "Download App & Install" অপশনটি নির্বাচন করুন।"; "এরপর আপডেটেড ভার্শনটি ডাউনলোড হতে থাকবে। ডাউনলোড সম্পন্ন হওয়া পর্যন্ত অপেক্ষা করুন। এবং ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন।"; "ডাউনলোড সম্পন্ন হয়ে যাবার পর এই পেজটি দেখতে পাবেন। এখান থেকে "Update" অপশনটিতে ক্লিক করুন।"
- **Hardware:** network (download), storage. **Offline/online:** ONLINE only (network indicator shown).
- **Data:** app version manifest (version number, release notes, APK URL).

## SR-S-07 Install unknown apps + update confirm [SYSTEM]
- **Pages:** 7, 8, 9
- **Content:** Samsung "Install unknown apps" page; warning "Installing apps from this source may put your phone and data at risk."; app list with toggles (ARON SR 93.11 MB OFF -> must be turned ON; Bluetooth 0.97 MB off; Chrome 106 MB on; Drive 68.08 MB off "Deep sleeping"; Galaxy Store 74.80 MB off; Gmail 220 MB off; My Files 7.27 MB on; Quick Share 1.99 MB off; Quick Share Agent 6.16 MB off). Installer dialog "Do you want to update this app?" [Cancel | Update]; after update "App installed." [Done | Open].
- **Rules (stated):** "...Automatically ইউজারকে Install Unknow apps পেজে নিয়ে যাবে। এই পেজ থেকে ARON SR এর বাটনটিতে ক্লিক করে অন করে দিন।" -> enable "Allow from this source" for ARON SR. After update "আপডেট সম্পন্ন হয়ে যাবার পর "Open" অপশনে ক্লিক করুন।"
- **Permission:** REQUEST_INSTALL_PACKAGES (Install unknown apps). **Offline:** local after download.

## SR-S-08 Forced "update required" splash
- **Pages:** 9
- **Content:** illustration (phone with megaphone and gear); text "কিছু নতুন আপডেট পাওয়া গেছে, এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" [EN: Some new updates have been found; to continue using this app, you must update!]; button "আপডেট" [EN: Update]; footer "Current version SR App 1.0.1".
- **Rules:** update is mandatory (stated in the message); no skip/cancel control on screen (observed). Callout: "এরপর আপডেট অপশনে ক্লিক করুন।"
- **Entry:** first launch after APK update. **Exit:** SR-S-09.

## SR-S-09 App data update in progress ("অ্যাপ আপডেট হচ্ছে")
- **Pages:** 10 (left phone)
- **Content:** phone-with-gears illustration; "অ্যাপ আপডেট হচ্ছে"; blue progress bar with "প্রসেসিং(১৪/৮৭)" [EN: Processing (14/87)]; red tip "টিপস: অ্যাপটি বন্ধ করবেন না" [EN: Tips: Do not close the app].
- **Rules:** do not close the app (stated). Counter is current/total (87 units; unit undefined). Callout: "আপডেট বাটনে ক্লিক করার পর অ্যাপ আপডেট এর স্ট্যাটাস দেখতে পাবেন।"
- **Entry:** after tapping "আপডেট". **Exit:** SR-S-10.
- **Data:** local data re-processing/migration. Matches release-note "Sync file update without deleting". **Offline:** not stated.

## SR-S-10 Dashboard (home)
- **Pages:** shown in full on 10, 16, 40, 44, 46, 48, 53, 61, 64, 68, 71, 74, 75, 77; repeated on pages 11, 12, 18, 20, 22 (entry highlights).
- **Purpose:** Home. Header identity, 12 module tiles (10 on some accounts), target card, six KPI tiles.
- **Entry:** after login/update. **Exit:** any tile; gear icon -> Settings (SR-S-58).
- **Header:** avatar; line 1 "SR - Testing Banani (sr334001)" or "SR-16858 (sr16858)" (SR name + username in brackets); line 2 route and date: "Apsis RouteDaily, 2026-04-22", "Savar Bazar(Sat, Mon, Wed), 2025-11-26", "Savar BazarDaily, 2025-11-30" (route name, optional visit days in brackets, ISO date); gear icon.
- **Tiles (4 columns x 3 rows, icon + Bangla label), in order:**
  | Row | Tiles |
  |---|---|
  | 1 | "অ্যাটেনডেন্স" [Attendance] -> SR-S-12; "স্টক" [Stock] -> SR-S-17; "বিক্রয়" [Sales] -> SR-S-18; "মেমো" [Memo] -> SR-S-39 |
  | 2 | "সারসংক্ষেপ" [Summary] -> SR-S-60 (undocumented); "বিক্রয় জমা" [Sales deposit] -> SR-S-55; "আউটলেট" [Outlet] -> SR-S-46; "টিউটোরিয়াল" [Tutorial] -> SR-S-56 |
  | 3 | "টাস্ক ডেলিগেশন" [Task delegation] -> SR-S-57 (red numeric badge e.g. "২" = tasks assigned); "আস্থা" [Astha] -> SR-S-43; "লয়ালটি পয়েন্ট"/"লয়্যালটি পয়েন্ট" [Loyalty point] -> SR-S-50; "Photo Capture" (English) -> SR-S-52 |
  - Tiles "লয়ালটি পয়েন্ট" and "Photo Capture" are ABSENT on the SR-16858 account (10 tiles, p44, p71, p74, p77) but present on sr334001 (12 tiles) -> per-user/role/build feature flag (observed; see F-10).
- **Target card** (red-purple gradient): "টার্গেট ও অ্যাচিভমেন্ট (SKU List)"; sub-label "এস টি ডি" [STD]; green progress bar with % at right (sr334001: "২৭১৯%" full bar; SR-16858: "০%"); link "বিস্তারিত" [Details] -> SR-S-11.
- **KPI tiles** (value over label): "০/৩৭" "আউটলেট ভ্রমণ" [Outlet visit, visited/total]; "০%" "স্ট্রাইক রেট"; "১,৫০০" "ইস্যু" [Issue]; "১,৫০০" "বর্তমান স্টক" [Current stock]; "৩৭" "নন ভিজিট" [Non-visit] (p10 transcribed once as "মন ভিজিট"; p16+ read "নন ভিজিট"); "০" "বিক্রি নেই" [No sale]. Other accounts: 0/81 + 81 (p44, 71, 74, 77), 0/80 + 80 (p75).
- **Below KPI tiles:** a red bar/card is cut off at the bottom of every screenshot (scrolling dashboard; content unknown - F-11).
- **Rules:** none stated. Observed/implied formulas: outlet visit = visited/total route outlets; non-visit = total - visited (37-0=37) (inferred); strike rate formula not stated.
- **Numerals:** Bangla digits with Indian-style comma grouping ("১,৫০০"); Latin digits appear on some screens (p24, p35 input, p49 year).
- **Messages:** none. **Offline:** not stated; counters imply local data.
- **Data read:** SR profile (name, username), daily route (name, visit days, date), outlet count, visits, strike rate, issued stock, current stock, SKU target/achievement, task count badge.

## SR-S-11 Target & Achievement (SKU list)
- **Pages:** 11
- **Title:** "টার্গেট ও অ্যাচিভমেন্ট" [EN: Target and Achievement]
- **Entry:** dashboard "বিস্তারিত". **Exit:** back "<".
- **Controls:** back chevron; tab chip "এস টি ডি" [STD] (only one visible); scrollable SKU cards. Card: pack thumbnail + product/brand name; grid labels row 1 "টার্গেট" [Target], "অ্যাচিভ" [Achieved], "বাকি" [Remaining], "অর্জন" [Attainment %, red]; row 2 English abbreviations "ADS", "TADS", "PADS", "RADS" (undefined).
- **Sample cards:** Maxim T500 A0 R500 0% ADS0 TADS36 PADS0 RADS45; Avon T900 A0 R900 0% ADS0 TADS68 PADS0 RADS82; ARIS T900 ... TADS68 RADS82; Marise T500 ... TADS36 RADS45; 5th card cut off.
- **Rules (inferred):** Remaining = Target - Achieved; Attainment % = Achieved/Target.
- **Messages:** callouts "SKU অনুযায়ী টার্গেট ও অ্যাচিভমেন্ট এর ডাটা দেখার জন্য বিস্তারিত অপশনে ক্লিক করুন।"; "বিস্তারিত অপশনে ক্লিক করার পর SKU অনুযায়ী টার্গেট ও অ্যাচিভমেন্ট এর ডাটা দেখতে পারবেন।"
- **Data read:** SKU/brand master (name, image), SR SKU target, achievement. **Offline:** not stated.

## SR-S-12 Attendance (check-in / check-out)
- **Pages:** 12, 13, 14, 15
- **Title:** "অ্যাটেনডেন্স" [EN: Attendance]
- **Purpose:** Daily check-in before work and check-out at end of day, with current location shown.
- **Entry:** dashboard tile. **Exit:** back "<" (to dashboard).
- **Content (all states):** header back chevron + calendar/clock icon + title; user/route card "SR-16858 (sr16858)" and "Savar Bazar(Sat, Mon, Wed), 2025-11-26"; location card with map-pin icon, reverse-geocoded address (e.g. "Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh") and a red circular "Refresh" icon; status message; two buttons/status boxes.
- **States:**
  | State | Status message (verbatim) | Left control | Right control |
  |---|---|---|---|
  | A. Not checked in (p12, 13) | "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।" | green "চেক ইন" [Check In] ACTIVE | grey "চেক আউট" [Check Out] disabled |
  | C. Checked in, before 5 PM (p14) | "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" | dashed green box, green tick "চেক ইন সম্পন্ন হয়েছে" [Check-in completed] | dashed orange box, warning icon "চেক আউট ৫টার পরে সক্রিয় হবে" [Check-out will be activated after 5 o'clock] |
  | D. Check-out available (p14) | same as C | "চেক ইন সম্পন্ন হয়েছে" | solid red gradient button "চেক আউট" ACTIVE |
  | F. Day complete (p15) | "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" | "চেক ইন সম্পন্ন হয়েছে" | red tick box "চেক আউট সম্পন্ন হয়েছে" [Check-out completed] (not a button) |
- **Rules:** (stated) "কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন"; Yellow note p14: "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।" After check-in the check-in button is disabled and status changes ("চেক ইন সম্পন্ন হয়ে যাবার পর চেক ইন বাটনটি Disable হয়ে যাবে এবং চেক ইন স্ট্যাটাস পরিবর্তন হয়ে যাবে।"); same for check-out ("চেক আউট সম্পন্ন হয়ে যাবার পর চেক আউট বাটনটি Disable হয়ে যাবে এবং চেক আউট স্ট্যাটাস পরিবর্তন হয়ে যাবে।"). Callouts: "আপডেট সম্পন্ন হওয়ার পরে ARON SR অ্যাপ্লিকেশন এর ড্যাশবোর্ড থেকে অ্যাটেনডেন্স এর ডাটা প্রদান করতে অ্যাটেনডেন্স অপশনে ক্লিক করুন।"; "এরপর ইউজার তার বর্তমান লোকেশন দেখতে পারবেন। বর্তমান লোকেশন পেতে কোনো সমস্যার সম্মুখীন হইলে Refresh বাটনে ক্লিক করুন।"; "চেক ইন সম্পন্ন করার জন্য “চেক ইন” অপশনে ক্লিক করুন।"; "চেক আউট সম্পন্ন করার জন্য “চেক আউট” অপশনে ক্লিক করুন।"
- Address shown is the CURRENT location (re-read), not the stored check-in location (p14 shows a different address at check-out time) (observed).
- **Hardware:** location + reverse geocoding (address; online dependency not stated); device clock.
- **Data written:** attendance record (SR, date, check-in time, check-out time; location implied). **Offline:** not stated (address lookup likely online).

## SR-S-13 Check-in confirmation sheet
- **Pages:** 13
- **Content:** bottom sheet over SR-S-12: drag handle; green title "চেক ইন করা হচ্ছে" [Checking in]; time chip with clock icon "04:47 PM" (12-hour, read-only, current time); large green half-circle button with enter icon labelled "চাপ দিয়ে ধরে রাখুন" [Press and hold].
- **Rules (stated):** "এরপর চেক ইন এর সময় চেক করুন। তারপর সবুজ বাটনটি চাপ দিয়ে ধরে রাখুন।" -> hold (not tap) confirms. Hold duration / early-release behaviour not stated.
- **Result:** state C in SR-S-12. No success toast; state change is the confirmation.

## SR-S-14 Check-out confirmation sheet
- **Pages:** 15
- **Content:** drag handle; red title "চেক আউট করা হচ্ছে" [Checking out]; time chip "05:00 PM"; red half-circle hold button "চাপ দিয়ে ধরে রাখুন".
- **Rules (stated):** "এরপর চেক আউট এর সময় চেক করুন। তারপর লাল বাটনটি চাপ দিয়ে ধরে রাখুন।" Same press-and-hold pattern. **Result:** state F.

## SR-S-15 Printer pairing in Android Bluetooth settings [SYSTEM]
- **Pages:** 16, 17, 18
- **Title:** "মোবাইল Bluetooth এর সাথে প্রিন্টার Pair করার প্রক্রিয়া" [EN: Process of pairing the printer with mobile Bluetooth]
- **Steps/screens:** (1) Bluetooth page Off, helper "Turn on Bluetooth to connect to nearby devices."; (2) scanning: action "Stop", "Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices.", "Available devices" list shows printer "RPP02N"; (3) dialog "Bluetooth pairing request", "Enter PIN to pair with RPP02N (Try 0000 or 1234).", field "PIN" (4 masked chars), "Cancel" / "Pair"; (4) "Paired devices" lists "RPP02N" (gear icon); other nearby devices incidental ("28:D0:EA:1F:F0:DA" with "Device name will appear when this device is connected.", "QCY H3-APP", "oraimo SpaceBuds Neo+ BLE").
- **Rules (stated):** user must turn phone Bluetooth ON and turn the printer's Power ON first: "Mobile Printer connect করার জন্য সর্বপ্রথম ইউজার এর ডিভাইস এর Setting এ যেয়ে Bluetooth অন করে নিতে হবে। এবং Mobile Printer এর Power অন করে নিতে হবে।" PIN to enter is 0000: "এরপর Pin Section এ (0000) এর পিন নাম্বারটি প্রদান করুন এবং Pair বাটনটিতে ক্লিক করুন।" Callouts also: "এরপর নির্ধারিত প্রিন্টার Bluetooth এর Available Devices Section এ show করবে। নির্ধারিত প্রিন্টার এর নামে এর উপর ক্লিক করুন।"; "প্রিন্টার এর সাথে Bluetooth সংযোগ স্থাপনা সম্পূর্ণ হইলে Paired Devices Section এ নির্ধারিত প্রিন্টার এর নাম দেখতে পারবেন।"
- Pairing happens in Android settings, not inside the app (observed).
- **Hardware:** Bluetooth; RPP02N thermal printer.

## SR-S-16 Bluetooth "Nearby devices" permission + printer connect (on Stock screen) [SYSTEM + app]
- **Pages:** 18, 19, 20
- **Title:** "ARON SR অ্যাপ্লিকেশান এর সাথে প্রিন্টার Connect করার প্রক্রিয়া"
- **Flow:** dashboard "স্টক" -> Stock screen (SR-S-17) with red slashed printer icon (disconnected) -> Android dialog "Allow ARON SR to find, connect to and determine the relative position of nearby devices?" [Allow | Don't allow] -> tap printer icon -> green banner "প্রিন্টার কানেক্ট করা হয়েছে" [EN: Printer has been connected] and printer icon turns green.
- **Rules (stated):** "ইউজারকে অবশ্যই এই Permission টি Allow অপশনে ক্লিক করে Allow করতে হবে। অন্যথাই প্রিন্টার Connect হবে না।" ; "এবার ইউজারকে প্রিন্টার আইকন এর উপর ক্লিক করতে হবে।" ; "প্রিন্টার এর কানেকশান সম্পূর্ণ হয়ে যাবার পর ইউজার প্রিন্টার কানেক্ট করা হয়েছে একটি Alert দেখতে পারবেন। এবং প্রিন্টার আইকনটি সবুজ বর্ণের হয়ে যাবে।"; callout "এরপর ARON SR অ্যাপ্লিকেশান এর সাথে প্রিন্টার এর সংযোগ স্থাপন করার জন্য ড্যাশবোর্ড থেকে স্টক অপশনে ক্লিক করুন।" (printer connection is made from the Stock screen's printer icon)
- **Printer icon states:** red slashed printer = disconnected; green printer = connected. The same icon appears on Review (SR-S-33) and Memo (SR-S-39) screens.
- **Not stated:** behaviour on "Don't allow"; whether Sale/Memo screens also let the user connect.

## SR-S-17 Stock (daily stock-taking)
- **Pages:** 19, 20, 21
- **Title:** "স্টক" [EN: Stock]; process title p21 "স্টক নেয়ার প্রক্রিয়া" [EN: Stock-taking process]
- **Purpose:** Record the stock issued to the SR per SKU for the day, check category totals, save, print stock memo.
- **Entry:** dashboard "স্টক". **Exit:** back "<".
- **Controls:**
  | Control | Type | Notes |
  |---|---|---|
  | Header printer icon | status/tap button | red slashed = disconnected, green = connected (tap connects) |
  | Column headers "এসকেইউ", "ইস্যু", "স্টক" [SKU, Issue, Stock] | - | |
  | SKU row: pack image + purple round badge (bottom-right), purple SKU code (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, then AVON pack...), red "-" button, underlined numeric input (default "০"), purple "+" button, read-only "স্টক" value | stepper + editable number | step size not stated; min shown 0 |
  | Bottom purple panel: "ক্যাটাগরি", "মোট ইস্যু", "স্টক"; rows "সিগারেট", "বিড়ি", "লাইটার", "ম্যাচ" | derived totals | |
  | "সংরক্ষণ" [Save] (red gradient, floppy icon) | button | writes |
  | "প্রিন্ট" [Print] (light, printer icon) | button | prints stock memo |
  - Sample before save: MaxR-10S issue ৬,৫০০ stock ০ badge ৬৫০; MaxR-20S ০; MaxB-10S ০; MaxB-20S issue ৬,০০০ stock ০ badge ৩০০; totals সিগারেট ১২,৫০০ / বিড়ি ২,৪০০ / লাইটার ৫৫০ / ম্যাচ ৪০০, stock all ০. After save: stock column = issue (MaxR-10S ৬,৫০০, MaxB-20S ৬,০০০), category stock = issue totals. Issue inputs remain filled.
- **Rules:** (stated) "এরপর এসকেইউ অনুযায়ী স্টক নিন এবং ক্যাটাগরি অনুযায়ী স্টক চেক করুন। স্টক নেওয়া শেষে “সংরক্ষণ” অপশনে ক্লিক করে ডাটা সংরক্ষণ করুন।"; "এরপর “প্রিন্ট” অপশনে ক্লিক করে স্টক এর মেমো প্রিন্ট করতে পারবেন।"; daily: "প্রতিদিনের মত স্টক নেওয়ার জন্য “স্টক” বাটনে ক্লিক করুন।" (observed) category issue total = sum of SKU issues; (inferred) badge = packets (issue / pack size).
- **Messages:** "প্রিন্টার কানেক্ট করা হয়েছে" (green banner). No validation/save-confirm/success message shown.
- **Hardware:** Bluetooth printer. **Data:** SKU master (code, image, pack size, category), per-SKU issue qty (write), stock (read/updated), category totals, stock memo (print). **Offline:** not stated.

## SR-S-18 Sale - retailer (outlet) selection
- **Pages:** 22, 40
- **Title:** "বিক্রয়" [EN: Sale]
- **Purpose:** Choose the outlet to sell to.
- **Entry:** dashboard "বিক্রয়". **Exit:** SR-S-19 once an outlet is chosen (screen continues in place).
- **Controls:** back chevron + icon + title; dropdown (searchable/selectable, single) placeholder "রিটেইলার নির্বাচন করুন" [Select retailer]; alphabet filter strip (dark bar, chips: "All" selected + A B C D E F G H I J / K M N P R S T V Z on p22 & p40 - first letters of outlet names present); empty-state text "বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন" [Select a retailer to continue the sale].
- **Rules (stated):** callout "দোকানের নাম সিলেক্ট করার জন্য ড্রপডাউন অপশন টাচ করুন। এরপর নামের তালিকা থেকে নির্ধারিত দোকান নির্বাচন করুন। এরপর “এগিয়ে যান” অপশনে ক্লিক করুন।" and "বিক্রয়কার্য্য শুরু করতে “বিক্রয়” অপশনে ক্লিক করুন।" A retailer MUST be selected. (inferred) letter chips filter the dropdown by first letter. Note: no "এগিয়ে যান" button is visible on this screen (F-12).
- **Data read:** today's route outlets (name, code). **Offline:** list expected on device (inferred).

## SR-S-19 Sale - outlet selected (geofence status, Sale History, Points, Force Sale, Refresh)
- **Pages:** 23, 24, 25, 27, 31, 41, 42 (left phones)
- **Content:** dropdown shows "Moin Store (DHK-344-005-[phone]-Apsis Cluster)" (name + parenthesised "code-phone-cluster"); alphabet strip now "All, A, B, M, R, S, a, b, j, o, s, t" (mixed case); two tab buttons in English: "Sale History" (blue, history icon) and "Points" (orange, medal icon); out-of-range block: red warning triangle + blue text "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।"; buttons "ফোর্স সেল" [Force Sale] (purple-red gradient) and "রিফ্রেশ" [Refresh] (dark grey).
- **Rules:** (stated) "এরপর ইউজার নির্দিষ্ট আউটলেট থেকে, নির্ধারিত রেঞ্জ এর বাহিরে থাকলে “ফোর্স সেল” অপশনটি দেখতে পাবেন। এই অপশনটিতে ক্লিক করুন। লোকেশান এর তথ্য আপডেট করার জন্য “রিফ্রেশ” অপশনে ক্লিক করুন"; Note: "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের লোকেশন অন রাখতে হবে।" (location must be ON; shown p25, p41). (observed) Sale History and Points remain available out of range. The numeric radius is NOT stated.
- **Exits:** Sale History -> SR-S-20; Points -> SR-S-21; Force Sale -> SR-S-22; in-range path -> start-call prompt SR-S-25 (the in-range path is never shown explicitly, F-13).
- **Data:** outlet coordinates, SR current location (geofence).

## SR-S-20 Sale Data (previous sales of the selected outlet)
- **Pages:** 23
- **Title:** "সেল ডাটা" [EN: Sale Data]; opened by "Sale History" (callout names it "পূর্বের সেল ডাটা দেখুন"; also a green button of that name on SR-S-32).
- **Controls:** back chevron; date field (calendar icon, "November 26, 2025", opens date picker - inferred); heading "2025-11-26"; table "এসকেইউ" | "পরিমাণ" | "মূল্য" with rows MaxR-10S ৫২০ ৪,১৬০.০০; MaxR-20S ১,০০০ ৮,০০০.০০; MaxB-10S ৫০০ ৪,০০০.০০; AB-12s ৬০০ ৪৫০.০০; ABS ১,২৫০ ১,০০০.০০; Aster ১০০ ১,২৫০.০০; FB ৬০০ ১,৮০০.০০; footer "মোট" ৪,৫৭০.০০ ২০,২৬০.০০ (row values sum to 20,660.00 - F-14).
- **Rules (stated):** user can view any date ("ইউজার যেকোন তারিখ এর সেল দেখতে পারবেন তারিখ নির্বাচন করার মাধ্যমে"); Note "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে।" -> ONLINE ONLY.
- **Data read:** outlet sales history by date (SKU, qty, value).

## SR-S-21 Points (Diamond League points of the selected outlet)
- **Pages:** 24
- **Title:** "পয়েন্ট" [EN: Points] (p24 page title typo "Diamong League Point")
- **Controls:** back chevron; dashed info box "Manage retailer gift requisition and redemption." with button "রিডিম্পশন" [Redemption] (gift icon); points card: "Diamond League (April)" with orange pill "110 Pts"; "Expiring Points:" "110 Pts"; "Expiry Date:" "2026-05-07". Latin digits, English labels.
- **Rules (stated):** "বিগত দিন পর্যন্ত আউটলেট এর অর্জিত Diamond League Point দেখতে Point বাটনে ক্লিক করুন।" - points up to previous days. "রিডিম্পশন" -> Redemption page (SR-S-51).
- **Data read:** league (name, month), points balance, expiring points, expiry date per outlet. **Offline:** not stated.

## SR-S-22 Force Sale reason
- **Pages:** 25, 41
- **Title:** (inline state of SR-S-19) "ফোর্স সেল প্রক্রিয়া" [EN: Force Sale process]
- **Content:** warning icon + "আপনার ফোর্স সেলের কারণ কী?" [What is the reason for your force sale?]; two purple buttons "ইন্টারনেট সমস্যা" [Internet problem] and "লোকেশন চেঞ্জ" [Location change] (mutually exclusive, exactly one, required).
- **Rules (stated):** "“ফোর্স সেল” বাটনে ক্লিক করার সাথেই কারণ জানার জন্য নিম্নের অপশন গুলা আসবে, ১। ইন্টারনেট সমস্যা, ২। লোকেশন চেঞ্জ এই ২টি অপশন থেকে প্রয়োজন অনুযায়ী একটি সিলেক্ট করবেন, এরপর আউটলেটের ছবি উঠানোর জন্য “Camera” ওপেন হয়ে যাবে". Exit -> camera SR-S-24.
- **Data written:** force-sale record (reason code, outlet photo) (implied).

## SR-S-23 Camera and Audio permission prompts [SYSTEM]
- **Pages:** 26
- **Title:** "Camera এবং Audio Permission এর প্রক্রিয়া"
- **Content:** "Allow ARON SR to take pictures and record video?" and "Allow ARON SR to record audio?"; each with "While using the app" (instructed), "Only this time", "Don't allow".
- **Rules (stated):** "Camera ওপেন হবার পর Camera Permission এর Alert দেখতে পারবেন। অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।"; "এরপর Audion Permission Alert দেখতে পারবেন । অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।" Purpose of audio not stated; "pictures and record video" wording shows camera supports video.

## SR-S-24 Camera capture (outlet photo)
- **Pages:** 27, 31, 42 (also camera screens reused in SR-S-47 p54, SR-S-49 p59)
- **Content:** live viewfinder (shopfront); controls: flip-camera icon (left), large white round shutter (centre), "X" close (right). No flash/zoom.
- **Rules (stated):** "এরপর নির্দিষ্ট আউটলেটের ছবি উঠাবেন, এবং ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" -> photo capture also updates the outlet's location.
- **Data written:** outlet photo; outlet lat/long update. **Exit:** SR-S-25.

## SR-S-25 Start-call prompt and sale footer
- **Pages:** 27, 31, 42, 43
- **Content:** info icon + "আপনি কি কল শুরু করতে চান?" [Do you want to start the call?]; buttons "হ্যাঁ" [Yes] (red-purple) and "না" [No] (dark grey). Purple footer: cart icon with total "০.০০"; yellow "Collect DRP Discount" (English; two lines) and rose "এগিয়ে যান→" [Proceed].
- **Rules (stated):** "এরপর কল শুরু করার জন্য অপশন আসবে “হ্যাঁ” বাটনে ক্লিক করে আপনার কল শুরু করতে পারবেন।"; zero sale: "এরপর জিরো সেল করার জন্য অপশন আসবে “এগিয়ে যান” বাটনে ক্লিক করুন।"; DRP: "এরপর DRP Discount সংগ্রহ করার জন্য Collect DRP Discount অপশনে ক্লিক করুন।" Effect of "না" not stated.
- **Exits:** "হ্যাঁ" -> AV/KV (SR-S-29/30) / survey (SR-S-31) / SKU entry (SR-S-32); "Collect DRP Discount" -> SR-S-26; "এগিয়ে যান" with no SKU -> SR-S-38.

## SR-S-26 Slide ("স্লাইড") - DRP / empty-pack collection
- **Pages:** 28, 29, 30
- **Title:** "স্লাইড" [EN: Slide]; process "DRP সংগ্রহ প্রক্রিয়া" [EN: DRP collection process]
- **Purpose:** Record empty cigarette packs returned per SKU against a time-limited offer (DRP discount).
- **Entry:** "Collect DRP Discount" (p27/31) or "স্লাইড সংগ্রহ" (p34) on the sale footer. **Exit:** "জমা দিন" saves -> back to sale (not shown).
- **Content:** back chevron; outlet block "Savar Metro (1618409)"; "রুট: Savar BazarDaily"; "ক্লাস্টার: Madrasa Road"; purple list header "এসকেইউ" | "খালি প্যাকেট" [Empty packet]; rows (thumbnail, purple SKU code MaxR-10S / MaxR-20S, stepper "−  [value]  +", red diagonal "OFFER" ribbon); full-width "জমা দিন" [Submit/Deposit] button.
- **Rules (stated):** callouts "এরপর স্লাইড সংগ্রহের জন্য এস কে ইউ লিস্ট দেখতে পারবেন। এবং স্লাইড এর অফার এর বিস্তারিত দেখার জন্য ‘Offer’ অপশনে ক্লিক করুন।"; "এরপর এখান থেকে (+) এবং (-) অপশন এর মাধ্যমে স্লাইড এর ডাটা প্রদান করতে পারবেন। এবং ম্যানুয়াল এন্ট্রিও করতে পারবেন। ম্যানুয়াল এন্ট্রির জন্য মার্ক করা অপশন এ ক্লিক করুন।"; "এরপর সংরক্ষিত প্যাক এর পরিমাণ দেখতে পারবেন।"; "এরপর ‘জমা দিন’ অপশনে ক্লিক করে স্লাইড এর ডাটা সেভ করুন।" Only SKUs with an active offer show the OFFER ribbon (observed).
- **Data:** outlet (code 1618409), route, cluster, SKU, offer, empty-pack count per SKU (write).

## SR-S-27 Slide offer detail dialog
- **Pages:** 28
- **Content:** header "ছাড়" [Discount] + "X"; offer text "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s"; validity "Nov 25, 2025" -> "Dec 30, 2025" (calendar icons); yellow "× বন্ধ করুন" [Close].
- **Rules (stated):** "এখান থেকে ইউজার স্লাইড সংগ্রহের সময়সীমা এবং অফার এর তথ্য দেখতে পারবেন। তথ্য দেখা শেষে ‘বন্ধ করুন’ অপশনে ক্লিক করুন।" Behaviour outside the validity window not stated.

## SR-S-28 Slide manual-quantity dialog
- **Pages:** 29
- **Content:** red "X"; thumbnail with blue badge "০"; label "MaxR-10 S"; stepper "−  10  +" (editable); two columns of teal shortcut buttons: add "১", "৫", "১০", "২০", "৫০"; subtract "-১", "-৫", "-১০", "-২০", "-৫০"; "সংরক্ষণ" [Save].
- **Rules (stated):** "এরপর এখান থেকে প্যাক অনুযায়ী ম্যানুয়াল এন্ট্রি করতে পারবেন সেইসাথে শর্টকাট ডাটা এর মাধ্যমেও এন্ট্রি করতে পারবেন। এন্ট্রি শেষে ‘সংরক্ষন’ অপশনে ক্লিক করুন।" Quantity commits only on dialog "সংরক্ষণ". Tapping the stepper value opens this dialog. Min/max not stated.

## SR-S-29 AV viewer
- **Pages:** 32
- **Content:** full-screen black, promotional pack image/video (MARISE "Black Diamond" pack, shown rotated/landscape); thin red vertical line at left (progress bar? unlabelled); small round grey "×" button bottom-right.
- **Rules (stated):** "এরপর নির্দিষ্ট আউটলেটের জন্য কোন AV থাকলে সেই AV দেখতে পারবেন।" Shown only if one exists for the outlet; AV first, then KV. Mandatory-watch / skip rules not stated.

## SR-S-30 KV viewer
- **Pages:** 32
- **Title:** "KV"
- **Content:** back chevron; image (matchbox ad: "ফ্লেইম বক্স", "কার্বোরাইজড ১০০% নিরাপদ দিয়াশলাই", "আবুল খায়ের ম্যাচ ফ্যাক্টরী লিঃ"); full-width "× বন্ধ করুন" [Close].
- **Rules (stated):** "AV দেখা সম্পন্ন হয়ে গেলে নির্দিষ্ট আউটলেটের জন্য KV থাকলে KV দেখতে পারবেন। KV দেখা হয়ে গেলে “বন্ধ করুন” বাটনে ক্লিক করুন।"
- **Data:** AV asset, KV asset, outlet-to-asset targeting. Caching/offline not stated.

## SR-S-31 Survey - POSM Availability (+ submit confirmation)
- **Pages:** 33
- **Title:** "সার্ভে" [EN: Survey]; section heading (red) "POSM Availability"; process "POSM সার্ভে করার প্রক্রিয়া"
- **Controls:**
  | Control | Type | Required | Notes |
  |---|---|---|---|
  | Info card "আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে" | text | - | |
  | Q "1* আপনার আউটলেটে POSM আছে?" answer "উত্তরঃ" | dropdown: "হ্যাঁ" / "না" | required (*) | only "হ্যাঁ" shown; "না" per callout |
  | Q "1.1* POSM এর ছবি সাবমিট করলে আপনি ৫০ পয়েন্ট অর্জন করবেন।" answer "উত্তরঃ" | photo (thumbnail + red trash icon to delete) | required (*), conditional on Q1=হ্যাঁ | earns 50 points |
  | "সাবমিট →" [Submit] (purple footer) | button | - | opens confirm |
  - Confirm dialog: info icon, "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?"; green "হ্যাঁ", red "না".
- **Rules (stated):** survey shown for specific outlets only; callouts "নির্দিষ্ট আউটলেটের জন্য কোন POSM Availability এর একটি সার্ভে দেখতে পারবেন। POSM না থাকলে না নির্বাচন করুন। POSM থাকলে হ্যাঁ অপশন নির্বাচন করুন। এরপর POSM এর একটি ছবি তুলুন। এরপর সাবমিট অপশনে ক্লিক করুন।" and "সকল তথ্য সঠিক হলে “হ্যাঁ” বাটনে ক্লিক করে তথ্য সংরক্ষণ করুন।"
- **Hardware:** camera. **Data:** survey (POSM Availability), answers (Q1; Q1.1 photo), points ledger +50.
- **Position in flow:** after AV/KV (inferred from page order; p32 -> p33).

## SR-S-32 Sale entry (SKU quantities)
- **Pages:** 34 (also 47 edit mode)
- **Title:** "বিক্রয়"
- **Content:** back chevron + logo; outlet dropdown with small outlet image "Savar Metro (1618409)"; alphabet strip All + A B C D E F G H I J / K M N P R S T V Z (single-select filter by SKU first letter per transcriber; for outlets elsewhere - ambiguity F-15); green full-width "পূর্বের সেল ডাটা দেখুন" [View previous sale data] (history icon) -> SR-S-20; SKU cards: pack image with purple badge bottom-left "১", purple SKU code, stepper "(−) [qty] (+)" with underlined editable qty, and three unlabelled icon+number rows (cart "১০০০০"/"১১০০০"/"৩৬০০"; percent "০%"; box "০"); purple footer: cart icon + total "২৮০.৫০", yellow "স্লাইড সংগ্রহ" [Slide collection] and rose "এগিয়ে যান→" [Proceed].
- **Sample:** MaxR-10S qty ১০; MaxR-20S ২০; AB-12s ১২.
- **Rules (stated):** "এরপর SKU লিস্ট দেখতে পারবেন, SKU অনুযায়ী দোকানদারের চাহিদামত শলাকার পরিমাণ উল্লেখ করুন। এরপর এগিয়ে যান অপশনে ক্লিক করুন।" -> quantity entered in sticks (শলাকা) per shopkeeper's demand.
- **Exits:** "এগিয়ে যান" -> SR-S-33; with zero SKUs -> SR-S-38; "স্লাইড সংগ্রহ" -> SR-S-26.
- **Data:** SKU list (code, image, category, price), outlet, cart total.

## SR-S-33 Review ("নিরীক্ষণ") - verify, credit flag, QC, print/commit
- **Pages:** 34, 35, 36, 38, 39, 43 (and Memo-style layout p44)
- **Title:** "নিরীক্ষণ" [EN: Review / Inspection / Verification]
- **Header block:** back chevron; outlet name e.g. "Savar Metro (1618409)" / "Kamal Store (1618408-[phone]-Ma Road)" / "Afifa Stor (Diamond)" / "Moin Store (DHK-344-005-[phone]-Apsis Cluster)"; icon at top-right of outlet name (red eye-with-slash on p34/35/36 - unexplained; printer icon green on p38/39, red slashed on p43); lines "রুট: ..." (p34, 35, 36, 43) or "সেকশন: AgrabadDaily" (p38, 39); "ক্লাস্টার: ...".
- **Sale table:** "এসকেইউ" | "পরিমাণ" | "মূল্য"; SKU rows; category subtotals "মোট সিগারেট", "মোট বিড়ি", "মোট লাইটার", "মোট ম্যাচ"; "মোট". Example p34: MaxR-10S ২০ ১৬০.০০; MaxR-20S ২০ ১৬০.০০; AB-12s ১২ ৯.০০; Aster ১ ১২.৫০; SL ১২ ১৯.০০; মোট সিগারেট ৪০ ৩২০.০০; বিড়ি ১২ ৯.০০; লাইটার ১ ১২.৫০; ম্যাচ ১২ ১৯.০০; মোট ৬৫ ৩৬০.৫০. Zero sale: single row "সর্বমোট" ০ ০.০০. Red "QC" deduction line exists (p38, partly hidden).
- **Slide section** (when slide collected): tab label "স্লাইড"; columns "এসকেইউ" | "পরিমাণ" | "দাম"; offer text "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s"; row MaxR-10S ১০ ৮০.০ (red); "মোট" ১০ ৮০.০০; "সর্বমোট" ৬৫ ২৮০.৫০ (= 360.50 - 80.00; quantity total unchanged).
- **Controls:** checkbox (default unchecked) "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" (p34, 35, 36) / "এই বিক্রয়টি ক্রেডিট হিসাবে চিহ্নিত করুন" (p38, 39) [Mark this sale as due/credit] - NOT shown on zero-sale review; yellow "প্রোডাক্ট QC" [Product QC] -> SR-S-35; gradient "প্রিন্ট" [Print] -> commit flow (SR-S-37); info "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।"; after partial payment the checkbox label becomes "Baki ৬১ টাকা" and is checked.
- **Rules (stated):** callouts "এরপর উক্ত পেজ থেকে আপনারা বিক্রয় এর পরিমাণ এবং স্লাইড এর পরিমাণ সঠিক আছে কিনা সেটি যাচায় করতে পারবেন। এবং বিক্রয় যদি বাকীতে হয়ে থাকে তাহলে বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন অপশনটি নির্বাচন করুন।"; "এরপর উক্ত দোকানের সকল তথ্য সংরক্ষন করার জন্য “প্রিন্ট” বাটনে ক্লিক করুন, এরপর এমন একটি Alert দেখতে পারবেন এবার “হ্যাঁ” বাটনে ক্লিক করুন।"; zero sale "এখান থেকে জিরো সেল এর ডাটা চেক করুন এবং ‘প্রিন্ট’ অপশনে ক্লিক করে জিরো সেল কমপ্লিট করুন।" -> tapping Print is the commit action.
- **Data:** sale/memo header+lines, category subtotals, slide offer, credit flag, QC deduction.

## SR-S-34 Partial payment dialog ("পরিশোধিত টাকার পরিমাণ লিখুন")
- **Pages:** 35
- **Process title:** "আংশিক টাকা পরিশোধ করার প্রক্রিয়া" [EN: Partial payment process]
- **Trigger:** ticking the credit checkbox on SR-S-33.
- **Content:** title "পরিশোধিত টাকার পরিমাণ লিখুন" [Enter the amount of money paid]; body "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?"; number input (floating label "আদায়কৃত অর্থ" [Collected amount], value "100" typed Latin) with read-only live text "বাকিঃ ৬১.৫০" (= grand total 161.50 - 100); buttons green "হ্যাঁ", red "না".
- **Rules (stated):** "এবং এই পরিমাণটি অবশ্যই সর্বমোট টাকার পরিমাণ এর থেকে কম হতে হবে।" (collected must be strictly less than grand total). After confirmation the checkbox shows checked with "Baki ৬১ টাকা". Callouts: "বিক্রয়টি বাকি হিসাবে চিহ্নিত করার পর এই পেজটি দেখতে পারবে। এখানে আপনি রিটেইলার থেকে ঐ মুহূর্তে সংগ্রহীত টাকার পরিমাণ উল্লেখ করুন।..."; "এরপর আপনি উক্ত দোকানের জন্য অবশিষ্ট বাকি টাকার পরিমাণ দেখতে পারবেন।" Error text for amount >= total not shown; 0/empty/decimal rules not stated.
- **Data:** payment/collection at point of sale; outlet receivable.

## SR-S-35 QC picker and QC summary
- **Pages:** 36, 37, 38
- **Title:** "QC" (header); section "QC সারাংশ" [QC Summary]; process "QC INPUT করার প্রক্রিয়া"
- **Content:** info card "QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন"; horizontal carousel of SKU tiles (MaxR-10S, MaxR-20S, MaxB-10S, 4th cut off "Ma..."); empty state "QC করার জন্য কোন পণ্য নেই" [There is no product for QC]; summary table "এসকেইউ" | "QC টাইপ" | "পরিমাণ" (row: MaxR-10S | "MFC Fault,MKT Fault" | ১০); totals "মোট QC নিষ্পত্তি" ১০; "সেটেলমেন্ট পরিমান" ৮০.০০৳; purple footer "QC জমা দিন →" [Submit QC].
- **Rules (stated):** "QC করার জন্য প্রোডাক্ট QC নামে একটা অপশন দেখতে পারবেন। ত্রূটিপূর্ণ সিগারেট ফেরত আনার জন্য “প্রোডাক্ট QC ” বাটনে প্রেস করুন।"; "“প্রোডাক্ট QC” বাটনে ক্লিক করার পর SKU লিস্ট দেখতে পারবেন। এরপর যে SKU এ QC করবেন SKU টি নির্বাচন করুন।"; "QC এর তথ্য গুলো সঠিক থাকলে “QC জমা দিন” বাটনে ক্লিক করুন।" Submit confirm dialog: title "ইনফর্মেশন", body "আপনি কি QC জমা দিতে চান?", green "হ্যাঁ", red "না"; callout "QC এর তথ্য গুলো সফল ভাবে সংরক্ষিত করার জন্য “হ্যাঁ” বাটনে ক্লিক করুন।"
- **Data:** QC record (per SKU, fault types, qty, settlement amount).

## SR-S-36 QC entry (per SKU)
- **Pages:** 37
- **Title:** "QC এন্ট্রি" [EN: QC Entry]
- **Content:** back chevron; SKU card (pack image) with amounts "সর্বোচ্চ QC: ৫৯৪.৫০৳" [Maximum QC 594.50 taka], "QC হয়ে গেছে: ০.০০৳" [QC done], "বাকি আছে: ৫৯৪.৫০৳" [Remaining]; table "ফল্ট টাইপ" | "প্রকার" | "পরিমাণ" with numeric input per row:
  | Group | Row (প্রকার) | EN |
  |---|---|---|
  | "উৎপাদন ত্রুটি" [Production fault] | "ড্যামেজড ও ক্রাশড – প্যাক / আউটার CBC" | Damaged and crushed - pack / outer CBC |
  | | "আউটার / প্যাক / স্টিক কম থাকা" | Outer / pack / stick short |
  | | "অন্যান্য ত্রুটি" | Other faults |
  | "পরিবহন ত্রুটি" [Transport fault] | "মেয়াদোত্তীর্ণ স্টক (৪ মাস+)" (digit glyph looks like 8) | Expired stock (4 months+) |
  | | "স্টক ড্যামেজড – রুট সার্ভিস কালীন" | Stock damaged - during route service |
  | | "স্বাদ সংক্রান্ত সমস্যা" | Taste-related problem |
  Buttons stacked: "সংরক্ষণ" [Save], "বাতিল" [Cancel], "ডিলিট" [Delete].
- **Rules (stated):** "“উৎপাদন ত্রুটি” এর জন্য, যে ধরণের ত্রুটি এবং কতগুলা শলাকা ত্রুটিপূর্ণ তার সংখ্যা উৎপাদন ত্রুটি এর পরিমাণ কলামে প্রদান করুন। “পরিবহন ত্রুটি” এর জন্য, কি ধরণের ত্রুটি এবং কতগুলা শলাকা ত্রুটিপূর্ণ তার সংখ্যা পরিবহন ত্রুটি এর পরিমাণ কলামে প্রদান করুন। এরপর QC এর তথ্য গুলো সংরক্ষণ করার জন্য সংরক্ষণ বাটনে ক্লিক করুন।" Qty in sticks. Cancel/Delete not explained.
- **Data:** per-SKU QC entry; max QC amount; done; remaining.

## SR-S-37 Sale commit dialogs (confirm -> print prompt -> success)
- **Pages:** 38, 39
- **Process title:** "বিক্রয় প্রক্রিয়া" [EN: Sales process]
- **Dialogs in order (after tapping প্রিন্ট on SR-S-33):**
  1. warning icon; title "আপনি কি নিশ্চিত?"; body "বিক্রয় জমা হবে" [Sale will be submitted]; green "হ্যাঁ", red "না".
  2. info icon; "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" [Do you want to print this sale?]; green "হ্যাঁ", red "না".
  3. green tick; title "সফল"; body "বিক্রয় সফল ভাবে জমা হয়েছে" [Sale has been submitted successfully]; green "ওকে".
- **Rules (stated):** "এরপর বিক্রয় মেমো প্রিন্ট এর একটি Alert দেখতে পারবেন। মেমো প্রিন্ট করতে চাইলে “হ্যাঁ” অপশনটিতে ক্লিক করুণ।" ; "“হ্যাঁ” অপশনে ক্লিক করার পর উক্ত আউটলেট এর বিক্রয়কার্য্য সম্পূর্ণ হওয়ার একটি “সফল” ম্যাসেজ দেখতে পারবেন।" Effect of "না" at the print prompt not stated.
- **Hardware:** Bluetooth thermal printer (memo). 

## SR-S-38 Zero-sale confirmation dialog
- **Pages:** 43
- **Process title:** "জিরো বিক্রয় প্রক্রিয়া" [EN: Zero sale process]
- **Content:** warning icon; title (blue) "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি" [No SKU has been selected for sale]; body "আপনি কি জিরো (০) বিক্রয় করতে চান?" [Do you want to do a zero (0) sale?]; green "হ্যাঁ", red "না".
- **Rules (stated):** "এরপর ‘হ্যাঁ’ অপশনটি নির্বাচন করুন।" Trigger: "এগিয়ে যান" pressed with no SKU quantity (observed). Result -> SR-S-33 in zero-sale form.

## SR-S-39 Memo (view, reprint, pay dues, edit)
- **Pages:** 44, 45, 46, 47
- **Title:** "মেমো" [EN: Memo]; process titles "বাকি পরিশোধ প্রক্রিয়া" [Process of paying dues], "বিক্রয় এডিট প্রক্রিয়া" [Process of editing a sale]
- **Entry:** dashboard "মেমো". **Exit:** back; Edit -> SR-S-41.
- **Content:** back chevron + icon + title; round printer button (red slashed when not connected); info card "আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন"; outlet dropdown "Tasmin (1618415)  : ২৯১.৫০ ৳ বাকি" (outstanding due shown in red); alphabet strip All A B C D E F G H I J K M N P / R S T V Z; memo table "এসকেইউ" | "পরিমাণ" | "দাম": MaxR-10S ১০ ৮০.০০; MaxR-20S ২০ ১৬০.০০; AB-25s ২৫ ২০.০০; Aster ১ ১২.৫০; SL ১২ ১৯.০০; subtotals মোট সিগারেট ৩০ ২৪০.০০; মোট বিড়ি ২৫ ২০.০০; মোট লাইটার ১ ১২.৫০; মোট ম্যাচ ১২ ১৯.০০; "মোট" ৬৮ ২৯১.৫০; "মোট QC" "- ০.০০"; "সর্বমোট" ২৯১.৫০; credit status box (red dashed): red text "এই বিক্রয়টি বাকিতে করা হয়েছে।" + green button "পরিশোধিত করুন" [Mark as paid]; bottom buttons "প্রিন্ট" [Print] and "এডিট" [Edit, yellow].
- **Rules (stated):** callouts "বাকীতে বিক্রি আউটলেট গুলোর বাকী পরিশোধ করতে মেমো অপশনে ক্লিক করুন।"; "এরপর আউটলেট নির্বাচন করুন এবং ‘পরিশোধিত করুন’ অপশনে ক্লিক করুন।"; "বিক্রয় পরবর্তী কোন আউটলেটের ভুল বিক্রয় মেমো অপশন থেকে সংশোধন করা যাবে।"; "মেমো অপশন এর আউটলেট তালিকা থেকে নির্ধারিত আউটলেট সিলেক্ট করুন। বিক্রয় এডিট করার জন্য ঐ আউটলেটের Geofencing এর আওতায় থাকতে হবে, এবং QC কৃত কোন আউটলেটে বিক্রয় এডিট করা যাবে না। বিক্রয় এডিট করতে এডিট অপশনে ক্লিক করুণ।" Grand total = মোট - মোট QC (observed arithmetic).
- **Data:** memo + lines, credit/paid status, outlet due, QC total.

## SR-S-40 Mark-as-paid confirmation and success
- **Pages:** 45
- **Content:** warning icon, title "আপনি কি নিশ্চিত?" (no body), green "হ্যাঁ", red "না"; then success dialog (green tick) "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" with "ওকে".
- **Rules (stated):** "এরপর ‘হ্যাঁ’ অপশনটি নির্বাচন করুন।"; "এরপর একটি সফল মেসেজ দেখতে পারবেন। এবং বাকী স্ট্যাটাসটি আউটলেট এর পাশে থেকে মুছে যাবে।" Whole-memo settlement; no partial amount field on this screen.

## SR-S-41 Sale-edit reason dialog
- **Pages:** 47
- **Content:** title "দয়া করে বিক্রয় এডিট করার কারন লিখুন" [Please write the reason for editing the sale]; dropdown showing "ভুল SKU নির্বাচিত।" [Wrong SKU selected.] (3 options exist; only first named); buttons "বাতিল" [Cancel, yellow], "জমা দিন" [Submit, red-pink].
- **Rules (stated):** "নির্ধারিত আউটলেট সিলেক্ট করে এডিট বাটনে ক্লিক করার পর, বিক্রয় এডিট এর কারন হিসেবে তিনটি অপশন দেখতে পাবেন। নির্দিষ্ট অপশন সিলেক্ট করে জমা দিন বাটনে ক্লিক করুন।" Reason mandatory (dropdown; no free text although the title says "লিখুন").

## SR-S-42 Sale page in edit mode
- **Pages:** 47
- **Content:** same layout as SR-S-32 but the outlet is a read-only text box "Tasmin (1618415)"; SKU cards pre-filled (MaxR-10S ১০, MaxR-20S ২০, AB-25s ২৫) with the three unlabelled icon rows (cart "২৫০০"/"৫০০০"/"৮৭৫০", percent "১০০%", box "০"); footer total "২৯১.৫০"; "এগিয়ে যান →".
- **Rules (stated):** "বিক্রয় এডিট এর কারন জমা দেওয়ার পরে ঐ আউটলেটের বিক্রয় পেজ দেখতে পাবেন। এখান থেকে আবার সঠিক বিক্রয় ইনপুট করে বিক্রয় এডিট কার্য সম্পন্ন করুন।" Remaining steps (review, commit) not shown.

## SR-S-43 Astha - route information
- **Pages:** 48, 49
- **Title:** "আস্থা" [EN: Astha]; tabs "রুটের তথ্য" [Route information] (selected, red pill) / "দোকান ভিত্তিক তথ্য" [Shop-wise information]
- **Controls:** dropdowns "বছর নির্বাচন করুন" [Select year] (placeholder "বছর"; value "2025") and "কোয়ার্টার নির্বাচন করুন" [Select quarter] (placeholder "কোয়ার্টার"; value "Q-4 (Oct-Dec)"; format "Q-<n> (<Mon>-<Mon>)"; greyed until year chosen - inferred); "মাস নির্বাচন করুন" [Select month] multi-select chips "Oct","Nov","Dec" (selected chip shows check mark "✓ Nov"); sub-tabs "এসটিডি টার্গেট" [STD target] (default) / "মেমো টার্গেট" [Memo target]; empty state "তথ্য পাওয়া যায়নি" [No data found].
- **Tables:** STD target "ব্র্যান্ড" | "টার্গেট" | "অর্জন" | "বাকি" | "%": Maxim 12,480 / 33,050 / 0 / 264.82%; Black Diamond 218,010 / 2,000 / 216,010 / 0.92%; Abul Bidi Style 0 / 6,250 / 0 / 0.00%; Marise -20 / 7,500 / 0 / -37,500.00%; Avon 4,180 / 5,000 / 0 / 119.62%; Supreme 16,360 / 0 / 16,360 / 0.00%; Special Abul Bidi 0 / 0 / 0 / 0.00%; Abul Bidi Gold 0 / 6,250 / 0 / 0.00% (scrolls). Memo target: single row "All Brand" 28 / 6 / 22 / 21.43%.
- **Rules (stated):** "রুট এবং আউটলেট ভিত্তিক আস্থা এর এসটিডি এবং মেমো এর টার্গেট এবং অ্যাচিভমেন্ট এর ডাটা দেখতে আস্থা অপশনে ক্লিক করুন।"; "রুট এর তথ্য দেখতে এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।"; "প্রাথমিক অবস্থায় উক্ত রুট এর **এসটিডি** টার্গেট দেখতে পারবেন। এরপর **মেমো টার্গেট** দেখতে **মেমো টার্গেট** অপশনে ক্লিক করুন।"; "এরপর **মেমো টার্গেট অ্যাচিভমেন্ট** এর ডাটা দেখতে পারবেন। সেইসাথে মাস নির্বাচন সেকশান থেকে যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।" No clamping: >100% and negative target shown as is (observed). Formulas (inferred): remaining = max(target-achievement,0); % = achievement/target x100; target 0 -> 0.00%.
- **Data:** route-level targets/achievements by brand, year, quarter, month. **Offline:** not stated.

## SR-S-44 Astha - shop-wise list
- **Pages:** 50
- **Controls:** tab "দোকান ভিত্তিক তথ্য" active; scrollable outlet cards. Card fields: "খুচরা বিক্রেতার নাম" (e.g. "Shopon (1618409-[phone]-Madrasa Road)"), "মালিকের নাম" (Sopon), "যোগাযোগ" ([phone]), "Sub-Channel" (orange: Diamond/Gold), "Wing", "Division", "Territory", "Zone" (red). Cards: Shopon (Diamond), Siyam (Gold, owner Robiul, [phone]), Babu Store (Diamond, [phone], Wing Dhaka, Division Savar). Before selection: year/quarter dropdowns greyed and "তথ্য পাওয়া যায়নি".
- **Rules (stated):** "আউটলেট ভিত্তিক আস্থা এর এসটিডি এবং মেমো এর টার্গেট এবং অ্যাচিভমেন্ট এর ডাটা দেখতে দোকান ভিত্তিক তথ্য অপশনে ক্লিক করুন।"; "এরপর আস্থা রিটেইলার লিস্ট দেখতে পারবেন। একটি রিটেইলার নির্বাচন করুন।" Whole card taps through (inferred).
- **Data:** outlet master (name, code, owner, mobile, sub-channel, wing, division, territory, zone).

## SR-S-45 Astha - outlet detail (outlet-wise targets)
- **Pages:** 51, 52
- **Content:** outlet header: "খুচরা বিক্রেতার নাম : Babu Store (1618411)", "মালিকের নাম : Babu", "যোগাযোগ : [phone]", "Sub-Channel : Diamond", "Wing : Gaibandha", "Division : Savar", "Territory : Savar", "Zone : Savar Metro"; year/quarter dropdowns (2025, Q-4 (Oct-Dec)); month chips; sub-tabs STD/Memo target; table "ব্র্যান্ড" | "টার্গেট" | "অর্জন" | "%" (4 columns, no "বাকি"). STD: Maxim 900/0/0.00%; Black Diamond 10,140/0; Abul Bidi Style 0; Marise 0; Avon 900/0; 6th row cut off. Memo (per brand): Maxim 9/0; Black Diamond 9/0; Abul Bidi Style 0; Marise 9/0; Avon 9/0.
- **Rules (stated):** "এরপর এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।"; "প্রাথমিক অবস্থায় উক্ত আউটলেট এর এসটিডি টার্গেট দেখতে পারবেন। এরপর মেমো টার্গেট দেখতে মেমো টার্গেট অপশনে ক্লিক করুন।"; "এরপর মেমো টার্গেট অ্যাচিভমেন্ট এর ডাটা দেখতে পারবেন।"; "সেইসাথে মাস নির্বাচন সেকশান থেকে যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।"

## SR-S-46 Outlet menu
- **Pages:** 53
- **Title:** "আউটলেট" [EN: Outlet]
- **Controls:** three tiles: "নতুন দোকান" [New shop] -> SR-S-47; "স্থায়ী বন্ধ" [Permanently closed] -> SR-S-48; "তথ্য পরিবর্তন করুন" [Change information] -> SR-S-49.
- **Rules (stated):** "নতুন আউটলেট সংযোজন, **স্থায়ী বন্ধ**, এবং **তথ্য পরিবর্তন** এর জন্য ড্যাশবোর্ড থেকে **আউটলেট** বাটনে ক্লিক করুন।" ; "আউটলেট বাটনে ক্লিক করার পর আপানারা **নতুন দোকান**, **স্থায়ী বন্ধ**, এবং **তথ্য পরিবর্তন** অপশন তিনটি দেখতে পারবেন।..."

## SR-S-47 New shop
- **Pages:** 54, 55
- **Title:** "নতুন দোকান" [EN: New shop]
- **Controls:**
  | Control | Type | Required | Notes |
  |---|---|---|---|
  | "ক্লাস্টার নির্বাচন করুন" [Select cluster] (e.g. "Molobe Para") | dropdown | not stated | options = SR's clusters (inferred) |
  | "দোকানের নাম" [Shop name] (e.g. "Rasel Store") | text | not stated | |
  | "দোকানের মালিকের নাম" [Owner name] ("Rasel") | text | not stated | |
  | "মোবাইল নম্বর" [Mobile number] ("[phone]", 11 digits) | text/number | not stated | format validation not stated |
  | "GEO এবং ছবি ধারণ করুন" [Capture GEO and photo] | button -> camera (SR-S-24) | - | one photo of the outlet |
  | "সংরক্ষণ" [Save] (floppy icon) | button | - | no confirmation dialog |
- **Success:** modal, green tick, "সফল" [Success], button "ওকে".
- **Rules (stated):** "তথ্য ইনপুট শেষে **GEO এবং ছবি ধারন করুন** অপশনে ক্লিক করুন।"; "GEO এবং ছবি ধারণ করুন অপশনে ক্লিক করার পরে ডিভাইসের ক্যামেরা অপশন চালু হবে। এরপর ঐ **আউটলেটের** একটি ছবি তুলুন।"; "ছবি কেপচার শেষ করে, **সংরক্ষণ** বাটনে ক্লিক করুন।"; "সংরক্ষণ বাটনে ক্লিক করার পরে এলার্ট দেখতে পারবেন।" No "photo captured" alert shown for New Shop (unlike Info Change). No field for outlet type/sub-channel/address/route.
- **Data written:** new outlet (cluster, name, owner, mobile, lat/long, photo).

## SR-S-48 Permanently closed outlet
- **Pages:** 56, 57
- **Title:** "স্থায়ী বন্ধ" [EN: Permanently closed]
- **Controls:** "ক্লাস্টার নির্বাচন করুন" dropdown ("Molobe Para"); "আউটলেট নির্বাচন করুন" [Select outlet] dropdown ("Sifat St (Diamond)" = name + sub-channel); read-only grey info: "দোকানের নাম:" "Sifat St (Diamond)", "দোকানের মালিকের নাম:" "Nurul Kobir", "মোবাইল নম্বর:" "[phone]"; "সংরক্ষণ" [Save]. No reason/remarks, photo or GEO field.
- **Confirm:** blue info icon; title "আপনি কি নিশ্চিত?"; body "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" [This shop is going to be permanently closed]; green "হ্যাঁ", red "না". **Success:** "সফল" + "ওকে".
- **Rules (stated):** "স্থায়ী বন্ধ দোকানের তথ্য ইনপুট করার জন্য **স্থায়ী বন্ধ** অপশনে ক্লিক করুন।"; "**স্থায়ী বন্ধ** অপশনে ক্লিক করার পরে এই পেজ দেখতে পারবেন। এখান থেকে ঐ আউটলেটের তথ্য ইনপুট করুন। ইনপুট শেষে **সংরক্ষণ** বাটনে ক্লিক করুন।"; "**সংরক্ষণ** বাটনে ক্লিক করার পরে, এই এলার্ট দেখতে পারবেন। এখান থেকে **হ্যাঁ** অপশনে ক্লিক করুন।"

## SR-S-49 Information change
- **Pages:** 58, 59, 60
- **Title:** "তথ্য পরিবর্তন করুন" [EN: Change information]
- **Controls:** "ক্লাস্টার নির্বাচন করুন" ("Molobe Para"); "আউটলেট নির্বাচন করুন" ("Ebraj St (Diamond)"); editable pre-filled "দোকানের নাম" ("Ebraj St"), "দোকানের মালিকের নাম" ("Ebraj"), "মোবাইল নম্বর" ("[phone]"); "GEO এবং ছবি ধারণ করুন"; "সংরক্ষণ".
- **Flow & dialogs:** change fields -> "GEO এবং ছবি ধারণ করুন" -> camera -> alert (green tick) "ছবি ধারণ করা সম্পন্ন হয়েছে" [Photo capture completed] + "ওকে" -> "সংরক্ষণ" -> confirm: info icon, "আপনি কি নিশ্চিত?", "এই পরিবর্তন সংরক্ষণ করা হবে" [This change will be saved], "হ্যাঁ"/"না" -> success "সফল" + "ওকে".
- **Rules (stated):** "দোকানের তথ্য ভুল মনে হইলে আপনারা তথ্যগুলো পরিবর্তন করতে পারবেন। তথ্য পরিবর্তন শেষে “GEO এবং ছবি ধারন করুন” অপশনটি নির্বাচন করতে হবে।" (GEO+photo mandatory after changing); "“GEO এবং ছবি ধারন করুন” অপশনটি নির্বাচন করার পর আপনার ডিভাইস এর Camera ওপেন হয়ে যাবে। এরপর উক্ত দোকানের একটি ছবি উঠাতে হবে।"; "ছবি উঠানোর পর একটি “Successful” alert দেখতে পারবেন। এরপর “OK” অপশনে ক্লিক করুন।"; "আউটলেটের তথ্য সঠিক মনে হইলে “**সংরক্ষন করুন**” অপশনে ক্লিক করুন। এরপর “**হ্যাঁ**” অপশন নির্বাচন করুন।"; "এরপর আপনার তথ্য সংরক্ষন এর একটি **Successful Alert** দেখতে পারবেন।" Enforcement message for skipping GEO+photo not shown.
- **Data updated:** outlet name, owner, mobile, GEO, photo.

## SR-S-50 Loyalty Point menu
- **Pages:** 61
- **Title:** "লয়্যালটি পয়েন্ট" [EN: Loyalty Point]
- **Controls:** back chevron; one tile "রিডিম্পশন" [Redemption] (gift-and-coin icon) -> SR-S-51.
- **Rules (stated):** "গিফট রিডিম্পশন করার জন্য অ্যাপ্লিকেশান ড্যাশবোর্ড থেকে লয়্যালটি পয়েন্ট অপশনে ক্লিক করুন।"; "এরপর রিডিম্পশন অপশনে ক্লিক করুন।" (Tile only on accounts with the loyalty feature.)

## SR-S-51 Gift Redemption (Diamond League)
- **Pages:** 62, 63 (also reachable from SR-S-21 "রিডিম্পশন")
- **Title:** "Gift Redemption" (English; slide title "গিফট রিডিম্পশন" p61, "গিফট রিডিমশন" p62-63)
- **Controls:** back chevron; outlet dropdown ("Bipu Store (6909084-[phone]-Apsis Cluster)"); alphabet strip All A B M R S a b j o s t; red band "Diamond League (April)" with pill "২২০ Pts Remaining" (live counter); gift grid (2 columns, scrolls; more below fold) - card: icon, Bangla gift name, coin badge with cost, stepper "- qty +":
  | Gift | Cost |
  |---|---|
  | "প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক।" [for every 1 point, 2 taka cash back] | ১ |
  | "২ স্টেপ কিচেন র‍্যাক।" [2-step kitchen rack] | ২০০ |
  | "চেয়ার।" [Chair] | ২০০ |
  | "টর্নেডো ফ্যান।" [Tornado fan] | ৮০০ |
  Button "Confirm Redemption" (red). Dialog: info icon "Confirm redemption of these items? Points will be deducted." buttons "নিশ্চিত করুন" (green) / "বাতিল" (red); success: green tick "Redemption successful" + "ওকে".
- **Button states (observed):** "-" grey at qty 0; "+" grey when cost > remaining points (tornado fan at 220 remaining). Sample: 20 x cash-back + 1 x kitchen rack = 220 -> "০ Pts Remaining".
- **Rules (stated):** "এরপর এই পেজে উক্ত আউটলেট এর অর্জিত টোটাল পয়েন্ট দেখতে পারবেন এবং গিফট এর লিস্ট দেখতে পারবেন।"; "এরপর এখান থেকে প্লাস এবং মাইনাস বাটনের মাধ্যমে পয়েন্ট অনুযায়ী গিফট সিলেক্ট করতে পারবেন এবং সর্বোচ্চ ১৯৯ পয়েন্ট ক্যাশ রিডিম্পশন করতে পারবেন। তথ্য সাবমিট করার জন্য Confirm Redemption অপশনে ক্লিক করুন।"; "তথ্য সেভ করার জন্য এখান থেকে নিশিত করুন অপশনে ক্লিক করুন।"; "এরপর একটি সফল মেসেজ দেখতে পারবেন।"
- **Data:** league points per outlet per month, gift catalogue (name, cost), redemption record (outlet, gift, quantity, points). **Offline:** not stated.

## SR-S-52 Photo Capture menu
- **Pages:** 64, 68
- **Title:** "Photo Capture" (English)
- **Controls:** back chevron; tiles "আস্থা" [Astha, round emblem] -> SR-S-53; "Campaigns" (English) -> SR-S-54.
- **Rules (stated):** "আস্থা আউটলেটে গিফট বিতরণ এর ছবি উঠানোর জন্য ড্যাশবোর্ড থেকে Photo Capture অপশনে ক্লিক করুন।"; "এরপর এখান থেকে আস্থা বাটনে ক্লিক করুন।"; "Diamond League এর গিফট বিতরণ এর ছবি উঠানোর জন্য ড্যাশবোর্ড থেকে Photo Capture অপশনে ক্লিক করুন।"; "এরপর এখান থেকে Campaigns বাটনে ক্লিক করুন।"

## SR-S-53 Astha Photo Capture (Astha gift distribution photo)
- **Pages:** 65, 66, 67
- **Title:** "Astha Photo Capture"; slide title "আস্থা গিফট Photo Capture"
- **Controls:** outlet dropdown ("Bipu store (6658061-[phone]-Apsis Cluster)", placeholder "রিটেইলার নির্বাচন করুন"); alphabet strip; gift card (red title "27 pcs Dinner Set"; label "Gift Distribute Image"; photo slot with camera icon "Capture Photo"); after photo: thumbnail with red trash (delete) and circular reload (retake) icons, tap to zoom; "সাবমিট" [Submit]. Empty state: camera icon + "অনুগ্রহ করে রিটেইলার নির্বাচন করুন" (no Submit). Already captured: "Photo already captured." (Submit still displayed).
- **Dialogs:** "আপনি কি নিশ্চিত?" with "হ্যাঁ"/"না"; success "Photo capture details saved successfully." + "ওকে".
- **Rules (stated):** "TSO Portal থেকে আউটলেটে এর জন্য যে গিফট নির্বাচন করা হয়েছিলো ঐ সকল আউটলেট দেখতে পাবেন। আউটলেটে নির্বাচন করার পর আউটলেটে নির্বাচিত গিফট এর নাম দেখতে পাবেন। এবার গিফট এর ছবি উঠানোর জন্য Capture Photo তে ক্লিক করুন।"; "এরপর ফোন এর ক্যামেরা ওপেন হবে গিফট এর ছবি ভালোভাবে উঠাবেন। এরপর ডিলিট ও রিলোড বাটনে ক্লিক করে পুনরায় ছবি তুলতে পারবেন। সেইসাথে ছবিতে ক্লিক করে ছবি জুম করে দেখতে পারবেন। সবকিছু ঠিক থাকলে সাবমিট অপশনে ক্লিক করুন।"; "তথ্য সেভ করার জন্য এখান থেকে নিশিত করুন অপশনে ক্লিক করুন।"; "আউটলেটে একবার ছবি উঠানোর পর পুনরায় উক্ত আউটলেট সিলেক্ট করুন।"; "এরপর এখানে দেখতে পারবেন উক্ত আউটলেটে ছবি উঠানো হয়েছে। একটি আউটলেটে একবার ছবি তুলতে পারবেন।"
- **Data:** gift assignment (from TSO portal), gift-distribution photo (one per outlet).

## SR-S-54 Campaign Photo Capture (Diamond League gift verify)
- **Pages:** 69, 70
- **Title:** "Campaign Photo Capture"; slide title "Diamond League Gift Photo Capture" (p68 slide title "লয়ালতি")
- **Controls:** outlet dropdown ("Moin Store (DHK-344-005-[phone]-Apsis Cluster)"); alphabet strip; second dropdown "Diamond League (March) Gift Verify" (campaign/month; options not shown); gift cards per redeemed gift ("২ স্টেপ কিচেন র‍্যাক।", "চেয়ার।"), each with "Gift Distribute Image" and "Capture Photo"; thumbnail with delete + reload icons; "সাবমিট".
- **Dialogs:** "আপনি কি নিশ্চিত?" + "হ্যাঁ"/"না"; success "Campaign details saved successfully." + "ওকে".
- **Rules (stated):** "যেসকল আউটলেট এর জন্য গিফট রিডিম করা হয়েছিলো ঐ সকল আউটলেট দেখতে পাবেন। আউটলেট নির্বাচন করার পর আউটলেটে গিফট এর নাম গুলো দেখতে পাবেন। এবার গিফট এর ছবি উঠানোর জন্য Capture Photo তে ক্লিক করুন।"; same camera/delete/reload/zoom text as SR-S-53; "তথ্য সেভ করার জন্য এখান থেকে হ্যাঁ অপশনে ক্লিক করুন।" Submit available with one of two gift photos missing (observed; F-30).

## SR-S-55 Sales Deposit ("বিক্রয় জমা") - end-of-day sync and submit
- **Pages:** 71, 72, 73
- **Title:** "বিক্রয় জমা" [EN: Sales deposit/submit] (shield icon); process "বিক্রয় জমা করার প্রক্রিয়া"
- **Controls:** back chevron; "ডিভাইস স্ট্যাটাস" [Device status] green dot "অনলাইন" [Online] (offline state never shown); reconciliation table "বিষয়" | "ডিভাইস" | "সার্ভার" with rows "আউটলেট" (১/১), "বিক্রয়" (৬৮/৬৮), "স্টক" (১৯,৭০০/১৯,৭০০), "কিউসি" [QC] (০/০), "প্রমোশন" [Promotion] (০/০) (p72 right shows another data set: ১/১, ৩৮/৩৮, ১৪,০০০/১৪,০০০, ০/০, ০/০); red notice "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।"; button "ডাটা সিঙ্ক করুন" [Sync data] (red-purple, sync icon, enabled); button "বিক্রয় জমা" (grey/disabled before sync, blue/enabled after).
- **Dialogs:** pending-dues warning: yellow triangle, bold blue "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" (number = dynamic count of retailers with dues), green "হ্যাঁ", red "না"; success: green tick "বিক্রয় সফল ভাবে জমা হয়েছে" + "ওকে".
- **Rules (stated):** "সকল আউটলেটে বিক্রয় এর কাজ শেষে সকল ডাটা আপলোড করার জন্য ডিভাইসের ড্যাশবোর্ড থেকে “বিক্রয় জমা” অপশনে ক্লিক করুন।"; "সারাদিনে আপনার বিক্রয় করা সকল তথ্য সঠিক থাকলে “ডাটা সিঙ্ক করুন” অপশনে ক্লিক করুন। এবং প্রক্রিয়াটি সম্পূর্ণ হওয়া পর্যন্ত অপেক্ষা করুন।"; "সিঙ্ক এর কাজ সম্পূর্ণ হয়ে গেলে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে। এরপর “বিক্রয় জমা” অপশনে ক্লিক করুন।"; "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে। এরপর প্রক্রিয়াটি সম্পূর্ণ হওয়ার জন্য কিছুক্ষণ অপেক্ষা করুন"; "এরপর আপনার উক্ত রুট এ যদি কোন দোকানের বাকি পরিশোধ অবশিষ্ট থাকে তাহলে এই মেসেজ দেখতে পারবেন। আপনি চাইলে হ্যাঁ অপশনটি নির্বাচন করে বিক্রয় জমা দিতে পারবেন। অথবা আপনি না অপশন নির্বাচন করে পুনরাই বাকি পরিশোধ করতে পারবেন।"; "প্রক্রিয়াটি সম্পূর্ণ হয়ে গেলে Success Alert দেখতে পারবেন এরপর “ওকে” অপশন এ ক্লিক করে ডাটা আপলোড এর কাজ সম্পূর্ণ করুন।" (p72 "Success Alert" -> after "OK" the upload is complete)
- **Hardware:** network. **Data:** device-vs-server counts; day's sales deposit; dues count. **Offline/online:** ONLINE (stated by Device Status line and the sync button); this is the only explicit upload point documented.

## SR-S-56 Tutorial
- **Pages:** 74
- **Title:** "টিউটোরিয়াল" [EN: Tutorial] (monitor/video icon); process "টিউটোরিয়াল ভিডিও দেখার প্রক্রিয়া"
- **Content:** back chevron; empty state in red box "কোনো টিউটোরিয়াল পাওয়া যায়নি।" [No tutorial was found.] (populated layout never shown).
- **Rules (stated):** "টিউটোরিয়াল ভিডিও দেখার জন্য ড্যাশবোর্ড থেকে টিউটোরিয়াল অপশনে ক্লিক করুন।"; "টিউটোরিয়াল অপশনে ক্লিক করার পর এই সেকশান এ ভিডিও দেখতে পারবেন।"
- **Data:** tutorial video list (server/admin content).

## SR-S-57 Task Delegation (AMO-assigned tasks)
- **Pages:** 75, 76
- **Title:** "টাস্ক" [EN: Task]; tile "টাস্ক ডেলিগেশন"
- **Content:** back chevron; task cards: red type tag "OOS"; outlet name ("Babu Store"); status at right "চলমান" [Ongoing, orange] / "সম্পন্ন" [Completed, green]; description "Babu Store দোকানে Maxim এ OOS আছে" / "Babu Store দোকানে Avon এ OOS আছে"; footer flag icon "Completion on: 2025-11-30" (English label). Swipe card left-to-right reveals red panel with circle-check icon and "Resolve" (English); tap Resolve -> status "সম্পন্ন", card stays in place. No confirmation, note, photo or toast.
- **Rules (stated):** "AMO থেকে SR ইউজার এর জন্য যতগুলো টাস্ক বরাদ্দ করা আছে এই সংখ্যা দেখতে পারবেন নটিফিকেশন আকারে। নিজের টাস্ক দেখার জন্য ড্যাশবোর্ড থেকে টাস্ক ডেলিগেশন অপশনে ক্লিক করুন।"; "এরপর ইউজার এই সেকশনে AMO থেকে বরাদ্দকৃত টাস্ক লিস্ট আকারে দেখতে পারবেন।"; "টাস্ক সম্পন্ন হয়ে গেলে বাম থেকে ডান দিকে Swipe করুন। এরপর Resolve অপশনে ক্লিক করুন।"; "Resolve অপশনে ক্লিক করার পর টাস্ক এর স্ট্যাটাস পরিবর্তন হয়ে যাবে এবং সম্পন্ন দেখাবে।"
- **Roles:** SR resolves; AMO assigns. **Data:** task (type, outlet, brand text, completion date, status); badge count.

## SR-S-58 Settings
- **Pages:** 77, 78
- **Title:** "সেটিং" [EN: Settings]; process (p78) "ভাষা পরিবর্তন এবং ডাটা ফাইল পাঠানোর প্রক্রিয়া"
- **Entry:** dashboard gear icon. **Controls:** back chevron; three tiles: (1) language selector flag + "বাংলা" + caret -> dropdown with two options each with a flag: "en" and "বাংলা" (callout calls it "En & বাংলা" button); (2) "PDA টু সাপোর্ট" [PDA to Support]; (3) "লগ আউট" [Log Out].
- **PDA to Support result:** modal, green tick, title "সফল", body "ডাটা ফাইল পাঠানো হয়েছে।" [The data file has been sent.], "ওকে". No confirmation before sending, no progress.
- **Rules (stated):** "ভাষা পরিবর্তন করার জন্য, সিঙ্ক ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য, এবং Logout করার জন্য “সেটিং” বাটনে ক্লিক করুন।"; "“সেটিং” বাটনে ক্লিক করার পর ইউজার এই তিনটি অপশন পাবেন।"; "“En & বাংলা” বাটনের মাধ্যমে আপনি Application এর ভাষা পরিবর্তন করতে পারবেন।"; "ডাটা ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য “PDA টু সাপোর্ট” বাটনে ক্লিক করুন।" No confirmation/restart on language change. No profile, password change, version, printer, notification or sync settings exist on this screen.
- **Data:** language preference (local), sync/data file (sent to support).

## SR-S-59 Logout confirmation
- **Pages:** 79
- **Title:** "লগ আউট করার প্রক্রিয়া" [EN: Process of logging out]
- **Content:** info icon; blue text "আপনি কি নিশ্চিত যে লগআউট করতে চান?" [Are you sure you want to log out?]; green "হ্যাঁ", red "না".
- **Rules (stated):** "ডিভাইস থেকে Logout করার জন্য Logout অপশনে ক্লিক করুণ।"; "এরপর “হ্যাঁ” অপশনটি নির্বাচন করে Logout সম্পূর্ণ করে ফেলুন।" Not stated: blocked when unsynced data exists, local wipe, destination screen (login implied).

## SR-S-60 Summary ("সারসংক্ষেপ") - NO MANUAL PAGE (stub)
- **Pages:** appears only as a dashboard tile (row 2, col 1) on p10, 16, 40, 44, 46, 48, 53, 61, 64, 68, 71, 74, 75, 77. No page documents it.
- **Action:** the rebuild must discover its content from the live app/screenshots (sponsor said screenshots will follow). Listed so it is not silently dropped.

---------------------------------------------------------------------
Screens not shown anywhere in the manual but referenced (for completeness): "Redemption" success follow-up; AV/KV after "না" on call prompt; post-"জমা দিন" navigation on Slide; final screens of the sale-edit flow after "এগিয়ে যান" (SR-S-42); loading/progress UI for Sync Data; any "Sales deposit" failure state.

---------------------------------------------------------------------
# SECTION B - FLOW LIST (end-to-end workflows)
---------------------------------------------------------------------
Tags: **(stated)** in the manual, **(page-order)** inferred only from slide order, **(gap)** the manual does not show the step. Alternate paths are listed under each flow.

## SR-F-01 Install and first login (new device)
1. Open file manager, tap APK `aron_sr_app_..._v1.apk` -> installer "Install" (SR-S-01) (p2). 2. "Done" or "Open" (p3); launcher icon "ARON SR". 3. Login: username + password -> "লগইন" (SR-S-02) (p4). 4. Location permission "While using the app" (SR-S-03) (p4). 5. OTP screen appears (new device / new version) (SR-S-04) -> TSO supplies OTP (SR-F-02) -> enter 4 digits -> "Verify" (p5-6). 6. If a newer version exists -> SR-F-03. 7. Data processing screen (SR-S-09) then Dashboard (SR-S-10).
- Alternates: "Cancel" at installer; "Don't allow"/"Only this time" on location (consequence not stated - gap); wrong credentials/OTP (no message shown - gap).

## SR-F-02 OTP retrieval by TSO (cross-role)
1. SR reaches OTP screen (SR-S-04). 2. TSO logs into TSO web portal, opens last menu item "SR Device OTP" (SR-S-05). 3. Selects Wing/Division/Territory/Distribution House/Zone (each defaults "All Selected (1)"), taps "View". 4. Finds the SR row (Field Force ID, Name, Username, Zone, Create Time, OTP). 5. Tells the 4-digit OTP to the SR, who types it and taps "Verify" (p5-6).

## SR-F-03 Forced app update
1. Update page (SR-S-06): "Download App & Install". 2. Auto-redirect to "Install unknown apps" (SR-S-07): turn ON for ARON SR. 3. Download progress "Downloading n%"; keep internet on, do not close app. 4. "Download completed" -> installer "Do you want to update this app?" -> "Update". 5. "App installed." -> "Open". 6. Forced splash (SR-S-08) -> "আপডেট". 7. Data update "প্রসেসিং(n/87)" (SR-S-09). 8. Dashboard (SR-S-10) (p7-10).
- Alternate: no skip path exists on SR-S-08 (stated "অবশ্যই"); "Cancel" on installer dialog (consequence not stated).

## SR-F-04 Start of day: check-in
1. Dashboard -> "অ্যাটেনডেন্স" (SR-S-12) (p12). 2. Current address auto-shown; if it fails tap Refresh. 3. Tap "চেক ইন" -> sheet (SR-S-13): verify time chip, press and hold the green button (p13). 4. State becomes "চেক ইন সম্পন্ন হয়েছে"; check-in disabled; check-out box shows "চেক আউট ৫টার পরে সক্রিয় হবে" (p14).

## SR-F-05 End of day: check-out
1. Attendance (SR-S-12) after 5 PM: "চেক আউট" turns active (p14 note "বিকাল ৫ ঘটিকা থেকে"). 2. Tap "চেক আউট" -> sheet (SR-S-14): check time, press and hold the red button. 3. State F: "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" (p15).
- Alternate: before 5 PM -> control disabled (stated).

## SR-F-06 Printer pairing and connection (one-time per phone/printer, repeated if disconnected)
1. Android Settings -> Bluetooth ON; printer power ON (SR-S-15) (p16). 2. Scan; tap "RPP02N" in Available devices (p17). 3. PIN dialog: enter 0000 -> "Pair" (p17). 4. Verify "RPP02N" under Paired devices (p18). 5. Open ARON SR -> dashboard "স্টক" (SR-S-17) (p18). 6. Allow "Nearby devices" permission (SR-S-16) (p19). 7. Tap red slashed printer icon -> banner "প্রিন্টার কানেক্ট করা হয়েছে", icon green (p19-20).
- Alternates: permission "Don't allow" -> printer won't connect (stated), no in-app message shown (gap); wrong PIN (system).

## SR-F-07 Daily stock-taking
1. Dashboard -> "স্টক" (SR-S-17) (p20-21). 2. For each SKU set the issue quantity with "-"/"+" or by typing. 3. Check category totals (সিগারেট, বিড়ি, লাইটার, ম্যাচ). 4. "সংরক্ষণ" -> Stock column fills. 5. Optional "প্রিন্ট" -> stock memo to RPP02N (needs green printer icon).
- Alternate: no validation/confirmation shown (gap).

## SR-F-08 Normal sale (in-range outlet) - from outlet select to print
1. Dashboard -> "বিক্রয়" (SR-S-18) (p22). 2. Open dropdown / alphabet strip; pick the outlet (SR-S-19). 3. Geofence check shown on this screen; if in range no warning appears (page-order; the in-range screen is never shown, F-13). 4. Start-call prompt "আপনি কি কল শুরু করতে চান?" -> "হ্যাঁ" (SR-S-25) (p31). 5. AV (if any) then KV (if any) -> "বন্ধ করুন" (SR-S-29/30) (p32) [page-order position]. 6. Survey (POSM Availability) if configured: Q1 হ্যাঁ/না; Q1.1 photo; "সাবমিট →" -> confirm "হ্যাঁ" (SR-S-31) (p33) [page-order]. 7. Sale entry (SR-S-32): enter quantity of sticks per SKU (stepper or typing) (p34). 8. Optional slide collection via "স্লাইড সংগ্রহ" (SR-F-12). 9. "এগিয়ে যান →" -> Review (SR-S-33): verify sale and slide quantities/amounts (p34). 10. Optional: tick credit checkbox (SR-F-10); optional Product QC (SR-F-13). 11. Tap "প্রিন্ট" -> "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে" -> "হ্যাঁ" (SR-S-37) (p38). 12. "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" -> "হ্যাঁ" prints memo on RPP02N (p39). 13. "সফল / বিক্রয় সফল ভাবে জমা হয়েছে" -> "ওকে" (p39).
- Alternates: "না" at confirm (stays, implied); "না" at print prompt (effect not stated - gap; memo can be printed later from Memo, p34/p38/p43); "না" at start-call prompt (effect not stated); printer not connected (red slashed icon; behaviour not stated); Sale History / Points available any time from SR-S-19 (SR-F-14, SR-F-15); back arrow from Review (not described).

## SR-F-09 Force sale (outside geofence)
1. Select outlet (SR-S-19): warning "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।" with "ফোর্স সেল" and "রিফ্রেশ" (p23, 25, 41). 2. Optional "রিফ্রেশ" re-reads location and re-evaluates. 3. Tap "ফোর্স সেল" -> "আপনার ফোর্স সেলের কারণ কী?" -> choose one: "ইন্টারনেট সমস্যা" or "লোকেশন চেঞ্জ" (SR-S-22). 4. Camera opens (first time: Camera permission then Audio permission, "While using the app", SR-S-23) (p26). 5. Take one outlet photo with the shutter (SR-S-24); outlet's location information is updated at photo capture (p27/42). 6. "আপনি কি কল শুরু করতে চান?" -> "হ্যাঁ" (SR-S-25) (p42). 7. Continue as SR-F-08 steps 5-13 (SKU entry or zero sale).
- Alternates: "X" in camera (consequence not stated); location OFF (must be ON, stated p25/41); permission denied (gap).

## SR-F-10 Credit / partial-payment sale
1. On Review (SR-S-33) tick "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" (p34). 2. Dialog "পরিশোধিত টাকার পরিমাণ লিখুন" (SR-S-34): enter collected amount (must be less than grand total); live "বাকিঃ x" (p35). 3. "হ্যাঁ" -> checkbox checked, label "Baki ৬১ টাকা". 4. Continue to Print/commit (SR-F-08 step 11-13).
- Alternates: "না" (presumably unticks - not stated); amount >= total (error text not shown); full payment = leave unticked (non-credit path, inferred).

## SR-F-11 Zero sale (visit with no sale)
1. Select outlet; if outside range do force sale (SR-F-09) first. 2. Answer "হ্যাঁ" to start-call (p42). 3. On the sale footer tap "এগিয়ে যান →" with no SKU quantity (p42). 4. Dialog "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি / আপনি কি জিরো (০) বিক্রয় করতে চান?" -> "হ্যাঁ" (SR-S-38) (p43). 5. Review shows "সর্বমোট ০ ০.০০" (no credit checkbox) (SR-S-33) (p43). 6. Tap "প্রিন্ট" to complete (p43). 7. Same confirm/print/success dialogs expected (SR-S-37) (page-order; p43 shows only step 6).
- Alternate: "না" at zero-sale dialog returns to selection (implied); Product QC still available on zero-sale review (observed).

## SR-F-12 Slide / DRP empty-pack collection
1. From sale footer tap "Collect DRP Discount" (p27/31) or "স্লাইড সংগ্রহ" (p34). 2. Slide screen (SR-S-26): SKU rows with OFFER ribbons. 3. Tap "OFFER" -> offer detail (SR-S-27): text + validity -> "বন্ধ করুন". 4. Enter empty-pack quantity per SKU via "-"/"+" or tap the value -> manual dialog (SR-S-28) with shortcuts +/-1,5,10,20,50 -> "সংরক্ষণ". 5. Quantities show on the row (e.g. ১০). 6. "জমা দিন" saves slide data (p30). 7. Return to sale -> Review shows "স্লাইড" section and deducts value (SR-S-33) (p34).
- Alternate: outside validity window / over-collection (not stated).

## SR-F-13 Product QC (defective cigarettes) - from the review screen
1. On Review tap yellow "প্রোডাক্ট QC" (p36). 2. QC screen (SR-S-35): choose SKU tile from carousel. 3. QC entry (SR-S-36): enter stick counts per fault type (production / transport); read Max QC / done / remaining; "সংরক্ষণ" (also "বাতিল", "ডিলিট") (p37). 4. Back at QC summary: one row per SKU "MFC Fault,MKT Fault ১০", totals "মোট QC নিষ্পত্তি", "সেটেলমেন্ট পরিমান" (p37). 5. "QC জমা দিন →" -> "ইনফর্মেশন / আপনি কি QC জমা দিতে চান?" -> "হ্যাঁ" (p38). 6. Return to Review; QC shows as a red deduction line and in memo "মোট QC" (p38, p44).
- Alternates: "না" (stays); empty summary "QC করার জন্য কোন পণ্য নেই"; after QC the outlet's sale cannot be edited (stated p46).

## SR-F-14 View previous sales of an outlet (Sale History) - ONLINE
1. Select outlet in Sale (SR-S-19) -> "Sale History" (or green "পূর্বের সেল ডাটা দেখুন" on SR-S-32) (p23, p34). 2. "সেল ডাটা" (SR-S-20): choose date from picker; table SKU/quantity/value + total. Mobile data must be ON (stated).

## SR-F-15 View outlet Diamond League points
1. Sale -> select outlet -> "Points" (SR-S-19) (p24). 2. "পয়েন্ট" (SR-S-21): card "Diamond League (April)" points, expiring points, expiry date. 3. Optionally "রিডিম্পশন" -> SR-F-16.

## SR-F-16 Gift redemption
1. Entry A: Dashboard -> "লয়্যালটি পয়েন্ট" -> "রিডিম্পশন" (SR-S-50) (p61). Entry B: Points screen -> "রিডিম্পশন" (SR-S-21) (p24). 2. Gift Redemption (SR-S-51): pick outlet; see "Diamond League (month)" and "Pts Remaining". 3. Use "+"/"-" on gift cards (cash-back card: 1 point = 2 taka, max 199 points as cash). 4. "Confirm Redemption" -> "Confirm redemption of these items? Points will be deducted." -> "নিশ্চিত করুন" (or "বাতিল") (p63). 5. "Redemption successful" -> "ওকে".

## SR-F-17 Astha gift distribution photo
1. Dashboard -> "Photo Capture" -> "আস্থা" (SR-S-52) (p64). 2. Astha Photo Capture (SR-S-53): pick outlet (only outlets with a TSO-portal-assigned gift); gift name shown (e.g. "27 pcs Dinner Set"). 3. "Capture Photo" -> camera (permissions as SR-S-23). 4. Review thumbnail: delete / reload / tap-to-zoom. 5. "সাবমিট" -> "আপনি কি নিশ্চিত?" -> "হ্যাঁ" -> "Photo capture details saved successfully." -> "ওকে" (p66). 6. Re-selecting the outlet later shows "Photo already captured." (one photo per outlet, p67).

## SR-F-18 Campaign (Diamond League) gift photo
1. Dashboard -> "Photo Capture" -> "Campaigns" (p68). 2. Campaign Photo Capture (SR-S-54): pick outlet (only outlets with redeemed gifts); pick campaign "Diamond League (March) Gift Verify". 3. For each redeemed gift tap "Capture Photo", review (delete/reload/zoom). 4. "সাবমিট" -> "আপনি কি নিশ্চিত?" -> "হ্যাঁ" -> "Campaign details saved successfully." -> "ওকে" (p70).

## SR-F-19 Credit dues settlement (pay dues)
1. Dashboard -> "মেমো" (SR-S-39) (p44). 2. Choose outlet whose dropdown row shows "x ৳ বাকি" (red). 3. Memo shows red box "এই বিক্রয়টি বাকিতে করা হয়েছে।". 4. "পরিশোধিত করুন" -> "আপনি কি নিশ্চিত?" -> "হ্যাঁ" (SR-S-40) (p45). 5. "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" -> "ওকে"; the বাকি tag disappears from the outlet entry.

## SR-F-20 Memo view and reprint
1. Dashboard -> "মেমো" (SR-S-39). 2. Pick outlet; memo displayed. 3. "প্রিন্ট" (or round printer button) reprints the memo on the printer (p44 info card: "এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন").
- Alternate: printer disconnected (red slashed icon) - behaviour not shown.

## SR-F-21 Edit a sale after the fact
1. Dashboard -> "মেমো" -> select outlet (p46). 2. Preconditions (stated): SR is inside the outlet's geofence; outlet has no QC. 3. Tap "এডিট" -> dialog "দয়া করে বিক্রয় এডিট করার কারন লিখুন" -> choose one of three reasons (e.g. "ভুল SKU নির্বাচিত।") -> "জমা দিন" (SR-S-41) (p47). 4. Sale page opens pre-filled (SR-S-42): re-enter correct quantities -> "এগিয়ে যান →". 5. Review/commit (gap: not shown; assumed as SR-F-08 step 9-13).
- Alternates: "বাতিল" in reason dialog; outside geofence or QC done -> edit not allowed (error text not shown - gap).

## SR-F-22 Astha targets and achievements
Route view: Dashboard -> "আস্থা" (SR-S-43) -> "রুটের তথ্য" -> choose "বছর" and "কোয়ার্টার" -> months chips (1-3) -> sub-tab "এসটিডি টার্গেট" (default) / "মেমো টার্গেট" (p48-49).
Outlet view: tab "দোকান ভিত্তিক তথ্য" (SR-S-44) -> tap a retailer card -> SR-S-45 -> year, quarter, months -> STD / Memo target table (p50-52).

## SR-F-23 SKU target and achievement view
Dashboard -> target card "বিস্তারিত" (SR-S-11) (p11). Back to dashboard.

## SR-F-24 Register a new outlet
1. Dashboard -> "আউটলেট" -> "নতুন দোকান" (SR-S-46/47) (p53). 2. Select cluster; enter shop name, owner name, mobile number. 3. "GEO এবং ছবি ধারণ করুন" -> camera -> one photo (p54). 4. "সংরক্ষণ" -> "সফল" -> "ওকে" (p55). No confirm dialog.

## SR-F-25 Permanently close an outlet
Dashboard -> "আউটলেট" -> "স্থায়ী বন্ধ" -> select cluster -> select outlet (read-only name/owner/mobile appear) -> "সংরক্ষণ" -> "আপনি কি নিশ্চিত? / এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" -> "হ্যাঁ" -> "সফল" -> "ওকে" (p56-57).

## SR-F-26 Change outlet information
Dashboard -> "আউটলেট" -> "তথ্য পরিবর্তন করুন" -> select cluster and outlet (fields pre-fill) -> edit name/owner/mobile -> "GEO এবং ছবি ধারণ করুন" (must) -> camera one photo -> "ছবি ধারণ করা সম্পন্ন হয়েছে" -> "ওকে" -> "সংরক্ষণ" -> "আপনি কি নিশ্চিত? / এই পরিবর্তন সংরক্ষণ করা হবে" -> "হ্যাঁ" -> "সফল" -> "ওকে" (p58-60).

## SR-F-27 End-of-day sync and Sales Deposit
1. After all outlets (and dues) are done, Dashboard -> "বিক্রয় জমা" (SR-S-55) (p71). 2. Check Device Status "অনলাইন". 3. Tap "ডাটা সিঙ্ক করুন" and wait for completion (p71). 4. Compare Device vs Server for Outlet, Sales, Stock, QC, Promotion (p71). 5. "বিক্রয় জমা" becomes enabled (p72). 6. Tap it: if retailers still owe, dialog "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" -> "হ্যাঁ" submits anyway; "না" returns to collect dues (SR-F-19) (p72). 7. Wait; "বিক্রয় সফল ভাবে জমা হয়েছে" -> "ওকে" completes the upload (p73).
- Alternates: sync fails/offline (not shown - gap); conflicting statement on whether dues settlement is a hard precondition (F-37).

## SR-F-28 Task resolution
Dashboard badge (e.g. "২") -> "টাস্ক ডেলিগেশন" -> "টাস্ক" list (SR-S-57) -> swipe card left-to-right -> "Resolve" -> status "সম্পন্ন" (p75-76). No confirm.

## SR-F-29 Tutorial viewing
Dashboard -> "টিউটোরিয়াল" (SR-S-56) -> list (shown empty: "কোনো টিউটোরিয়াল পাওয়া যায়নি।") (p74).

## SR-F-30 Settings: change language
Dashboard gear -> "সেটিং" (SR-S-58) -> language tile -> choose "en" or "বাংলা" (p77-78). No confirmation/restart.

## SR-F-31 Settings: send data file to support
Dashboard gear -> "PDA টু সাপোর্ট" -> "সফল / ডাটা ফাইল পাঠানো হয়েছে।" -> "ওকে" (p78). No confirm, no progress.

## SR-F-32 Logout
Dashboard gear -> "লগ আউট" -> "আপনি কি নিশ্চিত যে লগআউট করতে চান?" -> "হ্যাঁ" (SR-S-59) (p79).

## SR-F-33 Typical day (page-order synthesis; the manual does not give one master sequence)
Install/login/OTP (once per device or version) -> check-in (SR-F-04) -> connect printer (SR-F-06) -> stock (SR-F-07) -> per outlet: sale/force-sale/zero-sale (SR-F-08/09/11) with optional slide, QC, credit, survey, points/redemption -> memo corrections / dues (SR-F-19/21) -> tasks (SR-F-28) -> sync + sales deposit (SR-F-27) -> check-out after 5 PM (SR-F-05) -> logout (SR-F-32).

---------------------------------------------------------------------
# SECTION C - RULES REGISTER
---------------------------------------------------------------------
One row per business rule, validation, restriction or observed behaviour. "Basis": **S** = stated in manual text (exact wording quoted where the manual words it), **O** = observed in a screenshot only, **I** = inferred (needs confirmation). Where the manual gives no Bangla wording the "Exact wording" cell says "(none)".

| ID | Rule | Exact wording (verbatim) | Page | Screens | Basis |
|---|---|---|---|---|---|
| SR-R-001 | App is installed by sideloading an APK from the file manager, not from a store | "ARON SR অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার জন্য প্রথমে ফাইল ম্যানেজার ওপেন করুন।" | 2 | S-01 | S |
| SR-R-002 | Each SR has an individual username and password | "নির্দিষ্ট SR এর জন্য, তাদের নির্দিষ্ট ইউজারনেম এবং পাসওয়ার্ড দিয়ে, "লগইন" বাটনে ক্লিক করুন।" | 4 | S-02 | S |
| SR-R-003 | Location permission is mandatory; choose "While using the app" | "অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যই ইউজারকে লোকেশন পারমিশন দিতে হবে।" | 4 | S-03 | S |
| SR-R-004 | Default location accuracy offered is Precise | (none; Precise highlighted by default) | 4 | S-03 | O |
| SR-R-005 | OTP verification is required on the first login on a device | "এই OTP একটি ডিভাইসে প্রথমবার লগইন করার সময় দিতে হবে।" | 5 | S-04 | S |
| SR-R-006 | OTP is also required after installing a new app version | "Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process." | 5, 6 | S-04 | S |
| SR-R-007 | OTP is 4 digits and is provided by the TSO (not SMS) | "Enter the 4-digit OTP provided by your TSO." | 5 | S-04, S-05 | S |
| SR-R-008 | TSO finds the OTP in portal menu "SR Device OTP" (last menu item) | "TSO পোর্টালে লগইন করার পর মেনু লিস্ট এর সর্বশেষ মেনু "SR Device OTP" তে ক্লিক করুন।" | 5 | S-05 | S |
| SR-R-009 | OTP list is filtered by Wing, Division, Territory, Distribution House, Zone (default "All Selected (1)") and shown via "View" | "এরপর যে টেরিটোরি বা জোন এর OTP প্রয়োজন সেটি নির্বাচন করে "View" অপশনে ক্লিক করুন। এরপর আপনার উক্ত টেরিটোরি বা জোন এর সকল রুট এর OTP লিস্ট দেখতে পারবেন।" | 6 | S-05 | S |
| SR-R-010 | SR must take the correct OTP for the correct SR/route from the list | "উক্ত লিস্ট থেকে সঠিক রুট এর জন্য সঠিক OTP নিয়ে Enter OTP ফিল্ডে ইনপুট করুন।" | 6 | S-04, S-05 | S |
| SR-R-011 | OTP list is per SR (one row per Field Force/username) with creation time | (none; table columns Sr No., Field Force ID, Field Force Name, Username, Zone ID, Zone, Create Time, OTP) | 6 | S-05 | O |
| SR-R-012 | Release 1.0.25 includes: new promotion modality, login-time 2FA verification, sync file update without deleting | "New promotion modality., Login time 2FA verification., Sync file update without deleting." | 7 | S-06 | S |
| SR-R-013 | "Install unknown apps" must be enabled for ARON SR to install the update | "...এই পেজ থেকে ARON SR এর বাটনটিতে ক্লিক করে অন করে দিন।" | 7 | S-06, S-07 | S |
| SR-R-014 | Keep internet on and wait for the download; do not close the app while downloading | "ডাউনলোড সম্পন্ন হওয়া পর্যন্ত অপেক্ষা করুন। এবং ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন।" / "Please do not close the app while downloading" | 8 | S-06 | S |
| SR-R-015 | Update is mandatory to keep using the app; splash has no skip | "কিছু নতুন আপডেট পাওয়া গেছে, এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" | 9 | S-08 | S |
| SR-R-016 | After update the app processes data (n of 87 units); do not close the app | "অ্যাপ আপডেট হচ্ছে" / "প্রসেসিং(১৪/৮৭)" / "টিপস: অ্যাপটি বন্ধ করবেন না" | 10 | S-09 | S |
| SR-R-017 | Dashboard header shows SR name (username), route name (with visit days in brackets on some accounts) and date in ISO YYYY-MM-DD | (none) | 10, 12, 44, 71, 74 | S-10 | O |
| SR-R-018 | Dashboard tile set differs per account ("লয়ালটি পয়েন্ট" and "Photo Capture" missing on SR-16858) | (none) | 10 vs 44, 71, 74 | S-10 | O |
| SR-R-019 | Outlet visit KPI is visited/total outlets of the day; non-visit = total - visited | (none; "০/৩৭" and "৩৭") | 10, 53 | S-10 | I |
| SR-R-020 | Task badge on the "টাস্ক ডেলিগেশন" tile shows the number of AMO-assigned tasks | "AMO থেকে SR ইউজার এর জন্য যতগুলো টাস্ক বরাদ্দ করা আছে এই সংখ্যা দেখতে পারবেন নটিফিকেশন আকারে।" | 75 | S-10, S-57 | S |
| SR-R-021 | Target card % is not capped (shows ২৭১৯%) | (none) | 10, 16 | S-10 | O |
| SR-R-022 | SKU target card: Remaining = Target - Achieved; Attainment = Achieved / Target | (none; "টার্গেট/অ্যাচিভ/বাকি/অর্জন") | 11 | S-11 | I |
| SR-R-023 | Check-in is expected before starting work | "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।" | 12, 13 | S-12 | S |
| SR-R-024 | Current location/address is shown on Attendance; Refresh retries a failed location read | "বর্তমান লোকেশন পেতে কোনো সমস্যার সম্মুখীন হইলে Refresh বাটনে ক্লিক করুন।" | 12 | S-12 | S |
| SR-R-025 | Check-in is confirmed by press-and-hold after verifying the displayed time | "এরপর চেক ইন এর সময় চেক করুন। তারপর সবুজ বাটনটি চাপ দিয়ে ধরে রাখুন।" | 13 | S-13 | S |
| SR-R-026 | After check-in the check-in button is disabled and the status changes (once per day) | "চেক ইন সম্পন্ন হয়ে যাবার পর চেক ইন বাটনটি Disable হয়ে যাবে এবং চেক ইন স্ট্যাটাস পরিবর্তন হয়ে যাবে।" | 14 | S-12 | S |
| SR-R-027 | Check-out is available only from 5 PM | "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।" / "চেক আউট ৫টার পরে সক্রিয় হবে" | 14 | S-12 | S |
| SR-R-028 | Check-out is confirmed by press-and-hold after verifying the displayed time | "এরপর চেক আউট এর সময় চেক করুন। তারপর লাল বাটনটি চাপ দিয়ে ধরে রাখুন।" | 15 | S-14 | S |
| SR-R-029 | After check-out the check-out button is disabled and status changes; day is complete | "চেক আউট সম্পন্ন হয়ে যাবার পর চেক আউট বাটনটি Disable হয়ে যাবে এবং চেক আউট স্ট্যাটাস পরিবর্তন হয়ে যাবে।" / "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" | 15 | S-12 | S |
| SR-R-030 | Check-out should be done before finishing the day's work | "আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" | 14 | S-12 | S |
| SR-R-031 | Printer prerequisites: phone Bluetooth ON and printer power ON | "...ডিভাইস এর Setting এ যেয়ে Bluetooth অন করে নিতে হবে। এবং Mobile Printer এর Power অন করে নিতে হবে।" | 16 | S-15 | S |
| SR-R-032 | Printer is paired in Android Bluetooth settings; model "RPP02N", PIN 0000 | "এরপর Pin Section এ (0000) এর পিন নাম্বারটি প্রদান করুন এবং Pair বাটনটিতে ক্লিক করুন।" | 17 | S-15 | S |
| SR-R-033 | Bluetooth "Nearby devices" permission must be allowed or the printer will not connect | "ইউজারকে অবশ্যই এই Permission টি Allow অপশনে ক্লিক করে Allow করতে হবে। অন্যথাই প্রিন্টার Connect হবে না।" | 19 | S-16 | S |
| SR-R-034 | In-app printer connection is made by tapping the printer icon on the Stock screen | "এরপর ARON SR অ্যাপ্লিকেশান এর সাথে প্রিন্টার এর সংযোগ স্থাপন করার জন্য ড্যাশবোর্ড থেকে স্টক অপশনে ক্লিক করুন।" / "এবার ইউজারকে প্রিন্টার আইকন এর উপর ক্লিক করতে হবে।" | 18, 19 | S-16, S-17 | S |
| SR-R-035 | Connected state = green banner + green printer icon; disconnected = red slashed icon | "প্রিন্টার এর কানেকশান সম্পূর্ণ হয়ে যাবার পর ইউজার প্রিন্টার কানেক্ট করা হয়েছে একটি Alert দেখতে পারবেন। এবং প্রিন্টার আইকনটি সবুজ বর্ণের হয়ে যাবে।" | 19, 20 | S-16, S-17, S-33, S-39 | S |
| SR-R-036 | Stock-taking is a daily activity | "প্রতিদিনের মত স্টক নেওয়ার জন্য “স্টক” বাটনে ক্লিক করুন।" | 20 | S-17 | S |
| SR-R-037 | Stock is taken per SKU and checked per category before saving | "এরপর এসকেইউ অনুযায়ী স্টক নিন এবং ক্যাটাগরি অনুযায়ী স্টক চেক করুন। স্টক নেওয়া শেষে “সংরক্ষণ” অপশনে ক্লিক করে ডাটা সংরক্ষণ করুন।" | 21 | S-17 | S |
| SR-R-038 | Stock memo can be printed after save | "এরপর “প্রিন্ট” অপশনে ক্লিক করে স্টক এর মেমো প্রিন্ট করতে পারবেন।" | 21 | S-17 | S |
| SR-R-039 | Category "মোট ইস্যু" equals sum of SKU issues in that category; after Save "স্টক" = issue | (none; 6,500+6,000=12,500) | 21 | S-17 | O |
| SR-R-040 | Issue quantity changed by "-"/"+" or by typing; minimum shown 0 | (none) | 21 | S-17 | O |
| SR-R-041 | SKU badge on stock/sale cards may be packets (issue / pack size) | (none; 6,500 -> 650, 6,000 -> 300) | 21 | S-17 | I |
| SR-R-042 | A retailer must be selected to continue a sale | "বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন" | 22, 40 | S-18 | S |
| SR-R-043 | Outlet is chosen from a dropdown, then "এগিয়ে যান" | "দোকানের নাম সিলেক্ট করার জন্য ড্রপডাউন অপশন টাচ করুন। এরপর নামের তালিকা থেকে নির্ধারিত দোকান নির্বাচন করুন। এরপর “এগিয়ে যান” অপশনে ক্লিক করুন।" | 22, 40 | S-18 | S |
| SR-R-044 | Alphabet strip lists only the first letters present; letter chips filter the list (case-sensitive letters seen) | (none) | 22, 23, 40 | S-18, S-19, S-32, S-39, S-51, S-53, S-54 | I |
| SR-R-045 | Geofence: outside the outlet's defined range the warning is shown | "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।" | 23, 25 | S-19 | S |
| SR-R-046 | "ফোর্স সেল" is visible only when outside the defined range | "...নির্ধারিত রেঞ্জ এর বাহিরে থাকলে “ফোর্স সেল” অপশনটি দেখতে পাবেন।" | 25, 41 | S-19 | S |
| SR-R-047 | "রিফ্রেশ" re-reads location to re-evaluate the range | "লোকেশান এর তথ্য আপডেট করার জন্য “রিফ্রেশ” অপশনে ক্লিক করুন" | 25, 41 | S-19 | S |
| SR-R-048 | Mobile location must be ON | "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের লোকেশন অন রাখতে হবে।" | 25, 41 | S-19, S-22 | S |
| SR-R-049 | Sale History and Points remain available when out of range | (none) | 23, 24 | S-19 | O |
| SR-R-050 | Sale History needs mobile data ON (online only) | "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে।" | 23 | S-20 | S |
| SR-R-051 | Sale History can be viewed for any date chosen with the date picker | "এবং ইউজার যেকোন তারিখ এর সেল দেখতে পারবেন তারিখ নির্বাচন করার মাধ্যমে।" | 23 | S-20 | S |
| SR-R-052 | Points shown are those earned up to previous days | "বিগত দিন পর্যন্ত আউটলেট এর অর্জিত Diamond League Point দেখতে Point বাটনে ক্লিক করুন।" | 24 | S-21 | S |
| SR-R-053 | Points are grouped per league period with expiring points and expiry date | "এখান থেকে আউটলেট এর পয়েন্ট Expiring Point, এবং Expiry Date দেখতে পারবেন।" | 24 | S-21 | S |
| SR-R-054 | Points screen links to Redemption | "এখান থেকে রিডিম্পশান অপশনে ক্লিক করে রিডিম্পশান পেজে যেতে পারবেন।" | 24 | S-21, S-51 | S |
| SR-R-055 | Force sale needs exactly one reason of two: Internet problem / Location change | "...১। ইন্টারনেট সমস্যা, ২। লোকেশন চেঞ্জ এই ২টি অপশন থেকে প্রয়োজন অনুযায়ী একটি সিলেক্ট করবেন..." | 25, 41 | S-22 | S |
| SR-R-056 | After the reason, camera opens for an outlet photo | "...এরপর আউটলেটের ছবি উঠানোর জন্য “Camera” ওপেন হয়ে যাবে" | 25, 41 | S-22, S-24 | S |
| SR-R-057 | Camera permission and Audio permission must be granted as "While using the app" | "অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।" (camera) / same for audio | 26 | S-23 | S |
| SR-R-058 | Taking the outlet photo updates the outlet's location data | "...ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" | 27, 31, 42 | S-24 | S |
| SR-R-059 | A call (visit) is started by answering "হ্যাঁ" to the start-call prompt | "এরপর কল শুরু করার জন্য অপশন আসবে “হ্যাঁ” বাটনে ক্লিক করে আপনার কল শুরু করতে পারবেন।" | 31 | S-25 | S |
| SR-R-060 | DRP discount is collected through the "Collect DRP Discount" button | "এরপর DRP Discount সংগ্রহ করার জন্য Collect DRP Discount অপশনে ক্লিক করুন।" | 27 | S-25, S-26 | S |
| SR-R-061 | Cart total starts at ০.০০ before any quantity | (none) | 27, 31, 42 | S-25 | O |
| SR-R-062 | Slide offers have a validity period | "...ইউজার স্লাইড সংগ্রহের সময়সীমা এবং অফার এর তথ্য দেখতে পারবেন।" (Nov 25, 2025 -> Dec 30, 2025) | 28 | S-26, S-27 | S |
| SR-R-063 | Slide offer example: 100 stick worth of empty MaxR packs earns 1 pack MaxR 10s | "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s" | 28, 34 | S-27, S-33 | S |
| SR-R-064 | Only SKUs with an active offer carry the OFFER ribbon | (none) | 28 | S-26 | O |
| SR-R-065 | Slide quantity set by "-"/"+" or manual dialog; shortcuts +1/+5/+10/+20/+50 and -1/-5/-10/-20/-50; committed on dialog "সংরক্ষণ" | "এরপর এখান থেকে প্যাক অনুযায়ী ম্যানুয়াল এন্ট্রি করতে পারবেন সেইসাথে শর্টকাট ডাটা এর মাধ্যমেও এন্ট্রি করতে পারবেন। এন্ট্রি শেষে ‘সংরক্ষন’ অপশনে ক্লিক করুন।" | 29 | S-28 | S |
| SR-R-066 | "জমা দিন" saves the slide data | "এরপর ‘জমা দিন’ অপশনে ক্লিক করে স্লাইড এর ডাটা সেভ করুন।" | 30 | S-26 | S |
| SR-R-067 | AV is shown only if the outlet has one; then KV only if the outlet has one; KV closed with "বন্ধ করুন" | "এরপর নির্দিষ্ট আউটলেটের জন্য কোন AV থাকলে সেই AV দেখতে পারবেন।" / "AV দেখা সম্পন্ন হয়ে গেলে নির্দিষ্ট আউটলেটের জন্য KV থাকলে KV দেখতে পারবেন। KV দেখা হয়ে গেলে “বন্ধ করুন” বাটনে ক্লিক করুন।" | 32 | S-29, S-30 | S |
| SR-R-068 | Survey exists only for outlets it is configured for | "নির্দিষ্ট আউটলেটের জন্য কোন POSM Availability এর একটি সার্ভে দেখতে পারবেন।" | 33 | S-31 | S |
| SR-R-069 | Questions marked (*) must be answered | "আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে" | 33 | S-31 | S |
| SR-R-070 | If POSM absent select না; if present select হ্যাঁ then take one POSM photo | "POSM না থাকলে না নির্বাচন করুন। POSM থাকলে হ্যাঁ অপশন নির্বাচন করুন। এরপর POSM এর একটি ছবি তুলুন।" | 33 | S-31 | S |
| SR-R-071 | Submitting the POSM photo earns 50 points | "POSM এর ছবি সাবমিট করলে আপনি ৫০ পয়েন্ট অর্জন করবেন।" | 33 | S-31 | S |
| SR-R-072 | Survey submit requires confirmation | "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?" | 33 | S-31 | S |
| SR-R-073 | Sale quantity is entered in sticks per shopkeeper's demand | "SKU অনুযায়ী দোকানদারের চাহিদামত শলাকার পরিমাণ উল্লেখ করুন। এরপর এগিয়ে যান অপশনে ক্লিক করুন।" | 34 | S-32 | S |
| SR-R-074 | "এগিয়ে যান" moves to the Review screen where sale and slide quantities are verified | "...বিক্রয় এর পরিমাণ এবং স্লাইড এর পরিমাণ সঠিক আছে কিনা সেটি যাচায় করতে পারবেন।" | 34 | S-32, S-33 | S |
| SR-R-075 | Review shows per-category subtotals (সিগারেট, বিড়ি, লাইটার, ম্যাচ) and totals | (none) | 34, 35, 44 | S-33, S-39 | O |
| SR-R-076 | Slide offer value is shown in red and deducted from the grand total (360.50 - 80.00 = 280.50); quantity total unchanged | (none) | 34 | S-33 | O |
| SR-R-077 | Credit sale is optional via checkbox | "এবং বিক্রয় যদি বাকীতে হয়ে থাকে তাহলে বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন অপশনটি নির্বাচন করুন।" | 34, 38 | S-33 | S |
| SR-R-078 | Collected amount at point of sale must be less than the grand total | "এবং এই পরিমাণটি অবশ্যই সর্বমোট টাকার পরিমাণ এর থেকে কম হতে হবে।" | 35 | S-34 | S |
| SR-R-079 | Due = grand total - collected amount, shown live as "বাকিঃ x"; checkbox then shows "Baki n টাকা" | "বাকিঃ ৬১.৫০" / "Baki ৬১ টাকা" | 35 | S-34, S-33 | O |
| SR-R-080 | The remaining due for the shop is shown after partial payment | "এরপর আপনি উক্ত দোকানের জন্য অবশিষ্ট বাকি টাকার পরিমাণ দেখতে পারবেন।" | 35 | S-34 | S |
| SR-R-081 | Memo can be printed later from the Memo section | "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" | 34, 36, 38, 43 | S-33, S-39 | S |
| SR-R-082 | Tapping "প্রিন্ট" on Review commits the sale (confirm -> print prompt -> success) | "এরপর উক্ত দোকানের সকল তথ্য সংরক্ষন করার জন্য “প্রিন্ট” বাটনে ক্লিক করুন, এরপর এমন একটি Alert দেখতে পারবেন এবার “হ্যাঁ” বাটনে ক্লিক করুন।" | 38, 39 | S-33, S-37 | S |
| SR-R-083 | Print prompt precedes the success message; success after "হ্যাঁ" | "“হ্যাঁ” অপশনে ক্লিক করার পর উক্ত আউটলেট এর বিক্রয়কার্য্য সম্পূর্ণ হওয়ার একটি “সফল” ম্যাসেজ দেখতে পারবেন।" | 39 | S-37 | S |
| SR-R-084 | "এগিয়ে যান" with no SKU triggers a zero-sale confirmation | "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি" / "আপনি কি জিরো (০) বিক্রয় করতে চান?" | 43 | S-38 | S |
| SR-R-085 | Zero sale is completed with "প্রিন্ট" on its review screen; no credit checkbox is shown | "এখান থেকে জিরো সেল এর ডাটা চেক করুন এবং ‘প্রিন্ট’ অপশনে ক্লিক করে জিরো সেল কমপ্লিট করুন।" | 43 | S-33 | S |
| SR-R-086 | Product QC (return of defective cigarettes) is started from the Review screen | "ত্রূটিপূর্ণ সিগারেট ফেরত আনার জন্য “প্রোডাক্ট QC ” বাটনে প্রেস করুন।" | 36 | S-33, S-35 | S |
| SR-R-087 | QC is done on one SKU at a time, chosen from the SKU list | "...এরপর যে SKU এ QC করবেন SKU টি নির্বাচন করুন।" | 36 | S-35 | S |
| SR-R-088 | QC quantity is the number of defective sticks per fault type, for production and transport groups | "যে ধরণের ত্রুটি এবং কতগুলা শলাকা ত্রুটিপূর্ণ তার সংখ্যা ... পরিমাণ কলামে প্রদান করুন।" | 37 | S-36 | S |
| SR-R-089 | QC fault types: Production - damaged & crushed pack/outer CBC; outer/pack/stick short; other. Transport - expired stock (4 months+); stock damaged during route service; taste-related problem | (none beyond the row labels; see S-36) | 37 | S-36 | O |
| SR-R-090 | Each SKU has a Maximum QC amount in taka, with QC done and Remaining | "সর্বোচ্চ QC: ৫৯৪.৫০৳" / "QC হয়ে গেছে: ০.০০৳" / "বাকি আছে: ৫৯৪.৫০৳" | 37 | S-36 | O |
| SR-R-091 | QC summary has one row per SKU with comma-joined fault type codes (MFC Fault, MKT Fault), total QC quantity and settlement amount | "MFC Fault,MKT Fault" / "মোট QC নিষ্পত্তি" / "সেটেলমেন্ট পরিমান" | 37 | S-35 | O |
| SR-R-092 | QC data is submitted with confirmation | "আপনি কি QC জমা দিতে চান?" | 38 | S-35 | S |
| SR-R-093 | Total QC is deducted from the memo total (সর্বমোট = মোট - মোট QC) | "মোট QC" "- ০.০০" | 44 | S-39 | O |
| SR-R-094 | Memo outlet dropdown shows the outlet's outstanding due in red ("x ৳ বাকি") | "Tasmin (1618415)  : ২৯১.৫০ ৳ বাকি" | 44 | S-39 | O |
| SR-R-095 | Credit dues of the outlet are paid from the Memo option | "বাকীতে বিক্রি আউটলেট গুলোর বাকী পরিশোধ করতে মেমো অপশনে ক্লিক করুন।" | 44 | S-39 | S |
| SR-R-096 | Credit memo shows a red "credit" box with "পরিশোধিত করুন" (settles the whole memo) | "এই বিক্রয়টি বাকিতে করা হয়েছে।" | 44 | S-39 | S |
| SR-R-097 | Marking paid requires confirmation; afterwards the বাকি tag is removed from the outlet entry | "...এবং বাকী স্ট্যাটাসটি আউটলেট এর পাশে থেকে মুছে যাবে।" | 45 | S-40 | S |
| SR-R-098 | A wrong sale can be corrected only via the Memo option after the sale | "বিক্রয় পরবর্তী কোন আউটলেটের ভুল বিক্রয় মেমো অপশন থেকে সংশোধন করা যাবে।" | 46 | S-39 | S |
| SR-R-099 | Editing a sale requires the SR to be inside the outlet's geofence | "বিক্রয় এডিট করার জন্য ঐ আউটলেটের Geofencing এর আওতায় থাকতে হবে" | 46 | S-39, S-41 | S |
| SR-R-100 | A sale cannot be edited at any outlet where QC has been done | "QC কৃত কোন আউটলেটে বিক্রয় এডিট করা যাবে না।" | 46 | S-39, S-41 | S |
| SR-R-101 | An edit reason is mandatory, chosen from three options | "বিক্রয় এডিট এর কারন হিসেবে তিনটি অপশন দেখতে পাবেন। নির্দিষ্ট অপশন সিলেক্ট করে জমা দিন বাটনে ক্লিক করুন।" | 47 | S-41 | S |
| SR-R-102 | After the reason the outlet's sale page opens pre-filled for re-entry | "...ঐ আউটলেটের বিক্রয় পেজ দেখতে পাবেন। এখান থেকে আবার সঠিক বিক্রয় ইনপুট করে বিক্রয় এডিট কার্য সম্পন্ন করুন।" | 47 | S-42 | S |
| SR-R-103 | Astha data is viewed by route and by outlet, with STD and Memo targets and achievements | "রুট এবং আউটলেট ভিত্তিক আস্থা এর এসটিডি এবং মেমো এর টার্গেট এবং অ্যাচিভমেন্ট এর ডাটা দেখতে আস্থা অপশনে ক্লিক করুন।" | 48 | S-43 | S |
| SR-R-104 | Year and quarter must be chosen to view route data | "রুট এর তথ্য দেখতে এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।" | 48 | S-43 | S |
| SR-R-105 | STD target is shown first; Memo target via sub-tab | "প্রাথমিক অবস্থায় উক্ত রুট এর এসটিডি টার্গেট দেখতে পারবেন। এরপর মেমো টার্গেট দেখতে মেমো টার্গেট অপশনে ক্লিক করুন।" | 49, 51 | S-43, S-45 | S |
| SR-R-106 | Any one month or all 3 months of the quarter can be selected (multi-select chips) | "যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।" | 49, 52 | S-43, S-45 | S |
| SR-R-107 | Route memo target is a single "All Brand" row; outlet memo target is per brand | (none) | 49, 52 | S-43, S-45 | O |
| SR-R-108 | Route table has Remaining (বাকি) column; outlet table has no Remaining column | (none) | 49, 51 | S-43, S-45 | O |
| SR-R-109 | Remaining = target - achievement floored at 0; % = achievement / target x 100; target 0 -> 0.00%; no clamping (negative target and >100% displayed) | (none; Maxim 12,480/33,050/0/264.82%; Marise -20 -> -37,500.00%) | 49 | S-43 | I |
| SR-R-110 | Outlet list for Astha shop-wise info carries name-code-mobile-area, owner, contact, sub-channel, wing, division, territory, zone | (none) | 50 | S-44 | O |
| SR-R-111 | Select a retailer from the Astha list to see its targets | "এরপর আস্থা রিটেইলার লিস্ট দেখতে পারবেন। একটি রিটেইলার নির্বাচন করুন।" | 50 | S-44 | S |
| SR-R-112 | Outlet menu offers exactly three actions: new shop, permanent closure, information change | "...নতুন দোকান, স্থায়ী বন্ধ, এবং তথ্য পরিবর্তন অপশন তিনটি দেখতে পারবেন।" | 53 | S-46 | S |
| SR-R-113 | New shop fields: cluster, shop name, owner name, mobile number; GEO and one photo captured by one button; Save creates outlet with no confirm dialog | "তথ্য ইনপুট শেষে GEO এবং ছবি ধারন করুন অপশনে ক্লিক করুন।" / "এরপর ঐ আউটলেটের একটি ছবি তুলুন।" | 54, 55 | S-47 | S |
| SR-R-114 | New shop flow order: fill info -> GEO+photo -> Save -> success alert | "ছবি কেপচার শেষ করে, সংরক্ষণ বাটনে ক্লিক করুন।" / "সংরক্ষণ বাটনে ক্লিক করার পরে এলার্ট দেখতে পারবেন।" | 54, 55 | S-47 | S |
| SR-R-115 | Permanent closure: cluster -> outlet -> read-only name/owner/mobile -> Save -> confirmation; no reason/photo/GEO field | "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" | 56, 57 | S-48 | S |
| SR-R-116 | Information change: wrong info may be corrected; after changes GEO+photo capture must be selected | "তথ্য পরিবর্তন শেষে “GEO এবং ছবি ধারন করুন” অপশনটি নির্বাচন করতে হবে।" | 58 | S-49 | S |
| SR-R-117 | Info-change photo: exactly one photo must be taken; alert "ছবি ধারণ করা সম্পন্ন হয়েছে" follows | "...এরপর উক্ত দোকানের একটি ছবি উঠাতে হবে।" | 59 | S-49 | S |
| SR-R-118 | Info change save requires confirmation then shows success | "এই পরিবর্তন সংরক্ষণ করা হবে" | 60 | S-49 | S |
| SR-R-119 | Gift redemption is entered via Dashboard > Loyalty Point > Redemption (or Points > Redemption) | "গিফট রিডিম্পশন করার জন্য অ্যাপ্লিকেশান ড্যাশবোর্ড থেকে লয়্যালটি পয়েন্ট অপশনে ক্লিক করুন।" | 61, 24 | S-50, S-21, S-51 | S |
| SR-R-120 | Redemption page shows outlet's total points earned and the gift list | "এই পেজে উক্ত আউটলেট এর অর্জিত টোটাল পয়েন্ট দেখতে পারবেন এবং গিফট এর লিস্ট দেখতে পারবেন।" | 62 | S-51 | S |
| SR-R-121 | Gifts are selected with +/- according to points; a "+" is disabled when the gift cost exceeds remaining points; "-" disabled at 0; remaining counter decreases | "প্লাস এবং মাইনাস বাটনের মাধ্যমে পয়েন্ট অনুযায়ী গিফট সিলেক্ট করতে পারবেন" | 62 | S-51 | S (buttons O) |
| SR-R-122 | Maximum 199 points can be redeemed as cash | "সর্বোচ্চ ১৯৯ পয়েন্ট ক্যাশ রিডিম্পশন করতে পারবেন" | 62 | S-51 | S |
| SR-R-123 | Cash-back: 1 point = 2 taka | "প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক।" | 62 | S-51 | S |
| SR-R-124 | Confirming redemption deducts the points | "Confirm redemption of these items? Points will be deducted." | 63 | S-51 | S |
| SR-R-125 | Astha photo: outlets and their gift come from the TSO Portal | "TSO Portal থেকে আউটলেটে এর জন্য যে গিফট নির্বাচন করা হয়েছিলো ঐ সকল আউটলেট দেখতে পাবেন।" | 65 | S-53 | S |
| SR-R-126 | Gift photo can be deleted, reloaded (retaken), zoomed by tapping; Submit when OK | "ডিলিট ও রিলোড বাটনে ক্লিক করে পুনরায় ছবি তুলতে পারবেন। সেইসাথে ছবিতে ক্লিক করে ছবি জুম করে দেখতে পারবেন। সবকিছু ঠিক থাকলে সাবমিট অপশনে ক্লিক করুন।" | 65, 69 | S-53, S-54 | S |
| SR-R-127 | Astha photo: one photo per outlet; afterwards "Photo already captured." | "একটি আউটলেটে একবার ছবি তুলতে পারবেন।" | 67 | S-53 | S |
| SR-R-128 | Astha/Campaign photo submit requires confirmation | "আপনি কি নিশ্চিত?" | 66, 70 | S-53, S-54 | S |
| SR-R-129 | Campaign photo lists only outlets for which gifts were redeemed, with one capture slot per redeemed gift and a campaign dropdown | "যেসকল আউটলেট এর জন্য গিফট রিডিম করা হয়েছিলো ঐ সকল আউটলেট দেখতে পারবেন।" | 69 | S-54 | S |
| SR-R-130 | Before finishing the day all operation data must be synced and the sales deposit submitted | "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।" | 71, 73 | S-55 | S |
| SR-R-131 | "বিক্রয় জমা" is disabled until sync completes, then enabled | "সিঙ্ক এর কাজ সম্পূর্ণ হয়ে গেলে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে।" | 72 | S-55 | S |
| SR-R-132 | (Conflicting) "বিক্রয় জমা" is enabled after all shops' dues are paid AND sync is finished | "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে।" | 73 | S-55 | S (conflicts with R-133) |
| SR-R-133 | Pending dues give a soft warning: Yes = submit anyway, No = go collect dues | "আপনি চাইলে হ্যাঁ অপশনটি নির্বাচন করে বিক্রয় জমা দিতে পারবেন। অথবা আপনি না অপশন নির্বাচন করে পুনরাই বাকি পরিশোধ করতে পারবেন।" | 72 | S-55 | S |
| SR-R-134 | Device-vs-server counts (outlet, sales, stock, QC, promotion) are displayed for reconciliation | (none) | 71, 72, 73 | S-55 | O |
| SR-R-135 | Wait until sync/submit completes; then confirm the success alert to finish the upload | "প্রক্রিয়াটি সম্পূর্ণ হওয়া পর্যন্ত অপেক্ষা করুন।" / "ওকে” অপশন এ ক্লিক করে ডাটা আপলোড এর কাজ সম্পূর্ণ করুন।" | 71, 73 | S-55 | S |
| SR-R-136 | Device status line shows connectivity (only "অনলাইন" ever shown) | "ডিভাইস স্ট্যাটাস" / "অনলাইন" | 71-73 | S-55, S-06 | O |
| SR-R-137 | Tutorials are listed from the dashboard; empty state when none | "কোনো টিউটোরিয়াল পাওয়া যায়নি।" | 74 | S-56 | S |
| SR-R-138 | Tasks are assigned by AMO to the SR and shown as a list | "এরপর ইউজার এই সেকশনে AMO থেকে বরাদ্দকৃত টাস্ক লিস্ট আকারে দেখতে পারবেন।" | 75 | S-57 | S |
| SR-R-139 | Resolve a task by swiping left-to-right then tapping Resolve; status changes to সম্পন্ন; card stays in the list | "টাস্ক সম্পন্ন হয়ে গেলে বাম থেকে ডান দিকে Swipe করুন। এরপর Resolve অপশনে ক্লিক করুন।" | 76 | S-57 | S |
| SR-R-140 | Task statuses seen: চলমান (ongoing), সম্পন্ন (completed); task types seen: OOS | (none) | 75, 76 | S-57 | O |
| SR-R-141 | Settings has exactly language, PDA to Support, Log Out | "“সেটিং” বাটনে ক্লিক করার পর ইউজার এই তিনটি অপশন পাবেন।" | 77 | S-58 | S |
| SR-R-142 | Languages: "en" and "বাংলা"; no confirmation/restart shown | "“En & বাংলা” বাটনের মাধ্যমে আপনি Application এর ভাষা পরিবর্তন করতে পারবেন।" | 78 | S-58 | S |
| SR-R-143 | PDA to Support sends the device data/sync file to the support team (single tap) | "ডাটা ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য “PDA টু সাপোর্ট” বাটনে ক্লিক করুন।" | 78 | S-58 | S |
| SR-R-144 | Logout requires confirmation | "আপনি কি নিশ্চিত যে লগআউট করতে চান?" | 79 | S-59 | S |
| SR-R-145 | Numerals are shown in Bangla digits with comma grouping on most screens; some screens use Latin digits and English labels (Points, Redemption, Photo capture, year) | (none) | 10, 24, 35, 49, 62-70 | many | O |
| SR-R-146 | Dates are ISO YYYY-MM-DD in headers and tables; date picker shows "November 26, 2025"; offer validity "Nov 25, 2025" | (none) | 12, 23, 28 | S-10, S-12, S-20, S-27 | O |
| SR-R-147 | Outlet display label format is "Name (code-phone-cluster)" or "Name (code)" or "Name (sub-channel)" depending on screen | (none) | 23, 28, 50, 56 | many | O |
| SR-R-148 | Times use 12-hour AM/PM zero-padded ("04:47 PM") | (none) | 13, 15 | S-13, S-14 | O |
| SR-R-149 | Money amounts show two decimals with comma thousands and "৳" suffix on QC screens ("৫৯৪.৫০৳") | (none) | 23, 37 | S-20, S-36 | O |
| SR-R-150 | Outlet photo + GEO are captured with the in-app camera (flip, shutter, close) | (none) | 27, 54, 59 | S-24, S-47, S-49 | O |

Note for Sections C, D, E: "S-nn" means screen "SR-S-nn" from Section A; "R-nnn" means "SR-R-nnn".

---------------------------------------------------------------------
# SECTION D - MESSAGES REGISTER
---------------------------------------------------------------------
Every user-visible message, prompt, dialog, banner, note, empty state and fixed label-sentence seen in the manual, verbatim (typos kept). Kinds: SYS = Android system dialog (not app text), DLG = app dialog, BAN = banner/toast, INL = inline text, EMP = empty state, NOTE = yellow note on the manual page (manual-authored, shown beside the screen; on p25/41 also reproduced as guidance), INF = info card. Dialog buttons are given in brackets. No error/validation message exists anywhere in the manual (see F-GEN).

| ID | Verbatim text | English meaning | Screen | Page | Kind |
|---|---|---|---|---|---|
| SR-M-001 | Do you want to install this app? [Cancel / Install] | Install confirmation | S-01 | 2 | SYS |
| SR-M-002 | App installed. [Done / Open] | Install/update finished | S-01, S-07 | 3, 9 | SYS |
| SR-M-003 | Allow ARON SR to access this device's location? [While using the app / Only this time / Don't allow; Precise / Approximate] | Location permission request | S-03 | 4 | SYS |
| SR-M-004 | Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process. | Device must be verified (OTP) | S-04 | 5, 6 | INL |
| SR-M-005 | Enter OTP | OTP heading | S-04 | 5, 6 | INL |
| SR-M-006 | Enter the 4-digit OTP provided by your TSO. | OTP instruction | S-04 | 5 | INL |
| SR-M-007 | Update Available | Update heading | S-06 | 7, 8 | INL |
| SR-M-008 | Version 1.0.25 available | Update version | S-06 | 7, 8 | INL |
| SR-M-009 | New promotion modality., Login time 2FA verification., Sync file update without deleting. | Release notes | S-06 | 7, 8 | INL |
| SR-M-010 | Network Status / অনলাইন | Connectivity indicator: Online | S-06 | 7, 8 | INL |
| SR-M-011 | Downloading 24% | Download progress | S-06 | 8 | INL |
| SR-M-012 | Download completed | Download finished | S-06 | 8 | INL |
| SR-M-013 | Please do not close the app while downloading | Do not close app | S-06 | 8 | INL |
| SR-M-014 | Installing apps from this source may put your phone and data at risk. | Unknown-source warning | S-07 | 7 | SYS |
| SR-M-015 | Do you want to update this app? [Cancel / Update] | Update confirmation | S-07 | 8 | SYS |
| SR-M-016 | কিছু নতুন আপডেট পাওয়া গেছে, এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে! | New updates found; you must update to continue | S-08 | 9 | INL |
| SR-M-017 | Current version SR App 1.0.1 | Installed version footer | S-08 | 9 | INL |
| SR-M-018 | অ্যাপ আপডেট হচ্ছে | App update in progress | S-09 | 10 | INL |
| SR-M-019 | প্রসেসিং(১৪/৮৭) | Processing 14 of 87 | S-09 | 10 | INL |
| SR-M-020 | টিপস: অ্যাপটি বন্ধ করবেন না | Tip: do not close the app | S-09 | 10 | INL |
| SR-M-021 | আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | Not checked in; please check in before starting work | S-12 | 12, 13 | INL |
| SR-M-022 | চেক ইন করা হচ্ছে | Checking in (sheet title) | S-13 | 13 | INL |
| SR-M-023 | চেক ইন সম্পন্ন হয়েছে | Check-in completed | S-12 | 14, 15 | INL |
| SR-M-024 | চেক আউট ৫টার পরে সক্রিয় হবে | Check-out activates after 5 o'clock | S-12 | 14 | INL |
| SR-M-025 | আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | Not checked out; please check out before finishing | S-12 | 14 | INL |
| SR-M-026 | বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন। | Note: check-out possible from 5 PM | S-12 | 14 | NOTE |
| SR-M-027 | চেক আউট করা হচ্ছে | Checking out (sheet title) | S-14 | 15 | INL |
| SR-M-028 | চেক আউট সম্পন্ন হয়েছে | Check-out completed | S-12 | 15 | INL |
| SR-M-029 | আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ। | Day complete, thank you | S-12 | 15 | INL |
| SR-M-030 | চাপ দিয়ে ধরে রাখুন | Press and hold (button label) | S-13, S-14 | 13, 15 | INL |
| SR-M-031 | Turn on Bluetooth to connect to nearby devices. | Bluetooth off helper | S-15 | 16 | SYS |
| SR-M-032 | Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices. | Pairing helper | S-15 | 17, 18 | SYS |
| SR-M-033 | Bluetooth pairing request / Enter PIN to pair with RPP02N (Try 0000 or 1234). [Cancel / Pair] | PIN prompt | S-15 | 17 | SYS |
| SR-M-034 | Device name will appear when this device is connected. | Unnamed device helper | S-15 | 18 | SYS |
| SR-M-035 | Allow ARON SR to find, connect to and determine the relative position of nearby devices? [Allow / Don't allow] | Nearby devices permission | S-16 | 19 | SYS |
| SR-M-036 | প্রিন্টার কানেক্ট করা হয়েছে | Printer has been connected (green banner) | S-16, S-17 | 20 | BAN |
| SR-M-037 | বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন | Select a retailer to continue the sale | S-18 | 22, 40 | EMP |
| SR-M-038 | রিটেইলার নির্বাচন করুন | Select retailer (placeholder) | S-18, S-53 | 22, 40, 67 | INL |
| SR-M-039 | আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই। | You are not within the selected retailer's range | S-19 | 23, 25, 41 | INL |
| SR-M-040 | বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে। | Note: mobile data must be ON | S-20 | 23 | NOTE |
| SR-M-041 | Manage retailer gift requisition and redemption. | Points screen info text | S-21 | 24 | INF |
| SR-M-042 | Expiring Points: / Expiry Date: | Points card labels | S-21 | 24 | INL |
| SR-M-043 | আপনার ফোর্স সেলের কারণ কী? | What is the reason for your force sale? | S-22 | 25, 41 | INL |
| SR-M-044 | বিঃদ্রঃ অবশ্যই মোবাইল ফোনের লোকেশন অন রাখতে হবে। | Note: mobile location must be ON | S-19, S-22 | 25, 41 | NOTE |
| SR-M-045 | Allow ARON SR to take pictures and record video? [While using the app / Only this time / Don't allow] | Camera permission | S-23 | 26 | SYS |
| SR-M-046 | Allow ARON SR to record audio? [While using the app / Only this time / Don't allow] | Microphone permission | S-23 | 26 | SYS |
| SR-M-047 | আপনি কি কল শুরু করতে চান? [হ্যাঁ / না] | Do you want to start the call? | S-25 | 27, 31, 42 | INL |
| SR-M-048 | ছাড় | "Discount" (offer dialog title) | S-27 | 28 | DLG |
| SR-M-049 | 100 stick worth of empty pack of MaxR get 1 pack MaxR 10s | Slide offer text (master data) | S-27, S-33 | 28, 34 | DLG |
| SR-M-050 | আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে | You must answer (*) questions | S-31 | 33 | INF |
| SR-M-051 | 1* আপনার আউটলেটে POSM আছে? | Does your outlet have POSM? | S-31 | 33 | INL |
| SR-M-052 | 1.1* POSM এর ছবি সাবমিট করলে আপনি ৫০ পয়েন্ট অর্জন করবেন। | Submit POSM photo to earn 50 points | S-31 | 33 | INL |
| SR-M-053 | আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত? [হ্যাঁ / না] | Are you sure about submitting the survey? | S-31 | 33 | DLG |
| SR-M-054 | আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন। | You can print later from the Memo section | S-33 | 34, 36, 38, 39, 43 | INF |
| SR-M-055 | পরিশোধিত টাকার পরিমাণ লিখুন | Enter the amount paid (dialog title) | S-34 | 35 | DLG |
| SR-M-056 | এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন? | How much are you collecting from the retailer now? | S-34 | 35 | DLG |
| SR-M-057 | বাকিঃ ৬১.৫০ | Due: 61.50 (live) | S-34 | 35 | INL |
| SR-M-058 | Baki ৬১ টাকা | Baki 61 taka (checkbox label after payment) | S-33 | 35 | INL |
| SR-M-059 | আপনি কি নিশ্চিত? / বিক্রয় জমা হবে [হ্যাঁ / না] | Are you sure? Sale will be submitted | S-37 | 38 | DLG |
| SR-M-060 | আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান? [হ্যাঁ / না] | Do you want to print this sale? | S-37 | 39 | DLG |
| SR-M-061 | সফল / বিক্রয় সফল ভাবে জমা হয়েছে [ওকে] | Success: sale submitted successfully | S-37 | 39 | DLG |
| SR-M-062 | বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি / আপনি কি জিরো (০) বিক্রয় করতে চান? [হ্যাঁ / না] | No SKU selected / Do a zero sale? | S-38 | 43 | DLG |
| SR-M-063 | QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন | To enter QC select the product to add | S-35 | 36, 37, 38 | INF |
| SR-M-064 | QC করার জন্য কোন পণ্য নেই | No product for QC | S-35 | 36 | EMP |
| SR-M-065 | ইনফর্মেশন / আপনি কি QC জমা দিতে চান? [হ্যাঁ / না] | Information: submit QC? | S-35 | 38 | DLG |
| SR-M-066 | সর্বোচ্চ QC: ৫৯৪.৫০৳ / QC হয়ে গেছে: ০.০০৳ / বাকি আছে: ৫৯৪.৫০৳ | Max QC / QC done / Remaining (money) | S-36 | 37 | INL |
| SR-M-067 | আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন | Select outlet to view memo; reprint from here | S-39 | 44, 46 | INF |
| SR-M-068 | এই বিক্রয়টি বাকিতে করা হয়েছে। | This sale was made on credit | S-39 | 44, 45 | INL |
| SR-M-069 | আপনি কি নিশ্চিত? (no body) [হ্যাঁ / না] | Are you sure? (mark as paid) | S-40 | 45 | DLG |
| SR-M-070 | এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে। [ওকে] | Memo successfully marked as paid | S-40 | 45 | DLG |
| SR-M-071 | দয়া করে বিক্রয় এডিট করার কারন লিখুন | Please write the reason for editing the sale | S-41 | 47 | DLG |
| SR-M-072 | ভুল SKU নির্বাচিত। | Wrong SKU selected. (edit reason option 1) | S-41 | 47 | DLG |
| SR-M-073 | তথ্য পাওয়া যায়নি | No data found | S-43, S-44, S-45 | 48, 50, 51 | EMP |
| SR-M-074 | সফল [ওকে] (no body text) | Success (new shop saved) | S-47 | 55 | DLG |
| SR-M-075 | আপনি কি নিশ্চিত? / এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে [হ্যাঁ / না] | Are you sure? This shop is going to be permanently closed | S-48 | 57 | DLG |
| SR-M-076 | সফল [ওকে] (no body text) | Success (permanent close saved) | S-48 | 57 | DLG |
| SR-M-077 | ছবি ধারণ করা সম্পন্ন হয়েছে [ওকে] | Photo capture completed | S-49 | 59 | DLG |
| SR-M-078 | আপনি কি নিশ্চিত? / এই পরিবর্তন সংরক্ষণ করা হবে [হ্যাঁ / না] | Are you sure? This change will be saved | S-49 | 60 | DLG |
| SR-M-079 | সফল [ওকে] (no body text) | Success (info change saved) | S-49 | 60 | DLG |
| SR-M-080 | ২২০ Pts Remaining (live badge; "Diamond League (April)") | Remaining redeemable points | S-51 | 62 | INL |
| SR-M-081 | Confirm redemption of these items? Points will be deducted. [নিশ্চিত করুন / বাতিল] | Redemption confirmation | S-51 | 63 | DLG |
| SR-M-082 | Redemption successful [ওকে] | Redemption done | S-51 | 63 | DLG |
| SR-M-083 | আপনি কি নিশ্চিত? [হ্যাঁ / না] | Are you sure? (Astha photo submit) | S-53 | 66 | DLG |
| SR-M-084 | Photo capture details saved successfully. [ওকে] | Astha photo saved | S-53 | 66 | DLG |
| SR-M-085 | অনুগ্রহ করে রিটেইলার নির্বাচন করুন | Please select a retailer (empty state with camera icon) | S-53 | 67 | EMP |
| SR-M-086 | Photo already captured. | One photo per outlet already taken | S-53 | 67 | EMP |
| SR-M-087 | আপনি কি নিশ্চিত? [হ্যাঁ / না] | Are you sure? (campaign photo submit) | S-54 | 70 | DLG |
| SR-M-088 | Campaign details saved successfully. [ওকে] | Campaign photos saved | S-54 | 70 | DLG |
| SR-M-089 | ডিভাইস স্ট্যাটাস / অনলাইন | Device status: Online | S-55 | 71-73 | INL |
| SR-M-090 | আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন। | Before finishing the day sync all data and submit sales deposit | S-55 | 71, 72, 73 | INL |
| SR-M-091 | আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? [হ্যাঁ / না] | You still have dues with 1 retailer; submit sales deposit? (number dynamic) | S-55 | 72 | DLG |
| SR-M-092 | বিক্রয় সফল ভাবে জমা হয়েছে [ওকে] | Sales deposit submitted successfully | S-55 | 73 | DLG |
| SR-M-093 | কোনো টিউটোরিয়াল পাওয়া যায়নি। | No tutorial found | S-56 | 74 | EMP |
| SR-M-094 | Babu Store দোকানে Maxim এ OOS আছে (pattern: "<outlet> দোকানে <brand> এ OOS আছে") | OOS at <outlet> for <brand> (task text; also Avon example) | S-57 | 75, 76 | INL |
| SR-M-095 | Completion on: 2025-11-30 | Task completion/due date label (English) | S-57 | 75, 76 | INL |
| SR-M-096 | চলমান / সম্পন্ন | Status: ongoing / completed | S-57 | 75, 76 | INL |
| SR-M-097 | Resolve | Swipe action label | S-57 | 76 | INL |
| SR-M-098 | সফল / ডাটা ফাইল পাঠানো হয়েছে। [ওকে] | Success: data file has been sent | S-58 | 78 | DLG |
| SR-M-099 | আপনি কি নিশ্চিত যে লগআউট করতে চান? [হ্যাঁ / না] | Are you sure you want to log out? | S-59 | 79 | DLG |

Note: the closing slide p80 says "ধন্যবাদ" [Thank you]; it is not app text and is not counted.

Message conventions observed: confirmation dialogs use title "আপনি কি নিশ্চিত?" (or "ইনফর্মেশন") with green "হ্যাঁ" and red "না"; success dialogs use green tick, title "সফল" (or English text) and green "ওকে". Message language is MIXED: some Bangla, some English (p63, 66, 70, 78 areas) - the rebuild must localise all of them (en/bn).

---------------------------------------------------------------------
# SECTION E - DATA ENTITIES AND FIELDS REGISTER
---------------------------------------------------------------------
Every entity and field the screens read (R) or write (W). Field types/units are as shown; where the manual does not say, "not stated". Offline/online per entity is "not stated" unless noted.

| ID | Entity | Fields (as seen) | Screens (R/W) |
|---|---|---|---|
| SR-E-01 | SR user / credentials | username, password, display name ("SR - Testing Banani", "SR-16858"), login id in brackets ("sr334001", "sr16858"), Field Force ID (9892, 6066), Field Force Name, Zone, Zone ID (6334), Sr No. | S-02 W, S-05 R, S-10 R, S-12 R |
| SR-E-02 | Device binding / SR device OTP | 4-digit OTP (6818, 3560), Create Time (2026-01-20), Field Force ID, username, (implied) device id, app version that triggers re-verification | S-04 W, S-05 R |
| SR-E-03 | App version / update manifest | installed version ("1.0.25", "1.0.1"), available version, release-note text, APK file name (aron_sr_app_25_11_2025_v1.apk), size 80.40 MB / 93.11 MB, download progress % | S-01, S-02, S-06, S-07, S-08 R |
| SR-E-04 | Local data set / sync file | processed units counter (n of 87), "sync file"/"data file" sent to support, device-vs-server counts | S-09, S-55 R/W, S-58 W |
| SR-E-05 | Org hierarchy | Wing (Dhaka, Gaibandha), Division (Savar), Territory (Savar), Distribution House, Zone (Savar Metro, Banani - Test), Cluster (Molobe Para, Madrasa Road, Apsis Cluster), Section/Route (AgrabadDaily, Apsis RouteDaily, Savar BazarDaily), Sub-Channel (Diamond, Gold) | S-05 R, S-44 R, S-45 R, S-47..49 R |
| SR-E-06 | Daily route / route assignment | route name, visit days ("(Sat, Mon, Wed)"), route type suffix ("Daily"), business date (ISO), planned outlet count (37, 80, 81) | S-10, S-12, S-26, S-33 R |
| SR-E-07 | Outlet / retailer | outlet code (1618409, DHK-344-005-...), name, display label "Name (code-phone-cluster)", owner name, mobile/contact (11 digits, sometimes shown without leading 0), cluster, route/section, sub-channel/class (Diamond, Gold), wing/division/territory/zone, latitude/longitude (updated on photo), outlet photo, status (active / permanently closed), outstanding due, first-letter index, AV/KV assignment, survey assignment, gift assignment | S-18, S-19, S-25, S-32, S-39, S-44..49, S-51..54 R; S-24, S-47..49 W |
| SR-E-08 | Attendance | SR, date, check-in time (12 h), check-out time, check-in/out location & address (implied), status (not checked in / checked in / checked out / complete) | S-12 R/W, S-13 W, S-14 W |
| SR-E-09 | Location sample / address | current latitude/longitude, reverse-geocoded address (road, suburb, house, city, district, division, postcode, country), refresh action; accuracy and mock-location flag are NOT shown | S-12, S-19, S-24, S-47, S-49 |
| SR-E-10 | Printer | model "RPP02N", pairing PIN "0000", paired/connected state (red slashed / green icon), connected banner | S-15, S-16, S-17, S-33, S-37, S-39 |
| SR-E-11 | SKU master | SKU code (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, AB-12s, AB-25s, SAB-25s, ABS, Aster, FB, SL, Avon-20S, ARIS-A-20s, BDDF-20s), brand (Maxim, Black Diamond, Abul Bidi Style, Marise, Avon, ARIS, Supreme, Special Abul Bidi, Abul Bidi Gold), pack image, pack-size suffix (10S/20S/12s/25s), category (সিগারেট, বিড়ি, লাইটার, ম্যাচ), unit price (derived 8.00 etc.), badge number, offer flag | S-11, S-17, S-23, S-26, S-32, S-33, S-35, S-36, S-39, S-42 R |
| SR-E-12 | Stock / issue | per-SKU issue qty, per-SKU stock, category total issue, category stock, dashboard "ইস্যু" and "বর্তমান স্টক", stock memo | S-17 R/W, S-10 R, S-55 R |
| SR-E-13 | SKU target and achievement ("STD") | target, achieved (অ্যাচিভ), remaining (বাকি), attainment % (অর্জন), ADS, TADS, PADS, RADS (undefined), overall bar % | S-10, S-11 R |
| SR-E-14 | Dashboard KPIs | outlet visit (visited/total), strike rate %, issue, current stock, non-visit, no sale, task badge count, target % | S-10 R |
| SR-E-15 | Call / visit | call-start decision (হ্যাঁ/না), visit timestamp (implied), zero-sale flag, strike-rate contribution (implied) | S-25 W, S-38 W |
| SR-E-16 | Geofence check | outlet coordinates, SR coordinates, allowed range (value NOT stated), in/out result, Refresh | S-19 R, S-39/S-41 R (edit) |
| SR-E-17 | Force-sale record | reason (ইন্টারনেট সমস্যা / লোকেশন চেঞ্জ), outlet photo, outlet location update | S-22, S-24 W |
| SR-E-18 | Sale / memo header | outlet, route or section, cluster, date, total quantity, total value, slide deduction, total QC, grand total (সর্বমোট), credit flag, collected amount (আদায়কৃত অর্থ), due (বাকি), paid status, edit reason, committed/printed state | S-32, S-33 W, S-34 W, S-37 W, S-39 R, S-42 W |
| SR-E-19 | Sale / memo line | SKU, quantity (sticks), value/price (দাম/মূল্য), category subtotals (মোট সিগারেট / বিড়ি / লাইটার / ম্যাচ) | S-32 W, S-33 R, S-39 R |
| SR-E-20 | Sale history (read model) | outlet, selected date, rows (SKU, quantity, value), totals | S-20 R (online) |
| SR-E-21 | Slide / DRP | offer (text, start date, end date, ratio e.g. 100 sticks of empty packs = 1 pack MaxR 10s), SKU, empty-pack count (জমা দিন), value deduction on memo | S-26..S-28 R/W, S-33 R |
| SR-E-22 | AV / KV assets | video (AV), image (KV), outlet targeting, display order (AV then KV) | S-29, S-30 R |
| SR-E-23 | Survey (POSM Availability) | survey definition (title, questions Q1, Q1.1), required flags (*), answers (হ্যাঁ/না), photo (one), points reward 50, submission | S-31 R/W |
| SR-E-24 | QC | per SKU: max QC (৳), QC done (৳), remaining (৳); fault entries: production (damaged & crushed pack/outer CBC; outer/pack/stick short; other) and transport (expired stock 4 months+; stock damaged during route service; taste problem) with stick counts; QC type codes "MFC Fault", "MKT Fault"; total QC quantity; settlement amount (৳); submitted state | S-35, S-36 R/W, S-33, S-39 R |
| SR-E-25 | Credit / dues | credit flag on memo, outstanding due per outlet, mark-as-paid action, route-level count of retailers with dues | S-33 W, S-34, S-39 R, S-40 W, S-55 R |
| SR-E-26 | Collection at point of sale | amount collected now (must be < grand total), resulting due | S-34 W |
| SR-E-27 | Sale edit | edit reason (3 options; known: ভুল SKU নির্বাচিত।), re-entered quantities, QC-done lock, geofence precondition | S-41, S-42 W |
| SR-E-28 | Astha targets | year, quarter ("Q-4 (Oct-Dec)"), months (Oct/Nov/Dec), level (route / outlet), STD target per brand (target, অর্জন, বাকি (route only), %), memo target (route: All Brand; outlet: per brand) | S-43, S-45 R |
| SR-E-29 | Astha gift assignment and photo | outlet, gift name (e.g. "27 pcs Dinner Set") assigned in TSO portal, gift-distribution photo (one per outlet), captured flag | S-53 R/W |
| SR-E-30 | Loyalty / Diamond League | league name + month ("Diamond League (April)"), points balance, remaining, expiring points, expiry date (2026-05-07), gift catalogue (name, point cost, cash-back item 1 point = 2 taka), redemption (outlet, gift, quantity, points) | S-21 R, S-51 R/W |
| SR-E-31 | Campaign gift verification | campaign ("Diamond League (March) Gift Verify"), outlet, redeemed gifts, per-gift photo | S-54 R/W |
| SR-E-32 | Points ledger | POSM photo +50 points; points deducted on redemption | S-31 W, S-51 W |
| SR-E-33 | Task (AMO to SR) | type (OOS), outlet, brand text, description, completion date, status (চলমান/সম্পন্ন), assigned by AMO, resolved action | S-57 R/W, S-10 R (badge) |
| SR-E-34 | Tutorial | tutorial video list (server/admin content) | S-56 R |
| SR-E-35 | Settings / session | language (en / বাংলা), session/token (logout) | S-58 W, S-59 W |
| SR-E-36 | Sales deposit (day close) | device vs server counts for outlet, sales, stock, QC (কিউসি), promotion (প্রমোশন); online status; dues-pending count; submitted state | S-55 R/W |
| SR-E-37 | Photo (generic) | outlet photo, POSM photo, gift photo(s); fields: image, capture time, owner outlet; compression/size NOT stated | S-24, S-31, S-47, S-49, S-53, S-54 W |
| SR-E-38 | Promotion | appears only as reconciliation row "প্রমোশন" and release note "New promotion modality" | S-55 R, S-06 |
| SR-E-39 | TSO portal user | role TSO, user "tso-apsis", scope = Wing/Division/Territory/Distribution House/Zone filters | S-05 R |

---------------------------------------------------------------------
# SECTION F - UNCLEAR / CONFLICTING ITEMS
---------------------------------------------------------------------
Each item has page refs. "CONFLICT" = two places in the manual disagree. "GAP" = the manual is silent. "READING" = low-resolution glyph/number. Items marked (resolved) were re-checked against the PDF.

**F-GEN (GAP, applies to all pages)** No page shows any validation or error message (wrong password, wrong OTP, required field missing, quantity over stock, amount >= total, edit not allowed, outside validity window), any loading indicator (sync, upload), any offline banner, any "pending sync" counter, or any retry UI. The only connectivity UI is "Network Status / অনলাইন" (p7-8) and "ডিভাইস স্ট্যাটাস / অনলাইন" (p71-73); an Offline state is never shown. The manual also never mentions mock-location/fake-GPS detection, GPS accuracy, photo compression/size, or battery. All of these must come from the live app or the sponsor, not from this manual.

**F-01 (CONFLICT, p3, 4, 7, 9, 2)** Version labels disagree: login footer "version - 1.0.25" (p4); update page offers "Version 1.0.25 available" (p7, 8); forced-update splash shows "Current version SR App 1.0.1" (p9); APK name "aron_sr_app_25_11_2025_v1.apk" (p2). Which is installed vs available is unclear.

**F-02 (CONFLICT, p2 vs p7)** APK 80.40 MB (file manager) vs 93.11 MB in "Install unknown apps" list - probably APK size vs installed size.

**F-03 (GAP, p5-6)** OTP validity/expiry, single use, retry limit, lockout, and the wrong-OTP message are not shown. Table "Create Time" is a date only (2026-01-20). Row 2 OTP reads "3560" or "3500" at low zoom; the SR phone shows 3-5-6-0 so 3560 (READING).

**F-04 (CONFLICT, p6)** Callout says the list shows OTPs "of all routes" ("সকল রুট এর OTP লিস্ট") and "correct OTP for the correct route", but the table is per SR/username/Field Force (Zone column only). Relationship route <-> SR OTP unclear.

**F-05 (READING/GAP, p10, 16, 40, 46, 48, 53, 61, 64, 68)** Target card bar shows "২৭১৯%" (2719%) on the sr334001 account while every KPI is zero; SR-16858 shows "০%" (p44, 71, 74). Meaning and formula undefined; bar is not capped.

**F-06 (READING/GAP, p10 vs p16+)** KPI tile label transcribed once as "মন ভিজিট" (p10, low-res) and as "নন ভিজিট" (p16, 40, 44, 46, 48, 53, 61, 71, 74-77). Treat as "নন ভিজিট" [Non-visit]. Formulas for strike rate, outlet visit, non-visit, no-sale, issue vs current stock (both 1,500) are not shown; units not given.

**F-07 (GAP, p10, 11)** "এস টি ডি" (STD) is not defined (target group? standard?). ADS, TADS, PADS, RADS are not defined. Observed: Target 500 -> TADS 36, RADS 45; Target 900 -> TADS 68, RADS 82. 5th SKU card and any other tabs are cut off. The "বিস্তারিত" destination is shown on p11 only.

**F-08 (CONFLICT, p14)** Check-out lock wording differs: "চেক আউট ৫টার পরে সক্রিয় হবে" (after 5) vs note "বিকাল ৫ ঘটিকা থেকে" (from 5 PM). p15 accepts 05:00 PM, so threshold >= 17:00. Time basis (device vs server clock), time zone, a latest check-out time, and early/forgotten check-out handling are not stated.

**F-09 (GAP, p12-15)** Check-in: no time window, no geofence/distance check, whether location is stored, hold duration, early-release behaviour, or whether other tiles are locked before check-in. Sample check-in time "04:47 PM" is a late demo value. Address at check-out (28 Ahmed Tower, Gulshan 1213) differs from check-in (Kamal Ataturk Avenue, Banani 1212) - the screen shows the CURRENT address; Refresh behaviour undocumented. Reverse geocoding needs an address service (online dependency not stated).

**F-10 (CONFLICT, p10, 16, 40, 46, 48, 53, 61, 64, 68 vs p44, 71, 74, 75, 77)** Dashboard tiles: sr334001 account has 12 tiles (incl. "লয়ালটি পয়েন্ট"/"লয়্যালটি পয়েন্ট" and "Photo Capture"); SR-16858 has 10 tiles (p44, 71, 74, 75, 77). Also the route label differs: "Apsis RouteDaily" vs "Savar Bazar(Sat, Mon, Wed)" vs "Savar BazarDaily" (p75), outlet totals 37 / 81 / 80, and spelling of the loyalty tile (লয়ালটি / লয়্যালটি / p68 title "লয়ালতি"). Suggests per-user/role/project feature flags and/or app-version differences.

**F-11 (GAP, p10, 16, 40, 44, 46, 48, 53, 61, 71, 74)** A red bar/card at the bottom of every dashboard screenshot is cut off; the dashboard scrolls and the content is unknown.

**F-12 (CONFLICT, p22, 27, 31, 34, 40)** Callout says to tap "এগিয়ে যান" after selecting the shop, but p22/p40 show no such button; the post-selection screen shows Sale History/Points/Force Sale/Refresh instead. "এগিয়ে যান →" first appears in the sale footer (p27, 31, 34, 42).

**F-13 (GAP, p22-27, 31, 41, 42)** The in-range path is never shown. Photo capture appears only after Force Sale (p25, 41, 42) but p27/p31 (titled DRP / Sale process) show the same camera with no force-sale step. Unknown: whether the outlet photo is mandatory for every call or only for force sale; whether the call-start prompt appears for every outlet; effect of "না" on the call prompt; where AV/KV (p32) and the survey (p33) sit in the sequence (placed by slide order only).

**F-14 (CONFLICT/READING, p23)** Sale Data table: row values sum to 20,660.00 but the footer shows "২০,২৬০.০০" (20,260.00); quantity total 4,570 is correct. Possibly a data/misprint issue.

**F-15 (GAP/CONFLICT, p22, 23, 24, 25, 34, 40, 41, 47, 62, 65, 67, 69)** Alphabet strip: all-uppercase (All A B C D E F G H I J K M N P R S T V Z) on p22/p34/p40/p44/p46/p47; mixed case (All A B M R S a b j o s t) after selecting Moin Store (p23-25, 27, 31, 41, 42) and on p62/65/67/69. Never explained: assumed first-letter filter of the outlet list (on p34 the transcriber described it as filtering SKUs by first letter - unclear which list it filters on the SKU screen). Case-sensitivity inconsistency.

**F-16 (GAP, p23, 25, 41, 46)** Force sale: geofence radius (metres) never stated; what Refresh does when still out of range; whether the reason and photo are flagged on the memo/report; who may force; any per-day limit. "ইন্টারনেট সমস্যা" as an accepted reason implies the current app's geo-check depends on connectivity at times.

**F-17 (GAP, p4, 19, 26)** Behaviour when location, Bluetooth, camera or microphone permission is denied ("Don't allow"/"Only this time") is not shown; purpose of the microphone/audio permission is never stated (video/AV recording?).

**F-18 (GAP, many)** Abbreviations never expanded: DRP (p27-31), AV, KV (p32), POSM (p33), QC (p36-38; "কিউসি" p71-73), SKU, OOS (p75), CBC (p37), MFC/MKT (p37), STD (p10), ADS/TADS/PADS/RADS (p11), PDA (p77-78), TSO, AMO, "আস্থা"/Astha, "Diamond League". Product line names: Maxim, Black Diamond, Abul Bidi Style, Marise, Avon, ARIS, Supreme, Special Abul Bidi, Abul Bidi Gold.

**F-19 (CONFLICT, p27/31 vs p34; p28 vs p29)** The same sale-footer button is "Collect DRP Discount" (English, p27, 31, 42) and "স্লাইড সংগ্রহ" (Bangla, p34). Slide column "খালি প্যাকেট" (empty packet) and callout "প্যাক অনুযায়ী" (per pack) vs offer "100 stick worth of empty pack" - the unit of slide quantity (packs vs sticks) is ambiguous; on p34 slide row quantity 10 has value 80.0.

**F-20 (GAP, p28-30)** Slide: min/max quantity (negative?), what happens outside the offer window, one offer per SKU?, confirmation/success after "জমা দিন", navigation afterwards, blue badge "০" in the manual dialog, wrapped label "MaxR-10 / S", callout spelling "সংরক্ষন" vs button "সংরক্ষণ".

**F-21 (GAP, p32)** AV/KV: meanings of AV and KV are not stated; whether they must be watched fully or can be skipped; red vertical line at left of AV (progress?); download/caching; AV is displayed rotated (landscape on portrait screen).

**F-22 (GAP, p33)** Survey: dropdown options only inferred as হ্যাঁ/না; whether Q1.1 hides when না; whether points are awarded immediately; number of photos; error when photo missing; where survey appears in the call.

**F-23 (GAP, p34, 47, 21)** Three unlabelled icon+number rows on each SKU card (cart "১০০০০"/"১১০০০"/"৩৬০০" on p34; "২৫০০"/"৫০০০"/"৮৭৫০" on p47; percent "০%" vs "১০০%"; box "০") and the purple badge ("১" on SKU cards; packet-style on stock p21). Meanings undefined; values are not line prices. Digits small and may be misread.

**F-24 (CONFLICT/READING, p34 vs p36; p34)** Sale-screen quantities (MaxR-10S ১০) vs Review table (MaxR-10S ২০, total cigarette 40) - screenshots may differ in time. The sale-screen footer total "২৮০.৫০" equals the post-slide grand total on Review (360.50 gross) - unclear whether cart total is net of slide. Review table digits for Avon-20S (180 vs 140) and BDDF-20s (108 vs 104) are low-res (p38).

**F-25 (GAP, p34, 35, 36)** Red eye-with-slash icon at top-right of the outlet header on Review (and an extra "⌟" glyph on p35) is unexplained (hidden price? not-synced?).

**F-26 (CONFLICT, p34/35 vs p38; p35; p44-45)** Credit checkbox label: "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" (p34, 35, 36) vs "এই বিক্রয়টি ক্রেডিট হিসাবে চিহ্নিত করুন" (p38, 39). Partial payment: dialog shows due "৬১.৫০" but label "Baki ৬১ টাকা" (rounded/truncated?); amount input is Latin digits "100"; whether decimals, 0 or empty allowed, the error for amount >= total, and what "না" does are not shown; the dialog appears while the checkbox is still unchecked behind it. On Memo (p44-45) "পরিশোধিত করুন" settles the WHOLE memo with no amount field; the p37-48 transcriber notes repo docs/06 mentions partial payment (not verified by me) - partial collection of existing dues is not documented in the manual.

**F-27 (READING/CONFLICT/GAP, p36, 37, 38)** QC: expired-stock label digit "(৪ মাস+)" renders like "8" - read as 4 months+ but could be 8 (p37; in this font Bengali ৪ looks like Latin 8; not resolved by the PDF re-check). Row-to-group mapping (expired stock sits in a shaded band; label "পরিবহন ত্রুটি" is beside rows 5-6 only). Max QC (594.50) basis undefined and unrelated to the 10 x 8.00 = 80.00 shown. Summary uses English codes "MFC Fault, MKT Fault" while entry uses Bangla group names (mapping inferred). "বাতিল" and "ডিলিট" on QC entry unexplained. SKU carousel shows MaxB-10S which is not on the sale list (list scrolled). QC reached from Review: whether QC must precede commit, and any QC time/qty limit, not stated.

**F-28 (GAP, p38, 39, 43)** Sale commit: effect of "না" on "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" (does the sale still save?), printer not connected (red slashed icon on p43, 45, 47), print failure. p38 says "প্রিন্ট" saves all shop information; p39 callout says the success message appears after the print prompt "হ্যাঁ". Success text says "জমা" (submitted) although the day-level "বিক্রয় জমা" is a separate step.

**F-29 (CONFLICT, p38 vs p43/34)** Review sub-line label "সেকশন: AgrabadDaily" (p38, 39) vs "রুট: ..." (p34-36, 43). Outlet label formats vary: "Moin Store (DHK-344-005-[phone]-Apsis Cluster)", "Savar Metro (1618409)", "Kamal Store (1618408-[phone]-Ma Road)" (wrapped), "Afifa Stor (Diamond)", "Tasmin (1618415)", "Babu Store (1618411)" vs "Babu Store (1618411-[phone]-Madrasa Road)". Composition of "DHK-344-005-<phone>-<cluster>" unexplained.

**F-30 (GAP, p67, 69)** Campaign photo: Submit is enabled with one of two gift photos missing (p69); unknown if every gift needs a photo. Astha: "Photo already captured." screen still shows Submit (p67) - behaviour unknown. Campaign dropdown options and whether March (campaign) vs April (redemption) months are expected are not shown.

**F-31 (GAP, p47)** Edit-reason dropdown has three options but only "ভুল SKU নির্বাচিত।" is visible; title says "লিখুন" (write) yet it is a dropdown; steps after re-entry (review/commit/print), edit limits (count, time), editing a paid or credit memo, and effect on stock/QC/points are not shown.

**F-32 (CONFLICT/GAP, p48-52)** Astha: route table has "বাকি" column, outlet table does not (p49 vs p51/52); route memo target is one "All Brand" row while outlet memo target is per brand (p49 vs p52); Marise target -20 gives -37,500.00% (p49); Abul Bidi Style and Gold have target 0 but achievement 6,250; Maxim 264.82%; units not stated; month chips with none selected still show data and selecting only Nov shows the same targets as the whole quarter (p51, 52); Wing for Babu Store is "Dhaka" on the list (p50) vs "Gaibandha" on the detail (p51, 52); Division and Territory both "Savar"; left phone on p50 shows the route tab pill active though the instruction is to press "দোকান ভিত্তিক তথ্য"; whether the list covers all route outlets or only Astha-enrolled ones; brand rows cut off; digits "৯" for memo target read as 9 (READING); purpose/definition of Astha and "memo target" never given.

**F-33 (GAP, p54, 55)** New shop: required fields, mobile-number format/length/duplicate check, whether GEO+photo must be done before Save, error messages, approval workflow, whether usable immediately, which route/sub-channel it gets (no such field). No "photo captured" alert in New Shop (unlike Info Change p59). Button spelled "ধারণ" vs caption "ধারন".

**F-34 (GAP, p56, 57)** Permanent close: no reason, photo or GEO; reversibility/approval; effect on today's route, dues, memos; caption on p57 repeats p55's text.

**F-35 (CONFLICT/GAP, p50, 51, 56, 58)** Mobile numbers lose the leading zero on some screens ("[phone]", "[phone]") vs full on others ("[phone]", "[phone]"); outlet dropdown shows "Ebraj St (Diamond)" while the text field shows "Ebraj St". GEO+photo "must" be selected after changing info but no enforcement message is shown (p58).

**F-36 (GAP/CONFLICT, p61, 62, 63)** Loyalty: whether the 199-point cash cap is per redemption, per month or per outlet; full gift catalogue (more cards below the fold); step/limit of the cash-back stepper; league month label ("April") vs business date 2026-04-22 vs campaign "March"; slide titles spelled "রিডিম্পশন" (p61) vs "রিডিমশন" (p62-63); callout "নিশিত করুন" (typo) vs button "নিশ্চিত করুন" (p63) and vs button "হ্যাঁ" on p66; behaviour after "ওকে"; messages mix English and Bangla.

**F-37 (CONFLICT, p71, 72 vs p73)** Condition enabling "বিক্রয় জমা": p71-72 say it enables once sync completes; p73 says it enables after all shops' dues are paid AND sync finishes; yet p72 shows a soft warning that lets the SR submit with 1 retailer still owing. Disabled state is shown (grey) on p71 and enabled (blue) on p72/73.

**F-38 (READING/CONFLICT, p71, 72, 73)** Reconciliation row 4: label read as "কিউসি" (p71-72) and as "রিডিসি" (p73 transcript); re-checked on the PDF, p73 shows the same label as p71-72 -> "কিউসি" [QC] (resolved). Data sets differ between pages (68 sales/19,700 stock on p71, 73; 38 sales/14,000 stock on p72 right) and the outlet row (১) conflicts with dashboard "০/৮১" (p71). Units of the values (count vs quantity vs money) are not stated. Failure/progress messages for sync and submit are absent.

**F-39 (GAP, p75, 76)** Tasks: only type OOS is shown; whether the badge counts only open tasks; refresh mechanism (push/sync/poll); no detail screen, no evidence/note on Resolve, no undo; whether Resolve syncs back to the AMO; "Completion on" is shown even after resolve (due vs completion date); header on p75 shows route "Savar BazarDaily, 2025-11-30" vs "Savar Bazar(Sat, Mon, Wed), 2025-11-26" on p74.

**F-40 (CONFLICT/GAP, p77, 78)** The file sent by "PDA টু সাপোর্ট" is called "সিঙ্ক ফাইল" (p77 callout) and "ডাটা ফাইল" (p78); contents/size/Wi-Fi rule undefined; no confirmation/progress/failure message. Language option labelled "en" (not "English") and the callout calls it "En & বাংলা". Settings has no profile, password change, version, printer, notification or sync options (this build).

**F-41 (GAP, p79)** Logout: whether blocked while unsynced data exists, whether local data is wiped, landing screen, connectivity requirement.

**F-42 (GAP, p74)** Tutorial: only the empty state is shown; list/player layout, streaming vs download, categories unknown.

**F-43 (GAP, p10 etc.)** "সারসংক্ষেপ" (Summary) tile has no manual page (SR-S-60). "বিক্রয় জমা" meaning clarified by p71-73 (end-of-day sync + submit). "আস্থা" explained only by p48-52 (targets) and p64-67 (gift photo).

**F-44 (GAP, p16-20)** Printer: manual PIN 0000 vs Android hint "Try 0000 or 1234"; only RPP02N documented; whether printer reconnects automatically, whether Sale/Memo screens expose a connect action, memo/stock-memo print layout (the printed memo itself is never shown).

**F-45 (GAP, p21)** Stock: "+"/"-" step; whether Save asks for confirmation or shows success; whether stock can be edited after Save or taken more than once per day; units (sticks/pieces); fifth card AVON cut off and other categories' SKUs not visible; "মোট ইস্যু"/"স্টক" semantics after Save (stock = issue); no stock-shortage check against sales is shown.

**F-46 (GAP - offline/online overall)** Explicitly online: update download, OTP verify (implied), Sale History (stated), sync and sales deposit (stated). Everything else - selling, memo, QC, survey, Astha, redemption, photo capture, tasks, tutorial - is not described as offline or online; no manual page states what is queued on the device or when each record is uploaded other than the end-of-day "ডাটা সিঙ্ক করুন" + "বিক্রয় জমা". Whether photos upload during sync is not shown. The "ইন্টারনেট সমস্যা" force-sale reason shows connectivity problems already occur in the field.

**F-47 (READING, source typos kept verbatim)** "Unknow" (p7), "অন্যথাই" (p19), "অবশ্যয়", "Audion" (p26), "যাচায়", "সংরক্ষন/সংরক্ষণ", "করুণ/করুন" (p36, 39, 46, 79), "ত্রূটিপূর্ণ" (p36), "আপানারা", "ধারন/ধারণ", "কেপচার" (p53-60), "নিশিত" (p63, 66), "Diamong" (p24), "লয়ালতি" (p68), "Stor" (p38).

**F-48 (CONFLICT, p23)** Callout says tap "পূর্বের সেল ডাটা দেখুন" but the on-screen button is the English "Sale History". The Bangla label appears as a green button on the sale entry screen (p34).

**F-49 (GAP, p44-47)** Memo: whether one outlet can have several memos per day (only one is shown); whether paid/edited memos can be reprinted; how the memo dropdown orders outlets; printer round button vs "প্রিন্ট" button behaviour; Edit button visible even when preconditions fail.

**F-50 (CONFLICT, throughout)** Numerals and language are mixed: Bangla digits on most screens but Latin digits on Points (p24), partial-payment input (p35), Astha year/figures (p49-52), redemption/photo screens (p62-70); English labels ("Sale History", "Points", "Photo Capture", "Campaigns", "Completion on:", "Resolve", "Expiring Points:") inside a Bangla UI; success/confirm messages in English on p63, 66, 70.
