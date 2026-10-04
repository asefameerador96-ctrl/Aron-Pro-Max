# Verification: "Web back-office pages, exports, strings"

Date: 2026-10-04. Verifier: independent re-check against the PDF pages and docs/. Register: manuals/manual-delta-register.md section 1 and 2.7.
PDF page numbers equal the printed slide numbers cited by the register (checked: Web 2-8, 9-10, 16, 18-21, 22-35, 40-48; TSO 6-7, 13-14, 21; AMO 11, 21, 38, 52, 55-56, 66-67; SR 26). The Web PDF has a text layer for Latin text only; Bengali was read from the page images. Bengali typos that the text layer garbles (for example the two spellings flagged in G-man-101) were read from the images.

## Summary

| Id | Verdict | One-line reason |
|---|---|---|
| G-man-039 | PARTLY | Address, NID, TIN, Trade License, Contact Number, Latitude columns are on the TSO's Browse Retailer table (p4). The sample values for NID/TIN/Trade License/Address are blank, and the Excel file's contents are never shown, so "sees and exports NID/TIN" is a column fact, not a data or export fact. |
| G-man-046 | CONFIRMED | Astha Web Entry (p21) is an outlet x SKU grid for the Astha channel; no Save button; docs/09 and docs/10 do not mention it. |
| G-man-048 | CONFIRMED | "Loyalty Program - Diamond League Report" is a menu item and page (p34, Get Data + Get Excel); docs/09 lists no such report. Columns are not shown. |
| G-man-085 | PARTLY | Web Entry grid and fields are right (p19). "Successful Call per sub-channel" is wrong: Successful Call is one route-level input; only the class columns are per sub-channel. Sale = Issue - Return is unevidenced (it is flagged inferred). |
| G-man-092 | CONFIRMED | DSS Report exists (p24), is named as the pre-Final-Submit check (p20), and appears nowhere in docs/01-13 or docs/22. Row list on screen is cut off. |
| G-man-093 | CONFIRMED | DS-RRS Report with Print button and the caption "see discount data in the print view before Final Submit" (p25). Layout never shown. |
| G-man-094 | PARTLY | Route Wise Memo Report is real and unspecified (p29). BSR and CPR has three names; STD has two, not three. "Live" is a name only: the sample is a past-date range. |
| G-man-095 | PARTLY | Filter vocabulary and four export labels are right. The related "13 Excel-only reports" (C-35) is contradicted by R-060's own list of 11, and two of those 11 have a Get Data button. Column sets are visible on screen for about seven reports. |
| G-man-099 | PARTLY | 15 menu items and 41 pages, the eight pages missing from docs/09 and the 13 spec pages absent from the TSO menu all check out. The "build changed" explanation for menu differences is wrong: it is the sidebar being clipped in the screenshots. |
| G-man-100 | PARTLY | 'SR Not Set' sits on the AMO-labelled routes and 4 target routes matches the four SR routes. But route kind is never printed, the three AMO rows are probably two routes, AMO users also sit on SR-kind routes (TSO p7), and 'SS' is not defined anywhere. |
| G-man-101 | CONFIRMED | 251 entries (99/90/24/38) and Android-owned 12/12/2 recount exactly; typos and the three AMO success wordings are on the pages; the spec has no string list. Caveat: entries include labels and placeholders, not only messages. |
| G-man-102 | PARTLY | The listed error classes are indeed absent, but "no error, offline, validation or failure text exists in any manual" is too strong. Five guard or failure texts are printed (AMO out-of-range with Manual Override and Refresh, sync-before-submit warning, unpaid-dues confirm, TSO already-submitted alert, Web back-date banner). |

---

## G-man-039  PII baseline: the TSO sees and exports NID, TIN, trade licence, address, phone and coordinates

**Claim (register row 117, C-51):** Web Browse Retailer shows NID, TIN, Trade Licence, address, phone, coordinates to the TSO and lets the TSO export them. cfg.pii.field_roles hides nid/tin from the TSO. Parity baseline, or a recorded restriction.

**What I saw**
- Web p4 (Browse Retailer, user tso-apsis): table headers Zone Code, Zone Name, Outlet Name, Owner Name, Contact Number, Route Code, Route, Outlet Code, Cluster Type, Cluster Name, Address, NID, TIN, Trade License, Latitu(de), cut off at the right. Contact Number values are 11-digit ([phone]). Latitude values 23.79, 22.31. The Address, NID, TIN and Trade License cells are empty in every visible row. Buttons "Get Data" and "Get Excel"; the caption says "Export Excel".
- Web p40 (Outlet Approval Panel): columns include Owner Name, Phone Number, Latitude, Longitude, Status, Actions. Web p42-43 (Wholesale Retailers): Contact Number, Address, Geo Class.
- Web p46 (SR OTP Panel): table Field Force Name, Username, Zone Code, Zone, OTP; the sample shows "No Data". So the OTP column exists on the TSO's page, but no value is seen.
- Not seen anywhere: the content of any Excel file. The export button sits on the same page, but whether the xlsx carries NID/TIN is not shown.

