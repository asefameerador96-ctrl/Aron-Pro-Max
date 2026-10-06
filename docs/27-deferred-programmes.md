# 27 — Deferred: target, loyalty and discount/promotion programmes (binding, 2026-10-06)

**Sponsor decision.** Target, loyalty (Astha, Diamond League, Superstar, campaign gifts) and every other discount or promotional programme are set aside. The business rules are still being collected. Later they come back as a logic engine plus portals and dashboards for (a) Target, (b) Discount/Program, (c) Astha. Phase 1 must not block them.

Units are confirmed: cigarettes in sticks, lighters in **pieces**, matches in **dozens**.

## What this means for every lane

1. **Do not build** any row whose decision in `docs/25-build-backlog.csv` is `DEFERRED` (33 rows: Astha, Diamond League, campaign gift photo, outlet eligibility dots, SKU target screens, target approval and upload, offer CRUD, loyalty ledger job, Astha/target/discount reports). `tools/my-rows.py` no longer lists them.
2. **Rows marked `TRIM (docs/27)`** in `scope_note` are built without the programme part (no loyalty tile, no target ring, no targets or offers in the bundle).
3. **Keep the hooks, do not delete them.** Tables already created (programmes, enrolments, gifts, Astha targets, loyalty ledger, targets) stay in the schema, empty and unused. Contract operations and record types for them stay in `contract/openapi.yaml` but are not implemented: a backend route for a deferred operation returns `501 not_implemented` (add no code). Nothing may break if they stay empty.
4. **The money model does not change.** Memo net = gross − offer discount − slide/DRP − QC settlement. The offer discount stays a stored memo component (`sku, qty, value, kind`) that is zero until the discount engine exists. A discount line may still be entered by a later engine; the memo, review and print code must already render and sum it.
5. **Bundle** (`GET /sync/bundle`) returns empty `targets` and `offers` arrays; the fields stay in the contract so the app's parser does not change later.
6. **No UI** for deferred items: no tile, no menu entry, no report key in the picker. Hide, do not grey out.
7. Do not invent business rules for these programmes. If something needs a number (a slab, a threshold), it waits.

## Where it comes back

A Phase 1.5 block after Day 7 (or earlier if time is left): offer/discount engine, target engine, Astha. The hooks above are why it should be additive.
