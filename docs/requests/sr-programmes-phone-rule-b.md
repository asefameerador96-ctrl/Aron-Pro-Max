# Phone rule B for the SR programme sections (proposal of the lead's ruling 2026-11-18, for lane/android-sr-programmes)

Status: DESIGN, not built on the server yet. The server side (rule A) is a spike with measurements in `docs/status/sr-targets-server.md`; this file is what the phone lane must implement when A lands. It replaces the timestamp rule (b) of the `RouteSnapshot` description for `achievement_mtd`, `kpi`, `star`'s STD (not added on the phone), and `campaigns`.

## What the server will guarantee (rule A)
For every figure the phone merges (`achievement_mtd`, `kpi.*` volumes and memo counts and `by_pack`, `campaigns[].std/memo/skus`): it holds EVERY memo of the route (campaigns: of the outlet) that was COMMITTED before the bundle build read it, with business date from the 1st of the bundle `month` up to and including `through_date`, active, lines not voided, sale lines only, read in ONE repeatable-read snapshot straight from the memo tables, never from the lagging `dw.agg_*` aggregates. `as_of` is informational only (newest `received_at` among the memos read); never use it in arithmetic.

## The phone rule (ordering on the phone, no server clock)
Let `R` be the moment the phone SENT the request for this bundle (its own sync-cycle counter, not a clock). Let a memo be "acknowledged before R" when the phone had received `accepted` or `duplicate` for its record before `R`.
1. The server figure holds every memo acknowledged before `R` (the server commits a family before it returns the ack: `IngestService.runFamilies` completes every family transaction before `acksOf` and the response; a `duplicate` ack means an earlier committed upload).
2. The phone ADDS a local memo iff it was NOT acknowledged before `R` and its business date is inside the bundle `month` (every such memo dated after `through_date`, acknowledged or not, because the server figure is cut at `through_date`).
3. The phone SUBTRACTS a memo that WAS acknowledged before `R` (so the server figure holds it) when the phone has since voided or superseded it locally and that void or edit record was not acknowledged before `R`. An edit therefore is: add the new memo (not acknowledged), subtract the old one.
4. SERIALISATION, required: the phone must not have an upload batch in flight when it sends the bundle request (single flight: upload, then bundle). A batch in flight at `R` may or may not be committed when the server reads, and the rule cannot tell; this is the same requirement the Astha rule already has.
5. A bundle fetched before a later sync cycle is merged with the memos as they stood at its own `R`; after the next bundle the rule is applied again with the new `R`. Nothing is derived from any timestamp.

## Day counts when today's memos are in the achieved figure
Unchanged: ADS, PADS and TADS divide by `calendar.elapsed_working_days_before_today` plus 1 when `calendar.today_is_working` (default until Q-A), RADS by `remaining_working_days_after_today`; a zero divisor gives no figure.

## Tests the phone lane needs (what the phone would show)
Two offline days synced and a bundle pulled at every interleaving point (before the commit of batch 2, after the commit, after a rebuild); an edit with identical lines; a void followed by a re-sale; a bundle requested right after an upload ack. Each must show exactly the true total, never less, never more.
