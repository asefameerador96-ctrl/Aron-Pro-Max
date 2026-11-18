# SR programme figures: the phone rule, EXACT BY IDENTITY (for lane/android-sr-programmes)

Status: BUILT on `lane/sr-targets-server` (D-TKPI-14, the lead's ruling after three review rounds). This is the ONLY phone rule; the earlier rule that inferred from acknowledgements, resolutions, quarantine states, ordering and single flight is deleted everywhere. The binding text is the `RouteSnapshot` description and `POST /v1/sync/figures` in `contract/openapi.yaml`; this file says the same in the phone lane's terms, and if the two ever differ the contract wins. NOTHING here reads a timestamp: `as_of` is informational and must never be compared with a clock.

## What the figure is
`achievement_mtd`, `kpi.*` (volumes, memo counts, `by_pack`), `star` STD and `campaigns[].std/memo/skus`: all accepted memos (status active, lines not voided) with a business date from the 1st of the bundle `month` up to and including `through_date` (bundle date minus one day), read in ONE repeatable-read snapshot from `app.memo` / `app.memo_line`. The bundle date's own memos are never in it. (A bundle built ahead for tomorrow has `through_date` = the day it was built on.)

Definitions, the same for the phone's local additions:
- quantities (`achieved_qty_base`, brand and category `volume_*`, `by_pack`, Star STD, campaign STD, campaign SKU `sold_qty_base`): SALE lines only;
- `achievement_mtd.memo_count`: a memo counts once per SKU when it has ANY non-voided line of that SKU (free lines included; a free-only memo gives `achieved_qty_base` 0 and `memo_count` 1);
- KPI brand `memo_achieved` and campaign `memo`: a memo counts once when it has a SALE line of the brand (campaign: of a shown campaign SKU);
- brand sums use ONE hierarchy, the current product tree, for target and figure;
- campaign figures are the OUTLET's: every route's memos at the outlet through `through_date`, plus this phone's own local memos. Another SR's sales at a shared outlet are in the figure; that SR's unsynced and today's memos are not.

## The exchange: `POST /v1/sync/figures`
Request: `{ for?: "YYYY-MM-DD", probe: [{ memo_client_uuid, memo_no?, business_date, removed_locally? }], include_astha? }`. SR phone token only. The probe lists the phone's OWN memos of the bundle month whose status in the figure it does not know: never confirmed IN, pending, sent and unanswered, held for review, refused, or voided or superseded on the phone (`removed_locally: true`). At most **300** entries (`400 ERR_VALIDATION`, field `body.probe`, code `too_many`), body at most 64 KiB (`413`). Read-only and idempotent: nothing is written, a retry is always safe. Call it after a sync cycle and when a figures screen opens while online, never on a timer.

Response: `{ business_date, through_date, routes: [{route_id, targets, achievement_mtd, calendar, kpi, star, campaigns, *_truncated}], figure_probe: { in_figure: [{memo_client_uuid, lines, stored_lines?}], voided: [uuid], twin_ambiguous?: [uuid] }, astha? }`. The route sections are the SAME as the bundle's `RouteSnapshot` members (same loaders), read in the SAME transaction as the answer. The bundle's version and ETag are not affected by this call.

EXACTLY three states per probed uuid:
1. **IN** (`in_figure`): stored, active, not voided, at least one non-voided line, dated from the 1st of the month up to `through_date`; `lines` = the stored non-voided line count; when the probe had `removed_locally`, `stored_lines` = the stored lines (sku, base quantity, kind) so the phone subtracts exactly what the figures counted.
2. **VOIDED** (`voided`): the server holds it and it is gone for good (voided, superseded, admin data void, every line voided).
3. **NOT IN**: every other uuid, listed nowhere: never uploaded, in flight and not committed in the snapshot, held for review, refused, or stored but dated after `through_date`. A uuid that is not the caller's own, or unknown, is NOT IN and nothing more (nothing is revealed). `twin_ambiguous` is an annotation on NOT IN (see the paper twin), not a fourth state.

## The phone rule
For each own memo m of the bundle month, live or removed on the phone:
1. dated after `through_date`: the figure never holds it; ADD it if it is live on the phone and not VOIDED (a VOIDED answer, e.g. an admin data void of today, drops it);
2. dated up to `through_date`, live on the phone: IN -> add nothing, remember "confirmed IN" and do not probe it again until the phone itself voids or edits it; VOIDED -> drop it, add nothing; NOT IN -> ADD it;
3. dated up to `through_date`, voided or superseded on the phone: IN -> SUBTRACT the `stored_lines` of the answer; otherwise nothing. An edit is therefore: the new memo is added (NOT IN) or already IN, the old memo is subtracted while it is still IN and dropped when it is VOIDED;
4. a memo confirmed IN and later voided by the office leaves the figure by itself; the phone adds nothing for it (it never probed it again and the office's truth is the figure);
5. a memo the server stored with fewer lines than the phone sent (`lines` smaller: a line refused for good, e.g. a zero price) is IN: the figure holds what the server stored and the phone adds nothing more (server-authoritative; the phone may show the difference);
6. more than 300 unconfirmed memos in the window: the phone cannot get an exact total in one call, it shows that it is still syncing until its uploads and confirmations bring it under the cap.

There are no acknowledgements, resolutions, quarantine states, clocks or single-flight requirements in this rule. A memo that commits after the snapshot is NOT IN in that snapshot and is added: exact for that bundle, IN for the next.

**Old-phone fallback (the only other case):** a phone with no `/figures` answer uses the bundle's figures, shows them with their as-of label, adds only memos dated after `through_date` and its never-sent pending memos. The phone uses the newest `/figures` answer it has, else the bundle's figures.

## Star and day counts
The phone merges Star STD by this same rule and recomputes projection, achievement, slab and status with the shared `StarProgress` code; last month's sold quantity (`lms`) is not merged. ADS, PADS and TADS divide by `calendar.elapsed_working_days_before_today` plus 1 when `calendar.today_is_working` (default until Q-A), RADS by `calendar.remaining_working_days_after_today`; a zero divisor gives no figure.

## The paper twin
When the uuid is unknown and an own paper-backfill memo (`entry_source = manual`) with the same `memo_no` and business date exists, the answer for the phone's uuid is the twin's, so the phone's own copy is IN when the paper memo is (not added on top) and VOIDED when the office voids the paper memo. LIMIT: the memo number must identify the memo. It is unique per business date over ALL memos (the `memo_no_unique` index), so one number is one memo and the answer is exact; a mistyped paper number is simply another memo until a reviewer matches them. If two own candidates ever matched, the answer is NOT IN with the uuid in `twin_ambiguous`, never a guess. The phone must send `memo_no` for every probed memo.

## Invariant and proof
Let S be the one snapshot, F(S) the figures, A(S) the answer for the probe set P; c(m) the contribution of memo m. CLAIM: the phone shows T(S) = the sum of c over every memo live in S (stored active memos in the window, including other users' at a shared outlet for the campaign figure) plus every phone-live memo S does not hold, each counted once. PROOF: F(S) is the sum of c over the stored active memos dated from the 1st up to `through_date`, computed in S. Every own memo is in exactly one class in S, decided by S itself: (a) IN: counted in F, nothing added, counted once; if the phone removed it, the subtraction is of the stored lines, exactly what F counted; (b) VOIDED: not in F, nothing added, counted zero times, the server truth; (c) NOT IN and dated up to `through_date`: not in F by the definition of the answer, the phone adds its local contribution once; (d) dated after `through_date`: not in F (the cut), the phone adds it once unless VOIDED. A memo committing after S is NOT IN in S and is added: exact for that bundle. A memo confirmed IN earlier and untouched stays in F unless the server removed it (void or supersede: nothing changes a stored memo's route, outlet or date), in which case F lacks it and the phone adds nothing. A and F are read in one repeatable-read transaction, so no interleaving of commits, rebuilds, reviewer releases or sweeps can make them disagree. The reviewed holes close by construction: a held or released void or edit (the old memo is IN until the server really voids it, then VOIDED: never subtracted twice; the edit is NOT IN until stored: never lost); a line refused for good (IN, `lines` smaller: nothing doubles on every bundle); a held memo of today released before the phone hears (today is cut: added; a held memo of an earlier day: IN once released); an admin data void of today (VOIDED: dropped); the paper twin (IN through the memo number); a lost response (the memo is IN or NOT IN as committed, no acknowledgement involved); a bundle taken while a batch is in flight (NOT IN or IN as committed at S).

Limits, stated: the probe cap (rule 6); the paper-twin identity is the memo number; other SRs' memos at a shared outlet are in the campaign figure by design; the Astha section is a separate live snapshot (below).

## Cost and size
Measured on the 840,000-memo scratch set and for 70 outlets x 20 SKUs in `docs/status/sr-targets-server.md` (section MEASUREMENTS). The probe is one lookup of at most 300 memos by `(client_uuid, business_date)` (dates given, so only the month's partitions), one stored-line read by the `memo_line_no` index, and, only for unknown uuids, one lookup by `(memo_no, business_date)`.

## Astha
`include_astha: true` returns the bundle's Astha section built in the SAME transaction as the figures by the programmes loader. Its identity predicate (a live snapshot through today: sale memos, no active edit) and the identity of redemptions are the loyalty lane's: `docs/requests/figures-astha-identity.md`.

## Tests (server twins of what the phone lane must prove)
`SrPhoneWouldShowTest` (identity phone model; totals compared) and `SrFiguresEndToEndTest` (the real ingest and the real endpoint: held memo released, held void and held edit released, zero-price line, admin data void, paper twin before and after upload, lost response, commit during the read). The phone lane's tests: every case above, plus a bundle on the 1st of the month, a prefetched bundle, two routes, more than 300 unconfirmed memos.
