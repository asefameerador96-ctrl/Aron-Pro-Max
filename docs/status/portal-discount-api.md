# Status: lane portal-discount-api (Lane A), started 2026-11-11

Branch `lane/portal-discount-api` from INT `claude/wonderful-thompson-k6ejnf`. Push only that branch; no pull request. Scope: `docs/status/portal-discount-brief.md` (on `lane/lead-docs-1022`); its s8 defaults are the answers until the owner says otherwise. The field does not change (memo offer line zero, bundle `offers` empty).

## Done
- Train 1 (schema and contract; D-DISC-API-01 to 03): V0152/V0153 (draft status, request_uuid, append-only trail, immutable versions, dw.v_offer and dw.v_offer_version, view contract 1.6), contract 1.12.0 (request file docs/requests/contract-1.12-discount-portal.md, lead ruling D-LEAD-CONTRACT-112-01), regenerated slices and web types. Checked head 5f694a0b5.
- Train 2 (Ktor admin API; D-DISC-API-04): `AdminOffers.kt` (six operations of `/v1/admin/offers`), permission module `discounts` (AdminModules, V0154/V0155, contract AdminModule, dev seeds), BRAND read routes in PortalLevels, scope-cases lines, `AdminOffersTest` (10 tests), db tests.

## Gate results with the pinned CI tools (tools/ci/install-tool.sh; head of train 2)
- `tools/ci/migrations-check.sh d58e64632 <squawk>`: "4 new migration(s)", "migrations: ok" (V0152 to V0155; the only `squawk-ignore` left is `require-concurrent-index-creation` on the empty `offer_version` table).
- `tools/ci/contract-breaking.sh d58e64632 <oasdiff>`: 18 breaking by rule, all `response-property-enum-value-added` (`draft` on `Offer.status` where `Offer` is a oneOf member of the sync responses, and `discounts` on `AdminModule`); approved by the version bump 1.11.2 to 1.12.0 and docs/requests/contract-1.12-discount-portal.md under D-LEAD-CONTRACT-112-01; `contract: ok`.
- Redocly 2.59.0 clean; `python3 tools/slice-contract.py --check` clean.

## Not done / not tested
- Web (Lane B): `GRANT_MODULES` in web/src/lib/admin/access-grants.ts, modules.ts and the i18n keys do not know `discounts` yet; the pages do not exist.
- The Android MoneyGateReviewTest needs the Android SDK and no android/ file changed; it was not run here.
- `channel` scope nodes and `drp_slide` are refused 400 in this release (no id for a channel; docs/27).

## HANDOVER
Local DB tests need a PostgreSQL 16 and `ARON_TEST_PG_URL`: follow the existing repo instruction (docs/26-lane-playbook.md and docs/24-build-spec.md D24-05); no credential belongs in this file. Run one Gradle at a time (two runs collide on db/build/kotlin). Pinned CI tools: `tools/ci/install-tool.sh squawk|oasdiff <dir>`; gates: `tools/ci/migrations-check.sh <base> <squawk>` and `tools/ci/contract-breaking.sh <base> <oasdiff>`.