**Spec quote**
- docs/03-data-model.md: "PII note: NID, TIN, trade licence and phone are sensitive. Expose only to roles that need them (see `docs/09`)."
- docs/09-web-and-api.md: "Retailer: Browse Retailer ... PII-gated columns." and "PII: outlet NID/TIN/phone returned only to roles that need them." Silent on whether the TSO is such a role.
- docs/22 P-12: "NID is the placeholder `123` for 589k and blank for 145k. TIN and trade licence are empty for all. Address is filled for 13."
- Plan default (not spec): lens-config.md line 253 sets cfg.pii.field_roles = {nid:[admin], tin:[admin], trade_license:[admin,tso], contact_number:[sr,amo,tso,admin]}. The register is right that the plan default hides nid/tin from the TSO. It permits trade_license for the TSO, which the register does not mention.

**Verdict: PARTLY**

**Corrected statement:** The TSO's web Browse Retailer table has columns for Address, NID, TIN, Trade License, Contact Number and Latitude (Longitude shows on the Outlet Approval Panel, p40). In the sample the NID, TIN, Trade License and Address cells are blank. This matches docs/22 P-12: those fields are almost never populated, so the working PII today is phone and owner name. The content of the Excel export is not shown, so "exports NID/TIN" is an assumption. The spec is silent on the TSO row of the role x field matrix; the plan default hides nid and tin from the TSO and allows trade_license.

**Build implication**
- Config: keep cfg.pii.field_roles. Decide the TSO row explicitly (Q30 in lens-config). Parity-safe default: show the columns to the TSO but return null for nid/tin when the stored value is the placeholder '123'. Record any hiding as a deliberate D-id.
- Schema: report_export_log (includes_pii boolean) as the register proposes. Keep NID/TIN/trade licence nullable and PII-classified, per docs/22 P-12.
- UI: the TSO outlet table must keep Address, NID, TIN, Trade License as columns only if the decision is "parity"; otherwise hide the columns, not blank them.
- Open question for AKTCL: does the live Excel export contain NID/TIN? Ask for one real file.

---

## G-man-046  Astha Web Entry: outlet x SKU quantity grid for Astha-channel outlets

**Claim (register row 124):** A web page where the TSO enters per-outlet, per-SKU quantities for Astha outlets. Absent from docs/10, which covers Astha targets, achievement, gift choice and the report only.

**What I saw**
- Web p21: page heading "Web Entry By Outlet" (menu "Astha Web Entry"). Filters Wing/Division/Territory/House/Zone, then Route ("Apsis Route-Daily"), Channel ("Astha Channel" with a clear X), Category ("All items are selected." with X), Date picker (2026-04-13), blue "Filter". Grid: Outlet column (name plus code in brackets: shaiful (3362328), Bipu store (6658061), asd (3391607), Mashrur Store (7911468) ...) and SKU columns MaxR-10S, MaxR-20S, MaxB-10S, MaxB-20S, MaxWB-10S, MaxWB-20S, MaxDB-20S 20HL, Avon-20S, then a cut-off column. Every cell is an empty input; one cell is focused (blue). No Save or Submit button is visible.
- Caption (Bengali): "Only for Astha outlets, the Astha Web Entry menu must be used for Web Entry."
- Contrast with plain Web Entry (p19): its Date is a read-only label and the grid is per SKU for the route, not per outlet.

**Spec quote:** docs/10-kpis-and-programs.md: "Gift chosen per outlet in the TSO portal; SR hands over, one photo per outlet; web Astha Gift Choice Report lists choices." No data-entry mention. docs/09-web-and-api.md lists "Data Entry" only as an admin page group; no Astha entry. docs/03 has no web_entry table.

**Verdict: CONFIRMED**

**Corrected statement:** As registered. Additions from the page: (1) the Channel filter is pre-set to "Astha Channel" but is clearable, so the page is not hard-locked to Astha; (2) the Date is an editable picker here (read-only on plain Web Entry); (3) exclusivity ("Astha outlets must use this menu") is a caption instruction, not something the screens enforce; (4) the units of the cells are not printed (sticks per docs/22 is the safe assumption).

**Build implication**
- Schema: web_entry_outlet_sku(outlet_id, sku_id, business_date, qty, entered_by, client_uuid, status) as proposed; unique on (outlet, sku, business_date, source='web').
- Rule: when a plain Web Entry route-day exists and Astha outlets in that route also have Astha entries, aggregates must not double count (route-day totals vs outlet rows). Log as D-id; ask a TSO.
- UI: add an explicit Save (the page shows none). Keep Channel preset to Astha, clearable only if AKTCL confirms.
- Feeds fact_daily_outlet and Astha quarter achievement, as the register says.

---

## G-man-048  Loyalty Program - Diamond League Report (outlet-wise points) is not a listed report

