# Contract 1.23.0 (provisional): portal points adjustment and `units_captured` (record for the contract gate)

From: lane portal-loyalty-api, 2026-11-18 (D-LOY-API-35, -36, -38, -39, -40). Additive only, no migration, no breaking change.

Added:
- `POST /v1/admin/programmes/{id}/adjustments` (`postProgrammeAdjustment`), `PUT .../adjustments` (`putProgrammeAdjustments`, dry run then apply, idempotent by `batch_uuid`) and `GET .../ledger` (`listProgrammeLedger`), all in permission module `loyalty` (write grant for the first two; no module-map change).
- Schemas `ProgrammeAdjustmentWrite`, `ProgrammeAdjustmentRow`, `ProgrammeAdjustmentBatch`, `ProgrammeAdjustmentRowError`, `ProgrammeAdjustmentBatchResult`, `LedgerEntry`, `LedgerPage`.
- `PendingGiftVerification.units_captured` (optional integer array; an older server omits it and the phone falls back to `photos_captured`).
- New error codes on the new routes: `would_overdraw`, `not_enrolled` (every programme kind), `debit_has_no_expiry`, `too_many_debit_rows` (at most 2,000 debit rows per file), and a full database lock table is `503`.

Removed: nothing. The phone path is unchanged: `loyalty[].balance_points` is still the sum of the ledger rows, so a posted adjustment shows from the next bundle.
