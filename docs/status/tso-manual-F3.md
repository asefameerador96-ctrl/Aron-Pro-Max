# tso-manual F3 foundation-rules: lane status

Lane F3 of `docs/requests/tso-manual-build-2026-10.md` (s4 formulas, s5 F3). Branch `tso-man/F3`, no pull request.
Written 2026-10-29.

## Done

Every item of the lane: s4.1 primitives (DASH-14, T-05, T-06, T-07, T-09, T-12 pure part, TK-07, ticket age) and the
corpus. Integer arithmetic only (Long, checked multiply and add, one rounding per figure); no Double or Float in any
path (money-float-scan green).

| file (shared/rules/src/commonMain/.../rules/) | what |
|---|---|
| `Percent.kt` | `whole`, `tenths`, `bp` (d = 0 -> null), `text(v, decimals, locale)`, `wholeText`, `tenthsText`, `bpText`, `DASH` |
| `Qty.kt` | `tenths(n, d)`, `text(n, locale)`, `text(n, d, locale)`: one decimal half up, " K" from 1,000.0 rounded once from n, grouped |
| `Targets.kt` | `tillDate(T, e, n)` (e clamped, n = 0 -> null), `remaining`, `band` + `Bands` (`cfg.kpi.bar_bands`), `cardPct` (capped 100), `detailsBp` (uncapped), `dailyRingTenths(A, T, W)`, `TillDateBasis` (`cfg.kpi.tilldate_basis.*`), `days(basis, month, D, cal)` -> `TillDateDays(elapsed, period)` (past month e = n, future e = 0) |
| `WorkingDays.kt` | `month(ym, weekend, off, makeup)`, `elapsed(D, ...)` (contract signatures), `resolve(NodePath, weekend, marks)` -> `NodeCalendar` (path holidays and make-up days), `weekendOf(cfg list)`, `CalendarLevel` / `DayKind` from the `app.calendar_holiday` wire values |
| `TicketMath.kt` | `counts(statuses)`, `counts(cached, unsentUuids)` (offline merge by UUID), `TicketCounts` (open, resolved % and open %, 0 when total = 0), `age` / `ageText(now, captured, locale)` |
| `Formats.kt` | two one-line delegates only: `qty`, `percent` |

Tests (commonTest): `PercentTest`, `QtyTest`, `TargetsTest`, `WorkingDaysTest`, `TicketMathTest` (every s4.2 to s4.4
fixture, the 29/1186 double-rounding case, the K boundary, Bengali digits, d = 0 -> null and "—", the contract's Long
bound and overflow throwing), `KpiFormatCorpusTest` (reads the corpus; file read in jvmTest `KpiCorpusSource.kt`).

Corpus: `tools/ci/kpi-format-corpus.py` writes `contract/fixtures/kpi-format-corpus.json`: 300 seeded cases plus 81
contract fixtures, en and bn. The oracle is independent (Python `Fraction`, rounded half up, no shared code) and the
generator refuses to write or pass `--check` unless it reproduces every number the contract's s4 tables state (128
checks). Mutation check done: one corrupted expectation fails both `--check` and `KpiFormatCorpusTest`, and the test
task re-runs (the corpus is a declared test input).

## For the consuming lanes

- **B2:** `WorkingCalendarRepo` loads the live `app.calendar_holiday` rows as `CalendarMark(date,
  CalendarLevel.fromWire(scope_type), scope_id, DayKind.fromWire(kind))`, the node's `NodePath` (ids down to the node's
  level, null below) and `WorkingDays.weekendOf(cfg.calendar.weekend_days)`, then calls `WorkingDays.resolve(...)` and
  `month` / `elapsed`. Till-date: `Targets.days(TillDateBasis.fromWire(cfg), month, D, cal)` then `Targets.tillDate` per
  item row; a card is the SUM of its item rows. Note `backend/sync` already has its own `object WorkingDays`
  (ingest backdate window, same package wins); import the rules one with an alias there if ever needed.
- **A1:** formatting only through `Percent.text` / `Qty.text` (or `Formats.percent` / `Formats.qty`); cards
  `Targets.cardPct` + `Targets.band`, details `Targets.detailsBp` printed with 2 decimals, sales ring
  `Targets.dailyRingTenths`, Total Target `Qty.text(T, W, locale)`.
- **A2:** `TicketMath.counts(cached, unsentUuids)` offline, `TicketCounts.resolvedPctText` / `openPctText`,
  `TicketMath.ageText(trustedNow, capturedAt, locale)` (or `age()` to map to string resources).
