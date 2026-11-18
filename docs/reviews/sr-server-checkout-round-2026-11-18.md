# Lead review: sr-server check-out round (932bb1634..2f5922ee)

Reviewed 2026-11-18 by the lead (Opus pass, two lenses). The tests named were run by the reviewers on a local PostgreSQL 16; the dev database was not touched.

## closed-by-readers verdict=pass
SUMMARY: Lens A+B (closed_by and the CHECKOUT_WITHOUT_SUBMIT signal rules) on lane/sr-server 2f5922ee, delta 932bb1634..2f5922ee: pass, nothing blocking, ten lows.

Ingest of the new member:
- Absent: the column default 'rep' applies, so an old phone's check-out is stored and judged exactly as before (tested).
- 'rep': same as absent.
- 'phone': stored and acked accepted, exempt from checkout_too_early (IngestService.kt:933) and never judged for a missing submit (:732).
- Unknown value, or 'phone' on a check_in: the DB CHECK fails (23514) and the record is refused schema_invalid as a final rejection (:850-852). A JSON null hits NOT NULL (23502) and gets the same refusal.
- The member is allowed by PayloadShapes and stored by RecordWriter's generic jsonb_populate_record insert.
- Resend under the same UUID is a duplicate. A resend with a different closed_by has a different payload hash and is quarantined PAYLOAD_CONFLICT, so it can never become a second check-out.

Every reader of attendance check-out found, and how it treats a phone close:
- Aggregator.rebuildAttendance (Aggregator.kt:318-320): check_out_* filtered to rep only. dw.fact_attendance check-out is NULL, so dw.v_attendance and the V0116/V0118 reuse views give hours_in_field NULL.
- GIGO report (ReportHandlersB.kt:115-120): check-out time, hours and check-out fix are rep only; a check_out_note says 'no check-out (closed by the phone)'. Exports use the same spec.
- DayStates.supervisorDay (DayStates.kt:170): returns early, so supervisor_day.checked_out_at is never set and dw.fact_supervisor_day follows.
- Route-day path: attendance only ever set in_field_at, from check-ins.
- Unaffected because they filter to check-ins only: DashboardSummaries checked_in_at and dirtyStale, DayApi and SupervisorBundle day state.
- TeamApi.locations: excludes fixes with no coordinates, which a phone close normally has (see lows).
- Risk geo rules use only fix_status 'ok'.
- No web/src reader beyond the generated types; no 'open attendance' view exists.

Signal rules:
- Not raised when the user-day has no registry row of a DAY_LOCKED_TYPES type (attendance, day_open, day_submit and telemetry are excluded).
- Withdrawal by the worker (RiskSignals.kt:136-157): only rows still 'open' move, with an EvalPlanQual-safe status recheck, to dismissed plus withdrawn_by, withdrawn_at and day_submit_client_uuid. It is idempotent, counts route-day and supervisor-day submits that are not data-voided or submit-voided, and never touches confirmed, reviewed or reviewer-dismissed rows.
- It runs as worker_rw using the existing V0014 grants (SELECT on app, INSERT/UPDATE on risk_signal); no grant migration.
- Live dashboard readers count only non-dismissed or open/confirmed signals.

V0203 migration: metadata-only ADD COLUMN with a constant default, plus a NOT VALID CHECK, under the V0189 NOWAIT retry. V0205 validates it. It contains no data guard that can RAISE, so no pre-flight mirror is needed. attendance_event is not partitioned.

Verified: in a temporary worktree against a local PostgreSQL 16, these tests passed: CheckoutClosedByTest (6), CheckoutWithoutSubmitTest (3), SrCheckoutRoundTest (2), AttendanceClosedByTest (1), CheckoutClosedByV0203Test (3, including migrating a database holding data from V0191). The worktree, the temporary server and its data directory were removed afterwards; the main repository's HEAD was not moved.

