# Request (backend-core → lead / db / shared): two device config keys do not fit ConfigValueJson

Found by the F-API-005 checker (2026-10-07). The registry defaults of `cfg.sync.reconcile_types` and
`cfg.bundle.outlet_fields` (V0006) nest object → object → array, which `ConfigValueJson` (contract) does not allow
(an object's members may be scalars, arrays of scalars or flat objects). A phone decoding strictly against the contract
would reject the whole bundle.

**Interim (backend-core):** the bundle leaves values that do not fit the contract out of `config.values` and
`config.scheduled` (`ScopedConfig.fitsContract`); the phone falls back to its built-in default for those two keys.

**Ask (lead decides one):** (a) widen `ConfigValueJson` to allow one more level for these keys, or (b) the db lane
reshapes the two defaults (for example `reconcile_types` as an array of `{row, types}` objects with `types` a
comma-joined string). `cfg.bundle.outlet_fields` is effectively server-side (it masks columns); its `delivery` could
become `server`.
