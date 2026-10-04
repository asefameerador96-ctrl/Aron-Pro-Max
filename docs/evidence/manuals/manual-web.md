# ARON Web Dashboard (AKTC Web User Manual) - Consolidated Inventory

Source: "ARON Web USER MANUAL" (file name AKTC_Web_User_Manual.pdf), 49 pages, AKTCL's official manual for the current Apsis web build. Consolidated 2026-10-04 from five chunk transcripts plus spot re-checks of PDF pages 3, 40 and 41.

## Source coverage check

| Chunk | Pages | Present | Notes |
|---|---|---|---|
| web-p01-10.md | 1-10 | YES | read in full |
| web-p11-20.md | 11-20 | YES | read in full |
| web-p21-30.md | 21-30 | YES | read in full |
| web-p31-40.md | 31-40 | YES | read in full |
| web-p41-49.md | 41-49 | YES | read in full |

No range is missing. Page 1 is the cover and page 49 is the "THANK YOU" closing slide (no controls; not counted as screens). Pages 43 and 44 carry the wrong slide title "Login page" (copy-paste error in the manual); their real content is the Retailer Wholesale Outlet selection flow.

Conventions used below:
- Screen id = WEB-S-nn. Rule id = R-nnn (section C). Message id = M-nnn (section D). Entity id = E-nn (section E). Flow id = F-nn (section B). Unclear id = U-nn (section F).
- "p." = manual page. Bangla in this manual is Bengali script. Verbatim Bangla captions for every page are in Annex G (auto-extracted from the chunk transcripts). All screenshots were taken as a TSO-role web user (`tso-apsis`, and `TSO-1012` on pp.35-37) on Apsis test data (Wing "Apsis Wing" code 1418, Division "Apsis Division" 1417, Territory "Apsis Territory" 1416, House "Apsis House" 1415, Zone "Banani - Test" 6334, Route "Apsis RouteDaily" code 8), sample date 2026-04-12/13.
- The manual shows ONLY the TSO web portal. Whether other roles (AMO, SR, admin, WMO, HQ) have a web login, or a different menu, is not documented (U-01).
- The whole web product is online-only; "offline" is not applicable to any screen (R-092). Every instruction in the manual is a Bangla caption; there are no numbered steps, no on-screen toast/error/validation texts are captured anywhere in the manual (M-036).

## Cross-cutting UI patterns (apply to most screens; referenced instead of repeated)

- **P-FILTER-GEO (report style)**: five multi-select dropdowns in this order: Wing, Division, Territory, Distribution House, Zone. Each has a clear "X" and a chevron and shows "All Selected (n)" by default, n = count in the user's scope (1 for tso-apsis; Zone 4 for TSO-1012). Used on pp.4, 6, 8, 22, 23, 26-34, 36, 37, 39-46, 48.
- **P-FILTER-GEO (entry style)**: five single-select dropdowns Wing, Division, Territory, House, Zone, pre-filled with one value each (chevron only, no X). Label is "House", not "Distribution House". Used on pp.5, 7, 9-11, 18-21, 24, 25, 38, 47 (p.47 adds Route).
- **P-DATE**: date picker with calendar icon, format YYYY-MM-DD ("Choose Date" single or "Choose Date Range" start -> end). Exceptions: Sales Plan edit date shows 04/12/2026 (pp.10-11); Final Submit "Date of Data Entry" shows 2026/04/13 (p.20); month pickers show "April 2026" (pp.35, 37).
- **P-OUTPUT**: buttons "Get Data" (green, loads on-screen grid), "Get Excel" (blue/indigo, downloads .xlsx), "Print" (orange, p.25 only), "Download PDF" (green, p.6 only), "Download Report" (green, p.31), "Export Excel" (blue, p.16), "Filter" (orange p.3 / blue p.21 / green p.20), "View" (p.37, 46), "Apply" (p.35), "Submit" (p.5, 7, 20, 44). Red rectangles in the manual are annotation only.
- **P-TABLE**: dark-blue header row; pager "<  1  >"; where counted, "1-N of N items" and page-size dropdown "10 / page" (pp.37-44 style); some tables have per-column search icon (p.4) or sort arrows (p.39).
- **P-CAPTION**: one lilac Bangla caption per page is the only instruction text.

---

# A. SCREEN INVENTORY (in the order a user meets them)

## WEB-S-01  Login page  (p.2)
- Title: "Login page". Card heading "Login to your account", small text "Welcome back" (M-001, M-002). Product logo = stylised "A" (brand ARON).
- Purpose: authenticate a web user with their assigned ID and password. Role(s): all web users (only TSO shown).
- Entry: browser URL (not stated). Exit: Dashboard (WEB-S-03) on success.
- Controls: 
  - "User ID" - text input, sample `tso-apsis`; required (presumed, not stated); no format/length limit stated.
  - "Password" - password input, masked, eye/eye-slash show-hide toggle at right end; required (presumed).
  - "Remember me" - checkbox, default unchecked.
  - "Login" - full-width gradient button, submits.
- Absent (explicitly not on the screen): Forgot password link, language switcher, captcha, OTP field, role selector.
- Rules: R-001, R-002, R-003, R-004. Messages: none captured (no error text for bad credentials) - U-02.
- Data: reads/writes User account + session (E-01). Online-only. No hardware/permissions.

## WEB-S-02  Global shell: sidebar menu, top bar, logout  (pp.3-48, every logged-in page)
- Purpose: navigation container for all screens. Role(s): TSO shown.
- Sidebar (red-to-purple gradient, logo "A" top, round "<" collapse toggle at top edge of sidebar/content; function inferred). Items in order (chevron = expandable group):
  1. Dashboard (WEB-S-03)
  2. Retailer (group) > Browse Retailer (WEB-S-04)
  3. QC (group) > QC Entry (05), QC Report (Market & Warehouse) (06), Warehouse QC Entry (07), Route wise QC Report (08)
  4. Sales Plan (direct page, WEB-S-09/10)
  5. Products (group) > Browse Category (11), Browse Segment (12), Browse Brands (13), Browse Variant (14), Browse SKUs (15), Browse Product Tree (16)
  6. Route Planning (group) > Browse Routes (17)
  7. Data Entry (group) > Web Entry (18), Final Submit (19), Astha Web Entry (20)
  8. Reports (group) > STD Memo Report (21), SR Efficiency Report (22), DSS Report (23), DS-RRS Report (24), Route wise STD Report (25), Data Entry Log (26), Final Submit Log Report (27), Route wise Memo Report (28), Route wise BSR & CPR Report (29), By Outlet Report (30), Astha Report (31), GIGO Report (32), Loyalty Program - Diamond League Report (33; label wraps to two lines, truncated to "Loyalty Program - Diamond" on p.31)
  9. Target (group) > Set Target (34), Target Allocation Report (35), Target Approve List (36)
  10. Supervisory Module (group) > AMO Call Report (37)
  11. Outlet (group) > SR Outlets Reports (38), Outlet Approval Panel (39)
  12. Retailer Wholesale Outlet (direct, WEB-S-41)
  13. Credentials (direct, WEB-S-43)
  14. SR Device OTP (direct, WEB-S-44)
  15. Astha Gift Panel (group) > Astha Gift Choice Panel (45), Astha Gift Choice Report (46)
  - Red "Logout" button with logout icon at sidebar bottom (R-095).
- Top bar: page title (blue bold) at left; round user-avatar icon at right with the logged-in user ID underneath (`tso-apsis`, `TSO-1012`). Click behaviour of the avatar is not shown (U-03).
- Menu inconsistencies between screenshots (some omit Credentials/SR Device OTP/Astha Gift Panel, or Reports shows 9 vs 13 items): see U-04.
- Whether menu items are hidden per role/permission is not documented (U-01).

## WEB-S-03  Dashboard  (p.3)
- Title: "Dashboard". Purpose: landing KPI summary for the user's scope. Role: TSO shown. Entry: after login or sidebar Dashboard. Exit: any menu.
- Controls: orange "Filter" button (list icon), top right of content - date-range selection per caption; the popup/fields are not shown (U-05).
- Tiles (row 1, each: title, "Total Sales" number, donut ring with % centre, blue "i" info icon bottom-right whose content is not shown):
  1. "Sales (Cigarette)" 0 / 0.0%
  2. "Sales (Bidi)" 0 / 0.0%
  3. "Sales (Lighter in Pcs)" 0 / 0.0%
  4. "Sales (Match in Dozen)" 0 / 0.0%
- Row 2:
  5. "Live Strike Rate": "43" Target Outlet, "1" Successful Calls, ring "2.3%" with small green marker (1/43 = 2.3%; formula inferred).
  6. "By Channel Successful Call": rows "0 (0%)" + channel name in order GT Channel, DCC Channel, Astha Channel, RCC Channel, MT Channel, HoReCa; concentric radial chart (empty in sample).
  7. "Final Submit Status": counters "1 Total Zone", "1 Total Service Zone", "1 Remaining"; ring "0.0%" labelled "Total Final Submit" with value "0".
  8. "Login/Submit Status": "Login Status" bar 25.0% with "4 Target Route" / "1 Successful Login"; "Submit Status" bar 0.0% with "1 Login successfully" / "0 Submitted successfully" (icons: download-in-document, upload-in-document).
- Row 3 (cut off): 9. "By Channel STD" (rows "0 (0%) GT Channel" ...); 10. panel "Breakdown - STD /Memo" ("STD" dark, "Memo" light-blue toggle word; gear icon top-right; caption calls it "STD/Value/Memo Breakdown").
- Named only in the caption (not visible): "By Segment Contribution", "Sales Trend", "Geo Fencing", "FF Geo Location".
- Rules: R-011, R-014, R-015, R-016, R-017, R-018, R-019. Messages: M-031 (tile labels). No scope picker on page (scope derived).
- Data read: Sales by product family, Targets/Target Outlet/Target Route, Successful calls, Channels, Zones/Service Zones, Logins, Submits, Memos, STD, Segments, Geo-fence results, FF GPS locations (E-17, E-18, E-19, E-20, E-21). Online-only; "Live" implies near-real-time from synced app data.

## WEB-S-04  Browse Retailer  (p.4; in-app heading "Outlets")
- Purpose: list the territory's outlets and export to Excel. Entry: Retailer > Browse Retailer. Role: TSO.
- Filters: P-FILTER-GEO (report style) Wing, Division, Territory, Distribution House, Zone, all "All Selected (1)". Required: not stated.
- Buttons: "Get Data" (green) loads table; "Get Excel" (blue; the caption calls it "Export Excel") downloads Excel.
- Table columns (each header has a magnifier = per-column search): Zone Code, Zone Name, Outlet Name, Owner Name, Contact Number (11-digit 01xxxxxxxxx), Route Code, Route, Outlet Code (7-digit numeric in sample), Cluster Type ("Transit Hub"), Cluster Name ("Apsis Cluster"), Address (blank), NID (blank), TIN (blank), Trade License (blank), Latitude (e.g. 23.79...); further columns to the right are cut off (Longitude etc., U-06). Vertical scrollbar.
- Rules: R-020, R-021, R-011. Data: Outlet master (E-04), hierarchy (E-02), Route (E-06), Cluster (E-05). Sample data has duplicate outlet names/contacts (U-06). Online-only.

## WEB-S-05  QC Entry (Market QC)  (p.5)
- Purpose: enter Market QC fault counts per SKU for a route/date. Entry: QC > QC Entry.
- Filters: single-select Wing ("Apsis Wing"), Division, Territory, House, Zone ("Banani - Test"); "Select Route" dropdown ("Apsis RouteDaily"); "Choose Date" date picker (2026-04-12). Green "Submit" button.
- Grid: "SKU Name" column (rows MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, MaxDB-20S 20HL, Avon-20S, ARIS-A-20s, ARIS-O-20s ... scroll). Column group "Manufacturing Fault": Brand Mix Up | Cigarette Visual Fault | Damaged & Crushed Pack/Outer CBC | Outer, Pack or Stick missing | Others. Column group "Marketing Fault": Damp Cigarette stick | Stock damaged during transport | Shelf life expired stock | Spotting on Cigarette | Others (grid cut off at right, possibly more - U-07). Each cell number input default 0.
- Rules: R-022, R-023, R-024, R-025. Data written: Market QC entry (E-12). Read: SKU master (E-09), routes. No messages. Online-only.

## WEB-S-06  QC Report (Market & Warehouse)  (p.6)
- Purpose: download combined QC report. Entry: QC > QC Report (Market & Warehouse).
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-12 -> 2026-04-12). Buttons: "Get Excel" (blue), "Download PDF" (green, cloud icon). No on-screen table.
- Rules: R-027, R-059, R-094. Caption mentions only Excel (U-08). Data read: Market + Warehouse QC (E-12, E-13). Online-only.

## WEB-S-07  Warehouse QC Entry  (p.7; slide title "Warehouse QC Report", screen heading "Warehouse QC Entry")
- Purpose: enter warehouse QC fault counts per SKU for a zone/date. Entry: QC > Warehouse QC Entry.
- Filters: single-select Wing/Division/Territory/House/Zone; "Choose Date" single date (2026-04-12); NO route selector. Green "Submit".
- Grid: identical to WEB-S-05 (SKU Name; Manufacturing Fault x5; Marketing Fault x5; cells default 0).
- Rules: R-023, R-024, R-025, R-026. Data written: Warehouse QC entry (E-13). Caption says "date range" but control is single date (U-09). Online-only.

