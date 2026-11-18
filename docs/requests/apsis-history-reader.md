# Request to the server lane: a scoped reader of the Apsis history (lead ruling, 2026-11-18)

Consumer: lane M-APSIS1. Status: the role and views exist in the provisional migrations (`tools/apsis-migration/provisional-migrations/V0262`); **nothing in the API or web reads them yet**. This request is what the server lane builds.

## The role and the login
- Database role `hist_ro` (NOLOGIN): `SELECT` on exactly `dw.v_hist_sales_line`, `dw.v_hist_sales_day`, `dw.v_hist_visit_geo`, `dw.v_hist_attendance`, `dw.v_sales_with_history` and `USAGE` on schema `dw`; no other privilege on anything (a test runs the tool's suite as `hist_ro` and proves `app.*`, other `dw.*`, `apsis_*` and every write are refused). It is outside the five-role grant map, so `app.apply_db_role_grants()` neither grants nor revokes it.
- A separate **login** for the API's history pool: the Entra principal of the API's managed identity as a PostgreSQL role `aron_hist_api`, a plain member of `hist_ro` (no secret; an Entra token as for the other pools). infra-final wires the identity and the grant `GRANT hist_ro TO aron_hist_api`. The pool does `SET ROLE hist_ro`.
- **The sync code never holds this pool.** `api_rw`, `worker_rw`, `jobs_rw` have no privilege on the history relations (grant-map entries in V0262; the lane test runs `apply_db_role_grants()` and checks every role).

## What the server lane builds
1. A second small read-only connection pool (history pool) used only by the analytics read path of reports that include history: monthly trend, same month last year, outlet sales history, route productivity (Dec 2025 - Sep 2026). Reports for today, week, live, dues, targets and loyalty never read it.
2. Reports read `dw.v_sales_with_history` (quantity by business date, outlet, SKU, route; `source` is `live` or `apsis`; archive up to `apsis_ctl.setting history_until`, live after it). It has **no money column** (the live aggregate has none): value reports (taka) need a live money aggregate first or read `dw.v_hist_sales_day.gross_mtk` for the history part only.
3. **Scope** is applied by the same server-side scope filter as every other dw read, on the scope columns every view carries: `zone_id`, `territory_id`, `division_id`, `wing_id` (derived from the token, never sent by the client). A row whose geography could not be resolved has NULL scope ids and is returned **only** to national roles (an equality filter on a scope id never matches NULL; national readers add `OR territory_id IS NULL`). Attendance scope is the user's home zone as of the migration load.
4. Reference SQL (as `hist_ro`): `SELECT business_date, sum(qty_base) FROM dw.v_hist_sales_line WHERE territory_id = ANY($1::bigint[]) OR ($2 AND territory_id IS NULL) GROUP BY 1`, with `$1` the user's territory ids, `$2` true for national roles.
5. Tests (server lane): a TSO sees only their own territory; a DMO only their division; a wing manager only their wing; NULL-scope rows only for National Users and Super Admin; no endpoint accepts a scope id from the client; the history pool's login cannot write; sync endpoints work with the history pool down.

## Caveats the reports must show
History is attributed to the geography as of the migration (past moves are not kept); history rows include zero-sale rows that the live aggregate may not, so "calling outlets" across the boundary can differ; a single global `history_until` (owner question Q16: per-wave cutover needs a per-wave date).
