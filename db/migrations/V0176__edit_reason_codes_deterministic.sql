-- sr-server train 2: the edit_reason codes wrong_quantity and wrong_price_type have ONE state everywhere (SRM-B-15, review low).
-- V0173 inserted them ON CONFLICT DO NOTHING, so a database that already held them (the dev seed once retired wrong_quantity) kept whatever
-- valid_to / labels it had, and a fresh database got the V0173 row: the same release behaved differently on dev and on a new database.
-- They stay ACTIVE (the AMO's legacy edit list still sends them: a stored code never changes meaning) with the V0173 labels; the SR app's own
-- reasons are wrong_sku_qty and promotion_issue. Forward-only; V0173 is not edited. The item guard allows labels, sort, attrs and valid_to.
INSERT INTO app.code_list_item (list_key, code, label_en, label_bn, sort, attrs) VALUES
  ('edit_reason', 'wrong_quantity',   'Wrong quantity',   'ভুল পরিমাণ',      4, '{}'),
  ('edit_reason', 'wrong_price_type', 'Wrong price type', 'ভুল মূল্যের ধরন', 5, '{}')
ON CONFLICT (list_key, code) DO UPDATE
   SET valid_to = NULL, label_en = EXCLUDED.label_en, label_bn = EXCLUDED.label_bn, sort = EXCLUDED.sort;
