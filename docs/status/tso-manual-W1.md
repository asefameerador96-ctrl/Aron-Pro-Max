# tso-manual W1 web: lane status

Lane W1 of `docs/requests/tso-manual-build-2026-10.md` (s5 W1). Branch `tso-man/W1`, base 95a35b7d, no pull request,
nothing pushed. Written 2026-10-29: step 1 (support console and numbers) and step 2 (day, drill, leave, feedback, reports).
Both halves of s5 W1 are built; "Next step" lists what is left (asks only).

## Done (step 1)

| item | what | files |
|---|---|---|
| D-TSO-MAN-10, R-F-13, R-F-14 (numbers) | `fmt.ts` gains the integer-only TSO formatters, BigInt inside, one rounding per figure: `pctWhole`, `pctTenths`, `pctBpOf`, `pctText`, `qtyText` (K from 1,000.0, rounded once from n), `tillDate`, `remaining`, `band`, `cardPct`, `targetCard` (item rows summed, R-F-01), `dailyRingTenths` / `salesCard` (W = 0 prints "—", R-F-13), `ticketCounts` (whole % once from the counts, 0% at total 0, R-F-14), `ticketAge`; `DASH`, `K_SUFFIX`, `localDigits`. `pctBp` now prints through `pctText` (Western grouping in bn, as the corpus). | `web/src/lib/national/fmt.ts` |
| corpus test (F3 ask to W1) | every entry of `contract/fixtures/kpi-format-corpus.json` in en and bn: percent, qty, sales_card, tilldate, target_card, details, remaining, band, ticket_counts, ticket_age; plus named checks (W = 0 dash, A null dash, K boundary, Bengali digits, 29/1186 = 2.4%, bad input = dash). A new corpus section fails the test until it is checked or listed. | `web/tests/kpi-format-corpus.test.ts` |
| R-F-11 (F1 ask 14, W1 part) | `GRANT_MODULES` + `support` (16) with `grant.module.support` bn and en; module map `/v1/support/tickets/**` -> support ahead of `/v1/support/**` -> mdm (B1's AdminModules line), page `/admin/tickets` -> support; `user-access.test.ts` 15 -> 16. | `web/src/lib/admin/access-grants.ts`, `modules.ts`, `web/tests/user-access.test.ts` |
| TK-16 menu (D-TSO-MAN-22) | "Tickets" entry in ADMIN_MENU (portal roles); `grantsOf(role, modules, supportDesk)` withholds module support when `Me.support_desk` is not true, so the shell shows the entry only to the desk (an ADMIN also needs the module grant); a failed /v1/me hides it. | `web/src/app/admin/menu.ts`, `modules.ts`, `web/src/lib/menu/matrix.ts` (one tagged line, see deviations) |
| TK-16 console | `/admin/tickets`: counts (Total, New, In Progress, Resolved, Closed, Cancelled), Ticket Resolution Status (Resolved % and Open % from the counts), status tabs with counts, search (2..40) and priority / issue type / date filters in the URL, cursor paging, rows "INC… · issue", title, requester, priority and status chips, age, updated. `/admin/tickets/[ticket_uuid]`: "INC… · Tickets", title, chips, age, facts, description, thread (desk rows always "Support"/"সাপোর্ট", never a name; resolution message with its line breaks), closed note ("This ticket is resolved. No further comments can be added."), desk panel. Only `Me.support_desk` opens the pages (Forbidden otherwise). | `web/src/app/admin/tickets/page.tsx`, `[ticket_uuid]/page.tsx`, `web/src/components/admin/tickets/*` |
| TK-16 desk actions | Reply (1..2000, `event_uuid`), Start, Resolve (code from `ticket_resolution_code` AND notes 1..2000, no code preselected), Cancel (reason 3..500, confirm), Close (resolved only), Priority (open only). Ops `ticket.update` (PATCH) and `ticket.event` (POST) in the BFF whitelist; every update sends `op_uuid` and `expected_version`; each action keeps its key until the server answers (a lost answer or 5xx retries with the same key). 409 ERR_CONFLICT shows "Someone changed this ticket…" with Reload; other refusals show their problem text (ERR_TICKET_TRANSITION_INVALID, ERR_TICKET_COMMENTS_CLOSED texts added). | `ticket-rules.ts`, `use-ticket-op.ts`, `desk-panel.tsx`, `reply-form.tsx`, `resolve-form.tsx`, `cancel-form.tsx`, `web/src/lib/admin/ops.ts` |
| TK-14 (web) | Ticket and event photos as thumbnails through `GET /v1/media/{uuid}/read-url` (a failed URL drops only that photo). | `server.ts`, `ticket-thread.tsx` |
| strings | bn and en pairs in one module (keys `tickets.*`, plus `grant.module.support` and the two `error.ERR_TICKET_*`). | `web/src/lib/i18n/messages-tsoman.ts` (+ one spread line each in `messages-en.ts`, `messages-bn.ts`) |
| contract aliases | `SupportTicket*` types. | `web/src/contract/types.ts` |
| mock | the five ticket operations with the contract rules (own vs desk scope, op replay by canonical body, version 409, transition 409, comments closed 409, first reply -> in progress, derived event UUIDs), 5 seeded tickets for tso334, `Me.support_desk` (ADMIN, SUPERADMIN), code lists `ticket_issue_type` (31, V0131) and `ticket_resolution_code`. | `web/mock/tickets.ts`, `server.ts`, `master-seed.ts` |
| test | `checker-tickets.test.tsx` (25): resolve needs code and notes, cancel needs reason, reply bounds, op_uuid + expected_version in every body, transition matrix, key kept only without an answer, rendered panel per status (required markers, no preselected code, bn), conflict banner with Reload (en, bn), BFF through the mock (replay writes nothing, stale version 409 ERR_CONFLICT, reused op_uuid 409, close from new 409 TRANSITION_INVALID, reply on resolved 409 COMMENTS_CLOSED, first reply -> in_progress, reply stored once, TSO 403 at the BFF, SUPPORT 403 at the API), "Support" in the thread, photos, list 18/723 -> 2% and 1/201 -> ০%, query parsing, module and menu gating incl. the shell's /v1/me read. Mutation-checked (code check removed, actor name shown, desk filter removed: 4 tests fail). | `web/tests/checker-tickets.test.tsx` |

Seen in a browser in step 1 (Next dev against the contract mock, bn, admin1): list, detail, the required-field errors, a
stale-version resolve showing the conflict with Reload, then the resolve after the reload with the screenshot-14 message and
the closed note. Not a golden; the web has no screenshot goldens for this page.

## Done (step 2)

| item | what | files |
|---|---|---|
| DASH-13 + F1 ask 11 | The Login / Bikroy Joma drill (`/drill/login`) reads `POST /v1/dashboards/national/login-submit/users?tab=` once per tab (Not Logged In, Not Uploaded): columns Name (full name; "SR Not Set" for a vacant route), User Name, Route, Dep Name (the zone), Role (SR/AMO), in the app card's order (screenshot 8). Caption per tab: the route count ("1,186 routes without a login today."); a "The list is cut at 2,000 rows. Narrow the filter (a territory or a zone)…" note only when `truncated`. The filter carries the picked wing/division/territory/zone ids below the token's own level (boundGeo) and the date; a picked route narrows on the page (the national filter has no route ids). The drill no longer makes the tracking read. | `web/src/app/(dashboards)/drill/[kind]/page.tsx`, `login-users.ts` (new, colocated) |
| FS-14 | `/admin/day` (and Config P15) reopen form: a client form (`DayReopenForm`) that checks zone, date (never a future day) and a reason of 10..500 code points, asks for a confirm, and posts op `day.reopen` with `client_uuid`. The key is kept only while the last attempt got no answer (network error or 5xx) AND the request (zone, date, reason) is unchanged; any answer, or a changed request, starts a new key. Window hint "within 3 days"; window, roles and audit stay the server's; server field errors (`/business_date` `window`, `too_short`, …) show on their field. | `web/src/components/admin/config/day-reopen-form.tsx` (new), `day-control-view.tsx`, `web/src/lib/admin/ops.ts` (comment) |
| FS-18 | The web Final Submit button only for the role the API accepts with the shipped config: the TSO (`FINAL_SUBMIT_BUTTON_ROLES`). The page stays open to the menu's roles (TSO, DMO, ADMIN, SUPERADMIN: preview and Delete Section Data; `FINAL_SUBMIT_PAGE_ROLES`). The team-op proxy forwards `day.final-submit` only for TSO, DMO and WM (DMO/WM may be delegates under `cfg.day.final_submit_delegate_roles`, bounds "subset of DMO, WM"); ADMIN and SUPERADMIN are refused at the proxy (the API always answers them 403). | `web/src/lib/admin/ops.ts`, `web/src/components/admin/config/final-submit-page.tsx` |
| LV-19 | `/leave`: Applicant (applicant_name with applicant_username under it; fallbacks username, then `#user_id`), Territory (territory_name), Type (label of the `leave_type` code list from `GET /v1/admin/code-lists`: label_bn in Bangla when supplied, else label_en; the code when the list cannot be read). Reject opens an optional note (DecisionRequest.note, at most 300 through the BFF) and needs a second tap; Approve stays one tap; test ids `approve-<uuid>` / `reject-<uuid>` kept. | `web/src/app/(dashboards)/leave/page.tsx`, `leave-rows.ts`, `leave-decision.tsx` (new, colocated) |
| FB-03 | Mock seed: `feedback_category` = suggestion "Suggestion", feedback "Feedback" (label_bn null, as V0027 and V0131); three TSO feedback rows with business_date, captured_at, username and zone_id, one with a photo. | `web/mock/master-seed.ts` |
| FB-12 | Feedback inbox: category by its label (code-list ref), Photo thumbnail through `GET /v1/media/{uuid}/read-url`, Username, Zone (geo ref "code · name"), Status, Date (business_date), from/to filter (contract `from`, `to`), status action for SUPPORT, ADMIN and SUPERADMIN (the API's `FEEDBACK_TRIAGE`). CRUD engine: optional `media` field flag (one read per picture on the page, a dash when the read fails, also on the action page summary); a code ref the list cannot label now shows its code instead of "NaN". | `web/src/app/admin/_entities/feedback.ts`; tagged engine lines in `web/src/components/admin/crud/meta.ts`, `pages.tsx`, `server.ts` |
| XA-02 | Report catalogue entry `tso-visit-log` (row XA-02, field force, period + geo, title "TSO Visit Log" / "টিএসও ভিজিট লগ"); the mock answers it per docs/24 s12.3: plan date, TSO, zone, route, outlet, status, completed at, both questionnaire answers by key (V0139 labels in en and bn as column labels), delegate task, task, task status. | `web/src/lib/reports/catalog.ts`, `web/mock/dash.ts` |
| XA-03 | Task Planner: the catalogue offers the server's `outlet_code` filter; the mock lists tasks with the columns B3 adds (assigned by, route, label, comment) next to the server's existing ones, with Bangla column labels. | `web/src/lib/reports/catalog.ts`, `web/mock/dash.ts` |
| Quarantine (D-TSO-MAN-31) | Reason `after_final_submit` reads "Late sale after Final Submit" in the list and the detail, with a help note (captured before the zone's Final Submit, kept out of the day's figures until decided, accept counts it marked late, discard keeps it out, nothing lost while it waits); its accept decision reads "Accept as late sale". Other reasons unchanged. | `web/src/components/admin/config/quarantine-view.tsx` (tagged) |
| tso-daily-tracking | Each route line reads "35/40 · Till-date 87.50%" through `fmt.ts pct2dpText` (the server's two-decimal percent moved to basis points once and printed by `pctText`; no second rounding to one decimal); "—" while the server sends none; Bengali digits in bn. | `web/src/app/(dashboards)/tso-daily-tracking/page.tsx`, `web/src/lib/national/fmt.ts` |
| mock (every new op) | `POST /v1/dashboards/national/login-submit/users` (over the seeded day of mock/seed.ts: SR row then the AMO rows of the zone, route_count, cut at 2000 with `truncated`), `brand-cpr` (integer bp of both denominators, sorted by outlets bought), `segment-share` (value share of all value, volume share inside the unit family), `GET /v1/me/notifications` and `POST /v1/me/notifications/read` (own rows, newest first, paging, read up to an id, idempotent), `POST /v1/day/reopen` (ADMIN/SUPERADMIN, reason 10..500, window 3 days → 400 `window`, `client_uuid` replay → the first result, reuse with another body → 409 ERR_CONFLICT, a second reopen → 409 ERR_DAY_NOT_FINAL_SUBMITTED, audited `zone_day.reopened`), `GET /v1/media/{uuid}/read-url` for every signed-in role (master-data users too), the leave applicant members and the stored decision note, leave types sick and earn. The new national answers are checked with `satisfies` against the generated contract types. | `web/mock/tso-manual.ts` (new), `national.ts`, `server.ts`, `dash.ts`, `admin.ts`, `master-seed.ts` |
| strings | bn and en pairs under `tsoman.` (drill, reopen, leave, feedback, report title, quarantine, tracking), plus `error.ERR_DAY_NOT_FINAL_SUBMITTED`. | `web/src/lib/i18n/messages-tsoman.ts` |
| tests | Named: `drill-columns.test.tsx` (17), `day-reopen-form.test.tsx` (18), `final-submit-roles.test.tsx` (13), `leave-applicant.test.tsx` (13). Also: `feedback-inbox.test.tsx` (7), `tso-reports.test.ts` (3), `late-sale-quarantine.test.tsx` (4), `tso-daily-tracking.test.tsx` (3), `mock-tso-manual.test.ts` (7). Pages render against the contract mock; actions go through the real BFF handlers. | `web/tests/*` |

## Decisions

- **D-W1-01 Desk gating through the module grants.** `Me.support_desk` is folded into the menu's module grants: without it
  module `support` is withheld (for every role), so `canSee(grants, moduleOfPage("/admin/tickets"))` hides the entry. This
  needs only `loadMenuAccess` to pass the flag (one line) instead of a new MenuItem flag through `menu.ts`, `shell.tsx` and
  both layouts. `menuFor(role)` without grants (tests, static lists) still lists the entry for the portal roles.
- **D-W1-02 Reply idempotency key.** The reply goes through `addSupportTicketEvent`, whose body is `{event_uuid, body}`
  (additionalProperties false): it sends `event_uuid`, not `op_uuid`/`expected_version`. Every status, priority, resolve
  and cancel action goes through `updateSupportTicket` with `op_uuid` and `expected_version`.
- **D-W1-03 Key lifetime.** An action keeps its `op_uuid`/`event_uuid` while no answer came back (network error or 5xx),
  so the retry replays; any 2xx or 4xx settles it and the next attempt is a new op.
- **D-W1-04 Resolution codes.** Read from `GET /v1/admin/code-lists` (`ticket_resolution_code`, valid today, sort order).
  When the list cannot be read (SUPPORT or an ADMIN without masterdata gets 403 there) the form offers the V0131 seed
  `resolved` / "Resolved" alone and says so. Labels follow the code-list rule (label_bn when AKTCL supplies it).
- **D-W1-05 Priority only on open tickets**, and no confirm on Close (the hint says the reporter normally closes);
  Cancel asks for a confirm (no reopen).
- **D-W1-06 working_days in the web corpus test.** The web never counts working days (the server sends `working_days`,
  `elapsed_days`, `period_days`; D-TSO-MAN-08 names the four server consumers), so the section is listed as "not a web
  formatter" instead of re-implementing WorkingDays in TypeScript; the test fails if a new section appears unlisted.
- **D-W1-07 Trusted now.** The age column and header use the web server's clock (`serverNowMs`), never the browser's.
- **D-W1-08 Issue-type filter** shows only when the code list can be read; status tabs carry the desk-wide summary counts.
- **D-W1-09 Login drill read.** One `login-submit/users` call per tab; the filter is built from the picked geo levels the
  token does not fix (boundGeo) plus the date; a picked route narrows on the page. The scope stays the token's: a zone outside
  reach only narrows and comes back empty (tested). The drill no longer reads `GET /v1/dashboards/login-submit`.
- **D-W1-10 Mock users op over the seed.** The users op is computed from `mock/seed.ts` (the drill page's geo ids), not from
  the national mock's 30 zones; an AMO covers the zone of its own route. The seeded day has no SR route without a login in
  territory 334, so Not Logged In is the empty state for tso334 in the mock; the tests stub richer rows.
- **D-W1-11 Reopen key.** A dedicated form instead of the generic OpForm: OpForm keeps its UUID across a 4xx and across field
  edits, so a lost answer for zone A could be replayed as zone B. Here the key is kept only for a resend of the same
  unanswered request (step 1's `keepsKey`: no answer or 5xx).
- **D-W1-12 Final Submit roles.** Button: TSO only, because `cfg.day.final_submit_delegate_roles` is server-delivered (default
  []) and the web cannot read it; proxy: TSO, DMO, WM (the roles the API can ever accept); page: the menu's roles. A delegated
  DMO or WM gets the button once the preview says so (ask 4).
- **D-W1-13 Leave type label and note.** Labels from `/v1/admin/code-lists` (MASTER_READERS include the web roles); retired
  items still label old rows; the note limit follows the existing BFF (300) rather than the contract's 500 (ask 9).
- **D-W1-14 Feedback thumbnail in the engine.** One optional `media` flag on a field (no new field kind); read URLs resolved
  on the server for the rows of the page only (25); a failed read leaves a dash. The zone column uses the geo zone list like
  the other entities ("code · name"); the action page labels it with one GET.
- **D-W1-15 Feedback triage roles.** SUPPORT gets the status action because `FeedbackApi.FEEDBACK_TRIAGE` is SUPPORT, ADMIN,
  SUPERADMIN; three older checks that pinned SUPPORT as read-only were changed (deviations).
- **D-W1-16 Till-date % precision.** `DailyTrackingRow.tilldate_target_achievement_pct` is a two-decimal number (the row has
  no integers for it), so the web prints it at two decimals through bp and `pctText` and never re-rounds it.
- **D-W1-17 Quarantine wording.** Only `after_final_submit` gets a label and help (the item names it); other codes keep the
  server's code text as before.
- **D-W1-18 Catalogue row id.** The visit log's catalogue `row` is "XA-02" (no F-WEB row exists for it).

## Deviations (files outside the s5 W1 Owns list; each line tagged `// tso-manual W1`)

- `web/src/lib/menu/matrix.ts` (step 1): `loadMenuAccess` reads `support_desk` and passes it to `grantsOf`; a failed /v1/me
  now returns `grantsOf(role, null, false)` (the same as before except module support). Needed for the s5 rule "the Tickets
  entry shows when Me.support_desk" (D-W1-01).
- `web/src/lib/admin/access-grants.ts` and `web/tests/user-access.test.ts` (step 1): ruled to W1 by R-F-11 / F1 ask 14.
- `web/tests/roles-menu.test.ts` (step 1): two expectations gain `/admin/tickets` (the static role list of the portal roles).
- `error.ERR_TICKET_TRANSITION_INVALID`, `error.ERR_TICKET_COMMENTS_CLOSED` (step 1) and `error.ERR_DAY_NOT_FINAL_SUBMITTED`
  (step 2): outside `tickets.`/`tsoman.` because `problemMessage` reads `error.<code>`.
- Step 2, new files next to owned ones (no other lane edits `web/`): `web/src/app/(dashboards)/drill/[kind]/login-users.ts`,
  `web/src/app/(dashboards)/leave/leave-rows.ts` and `leave-decision.tsx` (a page file may not export helpers, and the reject
  note needs a client component), `web/src/components/admin/config/day-reopen-form.tsx` (D-W1-11).
- Step 2, tagged lines: `web/src/components/admin/config/quarantine-view.tsx` (the owned quarantine-page.tsx only loads the
  data; the reason text and the decision labels are rendered by the view); `web/src/components/admin/crud/meta.ts`,
  `pages.tsx`, `server.ts` (FB-12 thumbnail and the code-ref fallback, D-W1-14).
- Step 2, tests outside the list, each change tagged: `dash-drill.test.tsx` (the login drill's read is stubbed),
  `feedback.test.ts`, `checker-adm4-pages.test.ts` CA4-4 and `checker-adm4-bff.test.ts` CA4-20 (SUPPORT triages, D-W1-15),
  `reports-seeded.test.ts` (the task planner has no route-day sum now), `web/e2e/v13-followup.spec.ts` (category
  `suggestion`). New test files beyond the four named: `feedback-inbox.test.tsx`, `tso-reports.test.ts`,
  `late-sale-quarantine.test.tsx`, `tso-daily-tracking.test.tsx`, `mock-tso-manual.test.ts`.

## Asks

1. **Lead (web cards outside W1's files):** `web/src/components/national/cards.tsx` prints the Sales ring, CPR, Final
   Submit and Login/Submit rings with `pctBp(bp, 1)` (tenths from a rounded bp: 29/1186 prints 2.5%, the phone 2.4%).
   The slices already carry the integers; the fix is `pctText(locale, pctTenths(n, d), 1)` per card (Sales:
   `dailyRingTenths(total, monthly_target_qty, working_days)`). Assign the file to W1 or its owner.
2. **Lead:** `web/src/components/kit/icons.tsx` `MENU_ICON` has no entry for `admin-tickets` (falls back to the dot);
   one line `"admin-tickets": "inbox"` if the owner of that file agrees.
3. **B1:** the console assumes `Me.support_desk` is true for ADMIN and SUPERADMIN (D-TSO-MAN-22) and that the module guard
   answers 403 for an ADMIN without module `support`; the read URL of `support_ticket*` media must be allowed for the desk
   (TK-14), else the photos are silently left out.
4. **F1 + B3 (1.10.x): `FinalSubmitPreview.can_submit`** (bool, computed on the server from the caller's role and
   `cfg.day.final_submit_delegate_roles`). The web shows the Submit button only to the TSO today (D-W1-12); with this member
   it would follow the server for a delegated DMO or WM. Until then a delegated DMO or WM cannot submit from the web (the app
   path is unaffected).
5. **B3 (`POST /v1/day/reopen` answers the web expects; the mock does this):** outside the window → 400 ERR_VALIDATION with
   `errors: [{pointer: "/business_date", code: "window"}]` (shown on the date field); a zone-day not final-submitted (or
   already reopened) → 409 ERR_DAY_NOT_FINAL_SUBMITTED; the same `client_uuid` with another body → 409 ERR_CONFLICT; a replay
   → 200 with the first DayReopenResult.
6. **B3 (report columns):** the mock's column keys are, for the task planner, `assigned_by`, `route`, `label`, `comment`, and
   for the visit log `plan_date, tso, zone, route, outlet_code, outlet_name, status, completed_at, sr_visits_regularly,
   sr_prints_memo, delegate_task, task, task_status`, each with `label_bn`. The web engine takes any key; please send
   `label_bn` so the Bangla report headers are not English.
7. **B1/B3 (media authz):** the feedback inbox reads `GET /v1/media/{photo_uuid}/read-url` as SUPPORT, ADMIN and SUPERADMIN;
   if the API refuses those roles for `feedback` media, the thumbnail shows a dash.
8. **B2:** `DailyTrackingRow.tilldate_target_achievement_pct` at two decimals (the web prints it at that precision, D-W1-16).
9. **Lead:** the leave-decision BFF (`web/src/app/api/bff/leave-decision/route.ts`, not W1's) caps the note at 300 while
   DecisionRequest allows 500; the page follows 300. One constant if the owner wants 500.

## Not done

- Asks 1 and 2 wait for the lead (not W1 files).
- `getTargetAchievement` (changed, not new, in 1.10.0) has no mock: the web does not read it.
- Step 2 pages were not seen in a live browser. A Next dev server (port 3311) and a mock started at 04:31 in this worktree
  (step 1) were still running; they hold the Next dev lock for this folder and their mock process predates the step-2 mock
  routes. They were left untouched (not started by this step). Every step-2 page was rendered server-side against the
  contract mock in the tests above (static markup); the client forms' clicks are covered through their pure rules, not by a
  browser.
- Playwright e2e not run on this laptop.

## Verify step 2 (this worktree, 2026-10-29, Windows laptop, Node 22; CI not run)

- `cd web && npm run lint`: exit 0 (whole web).
- `npm run typecheck`: exit 0.
- `npm test` (whole web): 154 files, 1899 passed, 1 skipped, 0 failed.
- `python tools/ci/money-float-scan.py`: 17 of 17 allowed, 0 open findings (`python3` is not on this laptop's PATH; `python`
  ran it).

## Verify step 1 (this worktree, 2026-10-29, Windows laptop, Node 22; CI not run)

- `cd web && npm ci`: 444 packages, exit 0 (install scripts off).
- `npm run typecheck`: exit 0.
- `npm run lint`: exit 0 (whole web).
- `npx vitest run tests/checker-tickets.test.tsx tests/kpi-format-corpus.test.ts`: 2 files, 382 tests passed.
- `npx vitest run` (whole web): 145 files, 1813 passed, 1 skipped, 0 failed.

## Goldens

None: the web pages and formatters have no screenshot goldens (Roborazzi goldens are Android only). W1 needs no golden from
the Linux runner.

## Next step

The s5 W1 items are built (both steps). What a next agent on this lane can do:

1. When F1/B3 answer ask 4: `final-submit-page.tsx` passes `preview.can_submit` (keep the TSO rule as the fallback for an
   older server) and `final-submit-roles.test.tsx` gains the delegate case. When B3 answers asks 5 and 6: align
   `web/mock/tso-manual.ts` (reopen) and `web/mock/dash.ts` (report keys) if B3 differs.
2. If the lead assigns `web/src/components/national/cards.tsx` (ask 1): print the rings with `pctText(locale, pctTenths(n,
   d), 1)` and `dailyRingTenths` from the slice integers.
3. Before merge: rerun `cd web && npm run lint && npm run typecheck && npm test`. When B1, B2 and B3 are on dev (test
   account), check the drill, reopen, leave, feedback and quarantine pages there; stop the step-1 dev server on port 3311 and
   its mock if nobody uses them.
