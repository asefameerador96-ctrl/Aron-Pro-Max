# CLAUDE.md — Aron Rebuild Agent Brief

You are building the in-house replacement for **Aron**, AKTCL's field-sales platform. AKTCL owns the IP. This is a clean-room rebuild from the specs in `docs/`. Read this file every session and keep it loaded.

## Mission

Ship a system that reproduces everything the current Aron does — SR/AMO/TSO Android apps, web dashboards, one API and database — so AKTCL can switch 8,500 reps off the vendor app **with no disruption to a single day of selling**. Match the current behaviour first; improve second, and only where it does not risk the cutover.

## Environment profiles (read every session)

The Azure subscription and Google project used now are **temporary TEST accounts**: build the full system but run it at **pilot size (5 to 10 users)**, minimal and cheap. A **final** account arrives later and the system is moved there and sized for 8,500 users. Never create fleet-sized resources, request big quotas, or enable zone/geo redundancy, Front Door/WAF or Premium tiers in the test account without the sponsor's written yes. See `docs/28-environment-profiles.md`.

## Sponsor standing rules (decided, do not re-ask, do not drift)

These were stated by the sponsor (one human, the owner) and apply to every session. A later message can change one; until then they hold. The full list with dates is in `DECISIONS.md` and `docs/23`, `docs/27`, `docs/28`.

1. **Test account now, final account later** (`docs/28`). Pilot size, minimal and cheap. No fleet sizing, no quota requests.
2. **Deferred: target, loyalty (Astha, Diamond League, Superstar, gifts) and every discount/promotion programme** (`docs/27`). Keep hooks only; the memo's offer-discount line stays zero.
3. **Out of scope: Apsis data migration and cutover.** Phase 2 portals are later; keep the hooks.
4. **Everything else is in scope, no cuts.** Seven days, ten at most. Native Kotlin apps (SR, AMO, TSO), web dashboard with roles, GUI admin portal for every setting, Ktor backend on Azure with PostgreSQL. Offline-first, anti-spoofing, device owner with scheduled app blocking.
5. **Units:** cigarettes in sticks, lighters in pieces, matches in dozens. Money in integer milli-taka.
6. **Be honest about guarantees:** designed, tested, drilled; never "cannot fail". State exactly where a proof runs (test vs final account).
7. **Do the work yourself where you can.** The owner is the only human: do not hand them manual steps that a lane, the laptop session or a script can do. Use the laptop operator session for laptop-only steps; ask the owner only for what needs their account, their approval or their hands (phones, printer, MFA, billing).
8. **Never print or store secrets.** Signing key, Maps/Firebase keys and Azure identity live in GitHub secrets only.
9. **Before you ask the owner something, check these rules and the docs.** If a rule above already answers it, apply it.

## Non-negotiable constraints

These are hard requirements. Every feature is reviewed against them.

1. **Offline-first.** The SR app must run a full selling day with no connectivity — load the route, visit outlets, geo-validate, sell, print memos, collect dues — and sync later. The network is never in the critical path of a sale. Treat this as the core of the system, not a feature.
2. **Idempotent sync.** Every field record gets a client-generated UUID at creation. The server upserts by that UUID. A retried, duplicated or partial upload must never create a second sale or double a number. This is the single most important correctness rule.
3. **Lightweight and battery-friendly.** SRs use shared, mid/low-end Android phones on a 2GB/day data pack for a full day in the field. The app must not drain the battery or the data pack. See `docs/04` for the explicit budgets. Concretely: no continuous GPS; sample location on demand with balanced-power/fused provider; batch and compress uploads; prefer Wi-Fi for large transfers; compress photos before queueing; no chatty polling; keep the APK lean.
4. **Server-side scope.** A user's data reach (which wing/division/territory/zone/routes they see) is derived on the server from their role and assignment, encoded in their token. The client never sends a list of zone/scope IDs. This fixes the biggest flaw in the current build.
5. **Geo-validation that works offline, with anti-spoofing.** Distance checks run on-device against downloaded outlet coordinates; the server re-checks on sync. Reps already defeat the current geofence with fake-GPS apps — detect mock locations and implausible tracks and surface them. See `docs/05`.
6. **No-hiccup cutover.** The migration imports the Apsis history and runs in parallel before switchover. Same field workflows, same memo format, same logins where possible. See `docs/11`.
7. **Dhaka business date on every transaction.** Store UTC timestamps plus an explicit Asia/Dhaka (UTC+6) business date. "Today's route" and all day-level rollups key off the business date.
8. **Bilingual.** The field apps are Bangla-first with English labels in places (as today). Bundle Bengali fonts; keep all strings in a localization layer.

## Recommended stack (confirm in `docs/13`)

