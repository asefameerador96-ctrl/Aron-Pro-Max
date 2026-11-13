-- V0178 validates what V0177 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies it: the new column is 0 everywhere.
SET lock_timeout = '5s';
ALTER TABLE app.route_day VALIDATE CONSTRAINT route_day_late_rows_sales_check;
