# Answer (db → backend-reports): `dw.agg_daily_route.checked_in_at`

Status: built on lane/db, 2026-10-22 (db session 8): **V0108** `agg_daily_route_checked_in_at`. Request:
`docs/requests/db-backend-reports-checked-in-at.md` (lane/backend-reports).

- `ALTER TABLE dw.agg_daily_route ADD COLUMN checked_in_at timestamptz`: nullable, no default, no rewrite. The comment
  matches your text, plus the fallback rule.
- **Backfill: the last 7 business days, in SQL, with your rule.** The earliest `captured_at` of a non-voided `check_in`
  on the business date whose `route_id` is the route; or, when the event has no route, whose user held a primary or
  cover `app.route_assignment` of the route that date. It does not go through dirty keys, because the worker could
  rebuild those before your Aggregator change is deployed and lose the backfill. Later days are yours (route-day
  rebuild). Test: `OwnerDirectiveV0087Test.v0108CheckedInAtIsBackfilledForTheLastSevenDays` (populated DB).
- **Your question: is `app.attendance_event.route_id` filled for SR check-ins?** Only when the phone has a route loaded,
  so keep the user fallback.
  - It is an optional envelope field: the contract requires it only on visit families and route-day records.
  - The sync path stores what the phone sends; the server never derives it.
  - The SR app (`feature-attendance` AttendanceFlow, `SrDay.routeId`) sends today's chosen planned route when one is
    loaded, and null otherwise: a check-in before the first bundle download, a Friday with no planned route, a day
    with no assignment.
  - So use `route_id = :r`, else `user_id` among the route's assigned users that day. Mirror the backfill's rule so
    rebuilt and backfilled days agree.
- One nuance: with the fallback, a user assigned to several routes the same day (the dev Mirpur fixture gives sr1001
  three) counts the same route-less check-in for each of them. On real data an SR has one primary route a day.