- **Mobile:** Flutter (Dart). Local store: Drift or sqflite. Background sync: WorkManager via `workmanager`. Location: `geolocator` (balanced power, mock-location flag). Bluetooth thermal printing: an ESC/POS plugin (the field printer is an RPP02N-class 58mm).
- **Backend:** Node.js + TypeScript (NestJS or Fastify). PostgreSQL. Prisma or Kysely for data access, with SQL migrations.
- **Hosting:** Azure — App Service/Container Apps (API), Azure Database for PostgreSQL, Blob Storage (photos), GitHub Actions (CI/CD).
- **Web:** Next.js + TypeScript + Tailwind (the team's stack), reading the same aggregates as the apps.

## Monorepo structure to create

```
/api        backend service (REST, the /sync surface, admin)
/web        Next.js dashboards and portals
/app        Flutter app (one codebase, role-aware: SR/AMO/TSO modes)
/db         migrations + seed
/packages   shared types (the API contract, enums, validation)
/infra      IaC + GitHub Actions workflows
```
Keep one shared source of truth for the API contract and enums in `/packages` so the app, web and API can't drift.

## Conventions

- All IDs that originate on a device are UUID v4 generated client-side. Server-assigned surrogate keys are separate.
- Money in integer minor units; quantities in the SKU's own unit (sticks / pieces / dozens — see `docs/10`).
- Timestamps `timestamptz` (UTC) + a `business_date` (date, Asia/Dhaka) column on transactions.
- Migrations are forward-only and checked in. Never edit a shipped migration.
- Every endpoint and every sync path has tests. The sync engine has property/fuzz tests for duplicate and out-of-order uploads.
- Secrets only via environment/Azure Key Vault. Never commit keys.

## Definition of done (per feature)

- Works offline where the spec says so, and survives a kill-and-relaunch mid-operation.
- Sync is idempotent and reconciles (device count == server count).
- Scope enforced server-side; a user cannot read outside their assignment.
- Has tests, including the offline/sync path.
- Meets the battery/size budget in `docs/04` (no new continuous sensors, no uncompressed media).
- Bangla + English strings in the localization layer, not hardcoded.

## How to work

1. Build in the phase order in `docs/12`. Finish the Phase 1 vertical slice (one SR, one sale, offline → sync → one dashboard tile) before widening.
2. Keep a running `DECISIONS.md` at the repo root: every assumption you make, with date and reason. Flag anything in `docs/13` you had to decide yourself.
3. When a spec is ambiguous, prefer the current app's observed behaviour (in `docs/06`–`08`); if still unclear, implement the smallest correct version and log the question.

## Guardrails

- This is a clean-room rebuild of software AKTCL owns. Do not attempt to reach, call, scrape or reverse-engineer Apsis's running backend or app. Build from the specs here.
- The new system defines its **own** API; do not try to match Apsis's wire protocol byte-for-byte.
- No hardcoded secrets. If the Apsis data dump contains credentials or tokens, treat them as data to migrate/rotate, never to reuse against a live service.

---

# Tooling

## gstack (recommended)

This project uses [gstack](https://github.com/garrytan/gstack): Garry Tan's set of
Claude Code skills that act as a virtual engineering team (CEO, eng manager,
designer, reviewer, QA, security officer, release engineer).

Install it once per machine (needs git and [Bun](https://bun.sh)):

```bash
git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack
cd ~/.claude/skills/gstack && ./setup --team
```

Then restart Claude Code. `--team` makes gstack auto-update at the start of each session.
In Claude Code on the web, `.claude/hooks/install-gstack.sh` (a SessionStart hook) installs it
automatically at the start of each session; the log is at `~/.gstack-install.log`.

### Rules

- Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.
- Use `~/.claude/skills/gstack/...` for gstack file paths.

### The sprint: Think → Plan → Build → Review → Test → Ship → Reflect

| Stage   | Skills |
|---------|--------|
| Think   | `/office-hours` (start here), `/spec` |
| Plan    | `/autoplan`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`, `/plan-devex-review` |
| Design  | `/design-consultation`, `/design-shotgun`, `/design-html`, `/design-review`, `/diagram` |
| Review  | `/review`, `/cso` (security), `/codex` (second opinion), `/investigate` (debugging), `/deslop-shared-libs`, `/test-audit` |
| Test    | `/qa`, `/qa-only`, `/browse`, `/scrape`, `/benchmark`, `/devex-review`, `/setup-browser-cookies`, `/connect-chrome` |
| Ship    | `/ship`, `/land-and-deploy`, `/canary`, `/setup-deploy`, `/document-release`, `/document-generate` |
| Reflect | `/retro`, `/learn` |
| Safety  | `/careful`, `/freeze`, `/guard`, `/unfreeze` |
| Other   | `/make-pdf`, `/setup-gbrain`, `/gstack-upgrade` |

## Android and Azure skills

Vendored in `.claude/skills/`, loaded automatically for this repo. Sources and pinned commits:
`.claude/skills/SOURCES.md`. Update with `scripts/update-agent-skills.sh`, then review and commit.

- **Android**: Google's official [android/skills](https://github.com/android/skills) plus `claude-android-ninja`.
  The field app is **Flutter**, so only the platform-level skills apply to it: `play-policy-insights`,
  `android-permissions-security`, `android-intent-security`, `android-profiler`, `r8-analyzer`, `agp-9-upgrade`
  (the Flutter Android host project). The Jetpack Compose / Kotlin-architecture skills (`claude-android-ninja`,
  `navigation-3`, `styles`, `adaptive`, `migrate-xml-views-to-jetpack-compose`) apply only if `docs/13` Q1 picks native Kotlin.
- **Azure**: Microsoft's [azure-skills](https://github.com/microsoft/azure-skills) (`azure-enterprise-infra-planner`,
  `azure-prepare`, `azure-validate`, `azure-deploy`, `azure-reliability`, `azure-diagnostics`, `azure-quotas`, ...)
  plus Microsoft Learn docs skills (`azure-well-architected`, `azure-architecture`, `azure-resiliency`, and per-service guides).
  Design every Azure component against the Well-Architected reliability and performance pillars.
- **MCP servers** (`.mcp.json`): `azure` (Azure MCP Server; needs `az login` for live resources) and
  `microsoftdocs` (Microsoft Learn docs, no sign-in).
