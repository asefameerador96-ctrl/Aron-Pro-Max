# Question (web-dashboard to lead): digit grouping of full money amounts on the web

Filed 2026-10-18 after qa's gate (95 of 200 sample amounts differ between web and phone).

**Facts.** The web formats full amounts (`formatTakaDisplay`, `formatTaka`, `formatMtk`, `formatMtkExact`) through Intl: Western grouping in en (`912,669.05`), South Asian grouping in bn (`৯,১২,৬৬৯.০৫`). The phone and shared formats use Western grouping in both languages (F-SYS-051: `৯১২,৬৬৯.০৫`). Digits, decimals and rounding already agree.

**What the owner's dashboard screenshot shows** (`docs/evidence/web-dashboard-reference-2026-10-17.md`): only compact lakh/crore figures (`3.61 Cr`, `65.07 Lakh`, `1.11 Lakh`) and small whole counts (`28,067`, `1,051`). It never shows a full amount of a lakh or more, so it does not decide the question. The compact mode on the tiles is separate and stays as it is.

**Proposal.** Align the full-amount formatters to Western grouping in both languages through one formatter (`lib/reports/format.ts`), so a figure reads the same on the phone, the memo, the report and the web, and feed `contract/fixtures/money-format-corpus.json` (on lane/qa, not yet on INT) to vitest. One spelling of the unit word per language (`Cr` in en, `কোটি` in bn; today `unit.crore` is `Cr` and the glossary elsewhere says `Crore`): pick `Cr` unless the lead says otherwise.

**Not done, waiting:** the corpus is not on INT, and the change touches every money test. Say "align" and I do it with the corpus as the test input; say "keep" and the deviation stays documented here.

Already done in the same pass (QA-MNY-04): money splits use BigInt maths (`lib/admin/money.ts`, `price-money.ts`, `lib/dash/lakh.ts`), with `tests/money-bigint.test.ts` covering a value above 2^53.
