# Lane portal-loyalty-web (Lane L-B): status

## Train 4 (D-LOY-WEB-05): the mock follows AdminLoyalty.kt (the lead's FAIL on train 3)

What ran: lint, tsc, vitest (`tests/loyalty-bff.test.ts` asserts the SERVER's behaviour: first error only, newest first, keyset paging, containment reach, replay keys, check order), the loyalty e2e files alone 3 times, the full e2e once, the three gate scripts, and the lane CI run (id in the train report). The five pixel snapshots (config-tiers tiers A/B/C, national-shots route map and sale position) fail in this container's local runs, identical on clean INT; the lane CI run is the proof, not the local run. What is still different from the server, and the open work for the NATIONAL / BRAND / WM / DMO / TSO levels (no page or menu entry yet), are listed in D-LOY-WEB-05.

## Train 3 (D-LOY-WEB-04): the mock equals the server

Server pointers/codes mapped to fields, per-action 409 messages, no-change detection, cursor paging for versions and enrolments, exclusive enrolment end, the server's other refusals and role reach in the mock, ids `^[1-9]\d{0,14}$`. Next: switch from the mock to the live API when L-A's routes are on INT (a base-URL change; run the e2e journeys against it).

## Train 2 (D-LOY-WEB-03): aligned to contract 1.13.0

INT ce9d92bf7 merged. All loyalty calls are typed from the generated contract (no `rawRequest`), the contract paths replace the sketch paths, getProgramme/getGift serve the detail, closed ProgrammeWrite/Patch and GiftWrite/Patch bodies are sent, the progress panel says "not available yet" (no operation in 1.13.0), the channel checkboxes are gone (scope nodes need ids: see the decision), the tso-integration exception and the `/v1/admin/loyalty/**` map line are gone, and the `loginOk` landing wait (D-LOY-WEB-02) is included. Gate numbers are in the train 2 report. The Not-done list below still holds for pickers, image upload, read views and report rebuilds; the "switch to typed apiClient" item is done.

Branch `lane/portal-loyalty-web` from INT `claude/wonderful-thompson-k6ejnf` (f46f8047f). Records: `DECISIONS.md` D-LOY-WEB-01 and later.
Spec: `docs/status/portal-loyalty-brief.md` (s5 v1 slice, s7 API sketch, s8 web sketch, s10 owner questions with the defaults applied).
Pattern followed: `docs/status/portal-discount-web.md`. Contract on INT: 1.12.2 (1.13.0 of portal-loyalty-api not landed; assumed members listed in D-LOY-WEB-01).

## Done (train 1)

- Module `loyalty` (GRANT_MODULES, MODULE_KEYS, API route map, page map), menu entry, path-role entries; role matrix in `web/src/lib/admin/loyalty.ts`.
- Screens: list, detail (tiers, channels, progress with guarded percent, enrolments + enrol form, gifts, versions, audit), create draft, edit draft (a new version per save), status panel (activate Super Admin only, end), gift catalogue (add / edit, Super Admin only).
- BFF routes (programme create / update with If-Match / status / enrolments, gift create / update) with strict zod re-validation.
- Exact numbers: Decimal3 STD via BigInt, guarded percent, integer points, BigInt cash value; no float on a money path.
- bn + en strings (`messages-loyalty.ts`); empty, loading, offline, error and long-Bangla states.
- Mock `web/mock/loyalty.ts` (programmes, versions, status, enrolments, gifts, progress); users `mloy1` (ADMIN, `loyalty: r`).

## Tested

- Unit (`web/tests`): `loyalty.test.ts` (46: role matrix, module wiring, menu and path roles, exact BigInt numbers, form to wire body, pointer-to-field mapping in both styles, failure messages by server code, no-change, enrolment window, cursors, banner text for unplaced pointers, display bn/en) and `loyalty-bff.test.ts` (51: the BFF routes against the mock, asserting the SERVER's behaviour: one first error, check order, newest-first keyset paging, containment reach, replay keys, row versions, flat versus indexed pointers). Both run against `web/mock/loyalty.ts`, which follows `AdminLoyalty.kt` of lane portal-loyalty-api (what is equal and what still differs: DECISIONS D-LOY-WEB-05).
- e2e: `e2e/loyalty.spec.ts` (15: gating by role and grant, list filters, detail, create-edit-activate-end journey, enrol, field highlight and no-change, enrolment refusals, gift refusals, gifts, server-failure banner, long Bangla at phone and laptop width) and `e2e/loyalty-quality.spec.ts` (6: axe WCAG A/AA light, dark and Bangla on ten screens plus three action states; the shared perf BUDGET on the list).
- Gates at the train 5 head (numbers in the train report to the lead): lint 0, tsc 0, build 0, vitest 2108 passed (1 skipped, i18n parity included), money-float-scan 0 open, date-pin-scan 0 new, `tools/ci` unittest OK. Loyalty e2e alone 3 of 3 green (21 each); the full e2e has the five known pixel snapshots failing locally (config-tiers tiers A/B/C, national-shots route map and sale position), identical on clean INT, so the lane CI run is the proof, not the local run.

## Not done

- Read views and menu entries for NATIONAL / BRAND / WM / DMO / TSO (directive 16, seven levels): the admin shell admits only SUPPORT, ADMIN and SUPERADMIN; the server already scopes their reads. Needs a shell for those roles, `ADMIN_PATH_ROLES` lines and scope-aware empty states.
- Scope and product pickers (the form creates with an empty scope and keeps the scope on edit; the detail lists nodes by type and id), gift image upload, an outlet picker, multi-row enrolment, Superstar slabs and the campaign gift list, report rebuilds.
- Progress views: 1.13.x has no progress operation; the panel says so and shows no numbers. The tested helpers (`guardedPercent`, `remainingStd`, `cashValueMtk`) are ready for when it exists.
- The switch from the mock to the live API (a base-URL change) and the e2e journeys against it, when L-A's routes are on INT; an authenticated dev pass needs a pilot login lanes do not have.
- Regenerate the types when contract 1.13.1 (description text only) is merged.
- The flat pointers inside tiers and rows are a server shortcoming reported to the API lane; the form lights the right row once the server indexes them.

## HANDOVER (for a fresh session; written 2026-11-12 on INT aaab1f217, contract 1.13.1)

State: train 108 landed and deployed (INT aaab1f217 = this lane's 53ff6055b on Loyalty API train 2; the admin routes are live on dev). The web section `/admin/loyalty` still runs on the MOCK in unit and e2e tests; no code switches it to the live API yet, and none has to: see (1).

### 1. What "live switch" means, file by file

- **There is no flag.** Every loyalty call goes through `apiClient` (`web/src/lib/api/client.ts`), whose base is `ARON_API_BASE_URL` (default `http://127.0.0.1:4010`). The deployed web image already points it at the real API, so on dev the pages call the live routes today. The mock is only what the TESTS point that variable at.
- **Where the mock is wired:** `web/mock/loyalty.ts` (the handler, the seed, the model), `web/mock/server.ts` (import on line 17, `loyalty: freshLoyaltyStore()` in the state, the `handleLoyalty(...)` dispatch line ~412, mock users `mloy1`, `mdisc1`, `mgrant1`, `madmin1`, `super1`; `mloy1` has `loyalty: r` in `/v1/me`). `web/playwright.config.ts` starts the mock on `E2E_MOCK_PORT` (4010) and the built web on `E2E_WEB_PORT` (3100) with `ARON_API_BASE_URL` set to the mock. Unit tests (`tests/loyalty-bff.test.ts`) start `createMock()` in process and set `ARON_API_BASE_URL` to it.
- **Reads (server components), `web/src/lib/admin/loyalty-load.ts`:** listProgrammes `GET /v1/admin/programmes`; getProgramme `GET /v1/admin/programmes/{id}`; listProgrammeVersions `GET /v1/admin/programmes/{id}/versions` (cursor `?vc=`); listProgrammeEnrolments `GET /v1/admin/programmes/{id}/enrolments` (cursor `?ec=`); listGifts `GET /v1/admin/gifts[?programme_id]`; audit `GET /v1/admin/audit?entity=programme` (detail page, via apiClient). `/v1/me` modules via `lib/admin/modules.ts` `loadGrants`.
- **Writes (BFF, `web/src/lib/admin/loyalty-server.ts`, routes under `web/src/app/api/bff/admin/loyalty/**`):** `POST /api/bff/admin/loyalty` -> createProgramme; `PATCH /[id]` -> updateProgramme (If-Match = row `version`); `POST /[id]/status` -> setProgrammeStatus; `PUT /[id]/enrolments` -> putProgrammeEnrolments; `POST /gifts` -> createGift; `PATCH /gifts/[id]` -> updateGift (If-Match = gift `version`).
- **Pages (`web/src/app/admin/loyalty/**`):** list `page.tsx`, `new/page.tsx`, `[id]/page.tsx` (detail: tiers, scope, status panel, progress "not available yet", enrolments + enrol form, gifts, versions, audit), `[id]/edit/page.tsx`, `gifts/page.tsx` (catalogue, `?new=1`, `?edit=<id>`). Components in `web/src/components/admin/loyalty-*.tsx`.
- **e2e journeys that run on the mock:** all of `web/e2e/loyalty.spec.ts` (15) and `web/e2e/loyalty-quality.spec.ts` (6). They depend on the mock's seed (programmes 1 ASTHA-Q4 active, 2 DL-OCT active, 3 SS-NOV draft with scope + qualifying, 4 ASTHA-Q3 ended; gifts 1..4; enrolments for outlets 101..103; outlets/places/products 1..1000 exist) and on mock users. They cannot run against the real API as written.
- **Do in the live-switch round:** (a) keep the mock, add NOTHING to production code; (b) run the same journeys by hand or by a separate `E2E_LIVE=1`-style spec against dev with real logins (see 2); (c) compare every mock-vs-server difference listed in DECISIONS D-LOY-WEB-05 against the live answers and fix the MOCK or the web, not the server; (d) re-check pointers in `web/src/lib/admin/loyalty.ts` (`pointerToField`) against live bodies.

### 2. What must be proven against the live dev API, and how

- Prove: list/detail/versions/enrolments/gifts render for a Super Admin; create draft -> edit (version 3 after one patch; created = 2) -> activate (Super Admin) -> end; the reason is mandatory and a replay (same `request_uuid`) makes no second row; enrol one outlet (exclusive end, overlap = ERR_MASTER_OVERLAP, unknown outlet = `body.rows[i].outlet_id` not_found); gift create/update (Super Admin only) and `image_url` null; ADMIN `w` cannot activate or change gifts (403); ADMIN `r` sees no write buttons; an ADMIN without the grant gets no menu entry and the not-granted page; `scope` and `qualifying` survive an edit unchanged; flat error pointers reach the banner as `member: message`.
- Logins: Super Admin and ADMIN with `loyalty` r / w / none are needed (and TOTP for admin roles). **No pilot ADMIN or Super Admin login exists for lanes** (rule 8: secrets live in GitHub secrets only), so this cannot be proven from a lane container; it needs the lead's or the owner's account, or a login the owner provides for a test run. Cannot be tested at all today: NATIONAL / BRAND / WM / DMO / TSO (the admin shell does not admit them), gift images (no uploaded `gift_image` asset UI), channel scope (no ids).
- Unauthenticated smoke that lanes CAN do: `GET /v1/health` (build must read aaab1f217 or newer), `GET /v1/admin/programmes` and `/v1/admin/gifts` answer 401 without a token, web `/login` 200, `/admin/loyalty` 307 to `/login?next=%2Fadmin%2Floyalty`.

### 3. Open items

- **Indexed error pointers in `tiers[]` / `rows[]`:** the server answers FLAT pointers today (`body.label_en`, `body.tier_code`, `body.outlet_id`, `body.valid_from`); the lead reports it to the API lane. The form maps both styles; flat ones go to the banner as `member: message`, indexed ones light the right row (`pointerToField`, `fieldErrorsOf`, `unplacedText`; DECISIONS D-LOY-WEB-05 item 3). When the server indexes them, extend `tests/loyalty-bff.test.ts` "pointers inside tiers[] and rows[]" and the mock together.
- **Seven portal levels (directive 16):** NATIONAL, BRAND, WM, DMO, TSO have no Loyalty page or menu entry; the admin shell admits SUPPORT/ADMIN/SUPERADMIN only (`ADMIN_PORTAL_ROLES`, `ADMIN_PATH_ROLES` in `lib/auth/roles.ts`, `LOYALTY_READ_ROLES` in `lib/admin/loyalty.ts`). The server already scopes their reads (the mock models it). Needs a shell for those roles, role lines, scope-aware empty states.
- **Kit finding (from the lead):** the primary button's red start colour measures 4.33:1 against white, below 4.5:1 for normal text; this is the shared kit's, not loyalty's. axe passes on the loyalty screens today; fix in the kit (token), then re-run `loyalty-quality.spec.ts`.
- **Half done / not built:** scope and product pickers (create sends empty scope; edit keeps scope; detail lists nodes by type and id); gift image upload; outlet picker (outlets by number); multi-row enrolment; Superstar slabs and the campaign gift list; progress views (no operation in 1.13.x: the panel says "not available yet", helpers `guardedPercent`, `remainingStd`, `cashValueMtk` are tested and unused); report rebuilds. Nothing is half-written in the tree; the last pushed head passed the lane CI.
- Mock differences from the server that remain (DECISIONS D-LOY-WEB-05): fixed small geography/products/outlets/assets, no `points_expire_on`/`rules` storage, no audit bodies, own fingerprint, BRAND reach brand-level only.

### 4. Running the gates in a lane container

`cd web && npm ci` (~20 s). Then, with the durations measured in this lane's container:
- `npm run lint` ~20 s; `npx tsc --noEmit` ~25 s; `npx vitest run` ~30 s (162 files, 2108 tests, 1 skipped); `npm run build` ~1.5 to 2 min (the e2e runs the BUILT app, so rebuild after any `src/` change).
- Loyalty e2e alone: `PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test e2e/loyalty.spec.ts e2e/loyalty-quality.spec.ts --trace off` ~2.6 min (21 tests). Full e2e: `... npx playwright test --trace off` ~25 min; expect exactly 5 local failures (config-tiers tiers A/B/C, national-shots route map and sale position: environment-bound, identical on clean INT), everything else green (367 at 53ff6055b). Use `nohup ... &` and poll; do not run two e2e runs on the same ports (`E2E_WEB_PORT`/`E2E_MOCK_PORT` override them).
- Repo gates from the root: `python3 tools/ci/money-float-scan.py` (<1 s, 0 open), `python3 tools/ci/date-pin-scan.py` (<1 s, 0 new), `python3 -m unittest discover -s tools/ci -p "test_*.py"` (~11 s, OK, 9 skipped).
- Lane CI: push the branch only; the `ci` workflow takes ~25 min (`gh run list --branch lane/portal-loyalty-web`, then `gh run view <id> --json status,conclusion`). The lane CI run is the proof for the five local pixel failures.
- Rules that bit before: call `hydrated()` (e2e/helpers.ts) before the first interaction after `goto`; `loginOk` waits for the post-login landing (D-LOY-WEB-02); use the shared `BUDGET` for perf; never skip, weaken or widen a test or timeout; `DECISIONS.md` merges conflict on append (resolve as a union).
