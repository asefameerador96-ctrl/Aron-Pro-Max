# Lead review: sr-server follow-ups (2f5922ee..ee82e904)

Reviewed 2026-11-18 by the lead (Opus pass, two lenses). Verdict: PASS, lows only. Tests named were run by the reviewer on a local PostgreSQL 16.

## index-swap-attendance verdict=pass
SUMMARY: Lens: the attendance uniqueness change at ee82e904 (delta 2f5922ee..ee82e904). No blocking breach found; verdict pass.

What can coexist for one user-day (both partial indexes keep the old 'voided_at IS NULL' predicate):
- One rep check_in, one rep check_out and one phone check_out.
- A phone check_in is refused. The V0203 CHECK raises 23514, which IngestService.kt:852 maps to a final schema_invalid. A rep check_in is accepted.
- Two rep check-outs, two phone closes or two check-ins with different UUIDs are refused attendance_duplicate (RecordWriter.kt:140-152).
- Phone close then rep check-out, and the reverse order: both rows are stored and both answered accepted.
- A check-in after a phone close of the same day is accepted.
- A resend of the same UUID still answers duplicate: the registry is checked first, and on 23505 existingId by client_uuid answers AlreadyThere.
- A data void frees the slot as before.

Readers count only the rep row, so nothing double-counts:
- Aggregator.rebuildAttendance check_out_* (and so dw.v_attendance hours) and the gigo report check_out_at, hours and fix filter closed_by = 'rep'.
- DayStates.supervisorDay skips a phone close (line 170); checked_out_at is GREATEST of rep rows only.
- The CHECKOUT_WITHOUT_SUBMIT and checkout_too_early judgements skip a phone close.
- The worker late raise (RiskSignals.kt:177) filters rep.
- TeamApi last-fix filters rep.
- DashboardSummaries reads check-ins only.

The migration:
- The CHECK is NOT VALID in V0203 and validated in V0205.
- One transaction under the NOWAIT retry: both new indexes are created before the old one is dropped, so there is no window without uniqueness.
- It cannot fail on existing data (reasoning in the lows).

Tests I ran in a temp worktree on local PG 16:
- :db:test CheckoutClosedByV0203Test passed (3 tests).
- :backend:app:test CheckoutOutranksTest (5) and CheckoutClosedByTest (6) passed.

Lows:
- V0205 and D-SV2-18 say the index build holds a SHARE lock where the migration takes ACCESS EXCLUSIVE.
- CREATE and DROP INDEX are not idempotent.
- The AE on attendance_event is held across the long VALIDATEs at fleet size.
- GEO_OUT_OF_BOUNDS still runs on a phone-close fix.
- The TeamApi mock count and gigo any_mock still include phone rows.

Not checked: the dev database, a fleet-size timing run, and the risk-rule logic outside this lens. HEAD of the main repository was not moved.

LOWS:
- V0205__attendance_closed_by_and_memo_username_validate.sql:7-8 and DECISIONS.md D-SV2-18 (1): both say the index build 'holds a SHARE lock (reads go on, writes wait)'. That is wrong. The DO block takes ACCESS EXCLUSIVE on app.attendance_event (line 21), so reads of attendance_event (dashboards, gigo, TeamApi) also wait while the two unique indexes are built and until commit. This is harmless at pilot size and takes seconds at fleet size, but the comment and decision text should say ACCESS EXCLUSIVE, or the lock should be lowered to SHARE (enough for CREATE INDEX plus DROP INDEX in a transaction, since DROP INDEX takes AE on the index and the table only briefly).
- V0205:29-31: CREATE UNIQUE INDEX has no IF NOT EXISTS and DROP INDEX app.attendance_event_once has no IF EXISTS. V0203 uses the IF NOT EXISTS pattern, so this is inconsistent with it. Under Flyway's default per-migration transaction (no CONCURRENTLY, no mixed mode) a partial run rolls back, so 'one index left behind' cannot happen through Flyway. A hand re-run or a database where someone pre-created an index of that name would fail, but the failure is an error, not silent damage. Making both statements idempotent would cost nothing.
- V0205:10-14 then 18-27: the VALIDATE statements run first and hold SHARE UPDATE EXCLUSIVE on attendance_event, app_user, memo (all partitions), memo_void and sale_abort until commit. The NOWAIT loop then upgrades the attendance_event lock to ACCESS EXCLUSIVE. Ordinary writes (ROW EXCLUSIVE) are not blocked by SUEX, so I see no deadlock. At fleet size, though, the full memo validate scan lengthens the transaction, and the AE on attendance_event is held until the end of it. Consider putting the index swap before the VALIDATEs, or in its own migration, so the AE is held only for the index build.
- IngestService.kt:1224: outOfBounds (GEO_OUT_OF_BOUNDS) still runs for a phone close, while gnssConsistency (line 1225) and geo_fix (RecordWriter.kt:106) are skipped. The isPhoneClose KDoc (IngestService.kt:1979-1984) promises 'no geo risk rule'. Scenario: a phone close carrying a stale cached fix outside the Bangladesh box raises GEO_OUT_OF_BOUNDS on a rep who was not there at that time. Unlikely (the phone close is meant to read no location), but the code contradicts the stated rule.
- TeamApi.kt:160 (mock CTE) and ReportHandlersB.kt:119 (gigo any_mock): both still count a phone-close row whose fix_is_mock is true. TeamApi locations (line 152) now filters closed_by = 'rep', but the mock count beside it does not, so item (6) 'ignored by team locations' holds only partly. This matters only if the phone close sends is_mock=true (the fix member is required, so it sends some fix).
- Aggregator.kt:316 (fact_attendance zone_id is the first zone by captured_at) also takes phone-close rows into the array. Harmless, because a check-in always sorts earlier, but the phone row is not excluded the way it is everywhere else.
- Not checked: the dev database itself (no access). The argument that it cannot fail: under the old index every non-voided row was already unique on (user_id, business_date, kind), and all existing rows get closed_by='rep' by default, so the rep index is a subset of the old one and cannot conflict. The phone index has no rows, and even if V0203 code had ingested phone closes, at most one check-out per user-day could exist under the old index, so (user_id, business_date) is unique among phone rows. Also not checked: a timed lock or duration run at fleet size, and the RiskSignals withdrawal and late-raise logic outside this lens.

