# Verification: "Closing the day: sales submit, final submit, dues, void"

Date: 2026-10-04. Verifier: independent re-check against the PDF pages and docs/. Register: manuals/manual-delta-register.md section 1 and 2.7.
PDF page numbers below equal the printed page numbers cited by the register (checked: SR 71-73, AMO 66-68, AMO 39, AMO 28, TSO 5-7 and 11-14, Web 3, 9-11, 19-21, 27-29).
Page-reading notes: Bengali digits were read from the 3x crop of SR p71 (Outlet 1/1, Sale 68/68, Stock 19,700/19,700, QC 0/0, Promotion 0/0). Other screens were read at page resolution; text quoted below is legible.

## Summary

| Id | Verdict | One-line reason |
|---|---|---|
| G-man-030 | PARTLY | Warn-with-Yes/No is on p72 (SR) and p67 (AMO) as claimed. The "contradiction" with p73/p68 is a textual one only; the screens are identical and reconcilable. The register also missed that docs/04 itself says "dues cleared". |
| G-man-031 | PARTLY | Row sets, headers and the 8-row AMO variant are right. The register's "sale = memo count" assumption is contradicted by the pages (1 outlet, 68 / 118 / 25 sales). 'অফলাইন' is not in the manual. AMO QC does have a capture button. |
| G-man-066 | PARTLY | Captions and 25% = 1/4 login arithmetic are right. The submit bar is 0% on every surface, so the denominator is NOT provable. docs/07 and docs/08 already say "logged-in"; the clash is with docs/10 and plan D-03 only. Caption wording differs per surface. |
| G-man-070 | CONFIRMED | The alert screenshot sits over the Wing..Zone form with Get Sales Data boxed, so the duplicate check fires at Get Sales Data. |
| G-man-071 | PARTLY | Not Set routes do not block and there is no confirm: seen. "No time gate" is not evidenced: the 14:26:05 row is a "Not Done" row. All "SR Not Set" rows in the samples are AMO routes. |
| G-man-086 | PARTLY | The Delete Section Data button is on p20 only on the "exist" row, with no confirm. Its effect and scope are not shown, only implied by the label. docs/09 does list "Data Entry" and "Final Submit Log" pages, so "no web page" is overstated. |
| G-man-087 | CONFIRMED | Banner text and "call support" are on p20; the spec is silent. Extra finding: the zone's Sales Plan "Data Entry Date" is 2026-04-12, one day before the banner date, which hints at a per-zone lever. |

---

## G-man-030  Sales Deposit dues rule: warn vs block

**Claim (register row 108, C-17, I-09):** The manuals say both "warn" (SR p72, AMO p67) and "block" (SR p73, AMO p68). Spec says "warns" and has cfg.day.sales_submit_dues_warning (off/warn/block, default warn). Winner: soft warning. The "১টি" in the dialog is a dynamic count.

**What I saw**
- SR p71: Sales Deposit screen. Header "ডিভাইস স্ট্যাটাস ● অনলাইন". Table বিষয় | ডিভাইস | সার্ভার with 5 rows. Red advisory "আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।" Button "ডাটা সিঙ্ক করুন" active, "বিক্রয় জমা" grey (disabled).
- SR p72: after sync the "বিক্রয় জমা" button is blue (enabled), callout "সিঙ্ক এর কাজ সম্পূর্ণ হয়ে গেলে ... Enable হয়ে যাবে". Pressing it shows a warning dialog "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" with green হ্যাঁ and red না. Callout: "এরপর আপনার উক্ত রুট এ যদি কোন দোকানের বাকি পরিশোধ অবশিষ্ট থাকে তাহলে এই মেসেজ দেখতে পারবেন। আপনি চাইলে হ্যাঁ ... বিক্রয় জমা দিতে পারবেন। অথবা ... না ... পুনরাই বাকি পরিশোধ করতে পারবেন।" This is a soft warning. It is scoped to "your route" and counts retailers, not taka.
- SR p73: left screenshot is the same blue enabled button. Callout: "সকল দোকানের বাকি পরিশোধ সম্পূর্ণ হয়ে গেলে ডাটা সিঙ্ক করা শেষে ‘বিক্রয় জমা’ অপশনটি Enable হয়ে যাবে।" Right: success dialog "বিক্রয় সফল ভাবে জমা হয়েছে" with OK.
- AMO p66-68: identical structure and wording (AMO p67 dialog, p68 enable sentence and success dialog). The AMO advisory says "এই সেকশন থেকে" where SR says "এই অপশন থেকে" (one word differs).
- The count "১টি" appears as 1 in all four dialog screenshots (SR p72, AMO p67 and the two dimmed backgrounds). Nothing on the pages shows it changing, so "dynamic" is an inference (a reasonable one).

