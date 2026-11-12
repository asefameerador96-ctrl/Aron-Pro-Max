# Status: lane portal-loyalty-api (Lane L-A), started 2026-11-12

Branch `lane/portal-loyalty-api` from INT `claude/wonderful-thompson-k6ejnf` (f46f8047f). Push only that branch; no pull request. Spec: `docs/status/portal-loyalty-brief.md` (its s10 defaults are the answers until the owner or the lead says otherwise). The field does not change (loyalty in the apps stays deferred, docs/27: no earning, no redemption, memo offer line zero, bundle `programmes` null).

## Done
- Train 1 (schema and contract; D-LOY-API-01 to 03): V0158 to V0162 (programme draft status and lifecycle guard, immutable versions with tiers/products/scope, status trail, gift journal, enrolment batch ledger, module `loyalty` with `targets` grants carried over, dw.v_programme / v_programme_version / v_gift, view contract 1.7), contract 1.13.0 (request file docs/requests/contract-1.13-loyalty-portal.md, ruling D-LEAD-CONTRACT-113-01 to be recorded by the lead), regenerated slices, web types, data dictionary and dw view contract, `LoyaltyPortalV0158Test`, `LoyaltyPortalContractTest`.

## Next
- Train 2 (routes): `AdminLoyalty.kt` in backend/masterdata modelled on `AdminOffers.kt`; module map `programmes`/`gifts` from `targets` to `loyalty` (AdminModules.kt), BRAND/WM/DMO/TSO read routes in PortalLevels, tests modelled on `AdminOffersTest`, RouteInventory/ScopeLeak/ContractConformance entries. Starts after the lead's gate of train 1.
- Later: progress/tier/redemption read endpoints with `dw.v_enrolment` and `dw.v_loyalty_balance` (not in 1.13.0).

## Deploy hazard
See D-LOY-API-03 (1): land with the web head that adds `loyalty` to GRANT_MODULES.

## Owner questions (defaults applied, brief s10)
Q1 units of thresholds (default STD as stored, unit shown, `std` the only basis), Q2 programmes covered (Astha and Diamond League definitions; Superstar/campaign as definitions only), Q3 activation (SUPERADMIN only), Q4 gift taka value (points only), Q5 overlap (existing per-programme rule), Q6 readers, Q7 reports (progress views only), Q8 screenshots/workbook.
