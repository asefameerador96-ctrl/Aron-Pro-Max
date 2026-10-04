# Verification: device binding, login, logout, app updates, check-out time

Batch theme: "Device binding, login, logout, app updates, check-out time".
Entries checked: G-man-021, G-man-022, G-man-024, G-man-027, G-man-028, G-man-029 (plus their section 2.7 rows C-08, C-09, C-18, C-25, C-48, C-49, I-01, I-02, I-16, I-17, I-18 and section 3 rows).
Date of check: 2026-10-04. Method: every cited PDF page was opened and read (SR pp 2-16 and 76-79; AMO pp 2-8, 17-20, 77-79; TSO pp 2-21; Web pp 45-47) and compared with docs/01-13, docs/22, docs/ui-reference and the plan lens files.

## Summary table

| Id | Verdict | One-line reason |
|---|---|---|
| G-man-021 | PARTLY | 4 digits, view-only TSO panel, re-ask after a new version, 8 vs 5 columns are all on the pages. "Server creates the OTP at login" is an inference, the sample Create Time values suggest a long-lived per-user code, and the committed docs ("issued by the TSO") are not flatly contradicted. |
| G-man-022 | PARTLY | Unknown-sources hop, percent download, installer, post-install splash, n/m processing and three separate APKs are all on the pages. The "forced gate", SHA-256, resumable download and "version contradiction" are not demonstrated by the manual. |
| G-man-024 | CONFIRMED | TSO dialog "Your all app data will removed." and the silent SR/AMO dialog are on the pages; docs/08 says keep the wipe. Only the "rows at risk" list is unproven. |
| G-man-027 | PARTLY | TSO has exactly seven drawer entries and no Settings. But SR/AMO Settings do not hold update, version or password either, and docs/08 already matches the manual, so the only conflict is with plan drafts. |
| G-man-028 | CONFIRMED | Every TSO label, button, dialog is English; exactly three Bangla strings exist in the app UI. CLAUDE.md #8 "as today" is wrong for the TSO. |
| G-man-029 | PARTLY | Address + Refresh, press-and-hold sheet, four states, 17:00 gate are on the pages. The AMO "English vs SR Bangla" wording claim is wrong (the AMO shows both), and "device clock", "test capture" and "check-in gate" are not supported by the pages. |

---

## G-man-021: Device-binding OTP

**Claim (register).** Manual: the OTP is created by the server when a device logs in on a new phone or after a new app version; it is 4 digits; the TSO only views it on the web (no issue button); the panel has 8 columns (SR manual) or 5 (Web manual); the AMO manual shows no OTP. Spec: docs/09 and docs/06 have the TSO issue the OTP; lens M-41 stores a one-way hash; cfg.auth.otp_length defaults to 6. Register winner: manual on who creates it and its length; the recommendation (no re-verify for in-place updates) wins over the manual's "new version" re-ask.

**What I saw.**
- SR p5 (OTP screen, title "OTP যাচাইকরণ প্রক্রিয়া"): four empty boxes, text "Enter the 4-digit OTP provided by your TSO.", button "Verify", red footer "Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process." Callout: after logging in with the SR id an OTP verification page appears; the OTP must be given the first time on a device; "log in to the TSO portal to see the OTP". Second phone shows the TSO portal sidebar with the last item "SR Device OTP" (user label "tso-apsis").
- SR p6: portal page "SR OTP Panel" with filters Wing, Division, Territory, Distribution House, Zone (each "All Selected (1)") and one button "View" (no Issue/Generate/Resend button anywhere). Table columns: Sr No., Field Force ID, Field Force Name, Username, Zone ID, Zone, Create Time, OTP (8). Two rows: sr334002 / Create Time 2026-01-20 / OTP 6818 and sr334001 / 2026-01-14 / 3560. Second phone has 3 5 6 0 typed into the 4 boxes. Callout: pick territory or zone, click View, "you will see the OTP list of all routes of that territory or zone"; take the right OTP for the right route and Verify.
- Web p46 ("SR Device OTP"): same page, same five filters, "View", empty state "No Data". Table columns: Field Force Name, Username, Zone Code, Zone, OTP (5). Callout: after clicking Login on the SR device, log in to the TSO portal, open SR Device OTP, select everything, click View, and the OTP list per route appears.
- SR p7: release notes on the update page for version 1.0.25: "New promotion modality., Login time 2FA verification., Sync file update without deleting." So the OTP is the 1.0.25 "2FA".
- AMO p4-5: login (AMO App version 1.0.2) -> location permission -> "Update Available". No OTP screen anywhere in the AMO manual. TSO p4: login goes straight to the dashboard, no OTP, no version.
- SR update walk-through pp 7-10 (Open -> splash -> processing -> dashboard) shows no OTP step.

