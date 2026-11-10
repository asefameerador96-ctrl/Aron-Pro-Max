-- Dev content for the AMO manual build (docs/requests/amo-manual-build-2026-11.md s2; D-AMO-MAN-10; D-AMO-FND-05).
-- Dev, CI and test only: the final account imports the same content through the admin content API (POST /v1/admin/rubrics,
-- config page). Idempotent; a second run changes nothing.
--
-- 1. The Joint Call rubric: seven criteria in three sections (screenshot 31, manual s9). Version 1 is the flat older shape
--    (no sections, no guidance), version 2 the manual's: orange section headers, the "what to look for" lines in English and
--    Bangla. rubric_version rows are append-only (V0023), so version 2 is an insert and rubric.version moves to 2. A database
--    that already holds a Joint Call rubric without the sections gets the next version; one that has them is left alone.
-- 2. cfg.rubric.distribution_brands: the Perf Assessment brand list in the order of screenshot 27, only the brands the dev
--    catalogue has (17 of the 18: "Maxim - Platinum Series" is not in the catalogue). A new config version of its own, found
--    again by its summary, like 04_dev_config_and_codes.sql.

DO $joint$
DECLARE
  rid bigint;
  cur int;
  v1 jsonb := $j$[
    {"criterion_id": 1, "key": "sales_situation", "label_en": "Summarise the situation", "label_bn": "পরিস্থিতি সংক্ষেপে বর্ণনা করুন", "answer_type": "score_1_5"},
    {"criterion_id": 2, "key": "sales_concept", "label_en": "Describe the concept", "label_bn": "ধারণাটি বর্ণনা করুন", "answer_type": "score_1_5"},
    {"criterion_id": 3, "key": "sales_how_it_works", "label_en": "Explain how it will work", "label_bn": "জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা", "answer_type": "score_1_5"},
    {"criterion_id": 4, "key": "sales_reaffirm", "label_en": "Reaffirm the benefits", "label_bn": "সুবিধা পুনঃব্যক্ত করা", "answer_type": "score_1_5"},
    {"criterion_id": 5, "key": "sales_close", "label_en": "Close successfully", "label_bn": "সফলভাবে সমাপ্ত করা", "answer_type": "score_1_5"},
    {"criterion_id": 6, "key": "relationship", "label_en": "Relationship assessment", "label_bn": "রিলেসনশিপ এসেসম্যান্ট", "answer_type": "score_1_5"},
    {"criterion_id": 7, "key": "service_quality", "label_en": "Service quality assessment", "label_bn": "সার্ভিস কোয়ালিটি এসেসম্যান্ট", "answer_type": "score_1_5"}
  ]$j$::jsonb;
  v2 jsonb := $j$[
    {"criterion_id": 1, "key": "sales_situation", "label_en": "Summarise the situation", "label_bn": "পরিস্থিতি সংক্ষেপে বর্ণনা করুন", "answer_type": "score_1_5",
     "section": "5-step sales call assessment", "section_bn": "৫ ধাপ সেলস কল অ্যাসেসমেন্ট",
     "guidance_en": "(What to look for: OHS count, OOS, product quality check, understanding opportunity and issues)",
     "guidance_bn": "(যা দেখতে হবে: OHS কাউন্ট, OOS, Product quality চেক, অপর্চুনিটি এবং ইস্যু বুঝা)"},
    {"criterion_id": 2, "key": "sales_concept", "label_en": "Describe the concept", "label_bn": "ধারণাটি বর্ণনা করুন", "answer_type": "score_1_5",
     "section": "5-step sales call assessment", "section_bn": "৫ ধাপ সেলস কল অ্যাসেসমেন্ট",
     "guidance_en": "(What to look for: explain the SOQ requirement properly, present the offer, campaign communication)",
     "guidance_bn": "(যা দেখতে হবে: SOQ রিকুয়ারমেন্ট ঠিক মতন বুঝানো, অফার টি তুলে ধরা, ক্যাম্পেইন কমিনিকেশন)"},
    {"criterion_id": 3, "key": "sales_how_it_works", "label_en": "Explain how it will work", "label_bn": "জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা", "answer_type": "score_1_5",
     "section": "5-step sales call assessment", "section_bn": "৫ ধাপ সেলস কল অ্যাসেসমেন্ট",
     "guidance_en": "(What to look for: explain the offer modality properly, highlight the shopkeeper's business benefit)",
     "guidance_bn": "(যা দেখতে হবে: অফার এর মডালিটি ঠিক মতন বুঝানো, দোকানদারের ব্যবসায়িক বেনিফিট হাইলাইট করা)"},
    {"criterion_id": 4, "key": "sales_reaffirm", "label_en": "Reaffirm the benefits", "label_bn": "সুবিধা পুনঃব্যক্ত করা", "answer_type": "score_1_5",
     "section": "5-step sales call assessment", "section_bn": "৫ ধাপ সেলস কল অ্যাসেসমেন্ট",
     "guidance_en": "(What to look for: answer any question of the shopkeeper, highlight the benefits again)",
     "guidance_bn": "(যা দেখতে হবে: দোকানদারের কোন প্রশ্নের উত্তর দেয়া, পুনরায় বেনিফিট গুলা হাইলাইট করা)"},
    {"criterion_id": 5, "key": "sales_close", "label_en": "Close successfully", "label_bn": "সফলভাবে সমাপ্ত করা", "answer_type": "score_1_5",
     "section": "5-step sales call assessment", "section_bn": "৫ ধাপ সেলস কল অ্যাসেসমেন্ট",
     "guidance_en": "(What to look for: memo and execution as per the idea, ensure it)",
     "guidance_bn": "(যা দেখতে হবে: আইডিয়া অনুযায়ী মেমো/এক্সিকিউশন করা, এনসিউর করা)"},
    {"criterion_id": 6, "key": "relationship", "label_en": "Relationship", "label_bn": "রিলেসনশিপ", "answer_type": "score_1_5",
     "section": "Relationship assessment", "section_bn": "রিলেসনশিপ এসেসম্যান্ট",
     "guidance_en": "(What to look for: greeting, knowing the shopkeeper's name, warmth with the shopkeeper)",
     "guidance_bn": "(যা দেখতে হবে: সম্ভাষণ দেওয়া, দোকানদারের নাম জানা, দোকানদারের সাথে আন্তরিকতা)"},
    {"criterion_id": 7, "key": "service_quality", "label_en": "Service quality", "label_bn": "সার্ভিস কোয়ালিটি", "answer_type": "score_1_5",
     "section": "Service quality assessment", "section_bn": "সার্ভিস কোয়ালিটি এসেসম্যান্ট",
     "guidance_en": "(What to look for: visiting on time, memo done correctly and kept, solving the shopkeeper's complaints)",
     "guidance_bn": "(যা দেখতে হবে: টাইমলি দোকানে আসা, মেমো সঠিক ভাবে করা এবং সংরক্ষণ, দোকানদারের অভিযোগ সমাধান করা)"}
  ]$j$::jsonb;
