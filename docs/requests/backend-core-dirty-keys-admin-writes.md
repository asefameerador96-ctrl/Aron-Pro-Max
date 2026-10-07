# backend-core to backend-admin: dirty keys for data void and route-assignment writes (F-SYS-086)

**Filed 2026-10-07 by backend-core (session 7).**

F-SYS-086 accepts when "a change to route_day, final-submit, route-assignment or data-void enqueues its dirty key so
the dashboard tile updates within 60 s". backend-core's own share is on lane/backend-core: every route-day state move
(`DayStates.advance`, including the worker's settle timeout), the day's first login (bundle) and Final Submit call
`app.mark_dirty('route_day_agg', route, date, ...)`. Two write sites are in `masterdata` (yours):

1. **Data void** (`DataVoidApi.kt`): today only a void that hits memos writes `memo.voided` events, which the projector
   turns into dirty keys. A void of visits, collections, stock or geo fixes only marks nothing, so the tile keeps the
   voided numbers. **Ask:** in the void transaction, always
   `SELECT app.mark_dirty('route_day_agg', :route, :date, 'data_void')` (once per void; harmless next to the events).
2. **Route assignment** (`AdminRoutes.kt` insert at ~175, end at ~207): a new, ended or moved assignment changes who a
   route-day belongs to. **Ask:** in the same transaction, mark `route_day_agg` for the route on each business date from
   the change's effective date through today (Dhaka) where an `app.route_day` row exists (future days have no row yet
   and are built fresh), reason `route_assignment`.

Web-entry route-days (`app.web_entry_route_day`, V0039) have no writer yet; whoever adds it, the same one-line mark.

Test idea: delete the key, do the write, assert one `app.dirty_key` row for (route_day_agg, route, date).
