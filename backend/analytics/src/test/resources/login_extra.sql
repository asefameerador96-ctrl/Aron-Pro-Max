-- Successful Login (login AND download AND check-in, D-REPORTS-LOGIN-01) for the seeded day: every route-day downloaded its bundle at 01:30Z and its SR checked in
-- at 03:00Z (route-day assignee; skipped for a user who already has a live check-in that day, so a test's own attendance events stay as they are).
UPDATE app.route_day SET bundle_count = 1, last_bundle_at = TIMESTAMPTZ '2026-10-04 01:30Z' WHERE business_date = DATE '2026-10-04' AND bundle_count = 0;
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', rd.assigned_user_id, rd.route_id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false, 'Banani'
    FROM app.route_day rd
   WHERE rd.business_date = DATE '2026-10-04' AND rd.assigned_user_id IS NOT NULL AND rd.logged_in_at IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM app.attendance_event e WHERE e.user_id = rd.assigned_user_id AND e.business_date = rd.business_date AND e.kind = 'check_in' AND e.voided_at IS NULL);
