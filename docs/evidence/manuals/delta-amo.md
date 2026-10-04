# Delta: AMO App User Manual versus the Aron spec and plan

Date: 2026-10-04. Manual: "AMO (Area Marketing Officer) App User Manual", 80 pages, Apsis build 1.0.2 (inventory `manual-amo.md`: 62 screens, 116 rules, 90 messages, 37 flows, 22 entities). Compared against: `CLAUDE.md`, `PROJECT-CONTEXT.md`, `docs/01-13` and `docs/22`, `db/schema.sql`, `db/seed`, and the planning lenses `lens-features.md`, `lens-data.md` (with targeted reads of `lens-config.md`, `lens-sync.md`, `lens-security.md` for cfg keys and sync/PII rules).

Not found: `docs/15-feature-inventory.md` and `docs/16-data-platform.md` do not exist in the repo (only `docs/22-apsis-data-profile.md` was added); the plan lenses stand in for them. "Spec" below means docs/01-13 + schema.sql; "plan" means the lens files. An item the plan covers but the repo docs do not is marked COVERED with the plan reference, because the plan is the current intent; the repo docs still need the update.

PDF spot-checks against `178ef52d-AMO_App_User_Manual.pdf` (pp 8-11, 28-29, 34-35, 40-45, 66-68) confirmed: badge on Outlet only; SS user header and 13-tile layout; Edit button grey on both memo layouts; SR phone masked; Western digit grouping; p66 grey and p67/p68 blue deposit button; p67 has 8 table rows. Cross-manual checks used `manual-sr.md` (SR-S-02..09, 10, 12, 19..24, 32..42, 55, 57, 58), `manual-tso.md` (TSO-S-13) and `manual-web.md` (WEB-S-38..40, F-20).

## Headline

- 327 items checked (62 screens, 37 flows, 22 entities, 116 rules, 90 messages): 88 COVERED, 199 PARTIAL, 24 MISSING, 16 CONTRADICTS. The spec/plan is strong on the spine (attendance, calls, sale, memo, targets, Astha, reports) and weak on exact rules, strings and AMO-specific variants.
- 49 gaps issued: 27 major, 22 minor, 0 blocker. No single gap stops a build, but seven contradict the spec directly (Task Delegation badge, override overwrites the outlet location at once, "live" team location, SR phone masking, whole-memo mark-paid, cluster-first AMO outlet forms, no reject path) and four need data only the live app or AKTCL can supply (Joint Call items 4-5, printed memo samples, the sale-card extras, the AMO Survey form).
- The manual contains no error, offline or failure text at all, and no number/date formatting rules; both must be authored (G-44, G-46, G-47).
- The AMO needs an SS designation, a supervisor-level day state (no route), and a zone-wide offline bundle that the SR-centred docs/04 does not describe (G-01, G-39, G-49).
- Cross-manual reading settled several of the inventory's open questions (see section 7): the two update UIs are two stages of one update; mark-paid is whole-memo in both apps; rejection is a web action; Print is the sale commit.

## 1. Gap table (every PARTIAL / MISSING / CONTRADICTS item)

### 1.1 Index

| Gap | Sev | Kind | Title | Screens |
| --- | --- | --- | --- | --- |
| G-man-amo-01 | major | missing-rule | SS role and per-user menu variants | S-06 |
| G-man-amo-02 | minor | contradiction | Dashboard badge: Outlet only, not Task Delegation | S-06, S-32 |
| G-man-amo-03 | minor | underspecified | Dashboard header date, KPI tile definitions, chevron | S-06 |
| G-man-amo-04 | major | underspecified | Dashboard Sale tile versus Control Call > Sale | S-06, S-16, S-17 |
| G-man-amo-05 | major | underspecified | Install and two-stage update flow | S-01, S-04, S-05 |
| G-man-amo-06 | major | missing-rule | Login, device binding, permission denial, offline login, version string | S-02, S-03, S-15 |
| G-man-amo-07 | minor | underspecified | Printer pairing, connection and print-button rules | S-07, S-45, S-47, S-50 |
| G-man-amo-08 | minor | underspecified | Attendance states, strings, gate and clock | S-08, S-09, S-10 |
| G-man-amo-09 | major | new-data | Joint Call 5-step rubric items 4-5 are not in the manual | S-12 |
| G-man-amo-10 | major | missing-rule | Joint Call: assessed SR, default stars, untouched save | S-12 |
| G-man-amo-11 | major | contradiction | Manual Override overwrites the outlet location at once | S-11, S-13, S-14 |
| G-man-amo-12 | minor | missing-rule | SR Performance Assessment: OOS subset, POSM tri-state | S-22 |
| G-man-amo-13 | minor | new-data | AMO Survey screen is not documented | S-23 |
| G-man-amo-14 | major | underspecified | Sale Review: commit point, QC and Print independence | S-18, S-21 |
| G-man-amo-15 | minor | new-data | Sale card extras: badge, three info lines, eye icon | S-17 |
| G-man-amo-16 | major | contradiction | Match quantities shown in pieces on one screen and dozens on another | S-17, S-18, S-47, S-50 |
| G-man-amo-17 | minor | missing-rule | Credit sale: partial-payment validation and display | S-19 |
| G-man-amo-18 | major | new-data | Memo layouts and printed output are never shown | S-18, S-47, S-50, S-45 |
| G-man-amo-19 | major | contradiction | Mark-as-paid is all-or-nothing per memo | S-46, S-47, S-48 |
| G-man-amo-20 | major | underspecified | Whose dues the AMO sees and settles | S-46, S-51 |
| G-man-amo-21 | major | underspecified | Sale edit from Memo: AMO rules and the greyed Edit button | S-47, S-49 |
| G-man-amo-22 | minor | underspecified | Previous sale data ("সেল ডাটা") is online-only and retailer-wide | S-20 |
| G-man-amo-23 | major | contradiction | Team Location is "live" in the manual, last-synced in the spec | S-24 |
| G-man-amo-24 | major | missing-feature | Map and geocoding provider is undecided | S-08, S-24, S-44 |
| G-man-amo-25 | minor | underspecified | Team Performance: drill-down, bands, caps | S-25, S-26 |
| G-man-amo-26 | minor | underspecified | Task Delegation: statuses, tabs, outlet card | S-27, S-28 |
| G-man-amo-27 | minor | underspecified | Live Dashboard: Filter press and the meaning of "মোট বিক্রয়" | S-29 |
| G-man-amo-28 | minor | contradiction | SR Stock: phone masking, SR identifier, lifted-stock period | S-30, S-31 |
| G-man-amo-29 | major | contradiction | Outlet verification has no reject path in the app | S-34, S-37, S-39 |
| G-man-amo-30 | minor | underspecified | Verification lists and forms: fields, mandatory flags, what the AMO sees | S-33, S-34, S-36, S-38, S-39 |
| G-man-amo-31 | major | contradiction | AMO-initiated outlet operations are cluster-first and differ from the SR flows | S-40, S-41, S-42 |
| G-man-amo-32 | minor | underspecified | Photo + GEO capture: requiredness and feedback | S-35, S-14 |
| G-man-amo-33 | major | missing-rule | Update Base: no distance limits, no success message | S-43, S-44 |
| G-man-amo-34 | major | underspecified | AMO Stock screen: columns, meaning, Print enabling | S-45 |
| G-man-amo-35 | minor | underspecified | Summary: "ডিসকাউন্ট এবং অন্যান্য" and the second totals block | S-50 |
| G-man-amo-36 | major | contradiction | Sales Deposit: p67 warns on dues, p68 says it blocks | S-51 |
| G-man-amo-37 | major | underspecified | Sales Deposit reconciliation rows and what each count measures | S-51 |
| G-man-amo-38 | major | online-only | Sales Deposit and update screens: online indicator and failure states | S-51, S-04 |
| G-man-amo-39 | major | underspecified | AMO day state: no route, so no route_day | S-51, S-29 |
| G-man-amo-40 | minor | underspecified | Astha: month chips ignored by the memo target, empty state | S-52, S-53, S-54 |
| G-man-amo-41 | minor | new-data | STD Memo Report: columns, date rules, scrolling | S-56 |
| G-man-amo-42 | minor | underspecified | Sales Summary Up To Now: CPR definition and zero-target % | S-57, S-58 |
| G-man-amo-43 | minor | underspecified | PDA to Support and Logout behaviour | S-59..S-62 |
| G-man-amo-44 | major | missing-rule | Number, date and time formatting contract | all screens |
| G-man-amo-45 | minor | missing-rule | Label formats and mobile-number normalisation | S-11, S-13, S-17, S-28, S-33..S-54 |
| G-man-amo-46 | major | missing-message | App-owned message catalogue and confirm-dialog matrix | all dialogs and toasts |
| G-man-amo-47 | major | missing-message | Offline, error and failure messages do not exist in the manual | all screens |
| G-man-amo-48 | minor | missing-field | Glossary of terms used on screen | S-12, S-25, S-52, S-56..S-58 |
| G-man-amo-49 | major | underspecified | AMO bundle scope: any route of the zone, offline | S-11, S-13, S-28, S-43, S-46 |

### 1.2 Full table

Severity: blocker = cutover parity breaks or a sale cannot complete; major = a daily workflow, report or integrity rule is wrong or undefined; minor = confirm-only or cosmetic. "Manual says" quotes the manual verbatim where the exact words matter. The Items column lists the ledger rows that roll up to the gap.

