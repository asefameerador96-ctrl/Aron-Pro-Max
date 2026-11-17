# Master data mapping

Source: the owner's October master data workbook. This document maps it to the Aron rebuild using only the sanitised profile (`docs/reference/master-data-profile-2026-10.md`). No row values appear here.

## 1. What already matches

- **Hierarchy codes and names.** Wing, division, territory, house, zone and route codes are stored as text, unique, up to 40 characters, so leading zeros survive. Each level has `name` and `name_bn`. The chain Wing > Division > Territory > Zone > Route matches the single-parent foreign keys.
- **Houses.** `app.house` exists. `zone.house_id` is nullable. House scope nodes and the dashboard House filter exist.
- **Clusters.** Keyed by (zone, name) with no cap per zone. Free-text `cluster_type` holds 'Transit Hub'.
- **Multi-route reps.** Effective-dated `route_assignment` and a weekday mask per route already handle a rep with 1 to 4 routes, including weekday alternation. SR reach is the union of the rep's assigned routes. The server computes it.
- **Visit patterns.** Every file pattern fits as a mask (bit0 Sat to bit6 Fri). Friday is the default weekend.
- **Outlet code.** Text up to 32 characters. All six code shapes in the file pass. Android never parses codes as numbers.
- **Nullable fields.** Coordinates, geo class, address, contact number and `sub_channel_id` are all nullable. The 7,275 outlets with no coordinates go through the no-location policy.
- **Six channels.** They match the schema, contract, portal and dashboard cards.
- **Bundle size.** It handles a rep with three routes of 250 outlets without paging.

## 2. Gaps to close

All gaps below were confirmed by the verifier. Where the verifier corrected a fact, the corrected fact is used.

