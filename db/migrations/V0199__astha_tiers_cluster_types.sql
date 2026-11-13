-- Lane masterdata-fit, push 1 (M06, M07, M22; owner rulings D-OWNER-MASTERDATA-01 and D-OWNER-PARITY-02).
-- (1) The Astha tiers follow the field: Gold, Diamond, Platinum, Titanium, all sub-channels of channel Astha. Titanium is added; Silver is RETIRED
--     (app.sub_channel.status = 'inactive', the code-list item gets valid_to), never deleted: an outlet that references it keeps working and keeps its
--     history, and no new write picks it. The order follows the field: Gold, Diamond, Platinum, Titanium.
-- (2) A new code list 'cluster_type' holds the six cluster types of the field with Bangla labels (the Bangla wording is ours, to be confirmed by
--     AKTCL). app.cluster.cluster_type stays free text (<= 60 characters) and keeps the field's wording (attrs.value); the importer maps a code or a
--     label to that wording.
-- The CHECK on code_list.list_key is rebuilt from its CURRENT definition plus 'cluster_type', so keys that other migrations added stay allowed.
-- (3) (push 1b) Four SOURCE columns on app.outlet keep what the field's sheet said when our columns cannot hold it, so nothing the owner gave us vanishes
--     without a trace: source_created_at / source_updated_at (the sheet's Created At / Updated At; our own created_at/updated_at stay the time WE wrote the row,
--     the delta readers key off updated_at), source_contact_number (a phone text that cannot be repaired to 01XXXXXXXXX; personal data, never in a bundle or report)
--     and source_sub_channel (a tier text that was dropped because it did not fit the channel, or names a retired or unknown tier). Nullable, no default:
--     a metadata-only change on the hot table.
-- (4) (push 1b) Silver is retired WITHOUT a valid_to date: the bundle drops a code-list item once its valid_to has passed, and an outlet that still carries
--     Silver would lose its label on the phone. The item stays valid, flagged attrs.retired = true (pickers hide it; a label is still shown); the SERVER
--     refuses to write a retired or inactive tier (app.sub_channel.status = 'inactive').
-- (5) (push 1b) A Titanium row that already exists is checked, not trusted: another channel is a hard error (a human must look), the same channel is
--     re-activated. Existing clusters typed 'market' (dev data) are mapped to the field's 'Bazar Hub'; any other value outside the six is only counted.
-- (6) (push 1b) A master pin that must NOT replace a pin the field confirmed is kept as a CANDIDATE in app.outlet_location_history (basis 'candidate'); readers
--     of "the pin in force" (GeoRecheck, the outlet detail) ignore candidates.
-- (7) (push 1b, B2) cfg.release.short_rep_id_version_code: the first app version code that carries the 3-character rep-id rule. Default 0 = no release does,
--     so the server REFUSES to make an SR/AMO user of fewer than 4 characters (the installed app throws on such a memo number). The server allows it only when
--     cfg.release.min_version_code of that app is at least this value.
-- Added NOT VALID here and validated in V0200 (squawk rule).

SET lock_timeout = '5s';

-- Lock safety (V0144 pattern): app.outlet and app.code_list get an ALTER (ACCESS EXCLUSIVE); all locks by NOWAIT in a sub-transaction, retried for about 60 s.
DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.outlet, app.code_list, app.outlet_location_history IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0199: app.outlet or app.code_list stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

DO $$
DECLARE c text; st text;
BEGIN
  SELECT channel, status INTO c, st FROM app.sub_channel WHERE code = 'Titanium';
  IF NOT FOUND THEN
    INSERT INTO app.sub_channel (channel, code, name, name_bn) VALUES ('Astha', 'Titanium', 'Titanium', NULL);
  ELSIF c <> 'Astha' THEN
    RAISE EXCEPTION 'V0199: a sub-channel with code Titanium already exists under channel % (expected Astha); resolve it by hand, then re-run', c;
  ELSIF st <> 'active' THEN
    UPDATE app.sub_channel SET status = 'active' WHERE code = 'Titanium';
  END IF;
  SELECT attrs->>'channel' INTO c FROM app.code_list_item WHERE list_key = 'sub_channel' AND code = 'titanium';
  IF FOUND AND c IS DISTINCT FROM 'Astha' THEN
    RAISE EXCEPTION 'V0199: the sub_channel code-list item titanium already exists for channel % (expected Astha); resolve it by hand, then re-run', c;
  END IF;
END $$;
UPDATE app.sub_channel SET status = 'inactive' WHERE code = 'Silver' AND status <> 'inactive';

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('sub_channel', 'titanium', 'Titanium', NULL, 9, '{"value": "Titanium", "channel": "Astha"}')
ON CONFLICT (list_key, code) DO UPDATE SET valid_to = NULL;
UPDATE app.code_list_item SET sort = 7 WHERE list_key = 'sub_channel' AND code = 'diamond';
UPDATE app.code_list_item SET sort = 8 WHERE list_key = 'sub_channel' AND code = 'platinum';
UPDATE app.code_list_item SET sort = 10, attrs = attrs || '{"retired": true}'::jsonb
 WHERE list_key = 'sub_channel' AND code = 'silver' AND NOT (attrs @> '{"retired": true}');

