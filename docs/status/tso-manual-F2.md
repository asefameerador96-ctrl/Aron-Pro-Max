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

## Asks and notes for other lanes

- B1: `Inbox.add` inserts `app.user_notification` with `ON CONFLICT (dedupe_key) DO NOTHING`; `ref_id` <= 64 chars,
  `dedupe_key` <= 160. The ticket handler inserts the state row with `requester_role` and `priority` (from attrs), the
  `created` event (kind created) and the domain event in one transaction; the ticket number comes from the default.
  `affected_username` is stored as sent; the resolved user goes to `support_ticket_state.affected_user_id` (write-once).
- B1: add `ticket_issue_type` and `ticket_resolution_code` to `AdminCalendar.TABLE_LISTS` so the admin GUI edits them
  (TK-21); the bundle sends them once F1's CodeListKey has them (BundleService filters unknown keys).
- B3: AutoFinalSubmitJob writes `via = 'job'` with `submitted_by` NULL (CHECK); the late-row code is
  `after_final_submit` (sync_quarantine) and the after-lock code `day_final_submitted` (sync_rejected).
- B3 / lead: D-TSO-MAN-30 says "audit via job with actor system". Under per-app logins the worker (`worker_rw`) cannot
  insert `app.audit_log` (DbRolesTest keeps it closed); today the worker signs in as the admin login, so it works on
  dev. Options for the 1.10.x batch: keep the job's trace in the via=job row plus the `zone_day.final_submitted`
  event (no grant), or the lead rules a narrow grant (then DbRolesTest's expected map changes with it). F2 did not
  open the grant on its own.
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
