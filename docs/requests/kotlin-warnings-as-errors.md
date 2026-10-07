# Request (infra → shared-rules owner, backend-core): fix 14 compiler warnings so warnings-as-errors can switch on (AUD-TP-6)

From: infra lane, 2026-10-07. Root `build.gradle.kts` now has a `warningsAsErrors` module list (production sources only,
tests stay lenient). It is empty because every target module still warns. Fix these, then add your module to the list
in the same commit (infra owns the file; appending your module path is allowed):

| Module | Warnings (2026-10-07) |
|---|---|
| `:shared:rules` | `BusinessDate.kt:20` and `Formats.kt:35`: kotlinx-datetime `monthNumber`/`dayOfMonth` deprecated (use `month.number`/`day`) |
| `:backend:platform` | `Requests.kt:32` `readRemaining(max)` deprecated (use `readBuffer`); `Requests.kt:65-69` needs `@OptIn(ExperimentalSerializationApi::class)` |
| `:backend:auth` | `AuthModule.kt:101` condition always true; `LoginService.kt:75` condition always false (dead branch: check the intent) |
| `:backend:sync` | `IngestService.kt:220`, `RecordWriter.kt:124` Jdbi `Handle.release(String)` deprecated (use `releaseSavepoint`); `ScopedConfig.kt:96` redundant `else`; `SyncApi.kt:82` `readRemaining(max)` deprecated |

Check locally: add the module to `warningsAsErrors`, then `./gradlew <module>:compileKotlin --rerun-tasks`.
Kover coverage floors follow the same path once the floors (docs/20 P7) are agreed for these modules.
