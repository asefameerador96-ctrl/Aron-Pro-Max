# Contract v1.10 queue (lead; applied as one batch after 1.9.0, D-CONTRACT-BATCH-01)

Lanes needing a new value in a response enum (a new `ProblemCode`, `RecordOutcomeCode`, role or level, ...) or a new member, operation or tag add one row here (same shape as `contract-v1.9-queue.md`); the lead applies the batch in one approved head with every generator and mirror (checklist in `contract-v1.7-queue.md`). Until a row ships, the server keeps the documented interim answer (for a new code: the nearest existing code with a stable detail). Opened 2026-10-24 when 1.9.0 was applied.

| # | Ruled | Source | Shape |
|---|---|---|---|
| 1 | 2026-10-24 (lead; D-NIGHT-MDM-ALLOWLIST-01; moved from v1.9 row 6) | mdm #3 writes the shape (`docs/requests/contract-v1.9-shapes-mdm.md` on `lane/mdm`) | MDM packages catalogue endpoint (per device: launcher-visible packages with label and icon hash) for the allow-list picker; `MdmPolicy` and `MdmPolicyWrite` gain the optional `work_hours_allowlist`. Shape file from mdm #3 pending; not in 1.9.0 (the lead shipped 1.9.0 without waiting for it). |
| 2 | 2026-10-24 (lead; contract 1.9.0 checker note 4) | D-CONTRACT-1-9-01 | `MemoView.supersedes_memo_no` and `OpenMemo.memo_no` keep the inline `maxLength: 40` that 1.9.0 row 1 fixed for `MemoView.memo_no`; make them `MemoNo` (nullable where they are). `DueCollectionPayload.against_memo_no` also takes imported legacy numbers, so it stays a string; only its length (40 < 44) is checked. Response constraint widened: rides the bump. |
| 3 | 2026-10-24 (lead; contract 1.9.0 checker note 1) | D-NIGHT-DAYCLOSE-01 | `SyncTotalsResponse.digest` becomes required once backend-core's server sends it (not breaking for a reader; the conformance suite must be green with it). |
| (next) | | | |
