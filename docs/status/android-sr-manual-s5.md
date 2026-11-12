# android-sr-manual-s5 (lane S5 = part B lane B1): SRM-B-04 to SRM-B-30

Branch `lane/android-sr-manual-s5` from INT `claude/wonderful-thompson-k6ejnf`. Train 1: everything that needs no server change. Records: D-SR-MAN-S5-01. All rows are DEVICE-UNVERIFIED (host tests only; no device walk).

| row | state | what was done / what is needed |
|---|---|---|
| SRM-B-01..03 | deferred | DRP / OFFER discount parts stay deferred (docs/27); nothing built, no tile or button |
| SRM-B-04 | done (was MATCH) | hook kept, nothing to do |
| SRM-B-05 | done | KV/AV step: gradient title band "KV"/"AV", item centred, full-width gradient "× বন্ধ করুন" at the bottom (`CallContentHost.kt` `ContentBand`) |
| SRM-B-06 | done | survey: "সার্ভে" band, red survey title (`titleBn/titleEn`), info card, numbered questions `1*`/`1.1*` (`SurveyNumbering`, tested), "উত্তরঃ" dropdown for yes/no, "সাবমিট →" pinned at the bottom |
| SRM-B-07 | done (was MATCH) | skip-the-survey button kept, logged D-SR-MAN-S5-01 (3) |
| SRM-B-08 | needs server + web | survey report / dw view / report key `posm-survey`: SV and web lane; not an Android row |
| SRM-B-09 | done, dots owner question | memo list rows use the picker label "name (code-phone-cluster)" and the ৳ amount; status dots not drawn (question 1) |
| SRM-B-10 | done | outlet selector (dropdown with the red "বাকি" tag) and the A-Z letter bar on `MemoDetailView` (opt-in `switcher`, `letterFilter`) |
| SRM-B-11 | done | SR `MemoHost` passes the outlet label to the detail header |
| SRM-B-12 | done | "মোট <category>" rows carry the quantity in field units (`subtotalQuantities`); no cross-category total |
| SRM-B-13 | done (CONFLICT-WITH-RULE) | rule 5 stands; nothing changed |
| SRM-B-14 | done | banner on the detail with the printer badge slot, Print and Edit side by side. WORDING: new key `memo_banner_manual` ("এছাড়াও আপনি ..."), the old `memo_banner` untouched; S6 may fold it into the glossary pass |
| SRM-B-15 | done, code list needs SV | three manual reasons; wire codes `wrong_sku_qty`, `promotion_issue` need adding to the `edit_reason` code list by a forward migration (SV) |
| SRM-B-16 | done | reason step is a modal (dropdown, বাতিল amber, জমা দিন gradient, off until a reason is chosen); জমা দিন starts `beginEdit` |
| SRM-B-17 | done | QC per outlet and business date (`EditGate.qcEnteredForOutlet`, tested); AMO has the same glitch, not touched |
| SRM-B-18 | done | edit sale shows the retailer pill and the outlet/route/cluster lines. The EDIT frame printer badge is in `SrApp.kt` (owned by another lane's shell): NOT changed, one-line request below |
| SRM-B-19 | done | SR due box is the dashed one, sentence left, green "পরিশোধিত করুন" right |
| SRM-B-20 | done | `MemoUiState.paidDone` + modal with green tick, text, ওকে; only after a stored collection |
| SRM-B-21 | done (was MATCH) | nothing |
| SRM-B-22 | done | `SummaryHost` passes `categoryLabel` (সিগারেট, লাইটার, ম্যাচ ...) |
| SRM-B-23, 24 | done (were MATCH) | nothing |
| SRM-B-25 | device side done; server column needs contract 1.14.0 | business table + device figures built; see "Needed from SV" |
| SRM-B-26 | done (was MATCH) | nothing |
| SRM-B-27 | done | `vendorDialogs = true` from `SubmitHost` (dues modal Yes/No with warning triangle) |
| SRM-B-28 | owner question, KEPT | hold sheet stays; one-line change if the owner drops it (D-SR-MAN-S5-01 (2)) |
| SRM-B-29 | done | success modal over the table, table and check-out hint stay after ওকে |
| SRM-B-30 | needs server | late-row flag on `committed_at` after `sales_submitted_at`; phone side already refuses (unchanged) |

## Needed from SV (contract 1.14.0) for SRM-B-25
Per route-day in the submit digest (the `server_totals` / `SubmitDigest` read the Sales Submit screen already uses), additive and optional:
- `outlets_visited` (int, distinct outlets with a `visit` record that date)
- `sold` / `stock_issued` / `qc`: lists of `{sku_id, qty_base}` PER SKU (the lead's ruling: per category cannot be turned back into packets when a category mixes pack factors; live memos only for `sold`: a superseded memo is not counted; `stock_issued` = issue + adjustment `stock_movement` qty; `qc` = sum of `qc_line.qty_base`); base units only, the phone converts to field units
- `promotion_mtk` (integer milli-taka text/int as the contract's money type: sum of live memos' discount lines, zero while the programmes are deferred)
Then the lane wires `ServerBusiness` (`BusinessTotals.kt`) in its train 2; until then the column is blank and each row says "Waiting".

## Needed from other lanes
- SrApp.kt owner: add `printer = day.printerManager` to the EDIT frame (SRM-B-18).
- S6: fold `memo_banner_manual` / `sr_survey_answer` wording into the glossary pass if the owner's words differ.

## Fix round after the Opus pass on 997b72a0d (D-SR-MAN-S5-02)
Fixed: AMO category row regression (default path test), survey Latin numbering over supported questions, whole-category totals under a letter filter, selector/list due after Mark Paid, base unit word on leftovers, "other" line for unknown SKUs, record-count check kept visible until the server figures exist, string resources for KV/AV/×/▾, stale test renamed, tests through `RoomSaleReads.businessTotals` and `SrSaleKit.prepareEdit` (two visits, one outlet). Declared: duesWarning card kept beside the dues modal (B-27); list rows keep "মেমো <no> · <time>" (B-09); the A-Z bar filters the open memo's SKUs only (B-10); device business figures span all routes on the phone while the server's are per route-day (open, train 2). Server digest will be per SKU `{sku_id, qty_base}`; `ServerBusiness` is planned for that in train 2.
