# Owner action list (lead keeps it current; the evening report quotes it)

Only what needs the owner's account, approval or hands (CLAUDE.md rule 7). Newest first.

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
| 13 | 2026-10-07 | **Link the SR app in Google Play Console for Play Integrity** (the app package must be linked to a Google Cloud project so the server can decode integrity verdicts); tell the lead when done | backend-core decodes Play Integrity now (N-027); until the link exists verdicts stay unevaluated, so on dev the integrity and enrolment gates stay OFF (accepted and flagged) and the integrity device check (D-04 to D-05 family) can only be a record-only check | owner (Play account), infra prepares the Key Vault secret |
| 12 | 2026-10-07 | **Start a fresh laptop Claude session** in the repository folder before the device checks tomorrow (the old laptop operator session is gone: its environment was deleted at 13:33 UTC) | USB, adb and az steps run on the laptop; the cloud sessions cannot reach it | owner |
