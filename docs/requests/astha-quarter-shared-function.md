# One Astha quarter function for phone, web and server (lane android-sr-programmes, 2026-11-18; for the loyalty server lane)

The phone no longer derives quarter keys or month lists itself. `shared/rules` now holds `AsthaQuarters` (`of(year, month, startMonth)`, `of("YYYY-MM[-DD]", startMonth)`, `parse("YYYY-Qn", startMonth)`): label `YYYY-Qn` where YYYY is the year of the quarter's FIRST month and n counts from `cfg.astha.quarter_start_month`; months are the three `YYYY-MM` of the quarter. It is the algorithm of `backend/platform/.../AsthaQuarter.kt` (verbatim reference copy in `AsthaQuartersParityTest`, which compares every month of 2020 to 2035 for every start month 1..12, and pins vectors for start months 1, 4, 7, 10 and year boundaries).

Asked of the loyalty server lane: make `AsthaQuarter.of` and `AsthaQuarter.parse` call `com.aktcl.aron.rules.AsthaQuarters` (or delete them) so that the two can never drift; the backend already depends on `shared:rules`. Any change of the naming then happens in one place and fails the parity test on both sides.

Note for the owner-facing wording: with a start month other than January the key year is the year of the first month, so `2026-Q4` with start month 7 is April to June 2026 (not 2027); the phone's old rule (a fiscal year) differed for quarters after the new year and was wrong for them.
