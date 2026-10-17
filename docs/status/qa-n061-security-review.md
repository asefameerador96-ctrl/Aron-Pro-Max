# N-061 security review (qa, 2026-10-17)

Row: *Security review: scope-leak harness over every endpoint, secrets scan, dependency scan, token and device-proof
tests, MASVS-style checks on the three APKs.* Acceptance: no endpoint returns a row outside the caller's scope across
the full harness; no secret or key is in the repository or the APKs; every critical finding is fixed or ticketed.

Where the proofs run: local PostgreSQL 16 and the Ktor test engine in a lane container, plus CI on every push. This is
not the dev API and not the final account. Nothing here logged in to the dev API.

## Verdict

| Part | Result | Evidence |
|---|---|---|
| Scope leaks, every endpoint | **0 leaks**: 1,008 probe calls on 155 operations (388 of them name the other division; 351 refused with 403/404), from 4 scoped callers plus the national control. The national control sees the other division on 45 of 78 reads. Of the other 33: 22 are global and 8 per-caller by design, and **3 are debt** (`media/{}/read-url`, `admin/config/reach/{}` and `.../pending`), each listed with its reason | `ScopeLeakAcrossEndpointsTest`, `scope-leak-baseline.txt` (empty), `scope-blind-baseline.txt` |
| Seeded leak turns the suite red | yes: the national token swapped in for tso1001 on three operations is reported | `aSeededCrossScopeReadIsCaught` |
| Secrets in the repository | **none**: only test-fixture passwords (listed below) | gitleaks over the whole history in CI; `NoSecretsInRepoTest`; this sweep |
| Secrets in the APKs | **none** in a local, unsigned SR build. The CI-built APKs (all three) are not scanned yet; they carry the Maps and Firebase client keys by design | `tools/ci/apk-security-check.py` |
| Dependencies | **5 High (Jackson 2.22.0, backend)**: ticketed, with a tried fix. The three APKs are clean. Web: CI OSV gate | `tools/ci/gradle-lockfile.py` + osv-scanner + `osv-gate.py` |
| Tokens and device proof | covered by existing suites: `backend/app` green locally, the other modules in CI | listed below |
| MASVS-style APK checks | **partial**: the local SR build is clean except the missing signature. The CI-built SR, AMO and TSO APKs are not scanned yet | `apk-security-check.py`, CI request |

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

**This review's additions:**
- **Other-division rows to read.** `seedOtherRows` inserts the other division's rows that no phone record creates: a
  task, an outlet request, a visit plan with its outlet, a web route-day entry, a device directive and status report,
  a dues adjustment and an entry unlock. The inserts run with triggers and foreign keys off, so they are fixtures,
  not ingest.
- **Aimed probes.** The other-path variant puts the other division's ids into the path **and into every required id
  query parameter**, e.g. `web-entry/route-day?route_id=` and `day/final-submit/preview?zone_id=`. It also puts the
  other division's record uuids and row ids into these paths:
  - `outlet-requests/{request_uuid}`, `tasks/{task_uuid}/cancel`, `leave/{leave_uuid}/decision`,
    `day/exceptions/{exception_uuid}/decision`, `feedback/{feedback_uuid}`;
  - `admin/entry-unlocks/{unlock_id}/expire`, `admin/dues-adjustments/{adjustment_id}/decision`.
  The own call keeps its sample value.
- **Config selectors.** `scope_type=zone&scope_id=` is probed on the config tools: blast-radius, whatif, density and
  calibration.
- **The national control counts only a 2xx**, because a refusal echoes the asked path in its problem `instance`. For
  the scoped probes, only that `instance` field is blanked on a refusal; everything else in the body is still
  scanned.
- **Fixes to the probes themselves.** `outlets/nearby` was probed at a radius the config refuses (500 m; 50, 100 and
  300 are allowed). `admin/geo` reads use the division level.

The blind list went from 48 lines to 33. Every probe of the newly judged reads, by every scoped caller in every
variant, finds no leak. The first Opus checker's findings are fixed or listed below; a re-check follows.

**Known gaps in the proof (none is a leak found):**
- `media/{}/read-url`: reach-filtered, but the answer carries no division identity. It needs an other-division photo
  and a status-code rule.
- `admin/config/reach/{}` and `.../pending`: scoped callers are probed with the other `zone_id`, but the national
  control does not see the other division yet.
