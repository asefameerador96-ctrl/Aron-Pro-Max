-- Dev database overrides of docs/24 s9.4 (D24-17): enrolment, lockdown and integrity are relaxed so test phones work
-- before enrolment. Production never loads this file. The overrides get their own, new config version (the next
-- number), found again by its summary on a re-run, so phones that already hold a later version still receive them.

INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
  SELECT coalesce((SELECT max(config_version) FROM app.cfg_version), 0) + 1, 'change', u.id,
         'Dev database overrides (docs/24 s9.4, seed)', 3
    FROM app.app_user u
   WHERE u.username = 'aron.system'
     AND NOT EXISTS (SELECT 1 FROM app.cfg_version WHERE summary = 'Dev database overrides (docs/24 s9.4, seed)');
INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason)
  SELECT v.key, 'global', 0, v.value, now(), cv.config_version, u.id, 'dev database override (D24-17)'
    FROM (VALUES ('cfg.device.require_enrolled', 'false'::jsonb), ('cfg.device.lockdown_level', '"dev"'::jsonb),
                 ('cfg.device.require_integrity', 'false'::jsonb)) AS v(key, value)
    JOIN app.app_user u ON u.username = 'aron.system'
    JOIN app.cfg_version cv ON cv.summary = 'Dev database overrides (docs/24 s9.4, seed)'
   WHERE NOT EXISTS (SELECT 1 FROM app.cfg_value x WHERE x.key = v.key AND x.scope_type = 'global'
                       AND x.config_version = cv.config_version)
     AND NOT EXISTS (SELECT 1 FROM app.cfg_value x WHERE x.key = v.key AND x.scope_type = 'global'
                       AND x.effective_to IS NULL AND x.value = v.value);

-- Business code lists are system reference data and ship in migration V0019 (every environment), not here. A dev
-- database seeded before V0019 still holds the old seed's invented codes and labels: retire those codes and restore the
-- evidenced labels (idempotent; codes are never deleted).
UPDATE app.code_list_item SET valid_to = DATE '2026-10-07'
 WHERE valid_to IS NULL AND (list_key, code) IN (
   ('force_reason','gps_not_found'), ('skip_reason','outlet_closed'), ('edit_reason','wrong_quantity'),
   ('void_reason','duplicate_memo'), ('stock_variance_reason','counting_error'), ('stock_variance_reason','damaged_in_transit'),
   ('qc_fault_type','loose_tobacco'), ('qc_fault_type','torn_pack'), ('day_exception_reason','rain'),
   ('outlet_close_reason','shop_closed_permanently'), ('submit_void_reason','late_sale_entry'));
UPDATE app.code_list_item i SET label_en = v.en, label_bn = v.bn
  FROM (VALUES ('edit_reason', 'wrong_sku', 'Wrong SKU selected', 'ভুল SKU নির্বাচিত।'),
               ('force_reason', 'location_change', 'Location change', 'লোকেশন চেঞ্জ')) AS v(list_key, code, en, bn)
 WHERE i.list_key = v.list_key AND i.code = v.code AND (i.label_en, i.label_bn) IS DISTINCT FROM (v.en, v.bn);


-- A national holiday for the calendar tests (Victory Day); the test Sunday and Friday stay free.
INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, name_bn, reason)
  SELECT DATE '2026-12-16', 'global', 0, 'holiday', false, 'Victory Day', 'বিজয় দিবস', 'seed'
   WHERE NOT EXISTS (SELECT 1 FROM app.calendar_holiday WHERE date = DATE '2026-12-16' AND scope_type = 'global' AND revoked_at IS NULL);