| Id | Master data has | Build has | Change needed | Where | Severity |
|---|---|---|---|---|---|
| M01 | 729,884 outlets with coordinates, all inside Bangladesh | The importer sets basis 'master' but leaves `location_confirmed` false. As a result the apps record 'provisional', Nearby and the TSO periphery hide these pins, and every outlet goes to first-visit correction. The server recheck still uses them. The web importer rejects values with more than 8 decimals. | Set `location_confirmed = true` on rows that pass the Bangladesh bounding box. Round values to 8 decimals instead of rejecting them. | import, backend, SR app, AMO app, TSO app | high |
| M02 | Frequency 6 = 'Daily' = every selling day with Friday off (229,279 outlets) | 'daily' only accepts mask 127. A 'daily' route with mask 63 fails the CHECK and `DayPlan.consistent`. Friday stays off only because of `cfg.calendar.weekend_days = [5]`, so a make-up Friday would plan every Daily route. | Import rule: frequency 6 becomes kind 'daily' with mask 127. Log it in DECISIONS.md. Optional later: a forward migration so 'daily' means every selling day. | import, schema, backend, SR app | high |
| M03 | 88,477 outlets with an empty plan | `visit_days_mask` is NOT NULL and must be 1 to 127 on both route and `route_planned`. The contract requires it. "No plan" cannot be stored, and an invented 127 inflates target outlets. | After the owner rules: either add a forward migration for an unplanned route (mask 0, label 'No visit plan', never planned), or import these routes without an assignment. | schema, contract, backend, SR app, import | high |
| M04 | The plan is given per outlet | The plan is per route only. Target outlets counts every active outlet of a planned route. | First count routes with more than one pattern, or a mix of filled and empty outlets. Only if real mixing exists, add a nullable outlet-level mask that the bundle and target counts honour. | import, schema, contract, backend, SR app | high |
| M05 | 92,961 rows with empty Channel. Labels such as 'GT Channel' and 'Astha Channel'. | `channel` is NOT NULL and must be one of 6 short codes, matched exactly. One bad row returns a 400 for the whole batch, even on a dry run. These rows also need a zone and a cluster. | Strip ' Channel' from labels. When the channel is empty, derive it from the sub-channel (Gold, Diamond, Platinum and Titanium mean Astha). Report a bad channel as a row error, not a whole-batch 400. Keep NOT NULL. | import, backend, portal | high |
| M06 | Sub-channel carries the Astha tier (Gold 54,858; Diamond 10,920; Platinum 8,092) | The bulk importer and `OutletImportRow` have no `sub_channel` member, and the INSERT and UPDATE never write it. Only the admin edit and the outlet-request flow set it. | Add an optional `sub_channel` code to the contract, IMPORT_MEMBERS and the web template. Resolve it by code, check it against the channel, and write it in both INSERT and UPDATE. Map '--' to NULL. | contract, backend, portal, import | high |
| M07 | Tiers are Gold, Diamond, Platinum and Titanium. There is no Silver. | V0027 seeds Silver and has no Titanium. D-258 and `cfg.astha.tiers` list Silver. The repo gives two Titanium counts (4 in the pair table, 1,206 in `docs/status/real-data.md`). | Add a forward migration that adds Titanium (channel Astha) and sets Silver inactive. Update D-258, docs/19 and the test fixtures. Reconcile the two Titanium counts. | schema, portal, import | high |
| M08 | Outlet phones: 409,186 with 10 digits, 362 with an 880 prefix, about 56,000 junk. Rep phones are mostly missing the leading 0. | Every path uses the strict `^01[3-9][0-9]{8}$` with no repair: backend import, web import, portal editors, outlet requests, bundle, and user phone. Only Android uses `normalisePhone`. | Apply the `normalisePhone` repair in both importers, the portal editors and the user loader. Store a number that cannot be repaired as NULL with a warning, and do not reject the row. Keep the strict CHECK. | backend, portal, import | high |
| M09 | Houses, zone-to-house links, 6,954 reps and the rep-to-route links | `tools/data/retailers_to_sql.py` already upserts wing through outlet, but it has no houses, no `zone.house_id`, no users and no assignments. The admin API cannot set `zone.house_id`. Single-row endpoints refuse past start dates. | Extend `retailers_to_sql.py`, if the layout matches, with houses, `zone.house_id`, SR users and primary assignments, all keyed by code. Add `house_id` to the zone entity in AdminGeography. | import, backend, portal | high |
| M10 | 104,876 clusters keyed by zone and name | The API importer only looks up existing active clusters. Matching is exact, so case and inner spaces count. There is no bulk cluster endpoint. | Add create-if-missing, or a bulk cluster import, using normalised names. After a collision check, add a functional unique index. | backend, import | high |
| M11 | 10 wings and 50 divisions under one implied calendar | The bundle honours wing and division weekends. The 00:05 route-day job, SqlRoutePlanner and IngestService backdate read only the global weekend. | Resolve the weekend per route with `WorkingCalendars.weekendAt`. Add a test with a wing-level override. | backend | medium |
| M12 | 737,159 outlets | The importer takes at most 5,000 rows per request. Codeless rows get 'AD-<id>', which has the same shape as master codes, so a later master row could merge into an unrelated outlet. | For the final account, build a COPY-into-staging, set-based loader. Use a prefix the master data never uses for generated codes, and check for collisions. | import, backend | medium |
| M13 | Sales_Rep_Id is two letters then digits | SR and AMO usernames need 4 to 32 characters, so a 3-character id fails. | Set username to lower(id) and `employee_code` to the same id. If a 3-character id exists, add a forward migration that relaxes the rule to `{2,31}`. | schema, backend, import | medium |
| M14 | No AMO, TSO, DMO or WM people in the file | Scope rows can be assigned by hand in the admin portal. There is no bulk path. Without scope rows these four roles have zero zones. NATIONAL, BRAND and ADMIN get national reach anyway. | Get a supervisor roster, or confirm that people are created by hand. Load it as users plus `user_scope`. Imported AMOs must meet the memo username rule. | import, portal | medium |
| M15 | 305 Astha outlets with sub-channel GT, and 57 other mismatched pairs | The admin pairing check rejects about 362 rows. The 1,201 Astha outlets with '--' load with no tier. | List the 362 for the owner and import them with a NULL tier. Keep the check for new edits. | import, backend | medium |
| M16 | 'Semi Urban' (105,535 outlets), plus 144,264 empty | `retailers_to_sql.py` already maps this to 'SemiUrban' (D-258). The admin import API has no `geo_class` member and refuses the key. | Add an optional `geo_class` member to the API import, with the same mapping. | contract, backend, import | medium |
| M17 | The tier is an outlet property | The programme `tier_code` is free text with no link to `outlet.sub_channel_id`. | Now: record in DECISIONS.md that the outlet sub-channel is the source of the tier. When loyalty returns: derive the tier from the sub-channel and flag mismatches for Astha programmes only. | backend, TSO app | medium |
| M18 | 92,961 outlets on routes with no rep and no status | An empty status becomes 'active', and coverage counts include these outlets. Zone close can create unplanned route_day rows. No view separates out unassigned routes. | Keep these routes without an assignment. Set outlet status per the owner's ruling. Add a 'no rep' report and exclude these outlets from coverage. | import, backend, portal | low |
| M19 | Mon/Thu/Fri on 43 outlets and Sat/Tue/Wed on 37 | Both are accepted as 3f. A Friday visit is silently dropped. No overlap check exists. | Flag routes whose mask contains a weekend day and list them for the owner. Optionally add an admin warning. | import, portal | low |
| M20 | Created At on all rows, Updated At on 280,719 (text) | The import sets both dates to now(), so every outlet looks changed in deltas. | Parse the dates in the loader. Do not route this through the portal API. | import | low |
| M21 | Channel is a first-class dimension | The 'channel' scope node passes the type check, then is always refused. There is no `app.channel` table. | Choose one: resolve a channel node to its sub-channels, or drop the node type in a forward migration. Log the choice. Offers and loyalty are deferred. | backend, portal | low |
| M22 | Six cluster types, 99.7% 'Transit Hub' | Free text with no code list. The loader keeps the first row's type for each cluster without warning. Test and mock data use other values. | Add a `cluster_type` code list with Bangla labels. Count conflicts within a cluster. Align test and mock data. | schema, portal, import | low |