**Spec quotes.**
- docs/06 Setup: "A new phone is bound with a **device OTP issued by the TSO** before it can sync."
- docs/09: "`POST /auth/bind-device` `{ deviceUuid, otp }` → binds (TSO-issued OTP)"; admin list includes "SR Device OTP" and `device-otp` CRUD.
- docs/02: "Device binding via a TSO-issued one-time code (as today)."
- docs/13 item 4: "device binding via TSO OTP. Confirm token lifetimes and refresh policy."
- Plan drafts (not committed docs): lens-features F-ADM-022 "Pick user → issue OTP (shown to TSO to relay)", F-API-036 `POST /admin/device-otp` "Issue OTP"; lens-data M-41 `device_otp.code_hash bytea NOT NULL`; lens-config `cfg.auth.otp_length` default 6.

**Verdict: PARTLY.**

**Corrected statement.**
1. Confirmed on the pages: the OTP is 4 digits; it is shown to the TSO on a web page that has filters and a View button but no issue control; the SR app asks for it on the first login on a device AND after installing a new app version (SR p5 footer); the SR-manual panel has 8 columns, the Web-manual panel 5 (and calls the zone column "Zone Code" where the SR manual says "Zone ID"); the AMO and TSO manuals show no OTP step.
2. NOT shown: who generates the code and when. The pages say "provided by your TSO" and that the TSO reads it on the portal. That the server creates a fresh code when a device logs in is a plausible reading of Web p46 (look after you click Login) but not a printed fact. The two sample rows have Create Time 2026-01-14 and 2026-01-20, while the same SR account's other screenshots are dated 2026-04-22; so the code looks long-lived per user, or "Create Time" is the user/record creation time. Do not design "a new code per login attempt" from the manual. Expiry, retries and lockout are not shown at all.
3. docs/06, 09 and 02 say "TSO-issued". That is compatible with the manual's "provided by your TSO" (the TSO is the human relay), so the committed docs are not flatly contradicted. The real conflict is with the plan drafts (F-ADM-022 issue action, F-API-036 issue endpoint, M-41 one-way hash, cfg.auth.otp_length 6): the TSO must be able to READ the code, so a hash cannot work.
4. The panel in the manual lists every SR in the selected zone (callout: "all routes"). "List only SRs with an open request" in the register is a recommended improvement, not parity.
5. The AMO question stays open: the OTP arrived with SR build 1.0.25 (release note), the AMO manual is build 1.0.2/1.0.4 and shows none. It cannot be shown that the AMO app binds today.
6. "Re-ask after a new version" is printed on the SR OTP screen, but "installed new version" may mean a full install rather than an in-place update, and the update walk-through (pp 7-10) shows no OTP. The register's recommendation of cfg.auth.reverify_on_new_version (parity = true, recommended false) stands as a decision, and the 8,500-lookups-per-release arithmetic is an estimate, not from the manual.

**Build implication.**
- Schema (lens-data M-41): replace `code_hash` with an encrypted-at-rest (or regenerate-on-view with audit) code column; add `created_at`, `expires_at`, `used_at`, `attempts`, `created_by = 'system'`; add `device.verified_app_version` and a per-user `app_user.employee_code` ("Field Force ID", used as a panel column).
- Rule: the OTP gate triggers on (a) unknown device_uuid and (b) `app_version` < `device.verified_app_version` only when `cfg.auth.reverify_on_new_version` is true. Default decision to record as a D-id: parity (true) on the pilot, false at wave rollout, because it breaks an offline day. Confirm with the business (Q-15 in the register).
- API: `POST /auth/bind-device` stays; add a TSO read `GET /admin/device-otp?zone=` (scoped from the token, no issue action required); the admin "issue/re-issue" action stays as an admin-only improvement.
- UI: TSO web panel columns = union (Sr No., Field Force ID, Name, Username, Zone ID/Code, Zone, Create Time, OTP) plus search and refresh; filters Wing..Zone as in the manual.
- Config: cfg.auth.otp_length default 4 (manual wins; docs have no number), cfg.auth.otp_ttl_min (manual shows no expiry: unknown; confirm with the business), cfg.auth.otp_visible_roles, cfg.auth.reverify_on_new_version.
- Offline: OTP verify and the TSO panel are online-only (the portal is a web page); a device that has already verified keeps working offline.

