# Web-admin lane status

## WAVE 4 (2026-10-22, session_01WrmfRQageVY9yT4n5tvnst, docs/34 s1, s2, s4.2) — read this first
**Branch:** `lane/web-admin` (push only there; merge INT before each push; no PRs). Gate from `web/`: `npm ci`; `npm run lint` (before build); `npx tsc --noEmit`; `npx vitest run`; `npm run build`; `PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test`.
**Done (each with unit + Playwright tests; the backend halves do not exist yet, so every page runs against the provisional mock in `web/mock/custom.ts`; each has a request file and an `it.fails` contract test in `web/tests/requests/` that turns RED when the contract gains the operation):**
1. **Users, portal levels, grants, brand scope.** `/admin/users` role select lists the owner's seven levels first (SUPERADMIN, ADMIN, NATIONAL, BRAND, WM, DMO, TSO), then SR/AMO marked "app only", then ANALYST/SUPPORT/TOP. `/admin/user-access/{id}` (link "Access" for SUPERADMIN): module grants per module none/read/write for ADMIN, brand tick list for BRAND (at least one brand), PUT with If-Match + reason, Super Admin only (ADMIN/SUPPORT read-only). Request `docs/requests/backend-admin-user-access.md` (Role NATIONAL/BRAND, GET/PUT `/v1/admin/users/{id}/access`). Op `user-access.put`. NATIONAL/BRAND are not in the generated `Role`: provisional `lib/admin/access-grants.ts`; the users list `role` filter keeps the contract values only.
2. **Route monthly targets** `/admin/route-targets` (D-TARGET-MIN-01): xlsx read in the browser (`read-excel-file`, template via `write-excel-file`), per-row validation report, server dry-run preview (rows, routes, SKUs, months, total, how many existing targets are replaced), commit with reason, idempotent by batch uuid, list of targets in force. Request `docs/requests/backend-admin-route-targets.md` (`PUT/GET /v1/admin/route-targets`). The **calendar/holidays page already existed** (weekend config + holidays/make-up/emergency days); added a "working days per month" table (national entries) on it. Shared flow in `components/admin/sheet-import.tsx`.
3. **House de-duplication** `/admin/house-dedupe` (master-data hub, geography): groups by territory + normalised name (case, spaces, punctuation ignored; Bangla vowel signs kept), suggested keeper = oldest active, activate/deactivate through the ordinary house PATCH (status = the active flag, If-Match + reason). Mock has a seeded duplicate H-5.
4. **Outlet channel mandatory.** Form: channel is a required choice with NO preselected option (`noDefault` meta flag + disabled "Choose…" prompt for required enums with no value), cannot be cleared on edit. New **outlet Excel import** `/admin/outlet-import` (channel column mandatory, report refuses blank/unknown channel; proxy repeats the rule). Request `docs/requests/backend-admin-outlet-import.md` (`POST /v1/admin/outlets/import`).
5. **SKU pack fields and preview.** `base_per_pack`, `entry_unit_default` were already on the create form; added the live preview under the fields ("1 packet = 20 sticks" / "1 box = 25 pieces" / matches in dozens), the derived pack label (packet / box / dozen), non-blocking warnings (cigarette 10/20, lighter 25, matches 1, base unit and entry unit). Edit page shows the stored pack size read-only: `SkuPatch` has no pack members. Request `docs/requests/backend-admin-sku-pack-edit.md`.
**Decisions:** every write goes through the whitelisted ops (`lib/admin/ops.ts`) with a reason and, where the API has a version, If-Match; previews send no reason and write nothing. New strings live in `lib/i18n/messages-wave4.ts` (pairs, spread into en/bn). Module grants are NOT yet applied to the portal menu (needs `Me.modules`; see the access request section 4).
**Traps:** mock users `msuper1` (SUPERADMIN, master), `nat1`, `brand1` rows exist; `/v1/admin/**` mock handlers only serve `master` users. `it.fails` request tests are expected to stay "expected fail" until the contract changes; when one turns red the contract has landed: remove the provisional code. The create form returns to the list after saving (no form-ok banner).