## 3. Dropped after verification

- **Route kind on import (RV-8).** `route_class` already exists separately from `kind`. This is now an owner mapping question (Q15), not a schema gap.
- **"Nothing maps the file's columns."** Corrected: `retailers_to_sql.py` covers wing through outlet. The gap is now M09.
- **"'Semi Urban' fails 105,535 rows."** Corrected: the existing ETL maps it. Only the API import gap remains (M16).
- **"1,201 Astha/'--' rows are rejected."** Corrected: they load with a NULL tier. Only about 362 rows fail (M15).
- **"Friday counts as planned for daily routes."** Corrected: it does not by default, only on a make-up Friday (M02).
- **"GEO_SAME_POINT ignores imported pins."** Not verified, so it is not part of M01.
- **"Dashboards list unassigned routes."** Overstated: they mostly omit them (M18).
- **"AMO is hard-wired to zone and TSO to territory in ReachCalculator."** Corrected: reach comes purely from scope rows (M14).

## 4. Work packages

1. **Server schema and code lists:** M02 (optional redefinition), M03, M04, M07, M11, M13, M21, M22.
2. **Contract and bundle:** M03, M04, M06, M16, M17.
3. **SR app:** M01 (location basis), M02 and M03 (labels and planned state), M04. Also confirm that the route picker handles 3 or 4 routes planned on the same day, with a test.
4. **AMO/TSO apps and portal:** M01 (TSO periphery and AMO), M08 (portal editors), M09 (zone house field), M14, M17, M18 (no-rep report), M21.
5. **Import mapping and data-quality rules:** M05, M06, M08, M09, M10, M12, M15, M16, M19, M20, M22. Counts this lane can take from the workbook without exposing row values:
   - patterns per route
   - reps whose routes span more than one zone
   - maximum clusters per zone and per territory
   - longest names and number of blank owners
   - whether the shortest rep id is 3 characters

## 5. Questions for the owner

Each question shows the default that applies if it is not answered.

1. Do all wings sell Saturday to Thursday with Friday off? Default: yes, Daily is mask 127 with Friday as the weekend.
2. Is a route whose outlets have no plan "not visited", or should it get a pattern? Default: imported without an assignment, so it is not planned.
3. When outlets on one route have different patterns, which pattern wins? Default: the most common pattern, and the routes are listed for you.
4. Are Mon/Thu/Fri and Sat/Tue/Wed real patterns or entry errors? Default: loaded as in the file and flagged.
5. On each of the 7 two-rep routes, which rep is the primary? Default: the rep on more of the route's rows is primary, and the other is an open-ended cover.
6. For the 4 hierarchy exceptions, which parent is correct? Default: the parent with the most outlets or routes.
7. Are the 92,961 outlets on unassigned routes part of the live outlet universe? Default: loaded as 'archived' until a rep is assigned.
8. Are the master coordinates trusted enough to skip first-visit correction? Default: yes, they are marked confirmed.
9. For Astha/GT (305 rows) and the 57 mismatched pairs, which column is the truth? Default: no tier, and the rows are listed for you.
10. How many Titanium outlets are there, and what is the tier order? Default: Titanium is added last and Silver is set inactive.
11. Will you supply an AMO/TSO/DMO/WM roster, or will people be created in the portal? Default: created by hand in the portal.
12. Can a Sales_Rep_Id have only one digit? Default: the username rule is relaxed if the workbook contains one.
13. What date format do Created At and Updated At use? Default: the import time is kept for dates that cannot be parsed.
14. Is there an Apsis rule for numbering new outlet codes? Default: keep `<ZONE>-<NNN>`.
15. How can dummy BR, institutional or AMO routes be identified in the file? Default: every route loads as kind 'sr' with class 'normal'.
16. Does 'Transit Hub' mean "unset"? Default: kept as given.

## 6. Not done on purpose

- The workbook is not loaded into the test account. It holds fleet-sized personal data, and the test account stays at pilot size (`docs/28`). Data migration stays out of scope until you say otherwise (standing rule 3).
- No row value from the workbook is in the repository. Only the sanitised profile and counts are.
