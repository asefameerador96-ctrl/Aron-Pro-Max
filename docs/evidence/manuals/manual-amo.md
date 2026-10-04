# ARON AMO (Area Marketing Officer) App - Consolidated Inventory

Source: "AMO (Area Marketing Officer) App User Manual", 80 pages (AKTCL official manual for the Apsis build; app name "ARON AMO", login footer "AMO App (version - 1.0.2) Developed by Apsis Solutions").
Consolidated: 2026-10-04 from the seven chunk transcripts plus direct PDF re-checks of pp 23-24, 48-50, 66-68.

## 0. COVERAGE STATEMENT (read first)

| Expected chunk | Present? | Notes |
|---|---|---|
| pp 1-12 (amo-p01-12.md) | YES | read in full |
| pp 13-24 (amo-p13-24.md) | YES | read in full; pp 23-24 re-checked on the PDF |
| pp 25-36 (amo-p25-36.md) | YES | read in full |
| pp 37-48 (amo-p37-48.md) | YES | read in full; p48 re-checked on the PDF |
| pp 49-60 (amo-p49-60.md) | YES | read in full; pp 49-50 re-checked on the PDF |
| pp 61-72 (amo-p61-72.md) | YES | read in full |
| pp 73-80 (amo-p73-80.md) | YES | read in full |

NO RANGE IS MISSING. Page 1 = cover, page 80 = "ধন্যবাদ" (Thank you) closing slide; neither is a screen.

### Facts verified directly against the PDF during consolidation (they change how earlier transcripts read)
1. **p23-24 (SR Call Assessment): items 4 and 5 of the "5-step sales call assessment" section are NOT visible anywhere in the manual.** Page 23 ends mid-item-3 and page 24 starts with the tail "...করা, এনসিউর করা)" ("...doing, ensuring)") of a later item. The manual never shows the titles of steps 4-5. This is a real gap in the ground truth - it must be sourced from the live app or Apsis config, not from this manual.
2. **pp 48-50 are ONE scrolling form** ("নতুন দোকান" for an SR-submitted new outlet): p48 shows the top (route, cluster, shop name, owner, mobile, Sub-Channel, Geo Classification); p49-50 show the same form scrolled to the bottom (name/owner/mobile, Sub-Channel, Geo Classification, then the buttons "GEO এবং ছবি ধারণ করুন", "বাতিল", "সংরক্ষণ"). So p49-50 are the continuation of the "নতুন আউটলেট যাচাইকরণ" verification flow and the Sub-Channel/Geo Classification fields are filled there. (Chunk transcript p49-60 had flagged this as unclear.)
3. **pp 66-68 (Sales Deposit)** re-checked: p66 table has 9 rows (incl. "মূল্য সম্মতি"), p67 table has 8 rows (no "মূল্য সম্মতি" row), p68 has 9 rows. The p67 callout (enabled after sync; dues dialog lets you submit anyway) and the p68 callout (enabled after dues of ALL shops are settled and sync done) genuinely conflict.
4. **p25 vs p21**: p21 is titled "Control call" but its screenshots show the Joint Call tile highlighted and the "জয়েন্ট কল একটিভিটি" screen. The real Control Call Activity screen is on p25-27.

### Totals
62 screens (S-01..S-62; S-21, S-23, S-49 are documented only as a button/tile with no screen shown), 116 rules (R-001..R-117, R-113 intentionally unused), 90 message entries (M-001..M-090), 37 flows (F-01..F-37; F-37 lists explicit gaps), 63 unclear/conflicting items (U-01..U-62 plus U-47a).

