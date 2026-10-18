> **lead-backend, 2026-10-17 (lane/lead-backend):** done, enforced when a location request is **approved** (the outlet
> never moves on a request that breaks a limit), never at ingest: the stored request stays idempotent and the approver
> sees why. 409 `ERR_CONFLICT` with `context.guard` = `mocked_fix`, `update_base_distance` (proposed point farther than
> `cfg.geo.update_base_max_distance_m`, 100, from the request's own fix), `update_base_move` (farther than
> `cfg.geo.update_base_max_move_m`, 1000, from the stored point) or `update_base_monthly_cap` (the outlet's location was
> already changed `cfg.geo.update_base_max_per_outlet_month`, 1, times in this Dhaka month). They apply to every location
> request (the move is the fraud vector whoever raises it); a web request has no fix, so only the move and the cap apply.
> **Open for db + lead:** the three keys are not in `app.cfg_key` nor in docs/24 s9.5 yet (docs/19 lists them); the server
> reads the documented defaults until they are registered. A migration was not added here because V0084 to V0087 are held
> on lane/lead-hardening; registering them is a 3-row migration after those land, plus the s9.5 rows. No new
> `OutletRequest.flags` value was added (that enum is closed; a new value would be a breaking response change).
> The 1000 m move cap is a hard cap (D-111) for every role; an outlet imported with a wrong stored point is corrected by
> an admin outlet edit (`PATCH /v1/admin/outlets/{id}`), not by a location request.

# Request: server guards for Update Base (from android-amo, 2026-10-18)

To: backend / masterdata owner. The AMO app (F-AMO-028) sends a `location` outlet request whose proposed lat/lng is the AMO's chosen point, within `cfg.geo.update_base_max_distance_m` (100) of the request's own fix. Today `OutletRequests.kt` only validates range and flags `mocked_fix`. Please enforce on create and on approval:
1. distance(request_fix, proposed lat/lng) <= `cfg.geo.update_base_max_distance_m`, else reject (or flag for the approver);
2. a mocked request fix is refused for `location` requests;
3. distance(outlet stored point, proposed) <= `cfg.geo.update_base_max_move_m` (default 1000), above it the TSO approves (existing check) or reject;
4. `cfg.geo.update_base_max_per_outlet_month` per outlet and month.
Also note in docs/24 s12.2: `location` takes lat/lng from the request's own fix or within `update_base_max_distance_m` of it.
