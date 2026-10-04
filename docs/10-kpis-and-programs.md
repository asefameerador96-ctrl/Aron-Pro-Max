# 10 — KPIs, Aggregation and Programs

## Aggregation (the read side)

A job rolls newly-synced transactions into per-date, per-scope summary tables. Dashboards and app-home screens read these, never the transaction log. Start with Postgres materialized views or incremental upserts keyed by `business_date`; move to an incremental worker if volume needs it.

| Aggregate | Grain | Feeds |
| --- | --- | --- |
| `fact_daily_route_sku` | date × route × SKU: STD qty, memo count | STD reports, leaderboard, top-sheet |
| `fact_daily_outlet` | date × outlet: visited, geo-valid, STD, memos, zero-sale | CPR, geo %, by-outlet |
| `fact_daily_route` | date × route: calls, successful calls, geo/photo-valid, login/submit state | dashboard, daily tracking |
| rollup views | zone / territory / division / wing × date | every rolled-up dashboard number |

Targets sit beside the facts at the same grain, so achievement % is a division the read side already has both sides of.

## KPI definitions

| KPI | Definition | Notes |
| --- | --- | --- |
| STD (API: STT) | Volume sold, in the SKU's unit | sticks (cigarette), pieces/dozens (lighter/match), bidi unit — confirm `docs/13` |
| Memo | Count of memos | |
| CPR | successful calls ÷ target outlets on the day's routes | "strike rate" in the apps |
| BSR | memos containing a brand ÷ total memos | confirm denominator `docs/13` |
| Geo-validation % | geo-valid calls ÷ total calls | force sales are photo-valid, excluded |
| Login % | routes whose bundle was downloaded ÷ target routes | |
| Submit % | routes uploaded ÷ target routes (apps: ÷ logged-in) | |
| Final-submit status | zones submitted vs remaining | one/zone/day |
| Achievement % | sales ÷ target (MTD or month) | bands: ≥100, 90–100, 80–90, <80 |
| Channel mix | calls/memos split across GT/DCC/Astha/RCC/MT/HoReCa | |
| Retention | current API returns `total_retention_*` per category | meaning to confirm `docs/13` |

Guard every % against divide-by-zero and against negative targets (the current Astha report shows −37,500% from a −20 target — validate target ≥ 0 at entry and clamp/flag at read).

## Programs

### Astha (loyalty for Astha outlets)
Astha is a channel (and its tiers are sub-channels: Platinum, Gold, Diamond, Silver). Runs by quarter. Each Astha outlet has per-brand STD targets + a memo target; apps/report show target/achievement/remaining/%. Gift chosen per outlet in the TSO portal; SR hands over, one photo per outlet; web Astha Gift Choice Report lists choices.

### Diamond League (points)
Outlets earn monthly points. SR app redemption: cash back 2 Tk/point (≤199 pts), gifts (kitchen rack 200, chair 200, tornado fan 400). Points deducted on confirm. Each hand-over photographed (campaign "… Gift Verify"). Web Campaign Gift Redemption report. → `loyalty_ledger`, `redemption`, `gift_photo`.

### Superstar (monthly outlet campaign)
Each outlet: category, incentive slab, base target, STD & memo targets with achievement, sales-criteria-met flag. Web Superstar Campaign Report.

### Promotions / free samples
~22 promotion groups behind the Discount Report; offers apply at sale entry; DRP empty-pack/slide collection earns a discount. Free samples entered by SKU and reported by route.

### Targets & revisions
Monthly, by route and zone, per category/brand/SKU; values can be fractional (so split by a formula down to route/SKU). A revision passes multiple approval levels (config, date range, status, next-approver level, approved_by, final flag). → `target`, `target_revision`.

### Other workflows
Task delegation (AMO/TSO → SR, swipe-resolve). Leave (TSO → DMO approval). Device binding (TSO OTP).
