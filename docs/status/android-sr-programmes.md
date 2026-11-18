# Lane android-sr-programmes (A-PROG1) status

## HANDOVER (top; keep current; updated 2026-11-18 ~07:55Z; this session is near its context limit: a successor continues from here)
- Branch `lane/android-sr-programmes` (INT `29e8d86f` merged, train 118). Lead session `session_01MbUQSxrP7AB9tbyANjUTPS` (send_message; start with "checked", give head and ci run). Repo public: no secrets, no personal data. Never push while a CI run of this branch is in progress (a push cancels it; the brief forbids cancelling before 45 minutes); before every push run `:android:core-ui:testDebugUnitTest --tests '*HardcodedStringScanTest'`, the touched modules' tests and lint.
- State: slices 1 to 5 built and pushed; check-in gate in; lows 5 and 6 pushed at `ebcc0ee8` (CI run 44260320759, in progress at 07:50Z). Slices 6 and 7 (photos) NOT started. Room 12 not usable yet (only after the integrity lane 11 lands; no bump).
- ROUND 2 FIXES (A-PROG2, 2026-11-18): B1 done (`bundle.requested_at` in sync_meta, written by `ReferenceRepository.apply(requestedAt)` from `BundleDownloader.fetch` before the request leaves; no schema change; `AsthaLocal` counts a memo if still local or acked after that instant, falls back to bundle server time after a promoted prefetch, never uses `astha_as_of`); B2 done; B3 done (category from `products.skus[].category_code`, mixed or unknown category prints neutral base units and a log line); B4 done (match = dozens, base unit, fractions of a dozen; lighter boxes of `base_per_pack`; cigarette/bidi packets). Lows done: zero-sale memo adds no memo count; memo only to rows of its own route and enrolled outlets; by_pack grouped by the SKU's `base_per_pack`; gift tier from the English sub-channel label (language independent); "tier not downloaded" text; acked choice reads "sent", a newer bundle wins; refusal shows the bundle reason text; gift quarter from the trusted clock; re-reads after SAVED are caught. NOT done: a memo voided on this phone is not subtracted (the phone has no memo-void write; a server void arrives with the next bundle). Still waiting: CI run of the next push.
- THEN (next): slices 6 and 7 and the quarter chips as below.
- Waiting on the lead: Room 12 go; the Loyalty lane's answer on `balance_before_points`; the cash-back gift (card visible, cannot be confirmed).
- Local toolchain: Android SDK `/opt/android-sdk` (from dl.google.com), `local.properties` git-ignored; goldens `-Proborazzi.record=true`.

## Server answers received (lead, 2026-11-18)
- N-4: earning is NOT posted (no earning rule yet, owner question open): `balance_points` is 0 or negative; the points and redemption screens show the real balance, 0 included, no fake points; the confirm step says honestly when the balance does not cover a gift and follows the server's policy only (no phone-side rule). Status of those screens: **"built, server path not verified" and "needs owner's earning rule"**. Nightly expiry runs; ingest of `redemption`, `redemption_line`, `gift_choice`, `gift_photo` is real on dev (gift_photo parent is `redemption_client_uuid`). A refused record arrives in the sync acks (`RecordAck`: gift_locked and gift_not_for_tier final; parent_missing retryable; texts in the bundle `reason_texts`); the existing refused-records screen and Send-again rules are reused, no second mechanism.
- N-6: confirmed brand_id is the brand-level product node id; only active nodes are sent, so a missing node shows `#id` (guarded).
- N-7: tier is NOT on the wire except via the outlet's `sub_channel_id` (mapped through the `sub_channel` code list) and `gift_assignments[].tier_code`; the Loyalty lane adds `loyalty[].tier_code` with N-1. Shop-wise info shows a dash where the tier is unknown: "tier pending server".
- N-1: not built yet; months without downloaded rows show "not downloaded", never zero (done in slice 2 follow-up).
- Lesson: run `:android:core-ui:testDebugUnitTest --tests '*HardcodedStringScanTest'` before every push (visible text must live in resources); the first CI run failed on it.