---

## G-man-022: App distribution and in-app updater

**Claim (register).** Manual: side-loaded APKs, an "Install unknown apps" hop, a two-stage update (APK download with percent, then an on-device n/m processing screen), a forced update gate, per-role APKs named `aron_<role>_app_<dd_mm_yyyy>_v<n>.apk`, versions that disagree (SR 1.0.25 vs splash 1.0.1; AMO 1.0.2 vs 1.0.4 offered; TSO none). Spec: docs/06 has only "in-app update"; docs/02 says Play track; docs/11 says signed APK. Fix: handle REQUEST_INSTALL_PACKAGES; resumable download with SHA-256; migration preserves pending rows; the hard gate must not strand an offline day; one codebase, three flavours; show real version.

**What I saw.**
- SR p2: file manager lists `aron_sr_app_25_11_2025_v1.apk`, 80.40 MB; system installer "Do you want to install this app? Cancel / Install". SR p3: "App installed. Done / Open"; launcher icon labelled "ARON SR". AMO p2-3: `aron_amo_app_26_11_2025_v1.apk`, 74.39 MB, launcher "ARON AMO". TSO p2-3: `aron_tso_app_28_02_2026_v1.apk`, 74.09 MB, launcher "ARON TSO"; the same file list also shows `live_aron_amo_a..._2026_v1.0.3.apk` (79.23 MB) and `..._v1.old.apk` (78.75 MB). All three files end "_v1" while the in-app versions differ, so "v<n>" is not the app version.
- SR p4 login footer "SR App (version - 1.0.25), App Developed by Apsis Solutions". AMO p4 "AMO App (version - 1.0.2), Developed by Apsis Solutions". TSO p4: no version, no footer.
- SR p7: page with back arrow, "Network Status ● অনলাইন", banner "NEW UPDATE", "Update Available / Version 1.0.25 available", release notes line, button "Download App & Install". Callout: after tapping it the user is taken automatically to "Install unknown apps" and must switch ARON SR on. The second phone shows that Android list with ARON SR (93.11 MB) off. AMO p5: same with "Version 1.0.4 available", no release notes; list shows ARON AMO (92.18 MB) on and ARON SR (101 MB) off, i.e. three separate packages.
- SR p8: "Downloading 24%" bar, red line "Please do not close the app while downloading"; callout: wait for the download and keep the device internet on; then installer "Do you want to update this app? Cancel / Update" with "Download completed". AMO p6: "Downloading 53%". SR p9: "App installed. Open", then a Bangla splash "কিছু নতুন আপডেট পাওয়া গেছে, এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!" with a single button "আপডেট" and footer "Current version SR App 1.0.1" (AMO p7: "Current version 1.0.2"). No skip or back control on the splash.
- SR p10: "অ্যাপ আপডেট হচ্ছে", bar, "প্রসেসিং(১৪/৮৭)", red tip "টিপস: অ্যাপটি বন্ধ করবেন না", then the dashboard. AMO p7: "প্রসেসিং(৪৬/৮৪)". Nothing says what the 87 or 84 units are.
- No SHA-256, checksum, resume, Wi-Fi-only or size warning appears anywhere. TSO manual has no update screens.