## WEB-S-08  Route Wise QC Report  (p.8)
- Purpose: download QC report by route. Entry: QC > Route wise QC Report.
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-12 -> 2026-04-12) + "QC Type" single-select (shown open; options "Market QC" (default shown) and "Warehouse QC"). Button "Get Excel" only (no PDF, no table).
- Rules: R-028, R-059. Data read: Market QC / Warehouse QC grouped by route. Online-only.

## WEB-S-09  Sales Plan (list / view state)  (p.9)
- Purpose: per zone, view enabled SKUs and PDA contact details. Entry: sidebar Sales Plan (direct).
- Filters: single-select Wing/Division/Territory/House/Zone; green "Get Data".
- Table columns: Zone Name, Wing, Division, Territory, House, Email (blank), Address (blank), PDA Contact No. (blank), Enabled SKUs (scrollable box of greyed read-only SKU chips: MaxB-20S, MaxR-10S, MaxWB-10S, Avon-20S, ARIS-A-20s, Max..., maybe LSCM-20S 20HL), Data Entry Date (2026-04-12), PDA (blank), Actions (blue pencil = edit).
- Rules: R-029, R-030. Exit: edit mode WEB-S-10.

## WEB-S-10  Sales Plan (edit mode and SKU picker)  (pp.10-11)
- Purpose: change a zone's enabled SKUs and contact fields. Entry: pencil icon on WEB-S-09.
- Edit state: Email, Address, PDA Contact No. become empty text inputs; Enabled SKUs becomes removable chips (each with "x") with dropdown arrow at right edge (the "marked arrow", p.10); Data Entry Date becomes a date input shown 04/12/2026 (MM/DD/YYYY; U-10); Actions become green circle tick (save) and red circle x (cancel); Get Data stays.
- SKU picker (opens from arrow): white card with scrollbar; parent node "SKU List" with collapse caret and tri-state checkbox (indeterminate when some children unchecked); children with checkboxes: MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S (checked), MaxDB-20S 20HL (unchecked), next item (probably Avon-20S, checked) and more below.
- Chips visible at p.11: "MaxWB-10S x", "Avon-20S x", "ARIS-A-20s x", "Max x" (clipped); at p.10: "MaxR-20S x", "MaxB-10S x", "ARIS-O-20s x", "ARIS-S-20s x" (clipped).
- Rules: R-029, R-030, R-031, R-032, R-033, R-034. Messages: none (no save confirmation documented). Data written: Sales Plan / zone enabled SKU set, zone Email/Address/PDA Contact No./Data Entry Date (E-08). Effect on SR/AMO apps implied, not stated. Online-only.

## WEB-S-11  Browse Category  (p.12)
- Entry: Products > Browse Category. Read-only table: Category Name | Status | Sort. Rows: Cigarette Active 1; Bidi Active 2; Lighter Active 3; Match Active 4. Status = green "Active" badge. Pager "< 1 >". No filters/search/export/add/edit.
- Rules: R-035, R-036, R-037, R-041. Data: Category (E-09a below in E-09).

## WEB-S-12  Browse Segment  (p.13)
- Entry: Products > Browse Segment. Columns: Category Name | Segment Name | Status | Sort. Rows: Cigarette/Medium/Active/3; Cigarette/Slim/4; Cigarette/Low/5; Bidi/Bidi/6; Lighter/Lighter/7; Match/Match/8. Pager. Read-only. Sort starts at 3 (U-11).

## WEB-S-13  Browse Brands  (p.14; caption says "Browse Brand")
- Columns: Category Name | Segment Name | Brand Name | Status | Sort. 17 rows, all Active: Cigarette/Medium/Maxim 1; Cigarette/Slim/Avon 2; Slim/ARIS 3; Cigarette/Low/Marise 4; Low/Black Diamond 5; Low/Sunmoon 6; Low/Supreme 7; Bidi/Bidi/Special Abul Bidi 8; Bidi/Existing Abul Bidi/ 42 No. Abul Bidi 9 (brand name contains slash alias "Existing Abul Bidi/ 42 No. Abul Bidi"); Bidi/Ananda Bidi 10; Bidi/Abul Bidi Gold 11; Bidi/Abul Bidi Style 12; Lighter/Lighter/Aster 13; Match/Match/Flame Box 14; Match/Match/Salmon 15; Cigarette/Slim/ESSE 16; Lighter/Lighter/MAX 17. Pager single page. Read-only.

## WEB-S-14  Browse Variant  (p.15)
- Columns: Category Name | Segment Name | Brand Name | Variant Name | Status | Sort. Rows visible (all Active): Maxim: Maxim Regular 1, Maxim Bolt 2, Maxim Wild Berry 2, Maxim Double Burst 3; Avon: Avon 3; ARIS: ARIS Apple 4, ARIS Original 5, ARIS Strawberry 6; Marise: Marise Special Blend 7; Black Diamond: Black Diamond Double Filter 8, Black Diamond Advanced 9; Sunmoon: Sunmoon American blend 10; Supreme: Supreme 11, Supreme Style 12, Supreme Fresh Max 13; ESSE (Slim): ESSE Change Mango 13, ESSE Prime 13; Bidi/Special Abul Bidi: Special Abul Bidi 25 (14). Further rows cut off; vertical scrollbar; pager not visible. Duplicate sort values (U-11). Read-only.

## WEB-S-15  Browse SKUs  (p.16)
- Controls: blue "Export Excel" button with cloud-download icon (top right; caption calls it "Excel Export") downloads SKU list.
- Columns: Product Name | SKU Name | Short Name | Pack Size | Pack Type | Sort | Outlet price | C&c price | Distributor price. Rows visible (Product / SKU / Short / Pack / Type / Sort / Outlet / C&c / Distributor): Maxim Regular MaxR-10S 10 HLP 1 8 8 7.935; MaxR-20S 20 HLP 2 8 8 7.935; Maxim Bolt MaxB-10S 10 3 8 8 7.935; MaxB-20S 20 4 8 8 7.935; Maxim Wild Berry MaxWB-10S 10 4 8 8 7.935; MaxWB-20S 20 4 8 8 7.935; Maxim Double Burst MaxDB-20S 20HL 20 HLP 4 8 8 7.935; Avon Avon-20S 20 5 7 7 6.945; ARIS Apple ARIS-A-20s 20 6 6.5 6.5 6.445; ARIS Original ARIS-O-20s 20 7 6.5 6.5 6.445; ARIS Strawberry ARIS-S-20s 20 8 6.5 6.5 6.445; Marise Special Blend MSB-10s 10 9 6.2 6.2 6.145; MSB-20s 20 10 6.2 6.2 6.145; Black Diamond Double Filter BDDF-10s 10 11 5.2 5.2 5.155; BDDF-20s 20 12 5.2 5.2 5.155; Black Diamond Advanced BDA-10s 10 13 5.2 5.2 5.155; BDA-20s 20 14 5.2 5.2 5.155 (more rows below; the DSS report p.24 also shows SM-Americ... 10s, i.e. more SKUs exist; the Route wise STD report shows 38 products). SKU Name equals Short Name in every visible row.
- Rules: R-035, R-038, R-039, R-040. Data: SKU (E-09). Online-only; file download.

## WEB-S-16  Browse Product Tree  (p.17; sidebar "Browse Product Tree", title "Browse Products Tree")
- Org-chart style tree on grey canvas: root "All Products"; four beige child cards each labelled "Category" with name Cigarette, Bidi, Lighter, Match and a blue "Expand" button. Expanded state not shown (presumably Segment > Brand > Variant > SKU). No filters/export. Rule R-035.

## WEB-S-17  Browse Routes  (p.18)
- Purpose: list AMOs and SRs assigned per route and "section" (zone). Entry: Route Planning > Browse Routes (only sub-item).
- Controls: single-select Wing/Division/Territory/House/Zone + green "Get Data". Result table NOT shown in the manual (U-12). Rule R-042. Role/assignment data (E-06, E-07).

## WEB-S-18  Web Entry  (p.19)
- Purpose: back-office manual entry of a route's day sales by SKU. Entry: Data Entry > Web Entry.
- Controls (screen order): "Date: 2026-04-13" (bold label + blue date text, no picker, read-only); single-select Wing/Division/Territory/House/Zone; "Select Classifications" multi-select with removable tags (shown "GT Channel x"); "Select Route" single-select with clear x (shown "Apsis Route-Daily"); "Target Outlet" numeric read-only (37); "Successful Call" numeric input (5); green "Save" (disk icon); blue "Brand Data" button (purpose not stated, U-13).
- Grid: "Sku Name" (read-only; MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, Avon-20S, ARIS-A-20s, ARIS-O-20s, ARIS-S-20s ... more) | "Issue" (number, default 0) | "Return" (number, default 0) | "Sale" (read-only 0, derived, formula not shown) | "Memos" (number, default 0) | group "GT Channel" with sub-column "GT" (input, empty; one sub-column per selected classification).
- Rules: R-043, R-044, R-045, R-046, R-047, R-048, R-049, R-052. No validation limits stated. Data written: Daily route sales entry (E-14). Flows: F-10. Online-only.

## WEB-S-19  Final Submit  (p.20)
- Purpose: finalise the day's sales per zone; delete a route's entered data. Entry: Data Entry > Final Submit.
- Elements: pink banner M-003 (Bangla, cut-off date warning); single-select Wing/Division/Territory/House/Zone + green "Filter"; table Route | SR Name | Status | Actions with rows: Apsis AMOAMO / SR Not Set / -- ; AMO-Apsis RouteAMO / SR Not Set / -- (twice); Apsis RouteDaily / SR - Testing Banani / exist / red "Delete Section Data"; Apsis Route 2Daily / SR - Testing Banani 2 / --; Apsis Route 3Daily / SR - Testing Banani 3 / --; Apsis Route 4Daily / SR - Testing Banani 3 / --. Pink notice M-004 under the table; "Date of Data Entry" date picker (2026/04/13); dark-blue "Submit" button.
- Rules: R-052, R-053, R-054, R-055, R-056. No confirm dialog or toast for Submit/Delete shown (U-14). Data: Final submit record (E-15), daily entry (E-14). Flow F-12. Online-only.

## WEB-S-20  Astha Web Entry  (p.21; in-app heading "Web Entry By Outlet")
- Purpose: web entry per outlet by SKU for Astha channel outlets. Entry: Data Entry > Astha Web Entry.
- Filters row 1: single-select Wing/Division/Territory/House/Zone. Row 2: "Route" dropdown (Apsis Route-Daily); "Channel" dropdown with X (pre-set "Astha Channel"); "Category" multi-select with X (shows "All items are selected."); "Date" picker (2026-04-13); blue "Filter" button.
- Grid: "Outlet" column (outlet name + code in parentheses, e.g. "shaiful (3362328)", "Bipu store (6658061)", "asd (3391607)", "Mashrur Store (7911468)", ... "Mashrur Store (3332258)") then SKU columns MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, MaxDB-20S 20HL, Avon-20S, "A..." (cut off, scrolls); each cell an empty input (focus state shown blue). No Save/Submit button visible (U-15).
- Rules: R-050, R-051. Data written: per-outlet per-SKU quantity (E-14b in E-14). Flow F-11. Online-only.

## WEB-S-21  STD Memo Report  (p.22)
- Purpose: download STD and Memo reports together or separately (Excel). Entry: Reports > STD Memo Report.
- Filters: P-FILTER-GEO (report) five "All Selected (1)"; "Choose Date Range" (2026-04-13 -> 2026-04-13); "Location" dropdown ("Wing"); "Date Grouping" ("Total"); "Category" multi (Cigarette); "Product Type" ("Category"); "Active Status" ("All"); "Select Products" multi ("All Selected (1)"); "Classification Type" ("Total"); "Report Type" ("STD MEMO"). Button "Get Excel" only. Option lists not shown (U-16).
- Rules: R-059, R-060, R-063, R-099. Data read: memos, STD, products (E-16, E-17, E-09).

## WEB-S-22  SR Efficiency Report  (p.23)
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-13 -> 2026-04-13). Button "Get Excel" only. No table. Metrics undefined (U-17). Rule R-059, R-060.

## WEB-S-23  DSS Report (Sales Summary)  (p.24)
- Filters: single-select Wing/Division/Territory/House/Zone; "Field Force Type" ("SR"); "Choose Date" (2026-04-13); "Category" multi ("All Selected (4)"). Buttons "Get Data" (green), "Get Excel" (blue).
- Grid: Route Code | Route (blue underlined link -> outlet-wise sales of that route) | SR Name | No of Outlets | No of Memos | SKU columns MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, Avon-20s, ARIS-A-20s, ARIS-O-20s, ARIS-S-20s, MSB-10s, MSB-20s, BDDF-10s, BDDF-20s, BDA-10s, BDA-20s, SM-Americ... (more to right). Sample row: 8 | Apsis RouteDaily | SR - Testing Banani | 36 | 5 | 1000, -, -, 1000, -, 500, 500, 500, 1000, 500, -, 500, 750, -, 500, -, 500. Yellow summary band: "Total" (36, 5), "DCC STD", "Gold STD", "RCC STD", "Platinum STD", "Diamond STD", "GT STD" (values equal the route row), "Silver STD" (more rows may follow).
- Rules: R-059, R-061, R-062, R-100. Flow F-15. Destination of the route link not shown (U-18).

