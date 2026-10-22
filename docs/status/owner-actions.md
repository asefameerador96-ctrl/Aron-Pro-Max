# Owner action list (lead keeps it current; the evening report quotes it)

Only what needs the owner's account, approval or hands (CLAUDE.md rule 7). Newest first.

## 2026-10-15 (owner back; Honor X5c Plus in hand)

| # | What | Why |
|---|---|---|
| A | Download `aron-release-signed-dev-722` (GitHub, Actions run 722, https://github.com/asefameerador96-ctrl/Aron-Pro-Max/actions/runs/37736394003), unzip, install `aron-sr-0.1.722-dev.apk` on the Honor | first real-phone run of the SR slice (D-01, D-02) |
| B | Say `owner approves dev enrolment off` (path A) **or** start the laptop session so a token can be minted (path B) | the phone must pass enrolment before login; path A is fastest and is flipped back on for D-04 |
| C | Read `aron-dev-seed-password` in the Azure portal (Key Vault in rg-aron-dev); never paste it in chat | login sr1001 and tso1001 |
| D | Confirm or change the proposed target (feature-complete on dev 2026-10-20, buffer 2026-10-22) | forecast |
| E | Tell the lead the weekly usage percentage; turn ultracode off for the lead session | pacing of the released lanes |
| F | Policy (low priority, not blocking): may an **approved negative dues correction** take an outlet balance below zero (a credit)? | backend-admin built dues corrections (ADMIN proposes, SUPERADMIN decides) with no balance floor. Default until you answer: allowed, and the approval response flags it when the balance goes negative. |
| G | (2026-10-18) **No longer needed:** the APK size baseline refresh. The harness refused the integrator's edit of `tools/ci/apk-size-baseline.json` (a CI gate change; nobody worked around it) and android-tso instead trimmed ~0.74 MB (SR release armv7 5.04 -> 4.30 MB, -1.7% vs baseline), which is on INT 54ff64f2 with a green size gate. Open item for you: the release smoke on a phone (D-SIZE-01 in docs/status/device-checks.md: install release, cold start, SQLCipher open, one sale, sync, print), because the trim changes native-lib packaging and R8 rules. |
| H | (2026-10-18) Still open, your word only: **"promote hardening"** or keep holding (D-HARD-HOLD-01; N-059 restore-mints-generation fix lives only on the held branch). Nothing else for you to do: when you say it, the lead first renumbers the hardening migrations `V0084`..`V0087` to the next free numbers, because lane fixes already took `V0084` (PDA age-limit key) on INT (D-MIG-RANGE-01), then the integrator promotes on a new candidate branch. |
| I | (2026-10-18) Dev web check of the PII reveal, 5 minutes, needs your admin login and authenticator (no lane has them; the laptop operator session cannot do the TOTP): sign in as `admin1001` on the dev web, open `/admin/audit`, press **Show personal data**, enter password + authenticator code. Expect: a countdown of about 15 min, personal columns unmasked on a report with pii columns, masked again after the window or after a page reload that refreshes the token; sign in as an ANALYST or SUPPORT user and confirm there is no button. (Also tells us the audit viewer works on real data.) Not blocking anything | PII reveal V0086 is live on dev (INT 8edc7124 + web UX 9d1d8e18); only the e2e on the mock proves the screen so far |


Older items below still stand unless a row above replaces them (rows 4, 5, 6, 11, 12 become live once the laptop session exists).

| # | Since | What | Why | Who helps |
|---|---|---|---|---|
| 1 | 2026-10-07 | Tell the lead the **weekly usage percentage** (Claude usage settings) | the seven-day window shows a warning; the plan is sized from it | lead |
| 2 | 2026-10-07 | Turn **ultracode off** for the lead session (it says token cost is not a constraint) | the five-hour limit was hit at 06:45 UTC and usage is binding | owner setting |
| 3 | 2026-10-07 | Yes or no: **restore drill** in the test account (about USD 0.3, temporary copy deleted in the same run) | proves backups restore; infra waits for the phrase "owner approved restore drill" | infra |
| 4 | 2026-10-07 | Lab phone on USB: data cable, USB debugging on, accept the RSA prompt (`adb devices` must list it) | D-P1 (core-printing instrumented test, about 3 minutes) and every device check | laptop operator **Owner 2026-10-07 10:50 UTC: cannot do it today, moved to 2026-10-08.** |
| 5 | when the SR app installs | **D-P2a** printer check: SR app on the A06 plus the MP-58N (stock slip print, printer switched off half way, paper out); steps in docs/status/device-checks.md | print is T1 and needs the real printer | android-print (waits for the USB phone, now 2026-10-08) |
| 6 | when the SR app installs | **D-UI-01** outdoor legibility: A06 outdoors at midday, full brightness, 5 minutes: home, sale entry, review, memo readable without shading the screen | docs/32 s2a | android-core-ui (waits for the USB phone, now 2026-10-08) |
| 7 | later | Google Cloud Maps key: add the SR app package name and signing certificate to the key's restrictions (SR now has the on-tap map) | docs/24 R19 | infra gives the values |
| 8 | before making the repo private | set a GitHub Actions spending limit | private repos meter CI minutes | owner |
| 9 | 2026-10-10 | review the full-size dev Azure resources kept for a week (docs/28 exception) | cost | infra |
| 10 | when ready | review the design preview (sent 08:39 UTC) and say what to change; brand colour if AKTCL has one (Q-UI-13) | docs/32 | design |
| 11 | when the lab phones are on USB | device-owner steps of the geo and device-owner lane (docs/status/device-checks.md, android-geo-dpc section: enrolment on a factory-reset test phone, app blocking, spoofing apps) | N-021/026/029/030/032/034/035 device halves | android-geo-dpc notes, laptop operator (waits for the USB phone, now 2026-10-08) |
| 13 | 2026-10-07 | **Enable the Play Integrity API on the Google Cloud project used for Firebase (FCM) and link the SR app to it in Google Play Console** (the app package must be linked to a Google Cloud project so the server can decode integrity verdicts); tell the lead when done | backend-core decodes Play Integrity now (N-027); until the link exists verdicts stay unevaluated, so on dev the integrity and enrolment gates stay OFF (accepted and flagged) and the integrity device check (D-04 to D-05 family) can only be a record-only check | owner (Play account), infra prepares the Key Vault secret |
| 12 | 2026-10-07 | **Start a fresh laptop Claude session** in the repository folder before the device checks tomorrow (the old laptop operator session is gone: its environment was deleted at 13:33 UTC) | USB, adb and az steps run on the laptop; the cloud sessions cannot reach it | owner |

## 2026-10-22 (owner's directive on portal levels, dashboard, reports, app fixes, MDM: `docs/34`)

| # | What | Why |
|---|---|---|
| J | **Upload here** (as you uploaded the brief): the report explanation Word docs and the September 1–30 Excel samples from `D:\Aron Web Portal Reports`, and screenshots plus the feature list of the MDM console from `D:\Aron MDM` | both folders are on your laptop; no cloud session can read them. I file sanitised specs in `docs/reports/`; the raw workbooks never go into the public repository |
| K | Answer docs/34 s6: Q1 Distance Covered unit (metres along the GPS track is the default), Q2 bidi in packets (sticks per packet per SKU), Q3 lighters/matches on dashboards in pieces/dozens (the brief) or boxes, Q4 brand users: hide the non-brand cards (default) | each has a default applied until you answer |
| L | Pack pictures: run the loader once with your admin login on the laptop (`docs/runbooks/load-packshots.md`, about 10 minutes) **or** upload per SKU in the admin portal **or** say "repository private now" | the 42 real pictures cannot sit in a public repository (D-PACK-04); build 1258 bundles only three dev crops, which is why most packshots are blank |
| M | When the android-print lane's diagnostics build is ready (I will name the artifact): pair the MP-58N in Android Bluetooth settings, open Settings → Printer in the SR app, run the three test pages, tap Export and send me the text | the fault cannot be located without the printer |
| N | Still open from before: "promote hardening" (H), the PII reveal check (I), the weekly usage percentage (1) | unchanged |
| O | Confirm the Durga Puja holiday date used by the Gulshan–Banani dev seed (`db/seed/06_dev_gulshan_banani.sql`, working-day calendar); say "seed 06 Durga Puja date is right" or give the date | db #8's close-out note (19:47 UTC): a wrong date mis-marks one working day on dev only; nothing else depends on it |
| P | Three dependabot pull requests are open against the integration branch since 2026-10-18 (#7 GitHub Actions minor/patch, #9 Gradle minor/patch, #11 npm minor/patch). Decide: close them, or turn dependabot pull requests off in the repository settings (keep the security alerts). Nobody merges them: they bypass the train, and the lead folds needed bumps through lane/lead-* branches (as with next 16.4.2 today) | lane check 18:50 UTC; no action is taken on them until you decide |
