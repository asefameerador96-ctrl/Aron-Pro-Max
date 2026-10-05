# 24 — Build spec: verification log

**Date: 2026-10-05. Author: the architect agent.** What was run to prove `docs/24-build-spec.md`, `contract/openapi.yaml`, the Gradle skeleton, `tools/android-sdk.sh` and `.github/workflows/ci.yml`, with the real (shortened) output, and what could not be verified and why. Outputs are trimmed to the decisive lines; the `JAVA_TOOL_OPTIONS` proxy banner printed by every JVM tool in this container is removed.

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
| `./gradlew` build and tests of `shared`, `db`, `backend` (PostgreSQL 16) | **pass**, 61 tasks, 40 tests, 0 failures |
| Three debug APKs (`assembleDebug`) | **pass**: `com.aktcl.aron.sr`, `.amo`, `.tso` |
| Android local unit tests (`testDebugUnitTest`, incl. Robolectric Room outbox test) | **pass**, 23 tests, 0 failures (after one fix, s6) |
| Android lint (`lintDebug`, `abortOnError`) | **pass** |
| R8 release build of the SR app (unsigned) | **pass**, 2,355,288 bytes (budget 30 MB) |
| `tools/android-sdk.sh` (idempotent rerun) | **pass**, 4 s |
| `actionlint` 1.7.12 on `.github/workflows/ci.yml` | **pass**, no findings |
| Gradle wrapper jar checksum vs services.gradle.org | **match** |
| Secret scan of every changed and new file | **clean** |
| The CI workflow running on GitHub | **not verified** (s8) |
| Final combined run after the last edits (JVM build and tests, three debug APKs, Android unit tests, lint, one Gradle invocation) | **pass**: `BUILD SUCCESSFUL in 1m 25s`, 1,355 tasks, 40 JVM and 23 Android tests, 0 failures |

## 3. Contract

```text
$ npx --yes @redocly/cli@2 lint contract/openapi.yaml --config contract/redocly.yaml
validating contract/openapi.yaml...
contract/openapi.yaml: validated in 938ms
Woohoo! Your API description is valid. 🎉
$ npx --yes @redocly/cli@2 --version
2.57.0
```

Size: 115 paths, 140 operations, 380 schemas, 10 component examples (plus 2 inline). Example validation (Python `jsonschema`, draft 2020-12, every `components.examples` entry and every inline example against the schema it illustrates):

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

Contract fixes made today while writing docs/24 (the contract had been committed as `f4f0df3` before them): `location_mode` value `off` quoted (YAML 1.1 parsers such as PyYAML read a bare `off` as boolean false; YAML 1.2 tools did not complain); `number` added to `ConfigKey.value_type` (D24-62); `ERR_PUSH_DISABLED` stated as 409 on `POST /v1/admin/notifications`. Lint and example validation above were rerun after these edits.

## 4. Cross-check docs/24 ⇄ contract

The one-off script output is pasted in docs/24 Appendix B (`RESULT: PASS (no drift)`): 140 of 140 operations in Appendix A, 43 `/v1` paths named in the prose all exist, 18 enums agree in both directions, 172 config keys in the s9.5 registry cover every key named in docs/24 (172) and in the contract (29), 13 headers and 35 schema names exist, 32 of 32 record types have a payload mapping.

The permanent gate is in the build (`shared/contract` jvmTest): `ContractDriftTest` (17 tests: 15 enums of the Kotlin mirror equal the YAML, scope precedence equals the `ConfigScopeType` description, every path starts with `/v1/`) and `SpecCrossCheckTest` (8 tests). Mutation check: deleting the `cfg.auth.refresh_grace_s` registry row and changing `ERR_BUNDLE_CURSOR_EXPIRED` from 410 to 409 in docs/24 made the build fail, and restoring the file made it pass:

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
BUILD SUCCESSFUL in 22s
61 actionable tasks: 61 executed
```

(`aron_test` is a throwaway local role on the build container, not a credential of any environment.)

| Module | Tests | Failed |
|---|---|---|
| shared/contract (ContractDriftTest 17, SpecCrossCheckTest 8) | 25 | 0 |
| shared/rules (MoneyTest: 116,416 → 116,420 mtk; 20 × 7,935 = 158,700; half away from zero) | 3 | 0 |
| db (MigrationNamingTest) | 2 | 0 |
| backend/platform (DatabaseSmokeTest on PostgreSQL 16: Hikari + Flyway + JDBI, `2026-10-04 18:30Z` → business date `2026-10-05` in Asia/Dhaka; PlatformModuleTest) | 2 | 0 |
| backend/app (HealthTest: `GET /v1/health` 200 with `X-Aron-Api: 1`) | 1 | 0 |
| backend/auth, sync, masterdata, config, analytics, notify, media (placeholders) | 7 | 0 |
| **Total** | **40** | **0** |

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