**Claim (register row 126):** The web has a report "Loyalty Program - Diamond League Report" for outlet-wise loyalty points; docs/09 and the plan list only "Campaign Gift Redemption".

**What I saw**
- Web p34: menu item and page title "Loyalty Program - Diamond League Report". Filters Wing, Division, Territory, Distribution House, Zone (all "All Selected (1)") and "Choose Date Range" (2026-04-13 to 2026-04-13). Buttons "Get Data" and "Get Excel". The body is empty (no grid captured).
- Caption: "To see Loyalty Point by outlet, click the Loyalty Program - Diamond League Report sub-menu in the Report menu, then click Get Data and Get Excel to see and download the report."
- The sidebar label wraps to two lines and is truncated to "Loyalty Program - Diamond" on p24-31.
- SR p24 (not re-viewed here, taken from the SR inventory): the Points card has "Expiring Points" and "Expiry Date".

**Spec quote:** docs/09-web-and-api.md Reports list: "... GIGO (attendance), Campaign Gift Redemption, Discount Report, ..." with no Diamond League points report. docs/10: "Web Campaign Gift Redemption report. → loyalty_ledger..." (redemptions, not a points statement). Plan: lens-features.md F-WEB-022 "Report: Campaign Gift Redemption ... redemptions, points, photo-verified flag".

**Verdict: CONFIRMED**

**Corrected statement:** As registered. Two notes: (1) the page shows only that the report is by outlet and takes a date range; the columns "earned, spent, balance, expiring" come from the SR Points screen and are a reasonable proposal, not something the web manual shows; (2) "Campaign Gift Redemption" (docs/09) does not appear on the TSO menu at all, so it may belong to another role or be the vendor's other name. Do not assume the two are the same report.

**Build implication**
- New report 'diamond-league-points' (json + xlsx) from loyalty_ledger and agg_outlet_balance; filter set geo + date range. Keep Campaign Gift Redemption as its own report for the other role.
- Question for AKTCL: is "Campaign Gift Redemption" a live report today and who sees it?

---

## G-man-085  Web Entry: route-day aggregate sales entry has no model

**Claim (register row 163):** Web Entry takes Issue / Return / Sale / Memos / Successful Call per sub-channel for a route-day. docs/09 names "Data Entry" only; no table holds route-day totals.

**What I saw**
- Web p19 (Web Entry): "Date: 2026-04-13" as a read-only label (no picker). Wing/Division/Territory/House/Zone dropdowns. "Select Classifications" multi-select with a removable tag "GT Channel". "Select Route" ("Apsis Route-Daily" with X). "Target Outlet" greyed read-only: 37. "Successful Call": editable, value 5. Green "Save". Blue "Brand Data" (purpose not stated). Grid: Sku Name (read-only), Issue (input, default 0), Return (input, 0), Sale (plain text 0, not an input), Memos (input, 0), then a column group "GT Channel" with one sub-column "GT" holding an empty input. Rows MaxR-10S ... ARIS-S-20s and more.
- Caption: choose Route first, enter the Successful Call count, then go SKU by SKU entering Issue, Return and Memos.
- p20 banner on the Final Submit page: no sales data can be entered before 2026-04-13.

**Spec quote:** docs/09: "Admin/master-data pages (TSO/admin, beyond the SR-visible menu): QC, Sales Plan, Data Entry, Supervisory Module ..." and the report "Data Entry Log (download/upload per route)". docs/03 has `route_log` (download/upload first and last time) but no entry totals.

**Verdict: PARTLY**

**Corrected statement:** The page entry is a route-day grid: one route-level "Successful Call" number, a read-only "Target Outlet" (37 in the sample), and per SKU an Issue, Return, Memos input, a derived Sale, and one input per selected classification (sample: GT Channel, with sub-column GT). Successful Call is NOT per sub-channel; only the class columns are. The formula for Sale (Issue - Return) and the rule "class columns sum to Sale" are not on the page; they are the register's own inferences and were already labelled as such. "Brand Data" is unexplained.

**Build implication**
- Schema as the register proposes: web_entry_route_day(route, business_date, successful_calls, target_outlets_snapshot, status), web_entry_line(entry, sku, issue_qty, return_qty, sale_qty, memo_count), web_entry_line_class(line, sub_channel, qty). successful_calls lives on the route-day row, not the class row.
- Rules: log Sale derivation as an ASSUMPTION; add a back-date window (G-man-087). One entry per route-day, replace audited, client uuid per submission.
- Unknown, confirm with a TSO: what "Brand Data" does; whether classification columns are channels or sub-channels (header says "GT Channel", sub-column says "GT").

---

## G-man-092  DSS Report (Sales Summary): the pre-Final-Submit check report is not in the spec or plan

**Claim (register row 170):** DSS Report is a route-wise sales summary used to check data before Final Submit; no hit for "DSS" in docs/01-13, docs/22 or the plan lenses.

