# Loyalty portal: scoping brief (lane portal-loyalty-brief, 2026-11-11)

Status: docs-only brief, modelled on `docs/status/portal-discount-brief.md`. Every ASSUMPTION is marked. The owner's answers to section 10 can change it; lanes start on the defaults. Planning doc to follow at `docs/37-loyalty-portal.md` (lead decision; 36 is the discount portal).

## 1. What the current Aron does for loyalty

The Loyalty portal is roadmap item 2 (D-LEAD-ROADMAP-02, DECISIONS.md:1753). The roadmap authorises the PORTAL only. Loyalty in the field apps stays deferred (docs/27:1-19; rule 2). Nothing here changes the SR, AMO or TSO apps, the memo or the bundle.

Four programmes exist today (docs/10-kpis-and-programs.md "Programs"):

- **Astha** (docs/06-sr-app.md:40-45, docs/10 "Astha"): a quarterly programme for Astha-channel outlets. Tiers are sub-channels: Platinum, Gold, Diamond, Silver. Each outlet has per-brand STD targets and a memo target, with achievement, remaining and %. The TSO chooses one gift per outlet in the TSO portal. The SR hands it over and takes one photo per outlet (docs/06:50). The web "Astha Gift Choice Report" lists the choices. The AMO app is the same with a route selector (docs/07-amo-app.md:52-54).
- **Diamond League** (docs/06:47-51, docs/10): outlets earn monthly points. The SR redeems them for cash back (2 Tk a point, up to 199 points) or gifts (kitchen rack 200, chair 200, tornado fan 400). Points are deducted on confirm. Every gift hand-over is photographed ("... Gift Verify"). The web "Campaign Gift Redemption" report follows.
- **Superstar** (docs/10 "Superstar"): a monthly outlet campaign. Each outlet has a category, incentive slab, base target, STD and memo targets with achievement, and a sales-criteria-met flag. The web "Superstar Campaign Report" follows.
- **Campaign gifts** (docs/06:51): per-redeemed-gift photo capture.

Known data bug to guard (docs/06:45, docs/10): a negative Astha target produced -37,500% achievement. Targets must be >= 0 and every % must guard divide-by-zero.

Not found: exact tier thresholds, point-earning rules beyond the POSM survey (50 points once per response, docs/24-build-spec.md:540, `cfg.loyalty.earning_rules` at :1062), Superstar slab values, and the Astha/Superstar/Gift report specs. `docs/reports/` has no loyalty file. docs/27:15 forbids inventing slabs or thresholds. The web menu hides Astha Gift Panel and Superstar Program (docs/34:65-66).

## 2. What already exists as hooks

| Area | Hook | Cite |
|---|---|---|
| Schema | `app.programme` (kind diamond_league, astha, campaign, superstar; period_label, active_from/to, points_expire_on, `rules` jsonb, status active/inactive, `version`) | V0007:78-103 |
| Schema | `app.programme_enrolment` (outlet, league_label, tier_code, base_target, valid_from/to, gist EXCLUDE: no overlapping enrolment per programme and outlet) | V0007:105-115 |
| Schema | `app.gift` (code, names, `points_cost` 1..100000 or null for Astha, `tier_codes`, image_url) | V0007:116-132 |
| Schema | `app.gift_assignment` (programme, quarter, outlet, route, gift, tier, chosen_by, photo, locked_at; unique per programme, quarter, outlet) | V0007:134-151 |
| Schema | `app.astha_target` (month, route, outlet, brand; `std_target numeric(16,3)` >= 0, `memo_target`, `batch_uuid`) | V0007:154-168 |
| Schema | `app.loyalty_ledger` (append-only points; unique `(source_type, source_id)`) | V0007:171-185 |
| Schema | `app.redemption` and `app.gift_photo` (phone records) | V0007:1640, 1726 |
| Dictionary | Comments on these tables | V0016:127-140, 949-977 |
| Contract (1.12.2) | `/v1/admin/programmes`, `/{id}`, `/{id}/enrolments`; `/v1/admin/gifts`, `/{id}`; `Programme`, `ProgrammeWrite`, `ProgrammeKind` | openapi.yaml:6078-6230, 15586-15640 |
| Contract | Phone operations `getLoyaltyBalances`, Astha targets, gift choices (not implemented, 501 per docs/27:11) | openapi.yaml:5024-5110 |
| Backend | Module map sends programmes, gifts and `/v1/programmes/**` to module `targets` | AdminModules.kt:101-104 |
| Backend | `AdminContentApi.kt:118` says it is "trimmed per docs/27: no programme definitions, no gifts" (no route) | AdminContentApi.kt:118 |
| Rules | `LoyaltyMath` (points total, cash at 2,000 mtk a point), marked UNUSED | shared/rules/.../Loyalty.kt |
| Config | `cfg.loyalty.*` (program_active, earning_rules, expiry_days 7, cash rate, cash max 199, photo, roles, negative policy) | docs/24-build-spec.md:1061-1068 |
| Web | None: no loyalty page, mock or module. `GRANT_MODULES` has no `loyalty` | access-grants.ts:20; modules.ts |
| dw | None: grep of db/migrations and docs/dw-view-contract.md finds no programme, gift or loyalty view | |

