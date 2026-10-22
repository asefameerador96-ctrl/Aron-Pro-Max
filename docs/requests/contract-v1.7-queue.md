# Contract v1.7 queue (lead; applied as one batch after 1.5.0 backend-reports and 1.6.0 mdm)

Rule (lead 2026-10-22): contract versions are bumped by batch, not per lane. Lanes add one line here; the lead applies the
batch in one lead-approved push (openapi.yaml, slices, WireDtos and the Kotlin catalogue, web types, web bn/en messages,
docs/24's error table), with the dependent suites of contract-v1.3-queue.md's batch checklist run before the push.

| # | Ruled | Source | Shape |
|---|---|---|---|
| 1 | 2026-10-22 | D-AUTHZ-ADMIN-CREDS-01 | ERR_AUTHZ_ROLE_ABOVE_GRANT — 403, ProblemCode, portal user management, requested by backend-authz, D-AUTHZ-ADMIN-CREDS-01. Until then the refusals answer 403 ERR_FORBIDDEN with the stable details "only a SUPERADMIN manages ADMIN and SUPERADMIN users" and "only a SUPERADMIN manages ANALYST and SUPPORT users" (`AdminUsers.kt`); the batch swaps the code there. |
| 2 | 2026-10-22 (lead, D-SCOPE-TREE-01) | docs/lanes/backend-authz.md row 4, D-ROLES-02 | `UserScopeNode.node_type` and `UserScopeWrite.nodes[].node_type` gain `house` (response enum: breaking, hence here). With it backend-authz adds `house` to `SCOPE_TABLES` in `AdminUsers.kt` (PUT /v1/admin/users/{id}/scope). The resolver already reads `house` rows (ReachCalculator/GeoRepository, zones whose `zone.house_id` is the node). |
| 3 | 2026-10-22 (lead, mdm #2) | D-MDM-01, `mdm-contract-network-type.md` | `DeviceStatusReport.network_type`: optional, `enum [wifi, mobile, offline]`, additive. The MDM list already reads `report->>'network_type'` and shows `unknown` when absent. With the batch: mirror in `shared:contract` and `DeviceStatusReportDto`; the app fills it from one `ConnectivityManager` read per report (`DeviceStatusReporter`, core-sync), no polling. No migration (jsonb). (Copied verbatim from lane/mdm 2a1ac9ab docs/requests/mdm-contract-v1.7-queue-line.md at the lead's request, 2026-10-22.) |
