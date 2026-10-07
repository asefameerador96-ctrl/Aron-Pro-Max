# Request (backend-core → backend-admin): `route_assignment.ended_at` in reach and route planning

Found by the F-API-005 checker (2026-10-07, not reproduced). `SqlReach` and `SqlRoutePlanner` (backend:masterdata) select
assignments by `valid_from`/`valid_to` only, while `ConfigResolver`, `ConfigService`, `ConfigTools`, `DeviceOtps` and
`AdminPrices` also require `ended_at IS NULL`. If an assignment can be ended by setting `ended_at` without moving
`valid_to`, the bundle and the batch scope check still treat it as active (possible scope leak).

**Ask:** one rule for every reader. Proposed: an assignment covers business date `d` when
`valid_from <= d AND (valid_to IS NULL OR valid_to > d) AND (ended_at IS NULL OR ended_at > <start of d in Dhaka>)`, so
history stays valid for late uploads of earlier days while an ended assignment stops at once. Ending an assignment should
also set `valid_to` (the admin write path is backend-admin's). backend-core consumes this through the platform
`ReachResolver`/`RoutePlanner` interfaces and needs no change.
