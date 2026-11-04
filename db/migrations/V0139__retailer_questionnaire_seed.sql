-- V0139 the Visit Query questionnaire (contract docs/requests/tso-manual-build-2026-10.md s2; D-TSO-MAN-35; gap item
-- VQ-01; lane F2). The TSO's Visit Query (screenshot 37) asks the retailer two free-text questions; until now no
-- environment had an active retailer_questionnaire rubric, so the phone had nothing to show. Version 1 is seeded here
-- (system reference data, like V0027's code lists): Bangla from screenshot 37, English from the manual. Answers are
-- free text up to 1000 characters (app.call_assessment_answer.answer_text, D-TSO-MAN-35 amends D-199's 500).
-- Idempotent: nothing is written when a retailer_questionnaire rubric already exists (an admin may have created one on
-- the content page; it is kept as it is). rubric_version rows are append-only (V0023 guard).

SET lock_timeout = '5s';

WITH r AS (
  INSERT INTO app.rubric (kind, version, status)
  SELECT 'retailer_questionnaire', 1, 'active'
   WHERE NOT EXISTS (SELECT 1 FROM app.rubric WHERE kind = 'retailer_questionnaire')
  RETURNING id
)
INSERT INTO app.rubric_version (rubric_id, version, criteria)
SELECT r.id, 1,
       '[{"criterion_id": 1, "key": "sr_visits_regularly", "label_bn": "SR আপনার দোকান নিয়মিত ভিজিট করে?", "label_en": "Does the SR visit your shop regularly?", "answer_type": "text"},
         {"criterion_id": 2, "key": "sr_prints_memo", "label_bn": "SR নিয়মিত মেমো প্রিন্ট করে দেয়?", "label_en": "Does the SR give you a printed memo regularly?", "answer_type": "text"}]'::jsonb
  FROM r;