| Gap | Title | Manual ref (screen, pages) | Manual says | Spec / plan says | Kind | Sev | Fix | Proposed home | Items |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| G-man-amo-01 | SS role and per-user menu variants | S-06 pp 8-12 (V1 p8-10 amo5756; V2 p11 ss344002; V3 p12+ amo5001); U-08 | Three tile layouts. V2 user header "AMO 2 (ss344002)" / "BepariparaSS, 2025-11-01" shows 13 tiles: no Team Performance, no SR Stock, no Astha, no Report. V1 has 16 tiles (no Report), V3 17 (adds "Report"). Nothing says what drives the difference. The username prefix ss (not amo) and the header suffix SS show a non-AMO designation using the AMO app. | docs/07 Home: one 17-tile list; "login = the zone, e.g. amo5001". docs/03 role enum has no ss (route_assignment.role reuses it). Plan: assignment_kind 'ss (sales supervisor)' (lens-data M-14); cfg.app.home_tiles per ROLE/WAVE (lens-config); G-feat-07 treats SS only as a substitute SR on a route. | missing-rule | major | AKTCL to confirm what SS means (Sales Supervisor vs Substitute SR) and that SS logs into the AMO app mode. Add ss (or a designation column) to the role enum and app_user. Replace the fixed tile list with a visibility rule resolved per user: Team Performance and SR Stock need subordinate SRs in scope; Astha needs Astha outlets in scope; Report is gated by app version/flag. Ship it through cfg.app.home_tiles resolved per user, and add a test for the 13-tile V2 set. | docs/03 roles + enum; db/schema.sql role; docs/07 Home; cfg.app.home_tiles; F-AMO-001; G-feat-07 | S-06, R-018, E-01 |
| G-man-amo-02 | Dashboard badge: Outlet only, not Task Delegation | S-06, S-32 pp 8-10, 12, 47, 51, 53, 66, 74 (PDF p8-10 checked); U-07 | The red numeric badge sits on the Outlet tile only (৮ on p8-10, ০ on p12/14/37; a dot with no number on p66/p74). The hub has its own per-type badges (২/২/২ on p47). The Task Delegation tile carries no badge in the AMO app (SR manual SR-S-10 shows the Task Delegation badge, SR only). | docs/07: "Task Delegation (badge = pending SR requests) ... Outlet (badge)". Plan F-AMO-001 repeats it. | contradiction | minor | Remove the Task Delegation badge from the AMO tile set (it belongs to the SR app). Define the Outlet badge = total pending SR verification requests in the AMO's scope (sum of the three hub badges), always shown with its number, refreshed from the bundle/delta with an as-of time. | docs/07 Home; F-AMO-001 | S-06, S-32, R-024 |
| G-man-amo-03 | Dashboard header date, KPI tile definitions, chevron | S-06 pp 8, 11, 12 (R-017, R-019, R-025; U-06, U-13) | Header line 2 is "<territory/point name><role>, <yyyy-mm-dd>" (e.g. "Savar BazarAMO, 2025-12-01", "AMO-AgrabadAMO, 2026-04-26"); the date's meaning is not stated and two captures are the 1st of a month. KPI tiles: "আজকের টার্গেট" = "আজকের দিনের সম্পূর্ণ করা টার্গেট / আজকের দিনের টোটাল টার্গেট"; "টোটাল কল টার্গেট", "কন্ট্রোল কল টার্গেট", "জয়েন্ট কল টার্গেট" = "চলতি মাসের সম্পূর্ণ করা ... / চলতি মাসের ... টার্গেট" (this month done/total). All show 0/0 in every capture. A "^" chevron under the grid collapses the tile panel. | docs/07: header `<Zone>AMO, <date>`; "Bottom KPI tiles (month to date): Today's target (done/total today), Total call target, ..." (internally inconsistent: header says MTD but Today is daily). No definition of done or of Total call; no chevron. Targets origin = G-feat-57. | underspecified | minor | Proposed: header date = current business date (Asia/Dhaka); header names the territory/point, not the zone code. Today = calls done today / today's call target; Total call = all saved AMO outlet visits this month (control + joint) / monthly target; Control = saved control-call visits; Joint = saved joint-call assessments. Tiles are display-only. Add the collapsible tile panel. All proposals to be confirmed against the live app. | docs/07 Home; docs/10 KPI table; F-AMO-002; cfg.target.supervisor_targets | S-06, R-017, R-019, R-025 |
| G-man-amo-04 | Dashboard Sale tile versus Control Call > Sale | S-06, S-16, S-17 pp 10, 25-28; U-12 | Dashboard "বিক্রয়": "বিক্রয় বাটনে ক্লিক করে আপনারা বিক্রয় কাজ শুরু করতে পারবেন।" The only documented sales path is Control Call > route > retailer > in range or Manual Override > tiles বিক্রয় / এসআর পারফ. অ্যাসেসমেন্ট / সার্ভে. | docs/07 lists a Sale tile and also Control Call > Sale. Plan has two features (F-AMO-006 via Control Call, F-AMO-012 standalone Sale tile) and never says the standalone tile is geo-gated or creates a visit. | underspecified | major | State that the Sale tile opens the same route + retailer picker, geo gate and visit(kind=control_call) as Control Call, with Sale preselected, so there is one code path and no un-gated AMO sale. Merge F-AMO-006 and F-AMO-012. Confirm on the live app. | docs/07 Sale; F-AMO-006/012; docs/05 | S-06, S-17, F-07 |
| G-man-amo-05 | Install and two-stage update flow | S-01, S-04, S-05 pp 2-7; U-02, U-04 (cross-check SR manual SR-S-06..09) | Side-loaded APK "aron_amo_app_26_11_2025_v1.apk" (74.39 MB) from the file manager; "ARON SR" and "ARON AMO" are separate apps on the same phone (p5). Stage 1 (English): "Update Available / Version 1.0.4 available / Download App & Install", back arrow, "Network Status ● অনলাইন", the app sends the user to OS "Install unknown apps", "Downloading 53%", "Please do not close the app while downloading", OS "Do you want to update this app?". Stage 2 (Bangla, blocking): "কিছু নতুন আপডেট পাওয়া গেছে. এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" button "আপডেট"; "অ্যাপ আপডেট হচ্ছে" with "প্রসেসিং(৪৬/৮৪)" and "টিপস: অ্যাপটি বন্ধ করবেন না". The SR manual shows these are sequential stages of one update: after the APK install comes local data re-processing ("প্রসেসিং(১৪/৮৭)") matching the release note "Sync file update without deleting". | docs/06: "new update available -> download & install ... keep an update-check endpoint and a lightweight in-app updater". Plan F-SYS-020: min_version forces, Wi-Fi-preferred, waves; cfg.release.update_prompt_policy (silent/prompt/force_after_date). Not covered: REQUEST_INSTALL_PACKAGES / "Install unknown apps" redirect; progress and do-not-close UX; the second local-migration stage; a forced gate versus an offline selling day; co-install of per-role apps. | underspecified | major | (1) Updater handles the Install-unknown-apps per-app permission and deep-links to the OS page; this self-install is not allowed on a Play-distributed build, and docs/02 mentions a Play track, so decide the channel. (2) Specify two stages: APK download/install, then an on-device migration with determinate n/m progress that preserves all pending (unsynced) rows. (3) A hard gate (cfg.release.min_version) must not strand an offline selling day: let the open route finish and enforce at the next online start. (4) Progress %, do-not-close warning, resume after kill. (5) Record the per-role-APK versus one-app decision: handsets carry ARON SR and ARON AMO side by side (p5) and the pilot adds a third package id (docs/11). | docs/06 Setup; docs/13 Q1; F-SYS-020, F-SYS-044; cfg.release.*; DECISIONS.md | S-01, S-04, S-05, F-01, F-02, F-03, R-001, R-006, R-008, R-010, R-011 + 8 messages |
| G-man-amo-06 | Login, device binding, permission denial, offline login, version string | S-02, S-03, S-15 pp 4, 22; U-05, U-18 (cross-check SR manual SR-S-02..04) | Login: "ইউজারনেম", "পাসওয়ার্ড", "লগইন"; footer "AMO App / (version - 1.0.2) / Developed by Apsis Solutions"; no forgot-password, remember-me or language toggle; no OTP step is shown. The location prompt follows the Login tap: "অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যয় ইউজারকে লোকেশন পারমিশন দিতে হবে।" Camera prompt text is "Allow ARON AMO to take pictures and record video?" plus a microphone prompt (p22) but no recording UI exists. No text for denial, wrong password or offline login. The SR manual shows a TSO-issued 4-digit OTP on a new device and after installing a new version, plus "Login time 2FA verification" (v1.0.25). | docs/06: location (while using), camera, microphone (confirm), Bluetooth; Q15 open; device bound once via TSO OTP (F-SYS-003 lists SR and AMO). Plan F-SYS-001 (offline re-entry ASSUMPTION), F-SYS-023, G-feat-34. Absent: permission-denied path, OTP after each new version, app version on login, vendor footer. | missing-rule | major | Location "While using the app" is mandatory: on denial show a blocking explanation with an Open-settings button and disable check-in, calls and sales while reading screens stay usable. Allow offline re-login with a cached session (cfg.auth.offline_session_max_days). Decide whether the AMO app binds with the TSO OTP and whether a new app version re-triggers it (SR manual says yes; that would add 8,500+ OTP requests per release). Show the app version on login and Settings; replace the Apsis credit with AKTCL branding. Evidence for Q15: only a camera preview, no recording screen, so request camera only (no RECORD_AUDIO) unless AKTCL confirms voice recording. | docs/06 Setup; docs/13 Q4/Q15; F-SYS-001/003/023; G-feat-34 | S-02, S-03, F-01, R-004, R-005 + 1 messages |
| G-man-amo-07 | Printer pairing, connection and print-button rules | S-07, S-45, S-47, S-50 pp 12-16, 43, 46 | Pair in OS Bluetooth settings: tap "RPP02N", PIN "0000" (dialog also says "Try 0000 or 1234"), "Pair". In-app: Stock tile > OS nearby-devices permission ("অন্যথাই প্রিন্টার Connect হবে না।") > tap the printer icon (red-slashed = disconnected) > banner "প্রিন্টার কানেক্ট করা হয়েছে" > icon turns green. The same icon is on the Memo and Summary headers. The Stock "প্রিন্ট" button stays grey after connecting (p16). | docs/06: pair/connect RPP02N-class 58 mm; "Connect from Stock screen; show connection state". Plan F-SR-013 auto-reconnect; G-feat-43 failure path. Absent: default PIN 0000/1234, in-app pairing, status icon on Memo/Summary, Print enable rule, permission-denied message. | underspecified | minor | Offer an in-app pairing wizard (bonded list, discover RPP02N, try PIN 0000 then 1234) so reps need not use OS settings. Show the printer-status icon (tap to reconnect) on Stock, Memo detail, Summary and every Print screen. Define Print enabled = printer connected AND data saved. Add the Bluetooth-permission-denied message. | docs/06 Day flow step 2; F-SR-013; G-feat-43; cfg.print.* | S-07, S-45, F-04, R-013, R-016 + 1 messages |
| G-man-amo-08 | Attendance states, strings, gate and clock | S-08, S-09, S-10 pp 17-20; U-14 (cross-check SR manual SR-S-12..14) | Four states with exact strings: "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।"; waiting tile "চেক আউট ৫টার পরে সক্রিয় হবে" plus note "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।"; done "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" Sheets show a read-only time chip: 04:05 PM (check-in) and 12:53 PM (check-out sample, which contradicts the 5 PM rule; the SR manual shows 05:00 PM). Address card shows the current location with a refresh icon. | docs/07 Attendance: reverse-geocoded address + refresh, press-and-hold, check-out from 5 pm, "Already Checked-In/Out". Plan: cfg.day.checkout_earliest_time; G-feat-18 (clock). Absent: the four state texts; whether check-in gates other tiles (message says "before starting work"); hold duration; which clock fills the time chip. | underspecified | minor | Add the state/message table to the l10n seed. Decide the check-in gate (recommend a soft prompt that never blocks selling offline). Make hold duration config (default about 1 s). Time chip = device time, stored with the server-skew flag (G-feat-18). | docs/07 Attendance; F-AMO-003; cfg.day.*; cfg.app.hold_ms (new) | S-08, S-09, S-10, F-05, F-06, R-026 + 8 messages |
| G-man-amo-09 | Joint Call 5-step rubric items 4-5 are not in the manual | S-12 pp 23-24 (PDF re-checked); U-01, U-17 | "৫ ধাপ সেলস কল অ্যাসেস্টমেন্ট" shows only 3 items: 1 "Summarise the situation" (যা দেখতে হবে: OHS কাউন্ট, OOS, Product quality চেক, অপর্চুনিটি এবং ইস্যু বুঝা); 2 "ধারণাটি বর্ণনা করুন" (SOQ রিকুয়ারম্যান্ট ঠিক মতন বুঝানো, অফার টি তুলে ধরা, ক্যাম্পেইন কমিউনিকেশন); 3 "জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা" (অফার এর মডালিটি ঠিক মতন বুঝানো, দোকানদারের ব্যবসায়িক বেনিফিট হাইলাইট করা). Items 4-5 are not visible anywhere (p23 ends mid-item 3; p24 starts with "...করা, এনসিউর করা)"). The Relationship and Service-quality sections have guidance only. Chips are "Low" / "উচ্চ". Not found in the SR, TSO or Web manuals either. | docs/07: items 1-3 paraphrased, "(steps 4-5 ...)". Plan: G-feat-56 (minor), cfg.rubric.joint_call, assessment_criterion. | new-data | major | The manual cannot supply items 4-5. Obtain them from the live app screenshots the sponsor is sharing or from the Apsis rubric config. Until then ship the rubric as versioned data: 3 known items with verbatim Bangla guidance plus 2 disabled placeholders, so adding them is a data change, not a release. Raise G-feat-56 from minor to major. | docs/07 Joint Call; G-feat-56; assessment_rubric seed; DECISIONS.md | S-12, F-14, E-13 |
| G-man-amo-10 | Joint Call: assessed SR, default stars, untouched save | S-12 pp 23-24; U-15, U-16 | The header card shows only "রুট:" and "খুচরা বিক্রেতাঃ"; no SR is selected. Star 1 is pre-selected on every item in all captures (item 1 shows two). Save is always available. Success "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" returns to the Joint Call screen with route and retailer still selected. | docs/03 call_assessment.sr_id exists; docs/07 and the plan give no rule for who the SR is, default scores or required ratings. | missing-rule | major | Derive the assessed SR = the route's active route_assignment on the business date (acting user if cover/SS), show it read-only in the form header and record sr_id. Default rating: either replicate "1 pre-selected" for parity or leave unrated and require all items (cfg.rubric.require_all_rated); log the chosen behaviour as a deliberate change if it differs from Apsis. | docs/07 Joint Call; docs/03 call_assessment; cfg.rubric.* | S-12, F-14, R-040, E-13 + 1 messages |
| G-man-amo-11 | Manual Override overwrites the outlet location at once | S-11, S-13, S-14 pp 21, 25-26 (also S-43/S-44 pp 64-65); U-11, U-28 (cross-check SR-S-22, SR-S-24) | Out-of-range panel "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" with "ম্যানুয়াল ওভাররাইড" and "রিফ্রেশ" (also on the Joint Call screen). The override opens the camera, one photo: "ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" i.e. the stored outlet location is overwritten immediately, with no reason picker (the SR app asks internet_problem / location_change), no approval, no daily limit; the "✕" cancel outcome is undocumented; radius not stated. The SR manual shows the same photo-overwrites-location behaviour for Force Sale. | docs/05: the photo-corrected location is "routed through the normal outlet-change/verification flow so it can't be abused silently"; docs/07: override = outlet photo "which updates location". Plan F-AMO-005 (web approval, ASSUMPTION), cfg.geo.outlet_location_change_approval. | contradiction | major | Record it as a DELIBERATE change in docs/13: the override raises a location-change request (purpose manual_override); the call proceeds (photo_validated true, geo_validated false) and is flagged for the TSO, but the stored location does not change until approved. Also decide: a reason picker for the AMO (parity with the SR) or none; a per-AMO per-day limit (cfg.geo.override_max_per_day); "✕" = abort, stay on the picker, no visit; a supervisor-visible AMO override count. Document the side effect: while approval is pending every repeat visit to that outlet is out of range again. | docs/05; docs/07 Control Call; docs/13 "deliberately changed"; cfg.geo.* | S-11, S-13, S-14, F-08, R-036 |
| G-man-amo-12 | SR Performance Assessment: OOS subset, POSM tri-state | S-22 pp 31-32; U-29 | "অ্যাসেসমেন্ট মুহূর্তে এস.কে.ইউ দোকানে থাকলে অথবা OOS হয়ে গেলেও টিক চিহ্ন দিন।" (15 checkboxes, 3 columns, fixed order Maxim ... Salmon); OOS section "OOS থাকলে টিক চিহ্ন দিন।" with unticked brands greyed (inferred); POSM "হ্যাঁ" / "না" checkboxes (default unchecked); after Save the dialog returns to Control Call with the retailer cleared and the route kept. | docs/07 lists the same 15 brands in the same order (matches); docs/03 distribution_check has present/oos/posm per brand. Plan M-26 (header + lines, POSM per visit - the manual confirms POSM is per visit). Absent: OOS must be a subset of distributed; POSM unanswered/yes/no and mutual exclusion; return behaviour; brand-list source (callouts say SKU, grid shows brands). | missing-rule | minor | Rule oos implies present (UI enables an OOS tick only for ticked brands; add a DB CHECK). POSM = unanswered / yes / no, yes and no mutually exclusive. Brand grid from the product tree flag in_distribution_check (cfg.rubric.distribution_brands) in the manual's order. After Save return to the outlet picker with the route kept. | docs/07 SR Perf. Assessment; docs/03 distribution_check; plan M-26 | S-22, F-15, R-055, R-057 + 2 messages |
| G-man-amo-13 | AMO Survey screen is not documented | S-23 (and S-16) p 27; U-27 (cross-check SR-S-31) | Control Call tile "সার্ভে": "নির্দিষ্ট আউটলেটে সার্ভে করার জন্য সার্ভে বাটনে ক্লিক করুন।" The form is never shown. "সার্ভে" is a counted record type on the AMO Sales Deposit table, while the SR table lists only outlet/sale/stock/QC/promotion. The SR manual documents a "POSM Availability" survey (Q1, Q1.1 photo, shown for specific outlets only, confirm "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?", +50 points). | docs/07: "Survey" only as a menu item; plan F-AMO-010 assumes the same POSM survey as the SR (G-feat-12: question set absent). | new-data | minor | Probably the SR POSM survey reused for the AMO, but not shown; capture the AMO screen from the live app before building. Model with a survey_id, reuse the SR component, count it in the AMO reconciliation row "সার্ভে", and keep the tile feature-flagged until confirmed (the loyalty +50 points reward must not apply to AMO). | docs/07 Control Call; G-feat-12; plan M-09; cfg.survey.* | S-23, F-16, E-15 |
| G-man-amo-14 | Sale Review: commit point, QC and Print independence | S-18, S-21 pp 28-29; U-22, U-23 (cross-check SR-S-33..38) | Review "নিরীক্ষণ" has no Save button: checkbox "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন", orange "প্রোডাক্ট QC" and green "প্রিন্ট" side by side, info "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" The QC screen is never shown. The SR manual shows Print is the commit: confirm "আপনি কি নিশ্চিত? বিক্রয় জমা হবে", then "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?", then "বিক্রয় সফল ভাবে জমা হয়েছে"; the SR memo subtracts "মোট QC" before "সর্বমোট"; a zero sale has its own confirm "আপনি কি জিরো (০) বিক্রয় করতে চান?" (not documented for the AMO). | docs/06 step 4d: "review -> optional credit -> Product QC -> print memo" (QC as a step before print; QC locks edits). Plan F-SR-025 "before commit". Not stated: the moment the memo and visit rows are first persisted locally. | underspecified | major | Specify: the draft memo is written to the local DB (state pending, own client_uuid, visit client_uuid) the moment the user taps "এগিয়ে যান" so a kill-and-relaunch resumes at Review; it becomes final on Print (with the two SR confirm dialogs) or on leaving Review; QC and Print are independent buttons (QC optional, completing QC locks edit); a memo with no print has printed_at NULL and still counts. Define the QC settlement line that reduces the payable. State whether the AMO has zero-sale. This is the DoD kill-and-relaunch test. | docs/06 step 4d; docs/04; F-SR-025/027/028/029; plan lens-sync outbox | S-18, F-07, F-37, R-053, E-08 |
| G-man-amo-15 | Sale card extras: badge, three info lines, eye icon | S-17 p 28; U-20, U-21 | Each SKU card: pack image with a blue circular badge (১/০/১), SKU name, −/qty/+ stepper (typing allowed), three right-hand lines (line 1 e.g. ১১০০০ / ১০০০০ / ৫৫০, "0%", "০"); a sticky bar with cart icon and running total "১১৬.৪২" and "এগিয়ে যান →"; Review has a red eye-with-slash icon; "পূর্বের সেল ডাটা দেখুন" on top. | docs/06/07: "SKU quantities" only; no card layout. Plan F-SR-023 mentions the suggested quantity. | new-data | minor | Meaning unknown. Hypotheses to verify on the live app: line 1 = current stock in sticks (Avon-20S ১১০০০ equals the SR lifted-stock figure on p41), "0%" = discount %, "০" = free/offer quantity, badge = quantity in cart or last-sale packs, eye-slash = hide prices. Build as optional columns driven by what is confirmed; add the running-total bar. | docs/06 Sale; F-SR-023/025 | S-17, F-07, R-045 |
| G-man-amo-16 | Match quantities shown in pieces on one screen and dozens on another | S-17, S-18, S-47, S-50 pp 28-29, 43, 46; U-43 (cross-check SR-S-33) | Entry is in sticks ("শলাকার পরিমাণ উল্লেখ করুন"). Match lines differ by screen: FB qty 1 = 2.33 and SL qty 1 = 1.58 (price/12, per piece) on p28/p43, but FB 1 = 28.00 and SL 1 = 19.00 (per dozen) on p29/p46; the SR manual shows SL ১২ = ১৯.০০ (12 pieces). MaxR-10S 10 = 80.00 (8.00 per stick) against the seed catalog outlet price 9.20: the price list changed. | docs/13 Q8 (units) open; docs/03 sku.unit default stick; plan D-02 (qty_entered + pack_factor), effective-dated sku_price, G-data-04 (blocker). | contradiction | major | Treat as hard evidence for Q8: the same match SKU is per piece on one screen and per dozen on another. Fix base unit per category (stick / piece), store qty_base plus entered unit, print the unit on the memo, never show a bare number for FB/SL/Aster without a unit label. Golden tests: the memo on p43 and the summary on p46 must reproduce to the paisa. | docs/13 Q8; docs/03 sku.unit; plan D-02; G-data-04 | S-17, S-18, R-044, E-07 |
| G-man-amo-17 | Credit sale: partial-payment validation and display | S-19 p 29; U-24 (cross-check SR-S-34) | Dialog "পরিশোধিত টাকার পরিমাণ লিখুন" / "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?"; field "আদায়কৃত অর্থ"; live suffix "বাকিঃ ৪৮.৫০" (2 decimals); "এই পরিমাণটি অবশ্যই সর্বমোট টাকার পরিমাণ এর থেকে কম হতে হবে।"; buttons "হ্যাঁ" / "না"; afterwards the checkbox reads "Baki ৪৮ টাকা" (whole taka). | docs/06: "optional credit (বাকি) with partial payment"; plan cfg.credit.partial_payment_min_pct. No rule text, no error behaviour, no rounding rule. | missing-rule | minor | Validation: amount >= 0 (0 allowed = full credit), strictly less than net, numeric with at most 2 decimals; show the due with 2 decimals everywhere (the label too, not 48); "না" closes and leaves the checkbox unticked; Bangla error strings. | docs/06 step 4d; F-SR-026; cfg.credit.* | S-19, F-09, R-049, R-050 + 6 messages |
| G-man-amo-18 | Memo layouts and printed output are never shown | S-18, S-47, S-50, S-45 pp 28-29, 43-46; U-43 (cross-check SR-S-33, SR-S-39) | Two on-screen memo layouts: A (credit) "রুট:", category subtotals মোট সিগারেট/বিড়ি/লাইটার/ম্যাচ, "মোট ডিসকাউন্ট", "সর্বমোট", credit panel, red printer icon; B (cash) "সেকশনঃ", one "মোট" row, no subtotals, green printer icon. Column "মূল্য" (review) versus "দাম" (memo). The SR memo shows "মোট QC" where the AMO memo shows "মোট ডিসকাউন্ট". The screen has no memo number, date/time, seller, retailer address or phone. Review header "ক্লাস্টার: Savar Metro" is the zone name. No printed memo, stock slip or summary is shown anywhere. | docs/01 and docs/11: "same memo" (layout and totals). Plan: cfg.memo.*, golden print test (lens-sync), G-feat-60 (summary layout). No sample exists. | new-data | major | Collect physical 58 mm printed samples from AKTCL (cash memo, credit memo with partial payment, zero sale, edited memo, reprint, stock slip, day summary) and encode them as the template contract with a golden-print test. Decide whether the two on-screen layouts are two templates or one (credit vs cash); fix which field prints under "Cluster"; record the QC/discount deduction line label. | docs/01; docs/11; F-SR-028; G-feat-60; cfg.memo.*; cfg.print.template_version | S-18, S-47, R-047, E-08 |
| G-man-amo-19 | Mark-as-paid is all-or-nothing per memo | S-46, S-47, S-48 pp 42-45; U-44 (cross-check SR-S-39, SR-S-40) | A credit memo shows "এই বিক্রয়টি বাকিতে করা হয়েছে।" and a single "পরিশোধিত করুন" (no amount, no partial, no payment mode, no receipt) > "আপনি কি নিশ্চিত?" > "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" > the outlet's due label disappears. The SR manual shows the same whole-memo settlement. | docs/06 Due collection: "mark paid (full or partial)"; docs/03 due_collection(amount); plan M-12 is_full_settlement, payment_mode; G-feat-45. | contradiction | major | Both manuals show full settlement of one memo only. Decide: keep full-only in both apps (parity) or add an amount field (improvement). Either way write a due_collection row (client_uuid, against_memo_id, amount = remaining) so the action is idempotent and auditable, and define which memo is settled when an outlet has several. | docs/06 step 5; docs/07 Memo; F-AMO-014; plan M-12 | S-46, S-47, S-48, F-13, R-076, E-09 + 3 messages |
| G-man-amo-20 | Whose dues the AMO sees and settles | S-46, S-51 pp 42, 44, 67 | The outlet dropdown shows ": ১১৬.৪২ ৳ বাকি" (green) beside outlets with outstanding credit; the deposit dialog counts retailers who still owe ("১টি রিটেইলারের কাছে বাকি"). Not stated whether the AMO sees only credit memos he created, or any SR's credit for that retailer, or what "your route" means for an AMO. | docs/06/07: warn on dues. Plan G-feat-45 (allocation) has no scope rule for supervisors. | underspecified | major | Rule: an AMO's due label and deposit-time dues count cover dues from the AMO's own credit memos only; SR credit stays with the SR (avoids cash-handling ambiguity). Show the label with 2 decimals on the Memo, Control Call and Update pickers. If AKTCL wants AMO-collects-SR-dues, record collector_id on due_collection. | docs/06 step 5/7; docs/07; G-feat-45; F-AMO-014/030 | S-46, R-073, E-09 + 1 messages |
| G-man-amo-21 | Sale edit from Memo: AMO rules and the greyed Edit button | S-47, S-49 p 43-44 (PDF p43-44 checked); U-45 (cross-check SR-S-39, SR-S-41) | "এডিট" (pencil) sits beside "প্রিন্ট"; it is light grey on both AMO memo layouts (p43 left and right, p44) whereas the SR manual shows it yellow and enabled; the edit screen, editable fields, limits and reason dialog are never shown for the AMO. SR rules: inside the outlet geofence, not after QC, one of 3 reasons, dropdown "ভুল SKU নির্বাচিত।", then re-enter. | docs/06 Sale edit: geofence, pre-QC, 3 reasons, new memo supersedes old; docs/07 only lists "Edit". | underspecified | major | Apply the SR edit rules to the AMO and write the reason list (config). Decide why the AMO button is grey (outside geofence, role not allowed, or a disabled-until-in-range state) and test it. An edit creates a superseding memo row and adjusts the outlet due. | docs/06 step 6; F-SR-033; F-AMO-014; cfg.memo.edit_* | S-47, S-49, F-12, R-075 |
| G-man-amo-22 | Previous sale data ("সেল ডাটা") is online-only and retailer-wide | S-20 p 30; U-26 (cross-check SR-S-20) | Date field (English "November 26, 2025", echo 2025-11-26), table SKU/পরিমাণ/মূল্য with thumbnails, total band with quantity to 2 decimals (4,570.00; the sample total misprints, rows sum to 20,660). The sample quantities (520 sticks) are retailer-wide, not one seller's. The SR manual adds the note that mobile data must be on, i.e. online-only. | docs/07: "View previous sale data with a date picker"; plan F-AMO-013 (local history days, else online). | underspecified | minor | Define: per-retailer, per-date SKU aggregate across all memos for that outlet within the AMO's scope (server read, last 7 days cached), default date = last sale date, integer quantities, totals computed server-side; show an offline message when the date is outside the cache. | docs/07 Sale; F-AMO-013; F-API-025 | S-20, F-10, R-052 |
| G-man-amo-23 | Team Location is "live" in the manual, last-synced in the spec | S-24 p 33; U-30, U-31 (cross-check TSO-S-13) | "আপনার টিমের SR দের লাইভ লোকেশান দেখার জন্য টিম লোকেশান বাটনে ক্লিক করুন।" Single-select SR dropdown ("SR-Kakoli - 1"), Google Map with a rider marker and name; no timestamp. The TSO manual calls its My Team map "live" too. | docs/07: "live locations ... (Uses the SRs' last synced fixes; not continuous tracking - battery)". Plan G-feat-36 (age label), cfg.tso.team_location_max_age_min. | contradiction | major | Keep the last-synced fix (constraint 3) but state it honestly: show "last seen HH:MM (n min ago)" and the source (check-in / visit / sync); record it in docs/13 "deliberately changed" so AKTCL signs off the loss of true live; provide a list fallback when offline or the map is unavailable; optionally a bounded on-demand ping answered on the SR's next sync. | docs/07 Team; docs/13; F-AMO-016; F-API-023 | S-24, F-17, R-022 |
| G-man-amo-24 | Map and geocoding provider is undecided | S-08, S-24, S-44 pp 17, 33, 65 | Google Maps (compass, my-location, directions, POI labels) in Team Location and Update Base; Attendance shows a reverse-geocoded address ("...Gulshan, Dhaka District, Dhaka Division, 1213, Bangladesh"). | docs/07: "on a map". Plan F-AMO-028 "map tiles (cached?)". No provider, key, cost, offline or data-budget decision anywhere. | missing-feature | major | New decision in docs/13: map + geocoder provider (Google Maps SDK and Geocoding as today, or MapLibre/OSM with self-hosted tiles); quota and cost for 1,051 AMOs plus TSOs; offline behaviour (Update Base without tiles: accept the current fix and let the user adjust numerically); Attendance address degrades to coordinates offline; map traffic counted in the 2 GB budget. | docs/02; docs/13 (new Q); docs/04; F-AMO-003/016/028 | S-08, S-24, S-44 |
| G-man-amo-25 | Team Performance: drill-down, bands, caps | S-25, S-26 pp 34-36 (PDF p34-35 checked); U-32 | "Monthly Target" (default) / "Till Date Target"; zone card and route cards with 4 category bars, % in red/yellow/green (thresholds unstated; cards cap at 100%, details do not, e.g. 202.49%), each card "বিস্তারিত →"; tapping a category opens details for that category only ("যেমন শুধু বিড়ি এর তথ্য দেখতে বিড়ি এর উপর ক্লিক করুন"); details table Item/টার্গেট/অর্জন/বাকি/% with variant-level items ("Marise Special Blend"); বাকি = max(target - achievement, 0); achievement is identical in both modes. | docs/07: tabs, zone + route cards, 4 bars (green/amber/red), Details > brand table. docs/10 bands >=100 / 90-100 / 80-90 / <80 (four bands). Plan cfg.kpi.bands, cfg.target.achievement_pct_cap, cfg.kpi.tilldate_basis. Absent: category drill-down, 3-colour thresholds, cap rule, remaining clamp, item granularity. | underspecified | minor | Add the category-tap drill-down. Set the bar bands as a separate key from the 4-band KPI set (observed: 97% green, 70% and 56% yellow, 13-14% red; propose red <50, yellow 50-<90, green >=90, to confirm). Cap % at 100 on cards, uncapped (2 dp) in details; clamp remaining at 0; item = variant. | docs/07 Team Performance; docs/10 bands; cfg.kpi.bands (+ cfg.kpi.bar_bands new) | S-25, S-26, F-18, R-060, R-061, R-063 |
| G-man-amo-26 | Task Delegation: statuses, tabs, outlet card | S-27, S-28 pp 37-38; U-34, U-36 (cross-check SR-S-57) | List tab "Assigned Tasks" (an empty grey track beside it suggests more tabs); cards: outlet name + status "চলমান" (Ongoing) + description + "Completion on: 2025-11-03". Form: route/section, outlet (label "Name (suffix)"), alphabet strip, read-only card (আউটলেটের নাম, মালিকের নাম, এসআর নাম, এসআর মোবাইল নম্বর - blank), "টাস্ক টাইপ" (default "General Task"), "টাস্ক কমপ্লিট করার ডেট", description, "টাস্ক বরাদ্দ করুন"; toast "Save Successfully!". Only tasks assigned by this AMO are listed. SR manual: statuses "চলমান" and "সম্পন্ন" (swipe Resolve), type tag "OOS", free-text description naming the brand. | docs/07: Assigned Tasks list + assign. docs/03 task.status default 'pending', enum oos/general/irregular_visit. Plan M-25 task_event, cfg.task.types, F-AMO-019 (assignee derivation ASSUMPTION). | underspecified | minor | Map internal pending to the display label "চলমান" and completed to "সম্পন্ন" (plus overdue/cancelled labels if used) so the AMO sees SR resolution on his list; confirm the second tab; make description length and the past-date rule config; show assignee SR name and masked mobile (see the SR Stock gap). | docs/07 Task Delegation; docs/03 task; cfg.task.* (+ cfg.task.statuses, description_max_len new) | S-27, S-28, F-19, R-067, E-16 + 3 messages |
| G-man-amo-27 | Live Dashboard: Filter press and the meaning of "মোট বিক্রয়" | S-29 p 39; U-37 | Two dropdowns ("Savar BazarAMO", "Savar Bazar(Sat, Mon, Wed)") and a "ফিল্টার" button; nothing loads until it is pressed. Tiles: বিক্রয় (মোট বিক্রয় 38, মোট টাকা 161.50); লাইভ স্ট্রাইক রেট (টার্গেট আউটলেট 81, Successful Calls 1, 1.23%); জিও ফেন্সিং স্ট্যাটাস (81, জিও ভ্যালিডেশন 0, ছবি ভ্যালিডেশন 1, মোট আউটলেট ভ্রমণ 1, 0.00%); লগইন & বিক্রয় জমা স্টেটাস (100% / 0%). | docs/07: "Sales (total memos, total taka)"; strike rate, geo % and login/submit % formulas match the manual's numbers (1/81 = 1.23%; 1/1 login; 0/1 deposit). | underspecified | minor | Keep "select then Filter" as an explicit load (saves data). Define "মোট বিক্রয়": the sample 38 equals the quantity total of the p28 sale (সর্বমোট ৩৮), so it may be quantity, not memo count: verify and label it. Dropdown 1 = zone/AMO level, dropdown 2 = route; geo % = geo-valid / total visited (0/1 = 0.00% is consistent). | docs/07 Live Dashboard; F-AMO-020; docs/10 | S-29, F-20, R-068, R-069, E-19 |
| G-man-amo-28 | SR Stock: phone masking, SR identifier, lifted-stock period | S-30, S-31 pp 40-41 (PDF checked); U-38, U-59 | SR list rows: "SR-12237", route label "SenbagDaily", phone masked "***********", "মোট আউটলেট ৭৮". Lifted stock: SKU / "ইস্যু" plus "মোট" summed across SKU units; no date selector, no search. The SR appears as "SR-<5 digits>" here, "SR-Kakoli - 1" on Team Location and Task, and sr334001 in docs/06. | docs/07: "SR list (code, route, phone, total outlets)". Plan lens-security: app_user.phone visible to "supervisors in scope"; cfg.pii.mask_style hide/last4; app_user.employee_code (M-19). | contradiction | minor | Adopt the manual: the AMO view masks SR phones (cfg.pii.mask_style=hide for SR phones) and correct the lens-security row. Define lifted stock = sum of today's issue movements per SKU for the SR's route (add an optional date picker) and drop the mixed-unit grand total or show it per category. Define the display-name rule (employee_code vs username vs name). | docs/07 SR Stock; lens-security PII table; plan M-19; F-AMO-021 | S-30, S-31, F-21, R-070, R-071, E-01, E-06 |
| G-man-amo-29 | Outlet verification has no reject path in the app | S-34, S-37, S-39 pp 48-54; U-39, U-40 (cross-check WEB-S-39, WEB F-20) | The verification forms have "বাতিল" (red) and "সংরক্ষণ" (green); "বাতিল" is never described (icon is a floppy on p49/50/54, an x-in-circle on p52). No reject action or reason field. Closure and info-change saves show a confirm alert (text not shown), then "Data Updated Successfully". The Web manual shows the lifecycle Pending > Verify > Verified > Approve or Reject (Reject and Approve only on Verified rows), for New, Close and Info types. | docs/07: "Cancel (reject) \| Save (approve)". Plan M-15 rejection_reason; F-SR-040 (rejection reason shown, ASSUMPTION). | contradiction | major | Align the state machine with the Web manual: the AMO app's Save = Verify (Pending to Verified); Reject exists only at the web step; "বাতিল" discards the form with no state change. If AKTCL wants AMO rejection, add it as a distinct, reasoned action and show the rejected state to the SR. Confirm what "বাতিল" does on the live app before cutover. | docs/07 Outlet; docs/03 outlet_change_request/request_status; F-AMO-022..024; F-SR-040 | S-34, S-37, S-39, F-22, F-23, F-24, R-083, R-084, E-17 + 1 messages |
| G-man-amo-30 | Verification lists and forms: fields, mandatory flags, what the AMO sees | S-33, S-34, S-36, S-38, S-39 pp 48-54; U-59 | Request cards show 5 fields (রুট, ক্লাস্টার, খুচরা বিক্রেতার নাম, মালিকের নাম, যোগাযোগ - phone unmasked, leading 0 inconsistent). New-outlet form: route and cluster dropdowns pre-filled, text fields editable, dropdowns "Sub-Channel" ("Select Sub-Channel"; GT/Diamond/Gold seen) and "Geo Classification" ("Select Geo Classification"; Urban seen) in English, then "GEO এবং ছবি ধারণ করুন". Info-change form: no GEO button, Cluster looks disabled. Closure form: read-only. No requester, date, photo or GPS is shown to the AMO. | docs/07 and plan: AMO sets Sub-Channel and Geo Classification, optional re-capture of GEO + photo. Mandatory flags, option lists and what the AMO sees: absent. | underspecified | minor | Make Sub-Channel and Geo Classification required on new-outlet verification (19% of outlets have no geo class: docs/22 P-14); options from the sub_channel and geo_class masters (GT, Diamond, Gold, Platinum, Silver, RCC, DCC, MT, HoReCa; Urban, Semi Urban, Rural, Hill). Route and cluster editable on new-outlet only. Beyond the manual, add requester name, submit date, photo and GPS distance (and an old-versus-new diff on info change) so the AMO can verify; localise the English labels. | docs/07 Outlet; docs/22 P-14; F-AMO-022..024 | S-33, S-34, S-36, S-37, S-38, S-39, F-22, F-24 |
| G-man-amo-31 | AMO-initiated outlet operations are cluster-first and differ from the SR flows | S-40, S-41, S-42 pp 55-62; U-42 | AMO "নতুন দোকান": cluster, name, owner, mobile, GEO + photo, Save (no Route, no Sub-Channel/Geo Class, no Cancel). "স্থায়ী বন্ধ": cluster > retailer, confirm "আপনি কি নিশ্চিত?" / "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে", no reason, no photo. "তথ্য পরিবর্তন করুন": cluster > retailer, name/owner/mobile, GEO + photo ("অপশনটি নির্বাচন করতে হবে"), confirm "এই পরিবর্তন সংরক্ষণ করা হবে". Hub caption "দোকান সংক্রান্ত অপারেশন্স". | docs/07 and plan F-AMO-025..027: "As F-SR-037/038/039" (route-based). Absent: route assignment of an AMO-created outlet, effect timing, closure with open dues or unsynced memos. | contradiction | major | Make the AMO forms cluster-first: outlet_change_request.route_id nullable with the web approver assigning the route (or add a route dropdown). Decide approval path for AMO-initiated requests (web Verify/Approve, as in the Web manual) and document it. Add a closure guard (open dues > 0 or unsynced memos: warn or block via cfg) because P-08 shows closed outlets keep dues. Keep the same confirm texts. | docs/07 Outlet own ops; docs/03 outlet_change_request; F-AMO-025..027; cfg.outlet.* (new) | S-32, S-40, S-41, S-42, F-25, F-26, F-27, R-085, R-086, R-087, E-05, E-17 + 2 messages |
| G-man-amo-32 | Photo + GEO capture: requiredness and feedback | S-35, S-14 pp 26, 49-50, 56-57, 60-62; U-60 | Full-screen camera (flip, shutter, ✕); exactly one photo ("একটি ছবি"); the dialog "ছবি ধারণ করা সম্পন্ন হয়েছে" appears only in the change-info flow; no preview, retake, coordinates or accuracy shown; unknown whether Save is blocked until captured (p60 says capture must be done after editing). | docs/06 and F-SYS-030: capture, compress, queue; "must re-capture GEO + photo" for info change. | underspecified | minor | Require capture before Save on new shop and info change; optional on verification. Always confirm capture (thumbnail plus "GEO captured, ±n m"); allow one retake; one fix at shutter with the mock flag; compress per docs/04. | docs/06 Outlet management; F-SYS-030; F-AMO-022/025/027 | S-14, S-34, S-35, S-42, F-22, F-27, R-088 + 1 messages |
| G-man-amo-33 | Update Base: no distance limits, no success message | S-43, S-44 pp 64-65; U-28 | "আপডেট বেস": route + outlet dropdowns, alphabet strip, button; confirm "আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে?" [আপডেট / বাতিল]; camera photo; "অবস্থান নিশ্চিত করুন" Google map with an avatar marker and accuracy circle; "নিশ্চিত করুন". No success message, no limit on how far the point moves or on distance from the AMO's GPS, no mock-GPS check. | docs/07: same steps. Plan request type base_update, cfg.geo.update_base_requires_photo, G-feat-39. | missing-rule | major | Add guards: the AMO must be within cfg.geo.update_base_max_distance_m (default 100) of the chosen point; the point within cfg.geo.update_base_max_move_m of the old location unless flagged; reject mocked fixes; route through approval (see the Manual Override gap); show a success or pending message; cap updates per outlet per month. Outlet location quality is already poor (docs/22 P-09/P-10). | docs/05; docs/07 Update Base; F-AMO-028; cfg.geo.* | S-43, S-44, F-28, R-089 + 1 messages |
| G-man-amo-34 | AMO Stock screen: columns, meaning, Print enabling | S-45 pp 14-16; U-41 (cross-check SR-S-17) | Columns "এসকেইউ \| ইস্যু \| স্টক"; Issue entered by −/+ or typing; "স্টক" read-only per SKU; bottom blue panel "ক্যাটাগরি \| মোট ইস্যু \| স্টক" (সিগারেট/বিড়ি/লাইটার/ম্যাচ); "সংরক্ষণ" always enabled; "প্রিন্ট" grey even after the printer connects (p16); no limits; not stated whose stock it is or what Print outputs. The Sales Deposit "স্টক" row counts quantity (45,150). | docs/07 Stock: "As F-SR-014/015 (issue by SKU, print stock memo)"; docs/03 stock_issue (issued, returned); plan M-11 stock_movement. | underspecified | major | Define: "ইস্যু" = quantity received today (stock_movement kind=issue); "স্টক" = on-hand after issue (opening + issues - sales); category totals computed locally; Print enabled after Save when a printer is connected; per-SKU soft ceiling (cfg.stock.max_issue_qty) and sales-plan SKUs only; the Sales Deposit "স্টক" reconciliation compares total quantity. | docs/06 step 3; docs/07 Stock; F-AMO-029; plan M-11 | S-45, F-29, R-114, R-115, R-116, E-10 |
| G-man-amo-35 | Summary: "ডিসকাউন্ট এবং অন্যান্য" and the second totals block | S-50 p 46; U-46 (cross-check SR-S-33) | Columns মেমো, পরিমাণ, মূল্য, ডিসকাউন্ট, ডিসকাউন্ট মূল্য, ফেরত per SKU plus category rows; green rows "ডিসকাউন্ট এবং অন্যান্য (-)" and "সর্বমোট"; a second block "মোট বিক্রয়" (মোট / ডিসকাউন্ট (-) / সর্ব মোট); no date picker; Print. The SR memo subtracts "মোট QC" from the total, so "অন্যান্য" is probably QC settlement. | docs/07: same columns, return qty = issue - sold (inferred); plan G-feat-60. Absent: what "অন্যান্য" is, the second block, confirmation of "ফেরত". | underspecified | minor | Define "ডিসকাউন্ট এবং অন্যান্য (-)" = discounts + QC settlement deductions; add the second totals block; verify "ফেরত" = issue - sold on the live app (sample 8,490 against 10 sold); the summary covers the current business date only. | docs/07 Summary; F-AMO-015; G-feat-60 | S-50, F-30 |
| G-man-amo-36 | Sales Deposit: p67 warns on dues, p68 says it blocks | S-51 pp 67-68 (PDF p66-68 checked); R-092/R-093, U-47a (cross-check SR-S-55) | p67: after sync the "বিক্রয় জমা" button turns blue; tapping with outstanding dues shows "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" [হ্যাঁ submits / না goes back]. p68 callout: "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে “বিক্রয় জমা” অপশনটি Enable হয়ে যাবে।" The SR manual contains both callouts too. | docs/07: "warns on outstanding dues"; plan cfg.day.sales_submit_dues_warning (off/warn/block, default warn). | contradiction | major | The manual contradicts itself. Follow p67 (warn, never block) because blocking a day on legitimate credit breaks selling; log the decision and keep block as a config option; the "১টি" in the message is a dynamic count. | docs/07 Sales Submit; DECISIONS.md; cfg.day.sales_submit_dues_warning | S-51, F-31, R-093 + 1 messages |
| G-man-amo-37 | Sales Deposit reconciliation rows and what each count measures | S-51 pp 66-68; U-47a, U-48 (cross-check SR-S-55) | Table "বিষয় \| ডিভাইস \| সার্ভার" with 9 rows in fixed order (আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন, ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা, মূল্য সম্মতি, জয়েন্ট কল, সার্ভে). p67 shows only 8 rows (no "মূল্য সম্মতি") and different totals. p66 shows device = server (118/118, 45,150/45,150) before any sync. "স্টক" is a quantity total while "বিক্রয়" is a record count. The SR table has 5 rows. | docs/07 and F-SYS-009: the same 9 types; plan: SR 5 types + AMO 4 extra; price compliance has no screen (G-feat-01). | underspecified | major | Make the row set config-driven per role and app version (p67 proves it varies). Define each row's measure (record count vs total quantity) and that the Server column is the last server_totals from the sync response (blank with a timestamp before the first sync), never a client echo. Types with no capture screen (QC, প্রমোশন, মূল্য সম্মতি) stay but show 0 until the capture exists. Test device == server after fuzzed batches. | F-SYS-009; F-AMO-030; plan lens-sync ACK; cfg.sync.reconcile_types (new) | S-51, F-31, R-094, E-15, E-20 + 1 messages |
| G-man-amo-38 | Sales Deposit and update screens: online indicator and failure states | S-51, S-04 pp 5-6, 66-68; U-47 | "ডিভাইস স্ট্যাটাস ● অনলাইন" (green dot) and "Network Status ● অনলাইন" on the update screen; no offline wording. "ডাটা সিঙ্ক করুন" is always enabled and "বিক্রয় জমা" needs the network; success "বিক্রয় সফল ভাবে জমা হয়েছে" after "কিছুক্ষণ অপেক্ষা করুন". No failure or timeout text. | docs/07: Sync, then Sales Submit; plan G-feat-65 and cfg.day.sales_submit_offline_queue (queue when offline). | online-only | major | Add an Online/Offline indicator ("অফলাইন") on Sales Deposit and the update screens. Sync is disabled with an explanation when offline. Sales Deposit when offline becomes a queued state ("will submit on next connection") with a pending badge, not an error. Add failure and timeout messages (retry, data kept). | docs/07 Sales Submit; G-feat-65; F-SR-035 | S-04, S-51, F-31, R-007 + 2 messages |
| G-man-amo-39 | AMO day state: no route, so no route_day | S-51, S-29 pp 39, 66-68 | The AMO has its own "বিক্রয় জমা", while the Live Dashboard counts "লগইন & বিক্রয় জমা" per target route (1/1 logged in, 0/1 deposited for "Savar Bazar(Sat, Mon, Wed)"). The AMO owns no route. | docs/09: POST /day/sales-submit {routeId, businessDate}; plan route_day (route x date, acting_user_id); docs/04 state machine is per route. | underspecified | major | Define the AMO day state as a per-user-per-date supervisor_day (checked in, synced, sales_submitted) separate from route_day, so an AMO submit neither needs a routeId nor flips an SR route to submitted. Decide whether AMO sales on a route count toward that route's login/submit % (recommend no; count only SR bundles). | docs/04; docs/09 day endpoints; plan M-32; F-SYS-016 | S-29, S-51, F-31, E-20 |
| G-man-amo-40 | Astha: month chips ignored by the memo target, empty state | S-52, S-53, S-54 pp 69-73; U-50 | Route/shop tabs; route dropdown; year then quarter ("Q-4 (Oct-Dec)") dropdowns (quarter greyed until a year is chosen); empty state "তথ্য পাওয়া যায়নি" until chosen; month chips Oct/Nov/Dec with 0-3 selectable; STD and Memo sub-tabs; the memo-target row ("All Brand" ২৮/৫/২৩/১৭.৮৬%) is identical with 0 chips and with Nov+Dec selected. | docs/06 Astha (SR) and docs/07 "as the SR app with a route selector"; docs/22 quarter; plan cfg.astha.quarter_start_month. | underspecified | minor | Define month chips for the memo target (recompute for the selected months; 0 chips = whole quarter; Apsis ignores them). Default year/quarter to the current ones instead of the empty state (keep the empty state when there is no data). Keep the target >= 0 guard (the manual shows -20 giving -37,500%). | docs/06 Astha; docs/07 Astha; F-AMO-031 | S-52, S-54, F-32 + 1 messages |
| G-man-amo-41 | STD Memo Report: columns, date rules, scrolling | S-56 p 75; U-52, U-55 | Title "এসটিডি মেমো রিপোর্ট"; date range "April 1, 2026 - April 25, 2026" (default month-to-date; the dashboard date was 2026-04-26); first column header = the AMO's own area name with route rows; visible columns সিগারেট, লাইটার (more off-screen: swipe right-to-left); footer "মোট" (the cigarette column does not sum to the footer). | docs/07: "STD Memo Report (date range, per-route STD by category)"; plan G-feat-53 (column sets unknown). | new-data | minor | Capture the full column set from the live app (likely সিগারেট, বিড়ি, লাইটার, ম্যাচ plus memo count). Define date rules: default = 1st of month to yesterday, a maximum range, an include-today toggle. Render with a frozen first column instead of the hidden swipe. | docs/07 Report; G-feat-53; cfg.report.* (new) | S-56, F-33, R-101, E-21 |
| G-man-amo-42 | Sales Summary Up To Now: CPR definition and zero-target % | S-57, S-58 p 76; U-53, U-54 | Route cards "CPR : 79.67", "মোট মেমো : 572"; route detail ব্রান্ড/টার্গেট/বিক্রয়/%/মেমো with fractional targets (88,235.29) and % = sales/target even above 400%; "0%" is shown when the target is 0.00 (Ananda Bidi sales 1,250). | docs/07: same; docs/10: CPR = successful calls / target outlets; F-SYS-034 divide-by-zero shows "-". | underspecified | minor | CPR values 24.55-91.52 are consistent with a strike-rate %: define month-to-date CPR = sum of successful calls / sum of target outlets over trading days to yesterday and print the unit "%". For target 0 show "-" (a deliberate change from the observed "0%", log it). Define the row sort order. | docs/07 Report; docs/10 CPR; F-AMO-033; plan D-05/D-09 | S-57, S-58, F-33, R-103, E-21 |
| G-man-amo-43 | PDA to Support and Logout behaviour | S-59..S-62 pp 77-79; U-47, U-57 (cross-check SR-S-58) | "PDA টু সাপোর্ট" gives an immediate dialog "সফল / সিঙ্ক ফাইল পাঠানো হয়ে গেছে" (no confirm, no progress; the file is "সেলস ফাইল" on p10, "ডাটা ফাইল" on p77-78, "সিঙ্ক ফাইল" in the dialog). Logout: "আপনি কি নিশ্চিত যে লগআউট করতে চান?" [হ্যাঁ / না]. Settings has no version or profile line. | docs/06 Settings; plan F-SYS-021 (needs network, Wi-Fi-preferred, size cap), F-SYS-022 (AMO keeps local data, TSO wipes), cfg.app.logout_wipes_data. | underspecified | minor | PDA to Support: show progress and failure, queue when offline ("will be sent when online"), include app version and last sync. Logout: warn with the pending-row count and never wipe unsynced data for the AMO. Add an app version/build line to Settings. | docs/06 Settings; F-SYS-021/022; G-feat-41 | S-61, S-62, F-35, F-36, F-37, R-106, R-107 + 2 messages |
| G-man-amo-44 | Number, date and time formatting contract | all screens; R-108, U-25 (PDF p34 checked) | Numbers in Bangla digits with Western 3-digit grouping (২,০৮২,৮২০, not lakh style ২০,৮২,৮২০), 2 decimals for money and %, but Latin digits in typed fields (100), dates ("November 26, 2025", "April 1, 2026 - April 25, 2026", "2025-11-26", "Completion on: 2025-11-03") and times ("04:05 PM"). The SR inventory calls the grouping Indian-style, but its samples are below 1 lakh, where both styles look the same. | CLAUDE.md and docs/04: Bangla-first, strings in a localization layer, fonts. No number, date or time rules. | missing-rule | major | Specify a formatting contract: digit script follows the UI language (Bangla digits for বাংলা, Latin for en), including the printed memo; 3-digit Western grouping unless AKTCL asks for lakh; money 2 dp; ISO dates in lists and long dates in pickers; 12-hour time with AM/PM; typed fields accept both digit scripts and normalise. Put the formatters in /packages. | docs/04 or new docs/20 i18n; /packages formatters; cfg.i18n.digit_script, grouping, date_style (new) | R-108 |
| G-man-amo-45 | Label formats and mobile-number normalisation | S-11, S-13, S-17, S-28, S-33..S-54; R-109..R-111, U-58, U-59 (cross-check WEB 11-digit contact) | Retailer label "Name (code-mobile-cluster)" with the mobile's leading 0 dropped and an empty mobile giving "--" ("Sujon Store (1618414--Madrasa Road)"); Task Delegation and Memo use "Sifat St (Diamond)"; route label = name + days glued ("Savar BazarDaily", "Savar Bazar(Sat, Mon, Wed)"); contacts appear as [phone] or [phone]; "Savar Metro" is a cluster on p28 and a zone on p71; the first dropdown is called "সেকশন" but lists routes; mobile fields accept any text. | docs/06 label "name (code-phone-cluster)"; plan cfg.app.outlet_list_label_format; docs/22 P-12 phone filled for 100%. The Web manual shows the contact as 11-digit 01xxxxxxxxx. | missing-rule | minor | Store the mobile canonically as 11 digits (01[3-9]xxxxxxxx), validate at entry (new shop, info change, verification) and display with the leading 0. Keep the label template configurable per screen (code-mobile-cluster vs sub-channel suffix) and render an empty mobile as "-". Route label = name + " (" + visit_days + ")". Add a terminology table: route = সেকশন; cluster versus zone. The alphabet strip lists only initials present in the list (L, O, Q, U, W, X, Y absent in samples; Latin only): define a case-insensitive match, the "All" chip and handling of Bangla-script names. | docs/06 outlet list; docs/03 outlet.contact_number; cfg.app.outlet_list_label_format (+ cfg.outlet.mobile_regex, cfg.app.route_label_format new) | S-28, S-46, R-109, R-110, R-111, E-04, E-05 |
| G-man-amo-46 | App-owned message catalogue and confirm-dialog matrix | all dialogs and toasts; M-011..M-070, U-61 | About 50 app-owned strings with inconsistent wording: save success "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" vs "Data Updated Successfully" vs toast "Save Successfully!" vs "বিক্রয় সফল ভাবে জমা হয়েছে". Confirms "আপনি কি নিশ্চিত?" (with a context line) exist on mark-paid, permanent close, change save, update base, logout and verification, but not on new-shop save or PDA upload. The messages never say whether the data is only on the device or on the server. | docs/04: "all strings in a localization layer". No string list or dialog matrix in any doc. | missing-message | major | Seed the l10n catalogue from the manual's message register (M-xxx) verbatim in Bangla with English equivalents; unify the success wording and distinguish "saved on device" from "uploaded" (offline-first); document which actions confirm; keep the Bangla originals for retraining parity but correct the manual's own typos instead of copying them ("অবশ্যয়", "আপানারা", "Audion", "Unknow", "কন্ট্রল" beside "কন্ট্রোল", "স্টেটাস" beside "স্ট্যাটাস", "সর্ব মোট" beside "সর্বমোট"); route everything through cfg.i18n.overrides. | app l10n catalogue; docs/06-08 messages appendix; cfg.i18n.overrides | R-082 + 29 messages |
| G-man-amo-47 | Offline, error and failure messages do not exist in the manual | all screens; U-47 | No text anywhere for: wrong password, no network, GPS fix failed, permission denied, printer not connected when Print is pressed, save/photo/upload failure, partial sync failure, session expiry, interrupted update. Offline is never described except the Sales Deposit "অনলাইন" dot. | docs/04: a mismatch is "visible, never silent"; plan lens-sync cfg.sync.reason_texts covers rejected rows only. | missing-message | major | Define the offline and failure message set and states: offline banner that says work can continue, printer-not-connected prompt, GPS timeout with Refresh, permission-denied, sync pending / failed with retry, session expired while offline = keep working. Needed because offline-first is a constraint and Apsis gives no reference text. | app l10n catalogue; docs/04; cfg.sync.reason_texts | F-37 |
| G-man-amo-48 | Glossary of terms used on screen | S-12, S-25, S-52, S-56..S-58; U-49 | Unexpanded terms in UI and guidance: OHS, OOS, SOQ, POSM, STD ("এসটিডি টার্গেট"), CPR, "PDA", "আস্থা", "লিফটেড স্টক", "ইস্যু"; English labels mixed with Bangla (R-112: Report, Sub-Channel, Geo Classification, Wing/Division/Territory/Zone, Assigned Tasks). | docs/10 defines STD, CPR, BSR; docs/06 Astha. OHS and SOQ are not defined (SOQ ties to the suggested-order hook in docs/05). | missing-field | minor | Add a glossary (Bangla label, English expansion, definition): OHS = on-hand stock (proposed), SOQ = suggested order quantity (proposed; link to F-SYS-035), POSM, STD (volume sold), CPR (strike rate), PDA to Support; decide which English labels stay English for retraining. | docs/01 glossary (new); docs/10 |  |
| G-man-amo-49 | AMO bundle scope: any route of the zone, offline | S-11, S-13, S-28, S-43, S-46 pp 21, 25, 38, 42, 64; docs/22 P-13 | The AMO picks any route of the zone and any retailer in it for Control Call and Joint Call (offline range check), Update Base, Task Delegation and Memo. Zones hold 4-54 routes of median 64 outlets (max 214). | docs/04 bundle = "the SR's routes and the outlets on them"; plan lens-sync 8.19 (an AMO with 20 routes: paged sections, 2 MB gz cap). | underspecified | major | Specify the AMO bundle: zone-wide outlet list (id, code, name, owner, phone, cluster, lat/lng, route, due, status) for all routes of the zone, delta-refreshed, plus pending verification requests, the SR list and today's tasks. Budget it (a 54-route zone is roughly 3.5k-11k outlets), confirm the 2 MB gz cap holds, and test a Control Call at any zone outlet in airplane mode. | docs/04 bundle; docs/22 P-13; F-SYS-006; lens-sync 8.19; cfg.bundle.* | S-11, S-13 |