**Spec quotes.**
- docs/06 Setup: "**In-app update:** a "new update available → download & install" flow (the fleet side-loads APKs; keep an update-check endpoint and a lightweight in-app updater)."
- docs/02: "App distribution: internal APK channel for pilot, then Play Store managed/internal track or MDM push for the fleet".
- docs/11: "Distribute via an internal/managed track or signed APK channel; keep the in-app updater." and "The new app must coexist with the old during pilot/parallel (different package id)".
- docs/13 "deliberately changed": "One role-aware app codebase instead of three separate apps (if Flutter is kept) — or keep three builds if the business prefers separate install artefacts."
- docs/04: "Target install size materially smaller than the current ~90 MB APK."
- Plan: lens-config cfg.release.min_version "blocks a **new day's login**, never upload or local capture"; lens-scale 4.8 same. So the "do not strand an offline day" rule is already in plan drafts, not in docs/.

**Verdict: PARTLY.**

**Corrected statement.**
1. Confirmed: the current update is a side-loaded APK with an "Install unknown apps" hop (SR p7, AMO p5), a percent download, the OS installer, then a Bangla local-update splash and a n/m processing screen; the three apps are separate packages with separate launcher labels and separate install entries; the 80.40 MB SR file is about 4% of a 2 GB day (80.4 / 2048 = 3.9%). The spec is silent on the unknown-sources hop, the two stages, release notes and the splash.
2. Correction, "forced gate": the word "must" (অবশ্যই) is printed only on the post-install splash, whose button leads to the n/m processing. That reads as a mandatory local data/schema update after install, not as a policy gate that blocks the app for not having the newest APK. Whether the English "Update Available" page can be dismissed is not stated (it has a back arrow). So cfg.release.min_version with force is a design addition, not observed parity.
3. Correction, "preserves pending rows": the pages only say "প্রসেসিং(n/87)" and "do not close". The one printed hint is the 1.0.25 release note "Sync file update without deleting", which implies earlier builds lost the sync file on update. Treat "no loss across an update" as a requirement to test (T-gate), not as observed behaviour.
4. Correction, versions: login 1.0.25 / "1.0.25 available" / splash "Current version 1.0.1" is consistent with an old build 1.0.1 updating to 1.0.25 (the splash always shows the PRE-update number: SR 1.0.1, AMO 1.0.2 while 1.0.4 is offered). It is a stale or data-version label on the splash, not proof of a version bug. The AMO "92.18 MB" is the installed size, the file is 74.39 MB; docs/04's "~90 MB APK" is the installed footprint (92-101 MB), while the APK files are 74-80 MB.
5. Not in the manual (design, label as improvement): SHA-256 verification, resumable download, Wi-Fi-only default, "finish the open route before enforcing". Also SR and AMO have no "drawer"; the version belongs on the login footer (as today) and in Settings.
6. Citation fix: docs/13 Q1 is the mobile framework; the one-codebase-vs-three-builds question is the "deliberately changed" bullet, and the distribution channel (side-load vs Play) is not asked in docs/13 at all (register Q-50 is new).

**Build implication.**
- Schema: `app_release` (version, role, abi, apk_url, sha256, size_mb, notes_bn, notes_en, min_version, rollout_wave, published_at) as in lens-features F-SYS-020/F-API-029; `device.app_version`, `device.data_schema_version`.
- Rule: updater order = (1) online check, Bangla instructions, deep link to Install unknown apps (use canPackageInstalls check), (2) download with percent, verify SHA-256, (3) OS install, (4) local migration with n/m progress that is transactional and keeps all rows with sync_state != synced; add a kill-and-relaunch test during migration (T-gate, phase 2/7). Decide the channel: the docs/02 Play-track option is incompatible with a self-installing APK (record as a D-id; ask the business, Q-50).
- UI: Bangla instructions for the unknown-sources toggle; "do not close" and resume text; version shown on the login footer and Settings from one source (build version), plus the data-schema version on the splash if kept.
- Config: cfg.release.min_version, update_prompt_policy (default `prompt`, never `force` on mobile data), update_wifi_only, finish_offline_day_before_force (register 2.5). Flavours: three applicationIds (different from the old apps for the pilot), launcher labels "ARON SR / ARON AMO / ARON TSO", file names kept for parity but add the real version.
- Phase: unknown-sources hop and download UI in phase 2 with the updater; forced gate in phase 7 (waves).

---

## G-man-024: Logout with unsynced data