DO $$
DECLARE d text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO d FROM pg_constraint WHERE conname = 'code_list_list_key_check' AND conrelid = 'app.code_list'::regclass;
  IF d IS NOT NULL AND d NOT LIKE '%cluster_type%' THEN
    EXECUTE 'ALTER TABLE app.code_list DROP CONSTRAINT code_list_list_key_check';
    EXECUTE 'ALTER TABLE app.code_list ADD CONSTRAINT code_list_list_key_check ' || regexp_replace(d, 'ARRAY\[', 'ARRAY[''cluster_type''::text, ') || ' NOT VALID';
  END IF;
END $$;

INSERT INTO app.code_list (list_key, description) VALUES ('cluster_type', 'Cluster type as the field writes it (D-MD-22).') ON CONFLICT (list_key) DO NOTHING;
INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('cluster_type', 'transit_hub',    'Transit Hub',    'ট্রানজিট হাব',            1, '{"value": "Transit Hub"}'),
  ('cluster_type', 'residential',    'Residential',    'আবাসিক',                   2, '{"value": "Residential"}'),
  ('cluster_type', 'commercial_hub', 'Commercial Hub', 'বাণিজ্যিক হাব',            3, '{"value": "Commercial Hub"}'),
  ('cluster_type', 'university_hub', 'University Hub', 'বিশ্ববিদ্যালয় হাব',       4, '{"value": "University Hub"}'),
  ('cluster_type', 'bazar_hub',      'Bazar Hub',      'বাজার হাব',                5, '{"value": "Bazar Hub"}'),
  ('cluster_type', 'industrial_hub', 'Industrial Hub', 'শিল্প হাব',                6, '{"value": "Industrial Hub"}')
ON CONFLICT (list_key, code) DO NOTHING;

-- (3) the field's original values, kept beside ours (nothing the owner gave us vanishes without a trace)
ALTER TABLE app.outlet ADD COLUMN IF NOT EXISTS source_created_at timestamptz;
ALTER TABLE app.outlet ADD COLUMN IF NOT EXISTS source_updated_at timestamptz;
ALTER TABLE app.outlet ADD COLUMN IF NOT EXISTS source_contact_number text;
ALTER TABLE app.outlet ADD COLUMN IF NOT EXISTS source_sub_channel text;
ALTER TABLE app.outlet ADD CONSTRAINT outlet_source_contact_number_len CHECK (char_length(source_contact_number) <= 60) NOT VALID;
ALTER TABLE app.outlet ADD CONSTRAINT outlet_source_sub_channel_len CHECK (char_length(source_sub_channel) <= 120) NOT VALID;
COMMENT ON COLUMN app.outlet.source_created_at IS 'Created At of the outlet in the field''s master sheet (the import, D-MD-20); our created_at is when this server wrote the row. Null for outlets not imported.';
COMMENT ON COLUMN app.outlet.source_updated_at IS 'Updated At of the outlet in the field''s master sheet (the import, D-MD-20); our updated_at is when this server last changed the row. Null for outlets not imported.';
COMMENT ON COLUMN app.outlet.source_contact_number IS '[pii:personal] The phone text of the field''s sheet when it could not be repaired to 01XXXXXXXXX (contact_number then stays empty); kept so the value is not lost, never sent to a device or shown in a report.';
COMMENT ON COLUMN app.outlet.source_sub_channel IS 'The tier text of the field''s sheet when it was NOT stored in sub_channel_id (it disagreed with the channel, is retired or unknown); kept so the value is not lost.';

-- (5) dev clusters typed with the old free-text value 'market' take the field's nearest value; other values outside the six are only counted
UPDATE app.cluster SET cluster_type = 'Bazar Hub' WHERE lower(btrim(cluster_type)) = 'market';
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM app.cluster
   WHERE cluster_type IS NOT NULL AND cluster_type NOT IN ('Transit Hub', 'Residential', 'Commercial Hub', 'University Hub', 'Bazar Hub', 'Industrial Hub');
  IF n > 0 THEN RAISE NOTICE 'V0199: % cluster(s) carry a cluster_type outside the six field values (list them: SELECT id, zone_id, name, cluster_type FROM app.cluster WHERE cluster_type NOT IN (...))', n; END IF;
END $$;

-- (6) candidate pins
ALTER TABLE app.outlet_location_history DROP CONSTRAINT outlet_location_history_basis_check;
ALTER TABLE app.outlet_location_history ADD CONSTRAINT outlet_location_history_basis_check CHECK (basis IN ('master','provisional','placeholder','none','candidate')) NOT VALID;
COMMENT ON COLUMN app.outlet_location_history.basis IS 'Location basis from valid_from on: master, provisional, placeholder (no usable pin), none, or candidate (a master pin the import did NOT apply because the field had confirmed another; never the pin in force).';

-- (7) the gate for short rep ids
INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, restrictive_dir, editor_permission, description_en) VALUES
  ('cfg.release.short_rep_id_version_code', 'release', 'S', 'int', '0'::jsonb, '{"min": 0, "max": 2000000000}'::jsonb, NULL, ARRAY['global']::text[], 3, NULL, 'S', 'server', false, false, 'none', 'cfg.edit.release',
   'First app version code (SR and AMO) whose memo-number rule accepts a rep id of 3 characters. 0 = none yet: the server refuses to create an SR/AMO user with fewer than 4 characters. Set it only when cfg.release.min_version_code is at least this value for the app.')
ON CONFLICT (key) DO NOTHING;