## 2. Coverage tally

| Unit | Total | COVERED | PARTIAL | MISSING | CONTRADICTS |
| --- | --- | --- | --- | --- | --- |
| Screens (S-01..S-62) | 62 | 7 | 50 | 0 | 5 |
| Flows (F-01..F-37) | 37 | 2 | 31 | 0 | 4 |
| Data entities (E-01..E-22) | 22 | 7 | 15 | 0 | 0 |
| Rules (R-001..R-117, R-113 unused) | 116 | 56 | 43 | 10 | 7 |
| Messages (M-001..M-090) | 90 | 16 | 60 | 14 | 0 |
| **All** | **327** | **88** | **199** | **24** | **16** |

Verdict rules: COVERED = the spec or plan states the item adequately (or it is OS-owned, or an open question in docs/13 already tracks it); PARTIAL = mentioned but a rule, field, string or variant is missing; MISSING = absent; CONTRADICTS = the spec or plan says otherwise. Messages: PARTIAL means the spec describes the event but not the text; most message rows roll up to G-46. No screen is wholly MISSING because the spec covers every tile at some level; the misses are rules, strings and variants.

Gaps by severity: 27 major, 22 minor, 0 blocker. By kind: contradiction 9, missing-feature 1, missing-field 1, missing-message 2, missing-rule 8, new-data 5, online-only 1, underspecified 22.

