# Contract 1.15.0: loyalty and Astha, the FIELD side (lane portal-loyalty-api, train 3, 2026-11-12)

Approved by the lead as **D-LEAD-CONTRACT-115-01** (2026-11-12; the lead appends the DECISIONS line when the push is gated). **Status: applied in `contract/openapi.yaml`
(info.version 1.15.0) with push B.** Rulings: (1) the three value members and the optional upload member, additive; (2) `by_pack` with the member named `pack_factor`, achievement only; (3) V0180, V0181 and V0182 are working numbers; (4) `programmes` null when no programme is active in the reach. If sr-server is gated first it takes 1.15.0 and this moves to 1.16.0. 1.15.0 is additive on 1.14.0 (lane sr-server, V0173 to V0175). Migrations of this train start at
V0180 and follow LANDING order (the lead may order one rename at merge time). Owner basis: D-OWNER-SR-PROGRAMMES-01 (2026-11-12) puts the SR
programme screens in scope; memo discount lines stay zero (docs/27, rule 2). Rows cited: SRM-B-61..81 and 143..148 of
`docs/requests/sr-manual-build-2026-11-part-b.md`.

Everything below is stated so that an Android lane can build from it: unit, rounding and as-of are written out for each member.

## 1. Bundle section `programmes` (SRM-B-75, 61, 62, 65)

`ProgrammesSection` already exists in the contract (programmes, loyalty, gifts, astha_targets, gift_assignments, pending_gift_verifications).
It was always `null`; from 1.15.0 it is an object for any user whose routes have an active programme, and `null` only when no programme is
active in the user's reach (the phone then hides every programme tile). The section follows the user's route reach (token scope, never a list
from the client): outlets of the bundle's routes only. It is delta-capable (`programmes` is already in the delta section list) and size-budgeted
(docs/04): the budget and the measured size are in the push B report.

| Member | Content | Unit, rounding, as-of |
|---|---|---|
| `programmes` | Active programmes whose window covers the bundle's business date, `Programme` shape with `tiers`. Inactive and draft are not sent. | Dates are Dhaka business dates. |
| `loyalty` | `LoyaltyBalance` per (outlet, programme) of the user's outlets, enrolled outlets only. | `balance_points`, `expiring_points`: integer points. `as_of_date` = the PREVIOUS Dhaka business date: the phone shows "as of <date>" and never adds its own redemptions to it except the open basket it holds locally. `expiring_points` = the credits expiring on the EARLIEST upcoming `expires_on` (that date is `expiry_date`, else null), capped by the balance, never negative; no window, no `expiry_warning_days` key. The balance leaves out credits already past their `expires_on` (and the `expiry` debit rows, so an expiry is never counted twice). |
| `gifts` | Active gifts of the sent programmes. | `points_cost` integer points (null for Astha gifts). `tier_codes` filters Astha gifts by the outlet's tier. `image_url` is ALWAYS null in the bundle (a signed URL would change the content digest on every build and be cached): the phone takes the stable `image_asset_id` and fetches the bytes once per id with `GET /v1/sku-images/{asset_id}`, which also serves active gifts' pictures (purpose `gift_image`, at most 300 KB). No signed URL is stored anywhere. |
| `astha_targets` | `AsthaTargetRow` for the months of the active Astha quarter, one row per (route, outlet, brand, month); the route row has `outlet_id` null; the memo-target row has `brand_id` null ("All Brand"). | See section 2. |
| `gift_assignments` | `GiftAssignment` for the quarter, outlets of the user's routes. | `locked` is true once the hand-over photo exists. `chosen_at` is UTC. |
| `pending_gift_verifications` | Redeemed campaign gifts still short of photos (Push C fills it). | Counts are integers. |

Flags: a tile is hidden when its flag is off. The server omits the matching rows when `cfg.flag.astha_ui` / `cfg.flag.loyalty_ui` /
`cfg.flag.superstar_ui` is off, so a hidden programme sends no data.

Shop-wise list (SRM-B-65): the tier label of an outlet is `programme_enrolment.tier_code` (its `ProgrammeTier.label_*` is in `programmes[].tiers`),
falling back to the outlet's sub-channel name; `league_label` carries the Diamond League label.

## 2. `AsthaTargetRow` gains three members (additive; SRM-B-63, 64, 146)

