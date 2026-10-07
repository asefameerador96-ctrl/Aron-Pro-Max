# Integration train (integrator)

INT only moves by fast-forward to a green candidate. Owner of this file: integrator.

| time UTC | INT sha | lanes merged (head sha) | notes |
|---|---|---|---|
| 2026-10-07 06:36 | 7128431 | none (candidate lane/train-20261007T0636 = INT head) | INT verdict: red (Android debug APKs, unit tests and lint) |
| 2026-10-07 08:48 | f1d77ed | candidate lane/train-20261007T0848 (backend-admin 3c40e33, backend-reports dc0ab6c, android-core 93632be, web-config 3fc8ae1, infra 6485bb6, lead-contract-v1-3 e0feeb3) | optimistic batch; CI red only on the Android job (same as INT), not promoted |
| 2026-10-07 09:42 | 699d4dd | backend-core 7722643 (green ci run 37599857144; INT merged in, docs-only difference, so no new run) | promoted alone, fast-forward; INT 88ede0b -> 699d4dd |
| 2026-10-07 09:50 | d0afa69 | infra f9429de (green run 37602080027) merged with INT 319b24e | MISTAKE: INT carried 37 non-docs backend files the head lacked, so the merge was untested; pushed anyway as fast-forward. Verification run 37603167252 pending. Lead informed. |
| 2026-10-07 10:19 | 12a823e | candidate lane/train-20261007T1000, ALL JOBS GREEN (run 37604459973): backend-admin 3c40e33, android-core 3ce1913, android-sys 2830441, android-core-ui 5d80533, web-config 10e6857, infra 5308ee7, lead-contract-v1-3 e0feeb3 | fast-forward INT c3db837 -> 12a823e. INT is green. Earlier verification run of d0afa69 (infra merge): only the old Web and Android reds, union fine. |

## Open reds

INT-head verdict (lane/train-20261007T0636 = INT 7128431, ci run 37583161952): **failure**. Only one job red; all others green (Detect changed areas, Repository gates incl. Semgrep, Contract lint, Shared db and backend, Release APKs and APK size gate, Web, Container images, Infra validation).

| red job | owner | note |
|---|---|---|
| Android debug APKs, unit tests and lint (step "Assemble the three debug APKs, run Android unit tests and lint") | android lanes (android-core / android-sr-a / android-sr-b / android-geo-dpc) | failing step 6m22s; the log tail the MCP returns holds no error lines, lanes read it from the run page |

Lane heads at 2026-10-07 08:45 UTC (INT 97feb99): none ready. Latest CI per head: backend-reports dc0ab6c failure, backend-admin 3c40e33 failure, infra 6485bb6 failure, web-config 3fc8ae1 failure, lead-contract-v1-3 e0feeb3 failure, android-sys 437f865 running; android-core-ui 41bd10b and android-sr-b 37848fe not re-checked yet. Heads with 0 commits ahead of INT: android-print, android-sr-a, web-admin, web-dashboard.

Note: five-hour usage limit stalled all lanes 06:45-08:30 UTC.

### 09:01 UTC update (INT f1d77ed)
Red jobs per lane head (latest completed run on that exact head):
- Only the Android job (INT's red): android-core 93632be, android-sys 2830441, backend-admin 3c40e33, backend-reports dc0ab6c, lead-contract-v1-3 e0feeb3, web-config 3fc8ae1.
- NEW reds beyond INT's (not ready): infra f1db789 = Container images (build and runtime smoke); android-geo-dpc 7b0b015 and android-print 8afb986 = Web (lint, types, tests, build, e2e) + Android.
- Still running, no verdict: android-core-ui 5d80533, android-sr-a 3828ab0, android-sr-b 977f8e8, backend-core 1e27c8b (Android red so far).
Candidate lane/train-20261007T0848 failed only on the Android job; per the lead the Android fixes (core-media consumer-rules.pro, MissingPermission in ConnectivityFlush.kt:61 and SupportUpload.kt:160) are still arriving on lane/android-core. Next candidate when those land.

### 10:19 UTC: INT is green (12a823e). Open reds from here are per-head only (android-sr-a Web/release gate, android-geo-dpc and android-print Web, android-sr-b new head 4fdcf13, db d868dba, backend-core 8d7fd7f, backend-reports bd866d6 pending runs).