**What I saw**
- Web p24 (DSS Report): filters Wing..Zone (single-select), Field Force Type "SR", Choose Date 2026-04-13 (single date), Category "All Selected (4)". Buttons "Get Data" and "Get Excel". Table columns Route Code, Route, SR Name, No of Outlets, No of Memos, then SKU columns (MaxR-10S, MaxR-20S, ... MSB-10s, BDDF-10s, BDA-10s, SM-Americ... cut off). One data row: Route Code 8, route "Apsis RouteDaily" (blue link, boxed), SR - Testing Banani, No of Outlets 36, No of Memos 5, SKU values 1000, 500, 750 and so on. Below, yellow summary rows: Total (36, 5), DCC STD, Gold STD, RCC STD, Platinum STD, Diamond STD, GT STD (values equal to the route row), Silver STD (cut at the bottom edge).
- Caption: see the Sales Summary report; Get Data shows the day's route-wise Sales Summary; Get Excel downloads; click the route name to see the route's sales by outlet.
- Web p20 (Final Submit): pink notice "Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit".
- Cross-page numbers: Web Entry p19 (same route, same date 2026-04-13) has Target Outlet 37 and Successful Call 5. DSS shows No of Outlets 36 and No of Memos 5. Dashboard p3 shows a zone-level Target Outlet 43.