- `value_target_mtk`: string, integer milli-taka, `>= 0`, `"0"` until the target is uploaded (owner default Q5). A string because milli-taka exceed 2^53 in sums; the phone parses with a 64-bit integer.
- `value_achieved_mtk`: string, integer milli-taka. Gross milli-taka of the SALE lines of the (outlet, brand, month) up to `as_of`, summed from memos on the server: memos that were superseded or voided are excluded (follow `supersedes_uuid`), the memo offer line is zero so net equals gross. Brand `null` = the whole outlet.
- `as_of` / `astha_as_of`: DISPLAY ONLY ("data as of"), never part of the rule below. The bundle carries one `astha_as_of` for the section (the newest memo ingest among the outlets of the rows actually sent, for the MONTH of the business date; the Dhaka start of the month when there is none) and no per-row `as_of`; `listAsthaTargets` carries the read time.
- **THE ACHIEVEMENT RULE (one rule, B1/B2 of the Opus pass; every other text refers here).** Server guarantee: every figure of the `programmes` section is read in ONE read-only REPEATABLE READ transaction on the PRIMARY database (never a replica), and includes every memo of this user whose sync acknowledgement was sent before the bundle request was received. Phone rule: the phone adds its own memos that had NOT been acknowledged when it SENT this bundle request, keyed by `memo_uuid`, recomputed from scratch after each bundle; the phone never uploads records while a bundle request is in flight (single-flight sync); if an acknowledgement arrives during the request it discards the section's achievement and refetches.
- **Android check (grep of `android/core-sync`, 2026-11-13): upload and bundle fetch are NOT yet single-flight.** `SyncEngine.run` takes a per-user `Mutex` (`lockOf(userId)`), so uploads are serialised with each other, but `BundleDownloader` does not take that lock, so an upload can run while a bundle request is in flight. For the Android lane: take the same lock around the bundle request (or refuse to start an upload while one is in flight), and discard and refetch if an acknowledgement arrives during the request. This lane did not change Android.
- Percent: the phone shows `achieved / target` as a percentage with 2 decimals, rounded half-up, and `0.00%` when the target is 0 (the -37,500% bug: targets are `>= 0` and every percentage guards the divide by zero).
- Units on the phone (rule 5): `std_target` / `std_achieved` (Decimal3) stay in base units (sticks) on the server; the phone shows packets for cigarettes and dozens for matches, one line PER PACK SIZE for a brand with 20s and 10s, never summed (SRM-B-146; rule 5 wins over the owner's screenshot).

**One line per pack size (ruling 2): `by_pack`**, optional, max 6 entries `{ pack_factor, std_achieved }`: `pack_factor` is the SKU base units per pack (20 or 10 for cigarettes), `std_achieved` is Decimal3 in the row's own base units. One entry per pack size with a sale in the month, ordered by `pack_factor`; empty on the All Brand row. The phone shows one line per entry and never sums across pack sizes (rule 5). The entries add up to the row's `std_achieved` (server test). The TARGET per pack size is not defined: it stays absent until the owner answers (owner question below).

**Owner question for the lead to carry:** the Astha STD target is uploaded per brand and month, not per pack size. Should the target be split by pack size (20s and 10s each with their own target)? Until the owner answers, the phone shows the brand target on its own line and one achievement line per pack size.

## 3. Phone reads and the portal upload

- `getLoyaltyBalances`, `listAsthaTargets`, `listGiftChoices` become real read-only online refreshes (the bundle stays the offline source). They reuse the section builders of the bundle for the same user and date and are cut to the caller's reach by the token. A failure on the phone keeps the bundle figure with its age.
- `putAsthaTargets`: `AsthaTargetWrite` rows gain optional `value_target_mtk` (string, integer milli-taka, `>= 0`, default `"0"`). Idempotent by `batch_uuid`: a replay answers the first result; the same `batch_uuid` with different rows is `409`; all-or-nothing; a negative or fractional value is `400 ERR_VALIDATION` with the indexed pointer (`body.rows[2].value_target_mtk`). Roles from `cfg.astha.target_entry_roles`. The web page request for the web lane is written with the handler in Push B.

## 4. New sync record `gift_choice` (SRM-B-66; Push C)

Fields: `client_uuid` (UUID v4, created when the SR taps the gift), `outlet_id`, `gift_id`, `quarter` (`^\d{4}-Q[1-4]$`), `captured_at` (UTC), optional `fix`. Idempotent upsert by `client_uuid` into `app.gift_assignment` (unique programme, quarter, outlet): last writer wins by `captured_at` until the hand-over photo locks the row. A choice after the lock is REFUSED with reason `gift_locked` (shown on the phone, never silently dropped); an outlet outside the caller's reach is `out_of_scope`; a gift not offered to the outlet's tier is `gift_not_for_tier`. Who may choose: `cfg.astha.gift_choice_roles` (default SR and TSO, owner Q4). `putGiftChoices` (web/TSO panel) keeps its per-row `client_uuid`.

## 5. Redemption posting, expiry, adjustments, gift photos (Push C)

No new wire member: `redemption` / `redemption_line` / `gift_photo` already exist. The WORKER posts one negative `app.loyalty_ledger` row per redemption (`source_type` `redemption`, `source_id` = client_uuid; UNIQUE, so a retry is a no-op). Two phones over one balance keep the redemption and flag it for review (`cfg.loyalty.negative_balance_policy`, default accept_and_flag, owner Q2). Cash cashback is booked only as `redemption.cash_mtk`, never a memo discount (owner Q3). No earning rule runs (owner Q1). The nightly expiry job posts debit rows (`source_type` `expiry`, `source_id` `programme:outlet:date`). One photo per Astha outlet (the first by `captured_at` locks; a second is stored as a flagged duplicate, never deleted).

## 6. Migrations (numbers follow landing order)

- V0180: `app.astha_target.value_target_mtk bigint NOT NULL DEFAULT 0 CHECK (>= 0)`; `app.astha_target_batch` (append-only ledger of upload batches: `batch_uuid` PK, request fingerprint, counts, first result), grants as the V0158 ledgers.
- V0181 (push B3, done): unique index `astha_target_outlet_one` on `app.astha_target (month, outlet_id, coalesce(brand_id, 0)) WHERE outlet_id IS NOT NULL`; a route change moves the row (it fails loudly if duplicates already exist, nothing is deleted).
- V0182 (Push C): `app.gift_assignment.client_uuid uuid UNIQUE`, `captured_at timestamptz`; the `gift_choice` ingest-registry entry is code, not SQL.
- Dictionary comments and `dw` views follow in the same migrations.

## 7. What the Android lanes get when

Push B: the `programmes` section and the three reads (contract 1.15.0 members of section 2 and 3). Push C: `gift_choice`, redemption posting, gift photos. Until the lead's ruling the section stays `null` and nothing in the schema changes.


## 8. Push B outcome (2026-11-12, additive to 1.15.0, same ruling)

- **Gift picture:** `Gift.image_asset_id` (nullable Uuid) added; `getSkuImage` also serves active gifts' pictures. The bundle's `image_url` stays null (section 1).
- **`as_of` is display only** and content-derived (section 2 has THE rule; section 9 supersedes the earlier phone rule written here). The content digest must not contain a wall-clock time, or every build would be a new snapshot.
- **`expiring_points`, `expiry_date`:** `cfg.loyalty.expiry_days` (7) is the lifetime AFTER THE PERIOD END (D24-68: April points expire on 7 May) and sets `expires_on` when points are posted. `expiring_points` = the credits on the EARLIEST upcoming `expires_on` (that date is `expiry_date`), capped by the balance, never negative; no window; no FIFO matching yet.
- **Superseded memos** are excluded by status and also by `supersedes_client_uuid` (a live memo that names an original removes it even before the original's status flips).
- **Reads:** `getLoyaltyBalances` (live ledger up to and including today; named outlets outside the reach are left out; a national reader must name outlets), `listAsthaTargets` and `listGiftChoices` (a national reader must narrow by `route_id` or `zone_id`; a route or zone outside the reach is `403 ERR_OUT_OF_SCOPE`; paged by offset cursor). `400` declared on `getLoyaltyBalances` and `listGiftChoices`. Roles: SR, AMO, TSO, DMO, WM, NATIONAL, TOP, ADMIN, SUPERADMIN; the `cfg.flag.*_ui` tile flags do not gate these reads.
- **`putAsthaTargets`:** SUPERADMIN and the roles of `cfg.astha.target_entry_roles` (default ADMIN, TSO); a TSO only for outlets of routes in its reach (403 with pointer `body.rows[i].outlet_id`); month must lie in the quarter (`outside_quarter`); brand must be a brand node; keys unique per batch; the route is taken from the outlet; replay of a `batch_uuid` answers the first counts, the same uuid with other rows is `409 ERR_CONFLICT`. Web request: `docs/requests/web-astha-target-upload.md`.

## 9. Push B3 additions (Opus pass, 2026-11-13; same ruling, still 1.15.0)

- Achievement rule: section 2 (the one rule); `ProgrammesSection` states it once.
- Programme geography: a programme is in the bundle, `getLoyaltyBalances` and `listGiftChoices` only if its active version's scope includes the user's reach (one shared predicate, `ProgrammeScopeSql`, also used by the portal reads); a gift picture is served only for an active gift of an active programme in window whose scope includes the caller (`GET /v1/sku-images/{asset_id}`).
- Caps are never silent: bundle `truncated_sections` (programmes 50, loyalty 11,000, gifts 200) and `astha_truncated` (1,000 rows); `getLoyaltyBalances` has `truncated` (2,000); the programme filter is applied before the programme cap. New required members: `ProgrammesSection.astha_as_of`, `astha_truncated`, `truncated_sections`; `AsthaTargetRow.value_target_mtk`, `value_achieved_mtk`, `by_pack` (maxItems 1000); `LoyaltyBalance.league_label`, `expiry_date`; `LoyaltyBalanceList.truncated`. Timestamps are `Instant.wire()` (milliseconds, Z).
- `putAsthaTargets`: one row per (month, outlet, brand); every role is judged by its reach; a malformed or empty `cfg.astha.target_entry_roles` fails closed to SUPERADMIN only; role names match the contract enum spelling exactly; a replay is answered only after role and reach are judged.
