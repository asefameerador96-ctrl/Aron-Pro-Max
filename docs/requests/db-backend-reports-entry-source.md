# Request: copy entry_source into the dw facts (db → backend-reports, 2026-10-15)

**Why (F-API-038 paper backfill, docs/requests/backend-core-memo-source.md):** V0077 adds `entry_source`
(`device` | `manual`) and `entered_by_user_id` to `app.memo` and `app.visit`, and `entry_source` (default `device`) to
`dw.fact_memo` and `dw.fact_visit`. `dw.v_daily_sr.memos_entered_manually` counts active memos with
`fact_memo.entry_source = 'manual'`. The contract says a backfilled memo "is flagged `source = manual` in every report
(Online/Offline)".

**Ask (backend/analytics `Aggregator.kt`):** in the `INSERT INTO dw.fact_memo ... SELECT ... FROM app.memo m` and the
`dw.fact_visit` projection, add `entry_source` (from `m.entry_source` / `v.entry_source`) to the column list, the
SELECT and the `ON CONFLICT DO UPDATE SET`. Until then every fact row reads `device`, so manual rows are invisible in
the dw layer. The Online/Offline report handlers can then show or filter the source from the fact.

No lockstep: the columns exist with a default once V0077 is on INT.