## worker-rules-authz verdict=pass
SUMMARY: Pass, with lows only and no blocking findings. Delta 2f5922ee..ee82e904 (lane/sr-server, fetched into FETCH_HEAD because of a ref lock), checked through the worker-rules, withdrawal and AuthzAudit lens.

**Raise rule** (raiseCheckoutWithoutSubmit, backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/RiskSignals.kt:159-183):
- It looks only at rep check-outs that are not voided, from users whose current role is SR or AMO.
- Captures are ingest_registry rows of any status, of ROUTE_CAPTURE_TYPES. A test in CheckoutOutranksTest keeps that set equal to IngestService.DAY_LOCKED_TYPES.
- "No effective submit" is the same SQL as the ingest: a day_submit not data-voided and not submit_voided, on the route-day or the supervisor-day. A superseded earlier cycle still counts as a submit, which is lenient. No code sets submit_voided to true today.
- An existing row of any status blocks the raise through the unique key (code, subject_type, subject_id, business_date) and ON CONFLICT DO NOTHING (V0007 risk_signal). That same constraint covers races between the ingest and the worker, and between two workers. Dirty keys are marked only for the rows RETURNING gives back. Raise, withdraw, raise cannot happen.

**Withdrawal** (RiskSignals.kt:137-156):
- It moves only rows that are still open; under READ COMMITTED a reviewer's concurrent decision wins.
- It only adds withdrawn_by, withdrawn_at and day_submit_client_uuid; there is no key collision with the ingest evidence.
- It marks route_day_agg dirty for every route the user held or covered that day. That matches Aggregator, which counts suspicious signals per user_id.

**Other checks:**
- The job runs evaluateDay, the withdrawal and the late raise in three separate runCatching blocks.
- A phone close stores no geo_fix and is skipped by the GNSS check and by TeamApi; outOfBounds still runs on it (low).

**AuthzAudit:** the new probe still finds a real kept value at any depth, because the response DTO field is a Long. The string form is no longer caught (low). No baseline entry changed.

**Hygiene:** DECISIONS.md only appended (D-SV2-18). The contract text change is additive: closed_by='rep' is now accepted on a check-in. .github and tools/ci are unchanged; infra/tests/check_infra.py only had its regex widened to {2,31}.

**The main lows:**
- A data-void of a submit days later now raises a late signal; the docs do not say so.
- The late raise has no settle period: a short open-then-withdrawn signal of about 15 minutes, which cannot repeat.
- Every 15 minutes the late raise scans the whole lookback window (30 to 400 days).
- A worker-raised signal has no route_id, so scope falls back to the user's home zone.
- outOfBounds still runs on a phone close's fix.
- The V0205 comment says SHARE lock; the code takes ACCESS EXCLUSIVE.

**What I could not check:**
- I did not run the Gradle tests or the migrations. PostgreSQL 16 is installed here, but I did not set up a worktree build.
- How a quarantined or integrity-held day_submit is stored. If it is not in route_day_event, the rule treats the day as having no submit, the same as the ingest does.
- The phone outbox ordering in the app.
- The query plans and indexes for the new scan.

