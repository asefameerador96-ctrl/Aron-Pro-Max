-- V0163 SR manual build, sr-server train 1 (docs/requests/contract-1.14-sr-manual.md; D-SR-MAN-01, D-SV1-01 to 06).
--
-- 1. app.visit.zero_sale_reason_code: why the SR made a zero sale (SRM-A-40, screenshot 32); set by visit_close, only when
--    is_zero_sale (CHECK). Code list zero_sale_reason, four rows (Q12 default labels).
-- 2. app.visit.call_photo_uuid / call_photo_skip_reason_code: the outlet photo taken at call start (SRM-A-27, screenshot 20).
--    A skip carries a reason and no photo (CHECK). media.purpose gains outlet_call.
-- 3. Code lists: zero_sale_reason (new key), edit_reason + wrong_sku_qty, promotion_issue (SRM-B-15), task_type +
--    poor_brand_visibility (SRM-B-49). Labels Bangla verbatim from the manual / Q12 defaults.
-- All CHECKs that widen or are new are NOT VALID here (every existing row satisfies them) and validated by V0164 in a
-- transaction of its own (squawk rule). Migrations are forward-only; ADD COLUMN is nullable without default (no rewrite).
-- Locks: app.visit and app.media are taken by ONE LOCK ... NOWAIT retried for about 60 s (V0144 pattern).

SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.visit, app.media IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0163: app.visit or app.media stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.visit
    ADD COLUMN IF NOT EXISTS zero_sale_reason_code text,
    ADD COLUMN IF NOT EXISTS call_photo_uuid uuid,
    ADD COLUMN IF NOT EXISTS call_photo_skip_reason_code text;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'visit_zero_sale_reason_needs_zero_sale' AND conrelid = 'app.visit'::regclass) THEN
    ALTER TABLE app.visit ADD CONSTRAINT visit_zero_sale_reason_needs_zero_sale
      CHECK (zero_sale_reason_code IS NULL OR (is_zero_sale AND zero_sale_reason_code ~ '^[a-z][a-z0-9_]{1,40}$')) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'visit_call_photo_skip_check' AND conrelid = 'app.visit'::regclass) THEN
    ALTER TABLE app.visit ADD CONSTRAINT visit_call_photo_skip_check
      CHECK (call_photo_skip_reason_code IS NULL
             OR (call_photo_uuid IS NULL AND call_photo_skip_reason_code ~ '^[a-z][a-z0-9_]{1,40}$')) NOT VALID;
  END IF;

  -- media.purpose gains outlet_call (widening: drop the old CHECK, add the new one NOT VALID).
  ALTER TABLE app.media DROP CONSTRAINT IF EXISTS media_purpose_check;
  ALTER TABLE app.media ADD CONSTRAINT media_purpose_check
    CHECK (purpose IN ('force_sale','outlet_capture','outlet_verification','survey','feedback','support','gift_photo','outlet_call')) NOT VALID;

  ALTER TABLE app.code_list DROP CONSTRAINT IF EXISTS code_list_list_key_check;
  ALTER TABLE app.code_list ADD CONSTRAINT code_list_list_key_check CHECK (list_key IN (
    'channel','sub_channel','geo_class','force_reason','edit_reason','void_reason','visit_outcome','skip_reason',
    'day_exception_reason','stock_variance_reason','task_type','leave_type','feedback_category','qc_fault_type',
    'payment_mode','outlet_close_reason','submit_void_reason','ticket_issue_type','ticket_resolution_code','zero_sale_reason','call_photo_skip')) NOT VALID;
END $$;

-- visit_immutable names the columns a synced visit row may still change; the close fills zero_sale_reason_code once, so it joins the
-- write-once list ('='). call_photo_* are set at insert only and stay immutable (not named). Recreated, never edited (V0007 is history).
CREATE OR REPLACE TRIGGER visit_immutable BEFORE UPDATE OR DELETE ON app.visit
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at', 'server_verdict', 'server_distance_m', 'server_radius_m', 'server_max_accuracy_m', 'server_checked_at', '=close_client_uuid', '=close_received_at', '=close_captured_at', '=outcome_code', '=call_started_at', '=call_declined', '=ended_at', '=is_zero_sale',
    '=zone_id', '=cluster_id', '=outlet_channel', '=outlet_geo_class', '=zero_sale_reason_code', 'data_origin');


