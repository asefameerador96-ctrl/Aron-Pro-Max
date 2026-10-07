# Request: web-config -> backend-admin: day control lists

Rows: F-ADM-029, F-ADM-052 (Day control: late-sync queue after final, missing check-out list).

There is no endpoint for either list. The web reads the report registry (`POST /v1/reports/final-submit-log/query` and
`/attendance/query`, `period.date`, `geo.zone`) and finds the rows by column key pattern: a `late*` column that is true or above
zero (final-submit-log), and a `check_out*` column that is empty with a `check_in*` column present (attendance).
`// REQUEST: web-config-day-control-columns`: when the reports have no such column, the page says so instead of guessing.

Needed: the final-submit-log report carries a boolean/number column whose key contains `late` (rows after the final), and the
attendance report carries `check_in_at` and `check_out_at` timestamp columns (null when missing). Or a dedicated operation; the web adapts.
