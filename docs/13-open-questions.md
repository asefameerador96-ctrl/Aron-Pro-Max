# 13 — Decisions and Open Questions

Split into decisions to lock before/early in the build, and questions whose answers can arrive as their module comes up. Keep answers in the repo's `DECISIONS.md` as they land.

## Lock early (they shape the whole build)

1. **Mobile framework** — recommend **Flutter** (current apps are Flutter; least cutover retraining; one codebase for 3 roles). Alternatives: React Native (reuses team JS/TS), native Kotlin.
2. **Backend stack** — recommend **Node.js + TypeScript + PostgreSQL**. Alternative: .NET + PostgreSQL.
3. **Hosting specifics** — Azure services confirmed: App Service vs Container Apps; Azure Database for PostgreSQL tier; Blob Storage; GitHub Actions. (Board already set Azure + GitHub Actions.)
4. **Auth** — JWT access + refresh with role+scope in the token; device binding via TSO OTP. Confirm token lifetimes and refresh policy.
5. **Memo numbering across cutover** — continue the Apsis series, or restart with a device/zone prefix? (Retailer-facing; affects the printed memo.)

## Confirm during the relevant module

6. **Geo-triggered volume suggestion** — exact inputs and formula. Reconstruct from migrated history if Apsis won't share. (Blocks only the suggestion feature, not sale entry.)
7. **Geofence radius values** per territory, and behaviour offline when an outlet has no saved location.
8. **Units** — STD vs STT naming; bidi unit; lighters in pieces (web) vs boxes (TSO app). Needed for correct KPI math.
9. **BSR denominator** — total memos or target outlets?
10. **`retention`** — what `total_retention_*` on the dashboard means.
11. **After final submit** — can anything change, and who can reopen a day?
12. **Target split** — how monthly targets split to route and SKU; the approval levels for a revision.
13. **Programs live now** — which of Astha / Diamond League / Superstar are active; where Astha gift choices are set (the "TSO portal"); the promotion catalogue.
14. **TSO product scope** — does the TSO role span product lines beyond tobacco (the "Digonto"/paan-masala assets in the current app)?
15. **Microphone permission** — the current AMO build requests it and there's a `voicerecording` page; confirm whether call audio is recorded and whether to keep it.
16. **Roles beyond the apps** — DMO / Wing Manager / Top Management: web-only? Confirm their exact views and any approvals they own (DMO approves TSO leave).
17. **Wholesale / distributor flows** — the current build has `bex` order pages and back-margin commission letters; confirm whether distribution houses use any Aron screens we must rebuild.
18. **Data dump contents** — confirm it includes photos and raw GPS fixes, not just tabular sales.

## Things we deliberately changed from Apsis (confirm acceptable)

- Scope resolved server-side (client never sends zone IDs).
- Reads from aggregates, not client-side aggregation of 400 KB payloads.
- One role-aware app codebase instead of three separate apps (if Flutter is kept) — or keep three builds if the business prefers separate install artefacts.
- Anti-spoofing surfaced as a supervisor-visible flag (optionally a hard block for mock GPS).
- App size and battery budgets enforced (`docs/04`).
