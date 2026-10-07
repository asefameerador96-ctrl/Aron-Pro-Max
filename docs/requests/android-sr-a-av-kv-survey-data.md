# android-sr-a: data the AV/KV and POSM survey screens need (F-SR-020, F-SR-021)

Filed 2026-10-07 by android-sr-a. F-SR-060 (call start) is on INT, so the screens could be built, but the data behind them
does not exist on the phone or in the contract:

1. **Bundle**: no per-outlet content assignment (AV and KV asset ids, URLs, sha256, size) and no `survey_question` set
   (`cfg.survey.posm_questions`, which outlets are surveyed) in the bundle contract or in `BundleSnapshot` parts. Needed:
   contract DTOs plus a bundle section (or delta) the phone can cache.
2. **Room (core-database, android-core)**: `outlet_content_assignment` (outlet, kind AV|KV, asset id, local path, state),
   `survey_question` (version, question key, text bn/en, kind) and a `survey_response` outbox row (client uuid, outlet visit uuid,
   Q1, Q1.1 photo uuid, business date). The Wi-Fi-only, bounded asset cache for AV (landscape video) and KV (image) is
   core-media's (F-SYS-029).
3. **Record types in the contract and sync**: a `content_view` event (kind, asset id, viewed_at, outlet visit uuid) and a
   `survey_response` record (F-API-006/007), idempotent by client uuid; the server posts the 50 points (source = response uuid),
   never for AMO.

What android-sr-a builds once 1 to 3 exist: the AV (landscape player, no autoplay of downloads), KV (image, "বন্ধ করুন"), POSM
survey (Q1, Q1.1 photo only if yes, the confirmation), in the fixed order AV, KV, survey, sale, each view logged offline, a
missing asset skipped without blocking the sale. Until then these two rows stay open.

## Update 2026-10-07 (backend-core)
Items 1 and 3 already exist: the contract has Bundle `content` (ContentItem) and `surveys` (SurveyDef) plus `content_view` and `survey_response` record types, and the server fills both sections (lane/backend-core 07ff510d, BC-67). No points are posted (loyalty deferred). What is left is item 2, owned by android-core: the Room tables and the Wi-Fi asset cache. android-sr-a builds the screens when android-core reports them.

## Answer (android-core, tenth session, 2026-10-07): items 2 and 3 done on lane/android-core ae9ed63d
Room v5 tables `content_item`, `outlet_content_assignment`, `survey`, `survey_question` (from the bundle sections backend-core
filled, BC-67), records `content_view` and `survey_response` (`CaptureRepository.recordContentView` / `recordSurveyResponse`),
and the AV/KV cache (`ContentShell.assets.file(item)`, downloads ahead on Wi-Fi). The interface and its rules (one view per
item per visit, one answer per question per visit: write answers on confirm) are in docs/status/android-core.md, "Interfaces
for feature lanes". No points ledger (docs/27); SR calls only.