**Claim (register).** TSO logout wipes everything (dialog "Your all app data will removed.") with no pending-upload check; SR and AMO logout dialogs are silent about wiping. docs/08 says keep the wipe; this conflicts with CLAUDE.md #1 and #2. Fix: block logout while anything is unsynced.

**What I saw.**
- TSO p21 ("Logout"): dashboard with a logout icon at the top-right of the header (the same icon sits at the top-right of the drawer, pp 8, 20); callout "click this icon to logout". Dialog: title "Log Out!", text "Your all app data will removed.", buttons "Cancel" (green) and "Log Out" (orange). No count of pending items, no sync button.
- SR p77 (Settings): three tiles, language dropdown (বাংলা), "PDA টু সাপোর্ট", "লগ আউট". SR p79: dialog "আপনি কি নিশ্চিত যে লগআউট করতে চান?" with "হ্যাঁ" / "না"; callout "select হ্যাঁ and complete the logout". AMO p77-79: identical (Settings is a home tile on the AMO). Neither says anything about deleting data.
- The TSO manual shows no sync screen, no pending count and no offline queue anywhere, so what a TSO device can have unsent is not documented.

**Spec quotes.**
- docs/08: "**Logout wipes all local app data** ("your all app data will be removed") — keep that behaviour."
- CLAUDE.md constraint 1: "The network is never in the critical path of a sale"; constraint 2: "A retried, duplicated or partial upload must never create a second sale or double a number."
- docs/08 Notes: "Same offline/sync rules." (so the new TSO app will queue writes).
- Plan: lens-features F-SYS-022 "SR/AMO: ends session (keeps local day data; ASSUMPTION). TSO: wipes all local app data ... must refuse while pending uploads exist, or warn with count (G-feat-41)".

**Verdict: CONFIRMED.** The dialog wording, the silent SR/AMO dialogs and docs/08's "keep that behaviour" are exactly as claimed.

