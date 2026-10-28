# tso-manual B1 backend-tickets (+ notifications inbox): lane status

Lane B1 of `docs/requests/tso-manual-build-2026-10.md` (s5 B1). Branch `tso-man/B1`, base 95a35b7d, no pull request,
nothing pushed. Written 2026-10-29 (step 1: the inbox).

## Done (step 1: the notification inbox, D-TSO-MAN-26, SH-08)

| file | what |
|---|---|
| `backend/platform/.../platform/Inbox.kt` (new; first commit, also `tso-man/B1-inbox`) | The fixed producer API of s5 B1: `Inbox.add(h: Handle, item: InboxItem): Boolean`. `InboxItem(userId, kind, refType, refId, titleEn, titleBn, bodyEn, bodyBn, dedupeKey)`. `INSERT ... ON CONFLICT (user_id, dedupe_key) DO NOTHING` (R-F-04) on the caller's handle; refuses to run outside a transaction. Returns true when a row was inserted (nudge that user after the commit). Also `Inbox.addAll` (returns the recipients with a new row), `Inbox.PUSH_REASON` (`"notification"`), `Inbox.REF_TYPE_OF`, `Inbox.zoneDayRefId(zoneId, date)` (`<zone_id>:<business_date>`, D-F1-06) |
| `backend/notify/.../notify/InboxApi.kt` (new) | `inboxRoutes`: `GET /v1/me/notifications` (listMyNotifications) and `POST /v1/me/notifications/read` (markMyNotificationsRead), per contract 1.10.0, answered with the generated contract DTOs. `InboxPruneJob` (worker) |
| `backend/notify/.../notify/Push.kt` | `PushReasons` (`TASK_ASSIGNED`, `NOTIFICATION` = `Inbox.PUSH_REASON`, `ALL`); the task gate reads the constant |
| `backend/notify/build.gradle.kts` | test dependencies `:db` and the platform test fixtures (FreshDb), `ARON_TEST_PG_URL` for the test task (as masterdata) |
| `backend/notify/src/test/.../notify/InboxTest.kt` (new) | 8 tests, below |
| Appends (hot files, marked `tso-manual B1`) | `Wiring.kt` routes block: `inboxRoutes(InboxDeps(db, guard, clock))` (plus its import line); `Main.kt`: the `InboxPruneJob` line; `AdminModules.kt` OWN: `* /v1/me/notifications/**`; `scope-cases.txt`: the two routes -> `case InboxTest#ownRowsOnly` |

How the pieces fit:
- A producer (B1's desk PATCH later, B3's leave decision and midnight auto Final Submit) calls `Inbox.add` in the
  transaction that makes the change, then, after the commit, `nudger.nudge(userId, Inbox.PUSH_REASON)` for each user
  `add` returned true for. The desk applies `cfg.notify.ticket_push_enabled` before nudging; the row is written either
  way. `PushNotifier.nudge` keeps `cfg.ops.push_enabled` for every reason.
- `GET /v1/me/notifications`: own rows only (token user), newest first by id, `before_id` paging with `next_before_id`
  (the last id of a full page when an older row exists, else null), `limit` 1..100 (50), `unread_count` over every
  unread row. Page and count come from one SQL statement (one snapshot). `as_of` from the server clock.
- `POST /v1/me/notifications/read`: `UPDATE ... SET read_at = now WHERE user_id = me AND id <= up_to_id AND read_at IS
  NULL`, then the unread count, in one transaction. A repeat or a lower id changes nothing and answers the same count;
  `read_at` keeps the first time (V0133 writes it once).
- `InboxPruneJob.runOnce()`: deletes rows with `created_at` older than `cfg.notify.inbox_retention_days` (read through
  DbServerConfig; clamped to the registry bounds 30..365; 90 when unreadable) in chunks of 5,000, each in its own
  transaction with a 60 s statement timeout. Every 6 hours in the worker (first run 11 minutes after start). Only the
  worker's role can delete (V0133 grants `worker_rw` DELETE; `jobs_rw` inherits it; `api_rw` has none).

## Decisions (D-B1-NN)

- **D-B1-01 `InboxItem` types.** `kind` and `refType` are the wire strings (not the Kotlin enum), checked in `init`
  against the contract's `NotificationKind` and the fixed pairs ticket_updated/support_ticket,
  leave_decided/leave_application, final_submit_auto/zone_day. `titleBn` and `bodyBn` are `String?` without a default
  (a producer states Bangla explicitly, constraint 8). The V0133 bounds (ref_id 1..64, title_en 1..120, title_bn <= 120,
  body_en <= 300, body_bn <= 300, dedupe_key 1..160, no U+0000) are checked in the producer. Reason: B3 codes against the
  s5 signature at once; strings are the least surprising type for the named members, and a producer bug fails with a
  clear message instead of a 23514.
