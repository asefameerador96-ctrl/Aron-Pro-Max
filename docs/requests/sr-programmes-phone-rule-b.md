# Phone rule B for the SR programme sections (for lane/android-sr-programmes)

Status: the server side (rule A) is BUILT on `lane/sr-targets-server` (D-TKPI-12): own-route figures read straight from the memo tables in one repeatable-read snapshot; tests of what the phone would show in `SrPhoneWouldShowTest` (a model of this rule) and `SrPhoneWouldShowIngestTest` (the same through the real ingest, quarantine release, accept-with-fix and the waiting sweep). The binding text is the `RouteSnapshot` description in `contract/openapi.yaml`; this file says the same in the phone lane's terms, and if the two ever differ the contract wins. NOTHING here reads a timestamp: `as_of` is informational and a phone must never compare it with a clock.

## What the server figure is
For `achievement_mtd`, `kpi.*` (volumes, memo counts, `by_pack`), `star` STD and `campaigns[].std/memo/skus`: all accepted memos (status active, lines not voided) with a business date from the 1st of the bundle `month` up to and including `through_date` (bundle date minus one day), read in ONE repeatable-read snapshot from `app.memo` / `app.memo_line`. It holds every memo the server had COMMITTED when it read, whether the phone has heard of it or not. The bundle date's own memos are never in it. (A bundle built ahead for tomorrow has `through_date` = the day it was built on, so that day's committed memos ARE in it; the rule still gives the true total.)

Definitions, the same for the phone's local additions:
- quantities (`achieved_qty_base`, brand and category `volume_*`, `by_pack`, Star STD, campaign STD, campaign SKU `sold_qty_base`): SALE lines only;
- `achievement_mtd.memo_count`: a memo counts once per SKU when it has ANY non-voided line of that SKU (free lines included; a free-only memo gives `achieved_qty_base` 0 and `memo_count` 1);
- KPI brand `memo_achieved` and campaign `memo`: a memo counts once when it has a SALE line of the brand (campaign: of a shown campaign SKU);
- brand sums use ONE hierarchy, the current product tree, for target and figure;
- campaign figures are the OUTLET's: every route's memos at the outlet committed before the read, through `through_date`, plus this phone's own local memos. Another SR's sales at a shared outlet are in the figure; that SR's unsynced and today's memos are not.

## The phone rule (ordering only)
Let `R` be the moment the phone SENT the request for this bundle (its own sync-cycle counter).
1. A memo is ACKNOWLEDGED when the phone holds `accepted` or `duplicate` for the memo AND for all its lines, or a resolution `accepted` / `accepted_with_fix` for it (from a batch response or a delta), received before `R`. The server commits a family before it returns its ack (`IngestService.runFamilies`), so the server figure holds every memo acknowledged before `R`.
2. A memo whose last answer is `quarantined` (held for review, or waiting for a parent record) is NEVER added: show it as held for review (the awaiting line); it is not a counted sale yet and the server figure lacks it. The server may commit it before the phone hears (a reviewer's accept or accept_with_fix, or the sweep of waiting children): then the figure holds it and the phone, which still does not add it, is exact. It becomes acknowledged only when an acknowledgement or resolution reaches the phone before a bundle request. A resolution `rejected` (or any final refusal) removes it; never add it.
3. ADD a live local memo dated inside the bundle `month` iff it is NOT acknowledged before `R` and not held or refused; ADD every such local memo dated after `through_date`, acknowledged or not (the figure is cut there).
4. SUBTRACT a memo acknowledged before `R` and dated up to `through_date` when the phone has since voided or superseded it and that void or edit record was not acknowledged before `R`. An edit is: add the new memo, subtract the old one.
5. SINGLE FLIGHT: send NO bundle request while an upload batch is in flight. A batch whose response never arrived (lost response, timeout, a 5xx after the server committed) is still in flight: replay it until it is answered (the answer is `duplicate`), then ask.
6. A bundle is merged with the memos as they stood at its own `R`; the next bundle applies the rule again with its new `R`.
7. An admin data void with no phone event takes memos out of the figure: until the phone learns of the void its total can be below its local memos. Correct for the business; nothing to do.

## Star
The phone merges the Star STD by rules 1-4 like every other figure and recomputes projection, achievement, slab and status with the shared `StarProgress` code. Last month's sold quantity (`lms`) is not merged (a last-day memo of last month not yet acknowledged is missing from it).

## Day counts when today's memos are in the achieved figure
ADS, PADS and TADS divide by `calendar.elapsed_working_days_before_today` plus 1 when `calendar.today_is_working` (default until Q-A), RADS by `calendar.remaining_working_days_after_today`; a zero divisor gives no figure.

## Tests the phone lane needs (what the phone would show)
Two offline days synced and a bundle pulled at every interleaving; an edit with identical lines; a void then a re-sale; a bundle right after an upload ack; a memo quarantined then released (before and after the phone hears); accept_with_fix; the waiting sweep; a free-only memo; a bundle on the 1st; a prefetched bundle; two routes. Each must show exactly the true total, never less, never more. The server-side twins are `SrPhoneWouldShowTest` and `SrPhoneWouldShowIngestTest`.