## WEB-S-24  DS-RRS Report  (p.25)
- Filters: single-select Wing...Zone; "Field Force Type" ("SR"); "Choose Date" (2026-04-13); "Route" ("Apsis RouteDaily"); "Category" multi ("Cigarette"). Buttons "Get Data" (green), "Get Excel" (blue), "Print" (orange). No grid shown.
- Rules: R-058, R-100. Caption says "date range" but control is a single date (U-19). Print shows discount data in print view before Final Submit.

## WEB-S-25  Route Wise STD Report  (p.26; in-app heading "Route Wise Live STD Report")
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-12 -> 2026-04-12); "Category" multi ("All Selected (4)"); "Product Type" ("SKU"); "Active Status" ("All"); "Select Products" ("All Selected (38)"). No Location field though caption mentions it. Buttons "Get Data", "Get Excel".
- Grid: Wing_Code | Wing | Division_Code | Division | Territory_Code | Territory | House_Code | House | Zone_Code | Zone | Route_Code | Route | SKU columns (MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, M... more). Sample: 1418, Apsis Wing, 1417, Apsis Division, 1416, Apsis Territory, 1415, Apsis House, 6334, Banani - Test, 8, Apsis Route, 1000, 1500, 1500, 2000, 1000, 2000, 0. Pager "< 1 >".
- Rules: R-059, R-069, R-099.

## WEB-S-26  Data Entry Log  (p.27; in-app heading "Download Upload Log Report")
- Filters: P-FILTER-GEO (report) + "Choose Date" (2026-04-13). Buttons "Get Data", "Get Excel".
- Grid (two-level header): Wing | Wing Code | Division | Division Code | TERRITORY | TERRITORY Code | HOUSE | HOUSE Code | Zone | Zone Code | Route | Route Code | group "DOWNLOAD": MAX | MIN | Count (an "UPLOAD" group probably off-screen, U-20). Rows: AMO-Apsis Route/9 17:50:40/17:50:40/1; AMO-Apsis Route/9 blank; Apsis AMO/500050 blank; Apsis Route/8 16:57:25/17:35:50/31 (MAX earlier than MIN); Apsis Route 2/10 blank; Apsis Route 3/11 13:25:09/13:25:09/1; Apsis Route 4/12 13:24:51/13:24:51/1. Times HH:MM:SS 24h.
- Rule R-064. Purpose: login and sales-submit details per route (device download/upload events: first/last time, count). Data: Sync/session log (E-22).

## WEB-S-27  Final Submit Log Report  (p.28)
- Filters: P-FILTER-GEO (report) + "Choose Date" (2026-04-13) + "Submit Status" dropdown (options "Done", "Not Done"; shows "Not Done" greyed by default). Buttons "Get Data", "Get Excel".
- Grid: WING_CODE | WING | DIVISION_CODE | DIVISION | TERRITORY_CODE | TERRITORY | HOUSE_CODE | HOUSE | ZONE_CODE | ZONE | SUBMIT_STATUS | MAX_TIME | MIN_TIME | COUNT. Sample: 1418 Apsis Wing 1417 Apsis Division 1416 Apsis Territory 1415 Apsis House 6334 Banani - Test Not Done 14:26:05 14:26:05 1.
- Rules: R-057, R-059. Caption says "date range", control is single date (U-21).

## WEB-S-28  Route Wise Memo Report  (p.29)
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-13 -> 2026-04-13); "Category" multi (Cigarette); "Product Type" (Category); "Active Status" (All); "Select Products" (All Selected (1)). Buttons "Get Data", "Get Excel". No grid shown; caption mentions "Location" not on screen. Rules R-059, R-069.

## WEB-S-29  Route Wise BSR & CPR Report  (p.30; in-app heading "Route Wise Strike Rate & BSR Report")
- Filters identical to WEB-S-28. Buttons "Get Data", "Get Excel". No grid shown. Three different names for this report (U-22). Rule R-070.

## WEB-S-30  By Outlet Report  (p.31)
- Filters: P-FILTER-GEO (report); "Select Category" (X; Cigarette); "Product Type" ("Total"); "Active Status" ("All"); "Select Products" ("All Selected"); "Sub Channel" multi ("All Selected (9)"); "Report Type" multi ("All Selected (2)"); "Choose Date Range" (2026-04-13 -> 2026-04-13); "STD Criteria ('000)" = operator dropdown (">") + number ("0"); "Memo Criteria" = operator dropdown (">") + number ("0"); "Outlet Code" text, placeholder "Enter Outlet Code" (M-017). Small round "i" icon left of button; green "Download Report" button (download icon). No table.
- Rules: R-059, R-065, R-098. Caption says "Get Excel" and "that day's"; button is "Download Report" and a date range is offered (U-23).

## WEB-S-31  Astha Report  (p.32; in-app heading "Astha Reports")
- Filters: P-FILTER-GEO (report); "Year" (single, shows "2025"); "Quarter" (single, shows "Q-3 (Jul-Sep)", option format "Q-<n> (<Mon>-<Mon>)"); "Month" (multi, X, shows "All Months (3)"). Button "Get Excel". Rule R-066. Year default 2025 vs other pages 2026 (U-24).

## WEB-S-32  GIGO Report  (p.33; slide "Gigo Report")
- Filters: P-FILTER-GEO (report) + "Choose Date" (2026-04-13). Button "Get Excel". "GIGO" not expanded; content unknown (U-25). Rule R-067.

## WEB-S-33  Loyalty Program - Diamond League Report  (p.34)
- Filters: P-FILTER-GEO (report) + "Choose Date Range" (2026-04-13 -> 2026-04-13). Buttons "Get Data" (green) shows outlet-wise Loyalty Point on screen; "Get Excel". Table columns not shown (U-26). Rule R-068.

## WEB-S-34  Set Target  (p.35; in-app heading "Target Settings")
- Filters: multi-select Wing, Division, Territory ("All Selected (1)") only (no House/Zone); "Choose Month :-" month picker ("April 2026"); green "Apply"; blue "Download Sample"; collapsible blue bar "Upload Excel Panel Section" with ">" arrow (expanded content not shown). After Apply a manual route-wise input grid and a Save are implied but NOT shown (U-27).
- Rules: R-071, R-072, R-074. Data written: monthly target by route (E-18); uploaded Excel. Flow F-16. Role shown: TSO-1012.

## WEB-S-35  Target Allocation Report  (p.36)
- Filters: P-FILTER-GEO (report; Zone "All Selected (4)") + "Choose Date" (2026-04-13). Button "Get Excel". Rule R-075. Caption says "view" but only download (U-28).

## WEB-S-36  Target Approval List  (p.37; sidebar "Target Approve List", header "Target Approval List", screen heading "Target Approval Panel")
- Filters: P-FILTER-GEO (report; Zone "All Selected (4)"); "Choose Month :-" ("April 2026"); green "View".
- Table: Target Name | Product Type | Target Type | Start Date | End Date | Approval Status | Action. Row: "Phatherhat of April-2026" | variant | stt | 2026-04-01 | 2026-04-30 | WMO approval pending | green eye icon (view details) + blue download icon (download target file). Pager "< 1 >".
- Rules: R-073, R-074. Data: Target header/details (E-18). Flow F-17.

## WEB-S-37  AMO Call Report  (p.38)
- Filters: single-select Wing/Division/Territory/House/Zone; "Field Force Type" ("AMO"); "AMO" ("AMO-6334"); "Report Type" ("Summary"); "Choose Date" (2026-04-13); "Report By" radio: "By Date" (selected) / "By Month". Button "Get Excel". Rules: R-076, R-100. Data: AMO calls (E-19).

## WEB-S-38  SR Outlets Reports  (p.39; header "SR Outlet Report")
- Filters: P-FILTER-GEO (report); "Report Category" (shown open): "New Outlets" (default), "Close Outlets", "Info Changes"; "Date Range" (2026-04-01 -> 2026-04-16). Buttons "Get Data" (green), "Get Excel" (blue).
- Table (sortable columns except Latitude, Longitude, Status): first column clipped ("... Code", probably Division Code) | House | Zone Code | Zone | Route Code | Route | Cluster | Date | Outlet Name | Owner Name | Phone Number | Latitude | Longitude | Status. Sample: Apsis House, 6334, Banani - Test, 8, Apsis Route, Apsis Cluster, 2026-04-12, Alif Store, Alif, [phone], 23.793843, 90.404396, Approved. Footer: horizontal scrollbar, "1-1 of 1 items", pager, "10 / page".
- Rules: R-077, R-081, R-082, R-096, R-097. Flow F-20.

## WEB-S-39  Outlet Approval Panel  (pp.40-41; header "Outlet Approval Panel", screen heading "Firefly Outlets Reports")
- Filters: P-FILTER-GEO (report); "Outlet Type" single-select (placeholder-style grey "New Outlets"; options "New Outlets", "Close Outlets", "Info Changes"); NO date range on screen though caption says Report Category & Date range (U-29). Buttons "Get Data" (green), "Get Excel" (blue).
- Table: first column clipped (Division Code) | Territory | Territory Code | House | House Code | Zone | Zone Code | Route | Route Code | Cluster | Owner Name | Phone Number | Latitude | Longitude | Status | Actions (no Outlet Name or Date column). Rows: 1) 1416/1415/6334/8, Apsis Cluster, tewt, [phone], 23.7938108, 90.4043862, Pending, [Verify]; 2) New, [phone], 23.7937241, 90.4044049, Pending, [Verify]; 3) Alif, [phone], 23.7938429, 90.4043959, Verified, [Reject] red + [Approve] green. Footer "1-3 of 3 items", pager, "10 / page".
- Rules: R-077, R-078, R-079, R-081, R-082. Flow F-20. Data written: outlet request status (E-04b in E-04).

## WEB-S-40  Approve outlet request? (confirmation dialog)  (p.41)
- Modal over WEB-S-39: blue circled "?" icon, title "Approve outlet request?", body "Do you want to approve this request?", buttons "Yes, approve it" (green) and "Cancel" (blue). Messages M-005..M-008. Rule R-080. No success toast shown. (The caption writes "Yes, Approve it".) Equivalent dialogs for Verify and Reject are not shown (U-30).

## WEB-S-41  Retailer Wholesale Outlet  (pp.42-43; heading "Wholesale Retailers")
- Purpose: mark territory outlets as Wholesale in bulk. Entry: sidebar Retailer Wholesale Outlet (direct).
- Filters: P-FILTER-GEO (report); "Wholesale Status" single-select (options "No", "Yes"; Yes shown on p.42, No on p.43); "Outlet Search" text (placeholder "Enter Outlet Search", optional). Buttons "Get Data" (green), "Get Excel" (blue).
- Table: checkbox column with header select-all (indeterminate when partially ticked), Zone Code, Zone Name, Outlet Name, Owner Name, Contact Number, Route Code, Route, Outlet Code (7-digit or alphanumeric "DHK-344-004"), Cluster Type, Cluster Name, Address, "Geo Cla..." (probably Geo Classification; sample "Urban"), more columns off-screen. Pager "1-10 of 43", pages 1-5, "10 / page". Ticked rows highlighted light blue.
- Floating round basket button right of the table with green badge = live count of ticked outlets ("0" p.42, "3" p.43); clicking opens WEB-S-42.
- Rules: R-083, R-084, R-096, R-098. Data: Outlet wholesale flag (E-04). Flow F-21.

## WEB-S-42  Selected Outlets dialog  (p.44; title "Selected Outlets (3)")
- Modal with "x" close. Table: Zone Name | Route | Outlet Code | Outlet Name | Action ("Remove", red trash icon). Rows (sample): Banani - Test | Apsis RouteDaily | 1886742 | Mashrur Store; 2131717 bulbul store; 3332258 Mashrur Store. Blue "Submit" button completes marking as Wholesale. Rules R-084, R-085, R-086. No confirmation/toast shown (U-31).

## WEB-S-43  Credentials / Change Password  (p.45)
- Purpose: change own password. Entry: sidebar Credentials.
- Light-blue info box "Password Changing Guideline" with six bullets (M-009..M-014). Fields (all required, red asterisk, masked with show/hide eye): "Old Password *" (placeholder "Old Password"), "New Password *" ("New Password"), "Confirm Password *" ("Confirm Password"). Centered blue "Change Password" button. Decorative key watermark.
- Rules: R-005, R-006, R-007, R-008, R-009, R-010, R-003. No error/success texts shown (U-32). Data: credential, password history (E-01).