- **D-B1-02 `Inbox.add` returns Boolean and requires a transaction.** s5 fixes the parameters, not the result: true
  means a new row, so the caller nudges only users that got one (a retried producer does not push twice). `add`
  throws `IllegalStateException` on a handle outside a transaction, so "in the caller's transaction" (R-F-04) cannot be
  skipped by accident.
- **D-B1-03 The inbox is "own" for the ADMIN module gate.** `* /v1/me/notifications/**` is in `AdminModules.OWN` (the
  caller's own data, like `GET /v1/me`), so RouteInventoryTest's module check holds and an ADMIN gets its own (empty)
  inbox, not a 403. It is a second B1 line in AdminModules.kt (s5 names one, for `support`, which comes with the
  ticket routes).
- **D-B1-04 Any signed-in caller reads its own inbox.** The contract names no role; the producers address TSOs today,
  but an SR or AMO phone (or a web user) gets its own rows (none today), never another user's. A NATIONAL web token's
  POST `.../read` is refused 403 by PortalLevels (national users write only their session); not changed, because
  PortalLevels.kt is not a B1 file and no producer writes rows for NATIONAL.
- **D-B1-05 Server DTOs are the generated contract classes** (`NotificationInbox`, `NotificationItem`,
  `NotificationReadRequest`, `NotificationReadResult` from WireDtos.kt) instead of hand-written server copies: their
  members are drift-tested against the contract (WireDtosDriftTest) and `ResponseJson` sends the required-nullable
  members as explicit nulls.
- **D-B1-06 Prune by `created_at`**, read or unread, in id order (the oldest ids first); no new index (a prune scan per
  6 hours is cheap at pilot size; a `created_at` index is an F2 item if the fleet table grows, noted for 1.10.x/V0141+).

## Deviations (files outside the s5 B1 Owns list)

- `backend/notify/build.gradle.kts`: test dependencies and the test environment for InboxTest (the module had no tests
  and no database fixture; s5 places InboxTest in the lane and the Verify runs `:backend:notify:test`).
- `Wiring.kt`: one import line next to the existing notify imports (needed for the appended route line).

## Asks

None from step 1.

Open asks addressed to B1 by the foundation lanes (to do in the next steps, all ticket-side):
- F1 ask 10 (R-F-03): `updateSupportTicket` replay rules (op_uuid, `op_sha256` on the op's first event row, derived
  event UUIDs `aron.support.ticket-op:<op_uuid>:<kind>`, no-change op writes nothing).
- F1 ask 14 / R-F-11: `AdminModules.ALL` + `support`, `ROUTES` maps `/v1/support/tickets/**` to `support` before
  `/v1/support/**` -> mdm, an AdminModulesTest sample pair (UserAccessTest follows ALL).
- F1 D-F1 note: backend/sync TypeRules lines for `support_ticket` / `support_ticket_event` (parent `ticket_uuid`) and
  PayloadShapes are already in (F1 companion commit); B1 writes only the handler, and changes those lines if needed.
- F2: resolution message exactly as D-TSO-MAN-20 (`kind = 'resolution'`, up to 2400 characters); state row with
  `requester_role` and `priority` from attrs, the `created` event and the domain event in one transaction; set
  `resolved_at` / `closed_at` with the status (R-F-08); `affected_username` stored as sent, resolved user in
  `support_ticket_state.affected_user_id`.
- F2: add `ticket_issue_type` and `ticket_resolution_code` to `AdminCalendar.TABLE_LISTS` (TK-21).
- R-F-05: re-run the requester-role check when an ADMIN accepts a quarantined `support_ticket` / `support_ticket_event`.

## Verify (this worktree, local laptop, 2026-10-29; CI not run, nothing pushed)

All Gradle runs through the shared wrapper `gl.sh` (JDK 21, UTC, local PostgreSQL 16).

| Command | Result |
|---|---|
| `gl.sh <wt> :backend:platform:compileKotlin` (Inbox.kt alone, before the first commit) | BUILD SUCCESSFUL |
| `gl.sh <wt> :backend:notify:test --tests '*Inbox*' :backend:app:compileKotlin --no-configuration-cache` (first run) | BUILD FAILED: InboxTest 8 tests, 2 failed, both test-harness bugs (the shared test clock was not reset between tests, and the tokens expired when a test moved the clock 2 h). Fixed: `@BeforeEach` resets the clock, tokens live 24 h. `:backend:app:compileKotlin` ran and succeeded in this run |
| `gl.sh <wt> :backend:platform:test :backend:notify:test --tests '*Inbox*' :backend:app:test --tests '*RouteInventoryTest*' --tests '*ContractConformanceTest*' --no-configuration-cache --continue` | **BUILD SUCCESSFUL in 6m 26s**. InboxTest 8/8; `:backend:platform:test` 93 tests in 14 classes, 0 failures (AdminModulesTest included); RouteInventoryTest 9/9 (the two routes guarded, 401 without a token, scope lines, ADMIN module "own"); ContractConformanceTest 6/6 (report: 224 operations, 224 with a 2xx, so both inbox operations answered 2xx and validated against the contract) |
| `python tools/ci/date-pin-scan.py .` | "106 literal(s) in seed-dependent tests, 106 baselined, 0 new" |
| `python tools/ci/wallclock-scan.py .` | 12 offenders in 4 modules, the same 12 without this lane's changes; none in backend/notify (the test reads no wall clock: tokens are minted and verified at a fixed test clock, the prune uses the database's `now()`) |

