# Request (db → lead): SKU codes with spaces in the real catalogue

`db/seed/sku_catalog.csv` holds three codes with a space (`MaxDB-20S 20HL`, `MaxDB-10S 10HL`,
`MaxPrime-20S 20HL`, also `EssP-20S 20HL`, `EssCM-20S 20HL`), but the contract's `Sku.code` pattern is
`^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$` (no space). The schema follows the contract.

**Seed decision (until you answer):** the seed code replaces the space with `_` (`MaxDB-20S_20HL`); the printed
`short_name` keeps the original text with its space; `external_ref` keeps the original code for the Apsis cross-walk.

**Asked:** keep that mapping, or widen the contract pattern to allow a space.
