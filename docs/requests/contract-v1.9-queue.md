# Contract v1.9 queue (lead; applied as one batch after 1.8.0, D-CONTRACT-BATCH-01)

Lanes needing a new value in a response enum (a new `ProblemCode`, `RecordOutcomeCode`, role or level, ...) or a new member, operation or tag add one row here (same shape as `contract-v1.8-queue.md`); the lead applies the batch in one approved head with every generator and mirror (checklist in `contract-v1.7-queue.md`). Until a row ships, the server keeps the documented interim answer (for a new code: the nearest existing code with a stable detail). Opened 2026-10-24 when 1.8.0 was applied.

| # | Ruled | Source | Shape |
|---|---|---|---|
| 1 | 2026-10-24 (lead; contract 1.8.0 checker note N1) | D-CONTRACT-1-8-01 | `MemoView.memo_no` is an inline string with `maxLength: 40`, shorter than the 44 characters the `MemoNo` pattern allows (`<username>-<yyMMdd>-<seq>`, a 32-character login); make it `$ref: '#/components/schemas/MemoNo'` as `FlaggedMemoView.memo_no` is from 1.8.0. A response constraint widened, so oasdiff may class it as breaking; it rides the version bump. |
| 2 | 2026-10-24 (lead; contract 1.8.0 checker) | D-CONTRACT-1-8-01, D-DB-MEMO-FLAGS-VIEW-01 | `GET /v1/memos` (`MemoApi.kt`) leaves out memos without a route (inner join on `app.route`, `WHERE m.route_id IS NOT NULL`), so their `server_flags` show only in `getFlaggedMemos`. Candidate: `MemoView.route_id` becomes `[integer, 'null']` and the read uses a left join (a response type widened: breaking for an old reader, rides the bump). backend-core implements; the phone's memo history ignores the member today. |
| (next) | | | Expected: mdm's packages catalogue endpoint (docs/status/mdm.md), when mdm writes it up (moved from the v1.8 queue, not part of 1.8.0). |