Not checked:
- DayStateTest was not run.
- The full suite and CI were not run.
- Android phone behaviour and the MemoNumbers alignment on lane/android-gates, the bouncycastle pin and the V0204 memo widening belong to other lenses. For V0204 I only confirmed in PG16 that a NOT VALID CHECK can be added to a partitioned parent and then validated.
- Lock behaviour of V0205 VALIDATE on a fleet-sized memo table.
- Whether a periodic full reconcile later corrects the stale aggregate counts.

LOWS:
- Contract and server disagree on closed_by='rep' sent on a check_in. AttendanceEventPayload.yaml (closed_by description) and the openapi text say closed_by on a check_in is refused schema_invalid. But PayloadShapes.kt:18 allows the member on any attendance_event, and the V0203 CHECK (V0203__attendance_closed_by.sql:25) only refuses 'phone' on a check_in. So check_in + closed_by='rep' is accepted. It is harmless (it means the rep) but the contract text overstates the refusal, and CheckoutClosedByTest order 5 only tests 'phone' on a check_in. Fix either the wording or the rule.
- Which close wins for the same day depends on arrival order. attendance_event_once (V0007:399, on user, business_date, kind, not voided) keeps the first stored check-out. A phone close stored first makes a later real rep check-out for the same day get refused attendance_duplicate (RecordWriter.kt:150, IngestService.kt:1229), so that day shows 'no check-out' even though the rep pressed it. On the same phone this should not happen: the phone only closes a day that has no local check-out. On shared phones or after a reinstall it can. No test covers it. Consider letting a rep check-out replace or outrank a phone close.
- Dashboard aggregates are not rebuilt when the worker withdraws a signal. RiskSignalEvaluator.withdrawCheckoutWithoutSubmit (RiskSignals.kt:136-157) does not mark the route-day or zone-day dirty and does not emit risk_signal.changed. The 'susp' parts of dw.agg_daily_route and agg_daily_zone (Aggregator.kt:177, :377) keep counting the withdrawn signal until something else rebuilds that day. The reviewer path (RiskSignalApi.kt:221/253) has the same gap, so this is older behaviour. The live readers are fine: DashboardApi.kt:209 excludes dismissed, ReportHandlersC.kt:64 scores open and confirmed only, SupervisorBundle.kt:123 shows open only.
- A capture that arrives after the check-out is never judged. The check is made once, when the check-out is ingested (IngestService.kt:766, routeCapturesExist at :1980). If the user-day's captures are first registered in a later batch than the check-out, no signal is raised, then or later, even if no submit ever comes. This misses a signal; it never raises one wrongly. Ruling 5 sends the check-out after the submit, so it is rare.
- The capture test counts registry rows of any status, not only accepted ones. routeCapturesExist (IngestService.kt:1980) counts quarantined, rejected and parked rows, and the test asserts this (CheckoutClosedByTest order 6). The android-gates request says 'in the registry' without a status, so this is defensible. It is broader than 'accepted captures': a user-day whose only captures were refused still gets the signal.
- A day stays withdrawn even if its submit is later voided. The withdrawal is never re-checked: a submit voided or superseded after the withdrawal leaves the signal dismissed. This is documented (RiskSignals.kt:129) and tested (SrCheckoutRoundTest), and only the void trail shows it. withdrawCheckoutWithoutSubmit also overwrites the evidence key day_submit_found (false becomes true); the original value is lost.
- The withdrawal shares one runCatching with evaluateDay (RiskSignals.kt:210-213). If evaluateDay throws (for example on a config or geo error), the withdrawal does not run that cycle.
- The server does not check that a phone close has no location. A closed_by='phone' record with a usable non-mock fix would become the user's newest fix for that day in TeamApi.locations (TeamApi.kt:151, source 'check_out'), and its geo_fix row would feed the risk rules. Phones following the request send provider_unavailable, so the coordinates are null and it is filtered out.
- Test coverage gap: no single test runs the real ingest of a day_submit and then the worker rule. The app test (CheckoutClosedByTest order 4) ingests the check-out and the submit and stops at 'still open'. The worker test (SrCheckoutRoundTest) runs on signals and submits inserted by SQL, as worker_rw. AttendanceClosedByTest and DayStateTest also insert rows or call DayStates directly rather than go through /sync.
- GIGO (attendance) report column order changed. check_out_note was inserted before any_mock (ReportHandlersB.kt:101), so in exports every later column shifts by one. Anyone reading the export by position will break; appending the column at the end would avoid it.

