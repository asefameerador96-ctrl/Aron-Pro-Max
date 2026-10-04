# Delta: AKTC Web Dashboard User Manual vs the Aron spec and plan

Date: 2026-10-04. Author: manual-delta pass for the Web manual. Output of the "find everything the spec does not cover" task.

**Inputs read in full:** `scratchpad/manuals/manual-web.md` (the 644-line consolidated inventory: 46 screens, 26 flows, 100 rules, 38 messages, 25 entities, 43 unclear items); `docs/09`, `docs/10`, `docs/03`, `docs/13`, `db/schema.sql`; plan drafts `plan/lens-features.md` (275 features, 67 gaps) and `plan/lens-data.md` (schema v2, M-01..M-45, DQ-01..DQ-34). Also read for context: `docs/22`, `PROJECT-CONTEXT.md`, `db/seed/README.md`, and, for cfg keys and admin pages only, `plan/lens-config.md` sections 1, 2.1 and 4.2; the other manual inventories and deltas (`manual-tso.md`, `manual-sr.md`, `manual-amo.md`, `delta-sr.md`, `delta-amo.md`) were searched for cross-manual checks.

**Not found:** `docs/15-feature-inventory.md` and `docs/16-data-platform.md` do not exist in the repo. "Plan" below therefore means the `plan/lens-*.md` drafts, which are not yet merged into `docs/`. An item covered only by a lens draft is counted COVERED but is still not in the spec of record.

**PDF spot-checks** against `b352c81e-AKTC_Web_User_Manual.pdf` (pages 2-7, 10-11, 19-20, 22, 24-25, 27-28, 31, 35-37, 40-46): the inventory is accurate; the only corrections are two small additions in section 6E. Log in Appendix B.

**Verdicts.** COVERED = spec or plan states it adequately. PARTIAL = mentioned, but a field, rule, state or text is missing. MISSING = absent. CONTRADICTS = spec or plan says otherwise. Messages are PARTIAL when the trigger exists but the verbatim text is not in the spec (those roll into G-man-web-26). Excel column sets of the Excel-only reports are shown nowhere in the manual, so they are not scored; they stay with G-feat-53 and G-man-web-16.

---

## 0. Headline

1. **27 gaps: 1 blocker, 16 major, 10 minor** (table in section 1). The blocker is G-man-web-03: the web Final Submit page has a **Delete Section Data** button that deletes a route's day. docs/03 forbids hard-deleting transactions and the sync design assumes a retried upload can never change a number; the two collide unless the delete is an audited void and the voided UUIDs stay in the ingest registry as tombstones.
2. **The TSO web portal is a back-office write tool, not a read-only dashboard.** The manual shows 15 menu items and 41 pages and the TSO writes through QC entry, Sales Plan, Web Entry, Astha Web Entry, Final Submit, Set Target, outlet Verify/Approve/Reject, wholesale marking and Astha gift choice. docs/09 calls these "admin pages" and lists "37 pages across 13 menus"; the two observed menus are role-specific and the real inventory is their union (G-man-web-23).
3. **Seven pages are named nowhere in the spec or plan**: Web Entry (G-01), Astha Web Entry (G-02), Final Submit on the web (G-03), DSS Report (G-13), DS-RRS Report with Print (G-14), Route wise Memo Report (G-15), and the Loyalty Program - Diamond League Report (G-21). Warehouse QC (G-06) and the Astha Gift Choice Panel (G-20) are named only as a menu heading or an open question.
4. **Six places where the manual and the spec say different things:** the device OTP is created by the server and only viewed by the TSO (G-11); every monthly target set, not only revisions, needs approval, first by a role called WMO, at variant level (G-09); QC has 10 fault reasons in two groups, not 2 numbers (G-06); the Outlet Approval Panel handles New, Close and Info requests and the TSO verifies on the web (G-07); Browse Routes lists AMO and SR, so "SS" is a supervisor tier and AMO routes need a route kind (G-24); the web Submit Status tile appears to divide by logged-in routes, not target routes (G-18).
5. **Three things the manual answers that the plan left open:** where Astha gift choices are set (Q13, G-20), what "Supervisory Module" and the "QC" admin page are (G-feat-31/32, G-22 and G-06), and that closure and info-change requests share the Outlet Approval Panel (G-feat-55, G-07). It also gives web unit labels (lighter in pieces, match in dozen) for Q8.
6. **The manual cannot tell us** (Appendix C): what 'exist' and Delete Section Data remove, how the back-date cut-off date is produced, what DS-RRS means, what 'Brand Data' does, and the Excel/PDF/print layouts of every report. The manual shows no error, validation or confirmation text for any action (M-036), no offline state (R-092) and only a TSO login (U-01).
7. Coverage by item: 235 items, 63 COVERED, 118 PARTIAL, 42 MISSING, 12 CONTRADICTS (section 2).

---

## 1. Gap table (every PARTIAL, MISSING and CONTRADICTS item, grouped into 27 gaps)

Every non-COVERED item in Appendix A points to one or more of these gap ids. Severity: blocker = a daily workflow or correctness rule breaks at cutover; major = a daily workflow or report is wrong or missing; minor = cosmetic or confirm-only.

