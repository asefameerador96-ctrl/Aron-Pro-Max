-- Lane masterdata-fit, push 1 (M06, M07, M22; owner rulings D-OWNER-MASTERDATA-01 and D-OWNER-PARITY-02).
-- (1) The Astha tiers follow the field: Gold, Diamond, Platinum, Titanium, all sub-channels of channel Astha. Titanium is added; Silver is RETIRED
--     (app.sub_channel.status = 'inactive', the code-list item gets valid_to), never deleted: an outlet that references it keeps working and keeps its
--     history, and no new write picks it. The order follows the field: Gold, Diamond, Platinum, Titanium.
-- (2) A new code list 'cluster_type' holds the six cluster types of the field with Bangla labels (the Bangla wording is ours, to be confirmed by
--     AKTCL). app.cluster.cluster_type stays free text (<= 60 characters) and keeps the field's wording (attrs.value); the importer maps a code or a
--     label to that wording.
-- The CHECK on code_list.list_key is rebuilt from its CURRENT definition plus 'cluster_type', so keys that other migrations added stay allowed.
-- Added NOT VALID here and validated in V0200 (squawk rule).

SET lock_timeout = '5s';

INSERT INTO app.sub_channel (channel, code, name, name_bn) VALUES ('Astha', 'Titanium', 'Titanium', NULL) ON CONFLICT (code) DO NOTHING;
UPDATE app.sub_channel SET status = 'inactive' WHERE code = 'Silver' AND status <> 'inactive';

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('sub_channel', 'titanium', 'Titanium', NULL, 9, '{"value": "Titanium", "channel": "Astha"}')
ON CONFLICT (list_key, code) DO NOTHING;
UPDATE app.code_list_item SET sort = 7 WHERE list_key = 'sub_channel' AND code = 'diamond';
UPDATE app.code_list_item SET sort = 8 WHERE list_key = 'sub_channel' AND code = 'platinum';
UPDATE app.code_list_item SET sort = 10, valid_to = (now() AT TIME ZONE 'Asia/Dhaka')::date
 WHERE list_key = 'sub_channel' AND code = 'silver' AND valid_to IS NULL;

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
