# Status: lane portal-discount-api (Lane A), started 2026-11-11

Branch `lane/portal-discount-api` from INT `claude/wonderful-thompson-k6ejnf`. Push only that branch; no pull request. Scope: `docs/status/portal-discount-brief.md` (on `lane/lead-docs-1022`); its s8 defaults are the answers until the owner says otherwise. The field does not change (memo offer line zero, bundle `offers` empty).

## Done
- Train 1 (schema and contract; D-DISC-API-01 to 03): V0152/V0153 (draft status, request_uuid, append-only trail, immutable versions, dw.v_offer and dw.v_offer_version, view contract 1.6), contract 1.12.0 (request file docs/requests/contract-1.12-discount-portal.md, lead ruling D-LEAD-CONTRACT-112-01), regenerated slices and web types. Checked head 5f694a0b5.
- Train 2 (Ktor admin API; D-DISC-API-04): `AdminOffers.kt` (six operations of `/v1/admin/offers`), permission module `discounts` (AdminModules, V0154/V0155, contract AdminModule, dev seeds), BRAND read routes in PortalLevels, scope-cases lines, `AdminOffersTest` (10 tests), db tests.

## Gate results with the pinned CI tools (tools/ci/install-tool.sh; head of train 2)
- `tools/ci/migrations-check.sh <INT 34bef94a1> <squawk>`: "2 new migration(s)" (V0154, V0155), "migrations: ok" (V0152/V0153 are shipped; the only `squawk-ignore` left in them is `require-concurrent-index-creation` on the empty `offer_version` table).
- `tools/ci/contract-breaking.sh <INT 34bef94a1> <oasdiff>`: 39 breaking by rule, all `response-property-enum-value-added` (`discounts` on `AdminModule`, the `QtyUnit` words on `OfferSkuUnit.field_unit` where `Offer` is a oneOf member of the sync responses); approved by the version bump 1.12.0 to 1.12.1 and docs/requests/contract-1.12-discount-portal.md (1.12.1 section) under D-LEAD-CONTRACT-1121-01 (confirmed by the lead, 2026-11-11 11:12 UTC); `contract: ok`. (Against the pre-train-92 base, 1.11.2 to 1.12.0, the same gate reported 15 for `draft` on `Offer.status`.)
- Redocly 2.59.0 clean; `python3 tools/slice-contract.py --check` clean.

## Deploy hazard (D-DISC-API-06)
The 1.12.1 head must land together with Lane B's web head that adds `discounts` to GRANT_MODULES/modules.ts/menu. Alone, the contract turns the web CI red (tso-integration-web.test.tsx requires the AdminModule enum to equal GRANT_MODULES) and the deployed grants editor would drop every carried-over `discounts` grant on save. Never deploy the API alone.

## Follow-ups (not done)
Replay returns the stored version with the current status and ETag; `setOfferStatus` has no If-Match; no DB guard on status transitions; base quantities are not checked against pack_factor; old ADMIN tokens lacking the `discounts` claim get 401 ERR_SCOPE_CHANGED until refresh.

## Not done / not tested
- Web (Lane B): `GRANT_MODULES` in web/src/lib/admin/access-grants.ts, modules.ts and the i18n keys do not know `discounts` yet; the pages do not exist.
- The Android MoneyGateReviewTest needs the Android SDK and no android/ file changed; it was not run here.
- `channel` scope nodes and `drp_slide` are refused 400 in this release (no id for a channel; docs/27).

## HANDOVER (written 2026-11-11 after a9974b0788; branch held until the lead's verdict, then train 93 with Lane B)
**State.** Train 1 is on INT (train 92, contract 1.12.0, V0152/V0153, live on dev). Train 2 is pushed at a9974b0788 on `lane/portal-discount-api` (AdminOffers.kt, V0154/V0155, contract 1.12.1, tests); awaiting the lead's static gate and Sonnet records lens, then train 93 TOGETHER with Lane B's web head (never deploy the API alone, see above).
**Where things are.** Routes and rules: `backend/masterdata/.../AdminOffers.kt`; tests: `AdminOffersTest` (masterdata), `DiscountPortalV0152Test` (db), `DiscountPortalContractTest` (shared/contract); harness entries: `ContractConformanceTest` FX, `ScopeLeakAcrossEndpointsTest.seedOtherRows`, `scope-cases.txt`; records: DECISIONS D-DISC-API-01 to 07, request file docs/requests/contract-1.12-discount-portal.md.
**Local gate recipe.** PostgreSQL 16 plus `ARON_TEST_PG_URL` as in docs/26 (restart the service if the session reconnects; use a fresh database for `DatabaseSmokeTest`, it migrates the URL's own database and fails on a checksum if an older V0152 was applied there). One Gradle at a time. Pinned tools: `tools/ci/install-tool.sh squawk|oasdiff <dir>`; `tools/ci/migrations-check.sh <INT sha> <squawk>`; `tools/ci/contract-breaking.sh <INT sha> <oasdiff>`; `python3 tools/slice-contract.py --check`; Redocly 2.59.0; openapi-typescript 7.13.0 for `web/src/contract/openapi.d.ts`. Delete `tools/**/__pycache__` before the app tests (NoSecretsInRepoTest scans them). Never edit a shipped migration (V0152 to V0153 are shipped); a 1.12.x contract change needs the version bump plus the request file.
**Follow-ups (not done).** Replay returns the stored version with the offer's current status and ETag; `setOfferStatus` has no `If-Match`; no database guard on status transitions (the handler enforces them); base quantities are not checked against `pack_factor`; an old ADMIN token without the `discounts` claim gets 401 ERR_SCOPE_CHANGED until refresh. Open owner questions: what a `channel` scope node id is (refused 400 now); BRAND reading offers that name no product is the Q9 default; bidi in packets pending the owner (docs/34 s4.2).
