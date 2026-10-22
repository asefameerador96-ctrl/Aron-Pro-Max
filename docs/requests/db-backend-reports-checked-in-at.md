# backend-reports request to the db lane: `checked_in_at` on `dw.agg_daily_route`

Needed by the national dashboard card Login / Submit Status (docs/ui-reference/web/dashboard-brief.md s3.4: "Successful Login = check-in and download").
Today `loginSubmit` counts a route as logged in when `logged_in_at` (first `day_open`, set by the bundle download) and `downloaded_at` are both set,
which are the same event. The attendance check-in (`app.attendance_event`, `kind = 'check_in'`) is not in the summary, so a rep who downloaded but never
checked in is counted as logged in (Opus checker finding, open since session 3).

Ask (additive, nullable, no backfill needed for new days; a backfill from `app.attendance_event` is welcome for the last 7 days):

```sql
ALTER TABLE dw.agg_daily_route ADD COLUMN checked_in_at timestamptz;
COMMENT ON COLUMN dw.agg_daily_route.checked_in_at IS 'UTC time of the first non-voided attendance check-in of the route''s SR on the business date.';
```

Rule I will write in `Aggregator`/`DashboardSummaries` (same route-day rebuild, idempotent): the earliest `app.attendance_event.captured_at` with `kind = 'check_in'`,
`voided_at IS NULL`, `business_date = :d`, and `route_id = :r` (or, when the event carries no route, `user_id` = the route's SR user of that day).
Then `loginSubmit` counts `logged_in_at IS NOT NULL AND downloaded_at IS NOT NULL AND checked_in_at IS NOT NULL`. Until the column exists the code keeps today's
behaviour (column probed once, like `ready()`), so nothing breaks on either side of the migration.

Question for db: is `app.attendance_event.route_id` filled by the sync path for SR check-ins? If it is often null, say so and I use the user fallback.
