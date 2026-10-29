# Request to the data lane: zone-move day, Ops list and `dw.agg_daily_zone` can disagree

From: backend-reports (session 10, 2026-10-29). Status: information, no change asked now. Pre-existing since V0042.

**What happens.** The Ops lists (login/submit and the other route-day lists) place each route-day in the zone, territory, division and wing it had on that date (`AsWasSql.geo`, from `app.route_zone_history`). `dw.agg_daily_zone` stamps the zone a route had when its rows were AGGREGATED. On the day a route moves zone, rows that synced before the move are stamped with the old zone and rows that synced after it with the new one, so the zone-keyed aggregate can split that one route-day across two zones, while the Ops list (as-was, one zone per route-day) shows it whole in one. Which zone each shows for the move day depends on sync order, not on the data.

**Why it is not fixed in the reports.** The reports cannot know which zone a late row "should" have. The rule that makes both agree is one zone per route-day, decided in the aggregation (the as-was write side, D-REPORTS-ASWAS-01, keys `dw.agg_daily_zone` by the as-was zone of the memo's business date). If the data lane changes how the stamp is chosen (for example: the zone in force at the START of the business date, or at its end, for every row of that date), the Ops lists and the aggregate agree on the move day. Today the rebuild/reconcile path (D-REPORTS-REBUILD-01) restates the aggregate from the as-was geography, which converges both after the next rebuild of that date; until then the difference is visible.

**Ask.** Decide the move-day rule (zone at the start of the date, or at its end) and tell backend-reports; the Ops lists follow whichever is chosen. No migration is needed for this note.