COMMENT ON COLUMN app.visit.zero_sale_reason_code IS 'Why the call ended with a zero-sale memo (code list zero_sale_reason); set by visit_close, only when is_zero_sale (CHECK). Null on every other visit.';
COMMENT ON COLUMN app.visit.call_photo_uuid IS 'media_uuid of the outlet photo taken at call start (media purpose outlet_call, ref_type visit); null when none was taken (see call_photo_skip_reason_code).';
COMMENT ON COLUMN app.visit.call_photo_skip_reason_code IS 'Why no call-start photo was taken: camera_failed, permission_denied, storage_full or other. Set only when call_photo_uuid is null (CHECK).';

INSERT INTO app.code_list (list_key, description) VALUES
  ('call_photo_skip', 'Why no outlet photo was taken at call start (SR manual screenshot 20); the first value is camera_unavailable.'),
  ('zero_sale_reason', 'Why an SR made a zero sale at an outlet (SR manual screenshot 32); attrs.outcome_hint = the visit_outcome the reason maps to for reports.')
ON CONFLICT (list_key) DO NOTHING;

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('zero_sale_reason', 'stock_available',   'Stock Available',              'স্টক আছে',                  1, '{"outcome_hint": "zero_sale_stock_ok"}'),
  ('zero_sale_reason', 'outlet_closed_during_visit', 'Outlet Closed during visit',   'ভিজিটের সময় দোকান বন্ধ',     2, '{"outcome_hint": "closed"}'),
  ('zero_sale_reason', 'absence_of_owner', 'Absence of owner',             'মালিক অনুপস্থিত',            3, '{"outcome_hint": "owner_absent"}'),
  ('zero_sale_reason', 'insufficient_cash', 'Insufficient Cash',            'পর্যাপ্ত নগদ নেই',           4, '{"outcome_hint": "zero_sale_stock_ok"}'),
  ('call_photo_skip', 'camera_unavailable', 'Camera unavailable',  'ক্যামেরা পাওয়া যাচ্ছে না',   1, '{}'),
  ('call_photo_skip', 'permission_denied',   'Camera permission denied', 'ক্যামেরার অনুমতি নেই',   2, '{}'),
  ('call_photo_skip', 'storage_full',        'Storage full',         'ফোনের জায়গা শেষ',            3, '{}'),
  ('call_photo_skip', 'other',               'Other',                'অন্যান্য',                    4, '{}'),
  ('edit_reason',      'wrong_sku_qty',     'Wrong SKU quantity',           'ভুল SKU পরিমাণ',             2, '{}'),
  ('edit_reason',      'promotion_issue',   'Promotion issue',              'প্রমোশন ইস্যু',              3, '{}'),
  ('task_type',        'poor_brand_visibility', 'Poor Brand Visibility',    NULL,                          4, '{}')
ON CONFLICT (list_key, code) DO NOTHING;

-- cfg.sale.call_photo_required (SRM-A-27, part A Q8 default true, lead C2): the call-start photo is mandatory; false lets the phone skip it
-- without a reason. A reasoned skip is always allowed (field-first, a broken camera never blocks a sale).
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.sale.call_photo_required', 'sale', 'S', 'bool', 'true'::jsonb, '{}'::jsonb, NULL, ARRAY['global', 'role']::text[], 1, NULL, 'B', 'both', false, false, 'none', 'cfg.edit.field',
   'The outlet photo at call start is required (SRM-A-27). On: the phone asks for it before the sale; a camera failure or refusal is recorded as a skip reason and never blocks the sale. Off: the photo step is not shown.')
ON CONFLICT (key) DO NOTHING;
