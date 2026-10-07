-- V0042 AUD-DA-07: dw.build_dim_date(from, to) extends the calendar dimension (V0011 generated 2026-01-01 to
-- 2030-12-31 once). Same attributes as V0011: Friday weekend (cfg.calendar.weekend_days default), visit_day_bit 0 for
-- Saturday. Existing dates are left untouched (their is_holiday flags come from app.calendar_holiday). Idempotent;
-- the worker may call it yearly, e.g. dw.build_dim_date(current_date, current_date + 3 * 365).

SET lock_timeout = '5s';

CREATE FUNCTION dw.build_dim_date(p_from date, p_to date) RETURNS integer
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  n integer;
BEGIN
  IF p_from IS NULL OR p_to IS NULL OR p_to < p_from OR p_to - p_from > 3660 THEN
    RAISE EXCEPTION 'dw.build_dim_date: give a range of at most ten years (from % to %)', p_from, p_to USING ERRCODE = '22023';
  END IF;
  INSERT INTO dw.dim_date (business_date, iso_weekday, visit_day_bit, month, quarter, iso_week, is_weekend)
  SELECT d, extract(isodow FROM d)::smallint, ((extract(isodow FROM d)::int + 1) % 7)::smallint, date_trunc('month', d)::date,
         to_char(d, 'YYYY') || '-Q' || to_char(d, 'Q'), extract(week FROM d)::smallint, extract(isodow FROM d) = 5
    FROM generate_series(p_from, p_to, interval '1 day') AS g(t), LATERAL (SELECT g.t::date AS d) x
  ON CONFLICT (business_date) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- No function grant here: app.apply_db_role_grants() grants EXECUTE on every dw function to the dw readers. The
-- function runs with the caller's rights, so only a role that may INSERT into dw.dim_date (the worker) can add dates;
-- web_ro and bi_reader get 42501.

COMMENT ON FUNCTION dw.build_dim_date(date, date) IS 'Adds the missing dates of [from, to] (at most ten years) to dw.dim_date and returns how many were added; existing rows are not changed.';
