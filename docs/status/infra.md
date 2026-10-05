# Infra lane status

## Done

- **Lead request (CI concurrency), 2026-10-05.** `.github/workflows/ci.yml`: push runs are grouped by commit SHA and
  never cancelled; only `pull_request` runs are grouped by ref and cancel their predecessor. A dependency-free `changes`
  job (`git diff` against `github.event.before` / the PR base) gates the contract, JVM and Android jobs, so a docs-only
  push builds nothing heavy; manual dispatch, a new branch or an unreachable base runs everything. The Android job
  uploads the three debug APKs (`aron-debug-apks-<sha>`) on every successful run. Checked with actionlint 1.7.7 +
  shellcheck 0.10.0 and the filter script exercised locally against five cases.

## In progress

- N-012 Azure infrastructure as code (Bicep).
- N-013 CI/CD (deploy workflow).

## Blocked

- Nothing.

## Requests filed

- None yet.
