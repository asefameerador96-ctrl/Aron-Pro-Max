# qa → backend-core: Final Submit races (KD-1 late rows undercounted, KD-2 replay off by 1 ms), 2026-10-17

Found by `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/DayCloseRaceTest.kt`. That is a concurrency fuzz of
`POST /v1/day/final-submit`: shared-uuid double submits, two-tab submits, a delegate DMO, the SR phone's batches in
flight, and reopen races, under a strict oracle of 17 clauses. Each clause is proven by a seeded fault.

**Not found:** two live closes, a 5xx, a lost or doubled row, a wrong 409.

**KD-1 (data correctness): late rows undercounted by one when a close lands inside a sale family.**
- `DayStates.afterStored` checks lateness with `UPDATE app.route_day ... WHERE final_submitted_at IS NOT NULL`. While
  the day is open, that statement matches no row and so takes no lock.
- A Final Submit can therefore commit between a family's first record (the visit: not counted) and its later records
  (counted as late), although the whole family commits after the close.
- Result: `route_day.late_rows_after_final` and `final_submit.late_rows` are one short. In the second shape, only the
  route-day is one short and the close row is right.
- Frequency: about 1 round in 25 to 40. Evidence seeds:
  - 20261051 (date 2027-03-12: counted 17, allowed {18, 13, 12});
  - 873 (run seed 777, 2027-07-14: route-day 9 against close row 10);
  - 797, 837, 854, 880, 20261031, 20261045.
- **Fix, verified by the qa checker in a scratch clone:** in `DayStates.afterStored`, before the late checks, run
  `SELECT 1 FROM app.route_day WHERE route_id = ANY(:r) AND business_date = :d ORDER BY route_id FOR NO KEY UPDATE`
  (after `planner.planIn`). Both shapes then give 0/0, and 200 strict rounds were green.
  - Lock the route-days in route_id order, because a `day_open` names several routes. Final Submit
    (`DayApi.kt`, before its route_day UPDATE) should lock the zone's route-days in the same order.
  - **Do not use FOR SHARE.** Two families on one route can both hold it and then both try to UPDATE: a deadlock.
    `IngestService` runs `afterStored` inside a savepoint and only logs its failures, so a deadlock there would
    silently drop the late count.
  - A zone-day advisory lock also works, but ingest must take it in shared mode so the SRs of one zone do not
    serialise.
- **Third shape:** a one-record family (`due_collection`, `memo_void`) never takes the route-day lock, because it has
  no state UPDATE. A close can commit between its route-day late check and its close-row late check. The close row
  then counts the row and the route-day does not, so the two counters differ by one. The fix above (lock the
  route-day for every record type before the late checks) removes this shape too.
- **Severity:** a reporting miscount (one late row per affected close), not a lost or doubled sale. The qa checker
  reproduced it deterministically in both shapes.
- **Proof:** with the fix, `ARON_DAYCLOSE_TOLERATE=none ARON_DAYCLOSE_FUZZ_ROUNDS=200 ./gradlew :backend:app:test
  --tests '*DayCloseRaceTest*'` must be green.
- **Then drop the default** `KD-1` tolerance in `DayCloseRaceTest` (one line, marked). Until then the test counts KD-1
  rounds instead of failing, so CI stays green, and every other failure still fails.

**KD-2: a replay or a 409's `submitted_at` can be 1 ms later than the first answer** (about 1 close in 2,000).
- `finalSubmit` answers `now.wire()`, truncated to the millisecond, but stores `now` through JDBC, which rounds to the
  microsecond. JDK 21's `Instant.now()` carries nanoseconds, so a time in the last 500 ns of a millisecond is stored
  as the next millisecond.
- Repro: `aReplayAnswersTheFirstSuccessToTheMillisecond`, `@Disabled` with this request's name. The clock is fixed at
  `…:00.1239996`; the first answer is `.123Z` and the replay is `.124Z`.
- **Fix, verified by the qa checker:** `val now = clock.now().truncatedTo(ChronoUnit.MICROS)` in `finalSubmit`
  (`DayApi.kt`). The repro then passes; remove the `@Disabled`.

**Latent, for when `POST /v1/day/reopen` is built** (today it answers 404; docs/33 G29/G34): a submit queued behind a
reopen reads its clock before it waits for the zone-day lock, so the second close can be stamped slightly before the
reopen it follows. That happened in 1 to 7 of about 70 reopen rounds (the test reopens in SQL under the same lock).
Read the clock after taking the lock.
