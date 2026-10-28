# TSO manual build, lane F2 (foundation-db): status

Contract: `docs/requests/tso-manual-build-2026-10.md` (commit 5eece4ec on the integration branch) s2, s5 F2, s6.
Branch `tso-man/F2`, based on train 54 (eb7b8eb5). No pull request (rule 11). Proof: local and CI tests only; nothing
here touches Azure (rule 1).

## Done

| File | Items | What |
|---|---|---|
| V0130__support_ticket.sql | TK-02, TK-05, TK-20 | `app.support_ticket_no_seq` (START 100001), `app.next_support_ticket_no()`, `app.support_ticket` (write-once capture row, feedback envelope), `app.support_ticket_state` (forward-only status, write-once facts), `app.support_ticket_event` (append-only thread); origin registry rows, stamp and guard triggers |
| V0131 + V0132 | TK-03, TK-21, FB-03 | code_list key CHECK replaced NOT VALID then validated; lists `ticket_issue_type` (31 Annex A types verbatim, Annex A order, attrs per D-TSO-MAN-19) and `ticket_resolution_code` (resolved); `feedback_category` item feedback (sort 2) |
| V0133__user_notification.sql | SH-08 | `app.user_notification` inbox (dedupe_key idempotency, read_at write-once, prune by DELETE) |
| V0134__tso_manual_config.sql | TK-21, T-05, PER-07, SP-03, LV-21, FS-13, DP-01..05, LV-22 | 15 cfg keys; defaults late_sync -> quarantine, reopen_roles -> ADMIN, SUPERADMIN; `support.ticket.manage` for ADMIN, SUPERADMIN; 6 domain-event types v1 (planned) |
| V0135 + V0136 | FS-11, FS-13 | `final_submit.via` + job, `submitted_by` nullable, CHECK `final_submit_job_actor`; sync_rejected + day_final_submitted, ticket_state_invalid, issue_type_unknown; sync_quarantine + after_final_submit; late_rows comments fixed; all four constraints validated in V0136 |
| V0137 + V0138 | SP-03 | `app.visit_plan.route_id` + FK NOT VALID, validated in V0138 |
| V0139__retailer_questionnaire_seed.sql | VQ-01 | active `retailer_questionnaire` rubric v1 with the two questions (bn from screenshot 37, en from the manual), idempotent |
| V0140__dw_tso_views.sql | DP-01..05, TK-20, LV-22, DASH-04 | `dw.v_support_ticket`, `v_leave_application`, `v_feedback`, `v_visit_plan_outlet`, `v_call_assessment`, `v_task`; view contract 1.3; `v_calendar` and `v_calendar_month` comments say global only |
| db/src/test/.../TsoManualV0130Test.kt | all of the above | see Tests |
| docs/data-dictionary.md, docs/data-events.md, docs/dw-view-contract.md | rule 12 | regenerated |

## Decisions and deviations (for the lead; DECISIONS.md is the lead's)

1. **Ticket number function.** The contract's default `'INC' || lpad(nextval(..)::text, 6, '0')` cuts a 7-digit value to
   its first 6 digits (lpad truncates), so INC999999 would be followed by INC100000, a duplicate. The default is
   `app.next_support_ticket_no()`: one nextval, padded below 1,000,000, printed in full above (INC1000000). Tested.
2. **`app.support_ticket_state` carries `data_origin`** (child of the ticket in the origin registry, like
   `app.feedback_status`). Not in the s2.1 column list, but DataOriginTest requires every `retention: transaction` table
   to be in the registry, and the state row is the ticket's data. Stamped by trigger; writers need no change.
3. **Database backstops of the D-TSO-MAN-20 matrix** on `app.support_ticket_state`: `guard_transition` (new>in_progress,
   new|in_progress>resolved, new|in_progress>cancelled, resolved>closed) and a write-once guard (ticket, requester and
   role fixed; first_response, resolution, close and cancel facts written once; status, priority, assignee, version,
   updated_at free). B1's service still answers 409 ERR_TICKET_TRANSITION_INVALID first; the trigger refuses with 42501
   if a code path ever skips it. The event table also checks from_status/to_status against the five statuses.