**Corrected statement (small).**
- The TSO dialog text is "Your all app data will removed." (docs/08 prints "will be removed"; keep the manual's grammar only if parity matters, else fix it).
- The pages show no guard; they cannot prove none exists, and "synced or not" in the register's key finding 12 is inference. The old TSO app seems to be mostly an online client, so the size of the loss is unknown; the "TSO rows at risk" list is a risk of the NEW design (docs/08: same offline rules), not an observation.
- SR/AMO wiping is unknown, not "silent no-wipe".
- Logout is a header icon on the TSO (and the drawer), not a drawer item and not in Settings; docs/08 does not say where it is.

**Build implication.**
- Rule: on Log Out, if any local row has sync_state in (pending, syncing, failed) or the media queue is non-empty, show "N items not yet sent" with [Sync now] [Cancel]; wipe (DB, caches, secure storage, tokens) only when fully reconciled. Same for SR and AMO (they keep data on a normal logout only if the business wants; default: logout ends the session and keeps unsent rows). Add bn/en string pair; fix the grammar.
- Config: cfg.app.logout_wipes_data {tso: true} only on a reconciled device; cfg.app.logout_block_when_pending = true.
- Decision: D-id, "CLAUDE.md #1/#2 override docs/08 'keep that behaviour'", with the reason; amend docs/08.
- Test: T-3 kill-and-relaunch plus logout with pending rows; assert zero rows lost.

---

## G-man-027: TSO app has no Settings

**Claim (register).** The TSO app has no Settings (language, version, update, PDA to Support, password), contradicting plan F-SYS-019/020/021/004 ("all-app"); docs/08 lists seven drawer entries. Fix: state the seven entries in docs/08; add a marked Settings entry as an improvement.

**What I saw.**
- TSO p8 (and pp 9, 19, 20): the drawer has exactly seven entries: Dashboard, Leave, Final Submit, My Periphery (My Team, Retailer), My Call (My Visit Plan, Set Plan), Target Status, My Feedback, each with a one-line subtitle ("View Dashboard", "Manage your leaves", "Submit Sales Data", "Check your team and outlet", "Create and manage plans", "Check targets", "Manage your feedbacks"). Header greeting "Good day, Apsis", hamburger top-left, logout icon top-right, a Home FAB at the bottom. TSO p4: login has Username, Password, Login only: no language switch, no version, no "forgot password".
- SR p77 and AMO p77: Settings has exactly three tiles: language, "PDA টু সাপোর্ট", "লগ আউট". No update entry, no version line and no password change in the SR or AMO Settings either. Update runs at login (SR p7, AMO p5); version is the login footer. The only password change in any manual is Web p45 "Credentials".

**Spec quotes.**
- docs/08 Drawer menu: "Dashboard, Leave, Final Submit, My Periphery (My Team, Retailer), My Call (My Visit Plan, Set Plan), Target Status, My Feedback." (seven top-level entries, no Settings, no logout location).
- docs/06 Setup: "**Settings:** language (English / বাংলা), "PDA to Support" ..., Logout." (SR). docs/07 Home lists a "Settings" tile (AMO). docs/08 has none.
- Plan drafts: lens-features F-SYS-019 language toggle, F-SYS-020 updater, F-SYS-021 PDA to Support, all "all-app"; F-SYS-004 change password "all roles ... Credentials page / app settings".

**Verdict: PARTLY.**

**Corrected statement.**
- The TSO app has no Settings screen and no language switch, no version display, no PDA to Support and no documented update flow: confirmed on pp 4, 8, 21.
- It is NOT a contradiction with committed docs: docs/08 already matches the manual (seven entries) and is silent on Settings. The conflict is only with the plan drafts' "all-app" marking.
- "Password" and "update" were wrongly grouped: SR and AMO Settings contain only language, PDA to Support and Log Out. No mobile app (SR, AMO or TSO) has a change-password screen; the policy page exists only on the web (Credentials). F-SYS-004 therefore needs "web only" for parity, with in-app change-password as an improvement.
- Logout is a header icon (not a drawer item). The Home FAB is part of the TSO chrome.
- Whether a Settings screen is hidden elsewhere cannot be fully excluded from a 22-page manual (TSO U-30 says the manual does not cover it).

**Build implication.**
- docs/08: add "The current TSO drawer has exactly seven entries; logout is the header icon; there is no Settings, language, printer, OTP or change-password screen."
- Config: cfg.app.drawer_items.tso = the seven (+ Settings only if accepted as an improvement; mark it as such). If added: language, version and update check, PDA to Support, change password.
- Plan fix: mark F-SYS-019/020/021 "SR, AMO (TSO: improvement)", F-SYS-004 "web; apps: improvement".
- Phase 3 (TSO app). No schema change.

---

## G-man-028: TSO UI is English-first

**Claim (register).** All TSO screens are English with three Bangla strings; CLAUDE.md #8 says Bangla-first. Fix: ship bn and en catalogues for the TSO, default TSO to English per role (cfg.app.default_locale sr: bn, amo: bn, tso: en), keep the three Bangla strings verbatim and give the Bangla duplicate-Final-Submit alert an English twin.

**What I saw.**
- English throughout: login "Username / Password / Login" (p4); dashboard titles "Sales (Cigarette)", "Sales (Lighter in Box)", "Sales (Match in Dozen)", "By Channel STD", "CPR", "Login & Bikroy Joma Status" (pp 5-7); drawer (p8); "Leave Applications", "Apply For Leave", "Leave Apply", "Casual / Sick / Earn" (pp 10-11); "Final Submit", "Get Sales Data", "Success - Final Submit Done Successfully..." (pp 12-13); My Teams / Outlets / Radius (p15); Visit Plan, Set Plan, Select Outlets (pp 16-18); Target Status (p19); My Feedback (p20); "Log Out!" (p21).
- Exactly three Bangla strings inside the app: p14 alert "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" (with a Bangla callout around it); p17 Visit Query questions "SR আপনার দোকান নিয়মিত ভিজিট করে?" and "SR নিয়মিত মেমো প্রিন্ট করে দেয়?" (the third item "Do you want to delegate the task?" is English). Map labels such as "Dhaka ঢাকা" come from the map provider.
- The PDF's own callouts are Bangla; they are not app strings.

**Spec quotes.**
- CLAUDE.md constraint 8: "Bilingual. The field apps are Bangla-first with English labels in places (as today)."
- docs/08: no language statement. docs/06 Settings: language English / বাংলা (SR). docs/02 Localization: "Bangla-first, English labels where the current app uses them".
- Plan: lens-config `cfg.app.default_locale` default `bn`.

**Verdict: CONFIRMED.**

**Corrected statement (additions only).**
- The TSO has no language switch today (p4, p8): English-only. "User can switch" is an addition, not parity.
- Within the TSO the English/Bangla split is not uniform: the success dialog is English while the already-submitted alert is Bangla. The AMO is also mixed (see G-man-029: English "Already Checked-In").
- The "as today" clause of CLAUDE.md #8 is factually wrong for the TSO, so recording a D-id is warranted: parity default English for the TSO, constraint #8 still met because the bn catalogue ships and the strings are in the localisation layer.

**Build implication.**
- Config: cfg.app.default_locale per role (sr bn, amo bn, tso en); full bn and en catalogues for the TSO build; Bengali font bundled (docs/04: one Bengali + one Latin font).
- Strings: the three Bangla strings seeded verbatim in bn; en twins written for the alert and the questions; the English labels seeded in en with Bangla translations reviewed by AKTCL.
- Rule: no hard-coded text in the TSO screens; map labels follow the map provider locale (open question: map provider, G-man-059).
- Phase 3. D-id to add to DECISIONS.md.

---

## G-man-029: Attendance UX

**Claim (register).** SR and AMO attendance: address + Refresh, press-and-hold confirm sheet with a time chip, four states, check-in gate, clock source; the SR should get the AMO attendance UI; the AMO shows English "Already Checked-In/Out" where the SR shows Bangla "চেক ইন/আউট সম্পন্ন হয়েছে"; the AMO 12:53 PM check-out sample contradicts the 5 PM rule and is "probably a test capture"; check-out enabled at >= 17:00 inclusive; check-in gate as a soft prompt; time chip from the device clock with a server-skew flag.

**What I saw.**
- SR p12: header "SR-16858 (sr16858)" and "Savar Bazar(Sat, Mon, Wed), 2025-11-26" (route with visit days and date); a card with a reverse-geocoded address "Kamal Ataturk Avenue, Banani, 32, Gulshan, Dhaka, Dhaka District, Dhaka Division, 1212, Bangladesh" and a red Refresh icon; message "আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।"; green "চেক ইন" and grey "চেক আউট". Callout: if the current location cannot be read, press Refresh.
- SR p13: tap Check in -> bottom sheet "চেক ইন করা হচ্ছে", time chip "04:47 PM", big half-circle button "চাপ দিয়ে ধরে রাখুন" (press and hold). Callout: check the time, then press and hold the green button.
- SR p14: after check-in the left box reads "চেক ইন সম্পন্ন হয়েছে" and the right box (orange, warning icon) reads "চেক আউট ৫টার পরে সক্রিয় হবে" ("will be active after 5"); yellow note "বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন।" ("from 5 pm"). Second phone: red active "চেক আউট"; the address differs ("28 Ahmed Tower ... Gulshan ... 1213"), so the address is the current location, not a stored one. SR p15: sheet "চেক আউট করা হচ্ছে", chip "05:00 PM", red hold button; final state "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" with boxes "চেক ইন সম্পন্ন হয়েছে" / "চেক আউট সম্পন্ন হয়েছে".
- AMO p17-18: same layout on a purple theme (AMO-5756, Savar BazarAMO); sheet chip "04:05 PM".
- AMO p19 left (AMO-5756): "চেক ইন সম্পন্ন হয়েছে" and the orange "চেক আউট ৫টার পরে সক্রিয় হবে" (Bangla). AMO p19 right (a different account "AMO 2 (ss344002)", date 2025-11-01): the same check-in box reads English "Already Checked-In" and the check-out button is red and active. AMO p20: sheet chip "12:53 PM" on that account; final state "Already Checked-In" / "Already Checked-Out" (English) under a Bangla thank-you message.
- Nothing on pp 12-20 says whether sale tiles are locked before check-in, how long to hold, whether the time is the device or server clock, or whether the fix is stored.

**Spec quotes.**
- docs/06 Day flow 1: "**Attendance check-in** — GPS + time. Check-out only enabled from 5 pm. → `attendance`."
- docs/07 Attendance: "Shows reverse-geocoded current address + refresh. Press-and-hold green = check-in; check-out (red) only from 5 pm; then buttons disable ("Already Checked-In/Out")."
- docs/04: "Check-out opens at 5 pm; final submit is once per zone per day." docs/03: `attendance` = "check-in/out time + GPS".
- Plan: lens-config `cfg.day.checkout_earliest_time` default 17:00, allowed range 12:00-22:00.

**Verdict: PARTLY.**

**Corrected statement.**
1. Confirmed: address (re-read, reverse-geocoded) with Refresh; hold-to-confirm sheet with a read-only time chip; four states (not checked in; checked in before 17:00; check-out available; day complete); check-out gated to 17:00; address at check-out is a fresh read. docs/07 already specifies the AMO version, so the gap is that docs/06 omits it: document the identical SR screen in docs/06 (the SR manual already shows it), do not "give" the SR a new UI.
2. Wrong: "AMO shows English where the SR shows Bangla". The AMO shows BOTH: Bangla "চেক ইন সম্পন্ন হয়েছে" (p19 left) and English "Already Checked-In/Out" (p19 right, p20, a different account/build). It is an inside-AMO inconsistency (docs/07 quotes the English form). Use one bn/en pair in the catalogue.
3. Unsupported: "probably a test capture" for 12:53 PM. The page shows a different account (ss344002, an "SS"-style id, dated 2025-11-01) with an enabled check-out at 12:53 PM; the cause (test, role-specific rule, older build, or a per-account config) is unknown. Keep it as an open question; note that lens-config's range 12:00-22:00 would allow it.
4. Inclusive 17:00: only one sample (05:00 PM chip, SR p15) and two conflicting wordings ("after 5" on the tile, "from 5 pm" in the note). Implement >= 17:00 (config) and confirm.
5. Unsupported: "clock source = device clock" and "server-skew flag" (the chip is just a read-only time); "check-in gate" (the manual only prints a prompt "please check in before starting work"; whether other tiles lock is unknown, register Q-13); the hold duration; whether a fix is stored.
6. Extra data on the page: the attendance header shows route name + visit days + date; the day record is keyed to route and business date.

**Build implication.**
- Schema: `attendance` (user_id, route_id, business_date, check_in_at, check_out_at, check_in_lat/lng/accuracy/mock, check_out_lat/lng/accuracy/mock, address_text nullable, clock_skew_s). One fix at check-in and one at check-out (docs/05, docs/04: single on-demand fused fix).
- Rule: check-out enabled when device-local Dhaka time >= cfg.day.checkout_earliest_time (17:00), re-checked on the server on sync; state machine states = not_checked_in, checked_in, check_out_ready, complete; buttons disable after use; Refresh = one new fix, capped by cfg.geo.refresh_max.
- UI: strings (bn/en) for the four states and the two sheets; address text by reverse geocode when online, else coordinates plus the last known address; press-and-hold duration cfg.app.hold_to_confirm_ms (default 1000, unknown).
- Config: cfg.day.checkout_earliest_time, cfg.day.checkin_gate (off/soft/hard; soft), cfg.app.hold_to_confirm_ms, cfg.geo.refresh_max.
- Offline: check-in and check-out are local writes, address lookup degrades offline; never block selling on a missing check-in unless the business asks (Q-13).
- Phase 2 (full SR day).

---

## Cross-entry notes for the register owner

1. Section 3 row "Attendance done-state wording" already notes the AMO shows Bangla then English; align the G-man-029 sentence with it.
2. C-09 "OTP list is view-only and server-created (Web p46)": view-only is on the page (View button only); "server-created" is not.
3. I-01 (version labels), I-18 (12:53 PM) and I-16 (two update UIs) are accurate as observations, but their "resolutions" (installed vs offered, test capture, two stages of one update) are inferences; keep them as "likely".
4. docs/04's "~90 MB APK" matches installed size (92-101 MB), the APK files are 74-80 MB: the size-budget baseline should say which.
5. The 1.0.25 release note ("Login time 2FA verification", "Sync file update without deleting") is the best primary evidence for both the OTP origin and the update-data-loss history; it is on SR p7 only.
