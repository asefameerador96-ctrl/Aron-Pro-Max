# 24 — Build spec (binding for every lane)

> **Environment rule (binding, 2026-10-06):** all Azure and Google settings in this spec describe the FINAL profile unless a value is marked otherwise. The temporary TEST account runs the pilot profile of `docs/28-environment-profiles.md` (5 to 10 users, cheapest tiers, single zone, no Front Door, no quota requests). Never create fleet-sized resources in the test account.


**Status: v1, 2026-10-05. Owner: the architect (contract and shared lane).** This is the build-level contract for the seven-day plan of `docs/23`. Every lane builds against it; a reviewer rejects a change that contradicts it. Its machine-readable half is `contract/openapi.yaml` (OpenAPI 3.1, lint clean); where the two disagree on a field name, type or enum value, **the YAML wins** and this page is corrected in the same PR.

**Precedence.** `docs/23` > sponsor statements in `docs/ui-reference/*` > this page, `docs/14`–`22` and `DECISIONS.md` > `docs/01`–`13`. Inside the third tier this page wins on build mechanics (stack, layout, wire format, numbers in s3 to s11), because it is the implementation of `docs/23` (native Kotlin, Ktor, device owner, no Apsis import in this build). `docs/23` overrides the Flutter, Node, "no MDM on shared phones" and migration assumptions of `docs/01`–`13` and `CLAUDE.md`.

**Words.** MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119. A number in this page is a default that lives in a config key when the key is named (s9); a number without a key is a hard rule that changes only by a PR to this page. Decisions I took myself carry an id `D24-nn` and are listed with their reason in s14.

**Sections.** 1 repo layout and ownership · 2 Gradle · 3 API conventions · 4 sync protocol · 5 Android architecture · 6 backend and web architecture · 7 money, units, numbering · 8 auth and devices · 9 config · 10 device policy · 11 geo integrity · 12 data model · 13 quality, CI, delivery · 14 decisions I took · Appendix A endpoint index · Appendix B cross-check against the contract.

---

## 1. Repository layout and folder ownership

### 1.1 Top level