CONTRADICTS items: screens S-14, S-24, S-30, S-40, S-48; flows F-08, F-13, F-17, F-25; rules R-022, R-024, R-036, R-070, R-076, R-085, R-093 (R-093 is manual-versus-manual; the spec sides with p67).

## 3. Manual items that imply a database field or table the schema lacks

`db/schema.sql` is the v1 starting point; the plan (lens-data) already proposes v2 for most rows. The last column says whether a gap remains after the plan.

| # | Manual item | Implied field / table | schema.sql (v1) | Plan (lens-data / features) | Gap |
| --- | --- | --- | --- | --- | --- |
| 1 | Dashboard header, SR list: "AMO 2 (ss344002)", "SR-12237", "SR-Kakoli - 1" (S-06, S-30, S-24) | app_user: employee_code, display label, designation SS, territory/point label | username, full_name, phone, role only; role enum has no ss | employee_code and locale added (M-19); no ss role | G-01, G-28 |
| 2 | Control Call / Joint Call (S-11, S-13) | visit.kind (control_call / joint_call / sr_call), route_id, subject SR | visit has no kind or route_id | visit_kind and route_id in M-05 | covered |
| 3 | KPI tiles (S-06) | supervisor_target: AMO daily call target, monthly total / control / joint targets | none (target is route/zone x product) | F-AMO-002 names +supervisor_target and cfg.target.supervisor_targets; no DDL in lens-data | G-03 |
| 4 | SR Call Assessment (S-12) | call_assessment: derived sr_id, rubric version, per-criterion scores incl. items 4-5, star scale | scores jsonb, sr_id nullable, no rubric | assessment_rubric / criterion / answer (M-27); items 4-5 unknown | G-09, G-10 |
| 5 | SR Performance Assessment (S-22) | distribution_check header (POSM unanswered/yes/no) + brand lines; CHECK oos implies present | one row per brand with posm repeated | header + lines (M-26); no CHECK, no tri-state | G-12 |
| 6 | Survey tile (S-23) | survey_id and questions for the AMO survey; responses linked to the visit | survey_response(question_key) only | survey_question / response (M-09), AMO instrument unknown | G-13 |
| 7 | Sales Deposit rows প্রমোশন, মূল্য সম্মতি, কিউসি (S-51) | promotion capture record; price_compliance_check; QC count | no price compliance, offer FK dangles, qc_entry exists | price_compliance_check with ASSUMED fields (M-28); promotion capture entity undefined | G-37 |
| 8 | Verification and AMO outlet ops (S-33..S-42) | outlet_change_request: origin (sr/amo/web), nullable route_id (cluster-first), decision (verify/reject/cancel), verified_at, rejection_reason | type, status, requested_by, verified_by, approved_by, proposed jsonb | route_id, verified_at, rejection_reason, event table (M-15); origin and decision not explicit | G-29, G-31 |
| 9 | Manual Override / Update Base (S-14, S-43, S-44) | outlet_location_history with source manual_override / base_update and the fix used | latitude, longitude only | outlet_location_history (M-16) | G-11, G-33 |
| 10 | Mobile fields on outlet forms (S-34, S-40, S-42) | canonical 11-digit mobile, CHECK pattern, phone_hash | contact_number text | phone_hash (M-16); no CHECK | G-45 |
| 11 | Task Delegation (S-27, S-28) | task: route_id, status vocabulary (চলমান / সম্পন্ন), created_by_role, display labels | status text default 'pending' | task v2 and task_event (M-25); label map not defined | G-26 |
| 12 | Memo, Review, Summary (S-18, S-47, S-50) | memo layout/template version, QC settlement deduction line, printed count | gross, discount, net; no QC deduction | memo v2 (M-06), memo-level QC (M-08) | G-14, G-18 |
| 13 | Sale entry units (S-17) | memo_line quantity unit (stick/piece/dozen) and pack_factor | memo_line.qty int, no unit | qty_entered + unit + pack_factor (D-02, M-07) | G-16 |
| 14 | Stock screen (S-45) | on-hand after issue; category totals derived | stock_issue(issued, returned) | stock_movement events (M-11) | G-34 |
| 15 | Sales Deposit (S-51) | snapshot of device-vs-server counts per record type plus dues count at submit | sync_batch.counts jsonb | server_totals in the ACK; route_day.submitted_with_dues; no per-submit snapshot | G-37 |
| 16 | AMO Sales Deposit (S-51) | supervisor_day (user x date): checked in, synced, sales_submitted | day_state enum is per route; final_submit per zone | route_day only (M-32) | G-39 |
| 17 | Reconciliation row set per role/version (S-51) | config of reconciled record types | none | none | G-37 (cfg.sync.reconcile_types new) |
| 18 | Team Location (S-24) | last fix per SR with source (attendance/visit/sync) and age | visit lat/lng only | geo_fix (M-17) and +user_last_fix | G-23 |
| 19 | Live Dashboard, Summary, Route detail (S-29, S-50, S-58) | route-day sales amount, memo count, visited, geo/photo-valid, login/deposit | fact_daily_route has calls and flags but no amount or memo columns | dw agg_daily_route with net_mtk, is_successful (lens-data section 4) | covered |
| 20 | Astha (S-52..S-54) | per-outlet STD and memo targets by month; quarter | target is route/zone only | astha_target (M-22), target v2 (M-21) | G-40 |
| 21 | Update stages (S-04, S-05) | app_release manifest; local migration state (n of m) | none | app_release (M-44); migration progress is local only | G-05 |
| 22 | Login on a new AMO phone (S-02) | device_otp and device binding for AMO devices | device table only | device v2 and OTP (M-41) | G-06 |

