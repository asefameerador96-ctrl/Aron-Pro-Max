# Seed → table map: Gulshan and Banani dev geography (D-SEED-02, docs/34 s4.4)

Source: `db/seed/06_dev_gulshan_banani.sql` (replaces `06_dev_real_route.sql`), with `db/seed/02_products.sql`
(SKUs, units) and migrations V0087–V0103. Written for lane qa's data-flow trace
(`docs/evidence/data-flow-trace-<date>.md`: seed → bundle → SR app → sync → summaries → dashboard → reports).

## Where it applies (read first)

- **Azure dev database only.** The file runs only when the config version of `04_dev_config_and_codes.sql`
  ("Dev database overrides (docs/24 s9.4, seed)") is absent. The dev deploy loads `db/seed/0*.sql` without `04_*`
  (`infra/deploy.sh`), so dev gets Gulshan/Banani; CI, the backend test harnesses and a local `:db:seed` load 04 and
  keep the Mirpur fixture of 01/03 (about 250 test assertions key on its codes, schedule and counts).
  Check on dev before trusting the trace: `SELECT count(*) FROM app.cfg_version WHERE summary = 'Dev database
  overrides (docs/24 s9.4, seed)'` must be 0.
- Reaches dev at the next CI deploy with the dev seed on (`ARON_DEV_SEED`). Not run against dev from this lane.
- **The dev slice smoke** (infra, changed by lane db 2026-10-22 because no infra lane runs this wave):
  `infra/sql/devseed-smoke-outlet.sql` (loaded after this seed) puts one smoke outlet on each route: `SMOKE-SR-001`
  on `GUL-SR-2` (moved from `MIR-SR-D` on a database that already had it: placement closed and reopened, location row
  appended), `SMOKE-SR-002` on `GUL-SR-1`, `SMOKE-SR-003` on `BAN-SR-11` (GT, visit sequence 99, synthetic owner).
  `infra/scripts/slice-smoke.py` takes the code list and sells on whichever is on today's planned route; on Friday
  there is no route by design and the (non-blocking) smoke reports that. **For the trace: on dev each route has 21
  outlets (20 seed + 1 smoke)**, and the smoke's sale is voided at the end of every run (no money left), but its
  visit stays: exclude `SMOKE-SR-%` when matching outlet counts and calls.
- Test profile proof: `SeedGulshanBananiTest` (dev profile loaded without 04; second run changes nothing; full seed
  leaves Mirpur untouched).

## Seed → tables

