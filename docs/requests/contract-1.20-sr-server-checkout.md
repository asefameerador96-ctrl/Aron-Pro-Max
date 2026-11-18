# Contract 1.20.0: SR check-out round (lane sr-server, SV6, 2026-11-17)

Version 1.20.0 is **provisional**: the lead confirms the number and the migration numbers before the last push, because landing order decides (trains before this one: Loyalty C
V0192..V0194 / 1.17.0, master data V0195..V0200 / 1.18.1, integrity V0201..V0202 / 1.19.0). This round sits on 1.16.0 (INT 9bb7f5cf1) and uses V0203..V0205.
Approval REQUESTED from the lead (not approved until the lead says so). Everything is **additive or looser**. Source: ruling 6 of D-LEAD-PREREQ-03, section 4 (second "## 4",
"Check-out: who closed it") and section 5 of `docs/requests/android-gates.md` on `lane/android-gates`; records D-SV2-14 to D-SV2-16 (DECISIONS.md).

## What changes

| change | where | rows |
|---|---|---|
| `AttendanceEventPayload.closed_by` (new schema `AttendanceClosedBy`: `rep` or `phone`), optional | `POST /v1/sync/batch`, record `attendance_event` | A |
| `MemoNo.pattern` and the memo-producing `Username` text: `{3,31}` becomes `{2,31}` after the first letter (a 3-character username) | schemas `MemoNo`, `Username` (description) | C |
| (no schema change) `CHECKOUT_WITHOUT_SUBMIT` is not raised for a user-day with no route captures and is withdrawn when an accepted Sales Submit arrives later | `RiskSignal` rows, worker rule | B |

## Behaviour (A): `closed_by`

* Only on a `check_out`. A `check_in` that carries it is refused `schema_invalid` (final); so is any value other than `rep` or `phone`. Absent means `rep` (every phone before this round).
* `phone` = the phone closed a forgotten day at a later start (no location read). The record is stored and acknowledged `accepted` (idempotent by `client_uuid` like any record), but it is read
  everywhere as "no check-out": no check-out time and no hours (`dw.fact_attendance.check_out_*` stay empty, so `dw.v_attendance.check_out_at`/`hours_in_field` and the attendance report `gigo` show none),
  AMO `supervisor_day.checked_out_at` is not set, it is never judged by `cfg.day.checkout_earliest_time` (no `checkout_too_early`), and `CHECKOUT_WITHOUT_SUBMIT` is not judged for it.
* Uniqueness (V0205): at most one REP row per user, day and kind and at most one phone close per user and day, so a real check-out of the same day outranks a phone close whatever the arrival order (both are stored; every reader looks at the rep rows). `closed_by = rep` on a check-in is accepted; `phone` on a check-in is refused `schema_invalid`. A phone close's fix is never a geo_fix (not the newest location, no geo risk rule).
* **Order of deployment:** `PayloadShapes` is strict, so an older server refuses the member `schema_invalid`. The server ships (migration V0203 first, then the API) before any phone sends it.

## Behaviour (B): `CHECKOUT_WITHOUT_SUBMIT`

* Not raised for a user-day without a route capture: no `app.ingest_registry` row (any status) of the user and business date whose record type is a day-lock figure type
  (`visit`, `visit_close`, `visit_skip`, `sale_abort`, `memo`, `memo_line`, `memo_discount`, `qc_line`, `memo_void`, `due_collection`, `stock_movement`, `redemption`, `redemption_line`).
  Attendance, `day_open`, `day_submit` and telemetry are not captures.
* Withdrawn by a worker rule (`RiskSignalEvaluator.withdrawCheckoutWithoutSubmit`, run by the risk-signal job every 15 minutes, as `worker_rw`; the API role may not update `risk_signal`, no grant changed):
  an `open` signal whose user-date now has an effective `day_submit` (not data-voided, its route-day or supervisor-day not submit-voided) becomes `dismissed` with
  `evidence.withdrawn_by = "day_submit"` (also `withdrawn_at`, `day_submit_client_uuid`). The status set has no `withdrawn` (adding one would widen the CHECK and every reader), and the dashboards count only `open` and `confirmed`.
  A signal a reviewer has already decided (`reviewed`, `dismissed`, `confirmed`) is never touched. Looks back `cfg.retention.ingest_registry_days` (45) business dates.
* The mirror: a worker rule also raises the signal for a real check-out whose day got route captures AFTER it was stored and has no submit (`evidence.raised_by = worker`). Withdrawal and late raise mark the user's route-day aggregates dirty.
* Delay: up to about 15 minutes after the submit is stored. A submit voided or superseded AFTER the withdrawal does not re-raise the signal (the ingest raises only when a check-out is stored; the dismissed row keeps the unique key), the day stays withdrawn.
* The attendance report `gigo` gains the string column `check_out_note` (the LAST column): `no check-out (closed by the phone)` for a user-day whose only check-out is a phone close (lead ruling 2).

## Behaviour (C): three-character memo usernames

`app_user_memo_username` and the `memo_no` CHECK of `memo`, `memo_void` and `sale_abort` accept `^[a-z][a-z0-9]{2,31}` (V0204, NOT VALID, validated by V0205); the server's memo-number
parser (`MemoNumbers`), the Sales Submit digest, `MemoApi`, the paper backfill and the admin user form follow. A value that was valid stays valid. `docs/24` s7.5 says 3 to 32.

## Tests (fail before this round, pass after)

`CheckoutClosedByTest`, `DayStateTest.anAmoPhoneClose...`, `MemoUsernameThreeCharsTest` (backend:app); `SrCheckoutRoundTest` (backend:masterdata); `AttendanceClosedByTest` (backend:analytics);
`CheckoutClosedByV0203Test` (db). `CheckoutWithoutSubmitTest` now seeds a capture for the days it judges.

## Web and android

Done in this round (lead ruling 6): `web/src/lib/admin/op-rules.ts`, `web/src/components/admin/config/paper-backfill-form.tsx`, regenerated `web/src/contract/openapi.d.ts`; web tests `op-rules-v14`, `contract-drift`, `entry-pages`, `checker-v14-forms`, `backfill-match-access` and `tsc --noEmit` pass. The android `ErrorReporter` gap is filed in `docs/requests/android-error-reporter-short-usernames.md`.

## Rollout

Create no 3-character SR or AMO login until the fleet runs the android-gates build (older APKs hold the 4-character memo username rule and throw on the first memo number), or gate it on a minimum app version.