**Spec quotes**
- docs/06 Day flow step 7: "Sales Submit per the day state machine (warns if any retailer still has dues)".
- docs/07 Sales Submit (AMO): "Sync → then Sales Submit (warns on outstanding dues)".
- docs/04 day state machine: "`sales_submitted` (SR closes the day, dues cleared)". The register did not cite this. It implies dues must be cleared first, which contradicts the two "warns" lines inside the spec.

**Verdict: PARTLY**

**Corrected statement:** SR p72 and AMO p67 show a Yes/No confirm that lets the rep submit with dues outstanding. The "enabled after all dues are paid" sentence on SR p73 and AMO p68 is the same flow described for the zero-dues path: the screenshot beside it shows the same enabled button that p72 shows before any dues dialog. The pages therefore prove an inconsistent sentence, not an inconsistent behaviour. The observed behaviour is: submit enabled after sync completes; if any retailer on the route still owes, a Yes/No dialog; Yes submits. The one real contradiction is inside the spec (docs/04 "dues cleared" vs docs/06 and docs/07 "warns"); it needs a D-id. Winner stays: warn, key default `warn`.

**Build implication**
- Rule: Sales Submit button enabled only when the last sync of this business date completed with no pending rows; dues never disable it.
- UI: dialog text as a localisation string with a `{n}` placeholder (count of retailers on the route with due_amount > 0); buttons হ্যাঁ / না; the না path returns to the Memo menu due list.
- Config: keep `cfg.day.sales_submit_dues_warning` = warn; add a D-id that overrides docs/04 wording ("dues cleared" becomes "dues warned").
- Data: record on `day_submit` (or the route day row) `dues_outstanding_count` and `submitted_with_dues bool` so the warning's outcome is reportable.
- Test: submit with 0, 1, many owing retailers; offline at the moment of pressing (queued state, see G-man-031).
- Open: does the count include only this route, or all the SR's routes? The callout says "উক্ত রুট এ" (in that route).

---

## G-man-031  Sales Deposit reconciliation: rows, measures, states

**Claim (register row 109, I-15):** SR shows 5 rows; AMO shows 9, but p67 shows 8 (no 'মূল্য সম্মতি'). Define each row's measure (outlet = visited, sale = memo count, stock = total quantity in sticks, QC = entries, promotion = DRP/slide records: assumption). Server column = last server_totals; p66 shows device = server before any sync. Types with no capture screen (AMO QC, প্রমোশন, মূল্য সম্মতি) stay but show 0. Add an Online/Offline indicator ('অফলাইন') and progress, failure, timeout and retry messages.

**What I saw**
- SR p71-73: 5 rows: আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন. Values 1/1, 68/68, 19,700/19,700, 0/0, 0/0. Confirmed.
- AMO p66 and p68: 9 rows: আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন, ডিস্ট্রিবিউশন এবং OOS কর্মক্ষমতা, মূল্য সম্মতি, জয়েন্ট কল, সার্ভে. Values 1/1, 118/118, 45,150/45,150, then 0s. Confirmed.
- AMO p67: 8 rows, "মূল্য সম্মতি" is absent; values 1/1, 25/25, 11,700/11,700. Confirmed (different test data from p66/p68).
- AMO p66 (button not yet pressed, submit grey) already shows server = device (118/45,150), and p68 (after sync) shows the same figures. So the server column was already equal before this press. It may simply have been synced earlier; the page cannot prove "before any sync".
- Device status shows only "অনলাইন" with a green dot on all six pages. No "অফলাইন", no progress, error, timeout or retry text anywhere (AMO inventory U-47 agrees).
- Measure check: 1 outlet and 68 (SR), 118 and 25 (AMO) "বিক্রয়". One outlet cannot hold 68 or 118 memos. AMO p28 (sale review) shows "সর্বমোট ৩৮" as the quantity total for one sale (10+25+1+1+1), and AMO p39 live dashboard shows "৩৮ মোট বিক্রয়" next to "১৬১.৫০ মোট টাকা", so "বিক্রয়" looks like a quantity total (units of the SKU) or a line count, not a memo count. Stock (19,700; 45,150; 11,700) is a quantity.
- AMO p28: the sale review screen has an orange "প্রোডাক্ট QC" button next to "প্রিন্ট". The QC screen is never shown, but a capture entry exists.

