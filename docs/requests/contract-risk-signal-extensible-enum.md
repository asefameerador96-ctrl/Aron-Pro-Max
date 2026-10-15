# Contract 1.3.1: `RiskSignalCode` becomes an extensible enum (one approved break)

- **Lead approval:** session_01MbUQSxrP7AB9tbyANjUTPS, 2026-10-15 (backend-core session 12 handover item 4).
- **Change:** `components.schemas.RiskSignalCode` keeps its list of codes under `x-extensible-enum` instead of `enum`, adds
  `MEMO_DUPLICATE_SUSPECT` (db V0077; docs/24 s11.4 row added), and its description says every client must accept a code
  it does not know. `info.version` 1.3.0 -> 1.3.1.
- **Why it is a break for oasdiff:** the `?code=` query parameter of `GET /v1/risk-signals` uses `RiskSignalCode`, and
  oasdiff reads the move from `enum` to `x-extensible-enum` as 19 x `request-parameter-enum-value-removed` there (no
  response rule fires). The schema is also reached from responses: `RiskSignal.code` (risk list, bundle
  `supervisor.risk_signals`) and `GeoValidationSummary.signals_by_code` (property names, now any string; server type
  `Map<String, Int>`).
- **Why no phone is exposed:** the server never fills the bundle's `supervisor.risk_signals` today (no phone has received
  a code), and the generated Kotlin client (`shared/contract/.../WireDtos.kt`, `gen_wire_dtos.py`) maps every enum to
  `String`, so an unknown value never fails decoding. No Android code decodes `RiskSignalCode` with the Kotlin enum class.
- **After this change:** new risk codes are additive (oasdiff does not report values added to `x-extensible-enum`), so
  the next code does not need a version bump or a request file (checked: an added value reports nothing, a removed one
  is `request-parameter-x-extensible-enum-value-removed`). The server still needs `ContractCatalogues.RiskSignalCode` and
  the docs/24 s11.4 row in the same commit (`ContractDriftTest`, `SpecCrossCheckTest`), and `?code=` with a code the
  server does not know still answers 400 `ERR_VALIDATION` (the contract types it as a string; not documented there yet).
- **Generated outputs in the same commit:** `contract/slices`, web `src/contract/openapi.d.ts` (`RiskSignalCode` is now
  `string`); `WireDtos.kt` unchanged. `ContractDriftTest.enumOf` reads `x-extensible-enum` when there is no `enum`.
- **Server:** `GET /v1/risk-signals?code=MEMO_DUPLICATE_SUSPECT` answers 200 (`RiskSignalApi.CODES` follows
  `ContractCatalogues.RiskSignalCode`).