- `admin/geo`: only the division level is probed. Zone, territory, house and wing use other reach predicates.
- Not aimed at the other division:
  - `admin/route-assignments/{id}/end`, `risk-signals/{signal_id}/review`, `admin/quarantine/{id}/...`;
  - geo writes;
  - the `planner_user_id` and `assignee_user_id` selectors.
- `admin/enrolment-tokens`, `admin/dues-adjustments` and `admin/entry-unlocks` are readable by national roles only.
  "Judged" there says no scoped caller reached them, not that their zone filters work.
- No positive "own" check on the uuid paths: a server that answered 404 to everyone would pass them.

## 2. Secrets

- CI: gitleaks scans the pushed range and the whole history on every push (`tools/ci/gitleaks.toml`). The reviewed
  exceptions are in `tools/ci/gitleaksignore`.
- `NoSecretsInRepoTest` (backend) runs in the test suite.
- This review also swept `git ls-files` (excluding `.claude/`) with the APK check's secret patterns: private keys,
  storage account keys, SAS signatures, AWS keys, JWTs and password literals. The only hits are test-fixture passwords:
  - web tests: `tso-pass-1` and similar;
  - `MfaTest.kt` `Admin-pass-00001`;
  - backend tests `throwaway-*`;
  - `PASSWORD = "login_password"` in the Android LoginScreen, which is a resource key;
  - `ARON_SESSION_SECRET: "e2e-only-session-secret-..."` in `web/playwright.config.ts`, a value used only by the
    end-to-end tests.

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

## 4. Tokens and device proof (existing suites)

`backend/app` ran locally in this review (357 of 357 green). The other modules run in CI on every push; their results
are on the CI run of the checked head, not in this container.

- **`backend/auth`:**
  - `TokenVerifierTest.rejectsTamperedForeignWrongAudienceAndExpiredTokens`;
  - `LoginTest`, `RefreshTest` (rotation and reuse);
  - `LoginAbuseTest` and `LoginAbuseCheckerTest` (lockout and rate limits);
  - `TotpTest`, `SecurityEventsTest`, `AuthReviewTest`, and `DeviceGateTest` (auth side).
- **`backend/platform`:** `N009ReviewTest.guardRejectsMismatchedDeviceId`. A token bound to one device is refused
  from another `X-Device-Id`; this is the device-binding proof.
- **`backend/app`:**
  - `DeviceGateTest`, the enrolment and integrity gate;
  - `MfaTest`;
  - `BindDeviceTest` and `BindDeviceCheckerTest`;
  - `LogoutCheckerTest`.
- **`backend/masterdata`:** `DeviceOtpTest`, `DeviceOtpRefuteTest`, `DeviceReplaceTest`.
- **Sync:** the fuzz suite (N-024, 10,000 fault schedules) shows that a replayed or concurrent batch never doubles a
  row.

## 5. MASVS-style checks on the APKs (partial)

`tools/ci/apk-security-check.py` checks each APK:
- the manifest, read with aapt2: debuggable, allowBackup, cleartext, and exported components without a permission;
- the signer, read with apksigner: a debug certificate, or the v1 scheme only;
- every entry, scanned for secrets.

Its self-test is `tools/ci/test_apk_security.py`.

**Only the SR app was scanned, and only a local unsigned build without the CI secrets.** It passes everything except
the missing signature. A debug-signed copy fails the gate, as it should.

**Not done yet, so the APK half of the acceptance is open:**
- The CI-built SR, AMO and TSO release APKs have not been scanned. That needs the CI step in
  `docs/requests/qa-apk-security-check.md`.
- The CI build puts the Google Maps key (`${mapsApiKey}` in each manifest) and the Firebase key (from
  `google-services.json`) into all three APKs. These are client keys that ship inside every Android app by design, so
  the check reports them as **info**, not as a secret. Their protection is the restriction by package name and signing
  certificate in the Google Cloud console. That restriction must be confirmed for both keys; it is an owner console
  step.

## Open items

- The scope gaps listed in section 1.
- The CI scan of the three release APKs, and the Maps and Firebase key restrictions (section 5).
- Jackson 2.22.3 pin (backend-core) and the Gradle OSV CI step (infra).
- APK check in CI (infra); AMO and TSO release builds.
- Opus re-check of this row.
