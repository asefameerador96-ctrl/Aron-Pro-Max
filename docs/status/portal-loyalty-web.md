# Lane portal-loyalty-web (Lane L-B): status

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

- Unit: `tests/loyalty.test.ts` (role matrix, module wiring, menu, exact numbers, form to wire body, display), `tests/loyalty-bff.test.ts` (create / replay / conflicts / If-Match / status / enrolments / gifts / grants against the mock).
- e2e: `e2e/loyalty.spec.ts` (12: gating by role and grant, list filters, detail, create-edit-activate-end journey, enrol, ADMIN cannot activate, gifts, server failure banner, long Bangla phone and laptop), `e2e/loyalty-quality.spec.ts` (axe light/dark/bn on ten screens and three action states; perf BUDGET on the list).
- Gates at the train 1 head: lint 0, typecheck 0, vitest 2058 passed (1 skipped, i18n parity included), build 0, money-float-scan 0 open, tools/ci test_gates OK (60 tests). e2e: loyalty.spec + loyalty-quality.spec alone 3 of 3 green (18 each); full suite 364 passed, 5 failed = the five known pixel-snapshot specs only (config-tiers tiers A/B/C, national-shots route map and sale position). Perf (Fast 3G, 4x CPU, 360 px): JS 236 KB, LCP 1.4 s, CLS 0.01 against BUDGET 300 KB / 3500 ms / 0.1.

## Not done

- Switch from `rawRequest` and local types to the typed `apiClient` once contract 1.13.0 is on INT; align the ASSUMED members (D-LOY-WEB-01); drop the pending-module exception in `tests/tso-integration-web.test.tsx` (it removes itself, then restore the plain typed check).
- Read view for NATIONAL / BRAND / WM / DMO / TSO (Q6): `LOYALTY_READ_ROLES` plus one `ADMIN_PATH_ROLES` line; the admin shell is for the portal roles today.
- Product and geography scope pickers, gift image upload, outlet picker, multi-row enrolment, Superstar slabs / campaign gift list, report rebuilds (all per brief s5 NOT-in-v1 or owner questions).
- Authenticated pass on the dev server (no pilot login for lanes; the screens are verified against the mock only).

## HANDOVER

- Start: read `DECISIONS.md` D-LOY-WEB-01, this file, `web/src/lib/admin/loyalty.ts` (all rules), `web/mock/loyalty.ts` (what the mock models).
- Switching to the real API: the loaders (`lib/admin/loyalty-load.ts`) and BFF (`lib/admin/loyalty-server.ts`) call contract paths through `rawRequest`; replace with `apiClient` typed calls and delete the local wire types when `openapi.d.ts` has them. The mock is only the e2e/unit base URL target.
- Run: `cd web && npm ci && npm run lint && npm run typecheck && npm test && npm run build && PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test e2e/loyalty.spec.ts e2e/loyalty-quality.spec.ts --trace off`.
