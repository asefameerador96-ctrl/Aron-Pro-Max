-- V0097 the working-day calendar of the minimum target slice (owner directive 2026-10-22, docs/34 s2, D-TARGET-MIN-01;
-- dashboard brief s2: daily target = monthly target / working days of the month, Fridays and holidays excluded).
-- No new table: the days are dw.dim_date (V0011, extended by dw.build_dim_date) and the holidays are the admin's
-- app.calendar_holiday rows (V0005; admin page "Calendar"), so there is one source of truth for each.
-- 1. dw.v_calendar: one row per date with is_friday, is_holiday (a live national holiday or emergency off day),
--    holiday_name, is_makeup_day (a declared selling day that is normally off) and is_working_day
--    (= is_makeup_day OR NOT (is_weekend OR is_holiday); the weekend is Friday, dw.dim_date.is_weekend).
--    National (global-scope) entries only: a division- or zone-scoped holiday does not change the national working
--    days; such scoped days are read from app.calendar_holiday by the API where it needs them.
-- 2. dw.v_calendar_month: per month the count of days, Fridays, holidays and working days.
-- 2b. dw.v_working_day (day, working): the shape backend-reports reads (db-backend-reports-dashboard-summaries.md A5).
-- 3. dw.v_route_monthly_target: the route x SKU targets of V0096 with category, base unit, the month's working days
--    and the daily target (numeric, base units; rounding is the caller's, the dashboard shows two decimals).

SET lock_timeout = '5s';

CREATE VIEW dw.v_calendar AS
SELECT d.business_date, d.month, d.iso_weekday, d.iso_weekday = 5 AS is_friday, d.is_weekend,
       h.off_id IS NOT NULL AS is_holiday, coalesce(h.off_name, m.makeup_name) AS holiday_name,
       m.makeup_id IS NOT NULL AS is_makeup_day,
       (m.makeup_id IS NOT NULL OR NOT (d.is_weekend OR h.off_id IS NOT NULL)) AS is_working_day
  FROM dw.dim_date d
  LEFT JOIN LATERAL (SELECT c.id AS off_id, c.name_en AS off_name FROM app.calendar_holiday c
                      WHERE c.date = d.business_date AND c.scope_type = 'global' AND c.revoked_at IS NULL
                        AND NOT c.selling_day) h ON true
  LEFT JOIN LATERAL (SELECT c.id AS makeup_id, c.name_en AS makeup_name FROM app.calendar_holiday c
                      WHERE c.date = d.business_date AND c.scope_type = 'global' AND c.revoked_at IS NULL
                        AND c.selling_day) m ON true;

CREATE VIEW dw.v_calendar_month AS
SELECT month, count(*)::int AS days, count(*) FILTER (WHERE is_friday)::int AS fridays,
       count(*) FILTER (WHERE is_holiday)::int AS holidays, count(*) FILTER (WHERE is_working_day)::int AS working_days
  FROM dw.v_calendar
 GROUP BY month;

CREATE VIEW dw.v_working_day AS
SELECT business_date AS day, is_working_day AS working FROM dw.v_calendar;

CREATE VIEW dw.v_route_monthly_target AS
SELECT t.month, t.route_id, t.sku_id, s.category_code, s.base_unit, t.qty_base AS monthly_qty_base, cm.working_days,
       CASE WHEN cm.working_days > 0 THEN t.qty_base::numeric / cm.working_days END AS daily_qty_base,
       t.batch_uuid, t.updated_at
  FROM app.route_monthly_target t
  JOIN app.sku s ON s.id = t.sku_id
  LEFT JOIN dw.v_calendar_month cm ON cm.month = t.month;

COMMENT ON VIEW dw.v_calendar IS 'Stable view: one row per date with Friday, national holiday, make-up day and working-day flags (dim_date plus the live global app.calendar_holiday rows).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_calendar.business_date IS 'Calendar date (Asia/Dhaka business date).';
COMMENT ON COLUMN dw.v_calendar.month IS 'First day of the date''s month.';
COMMENT ON COLUMN dw.v_calendar.iso_weekday IS 'ISO weekday, 1 = Monday ... 5 = Friday ... 7 = Sunday.';
COMMENT ON COLUMN dw.v_calendar.is_friday IS 'True on Fridays.';
COMMENT ON COLUMN dw.v_calendar.is_weekend IS 'True on the weekly off day (Friday, dw.dim_date.is_weekend).';
COMMENT ON COLUMN dw.v_calendar.is_holiday IS 'True when a live national holiday or emergency off day is declared for the date.';
COMMENT ON COLUMN dw.v_calendar.holiday_name IS 'English name of the declared holiday or make-up day; null on ordinary days.';
COMMENT ON COLUMN dw.v_calendar.is_makeup_day IS 'True when the date is declared a selling (make-up) day.';
COMMENT ON COLUMN dw.v_calendar.is_working_day IS 'True when the date counts as a working day: a make-up day, or neither weekend nor holiday.';
COMMENT ON VIEW dw.v_calendar_month IS 'Stable view: per month the number of days, Fridays, national holidays and working days (divisor of the daily target).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_calendar_month.month IS 'First day of the month.';
COMMENT ON COLUMN dw.v_calendar_month.days IS 'Days in the month.';
COMMENT ON COLUMN dw.v_calendar_month.fridays IS 'Fridays in the month.';
COMMENT ON COLUMN dw.v_calendar_month.holidays IS 'Dates in the month with a live national holiday or emergency off day.';
COMMENT ON COLUMN dw.v_calendar_month.working_days IS 'Working days in the month (dw.v_calendar.is_working_day).';
COMMENT ON VIEW dw.v_working_day IS 'Stable view: one row per date, working = the national working-day flag of dw.v_calendar (Fridays and national holidays off, make-up days on).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_working_day.day IS 'Calendar date (Asia/Dhaka business date).';
COMMENT ON COLUMN dw.v_working_day.working IS 'True when the date counts as a national working day.';
COMMENT ON VIEW dw.v_route_monthly_target IS 'Stable view: route x SKU monthly targets in base units with the month''s working days and the derived daily target (D-TARGET-MIN-01).
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON COLUMN dw.v_route_monthly_target.month IS 'Target month (first day of the month).';
COMMENT ON COLUMN dw.v_route_monthly_target.route_id IS 'Route (app.route).';
COMMENT ON COLUMN dw.v_route_monthly_target.sku_id IS 'SKU (app.sku).';
COMMENT ON COLUMN dw.v_route_monthly_target.category_code IS 'Product category of the SKU: cigarette, bidi, lighter or match.';
COMMENT ON COLUMN dw.v_route_monthly_target.base_unit IS 'Base unit of the quantities: stick, piece or dozen.';
COMMENT ON COLUMN dw.v_route_monthly_target.monthly_qty_base IS 'Target quantity for the month in base units.';
COMMENT ON COLUMN dw.v_route_monthly_target.working_days IS 'Working days of the month (dw.v_calendar_month).';
COMMENT ON COLUMN dw.v_route_monthly_target.daily_qty_base IS 'Daily target in base units = monthly_qty_base / working_days, unrounded; null when the month has no working day.';
COMMENT ON COLUMN dw.v_route_monthly_target.batch_uuid IS 'Upload batch that last wrote the target (app.route_target_batch).';
COMMENT ON COLUMN dw.v_route_monthly_target.updated_at IS 'UTC instant of the last upload that wrote the target.';

SELECT app.apply_db_role_grants();
