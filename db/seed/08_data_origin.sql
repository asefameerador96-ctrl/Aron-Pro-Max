-- Data origin restamp (V0116, D-DB-DATA-ORIGIN-01): after 01, 03 and 06 have tagged their accounts, routes and outlets,
-- every capture row and dw row re-derives its data_origin from those tags (app.restamp_data_origin, owner only; the
-- seed runs as the migration owner). A second run changes nothing. No-op on a database migrated before V0116.
DO $origin$
BEGIN
  IF to_regprocedure('app.restamp_data_origin(date)') IS NULL THEN
    RETURN;
  END IF;
  PERFORM app.restamp_data_origin(NULL);
END
$origin$;