4. **View column order.** s2.5 lists `is_effective, data_origin`; the existing DwViewContractTest and DataOriginTest pin
   `data_origin` then `is_effective` last for every `dw.v_*` view, so the six views follow that order.
5. **`lake_export` grant.** No `lake_export` role exists in the database (the lake job is not built); the views get the
   same grants as every other `dw.v_*` view (web_ro, bi_reader through the `v_*` map rows; api_rw reads all of dw).
6. **PII metadata.** The data dictionary accepts `pii: none|personal|sensitive|secret` and a retention class word, not
   "low" or a config key: tickets, ticket state, ticket events and the inbox are `pii: personal` (free text columns
   tagged `[pii:personal]`: description, affected_username, resolution_notes, cancel_reason, body, body_en, body_bn);
   the inbox's retention class is `ops` (the prune window is `cfg.notify.inbox_retention_days`). The dw views are
   `pii: none` (no free text).
7. **Editor permissions of rows without a bracket in s2.4:** kpi, tso and leave keys take `cfg.edit.field` (their area);
   `cfg.support.ticket_max_photos` and `cfg.feedback.max_images` take `cfg.edit.ops` like the other upload limits.
   List items (subset of SR/AMO/TSO; two ascending 1..99) are in `bounds_rule`: the config editor checks `max_items`
   only (ConfigValidator), so the reading modules must enforce the item rule. Bangla descriptions stay NULL like every
   other key.
8. **Grants for worker jobs** (grant map rows): `worker_rw` INSERT, DELETE on `app.user_notification` (auto-submit inbox
   rows, InboxPruneJob) and INSERT on `app.final_submit` (AutoFinalSubmitJob). `api_rw` has no DELETE on the inbox:
   B1, run the prune in the worker. **Not granted:** `worker_rw` INSERT on `app.audit_log`; DbRolesTest pins it as
   forbidden (the audit trail belongs to the API and auth roles). See the ask to B3 below.
9. **Extra comment fixes** next to the two the contract names: `dw.agg_daily_route`, `dw.v_daily_route` and
   `dw.v_route_day.late_rows_after_final`, `final_submit.via`, `final_submit.submitted_by`,
   `dw.v_final_submit_log.first_submitted_by`, `sync_rejected.code`, `sync_quarantine.code`.
10. **Existing tests touched (expected values or fixture scope only, nothing weakened):** CodeListsTest code list count
    17 -> 19; DataEventsTest planned set + the six V0134 types; DwViewContractTest history + "1.3" (the test's own
    procedure for a new contract version); SchemaV1aTest `ruled` set + the 15 V0134 keys (the set exists for keys the
    lead added by ruling after docs/24 s9.5; docs/24 is not an F2 file); BackOfficeAndConfigV0023Test inserts version 1
    only for the rubric it creates (V0139 now seeds a rubric, so "every rubric" collided).