## usernames-migrations-contract verdict=pass
SUMMARY: Lens C+D and hygiene for lane/sr-server 2f5922ee (delta 932bb1634..2f5922ee): pass, nothing blocking. The fetch hit a ref lock, so I fetched each branch into FETCH_HEAD. The main HEAD was not moved, and all work ran in temporary worktrees that I removed afterwards. Six low findings are listed separately.

(C) Username and memo-number rules agree everywhere:
- The memo-number rule is now ^[a-z][a-z0-9]{2,31}-\d{6}-\d{3,4}$ in:
  - the database: V0204 on memo, memo_void and sale_abort;
  - the server: MemoApi.kt:78, PaperBackfill.kt:69, SalesSubmitDigest.kt:199 and MemoNumbers.kt:18 (its sequence part is \d{3,}, as before);
  - the contract: MemoNo in openapi.yaml:7367 and the slice;
  - the web: op-rules.ts:54 and paper-backfill-form.tsx:13.
- The SR and AMO username rule is ^[a-z][a-z0-9]{2,31}$ in V0204 (app_user_memo_username), AdminUsers.kt:28, the Username description, and Android MemoNumbers on origin/lane/android-gates 7dcd814cd:14. The meaning is identical in all places.
- No other copy of {3,31} is left in backend, web or contract source, or in any database function or view. The only stale copies are the infra test stub (see lows) and docs or DECISIONS history.
- The change only widens the rules, so every existing memo number still matches.
- Parsing stays unambiguous. A memo username cannot contain a hyphen, and the server compares the part before the first hyphen exactly (MemoNumbers group 1; PaperBackfill.kt:117 substringBefore('-') != username). No code matches memo_no by prefix or LIKE, so 'abc-...' and 'abcd-...' cannot collide.

Migrations V0203..V0205, run on local PostgreSQL 16:
- db tests passed:
  - CheckoutClosedByV0203Test (3 tests): migrates to V0191 with data, then applies the round; checks NOT VALID on the partitioned memo parent after V0204 and validated after V0205.
  - MigrationApplyTest, DataDictionaryTest, DwViewContractTest and MigrationNamingTest.
- Gradle reported a results-file error after the tests finished. That came from this environment; every test class reported SUCCESS.
- In the migrated template database I checked what the constraints actually are. There are exactly the expected five constraints, all validated, and the {2,31} memo CHECK is inherited by memo_default and every memo_yYYYYmMM partition. No {3,31} copy remains.
- Locks:
  - V0203 and V0204 take one LOCK ... NOWAIT, retried every 0.5 s for about 60 s, following the V0189 pattern. They wait holding nothing.
  - Under ACCESS EXCLUSIVE they only change the catalog: ADD COLUMN with a constant default (no table rewrite), DROP plus ADD CHECK NOT VALID. Each is held for milliseconds.
  - V0205's VALIDATE takes SHARE UPDATE EXCLUSIVE and scans the tables, which does not block reads or writes. At fleet size the memo scan will take minutes; there is a precedent in V0077/V0078 on memo.
- Re-running the migrations is safe: IF NOT EXISTS / IF EXISTS guards are used, and Flyway applies each version once.
- The only RAISE is the lock-wait timeout. No data guard can raise, so no pre-flight mirror is needed.
- tools/ci/migrations-check.sh 9bb7f5cf1 passes, with squawk at the pinned 2.67.0 and also 2.73.0.

