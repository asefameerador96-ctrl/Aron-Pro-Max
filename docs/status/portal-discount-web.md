# Lane portal-discount-web (Lane B): status

Branch `lane/portal-discount-web` from INT `claude/wonderful-thompson-k6ejnf` (34bef94a1). Records: `DECISIONS.md` D-DISC-WEB-01 and later.
Brief: `docs/status/portal-discount-brief.md` (on `lane/lead-docs-1022`), sections 4, 6, 7, 9. Contract 1.12.0.

## Done (train 1)

- Module `discounts` (GRANT_MODULES, MODULE_KEYS, API route map, page map), menu entry, path-role entries; role matrix in `web/src/lib/admin/offers.ts`.
- Screens: list, detail (versions, audit trail), create draft, edit draft, status change (activate / end) with mandatory reason and `request_uuid`.
- BFF routes (create, update with If-Match, status) with strict zod re-validation.
- Integer-only money / percent / quantity helpers; field-unit entry and display with the base figure.
- bn + en strings (`messages-discounts.ts`); empty, error, offline, loading and long-Bangla states.
- Mock `web/mock/offers.ts` for the six operations; users `mdisc1` (ADMIN, discounts r).

## Tested

- Unit: `tests/offers.test.ts` (role matrix, module wiring, menu, integer helpers, form to contract, display), `tests/offers-bff.test.ts` (create / replay / conflicts / grants / update / status / reads against the mock).
- e2e: `e2e/discounts.spec.ts` (gating by role and grant, list filters, detail, create-edit-activate-end journey, ADMIN cannot activate, server failure banner, long Bangla at phone and laptop width).
- Gate results are in the train report to the lead.

## Not done

- Switch from the mock to Lane A's real routes (later train, when they are on INT).
- Read view for NATIONAL / BRAND / WM / DMO / TSO (owner question Q9); one constant plus one path-role line.
- Vendor-parity look (no screenshots exist for this portal; no parity claim).
- axe accessibility e2e and a perf-budget check for the new screens.
- Pickers load whole reference lists (pilot size); a server search would be needed at fleet size.
- Bundle programme type (Q1), `drp_slide`, `channel` / `sub_channel` scope.
- `AdminModule` contract enum gains `discounts` with Lane A train 2; until then the user-access editor can offer a module the server refuses.

## HANDOVER

- Start: read `DECISIONS.md` D-DISC-WEB-01, this file, `web/src/lib/admin/offers.ts` (all rules), `web/mock/offers.ts` (what the mock models).
- Switching to the real API: delete the `handleOffers` line in `mock/server.ts` only for e2e runs against a real backend; the pages and BFF already call the contract paths through `apiClient`. Re-check the audit entity name (`offer`) and the `discounts` module name with Lane A.
- Run: `cd web && npm ci && npm run lint && npm run typecheck && npm test && npm run build && PW_CHROMIUM_PATH=/opt/pw-browsers/chromium npx playwright test e2e/discounts.spec.ts --trace off`.