## WEB-S-44  SR Device OTP (SR OTP Panel)  (p.46)
- Purpose: let the TSO read the login OTP that an SR's device needs. Entry: sidebar SR Device OTP.
- Filters: P-FILTER-GEO (report). Button "View" (indigo, top right of filter panel). Table: Field Force Name | Username | Zone Code | Zone | OTP. Empty state: inbox icon + "No Data" (M-016). No route filter/column although caption says "according to route" (U-33). OTP length/expiry/refresh not shown.
- Rules: R-087, R-088. Data: Device login OTP (E-23). Flow F-23.

## WEB-S-45  Astha Gift Choice Panel  (p.47; heading "Astha Gift Choice")
- Purpose: choose a gift per Astha outlet. Entry: Astha Gift Panel > Astha Gift Choice Panel.
- Filters (single-select, pre-filled): Wing, Division, Territory, House, Zone, Route ("Apsis RouteDaily"). Button "Get Data".
- Table: clipped leading column | Wing Code | Division | Division Code | Territory | Territory Code | House | House Code | Zone | Zone Code | Route | Route Code | Outlet Code | Owner Name | Gift List (per-row dropdown). Rows: 3332258 Mashrur; 3363535 Rony Ahmed; 3841914 aatha "24 pcs Dinner Set"; 4896279 aatha "Ceiling Fan (56 inch)"; 5557774 jubu "24 pcs Dinner Set"; 8972308 jubu "27 pcs Dinner Set"; 6781440 test321 "Ceiling Fan (56 inch)"; 8607881 Mashrur "27 pcs Dinner Set". Open dropdown options: "Ceiling Fan (56 inch)", "27 pcs Dinner Set" (another row shows "24 pcs Dinner Set").
- No Save button visible (U-34). Rules: R-089, R-090. Data: Astha outlet, gift catalogue, outlet gift choice (E-24). Flow F-24.

## WEB-S-46  Astha Gift Choice Report  (p.48; heading "Astha Gift Choice")
- Filters: P-FILTER-GEO (report) + "Gift Status" single-select (options "Yes", "No"; Yes shown). Button "Get Excel" only. No table. Rule R-091. Meaning of Yes/No inferred (U-35).

---

# B. FLOW LIST (end-to-end workflows, ordered steps, alternates)

Note: the web manual contains NO sale/memo-print workflow (that is in the SR/AMO app manuals). The web equivalents are back-office entry (F-10, F-11), review (F-12, F-14, F-15) and finalisation (F-12).

**F-01 Login, session, logout** (p.2, 3, sidebar)
1. Open portal -> WEB-S-01. 2. Enter User ID and Password (optional: tick Remember me; toggle eye to reveal password). 3. Click Login -> WEB-S-03 Dashboard. Alt: bad credentials -> error text NOT documented (U-02). No forgot-password path exists on screen. 4. Logout via red Logout button in WEB-S-02. 5. Change own password -> F-22.

**F-02 View dashboard and filter by date range** (p.3): WEB-S-03 -> click orange Filter -> choose date range (popup not shown, U-05) -> tiles refresh. Scope is fixed by the user's assignment.

**F-03 Browse and export outlet list** (p.4): Retailer > Browse Retailer (WEB-S-04) -> set geography filters (default all in scope) -> Get Data -> (optional per-column search) -> Get Excel to download.

**F-04 Market QC entry** (p.5): QC > QC Entry (WEB-S-05) -> pick Wing/Division/Territory/House/Zone -> Select Route -> Choose Date -> enter per-SKU counts under Manufacturing Fault (5 reasons) and Marketing Fault (5 reasons) -> Submit. Alt: leave cells at 0 (default). Re-entry/overwrite/edit rules not documented (U-07).

**F-05 Warehouse QC entry** (p.7): QC > Warehouse QC Entry (WEB-S-07) -> pick geography to Zone -> Choose Date (no route) -> enter per-SKU counts -> Submit.

**F-06 QC reporting** (pp.6, 8): (a) QC Report: WEB-S-06 -> filters + date range -> Get Excel or Download PDF. (b) Route-wise: WEB-S-08 -> filters + date range -> QC Type (Market QC / Warehouse QC) -> Get Excel.

**F-07 Maintain Sales Plan (enabled SKUs and zone contact)** (pp.9-11): Sales Plan (WEB-S-09) -> select geography to Zone -> Get Data -> click pencil (WEB-S-10 edit mode) -> optionally fill Email, Address, PDA Contact No., Data Entry Date -> open Enabled SKUs dropdown arrow -> tick SKUs to sell / untick SKUs with no sales (or click "x" on a chip to remove) -> click green tick to save. Alt: red x to cancel (inferred); nothing saves before the tick (R-031).

**F-08 Browse product master** (pp.12-17): Products > Browse Category / Segment / Brands / Variant / SKUs / Product Tree (WEB-S-11..16). On SKUs, Export Excel downloads the SKU list. On Product Tree, Expand a category node to drill down (expanded state not shown). All read-only.

**F-09 Browse route assignments** (p.18): Route Planning > Browse Routes (WEB-S-17) -> filters -> Get Data -> list of AMO/SR assigned per route and section (result table not shown, U-12).

**F-10 Daily web entry for a route (non-Astha)** (p.19, p.20 constraint)
1. Data Entry > Web Entry (WEB-S-18). 2. Select geography (Wing..Zone) and Classifications (e.g. GT Channel). 3. Select Route FIRST (R-044). 4. Target Outlet appears read-only. 5. Enter Successful Call count. 6. Enter per SKU Issue, Return, Memos (Sale is derived; GT channel column per classification). 7. Click Save. Alt/blocked: date earlier than the cut-off -> not allowed, call support (R-052/053). Alt: re-opening a route that already has data: "exist" status on WEB-S-19 and Delete Section Data (F-12). Validation limits/messages not shown (U-13).

**F-11 Web entry for Astha outlets** (p.21): Data Entry > Astha Web Entry (WEB-S-20) -> geography -> Route -> Channel = Astha Channel -> Category (default all) -> Date -> Filter -> enter SKU quantities per outlet row -> save mechanism not shown (U-15). Rule: Astha outlets MUST use this menu (R-050).

**F-12 Check then Final Submit a zone/day, with delete alternate** (pp.20, 24, 25, 28)
1. (Advisory) check sales in DSS Report (F-15) and/or DS-RRS Print for discount data (R-055, R-058). 2. Data Entry > Final Submit (WEB-S-19). 3. Select geography/Zone, click Filter -> route table with SR Name and Status. 4. Pick "Date of Data Entry". 5. Click Submit (per Zone per day, R-054). Alt A: a route showing Status "exist" has red "Delete Section Data" -> removes entered data for that route/date (no confirm shown, U-14) then re-enter via F-10. Alt B: data older than cut-off date -> blocked, call support (M-003). Alt C: verify result via Final Submit Log Report (F-13). Whether Final Submit locks further edits is not stated (U-14).

**F-13 Verify final-submit status** (p.28): Reports > Final Submit Log Report (WEB-S-27) -> filters, Choose Date, Submit Status Done / Not Done -> Get Data (grid) or Get Excel.

**F-14 Generic report download pattern** (pp.22-34, 36, 38, 48): open Reports item -> set every filter ("select everything", R-059) -> click Get Excel (download) or Get Data (on-screen grid where the screen has it) or Print (DS-RRS) or Download Report (By Outlet) or Download PDF (QC Report). Individual reports: STD Memo (WEB-S-21), SR Efficiency (22), DSS (23), DS-RRS (24), Route wise STD (25), Data Entry Log (26), Final Submit Log (27), Route wise Memo (28), Route wise BSR & CPR (29), By Outlet (30), Astha (31), GIGO (32), Loyalty Diamond League (33), Target Allocation (35), AMO Call (37), SR Outlets (38), Astha Gift Choice Report (46).

**F-15 DSS drill-down by route** (p.24): WEB-S-23 -> Get Data -> view route-wise summary with Total and STD-class rows -> click the route name link -> outlet-wise sales of that route (target screen not shown, U-18) ; or Get Excel.

**F-16 Set monthly target** (p.35): Target > Set Target (WEB-S-34) -> Wing/Division/Territory -> Choose Month. Path A (manual): click Apply -> route-wise input grid -> enter targets -> Save. Path B (Excel): click Download Sample -> fill file -> expand "Upload Excel Panel Section" -> upload. Then approval (F-17). Grid, Save button and upload panel not shown (U-27).

**F-17 Track target approval** (p.37): Target > Target Approve List (WEB-S-36) -> geography + Choose Month -> View -> table (status e.g. "WMO approval pending") -> green eye = details, blue download icon = download target file. Approval itself (by WMO) is not a web screen in this manual.

**F-18 Target allocation report** (p.36): WEB-S-35 -> filters + Choose Date -> Get Excel.

**F-19 AMO call report** (p.38): Supervisory Module > AMO Call Report (WEB-S-37) -> geography -> Field Force Type (AMO) -> AMO -> Report Type (Summary) -> Report By (By Date / By Month) -> date -> Get Excel.

**F-20 Outlet request lifecycle (SR creates in app; web verifies/approves/rejects)** (pp.39-41)
1. SR creates New Outlet / Close Outlet / Info Change request in SR app (app manual; status Pending). 2. Outlet > Outlet Approval Panel (WEB-S-39) -> filters -> Outlet Type -> Get Data. 3. On a Pending row click Verify -> Verified (confirmation not shown for Verify, U-30). 4. On a Verified row click Approve -> dialog WEB-S-40 -> "Yes, approve it" -> Approved; or "Cancel" -> no change. Alt: click Reject on a Verified row -> Rejected (dialog/reason not shown, U-30). Pending rows cannot be approved/rejected directly (R-079). 5. Outlet > SR Outlets Reports (WEB-S-38) -> Report Category (New Outlets / Close Outlets / Info Changes) + Date Range -> Get Data / Get Excel to see the Approved/Rejected list.

**F-21 Mark outlets as Wholesale (3 steps)** (pp.42-44)
1. Retailer Wholesale Outlet (WEB-S-41) -> filters -> Wholesale Status = No -> optional Outlet Search -> Get Data. 2. Tick row checkboxes (or header select-all); badge shows live count. 3. Click floating basket -> Selected Outlets dialog (WEB-S-42) -> review; "Remove" any row; 4. Click Submit -> outlets flagged Wholesale. Alt: Wholesale Status = Yes lists current wholesale outlets; Get Excel exports; closing dialog with "x" keeps selection (implied). Unflagging wholesale outlets is not documented (U-36). Selection across pages not documented (U-37).

**F-22 Change password** (p.45): Credentials (WEB-S-43) -> read guideline -> enter Old, New, Confirm -> Change Password. Blocked by: <12 chars, missing lower/upper/digit, reuse of last 10, change within 24 h of previous change (R-005..R-008).

**F-23 SR device login OTP relay** (p.46): SR taps login on SR device (app manual) -> TSO logs into web -> SR Device OTP (WEB-S-44) -> filters -> View -> read OTP next to the SR's Field Force Name/Username -> tell the SR -> SR enters OTP on device. Alt: "No Data" empty state if no OTP pending/no rows.

**F-24 Astha gift selection and reporting** (pp.47-48): Astha Gift Panel > Astha Gift Choice Panel (WEB-S-45) -> geography + Route -> Get Data -> per Astha outlet pick Gift from Gift List dropdown (Ceiling Fan (56 inch) / 24 pcs Dinner Set / 27 pcs Dinner Set) -> (save mechanism not shown, U-34). Then Astha Gift Choice Report (WEB-S-46) -> filters -> Gift Status Yes/No -> Get Excel.

**F-25 Astha and loyalty reporting** (pp.32, 34): Astha Report (WEB-S-31): Year, Quarter, Month(s) -> Get Excel. Loyalty Diamond League (WEB-S-33): date range -> Get Data (view) / Get Excel.

**F-26 Monitor field sync/login activity** (pp.27, 3): Data Entry Log (WEB-S-26) -> Choose Date -> Get Data -> per-route Download MAX/MIN/Count (and presumably Upload); plus Dashboard Login/Submit Status tile (WEB-S-03).

---

# C. RULES REGISTER

Columns: id | rule | exact wording (verbatim where the manual prints it; otherwise "implied"/"observed") | page | screens.

