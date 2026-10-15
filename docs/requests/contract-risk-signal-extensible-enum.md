# Contract 1.3.1: `RiskSignalCode` becomes an extensible enum (one approved break)

- **Lead approval:** session_01MbUQSxrP7AB9tbyANjUTPS, 2026-10-15 (backend-core session 12 handover item 4).
- **Change:** `components.schemas.RiskSignalCode` keeps its list of codes under `x-extensible-enum` instead of `enum`, adds
  `MEMO_DUPLICATE_SUSPECT` (db V0077; docs/24 s11.4 row added), and its description says every client must accept a code
  it does not know. `info.version` 1.3.0 -> 1.3.1.
- **Why it is a break for oasdiff:** `RiskSignalCode` is reached from responses (`GET /v1/sync/bundle` supervisor
  `risk_signals`, `GET /v1/risk-signals`) and the `?code=` query parameter, so a new value or the removal of `enum` is
  reported (`response-property-enum-value-added` and related rules).
- **Why no phone is exposed:** the server never fills the bundle's `supervisor.risk_signals` today (no phone has received
  a code), and the generated Kotlin client (`shared/contract/.../WireDtos.kt`, `gen_wire_dtos.py`) maps every enum to
  `String`, so an unknown value never fails decoding. No Android code decodes `RiskSignalCode` with the Kotlin enum class.
- **After this change:** new risk codes are additive (oasdiff does not report values added to `x-extensible-enum`), so
  the next code does not need a version bump or a request file. Removing a code still needs one.
- **Generated outputs in the same commit:** `contract/slices`, web `src/contract/openapi.d.ts` (`RiskSignalCode` is now
  `string`); `WireDtos.kt` unchanged. `ContractDriftTest.enumOf` reads `x-extensible-enum` when there is no `enum`.
- **Server:** `GET /v1/risk-signals?code=MEMO_DUPLICATE_SUSPECT` answers 200 (`RiskSignalApi.CODES` follows
  `ContractCatalogues.RiskSignalCode`).