Contract 1.16.0 to 1.20.0 is additive:
- New AttendanceClosedBy enum; new optional AttendanceEventPayload.closed_by; MemoNo pattern widened; Username description text; changelog entry.
- tools/ci/contract-breaking.sh with oasdiff 1.33.0 reports 0 breaking changes. The 17 warnings are pattern-changed notices for the widened MemoNo.
- PayloadShapes adds closed_by to the allowed fields only, not the required ones.
- The WireDtos member is nullable with a default.
- The slices are current: tools/slice-contract.py changed nothing.
- openapi.d.ts regenerated with openapi-typescript 7.13.0 is byte-identical.
- The data dictionary and dw view contract tests pass.

(D) Bouncycastle: the pin is strictly 1.80 and only through testImplementation constraints in backend app and analytics. The catalog adds bouncycastle = "1.80", and bcpkix stays at 1.80. Resolved runtimeClasspath is identical before and after for backend app, analytics, sync, masterdata and platform.

Hygiene:
- DECISIONS.md only has lines added.
- .github and tools are unchanged.
- The changed tests add new intent and loosen nothing:
  - CheckoutWithoutSubmitTest now seeds a route capture so the signal is still raised under the new rule.
  - AmoManualContractTest pins the new version.
- No personal data in fixtures; the new log lines carry ids only.

Not checked:
- the Android build and tests themselves;
- the full backend test suite (I ran only the db tests above);
- the actual VALIDATE time on a fleet-size memo table;
- lenses A and B in depth: I only checked that IngestService excludes phone closes from too-early and from the CHECKOUT_WITHOUT_SUBMIT check.

LOWS:
- Old APKs and 3-character logins. Once this server lands, AdminUsers.kt:28/96 allows creating a 3-character SR or AMO. The candidate tree still has android/core-database/.../MemoNumbers.kt:12 at ^[a-z][a-z0-9]{3,31}$ (a require()). Only origin/lane/android-gates 7dcd814cd:MemoNumbers.kt:14 is relaxed to {2,31}. A 3-character user signed in on an APK built before the android-gates change would throw on the first memo number. Rollout note: create no 3-character SR or AMO until the fleet runs the android-gates build, or gate it on a minimum app version. This is not a pattern disagreement between the landed pieces.
- infra/tests/check_infra.py:1737: the fake sync server in the infra smoke-test harness still uses [a-z][a-z0-9]{3,31}-\d{6}-\d{3,4}. It only checks the smoke login 'smoke1', so nothing breaks today. It is a stale copy of the pattern and should follow {2,31}. It lives in infra tests, not a gate script.
- Bouncycastle pin: the comment says the resolved version is 'unchanged (1.80)'. That is not always true. On base 932bb1634, with 1.80.2 in the Gradle cache, the range [1.80,1.81) resolved bcutil and bcprov to 1.80.2 in testRuntimeClasspath of backend:app and backend:analytics; the candidate resolves exactly 1.80. This is test-only. runtimeClasspath of backend app, analytics, sync, masterdata and platform is byte-identical before and after (0 diff lines), and no bouncycastle is on any production classpath. Fix the wording in backend/app/build.gradle.kts:16-19, backend/analytics/build.gradle.kts:8-11 and D-SV2-14.
- IngestService.kt:~1971-1985: two KDoc blocks are stacked. The isPhoneClose doc sits above routeCapturesExist, so it documents the wrong function. Cosmetic only.
- Already true before this change: due_ledger.memo_no and due_collection.against_memo_no are CHECK length <= 40 (V0007:1000, 1997). A memo number of a 32-character username is 44 characters. The widening does not make this worse, but the length limits still disagree with the 32-character maximum.
- Already true before this change: the server's MemoNumbers.FORMAT allows \d{3,} for the sequence, while the database, MemoApi, PaperBackfill, SalesSubmitDigest, the contract and the web use \d{3,4}. The database refuses anything longer, so this round does not change it.