The current `app.programme.status` allows only active/inactive (V0007:93).

## 3. Who does what (docs/34:8-34)

Scope comes from token claims `geo`, `brands`, `modules`; the client never sends scope. An ungranted page is not listed and its API returns 403.

- Create, edit, activate, end: SUPERADMIN, or ADMIN with the `w` grant on module `loyalty` (the "Loyalty admin", roadmap item 2). ASSUMPTION: activation and gift-catalogue changes are Super Admin only, as prices are (AdminPrices.kt:411).
- Read: ADMIN with `r`; NATIONAL reads all; BRAND reads programmes and progress for its brands only (ASSUMPTION: a programme with brand-scoped targets or gifts of its brands). WM, DMO and TSO read enrolled outlets and progress within their `geo` reach. SR and AMO have no portal login. SUPPORT is not a portal level; it must be refused as in the discount scope matrix.
- Outlet tier and enrolment are outlet data. Scope uses the outlet's geography and its channel (`outlet.channel`, docs/34:51-53).

## 4. Units and money

- Rule 5: the field counts packets, boxes and dozens. Storage and server aggregates stay in base units via `pack_factor` / `qty_base`; the dashboard shows base units. Money is integer milli-taka.
- `astha_target.std_target` is "standard units" `numeric(16,3)` (V0016:135), not base sticks. ASSUMPTION: STD means the standard unit of the existing target model (`app.target`), and v1 shows it as stored with its unit label. **Question (Q1):** are tier thresholds and Superstar/Astha targets in sticks (base), packets, or STD?
- Points are integers (`loyalty_ledger.points int`). Cash value is points x `cash_rate_mtk_per_point` (default 2,000 mtk) in integer arithmetic (Loyalty.kt). No float or double on any `_mtk` path.
- Gift cost is points (`points_cost`). v1 stores no taka value for a gift. A gift value in mtk is an open question (Q4).

## 5. Proposed v1 slice

An admin section "Loyalty programmes" in the web portal.

In v1:
- Programme definition with kind, code, titles (bn/en), period, date window, change reason, and the lifecycle draft / active / inactive (shown as "Ended"). Today's CHECK needs a `draft` value, like `app.offer` (V0156 pattern).
- Versioned rules: each rule change is a new immutable version, in the Discount style (`UNIQUE (programme_id, version_no)`), not an edit in place.
- Scope by product (brands/SKUs), geography (wing to route) and outlet channel (GT, DCC, Astha, RCC, MT, HoReCa).
- Tier rules and enrolment: tier codes with thresholds as DATA the owner supplies (nothing invented), and per-outlet enrolment using the existing gist rule (no overlap).
- Gift catalogue: code, names, points cost, tier availability, image (existing `admin_asset` upload, `gift_image` type).
- Read-only progress views from dw aggregates: enrolled outlets per tier, target vs achievement (guarded %), points earned/redeemed/expiring from `dw.v_loyalty_*` (ledger is empty until the field posts).

NOT in v1:
- Field redemption, points earning, cash back, ledger postings, gift hand-over photos, the SR/AMO/TSO Astha and Loyalty tiles, memo lines, print, bundle `programmes` (stays null).
- The nightly ledger and expiry job. Any engine that computes tiers or points. Any slab we invent (docs/27:15).
- Discount programmes (item 1), Target management (item 3).
- The 501 phone operations stay 501.

## 6. Data model sketch

Forward-only migrations from V0158 (take the next free number at train time; never edit V0007).
- Widen `app.programme.status` to draft/active/inactive (NOT VALID, then VALIDATE, as V0154/V0155).
- ASSUMPTION: add `app.programme_version` (programme_id, version_no, rules jsonb, tiers, scope rows, valid window, `request_uuid uuid UNIQUE`) rather than overloading `programme.rules` and `programme.version` (an optimistic-lock counter). Plus `app.programme_scope` rows for product, geography and channel, like `offer_version_product/scope` (V0003:121-183).
- `app.programme_tier` (programme_id, tier_code, label_en/bn, threshold_value, threshold_basis) only if Q1 gives a basis.
- `app.programme_status_change` (from, to, request_uuid unique, reason, actor, at) for replay-safe activate/end.
- Reuse `app.gift` and `app.programme_enrolment`; add `request_uuid` where a write is not yet replay-safe. `gift_assignment`, `astha_target`, `loyalty_ledger` are read-only in v1.
- Every write through `AuditLog.write` with a mandatory reason (10 to 500 characters).
- New views `dw.v_programme`, `dw.v_programme_version`, `dw.v_gift`, `dw.v_enrolment`, `dw.v_loyalty_balance` (docs/31:46). `COMMENT ON` every new column and view (V0016 style); regenerate `docs/data-dictionary.md`. Restate that the portal exists while the field stays deferred.

## 7. API sketch

