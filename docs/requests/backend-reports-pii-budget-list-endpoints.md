# backend-reports → masterdata / sync / backend-admin: charge the PII read budget on your list endpoints (F-SYS-058)

Status: open, from backend-reports, 2026-10-15. Found by the F-SYS-058 Opus checker.

docs/21 s4.5 puts the hourly personal-data budget "across list endpoints" (the `GET /outlets` harvest is the named threat). The budget now lives in the platform:
`com.aktcl.aron.backend.platform.PiiReadBudget.charge(db, config, clock, userId, role, rows)` (atomic; 429 with Retry-After when the rows would pass `cfg.pii.list_rows_per_hour`; a refused read is not counted; the TSO limit is a role value; future-dated and closed role values are respected).

Call it with the number of rows you are about to return **with unmasked personal columns** (only when the caller holds the pii claim; masked reads are free), before responding:
- masterdata `OutletsApi.kt` (contact_number; docs/21 treats owner_name as personal too), `AdminOutlets.kt`, `AdminUsers.kt` (phone)
- sync `NearbyApi.kt` (outlet phone)
Add a test per endpoint: a pii read within the limit passes, the next read that would pass it is 429 and the budget is not eaten by the refusal.

backend-reports already charges `POST /v1/reports/{key}/query` (json) and the daily export budget.
