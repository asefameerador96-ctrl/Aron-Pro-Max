# android-sr-b: all 22 rows blocked (2026-10-07)

Checked against `docs/status/*.csv` on `origin/claude/wonderful-thompson-k6ejnf` at 010e2ee.

Root blockers (first unmet dependency of each chain):
- **N-023** (android-core, Shared Compose UI kit): not in `core-ui`. Blocks F-SR-023 (sale entry) and F-SR-060, and through F-SR-023 also F-SR-022, 025, 026, 027, 029, 033, 053, 069.
- **F-SR-014** (android-sr-a, Stock load): blocks F-SR-050, 081, and through 050 F-SR-010, 053, 068.
- **F-SR-017** (android-sr-a, open visit and geo check): blocks F-SR-054, 057, 067.
- **F-SR-028** (android-print, Print memo): blocks F-SR-030, 032, 033, 036.
- **F-SYS-009** (android-core, reconciliation): blocks F-SR-034, 035. Also F-SYS-060 and F-API-008/025 (backend) for 026, 032, 035, 054.

Also missing and needed by every screen: the app wiring of the per-user Room database (planned with F-SYS-006).

Ask: land N-023 first (it unblocks the first row of my chain, F-SR-023, on Day 2). I will start F-SR-023 the moment it is on INT.
