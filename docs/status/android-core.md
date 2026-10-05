# Status: lane android-core (Day 1)

## Done (builder, then an independent checker per row; every confirmed defect fixed with a test)
- **N-001** Native build and the three app shells. `:android:app-{sr,amo,tso}:assembleDebug` gives `com.aktcl.aron.sr/.amo/.tso`.
  - Each shell: Compose login (feature-auth), then a home placeholder (feature-home); Hilt wiring in the app module.
  - API base URL: a bare origin from `aron.apiBaseUrl`, validated at configuration time; a release build refuses http.
  - core-network: OkHttp, kotlinx.serialization, phone headers, `X-Aron-Api` edge detection, RFC 9457 problems, one refresh-and-repeat on `ERR_TOKEN_EXPIRED`/`ERR_SCOPE_CHANGED`, the `X-Device-Proof` hook and proof strings.
  - core-session: tokens encrypted with an Android Keystore AES-GCM key; Argon2id offline unlock (7 days, 10 failures then a doubling cool-down on elapsedRealtime, high-water clock against rollback).
  - core-session: a refresh failure flags re-auth and never ends local work; the upload grant survives logout; shared-phone users are kept separate.
  - Login is proven against MockWebServer serving the contract's own `phoneOk` and `BundleSr` examples (`SessionRepositoryTest`).
- **F-SYS-033** Labels ARON SR/AMO/TSO in both languages and the package ids, checked by aapt2 and by `AppCoexistenceTest`.
- **F-SYS-044** No shared authority, permission or sharedUserId; exported components are an explicit allowlist.
  - `-Paron.applicationIdSuffix=.copy` builds a fourth package, `com.aktcl.aron.sr.copy`.
  - **Device part pending:** printing from each app with the same paired MP-58N.
- **F-SYS-018** Languages, digits, fonts and the build gate.
  - Bangla and English resources; Bangla at first run with a persistent switch (`AppLocale`, no process-wide default-locale change).
  - Bengali digits through `LocaleDigits` (South Asian grouping), applied to dates and numbers, never to identifiers.
  - Fonts: Noto Sans Bengali 400 and 700, plus subset Noto Sans 400 and 700. The four files total 336 KB raw (182 KB in the APK). The OFL text ships as `assets/licenses/OFL-noto-fonts.txt`.
  - Build gate: `HardcodedStringScanTest` (in `core-ui` `testDebugUnitTest`, which CI runs) fails on any prose-like literal in app-*, feature-*, core-ui and dpc, and on any string without a `values-bn` twin. 17 gap cases found by the checker are tests.
- **N-016** Room database for the SR day.
  - Tables: route, outlet, sku, geo_fix, attendance_event, stock_movement, visit, visit_close, memo, memo_line, memo_discount, qc_line, outbox, sync_meta. Every device table is keyed by its client_uuid.
  - Captures write domain rows plus outbox records in one transaction. Payloads are built from the stored row and checked against the contract (encoded JSON, required members, nested GeoFix).
  - Tests: migration from the exported schema plus an identity-hash check; duplicate-uuid rejection in every table; kill and relaunch with a persisted in-flight batch; a seeded SR day (200 outlets, 60 SKUs) loads in about 0.2 s on the host.
  - A per-user database file (`aron-u<id>.db`) with a SQLCipher factory.

## Second re-check (independent agent, on the pushed fixes)
- Found a release-build regression: the https guard broke the configuration cache. Fixed; `assembleRelease` now builds (10.7 MB unsigned with R8) and an http base URL fails. **Ask to infra:** add `:android:app-sr:assembleRelease` to CI so this cannot regress silently.
- Three session edge cases (rollback without an unlock attempt, reboot plus clock forward, upload grant on password change), two memo checks, and the scanner's misses and false positives: all fixed, every case is a test.
- Scanner false-positive budget: realistic Day-2 code (Room `@Query`, log tags, Timber, MIME types, date patterns, time zones, exceptions, previews, `@Named`, headers, work names, routes, JSON keys) passes. Other lanes opt a line out only with `// i18n-ignore: <reason>`.

