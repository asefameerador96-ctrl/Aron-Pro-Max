# Request from android-print: wire printing into the database and the SR screens

**Status:** open, 2026-10-07. **To:** android-core (core-database), android-sr (feature-sale, feature-memo, feature-stock, app shells), lead (one config key).

`android/core-printing` now has everything that prints (N-018, N-019) and the print flow logic and UI pieces for
F-SR-013, F-SR-028, F-SR-073 (and the policy for F-SR-015, F-SR-031, F-SR-066). The rows cannot be finished
end to end until these parts exist in modules android-print does not own.

## 1. android-core: persistence (`core-database`)
- Table `print_event` (device record, key `client_uuid`) with the columns of contract `PrintEventPayload`
  (`document_kind`, `memo_client_uuid`, `ref_client_uuid`, `print_count`, `outcome`, `user_confirmed`,
  `template_version`, `printer_model`) plus `at_utc` and `business_date`; record type `print_event` in the outbox
  (family per docs/24 s4.2: `visit` for memo prints, self for stock slip and day summary).
- Columns on `memo`: `printed_at` (nullable, UTC) and `print_count` (int, default 0), as in contract `Memo`.
- An implementation of `com.aktcl.aron.core.printing.flow.PrintLedger`:
  - `history(uuid)`: the `print_event` rows whose `memo_client_uuid` or `ref_client_uuid` is `uuid`, oldest first.
  - `record(event)`: in **one** transaction insert the `print_event` row and its outbox record (payload =
    `event.payload()`); when `event.outcome == "printed"`: set `memo.printed_at` if null and `memo.print_count`
    = number of printed events, or for a stock slip set `stock_movement.slip_printed = true` on the family.
  - A duplicate `client_uuid` must be ignored (idempotent), never a second row.
- `PrintFlowTest.MemLedger` (core-printing tests) is the reference behaviour.

## 2. android-sr: screens (`feature-sale`, `feature-memo`, `feature-stock`, app modules)
- One `PrinterManager` per app process (singleton; `BluetoothSppTransport.factory(context)`,
  `PrefsSavedPrinterStore(context)`, application scope, idle from `cfg.print.disconnect_idle_s`).
- Stock, Review (sale), Memo and Summary screens: `HoldPrinter(manager)`, `PrinterIcon(manager)` in the top bar,
  `PrinterBanner(manager)` under it; Print enabled only when `manager.canPrint`.
- Sale review: the Print button drives `SaveAndPrint(commit = { save the memo + outbox, return CommittedMemo }, printing)`
  rendered with `SaveAndPrintDialogs(flow) { printed -> navigate on }`. The commit is the existing memo save; it
  must not depend on the printer.
- Memo menu (F-SR-030/031/066): Print calls `MemoPrinting.printMemo(memoUuid, memoPrintFromRoom)`; show the
  "ছাপা ঠিক আছে?" dialog for `AwaitingConfirmation` and call `confirm`; `LimitReached` shows `ui_print_limit_reached`.
  The duplicate marker and "supersedes" come from `MemoPrint.reprintNo` (set by `MemoPrinting`) and
  `MemoPrint.supersedesMemoNo` (the edited memo's original number).
- Stock (F-SR-015): after Save (never blocked by the printer) call `MemoPrinting.printStockSlip(stockFamilyUuid, slip)`.
- Mapping Room rows to `MemoPrint` / `StockSlipPrint`: amounts straight from the stored mtk columns, SKU short
  names, outlet `name (code)`, SR name, route name; nothing recomputed.
- Fonts: `PrintFonts(regular, bold)` from `R.font.noto_sans_bengali_regular/bold` of core-ui (`resources.openRawResource`);
  labels: `AndroidPrintLabels(context, AppLanguage.BN)`; templates: `TemplateSet(bundle.templates, labels)`.

## 3. Lead: config
- `cfg.print.confirm_after_print` (bool, default true) is named in docs/15 F-SR-073 but missing from the docs/24
  s9.5 registry. `MemoPrinting` takes it as a parameter, default true. Please add it or rule it out.
- `cfg.memo.reprint_watermark` (docs/15 F-SR-031/066) is also missing; the duplicate marker is always printed.
