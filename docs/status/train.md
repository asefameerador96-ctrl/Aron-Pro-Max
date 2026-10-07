# Integration train (integrator)

INT only moves by fast-forward to a green candidate. Owner of this file: integrator.

| time UTC | INT sha | lanes merged (head sha) | notes |
|---|---|---|---|
| 2026-10-07 06:36 | 7128431 | none (candidate lane/train-20261007T0636 = INT head) | INT verdict: red (Android debug APKs, unit tests and lint) |

## Open reds

INT-head verdict (lane/train-20261007T0636 = INT 7128431, ci run 37583161952): **failure**. Only one job red; all others green (Detect changed areas, Repository gates incl. Semgrep, Contract lint, Shared db and backend, Release APKs and APK size gate, Web, Container images, Infra validation).

| red job | owner | note |
|---|---|---|
| Android debug APKs, unit tests and lint (step "Assemble the three debug APKs, run Android unit tests and lint") | android lanes (android-core / android-sr-a / android-sr-b / android-geo-dpc) | failing step 6m22s; the log tail the MCP returns holds no error lines, lanes read it from the run page |

Lane heads at 2026-10-07 08:45 UTC (INT 97feb99): none ready. Latest CI per head: backend-reports dc0ab6c failure, backend-admin 3c40e33 failure, infra 6485bb6 failure, web-config 3fc8ae1 failure, lead-contract-v1-3 e0feeb3 failure, android-sys 437f865 running; android-core-ui 41bd10b and android-sr-b 37848fe not re-checked yet. Heads with 0 commits ahead of INT: android-print, android-sr-a, web-admin, web-dashboard.

Note: five-hour usage limit stalled all lanes 06:45-08:30 UTC.
