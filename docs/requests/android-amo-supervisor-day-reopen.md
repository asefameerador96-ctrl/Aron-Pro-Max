> **lead-backend, 2026-10-17:** nothing for the backend to build; the premise does not hold today. The server never
> voids a Sales Submit: `POST /v1/day/submit-void` is in the contract, but the whole submit-void family is **DROP** in
> the backlog (F-API-069, F-TSO-030, F-ADM-074, F-SR-080: "not in any manual or screenshot"; D-64: a sale after Sales
> Submit is accepted and flagged). No code sets `route_day.submit_voided` or `supervisor_day.submit_voided`, so no
> response ever carries a voided cycle and `reopenRouteDay` has nothing to react to. If the lead reinstates submit void,
> it is a T1 day-state row (route-day and supervisor-day scope, ingest guard against an old-cycle submit re-locking the
> day) and the phone half is android-core's (read `day_states[].submit_voided`/`submit_cycle`). The `AMO.<row>`
> reconcile set is a db-lane seed (`cfg.sync.reconcile_types`).

# Request: android-core / core-sync — deliver a server void of a day_submit to the phone

From: android-amo (2026-10-17). Needed by: F-AMO-030 (AMO Sales Submit), also the SR Sales Submit.

`CaptureRepository.reopenRouteDay(date, routeId, voidedCycle)` stores the reopen mark that `RouteDayLock` reads, but nothing in main code calls it: a server void of a cycle never reaches the phone, so the day stays locked and cycle 2 cannot be submitted. For an AMO the supervisor day has `routeId = null` (`reopenRouteDay(date, null, cycle)`).

Needed: where the sync response (or the bundle delta) carries the voided cycle, call `reopenRouteDay` with the submit's `route_id` (null for `scope = supervisor_day`). AMO side is ready: the Submit gate and new calls read `CaptureRepository.submitStands(date)`, which honours the mark.

Also: `cfg.sync.reconcile_types` is seeded with SR rows only. The AMO app uses its default nine rows (outlet, sale, stock, qc, promotion, distribution, price_compliance, joint_call, survey) until an `AMO.<row>` set exists in the registry seed (db lane).
