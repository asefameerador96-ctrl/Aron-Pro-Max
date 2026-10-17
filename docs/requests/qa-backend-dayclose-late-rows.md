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
- **Suggested fix:** take the route-day row lock (`SELECT ... FOR NO KEY UPDATE`, or `FOR SHARE` against the close's
  `UPDATE`) before the late check, so a close and a family serialise. A lock on the zone-day key also works.
- **Proof:** with the fix, `ARON_DAYCLOSE_TOLERATE=none ARON_DAYCLOSE_FUZZ_ROUNDS=200 ./gradlew :backend:app:test
  --tests '*DayCloseRaceTest*'` must be green.
- **Then drop the default** `KD-1` tolerance in `DayCloseRaceTest` (one line, marked). Until then the test counts KD-1
  rounds instead of failing, so CI stays green, and every other failure still fails.

**KD-2: a replay or a 409's `submitted_at` can be 1 ms later than the first answer** (about 1 close in 2,000).
- `finalSubmit` answers `now.wire()`, truncated to the millisecond, but stores `now` through JDBC, which rounds to the
  microsecond. JDK 21's `Instant.now()` carries nanoseconds, so a time in the last 500 ns of a millisecond is stored
  as the next millisecond.
- Repro: `aReplayAnswersTheFirstSuccessToTheMillisecond`, `@Disabled` with this request's name. The clock is fixed at
  `06:00:00.9999997`; the first answer is `.999Z` and the replay is `06:00:01.000Z`.
- **Suggested fix:** truncate `now` once (to the microsecond or the millisecond) before it is both answered and
  stored. Then remove the `@Disabled`.

**Latent, for when `POST /v1/day/reopen` is built** (today it answers 404; docs/33 G29/G34): a submit queued behind a
reopen reads its clock before it waits for the zone-day lock, so the second close can be stamped slightly before the
reopen it follows. That happened in 1 to 7 of about 70 reopen rounds (the test reopens in SQL under the same lock).
Read the clock after taking the lock.
