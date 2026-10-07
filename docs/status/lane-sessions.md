# Lane sessions (the lead keeps this current; the lane check reads it)

| Sub-lane | Session id | Model | Started | Notes |
|---|---|---|---|---|
| db | session_01KAjUS8Gz437Nx93fsiVFYX | Opus | 2026-10-05 | |
| shared (first session) | session_01SD55WuhWKuEfeuC6T4A8e2 | Sonnet | 2026-10-05 | finished; blocked by its own permission settings on the contract; do not nudge |
| shared-2 | session_01QRBncHfaoTvSqTLur5L3ZH | Sonnet | 2026-10-07 | N-002, DTO hosting, web drift |
| backend-core | session_01MJ1SsuGYYncC4RgHdgyMrb | Opus | 2026-10-07 | recycled; previous session_01MBUTbmmLSATv8rnitvbFdL is retired |
| backend-reports | session_019WhVDAstm1xPBBrp6FPKM9 | Sonnet | 2026-10-07 | |
| backend-admin | session_01JK4kErf8frNpx25mmoyPAc | Sonnet | 2026-10-07 | |
| android-core | session_01LQJTcDC8axvtsjBBQcJqhW | Opus | 2026-10-07 | recycled; previous session_01K8HqGn8ou9sxK5kC5ZgG3j is retired |
| android-core-ui | session_01PompFHeojjtrnV78dsjmsB | Sonnet | 2026-10-07 | N-023 UI kit first, published in slices; split from android-core because 3 lanes wait on it |
| android-sr-a | session_01Gyh9KAacFpMFb35wUDq21c | Sonnet | 2026-10-07 | |
| android-sr-b | session_01DCSzXKooAFuYYEGy9UAMSN | Sonnet | 2026-10-07 | |
| android-geo-dpc | session_018dVKqTVsFaUht9A1J7XEst | Opus | 2026-10-07 | |
| android-print | session_012CxBfkJ79PW16h2NpNtPQv | Opus | 2026-10-07 | READY TO RECYCLE 2026-10-07 04:51, retired; all remaining rows blocked on android-core PrintLedger and SR screens; start a replacement when PrintLedger is on INT (handoff docs/status/android-print.md) |
| web-admin | session_018iHvNJSMqCk8eLitCk9wzU | Sonnet | 2026-10-05 | |
| web-config | session_0146veK2iXvmHGwiPBpbBJ2k | Sonnet | 2026-10-07 | |
| web-dashboard | session_01Sk9wwEshZ57sQafoEyccP7 | Sonnet | 2026-10-07 | READY TO RECYCLE 2026-10-07 04:51; replacement to start when contract v1.2 is on INT (handoff in docs/status/web-dashboard.md) |
| infra | session_011BobrrmxjEerwAMUs6AzN2 | Opus | 2026-10-05 | |
| laptop operator | session_01UbRHnSorx4s1XARa6aN1Tg | Opus | 2026-10-06 | never nudged; runs only owner-approved laptop tasks |

Wave 2 (not started): android-amo, android-tso, qa: start when the SR slice (login, bundle, visit, sale, memo, sync) runs end to end on dev.