- **W1:** `web/tests/kpi-format-corpus.test.ts` reads the sections documented in the generator's header (percent, qty,
  sales_card, tilldate, target_card, details, remaining, band, working_days, ticket_counts, ticket_age). Every integer
  in the file is a safe JS integer; the largest percent intermediate (20000 n + d) stays below 2^53 for the contract
  bound n <= 3.1e11, so plain numbers suffice there.

## Decisions (the contract was silent; smallest correct choice)

1. **D-F3-01 Percent grouping.** `Percent.text` groups the whole part with "," like `Formats.integer` (F-SYS-051 Western
   grouping) and the web's `pctBp`: 12345 tenths -> "1,234.5%". Only uncapped figures above 999% are affected.
2. **D-F3-02 Domain errors.** A negative part, whole, quantity or divisor throws `IllegalArgumentException`; an overflow
   throws `ArithmeticException` (never wraps), as `Money`. The contract's bound (A <= 10^10, W <= 31) is tested to fit.
3. **D-F3-03 Ring with W = 0.** `Targets.dailyRingTenths` is null (prints "—") when W = 0 or T = 0, matching Total Target
   `Qty.text(T, 0)` = "—", instead of the literal tenths(A x 0, T) = 0.0%.
4. **D-F3-04 Path resolution.** Per date the most specific declaration on the node's path decides (zone over territory
   over division over wing over global), as `DayPlan.isSellingDay` plans route-days; at equal level an off day wins.
   The set form `month(ym, weekend, off, makeup)` lets a make-up day win an overlap, which is `dw.v_calendar`'s rule;
   for global scope (one declaration per date, unique index `calendar_holiday_one`) both agree.
5. **D-F3-05 Lenient config readers.** `Bands.of` falls back to [40, 80] for a missing or malformed value;
   `TillDateBasis.fromWire` falls back to calendar_days; `weekendOf(null)` is Friday and `weekendOf([])` no weekend (as
   the national dashboard reads `cfg.calendar.weekend_days`).
6. **D-F3-06 Tickets.** A status code this version does not know counts in Total only. Offline merge: UUIDs compare
   case-insensitively; an unsent ticket already in the cache counts once with the cached status (lost ack); a capture
   time after the trusted now is "just now"; an extreme difference saturates instead of wrapping.
7. **D-F3-07 Bangla age text.** No screenshot shows it: "এইমাত্র", "N মিনিট আগে", "N ঘণ্টা আগে", "N দিন আগে" with Bengali
   digits (en "just now", "Nm ago", "Nh ago", "Nd ago" as screenshots 11 to 14).
8. **D-F3-08 Test placement.** `KpiFormatCorpusTest` is in commonTest as the lane text says; reading a file needs the
   JVM, so the reader is an `expect fun` with its `actual` in jvmTest (`KpiCorpusSource.kt`), and the JSON is read by a
   small integer-only reader in the test (shared:rules has no JSON library; adding one would change the lockfile).

## Deviations

1. **`shared/rules/build.gradle.kts` (not in F3's owned list):** one appended block, marked `// tso-manual F3`, declaring
   the corpus as an input of the module's test tasks. Reason: the build cache is on (`org.gradle.caching=true`), so
   without it a corpus-only change would be served a cached green test. Same pattern as `android/feature-tso` and
   `backend/sync` for the money corpus. No other lane touches the file.
2. **Contract s4.3 "working basis (switch check)" 40999 -> 33115** is `Targets.tillDate` on the card's monthly total.
   The card figure per D-TSO-MAN-05 (sum of item rows) is 33114 at 21/26 (32799 at 20/25, equal). Both are pinned in
   `TargetsTest` and the corpus; B2 and A1 should assert 33114 for a card built from the eight Cigarette items.
3. **Base:** this worktree started at `eb7b8eb5` (train 54), which does not contain the contract commit `5eece4ec`; the
   contract was read from that commit. No merge was made, so the branch carries only F3's files.

## Asks

1. **CI (owner of `.github/workflows/ci.yml`):** add `python3 tools/ci/kpi-format-corpus.py` next to
   `money-format-corpus.py` in the "Money gates" step, so a stale corpus fails CI (`--check` is the default).
2. **F1 / B2:** the contract has `period_days` 1..31, but on the working basis a month whose every day is off gives
   n = 0 (`Targets.tillDate` null). Either allow 0 or send null till-date figures for that month.
3. **Owner (with AKTCL's Bangla labels, s7.1 Q5):** confirm the Bangla age wording of D-F3-07.

## Not done

Nothing of the lane is left. The consumers (B2 WorkingCalendarRepo and parity tests, A1 and A2 screens, W1 web test)
are other lanes.

## Verify

- `gl.sh :shared:rules:allTests` and `:shared:rules:jvmTest`: see the lane's final report for the exact run.
- `python tools/ci/kpi-format-corpus.py --check`: up to date.