| Path | Owner lane (`docs/25` lane id) | What lives there |
|---|---|---|
| `contract/` | shared | `openapi.yaml` (the API contract), `redocly.yaml` (lint rules) |
| `shared/contract/` | shared | Kotlin Multiplatform (`jvm()` target) mirror of wire enums and constants, drift tests against `openapi.yaml` |
| `shared/rules/` | shared | Kotlin Multiplatform business rules used by phone and server: money and rounding, memo totals, discount lines, geofence maths, business date, memo number |
| `db/` | db | Gradle module `:db`: `migrations/V####__name.sql` (Flyway, forward-only), `seed/` (seed data and loaders), migration tests |
| `backend/` | backend | Ktor service, one Gradle module per bounded context (s2.1) |
| `android/` | android-core, android-geo, android-dpc, android-print, android-sr, android-amo, android-tso (s1.2) | Three native apps and their libraries |
| `web/` | web-dashboard, web-admin (s1.3) | One Next.js + TypeScript + Tailwind app: dashboards, reports, admin portal, maps |
| `infra/` | infra | Azure as code (Bicep), `bootstrap-azure.ps1`, container image files, observability as code |
| `.github/` | infra | GitHub Actions workflows (`ci.yml` and later `deploy-*.yml`) |
| `qa/` | qa | Black-box suites: contract tests against a running API, sync property and fuzz harness, load and chaos scripts (Azure Load Testing), Android UI flows, security checks |
| `tools/` | infra | Developer-box scripts (`android-sdk.sh`, `setup-laptop.ps1`) |
| root build files: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/`, `gradlew`, `gradlew.bat`, `.gitignore` | infra | The single Gradle build (s2) |
| `docs/24-*` | shared (architect) | This spec and its verification log |
| `docs/25-*` | planning | Build backlog; lanes only tick their own rows' status |
| `docs/00`–`23`, `docs/evidence/`, `docs/ui-reference/`, `docs/setup/` | sponsor and planning | Read-only for build lanes |
| `DECISIONS.md` | everyone, append-only | One line per new assumption: date, lane, decision, reason |

### 1.2 Android ownership

| Module | Owner lane | Content |
|---|---|---|
| `android/core-common` | android-core | Result types, dispatchers, trusted clock (s3.8), business date (calls `shared:rules`), ids (UUID v4), logging facade, localisation helpers |
| `android/core-database` | android-core | Room database per user (s5.3), entities, DAOs, outbox, migrations, schema JSON in `schemas/` |
| `android/core-network` | android-core | OkHttp client, gzip, auth interceptor, `X-Device-Proof` signer hook, problem parsing, `X-Aron-Api` check (s3.1) |
| `android/core-session` | android-core | Login, token store, offline unlock (Argon2id), device binding, session switching on shared phones |
| `android/core-sync` | android-core | Sync engine: triggers, batching, acks, bundle and delta, config delta, media upload, WorkManager workers, FCM receiver |
| `android/core-ui` | android-core | Compose theme, Bangla and English typography (bundled Bengali font), common components, string resources for shared UI |
| `android/core-geo` | android-geo | On-demand fixes, mock detection, GNSS summary, geofence verdict, Play Integrity and key attestation, risk evidence |
| `android/dpc` | android-dpc | Device-owner receiver, policy applier, app blocking schedule, status reporter, QR provisioning extras |
| `android/core-printing` | android-print | Bluetooth SPP ESC/POS printing for the MP-58N class 58 mm printer: templates, Bangla bitmap rendering, reprint, status |
| `android/feature-auth`, `feature-home`, `feature-attendance`, `feature-stock`, `feature-sale`, `feature-memo`, `feature-dayclose`, `feature-outlet`, `feature-tasks` | android-sr | SR screens and view models; reused by the AMO app and (auth, home, tasks) by the TSO app |
| `android/feature-amo`, `android/app-amo` | android-amo | AMO-only screens (control and joint calls, verification, team, zone-wide lists, Update Base) and the AMO application module |
| `android/feature-tso`, `android/app-tso` | android-tso | TSO-only screens (dashboard, Final Submit, visit plans, call assessment, leave, feedback, team map) and the TSO application module |
| `android/app-sr` | android-sr | SR application module |

A lane that needs a change in a feature module owned by another android lane opens an issue to that lane; it never edits the module. The AMO and TSO lanes add role behaviour in their own modules through the extension points the SR lane exposes (role-aware navigation graph contributions, `@IntoSet` Hilt bindings).

### 1.3 Web ownership

| Path | Owner lane |
|---|---|
| `web/package.json`, `web/next.config.*`, `web/tailwind.config.*`, `web/tsconfig.json`, `web/src/lib/**` (API client, BFF auth, formatting, i18n), `web/src/app/(dashboards)/**`, `web/src/app/reports/**`, `web/src/components/**` except `admin/` | web-dashboard |
| `web/src/app/admin/**`, `web/src/components/admin/**` | web-admin |
| `web/src/contract/openapi.d.ts` | generated from `contract/openapi.yaml` by `npm run gen:contract` (`openapi-typescript`); never edited by hand; regenerated in the same PR that changes the contract |

### 1.4 Ownership rules

1. **A lane edits only files under folders it owns.** CODEOWNERS (written by infra on Day 1) enforces it: a PR touching another lane's folder needs that lane's approval.
2. **Append-only exceptions**, each reviewed by the owner: any lane MAY append a `[libraries]` entry to `gradle/libs.versions.toml` that uses an existing version or adds a new version line (only infra bumps a version); any lane MAY append an `include(...)` line to `settings.gradle.kts` for a new module inside its own folder; any lane MAY append to `DECISIONS.md`.
3. **Contract changes go first.** A lane that needs a new field or endpoint opens a PR that changes `contract/openapi.yaml` and `shared/contract` only (owner: shared lane). It merges before the dependent PR. Inside `/v1` only additive changes are allowed (s3.7).
4. **Generated code** (Room schemas, `openapi.d.ts`, Hilt and KSP output) is never edited by hand. Room schema JSON is committed; everything else is a build output.
5. **No secret, key, token, keystore or `google-services.json` is ever committed** (s13.6). `.gitignore` already excludes them.

---

## 2. Gradle structure

### 2.1 One root build

There is exactly **one Gradle build** at the repository root (Gradle wrapper 9.8.0, checksum pinned in `gradle/wrapper/gradle-wrapper.properties`). No `buildSrc`, no included builds, no convention-plugin project in Phase 1: shared configuration lives in the root `build.gradle.kts` (`subprojects { plugins.withId(...) }`) so the 35 modules (2 shared, 1 db, 9 backend, 23 android) configure in one place. Configuration cache is on and MUST stay green (`org.gradle.configuration-cache.problems=fail`). Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); Google Maven is filtered to `com.android.*`, `com.google.*` and `androidx.*`.

Base package `com.aktcl.aron`. Group `com.aktcl.aron`. Application ids: **`com.aktcl.aron.sr`**, **`com.aktcl.aron.amo`**, **`com.aktcl.aron.tso`** (sponsor decision, `docs/23` s8). Dev builds use the same ids (no `.debug` suffix) because the provisioning QR names the package (s10.4).

| Module | Plugin | Package / namespace | Depends on (in-repo) |
|---|---|---|---|
| `:shared:contract` | Kotlin Multiplatform, `jvm()` only, serialization | `com.aktcl.aron.contract` | nothing |
| `:shared:rules` | Kotlin Multiplatform, `jvm()` only | `com.aktcl.aron.rules` | nothing (kotlinx-datetime) |
| `:db` | Kotlin JVM | `com.aktcl.aron.db` | nothing; ships `db/migrations/*.sql` as classpath `db/migration` |
| `:backend:platform` | Kotlin JVM, serialization | `com.aktcl.aron.backend.platform` | `:shared:contract`, `:shared:rules`; tests use `:db` |
| `:backend:auth`, `:backend:sync`, `:backend:masterdata`, `:backend:config`, `:backend:analytics`, `:backend:notify`, `:backend:media` | Kotlin JVM, serialization | `com.aktcl.aron.backend.<name>` | `:backend:platform` only (never each other; cross-context calls go through interfaces declared in `:backend:platform`) |
| `:backend:app` | Kotlin JVM, `application` | `com.aktcl.aron.backend.app` (main `MainKt`) | every `:backend:*`; `runtimeOnly(:db)` |
| `:android:core-common` | Android library | `com.aktcl.aron.core.common` | `:shared:rules` (api) |
| `:android:core-database` | Android library, KSP, Room | `com.aktcl.aron.core.database` | `:android:core-common` |
| `:android:core-network` | Android library | `com.aktcl.aron.core.network` | `:android:core-common`, `:shared:contract` (api) |
| `:android:core-session` | Android library | `com.aktcl.aron.core.session` | `:android:core-common`, `:android:core-network` |
| `:android:core-sync` | Android library | `com.aktcl.aron.core.sync` | `core-common`, `core-database`, `core-network`, `core-session` |
| `:android:core-geo` | Android library | `com.aktcl.aron.core.geo` | `:android:core-common` |
| `:android:core-printing` | Android library | `com.aktcl.aron.core.printing` | `:android:core-common` |
| `:android:core-ui` | Android library, Compose | `com.aktcl.aron.core.ui` | `:android:core-common` |
| `:android:dpc` | Android library | `com.aktcl.aron.dpc` | `:android:core-common` |
| `:android:feature-*` (auth, home, attendance, stock, sale, memo, dayclose, outlet, tasks, amo, tso) | Android library, Compose | `com.aktcl.aron.feature.<name>` | any `core-*` and `:android:dpc`; never another feature, never an app |
| `:android:app-sr`, `:android:app-amo`, `:android:app-tso` | Android application, Compose, KSP, Hilt | `com.aktcl.aron.sr` / `.amo` / `.tso` | every core module, `:android:dpc`, the features of the role (SR: auth, home, attendance, stock, sale, memo, dayclose, outlet, tasks; AMO: the SR set plus amo; TSO: auth, home, tasks, tso) |

### 2.2 Dependency direction

```
shared:contract   shared:rules                (pure Kotlin, no Android, no Ktor, no JDBC)
      ▲   ▲            ▲   ▲
      │   └────────────┼───┼──────────── backend:platform ◄── backend:{auth,sync,masterdata,config,analytics,notify,media} ◄── backend:app ──runtimeOnly──► db
      │                │   │
android:core-network   android:core-common ◄── core-{database,session,sync,geo,printing,ui}, dpc ◄── feature-* ◄── app-{sr,amo,tso}
```

Arrows point from user to dependency. Rules: `shared:*` MUST NOT depend on anything in the repo; a backend context module MUST NOT depend on another context module; a feature module MUST NOT depend on another feature module; only `app-*` modules apply Hilt's Gradle plugin and `@HiltAndroidApp` (libraries use `hilt-android` and `@Module`/`@InstallIn` through KSP if they need it, and add the KSP plugin themselves).

### 2.3 Toolchain rules

- JDK 21 runs Gradle and the server; **bytecode target 17** for every module (`jvmTarget = 17`; JVM and KMP modules also compile with `-Xjdk-release=17` and `options.release = 17`; Android modules use `compileOptions` 17 because AGP refuses `--release`).
- AGP 9 built-in Kotlin: Android modules do **not** apply `org.jetbrains.kotlin.android`. Compose modules apply `org.jetbrains.kotlin.plugin.compose`.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26` (Android 8.0, `docs/23` Q4). D24-08.
- Tests: JUnit Platform (Jupiter 6) for `shared`, `db`, `backend`; JUnit 4 + Robolectric 4.17 for Android local tests; MockK is the only mocking library (D24-07).
- No dynamic versions anywhere. No `mavenLocal()`.

### 2.4 Pinned versions (checked 2026-10-05, newest stable on that day)

| Area | Library | Version |
|---|---|---|
| Build | Gradle wrapper | 9.8.0 |
| Build | Kotlin (stdlib, compiler plugins) | 2.4.20 |
| Build | Android Gradle plugin | 9.4.1 |
| Build | KSP2 | 2.3.12 |
| Kotlin | kotlinx-coroutines | 1.11.0 |
| Kotlin | kotlinx-serialization | 1.11.0 |
| Kotlin | kotlinx-datetime | 0.8.0 |
| Backend | Ktor (server; BOM) | 3.6.0 |
| Backend | Flyway (`flyway-core`, `flyway-database-postgresql`) | 13.9.0 |
| Backend | PostgreSQL JDBC | 42.7.13 |
| Backend | HikariCP | 7.1.0 |
| Backend | JDBI 3 (`jdbi3-core`, `jdbi3-kotlin`) | 3.55.0 |
| Backend | Logback | 1.6.5 |
| Backend | Nimbus JOSE + JWT | 10.10 |
| Backend | argon2-jvm | 2.12 |
| Backend | Azure SDK BOM (`azure-storage-blob`, `azure-identity`) | 1.3.8 |
| Backend | Firebase Admin (FCM HTTP v1) | 9.11.0 |
| Backend | FastExcel (xlsx export) | 0.20.2 |
| Backend | Application Insights Java agent | 3.7.10 |
| Android | Compose BOM | 2026.09.00 |
| Android | activity-compose | 1.13.0 |
| Android | lifecycle | 2.11.0 |
| Android | navigation-compose | 2.10.2 |
| Android | core-ktx | 1.19.1 |
| Android | Room | 2.8.5 |
| Android | Hilt (Dagger) | 2.60.1 |
| Android | androidx.hilt | 1.4.0 |
| Android | WorkManager | 2.12.0 |
| Android | DataStore | 1.2.1 |
| Android | Play Integrity | 1.6.0 |
| Android | play-services-location | 21.4.0 |
| Android | Maps Compose (AMO, TSO only) | 9.0.0 |
| Android | Firebase BoM (messaging) | 34.19.0 |
| Android | google-services Gradle plugin | 4.5.0 |
| Android | OkHttp (BOM) | 5.5.0 |
| Android | argon2kt | 1.6.0 |
| Android | SQLCipher for Android | 4.19.1 |
| Test | JUnit 4 / JUnit BOM (Jupiter) | 4.13.2 / 6.1.3 |
| Test | MockK | 1.14.11 |
| Test | Robolectric | 4.17 |
| Test | androidx.test core / ext-junit | 1.7.0 / 1.3.0 |
| Test | Testcontainers (`testcontainers-postgresql`) | 2.0.5 |
| Test | snakeyaml-engine | 3.2 |
| Lint | Redocly CLI | 2.x (`@redocly/cli@2`, 2.57.0 verified) |

The catalogue is `gradle/libs.versions.toml`; the excerpt every lane uses:

```toml
[versions]
kotlin = "2.4.20"
agp = "9.4.1"
ksp = "2.3.12"
android-compileSdk = "37"
android-targetSdk = "36"
android-minSdk = "26"
jvm-target = "17"
coroutines = "1.11.0"
serialization = "1.11.0"
datetime = "0.8.0"
ktor = "3.6.0"
flyway = "13.9.0"
postgresql = "42.7.13"
hikari = "7.1.0"
jdbi = "3.55.0"
compose-bom = "2026.09.00"
room = "2.8.5"
hilt = "2.60.1"
work = "2.12.0"
play-integrity = "1.6.0"
play-services-location = "21.4.0"
maps-compose = "9.0.0"
firebase-bom = "34.19.0"
okhttp = "5.5.0"
mockk = "1.14.11"
robolectric = "4.17"
testcontainers = "2.0.5"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
room = { id = "androidx.room", version.ref = "room" }
google-services = { id = "com.google.gms.google-services", version.ref = "google-services" }
```

### 2.5 Two build decisions

**Database access: JDBI 3 with hand-written SQL, not jOOQ** (D24-04). jOOQ's type safety needs code generation from a live schema, which couples `:db` to every backend compile, needs a database (or Testcontainers) inside the build, and slows the parallel lanes on Day 1. The hot paths (ingest upsert `INSERT ... ON CONFLICT`, aggregate upserts, `COPY`) are hand-tuned SQL anyway. JDBI gives named parameters, row mappers to Kotlin data classes, transactions and batch statements with no codegen. Every SQL statement lives in the repository class of its context, has a test against real PostgreSQL 16, and uses bind parameters only (string concatenation of values is a review reject).

**Test database: a real PostgreSQL 16, never an embedded or in-memory substitute** (D24-05). Tests read `ARON_TEST_PG_URL` (a JDBC URL); CI sets it to a `postgres:16` service container; a developer box sets it to a local PostgreSQL 16 (the build container has PostgreSQL 16 and no Docker). When the variable is absent the tests start `postgres:16-alpine` with Testcontainers 2.0.5 (needs Docker). Each test class migrates a fresh schema with Flyway from `classpath:db/migration` and drops it at the end. H2 and embedded-postgres binaries are not used: the ingest path depends on PostgreSQL-only features (`ON CONFLICT`, partial unique indexes, exclusion constraints, `timestamptz` and `AT TIME ZONE 'Asia/Dhaka'`).

### 2.6 Commands every lane uses

| Purpose | Command |
|---|---|
| JVM build and tests (shared, db, backend) | `./gradlew :shared:contract:build :shared:rules:build :db:build :backend:app:build` (or `./gradlew build -x lint` once the Android SDK is installed) |
| Three debug APKs | `./gradlew :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug` |
| Android local tests | `./gradlew testDebugUnitTest` |
| Contract lint | `npx --yes @redocly/cli@2 lint contract/openapi.yaml --config contract/redocly.yaml` |
| Android SDK on a fresh Linux box | `tools/android-sdk.sh` (cmdline-tools 23.0, `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools`) |
| Run the API locally | `ARON_ROLE=api ./gradlew :backend:app:run` (health at `GET /v1/health`) |

---

## 3. API conventions

### 3.1 Transport and format

1. **HTTPS only** (TLS 1.2 or later, terminated at Azure Front Door). One host per environment; every path starts with **`/v1`**. Dev host: the Container Apps FQDN behind Front Door in `rg-aron-dev`; the phone reads the **origin** (scheme and host, no path, for example `https://api.aron-dev.invalid`) from `BuildConfig.API_BASE_URL` (Gradle property `aron.apiBaseUrl`) or, on an enrolled phone, from the provisioning extra `aron.api_base_url` (s10.4), which wins; the contract paths already start with `/v1`.
2. **JSON** (`application/json; charset=utf-8`), member names `snake_case`. Requests are **strict**: an unknown member, a wrong type or a value outside the schema is `400 ERR_VALIDATION` with one `errors[]` entry per problem (`pointer` is a JSON Pointer). Responses are read **leniently**: phones and web ignore unknown response members (`Json { ignoreUnknownKeys = true }` for responses only), so adding a response member is never breaking.
3. Optional request members MAY be omitted or `null`; the server treats both the same. Every `required` response member is always present (possibly `null` when its schema allows).
4. **Compression.** Clients send `Accept-Encoding: gzip`; the server compresses responses larger than 1 KiB. `POST /v1/sync/batch` MUST be sent with `Content-Encoding: gzip` (otherwise `415 ERR_UNSUPPORTED_MEDIA_TYPE`). Other request bodies larger than 4 KiB SHOULD be gzip. Limits: batch body 1 MiB compressed (`cfg.api.max_batch_body_kb` = 1024) and 8 MiB decompressed (`cfg.api.max_batch_decompressed_mb` = 8, a 20:1 ratio guard, `413 ERR_SYNC_DECOMPRESSION_LIMIT`); any other JSON body 256 KiB (`413 ERR_PAYLOAD_TOO_LARGE`).
5. **Types on the wire.** Server ids are JSON integers (int64, `Id`, at most 2^53 − 1 in practice). Device-originated ids are lower-case UUID v4 strings. Money is integer milli-taka (`*_mtk`, s7). Quantities are integers in the SKU base unit (`qty_base`). Timestamps are RFC 3339 UTC with milliseconds and `Z` (`2026-10-05T04:31:07.120Z`). Business dates are `YYYY-MM-DD` in Asia/Dhaka. Coordinates are WGS84 decimal degrees, at most 7 decimals. Durations carry their unit in the name (`_s`, `_ms`, `_min`, `_h`, `_days`).
6. **Marker headers on every API-originated response:** `X-Aron-Api: 1`, `X-Request-Id`, `X-Server-Time`, `X-Config-Version`, `X-Server-Generation`; and on authenticated phone calls `X-Bundle-Version-Current`. A response **without `X-Aron-Api`** came from the edge (WAF block page, gateway timeout, captive portal) and the phone treats it as a transport failure, never as a business answer (replaces the old `success` member check, D24-10). There is no success envelope: a 2xx body is the resource itself.
7. Clients send `X-Request-Id` (UUID v4, new per HTTP attempt); the server echoes it or mints one, logs it and returns it as `request_id` in problems.

### 3.2 Authentication headers

| Header | Who sends it | Rule |
|---|---|---|
| `Authorization: Bearer <JWT>` | every authenticated call | ES256 access token (s8.2). `aud` `aron-api` (full grant) or `aron-upload` (upload grant: only `POST /v1/sync/batch`, `POST /v1/media/sas`, `POST /v1/auth/refresh` with `grant=upload`, `POST /v1/auth/logout`). `bind_token` (`aron-bind`) and `mfa_token` (`aron-mfa`) are Bearer tokens accepted only by bind-device and mfa/verify. |
| `X-Device-Id` | phones, every authenticated call | The device's `device_uuid` (UUID v4 minted at enrolment, s10.4). MUST equal the token's `dvu` claim, else `401 ERR_DEVICE_PROOF_INVALID`. |
| `X-Device-Proof` | phones on `auth/refresh`, `auth/bind-device`, `sync/batch`, `devices/me/*` | ES256 signature (86-char base64url, raw r‖s) by the device's Keystore key over the proof string of s8.3. |
| `X-App-Version` | phones, every call | `<versionName>+<versionCode>`, for example `1.0.3+10003`. Drives the version gate (s3.7). |
| `X-Config-Version` | phones on sync and config calls | The config version the phone holds. |
| `X-Batch-Attempt`, `X-Pending-Rows`, `X-Last-Sync-Error`, `X-Device-Time` | phones on `sync/batch` | Attempt number (1 for the first send of a `batch_uuid`), rows waiting in the outbox, the last transport or problem code, device wall clock. Telemetry only; they never change the outcome. |
| Cookie `aron_rt` | web | HttpOnly, Secure, SameSite=Strict refresh cookie on the web BFF (s6.5). The browser never sees an access token. |

### 3.3 Idempotency

1. **Records** (sync): every record carries `client_uuid` (UUID v4 generated on the phone when the row is committed). The server keeps `ingest_registry (client_uuid PK, type, payload_sha256, device_id, user_id, status, server_id, first_batch_uuid, received_at)`. `payload_sha256` is the SHA-256 of the RFC 8785 (JCS) canonical form of the whole record object as received, computed by the server with the RFC 8785 canonicaliser that `shared:contract` provides (shared lane, Day 1). Same `client_uuid` and same hash: ack `duplicate` with the stored outcome and `server_id`, nothing written. Same `client_uuid`, different hash: ack `quarantined`, code `payload_conflict`; the first row stays and the second copy is kept in quarantine for review. A record is never applied twice and never overwritten by a resend.
2. **Batches**: `(device_id, batch_uuid)` is stored with a fingerprint (SHA-256 over the sorted `client_uuid:payload_sha256` list) and the full response for `cfg.retention.sync_batch_response_h` (48 h). A repeat with the same fingerprint returns the stored response with `replayed: true`; a different record set under the same `batch_uuid` is `409 ERR_SYNC_BATCH_UUID_REUSED`. The phone mints `batch_uuid` when it assembles a batch and persists it with the member rows; a retry resends the identical batch with the same `batch_uuid` and `X-Batch-Attempt` + 1; any change of membership (bisect, s4.7) mints a new `batch_uuid`.
3. **Online commands**: every create-or-act POST that a phone or browser may retry carries a client UUID in the body (`client_uuid` on sales-submit, final-submit, cover and data-void; `task_uuid` on task create; `review_uuid` on risk review; `batch_uuid` on price publish and target upsert). A replay returns the first result with the same status code. Decisions (approve, reject, verify, resolve, cancel) are state transitions: repeating the same decision by the same actor returns `200` with the stored outcome; a different transition from a closed state is `409 ERR_REQUEST_STATE`.
4. **Master-data creates** are protected by natural unique codes (`409 ERR_MASTER_DUPLICATE_CODE`); updates use `PATCH` with `If-Match: "<version>"` (optimistic concurrency, `412 ERR_PRECONDITION_FAILED` on a stale version, D24-44). Master data is never hard-deleted: status `inactive`/`closed` or `valid_to`.

### 3.4 Errors: RFC 9457 problem details

Every non-2xx API response is `application/problem+json` with members `type` (`urn:aron:problem:<code in lower case>`), `title`, `status`, `detail`, `instance`, **`code`** (stable, from `ProblemCode`), `request_id`, `retryable`, `retry_after_s`, `server_time`, `message_key` (localisation key; bn and en texts live in the app and web catalogues), `errors[]`, `context` (code-specific scalars). Clients branch on `code` only, never on `title` or `detail`. Any operation MAY also return `500 ERR_INTERNAL` and `503 ERR_SERVICE_UNAVAILABLE` / `ERR_READ_ONLY_MODE` from the platform even where the contract lists only the operation-specific statuses.

| HTTP | Codes | Retryable | Client action |
|---|---|---|---|
| 400 | `ERR_VALIDATION`, `ERR_MALFORMED_JSON`, `ERR_UNSUPPORTED_SCHEMA_VERSION`, `ERR_AUTH_PASSWORD_POLICY`, `ERR_NONCE_INVALID`, `ERR_MASTER_EFFECTIVE_DATE_PAST`, `ERR_CFG_UNKNOWN_KEY`, `ERR_CFG_OUT_OF_BOUNDS`, `ERR_CFG_SCOPE_NOT_ALLOWED`, `ERR_CFG_DEPENDENCY`, `ERR_CFG_REASON_REQUIRED`, `ERR_REPORT_INVALID_QUERY` | no | fix the request; phones log and park the operation (a batch never gets 400 for one bad record: bad records are rejected per record, s4.5) |
| 401 | `ERR_UNAUTHENTICATED`, `ERR_TOKEN_EXPIRED`, `ERR_SCOPE_CHANGED`, `ERR_PASSWORD_CHANGED`, `ERR_AUTH_INVALID_CREDENTIALS`, `ERR_AUTH_REFRESH_INVALID`, `ERR_AUTH_REFRESH_REUSED`, `ERR_AUTH_MFA_INVALID`, `ERR_AUTH_OTP_INVALID`, `ERR_AUTH_OTP_EXPIRED`, `ERR_DEVICE_PROOF_INVALID` | `ERR_TOKEN_EXPIRED` and `ERR_SCOPE_CHANGED`: yes after one refresh | `ERR_TOKEN_EXPIRED`/`ERR_SCOPE_CHANGED`: refresh once and repeat; `ERR_SCOPE_CHANGED` also schedules a full bundle. Refresh failures (`REFRESH_*`, `PASSWORD_CHANGED`): the phone keeps selling offline on the local session and asks for the password at the next online moment; the outbox keeps uploading on the upload grant |
| 403 | `ERR_FORBIDDEN`, `ERR_OUT_OF_SCOPE`, `ERR_AUTH_ACCOUNT_LOCKED` (with `retry_after_s`, D24-60), `ERR_AUTH_USER_DISABLED`, `ERR_AUTH_PASSWORD_CHANGE_REQUIRED`, `ERR_AUTH_BIND_LOCKED`, `ERR_AUTH_OTP_ATTEMPTS_EXCEEDED`, `ERR_DEVICE_NOT_ENROLLED`, `ERR_DEVICE_UNBOUND`, `ERR_DEVICE_SUSPENDED`, `ERR_DEVICE_REVOKED`, `ERR_DEVICE_INTEGRITY_FAILED`, `ERR_ENROLMENT_TOKEN_INVALID`, `ERR_ENROLMENT_TOKEN_EXPIRED`, `ERR_ENROLMENT_TOKEN_EXHAUSTED`, `ERR_ENROLMENT_ATTESTATION_FAILED` | no (`ACCOUNT_LOCKED` after `retry_after_s`) | show the localised message; never retry automatically |
| 404 | `ERR_NOT_FOUND` | no | also returned for an id outside the caller's reach on a single-resource GET (no existence leak) |
| 409 | `ERR_CONFLICT`, `ERR_REQUEST_STATE`, `ERR_SEPARATION_OF_DUTIES`, `ERR_CFG_SELF_APPROVAL`, `ERR_CFG_FREEZE_WINDOW`, `ERR_MASTER_DUPLICATE_CODE`, `ERR_MASTER_OVERLAP`, `ERR_MASTER_IN_USE`, `ERR_SYNC_BATCH_UUID_REUSED`, `ERR_BUNDLE_NEW_BUSINESS_DATE`, `ERR_DAY_ALREADY_FINAL_SUBMITTED`, `ERR_DAY_NOT_FINAL_SUBMITTED`, `ERR_DAY_SUBMIT_VOID_NOT_ALLOWED`, `ERR_DAY_DATA_VOID_NOT_ALLOWED`, `ERR_DEVICE_LIMIT_REACHED`, `ERR_PUSH_DISABLED`, `ERR_ENTRY_WINDOW_CLOSED`, `ERR_GIFT_CHOICE_LOCKED` | no | `ERR_SYNC_BATCH_UUID_REUSED`: the phone mints a new `batch_uuid` for the same rows (a phone bug if it happens); `ERR_BUNDLE_NEW_BUSINESS_DATE`: fetch the full bundle |
| 410 | `ERR_BUNDLE_CURSOR_EXPIRED` | no | fetch the full bundle (or restart paging) |
| 412 | `ERR_PRECONDITION_FAILED` | no | reload and re-apply the edit |
| 413 | `ERR_PAYLOAD_TOO_LARGE`, `ERR_SYNC_BATCH_TOO_LARGE`, `ERR_SYNC_DECOMPRESSION_LIMIT`, `ERR_REPORT_TOO_LARGE` | no | phones halve the batch (s4.7); reports switch to the export job |
| 415 | `ERR_UNSUPPORTED_MEDIA_TYPE` | no | client bug |
| 426 | `ERR_APP_VERSION_UNSUPPORTED` (`context.min_version_code`) | no | open the update screen (s3.7); an open offline day continues |
| 429 | `ERR_RATE_LIMITED`, `ERR_SYNC_HOLD` | yes, after `Retry-After` | wait `Retry-After` × U(0.8, 1.2), capped at 900 s |
| 500 | `ERR_INTERNAL` | yes | phones bisect a failing batch (s4.7) |
| 503 | `ERR_SERVICE_UNAVAILABLE`, `ERR_READ_ONLY_MODE`, `ERR_BUNDLE_NOT_READY` | yes, after `Retry-After` | as 429 |

### 3.5 Lists, pagination and incremental reads

- Every list operation takes `limit` (1 to 500, default 100) and an opaque `cursor`, and returns `items` and `next_cursor` (`null` on the last page). Cursors are keyset cursors (stable under inserts) and do not expire, except bundle page cursors, which die with their snapshot (`410 ERR_BUNDLE_CURSOR_EXPIRED`). Order is stated per operation (default: primary key ascending).
- Master-data lists also accept `updated_since` (timestamp): rows with `updated_at > updated_since` including those that became `inactive`, `closed` or ended, so a mirror can apply deletes as status changes. Paging inside an `updated_since` read is ordered by `(updated_at, id)`.
- Geo selectors (`level`, `node_id`, `zone_id`, `territory_id`) only **narrow** the caller's reach; omitted means the whole reach; a node outside the reach is `403 ERR_OUT_OF_SCOPE`. The client never sends a list of scope ids.
- Date ranges are inclusive business dates (`from`, `to`); at most 93 days for transaction lists and dashboards, 400 days for monthly target reads.

### 3.6 Rate limits and admission control

| Limit | Value (key) | Response |
|---|---|---|
| Phone requests per device | 120 per minute (`cfg.api.rl.device_per_min`) | 429 `ERR_RATE_LIMITED`, `Retry-After`, `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` |
| Web requests per user | 300 per minute (`cfg.api.rl.user_per_min`) | same |
| Login | 10 per 15 min per username and 30 per 15 min per device | 429; failures also count toward lockout (`cfg.auth.lockout_attempts` = 10 in 15 min) |
| OTP verify (bind) | 5 attempts per OTP (`cfg.auth.otp_max_attempts`) | 403 `ERR_AUTH_OTP_ATTEMPTS_EXCEEDED`, OTP expires |
| Device OTP issue (support) | 10 per user per hour | 429 |
| Batches in flight per API replica | 64 (`cfg.api.inflight_batches_per_replica`) | 503 `ERR_SERVICE_UNAVAILABLE`, `Retry-After` 5 to 60 s (random) |
| Ops hold | `cfg.ops.sync_hold_s` (0 = off) | 200 with `hold_s` in the batch response; `cfg.ops.sync_hold_by_version` gives 429 `ERR_SYNC_HOLD` to one build |
| Report run | 2 concurrent per user (`cfg.ops.report_concurrency_per_user`) | 429 |

Limits are counted per device and per user, **never per IP** (carrier NAT puts thousands of phones behind one address). Counters are in-memory per replica in Phase 1 (D24-58), so the effective limit is the value times the replica count. Load shedding order under pressure: dashboards and reports first (503 with `Retry-After` 30), then bundle (serve the previous snapshot plus delta), batch last.

### 3.7 Versioning and the app-version gate

- **API versioning.** `/v1` changes only additively: new operations, new optional request members, new response members, new enum values in requests the server accepts. Removing or renaming anything, making an optional request member required, or adding a value to a response enum that old phones cannot decode is breaking: it ships under `/v2`, served in parallel with `/v1` until `cfg.release.min_version_code` passes the last `/v1` build. Record payloads carry `schema_version` (1 in Phase 1); the server accepts every version from 1 to the current one; a batch whose top-level `schema_version` is above the server's is `400 ERR_UNSUPPORTED_SCHEMA_VERSION`.
- **Version gate.** Phones send `X-App-Version`. `cfg.release.min_version_code` is a JSON object per flavour (`{"sr": n, "amo": n, "tso": n}`, D24-22). Below the minimum: `426 ERR_APP_VERSION_UNSUPPORTED` on login and on a bundle request for a **new** business date; the batch upload, media and refresh of the upload grant are **never** gated, so an old phone can always empty its outbox. `cfg.release.blocked_version_codes` (`{"sr": [..], ...}`) blocks new captures on those builds (the phone shows the update screen) while uploads continue. `GET /v1/config/public` gives the minimums before login; `GET /v1/app/update-check` gives the latest release, prompt policy and Wi-Fi rule. An open offline day finishes before a forced update (`cfg.release.finish_offline_day_before_force` = true).

### 3.8 Time

1. The server stores **UTC `timestamptz`** for every instant and an explicit **`business_date`** (`date`, Asia/Dhaka, UTC+6, no DST) on every transaction. The business-date cutoff is 00:00 Dhaka (`cfg.day.business_date_cutoff_time`, D24-29). `shared:rules` owns the conversion (`BusinessDate.of(instant)`), used by phone and server.
2. **Trusted time on the phone.** Each API response carries `X-Server-Time`. The phone stores an anchor `(server_time, elapsedRealtime_ms, boot_count)` at every successful contact (at most 3 recent anchors travel in each batch as `time_anchors`). Trusted now = `anchor.server_time + (elapsedRealtime − anchor.elapsed_ms)` while `boot_count` is unchanged. After a reboot with no new anchor the phone uses the wall clock and marks records with `clock_offset_ms = null`. The business date of a capture is computed from trusted now, never from the raw wall clock when an anchor exists.
3. Every record carries `captured_at` (trusted time), `captured_elapsed_ms`, `boot_count`, `clock_offset_ms` and its `business_date`. The server re-derives the instant from the anchors of the batch; when it differs from `captured_at` by more than `cfg.sync.max_clock_skew_min` (10 min) it raises `CLOCK_SKEW` (s11.4) and keeps the phone's business date unless the date differs, in which case the server's date wins and the original is kept in `business_date_device`.
4. Rows whose business date is more than `cfg.sync.max_backdate_days` (7) before the server's today are **quarantined** (`business_date_out_of_window`), never silently dropped; rows dated in the future (more than 10 minutes ahead of server time) are quarantined the same way.
5. "Today's route" and every day-level rollup key off the business date. Dashboards default to today in Dhaka.

---

## 4. Sync protocol

### 4.1 Principles

1. **The network is never in the critical path of a sale.** Every capture is one local Room transaction that writes the domain rows and their outbox rows together. Upload happens later.
2. **One upload channel:** `POST /v1/sync/batch`. Online endpoints for the same facts (sales-submit, task create) exist for the web and for supervisors online, and are idempotent with the same `client_uuid` (a record sent both ways is applied once).
3. **The server never trusts identity from the body:** user, role, device and reach come from the token and the device record. Scope is re-checked per record on the server (s8.4).
4. **Nothing is dropped.** Every record ends in exactly one of: stored (`accepted`/`duplicate`), `rejected` with a stable code (kept in `sync_rejected`), or `quarantined` (kept in `sync_quarantine` for review). Device count equals server count, per type and date (s4.12).

### 4.2 Phase 1 record types, families and order

A **family** is a header record and its children, linked by `family_uuid` (the header's `client_uuid`). `rank` orders a family: lower ranks are processed first in a batch and are committed in the same phone transaction or before their children. A family travels in one batch when it fits; the phone holds a family's first rows for at most `cfg.sync.family_hold_max_s` (180 s) to let it complete.

| `type` | Family header (rank 0) | Rank | Parent reference in payload | Writers | Server table (s12) |
|---|---|---|---|---|---|
| `day_open` | self | 0 | — | SR, AMO, TSO | `route_day_event` |
| `attendance_event` (check_in / check_out) | self | 0 | — | SR, AMO, TSO | `attendance_event` |
| `stock_movement` (issue, return, adjustment, damaged, short) | self | 0 | — | SR, AMO | `stock_movement` (append-only ledger) |
| `visit` | self | 0 | — | SR, AMO (control, joint), TSO | `visit` |
| `visit_close` | `visit` | 1 | `visit_client_uuid` | SR, AMO, TSO | `visit` (close columns) |
| `visit_skip` | self | 0 | — | SR, AMO | `visit_skip` |
| `memo` | `visit` | 1 | `visit_client_uuid` (and `supersedes_client_uuid` on an edit) | SR, AMO | `memo` |
| `memo_line` | `visit` | 2 | `memo_client_uuid` | SR, AMO | `memo_line` |
| `memo_discount` | `visit` | 2 | `memo_client_uuid` | SR, AMO | `memo_discount` |
| `qc_line` | `visit` | 2 | `visit_client_uuid`, optional `memo_client_uuid` | SR, AMO | `qc_line` |
| `print_event` | `visit` (memo prints) or self (stock slip, day summary) | 3 or 0 | `memo_client_uuid` / `ref_client_uuid` | SR, AMO | `print_event` |
| `memo_void` | self | 0 | `memo_client_uuid` (must already exist on the server or in the same batch) | SR, AMO | `memo_void` |
| `due_collection` | self | 0 | `against_memo_client_uuid`, optional `visit_client_uuid` | SR, AMO | `due_collection` |
| `survey_response` | `visit` | 2 | `visit_client_uuid` | SR, AMO | `survey_response` |
| `distribution_check` | `visit` | 1 | `visit_client_uuid` | AMO, TSO | `distribution_check` |
| `distribution_check_line` | `visit` | 2 | `check_client_uuid` | AMO, TSO | `distribution_check_line` |
| `call_assessment` | `visit` or self | 1 or 0 | optional `visit_client_uuid` / `visit_plan_outlet_client_uuid` | AMO, TSO | `call_assessment` |
| `call_assessment_answer` | same family as its assessment | 2 or 1 | `assessment_client_uuid` | AMO, TSO | `call_assessment_answer` |
| `outlet_change_request` | self | 0 | optional `origin_visit_client_uuid` | SR, AMO | `outlet_change_request` |
| `outlet_request_verification` | self | 0 | `request_uuid` | AMO | `outlet_request_event` |
| `task` | self | 0 | optional `source_visit_client_uuid` | AMO, TSO | `task` |
| `task_event` | self | 0 | `task_uuid` | SR, AMO, TSO | `task_event` |
| `visit_plan` | self | 0 | — | TSO | `visit_plan` |
| `visit_plan_outlet` | `visit_plan` | 1 | `plan_client_uuid` | TSO | `visit_plan_outlet` |
| `leave_application` | self | 0 | — | TSO | `leave_application` |
| `feedback` | self | 0 | optional `photo_uuid` | TSO | `feedback` |
| `day_exception` | self | 0 | — | SR, AMO | `day_exception` |
| `media_meta` | self | 0 | `ref_client_uuid` (the record the photo belongs to) | all | `media` |
| `geo_breadcrumb` | self | 0 | — | SR, AMO (only when `cfg.geo.breadcrumbs_enabled`) | `geo_breadcrumb` |
| `device_status` | self | 0 | — | all | `device_status_report` |
| `config_ack` | self | 0 | — | all | `config_ack` |
| `day_submit` (scope `route_day` or `supervisor_day`) | self | 0 | — | SR (route-day), AMO (supervisor-day, D24-54) | `route_day_event` |
| `content_view` (AV or KV shown or skipped) | `visit` | 2 | `visit_client_uuid` | SR, AMO | `content_view` |
| `redemption` (loyalty basket) | self | 0 | optional `visit_client_uuid` | SR, AMO (`cfg.loyalty.redemption_roles`) | `redemption` (+ `loyalty_ledger` debit) |
| `redemption_line` | `redemption` | 1 | `redemption_client_uuid` | SR, AMO | `redemption_line` |
| `gift_photo` (Astha hand-over or campaign Gift Verify) | self | 0 | `gift_assignment_id` or `redemption_client_uuid` | SR, AMO | `gift_photo` |
| `price_compliance_check` | `visit` | 2 | `visit_client_uuid` | AMO | `price_compliance_check` |
| `risk_review` (Exceptions screen, offline) | self | 0 | `signal_id` | AMO | `risk_signal_review` |
| `activity_log` | self | 0 | — | all | `activity_log` |
| `app_error` | self | 0 | — | all | `app_error` |
| `sale_abort` (memo number burned without a memo) | self | 0 | optional `visit_client_uuid` | SR, AMO | `sale_abort` |
| `consent_accept` (location notice) | self | 0 | — | all | `user_consent` |

Ordering rules (the phone MUST follow them; the server tolerates violations by parking):
1. Outbox order is commit order (`seq`). A batch is the next rows in `seq` order, whole families first.
2. `day_open` precedes everything of its business date; `day_submit` is always the **last** record of its route-day in the outbox (s4.9).
3. A child whose parent has not arrived is **parked** (ack `rejected`, `retryable: true`, code `parent_missing`; the phone resends it later). After `cfg.sync.parked_ttl_days` (7) the server converts it to a final rejection and lists it on the quarantine page.
4. `memo_void` and `due_collection` reference a memo by `client_uuid`; if the memo is unknown they are parked the same way.
5. The record that references a photo (`visit` with a force-sale photo, `outlet_change_request`, `feedback`, `survey_response`, `gift_photo`) syncs **before** the photo's `media_meta`; the blob upload follows its `media_meta` ack (s4.11).

### 4.3 Record envelope

Every record = envelope + `payload` (schema per type; `unevaluatedProperties: false`). Envelope members: `type`, `client_uuid`, `family_uuid`, `rank` (0 to 3), `schema_version`, `business_date`, `captured_at`, `captured_elapsed_ms`, `boot_count`, `clock_offset_ms`, `captured_offline`, `route_id` (required on visit families and route-day records), `acting_for_user_id` (cover assignments), `bundle_version`, `bundle_stale`, `config_version`, and `sig` on header records (`visit`, `memo`, `attendance_event`, `stock_movement`, `due_collection`, `memo_void`, `outlet_change_request`, `redemption`, `gift_photo`): an ES256 signature by the device key over the JCS form of the record without `sig` (s8.3). Identity (user, device) is never in the envelope.

### 4.4 Batch request

`POST /v1/sync/batch`, gzip, members: `batch_uuid`, `device_uuid`, `schema_version`, `app_version`, `trigger` (`SyncTrigger`), `sent_at_device`, `pending_rows`, `time_anchors` (≤ 3), `device_counts` (business date → committed-row counts per type, for every date in the batch plus today), `device_money` (business date → `MoneyTotals`, sent with `day_submit` and at least once per date), `telemetry` (`DeviceDayTelemetry`, at most once per hour), `records` (1 to 500; the phone sends at most `cfg.sync.batch_max_rows` = 200 and at most `cfg.sync.batch_max_kb_raw` = 256 KiB uncompressed, D24-30). The server processes records **in array order**, each family in one database transaction (family atomicity: a family's rows in one batch are stored all-or-nothing; a rejected child does not roll back an accepted header, it is acked separately).

### 4.5 Acknowledgements

The response has **exactly one ack per request record, in request order** (partition invariant: `accepted + duplicate + rejected + quarantined = len(records)`). Ack members: `client_uuid`, `type`, `status`, `code` (when rejected or quarantined), `retryable` (when rejected), `message_key`, `server_id`.

| Status | Meaning | Phone action |
|---|---|---|
| `accepted` | stored now | mark the outbox row `acked`; delete it after 7 days (it stays for reconciliation and reprint) |
| `duplicate` | already stored earlier with the same hash | same as accepted |
| `rejected` + `retryable: true` | not stored yet; will be accepted later without change | keep `pending`; resend after backoff; codes: `parent_missing`, `scope_stale`, `config_version_unknown`, `price_list_unknown`, `outlet_pending_approval`, `server_error` |
| `rejected` + `retryable: false` | final; kept server-side in `sync_rejected` | mark `rejected`, show the reason text (`reason_texts` of the bundle) in Sync Status; count it in reconciliation |
| `quarantined` | stored aside for a human; outcome arrives later in `resolutions[]` | mark `quarantined`; on resolution `accepted`/`accepted_with_fix` it counts as stored, on `discarded` as rejected |

Code catalogue (`RecordOutcomeCode`):

| Code | Status | Retryable | Meaning |
|---|---|---|---|
| `parent_missing` | rejected | yes | parent family row not on the server yet (parked; final after 7 days) |
| `scope_stale` | rejected | yes | route or outlet not in the user's reach at the current `scope_version`; resend after the next bundle |
| `config_version_unknown` | rejected | yes | record cites a config version newer than the server's (only during a deploy) |
| `price_list_unknown` | rejected | yes | `price_list_date` or price row not yet visible to this replica |
| `outlet_pending_approval` | rejected | yes | sale against a new outlet whose request is not approved yet |
| `server_error` | rejected | yes | the record's family failed with an unexpected error |
| `schema_invalid` | rejected | no | payload fails the schema |
| `unknown_record_type` | rejected | no | `type` unknown to this server |
| `unknown_sku`, `unknown_outlet`, `unknown_route` | rejected | no | id does not exist |
| `arithmetic_mismatch` | quarantined | — | memo totals do not reproduce (s7.4) |
| `memo_no_invalid` | rejected | no | memo number does not match the user, date and block (s7.5) |
| `memo_no_duplicate` | quarantined | — | memo number already used by another `client_uuid` |
| `edit_not_allowed` | rejected | no | edit after Sales Submit, after QC lock or outside `cfg.memo.edit_window_min` |
| `chain_too_deep` | rejected | no | edit chain longer than `cfg.memo.edit_chain_max` (3) |
| `voided_by_admin` | rejected | no | row captured before an admin data void of its route-day |
| `content_duplicate` | quarantined | — | different `client_uuid`, same content fingerprint (same outlet, lines and minute) |
| `lines_exceed_max` | rejected | no | more lines than `cfg.sale.max_lines_per_memo` (60) |
| `qty_invalid` | rejected | no | quantity zero, negative or above the hard ceiling |
| `attendance_duplicate` | rejected | no | a second check-in or check-out of the same user and date under another `client_uuid` (the first one stands) |
| `checkout_too_early` | quarantined | — | check-out before `cfg.day.checkout_earliest_time` on trusted time |
| `payload_conflict` | quarantined | — | same `client_uuid`, different payload hash |
| `business_date_out_of_window` | quarantined | — | older than `cfg.sync.max_backdate_days` or in the future |
| `scope_out_of_reach` | quarantined | — | route or outlet outside the user's reach on that business date |
| `no_assignment_on_date` | quarantined | — | user had no assignment to `route_id` on `business_date` |
| `device_integrity_failed` | quarantined | — | the device failed integrity or is not device owner while `cfg.device.require_integrity` is true |
| `device_not_enrolled` | quarantined | — | device not enrolled while `cfg.device.require_enrolled` is true |
| `user_disabled` | quarantined | — | user disabled after capture; kept for review, never lost |
| `device_revoked` | quarantined | — | device revoked; upload grant still accepted during the grace window |
| `app_version_blocked` | quarantined | — | captured on a blocked build after the block took effect |
| `after_month_close` | quarantined | — | business date in a closed month beyond `cfg.day.month_close_grace_days` |
| `unknown_gift` | rejected | no | gift id unknown or not offered by the programme |
| `insufficient_points` | rejected | no | redemption above the server balance while `cfg.loyalty.negative_balance_policy` = reject (the default accept_and_flag never uses it) |
| `gift_photo_exists` | rejected | no | a second hand-over photo for the same Astha assignment or redeemed unit under another `client_uuid` |
| `programme_inactive` | quarantined | — | programme not active on the business date or outlet not enrolled |

### 4.6 Resume, retry and ordering guarantees

- A batch is the unit of transmission, a record the unit of outcome. If the connection drops after the server committed, the retry with the same `batch_uuid` returns the stored response (`replayed: true`); if it dropped before, the retry processes it fresh. Either way each record is stored once.
- The phone marks rows `in_flight` with the `batch_uuid` before sending and returns them to `pending` only after a definitive response or a timeout; a killed app restarts by resending the persisted batch first.
- A row that keeps failing (`retryable` reject or 5xx) moves back by `cfg.sync.family_skip_after` (5) attempts so later families are not blocked behind it (poison-row skip-ahead); after `cfg.sync.row_max_retries` (10) attempts it is marked `rejected(retry_exhausted)` locally and still counted in reconciliation.
- Out-of-order arrival is safe: parents park children, states are derived from timestamps (s4.9), and every write is an upsert by `client_uuid`.

### 4.7 Triggers, timers and backoff

| Id | Trigger (`SyncTrigger`) | Behaviour |
|---|---|---|
| T1 | `write_debounce` | after any outbox commit, wait `cfg.sync.debounce_s` (5 s) of quiet, then send if connected; a family header waits up to `cfg.sync.family_hold_max_s` (180 s) for its children |
| T2 | `foreground` | app comes to the foreground and the last successful sync is older than 60 s |
| T3 | `connectivity` | network becomes available (`ConnectivityManager` callback while the process lives); validated with `HEAD /v1/health` (3 s timeout, `X-Aron-Api` present) before sending |
| T4 | `workmanager_connectivity` | one-time unique work `aron-sync` with `NetworkType.CONNECTED` enqueued whenever rows are pending and the process may die |
| T5 | `periodic` | unique periodic work every `cfg.sync.periodic_min` (15 min, the Android floor) **only while rows are pending**; cancelled when the outbox is empty; constraint `batteryNotLow` (`cfg.sync.periodic_requires_battery_not_low`) |
| T6 | `manual` | Sync button |
| T7 | `day_submit` | Sales Submit tapped: send immediately, ignoring debounce |
| T8 | `checkout` | check-out committed: send after a random 0 to `cfg.sync.checkout_jitter_s` (90 s) delay (spreads the 17:00 wave) |
| T9 | `resync` | server generation changed (s4.8) |
| T10 | `digest_resend` | the digest named buckets to resend |
| T11 | `directive` | a signed directive asked for it |

Backoff after a transport failure, 429, 5xx or 503: delay = min(`cfg.sync.retry_cap_s` (300 s), `cfg.sync.retry_backoff_s` (2 s) × 2^(attempt−1)) × U(0.5, 1.0) ("full jitter" on the upper half); `Retry-After`, when present, replaces the computed delay (× U(0.8, 1.2), capped 900 s); a batch response with `hold_s > 0` pauses automatic triggers for `hold_s` × U(1.0, 1.2). In-process retries stop after `cfg.sync.retry_max_inprocess` (5); T4 then takes over. On `500` the phone **bisects**: it splits the batch into halves with new `batch_uuid`s until the failing family is isolated, then sends that family alone with backoff. No trigger ever polls when the outbox is empty; FCM data messages only make the phone pull (bundle delta, config delta, policy) after a random `pull_after_s` (0 to `cfg.ops.push_jitter_s` = 120 s), they never trigger an upload. Wake locks are held only during a send, at most `cfg.sync.wakelock_max_s` (90 s).

### 4.8 Server generation and re-send

Each database lineage has a `generation` UUID (minted at creation, after a failover that may have lost acknowledged writes, and after a point-in-time restore). It is on every response (`X-Server-Generation`) and in the batch response. When a phone sees a new value it calls `GET /v1/sync/generation` (`kind`, `restore_point_utc`, `lost_after_utc`) and resends every row acked after `lost_after_utc` (at most `cfg.sync.resync_window_h` = 24 h back), spread by a random 0 to `cfg.sync.resync_jitter_s` (900 s) delay, with trigger `resync`. Idempotency makes the re-send safe. `POST /v1/sync/digest` closes any remaining gap: for each recent business date and type the phone sends 16 buckets (`count`, `hash` = sum of the first 8 bytes of each acked `client_uuid` modulo 2^64, as 16 hex digits; bucket = first hex digit of the uuid); the server answers the buckets that differ and the phone resends those rows (trigger `digest_resend`). The phone sends a digest once a day after its first successful batch and after every generation change.

### 4.9 Day state machine

The owning entity is the **route-day** (`route_day`: one row per route and business date, created by the server from assignments the evening before; D24-26). AMO and TSO have a **supervisor-day** (`supervisor_day`: user and business date) for attendance and Sales Submit (D24-54). States are **derived on the server from event timestamps**, so a late or repeated record never moves a state backwards.

| State | Entered when (server) |
|---|---|
| `not_started` | route-day exists, nothing received |
| `logged_in` | first bundle request (200, 304 or delta) of date D by the assigned or acting user, or a `day_open` record with `offline_start` (offline start on a cached bundle); a pre-fetch (`for` = a later date) never counts |
| `in_field` | first `attendance_event` check-in or first `visit` of the route-day |
| `synced` | a batch for the route-day was received and nothing is parked for it |
| `submit_pending_rows` | `day_submit` received but server totals are below the device counts in it; the server waits up to `cfg.day.submit_settle_timeout_min` (30 min) for the missing rows |
| `sales_submitted` | totals reached the device counts, or the settle timeout passed (then `submit_count_mismatch = true` and the route shows on sync-health) |
| `final_submitted` | the TSO (or a delegate in `cfg.day.final_submit_delegate_roles`) final-submitted the zone-day containing the route |

Rules:
1. **Sales Submit** is a `day_submit` record (offline-capable, `cfg.day.sales_submit_offline_queue` = true) or `POST /v1/day/sales-submit` online, idempotent by `client_uuid`. After it the phone locks capture for that route-day except dues collection and printing. `submit_cycle` starts at 1.
2. **Submit void** (`POST /v1/day/submit-void`, TSO and above, before Final Submit): the effective submit is voided, the state returns to `synced`, the phone unlocks at its next contact (`day_states[]` in the batch response or delta) and the next submit carries `submit_cycle` + 1.
3. **Final Submit** (`POST /v1/day/final-submit`) is online-only, once per zone and business date (a second one returns `409 ERR_DAY_ALREADY_FINAL_SUBMITTED` with `context.submitted_at`; a replay with the same `client_uuid` returns the first success). Preview (`GET /v1/day/final-submit/preview`) lists every route with its state; routes not submitted are allowed (`cfg.day.final_submit_allow_not_set_routes` = allow).
4. **Late data after Final Submit** is accepted and flagged (`cfg.day.late_sync_after_final_policy` = `accept_and_flag`): stored, counted in the day's aggregates, and listed on the final-submit log as late; it never reopens the day. **Reopen** (`POST /v1/day/reopen`) is audited and limited to `cfg.day.reopen_roles` (admin) within `cfg.day.reopen_window_days` (3).
5. **Data void** (`POST /v1/admin/data-void`, before Final Submit only) tombstones every record of a route-day: rows get `voided_at`, their `client_uuid`s stay in `ingest_registry` with status `voided`, and a route-day barrier rejects late rows captured before the void (`voided_by_admin`) while rows captured after it are accepted (D24-45).
6. **Voids are tombstones everywhere**: a `memo_void` record marks the memo `voided` (never deleted), prints a void slip and removes it from net totals; an edited memo marks the original `superseded`. Aggregates subtract voided and superseded memos.
7. **Day-close rule for counts:** a business date is closed for reconciliation when the route-day is `final_submitted` and every device that wrote to it has an empty outbox for that date (sync-health shows the exceptions).

### 4.10 Bundle

`GET /v1/sync/bundle?for=<date>` returns one snapshot for the caller and a business date (default today). Contents (`Bundle`): `meta` (`bundle_version` = `<date>:<snapshot_seq>`, `valid_for_business_date`, `server_time`, `config_version`, `schema_version`, delta `cursor`, `is_prefetch`, `paged_sections`), `user` (role, locale, `bind_ordinal`, `memo_seq_block_size`), `config` (resolved values plus `scheduled` values within `cfg.sys.schedule_horizon_days` = 7), `code_lists`, `products` (tree and SKUs with base unit and pack factor), `prices` (selling price types valid today plus scheduled), `offers`, `calendar` (holidays and make-up days), `templates` (print templates), **`routes[]`** (one `RouteSnapshot` per route assigned to the user for that date: route, `assignment_kind` primary or cover, `planned_today`, `target_outlets` frozen at the first bundle of the day, `outlets` with **resolved `radius_m` and `max_accuracy_m` per outlet**, open memos for dues, sales plan SKUs, targets, month-to-date achievement, `day_state`), `tasks`, `surveys`, `rubrics`, `supervisor` (AMO and TSO: team members and their routes, pending outlet requests, and for the AMO the zone's open `risk_signals` for the offline Exceptions screen), `reason_texts` (bn and en text per outcome code), `device_policy_version`, **`programmes`** (s4.14: programme periods, loyalty balances per outlet as of the previous business day with league label, expiring points and expiry date, the gift catalogue, Astha targets of the user's routes, Astha gift assignments and pending campaign Gift Verify slots; `null` when no programme is active), **`content`** (AV and KV items with assets to download on Wi-Fi) **`tutorials`** and **`my_outlet_requests`** (the user's own outlet requests of the last 30 days with status and rejection reason). Each `BundleOutlet` also carries `suggested_qty` (the suggested-order hook, empty while `cfg.sale.suggested_qty_enabled` is false).

- **Snapshot generation.** The worker pre-builds D+1 snapshots at `cfg.bundle.d1_generation_time` (22:00), refreshes dirtied users at `cfg.bundle.refresh_time` (03:30) and checks coverage at `cfg.bundle.coverage_check_time` (04:30). Snapshots are stored gzip in Blob Storage; the API serves them with `ETag` = `bundle_version` and answers `If-None-Match` with 304.
- **Sections.** The list sections that may be paged or delta-updated are named by `BundleSectionName`: `outlets`, `open_memos`, `prices`, `offers`, `tasks`, `team`, `pending_outlet_requests`, `programmes`, `content`.
- **Size.** An SR bundle MUST stay under `cfg.bundle.max_gz_kb` (2,048 KiB gz). A section with more than `cfg.bundle.page_threshold_rows` (2,000) rows (AMO zone-wide outlets) is paged: listed in `meta.paged_sections` and fetched with `GET /v1/sync/bundle/page?section=&page=` (`cfg.bundle.page_rows` = 1,000 per page).
- **Delta.** `GET /v1/sync/delta?since=<cursor>&for=<date>` returns `BundleDelta` (`sections` with `upsert`/`delete` per section, `routes_added`, `routes_removed`, `day_states`, `resolutions`, new `cursor`), 304 when nothing changed, `409 ERR_BUNDLE_NEW_BUSINESS_DATE` when the cursor is from an older date, `410 ERR_BUNDLE_CURSOR_EXPIRED` when older than `cfg.bundle.delta_max_age_h` (72 h). A route newly assigned during the day arrives as a whole `RouteSnapshot` in `routes_added`. The phone pulls a delta when `X-Bundle-Version-Current` is newer than its own, on foreground at most every `cfg.bundle.delta_min_interval_min` (30 min), and on an FCM nudge.
- **Config delta.** `GET /v1/config/delta?since=<version>` returns the resolved values that changed for the caller's scope chain (`ConfigDelta`: `values`, `scheduled`, `removed_keys`, `calendar_changes`, `outlet_radius_changes`, `policy_changed`); 304 when unchanged; 410 when the phone is more than `cfg.sys.config_delta_max_age_versions` (500) behind (fetch the bundle). Triggered when a response's `X-Config-Version` is above the phone's, at most every `cfg.sync.config_check_min_gap_min`.
- **Stale bundle.** With no network at day start the phone opens the day on the newest cached bundle not older than `cfg.bundle.stale_max_days` (2) and marks records `bundle_stale: true`; the day counts as logged in (`day_open.offline_start`).
- **Morning storm.** The first automatic bundle fetch of the day waits a random 0 to `cfg.sync.login_jitter_s` (120 s) unless the user is on the login screen.

### 4.11 Media

Photos are compressed on the phone before they are queued: long edge `cfg.media.long_edge_px` (1,024 px), JPEG quality `cfg.media.jpeg_quality` (70), at most `cfg.media.photo_max_kb` (150 KB; the compressor steps quality down to 40, then the edge to 800 px). Order: (1) the referencing record syncs; (2) the `media_meta` record syncs (sha256, bytes, size, `blob_path`); (3) the phone asks `POST /v1/media/sas` for write-only user-delegation SAS URLs (up to 10, `cfg.media.sas_ttl_min` = 15 min, one blob path each) and PUTs the blob with `x-ms-blob-type: BlockBlob`; (4) the server's blob-created event marks the media row `stored` after checking size and sha256. Photos upload on Wi-Fi by default (`cfg.media.wifi_only_default`); evidence photos fall back to mobile data after `cfg.media.evidence_mobile_fallback_h` (6 h). A visit whose photo is still missing after `cfg.media.pending_photo_grace_days` (7) is flagged.

### 4.12 Reconciliation (device count = server count)

For each business date the phone keeps counts of **committed** rows per record type and `MoneyTotals` (active memo count, gross, offer discount, DRP discount, QC deduction, net, paid, due, dues collected, net per product category, issued and sold quantity per SKU in base units). They travel in `device_counts` and `device_money` (s4.4) and in `day_submit`. The server answers `server_totals[]` (per type: accepted, rejected, quarantined; and the same `MoneyTotals`) for every date in the batch plus today, and `GET /v1/sync/totals?business_date=` gives the same for the Sales Submit screen. **Reconciled** means, per type: device committed = server accepted + rejected + quarantined, and money and quantity totals equal to the milli-taka and the unit. The Sales Submit screen shows the five legacy rows (outlet, sale, stock, QC, promotion) mapped from these counts (`cfg.sync.reconcile_types`, D24-25); a mismatch blocks nothing but is shown in red with the count of rows awaited.

### 4.13 Late data and retention

Rows are accepted up to `cfg.sync.max_backdate_days` (7) late (older ones are quarantined, s3.8). A month closes at its last day plus `cfg.day.month_close_grace_days` (3); later rows for it are quarantined `after_month_close`. The ingest registry keeps `client_uuid`s for `cfg.retention.ingest_registry_days` (45). The phone keeps acked rows 7 days and photos `cfg.media.local_keep_days` (2) after upload; it never deletes a row that is not acked.

---

### 4.14 Programme, content and telemetry records

Full parity includes the programmes of the current app (sponsor, overruling D24-56); only the new Phase 2 portals stay out.

1. **Loyalty ledger.** `loyalty_ledger (outlet_id, programme_id, source_type, source_id, points, business_date, expires_on, flags)` is derived on the server and idempotent on `(source_type, source_id)`: one earning row per qualifying `survey_response` (`cfg.loyalty.earning_rules`: the POSM survey photo gives 50 points once per response `client_uuid`; the AMO survey earns nothing), one debit per `redemption`, one expiry row per period from the nightly job after the period's `points_expire_on` (`cfg.loyalty.expiry_days` = 7 days after the period end), and finance adjustments. A replayed upload never posts points twice.
2. **Redemption.** The phone shows the bundle balance (as of the previous business day) minus its own unsynced redemptions, or the live value of `GET /v1/programmes/loyalty/balances` when online. Confirm commits `redemption` and its `redemption_line` rows in one transaction; cash-back points are at most `cfg.loyalty.cash_max_points`, valued at `cfg.loyalty.cash_rate_mtk_per_point` (2 Tk a point). Server rules: `points_total` = cash points + Σ line points and `cash_mtk` = cash points × rate (otherwise quarantined `arithmetic_mismatch`); an unknown gift is rejected `unknown_gift`; an inactive programme or a non-enrolled outlet is quarantined `programme_inactive`; a balance lower than the phone could know (another phone redeemed first) is **accepted and flagged** `overdraw` (`cfg.loyalty.negative_balance_policy` = accept_and_flag; `reject` returns `insufficient_points`). With `cfg.loyalty.redemption_requires_photo` every redeemed unit waits for a Gift Verify photo (`programmes.pending_gift_verifications`).
3. **Gift photos.** `gift_photo` carries the hand-over photo (media purpose `gift_photo`, s4.11) and a fix. Astha: one photo per gift assignment (`cfg.astha.one_photo_per_outlet`); the first accepted photo locks the TSO's choice (`cfg.astha.gift_choice_lock` = on_sr_photo; a later edit gets `ERR_GIFT_CHOICE_LOCKED`). Campaign: one photo per redeemed unit (`unit_no`); Submit is enabled only when every slot has a photo. A second photo for the same slot under another `client_uuid` is rejected `gift_photo_exists` (the phone shows "photo already captured").
4. **Astha.** Targets by outlet, brand and month inside a quarter (`cfg.astha.quarter_start_month`) are entered by `cfg.astha.target_entry_roles` with `PUT /v1/programmes/astha/targets` (never negative; a zero target displays a dash); achievement comes from `dw`; the bundle carries the rows of the user's routes and the AMO picks a route. Gift choices are saved on the web panel with `PUT /v1/programmes/astha/gift-choices` (`cfg.astha.gift_choice_roles`).
5. **Content.** AV and KV items (`content` in the bundle) download on Wi-Fi into the bounded cache (`cfg.content.download_network_policy`, at most `cfg.content.max_item_mb` each). During a call they play in `sequence` before survey and sale; each shown or skipped item is one `content_view` (telemetry class, duplicates tolerated); a missing asset is `skipped_missing` and never blocks the sale.
6. **Telemetry and control records.** `activity_log` (sampled at `cfg.app.activity_log_sample_pct`), `app_error` (scrubbed crash and ANR reports), `consent_accept` (once per user and policy version; with `cfg.app.location_notice_required` no sale starts before acceptance), `sale_abort` (a consumed memo number with no memo; it explains the line in the `memo-number-gaps` report), `price_compliance_check` (AMO control call; counted in the AMO reconciliation row) and `risk_review` (the AMO Exceptions screen offline; the same event as the online review with `review_uuid` = `client_uuid`) follow the ack rules of s4.5. Telemetry records (`content_view`, `activity_log`, `app_error`) are never quarantined for scope.
7. **Superstar.** Server-side only: enrolment slabs and base targets through `PUT /v1/admin/programmes/{id}/enrolments`, criteria from `cfg.superstar.criteria_met_rule`, read through the `superstar-campaign` report. No phone record.

## 5. Android architecture contract

### 5.1 Shape of every app

- **Three application modules, one Gradle build, no product flavours.** `app-sr`, `app-amo`, `app-tso` each set `BuildConfig.ARON_ROLE` and assemble the core and feature modules of their role (s2.1). Single activity, Jetpack Compose, Navigation Compose; Hilt for DI (`@HiltAndroidApp` only in app modules).
- **Layers:** Compose screen → `ViewModel` exposing one `StateFlow<UiState>` and taking intents → use cases (in the feature module, or in a core module when two features share them) → repositories in core modules → Room DAOs and the OkHttp client. A screen never touches Room or OkHttp directly. Business arithmetic (money, totals, distance, business date, memo number) is called from `shared:rules`, never re-implemented.
- **Coroutines:** `Dispatchers.IO` only inside repositories; ViewModels use `viewModelScope`; no `GlobalScope`; long work goes to WorkManager.

### 5.2 Local storage

| Store | Content | Rules |
|---|---|---|
| `aron-device.db` (Room, SQLCipher) | enrolment record, device key alias, time anchors, list of users who logged in on this phone, applied device policy, printer pairing | one per phone |
| `aron-u<user_id>.db` (Room, SQLCipher) | that user's bundle tables, captures, drafts, **outbox**, acks, sync log, reconciliation counters | one per user on a shared phone (D24-32); key wrapped by an Android Keystore AES key |
| DataStore (preferences) | UI preferences only (language, last tab) | never business data |
| `files/media/` | compressed photos waiting for upload | deleted `cfg.media.local_keep_days` after upload |

**Outbox row** (`outbox` table, created in `core-database`): `seq` (autoincrement, commit order), `client_uuid`, `record_type`, `family_uuid`, `rank`, `business_date`, `payload_json` (the full record, envelope included, exactly as it will be sent), `payload_sha256`, `state` (`pending`, `in_flight`, `acked`, `rejected`, `quarantined`), `batch_uuid`, `attempts`, `last_code`, `created_at`, `acked_at`, `server_id`. A capture writes its domain rows and its outbox rows in **one** `withTransaction`; a row is never edited after commit (an edit is a new record). Room schema JSON is exported to `android/core-database/schemas/` and every schema change ships a Room `Migration` with a migration test; destructive migration is forbidden.

### 5.3 Shared phones and sessions

Several users may log in on one phone (D-66 kept, D24-32). Each user has their own database and outbox; logging out or switching user never deletes or blocks another user's pending rows: the sync engine uploads every user's outbox with that user's upload grant. Offline unlock (s8.1) works per user. Memo numbers are per user (s7.5), so two users on one phone never collide.

### 5.4 Background work

| Unique work name | Kind | Constraints | Purpose |
|---|---|---|---|
| `aron-sync` | one-time, expedited when triggered by `day_submit` | `CONNECTED` | upload outbox, then config and bundle deltas if flagged |
| `aron-sync-periodic` | periodic `cfg.sync.periodic_min` (15 min) | `CONNECTED`, battery not low | exists only while the outbox is non-empty |
| `aron-media` | one-time | `UNMETERED` (or `CONNECTED` after the evidence fallback) | photo upload (s4.11) |
| `aron-prefetch` | one-time, evening | `UNMETERED`, charging | D+1 bundle pre-fetch |
| `aron-status` | one-time | `CONNECTED` | device status report on the events of s10.3 |
| `aron-content` | one-time | `UNMETERED` (per `cfg.content.download_network_policy`) | AV, KV and tutorial asset download into the bounded cache |

No foreground service runs during the day except while printing (Bluetooth) and during a sync triggered by the user. The DPC exempts the app from battery optimisation (s10.2) so Samsung and Honor savers do not kill WorkManager.

### 5.5 Location, printing, camera

- **Location:** `FusedLocationProviderClient.getCurrentLocation` on demand only (s11.1), priority from `cfg.geo.fix_accuracy_mode` (`balanced` default), timeout `cfg.geo.fix_timeout_s` (15 s). GNSS status and measurement callbacks are registered only for the fix window. No continuous location, no `requestLocationUpdates` except the batched breadcrumb request when `cfg.geo.breadcrumbs_enabled` (off by default; interval `breadcrumb_interval_min` = 30, `setMaxUpdateDelayMillis` = interval, D24-46).
- **Printing:** Bluetooth Classic SPP (UUID `00001101-0000-1000-8000-00805F9B34FB`) to the MP-58N class 58 mm printer, 384 dots per line, ESC/POS. Bangla text is rendered to a 1-bit bitmap and sent with `GS v 0`; templates come from the bundle (`templates`, `cfg.print.template_version`). The printer is connected on demand and released after `cfg.print.disconnect_idle_s` (120 s).
- **Camera:** CameraX capture inside the app only for evidence photos (`cfg.media.evidence_camera_only`); no gallery picker for evidence.

### 5.6 Localisation and fonts

All user-visible strings live in `res/values-bn/strings.xml` and `res/values/strings.xml` (English) of the module that shows them; none are hard-coded. Bangla is the default app language (per-app locale set to `bn` at first run, switchable in Settings). Digits are shown as Bengali digits when the language is Bangla (formatter in `core-common`). A Bengali font (Noto Sans Bengali, OFL) is bundled in `core-ui` `res/font`. Server texts arrive as `message_key` and are looked up locally.

### 5.7 Budgets (checked in CI and on Day 6)

| Budget | Limit | Where checked |
|---|---|---|
| APK size per ABI (release, R8) | ≤ 30 MB (`cfg.release.apk_max_mb`), ABI splits `arm64-v8a`, `armeabi-v7a` | CI step on release builds |
| Location | on-demand fixes only; ≤ 80 fixes and ≤ 15 GPS minutes per SR-day | Day 6 battery log |
| Wake locks | ≤ 90 s each, ≤ 10 min per day | Day 6 battery log |
| Mobile data | sync and bundle ≤ 5 MB per SR-day; photos ≤ 3 MB per day on mobile | Day 6 data run |
| Cold start to login screen | ≤ 2 s on the Galaxy A06 | Day 6 |
| Memo commit (tap Save to printed) | commit ≤ 300 ms; never waits for the network | instrumented test |

### 5.8 Robustness rules

1. Every multi-step flow (sale, stock entry, outlet request) keeps its draft in Room; a kill and relaunch restores the draft. The commit is one transaction.
2. The app never blocks a sale on the network, the printer, a photo upload or a bundle refresh.
3. Release builds: `android:allowBackup="false"`, no cleartext traffic, user-installed CAs not trusted, no exported component except the launcher activity, the DPC receiver (protected by `BIND_DEVICE_ADMIN`) and Firebase's own service. No certificate pinning in Phase 1 (Front Door-managed certificates rotate; a pin would strand 8,500 phones), D24-61.
4. Crash and ANR reporting: App Insights is not on the phone; the app logs to a local ring buffer (1 MB) that the `upload_support_bundle` directive uploads.

---

## 6. Backend and web architecture contract

### 6.1 Processes

One container image `aron-backend` (JRE 21, `backend:app` distribution) runs in three roles chosen by `ARON_ROLE` (D24-27):

| Role | Runs | Scaling |
|---|---|---|
| `api` | Ktor (Netty) on port 8080: every `/v1` route | Container Apps, 3 to 30 replicas, HTTP concurrency rule |
| `worker` | aggregate projector, bundle generator, scheduled jobs (day rollover, settle timeouts, parked-row expiry, export jobs, risk rules, FCM sender); jobs that must run once take a PostgreSQL advisory lock | 1 to 10 replicas |
| `migrate` | Flyway `migrate` from `classpath:db/migration`, then exit | Container Apps job before each deploy |

### 6.2 Modules and endpoint ownership

| Module | Owns | Endpoint tags |
|---|---|---|
| `platform` | env config, Hikari pools (write and read replica), JDBI, transactions, problem mapping, request id, JSON, auth plugin (JWT verify, device proof verify), reach resolver interface, clock, metrics, `RecordHandler` registry, domain-event outbox writer, audit writer | — |
| `auth` | users' credentials, login, tokens, refresh families, MFA, device enrolment, nonce, device binding and OTP, device admin, enrolment tokens, audit reads | `auth`, `devices`, `admin-devices`, `admin-audit`; `/v1/me` |
| `sync` | bundle and delta, batch ingest, digest, totals, generation, route-day state machine, Sales Submit, Final Submit, submit void, reopen, cover, data void, quarantine, memos read, web entry (route-day, Astha outlet-SKU, QC), paper backfill and entry unlocks (same ingest path) | `sync`, `day`, `memos`, `web-entry`; `/v1/admin/quarantine*`, `/v1/admin/data-void` |
| `masterdata` | geography, clusters, routes and assignments, product tree, SKUs, prices (preview, approval), sales plans, offers, outlets (incl. bulk outlet kind), users admin and scope, targets and target revisions, supervisor targets, calendar, code lists, outlet requests, leave, visit plans, feedback (incl. status), programmes, enrolments, gifts, Astha targets and gift choices, surveys, rubrics, AV and KV content, tutorials, print templates, dues adjustments | `admin-geography`, `admin-routes`, `admin-products`, `admin-outlets`, `admin-users` (except permissions), `admin-targets`, `admin-calendar`, `outlet-requests`, `people`, `programmes`, `admin-content`, `admin-finance` |
| `config` | key registry, scoped values, resolution, change requests and approvals, versions, reach, what-if, blast radius, density and calibration, permission matrix (`cfg.web.menu_by_role`), device policy rendering, release policy and app releases, public config, update check | `config`, `admin-config`, `admin-releases`; `/v1/admin/permissions*`, `/v1/app/update-check` |
| `analytics` | aggregate projector, loyalty ledger and expiry job, dashboards and tracking actions, reports, exports and the export log, risk-signal rules and reviews, team locations, nearby outlets and stock, app home, tutorials read | `dashboards`, `reports`, `risk`, `team`; `/v1/app/home`, `/v1/tutorials`, `/v1/programmes/loyalty/balances` |
| `notify` | FCM tokens, notifications, task records and task API | `notifications`, `tasks`; `/v1/devices/me/push-token` |
| `media` | SAS issue, read URLs, blob-created handling, multipart fallback, admin asset uploads, PDA to Support uploads, web error reports | `media`, `support`; `/v1/admin/assets` |
| `app` | wiring, health and readiness, `main` | `health` |

Ingest is one path: `sync` validates the envelope, checks idempotency and reach, then calls the `RecordHandler` registered for the type (handlers live in the module that owns the table: for example `outlet_change_request` in `masterdata`, `task` in `notify`). The handler writes its rows and a `domain_event` row in the same transaction.

### 6.3 Data, events and aggregates

- PostgreSQL 16 (Azure Database for PostgreSQL Flexible Server, zone-redundant HA). Schemas: `app` (transactions, master data, config, `audit_log`), `dw` (aggregates read by dashboards), `stg` (reserved for imports; empty in this build). Every transaction table has `business_date date not null` and UTC `timestamptz` columns; device-originated rows have `client_uuid uuid unique not null`.
- **Domain-event outbox:** `app.domain_event (id bigserial, event_type, aggregate_type, aggregate_id, business_date, payload jsonb, created_at)`; the worker reads it in id order with `FOR UPDATE SKIP LOCKED`, updates `dw.agg_*` with idempotent upserts keyed by event id, and records its position. Phase 2 consumers (mother dashboard, indent portal) subscribe to the same table (s12.5).
- Dashboards and reports read **only** `dw` (from the read replica when configured, `ARON_DB_READ_URL`); they never scan transaction tables.

### 6.4 Server configuration (environment)

| Variable | Meaning |
|---|---|
| `ARON_ROLE` | `api`, `worker` or `migrate` |
| `ARON_ENV` | `dev` or `prod` (selects defaults such as `cfg.device.require_enrolled` overrides, s9.4) |
| `ARON_DB_URL`, `ARON_DB_READ_URL` | JDBC URLs; credentials from Key Vault references (managed identity), never in the image |
| `ARON_JWT_SIGNING_KEY` | ES256 private key (PKCS#8 PEM) from Key Vault secret `aron-jwt-signing-key`; `ARON_JWT_KID` its key id |
| `ARON_BLOB_ACCOUNT`, `ARON_BLOB_CONTAINER_MEDIA` | photo storage (user-delegation SAS through managed identity) |
| `ARON_FCM_SERVICE_ACCOUNT_JSON` | Firebase service account JSON from Key Vault secret `aron-fcm-service-account` (filled at deploy from the GitHub secret `FCM_SERVICE_ACCOUNT_JSON`) |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | telemetry |
| `PORT` | 8080 |

### 6.5 Web

- Next.js (App Router) + TypeScript (strict) + Tailwind, built as a standalone Node server and deployed to Azure Container Apps next to the API. Types for every API call come from `web/src/contract/openapi.d.ts` (generated, s1.3).
- **BFF auth:** the browser talks to Next.js route handlers under `/api/bff/*`; they hold the refresh token in the `aron_rt` HttpOnly cookie, mint access tokens server-side and call the API. The browser never stores a token. Web access tokens live 15 min. Web login: username and password, then TOTP for `ADMIN`, `SUPERADMIN` and `SUPPORT` (`cfg.auth.mfa_required_roles`, D24-33).
- Maps use the Google Maps JavaScript API with the key from the GitHub secret `MAPS_WEB_KEY`, injected at build time as `NEXT_PUBLIC_MAPS_WEB_KEY` and restricted by HTTP referrer in Google Cloud.
- Dashboards read the same aggregates as the AMO and TSO app home (`/v1/dashboards/*`, `/v1/app/home`); Excel exports are produced by the server (`/v1/reports/{report_key}/query` with `format: xlsx`).

---

## 7. Money, units and numbering

### 7.1 Money

All money is **integer milli-taka** (`*_mtk`, int64; 1 Tk = 1,000 mtk; 1 paisa = 10 mtk). Milli-taka is needed because seed prices carry 3 decimals (for example 7.935 Tk a stick = 7,935 mtk). Floating point is never used for money on phone, server or web. PostgreSQL columns are `bigint`. Display: taka with 2 decimals (`৳ 1,164.20`, Bengali digits in Bangla); unit prices may show 3 decimals.

### 7.2 Quantities and units

Every quantity is an integer in the **SKU's base unit** (`qty_base`): sticks for cigarettes and bidis, pieces for lighters, dozens for matches (`cfg.sale.qty_entry_unit`; lighter and match units MUST-CONFIRM, s14). Lines also record what the user typed: `qty_entered`, `unit_entered` (`stick`, `piece`, `dozen`, `pack`) and `pack_factor` (base units per pack, from the SKU at capture). `qty_base = qty_entered × (unit_entered = pack ? pack_factor : 1)`. The UI shows a **read-only pack badge** beside the entry (`= 2 প্যাকেট + 4`), computed from `qty_base` and `pack_factor`; it is never stored. Quantities are never summed across SKUs with different base units; reports sum per SKU or convert with an explicit report unit.

### 7.3 Line arithmetic

`line.gross_mtk = divHalfUp(qty_base × base_price_mtk, price_per_qty)` where `base_price_mtk` is the price of `price_per_qty` base units (normally 1) from the price row valid on the business date for the outlet's selling price type (`outlet`, `cc` or `distributor`). `divHalfUp` rounds half away from zero (`shared:rules` `Money.divHalfUp`). Free lines (`line_kind` `promo_free`, `free_sample`, `drp_reward`) are priced in `gross_mtk` like sale lines and offset by a `memo_discount` of the same value (`free_goods` for promo and sample, `drp` for DRP rewards), so volume and gross stay comparable across memos.

### 7.4 Memo total

A memo is committed on the phone as one transaction: header, lines, discount lines (`memo_discount`: `sku_id` or null, `qty_base`, `value_mtk`, `kind` `offer`, `drp` or `free_goods`, `offer_id`, `offer_version_id`) and QC lines (`qc_line` with `settlement_mtk` and `applied_to_memo`). The server recomputes every equation and quarantines the memo (`arithmetic_mismatch`) if one fails:

```
gross_mtk          = Σ memo_line.gross_mtk
offer_discount_mtk = Σ memo_discount.value_mtk  where kind ∈ {offer, free_goods}
drp_discount_mtk   = Σ memo_discount.value_mtk  where kind = drp
qc_deduction_mtk   = Σ qc_line.settlement_mtk   where applied_to_memo
raw_mtk            = gross − offer_discount − drp_discount − qc_deduction
net_mtk            = roundToPaisaHalfUp(raw_mtk)          (a multiple of 10)
round_adj_mtk      = net − raw                            (|round_adj| ≤ 5)
paid_mtk + due_mtk = net_mtk;  is_credit = due_mtk > 0
line_count, discount_line_count, qc_line_count = the number of child records
```

Sums are taken over unrounded integer line values and rounded **once** (`cfg.memo.rounding_mode` = `half_up_paisa`). `net_mtk` and `due_mtk` may be negative only when a zero sale with QC settlement leaves a credit (`cfg.memo.allow_negative_net` = true). Fixtures (in `shared/rules` tests): 116,416 mtk → net 116,420; 20 sticks × 7,935 mtk = 158,700 mtk; 5 mtk rounds away from zero.

### 7.5 Memo number

Format **`<username>-<yyMMdd>-<seq>`** (`cfg.memo.number_format`), for example `sr1042-261005-007`. `username` is the lower-case login of the capturing user (SR and AMO usernames match `^[a-z][a-z0-9]{3,31}$`, no hyphen); `yyMMdd` is the business date; `seq` is decimal, zero-padded to at least 3 digits.

- `seq = bind_ordinal × block_size + n`, with `n = 1, 2, …` the user's memo counter for that business date on this phone, `block_size` = `cfg.memo.seq_block_size` (500) and `bind_ordinal` ∈ {0, 1, 2, 3} assigned by the server at bind-device: the lowest ordinal not held by another active binding of the user. A user may hold at most 4 active device bindings (`409 ERR_DEVICE_LIMIT_REACHED`). Two phones of one user therefore never produce the same number offline (D24-24).
- If `n` exceeds `block_size`, the phone continues in the overflow range `seq = 5000 + bind_ordinal × 1000 + (n − block_size)` for up to 999 more memos and records `memo_seq_overflow` in telemetry; beyond that the phone refuses a new memo with `memo.seq_exhausted` (1,499 memos in a day on one phone; not reachable in practice).
- The counter is stored in Room and incremented in the same transaction as the memo. An edited memo takes a new number; voided and superseded numbers are never reused. The server rejects a number that does not match the user, the business date and the user's ordinal ranges (`memo_no_invalid`) and quarantines a number already used by another `client_uuid` (`memo_no_duplicate`). The `memo-number-gaps` report lists gaps.

### 7.6 Rounding summary

| Value | Rule |
|---|---|
| line gross | `divHalfUp` to the milli-taka |
| memo net | sum unrounded, then half-up to the paisa once; `round_adj` carries the difference |
| totals and aggregates | exact integer sums of stored values; never re-rounded |
| percentages (KPIs) | computed from integers, rounded half-up to 2 decimals for display; `null` when the denominator is 0 |
| distances | metres as `double` from `shared:rules` haversine (WGS84 mean radius 6,371,008.8 m); compared with `<=` against the integer radius |

---

## 8. Authentication, devices and roles

### 8.1 Login, tokens and offline unlock

**Phone login.** `POST /v1/auth/login {username, password, client: app_sr|app_amo|app_tso, device_uuid}`. In production the device must be enrolled and active (`cfg.device.require_enrolled`, else `403 ERR_DEVICE_NOT_ENROLLED`). Outcomes: `ok` (tokens), `bind_required` (user not bound to this device: a `bind_token` with `aud aron-bind`, 10 min), `password_change_required` (temporary password). **Binding:** the TSO sees a 4-digit OTP for the SR on the web Device OTP panel (`GET /v1/admin/device-otps`; `cfg.auth.otp_length` = 4, valid `cfg.auth.otp_ttl_min` = 120 min); the SR types it; the phone calls `POST /v1/auth/bind-device` with the bind token and `X-Device-Proof`; the response carries the tokens and `bind_ordinal`. **Web login:** password, then `mfa_required` with an `mfa_token` (`aron-mfa`, 5 min) for MFA roles, then `POST /v1/auth/mfa/verify` (TOTP or recovery code).

| Token | Lifetime | Rules |
|---|---|---|
| Access (phone) | 60 min + U(0, 10) min jitter (`cfg.auth.access_ttl_min`, `cfg.auth.access_ttl_jitter_min`) | ES256 JWT; `sync/batch` accepts an access token expired by at most 60 s |
| Access (web) | 15 min | minted by the BFF |
| Refresh (full grant) | sliding 30 days (`cfg.auth.refresh_ttl_days`), absolute 90 ± 15 days drawn per family (`cfg.auth.refresh_absolute_days`) | opaque 256-bit, stored hashed; rotated on every use; a replaced token reused within 60 s (`cfg.auth.refresh_grace_s`) gets the same replacement, after that the family is revoked (`401 ERR_AUTH_REFRESH_REUSED`) |
| Refresh (upload grant, phones) | idle 7 days (`cfg.auth.upload_grant_idle_days`) | `aud aron-upload`; may only upload; survives logout, password change and user disable so rows already captured always reach the server; ends with device revocation plus grace |
| `bind_token` / `mfa_token` | 10 min / 5 min | single purpose |

**Offline unlock.** After an online login the phone stores an Argon2id verifier of the password (m = 32 MiB, t = 2, p = 1, 16-byte salt) in the user's encrypted database. With no network the user unlocks with the password for `cfg.auth.offline_unlock_max_days` (7) since the last online login; `cfg.auth.offline_unlock_max_attempts` (10) failures start a doubling cool-down. Selling never needs a live access token; uploads use whatever grant is valid when the network returns.

**Lockout.** 10 failed logins in 15 min per `username + device + IP class` (`cfg.auth.lockout_key_mode`) lock for 15 min, doubling (`403 ERR_AUTH_ACCOUNT_LOCKED`, `retry_after_s`).

### 8.2 Access-token claims

| Claim | Value |
|---|---|
| `iss` | `aron` |
| `aud` | `aron-api`, `aron-upload`, `aron-bind` or `aron-mfa` |
| `sub` | user id (decimal string) |
| `uname` | username |
| `role` | one `Role` |
| `sv` | the user's `scope_version` at mint |
| `did`, `dvu` | device id and device uuid (phones) |
| `flv` | `sr`, `amo`, `tso` or `web` |
| `perm` | admin permission bundle ids (web) |
| `pii` | whether outlet owner and phone columns may be returned |
| `amr` | `["pwd"]`, `["pwd","otp"]` or `["pwd","mfa"]` |
| `iat`, `nbf`, `exp`, `jti` | standard |

Header `alg ES256`, `kid` = the key id; keys are published at `GET /v1/auth/jwks`. The signing key is a P-256 key loaded at startup from Key Vault (D24-19); rotation publishes the new key in JWKS 24 h before it signs.

### 8.3 Device key and proofs

At enrolment the app creates an EC P-256 key in the Android Keystore (StrongBox when present), non-exportable, with a key-attestation challenge of `SHA-256(enrolment_token)`, and sends the public key and the attestation chain (s10.4). `X-Device-Proof` and record `sig` values are ES256 signatures (raw r‖s, base64url, 86 characters) over UTF-8 strings whose lines are joined by `\n`:

| Call | Signed string |
|---|---|
| refresh | `aron-proof-v1`, `refresh`, `<device_uuid>`, `<hex sha256(refresh_token)>`, `<nonce_bucket>` |
| bind-device | `aron-proof-v1`, `bind`, `<device_uuid>`, `<hex sha256(otp)>`, `<nonce_bucket>` |
| sync/batch | `aron-proof-v1`, `batch`, `<device_uuid>`, `<hex sha256(gzip body bytes)>`, `<batch_uuid>`, `<X-Batch-Attempt>` |
| devices/me/* | `aron-proof-v1`, `device`, `<device_uuid>`, `<METHOD> <path>`, `<hex sha256(body, or empty)>`, `<nonce_bucket>` |
| record `sig` | `aron-sig-v1`, `<type>`, `<client_uuid>`, `<hex sha256(JCS(record without sig))>` |

`nonce_bucket = floor(unix_seconds / 300)` of trusted time; the server accepts the current and the previous bucket. A bad or missing proof where required is `401 ERR_DEVICE_PROOF_INVALID`; on a batch it fails the whole request (the phone retries after re-checking its key). An invalid record `sig` quarantines that record (`device_integrity_failed`).

### 8.4 Scope (reach) is server-side

Reach is computed on the server from the user's role, `user_scope` (effective-dated supervisory nodes) and `route_assignment` (primary and cover, effective-dated), and cached per `(user_id, scope_version)` for 5 minutes. The client never sends scope ids; selectors only narrow (s3.5). Any change to a user's role, status, scope or assignments bumps `scope_version`; a request whose token carries an older `sv` gets `401 ERR_SCOPE_CHANGED` (refresh, then a full bundle). Every ingested record is checked against the reach **on its business date**.

| Role | Reach |
|---|---|
| `SR` | routes assigned to the user on the date (primary or cover) and their outlets; own records |
| `AMO` | the zone(s) in `user_scope`: all routes, outlets and users of those zones |
| `TSO` | the zones in `user_scope` (normally the zones of one territory) |
| `DMO` | a division; `WM` a wing; `TOP` national |
| `ANALYST` | national, read-only, no PII unless granted |
| `SUPPORT` | national read of users, devices and sync health; credential and device actions; no business edits |
| `ADMIN`, `SUPERADMIN` | national |

### 8.5 Roles and permissions

`Role` = `SR`, `AMO`, `TSO`, `DMO`, `WM`, `TOP`, `ANALYST`, `SUPPORT`, `ADMIN`, `SUPERADMIN` (one role per user; `TOP` and `SUPPORT` added by D24-21). Web roles of `docs/23` Q6 map to WM, DMO, TSO, ADMIN, SUPERADMIN, ANALYST. R = read in reach, W = write in reach, A = approve, — = none.

| Capability | SR | AMO | TSO | DMO | WM | TOP | ANALYST | SUPPORT | ADMIN | SUPERADMIN |
|---|---|---|---|---|---|---|---|---|---|---|
| Field capture by sync (sale, stock, attendance, visits) | W | W | W (visits, plans, leave, feedback) | — | — | — | — | — | — | — |
| Outlet requests | W (create) | W (verify) | R | A (approve on web) | A | R | R | — | A | A |
| Tasks | R, W (events) | W | W | W | W | R | R | — | W | W |
| Sales Submit / Final Submit / submit void | W (own) / — / — | W (own) / — / — | — / W / W | — / W (delegate) / W | R | R | R | — | W | W |
| Day exceptions, leave decisions | W (raise) | W (raise) | A (exceptions) | A (leave) | A | R | R | — | A | A |
| Dashboards, reports, team map | — | R (app home) | R | R | R | R | R | R (sync health) | R | R |
| Risk signals | — | — | R, W (review) | R, W | R, W | R | R | R | R, W | R, W |
| Master data (geo, routes, products, prices, offers, outlets, targets, calendar, code lists) | — | — | W (radius proposals, D24-59) | R | R | R | R | R | W | W |
| Users and scope | — | — | W (reset password and unlock SR and AMO users of own zones) | R | R | R | — | W (credentials) | W | W (incl. ADMIN roles) |
| Devices, enrolment tokens, device OTPs | — | — | R (OTP of own zones, view) | R | R | — | — | W | W | W |
| Config changes | — | — | W (radius proposals, own territory) | — | — | — | — | — | W (C0 to C2), adopt proposals | W, A (C3) |
| Releases | — | — | — | — | — | — | — | R | W (draft) | W, A (publish) |
| Quarantine resolve, data void | — | — | — | — | — | — | — | — | W | W, A |
| Audit log | — | — | — | — | — | — | R | R | R | R |
| Loyalty redemption, gift photos (sync) | W | W | — | — | — | — | — | — | — | — |
| Astha targets / gift choices | — | R | W / W (own zones) | R | R | R | R | — | W / W | W / W |
| Programmes, gifts, surveys, rubrics, content, tutorials, print templates | — | — | — | — | — | — | — | — | W | W |
| Web entry and QC entry / paper backfill / entry unlocks | — | — | W (own zones) / — / — | — | — | — | — | — / W / W | W / W / W | W / W / W |
| Target revisions, supervisor targets | — | — | R | R | A (level 1) | R | R | — | W | W, A |
| Price batches above `cfg.price.max_change_pct`, dues adjustments | — | — | — | — | — | — | — | — | W (maker) | W, A (checker) |
| Permission matrix (`cfg.web.menu_by_role`) | — | — | — | — | — | — | — | — | R | W (C3 change, second SUPERADMIN approves) |

### 8.6 Maker-checker and separation of duties

A second person is required for: C3 config changes (approver ≠ requester, `ERR_CFG_SELF_APPROVAL`); outlet request approval (approver ≠ requester ≠ verifier, `ERR_SEPARATION_OF_DUTIES`); quarantine `accept_with_fix` of a data-entry class; release publish; data void. Every write on the web and every admin action is written to `app.audit_log` (append-only, each row carries the SHA-256 of the previous row).

### 8.7 Device lifecycle

`DeviceState`: `enrolled` (after `POST /v1/devices/enrol`) → `active` (first successful bind) → `suspended` (reversible; login refused, uploads accepted) → `revoked` (login refused; upload grant accepted for 7 days, then refused) or `replaced` (a new phone took over the user's bindings). How the server knows a phone is genuine: (1) at enrolment, the Keystore **key attestation** chain is verified to a Google root, with the attestation challenge, `attestationApplicationId` package and signing-certificate digest matching the flavour, and verified-boot state; the app also reports `isDeviceOwnerApp` (s10.4); (2) every status report (s10.3) repeats `device_owner`, the applied policy version and the restriction states; (3) **Play Integrity** (standard request; `requestHash` = SHA-256 of a server nonce from `POST /v1/devices/nonce`) every `cfg.device.integrity_refresh_h` (24 h) and at check-in, decoded server-side with Google's `decodeIntegrityToken`. `deviceRecognitionVerdict` containing `MEETS_DEVICE_INTEGRITY` is required when `cfg.device.require_integrity` is true, which the **production** database seed sets (sponsor, D24-18; the dev seed keeps false); `appRecognitionVerdict` is recorded but never required because the APK is installed outside Play (`UNRECOGNIZED_VERSION` expected). A phone failing the device verdict keeps capturing; its attendance and sales are quarantined `device_integrity_failed` (never rejected, as D24-17) and supervisors see `DEVICE_INTEGRITY_FAIL`. The result is `DeviceTrust` (`trust_level` `high`, `normal`, `low`, `blocked`).

---

## 9. Config model

### 9.1 Tables (owned by `backend:config`, migrations by the db lane)

| Table | Columns (key ones) | Rules |
|---|---|---|
| `app.cfg_key` | `key` PK, `area`, `kind` (S, T, O), `value_type`, `default_value jsonb`, `bounds jsonb`, `scope_levels text[]`, `risk_class` 0 to 3, `effect` (B, S, R), `delivery` (server, device, both), `requires_ack`, `future_dated_only`, `restrictive_dir`, `editor_permission`, `description_en`, `description_bn` | the registry; seeded by migration from s9.5; a key is never deleted (retired keys keep `retired_at`) |
| `app.cfg_value` | `id`, `key`, `scope_type` (`ConfigScopeType`), `scope_id` (null for global), `value jsonb`, `effective_from timestamptz`, `effective_to timestamptz null`, `config_version`, `change_id`, `created_by`, `reason` | exclusion constraint: at most one row per `(key, scope_type, scope_id)` valid at any instant; rows are closed (`effective_to`), never updated in place |
| `app.cfg_change` | `change_id`, `status` (`ConfigChangeStatus`), `items jsonb` (key, scope, value, effective_from), `reason` (required, ≥ 10 characters), `risk_class` (max of items, after escalation), `requested_by`, `approver`, `apply_at`, `decided_at`, `blast_radius jsonb` | C2 waits `cfg.sys.c2_delay_min`; C3 waits for a second approver |
| `app.cfg_version` | `config_version` (bigint, monotonic), `change_id`, `committed_at`, `summary` | one row per committed change set; rollback creates a new version |
| `app.cfg_ack` | `device_id`, `user_id`, `config_version`, `applied_at`, `keys text[]` | from `config_ack` records; feeds `GET /v1/admin/config/reach/{version}` |

### 9.2 Resolution

For a key and a subject at an instant: collect the subject's scope chain (device, user, outlet, route, zone, geo_class, territory, division, wing, role, global), take the `cfg_value` rows of the key valid at the instant on any node of the chain, and pick the **most specific**: device 120 > user 110 > outlet 100 > route 90 > zone 80 > geo_class 70 > territory 50 > division 40 > wing 30 > role 10 > global 0. No row: the registry default. A row on a level not in the key's `scope_levels` is refused at write (`ERR_CFG_SCOPE_NOT_ALLOWED`). `geo_class` (Hill, Urban, SemiUrban, Rural) is an attribute of the outlet, not a node with a parent (D24-13); house, wave and device-model levels of `docs/19` are not used in Phase 1. For the geofence the server resolves `cfg.geo.radius_m` and `cfg.geo.max_accuracy_m` **per outlet** and ships the result in the bundle (`BundleOutlet.radius_m`, D24-15); the phone never resolves a radius itself. `GET /v1/admin/config/resolve` shows the winning row (provenance).

### 9.3 Versions and propagation

1. Every committed change set increments the global `config_version` (D24-14). Every API response carries `X-Config-Version`.
2. Server-side keys (`delivery` server) take effect on commit; each replica caches resolved values for at most 30 s and drops its cache on PostgreSQL `NOTIFY cfg_changed`.
3. Device keys (`device`, `both`) reach the phone in the bundle (`config.values`, `config.scheduled`) and by `GET /v1/config/delta?since=<version>` when a response shows a newer `X-Config-Version`, or after an FCM `config_pull` nudge (s4.7). The delta contains only keys whose resolved value changed for the caller's chain.
4. Effect: **B** applies at once; **S** at the next session or business day; **R** with the next release. Future-dated values (`future_dated_only`, and any value with `effective_from` in the future within `cfg.sys.schedule_horizon_days` = 7) travel in `scheduled` and the phone switches them at `effective_from` on trusted time, offline included.
5. Keys with `requires_ack` (marked `*` in s9.5) make the phone send a `config_ack` record once applied. Reach (applied / acknowledged / targeted devices) is on `GET /v1/admin/config/reach/{version}`. Target: 95 % of selling phones within 15 min (s13.1).
6. Tolerance (D-431): a record captured under an older `config_version` is judged against the values in force at its capture when the change is younger than `cfg.sys.config_accept_window_h` (48 h); the server never rejects honest offline work because a rule changed meanwhile.

### 9.4 Risk classes and environments

| Class | Applies | Approval |
|---|---|---|
| C0 | at once | none |
| C1 | at once | none; audited |
| C2 | after `cfg.sys.c2_delay_min` (10 min), cancellable | none; audited |
| C3 | after approval by a second person with `SUPERADMIN` (`ERR_CFG_SELF_APPROVAL` for the requester), at most `cfg.sys.c3_max_per_hour` (5) fleet-wide, refused inside `cfg.sys.change_freeze_windows` (07:00 to 09:30 and 16:30 to 19:30 Dhaka, `ERR_CFG_FREEZE_WINDOW`) | yes |

The radius is C1 at outlet, C2 at route, zone, geo_class and territory, C3 at division, wing and global; any radius change that ends above 150 m and increases the resolved value is C3 (`docs/19` escalation). **Environments:** the dev database is seeded with global overrides `cfg.device.require_enrolled = false`, `cfg.device.lockdown_level = dev` and `cfg.device.require_integrity = false` (D24-17); the **production** database is seeded with `cfg.device.require_integrity = true` (sponsor overrule of D24-18: the device verdict `MEETS_DEVICE_INTEGRITY` only) and otherwise uses the registry defaults.

### 9.5 Phase 1 key registry

Legend. **Levels:** G global, ROLE, W wing, D division, T territory, GC geo_class, Z zone, R route, O outlet, U user, DEV device. **Ed** (editor permission `cfg.edit.<domain>`): F field, O ops, P programme, S security, R release, FIN finance. **Eff:** B next request or bundle, S next session or business day, R next release; `*` = the phone sends `config_ack`; FD = future-dated only. **Dl:** srv, dev, both. Names, defaults and bounds follow `docs/19` s3.2 unless marked **new** (a key `docs/19` does not have) or **changed** (a default this page changes, with the decision id).

| Key | Type | Default | Bounds | Levels | Ed | Eff | Dl | Cls |
|---|---|---|---|---|---|---|---|---|
| **Geofence and location** |||||||||
| `cfg.geo.radius_m` | int (m) | 100 | `radius_min_m`..`radius_max_m` | G W D T GC Z R O | F (TSO proposes, D24-59) | B* | both | C1 O / C2 R Z GC T / C3 D W G |
| `cfg.geo.radius_min_m` | int | 20 | 10..100 | G | S | B | srv | C3 |
| `cfg.geo.radius_max_m` | int | 2000 | 500..5000 | G | S | B | srv | C3 |
| `cfg.geo.tso_radius_mode` | enum(propose, apply) | propose | — | G W | S | B | srv | C3 |
| `cfg.geo.max_accuracy_m` | int | 100 | 30..300 | G W D T GC Z R O | F | B* | both | C2 |
| `cfg.geo.accuracy_tolerant` | bool | false | — | G | F | B | both | C2 |
| `cfg.geo.fix_timeout_s` | int | 15 | 5..30 (above 15 is C3) | G T GC Z | O | B | dev | C1 |
| `cfg.geo.fix_accuracy_mode` | enum(balanced, high) | balanced | — | G T Z | O | B | dev | C3 for `high` |
| `cfg.geo.refresh_max` | int | 3 | 1..5 | G | F | B | dev | C1 |
| `cfg.geo.fix_reuse_max_age_s` | int | 60 | 0..300 | G | O | B | dev | C1 |
| `cfg.geo.stale_fix_max_age_s` (**new**) | int | 30 | 5..300 | G | S | B | both | C2 |
| `cfg.geo.require_precise` | bool | true | — | G | O | B | dev | C2 |
| `cfg.geo.mock_policy` (**changed**, D24-16) | enum(silent_flag, warn_rep, block_sale) | block_sale | — | G W D T Z | S | B* | both | C3 |
| `cfg.geo.no_location_policy` | enum(force_sale_required, allow_unvalidated, block) | force_sale_required | — | G W T Z | F | B* | both | C3 |
| `cfg.geo.breadcrumbs_enabled` (**new**) | bool | false | — | G W D T Z U | O | B* | both | C2 |
| `cfg.geo.breadcrumb_interval_min` (**new**) | int | 30 | 5..120 | G | O | B | dev | C1 |
| `cfg.geo.radio_env_enabled` | bool | false | — | G | S | B | dev | C2 |
| `cfg.geo.max_speed_kmh` | int | 60 | 30..150 | G W T Z | S | B | srv | C2 |
| `cfg.geo.teleport_min_distance_m` | int | 500 | 100..5000 | G T | S | B | srv | C2 |
| `cfg.geo.min_fixes_for_jitter` | int | 8 | 3..50 | G | S | B | srv | C2 |
| `cfg.geo.jitter_threshold_m` | int | 2 | 1..20 | G | S | B | srv | C2 |
| `cfg.geo.perfect_accuracy_threshold_m` | int | 3 | 1..10 | G | S | B | srv | C2 |
| `cfg.geo.same_point_outlets_max` | int | 3 | 2..20 | G T | S | B | srv | C2 |
| `cfg.geo.route_single_point_pct` (**new**, D24-41) | pct | 80 | 50..100 | G | S | B | srv | C2 |
| `cfg.geo.route_single_point_radius_m` (**new**, D24-41) | int | 30 | 5..200 | G | S | B | srv | C2 |
| `cfg.geo.route_single_point_min_visits` (**new**, D24-41) | int | 8 | 3..50 | G | S | B | srv | C2 |
| `cfg.geo.gnss_min_satellites_used` (**new**, D24-40) | int | 4 | 3..12 | G | S | B | srv | C2 |
| `cfg.geo.gnss_cn0_stddev_min_dbhz` (**new**, D24-40) | number | 1.0 | 0.1..5.0 | G | S | B | srv | C2 |
| `cfg.geo.gnss_cn0_mean_max_dbhz` (**new**, D24-40) | number | 48 | 40..60 | G | S | B | srv | C2 |
| `cfg.geo.gps_time_skew_max_s` | int | 60 | 5..600 | G | S | B | srv | C2 |
| `cfg.geo.device_server_mismatch_m` (**new**) | int | 10 | 1..100 | G | S | B | srv | C2 |
| `cfg.geo.short_visit_gap_s` (**new**) | int | 60 | 10..600 | G | S | B | srv | C2 |
| `cfg.geo.short_visit_gap_count` (**new**) | int | 5 | 2..50 | G | S | B | srv | C2 |
| `cfg.geo.integrity_weight` | json (code → weight) | s11.4 column "Weight" | each 0..300 | G | S | B | srv | C2 |
| `cfg.geo.suspicious_score_threshold` | int | 50 | 1..300 | G W T | S | B | srv | C2 |
| `cfg.sec.fraud.location_move_alert_m` | int | 300 | 50..2000 | G | S | B | srv | C2 |
| **Device policy and integrity** (all **new**; `docs/19` has no `cfg.device.*`) |||||||||
| `cfg.device.require_enrolled` | bool | true (dev database: false) | — | G W D T Z U | S | B | srv | C3 |
| `cfg.device.require_integrity` | bool | false; **production seed true** (device verdict only, D24-18 sponsor) | — | G W D T Z | S | B | srv | C3 |
| `cfg.device.integrity_refresh_h` | int | 24 | 1..168 | G | S | B | both | C2 |
| `cfg.device.key_attestation_required` | bool | true | — | G | S | B | srv | C3 |
| `cfg.device.lockdown_level` | enum(dev, prod) | prod (dev database: dev) | — | G W D T Z DEV | S | B* | both | C3 |
| `cfg.device.app_control_mode` | enum(blocklist, allowlist) | blocklist | — | G W D T Z | S | B* | dev | C2 |
| `cfg.device.blocked_packages` | list<text> | s10.6 list | ≤ 300 package names | G W D T Z ROLE | S | B* | dev | C2 |
| `cfg.device.allowed_packages` | list<text> | [] | ≤ 300 | G W D T Z ROLE | S | B* | dev | C2 |
| `cfg.device.always_allowed_packages` | list<text> | s10.6 list | ≤ 100 | G | S | B* | dev | C2 |
| `cfg.device.blocking_enabled` | bool | true | — | G W D T Z ROLE U | S | B* | dev | C2 |
| `cfg.device.blocking_hard_end_time` | time or null | 20:00 | 12:00..23:59 | G W | S | B* | dev | C2 |
| `cfg.device.blocking_working_days_only` | bool | true | — | G | S | B* | dev | C1 |
| `cfg.device.battery_exemption` | bool | true | — | G | O | B | dev | C1 |
| `cfg.device.status_min_interval_min` | int | 60 | 15..1440 | G | O | B | dev | C1 |
| `cfg.device.policy_drift_max_h` | int | 24 | 1..168 | G | S | B | srv | C1 |
| **Day, attendance, submit** |||||||||
| `cfg.day.business_date_cutoff_time` | time | 00:00 | 00:00..06:00 | G | O | S, FD | srv | C3 |
| `cfg.day.checkin_earliest_time` | time | 05:00 | 00:00..12:00 | G W | F | B | both | C1 |
| `cfg.day.checkin_gate` | enum(off, soft, hard) | soft | — | G W | F | B | both | C2 |
| `cfg.day.checkout_earliest_time` | time | 17:00 (inclusive) | 12:00..22:00 | G ROLE W D T Z | F | B* | both | C2 (C3 at D and wider) |
| `cfg.day.sales_submit_offline_queue` | bool | true | — | G | O | B | both | C2 |
| `cfg.day.submit_settle_timeout_min` | int | 30 | 5..240 | G | O | B | srv | C2 |
| `cfg.day.submit_grace_h` | int | 10 | 0..24 | G | O | B | both | C1 |
| `cfg.day.final_submit_allow_not_set_routes` | enum(allow, warn, block) | allow | — | G W | F | B | srv | C2 |
| `cfg.day.final_submit_delegate_roles` | list<role> | [] | subset of DMO, WM | G W | S | B | srv | C3 |
| `cfg.day.late_sync_after_final_policy` | enum(accept_and_flag, quarantine, reject) | accept_and_flag | — | G | F | B | srv | C3 |
| `cfg.day.reopen_roles` | list<role> | [ADMIN] | subset of roles | G W | S | B | srv | C3 |
| `cfg.day.reopen_window_days` | int | 3 | 0..31 | G | F | B | srv | C2 |
| `cfg.day.month_close_grace_days` | int | 3 | 0..10 | G | S | B | srv | C3 |
| `cfg.day.exception_requires_approval` | bool | true | — | G W | F | B | srv | C2 |
| **Sale, memo, print, price, UI** |||||||||
| `cfg.sale.qty_entry_unit` | json (category → unit) | {cigarette: stick, bidi: stick, lighter: piece, match: dozen} | units of `QtyUnit` | G | O | S, FD | both | C3 |
| `cfg.sale.max_lines_per_memo` | int | 60 | 40..200 | G | O | B | both | C1 |
| `cfg.sale.stock_check` | enum(off, warn, block) | warn | — | G W Z | F | B | both | C2 |
| `cfg.sale.force_requires_photo` | bool | true | — | G T | F | B | both | C2 |
| `cfg.memo.number_format` | text | `<username>-<yyMMdd>-<seq3>` | must contain the sequence token | G | O | S, FD | both | C3 |
| `cfg.memo.seq_block_size` | int | 500 | 100..999 | G | O | S, FD | both | C3 |
| `cfg.memo.rounding_mode` | enum(half_up_paisa) | half_up_paisa | Phase 1 implements one mode | G | O | S, FD | both | C3 |
| `cfg.memo.allow_negative_net` | bool | true | — | G | F | B | both | C2 |
| `cfg.memo.edit_window_min` | int | 0 (until Sales Submit) | 0..720 | G T | F | B | both | C1 |
| `cfg.memo.edit_chain_max` | int | 3 | 1..10 | G | F | B | both | C1 |
| `cfg.memo.reprint_max` | int | 5 | 0..20 | G T | F | B | dev | C1 |
| `cfg.print.template_version` | int | 1 | 1..999 | G | F | B, FD | dev | C2 |
| `cfg.print.models` (**changed**) | list<text> | [MP-58N, RPP02N] | 1..5 | G | O | B | dev | C0 |
| `cfg.print.disconnect_idle_s` | int | 120 | 30..600 | G | O | B | dev | C1 |
| `cfg.price.list_version` | int (marker) | 0; +1 on every price publish | ≥ 0 | G | FIN | B | both | C2 |
| `cfg.ui.outlet_badges` | json (badge code → colour, label bn/en) | four dots: red, green, magenta (promotion groups 1 to 3), emblem (Astha) | ≤ 8 badges | G | F | B | both | C1 |
| **Media** |||||||||
| `cfg.media.long_edge_px` | int | 1024 | 640..1280 | G W | O | B | dev | C2 |
| `cfg.media.jpeg_quality` | int | 70 | 40..90 | G | O | B | dev | C1 |
| `cfg.media.photo_max_kb` | int | 150 | 60..200 (above 150 is C3) | G W | O | B | dev | C2 |
| `cfg.media.max_photos_per_visit` | int | 4 | 1..10 | G | F | B | dev | C1 |
| `cfg.media.wifi_only_default` | bool | true | — | G W D T Z | O | B | dev | C2 |
| `cfg.media.evidence_mobile_fallback_h` | int | 6 | 0..48 (0 = never) | G W | O | B | dev | C1 |
| `cfg.media.evidence_camera_only` | bool | true | — | G | S | B | dev | C2 |
| `cfg.media.sas_ttl_min` | int | 15 | 5..60 | G | S | B | srv | C2 |
| `cfg.media.local_keep_days` | int | 2 | 1..14 | G | O | B | dev | C1 |
| `cfg.media.pending_photo_grace_days` | int | 7 | 1..30 | G | O | B | srv | C1 |
| **Sync** |||||||||
| `cfg.sync.debounce_s` | int | 5 | 2..60 | G W | O | B | dev | C1 |
| `cfg.sync.family_hold_max_s` | int | 180 | 60..900 | G | O | B | dev | C1 |
| `cfg.sync.batch_max_rows` | int | 200 | 50..500 | G | O | B | dev | C1 |
| `cfg.sync.batch_max_kb_raw` | int | 256 | 64..1024 | G | O | B | dev | C1 |
| `cfg.sync.retry_backoff_s` | int | 2 | 1..60 | G | O | B | dev | C1 |
| `cfg.sync.retry_cap_s` | int | 300 | 60..900 | G | O | B | dev | C1 |
| `cfg.sync.retry_max_inprocess` | int | 5 | 3..10 | G | O | B | dev | C1 |
| `cfg.sync.row_max_retries` | int | 10 | 3..50 | G | O | B | dev | C1 |
| `cfg.sync.family_skip_after` | int | 5 | 2..20 | G | O | B | dev | C1 |
| `cfg.sync.periodic_min` | int | 15 | 15..120 | G W | O | B | dev | C2 |
| `cfg.sync.periodic_requires_battery_not_low` | bool | true | — | G | O | B | dev | C1 |
| `cfg.sync.login_jitter_s` | int | 120 | 0..600 | G W | O | B | dev | C1 |
| `cfg.sync.checkout_jitter_s` (**new**) | int | 90 | 0..600 | G W | O | B | dev | C1 |
| `cfg.sync.wakelock_max_s` | int | 90 | 10..90 | G | O | B | dev | C1 |
| `cfg.sync.max_clock_skew_min` | int | 10 | 2..60 | G | O | B | both | C2 |
| `cfg.sync.max_backdate_days` | int | 7 | 1..30 | G | F | B | srv | C2 |
| `cfg.sync.parked_ttl_days` | int | 7 | 1..30 | G | O | S | srv | C1 |
| `cfg.sync.resync_window_h` | int | 24 | 1..72 | G | O | B | both | C2 |
| `cfg.sync.resync_jitter_s` | int | 900 | 0..3600 | G | O | B | dev | C1 |
| `cfg.sync.config_check_min_gap_min` | int | 5 | 1..60 | G | O | B | dev | C1 |
| `cfg.sync.reconcile_types` | json (role → row → record types) | SR: outlet, sale, stock, QC, promotion (s4.12) | subset of `RecordType` | G ROLE | O | B | dev | C1 |
| **Bundle** |||||||||
| `cfg.bundle.stale_max_days` | int | 2 | 1..3 | G W | O | B | dev | C2 |
| `cfg.bundle.delta_max_age_h` | int | 72 | 24..168 | G | O | B | srv | C1 |
| `cfg.bundle.delta_min_interval_min` | int | 30 | 30..240 | G | O | B | dev | C1 |
| `cfg.bundle.max_gz_kb` | int | 2048 | 512..4096 | G | O | B | srv | C2 |
| `cfg.bundle.page_threshold_rows` | int | 2000 | 500..10000 | G | O | B | srv | C1 |
| `cfg.bundle.page_rows` | int | 1000 | 200..5000 | G | O | B | srv | C1 |
| `cfg.bundle.d1_generation_time` | time | 22:00 | 20:00..23:30 | G | O | B | srv | C1 |
| `cfg.bundle.refresh_time` | time | 03:30 | 02:30..05:00 | G | O | B | srv | C1 |
| `cfg.bundle.coverage_check_time` | time | 04:30 | 03:30..06:00 | G | O | B | srv | C1 |
| `cfg.bundle.outlet_fields` | json (role → outlet columns) | SR: no NID, TIN or licence | subset of outlet columns | ROLE | S | S | both | C3 |
| **Auth** |||||||||
| `cfg.auth.access_ttl_min` | int | 60 (15 for web-only roles) | 15..1440 | G ROLE | S | S | srv | C2 |
| `cfg.auth.access_ttl_jitter_min` | int | 10 | 0..30 | G | S | S | srv | C1 |
| `cfg.auth.refresh_ttl_days` | int | 30 (sliding) | 7..90 | G ROLE | S | S | srv | C3 |
| `cfg.auth.refresh_absolute_days` | int | 90 | 30..180 | G ROLE | S | S | srv | C3 |
| `cfg.auth.refresh_absolute_jitter_days` | int | 15 | 0..30 | G ROLE | S | S | srv | C3 |
| `cfg.auth.refresh_grace_s` | int | 60 | 0..300 | G | S | S | srv | C2 |
| `cfg.auth.upload_grant_idle_days` | int | 7 | 1..30 | G | S | S | srv | C2 |
| `cfg.auth.offline_unlock_max_days` | int | 7 | 1..14 | G ROLE | S | B | dev | C2 |
| `cfg.auth.offline_unlock_max_attempts` | int | 10 | 3..20 | G | S | B | dev | C2 |
| `cfg.auth.lockout_attempts` | int | 10 | 3..50 | G ROLE | S | S | srv | C2 |
| `cfg.auth.lockout_window_min` | int | 15 | 1..60 | G | S | S | srv | C1 |
| `cfg.auth.lockout_min` | int | 15 (doubling) | 1..1440 | G | S | S | srv | C1 |
| `cfg.auth.lockout_key_mode` | enum(username_device_ipclass, username_device, username) | username_device_ipclass | — | G | S | S | srv | C3 |
| `cfg.auth.password_min_len` | int | 8 field roles, 12 web roles | 8..64 | G ROLE | S | S | srv | C2 |
| `cfg.auth.otp_length` | int | 4 | 4..8 | G | S | S | srv | C1 |
| `cfg.auth.otp_ttl_min` | int | 120 | 5..1440 | G | S | S | srv | C2 |
| `cfg.auth.otp_max_attempts` | int | 5 | 3..10 | G | S | S | srv | C2 |
| `cfg.auth.bind_otp_required` (**new**) | bool | true | — | G W D T Z | S | S | srv | C3 |
| `cfg.auth.mfa_required_roles` (**changed**, D24-33) | list<role> | [ADMIN, SUPERADMIN, SUPPORT] | subset of web roles | G | S | S | srv | C2 |
| **Release** |||||||||
| `cfg.release.min_version_code` (**new**, replaces semver `min_version`, D24-22) | json {sr, amo, tso → int} | {sr: 1, amo: 1, tso: 1} | may only increase; ≤ latest published | G | R | B | both | C3 |
| `cfg.release.blocked_version_codes` (**new**, replaces `blocked_versions`) | json {sr, amo, tso → list<int>} | {sr: [], amo: [], tso: []} | published codes only | G | R | B | both | C3 |
| `cfg.release.update_prompt_policy` | enum(silent, prompt, force_after_date) | prompt | — | G | R | B | both | C2 |
| `cfg.release.update_wifi_only` | bool | true | — | G | R | B | both | C2 |
| `cfg.release.finish_offline_day_before_force` | bool | true | — | G | R | B | dev | C2 |
| `cfg.release.apk_max_mb` | int | 30 | 15..30 | G | R | B | srv | C1 |
| **Operations, notifications, API** |||||||||
| `cfg.ops.push_enabled` (**changed**, D24-23) | bool | true | — | G | O | B | srv | C3 |
| `cfg.ops.push_jitter_s` | int | 120 | 0..600 | G | O | B | srv | C1 |
| `cfg.notify.task_push_enabled` (**new**) | bool | true | — | G W D T Z | O | B | srv | C1 |
| `cfg.ops.sync_hold_s` | int (kind O, auto-expires) | 0 | 0..900 | G W D T Z | O | B | both | C3 |
| `cfg.ops.sync_hold_by_version` | list<int> (kind O) | [] | duration ≤ 24 h | G | O | B | srv | C3 |
| `cfg.ops.read_only_mode` | bool (kind O) | false | duration required | G | O | B | srv | C3 |
| `cfg.ops.maintenance_banner` | json {bn, en, from, to, severity} or null | null | 200 characters each | G W D T Z ROLE | O | B | both | C0 |
| `cfg.ops.report_concurrency_per_user` | int | 2 | 1..5 | G | O | B | srv | C1 |
| `cfg.ops.report_sync_max_rows` (**new**, D24-28) | int | 10000 | 1000..50000 | G ROLE | O | B | srv | C1 |
| `cfg.ops.report_export_max_rows` | int | 200000 | 10000..1000000 | G ROLE | O | B | srv | C1 |
| `cfg.api.rl.device_per_min` | int | 120 | 30..600 | G ROLE | O | B | srv | C2 |
| `cfg.api.rl.user_per_min` | int | 300 | 60..2000 | G ROLE | O | B | srv | C1 |
| `cfg.api.max_batch_body_kb` | int | 1024 | 256..4096 | G | O | B | srv | C2 |
| `cfg.api.max_batch_decompressed_mb` | int | 8 | 2..32 | G | O | B | srv | C2 |
| `cfg.api.max_batch_rows` | int | 500 | 100..1000 | G | O | B | srv | C2 |
| `cfg.api.inflight_batches_per_replica` | int | 64 | 16..256 | G | O | B | srv | C2 |
| **System, retention, SLA** |||||||||
| `cfg.sys.schedule_horizon_days` | int | 7 | 1..30 | G | O | B | both | C1 |
| `cfg.sys.config_delta_max_age_versions` | int | 500 | 50..10000 | G | O | B | srv | C1 |
| `cfg.sys.config_accept_window_h` | int | 48 | 0..168 | G | S | B | srv | C3 |
| `cfg.sys.c2_delay_min` | int | 10 | 0..60 | G | S | B | srv | C3 |
| `cfg.sys.c3_max_per_hour` | int | 5 | 1..20 | G | S | B | srv | C3 |
| `cfg.sys.change_freeze_windows` | list of Dhaka time ranges | 07:00-09:30, 16:30-19:30 | 0..6 ranges | G | S | B | srv | C3 |
| `cfg.retention.sync_batch_response_h` | int | 48 | 24..72 | G | O | S | srv | C1 |
| `cfg.retention.ingest_registry_days` | int | 45 | 30..400 (must exceed `cfg.sync.max_backdate_days` + 30) | G | O | S | srv | C2 |
| `cfg.sla.pending_rows_alert_h` | int | 4 | 1..24 | G | O | B | srv | C1 |
| **Programmes and content** (sponsor parity, s4.14) |||||||||
| `cfg.loyalty.program_active` | bool | true | — | G W D T | P | B | both | C2 |
| `cfg.loyalty.earning_rules` | json | POSM survey photo 50 points once per response; AMO survey 0 | points 0..100000 | G W | P | S | srv | C3 |
| `cfg.loyalty.expiry_days` | int | 7 (days after the period end; MUST-CONFIRM, D24-68) | 0..365 | G | P | S | srv | C3 |
| `cfg.loyalty.cash_rate_mtk_per_point` | money_mtk | 2000 (2 Tk a point) | 0..100000 | G W | P | B* | both | C3 |
| `cfg.loyalty.cash_max_points` | int | 199 | 0..100000 | G W | P | B | both | C2 |
| `cfg.loyalty.redemption_requires_photo` | bool | true | — | G | P | B | both | C1 |
| `cfg.loyalty.redemption_roles` | list<role> | [SR, AMO] | subset of roles | G | P | B | both | C1 |
| `cfg.loyalty.negative_balance_policy` | enum(accept_and_flag, reject) | accept_and_flag | — | G | P | B | srv | C2 |
| `cfg.astha.program_active` | bool | true | — | G W | P | B | both | C2 |
| `cfg.astha.quarter_start_month` | int | 1 | 1..12 | G | P | S, FD | both | C3 |
| `cfg.astha.target_entry_roles` | list<role> | [ADMIN, TSO] | subset of roles | G | P | S | srv | C2 |
| `cfg.astha.gift_choice_roles` | list<role> | [TSO] | subset of roles | G | P | S | srv | C1 |
| `cfg.astha.gift_choice_lock` | enum(none, on_sr_photo) | on_sr_photo | — | G | P | S | srv | C1 |
| `cfg.astha.one_photo_per_outlet` | bool | true | — | G | P | B | both | C1 |
| `cfg.superstar.program_active` | bool | false | — | G W | P | B | both | C2 |
| `cfg.superstar.criteria_met_rule` | json | {std_pct 100, memo_pct 100} | 0..200 each | G W | P | S | srv | C2 |
| `cfg.content.max_item_mb` | int | 8 | 1..20 | G | O | B | srv | C2 |
| `cfg.content.download_network_policy` | enum(wifi_only, wifi_preferred, any) | wifi_only | — | G W | O | B | dev | C2 |
| `cfg.flag.loyalty_ui` | bool | true | — | G ROLE W D T Z U DEV | R | B | both | C2 |
| `cfg.flag.astha_ui` | bool | true | — | G ROLE W D T Z U DEV | R | B | both | C2 |
| `cfg.flag.superstar_ui` | bool | false | — | G ROLE W D T Z U DEV | R | B | both | C2 |
| `cfg.flag.print_enabled` | bool | true | — | G ROLE W D T Z U DEV | R | B | both | C2 |
| `cfg.flag.credit_ui` | bool | true | — | G ROLE W D T Z U DEV | R | B | both | C2 |
| **Field app details** (backlog coverage) |||||||||
| `cfg.app.home_tiles` | json (role → tile ids) | per role; Loyalty Point and Photo Capture only for accounts with programme outlets | must keep Attendance, Sale, Memo, Sales Submit | G ROLE U | O | B | dev | C1 |
| `cfg.app.activity_log_sample_pct` | pct | 10 | 0..100 | G ROLE | O | B | dev | C1 |
| `cfg.app.location_notice_required` (**new**) | bool | true | — | G | S | B | dev | C2 |
| `cfg.sale.sort_by_distance` | bool | false | — | G ROLE | F | B | dev | C0 |
| `cfg.sale.suggested_qty_enabled` | bool | false | — | G W T | P | B | both | C1 |
| `cfg.stock.resave_guard_window_min` | int | 5 | 1..60 | G | O | B | both | C1 |
| `cfg.visit.closed_streak_task` | int | 3 | 0..10 (0 = off) | G W | F | B | srv | C1 |
| `cfg.calendar.weekend_days` | list<int> (ISO weekday) | [5] (Friday) | 0..3 days | G W D | F | S, FD | both | C3 |
| `cfg.day.take_action_after` | time | 17:00 | 12:00..23:00 | G W | F | B | srv | C1 |
| `cfg.tso.periphery_radius_options_m` | list<int> | [50, 100, 300] | 1..6 values, 10..5000 | G W | F | B | both | C0 |
| `cfg.tso.periphery_max_markers` | int | 300 | 50..2000 | G | O | B | both | C1 |
| `cfg.support.max_upload_mb` | int | 20 | 5..100 | G | O | B | dev | C1 |
| `cfg.support.pda_upload_wifi_only` | bool | true | — | G | O | B | dev | C1 |
| **Back office** (s12.6) |||||||||
| `cfg.web.entry_backdate_days` | int | 0 (today only) | 0..31 | G Z | F | B | srv | C2 |
| `cfg.web.entry_classes` | list<sub_channel> | [GT] | subset of sub-channels | G Z | F | B | srv | C1 |
| `cfg.web.entry_validate_calls_le_target` | bool | true | — | G | F | B | srv | C1 |
| `cfg.web.entry_app_overlap_policy` | enum(exclusive_flag, replace, add) | exclusive_flag | — | G | F | B | srv | C2 |
| `cfg.web.entry_unlock_roles` | list<role> | [SUPPORT, ADMIN] | subset of roles | G | S | B | srv | C2 |
| `cfg.web.entry_unlock_max_days` | int | 7 | 1..31 | G | S | B | srv | C2 |
| `cfg.web.entry_unlock_ttl_h` | int | 24 | 1..168 | G | S | B | srv | C2 |
| `cfg.web.menu_by_role` | json (role → menu → actions) | the seed matrix of `docs/19` s5.3 mapped to the roles of s8.5 | menu ids from the page registry | ROLE | S | B | srv | C3 |
| `cfg.entry.paper_backfill_window_days` | int | 7 | 1..30 | G | F | B | srv | C2 |
| `cfg.target.approval_levels` | json | [{level 1, role WM}] | 1..5 levels | G W | P | S | srv | C3 |
| `cfg.target.lock_after_month_start` | bool | true | — | G | S | S | srv | C3 |
| `cfg.target.template_version` | int | 1 | 1..999 | G | P | S | srv | C1 |
| `cfg.target.upload_max_rows` | int | 12000 | 100..50000 | G | P | S | srv | C1 |
| `cfg.price.max_change_pct` | pct | 15 | 1..100 | G | FIN | B | srv | C2 |
| `cfg.geo.density_neighbour_radii_m` | list<int> | [25, 50, 100, 150, 300] | 1..8 values, 10..1000 | G | O | B | srv | C1 |
| `cfg.geo.calibration_min_visits` | int | 500 | 100..5000 | G | F | B | srv | C1 |
| `cfg.sys.break_glass_mode` | enum(restore_or_restrict_only, restore_only, off) | restore_or_restrict_only | — | G | S | B | srv | C3 |
| `cfg.sys.break_glass_max_h` | int | 4 | 1..8 | G | S | B | srv | C3 |

Keys of `docs/19` not listed here keep their `docs/19` definition and are seeded too, but no Phase 1 code may depend on them without adding them to this table first (a PR to this page).

---

## 10. Device-policy contract

### 10.1 Model

Every field phone is factory-reset and enrolled as **device owner** of the field app of its role (`docs/23` s4). Each app embeds the `android:dpc` library; the admin component is `com.aktcl.aron.<flavour>/com.aktcl.aron.dpc.AronDeviceAdminReceiver` (D24-09). A phone serves one flavour; moving a phone to another role is a factory reset and re-enrolment. The DPC also declares the Android 12+ provisioning activities for `android.app.action.GET_PROVISIONING_MODE` (answers `PROVISIONING_MODE_FULLY_MANAGED_DEVICE`) and `android.app.action.ADMIN_POLICY_COMPLIANCE`, both protected by `BIND_DEVICE_ADMIN`.

### 10.2 Policy JSON

The server renders a `DevicePolicy` from the resolved `cfg.device.*` and `cfg.geo.*` keys of the device and its user; `policy_version` is the `config_version` at render. The phone fetches it with `GET /v1/devices/me/policy` (`ETag` = `policy_version`) at enrolment, when a config delta has `policy_changed: true`, and at each login. Example (prod):

```json
{
  "policy_version": 4812,
  "lockdown_level": "prod",
  "generated_at": "2026-10-05T02:10:00.000Z",
  "user_restrictions": {
    "no_debugging_features": true, "no_install_unknown_sources": true, "no_install_apps": true,
    "no_factory_reset": true, "no_safe_boot": true, "no_add_user": true,
    "no_config_date_time": true, "no_usb_file_transfer": true, "no_config_location": true
  },
  "global_settings": { "auto_time_required": true, "adb_enabled": false, "location_mode_high_accuracy": true },
  "self_protection": { "uninstall_blocked": true, "user_control_disabled": true, "battery_optimisation_exempt": true },
  "permission_grants": [
    { "permission": "android.permission.ACCESS_FINE_LOCATION", "state": "granted", "min_api": 26 },
    { "permission": "android.permission.ACCESS_COARSE_LOCATION", "state": "granted", "min_api": 26 },
    { "permission": "android.permission.CAMERA", "state": "granted", "min_api": 26 },
    { "permission": "android.permission.BLUETOOTH_CONNECT", "state": "granted", "min_api": 31 },
    { "permission": "android.permission.BLUETOOTH_SCAN", "state": "granted", "min_api": 31 },
    { "permission": "android.permission.POST_NOTIFICATIONS", "state": "granted", "min_api": 33 },
    { "permission": "android.permission.RECORD_AUDIO", "state": "denied", "min_api": 26 }
  ],
  "app_control": {
    "mode": "blocklist",
    "blocked_packages": ["com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.zhiliaoapp.musically",
                          "com.ss.android.ugc.trill", "com.google.android.youtube", "com.snapchat.android",
                          "com.dts.freefireth", "com.tencent.ig"],
    "allowed_packages": [],
    "always_allowed_packages": ["com.whatsapp", "com.facebook.orca", "com.google.android.dialer", "com.samsung.android.dialer",
                                "com.google.android.apps.messaging", "com.samsung.android.messaging",
                                "com.google.android.apps.maps", "com.android.settings", "com.sec.android.app.camera",
                                "com.google.android.inputmethod.latin", "com.sec.android.app.launcher"]
  },
  "schedule": { "enabled": true, "starts_on": "check_in", "ends_on": "check_out", "hard_end_time": "20:00", "working_days_only": true },
  "location": { "require_precise": true, "require_location_on": true, "breadcrumbs_enabled": false, "breadcrumb_interval_min": 30 },
  "status_report": { "on_events": ["enrolment", "policy_applied", "check_in", "check_out", "boot", "integrity_change", "app_update"], "min_interval_min": 60 },
  "integrity": { "play_integrity_required": true, "refresh_h": 24, "key_attestation_required": true }
}
```

| Item | `dev` lockdown | `prod` lockdown |
|---|---|---|
| `no_debugging_features`, `adb_enabled` | false, true (test phones keep USB debugging) | true, false |
| `no_install_unknown_sources`, `no_install_apps` | false, false | true, true (the DPC lifts `no_install_apps` only while installing its own update, D24-36) |
| `no_factory_reset`, `no_safe_boot`, `no_add_user` | false | true |
| `no_config_date_time`, `auto_time_required` | false, true | true, true |
| `uninstall_blocked`, `user_control_disabled` | false | true |
| Permission pinning | granted, not pinned | granted and pinned (`setPermissionGrantState`) |
| App blocking schedule | as configured (to test it) | as configured |

In production `integrity.play_integrity_required` is true (seeded `cfg.device.require_integrity`, D24-18) and covers the device verdict only. `ACCESS_BACKGROUND_LOCATION` is granted only while `cfg.geo.breadcrumbs_enabled` is true. `RECORD_AUDIO` is denied (the app never asks for the microphone).

### 10.3 Status report

`DeviceStatusReport` (`device_owner`, `lockdown_level_applied`, `policy_version_applied`, `policy_apply_errors`, `restrictions_applied`, `blocking_active`, `suspended_packages`, permission states, battery-optimisation state, location state and mode, developer options, ADB, auto time, time zone, `mock_location_apps`, Play Services version, `play_integrity` evidence, pending rows and media, last sync, battery, storage, printer) is sent on the events in `status_report.on_events` and at most every `min_interval_min` otherwise: online with `POST /v1/devices/me/status` (answer `DeviceStatusAck`: trust, current policy version, directives), offline as a `device_status` record. The server compares it with the policy it rendered and raises `DEVICE_POLICY_DRIFT`, `DEVICE_NOT_OWNER`, `DEVICE_DEBUG_ENABLED` or `DEVICE_MOCK_APP_PRESENT` (s11.4).

### 10.4 Enrolment

1. An admin or support user creates an enrolment token in the portal (`POST /v1/admin/enrolment-tokens`: `flavour`, `lockdown_level`, `max_uses` 1 to 500, `expires_in_h` 1 to 168, optional `zone_id`, `release_id`, Wi-Fi). The token is 256 bits, base64url, shown once (D24-34).
2. The portal shows the **provisioning QR** (`ProvisioningQrPayload`): `android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME` (`com.aktcl.aron.<flavour>/com.aktcl.aron.dpc.AronDeviceAdminReceiver`), `…_PACKAGE_DOWNLOAD_LOCATION` (HTTPS URL of the release APK behind Front Door), `…_SIGNATURE_CHECKSUM` (URL-safe base64 SHA-256 of the APK signing certificate), `…_LEAVE_ALL_SYSTEM_APPS_ENABLED` (true), optional `…_SKIP_ENCRYPTION` and `…_WIFI_SSID`, `…_WIFI_SECURITY_TYPE`, `…_WIFI_PASSWORD`, and `…_ADMIN_EXTRAS_BUNDLE` with `aron.enrolment_token`, `aron.api_base_url`, `aron.env`, `aron.flavour`, `aron.lockdown_level`, `aron.zone_code`.
3. On the factory-reset phone: tap the welcome screen six times, scan the QR; Android downloads and verifies the APK and makes the app device owner. The app reads the extras, creates the device key with attestation challenge `SHA-256(enrolment_token)`, mints `device_uuid`, and calls `POST /v1/devices/enrol` (token, `device_uuid`, package, version, signing-certificate SHA-256, `device_owner`, public key JWK, attestation chain, device info, first status).
4. The server checks the token (valid, not expired or exhausted: `ERR_ENROLMENT_TOKEN_*`), the attestation (`ERR_ENROLMENT_ATTESTATION_FAILED`) and the package, stores the device (`enrolled`) and returns `EnrolDeviceResponse` with the policy, which the DPC applies before showing the login screen.
5. Dev path (test phones with USB debugging): `adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver`, then the app asks for the enrolment token by typing or scanning.

### 10.5 Offline application

The policy is stored in `aron-device.db` and applied on receipt, on boot and on app start; nothing needs the network after that. The **blocking schedule** runs on the phone on trusted time: when a check-in `attendance_event` commits, the DPC suspends the packages of `app_control` (`setPackagesSuspended`; in allowlist mode every launchable non-system package not allowed); a check-out commit or `hard_end_time` (20:00) releases them, whichever comes first; on non-working days (calendar in the bundle) nothing is suspended when `working_days_only`. `always_allowed_packages` and the Aron app are never suspended. Each change of blocking state is reported (s10.3). If the app is killed, the suspension stays (it is a system setting) and is re-evaluated at the next start or boot.

### 10.6 Default lists (D24-35)

- Blocked: Facebook (`com.facebook.katana`, `com.facebook.lite`), Instagram (`com.instagram.android`), TikTok (`com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`), YouTube (`com.google.android.youtube`), Snapchat (`com.snapchat.android`), games (`com.dts.freefireth`, `com.tencent.ig`); the list is edited in the portal (`cfg.device.blocked_packages`).
- Always allowed: WhatsApp (`com.whatsapp`), Messenger (`com.facebook.orca`), the dialer, SMS, Google Maps, camera, Settings, the keyboard and the launcher of the three test phones (Samsung and Google packages above; Honor equivalents are added on Day 3 from the test phone's package list).

---

## 11. Geo-integrity contract

### 11.1 The fix

Every geo-relevant record embeds a `GeoFix` taken on demand (never a cached last-known location): `purpose` (`FixPurpose`), `fix_status`, `lat`, `lng`, `accuracy_m`, `altitude_m`, `vertical_accuracy_m`, `speed_mps`, `bearing_deg`, `provider`, `fix_time` (GNSS-derived when the provider is `gps`), `fix_elapsed_realtime_ms`, `fix_age_ms`, `time_to_fix_ms`, `request_priority`, **`is_mock`** (`Location.isMock()` on API 31+, `isFromMockProvider()` below), `reused` (a fix of the same purpose cycle within `cfg.geo.fix_reuse_max_age_s` and 30 m), `refresh_count`, `gnss` (`GnssSummary` from `GnssStatus` and, where supported, `GnssMeasurementsEvent` during the fix window: satellites visible and used, constellations, C/N0 mean, max and standard deviation of used satellites, ephemeris share, raw-measurement count, AGC mean), `radio` (serving and neighbour cells and up to 8 salted Wi-Fi BSSID hashes, only when `cfg.geo.radio_env_enabled`), and `device` (`device_owner`, developer options, ADB, auto time, mock-app present, `integrity_ref` = the `client_uuid` of the latest `device_status` that carried a Play Integrity token).

### 11.2 The on-device verdict

`shared:rules` implements one function used by phone and server: `verdict(fix, outlet, radius_m, max_accuracy_m, policy) → DeviceGeoVerdict`. Order of checks:

| # | Condition | `verdict` | `action` |
|---|---|---|---|
| 1 | `fix_status ≠ ok` | `no_fix` | after up to `cfg.geo.refresh_max` (3) refreshes: `force_sale` (reason from the `force_reason` code list, photo when `cfg.sale.force_requires_photo`) |
| 2 | `is_mock` | `mocked` | `cfg.geo.mock_policy`: `block_sale` → `blocked` (the visit is recorded and closed `abandoned`, the rep sees "mock location detected"); `warn_rep` → `force_sale` with a warning; `silent_flag` → `force_sale`. A mocked fix is never `sale_allowed` |
| 3 | outlet has no usable location (`location_basis` `none` or `placeholder`) | `no_outlet_location` | `cfg.geo.no_location_policy` (`force_sale_required` → `force_sale`; the visit fix may become the outlet's provisional location through an `outlet_change_request` of type `location`) |
| 4 | `accuracy_m > max_accuracy_m` | `accuracy_too_low` | refresh offered; after `refresh_max`: `force_sale` |
| 5 | `distance_m ≤ radius_m` (or `distance_m − accuracy_m ≤ radius_m` when `cfg.geo.accuracy_tolerant`) | `in_range` | `sale_allowed` |
| 6 | otherwise | `out_of_range` | refresh offered; then `force_sale` |

`distance_m` is the haversine distance (s7.6). The phone stores the verdict with `radius_m_used`, `max_accuracy_m_used`, `location_basis` and the outlet coordinates it used. Location off or precise location denied blocks the visit screen until fixed (the DPC pins both on enrolled phones).

### 11.3 Server re-check

On ingest the server recomputes the verdict with the same function, the outlet location and the radius **in force at `captured_at`** (D-431 tolerance, s9.3) and stores `server_verdict` and `server_distance_m` beside the phone's. A sale is never rejected for its geo verdict (the sale happened; the record is evidence); disagreement raises `GEO_DEVICE_SERVER_MISMATCH`, and the dashboards count geo-valid calls from the server verdict.

### 11.4 Risk-signal catalogue

Signals are computed by the worker from fixes, visits and status reports of one business date (and per fix for the instant ones), stored in `app.risk_signal` with the evidence numbers and the `config_version` of the thresholds used. Each signal has a weight; a user-day whose weights sum to at least `cfg.geo.suspicious_score_threshold` (50) is "suspicious" on the dashboards.

| Code | Fires when (defaults) | Threshold keys | Severity (1 to 4) | Weight |
|---|---|---|---|---|
| `GEO_MOCK` | any fix with `is_mock = true` | `cfg.geo.mock_policy` | 4 | 100 |
| `GEO_TELEPORT` | two consecutive fixes of a user-day ≥ 500 m apart with implied speed > 60 km/h | `cfg.geo.teleport_min_distance_m`, `cfg.geo.max_speed_kmh` | 3 | 40 |
| `GEO_ZERO_JITTER` | among ≥ 8 fixes of a user-day, 3 or more pairs taken ≥ 60 s apart differ by < 2 m with identical `accuracy_m` | `cfg.geo.min_fixes_for_jitter`, `cfg.geo.jitter_threshold_m` | 3 | 40 |
| `GEO_PERFECT_ACCURACY` | 3 or more fixes of a user-day report `accuracy_m` < 3 m | `cfg.geo.perfect_accuracy_threshold_m` | 2 | 20 |
| `GEO_SAME_POINT` | more than 3 distinct outlets (with `location_confirmed`) get geo-valid visits from fixes within 10 m of one point in a user-day | `cfg.geo.same_point_outlets_max` | 3 | 30 |
| `GEO_ROUTE_SINGLE_POINT` | ≥ 80 % of a route-day's visit fixes lie within 30 m of their median point, with ≥ 8 visits | `cfg.geo.route_single_point_pct`, `…_radius_m`, `…_min_visits` | 3 | 40 |
| `GEO_STALE_FIX` | `fix_age_ms` > 30 s on a fix not marked `reused` | `cfg.geo.stale_fix_max_age_s` | 2 | 20 |
| `GEO_GNSS_INCONSISTENT` | provider `gps` and (`satellites_used` < 4, or ≥ 6 used with C/N0 standard deviation < 1.0 dB-Hz, or C/N0 mean > 48 dB-Hz) | `cfg.geo.gnss_min_satellites_used`, `cfg.geo.gnss_cn0_stddev_min_dbhz`, `cfg.geo.gnss_cn0_mean_max_dbhz` | 3 | 30 |
| `GEO_GNSS_TIME_SKEW` | GNSS `fix_time` differs from trusted time at the fix by > 60 s | `cfg.geo.gps_time_skew_max_s` | 3 | 30 |
| `GEO_DEVICE_SERVER_MISMATCH` | server verdict ≠ phone verdict, or the distances differ by > 10 m | `cfg.geo.device_server_mismatch_m` | 2 | 40 |
| `GEO_SHORT_VISIT_GAPS` | ≥ 5 visits of a route-day open < 60 s after the previous visit closed | `cfg.geo.short_visit_gap_s`, `cfg.geo.short_visit_gap_count` | 2 | 20 |
| `DEVICE_NOT_OWNER` | a status report or fix says `device_owner = false` on a phone enrolled with lockdown `prod` | — | 4 | 60 |
| `DEVICE_INTEGRITY_FAIL` | Play Integrity lacks `MEETS_DEVICE_INTEGRITY`, or key attestation fails | `cfg.device.require_integrity` | 4 | 30 |
| `DEVICE_DEBUG_ENABLED` | developer options or ADB on, on a `prod` phone | — | 2 | 10 |
| `DEVICE_MOCK_APP_PRESENT` | `mock_location_apps` not empty or `mock_app_present` | — | 3 | 30 |
| `DEVICE_POLICY_DRIFT` | applied policy version older than the current one for > 24 h, or policy apply errors | `cfg.device.policy_drift_max_h` | 2 | 10 |
| `CLOCK_SKEW` | `captured_at` differs from the server-derived instant by > 10 min, or auto time off | `cfg.sync.max_clock_skew_min` | 2 | 10 |
| `GEO_OUT_OF_BOUNDS` | a fix outside the Bangladesh bounding box (lat 20.5 to 26.7, lng 88.0 to 92.7) | — | 3 | 40 |
| `CONFIG_STAMP_REGRESS` | a device stamps a third row with a config version below one it had already received (FS-34) | — | 2 | 20 |

The weights are the default of `cfg.geo.integrity_weight`. Thresholds are read at evaluation time; a change applies to signals computed after it and the evidence records which version was used.

### 11.5 What each person sees

- **SR:** only the mock warning (and the block when `block_sale`), the geofence result on the visit screen ("আপনি দোকান থেকে 140 মিটার দূরে") and force-sale prompts. Never risk scores.
- **AMO app:** the geo state of team visits on the zone list (valid, force, mock) without scores.
- **TSO app and web (TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN):** `GET /v1/risk-signals` in reach (code, severity, score, evidence numbers, map of the fixes against the outlet pin and radius, GNSS summary, device state), review actions `reviewed`, `dismissed`, `confirmed` with a note (`POST /v1/risk-signals/{id}/review`, append-only), the Geo Validation dashboard (geo-valid %, force-sale %, mock count, mismatch count, suspicious user-days) and the `suspicious-location` report.

---

## 12. Data model

### 12.1 Phase 1 tables (PostgreSQL 16, owned by the db lane; names follow `docs/16` where it defines them)

All tables live in schema `app` unless marked `dw`. Every transaction table has `business_date date not null`, `created_at timestamptz not null default now()`, and, when device-originated, `client_uuid uuid not null unique`, `device_id`, `user_id`, `captured_at timestamptz`, `config_version bigint`, `bundle_version text`, `received_at timestamptz`. Large transaction tables (`visit`, `memo`, `memo_line`, `geo_fix`, `ingest_registry`, `domain_event`) are range-partitioned by month on `business_date` (or `received_at` for the registry). Master data is effective-dated (`valid_from date`, `valid_to date null`) or status-based; nothing is hard-deleted.

| Area | Tables |
|---|---|
| Geography and routes | `wing`, `division`, `territory`, `house`, `zone`, `cluster`, `route` (kind, visit kind, visit days), `route_planned` (planned days, effective-dated), `route_assignment` (user, route, kind primary or cover, `valid_from`, `valid_to`; exclusion constraint: one primary per route and date) |
| People and access | `app_user` (username unique case-insensitive, role, status, locale, password hash Argon2id, `scope_version`), `user_scope` (user, node type, node id, effective-dated), `role_grant_map`, `refresh_family`, `refresh_token` (hashed), `mfa_secret`, `device_otp` |
| Devices | `device` (`device_uuid` unique, flavour, state, lockdown level, public key JWK, attestation summary, trust), `device_binding` (user, device, `bind_ordinal`, active), `enrolment_token` (hash only, uses, expiry), `device_status_report`, `device_directive`, `push_token`, `app_release` |
| Products and prices | `product_node` (category, segment, brand, variant), `sku` (base unit, pack factor, report unit), `sku_price` (sku, price type, `price_mtk`, `price_per_qty`, `valid_from`, `valid_to`), `sales_plan` (zone, sku, effective-dated), `offer`, `offer_version` (rule as data, s12.5) |
| Outlets | `outlet` (code, names, owner, contact, location, `location_confirmed`, provisional location, cluster, channel, geo class, price type, kind, status), `outlet_location_history`, `outlet_change_request`, `outlet_request_event` (verify, approve, reject trail) |
| Field transactions | `route_day` (route, date, state timestamps, `submit_cycle`, mismatch flag), `route_day_event` (`day_open`, `day_submit`), `supervisor_day`, `attendance_event`, `stock_movement` (append-only ledger, s12.5), `visit` (with close columns and both verdicts), `visit_skip`, `geo_fix` (every embedded fix, keyed by the record that carried it), `memo` (status active, voided, superseded), `memo_line`, `memo_discount` (replaces `docs/16` `memo_offer`; kinds offer, drp, free_goods), `qc_entry` and `qc_entry_line` (from `qc_line` records; one `qc_entry` per visit), `print_event`, `memo_void`, `due_collection`, `survey_response`, `distribution_check`, `distribution_check_line`, `call_assessment`, `call_assessment_answer`, `task`, `task_event`, `visit_plan`, `visit_plan_outlet`, `leave_application`, `feedback`, `day_exception`, `media`, `geo_breadcrumb`, `config_ack`, `final_submit`, `submit_void_event`, `route_day_void_barrier` |
| Sync | `ingest_registry` (s3.3), `sync_batch` (stored responses, 48 h), `sync_rejected`, `sync_quarantine` (with resolution), `server_generation` |
| Config | `cfg_key`, `cfg_value`, `cfg_change`, `cfg_version`, `cfg_ack` (s9.1), `calendar_holiday`, `code_list`, `code_list_item` (business code lists keyed by `CodeListKey`: `channel`, `sub_channel`, `geo_class`, `force_reason`, `edit_reason`, `void_reason`, `visit_outcome`, `skip_reason`, `day_exception_reason`, `stock_variance_reason`, `task_type`, `leave_type`, `feedback_category`, `qc_fault_type`, `payment_mode`, `outlet_close_reason`, `submit_void_reason`; codes are immutable and retired by `valid_to`) |
| Risk, audit, events | `risk_signal`, `risk_signal_review`, `audit_log` (append-only, hash-chained), `domain_event` (s6.3) |
| Targets | `target_set`, `target_revision` (status `pending_approval`, `approved`, `rejected`, `superseded`; decisions per level of `cfg.target.approval_levels`), `target` (month, scope route or zone, sku or brand, quantity or value, never negative), `supervisor_target` (AMO call targets by user and month) |
| Programmes and loyalty | `programme`, `programme_enrolment`, `gift`, `gift_assignment`, `gift_photo`, `redemption`, `redemption_line`, `loyalty_ledger` (server-derived, idempotent on `(source_type, source_id)`), `astha_target` |
| Content and telemetry | `survey`, `survey_question`, `rubric`, `content_item`, `content_view`, `tutorial`, `print_template`, `activity_log`, `app_error`, `sale_abort`, `user_consent`, `price_compliance_check` |
| Back office | `web_entry_route_day`, `web_entry_line`, `web_entry_outlet_sku`, `qc_summary_entry` (market and warehouse, source web), `entry_unlock`, `dues_adjustment`, `price_batch`, `tracking_action`, `report_export_log`, `client_error` |
| Aggregates (`dw`) | `dw.agg_daily_route`, `dw.agg_daily_route_sku`, `dw.agg_daily_zone`, `dw.agg_hourly_zone`, `dw.agg_daily_outlet`, `dw.fact_visit`, `dw.fact_memo`, `dw.fact_geo_fix`, `dw.fact_device_day`, `dw.dim_date`, `dw.dim_geo`, `dw.dim_product`, `dw.dim_outlet` |

The db lane writes these as Flyway migrations `db/migrations/V0001__<name>.sql` onward (four-digit, lower snake case, checked by `MigrationNamingTest`; D24-52). Forward-only: a shipped migration is never edited.

### 12.2 Outlet change requests

`OutletRequestType` and the `proposed` members each type requires: `new` (name, owner_name, contact_number, address, cluster_id, sub_channel_id, lat, lng; `outlet_id` null; at least one photo), `close` (close_reason_code; `outlet_id`), `info` (any subset of name, name_bn, owner_name, contact_number, address, sub_channel_id; `outlet_id`), `cluster` (cluster_id; `outlet_id`), `location` (lat, lng from the request's own fix; `outlet_id`; photo required), `route_add` (an existing outlet missing from the requester's route or cluster: `outlet_id` and `cluster_id`; verified by the AMO). Life cycle (`OutletRequestStatus`): `pending` → `verified` (AMO, in the app or on the web) → `approved` or `rejected` (DMO or admin on the web; approver ≠ requester ≠ verifier) ; `lapsed` after 30 days without decision; `discarded` by an admin. An approved `new` request creates the outlet with a code; `location` moves the pin (history kept) and sets `location_confirmed`; a move above `cfg.sec.fraud.location_move_alert_m` (300 m) also needs the TSO's approval.

### 12.3 Report registry (Phase 1)

Every report is one `POST /v1/reports/{report_key}/query` with a `ReportQuery` (dates, geo selectors that narrow the reach, product selectors, format `json`, `xlsx`, `pdf` or `print`). `print` returns a server-rendered HTML print view; `pdf` is always an export job; `xlsx` is returned inline up to `cfg.ops.report_sync_max_rows` (10,000) rows, otherwise as an export job (`202`, then `GET /v1/report-exports/{export_id}`), refused above `cfg.ops.report_export_max_rows` (200,000, `413 ERR_REPORT_TOO_LARGE`). All read `dw`.

| `report_key` | Content |
|---|---|
| `std-memo` | sales (STD) by memo: memo number, outlet, SKU lines, discounts, net |
| `sr-efficiency` | per SR: target outlets, visited, successful calls, strike rate, memos, net, hours in field |
| `route-std` | sales to date by route and SKU against target |
| `route-memo` | memo counts and value by route and day |
| `route-bsr-cpr` | CPR and BSR by route and brand |
| `data-entry-log` | bundle downloads and uploads per route-day with times |
| `final-submit-log` | Final Submit events per zone-day, including late rows after it |
| `final-submit-status` | zone-days final-submitted or not, with route states |
| `by-outlet` | sales by outlet for a range |
| `by-outlet-by-day` | sales by outlet and day |
| `gigo` | attendance check-in and check-out (GIGO) with geo verdicts |
| `attendance` | attendance summary by user and day |
| `discount` | discount lines by kind, offer and SKU |
| `online-offline` | records captured offline versus online, with sync delay |
| `tso-top-sheet` | TSO top sheet per zone-day |
| `daily-tracking` | Daily Tracking buckets by route (100, 90-100, 80-90, below 80, exception, not logged in) |
| `sr-outlets` | outlets of an SR's routes with last visit and dues |
| `leaderboard` | ranking of SRs, AMOs or zones by achievement |
| `suspicious-location` | risk signals and suspicious user-days (s11.4) |
| `sync-health` | per device and route-day: pending rows, last contact, rejects, quarantine, mismatch |
| `ds-rrs` | DS-RRS sheet (distributor stock and retail sales by SKU) |
| `dss` | DSS sheet (daily sales summary per zone) |
| `amo-call` | AMO control and joint calls with assessments |
| `dues-ageing` | outstanding dues by outlet and age bucket |
| `settlement` | QC settlements by fault type and SKU |
| `qc-report` | QC entries by visit, SKU and fault |
| `stock-summary` | issued, sold, returned and closing stock per SR and SKU |
| `memo-number-gaps` | gaps and voids in memo number series per user and day |
| `sales-summary` | AMO Sales Summary Up To Now: route cards (CPR, memos) and a brand table per route |
| `task-planner` | tasks assigned and resolved by scope and date |
| `by-route-geo-capture` | outlets per route with and without coordinates, placeholders included |
| `free-sample` | free-sample quantities by SKU and route (`line_kind` free_sample) |
| `target-allocation` | monthly targets by route and zone |
| `route-qc` | QC by route for a date range and QC type |
| `geofence-calibration` | distance histogram per geo class and territory with force-sale share |
| `astha` | Astha target, achievement, remaining and percent per outlet and brand for year, quarter and months |
| `astha-gift-choice` | Astha gift choices and photo status, filtered by gift status |
| `campaign-gift-redemption` | redemptions, points and the photo-verified flag |
| `diamond-league` | outlet points statement: earned, spent, balance and expiring for a date range |
| `superstar-campaign` | per outlet category, slab, base target and criteria-met flag |
| `retailer-list` | Browse Retailer list export (PII columns by role) |
| `sku-list` | Browse SKU list export with the price types the role may see (three decimals) |

Every export (xlsx, pdf, print) is listed in the export log `GET /v1/report-exports` with its filters, row count and PII flag. Column sets whose sample workbook is not yet seen carry `columns_known: false` in `GET /v1/reports` and are fixed when the sponsor provides the sample.

### 12.4 KPI definitions (dashboards and app home)

| KPI | Definition |
|---|---|
| `target_routes` | route-days planned for the date in reach, minus approved day exceptions |
| `logged_in_routes`, `login_pct` | route-days in state `logged_in` or later; ÷ `target_routes` × 100 |
| `sales_submitted_routes`, `submit_pct_of_logged_in` | route-days `sales_submitted` or `final_submitted`; ÷ `logged_in_routes` × 100 |
| `day_completion_pct` | `sales_submitted_routes` ÷ `target_routes` × 100 |
| `target_outlets` | outlets of planned routes, frozen at each route's first bundle of the day |
| `visited_outlets` | distinct outlets with a visit not closed `abandoned`, SR calls only (AMO calls excluded) |
| `successful_calls` | distinct outlets with an active memo with `line_count > 0` |
| `strike_rate_pct` (CPR) | `successful_calls` ÷ `target_outlets` × 100 |
| BSR (per brand) | active memos containing the brand ÷ active memos × 100 |
| `active_memo_count`, `gross_mtk`, `net_mtk` | memos with status active and `line_count > 0`; sums of their stored values |
| `geo_valid_pct` | visits with server verdict `in_range` ÷ visits × 100 |
| `force_sale_pct` | visits with action `force_sale` ÷ visits × 100 |
| `mock_visits`, `suspicious_visits` | visits with a mocked fix; visits of suspicious user-days (s11.4) |
| `zones_final_submitted`, `final_submit_pct` | zone-days `final_submitted` ÷ zones with target routes × 100 |
| Achievement | net (or quantity) month to date ÷ monthly target × 100 on the till-date basis of the surface (`docs/10`) |

Percentages: 0 to 100 with 2 decimals; `null` when the denominator is 0 (shown as a dash).

### 12.5 Phase 2 extension points (built now, used later)

1. **Offers as data:** `offer` and `offer_version` hold typed rule rows (types in this build: `pct_discount`, `amount_per_unit`, `free_qty`, `drp_slide`, D24-38); a memo records the `offer_version_ids` it used. The Phase 2 discount portal adds rule tables without changing memos.
2. **Targets as data:** `target_set` → `target_revision` → `target` rows (month, scope, SKU or brand, measure); the Phase 2 target engine writes new revisions.
3. **Ledgers:** `stock_movement` is an append-only ledger (issue, return, adjustment, damaged, short; every row signed quantity in base units); an `indent` ledger table is reserved for the Phase 2 indent portal (created empty in Phase 1 with the same shape).
4. **Domain-event outbox:** every write emits `domain_event` rows (`memo.created`, `memo.voided`, `visit.closed`, `route_day.state_changed`, `outlet.changed`, `stock.moved`, `target.revised`); the mother dashboard and the indent portal subscribe by reading in id order.
5. **External references:** master data and transaction tables carry `external_ref varchar(64) null` (unique per table when set) for ERP and Apsis cross-walks; the Apsis import (not in this build) fills `stg.id_crosswalk`.

---

### 12.6 Back-office entry, approvals and administration

1. **Web Entry (route-day aggregate).** `GET`/`POST /v1/web-entry/route-day`: one entry per route-day (Issue, Return, Memos per SKU and Successful Calls at most the target outlets), browser `client_uuid` idempotency, a re-save replaces with an audit row, web rows and app rows never added (`cfg.web.entry_app_overlap_policy`), window `cfg.web.entry_backdate_days` unless an **entry unlock** (`/v1/admin/entry-unlocks`; `cfg.web.entry_unlock_roles`, `cfg.web.entry_unlock_max_days`, `cfg.web.entry_unlock_ttl_h`) is active (`409 ERR_ENTRY_WINDOW_CLOSED` otherwise), refused after Final Submit. Visits created by web entry carry `visit_kind` = `web_entry`.
2. **Astha Web Entry.** `GET`/`POST /v1/web-entry/outlet-sku`: Astha-channel outlets by SKU, explicit Save, overlap with app memos flagged, never added.
3. **QC Entry.** `POST /v1/web-entry/qc`: market (route) or warehouse (zone) QC by SKU and fault type, stored as a separate web source, never added to app QC; warehouse entries need a reason.
4. **Paper backfill.** `POST /v1/admin/data-entry`: a dead-phone day keyed from the printed memo within `cfg.entry.paper_backfill_window_days`, written through the ingest path with `source = manual`; a memo number already stored is stored once (`duplicate_memo_no`).
5. **Targets.** `PUT /v1/admin/targets` and `POST /v1/admin/targets/upload` (all-or-nothing, error sheet) create a revision `pending_approval`; `GET /v1/admin/target-revisions` is the approval queue and `POST .../{revision_id}/decision` approves per level of `cfg.target.approval_levels` (maker ≠ approver); the last approval makes it live and supersedes the older revision. AMO call targets use `/v1/admin/supervisor-targets`.
6. **Prices.** `POST /v1/admin/prices/preview` is mandatory before a publish; a batch moving any price by more than `cfg.price.max_change_pct` is stored `pending_approval` until a second person decides (`POST /v1/admin/prices/batches/{batch_uuid}/decision`).
7. **Dues adjustments.** `/v1/admin/dues-adjustments`: correction or write-off with a reason; a different person approves; approval adds a dues-ledger row and changes the outlet balance.
8. **Permissions and flags.** The role × menu × action matrix is the config key `cfg.web.menu_by_role`: `GET /v1/admin/permissions` shows it with the admin roster and `PUT /v1/admin/permissions/roles/{role}` creates a C3 change request (never an immediate grant); the server enforces permissions per action whatever the menu shows. Feature flags are the `cfg.flag.*` keys edited in the config console; a flag never changes the shape of captured data.
9. **Admin content.** Programmes, enrolments and gifts, surveys, rubrics, AV and KV content, tutorials and print templates are versioned admin data under `/v1/admin/*` (tag `admin-content`); files upload through `POST /v1/admin/assets` (write-only SAS) and reach phones in the next bundle or delta.
10. **Other operations.** Daily Tracking "take action" (`POST /v1/dashboards/daily-tracking/actions`, after `cfg.day.take_action_after`), the feedback inbox status (`PATCH /v1/feedback/{feedback_uuid}`), the TSO retailer radius map (`GET /v1/outlets/nearby`), bulk outlet kind (`POST /v1/admin/outlets/outlet-kind`), the replace-device wizard (`POST /v1/admin/devices/{device_id}/replace`), config what-if, blast radius, density, calibration, version detail and pending reach (`/v1/admin/config/*`), PDA to Support (`POST /v1/support/pda-upload`), web error reports (`POST /v1/client-errors`) and the multipart media fallback (`POST /v1/media/upload`).

## 13. Quality, CI and delivery

### 13.1 Observability and SLOs

Every API request logs one structured line (JSON: request id, user id, device id, route, status, duration, problem code) and emits OpenTelemetry metrics to Application Insights (Java agent 3.7.10). The phone sends its own counters in `telemetry` (s4.4) and `device_status`.

| SLO | Target | Measured by |
|---|---|---|
| Sync acknowledgement after capture, phone online and foreground | p95 ≤ 60 s | `captured_at` → `received_at` |
| `POST /v1/sync/batch` latency (200 records) | p95 ≤ 1.5 s, p99 ≤ 4 s | App Insights |
| `GET /v1/sync/bundle` | p95 ≤ 2 s (304 ≤ 300 ms) | App Insights |
| Dashboards and app home | p95 ≤ 1 s | App Insights |
| Aggregate lag (event to `dw`) | p95 ≤ 60 s | worker metric |
| Config reach | 95 % of selling phones apply a change within 15 min | `cfg_ack` |
| `/v1/sync/*` availability | 99.9 % per month | Front Door and App Insights |
| Data integrity | zero lost or duplicated rows; device count = server count | reconciliation (s4.12), Day 6 drills |

Alerts (Azure Monitor): batch 5xx > 1 % over 5 min; p95 batch > 3 s over 10 min; worker lag > 5 min; any `payload_conflict` or `memo_no_duplicate`; devices holding pending rows longer than `cfg.sla.pending_rows_alert_h` (4 h) listed for the TSO and helpdesk.

### 13.2 CI gates per lane (`.github/workflows/ci.yml`, every push and PR)

| Lane | Gate (all must pass) |
|---|---|
| shared | Redocly lint of `contract/openapi.yaml` with `contract/redocly.yaml` (0 errors, 0 warnings); `:shared:contract:build` (drift tests: every enum in `shared/contract` equals the YAML; `SpecCrossCheckTest`: this page agrees with the YAML; `BacklogCoverageTest`: every in-scope `docs/25` row's needs exist); `:shared:rules:build` (money, totals, distance, business-date fixtures) |
| db | `:db:build` (naming test); migrations apply to an empty PostgreSQL 16 and to the previous release's schema |
| backend | `:backend:*:build` against the `postgres:16` service (`ARON_TEST_PG_URL`); sync property and fuzz tests (duplicate, reordered, partial and replayed batches never change the stored count) |
| android-* | `assembleDebug` of the three apps; `testDebugUnitTest` (Robolectric Room and outbox tests); Android lint `abortOnError`; release builds check the APK size budget |
| web-* | `npm ci`, `npm run gen:contract` produces no diff, `tsc --noEmit`, `next lint`, unit tests, `next build` |
| infra | Bicep build and what-if against `rg-aron-dev`; actionlint |
| qa | contract tests against the API started in CI; nightly load profile against `rg-aron-dev` |

### 13.3 Definition of done (per backlog row)

A row of `docs/25` is done when: its acceptance test passes and is automated (or, for device-only behaviour, recorded with a screen capture and the phone model); it works offline where the spec says so and survives a kill and relaunch mid-operation; sync is idempotent and reconciles; scope is enforced on the server and a test proves a user cannot read outside the assignment; Bangla and English strings are in the resource files; no new continuous sensor and no uncompressed media; the CI gates of the lane pass; the checker (s13.5) signed it off; `DECISIONS.md` has a line for every new assumption.

### 13.4 Branches and merging

- `main` is protected: merge by PR only, CI green, one approving review from the checker, linear history (squash).
- Lane branches: `lane/<lane-id>/<backlog-id>-<slug>` (for example `lane/backend/F-SYS-006-batch-ingest`). Small PRs (≤ 400 changed lines excluding generated files), merged at least daily.
- A contract PR (`contract/`, `shared/contract`) merges before the PRs that depend on it; it must keep the drift tests and Redocly green and regenerate `web/src/contract/openapi.d.ts`.
- Commit messages: imperative subject ≤ 72 characters, body with the backlog id.

### 13.5 Builder, then checker

Every task is built by one agent and checked by a different agent (`docs/23` s2 rule 2). The checker: checks out the branch, runs the lane's CI commands locally, runs the acceptance test of the backlog row, reviews the diff against this spec (ownership, contract, idempotency, scope, offline, strings, budgets), and either approves with the command outputs pasted into the PR or requests changes. The builder never approves its own PR.

### 13.6 Secrets

No key, password, token, keystore or service-account file is ever committed. Values come from GitHub Actions secrets at build time and from Azure Key Vault at run time.

| GitHub secret | Used by | How |
|---|---|---|
| `MAPS_ANDROID_KEY` | AMO and TSO app builds | env var read by the root Gradle build into the manifest placeholder `mapsApiKey` (empty when absent; maps then show a grey tile in debug) |
| `MAPS_WEB_KEY` | web build | `NEXT_PUBLIC_MAPS_WEB_KEY` at `next build` (referrer-restricted key) |
| `GOOGLE_SERVICES_JSON` | the three app builds | written to `android/app-{sr,amo,tso}/google-services.json` in CI before the build; the `google-services` plugin is applied only when the file exists |
| `FCM_SERVICE_ACCOUNT_JSON` | backend deploy | copied into Key Vault secret `aron-fcm-service-account`; the API reads it through a Key Vault reference |
| `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEYSTORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD` | release APK builds (Day 7) | decoded to a temporary keystore in the runner; the certificate digest is pinned in the provisioning QR (D24-49) |
| `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` (+ variables `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`) | deploy workflows | OpenID Connect login (no client secret), created by `infra/bootstrap-azure.ps1` |

Key Vault holds `aron-jwt-signing-key`, `aron-fcm-service-account`, database credentials and the blob account settings. Debug builds use the debug keystore of the runner; release builds never do.

### 13.7 How a lane proves acceptance

For each backlog row the PR description contains: the row id, the acceptance test text, the exact commands run and their short output (test counts, pass), and for device behaviour the phone model, Android version and a screen recording or photo link. Sync rows add the reconciliation output (device counts and server totals for the test day). The daily 10-minute check of `docs/23` s7 is run on `main` after the day's merges.

---

## 14. Decisions I took myself

Each row is a question the sources left open or answered in conflicting ways, with the decision this spec makes and why. Rows marked **sponsor** should be confirmed by the sponsor; until then the decision stands.

| Id | Question | Decision | Reason |
|---|---|---|---|
| D24-01 | Build layout: convention plugins, `buildSrc` or one root script? | One root Gradle build; shared configuration in the root `build.gradle.kts`; no `buildSrc` or included builds | Fastest configuration with the configuration cache on; one place for 35 modules; lanes never edit build logic (infra owns it) |
| D24-02 | How do phone, server and web share the contract? | `shared:contract` and `shared:rules` are Kotlin Multiplatform with a `jvm()` target only (Android consumes the JVM artifact); the web gets TypeScript types generated from `openapi.yaml` | Kotlin/JS or Wasm for the web adds a toolchain for no gain; the YAML is the cross-language truth |
| D24-03 | Which artefact is the source of truth for the API? | `contract/openapi.yaml`; the Kotlin mirror is hand-written and checked by drift tests on every build | Codegen of Kotlin models from OpenAPI 3.1 (with `unevaluatedProperties`, `oneOf` discriminators) is unreliable on Day 1; drift tests give the same safety |
| D24-04 | jOOQ or a JDBC helper? | JDBI 3 with hand-written SQL | s2.5: no schema codegen in the build, PostgreSQL-specific SQL on the hot paths, fewer cross-lane couplings |
| D24-05 | Testcontainers or embedded PostgreSQL? | Real PostgreSQL 16 via `ARON_TEST_PG_URL` (CI service container, local PG16 on the dev box); Testcontainers fallback | The build container has PG16 and no Docker; CI has Docker; embedded binaries differ from Azure's engine |
| D24-06 | Phone HTTP stack? | OkHttp 5 + kotlinx.serialization; no Retrofit, no Ktor client | Smallest APK, full control of gzip, proofs, edge-response detection and timeouts |
| D24-07 | Mocking and test runners? | MockK only; JUnit 6 (Jupiter) on JVM modules; JUnit 4 + Robolectric 4.17 on Android | One mocking style across lanes; Robolectric needs JUnit 4 runners |
| D24-08 | SDK levels? | compileSdk 37, targetSdk 36, minSdk 26 | AGP 9.4 supports 37; target 36 avoids untested Android 17 behaviour changes until the device lab passes; min 26 per `docs/23` Q4 |
| D24-09 | One DPC app or each field app as device owner? | Each field app embeds `android:dpc` and is device owner of its phone; a role change is a factory reset and re-enrolment | No second APK to install, update and keep in step; the QR names one package |
| D24-10 | Success envelope (`success: true`) as in the old app? | No envelope; 2xx bodies are the resource, errors are RFC 9457; the `X-Aron-Api: 1` header separates API answers from edge pages | Standard, smaller, and still detects captive portals and WAF pages (the reason for D-81) |
| D24-11 | Ack vocabulary | `accepted`, `duplicate`, `rejected` (with `retryable`), `quarantined`; payload conflict → quarantined; parent missing → rejected retryable (parked) | Four states cover every outcome; retryable rejects let the phone keep one rule: resend until final |
| D24-12 | Record-type modelling | `memo_discount` lines instead of a free-form offer table; `qc_line` without a header record; `GeoFix` embedded in the record that needs it; `geo_breadcrumb` standalone; `visit` is rank 0 and `memo` rank 1 of the visit family; an edited memo is a new memo with `supersedes_client_uuid` | Mirrors how the phone commits; discount lines reconcile exactly; fewer record types to sync |
| D24-13 | Config scope levels | global, role, wing, division, territory, geo_class, zone, route, outlet, user, device; house, wave and device-model levels of `docs/19` dropped for Phase 1; geo_class has no parent | Houses carry no rules in the sources; waves belong to cutover (not in this build) |
| D24-14 | One config version per key, per scope or global? | One global monotonic `config_version`; the delta filters by the caller's scope chain | One number in a header is enough for the phone to know it is behind |
| D24-15 | Who resolves the radius? | The server resolves `radius_m` and `max_accuracy_m` per outlet into the bundle | The phone cannot know the full chain offline; one resolution point avoids drift |
| D24-16 | `cfg.geo.mock_policy` default: `warn_rep` (`docs/19`) or refuse (`docs/23`)? | `block_sale`; the blocked attempt is recorded as a visit closed `abandoned` with action `blocked` | `docs/23` s4 promises "the call is refused and flagged"; `docs/23` outranks `docs/19` |
| D24-17 | Enrolment gate in dev | `cfg.device.require_enrolled` true by default, overridden to false in the dev database; an unenrolled phone gets 403 at login in prod; records from unenrolled or failing devices are quarantined, not rejected | Test phones must work before every phone is enrolled; production follows `docs/23` |
| D24-18 | Is Play Integrity mandatory? (**sponsor overrule**, 2026-10-05) | The registry default of `cfg.device.require_integrity` stays false, but the **production seed sets it true** for the device verdict only (`MEETS_DEVICE_INTEGRITY`); `appRecognitionVerdict` is never required (sideloaded APKs are `UNRECOGNIZED_VERSION`); the dev seed keeps false; failing devices stay quarantined, not rejected (as D24-17), and supervisors see `DEVICE_INTEGRITY_FAIL` | The sponsor wants anti-spoofing layered, and every phone will be enrolled |
| D24-19 | JWT signing keys | One ES256 key from Key Vault loaded at startup; JWKS publishes current and next key; delegated signing deferred | Simple and rotatable; no runtime Key Vault dependency per request |
| D24-20 | Redis for rate limits and caches? | No Redis in Phase 1 | Fewer moving parts; limits are per replica (D24-58) and caches are short-lived in-process |
| D24-21 | Roles beyond the brief | Add `TOP` (national read) and `SUPPORT` (helpdesk) | Helpdesk needs OTP and device actions without business edits; top management needs national read |
| D24-22 | App-version gate unit | Integer `versionCode` per flavour (`cfg.release.min_version_code` json); replaces the semver keys of `docs/19` | Android compares version codes; three apps release independently |
| D24-23 | FCM push in the pilot | On (`cfg.ops.push_enabled` true); data messages only nudge pulls, never uploads | `docs/23` Day 3 requires tasks with push |
| D24-24 | Offline-unique memo numbers | `<username>-<yyMMdd>-<seq>` with per-binding blocks of 500 (`bind_ordinal` 0..3) and an overflow range | Parity with the printed format; unique across a user's phones without network |
| D24-25 | Reconciliation granularity | Counts per record type plus money per category and quantity per SKU; the five legacy rows are a view | Exact proof of "device count = server count" while keeping the familiar screen |
| D24-26 | Day state owner | The route-day, with an explicit `submit_pending_rows` state and a 30-minute settle | A submit that overtakes its own rows must not show a short day |
| D24-27 | Number of server images | One image, roles `api`, `worker`, `migrate` | One build, one scan, one version in production |
| D24-28 | Excel exports | Inline up to 10,000 rows, export job above, refused above 200,000 | Keeps API replicas responsive at month end |
| D24-29 | Business-date cutoff | 00:00 Asia/Dhaka | Matches the field day; no evidence of a later cutoff |
| D24-30 | Batch size | Phone ≤ 200 records and 256 KiB raw; server accepts ≤ 500 | Small batches finish on 2G-class links; the server cap is defence in depth |
| D24-31 | Trusted time | Server-time anchors with `elapsedRealtime` and boot count; Dhaka is UTC+6 with no DST | Phones with wrong clocks must not shift business dates; no DST simplifies every rule |
| D24-32 | Shared phones under device owner | Kept: several users per phone, one database and outbox per user | `docs/23` enrols every phone, but phone sharing (D-66) is still how the field works |
| D24-33 | Web authentication | BFF with HttpOnly refresh cookie; TOTP for ADMIN, SUPERADMIN, SUPPORT | No tokens in the browser; admins hold fleet-wide power (`docs/23` Q5) |
| D24-34 | Enrolment token shape | 256-bit, multi-use up to 500 phones, TTL at most 168 h, hash stored, shown once | One QR per enrolment session for a zone; short life limits leaks |
| D24-35 | Default app lists | Block Facebook, Instagram, TikTok, YouTube, Snapchat, Free Fire, PUBG; always allow WhatsApp, Messenger, dialer, SMS, Maps, camera, Settings, keyboard, launcher; hard end 20:00 | `docs/23` Q1 default, made concrete as package names |
| D24-36 | `no_install_apps` in prod | On; the DPC lifts it only around its own self-update | Stops side-loading spoofing tools while keeping updates possible |
| D24-37 | Target approval in this build (revised) | Every target set or upload is a revision that goes live after the approvals of `cfg.target.approval_levels` (default one level, WM); AMO call targets have their own API | The backlog (F-ADM-014, F-WEB-030, F-API-021) requires the approval queue; my earlier approval-free choice is withdrawn |
| D24-38 | Offer rule types | `pct_discount`, `amount_per_unit`, `free_qty`, `drp_slide` (the contract's `Offer.offer_type`) | Covers the promotions seen in the manuals; further types are data added later |
| D24-39 | Which records are signed | Header records only (`visit`, `memo`, `attendance_event`, `stock_movement`, `due_collection`, `memo_void`, `outlet_change_request`, `redemption`, `gift_photo`) | Proves origin of the money, points and attendance facts at low CPU cost |
| D24-40 | GNSS plausibility thresholds | ≥ 4 satellites used; with ≥ 6 used, C/N0 standard deviation ≥ 1.0 dB-Hz; C/N0 mean ≤ 48 dB-Hz | Simulator signals are uniformly strong; real sky views vary |
| D24-41 | Single-point route rule | ≥ 80 % of visit fixes within 30 m, at least 8 visits | Catches a rep "visiting" a whole route from one tea stall without flagging dense markets |
| D24-42 | Problem `type` URIs | `urn:aron:problem:<code>` | Stable, no documentation host needed |
| D24-43 | Pagination | Opaque keyset cursors, `limit` ≤ 500 | Stable under inserts; no offset scans |
| D24-44 | Concurrent admin edits | `PATCH` with `If-Match: "<version>"`, 412 on conflict | Two admins cannot silently overwrite each other |
| D24-45 | "Delete Section Data" | Replaced by an audited data void with tombstones and a route-day barrier, before Final Submit only | Nothing is ever deleted; late uploads cannot resurrect voided rows |
| D24-46 | Breadcrumbs | Off by default; when on, batched every 30 min at balanced power | Battery budget; `docs/23` s4 says optional and off |
| D24-47 | Payload hash | SHA-256 of the RFC 8785 canonical JSON computed by the server | Formatting differences between phone builds never look like conflicts |
| D24-48 | Memo counter overflow | `5000 + bind_ordinal × 1000 + extra` for up to 999 more memos | Never blocks a sale for numbering; keeps numbers unique |
| D24-49 | Signing keys | Release keystore in GitHub secrets; the QR pins its certificate digest | Provisioning verifies the APK signature; the key must be stable |
| D24-50 | Maven Central rate limits in build containers | A machine-local Gradle init script may point to Google's Maven Central mirror; it is never committed | The repo stays standard; containers that hit HTTP 429 can still build |
| D24-51 | Who edits root build files | Infra only; the version catalogue and `settings.gradle.kts` are append-only for other lanes | Prevents version churn across seven parallel lanes |
| D24-52 | Migration naming | Flyway `V<NNNN>__<name>.sql` | Flyway runs inside the app image (`migrate` role); supersedes the dbmate naming of earlier drafts |
| D24-53 | Device status offline | `device_status` is also a sync record type | Status must reach the server from phones that are offline at the event |
| D24-54 | AMO Sales Submit | A supervisor-day submit (`scope: supervisor_day`) | AMOs sell on several routes; their day is not a route-day |
| D24-55 | TSO leave | Applied by a `leave_application` record, decided by the DMO on the web | Matches the TSO manual; DMO is the TSO's manager |
| D24-56 | Content views, redemptions, gifts (**overruled by the sponsor**, 2026-10-05) | Built in this build: record types `content_view`, `redemption`, `redemption_line`, `gift_photo`, bundle members `programmes` and `content`, programme, Astha and loyalty APIs, the loyalty ledger and expiry job, programme reports (s4.14) | The sponsor's rule is full parity; only the new Phase 2 portals are out |
| D24-57 | Upload after logout | An upload-only grant survives logout and password change | Captured sales must always reach the server |
| D24-58 | Rate-limit storage | In-memory per replica | No Redis (D24-20); limits are protective, not billing |
| D24-59 | TSO radius edits | Proposals that an F editor adopts (`cfg.geo.tso_radius_mode` = propose) | Separation of duties on the anti-fraud control |
| D24-60 | Locked account status | 403 `ERR_AUTH_ACCOUNT_LOCKED` with `retry_after_s` | The credentials were right or wrong; the account state is a permission question |
| D24-61 | Certificate pinning | Not in Phase 1 | Front Door rotates managed certificates; a wrong pin would strand the fleet |
| D24-62 | Config value types | `number` added to the value types (GNSS C/N0 thresholds) | dB-Hz thresholds need decimals |
| D24-63 | Printer model | MP-58N (`docs/23`) first, RPP02N kept | The sponsor named MP-58N; both are 58 mm ESC/POS |
| D24-64 | Who owns shared feature modules | The SR lane owns auth, home, attendance, stock, sale, memo, dayclose, outlet and tasks; AMO and TSO extend through their own modules | One owner per folder (`docs/23` rule 3) while reusing the SR screens |
| D24-65 | Lighter and match base units (**sponsor**) | Lighter: piece; match: dozen, until confirmed (`cfg.sale.qty_entry_unit`, C3, future-dated) | MUST-CONFIRM in `docs/19`; changing it later is a config change, not code |
| D24-66 | Report column sets (**sponsor**) | Reports without a sample workbook ship with `columns_known: false` | Avoids inventing column orders the business will reject |
| D24-67 | Names of the added record types | `redemption`, `redemption_line`, `gift_photo`, `price_compliance_check`, `activity_log`, `consent_accept` as in `docs/16`; `sale_abort` and `app_error` from the backlog; DRP empties stay in `memo_discount.basis_qty_base` (no `drp_collection` record) | One name across the documents; DRP empties already reconcile inside the memo |
| D24-68 | Loyalty points expiry (**sponsor**) | `cfg.loyalty.expiry_days` = 7 days after the period end | The only evidence: April points expire on 7 May; a config change if wrong |
| D24-69 | Feature flags | The `cfg.flag.*` config keys, edited in the config console; no separate flag API | One change, audit and reach path for every switch |
| D24-70 | Role × menu × action matrix | The config key `cfg.web.menu_by_role`; edits are C3 change requests through `PUT /v1/admin/permissions/roles/{role}` | A permission grant must be two-person and versioned |
| D24-71 | Report formats | `json`, `xlsx`, `pdf` (always an export job) and `print` (HTML) | The backlog needs print views and PDF downloads (F-API-017, F-API-053, F-WEB-053, F-WEB-061) |
| D24-72 | Export log | `GET /v1/report-exports` lists every export with filters, rows and PII flag | The viewer needs inline exports and jobs in one list |
| D24-73 | Web Entry, Astha Web Entry, QC entry, paper backfill | Online web endpoints with browser `client_uuid`s, not sync records; paper backfill goes through the ingest path with `source = manual` | Office staff key them online; idempotency is the same |
| D24-74 | AMO Exceptions screen offline | `risk_review` record plus the zone's open signals in the AMO bundle | F-AMO-038 requires offline review as idempotent events |
| D24-75 | Price-change rail | A batch above `cfg.price.max_change_pct` waits for a second approver | `docs/19` rail and F-ADM-081 |
| D24-76 | AMO call targets | `/v1/admin/supervisor-targets` (counts per user and month), not product target rows | They are call counts, not product quantities |
| D24-77 | Nearby outlets | `GET /v1/outlets/nearby` limited to Bangladesh coordinates, the configured radii and `cfg.tso.periphery_max_markers` | Protects PII and the geo index |
| D24-78 | Enum additions from the backlog | `VisitOutcome` + `not_reached`, `StockMovementKind` + `qc_return`, `OutletRequestType` + `route_add`, `VisitKind` + `web_entry`, `CodeListKey` + `channel`, `sub_channel`, `geo_class` | F-SR-057, F-SR-053, F-SR-076, F-SYS-078, F-ADM-011 |
| D24-79 | Risk signals from the backlog | `GEO_OUT_OF_BOUNDS`, `CONFIG_STAMP_REGRESS` | F-SYS-062, F-SYS-091 |
| D24-80 | Error reporting | Phones send `app_error` records in the batch; the web posts `POST /v1/client-errors`; no third-party crash SDK | F-SYS-032 with privacy scrubbing and no extra SDK in the APK |
| D24-81 | TSO web powers | A TSO may reset passwords and unlock SR and AMO users of its own zones; TSO radius edits are proposals an editor adopts | F-TSO-023, F-TSO-025 |


---

## Appendix A. Endpoint index (generated from `contract/openapi.yaml`)

Generated by the cross-check script (`--appendix`); 206 operations on 166 paths, grouped by tag in the order of the contract. Request and response schemas, statuses and examples are in the YAML.

| Tag | Method | Path | operationId | Summary |
|---|---|---|---|---|
| health | GET | `/v1/health` | `getHealth` | Liveness and phone connectivity validation. |
| health | HEAD | `/v1/health` | `headHealth` | Connectivity check with no body. |
| health | GET | `/v1/health/ready` | `getReadiness` | Readiness probe for Azure Container Apps (checks the database pool). |
| health | GET | `/v1/config/public` | `getPublicConfig` | Unauthenticated values the login screen needs (minimum versions, banner, helpdesk). |
| auth | POST | `/v1/auth/login` | `login` | Password login (phone or web). |
| auth | POST | `/v1/auth/refresh` | `refreshToken` | Rotate a refresh token (full or upload grant). |
| auth | POST | `/v1/auth/logout` | `logout` | End a session; optionally revoke the upload grant after the outbox is empty. |
| auth | POST | `/v1/auth/change-password` | `changePassword` | Change the caller's password (policy-checked, revokes the user's other full-grant families). |
| auth | POST | `/v1/auth/bind-device` | `bindDevice` | Bind the user to this enrolled phone with the 4-digit OTP the TSO reads on the web. |
| auth | POST | `/v1/auth/mfa/enrol` | `enrolMfa` | Start TOTP enrolment for the caller (web admin roles). |
| auth | POST | `/v1/auth/mfa/verify` | `verifyMfa` | Second login step for web admins (TOTP or recovery code). |
| auth | GET | `/v1/auth/jwks` | `getJwks` | Public signing keys (JWKS) for access-token verification. |
| auth | GET | `/v1/me` | `getMe` | The caller's identity, role, permissions and scope summary. |
| devices | POST | `/v1/devices/enrol` | `enrolDevice` | Register a factory-reset, device-owner phone with an enrolment token from the QR. |
| devices | POST | `/v1/devices/nonce` | `createDeviceNonce` | Single-use nonce for a Play Integrity request or a key re-attestation. |
| devices | GET | `/v1/devices/me/policy` | `getDevicePolicy` | The device-owner policy for this phone (ETag = policy_version). |
| devices | POST | `/v1/devices/me/status` | `reportDeviceStatus` | Report policy, integrity and health status now (online path; the same object also rides the batch as `device_status`). |
| sync | GET | `/v1/sync/bundle` | `getBundle` | Full day bundle for the caller and a business date (per-route snapshots inside). |
| sync | GET | `/v1/sync/bundle/page` | `getBundlePage` | One page of a paged bundle section (AMO zone-wide outlet lists). |
| sync | GET | `/v1/sync/delta` | `getBundleDelta` | Changes since a delta cursor for the same business date. |
| sync | POST | `/v1/sync/batch` | `postSyncBatch` | Idempotent upload of outbox records; one acknowledgement per record. |
| sync | POST | `/v1/sync/digest` | `postSyncDigest` | Compare per-type bucket digests of recent days; the server names the buckets to re-send. |
| sync | GET | `/v1/sync/totals` | `getSyncTotals` | Server totals for the caller and a business date (the Server column of Sales Submit). |
| sync | GET | `/v1/sync/generation` | `getServerGeneration` | Statement of the current server generation after a failover or point-in-time restore. |
| sync | GET | `/v1/admin/quarantine` | `listQuarantine` | Rejected and quarantined records (nothing is ever dropped) for review. |
| sync | POST | `/v1/admin/quarantine/{quarantine_id}/resolve` | `resolveQuarantine` | Accept, accept with a fix, or discard a quarantined record; re-ingests through the same path. |
| media | POST | `/v1/media/sas` | `createMediaUploadUrls` | Write-only user-delegation SAS URLs for up to 10 photos (15 minutes, one blob path each). |
| media | GET | `/v1/media/{media_uuid}/read-url` | `getMediaReadUrl` | Short-lived read URL for a photo in the caller's reach (supervisors, web). |
| media | POST | `/v1/media/upload` | `uploadMediaMultipart` | Multipart fallback for one small non-evidence image (feedback); evidence photos always use SAS. |
| config | GET | `/v1/config/delta` | `getConfigDelta` | Resolved config changes for the caller's scope chain since a version. |
| app | GET | `/v1/app/home` | `getAppHome` | Role-scoped KPI snapshot for the AMO and TSO app home screens (explicit open, never a timer). |
| app | GET | `/v1/app/update-check` | `checkForUpdate` | Latest published release for this flavour and ABI, with the minimum and blocked versions. |
| app | GET | `/v1/tutorials` | `listTutorials` | Tutorial videos and manuals for the caller's role (also in the bundle; playback is online only). |
| day | POST | `/v1/day/sales-submit` | `postSalesSubmit` | Online Sales Submit for a route-day or a supervisor-day (the phone normally sends a `day_submit` record). |
| day | GET | `/v1/day/final-submit/preview` | `getFinalSubmitPreview` | Read before Final Submit (the "already submitted" alert fires here, at Get Sales Data). |
| day | POST | `/v1/day/final-submit` | `postFinalSubmit` | Close a zone-day (TSO app or web). Online-only, once per zone and day. |
| day | POST | `/v1/day/submit-void` | `postSubmitVoid` | Void the effective Sales Submit of one route-day (capture unlocks on the phone, submit_seq + 1). |
| day | POST | `/v1/day/reopen` | `postDayReopen` | Audited reopen of a final-submitted zone-day (roles in cfg.day.reopen_roles). |
| day | POST | `/v1/day/cover` | `postCoverAssignment` | Same-day cover of a route by another user of the zone (up to 7 days); bumps scope_version. |
| day | GET | `/v1/day/route-days` | `listRouteDays` | Route-day states in the caller's reach for a business date. |
| day | GET | `/v1/day/exceptions` | `listDayExceptions` | Day exceptions (rain, hartal, ...) raised in the caller's reach. |
| day | POST | `/v1/day/exceptions/{exception_uuid}/decision` | `decideDayException` | Approve or reject a day exception (TSO of the zone). |
| day | POST | `/v1/admin/data-void` | `postDataVoid` | Audited void of a route-day's data (replaces "Delete Section Data"); tombstones and a route-day barrier. |
| memos | GET | `/v1/memos` | `listMemos` | Memo history for an outlet beyond the phone's 7-day local window (online fallback), or for the web. |
| outlet-requests | GET | `/v1/outlet-requests` | `listOutletRequests` | Outlet change requests in the caller's reach (web Outlet Approval Panel, AMO verification list). |
| outlet-requests | GET | `/v1/outlet-requests/{request_uuid}` | `getOutletRequest` | One request with its event trail and photo references. |
| outlet-requests | POST | `/v1/outlet-requests/{request_uuid}/verify` | `verifyOutletRequest` | Web verification of a pending request (the AMO app verifies with an `outlet_request_verification` record). |
| outlet-requests | POST | `/v1/outlet-requests/{request_uuid}/approve` | `approveOutletRequest` | Approve a verified request on the web (new outlet gets a code; close sets status closed; location moves the pin). |
| outlet-requests | POST | `/v1/outlet-requests/{request_uuid}/reject` | `rejectOutletRequest` | Reject a pending or verified request with a reason (shown to the requester). |
| tasks | GET | `/v1/tasks` | `listTasks` | Tasks in the caller's reach. |
| tasks | POST | `/v1/tasks` | `createTask` | Create a task online (web, AMO or TSO online path; apps normally queue a `task` record). |
| tasks | POST | `/v1/tasks/{task_uuid}/cancel` | `cancelTask` | Cancel an ongoing task (creator, the zone's TSO or an admin). |
| notifications | PUT | `/v1/devices/me/push-token` | `registerPushToken` | Register or replace the FCM token of this phone for the calling user. |
| notifications | POST | `/v1/admin/notifications` | `sendNotification` | Send an FCM data nudge (announcement or config pull) to devices in a scope. |
| team | GET | `/v1/team/locations` | `getTeamLocations` | Last synced fix per team member (no live tracking; age and source shown). |
| team | GET | `/v1/team/stock` | `getTeamStock` | Issued, sold, returned and current stock per SR and SKU for a date (SR phones never returned). |
| team | GET | `/v1/outlets/nearby` | `getNearbyOutlets` | Outlets within a radius of a point (TSO Retailer radius map), capped and scoped. |
| people | GET | `/v1/leave` | `listLeave` | Leave applications in the caller's reach. |
| people | POST | `/v1/leave/{leave_uuid}/decision` | `decideLeave` | Approve or reject a TSO leave application (DMO by default). |
| people | GET | `/v1/visit-plans` | `listVisitPlans` | TSO visit plans with outlet completion. |
| people | GET | `/v1/feedback` | `listFeedback` | Feedback submitted from the TSO app. |
| people | PATCH | `/v1/feedback/{feedback_uuid}` | `updateFeedbackStatus` | Set the status of a feedback item in the inbox (audited with a reason). |
| dashboards | GET | `/v1/dashboards/summary` | `getDashboardSummary` | National, wing, division, territory or zone dashboard for a date or range (p95 at most 1 s). |
| dashboards | GET | `/v1/dashboards/daily-tracking` | `getDailyTracking` | Daily Tracking by route with achievement buckets (100, 90-100, 80-90, below 80, exception, not logged in). |
| dashboards | GET | `/v1/dashboards/login-submit` | `getLoginSubmitStatus` | Login and Sales Submit status with the lists behind each count (Bikroy Joma status). |
| dashboards | GET | `/v1/dashboards/sync-health` | `getSyncHealth` | Sync health per device and route-day (pending rows, last contact, rejects, quarantine, mismatch). |
| dashboards | GET | `/v1/dashboards/geo-validation` | `getGeoValidation` | Geo-validation, force-sale, mock, mismatch and suspicious-visit rates with risk-signal counts. |
| dashboards | GET | `/v1/dashboards/targets` | `getTargetAchievement` | Monthly target and achievement (till-date basis per surface) for a node. |
| dashboards | POST | `/v1/dashboards/daily-tracking/actions` | `createTrackingAction` | Daily Tracking "take action" note on a route-day; notifies the route's TSO and AMO, never reassigns. |
| risk | GET | `/v1/risk-signals` | `listRiskSignals` | Risk signals for supervisors (the SR never sees them, except the mock warning on the phone). |
| risk | POST | `/v1/risk-signals/{signal_id}/review` | `reviewRiskSignal` | Review, dismiss or confirm a signal with a note (append-only event). |
| reports | GET | `/v1/reports` | `listReports` | Reports the caller may run, with parameters, columns and formats. |
| reports | POST | `/v1/reports/{report_key}/query` | `runReport` | Run a report with one ReportQuery object; json inline, xlsx inline up to the row limit or as an export job. |
| reports | GET | `/v1/report-exports/{export_id}` | `getExportJob` | Status of an export job; a short-lived download URL when done. |
| reports | GET | `/v1/report-exports` | `listExportLog` | Export log viewer (who exported which report with which filters, rows and PII flag). |
| admin-geography | GET | `/v1/admin/geo/{level}` | `listGeoNodes` | List wings, divisions, territories, houses or zones in reach. |
| admin-geography | POST | `/v1/admin/geo/{level}` | `createGeoNode` | Create a geography node. |
| admin-geography | GET | `/v1/admin/geo/{level}/{id}` | `getGeoNode` | One geography node. |
| admin-geography | PATCH | `/v1/admin/geo/{level}/{id}` | `updateGeoNode` | Update a geography node (name, parent, contact fields, status). Optimistic concurrency by If-Match. |
| admin-geography | GET | `/v1/admin/clusters` | `listClusters` | Clusters (outlet groups inside a zone). |
| admin-geography | POST | `/v1/admin/clusters` | `createCluster` | Create a cluster. |
| admin-geography | PATCH | `/v1/admin/clusters/{id}` | `updateCluster` | Rename, move or deactivate a cluster. |
| admin-routes | GET | `/v1/admin/routes` | `listRoutes` | Routes with kind, visit kind and visit days. |
| admin-routes | POST | `/v1/admin/routes` | `createRoute` | Create a route. |
| admin-routes | GET | `/v1/admin/routes/{id}` | `getRoute` | One route. |
| admin-routes | PATCH | `/v1/admin/routes/{id}` | `updateRoute` | Update a route; a visit-days change takes effect from `effective_from` (a future Dhaka date). |
| admin-routes | GET | `/v1/admin/route-assignments` | `listRouteAssignments` | Route assignments (primary, cover) valid on a date or in a range. |
| admin-routes | POST | `/v1/admin/route-assignments` | `createRouteAssignment` | Assign a user to a route from a date (one primary per route-day, enforced by exclusion). |
| admin-routes | POST | `/v1/admin/route-assignments/{id}/end` | `endRouteAssignment` | End an assignment at a date (never deleted). |
| admin-products | GET | `/v1/admin/product-nodes/{level}` | `listProductNodes` | Product tree levels (category, segment, brand, variant). |
| admin-products | POST | `/v1/admin/product-nodes/{level}` | `createProductNode` | Create a product tree node. |
| admin-products | PATCH | `/v1/admin/product-nodes/{level}/{id}` | `updateProductNode` | Update a product tree node. |
| admin-products | GET | `/v1/admin/skus` | `listSkus` | SKUs with base unit, pack size and report unit. |
| admin-products | POST | `/v1/admin/skus` | `createSku` | Create a SKU. |
| admin-products | PATCH | `/v1/admin/skus/{id}` | `updateSku` | Update a SKU (base unit and pack size changes are future-dated). |
| admin-products | GET | `/v1/admin/prices` | `listPrices` | Effective-dated prices per SKU and price type. |
| admin-products | POST | `/v1/admin/prices` | `createPrices` | Publish new prices from a future Dhaka date (closes the open row of each SKU and type at that date). |
| admin-products | GET | `/v1/admin/sales-plans/{zone_id}` | `getSalesPlan` | SKUs enabled for a zone on a date. |
| admin-products | PUT | `/v1/admin/sales-plans/{zone_id}` | `putSalesPlan` | Replace a zone's sales plan from a date (effective-dated; no overwrite of the past). |
| admin-products | GET | `/v1/admin/offers` | `listOffers` | Offer and promotion rules (data, not code). |
| admin-products | POST | `/v1/admin/offers` | `createOffer` | Create an offer rule (future-dated `valid_from`). |
| admin-products | PATCH | `/v1/admin/offers/{id}` | `updateOffer` | Change an offer; a rule change creates a new offer version (memos keep the version they used). |
| admin-products | POST | `/v1/admin/prices/preview` | `previewPrices` | Mandatory preview of a price publish (SKUs, price types, outlets and devices affected; whether a second approver is needed). |
| admin-products | POST | `/v1/admin/prices/batches/{batch_uuid}/decision` | `decidePriceBatch` | Second-approver decision on a price batch that moves a price by more than cfg.price.max_change_pct. |
| admin-users | GET | `/v1/admin/users` | `listUsers` | Users in reach. |
| admin-users | POST | `/v1/admin/users` | `createUser` | Create a user with a temporary password (shown once, must be changed at first login). |
| admin-users | GET | `/v1/admin/users/{id}` | `getUser` | One user. |
| admin-users | PATCH | `/v1/admin/users/{id}` | `updateUser` | Update a user (role and status changes bump scope_version; disabling keeps the upload grant). |
| admin-users | GET | `/v1/admin/users/{id}/scope` | `getUserScope` | Effective-dated supervisory scope nodes of a user. |
| admin-users | PUT | `/v1/admin/users/{id}/scope` | `putUserScope` | Replace a user's scope from a date (bumps scope_version; tokens refresh with 401 ERR_SCOPE_CHANGED). |
| admin-users | POST | `/v1/admin/users/{id}/credentials` | `manageUserCredentials` | Reset password (temporary, shown once), unlock, or force logout (revoke full-grant families). |
| admin-users | GET | `/v1/admin/permissions` | `getPermissionMatrix` | Role by menu by action matrix (data, cfg.web.menu_by_role) and the admin roster. |
| admin-users | PUT | `/v1/admin/permissions/roles/{role}` | `putRolePermissions` | Change one role's menus and actions; creates a C3 config change request (never an immediate grant). |
| admin-outlets | GET | `/v1/admin/outlets` | `listOutlets` | Outlets in reach (PII columns only with the pii claim). |
| admin-outlets | POST | `/v1/admin/outlets` | `createOutlet` | Create an outlet directly (admin); field-created outlets go through outlet requests. |
| admin-outlets | GET | `/v1/admin/outlets/{id}` | `getOutlet` | One outlet with placement and location history. |
| admin-outlets | PATCH | `/v1/admin/outlets/{id}` | `updateOutlet` | Update an outlet (status close is never a delete; placement changes keep history). |
| admin-outlets | POST | `/v1/admin/outlets/outlet-kind` | `bulkSetOutletKind` | Mark outlets wholesale or retail in bulk, idempotent by batch_uuid, one audit row per outlet. |
| admin-targets | GET | `/v1/admin/targets` | `listTargets` | Live monthly targets for routes or zones. |
| admin-targets | PUT | `/v1/admin/targets` | `putTargets` | Upsert a month's targets for a set of scopes (all-or-nothing; targets are never negative). |
| admin-targets | GET | `/v1/admin/target-revisions` | `listTargetRevisions` | Target revise list (approval queue) with month and status filters. |
| admin-targets | POST | `/v1/admin/target-revisions/{revision_id}/decision` | `decideTargetRevision` | Approve or reject a target revision at the caller's level of cfg.target.approval_levels. |
| admin-targets | POST | `/v1/admin/targets/upload` | `uploadTargets` | Upload a target workbook (all-or-nothing); a bad row rejects the whole file with a downloadable error sheet. |
| admin-targets | GET | `/v1/admin/targets/template` | `getTargetTemplate` | Target sample workbook for a month and scope (cfg.target.template_version). |
| admin-targets | GET | `/v1/admin/supervisor-targets` | `listSupervisorTargets` | AMO call targets (total, control-call, joint-call) by user and month; they feed the AMO home tiles. |
| admin-targets | PUT | `/v1/admin/supervisor-targets` | `putSupervisorTargets` | Upsert AMO call targets for a month (all-or-nothing, never negative). |
| admin-calendar | GET | `/v1/admin/calendar/holidays` | `listHolidays` | Holidays, make-up days and emergency off-days with their scope. |
| admin-calendar | POST | `/v1/admin/calendar/holidays` | `createHoliday` | Declare a holiday, make-up day or emergency off-day (an emergency declaration is effective now). |
| admin-calendar | GET | `/v1/admin/code-lists` | `listCodeLists` | Business code lists (force, edit, void, visit outcome, day exception, QC fault types, ...). |
| admin-calendar | PUT | `/v1/admin/code-lists/{list_key}` | `putCodeList` | Add items or edit labels; codes are immutable and retired by valid_to, never deleted. |
| admin-config | GET | `/v1/admin/config/keys` | `listConfigKeys` | The config key registry (type, default, bounds, scope levels, risk class, effect). |
| admin-config | GET | `/v1/admin/config/values` | `listConfigValues` | Stored scoped values of a key (open and, with history, closed rows). |
| admin-config | GET | `/v1/admin/config/resolve` | `resolveConfig` | Effective value of a key at a node and instant, with provenance (which scope row won). |
| admin-config | GET | `/v1/admin/config/changes` | `listConfigChanges` | Change requests and applied changes (history). |
| admin-config | POST | `/v1/admin/config/changes` | `createConfigChange` | Write one or more scoped values with a mandatory reason; C3 changes wait for a second approver. |
| admin-config | GET | `/v1/admin/config/changes/{change_id}` | `getConfigChange` | One change with its blast radius and approval trail. |
| admin-config | POST | `/v1/admin/config/changes/{change_id}/decision` | `decideConfigChange` | Approve, reject or cancel a pending change (the approver is never the requester). |
| admin-config | GET | `/v1/admin/config/versions` | `listConfigVersions` | Committed config versions. |
| admin-config | POST | `/v1/admin/config/versions/{version}/rollback` | `rollbackConfigVersion` | Revert one version (or roll back to it); produces a NEW version, deletes nothing. |
| admin-config | GET | `/v1/admin/config/reach/{version}` | `getConfigReach` | How many targeted devices applied and acknowledged a version. |
| admin-config | GET | `/v1/admin/config/whatif` | `configWhatIf` | Re-evaluate stored fixes under a candidate radius and count visits whose verdict would change. |
| admin-config | GET | `/v1/admin/config/blast-radius` | `configBlastRadius` | Zones, routes, outlets, users and devices a change at a scope would touch. |
| admin-config | GET | `/v1/admin/config/density` | `configDensity` | Outlet density index (neighbours within cfg.geo.density_neighbour_radii_m) for a scope. |
| admin-config | GET | `/v1/admin/config/calibration` | `configCalibration` | Geofence calibration (distance histogram per geo class and territory, force-sale share, suggested radius). |
| admin-config | GET | `/v1/admin/config/versions/{version}` | `getConfigVersion` | One committed config version with its values (compare and history views). |
| admin-config | GET | `/v1/admin/config/reach/{version}/pending` | `listConfigReachPending` | Devices targeted by a version that have not applied it yet, with their lag. |
| admin-devices | GET | `/v1/admin/devices` | `listDevices` | Device fleet with enrolment, trust, policy and last contact. |
| admin-devices | GET | `/v1/admin/devices/{device_id}` | `getDevice` | One device with bound users and its latest status report. |
| admin-devices | POST | `/v1/admin/devices/{device_id}/state` | `changeDeviceState` | Suspend, revoke or reactivate a device (revoked devices keep the upload grant for the grace window). |
| admin-devices | GET | `/v1/admin/devices/{device_id}/policy` | `previewDevicePolicy` | The policy this device would receive now (preview of the resolved cfg.device.* keys). |
| admin-devices | GET | `/v1/admin/devices/{device_id}/status-history` | `listDeviceStatusHistory` | Status reports received from the device (newest first). |
| admin-devices | GET | `/v1/admin/devices/{device_id}/directives` | `listDeviceDirectives` | Remote directives sent to a device and their acknowledgement. |
| admin-devices | POST | `/v1/admin/devices/{device_id}/directives` | `createDeviceDirective` | Ask a device for a status report, a bundle re-download, a support bundle or a re-send from a date. |
| admin-devices | GET | `/v1/admin/enrolment-tokens` | `listEnrolmentTokens` | Enrolment tokens (never the secret value after creation). |
| admin-devices | POST | `/v1/admin/enrolment-tokens` | `createEnrolmentToken` | Create an enrolment token and its Android provisioning QR payload (shown once). |
| admin-devices | POST | `/v1/admin/enrolment-tokens/{token_id}/revoke` | `revokeEnrolmentToken` | Revoke an unused or partly used enrolment token. |
| admin-devices | GET | `/v1/admin/device-otps` | `listDeviceOtps` | SR Device OTP panel (view-only for the TSO; one aggregated audit event per page view). |
| admin-devices | POST | `/v1/admin/device-otps` | `issueDeviceOtp` | Re-issue a device OTP for a user (support and ops roles; the TSO for own zones). |
| admin-devices | POST | `/v1/admin/devices/{device_id}/replace` | `replaceDevice` | Replace-device wizard - upload-first or revoke-now for the old phone, and an OTP for the new one. |
| admin-releases | GET | `/v1/admin/releases` | `listReleases` | App releases by flavour. |
| admin-releases | POST | `/v1/admin/releases` | `createRelease` | Register an APK built by CI (SHA-256 and signing certificate checked) as a draft release. |
| admin-releases | PATCH | `/v1/admin/releases/{release_id}` | `updateRelease` | Publish, block or retire a release, or change its staged rollout percentage. |
| admin-releases | GET | `/v1/admin/releases/policy` | `getReleasePolicy` | Minimum, latest and blocked version codes per flavour (edited through config changes of cfg.release.*). |
| admin-audit | GET | `/v1/admin/audit` | `listAudit` | Append-only, hash-chained audit log. |
| programmes | GET | `/v1/programmes/loyalty/balances` | `getLoyaltyBalances` | Live loyalty balance of outlets in reach (online refresh of the bundle's previous-day balance). |
| programmes | GET | `/v1/programmes/astha/targets` | `listAsthaTargets` | Astha targets and achievement by outlet and brand for a quarter (route and shop information, web reads). |
| programmes | PUT | `/v1/programmes/astha/targets` | `putAsthaTargets` | Upsert Astha targets for a quarter (roles in cfg.astha.target_entry_roles; targets never negative). |
| programmes | GET | `/v1/programmes/astha/gift-choices` | `listGiftChoices` | Astha gift choice per outlet (TSO panel and Astha Gift Choice report source). |
| programmes | PUT | `/v1/programmes/astha/gift-choices` | `putGiftChoices` | Save gift choices explicitly (roles in cfg.astha.gift_choice_roles); a choice locks once the SR's photo exists. |
| web-entry | GET | `/v1/web-entry/route-day` | `getWebEntryRouteDay` | The web route-day aggregate entry of a route and date (with the app's sale quantities read-only). |
| web-entry | POST | `/v1/web-entry/route-day` | `saveWebEntryRouteDay` | Save one route-day aggregate entry (Issue, Return, Memos, Successful Call per SKU); a re-save replaces with audit. |
| web-entry | GET | `/v1/web-entry/outlet-sku` | `getAsthaWebEntry` | Astha Web Entry grid (Astha-channel outlets by SKU) of a route and date. |
| web-entry | POST | `/v1/web-entry/outlet-sku` | `saveAsthaWebEntry` | Save the Astha outlet-by-SKU entry explicitly; overlap with app memos is flagged, never added. |
| web-entry | POST | `/v1/web-entry/qc` | `saveQcEntry` | Market or Warehouse QC entry (SKU by fault type) stored as a separate web source, never added to app QC. |
| web-entry | POST | `/v1/admin/data-entry` | `createPaperBackfill` | Key a dead-phone day from a printed memo (source manual) through the same ingest path. |
| web-entry | GET | `/v1/admin/entry-unlocks` | `listEntryUnlocks` | Entry unlock grants (zone or route, date range, reason, expiry). |
| web-entry | POST | `/v1/admin/entry-unlocks` | `createEntryUnlock` | Grant back-dated web entry for a zone or route (roles in cfg.web.entry_unlock_roles), audited. |
| web-entry | POST | `/v1/admin/entry-unlocks/{unlock_id}/expire` | `expireEntryUnlock` | Expire an unlock grant now (an expired grant no longer allows back-dated entry). |
| admin-content | GET | `/v1/admin/surveys` | `listSurveys` | Survey and questionnaire definitions (POSM, AMO survey, TSO visit query) with validity and assignment. |
| admin-content | POST | `/v1/admin/surveys` | `createSurvey` | Create a survey (version 1); questions are immutable once answered, a change is a new version. |
| admin-content | PATCH | `/v1/admin/surveys/{id}` | `updateSurvey` | Publish a new survey version, change validity or assignment, or deactivate. |
| admin-content | GET | `/v1/admin/rubrics` | `listRubrics` | Assessment rubrics (joint-call stars, TSO visit query). |
| admin-content | POST | `/v1/admin/rubrics` | `createRubric` | Create a rubric. |
| admin-content | PATCH | `/v1/admin/rubrics/{id}` | `updateRubric` | Publish a new rubric version or deactivate. |
| admin-content | GET | `/v1/admin/content` | `listContentItems` | AV and KV marketing content with per-outlet assignment and validity. |
| admin-content | POST | `/v1/admin/content` | `createContentItem` | Create an AV or KV item from an uploaded asset (at most cfg.content.max_item_mb). |
| admin-content | PATCH | `/v1/admin/content/{id}` | `updateContentItem` | Change validity, assignment or order, replace the asset (new version) or deactivate. |
| admin-content | GET | `/v1/admin/tutorials` | `listAdminTutorials` | Tutorial videos and the four manuals per role. |
| admin-content | POST | `/v1/admin/tutorials` | `createTutorial` | Add a tutorial video or manual from an uploaded asset. |
| admin-content | PATCH | `/v1/admin/tutorials/{id}` | `updateTutorial` | Edit, reorder or retire a tutorial. |
| admin-content | POST | `/v1/admin/assets` | `createAdminAssetUpload` | Write-only SAS for an admin asset (AV video, KV image, tutorial, manual PDF, SKU pack image, gift image). |
| admin-content | GET | `/v1/admin/print-templates` | `listPrintTemplates` | Versioned print templates (memo kinds, stock slip, day summary, void slip, due receipt). |
| admin-content | POST | `/v1/admin/print-templates` | `createPrintTemplateVersion` | Publish a new template version (future-dated); phones print it after their next sync. |
| admin-content | GET | `/v1/admin/programmes` | `listProgrammes` | Programme definitions (Diamond League, Astha, campaigns, Superstar). |
| admin-content | POST | `/v1/admin/programmes` | `createProgramme` | Create a programme period (for example "Diamond League (October)" or a campaign). |
| admin-content | PATCH | `/v1/admin/programmes/{id}` | `updateProgramme` | Change dates, rules or status of a programme. |
| admin-content | GET | `/v1/admin/programmes/{id}/enrolments` | `listProgrammeEnrolments` | Outlets enrolled in a programme with league, tier or slab and base target. |
| admin-content | PUT | `/v1/admin/programmes/{id}/enrolments` | `putProgrammeEnrolments` | Upsert enrolments (idempotent by batch_uuid, all-or-nothing). |
| admin-content | GET | `/v1/admin/gifts` | `listGifts` | Gift catalogue (points cost for Diamond League, tier for Astha, cash-back line). |
| admin-content | POST | `/v1/admin/gifts` | `createGift` | Add a gift to a programme's catalogue (code immutable). |
| admin-content | PATCH | `/v1/admin/gifts/{id}` | `updateGift` | Change cost, labels or status of a gift (memos and redemptions keep the cost they used). |
| admin-finance | GET | `/v1/admin/dues-adjustments` | `listDuesAdjustments` | Dues adjustments and write-offs with their maker-checker state. |
| admin-finance | POST | `/v1/admin/dues-adjustments` | `createDuesAdjustment` | Propose a correction or write-off of an outlet due (pending until a different person approves). |
| admin-finance | POST | `/v1/admin/dues-adjustments/{adjustment_id}/decision` | `decideDuesAdjustment` | Approve or reject a dues adjustment (checker ≠ maker); approval adds a ledger row and changes the outlet balance. |
| support | POST | `/v1/support/pda-upload` | `createSupportUpload` | Write-only SAS for one "PDA to Support" data file (encrypted zip, size-capped). |
| support | POST | `/v1/client-errors` | `reportClientError` | Privacy-scrubbed error report from the web app (phones send `app_error` records in the batch). |

---

## Appendix B. Cross-check against `contract/openapi.yaml` (2026-10-05, rerun after the backlog coverage changes)

Permanent gate: the same checks run on every build in `shared/contract` (`SpecCrossCheckTest`: Appendix A equals the contract's operations, every `/v1` path named here exists, the s4.2 record types, the s3.4 HTTP status of every `ProblemCode`, the s4.5 status and retryability of every `RecordOutcomeCode`, the s11.4 and s12.3 catalogues, every `cfg.*` key in the s9.5 registry, every header; `ContractDriftTest`: 24 enums of the Kotlin mirror equal the YAML and every record type has a payload mapping; `BacklogCoverageTest`: every in-scope backlog row's needs exist, docs/24-build-spec-verification.md s10). A change to this page or to the contract that breaks agreement fails `./gradlew :shared:contract:build`.

One-off run: a script parses the YAML and this page (sections 1 to 14, appendices excluded) and checks both directions: every `/v1` path named here exists in the contract and every contract operation is in Appendix A; every value of the contract enums below appears in the matching section and every value listed in those sections exists in the enum; every `cfg.*` key named here or in the contract is in the s9.5 registry and matches the contract's `ConfigKeyName` pattern; every `X-*` header and every schema name cited here exists in the contract; every record type has a payload mapping. Drift found and fixed while writing: `location_mode` enum value `off` quoted (YAML 1.1 parsers read it as a boolean); `number` added to the config `value_type` enum (D24-62); `ERR_PUSH_DISABLED` fixed to 409 in both documents; the offer types of s12.5 and D24-38 aligned with the contract's `Offer.offer_type`; then the 120 elements of the backlog coverage check (verification log s10).

Result (exit code 0):

```text
Endpoints: contract has 206 operations on 166 paths; spec body names 75 distinct /v1 paths; not in contract: none
Appendix A rows: 206 (contract operations: 206) -> identical
Enum RecordType (42 values) vs s4.2 table: contract-only none; spec-only none -> ok
Enum RecordOutcomeCode (36 values) vs s4.5: contract-only none; spec-only none -> ok
Enum AckStatus (4 values) vs s4.5: contract-only none; spec-only none -> ok
Enum ProblemCode (72 values) vs s3.4 table: contract-only none; spec-only none -> ok
Enum DayState (7 values) vs s4.9 table: contract-only none; spec-only none -> ok
Enum SyncTrigger (11 values) vs s4.7 table: contract-only none; spec-only none -> ok
Enum RiskSignalCode (19 values) vs s11.4 table: contract-only none; spec-only none -> ok
Enum ReportKey (42 values) vs s12.3 table: contract-only none; spec-only none -> ok
Enum Role (10 values) vs s8.5: contract-only none; spec-only none -> ok
Enum ConfigScopeType (11 values) vs s9.2: contract-only none; spec-only none -> ok
Enum GeoVerdict (6 values) vs s11.2 table: contract-only none; spec-only none -> ok
Enum OutletRequestType (6 values) vs s12.2: contract-only none; spec-only none -> ok
Enum OutletRequestStatus (6 values) vs s12.2: contract-only none; spec-only none -> ok
Enum DeviceState (5 values) vs s8.7: contract-only none; spec-only none -> ok
Enum LockdownLevel (2 values) vs s10.2: contract-only none; spec-only none -> ok
Enum BundleSectionName (9 values) vs anywhere: contract-only none; spec-only none -> ok
Enum CodeListKey (17 values) vs anywhere: contract-only none; spec-only none -> ok
Enum AppFlavour (3 values) vs s2.1: contract-only none; spec-only none -> ok
Config keys: registry s9.5 has 226 keys; spec names 226; contract names 64; spec keys missing from registry: none; contract keys missing from registry: none
Registry key names matching contract ConfigKeyName pattern: 226/226 (bad: none)
Headers: spec names ['X-App-Version', 'X-Aron-Api', 'X-Batch-Attempt', 'X-Bundle-Version-Current', 'X-Config-Version', 'X-Device-Id', 'X-Device-Proof', 'X-Device-Time', 'X-Last-Sync-Error', 'X-Pending-Rows', 'X-Request-Id', 'X-Server-Generation', 'X-Server-Time']; missing from contract: none
Schema names cited in the spec: 41; not components.schemas: none
SyncRecord discriminator maps 42/42 record types (unmapped: none)

RESULT: PASS (no drift)
```

Not machine-checked: prose rules (for example retry timings) that have no field in the contract; they are binding here and the contract descriptions refer to these sections.

## 14a. Lead rulings after Day 1 (2026-10-06, binding; they answer docs/requests/db-*.md)

| # | Ruling |
|---|---|
| R1 | `app.ingest_registry` is hash-partitioned on `client_uuid` with `PRIMARY KEY (client_uuid)`; retention is a worker DELETE by `received_at`. This replaces the "range by received_at" wording of s12.1. |
| R2 | Seed only the 172 keys of s9.5. The other docs/19 keys are not seeded; add a key by a migration only when a row needs it. |
| R3 | `scope_id` for `role` is the `app.role_def.ordinal` (SR 1, AMO 2, TSO 3, DMO 4, WM 5, TOP 6, ANALYST 7, SUPPORT 8, ADMIN 9, SUPERADMIN 10); for `geo_class` the `app.geo_class_def.ordinal` (Hill 1, Urban 2, SemiUrban 3, Rural 4); 0 is global. |
| R4 | SKU codes with a space are stored with `_` in `Sku.code` (`MaxDB-20S_20HL`); `short_name` keeps the printed text; `external_ref` keeps the original. The contract pattern is unchanged. |
| R5 | `cfg.web.menu_by_role` default is derived from docs/19 s5.3 mapped to the s8.5 roles; `cfg.app.home_tiles` default for SR is the tile order of `docs/ui-reference/sr/` without the Loyalty Point and Photo Capture tiles (docs/27). A `{}` value still means "built-in default" for clients. |
| R6 | Programmes, targets and discounts are deferred (docs/27). |
