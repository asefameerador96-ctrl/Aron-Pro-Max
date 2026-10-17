# Request: server guards for Update Base (from android-amo, 2026-10-18)

To: backend / masterdata owner. The AMO app (F-AMO-028) sends a `location` outlet request whose proposed lat/lng is the AMO's chosen point, within `cfg.geo.update_base_max_distance_m` (100) of the request's own fix. Today `OutletRequests.kt` only validates range and flags `mocked_fix`. Please enforce on create and on approval:
1. distance(request_fix, proposed lat/lng) <= `cfg.geo.update_base_max_distance_m`, else reject (or flag for the approver);
2. a mocked request fix is refused for `location` requests;
3. distance(outlet stored point, proposed) <= `cfg.geo.update_base_max_move_m` (default 1000), above it the TSO approves (existing check) or reject;
4. `cfg.geo.update_base_max_per_outlet_month` per outlet and month.
Also note in docs/24 s12.2: `location` takes lat/lng from the request's own fix or within `update_base_max_distance_m` of it.
