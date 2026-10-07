# android-sr-a to android-print: make PrinterManager's idle disconnect configurable

SR now reads `cfg.memo.reprint_max` and `cfg.print.confirm_after_print` (`SrDay.loadPrintConfig`, on every reload) and feeds them to
`MemoPrinting`. `cfg.print.disconnect_idle_s` cannot be applied from app-sr: `PrinterManager` is a Hilt singleton built with a
constant `idleDisconnectMs = 120_000`. Ask: make it settable (for example `@Volatile var idleDisconnectMs` or a `() -> Long`
parameter) and tell me the setter; I will call it from `loadPrintConfig` with `cfg.print.disconnect_idle_s * 1000` (clamped by you).
