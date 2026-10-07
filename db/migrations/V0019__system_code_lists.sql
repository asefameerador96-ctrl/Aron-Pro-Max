-- V0019 system reference data: the business code-list items every environment needs (audit AUD-DA-04; docs/16 s3.5 and
-- s3.6, docs/19, D-34, D-38, D-39, D-95, D-159, D-197, D-200, D-258). Until now they existed only in the dev seed, so a
-- QA, staging or production database had empty pickers. Idempotent: ON CONFLICT DO NOTHING keeps any label an admin
-- (or the dev seed) already set; codes are immutable (V0005 trigger) and are retired with valid_to, never deleted.
--
-- Closed lists (logic or a CHECK depends on the code): visit_outcome, payment_mode, channel, geo_class, qc_fault_type
-- groups. Lists authored with AKTCL ship only the codes the specs name, or one minimal evidence-based code where the
-- specs name none and a reason is required (stock_variance_reason, outlet_close_reason, submit_void_reason); the
-- sponsor go-live checklist (docs/status/db.md) asks AKTCL to confirm them and to supply the missing Bangla labels.
-- Bangla labels are set only where the docs or the evidence manuals give them; the rest are NULL until AKTCL supplies
-- them (the apps fall back to label_en). attrs.system marks a code the server assigns (never offered in a picker);
-- attrs.roles limits a code to the listed roles.
-- channel and geo_class: the code pattern is lower case, so the canonical value (the CHECK, contract enum and
-- geo_class_def value) is attrs.value; geo_class carries its config ordinal. sub_channel: the nine rows of D-258 are
-- master rows of app.sub_channel (mixed-case code); the code-list items label them, attrs.value = app.sub_channel.code.

SET lock_timeout = '5s';

INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  -- visit_outcome (closed; D-38, CHECK on app.visit.outcome_code)
  ('visit_outcome', 'sold', 'Sold', NULL, 1, '{}'),
  ('visit_outcome', 'zero_sale_stock_ok', 'No sale, stock available', NULL, 2, '{}'),
  ('visit_outcome', 'closed', 'Outlet closed', NULL, 3, '{}'),
  ('visit_outcome', 'owner_absent', 'Owner absent', NULL, 4, '{}'),
  ('visit_outcome', 'refused', 'Refused', NULL, 5, '{}'),
  ('visit_outcome', 'competitor_exclusive', 'Competitor exclusive', NULL, 6, '{}'),
  ('visit_outcome', 'not_reached', 'Not reached', NULL, 7, '{}'),
  ('visit_outcome', 'abandoned', 'Abandoned', NULL, 8, '{}'),
  -- skip_reason (contract default)
  ('skip_reason', 'not_reached', 'Not reached', NULL, 1, '{}'),
  -- force_reason (docs/05, docs/16 s3.6, D-95); no_outlet_location is set by the geo rule, manual_override by the AMO
  ('force_reason', 'internet_problem', 'Internet problem', 'ইন্টারনেট সমস্যা', 1, '{}'),
  ('force_reason', 'location_change', 'Location change', 'লোকেশন চেঞ্জ', 2, '{}'),
  ('force_reason', 'no_outlet_location', 'Outlet has no location', NULL, 3, '{"system": true}'),
  ('force_reason', 'manual_override', 'Manual override', NULL, 4, '{"roles": ["AMO"]}'),
  -- edit_reason (D-200; two more live-app reasons pending MQ-18)
  ('edit_reason', 'wrong_sku', 'Wrong SKU selected', 'ভুল SKU নির্বাচিত।', 1, '{}'),
  -- void_reason (docs/19 cfg.memo.void_reasons, Q43 pending)
  ('void_reason', 'retailer_cancelled', 'Retailer cancelled', NULL, 1, '{}'),
  ('void_reason', 'wrong_outlet', 'Wrong outlet', NULL, 2, '{}'),
  ('void_reason', 'duplicate_entry', 'Duplicate entry', NULL, 3, '{}'),
  ('void_reason', 'other', 'Other', NULL, 4, '{}'),
  -- day_exception_reason (D-39 as docs/16 s3.6 codes it, plus docs/19's other)
  ('day_exception_reason', 'rain_flood', 'Rain or flood', NULL, 1, '{}'),
  ('day_exception_reason', 'hartal', 'Hartal', NULL, 2, '{}'),
  ('day_exception_reason', 'market_closed', 'Market closed', NULL, 3, '{}'),
  ('day_exception_reason', 'dh_out_of_stock', 'Distribution house out of stock', NULL, 4, '{}'),
  ('day_exception_reason', 'breakdown', 'Breakdown', NULL, 5, '{}'),
  ('day_exception_reason', 'sick', 'Sick (without leave)', NULL, 6, '{}'),
  ('day_exception_reason', 'other', 'Other', NULL, 7, '{}'),
  -- stock_variance_reason (authored; none named; a reason is required for adjustment, damaged and short)
  ('stock_variance_reason', 'other', 'Other', NULL, 1, '{}'),
  -- task_type (D-167)
  ('task_type', 'oos', 'OOS', NULL, 1, '{}'),
  ('task_type', 'general', 'General Task', NULL, 2, '{}'),
  ('task_type', 'irregular_visit', 'Irregular Visit', NULL, 3, '{}'),
  -- leave_type
  ('leave_type', 'casual', 'Casual', NULL, 1, '{}'),
  ('leave_type', 'sick', 'Sick', NULL, 2, '{}'),
  ('leave_type', 'earn', 'Earn', NULL, 3, '{}'),
  -- feedback_category (D-197: suggestion only)
  ('feedback_category', 'suggestion', 'Suggestion', NULL, 1, '{}'),
  -- qc_fault_type (D-34, D-159; group MFC = production fault, MKT = transport fault; applies_to app and/or web)
  ('qc_fault_type', 'damaged_crushed', 'Damaged & Crushed Pack/Outer CBC', 'ড্যামেজড ও ক্রাশড – প্যাক / আউটার CBC', 1, '{"group": "MFC", "applies_to": ["app", "web"]}'),
  ('qc_fault_type', 'short_outer_pack_stick', 'Outer, Pack or Stick missing', 'আউটার / প্যাক / স্টিক কম থাকা', 2, '{"group": "MFC", "applies_to": ["app", "web"]}'),
  ('qc_fault_type', 'other_mfc', 'Others (manufacturing)', 'অন্যান্য ত্রুটি', 3, '{"group": "MFC", "applies_to": ["app", "web"]}'),
  ('qc_fault_type', 'brand_mix_up', 'Brand Mix Up', NULL, 4, '{"group": "MFC", "applies_to": ["web"]}'),
  ('qc_fault_type', 'visual_fault', 'Cigarette Visual Fault', NULL, 5, '{"group": "MFC", "applies_to": ["web"]}'),
  ('qc_fault_type', 'expired_stock', 'Expired stock (4 months+)', 'মেয়াদোত্তীর্ণ স্টক (৪ মাস+)', 6, '{"group": "MKT", "applies_to": ["app", "web"]}'),
  ('qc_fault_type', 'damaged_in_transit', 'Stock damaged during route service', 'স্টক ড্যামেজড – রুট সার্ভিস কালীন', 7, '{"group": "MKT", "applies_to": ["app", "web"]}'),
  ('qc_fault_type', 'taste', 'Taste-related problem', 'স্বাদ সংক্রান্ত সমস্যা', 8, '{"group": "MKT", "applies_to": ["app"]}'),
  ('qc_fault_type', 'damp_stick', 'Damp Cigarette stick', NULL, 9, '{"group": "MKT", "applies_to": ["web"]}'),
  ('qc_fault_type', 'spotting', 'Spotting on Cigarette', NULL, 10, '{"group": "MKT", "applies_to": ["web"]}'),
  ('qc_fault_type', 'other_mkt', 'Others (marketing)', NULL, 11, '{"group": "MKT", "applies_to": ["web"]}'),
  -- payment_mode (closed: CHECK on payment_mode)
  ('payment_mode', 'cash', 'Cash', 'নগদ', 1, '{}'),
  -- outlet_close_reason (authored; the observed app confirms "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে", F-SR-038)
  ('outlet_close_reason', 'permanently_closed', 'Closed permanently', 'স্থায়ীভাবে বন্ধ', 1, '{}'),
  -- submit_void_reason (authored; D-539's case is an accidental submit)
  ('submit_void_reason', 'accidental_submit', 'Submitted by mistake', NULL, 1, '{}'),
  ('submit_void_reason', 'other', 'Other', NULL, 2, '{}'),
  -- channel (closed: CHECK on outlet.channel and sub_channel.channel, contract enum)
  ('channel', 'gt', 'GT Channel', NULL, 1, '{"value": "GT"}'),
  ('channel', 'dcc', 'DCC', NULL, 2, '{"value": "DCC"}'),
  ('channel', 'astha', 'Astha', 'আস্থা', 3, '{"value": "Astha"}'),
  ('channel', 'rcc', 'RCC', NULL, 4, '{"value": "RCC"}'),
  ('channel', 'mt', 'MT', NULL, 5, '{"value": "MT"}'),
  ('channel', 'horeca', 'HoReCa', NULL, 6, '{"value": "HoReCa"}'),
  -- geo_class (closed: app.geo_class_def; ordinal is the config scope_id)
  ('geo_class', 'hill', 'Hill', NULL, 1, '{"value": "Hill", "ordinal": 1}'),
  ('geo_class', 'urban', 'Urban', NULL, 2, '{"value": "Urban", "ordinal": 2}'),
  ('geo_class', 'semi_urban', 'Semi Urban', NULL, 3, '{"value": "SemiUrban", "ordinal": 3}'),
  ('geo_class', 'rural', 'Rural', NULL, 4, '{"value": "Rural", "ordinal": 4}'),
  -- sub_channel (D-258: GT, RCC, DCC, MT, HoReCa and the Astha tiers)
  ('sub_channel', 'gt', 'GT', NULL, 1, '{"value": "GT", "channel": "GT"}'),
  ('sub_channel', 'rcc', 'RCC', NULL, 2, '{"value": "RCC", "channel": "RCC"}'),
  ('sub_channel', 'dcc', 'DCC', NULL, 3, '{"value": "DCC", "channel": "DCC"}'),
  ('sub_channel', 'mt', 'MT', NULL, 4, '{"value": "MT", "channel": "MT"}'),
  ('sub_channel', 'horeca', 'HoReCa', NULL, 5, '{"value": "HoReCa", "channel": "HoReCa"}'),
  ('sub_channel', 'gold', 'Gold', NULL, 6, '{"value": "Gold", "channel": "Astha"}'),
  ('sub_channel', 'platinum', 'Platinum', NULL, 7, '{"value": "Platinum", "channel": "Astha"}'),
  ('sub_channel', 'diamond', 'Diamond', NULL, 8, '{"value": "Diamond", "channel": "Astha"}'),
  ('sub_channel', 'silver', 'Silver', NULL, 9, '{"value": "Silver", "channel": "Astha"}')
ON CONFLICT (list_key, code) DO NOTHING;

-- The nine sub-channel master rows of D-258 (the importer and the admin page key outlets to them).
INSERT INTO app.sub_channel (channel, code, name)
SELECT (i.attrs ->> 'channel'), i.attrs ->> 'value', i.label_en
  FROM app.code_list_item i WHERE i.list_key = 'sub_channel'
ON CONFLICT (code) DO NOTHING;