| Seed item | Table(s) and key values | Read by (hop of the trace) |
|---|---|---|
| House `H-GUL` Gulshan Distribution House, under `T-DHK-N` (wing `W-CEN`, division `D-DHK`) | `app.house` (`is_active` true) | dashboard House filter; `dw.dim_geo.house_id` (projector) |
| Zones `Z-GUL` Gulshan, `Z-BAN` Banani (house H-GUL) | `app.zone` | filters, Final Submit card (service zones), `dw.agg_daily_zone` |
| Clusters Gulshan 2 Circle, Gulshan 1 Circle (Z-GUL), Banani Road 11 (Z-BAN) | `app.cluster` | outlet pop-up Cluster Name |
| Routes `GUL-SR-2` Sat+Tue (mask 9), `GUL-SR-1` Sun+Wed (18), `BAN-SR-11` Mon+Thu (36); all `2f`, `route_class` normal | `app.route`, `app.route_planned` (from 2026-10-01) | bundle "today's route", Target Route, Live CPR (normal routes only) |
| Friday | no route has bit 6 (Friday); masks sum to 63 = Sat..Thu, each selling day exactly one route | planner; Friday shows no planned route |
| 60 outlets, codes `GUL2-001..020`, `GUL1-001..020`, `BAN11-001..020`, plus the owner's three test outlets on GUL-SR-1 (D-NIGHT-SEED-OUTLETS-01): `GUL1-T01` Asef Home 23.788347, 90.402357; `GUL1-T02` Asef Office 23.791886, 90.420650; `GUL1-T03` Asef Head Office 23.795805, 90.418034 (retail GT, visit order 21-23; the owner's exact pins on the owner's route, so Asef Home sits about 1.5 km west of Gulshan 1 circle, nearer Banani, though its cluster is Gulshan 1 Circle) | `app.outlet` (`location_basis` master, confirmed, accuracy 8 m, geo_class Urban), `app.outlet_placement_history`, `app.outlet_location_history` (from 2026-10-01) | bundle outlets with pins → on-device geofence; route map base positions; Sale Position base (B) |
| Pins | n 1–10 south along the north–south street from the centre (≈ 65 m apart), n 11–20 east along the east–west street (≈ 62 m apart). Centres: Gulshan 2 circle 23.7925, 90.4078 (Gulshan Avenue, Road 90); Gulshan 1 circle 23.7808, 90.4165 (Gulshan Avenue south, Road 27); Banani 11 23.7937, 90.4043 (Kamal Ataturk Avenue, Road 11). Plausible street points, not surveyed shops | geofence radius `cfg.geo.radius_m` (100 m default) |
| Channels per route: n 10, 20 Astha; 7 DCC; 13 RCC; 17 MT; 19 HoReCa; the rest GT (14 GT per route) | `app.outlet.channel` (NOT NULL), `app.sub_channel` (+ DCC-1, RCC-1, MT-SUPER, HORECA-REST) | By Channel Successful Call, By Channel STD (sum = Successful Calls) |
| Outlet n 20 of each route: wholesale, distributor price | `app.outlet.outlet_kind`, `price_type` | memo price type |
| Names: invented Bangla/English shop names; owners `Owner GUL2-1` …; phones `0170700xxxx` synthetic | `app.outlet.name`, `name_bn`, `owner_name`, `contact_number` | outlet pop-up (PII: synthetic only) |
| Sales plan: every active SKU with a non-zero outlet price (39 SKUs) for Z-GUL and Z-BAN, from 2026-10-01 | `app.sales_plan` | bundle SKU list (Stock, Sale) |
| sr1001: primary on the three routes from the seeding date; its open Mirpur primaries (MIR-SR-*, real 16685/16686) closed that date; `MIR-SR-*` inactive; home zone Z-GUL | `app.route_assignment`, `app.route.status`, `app.app_user.home_zone_id` | login bundle, Login / Submit Status. The switch takes effect on the seeding date: a phone mid-day on a Mirpur bundle at re-seed loses that day's Mirpur scope on sync (accepted on dev; re-seed outside selling hours) |
| amo1001: zone scope Z-GUL and Z-BAN (Z-MIR closed); home zone Z-GUL. tso1001 territory T-DHK-N already covers both zones; dmo1001 division D-DHK | `app.user_scope` | AMO/TSO apps and portal scope |
| Holidays (global), relative to the seed's today (D-DB-SEED-ROLLING-01): Victory Day (16 Dec) and Christmas Day (25 Dec) of every year whose date falls in today's month and the next two; Durga Puja (Bijoya Dashami) only on 2026-10-21 (lunar date, **owner to confirm**, owner row O) | `app.calendar_holiday` → `dw.v_calendar`, `dw.v_calendar_month` | working days (for a run in Oct 2026): Oct 25, Nov 26, Dec 26 |
| Monthly targets for today's month and the next two (Oct, Nov, Dec 2026 for a run in Oct 2026; D-DB-SEED-ROLLING-01): one batch per month (`app.route_target_batch.batch_uuid` = md5('gulshan-banani-target-YYYY-MM-01'), by superadmin1001); per route and month ≈ 200,000 cigarette sticks, 30,000 bidi sticks, 500 lighters (20 boxes), 300 dozen matches, spread over the plan SKUs with weights 1–3 by catalogue order, whole packs | `app.route_target_batch`, `app.route_monthly_target` (351 rows) → `dw.v_route_monthly_target` | Sales cards' %, "i" daily target (e.g. GUL-SR-2 Oct cigarette ≈ 200,000 / 25 = 8,000 sticks a day) |
| SKU units (all SKUs, 02_products.sql + V0103) | `app.sku.base_per_pack` cigarettes 20/10, bidi 25/12, lighters 25 (box), matches 1; `entry_unit_default` pack/pack/pack/dozen; `cfg.sale.qty_entry_unit`, `cfg.sale.allow_loose_base_units` false | SR app entry in packets → `qty_base` on memo lines → aggregates in sticks/pieces/dozens |
| Prices | `app.sku_price` per base unit (e.g. MaxR-20S 9.200 Tk a stick → 184 Tk a packet) | memo line amount = packets × packet price |

## Expected hops for the trace (one SR day)

1. Login as sr1001 on a Saturday: bundle has `GUL-SR-2` planned, 20 outlets with pins, 39 SKUs with pack sizes.
2. Sale at `GUL2-001` in packets: memo line `qty_entered` packets, `pack_factor` 20 or 10, `qty_base` sticks.
3. Sync: `app.memo`/`app.memo_line`, `app.visit` (distance, verdict) → outbox → `dw.fact_memo`, `dw.fact_visit`
   (`within_fence`, `completed` generated) → `dw.agg_daily_route`, `agg_daily_route_sku` (category via `dw.dim_product.category_code`, view `dw.v_daily_sku`),
   `agg_daily_outlet_sku`, `agg_daily_route_variant` (V0011, V0099; there is no `agg_daily_outlet_category`, qa db-qa-1).
4. Dashboard: Sales (Cigarette) = Σ qty_base of cigarette lines; ring % = sales / Σ daily target of GUL-SR-2 cigarette SKUs;
   CPR target outlets 20 (21 on dev with the smoke outlet); channel cards sum to successful calls.
