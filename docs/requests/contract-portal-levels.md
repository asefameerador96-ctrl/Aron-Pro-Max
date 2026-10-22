# Request (backend-authz → lead, contract 1.4.0; 2026-10-22): portal user levels (docs/34 s1, D-ROLES-02)

Status: OPEN for the lead's confirmation at review. A message from the lead session (session_01MbUQSxrP7AB9tbyANjUTPS,
2026-10-22 09:16 UTC) stated approval of Part 1 and Part 2 with `info.version` 1.3.3 → 1.4.0, on the condition that old
clients tolerate unknown enum values; the lead confirms it when checking this candidate. oasdiff counts the new values
in the response enums `Role` and `ProblemCode` as breaking; tools/ci/contract-breaking.sh reads this file and the bump.
Condition work in this candidate: the wire DTOs carry `role` and `code` as strings and the phones read responses
leniently (shared/contract UnknownEnumToleranceTest); the web falls back to the generic error text for an unknown code
and does not throw on an unknown role label (web/tests/role-tolerance.test.ts). Slices regenerated in the same candidate.

## Part 1 (row 1, in this push)

- `Role` gains `NATIONAL` and `BRAND`. `TOP` stays in the enum, described as retired (no user holds it once lane db's
  migration has moved TOP users to NATIONAL; it stays so historical rows and old audit images remain valid).
  Who sees a new value: web clients (the web types are regenerated in this push) and the TSO app when it lists users
  (it never receives NATIONAL or BRAND users: they have no zone; a TSO's user list is reach-scoped).
- `ProblemCode` gains `ERR_AUTH_PORTAL_NOT_ALLOWED` (403): a web login, a web refresh or a web token of an SR or AMO
  (they have no portal login). The login answers it only after the password matched; a wrong password stays 401
  `ERR_AUTH_INVALID_CREDENTIALS`. Phones never receive it. Web messages added in Bangla and English
  (`error.ERR_AUTH_PORTAL_NOT_ALLOWED`); docs/24 s3.x error table updated.
- `login` description: the refusal above.

## Part 2 (rows 2, 3 and 5; additive, a later push)

- `AdminModule` enum: masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config,
  data_entry, indent, leaderboard, daily_tracking.
- `Me.modules` (ADMIN: `{module: "r"|"w"}`; SUPERADMIN: every module `w`; others: absent or empty) and `Me.brands`
  (BRAND: brand product-node ids; others absent), so the web builds the sidebar from the grants.
- Grants API (row 5): `GET/PUT /v1/admin/users/{id}/module-grants`, `GET/PUT /v1/admin/users/{id}/brand-scope`, each
  with `If-Match` and a `reason`; shapes in that push.