11. **db/schema.sql** is the original starting-point sketch (not kept in sync with the migrations); not changed.
12. **Origin of a midnight-job Final Submit (checker round 1, V0135 s4).** `app.final_submit` is a pass-1 table of the
    V0116 origin registry, so its stamp and restamp read only user columns, outlet_id and route_id; a `via = 'job'` row
    has none of them and would have been stamped `production` for every zone, test zones included (and the stamp
    overwrites what the writer sends). V0135 now gives final_submit its own rule, `app.derive_final_submit_origin`: a
    user's submit keeps the generic rule (the submitter's origin, unchanged since V0116); a job submit takes the most
    synthetic origin of the routes in its `route_states`, or of the zone's routes when it lists none (an element without
    a numeric route_id is ignored). The trigger `final_submit_stamp_origin` now calls `app.stamp_final_submit_origin()`;
    the registry `app.data_origin_capture` gains `derive_fn` (null = `app.derive_data_origin`; set only for
    final_submit) and `app.restamp_data_origin` (replaced, otherwise as V0121) calls it, so stamp and restamp agree.
    `app.derive_data_origin` is unchanged and never reads zone_id, so a production memo or visit in a mixed zone stays
    production. B3 needs no change: the job only has to write `route_states` as DayApi does (the preview's routes).
    Confirmed by lead ruling R-F-09 (fix round 2).
13. **Resolution message length (checker round 1, V0130; lead ruling R-F-02).** D-TSO-MAN-20's message
    `[TicketID: <no>] - Resolved:\nResolution Code: <label>\nResolution Notes:\n\n<notes>` is 62 fixed characters +
    a ticket number (up to 22: INC + a bigint) + the code label (up to 120, `code_list_item.label_en`) + notes (up to
    2000, the state CHECK and SupportTicketUpdate) = at most 2204. The event body CHECK was `length(body) <= 2000`, so a
    valid resolution with notes over about 1810 characters would have failed the insert (23514, a 500 on the desk
    PATCH). V0130 now allows `length(body) <= 2000 OR (kind = 'resolution' AND length(body) <= 2400)`, 2400 being the
    contract's `SupportTicketEvent.body` maxLength (R-F-02; the checker round 1 draft used 2300); every other kind keeps
    2000 (comments from the phone and the desk unchanged).

## Asks and notes for other lanes

- **B3 (bundle): `BundleRubricsTest` and the V0139 seed.** Done by F2 under R-F-10 (fix round 2): the test now pins
  that a fresh database sends the seeded Visit Query to TSO and AMO, and the admin-rubric case sets the seeded row
  inactive first. Still worth a look for B3: `BundleContent.rubrics` returns every active rubric of a kind; with the
  seed plus an admin-created one there can be two active `retailer_questionnaire` rubrics, and the phone would get both.
- **F1: `SupportTicketEvent.body` maxLength.** Settled by R-F-02 (2400, F1 done); the database CHECK matches.
- **B1:** build the resolution message exactly as D-TSO-MAN-20 (`kind = 'resolution'`, `body` the formatted text); the
  database accepts it up to 2400 characters, which covers 2000 characters of notes with any code label. Desk PATCH
  replay (R-F-03): the op's first event row has `client_uuid = op_uuid` and `op_sha256` = SHA-256 of the request body
  as canonical JSON (32 bytes; null on every other row; written once with the row).
- B1: `Inbox.add` inserts `app.user_notification` with `ON CONFLICT (user_id, dedupe_key) DO NOTHING` (R-F-04: unique
  per recipient); `ref_id` <= 64 chars, `dedupe_key` <= 160. The ticket handler inserts the state row with
  `requester_role` and `priority` (from attrs), the `created` event (kind created) and the domain event in one
  transaction; the ticket number comes from the default. A resolution update sets `resolved_at` with the status, and a
  close sets `closed_at` (R-F-08 CHECKs refuse either without it). `affected_username` is stored as sent; the resolved
  user goes to `support_ticket_state.affected_user_id` (write-once).
- B1: add `ticket_issue_type` and `ticket_resolution_code` to `AdminCalendar.TABLE_LISTS` so the admin GUI edits them
  (TK-21); the bundle sends them once F1's CodeListKey has them (BundleService filters unknown keys).
- B3: AutoFinalSubmitJob writes `via = 'job'` with `submitted_by` NULL (CHECK); the late-row code is
  `after_final_submit` (sync_quarantine) and the after-lock code `day_final_submitted` (sync_rejected).
- B3: the audit question is settled by R-F-07: the worker gets no INSERT on `app.audit_log`; the midnight job's trace
  is its `via = 'job'` final_submit row plus the `zone_day.final_submitted` event. The worker now has UPDATE on
  `app.push_token` (revoking an unregistered token). Inbox rows of the job: `ON CONFLICT (user_id, dedupe_key)`.
  `dw.agg_daily_zone.auto_submitted` keeps its V0099 comment ("app.final_submit is not written"); once B2/B3 retire the
  dw-only stamp, F2 rewords or deprecates it in the 1.10.x batch.
- B2: `dw.v_calendar` / `v_calendar_month` stay global with the Friday weekend (comment updated; DASH-04).
- Index follow-up (fleet size, not pilot): `app.call_assessment (visit_plan_outlet_client_uuid)` has no index;
  `dw.v_visit_plan_outlet` and VisitPlansApi read by it. An index on an existing table needs CONCURRENTLY (squawk);
  proposed for the final-account sizing batch.

## Verify

- `:db:test` through the shared wrapper (JDK 21, local PostgreSQL 16), at 33acd91d: **313 tests, 312 passed, 1 failed**
  (BUILD FAILED in 23m 24s). The one failure is `OwnerDirectiveV0087Test.v0101TheFirstFinalSubmitWins`: expected
  `2026-10-05 15:00:00+00`, got `2026-10-05 21:00:00+06`, the same instant printed in this laptop's Asia/Dhaka session
  time zone (the test casts a timestamptz to text; CI runs in UTC). Not caused by this lane: the same class run with
  `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` passes all 24 tests (BUILD SUCCESSFUL). Every other class is green,
  TsoManualV0130Test 13/13.
- `tools/ci/migrations-check.sh eb7b8eb5 <no-op squawk>`: "checksum: 121 shipped migration(s) at eb7b8eb checked;
  order: 11 new migration(s), highest shipped version 122; migrations: ok".
- Docs regenerated with the render.sh test set (`-Paron.writeDictionary=true --rerun-tasks`) through the wrapper.

## Not done

- squawk was not run locally: the pinned binary is Linux-only and no local copy exists; downloading one needs the
  owner's approval. The checksum and order parts of `tools/ci/migrations-check.sh` ran locally with a no-op squawk;
  the new files were reviewed by hand against the kept rules (lock_timeout first, NOT VALID then VALIDATE in the next
  file, no index on an existing table, `ban-drop-not-null` marked as in V0044, new-table indexes as in V0102). CI runs
  the real squawk.
- Backend, web and Android tests were not run (other lanes' verify); the new defaults (late-sync quarantine, reopen
  roles) and the extra feedback category reach them only through the bundle and config reads they already make.

## Fix round 2 (lead rulings)

Contract s9 rulings R-F-02..11 applied in commit 6165e271 (V0130..V0140 are unshipped, so the files were edited in
place; nothing at V0122 or lower was touched). An earlier fixer had left uncommitted edits (V0130 body CHECK at 2300,
the V0135 job-origin rule, test additions, dictionary, status lines). Reviewed: the origin rule and its registry/restamp
change were right and are kept; the 2300 limit was replaced by R-F-02's 2400; the status lines that the rulings settled
were reworded above (decisions 12, 13; asks to B1, B3, F1).

| ruling | what changed | test |
|---|---|---|
| R-F-02 | V0130 `support_ticket_event.body` CHECK `length(body) <= 2000 OR (kind = 'resolution' AND length(body) <= 2400)`; header and column comment say 2400 | `aResolutionWithTheLongestNotesAndLabelWritesItsMessage`: a resolution code with a 120-character label and 2000 characters of notes; the formatted D-TSO-MAN-20 event inserts (length 62 + ticket number + 120 + 2000, text checked in full); the theoretical longest (2204) and 2400 insert; 2401 refused; a comment keeps 2000 |
| R-F-03 | V0130 `op_sha256 bytea CHECK (op_sha256 IS NULL OR octet_length(op_sha256) = 32)`; write-once (the event row guard covers it); column comment states the desk PATCH replay rule | `aDeskOpStoresItsBodyHashOnItsFirstEventRowOnly`: hash on the op's first row (client_uuid = op_uuid), null on its second row and on a device comment; same body matches, other body does not; 31, 33 and 0 bytes refused (23514); update refused (42501) |
| R-F-04 | V0133 `UNIQUE (user_id, dedupe_key)` (constraint `user_notification_user_id_dedupe_key_key`) instead of `UNIQUE (dedupe_key)`; header and dedupe_key comment | `notificationsAreIdempotentAndReadOnce`: one key reaches two recipients; repeats for each recipient with `ON CONFLICT (user_id, dedupe_key) DO NOTHING` add nothing; a plain duplicate is 23505 |
| R-F-06 | V0134 `cfg.feedback.max_images` bounds `{"min": 0, "max": 1}` | bounds asserted in `theBuildsSettingsPermissionAndEventsAreRegistered` |
| R-F-07 | V0135 grant map row `worker_rw app.push_token UPDATE`; no audit grant | DbRolesTest map gains exactly `worker_rw app.push_token UPDATE = true` (its `worker_rw app.audit_log INSERT = false` stays); TsoManualV0130Test checks final_submit INSERT t, push_token UPDATE t, push_token INSERT f, audit_log INSERT f |
| R-F-08 | V0130 `support_ticket_state_closed CHECK ((status = 'closed') = (closed_at IS NOT NULL))`, `support_ticket_state_resolved_at CHECK (status NOT IN ('resolved','closed') OR resolved_at IS NOT NULL)`; resolved_at and closed_at comments | state test: resolving without resolved_at, closed_at on a new ticket, closing without closed_at and closed_at on a resolved ticket are each refused 23514 |
| R-F-09 | kept the round 1 rule (V0135 s4, decision 12). Checked: V0135's `CREATE OR REPLACE TRIGGER final_submit_stamp_origin` replaces V0116's trigger of the same name, so one stamp runs per insert and the two cannot fight; `final_submit_guard_origin` (BEFORE UPDATE, `app.guard_data_origin`) is untouched | `aJobFinalSubmitTakesTheOriginOfTheZonesRoutesAndKeepsItThroughARestamp`: the trigger list on app.final_submit is exactly the guard (BEFORE UPDATE) and the new stamp (BEFORE INSERT); a job submit for a zone whose routes are test is `test` (listed routes, or the zone's routes when none are listed, writer's value overwritten) and stays `test` after `app.restamp_data_origin()`; a user's submit keeps the submitter's origin (real TSO production, test TSO test); a second restamp changes nothing; re-tagged routes move only the job rows; api_rw cannot re-tag (42501) |
| R-F-10 | cross-lane edit of `backend/sync/.../BundleRubricsTest.kt`: new test `aFreshDatabaseSendsTheSeededVisitQueryToTheSupervisors` (the V0139 rubric alone, version 1, criteria 1 and 2, keys, text type, bn and en labels, SR gets none); the existing case sets the seeded row inactive first and asserts it is left out; every earlier assertion unchanged | `:backend:sync:test` (BundleRubricsTest 2/2) and the backend/app classes below |
| R-F-11 | the one database object that lists modules is the `app.user_module_grant.module` CHECK (V0089). V0135 s5 replaces it NOT VALID with `support` added; V0136 validates it; the column comment lists support. The V0089 seed INSERT (shipped) and the dev seed `db/seed/07_admin_module_grants.sql` also list the 15 modules: data, not CHECKs, so not changed (see asks) | `anAdminMayBeGrantedTheSupportModule` (support and all 15 others accepted, unknown modules 23514); `everyConstraintAddedNotValidIsValidated` includes `user_module_grant_module_check` |

Docs: `docs/data-dictionary.md` regenerated (render.sh test set through the wrapper, `-Paron.writeDictionary=true
--rerun-tasks`): op_sha256, derive_fn, the new comments and the inbox key. `docs/data-events.md` and
`docs/dw-view-contract.md` regenerated with no change.

### Verify (fix round 2)

All through the shared wrapper (JDK 21, local PostgreSQL 16, UTC JAVA_TOOL_OPTIONS), at 6165e271.

- `gl.sh <wt> :db:test` (full): **BUILD FAILED, 317 tests, 1 failed**: `OwnerDirectiveV0087Test.v0101TheFirstFinalSubmitWins`
  (expected `2026-10-05 15:00:00+00`, got `2026-10-05 21:00:00+06`, the same instant in Asia/Dhaka). The run printed
  "Configuration cache entry reused": the cached `:db:test` entry of this worktree predates the wrapper's UTC option, so
  the test JVM was still in Asia/Dhaka. Not caused by this lane (see the ask). Every other class SUCCESS.
- `gl.sh <wt> :db:test --no-configuration-cache` (full, rerun): **BUILD SUCCESSFUL in 12m**, 38 classes all SUCCESS,
  among them OwnerDirectiveV0087Test 24/24, TsoManualV0130Test 17/17, DbRolesTest 9/9, DataOriginTest 7/7,
  DataDictionaryTest 2/2.
- `gl.sh <wt> :backend:sync:test` (full): **BUILD FAILED, 37 tests, 1 failed**: `JcsTest.payloadShapesMatchTheContractSlices`
  starts `python3`, which is not on this laptop (Windows Store alias, exit 9009); not caused by this lane (F2 changes
  no backend/sync main code). `python backend/sync/tools/gen_payload_shapes.py --check` exits 0. BundleRubricsTest 2/2
  SUCCESS; BundleUnitTest, GnssRuleTest, MoneyFormatCorpusTest, RetentionJobTest, WorkingDaysTest SUCCESS.
- `gl.sh <wt> :backend:app:test` with `--tests` for every backend/app test class that touches rubrics, final_submit,
  user_notification, push_token, data_origin or restamp (grep of backend/app/src/test): ContractConformanceTest 6,
  DataFlowTraceTest 16, DataVoidAcceptanceTest 3, DayApiTest 6, DayCloseRaceTest 3, PushCheckerTest 10, PushTest 2,
  ScopeLeakAcrossEndpointsTest 7, SupervisorSyncFaultFuzzTest 3: **BUILD SUCCESSFUL**, all SUCCESS. No backend/app test
  reads user_notification or restamp today.
- Extra: `gl.sh <wt> :backend:masterdata:test --tests AdminContentTest --tests VisitPlansTest` (the other rubric
  readers): **BUILD SUCCESSFUL**, 9 and 4 tests.
- `bash tools/ci/migrations-check.sh eb7b8eb5 <no-op squawk>` at 6165e271: "checksum: 121 shipped migration(s) at
  eb7b8eb checked; order: 11 new migration(s), highest shipped version 122; migrations: ok" (exit 0). squawk itself
  runs in CI only (R-F-22); the new DDL follows the kept rules (lock_timeout first; the module CHECK added NOT VALID in
  V0135 and validated in V0136; no index on an existing table).

### Asks (fix round 2)

- Lead (gl.sh): a plain `gl.sh <wt> :db:test` in a worktree that already has a configuration cache entry for `:db:test`
  reuses it, and the cached Test task's environment predates the wrapper's `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`, so
  the test JVM still runs in Asia/Dhaka and `OwnerDirectiveV0087Test.v0101TheFirstFinalSubmitWins` fails as before.
  With `--no-configuration-cache` it passes. Suggest the wrapper passes `--no-configuration-cache` for test tasks (or
  deletes `<wt>/.gradle/configuration-cache` once), or the db test task sets `systemProperty("user.timezone", "UTC")`.
- B1 / W1 / lead: no ADMIN holds `support` yet. The shipped V0089 seed and the dev seed `07_admin_module_grants.sql`
  grant the 15 older modules only, so once B1 maps `/v1/support/tickets/**` to `support`, an ADMIN reaches the desk
  only after a SUPERADMIN grants it on the access page (SUPERADMIN is unaffected). If the lead wants seeded dev admins to
  hold it, F2 adds `('support')` to the dev seed and moves OwnerDirectiveV0087Test's `15|w` to `16|w` (two lines).
- Laptop only: `JcsTest.payloadShapesMatchTheContractSlices` runs `python3`, which this laptop does not have (Windows
  Store alias, exit 9009). The same check run with `python backend/sync/tools/gen_payload_shapes.py --check` exits 0.
