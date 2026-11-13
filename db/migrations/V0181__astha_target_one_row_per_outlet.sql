-- Loyalty train 3, push B3 (lane portal-loyalty-api; Opus pass finding B3): one Astha target row per (month, outlet, brand) for outlet rows.
-- The V0007 index astha_target_one includes route_id, so after an outlet moved to another route a re-upload of the same quarter left a second row (the outlet's old route and its new one)
-- and the territory reads showed target and achievement twice. The upload now updates the one row and moves its route_id. If rows that violate this already exist the migration FAILS
-- LOUDLY at CREATE UNIQUE INDEX (nothing is deleted or merged silently); the table is small (at most one row per outlet, brand and month of a quarter).

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX astha_target_outlet_one ON app.astha_target (month, outlet_id, coalesce(brand_id, 0)) WHERE outlet_id IS NOT NULL;

COMMENT ON TABLE app.astha_target IS 'Astha monthly target of a route (outlet_id null), an outlet (brand_id null: the memo target "All Brand") or an outlet and brand; one row per (month, outlet, brand) for outlet rows (index astha_target_outlet_one), so a route change moves the row instead of doubling it.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