## 4. Manual items that imply an admin-configurable parameter

EXISTS = already a cfg key in lens-config; NEW = key to add; EXTEND = existing key needs wider scope.

| # | Parameter | Observed in manual | Proposed key | Status | Gap |
| --- | --- | --- | --- | --- | --- |
| 1 | Tile visibility per user/designation (Team Performance, SR Stock, Astha, Report, Survey) | 13 / 16 / 17 tiles by user (p8-12) | cfg.app.home_tiles (per role, wave; extend to per user/designation) | EXISTS (extend) | G-01 |
| 2 | Check-out earliest time | 17:00 | cfg.day.checkout_earliest_time | EXISTS | G-08 |
| 3 | Press-and-hold duration for check-in/out | not stated | cfg.app.hold_ms (default 1000) | NEW | G-08 |
| 4 | Check-in required before other tiles (hard vs soft) | message only | cfg.day.checkin_gate (off / soft / hard) | NEW | G-08 |
| 5 | Geofence radius for Control/Joint Call | not stated | cfg.geo.radius_m | EXISTS | G-11 |
| 6 | Manual Override reason list for AMO and per-day limit | no reason, no limit | cfg.sale.force_reasons (extend to AMO); cfg.geo.override_max_per_day | EXISTS + NEW | G-11 |
| 7 | Update Base distance guards | none | cfg.geo.update_base_max_distance_m, cfg.geo.update_base_max_move_m | NEW | G-33 |
| 8 | Outlet location change needs approval | applied at once in Apsis | cfg.geo.outlet_location_change_approval | EXISTS | G-11 |
| 9 | Joint-call rubric items, guidance text, star scale and chip labels (Low / উচ্চ) | 5 stars; 3 of 5 items known | cfg.rubric.joint_call | EXISTS | G-09 |
| 10 | Require all items rated; default star | star 1 pre-selected | cfg.rubric.require_all_rated, cfg.rubric.default_rating | NEW | G-10 |
| 11 | Distribution/OOS brand list and order (15 brands) | p31 | cfg.rubric.distribution_brands | EXISTS | G-12 |
| 12 | POSM question | Yes / No | cfg.survey.posm_questions | EXISTS | G-12 |
| 13 | AMO survey questions | never shown | cfg.survey.amo_questions | NEW | G-13 |
| 14 | Task types, statuses and labels, description length, past due date | General Task; চলমান / সম্পন্ন | cfg.task.types; cfg.task.statuses, cfg.task.description_max_len, cfg.task.allow_past_due_date | EXISTS + NEW | G-26 |
| 15 | Team Performance bar colour bands | red / yellow / green | cfg.kpi.bar_bands | NEW | G-25 |
| 16 | Percent display cap on summary cards | 100% on cards, uncapped in details | cfg.kpi.card_pct_cap (cfg.target.achievement_pct_cap exists for the raw clamp) | NEW + EXISTS | G-25 |
| 17 | Till-date target basis | pro-rated, formula unknown | cfg.kpi.tilldate_basis | EXISTS | G-25 |
| 18 | Dues at Sales Deposit: off / warn / block | p67 warn, p68 block | cfg.day.sales_submit_dues_warning | EXISTS | G-36 |
| 19 | Sales Deposit offline queue | online only in manual | cfg.day.sales_submit_offline_queue | EXISTS | G-38 |
| 20 | Reconciliation record types per role/version | 9 rows, 8 on p67 | cfg.sync.reconcile_types | NEW | G-37 |
| 21 | Credit: allow zero payment, min payment | must be < total | cfg.credit.partial_payment_min_pct; cfg.credit.allow_zero_payment | EXISTS + NEW | G-17 |
| 22 | Memo edit: reasons, after-print, AMO role, credit memos | 3 reasons (SR); AMO button grey | cfg.memo.edit_* (+ cfg.memo.edit_roles) | EXISTS + NEW | G-21 |
| 23 | Mark-paid mode: full only vs partial; payment mode | full only | cfg.credit.collection_partial_roles | NEW | G-19 |
| 24 | Printer pairing PINs and model filter | 0000 / 1234; RPP02N | cfg.print.pairing_pins, cfg.print.model_filter | NEW | G-07 |
| 25 | Retailer and route label templates per screen | name (code-mobile-cluster); name (suffix); route + days | cfg.app.outlet_list_label_format (per screen); cfg.app.route_label_format | EXISTS + NEW | G-45 |
| 26 | Mobile validation pattern | none | cfg.outlet.mobile_regex | NEW | G-45 |
| 27 | Digit script, grouping and date style | Bangla digits, Western grouping | cfg.i18n.digit_script, cfg.i18n.grouping, cfg.i18n.date_style | NEW | G-44 |
| 28 | Message and label overrides | about 50 strings | cfg.i18n.overrides | EXISTS | G-46 |
| 29 | Verification: Sub-Channel and Geo Classification required | dropdowns, flags unknown | cfg.outlet.verify_requires_subchannel, cfg.outlet.verify_requires_geo_class | NEW | G-30 |
| 30 | AMO-initiated requests: approval path; closure guard when dues exist | not stated | cfg.outlet.amo_request_approval; cfg.outlet.close_block_if_dues | NEW | G-31 |
| 31 | Team Location max age and map provider | no timestamp; Google Maps | cfg.tso.team_location_max_age_min (apply to AMO); cfg.maps.provider | EXISTS + NEW | G-23, G-24 |
| 32 | Report date rules: default range, include today, max range | month-to-date; ends yesterday | cfg.report.default_range, cfg.report.include_today, cfg.report.max_range_days | NEW | G-41 |
| 33 | Astha: quarter start, memo-target month semantics | Q-4 (Oct-Dec); months ignored | cfg.astha.quarter_start_month (exists); cfg.astha.memo_target_month_filter | EXISTS + NEW | G-40 |
| 34 | Update policy: soft / hard, min version, Wi-Fi only, allow offline day | Path A skippable, Path B blocking | cfg.release.min_version, update_prompt_policy, update_wifi_only (exist); cfg.release.finish_offline_day_before_force | EXISTS + NEW | G-05 |
| 35 | Location-permission-denied policy | mandatory, consequence not stated | cfg.app.location_denied_policy | NEW | G-06 |
| 36 | Offline session length | not stated | cfg.auth.offline_session_max_days | EXISTS | G-06 |
| 37 | Stock issue soft ceiling | none | cfg.stock.max_issue_qty | NEW | G-34 |
| 38 | PDA to Support size cap and Wi-Fi only | instant success dialog | cfg.support.max_upload_mb, cfg.support.pda_upload_wifi_only | EXISTS | G-43 |
| 39 | Dashboard badge/data refresh interval | not stated | cfg.ops.dashboard_refresh_min_s | EXISTS | G-02 |
| 40 | AMO call targets (daily, monthly total / control / joint) | 0/0 in all captures | cfg.target.supervisor_targets | EXISTS | G-03 |
| 41 | Alphabet filter script (Latin / Bangla) and visibility | Latin, dynamic | cfg.app.alphabet_filter | NEW | G-45 |