LOWS:
- Late raise fires on a data-void that comes later, and the docs do not say so. RiskSignals.kt:173-174 raises when NOT EXISTS effectiveSubmit and no row exists. Case: a rep submits and checks out, so the ingest raises nothing and no row exists. Up to the lookback (45 days, configurable to 400) later, an admin data-voids that day_submit. The next 15-minute run raises CHECKOUT_WITHOUT_SUBMIT (raised_by: worker) on that rep. The opposite case, a void after a withdrawal, is documented as never re-raising (KDoc RiskSignals.kt:131-132, D-SV2-17(1)). Both behaviours are defensible, but the KDoc and D-SV2-18(4) should name both.
- No grace period on the late raise (RiskSignals.kt:159-183). If a rep check-out is stored before the captures and the submit (shared phone, reinstall, a second device), one run can raise in the gap and the next run withdraws it. The noise is bounded: open for at most 15 minutes plus the aggregate rebuild, and it cannot ping-pong because the dismissed row holds the unique key and ON CONFLICT DO NOTHING keeps it. In the normal phone outbox order (captures, then submit, then check-out) the gap cannot happen. The ingest-time raise has the same gap. A small settle delay (for example only check-outs whose captured_at is older than N minutes) would remove it.
- Scan load: every 15 minutes raiseCheckoutWithoutSubmit (RiskSignals.kt:170-175) scans every rep check-out in business_date >= today - lookbackDays(). That is 30 to 400 days (cfg.retention.ingest_registry_days, coerceIn at :185). Each check-out gets two correlated EXISTS probes. There is no restriction to days touched recently and no NOT EXISTS on risk_signal (it relies on ON CONFLICT). This is fine at pilot size. At fleet size (8,500 users x 45 days, about 380k candidates per run) it should be bounded, for example by ingest_registry.received_at since the last run, or by the sync backdate window rather than registry retention.
- The first worker run after deploy back-fills over the whole lookback. Older days got their signal from the 1.9 ingest raise, which judged every check-out with no submit, so in practice only edge days are new: a submit voided afterwards, a token role that differed, an ingest raise that failed and was swallowed by runCatching. These signals appear without notice. Worth one line in the rollout note.
- A worker-raised signal has route_id NULL; the ingest raise sets the check-out's route_id (IngestService.kt:773 vs RiskSignals.kt:164). RiskSignalApi.kt:244 and SupervisorBundle.kt:123 then fall back to u.home_zone_id for scope. For a stand-in rep working outside their home zone, the worker-raised signal is visible to the home-zone supervisor, not the zone the day was worked in. Scope is still derived on the server, so this is not a leak, but the visibility is inconsistent with the ingest raise.
- Phone close and geo rules: RecordWriter.kt:104-106 skips the geo_fix, IngestService.kt:1225 skips gnssConsistency, and TeamApi.kt:152 filters closed_by='rep'. But outOfBounds (IngestService.kt:1224, 1464-1494) still runs on a phone close's fix. A phone close carrying a reused out-of-country fix would raise GEO_OUT_OF_BOUNDS on the rep. The isPhoneClose KDoc says 'no geo risk rule' (IngestService.kt:1983). The contract says a phone close takes no location read, so this needs a misbehaving phone.
- AuthzAuditTest.kt:1033-1037 answerKeeps only matches a non-string JSON number whose content parses as a Long and whose key is exactly sale_qty_base. A kept value echoed as a string ("9999"), as 9999.0, or under another key would now be a NOTE, where the old text search (h.body.contains("9999")) made it a FINDING. Today WebEntry.kt:34 declares sale_qty_base: Long?, so a real mass assignment is still found at any depth; the unit test at :1024-1031 proves the positive case, the depth case and the false-positive case. Baseline file unchanged. Suggest also accepting the string form, so the probe is never weaker than the old one on this field.
- V0205 comment (lines 8-9 and D-SV2-18(1)) says the index build 'holds a SHARE lock (reads go on, writes wait)'. The DO block actually takes ACCESS EXCLUSIVE NOWAIT (V0205 lines 19-31), which also blocks reads for the build. That is safe (no duplicate window, the old index is dropped in the same transaction, existing rows are all rep and already unique) but the comment is wrong. This is outside my lens; I only noted it.
- CheckoutOutranksTest.kt:51 builds RiskSignalEvaluator on fresh.db without SET ROLE worker_rw, so the end-to-end chain runs as the owner role. Worker-role coverage for both rules comes from SrCheckoutRoundTest (SET LOCAL ROLE worker_rw at :64 and in the new raise test). Grants check out against V0014: worker_rw has SELECT on app.* except app_user, a column grant on app_user without password_hash (the rule reads only id and role), INSERT and UPDATE on risk_signal, INSERT, UPDATE and DELETE on dirty_key, and EXECUTE on app functions. No grant migration was needed.
