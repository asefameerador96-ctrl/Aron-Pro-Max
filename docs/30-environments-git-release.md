# 30 — Environments, Git governance and release (binding, 2026-10-07)

This page applies the full design of `docs/20` s5 (written for the long plan) to the 7-day build and to the **test account / final account** split of `docs/28`. Where `docs/20` s5 and this page differ, this page wins for the build; `docs/20` s5 is the target for the final account.

## 1. Environments: what exists, what comes

| Environment | Purpose | Where | Data | Status today (2026-10-07) |
|---|---|---|---|---|
| **local** | engineer and lane loop | the lane's container or the owner's laptop; PostgreSQL 16 | synthetic seed (`db/seed`) | live |
| **CI (ephemeral)** | every push: build, unit, integration, contract, images booted against PostgreSQL | GitHub Actions | Testcontainers or service containers, discarded | live |
| **dev** | shared integration and device testing | Azure test account, `rg-aron-dev` (Front Door address in `docs/status/infra.md`) | synthetic seed only, never real data | live; deployed automatically from the integration branch when CI is green |
| **device lab** (this is our "QA" for the apps) | real behaviour: the owner's Galaxy A06, A07, Honor X5c Plus, the MP-58N; battery, print, GPS, device-owner, spoofing apps | the owner's desk | synthetic plus test accounts | live as a protocol: `docs/status/device-checks.md` |
| **staging** | production-shaped rehearsal: full-size synthetic fleet, load, failover and restore drills, release-candidate soak, move rehearsal | final Azure account | synthetic fleet (`N-054`) | **not created** (docs/28: no fleet-sized resources in the test account). The infra lane adds `infra/params/stage.bicepparam` (a parameter file only) |
| **prod / live** | the real system for 8,500 users. "Live" and "prod" are the same environment: prod serving the real fleet | final Azure account | real | **not created**. Parameter file exists: `infra/params/prod.bicepparam` |

"QA" is therefore not a separate Azure environment in the test account. QA means: the automated suites in CI, plus the device-lab protocol on real phones, plus the independent checker on every row. A separate QA Azure environment would double the cost for no extra signal at 5 to 10 users; it appears as staging in the final account.

**Rings for the apps** (staged rollout inside prod): ring 0 the owner's three test phones; ring 1 a pilot group of 20 to 50 SRs; ring 2 the fleet by `rollout_wave` and `wave_pct` (`docs/20` s5.8). The admin portal's release management and `cfg.release.*` keys already carry this (F-SYS-054, F-ADM-027).

**Rules.** No real data outside prod. Secrets only in GitHub secrets and Key Vault. Every environment is created from the same Bicep templates; only the parameter file differs. A change reaches prod only through dev (CI green) then staging (soak) then a manual promote with a recorded approver.

## 2. Promotion flow

1. Lane commits on the **integration branch** (`claude/wonderful-thompson-k6ejnf`, "INT"); CI runs on every push.
2. CI green on INT automatically deploys **dev** and builds the three debug APKs.
3. At the end of each day the lead cuts a **daily gate**: all CI green on the INT head, the day's 10-minute check done by the owner, sampled audit done. The head is tagged `gate-dayN-YYYYMMDD` and promoted to `main` by a pull request (merge commit, CI green on the merge).
4. In the final account: `main` deploys **staging**; a manual `promote-prod` with the release tag and a required approver deploys **prod**; blue/green by traffic weights; rollback by shifting weights back; the database is forward-only (`docs/20` s5.6 expand/contract).
5. Android: a release tag `app-vX.Y.Z` builds signed APKs per ABI, attaches them to a GitHub Release, and creates a draft release in the admin portal for the rings.

## 3. Git and GitHub governance

**State today (honest):** all work is in GitHub (`asefameerador96-ctrl/Aron-Pro-Max`, about 100 commits, every row one commit, "F-XXX-nnn: what"), but there is **one branch**, it is the default branch, there is **no `main`, no branch protection, no tags, no releases, no CODEOWNERS, no PR template, no Dependabot**. That was acceptable for 14 lanes pushing straight to one branch in the first two days; it is not enterprise grade.