## 5. Connectivity: online-only versus offline

The AMO manual never states offline behaviour except the Sales Deposit "অনলাইন" dot, so every row below is inferred from the data each screen needs, plus SR-manual notes where the screen is shared. OFFLINE = must work with no signal; QUEUED = write offline, upload later; CACHED = read from the last snapshot with an as-of time; ONLINE-ONLY = needs the server or a map provider.

| Screen(s) | Class | Why / manual evidence | Requirement |
| --- | --- | --- | --- |
| S-01 install | OFFLINE | local APK install | APK file must already be on the phone |
| S-02 login | ONLINE first, then cached | first login needs the server; offline re-login not stated (U-05) | allow cached-session re-login (G-06) |
| S-03, S-15 permissions | OFFLINE | OS dialogs | denial path (G-06) |
| S-04 update (APK) | ONLINE-ONLY | download of about 74 MB; "ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন" | Wi-Fi preferred; resumable (G-05) |
| S-05 forced update / processing n/m | ONLINE then LOCAL | shown after login; migration runs locally | must preserve pending rows; never strand an offline day (G-05) |
| S-06 dashboard | CACHED | KPI targets and badge counts come from the bundle | show as-of time (G-02, G-03) |
| S-07 printer pairing | OFFLINE | Bluetooth, local | - |
| S-08 attendance | OFFLINE (address ONLINE) | check-in/out is local; reverse-geocoded address needs a lookup | coordinates when offline (G-24) |
| S-09, S-10 sheets | OFFLINE | local write | queued upload |
| S-11, S-13 Joint/Control Call picker | OFFLINE | range check uses outlet coordinates | zone-wide outlet list in the AMO bundle (G-49) |
| S-12, S-22, S-23 assessments, survey | OFFLINE (queued) | local forms; save message does not say local vs server | outbox; wording (G-46) |
| S-14 manual override camera | OFFLINE (queued) | photo + request queue | location change waits for approval (G-11) |
| S-16..S-19 sale, review, partial payment | OFFLINE | core of the day | persist draft at Proceed (G-14) |
| S-20 previous sale data | ONLINE-ONLY (cached recent) | SR manual: "মোবাইল ফোনের ডাটা অন রাখতে হবে" | 7-day local cache optional (G-22) |
| S-21 QC | OFFLINE | local | - |
| S-24 team location | ONLINE-ONLY | Google Map tiles + other users' fixes | list fallback with last cached fixes (G-23, G-24) |
| S-25, S-26 team performance | CACHED | server aggregates | snapshot with as-of; refresh online |
| S-27 task list / S-28 assign | CACHED / QUEUED | list from bundle; assign offline with server-side derivation | route assignment in the AMO bundle (G-26) |
| S-29 live dashboard | ONLINE-ONLY | "লাইভ" aggregates | explicit Filter load; last view cached (G-27) |
| S-30, S-31 SR stock | ONLINE-ONLY (cached) | other users' synced stock | as-of label (G-28) |
| S-32..S-39 verification | CACHED list, QUEUED actions | requests arrive with the bundle; verification saved offline | photos via media queue |
| S-40..S-42 AMO outlet ops | OFFLINE (queued) | local forms + photo | pending state visible (G-31) |
| S-43 update base / S-44 map | OFFLINE (steps) / ONLINE (map tiles) | Google Maps screen | tile-less fallback (G-24, G-33) |
| S-45 stock, S-46..S-49 memo, S-50 summary | OFFLINE | local data and Bluetooth print | - |
| S-51 sales deposit | ONLINE for sync; submit QUEUED | "ডিভাইস স্ট্যাটাস অনলাইন"; offline wording never shown | queued submit state (G-38) |
| S-52..S-54 Astha | CACHED | targets/achievement snapshots | as-of label (G-40) |
| S-55..S-58 reports | ONLINE-ONLY | route/date aggregates | optional last-view cache (G-41, G-42) |
| S-59, S-60 settings, language | OFFLINE | local | - |
| S-61 PDA to Support | ONLINE (queue if offline) | uploads the sync file | queue and report (G-43) |
| S-62 logout | OFFLINE | local | pending-row warning (G-43) |

## 6. Contradictions

### 6A. Manual versus spec or plan

| Item | Manual | Spec / plan | Gap |
| --- | --- | --- | --- |
| Task Delegation badge | Badge only on Outlet (PDF p8-10) | docs/07: Task Delegation badge = pending SR requests | G-02 |
| AMO is one zone, login = zone | Header names territory/point; usernames amo5756, amo5001, ss344002; SS users run the AMO app | docs/07 "login = the zone"; role enum without ss | G-01 |
| Override changes outlet location | Photo updates the location at once (also SR Force Sale) | docs/05: via verification flow, never silent | G-11 |
| Team Location | "লাইভ লোকেশান" | docs/07: last synced fixes | G-23 |
| SR phone in SR Stock | Masked "***********" | docs/07 shows phone; lens-security: supervisors in scope see app_user.phone | G-28 |
| Mark paid | Whole memo, no amount (also SR-S-40) | docs/06: full or partial | G-19 |
| AMO own new shop | Cluster-based; no route, Sub-Channel, Geo Class, Cancel | F-AMO-025: as SR (route-based) | G-31 |
| Verification Cancel | "বাতিল" undescribed; Web manual: Reject is a web action on Verified rows | docs/07: Cancel (reject) | G-29 |
| QC and Print on Review | Independent buttons; SR Print = commit with two confirms | docs/06: QC is a step before print | G-14 |
| Divide by zero in % | Target 0.00 shows "0%" (p76); Astha 0.00% | F-SYS-034: show "-" | G-42 |
| KPI header | Today = daily, other three = month | docs/07 header says month to date for all four | G-03 |
| Live "মোট বিক্রয়" | 38 equals the p28 quantity total | docs/07: total memos | G-27 |
| Match unit | FB/SL per piece (p28, p43, SR-S-33) and per dozen (p29, p46) | docs/13 Q8 open | G-16 |
| Attendance fix | 12:53 PM check-out sheet (SR manual shows 05:00 PM) | 5 pm rule | G-08 |

