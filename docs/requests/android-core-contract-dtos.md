# Request: wire DTOs in shared:contract (from android-core, 2026-10-05)

**What.** Host the Kotlin `@Serializable` DTOs of the contract in `shared:contract`, starting with the ones the phone
uses on Day 1 and Day 2:

- auth: `LoginRequest`, `LoginResponse`, `UserSummary`, `ScopeSummary`, `NodeRef`, `LoginDevice`, `RefreshRequest`,
  `TokenPair`, `LogoutRequest`;
- bundle: `BundleMeta` (with `paged_sections`), `BundleUser`, `Route`, `RouteSnapshot`, `BundleOutlet`, `Sku`, `SkuPrice`,
  `OpenMemo`;
- records: `RecordEnvelope`, `GeoFix`, `FixDeviceState`, `GnssSummary`, `DeviceGeoVerdict`, and the payloads
  `AttendanceEventPayload`, `StockMovementPayload`, `VisitPayload`, `VisitClosePayload`, `MemoPayload`,
  `MemoLinePayload`, `MemoDiscountPayload`, `QcLinePayload`; `SyncBatchRequest`, `SyncBatchResponse`, `RecordAck`.

**Why.** docs/24 D24-03 says the Kotlin mirror is hand-written in `shared:contract` and checked by drift tests. Today it
holds only enums, so android-core had to write local mirrors to proceed (rule: never invent a field; a local stub is
marked `// REQUEST:`). One copy in `shared:contract` keeps the phone and the server from drifting.

**Exact shape.** Member names and nullability exactly as `contract/openapi.yaml` (snake_case via `@SerialName`;
optional members nullable with a `null` default; required members without defaults). Requests are encoded with
`explicitNulls = false`; responses decoded with `ignoreUnknownKeys = true` (docs/24 s3.1 item 2). Free-form `GnssSummary`
and `RadioEnvironment` may stay `JsonObject` on the phone side if typing them costs too much on Day 2.

**Local stubs today (to delete when this lands).**
- `android/core-network/src/main/kotlin/com/aktcl/aron/core/network/AuthDtos.kt` and `BundleDtos.kt`, checked
  member-by-member against the YAML by `AuthDtoContractTest`.
- `android/core-database/src/main/kotlin/com/aktcl/aron/core/database/record/*` (record payloads and reference
  rows), checked by `RecordPayloadContractTest`.