## Awaiting a device or the CI emulator job (prepared, compiled, not run here)
- `core-session` `DeviceCryptoTest`: Keystore cipher round trip and tamper rejection; native Argon2id parameters and time on the phone.
- `core-database` `SqlCipherDeviceTest`: the per-user file is encrypted and reopens with its key only.
- Manual on the Galaxy A06:
  - install the three APKs side by side;
  - log in against the Azure API once it is up;
  - print from each app;
  - time the seeded day load with SQLCipher (the host figure is not a phone figure).

## Not done / next (Day 2 rows)
- Sync worker loop, batching and acks (F-SYS-008); full bundle apply into Room (F-SYS-006); the trusted clock (F-SYS-049).
  - The session and the device proof use `WallClock.System` until F-SYS-049 replaces it.
- App wiring of the user database and its Keystore-wrapped SQLCipher passphrase lands with F-SYS-006, the first screen that reads Room.
- No `memo_counter` table yet: that is F-SYS-027.
- Bundle apply has no monotonic version or date guard yet (F-SYS-006).
- Quarantine resolutions are not applied yet (F-SYS-008).

## Decisions taken (android-core). DECISIONS.md is read-only for lanes (docs/26 s2), so they are recorded here.
- **AC-01:** before enrolment exists, a dev phone mints and keeps a local device_uuid. Enrolment (dpc lane) replaces it with `DeviceIdentity.setEnrolledUuid`. Reason: login needs a device_uuid on Day 1.
- **AC-02:** the offline verifier and the profile live in an encrypted file per username hash, not in the user's Room database. Reason: the user id is unknown until the username is matched. docs/24 s8.1 says "in the user's encrypted database"; the protection is equivalent (Keystore AES-GCM).
- **AC-03:** the offline cool-down starts at 60 s, doubles per failure after the 10th, and is capped at 1 h. It runs on elapsedRealtime, falling back to the wall clock only after a reboot. docs/24 gives "doubling" but no base or cap.
- **AC-04:** a date earlier than the last online login by more than 10 minutes refuses offline unlock (CLOCK_INCONSISTENT). Offline age is measured from the highest wall time seen. Reason: reps tamper with clocks; prod phones have auto-time enforced.
- **AC-05:** login falls back to offline unlock on transport failure, an edge page, 429, 5xx and 426 (the session is flagged update-required; docs/24 s3.7 lets an open offline day finish). It never falls back on 401 or 403.
- **AC-06:** only `ERR_AUTH_REFRESH_INVALID`, `ERR_AUTH_REFRESH_REUSED`, `ERR_PASSWORD_CHANGED` (and `ERR_AUTH_USER_DISABLED` for the full grant) end a refresh family. Device-proof or suspension errors keep the tokens.
- **AC-07:** `outbox.payload_sha256` is SHA-256 of the stored `payload_json` bytes, a local integrity check. The server's ingest hash is its own RFC 8785 computation (docs/24 s3.3).
- **AC-08:** the Day-1 Room schema replaces the skeleton's version 1 in place, with no migration. Nothing was ever installed that opened the old database: the skeleton activity never touched Room.
- **AC-09:** Bangla mode renders in Noto Sans Bengali, which also covers Basic Latin. English mode renders in subset Noto Sans.
- **AC-10:** the language preference lives in SharedPreferences, not DataStore. Reason: it must be read synchronously in `attachBaseContext`.
- **AC-11:** WorkManager's `SystemJobService` and `DiagnosticsReceiver` and profileinstaller's receiver are exported by those libraries behind system-only permissions. They are allowlisted by name. This deviates from the literal list in docs/24 s5.8.
- **AC-12:** ownership. Per the lead's Day-1 notes, android-core owns the three app shells' build wiring. The Day-1 login screen in feature-auth and the home placeholder in feature-home were built here because N-001 needs them and android-sr had no Day-1 rows. android-sr takes them over from Day 2 (F-SR-001, F-SR-008).

## Requests filed
- `docs/requests/android-core-contract-dtos.md`: host the auth, bundle and record DTOs in `shared:contract`. Local mirrors are marked `REQUEST:` and checked member by member against the YAML.

## Environment
- Maven Central answered 429. I used the mirror init script of docs/24-build-spec-verification.md s9 in `~/.gradle/init.d` (not committed).