### Conventions
- Bangla is verbatim as printed (including the manual's own typos); English translation follows in `( )` or after `=`. Bangla digits (০-৯) appear on screens; Western equivalents are given where useful.
- "p" = PDF/manual page number. "INFERRED" = deduced from screenshots, NOT stated in the manual.
- IDs: screens AMO-S-nn (written S-nn), rules AMO-R-nnn (R-nnn), messages AMO-M-nnn (M-nnn), flows AMO-F-nn (F-nn), unclear items AMO-U-nn (U-nn).
- Role: every screen is the AMO role (the manual documents only the AMO app) unless noted "OS" (Android system UI). Some screens also expose data about SRs of the AMO's team/zone.
- Sync/offline: the AMO manual NEVER states offline behaviour for any screen except that the Sales Deposit screen shows a "ডিভাইস স্ট্যাটাস ● অনলাইন" indicator and requires a manual sync (S-51). Each screen's "Offline/online" line says "not stated" unless the manual says otherwise, plus an INFERRED note where the nature of the data forces it.

---------------------------------------------------------------------

# A. SCREEN INVENTORY (in the order a user meets them)

Cross-cutting rules that apply to every screen: R-108 (digits/number format), R-109 (retailer label), R-110 (route label), R-111 (mobile format), R-112 (Bangla/English mix). Unclear items with no single owning screen: U-09 (spelling variants), U-62 (low-legibility regions).

Order: install/login/update (S-01..S-05) -> home dashboard (S-06) -> printer set-up (S-07) -> each dashboard tile in grid order: Attendance, Joint Call, Control Call (+ its sub-screens), Team Location, Team Performance, Task Delegation, Live Dashboard, SR Stock, Outlet (+ all sub-screens), Stock, Sales/Memo, Summary, Sales Deposit, Astha, Report, Settings.

Dashboard grid order (variant 3, the latest, p12/14/17/25/37...): Row1 Attendance | Joint Call | Control Call; Row2 Team Location | Team Performance | Task Delegation; Row3 Live Dashboard | SR Stock | Outlet; Row4 Stock | Sales | Memo; Row5 Summary | Sales Deposit | Astha; Row6 Report | Settings.

---------------------------------------------------------------------
## S-01 | APK installation (Android file manager + package installer) | "অ্যাপ্লিকেশান ইনস্টল করার প্রক্রিয়া" | pp 2-3
- **Role:** OS (user installing). 
- **Purpose:** side-load the AMO APK from the phone's file manager (no Play Store).
- **Entry:** user has the file `aron_amo_app_26_11_2025_v1.apk` (pattern `aron_amo_app_<dd>_<mm>_<yyyy>_v1.apk`, 74.39 MB on p2) in Internal storage; distribution channel not stated.
- **Controls (OS):** File Manager -> tap the APK file -> OS dialog "ARON AMO / Do you want to install this app?" buttons "Cancel", "Install" -> OS dialog "App installed." buttons "Done", "Open".
- **Exits:** "Open" -> app launches to Login (S-02); "Done" -> back to file manager; launcher icon "ARON AMO" appears on the home screen (tap to launch/login).
- **Messages (OS, English):** "Do you want to install this app?"; "App installed."; buttons Cancel / Install / Done / Open.
- **Rules:** R-001, R-002.
- **Hardware/permissions:** storage (OS), package installer.
- **Data:** none. **Offline/online:** none (local install). 
- **Note:** a separate app "ARON SR" is installed on the same phone in the p5 screenshot -> separate APK per role.

## S-02 | Login | "লগইন" | p4
- **Entry:** launcher icon "ARON AMO" / "Open" from installer. **Exit:** location permission prompt (S-03) then, for a successful login, S-05 (forced update, if one is pending) or S-06 (dashboard).
- **Layout:** top half red-to-purple gradient with diagonal split and circular logo (letter "A"); white lower half.
- **Controls:**
  | Control | Label (verbatim) | Type | Required | Notes |
  |---|---|---|---|---|
  | Username | "ইউজারনেম" (Username) | text input, placeholder "ইউজারনেম" | yes (implied) | no length/format shown; AMO usernames seen: amo5756, amo5001, ss344002 |
  | Password | "পাসওয়ার্ড" (Password) | masked text input (shows "******") | yes (implied) | no show/hide icon; no complexity rules stated |
  | Login button | "লগইন" (Login) | full-width gradient button | - | submits credentials, then triggers S-03 |
  | Footer (static) | "AMO App" / "(version - 1.0.2)" / "Developed by Apsis Solutions" | 3 centred text lines | - | version string seen = 1.0.2 |
- **Not present on screen:** forgot-password, remember-me, language toggle, role selector, OTP/device-binding.
- **Rules:** R-003, R-004, R-005. **Hardware:** location (prompted after login tap).
- **Data read/written:** User (username, password), app version. **Offline:** not stated whether login works offline.
- **Messages:** none shown (no wrong-password / network-error text in the manual).
- **Unclear items for this screen:** U-05, U-02 (section F).

## S-03 | Location permission prompt (Android runtime dialog) | p4 (right)
- **Role:** OS. **Entry:** immediately after tapping "লগইন" on S-02. **Exit:** returns to app flow.
- **Dialog text:** "Allow ARON AMO to access this device's location?"; two map thumbnails "Precise" (pre-selected, pin) and "Approximate"; buttons stacked "While using the app" (the one the manual tells the user to pick), "Only this time", "Don't allow".
- **Rule:** R-004 - the user MUST give location permission to use the app; choose "While using the app" (foreground-only; precise).
- **Offline/online:** n/a.

## S-04 | Update Available - in-app APK download (Path A) | "অ্যাপ্লিকেশন আপডেট প্রক্রিয়া" | pp 5-6
- **Role:** AMO (+OS dialogs). **Entry:** app detects a new version (trigger not stated). **Exit:** OS "Install unknown apps" settings -> back to app -> download -> OS "Do you want to update this app?" -> "Update" -> "Open" -> app (S-02/S-06).
- **State 1 (p5 left):** top bar: back arrow "<" (left); right: "Network Status" + green dot + "অনলাইন" (Online). Banner: orange ribbon "NEW UPDATE", purple cloud-download icon; heading "Update Available"; sub text "Version 1.0.4 available"; full-width dark-purple button "Download App & Install".
- **State 2 (OS, p5 right):** Android Settings > "Install unknown apps" (warning "Installing apps from this source may put your phone and data at risk."); app list with toggles: "Android Assistant" (801 KB, ON, "Auto disabled"), "ARON AMO" (92.18 MB [or 72.18, digit unclear], ON - the one to switch on), "ARON SR" (101 MB, OFF), "Bluetooth" (1.46 MB, OFF), "Captive portal login" (10.56 MB, OFF), "Chrome" (240 MB, OFF), "Drive" (186 MB, OFF), "Galaxy Store" (353 MB, OFF), "Gmail" (342 MB, OFF), "Messages" (57.67 MB, OFF). The app sends the user here automatically after "Download App & Install".
- **State 3 (p6 left):** same banner; the button is replaced by a progress bar with label "Downloading 53%" (percentage updates); red warning with info icon "Please do not close the app while downloading". "Network Status ● অনলাইন" remains top-right (no back arrow in this frame).
- **State 4 (p6 right):** dimmed update screen (button now reads "Install", dark/disabled); OS dialog "Do you want to update this app?" buttons "Cancel", "Update". After update -> "Open".
- **Rules:** R-006, R-007, R-008, R-009. **Permissions:** Install unknown apps (per app), network, storage (APK, implied).
- **Data:** app version (current vs available 1.0.4), downloaded APK. **Online-only** (download).
- **Unclear items for this screen:** U-02, U-03, U-04 (section F).

## S-05 | Forced in-app update (Path B, Bangla) | "অ্যাপ্লিকেশান আপডেট প্রক্রিয়া" | p7
- **Entry:** after logging in with the AMO ID, when an update is mandatory. **Exit:** on completion -> dashboard S-06 (p12 callout "আপডেট সম্পন্ন হওয়ার পরে ... ড্যাশবোর্ড").
- **State 1 (prompt):** illustration (phone, chat/like bubbles, megaphone). Text (4 lines): "কিছু নতুন আপডেট পাওয়া গেছে. এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" (Some new updates have been found. To continue using this app, you must update!). Full-width blue-gradient button "আপডেট" (Update) with a phone/update icon. Footer: "Current version 1.0.2".
- **State 2 (progress):** illustration (phone with gears + refresh arrows); heading "অ্যাপ আপডেট হচ্ছে" (App is updating); progress bar (~55%); caption "প্রসেসিং(৪৬/৮৪)" (Processing (46/84) - N of M, unit not stated); red tip "টিপস: অ্যাপটি বন্ধ করবেন না" (Tip: Do not close the app).
- **Callout inconsistency:** callout says click the "শুরু করো" (Start) button but the button is labelled "আপডেট".
- **Rules:** R-010, R-011. **Data:** app version, update payload (counter 46/84). **Online** (implied).
- **Unclear items for this screen:** U-04 (section F).

## S-06 | AMO Dashboard (home) | "ড্যাশবোর্ড" (no title bar text; purple header band) | pp 8-12 (and repeated on pp 14, 17, 21, 25, 34, 37, 39, 40, 42, 46, 47, 55, 66, 69, 74, 77)
- **Role:** AMO. **Entry:** after login/update; back from any menu. **Exits:** each tile (below).
- **Header band (purple):** person avatar icon; line 1 "<display name> (<username>)", e.g. "AMO-5756 (amo5756)", "AMO 2 (ss344002)", "amo5001 (amo5001)"; line 2 (small) "<territory/point name><role>, <yyyy-mm-dd>", e.g. "Savar BazarAMO, 2025-12-01", "BepariparaSS, 2025-11-01", "AMO-AgrabadAMO, 2026-04-26". Meaning of the date is not stated (R-017/U-06).
- **Menu grid (3 columns, rounded white tiles, icon + Bangla label).** All tiles, one-line function (per manual), target screen:
  | # | Label (verbatim) | English | Function (manual wording) | Screen |
  |---|---|---|---|---|
  | 1 | অ্যাটেনডেন্স | Attendance | AMO's own check-in / check-out (p8) | S-08 |
  | 2 | জয়েন্ট কল | Joint Call | observe an SR's activity (p8) | S-11 |
  | 3 | কন্ট্রল কল (tile) / কন্ট্রোল কল (callout, KPI) | Control Call | AMO's own call activity (p8) | S-13 |
  | 4 | টিম লোকেশন | Team Location | live location of team SRs (p8) | S-24 |
  | 5 | টিম পারফর্মেন্স | Team Performance | route-based target & achievement of all SRs (p9) | S-25 |
  | 6 | টাস্ক ডেলিগেশন | Task Delegation | list assigned tasks, assign tasks (p8) | S-27 |
  | 7 | লাইভ ড্যাশবোর্ড (icon has "LIVE" tag) | Live Dashboard | live Sales, Strike Rate, Geo Fencing Status, Login Status per route (p9) | S-29 |
  | 8 | এস আর স্টক | SR Stock | SKU-wise lifted stock of all SRs in the AMO's Zone (p9) | S-30 |
  | 9 | আউটলেট (red badge: ৮ on p8-10, ০ on p12/14/37...) | Outlet | add new outlet, permanent close, change info; verify SR requests (p9, p47) | S-32 |
  | 10 | স্টক | Stock | "as every day" stock entry (p9) | S-45 |
  | 11 | বিক্রয় | Sales | start sales work (p10) | S-17 (route/retailer context: see U-12) |
  | 12 | মেমো | Memo | view again and print the post-sale memo; edit sale (p10, p42) | S-46 |
  | 13 | সারসংক্ষেপ | Summary | whole day's sales (p10, p46) | S-50 |
  | 14 | বিক্রয় জমা | Sales Deposit/Submit | end-of-day submit of sales data (p10, p66) | S-51 |
  | 15 | আস্থা (round dark red/gold logo icon) | Astha | route-based Astha outlets' STD and memo target & achievement (p10, p69) | S-52 |
  | 16 | Report (English label, bar/donut icon; only in variant 3) | Report | reports (p74) | S-55 |
  | 17 | সেটিং | Settings | change language, send sales/data file to support, logout (p10, p77) | S-59 |
- **Chevron "^"** under the grid (collapse/expand the tile panel; not explained).
- **KPI tiles (2x2 light-blue, each icon + label + "achieved/target" value; all "0/0" in every capture):**
  | Label (verbatim) | English | Meaning (p11 verbatim) |
  |---|---|---|
  | আজকের টার্গেট | Today's target | "আজকের দিনের সম্পূর্ণ করা টার্গেট / আজকের দিনের টোটাল টার্গেট।" (today's completed target / today's total target) |
  | টোটাল কল টার্গেট | Total call target | "চলতি মাসের সম্পূর্ণ করা টার্গেট / চলতি মাসের জন্য টোটাল টার্গেট।" (current month completed / total) |
  | কন্ট্রোল কল টার্গেট | Control call target | "চলতি মাসের সম্পূর্ণ করা কন্ট্রোল কল / চলতি মাসের কন্ট্রোল কল টার্গেট।" |
  | জয়েন্ট কল টার্গেট | Joint call target | "চলতি মাসের সম্পূর্ণ করা জয়েন্ট কল / চলতি মাসের জয়েন্ট কল টার্গেট।" |
  Icons: dart (today), phone handset (total call), mobile phone (control call), people/handshake (joint call). Tiles look tappable but no action is described.
- **Menu variants observed:** V1 (pp 8-10, user amo5756): 16 tiles, no "Report", Outlet badge ৮. V2 (p11, user ss344002 "AMO 2"): 13 tiles - NO Team Performance, SR Stock, Astha; order: Attendance, Joint Call, Control Call / Team Location, Task Delegation, Live Dashboard / Outlet, Stock, Sales / Memo, Summary, Sales Deposit / Settings; no badge. V3 (p12 onward, user amo5001): 17 tiles incl. Report; Outlet badge ০.
- **Rules:** R-017, R-018, R-019, R-020, R-021, R-022, R-023, R-024, R-025. **Hardware:** location (check-in, calls), Bluetooth printer (memo/summary print).
- **Data read:** AMO profile (name, username, territory/point, role, date), monthly/daily call targets and achieved counts, Outlet pending-request count (badge). **Offline:** not stated; targets "come from the server".
- **Unclear items for this screen:** U-06, U-07, U-08, U-13 (section F).

## S-07 | Printer pairing (Android Bluetooth settings) | "মোবাইল Bluetooth এর সাথে প্রিন্টার Pair করার প্রক্রিয়া" | pp 12-14
- **Role:** OS. **Entry:** first-time set-up; precondition on p12: phone Settings > Bluetooth turned on (switch "Off" -> "On", helper "Turn on Bluetooth to connect to nearby devices.") and the mobile printer's power on. **Exit:** return to dashboard; then S-45 (Stock) to connect in-app.
- **Steps/screens (all OS):** (a) Bluetooth settings, scanning: "Stop" action, "On" toggle, text "Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices.", "Available devices" list incl. printer "RPP02N" -> tap it. (b) Dialog "Bluetooth pairing request" / "Enter PIN to pair with RPP02N (Try 0000 or 1234)." field "PIN" (masked, 4 chars shown) with Samsung keyboard (English (UK), Done key), buttons "Cancel", "Pair" -> manual says enter "0000" and tap Pair. (c) After pairing: "Paired devices" shows "RPP02N" with gear icon; "Available devices" lists unrelated devices ("28:D0:EA:1F:F0:DA" - "Device name will appear when this device is connected.", "QCY H3-APP", "oraimo SpaceBuds Neo+ BLE").
- **Rules:** R-012, R-013. **Hardware:** Bluetooth classic, 58 mm thermal printer model "RPP02N" (model name appears only here). **Data:** paired printer (OS-level). **Offline:** fully local.


---------------------------------------------------------------------
## S-08 | Attendance | "অ্যাটেনডেন্স" | pp 17-20
- **Entry:** dashboard tile "অ্যাটেনডেন্স" (p17 callout: "আপডেট সম্পন্ন হওয়ার পরে ... অ্যাটেনডেন্স অপশনে ক্লিক করুন" - after the update is complete). **Exits:** back arrow "◁"; "চেক ইন" -> S-09; "চেক আউট" -> S-10.
- **Layout:** app bar back "◁" + title "অ্যাটেনডেন্স". User card: person icon, "AMO-5756 (amo5756)" + sub-line "Savar BazarAMO, 2025-12-02" (or "AMO 2 (ss344002)" / "BepariparaSS, 2025-11-01" in other captures). Location card: pin icon + reverse-geocoded address (e.g. "4th Floor), 28, 4th Floor), Gulshan, Dhaka, Dhaka District, Dhaka Division, 1213, Bangladesh"; "1a, 1a Lalbagh Rd, Lalbagh, Dhaka, Dhaka District, Dhaka Division, 1211, Bangladesh") + refresh icon "⟳".
- **States (verbatim):**
  | State | Status text | Left button/tile | Right button/tile |
  |---|---|---|---|
  | Not checked in (p17,18) | "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।" | "চেক ইন" green gradient, enter-arrow icon, ACTIVE | "চেক আউট" grey gradient, exit-arrow icon, inactive |
  | Checked in, before 5 PM (p19 left) | "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" | dashed green tile with green check "চেক ইন সম্পন্ন হয়েছে" (Check-in completed), disabled | dashed orange tile with warning triangle "চেক আউট ৫টার পরে সক্রিয় হবে" (Check-out will become active after 5 o'clock), disabled |
  | Checked in, check-out active (p19 right) | same as above | dashed green tile, green check, "Already Checked-In" (English) | red gradient "চেক আউট" with exit icon, ACTIVE |
  | Both done (p20 right) | "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" | green check "Already Checked-In" | red-outlined tile red check "Already Checked-Out" |
- **Yellow note box (p19, full width):** "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।" (Note: you can do the check-out process from 5 PM in the afternoon.)
- **Refresh button:** p17 callout "বর্তমান লোকেশান পেতে কোনো সমস্যার সম্মুখীন হইলে Refresh বাটনে ক্লিক করুন।" (if you face any problem getting the current location, click Refresh).
- **Rules:** R-026, R-027, R-029, R-030, R-032. **Hardware:** location (GPS) + reverse geocoding (address string - online lookup INFERRED). 
- **Data written:** attendance (user, date, check-in time, check-out time, location/address, probably lat/long - fields not shown). **Offline:** not stated.
- **Unclear items for this screen:** U-14 (section F).

## S-09 | Check-in bottom sheet | "চেক ইন করা হচ্ছে" | p18
- **Entry:** tap "চেক ইন" on S-08. **Exit:** on hold-complete -> S-08 in "Checked in" state (button disabled).
- **Controls:** drag-handle bar; title (green) "চেক ইন করা হচ্ছে" (Checking in); time chip with clock icon "04:05 PM" (12-hour, Latin digits; source device vs server not stated); big green semicircular button with enter icon and "চাপ দিয়ে ধরে রাখুন" (Press and hold) - confirmation is by long-press (duration not stated).
- **Callout:** "এরপর চেক ইন এর সময় চেক করুন। তারপর সবুজ বাটনটি চাপ দিয়ে ধরে রাখুন।" (check the check-in time, then press and hold the green button).
- **Rules:** R-028. **Data:** check-in timestamp (+ location). 

## S-10 | Check-out bottom sheet | "চেক আউট করা হচ্ছে" | p20
- **Entry:** tap active "চেক আউট" on S-08 (enabled from 5 PM, R-030). **Exit:** S-08 "both done" state.
- **Controls:** drag handle; title (red) "চেক আউট করা হচ্ছে" (Checking out); time chip "12:53 PM" (this sample time contradicts the 5 PM rule - U-14); big red semicircular press-and-hold button with exit icon "চাপ দিয়ে ধরে রাখুন".
- **Callout:** "এরপর চেক আউট এর সময় চেক করুন। তারপর লাল বাটনটি চাপ দিয়ে ধরে রাখুন।"
- **Rules:** R-031, R-032. After done: Check Out button disabled "Already Checked-Out" (p20 callout: "চেক আউট বাটনটি Disable হয়ে যাবে এবং চেক আউট স্ট্যাটাস পরিবর্তন হয়ে যাবে।").
- **Unclear items for this screen:** U-14 (section F).

## S-11 | Joint Call Activity | "জয়েন্ট কল একটিভিটি" | pp 21 (right), 23 (left), 24 (right, dimmed)
- **Entry:** dashboard tile "জয়েন্ট কল". (p21's slide is titled "কন্ট্রোল কল করার প্রক্রিয়া" but its screenshots are THIS screen - see U-10.) **Exit:** tile "এসআর কল অ্যাসেসমেন্ট" -> S-12; back arrow.
- **Controls:**
  | Control | Type | Required | Default/Value | Notes |
  |---|---|---|---|---|
  | Route | dropdown | yes (INFERRED) | "Savar BazarDaily" | list of AMO's routes (not expanded) |
  | Retailer | dropdown | yes (INFERRED) | "Babu Store (1618411-[phone]-Madrasa Road)" | label "Name (code-number-address)" (R-109); placeholder text (seen on S-13) "রিটেইলার নির্বাচন করুন" |
  | Alphabet filter strip | single-select chips on dark bar | no | "All" (green) | All A B C D E F G H I / J K M N P R S T V Z (L,O,Q,U,W,X,Y absent -> dynamic, INFERRED) |
  | Out-of-range panel (p21 only) | warning panel | - | red triangle + "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" | buttons "ম্যানুয়াল ওভাররাইড" (blue, full width) and "রিফ্রেশ" (dark grey, full width) |
  | Activity tile (p23) | icon tile | - | "এসআর কল অ্যাসেসমেন্ট" (red-boxed) | only tile visible; others unknown |
- **Callouts:** p21 "প্রতিদিনের কার্য সম্পূর্ণ করার জন্য নির্দিষ্ট রুট এবং নির্দিষ্ট দোকান নির্বাচন করুন। AMO যদি নির্দিষ্ট সীমানার বাইরে অবস্থান করে তাহলে ম্যানুয়াল ওভাররাইড অপশন নির্বাচন করতে হবে।"; p23 "SR এর কল পর্যবেক্ষণ করার জন্য এই অপশনটি নির্বাচন করুন ।" and "AMO তার এস আর কল অ্যাসেসমেন্ট এই অপশন থেকে সম্পূর্ণ করতে পারবেন।" with bullets "৫ স্টেপ সেলস কল অ্যাসেসমেন্ট", "রিলেশনশিপ এসেসমেন্ট", "সার্ভিস কোয়ালিটি এসেসমেন্ট" and "AMO তার মার্কিং ১ থেকে ৫ এর মধ্যে মূল্যায়ন করতে পারবে।" (illustration: star widget with "Low" under star 1, "High" under star 5).
- **Rules:** R-033, R-034, R-035, R-036, R-038. **Hardware:** location (range check); camera if Manual Override (S-14). **Data read:** AMO routes, retailers per route with coordinates. **Offline:** not stated (range check uses outlet coordinates - INFERRED local).
- **After save (p24 right):** screen is shown dimmed with route AND retailer still selected (contrast S-13/S-22 where retailer resets).
- **Unclear items for this screen:** U-10, U-11, U-19 (section F).

## S-12 | SR Call Assessment (Joint Call form) | "এসআর কল অ্যাসেসমেন্ট" | pp 23-24
- **Entry:** tile on S-11. **Exit:** "সংরক্ষণ" -> success dialog -> back to S-11.
- **Header (read-only card, shop icon):** "রুট:" "Savar BazarDaily"; "খুচরা বিক্রেতাঃ" "Babu Store (1618411-17234127..." (truncated by card edge). The SR being assessed is NOT chosen on the form (U-16).
- **Instruction (ⓘ):** "SR নিম্নলিখিত পদক্ষেপগুলি কীভাবে সম্পন্ন করেছে তা মূল্যায়ন করুন। চিহ্নিতকরণের ক্ষেত্রে, 1 হল সর্বনিম্ন স্তর এবং 5 হল সর্বোচ্চ স্তর।" (Evaluate how the SR completed the following steps. For marking, 1 is the lowest level and 5 is the highest.)
- **Section 1 (orange header) "●৫ ধাপ সেলস কল অ্যাসেস্টমেন্ট" (5-step sales call assessment):** items, each = blue bold title, grey guidance in parentheses, 5-star control, chips "Low" (red, under star 1) and "উচ্চ" (green, under star 5):
  1. Title (English) "Summarise the situation" - "(যা দেখতে হবে: OHS কাউন্ট, OOS, Product quality চেক, অপর্চুনিটি এবং ইস্যু বুঝা)" (What to look for: OHS count, OOS, product quality check, understanding opportunity and issue).
  2. "ধারণাটি বর্ণনা করুন" (Describe the concept) - "(যা দেখতে হবে: SOQ রিকুয়ারম্যান্ট ঠিক মতন বুঝানো, অফার টি তুলে ধরা, ক্যাম্পেইন কমিউনিকেশন)" (explain the SOQ requirement properly, present the offer, campaign communication).
  3. "জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা" (Explain how the thing will work) - "(যা দেখতে হবে: অফার এর মডালিটি ঠিক মতন বুঝানো, দোকানদারের ব্যবসায়িক বেনিফিট হাইলাইট করা)" (explain the offer's modality properly, highlight the shopkeeper's business benefit).
  4./5. NOT SHOWN IN THE MANUAL (title and guidance unknown). Only the tail "...করা, এনসিউর করা)" ("...doing, ensuring)") of one of these items appears at the top of p24 left (U-01).
- **Section 2 (orange) "●রিলেসনশিপ এসেসম্যন্ট" (Relationship assessment)** - no title line, only guidance: "(যা দেখতে হবে: সম্ভাষণ দেওয়া, দোকানদারের নাম জানা, দোকানদারের সাথে আন্তরিকতা)" (giving a greeting, knowing the shopkeeper's name, cordiality) + 5 stars + Low/উচ্চ.
- **Section 3 (orange) "●সার্ভিস কোয়ালিটি এসেসম্যন্ট" (Service quality assessment)** - guidance: "(যা দেখতে হবে: টাইমলি দোকানে আসা, মেমো সঠিক ভাবে করা এবং সংরক্ষণ, দোকানদারের অভিযোগ সমাধান করা)" (coming to the shop on time, preparing and saving the memo correctly, resolving the shopkeeper's complaints) + 5 stars + Low/উচ্চ.
- **Star control:** 5 stars per item; in every screenshot star 1 is already selected (blue); item 1 shows 2 selected; grey = unselected. Legend illustration uses "Low"/"High" (English) while the form uses "Low"/"উচ্চ".
- **Save button:** full-width lime-green "সংরক্ষণ" (Save) with floppy icon. Callout: "এরপর সংরক্ষন অপশনে ক্লিক করে ডাটা সংরক্ষন করুন।"
- **Success dialog (p24 right):** green check, "ডেটা সফলভাবে সংরক্ষিত হয়েছে।", green button "ওকে". Callout: "এরপর ডাটা সংরক্ষন এর সফল মেসেজ দেখতে পারবেন।"
- **Rules:** R-033, R-038, R-039, R-040, R-041. **Data written:** SR call assessment (AMO, route, retailer, per-item 1-5 score in 3 sections, time) - SR id not on form. **Offline:** not stated (save message does not say local vs server).
- **Domain terms in guidance:** OHS (likely on-hand stock), OOS (out of stock), SOQ (suggested order quantity); not expanded in the manual.
- **Unclear items for this screen:** U-01, U-15, U-16, U-17 (section F).

## S-13 | Control Call Activity (route + retailer + range check) | "কন্ট্রল কল অ্যাক্টিভিটি" (title) / "কন্ট্রোল কল" (callouts) | pp 25, 32 (right, dimmed)
- **Entry:** dashboard tile "কন্ট্রল কল". **Exit:** in range -> tiles S-16; out of range -> Manual Override (S-14) / Refresh; back arrow.
- **Controls:** 
  | Control | Type | Required | Default/Value | Notes |
  |---|---|---|---|---|
  | Route (callout calls it "সেকশন" = section) | dropdown | yes (INFERRED) | "Savar BazarDaily" | route names like "Savar BazarDaily" or "Savar Bazar(Sat, Mon, Wed)" (R-110) |
  | Retailer ("দোকান") | dropdown | yes | value "Sujon Store (1618414--Madrasa Road)"; placeholder "রিটেইলার নির্বাচন করুন" (Select retailer) | label "Name (code-phone-address)", empty phone gives "--" (R-109) |
  | Alphabet strip | single-select chips | no | "All" | All, A,B,C,D,E,F,G,H,I,J,K,M,N,P,R,S,T,V,Z |
  | Out-of-range panel | warning + 2 buttons | shown only when out of range | red triangle; "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" | "ম্যানুয়াল ওভাররাইড" (blue), "রিফ্রেশ" (dark grey; re-checks location - INFERRED) |
- **Callout (p25 left):** "কন্ট্রোল কল এক্টিভিটি করার জন্য ARON AMO অ্যাপ্লিকেশন এর ড্যাশবোর্ড থেকে কন্ট্রোল কল অপশনে ক্লিক করুণ।" **Callout (p25 right):** "প্রতিদিনের কার্য সম্পূর্ণ করার জন্য নির্দিষ্ট সেকশন এবং নির্দিষ্ট দোকান নির্বাচন করুন। AMO যদি নির্দিষ্ট সীমানার বাইরে অবস্থান করে তাহলে ম্যানুয়াল ওভাররাইড অপশন নির্বাচন করতে হবে।"
- **Rules:** R-033, R-034, R-035. **Hardware:** location. **Data read:** routes, retailers (name, code, phone, address, coordinates), AMO location. Radius in metres NOT stated.
- **Unclear items for this screen:** U-11, U-19 (section F).

## S-14 | Manual Override camera | "ম্যানুয়াল ওভার রাইড প্রক্রিয়া" | p26 (left)
- **Entry:** "ম্যানুয়াল ওভাররাইড" on S-13/S-11. **Exit:** after the photo -> S-16 tiles.
- **UI:** full-screen camera viewfinder (stock-camera-like); bottom row: camera-flip icon (left), large white round shutter (centre, red-boxed), "✕" in circle (right, cancel/close). No flash/gallery. 
- **Callout:** "এরপর নির্দিষ্ট আউটলেটের ছবি উঠাবেন, এবং ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" (take a photo of the outlet; as soon as the photo is taken the location info for that outlet is updated).
- **Rules:** R-036. **Hardware:** camera, location. **Data written:** outlet photo, outlet location (lat/long) overwritten (which GPS - AMO device, INFERRED); override flag not stated.
- **Unclear items for this screen:** U-11, U-28 (section F).

## S-15 | Camera and Audio permission prompts (Android runtime) | "Camera এবং Audio Permission এর প্রক্রিয়া" | p22
- **Role:** OS. **Entry:** when the camera opens (screen that triggers it not shown; black background). 
- **Dialog 1:** blue camera icon, "Allow ARON AMO to take pictures and record video?" options "While using the app" (instructed), "Only this time", "Don't allow". Callout: "Camera ওপেন হবার পর Camera Permission এর Alert দেখতে পারবেন। অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।"
- **Dialog 2:** blue microphone icon, "Allow ARON AMO to record audio?" same 3 options. Callout: "এরপর Audion Permission Alert দেখতে পারবেন। অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।"
- **Rule:** R-037. Implies the call activity can record video with audio (purpose/duration not stated - U-18).
- **Unclear items for this screen:** U-18 (section F).

## S-16 | Control Call menu (Sales / SR Perf. Assessment / Survey) | "কন্ট্রোল কল মেনু তালিকা" | pp 26 (right), 27
- **Entry:** S-13 once in range, or after Manual Override photo (S-14). **Exits:** each tile.
- **Layout:** same app bar "কন্ট্রল কল অ্যাক্টিভিটি"; route dropdown "Savar BazarDaily"; retailer "Sujon Store (1618414--Madrasa Road)"; alphabet strip; out-of-range panel replaced by 3 tiles:
  1. "বিক্রয়" (Sales) -> S-17. Callout: "কন্ট্রোল কল অপশন থেকে ইউজার বিক্রয় অপশনে ক্লিক করে বিক্রয় কাজ সম্পন্ন করতে পারবেন।"
  2. "এসআর পারফ. অ্যাসেসমেন্ট" (SR Perf. Assessment; wraps on two lines) -> S-22. Callout: "কন্ট্রোল কল অপশন থেকে ইউজার এসআর পারফ. অ্যাসেসমেন্ট এর কাজ সম্পন্ন করতে পারবেন।"
  3. "সার্ভে" (Survey) -> S-23. Callout: "নির্দিষ্ট আউটলেটে সার্ভে করার জন্য সার্ভে বাটনে ক্লিক করুন।"
- **Rules:** R-042, R-043. **Navigation (p27 verbatim summary):** Dashboard > Control Call > (route, retailer) > {Sales | SR Perf. Assessment | Survey}.

## S-17 | Sales (order entry) | "বিক্রয়" | pp 28 (left), 30 (left)
- **Entry:** tile "বিক্রয়" on S-16 (Control Call). The dashboard "বিক্রয়" tile (p10: "বিক্রয় কাজ শুরু করতে পারবেন") presumably leads to the same flow (U-12). **Exit:** "এগিয়ে যান →" -> S-18; "পূর্বের সেল ডাটা দেখুন" -> S-20; back.
- **Header card (store icon):** "রুট: Savar BazarDaily"; "খুচরা বিক্রেতাঃ Siyam (1618410-[phone]-Ma…" (truncated, no ellipsis).
- **Button:** full-width grey-green with history icon "পূর্বের সেল ডাটা দেখুন" (View previous sale data).
- **Product list (scrolls), one card per SKU:** pack image thumbnail with blue circular badge at bottom-left showing a Bangla digit (১ on MaxR-10S, ০ on Avon-20S, ১ on ABS; meaning unexplained); SKU name in bold purple ("MaxR-10S", "Avon-20S", "ABS", "Aster"); quantity stepper = green round "−", numeric text field (underlined), green round "+" (values: MaxR-10S ১০, Avon-20S ০, ABS ২৫, Aster ১); right column with 3 icon+value lines: line 1 number (Avon-20S ১১০০০, ABS ১০০০০, Aster ৫৫০ - NOT unit price; meaning unknown), line 2 "0%" (orange/brown, every card), line 3 "০" (every card). Meanings of badge and 3 lines: U-20.
- **Bottom sticky bar (dark purple):** cart icon + running total (e.g. "১১৬.৪২"); green button "এগিয়ে যান →" (Proceed).
- **Callout:** "SKU অনুযায়ী দোকানদারের চাহিদামত শলাকার পরিমাণ উল্লেখ করুন। এরপর এগিয়ে যান অপশনে ক্লিক করুন।" (According to the SKU, state the quantity of sticks (শলাকা) as per the shopkeeper's demand. Then click Proceed.)
- **Rules:** R-044, R-045, R-046, R-051, R-052. **Data read:** route, retailer, SKU catalogue (image, name, category, price), stock (INFERRED). **Data written:** none until print/save (see S-18). **Offline:** not stated.
- **Unclear items for this screen:** U-12, U-20, U-22 (section F).

## S-18 | Sale Review | "নিরীক্ষণ" | pp 28 (right), 29
- **Entry:** "এগিয়ে যান →" on S-17. **Exits:** "প্রিন্ট" (print), "প্রোডাক্ট QC" (S-21), back.
- **Header:** top-right small round icon with a red eye-with-slash (purpose unexplained, U-21). Large text "Siyam (1618410-18327145…" (truncated) / "Babu Store (1618411-[phone]-Madrasa Road)"; small grey: "রুট: Savar BazarDaily" (or "রুট: Savar Bazar(Sat, Mon, Wed)") and "ক্লাস্টার: Savar Metro".
- **Table (dark grey header):** "এসকেইউ" (SKU) | "পরিমাণ" (Quantity) | "মূল্য" (Value). Sample rows (p28): MaxR-10S ১০ ৮০.০০; ABS ২৫ ২০.০০; Aster ১ ১২.৫০; FB ১ ২.৩৩; SL ১ ১.৫৮. Category subtotal rows (grey): "মোট সিগারেট" ১০ ৮০.০০; "মোট বিড়ি" ২৫ ২০.০০; "মোট লাইটার" ১ ১২.৫০; "মোট ম্যাচ" ২ ৩.৯২. Bold final: "সর্বমোট" ৩৮ ১১৬.৪২. (p29 sample: MaxR-10S ১০ ৮০.০০; AB-12s ১২ ৯.০০; Aster ১ ১২.৫০; FB ১ ২৮.০০; SL ১ ১৯.০০; subtotals ১০/৮০.০০, ১২/৯.০০, ১/১২.৫০, ২/৪৭.০০; সর্বমোট ২৫ ১৪৮.৫০.)
- **Checkbox (red-boxed, unchecked by default):** "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" (Mark this sale as due). When ticked a dialog appears (S-19); afterwards the label becomes "Baki ৪৮ টাকা" (checked, purple).
- **Buttons side by side:** orange "প্রোডাক্ট QC" (cart icon); green "প্রিন্ট" (printer icon). On p29 a second box below is cut off.
- **Info box (ⓘ):** "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" (You can print later from the Memo section.)
- **Callouts:** p28 "এরপর উক্ত পেইজ থেকে আপনারা বিক্রয় এর পরিমাণ সঠিক আছে কিনা সেটি যাচাই করতে পারবেন। এবং বিক্রয় যদি বাকীতে হয়ে থাকে তাহলে বিক্রয়টি ক্রেডিট হিসাবে চিহ্নিত করুন অপশনটি নির্বাচন করুন।"; p29 right "এরপর আপনি উক্ত দোকানের জন্য অবশিষ্ট বাকি টাকার পরিমাণ দেখতে পারবেন।"
- **Rules:** R-047, R-048, R-049, R-050, R-051, R-053. **Hardware:** Bluetooth printer (INFERRED; not named on p28). **Data written:** sale (retailer, route, SKU qty, values, totals, credit flag), memo. **Offline:** not stated. No Save/confirm button other than Print is shown - what persists the sale is unclear (U-22).
- **Unclear items for this screen:** U-21, U-22, U-23, U-24, U-25, U-61 (section F).

## S-19 | Partial-payment dialog | "পরিশোধিত টাকার পরিমাণ লিখুন" | p29 (left)
- **Entry:** ticking the due checkbox on S-18. **Exit:** "হ্যাঁ" -> S-18 with label "Baki <n> টাকা"; "না" (cancel, INFERRED).
- **Dialog:** title "পরিশোধিত টাকার পরিমাণ লিখুন" (Enter the amount of money paid); body "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?" (How much money are you collecting from the retailer now?); outlined numeric field with floating label "আদায়কৃত অর্থ" (Amount collected), typed value "100" (Latin digits, cursor), read-only right suffix "বাকিঃ ৪৮.৫০" (Due: 48.50; = 148.50 - 100 live); buttons green "হ্যাঁ" (Yes), red "না" (No).
- **Callout:** "বিক্রয়টি বাকি হিসাবে চিহ্নিত করার পর এই পেজটি দেখতে পারবে। এখানে আপনি রিটেইলার থেকে ঐ মুহূর্তে সংগ্রহীত টাকার পরিমাণ উল্লেখ করুন। এবং এই পরিমাণটি অবশ্যই সর্বমোট টাকার পরিমাণ এর থেকে কম হতে হবে। এরপর 'হ্যাঁ' অপশনটি নির্বাচন করুন।"
- **Rules:** R-049, R-050. **Validation:** collected MUST be less than grand total; no error text shown (U-24). **Data written:** collected amount, outstanding due per retailer. 
- **Unclear items for this screen:** U-24 (section F).

## S-20 | Sale Data (previous sales by date) | "সেল ডাটা" | p30
- **Entry:** "পূর্বের সেল ডাটা দেখুন" on S-17 (p30 red box is on the wrong button; callout is about this one). **Exit:** back.
- **Controls:** date-picker field (red-boxed), outlined with calendar icon, text "November 26, 2025" (English long format); under it a purple echo "2025-11-26". Default date not stated.
- **Table (purple header):** "এসকেইউ" | "পরিমাণ" | "মূল্য", thumbnail + name per row. Sample: MaxR-10S ৫২০ ৪,১৬০.০০; MaxR-20S ১,০০০ ৮,০০০.০০; MaxB-10S ৫০০ ৪,০০০.০০; AB-12s ৬০০ ৪৫০.০০; ABS ১,২৫০ ১,০০০.০০; Aster ১০০ ১,২৫০.০০; FB ৬০০ ১,৮০০.০০. Total band "মোট" ৪,৫৭০.০০ (quantity shown with 2 decimals) ২০,২৬০.০০ (row sum is 20,660.00 - U-26).
- **Callout:** "“পূর্বের সেল ডাটা দেখুন” অপশনে ক্লিক করার পর উক্ত দোকানের পূর্ববর্তী সেল ডাটা দেখতে পারবেন। এবং ইউজার যেকোন তারিখ এর সেল দেখতে পারবেন তারিখ নির্বাচন করার মাধ্যমে।"
- **Rules:** R-052. **Data read:** history by retailer/date/SKU (qty, value). **Offline:** not stated (arbitrary dates suggest server query - INFERRED).
- **Unclear items for this screen:** U-25, U-26 (section F).

## S-21 | Product QC | "প্রোডাক্ট QC" button | p28 (button only) + p66-68 table row "কিউসি"
- **NOT DOCUMENTED beyond the button label.** The screen opened by "প্রোডাক্ট QC" (orange, cart icon) on S-18 is never shown. The Sales Deposit table counts a "কিউসি" (QC) record type (S-51). Purpose assumed: product quality check - UNVERIFIED (U-23).

## S-22 | SR Performance Assessment (Distribution / OOS / POSM) | "এসআর পারফ. অ্যাসেসমেন্ট" (title) / "SR PERFORMANCE ASSESMENT এর প্রক্রিয়া" (slide) | pp 31-32
- **Entry:** tile on S-16. **Exit:** "সংরক্ষণ" -> success dialog -> S-13 with retailer cleared (route stays).
- **Header card:** "রুট: Savar BazarDaily"; "খুচরা বিক্রেতাঃ Borisal (1618412-[phone]-…" (truncated).
- **Section 1 (orange band, white bullet) "ডিস্ট্রিবিউশন পারফর্মেন্স" (Distribution Performance):** ⓘ "অ্যাসেসমেন্ট মুহূর্তে এস.কে.ইউ দোকানে থাকলে অথবা OOS হয়ে গেলেও টিক চিহ্ন দিন।" (At the moment of assessment, tick if the SKU is in the shop, or even if it has gone OOS.) 15 checkboxes in a 3-column grid (English labels), order: Maxim [checked in sample], Avon [checked], ARIS [checked]; Marise, Black Diamond, Sunmoon; Supreme, Special Abul Bidi, Existing Abul Bidi/ 42 No.; Ananda Bidi, Abul Bidi Gold, Abul Bidi Style; Aster, Flame Box, Salmon. Multi-select, optional, default unchecked (the 3 ticks are sample data).
- **Section 2 (purple band) "OOS Performance":** ⓘ "OOS থাকলে টিক চিহ্ন দিন।" (Tick if OOS). Same 15 checkboxes; in the screenshot unchecked ones are greyed (INFERRED: only SKUs ticked in Distribution are enabled).
- **POSM block (white card):** heading "POSM"; two checkboxes "হ্যাঁ" (Yes, unchecked in sample) and "না" (No, checked in sample); appear mutually exclusive (INFERRED).
- **Save:** full-width green "সংরক্ষণ" with floppy icon.
- **Callouts:** p31 left "SR Per Assessment এর জন্য এই অপশনটি ব্যবহার করা হয় / Distribution Performance / নির্দিষ্ট দোকানে AKTC এর যেই যেই SKU বিক্রয় করা হয় সেটি নির্বাচন করুন ।"; p31 right "(Out Of Stock) OOS Performance / যেই যেই SKU নির্দিষ্ট দোকানে বিক্রয় করা হয় কিন্তু এই মুহূর্তে স্টক নেই, সেই SKU গুলা নির্বাচন করুন ।"; p32 left "নির্দিষ্ট দোকানে যদি কোন স্টিকার অথবা ব্যানার থাকে তাহলে “হ্যাঁ” অথবা “না” অপশন এর মাধ্যমে এন্ট্রি করুন। এরপর সকল ডাটা সংরক্ষন এর জন্য “সংরক্ষন” অপশনে ক্লিক করুন।"; p32 right "সংরক্ষণ বাটনে ক্লিক করার পরে এলার্ট দেখতে পারবেন।"
- **Success dialog (p32 right):** green check, "ডেটা সফলভাবে সংরক্ষিত হয়েছে।", button "ওকে".
- **Rules:** R-054, R-055, R-056, R-057. **Data written:** assessment (retailer, AMO, date, distributed SKU set, OOS SKU set, POSM yes/no). **Data read:** AKTC brand list (fixed vs server-driven unknown). Sync table row: "ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা" (S-51).
- **Unclear items for this screen:** U-29 (section F).

## S-23 | Survey | "সার্ভে" | p27 (tile only) + p66-68 table row "সার্ভে"
- **NOT DOCUMENTED beyond the tile.** Callout "নির্দিষ্ট আউটলেটে সার্ভে করার জন্য সার্ভে বাটনে ক্লিক করুন।" The survey form (questions, fields) is never shown (U-27). Sales Deposit counts "সার্ভে" records.


---------------------------------------------------------------------
## S-24 | Team Location | "টিম লোকেশন" (title) / "টিম লোকেশান দেখার প্রক্রিয়া" (slide) | p33
- **Entry:** dashboard tile "টিম লোকেশন". **Exit:** back.
- **Controls:** single-select SR dropdown (list shows entries like "SR-Kakoli - 1", appearing twice when expanded - U-30; format "SR-<Name> - <n>"); Google Map below: compass top-left, "my location" crosshair top-right, orange scooter/rider marker with info-window label = SR name (e.g. "SR-Kakoli - 1"), bottom-right Google "directions" and "open in Google Maps" buttons, Google logo, POI labels from Google. No "last seen" timestamp, battery or distance.
- **Callouts:** "আপনার টিমের SR দের লাইভ লোকেশন দেখার জন্য টিম লোকেশান বাটনে ক্লিক করুন। এরপর টিম লোকেশান দেখার জন্য SR লিস্ট থেকে নির্দিষ্ট SR সিলেক্ট করুন।"; "নির্দিষ্ট SR কে সিলেক্ট করার পর ঐ SR এর নাম, এবং ঐ SR এর লাইভ লোকেশন Google Maps এ দেখতে পারবেন।"
- **Rules:** R-022, R-058. **Hardware/dependency:** Google Maps SDK + internet (INFERRED). **Data read:** team SR list, SR latest lat/long. **Online** (INFERRED).
- **Unclear items for this screen:** U-30, U-31 (section F).

## S-25 | Team Performance (summary) | "টিম পারফর্মেন্স" | pp 34, 36 (left)
- **Entry:** dashboard tile. **Exits:** "বিস্তারিত →" -> S-26; tap a category row -> S-26 filtered.
- **Controls:** segmented toggle (2 segments): "Monthly Target" (selected, green; default) | "Till Date Target" (white). Card "Zone: Agrabad" with four progress rows; cards "রুট: Agrabad", "রুট: Motiharpol", ... (list scrolls; one card per route). Each row: category label "সিগারেট" (Cigarette) / "বিড়ি" (Bidi) / "লাইটার" (Lighter) / "ম্যাচ" (Match), "achieved/target" and a percent in the bar's colour, thin progress bar (red low / yellow-orange mid / green high; thresholds not stated). Each card has a right-aligned link "বিস্তারিত →" (Details).
- **Sample values (Monthly):** Zone Agrabad: সিগারেট ২,০৮২,৮২০/২,৯৭২,৯০০ ৭০% (yellow); বিড়ি ১৩,৭৫০/১০২,৮০০ ১৩% (red); লাইটার ৬,৩১২/১১,২০০ ৫৬% (yellow); ম্যাচ ৫,২৭৬/৫,৪৪৭ ৯৭% (green). Route Agrabad: ১৪২,৬০০/১২৮,৯০০ ১০০%; ৬,৫০০ (or ৭,৩০০)/৮,২০০ ৮৯%; ৯৯/৭০০ ১৪%; ৪৩১/৩২৫ ১০০%. Route Motiharpol: ১৫১,৬৯০/১২৯,১৮০ ১০০%; ১,৫০০/৬,৬০০ ২৩%; ৮৪৪/৭০০ ১০০%; ম্যাচ cut off.
- **Callouts:** "টিম পারফর্মেন্স দেখতে টিম পারফর্মেন্স অপশনে ক্লিক করুন।"; "এরপর এখানে Zone এবং রুট এর তালিকা দেখতে পারবেন। এখান থেকে Monthly Target এবং Till Date Target দেখতে পারবেন। জোন এবং রুট এর বিস্তারিত তথ্য দেখতে বিস্তারিত বাটনে ক্লিক করুন।"; p36 "এখান থেকে যেকোন রুট অথবা জোন থেকে সিগারেট, বিড়ি, লাইটার, এবং ম্যাচ এর তথ্য আলাদা করে দেখতে পারবেন। যেমন শুধু বিড়ি এর তথ্য দেখতে বিড়ি এর উপর ক্লিক করুন।"
- **Rules:** R-059, R-060, R-063. **Data read:** targets (monthly, till-date) and achievements per zone/route/category. **Units** not labelled. **Offline:** not stated (server aggregates - INFERRED).
- **Unclear items for this screen:** U-32 (section F).

## S-26 | Team Performance details | title = zone name (e.g. "Agrabad") | pp 35, 36 (right)
- **Entry:** "বিস্তারিত →" or a category row on S-25. **Exit:** back.
- **Controls:** same toggle "Monthly Target" | "Till Date Target"; zone card (all 4 categories, or only the tapped category, e.g. only বিড়ি); table (light-purple header) "Item" | "টার্গেট" | "অর্জন" | "বাকি" | "%".
- **Monthly rows (p35):** Avon ১৬০,০০০/৩২৩,৯৮০/০/২০২.৪৯%; ARIS Apple ১৬,৮০০/৯,৫২০/৭,২৮০/৫৬.৬৭%; Marise Special Blend ২,২৮০,০০০/১,২৩০,৯৩০/১,০৪৯,০৭০/৫৩.৯৯%; Black Diamond Advanced ৪৪৮,৩০০/৪৭৯,৪৯০/০/১০৬.৯৬%; Supreme Fresh Max ৩,৮০০/৫৫০/৩,২৫০/১৪.৪৭%; Existing Abul Bidi/ 42 No. Abul Bidi ৬,৮০০/৭,২৫০/০/১০৬.২৮% (7,250/6,800 = 106.62 - U-33); "Abul Bidi …" cut off (০ achievement, 0.00%).
- **Till Date rows (p35 right):** Avon ১৩৩,১৮৫/৩২৩,৯৮০/০/২৪৩.২৬%; ARIS Apple ১৩,৯৮১/৯,৫২০/৪,৪৬১/৬৮.০৯%; Marise Special Blend ১,৮৯৮,৪৮৯/১,২৩০,৯৩০/৬৬৭,৫৫৯/৬৪.৮৪%; Black Diamond Advanced ৩৭৩,৩৬৯/৪৭৯,৪৯০/০/১২৮.৪২%; Supreme Fresh Max ৩,১৬৩/৫৫০/২,৬১৩/১৭.৩৯%; Existing Abul Bidi/ 42 No. Abul Bidi ৫,৩০০/৭,২৫০/০/১৩৬.০২%; zone card Till-Date (approx.): সিগারেট ২,০৮২,৮২০/২,৪৭২,৪৭৭ ৮৪%; বিড়ি ১৩,৭৫০/৮২,২৬৩ ১৬%; লাইটার ৬,৩১২/৯,৩২৮ ৬৮%; ম্যাচ ৫,২৭৬/৪,৫২৯ ১০০%.
- **Bidi-only view (p36 right, Till Date):** Existing Abul Bidi/ 42 No. Abul Bidi ৫,৩০০/৭,২৫০/০/১৩৬.০২%; Abul Bidi Gold ৪৮,০১৮/০/৪৮,০১৮/০.০০%; Special Abul Bidi 25 ৩১,৯১৯/৬,৫০০/২৫,৪১৯/২০.৩৬%.
- **Callouts:** "বিস্তারিত অপশনে ক্লিক করার পর উক্ত জোনের টার্গেট, অর্জন দেখতে পারবেন। টার্গেট, অর্জন এর Til Date Target ডাটা দেখতে Till Date Target বাটনে ক্লিক করুন।"; "এরপর এখান থেকে টার্গেট ও অ্যাচিভমেন্ট এর Till Date Target ডাটা দেখতে পারবেন।"; p36 "এরপর এখান থেকে শুধু বিড়ি এর টার্গেট ও অ্যাচিভমেন্ট এর বিস্তারিত তথ্য দেখতে পারবেন।"
- **Rules:** R-059, R-060, R-061, R-062, R-063. **Data read:** per-item targets/achievements. Table scrolls vertically.
- **Unclear items for this screen:** U-32, U-33 (section F).

## S-27 | Task Delegation (list) | "টাস্ক ডেলিগেশন" | pp 37, 38 (right)
- **Entry:** dashboard tile. **Exits:** orange "+" FAB -> S-28; back arrow -> dashboard.
- **Controls:** tab/segment bar with one tab "Assigned Tasks" (blue, selected; empty grey track beside it suggests room for more tabs); list of task cards: line 1 outlet name (e.g. "Afifa Stor", "Sifat St") + right-aligned orange status "চলমান" (Ongoing); line 2 task description (e.g. "Test task delegated to my SR", "Improve the sales quality"); line 3 flag icon + "Completion on: 2025-11-03" (English label, YYYY-MM-DD). New task is added at top (INFERRED).
- **Callouts:** "আপনার টিম মেম্বারদের টাস্ক Assign করার জন্য টাস্ক ডেলিগেশন বাটনে ক্লিক করুন।"; "Assigned Tasks অপশন থেকে আপনার দ্বারা Assign কৃত টাস্কগুলো দেখতে পারবেন। নতুন টাস্ক Assign করার জন্য প্লাস বাটনে ক্লিক করুন।"
- **Success toast after save (p38 right):** full-width lime-green bar "Save Successfully!" (English).
- **Rules:** R-064, R-066, R-067. **Data read:** tasks created by this AMO. Only status seen: "চলমান".
- **Unclear items for this screen:** U-34 (section F).

## S-28 | Assign Task (form) | "অ্যাসাইন্ড টাস্ক" | p38 (left)
- **Entry:** "+" on S-27. **Exit:** "টাস্ক বরাদ্দ করুন" -> S-27 with toast.
- **Controls (top to bottom):**
  | # | Control | Type | Value shown | Notes |
  |---|---|---|---|---|
  | 1 | Route/section | dropdown | "AgrabadDaily" | required implied |
  | 2 | Outlet | dropdown | "Sifat St (Diamond)" | label "Name (suffix)"; suffix likely outlet class (U-35) |
  | 3 | Alphabet strip | chips | All, A B D E G H I J K L / M N O P R S | dynamic (C, F, Q missing) |
  | 4 | Outlet info card (read-only, shop icon) | 4 rows | "আউটলেটের নাম:" Sifat St; "মালিকের নাম:" Nurul Kobir; "এসআর নাম:" SR-Kakoli - 1; "এসআর মোবাইল নম্বর:" (blank) | assignee = the outlet's SR; no SR picker |
  | 5 | Task type ("টাস্ক টাইপ") | dropdown | "General Task" | other options not shown |
  | 6 | Completion date ("টাস্ক কমপ্লিট করার ডেট") | date picker (calendar icon) | "November 3, 2025" | shown "Month D, YYYY" here, "YYYY-MM-DD" in list |
  | 7 | Description | multi-line text area, no visible label | "Improve the sales quality" | no length limit shown |
  | 8 | "টাস্ক বরাদ্দ করুন" (Assign task) | full-width green button | - | submits |
- **Callout:** "প্লাস বাটনে ক্লিক করার পর রুট/সেকশন এবং নির্দিষ্ট আউটলেট সিলেক্ট করুন। টাস্ক টাইপ এবং টাস্ক কমপ্লিট করার ডেট সিলেক্ট করুন এবং টাস্ক বরাদ্দ করুন বাটনে ক্লিক করুন।"
- **Rules:** R-065. **Data written:** task (route, outlet, derived SR, type, completion date, description, status "চলমান"). **Validation:** none stated (U-36).
- **Unclear items for this screen:** U-34, U-35, U-36 (section F).

## S-29 | Live Data (Live Dashboard) | "লাইভ ডেটা" (title) / "লাইভ ড্যাশবোর্ড দেখার প্রক্রিয়া" (slide) | p39
- **Entry:** dashboard tile "লাইভ ড্যাশবোর্ড". **Exit:** back.
- **Controls:** two dropdowns "Savar BazarAMO" and "Savar Bazar(Sat, Mon, Wed)" (route/section selectors; levels unexplained) + funnel-like "ফিল্টার" (filter) icon button: select route then press filter to load.
- **Tiles:**
  - A (purple header) "বিক্রয়": "38" "মোট বিক্রয়" (Total sales count); "161.50" "মোট টাকা" (Total taka).
  - B "লাইভ স্ট্রাইক রেট": "81" "টার্গেট আউটলেট"; "1" "Successful Calls" (English); donut centre "1.23%" + "স্ট্রাইক রেট" (1/81 = 1.2345%, formula implied).
  - C "জিও ফেন্সিং স্ট্যাটাস": "81" "টার্গেট আউটলেট"; "0" "জিও ভ্যালিডেশন"; "1" "ছবি ভ্যালিডেশন"; "1" "মোট আউটলেট ভ্রমণ"; donut "0.00%" (ratio unstated).
  - D "লগইন & বিক্রয় জমা স্টেটাস": "লগইন স্টেটাস" bar "100%" with "1" "টার্গেট রুট", "1" "মোট লগইন"; "বিক্রয় জমা স্টেটাস" bar "0%" with "1" "লগইন করেছে", "0" "মোট বিক্রয় জমা".
- **Callouts:** "রুট/সেকশন অনুযায়ী লাইভ ডাটা দেখার জন্য লাইভ ড্যাশবোর্ড বাটনে ক্লিক করুন"; "রুট/সেকশন সিলেক্ট করে ফিল্টার বাটনে ক্লিক করুন। রুটের বিক্রয়, লাইভ স্ট্রাইক রেট, জিও ফেন্সিং স্ট্যাটাস এবং লগইন, বিক্রয় জমা এর লাইভ ডাটা এখান থেকে দেখতে পারবেন।"
- **Rules:** R-068, R-069. **Data read:** sales count/amount, calls, target outlets, geo validation and photo validation per visit, outlets visited, login events, sales-deposit records. **Online** (INFERRED from "live"). The data implies SR-side geo validation + photo validation are captured and surfaced to the AMO.
- **Unclear items for this screen:** U-31, U-37 (section F).

## S-30 | SR List | "এসআর লিস্ট" | pp 40, 41 (left)
- **Entry:** dashboard tile "এস আর স্টক" (p40 callout: "এস আর থেকে লিফটেড স্টক এর পরিমাণ দেখতে এস আর স্টক অপশনে ক্লিক করুন।"). **Exits:** row tap (">") -> S-31.
- **List rows:** avatar icon, SR code bold ("SR-12237"), route/section name ("SenbagDaily"), phone icon + masked "***********" (11 asterisks), right label "মোট আউটলেট" (Total outlets) with purple number, ">" chevron. Sample: SR-12237 SenbagDaily 78; SR-12248 FokirhatDaily 78; SR-12249 ChandpurDaily 70; SR-12250 SeloniaDaily 76; SR-12251 ArjuntolaDaily 72; SR-12253 MaguaDaily 51; SR-12254 College RoadDaily 49; SR-12255 Abdullah PurDaily 53; SR-12256 (cut off). No search.
- **Callouts:** "এরপর এখান থেকে এস আর লিস্ট দেখতে পারবেন।"; p41 "লিফটেড স্টক দেখার জন্য এবার একটি এস আর নির্বাচন করুন।"
- **Rules:** R-023, R-070. **Data read:** SR (code, route name, masked mobile, outlet count) for the AMO's zone.
- **Unclear items for this screen:** U-38, U-59 (section F).

## S-31 | Lifted Stock | "লিফটেড স্টক" | p41 (right)
- **Entry:** SR row on S-30. **Exit:** back.
- **Layout:** summary card (avatar, "SR-12237", "SenbagDaily", masked phone, "মোট আউটলেট" 78); table (purple header) "এসকেইউ" (SKU) | "ইস্যু" (Issue); rows with pack thumbnail + SKU name + right-aligned Bangla number: MaxR-10S ১০,০০০; Avon-20S ১১,০০০; ARIS-O-20s ১১,০০০ (p43 reads "ARIS-A-20s"); SAB-12s ৯,০০০; EAB ১৩,৭৫০; Aster ১,০০০; FB ১,১০০; SL ১,২০০; footer "মোট" ৫৮,০৫০ (sum of all SKUs irrespective of unit).
- **Callout:** "এরপর এখান থেকে এস কে ইউ অনুযায়ী লিফটেড স্টক দেখতে পারবেন।"
- **Rules:** R-071. Read-only; no date selector; units not shown (U-38).
- **Unclear items for this screen:** U-38 (section F).

## S-32 | Outlet hub | "আউটলেট" | pp 47, 51, 53, 55, 58, 60, 63, 64 (repeated)
- **Entry:** dashboard tile "আউটলেট" (callout p47: "এস আর থেকে প্রেরিত আউটলেট সংক্রান্ত তথ্য যাচাই এবং নতুন আউটলেট সংযোজন, স্থায়ী বন্ধ, তথ্য পরিবর্তন এবং বেস আপডেট এর জন্য ড্যাশবোর্ড থেকে আউটলেট বাটনে ক্লিক করুন।"; p55 "নতুন আউটলেট সংযোজন, স্থায়ী বন্ধ, এবং তথ্য পরিবর্তন এর জন্য ড্যাশবোর্ড থেকে আউটলেট বাটনে ক্লিক করুন।").
- **Section 1 caption** "এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন" (Verify the requests coming from SR; "আশা" probably typo of "আসা"). Three tiles each with a red count badge (value 2/2/2 on p47; digits illegible on later pages): "নতুন আউটলেট যাচাইকরণ" (New outlet verification; store+plus icon) -> S-33; "আউটলেট বন্ধের যাচাইকরণ" (Outlet closure verification; CLOSE sign) -> S-36; "আউটলেট সংশোধনের" (label truncated, probably "...যাচাইকরণ"; shop-with-magnifier/cursor icon) -> S-38.
- **Section 2 caption** "দোকান সংক্রান্ত অপারেশন্স" (also printed "অপারেশন" p51) (Shop-related operations). Four tiles, no badge: "নতুন দোকান" (New shop) -> S-40; "স্থায়ী বন্ধ" (Permanently closed) -> S-41; "তথ্য পরিবর্তন করুন" (Change information) -> S-42; "আপডেট বেস" (Update base; shop+map-pin icon) -> S-43.
- **Callouts:** p47 right "আউটলেট বাটনে ক্লিক করার পর আপনারা নতুন দোকান, স্থায়ী বন্ধ, তথ্য পরিবর্তন এর অনুরোধ গুলো যাচাই করার জন্য এই তিনটি অপশন দেখতে পারবেন। নতুন দোকানের তথ্য যাচাই করার জন্য নতুন আউটলেট যাচাইকরণ অপশনে ক্লিক করুন।"; p55 right "আউটলেট বাটনে ক্লিক করার পর আপানারা নতুন দোকান, স্থায়ী বন্ধ, এবং তথ্য পরিবর্তন অপশন তিনটি দেখতে পারবেন। নতুন দোকানের তথ্য ইনপুট করার জন্য নতুন দোকান অপশনে ক্লিক করুন।" (says three options; four tiles are shown).
- **Rules:** R-079. **Data read:** counts of pending SR requests by type. Home-grid badge (০/৮) vs hub badges (২,২,২) are inconsistent (U-07).
- **Unclear items for this screen:** U-07, U-42 (section F).

## S-33 | New outlet verification - request list | "নতুন দোকান" | p48 (left)
- **Entry:** "নতুন আউটলেট যাচাইকরণ" tile (S-32). **Exit:** tap a card -> S-34.
- **Cards (label : value):** "রুট", "ক্লাস্টার", "খুচরা বিক্রেতার নাম", "মালিকের নাম", "যোগাযোগ" (contact, unmasked). Sample: Savar BazarDaily | Madrasa Road | Rakib Store | Rakib | [phone]; Savar BazarDaily | Madrasa Road | Ishita Store | Ishita | [phone]; Savar BazarDaily | North Nama Bazar | Shaiful Store | Shaiful | [phone]; Savar BazarDaily | Kamarporti | Moin Store | Moinuddin | [phone].
- **Callout:** "নতুন দোকান অপশন ক্লিক করার পরে আপনারা এই পেজটি দেখতে পারবেন। এখান থেকে নতুন দোকান নির্বাচন করুন।"
- **Data read:** SR-submitted new-outlet requests. 4 cards vs badge 2 (U-07).
- **Unclear items for this screen:** U-07, U-59 (section F).

## S-34 | New outlet verification form (scrolling) | "নতুন দোকান" | pp 48 (right), 49 (left), 50
- **Entry:** card on S-33. **Exits:** "GEO এবং ছবি ধারণ করুন" -> S-35; "সংরক্ষণ" -> success dialog; "বাতিল" (action not described); back.
- **Fields in order (top = p48, bottom = p49):**
  | # | Label (verbatim) | Type | Value (sample) | Notes |
  |---|---|---|---|---|
  | 1 | "একটি রুট সিলেক্ট করুন" (Select a route) | dropdown | "Savar BazarDaily" | pre-filled from SR request |
  | 2 | "ক্লাস্টার নির্বাচন করুন" (Select cluster) | dropdown | "Madrasa Road" | pre-filled |
  | 3 | "দোকানের নাম" (Shop name) | text | "Rakib Store" / "Ashraf Store" | pre-filled, editable |
  | 4 | "দোকানের মালিকের নাম" (Shop owner's name) | text | "Rakib" / "Ashraf" | |
  | 5 | "মোবাইল নম্বর" (Mobile number) | text/number | "[phone]" (11 digits, leading 0) | no length/format validation shown |
  | 6 | "Sub-Channel" (English) | dropdown | placeholder "Select Sub-Channel"; "GT" on p50 | AMO fills; options not shown (GT, Diamond, Gold seen elsewhere) |
  | 7 | "Geo Classification" (English) | dropdown | placeholder "Select Geo Classification"; "Urban" on p50 | AMO fills |
  | 8 | "GEO এবং ছবি ধারণ করুন" (Capture GEO and photo) | blue-indigo gradient full-width button (red-boxed p49) | - | opens camera (S-35) |
  | 9 | "বাতিল" (Cancel) | red button; floppy icon on p49/50 | - | action not described |
  | 10 | "সংরক্ষণ" (Save) | green button, floppy icon (red-boxed p50) | - | submits |
- **Screenshot shows no indicator** that photo/GEO was captured (no thumbnail/tick/coords).
- **Callouts:** p48 "এরপর এখান থেকে ইউজার এস আর থেকে ইনপুট করা নতুন দোকানের তথ্য দেখতে পারবেন। AMO Sub-Channel এবং Geo Classification এর ডাটা ইনপুট করতে পারবেন।"; p49 left "তথ্য ইনপুট শেষে GEO এবং ছবি ধারন করুন অপশনে ক্লিক করুন।"; p50 left "ছবি কেপচার শেষ করে, সংরক্ষণ বাটনে ক্লিক করুন।"; p50 right "সংরক্ষণ বাটনে ক্লিক করার পরে এলার্ট দেখতে পারবেন।"
- **Success dialog (p50 right):** green tick, "ডেটা সফলভাবে সংরক্ষিত হয়েছে।", button "ওকে"; dimmed background = a card with "রুট : Court Bari(Sun, Tue, Thu)", "ক্লাস্টার : Madrasa Road", "খুচরা বিক্রেতার নাম : Rajib Store", "মালিকের নাম : Rajib", "যোগাযোগ : [phone]". No Yes/No confirm for this save.
- **Rules:** R-080, R-081, R-082. **Hardware:** camera, location. **Data written:** outlet (name, owner, mobile, route, cluster, sub-channel, geo classification, GPS, photo); request state change. No reject action exists on screen (U-39).
- **Unclear items for this screen:** U-39, U-59, U-60, U-61 (section F).

## S-35 | Outlet GEO + photo capture (device camera) | pp 49 (right), 56 (right), 61 (left)
- **Entry:** "GEO এবং ছবি ধারণ করুন" from S-34 / S-40 / S-42. **Exit:** back to the form; on S-42 a dialog appears.
- **UI:** full-screen device camera viewfinder (stock-camera look; shopfront with signage "রাসেল জেনারেল স্টোর" in demo): camera-flip icon (left), white round shutter (centre, red-boxed), "✕" close (right). Exactly ONE photo ("একটি ছবি").
- **Callouts:** "GEO এবং ছবি ধারন করুন অপশনে ক্লিক করার পরে ডিভাইসের ক্যামেরা অপশন চালু হবে। এরপর ঐ আউটলেটের একটি ছবি তুলুন।" (pp 49, 56); p61 "“GEO এবং ছবি ধারণ করুন” অপশনটি নির্বাচন করার পর আপনার ডিভাইস এর Camera ওপেন হয়ে যাবে। এরপর উক্ত দোকানের একটি ছবি উঠাতে হবে।"
- **Dialog after capture (p61 right only):** green check, "ছবি ধারণ করা সম্পন্ন হয়েছে" (Photo capture completed), button "ওকে". Callout: "ছবি উঠানোর পর একটি “Successful” alert দেখতে পারবেন। এরপর “OK” অপশনে ক্লিক করুন।" (This dialog is shown only for the change-info flow in the manual; S-34/S-40 screenshots do not show it.)
- **Rules:** R-081, R-088. **Data written:** photo + GEO (GPS) of the outlet. No retake flow, preview, compression or mock-GPS handling is described.
- **Unclear items for this screen:** U-28, U-60 (section F).

## S-36 | Outlet closure verification - request list | "স্থায়ী বন্ধ" | p51 (right)
- **Entry:** "আউটলেট বন্ধের যাচাইকরণ" tile (S-32). **Exit:** tap card -> S-37.
- **Cards:** same 5-row label:value layout as S-33. Sample: Savar BazarDaily | Madrasa Road | Siyam | Robiul | [phone]; Savar BazarDaily | Madrasa Road | Tanjid | Soriful | [phone] (contacts shown WITHOUT leading 0).
- **Callouts:** "এস আর থেকে প্রেরিত স্থায়ী বন্ধ দোকানের তথ্য যাচাই করার জন্য আউটলেট বন্ধের যাচাইকরণ অপশনে ক্লিক করুন।"; "আউটলেট বন্ধের যাচাইকরণ অপশনে ক্লিক করার পরে এই পেজ দেখতে পারবেন। এখান থেকে আউটলেট নির্বাচন করুন।"

## S-37 | Outlet closure verification - detail | "স্থায়ী বন্ধ" | p52
- **Entry:** card on S-36. **Exit:** "সংরক্ষণ" -> (confirm "হ্যাঁ") -> success dialog.
- **Fields:** "একটি রুট সিলেক্ট করুন" dropdown "Savar BazarDaily" (pre-filled); "ক্লাস্টার নির্বাচন করুন" dropdown "Madrasa Road" (pre-filled); read-only grey rows "দোকানের নাম:" Siyam, "দোকানের মালিকের নাম:" Robiul, "মোবাইল নম্বর:" [phone]; buttons red "বাতিল" (x-in-circle icon) and green "সংরক্ষণ" (red-boxed).
- **Callout:** "এরপর উক্ত আউটলেট এর তথ্য দেখতে পারবেন। সংরক্ষণ বাটনে ক্লিক করার পরে, একটি এলার্ট দেখতে পারবেন। সেখান থেকে হ্যাঁ অপশনে ক্লিক করুন।" (confirm alert is described but NOT shown on this page).
- **Success dialog:** green tick, ENGLISH "Data Updated Successfully", button "ওকে". Callout: "এরপর ডাটা সংরক্ষন এর একটি সফল এলার্ট দেখতে পারবেন।"
- **Rules:** R-083. No reject path, no reason field (U-40).
- **Unclear items for this screen:** U-40, U-61 (section F).

## S-38 | Outlet info-change verification - request list | "তথ্য পরিবর্তন করুন" | p53 (right)
- **Entry:** "আউটলেট সংশোধনের" tile. **Exit:** card -> S-39.
- **Cards:** 5-row label:value layout. Sample: Savar BazarDaily | Madrasa Road | Sohel Rana | Sohel | [phone]; Savar BazarDaily | Madrasa Road | Tanjid | Soriful | [phone] (contacts WITH leading 0 here).
- **Callouts:** "এস আর থেকে প্রেরিত তথ্য পরিবর্তিত দোকানের তথ্য যাচাই করার জন্য আউটলেট সংশোধনের অপশনে ক্লিক করুন।"; "আউটলেট সংশোধনের অপশনে ক্লিক করার পরে এই পেজ দেখতে পারবেন। এখান থেকে আউটলেট নির্বাচন করুন।"

## S-39 | Outlet info-change verification - edit form | "তথ্য পরিবর্তন করুন" | p54
- **Entry:** card on S-38. **Exit:** "সংরক্ষণ" -> (confirm "হ্যাঁ") -> success dialog.
- **Fields:** "একটি রুট সিলেক্ট করুন" dropdown "Savar BazarDaily"; "ক্লাস্টার নির্বাচন করুন" dropdown "Madrasa Road" (lighter grey text - maybe disabled); "দোকানের নাম" text "Rinku Store"; "দোকানের মালিকের নাম" text "Rinku"; "মোবাইল নম্বর" text "[phone]"; "Sub-Channel" dropdown "GT"; "Geo Classification" dropdown "Urban"; red "বাতিল" (floppy icon), green "সংরক্ষণ" (red-boxed). NO "GEO এবং ছবি ধারণ করুন" button.
- **Callouts:** "দোকানের তথ্য ভুল মনে হইলে আপনারা তথ্যগুলো পরিবর্তন করতে পারবেন। আউটলেটের তথ্য সঠিক মনে হইলে “সংরক্ষন করুন” অপশনে ক্লিক করুন।"; "সংরক্ষণ বাটনে ক্লিক করার পরে, একটি এলার্ট দেখতে পারবেন। সেখান থেকে হ্যাঁ অপশনে ক্লিক করুন। এরপর ডাটা সংরক্ষন এর একটি সফল এলার্ট দেখতে পারবেন।"
- **Success dialog:** green tick, ENGLISH "Data Updated Successfully", "ওকে" (background card shows a different record: Tasmin / Jubaer / [phone]).
- **Rules:** R-084.
- **Unclear items for this screen:** U-39, U-61 (section F).

## S-40 | New shop (AMO-initiated, cluster variant) | "নতুন দোকান" | pp 55-57
- **Entry:** tile "নতুন দোকান" on S-32. **Exits:** "GEO এবং ছবি ধারণ করুন" -> S-35; "সংরক্ষণ" -> success.
- **Fields:** "ক্লাস্টার নির্বাচন করুন" dropdown "Madrasa Road"; "দোকানের নাম" "Aziz Store"; "দোকানের মালিকের নাম" "Aziz"; "মোবাইল নম্বর" "[phone]"; purple full-width "GEO এবং ছবি ধারণ করুন" (red-boxed p56); green full-width "সংরক্ষণ" with floppy icon (red-boxed p57). NO Cancel button, NO Route, NO Sub-Channel/Geo Classification on this variant.
- **Callouts:** "নতুন দোকান অপশন ক্লিক করার পরে আপানারা এই পেজটি দেখতে পারবেন। এখান থেকে আপনারা নতুন দোকানের তথ্য ইনপুট করতে পারবেন। তথ্য ইনপুট শেষে GEO এবং ছবি ধারন করুন অপশনে ক্লিক করুন।"; "ছবি কেপচার শেষ করে, সংরক্ষণ বাটনে ক্লিক করুন।"; "সংরক্ষণ বাটনে ক্লিক করার পরে ডাটা সংরক্ষন এর একটি এলার্ট দেখতে পারবেন।"
- **Success dialog:** "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" / "ওকে".
- **Rules:** R-081, R-082, R-085. **Data written:** new outlet (cluster, name, owner, mobile, GPS, photo).
- **Unclear items for this screen:** U-42, U-60 (section F).

## S-41 | Permanently closed (AMO-initiated) | "স্থায়ী বন্ধ" | pp 58, 59, 63 (right)
- **Entry:** tile "স্থায়ী বন্ধ" on S-32 (callout: "স্থায়ী বন্ধ দোকানের তথ্য ইনপুট করার জন্য স্থায়ী বন্ধ অপশনে ক্লিক করুন।"). **Exit:** "সংরক্ষণ" -> confirm -> success.
- **Fields:** "ক্লাস্টার নির্বাচন করুন" dropdown "Madrasa Road"; "রিটেইলার নির্বাচন করুন" dropdown "Kamal Store (1618408-[phone]-Madrasa Road)"; read-only rows "দোকানের নাম:" (full label string, wraps 3 lines), "দোকানের মালিকের নাম:" Kamal, "মোবাইল নম্বর:" [phone]; single green full-width "সংরক্ষণ". No route dropdown, no reason, no photo, no Cancel.
- **Callout:** "স্থায়ী বন্ধ অপশনে ক্লিক করার পরে এই পেজ দেখতে পারবেন। এখান থেকে ঐ আউটলেটের তথ্য ইনপুট করুন। ইনপুট শেষে সংরক্ষণ বাটনে ক্লিক করুন।"
- **Confirm dialog (p59 left):** blue "i" icon, heading "আপনি কি নিশ্চিত?", body "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" (~90% legibility), buttons green "হ্যাঁ" (red-boxed), red "না". Callout: "সংরক্ষণ বাটনে ক্লিক করার পরে, এই এলার্ট দেখতে পারবেন। এখান থেকে হ্যাঁ অপশনে ক্লিক করুন।"
- **Success (p59 right):** "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" / "ওকে". Callout: "সংরক্ষণ বাটনে ক্লিক করার পরে ডাটা সংরক্ষন এর এলার্ট দেখতে পারবেন।"
- **Rules:** R-086. (p63 notes that no confirmation or success was shown on that page, but p59 shows both for the same form.)
- **Unclear items for this screen:** U-40 (section F).

## S-42 | Change information (AMO-initiated) | "তথ্য পরিবর্তন করুন" | pp 60, 61, 62
- **Entry:** tile "তথ্য পরিবর্তন করুন" on S-32 (callout: "দোকানের তথ্য পরিবর্তন করার জন্য তথ্য পরিবর্তন করুণ অপশনে ক্লিক করুন।"). **Exits:** "GEO এবং ছবি ধারণ করুন" -> S-35; "সংরক্ষণ" -> confirm -> success.
- **Fields:** "ক্লাস্টার নির্বাচন করুন" dropdown "Madrasa Road" (label cut off on p61); "রিটেইলার নির্বাচন করুন" dropdown "Sujon Store (1618414--Madrasa Road)"; "দোকানের নাম" text "Sujon Store"; "দোকানের মালিকের নাম" text "Sujon"; "মোবাইল নম্বর" text "[phone]"; purple "GEO এবং ছবি ধারণ করুন"; green "সংরক্ষণ" (faded on p61/62; may be disabled until photo - not stated). No Sub-Channel/Geo Classification visible (lower part may be cut off by scroll).
- **Callouts:** p60 "দোকানের তথ্য ভুল মনে হইলে আপনারা তথ্যগুলো পরিবর্তন করতে পারবেন। তথ্য পরিবর্তন শেষে “GEO এবং ছবি ধারন করুন” অপশনটি নির্বাচন করতে হবে।"; p61 (photo dialog, see S-35); p62 left "আউটলেটের তথ্য সঠিক মনে হইলে “সংরক্ষন করুন” অপশনে ক্লিক করুন। এরপর “হ্যাঁ” অপশন নির্বাচন করুন।"; p62 right "এরপর আপনার তথ্য সংরক্ষন এর একটি Successful Alert দেখতে পারবেন।"
- **Confirm dialog (p62 left):** blue "i", heading "আপনি কি নিশ্চিত?", body "এই পরিবর্তন সংরক্ষণ করা হবে", buttons green "হ্যাঁ" (red-boxed), red "না". **Success:** "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" / "ওকে".
- **Rules:** R-087, R-088. **Data written:** outlet name/owner/mobile, GEO, photo.
- **Unclear items for this screen:** U-60, U-61 (section F).

## S-43 | Base recalibration (Update Base) | "বেস রিক্যালিব্রেশন" (title) / "বেস লোকেশান আপডেট প্রক্রিয়া" (slide) | pp 64, 65 (left)
- **Entry:** tile "আপডেট বেস" on S-32 (callout: "আউটলেটের বেস লোকেশান আপডেট করার জন্য আপডেট বেস বাটনে ক্লিক করুন।"). **Exit:** "আপডেট বেস" -> confirm -> camera (S-35 style) -> map S-44.
- **Controls:** dropdown 1 (no visible label) "Savar BazarDaily" (route/section); dropdown 2 (no label) "Babu Store (1618411-[phone]-Madrasa Road)"; alphabet strip (All, A-J / K M N P R S T V Z; dynamic); green button "আপডেট বেস".
- **Callout:** "আপডেট বেস বাটনে ক্লিক করার পর, রুট/সেকশন এবং আউটলেট সেলেক্ট করে আপডেট বেস বাটনে ক্লিক করুন।"
- **Confirm dialog (p65 left):** yellow warning triangle, "আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে?" buttons green "আপডেট" (red-boxed), red "বাতিল". Callout: "আপডেট বেস বাটনে ক্লিক করার পর এই alert দেখতে পারবেন আপডেট অপশন আ ক্লিক করুন। এরপর ক্যামেরা অপশন চালু হলে ঐ আউটলেটের একটি ছবি তুলুন।"
- **Rules:** R-089.
- **Unclear items for this screen:** U-28 (section F).

## S-44 | Confirm location (map) | "অবস্থান নিশ্চিত করুন" | p65 (right)
- **Entry:** after the outlet photo in S-43 flow. **Exit:** "নিশ্চিত করুন" completes the update (no success message shown).
- **UI:** Google Maps view centred on a person-avatar marker (current location) with blue accuracy circle; compass top-left, my-location crosshair top-right; green button "নিশ্চিত করুন" (Confirm). Demo map shows a Banani/Dhaka area although outlets are in Savar.
- **Callout:** "আউটলেটের ছবি তুলার পরে Google Maps অপশন থেকে ঐ আউটলেটের লোকেশনটি সিলেক্ট করে নিশ্চিত করুন বাটনে ক্লিক করে আউটলেটের বেস লোকেশান আপডেটের কাজ সম্পন্ন করুন।"
- **Rules:** R-089. **Hardware:** camera, location, Google Maps (internet INFERRED). **Data written:** outlet base lat/long, outlet photo. No max-distance rule, mock-GPS or accuracy rule stated.
- **Unclear items for this screen:** U-28 (section F).

---------------------------------------------------------------------
## S-45 | Stock (stock entry / issue + printer connect) | "স্টক" | pp 14-16 (also callout p9 "স্টক এন্ট্রি")
- **Entry:** dashboard tile "স্টক" (p9: "প্রতিদিনের মত স্টক নেওয়ার জন্য “স্টক” বাটনে ক্লিক করুন ।" = As every day, click Stock to take stock; p14: "...প্রিন্টার এর সংযোগ স্থাপন করার জন্য ড্যাশবোর্ড থেকে স্টক অপশনে ক্লিক করুন।"). **Exit:** back arrow "◁". 
- **Bluetooth permission dialog (OS, p15 left, shown the first time):** "Allow ARON AMO to find, connect to and determine the relative position of nearby devices?" buttons "Allow" (instructed), "Don't allow". Callout: "এরপর অ্যাপ্লিকেশানে Bluetooth Permission এর একটি Alert দেখতে পারবেন। ইউজারকে অবশ্যই এই Permission টি Allow অপশনে ক্লিক করে Allow করতে হবে। অন্যথাই প্রিন্টার Connect হবে না।"
- **Header:** purple, back arrow, title "স্টক"; top-right circular printer button (red box): printer icon with red slash = not connected (tap to connect; callout "এবার ইউজারকে প্রিন্টার আইকন এর উপর ক্লিক করতে হবে।"); after connecting it turns GREEN with a lime-green banner "প্রিন্টার কানেক্ট করা হয়েছে" (Printer connected) (p16 callouts: "প্রিন্টার এর কানেকশান সম্পন্ন হয়ে যাবার পর ইউজার প্রিন্টার কানেক্ট করা হয়েছে একটি Alert দেখতে পারবেন।" / "এরপর প্রিন্টার আইকনটি সবুজ বর্ণের হয়ে যাবে।").
- **Column headers:** "এসকেইউ" (SKU) | "ইস্যু" (Issue) | "স্টক" (Stock).
- **SKU card (one per SKU):** pack image with purple circular badge bottom-right showing a number (0; meaning unexplained); SKU name in purple ("MaxR-10S", "MaxR-20S", "MaxB-10S", "MaxB-20S", 5th card partially visible with an AVON-style pack, name cut off); "−" button, numeric input (underlined, default 0), "+" button (Issue; stepper plus direct entry; unit/limits not stated); far right read-only Stock value (0). List scrolls; no search.
- **Bottom fixed blue summary panel:** columns "ক্যাটাগরি" (Category) | "মোট ইস্যু" (Total Issue) | "স্টক" (Stock); rows "সিগারেট", "বিড়ি", "লাইটার", "ম্যাচ" (all 0).
- **Bottom buttons:** lime-green "সংরক্ষণ" (Save, floppy icon, enabled); grey "প্রিন্ট" (Print, printer icon; greyed while printer not connected - rule not stated, and it stays grey on p16 after connecting).
- **Rules:** R-014, R-015, R-016, R-114, R-115, R-116, R-117. **Hardware:** Bluetooth (nearby-devices permission), thermal printer. **Data:** SKU master (name, image, category), AMO stock per SKU, issue qty per SKU, category totals, printed slip (content not stated). **Offline:** not stated; printer link is local Bluetooth.
- **Not stated:** to whom stock is issued, whether Save posts to server, what Print prints (U-41).
- **Unclear items for this screen:** U-41 (section F).

## S-46 | Memo selector | "মেমো" | pp 42 (right), 44 (left)
- **Entry:** dashboard tile "মেমো" (callout p42: "প্রয়োজনে আপনার মেমো অপশন থেকে পুনরায় সেলস মেমো প্রিন্ট এবং সেলস এডিট করতে পারবেন।"). **Exit:** "মেমো" tile -> S-47.
- **Controls:** dropdown 1 route "Savar BazarDaily"; dropdown 2 retailer "Siyam (1618410-[phone]-Madrasa Road)" followed by green text ": ১১৬.৪২ ৳ বাকি" (116.42 Taka due) and a caret - the due label appears only for outlets with outstanding credit; alphabet strip (All A B C D E F G H I / J K M N P R S T V Z; dynamic); one tile (red-boxed) icon + "মেমো".
- **Callouts:** "রুট এবং আউটলেটে সিলেক্ট করে মেমো বাটনে ক্লিক করুন।" (p42); "বাকীতে বিক্রি আউটলেট গুলোর বাকী পরিশোধ করতে রুট এবং আউটলেটে সিলেক্ট করে মেমো বাটনে ক্লিক করুন।" (p44).
- **Rules:** R-072, R-073. **Data read:** routes, outlets with outstanding balance, memos per outlet.

## S-47 | Memo detail (view / reprint / edit / mark paid) | "মেমো" | pp 43, 44 (right)
- **Entry:** "মেমো" tile on S-46. **Exits:** "প্রিন্ট" (reprint), "এডিট" (S-49), "পরিশোধিত করুন" (S-48), back.
- **Top-right printer-status icon:** circular grey with RED slashed printer (not connected) or GREEN printer (connected).
- **Info box (ⓘ):** "আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন" (no trailing "।").
- **Outlet card (shop icon):** "রুট:" Savar BazarDaily + "খুচরা বিক্রেতাঃ" Siyam (1618410-[phone]-M… (truncated); in the second layout the label is "সেকশনঃ" AgrabadDaily and retailer "Ebraj St (Diamond)".
- **Items table (dark blue/purple header):** "এসকেইউ" | "পরিমাণ" | "দাম" (acts as line total). Layout A (credit memo, p43 left/p44): MaxR-10S ১০ ৮০.০০; ABS ২৫ ২০.০০; Aster ১ ১২.৫০; FB ১ ২.৩৩; SL ১ ১.৫৮; category subtotals "মোট সিগারেট" ১০ ৮০.০০, "মোট বিড়ি" ২৫ ২০.০০, "মোট লাইটার" ১ ১২.৫০, "মোট ম্যাচ" ২ ৩.৯২; green rows "মোট ডিসকাউন্ট" "- ০.০০" and "সর্বমোট" ১১৬.৪২. Layout B (cash memo, p43 right): MaxR-20S ২০ ১৬০.০০; Avon-20S ২০ ১৪০.০০; ARIS-A-20s ২০ ১৩০.০০; ABS ২৫ ২০.০০; Aster ১ ১২.৫০; SL ১২ ১৯.০০; grey footer "মোট" ৯৮ ৪৮১.৫০; "মোট ডিসকাউন্ট" ০.০০; "সর্বমোট" ৪৮১.৫০ - no category subtotals, no credit panel.
- **Credit panel (layout A only; dashed red box):** credit-card icon, red text "এই বিক্রয়টি বাকিতে করা হয়েছে।" (This sale was made on credit) | divider | green pill button "পরিশোধিত করুন" (Mark as paid).
- **Bottom buttons:** "প্রিন্ট" (Print; printer icon; filled dark blue/purple) and "এডিট" (Edit; pencil icon; grey, looks disabled on the credit memo).
- **Callouts:** p43 left "রুট এবং আউটলেটে সিলেক্ট করে মেমো বাটনে ক্লিক করার পর প্রিন্টার কানেক্ট করে প্রিন্ট বাটনে ক্লিক করে মেমো প্রিন্ট সম্পন্ন করুন।"; p43 right "বিক্রয় এডিট করার জন্য এডিট অপশনে ক্লিক করে বিক্রয় এডিট করে নিতে পারবেন।"; p44 right "এরপর 'পরিশোধিত করুন' অপশনে ক্লিক করুন।"
- **Rules:** R-016, R-074, R-075, R-076. **Hardware:** Bluetooth printer must be connected before Print. **Data read:** sale/memo (lines, subtotals, discount, grand total, credit flag, paid flag). The two layouts are unexplained (U-43).
- **Unclear items for this screen:** U-43 (section F).

## S-48 | Mark credit memo as paid (confirm + success dialogs) | "বাকি পরিশোধ প্রক্রিয়া" | pp 44-45
- **Entry:** "পরিশোধিত করুন" on S-47 (credit memos only). **Exit:** OK -> memo screen; the due label beside the outlet disappears from the S-46 outlet dropdown.
- **Confirm dialog (p45 left):** yellow warning triangle, "আপনি কি নিশ্চিত?" (Are you sure?), buttons green "হ্যাঁ", red "না". Callout: "এরপর 'হ্যাঁ' অপশনটি নির্বাচন করুন।"
- **Success dialog (p45 right):** green check, "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" (This memo has been successfully marked as paid.), button "ওকে". Callout: "এরপর একটি সফল মেসেজ দেখতে পারবেন। এবং বাকী স্ট্যাটাসটি আউটলেট এর পাশে থেকে মুছে যাবে।" (background still shows the credit panel, not yet refreshed).
- **Rules:** R-076, R-077. No amount/partial/payment-method entry on this flow (U-44). **Data written:** memo payment status credit -> paid; outlet outstanding balance cleared.
- **Unclear items for this screen:** U-44 (section F).

## S-49 | Edit sale (from Memo) | "এডিট" button | p43 (button only)
- **NOT DOCUMENTED beyond the button.** The edit screen, editable fields, time window/lock after sync, reason capture, re-print behaviour are never shown (U-45). Callout only: "বিক্রয় এডিট করার জন্য এডিট অপশনে ক্লিক করে বিক্রয় এডিট করে নিতে পারবেন।"

## S-50 | Sales Summary (whole-day summary) | "বিক্রয় সারসংক্ষেপ" | p46
- **Entry:** dashboard tile "সারসংক্ষেপ" (callout: "সারাদিনের বিক্রয়কাজ শেষে বিক্রয় এর সকল ডাটা দেখার জন্য সারসংক্ষেপ বাটনে ক্লিক করুন।"). **Exit:** back.
- **Header icons/boxes:** printer-status icon (red slashed) top-right; info box ⓘ "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে" (Your full day's sales details will be here).
- **Main table (dark header):** "মেমো" (memo count) | "পরিমাণ" (Quantity) | "মূল্য" (Value) | "ডিসকাউন্ট" (Discount) | "ডিসকাউন্ট মূল্য" (Discount value) | "ফেরত" (Return). For each SKU: purple SKU name heading then one data row. Sample: (first SKU scrolled off, presumably MaxR-10S) ১ | ১০ | ৮০.০০৳ | ০.০ | ৮০.০০৳ | ৮,৪৯০; Avon-20S ১ | ২০ | ১৪০.০০৳ | ০.০ | ১৪০.০০৳ | ৭,৯৮০; SAB-12s ১ | ১২ | ১০.০০৳ | ০.০ | ১০.০০৳ | ৮,৯৮৮; Aster ১ | ১ | ১২.৫০৳ | ০.০ | ১২.৫০৳ | ৯৯৯; FB ১ | ১ | ২৮.০০৳ | ০.০ | ২৮.০০৳ | ৩৪৯; SL ১ | ১ | ১৯.০০৳ | ০.০ | ১৯.০০৳ | ৯৯৯. Category rows (no SKU heading): "সিগারেট" ৩০ ২২০.০০৳ ০.০ ২২০.০০৳ ১২,৪৭০; "বিড়ি" ১২ ১০.০০৳ ০.০ ১০.০০৳ ৮,৯৮৮; "লাইটার" ১ ১২.৫০৳ ০.০ ১২.৫০৳ ৯৯৯; "ম্যাচ" ২ ৪৭.০০৳ ০.০ ৪৭.০০৳ ১,৩৪৮. Green totals: "ডিসকাউন্ট এবং অন্যান্য (-)" "-০.০০"; "সর্বমোট" ২৮৯.৫০.
- **Second block:** green tab "মোট বিক্রয়" (Total sales); table header "মূল্য"; rows "মোট" ২৮৯.৫০ (red); "ডিসকাউন্ট" "(-) ০.০০" (red); "সর্ব মোট" ২৮৯.৫০ (bold; note space vs "সর্বমোট").
- **Button:** full-width "প্রিন্ট" (Print, printer icon).
- **Callout:** "সারাদিনের আপনার বিক্রয়ের তথ্য এবং ডিসকাউন্ট মূল্যের তথ্য সারসংক্ষেপ আকারে এখান থেকে দেখতে পারবেন। প্রয়োজনে আপনারা এখান থেকে প্রিন্ট বাটনে ক্লিক করে সারসংক্ষেপ প্রিন্ট করে নিতে পারবেন।"
- **Rules:** R-016, R-078. **Data read:** day's sales aggregated per SKU and category (memo count, qty, value, discount, discount value, "ফেরত"). Meaning of "ফেরত" values unclear (U-46). No date picker/filter/export besides Print.
- **Unclear items for this screen:** U-46 (section F).

## S-51 | Sales Deposit (sync + submit) | "বিক্রয় জমা" | pp 66-68
- **Entry:** dashboard tile "বিক্রয় জমা" (callout p66: "সকল আউটলেটে বিক্রয় এর কাজ শেষে সকল ডাটা আপলোড করার জন্য ডিভাইসের ড্যাশবোর্ড থেকে “বিক্রয় জমা” অপশনে ক্লিক করুন।"). **Exit:** OK on success dialog; back.
- **Status line (top-right):** "ডিভাইস স্ট্যাটাস" + green dot + "অনলাইন" (Device status: Online; offline wording never shown).
- **Reconciliation table (purple header):** "বিষয়" (Subject) | "ডিভাইস" (Device) | "সার্ভার" (Server). Rows: "আউটলেট" (Outlet), "বিক্রয়" (Sales), "স্টক" (Stock), "কিউসি" (QC), "প্রমোশন" (Promotion), "ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা" (Distribution and OOS performance), "মূল্য সম্মতি" (Price compliance), "জয়েন্ট কল" (Joint call), "সার্ভে" (Survey). Samples: p66 (before sync) আউটলেট ১/১; বিক্রয় ১১৮/১১৮; স্টক ৪৫,১৫০/৪৫,১৫০; others ০/০. p67: বিক্রয় ২৫/২৫; স্টক ১১,৭০০/১১,৭০০; "মূল্য সম্মতি" row ABSENT. p68: same as p66.
- **Warning box (pink/red):** "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই সেকশন থেকে বিক্রয় জমা সাবমিট করুন।" (Before finishing today's work, you must sync all operation data and submit the sales deposit from this section.)
- **Buttons:** green "ডাটা সিঙ্ক করুন" (Sync data; sync-arrows icon; always enabled in screenshots); "বিক্রয় জমা" (Sales deposit): GREY/disabled before sync (p66), BLUE/enabled after sync (p67, p68).
- **Callouts:** p66 "সারাদিনে আপনার বিক্রয় করা সকল তথ্য সঠিক থাকলে “ডাটা সিঙ্ক করুন” অপশনে ক্লিক করুন। এবং প্রক্রিয়াটি সম্পূর্ণ হওয়া পর্যন্ত অপেক্ষা করুন।"; p67 left "সিঙ্ক এর কাজ সম্পূর্ণ হয়ে গেলে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে। এরপর “বিক্রয় জমা” অপশনে ক্লিক করুন।"; p67 right "এরপর আপনার উক্ত রুট এ যদি কোন দোকানের বাকি পরিশোধ অবশিষ্ট থাকে তাহলে এই মেসেজ দেখতে পারবেন। আপনি চাইলে হ্যাঁ অপশনটি নির্বাচন করে বিক্রয় জমা দিতে পারবেন। অথবা আপনি না অপশন নির্বাচন করে পুনরাই বাকি পরিশোধ করতে পারবেন।"; p68 left "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে। এরপর প্রক্রিয়াটি সম্পূর্ণ হওয়ার জন্য কিছুক্ষণ অপেক্ষা করুন"; p68 right "প্রক্রিয়াটি সম্পূর্ণ হয়ে গেলে Success Alert দেখতে পারবেন এরপর “ওকে” অপশন এ ক্লিক করে ডাটা আপলোড এর কাজ সম্পূর্ণ করুন।"
- **Dues dialog (p67 right, appears after tapping "বিক্রয় জমা" when any retailer on the route still owes):** yellow triangle, "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" (the count "১টি" is dynamic), buttons green "হ্যাঁ" (submit anyway), red "না" (go back to collect dues).
- **Success dialog (p68 right):** green check, "বিক্রয় সফল ভাবে জমা হয়েছে" (Sales deposited successfully), button "ওকে".
- **Rules:** R-090, R-091, R-092, R-093, R-094. **Data:** counts of outlets, sales, stock, QC, promotion, distribution/OOS, price compliance, joint call, survey records on device vs server; retailer dues; sales deposit record. **Online-required** (sync + upload). Failure/offline messages are never shown (U-47).
- **Observation for rebuild scoping:** "প্রমোশন" (Promotion), "মূল্য সম্মতি" (Price compliance) and "কিউসি" (QC) are record types counted here but have NO screen anywhere in the AMO manual (U-48).
- **Unclear items for this screen:** U-47, U-47a, U-48 (section F).

## S-52 | Astha - Route information | "আস্থা" | pp 69-70
- **Entry:** dashboard tile "আস্থা" (callout p69: "রুট এবং আউটলেট ভিত্তিক আস্থা এর এসটিডি এবং মেমো এর টার্গেট এবং অ্যাচিভমেন্ট এর ডাটা দেখতে আস্থা অপশনে ক্লিক করুন।"). **Exits:** tab "দোকান ভিত্তিক তথ্য" -> S-53; back.
- **Controls:** two pill tabs "রুটের তথ্য" (Route information, default/active) | "দোকান ভিত্তিক তথ্য" (Shop-based information); route dropdown (outlined purple) "Savar Bazar(Sat, Mon, Wed)"; "বছর নির্বাচন করুন" (Select year) dropdown placeholder "বছর"; "কোয়ার্টার নির্বাচন করুন" (Select quarter) dropdown placeholder "কোয়ার্টার" (greyed until year chosen - INFERRED); empty state "তথ্য পাওয়া যায়নি" (No information found).
- **After selection (p70):** year "2025", quarter "Q-4 (Oct-Dec)" (format "Q-n (Mon-Mon)"); "মাস নির্বাচন করুন" (Select month) multi-select chips "Oct", "Nov", "Dec" (no selected-state visible); sub-tabs "এসটিডি টার্গেট" (STD target, default) | "মেমো টার্গেট" (Memo target). 
- **STD table columns:** "ব্র্যান্ড" | "টার্গেট" | "অর্জন" | "বাকি" | "%". Rows (Target/Achievement/Remaining/%): Maxim ১২,৪৮০/৩৩,০২০/০/২৬৪.৫৮%; Black Diamond ২১৮,০১০/২,০০০/২১৬,০১০/০.৯২%; Abul Bidi Style ০/৬,২৫০/০/০.০০%; Marise -২০/৯,৫০০/০/-৩৭,৫০০.০০% (negative target, clipped); Avon ৪,১৮০/৫,০০০/০/১১৯.৬২%; Supreme ১৬,৩৬০/০/১৬,৩৬০/০.০০%; "Special Abul ..." cut off.
- **Memo table:** one row "All Brand" ২৮ / ৫ / ২৩ / ১৭.৮৬%.
- **Callouts:** p69 "রুট এর তথ্য দেখতে এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।"; p70 left "প্রাথমিক অবস্থায় উক্ত রুট এর এসটিডি টার্গেট দেখতে পাবেন। এরপর মেমো টার্গেট দেখতে মেমো টার্গেট অপশনে ক্লিক করুন।"; p70 right "এরপর মেমো টার্গেট অ্যাচিভমেন্ট এর ডাটা দেখতে পাবেন। সেইসাথে মাস নির্বাচন সেকশান থেকে যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।"
- **Rules:** R-095, R-096, R-097. **Data read:** brand-level STD target/achievement and memo target/achievement by route/year/quarter/month. STD is never expanded (U-49).
- **Unclear items for this screen:** U-49, U-50, U-51 (section F).

## S-53 | Astha - Shop-based information (retailer list) | "আস্থা" | p71
- **Entry:** tab "দোকান ভিত্তিক তথ্য" on S-52 (callout: "আউটলেট ভিত্তিক আস্থা এর এসটিডি এবং মেমো এর টার্গেট এবং অ্যাচিভমেন্ট এর ডাটা দেখতে দোকান ভিত্তিক তথ্য অপশনে ক্লিক করুন।"). **Exit:** tap a retailer card -> S-54.
- **Controls:** the two tabs; route dropdown "Savar Bazar(Sat, Mon, Wed)"; scrollable list of retailer cards (no search/alphabet filter). Card fields: "খুচরা বিক্রেতার নাম" : "Kamal Store (1618408-[phone]-Madrasa Road)"; "মালিকের নাম" : Kamal; "যোগাযোগ" : [phone]; "Sub-Channel" : Diamond (orange); "Wing" : Dhaka; "Division" : Savar; "Territory" : Savar; "Zone" : Savar Metro. Card 2: Shopon (1618409-[phone]-Madrasa Road), owner Sopon, contact [phone], Diamond/Dhaka/Savar/Savar/Savar Metro. Card 3 (partial): Siyam (1618410-[phone]-Madrasa Road), owner Robiul.
- **Callout:** "এরপর আস্থা রিটেইলার লিস্ট দেখতে পারবেন। একটি রিটেইলার নির্বাচন করুন।"
- **Rules:** R-098, R-099. **Data read:** retailers with sub-channel and hierarchy Wing > Division > Territory > Zone.

## S-54 | Astha - Retailer detail (STD / Memo targets per outlet) | "আস্থা" | pp 72-73
- **Entry:** retailer card on S-53. **Exit:** back.
- **Header block (read-only):** "খুচরা বিক্রেতার নাম" : "Zafor Store (1618405-[phone]-Madrasha More)" (wraps); "মালিকের নাম" : "Md.Zafor"; "যোগাযোগ" : "[phone]"; "Sub-Channel" : "Gold" (orange/gold); "Wing" : Dhaka; "Division" : Savar; "Territory" : Savar; "Zone" : Savar Metro (purple). First three labels Bangla, last five English.
- **Selectors:** "বছর নির্বাচন করুন" dropdown (placeholder "বছর", then "2025"); "কোয়ার্টার নির্বাচন করুন" dropdown (placeholder "কোয়ার্টার", greyed until year chosen; then "Q-4 (Oct-Dec)"); empty state "তথ্য পাওয়া যায়নি"; "মাস নির্বাচন করুন" chips Oct/Nov/Dec (toggle chips: unselected light grey/dark text; selected solid purple/white text with leading check mark; 0-3 selectable); sub-tabs "এসটিডি টার্গেট" | "মেমো টার্গেট".
- **STD rows (p72):** Maxim ৮৬০/১০,০০০/০/১,১৬২.৭৯%; Black Diamond ১০,২৭০/১,০০০/৯,২৭০/৯.৭৪%; Abul Bidi Style ০/১,২৫০/০/০.০০%; Marise ০/৪,০০০/০/০.০০%; more rows below fold.
- **Memo target (p73):** one row "All Brand" ১১ / ১ / ১০ / ৯.০৯% (identical with 0 chips and with Nov+Dec selected - U-50).
- **Callouts:** p72 left "এরপর এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।"; p72 right "প্রাথমিক অবস্থায় উক্ত আউটলেট এর এসটিডি টার্গেট দেখতে পারবেন। এরপর মেমো টার্গেট দেখতে মেমো টার্গেট দেখতে মেমো টার্গেট অপশনে ক্লিক করুন।" (duplicated words as printed); p73 "এরপর মেমো টার্গেট অ্যাচিভমেন্ট এর ডাটা দেখতে পারবেন।" and "সেইসাথে মাস নির্বাচন সেকশান থেকে যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।"
- **Rules:** R-095, R-096, R-097, R-099.
- **Unclear items for this screen:** U-50 (section F).

## S-55 | Reports menu | "রিপোর্টসমূহ" | p74 (right); entry via dashboard tile "Report" p74 (left)
- **Entry:** dashboard tile "Report" (callout: "রিপোর্ট এর বিস্তারিত তথ্য দেখতে ড্যাশবোর্ড থেকে Report অপশনে ক্লিক করুন।"). **Exits:** two icon tiles in a red box: "এসটিডি মেমো রিপোর্ট" (STD Memo Report; bar+donut icon) -> S-56; "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ" (Sales Summary Up To Now; pie-with-check icon) -> S-57.
- **Callout:** "Report বাটনে ক্লিক করার পর ইউজার এই দুইটি অপশন পাবেন। এসটিডি মেমো রিপোর্ট দেখতে এসটিডি মেমো রিপোর্ট বাটনে ক্লিক করুন।"
- **Rules:** R-100.

## S-56 | STD Memo Report | "এসটিডি মেমো রিপোর্ট" | p75
- **Entry:** tile on S-55. **Exit:** back.
- **Controls:** date-range picker (red-boxed): calendar icon + "April 1, 2026 - April 25, 2026" (English month names; default = first of current month to current date); picker UI not shown. Horizontally scrollable table (swipe right-to-left to see all columns). Header: first column heading is the AMO's own area name "Agrabad" (holds route names), "সিগারেট" (Cigarette), "লাইটার" (Lighter), further columns off-screen (unknown). Rows (route: সিগারেট / লাইটার): Motiharpol ১৫১,৯৯০ / ৮৪৪; Chowmohoni ২০২,৬১০ / ১৭২; T&T ২৪৬,৯০০ / ৮৯৭; Commerce College ২৮৮,৩৫০ / ৪১১; Pathantuli ৩৮৭,৮০০ / ১,২০০; Beparipara ২৪০,৭০০ / ১,৩৩০; Badamtoli ২৭৮,২৮০ / ১,২২৫; Shishu Park ১৯১,৭৭০ / ১৩৪; Agrabad ১৪২,৬০০ / ৯৯; footer (bold, shaded) "মোট" ২,০৮২,৯৬০ / ৬,৩১২.
- **Callout:** "এরপর ইউজার এই পেজ দেখতে পারবেন। এখানে প্রথম অবস্থাই উক্ত মাসের বর্তমান তারিখ পর্যন্ত টোটাল এসটিডি এবং মেমো ডাটা দেখতে পারবেন। ইউজার প্রয়োজন অনুযায়ী তারিখ নির্বাচন করতে পারবেন। সকল কিছুর ডাটা দেখতে পেজ এর ডান দিক থেকে বাম দিকে Swipe করুন।"
- **Rules:** R-101. **Data read:** per-route sales by category, STD and memo figures for a date range. Cigarette column rows do not sum to the footer (U-52). Units not labelled.
- **Unclear items for this screen:** U-52, U-55 (section F).

## S-57 | Sales Summary Up To Now (route list) | "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ" | p76 (left)
- **Entry:** tile on S-55. **Exit:** route card (">") -> S-58.
- **Cards (route name purple bold English; "CPR : n"; "মোট মেমো : n"; ">"):** Motiharpol ৭৯.৬৭ / ৫৭২; Chowmohoni ৩৩.৪৬ / ৫৮২; T&T ২৪.৫৫ / ৮৮৬; Commerce College ৪০.৯৩ / ৯৩৭; Pathantuli ৪৬.৫৫ / ১,১৮০; Beparipara ৫০.১৭ / ১,১৫৯; Badamtoli ৫৯.৬ / ১,০৪৩; Shishu Park ৩৪.৪৬ / ৬৯৬; Agrabad ৯১.৫২ / ৬৮০. No filters/date picker; period "এখন পর্যন্ত" assumed month-to-date. CPR is never defined (U-53).
- **Callout:** "এরপর ইউজার সকল রুট এর লিস্ট দেখতে পারবেন সেই সাথে রুটগুলোর CPR এবং মোট মেমো সংখ্যা দেখতে পারবেন। একটি রুট এর বিস্তারিত তথ্য দেখতে রুট এর উপর ক্লিক করুন।" and (p75 right) "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ দেখার জন্য এখন পর্যন্ত বিক্রয় সারসংক্ষেপ বাটনে ক্লিক করুন।"
- **Rules:** R-102.
- **Unclear items for this screen:** U-53 (section F).

## S-58 | Route detail (brand-wise target vs sales) | title = route name (e.g. "Motiharpol") | p76 (right)
- **Entry:** route card on S-57. **Exit:** back.
- **Header strip (two KPI tiles):** "CPR" ৭৯.৬৭; "মোট মেমো" ৫৭২.
- **Brand table (light-purple header):** "ব্রান্ড" | "টার্গেট" | "বিক্রয়" | "%" | "মেমো". Rows (Target / Sales / % / Memo): Marise ৮৮,২৩৫.২৯ / ৬৭,১২০ / ৭৬.০৭% / ২৭৯; Black Diamond ১২,৮৬৮.৭১ / ৫৩,৪১০ / ৪১৫.১৭% / ২৬৮; Aster ৬১৭.৬৫ / ৮৪৪ / ১৩৬.৬৫% / ৫৬; Salmon ৫৪.৭১ / ৩৭৭ / ৬৮৯.০৯% / ৫৪; Avon ৮,৮২৩.৫৩ / ২৭,৪০০ / ৩১০.৫৩% / ১৬৫; ARIS ২৬৪.৭১ / ২৮০ / ১০৫.৭৮% / ১০; Special Abul Bidi ১,৭৬৪.৭১ / ১,০০০ / ৫৬.৬৭% / ৩; Existing Abul Bidi/ 42 No. Abul Bidi ৩৫২.৯৪ / ৫০০ / ১৪১.৬৭% / ১; Flame Box ২৩.৮২ / ১২ / ৫০.৩৮% / ১; Ananda Bidi ০.০০ / ১,২৫০ / ০% / ৩; Maxim - Platinum Series ৩,৫২৯.৪১ / ৩,৭৪০ / ১০৭.১% / ৫১. (Table may scroll; Total row existence unknown.)
- **Callout:** "এরপর এই পেজ থেকে উক্ত রুট এর ব্রান্ড অনুযায়ী বিস্তারিত তথ্য দেখতে পারবেন।"
- **Rules:** R-103. Targets carry 2 decimals (pro-rated?); formula unknown (U-54).
- **Unclear items for this screen:** U-54 (section F).

## S-59 | Settings | "সেটিং" | pp 77-79
- **Entry:** dashboard tile "সেটিং" (callout: "ভাষা পরিবর্তন করার জন্য, ডাটা ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য, এবং Logout করার জন্য “সেটিং” বাটনে ক্লিক করুন।"; p77 right: "“সেটিং” বাটনে ক্লিক করার পর ইউজার এই তিনটি অপশন পাবেন।"). **Exit:** back.
- **Three cards in a row (red box):** (1) flag icon + "বাংলা" + ▾ = language dropdown (current language shown; default Bangla); (2) globe/PDA icon + "PDA টু সাপোর্ট" (PDA to Support) button; (3) exit-arrow icon + "লগ আউট" (Log out) button. Nothing else on the screen (no version, no profile).
- **Rules:** R-104. 

## S-60 | Language dropdown | p78 (left)
- **UI:** the dropdown on S-59 expanded as a small floating menu with two entries, each with a flag icon: "en" (English, lower-case code) and "বাংলা" (Bangla). Selecting changes the language of the whole app; no confirmation. Callout: "“En & বাংলা” বাটনের মাধ্যমে আপনি Application এর ভাষা পরিবর্তন করতে পারবেন।"
- **Rules:** R-105. Persistence not stated (U-56).
- **Unclear items for this screen:** U-56 (section F).

## S-61 | PDA to Support (send sync/data file) | p78 (right)
- **Entry:** card "PDA টু সাপোর্ট" on S-59. **UI:** immediately shows a modal success dialog (no pre-confirm, no progress): green check, title "সফল" (Success), message "সিঙ্ক ফাইল পাঠানো হয়ে গেছে" (The sync file has been sent), button "ওকে". Callout: "ডাটা ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য “PDA টু সাপোর্ট” বাটনে ক্লিক করুন।" (p10: "সেলস ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য").
- **Rules:** R-106. **Data:** local sync/data file (read + uploaded to support). Failure/offline behaviour not documented. The file name differs by page: "সেলস ফাইল" (p10), "ডাটা ফাইল" (p77-78 callouts), "সিঙ্ক ফাইল" (dialog).
- **Unclear items for this screen:** U-47 (section F).

## S-62 | Logout confirmation | "লগ আউট করার প্রক্রিয়া" | p79
- **Entry:** card "লগ আউট" on S-59. **UI:** dimmed Settings + dialog: blue "i" icon, "আপনি কি নিশ্চিত যে লগআউট করতে চান?" (Are you sure you want to log out?), buttons green "হ্যাঁ" (red-boxed; confirms) and red "না" (cancels; stays on Settings). Callouts: "ডিভাইস থেকে Logout করার জন্য Logout অপশনে ক্লিক করুণ।"; "এরপর “হ্যাঁ” অপশনটি নির্বাচন করে Logout সম্পূর্ণ করে ফেলুন।"
- **Rules:** R-107. Destination after logout and treatment of unsynced data not stated (U-57).
- **Unclear items for this screen:** U-57 (section F).

### Non-screen pages
- p1 cover ("ARON" / "AMO USER MANUAL"); p80 closing slide "ধন্যবাদ" (Thank you). No fields, rules or messages.


---------------------------------------------------------------------

# B. FLOW LIST (end-to-end workflows)

Notation: "->" = next step; "ALT" = alternate/branch; "NOT DOCUMENTED" = the manual shows no screen or text for that branch. The AMO manual has NO flows for: zero-sale, force-sale, sale cancellation/void, sale return, or offline operation. Those are listed under F-36 as explicit gaps.

**F-01 Install, first launch, login (pp 2-4)**
1. S-01 file manager -> tap APK -> OS "Install" -> "App installed." -> "Open" (ALT "Done": return to file manager; open later from launcher icon "ARON AMO").
2. S-02 Login: enter "ইউজারনেম" + "পাসওয়ার্ড" -> "লগইন".
3. S-03 location prompt -> choose "While using the app" (ALT "Only this time"/"Don't allow": manual says permission is mandatory; consequence NOT DOCUMENTED).
4. If an update is pending -> F-03 (S-05) -> S-06 Dashboard; else S-06.

**F-02 App update, Path A: APK download (pp 5-6)**
1. S-04 "Update Available / Version 1.0.4 available" -> "Download App & Install".
2. OS "Install unknown apps" -> toggle ON for "ARON AMO" -> back.
3. Progress "Downloading n%" (do not close app; keep internet).
4. OS "Do you want to update this app?" -> "Update" (ALT "Cancel": NOT DOCUMENTED) -> "Open".

**F-03 Forced in-app update, Path B (p7, p12)**
1. After login: S-05 prompt "...আপনাকে অবশ্যই আপডেট করতে হবে!" -> "আপডেট".
2. "অ্যাপ আপডেট হচ্ছে" + "প্রসেসিং(৪৬/৮৪)" (do not close the app).
3. -> S-06 Dashboard ("আপডেট সম্পন্ন হওয়ার পরে ... ড্যাশবোর্ড"). 
(No cancel/skip is offered: update is blocking.)

**F-04 Printer pairing and connection (pp 12-16)**
1. Phone Settings > Bluetooth ON; printer power ON (S-07).
2. Scan -> tap "RPP02N" under Available devices -> PIN dialog -> "0000" -> "Pair" (ALT "Cancel").
3. "RPP02N" appears under Paired devices.
4. App: dashboard "স্টক" -> S-45 -> OS Nearby-devices permission -> "Allow" (ALT "Don't allow": printer will not connect).
5. Tap the slashed printer icon -> banner "প্রিন্টার কানেক্ট করা হয়েছে" -> icon turns green.
6. ALT connection failure: no message documented.

**F-05 Daily check-in (pp 17-19)**
1. S-06 -> "অ্যাটেনডেন্স" -> S-08 (shows address; ALT location problem -> tap refresh).
2. "চেক ইন" -> S-09 sheet "চেক ইন করা হচ্ছে" -> verify time -> press and hold green button.
3. S-08 now "চেক ইন সম্পন্ন হয়েছে"; Check-in disabled.

**F-06 Daily check-out (pp 19-20)**
1. S-08 before 17:00: tile "চেক আউট ৫টার পরে সক্রিয় হবে" (disabled).
2. From 17:00: red "চেক আউট" active -> S-10 "চেক আউট করা হচ্ছে" -> verify time -> press and hold red button.
3. S-08: "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।"; both tiles disabled (Already Checked-In / Already Checked-Out).

**F-07 Control Call -> sale at an outlet -> print (pp 25-29, 28 end)**
1. S-06 -> "কন্ট্রল কল" -> S-13: select route, then retailer (optional letter filter).
2. ALT out of range: message "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" -> "রিফ্রেশ" (re-check) OR "ম্যানুয়াল ওভাররাইড" -> F-08.
3. In range / overridden -> S-16 tiles -> "বিক্রয়" -> S-17.
4. Enter quantity per SKU (stepper or typing), running total in bottom bar. Optional "পূর্বের সেল ডাটা দেখুন" (F-10) and back.
5. "এগিয়ে যান →" -> S-18 Review: verify quantities/totals.
6. ALT credit sale -> F-09. ALT "প্রোডাক্ট QC" -> S-21 (NOT DOCUMENTED).
7. "প্রিন্ট" (printer connected; F-04) -> memo printed. ALT not printing now: "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" -> F-11.
8. What persists/syncs the sale and the exit back to S-16: NOT DOCUMENTED (U-22).

**F-08 Manual override (out of range) (pp 21, 25-26)**
1. S-13/S-11 shows out-of-range panel -> "ম্যানুয়াল ওভাররাইড".
2. (First time) camera/audio permission prompts S-15 -> "While using the app".
3. S-14 camera: take one photo of the outlet (flip camera allowed; ALT "✕" cancel: outcome NOT DOCUMENTED). Photo capture updates the outlet's stored location.
4. The out-of-range panel is replaced by the three tiles (S-16).

**F-09 Credit (due) sale with partial payment (pp 28-29)**
1. On S-18 tick "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন".
2. S-19 dialog: type "আদায়কৃত অর্থ" (must be less than grand total; live "বাকিঃ n").
3. "হ্যাঁ" -> S-18 shows "Baki ৪৮ টাকা". ALT "না": dialog closes (INFERRED; checkbox state NOT DOCUMENTED).
4. Later: outlet appears with ": <amount> ৳ বাকি" in the Memo selector (S-46) -> settle via F-12; dues count appears in the Sales Deposit dialog (F-31).

**F-10 View a retailer's previous sale data (p30)**
S-17 -> "পূর্বের সেল ডাটা দেখুন" -> S-20 -> pick any date -> SKU qty/value table + total -> back.

**F-11 Memo reprint (pp 42-43)**
S-06 -> "মেমো" -> S-46 select route + retailer -> "মেমো" tile -> S-47 -> connect printer (printer icon red->green) -> "প্রিন্ট". ALT printer not connected: icon red-slashed (message on press NOT DOCUMENTED).

**F-12 Edit a sale (p43)**
S-47 -> "এডিট" -> S-49 (NOT DOCUMENTED). 

**F-13 Settle a credit memo (pp 44-45)**
S-46 select outlet showing "<amount> ৳ বাকি" -> "মেমো" -> S-47 credit panel "এই বিক্রয়টি বাকিতে করা হয়েছে।" -> "পরিশোধিত করুন" -> S-48 "আপনি কি নিশ্চিত?" -> "হ্যাঁ" (ALT "না": cancel) -> "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" -> "ওকে" -> due label removed from the outlet.

**F-14 Joint call: assess an SR's call (pp 21, 22, 23-24)**
1. S-06 -> "জয়েন্ট কল" -> S-11 -> select route + retailer. ALT out of range -> F-08 (p21 shows the panel on this screen).
2. Tile "এসআর কল অ্যাসেসমেন্ট" -> S-12.
3. Rate each item 1-5 stars in: 5-step sales call (3 items visible; items 4-5 NOT DOCUMENTED), Relationship, Service quality.
4. "সংরক্ষণ" -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে" -> back to S-11 (route and retailer still selected).

**F-15 SR Performance Assessment (pp 31-32)**
1. S-13 -> in range/override -> S-16 -> "এসআর পারফ. অ্যাসেসমেন্ট" -> S-22.
2. Distribution: tick all AKTC SKUs present at the shop (also tick if OOS).
3. OOS: tick those present but out of stock now.
4. POSM: "হ্যাঁ"/"না" (sticker or banner present?).
5. "সংরক্ষণ" -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে" -> S-13 with retailer cleared.

**F-16 Survey (p27)** S-16 -> "সার্ভে" -> S-23 (NOT DOCUMENTED).

**F-17 Team location (p33)** S-06 -> "টিম লোকেশন" -> S-24 -> pick SR from dropdown -> map shows rider marker + name (one SR at a time).

**F-18 Team performance with drill-down (pp 34-36)** S-06 -> "টিম পারফর্মেন্স" -> S-25 (toggle Monthly/Till Date) -> "বিস্তারিত →" or tap a category row -> S-26 (toggle, optionally category-filtered).

**F-19 Assign a task (pp 37-38)** S-06 -> "টাস্ক ডেলিগেশন" -> S-27 -> "+" -> S-28: route -> outlet (SR derived and shown) -> task type -> completion date -> description -> "টাস্ক বরাদ্দ করুন" -> toast "Save Successfully!" -> list shows new task "চলমান".

**F-20 Live dashboard (p39)** S-06 -> "লাইভ ড্যাশবোর্ড" -> S-29 -> select route(s) -> "ফিল্টার" -> tiles for sales, strike rate, geo-fencing, login & sales-deposit status.

**F-21 SR lifted stock (pp 40-41)** S-06 -> "এস আর স্টক" -> S-30 -> tap SR -> S-31 SKU-wise issue + total.

**F-22 Verify an SR-submitted new outlet (pp 47-50)** S-06 -> "আউটলেট" -> S-32 -> "নতুন আউটলেট যাচাইকরণ" -> S-33 -> tap card -> S-34 (prefilled; AMO selects Sub-Channel and Geo Classification; may edit fields) -> "GEO এবং ছবি ধারণ করুন" -> S-35 take one photo -> "সংরক্ষণ" -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে". ALT "বাতিল": NOT DOCUMENTED. No reject path.

**F-23 Verify an SR closure request (pp 51-52)** S-32 -> "আউটলেট বন্ধের যাচাইকরণ" -> S-36 -> card -> S-37 -> "সংরক্ষণ" -> confirm alert (text not shown) -> "হ্যাঁ" -> "Data Updated Successfully" -> "ওকে". ALT "বাতিল"/"না": NOT DOCUMENTED.

**F-24 Verify an SR info-change request (pp 53-54)** S-32 -> "আউটলেট সংশোধনের" -> S-38 -> card -> S-39 (edit any field if wrong; save unchanged = approve) -> "সংরক্ষণ" -> confirm alert (text not shown) -> "হ্যাঁ" -> "Data Updated Successfully" -> "ওকে".

**F-25 AMO adds a new shop (pp 55-57)** S-32 -> "নতুন দোকান" -> S-40: cluster, shop name, owner, mobile -> "GEO এবং ছবি ধারণ করুন" -> S-35 one photo -> "সংরক্ষণ" -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে" (no confirm).

**F-26 AMO permanently closes a shop (pp 58-59, 63)** S-32 -> "স্থায়ী বন্ধ" -> S-41: cluster -> retailer (details shown read-only) -> "সংরক্ষণ" -> "আপনি কি নিশ্চিত?" / "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" -> "হ্যাঁ" (ALT "না") -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে".

**F-27 AMO changes outlet information (pp 60-62)** S-32 -> "তথ্য পরিবর্তন করুন" -> S-42: cluster -> retailer -> edit name/owner/mobile -> "GEO এবং ছবি ধারণ করুন" -> S-35 photo -> "ছবি ধারণ করা সম্পন্ন হয়েছে" -> "ওকে" -> "সংরক্ষণ" -> "আপনি কি নিশ্চিত?" / "এই পরিবর্তন সংরক্ষণ করা হবে" -> "হ্যাঁ" (ALT "না") -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" -> "ওকে".

**F-28 Update outlet base location (pp 64-65)** S-32 -> "আপডেট বেস" -> S-43: route -> outlet -> "আপডেট বেস" -> "আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে?" -> "আপডেট" (ALT "বাতিল") -> camera: one outlet photo -> S-44 map: choose the outlet's location -> "নিশ্চিত করুন" (no success message documented).

**F-29 Stock entry/issue (pp 9, 14-16)** S-06 -> "স্টক" -> S-45: enter Issue per SKU (stepper/typing) -> category totals update -> "সংরক্ষণ" (what happens next NOT DOCUMENTED) -> "প্রিন্ট" (needs printer, F-04).

**F-30 Daily summary and print (p46)** S-06 -> "সারসংক্ষেপ" -> S-50 -> "প্রিন্ট" (needs printer).

**F-31 End-of-day sync and sales deposit (pp 66-68)**
1. S-06 -> "বিক্রয় জমা" -> S-51 (device status "অনলাইন"; device vs server counts).
2. Tap "ডাটা সিঙ্ক করুন" and WAIT until complete.
3. "বিক্রয় জমা" turns from grey (disabled) to blue (enabled).
4. Tap "বিক্রয় জমা". ALT dues exist on the route: dialog "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" -> "হ্যাঁ" (submit anyway) or "না" (return and settle dues via F-13, then sync again).
5. Wait ("কিছুক্ষণ অপেক্ষা করুন") -> "বিক্রয় সফল ভাবে জমা হয়েছে" -> "ওকে".
6. ALT sync failure / offline: NOT DOCUMENTED. Conflicting rule on p68 (U-47a).

**F-32 Astha (pp 69-73)** S-06 -> "আস্থা" -> S-52: route + year + quarter (+ month chips) -> STD target tab / Memo target tab. ALT "দোকান ভিত্তিক তথ্য" -> S-53 -> tap retailer -> S-54 -> year + quarter (+ months) -> STD / Memo.

**F-33 Reports (pp 74-76)** S-06 -> "Report" -> S-55 -> (a) "এসটিডি মেমো রিপোর্ট" -> S-56 (default month-to-date; change date range; swipe left for more columns); (b) "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ" -> S-57 -> tap a route -> S-58 brand table.

**F-34 Change language (pp 77-78)** S-06 -> "সেটিং" -> S-59 -> language dropdown (S-60) -> "en" or "বাংলা" -> UI language switches immediately.

**F-35 Send sync/data file to support (pp 77-78)** S-59 -> "PDA টু সাপোর্ট" -> dialog "সফল" / "সিঙ্ক ফাইল পাঠানো হয়ে গেছে" -> "ওকে".

**F-36 Logout (p79)** S-59 -> "লগ আউট" -> "আপনি কি নিশ্চিত যে লগআউট করতে চান?" -> "হ্যাঁ" (logout) or "না" (stay).

**F-37 Explicit GAPS (no flow in the manual, listed so the rebuild does not assume they exist or are absent)**
- Zero sale / no-sale visit reason, force sale, sale cancel/void, sale return ("ফেরত" appears only as a column on S-50), discount entry (discount columns exist but no entry UI), payment methods other than cash/credit, offline login, forgot/change password, session timeout, attendance correction, SR-side flows (those are in the SR manual), notification handling for badges.


---------------------------------------------------------------------

# C. RULES REGISTER

Type: STATED = the manual says it in words; SHOWN = visible in a screenshot only; INFERRED = deduced (flagged so the rebuild can verify). "Exact wording" is the manual's own text where it exists.

| ID | Rule | Exact wording (verbatim) / evidence | Type | Page | Screens |
|---|---|---|---|---|---|
| R-001 | App is side-loaded as an APK from the file manager; no store. APK name pattern `aron_amo_app_<dd>_<mm>_<yyyy>_v1.apk`; separate APK per role (ARON SR / ARON AMO). | "ARON AMO অ্যাপ্লিকেশনটি ডিভাইসে install দেওয়ার জন্য প্রথমে ফাইল ম্যানেজার ওপেন করুন।" | STATED | 2, 5 | S-01 |
| R-002 | After install, launcher icon "ARON AMO" is used to log in. | "Login করার জন্য icon টির উপর ক্লিক করুন।" | STATED | 3 | S-01 |
| R-003 | Credentials are issued per AMO (username + password). | "নির্দিষ্ট AMO এর জন্য, তাদের নির্দিষ্ট ইউজারনেম এবং পাসওয়ার্ড দিয়ে, “লগইন” বাটনে ক্লিক করুন।" | STATED | 4 | S-02 |
| R-004 | Location permission is mandatory; choose "While using the app" (foreground, precise pre-selected). Prompt appears after tapping Login (not at install). | "অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যয় ইউজারকে লোকেশন পারমিশন দিতে হবে।" | STATED | 4 | S-02, S-03 |
| R-005 | Login footer shows app version (seen 1.0.2) and vendor credit. | "AMO App / (version - 1.0.2) / Developed by Apsis Solutions" | SHOWN | 4 | S-02 |
| R-006 | Self-update needs OS permission "Install unknown apps" ON for ARON AMO; the app sends the user to that page automatically. | "“Download App & Install” অপশনটি নির্বাচন করার পর Automatically ইউজারকে Install Unknow apps পেজে নিয়ে যাবে। এই পেজ থেকে ARON AMO এর বাটনটিতে ক্লিক করে অন করে দিন।" | STATED | 5 | S-04 |
| R-007 | Update screen shows live network status and is network dependent. | "Network Status ● অনলাইন" | SHOWN | 5-6 | S-04 |
| R-008 | Do not close the app while downloading; keep internet connected until done. | "Please do not close the app while downloading"; "ডাউনলোড সম্পন্ন হওয়া পর্যন্ত অপেক্ষা করুন। এবং ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন।" | STATED | 6 | S-04 |
| R-009 | After download: OS "Update", then "Open". | "এখান থেকে “Update” অপশনটিতে ক্লিক করুন। আপডেট সম্পন্ন হয়ে যাবার পর “Open” অপশনে ক্লিক করুন।" | STATED | 6 | S-04 |
| R-010 | In-app update is MANDATORY/blocking and appears after login. | "এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!"; "AMO আইডি দিয়ে লগইন করার পরে ... আপডেট বাটনে ক্লিক করুন।" | STATED | 7 | S-05 |
| R-011 | Do not close the app during in-app update; progress shows "Processing (n/m)". | "টিপস: অ্যাপটি বন্ধ করবেন না"; "প্রসেসিং(৪৬/৮৪)" | STATED/SHOWN | 7 | S-05 |
| R-012 | Phone Bluetooth ON and printer powered ON before pairing. | "সর্বপ্রথম ইউজার এর ডিভাইস এর Setting এ গিয়ে Bluetooth অন করে নিতে হবে। এবং Mobile Printer এর Power অন করে নিতে হবে।" | STATED | 12 | S-07 |
| R-013 | Pair the printer in OS Bluetooth settings: tap "RPP02N", PIN "0000", Pair; it then shows under "Paired devices". | "এরপর Pin Section এ (0000) এর পিন নাম্বারটি প্রদান করুন এবং Pair বাটনটিতে ক্লিক করুন।" | STATED | 13-14 | S-07 |
| R-014 | Bluetooth "nearby devices" runtime permission is mandatory or the printer will not connect. | "ইউজারকে অবশ্যই এই Permission টি Allow অপশনে ক্লিক করে Allow করতে হবে। অন্যথাই প্রিন্টার Connect হবে না।" | STATED | 15 | S-45 |
| R-015 | Printer is connected in-app by tapping the printer icon on the Stock screen; icon red-slashed = disconnected, green = connected; success banner shown. | "এবার ইউজারকে প্রিন্টার আইকন এর উপর ক্লিক করতে হবে।"; "এরপর প্রিন্টার আইকনটি সবুজ বর্ণের হয়ে যাবে।" | STATED | 15-16 | S-45 |
| R-016 | Printing (Stock, Memo, Summary) needs a connected Bluetooth printer; printer-status icon (red slashed / green) is shown on Memo and Summary too. | "প্রিন্টার কানেক্ট করে প্রিন্ট বাটনে ক্লিক করে মেমো প্রিন্ট সম্পন্ন করুন।" | STATED/SHOWN | 43, 46 | S-45, S-47, S-50 |
| R-017 | Dashboard header = "<display name> (<username>)" and "<territory/point name><role>, <yyyy-mm-dd>". Meaning of the date not stated. | e.g. "amo5001 (amo5001)" / "AMO-AgrabadAMO, 2026-04-26" | SHOWN | 8,11,12 | S-06, S-08 |
| R-018 | Menu tile set differs by user/config: some AMOs lack Team Performance, SR Stock, Astha; latest build adds Report. | (no text; screenshots p8 vs p11 vs p12) | SHOWN | 8,11,12 | S-06 |
| R-019 | KPI tiles = achieved/target. Today's = daily; the other three = current month. | "আজকের দিনের সম্পূর্ণ করা টার্গেট / আজকের দিনের টোটাল টার্গেট।"; "চলতি মাসের সম্পূর্ণ করা টার্গেট / চলতি মাসের জন্য টোটাল টার্গেট।" (and control/joint equivalents) | STATED | 11 | S-06 |
| R-020 | Attendance tile is the supervisor's (AMO's) OWN check-in/out. | "সুপারভাইজর তার নিজের চেক ইন চেক আউট করার জন্য “অ্যাটেনডেন্স” অপশনটিতে ক্লিক করুন।" | STATED | 8 | S-06, S-08 |
| R-021 | Control Call = the AMO's own call activity; Joint Call = observing an SR's activity. | "AMO এর নিজের কল একটিভিটি করার জন্য “কন্ট্রোল কল” ..."; "SR এর এক্টিভিটি পর্যবেক্ষণের জন্য “Joint Call” ..." | STATED | 8 | S-06, S-11, S-13 |
| R-022 | Team Location shows the LIVE location of the AMO's own team SRs. | "আপনার টিমের SR দের লাইভ লোকেশান দেখার জন্য টিম লোকেশান বাটনে ক্লিক করুন।" | STATED | 8, 33 | S-24 |
| R-023 | SR Stock scope: all SRs of the AMO's Zone, lifted stock per route per SKU. | "একজন AMO তার Zone এর সকল এস আর এর রুট ভিত্তিক লিফটেড স্টক এর ডাটা SKU অনুসারে দেখতে পারবেন।" | STATED | 9 | S-30, S-31 |
| R-024 | Outlet tile carries a red numeric badge (values ৮, ০ seen); meaning not explained. | (no text) | SHOWN | 8,12 | S-06 |
| R-025 | A "^" chevron under the tile grid collapses/expands the menu. | (no text) | SHOWN/INFERRED | 8 | S-06 |
| R-026 | Must check in before starting work; message when not checked in. | "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।" | STATED | 17-18 | S-08 |
| R-027 | Attendance shows the current address; if getting location fails, use Refresh. | "বর্তমান লোকেশান পেতে কোনো সমস্যার সম্মুখীন হইলে Refresh বাটনে ক্লিক করুন।" | STATED | 17 | S-08 |
| R-028 | Check-in is confirmed by PRESS-AND-HOLD of a green button after viewing the check-in time. | "এরপর চেক ইন এর সময় চেক করুন। তারপর সবুজ বাটনটি চাপ দিয়ে ধরে রাখুন।" | STATED | 18 | S-09 |
| R-029 | After check-in, Check In becomes disabled and shows "completed". | "চেক ইন সম্পন্ন হয়ে যাবার পর চেক ইন বাটনটি Disable হয়ে যাবে এবং চেক ইন স্ট্যাটাস পরিবর্তন হয়ে যাবে।" | STATED | 19 | S-08 |
| R-030 | Check-out is available only from 5 PM (clock source not stated). | "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।"; tile "চেক আউট ৫টার পরে সক্রিয় হবে" | STATED | 19 | S-08, S-10 |
| R-031 | Check-out is confirmed by PRESS-AND-HOLD of a red button after viewing the check-out time. | "এরপর চেক আউট এর সময় চেক করুন। তারপর লাল বাটনটি চাপ দিয়ে ধরে রাখুন।" | STATED | 20 | S-10 |
| R-032 | One check-in and one check-out per day; both then disabled ("Already Checked-In"/"Already Checked-Out"). | "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন।"; "চেক আউট বাটনটি Disable হয়ে যাবে এবং চেক আউট স্ট্যাটাস পরিবর্তন হয়ে যাবে।" | STATED/SHOWN | 20 | S-08, S-10 |
| R-033 | Daily call work = select a specific route ("section") and a specific shop. | "প্রতিদিনের কার্য সম্পূর্ণ করার জন্য নির্দিষ্ট রুট এবং নির্দিষ্ট দোকান নির্বাচন করুন।" | STATED | 21, 25 | S-11, S-13 |
| R-034 | Alphabet quick-filter strip (All + letters) filters the retailer/outlet list by initial; letters shown are dynamic (L,O,Q,U,W,X,Y absent in samples). | (no text) | SHOWN/INFERRED | 21,25,38,42,64 | S-11, S-13, S-28, S-46, S-43 |
| R-035 | Geo-range check between AMO location and retailer; if outside the boundary the app says so and Manual Override MUST be used. Radius not stated. | "AMO যদি নির্দিষ্ট সীমানার বাইরে অবস্থান করে তাহলে ম্যানুয়াল ওভাররাইড অপশন নির্বাচন করতে হবে।" | STATED | 21, 25 | S-11, S-13 |
| R-036 | Manual Override opens the camera; taking the outlet photo also updates that outlet's stored location. | "ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" | STATED | 26 | S-14 |
| R-037 | Camera and microphone permissions must be granted with "While using the app". | "অবশ্যয় “While using the app” বাটনটিতে ক্লিক করার মাধ্যমে Permission দিতে হবে।" | STATED | 22 | S-15 |
| R-038 | Joint Call exposes "এসআর কল অ্যাসেসমেন্ট" with three sections: 5-step sales call, relationship, service quality. | "৫ স্টেপ সেলস কল অ্যাসেসমেন্ট / রিলেশনশিপ এসেসমেন্ট / সার্ভিস কোয়ালিটি এসেসমেন্ট" | STATED | 23 | S-11, S-12 |
| R-039 | Rating scale 1-5 stars; 1 lowest, 5 highest. | "AMO তার মার্কিং ১ থেকে ৫ এর মধ্যে মূল্যায়ন করতে পারবে।"; "1 হল সর্বনিম্ন স্তর এবং 5 হল সর্বোচ্চ স্তর।" | STATED | 23 | S-12 |
| R-040 | Star 1 is pre-selected on every item in all screenshots; no "rating required" validation shown, so save is always possible (INFERRED). | (no text) | SHOWN/INFERRED | 23-24 | S-12 |
| R-041 | Assessment saved with "সংরক্ষণ"; success dialog; no validation messages documented. | "এরপর সংরক্ষন অপশনে ক্লিক করে ডাটা সংরক্ষন করুন।" | STATED | 24 | S-12 |
| R-042 | Control Call exposes three actions: Sales, SR Perf. Assessment, Survey (tiles replace the out-of-range panel). | "কন্ট্রোল কল অপশন থেকে ইউজার বিক্রয় অপশনে ক্লিক করে বিক্রয় কাজ সম্পন্ন করতে পারবেন।" etc. | STATED/SHOWN | 26-27 | S-16 |
| R-043 | The AMO can sell at the outlet during a Control Call. | "ইউজার বিক্রয় অপশনে ক্লিক করে বিক্রয় কাজ সম্পন্ন করতে পারবেন।" | STATED | 26 | S-16, S-17 |
| R-044 | Sale quantity is entered per SKU in sticks (শলাকা) as per the shopkeeper's demand; stepper or typing; min 0 shown. | "SKU অনুযায়ী দোকানদারের চাহিদামত শলাকার পরিমাণ উল্লেখ করুন।" | STATED | 28 | S-17 |
| R-045 | Running total value is shown in the bottom bar. | (bottom bar "১১৬.৪২") | SHOWN | 28 | S-17 |
| R-046 | "এগিয়ে যান" proceeds to the Review screen to verify quantities. | "এরপর এগিয়ে যান অপশনে ক্লিক করুন।"; "বিক্রয় এর পরিমাণ সঠিক আছে কিনা সেটি যাচাই করতে পারবেন" | STATED | 28 | S-17, S-18 |
| R-047 | App computes category subtotals (Cigarette, Bidi, Lighter, Match), total discount and grand total. | (rows "মোট সিগারেট", "মোট বিড়ি", "মোট লাইটার", "মোট ম্যাচ", "মোট ডিসকাউন্ট", "সর্বমোট") | SHOWN | 28, 43 | S-18, S-47 |
| R-048 | A sale may be flagged as due/credit by a checkbox (unchecked by default). | "বিক্রয় যদি বাকীতে হয়ে থাকে তাহলে বিক্রয়টি ক্রেডিট হিসাবে চিহ্নিত করুন অপশনটি নির্বাচন করুন।" | STATED | 28 | S-18 |
| R-049 | Collected amount for a credit sale MUST be less than the grand total. | "এই পরিমাণটি অবশ্যই সর্বমোট টাকার পরিমাণ এর থেকে কম হতে হবে।" | STATED | 29 | S-19 |
| R-050 | Remaining due = grand total - collected, shown live in the field suffix ("বাকিঃ n") and afterwards in the checkbox label ("Baki n টাকা"; whole taka). | "এরপর আপনি উক্ত দোকানের জন্য অবশিষ্ট বাকি টাকার পরিমাণ দেখতে পারবেন।" | STATED/SHOWN | 29 | S-18, S-19 |
| R-051 | Printing of a memo can be postponed to the Memo section. | "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" | STATED | 28 | S-18, S-46 |
| R-052 | Previous sale data of a shop can be viewed for ANY date via a date picker. | "ইউজার যেকোন তারিখ এর সেল দেখতে পারবেন তারিখ নির্বাচন করার মাধ্যমে।" | STATED | 30 | S-17, S-20 |
| R-053 | "প্রোডাক্ট QC" button exists on the Review screen; function not described. | (button only) | SHOWN | 28 | S-18, S-21 |
| R-054 | Distribution tick rule: tick every AKTC SKU present at the shop, even if it has gone OOS. | "অ্যাসেসমেন্ট মুহূর্তে এস.কে.ইউ দোকানে থাকলে অথবা OOS হয়ে গেলেও টিক চিহ্ন দিন।" | STATED | 31 | S-22 |
| R-055 | OOS tick rule: tick SKUs sold at the shop but out of stock right now. | "OOS থাকলে টিক চিহ্ন দিন।"; "যেই যেই SKU নির্দিষ্ট দোকানে বিক্রয় করা হয় কিন্তু এই মুহূর্তে স্টক নেই, সেই SKU গুলা নির্বাচন করুন ।" | STATED | 31 | S-22 |
| R-056 | POSM = sticker or banner present, entered as "হ্যাঁ" or "না". | "নির্দিষ্ট দোকানে যদি কোন স্টিকার অথবা ব্যানার থাকে তাহলে “হ্যাঁ” অথবা “না” অপশন এর মাধ্যমে এন্ট্রি করুন।" | STATED | 32 | S-22 |
| R-057 | After Save the success alert shows; on OK the Control Call screen returns with the retailer cleared. | "সংরক্ষণ বাটনে ক্লিক করার পরে এলার্ট দেখতে পারবেন।" | STATED/SHOWN | 32 | S-22, S-13 |
| R-058 | Team Location: one SR at a time (single select); map shows SR name + live location on Google Maps. | "নির্দিষ্ট SR কে সিলেক্ট করার পর ঐ SR এর নাম, এবং ঐ SR এর লাইভ লোকেশন Google Maps এ দেখতে পারবেন।" | STATED | 33 | S-24 |
| R-059 | Team Performance has Monthly Target (default) and Till Date Target modes; zone and route cards with 4 categories (Cigarette, Bidi, Lighter, Match). | "এখান থেকে Monthly Target এবং Till Date Target দেখতে পারবেন।" | STATED | 34 | S-25, S-26 |
| R-060 | Percent = achieved/target; bar and % coloured red/yellow/green by value (thresholds not stated); summary cards cap the % at 100 while the details table does not. | (no text; screenshots) | SHOWN/INFERRED | 34-35 | S-25, S-26 |
| R-061 | Details table: Remaining = max(Target - Achievement, 0); % = Achievement/Target x 100 with 2 decimals, uncapped (e.g. 202.49%). | (no text; numbers) | SHOWN/INFERRED | 35 | S-26 |
| R-062 | Achievement is identical in Monthly and Till Date modes; only Target, Remaining and % change (Till Date target is pro-rated, formula not stated). | (no text; numbers) | SHOWN/INFERRED | 35 | S-26 |
| R-063 | Tapping a category (e.g. Bidi) on any zone/route card shows only that category's details. | "যেমন শুধু বিড়ি এর তথ্য দেখতে বিড়ি এর উপর ক্লিক করুন।" | STATED | 36 | S-25, S-26 |
| R-064 | Task Delegation list shows the tasks assigned BY the logged-in AMO ("Assigned Tasks" tab); "+" assigns a new task. | "আপনার দ্বারা Assign কৃত টাস্কগুলো দেখতে পারবেন। নতুন টাস্ক Assign করার জন্য প্লাস বাটনে ক্লিক করুন।" | STATED | 37 | S-27 |
| R-065 | Assigning: select route/section and outlet, then task type and completion date, then "টাস্ক বরাদ্দ করুন". Assignee = the outlet's SR (no SR picker). Mandatory fields/date limits not stated. | "রুট/সেকশন এবং নির্দিষ্ট আউটলেট সিলেক্ট করুন। টাস্ক টাইপ এবং টাস্ক কমপ্লিট করার ডেট সিলেক্ট করুন এবং টাস্ক বরাদ্দ করুন বাটনে ক্লিক করুন।" | STATED/INFERRED | 38 | S-28 |
| R-066 | After assigning, a success toast is shown and the task appears in the list. | "এরপর টাস্ক বরাদ্দের একটি সফল alert দেখতে পারবেন।" | STATED | 38 | S-27 |
| R-067 | Task status value seen: "চলমান" (Ongoing). Other statuses not shown. | (card status text) | SHOWN | 37-38 | S-27 |
| R-068 | Live Dashboard: select route/section then press the Filter button to load live data. | "রুট/সেকশন সিলেক্ট করে ফিল্টার বাটনে ক্লিক করুন।" | STATED | 39 | S-29 |
| R-069 | Live metrics: Sales (count, taka); Strike rate = Successful Calls / Target outlets (1/81 = 1.23%); Geo-fencing (target outlets, geo validation, photo validation, outlets visited); Login % (total login/target route); Sales-deposit % (deposits/logged in). Formulas derived from the numbers. | (no text) | SHOWN/INFERRED | 39 | S-29 |
| R-070 | SR phone numbers are masked ("***********") on SR List and Lifted Stock. | (screenshot) | SHOWN | 40-41 | S-30, S-31 |
| R-071 | Lifted stock shown per SKU ("ইস্যু") for the chosen SR with a total across SKUs; read-only, no date selector. | "এরপর এখান থেকে এস কে ইউ অনুযায়ী লিফটেড স্টক দেখতে পারবেন।" | STATED | 41 | S-31 |
| R-072 | Memo: select route and outlet, then press the Memo tile. | "রুট এবং আউটলেটে সিলেক্ট করে মেমো বাটনে ক্লিক করুন।" | STATED | 42 | S-46 |
| R-073 | Outlets with outstanding credit show ": <amount> ৳ বাকি" (green) next to their name in the outlet dropdown. | (screenshot) | SHOWN | 42,44 | S-46 |
| R-074 | Memo can be re-printed after connecting the printer. | "প্রিন্টার কানেক্ট করে প্রিন্ট বাটনে ক্লিক করে মেমো প্রিন্ট সম্পন্ন করুন।" | STATED | 43 | S-47 |
| R-075 | A sale can be edited from the memo screen via "এডিট"; editable fields/limits not stated (Edit looks greyed on a credit memo). | "বিক্রয় এডিট করার জন্য এডিট অপশনে ক্লিক করে বিক্রয় এডিট করে নিতে পারবেন।" | STATED | 43 | S-47, S-49 |
| R-076 | Credit memos show "এই বিক্রয়টি বাকিতে করা হয়েছে।" with a single "পরিশোধিত করুন" button (no amount field, no partial payment). | "বাকীতে বিক্রি আউটলেট গুলোর বাকী পরিশোধ করতে ... মেমো বাটনে ক্লিক করুন।" | STATED/SHOWN | 44 | S-47, S-48 |
| R-077 | Marking paid needs Yes/No confirmation; afterwards the due status disappears beside the outlet. | "বাকী স্ট্যাটাসটি আউটলেট এর পাশে থেকে মুছে যাবে।" | STATED | 45 | S-48, S-46 |
| R-078 | Summary shows the whole day's sales by SKU and category with discount and total; Print prints the summary. | "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে"; "প্রিন্ট বাটনে ক্লিক করে সারসংক্ষেপ প্রিন্ট করে নিতে পারবেন।" | STATED | 46 | S-50 |
| R-079 | Outlet hub: SR-raised requests (new outlet, closure, correction) are verified by the AMO; red count badges on those three tiles; AMO's own shop operations below (new shop, permanent closure, change info, update base). | "এস আর থেকে প্রেরিত আউটলেট সংক্রান্ত তথ্য যাচাই এবং নতুন আউটলেট সংযোজন, স্থায়ী বন্ধ, তথ্য পরিবর্তন এবং বেস আপডেট এর জন্য ... আউটলেট বাটনে ক্লিক করুন।" | STATED | 47 | S-32 |
| R-080 | New-outlet verification: SR-entered fields are pre-filled; the AMO inputs Sub-Channel and Geo Classification. | "AMO Sub-Channel এবং Geo Classification এর ডাটা ইনপুট করতে পারবেন।" | STATED | 48 | S-34 |
| R-081 | New shop: enter info FIRST, then "GEO এবং ছবি ধারণ করুন"; the camera opens; take exactly ONE photo of the outlet. | "তথ্য ইনপুট শেষে GEO এবং ছবি ধারন করুন অপশনে ক্লিক করুন।"; "ঐ আউটলেটের একটি ছবি তুলুন।" | STATED | 49, 56 | S-34, S-35, S-40 |
| R-082 | New-shop Save shows the success dialog directly (no Yes/No confirmation). After photo, click Save. | "ছবি কেপচার শেষ করে, সংরক্ষণ বাটনে ক্লিক করুন।" | STATED | 50, 57 | S-34, S-40 |
| R-083 | Closure verification: view details, Save, confirm with "হ্যাঁ", success "Data Updated Successfully". | "সংরক্ষণ বাটনে ক্লিক করার পরে, একটি এলার্ট দেখতে পারবেন। সেখান থেকে হ্যাঁ অপশনে ক্লিক করুন।" | STATED | 52 | S-37 |
| R-084 | Info-change verification: AMO may edit any field if wrong; if correct just Save; Save -> confirm "হ্যাঁ" -> success "Data Updated Successfully". No GEO/photo recapture. | "দোকানের তথ্য ভুল মনে হইলে আপনারা তথ্যগুলো পরিবর্তন করতে পারবেন। আউটলেটের তথ্য সঠিক মনে হইলে “সংরক্ষন করুন” অপশনে ক্লিক করুন।" | STATED | 54 | S-39 |
| R-085 | AMO-initiated New shop uses a cluster-based form (no route, no Sub-Channel/Geo Classification, no Cancel). | (screenshot) | SHOWN | 56 | S-40 |
| R-086 | AMO-initiated permanent closure requires choosing cluster + retailer and a Yes/No confirmation stating the closure is permanent. | "আপনি কি নিশ্চিত?" / "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" | STATED/SHOWN | 58-59 | S-41 |
| R-087 | AMO-initiated info change: after changing info the "GEO এবং ছবি ধারণ করুন" option must be selected; Save asks for confirmation. | "তথ্য পরিবর্তন শেষে “GEO এবং ছবি ধারন করুন” অপশনটি নির্বাচন করতে হবে।" | STATED | 60-62 | S-42 |
| R-088 | After the photo in the change-info flow a "Successful" alert is shown, then OK. | "ছবি উঠানোর পর একটি “Successful” alert দেখতে পারবেন। এরপর “OK” অপশনে ক্লিক করুন।" | STATED | 61 | S-35, S-42 |
| R-089 | Base-location update: choose route/section + outlet -> "আপডেট বেস" -> confirm "আপডেট" -> take one outlet photo -> choose outlet location on Google Maps -> "নিশ্চিত করুন". | "আউটলেটের ছবি তুলার পরে Google Maps অপশন থেকে ঐ আউটলেটের লোকেশনটি সিলেক্ট করে নিশ্চিত করুন বাটনে ক্লিক করে ... সম্পন্ন করুন।" | STATED | 64-65 | S-43, S-44 |
| R-090 | Before finishing the day's work, ALL operation data must be synced and the sales deposit submitted. | "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই সেকশন থেকে বিক্রয় জমা সাবমিট করুন।" | STATED | 66-68 | S-51 |
| R-091 | "বিক্রয় জমা" is disabled (grey) until data sync completes, then enabled (blue). | "সিঙ্ক এর কাজ সম্পূর্ণ হয়ে গেলে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে।" | STATED | 67 | S-51 |
| R-092 | If any retailer on the route still owes, a dialog with the count asks whether to submit anyway (Yes) or go back (No) to settle dues. Dues do NOT block submission. | "আপনি চাইলে হ্যাঁ অপশনটি নির্বাচন করে বিক্রয় জমা দিতে পারবেন। অথবা আপনি না অপশন নির্বাচন করে পুনরাই বাকি পরিশোধ করতে পারবেন।" | STATED | 67 | S-51 |
| R-093 | CONFLICT: p68 says the deposit button is enabled when ALL shops' dues are paid and sync is done. | "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে।" | STATED (conflicts with R-092) | 68 | S-51 |
| R-094 | Sales Deposit shows device-vs-server counts for 9 record types (Outlet, Sales, Stock, QC, Promotion, Distribution and OOS performance, Price compliance, Joint call, Survey) and device online status. | (table) | SHOWN | 66-68 | S-51 |
| R-095 | Astha: choose route (or retailer), then Year, then Quarter ("Q-n (Mon-Mon)"); until chosen the screen shows "তথ্য পাওয়া যায়নি". Month chips allow one month or all 3 months of the quarter. | "রুট এর তথ্য দেখতে এখান থেকে বছর এবং কোয়ার্টার নির্বাচন করুন।"; "যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন।" | STATED | 69-70, 73 | S-52, S-54 |
| R-096 | Astha has two target types: STD target (per brand) and Memo target (single "All Brand" row); STD shown first by default. | "প্রাথমিক অবস্থায় ... এসটিডি টার্গেট দেখতে পাবেন। এরপর মেমো টার্গেট দেখতে মেমো টার্গেট অপশনে ক্লিক করুন।" | STATED | 70, 72 | S-52, S-54 |
| R-097 | Astha table: Remaining = max(Target - Achievement, 0); % = Achievement/Target x 100 (2 decimals); % = 0.00 when target is 0; negative target shown as-is. | (numbers) | SHOWN/INFERRED | 70, 72, 73 | S-52, S-54 |
| R-098 | Shop-based Astha: list of retailers of the chosen route; selecting one opens its detail. | "এরপর আস্থা রিটেইলার লিস্ট দেখতে পারবেন। একটি রিটেইলার নির্বাচন করুন।" | STATED | 71 | S-53 |
| R-099 | Retailer block shows Sub-Channel (Diamond/Gold) and hierarchy Wing > Division > Territory > Zone. | (card fields) | SHOWN | 71-73 | S-53, S-54 |
| R-100 | Report tile gives two reports: STD Memo Report and Sales Summary Up To Now. | "Report বাটনে ক্লিক করার পর ইউজার এই দুইটি অপশন পাবেন।" | STATED | 74 | S-55 |
| R-101 | STD Memo Report defaults to month-to-date; user may select dates; wide table scrolls by swipe right-to-left. | "উক্ত মাসের বর্তমান তারিখ পর্যন্ত টোটাল এসটিডি এবং মেমো ডাটা ... ইউজার প্রয়োজন অনুযায়ী তারিখ নির্বাচন করতে পারবেন। সকল কিছুর ডাটা দেখতে পেজ এর ডান দিক থেকে বাম দিকে Swipe করুন।" | STATED | 75 | S-56 |
| R-102 | Sales Summary Up To Now lists all routes with CPR and total memo count; tap a route for detail. | "একটি রুট এর বিস্তারিত তথ্য দেখতে রুট এর উপর ক্লিক করুন।" | STATED | 76 | S-57 |
| R-103 | Route detail: brand-wise Target, Sales, % (Sales/Target x100, 2 dp; shows 0% when target is 0.00) and Memo count. | "উক্ত রুট এর ব্রান্ড অনুযায়ী বিস্তারিত তথ্য" | STATED/SHOWN | 76 | S-58 |
| R-104 | Settings has exactly three options: language, PDA to Support, Log out. | "“সেটিং” বাটনে ক্লিক করার পর ইউজার এই তিনটি অপশন পাবেন।" | STATED | 77 | S-59 |
| R-105 | Language can be switched at runtime between "en" and "বাংলা". | "“En & বাংলা” বাটনের মাধ্যমে আপনি Application এর ভাষা পরিবর্তন করতে পারবেন।" | STATED | 78 | S-60 |
| R-106 | "PDA টু সাপোর্ট" sends the device data/sync file to the support team (no pre-confirm; result dialog only). | "ডাটা ফাইল ডিভাইস থেকে সাপোর্ট টিমকে পাঠানোর জন্য “PDA টু সাপোর্ট” বাটনে ক্লিক করুন।" | STATED | 10, 77-78 | S-61 |
| R-107 | Logout requires Yes/No confirmation. | "আপনি কি নিশ্চিত যে লগআউট করতে চান?" | STATED | 79 | S-62 |
| R-108 | Numbers are shown in Bangla digits with Western 3-digit comma grouping (e.g. ২,০৮২,৮২০), 2 decimals for money/percent; some fields (typed amount "100", dates, times "04:05 PM") are Latin digits. | (screenshots) | SHOWN | 28-36,46,70-76 | all |
| R-109 | Retailer/outlet label format "Name (code-mobile-cluster/address)"; mobile without leading 0; an empty mobile yields "--" ("Sujon Store (1618414--Madrasa Road)"). | (screenshots) | SHOWN | 25,28,42,58,60,71 | S-11, S-13, S-17, S-41, S-42, S-46, S-53 |
| R-110 | Route label includes frequency/days or "Daily" appended to the name without space: "Savar BazarDaily", "Savar Bazar(Sat, Mon, Wed)", "Court Bari(Sun, Tue, Thu)". | (screenshots) | SHOWN | 21,29,39,50,69 | S-11, S-13, S-18, S-29, S-52 |
| R-111 | Mobile numbers appear inconsistently with and without the leading 0 ([phone] vs [phone]). | (screenshots) | SHOWN | 48-53,58,71-73 | S-33..S-42, S-53, S-54 |
| R-112 | Bangla/English mix is part of the UI: English labels in places (Report, Sub-Channel, Geo Classification, Wing/Division/Territory/Zone, Assigned Tasks, Successful Calls, "Already Checked-In", "Data Updated Successfully", "Save Successfully!"). | (screenshots) | SHOWN | various | various |
| R-114 | Stock screen: per-SKU Issue entered by stepper (−/+) or typing; read-only Stock value beside it; limits (issue <= stock) not stated. | (screenshot) | SHOWN | 15 | S-45 |
| R-115 | Stock screen bottom panel summarises Category (Cigarette, Bidi, Lighter, Match) Total Issue and Stock. | (screenshot) | SHOWN | 15 | S-45 |
| R-116 | Stock screen has Save (enabled) and Print (grey while printer is not connected; stays grey after connection in p16). | (screenshot) | SHOWN | 15-16 | S-45 |
| R-117 | Printer state indicator: red-slashed printer = disconnected, green printer = connected; connected banner "প্রিন্টার কানেক্ট করা হয়েছে". | (screenshot) | SHOWN | 15-16, 43 | S-45, S-47 |


---------------------------------------------------------------------

# D. MESSAGES REGISTER

Every user-visible message/dialog/status/empty-state/help text in the manual, verbatim, with English meaning and screen. Type: OS = Android system UI (not app-owned, but part of the documented journey); DLG = app dialog; TOAST; STATUS = inline state text; INFO = help/info box; EMPTY = empty state; WARN = persistent warning. NOTE: the manual contains NO error messages (no wrong password, no network failure, no validation error, no sync failure, no offline banner, no permission-denied text). That absence is itself a finding - see U-47.

| ID | Type | Verbatim text | English meaning | Screen | Page |
|---|---|---|---|---|---|
| M-001 | OS | Do you want to install this app? [Cancel] [Install] | install confirm | S-01 | 2 |
| M-002 | OS | App installed. [Done] [Open] | install complete | S-01 | 3 |
| M-003 | OS | Allow ARON AMO to access this device's location? [Precise / Approximate] [While using the app] [Only this time] [Don't allow] | location permission | S-03 | 4 |
| M-004 | STATUS | Network Status ● অনলাইন | network status: Online | S-04 | 5-6 |
| M-005 | STATUS | NEW UPDATE / Update Available / Version 1.0.4 available | update banner | S-04 | 5 |
| M-006 | BTN | Download App & Install | start update download | S-04 | 5 |
| M-007 | OS | Installing apps from this source may put your phone and data at risk. | install-unknown-apps warning | S-04 (OS) | 5 |
| M-008 | STATUS | Downloading 53% | download progress | S-04 | 6 |
| M-009 | WARN | Please do not close the app while downloading | do not close during download | S-04 | 6 |
| M-010 | OS | Do you want to update this app? [Cancel] [Update] | update confirm | S-04 | 6 |
| M-011 | DLG | কিছু নতুন আপডেট পাওয়া গেছে. এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে! | Some new updates found; you must update to continue | S-05 | 7 |
| M-012 | BTN/INFO | আপডেট ; Current version 1.0.2 | Update ; current version | S-05 | 7 |
| M-013 | STATUS | অ্যাপ আপডেট হচ্ছে ; প্রসেসিং(৪৬/৮৪) | App is updating ; Processing (46/84) | S-05 | 7 |
| M-014 | WARN | টিপস: অ্যাপটি বন্ধ করবেন না | Tip: do not close the app | S-05 | 7 |
| M-015 | INFO | AMO App (version - 1.0.2) Developed by Apsis Solutions | login footer | S-02 | 4 |
| M-016 | OS | Turn on Bluetooth to connect to nearby devices. | Bluetooth off helper | S-07 | 12 |
| M-017 | OS | Make sure the device you want to connect to is in pairing mode. Your phone (MD's A12) is currently visible to nearby devices. | pairing help | S-07 | 13-14 |
| M-018 | OS | Bluetooth pairing request - Enter PIN to pair with RPP02N (Try 0000 or 1234). [Cancel] [Pair] | PIN dialog | S-07 | 13 |
| M-019 | OS | Device name will appear when this device is connected. | unnamed device helper | S-07 | 14 |
| M-020 | OS | Allow ARON AMO to find, connect to and determine the relative position of nearby devices? [Allow] [Don't allow] | Bluetooth nearby-devices permission | S-45 | 15 |
| M-021 | TOAST | প্রিন্টার কানেক্ট করা হয়েছে | Printer connected | S-45 | 16 |
| M-022 | STATUS | আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | You have not checked in yet. Please check in before starting work. | S-08 | 17-18 |
| M-023 | STATUS | চেক ইন করা হচ্ছে | Checking in (sheet title) | S-09 | 18 |
| M-024 | BTN | চাপ দিয়ে ধরে রাখুন | Press and hold | S-09, S-10 | 18, 20 |
| M-025 | STATUS | আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Please check out before finishing today's work. | S-08 | 19-20 |
| M-026 | STATUS | চেক ইন সম্পন্ন হয়েছে | Check-in completed | S-08 | 19 |
| M-027 | STATUS | চেক আউট ৫টার পরে সক্রিয় হবে | Check-out will become active after 5 o'clock | S-08 | 19 |
| M-028 | STATUS | Already Checked-In | (English) check-in done | S-08 | 19-20 |
| M-029 | INFO | বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন। | Note: check-out possible from 5 PM | S-08 | 19 |
| M-030 | STATUS | চেক আউট করা হচ্ছে | Checking out (sheet title) | S-10 | 20 |
| M-031 | STATUS | আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ। | You have completed check-in and check-out for today. Thank you for your cooperation. | S-08 | 20 |
| M-032 | STATUS | Already Checked-Out | (English) check-out done | S-08 | 20 |
| M-033 | WARN | AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই। | AMO and the retailer are not within range. | S-11, S-13 | 21, 25 |
| M-034 | EMPTY | রিটেইলার নির্বাচন করুন | Select retailer (placeholder) | S-13 | 32 |
| M-035 | OS | Allow ARON AMO to take pictures and record video? [While using the app] [Only this time] [Don't allow] | camera permission | S-15 | 22 |
| M-036 | OS | Allow ARON AMO to record audio? [While using the app] [Only this time] [Don't allow] | microphone permission | S-15 | 22 |
| M-037 | INFO | SR নিম্নলিখিত পদক্ষেপগুলি কীভাবে সম্পন্ন করেছে তা মূল্যায়ন করুন। চিহ্নিতকরণের ক্ষেত্রে, 1 হল সর্বনিম্ন স্তর এবং 5 হল সর্বোচ্চ স্তর। | Evaluate how the SR completed the following steps; 1 lowest, 5 highest | S-12 | 23 |
| M-038 | DLG | ডেটা সফলভাবে সংরক্ষিত হয়েছে। [ওকে] | Data saved successfully. [OK] | S-12, S-22, S-34, S-40, S-41, S-42 | 24, 32, 50, 57, 59, 62 |
| M-039 | INFO | আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন। | You can print later from the Memo section. | S-18 | 28 |
| M-040 | CHK | এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন | Mark this sale as due | S-18 | 28 |
| M-041 | DLG | পরিশোধিত টাকার পরিমাণ লিখুন | Enter the amount of money paid (dialog title) | S-19 | 29 |
| M-042 | DLG | এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন? | How much money are you collecting from the retailer now? | S-19 | 29 |
| M-043 | STATUS | আদায়কৃত অর্থ | Amount collected (field label) | S-19 | 29 |
| M-044 | STATUS | বাকিঃ ৪৮.৫০ | Due: 48.50 (live suffix) | S-19 | 29 |
| M-045 | STATUS | Baki ৪৮ টাকা | Due 48 taka (checkbox label after confirm) | S-18 | 29 |
| M-046 | INFO | অ্যাসেসমেন্ট মুহূর্তে এস.কে.ইউ দোকানে থাকলে অথবা OOS হয়ে গেলেও টিক চিহ্ন দিন। | Distribution tick instruction | S-22 | 31 |
| M-047 | INFO | OOS থাকলে টিক চিহ্ন দিন। | OOS tick instruction | S-22 | 31 |
| M-048 | TOAST | Save Successfully! | task saved | S-27 | 38 |
| M-049 | STATUS | চলমান | Ongoing (task status) | S-27 | 37-38 |
| M-050 | STATUS | Completion on: 2025-11-03 | task completion date label | S-27 | 37 |
| M-051 | INFO | আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারেন। এছাড়াও আপনি এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারেন | You can view a memo by selecting an outlet; you can also reprint it from here | S-47 | 43 |
| M-052 | STATUS | : ১১৬.৪২ ৳ বাকি | : 116.42 Taka due (beside outlet name) | S-46 | 42, 44 |
| M-053 | STATUS | এই বিক্রয়টি বাকিতে করা হয়েছে। | This sale was made on credit. | S-47 | 43-44 |
| M-054 | DLG | আপনি কি নিশ্চিত? [হ্যাঁ] [না] | Are you sure? [Yes] [No] (mark-paid confirm) | S-48 | 45 |
| M-055 | DLG | এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে। [ওকে] | This memo has been successfully marked as paid. [OK] | S-48 | 45 |
| M-056 | INFO | আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে | Your full day's sales details will be here | S-50 | 46 |
| M-057 | DLG | ডেটা সফলভাবে সংরক্ষিত হয়েছে। (new shop) | (same as M-038) | S-34, S-40 | 50, 57 |
| M-058 | DLG | Data Updated Successfully [ওকে] | (English) data updated | S-37, S-39 | 52, 54 |
| M-059 | DLG | আপনি কি নিশ্চিত? / এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে [হ্যাঁ] [না] | Are you sure? / This shop is going to be permanently closed | S-41 | 59 |
| M-060 | DLG | ছবি ধারণ করা সম্পন্ন হয়েছে [ওকে] | Photo capture completed [OK] | S-35, S-42 | 61 |
| M-061 | DLG | আপনি কি নিশ্চিত? / এই পরিবর্তন সংরক্ষণ করা হবে [হ্যাঁ] [না] | Are you sure? / This change will be saved | S-42 | 62 |
| M-062 | DLG | আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে? [আপডেট] [বাতিল] | Are you sure, to update the retailer's location? [Update] [Cancel] | S-43 | 65 |
| M-063 | STATUS | ডিভাইস স্ট্যাটাস ● অনলাইন | Device status: Online | S-51 | 66-68 |
| M-064 | WARN | আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই সেকশন থেকে বিক্রয় জমা সাবমিট করুন। | Before finishing today's work you must sync all operation data and submit the sales deposit | S-51 | 66-68 |
| M-065 | DLG | আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? [হ্যাঁ] [না] | You still have dues with 1 retailer. Do you want to submit your sales? [Yes] [No] (count dynamic) | S-51 | 67 |
| M-066 | DLG | বিক্রয় সফল ভাবে জমা হয়েছে [ওকে] | Sales deposited successfully [OK] | S-51 | 68 |
| M-067 | EMPTY | তথ্য পাওয়া যায়নি | No information found | S-52, S-53 (list view), S-54 | 69, 71, 72 |
| M-068 | DLG | সফল / সিঙ্ক ফাইল পাঠানো হয়ে গেছে [ওকে] | Success / The sync file has been sent [OK] | S-61 | 78 |
| M-069 | DLG | আপনি কি নিশ্চিত যে লগআউট করতে চান? [হ্যাঁ] [না] | Are you sure you want to log out? [Yes] [No] | S-62 | 79 |
| M-070 | DLG | (alert, text not shown) described only as "একটি এলার্ট ... হ্যাঁ অপশনে ক্লিক করুন" for closure/info-change verification | confirm alert (text unknown) | S-37, S-39 | 52, 54 |
| M-071 | LABEL | Distribution Performance heading "ডিস্ট্রিবিউশন পারফর্মেন্স"; "OOS Performance"; "POSM" with options "হ্যাঁ"/"না" | section headings/options | S-22 | 31-32 |
| M-072 | LABEL | এসআর কল অ্যাসেসমেন্ট sections: "●৫ ধাপ সেলস কল অ্যাসেস্টমেন্ট", "●রিলেসনশিপ এসেসম্যন্ট", "●সার্ভিস কোয়ালিটি এসেসম্যন্ট" and chips "Low" / "উচ্চ" (legend: "High") | section headings, rating chips | S-12 | 23-24 |
| M-073 | LABEL | শুরু করো (named in callout) but button reads "আপডেট" | Start / Update | S-05 | 7 |
| M-074 | LABEL | Outlet hub captions: "এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন" ; "দোকান সংক্রান্ত অপারেশন্স" | Verify the requests coming from SR ; Shop-related operations | S-32 | 47, 51 |
| M-075 | LABEL | Tile labels (hub): "নতুন আউটলেট যাচাইকরণ", "আউটলেট বন্ধের যাচাইকরণ", "আউটলেট সংশোধনের", "নতুন দোকান", "স্থায়ী বন্ধ", "তথ্য পরিবর্তন করুন", "আপডেট বেস" | tile names | S-32 | 47 |
| M-076 | LABEL | Task Delegation labels: "Assigned Tasks", "আউটলেটের নাম:", "মালিকের নাম:", "এসআর নাম:", "এসআর মোবাইল নম্বর:", "টাস্ক টাইপ" (value "General Task"), "টাস্ক কমপ্লিট করার ডেট", "টাস্ক বরাদ্দ করুন" | form labels | S-27, S-28 | 37-38 |
| M-077 | LABEL | Live Data labels: "লাইভ ডেটা", "বিক্রয়", "মোট বিক্রয়", "মোট টাকা", "লাইভ স্ট্রাইক রেট", "টার্গেট আউটলেট", "Successful Calls", "স্ট্রাইক রেট", "জিও ফেন্সিং স্ট্যাটাস", "জিও ভ্যালিডেশন", "ছবি ভ্যালিডেশন", "মোট আউটলেট ভ্রমণ", "লগইন & বিক্রয় জমা স্টেটাস", "লগইন স্টেটাস", "টার্গেট রুট", "মোট লগইন", "বিক্রয় জমা স্টেটাস", "লগইন করেছে", "মোট বিক্রয় জমা", "ফিল্টার" | tile labels | S-29 | 39 |
| M-078 | LABEL | Stock labels: "এসকেইউ", "ইস্যু", "স্টক", "ক্যাটাগরি", "মোট ইস্যু", "সংরক্ষণ", "প্রিন্ট"; categories "সিগারেট", "বিড়ি", "লাইটার", "ম্যাচ" | stock screen labels | S-45 | 15 |
| M-079 | LABEL | Settings labels: "বাংলা", "en", "PDA টু সাপোর্ট", "লগ আউট" | settings | S-59, S-60 | 77-78 |
| M-080 | LABEL | Reports labels: "রিপোর্টসমূহ", "এসটিডি মেমো রিপোর্ট", "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ", "CPR", "মোট মেমো" | report names | S-55..S-58 | 74-76 |
| M-081 | LABEL | Astha labels: "রুটের তথ্য", "দোকান ভিত্তিক তথ্য", "বছর নির্বাচন করুন"/"বছর", "কোয়ার্টার নির্বাচন করুন"/"কোয়ার্টার", "মাস নির্বাচন করুন", "এসটিডি টার্গেট", "মেমো টার্গেট", "ব্র্যান্ড", "All Brand" | astha labels | S-52..S-54 | 69-73 |
| M-082 | LABEL | Team Performance labels: "Monthly Target", "Till Date Target", "Zone: Agrabad", "রুট: Agrabad", "বিস্তারিত →", table "Item"/"টার্গেট"/"অর্জন"/"বাকি"/"%" | performance labels | S-25, S-26 | 34-36 |
| M-083 | LABEL | Sales labels: "বিক্রয়", "পূর্বের সেল ডাটা দেখুন", "এগিয়ে যান →", "নিরীক্ষণ", "এসকেইউ", "পরিমাণ", "মূল্য", "ক্লাস্টার:", "প্রোডাক্ট QC", "প্রিন্ট", "সেল ডাটা", "মোট", "সর্বমোট", "মোট ডিসকাউন্ট", "দাম" | sales labels | S-17, S-18, S-20, S-47 | 28-30, 43 |
| M-084 | LABEL | Summary labels: "বিক্রয় সারসংক্ষেপ", "মেমো", "পরিমাণ", "মূল্য", "ডিসকাউন্ট", "ডিসকাউন্ট মূল্য", "ফেরত", "ডিসকাউন্ট এবং অন্যান্য (-)", "মোট বিক্রয়", "সর্ব মোট" | summary labels | S-50 | 46 |
| M-085 | LABEL | Sales Deposit labels: "ডিভাইস স্ট্যাটাস", "বিষয়", "ডিভাইস", "সার্ভার", "আউটলেট", "বিক্রয়", "স্টক", "কিউসি", "প্রমোশন", "ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা", "মূল্য সম্মতি", "জয়েন্ট কল", "সার্ভে", "ডাটা সিঙ্ক করুন", "বিক্রয় জমা" | deposit labels | S-51 | 66-68 |
| M-086 | LABEL | Outlet forms labels: "একটি রুট সিলেক্ট করুন", "ক্লাস্টার নির্বাচন করুন", "রিটেইলার নির্বাচন করুন", "দোকানের নাম", "দোকানের মালিকের নাম", "মোবাইল নম্বর", "Sub-Channel" / "Select Sub-Channel", "Geo Classification" / "Select Geo Classification", "GEO এবং ছবি ধারণ করুন", "বাতিল", "সংরক্ষণ", "নিশ্চিত করুন", "আপডেট বেস", "বেস রিক্যালিব্রেশন", "অবস্থান নিশ্চিত করুন" | form labels | S-34..S-44 | 48-65 |
| M-087 | LABEL | Card labels: "রুট", "ক্লাস্টার", "খুচরা বিক্রেতার নাম", "মালিকের নাম", "যোগাযোগ" | request-list cards | S-33, S-36, S-38 | 48, 51, 53 |
| M-088 | LABEL | Team Location title "টিম লোকেশন"; SR list "এসআর লিস্ট"; "লিফটেড স্টক"; "মোট আউটলেট" | titles | S-24, S-30, S-31 | 33, 40-41 |
| M-089 | LABEL | Attendance buttons "চেক ইন", "চেক আউট"; title "অ্যাটেনডেন্স" | buttons | S-08 | 17-20 |
| M-090 | LABEL | Dashboard labels: all tile names and KPI labels listed under S-06 | dashboard | S-06 | 8-12 |

Count: 90 message entries (M-001..M-090). Of these, errors = 0; true app dialogs/toasts/status messages ~ 50; the remainder are OS dialogs and label sets kept so no verbatim string is lost.


---------------------------------------------------------------------

# E. DATA ENTITIES AND FIELDS REGISTER

R = read, W = written (create/update) by the screen. Fields marked (?) are implied by a screenshot but not labelled in the manual. "Not stated" fields are NOT invented.

## E-01 User / AMO account
Fields: username (e.g. amo5756, amo5001, ss344002), password, display name ("AMO-5756", "AMO 2", "amo5001"), role label ("AMO"/"SS" suffix in header), territory/point name ("Savar Bazar", "Bepariparа", "AMO-Agrabad"), header date (yyyy-mm-dd, meaning not stated), app language preference ("en" / "বাংলা"), session/token. Screens: S-02 (W: login), S-06, S-08 (R), S-59/S-60 (W language), S-62 (session cleared).

## E-02 App version / update
Fields: current version (1.0.2), available version (1.0.4), update APK (74.39 MB sample), update progress (percent; processing n/m counter), install-unknown-apps permission (OS). Screens: S-01, S-02, S-04, S-05.

## E-03 Device permissions and printer
Fields: location permission (precise/approximate; while-using/only-this-time/deny), camera, microphone, Bluetooth nearby-devices, install-unknown-apps; paired printer (name "RPP02N", PIN 0000), printer connection state (red/green). Screens: S-03, S-04, S-07, S-15, S-45, S-47, S-50.

## E-04 Org hierarchy / geography
Fields: Wing (Dhaka), Division (Savar), Territory (Savar), Zone (Savar Metro; also "Agrabad" as zone on Team Performance), Cluster (Madrasa Road, North Nama Bazar, Kamarporti, "Savar Metro" shown as cluster on Review), Route/section (name + schedule: "Savar BazarDaily", "Savar Bazar(Sat, Mon, Wed)", "Court Bari(Sun, Tue, Thu)", "AgrabadDaily", "Agrabad", "Motiharpol", "Chowmohoni", "T&T", "Commerce College", "Pathantuli", "Beparipara", "Badamtoli", "Shishu Park"), AMO's area label. Note "Zone" vs "Cluster" is used for "Savar Metro" on different screens (U-58). Screens: S-11, S-13, S-18, S-25, S-26, S-28, S-29, S-33, S-34..S-44, S-46, S-47, S-52..S-58.

## E-05 Outlet / Retailer
Fields: outlet code (e.g. 1618405..1618414), shop name, owner name, mobile/contact (with/without leading 0), address/landmark segment of the label ("Madrasa Road", "Madrasha More"), cluster, route, sub-channel (GT, Diamond, Gold; Sub-Channel options unknown), geo classification (Urban; options unknown), GPS lat/long (home/base location, updated by photo override and Update Base), outlet photo, status (active / permanently closed), assigned SR (name "SR-Kakoli - 1"), SR mobile (blank in sample), outstanding due (বাকি), outlet class suffix "(Diamond)" in some labels, total outlets per SR. Screens: S-11, S-13, S-14, S-17, S-18, S-28, S-33..S-44, S-46, S-47, S-53, S-54.

## E-06 SR (Sales Representative)
Fields: SR code ("SR-12237"), SR name/route label ("SR-Kakoli - 1", "SenbagDaily"), mobile (masked), total outlets, live location (lat/long, timestamp not shown), lifted stock per SKU. Screens: S-24, S-28, S-30, S-31.

## E-07 Product: SKU / brand / category
Fields: SKU code/name (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, Avon-20S, AB-12s, SAB-12s, ARIS-O-20s / ARIS-A-20s (conflict), ABS, EAB, Aster, FB, SL, AVON item), pack image, category (সিগারেট/Cigarette, বিড়ি/Bidi, লাইটার/Lighter, ম্যাচ/Match), price per unit (derived 8.00 per stick for MaxR-10S; not shown), brand list for assessments (Maxim, Avon, ARIS, Marise, Black Diamond, Sunmoon, Supreme, Special Abul Bidi, Existing Abul Bidi/ 42 No., Ananda Bidi, Abul Bidi Gold, Abul Bidi Style, Aster, Flame Box, Salmon), brand/variant display names in targets ("Marise Special Blend", "Black Diamond Advanced", "Supreme Fresh Max", "ARIS Apple", "Special Abul Bidi 25", "Maxim - Platinum Series"). Units: sticks (শলাকা) for entry; unit of other quantities not labelled. Screens: S-17, S-18, S-20, S-22, S-25, S-26, S-31, S-45, S-47, S-50, S-52..S-54, S-58.

## E-08 Sale / Memo (header + lines)
Fields: memo/sale id (not shown), retailer, route/section, cluster, date (not shown on memo), lines (SKU, quantity, line value "মূল্য"/"দাম"), category subtotals (qty, value), total discount, grand total ("সর্বমোট"), credit flag ("বাকি"), collected amount, outstanding due, paid flag, print state. Memo-count per SKU ("মেমো" column), discount, discount value, return ("ফেরত"). Screens: S-17 (R/W), S-18 (W), S-19 (W), S-20 (R), S-46, S-47 (R/W: mark paid, edit), S-48 (W), S-49 (W), S-50 (R).

## E-09 Credit / due / payment
Fields: collected amount at sale time (must be < total), remaining due (live), outstanding due per retailer (shown beside outlet), paid status (credit -> paid), count of retailers with dues (Sales Deposit dialog). Screens: S-18, S-19, S-46, S-47, S-48, S-51.

## E-10 Stock
Fields: AMO stock per SKU (read-only "স্টক"), Issue per SKU (entered), category totals (Total Issue, Stock), SR lifted stock per SKU ("ইস্যু"; total). Screens: S-45 (R/W), S-31 (R), S-51 (count "স্টক").

## E-11 Attendance
Fields: user, date, check-in time (12-hour display), check-out time, location/address string, (lat/long ?), state flags (checked-in, checked-out). Screens: S-08, S-09, S-10.

## E-12 Call activity (Control / Joint)
Fields: AMO, route, retailer, in-range result, manual-override flag (?), override photo, outlet location update, activity type (Control/Joint), call counts feeding KPI tiles (today, total call, control, joint) and monthly targets. Screens: S-06, S-11, S-13, S-14, S-16.

## E-13 SR call assessment (Joint Call)
Fields: AMO, (SR implied), route, retailer, 5-step items 1-3 titles ("Summarise the situation", "ধারণাটি বর্ণনা করুন", "জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা") + items 4-5 (unknown), relationship score, service-quality score; each 1-5. Screens: S-12.

## E-14 SR performance assessment (Control Call)
Fields: AMO, route, retailer, distributed SKU set (15 brand checkboxes), OOS SKU set, POSM yes/no. Screens: S-22.

## E-15 Survey / QC / Promotion / Price compliance
Fields: NOT SHOWN. Only record-type counts in S-51. Screens: S-16 (Survey tile), S-18 (QC button), S-51.

## E-16 Task
Fields: route/section, outlet name, assignee SR (derived), task type ("General Task"; list unknown), completion date, description (free text), status ("চলমান"), created-by AMO. Screens: S-27 (R), S-28 (W).

## E-17 Outlet change requests (SR -> AMO)
Fields: request type (new outlet / closure / info change), route, cluster, retailer name, owner name, contact, pending counts (badges); AMO-added: sub-channel, geo classification; state transitions on Save (no reject shown). Screens: S-32, S-33..S-39.

## E-18 Targets and achievements
- AMO call KPIs: today target/achieved, month total/control/joint target/achieved (S-06).
- Team Performance: monthly and till-date target/achievement per zone, route, category, item (S-25, S-26).
- Astha: STD target/achievement per brand and Memo target/achievement ("All Brand") by route or retailer, year, quarter, month; % and remaining (S-52..S-54).
- Route brand target vs sales + memo count + CPR (S-57, S-58).

## E-19 Live metrics
Fields: total sales count, total taka, target outlets, successful calls, strike rate %, geo validation count, photo validation count, total outlets visited, geo-fencing %, target routes, total logins, login %, logged-in count, total sales deposits, deposit %. Screens: S-29.

## E-20 Sync / sales deposit
Fields: device-vs-server counts for outlet, sales, stock, QC, promotion, distribution and OOS, price compliance, joint call, survey; device online status; sync-complete state; sales deposit submitted; dues count. Screens: S-51.

## E-21 Reports
Fields: STD memo report (date range; per-route cigarette qty, lighter qty, further columns unknown; totals); sales summary up to now (route, CPR, total memo). Screens: S-56, S-57, S-58.

## E-22 Support file
Fields: local sync/data file (also called "সেলস ফাইল", "ডাটা ফাইল", "সিঙ্ক ফাইল"), upload result. Screens: S-61.

### Entity/screen cross-reference (compact)
| Entity | Screens that touch it |
|---|---|
| User/AMO | S-02, S-06, S-08, S-59, S-60, S-62 |
| Outlet/Retailer | S-11, S-13, S-14, S-17-S-20, S-28, S-33-S-44, S-46-S-48, S-53, S-54 |
| SR | S-24, S-28, S-30, S-31 |
| SKU/brand/category | S-17, S-18, S-20, S-22, S-25, S-26, S-31, S-45, S-47, S-50, S-52-S-54, S-58 |
| Sale/memo | S-17-S-20, S-46-S-50 |
| Credit/due | S-18, S-19, S-46-S-48, S-51 |
| Stock | S-31, S-45, S-51 |
| Attendance | S-08-S-10 |
| Call activity | S-06, S-11-S-16 |
| Assessments | S-12, S-22 |
| Task | S-27, S-28 |
| Change requests | S-32-S-39 |
| Targets/achievements | S-06, S-25, S-26, S-52-S-54, S-57, S-58 |
| Live metrics | S-29 |
| Sync/deposit | S-51 |
| Reports | S-55-S-58 |
| Support file | S-61 |


---------------------------------------------------------------------

# F. UNCLEAR / CONFLICTING ITEMS

Priority: H = could change what must be built or break the cutover; M = needs confirming; L = cosmetic/wording. "Verify" says where the answer must come from (live app / Apsis export / AKTCL ops).

| ID | Pri | Item | Pages | Detail / what to verify |
|---|---|---|---|---|
| U-01 | H | **5-step sales call assessment items 4 and 5 are missing** | 23-24 | Only items 1-3 are visible; the form scrolls past items 4-5 between p23 and p24 (verified on the PDF). One of them ends "...করা, এনসিউর করা)". Titles/guidance unknown. The 5-step model is "Summarise the situation -> Describe the concept -> Explain how it works -> ? -> ?". Verify from the live app. |
| U-02 | M | Version numbers | 2, 4, 5, 7 | Login shows 1.0.2, update screen offers 1.0.4, APK file name ends `_v1`, dated 26_11_2025. Plausible 1.0.2 -> 1.0.4 but unconfirmed which build the manual describes. |
| U-03 | L | APK size | 2, 5 | 74.39 MB on p2; installed list shows 92.18 MB (maybe 72.18). |
| U-04 | H | Two different update UIs | 5-6 vs 7; 12 | Path A (English, "Update Available / Download App & Install", APK download via OS installer) vs Path B (Bangla "আপডেট", "অ্যাপ আপডেট হচ্ছে", "প্রসেসিং(৪৬/৮৪)"). Not stated how they relate (APK update vs in-app data/config update; which appears when). Callout on p7 says "শুরু করো" but button is "আপডেট". Unit of 46/84 unknown. For the rebuild this decides whether a force-update gate AND a data-migration step both exist. |
| U-05 | H | Offline login and permission denial | 3, 4 | Not stated whether login works offline after first login, or what happens if location permission is denied (blocked login? blocked screens?). |
| U-06 | M | Dashboard header date meaning | 8, 11, 12, 14, 17, 25... | "<name>AMO, 2025-12-01 / 2025-11-01 / 2026-04-26". Business date? last login? period start? The first two are 1st-of-month. Territory text partly hidden "Sav?r BazarAMO", date "2025-12-0?" on p10. |
| U-07 | M | Red badge counts inconsistent | 8, 9, 10, 12, 14, 37, 47, 48, 51, 53, 55, 63, 64, 74 | Dashboard Outlet badge: ৮ (p8-10), ০ (p12, 14, 37...); hub badges ২/২/২ (p47) with digits illegible elsewhere (look like ২/৩/১ on p63); p48 lists 4 new-shop cards vs badge 2. Dashboard badge appears as a dot with no number on p74/77. Meaning (pending SR requests?) is INFERRED, and whether the dashboard badge = sum of hub badges is unconfirmed. |
| U-08 | M | Menu composition varies per capture | 8 vs 11 vs 12 | p11 lacks Team Performance, SR Stock, Astha; p12+ adds Report. Per-role? per-user config? per-version? Not stated. A rebuild needs the visibility rule. |
| U-09 | L | Spelling/typography variants | many | "কন্ট্রল" vs "কন্ট্রোল"; "লোকেশন" vs "লোকেশান"; "অ্যাপ্লিকেশান" vs "অ্যাপ্লিকেশন"; "Unknow" (p5); "অবশ্যয়" (p4, 22); "আপানারা" (p9, 55); "Audion" (p22); "অন্যথাই" (p15); "করুণ" (p21, 25, 60, 79); "অ্যাসেস্টমেন্ট"/"এসেসম্যন্ট"/"এসেসমেন্ট" (p23-24); "ASSESMENT" (p31); "সংরক্ষণ"/"সংরক্ষন"; "Til Date" (p35); "আ ক্লিক" (p65); "স্টেটাস" vs "স্ট্যাটাস" (p39); "সর্বমোট" vs "সর্ব মোট" (p46); "আশা" for "আসা" (p47...); "অপারেশন"/"অপারেশন্স". Localisation strings must be reviewed with AKTCL before copying. |
| U-10 | M | p21 slide is about Control Call but shows Joint Call | 21 | Title/callout say Control Call; red box is on the Joint Call tile; screen header is "জয়েন্ট কল একটিভিটি". The true Control Call screen is p25. Consequence: the out-of-range panel (Manual Override/Refresh) is shown on the Joint Call screen too. |
| U-11 | H | Geo-range radius and Manual Override policy | 21, 25, 26 | Radius in metres not stated. Not stated: whether Manual Override needs a reason, is flagged/logged/approved/limited per day; what happens on camera "✕" cancel; whether "রিফ্রেশ" re-reads GPS; whether Joint Call also forces override. Tied to CLAUDE.md constraint 5 (anti-spoofing): the manual reveals that the current app lets the AMO override and OVERWRITE the outlet location with a photo - an abuse path. |
| U-12 | M | Dashboard "বিক্রয়" tile vs Control-Call "বিক্রয়" | 10, 26, 28 | p10: Sales tile "to start sales work". The only documented Sales screen is reached via Control Call (route+retailer first, within range or override). Whether the dashboard tile opens the same route/retailer picker is unknown. |
| U-13 | L | KPI tiles and chevron | 8-12 | Values all "0/0"; formula = achieved/target (INFERRED from p11 text); tappability and the "^" chevron behaviour not described. |
| U-14 | M | Sample times contradict the 5 PM rule | 18, 19, 20 | Check-in 04:05 PM, check-out sheet 12:53 PM vs "check-out from 5 PM". Screenshots from test mode, or rule applies to another clock. Clock source (device vs server/Dhaka time) not stated. |
| U-15 | M | Star defaults and untouched ratings | 23-24 | Star 1 appears pre-selected everywhere (item 1 shows 2). Whether 1 is the default or sample data, and whether a form can be saved untouched, is not stated. |
| U-16 | H | SR being assessed is not selected | 23 | Form shows only route and retailer. Which SR gets the score (SR of that route?) unstated. |
| U-17 | L | Assessment form localisation/truncation | 23-24 | Chips "Low"/"উচ্চ" vs legend "High"; title "Summarise the situation" in English; retailer string truncated "Babu Store (1618411-17234127..." (no wrap). |
| U-18 | M | Camera + microphone purpose | 22 | Dialog says "take pictures and record video" and "record audio", but the triggering screen is black/not shown. Is video/audio recorded in calls? Duration, storage, upload unknown. |
| U-19 | L | Alphabet strip rules | 21, 25, 38, 42, 64 | Letters absent differ per screen (p21/25: no L O Q U W X Y; p38: no C F Q). Dynamic by initial letters of the list is INFERRED; case, non-Latin names, behaviour on selection not stated. |
| U-20 | M | Meaning of per-SKU badge and 3 lines on the Sales card | 28 | Blue badge (১/০/১), line1 number (১১০০০/১০০০০/৫৫০, cannot be unit price), line2 "0%", line3 "০". Candidates: stock, pack count, discount %, free qty. Unknown. |
| U-21 | L | Red eye-with-slash icon on Review | 28, 29 | Purpose unknown (hide prices? offline/location indicator?). |
| U-22 | H | What persists a sale | 28-29 | Review has only Print and Product QC (and credit checkbox). Whether Print saves the sale, whether a sale exists if neither is pressed, and where the sale is stored (local vs server) are not stated. Core to idempotent-sync design. |
| U-23 | M | Product QC | 28; 66-68 | Button purpose/screen not shown; "কিউসি" counted on Sales Deposit. |
| U-24 | M | No validation text for credit amount | 29 | Rule "must be less than total"; what happens for empty, 0, negative, >= total is not shown. Typed "100" Latin digits; due shown 48.50 in dialog but "৪৮ টাকা" in label (rounding). "না" behaviour unstated. Label says "বাকি" but callout says "ক্রেডিট". |
| U-25 | L | Date/number formats mixed | 18, 20, 29, 30, 38, 75 | Times "04:05 PM"; dates "November 26, 2025", "April 1, 2026 - April 25, 2026", "2025-11-26", "Completion on: 2025-11-03" - English formats in a Bangla UI. |
| U-26 | L | Sale Data total vs rows | 30 | Rows sum to 20,660.00 but total reads ২০,২৬০.০০ (digit misprint or misread). Total quantity displayed with 2 decimals. Default date unknown; online need unknown; p30 red box on wrong button. |
| U-27 | H | Survey content absent | 27 | No survey form, questions, validation anywhere in the manual. |
| U-28 | H | Photo-based location overwrite | 26, 49, 56, 61, 65 | Which coordinates are stored (AMO GPS), accuracy rules, max distance between photo location and outlet, mock-GPS detection, audit trail: none stated. |
| U-29 | M | SR Perf. checklist semantics | 31-32 | OOS list greyed for unticked SKUs (INFERRED: only distributed SKUs enable OOS); POSM Yes/No are checkboxes (mutual exclusivity unstated); POSM default; SKU list fixed vs server-driven; callout says "SR Per Assessment" and "AKTC". |
| U-30 | L | SR list shows "SR-Kakoli - 1" twice | 33 | Duplicate entry or selected value plus option; the " - 1" suffix unexplained. |
| U-31 | M | "Live" freshness | 33, 39 | No timestamp, refresh interval, or behaviour when SR phone is offline. |
| U-32 | M | Team Performance rendering | 34-36 | Colour thresholds unstated; cap at 100% on cards vs uncapped on details (INFERRED); units unlabelled; bidi numerator ৬,৫০০ vs ৭,৩০০ (89%); zone Till-Date bidi target ৮২,২৬৩ vs three visible items summing 85,237 (other bidi SKUs hidden?); the "Existing Abul Bidi" % ১০৬.২৮% vs 7,250/6,800 = 106.62. |
| U-33 | L | Digit legibility in Team Performance details | 35-36 | Several Till-Date values read "approx." |
| U-34 | M | Task data | 37-38 | Task type list (only "General Task"), mandatory fields, past-date rule, description limits, other statuses (completed? overdue?), other tabs (empty tab track), SR-side display of the task: not stated. Alphabet strip on p38 lacks C, F, Q. |
| U-35 | L | Outlet label suffix "(Diamond)" | 38, 43 | "Sifat St (Diamond)", "Ebraj St (Diamond)" - sub-channel? class? |
| U-36 | M | Task Save validation | 38 | No validation/error text shown. |
| U-37 | M | Live Data ambiguity | 39 | Geo-fencing donut "0.00%" ratio unstated (0/81 vs 0/1); dropdown 1 "Savar BazarAMO" vs 2 "Savar Bazar(Sat, Mon, Wed)" levels; mixed labels; sample shows 38 sales with 1 successful call; Filter is mandatory. |
| U-38 | M | Lifted stock | 41 | Period (day? cumulative?), unit, "ARIS-O-20s" vs "ARIS-A-20s" (p43), EAB/ARIS never in memos; SR-12252 missing, SR-12256 cut off; "মোট আউটলেট" = assigned or visited unclear; list search/pagination unknown. |
| U-39 | H | No reject path for new-outlet verification | 48-50 | Only "সংরক্ষণ" and an undescribed "বাতিল"; mandatory flags for Sub-Channel/Geo Classification unknown; option lists unknown; Cancel icon is a floppy on p49/50/54 and (x) on p52. |
| U-40 | M | Closure verification | 51-52 | Confirm alert text not shown; effect on outlet (deactivated?) not stated; Cancel unstated; contact shown without leading 0; same title "স্থায়ী বন্ধ" for SR-request list and AMO-initiated form. |
| U-41 | M | Stock screen semantics | 9, 14-16 | Whose stock (AMO's own vs SR issue), to whom issued, where Save writes, what Print outputs, issue<=stock limit, purple badge number, digits rendered "০"-style, 5th SKU name cut off. |
| U-42 | M | Outlet hub naming and "আপডেট বেস" | 47, 48, 55 | p47 callout says click "নতুন আউটলেট যাচাইকরণ" but the next page's callout/screen is "নতুন দোকান" (the list screen titled "নতুন দোকান" is the verification list; the bottom-row "নতুন দোকান" tile opens the AMO form S-40). Callout says "three options" while 4 tiles exist. Label "আউটলেট সংশোধনের" truncated. |
| U-43 | M | Two memo layouts | 43 | A: "রুট:" label + category subtotals + credit panel + red printer icon; B: "সেকশনঃ" label, single "মোট" row, no subtotals, green printer. Per outlet type? per version? 1-paisa mismatch (FB 2.33 + SL 1.58 = 3.91 vs 3.92). Also FB shows 2.33 on memo vs 28.00 on p29/p46. |
| U-44 | H | Mark-as-paid is all-or-nothing | 44-45 | No amount, partial settlement, method or receipt; but the sale-time flow accepts partial collection (p29). How later partial collections are recorded is unknown. Whether a collection record is created is unknown. |
| U-45 | H | Edit sale | 43 | Not documented: editable fields, limit after sync/deposit/QC, reason, approval, re-print. |
| U-46 | M | Summary "ফেরত" column | 46 | Meaning (return qty? remaining stock?) unclear; values (৮,৪৯০ etc.) read at low legibility. First SKU heading scrolled off. FB ২৮.০০ here vs ২.৩৩ on memo. No date picker. |
| U-47 | H | No error / failure / offline messages anywhere | all (esp. 66-68, 78) | No text for sync failure, no network, GPS fail, wrong password, permission denied, printer connect failure, save failure, photo failure, session expiry. Offline behaviour is never described except the Sales Deposit "অনলাইন" status. The rebuild must define these (offline-first constraint). |
| U-47a | H | Sales Deposit enabling rule conflict | 67 vs 68 | p67: enabled after sync, dues dialog lets you submit anyway. p68: enabled once all shops' dues are paid AND sync done. See R-092/R-093. Also p67 table has 8 rows (no "মূল্য সম্মতি") with different totals (25 / 11,700) from p66/p68 (118 / 45,150); p66 shows identical device/server counts BEFORE sync. |
| U-48 | H | Record types with no screen | 66-68 | "প্রমোশন", "মূল্য সম্মতি" (price compliance) and "কিউসি" (QC) are counted at deposit but no AMO screen creates them. They may exist in SR/TSO apps or be inactive; verify before dropping. |
| U-49 | M | "STD" and "Astha" never expanded | 10, 69-76 | STD (Standard? sales-to-date?) used in "এসটিডি টার্গেট" and "এসটিডি মেমো রিপোর্ট". Astha is a retailer-programme with tiers (Gold, Diamond); rules for tiers unknown. |
| U-50 | M | Astha Memo target with months | 70, 73 | Table identical with 0 chips and with Nov+Dec selected; 0-chip meaning (whole quarter vs none) unknown; month chips have no visible default; year/quarter option lists unknown (only 2025, Q-4). Memo-target view per outlet vs per route uses the same "All Brand" row. |
| U-51 | M | Astha STD digits and anomalies | 70 | Negative target "-২০" with % "-৩৭,৫০০.০০%" (clipped); last rows cut off; Black Diamond digits small; units unknown. |
| U-52 | M | STD Memo Report numbers | 75 | Cigarette column rows sum to 2,131,000 vs footer ২,০৮২,৯৬০; Motiharpol 151,990 here vs 151,690 on p34. Columns right of "Lighter" never shown. First column header is the AMO's own area name. |
| U-53 | H | "CPR" never defined | 76 | Likely a productivity metric (calls per ... / contribution?). Formula unknown (values 24.55-91.52). |
| U-54 | M | Fractional targets and % mismatches | 76 | Targets with 2 decimals (88,235.29) suggest pro-rating; Maxim - Platinum Series % shows 107.1% vs computed 105.97% (sales may be 3,780); memo counts for Marise/Black Diamond roughly legible; sort order not stated. |
| U-55 | L | Report date ceiling | 74-75 | Dashboard date 2026-04-26 but report range ends April 25, 2026 (capped at yesterday or different days?). |
| U-56 | L | Language persistence | 77-78 | Not stated if remembered after restart/logout. Option labels inconsistent ("en" vs "বাংলা"); callout calls it "En & বাংলা". |
| U-57 | H | Logout with unsynced data | 79 | Not stated: blocked if unsynced? needs internet? destination? local data wiped? |
| U-58 | M | Zone vs Cluster vs Route vs Section | 9, 25, 28, 34, 38, 71 | "Savar Metro" is called Cluster on Review (p28) and Zone on Astha (p71); "Agrabad" is Zone and route; callouts call the first dropdown "সেকশন" though it shows routes. Hierarchy Wing > Division > Territory > Zone shown only on Astha; the AMO's data scope ("Zone", p9) vs team ("টিম") unclear. |
| U-59 | M | Mobile number format and PII | 40-41, 48-53, 58, 71-73 | Leading 0 dropped in some views; SR mobiles masked on p40-41 but retailer contacts fully visible; mobile validation (length/pattern 01XXXXXXXXX) never stated. |
| U-60 | M | Photo/GEO capture feedback | 49-50, 56-57, 60 | After capture no thumbnail/tick/coords shown; unknown if Save is blocked until capture on S-34/S-40 (p60 says capture "must" be done); no retake, preview, compression, size limit, GPS accuracy; photo upload channel not described. |
| U-61 | M | Success wording inconsistent | 24, 32, 38, 50, 52, 54, 57, 59, 61, 62 | "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" vs English "Data Updated Successfully" vs English toast "Save Successfully!" vs "Successful alert". Save message does not say local vs server. |
| U-62 | L | Unreadable/low-legibility regions | 2, 5, 8-10, 35-36, 46, 51, 53, 59, 63, 70, 76 | APK size digits, hidden header text, badge digits, tiny dialog text on p59 (~90%), Till-Date approximations, p46 column digits, p76 small-table digits. |

## F-II. Things the manual is entirely silent about (so the rebuild cannot treat "not in the manual" as "not in the app")
- Offline operation, queued uploads, retry, conflict handling, sync status per record (only the manual device-vs-server table on Sales Deposit).
- Geo-fence radius, GPS accuracy/mock-location handling, location sampling cadence.
- Zero-sale/no-sale reasons, force sale, sale cancel/void/return, discounts entry, price/promo/scheme rules.
- Password/security: forgot/change password, lockout, device binding, session timeout, role switching.
- Notifications and what drives the red badges.
- Data retention on device, local DB encryption, photo handling.
- How AMO scope is derived (Zone/Team) and enforced.
- Audit trails for override, edit, closure, base updates.

