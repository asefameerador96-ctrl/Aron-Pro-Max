# Contract 1.15.0: loyalty and Astha, the FIELD side (lane portal-loyalty-api, train 3, 2026-11-12)

For the lead's approval as a ruling id (the lead appends it to DECISIONS.md; this file then cites it). **Status: proposal, nothing in
`contract/openapi.yaml` has changed yet.** 1.15.0 is additive on 1.14.0 (lane sr-server, V0173 to V0175). Migrations of this train start at
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
| `loyalty` | `LoyaltyBalance` per (outlet, programme) of the user's outlets, enrolled outlets only. | `balance_points`, `expiring_points`: integer points. `as_of_date` = the PREVIOUS Dhaka business date: the phone shows "as of <date>" and never adds its own redemptions to it except the open basket it holds locally. `expiring_points` = points whose expiry date falls within the next `cfg.loyalty.expiry_warning_days` (default 7); `expiry_date` = the earliest such date, else null. |
| `gifts` | Active gifts of the sent programmes. | `points_cost` integer points (null for Astha gifts). `tier_codes` filters Astha gifts by the outlet's tier. `image_url` is resolved when the bundle is built (a short-lived read URL), never stored. |
| `astha_targets` | `AsthaTargetRow` for the months of the active Astha quarter, one row per (route, outlet, brand, month); the route row has `outlet_id` null; the memo-target row has `brand_id` null ("All Brand"). | See section 2. |
| `gift_assignments` | `GiftAssignment` for the quarter, outlets of the user's routes. | `locked` is true once the hand-over photo exists. `chosen_at` is UTC. |
| `pending_gift_verifications` | Redeemed campaign gifts still short of photos (Push C fills it). | Counts are integers. |

Flags: a tile is hidden when its flag is off. The server omits the matching rows when `cfg.flag.astha_ui` / `cfg.flag.loyalty_ui` /
`cfg.flag.superstar_ui` is off, so a hidden programme sends no data.

Shop-wise list (SRM-B-65): the tier label of an outlet is `programme_enrolment.tier_code` (its `ProgrammeTier.label_*` is in `programmes[].tiers`),
falling back to the outlet's sub-channel name; `league_label` carries the Diamond League label.

## 2. `AsthaTargetRow` gains three members (additive; SRM-B-63, 64, 146)

- `value_target_mtk`: string, integer milli-taka, `>= 0`, `"0"` until the target is uploaded (owner default Q5). A string because milli-taka exceed 2^53 in sums; the phone parses with a 64-bit integer.
- `value_achieved_mtk`: string, integer milli-taka. Net memo value of the (outlet, brand, month) up to `as_of`, summed from memos on the server: memos that were superseded or voided are excluded (follow `supersedes_uuid`), the memo offer line is zero so net equals gross. Brand `null` = the whole outlet.
- `as_of`: `Timestamp` (UTC). The achievement counts every memo the server had received before `as_of`. The phone adds ONLY its own unsynced memos created after `as_of` (the local outbox), keyed by `memo_uuid`, recomputed from scratch after every bundle (adding is idempotent: a memo that already synced is in the server figure and not in the outbox).
- Percent: the phone shows `achieved / target` as a percentage with 2 decimals, rounded half-up, and `0.00%` when the target is 0 (the -37,500% bug: targets are `>= 0` and every percentage guards the divide by zero).
- Units on the phone (rule 5): `std_target` / `std_achieved` (Decimal3) stay in base units (sticks) on the server; the phone shows packets for cigarettes and dozens for matches, one line PER PACK SIZE for a brand with 20s and 10s, never summed (SRM-B-146; rule 5 wins over the owner's screenshot).

**For the one-line-per-pack-size rule a new optional member is needed** (the existing row grain is brand and month, base units, so it cannot be split by pack size on the phone): `by_pack`: array (max 6) of `{ base_per_pack: integer 1..1000, std_target: Decimal3, std_achieved: Decimal3 }`, base units, one entry per pack size of the brand that has a target or a sale in the month. `std_target` / `std_achieved` of the row stay the totals. **This needs the lead's ruling** (it also needs a target upload grain by pack size; default if no ruling: `by_pack` carries achievement only, `std_target` is shown on the first line with its pack size, owner question for the lead).

## 3. Phone reads and the portal upload

- `getLoyaltyBalances`, `listAsthaTargets`, `listGiftChoices` become real read-only online refreshes (the bundle stays the offline source). They reuse the section builders of the bundle for the same user and date and are cut to the caller's reach by the token. A failure on the phone keeps the bundle figure with its age.
- `putAsthaTargets`: `AsthaTargetWrite` rows gain optional `value_target_mtk` (string, integer milli-taka, `>= 0`, default `"0"`). Idempotent by `batch_uuid`: a replay answers the first result; the same `batch_uuid` with different rows is `409`; all-or-nothing; a negative or fractional value is `400 ERR_VALIDATION` with the indexed pointer (`body.rows[2].value_target_mtk`). Roles from `cfg.astha.target_entry_roles`. The web page request for the web lane is written with the handler in Push B.

## 4. New sync record `gift_choice` (SRM-B-66; Push C)

Fields: `client_uuid` (UUID v4, created when the SR taps the gift), `outlet_id`, `gift_id`, `quarter` (`^\d{4}-Q[1-4]$`), `captured_at` (UTC), optional `fix`. Idempotent upsert by `client_uuid` into `app.gift_assignment` (unique programme, quarter, outlet): last writer wins by `captured_at` until the hand-over photo locks the row. A choice after the lock is REFUSED with reason `gift_locked` (shown on the phone, never silently dropped); an outlet outside the caller's reach is `out_of_scope`; a gift not offered to the outlet's tier is `gift_not_for_tier`. Who may choose: `cfg.astha.gift_choice_roles` (default SR and TSO, owner Q4). `putGiftChoices` (web/TSO panel) keeps its per-row `client_uuid`.

## 5. Redemption posting, expiry, adjustments, gift photos (Push C)

No new wire member: `redemption` / `redemption_line` / `gift_photo` already exist. The WORKER posts one negative `app.loyalty_ledger` row per redemption (`source_type` `redemption`, `source_id` = client_uuid; UNIQUE, so a retry is a no-op). Two phones over one balance keep the redemption and flag it for review (`cfg.loyalty.negative_balance_policy`, default accept_and_flag, owner Q2). Cash cashback is booked only as `redemption.cash_mtk`, never a memo discount (owner Q3). No earning rule runs (owner Q1). The nightly expiry job posts debit rows (`source_type` `expiry`, `source_id` `programme:outlet:date`). One photo per Astha outlet (the first by `captured_at` locks; a second is stored as a flagged duplicate, never deleted).

## 6. Migrations (numbers follow landing order)

- V0180: `app.astha_target.value_target_mtk bigint NOT NULL DEFAULT 0 CHECK (>= 0)`; `app.astha_target_batch` (append-only ledger of upload batches: `batch_uuid` PK, request fingerprint, counts, first result), grants as the V0158 ledgers.
- V0181 (Push C): `app.gift_assignment.client_uuid uuid UNIQUE`, `captured_at timestamptz`; the `gift_choice` ingest-registry entry is code, not SQL.
- Dictionary comments and `dw` views follow in the same migrations.

## 7. What the Android lanes get when

Push B: the `programmes` section and the three reads (contract 1.15.0 members of section 2 and 3). Push C: `gift_choice`, redemption posting, gift photos. Until the lead's ruling the section stays `null` and nothing in the schema changes.
