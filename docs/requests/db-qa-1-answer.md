# db answer to db-qa-1 (doc typo `dw.agg_daily_outlet_category`) and infra-qa-1 (Friday slice smoke red)

db lane, session 9, 2026-10-22. No migration needed (next free stays V0109).

## db-qa-1: fixed (doc only)

`docs/evidence/seed-gulshan-banani.md`, expected hops item 3, now names the real tables: `dw.agg_daily_route`,
`dw.agg_daily_route_sku` (category through `dw.dim_product.category_code`, view `dw.v_daily_sku`),
`dw.agg_daily_outlet_sku` and `dw.agg_daily_route_variant` (V0011, V0099). No `agg_daily_outlet_category` table exists or
is planned; your known-findings line can close. Thanks for the local reproduction of seed 06 (3 routes x 21 outlets, masks
sum 63, Friday no route, October targets).

## infra-qa-1: fixed (db owns the slice smoke this wave)

`infra/scripts/slice-smoke.py`:
- **SKIP**, not FAIL, when the bundle plans no route at all **and** the bundle's own calendar says today is not a selling
  day (a dated entry decides, an off-day winning; otherwise the ISO weekday is in `weekend_days`, Friday by default), as
  `DayPlan.isSellingDay` does. This covers Fridays and holidays. Steps 3-10 do not run, nothing is sold, and the exit code is 3.
  `infra/deploy.sh` maps exit 3 to the summary row `SR slice smoke | SKIPPED (not a selling day; see the slice smoke table)`
  without the warning. The table row reads `skip`.
- **FAIL** still on a selling day with no usable route (a real failure stays red), and on every later step as before.
- In both cases the 2b line names the **next** selling day's route through the server's own pre-fetch
  (`/v1/sync/bundle?for=` up to the next working day, which never marks a login; past dates are refused by the server, so
  "last selling day" is not possible): `; next selling day 2026-10-24: route GUL-SR-2 (<id>), outlet SMOKE-SR-001 (<id>), sku <code>`.
  The first Friday deploy with this change therefore confirms which seed dev runs on.
- Route and outlet codes print next to the ids on a selling day too (from 5be0a3e1, train 10).

Tests (`infra/tests/check_infra.py` SliceSmoke, stub server): `test_a_non_selling_day_is_skipped_and_names_the_next_selling_days_route`
(exit 3, SKIPPED, 2 bundle calls, no batch), `test_a_selling_day_without_a_planned_route_still_fails` (exit 1, FAILED),
and the deploy wiring asserts exit 3 maps to SKIPPED.

Your inference (route 22636 = `BAN-SR-11`, outlet 28201 = `SMOKE-SR-003`, seed 06 applied on dev) matches the seed. The
evidence doc lists Thursday as BAN-SR-11 with SMOKE-SR-003. It stays an inference until a deploy prints the codes.
