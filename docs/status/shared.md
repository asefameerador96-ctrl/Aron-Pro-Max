# Status: lane shared (Day 1)

## Done (checker found 3 defects, all fixed with tests in RulesCheckerTest)
- **N-003** shared rules v1: `Money` (checked arithmetic, `formatTaka`), `Quantity` (units, pack badge), `MemoMath` (totals, settle, verify), `DiscountLine`/`QcLine`. Golden vectors from docs/ui-reference/sr/ and docs/24 s7.4 are tests; oracle and shuffle property tests.
- **N-004** geofence maths: `Geo.haversineM` (20 independent reference pairs, `shared/rules/reference/haversine_reference.py`), `RadiusResolver`, `GeoVerdicts.verdict` (s11.2 order), teleport and route-single-point helpers.
- **F-SYS-017** business date: `BusinessDate.of`, `TrustedClock`, `BusinessDateRules.reconcile/classify` (s3.8).
- **N-002**: nothing left on the shared Kotlin side (mirrors and drift tests exist; TypeScript generation belongs to web).

## In progress / blocked
- Nothing blocked. Config key `cfg.geo.*` bounds are applied by the caller (`Geo.clampRadius`).

## Decisions taken (shared lane)
- `HOUSE` scope kept in `ConfigScope` at precedence 60 (between geo_class and territory) because the task lists it; docs/24 s9.2 says it is unused in Phase 1, so no row exists for it.
- A fix with no or non-finite `accuracy_m` is `accuracy_too_low` (never proof). A fix with NaN/out-of-range coordinates is `no_fix`.
- Mock check precedes the outlet-has-no-location check (s11.2 order), so a mocked fix never validates even at an unlocated outlet.
- Negative net is reported by `MemoMath.verify` unless gross is 0 (zero sale with QC credit, s7.4).
- `isRouteSinglePoint` uses the coordinate-wise median as "their median point"; percentages use integer arithmetic.
- `BusinessDateRules.classify` treats "future" as `captured_at` more than 10 min ahead of server time.
- Rules enums (`GeoVerdict`, `GeoAction`, ...) live in `shared:rules` with wire names from docs/24 s11.2; they are not yet cross-checked against `contract/openapi.yaml` (see below).

## Requests filed
- none

## Environment note
- Maven Central answered 429; used the mirror init script of docs/24-build-spec-verification.md s9 in `~/.gradle/init.d` (not committed).
- Reading `contract/openapi.yaml` for the verdict/action enum names was blocked by the session's permission classifier, so those wire names come from docs/24 only; the next session should diff them against the contract.

## Day 2 (2026-10-06)
- Done: F-SYS-045 (price snapshot on `MemoLine`), F-SYS-051 (`Formats`), F-SYS-070 (`TextRules`). Checker findings fixed; tests in Day2CheckerTest.
- Loyalty (`LoyaltyMath`) marked UNUSED per docs/27, not extended.
- Decisions: taka sign after the amount with a space (row ACCEPT; docs/24 s7.1 shows it before: needs a ruling); dates dd/MM/yyyy and 24-hour times (docs/15 mention ISO and 12-hour: needs a ruling); phones must be 01[3-9]XXXXXXXX; NFC covers Bangla only; `lowercase()` depends on the platform Unicode tables.
- Requests filed: docs/requests/shared-backlog-coverage-table.md (BacklogCoverageTest red after docs/27; contract lane).
- Not started: docs/requests/android-core-contract-dtos.md (needs reading contract/openapi.yaml, blocked by the session classifier earlier).