**Spec quote:** none. `grep -i 'DSS\|DS-RRS'` over docs/*.md, db/, README.md and PROJECT-CONTEXT.md returns nothing; same over the plan lenses and critics.

**Verdict: CONFIRMED**

**Corrected statement:** As registered. Caveats: (1) the summary rows visible are Total plus seven sub-channels; the table is cut at Silver STD, and the By Outlet Report (p31) offers 9 sub-channels (docs/22 P-14 lists nine: GT, Gold, Platinum, RCC, Diamond, DCC, Silver, MT, HoReCa), so expect MT STD and HoReCa STD below the fold; (2) the sample's "No of Memos 5" equals the Web Entry "Successful Call 5" for the same route and date, which hints that DSS reads web-entered data, but this is inferred; (3) 36 vs 37 outlets stays unexplained.

**Build implication**
- New report 'dss' with drill-down by outlet, as proposed. Summary rows driven by the outlet sub-channel list (9 values), not hard-coded to 7.
- Must include web-entered rows (G-man-085) and refresh quickly, because Final Submit's own notice tells the TSO to check it first. Consider a soft gate: Final Submit page links to DSS.
- Open: define "No of Outlets" and "No of Memos" (sample 36 vs 37, 5 vs 5).

---

## G-man-093  DS-RRS Report with a Print view that shows discount data before Final Submit

**Claim (register row 171):** A DS-RRS report with a Print view showing discount data, used before Final Submit; not in spec or plan.

**What I saw**
- Web p25 (DS-RRS Report): filters Wing..Zone; "Field Force Type" SR; "Choose Date" 2026-04-13 (single date); "Route" (Apsis RouteDaily); "Category" (Cigarette with X). Three buttons: green "Get Data", blue "Get Excel", orange "Print". Body empty.
- Caption: select date range and route, click Get Data to see that day's DS-RRS report; Get Excel to download; "Before final submit you can see discount data in the Print View from the Print button."
- The caption says "date range"; the control is a single date (as I-32 says).

**Spec quote:** none; no hit for DS-RRS or "Print" report in docs/09 or the plan. docs/09 lists a separate "Discount Report".

**Verdict: CONFIRMED**

**Corrected statement:** As registered. The meaning of "DS-RRS" is not printed and the report layout is never shown; the register's hypothesis (route-day delivery and settlement statement) is unverified and must stay labelled so.

**Build implication**
- New report plus a server-rendered print view; filters Field Force Type, single date, Route, Category. Log prints in report_export_log (format 'print').
- Open question for AKTCL (blocking the layout): expansion of DS-RRS and one real printout. Do not guess columns.

---

## G-man-094  Route-wise reports: Memo Report missing; STD and BSR & CPR carry three names each

**Claim (register row 172):** No Route-wise Memo Report in the spec; Route-wise STD and BSR & CPR each carry three names.

**What I saw**
- Web p29: "Route Wise Memo Report". Filters: five geo selects, Choose Date Range (2026-04-13 to 2026-04-13), Category (Cigarette), Product Type (Category), Active Status (All), Select Products (All Selected (1)). Buttons Get Data and Get Excel. Body empty.
- Web p26: sidebar "Route wise STD Report"; slide title "Route Wise STD Report"; in-app heading "Route Wise Live STD Report"; caption "Route wise STD reports". Date range shown is 2026-04-12 to 2026-04-12, a past date. A grid with one route row is displayed (Wing_Code ... Route, SKU columns 1000, 1500, ...).
- Web p30: sidebar "Route wise BSR & CPR Report"; slide title "Route Wise BSR & CPR Report"; in-app heading "Route Wise Strike Rate & BSR Report"; caption second sentence "Route Wise Strike CPR & BSR Report". Same filters as the memo report.
- Web p22: STD Memo Report is a different page (Report Type STD / MEMO / STD MEMO, Location, Date Grouping), so the Route-wise Memo report is not a duplicate of it.

**Spec quote:** docs/09: Reports list "... STD Memo Report, SR Efficiency, Route-wise STD, Data Entry Log, Final Submit Log, CPR & BSR, ..." No "Route-wise Memo". One name each. docs/10: "CPR | successful calls / target outlets" and "BSR | memos containing a brand / total memos | confirm denominator docs/13" (Q9).

**Verdict: PARTLY**

**Corrected statement:** The missing Route-wise Memo Report is confirmed. BSR and CPR does carry three distinct strings (BSR & CPR / Strike Rate & BSR / Strike CPR & BSR). Route-wise STD has two distinct names (STD, and "Live STD"); the other variants differ only in case or plural. "Live" is a label only: the sample is a date-range query for a past date, not a live view. So "Live means today's aggregate with a 'data as of' stamp" is a proposal and is not on the page. Note that Route-wise Memo and BSR both show a Get Data button next to Get Excel, so they are not provably Excel-only (see G-man-095).

**Build implication**
- Add 'route-memo' report; one parameter object shared with route-std and route-bsr-cpr (ReportQuery, G-man-095). Store display aliases as data (cfg.i18n or report registry): STD {Route wise STD Report, Route Wise Live STD Report}; BSR&CPR {three strings}.
- Do not promise "live"; give a date range as the page does, plus an optional today shortcut with a data-as-of stamp.
- BSR denominator: still Q9 (spec text above); keep both candidates.

---

## G-man-095  Report filter vocabulary and output behaviours are unspecified

**Claim (register row 173):** Every report depends on a filter vocabulary and output behaviours (formats, labels, date formats, defaults) that the spec leaves undefined; Excel column sets are unknown.

**What I saw (filters, by page)**
- Geo cascade: Wing, Division, Territory, Distribution House (labelled "House" on entry pages), Zone, "All Selected (n)" on report pages (p4, 6, 8, 22-34, 39-43, 48), single-select pre-filled on entry and plan pages (p5, 9, 18-21, 24, 47).
- Date: single date (p24, 25, 28, 33), date range (p6, 8, 22, 23, 26, 29-31, 34), Month (p35 "April 2026"), Year + Quarter ("Q-3 (Jul-Sep)") + Month multi (p32).
- Product: Category, Product Type (Category or SKU), Active Status (All), Select Products (p22, 26, 29, 30, 31). Others seen: Location, Date Grouping, Classification Type, Report Type (p22); Sub Channel (All Selected (9)), Report Type (All Selected (2)), STD Criteria ('000) and Memo Criteria with an operator and number, Outlet Code (p31); Field Force Type (p24, 25, 38); Submit Status Done/Not Done (p28); Gift Status Yes/No (p48); QC Type Market/Warehouse (p8).
- Output controls: "Get Excel" (most pages), "Export Excel" (p4 caption, p16 button, p6 and p8 captions), "Excel Export" (p16 caption), "Download Report" (p31 button), "Download PDF" (p6), "Print" (p25). So four Excel labels, plus PDF and Print.
- Date display formats: 2026-04-12 (most), 04/12/2026 (p10), 2026/04/13 (p20), "April 2026" (p35).
- Grids on screen (so column sets ARE visible): Browse Retailer (p4), DSS (p24), Route-wise STD (p26), Data Entry Log (p27), Final Submit Log (p28), Outlet Approval Panel (p40), Astha Gift Choice Panel (p47), SR OTP Panel (p46).

**Spec quote:** docs/09: "Standard filter on data pages: Wing → Division → Territory → House → Zone, defaulted to the user's scope" and "GET /reports/<name>?filters…&format=json|xlsx". Nothing else. docs/09 also says "Reports render on screen first, with Excel export as a formatting step"; the current manual has several reports that show no grid in any screenshot.

**Related register row C-35 ("13 reports are Excel-only", from R-060):** R-060 in manual-web.md lists 11 reports (STD Memo, SR Efficiency, Route wise Memo, Route wise BSR & CPR, By Outlet, Astha, GIGO, Target Allocation, AMO Call, Astha Gift Choice Report, Route wise QC), not 13. Of those, Route wise Memo (p29) and Route wise BSR & CPR (p30) show a Get Data button beside Get Excel; they show no grid in the screenshot, which does not prove they are Excel-only.

**Verdict: PARTLY**

**Corrected statement:** The vocabulary, the four Excel labels and the mixed date formats are as registered, and the spec really is silent on all of it except the geo cascade and format=json|xlsx. Two corrections: (1) the Excel-only count is 11 by the register's own rule list, not 13, and two of the 11 may have an on-screen grid; (2) "the manual never shows the column sets" holds only for Excel-only reports. About eight on-screen grids give real column names to seed the xlsx layouts.

**Build implication**
- One ReportQuery schema in /packages as proposed, with these named parameters and the per-report table in manual-web.md Appendix D.
- Formats json, xlsx, pdf (QC Report only today), print (DS-RRS). Single label "Get Excel" plus an alias table; the manual's inconsistency is not parity to copy.
- Seed column sets from the visible grids (p4, 24, 26, 27, 28, 40, 46, 47). Ask AKTCL for sample .xlsx files for the other reports.
- Fix the count in C-35 to 11 pending a real check of p29/p30 behaviour.

---

## G-man-099  Role x menu x action matrix: the TSO web portal is wider and writes more than '37 pages, 13 menus'

**Claim (register row 177):** The TSO sidebar has 15 menu items and 41 pages; at least 8 TSO pages are in neither docs/09 nor the plan; 13 spec pages are absent from the TSO manual; the TSO writes data in many places; the menu differs between screenshots because the build changed (SR release 1.0.25 adds login 2FA).

**What I saw**
- Counts (p2-3, p44-48, sidebar fully visible on p9 and p47): 15 top-level items: Dashboard, Retailer, QC, Sales Plan, Products, Route Planning, Data Entry, Reports, Target, Supervisory Module, Outlet, Retailer Wholesale Outlet, Credentials, SR Device OTP, Astha Gift Panel. Pages: 1 + 1 + 4 + 1 + 6 + 1 + 3 + 13 + 3 + 1 + 2 + 1 + 1 + 1 + 2 = 41.
- TSO write actions seen: QC Entry with Submit (p5); Warehouse QC Entry (p7); Sales Plan edit with tick/cross actions (p9-10); Web Entry Save (p19); Final Submit with Submit and Delete Section Data (p20); Astha Web Entry (p21); Set Target with Apply, Download Sample, Upload Excel (p35); Outlet Approval Panel Verify, Reject, Approve with a confirm dialog (p40-41); Wholesale marking with Submit (p42-45); Change Password (p46); Astha Gift Choice Panel dropdown per outlet (p47).
- Menu differences: the sidebar is clipped by the screenshot window. p5 (QC expanded) ends at Retailer Wholesale Outlet; p19-21 (Data Entry expanded) end at Credentials; p22 starts at Dashboard and cuts the Reports list at 9 items, while p25-26 start at "Sales Plan" (sidebar scrolled) and show all 13 report items. p9 (all groups collapsed) and p47-48 show all 15 items. The items never change, only how many fit above the Logout button.
- SR release note, from the SR inventory: "Version 1.0.25 ... New promotion modality., Login time 2FA verification., Sync file update without deleting." The web manual has no version.

**Spec quote:** docs/01-overview.md: "AKTCL's menu exposes 37 pages across 13 menus." docs/09: "The current build has 37 pages across 13 menus" and "Admin/master-data pages (TSO/admin, beyond the SR-visible menu): QC, Sales Plan, Data Entry, Supervisory Module, Retailer/Wholesale Outlet, SR Device OTP, Diamond League setup, Set Target ...". Not in docs/09: Web Entry, web Final Submit, Astha Web Entry, DSS, DS-RRS, Route-wise Memo, Loyalty Diamond League Report, Astha Gift Choice Panel (docs/09 has only "Astha Gift Choice Report"). docs/09 pages missing from the TSO menu: Task Planner, By-Route Geo Capture, Campaign Gift Redemption, Discount Report, By Outlet By Day, Online/Offline Sales, Free Sample, TSO Top Sheet Performance, TSO Daily Tracking Dashboard, Tutorial, Performance Leaderboard, Superstar Program, Daily Tracking Dashboard (13, recounted).

**Verdict: PARTLY**

**Corrected statement:** 15 items, 41 pages, the eight unspecified TSO pages, the 13 spec pages absent from the TSO menu, and the list of TSO write actions are all confirmed. Two parts are not supported: (1) "the two observed menus belong to different roles" has no evidence in the manuals; nothing says the 37/13 figure was taken from another role, it may be an older or partial count; (2) "the build changed (SR 1.0.25 adds 2FA)" is wrong or at least unproven; the differences are screenshot clipping (the web manual's own U-04 says "likely crop or build-version"), and an SR app release note says nothing about the web sidebar.

**Build implication**
- Hold the menu as data (cfg.web.menu_by_role or admin_permission). Seed the TSO row with the 15/41 inventory above; give write rights to the TSO within own scope for the actions listed.
- Replace "37 pages / 13 menus" with the union count once other roles' menus are collected; do not claim a role or build explanation.
- Open question for AKTCL: which roles see the 13 spec-only pages (Task Planner, Leaderboard, Superstar, etc.).

---

## G-man-100  Route kind (SR vs AMO), 'SR Not Set', target-route definition and who is assigned to a route

**Claim (register row 178, C-31):** Routes have a kind (sr / amo); AMO routes carry an AMO and no SR, so 'SR Not Set' is normal; the "4 Target Routes" equal the four SR "...Daily" routes and the three AMO routes are excluded; AMO manual shows ss344002 on an AMO build, so SS is a supervisor tier; route names are not unique.

**What I saw**
- Web p20 (Final Submit table, 7 rows): Apsis AMOAMO / SR Not Set; AMO-Apsis RouteAMO / SR Not Set (twice); Apsis RouteDaily / SR - Testing Banani / exist; Apsis Route 2Daily / SR - Testing Banani 2; Apsis Route 3Daily / SR - Testing Banani 3; Apsis Route 4Daily / SR - Testing Banani 3. TSO p13 shows the same routes as "FF: SR Not Set" / "FF: SR - Testing Banani ...".
- Web p27 (Data Entry Log) lists route codes: AMO-Apsis Route code 9 (two rows, same name), Apsis AMO code 500050, Apsis Route 8, Apsis Route 2 10, Apsis Route 3 11, Apsis Route 4 12. Names carry no suffix here; the "AMO" or "Daily" is a separate appended label.
- Web p3 and TSO p6: "4 Target Route", "1 Successful Login", Login Status 25% (= 1/4). The four SR route codes (8, 10, 11, 12) match the count; the AMO codes (9, 500050) would be beyond it.
- TSO p7 ("Not Logged In" and "Not Uploaded" lists): AMO-6334 (amo6334) is listed on "Apsis Route 2" and "Apsis Route 3" and on "Apsis Route"; AMO-63342 (amo63342) on "Apsis Route 2" and "Apsis Route". So AMO users appear attached to the SR routes, with SRs (sr334001..3).
- AMO p11: header "AMO 2 (ss344002)" with sub-line "BepariparaSS, 2025-11-01" on the AMO app, 13 tiles. The sub-line pattern is point name + a suffix (AMO / SS), the same as "AMO-AgrabadAMO" and "Savar BazarAMO". "SS" is not expanded anywhere.
- Browse Routes (p18): no result table shown.

**Spec quote:** docs/03-data-model.md: "`route_assignment` | route_id + user_id + valid_from | SR (and SS) on a route; effective dates for alternate-day cover" and "`route` | ... `visit_days` (e.g. Sun/Tue/Thu or Daily), zone_id". docs/07-amo-app.md: "Astha: as the SR app but with a route selector (the AMO covers several routes)". The register's "F-ADM-003 reads SS as substitute, M-14 as sales supervisor" is plan wording (lens-features, lens-data), not the spec.

**Verdict: PARTLY**

**Corrected statement:** Confirmed: the AMO-labelled routes show "SR Not Set" and the SR "Daily" routes show SRs; the count of 4 target routes is consistent with the four SR routes; an ss-prefixed user runs the AMO app; route names repeat (two identical "AMO-Apsis RouteAMO" rows). Not confirmed or contradicted: (1) there is no route kind field on any page; "AMO" vs "Daily" is a label suffix and "kind" is an inference; (2) the three AMO rows are probably two routes (code 9 appears twice in p27), so rows are not routes; (3) AMO users also appear on the SR-kind routes in TSO p7, so "AMO routes carry an AMO and no SR" is only half the picture, and an AMO is assigned across routes (docs/07 agrees); (4) the manuals do not show whether AMO routes are outside Login % or Submit %; the Final Submit page does list the AMO routes (status "--"); (5) "SS is a supervisor tier" is an inference: only the username prefix and the "SS" label are printed.

**Build implication**
- Schema: route gets label/kind separate from name (route.name, route.visit_label, route.kind derived or entered); route_assignment must allow an AMO user on many routes and an SR on the same route. Never key on route name.
- Rule: cfg.kpi.target_route_kinds default [sr]; log as ASSUMPTION backed by the 4 = 4 match (p3, p6, p27). Decide cfg.day.final_submit_allow_not_set_routes; the page lists them, so the safe parity is "listed, not blocking" until AKTCL says otherwise.
- Docs: do not word SS as "supervisor" or "substitute" until AKTCL defines it. Open question: what do SS and the route suffix labels mean.

---

## G-man-101  No verbatim string catalogue: 251 message entries across the four manuals

**Claim (register row 179):** 251 message entries (SR 99, AMO 90, TSO 24, Web 38); Android-owned strings are SR 12, AMO 12, TSO 2; the spec has no string list; the manuals have typos not to copy; the AMO mixes three success wordings; the web password-guideline example must not ship.

**What I saw**
- Recount from the consolidated inventories: SR SR-M-001..099 = 99; AMO M-001..090 = 90; TSO TSO-M-01..24 = 24; Web M-001..038 = 38. Total 251. Android-owned: SR 12 rows of kind SYS; AMO 12 rows of kind OS; TSO M-01 and M-02 = 2.
- The entries are inventory rows. Many bundle several strings (TSO M-23 drawer titles, M-21 dashboard card titles; Web M-031 tile labels, M-019 "All Selected (n)", M-037 option values), so 251 is a row count, not a count of distinct messages. Web has one true dialog (p41 "Approve outlet request?").
- Typos seen on pages: "Audion Permission Alert" and a Bengali "অবশ্যয়" (SR p26, same in AMO); "Install Unknow apps" (SR p7, AMO p5); "Diamong League Point" (SR p24); "Terrotory" (TSO p12) and "Assing Task" (TSO p17), both also visible in the PDF text layer; "Your all app data will removed." (TSO p21 Log Out! dialog); the AMO "আপানারা" (AMO p55-56 callouts). Also "Pnael" in Web p40's caption.
- AMO success wordings: "Save Successfully!" lime banner (p38); "Data Updated Successfully" dialog (p52); "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" (p24, 32, 50 per the inventory). Three forms, as claimed.
- Confirm dialogs: TSO Log Out! (p21) has Cancel and Log Out; AMO p52 caption says to click "হ্যাঁ" in the alert after Save; the other actions in the register's yes/no list (mark-paid, update base, new shop, PDA upload) were not re-viewed.
- Web p46: guideline box includes the example "PhuTysYsd623gB".

**Spec quote:** CLAUDE.md constraint 8: "keep all strings in a localization layer." docs/02-architecture.md: "Localization: Bangla-first ... all strings in a localization layer; Bengali fonts bundled." docs/06-08 contain no message text at all.

**Verdict: CONFIRMED**

**Corrected statement:** As registered, with one caveat: the 251 are inventory rows (including labels and placeholders), so the number of catalogue keys will differ after de-duplication. The yes/no-confirm list was only partly re-checked (logout and closure verification).

**Build implication**
- /packages/i18n seed catalogue: one key per app-owned string, bn verbatim with typos corrected, en alongside; exclude the 26 Android-owned rows. Keep a "source_ref" (manual, page) per key for review.
- Rule: one success wording family; distinguish "saved on device" from "uploaded". Never ship the example password string.
- Cost note: plan the catalogue as roughly 220 rows to review, not 251 distinct messages.

---

## G-man-102  No error, offline, validation or failure text exists in any manual - author the set

**Claim (register row 180):** The manuals print no error, offline, validation or failure text, so the whole set must be authored (wrong password, no network, GPS failure, permission denied, printer not connected, save/upload failure, partial sync, required-field errors, unsynced items on logout, and others).

**What I saw**
- The listed classes are absent in the inventories (SR "No error/validation message exists anywhere"; TSO "Absent from the manual: login errors, required-field messages, ... network errors, sync status, permission prompts"; Web M-036). I found no counter-example for: wrong password, validation of required fields, no network, GPS fix failure, printer not connected, save or photo or upload failure, partial sync.
- Counter-examples (texts that ARE printed and are failure or guard messages):
  - AMO p21 (and p25): a red warning triangle with "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" ("AMO and the retailer are not within range") and two buttons, "ম্যানুয়াল ওভাররাইড" and "রিফ্রেশ" (Refresh). This is an out-of-range message with an override and a refresh, so it already covers part of the "GPS fix failed or out of range, with Refresh" item.
  - AMO p66-67 (and SR Sales Deposit): a pink advisory "Before finishing today's work you must sync all operation data and submit the sales deposit" and a "Device Status ● অনলাইন" indicator.
  - AMO p67: a warning dialog "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" with Yes and No (unpaid-dues confirm).
  - TSO p14: alert "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" (already submitted today).
  - Web p20: banner that sales data before 2026-04-13 cannot be entered, "please call support".

**Spec quote:** docs/04-sync-offline-battery.md has the principle that mismatches are visible; the register quotes it. lens-sync: cfg.sync.reason_texts covers rejected rows only.

**Verdict: PARTLY**

**Corrected statement:** The manuals print no text for: login and password failures, session or role errors, required-field and form validation, no-network or offline banners, GPS timeout, permission denied, printer not connected, save/photo/upload failure, partial sync failure, interrupted update, empty-state beyond a handful, and unsynced items on logout. They DO print five guard or failure texts (AMO out-of-range with Manual Override and Refresh, sync-before-submit advisory with an online/offline status line, unpaid-dues confirm, TSO already-submitted alert, Web back-date banner). Those five are parity strings, to be catalogued verbatim (G-man-101), not re-authored.

**Build implication**
- Split the work: (a) parity strings from the manuals, verbatim, in the catalogue; (b) the authored set for the classes above, reviewed with AKTCL in bn and en.
- Keep the AMO out-of-range screen's two actions (Manual Override, Refresh) in the SR/AMO geo-flow spec, tied to cfg.geo rules in docs/05.
- Offline-first: an "offline" status variant must exist next to the printed "অনলাইন" indicator (SR/AMO Sales Deposit); the manual shows only the online state.
