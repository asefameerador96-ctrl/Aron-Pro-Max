# lead-backend -> lead: TSO bundle outlets at fleet scale (final-account item, no code now), 2026-10-18

Since 3f3184bd (lead ruling 2026-10-17 23:24) the TSO bundle carries every outlet of the routes of its reach zones, built
like the AMO's (F-AMO-044): outlets of reach zones only, paged above `cfg.bundle.page_threshold_rows` (2000) in pages of
at most 2000 rows, delta by outlet key.

**The case.** F-TSO-027 assumed about 2,500 outlets (under 1 MB gzipped). A TSO normally holds one territory, i.e.
several zones, and `SupervisorBundle` documents 3,500 to 11,000 outlets per zone: a TSO login can mean 20,000 to 50,000
outlets, far above 1 MB and the 2 GB/day pack budget of docs/04, and a heavy morning load on the shared page cache
(about 150,000 rows for all users) and on the bundle build. Only routes are capped (2,000); outlets are not.

**Options (decide before the 8,500-user sizing in the final account):**
1. **Per-zone pick:** the TSO app asks for one zone at a time (`GET /v1/sync/bundle?zone_id=` or a page selector that
   names the zone), the default being the TSO's home zone; the other zones load on demand on Wi-Fi. Additive query
   parameter; the server checks it against the token's reach.
2. **Outlet cap with continuation:** the bundle carries at most N outlets (config, e.g. 3,000) and a continuation token
   for the rest, fetched page by page on Wi-Fi; the phone shows "more outlets on Wi-Fi" until done.

Option 1 matches how a TSO works (one zone per day) and keeps the pack small; option 2 needs no app UI change.
At pilot size (5 to 10 users, one zone) neither is needed (lead ruling 2026-10-18 01:04: no cap now).
