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

-- Business code lists the SR selling day needs on Day 2 (labels Bangla first; codes are immutable).
INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs)
  SELECT i.list_key, i.code, i.label_en, i.label_bn, i.sort, i.attrs::jsonb
    FROM (VALUES
      ('force_reason', 'internet_problem', 'Internet problem', 'ইন্টারনেট সমস্যা', 1, '{}'),
      ('force_reason', 'location_change', 'Outlet location changed', 'দোকানের অবস্থান পরিবর্তন', 2, '{}'),
      ('force_reason', 'gps_not_found', 'GPS not found', 'জিপিএস পাওয়া যায়নি', 3, '{}'),
      ('skip_reason', 'not_reached', 'Not reached', 'পৌঁছানো যায়নি', 1, '{}'),
      ('skip_reason', 'outlet_closed', 'Outlet closed', 'দোকান বন্ধ', 2, '{}'),
      ('edit_reason', 'wrong_quantity', 'Wrong quantity', 'ভুল পরিমাণ', 1, '{}'),
      ('edit_reason', 'wrong_sku', 'Wrong product', 'ভুল পণ্য', 2, '{}'),
      ('void_reason', 'retailer_cancelled', 'Retailer cancelled', 'দোকানদার বাতিল করেছেন', 1, '{}'),
      ('void_reason', 'duplicate_memo', 'Duplicate memo', 'দ্বৈত মেমো', 2, '{}'),
      ('visit_outcome', 'sold', 'Sold', 'বিক্রি হয়েছে', 1, '{}'),
      ('visit_outcome', 'zero_sale_stock_ok', 'No sale, stock available', 'বিক্রি নেই, স্টক আছে', 2, '{}'),
      ('visit_outcome', 'closed', 'Outlet closed', 'দোকান বন্ধ', 3, '{}'),
      ('visit_outcome', 'owner_absent', 'Owner absent', 'মালিক অনুপস্থিত', 4, '{}'),
      ('stock_variance_reason', 'counting_error', 'Counting error', 'গণনার ভুল', 1, '{}'),
      ('stock_variance_reason', 'damaged_in_transit', 'Damaged in transit', 'পরিবহনে ক্ষতিগ্রস্ত', 2, '{}'),
      ('qc_fault_type', 'loose_tobacco', 'Loose tobacco', 'আলগা তামাক', 1, '{"group": "MFC"}'),
      ('qc_fault_type', 'torn_pack', 'Torn pack', 'ছেঁড়া প্যাকেট', 2, '{"group": "MKT"}'),
      ('payment_mode', 'cash', 'Cash', 'নগদ', 1, '{}'),
      ('day_exception_reason', 'rain', 'Rain', 'বৃষ্টি', 1, '{}'),
      ('day_exception_reason', 'hartal', 'Hartal', 'হরতাল', 2, '{}'),
      ('task_type', 'oos', 'Out of stock', 'স্টক নেই', 1, '{}'),
      ('task_type', 'general', 'General', 'সাধারণ', 2, '{}'),
      ('outlet_close_reason', 'shop_closed_permanently', 'Closed permanently', 'স্থায়ীভাবে বন্ধ', 1, '{}'),
      ('submit_void_reason', 'late_sale_entry', 'Late sale entry', 'বিলম্বিত বিক্রয় এন্ট্রি', 1, '{}')
    ) AS i(list_key, code, label_en, label_bn, sort, attrs)
   WHERE NOT EXISTS (SELECT 1 FROM app.code_list_item x WHERE x.list_key = i.list_key AND x.code = i.code);

-- A national holiday for the calendar tests (Victory Day); the test Sunday and Friday stay free.
INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, name_bn, reason)
  SELECT DATE '2026-12-16', 'global', 0, 'holiday', false, 'Victory Day', 'বিজয় দিবস', 'seed'
   WHERE NOT EXISTS (SELECT 1 FROM app.calendar_holiday WHERE date = DATE '2026-12-16' AND scope_type = 'global' AND revoked_at IS NULL);