**Spec quotes**
- docs/06 step 7: "Sync (device-vs-server counts: outlet, sale, stock, QC, promotion)".
- docs/07 Sales Submit (AMO): "Device-vs-server counts: Outlet, Sale, Stock, QC, Promotion, Distribution & OOS performance, Price compliance, Joint call, Survey."
- docs/04 step 3: "The app compares them to its own counts and shows the device-vs-server screen ... A mismatch is visible, never silent."
- docs/07 Sale: "Same as the SR sale flow ... QC ... print", so QC capture is in the spec for the AMO too.

**Verdict: PARTLY**

**Corrected statement:** Row sets are as claimed (SR 5, AMO 9, AMO 8 on p67), headers are বিষয় / ডিভাইস / সার্ভার, and only "অনলাইন" is ever shown. Three details are wrong. (a) "sale = memo count" is contradicted by the pages: with 1 outlet the sale row reads 68 / 118 / 25, so it is a quantity total or line count; the unit is unknown and must be asked. (b) 'অফলাইন' and every failure string are new inventions, not manual text. (c) The AMO does have a Product QC button on the sale review (p28), so "no capture screen" is true only for প্রমোশন and মূল্য সম্মতি (and for the AMO's QC screen being undocumented, not absent).

**Build implication**
- Config: `cfg.sync.reconcile_types` per role and app version (SR: outlet, sale, stock, qc, promotion; AMO: those plus dist_oos, price_compliance, joint_call, survey; the AMO v-variant without price_compliance is allowed).
- Rule: each row has a documented `measure` in the config (visited outlet count; sale measure = TBD; stock = quantity total in SKU units; QC = entries). Mark sale measure "unknown; confirm with the business" (new Q). Do not ship the memo-count assumption.
- Server column: filled only from the last sync response `server_totals` with its timestamp; blank before the first sync of the day; never a copy of the device column.
- UI: keep "ডিভাইস স্ট্যাটাস" line; add অফলাইন / queued state as new strings (cfg strings), with a pending badge when submit is pressed offline (cfg.day.sales_submit_offline_queue). Mark these strings NEW.
- Schema: `reconcile_snapshot(user, business_date, type, device_count, server_count, synced_at)` so a mismatch is stored, not only displayed.
- Test: fuzzed duplicate and out-of-order batches end with device == server for every configured type.

---

## G-man-066  Submit % denominator

**Claim (register row 144, C-21):** The TSO tile (and the web and AMO live tiles, "appear to") divide submitted by logged-in routes. The plan flips it (D-03 target routes canonical). Parity first: cfg.kpi.submit_pct_denominator = logged_in_routes. Print captions exactly 'Target Route', 'Total Login', 'Login Count', 'Total Bikroy Joma'. Correct SF-7 and docs/10.

**What I saw**
- TSO p6 (card "Login & Bikroy Joma Status"): Login Status 25%, captions "4 Target Route", "1 Total Login" (1/4 = 25%, so login % = logins / target routes, confirmed). Bikroy Joma Status 0%, captions "1 Login Count", "0 Total Bikroy Joma".
- Web p3 (card "Login/Submit Status"): Login Status 25.0%, "4 Target Route", "1 Successful Login". Submit Status 0.0%, "1 Login successfully", "0 Submitted successfully". Also Final Submit Status card: Total Zone 1, Total Service Zone 1, Remaining 1, Total Final Submit 0, 0.0%.
- AMO p39 (Bangla "লগইন & বিক্রয় জমা স্টেটাস"): login 100%, "১ টার্গেট রুট", "১ মোট লগইন"; submit 0%, "১ লগইন করেছে", "০ মোট বিক্রয় জমা".
- Every submit bar is 0% with a numerator of 0. 0/1 and 0/4 both give 0%, so no page can distinguish the denominators. What the pages do show is the caption pairing: the number under the submit bar is the logged-in count on all three surfaces.
- Strike-rate cross-check (supports the dashboard maths): TSO 2/44 = 4.5%; Web 1/43 = 2.3%; AMO 1/81 = 1.23%, all successful calls / target outlets.

**Spec quotes**
- docs/08: "login % = total login / target routes; submit % = total submitted / login count."
- docs/07 Live Dashboard: "login % = logged-in routes / target; submit % = submitted / logged in."
- docs/10 KPI table: "Submit % | routes uploaded ÷ target routes (apps: ÷ logged-in)."
- seed-findings #7: web target routes (docs/10), apps logged-in (docs/07, docs/08).

**Verdict: PARTLY**

**Corrected statement:** For the TSO and AMO tiles the spec already says "÷ logged-in", so there is no manual-vs-spec contradiction there; the only contradiction is with plan decision D-03 (target routes canonical) and with docs/10's web column. The screens suggest, by caption pairing only, that the web uses the same basis; no screenshot can prove it, because every sample is 0%. Keep the register's recommendation (parity: logged_in_routes for all three), but flag the web basis as unconfirmed and keep target-routes as a secondary figure. The caption set to print differs per surface: TSO uses 'Target Route / Total Login / Login Count / Total Bikroy Joma'; web uses 'Target Route / Successful Login / Login successfully / Submitted successfully' and the card title 'Login/Submit Status'; AMO uses the Bangla set above. The register's single caption list is TSO-only.

**Build implication**
- Config: `cfg.kpi.submit_pct_denominator` default `logged_in_routes` for TSO, AMO and web until a real day with non-zero submits is checked on the live dashboard; store the basis next to the figure in the API (`submit_pct`, `submit_pct_basis`).
- UI: per-surface caption strings in the localisation layer; add 'Bikroy Joma' (বিক্রয় জমা) = Sales Submit to the glossary.
- Rule: login % stays logins / target routes (verified 1/4 = 25%).
- Edit docs/10 KPI row and D-03: submit % shown on apps = ÷ logged-in; ÷ target routes is a second, named KPI.
- Open: confirm the web basis from a day with submits (web test account), and what "Total Service Zone" vs "Total Zone" mean (web p3).

---

## G-man-070  Final Submit needs a read endpoint (check at Get Sales Data)

**Claim (register row 148, I-28):** The "already submitted" alert fires at Get Sales Data, before Submit. docs/09 has only POST /day/final-submit (409 on a second attempt); a GET preview is needed.

**What I saw**
- TSO p12: drawer "Final Submit / Submit Sales Data" then a form Wing, Division, Territory, House, Zone and a disabled grey "Get Sales Data" button. Callout: select the five levels and click "Get Sales Data".
- TSO p13: after Get Sales Data, a screen with "Sales Date: 2026-04-26", a Routes list (each card "FF: SR Not Set" or "FF: SR - Testing Banani ..."), and a green "Submit" button. Success dialog (English): "Success / Final Submit Done Successfully..." with OK.
- TSO p14: left, the filled form with Get Sales Data boxed. Right, a dialog in Bangla "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" with OK, drawn over the Wing/Division/Territory/House/Zone form, again with Get Sales Data boxed. The Routes screen is not behind it. Callout text: "Zone এর ফাইনাল সাবমিট হয়ে গেলে পরবর্তীতে সাবমিট করতে চাইলে অ্যালার্ট দিবে" ("if you want to submit again later it will alert"), which is looser than the screenshot.
- So the alert is raised on the form, before the Routes list is built: the check runs at Get Sales Data (the callout wording says "submit" loosely).

**Spec quotes**
- docs/08: "Select Wing → Division → Territory → House → Zone → "Get Sales Data" → sales date + routes with FF (SR) name or "Not Set" → Submit → success. A second attempt the same day is refused ("already given FINAL SUBMIT for today")." The spec does not say where the refusal fires.
- docs/09: "`POST /day/final-submit` `{ zoneId, businessDate }` (once/zone/day)." No GET. The "409" in the register comes from the plan (lens-features F-API-009), not from docs/09.

**Verdict: CONFIRMED** (minor attribution fix: 409 is a plan proposal, not spec text.)

**Corrected statement:** TSO p14 shows the duplicate alert over the zone form with Get Sales Data highlighted, so the app must know "already submitted" before it can show the routes list. docs/08 and docs/09 have no read for that. Add a scoped GET (zone, date) returning `alreadySubmitted`, submitted_at and the route list; keep POST idempotent.

**Build implication**
- API: `GET /day/final-submit/preview?zoneId=&businessDate=` (server-resolved scope; zone must be in the TSO's scope) returning `{salesDate, alreadySubmitted, submittedAt, submittedBy, routes[{routeId, name, kind, ffName|null}]}`.
- Rule: the Bangla alert comes from `alreadySubmitted`; POST with the same client_uuid returns the same success; a different uuid on a closed zone-day returns the same Bangla text (409).
- Strings: Bangla alert as shown, plus the English "Success / Final Submit Done Successfully..." (parity, mixed language on one flow).
- Schema: `final_submit` needs `client_uuid` (retry-safe) and a route snapshot (`final_submit_route`) because the list shown at submit time is a fact the web "Final Submit Log" later reports.
- Both calls online-only; offline shows the generic no-network message (to author, none in the manual).
- Test: two TSOs submit the same zone concurrently; a retry after timeout; Get Sales Data on a closed zone.

---

## G-man-071  Final Submit rules (Sales Date, SR Not Set, no confirm, no time gate)

**Claim (register row 149, C-42):** (1) Sales Date is server business date, read-only in the app, past dates only from the web with support override. (2) 'SR Not Set' and no-data routes do not block (parity), but add a confirm (cfg.day.final_submit_confirm). (3) Late batches after submit are accepted and flagged. (4) Per-route Status and Delete stay web-only. (5) No time gate: set cfg.day.final_submit_earliest_time to none; the web Final Submit Log sample carries 14:26:05.

**What I saw**
- TSO p13: "Sales Date: 2026-04-26" in a plain box with no picker icon or arrow (the Leave screen on p11 shows a calendar icon where a date is editable). The AMO screens in the same capture session show the date 2026-04-26 in their headers, so the value is probably today's date. Read-only is therefore the likely reading, not a stated rule.
- TSO p13: the route cards "AMO-Apsis RouteAMO / FF: SR Not Set" (twice) and "Apsis AMOAMO / FF: SR Not Set" are in the list, other cards show "FF: SR - Testing Banani", "... 2" and the Submit button is green and the Success dialog follows directly. So Not Set routes do not block, and there is no confirm step before Success. The pages show no upload-status or "no data" marker on any route card.
- Every "SR Not Set" row on TSO p13 and Web p20 is an AMO-named route (AMO-Apsis RouteAMO, Apsis AMOAMO). In the samples, "Not Set" may be the normal state of an AMO-operated route, not an exception.
- Web p20: same list as a table with the same Not Set rows, Submit enabled.
- Time gate: no page states one. Web p28 (Final Submit Log Report) filter "Submit Status" has options Done / Not Done and the displayed value is "Not Done"; the single result row reads SUBMIT_STATUS "Not Done", MAX_TIME 14:26:05, MIN_TIME 14:26:05, COUNT 1. So 14:26:05 sits on a row marked Not Done and is not a final-submit time. The register's use of it as evidence is not supported. The TSO screens carry no clock.
- Web p27 (Data Entry Log) shows download MAX/MIN times of 13:24:51 to 17:50:40 for 2026-04-13: these are route download/upload times, not submit times.

**Spec quotes**
- docs/08: "sales date + routes with FF (SR) name or "Not Set" → Submit → success."
- docs/04: "Check-out opens at 5 pm; final submit is once per zone per day." No time gate on final submit.
- docs/13 Q11: "After final submit — can anything change, and who can reopen a day?" (the web manual does not answer it either: its U-14 says whether Final Submit locks the day is not stated.)
- The 17:00 gate is only in the plan (lens-config `cfg.day.final_submit_earliest_time` = 17:00, "A: same as check-out"), not in docs/.

**Verdict: PARTLY**

**Corrected statement:** Confirmed on the pages: Not Set routes do not block, and no confirm exists between Submit and Success. Inferred, not stated: read-only Sales Date. Not evidenced: "no time gate". Neither the manuals nor the spec state a gate; the 14:26:05 row is a "Not Done" row, so it cannot show that a submit happened before 17:00. Default "none" is a parity-by-silence choice and must be marked "unknown; confirm with the business". The Not Set exception count must exclude routes whose field-force type is AMO.

**Build implication**
- Config: `cfg.day.final_submit_earliest_time` default `none` with a note "no gate seen in manual; unconfirmed"; `cfg.day.final_submit_confirm` default on (a deliberate D-id: the manual has no confirm, the action is irreversible and the web delete exists only before submit); `cfg.day.final_submit_allow_not_set_routes` default true.
- Rule: Sales Date = server Dhaka business date, shown read-only. Preview counts only routes of kind SR with no upload; AMO routes with "SR Not Set" are listed but not counted as exceptions.
- Schema: `final_submit_route` snapshot rows (route, ff_name or null, kind, upload_state at submit).
- Late batches after close: accept into the closed day, flag `after_final_submit` (G-feat-17); report them.
- Open questions (new): what do MIN/MAX_TIME/COUNT with "Not Done" mean on the Final Submit Log (p28); can a day be unsubmitted; any time gate in the live app.

---

## G-man-086  Web Final Submit with "Delete Section Data"

**Claim (register row 164, C-26, C-28):** Web Final Submit page (Web p20) lets the TSO delete a route's day data. docs/09 has only POST /day/final-submit (TSO app) and no web page; docs/03 says never hard-delete. Build the page; make Delete an audited VOID with tombstones in the ingest registry; offer Delete only before Final Submit; default scope web-entry rows only.

**What I saw**
- Web p19 (Web Entry): sidebar Data Entry > Web Entry / Final Submit / Astha Web Entry. The form has Date 2026-04-13, Classifications, Route, Target Outlet (read-only 37), Successful Call (5), Save, Brand Data, and a per-SKU Issue / Return / Sale / Memos grid.
- Web p20 (Final Submit): Bangla pink banner (see G-man-087), Wing..Zone single-select dropdowns plus green "Filter", table Route | SR Name | Status | Actions. Seven rows. Only "Apsis RouteDaily / SR - Testing Banani / exist" has a red "Delete Section Data" button; all other rows show "--" with no action. Advisory under the table: "Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit". "Date of Data Entry" picker (2026/04/13) with calendar icon, blue "Submit" button. Caption: select the Zone and click Submit to submit each day's sales.
- No confirmation dialog, toast or success message for Submit or Delete appears on p20. What Delete removes is not stated: no text explains it. The label and the "exist" row are the only evidence. Whether the delete uses the date in the picker below is not shown either.
- The logged-in web user is "tso-apsis": the TSO runs this page.

**Spec quotes**
- docs/03 conventions: "Soft-delete with `status` where the business needs history; never hard-delete transactions."
- docs/09 Pages: reports include "Data Entry Log (download/upload per route), Final Submit Log"; admin pages include "QC, Sales Plan, Data Entry, Supervisory Module ...". So a "Data Entry" admin page is listed by name only; nothing describes the Final Submit page, a date picker, or any delete.
- docs/09 API: "`POST /day/final-submit` `{ zoneId, businessDate }` (once/zone/day)."
- docs/04: "A record is immutable once synced; an edit or a due payment is a **new row** that references the original."
- schema.sql `final_submit`: PK (zone_id, business_date), submitted_by, submitted_at; no client_uuid, no reopen or void columns.

**Verdict: PARTLY**

**Corrected statement:** The web Final Submit page and a per-route "Delete Section Data" button exist (p20), shown only on a route whose Status is "exist", with no confirm. The spec forbids hard-deleting transactions and has no description of this page or action. The manual does not show what the delete removes (web-entered rows only, or app-synced memos too), which date it applies to, or whether Final Submit locks the day. "No web page in the spec" is overstated: docs/09 lists "Data Entry" and "Final Submit Log" by name. The blocker stands as a design risk (a delete beside idempotent sync), but its real size depends on that unknown scope, so it must be resolved with the business before Phase 4.

**Build implication**
- Schema (new): `data_void(id, zone_id, route_id, business_date, scope, reason, voided_by, voided_at, summary)`; `status 'void'` on the affected rows; `ingest_registry.state = 'voided'` tombstones so a later phone upload of a voided client_uuid is rejected with `voided_by_admin` and visible on the device-vs-server screen.
- Rule: Delete = audited void with mandatory reason, only while the zone-day is not final-submitted; default scope web-entry rows; app-synced memos need a higher role and an explicit D-id.
- UI: web Final Submit page with the six elements above; both banners as cfg strings; Delete requires a confirm (the manual shows none; deliberate improvement, D-id).
- Aggregates: a void must re-aggregate the affected business_date facts (late-data recompute window).
- API: `POST /admin/day/void`, `POST /day/final-submit` shared by app and web (one server rule, two surfaces; unverified that the live app writes one shared record).
- Test: void then retry the same batch (rejected, no resurrection); void then different batch for the same route-day.
- New open questions: what "exist" counts; Delete scope; does Final Submit lock edits (docs/13 Q11, web U-14).

---

## G-man-087  Back-date cut-off for web data entry

**Claim (register row 165, U-14):** The web blocks data entry before a cut-off date with a "call support" override. The spec has nothing for the web; plan cfg.sync.max_backdate_days = 7 governs app sync. Define cfg.web.entry_backdate_days (0 = today only: assumption) or cfg.web.entry_min_date with per-zone override; replace "phone support, who edits the DB" with an audited unlock; confirm whether the Sales Plan "Data Entry Date" is the lever.

**What I saw**
- Web p20 pink banner: "আপনি 2026-04-13 তারিখের পূর্বের কোন সেলস ডাটা এন্ট্রি করতে পারবেন না। যদি এর পূর্বের কোন ডাটা এন্ট্রি করার প্রয়োজন হয়, অনুগ্রহ করে সাপোর্টে ফোন দিন।" ("You cannot enter any sales data before 2026-04-13. If you need to enter earlier data, please phone support.")
- Dates on the same session's web screens: Web Entry 2026-04-13 (p19), Final Submit "Date of Data Entry" 2026/04/13 (p20), Data Entry Log and Final Submit Log choose-date 2026-04-13 (p27, p28). So 2026-04-13 is also the screenshot day or the default date.
- Web p9-11 Sales Plan for the same zone (Banani - Test): column "Data Entry Date" = 2026-04-12 (p9) and 04/12/2026 as an editable field in edit mode (p10-11). The banner date is exactly one day after it.
- "Who edits the DB" is not on any page; the manual says only "phone support".

**Spec quotes**
- No sentence in docs/01-13 mentions a back-date limit for web entry. docs/09 lists "Data Entry" among admin pages with no rules.
- Plan only: lens-data DQ-09 "`business_date` ∈ [today − cfg.sync.max_backdate_days (7), today + 1]" (app sync, a different surface).

**Verdict: CONFIRMED**

**Corrected statement (additions, no reversal):** The banner and "call support" are as claimed and the spec is silent. Three mechanisms fit the pages, and the register's "0 = today only" is just one of them: (a) a rolling window (banner date = today), (b) a fixed date, (c) a per-zone stored date, i.e. the Sales Plan "Data Entry Date" (2026-04-12) with the banner showing that date + 1. Reading (c) is supported by the one-day offset and by the column's existence; it is a hint, not proof. "Phone support, who edits the DB" is the register's inference.

**Build implication**
- Config/schema: store the cut-off as a per-zone date (`sales_plan_zone.data_entry_date`, G-man-088) with a global default `cfg.web.entry_backdate_days` (default 0, ASSUMPTION) so either mechanism can be selected without a release.
- Rule: web entry and web final submit reject `business_date < cutoff` with the Bangla banner built from the cutoff date and `cfg.support.contacts`.
- Audited unlock: `entry_unlock_grant(zone or route, date_from, date_to, granted_by, reason, expires_at, used_at)`, raised by admin or support; every use is logged.
- Keep this separate from the app-sync window (`cfg.sync.max_backdate_days`); document both in docs/09.
- New open question: is the cut-off per zone (Sales Plan Data Entry Date) or global, rolling or fixed, and who moves it today.
