# Web-admin lane status

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
