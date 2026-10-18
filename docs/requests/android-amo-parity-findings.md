# Request: AMO parity findings that need other owners (android-amo, 2026-10-18)

From the Opus parity audit of the AMO screens against docs/07, the AMO manual and delta-amo. The AMO lane fixed what is in feature-amo, app-amo and feature-home strings; these need someone else.

## Backend / contract
1. **Live Dashboard geo counts.** The manual shows "জিও ভ্যালিডেশন", "ছবি ভ্যালিডেশন" and "মোট আউটলেট ভ্রমণ". `DashboardKpis` has no `visits`, `geo_valid_visits` or `force_sale_visits` counts (only percentages). Add them; `active_memo_count` is already sent and is now the natural "মোট বিক্রয়".
2. **Territory or zone name in the supervisor bundle section**, for the Home subtitle "<Savar Bazar>AMO, <date>" (F-AMO-001).
3. **`route-std` (per-category STD) opened to the AMO role** (D-AMO-REPORT-01), so the STD Memo Report shows the manual's category columns.
4. **Verification payload members for route and cluster** (`outlet_request_verification`): the manual lets the AMO change them; the contract has no member for it (D-AMO-VERIFY-01).
5. **Rubric `section` and `guidance` fields** (`RubricDef`), for the Joint Call headings and "(যা দেখতে হবে: ...)" lines.
6. **A flag key for the Report tile and Survey** (F-AMO-040, D-348): the SS variant keeps Report today.

## feature-outlet (SR lane owns)
7. Per-kind confirm text for Close ("এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে") and Info ("এই পরিবর্তন সংরক্ষণ করা হবে") with the title "আপনি কি নিশ্চিত?", and a success dialog after save.
8. A `photoNote` / `saveLabel` parameter on `OutletRequestScreen`: the AMO sees "AMO যাচাই করবেন" today, which is wrong for an AMO's own request; and the capture button "GEO এবং ছবি ধারণ করুন".
9. SR sale path: `offlineBanner = true` always shows the "সংযোগ নেই" banner in the memo detail's sales history, even online (`SrSaleHosts.kt`).

## android-core
10. `localizedNumber` groups in lakh style (12,34,567); F-SYS-051 and the AMO manual use Western grouping (2,082,820), as `Formats.money` does.

11. **Own-request form uuid across a swipe-kill (feature-outlet, SR lane).** `OutletRequest` keeps its record uuid with `remember`; after a swipe-kill a reopened form queues a second request. The AMO forms use `FormDrafts` (app-amo, a uuid per target kept until the save succeeds): the same few lines fit the SR host.
12. **Sales Submit rows grouped as the manual's 9 (feature-dayclose owner, display only).** The counts table lists one row per record type (`CountsCard`, `SalesSubmitScreen.kt`), the manual lists Outlet, Sale, Stock, QC, Promotion, Distribution & OOS performance, Price compliance, Joint call, Survey. The same `CountRow` list feeds the submit gate and `writer.queue(date, gate, counts)`, i.e. the day_submit payload the server compares, so the grouping MUST be display-only (an optional `rowGroup: (recordType) -> groupKey` on the screen that sums device and server per group, server unknown if any member unknown; the gate and the payload keep the per-type rows). The AMO side only needs the group map (visit, memo, stock_movement, qc_line, memo_discount, distribution_check + distribution_check_line, price_compliance_check, call_assessment + call_assessment_answer, survey_response); SR keeps its own rows. Not done in the AMO lane: shared screen, money/sync-critical payload next to it.
