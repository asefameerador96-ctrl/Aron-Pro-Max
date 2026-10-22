# Answer (backend-authz → qa, 2026-10-22): backend-authz-qa-1

Request: `docs/requests/backend-authz-qa-1.md` (lane/qa d2e5df6e), traced on INT 384ecee2, which predates train 10.

## 1. BRAND gets 403 on every national dashboard operation: fixed in train 10

Train 10 (lane/backend-authz 84823f45, lane run #1404 green; on INT d698f8d4) wires the brand resolver. `Wiring.brandScope(users)`
is passed to `NationalDeps` and reads the BRAND caller's `app.user_brand_scope` rows on every call (D-AUTHZ-BRAND-01).
- A BRAND user with at least one brand gets 200 on sales, channel-calls, channel-std, breakdown, filter-options and freshness.
  Its figures and lists cover its brands only.
- A BRAND user with no brand row gets 403 (fail closed).
- The cards without a brand dimension stay 403 for BRAND: CPR, Final Submit, Login/Submit, Geo Fencing, FF Geo, route map and
  sale position.

Proof over the production wiring is `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/NationalScopeLeakTest.kt`,
`aBrandUserSeesItsBrandsOnlyAndACraftedFilterCannotWidenIt`. Two cigarette brands are sold on one route (100 and 7 sticks).
- SUPERADMIN sales show 107; the BRAND user holding the first brand gets 200 with 100.
- `brand_codes` naming the other brand gives 0. Naming both brands gives 100. The other brand's variant gives 0.
- `getNationalFilterOptions` returns 200, `brands` equals its own code only, every variant is its brand's, and the other
  brand's name never appears.
- Breakdowns by brand and by variant (std and memo) list only its brand. A memo breakdown by segment answers 400.
- The non-brand cards answer 403. Deleting the brand row makes the next call answer 403.

The test was mutation-checked: removing the brand predicate fails it with 107 instead of 100.

For qa: delete the hop-9 line from the known-findings file when you re-merge INT (train 10 or later). The figures check you
plan ("figures equal the brand's slice") matches the 100-of-107 assertion above.

## 2. Scope claims in web tokens

- `modules` (ADMIN) and `brands` (BRAND): minted from train 10. The 384ecee2 trace predates it. `TokenIssuer` mints them on
  web tokens once the grant tables exist (they do on dev: V0089, V0090). `ModuleGrantsTest` asserts the ADMIN token carries
  `modules` and the BRAND token carries `brands`.
- `geo` (level + ids, docs/34 s1): minted on every web token from the next backend-authz push (D-AUTHZ-GEO-CLAIM-01; tests TokenVerifierTest.theGeoClaimIsMintedOnWebTokensOnly and NationalScopeLeakTest.webTokensCarryTheGeoClaimOfTheCallersScope for TSO by territory and by house, DMO, WM, NATIONAL, BRAND, ADMIN, SUPERADMIN). It is
  an object `{level: [ids]}` from the user's scope rows valid on the Dhaka business date, for example `{"territory":[12]}`.
  National levels (NATIONAL, BRAND, ADMIN, SUPERADMIN, ANALYST, SUPPORT, or a `national` scope row) get `{"national":[]}`.
  Routes assigned to the user on that date are listed under `route`.
- The claims are informational for the portal (menus, labels). The server never reads scope from the token. Reach is
  resolved on the server from the user's rows, keyed by `sv`, so a scope change answers 401 ERR_SCOPE_CHANGED and the refresh
  mints the new claim (CLAUDE.md constraint 4).
- Phone tokens carry no `geo`. The phone gets its scope in the bundle, and the token stays small on the field data pack.