### 6B. Manual versus manual (and inside the manual)

| Item | Where | Note |
| --- | --- | --- |
| Joint Call vs Control Call slide title | p21 titled Control Call but shows the Joint Call screen (U-10) | p25-27 is the real Control Call screen |
| Deposit enabling rule | p67 (enabled after sync, dues dialog lets you submit) vs p68 (enabled when all dues are paid) (U-47a) | G-36; the SR manual repeats both callouts |
| Deposit table rows | p66/p68 9 rows vs p67 8 rows; p66 equal before sync | G-37 |
| Update UIs | Path A English vs Path B Bangla; callout "শুরু করো" vs button "আপডেট" (U-04) | resolved by SR-S-06..09: two stages of one update (G-05) |
| Versions and size | login 1.0.2 vs update 1.0.4; APK 74.39 MB vs 92.18/72.18 MB (U-02, U-03) | SR build is 1.0.25 |
| Check-out time sample | 12:53 PM on p20 vs the 5 PM rule and SR 05:00 PM (U-14) | G-08 |
| Match prices | FB 2.33 and SL 1.58 (p28, p43) vs 28.00 and 19.00 (p29, p46) | G-16 |
| Cluster vs zone | "Savar Metro" is a cluster on p28 and a zone on p71 (U-58) | G-45 |
| SKU code | ARIS-O-20s (p41) vs ARIS-A-20s (p43) (U-38) | seed catalog has both |
| Totals that do not add up | Sale Data total 20,260 vs rows 20,660 (p30); STD Memo footer vs rows and Motiharpol 151,990 (p75) vs 151,690 (p34); Team Performance bidi 6,500 vs 7,300 and 106.28% vs 106.62% (U-26, U-32, U-52) | data glitches; golden tests must not copy them |
| Maxim %  | Platinum Series 107.1% vs computed 105.97% (p76) (U-54) | - |
| Hub options | callout says three options, four tiles shown; first callout names "নতুন আউটলেট যাচাইকরণ", next screen titled "নতুন দোকান" (U-42) | - |
| Badge counts | ৮ vs ২/২/২ vs 4 request cards (U-07) | G-02 |
| Cancel icon | floppy (p49, 50, 54) vs x-in-circle (p52) (U-39) | G-29 |
| Success wording | "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" vs "Data Updated Successfully" vs "Save Successfully!" (U-61) | G-46 |
| Astha memo target | identical with no chips and with Nov+Dec (U-50) | G-40 |
| Digit grouping | SR inventory says Indian-style; AMO p34 shows Western (২,০৮২,৮২০) | G-44 |
| OTP | SR manual: OTP on a new device and after a new version, 2FA at login; AMO manual shows none | G-06 |
| Edit button | SR manual: yellow, enabled; AMO manual: light grey on both memos (p43) | G-21 |

## 7. Cross-manual resolutions and what still needs the live app

