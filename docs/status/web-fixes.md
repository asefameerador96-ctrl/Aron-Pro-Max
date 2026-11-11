# Lane web-fixes W1 (2026-11-11)

Record: D-WEB-FIX-01 in DECISIONS.md.

- MDM flake (docs/20 P5): `form-ok` sits in a box the post-action refresh removes, plus clicks before hydration. Spec fixed (hydrated, then assert the durable result). Old spec 2/6 red under CPU load, fixed spec 16/16 green.
- v13-followup end-date test: hydration wait added.
- Audit: 25 specs, 47 first-action-after-goto sites now behind `hydrated()` (plus 6 in mdm and v13-followup = 53 `hydrated()` lines in specs); specs without actions after goto listed as safe in D-WEB-FIX-01; mid-test interactions are residual risk.
- Lows of D-DISC-WEB-04: shared `BUDGET` in `e2e/helpers.ts`; `Money` non-plain English prints "Tk" (dead on shipped pages: the overview hero is `plain`; proven by tests/money-sign.test.tsx); displayUnit tests.
- Gates: lint 0, typecheck 0, vitest 2011 green, build 0, changed e2e files 3x green, final full e2e 345 passed / 6 failed = 5 known pixel snapshots (config-tiers A/B/C, national-shots x2) + sku-pack:29 (unguarded mock-state read, fixed with expect.poll, green 6/6 under load and 3/3 plain). Round 2 (lead FAIL): enrolment asserts the empty active list, factory reset asserts its own command id, tests/money-sign.test.tsx added, money-float-scan 0 open, test_gates OK.
