# tso-manual B1 backend-tickets (+ notifications inbox): lane status

Lane B1 of `docs/requests/tso-manual-build-2026-10.md` (s5 B1). Branch `tso-man/B1`, base 95a35b7d, no pull request,
nothing pushed. Written 2026-10-29 (step 1: the inbox; step 2: the support tickets).

## Done, step 2: support tickets (D-TSO-MAN-17..26, R-F-03, R-F-05, R-F-11, R-F-14; F1 asks 10, 14; F2 asks)

| file | what |
|---|---|
| `backend/masterdata/.../SupportTicketHandler.kt` (new) | RecordHandler for the sync records `support_ticket`, `support_ticket_event`. Check order: replay (ingest step 2, before any handler), then in `checkEarly` the requester role in `cfg.support.ticket_requester_roles` (else quarantined `scope_out_of_reach`), the issue type active on the business date (else rejected `issue_type_unknown`), for an event the ticket's owner, voided flag and state (else rejected `ticket_state_invalid`; a blank comment `schema_invalid`). `checkEarly` runs before ingest's parent check, so an event on another user's ticket is `ticket_state_invalid` (contract), not a scope hold; an event whose ticket is not stored is parked `parent_missing` (held with it while the ticket is quarantined), an event of a ticket refused for good is refused with it. `check` repeats the rules after the parent check on the locked state row. `afterStored`: ticket -> state row (requester, role, priority from attrs, affected user), `created` event (name-based uuid), `support_ticket.created`; comment -> `updated_at`, `support_ticket.commented`; close review -> status closed, `closed_at`, `closed_by`, version + 1, `support_ticket.status_changed`. R-F-05: see D-B1-08 |
| `backend/masterdata/.../SupportTicketService.kt` (new) | `SupportTickets` (desk permission, config reads on the caller's handle, stable v4 uuids, canonical JSON + SHA-256, the resolution message), `SupportTicketTexts` (bell texts, bn + en), `SupportTicketService`: list (filters, `created_at` desc then id desc, `(created_at, id)` cursor), summary (counts only, R-F-14), detail (thread oldest first, max 500; ticket photos max 3), desk update (D-TSO-MAN-20 matrix, R-F-03 replay, events, state, audit, inbox, domain event, push after commit), desk reply |
| `backend/masterdata/.../SupportTicketApi.kt` (new) | `supportTicketRoutes`: GET list, GET summary, GET detail, PATCH (403 desk, then 400 body: unknown member, types, bounds, at least one change, a resolve needs code and notes, a cancel needs a reason, neither member elsewhere), POST events (desk reply, 201 / 200) |
| `DomainEventProducer.kt` (append, companion) | `supportTicketCreated`, `supportTicketStatusChanged`, `supportTicketCommented`: the three V0134 v1 payloads, ids and codes only |
| `AdminCalendar.kt` (F2 ask, TK-21) | `TABLE_LISTS` + `ticket_issue_type`, `ticket_resolution_code` (the admin GUI edits them); `AdminMasterTest` expects 16 lists |
| `AdminModules.kt` (R-F-11, F1 ask 14; F1's companion commit had not added it) | `ALL` + `support`; `ROUTES`: `/v1/support/tickets/**` -> `support`, placed before `/v1/support/**` -> `mdm` (first match wins); `AdminModulesTest` sample pair and three mapping asserts |
| `AuthDtos.kt` / `AuthModule.kt` (the Me builder) | `Me.support_desk`: the permission `support.ticket.manage`, and module `support` for an ADMIN whose token carries modules |
| `MediaRead.kt` (TK-14) | a photo with `ref_type` `support_ticket` or `support_ticket_event`: its owner (the reporter) and the desk permission only |
| `Wiring.kt` (appends) | `SupportTicketHandler()` in `recordHandlers`; `supportTicketRoutes(SupportTicketDeps(db, config, push, guard, clock))` plus its import line |
| Tests (backend/app) | `TicketHarness.kt` (shared world), `SupportTicketTest.kt` (14 tests), `SupportTicketSyncFuzzTest.kt` (property test), below |
| Inventories (appends, marked `tso-manual B1`) | `scope-cases.txt` (5 routes -> SupportTicketTest cases), `ContractConformanceTest` FX (a seeded ticket of tso1001; PATCH and reply fixtures per caller), `AuthzAuditTest` (a seeded ticket and 5 RouteSpecs), `ScopeLeakAcrossEndpointsTest` (an LKX ticket the national desk sees; the other-path probes name it), `scope-blind-baseline.txt` (+ the ticket summary and the step-1 inbox read, both per-caller) |

TypeRules and PayloadShapes: F1's companion lines (`support_ticket`; `support_ticket_event` with parent `ticket_uuid`) are
right for the handler and were not changed.

## Done, step 1: the notification inbox (D-TSO-MAN-26, SH-08)

| file | what |
|---|---|
| `backend/platform/.../platform/Inbox.kt` (new; first commit, also `tso-man/B1-inbox`) | The fixed producer API of s5 B1: `Inbox.add(h: Handle, item: InboxItem): Boolean`. `InboxItem(userId, kind, refType, refId, titleEn, titleBn, bodyEn, bodyBn, dedupeKey)`. `INSERT ... ON CONFLICT (user_id, dedupe_key) DO NOTHING` (R-F-04) on the caller's handle; refuses to run outside a transaction. Returns true when a row was inserted (nudge that user after the commit). Also `Inbox.addAll`, `Inbox.PUSH_REASON` (`"notification"`), `Inbox.REF_TYPE_OF`, `Inbox.zoneDayRefId(zoneId, date)` (`<zone_id>:<business_date>`, D-F1-06) |
| `backend/notify/.../notify/InboxApi.kt` (new) | `inboxRoutes`: `GET /v1/me/notifications` and `POST /v1/me/notifications/read`, contract 1.10.0 DTOs. `InboxPruneJob` (worker) |
| `backend/notify/.../notify/Push.kt` | `PushReasons` (`TASK_ASSIGNED`, `NOTIFICATION` = `Inbox.PUSH_REASON`, `ALL`) |
| `backend/notify/build.gradle.kts` | test dependencies `:db` and the platform test fixtures, `ARON_TEST_PG_URL` |
| `backend/notify/src/test/.../notify/InboxTest.kt` (new) | 8 tests |
| Appends | `Wiring.kt` `inboxRoutes(...)` (+ import), `Main.kt` the `InboxPruneJob` line, `AdminModules.kt` OWN `* /v1/me/notifications/**`, `scope-cases.txt` the two routes -> `case InboxTest#ownRowsOnly` |

## Decisions (D-B1-NN)

Step 1 (unchanged): D-B1-01 `InboxItem` takes wire strings, Bangla stated explicitly; D-B1-02 `Inbox.add` returns
Boolean and requires a transaction; D-B1-03 the inbox is "own" for the ADMIN module gate; D-B1-04 any signed-in caller
reads its own inbox; D-B1-05 server DTOs are the generated contract classes; D-B1-06 prune by `created_at`.

Step 2:
- **D-B1-07 Push reason `notification`, not `ticket_updated`.** The contract's updateSupportTicket text says "one push with
  reason `ticket_updated`"; D-TSO-MAN-26 (s1, which precedes s3) and the s5 B1 fixed API say the bell's push reason is
  `notification`. The desk nudges `Inbox.PUSH_REASON` (`notification`) once per op or reply whose inbox row is new, after
  the commit, when `cfg.notify.ticket_push_enabled` is true. Ask 1 makes the contract text match.
- **D-B1-08 R-F-05 by the afterStored re-check.** An ADMIN accept releases the record with the quarantine code as an
  override, so ingest skips a handler refusal with that code (`scope_out_of_reach`). The handler cannot see the
  override, but only an accept can carry one: when a stored record's role is still not allowed, `afterStored` throws
  409 `ERR_REQUEST_STATE` ("the requester role ... is still not in cfg.support.ticket_requester_roles"); the accept
  rolls back whole and the item stays open. No new outcome code, no IngestService change. Once the role is allowed the
  accept stores the record and every other rule runs (`check` repeats the issue-type and state rules).
- **D-B1-09 Config read on the transaction's handle.** `cfg.support.ticket_requester_roles` and
  `cfg.notify.ticket_push_enabled` are read from `app.cfg_value`/`app.cfg_key` at the API clock on the handle in use (not
  the 30 s DbServerConfig cache), so a change holds for the next record or desk action, and the record handler needs no
  config object. Only SR, AMO and TSO count from the roles list (F2 decision 7: the reader enforces the item rule);
  an unreadable list means the default ["TSO"], an unreadable push switch means off.
- **D-B1-10 Desk event rows.** `via = 'web'`, `user_id` = actor, `family_uuid` = `client_uuid`, `captured_at` and
  `received_at` the API clock, `business_date` Dhaka today, `config_version` current, `created_at` the database default
  (as the sync writer's rows, so the thread orders by one clock). A cancel's `status_change` carries the cancel reason
  as its body (the reporter reads why); `priority_change` and `assign` carry no body (no untranslated text, the
  assignee is never named to the reporter). The ticket's `created` event is `via = 'device'` by the reporter with the
  ticket's envelope, uuid `StableIds.v4("aron.support.ticket-created:<ticket_uuid>")`.
- **D-B1-11 First desk reply on a New ticket.** It writes its own `status_change` (new -> in_progress, uuid
  `StableIds.v4("aron.support.ticket-reply:<event_uuid>:status_change")`) before the reply, stamps `first_response_at`,
  bumps the version and emits `support_ticket.status_changed`; a reply on an in_progress ticket changes no version.
  Start, resolve and cancel also stamp `first_response_at` once ("the first support action").
- **D-B1-12 A desk reply reaches the bell too.** The contract names the inbox row for updateSupportTicket only; a reply
  is the update a reporter most needs, so it gets one `ticket_updated` row (dedupe `ticket:<ticket_uuid>:<event_uuid>`)
  and the same push rule. Every row's texts are in bn and en (`SupportTicketTexts`).
- **D-B1-13 Close review statuses on read.** The synced `close_review` row has no `from_status`/`to_status` (the record
  carries none, and event rows are immutable), so the read states the move it made: resolved -> closed (a close review
  is accepted only on a resolved ticket and closes it).
- **D-B1-14 Assignee.** `assignee_user_id` must be an active user whose role holds `support.ticket.manage` now, else 400;
  `null` unassigns. A value equal to the current one is not a change.
- **D-B1-15 Order after the 404.** Replay, then `expected_version` (409), then the value checks that need the database
  (resolution code active today, the assignee; 400), then the transitions (409). Shape checks stay before the 404, as
  the contract orders them. A replay is therefore never refused for a code retired since the first send.
- **D-B1-16 Photos.** The detail's `attachments` and each event's are the media uuids of non-voided `app.media` rows of
  that ref (status not filtered: a pending photo is listed and its read URL answers 404 until stored), oldest first, at
  most 3 (the contract maxItems; ingest does not yet refuse a fourth, ask 3); `attachment_count` counts all of them.
- **D-B1-17 Ticket photos are private.** The media read URL of a `support_ticket*` photo is served to its owner and to the
  desk permission only, never to a supervisor by geography (the ticket itself is the reporter's and the desk's).
- **D-B1-18 `q`.** A ticket number prefix (upper-cased, so `inc1001` matches) or ILIKE on the title or the issue type's
  English label; `%`, `_` and `!` in `q` are literals (`ESCAPE '!'`: a backslash escape in a quoted literal made Jdbi's
  parser miss the next named parameter, found by the first test run).
- **D-B1-19 Me.support_desk for an ADMIN** also needs module `support` in the token (else the module gate refuses the
  routes, and the menu would lead to 403s).

## Deviations (files outside the s5 B1 Owns list)

- Step 1: `backend/notify/build.gradle.kts` (test dependencies), `Wiring.kt` import line.
- Step 2: `Wiring.kt` one more import line (`supportTicketRoutes`; a fully qualified extension call did not resolve).
- `AdminModules.kt`: three B1 lines in all (the step-1 OWN line, `support` appended to `ALL`, and the ROUTES line). The
  ROUTES line is inserted before `/v1/support/**` -> mdm instead of appended: the list is first-match, so an appended
  line would never match. `AdminModulesTest.kt` (platform tests) gets the sample pair R-F-11 names.
- `AdminCalendar.kt` (TABLE_LISTS append) and `AdminMasterTest.kt` (expected list count 14 -> 16): the F2 ask to B1.
- `MediaRead.kt`: the `ref_type` column in its row read plus the desk rule (s5 B1 "media read-url authz").
- `ModuleGrantsTest.kt` (backend/app): its SUPERADMIN `Me.modules` count follows `AdminModules.ALL` (15 -> 16), the
  R-F-11 consequence the full Verify found.
- `scope-blind-baseline.txt`: two appended lines, the ticket summary read and the step-1 inbox read (both per-caller; the harness cannot see a
  division in them; the inbox line was missing since step 1 and found by this step's run). Its header says the list may only shrink; the test's own message asks a new blind read for "other-division
  data or a reason", which is what the line gives.

## Asks

1. **F1 (1.10.x):** updateSupportTicket's description says the push reason is `ticket_updated`; D-TSO-MAN-26 and s5 B1 fix
   `notification` (D-B1-07). Reword the description. Also mention that a desk reply writes an inbox row (D-B1-12).
2. **F2 (V0141+):** set `support_ticket.created`, `support_ticket.status_changed`, `support_ticket.commented` to `live` in
   `app.domain_event_type` (V0117 rule: live once a producer ships) and move them in `DataEventsTest`; B1 ships the
   producers here. Until then the catalogue says planned while rows are written.
3. **Lead / F1 / A2:** D-TSO-MAN-23 caps photos at `cfg.support.ticket_max_photos` per ticket and per event, but no
   server rule refuses a fourth `media_meta` for a ticket ref yet (the media_meta record is not a B1 type; the read caps
   the lists at 3, D-B1-16). Either B1 adds a `media_meta` handler in 1.10.x with an outcome code the lead names, or the
   phone's cap (A2) is the rule. The server cannot refuse it without a code.
4. **W1:** `GRANT_MODULES` + `support` (R-F-11); the desk reads `assignee_user_id`/`assignee_name` (absent for a
   reporter) and sends `assignee_user_id` only for desk users (D-B1-14).

Foundation asks addressed to B1, all done in this step: F1 ask 10 (R-F-03 replay rules, SupportTicketTest), F1 ask 14
(AdminModules support), F1 D-F1 note (TypeRules/PayloadShapes checked, unchanged), F2 resolution message, state row,
created event and domain event in one transaction, `resolved_at`/`closed_at` with the status (R-F-08), affected user;
F2 AdminCalendar.TABLE_LISTS; R-F-05.

## Tests (step 2)

`SupportTicketTest` (backend/app, production wiring, fresh migrated database with db/seed, recording push sender):
- `aTicketFromThePhoneGetsItsNumberStateCreatedEventAndDomainEvent`: INC + 6 digits from 100001, unique and in order;
  state row (requester, TSO, p1 from attrs, new, version 1, affected user resolved ignoring case); one created event;
  `support_ticket.created` with exactly its schema's members; the reporter's detail (labels, desk-only members absent).
- `replayIsDuplicateAndAnotherPayloadUnderTheSameUuidIsQuarantined`: duplicate on resend, `payload_conflict` for the same
  uuid with another payload, nothing doubled (ticket, state, events, domain events), same for a comment.
- `anUnknownIssueTypeIsRejectedAndTheEventsOfARefusedTicketWithIt`: `issue_type_unknown`, its comment
  `ticket_state_invalid`, a retired type refused from its `valid_to`.
- `aRoleOutsideTheRequesterRolesIsQuarantinedAndTheAcceptReChecksTheRole` (R-F-05): SR ticket and comment quarantined;
  the accept is 409 `ERR_REQUEST_STATE`, the item stays open, nothing stored, the resend still held; with SR allowed the
  accept stores it (state, domain event), the phone's copy is a duplicate, the comment's own accept stores it.
- `anEventBeforeItsTicketIsParkedThenAccepted`: parked `parent_missing`, accepted after the ticket; in one batch the order
  sent decides.
- `reporterSeesOwnTicketsOnlyAndTheDeskSeesAll`: own list on phone and web, 404 for another reporter's ticket and for
  SUPPORT without the permission, 403 for the desk-only filters, the desk sees all and filters by requester, `q`
  (number prefix any case, title, issue label, literal `%`), status/priority/issue/date filters, eight 400s, cursor
  paging walks the whole list, writes 403 for a reporter and SUPPORT, 404 for an unknown ticket, `Me.support_desk`.
- `ticketPhotosAreReadByTheReporterAndTheDeskOnly`: attachments and count; read URL 200 for the reporter and the desk,
  403 for the DMO above the TSO, another TSO and SUPPORT.
- `theFullTransitionMatrix`: all 20 desk (from, to) pairs (6 allowed, 14 refused with 409
  `ERR_TICKET_TRANSITION_INVALID` and nothing written), timestamps, versions and event rows; the reporter's comment and
  close review in all 5 states.
- `aResolveWritesTheSupportMessageAndNeedsItsCodeAndNotes`: 16 malformed bodies are 400 and write nothing; the
  D-TSO-MAN-20 message exactly; "Support" as the actor; 2000 characters of notes fit.
- `theDeskOpReplaysByItsUuidAndFingerprint` (R-F-03, F1 ask 10): a three-change op writes three events (op_uuid with the
  SHA-256 of the canonical body, then the two derived uuids), a reordered and respaced replay after the version moved
  answers 200 and writes nothing, the op_uuid with another body / on another ticket / by another desk user / equal to a
  reply's uuid is 409 `ERR_CONFLICT`, a no-change op records nothing and leaves its uuid free, a stale version is 409,
  `null` unassigns, three audit rows for three applied ops.
- `commentsCloseAfterResolveOnBothPaths` (CR-8): the first reply moves New to in_progress with its own status change, a
  reply replays (200) and its uuid elsewhere is 409; after resolve the phone's comment is `ticket_state_invalid` and the
  desk's reply 409 `ERR_TICKET_COMMENTS_CLOSED`, while the old reply still replays; cancelled tickets the same; blank
  phone comment `schema_invalid`; reply body 400s.
- `closeRules`: close review from new refused, by another reporter refused, from resolved accepted once (state, event
  statuses on read, `support_ticket.status_changed`), duplicate on resend, a second one refused, the desk cannot close a
  closed ticket, the desk may close a resolved ticket.
- `summaryCountsTheCallersTicketsAndTheDeskCountsAll`: counts per status and open, counts only (R-F-14), `as_of`; the
  desk total; the s4.4 percentages from counts via TicketMath (18/723 -> 2, total 0 -> 0).
- `aDeskUpdateReachesTheReportersBellOnceAndPushesWhenSwitchedOn`: one inbox row (kind, ref, dedupe key, bn and en title),
  one data-only push `reason=notification`, the bell lists it; a replay adds no row and no push; with
  `cfg.notify.ticket_push_enabled` false a reply writes its row and sends no push.

`SupportTicketSyncFuzzTest.duplicateAndOutOfOrderUploadsNeverDoubleAndConverge`: 12 seeded schedules (env
`ARON_TICKET_FUZZ_RUNS`, `ARON_TICKET_FUZZ_SEED`, default 20261029) of random batches (any subset, any order, resends)
with desk replies (replayed) and resolves at random points, then a drain and a full resend. Per schedule: no double
(ticket, state, created event, domain event once; ticket number never changes; device count == server count), stable
answers (accepted then duplicate forever; a final refusal repeats; nothing left parked), the rules in the server's
order (no comment after the resolution, at most one close review and only after it, final status), and the outbox
event and version counts.

## Verify (this worktree, local laptop, 2026-10-29; CI not run, nothing pushed)

All Gradle runs through the shared wrapper `gl.sh` (JDK 21, UTC, local PostgreSQL 16). The laptop's Gradle lock is
shared by every lane; waits of up to 90 minutes were normal in this step.

| Command | Result |
|---|---|
| `gl.sh <wt> :backend:app:compileTestKotlin :backend:platform:compileTestKotlin :backend:masterdata:compileTestKotlin` (first try with `:backend:app:compileKotlin`) | first try: masterdata compiled, `Wiring.kt` failed (a fully qualified extension call to `supportTicketRoutes` does not resolve; fixed with an import line); second try BUILD SUCCESSFUL |
| `gl.sh <wt> :backend:app:test --tests '*SupportTicketTest' --tests '*SupportTicketSyncFuzzTest' :backend:platform:test --tests '*AdminModulesTest' :backend:masterdata:test --tests '*AdminMasterTest' --tests '*UserAccessTest' --continue` | BUILD FAILED: SupportTicketSyncFuzzTest 1/1, AdminModulesTest 12/12, AdminMasterTest 9/9, UserAccessTest 8/8 green; SupportTicketTest 12/14. The two failures were real: the list's `q` filter answered 500 (a `'\'` LIKE escape made Jdbi's parser skip the next named parameter; fixed with `ESCAPE '!'`, D-B1-18) and a test bug (the R-F-05 test rebuilt the record with a new `captured_at`, so the resend was a `payload_conflict`) |
| `gl.sh <wt> :backend:app:test --tests '*SupportTicketTest'` | BUILD SUCCESSFUL, 14/14 |
| `gl.sh <wt> :backend:app:test --tests '*RouteInventoryTest' --tests '*ContractConformanceTest' --tests '*AuthzAuditTest' --tests '*ScopeLeakAcrossEndpointsTest' --continue` | RouteInventoryTest 9/9, ContractConformanceTest 6/6, AuthzAuditTest 4/4; ScopeLeakAcrossEndpointsTest 6/7: `GET /v1/me/notifications` (the step-1 inbox read, never run through this harness in step 1) is blind for the national control. Fixed by its per-caller line in `scope-blind-baseline.txt` |
| **Final Verify:** `gl.sh <wt> :backend:masterdata:test :backend:notify:test :backend:platform:test :backend:sync:test :backend:app:test --continue` | BUILD FAILED in 2h 43m (the laptop was shared with other lanes' builds; app classes ran 10-50x slower than alone). platform 93/93, notify 8/8, masterdata 241/242, sync 36/37, app 477/479 (2 skipped). The four failures, each examined: (1) masterdata `AuditWriteRedactionTest.noAuditWriteBypassesThePlatformWriter`: expected `backend/platform/.../AuditLog.kt`, got the same path with backslashes (a Windows separator in the test's file scan; the only writer is still AuditLog.kt; not this lane's code). (2) sync `JcsTest.payloadShapesMatchTheContractSlices`: the test runs `python3`, which resolved to the WindowsApps alias inside the Gradle test JVM (exit 9009, "Python was not found"); `python backend/sync/tools/gen_payload_shapes.py --check` run by hand exits 0, and this lane changes nothing under backend/sync (F1 reported the same laptop failure). (3) app `ModuleGrantsTest.superadminHoldsEveryModuleAndBrandCarriesItsBrands`: expected 15 modules, got 16, a real consequence of R-F-11 (`support`); the test now expects `AdminModules.ALL` (16). (4) app `SyncConvergenceFuzzTest`: `UncompletedCoroutinesError` after its 1-minute `testApplication` timeout under the load (TasksTest took 132 s, SyncGenerationTest 229 s in the same run); it touches no ticket record |
| `gl.sh <wt> :backend:app:test --tests '*ModuleGrantsTest' --tests '*SyncConvergenceFuzzTest' --tests '*ScopeLeakAcrossEndpointsTest' --continue` (after the ModuleGrantsTest fix) | **BUILD SUCCESSFUL in 6m 36s**: ModuleGrantsTest 2/2, SyncConvergenceFuzzTest 1/1 (108 s), ScopeLeakAcrossEndpointsTest 7/7 |
| In the final Verify run, the lane's own classes | SupportTicketTest 14/14, SupportTicketSyncFuzzTest 1/1, InboxTest 8/8, AdminModulesTest 12/12, AdminMasterTest 9/9, UserAccessTest 8/8, RouteInventoryTest 9/9, ContractConformanceTest 6/6 (report: 229 operations, 229 with a 2xx, the five ticket operations included), AuthzAuditTest 4/4 (50 ticket probes: list and summary served to all ten roles, detail 200 to TSO (the reporter), ADMIN, SUPERADMIN and 404 to the rest, PATCH and reply 2xx to ADMIN and SUPERADMIN and 403 to the rest; no finding or question), ScopeLeakAcrossEndpointsTest 7/7 |
| `python tools/ci/date-pin-scan.py .` | "106 literal(s) in seed-dependent tests, 106 baselined, 0 new" |
| `python tools/ci/wallclock-scan.py .` | 12 offenders in 4 modules, the same 12 as before; none in the new tests (the harness waits with `System.nanoTime`, the API clock is fixed) |
| `python tools/ci/money-float-scan.py .` | "17/17 allowed (0 open finding(s))" |

Not run: CI (nothing pushed). The full run's three non-B1 laptop failures (AuditWriteRedactionTest, JcsTest, the
load timeout of SyncConvergenceFuzzTest) need the Linux runner to show green; the last was shown green alone here.

Step 1 runs (kept): `:backend:platform:test :backend:notify:test --tests '*Inbox*' :backend:app:test --tests
'*RouteInventoryTest*' --tests '*ContractConformanceTest*'` BUILD SUCCESSFUL (InboxTest 8/8, platform 93/93,
RouteInventoryTest 9/9, ContractConformanceTest 6/6); `date-pin-scan` 0 new; `wallclock-scan` no new offender.

## Golden screenshots

None: B1 is a backend lane (no screen).

## Next step

Step 2 is complete; nothing of s5 B1 is left open in this worktree. For the lead and the next agent on this lane:
- Merge order (s6): B1 first in the wave. The lane touches the hot files only by marked appends (Wiring.kt routes and
  `recordHandlers`, AdminModules.kt, the Me builder, MediaRead.kt, AdminCalendar.TABLE_LISTS, DomainEventProducer,
  scope-cases.txt, scope-blind-baseline.txt, the three inventory tests) plus the test expectation edits listed under
  Deviations (AdminMasterTest 16 lists, ModuleGrantsTest 16 modules, AdminModulesTest sample).
- Asks 1 to 4 above go to F1, F2, the lead and W1 (the 1.10.x / V0141+ batch).
- B3 producers: `Inbox.add(h, InboxItem(...))` in the deciding transaction, then `nudger.nudge(user, Inbox.PUSH_REASON)`
  after the commit; the desk's own use is in `SupportTicketService.update` and `reply` as a model.
- A2 reads tickets with `GET /v1/support/tickets` (own only), `/summary` (counts; whole percent via TicketMath) and
  `/{ticket_uuid}`; a close review on a ticket that is not resolved, and any comment once it left new/in_progress, come
  back `rejected ticket_state_invalid` (final): show "This ticket is resolved. No further comments can be added."
- If CI shows `SyncConvergenceFuzzTest` timing out again, it is load (its 1-minute `testApplication` bound), not tickets.