| id | rule | exact wording / evidence | page | screens |
|---|---|---|---|---|
| R-001 | Users get an assigned User ID and password; no self-registration | Caption: "আপনাদের নির্দিষ্ট আইডি এবং পাসওয়ার্ড দিয়ে পোর্টালে লগইন করুন" (log in with your specific ID and password) | 2 | S-01 |
| R-002 | "Remember me" persists the login; default unchecked; duration unstated | checkbox "Remember me" (unchecked) | 2 | S-01 |
| R-003 | Password fields masked with show/hide eye toggle | eye/eye-slash icon | 2, 45 | S-01, S-43 |
| R-004 | Successful login lands on Dashboard | Caption p.3: "সফলভাবে লগইন করার পরে এই ড্যাশবোর্ডটি আপনারা দেখতে পাবেন" | 2, 3 | S-01, S-03 |
| R-005 | Password minimum length 12 (no maximum stated) | "Password must be at least twelve (12) characters." | 45 | S-43 |
| R-006 | Password needs lowercase, uppercase, number (special char not required) | "Password must contain at least one lowercase letter, one uppercase letter, and one number." | 45 | S-43 |
| R-007 | No reuse of last 10 passwords | "You cannot use your previously 10 times used password." | 45 | S-43 |
| R-008 | 24-hour minimum between password changes | "You cannot change your password within 24 hours of last change." | 45 | S-43 |
| R-009 | Old, New and Confirm password all required | red asterisks on "Old Password *", "New Password *", "Confirm Password *" | 45 | S-43 |
| R-010 | Confirm must equal New (implied, not printed) | implied | 45 | S-43 |
| R-011 | Data visible is scoped to the user's assignment; filter lists show only own scope (counts (1), (4), (9), (2), (3)) | observed counters; no scope picker on Dashboard | 3, 4, 31-48 | S-03, S-04, all report screens |
| R-012 | Geography hierarchy Wing > Division > Territory > (Distribution) House > Zone > Route; codes shown next to names | observed (Wing 1418 > Division 1417 > Territory 1416 > House 1415 > Zone 6334 > Route 8) | all | all |
| R-013 | Report/browse screens use multi-select defaulting to all in scope ("All Selected (n)"); entry/plan screens use single-select pre-filled | observed | all | all filter screens |
| R-014 | Dashboard filterable by date range | "ডেট রেঞ্জ সিলেক্ট করে ফিল্টার করার অপশন রয়েছে" | 3 | S-03 |
| R-015 | Product families tracked separately with own units: Cigarette, Bidi, Lighter in Pcs, Match in Dozen | tile titles "Sales (Cigarette)", "Sales (Bidi)", "Sales (Lighter in Pcs)", "Sales (Match in Dozen)" | 3 | S-03 |
| R-016 | Six channels: GT, DCC, Astha, RCC, MT, HoReCa | tile "By Channel Successful Call" | 3 | S-03 |
| R-017 | Live Strike Rate % = Successful Calls / Target Outlet (inferred from 1/43=2.3%) | inferred | 3 | S-03 |
| R-018 | Login Status % = Successful Login / Target Route (1/4=25.0%); Submit Status tracks "Login successfully" vs "Submitted successfully" (inferred) | inferred | 3 | S-03 |
| R-019 | Final Submit Status shows Total Zone, Total Service Zone, Remaining and Total Final Submit % | tile labels | 3 | S-03 |
| R-020 | Browse Retailer lists territory outlets; Get Data loads, Get Excel exports | Caption: "Browse Retailer অপশনে ক্লিক করে ... টেরিটোরি এর আউটলেট লিস্ট পাবেন। Export Excel বাটনে ক্লিক করে এক্সেল ফাইল আকারে ডাউনলোড" | 4 | S-04 |
| R-021 | Outlet table has per-column search; outlet carries optional Address, NID, TIN, Trade License | magnifier icons; blank columns | 4 | S-04 |
| R-022 | Market QC is keyed by zone + route + date, per SKU | Caption: "জোন, রুট এবং ডেট সিলেক্ট করে Qc Entry দিতে পারবেন ... SKU wise QC এন্ট্রি" | 5 | S-05 |
| R-023 | Two fault categories, five reasons each (Manufacturing: Brand Mix Up, Cigarette Visual Fault, Damaged & Crushed Pack/Outer CBC, Outer, Pack or Stick missing, Others; Marketing: Damp Cigarette stick, Stock damaged during transport, Shelf life expired stock, Spotting on Cigarette, Others) | grid headers | 5, 7 | S-05, S-07 |
| R-024 | QC cells are number inputs defaulting to 0; limits not stated | observed | 5, 7 | S-05, S-07 |
| R-025 | Submit button saves the QC | "Submit বাটন ক্লিক করে QC জমা দিতে পারবেন" | 5, 7 | S-05, S-07 |
| R-026 | Warehouse QC is per zone + date (no route) | screen has no route field | 7 | S-07 |
| R-027 | QC Report (market+warehouse) by geography and date range; Excel and PDF | buttons "Get Excel", "Download PDF" | 6 | S-06 |
| R-028 | Route Wise QC Report: QC Type = Market QC or Warehouse QC; Excel only | dropdown options | 8 | S-08 |
| R-029 | Sales Plan decides which SKUs are enabled (sold) per zone: select SKUs that will sell, disable SKUs with no sales | Caption p.11: "যেসকল SKU গুলো সেলস চলবে সেগুলো সিলেক্ট করে দিতে পারবেন, ... যেসকল SKU এর সেলস নেই সেগুলো Disable করে দিতে পারবেন" | 9, 11 | S-09, S-10 |
| R-030 | Edit via pencil; green tick = save; red x = cancel (inferred); SKU added via arrow dropdown, removed via chip "x" | Caption p.9: "Action বাটন থেকে ইডিট আইকন এ ক্লিক করতে হবে" | 9-11 | S-09, S-10 |
| R-031 | Changes not saved until tick clicked | "টিক বাটনে ক্লিক করতে হবে সেভ করার জন্য" | 11 | S-10 |
| R-032 | Zone row is the unit of save | observed single row | 11 | S-10 |
| R-033 | SKU picker is a checkable tree: parent "SKU List" tri-state, SKU children | observed | 11 | S-10 |
| R-034 | Zone-level Email, Address, PDA Contact No., Data Entry Date are editable text/date fields | observed | 10, 11 | S-10 |
| R-035 | Product hierarchy All Products > Category > Segment > Brand > Variant > SKU | pages 12-17 | 12-17 | S-11..S-16 |
| R-036 | Product master pages are read-only for the TSO (no add/edit/delete controls) | observed | 12-17 | S-11..S-16 |
| R-037 | Categories: Cigarette(1), Bidi(2), Lighter(3), Match(4) | table | 12 | S-11 |
| R-038 | SKU list downloadable as Excel | "Excel Export অপশনে ক্লিক করে SKU List ডাউনলোড" | 16 | S-15 |
| R-039 | SKU has three prices: Outlet price, C&c price, Distributor price (up to 3 decimals; unit undefined) | columns | 16 | S-15 |
| R-040 | SKU has Pack Size (10/20) and Pack Type (HLP); name suffix "-10S/-20S" | columns | 16 | S-15 |
| R-041 | Status shown as "Active" badge; Sort = display order | tables | 12-15 | S-11..S-14 |
| R-042 | Browse Routes lists AMO and SR assigned per route and section | "রুট ও সেকশন অনুযায়ী Assign কৃত AMO এবং SR এর লিস্ট" | 18 | S-17 |
| R-043 | Web entry is per route, per date | Caption p.19 | 19 | S-18 |
| R-044 | Route must be selected first | "সর্বপ্রথম Route নির্বাচন করতে হবে" | 19 | S-18 |
| R-045 | Successful Call count must be provided | "Successful Call এর সংখ্যা প্রদান করতে হবে" | 19 | S-18 |
| R-046 | Enter data SKU by SKU: Issue, Return, Memos | "প্রতিটি SKU এর জন্য Issue, Return, Memos এ মোট বিক্রয় কত ছিল সেগুলো input দিতে হবে" | 19 | S-18 |
| R-047 | Target Outlet is system-supplied, read-only | greyed field (37) | 19 | S-18 |
| R-048 | Sale column is read-only/derived (formula not shown) | observed | 19 | S-18 |
| R-049 | Entry order: Route -> Successful Call -> per-SKU -> Save | caption order | 19 | S-18 |
| R-050 | Astha outlets must use the Astha Web Entry menu | "শুধুমাত্র Astha আউটলেটে Web Entry এর জন্য Astha Web Entry মেন্যুটি ব্যাবহার করতে হবে" | 21 | S-20 |
| R-051 | Astha web entry is outlet-by-SKU grid per route, channel, category, date | grid | 21 | S-20 |
| R-052 | No sales data entry before the cut-off date | "আপনি 2026-04-13 তারিখের পূর্বের কোন সেলস ডাটা এন্ট্রি করতে পারবেন না।" (cut-off date value is date-specific; mechanism undocumented) | 20 | S-18, S-19 |
| R-053 | Back-dated data requires phoning support | "যদি এর পূর্বের কোন ডাটা এন্ট্রি করার প্রয়োজন হয়, অনুগ্রহ করে সাপোর্টে ফোন দিন।" | 20 | S-19 |
| R-054 | Final Submit is per Zone per day, done after the day's sales work completes | "প্রতিটি Zone এর Final Submit এর জন্য Zone সিলেক্ট করে Submit বাটনে ক্লিক করে প্রতিদিনের বিক্রয় সাবমিট" | 20 | S-19 |
| R-055 | Check DSS Report before Final Submit | "Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit" | 20 | S-19, S-23 |
| R-056 | "Delete Section Data" only offered for a route whose Status is "exist" | red button only in the "exist" row | 20 | S-19 |
| R-057 | Final Submit status is Done / Not Done per zone/date with first/last time and count | columns SUBMIT_STATUS, MAX_TIME, MIN_TIME, COUNT | 28 | S-27 |
| R-058 | DS-RRS Print shows discount data in print view before Final Submit | "ফাইনাল সাবমিট এর পূর্বে Print বাটন থেকে প্রিন্ট View এ ডিসকাউন্ট এর ডাটা দেখতে পারবেন" | 25 | S-24 |
| R-059 | Select all filters before Get Excel/Get Data | "সকল কিছু নির্বাচন করার পর Get Excel অপশনে ক্লিক করতে হবে" | 22, 23, 33, 36, 37, 39, 40 | report screens |
| R-060 | Output of these reports is Excel only (no on-screen grid): STD Memo, SR Efficiency, Route wise Memo, Route wise BSR & CPR, By Outlet, Astha, GIGO, Target Allocation, AMO Call, Astha Gift Choice Report, Route wise QC | observed | 22-33, 36, 38, 48 | S-21, 22, 28, 29, 30, 31, 32, 35, 37, 46, 08 |
| R-061 | DSS is single-date, route-wise Sales Summary; Get Data shows grid; route name is a link to outlet-wise sales | Caption p.24 | 24 | S-23 |
| R-062 | DSS grid has Total and STD-class rows: DCC STD, Gold STD, RCC STD, Platinum STD, Diamond STD, GT STD, Silver STD | grid | 24 | S-23 |
| R-063 | STD and Memo reports can be viewed together or separately (Report Type default "STD MEMO") | Caption p.22 "একত্রে অথবা আলাদা করে" | 22 | S-21 |
| R-064 | Data Entry Log shows per route per date download and upload (login/submit) MIN/MAX time and count | Caption p.27 | 27 | S-26 |
| R-065 | By Outlet filters: STD Criteria ('000) and Memo Criteria are operator + number (default "> 0"); Outlet Code optional | observed | 31 | S-30 |
| R-066 | Astha report uses Year + Quarter (format "Q-3 (Jul-Sep)") + Month(s) (3 per quarter), not a date range | observed | 32 | S-31 |
| R-067 | GIGO report is single-date | "Choose Date" | 33 | S-32 |
| R-068 | Loyalty Diamond League is outlet-wise loyalty points over a date range, viewable and downloadable | Caption p.34 | 34 | S-33 |
| R-069 | Route wise STD/Memo reports filter by Category, Product Type (Category or SKU), Active Status, Select Products | observed | 26, 29 | S-25, S-28 |
| R-070 | Route wise BSR & CPR report gives route-wise BSR/CPR and Strike Rate | Caption p.30 | 30 | S-29 |
| R-071 | Targets are monthly and entered route-wise | "মাসিক সেলস টার্গেট ... রুট অনুযায়ী" | 35 | S-34 |
| R-072 | Two target entry methods: Apply -> manual input -> save, or Download Sample -> fill -> upload | Caption p.35 | 35 | S-34 |
| R-073 | A target covers a full calendar month (start 2026-04-01, end 2026-04-30) | row | 37 | S-36 |
| R-074 | Targets pass an approval chain including WMO; status e.g. "WMO approval pending" | row | 37 | S-34, S-36 |
| R-075 | Target Allocation Report is single-date Excel | observed | 36 | S-35 |
| R-076 | AMO Call Report: Report By = By Date or By Month, Report Type Summary | observed | 38 | S-37 |
| R-077 | Outlet requests come in three categories: New Outlets, Close Outlets, Info Changes | dropdown options | 39, 40 | S-38, S-39 |
| R-078 | Two-step approval: Verify, then Approve or Reject | Caption p.40 | 40 | S-39 |
| R-079 | Verify button only on Pending rows; Reject and Approve only on Verified rows | observed | 40, 41 | S-39 |
| R-080 | Approve requires confirmation; Cancel aborts | dialog "Approve outlet request?" / "Do you want to approve this request?" | 41 | S-40 |
| R-081 | Outlet requests carry latitude/longitude (6-7 decimals), phone (11-digit 01...), owner, cluster, route | table | 39-41 | S-38, S-39 |
| R-082 | Outlet request statuses: Pending, Verified, Approved, (Rejected) | observed | 39-41 | S-38, S-39 |
| R-083 | To mark wholesale: Wholesale Status = No, Get Data, tick outlets, open basket, Submit | Caption p.42-44 | 42-44 | S-41, S-42 |
| R-084 | Basket badge and dialog title show live selected count | "Selected Outlets (3)" | 42-44 | S-41, S-42 |
| R-085 | Remove only drops from selection (implied) | "Remove" | 44 | S-42 |
| R-086 | Submit completes the wholesale flagging | "Submit অপশনে ক্লিক করে ... Wholesale করার কাজ Complete করতে হবে" | 44 | S-42 |
| R-087 | SR device login OTP: SR requests login on device, TSO reads OTP in portal and relays it | Caption p.46 | 46 | S-44 |
| R-088 | OTP list requires filters then View | observed | 46 | S-44 |
| R-089 | TSO selects the gift for each Astha outlet; one gift per outlet; route must be selected | Caption p.47 | 47 | S-45 |
| R-090 | Gift catalogue observed: "Ceiling Fan (56 inch)", "24 pcs Dinner Set", "27 pcs Dinner Set" | dropdown | 47 | S-45 |
| R-091 | Astha Gift Choice Report filtered by Gift Status Yes/No | Caption p.48 | 48 | S-46 |
| R-092 | All web screens online-only; no offline behaviour documented | observed | all | all |
| R-093 | Date display formats differ by control (YYYY-MM-DD, MM/DD/YYYY, YYYY/MM/DD, "Month YYYY") | observed | 10, 11, 20, 35, 37 | S-10, S-19, S-34, S-36 |
| R-094 | Export action labelled inconsistently (Get Excel / Export Excel / Excel Export / Download Report) | observed | 4, 6, 8, 16, 31 | S-04, S-06, S-08, S-15, S-30 |
| R-095 | Logout via sidebar red button | observed | 3+ | S-02 |
| R-096 | Pagination: "<  1  >"; with counts "1-N of N items" and "10 / page" on pp.37-44 | observed | 12-14, 26, 37-44 | S-11..S-13, S-25, S-36, S-38, S-39, S-41 |
| R-097 | SR Outlets report table columns are sortable (not Latitude/Longitude/Status) | arrows | 39 | S-38 |
| R-098 | Outlet Code (p.31) and Outlet Search (p.42) are optional free-text filters | placeholders | 31, 42 | S-30, S-41 |
| R-099 | Active Status filter on product-based reports (value "All") | observed | 22, 26, 29, 30, 31 | S-21, S-25, S-28, S-29, S-30 |
| R-100 | Field Force Type filter (SR on DSS/DS-RRS, AMO on AMO Call Report) | observed | 24, 25, 38 | S-23, S-24, S-37 |

---

# D. MESSAGES REGISTER (user-visible texts, verbatim)

The manual captures NO toast, success, error, validation or empty-state message other than those listed; M-036 records that absence. Manual captions (instructions) are not product messages and are in Annex G.

| id | message (verbatim) | English meaning | screen | page |
|---|---|---|---|---|
| M-001 | Welcome back | greeting above login | S-01 | 2 |
| M-002 | Login to your account | login card heading | S-01 | 2 |
| M-003 | আপনি 2026-04-13 তারিখের পূর্বের কোন সেলস ডাটা এন্ট্রি করতে পারবেন না। যদি এর পূর্বের কোন ডাটা এন্ট্রি করার প্রয়োজন হয়, অনুগ্রহ করে সাপোর্টে ফোন দিন। | You cannot enter any sales data from before 2026-04-13. If earlier data must be entered, please call support. (pink banner) | S-19 | 20 |
| M-004 | Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit | advisory under the route table | S-19 | 20 |
| M-005 | Approve outlet request? | dialog title | S-40 | 41 |
| M-006 | Do you want to approve this request? | dialog body | S-40 | 41 |
| M-007 | Yes, approve it | confirm button | S-40 | 41 |
| M-008 | Cancel | dismiss button | S-40 | 41 |
| M-009 | Password Changing Guideline | info box heading | S-43 | 45 |
| M-010 | Password must be at least twelve (12) characters. | rule text | S-43 | 45 |
| M-011 | Password must contain at least one lowercase letter, one uppercase letter, and one number. | rule text | S-43 | 45 |
| M-012 | You cannot use your previously 10 times used password. | rule text | S-43 | 45 |
| M-013 | You cannot change your password within 24 hours of last change. | rule text | S-43 | 45 |
| M-014 | An example for illustration purposes is provided below: / PhuTysYsd623gB | example password line and value | S-43 | 45 |
| M-015 | Old Password / New Password / Confirm Password (placeholders; labels "Old Password *", "New Password *", "Confirm Password *") | input placeholders | S-43 | 45 |
| M-016 | No Data | empty-state of OTP table | S-44 | 46 |
| M-017 | Enter Outlet Code | placeholder | S-30 | 31 |
| M-018 | Enter Outlet Search | placeholder | S-41 | 42, 43 |
| M-019 | All Selected (n) (e.g. "All Selected (1)", "(4)", "(9)", "(2)", "(38)") and "All Selected" | multi-select default text | most filter screens | 4, 6, 8, 22+ |
| M-020 | All items are selected. | Category default text on Astha Web Entry | S-20 | 21 |
| M-021 | All Months (3) | Month default text | S-31 | 32 |
| M-022 | SR Not Set | no SR assigned to the route | S-19 | 20 |
| M-023 | exist  /  -- | Status: data exists for route/date  /  none | S-19 | 20 |
| M-024 | Delete Section Data | red button label | S-19 | 20 |
| M-025 | Selected Outlets (3) | dialog title with live count | S-42 | 44 |
| M-026 | Done  /  Not Done | Submit Status options | S-27 | 28 |
| M-027 | Pending  /  Verified  /  Approved | outlet request status values | S-38, S-39 | 39, 40 |
| M-028 | WMO approval pending | target approval status | S-36 | 37 |
| M-029 | 1-N of N items ; 10 / page | pager text | S-36, S-38, S-39, S-41 | 37-44 |
| M-030 | Yes / No | Wholesale Status options (S-41) and Gift Status options (S-46) | S-41, S-46 | 42, 48 |
| M-031 | Dashboard tile labels: "Total Sales", "Live Strike Rate", "Target Outlet", "Successful Calls", "By Channel Successful Call", "Final Submit Status", "Total Zone", "Total Service Zone", "Remaining", "Total Final Submit", "Login/Submit Status", "Login Status", "Target Route", "Successful Login", "Submit Status", "Login successfully", "Submitted successfully", "By Channel STD", "Breakdown - STD /Memo" | tile text | S-03 | 3 |
| M-032 | Remove | dialog row action | S-42 | 44 |
| M-033 | Verify / Reject / Approve | row action buttons | S-39 | 40, 41 |
| M-034 | Active | status badge | S-11..S-14 | 12-15 |
| M-035 | Expand | tree node button | S-16 | 17 |
| M-036 | (Absence) No success toast, error text, validation text, loading text, confirmation for Verify/Reject/Submit/Delete/Save, or login failure text is shown anywhere in the manual | - | all | all |
| M-037 | Select option values: QC Type "Market QC" / "Warehouse QC"; Report Category / Outlet Type "New Outlets" / "Close Outlets" / "Info Changes"; Report By "By Date" / "By Month"; Wholesale Status "No" / "Yes" | dropdown/radio values | S-08, S-38, S-39, S-37, S-41 | 8, 38-40, 42 |
| M-038 | Placeholder-style grey text "Not Done" (Submit Status default) and "New Outlets" (Outlet Type default) | default display | S-27, S-39 | 28, 40 |


# E. DATA ENTITIES AND FIELDS REGISTER

(R = read, W = write. All entities are server-side; the web is online-only.)

| id | entity | fields seen | screens |
|---|---|---|---|
| E-01 | User account / credential / session | User ID (e.g. tso-apsis, TSO-1012), password (hashed), Remember-me session, password history (last 10), last-password-change timestamp | S-01 (R/W), S-02, S-43 (W) |
| E-02 | Geography hierarchy | Wing (code 1418), Division (1417), Territory (1416), Distribution House / House (1415), Zone / Service Zone / "Section" (6334); names and codes at every level; per-user scope counts | all filter screens (R) |
| E-03 | Channel / classification | GT Channel, DCC Channel, Astha Channel, RCC Channel, MT Channel, HoReCa (dashboard); Sub Channel (9 values, unnamed); Classification Type (Total...); outlet class STD groups DCC, Gold, RCC, Platinum, Diamond, GT, Silver | S-03, S-18, S-20, S-21, S-23, S-30 (R) |
| E-04 | Outlet (master and requests) | Outlet Name, Owner Name, Contact/Phone Number, Outlet Code (7-digit or "DHK-344-004"), Zone Code/Name, Route Code/Route, Cluster Type, Cluster Name, Address, NID, TIN, Trade License, Latitude, Longitude, Geo Classification ("Urban"), Wholesale Status (Yes/No), Request category (New Outlets/Close Outlets/Info Changes), request Status (Pending/Verified/Approved/Rejected), request Date | S-04 (R), S-38 (R), S-39 (R/W status), S-40 (W), S-41 (R/W wholesale), S-42 (W), S-45, S-30 (R) |
| E-05 | Cluster | Cluster Type ("Transit Hub"), Cluster Name ("Apsis Cluster") | S-04, S-38, S-39, S-41 (R) |
| E-06 | Route | Route Code (8, 9, 10, 11, 12, 500050), Route name ("Apsis RouteDaily", "Apsis Route-Daily", "Apsis Route 2", "AMO-Apsis Route", "Apsis AMO"), Target Outlet count, assigned AMO/SR | S-05, S-08, S-17, S-18, S-19, S-20, S-23-26, S-45 (R) |
| E-07 | Field force user / assignment | Field Force Name, Username, Field Force Type (SR, AMO), SR Name (e.g. "SR - Testing Banani"), AMO id ("AMO-6334"), "SR Not Set" state | S-17, S-19, S-23, S-24, S-37, S-44 (R) |
| E-08 | Sales Plan (zone SKU enablement) | Zone Name, Wing, Division, Territory, House, Email, Address, PDA Contact No., Enabled SKUs (set), Data Entry Date, PDA (blank column) | S-09 (R), S-10 (W) |
| E-09 | Product master | Category (name, Status, Sort); Segment (Category, name, Status, Sort); Brand (name, Status, Sort); Variant (name, Status, Sort); SKU (Product Name, SKU Name, Short Name, Pack Size, Pack Type, Sort, Outlet price, C&c price, Distributor price, Active Status) | S-11-S-16 (R), S-05, S-07, S-10, S-18, S-20-S-30 (R) |
| E-12 | Market QC entry | Zone, Route, Date, SKU, 5 Manufacturing Fault counts, 5 Marketing Fault counts | S-05 (W), S-06, S-08 (R) |
| E-13 | Warehouse QC entry | Zone, Date, SKU, same 10 fault counts | S-07 (W), S-06, S-08 (R) |
| E-14 | Daily route sales entry (web) | Date, Zone, Route, Classification(s), Target Outlet, Successful Call, per SKU Issue, Return, Sale (derived), Memos, per-channel value; (14b) Astha: Outlet x SKU quantity for Route/Channel/Category/Date | S-18 (W), S-20 (W), S-19 (R/delete) |
| E-15 | Final submit record | Zone, Date, Submit Status (Done/Not Done), MIN/MAX time, Count, Route Status (exist/--) | S-19 (W), S-27 (R), S-03 (R) |
| E-16 | Memo (sales document) | memo count per route/outlet/date, Memo Criteria, discount data | S-03, S-21, S-23, S-24, S-28, S-30 (R) |
| E-17 | Sales / STD aggregates | Total Sales per product family, SKU quantities per route/outlet class, STD value ('000), STD criteria | S-03, S-21, S-23, S-25, S-30 (R) |
| E-18 | Target | Target Name ("Phatherhat of April-2026"), Product Type ("variant"), Target Type ("stt"), Start/End Date, Approval Status ("WMO approval pending"), monthly route-wise target values, uploaded Excel, allocation | S-34 (W), S-35, S-36 (R), S-03 (R) |
| E-19 | Calls / visits | Target Outlet, Successful Calls, Strike Rate, BSR, CPR, AMO calls (Summary/By Date/By Month), No of Outlets | S-03, S-22, S-23, S-29, S-37 (R) |
| E-20 | Geo fencing / FF location | "Geo Fencing", "FF Geo Location" (dashboard sections, not visible) | S-03 (R) |
| E-21 | Login / Submit status | Target Route, Successful Login, Login successfully, Submitted successfully | S-03 (R) |
| E-22 | Sync / session log | Route-level Download MAX/MIN/Count (Upload likely), HH:MM:SS | S-26 (R) |
| E-23 | SR device login OTP | Field Force Name, Username, Zone Code, Zone, OTP | S-44 (R) |
| E-24 | Astha gift choice | Astha outlet (Outlet Code, Owner Name, route), Gift List (Ceiling Fan (56 inch), 24 pcs Dinner Set, 27 pcs Dinner Set), Gift Status Yes/No | S-45 (W), S-46 (R) |
| E-25 | Loyalty / Astha program | Loyalty Point per outlet (Diamond League); Astha outlet memos by Year, Quarter, Month | S-31, S-33 (R) |
| E-26 | GIGO data | content not shown | S-32 (R) |
| E-27 | Excel/PDF export artefacts | .xlsx downloads (outlets, SKUs, QC, reports, target sample/uploaded template), QC PDF, print view (DS-RRS) | S-04, S-06, S-15, S-21+ |

---

# F. UNCLEAR / CONFLICTING ITEMS

- **U-01** (pp.1-49) Only a TSO web login is shown (`tso-apsis`, `TSO-1012`). Other roles' web menus/permissions (AMO, SR, WMO approver, HQ/admin) and per-role menu hiding are not documented. Product pages look read-only for TSO; who can edit products/routes is unknown. The cover calls the product "ARON" while the file is "AKTC Web".
- **U-02** (p.2) No login error text, lockout, password expiry, Forgot password, session timeout or Remember-me duration; case-sensitivity of User ID unknown.
- **U-03** (p.3 onward) Avatar/username click behaviour not shown; sidebar "<" collapse toggle function inferred.
- **U-04** (pp.3-8, 12-17, 22-24, 31, 41) Sidebar differs between screenshots: some omit Credentials / SR Device OTP / Astha Gift Panel (pp.5-8, 12-17, 41); Reports list shows 9 items on pp.22-24 vs 13 on pp.25-34 (By Outlet, Astha, GIGO, Loyalty Program - Diamond League added). Likely crop or build-version difference.
- **U-05** (p.3) Dashboard: Filter popup fields; "i" info icons content; ring % basis (target achievement inferred); units of Cigarette/Bidi (not printed); "Total Zone" vs "Total Service Zone" vs "Remaining" meanings; "STD" never expanded; gear icon in Breakdown panel; By Channel STD and Breakdown tiles cut off; By Segment Contribution, Sales Trend, Geo Fencing, FF Geo Location named only in caption; "FF" inferred Field Force.
- **U-06** (p.4) Columns right of Latitude cut off; caption "Export Excel" vs button "Get Excel"; caption typo "আপানারা"; duplicate outlet names/contacts with distinct codes in sample; Address/NID/TIN/Trade License blank.
- **U-07** (p.5) Marketing Fault grid cut off at right (more columns?); SKU rows below ARIS-A-20s cut off; no rules on edit/overwrite, past/future dates, maximum values, who may enter; whether "Select Route" is multi-select.
- **U-08** (p.6) "Download PDF" present on screen but not in caption; caption says "Export excel"; no report layout shown.
- **U-09** (p.7) Slide title "Warehouse QC Report" vs screen/menu "Warehouse QC Entry"; caption says date range but control is a single date; caption mentions only Zone.
- **U-10** (pp.9-11, 20) Date formats: 2026-04-12 (view), 04/12/2026 (Sales Plan edit; MM/DD vs DD/MM ambiguous), 2026/04/13 (Final Submit).
- **U-11** (pp.13-16) Sort values inconsistent: Segment starts at 3; variants duplicate (2,2; 3,3; 13,13,13); SKU 4,4,4,4. Unknown if sort is global or per-parent. SKU price units (stick vs pack), "HLP", "C&c" unexplained; Distributor price formula (outlet minus 0.065/0.055/0.045) not shown; full SKU list (38 products) not visible; SM-Americ... SKU visible only in DSS.
- **U-12** (p.18) Browse Routes result table not shown; "section" vs Zone terminology.
- **U-13** (p.19) "Brand Data" button purpose; "GT Channel > GT" column meaning; Sale formula (Issue minus Return?) not stated; options of Select Classifications; validation limits (Successful Call <= Target Outlet, non-negative); Save not mentioned in caption; date read-only?; route naming "Apsis Route-Daily" (p.19) vs "Apsis RouteDaily" (p.20).
- **U-14** (p.20) Final Submit: whether it locks the day; confirmation/toast for Submit and Delete Section Data; "exist" semantics; duplicate "AMO-Apsis RouteAMO" rows; "Apsis Route 3Daily" and "Apsis Route 4Daily" share SR "SR - Testing Banani 3"; the cut-off date 2026-04-13 is a fixed date in the banner (rolling/configured mechanism unknown; today is 2026-10-04); caption states Zone selection but screen has a route table plus Filter.
- **U-15** (p.21) Astha Web Entry: no Save/Submit visible; units of entry; last SKU column cut off; in-app title "Web Entry By Outlet" vs menu name; "All items are selected." wording differs.
- **U-16** (p.22) Option lists for Location, Date Grouping, Product Type, Active Status, Classification Type, Report Type not shown; caption typo "অপ্তিওনে".
- **U-17** (p.23) SR Efficiency metrics/formula and Excel columns unknown.
- **U-18** (p.24) DSS not expanded; last column truncated "SM-Americ..."; destination of route link not shown; units; caption typo "আপানারা".
- **U-19** (p.25) DS-RRS not expanded; caption says date range, UI has single date; Print view not shown; no grid shown.
- **U-20** (p.27) Caption starts "এর আর এর" (garbled; likely SR/RR); UPLOAD group not visible; MAX (16:57:25) earlier than MIN (17:35:50); duplicate AMO-Apsis Route rows.
- **U-21** (p.28) Caption "date range" vs single date; "Not Done" row carries times and count 1.
- **U-22** (p.30) Report named three ways: "Route wise BSR & CPR Report" (menu), "Route Wise Strike Rate & BSR Report" (heading), "Route Wise Strike CPR & BSR Report" and "বিসিপি ও স্ট্রাইক রেট" (caption). BSR, CPR formulas unknown. Same for p.26 "Route Wise Live STD Report" vs "Route wise STD Report".
- **U-23** (p.31) Caption says Get Excel / "that day's"; screen has "Download Report" and a date range; hover content of "i" icon; option lists for Product Type (shows "Total" here but "Category"/"SKU" elsewhere), Active Status, Sub Channel (9), Report Type (2), operator dropdowns; "STD Criteria ('000)" unit unexplained.
- **U-24** (p.32) Year default 2025 vs 2026 elsewhere; option lists unknown.
- **U-25** (p.33) GIGO not expanded; content unknown.
- **U-26** (p.34) Loyalty table columns not shown; Diamond League rules unknown.
- **U-27** (p.35) Manual-entry grid, Save, expanded Upload panel and sample Excel layout not shown; only Wing/Division/Territory filters here.
- **U-28** (p.36) Caption says "view" but output is a download; content unknown.
- **U-29** (pp.37, 39-41) Three names for one screen on p.37 (Target Approve List / Approval List / Approval Panel); Outlet Approval Panel heading "Firefly Outlets Reports" (leftover brand/build name); caption says "Report Category & Date range" but screen has "Outlet Type" and no date range; first table column clipped; same outlet "Alif" is "Approved" on p.39 and "Verified" on p.40; caption "Approved & Rejected" vs options New/Close/Info; typos "Pnael" (p.40), "সাব মেনুতা" (p.37); "WMO", "stt", "variant" meanings not given; other Approval Status values unknown.
- **U-30** (pp.40-41) Dialogs/reason capture for Verify and Reject not shown; caption writes "Yes, Approve it" vs button "Yes, approve it"; no success toast; actions for Approved/Rejected rows not shown; effect of Verify/Approve on SR app/outlet master not stated.
- **U-31** (p.44) No confirmation or success message for wholesale Submit; whether Remove also unticks grid row.
- **U-32** (p.45) No error/success texts; special characters, max length, new-vs-old difference beyond history rule not stated.
- **U-33** (p.46) OTP length, expiry, refresh, retry count not stated; caption says list is "according to route" but no route filter/column.
- **U-34** (p.47) No Save button for gift choice; whether choices can be changed; dropdown options per outlet vs global; leftmost column clipped; route shown "Apsis Route" vs filter "Apsis RouteDaily".
- **U-35** (p.48) Meaning of Gift Status Yes/No and report columns inferred.
- **U-36** (pp.42-44) No way shown to remove the Wholesale flag.
- **U-37** (p.43) Whether ticks persist across pager pages (43 outlets, 5 pages) and whether header checkbox selects page or all.
- **U-38** Label inconsistencies: "Get Excel" / "Export Excel" / "Excel Export" / "Download Report"; "Browse Brands" vs "Browse Brand"; "Browse Product Tree" vs "Browse Products Tree"; "Distribution House" vs "House"; "Gigo" vs "GIGO"; "Target Approve List".
- **U-39** Abbreviations never expanded: STD, DSS, DS-RRS, BSR, CPR, GIGO, WMO, HLP, C&c, PDA, FF, "stt", "20HL", brand/SKU codes (MaxR, MaxB, MaxWB, MaxDB, MSB, BDDF, BDA, SM-American), "Sub Channel", "Service Zone".
- **U-40** (pp.26, 29, 30, 31) Captions mention "Location" but no Location field on those screens (only p.22 has Location).
- **U-41** (pp.43, 44) Slide title "Login page" is a copy-paste error.
- **U-42** (p.42) Wholesale Status "Yes" shown while caption says select "No"; one contact number lacks leading 0 ([phone]); outlet codes mix numeric and "DHK-344-00x".
- **U-43** (all) Excel/PDF/print layouts (columns) of every download are not shown; Active Status option lists not shown; no audit/log of web edits described; no bulk upload besides target template.

---

# Addendum. Notes for the rebuild (observations only, from this manual)

- Web is online-only; the offline and sync constraints apply to the apps. Evidence the web reads sync telemetry: Data Entry Log (R-064), Dashboard Login/Submit Status, Final Submit Log.
- Password policy (R-005..R-008) and the SR device OTP relay (R-087) are explicit current behaviours to reproduce.
- Back-dating block with support override (R-052/053) and per-zone Final Submit (R-054) are current business-date controls.
- Outlets already carry Latitude/Longitude (6-7 decimals) and two-step approval (R-078/079).
- Scope today appears server-limited by assignment (R-011), but the manual cannot prove how.

---

# Annex G. Bangla captions, verbatim, by manual page (auto-extracted from the chunk transcripts)

- p.2: "লগইন পেজ থেকে আপনাদের নির্দিষ্ট আইডি এবং পাসওয়ার্ড দিয়ে পোর্টালে লগইন করুন।"
- p.3: "সফলভাবে লগইন করার পরে এই ড্যাশবোর্ডটি আপনারা দেখতে পাবেন। ড্যাশবোর্ড থেকে আপনারা Sales, By Channel STD, Final Submit Status, Login/ Submit Status, Live Strike Rate, By Channel Successful Call, STD/Value/Memo Breakdown, By Segment Contribution, Sales Trend, Geo Fencing, & FF Geo Location ইনফর্মেশনগুলো দেখতে পাবেন। ডেট রেঞ্জ সিলেক্ট করে ফিল্টার করার অপশন রয়েছে।"
- p.4: "Browse Retailer অপশনে ক্লিক করে আপানারা টেরিটোরি এর আউটলেট লিস্ট পাবেন। Export Excel বাটনে ক্লিক করে এক্সেল ফাইল আকারে ডাউনলোড করতে পারবেন।"
- p.5: "QC এর QC entry  অপশন থেকে জোন, রুট এবং ডেট সিলেক্ট করে Qc Entry দিতে পারবেন। Manufacturing Fault এবং Marketing Fault অনুযায়ী SKU wise QC এন্ট্রি করে Submit বাটন ক্লিক করে QC জমা দিতে পারবেন।"
- p.6: "QC এর QC report  অপশন থেকে QC কৃত রিপোর্টটি দেখতে পাবেন। Wing, Division, DH, Territory, Zone সিলেক্ট করে ডেট রেঞ্জ অনুযায়ী ফিল্টার করতে পারবেন। প্রয়োজনে Export excel বাটন থেকে এক্সেল ফাইল আকারে রিপোর্টটি ডাউনলোড করতে পারবেন।"
- p.7: "QC এর Warehouse QC entry অপশন থেকে Warehouse QC এন্ট্রি করতে পারবেন। Zone সিলেক্ট করে ডেট রেঞ্জ অনুযায়ী Manufacturing Fault এবং Marketing Fault এর QC এন্ট্রি করতে পারবেন।"
- p.8: "QC এর Route Wise QC Report অপশন থেকে Route Wise QC Report দেখতে পারবেন। Wing, Division, DH, Territory, Zone সিলেক্ট করে ডেট রেঞ্জ অনুযায়ী ফিল্টার করে রিপোর্ট দেখতে পারবেন। Export Excel বাটন থেকে excel আকারে রিপোর্টটি ডাউনলোড করতে পারবেন।"
- p.9: "Sales Plan অপশন থেকে নতুন SKU এড করা অথবা বাদ দেওয়া যায় । Action বাটন থেকে ইডিট আইকন এ ক্লিক করতে হবে।"
- p.10: "এরপর এই পেজ থেকে মার্ক করা এরো অপশনে ক্লিক করতে হবে।"
- p.11: "এরপর SKU লিস্ট আসবে এখান থেকে আপনার টেরিটরিতে যেসকল SKU গুলো সেলস চলবে সেগুলো সিলেক্ট করে দিতে পারবেন, সেইসাথে যেসকল SKU এর সেলস নেই সেগুলো Disable করে দিতে পারবেন। সিলেক্ট এর কাজ শেষ হয়ে গেলে টিক বাটনে ক্লিক করতে হবে সেভ করার জন্য।"
- p.12: "Product এর ক্যাটাগরি দেখার জন্য Products মেনু থেকে Browse Category সাব মেনুতে ক্লিক করতে হবে।"
- p.13: "Product এর সেগমেন্ট দেখার জন্য Products মেনু থেকে Browse Segment সাব মেনুতে ক্লিক করতে হবে। এরপর এই পেজ থেকে Segment এর details দেখতে পারবেন।"
- p.14: "Product এর ব্রান্ড দেখার জন্য Products মেনু থেকে Browse Brand সাব মেনুতে ক্লিক করতে হবে। এরপর এই পেজ থেকে Brand এর details দেখতে পারবেন।"
- p.15: "Product এর ভেরিয়েন্ট দেখার জন্য Products মেনু থেকে Browse Variant সাব মেনুতে ক্লিক করতে হবে। এরপর এই পেজ থেকে Variant এর details দেখতে পারবেন।"
- p.16: "SKU List দেখার জন্য Products মেনু থেকে Browse SKUs সাব মেনুতে ক্লিক করতে হবে। এরপর এই পেজ থেকে SKU এর details দেখতে পারবেন। Excel Export অপশনে ক্লিক করে SKU List ডাউনলোড করতে পারবেন।"
- p.17: "Product এর Tree দেখার জন্য Products মেনু থেকে Browse Product tree সাব মেনুতে ক্লিক করতে হবে। এরপর এই পেজ থেকে Product এর details দেখতে পারবেন।"
- p.18: "Route planning এর Browse route অপশন থেকে রুট ও সেকশন অনুযায়ী Assign কৃত AMO এবং SR এর লিস্ট দেখতে পারবেন।"
- p.19: "Data entry এর Web entry অপশন থেকে আপনারা রুট অনুযায়ী Web entry করতে পারবেন। এর জন্য সর্বপ্রথম Route নির্বাচন করতে হবে। Successful Call এর সংখ্যা প্রদান করতে হবে। এরপর আপনি SKU ধরে ধরে Data Entry শুরু করতে পারেন। প্রতিটি SKU এর জন্য Issue, Return, Memos এ মোট বিক্রয় কত ছিল সেগুলো input দিতে হবে।"
- p.20: "Data entry এর Final Submit অপশন থেকে আপনারা, সারাদিনের বিক্রয়কাজ সম্পন্ন করে Final Submit দিতে পারবেন। এখানে প্রতিটি Zone এর Final Submit এর জন্য Zone সিলেক্ট করে Submit বাটনে ক্লিক করে প্রতিদিনের বিক্রয় সাবমিট করতে পারবেন।"
- p.21: "শুধুমাত্র Astha আউটলেটে  Web Entry এর জন্য Astha Web Entry মেন্যুটি ব্যাবহার করতে হবে ।"
- p.22: "STD & Memo রিপোর্ট একত্রে অথবা আলাদা করে দেখার জন্য রিপোর্ট মেনু থেকে STD Memo Report অপ্তিওনে ক্লিক করতে হবে। এরপর রিপোর্ট ডাউনলোড করার জন্য সকল কিছু নির্বাচন করার পর Get Excel অপশনে ক্লিক করতে হবে।"
- p.23: "SR Efficiency Details দেখার জন্য রিপোর্ট মেনু থেকে SR Efficiency Report অপশনে ক্লিক করতে হবে। এরপর রিপোর্টটি ডাউনলোড করার জন্য সকল কিছু নির্বাচন করার পর Get Excel অপশনে ক্লিক করুন।"
- p.24: "Reports এর DSS reports অপশন থেকে আপানারা Sales Summary রিপোর্ট দেখতে পাবেন। ডেট সিলেক্ট করে Get Data ক্লিক করলে ঐ দিনের Route wise Sales Summary আপনারা দেখতে পাবেন। Get excel বাটনে ক্লিক করে এক্সেল ফাইল আকারে ডাউনলোড করতে পারবেন। এরপর এখান থেকে রুট এর নাম এর উপর ক্লিক করে আউটলেট অনুযায়ী উক্ত রুট এর সেলস দেখতে পারবেন।"
- p.25: "Reports এর DS-RRS reports অপশন থেকে আপানারা DS-RRS report রিপোর্ট দেখতে পাবেন। ডেট রেঞ্জ  এবং রুট সিলেক্ট করে Get Data ক্লিক করলে ঐ দিনের DS-RRS report আপনারা দেখতে পাবেন। Get excel বাটনে ক্লিক করে এক্সেল ফাইল আকারে ডাউনলোড করতে পারবেন। ফাইনাল সাবমিট এর পূর্বে Print বাটন থেকে প্রিন্ট View এ ডিসকাউন্ট এর ডাটা দেখতে পারবেন।"
- p.26: "Reports এর Route wise STD reports অপশন থেকে আপনারা ডেট ওয়াইস Route wise STD রিপোর্ট ডাউনলোড করতে পারবেন। ডেট রেঞ্জ এবং Location, product type ও product সিলেক্ট করে Get Excel ক্লিক করলে ঐ দিনের Route wise STD report আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.27: "এর আর এর লগইন এবং সেলস সাবমিট এর details দেখার জন্য Report মেনু থেকে Data Entry Log অপশনে ক্লিক করতে হবে। এরপর ডাটা দেখার জন্য এবং ডাউনলোড করার জন্য Get Data & Get Excel অপশনে ক্লিক করতে হবে।"
- p.28: "Reports এর Final submit log reports অপশন থেকে আপনারা ডেট ওয়াইস ফাইনাল সাবমিট রিপোর্ট ডাউনলোড করতে পারবেন। ডেট রেঞ্জ সিলেক্ট করে Get Excel ক্লিক করলে ঐ দিনের Final submit log reports আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.29: "Reports এর Route wise Memo reports অপশন থেকে আপনারা রুট ওয়াইস মেমো রিপোর্ট ডাউনলোড করতে পারবেন। ডেট রেঞ্জ এবং Location, Product Type এবং Product সিলেক্ট করে  Get Excel ক্লিক করলে ঐ দিনের Route wise Memo reports আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.30: "Reports এর Route Wise BSR & CPR Report অপশন থেকে আপনারা রুট ওয়াইস বিসিপি ও স্ট্রাইক রেট রিপোর্ট ডাউনলোড করতে পারবেন। ডেট রেঞ্জ এবং Location, Product Type এবং Product সিলেক্ট করে  Get Excel ক্লিক করলে ঐ দিনের Route Wise Strike CPR & BSR Report আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.31: "Reports এর By Outlet reports অপশন থেকে আপানারা আউটলেট মেমো রিপোর্ট ডাউনলোড করতে পারবেন। ডেট রেঞ্জ এবং Location, Product Type এবং Product সিলেক্ট করে  Get Excel ক্লিক করলে ঐ দিনের By Outlet reports আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.32: "Reports এর Astha reports অপশন থেকে আপনারা Astha আউটলেট মেমো রিপোর্ট ডাউনলোড করতে পারবেন। Year , Quarter এবং Month  সিলেক্ট করে  Get Excel ক্লিক করলে Astha Outlet reports আপনারা এক্সেল আকারে ডাউনলোড করতে পারবেন।"
- p.33: "GIGO Report ডাউনলোড করার জন্য রিপোর্ট অপশন থেকে GIGO Report সাব মেনুতে ক্লিক করুন। এরপর সকল কিছু নির্বাচন করার পর Get Excel বাটনে ক্লিক করে রিপোর্টটি ডাউনলোড করুন।"
- p.34: "আউটলেট অনুযায়ী Loyalty Point দেখার জন্য Report মেনু থেকে Loyalty Program – Diamond League Report সাব মেনুতে ক্লিক করুন। এরপর  Get Data & Get Excel বাটনে ক্লিক করার মাধ্যমে রিপোর্টটি দেখুন এবং ডাউনলোড করুন।"
- p.35: "মাসিক সেলস টার্গেট সেট করার জন্য Target মেনু থেকে Set Target অপশনে ক্লিক করুন। এরপর Apply অপশনে ক্লিক করে ম্যানুয়ালি রুট অনুযায়ী ইনপুট করে সেভ করতে পারবেন। অথবা Download Sample অপশনে ক্লিক করে উক্ত ফাইলে ডাটা ইনপুট করে আপলোড করে দিতে পারবেন।"
- p.36: "Target মেনু থেকে Target Allocation Report সাব মেনু তে ক্লিক করার পর Get Excel অপশনে ক্লিক করে এই রিপোর্টটি দেখতে পারবেন।"
- p.37: "আপনার মাসিক সেট করা টার্গেট এর Approval Status দেখতে Target Approval List সাব মেনুতা ক্লিক করতে হবে। এরপর সকল কিছু নির্বাচন শেষে View অপশনে ক্লিক করে Details দেখতে পারবেন।"
- p.38: "AMO Call Report এর জন্য Supervisory Module  অপশন থেকে AMO Call Report ডাউনলোড করতে পারবেন। Date সিলেক্ট করে Get Excel ক্লিক করলে AMO Call Report পেয়ে যাবেন।"
- p.39: "Approved & Rejected আউটলেট লিস্ট দেখতে Outlet মেনু থেকে SR Outlets Reports অপশনে ক্লিক করুন। এরপর সকল কিছু নির্বাচন করার পর Report Category & Date range নির্বাচন করে Get Data & Get Excel অপশন এর মাধ্যমে রিপোর্টটি দেখতে পারবেন এবং ডাউনলোড করতে পারবেন।"
- p.40: "আউটলেট এর রিকুয়েস্ট Verify, Approve, & Reject করার জন্য Outlet Approval Pnael অপশনে ক্লিক করতে হবে। এরপর সকল কিছু নির্বাচন করার পর Report Category & Date range নির্বাচন করে Get Data তে ক্লিক করুন। এরপর লাস্ট কলাম থেকে Verify অপশন এর মাধ্যমে Verify করুন এবং Approve করতে Approve অপশনে ক্লিক করুন।"
- p.41: "এরপর Yes, Approve it অপশনে ক্লিক করে Approve করুন।"
- p.42: "টেরিটোরির আউটলেটকে Wholesale হিসাবে মার্ক করতে Retailer Wholesale Outlet অপশনে ক্লিক করুন। এরপর এই পেজ থেকে সকল কিছু সিলেক্ট করে Wholesale Status No সিলেক্ট করুন। এরপর Get Data অপশনে ক্লিক করুন।"
- p.43: "এরপর এখান থেকে আউটলেট গুলো মার্ক করতে হবে। তারপর মার্ক করা বক্সে ক্লিক করুন।"
- p.44: "এরপর মার্ক করা আউটলেট গুলো লিস্ট আকারে দেখতে পারবেন। এখান থেকে আউটলেট রিমুভ করতে পারবেন। সবকিছু ঠিক থাকলে Submit অপশনে ক্লিক করে আউটলেট গুলো কে Wholesale করার কাজ Complete করতে হবে।"
- p.45: "Credentials অপশন থেকে আপানারা আপনাদের পাসওয়ার্ড পরিবর্তন করতে পারবেন। পুরাতন পাসওয়ার্ড এবং নতুন পাসওয়ার্ড ইনপুট করে Change Password বাটন ক্লিক করে আপানারা পাসওয়ার্ড পরিবর্তন করতে পারবেন।"
- p.46: "SR অ্যাপ্লিকেশান এর Login OTP দেখার জন্য এস আর ডিভাইসে লগইন অপশনে ক্লিক করার পর TSO পোর্টালে লগইন করে মেনু লিস্ট থেকে SR Device OTP অপশনে ক্লিক করুন। এরপর সকল কিছু নিরবাছন শেষে view অপশনে ক্লিক করার পর রুট অনুযায়ী OTP লিস্ট দেখতে পারবেন।"
- p.47: "আস্থা আউটলেট এর গিফট সিলেক্ট করে দেয়ার জন্য Astha Gift Panel থেকে Astha gift choice panel সাব মেনুতে ক্লিক করুন। এরপর সকল কিছু নির্বাচন শেষে Get Data অপশনে ক্লিক করার পর উক্ত রুট এর আস্থা আউটলেট চলে আসবে। এবার Gift List কলাম থেকে Gift Select করে দিতে পারবেন।"
- p.48: "আউটলেট অনুযায়ী সিলেক্টেড গিফট এর লিস্ট দেখতে Astha Gift Choice Report এ ক্লিক করুন। এরপর এখান থেকে Gift Status Yes এবং No এর মাধ্যমে গিফট এর ডাটা রিপোর্ট দেখতে পারবেন।"
- p.20 (banner, also M-003): আপনি 2026-04-13 তারিখের পূর্বের কোন সেলস ডাটা এন্ট্রি করতে পারবেন না। যদি এর পূর্বের কোন ডাটা এন্ট্রি করার প্রয়োজন হয়, অনুগ্রহ করে সাপোর্টে ফোন দিন।
- p.1 and p.49: no caption (cover; THANK YOU).
