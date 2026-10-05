# 24 — Build spec: verification log

**Date: 2026-10-05 (updated the same day after the coordinator's second request: D24-56 overruled, D24-18 changed, backlog coverage check in s10). Author: the architect agent.** What was run to prove `docs/24-build-spec.md`, `contract/openapi.yaml`, the Gradle skeleton, `tools/android-sdk.sh` and `.github/workflows/ci.yml`, with the real (shortened) output, and what could not be verified and why. Outputs are trimmed to the decisive lines; the `JAVA_TOOL_OPTIONS` proxy banner printed by every JVM tool in this container is removed.

## 1. Environment

| Item | Value |
|---|---|
| OS | Linux 6.18 (Ubuntu 24.04 container), outbound HTTPS through the agent proxy |
| JDK | OpenJDK 21.0.11 (`./gradlew --version`: `Launcher JVM: 21.0.11`) |
| Gradle | 9.8.0 via the wrapper (`Gradle 9.8.0`) |
| Node | v22.22.0 (for Redocly) |
| PostgreSQL | 16.14, local cluster `16 main` on port 5432, role and database `aron_test` |
| Docker | CLI 29.6.2 present, **no daemon** (`/var/run/docker.sock` missing) |
| Android SDK | `/opt/android-sdk`, 507 MB, installed by `tools/android-sdk.sh` |

## 2. Results at a glance

| Check | Result |
|---|---|
| Redocly lint of `contract/openapi.yaml` (rules in `contract/redocly.yaml`) | **pass**, 0 errors, 0 warnings |
| Contract examples validated against their schemas (JSON Schema 2020-12) | **pass**, 12 of 12 |
| Cross-check docs/24 ⇄ contract (script and `SpecCrossCheckTest`) | **pass**; a deliberate mutation is caught |
| Backlog-to-contract coverage (444 in-scope BUILD rows of docs/25, s10) | **pass** after the fix: 127 rows had gaps (120 missing contract elements), all closed; `BacklogCoverageTest` gates it |
| `./gradlew` build and tests of `shared`, `db`, `backend` (PostgreSQL 16) | **pass**, 52 tests, 0 failures |
| Three debug APKs (`assembleDebug`) | **pass**: `com.aktcl.aron.sr`, `.amo`, `.tso` |
| Android local unit tests (`testDebugUnitTest`, incl. Robolectric Room outbox test) | **pass**, 23 tests, 0 failures (after one fix, s6) |
| Android lint (`lintDebug`, `abortOnError`) | **pass** |
| R8 release build of the SR app (unsigned) | **pass**, 2,355,288 bytes (budget 30 MB) |
| `tools/android-sdk.sh` (idempotent rerun) | **pass**, 4 s |
| `actionlint` 1.7.12 on `.github/workflows/ci.yml` | **pass**, no findings |
| Gradle wrapper jar checksum vs services.gradle.org | **match** |
| Secret scan of every changed and new file | **clean** |
| The CI workflow running on GitHub | **not verified** (s8) |
| Final combined run after the last edits (`--rerun-tasks`: JVM build and tests, three debug APKs, Android unit tests, lint, one Gradle invocation) | **pass**: `BUILD SUCCESSFUL in 1m 56s`, 1,355 tasks executed, 52 JVM and 23 Android tests, 0 failures |

## 3. Contract

```text
$ npx --yes @redocly/cli@2 lint contract/openapi.yaml --config contract/redocly.yaml
validating contract/openapi.yaml...
contract/openapi.yaml: validated in 938ms
Woohoo! Your API description is valid. 🎉
$ npx --yes @redocly/cli@2 --version
2.57.0
```

Size after the coverage changes: 166 paths, 206 operations, 494 schemas, 10 component examples (plus 2 inline); first handback: 115 paths, 140 operations, 380 schemas. Example validation (Python `jsonschema`, draft 2020-12, every `components.examples` entry and every inline example against the schema it illustrates):

```text
OK   SyncBatchRequestExample -> SyncBatchRequest
OK   SyncBatchResponseExample -> SyncBatchResponse
OK   SyncBatchResponseRejectExample -> SyncBatchResponse
OK   BundleSr -> Bundle
OK   DeltaExample -> BundleDelta
OK   EnrolmentQrExample -> EnrolmentTokenCreated
OK   ProblemValidation -> Problem
OK   ProblemScopeChanged -> Problem
OK   ProblemBatchReuse -> Problem
OK   ProblemRateLimited -> Problem
OK   login.phoneOk -> LoginResponse
OK   config.radiusZone -> ConfigChangeRequest
```

Contract fixes made today while writing docs/24 (the contract had been committed as `f4f0df3` before them): `location_mode` value `off` quoted (YAML 1.1 parsers such as PyYAML read a bare `off` as boolean false; YAML 1.2 tools did not complain); `number` added to `ConfigKey.value_type` (D24-62); `ERR_PUSH_DISABLED` stated as 409 on `POST /v1/admin/notifications`. Then the coverage changes of s10 (66 operations, 10 record types and their payloads, 14 report keys, programme, content, web-entry and admin schemas, enum additions). Lint and example validation above were rerun after every edit; the last run is the one shown.

## 4. Cross-check docs/24 ⇄ contract

The one-off script output is pasted in docs/24 Appendix B (`RESULT: PASS (no drift)`): 206 of 206 operations in Appendix A, 75 `/v1` paths named in the prose all exist, 18 enums agree in both directions, 226 config keys in the s9.5 registry cover every key named in docs/24 (226) and in the contract (64), 13 headers and 39 schema names exist, 42 of 42 record types have a payload mapping.

The permanent gate is in the build (`shared/contract` jvmTest): `ContractDriftTest` (27 tests: 24 enums of the Kotlin mirror equal the YAML, every record type has a payload mapping, scope precedence equals the `ConfigScopeType` description, every path starts with `/v1/`), `SpecCrossCheckTest` (8 tests) and `BacklogCoverageTest` (2 tests, s10). The gates caught a real drift during the coverage work: after adding the report keys `retailer-list` and `sku-list` to the YAML and docs/24 but not yet to the Kotlin mirror, `ContractDriftTest.reportKeys`, `SpecCrossCheckTest.riskSignalsAndReportsMatch` and `BacklogCoverageTest.everyNeedExistsInTheContractAndTheSpec` failed (`37 tests completed, 3 failed`) until the mirror was regenerated. Mutation check: deleting the `cfg.auth.refresh_grace_s` registry row and changing `ERR_BUNDLE_CURSOR_EXPIRED` from 410 to 409 in docs/24 made the build fail, and restoring the file made it pass:

```text
SpecCrossCheckTest[jvm] > everyConfigKeyIsInTheRegistry()[jvm] FAILED
SpecCrossCheckTest[jvm] > problemCodeStatusesMatchSection34()[jvm] FAILED
25 tests completed, 2 failed
BUILD FAILED in 4s
(file restored, identical by cmp)
BUILD SUCCESSFUL in 973ms
```

## 5. JVM build: shared, db, backend

```text
$ export ARON_TEST_PG_URL="jdbc:postgresql://127.0.0.1:5432/aron_test?user=aron_test&password=aron_test"
$ ./gradlew --console=plain --rerun-tasks :shared:contract:build :shared:rules:build :db:build \
    :backend:platform:build :backend:auth:build :backend:sync:build :backend:masterdata:build \
    :backend:config:build :backend:analytics:build :backend:notify:build :backend:media:build :backend:app:build
BUILD SUCCESSFUL in 22s            (first handback run; the final combined run of s2 repeats it with 52 tests)
61 actionable tasks: 61 executed
```

(`aron_test` is a throwaway local role on the build container, not a credential of any environment.)

| Module | Tests | Failed |
|---|---|---|
| shared/contract (ContractDriftTest 27, SpecCrossCheckTest 8, BacklogCoverageTest 2) | 37 | 0 |
| shared/rules (MoneyTest: 116,416 → 116,420 mtk; 20 × 7,935 = 158,700; half away from zero) | 3 | 0 |
| db (MigrationNamingTest) | 2 | 0 |
| backend/platform (DatabaseSmokeTest on PostgreSQL 16: Hikari + Flyway + JDBI, `2026-10-04 18:30Z` → business date `2026-10-05` in Asia/Dhaka; PlatformModuleTest) | 2 | 0 |
| backend/app (HealthTest: `GET /v1/health` 200 with `X-Aron-Api: 1`) | 1 | 0 |
| backend/auth, sync, masterdata, config, analytics, notify, media (placeholders) | 7 | 0 |
| **Total** | **52** | **0** |

## 6. Android

```text
$ export ANDROID_HOME=/opt/android-sdk
$ ./gradlew --console=plain :android:app-sr:assembleDebug :android:app-amo:assembleDebug \
    :android:app-tso:assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 19s            (incremental; the first full run on this box took 2 min 56 s, 514 tasks)
833 actionable tasks: 91 executed, 5 from cache, 737 up-to-date
-rw-r--r-- 17309075 android/app-amo/build/outputs/apk/debug/app-amo-debug.apk
-rw-r--r-- 16946229 android/app-sr/build/outputs/apk/debug/app-sr-debug.apk
-rw-r--r-- 17308927 android/app-tso/build/outputs/apk/debug/app-tso-debug.apk
(final combined run: 16,937,639 / 17,300,318 / 17,300,318 bytes for SR / AMO / TSO)
android unit test suites 23 tests 23 failures+errors 0

$ aapt2 dump badging app-<role>-debug.apk
package: name='com.aktcl.aron.sr'  versionCode='1' versionName='0.1.0' compileSdkVersion='37' targetSdkVersion:'36' application-label:'ARON SR'
package: name='com.aktcl.aron.amo' versionCode='1' versionName='0.1.0' compileSdkVersion='37' targetSdkVersion:'36' application-label:'ARON AMO'
package: name='com.aktcl.aron.tso' versionCode='1' versionName='0.1.0' compileSdkVersion='37' targetSdkVersion:'36' application-label:'ARON TSO'
(AMO manifest: com.google.android.geo.API_KEY = "" because MAPS_ANDROID_KEY is not set here: no key in the repo)

$ ./gradlew --console=plain lintDebug
BUILD SUCCESSFUL in 46s

$ ./gradlew --console=plain :android:app-sr:assembleRelease
BUILD SUCCESSFUL in 1m 22s
2355288 android/app-sr/build/outputs/apk/release/app-sr-release-unsigned.apk
```

**Fix made during verification.** The first `testDebugUnitTest` run failed in the Robolectric Room test:

```text
OutboxDaoTest > insertsAndCountsPendingRows FAILED
java.lang.RuntimeException: Failed to interact with raw FileDescriptor internals; perhaps JRE has changed?
Caused by: java.lang.IllegalAccessException: ... cannot access class jdk.internal.access.SharedSecrets (in module
java.base) because module java.base does not export jdk.internal.access to unnamed module
```

Robolectric 4.17 with the API 36 runtime on JDK 21 needs `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`, `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` and `--add-opens=java.base/java.io=ALL-UNNAMED`; the root `build.gradle.kts` now passes them to every Android unit-test task (`unitTests.all { it.jvmArgs(...) }`). After the fix: `OutboxDaoTest tests 1 fail 0 err 0 time 5.737`.

## 7. Tooling

```text
$ ANDROID_HOME=/opt/android-sdk tools/android-sdk.sh        (rerun; first install earlier today)
Android SDK ready at /opt/android-sdk
  build-tools/36.0.0       36.0.0   Android SDK Build-Tools 36
  cmdline-tools/latest     23.0.0   Android SDK Command-line Tools (latest)
  platform-tools           37.0.1   Android SDK Platform-Tools
  platforms/android-37.0   2.0.0    Android SDK Platform 37.0
exit=0 wall=4s

$ ./actionlint -shellcheck= .github/workflows/ci.yml        (actionlint 1.7.12)
(no output; exit 0)

$ ./gradlew wrapper --gradle-version 9.8.0 --gradle-distribution-sha256-sum bafd5ce9…58e6c
BUILD SUCCESSFUL in 4s
$ sha256sum gradle/wrapper/gradle-wrapper.jar
238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5
$ curl https://services.gradle.org/distributions/gradle-9.8.0-wrapper.jar.sha256
238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5
```

Action versions in `ci.yml` (the GitHub REST API is not enabled for this session, so they were read with `git ls-remote --tags`): `actions/checkout` v7 (v7.0.1), `actions/setup-java` v6 (v6.0.1), `actions/setup-node` v7 (v7.0.0), `actions/upload-artifact` v7 (v7.0.1), `actions/cache` v6 (v6.1.0), `gradle/actions/setup-gradle` v6 (v6.4.0); each major tag exists.

`tools/android-sdk.sh` history: the first version died silently because `yes | sdkmanager --licenses` under `set -o pipefail` exits 141 (SIGPIPE) once cmdline-tools 23.0 stops reading; the script now installs directly (23.0 writes `licenses/android-sdk-license` itself), runs the licence step only when that file is missing with `pipefail` off, and fails if the licence file is still absent.

Secret scan (patterns for Google API keys `AIza…`, PEM private keys, service-account `private_key`, `client_secret`, storage `AccountKey=` and SAS `sig=`) over every modified and new file: no match. `local.properties` (sdk.dir only) and any `google-services.json` are git-ignored.

## 8. Not verified, and why

| Item | Why not | How it will be verified |
|---|---|---|
| The CI workflow on GitHub Actions | This task may not commit or push; nothing ran on GitHub | first push of the lane branches; the Day 1 check |
| Testcontainers fallback of the DB tests | no Docker daemon in this container (CLI only) | CI uses the `postgres:16` service; a laptop with Docker runs the fallback |
| The JVM CI job on a runner without a configured Android SDK | Gradle configures the Android modules even for JVM tasks; GitHub's `ubuntu-24.04` image presets `ANDROID_HOME`, which AGP accepts | first CI run |
| Builds with real `MAPS_ANDROID_KEY`, `GOOGLE_SERVICES_JSON` | the secrets are not available here (by design) | CI run after the sponsor sets the secrets (`docs/setup/chrome-google-firebase-github.md`) |
| Physical devices: Galaxy A06, A07, Honor X5c Plus; MP-58N printer; GPS; battery | no device attached to this container | Day 2 to Day 6 checks of `docs/23` s7 |
| Device-owner provisioning by QR, key attestation, Play Integrity, app blocking | needs a factory-reset phone and Google Play services | Day 3 check |
| Google developer verification for sideloaded APKs (rolling out from September 2026) | policy check on a real device and the Play Console | Day 1 (`docs/23` s9 risk 5) |
| Release signing and the APK budget of the AMO and TSO apps | no release keystore (secret); only the SR app was R8-built, unsigned | release job on Day 7 |
| Azure resources, deploy, load and failover | no Azure access from this session; outside this task | infra lane, Days 1 and 6 |
| Web (`web/`) | the folder does not exist yet; its CI job is added by the web lanes | web lanes, Day 1 |
| Completeness of the backlog coverage mapping | which contract elements a row needs is my reading of the row's name and acceptance test (s10); the script and `BacklogCoverageTest` prove that every listed need exists, not that the list is exhaustive | each lane checks its rows when it starts them; a missing need is added to the s10 table and the contract in one PR |
| Gradle 10 readiness | the build prints "Deprecated Gradle features were used ... incompatible with Gradle 10"; `--warning-mode all` shows `Configuration.setVisible(boolean)` called while configuring `:android:app-amo` (a third-party plugin, not this repo's scripts; removal is scheduled for Gradle 11) | re-check when the Hilt, KSP or google-services plugins are bumped |

## 9. Environment workaround (not in the repository)

Maven Central answered HTTP 429 (`Your ip has exceeded rate limits`) to this container on 2026-10-05, so the first dependency resolution failed. The repository keeps the standard `mavenCentral()`; this machine has a local Gradle init script that points it at Google's read-only mirror of Maven Central (D24-50). Content of `~/.gradle/init.d/aron-maven-mirror.init.gradle.kts`:

```kotlin
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
settingsEvaluated {
    fun swap(repos: org.gradle.api.artifacts.dsl.RepositoryHandler) {
        repos.withType(org.gradle.api.artifacts.repositories.MavenArtifactRepository::class.java).configureEach {
            if (url.toString().startsWith("https://repo.maven.apache.org/maven2")) setUrl(mirror)
        }
    }
    swap(pluginManagement.repositories)
    swap(dependencyResolutionManagement.repositories)
}
```

Any other Claude build container that hits the same 429 can copy this file to `~/.gradle/init.d/`; GitHub-hosted runners do not need it.

Other problems met and fixed on the way (all before the runs above): a `JavaCompile --release` option rejected by AGP for Android modules (now set only on JVM and KMP modules); OkHttp's BOM exported with `implementation` while OkHttp itself was `api` (now both `api`); an OpenAPI ambiguity between `integer` and `number` in `oneOf` (now type unions or `anyOf`); a Redocly `no-ambiguous-paths` error (`/v1/reports/exports/{id}` renamed `/v1/report-exports/{export_id}`).

## 10. Backlog-to-contract coverage check

**Requested by the coordinator on 2026-10-05 after the first handback; run the same day.**

**Scope.** Every row of `docs/25-build-backlog.csv` with decision BUILD whose lane is `backend`, `android-*` or `web-*`, or whose role is `API`: **444 rows**.

**Method.** For each row I read the name and the acceptance test and listed what the row needs from the contract. Each need is a token that a script checks against `contract/openapi.yaml` and `docs/24-build-spec.md`:

| Token | Exists when |
|---|---|
| `METHOD /v1/...` | the operation is in the contract's paths and in docs/24 Appendix A |
| `rec:<type>` | the `RecordType` value exists and has a row in the docs/24 s4.2 table |
| `report:<key>` | the `ReportKey` value exists and has a row in the docs/24 s12.3 table |
| `cfg:<key>` | the key has a row in the docs/24 s9.5 registry |
| `bundle:<member>` | the `Bundle` schema has the member and docs/24 s4.10 names it |
| `enum:<Schema>.<value>` | the component schema's enum has the value |
| `field:<Schema>.<prop>` | the component schema (with its `allOf`) has the property |
| `reportformat:<f>` | `ReportQuery.output.format` allows it |
| `param:<Name>` | the component parameter exists |
| local / internal | nothing in the contract is needed (phone-local behaviour or a server job) |

"Found before" was evaluated against the contract as committed in `f4f0df3` plus my three earlier fixes and against docs/24 as handed back the first time; "Found after" against the files as they are now.

**Result.** 127 of the 444 rows had at least one gap before the fix; together they missed **120 distinct contract elements**: 53 operations, 10 record types, 14 report keys, 20 config keys, 4 bundle members, 8 schema fields, 9 enum values and 2 report formats. Every gap is now closed in the contract and the spec (0 rows fail after the fix). The main causes: D24-56 (programmes deferred, overruled by the sponsor), the web back office (web entry, QC entry, paper backfill, unlock grants, target revisions, price approvals, dues adjustments, permissions, tutorials, content admin) and a set of admin analysis reads (what-if, blast radius, density, calibration, version detail, pending reach).

Gap rows closed: F-SR-006 F-SR-009 F-SR-014 F-SR-020 F-SR-021 F-SR-040 F-SR-041 F-SR-042 F-SR-043 F-SR-044 F-SR-045 F-SR-048 F-SR-049 F-SR-053 F-SR-055 F-SR-057 F-SR-074 F-SR-076 F-AMO-001 F-AMO-023 F-AMO-030 F-AMO-031 F-AMO-033 F-AMO-034 F-AMO-035 F-AMO-038 F-AMO-040 F-TSO-003 F-TSO-005 F-TSO-006 F-TSO-012 F-TSO-020 F-TSO-024 F-WEB-002 F-WEB-008 F-WEB-011 F-WEB-012 F-WEB-020 F-WEB-022 F-WEB-026 F-WEB-029 F-WEB-030 F-WEB-034 F-WEB-035 F-WEB-037 F-WEB-039 F-WEB-048 F-WEB-049 F-WEB-050 F-WEB-052 F-WEB-053 F-WEB-060 F-WEB-061 F-WEB-062 F-WEB-063 F-WEB-064 F-WEB-067 F-ADM-005 F-ADM-011 F-ADM-014 F-ADM-015 F-ADM-020 F-ADM-024 F-ADM-025 F-ADM-026 F-ADM-028 F-ADM-033 F-ADM-036 F-ADM-039 F-ADM-042 F-ADM-043 F-ADM-044 F-ADM-055 F-ADM-056 F-ADM-057 F-ADM-059 F-ADM-064 F-ADM-065 F-ADM-067 F-ADM-078 F-ADM-081 F-API-007 F-API-015 F-API-017 F-API-017b F-API-019 F-API-021 F-API-021a F-API-021b F-API-027 F-API-030 F-API-035a F-API-038 F-API-045 F-API-049 F-API-050 F-API-051 F-API-052 F-API-053 F-API-054 F-API-058 F-API-059 F-API-060 F-API-061 F-API-062 F-API-063 F-API-064 F-API-065 F-API-080 F-API-086 F-SYS-021 F-SYS-024 F-SYS-029 F-SYS-032 F-SYS-035 F-SYS-058 F-SYS-060 F-SYS-061 F-SYS-062 F-SYS-069 F-SYS-075 F-SYS-078 F-SYS-091 F-SYS-096 N-048 N-051 N-052.

**Permanent gate.** `shared/contract/src/jvmTest/kotlin/com/aktcl/aron/contract/BacklogCoverageTest.kt` parses this table and the backlog CSV on every build: every in-scope BUILD row must be in the table and every need must still exist in the contract and in docs/24. A new backlog row, or a lane removing something a row needs, fails `./gradlew :shared:contract:build`.

| Row | Lane | Needs (contract and spec) | Found before | Found after | Gap closed |
|---|---|---|---|---|---|
| F-SR-001 | android-sr | `GET /v1/config/public`; `POST /v1/auth/login`; `GET /v1/sync/bundle`; `GET /v1/sync/bundle/page` | yes | yes | — |
| F-SR-002 | android-sr | `POST /v1/auth/bind-device` | yes | yes | — |
| F-SR-003 | android-sr | local | yes | yes | — |
| F-SR-004 | android-sr | `GET /v1/app/update-check` | yes | yes | — |
| F-SR-005 | android-sr | local | yes | yes | — |
| F-SR-006 | android-sr | `POST /v1/support/pda-upload`; `cfg:cfg.support.max_upload_mb` | no | yes | `POST /v1/support/pda-upload`; `cfg:cfg.support.max_upload_mb` |
| F-SR-007 | android-sr | `POST /v1/auth/logout` | yes | yes | — |
| F-SR-008 | android-sr | `bundle:routes`; `bundle:user` | yes | yes | — |
| F-SR-009 | android-sr | `cfg:cfg.app.home_tiles`; `bundle:tasks` | no | yes | `cfg:cfg.app.home_tiles` |
| F-SR-010 | android-sr | `bundle:routes`; local | yes | yes | — |
| F-SR-011 | android-sr | `rec:attendance_event`; `POST /v1/sync/batch` | yes | yes | — |
| F-SR-012 | android-sr | `rec:attendance_event`; `cfg:cfg.day.checkout_earliest_time` | yes | yes | — |
| F-SR-013 | android-print | local | yes | yes | — |
| F-SR-014 | android-sr | `rec:stock_movement`; `cfg:cfg.stock.resave_guard_window_min` | no | yes | `cfg:cfg.stock.resave_guard_window_min` |
| F-SR-015 | android-print | `rec:print_event` | yes | yes | — |
| F-SR-016 | android-sr | `bundle:routes` | yes | yes | — |
| F-SR-017 | android-sr | `rec:visit`; `cfg:cfg.geo.radius_m`; `cfg:cfg.geo.mock_policy` | yes | yes | — |
| F-SR-018 | android-sr | `rec:visit`; `rec:outlet_change_request`; `rec:media_meta`; `POST /v1/media/sas`; `cfg:cfg.sale.force_requires_photo` | yes | yes | — |
| F-SR-019 | android-sr | `cfg:cfg.geo.refresh_max` | yes | yes | — |
| F-SR-020 | android-sr | `bundle:content`; `rec:content_view`; `cfg:cfg.content.download_network_policy` | no | yes | `bundle:content`; `rec:content_view`; `cfg:cfg.content.download_network_policy` |
| F-SR-021 | android-sr | `rec:survey_response`; `bundle:surveys`; `cfg:cfg.loyalty.earning_rules` | no | yes | `cfg:cfg.loyalty.earning_rules` |
| F-SR-022 | android-sr | `rec:memo_discount`; `rec:memo_line`; `bundle:offers` | yes | yes | — |
| F-SR-023 | android-sr | `rec:memo_line`; `cfg:cfg.sale.qty_entry_unit`; `cfg:cfg.sale.max_lines_per_memo` | yes | yes | — |
| F-SR-024 | android-sr | `bundle:offers`; `rec:memo_discount` | yes | yes | — |
| F-SR-025 | android-sr | `rec:memo`; `rec:qc_line` | yes | yes | — |
| F-SR-026 | android-sr | `rec:memo` | yes | yes | — |
| F-SR-027 | android-sr | `rec:qc_line` | yes | yes | — |
| F-SR-028 | android-print | `rec:memo`; `rec:print_event` | yes | yes | — |
| F-SR-029 | android-sr | `rec:memo`; `rec:visit_close` | yes | yes | — |
| F-SR-030 | android-sr | local; `bundle:routes` | yes | yes | — |
| F-SR-031 | android-print | `rec:print_event`; `cfg:cfg.memo.reprint_max` | yes | yes | — |
| F-SR-032 | android-sr | `rec:due_collection`; `bundle:routes` | yes | yes | — |
| F-SR-033 | android-sr | `rec:memo`; `cfg:cfg.memo.edit_chain_max` | yes | yes | — |
| F-SR-034 | android-sr | `GET /v1/sync/totals`; `POST /v1/sync/batch` | yes | yes | — |
| F-SR-035 | android-sr | `rec:day_submit`; `POST /v1/day/sales-submit` | yes | yes | — |
| F-SR-036 | android-sr | `rec:print_event`; local | yes | yes | — |
| F-SR-037 | android-sr | `rec:outlet_change_request`; `rec:media_meta` | yes | yes | — |
| F-SR-038 | android-sr | `rec:outlet_change_request`; `enum:OutletRequestType.close` | yes | yes | — |
| F-SR-039 | android-sr | `rec:outlet_change_request`; `enum:OutletRequestType.info` | yes | yes | — |
| F-SR-040 | android-sr | `bundle:my_outlet_requests`; `GET /v1/outlet-requests` | no | yes | `bundle:my_outlet_requests` |
| F-SR-041 | android-sr | `bundle:programmes`; `GET /v1/programmes/astha/targets` | no | yes | `bundle:programmes`; `GET /v1/programmes/astha/targets` |
| F-SR-042 | android-sr | `bundle:programmes` | no | yes | `bundle:programmes` |
| F-SR-043 | android-sr | `rec:redemption`; `rec:redemption_line`; `bundle:programmes`; `GET /v1/programmes/loyalty/balances` | no | yes | `rec:redemption`; `rec:redemption_line`; `bundle:programmes`; `GET /v1/programmes/loyalty/balances` |
| F-SR-044 | android-sr | `rec:gift_photo`; `rec:media_meta`; `bundle:programmes` | no | yes | `rec:gift_photo`; `bundle:programmes` |
| F-SR-045 | android-sr | `rec:gift_photo`; `bundle:programmes`; `field:ProgrammesSection.pending_gift_verifications` | no | yes | `rec:gift_photo`; `bundle:programmes`; `field:ProgrammesSection.pending_gift_verifications` |
| F-SR-046 | android-sr | `bundle:tasks`; `GET /v1/tasks` | yes | yes | — |
| F-SR-047 | android-sr | `rec:task_event` | yes | yes | — |
| F-SR-048 | android-sr | `bundle:tutorials`; `GET /v1/tutorials` | no | yes | `bundle:tutorials`; `GET /v1/tutorials` |
| F-SR-049 | android-sr | `bundle:routes`; `bundle:programmes` | no | yes | `bundle:programmes` |
| F-SR-050 | android-sr | `rec:stock_movement`; local | yes | yes | — |
| F-SR-053 | android-sr | `rec:stock_movement`; `enum:StockMovementKind.qc_return` | no | yes | `enum:StockMovementKind.qc_return` |
| F-SR-054 | android-sr | `GET /v1/memos` | yes | yes | — |
| F-SR-055 | android-sr | `bundle:programmes`; `field:LoyaltyBalance.expiring_points` | no | yes | `bundle:programmes`; `field:LoyaltyBalance.expiring_points` |
| F-SR-056 | android-sr | `bundle:routes` | yes | yes | — |
| F-SR-057 | android-sr | `rec:visit_close`; `rec:visit_skip`; `enum:VisitOutcome.not_reached`; `cfg:cfg.visit.closed_streak_task` | no | yes | `enum:VisitOutcome.not_reached`; `cfg:cfg.visit.closed_streak_task` |
| F-SR-060 | android-sr | `rec:visit_close` | yes | yes | — |
| F-SR-063 | android-sr | `cfg:cfg.bundle.stale_max_days`; `rec:day_open` | yes | yes | — |
| F-SR-064 | android-sr | local | yes | yes | — |
| F-SR-065 | android-sr | `bundle:routes`; `rec:day_open` | yes | yes | — |
| F-SR-066 | android-print | `rec:print_event`; `rec:memo` | yes | yes | — |
| F-SR-067 | android-sr | `bundle:routes`; local | yes | yes | — |
| F-SR-068 | android-sr | `bundle:routes` | yes | yes | — |
| F-SR-069 | android-sr | local | yes | yes | — |
| F-SR-072 | android-sr | `rec:visit` | yes | yes | — |
| F-SR-073 | android-print | `rec:print_event` | yes | yes | — |
| F-SR-074 | android-sr | `cfg:cfg.sale.sort_by_distance` | no | yes | `cfg:cfg.sale.sort_by_distance` |
| F-SR-075 | android-sr | `cfg:cfg.ui.outlet_badges`; `bundle:routes` | yes | yes | — |
| F-SR-076 | android-sr | `rec:outlet_change_request`; `enum:OutletRequestType.route_add` | no | yes | `enum:OutletRequestType.route_add` |
| F-SR-079 | android-sr | `rec:media_meta`; `POST /v1/media/sas` | yes | yes | — |
| F-SR-081 | android-sr | `rec:stock_movement`; `enum:StockMovementKind.adjustment` | yes | yes | — |
| F-AMO-001 | android-amo | `cfg:cfg.app.home_tiles`; `bundle:supervisor` | no | yes | `cfg:cfg.app.home_tiles` |
| F-AMO-002 | android-amo | `GET /v1/app/home` | yes | yes | — |
| F-AMO-003 | android-amo | `rec:attendance_event` | yes | yes | — |
| F-AMO-004 | android-amo | `rec:visit`; `enum:VisitKind.amo_control_call`; `bundle:supervisor` | yes | yes | — |
| F-AMO-005 | android-amo | `rec:outlet_change_request`; `rec:visit` | yes | yes | — |
| F-AMO-006 | android-amo | `rec:memo`; `enum:VisitKind.amo_control_call` | yes | yes | — |
| F-AMO-007 | android-amo | `rec:distribution_check`; `rec:distribution_check_line` | yes | yes | — |
| F-AMO-008 | android-amo | `rec:distribution_check_line` | yes | yes | — |
| F-AMO-009 | android-amo | `rec:distribution_check` | yes | yes | — |
| F-AMO-010 | android-amo | `rec:survey_response` | yes | yes | — |
| F-AMO-011 | android-amo | `rec:call_assessment`; `rec:call_assessment_answer`; `bundle:rubrics` | yes | yes | — |
| F-AMO-012 | android-amo | `rec:visit`; `rec:memo` | yes | yes | — |
| F-AMO-013 | android-amo | `GET /v1/memos` | yes | yes | — |
| F-AMO-014 | android-amo | `rec:print_event`; `rec:memo`; `rec:due_collection` | yes | yes | — |
| F-AMO-015 | android-amo | `rec:print_event` | yes | yes | — |
| F-AMO-016 | android-amo | `GET /v1/team/locations` | yes | yes | — |
| F-AMO-017 | android-amo | `GET /v1/dashboards/targets` | yes | yes | — |
| F-AMO-018 | android-amo | `GET /v1/tasks` | yes | yes | — |
| F-AMO-019 | android-amo | `rec:task`; `POST /v1/tasks`; `cfg:cfg.ops.push_enabled` | yes | yes | — |
| F-AMO-020 | android-amo | `GET /v1/dashboards/summary` | yes | yes | — |
| F-AMO-021 | android-amo | `GET /v1/team/stock` | yes | yes | — |
| F-AMO-022 | android-amo | `rec:outlet_request_verification`; `bundle:supervisor` | yes | yes | — |
| F-AMO-023 | android-amo | `rec:outlet_request_verification`; `bundle:programmes` | no | yes | `bundle:programmes` |
| F-AMO-024 | android-amo | `rec:outlet_request_verification` | yes | yes | — |
| F-AMO-025 | android-amo | `rec:outlet_change_request` | yes | yes | — |
| F-AMO-026 | android-amo | `rec:outlet_change_request` | yes | yes | — |
| F-AMO-027 | android-amo | `rec:outlet_change_request` | yes | yes | — |
| F-AMO-028 | android-amo | `rec:outlet_change_request`; `enum:OutletRequestType.location`; `cfg:cfg.sec.fraud.location_move_alert_m` | yes | yes | — |
| F-AMO-029 | android-amo | `rec:stock_movement`; `rec:print_event` | yes | yes | — |
| F-AMO-030 | android-amo | `rec:day_submit`; `rec:price_compliance_check`; `GET /v1/sync/totals` | no | yes | `rec:price_compliance_check` |
| F-AMO-031 | android-amo | `bundle:programmes`; `GET /v1/programmes/astha/targets` | no | yes | `bundle:programmes`; `GET /v1/programmes/astha/targets` |
| F-AMO-032 | android-amo | `report:std-memo` | yes | yes | — |
| F-AMO-033 | android-amo | `report:sales-summary` | no | yes | `report:sales-summary` |
| F-AMO-034 | android-amo | `POST /v1/support/pda-upload`; `POST /v1/auth/logout` | no | yes | `POST /v1/support/pda-upload` |
| F-AMO-035 | android-amo | `rec:price_compliance_check` | no | yes | `rec:price_compliance_check` |
| F-AMO-036 | android-amo | `rec:visit`; `enum:VisitKind.amo_joint_call` | yes | yes | — |
| F-AMO-038 | android-amo | `rec:risk_review`; `bundle:supervisor`; `field:SupervisorSection.risk_signals` | no | yes | `rec:risk_review`; `field:SupervisorSection.risk_signals` |
| F-AMO-039 | backend | `rec:day_submit`; `POST /v1/day/sales-submit` | yes | yes | — |
| F-AMO-040 | android-amo | `bundle:supervisor`; `cfg:cfg.app.home_tiles` | no | yes | `cfg:cfg.app.home_tiles` |
| F-AMO-043 | android-amo | `rec:survey_response` | yes | yes | — |
| F-AMO-044 | backend | `GET /v1/sync/bundle/page`; `GET /v1/sync/delta`; `cfg:cfg.bundle.page_threshold_rows` | yes | yes | — |
| F-AMO-049 | android-amo | `rec:stock_movement` | yes | yes | — |
| F-TSO-001 | android-tso | `POST /v1/auth/login`; `POST /v1/auth/logout` | yes | yes | — |
| F-TSO-002 | android-tso | `GET /v1/app/home`; `GET /v1/dashboards/summary` | yes | yes | — |
| F-TSO-003 | android-tso | `GET /v1/dashboards/summary`; `field:DashboardSummary.by_channel` | no | yes | `field:DashboardSummary.by_channel` |
| F-TSO-004 | android-tso | `GET /v1/dashboards/summary` | yes | yes | — |
| F-TSO-005 | android-tso | `GET /v1/dashboards/summary`; `field:DashboardSummary.by_segment` | no | yes | `field:DashboardSummary.by_segment` |
| F-TSO-006 | android-tso | `GET /v1/dashboards/summary`; `field:DashboardSummary.by_brand` | no | yes | `field:DashboardSummary.by_brand` |
| F-TSO-007 | android-tso | `GET /v1/dashboards/login-submit` | yes | yes | — |
| F-TSO-008 | android-tso | `GET /v1/leave` | yes | yes | — |
| F-TSO-009 | android-tso | `rec:leave_application` | yes | yes | — |
| F-TSO-010 | android-tso | `POST /v1/day/final-submit`; `GET /v1/day/final-submit/preview` | yes | yes | — |
| F-TSO-011 | android-tso | `GET /v1/team/locations` | yes | yes | — |
| F-TSO-012 | android-tso | `GET /v1/outlets/nearby`; `cfg:cfg.tso.periphery_radius_options_m` | no | yes | `GET /v1/outlets/nearby`; `cfg:cfg.tso.periphery_radius_options_m` |
| F-TSO-013 | android-tso | `rec:visit_plan`; `rec:visit_plan_outlet` | yes | yes | — |
| F-TSO-014 | android-tso | `GET /v1/visit-plans`; `rec:call_assessment` | yes | yes | — |
| F-TSO-015 | android-tso | `rec:call_assessment`; `rec:call_assessment_answer` | yes | yes | — |
| F-TSO-016 | android-tso | `rec:task` | yes | yes | — |
| F-TSO-017 | android-tso | `GET /v1/dashboards/targets` | yes | yes | — |
| F-TSO-018 | android-tso | `rec:feedback`; `rec:media_meta` | yes | yes | — |
| F-TSO-019 | backend | `POST /v1/auth/login`; `GET /v1/admin/device-otps` | yes | yes | — |
| F-TSO-020 | web-admin | `GET /v1/programmes/astha/gift-choices`; `PUT /v1/programmes/astha/gift-choices`; `cfg:cfg.astha.gift_choice_lock` | no | yes | `GET /v1/programmes/astha/gift-choices`; `PUT /v1/programmes/astha/gift-choices`; `cfg:cfg.astha.gift_choice_lock` |
| F-TSO-021 | android-tso | `GET /v1/day/final-submit/preview` | yes | yes | — |
| F-TSO-022 | web-admin | `GET /v1/admin/device-otps` | yes | yes | — |
| F-TSO-023 | web-admin | `POST /v1/admin/users/{id}/credentials` | yes | yes | — |
| F-TSO-024 | android-tso | `POST /v1/auth/change-password`; `GET /v1/app/update-check`; `POST /v1/support/pda-upload` | no | yes | `POST /v1/support/pda-upload` |
| F-TSO-025 | web-admin | `POST /v1/admin/config/changes`; `cfg:cfg.geo.tso_radius_mode` | yes | yes | — |
| F-TSO-027 | android-tso | `GET /v1/sync/bundle`; `bundle:supervisor` | yes | yes | — |
| F-TSO-028 | android-tso | local | yes | yes | — |
| F-TSO-029 | android-tso | `GET /v1/feedback` | yes | yes | — |
| F-WEB-001 | web-dashboard | `GET /v1/dashboards/summary`; `GET /v1/team/locations` | yes | yes | — |
| F-WEB-002 | web-dashboard | `GET /v1/admin/outlets`; `report:retailer-list`; `GET /v1/report-exports` | no | yes | `report:retailer-list`; `GET /v1/report-exports` |
| F-WEB-003 | web-admin | `GET /v1/admin/outlets/{id}`; `PATCH /v1/admin/outlets/{id}` | yes | yes | — |
| F-WEB-004 | web-dashboard | `GET /v1/admin/product-nodes/{level}` | yes | yes | — |
| F-WEB-005 | web-dashboard | `GET /v1/admin/product-nodes/{level}` | yes | yes | — |
| F-WEB-006 | web-dashboard | `GET /v1/admin/product-nodes/{level}` | yes | yes | — |
| F-WEB-007 | web-dashboard | `GET /v1/admin/product-nodes/{level}` | yes | yes | — |
| F-WEB-008 | web-dashboard | `GET /v1/admin/skus`; `GET /v1/admin/prices`; `report:sku-list` | no | yes | `report:sku-list` |
| F-WEB-009 | web-dashboard | `GET /v1/admin/product-nodes/{level}`; `GET /v1/admin/skus` | yes | yes | — |
| F-WEB-010 | web-dashboard | `GET /v1/admin/routes`; `GET /v1/admin/route-assignments` | yes | yes | — |
| F-WEB-011 | web-dashboard | `report:task-planner` | no | yes | `report:task-planner` |
| F-WEB-012 | web-dashboard | `report:by-route-geo-capture` | no | yes | `report:by-route-geo-capture` |
| F-WEB-013 | web-dashboard | `report:std-memo` | yes | yes | — |
| F-WEB-014 | web-dashboard | `report:sr-efficiency` | yes | yes | — |
| F-WEB-015 | web-dashboard | `report:route-std` | yes | yes | — |
| F-WEB-016 | web-dashboard | `report:data-entry-log` | yes | yes | — |
| F-WEB-017 | web-dashboard | `report:final-submit-log` | yes | yes | — |
| F-WEB-018 | web-dashboard | `report:route-bsr-cpr` | yes | yes | — |
| F-WEB-019 | web-dashboard | `report:by-outlet` | yes | yes | — |
| F-WEB-020 | web-dashboard | `report:astha` | no | yes | `report:astha` |
| F-WEB-021 | web-dashboard | `report:gigo` | yes | yes | — |
| F-WEB-022 | web-dashboard | `report:campaign-gift-redemption` | no | yes | `report:campaign-gift-redemption` |
| F-WEB-023 | web-dashboard | `report:discount` | yes | yes | — |
| F-WEB-024 | web-dashboard | `report:by-outlet-by-day` | yes | yes | — |
| F-WEB-025 | web-dashboard | `report:online-offline` | yes | yes | — |
| F-WEB-026 | web-dashboard | `report:free-sample` | no | yes | `report:free-sample` |
| F-WEB-027 | web-dashboard | `report:tso-top-sheet` | yes | yes | — |
| F-WEB-028 | web-dashboard | `report:daily-tracking` | yes | yes | — |
| F-WEB-029 | web-dashboard | `report:target-allocation` | no | yes | `report:target-allocation` |
| F-WEB-030 | web-admin | `GET /v1/admin/target-revisions`; `POST /v1/admin/target-revisions/{revision_id}/decision` | no | yes | `GET /v1/admin/target-revisions`; `POST /v1/admin/target-revisions/{revision_id}/decision` |
| F-WEB-031 | web-dashboard | `report:sr-outlets` | yes | yes | — |
| F-WEB-032 | web-admin | `GET /v1/outlet-requests`; `POST /v1/outlet-requests/{request_uuid}/verify`; `POST /v1/outlet-requests/{request_uuid}/reject`; `POST /v1/outlet-requests/{request_uuid}/approve` | yes | yes | — |
| F-WEB-033 | web-dashboard | `POST /v1/auth/change-password` | yes | yes | — |
| F-WEB-034 | web-dashboard | `report:astha-gift-choice` | no | yes | `report:astha-gift-choice` |
| F-WEB-035 | web-dashboard | `GET /v1/tutorials` | no | yes | `GET /v1/tutorials` |
| F-WEB-036 | web-dashboard | `report:leaderboard` | yes | yes | — |
| F-WEB-037 | web-dashboard | `report:superstar-campaign` | no | yes | `report:superstar-campaign` |
| F-WEB-038 | web-dashboard | `GET /v1/dashboards/daily-tracking` | yes | yes | — |
| F-WEB-039 | web-dashboard | `POST /v1/dashboards/daily-tracking/actions`; `cfg:cfg.day.take_action_after` | no | yes | `POST /v1/dashboards/daily-tracking/actions`; `cfg:cfg.day.take_action_after` |
| F-WEB-040 | web-dashboard | `POST /v1/reports/{report_key}/query`; `GET /v1/report-exports/{export_id}` | yes | yes | — |
| F-WEB-041 | web-dashboard | `GET /v1/me` | yes | yes | — |
| F-WEB-042 | backend | `GET /v1/admin/outlets`; `cfg:cfg.bundle.outlet_fields` | yes | yes | — |
| F-WEB-043 | web-dashboard | `POST /v1/auth/login`; `POST /v1/auth/mfa/verify`; `POST /v1/auth/refresh` | yes | yes | — |
| F-WEB-044 | web-dashboard | `report:suspicious-location` | yes | yes | — |
| F-WEB-045 | web-dashboard | `GET /v1/dashboards/sync-health` | yes | yes | — |
| F-WEB-046 | web-dashboard | `GET /v1/leave`; `POST /v1/leave/{leave_uuid}/decision` | yes | yes | — |
| F-WEB-047 | web-dashboard | `report:final-submit-status`; `GET /v1/day/route-days` | yes | yes | — |
| F-WEB-048 | web-admin | `GET /v1/web-entry/outlet-sku`; `POST /v1/web-entry/outlet-sku` | no | yes | `GET /v1/web-entry/outlet-sku`; `POST /v1/web-entry/outlet-sku` |
| F-WEB-049 | web-dashboard | `report:diamond-league` | no | yes | `report:diamond-league` |
| F-WEB-050 | web-admin | `GET /v1/web-entry/route-day`; `POST /v1/web-entry/route-day` | no | yes | `GET /v1/web-entry/route-day`; `POST /v1/web-entry/route-day` |
| F-WEB-051 | web-admin | `POST /v1/day/final-submit`; `GET /v1/day/final-submit/preview`; `POST /v1/admin/data-void` | yes | yes | — |
| F-WEB-052 | web-admin | `POST /v1/web-entry/qc` | no | yes | `POST /v1/web-entry/qc` |
| F-WEB-053 | web-dashboard | `report:ds-rrs`; `reportformat:print` | no | yes | `reportformat:print` |
| F-WEB-054 | web-dashboard | `report:amo-call` | yes | yes | — |
| F-WEB-055 | web-dashboard | `report:dss` | yes | yes | — |
| F-WEB-056 | web-dashboard | `report:route-memo` | yes | yes | — |
| F-WEB-057 | web-dashboard | `GET /v1/risk-signals`; `POST /v1/risk-signals/{signal_id}/review` | yes | yes | — |
| F-WEB-060 | web-admin | `POST /v1/web-entry/qc` | no | yes | `POST /v1/web-entry/qc` |
| F-WEB-061 | web-dashboard | `report:qc-report`; `reportformat:pdf` | no | yes | `reportformat:pdf` |
| F-WEB-062 | web-dashboard | `report:route-qc` | no | yes | `report:route-qc` |
| F-WEB-063 | web-admin | `GET /v1/report-exports` | no | yes | `GET /v1/report-exports` |
| F-WEB-064 | web-dashboard | `report:memo-number-gaps`; `rec:sale_abort` | no | yes | `rec:sale_abort` |
| F-WEB-067 | web-dashboard | `report:geofence-calibration`; `GET /v1/admin/config/calibration` | no | yes | `report:geofence-calibration`; `GET /v1/admin/config/calibration` |
| F-WEB-068 | web-dashboard | `GET /v1/dashboards/summary` | yes | yes | — |
| F-ADM-001 | web-admin | `GET /v1/admin/geo/{level}`; `POST /v1/admin/geo/{level}`; `PATCH /v1/admin/geo/{level}/{id}`; `GET /v1/admin/clusters`; `POST /v1/admin/clusters` | yes | yes | — |
| F-ADM-002 | web-admin | `POST /v1/admin/routes`; `PATCH /v1/admin/routes/{id}` | yes | yes | — |
| F-ADM-003 | web-admin | `POST /v1/admin/route-assignments`; `POST /v1/admin/route-assignments/{id}/end` | yes | yes | — |
| F-ADM-004 | web-admin | `POST /v1/admin/product-nodes/{level}`; `POST /v1/admin/skus`; `PATCH /v1/admin/skus/{id}` | yes | yes | — |
| F-ADM-005 | web-admin | `POST /v1/admin/prices`; `POST /v1/admin/prices/preview` | no | yes | `POST /v1/admin/prices/preview` |
| F-ADM-006 | web-admin | `GET /v1/admin/sales-plans/{zone_id}`; `PUT /v1/admin/sales-plans/{zone_id}` | yes | yes | — |
| F-ADM-007 | web-admin | `POST /v1/admin/users`; `PATCH /v1/admin/users/{id}`; `POST /v1/admin/users/{id}/credentials` | yes | yes | — |
| F-ADM-008 | web-admin | `PUT /v1/admin/users/{id}/scope` | yes | yes | — |
| F-ADM-009 | web-admin | `GET /v1/admin/devices`; `POST /v1/admin/devices/{device_id}/state` | yes | yes | — |
| F-ADM-010 | web-admin | `POST /v1/admin/outlets`; `PATCH /v1/admin/outlets/{id}` | yes | yes | — |
| F-ADM-011 | web-admin | `PUT /v1/admin/code-lists/{list_key}`; `enum:CodeListKey.channel`; `enum:CodeListKey.sub_channel`; `enum:CodeListKey.geo_class` | no | yes | `enum:CodeListKey.channel`; `enum:CodeListKey.sub_channel`; `enum:CodeListKey.geo_class` |
| F-ADM-012 | backend | `POST /v1/admin/config/changes`; `cfg:cfg.geo.radius_m`; `cfg:cfg.geo.radius_min_m`; `cfg:cfg.geo.radius_max_m` | yes | yes | — |
| F-ADM-013 | web-admin | `GET /v1/admin/config/keys`; `POST /v1/admin/config/changes`; `GET /v1/admin/config/reach/{version}` | yes | yes | — |
| F-ADM-014 | web-admin | `PUT /v1/admin/targets`; `POST /v1/admin/targets/upload`; `GET /v1/admin/target-revisions` | no | yes | `POST /v1/admin/targets/upload`; `GET /v1/admin/target-revisions` |
| F-ADM-015 | web-admin | `cfg:cfg.target.approval_levels` | no | yes | `cfg:cfg.target.approval_levels` |
| F-ADM-016 | web-admin | `POST /v1/admin/offers`; `PATCH /v1/admin/offers/{id}` | yes | yes | — |
| F-ADM-020 | web-admin | `POST /v1/admin/surveys`; `POST /v1/admin/rubrics`; `POST /v1/admin/content`; `POST /v1/admin/assets` | no | yes | `POST /v1/admin/surveys`; `POST /v1/admin/rubrics`; `POST /v1/admin/content`; `POST /v1/admin/assets` |
| F-ADM-021 | web-admin | `PUT /v1/admin/code-lists/{list_key}`; `enum:CodeListKey.task_type` | yes | yes | — |
| F-ADM-022 | web-admin | `GET /v1/admin/device-otps`; `POST /v1/admin/device-otps` | yes | yes | — |
| F-ADM-023 | web-admin | `PUT /v1/admin/code-lists/{list_key}`; `enum:CodeListKey.qc_fault_type` | yes | yes | — |
| F-ADM-024 | web-admin | `POST /v1/web-entry/route-day`; `POST /v1/admin/data-entry`; `POST /v1/web-entry/outlet-sku` | no | yes | `POST /v1/web-entry/route-day`; `POST /v1/admin/data-entry`; `POST /v1/web-entry/outlet-sku` |
| F-ADM-025 | web-admin | `GET /v1/admin/supervisor-targets`; `PUT /v1/admin/supervisor-targets` | no | yes | `GET /v1/admin/supervisor-targets`; `PUT /v1/admin/supervisor-targets` |
| F-ADM-026 | web-admin | `GET /v1/admin/tutorials`; `POST /v1/admin/tutorials`; `PATCH /v1/admin/tutorials/{id}`; `POST /v1/admin/assets` | no | yes | `GET /v1/admin/tutorials`; `POST /v1/admin/tutorials`; `PATCH /v1/admin/tutorials/{id}`; `POST /v1/admin/assets` |
| F-ADM-027 | web-admin | `POST /v1/admin/releases`; `PATCH /v1/admin/releases/{release_id}`; `GET /v1/admin/releases/policy` | yes | yes | — |
| F-ADM-028 | web-admin | `GET /v1/feedback`; `PATCH /v1/feedback/{feedback_uuid}` | no | yes | `PATCH /v1/feedback/{feedback_uuid}` |
| F-ADM-029 | web-admin | `POST /v1/day/reopen` | yes | yes | — |
| F-ADM-030 | web-admin | `GET /v1/admin/quarantine`; `POST /v1/admin/quarantine/{quarantine_id}/resolve` | yes | yes | — |
| F-ADM-033 | web-admin | `GET /v1/admin/calendar/holidays`; `POST /v1/admin/calendar/holidays`; `cfg:cfg.calendar.weekend_days` | no | yes | `cfg:cfg.calendar.weekend_days` |
| F-ADM-034 | web-admin | `GET /v1/admin/audit` | yes | yes | — |
| F-ADM-036 | web-admin | `GET /v1/admin/dues-adjustments`; `POST /v1/admin/dues-adjustments`; `POST /v1/admin/dues-adjustments/{adjustment_id}/decision` | no | yes | `GET /v1/admin/dues-adjustments`; `POST /v1/admin/dues-adjustments`; `POST /v1/admin/dues-adjustments/{adjustment_id}/decision` |
| F-ADM-037 | backend | `POST /v1/outlet-requests/{request_uuid}/approve` | yes | yes | — |
| F-ADM-038 | web-admin | `GET /v1/admin/config/versions`; `GET /v1/admin/config/changes` | yes | yes | — |
| F-ADM-039 | web-admin | `POST /v1/admin/config/changes`; `GET /v1/admin/config/density`; `GET /v1/admin/config/whatif` | no | yes | `GET /v1/admin/config/density`; `GET /v1/admin/config/whatif` |
| F-ADM-040 | web-admin | `GET /v1/admin/config/keys`; `POST /v1/admin/config/changes` | yes | yes | — |
| F-ADM-041 | web-admin | `POST /v1/admin/config/changes`; `cfg:cfg.ops.read_only_mode`; `cfg:cfg.ops.sync_hold_s`; `cfg:cfg.ops.maintenance_banner` | yes | yes | — |
| F-ADM-042 | web-admin | `GET /v1/admin/config/changes/{change_id}`; `POST /v1/admin/config/changes/{change_id}/decision`; `GET /v1/admin/config/blast-radius` | no | yes | `GET /v1/admin/config/blast-radius` |
| F-ADM-043 | web-admin | `GET /v1/admin/config/versions/{version}`; `POST /v1/admin/config/versions/{version}/rollback` | no | yes | `GET /v1/admin/config/versions/{version}` |
| F-ADM-044 | web-admin | `GET /v1/admin/config/reach/{version}`; `GET /v1/admin/config/reach/{version}/pending` | no | yes | `GET /v1/admin/config/reach/{version}/pending` |
| F-ADM-046 | web-admin | `GET /v1/admin/geo/{level}`; `GET /v1/admin/skus`; `GET /v1/admin/users`; `GET /v1/admin/code-lists`; `GET /v1/admin/calendar/holidays` | yes | yes | — |
| F-ADM-048 | web-admin | `GET /v1/admin/devices/{device_id}`; `POST /v1/admin/devices/{device_id}/state`; `POST /v1/admin/device-otps` | yes | yes | — |
| F-ADM-049 | web-admin | `POST /v1/admin/releases`; `PATCH /v1/admin/releases/{release_id}` | yes | yes | — |
| F-ADM-050 | web-admin | `GET /v1/dashboards/sync-health` | yes | yes | — |
| F-ADM-051 | web-admin | `GET /v1/admin/quarantine` | yes | yes | — |
| F-ADM-052 | web-admin | `POST /v1/day/reopen`; `GET /v1/day/route-days` | yes | yes | — |
| F-ADM-053 | web-admin | `GET /v1/admin/audit` | yes | yes | — |
| F-ADM-055 | web-admin | `cfg:cfg.flag.loyalty_ui`; `POST /v1/admin/config/changes` | no | yes | `cfg:cfg.flag.loyalty_ui` |
| F-ADM-056 | web-admin | `POST /v1/admin/outlets/outlet-kind` | no | yes | `POST /v1/admin/outlets/outlet-kind` |
| F-ADM-057 | web-admin | `POST /v1/admin/entry-unlocks`; `GET /v1/admin/entry-unlocks` | no | yes | `POST /v1/admin/entry-unlocks`; `GET /v1/admin/entry-unlocks` |
| F-ADM-058 | backend | `POST /v1/admin/data-void` | yes | yes | — |
| F-ADM-059 | web-admin | `POST /v1/admin/targets/upload`; `GET /v1/admin/targets/template` | no | yes | `POST /v1/admin/targets/upload`; `GET /v1/admin/targets/template` |
| F-ADM-060 | web-admin | `PUT /v1/admin/code-lists/{list_key}` | yes | yes | — |
| F-ADM-064 | web-admin | `GET /v1/admin/permissions`; `PUT /v1/admin/permissions/roles/{role}`; `cfg:cfg.web.menu_by_role` | no | yes | `GET /v1/admin/permissions`; `PUT /v1/admin/permissions/roles/{role}`; `cfg:cfg.web.menu_by_role` |
| F-ADM-065 | web-admin | `PATCH /v1/admin/skus/{id}`; `POST /v1/admin/assets` | no | yes | `POST /v1/admin/assets` |
| F-ADM-066 | web-admin | `cfg:cfg.ui.outlet_badges` | yes | yes | — |
| F-ADM-067 | web-admin | `GET /v1/admin/print-templates`; `POST /v1/admin/print-templates` | no | yes | `GET /v1/admin/print-templates`; `POST /v1/admin/print-templates` |
| F-ADM-070 | web-admin | `PATCH /v1/admin/outlets/{id}` | yes | yes | — |
| F-ADM-071 | web-admin | `POST /v1/admin/route-assignments` | yes | yes | — |
| F-ADM-076 | web-admin | `POST /v1/admin/users`; `PATCH /v1/admin/users/{id}`; `POST /v1/admin/device-otps` | yes | yes | — |
| F-ADM-078 | web-admin | `POST /v1/admin/devices/{device_id}/replace` | no | yes | `POST /v1/admin/devices/{device_id}/replace` |
| F-ADM-081 | backend | `POST /v1/admin/prices/preview`; `POST /v1/admin/prices/batches/{batch_uuid}/decision`; `cfg:cfg.price.max_change_pct` | no | yes | `POST /v1/admin/prices/preview`; `POST /v1/admin/prices/batches/{batch_uuid}/decision`; `cfg:cfg.price.max_change_pct` |
| F-API-001 | backend | `POST /v1/auth/login` | yes | yes | — |
| F-API-002 | backend | `POST /v1/auth/refresh` | yes | yes | — |
| F-API-003 | backend | `POST /v1/auth/bind-device` | yes | yes | — |
| F-API-004 | backend | `POST /v1/auth/change-password` | yes | yes | — |
| F-API-005 | backend | `GET /v1/sync/bundle`; `GET /v1/sync/delta` | yes | yes | — |
| F-API-006 | backend | `POST /v1/sync/batch` | yes | yes | — |
| F-API-007 | backend | `POST /v1/media/upload` | no | yes | `POST /v1/media/upload` |
| F-API-008 | backend | `POST /v1/day/sales-submit` | yes | yes | — |
| F-API-009 | backend | `POST /v1/day/final-submit` | yes | yes | — |
| F-API-010 | backend | `GET /v1/admin/outlets` | yes | yes | — |
| F-API-011 | backend | `GET /v1/admin/routes`; `GET /v1/admin/route-assignments` | yes | yes | — |
| F-API-012 | backend | `GET /v1/admin/targets` | yes | yes | — |
| F-API-013 | backend | `report:leaderboard` | yes | yes | — |
| F-API-014 | backend | `GET /v1/dashboards/summary` | yes | yes | — |
| F-API-015 | backend | `GET /v1/dashboards/daily-tracking`; `POST /v1/dashboards/daily-tracking/actions` | no | yes | `POST /v1/dashboards/daily-tracking/actions` |
| F-API-016 | backend | `GET /v1/dashboards/summary`; `GET /v1/app/home` | yes | yes | — |
| F-API-017 | backend | `POST /v1/reports/{report_key}/query`; `reportformat:pdf`; `reportformat:print` | no | yes | `reportformat:pdf`; `reportformat:print` |
| F-API-017a | backend | `report:memo-number-gaps` | yes | yes | — |
| F-API-017b | backend | `report:std-memo`; `report:sales-summary` | no | yes | `report:sales-summary` |
| F-API-018 | backend | `GET /v1/app/home` | yes | yes | — |
| F-API-019 | backend | `GET /v1/outlets/nearby` | no | yes | `GET /v1/outlets/nearby` |
| F-API-020 | backend | `GET /v1/visit-plans`; `rec:visit_plan` | yes | yes | — |
| F-API-020b | backend | `GET /v1/admin/route-assignments` | yes | yes | — |
| F-API-021 | backend | `PUT /v1/admin/targets`; `GET /v1/admin/target-revisions`; `POST /v1/admin/target-revisions/{revision_id}/decision` | no | yes | `GET /v1/admin/target-revisions`; `POST /v1/admin/target-revisions/{revision_id}/decision` |
| F-API-021a | backend | `GET /v1/admin/targets`; `GET /v1/dashboards/targets`; `GET /v1/programmes/astha/targets` | no | yes | `GET /v1/programmes/astha/targets` |
| F-API-021b | backend | `PUT /v1/programmes/astha/targets`; `PUT /v1/programmes/astha/gift-choices` | no | yes | `PUT /v1/programmes/astha/targets`; `PUT /v1/programmes/astha/gift-choices` |
| F-API-022 | backend | `GET /v1/leave`; `rec:leave_application`; `POST /v1/leave/{leave_uuid}/decision` | yes | yes | — |
| F-API-023 | backend | `GET /v1/team/locations` | yes | yes | — |
| F-API-024 | backend | `GET /v1/team/stock` | yes | yes | — |
| F-API-025 | backend | `GET /v1/memos` | yes | yes | — |
| F-API-026 | backend | `GET /v1/tasks`; `POST /v1/tasks`; `rec:task_event` | yes | yes | — |
| F-API-027 | backend | `GET /v1/tutorials` | no | yes | `GET /v1/tutorials` |
| F-API-028 | backend | `GET /v1/dashboards/sync-health`; `GET /v1/admin/quarantine`; `POST /v1/admin/quarantine/{quarantine_id}/resolve` | yes | yes | — |
| F-API-029 | backend | `GET /v1/app/update-check` | yes | yes | — |
| F-API-030 | backend | `POST /v1/support/pda-upload` | no | yes | `POST /v1/support/pda-upload` |
| F-API-031 | backend | `POST /v1/auth/logout` | yes | yes | — |
| F-API-032 | backend | `rec:feedback`; `GET /v1/feedback` | yes | yes | — |
| F-API-034 | backend | `report:suspicious-location`; `GET /v1/risk-signals` | yes | yes | — |
| F-API-035 | backend | `GET /v1/admin/geo/{level}`; `GET /v1/admin/clusters`; `PUT /v1/admin/code-lists/{list_key}` | yes | yes | — |
| F-API-035a | backend | `POST /v1/admin/offers`; `POST /v1/admin/surveys`; `POST /v1/admin/rubrics`; `POST /v1/admin/content`; `POST /v1/admin/programmes` | no | yes | `POST /v1/admin/surveys`; `POST /v1/admin/rubrics`; `POST /v1/admin/content`; `POST /v1/admin/programmes` |
| F-API-035b | backend | `POST /v1/admin/users`; `PUT /v1/admin/users/{id}/scope`; `POST /v1/admin/routes`; `POST /v1/admin/route-assignments`; `POST /v1/admin/outlets`; `PUT /v1/admin/code-lists/{list_key}` | yes | yes | — |
| F-API-035c | backend | `POST /v1/admin/skus`; `POST /v1/admin/prices`; `POST /v1/admin/product-nodes/{level}` | yes | yes | — |
| F-API-036 | backend | `GET /v1/admin/device-otps`; `POST /v1/admin/device-otps` | yes | yes | — |
| F-API-037 | backend | `GET /v1/admin/config/values`; `POST /v1/admin/config/changes`; `GET /v1/sync/bundle` | yes | yes | — |
| F-API-038 | backend | `POST /v1/admin/data-entry` | no | yes | `POST /v1/admin/data-entry` |
| F-API-039 | backend | `GET /v1/day/final-submit/preview` | yes | yes | — |
| F-API-040 | backend | `GET /v1/config/delta` | yes | yes | — |
| F-API-041 | backend | `rec:config_ack` | yes | yes | — |
| F-API-042 | backend | `GET /v1/config/public` | yes | yes | — |
| F-API-045 | backend | `POST /v1/admin/outlets/outlet-kind` | no | yes | `POST /v1/admin/outlets/outlet-kind` |
| F-API-048 | backend | `POST /v1/admin/data-void` | yes | yes | — |
| F-API-049 | backend | `POST /v1/admin/entry-unlocks`; `POST /v1/admin/entry-unlocks/{unlock_id}/expire` | no | yes | `POST /v1/admin/entry-unlocks`; `POST /v1/admin/entry-unlocks/{unlock_id}/expire` |
| F-API-050 | backend | `POST /v1/web-entry/route-day` | no | yes | `POST /v1/web-entry/route-day` |
| F-API-051 | backend | `POST /v1/web-entry/outlet-sku` | no | yes | `POST /v1/web-entry/outlet-sku` |
| F-API-052 | backend | `POST /v1/web-entry/qc` | no | yes | `POST /v1/web-entry/qc` |
| F-API-053 | backend | `reportformat:print`; `GET /v1/report-exports/{export_id}` | no | yes | `reportformat:print` |
| F-API-054 | backend | `POST /v1/admin/targets/upload`; `GET /v1/admin/targets/template` | no | yes | `POST /v1/admin/targets/upload`; `GET /v1/admin/targets/template` |
| F-API-055 | backend | `POST /v1/outlet-requests/{request_uuid}/verify`; `POST /v1/outlet-requests/{request_uuid}/reject`; `POST /v1/outlet-requests/{request_uuid}/approve` | yes | yes | — |
| F-API-057 | backend | `POST /v1/media/sas` | yes | yes | — |
| F-API-058 | backend | `GET /v1/admin/config/whatif` | no | yes | `GET /v1/admin/config/whatif` |
| F-API-059 | backend | `GET /v1/admin/config/blast-radius` | no | yes | `GET /v1/admin/config/blast-radius` |
| F-API-060 | backend | `GET /v1/admin/config/density`; `GET /v1/admin/config/calibration` | no | yes | `GET /v1/admin/config/density`; `GET /v1/admin/config/calibration` |
| F-API-061 | backend | `POST /v1/admin/config/changes`; `GET /v1/admin/config/changes`; `POST /v1/admin/config/changes/{change_id}/decision`; `field:ConfigChangeRequest.break_glass` | no | yes | `field:ConfigChangeRequest.break_glass` |
| F-API-062 | backend | `GET /v1/admin/config/versions`; `GET /v1/admin/config/versions/{version}`; `POST /v1/admin/config/versions/{version}/rollback` | no | yes | `GET /v1/admin/config/versions/{version}` |
| F-API-063 | backend | `GET /v1/admin/config/reach/{version}`; `GET /v1/admin/config/reach/{version}/pending` | no | yes | `GET /v1/admin/config/reach/{version}/pending` |
| F-API-064 | backend | `GET /v1/admin/permissions`; `PUT /v1/admin/permissions/roles/{role}` | no | yes | `GET /v1/admin/permissions`; `PUT /v1/admin/permissions/roles/{role}` |
| F-API-065 | backend | `cfg:cfg.flag.loyalty_ui`; `POST /v1/admin/config/changes`; `GET /v1/config/delta` | no | yes | `cfg:cfg.flag.loyalty_ui` |
| F-API-070 | backend | `GET /v1/sync/generation` | yes | yes | — |
| F-API-080 | backend | `POST /v1/admin/prices/preview`; `POST /v1/admin/prices` | no | yes | `POST /v1/admin/prices/preview` |
| F-API-083 | backend | `GET /v1/config/delta`; `cfg:cfg.sync.config_check_min_gap_min` | yes | yes | — |
| F-API-086 | backend | `POST /v1/admin/devices/{device_id}/replace` | no | yes | `POST /v1/admin/devices/{device_id}/replace` |
| F-SYS-001 | backend | `POST /v1/auth/login` | yes | yes | — |
| F-SYS-002 | backend | `POST /v1/auth/refresh` | yes | yes | — |
| F-SYS-003 | backend | `POST /v1/auth/bind-device`; `GET /v1/admin/device-otps` | yes | yes | — |
| F-SYS-004 | backend | `POST /v1/auth/change-password` | yes | yes | — |
| F-SYS-005 | backend | `GET /v1/me`; `GET /v1/admin/outlets` | yes | yes | — |
| F-SYS-006 | android-core | `GET /v1/sync/bundle`; `rec:day_open` | yes | yes | — |
| F-SYS-007 | android-core | `GET /v1/sync/delta` | yes | yes | — |
| F-SYS-008 | android-core | `POST /v1/sync/batch` | yes | yes | — |
| F-SYS-009 | android-core | `GET /v1/sync/totals`; `POST /v1/sync/batch` | yes | yes | — |
| F-SYS-010 | android-core | `POST /v1/media/sas`; `rec:media_meta` | yes | yes | — |
| F-SYS-011 | android-core | local; `cfg:cfg.sync.periodic_min` | yes | yes | — |
| F-SYS-012 | backend | `rec:visit`; `GET /v1/dashboards/geo-validation` | yes | yes | — |
| F-SYS-013 | backend | `GET /v1/risk-signals`; `enum:RiskSignalCode.GEO_TELEPORT`; `enum:RiskSignalCode.GEO_ZERO_JITTER` | yes | yes | — |
| F-SYS-014 | backend | `POST /v1/sync/batch`; `GET /v1/admin/quarantine` | yes | yes | — |
| F-SYS-015 | backend | `GET /v1/dashboards/summary`; internal | yes | yes | — |
| F-SYS-016 | backend | `GET /v1/day/route-days`; `rec:day_submit` | yes | yes | — |
| F-SYS-018 | android-core | local | yes | yes | — |
| F-SYS-019 | android-core | local | yes | yes | — |
| F-SYS-020 | android-core | `GET /v1/app/update-check`; `cfg:cfg.release.min_version_code` | yes | yes | — |
| F-SYS-021 | android-core | `POST /v1/support/pda-upload` | no | yes | `POST /v1/support/pda-upload` |
| F-SYS-022 | android-core | `POST /v1/auth/logout` | yes | yes | — |
| F-SYS-023 | android-core | local | yes | yes | — |
| F-SYS-024 | android-core | `rec:activity_log` | no | yes | `rec:activity_log` |
| F-SYS-025 | backend | `report:data-entry-log` | yes | yes | — |
| F-SYS-026 | backend | `GET /v1/dashboards/sync-health` | yes | yes | — |
| F-SYS-027 | android-core | `rec:memo`; `cfg:cfg.memo.seq_block_size` | yes | yes | — |
| F-SYS-028 | android-core | local | yes | yes | — |
| F-SYS-029 | android-core | local; `cfg:cfg.content.max_item_mb` | no | yes | `cfg:cfg.content.max_item_mb` |
| F-SYS-030 | android-core | `rec:media_meta`; `cfg:cfg.media.photo_max_kb` | yes | yes | — |
| F-SYS-031 | android-geo | `rec:device_status`; `POST /v1/devices/me/status` | yes | yes | — |
| F-SYS-032 | android-core | `rec:app_error`; `POST /v1/client-errors` | no | yes | `rec:app_error`; `POST /v1/client-errors` |
| F-SYS-033 | android-core | local | yes | yes | — |
| F-SYS-034 | backend | `PUT /v1/admin/targets`; `GET /v1/dashboards/targets` | yes | yes | — |
| F-SYS-035 | backend | `field:BundleOutlet.suggested_qty`; `cfg:cfg.sale.suggested_qty_enabled` | no | yes | `field:BundleOutlet.suggested_qty`; `cfg:cfg.sale.suggested_qty_enabled` |
| F-SYS-037 | android-core | `cfg:cfg.media.wifi_only_default`; `cfg:cfg.media.evidence_mobile_fallback_h` | yes | yes | — |
| F-SYS-044 | android-core | local | yes | yes | — |
| F-SYS-046 | android-core | local; `cfg:cfg.sync.debounce_s` | yes | yes | — |
| F-SYS-047 | android-core | `GET /v1/sync/generation` | yes | yes | — |
| F-SYS-048 | backend | `POST /v1/sync/batch`; `cfg:cfg.sync.family_skip_after` | yes | yes | — |
| F-SYS-049 | android-core | local; `cfg:cfg.sync.max_clock_skew_min` | yes | yes | — |
| F-SYS-050 | backend | `POST /v1/sync/batch`; `field:SyncBatchRequest.telemetry`; `param:XPendingRows` | yes | yes | — |
| F-SYS-052 | android-core | local; `cfg:cfg.auth.offline_unlock_max_days` | yes | yes | — |
| F-SYS-053 | android-core | `GET /v1/config/delta`; `rec:config_ack` | yes | yes | — |
| F-SYS-054 | backend | `cfg:cfg.release.min_version_code`; `cfg:cfg.release.blocked_version_codes` | yes | yes | — |
| F-SYS-055 | backend | `POST /v1/sync/batch` | yes | yes | — |
| F-SYS-056 | backend | `GET /v1/day/route-days`; internal | yes | yes | — |
| F-SYS-057 | backend | `GET /v1/risk-signals`; `POST /v1/risk-signals/{signal_id}/review` | yes | yes | — |
| F-SYS-058 | backend | `GET /v1/report-exports` | no | yes | `GET /v1/report-exports` |
| F-SYS-059 | backend | `GET /v1/admin/audit` | yes | yes | — |
| F-SYS-060 | backend | `report:dues-ageing`; `GET /v1/admin/dues-adjustments` | no | yes | `GET /v1/admin/dues-adjustments` |
| F-SYS-061 | backend | `rec:redemption`; `rec:survey_response`; `cfg:cfg.loyalty.expiry_days`; `cfg:cfg.loyalty.negative_balance_policy`; `GET /v1/programmes/loyalty/balances` | no | yes | `rec:redemption`; `cfg:cfg.loyalty.expiry_days`; `cfg:cfg.loyalty.negative_balance_policy`; `GET /v1/programmes/loyalty/balances` |
| F-SYS-062 | backend | `enum:RecordOutcomeCode.arithmetic_mismatch`; `enum:RiskSignalCode.GEO_OUT_OF_BOUNDS` | no | yes | `enum:RiskSignalCode.GEO_OUT_OF_BOUNDS` |
| F-SYS-063 | backend | internal | yes | yes | — |
| F-SYS-064 | backend | `POST /v1/reports/{report_key}/query`; `GET /v1/report-exports/{export_id}` | yes | yes | — |
| F-SYS-067 | backend | `GET /v1/sync/bundle`; `cfg:cfg.bundle.d1_generation_time` | yes | yes | — |
| F-SYS-069 | backend | `report:memo-number-gaps`; `rec:sale_abort` | no | yes | `rec:sale_abort` |
| F-SYS-071 | android-core | local | yes | yes | — |
| F-SYS-072 | android-core | `POST /v1/sync/batch`; `param:XDeviceProof` | yes | yes | — |
| F-SYS-073 | android-core | `POST /v1/admin/notifications`; `GET /v1/config/delta` | yes | yes | — |
| F-SYS-074 | android-core | local | yes | yes | — |
| F-SYS-075 | android-core | `rec:consent_accept`; `cfg:cfg.app.location_notice_required` | no | yes | `rec:consent_accept`; `cfg:cfg.app.location_notice_required` |
| F-SYS-078 | backend | `enum:VisitKind.web_entry`; `enum:VisitKind.tso_visit` | no | yes | `enum:VisitKind.web_entry` |
| F-SYS-079 | android-core | `cfg:cfg.sync.checkout_jitter_s` | yes | yes | — |
| F-SYS-080 | android-core | `POST /v1/sync/digest` | yes | yes | — |
| F-SYS-081 | android-core | `field:SyncBatchRequest.telemetry` | yes | yes | — |
| F-SYS-084 | backend | `GET /v1/dashboards/sync-health`; `cfg:cfg.sla.pending_rows_alert_h` | yes | yes | — |
| F-SYS-086 | backend | internal | yes | yes | — |
| F-SYS-089 | backend | `GET /v1/sync/generation`; `cfg:cfg.sync.resync_window_h` | yes | yes | — |
| F-SYS-090 | backend | `cfg:cfg.bundle.stale_max_days`; `cfg:cfg.sync.max_backdate_days` | yes | yes | — |
| F-SYS-091 | backend | `enum:RiskSignalCode.CONFIG_STAMP_REGRESS`; `cfg:cfg.sys.config_accept_window_h` | no | yes | `enum:RiskSignalCode.CONFIG_STAMP_REGRESS` |
| F-SYS-092 | android-core | `GET /v1/config/delta`; `cfg:cfg.sync.config_check_min_gap_min` | yes | yes | — |
| F-SYS-094 | backend | `POST /v1/admin/data-void`; `enum:RecordOutcomeCode.voided_by_admin` | yes | yes | — |
| F-SYS-096 | backend | `rec:activity_log`; `rec:consent_accept`; `rec:device_status` | no | yes | `rec:activity_log`; `rec:consent_accept` |
| N-001 | android-core | local | yes | yes | — |
| N-009 | backend | `GET /v1/health` | yes | yes | — |
| N-010 | web-dashboard | `POST /v1/auth/login`; `GET /v1/me` | yes | yes | — |
| N-011 | web-admin | `GET /v1/admin/clusters`; `POST /v1/admin/clusters`; `GET /v1/admin/audit` | yes | yes | — |
| N-016 | android-core | local | yes | yes | — |
| N-017 | backend | `bundle:routes`; `GET /v1/admin/routes` | yes | yes | — |
| N-018 | android-print | local; `bundle:templates` | yes | yes | — |
| N-019 | android-print | local | yes | yes | — |
| N-021 | android-geo | `rec:visit`; `cfg:cfg.geo.fix_reuse_max_age_s` | yes | yes | — |
| N-023 | android-core | local | yes | yes | — |
| N-025 | android-geo | `field:GeoFix.gnss` | yes | yes | — |
| N-026 | android-geo | `POST /v1/devices/nonce`; `POST /v1/devices/enrol` | yes | yes | — |
| N-027 | backend | `POST /v1/devices/enrol`; `cfg:cfg.device.require_enrolled`; `cfg:cfg.device.require_integrity` | yes | yes | — |
| N-028 | backend | `enum:RiskSignalCode.GEO_GNSS_INCONSISTENT` | yes | yes | — |
| N-029 | android-dpc | `GET /v1/devices/me/policy` | yes | yes | — |
| N-030 | android-dpc | `POST /v1/devices/enrol`; `POST /v1/admin/enrolment-tokens` | yes | yes | — |
| N-031 | backend | `POST /v1/admin/enrolment-tokens`; `POST /v1/devices/me/status`; `POST /v1/admin/devices/{device_id}/state` | yes | yes | — |
| N-032 | android-dpc | `GET /v1/devices/me/policy`; `cfg:cfg.device.blocked_packages` | yes | yes | — |
| N-033 | backend | `cfg:cfg.device.blocked_packages`; `GET /v1/devices/me/policy` | yes | yes | — |
| N-034 | android-dpc | `GET /v1/app/update-check` | yes | yes | — |
| N-035 | android-geo | `rec:geo_breadcrumb`; `cfg:cfg.geo.breadcrumbs_enabled` | yes | yes | — |
| N-036 | backend | `rec:geo_breadcrumb`; `GET /v1/team/locations` | yes | yes | — |
| N-037 | backend | `PUT /v1/devices/me/push-token`; `POST /v1/admin/notifications`; `cfg:cfg.notify.task_push_enabled` | yes | yes | — |
| N-038 | android-core | local; `PUT /v1/devices/me/push-token` | yes | yes | — |
| N-040 | android-sr | `rec:outlet_change_request`; `enum:OutletRequestType.cluster` | yes | yes | — |
| N-041 | android-sr | local | yes | yes | — |
| N-044 | backend | `POST /v1/outlet-requests/{request_uuid}/approve` | yes | yes | — |
| N-045 | web-admin | `POST /v1/admin/enrolment-tokens`; `GET /v1/admin/devices` | yes | yes | — |
| N-046 | web-admin | `cfg:cfg.device.blocked_packages`; `POST /v1/admin/config/changes` | yes | yes | — |
| N-047 | web-dashboard | local | yes | yes | — |
| N-048 | backend | `report:std-memo`; `report:sr-efficiency`; `report:route-std`; `report:route-memo`; `report:route-bsr-cpr`; `report:by-outlet`; `report:by-outlet-by-day`; `report:online-offline`; `report:task-planner`; `report:by-route-geo-capture` | no | yes | `report:task-planner`; `report:by-route-geo-capture` |
| N-049 | backend | `field:Outlet.external_ref`; internal | yes | yes | — |
| N-051 | backend | `report:data-entry-log`; `report:final-submit-log`; `report:gigo`; `report:dss`; `report:ds-rrs`; `report:tso-top-sheet`; `report:daily-tracking`; `report:leaderboard`; `report:amo-call`; `report:sr-outlets`; `report:discount`; `report:free-sample` | no | yes | `report:free-sample` |
| N-052 | backend | `report:qc-report`; `report:route-qc`; `report:target-allocation`; `report:astha`; `report:campaign-gift-redemption`; `report:diamond-league`; `report:superstar-campaign`; `report:memo-number-gaps` | no | yes | `report:route-qc`; `report:target-allocation`; `report:astha`; `report:campaign-gift-redemption`; `report:diamond-league`; `report:superstar-campaign` |
| N-053 | android-amo | local | yes | yes | — |
| N-056 | backend | `POST /v1/sync/batch`; `enum:ProblemCode.ERR_RATE_LIMITED` | yes | yes | — |
