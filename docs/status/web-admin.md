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