**Target (applied by `tools/github-governance.ps1`, run by the laptop operator with the owner's approval):**

| Item | Rule |
|---|---|
| Branches | `main` = always releasable, protected. INT (`claude/wonderful-thompson-k6ejnf`) = integration, lanes push here with the merge-first, no-force protocol of `docs/26` s3. Hotfix branches `hotfix/*` from `main`. |
| `main` protection | pull request required; status checks required (`ci` jobs: contract, jvm, android, web, images, infra); branch up to date; no force push; no deletion; conversation resolution; **zero required approvals** because there is one human, so the gate is CI plus the lead's checks, not a rubber stamp. Admin may override in an emergency, and the override is visible. |
| Commits | one per finished row, `F-XXX-nnn: summary`; body lists tests run; trailer `Co-Authored-By: Claude <noreply@anthropic.com>`; never force-push or rewrite INT. |
| Pull requests | only for promotion INT to `main`, daily at the gate, with the gate report from `.github/pull_request_template.md`; the lead opens them once the owner has approved the flow. |
| Tags | `gate-dayN-YYYYMMDD` at each daily gate; `baseline-2026-10-06` at the first green deploy; `app-vX.Y.Z` per Android release; `server-vX.Y.Z` per backend release. Semantic versioning; `CHANGELOG.md` generated per release. |
| Releases | GitHub Release per `app-v*` tag with the signed APKs (SHA-256 listed) and the release notes in Bangla and English. |
| CODEOWNERS | the owner for everything, with the lane-to-folder map as comments (`docs/26` s2); there is one human reviewer. |
| Supply chain | Dependabot for Gradle, npm, GitHub Actions and Docker; Actions pinned by SHA (already true); secret scanning and push protection on; CodeQL for Kotlin and TypeScript; `gitleaks` in CI. |
| Migrations | forward-only, checksum-checked, expand/contract (`docs/20` s5.6). A shipped migration is never edited. |
| Contract | `oasdiff` breaking-change check in CI; a breaking change needs a `schema_version` bump and a lead-approved request file. |

### 3a. Repository visibility (found 2026-10-07, owner decision pending)

The repository `asefameerador96-ctrl/Aron-Pro-Max` has been **public** since it was created on 2026-10-04 (GitHub reports `visibility: public`). It holds AKTCL's whole rebuild: code, infra, specs, price catalogue seed, UI reference images, sales-data aggregates, the Azure subscription and tenant ids in `infra/bootstrap-azure.ps1` and `docs/23`. No secret is committed (secrets live in GitHub secrets and Key Vault; forked PRs get none), but the content is company IP and should be private. Consequences of going private: GitHub Actions minutes become metered (free plan 2,000 a month; the owner sets a spending limit under Billing so CI never stops); CodeQL and secret scanning need GitHub Advanced Security, so CI uses gitleaks, OSV-scanner and Semgrep, which work either way. `tools/github-governance.ps1 -MakePrivate` does the change after the owner approves.

## 4. Rollback and recovery

| What | How | Proof |
|---|---|---|
| Bad API or worker deploy | shift Container Apps traffic back to the previous revision (seconds); in-flight batches are replayed by `batch_uuid` | dev drill once; staging drill in the final account |
| Bad migration | forward fix (expand/contract keeps the old code working); point-in-time restore of the database as the last resort | restore drill on dev (Day 6) |
| Bad Android build | no downgrade; roll-forward release inside 24 hours; `cfg.release.blocked_versions` stops new captures only, never blocks upload | upgrade drill on the A06 |
| Lost data on a phone | none by design: the outbox holds every record until the server confirms it | kill-and-relaunch tests, device checks |
| Bad config change | `cfg` versions with rollback in the admin portal (F-ADM-043); blast-radius and what-if before applying | config tests |

## 5. What is not done yet (tracked, owner in brackets)

1. `main`, protection, tags, templates, CODEOWNERS, Dependabot, CodeQL, gitleaks: scripted, **waiting for the owner's approval** (laptop operator, `tools/github-governance.ps1`).
2. `stage.bicepparam` and the promotion workflows `promote-prod.yml` and `release-app.yml`: written by the infra lane, **not deployable** until the final account exists.
3. Device-lab protocol file and the 8-hour battery run: QA lane and the owner (Day 6).