## Coming from the Loyalty lane (lead ruling, 2026-11-18; not landed, build behind a null check)
- Bundle `astha_targets` will carry the whole current quarter within one bound of 1,000 rows; `astha_truncated_months` lists months that did not fit; `loyalty[].tier_code`; an Astha row of an inactive brand carries `brand_name` and `brand_name_bn`.
- Months in `astha_truncated_months` are read online with `listAsthaTargets` when connected and then KEPT on the phone; a month never fetched shows "not downloaded, connect once" (Bangla and English), never zero. Storage choice: raw `bundle_section` cannot hold fetched months without a schema change, so until the Room 12 step an **app-private file cache keyed by route and month** is used (decision when built).
- A redemption at balance 0 is accepted by the server and the balance goes negative (`overdrawn` true): the screen shows the balance and the overdrawn state plainly and does not block on the phone.

## Screen table
Legend: WIRE = data is in the bundle today. LOCAL = a write that goes through the outbox. SCHEMA = needs a new Room table (not before the lead gives a version).

| # | Manual | Screen | Data source (contract name) | Offline | Sync record | Build order | State |
|---|---|---|---|---|---|---|---|
| 1 | 48 | Astha menu (two tiles: Target, Gift selection) | none (navigation); tile visibility from `cfg.flag.astha_ui` and `programmes` non-null | yes | none | 1 | BUILT (slice 1); Gift tile hidden until slice 5 |
| 2 | 49 | Choose year and quarter | `AsthaTargetRow.month` (current month rows only); lists 2026-2030 and Q-1..Q-4 as in the manual | yes for the current month; other quarters show "no data" offline | none | 1 | BUILT (slice 1), partial (N-1) |
| 3 | 50 | Route STD target and memo target (tab "route info", month chips, brand table volume and value, All Brand memo row) | `programmes.astha_targets` rows with `outlet_id` null: `brand_id`, `std_target`, `std_achieved`, `value_target_mtk`, `value_achieved_mtk`, `memo_target`, `memo_achieved`, `by_pack[]`, `astha_as_of`; brand names from `products` section (to verify, N-6) | yes | none | 1 | BUILT (slice 1); brand names come from `products.nodes` (level brand), N-6 resolved; partial (N-1) |
| 4 | 51 | Shop-wise info (Astha outlets, tier, search; tap opens the outlet's brand table) | rows with `outlet_id` set; outlet name, owner, phone, wing-to-zone from the stored outlets; tier from `Gift.tier_codes` match and outlet sub-channel (`sub_channel` 1.18.0, Gold, Diamond, Platinum, Titanium) | yes | none | 2 | BUILT (slice 2) without the four geography rows (N-9); tier label from the `sub_channel` code list by the outlet's `sub_channel_id` |
| 5 | 55 | Outlet points (campaign card, points, expiring points, expiry date, Redemption button) | `programmes.loyalty[]`: `balance_points`, `expiring_points`, `expiry_date`, `league_label`, `overdrawn`, `as_of_date`; `programmes[]` for the name | yes (as of previous day plus local redemptions) | none | 3 | BUILT (slice 3), **server path not verified; needs owner's earning rule** (balances are 0 until earning is posted); Points page is reached from a second tile of the Loyalty menu until the lead decides on the Sales-screen entry |
| 6 | 53 | Gift redemption (outlet picker, letter chips, remaining points banner, gift cards with steppers) | `gifts[]` (`points_cost`, `name_bn`, `image_asset_id`), `loyalty[]`; remaining = balance minus this phone's unsynced redemptions | yes | `redemption` + `redemption_line` (RedemptionPayload, client UUID) | 4 | BUILT (slice 4), **server path not verified; needs owner's earning rule**; unsynced redemptions read from `amo_record` + outbox state (no schema change) |
| 7 | 54 | Confirm and success dialogs | same as 6 | yes ("saved on the phone", not "posted") | same | 4 | BUILT (slice 4), same status |
| 8 | 52 | Astha gift selection (outlet picker, letter chips, outlet details, gift cards, Submit) | `gifts[]` filtered by `tier_codes`, `gift_assignments[]` (`locked`, `gift_id`), outlets | yes | `gift_choice` (GiftChoicePayload: outlet_id, gift_id, quarter, fix); refusals `gift_locked`, `gift_not_for_tier` shown on the phone | 5 | BUILT (slice 5), server path not verified; the local choice is read from `amo_record` + outbox state; a refused one is shown as refused and can be chosen again |
| 9 | 56 | Photo capture menu (Astha, Campaign) | none | yes | none | 6 | READY |
| 10 | 57 | Astha gift photo (gift chosen, capture, retake, Submit, "already captured") | `gift_assignments[]` (`photo_captured`, `locked`), `gifts[]` | yes | `gift_photo` (GiftPhotoPayload, programme_kind astha, `photo_uuid`) plus media upload, compressed before queueing, Wi-Fi preferred | 6 | READY; local "already captured" needs a local read |
| 11 | 58 | Campaign gift photo (outlet, campaign, one slot per unit) | `pending_gift_verifications[]` plus this phone's unsynced redemptions; `unit_no` 1..50 | yes | `gift_photo` (programme_kind campaign, `redemption_client_uuid`, `unit_no`) | 7 | READY |
| 12 | 10 | Target and achievement by SKU (STD tab; Target, Achieved, Remaining, %, ADS, TADS, PADS, RADS) | **NOT ON THE WIRE**: the SR's per-SKU monthly target and the working-day calendar for ADS formulas are not in the bundle (RouteSnapshot `targets` and `achievement_mtd` stay empty) | blocked | none | blocked | BLOCKED, request N-2 |
| 13 | 21 | Outlet target (today's target pop-up, outlet target table by SKU) | **NOT ON THE WIRE**: per-outlet per-SKU target and sales for the campaign (e.g. Super Star Low) | blocked | none | blocked | BLOCKED, request N-3 |
| 14 | 61 | Brand-wise KPI (volume and memo percent per brand) | **NOT ON THE WIRE**: brand targets and KPI snapshot per SR and month (the parity lane's KPI dashboard has the same gap) | blocked | none | blocked | BLOCKED, request N-5 |
| 15 | 62 | Match Box Lifting Star (target, STD, projection, LMS, LMADS, PADS, growth, slab, status) | **NOT ON THE WIRE**: server-computed snapshot per SR and month (rules in `Programme.rules` kind superstar are in the bundle, the figures are not) | blocked | none | blocked | BLOCKED, request N-8 |

Sales Journey (59) and KPI dashboard (60) belong to lane android-parity-sr-1 and are left alone.

## Build order (slices, one push each)
1. Menu, year/quarter, route target tabs (rows 1 to 3), replacing COMING_SOON for Astha. Model and reader in a new feature module or file set; goldens.
2. Shop-wise info (row 4).
3. Loyalty menu and outlet points (row 5).
4. Gift redemption and confirmation (rows 6, 7) with outbox records, duplicate and out-of-order tests.
5. Astha gift selection (row 8) with the `gift_choice` record.
6. Photo menu and Astha gift photo (rows 9, 10).
7. Campaign gift photo (row 11).
Rows 12 to 15 start when the server needs (N-2, N-3, N-5, N-8) are answered.

## What needs new Room tables (list only; nothing is created yet)
- Local pending state for rows 6 to 8, 10, 11: if the outbox cannot be queried by record type and payload field for "unsynced redemptions of outlet X" and "gift photo already captured for assignment Y", a small table per concern is needed. Slice 4 decides this; if a table is needed, I ask the lead for a version number first.
- Gift picture cache (bytes by `image_asset_id`): reuse the existing packshot/media cache if it accepts gift ids; otherwise a table.

## Decisions
Appended to DECISIONS.md as D-SRPROG-NN (see there).
