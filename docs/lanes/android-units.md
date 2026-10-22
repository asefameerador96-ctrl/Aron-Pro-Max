# Lane brief: android-units

Session model: **Sonnet** builder, **Opus checker** for every row (money and quantities; docs/29). Owns the
counting-unit change of `docs/34-owner-directive-2026-10-22.md` s4.2 (D-UNITS-02) across the SR app
(`feature-stock`, `feature-sale`, `feature-memo`, `feature-summary`/submit, dues, `core-printing` memo
layout) and the quantity displays of the AMO and TSO apps.

Read `docs/lanes/README.md` first, then docs/34 s4.2, `docs/24-build-spec.md` s7 (memo maths) and s9.5
(units), migrations V0003, V0006, V0007 (`base_unit`, `base_per_pack`, `entry_unit_default`,
`unit_entered`, `pack_factor`, `qty_base`, `cfg.sale.qty_entry_unit`), and the contract slices for memo,
stock and indent lines.

- The rule: field units are packets (cigarettes 20s/10s per SKU; bidi per SKU), boxes of 25 (lighters) and
  dozens (matches). Entry, display, totals, print and sync carry the entry unit and `pack_factor`; `qty_base`
  is computed at entry and is what the server aggregates. Loose base units are not entered
  (`cfg.sale.allow_loose_base_units` false by default). Every quantity on screen and on the memo shows its
  unit label (প্যাকেট / বক্স / ডজন).
- Prices show per entry unit; `price_base_milli × pack_factor` must equal the shown packet price exactly;
  line amount = `qty_entered × packet price`; the memo oracle and the stored total must still agree (checker
  proves it with the shared oracle, for every SKU in the seed, including 10s and 20s of the same brand).
- Stock: opening, indent, received, closing and the stock picture grid in the entry unit; the stock
  reconciliation (device count = server count) compares `qty_base`.
- Memo print (`core-printing` layout): unit label per line, totals per category in the entry unit; golden
  images re-recorded once, reviewed by the checker.
- Sync: the records already carry `unit_entered`, `pack_factor`, `qty_base`; make sure every writer fills
  them and the server rejects a line whose `qty_base ≠ qty_entered × pack_factor` (coordinate with
  backend-core through a request if the check is missing).
- Bundle: `base_per_pack` and `entry_unit_default` per SKU come from the bundle; the device never hardcodes
  20, 10 or 25.
- Acceptance includes an offline sale of a 10s packet SKU, a 20s packet SKU, a lighter box and a match dozen,
  printed, synced, and the dashboard card (sticks/pieces/dozens) showing the derived base quantity.
- Status file `docs/status/android-units.md`; requests `docs/requests/android-units-<name>.md`.
