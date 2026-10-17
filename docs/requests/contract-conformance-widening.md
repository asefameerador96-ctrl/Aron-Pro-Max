# Contract 1.3.2 and 1.3.3: code-list attrs and config values widened to what the server stores (approved breaks)

- **Lead approval:** session_01MbUQSxrP7AB9tbyANjUTPS, 2026-10-17 ("approve widening"; D-CONTRACT-WIDEN-01). Found by qa's
  `ContractConformanceTest` (docs/requests/qa-contract-conformance.md, section C).
- **Change 1, `CodeItem.attrs`:** each attribute is a JSON scalar **or an array of scalars** (at most 50). The shipped
  seed `V0027__system_code_lists.sql` stores `applies_to: ["app", "web"]` on `qc_fault_type` and `roles: ["AMO"]` on
  `force_reason`; `GET /v1/admin/code-lists`, `PUT /v1/admin/code-lists/{list_key}` and the bundle's `code_lists`
  already answer them.
- **Change 2, `ConfigValueJson`:** a role map's value may be an object or an array of scalars or objects; an object
  inside is the new `ConfigValueRecord` (members are scalars or arrays of scalars). The shipped registry stores
  `cfg.bundle.outlet_fields` = `{"SR": {"exclude": [...]}}` (V0006, V0049) and `cfg.web.menu_by_role` =
  `{"TSO": [{"menu", "page", "actions": [...]}]}` (V0012); `PUT /v1/admin/permissions/roles/{role}` answers such
  `changes[].value`. Array items that are objects also take `ConfigValueRecord` (arrays inside now allowed).
- `info.version` 1.3.1 -> 1.3.2.
- **Why it is a break for oasdiff:** `response-property-any-of-added`, `response-property-type-changed` and
  `response-property-max-length-unset` on every operation that returns a code list or a config value (code lists,
  bundle and delta, config keys, values, resolve, versions, changes, permissions), and `request-property-any-of-removed`
  on `POST /v1/admin/config/changes` (oasdiff reads the rewritten inline `anyOf` members as removed and added; the
  request accepts strictly more than before).
- **Why no client is exposed:** the servers already send these shapes (seed and registry data of shipped,
  forward-only migrations), so no running client sees anything new. The phone does not read `attrs` (Android has no
  reader of it) and decodes config values as `JsonElement` (`ResolvedConfigValue` in `WireDtos.kt`); the web reads only
  `attrs.group` and treats config values as JSON. Recoding stored config would be riskier than correcting the contract.
- **Generated outputs in the same push:** `contract/slices`, web `src/contract/openapi.d.ts`, `WireDtos.kt` (unchanged
  by the generator), conformance baseline shrunk by the fixed findings.

## 1.3.3 follow-up (lead approval, session_01MbUQSxrP7AB9tbyANjUTPS, 2026-10-17, D-CONTRACT-WIDEN-01)

- **Change 3, `ConfigKeyDef.default_value`:** `oneOf` [`ConfigValueJson`, null]. The shipped registry
  (`V0006__cfg_key_registry.sql`) stores `cfg.ops.maintenance_banner` with default `null` (no banner), and
  `GET /v1/admin/config/keys` already answers it. `info.version` 1.3.2 -> 1.3.3.
- **Why it is a break for oasdiff:** one `response-property-one-of-added` on `GET /v1/admin/config/keys`
  (`items/items/default_value`).
- **Why no client is exposed:** the server already sends null for that key; the web config editor is the only reader
  of the registry read (phones get resolved values, not the registry).
- **Generated outputs in the same push:** `contract/slices`, web `src/contract/openapi.d.ts`; the last line of
  `contract-conformance-baseline.txt` is removed.
