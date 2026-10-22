# Web portal dashboard — the owner's brief (Report Brief 14, "updated with business answers")

Source: `Dashboard_Brief.docx` uploaded by the owner on 2026-10-22 with the message in
`docs/34-owner-directive-2026-10-22.md`. The text below is the brief, converted to Markdown without
changing its content (section numbers, figures and SQL are the owner's). The six embedded screenshots are
filed next to this page as `dashboard-brief-1..6-*.webp`; the pop-ups that showed a rep's or an outlet's
name and phone number are painted over (grey box) before filing, per the rules in `../README.md`.
This brief and the screenshot `dashboard-national.webp` are the binding spec for the national dashboard
(ruling D-DASH-02 in `DECISIONS.md`).

| Item | Detail |
|---|---|
| Screen in Aron | Web portal > Dashboard (the landing page after login) |
| Sample analysed | Screenshot of 21 Oct 2026 around 12:00 noon, all Wings, all products. The day was still in progress, so achievement, submit and final-submit figures are low |
| What it shows | A live view, updated till the current time, of the selected day: sales vs daily target by category, CPR, successful calls and STD by channel, memo or STD by brand, final submit, login and Bikroy Joma status, geo-fencing and a map of field force locations |
| Main users | Management, Wing/Division heads, TSO, MIS. Each user sees only the area they have access to |
| Related briefs | STD & Memo (02), By Outlet (03), CPR / BSR (08), Download / Upload Log (06), Final Submit Log (07), Geo-Fencing (01), Online / Offline (12) |

## Dashboard as it looks today

Figure 1: `dashboard-brief-1-dashboard-2026-10-21.webp` (the sample analysed). The owner's own capture of
2026-10-22 is `dashboard-national.webp`.

## 1. Filters (top of the page)

- Location: Wing (10), Division (50), Territory (291), House (295), Zone (1,051), each multi-select; default is everything the user has access to.
- Product: Category, Segment, Brand, Variant; default All. These change the product-based cards (sales, CPR and BSR, channel calls, channel STD, brand breakdown).
- Date: one day; default today. All cards show that single day.
- House list: the current filter shows 295 houses against 239 in the reports, because some houses appear twice and some are deactivated. The new filter must list each active house once.
- Apply Filter: the cards refresh only when this is clicked. The Filter button at the top right hides or shows the filter panel.

Live dashboard: every card is calculated live. For today, each figure is "till time": everything synced up to the moment the page is loaded or refreshed. For a past date it shows that day's final figures. Targets on the "i" pop-up stay the full-day target, so achievement % rises through the day.

Rule for every card: the figures are for the selected location and date, and for the selected products where the card is product-based. Sales are the route totals by any user or device, latest memo version only, as in Brief 02.

## 2. Sales cards (row 1)

Four cards, one per category, each in its own unit. With all locations selected they show the national total for the day.

| Card | Unit | Sample: Total Sales | Sample: % | Daily target implied |
|---|---|---|---|---|
| Sales (Cigarette) | sticks | 1.33 Cr | 24.8% | about 5.36 Cr sticks |
| Sales (Bidi) | sticks | 18.64 Lakh | 23.2% | about 80 Lakh sticks |
| Sales (Lighter in Pcs) | pieces | 1.41 Lakh | 46.1% | about 3.06 Lakh pcs |
| Sales (Match in Dozen) | dozens | 11,326 | 14.7% | about 77,000 dozen |

- Total Sales = SUM(quantity) of the category's memo lines for the selected date, location and products: the day's STD (Brief 02). Shown in Lakh / Crore (1 Lakh = 100,000; 1 Crore = 10 million).
- Percentage (ring) = Total Sales / Daily Target x 100: achievement against the day's target. Check: the cigarette daily target implied by the sample (5.36 Cr) is close to the September daily average sale (1,354 million sticks / 26 days = 5.2 Cr).
- "i" button (daily target). All four sales cards have the "i" icon. Clicking it shows the Daily Target for that category, for the same location and product filter (national figure when everything is selected). Daily Target = monthly target / working days in the month (Fridays and holidays excluded), summed over the routes in the filter. In the new system the pop-up should show Daily Target, Sales so far and Remaining (Target minus Sales, or 0).
- Live. During the day the figure grows as SRs sell and sync. Offline memos appear only after sync (Brief 12).

```sql
SELECT p.category, SUM(l.quantity) AS total_sales
FROM   memo_header m JOIN memo_line l ON l.memo_id = m.memo_id
JOIN   product_dim p ON p.sku_id = l.sku_id
WHERE  m.is_latest_version AND m.memo_date = :date
  AND  m.route_id IN (:routes_in_filter) AND l.sku_id IN (:skus_in_filter)
GROUP BY p.category;
-- "i" pop-up: monthly target / working days of the month
SELECT t.category,
       SUM(t.monthly_target_qty) / wd.working_days AS daily_target
FROM   route_monthly_target t
JOIN   (SELECT COUNT(*) AS working_days FROM calendar
        WHERE month = date_trunc('month', :date)
          AND NOT is_friday AND NOT is_holiday) wd ON true
WHERE  t.month = date_trunc('month', :date)
  AND  t.route_id IN (:routes_in_filter) AND t.sku_id IN (:skus_in_filter)
GROUP BY t.category, wd.working_days;
```

## 3. Calls and status cards (row 2)

### 3.1 Live CPR

- Target Outlet (sample 4.62 Lakh) = outlets on the routes scheduled for the selected day (each route counts once per visit day, as in Brief 08). Manual (no-internet) routes and the dummy BR / Institutional Sales routes are excluded.
- Successful Calls (sample 1.06 Lakh) = outlets with at least one memo today on those routes.
- CPR % = Successful Calls / Target Outlet x 100 (sample 23.0%). The same formula as the CPR / BSR report, for one day. "Live" because it rises through the day.

### 3.2 By Channel Successful Call

- What it shows: today's successful calls split by the outlet's channel: GT, DCC, Astha, RCC, MT, HoReCa, each with its count and share of the total (sample GT 81,058 = 78.5%, Astha 18,192 = 17.6%).
- Calculation: COUNT(DISTINCT outlet with a memo today) grouped by the outlet's channel from the outlet master; % = channel / sum of all channels.
- Gap: the card stays by channel only (no "Other" group). In the sample the channels add up to 103,293, about 3,000 fewer than the 1.06 Lakh on the CPR card, so some calling outlets have no channel in the master. The new system must make channel mandatory in the outlet master, so the channels always add up to Successful Calls.

### 3.3 Final Submit Status

- Total Zone (1,051) = zones in the filter.
- Total Service Zone (1,045) = zones that need a final submit today (zones with SR routes working that day).
- Total Final Submit (0) = service zones whose TSO has done the Final Submit for the date; second submits are ignored (Brief 07).
- Remaining = Total Service Zone minus Total Final Submit (1,045). Ring % = Total Final Submit / Total Service Zone. At noon it is 0% because Final Submit happens at the end of the day; unsubmitted zones are auto-submitted at midnight.

### 3.4 Login / Submit Status

- Login Status: Target Route (6,933) = SR routes scheduled today; Successful Login (6,920) = those routes whose SR has checked in and downloaded (synced) today (both confirmed). % = 6,920 / 6,933 = 99.8%. Source: Download / Upload Log (Brief 06).
- Submit Status: Login successfully (6,920) = the routes that logged in; Submitted successfully (5) = routes that have done Bikroy Joma (sales upload) today. % = 5 / 6,920 = 0.1% at noon; it rises in the evening.

## 4. Channel and brand cards (row 3)

### 4.1 By Channel STD

- What it shows: today's cigarette sales (sticks) split by outlet channel, as a pie and a list with value and share.
- Check: the six channels add up to 133.16 Lakh = the 1.33 Cr on the Sales (Cigarette) card (GT 96.85 Lakh 72.7%, Astha 31.14 Lakh 23.4%, RCC 3.11 Lakh, DCC 1.95 Lakh, MT 7,870, HoReCa 3,200). Confirmed: this card is always cigarette only.
- Calculation: SUM(quantity) of today's memo lines grouped by the outlet's channel; % = channel / total.

### 4.2 Breakdown - STD / Memo

Figure 2: `dashboard-brief-2-breakdown-gear.webp` (the gear menu open, "By Memo > By Brand" active).

- What it shows: one bar per item for the selected day, as STD (quantity sold) or Memo (number of memos containing the item). The title shows the active measure. The sample shows Memo by Brand: Black Diamond 86,621, Marise 64,417, Special Abul Bidi 15,736, Maxim 9,753, and so on.
- Calculation: STD = SUM(quantity) by brand. Memo = COUNT(DISTINCT memo_id) with the brand, recounted at brand level (memos must not be added up across brands: one memo can hold several brands, Brief 02).
- Settings (gear icon): six choices, one active at a time: By STD - Segment, Brand or Variant; By Memo - Segment, Brand or Variant.
- Mixed units: in STD mode, sticks, pieces and dozens would sit on the same axis if all categories are shown. The STD view should be limited to one category, or show the unit per bar.

## 5. Geo cards (row 4)

### 5.1 Geo Fencing

- Total Calls (1.20 Lakh) = all completed calls today, with or without a memo (zero calls included, as in Total_Call, Brief 12). It is higher than the 1.06 Lakh successful calls by the zero-memo calls.
- Geo Validation (1.18 Lakh) = calls completed inside the outlet's geo-fence radius (20, 50 or 100 m).
- % = Geo Validation / Total Calls (98.08%). The rest are Force Sales outside the radius. Same rule as the Geo-Fencing Progression report (Brief 01).

### 5.2 FF Geo Location (national map)

Figure 3: `dashboard-brief-3-ff-geo-pins.webp` (zoomed in: one green pin per route, blue cluster counts; the route pop-up is redacted in the filed copy).

- What it shows: one pin per route (at the SR's latest position) on a Google map. Pins are grouped into clusters with a count when zoomed out.
- Pin colour = route geo-fencing % today: Red below 70%, Amber 70% to 90%, Green above 90%. "Filter by Color" shows only routes of the chosen colour(s).
- Route pop-up: SR Name, Contact, Territory Id and Name, Zone, Route, Date Time of the latest position, Miss Percentage (share of today's calls made outside the geo-fence, i.e. 100% minus the geo-fencing %).

### 5.3 Route details: View Live Map of SR Position

Figure 4: `dashboard-brief-4-route-map-details.webp` ("Map Details" page: header line SR, Territory, Zone, Route, Date; map; legend card; Target vs Achievement; CPR; time card).
Figure 5: `dashboard-brief-5-route-map-outlet-popup.webp` (the same map zoomed to a route: black, green and red outlet markers, the SR path line, an outlet pop-up, redacted in the filed copy).

Clicking a route pin opens the route's map for the day, with the SR, Territory, Zone, Route and Date in the header.

Map markers:

| Marker | Meaning |
|---|---|
| Sale Start Position (flag) | where the SR started selling (first call of the day) |
| Outlet Base Position (black) | outlet of the route the SR has not called yet |
| Geo Validation (green) | outlet called from inside its geo-fence |
| Photo Validation (red) | outlet called from outside its geo-fence (Force Sale); the SR must capture a photo on the device |
| SR Last Position | the SR's latest GPS position; a line joins the call positions in order |

- Outlet pop-up: Outlet Name, Contact, Code, Cluster Name, call Time and category-wise sales of the call (Cigarette, Bidi, Lighter, Match in their own units).

Cards below the map (route, selected day):

- Target vs Achievement: per category, route sales today / route daily target (sample Cigarette 26%, Bidi 0%, Lighter 60%, Match 68%), and Last Sale Position (the last outlet's place name). The same category-wise target vs achievement is also shown on the map.
- CPR: Target Outlet (route outlets scheduled today, 50), Successful Call (15), Geo Validation (calls inside the fence, 15); % = Successful Call / Target Outlet (30%).
- Time card: First Sale Time (09:41:01) and Last Sale Time (12:14:32); Total Sale Time = last minus first (2.55 hour); Average Sale Time = Total Sale Time / Successful Call (2.55 / 15 = 0.17 hour); Distance Covered (1196.97) = the length of the SR's path through the day's positions (unit to be confirmed, see section 7).

### 5.4 Outlet details: Sale Position (SR Hit / Miss)

Figure 6: `dashboard-brief-6-sale-position.webp` ("Sale Position" page: header "SR Hit / Miss Position (81.83 Meter)", base marker B, red line to the sale marker, sale pop-up with the force-sale photo and "View Image", "Map Indications" legend).

Clicking an outlet's details opens the Sale Position page for that outlet and day: Zone, Route, Outlet Code, Date, and the distance between the base position and the sale position in the header (e.g. 81.83 m).

- Base Position (B): the outlet's approved base location (Brief 11).
- Sale positions: where each memo was made, with its own marker: New Sale, Repeat Sale Position (yellow), Edited Sale Position (teal). A red line joins the base and the sale position.
- Sale pop-up: Sale Status, Outlet Code, distance, and the force-sale photo with a "View Image" button when the call was outside the fence.
- Distance: straight-line (haversine) distance between the base GPS and the call GPS, stored with the call; the call is a hit if distance <= the outlet's radius (20, 50 or 100 m), otherwise a miss (Force Sale).

## 6. Source data required

| Data set | Used by |
|---|---|
| Memo header and lines (latest version) | Sales cards, CPR, channel calls, channel STD, brand breakdown |
| Route daily target (from the monthly upload) | Sales card % and the "i" daily target pop-up |
| Route schedule and outlet-route assignment | Target Outlet, Target Route |
| Outlet master (channel, sub-channel) | By Channel cards |
| Call record (completed, within_fence) | Geo Fencing, Total Calls |
| Download / upload log | Login / Submit Status |
| Final submit log | Final Submit Status |
| User location log | FF Geo Location pins, SR Last Position, path and Distance Covered |
| Call record (GPS, distance, within_fence, photo) | route map markers, Sale Position page, force-sale photo |
| Outlet location history (base position) | Outlet Base Position, Sale Position page |
| Location master (house active flag) | filters: only active, de-duplicated houses |

Performance: the dashboard is live and opens for every user, so it should read from summary tables updated as each memo, call, login, Bikroy Joma and final submit syncs (event-driven or within a few minutes), not by querying the raw memo tables on each page load. The page should show a "last updated" time.

## 7. Business answers (confirmed)

- The "i" icon on all four sales cards shows the daily target.
- Daily target = monthly target / working days. The dashboard shows total category-wise sales, the national number when everything is selected.
- By Channel Successful Call stays by channel.
- By Channel STD is cigarette only.
- Breakdown settings: By STD or By Memo, each by Segment, Brand or Variant.
- FF Geo Location shows each route, coloured by geo-fencing (Red below 70%, Amber 70-90%, Green above 90%). Clicking a route opens its outlet map: black = not called yet, green = called inside the geo-fence, red = called outside (photo capture required). Outlet details show the base position, sale position and force-sale picture. Outlet click shows category-wise sales; category-wise target vs achievement shows on and below the map.
- Successful Login = check-in and download; Target Route = SR routes scheduled that day.
- The extra houses in the filter are duplicates or deactivated houses.
- The dashboard is live: every calculation is updated till the current time.

### Still open

- Route map "Distance Covered" (1196.97): is the unit metres or kilometres, and is it measured along the SR's GPS track or between calls? (Asked of the owner on 2026-10-22; the lead's default until answered is in `docs/34` s6.)
