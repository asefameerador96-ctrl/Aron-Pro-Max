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

## HANDOVER

- Start: read `DECISIONS.md` D-LOY-WEB-03, -04, -05 (today's truth), this file, `web/src/lib/admin/loyalty.ts` (all rules), `web/mock/loyalty.ts` (what the mock models and how it follows the server).
- Calls: every loyalty read (`lib/admin/loyalty-load.ts`) and write (`lib/admin/loyalty-server.ts`, BFF routes under `/api/bff/admin/loyalty`) uses `apiClient` with the generated types, so a contract drift fails tsc. The mock is only the base-URL target of the unit and e2e runs; switching to the live API changes `ARON_API_BASE_URL` and nothing else.
- Run: `cd web && npm ci && npm run lint && npm run typecheck && npm test && npm run build && PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test e2e/loyalty.spec.ts e2e/loyalty-quality.spec.ts --trace off`.
- Rules for the next round: keep the mock equal to `AdminLoyalty.kt` (re-read the file, not the contract, for check order and pointers); never claim equality beyond what a test asserts; the hydrated() rule before the first interaction after goto and the shared `BUDGET` apply to every new e2e.
