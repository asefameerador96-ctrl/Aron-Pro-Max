# Lane sessions (the lead keeps this current; the lane check reads it)

| Sub-lane | Session id | Model | Started | Notes |
|---|---|---|---|---|
| db | session_017dmQw2byPhYrFKrGJrEMRj | Opus | 2026-10-07 | recycled 05:35; previous session_01KAjUS8Gz437Nx93fsiVFYX is retired (READY TO RECYCLE 05:33) |
| shared (first session) | session_01SD55WuhWKuEfeuC6T4A8e2 | Sonnet | 2026-10-05 | finished; blocked by its own permission settings on the contract; do not nudge |
| shared-2 | session_01QRBncHfaoTvSqTLur5L3ZH | Sonnet | 2026-10-07 | N-002, DTO hosting, web drift |
| backend-core | session_01465rpZSgSrMTU8CACwuEYx | Opus | 2026-10-07 | recycled 10:25 (fourth session); previous session_01FfFvStuQXyZNg6QM9rndaD is retired (READY TO RECYCLE 10:25, head c59a703d); earlier sessions retired (01MJ1SsuGYYncC4RgHdgyMrb, 01MBUTbmmLSATv8rnitvbFdL) |
| backend-reports | session_019WhVDAstm1xPBBrp6FPKM9 | Sonnet | 2026-10-07 | |
| backend-admin | session_01TogQKB1R6sgWVTafC9DMYG | Sonnet | 2026-10-07 | recycled 05:02; previous session_01JK4kErf8frNpx25mmoyPAc is retired (READY TO RECYCLE 05:01) |
| android-core | session_01Xx4ADUeVGTHNrVSqXU3tjh | Opus | 2026-10-07 | recycled 08:39 (fourth session); session_01EcjRGi19bkvqTfp5RQnJU2 retired (READY TO RECYCLE 08:39) |
| android-core-ui | session_01F7k2exq6bgrBGdZAmvG9Ey | Sonnet | 2026-10-07 | recycled 08:40; session_01PompFHeojjtrnV78dsjmsB retired (READY TO RECYCLE 08:39) |
| android-sr-a | session_01TyF3Y1MGEyGN2Ke8EHwZUG | Sonnet | 2026-10-07 | recycled 08:46; session_01Gyh9KAacFpMFb35wUDq21c retired (READY TO RECYCLE 08:46) |
| android-sr-b | session_019i1dfbm7pSrMDPXskSLY3v | Sonnet | 2026-10-07 | recycled 08:39; session_01DCSzXKooAFuYYEGy9UAMSN retired (READY TO RECYCLE 08:38) |
| android-sys | session_01GXvRHr1e5rHuj8p68xW15F | Opus | 2026-10-07 | camera and photo pipeline, media queue, location permission UX, update check, PDA support, language switch, logout: 8 T1 rows moved from android-core-ui |
| android-geo-dpc | session_018dVKqTVsFaUht9A1J7XEst | Opus | 2026-10-07 | FINISHED 08:50: every row built and Opus-checked (head 7b0b015f on lane/android-geo-dpc); device halves DEVICE-PENDING; no replacement started (nothing left to build); waits for wiring call sites in other lanes (docs/requests/android-geo-dpc-wiring.md) |
| android-print | session_01FyHPQAii1xCvR7SvMwQEva | Opus | 2026-10-07 | recycled 05:21 after android-core put RoomPrintLedger on INT; previous session_012CxBfkJ79PW16h2NpNtPQv is retired (READY TO RECYCLE 04:51) |
| web-admin | session_018iHvNJSMqCk8eLitCk9wzU | Sonnet | 2026-10-05 | |
| web-config | session_0146veK2iXvmHGwiPBpbBJ2k | Sonnet | 2026-10-07 | |
| web-dashboard | session_01RM4v6DRfjmfAbfrDi17Jzo | Sonnet | 2026-10-07 | recycled 05:01; previous session_01Sk9wwEshZ57sQafoEyccP7 is retired (READY TO RECYCLE 04:51) |
| infra | session_01Th2LZgi7Jg7dxUX3gmwFQ2 | Opus | 2026-10-07 | recycled 06:41 (third session); session_01CDohyiiiYjHVdhw5DSeqPc retired (READY TO RECYCLE 06:41); session_011BobrrmxjEerwAMUs6AzN2 retired earlier |
| integrator | session_017ASTyJnQ6z71B1uoc447YL | Sonnet | 2026-10-07 | recycled 10:50 (second session; integration train conductor, docs/lanes/integrator.md; recycle at ~300k); session_01HMc2Bcq7MRYf8xgj423pKU retired and archived (READY TO RECYCLE 10:49, 439k context) |
| laptop operator | session_01UbRHnSorx4s1XARa6aN1Tg | Opus | 2026-10-06 | never nudged; runs only owner-approved laptop tasks |

Wave 2 (not started): android-amo, android-tso, qa: start when the SR slice (login, bundle, visit, sale, memo, sync) runs end to end on dev.

**Throttle (2026-10-07 08:38 UTC, usage constraint):** woken automatically: integrator, backend-core, db, android-core, android-sr-a, android-sr-b, android-core-ui, android-geo-dpc, android-print, infra. ON HOLD until the lead releases them: web-config (released 2026-10-07 09:41 UTC for ONE job only: de-flake web/e2e/config-journeys.spec.ts, then back on hold), web-admin, web-dashboard, backend-reports, backend-admin, android-sys, shared-2.
