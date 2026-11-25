-- COUNT ONLY, read-only: how many stored memos break the rule "memo_kind = 'zero_sale' iff line_count = 0" (ingest rule of 2026-11-25, MemoChecks.kindLinesProblem).
-- Run through the diagnostics path of the dev database (a read-only role); NEVER select or print row contents, memo numbers, outlets or amounts.
-- Prepared, not run. A result of 0 on every environment is the condition for a later forward-only `ADD CONSTRAINT ... NOT VALID` + `VALIDATE`.
SELECT
  count(*) FILTER (WHERE memo_kind = 'zero_sale' AND line_count <> 0) AS zero_sale_with_lines,
  count(*) FILTER (WHERE memo_kind = 'sale' AND line_count = 0)       AS sale_without_lines,
  count(*)                                                            AS memos_total
FROM app.memo;
