# Lead hand-offs from the contract 1.7 wiring (2026-10-23)

Routed by the lead from the lanes' checker notes; each goes to the owning lane's next session brief. None is urgent for the pilot (no house-scoped portal user exists yet).

| # | To | From | Item | Status |
|---|---|---|---|---|
| 1 | backend-admin #7 | backend-authz #8 checker note 2 | `HouseDedup` merge moves zones but not `app.user_scope` house nodes and does not bump `scope_version`, so a user scoped to a merged house drops to zero reach (fails closed). Proposal: move the nodes to the surviving house and bump `scope_version`. Also: a house set inactive after being granted keeps reaching its zones (only the scope write checks the active flag); decide whether the resolver should drop inactive houses (then a test). | open |
| 2 | backend-reports #6 | lead (contract 1.7 batch note) and backend-authz #8 note 3 | `DashboardApi` resolves a caller's top node only for national, wing, division, territory and zone; a user whose only scope node is a house gets 403 on the dashboard summary. Map a house top node to its zones (the national filter-options already handle house nodes). | open |
| 3 | lead (contract v1.8 batch) | backend-authz #8 checker note 1 | `getUserAccess` description names `ERR_AUTHZ_ROLE_ABOVE_GRANT` (row 1 of `contract-v1.8-queue.md`). | queued |
| 4 | web-admin #7 | backend-authz #8 role-node rule | `house` may be held by TSO and AMO (D-AUTHZ-1-7-WIRING-01); af5ed9d1 allows TSO only: one-line widening of `ALLOWED_SCOPE_TYPES` after train 19 lands. | told 14:33 |

## Added 2026-10-24 19:25 UTC (lead): constant-date lint for seed-dependent tests
D-TEST-DATE-PIN-01: 22 DataFlowTrace tests failed at Dhaka midnight because the test day was a constant while the dev seed starts at `app.dhaka_date(now())`. backend-core session 17 fixes the trace support (day from the seed clock). Infra/qa follow-up (next qa or infra session): a repository-gates step that fails when a test source under `backend/`, `shared/`, `web/` or `android/` that references the dev seed (`06_dev_gulshan_banani`, `GUL-SR-`, `SMOKE-SR-`, `sr1001`) also contains a literal ISO date in 2026; allow-list via a `// date-pin-ok:` comment with a reason. Small, T2.