Contract 1.12.2 to 1.13.0; regenerate `contract/slices/` and `web/src/contract/openapi.d.ts`.
- Reuse the existing operations where they fit: `listProgrammes`, `createProgramme`, `updateProgramme`, `listGifts`, `createGift`, `updateGift`, `put/listProgrammeEnrolments`. Add under `/v1/admin/loyalty`: `GET /programmes/{id}/versions`, `POST /programmes/{id}/status`, `GET /programmes/{id}/progress`, `GET /tiers`, `GET /redemptions` (read-only). ASSUMPTION: the old `/v1/admin/programmes` and `/v1/admin/gifts` paths stay (no removal) and gain the module `loyalty`.
- Enum additions: `ProgrammeStatus` (draft, active, inactive) replacing `ActiveStatus` on `Programme`, as `OfferStatus` did. A new value in a response enum is reported breaking by `tools/ci/contract-breaking.sh`; it passes only when the same push bumps `info.version` AND adds an added/modified, lead-approved `docs/requests/contract-1.13-loyalty-portal.md` naming the change (same as `contract-1.12-discount-portal.md`).
- Module map: move `/v1/admin/programmes/**`, `/v1/admin/gifts/**` and the new `/v1/admin/loyalty/**` from `targets` to `loyalty` (AdminModules.kt:101-104). `/v1/programmes/**` (phone) stays on `targets` while it is 501. A migration carries today's ADMIN access over, as V0154 did.
- Every write carries `request_uuid`; a replay returns the first result, a different fingerprint returns 409 (AdminPrices.kt:357 pattern). Writes use `If-Match` where the contract already does.
- Validation: dates, `points_cost` range, targets >= 0, thresholds non-decreasing by tier, money as integers.

## 8. Web sketch

Section `/admin/loyalty` under the existing admin kit and rail; the discounts section (`web/src/app/admin/discounts`, `offer-form.tsx`, `offer-status-panel.tsx`) is the nearest analogue. Pages: programmes list, detail with versions, new/edit with scope and tier editors, gift catalogue, enrolment list, progress.
- Add module `loyalty` to `GRANT_MODULES` and the label map (access-grants.ts:20-41), and the route and menu entries (modules.ts:45-68). Hide, do not grey out, for ungranted users (docs/27:16; docs/34 s1).
- Bangla/English key parity (tests/i18n.test.ts); states: empty, loading, offline, error, long Bangla (e2e/long-bangla.spec.ts). axe A/AA as in `e2e/discounts-quality.spec.ts`.
- Perf: existing docs/31 web gates; no new budget invented. Mock handlers beside `web/mock/offers.ts`.
- Look: no vendor screenshot exists for these screens (docs/ui-reference/web/ only names the menu items); admin kit until the owner uploads some (rule 14a).

## 9. Risks, lanes, estimate

Risks:
- Rework: no business rules or screenshots for tiers, slabs or reports (Q1, Q9, Q10).
- Field leak: bundle `programmes` must stay null; the phone operations stay 501.
- Module drift between AdminModules.kt and modules.ts; the `targets` to `loyalty` move must carry grants.
- Overlap of programme rules with Discount and Target (shared `app.programme`, `astha_target`).
- Superstar and Diamond League points need a ledger engine that is out of scope.
- Roles: NATIONAL/BRAND claims need the same checks as the discount scope matrix.

Lane split (same two lanes as Discount, Sonnet, Opus checker on migration, scope and money; Haiku for slice regeneration):
- **Lane A (API):** train 1 migration and dw views; train 2 contract 1.13.0, slices, module map; train 3 routes, audit, tests (happy, 400, 403, 409, replay; scope matrix for all seven levels plus SR, AMO, SUPPORT refused).
- **Lane B (web):** train 1 entity, menu, grants; train 2 scope/tier/gift editors; train 3 i18n, mock, e2e, a11y.
- Estimate: 6 trains, about 2 to 3 days per lane, in parallel; one lead gate train for the migration diff, contract diff and field-zero guards. Not firm until Q1 and Q2 are answered.
- The lead gates the field-zero guards (BundleAcceptanceTest, MoneyGateReviewTest) and the no-PR rule.

## 10. Owner questions (default if unanswered)

1. Are tier thresholds and targets measured in sticks (base), packets or STD? Default: STD as stored, with the unit shown; thresholds supplied as data, none invented.
2. Which programmes does v1 cover? Default: Astha and Diamond League definitions, tiers, gifts and enrolment; Superstar and campaign gifts as definitions only.
3. Who approves activation and gift-catalogue changes? Default: Super Admin only.
4. Does a gift carry a taka value as well as points? Default: points only.
5. May two programmes overlap on one outlet? Default: no overlap per kind (the gist rule); different kinds may overlap.
6. Which reader roles? Default: National all; Brand own brands; WM/DMO/TSO within geo; Admin by grant.
7. Do the old `/v1/admin/programmes` paths move to a `loyalty` module? Default: yes, with grants carried over.
8. Is the permission module named `loyalty` and the role "Loyalty admin"? Default: yes.
9. Which reports does the owner need (Astha Gift Choice, Campaign Gift Redemption, Superstar)? Default: progress views only; no report rebuild.
10. Can the owner upload screenshots of the vendor loyalty setup and reports, and the current tier/gift workbook? Default: admin kit look, empty seed.
