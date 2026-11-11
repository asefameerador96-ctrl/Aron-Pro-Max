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

## Limits and next-train notes (lead rulings, 2026-11-11)

- Limit: the list search is client-side over up to 10 pages of 100 (listOffers has no q); acceptable at pilot size.
- Empty product rows mean ALL products (lead ruling 2026-11-11 11:33 UTC, the brief's Q9 default on BRAND readers; Q9 itself, who else reads, is open): the form allows zero rows with an explicit warning; detail shows "All products". See D-DISC-WEB-02.
- Audit entity `offer`, actions create, new_version, activate, end (confirmed by Lane A); the mock uses the same.
- Next train: remove the AdminModule test exception and restore the type-level check once contract 1.12.1 (train 93) is on INT; switch to the real routes. (The five pixel-snapshot failures are proven pre-existing, see the baseline line.)
- Baseline (clean INT 34bef94a1, own worktree, specs config-tiers + national-shots, 2026-11-11): the same five pixel-snapshot tests fail there (config-tiers tiers A/B/C, national-shots route-map and sale-position; 5 failed, 4 passed). They are pre-existing in this container, not caused by this lane.

## Train 2 fixes (lead verdict on 03c59a6ac, Opus FAIL on the edit round-trip)

- No truncation: a quantity that is not a whole number of packs opens in base units (`OfferForm.inBase`), with the packs + remainder shown beside it; the PATCH sends only the members the user changed (`diffPatch` against `ruleOf(offer)`), so an unchanged quantity or amount is never re-sent through a conversion; nothing changed gives no request.
- One unit source: `deriveUnits` is used by the edit page (open) and the form (render and save), from the SKU list only; without proven units everything is in base units.
- Changing the products or the reward SKU re-expresses typed amounts and quantities with the same base value (`reunit`).
- Zero product rows = all products (lead ruling 2026-11-11 11:33 UTC, the brief's Q9 default on BRAND readers; D-DISC-WEB-02) with an explicit warning; detail says "All products".
- Tests added: sku_units-absent path, title-only edit byte-identical, truncation (65 on pack 20, reward 10), reunit, request_uuid kept on retry / regenerated on a changed body, status panel render (past valid_to block, Super-Admin-only note), e2e edit never truncates, long Bangla on the edit screen and the open status panel, gating test title fixed. Later: other-type member kept on a title-only edit, scope round-trip, edit-mode empty code, trimmed-title normalisation, reunit clearing (invalid old value, threshold, reward), type-change patches pinned member by member, display words per units rule 5.
- Contract 1.12.1 (merged from Lane A 067cc8c65) lists `discounts` in AdminModule: the type-level `Schemas["AdminModule"]` check is restored and the runtime exception is gone (D-DISC-WEB-03).
- E2E at head b563c1340 plus this note: full suite 340 passed, 5 failed (the five pre-existing pixel-snapshot specs only); e2e/discounts.spec.ts 12 of 12 inside that run. Unit: 2003 passed.
- E2E at the train 3 head (merge of Lane A 067cc8c65 + 1.12.1 vocabulary): full suite 339 passed, 6 failed = the five pre-existing pixel-snapshot specs plus e2e/v13-followup.spec.ts:43 (a visibility timeout under load; passes when run alone, 1 of 1). Unit: 2006 passed.
