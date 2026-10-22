# Lane brief: backend-authz

Session model: **Opus** (security; docs/29). Owns authentication, authorization, scope and token claims in the
backend (`auth`, `authz` modules) and the PII rules already built (status and handovers in
`docs/status/backend-authz.md`).

Read `docs/lanes/README.md` first, then `docs/34-owner-directive-2026-10-22.md` s1 (D-ROLES-02), `docs/03`
(roles), `docs/21` (security), `docs/24` s4 (auth), V0001 (`app.role`), V0020 (roles, PII), and the current
token code.

Wave 4 rows (all T1, Opus-checked):

1. Role codes: add `NATIONAL` and `BRAND`; migrate `TOP` users to `NATIONAL` (migration request to db with
   the exact statements; db owns the file). `SR` and `AMO` cannot log in to the web (login answers a clear
   error code the web shows in Bangla and English).
2. Module grants for `ADMIN`: table `user_module_grant(user_id, module, access r|w)`, module list fixed in
   the contract (`masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports,
   config, data_entry, indent, leaderboard, daily_tracking`); the token carries `modules`; every admin route
   checks its module and access; 403 otherwise. `SUPERADMIN` has all.
3. Brand scope: `user_brand_scope(user_id, brand_id)`; token claim `brands`; every product-based report and
   dashboard query for a `BRAND` user is filtered to those brands server-side; non-brand cards 403 for BRAND.
4. Geography scope unchanged in principle (wing / division / territory from the assignment), extended with
   the House level in the scope resolver and the dashboard filter endpoint (`GET /scope/tree` returns only
   active houses, de-duplicated).
5. Admin API for grants and brand scopes (create, change, remove; audited with reason; `If-Match`), consumed
   by web-admin.
6. Tests: the role × module × access matrix as a table-driven test; a BRAND user cannot read another brand
   even with a crafted filter; SR/AMO web login refused; TOP migration idempotent.

Status file `docs/status/backend-authz.md`; requests `docs/requests/backend-authz-<name>.md`.
