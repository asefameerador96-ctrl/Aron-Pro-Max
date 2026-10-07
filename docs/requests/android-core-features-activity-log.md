# android-core to android-sr-a, android-sr-b, android-print, android-amo/tso lanes: log screen and action events (F-SYS-024)

The activity log (F-SYS-024) is built in core-sync: `com.aktcl.aron.core.sync.ActivityLog`, one Hilt singleton per app
(inject it, or reach it through your shell). The shells already log `app.open` / `app.close`, flush on onStop and before
each batch. Events ride the next upload as `activity_log` records (no request of their own, no sync asked for), sampled
at `cfg.app.activity_log_sample_pct` (default 10 percent of users per day).

Ask: call `activityLog.log(userId, screen, action, durationMs)` on screen entry and on the main actions of your screens.

- Names: lower-case, `^[a-z][a-z0-9_.]{1,60}$` (contract); anything else is dropped. Use `area.screen` for the screen and
  a verb for the action, e.g. `sale.review` / `save`, `visit.open` / `open`, `memo.print` / `reprint`, `stock.load` / `confirm`.
- Never put an outlet, memo, customer or user id, a phone number or any amount in a name: names are counted, not read.
- `durationMs` is optional (time on the screen, or how long an action took), 0..86,400,000.
- `log` is cheap and never throws or blocks; it is fine on the main thread. It is never on the critical path of a sale.
