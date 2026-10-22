# backend-authz-qa-1 — BRAND user gets 403 on every national dashboard operation; no scope claims in web tokens

From lane qa (`DataFlowTraceTest` hops 8-9, `docs/evidence/data-flow-trace-2026-10-23.md`).

## 1. BRAND level cannot read the dashboard (docs/34 s1 level 4) — CONFIRMED by the Opus checker
A BRAND user with one `app.user_brand_scope` row calls `POST /v1/dashboards/national/{sales,cpr,channel-std,breakdown}`
with `{"date":"2026-10-24"}`: every answer is `403 ERR_FORBIDDEN`. `PortalLevels.kt:50-54` lets BRAND call sales,
channel-calls, channel-std, breakdown, filter-options, freshness, but `Wiring.kt:203` builds
`NationalDeps(NationalDashboardService(db, clock), reach, guard, clock = clock)` with no `brands` resolver, so
`NationalDashboardApi.kt:33` refuses with "no brand scope" (default resolver returns null). Known to backend-reports
(`backend-reports.md`: "default none until authz lands its brands claim"). Row 3 (brand scope) is the owner. Failing test:
`DataFlowTraceTest` hop 9 `BRAND user can read the product cards of its brands` (known-findings file; delete the line
when fixed, the test then asserts 200 and, still to add, that the figures equal the brand's slice).

## 2. Web tokens carry no `geo`, `brands`, `modules` claims (docs/34 s1)
TSO web token claim names: `amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname` (values not
printed); the SR app token adds `did`, `dvu`. docs/34 s1 says scope travels as claims `geo`, `brands` (BRAND), `modules` (ADMIN).
Today scope is resolved on the server from the user and `sv` (scope_version), which satisfies CLAUDE.md constraint 4 (the
client sends no scope). If the claims are still required, row 2 (module grants, train 10) is the place; if not, amend
docs/34. The trace asserts only the TSO case (hop 8).

Data points that are fine: WM, DMO and TSO callers asking for another division's ids get figures cut to their own reach
(`200` with zero units, never the other division); other-division route-map, ff-geo popup and sale-position answer 404;
NATIONAL cannot Final Submit (403); SR on the web login `403 ERR_AUTH_PORTAL_NOT_ALLOWED`; SR app token on the dashboard 401.
