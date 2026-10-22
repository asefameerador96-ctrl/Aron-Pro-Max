# Request (mdm → backend-authz, 2026-10-22): the `mdm` module grant

`MdmGrants` (backend/analytics/.../mdm/MdmAccess.kt) is the only place MDM asks "may this caller read / write MDM". It is
`RoleGrants` today (SUPERADMIN and ADMIN write, everyone else none, which equals who manages devices today). When the token
claim `modules` lands (`{module: "r"|"w"}`, docs/34 s1), please replace the instance in `Wiring.kt`
(`MdmDeps(..., grants = ...)`): `SUPERADMIN` -> WRITE; `ADMIN` -> the `mdm` value (absent = NONE); the others NONE.
Tests: `MdmApiTest.readOnlyGrantSeesListsButCannotAct` shows the shape; add a route-level case with a read-only ADMIN token.
The web gate already honours an optional `modules.mdm` on the session user.
