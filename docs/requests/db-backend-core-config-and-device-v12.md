# Note (db → backend-core): config shapes (R17) and v1.2 device columns (R12, R13, R18) are on INT

**Config (V0024, docs/24 s14a R17):**
- `cfg.sync.reconcile_types` is now a flat object keyed `ROLE.row` with arrays of record-type strings, e.g.
  `{"SR.outlet":["visit"],"SR.sale":["memo"],"SR.stock":["stock_movement"],"SR.qc":["qc_line"],"SR.promotion":["memo_discount"]}`.
  The default and every open or future stored value were reshaped (a role-scoped bare key `sale` became `SR.sale`);
  pending and scheduled `cfg_change` items too. Please adapt `ScopedConfig` (it fits `ConfigValueJson` unchanged).
- `cfg.bundle.outlet_fields` delivery is `server`: leave it out of the device config and keep masking on the server.
- New device keys, delivery `device`, scope `global`, bool: `cfg.print.confirm_after_print` (true),
  `cfg.memo.reprint_watermark` (true), `cfg.sale.require_printer_before_sale` (false). They reach the phone through the
  normal config bundle; nothing to code unless ScopedConfig lists keys explicitly.

**Device (V0025, V0026) on `app.device`:**

| Column | Write | NULL means |
|---|---|---|
| `root_hints text[]` | `DeviceStatusReport.root_hints` as sent; `{}` when the phone sent an empty list | unknown (older phone, never reported). Never write `{}` for a missing member (R18 item 3) |
| `root_hints_at timestamptz` | report time, **required whenever root_hints is set** (CHECK) | never reported |
| `integrity_unavailable_reason text` | `play_integrity_unavailable.reason` (`no_play_services`, `not_configured`, `offline`, `api_error`, `timeout`) | never reported |
| `integrity_unavailable_at timestamptz` | report time; set together with the reason (CHECK: both or neither) | never reported |

At most 16 hints, each one of the ten R13 values (CHECK). On enrolment use the top-level `EnrolDeviceRequest`
marker (R18 item 2). Keep the last reason when a later report carries a verdict; compare `integrity_unavailable_at`
with `integrity_checked_at` to know which is newer.

Reply here when ScopedConfig and the device writes are done.
