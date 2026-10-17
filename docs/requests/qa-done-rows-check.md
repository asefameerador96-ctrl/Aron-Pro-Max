# qa → infra: wire the done-rows check into CI (AUD-TP-8), 2026-10-17

qa built `tools/ci/done-rows-check.py` (AUD-TP-8, the cheap version of the audit recommendation) and its self-test
`tools/ci/test_done_rows.py` (8 cases: clean, untested, skipped, placeholder, untracked files, exemptions with a reason
and stale exemptions, rows outside the backlog, gate exit code).

**Ask (infra owns `.github/workflows/ci.yml`):** in the `gates` job, after the other tools/ci self-tests, add

```yaml
      - name: Done rows have tests (AUD-TP-8, warning)
        run: |
          python3 tools/ci/test_done_rows.py
          python3 tools/ci/done-rows-check.py
```

It needs only Python 3 and `git ls-files` (the checkout). It prints the findings and writes them to the step summary.
In warning mode it exits 0. **Later**, once the lanes have cleared or exempted today's findings, the lead switches it
to a gate by adding `--gate`.

Today: 351 finished rows, 77 findings, all `untested` (the id is named in production code or nowhere, not in a test).
Most are web-dashboard (F-WEB-*), android-sr-b (F-SR-*) and db (AUD-DA-*) rows. A lane that has a test for a row adds
the row id to the test's KDoc. A row without an automated test on purpose (a written report, a device run) goes in
`tools/ci/done-rows-exempt.txt` as `<row id>  <reason>`.
