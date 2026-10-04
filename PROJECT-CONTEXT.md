# PROJECT-CONTEXT — how this package came to be

This file carries the background and decisions from the planning conversation, so a fresh Claude Code session has the "why", not just the "what". Read it after `CLAUDE.md`.

## What this is

The in-house rebuild of **Aron**, AKTCL's field-sales/distribution platform, to replace the current vendor (Apsis) build. **AKTCL owns the Aron IP.** The Board decided to bring it in-house and end the Apsis relationship. The only thing expected from Apsis is a ~8-month data dump.

## How this spec was produced (provenance)

Clean-room, from sources AKTCL legitimately controls:
- the running **web dashboard** (observed under AKTCL's own login) — 37 pages, the API response shapes, the KPIs;
- the four official **user manuals** (Web, SR, AMO, TSO) — every app screen and field;
- the published **product data** (the 42-SKU catalogue, now in `db/seed/`).

No Apsis source code or backend was used, and the apps were **not** decompiled or traffic-sniffed. That was a deliberate choice, not a limitation (next section).

## Deliberate approach decisions

- **Clean-room, not clone.** We reproduce *behaviour and data*, and define our **own** API — we do not copy Apsis's wire protocol. This is both the cleaner engineering path and the stronger position even though AKTCL owns the IP. The authoritative source for internals is the **contractual source-code handover** Apsis owes; chase that through legal, not through reverse-engineering.
- **The current apps are Flutter** (confirmed from the APK contents: Dart AOT-compiled `libapp.so` + `flutter_assets`). Recommendation is to **stay on Flutter** for least cutover retraining across 8,500 reps. (Decision still open — `docs/13`.)
- **Recommended stack:** Flutter app; Node.js + TypeScript + PostgreSQL API; Azure hosting; GitHub Actions. Matches AKTCL's existing skills and the Board's Azure mandate.

## Key architecture decisions (detail in `docs/`)

- **Offline-first** SR app; the network is never in the critical path of a sale (`docs/04`).
- **Idempotent sync** by client-generated UUID — the core correctness rule (`docs/04`).
- **Server-side scope** — the client never sends zone IDs; the token carries reach (fixes the current build's biggest flaw) (`docs/09`).
- **Aggregates for reads** — dashboards read per-date/per-scope fact tables, not the transaction log (fixes the current 400 KB client-side aggregation) (`docs/10`).
- **Geo-validation offline + anti-spoofing** — on-device distance check, server re-check, mock-GPS detection surfaced to supervisors (addresses the known fake-GPS abuse) (`docs/05`).
- **Battery & size budgets** — no continuous GPS, constrained background sync, compressed photos, trimmed APK (addresses the known battery/data-drain pain) (`docs/04`).
- **No-hiccup cutover** — import + reconcile, parallel pilot, waved rollout with rollback (`docs/11`).

## Known pain points in the current system (improve, carefully)

Fake-GPS spoofing; 2 GB/day data + battery drain on shared low-end phones; slow dashboards (client-side aggregation of 400 KB payloads, all 1,051 zone IDs sent from the browser); 11 of 18 reports download-only; a data bug where a negative Astha target produced −37,500% achievement (validate targets ≥ 0, guard divide-by-zero).

## Companion documents (optional to import)

Two human-readable Claude Docs were produced during planning; their substance is already folded into this bundle:
- **Aron System Map** — what the current system does, page by page.
- **Aron Rebuild Blueprint** — the forward design.
If you want them in the repo verbatim, export them to Markdown and drop them in `docs/`.

## Open decisions

See `docs/13-open-questions.md`. The five to lock early: mobile framework, backend stack, Azure specifics, auth token policy, memo-numbering continuity across cutover.