## HANDOVER (2026-10-15, session 3; start here)
**Branch:** work on a local branch, push ONLY `git push origin HEAD:lane/web-admin`; merge INT (`origin/claude/wonderful-thompson-k6ejnf`) before each push; no pull requests. Commit messages end with `Co-Authored-By: Claude <noreply@anthropic.com>`.
**Done (all with checker rounds fixed):** everything in the earlier list (F-ADM-001..011, 020, 026, 028, 056, 070, 071, 076, F-WEB-003, 032, 043b, F-TSO-023, 025) plus this session: **F-ADM-065** SKU pack image, the **contract v1.3 stub removal** (tutorial/content asset_id, definition round-trip incl. option_codes, feedback filters, sub-channel select from code-list ids, acting-scope `valid_to` exclusive end), and **F-WEB-003b outlet change request** for TSO/DMO at `/admin/outlet-change`.
**Also done (session 3, later):** dues adjustment screen hardening and paper data entry behind the default-off flag `ARON_FEATURE_PAPER_ENTRY=1` (set in the web environment once reports' dw entry_source is on INT). SKU image was already done. Lead may still send (4) quarantine "match-backfill" action (POST /v1/admin/quarantine/{id}/match-backfill) after backend-admin 01d8a92b is promoted.
**Left:** nothing queued in the lane brief. Candidates if the lead wants more: outlet-change list of "my requests" (the existing /admin/outlet-requests already lists them); a SKU image column in the SKU list; a code-list select for the other free-text code fields.
**Server halves to confirm live (built against contract and mock):** `app.sku.image_asset_id` (db lane); `POST /v1/outlet-requests`; `CodeItem.id` for sub_channel; rubric write of `score_1_5`; `ADMIN_READ` of `/v1/admin/clusters` and `/v1/admin/code-lists` for TSO (the outlet-change form falls back to typed ids and shows a load-failure alert if the read is refused; the backend's MASTER_READERS includes TSO). Still open requests: web-admin-asset-upload-csp (infra sets `ARON_BLOB_ORIGIN`; the SKU preview image also needs it in `img-src`, done in `security-headers.ts`), web-admin-temp-password-ttl.
**How to run the web gate** (from `web/`): `npm ci`; `npm run lint` (ALWAYS before build: a lint error leaves a stale `.next` and e2e shows 403 pages for new routes; do not chain build after lint with `|`/`tail`, check the lint result); `npx tsc --noEmit`; `npx vitest run` (907 pass, 2 skipped); `npm run build`; `PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test` (about 5 min, 111 pass+; axe is in config-a11y). Checkers: T1 Opus, T2 Sonnet, each with `isolation: "worktree"`; copy their test files in, fix, then `git rm -r --cached .claude/worktrees` is NOT needed if you never `git add -A` while a worktree exists (add paths explicitly).
**Traps:** the admin segment streams behind `loading.tsx`, so `notFound()` answers 200; tests must use relative dates; new i18n keys go in BOTH `messages-en.ts` and `messages-bn.ts` before `} as const;` / `};`; shared files edited by other lanes: `master-links.ts`, `roles.ts`, `menu.ts`. New: `ref.codeList` loads options from `/v1/admin/code-lists` (one call), `valid_to` of a code item is exclusive; the asset field kind sends `""` as null and uses the sentinel `-` for "remove" in the form only.

## Done
- N-011 admin portal: role-gated `/admin` (ADMIN, SUPERADMIN, SUPPORT; SUPPORT read-only), kit (Field, ReasonField, DataTable, FilterBar), metadata CRUD generator proven on clusters (list, filter, create, edit, audit history), audit page. README "Add an entity" in `web/README.md`.

## Decisions
- ANALYST can read audit per docs/24 s8.5 but is outside the portal gate for now; revisit on Day 4.
- Reason lengths count code points like the contract.

## Open
- Create reason cannot be stored (contract), edit page re-reads the list (no GET by id): both requested.

## Requests filed
- web-admin-create-reason, web-admin-get-by-id.

## Scope note (lead, 2026-10-06)
- Read the deferred-programmes scope change (docs/27): target, loyalty/Astha and discount/promotion programmes are deferred. Nothing of them exists in `web/` (menu, entities, report keys); the admin CRUD generator stays generic so a programme module is one entity file later.
- Blocking nothing. Pending requests (routed by the lead): web-admin-create-reason, web-admin-get-by-id, web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job.

## Update (Day 1, later)
Done (pushed): F-ADM-001, 002, 003, 004, 007, 010, 011, 056, 070, F-WEB-003, F-WEB-032 with checker rounds fixed; F-ADM-021/023/060 delivered by web-config pages (hub links). Master-data hub at `/admin/master-data` uses `master-links.ts`.
In progress: F-ADM-005, 006, 008, 071 built, checker pending.
Next: F-ADM-076 (SR lifecycle wizard), F-ADM-020, F-ADM-026, F-ADM-028, F-ADM-065, F-TSO-023/025.
Decisions: parent level is the level above in geography; task_type attrs.roles dropped from the editor; ANALYST outside portal gate except outlet requests; direct edit ADMIN/SUPERADMIN only; mock master users madmin1/msupport1/mtso1; sub_channel_id is a plain number; acting-scope end date not expressible.
Requests (state: awaiting lead): web-admin-create-reason, web-admin-get-by-id, web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job, web-admin-sub-channel-ids, web-admin-outlet-web-request, web-admin-acting-scope.
Known not mine: e2e/pages.spec.ts daily-tracking date-dependent failure (dashboard lane).

## Update (checker round for 005/006/008/071)
Sonnet checker found 7 issues; fixed: unknown user on scope write is 404, duplicate scope nodes refused, scope node_id safe-integer bound, assignment end not after start, price publish refuses today (contract says future date). Not changed: zero price allowed (contract MtkNonNegative; product call), unknown SKU / overlapping effective ranges / second overlapping primary are API-side rules (mock gaps). Open: transfer form creates the new primary then ends the old one (two calls, not atomic): error is shown, retry is safe; needs an API transfer endpoint (not requested yet). Acting scope end date still requested.
F-ADM-076 built (`/admin/sr-lifecycle`); checker pending.

## Update (feedback, lifecycle, tutorials)
Done: F-ADM-076 and F-ADM-028 (checker findings fixed). F-ADM-026 built, checker pending.
Requests added: web-admin-feedback-filters, web-admin-tutorial-asset-id, web-admin-asset-upload-csp (infra sets ARON_BLOB_ORIGIN and the storage CORS rule).
Next: F-ADM-065 (SKU image upload, reuses the asset ticket flow), F-ADM-020 (surveys, rubrics, content), F-TSO-023/025, web/QC entry rows.
Trap: the admin segment streams behind loading.tsx, so notFound() in a page answers 200; the proxy rewrites known 404s instead.

## Update (definitions)
F-ADM-020 built at /admin/surveys, /admin/rubrics, /admin/content (checker pending). F-ADM-065 parked: contract has no SKU image member (request web-admin-sku-image). Request web-admin-definition-reads: read and write schemas do not round-trip (no keys on read, rubric enum differs, no asset_id).
Decision: points_per_photo (loyalty) is not exposed (docs/27 deferred).
Remaining: F-TSO-023, F-TSO-025 (T1 and T2, Day 5), then wait on contract for F-ADM-065.

## Update (F-ADM-020 fixed, F-TSO-023 done, F-TSO-025 built)
Done: F-ADM-020 and F-TSO-023 after checker rounds. F-TSO-025 built at /admin/radius (checker pending). Request added: web-admin-temp-password-ttl (backend 72 h vs 24 h).
Open: F-ADM-065 waits on a SKU image member (request filed). Lead may route the contract requests.
Known trap: a failed lint leaves a stale .next, then e2e shows 403 pages for new routes; always run lint before build.

## Update (prices checker)
F-ADM-005 Opus (money) checker found 14 (6 in the grid and page, 8 in the mock); all fixed. Not changed: no stale-data guard between two admins (contract has no version on publish), future and pending prices are not listed on the page.

## Update (2026-10-15, released by the lead)
Priority /admin/enrolment: the page (web-config, N-045) matches createEnrolmentToken and the backend (DeviceService.createToken): flavour sr/amo/tso, lockdown dev/prod, QR from qr_text, secret shown once. Two things stood in the way on the live API, both fixed here:
1. A new admin (ADMIN/SUPERADMIN/SUPPORT) with no authenticator could not sign in on the web: the login had no TOTP setup step. Added "Set up an authenticator" at the second step (POST /api/bff/mfa/enrol with the sealed mfa cookie, QR of the otpauth URI, the key, ten recovery codes, all shown once). A second enrolment answers the API's 409 with a clear text.
2. Creating a code when no app release is published answers 404 from the API (backend: "no published release of this flavour to provision"); the form now says so and points to the Releases page. For the owner's phone test: a published SR release (and its APK download URL) must exist on dev before a code can be made.
Not verified live: I have no admin credentials; I read the API code instead. Gate: lint, tsc, vitest 824, Playwright 97.
Opus checker (mfa/enrol): 4 defects fixed with regression tests: an expired second-step cookie said "wrong code" (now "sign in again"); the mock refused a second unconfirmed enrolment while the backend replaces it (mock now matches: 409 only after a confirmed code); a 204/empty answer threw (now a 503 problem); the secret and codes are announced to screen readers (role=status). Held: cookie purpose binding, replayed token, CSRF, no-store, no secret in headers/logs, one API call per POST. Note: docs/24 line 255 says aron-mfa tokens work only for mfa/verify, but the backend also accepts them at mfa/enrol (doc drift).
