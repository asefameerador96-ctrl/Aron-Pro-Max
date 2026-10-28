# tso-manual W1 web: lane status

Lane W1 of `docs/requests/tso-manual-build-2026-10.md` (s5 W1). Branch `tso-man/W1`, base 95a35b7d, no pull request,
nothing pushed. Written 2026-10-29 (step 1 of 2: support console and numbers).

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

Seen in a browser (Next dev against the contract mock, bn, admin1): list, detail, the required-field errors, a stale-version
resolve showing the conflict with Reload, then the resolve after the reload with the screenshot-14 message and the closed
note. Not a golden; the web has no screenshot goldens for this page.

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

## Deviations (files outside the s5 W1 Owns list; each line tagged `// tso-manual W1`)

- `web/src/lib/menu/matrix.ts`: `loadMenuAccess` reads `support_desk` and passes it to `grantsOf`; a failed /v1/me now
  returns `grantsOf(role, null, false)` (the same as before except module support). Needed for the s5 rule "the Tickets
  entry shows when Me.support_desk" (D-W1-01).
- `web/src/lib/admin/access-grants.ts` and `web/tests/user-access.test.ts`: ruled to W1 by R-F-11 / F1 ask 14.
- `web/tests/roles-menu.test.ts`: two expectations gain `/admin/tickets` (the static role list of the portal roles).
- `error.ERR_TICKET_TRANSITION_INVALID` and `error.ERR_TICKET_COMMENTS_CLOSED` keys: outside `tickets.`/`tsoman.` because
  `problemMessage` reads `error.<code>`.

## Asks

1. **Lead (web cards outside W1's files):** `web/src/components/national/cards.tsx` prints the Sales ring, CPR, Final
   Submit and Login/Submit rings with `pctBp(bp, 1)` (tenths from a rounded bp: 29/1186 prints 2.5%, the phone 2.4%).
   The slices already carry the integers; the fix is `pctText(locale, pctTenths(n, d), 1)` per card (Sales:
   `dailyRingTenths(total, monthly_target_qty, working_days)`). Assign the file to W1 (step 2) or its owner.
2. **Lead:** `web/src/components/kit/icons.tsx` `MENU_ICON` has no entry for `admin-tickets` (falls back to the dot);
   one line `"admin-tickets": "inbox"` if the owner of that file agrees.
3. **B1:** the console assumes `Me.support_desk` is true for ADMIN and SUPERADMIN (D-TSO-MAN-22) and that the module guard
   answers 403 for an ADMIN without module `support`; the read URL of `support_ticket*` media must be allowed for the desk
   (TK-14), else the photos are silently left out.

## Not done (step 1 scope)

- **F1 ask 11 ("list cut, narrow the filter" note when `NationalLoginSubmitUsers.truncated`)**: the note belongs to the
  rows of `POST /v1/dashboards/national/login-submit/users`, which the drill page does not read yet; that read is DASH-13
  (W1 second half, with `drill-columns.test.tsx`). Doing the note alone would have no place to show. Moved to step 2,
  first item.

## Verify (this worktree, 2026-10-29, Windows laptop, Node 22; CI not run)

- `cd web && npm ci`: 444 packages, exit 0 (install scripts off).
- `npm run typecheck`: exit 0.
- `npm run lint`: exit 0 (whole web).
- `npx vitest run tests/checker-tickets.test.tsx tests/kpi-format-corpus.test.ts`: 2 files, 382 tests passed.
- `npx vitest run` (whole web): 145 files, 1813 passed, 1 skipped, 0 failed.

## Goldens

None: the web console and the formatter have no screenshot goldens (Roborazzi goldens are Android only).

## Next step (step 2: the second half of s5 W1)

1. DASH-13 + F1 ask 11: drill login page reads `login-submit/users?tab=not_logged_in|not_uploaded` (columns Route, Name
   or "SR Not Set", User Name, Dep Name, role), route_count caption, the truncated note (bn and en, `tsoman.` keys);
   `drill-columns.test.tsx`; adjust `dash-drill.test.tsx` if it pins the old columns.
2. FS-14 reopen form (`day.reopen` op with `client_uuid`, reason 10..500, roles `cfg.day.reopen_roles`), FS-18 Submit
   button only for roles the API accepts; `day-reopen-form.test.tsx`, `final-submit-roles.test.tsx`.
3. LV-19 leave page (applicant name, username, territory, type label, optional reject note); `leave-applicant.test.tsx`.
4. FB-03 mock seed, FB-12 feedback entity (category label, username + zone, photo thumbnail, date filter, SUPPORT status
   action as the API).
5. XA-02/XA-03: `tso-visit-log` catalogue entry and labels (`web/src/lib/reports/catalog.ts`), task planner columns.
6. Quarantine page label and help for `after_final_submit` ("Accept as late sale").
7. tso-daily-tracking shows the till-date % (through `fmt.ts`), and ask 1 above if the lead assigns cards.tsx.
8. Verify: `cd web && npm run lint && npm run typecheck && npm test`.
