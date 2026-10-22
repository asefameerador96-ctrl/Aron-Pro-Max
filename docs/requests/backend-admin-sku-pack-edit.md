# Request: edit the pack size of an existing SKU — web-admin wave 4

Lane: web-admin. For: backend-admin. Spec: `docs/34` s4.2 (D-UNITS-02).

`SkuWrite` carries `base_per_pack` and `entry_unit_default`, but `SkuPatch` does not: a SKU created with a wrong pack size (a bidi packet, a lighter box) can never be corrected
through the portal. The SKU form therefore shows the pack size on create only; the edit page shows it read-only with the live preview ("1 packet = 20 sticks") and says it is fixed.

Proposed: `SkuPatch.base_per_pack` (integer 1..1000) and `SkuPatch.entry_unit_default` (QtyUnit), audited with `change_reason`, effective for NEW lines only: stored stock, indent and memo lines keep their own
`pack_factor` and `qty_base`, so nothing already recorded changes meaning. Changing `base_per_pack` must keep `price per base unit x pack_factor` equal to the shown packet price in milli-taka
(docs/34 s4.2 checker test): the price list is re-checked, and a mismatch is refused with a clear error (or the change is scheduled together with a price publish).

When it lands the web only has to flip the two fields from `create-only` to `rw` in `web/src/app/admin/_entities/products.ts`.
The pack label (packet / box / dozen) is derived from the category, not stored: tell the web lane if the owner wants a stored per-SKU label.