| Gap | Manual screen + pages | What the manual says | What the spec/plan says | Kind | Sev | Concrete fix | Proposed home |
|---|---|---|---|---|---|---|---|
| **G-man-web-01** Web Entry: route-day aggregate sales entry (Issue / Return / Sale / Memos / Successful Call, per sub-channel) has no model | WEB-S-18 p.19; F-10; R-043..R-049; E-14 | Data Entry > Web Entry. Read-only 'Date: 2026-04-13'; single-select Wing..Zone; 'Select Classifications' multi-select tags ('GT Channel'); 'Select Route' (must be chosen first); 'Target Outlet' read-only (37); 'Successful Call' input (5); green Save; blue 'Brand Data' button (purpose never stated). Grid: Sku Name / Issue (0) / Return (0) / Sale (read-only, derived) / Memos (0) / one sub-column per selected classification ('GT Channel' > 'GT'). Caption: enter Issue, Return, Memos 'how much total was sold' for each SKU. | docs/09 names 'Data Entry' only as an admin page. lens-features F-ADM-024 / G-feat-30 ASSUME a manual backfill of memos, attendance and stock through the normal ingest; lens-data W11 assumes `memo.entry_source`. The manual shows route-day TOTALS (no outlet, no memo, no visit), which cannot go through memo ingest. No table holds Issue, Return, Sale, Memos or Successful Call per route-day-SKU. | missing-feature | major | Model web entry as its own source: `web_entry_route_day(route_id, business_date, successful_calls, target_outlets_snapshot, status, entered_by, entered_at)`, `web_entry_line(entry_id, sku_id, issue_qty, return_qty, sale_qty, memo_count)`, `web_entry_line_class(line_id, sub_channel_id, qty)`. Decide and write down: Sale = Issue - Return (inferred; confirm), Successful Call <= Target Outlet, no negatives, sum of class columns = Sale, one entry per route-day (re-save replaces, audited), and how it combines with app-synced data for the same route-day (default: mutually exclusive, flagged if both exist, aggregates never add both). Ask a TSO what 'Brand Data' does (hypothesis, unverified: brand-level memo counts for BSR). Ask how often TSOs use Web Entry today (it fixes whether this is pre-pilot or Phase 6). | docs/09 'Data Entry'; docs/03 new tables; lens-data new migration after M-32 (call it M-46); F-ADM-024 rewrite; docs/10 aggregation rules; cfg.web.entry_* |
| **G-man-web-02** Astha Web Entry: outlet x SKU quantity grid for Astha-channel outlets, mandatory for them | WEB-S-20 p.21; F-11; R-050, R-051; E-14b; M-020 | Data Entry > Astha Web Entry (heading 'Web Entry By Outlet'). Caption: 'Only for Astha outlets, to do Web Entry use the Astha Web Entry menu.' Filters: Wing..Zone, Route, Channel (pre-set 'Astha Channel', with X), Category (multi, 'All items are selected.'), Date, blue Filter. Grid: 'Outlet' rows ('shaiful (3362328)') x SKU columns (MaxR-10S ... MaxDB-20S 20HL, Avon-20S, ...), empty inputs. No Save button is visible. | Absent. docs/10 Astha covers targets, achievement, gift choice and the Astha report only. No table holds a per-outlet per-SKU quantity entered on the web. | missing-feature | major | Add `web_entry_outlet_sku(outlet_id, sku_id, business_date, qty, status, entered_by, entered_at, UNIQUE(outlet_id, sku_id, business_date))`. It must feed `fact_daily_outlet`, `agg_daily_outlet_brand` and Astha quarter achievement. Define overlap with SR-app memos for the same outlet-day (replace vs add vs flag), the missing Save (explicit Save recommended), units (sticks), which outlets are listed (Astha channel outlets of the route, per cfg), and why Astha outlets are excluded from plain Web Entry (ASSUMPTION: outlet-level STD is needed for tier targets and gifts). Confirm with a TSO. | docs/10 Astha; docs/03; new migration (with G-man-web-01); F-ADM-018; cfg.astha.* |
| **G-man-web-03** Final Submit on the web, with 'Delete Section Data' (a destructive delete the spec forbids) and no defined interplay with idempotent sync | WEB-S-19 p.20; F-12; R-052..R-056; M-003, M-004, M-022..M-024; E-15 | Data Entry > Final Submit. Pink banner (cut-off, G-man-web-04). Single-select Wing..Zone + green Filter. Table Route / SR Name / Status / Actions. Sample: 'Apsis AMOAMO' and 'AMO-Apsis RouteAMO' (x2) show 'SR Not Set' and '--'; 'Apsis RouteDaily' / 'SR - Testing Banani' shows Status 'exist' and a red 'Delete Section Data' button; routes 2Daily to 4Daily show '--'. Pink notice: 'Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit'. 'Date of Data Entry' picker (2026/04/13) + dark-blue Submit. No confirm dialog or toast is shown for Submit or Delete. | docs/09: `POST /day/final-submit {zoneId, businessDate}` once per zone per day, TSO app (docs/08, TSO manual TSO-S-09..12). No web Final Submit page. No route-level delete of entered data. docs/03: 'never hard-delete transactions'. G-feat-17 and G-feat-64 cover reopen and 'Not Set' routes only. | contradiction | blocker | Build the page. Implement 'Delete Section Data' as an audited VOID with reason, never a SQL delete: `data_void(route_id, business_date, scope, reason, voided_by, voided_at, summary)` plus status 'void' on the rows. The manual does not say what 'exist' means or what is deleted (web-entry rows only, or app-synced memos too). Default: web-entry rows only; voiding app-synced memos needs an explicit decision and a higher role. Sync interplay (the blocker): voided client_uuids stay in `ingest_registry` as tombstones (state=voided), so a late or retried upload from the SR phone is rejected with reason `voided_by_admin`, shown on the SR's device-vs-server screen, and can never resurrect or double a number. Offer Delete only before Final Submit. Selectable 'Date of Data Entry' must respect G-man-web-04; the TSO app's Sales Date is read-only today, keep both. Banner texts become cfg strings. | docs/09 Data Entry + API (`POST /day/final-submit` vs web); docs/04 sync (tombstones); docs/03 conventions; lens-data M-30 / M-34 / M-46; F-ADM-029; cfg.web.delete_section_data_* |
| **G-man-web-04** Back-date cut-off for web data entry, with a 'call support' override | WEB-S-19 p.20; R-052, R-053; M-003; U-14 | Pink banner (Bangla): 'You cannot enter any sales data from before 2026-04-13. If any earlier data must be entered, please call support.' The date is a literal in the screenshot. Whether it is rolling (today only), a configured date, or the per-zone 'Data Entry Date' shown on Sales Plan is not stated. | None for the web. The plan has `cfg.sync.max_backdate_days` = 7 (DQ-09) for app sync, a different surface with a different window. | missing-rule | major | Define `cfg.web.entry_backdate_days` (0 = today only; ASSUMPTION) or `cfg.web.entry_min_date`, with per-zone override. Render the banner from it with the support contact (`cfg.support.contacts`). Replace 'phone support, who edits the DB' by an audited unlock: `entry_unlock_grant(zone or route, date range, granted_by, reason, expires_at, used_at)` raised by admin or support. Confirm whether the Sales Plan 'Data Entry Date' is the lever (G-man-web-05). Keep the app-sync window separate and document both in docs/09. | docs/09 rules; lens-config section 1.2 (cfg.day.* and new cfg.web.*); F-ADM-029; docs/04 |
| **G-man-web-05** Sales Plan is TSO-operated and carries zone contact fields and a Data Entry Date the schema lacks | WEB-S-09, S-10 pp.9-11; F-07; R-029..R-034; E-08 | Table Zone Name / Wing / Division / Territory / House / Email / Address / PDA Contact No. / Enabled SKUs / Data Entry Date / PDA / Actions. Pencil makes Email, Address, PDA Contact No. editable, Enabled SKUs removable chips with a tri-state 'SKU List' checkbox tree, date input (04/12/2026); green tick saves, red x cancels. Caption p.9: 'add new SKU or remove'; p.11: 'SKUs that will sell in your territory'. Save unit is one zone row. | `sales_plan(zone_id, sku_id, enabled)` and F-ADM-006 (zone x SKU, 'admin, TSO?'). `zone` has no email, address, PDA contact or data-entry date. Caption says territory, screen is zone. | missing-field | minor | Add `zone.email`, `zone.address`, `zone.pda_contact_no`, `zone.data_entry_date` (meaning unconfirmed; may drive G-man-web-04) and clarify the blank 'PDA' column. Let TSOs edit the plan of their own zones (`cfg.sales_plan.edit_roles`), save one zone row atomically with audit, and offer 'apply to all zones of this territory' (the caption implies territory scope). Plan changes reach phones at next bundle (G-feat-51). | docs/03 zone, sales_plan; lens-data M-04 / M-18; F-ADM-006; cfg.sales_plan.* |
| **G-man-web-06** QC on the web: Market QC entry, Warehouse QC entry, two QC reports (Excel and PDF), and a 10-reason fault taxonomy the spec collapses to 2 numbers | WEB-S-05..S-08 pp.5-8; F-04..F-06; R-022..R-028; E-12, E-13 | Market QC Entry: Wing..Zone + Select Route + Choose Date; grid per SKU; group 'Manufacturing Fault': Brand Mix Up / Cigarette Visual Fault / Damaged & Crushed Pack/Outer CBC / Outer, Pack or Stick missing / Others; group 'Marketing Fault': Damp Cigarette stick / Stock damaged during transport / Shelf life expired stock / Spotting on Cigarette / Others; number cells default 0; green Submit. Warehouse QC Entry: same grid per Zone + single date, no route. QC Report (Market & Warehouse): geo + date range, 'Get Excel' and 'Download PDF'. Route wise QC Report: geo + date range + 'QC Type' (Market QC / Warehouse QC), Excel. | docs/03 `qc_entry(visit_id, sku_id, production_fault_qty, transport_fault_qty)` (visit-bound, app only). cfg.qc.fault_kinds = two values. No warehouse QC, no web QC entry, no PDF. G-feat-32 guesses the QC admin page is a claims review. | missing-feature | major | Replace the two columns by a `qc_fault_type` code table (code, group MFC or MKT, label_bn/en, applies_to app / web_market / web_warehouse, sort, active) holding the union of the SR-app 6 types (G-man-sr-03) and the web 10 labels, with an explicit mapping of overlaps (Damaged & Crushed Pack/Outer CBC, Outer/Pack/Stick missing, Others, Stock damaged during transport ~ app 'stock damaged during route service', Shelf life expired ~ app 'expired stock 4 months+'). Add `qc_summary_entry(kind market or warehouse, zone_id, route_id NULL for warehouse, business_date, sku_id, fault_type_id, qty, entered_by, UNIQUE(kind, zone_id, route_id, business_date, sku_id, fault_type_id))`. Reports `qc` (json, xlsx, pdf) and `qc-route`. Web-entered QC and app QC share one fact with a source column and are never double counted. Decide re-entry (replace vs add), max qty, who may enter. Group naming: web 'Manufacturing/Marketing' = app 'MFC/MKT' = Bangla 'production/transport'; the spec's `production_fault` / `transport_fault` are the Bangla labels, so keep stable codes MFC / MKT. | docs/03 qc; lens-data M-08; lens-config cfg.qc.fault_kinds -> cfg.qc.fault_types; docs/09 QC; G-man-sr-03 |
| **G-man-web-07** Outlet Approval Panel handles all three request types and does Verify as well as Approve/Reject on the web | WEB-S-38..S-40 pp.39-41; F-20; R-077..R-082; M-005..M-008, M-027, M-033; E-04b | Outlet > Outlet Approval Panel (heading 'Firefly Outlets Reports'). 'Outlet Type' = New Outlets / Close Outlets / Info Changes, Get Data / Get Excel. Table: Division Code, Territory, Territory Code, House, House Code, Zone, Zone Code, Route, Route Code, Cluster, Owner Name, Phone Number, Latitude, Longitude, Status, Actions (no Outlet Name, no date). Pending row: [Verify]. Verified row: [Reject] [Approve]. Approve opens 'Approve outlet request? / Do you want to approve this request?' with [Yes, approve it] [Cancel]. SR Outlets Reports lists the outcome by category and date range. | docs/09: 'Outlet Approval Panel (pending new-outlet requests + who verified)'. docs/03: `verified_by` (AMO), `approved_by` (web). F-WEB-032 'approve -> creates outlet; reject with reason'. G-feat-55 asks whether closure and info changes are in the panel. | contradiction | major | Panel serves all three types through one filter. Verification exists on the web as well as in the AMO app (G-man-amo-29, G-man-amo-31): decide who may verify and record `verified_via` (app or web) and the verifier role (`cfg.outlet.verify_roles`). Reject only on Verified rows; the manual shows no reason box, so add one (`cfg.outlet.reject_requires_reason`) and a confirm dialog for Verify and Reject (texts unknown; author them). Add Outlet Name, request date, requester and 'verified by' columns (needed to tell closure requests apart). Approving a closure sets `outlet.status` closed (never delete); approving an info change applies `proposed`; approving a new outlet assigns the outlet code. Status reaches the SR app on next bundle. | docs/09 Outlet; lens-data M-15 (events); F-WEB-032; cfg.outlet.*; G-man-amo-29 |
| **G-man-web-08** Retailer Wholesale Outlet: bulk marking flow is only a page name in the spec | WEB-S-41, S-42 pp.42-44; F-21; R-083..R-086; M-018, M-025, M-032; E-04 | Sidebar item 'Retailer Wholesale Outlet' (heading 'Wholesale Retailers'): Wing..Zone multi-select, 'Wholesale Status' (No / Yes), 'Outlet Search' free text, Get Data / Get Excel. Table with row checkboxes and select-all; columns Zone Code, Zone Name, Outlet Name, Owner Name, Contact Number, Route Code, Route, Outlet Code, Cluster Type, Cluster Name, Address, Geo Cla(ssification). Pager '1-10 of 43'. A floating basket shows the live selected count; it opens 'Selected Outlets (3)' (Zone Name / Route / Outlet Code / Outlet Name / Remove). Blue Submit flags them Wholesale. No unflag path, no confirmation, cross-page selection not shown. | docs/09 lists 'Retailer/Wholesale Outlet' as an admin page. docs/13 Q17 (wholesale/distributor flows). lens-data M-16 `outlet.outlet_kind` retail or wholesale; G-feat-33. | underspecified | major | Bulk endpoint `POST /outlets/outlet-kind {batchUuid, outletIds[], kind, reason}` (idempotent by batchUuid), one audit row per outlet and `outlet_kind_history`. Support both directions behind `cfg.outlet.wholesale_unmark_allowed`. Keep the selection across pages (server-side or client-side set; define the select-all scope). Define the effect before building: price type (cc or distributor), KPI target-outlet counting, bundle label, geo gate (docs/22 P-16: wholesale buyers have 10^5 stick lines). Keep outlet kind separate from the channel enum. | docs/13 Q17; lens-data M-16 / W12; F-ADM-010; cfg.outlet.wholesale_* |
| **G-man-web-09** Targets: approval header, WMO level, variant product level and start/end dates; initial targets also need approval | WEB-S-34..S-36 pp.35-37; F-16, F-17, F-18; R-071..R-075; E-18; M-028 | Set Target (TSO-1012, heading 'Target Settings'): Wing/Division/Territory multi, 'Choose Month' (April 2026), Apply, Download Sample, 'Upload Excel Panel Section'. Target Approval List: Target Name 'Phatherhat of April-2026' / Product Type 'variant' / Target Type 'stt' / Start Date 2026-04-01 / End Date 2026-04-30 / Approval Status 'WMO approval pending' / Action (green eye = details, blue icon = download target file). Caption: 'to see the Approval Status of your monthly SET target'. Target Allocation Report: geo + one date, Get Excel. | docs/10: targets monthly by route and zone per category, brand or SKU; approval levels apply to REVISIONS (`target_revision`). schema `target.product_level` in category, brand, sku; `month` only. F-WEB-030 'Target Revise List: approve or reject' for TSO, DMO, WM. lens-config default levels tso, dmo, wm (Q12 open). | contradiction | major | (1) Every monthly target SET is a submission that needs approval, not only revisions: add header `target_set(id, name, territory_id, product_type, target_type, start_date, end_date, status, source manual or excel, source_media_id, submitted_by, submitted_at)` with `target` rows as children, status draft, submitted, wmo_pending, approved, rejected, returned. (2) Add `variant` to `target.product_level`. (3) 'stt' = STD (confirm other target types, e.g. memo). (4) Define the approver role 'WMO' (map to `wm` or add `wmo`) and fix the lens-config default so level 1 is WMO, not DMO. (5) Store start and end dates, not only `month`. (6) TSO screen is status + details + file download; the approver's screen is not in this manual. (7) Auto-name the header '<Territory> of <Month>-<Year>'. Decide whether the current live target stays in force while a new set is pending. | docs/10 Targets; docs/13 Q12, Q16; docs/03 target; lens-data M-21; F-WEB-029 / F-WEB-030; cfg.target.revision_approval_levels, cfg.target.product_types |
| **G-man-web-10** Target entry: manual route-wise grid, Excel sample download and upload, stored file | WEB-S-34 p.35, S-36 p.37; F-16; R-071, R-072; E-18, E-27 | After Apply the TSO enters targets route by route; or 'Download Sample', fill, upload through the collapsible 'Upload Excel Panel Section' (expanded panel, grid and Save are not shown). The submitted file can be downloaded later (blue icon, p.37). | F-ADM-014 says 'bulk upload' in one clause. docs/10 says targets are fractional and 'split by a formula down to route and SKU' (Q12 open). | underspecified | major | Specify the sample workbook (route code, route name, variant code, STD target, optional memo target), validation (>= 0, numeric, known route and variant, territory in scope, duplicates, month) and a per-row error report (reject whole file vs partial: recommend all-or-nothing with a downloadable error sheet). Store the uploaded file in Blob and link it to the header (G-man-web-09). The current build has the TSO supply route x variant values; no automatic split is evidenced. Keep the formula split as an optional improvement behind `cfg.target.split_method`. Add an entry window (`cfg.target.entry_window`). | docs/10 Targets; docs/09 Set Target; F-ADM-014; cfg.target.*; lens-data M-31 media_object (purpose target_upload) |
| **G-man-web-11** SR Device OTP is a view-only list of OTPs the server creates when the SR device attempts login; the spec has the TSO issue them | WEB-S-44 p.46; F-23; R-087, R-088; M-016; E-23 | Menu 'SR Device OTP' (heading 'SR OTP Panel'): Wing..Zone multi-select + indigo 'View'. Table Field Force Name / Username / Zone Code / Zone / OTP; empty state 'No Data'. Caption: after the SR taps Login on the SR device, the TSO logs in to the TSO portal, opens SR Device OTP, clicks View and sees the OTP list 'according to route' (no route filter on screen). | docs/09: `POST /auth/bind-device {deviceUuid, otp}` 'TSO-issued OTP'. lens-features F-ADM-022 'pick user -> issue OTP'. lens-data M-41 stores only `code_hash`. | contradiction | major | Flip the model: the SERVER creates the OTP when a device logs in on a new device or after a new app version (SR manual: 4 digits; G-man-sr-17), the TSO only VIEWS it. So there is no 'issue' button; the code must be retrievable by the TSO (encrypted at rest, or regenerate-on-view with audit; not a one-way hash); the list shows only SRs with an open request; add Create Time and status. Merge the 5-column web layout with the 8-column SR-manual layout (superset, G-man-sr-18). Add search by username and a manual refresh for launch-day volume. Throttle OTP creation per user and expire it (`cfg.auth.otp_ttl_min`). Online only on both sides (section 5). | docs/09 auth + device; lens-data M-41; F-ADM-022 / F-API-036; cfg.auth.otp_*; G-man-sr-17, G-man-sr-18 |
| **G-man-web-12** Web login and session details the spec does not state (Remember me, no self-service reset, no failure text) | WEB-S-01, S-02 p.2; F-01; R-001..R-004; E-01 | Card 'Welcome back / Login to your account'; 'User ID' (sample 'tso-apsis'), 'Password' with eye toggle, 'Remember me' checkbox (unchecked), 'Login'. Absent on screen: Forgot password, language switch, captcha, OTP, role picker. Top-right avatar shows the user ID ('tso-apsis', 'TSO-1012'); red Logout at the sidebar foot; round '<' collapses the sidebar. No error text for bad credentials is shown anywhere. | docs/09 `POST /auth/login` only. F-WEB-043 'as F-SYS-001'. G-feat-34: lockout and forgot-password unspecified. No Remember me. | missing-rule | minor | Add Remember me (long-lived refresh token; `cfg.auth.web_remember_me_days`). Label the field 'User ID'. Record the parity decision that the current web has NO self-service reset (reset goes through the TSO or support), and author the missing messages (wrong credentials, locked, expired session) in bn and en. User ID formats in the manual: 'tso-apsis', 'TSO-1012', 'AMO-6334'; decide case sensitivity (recommend case-insensitive, citext). | docs/09 Auth; F-SYS-001 / F-WEB-043; lens-data M-40; cfg.auth.web_* |
| **G-man-web-13** DSS Report (Sales Summary): the pre-Final-Submit check report is not in the spec or plan | WEB-S-23 p.24; F-15; R-055, R-061, R-062; E-17 | Reports > DSS Report. Single-select Wing..Zone, 'Field Force Type' (SR), 'Choose Date' (single), 'Category' (multi, 'All Selected (4)'), Get Data / Get Excel. Grid per route: Route Code / Route (blue link to outlet-wise sales of that route) / SR Name / No of Outlets / No of Memos / one column per SKU (MaxR-10S ... SM-American 10s). Yellow summary rows: Total, DCC STD, Gold STD, RCC STD, Platinum STD, Diamond STD, GT STD, Silver STD. Sample route 8: 36 outlets, 5 memos, GT STD row mirrors the route row. The Final Submit page tells the TSO to check here first. | Absent: no hit for 'DSS' in docs/01-13, docs/22 or the plan lenses. | missing-feature | major | Add report `dss` (json + xlsx): rows per route for one date and field-force type (SR or AMO), SKU columns = SKUs enabled in the zone, summary rows per outlet sub-channel evaluated as of the sale date (use `dim_outlet` history). Drill-down `GET /reports/dss/routes/{routeId}/outlets?date=` for outlet-wise sales. Define 'No of Outlets' (outlets on route or outlets visited; the sample shows 36 here vs Target Outlet 37 on Web Entry for the same route and day) and 'No of Memos'. It must include web-entered data (G-man-web-01) and refresh within a minute because it is the gate before Final Submit. | docs/09 Reports; docs/10 aggregates (`fact_daily_route_sku` + outlet class); lens-features new F-WEB row; lens-data section 4.5 |
| **G-man-web-14** DS-RRS Report with a Print view that shows discount data before Final Submit | WEB-S-24 p.25; R-058 | Reports > DS-RRS Report. Single-select Wing..Zone, Field Force Type (SR), Choose Date (single), Route, Category (multi, 'Cigarette'); Get Data, Get Excel, orange Print. Caption: 'before Final Submit, from the Print button you can see discount data in the print view'. No grid is shown. The acronym is never expanded. | Absent. Not named in docs/09, docs/10 or the plan. | missing-feature | major | Do not guess the layout. Obtain a sample printout and the meaning of DS-RRS from AKTCL. Hypothesis (UNVERIFIED): a route-day delivery and settlement statement (issue, return, sale, discount), which would tie into G-feat-02 (stock return) and G-feat-03 (cash settlement). Build as a report plus a server-rendered print view (HTML to print or PDF) with discounts per promotion group (shares the offer model with the Discount Report). Log prints in `report_export_log` (format print). | docs/09 Reports; G-feat-02 / G-feat-03; lens-data M-20 and M-43; new F-WEB row |
| **G-man-web-15** Route-wise reports: Memo Report missing; STD and BSR & CPR have a filter set and three names | WEB-S-25, S-28, S-29 pp.26, 29, 30; R-069, R-070; U-22, U-40 | 'Route wise STD Report' (heading 'Route Wise Live STD Report'): geo multi, date range, Category, Product Type (SKU), Active Status, Select Products (All Selected (38)); grid Wing_Code ... Zone, Route_Code, Route, SKU columns; Get Data / Get Excel. 'Route wise Memo Report': same filters. 'Route wise BSR & CPR Report' (heading 'Route Wise Strike Rate & BSR Report'; caption 'Route Wise Strike CPR & BSR'): same filters. | F-WEB-015 Route-wise STD and F-WEB-018 CPR & BSR exist. No Route-wise Memo Report. One name each. | missing-feature | minor | Add `route-memo`. Give each report one parameter object (G-man-web-16) and store the display aliases. 'Live' means today's aggregate with a 'data as of' stamp. BSR denominator per Q9; show both candidates (lens-data section 4.5). | docs/09 Reports; F-WEB-013..018; lens-data section 4.5 |
| **G-man-web-16** Report filter vocabulary and output behaviours are unspecified (every report depends on them) | pp.4, 6, 8, 16, 22-34, 36-39, 46, 48; R-013, R-038, R-059, R-060, R-063, R-065, R-066, R-069, R-093, R-094, R-096..R-100; M-017, M-019, M-037 | Beyond the five geography selectors each report adds its own controls: Choose Date vs Choose Date Range; Location (Wing...); Date Grouping (Total); Category (multi); Product Type (Category, SKU, Total); Active Status (All); Select Products (multi); Classification Type (Total); Report Type (STD MEMO; By Outlet 'All Selected (2)'); Field Force Type (SR, AMO); Sub Channel (multi, 9 values); 'STD Criteria ('000)' and 'Memo Criteria' as operator + number (default '> 0'); Outlet Code; QC Type; Submit Status (Done, Not Done); Year / Quarter ('Q-3 (Jul-Sep)') / Month; Report Category or Outlet Type; Wholesale Status; Gift Status. Outputs: Get Data (grid), Get Excel, Download PDF (QC), Print (DS-RRS), 'Download Report' (By Outlet), per-column search (Browse Retailer), sortable columns (SR Outlets), pager '1-N of N items' with '10 / page'. Reports use multi-select 'All Selected (n)', entry pages single-select pre-filled. Dates appear as 2026-04-12, 04/12/2026, 2026/04/13, 'April 2026'. | docs/09: standard geography filter and `GET /reports/<name>?filters&format=json/xlsx`. Excel column sets unknown (G-feat-53). 'Render on screen first' replaces the 13 Excel-only reports. | underspecified | major | Define one `ReportQuery` schema in /packages (geo node lists, date or range, `location` group-by level, `dateGrouping`, category, productType, products, activeStatus, classificationType, subChannels, reportType, fieldForceType, `stdCriteria`, `memoCriteria`, outletCode, plus per-report extras) and a per-report parameter table (Appendix D of this file lists every filter seen). Formats json, xlsx, pdf, print. ISO dates everywhere; month pickers 'Month YYYY'. Keep 'All Selected (n)' defaults, server-side scope, one export label ('Get Excel'). The deliberate departure 'render on screen first' needs the same column sets as today's Excel files: the manual shows none, so collect sample .xlsx files from AKTCL. | docs/09 API contract; /packages; F-WEB-040; cfg.report.lists.*, cfg.report.page_size_* |
| **G-man-web-17** Dashboard: tiles, charts and date-range filter beyond the spec's list | WEB-S-03 p.3; F-02; R-014..R-019; M-031; E-17..E-21 | Orange 'Filter' (date range). Tiles: Sales (Cigarette), Sales (Bidi), Sales (Lighter in Pcs), Sales (Match in Dozen), each 'Total Sales' + ring %; Live Strike Rate (43 Target Outlet, 1 Successful Calls, 2.3%); By Channel Successful Call (GT, DCC, Astha, RCC, MT, HoReCa); Final Submit Status (Total Zone 1, Total Service Zone 1, Remaining 1, Total Final Submit 0.0%); Login/Submit Status; By Channel STD; 'Breakdown - STD /Memo' toggle with gear icon. Caption also names By Segment Contribution, Sales Trend, Geo Fencing, FF Geo Location, and calls the breakdown 'STD/Value/Memo'. Blue 'i' icon on tiles (content not shown). | docs/09 dashboard bullet: sales vs target by category, live CPR, calls by channel, geo-validation %, login/submit %, final-submit status, sales by brand/channel, SR positions; 'loads from rollups, no button press'. docs/10 leaves units open (Q8). | missing-field | minor | Add: date-range filter (default today, MTD optional), STD / Value / Memo breakdown toggle (Value needs `net_mtk` in the aggregate), By Segment Contribution, Sales Trend, By Channel STD, 'Total Service Zone' (define; maybe zones with at least one SR-kind route), 'i' tooltips fed by the KPI registry. Units shown on the tile titles close part of Q8 for the web: lighters in pieces, matches in dozen. Ring % basis = achievement vs target (inferred; confirm). Live Strike Rate = Successful Calls / Target Outlet (1/43 = 2.3%), consistent with docs/10 CPR. | docs/09 Dashboard; docs/10 KPI table; lens-data section 4.5; cfg.dashboard.* |
| **G-man-web-18** Submit % on the web tile appears to use 'logged-in' as its base, not 'target routes' | WEB-S-03 p.3; R-018; E-21 | 'Login/Submit Status': Login Status 25.0% = '4 Target Route' / '1 Successful Login'. Submit Status 0.0% shown with '1 Login successfully' and '0 Submitted successfully' (checked on PDF p.3). | docs/10: 'Submit % = routes uploaded / target routes (apps: / logged-in)'. lens-data D-03 makes target routes canonical and renames the apps' figure 'Upload-of-login %'. | contradiction | major | The web tile pairs Submit with 'Login successfully', which reads as submitted / logged-in (the apps' basis), not submitted / target routes. With 0 submitted the sample cannot prove it. Do not assume either; check a live dashboard on a day with submits (Appendix C). Until then expose both under the D-03 names and label the tile with its basis (`cfg.kpi.submit_pct_denominator`). In the sample, 'Target Route' = 4 equals the four '...Daily' SR routes of zone 6334; the three AMO routes are excluded (G-man-web-24). | docs/10 KPI table; lens-data D-03; cfg.kpi.submit_pct_denominator |
| **G-man-web-19** Data Entry Log and Final Submit Log: column semantics | WEB-S-26, S-27 pp.27-28; F-13, F-26; R-057, R-064; E-15, E-22 | Data Entry Log (heading 'Download Upload Log Report'): per route, group 'DOWNLOAD' with MAX / MIN / Count (an UPLOAD group is probably off-screen); times HH:MM:SS. Sample: MAX 16:57:25 is earlier than MIN 17:35:50 (checked on PDF p.27). Final Submit Log: per zone SUBMIT_STATUS (Done / Not Done), MAX_TIME, MIN_TIME, COUNT; the 'Not Done' sample row has times and count 1. | `route_log(download_first, download_last, upload_first, upload_last, counts)` and `final_submit(zone, date, submitted_by, submitted_at)`; no per-zone MIN, MAX or COUNT. | underspecified | minor | Define MIN = first and MAX = last event time and do not copy the swapped sample. For the Final Submit Log define the three numbers (likely first and last route sales-submit time and the number of routes submitted while the zone is Not Done) and derive them from `route_day`. Show Dhaka time. Keep both UPLOAD and DOWNLOAD groups. | docs/09 Reports; lens-data M-31 (route_log view) and M-32; F-WEB-016 / F-WEB-017 |
| **G-man-web-20** Astha Gift Choice Panel (the 'TSO portal' of Q13) and its report | WEB-S-45, S-46 pp.47-48; F-24; R-089..R-091; E-24 | Astha Gift Panel > Astha Gift Choice Panel (heading 'Astha Gift Choice'): single-select Wing..Zone + Route, Get Data. Table: Wing Code ... Route Code, Outlet Code, Owner Name, 'Gift List' (per-row dropdown: 'Ceiling Fan (56 inch)', '24 pcs Dinner Set', '27 pcs Dinner Set'); no Save button. Astha Gift Choice Report: geo + 'Gift Status' (Yes / No), Get Excel. SR manual (SR-S-53): Astha Photo Capture lists only outlets with a TSO-portal-assigned gift. | docs/13 Q13 asks where Astha gift choices are set ('the TSO portal'). F-TSO-020 'unknown surface'. lens-data M-24 `gift_assignment`. docs/10: gift chosen per outlet in the TSO portal. | underspecified | major | The surface is now known: a web page, route-scoped list of Astha outlets with a gift dropdown. Specify the save (per-row autosave or Save), whether a choice can be changed or cleared and until when (lock when the SR photo exists), one gift per outlet per quarter (`gift_assignment` unique key), catalogue per tier and quarter (`cfg.astha.gift_catalog`), and what Gift Status Yes / No means (hypothesis: gift chosen or not; confirm). Gift assignments travel in the bundle (`giftAssignments`) so the SR app lists assigned outlets offline. | docs/10 Astha; docs/13 Q13; lens-data M-24; F-TSO-020 / F-WEB-034; cfg.astha.* |
| **G-man-web-21** Loyalty Program - Diamond League Report (outlet-wise loyalty points) is not a listed report | WEB-S-33 p.34; F-25; R-068; E-25 | Reports > 'Loyalty Program - Diamond League Report': geo + date range; 'Get Data' shows outlet-wise Loyalty Point on screen; 'Get Excel'. Table columns not shown. | F-WEB-022 'Campaign Gift Redemption' (redemptions, points, photo-verified). docs/10 Diamond League ledger and redemption. | missing-feature | minor | Add an outlet-wise loyalty point statement (earned, spent, balance, expiring points per the SR Points screen, G-man-sr-13) as its own report, on screen and Excel, separate from Gift Redemption. Source: `loyalty_ledger` / `agg_outlet_balance`. | docs/09 Reports; docs/10 Diamond League; lens-data M-23; dw agg_outlet_balance |
| **G-man-web-22** Supervisory Module = AMO Call Report (contents were unknown in the spec) | WEB-S-37 p.38; F-19; R-076, R-100; E-19 | Supervisory Module > AMO Call Report: Wing..Zone single-select, 'Field Force Type' (AMO), 'AMO' ('AMO-6334'), 'Report Type' (Summary), 'Choose Date', 'Report By' radio By Date / By Month, Get Excel. | docs/09 names 'Supervisory Module' as an admin page; G-feat-31 and F-ADM-025 say contents unknown. | underspecified | minor | Answer G-feat-31: it is AMO call reporting. Define 'Summary' (per AMO: control calls, joint calls, own sales calls, outlets covered; other Report Types unknown) and by-date vs by-month grouping; scope the AMO selector to the zone. Feed from `call_assessment`, `distribution_check` and AMO-kind visits. | docs/09 Reports; lens-features F-ADM-025; lens-data M-26 / M-27 |
| **G-man-web-23** Role x menu x action matrix: the TSO portal is wider and writes more than the spec's '37 pages, 13 menus' | WEB-S-02 p.2 and all; U-01, U-04 | TSO sidebar has 15 top-level items and 41 pages: Dashboard; Retailer (1); QC (4); Sales Plan; Products (6); Route Planning (1); Data Entry (3); Reports (13); Target (3); Supervisory Module (1); Outlet (2); Retailer Wholesale Outlet; Credentials; SR Device OTP; Astha Gift Panel (2). TSO write actions: QC entry (market, warehouse), Sales Plan edit, Web Entry, Astha Web Entry, Final Submit and Delete Section Data, Set Target, outlet Verify / Approve / Reject, wholesale marking, gift choice, own password. Screenshots differ by build (Reports shows 9 items on pp.22-24, 13 on pp.25-34; Credentials, SR Device OTP and Astha Gift Panel are missing from older shots). | docs/09: '37 pages across 13 menus' (Dashboard, Retailer, Products, Route Planning, Reports, Target, Outlet, Credentials, Astha Gift Panel, Tutorial, Performance Leaderboard, Superstar, Daily Tracking) with QC, Sales Plan, Data Entry, Supervisory, Wholesale, OTP, Set Target as 'admin pages (TSO/admin)'. lens-features marks them 'admin, TSO?'. | underspecified | major | The two observed menus belong to different roles; the real inventory is their UNION. At least 8 TSO pages are named nowhere in docs/09 or the plan (Web Entry, Final Submit (web), Astha Web Entry, DSS, DS-RRS, Route wise Memo, Loyalty - Diamond League Report, Astha Gift Choice Panel); 13 spec pages are absent from the TSO manual (Task Planner, By-Route Geo Capture, Campaign Gift Redemption, Discount, By Outlet By Day, Online/Offline Sales, Free Sample, TSO Top Sheet, TSO Daily Tracking, Tutorial, Performance Leaderboard, Superstar, Daily Tracking) and belong to other roles. Hold the matrix as data (`admin_permission` or `cfg.web.menu_by_role`), give the TSO the write actions listed above within own scope (do not hide them behind 'admin'), and replace '37 pages / 13 menus' by the union count. Collect the DMO, WM, Top and AMO web menus from the sponsor's screenshots (U-01). | docs/09 pages; lens-config section 4.1 and P9 / P10; F-ADM-*; cfg.web.menu_by_role |
| **G-man-web-24** Route kind (SR vs AMO), 'SR Not Set', target-route definition and who is assigned to a route | WEB-S-17, S-19, S-26 pp.18, 20, 27; R-042; E-06, E-07; M-022 | Final Submit for zone 6334 lists 7 routes: 'Apsis AMOAMO', 'AMO-Apsis RouteAMO' (x2) with 'SR Not Set'; 'Apsis RouteDaily', 'Apsis Route 2Daily', '3Daily', '4Daily' with SRs. The Dashboard shows '4 Target Route'. Route names carry a trailing type or visit-day word ('...Daily', '...AMO'). Data Entry Log has AMO routes too (codes 9, 500050). Browse Routes: 'list of AMOs and SRs assigned per route and section'. | docs/03 `route(visit_days)` and `route_assignment` 'SR (and SS)'. lens-features F-ADM-003 reads SS as 'substitute'; lens-data M-14 reads SS as 'sales supervisor'. No route kind. No rule that AMO routes are outside Login %, Submit % and Final Submit. | missing-field | major | Add `route.kind` (sr or amo); AMO routes carry an AMO and no SR, so 'SR Not Set' is a normal state. Define target routes (Login %, Submit %, Final Submit counts) as SR-kind routes planned that day (the sample's 4 matches the four SR routes); `cfg.kpi.target_route_kinds`. Decide whether 'SR Not Set' routes block Final Submit (`cfg.day.final_submit_allow_not_set_routes`; the TSO manual lists them and does not say). The manual says AMO and SR are assigned; the AMO manual shows user `ss344002` on an AMO build, so 'SS' is a supervisor tier, not a substitute: correct F-ADM-003 and M-14 wording. Store route name and visit-day label separately (display = name + label as built). | docs/03 route; lens-data M-14 / M-18 / M-32; docs/10 KPI (target routes); F-WEB-010; cfg.kpi.target_route_kinds |
| **G-man-web-25** Small field and schema differences seen in the master-data screens | WEB-S-04, S-11..S-16, S-41; R-041, R-099; E-04, E-05, E-09; U-11, U-29, U-42 | Product tables show Status ('Active' badge) and Sort; SKU table shows Short Name (equal to SKU Name), Pack Size, Pack Type ('HLP') and only Outlet, C&c and Distributor price; outlet list shows Cluster Type ('Transit Hub'), Cluster Name, Geo Classification; outlet codes are 7-digit numbers or 'DHK-344-004'; one contact number lacks the leading 0; a leftover heading 'Firefly Outlets Reports'; 'Active Status' filter (All) on product-based reports. | Product tables carry `sales_enable` and `sort`, no `status`; five price types; `outlet_code` text; phone not normalised; no rule on branding strings. | missing-field | minor | Add `status` (active or inactive) to each product level, or confirm `sales_enable` = Active and say so, so the 'Active Status' filter has a source. Show only the price types a role may see (3 for the TSO; reporting and NTO hidden). Normalise phones to 11 digits with leading 01 on read. Do not carry 'Apsis' or 'Firefly' strings into the UI. Keep `outlet_code` as text. | docs/03; lens-data M-16 / M-19; db/seed README; cfg.pii.field_roles |
| **G-man-web-26** Web string catalogue: the manual captures only a few product strings and no error, validation or success text | M-001..M-038; M-036 | Verbatim strings: banners M-003 (Bangla) and M-004; dialog M-005..M-008; password guideline M-009..M-014 including the example 'PhuTysYsd623gB'; 'No Data'; defaults 'All Selected (n)', 'All items are selected.', 'All Months (3)'; 'SR Not Set'; 'exist'; 'Selected Outlets (n)'; statuses 'Pending / Verified / Approved', 'WMO approval pending'. The manual shows no login-failure, validation, save-success, delete-confirm, Verify / Reject confirm, loading or error text. | No web string catalogue. F-SYS-018 localisation layer is listed for apps and web but no strings are inventoried. | missing-message | minor | Put every string in the web i18n catalogue (en default, bn where the manual shows bn). Author the missing ones in both languages and have AKTCL review them: login failure, locked account, validation, save success, confirm for Verify / Reject / Delete Section Data / Submit / Final Submit / wholesale Submit, unsaved changes, empty, session expired. Do not ship a fixed example password in the guideline text; generate one or drop it. | /packages i18n; docs/09; F-SYS-018 |
| **G-man-web-27** PII baseline: the TSO sees NID, TIN, trade licence, address, phone and coordinates, and can export them | WEB-S-04, S-39, S-41 pp.4, 40-43; R-021, R-081 | Browse Retailer shows Owner Name, Contact Number, Address, NID, TIN, Trade License, Latitude, Longitude and exports them with Get Excel. Wholesale and Approval screens show owner, phone, address, coordinates. SR Device OTP shows live OTP codes. | G-feat-59: PII role x field matrix undefined. lens-config default `cfg.pii.field_roles` limits nid and tin to admin and trade_license to admin and tso. docs/22 P-12: NID is the placeholder '123' for 589k outlets, TIN and trade licence empty. | underspecified | minor | Parity baseline: the TSO sees all of these columns for outlets in own scope today. Either set `cfg.pii.field_roles` to match, or restrict deliberately and record it in DECISIONS.md as a change from the current build. Log every Excel export of outlet lists (`report_export_log`, includes_pii). OTP values are secrets visible only to the TSO of that scope. | lens-data section 5.2; cfg.pii.*; docs/09 PII rule; G-feat-59 |

---

## 2. Coverage tally

| Item type | Total | COVERED | PARTIAL | MISSING | CONTRADICTS |
|---|---|---|---|---|---|
| Screens (WEB-S) | 46 | 9 | 25 | 9 | 3 |
| Flows (F) | 26 | 3 | 14 | 6 | 3 |
| Rules (R) | 100 | 36 | 38 | 21 | 5 |
| Messages (M) | 38 | 6 | 28 | 3 | 1 |
| Entities (E) | 25 | 9 | 13 | 3 | 0 |
| **All items** | **235** | **63** | **118** | **42** | **12** |

Gaps by severity: blocker 1, major 16, minor 10 (total 27). By kind: contradiction 5, missing-feature 7, missing-field 4, missing-message 1, missing-rule 2, underspecified 8.

How the counts were decided:
- Screens: 9 COVERED are the five product-master views (Category, Segment, Brands, Variant, Product Tree), SR Efficiency, GIGO, SR Outlets Reports and Credentials. The 9 MISSING screens are the four QC screens, Web Entry, Astha Web Entry, DSS, DS-RRS and Route Wise Memo; the 3 CONTRADICTS are Final Submit, Target Approval List and SR Device OTP.
- Flows: 3 of 26 covered (outlet export, product browse, change password). The MISSING flows are the three QC flows, both web entries and the DSS drill-down; the CONTRADICTS flows are Final Submit with delete, target approval tracking and the OTP relay.
- Rules: 100 rules; password policy, scope, hierarchy, Final Submit once per zone, request statuses and pagination are covered. MISSING rules cluster in QC (6, plus the fault-group rule that CONTRADICTS), Web Entry (7), Astha entry (2), DSS and DS-RRS (4), back-dating (1) and zone fields (1).
- Messages: 38; 6 are plain statuses or labels (nothing to build), 3 are raised by an absent feature, 1 is the Delete Section Data action, and 28 are PARTIAL (trigger or rule exists, verbatim text is not in the spec).
- Entities: 25 (the inventory skips E-10 and E-11); MISSING are Market QC, Warehouse QC and the web entries; PARTIAL are targets, outlets, routes, sales plan, OTP, gift choice and the final-submit record.
- Items covered only by a plan draft (not yet in `docs/`): password history tables (M-40), outlet kind (M-16), gift assignment (M-24), route_log as a view (M-31), target revisions (M-21), report export log (M-43). Merge the lens drafts before treating these as spec.

---

## 3. Manual items that imply a database field or table the schema lacks

`schema.sql` is the starting spine; "plan" = `plan/lens-data.md` (M-01..M-45). A row is a real gap only where the last two columns say no.

| Id | Implied by | Missing table or field (sketch) | In schema.sql | In plan (lens-data) | Home / gap |
|---|---|---|---|---|---|
| DB-w-01 | S-18, R-043..R-049, E-14 | `web_entry_route_day(route_id, business_date, successful_calls, target_outlets_snapshot, status, entered_by, entered_at, UNIQUE(route_id, business_date))`; `web_entry_line(entry_id, sku_id, issue_qty, return_qty, sale_qty, memo_count)`; `web_entry_line_class(line_id, sub_channel_id, qty)` | no | no (W11 assumes `memo.entry_source`, which cannot hold route totals) | new migration after M-32 / G-01 |
| DB-w-02 | S-18 'Brand Data' | `web_entry_brand(entry_id, brand_id, ...)` (hypothesis only; content unknown) | no | no | G-01 (confirm first) |
| DB-w-03 | S-20, R-050, R-051, E-14b | `web_entry_outlet_sku(outlet_id, sku_id, business_date, qty, status, entered_by, entered_at, UNIQUE(outlet_id, sku_id, business_date))` | no | no | G-02 |
| DB-w-04 | S-19 Delete Section Data | `data_void(route_id, business_date, scope, reason, voided_by, voided_at, summary jsonb)`; status 'void' on web-entry rows and memos; `ingest_registry.state` gains 'voided' (tombstone) | no | partly (M-30 registry has states, no 'voided') | G-03, lens-data M-30 |
| DB-w-05 | S-19 'exist' / '--' status | derived `has_data` per route-day (view over `route_day`, `web_entry_route_day`, memos) | no | partly (M-34 `final_submit_route`, M-32 `route_day`) | G-03 |
| DB-w-06 | R-052, R-053, M-003 | `entry_unlock_grant(id, scope_type, scope_id, date_from, date_to, granted_by, reason, expires_at, used_at)`; `zone.data_entry_date` (if it is the lever) | no | no (only `cfg.sync.max_backdate_days`) | G-04 |
| DB-w-07 | S-09, S-10, R-034, E-08 | `zone.email`, `zone.address`, `zone.pda_contact_no`, `zone.data_entry_date`, meaning of the 'PDA' column; `zone.is_service` if 'Total Service Zone' is a zone attribute | no | no (M-18 adds `dep_id`, `apsis_id` only) | G-05, G-17 |
| DB-w-08 | S-05..S-08, R-022..R-028 | `qc_fault_type(code, group MFC or MKT, label_bn, label_en, applies_to, sort, active)`; `qc_summary_entry(kind, zone_id, route_id, business_date, sku_id, fault_type_id, qty, entered_by, entered_at, UNIQUE(...))` | no (`qc_entry` is visit-bound, 2 columns) | no (M-08 keeps 2 fault kinds) | G-06 |
| DB-w-09 | S-39, R-078, R-079 | `outlet_change_request.verified_via` (app or web), verifier role; panel columns need outlet name, request date, requester | partial (`verified_by`) | partial (M-15 events) | G-07 |
| DB-w-10 | S-41, S-42, R-083..R-086 | `outlet.outlet_kind` retail or wholesale (plan M-16); `outlet_kind_history(outlet_id, kind, valid_from, valid_to, changed_by, batch_uuid)`; `outlet_bulk_op(batch_uuid, op, count, by, at)` | no | partial (M-16 has the column, no history, no bulk op) | G-08 |
| DB-w-11 | S-34, S-36, R-073, R-074, E-18 | `target_set(id, name, territory_id, product_type, target_type, start_date, end_date, status, source, source_media_id, submitted_by, submitted_at)`; `target.product_level` gains 'variant'; `target_approval_event(set_id, level, role, actor_id, decision, at)` for initial sets | no | partial (M-21 revisions only; month only) | G-09 |
| DB-w-12 | S-36 download icon, S-34 upload | uploaded target workbook linked to the header (`media_object.purpose = target_upload`) | no | partial (M-31 `media_object` exists, no such purpose) | G-10 |
| DB-w-13 | S-44, E-23 | `device_otp`: retrievable code (encrypted), `requested_at`, `requested_for_device_uuid`, `purpose` (new device or new version), status | no | partial (M-41 stores `code_hash` only) | G-11 |
| DB-w-14 | S-01 Remember me | `refresh_token.remember boolean` and longer `expires_at`; web idle timeout | no | partial (M-40 `refresh_token`) | G-12 |
| DB-w-15 | S-17, S-19, S-26 AMO routes | `route.kind` (sr or amo); `route_assignment.role` already allows amo | partial (role column yes, route kind no) | no | G-24 |
| DB-w-16 | S-11..S-14, R-041, R-099 | `status` (active or inactive) on product category, segment, brand, variant, SKU | no (`sales_enable` only) | no | G-25 |
| DB-w-17 | S-45, E-24 | gift catalogue per tier and quarter; `gift_assignment` lock (when the SR photo exists) | no | partial (M-23 catalogue, M-24 assignment) | G-20 |
| DB-w-18 | S-33 | outlet-wise loyalty statement (earned, spent, balance, expiring) | no | yes (`loyalty_ledger`, `agg_outlet_balance`) | G-21 (report only) |
| DB-w-19 | S-21, S-30, R-065 | report parameter registry (`report_def(code, name, params jsonb, formats[], roles[])`) or code-level contract in /packages | no | no | G-16 |
| DB-w-20 | S-37 | AMO call summary aggregate (calls by AMO by day) | no | partial (M-26 / M-27 facts, no AMO daily aggregate) | G-22 |
| DB-w-21 | S-03 Value breakdown, Segment Contribution, Sales Trend | `net_mtk` and segment in the daily zone-category aggregate | no | yes (`agg_daily_zone_category`) | G-17 (check columns) |
| DB-w-22 | S-27 MIN, MAX, COUNT per zone | derive from `route_day` (no table) | no | yes (M-32) | G-19 |
| DB-w-23 | S-01, S-43 | `app_user.username` case policy (citext) and ID formats ('TSO-1012', 'AMO-6334') | partial | partial (M-19 `employee_code`) | G-12 |
| DB-w-24 | S-24 Print, S-06 PDF | `report_export_log.format` gains 'print' and 'pdf' | no | yes (M-43, add values) | G-14, G-16 |

---


## 4. Manual items that imply an admin-configurable parameter

"Exists" = already a key in `plan/lens-config.md`. "New" = not in any draft. All keys follow the `cfg.<area>.<name>` scheme and the scoping and audit rules of lens-config section 2.

| Id | Parameter | Manual evidence | Proposed key | Type, default (A = assumption) | Status |
|---|---|---|---|---|---|
| C-w-01 | Earliest date web data entry accepts | R-052, M-003 | `cfg.web.entry_backdate_days` or `cfg.web.entry_min_date` | int, 0 = today only (A); zone override | New |
| C-w-02 | Who may unlock back-dated entry and for how long | R-053 | `cfg.web.entry_unlock_roles`, `cfg.web.entry_unlock_max_days` | list, int (A) | New |
| C-w-03 | Support contact shown in the cut-off banner | M-003 | `cfg.support.contacts` + banner text `cfg.text.web.entry_cutoff_banner` | list, text bn/en | Exists + new text |
| C-w-04 | Final Submit advisory text and whether DSS must be acknowledged | M-004 | `cfg.text.web.final_submit_advisory`, `cfg.day.final_submit_requires_dss_ack` | text; bool, false (A) | New |
| C-w-05 | Delete Section Data: enabled, roles, scope (web entry only or all), only before Final Submit | R-056 | `cfg.web.delete_section_data_roles`, `cfg.web.delete_section_data_scope`, `cfg.web.delete_section_data_before_final_only` | list [tso, admin] (A); enum web_entry_only (A); bool true | New |
| C-w-06 | Web Entry: classifications offered, validation (calls <= target outlets, issue >= return), enabled zones | S-18, U-13 | `cfg.web.entry_classes`, `cfg.web.entry_validate_calls_le_target`, `cfg.web.entry_enabled_zones` | list<sub_channel>; bool true (A); list | New |
| C-w-07 | Final Submit with 'SR Not Set' routes | S-19 | `cfg.day.final_submit_allow_not_set_routes` | enum warn (lens-config) | Exists |
| C-w-08 | QC fault types (web 10, app 6, groups MFC / MKT, labels, applies_to) | R-023 | `cfg.qc.fault_types` (replaces `cfg.qc.fault_kinds`) | list<{code, group, label_bn, label_en, applies_to, sort, active}> | Exists, must change |
| C-w-09 | QC cell maximum and re-entry policy (replace, add, block) | R-024, U-07 | `cfg.qc.max_qty_per_cell`, `cfg.qc.web_reentry_policy` | int; enum replace (A) | New |
| C-w-10 | Who may enter QC on the web | S-05, S-07 | `cfg.qc.web_entry_roles` | list [tso, admin] (A) | New |
| C-w-11 | Who may edit Sales Plan; bulk by territory | S-10, p.11 caption | `cfg.sales_plan.edit_roles` | list [tso, admin] | New |
| C-w-12 | Target approval levels, first approver WMO, applies to initial sets | R-074, M-028 | `cfg.target.revision_approval_levels` (rename `cfg.target.approval_levels`) | list<{level, role}> default [{1, wmo}] (A) | Exists, default wrong |
| C-w-13 | Target product types and target types | S-36 'variant', 'stt' | `cfg.target.product_types`, `cfg.target.types` | list [variant] (A); list [stt] (A) | New |
| C-w-14 | Target workbook template version and row cap | S-34 | `cfg.target.template_version`, `cfg.target.upload_max_rows` | int; int | New |
| C-w-15 | Window in which a TSO may set next month's target | R-071 | `cfg.target.entry_window` | {open_days_before, close_days_after} (A) | New |
| C-w-16 | OTP length 4, TTL, who sees it, re-verify after new app version | S-44, SR-S-04 | `cfg.auth.otp_length` (default 4), `cfg.auth.otp_ttl_min`, `cfg.auth.otp_visible_roles`, `cfg.auth.reverify_on_new_version` | int 4; int; list [tso]; bool | Exists (default wrong) + new |
| C-w-17 | Remember me duration | S-01 | `cfg.auth.web_remember_me_days` | int, 0 = off (A) | New |
| C-w-18 | Password policy numbers and example | R-005..R-008, M-010..M-014 | `cfg.auth.password_min_len` 12, `cfg.auth.password_history_depth` 10, `cfg.auth.password_min_age_h` 24 | int | Exists |
| C-w-19 | Who verifies, approves, rejects outlet requests; reason on reject; confirm dialogs | S-39, S-40 | `cfg.outlet.verify_roles`, `cfg.outlet.approve_roles`, `cfg.outlet.reject_requires_reason` | list; list; bool true (A) | New |
| C-w-20 | Wholesale marking: roles, unmark allowed, price type and KPI effect | S-41 | `cfg.outlet.wholesale_marking_roles`, `cfg.outlet.wholesale_unmark_allowed`, `cfg.outlet.wholesale_price_type` | list [tso]; bool; enum | New |
| C-w-21 | Page size and options; Excel row cap | R-096, M-029 | `cfg.report.page_size_default` 10, `cfg.report.page_size_options`, `cfg.ops.report_export_max_rows` | int; list [10, 20, 50]; int | New / exists |
| C-w-22 | Report option lists: Location levels, Date Grouping, Product Type, Active Status, Classification Type, Report Type | S-21, S-30 (U-16, U-23) | `cfg.report.lists.*` | code lists, values unknown | New |
| C-w-23 | STD criteria unit ('000) | S-30 | `cfg.report.std_criteria_divisor` | int 1000 | New |
| C-w-24 | Dashboard tile set, order, unit labels (stick, pcs, dozen) | S-03 | `cfg.dashboard.tiles`, `cfg.dashboard.unit_labels` | list; map | New |
| C-w-25 | Dashboard channel list and tile info text (bn, en) | S-03 'i' icons | `cfg.dashboard.channels`, `cfg.dashboard.tile_info` | list; map | New |
| C-w-26 | Submit % denominator | R-018 | `cfg.kpi.submit_pct_denominator` | enum (lens-config default target_routes; check G-18) | Exists, verify default |
| C-w-27 | Which route kinds count as target routes | S-03, S-19 | `cfg.kpi.target_route_kinds` | list [sr] (A) | New |
| C-w-28 | Astha gift catalogue, who chooses, when choices lock, Gift Status meaning | S-45, S-46 | `cfg.astha.gift_catalog`, `cfg.astha.gift_choice_roles`, `cfg.astha.gift_choice_lock` | list; list [tso]; enum | Exists, lock new |
| C-w-29 | Astha quarter start and Year default | S-31 | `cfg.astha.quarter_start_month`, `cfg.report.default_year` | int 1; current year | Exists / new |
| C-w-30 | Role x menu x action matrix | S-02 | `cfg.web.menu_by_role` (or `admin_permission`) | json | New / exists |
| C-w-31 | Web session idle timeout | S-01 | `cfg.auth.web_session_idle_min` | int 30 | Exists |
| C-w-32 | Date display format and month picker format | R-093 | `cfg.ui.date_format` | text YYYY-MM-DD | New |
| C-w-33 | AMO Call Report: report types offered | S-37 | `cfg.report.amo_call_types` | list [summary] (others unknown) | New |
| C-w-34 | Zone Data Entry Date, Email, Address, PDA Contact | S-09 | master data on `zone`, not cfg (unless Data Entry Date is the cut-off lever, then C-w-01) | n/a | Data |

---


## 5. Online-only versus works offline

The web manual shows no offline behaviour on any screen and R-092 records that. The whole web product is online-only by nature; the apps are not. This section lists what each web item means for the offline apps, because the app side is where CLAUDE.md constraints 1 and 2 bite.

| # | Web item | Web | App-side consequence | Requirement |
|---|---|---|---|---|
| 1 | All 46 web screens (R-092) | Online only | none directly | Web needs no offline mode. Reports must say 'data as of' because they depend on app syncs. |
| 2 | SR Device OTP (S-44, F-23) | Online | The SR device must reach the server to create the OTP; the TSO needs the web page; the SR then types it (SR manual SR-S-04). A new device or a new app version cannot be bound offline. | Mark as ONLINE-ONLY step in the SR login flow; plan launch-day capacity (8,500 SRs) and an offline-safe path for already-bound devices (no re-verify for in-place updates, G-man-sr-17). |
| 3 | Login, change password (S-01, S-43) | Online | App login is separate; offline re-entry is an app rule. | none |
| 4 | Dashboard, Live Strike Rate, geo and FF location tiles (S-03) | Online | Numbers move only when phones sync. | Show last-sync age; never imply real-time. |
| 5 | Web Entry, Astha Web Entry, QC entry (S-18, S-20, S-05, S-07) | Online | Web-entered rows and app-synced rows must coexist for the same route-day (G-01, G-02, G-06). | Same idempotency discipline: each web submission carries a client-generated UUID so a double click or retry cannot double it. |
| 6 | Final Submit and Delete Section Data (S-19) | Online | A phone that is offline when the zone is closed or a route is voided will upload later. | Late upload must land in a defined state (reject with reason `voided_by_admin` or `day_closed`, visible on the device-vs-server screen), never silently merge (G-03, G-feat-17). |
| 7 | Sales Plan edit (S-10) | Online | New or removed SKUs reach a phone only at its next bundle. | Offline phone keeps selling the old plan; server FLAGs `sku_not_in_plan`, does not reject (DQ-08). |
| 8 | Outlet Approval Panel (S-39) | Online | SR sees request status only after the next bundle; selling to a just-captured outlet before approval is G-feat-42. | Verify/Approve/Reject must be idempotent (double click). |
| 9 | Wholesale marking (S-41) | Online | Kind reaches phones at next bundle. | Server stays authoritative; a memo created offline under the old kind is flagged, not rejected. |
| 10 | Set Target, Target Approval (S-34, S-36) | Online | App target and achievement tiles update after approval and the next bundle. | Targets in the bundle carry their approval status; unapproved targets are not shown as live (decision in G-09). |
| 11 | Astha Gift Choice (S-45) | Online | SR Astha Photo Capture lists only outlets with an assigned gift, offline, from the bundle. | `giftAssignments` must be in the bundle (G-20). |
| 12 | Reports, Excel, PDF, Print (S-06, S-21..S-33, S-35, S-37, S-38) | Online | none | Large exports run as a background job with a download link (R3), not a request held open. |
| 13 | Data Entry Log (S-26) | Online | It reports app download and upload events; offline days show as gaps. | Keep route_log accurate on late uploads (re-aggregate by business date). |
| 14 | Browse Retailer, products, routes (S-04, S-11..S-17) | Online | none | none |

---


## 6. Contradictions

### 6A. Manual versus the spec (`docs/01-13`, `docs/22`, `db/schema.sql`)

| # | Manual (page) | Spec says | Resolution | Gap |
|---|---|---|---|---|
| 1 | The SR device attempts login and an OTP appears in the TSO's list (p.46) | docs/09: 'TSO-issued OTP' for `bind-device` | OTP is server-created on login attempt; TSO views it | G-11 |
| 2 | Target Approval List shows the approval status of the TSO's own monthly SET target, first approver 'WMO' (p.37) | docs/10: approval levels belong to REVISIONS; docs/09 'Target Revise List' | Every set needs approval; header entity; role WMO | G-09 |
| 3 | Product Type 'variant' on a target (p.37) | schema `target.product_level` is category, brand or sku | Add variant | G-09 |
| 4 | 'Delete Section Data' removes a route's entered data (p.20) | docs/03: never hard-delete transactions; idempotent sync | Audited void plus tombstones | G-03 |
| 5 | QC has Manufacturing (5) and Marketing (5) fault reasons (p.5, 7) | docs/03 `qc_entry` has production and transport quantities | Code table of reasons | G-06 |
| 6 | Submit Status tile lists 'Login successfully' beside 'Submitted successfully' (p.3) | docs/10 web Submit % = / target routes | Probably / logged-in; check live | G-18 |
| 7 | Web has its own Final Submit page with a selectable date and a delete action (p.20) | docs/09 Final Submit is an API called from the TSO app | Build both | G-03 |
| 8 | TSO portal: 15 menu items, 41 pages (pp.2-48) | docs/09: '37 pages across 13 menus' | The menus are role-specific; use the union | G-23 |
| 9 | Outlet Approval Panel serves New, Close and Info requests and has a web Verify (p.40) | docs/09: pending new-outlet requests; docs/03 `verified_by` = AMO | All three types, web verify | G-07 |
| 10 | Browse Routes lists 'AMO and SR' per route (p.18) | docs/03 / docs/09: SR and SS | Route kind and AMO assignment; SS is a supervisor tier | G-24 |
| 11 | Dashboard has a date-range Filter (p.3) | docs/09: 'loads from rollups, no button press' | Default loads without a press, filter available | G-17 |
| 12 | TSO enters targets route by route or by Excel (p.35) | docs/10: fractional targets 'split by a formula' | No formula split evidenced; keep formula optional | G-10 |
| 13 | QC, Sales Plan, Data Entry, Supervisory, Wholesale, OTP, Set Target are TSO pages (pp.5-46) | docs/09 groups them as 'admin pages (TSO/admin)' | Give the TSO write rights within scope | G-23 |
| 14 | 13 Excel-only reports (R-060) | docs/09: render on screen first, Excel as a formatting step | Deliberate change, keep; record in docs/13 'deliberately changed'; needs the same column sets | G-16 |
| 15 | Docs/13 Q13 asks where Astha gift choices are set | 'the TSO portal' | It is the web Astha Gift Choice Panel (p.47) | G-20 |
| 16 | Dashboard unit labels 'Lighter in Pcs', 'Match in Dozen' (p.3) | docs/13 Q8 open on lighter and match units | Web: lighter in pieces, match in dozen; TSO app boxes still to reconcile | G-17 |
| 17 | docs/13 Q16 (roles beyond the apps) | WM, DMO, Top named | A further approver role 'WMO' exists in the target chain | G-09 |
| 18 | Sales Plan caption speaks of 'your territory', save unit is a zone row (pp.9-11) | docs/03 `sales_plan` is zone x SKU | Zone row with a territory bulk option | G-05 |
| 19 | Retailer Wholesale Outlet is a TSO page that flags outlets (pp.42-44) | docs/09 lists it as an admin page; Q17 open | TSO-operated bulk flag | G-08 |

### 6B. Manual versus the plan drafts (`plan/lens-features.md`, `lens-data.md`, `lens-config.md`)

| # | Plan says | Manual says | Resolution | Gap |
|---|---|---|---|---|
| 1 | F-ADM-022 and P11: 'pick user -> issue OTP', M-41 `code_hash` | View-only list of server-created OTPs | Retrievable code, no issue button | G-11 |
| 2 | F-WEB-030: TSO, DMO, WM approve or reject in a 'Target Revise List' | TSO sees a status list; WMO approves elsewhere | Split TSO view from approver queue | G-09 |
| 3 | lens-config default `revision_approval_levels` = tso, dmo, wm | Status 'WMO approval pending' after the TSO sets | First approver WMO | G-09 |
| 4 | G-feat-30 / F-ADM-024 / lens-data W11: Data Entry = memo-level backfill with `memo.entry_source` | Route-day totals per SKU, no outlet, no memo | Separate web entry source | G-01 |
| 5 | G-feat-32: QC admin page is a claims review; `cfg.qc.fault_kinds` = 2 | QC entry and QC reports, 10 reasons | Fault type table | G-06 |
| 6 | lens-data D-03: Submit % canonical = / target routes | Tile pairs Submit with 'Login successfully' | Verify live | G-18 |
| 7 | F-ADM-003 'SS (substitute)'; lens-data M-14 'ss (sales supervisor)' | 'AMO and SR' on Browse Routes; AMO manual shows `ss344002` | SS is a supervisor tier | G-24 |
| 8 | lens-config `cfg.pii.field_roles` hides nid, tin from the TSO | TSO list and Excel carry NID, TIN, Trade License | Decide, log as change if restricted | G-27 |
| 9 | F-WEB-022 only 'Campaign Gift Redemption' | 'Loyalty Program - Diamond League Report' (outlet-wise points) | Separate report | G-21 |
| 10 | lens-data M-21 targets keyed by month | Start and End Date plus Name, Product Type, Target Type | Header entity | G-09 |
| 11 | F-ADM-014 'Set Target: admin, TSO?' | TSO sets the monthly target (TSO-1012) | TSO is the primary user | G-09, G-10 |
| 12 | lens-data M-30 registry states have no 'voided' | Delete Section Data | Add tombstone state | G-03 |
| 13 | G-feat-55 asks if closure and info changes are on the panel | Yes: Outlet Type New / Close / Info | Answered | G-07 |
| 14 | G-feat-31 'Supervisory Module unknown' | AMO Call Report | Answered | G-22 |

### 6C. Inside the web manual

| # | Item | Detail | Handling |
|---|---|---|---|
| 1 | Slide titles on pp.43 and 44 say 'Login page' | Copy-paste error; content is the wholesale flow | ignore title |
| 2 | Export label: 'Get Excel' / 'Export Excel' / 'Excel Export' / 'Download Report' (pp.4, 6, 8, 16, 31) | One action, four labels | one label ('Get Excel') |
| 3 | Captions say 'date range' on single-date screens (pp.7, 25, 28) | Warehouse QC Entry, DS-RRS, Final Submit Log | build what the screen shows (single date), allow range as improvement |
| 4 | Captions mention 'Location' on pp.26, 29, 30, 31 but only p.22 has a Location field | | define Location once (G-16) |
| 5 | p.39 caption says Report Category and Date range; p.40 screen has Outlet Type and no date range | Two screens, two name sets | keep both screens |
| 6 | Outlet 'Alif' is Approved on p.39 and Verified on p.40 | Different days | status machine, no conflict |
| 7 | Data Entry Log: MAX 16:57:25 earlier than MIN 17:35:50 (p.27) | Labels or data swapped | define first and last explicitly (G-19) |
| 8 | Sales Plan caption 'your territory' vs zone row (pp.9-11) | | G-05 |
| 9 | SR Device OTP caption 'according to route', screen has no route filter (p.46) | | G-11 |
| 10 | Dashboard 'Total Zone' vs 'Total Service Zone' (p.3) | Both 1 in sample | define (G-17) |
| 11 | Year filter shows 2025 on p.32, other pages show 2026 | Default | default = current year |
| 12 | Route names 'Apsis Route-Daily' (p.19) vs 'Apsis RouteDaily' (p.20) | Display variant | store name and label separately (G-24) |
| 13 | p.42 shows 'Yes' selected while the caption says choose 'No' | Dropdown open on Yes | none |
| 14 | Heading 'Firefly Outlets Reports' (pp.40-41) | Leftover vendor or product name | do not reproduce |
| 15 | Three names for BSR & CPR (menu, heading, caption) and for the target list (p.37) | | one name each, aliases kept (G-15) |
| 16 | Menu differs between screenshots (U-04) | SR Device OTP, Credentials, Astha Gift Panel and four Reports absent in older shots | consistent with later builds adding features (SR manual release 1.0.25 adds login 2FA); use the union (G-23) |
| 17 | Final Submit sample shows two identical 'AMO-Apsis RouteAMO' rows and routes 3Daily, 4Daily sharing one SR (p.20) | Test data | none; route names are not unique, so never key on name |

### 6D. Against the other manuals

| # | Web manual | Other manual | Resolution | Gap |
|---|---|---|---|---|
| 1 | SR OTP Panel: 5 columns, no route filter (p.46) | SR manual: 8 columns (Sr No., Field Force ID, name, username, Zone ID, Zone, Create Time, OTP); 4-digit; re-asked after a new app version | Build the superset | G-11, G-man-sr-18 |
| 2 | QC: 5 + 5 reasons, groups 'Manufacturing' / 'Marketing' (p.5) | SR app: 3 + 3 reasons, groups 'production' / 'transport', codes MFC / MKT (SR-S-35) | One fault-type table with applies_to | G-06, G-man-sr-03 |
| 3 | Final Submit: selectable 'Date of Data Entry', 'SR Not Set', Delete Section Data, no dialogs (p.20) | TSO app: read-only 'Sales Date', 'FF: SR Not Set', alert 'You have already submitted FINAL SUBMIT for today!', success 'Final Submit Done Successfully...' | Same server rule, two surfaces; carry over the app messages to the web | G-03, G-26 |
| 4 | Outlet Approval Panel: Verify, Reject, Approve on the web (p.40) | AMO app: Save/Cancel on verification, no reject (G-man-amo-29) | Decide verify roles | G-07 |
| 5 | Target Approval List: 'WMO approval pending' (p.37) | TSO app Leave: 'DMO approval pending'; TSO app Target Status shows monthly and till-date targets | Two approver roles; WMO for targets | G-09 |
| 6 | Dashboard unit labels lighter in pcs, match in dozen (p.3) | TSO app: lighters in boxes (docs/13 Q8) | Keep both via `report_unit` | G-17 |
| 7 | User IDs 'tso-apsis', 'TSO-1012', 'AMO-6334' | AMO manual: `amo5756`, `ss344002`; docs/03 `sr334001` | ID formats vary; case policy | G-12 |
| 8 | Astha Gift Choice Panel lists gifts per outlet (p.47) | SR manual SR-S-53: Astha Photo Capture only for outlets with a TSO-portal-assigned gift | Same data, bundle delivery | G-20 |
| 9 | Browse Routes: AMO and SR per route (p.18) | AMO manual: AMO picks any route of the zone | Assignment role amo vs scope | G-24 |

### 6E. Corrections and additions to the inventory (`manual-web.md`)

- U-04 (menus differ): explained by build versions, not role hiding. The OTP page appears only in later screenshots and the SR manual release notes (1.0.25) add 'Login time 2FA verification'.
- U-07: the Marketing Fault group ends with 'Others' on PDF pp.5 and 7, so there are exactly 5 + 5 reason columns (no hidden extras). SKU rows below 'ARIS-A-20s' are cut off.
- E-10 and E-11 are skipped in the entity numbering (no entity is missing).
- Dashboard (PDF p.3): 'Live Strike Rate 2.3%' is 1/43 and Login Status 25.0% is 1/4, as the inventory says; the Submit Status pair '1 Login successfully / 0 Submitted successfully' is the evidence used in G-18.
- DSS (PDF p.24): only the 'Total' row (36, 5) and the 'GT STD' row carry values; the other STD-class rows are blank in the sample.
- Everything else spot-checked matched the inventory (Appendix B).

---


## Appendix A. Verdict for every item

Codes: C covered, P partial, M missing, X contradicts. After the code, the gap ids that cover the difference (01 = G-man-web-01). Every PARTIAL and MISSING message also points to 26 (string catalogue) only where no feature gap applies.

### Screens (WEB-S)

| Id | Item | Verdict | Gaps | Note |
|---|---|---|---|---|
| WEB-S-01 | Login page | P | 12 | Remember me, no reset link, no failure text not in spec |
| WEB-S-02 | Global shell: sidebar, top bar, logout | P | 12, 23 | role menu matrix and TSO write actions not in spec |
| WEB-S-03 | Dashboard | P | 17, 18 | tiles and date filter beyond spec; Submit % basis |
| WEB-S-04 | Browse Retailer | P | 16, 27 | per-column search, PII columns, export |
| WEB-S-05 | QC Entry (Market QC) | M | 06 |  |
| WEB-S-06 | QC Report (Market & Warehouse) | M | 06, 16 | Excel + PDF |
| WEB-S-07 | Warehouse QC Entry | M | 06 |  |
| WEB-S-08 | Route Wise QC Report | M | 06 |  |
| WEB-S-09 | Sales Plan (list) | P | 05 | zone fields |
| WEB-S-10 | Sales Plan (edit, SKU picker) | P | 05 | TSO-operated, atomic zone row |
| WEB-S-11 | Browse Category | C | - |  |
| WEB-S-12 | Browse Segment | C | - |  |
| WEB-S-13 | Browse Brands | C | - |  |
| WEB-S-14 | Browse Variant | C | - |  |
| WEB-S-15 | Browse SKUs | P | 16, 25 | Excel export, role-visible prices |
| WEB-S-16 | Browse Product Tree | C | - |  |
| WEB-S-17 | Browse Routes | P | 24 | AMO + SR, route kind |
| WEB-S-18 | Web Entry | M | 01 |  |
| WEB-S-19 | Final Submit (web) | X | 03, 04 | Delete Section Data vs never hard-delete |
| WEB-S-20 | Astha Web Entry | M | 02 |  |
| WEB-S-21 | STD Memo Report | P | 16 | filter set |
| WEB-S-22 | SR Efficiency Report | C | - | columns unknown in spec and manual (G-feat-53) |
| WEB-S-23 | DSS Report | M | 13 |  |
| WEB-S-24 | DS-RRS Report (+ Print) | M | 14 |  |
| WEB-S-25 | Route Wise STD Report | P | 15, 16 |  |
| WEB-S-26 | Data Entry Log | P | 19 | MAX/MIN semantics |
| WEB-S-27 | Final Submit Log Report | P | 19 | zone MIN/MAX/COUNT |
| WEB-S-28 | Route Wise Memo Report | M | 15 |  |
| WEB-S-29 | Route Wise BSR & CPR Report | P | 15 | three names, filter set |
| WEB-S-30 | By Outlet Report | P | 16 | criteria filters, sub-channel, Download Report |
| WEB-S-31 | Astha Report | P | 16 | Year/Quarter/Month |
| WEB-S-32 | GIGO Report | C | - | GIGO = attendance (spec reading, plausible) |
| WEB-S-33 | Loyalty Program - Diamond League Report | P | 21 |  |
| WEB-S-34 | Set Target | P | 09, 10 |  |
| WEB-S-35 | Target Allocation Report | P | 09, 16 | single-date, variant level |
| WEB-S-36 | Target Approval List | X | 09 | status view, WMO, not a revise queue |
| WEB-S-37 | AMO Call Report | P | 22 |  |
| WEB-S-38 | SR Outlets Reports | C | - |  |
| WEB-S-39 | Outlet Approval Panel | P | 07 | all 3 types, web Verify |
| WEB-S-40 | Approve outlet request? dialog | P | 07, 26 |  |
| WEB-S-41 | Retailer Wholesale Outlet | P | 08 |  |
| WEB-S-42 | Selected Outlets dialog | P | 08 |  |
| WEB-S-43 | Credentials / Change Password | C | - | policy matches docs/09 |
| WEB-S-44 | SR Device OTP | X | 11 | server-created, TSO views |
| WEB-S-45 | Astha Gift Choice Panel | P | 20 |  |
| WEB-S-46 | Astha Gift Choice Report | P | 20 | Gift Status filter |

### Flows (F)

| Id | Item | Verdict | Gaps | Note |
|---|---|---|---|---|
| F-01 | Login, session, logout | P | 12 |  |
| F-02 | Dashboard and date-range filter | P | 17 |  |
| F-03 | Browse and export outlet list | C | - |  |
| F-04 | Market QC entry | M | 06 |  |
| F-05 | Warehouse QC entry | M | 06 |  |
| F-06 | QC reporting | M | 06 |  |
| F-07 | Maintain Sales Plan | P | 05 |  |
| F-08 | Browse product master | C | - |  |
| F-09 | Browse route assignments | P | 24 |  |
| F-10 | Daily web entry for a route | M | 01 |  |
| F-11 | Web entry for Astha outlets | M | 02 |  |
| F-12 | Check, then Final Submit, with delete alternate | X | 03, 04 |  |
| F-13 | Verify final-submit status | P | 19 |  |
| F-14 | Generic report download pattern | P | 16 |  |
| F-15 | DSS drill-down by route | M | 13 |  |
| F-16 | Set monthly target | P | 09, 10 |  |
| F-17 | Track target approval | X | 09 |  |
| F-18 | Target allocation report | P | 16 |  |
| F-19 | AMO call report | P | 22 |  |
| F-20 | Outlet request lifecycle | P | 07 |  |
| F-21 | Mark outlets as Wholesale | P | 08 |  |
| F-22 | Change password | C | - |  |
| F-23 | SR device login OTP relay | X | 11 |  |
| F-24 | Astha gift selection and reporting | P | 20 |  |
| F-25 | Astha and loyalty reporting | P | 21, 16 |  |
| F-26 | Monitor field sync/login activity | P | 19, 18 |  |

### Rules (R)

| Id | Item | Verdict | Gaps | Note |
|---|---|---|---|---|
| R-001 | Assigned ID and password, no self-registration | C | - |  |
| R-002 | Remember me persists login | P | 12 |  |
| R-003 | Password masked with eye toggle | C | - |  |
| R-004 | Login lands on Dashboard | C | - |  |
| R-005 | Password min 12 characters | C | - | docs/09 |
| R-006 | Lower + upper + number required | C | - | docs/09 |
| R-007 | No reuse of last 10 passwords | C | - | docs/09 |
| R-008 | 24 h between password changes | C | - | docs/09 |
| R-009 | Old, New, Confirm all required | C | - |  |
| R-010 | Confirm equals New (implied) | C | - |  |
| R-011 | Data scoped to assignment | C | - | docs/09, non-negotiable 4 |
| R-012 | Geography hierarchy with codes | C | - | docs/03 |
| R-013 | Multi-select for reports, single-select for entry | P | 16 |  |
| R-014 | Dashboard date-range filter | P | 17 |  |
| R-015 | Families with own units (Lighter in Pcs, Match in Dozen) | P | 17 | closes part of Q8 for web |
| R-016 | Six channels | C | - |  |
| R-017 | Live Strike Rate = successful calls / target outlets | C | - | docs/10 CPR |
| R-018 | Login % and Submit % bases | X | 18 |  |
| R-019 | Final Submit Status tile (Total Zone, Total Service Zone, Remaining) | P | 17 |  |
| R-020 | Browse Retailer lists territory outlets, Excel export | C | - |  |
| R-021 | Per-column search; Address, NID, TIN, Trade License | P | 27, 16 |  |
| R-022 | Market QC keyed by zone + route + date per SKU | M | 06 |  |
| R-023 | Two fault groups, five reasons each | X | 06 | spec has 2 numbers, other names |
| R-024 | QC cells default 0, limits unstated | M | 06 |  |
| R-025 | Submit saves QC | M | 06 |  |
| R-026 | Warehouse QC per zone + date, no route | M | 06 |  |
| R-027 | QC report: Excel and PDF | M | 06, 16 |  |
| R-028 | Route Wise QC Report with QC Type | M | 06 |  |
| R-029 | Sales Plan decides enabled SKUs per zone | C | - | docs/03 sales_plan |
| R-030 | Edit by pencil, tick saves, x cancels | C | - | UI pattern |
| R-031 | Nothing saved before tick | C | - |  |
| R-032 | Zone row is the unit of save | P | 05 |  |
| R-033 | SKU picker tri-state tree | C | - | UI pattern |
| R-034 | Zone Email, Address, PDA Contact No., Data Entry Date | M | 05 |  |
| R-035 | Hierarchy Category > Segment > Brand > Variant > SKU | C | - |  |
| R-036 | Product pages read-only for TSO | C | - |  |
| R-037 | Categories Cigarette, Bidi, Lighter, Match | C | - | seed CSV |
| R-038 | SKU list downloadable as Excel | P | 16 |  |
| R-039 | Three visible prices | C | - | 5 types in spec; role view in G-25 |
| R-040 | Pack size and pack type | C | - |  |
| R-041 | Status 'Active' badge and Sort | P | 25 |  |
| R-042 | Browse Routes lists AMO and SR per route | P | 24 |  |
| R-043 | Web entry per route per date | M | 01 |  |
| R-044 | Route must be selected first | M | 01 |  |
| R-045 | Successful Call count required | M | 01 |  |
| R-046 | Per SKU Issue, Return, Memos | M | 01 |  |
| R-047 | Target Outlet system-supplied, read-only | M | 01 |  |
| R-048 | Sale column derived | M | 01 |  |
| R-049 | Entry order Route, Successful Call, SKUs, Save | M | 01 |  |
| R-050 | Astha outlets must use Astha Web Entry | M | 02 |  |
| R-051 | Astha web entry outlet x SKU grid | M | 02 |  |
| R-052 | No data entry before cut-off date | P | 04 | plan has an app-sync window only |
| R-053 | Back-dated data needs a call to support | M | 04 |  |
| R-054 | Final Submit per zone per day | C | - | docs/09 and docs/10 |
| R-055 | Check DSS before Final Submit | M | 03, 13 |  |
| R-056 | Delete Section Data only on 'exist' rows | X | 03 |  |
| R-057 | Final-submit status Done/Not Done with times and count | P | 19 |  |
| R-058 | DS-RRS Print shows discount data | M | 14 |  |
| R-059 | Select all filters before Get Excel/Get Data | C | - | defaults in spec |
| R-060 | 13 reports are Excel-only | P | 16 | spec renders on screen first (deliberate) |
| R-061 | DSS single-date, route-wise, route link | M | 13 |  |
| R-062 | DSS Total and STD-class rows | M | 13 |  |
| R-063 | STD and Memo together or separately | P | 16 |  |
| R-064 | Data Entry Log MIN/MAX/count per route | C | - | route_log; semantics in G-19 |
| R-065 | By Outlet STD and Memo criteria, Outlet Code | P | 16 |  |
| R-066 | Astha report Year + Quarter + Month | P | 16 |  |
| R-067 | GIGO single-date | C | - |  |
| R-068 | Diamond League outlet-wise loyalty points | P | 21 |  |
| R-069 | Route-wise reports: Category, Product Type, Active Status, Products | P | 15, 16 |  |
| R-070 | Route-wise BSR & CPR report | C | - | F-WEB-018 |
| R-071 | Monthly targets entered route-wise | P | 10 | spec: formula split |
| R-072 | Two entry methods: manual or Excel upload | P | 10 |  |
| R-073 | Target covers a full calendar month, with start/end dates | P | 09 | schema has month only |
| R-074 | Approval chain including WMO | X | 09 |  |
| R-075 | Target Allocation Report single-date | P | 16 |  |
| R-076 | AMO Call Report By Date / By Month, Summary | P | 22 |  |
| R-077 | Three outlet request categories | C | - | docs/03 enum |
| R-078 | Two-step approval Verify then Approve/Reject on web | P | 07 | spec: AMO verifies |
| R-079 | Verify only on Pending; Reject/Approve only on Verified | P | 07 |  |
| R-080 | Approve needs confirmation | P | 07, 26 |  |
| R-081 | Outlet requests carry lat/long, phone, owner, cluster, route | C | - |  |
| R-082 | Statuses Pending, Verified, Approved, (Rejected) | C | - | docs/03 enum |
| R-083 | Wholesale marking steps | P | 08 |  |
| R-084 | Basket badge and dialog title show live count | P | 08 |  |
| R-085 | Remove only drops from selection | P | 08 |  |
| R-086 | Submit completes wholesale flagging | P | 08 |  |
| R-087 | SR device login OTP relayed by TSO | X | 11 |  |
| R-088 | OTP list needs filters then View | P | 11 |  |
| R-089 | TSO picks one gift per Astha outlet, route first | P | 20 |  |
| R-090 | Observed gift catalogue (3 gifts) | P | 20 |  |
| R-091 | Gift Choice Report by Gift Status | P | 20 |  |
| R-092 | Web is online-only | C | - | CLAUDE.md |
| R-093 | Date display formats vary | P | 16 | decide ISO |
| R-094 | Export button labelled inconsistently | C | - | standardise |
| R-095 | Logout via red sidebar button | C | - |  |
| R-096 | Pagination with '1-N of N items' and '10 / page' | C | - | docs/09 |
| R-097 | SR Outlets columns sortable | C | - | UI pattern |
| R-098 | Outlet Code and Outlet Search optional filters | P | 16, 08 |  |
| R-099 | Active Status filter | P | 25, 16 |  |
| R-100 | Field Force Type filter (SR, AMO) | P | 16 |  |

### Messages (M)

| Id | Item | Verdict | Gaps | Note |
|---|---|---|---|---|
| M-001 | Welcome back | P | 26 |  |
| M-002 | Login to your account | P | 26 |  |
| M-003 | Cut-off banner (Bangla) | M | 04 |  |
| M-004 | 'Before Final Submit, Please Checkout Sales Data From DSS Report...' | M | 03 |  |
| M-005 | Approve outlet request? | P | 26, 07 |  |
| M-006 | Do you want to approve this request? | P | 26, 07 |  |
| M-007 | Yes, approve it | P | 26, 07 |  |
| M-008 | Cancel | P | 26 |  |
| M-009 | Password Changing Guideline | P | 26 |  |
| M-010 | Password must be at least twelve (12) characters. | P | 26 | rule in docs/09, text not |
| M-011 | One lowercase, one uppercase, one number | P | 26 |  |
| M-012 | Cannot use previously 10 times used password | P | 26 |  |
| M-013 | Cannot change within 24 hours | P | 26 |  |
| M-014 | Example password line and value | P | 26 | do not ship a fixed example |
| M-015 | Old / New / Confirm Password placeholders | P | 26 |  |
| M-016 | No Data | P | 26 |  |
| M-017 | Enter Outlet Code | P | 16 |  |
| M-018 | Enter Outlet Search | P | 08 |  |
| M-019 | All Selected (n) | P | 26 |  |
| M-020 | All items are selected. | P | 02, 26 |  |
| M-021 | All Months (3) | P | 26 |  |
| M-022 | SR Not Set | P | 24 |  |
| M-023 | exist / -- | M | 03 |  |
| M-024 | Delete Section Data | X | 03 |  |
| M-025 | Selected Outlets (3) | P | 08 |  |
| M-026 | Done / Not Done | P | 19 |  |
| M-027 | Pending / Verified / Approved | C | - | enum in docs/03 |
| M-028 | WMO approval pending | P | 09 |  |
| M-029 | Pager text '1-N of N items', '10 / page' | C | - |  |
| M-030 | Yes / No | C | - |  |
| M-031 | Dashboard tile labels | P | 17 |  |
| M-032 | Remove | P | 08 |  |
| M-033 | Verify / Reject / Approve | P | 07 |  |
| M-034 | Active | C | - |  |
| M-035 | Expand | C | - |  |
| M-036 | Absence of toasts, errors and confirmations | P | 26 | must be authored |
| M-037 | Select option values (QC Type, Report Category, Report By, Wholesale Status) | P | 16 |  |
| M-038 | Grey default 'Not Done' / 'New Outlets' | C | - | UI quirk, define explicit defaults |

### Entities (E)

| Id | Item | Verdict | Gaps | Note |
|---|---|---|---|---|
| E-01 | User account, credential, session | C | - | lens-data M-40 covers history and Remember me in G-12 |
| E-02 | Geography hierarchy | C | - |  |
| E-03 | Channel / classification / outlet class | P | 01 | 'Classification' for Web Entry undefined |
| E-04 | Outlet (master and requests) | P | 07, 08, 25 |  |
| E-05 | Cluster | C | - |  |
| E-06 | Route | P | 24 | route kind |
| E-07 | Field force user / assignment | P | 24 |  |
| E-08 | Sales Plan (zone SKU enablement) | P | 05 |  |
| E-09 | Product master | P | 25 | status |
| E-12 | Market QC entry | M | 06 |  |
| E-13 | Warehouse QC entry | M | 06 |  |
| E-14 | Daily route sales entry (web), Astha outlet entry | M | 01, 02 |  |
| E-15 | Final submit record | P | 03, 19 |  |
| E-16 | Memo | C | - |  |
| E-17 | Sales / STD aggregates | C | - |  |
| E-18 | Target | P | 09, 10 |  |
| E-19 | Calls / visits | C | - |  |
| E-20 | Geo fencing / FF location | C | - | F-API-023 last synced fix |
| E-21 | Login / Submit status | P | 18 |  |
| E-22 | Sync / session log | C | - | route_log |
| E-23 | SR device login OTP | P | 11 | hash vs retrievable |
| E-24 | Astha gift choice | P | 20 |  |
| E-25 | Loyalty / Astha program values | P | 21 |  |
| E-26 | GIGO data | C | - |  |
| E-27 | Excel / PDF / print artefacts | P | 16, 14 |  |

---

## Appendix B. PDF spot-check log (`b352c81e-AKTC_Web_User_Manual.pdf`)

| Pages | Checked | Result |
|---|---|---|
| 2 | Login | Matches: User ID, Password with eye, Remember me unchecked, Login; no forgot-password link. |
| 3-4 | Dashboard, Browse Retailer | Matches. Submit Status pair '1 Login successfully / 0 Submitted successfully' confirmed. Browse Retailer shows NID, TIN, Trade License columns (blank) and per-column search icons. |
| 5-7 | Market QC, QC Report, Warehouse QC | Matches. 5 + 5 reason columns confirmed, last 'Others' in each group. 'Download PDF' present on p.6. Warehouse QC has no route selector. |
| 10-11 | Sales Plan edit and picker | Matches. Zone row, chips, tri-state 'SKU List', green tick and red x. Captions say 'territory'. |
| 19-20 | Web Entry, Final Submit | Matches. 'Brand Data' button, read-only Date, Sale column read-only; Final Submit statuses 'exist' and '--', 'Delete Section Data', both pink banners. |
| 22 | STD Memo Report | Matches the 10 extra filters. |
| 24-25 | DSS, DS-RRS | Matches. Only Total and GT STD rows have values. Print button on DS-RRS. |
| 27-28 | Data Entry Log, Final Submit Log | Matches. MAX earlier than MIN confirmed on p.27; 'Not Done' selected by default in grey on p.28. |
| 31 | By Outlet Report | Matches. Includes STD Criteria ('000), Memo Criteria, Outlet Code, Sub Channel (9), Report Type (2), 'Download Report'. |
| 35-37 | Set Target, Target Allocation, Target Approval | Matches. 'Phatherhat of April-2026', 'variant', 'stt', 'WMO approval pending', eye and download icons. |
| 40-41 | Outlet Approval Panel, approve dialog | Matches. Heading 'Firefly Outlets Reports'; Outlet Type options; Verify and Reject/Approve buttons; dialog text. |
| 42-44 | Wholesale | Matches. Basket badge 0 then 3, 'Selected Outlets (3)', 'Remove', Submit; p.43 and p.44 slide titles say 'Login page'. |
| 45-46 | Credentials, SR Device OTP | Matches. Guideline text and example password; OTP table with 5 columns and 'No Data'. |

Not re-checked (no new claim rests on them): pp.8-9, 12-18, 21, 23, 26, 29-30, 32-34, 38-39, 47-48.

---


## Appendix C. What only the sponsor's screenshots or the live app can settle

1. What 'exist' means on Final Submit, what 'Delete Section Data' deletes (web-entry rows only, or app-synced memos too), and whether Final Submit locks the day (G-03, Q11).
2. How the cut-off date is produced: rolling (today only), a configured date, or the per-zone 'Data Entry Date' on Sales Plan (G-04, G-05).
3. What DS-RRS stands for, its Print layout, and where the DSS route link goes (G-13, G-14).
4. Web Entry: purpose of 'Brand Data', the 'Classifications' list, the Sale formula, how often TSOs use Web Entry and Astha Web Entry, and how an Astha entry is saved (G-01, G-02).
5. Submit % basis on a day with real submits (G-18).
6. Target workflow: expanded 'Upload Excel Panel Section', the sample workbook, the approver (WMO) screen, other Target Types than 'stt', other Product Types than 'variant' (G-09, G-10).
7. Outlet Approval: Verify and Reject dialogs, the table for Close Outlets and Info Changes, effect of each action on the SR app and outlet master (G-07).
8. Wholesale: how to unflag, and what changes for a wholesale outlet (price type, KPIs) (G-08).
9. Astha gift choice: save mechanism, lock rule, meaning of Gift Status Yes and No (G-20).
10. Dashboard hidden parts: Filter popup, 'i' tooltips, By Segment Contribution, Sales Trend, Geo Fencing, FF Geo Location, and the meaning of Total Service Zone (G-17).
11. QC: re-entry rule, limits, and the layouts of the Excel and PDF outputs (G-06).
12. Menus and permissions of the DMO, WM, WMO, Top Management, AMO and admin web logins, including Tutorial, Performance Leaderboard, Superstar and Daily Tracking (G-23, U-01).
13. The Excel exports of every report (column sets), the print view, and the DSS and Browse Routes result tables (G-16, G-feat-53).
14. OTP expiry, refresh and retry rules (G-11).
15. Error, validation, confirmation and success texts for every action (G-26).
16. 'PDA' column and 'PDA Contact No.' on Sales Plan (G-05).

---


## Appendix D. Per-screen filter and control matrix (for G-man-web-16)

Geo = five geography selectors. M = multi-select 'All Selected (n)', S = single-select pre-filled. Outputs: GD Get Data (grid), GE Get Excel, PDF, PR Print, DR Download Report.

| Screen | Geo | Other controls | Outputs |
|---|---|---|---|
| S-04 Browse Retailer | M | per-column search | GD, GE |
| S-05 QC Entry | S | Select Route, Choose Date, fault grid | Submit |
| S-06 QC Report | M | Choose Date Range | GE, PDF |
| S-07 Warehouse QC Entry | S | Choose Date, fault grid | Submit |
| S-08 Route Wise QC Report | M | Date Range, QC Type | GE |
| S-09 Sales Plan | S | edit row | GD |
| S-18 Web Entry | S | Select Classifications, Select Route, Target Outlet (ro), Successful Call, Brand Data, grid | Save |
| S-19 Final Submit | S | Date of Data Entry | Filter, Submit |
| S-20 Astha Web Entry | S | Route, Channel, Category, Date, grid | Filter |
| S-21 STD Memo Report | M | Date Range, Location, Date Grouping, Category, Product Type, Active Status, Select Products, Classification Type, Report Type | GE |
| S-22 SR Efficiency | M | Date Range | GE |
| S-23 DSS | S | Field Force Type, Choose Date, Category | GD, GE |
| S-24 DS-RRS | S | Field Force Type, Choose Date, Route, Category | GD, GE, PR |
| S-25 Route Wise STD | M | Date Range, Category, Product Type, Active Status, Select Products | GD, GE |
| S-26 Data Entry Log | M | Choose Date | GD, GE |
| S-27 Final Submit Log | M | Choose Date, Submit Status | GD, GE |
| S-28 Route Wise Memo | M | Date Range, Category, Product Type, Active Status, Select Products | GD, GE |
| S-29 Route Wise BSR & CPR | M | same as S-28 | GD, GE |
| S-30 By Outlet | M | Select Category, Product Type, Active Status, Select Products, Sub Channel, Report Type, Date Range, STD Criteria, Memo Criteria, Outlet Code | DR |
| S-31 Astha Report | M | Year, Quarter, Month | GE |
| S-32 GIGO | M | Choose Date | GE |
| S-33 Loyalty - Diamond League | M | Date Range | GD, GE |
| S-34 Set Target | Wing, Division, Territory (M) | Choose Month, Apply, Download Sample, Upload Excel Panel | Apply |
| S-35 Target Allocation | M | Choose Date | GE |
| S-36 Target Approval List | M | Choose Month | View |
| S-37 AMO Call Report | S | Field Force Type, AMO, Report Type, Choose Date, Report By | GE |
| S-38 SR Outlets Reports | M | Report Category, Date Range | GD, GE |
| S-39 Outlet Approval Panel | M | Outlet Type | GD, GE |
| S-41 Retailer Wholesale Outlet | M | Wholesale Status, Outlet Search | GD, GE |
| S-44 SR Device OTP | M | none | View |
| S-45 Astha Gift Choice Panel | S | Route | GD |
| S-46 Astha Gift Choice Report | M | Gift Status | GE |

