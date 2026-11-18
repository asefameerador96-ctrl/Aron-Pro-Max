# Android small follow-up rounds, lead check, 2026-11-18

Base INT 2c7ff3c1bd. Two Opus lenses per round (correctness; gate and safety); each blocking finding checked by three Opus refuters. Reviewers read the code; they did not run the tests.

## lane/android-gates-2 at 337e2e79 — does not land yet

Production changes correct (AMO decide/outlet request gate before fix read and photo claim; attendance busy flag; sale replay compares stored lines).

BLOCKING (upheld 3/3): the rewrite of `EveryWriterIsGatedTest` deleted `theWritersOutsideTheRepositoryAreGatedToo`, the two order assertions that the gate call precedes the force-photo claim and `recordVisitOpen` in the visit committer lambdas of `SrDay.kt` and `AmoDay.kt`. The new per-function scan cuts at `fun` heads and looks for a transaction or outbox insert, so it never reaches those lambdas. Moving the gate below the photo claim would pass every test. Fix: restore the assertions or extend the scan; prove with a mutation.

Lows: replay line read happens after the draft is cleared; no test for a lines-only difference with equal totals; AMO replay of a decided request now throws under a closed gate (confirm the block screen is shown); the discovery regex is a heuristic and the wording overstates it; `gatedByCaller` keyed by file and function name only; no stale check for `gatedFiles`; survey replay branch claims photos without a gate (pre-existing).

## lane/android-sr-programmes at eb3b470e7 — PASS

No blocking. Lows: stale FigureRuleB KDoc; the Astha "left out" note over-counts after the fallback change; an edit dated after through_date is left out by the fallback; an uncertain newer gift choice shows the older bundle gift with no hint; Unconfirmed state shares a test tag. The lane answered with d762c3aa1 (all five addressed); the edit-after-through_date change is new logic and gets a short check before landing.