Resolved from the SR, TSO and Web manuals (so the AMO inventory's open questions need not block):

- U-04 two update UIs: sequential stages of one update (APK, then local data re-processing; release note "Sync file update without deleting") - SR-S-06..09.
- U-22 what persists a sale: Print is the commit, with confirms "আপনি কি নিশ্চিত? বিক্রয় জমা হবে" and "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" - SR-S-33, SR-S-37 (AMO dialogs not shown).
- U-44 mark-as-paid is whole-memo in both apps - SR-S-40 (spec says full or partial).
- U-39 no reject path: rejection is a web action on Verified rows; the app only verifies - WEB-S-39, F-20.
- U-14 check-out time: the SR check-out sheet shows 05:00 PM, so the AMO's 12:53 PM sample is probably a test capture - SR-S-14.
- Task statuses "চলমান" and "সম্পন্ন" - SR-S-57. Survey is the SR POSM Availability form - SR-S-31. "অন্যান্য" on the summary is probably the QC deduction ("মোট QC" on the SR memo) - SR-S-33. Sale History is online-only - SR-S-20.
- CPR (U-53) is the strike-rate formula already in docs/10; only the month-to-date aggregation needs fixing (G-42).

Still unresolved and needing a live-app capture or AKTCL answer:

- Joint Call rubric items 4 and 5 (titles and guidance) - G-09.
- What "SS" is and which tiles each designation gets - G-01.
- Meaning of the sale-card badge, "0%" and "০" lines and the eye-slash icon - G-15.
- The AMO Survey form - G-13.
- What "বাতিল" does on the verification forms and whether the AMO can reject - G-29.
- Why the AMO Edit button is grey, and the AMO edit screen - G-21.
- Full column set of the STD Memo Report - G-41.
- Printed memo, stock slip and summary samples at 58 mm - G-18.
- Whether record types QC, প্রমোশন and মূল্য সম্মতি have any capture screen in any app - G-37.
- Whether the AMO app binds a device with the TSO OTP and re-prompts after each version - G-06.

## Appendix A. Ledger: every item with its verdict

Gap column = the G-man-amo number(s). Blank = COVERED with no gap.

### A1. Screens

| ID | Screen | Verdict | Gaps | Note |
| --- | --- | --- | --- | --- |
| S-01 | APK install | PARTIAL | G-05 | side-load covered (docs/06, docs/02); per-role APK + Install-unknown-apps absent |
| S-02 | Login | PARTIAL | G-06 | login covered; version footer, OTP, denial and offline login absent |
| S-03 | Location permission | PARTIAL | G-06 | permission covered; mandatory-denial path absent |
| S-04 | Update Available (APK) | PARTIAL | G-05, G-38 | updater covered at one line; unknown-apps, progress, stage 2, network indicator absent |
| S-05 | Forced update + processing n/m | PARTIAL | G-05 | min_version gate in plan; local migration stage and offline-day rule absent |
| S-06 | Dashboard | PARTIAL | G-01, G-02, G-03, G-04 | tile set, badges, header date, KPI tiles, chevron, variants |
| S-07 | Printer pairing | PARTIAL | G-07 | pairing covered; PIN, in-app wizard, status icon locations absent |
| S-08 | Attendance | PARTIAL | G-08, G-24 | covered in docs/07; state strings, gate, clock, geocoder provider |
| S-09 | Check-in sheet | PARTIAL | G-08 | press-and-hold covered; hold duration and time source absent |
| S-10 | Check-out sheet | PARTIAL | G-08 | 5 pm rule covered; sample 12:53 PM anomaly, time source |
| S-11 | Joint Call Activity | PARTIAL | G-11, G-49 | geo gate covered; override semantics, zone-wide offline outlet list |
| S-12 | SR Call Assessment | PARTIAL | G-09, G-10 | items 4-5 unknown; SR attribution and default stars absent |
| S-13 | Control Call Activity | PARTIAL | G-11, G-49 | picker covered; override semantics, bundle scope |
| S-14 | Manual Override camera | CONTRADICTS | G-11, G-32 | manual: location overwritten at once; spec: via approval |
| S-15 | Camera/audio permission prompts | COVERED |  | Q15 open in docs/13; evidence in G-06 |
| S-16 | Control Call menu | COVERED |  | docs/07 Control Call |
| S-17 | Sales (order entry) | PARTIAL | G-04, G-15, G-16 | covered as "same as SR"; card extras and unit evidence absent |
| S-18 | Sale Review | PARTIAL | G-14, G-16, G-18 | commit point, QC/Print independence, memo layouts |
| S-19 | Partial-payment dialog | PARTIAL | G-17 | partial payment covered; validation, rounding |
| S-20 | Sale Data (previous sales) | PARTIAL | G-22 | date picker covered; source, default date, online-only |
| S-21 | Product QC | COVERED |  | docs/06 (QC fault quantities); screen not in AMO manual; link to G-14 |
| S-22 | SR Performance Assessment | PARTIAL | G-12 | brands/POSM covered; OOS subset and POSM tri-state absent |
| S-23 | Survey | PARTIAL | G-13 | tile only; screen never shown |
| S-24 | Team Location | CONTRADICTS | G-23, G-24 | manual "live"; spec last-synced; map provider |
| S-25 | Team Performance | PARTIAL | G-25 | tabs/cards covered; drill-down, bands, caps |
| S-26 | Team Performance details | PARTIAL | G-25 | details table covered; category filter, item level |
| S-27 | Task Delegation list | PARTIAL | G-26 | list covered; status labels |
| S-28 | Assign Task form | PARTIAL | G-26, G-45 | fields covered; outlet card, label suffix, limits |
| S-29 | Live Data | PARTIAL | G-27, G-39 | metrics covered; Filter semantics, "মোট বিক্রয়" measure, AMO login/deposit entity |
| S-30 | SR List | CONTRADICTS | G-28 | manual masks SR phone; plan/spec show it |
| S-31 | Lifted Stock | PARTIAL | G-28 | covered; period, units, total |
| S-32 | Outlet hub | PARTIAL | G-02, G-31 | tiles/badges covered; badge placement, "three vs four" options |
| S-33 | New outlet verification list | PARTIAL | G-30 | list fields absent |
| S-34 | New outlet verification form | PARTIAL | G-29, G-30, G-32 | Cancel=reject unsupported; mandatory flags; photo feedback |
| S-35 | Outlet GEO + photo capture | PARTIAL | G-32 | capture covered; requiredness and feedback absent |
| S-36 | Closure verification list | PARTIAL | G-30 | list fields absent |
| S-37 | Closure verification detail | PARTIAL | G-29, G-30 | no reject path; confirm text unknown |
| S-38 | Info-change verification list | PARTIAL | G-30 | list fields absent |
| S-39 | Info-change verification form | PARTIAL | G-29, G-30 | edit-any-field covered; reject path, diff |
| S-40 | New shop (AMO) | CONTRADICTS | G-31 | manual: cluster-based, no route; spec: as SR (route) |
| S-41 | Permanently closed (AMO) | PARTIAL | G-31 | cluster>retailer, confirm text, closure guard |
| S-42 | Change information (AMO) | PARTIAL | G-31, G-32 | cluster>retailer, confirm text, photo requiredness |
| S-43 | Update Base | PARTIAL | G-33 | flow covered; distance limits, success message |
| S-44 | Confirm location (map) | PARTIAL | G-33, G-24 | map step covered; provider, limits |
| S-45 | Stock | PARTIAL | G-34, G-07 | issue/print covered; Stock column, category panel, Print rule |
| S-46 | Memo selector | PARTIAL | G-19, G-20, G-45 | covered; due label, scope, label format |
| S-47 | Memo detail | PARTIAL | G-18, G-19, G-21 | layouts, mark-paid, edit button |
| S-48 | Mark credit memo paid | CONTRADICTS | G-19 | manual: whole-memo only; spec: full or partial |
| S-49 | Edit sale | PARTIAL | G-21 | screen not shown; SR rules exist in spec |
| S-50 | Sales Summary | PARTIAL | G-35 | columns covered; "অন্যান্য", second block |
| S-51 | Sales Deposit | PARTIAL | G-36, G-37, G-38, G-39 | rule conflict, row set/measures, online indicator, AMO day state |
| S-52 | Astha - route info | PARTIAL | G-40 | covered; memo target vs month chips, defaults |
| S-53 | Astha - shop list | COVERED |  | docs/06 Astha shop info |
| S-54 | Astha - retailer detail | PARTIAL | G-40 | same as S-52 |
| S-55 | Reports menu | COVERED |  | docs/07 Report |
| S-56 | STD Memo Report | PARTIAL | G-41 | columns and date rules |
| S-57 | Sales Summary Up To Now | PARTIAL | G-42 | CPR period/unit |
| S-58 | Route detail | PARTIAL | G-42 | zero-target % display |
| S-59 | Settings | COVERED |  | docs/06 Settings |
| S-60 | Language dropdown | COVERED |  | docs/06 Settings; F-SYS-019 |
| S-61 | PDA to Support | PARTIAL | G-43 | offline/failure and progress absent |
| S-62 | Logout confirmation | PARTIAL | G-43 | pending-data behaviour |

### A2. Flows

| ID | Flow | Verdict | Gaps |
| --- | --- | --- | --- |
| F-01 | Install, first launch, login | PARTIAL | G-05, G-06 |
| F-02 | Update Path A (APK) | PARTIAL | G-05 |
| F-03 | Forced update Path B | PARTIAL | G-05 |
| F-04 | Printer pairing and connection | PARTIAL | G-07 |
| F-05 | Daily check-in | PARTIAL | G-08 |
| F-06 | Daily check-out | PARTIAL | G-08 |
| F-07 | Control Call > sale > print | PARTIAL | G-04, G-14, G-15 |
| F-08 | Manual override | CONTRADICTS | G-11 |
| F-09 | Credit sale with partial payment | PARTIAL | G-17 |
| F-10 | View previous sale data | PARTIAL | G-22 |
| F-11 | Memo reprint | COVERED |  |
| F-12 | Edit a sale | PARTIAL | G-21 |
| F-13 | Settle a credit memo | CONTRADICTS | G-19 |
| F-14 | Joint call assessment | PARTIAL | G-09, G-10 |
| F-15 | SR Performance Assessment | PARTIAL | G-12 |
| F-16 | Survey | PARTIAL | G-13 |
| F-17 | Team location | CONTRADICTS | G-23 |
| F-18 | Team performance drill-down | PARTIAL | G-25 |
| F-19 | Assign a task | PARTIAL | G-26 |
| F-20 | Live dashboard | PARTIAL | G-27 |
| F-21 | SR lifted stock | PARTIAL | G-28 |
| F-22 | Verify SR new outlet | PARTIAL | G-29, G-30, G-32 |
| F-23 | Verify SR closure | PARTIAL | G-29 |
| F-24 | Verify SR info change | PARTIAL | G-29, G-30 |
| F-25 | AMO adds a new shop | CONTRADICTS | G-31 |
| F-26 | AMO permanently closes a shop | PARTIAL | G-31 |
| F-27 | AMO changes outlet information | PARTIAL | G-31, G-32 |
| F-28 | Update outlet base location | PARTIAL | G-33 |
| F-29 | Stock entry / issue | PARTIAL | G-34 |
| F-30 | Daily summary and print | PARTIAL | G-35 |
| F-31 | End-of-day sync and sales deposit | PARTIAL | G-36, G-37, G-38, G-39 |
| F-32 | Astha | PARTIAL | G-40 |
| F-33 | Reports | PARTIAL | G-41, G-42 |
| F-34 | Change language | COVERED |  |
| F-35 | Send sync/data file to support | PARTIAL | G-43 |
| F-36 | Logout | PARTIAL | G-43 |
| F-37 | Explicit gaps (zero sale, void, return, discount entry, payment modes, offline login, password, timeout, attendance fix, notifications) | PARTIAL | G-14, G-43, G-47 |

### A3. Data entities

| ID | Entity | Verdict | Gaps |
| --- | --- | --- | --- |
| E-01 | User / AMO account | PARTIAL | G-01, G-28 |
| E-02 | App version / update | COVERED |  |
| E-03 | Device permissions and printer | COVERED |  |
| E-04 | Org hierarchy / geography | PARTIAL | G-45 |
| E-05 | Outlet / retailer | PARTIAL | G-31, G-45 |
| E-06 | SR | PARTIAL | G-28 |
| E-07 | Product: SKU / brand / category | PARTIAL | G-16 |
| E-08 | Sale / memo | PARTIAL | G-14, G-18 |
| E-09 | Credit / due / payment | PARTIAL | G-19, G-20 |
| E-10 | Stock | PARTIAL | G-34 |
| E-11 | Attendance | COVERED |  |
| E-12 | Call activity (Control / Joint) | COVERED |  |
| E-13 | SR call assessment (Joint) | PARTIAL | G-09, G-10 |
| E-14 | SR performance assessment | COVERED |  |
| E-15 | Survey / QC / Promotion / Price compliance | PARTIAL | G-13, G-37 |
| E-16 | Task | PARTIAL | G-26 |
| E-17 | Outlet change requests | PARTIAL | G-29, G-31 |
| E-18 | Targets and achievements | COVERED |  |
| E-19 | Live metrics | PARTIAL | G-27 |
| E-20 | Sync / sales deposit | PARTIAL | G-37, G-39 |
| E-21 | Reports | PARTIAL | G-41, G-42 |
| E-22 | Support file | COVERED |  |

### A4. Rules

| ID | Rule | Verdict | Gaps |
| --- | --- | --- | --- |
| R-001 | Side-load APK; per-role APK name | PARTIAL | G-05 |
| R-002 | Launcher icon login | COVERED |  |
| R-003 | Per-AMO credentials | COVERED |  |
| R-004 | Location permission mandatory | PARTIAL | G-06 |
| R-005 | Login footer shows version + vendor | PARTIAL | G-06 |
| R-006 | Install unknown apps must be ON | MISSING | G-05 |
| R-007 | Update screen network status | MISSING | G-38 |
| R-008 | Do not close app while downloading | PARTIAL | G-05 |
| R-009 | OS Update then Open | COVERED |  |
| R-010 | In-app update mandatory after login | PARTIAL | G-05 |
| R-011 | Do not close during in-app update; n/m | MISSING | G-05 |
| R-012 | Bluetooth and printer ON before pairing | COVERED |  |
| R-013 | Pair RPP02N with PIN 0000 | PARTIAL | G-07 |
| R-014 | Nearby-devices permission mandatory | COVERED |  |
| R-015 | Connect via printer icon; red/green; banner | COVERED |  |
| R-016 | Printing needs connected printer; icon on Memo/Summary | PARTIAL | G-07 |
| R-017 | Dashboard header format and date | PARTIAL | G-03 |
| R-018 | Tile set varies by user/version | MISSING | G-01 |
| R-019 | KPI tiles achieved/target | PARTIAL | G-03 |
| R-020 | Attendance = own check-in/out | COVERED |  |
| R-021 | Control Call vs Joint Call | COVERED |  |
| R-022 | Team Location is live | CONTRADICTS | G-23 |
| R-023 | SR Stock scope = zone | COVERED |  |
| R-024 | Outlet tile badge (and no Task badge) | CONTRADICTS | G-02 |
| R-025 | Chevron collapses tile panel | MISSING | G-03 |
| R-026 | Must check in before work; message | PARTIAL | G-08 |
| R-027 | Attendance address + refresh | COVERED |  |
| R-028 | Check-in by press-and-hold | COVERED |  |
| R-029 | Check-in disabled after | COVERED |  |
| R-030 | Check-out from 5 PM | COVERED |  |
| R-031 | Check-out by press-and-hold | COVERED |  |
| R-032 | One check-in/out per day | COVERED |  |
| R-033 | Select route + shop for daily call work | COVERED |  |
| R-034 | Alphabet strip | COVERED |  |
| R-035 | Geo-range check; override required | COVERED |  |
| R-036 | Override photo overwrites outlet location | CONTRADICTS | G-11 |
| R-037 | Camera/mic permission While using | COVERED |  |
| R-038 | Joint call 3 sections | COVERED |  |
| R-039 | Rating 1-5 | COVERED |  |
| R-040 | Star 1 pre-selected; save always possible | PARTIAL | G-10 |
| R-041 | Assessment saved; success dialog | COVERED |  |
| R-042 | Control Call 3 actions | COVERED |  |
| R-043 | AMO can sell in Control Call | COVERED |  |
| R-044 | Quantity in sticks; stepper/typing | PARTIAL | G-16 |
| R-045 | Running total in bottom bar | PARTIAL | G-15 |
| R-046 | Proceed to Review | COVERED |  |
| R-047 | Category subtotals, total discount, grand total | PARTIAL | G-18 |
| R-048 | Due checkbox unchecked by default | COVERED |  |
| R-049 | Collected amount < grand total | PARTIAL | G-17 |
| R-050 | Remaining due live; label whole taka | PARTIAL | G-17 |
| R-051 | Print can be postponed to Memo | COVERED |  |
| R-052 | Previous sale data for any date | PARTIAL | G-22 |
| R-053 | Product QC button on Review | PARTIAL | G-14 |
| R-054 | Distribution tick rule | COVERED |  |
| R-055 | OOS tick rule | PARTIAL | G-12 |
| R-056 | POSM yes/no | COVERED |  |
| R-057 | After Save, retailer cleared | PARTIAL | G-12 |
| R-058 | Team Location one SR at a time | COVERED |  |
| R-059 | Monthly / Till Date; 4 categories | COVERED |  |
| R-060 | % colours; card cap 100 | PARTIAL | G-25 |
| R-061 | Remaining = max(T-A,0); uncapped details % | PARTIAL | G-25 |
| R-062 | Achievement identical; till-date target pro-rated | COVERED |  |
| R-063 | Tap category for filtered details | MISSING | G-25 |
| R-064 | Task list = tasks assigned by this AMO | COVERED |  |
| R-065 | Assign: route/outlet/type/date; assignee derived | COVERED |  |
| R-066 | Success toast and list update | COVERED |  |
| R-067 | Task status "চলমান" | PARTIAL | G-26 |
| R-068 | Live: select route then Filter | PARTIAL | G-27 |
| R-069 | Live metric formulas | PARTIAL | G-27 |
| R-070 | SR phone masked | CONTRADICTS | G-28 |
| R-071 | Lifted stock per SKU; no date selector | PARTIAL | G-28 |
| R-072 | Memo: route + outlet then Memo tile | COVERED |  |
| R-073 | Due label beside outlet name | MISSING | G-20 |
| R-074 | Reprint after connecting printer | COVERED |  |
| R-075 | Edit from memo; greyed button | PARTIAL | G-21 |
| R-076 | Credit memo single "পরিশোধিত করুন" | CONTRADICTS | G-19 |
| R-077 | Mark paid confirm; due label disappears | COVERED |  |
| R-078 | Summary whole day; Print | COVERED |  |
| R-079 | Outlet hub: SR requests + own ops | COVERED |  |
| R-080 | AMO inputs Sub-Channel + Geo Classification | COVERED |  |
| R-081 | New shop: info then GEO+photo; one photo | COVERED |  |
| R-082 | New-shop Save: success, no confirm | PARTIAL | G-46 |
| R-083 | Closure verification: save, confirm, success | PARTIAL | G-29 |
| R-084 | Info-change verification: edit any field | PARTIAL | G-29 |
| R-085 | AMO New shop is cluster-based | CONTRADICTS | G-31 |
| R-086 | AMO permanent closure: cluster + retailer + confirm | PARTIAL | G-31 |
| R-087 | AMO info change: GEO+photo required; confirm | PARTIAL | G-31 |
| R-088 | Photo-captured success alert | PARTIAL | G-32 |
| R-089 | Base-location update flow | PARTIAL | G-33 |
| R-090 | Sync all data and submit before finishing | COVERED |  |
| R-091 | Deposit disabled until sync done | COVERED |  |
| R-092 | Dues dialog: submit anyway or go back | COVERED |  |
| R-093 | Deposit enabled only when all dues paid (conflict) | CONTRADICTS | G-36 |
| R-094 | 9 record types device vs server; online status | PARTIAL | G-37 |
| R-095 | Astha year/quarter/months | COVERED |  |
| R-096 | STD vs Memo target | COVERED |  |
| R-097 | Astha table remaining/% rules | COVERED |  |
| R-098 | Shop-based Astha list | COVERED |  |
| R-099 | Sub-Channel + Wing>Division>Territory>Zone | COVERED |  |
| R-100 | Report tile: two reports | COVERED |  |
| R-101 | STD Memo Report default month-to-date | PARTIAL | G-41 |
| R-102 | Sales Summary: route list CPR + memos | COVERED |  |
| R-103 | Route detail brand table | PARTIAL | G-42 |
| R-104 | Settings: language, PDA, logout | COVERED |  |
| R-105 | Runtime language switch | COVERED |  |
| R-106 | PDA to Support sends sync file | PARTIAL | G-43 |
| R-107 | Logout confirm | PARTIAL | G-43 |
| R-108 | Bangla digits, Western grouping, 2 dp | MISSING | G-44 |
| R-109 | Retailer label format; "--" for empty mobile | PARTIAL | G-45 |
| R-110 | Route label format | PARTIAL | G-45 |
| R-111 | Mobile format inconsistent; no validation | MISSING | G-45 |
| R-112 | Bangla/English mix | COVERED |  |
| R-114 | Stock: issue entry, read-only Stock | PARTIAL | G-34 |
| R-115 | Stock category summary panel | MISSING | G-34 |
| R-116 | Stock: Save enabled, Print grey | PARTIAL | G-34 |
| R-117 | Printer state indicator | COVERED |  |

### A5. Messages

| ID | Verdict | Gaps | Note |
| --- | --- | --- | --- |
| M-001 | COVERED |  | OS-owned dialog |
| M-002 | COVERED |  | OS-owned dialog |
| M-003 | COVERED |  | OS-owned dialog |
| M-007 | COVERED |  | OS-owned dialog |
| M-010 | COVERED |  | OS-owned dialog |
| M-016 | COVERED |  | OS-owned dialog |
| M-017 | COVERED |  | OS-owned dialog |
| M-018 | COVERED |  | OS-owned dialog |
| M-019 | COVERED |  | OS-owned dialog |
| M-020 | COVERED |  | OS-owned dialog |
| M-035 | COVERED |  | OS-owned; Q15 |
| M-036 | COVERED |  | OS-owned; Q15 |
| M-004 | MISSING | G-38 | network status indicator |
| M-005 | PARTIAL | G-05 | update banner/button |
| M-006 | PARTIAL | G-05 | update banner/button |
| M-008 | MISSING | G-05 | update progress / do-not-close |
| M-009 | MISSING | G-05 | update progress / do-not-close |
| M-013 | MISSING | G-05 | update progress / do-not-close |
| M-014 | MISSING | G-05 | update progress / do-not-close |
| M-011 | PARTIAL | G-05 | forced update prompt |
| M-012 | PARTIAL | G-05 | forced update prompt |
| M-015 | MISSING | G-06 | login footer |
| M-021 | PARTIAL | G-07 | printer-connected banner |
| M-022 | MISSING | G-08 | attendance prompt strings |
| M-025 | MISSING | G-08 | attendance prompt strings |
| M-031 | MISSING | G-08 | attendance prompt strings |
| M-023 | PARTIAL | G-08 | attendance state strings |
| M-026 | PARTIAL | G-08 | attendance state strings |
| M-027 | PARTIAL | G-08 | attendance state strings |
| M-029 | PARTIAL | G-08 | attendance state strings |
| M-030 | PARTIAL | G-08 | attendance state strings |
| M-024 | COVERED |  | press and hold |
| M-028 | COVERED |  | Already Checked-In/Out (docs/07) |
| M-032 | COVERED |  | Already Checked-In/Out (docs/07) |
| M-033 | COVERED |  | docs/07 range message |
| M-034 | MISSING | G-46 | placeholder |
| M-037 | PARTIAL | G-10, G-46 | rubric instruction |
| M-038 | PARTIAL | G-46 | success wording |
| M-057 | PARTIAL | G-46 | success wording |
| M-066 | PARTIAL | G-46 | success wording |
| M-039 | PARTIAL | G-46 | info text |
| M-051 | PARTIAL | G-46 | info text |
| M-056 | PARTIAL | G-46 | info text |
| M-040 | PARTIAL | G-17 | credit sale strings |
| M-041 | PARTIAL | G-17 | credit sale strings |
| M-042 | PARTIAL | G-17 | credit sale strings |
| M-043 | PARTIAL | G-17 | credit sale strings |
| M-044 | PARTIAL | G-17 | credit sale strings |
| M-045 | PARTIAL | G-17 | credit sale strings |
| M-046 | PARTIAL | G-12 | tick instructions |
| M-047 | PARTIAL | G-12 | tick instructions |
| M-048 | PARTIAL | G-26 | task strings |
| M-049 | PARTIAL | G-26 | task strings |
| M-050 | PARTIAL | G-26 | task strings |
| M-052 | MISSING | G-20 | due label |
| M-053 | PARTIAL | G-19 | mark-paid strings |
| M-054 | PARTIAL | G-19 | mark-paid strings |
| M-055 | PARTIAL | G-19 | mark-paid strings |
| M-058 | PARTIAL | G-29 | verification success wording |
| M-059 | PARTIAL | G-31 | AMO closure/change confirms |
| M-061 | PARTIAL | G-31 | AMO closure/change confirms |
| M-060 | MISSING | G-32 | photo-captured dialog |
| M-062 | PARTIAL | G-33 | update-base confirm |
| M-063 | MISSING | G-38 | device status indicator |
| M-064 | PARTIAL | G-37 | deposit warning box |
| M-065 | PARTIAL | G-36 | dues dialog |
| M-067 | PARTIAL | G-40 | Astha empty state |
| M-068 | PARTIAL | G-43 | settings dialogs |
| M-069 | PARTIAL | G-43 | settings dialogs |
| M-070 | MISSING | G-46 | confirm text unknown in the manual itself |
| M-071 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-072 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-073 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-074 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-075 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-076 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-077 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-078 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-079 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-080 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-081 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-082 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-083 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-084 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-085 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-086 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-087 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-088 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-089 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |
| M-090 | PARTIAL | G-46 | label set: English in spec, Bangla verbatim absent |

