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
