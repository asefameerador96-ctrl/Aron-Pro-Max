# qa → several lanes: money correctness gate findings (docs/31), 2026-10-18

Source: the qa money gate (lane/qa dcacec1a). It has:
- property tests over `shared:rules`, checked against an independent BigInteger oracle (D-19 single rounding, D-18
  sign, QC, units);
- `MoneyIngestOracleTest` through the real `/v1/sync/batch`;
- the phone review calculator, checked against the oracle;
- a 200-amount format corpus (`contract/fixtures/money-format-corpus.json`) read by the web, shared/backend and
  android tests;
- the no-float scan `tools/ci/money-float-scan.py` with a shrink-only allow-list.

The CI step "Money gates" (Repository gates job) runs the scan, the corpus check and their self-tests.

**Clean:**
- no per-line rounding anywhere;
- a replay never changes an amount;
- phone, shared rules and stored server values agree with the oracle;
- the shared Kotlin formatters match the corpus completely.

Routing is the lead's (2026-10-18 01:08Z). Every repro stays `@Disabled` or `@Ignore` until its owner's fix lands,
and the fix commit enables it.

| Id | Severity | Owner lane | Finding | Repro test | Status |
|---|---|---|---|---|---|
| QA-MNY-01 | high (D-18 hole) | lead-backend (first after the openapi.d.ts regen) | The net's sign is checked only when a memo and all its children arrive in one batch. With the header first and the children later, a sale with gross 9,200 and net −30,480 is accepted with no flag: `MemoChecks.recordMismatch` never checks the sign, and `afterChildStored` compares sums only. | `MoneyIngestOracleTest.aSplitFamilyWithANegativeNetAndAGrossIsNotStoredSilently` (`@Disabled`) | routed |
| QA-MNY-05 | high (D-18 on the phone) | android-tso (owns the QC and zero-sale code) | A sale whose QC exceeds its gross (gross 12,500, net −27,500) has `canCommit == true` when no QC cap is configured (the default). The phone prints the memo and the server then quarantines it. Ruling: gross > 0 with net < 0 is blocked on the phone by the server's rule; a zero sale with QC stays a credit; the cap comes from the bundle when configured. | `MoneyGateReviewTest.aSaleWithQcAboveItsGrossIsNotCommittable` (`@Ignore`) | **fixed on lane/android-tso, not yet on INT** (`ReviewProblem.NetNegative`, and `completeQc` refuses it too; qa's repro ran on an older head). When that head is on INT, qa re-runs the repro, removes the `@Ignore`, and adds the boundaries to the oracle: 4 mtk over gross gives net 0 and is allowed (rounded once half up); 5 mtk over gives net −10 and is blocked; gross == QC gives net 0 and is allowed; zero sale + QC is a credit. |
| QA-MNY-02 | medium (pending D-34 / MQ-03) | lead-backend, **flag only** | QC settlement is never checked against qty_base × unit_price_mtk at capture: 19.400 Tk for 2 sticks at 9.200 is accepted and deducted. Until MQ-03 is answered: a risk signal, no quarantine, no rejection. The formula is inferred from one example. **Note for the fix:** `QcLinePayload` has no `price_per_qty`, and the phone sends `unit_price_mtk` per `pricePerQty` (pack price). A server recompute of qty × unit_price would false-flag every pack-priced QC line, so the fix needs `price_per_qty` in the contract or a lookup of the price snapshot. | `MoneyIngestOracleTest.aQcSettlementThatIsNotDefectTimesPriceIsFlagged` (`@Disabled`) | routed |
| QA-MNY-04 | medium (docs/24 s7.1) | web-dashboard | `lib/admin/money.ts`, `lib/admin/price-money.ts` and `lib/dash/lakh.ts` divide milli-taka as JS doubles (`abs / 1000`, `(abs % 1000) / 10`, `abs / 1e7`, `Math.round(mtk / 1000)`). They are correct for safe integers today, but float on money is forbidden. Use BigInt or strings as `lib/reports/format.ts` does. | `tools/ci/money-float-allow.txt` (7 `finding` entries, one per line, each matching exactly one line; the list's CEILING is 26 and may only shrink) | **fixed** by web-dashboard 4857fe41 (admin/money.ts, price-money.ts) and 2d2bda72 (lib/dash/lakh.ts): all 8 allow entries deleted, CEILING 18. |
| QA-MNY-06 | medium (F-SYS-051) | web-dashboard | `formatTakaDisplay`, `formatTaka` (reports), `formatMtk` and `formatMtkExact` group bn amounts the South Asian way through Intl `bn-BD` (৯,১২,৬৬,৯০৫.৪২); the phone and shared `Formats` group the Western way (৯১,২৬৬,৯০৫.৪২). 95 of 200 bn amounts differ; digits, rounding and sign agree. `formatLakhCrore` also differs from 1,000 crore up. Ruling: Western grouping per F-SYS-051, unless the owner's dashboard screenshot shows lakh/crore for full amounts (then it is logged as a question). | `web/tests/money-format-corpus.test.ts` (KNOWN list, shrink-only) | **fixed** by web-dashboard 32adcf33 (Western grouping); its 6 KNOWN pairs are deleted and KNOWN_CEILING is 0 (2026-10-18). |
| QA-MNY-07 | medium (F-SYS-051, docs/15) | android-core (when released) | `LocaleDigits.formatInteger` (core-common, used by `localizedNumber` across the SR, AMO and TSO apps) groups numbers the South Asian way (12,34,567) in both locales, against F-SYS-051, docs/15 ("no lakh grouping unless AKTCL asks") and shared `Formats.integer`. | none yet (write one with the fix) | open, no owner free |
| QA-MNY-08 | low (number format, not money) | android-tso | `TsoDashboards.compact` (quantity cards only) does not group the crore figure from 1,000 crore up ("1234.57" against "1,234.57"). Mostly theoretical, since it is used for quantities. The English crore word is "Crore" on Android and "Cr" on the web. | `TsoCompactCorpusTest` (`CRORE_UNGROUPED` counter) | **fixed on lane/android-tso, not yet on INT**: d124df7 fixes the grouping and the English crore word; 95dae87d empties the known list (ceiling 0) and passes against the corpus with 0 differences (2026-10-18). |
| QA-MNY-03 | low | lead-backend (last in line) | `ReportOutputs.kt:50`: an Excel cell of type `mtk` that is not an integer string falls back to `toDouble()`. An xlsx numeric cell is a double anyway, so accept it with a guard: above 2^53, write it as text. | `tools/ci/money-float-allow.txt` (`finding` entry) | routed |

**Pending on another lane:** the TSO QC-only path (D-18) is not built yet. Only the zero-sale memo with a QC credit is
tested; it passes on the phone and the server. The gate is extended when the path lands.

**Closing a row:** the owner's fix enables the repro (or deletes the KNOWN or `finding` line) in the same commit;
qa re-runs the gate and marks the row fixed here.