InboxTest (backend/notify/src/test/.../InboxTest.kt), against a fresh migrated database:
- `producersAreIdempotentPerRecipient`: same key twice in one transaction -> one row; a retried producer with other text
  -> nothing changes; the same key to three users -> one row each, `addAll` returns only the new recipients; a rolled
  back producer leaves no row; `add` outside a transaction is refused; `PUSH_REASON` = `PushReasons.NOTIFICATION`.
- `aMalformedItemFailsInTheProducer`: every V0133 bound and the kind/ref_type pairs; the longest legal values insert.
- `unreadCountAndPaging`: empty inbox; 7 rows paged 3/3/1 by `before_id`, `next_before_id` null on the last (and on an
  exact last) page; unread_count over all rows; newest first; Bangla null rows; explicit JSON nulls; RFC 3339 ms `Z`
  timestamps; 400 for limit 0/101/x and before_id 0/-4/abc; 401 without a token.
- `readUpToIdIsIdempotent` (TSO phone token): read up to the third newest -> 2 unread; a repeat an hour later and a
  lower id -> same count, `read_at` keeps the first time; `Long.MAX_VALUE` -> 0, twice; a new row is unread again;
  400 for 0, -1, {}, true, an extra member, 1.5; a phone call without X-Device-Id -> 401.
- `ownRowsOnly`: two TSOs and an SR phone; each sees and counts only its rows; paging from another user's id and a
  `user_id` query parameter change nothing; reading up to the other user's highest id marks none of that user's rows.
- `pruneRunsAsTheWorkerAndKeepsTheWindow`: rows 400/100/91/89/31/29/1 days old, read and unread; as a `worker_rw`
  login: DbServerConfig reads 90 from the database, the job deletes 3 in two chunks of 2, a second run 0, a configured
  5 is clamped to 30 (deletes 89 and 31), 1000 to 365; the worker may insert. As an `api_rw` login: insert and
  mark-read work, the prune fails with 42501 (permission denied) and deletes nothing.
- `theInboxIsTheCallersOwnForTheAdminModuleGate`, `theKindsAreTheContractsAndEachTakesItsRefType`.

Not run: the full `:backend:app:test` (s5 B1 Verify lists it for the whole lane; it is due after the ticket step) and
`:backend:notify:test` without the filter (InboxTest is the module's only test class, so the filtered run is the
whole module).

## Next step

Step 2 (tickets), in s5 B1 order: SupportTicketHandler (sync records `support_ticket`, `support_ticket_event`; check
order replay, role, issue type, ownership/state), SupportTicketService and SupportTicketApi (list, summary, detail,
desk PATCH with R-F-03 replay, desk replies), Me.support_desk, DomainEventProducer types, media read authz for the
desk, AdminModules `support` line, TypeRules/PayloadShapes check, AdminCalendar.TABLE_LISTS, the Wiring
`supportTicketRoutes` + `SupportTicketHandler` lines, entries in ScopeLeakAcrossEndpointsTest, ContractConformanceTest,
AuthzAuditTest and RouteInventoryTest, SupportTicketTest and SupportTicketSyncFuzzTest. The desk PATCH writes its inbox
row with `Inbox.add(h, InboxItem(kind = "ticket_updated", refType = "support_ticket", refId = ticket_uuid, dedupeKey =
"ticket:<ticket_uuid>:<event_uuid>", ...))` and nudges `PushReasons.NOTIFICATION` after the commit when
`cfg.notify.ticket_push_enabled`.
