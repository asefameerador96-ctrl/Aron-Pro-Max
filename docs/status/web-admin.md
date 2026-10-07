# Web-admin lane status

## Done
- N-011 admin portal: role-gated `/admin` (ADMIN, SUPERADMIN, SUPPORT; SUPPORT read-only), kit (Field, ReasonField, DataTable, FilterBar), metadata CRUD generator proven on clusters (list, filter, create, edit, audit history), audit page. README "Add an entity" in `web/README.md`.

## Decisions
- ANALYST can read audit per docs/24 s8.5 but is outside the portal gate for now; revisit on Day 4.
- Reason lengths count code points like the contract.

## Open
- Create reason cannot be stored (contract), edit page re-reads the list (no GET by id): both requested.

## Requests filed
- web-admin-create-reason, web-admin-get-by-id.

## Scope note (lead, 2026-10-06)
- Read the deferred-programmes scope change (docs/27): target, loyalty/Astha and discount/promotion programmes are deferred. Nothing of them exists in `web/` (menu, entities, report keys); the admin CRUD generator stays generic so a programme module is one entity file later.
- Blocking nothing. Pending requests (routed by the lead): web-admin-create-reason, web-admin-get-by-id, web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job.

## Update (Day 1, later)
Done (pushed): F-ADM-001, 002, 003, 004, 007, 010, 011, 056, 070, F-WEB-003, F-WEB-032 with checker rounds fixed; F-ADM-021/023/060 delivered by web-config pages (hub links). Master-data hub at `/admin/master-data` uses `master-links.ts`.
In progress: F-ADM-005, 006, 008, 071 built, checker pending.
Next: F-ADM-076 (SR lifecycle wizard), F-ADM-020, F-ADM-026, F-ADM-028, F-ADM-065, F-TSO-023/025.
Decisions: parent level is the level above in geography; task_type attrs.roles dropped from the editor; ANALYST outside portal gate except outlet requests; direct edit ADMIN/SUPERADMIN only; mock master users madmin1/msupport1/mtso1; sub_channel_id is a plain number; acting-scope end date not expressible.
Requests (state: awaiting lead): web-admin-create-reason, web-admin-get-by-id, web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job, web-admin-sub-channel-ids, web-admin-outlet-web-request, web-admin-acting-scope.
Known not mine: e2e/pages.spec.ts daily-tracking date-dependent failure (dashboard lane).

## Update (checker round for 005/006/008/071)
Sonnet checker found 7 issues; fixed: unknown user on scope write is 404, duplicate scope nodes refused, scope node_id safe-integer bound, assignment end not after start, price publish refuses today (contract says future date). Not changed: zero price allowed (contract MtkNonNegative; product call), unknown SKU / overlapping effective ranges / second overlapping primary are API-side rules (mock gaps). Open: transfer form creates the new primary then ends the old one (two calls, not atomic): error is shown, retry is safe; needs an API transfer endpoint (not requested yet). Acting scope end date still requested.
F-ADM-076 built (`/admin/sr-lifecycle`); checker pending.

## Update (feedback, lifecycle, tutorials)
Done: F-ADM-076 and F-ADM-028 (checker findings fixed). F-ADM-026 built, checker pending.
Requests added: web-admin-feedback-filters, web-admin-tutorial-asset-id, web-admin-asset-upload-csp (infra sets ARON_BLOB_ORIGIN and the storage CORS rule).
Next: F-ADM-065 (SKU image upload, reuses the asset ticket flow), F-ADM-020 (surveys, rubrics, content), F-TSO-023/025, web/QC entry rows.
Trap: the admin segment streams behind loading.tsx, so notFound() in a page answers 200; the proxy rewrites known 404s instead.
