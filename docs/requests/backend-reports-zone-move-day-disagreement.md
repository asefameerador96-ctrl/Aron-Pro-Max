# Request to the data lane: zone-move day, Ops list and `dw.agg_daily_zone` can disagree

From: backend-reports (session 10, 2026-10-29). Status: information, no change asked now. Pre-existing since V0042.

**What happens.** The Ops lists (login/submit and the other route-day lists) place each route-day in the zone, territory, division and wing it had on that date (`AsWasSql.geo`, from `app.route_zone_history`), one zone per route-day. `dw.agg_daily_zone` takes the zone from the memo/visit row, which is frozen when the row is written (V0042; D-REPORTS-ASWAS-01: "the zone from the memo/visit row (frozen) else `app.route_zone_on`"). On the day a route moves zone, rows written before the move carry the old zone and rows written after it the new one, so the zone-keyed aggregate can split that one route-day across two zones while the Ops list shows it whole in one zone. Which zone gets which rows depends on sync order, not on the data.

**Why it is not changed in the reports.** The reports cannot know which zone a late row "should" have; the rule is a data decision. I have not measured what the rebuild path (D-REPORTS-REBUILD-01) does for such a day.

**Ask (information, no change now).** If the data lane wants the two to agree on a move day, choose one rule (the zone in force at the start of the business date, or at its end, for every row of that date) and tell backend-reports; the Ops lists follow whichever is chosen. No migration is needed for this note.
