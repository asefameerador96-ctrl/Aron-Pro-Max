-- V0074 app.dues_adjustment: proposals to correct or write off an outlet's dues, decided maker-checker
-- (F-ADM-036; answers docs/requests/backend-admin-dues-adjustment-table.md). The ledger (app.due_ledger, kind
-- adjustment) stays append-only: on approval backend-admin inserts the ledger row in the same transaction with
-- source_client_uuid = client_uuid, so the ledger's (source_client_uuid, entry_kind) key makes a repeated approval a
-- no-op. Money in integer milli-taka; amount_mtk is the signed change of the outlet balance (a write-off lowers it).
--
-- A row is created pending; it is decided once (approved or rejected) by a user other than its maker; after that it
-- never changes, and no row is ever deleted (guard trigger below, plus a TRUNCATE guard).

SET lock_timeout = '5s';

CREATE TABLE app.dues_adjustment (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  client_uuid      uuid NOT NULL UNIQUE,
  outlet_id        bigint NOT NULL REFERENCES app.outlet(id),
  memo_client_uuid uuid,
  kind             text NOT NULL CHECK (kind IN ('correction','write_off')),
  amount_mtk       bigint NOT NULL CHECK (amount_mtk <> 0),
  reason           text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
  status           text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  created_by       bigint NOT NULL REFERENCES app.app_user(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  decided_by       bigint REFERENCES app.app_user(id),
  decided_at       timestamptz,
  decision_note    text CHECK (length(decision_note) <= 500),
  CONSTRAINT dues_adjustment_write_off_negative CHECK (kind <> 'write_off' OR amount_mtk < 0),
  CONSTRAINT dues_adjustment_decided_iff_not_pending CHECK ((status = 'pending') = (decided_by IS NULL AND decided_at IS NULL)),
  CONSTRAINT dues_adjustment_decided_together CHECK ((decided_by IS NULL) = (decided_at IS NULL)),
  CONSTRAINT dues_adjustment_maker_checker CHECK (decided_by IS NULL OR decided_by <> created_by)
);
CREATE INDEX dues_adjustment_outlet ON app.dues_adjustment (outlet_id, created_at);
CREATE INDEX dues_adjustment_status ON app.dues_adjustment (status, id);

-- Only the decision columns change, once, from pending; nothing is deleted.
CREATE FUNCTION app.dues_adjustment_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'app.dues_adjustment: adjustments are never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF OLD.status <> 'pending' THEN
    RAISE EXCEPTION 'app.dues_adjustment: a % adjustment is history and does not change', OLD.status USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.status = 'pending' THEN
    RAISE EXCEPTION 'app.dues_adjustment: an update must decide the adjustment (approved or rejected)' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (to_jsonb(NEW) - ARRAY['status', 'decided_by', 'decided_at', 'decision_note'])
     IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'decided_by', 'decided_at', 'decision_note']) THEN
    RAISE EXCEPTION 'app.dues_adjustment: only status, decided_by, decided_at and decision_note change' USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION app.dues_adjustment_guard() IS 'Lets a pending dues adjustment be decided once (status, decided_by, decided_at, decision_note) and refuses every other change and any delete.';
CREATE TRIGGER dues_adjustment_guard BEFORE UPDATE OR DELETE ON app.dues_adjustment
  FOR EACH ROW EXECUTE FUNCTION app.dues_adjustment_guard();
CREATE TRIGGER dues_adjustment_no_truncate BEFORE TRUNCATE ON app.dues_adjustment
  FOR EACH STATEMENT EXECUTE FUNCTION app.deny_mutation();
REVOKE UPDATE, DELETE, TRUNCATE ON app.dues_adjustment FROM PUBLIC;

COMMENT ON TABLE app.dues_adjustment IS 'One row is a proposed correction or write-off of an outlet''s dues, decided once by a checker other than the maker; an approval adds the due_ledger adjustment row (F-ADM-036).
owner: backend:admin | capture: ONLINE | retention: audit | pii: personal';
COMMENT ON COLUMN app.dues_adjustment.id IS 'Server surrogate key (adjustment_id in the API).';
COMMENT ON COLUMN app.dues_adjustment.client_uuid IS 'UUID v4 the browser generated; idempotency key of the create and source_client_uuid of the ledger row on approval.';
COMMENT ON COLUMN app.dues_adjustment.outlet_id IS 'Outlet whose dues change (app.outlet).';
COMMENT ON COLUMN app.dues_adjustment.memo_client_uuid IS 'client_uuid of the memo the adjustment corrects; null when it corrects the balance as a whole.';
COMMENT ON COLUMN app.dues_adjustment.kind IS 'correction (either sign) or write_off (always negative).';
COMMENT ON COLUMN app.dues_adjustment.amount_mtk IS 'Signed change of the outlet balance in milli-taka; never zero, negative for a write-off.';
COMMENT ON COLUMN app.dues_adjustment.reason IS '[pii:personal] Why the maker proposes the change (10 to 500 characters).';
COMMENT ON COLUMN app.dues_adjustment.status IS 'pending, approved or rejected; decided once.';
COMMENT ON COLUMN app.dues_adjustment.created_by IS 'Maker: user who proposed the adjustment (app.app_user).';
COMMENT ON COLUMN app.dues_adjustment.created_at IS 'UTC time the adjustment was proposed.';
COMMENT ON COLUMN app.dues_adjustment.decided_by IS 'Checker: user who approved or rejected it, never the maker; null while pending.';
COMMENT ON COLUMN app.dues_adjustment.decided_at IS 'UTC time of the decision; null while pending.';
COMMENT ON COLUMN app.dues_adjustment.decision_note IS '[pii:personal] Checker''s note on the decision (at most 500 characters).';

-- Grants as the other admin tables: api_rw inserts, reads and decides (its 'app.*' and '*/update' rows; the guard
-- limits the update); the worker reads (its 'app.*' row); no role deletes.
SELECT app.apply_db_role_grants();
