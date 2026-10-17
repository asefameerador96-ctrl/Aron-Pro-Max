# N-061 security review (qa, 2026-10-17) — DRAFT, in progress

Row: *Security review: scope-leak harness over every endpoint, secrets scan, dependency scan, token and device-proof
tests, MASVS-style checks on the three APKs.* Acceptance: no endpoint returns a row outside the caller's scope across
the full harness; no secret or key is in the repository or the APKs; every critical finding is fixed or ticketed.

Where the proofs run: local PostgreSQL 16 and the Ktor test engine in a lane container, plus CI on every push. This is
not the dev API and not the final account. Nothing here logged in to the dev API.

## Verdict

| Part | Result | Evidence |
|---|---|---|
| Scope leaks, every endpoint | **0 leaks** over (count pending) calls on 155 operations, from 4 scoped callers plus the national control. (count pending) reads cannot be judged yet (listed below with reasons) | `ScopeLeakAcrossEndpointsTest`, `scope-leak-baseline.txt` (empty), `scope-blind-baseline.txt` |
| Seeded leak turns the suite red | yes: the national token swapped in for tso1001 on three operations is reported | `seededLeaksAreReportedThroughTheBaseline` |
| Secrets in the repository | **none**: only test-fixture passwords (listed below) | gitleaks over the whole history in CI; `NoSecretsInRepoTest`; this sweep |
| Secrets in the APKs | **none** in the SR release build | `tools/ci/apk-security-check.py` |
| Dependencies | **5 High (Jackson 2.22.0, backend)**: ticketed, with a tried fix. The three APKs are clean. Web: CI OSV gate | `tools/ci/gradle-lockfile.py` + osv-scanner + `osv-gate.py` |
| Tokens and device proof | covered by existing suites, all green | listed below |
| MASVS-style APK checks | SR release: clean except the missing signature (the key is in CI only). AMO and TSO have no release build yet | `apk-security-check.py`, CI request |

Critical findings: none. High findings: the Jackson advisories, ticketed in `docs/requests/qa-backend-jackson-cves.md`.

## 1. Scope-leak harness

`backend/app/src/test/kotlin/com/aktcl/aron/backend/app/ScopeLeakAcrossEndpointsTest.kt` walks every operation in
`contract/openapi.yaml`. Each operation is called by sr1001 (app), amo1001, tso1001 and dmo1001 (web), and admin1001
as the national control. Two divisions each sell a day through the real `/v1/sync/batch`:
- own: the seed;
- other: the mirrored division D-LKX.

The other division's phone also sends one record of each generated type (built from the contract's payload schemas).
The aggregation worker then runs, so the dashboards carry both divisions.

Each operation is probed with these variants:
- **own:** the caller's own ids.
- **other-path:** the other division's ids in the path.
- **other-<selector>:** the other division's ids in each query selector.
- **other-body:** the other division's ids in each body id field, optional ones included.
- **other-coords:** `outlets/nearby` at the other division's pins.

An answer leaks when it carries any of these:
- the other division's marker or record UUIDs;
- one of its ids under a key of that kind;
- for a dashboard, numbers other than the caller's own node.

A write leaks when it answers 2xx while naming the other division.

**The positive control.** The national admin must see the other division on each read. A read where it cannot is
"blind": the harness cannot judge it, and it must be listed with a reason in `scope-blind-baseline.txt`. That list
may only shrink.

*In progress:* seeding other-division rows for the 12 debt lines of `scope-blind-baseline.txt` so they are judged.

## 2. Secrets

- CI: gitleaks scans the pushed range and the whole history on every push (`tools/ci/gitleaks.toml`). The reviewed
  exceptions are in `tools/ci/gitleaksignore`.
- `NoSecretsInRepoTest` (backend) runs in the test suite.
- This review also swept `git ls-files` (excluding `.claude/`) with the APK check's secret patterns: private keys,
  storage account keys, SAS signatures, AWS keys, JWTs and password literals. The only hits are test-fixture passwords:
  - web tests: `tso-pass-1` and similar;
  - `MfaTest.kt` `Admin-pass-00001`;
  - backend tests `throwaway-*`;
  - `PASSWORD = "login_password"` in the Android LoginScreen, which is a resource key.

  None is a credential of any running system. The signing key, the Maps and Firebase keys and the Azure identity
  live in GitHub secrets only.

## 3. Dependencies

The CI OSV gate reads only `web/package-lock.json`, because the repository has no Gradle lockfile. qa added
`tools/ci/gradle-lockfile.py`, which turns `gradle dependencies` into a `gradle.lockfile` that osv-scanner reads.
It ran over the `:backend:app` runtime classpath and the SR, AMO and TSO `releaseRuntimeClasspath`: 462 modules.

- Backend: `jackson-core` and `jackson-databind` resolve to **2.22.0**. They arrive via `com.auth0:java-jwt` (the
  token parser) and `google-cloud-storage`. That is 5 High advisories (CVSS 7.5) and 5 Medium, all fixed in 2.22.3.
  - Ticket: `docs/requests/qa-backend-jackson-cves.md` (backend-core).
  - Proposed fix: `platform("com.fasterxml.jackson:jackson-bom:2.22.3")`. It was tried locally: every Jackson module
    resolves to 2.22.3. It was then reverted.
- Android release apps: no advisory.
- CI wiring: `docs/requests/qa-infra-gradle-osv.md` (infra).

## 4. Tokens and device proof (existing suites, green in the full run)

- `backend/auth`:
  - `TokenVerifierTest`: tampered, foreign, wrong-audience and expired tokens are refused.
  - `LoginTest` (13), `RefreshTest` (10): rotation and reuse detection.
  - `LoginAbuseTest` and `LoginAbuseCheckerTest`: lockout and rate limits.
  - `TotpTest`, `SecurityEventsTest`, `AuthReviewTest`, `DeviceGateTest`.
- `backend/app`:
  - `MfaTest`;
  - `DeviceGateTest` (6): a token bound to one device is refused from another `X-Device-Id`;
  - `BindDeviceTest`, `BindDeviceCheckerTest`, `DeviceOtpTest`, `DeviceOtpRefuteTest`, `DeviceReplaceTest`;
  - `LogoutCheckerTest`.
- The sync fuzz suite (N-024, 10,000 fault schedules) shows that a replayed or concurrent batch never doubles a row.

## 5. MASVS-style checks on the APKs

`tools/ci/apk-security-check.py` checks each APK:
- the manifest, read with aapt2: debuggable, allowBackup, cleartext, and exported components without a permission;
- the signer, read with apksigner: a debug certificate, or the v1 scheme only;
- every entry, scanned for secrets.

Its self-test is `tools/ci/test_apk_security.py`.

The SR release build (local, unsigned) passes everything except the missing signature: it is not debuggable, backup is
off, there is no cleartext traffic, no exported component is unprotected, and it holds no secret. A debug-signed copy
fails the gate, as it should. AMO and TSO have no release build at this commit, so they are checked when their release
jobs exist. CI wiring: `docs/requests/qa-apk-security-check.md` (infra).

## Open items

- Scope-blind debt lines (in progress).
- Jackson 2.22.3 pin (backend-core) and the Gradle OSV CI step (infra).
- APK check in CI (infra); AMO and TSO release builds.
- Opus checker of this row.