BEGIN
  IF EXISTS (SELECT 1 FROM app.rubric_version WHERE rubric_id IN (SELECT id FROM app.rubric WHERE kind = 'joint_call') AND criteria @> '[{"key": "sales_situation", "section": "5-step sales call assessment"}]'::jsonb) THEN
    RETURN;
  END IF;
  SELECT id, version INTO rid, cur FROM app.rubric WHERE kind = 'joint_call' AND status = 'active' ORDER BY id LIMIT 1;
  IF rid IS NULL THEN
    INSERT INTO app.rubric (kind, version, status) VALUES ('joint_call', 1, 'active') RETURNING id INTO rid;
    INSERT INTO app.rubric_version (rubric_id, version, criteria) VALUES (rid, 1, v1);
    cur := 1;
  END IF;
  INSERT INTO app.rubric_version (rubric_id, version, criteria) VALUES (rid, cur + 1, v2);
  UPDATE app.rubric SET version = cur + 1, updated_at = now() WHERE id = rid;
END
$joint$;

INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class)
  SELECT coalesce((SELECT max(config_version) FROM app.cfg_version), 0) + 1, 'change', u.id,
         'AMO manual build dev content (Perf Assessment brand list, seed)', 1
    FROM app.app_user u
   WHERE u.username = 'aron.system'
     AND EXISTS (SELECT 1 FROM app.cfg_key WHERE key = 'cfg.rubric.distribution_brands')   -- V0150; a database migrated before it is left alone
     AND NOT EXISTS (SELECT 1 FROM app.cfg_version WHERE summary = 'AMO manual build dev content (Perf Assessment brand list, seed)');
INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason)
  SELECT 'cfg.rubric.distribution_brands', 'global', 0,
         (SELECT coalesce(jsonb_agg(b.id ORDER BY o.n), '[]'::jsonb)
            FROM unnest(ARRAY['Maxim', 'Avon', 'ARIS', 'Marise', 'Black Diamond', 'Sunmoon', 'Supreme', 'Special Abul Bidi', 'Existing Abul Bidi%',
                              'Ananda Bidi', 'Abul Bidi Gold', 'Abul Bidi Style', 'Aster', 'Flame Box', 'Salmon', 'ESSE', 'MAX', 'Maxim - Platinum Series'])
                 WITH ORDINALITY AS o(name, n)
            JOIN app.product_node b ON b.level = 'brand' AND b.name LIKE o.name),
         TIMESTAMPTZ '2026-01-01T00:00:00Z', cv.config_version, u.id, 'AMO manual build dev content (screenshot 27 order)'
    FROM app.app_user u
    JOIN app.cfg_version cv ON cv.summary = 'AMO manual build dev content (Perf Assessment brand list, seed)'
   WHERE u.username = 'aron.system'
     AND NOT EXISTS (SELECT 1 FROM app.cfg_value x WHERE x.key = 'cfg.rubric.distribution_brands' AND x.scope_type = 'global' AND x.config_version = cv.config_version)
     AND EXISTS (SELECT 1 FROM app.product_node WHERE level = 'brand');
