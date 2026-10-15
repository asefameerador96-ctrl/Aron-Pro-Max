# Request: `app.dues_adjustment` (to: db) — F-ADM-036 server side

From: backend-admin. Blocks `/v1/admin/dues-adjustments` (listDuesAdjustments, createDuesAdjustment, decideDuesAdjustment; lead-approved row, T1 money).

No proposal table exists; the ledger (`app.due_ledger`, kind `adjustment`, any sign, unique per `(source_client_uuid, entry_kind)`) is append-only and takes the row on approval.

```
app.dues_adjustment (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- adjustment_id
  client_uuid    uuid NOT NULL UNIQUE,                              -- browser uuid; also the ledger row's source_client_uuid on approval
  outlet_id      bigint NOT NULL REFERENCES app.outlet(id),
  memo_client_uuid uuid,                                            -- optional memo it corrects
  kind           text NOT NULL CHECK (kind IN ('correction','write_off')),
  amount_mtk     bigint NOT NULL CHECK (amount_mtk <> 0),           -- signed change of the outlet balance; write_off < 0
  reason         text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  status         text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  created_by     bigint NOT NULL REFERENCES app.app_user(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  decided_by     bigint REFERENCES app.app_user(id),
  decided_at     timestamptz,
  decision_note  text CHECK (length(decision_note) <= 500),
  CHECK (kind <> 'write_off' OR amount_mtk < 0),
  CHECK ((status = 'pending') = (decided_by IS NULL AND decided_at IS NULL)),
  CHECK (decided_by IS NULL OR decided_by <> created_by)            -- maker-checker (ERR_SEPARATION_OF_DUTIES in the API)
)
```
Guard trigger: only `status, decided_by, decided_at, decision_note` may change, once, from `pending`; no deletes. Index `(outlet_id, created_at)` and `(status, id)`. Grants as the other admin tables, plus the data-dictionary comments.

backend-admin then: create pending (idempotent by `client_uuid`), list (by outlet, status; reach-scoped), decide (checker is not the maker; approval inserts the ledger `adjustment` row in the same transaction with source = `client_uuid`, audited with the note).
