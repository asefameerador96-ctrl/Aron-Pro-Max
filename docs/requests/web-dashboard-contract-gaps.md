# Request from web-dashboard: contract gaps found while building the dashboard rows

The web builds against `contract/openapi.yaml` v1.1 and never invents a field. Where a row's acceptance test names a figure the contract
does not return, the web shows what the contract has, the mock mirrors it, and these requests list the smallest additions. Each page
degrades gracefully (the missing figure shows "not available") until the field exists.

1. **F-WEB-038 same-time-yesterday comparator.** `DailyTrackingPage` has no comparator. Needed: `comparator: { business_date, as_of_time, buckets: { ge_100, from_90, from_80, below_80, exception, not_logged_in } }`
   (counts of the previous business day at the same Dhaka clock time as `as_of`). Until then the page calls `GET /v1/dashboards/daily-tracking` for yesterday and labels it "yesterday (end of day)", which is NOT same-time and says so on screen.
2. **F-WEB-045 sync-health ops figures.** `SyncHealthPage.summary` lacks `config_ack_pct` (devices acked / devices owing an ack for a `requires_ack` key), `pending_photos` (count and oldest age), `quarantine_backlog` and a per-zone breakdown with route and device drill. Needed: `summary.config_ack_pct`, `summary.pending_photos`, `summary.quarantine_backlog`, `by_zone: [{zone_id, login_pct, submit_pct, final_submitted, trickle_p95_s, quarantined, pending_photos, config_ack_pct}]`. Until then the page shows login %, submit %, final submit and per-device rows from existing fields, and "not available" for config ack and photos.
3. **F-WEB-010 Browse Routes: AMO and SR names.** `Route` has no assigned user names. The web joins `GET /v1/admin/route-assignments` and the user list; a single read model `GET /v1/admin/routes?include=assignees` would avoid N calls.
4. **F-WEB-036 leaderboard target view.** Target achievement is a deferred programme (docs/27); the page builds the `mtd` and `volume` views and omits `target` until docs/27 changes.
5. **F-WEB-047 per-zone final-submit badge.** The Final Submit picker needs one row per zone with `final_submitted: boolean`. The page derives it from `GET /v1/dashboards/summary` children at zone level (`day_completion_pct = 100`); an explicit `zones: [{zone_id, final_submitted}]` on `LoginSubmitStatus` would be exact.
6. **F-WEB-033 password policy text.** The page shows the policy from `ChangePasswordRequest.new_password` (web roles: 12+, upper, lower, digit) and relies on `ERR_AUTH_PASSWORD_POLICY` for history and 24 h rules. A `GET /v1/config/public` entry `auth.password_min_len` would let the guideline follow the setting.
