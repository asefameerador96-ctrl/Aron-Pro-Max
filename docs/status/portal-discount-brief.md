# Discount/campaign portal: scoping brief (lead, 2026-11-11)

Status: lead brief, written 07:33 to 07:45 UTC from a six-reader research pass and an Opus critique; the owner's answers to section 8 can change it; lanes start on the defaults. Planning doc to follow at docs/36-discount-portal.md.

## 1. Why now

The owner set the post-AMO order on 2026-11-06 (DECISIONS.md:1753, D-LEAD-ROADMAP-02). Item 1 is the "Discount and campaign management portal (manager = Discount admin)". It "supersedes the 'later' of docs/27 with an order". Loyalty, Target, MDM and God Mode follow. The same paraphrase is in docs/status/usage.md:1880-1883. The order says the work starts after the AMO app is complete. Lead confirmation (2026-11-11 07:45 UTC): the AMO app is feature-complete on INT as of trains 88 to 91 (every manual screen, field units, team screens, call targets, verification); what remains is the device walk (blocked on the owner's laptop) and small follow-ups listed in docs/status/android-amo-calls.md. The portal work therefore starts now, in parallel with those follow-ups.

docs/27-deferred-programmes.md:1-19 still defers every discount programme. The roadmap message is our authority to start the PORTAL. It is not authority to change the field. The memo's offer-discount line stays zero in the SR, AMO and TSO apps until the owner says otherwise (CLAUDE.md rule 2; D-TSO-MAN-02 at DECISIONS.md:1404).

## 2. Standing rules that bind

- Units (rule 5): storage and server aggregates stay in base units via `pack_factor` / `qty_base`. The dashboard shows base units.
- Money: integer milli-taka only. Discounts are `discount_bp` or `discount_mtk_per_unit` (V0003:137-162).
- Scope (rule 16, docs/34:8-34): seven portal levels. Scope comes from token claims `geo`, `brands`, `modules`. The client never sends scope IDs. An ungranted page is not listed and its API returns 403.
- Look (rule 14a): vendor screenshots win. None exist for this portal (no image file in docs/ui-reference matches offer, promo, campaign or scheme). We build on the existing admin kit and rail until they arrive.
- No pull request until the owner approves the flow (rule 11).
- Test account, pilot size (rule 1, docs/28). This needs no new Azure resource.
- No secrets in code, logs or docs (rule 8).

## 3. What exists today

| Area | What is there | Forced zero? |
|---|---|---|
| Schema | `app.offer` (code, group_code, status active/inactive), `app.offer_version` (offer_type, level, valid_from/to, threshold_qty_base, discount_bp, discount_mtk_per_unit, reward_sku_id, stacking, rule jsonb), `app.offer_version_product`, `app.offer_version_scope` (V0003:121-183) | Empty; nothing writes them |
| Schema | `app.programme` (kind includes campaign), enrolment, gift tables (V0007:78-135) | Empty hooks |
| Memo | `app.memo.offer_discount_mtk`, `drp_discount_mtk`, `offer_version_ids`, `memo_net_equation` (V0007:695-723); `app.memo_discount` (V0007:807-840) | Phone ingest is NOT forced zero (MemoChecks.kt); paper path is (IngestService.kt:263-264, PaperBackfill.kt:99) |
| dw | `dw.v_memo`, `dw.v_daily_route` expose discount totals (V0118) | No view for offer_version or memo_discount |
| Contract | `Offer`, `OfferWrite`, `OfferPatch`, listOffers/createOffer/updateOffer (contract/openapi.yaml:3394-3452, 14028-14113), version 1.11.2 | Bundle `offers` always empty (BundleService.kt:48,164) |
| Backend admin | No offer route. `/v1/admin/offers/**` maps to module `targets` (AdminModules.kt:101). Prices pattern in AdminPrices.kt | Deferred (AdminProducts.kt:45) |
| Web admin | Generic CRUD entities and bespoke pages; no offers page, mock or module map (web/src/lib/admin/modules.ts) | op-rules.ts:59 rejects non-zero discount |
| Field | SaleReview.kt:71 keeps offer zero; memo and print already render and sum it (MemoScreens.kt:258-275, PrintModels.kt) | Yes, by MoneyGateReviewTest.kt:51 |

## 4. Proposed v1 scope

An admin section "Discount programmes" in the web portal.

- Programme create, read and update (no delete; a programme is ended): name (title_en, title_bn), code, type, scope, date window, status, change reason.
- Types:
  - Price-off per SKU. Maps to the existing `amount_per_unit` and `pct_discount`. Vendor evidence shows a money discount per SKU (docs/ui-reference/sr/sales-summary.md:21,31-32).
  - Quantity-based. Maps to `free_qty` with `threshold_qty_base`. The vendor memo may show a goods-in-quantity row (sr/memo.md:19-20,26-27), but the meaning of "SL Match 3 / 0.00" is still unknown (D-343).
  - Bundle. ASSUMPTION. No schema type exists. v1 stores it only after the owner gives rules (Q1).
  - `drp_slide` is not offered in v1. ASSUMPTION: DRP already runs in the field, and where its rates come from was not checked.
- Scope: brand or SKU via `offer_version_product`; wing to route and channel via `offer_version_scope`; date window via valid_from/valid_to.
- Status: draft, active, inactive (the UI calls inactive "Ended"). Today's CHECK allows only active and inactive, so a new migration adds draft. Keeping inactive avoids removing an enum value from the contract.
- Versioning: every rule change is a new immutable `offer_version`. This is the existing V0003 scheme (`UNIQUE (offer_id, version_no)`, recorded in `memo.offer_version_ids`), not the code-list scheme.
- Audit: every write through `AuditLog.write`, with a mandatory reason.
- Read views per role (see section 6).

Non-goals for v1:
- No change to field pricing, the sale screen, memos, print or the bundle.
- Active in the portal does not reach the field in v1. The bundle `offers` array stays empty.
- No discount engine, no stacking evaluation, no slabs or thresholds we invent (docs/27:15).
- No new outbox event. Later engines read `dw.v_*`, not the outbox (docs/31:46).
- No loyalty, Astha, gifts, targets or Campaign Enrollment tile (D-AMO-SHELL-03).
- No Discount Report rebuild. Its spec is not found (docs/reports/ is absent).

## 5. Data model sketch

New forward-only migration `V0152` (the latest is `V0151__dw_view_contract_1_5.sql`; numbering has gaps, so take the next free number at train time). Never edit V0003.

- Replace the `app.offer.status` CHECK with draft, active, inactive. Add it NOT VALID, then validate, as V0131/V0132 did. No rows exist, so no data moves.
- Add `request_uuid uuid UNIQUE` to `app.offer_version`, so create and update replays are safe.
- Add `app.offer_status_change` (offer_id, from_status, to_status, request_uuid unique, reason, actor, at).
- Add a type value only if Q1 confirms bundle.
- New views `dw.v_offer` and `dw.v_offer_version` (with product and scope rows). docs/31:46 says later engines read `dw.v_*`, not app tables or the outbox.
- `COMMENT ON` every new column and view, following V0016. Restate the V0016:1217 comment in the new migration to say the portal exists while the field line stays zero.

## 6. API surface

Contract bump 1.11.2 to 1.12.0. Regenerate `contract/slices/` (tools/slice-contract.py) and `web/src/contract/openapi.d.ts`; both CI checks must pass.

- `GET /v1/admin/offers`, `GET /v1/admin/offers/{id}`, `GET /v1/admin/offers/{id}/versions`
- `POST /v1/admin/offers` (creates a draft)
- `PATCH /v1/admin/offers/{id}` (new version while draft)
- `POST /v1/admin/offers/{id}/status` (activate or end)

Scope checks:
- Writes: SUPERADMIN, or ADMIN with the `w` grant on the discount module (Q4). ADMIN with `r` reads.
- ASSUMPTION, pending Q9: NATIONAL reads all. BRAND reads only offers whose product scope touches its brands. WM, DMO and TSO read offers whose geo scope meets their `Reach`, plus empty-scope (everywhere) offers.
- SR and AMO have no portal login.

Validation: the V0003 CHECKs in code too; `valid_to >= valid_from`; money as integers; reason 10 to 500 characters.

Idempotency: every write carries a client `request_uuid`. Replay returns the first result. A different fingerprint returns 409, as the prices `batch_uuid` does (AdminPrices.kt:357).

## 7. Acceptance

- Every endpoint has happy, 400, 403 and 409 tests, plus a replay test.
- A scope matrix test per role: all seven levels, plus SR, AMO and SUPPORT refused (SUPPORT is in web `ADMIN_PORTAL_ROLES` today). RouteInventoryTest stays green.
- Web: empty, loading, offline, error and long Bangla states (e2e/long-bangla.spec.ts). bn and en key parity (tests/i18n.test.ts).
- Perf: the existing docs/31 web gates. No new budget is invented here.
- Money scan: no float or double on any `_mtk` or `_bp` path. MoneyIngestOracleTest stays green.
- Field-zero guards stay green: MoneyGateReviewTest.kt:51, BundleAcceptanceTest.kt:214, PaperBackfillTest.kt:174.

## 8. Owner questions

1. Which programme types do you need, and what is a bundle? Default: price-off and quantity-based only; bundle waits.
2. Do two programmes on one SKU stack? Default: `exclusive`.
3. Who approves activation? Default: Super Admin, as for prices (AdminPrices.kt:411).
4. Does the Discount admin get its own permission module? The roadmap makes the four new admins per-module admins (DECISIONS.md:1753). Default: a new `discounts` module, moved off `targets` (AdminModules.kt:101).
5. What is a "campaign" here? Default: a label on `offer.group_code`; `app.programme` kind campaign stays empty.
6. Should thresholds be entered in packets, boxes or dozens, and is a price-off per unit per stick/piece or per packet/box/dozen? Default: entry and display in the field unit of the SKU (units rule 5, as the apps do), stored in base units (`threshold_qty_base`; `discount_mtk_per_unit` per base unit, converted once with `pack_factor` and shown in both units in the portal).
7. Can you share screenshots of the vendor discount setup and the Discount Report? Default: admin kit look until they arrive.
8. Can you share the current promotion list (open Q13, Q26 at DECISIONS.md:840,853)? Default: the portal ships with no seed rows.
9. Who outside Super Admin and the Discount admin may read programmes? Default: National, Brand, WM, DMO and TSO read only, within their scope. A Brand User also sees programmes that mix its brands with others.
10. Should the server reject phone memos with a non-zero offer line while the field stays zero? Default: no change in v1; we log it as a risk.

## 9. Lane plan

- Lane A (Sonnet): schema and backend. Trains: migration and dw views; contract bump and slices; routes, module map, audit, tests.
- Lane B (Sonnet): web. Trains: entity and menu; bespoke scope and product editors (nearest analogue `scope-editor.tsx`); i18n; mock handlers and e2e.
- Opus checker on money, scope and the migration (rule 10). Haiku only for mechanical slice regeneration.
- Budget: each lane recycles at about 450 k tokens (rule 10). Actuals go in docs/status/usage.md. No total is set until the owner answers Q1.
- Each lane should fit in two to three days.
- Planning doc: `docs/36-discount-portal.md` (lead decision; docs/35 is the final Azure design pack, so 36 is the next free number).
- The lead gates: the migration diff, the contract diff, the scope matrix, the field-zero guards and the no-PR rule.

## 10. Risks

- Field leak: a non-empty bundle or a lifted op-rules guard would change selling. The guards above must stay.
- Ingest gap: the phone path accepts discount lines today (Q10).
- Module drift between AdminModules.kt and web modules.ts.
- Roles: web roles.ts still lists TOP. Token claims for NATIONAL and BRAND were not verified in code.
- Rework when vendor screenshots arrive (rule 14a).
- Scope creep into a discount engine, which docs/27 still defers.
