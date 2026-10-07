# Lane brief: infra

Session model: **Opus** (docs/29 s3). Owns: `infra/` (Bicep, parameter files, deploy scripts), `.github/workflows/`, `tools/ci/`, `infra/validate.sh`, the Azure side of the dev environment, CI/CD, supply-chain gates, cost reading, runbooks in `docs/runbooks/` for deploy and rollback.

Read `docs/lanes/README.md` first, then **only the tail of `docs/status/infra.md`**: sections "Day 3", "Day 3 later", "In progress", "Blocked / waiting", "Requests filed", "Decisions taken (infra)" (the file is long; the older sections are history). Then `python3 tools/my-rows.py infra --todo`.

Binding for this lane:
- **Test account now, final account later** (`docs/28`, CLAUDE.md rule 1): pilot size, minimal and cheap; never create fleet-sized resources, request quotas, or enable zone/geo redundancy, WAF or Premium tiers without the sponsor's written yes. The zone-redundant dev resources already in `rg-aron-dev` stay until review on 2026-10-10 (exception section of docs/28). The cheap `dev-lite` profile is prepared; the reset workflow is owner-gated.
- Dev is shared and deployed **only by CI** from INT (`deploy.yml` after a green `ci`); never deploy by hand. Never print or store secrets: signing key, Maps/Firebase keys and Azure identity live in GitHub secrets and Key Vault only.
- Throughput rules from 2026-10-07 stay: one concurrency group per ref (a running push run finishes, a newer push replaces only the pending one); docs-only pushes start no run; deploy is its own workflow and is never cancelled mid-flight. Runner minutes are about 16 per full run plus 6 to 10 per deploy; keep CI cost in mind (repo is public now, private later with a spending limit set by the owner).
- GitHub governance: `tools/github-governance.ps1` is run by the laptop operator with the owner's approval (environments `azure-stage` and `azure-prod`, required checks list must match the job names in `ci.yml` exactly; whenever you rename a job, update the script and tell the lead).
- Anything that changes who can reach Azure or GitHub settings is a request to the lead, not an edit.

Next rows (from the previous session): AUD-DG-07, AUD-REL-06 and AUD-DG-06 (traffic split with a health gate, deploy by digest); OSV-scanner, Semgrep and npm audit in CI; per-app database logins for `docs/requests/db-runtime-roles.md` without touching the PostgreSQL server settings; per-ABI APK splits request `docs/requests/android-core-abi-splits.md` (android-core owns the Gradle part, you own the CI size gate).
