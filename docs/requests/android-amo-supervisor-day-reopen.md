# Request: android-core / core-sync — deliver a server void of a day_submit to the phone

From: android-amo (2026-10-17). Needed by: F-AMO-030 (AMO Sales Submit), also the SR Sales Submit.

`CaptureRepository.reopenRouteDay(date, routeId, voidedCycle)` stores the reopen mark that `RouteDayLock` reads, but nothing in main code calls it: a server void of a cycle never reaches the phone, so the day stays locked and cycle 2 cannot be submitted. For an AMO the supervisor day has `routeId = null` (`reopenRouteDay(date, null, cycle)`).

Needed: where the sync response (or the bundle delta) carries the voided cycle, call `reopenRouteDay` with the submit's `route_id` (null for `scope = supervisor_day`). AMO side is ready: the Submit gate and new calls read `CaptureRepository.submitStands(date)`, which honours the mark.

Also: `cfg.sync.reconcile_types` is seeded with SR rows only. The AMO app uses its default nine rows (outlet, sale, stock, qc, promotion, distribution, price_compliance, joint_call, survey) until an `AMO.<row>` set exists in the registry seed (db lane).
