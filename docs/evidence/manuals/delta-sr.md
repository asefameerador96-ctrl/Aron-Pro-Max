# Delta: SR (Sales Representative) App User Manual vs the Aron spec and plan

Date: 2026-10-04. Author: manual-delta pass for the SR manual. Output of the "find everything the spec does not cover" task.

**Inputs read in full:** `scratchpad/manuals/manual-sr.md` (the 1,094-line consolidated inventory: 60 screens, 33 flows, 150 rules, 99 messages, 39 entities, 51 unclear items); `docs/06, 03, 04, 05, 10, 13`; `db/schema.sql`; plan drafts `plan/lens-features.md` (275 features, 67 gaps) and `plan/lens-data.md` (schema v2, M-01..M-45, DQ-01..DQ-34). Also read for context: `docs/09`, `docs/22`, `plan/seed-findings.md`; searched (not read in full) `plan/lens-config.md`, `lens-sync.md`, `lens-quality.md`, `lens-security.md`, and the AMO/TSO/Web manual inventories.
**Not found:** `docs/15-feature-inventory.md` and `docs/16-data-platform.md` do not exist in the repo. "Plan" below therefore means the `plan/lens-*.md` drafts, which are not yet merged into `docs/`. An item covered only by a lens draft is counted COVERED but is still not in the spec of record.
**PDF spot-checks** against `0965d940-SR_App_User_Manual.pdf` (pages 10-11, 23-25, 34-35, 37-38, 45-47, 62-63, 72-73): the inventory is accurate except for one digit misread (tornado fan cost) and one unexplained icon, both resolved in section 6. Log in Appendix B.

**Verdicts.** COVERED = spec or plan states it adequately. PARTIAL = mentioned, but a field, rule, state or text is missing. MISSING = absent. CONTRADICTS = spec or plan says otherwise. Messages are PARTIAL when the trigger exists but the verbatim text is not in the spec (all of these roll into G-man-sr-30).

---

## 0. Headline

1. **One blocker, 15 majors, 17 minors: 33 gaps** (table in section 1). The blocker is the memo total model: the manual deducts a slide (DRP) value and a QC settlement amount from the memo total (`সর্বমোট = মোট - মোট QC`; slide 80.00 off 360.50), and neither the schema nor the plan has a column, rule or CHECK for them (G-man-sr-02).
2. **The plan's unit assumption is contradicted.** The SR enters and sees quantity in **sticks** (`শলাকার পরিমাণ`), with a read-only pack badge; lens-data M-02 assumed packs. The manual closes Q8 for cigarettes and bidi (G-man-sr-01).
3. **Three whole SR screens are absent from spec and plan:** SKU Target and Achievement with ADS/TADS/PADS/RADS (G-15), Sale History (G-12), and the outlet Points screen with expiring points and expiry date (G-13). Slide/DRP is specified only as a table name (G-04), and QC is collapsed from six fault types to two numbers (G-03).
4. **Four places where the manual and the spec say different things:** outlet forms select Cluster, not Route (G-22); dues are settled for the whole memo, not partially (G-23); the OTP is 4 digits and is re-asked after a new app version (G-17); photo capture updates the outlet location immediately (G-10).
5. **Two things the manual cannot tell us** and the live app or screenshots must: whether an outlet photo is taken on every call (G-09, a 50 GB/day question), and what the three unlabelled indicators on each sale card mean (G-05).
6. Coverage by item: 381 items, 161 COVERED, 169 PARTIAL (79 of them are messages whose text is not in the spec), 34 MISSING, 17 CONTRADICTS (section 2).
7. The manual shows **no offline state anywhere**, no validation error, no sync progress. Offline behaviour is a rebuild requirement taken from CLAUDE.md, not from this manual (section 5).

---

## 1. Gap table (every PARTIAL, MISSING and CONTRADICTS item, grouped into 33 gaps)

Every non-COVERED item in Appendix A points to one or more of these gap ids.

| Gap | Manual screen + pages | What the manual says | What the spec/plan says | Kind | Sev | Concrete fix | Proposed home |
|---|---|---|---|---|---|---|---|
| **G-man-sr-01** Sale and stock quantity is entered in sticks (not packs); pack badge and stepper | SR-S-32 p34; SR-S-17 p21; SR-S-42 p47 | p34: "SKU অনুযায়ী দোকানদারের চাহিদামত শলাকার পরিমাণ উল্লেখ করুন" (enter sticks per shopkeeper demand). MaxR-10S qty ১০ = 80.00 (8.00 per stick), MaxR-20S ২০ = 160.00, AB-12s ১২ = 9.00. Stock p21: issue ৬,৫০০ with purple badge ৬৫০ (= sticks / 10-stick pack). Sale and edit cards (p34, p47) carry badge ১ on 10/20/25-stick packs. | docs/13 Q8 open. docs/22 P-04: volume is sticks. lens-data M-02/section 4.6 ASSUMPTION: "SRs enter packs", with qty_entered, unit_entered, pack_factor. lens-features F-SR-023 "Unit (pack vs stick) Q8". | contradiction | major | Default `cfg.sale.qty_entry_unit = stick` for cigarette and bidi. Entry and display in sticks, `qty_base` = sticks, price per stick (MaxR 8.00). Pack badge is read-only = qty_base / pack_size. Stepper step = pack size (inferred: every sample qty is exactly 1 pack; confirm whether loose sticks may be typed). Lighter and match units stay open (Aster ১ = 12.50 per piece; SL ১২ = 19.00; FB ৬০০ = 1,800). Replace the M-02 assumption; close Q8 for cigarette and bidi. | docs/13 Q8; docs/03 memo_line; lens-data M-02/M-07/4.6; cfg.sale.qty_entry_unit |
| **G-man-sr-02** Memo total model lacks slide deduction, QC settlement amount and per-SKU max-QC cap | SR-S-33 p34, p43; SR-S-35 p37; SR-S-36 p37; SR-S-39 p44 | p34: "সর্বমোট ৬৫ ২৮০.৫০" = মোট ৩৬০.৫০ minus slide ৮০.০০ (quantity total unchanged). p44: "মোট ২৯১.৫০ / মোট QC - ০.০০ / সর্বমোট ২৯১.৫০". p37 QC summary: "মোট QC নিষ্পত্তি ১০", "সেটেলমেন্ট পরিমান ৮০.০০৳" (10 sticks x 8.00). QC entry header: "সর্বোচ্চ QC: ৫৯৪.৫০৳ / QC হয়ে গেছে: ০.০০৳ / বাকি আছে: ৫৯৪.৫০৳" (594.50 is not explained by 10 x 8.00). Product QC is also offered on the zero-sale review (p43). | docs/03: memo = gross, discount, net; qc_entry = fault quantities only. lens-data M-06 CHECK net = gross - discount; DQ-18 caps QC by qty sold in this visit. G-feat-05 says QC has "no credit note or stock effect". | missing-field | blocker | Add `memo.slide_deduction_mtk` and `memo.qc_deduction_mtk`; net = gross - discount - slide - qc (amend the CHECK, the printed lines "স্লাইড", "মোট QC", "সর্বমোট", and DQ-14). Add `qc_entry.settlement_mtk` (= defect sticks x price at capture; fixture 10 x 8.00 = 80.00). Add `qc_entry.max_qc_mtk` or a derived rule (basis unknown: obtain from live app; do not reuse DQ-18 until known). Define zero-sale + QC (negative grand total?) and outlet-level edit lock after QC. Update G-feat-05 (QC is a monetary credit on the memo). | docs/03 memo, qc_entry; docs/06 4d; lens-data M-06/M-08/DQ-14/DQ-18; G-feat-05 |
| **G-man-sr-03** QC has six fault types in two groups, plus picker, entry, summary and submit screens | SR-S-35 p36-38; SR-S-36 p37; SR-F-13 | Group "উৎপাদন ত্রুটি": "ড্যামেজড ও ক্রাশড – প্যাক / আউটার CBC", "আউটার / প্যাক / স্টিক কম থাকা", "অন্যান্য ত্রুটি". Group "পরিবহন ত্রুটি": "মেয়াদোত্তীর্ণ স্টক (৪ মাস+)", "স্টক ড্যামেজড – রুট সার্ভিস কালীন", "স্বাদ সংক্রান্ত সমস্যা". Qty in sticks per row. Buttons "সংরক্ষণ / বাতিল / ডিলিট". SKU carousel, summary type codes "MFC Fault,MKT Fault", "QC জমা দিন →", confirm "ইনফর্মেশন / আপনি কি QC জমা দিতে চান?", empty "QC করার জন্য কোন পণ্য নেই". | docs/03 qc_entry: production_fault_qty + transport_fault_qty only. lens-config cfg.qc.fault_kinds = two values. The AMO manual inventory (S-21) documents only the "প্রোডাক্ট QC" button, so SR p36-38 is the only source for the QC screens. | missing-field | major | Add `qc_entry_line(client_uuid, qc_entry_id, fault_type_code, qty_sticks)` and `cfg.qc.fault_types` (6 codes, group MFC/MKT, bn/en labels, active, sort). Keep the two group totals as derived columns. Parameterise the expired-stock threshold (4 months; PDF glyph is ৪, which this font draws like Latin 8). Specify "বাতিল" = discard edits, "ডিলিট" = remove this SKU's QC. Build the SKU-picker carousel, summary table and submit confirm exactly as shown. | docs/03 qc_entry; docs/06 4d; lens-data M-08; lens-config cfg.qc.*; docs/09 admin QC page |
| **G-man-sr-04** Slide/DRP: concrete offer rule, offer dialog, manual-quantity dialog with shortcut steps, memo slide section | SR-S-25 p27/p31/p42; SR-S-26..28 p28-30; SR-S-33 p34 | Offer dialog "ছাড়": "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s", valid Nov 25 2025 to Dec 30 2025. Rows with red "OFFER" ribbon only where an offer is active; stepper per SKU "খালি প্যাকেট"; tap value opens dialog with chips +১/৫/১০/২০/৫০ and -১/৫/১০/২০/৫০ then "সংরক্ষণ"; "জমা দিন" saves. Review shows a "স্লাইড" block (MaxR-10S ১০, ৮০.০ in red) deducted from the total. Footer button is "Collect DRP Discount" (p27/31/42) but "স্লাইড সংগ্রহ" on p34. Cart total ২৮০.৫০ equals the post-slide total. No auto-applied discount line appears on any Review or Memo screenshot. | docs/06 4c "DRP empty-pack/slide collection with offer". docs/10: DRP "earns a discount". G-feat-13 (blocker): rule schema undefined. lens-data M-10 drp_collection(kind, sku, qty, discount); cfg.drp.kinds. | missing-rule | major | Seed this as the first real offer and a test fixture: threshold 100 sticks of empty MaxR packs -> reward 1 pack MaxR-10S (10 sticks, value 10 x 8.00 = 80.00) shown as a deduction, quantity total unchanged. Offer master needs text bn/en, valid_from/valid_to (shown to the SR), qualifying SKU/brand, ratio, reward SKU. Slide screen lists only SKUs with an active offer; Offer detail dialog; manual dialog with configurable shortcut steps. Confirm: unit of "খালি প্যাকেট" (packs vs sticks), behaviour outside the validity window, whether cart total is net of slide, one label for the two entry points, and where auto-offer discounts render (spec says offers auto-apply; none visible in this manual). | docs/10 Promotions; docs/06 4c; G-feat-13; lens-data M-10/M-20; cfg.promo.*, cfg.drp.shortcut_steps |
| **G-man-sr-05** Three unlabelled indicators and a pack badge on every sale SKU card | SR-S-32 p34; SR-S-42 p47 | Per card: cart icon "১০০০০" / "১১০০০" / "৩৬০০" (p34), "২৫০০" / "৫০০০" / "৮৭৫০" (p47); percent icon "০%" (p34) vs "১০০%" (p47); box icon "০". Purple badge "১" on the pack image. | Absent. docs/06 4d says only "enter SKU quantities". | underspecified | major | Identify from live-app screenshots. Hypotheses to test: cart = available stock in sticks, percent = offer/discount or stock %, box = free or promo quantity. Until known, reserve three read-only slots fed by local data and keep the badge = packs (G-man-sr-01). | docs/06 4d; F-SR-023; screenshots (sponsor) |
| **G-man-sr-06** Sale commit sequence: Print is the commit; print is optional; three dialogs | SR-S-33 p38-39; SR-S-37 p38-39; SR-F-08 | Tapping "প্রিন্ট" on Review: dialog 1 "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে" -> dialog 2 "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" -> dialog 3 "সফল / বিক্রয় সফল ভাবে জমা হয়েছে". Info "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।". Printer state icon in the Review header. | docs/06 4d lists "...-> Product QC -> print memo" as the last step. G-feat-43 covers printer failure only. | missing-rule | minor | Specify: "হ্যাঁ" at dialog 1 commits the sale to the local DB (immutable, sync_state pending) before and regardless of printing. "না" at dialog 2 leaves `printed_at` null (reprint from Memo). "না" at dialog 1 stays on Review. Success dialog follows. Add to the Day flow and to the T-1 offline test script. | docs/06 4d; F-SR-028; cfg.sale.require_printer_before_sale |
| **G-man-sr-07** Stock screen: Issue vs Stock columns, category totals panel, packet badge, save semantics | SR-S-17 p19-21; SR-F-07 | Columns "এসকেইউ / ইস্যু / স্টক"; per-SKU "-" "+" or typed issue (default ০) with packet badge; bottom panel "ক্যাটাগরি / মোট ইস্যু / স্টক" for সিগারেট, বিড়ি, লাইটার, ম্যাচ; after "সংরক্ষণ" the Stock column equals Issue and inputs stay filled; "প্রিন্ট" prints the stock memo; printer icon. | docs/06 step 3 "Stock load - issue by SKU; print a stock memo". F-SR-014/015/050; second load per day G-feat-02. | underspecified | minor | Define Issue (today's lift; replace or add on re-save) and Stock (issue - sold - returned, computed locally); four-category totals panel; step size and typing; packet badge; Print enabled only after Save. Close with G-feat-02 (multiple loads). | docs/06 step 3; F-SR-014/015/050; lens-data M-11 |
| **G-man-sr-08** Start-call confirmation before AV/KV, survey and sale | SR-S-25 p27, p31, p42, p43 | After outlet selection (and after the force-sale photo): "আপনি কি কল শুরু করতে চান?" with "হ্যাঁ" / "না"; footer shows cart "০.০০", "Collect DRP Discount", "এগিয়ে যান→". Effect of "না" not stated. | docs/06 4a: selecting an outlet "opens visit"; docs/06 4b "in range -> start call"; lens-data visit.started_at set at open. | missing-rule | major | Add the explicit step. Decide whether `visit` (and visited/CPR/geo counts) is created at outlet select or at "হ্যাঁ"; store `call_started_at` separately from `opened_at`; define "না" (back to list, not counted as visited, logged). AV/KV, survey and sale begin only after "হ্যাঁ". Optional `cfg.sale.call_start_prompt` for parity. | docs/06 4a/4b; docs/04; lens-data M-05; F-SR-017 |
| **G-man-sr-09** Is an outlet photo required on every call, or only on Force Sale? | SR-S-24 p27, p31 vs p25, p41, p42 | p25/p41/p42 show the camera only after the Force Sale reason. p27 and p31 (slides titled "DRP" and "বিক্রয় প্রক্রিয়া") show the same viewfinder with no force-sale step. Callout: "ছবি উঠানোর সাথেই ... লোকেশনের তথ্য আপডেট হয়ে যাবে।" | docs/05: photo only on out-of-range Force Sale. docs/04 photo budget 100-200 KB. docs/22 P-01 peak 3.5 lakh calls/day. | underspecified | major | Confirm from the live app. If every call needs a photo the data budget changes by roughly 50 GB/day at 150 KB (3.5 lakh x 150 KB), and the Wi-Fi-first media queue becomes load-bearing. Default to the spec (Force Sale only) behind `cfg.sale.outlet_photo_every_call`. | docs/05; docs/04; lens-config cfg.sale.force_requires_photo |
| **G-man-sr-10** Photo capture updates the outlet location immediately (spec routes it through verification) | SR-S-24 p27, p31, p42; SR-F-09 | "ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে।" (location info updates as soon as the photo is taken; no approval step is shown). | docs/05: the photo "updates the outlet's location ... routed through the normal outlet-change/verification flow". Not on docs/13's "deliberately changed" list. cfg.geo.first_capture_sets_location, cfg.geo.outlet_location_change_approval exist in the plan. | contradiction | major | Log it as a deliberate change in docs/13 and DECISIONS.md. Define a device-side provisional location: after a force-sale photo, that SR's later in-range checks use the new fix (flagged provisional) until AMO/web approval; otherwise the SR must force-sell at that shop every day. Server keeps authoritative location and history; retain immediate update for outlets with a missing or placeholder location (docs/22 P-09). | docs/05; docs/13; lens-data M-16; cfg.geo.* |
| **G-man-sr-11** Permission gating: location mandatory and Precise; device location ON; Bluetooth Nearby; camera/audio | SR-S-03 p4; SR-S-16 p19; SR-S-19 p25/p41; SR-S-23 p26 | "অ্যাপ্লিকেশনটি ব্যবহার করার জন্য অবশ্যই ইউজারকে লোকেশন পারমিশন দিতে হবে।" (choose "While using the app"; Precise is the default). "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের লোকেশন অন রাখতে হবে।" Nearby devices: "অন্যথাই প্রিন্টার Connect হবে না।" Camera then a separate Audio prompt. | docs/06 lists permissions only. docs/05 single balanced-power fix. lens-sync covers BLUETOOTH_CONNECT/SCAN. Q15 microphone open. | underspecified | minor | Write the gating matrix: location denied -> Sale and Attendance blocked with a Bangla rationale and Settings deep link; require Precise (Approximate cannot meet a 100 m geofence) via `cfg.geo.require_precise`; handle device Location services OFF and "Only this time"; Bluetooth denied -> printing off, selling still allowed. Do not request microphone: the manual's Audio prompt is a camera-plugin side effect (Q15). | docs/06 Setup; F-SYS-023; docs/13 Q15 |
| **G-man-sr-12** SR Sale History by date (online-only in the current app) | SR-S-19/20 p23; SR-S-32 p34; SR-F-14 | Button "Sale History" (callout calls it "পূর্বের সেল ডাটা দেখুন") on the selected-outlet screen and a green "পূর্বের সেল ডাটা দেখুন" on Sale entry. Screen "সেল ডাটা": date field, heading date, table "এসকেইউ / পরিমাণ / মূল্য", footer "মোট". Note "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে।" Works for any date and when out of range. | No SR feature. plan F-AMO-013 covers AMO only; F-API-025 lists SR as a caller with no SR feature row. docs/06 silent. | missing-feature | major | Add F-SR-051 "Outlet sale history": local window `cfg.app.local_history_days`, online fallback `GET /memos?outlet=&date=` for older dates, offline banner for dates outside the window. Show per-SKU qty, value and total. Available before and after geo-check. Note the manual's own total mismatch (see section 6). | docs/06 Day flow 4; F-SR-051; F-API-025 |
| **G-man-sr-13** Outlet Points screen with expiring points and expiry date; points shown up to previous day | SR-S-21 p24; SR-S-51 p62; SR-F-15 | "Points" button opens "পয়েন্ট": "Manage retailer gift requisition and redemption." + "রিডিম্পশন"; card "Diamond League (April)" "110 Pts"; "Expiring Points: 110 Pts"; "Expiry Date: 2026-05-07". Callout: "বিগত দিন পর্যন্ত আউটলেট এর অর্জিত ... Point". | docs/06/10 describe redemption only. No expiry concept anywhere (loyalty_ledger has points_delta only; lens-data mentions source_type 'expiry' in a comment). | missing-field | major | Add F-SR-052 "Outlet points view": league label, balance, expiring points, expiry date, as-of date (previous day, from bundle). Add an expiry rule to Diamond League setup (April league expires 2026-05-07, about month end + 7 days; confirm), nightly expiry ledger rows, redemption only from unexpired points: `program_period.redeem_until`, `cfg.loyalty.expiry_days`. Clarify what "gift requisition" means. | docs/10 Diamond League; docs/06 Loyalty; lens-data M-23; F-SR-043 |
| **G-man-sr-14** POSM photo earns 50 points (first known Diamond League earn rule) | SR-S-31 p33 | "1.1* POSM এর ছবি সাবমিট করলে আপনি ৫০ পয়েন্ট অর্জন করবেন।" | docs/10: outlets earn monthly points, no rule. G-feat-20 (blocker): earning rules absent. lens-config cfg.loyalty.earning_rules empty. | missing-rule | major | Seed rule: survey Q1.1 photo submitted -> +50 points to the outlet (`source_type='survey_response'`, `source_id`=response client_uuid so replay never double-credits). Add `survey_question.points_reward`. Confirm: posts at submit or after photo upload; "না" earns nothing; monthly cap. | docs/10; lens-data M-09/M-23; cfg.loyalty.earning_rules |
| **G-man-sr-15** SKU-wise Target and Achievement screen with ADS, TADS, PADS, RADS | SR-S-10/11 p10-11; SR-F-23 | Target card "টার্গেট ও অ্যাচিভমেন্ট (SKU List)" with "এস টি ডি" bar and "বিস্তারিত" opens "টার্গেট ও অ্যাচিভমেন্ট": per SKU টার্গেট / অ্যাচিভ / বাকি / অর্জন and ADS / TADS / PADS / RADS. Maxim T500: TADS 36, RADS 45; Avon and ARIS T900: TADS 68, RADS 82 (all ADS and PADS 0). | docs/06 Home mentions the STD progress bar only. Neither docs nor plan define the four ADS metrics or this screen. | missing-feature | major | Add F-SR-053 and the definitions. Evidence from the PDF: RADS = remaining / remaining selling days (500/11 = 45.5 -> 45; 900/11 = 81.8 -> 82). ADS = achieved / elapsed days (0). TADS = target / selling days fits 500 -> 36 (14 days) but not 900 -> 68 (13.2 days): confirm. PADS undefined (projected or previous). Use `dim_date` working days and `cfg.kpi.tilldate_basis`. Compute locally from bundle target + local sales; do not cap % (card shows ২৭১৯%). | docs/06 Home; docs/10 KPIs; F-SR-010; lens-data 4.5 |
| **G-man-sr-16** AV, KV and survey are assigned per outlet and shown in a fixed order | SR-S-29/30 p32; SR-S-31 p33 | "নির্দিষ্ট আউটলেটের জন্য কোন AV থাকলে সেই AV দেখতে পারবেন" then "KV থাকলে KV ... “বন্ধ করুন”". Survey "নির্দিষ্ট আউটলেটের জন্য"; Q1 হ্যাঁ/না is required (*); Q1.1 photo only if হ্যাঁ; confirm "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?". AV full-screen (landscape) with "x"; KV image with "বন্ধ করুন". | docs/06 4c lists AV/KV and POSM survey. plan content_item.scope jsonb; survey.audience is by role only. G-feat-12. | missing-rule | minor | Per-outlet (or cluster/route/channel) assignment with validity for AV, KV and survey; order AV -> KV -> survey -> sale; conditional question logic; AV is video (pre-download on Wi-Fi, bounded LRU), KV an image; viewed logging; skip rules unknown. | docs/06 4c; lens-data M-09/M-44; cfg.content.*, cfg.survey.* |
| **G-man-sr-17** Device OTP: 4 digits, and re-verification after installing a new app version | SR-S-04 p5-6; SR-F-01 | "Enter the 4-digit OTP provided by your TSO." (four boxes). On-screen: "Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process." Release note "Login time 2FA verification." | docs/06 Setup: a new phone is bound once with a TSO-issued OTP. lens-config cfg.auth.otp_length default 6, otp_ttl_min 30. F-SYS-003. | contradiction | major | Default `cfg.auth.otp_length = 4` for parity. Decide the re-verify-on-new-version rule (current: yes). Recommended default: no re-verify for in-place updates (8,500 TSO calls per release; breaks an offline day), behind `cfg.auth.reverify_on_new_version`; store `device.verified_app_version`. Keep the explanatory text bn/en. Record in docs/13 Q4. | docs/06 Setup; docs/13 Q4; cfg.auth.*; lens-data M-41 |
| **G-man-sr-18** TSO 'SR OTP Panel': scoped list of every SR with a visible OTP and Create Time | SR-S-05 p5-6; SR-F-02 | TSO portal last menu item "SR Device OTP" -> "SR OTP Panel": five multi-selects Wing, Division, Territory, Distribution House, Zone (default "All Selected (1)"), "View"; table "Sr No., Field Force ID, Field Force Name, Username, Zone ID, Zone, Create Time, OTP" (one row per SR; e.g. sr334002, OTP 6818, created 2026-01-20). | docs/09 names the page only. lens F-ADM-022 "pick user -> issue OTP", code_hash, TTL, one active OTP. Web manual WEB-S-44 shows 5 columns (Field Force Name, Username, Zone Code, Zone, OTP) and no route filter. | missing-field | major | Build the list-style panel: scoped list of all SRs with the currently valid OTP, columns incl. Field Force ID (= `app_user.employee_code`, new), Zone ID/code, Create Time. The OTP must be retrievable by the TSO: encrypted at rest or regenerate-on-view with audit, not a one-way hash. Rotate on bind. Reconcile with the web manual's 5-column variant (build difference). Launch-day bulk view. | docs/09 admin; F-ADM-022; lens-data M-41/M-19; web delta |
| **G-man-sr-19** In-app updater: unknown-sources permission, progress UI, forced-update splash, post-update data processing | SR-S-06..09 p7-10; SR-F-03 | "Update Available", "Network Status অনলাইন", "NEW UPDATE", release notes, "Download App & Install", "Downloading 24%", "Please do not close the app while downloading"; auto-redirect to Android "Install unknown apps" (toggle ARON SR ON); "Do you want to update this app?"; forced splash "কিছু নতুন আপডেট পাওয়া গেছে ... আপনাকে অবশ্যই আপডেট করতে হবে!" with "Current version SR App 1.0.1"; then "অ্যাপ আপডেট হচ্ছে / প্রসেসিং(১৪/৮৭)". | docs/06 Setup "in-app update"; F-SYS-020 (min_version, wave, Wi-Fi preferred); lens-sync 8.2 Drift migration preserves pending rows. REQUEST_INSTALL_PACKAGES and the unknown-sources flow appear nowhere (FileProvider in lens-security is scoped to printer and photo flows). | missing-rule | minor | Declare REQUEST_INSTALL_PACKAGES; check `canRequestPackageInstalls()` and deep-link with Bangla instructions; resumable download with percent; SHA-256 verify; show `app_release.notes`; forced screen text; post-update migration progress n/total without deleting pending rows. An 80.40 MB APK is about 4 percent of the 2 GB/day pack: prefer Wi-Fi, allow data with a warning (manual says "keep internet on"). Not a Play Store flow. | docs/06 Setup; F-SYS-020; cfg.release.* |
| **G-man-sr-20** Printer: pair in Android settings first (PIN 0000), connect only from Stock in the current app | SR-S-15..17 p16-20; SR-F-06 | Steps: Bluetooth ON, printer power ON, scan, tap "RPP02N", PIN "0000" (Android hint "Try 0000 or 1234"), then in the app open Stock and tap the printer icon (red slashed = disconnected, green = connected); green banner "প্রিন্টার কানেক্ট করা হয়েছে". The same icon sits on the Review and Memo headers. | docs/06 Day flow 2 "pairing ... connect from Stock screen; show connection state". lens-sync printer section (SPP, permissions). | underspecified | minor | Add the OS pairing step and PIN hint to the field guide; an in-app "Pair printer" shortcut to Bluetooth settings with a bonded-device picker (store MAC); make the printer icon tappable on Stock, Review and Memo; auto-reconnect to the last printer; Bangla banner. `cfg.print.pin_hint`, supported models. | docs/06 Day flow 2; F-SR-013; lens-sync printer section |
| **G-man-sr-21** SR Attendance UX: address + Refresh, press-and-hold sheet, four states | SR-S-12..14 p12-15; SR-F-04/05 | Location card with reverse-geocoded address and Refresh ("বর্তমান লোকেশন পেতে কোনো সমস্যার সম্মুখীন হইলে Refresh বাটনে ক্লিক করুন"). States: "আপনি এখনো চেক ইন করেননি..." / "চেক ইন সম্পন্ন হয়েছে" + "চেক আউট ৫টার পরে সক্রিয় হবে" / red "চেক আউট" / "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" Bottom sheet "চেক ইন করা হচ্ছে" with 12-hour time chip (04:47 PM) and "চাপ দিয়ে ধরে রাখুন". | docs/06 "GPS + time; check-out only from 5 pm". Press-and-hold only in F-AMO-003. lens-data A01: address not stored. | missing-feature | minor | Give SR the AMO attendance UI: address by reverse geocode when online, else coordinates + last address; Refresh = one new fix (cap `cfg.geo.refresh_max`); hold-to-confirm duration `cfg.app.hold_to_confirm_ms` (manual silent); time chip from device clock with server skew check; store a fix at check-out too; "day complete" state and strings. | docs/06 Day flow 1; F-SR-011/012; cfg.day.* |
| **G-man-sr-22** Outlet New/Close/Info forms use Cluster (then outlet), not Route | SR-S-46..49 p53-60; SR-F-24/25/26 | New shop: "ক্লাস্টার নির্বাচন করুন", "দোকানের নাম", "দোকানের মালিকের নাম", "মোবাইল নম্বর", "GEO এবং ছবি ধারণ করুন", "সংরক্ষণ" (no confirm; success "সফল"). Close and Info change: cluster, then "আউটলেট নির্বাচন করুন" with labels such as "Sifat St (Diamond)"; Close shows read-only name/owner/mobile and confirms "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে". Info change confirms "এই পরিবর্তন সংরক্ষণ করা হবে". No route field anywhere. | docs/06 Outlet management and F-SR-037/038/039: "route, shop name, owner, mobile ... route, outlet". The AMO manual's verification form shows route and cluster. | contradiction | major | Replace the route pickers with cluster (clusters of the SR's zone, from the bundle). Derive `route_id` (the SR's route that day; confirm) and store both route_id and cluster_id in `outlet_change_request` and `proposed`. Outlet picker filtered by cluster, label "name (sub-channel)". Validate the 11-digit BD mobile. GEO+photo mandatory on new and info. Keep AMO verification and the pending state (the manual shows none). | docs/06 Outlet management; F-SR-037..039; lens-data M-15 |
| **G-man-sr-23** Memo screen: dues are settled for the whole memo only; outstanding shown in the outlet picker | SR-S-39/40 p44-45; SR-F-19 | "এই বিক্রয়টি বাকিতে করা হয়েছে।" + "পরিশোধিত করুন" -> "আপনি কি নিশ্চিত?" -> "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।"; no amount field; the "বাকি" tag disappears from the outlet. Dropdown row "Tasmin (1618415) : ২৯১.৫০ ৳ বাকি" in red. Partial collection exists only at sale time. | docs/06 step 5 "mark paid (full or partial)"; docs/03 due_collection partial; F-SR-032. | contradiction | minor | Implement the manual flow first (whole-memo, confirm, clears the tag). Keep partial later collection as a labelled enhancement behind `cfg.credit.allow_partial_collection` (default off) or drop it. Show per-outlet outstanding in the memo picker. Define which memo is shown when an outlet has several and whether imported previous-day dues appear. The sales-deposit dues count must use the same source. | docs/06 step 5; F-SR-030/032; lens-data M-12 |
| **G-man-sr-24** Sale edit reasons: one known, two unknown; edit lock is outlet-level | SR-S-41 p47; SR-S-39 p46 | Dialog "দয়া করে বিক্রয় এডিট করার কারন লিখুন" with dropdown value "ভুল SKU নির্বাচিত।"; three options exist; buttons "বাতিল" and "জমা দিন". Edit needs the SR inside the geofence and "QC কৃত কোন আউটলেটে বিক্রয় এডিট করা যাবে না" (any outlet where QC was done). The outlet is read-only on the edit page. | docs/06 "choose 1 of 3 edit reasons" (unnamed). lens-config placeholder codes qty_error, price_error, wrong_outlet. DQ-16 locks per memo. | underspecified | minor | Replace the first placeholder with `wrong_sku` ("ভুল SKU নির্বাচিত।"); capture the other two from screenshots; drop `wrong_outlet` (outlet is read-only in edit). Align DQ-16 to an outlet-level QC lock. | cfg.memo.edit_reasons; docs/06 step 6; DQ-16 |
| **G-man-sr-25** Astha: route-level and outlet-level table shapes differ | SR-S-43/45 p48-52; SR-F-22 | Route tab: STD table "ব্র্যান্ড / টার্গেট / অর্জন / বাকি / %" and memo target one row "All Brand" (28 / 6 / 22 / 21.43%). Outlet detail: STD table without "বাকি", memo target per brand; header lists name+code, owner, contact, Sub-Channel, Wing, Division, Territory, Zone. Empty state "তথ্য পাওয়া যায়নি". With no month chip selected data still shows. | docs/06 Astha: STD by brand with Target, Achievement, Remaining, % and memo target "All Brand" (route only). Negative-target guard is deliberate. | underspecified | minor | Document both shapes; outlet-level memo target per brand; default months = whole quarter; empty-state string; keep the target >= 0 guard. | docs/06 Astha; lens-data M-22 |
| **G-man-sr-26** Redemption: scope of the 199-point cash cap, full catalogue, stepper and dialog rules | SR-S-51 p62-63; SR-F-16 | Live "২২০ Pts Remaining"; "+" disabled when cost > remaining, "-" disabled at 0; cash-back card "প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক।" cost ১; callout "সর্বোচ্চ ১৯৯ পয়েন্ট ক্যাশ রিডিম্পশন"; gifts কিচেন র‍্যাক ২০০, চেয়ার ২০০, টর্নেডো ফ্যান ৪০০ (digit checked on the PDF), more cards below the fold; English dialogs "Confirm redemption of these items? Points will be deducted." and "Redemption successful". | docs/06 and docs/10 list four items and the cap. cfg.loyalty.cash_max_points. G-feat-20 offline double-spend. | underspecified | minor | Define whether 199 is per redemption, per month or per outlet; capture the rest of the catalogue from a scrolled screenshot; encode the stepper rules; one redemption per confirm; server rejects overdrawn balances. | docs/10; F-SR-043; cfg.loyalty.* |
| **G-man-sr-27** Task Delegation vocabulary and card behaviour | SR-S-57 p75-76; SR-F-28 | Card: red tag "OOS", outlet, status "চলমান" or "সম্পন্ন", free-text description (e.g. "Babu Store দোকানে Maxim এ OOS আছে"), "Completion on: 2025-11-30". Swipe left to right reveals "Resolve"; status becomes "সম্পন্ন" and the card stays; no confirm; tile badge shows a count. | docs/03 task.status default 'pending'; docs/06 "Resolve -> Completed". AMO manual: only status "চলমান", free-text description. | missing-field | minor | Status values ongoing/completed with the bn labels; show resolved tasks; badge semantics (open count; confirm); keep description free text (no brand column); resolve works offline. | docs/06 Task Delegation; lens-data M-25 |
| **G-man-sr-28** Sales Deposit: counter meaning, offline state, progress and failure states | SR-S-55 p71-73; SR-F-27 | "ডিভাইস স্ট্যাটাস অনলাইন"; table "বিষয় / ডিভাইস / সার্ভার" rows আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন (1/1, 68/68, 19,700/19,700, 0/0, 0/0); "ডাটা সিঙ্ক করুন"; "বিক্রয় জমা" disabled until sync; dues dialog with a dynamic count; success "বিক্রয় সফল ভাবে জমা হয়েছে". | docs/06 step 7 lists the five counters; docs/04 describes the device-vs-server screen. F-SYS-009; F-SR-035 queues the submit offline. | underspecified | minor | Define each counter's unit (outlet = outlets visited, sale = memos, stock = sticks, QC = entries, promotion = DRP/slide records: assumption); add the Offline state (manual only shows "অনলাইন"), progress, failure and retry; queue the deposit when offline. | docs/04; F-SYS-009; F-SR-035 |
| **G-man-sr-29** Dashboard: hidden content below the KPI tiles, route label format, Summary tile has no manual page | SR-S-10 p10 and later; SR-S-60 | A red card is cut off under the KPI tiles on every screenshot. Route label "Savar Bazar(Sat, Mon, Wed)" vs "Savar BazarDaily". Tile "সারসংক্ষেপ" has no page. | docs/06 Home; plan F-SR-036 reuses the AMO Summary spec (docs/07). | underspecified | minor | Obtain scrolled dashboard and Summary screenshots. Define route label composition (name + visit days in brackets, or name + "Daily"). Confirm SR Summary equals AMO Summary. | docs/06 Home; F-SR-008/036; screenshots (sponsor) |
| **G-man-sr-30** No verbatim string catalogue: 99 messages (87 app-owned) with mixed Bangla and English | Manual section D (SR-M-001..099) | Bangla text verbatim, plus English-only dialogs ("Redemption successful", "Photo capture details saved successfully.", "Campaign details saved successfully.") and source typos (F-47). | None verbatim. CLAUDE.md: strings live in a localization layer. | missing-message | major | Create the seed catalogue (`/packages/i18n`): one key per app-owned message, bn verbatim (typo-corrected), en translation; normalise the mixed-language messages both ways; exclude the 12 Android-owned strings; wire `cfg.i18n.overrides`. | /packages/i18n; docs/06 appendix; cfg.i18n.overrides |
| **G-man-sr-31** Number, date, time and money formatting profile | SR rules R-145, R-146, R-148, R-149 (p10-24, p35, p49-70) | Bangla digits with comma grouping (১,৫০০ and ১৯,৭০০); Latin digits on some screens; ISO dates and "November 26, 2025"; 12-hour "04:47 PM"; two decimals and "৳" on QC and memo; checkbox label "Baki ৬১ টাকা" for a due of ৬১.৫০. | None. Localization covers strings only. | missing-rule | minor | Define a locale formatting profile (digit system, grouping, date, time, currency suffix) in `/packages` with `cfg.i18n.numeral_system`; show exact paisa for dues; decide digit rule for the printed memo (rasterised). | docs/06; lens-sync print template; cfg.i18n.* |
| **G-man-sr-32** Outlet label differs by screen; outlet thumbnail in the dropdown | SR-S-18, S-32, S-39, S-48 (R-147) | "Moin Store (DHK-344-005-[phone]-Apsis Cluster)", "Savar Metro (1618409)", "Kamal Store (1618408-[phone]-Ma Road)", "Tasmin (1618415) : ২৯১.৫০ ৳ বাকি", "Sifat St (Diamond)"; small outlet image in the Sale entry dropdown (p34). | docs/06 fixed template "name (code-phone-cluster)"; cfg.app.outlet_list_label_format is a single template. | underspecified | minor | Per-screen label template keys (sale, review, memo, outlet-ops, astha); thumbnail delivered in the bundle (compressed); code shapes (7-digit numeric and DHK-xxx-xxx). | lens-config cfg.app.*; docs/06 |
| **G-man-sr-33** Mobile numbers lose their leading zero on some screens | SR-S-44 p50; SR-S-48 p56 | "[phone]" and "[phone]" vs "[phone]" and "[phone]" elsewhere. | docs/22 P-12: phone filled for all outlets, no format rule. | new-data | minor | Store as text; normalise to 11 digits (01XXXXXXXXX) at import and display; validate on new/info entry. | docs/11 importer; docs/03 outlet |

---

## 2. Coverage tally

| Item type | Total | COVERED | PARTIAL | MISSING | CONTRADICTS |
|---|---|---|---|---|---|
| Screens (SR-S) | 60 | 21 | 30 | 4 | 5 |
| Flows (SR-F) | 33 | 13 | 12 | 3 | 5 |
| Rules (SR-R) | 150 | 90 | 35 | 18 | 7 |
| Messages (SR-M) | 99 | 12 | 79 | 8 | 0 |
| Entities (SR-E) | 39 | 25 | 13 | 1 | 0 |
| **All items** | **381** | **161** | **169** | **34** | **17** |

Gaps by severity: blocker 1, major 15, minor 17 (total 33). By kind: contradiction 5, missing-feature 3, missing-field 5, missing-message 1, missing-rule 7, new-data 1, underspecified 11.

How the counts were decided:
- Screens: 21 covered are mostly system dialogs, login, stage-gates and the programs the spec describes closely (Astha, photo capture, tutorial, settings, logout). The 4 MISSING screens are SKU Target detail (SR-S-11), Sale History (SR-S-20), Points (SR-S-21) and the Slide manual-quantity dialog (SR-S-28). The 5 CONTRADICTS are the camera/location rule (S-24), mark-as-paid (S-40) and the three outlet forms (S-47..49).
- Flows: 13 of 33 covered. The 12 PARTIAL flows each have one missing step or state; the 3 MISSING flows are Sale History, Points and the SKU target view.
- Rules: 150 rules; the 18 MISSING rules are press-and-hold (2), pack badge (1), Sale History (2), Points (2), slide shortcuts, POSM points, slide deduction, QC types, QC amounts (3) and formatting (4).
- Messages: 99, of which 12 are Android-owned (COVERED, nothing to build), 8 MISSING (the feature that raises them is absent) and 79 PARTIAL (trigger specified, text absent).
- Entities: 39; the single MISSING entity is the SKU target metrics (ADS family).
- Items covered only by a plan draft (not yet in `docs/`): forced-update gate (S-08), post-update local migration (S-09, R-016), Bluetooth permissions (S-16), the device-OTP table, the app-release manifest and the per-screen cfg keys. Merge the lens drafts before treating these as spec.

---

## 3. Manual items that imply a database field or table the schema lacks

"Plan" = `plan/lens-data.md` M-xx migrations. "Add" means neither `db/schema.sql` nor the plan has it.

| # | Manual item (screen, page) | Implied field or table | In db/schema.sql | In plan | Proposed |
|---|---|---|---|---|---|
| D1 | Slide value deducted from memo total (S-33 p34) | `memo.slide_deduction_mtk` | no | no (only `discount_mtk`, `memo_offer`) | Add; net = gross - discount - slide - qc |
| D2 | QC deduction and settlement amount (S-35 p37, S-39 p44) | `memo.qc_deduction_mtk`, `qc_entry.settlement_mtk` | no | no | Add |
| D3 | Max / done / remaining QC amount per SKU (S-36 p37) | `qc_entry.max_qc_mtk` (or derived; basis unknown) | no | no | Add after rule is known |
| D4 | Six QC fault types in two groups (S-36 p37) | `qc_entry_line(fault_type_code, qty)`, fault-type list | no (two qty columns) | no (two columns M-08) | Add; replace production/transport columns with derived totals |
| D5 | DRP offer text, validity shown to SR, ratio, reward SKU, per-SKU empty-pack count (S-26..28) | `offer` display columns; `drp_collection.sku_id` | no (drp has kind, qty only) | yes M-10/M-20 (no bilingual display text) | Add display text bn/en and validity to the offer master |
| D6 | Points expiry, expiring points, as-of date (S-21 p24) | `program_period.redeem_until` or `loyalty_ledger.expires_at`; bundle snapshot `as_of` | no | no | Add |
| D7 | POSM photo earns 50 points (S-31 p33) | `survey_question.points_reward`; ledger `source_type = 'survey_response'` | no | partial (source_type list lacks survey) | Add |
| D8 | TSO sees every SR's OTP with Create Time (S-05 p5-6) | `device_otp.created_at`, retrievable (encrypted) code; `app_user.employee_code` = Field Force ID | no | partial (M-41 stores `code_hash`, M-19 adds employee_code) | Change hash to encrypted or regenerate-on-view |
| D9 | OTP asked again after a new app version (S-04 p5-6) | `device.verified_app_version` | no | no | Add if parity is kept |
| D10 | Start-call confirmation (S-25 p27/31/42) | `visit.call_started_at`, `call_declined` | no | no (`started_at` only) | Add |
| D11 | AV, KV and survey shown only for specific outlets (S-29..31 p32-33) | `outlet_content_assignment(outlet, content or survey, valid_from, valid_to)` | no | partial (`content_item.scope` jsonb, `survey.audience` role only) | Add |
| D12 | Outlet image in the Sale dropdown (S-32 p34) | `outlet.thumbnail_media_id` | no | partial (`media_object`) | Add to bundle |
| D13 | SKU target metrics ADS, TADS, PADS, RADS (S-11 p11) | derived columns; working-day calendar; target at SKU for the SR's route | partial (`target` has route/zone x product) | partial (`dim_date`, `cfg.calendar`) | Define formulas first |
| D14 | Task status ongoing/completed (S-57 p75-76) | `task.status` vocabulary | default `'pending'` | `cfg.task.types` only | Align enum |
| D15 | Outlet requests carry Cluster, route derived (S-47..49) | `outlet_change_request.cluster_id` (and `route_id`) | no (jsonb only) | M-15 adds `route_id`, not cluster | Add `cluster_id` |
| D16 | Check-out GPS and sheet time (S-12..14) | `attendance.check_out_lat/lng` | no | yes M-12 | Covered by plan |
| D17 | Memo print state and reprint count (S-37, S-39) | `memo.print_count` | no (`printed_at` only) | yes M-06 | Covered by plan |
| D18 | Release notes text and forced version (S-06, S-08) | `app_release.notes`, `min_supported` | no | yes M-44 | Covered by plan |
| D19 | Sales-deposit counters device vs server (S-55) | `sync_batch.device_counts` | partial (`counts` jsonb) | yes M-31 | Covered by plan |
| D20 | Gift catalogue with bilingual names and point costs (S-51) | `gift_catalog` | no | yes M-23 | Covered by plan |
| D21 | Outlet-level Astha targets per brand (S-45) | `program_outlet_target` | no (target is route/zone only) | yes M-22 | Covered by plan |
| D22 | Phone numbers without leading zero (S-44, S-48) | normalised `contact_number` rule | text column | no | Normalise at import |

---

## 4. Manual items that imply an admin-configurable parameter

"Key" is the `plan/lens-config.md` name when one exists. NEW = no key in any plan draft.

| # | Parameter | Manual evidence | Manual value | Key | Action |
|---|---|---|---|---|---|
| P1 | Check-out earliest time | S-12 p14-15 | 17:00 ("৫টার পরে", "বিকাল ৫ ঘটিকা থেকে"; 05:00 PM accepted) | `cfg.day.checkout_earliest_time` | Exists; inclusive 17:00 |
| P2 | Hold-to-confirm duration | S-13/14 p13, p15 | not stated | NEW `cfg.app.hold_to_confirm_ms` | Add |
| P3 | Device OTP length | S-04 p5 | 4 | `cfg.auth.otp_length` (default 6) | Change default to 4 |
| P4 | Re-verify OTP after new app version | S-04 p5-6 | yes | NEW `cfg.auth.reverify_on_new_version` | Add (see G-17) |
| P5 | OTP validity | not shown (Create Time only) | none visible | `cfg.auth.otp_ttl_min` | Keep |
| P6 | Geofence radius, Refresh cap | S-19 p23-25 | not stated | `cfg.geo.radius_m`, `cfg.geo.refresh_max` | Exist |
| P7 | Require Precise location | S-03 p4 | Precise default | NEW `cfg.geo.require_precise` | Add |
| P8 | Force-sale reasons | S-22 p25 | 2 | `cfg.sale.force_reasons` | Exists |
| P9 | Outlet photo on every call | S-24 p27/31 | unclear | NEW `cfg.sale.outlet_photo_every_call` | Add, default off |
| P10 | Start-call prompt | S-25 p27/31/42 | on | NEW `cfg.sale.call_start_prompt` | Add |
| P11 | Sale quantity entry unit | S-32 p34 | stick | `cfg.sale.qty_entry_unit` | Default stick |
| P12 | Sale edit reasons | S-41 p47 | 3 (one known) | `cfg.memo.edit_reasons` | Fix defaults (wrong_sku) |
| P13 | QC fault types and groups | S-36 p37 | 6 types, groups MFC/MKT | `cfg.qc.fault_kinds` (2) | NEW `cfg.qc.fault_types` |
| P14 | Expired-stock QC threshold | S-36 p37 | 4 months | NEW `cfg.qc.expired_stock_months` | Add |
| P15 | Max-QC basis | S-36 p37 | 594.50 unexplained | NEW `cfg.qc.max_amount_basis` | Add once known |
| P16 | Slide shortcut steps | S-28 p29 | +/-1, 5, 10, 20, 50 | NEW `cfg.drp.shortcut_steps` | Add |
| P17 | DRP offer text, validity, ratio | S-27 p28 | 100 sticks -> 1 pack MaxR-10S; Nov 25 to Dec 30 2025 | `cfg.promo.rules` | Exists; add display fields |
| P18 | Credit: collected must be below total | S-34 p35 | strict | `cfg.credit.partial_payment_min_pct` | Exists |
| P19 | Partial later collection of dues | S-40 p45 | not allowed | NEW `cfg.credit.allow_partial_collection` | Add, default off |
| P20 | Dues warning at Sales Deposit | S-55 p72 | warn, allow submit | `cfg.day.sales_submit_dues_warning` | Exists |
| P21 | Cash-back rate and cap | S-51 p62 | 2 Tk per point, 199 points | `cfg.loyalty.cash_rate_mtk_per_point`, `cfg.loyalty.cash_max_points` | Exist; define cap scope |
| P22 | Gift catalogue and costs | S-51 p62 | 4+ items | `cfg.loyalty.gift_catalog` | Exists |
| P23 | Points expiry | S-21 p24 | April league expires 2026-05-07 | NEW `cfg.loyalty.expiry_days` | Add |
| P24 | POSM photo points | S-31 p33 | 50 | `cfg.loyalty.earning_rules` (empty) | Seed |
| P25 | Home tiles per role/user | S-10 p10 vs p44 | 12 vs 10 tiles | `cfg.app.home_tiles` | Exists |
| P26 | KPI strip items | S-10 p10 | 6 | `cfg.app.kpi_strip_items` | Exists |
| P27 | Outlet label per screen | R-147 | 5 formats | `cfg.app.outlet_list_label_format` (one) | Split per screen |
| P28 | Local sale-history window | S-20 p23 | online only | `cfg.app.local_history_days` | Exists |
| P29 | Printer PIN hint, supported models | S-15 p17 | 0000 or 1234; RPP02N | NEW `cfg.print.pin_hint`, `cfg.print.models` | Add |
| P30 | Language list and default | S-58 p78 | en, বাংলা | `cfg.app.default_locale` | Exists |
| P31 | Numeral system, date and time format | R-145..149 | Bangla digits, ISO date, 12-hour | NEW `cfg.i18n.numeral_system` | Add |
| P32 | Update policy, minimum version, Wi-Fi only | S-06/08 p7-9 | forced | `cfg.release.*` | Exist |
| P33 | Tutorial list | S-56 p74 | empty | `cfg.content.tutorial_videos` | Exists |
| P34 | Task statuses and types | S-57 p75-76 | OOS; ongoing, completed | `cfg.task.types` | Add statuses |
| P35 | Display cap on achievement % | S-10 p10 | none (shows ২৭১৯%) | `cfg.target.achievement_pct_cap` (1000) | Keep cap; allow off for parity |
| P36 | Astha quarter start month | S-43 p48 | Q-4 = Oct-Dec | `cfg.astha.quarter_start_month` | Exists |
| P37 | Working-day calendar (for ADS metrics) | S-11 p11 | not stated | `cfg.calendar.*` | Exists; used by TADS/RADS |
| P38 | Reconciliation counter list | S-55 p71 | outlet, sale, stock, QC, promotion | NEW `cfg.app.reconcile_counters` (per role) | Add |
| P39 | Pending-dues wording and count source | S-55 p72 | "১টি রিটেইলার" | `cfg.i18n.overrides` | Exists |

---

## 5. Online-only versus works offline

CLAUDE.md rule 1 says the whole selling day must work offline. The manual itself states connectivity for only four things (update download, Sale History, Sync, Sales Deposit); it never shows an offline state. "Not stated" below means the rebuild requirement comes from CLAUDE.md, not from this manual.

| # | Item (screen) | Manual evidence | Current build | Rebuild requirement |
|---|---|---|---|---|
| 1 | Login, password check (S-02) | OTP step follows | server round trip (implied) | Online for first login on a device; allow offline re-open of a downloaded day |
| 2 | Device OTP verify (S-04) | message "verify your device" | online (implied) | Online once; cache result |
| 3 | Update check, download, install (S-06) | "ডিভাইসের ইন্টারনেট কানেকশান সচল রাখুন"; Network Status line | online (stated) | Online; Wi-Fi preferred; resumable; never block upload |
| 4 | Forced-update gate (S-08) | splash with no skip | local check | `min_version` blocks a new day only |
| 5 | Reverse-geocoded address (S-12) | address under the map pin | online (implied) | Offline fallback to coordinates |
| 6 | Check-in and check-out (S-12..14) | not stated | not stated | Offline, queued |
| 7 | Printer pairing, connect, print (S-15..17, S-37) | Bluetooth | local | Offline |
| 8 | Stock (S-17) | not stated | not stated | Offline |
| 9 | Outlet list and geofence (S-18/19) | force reason "ইন্টারনেট সমস্যা" | not stated; implies connectivity trouble | Offline against the bundle |
| 10 | **Sale History (S-20)** | "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে।" | **online (stated)** | Hybrid: local window, online fallback (G-12) |
| 11 | Outlet Points (S-21) | "বিগত দিন পর্যন্ত" | not stated | Offline from bundle snapshot (as of previous day) |
| 12 | Force sale, outlet photo (S-22..24) | not stated | not stated | Offline; photo queued |
| 13 | Slide/DRP, survey, AV/KV (S-26..31) | not stated | AV/KV assets need a download | Offline; pre-download assets on Wi-Fi |
| 14 | Sale, review, credit, QC, zero sale, commit (S-32..38) | not stated | not stated | Offline |
| 15 | Memo view, reprint, mark paid, edit (S-39..42) | not stated | not stated | Offline |
| 16 | Astha targets (S-43..45) | not stated | not stated | Offline from bundle snapshot |
| 17 | Outlet new, close, info (S-47..49) | not stated | not stated | Offline queue |
| 18 | Gift redemption (S-51) | not stated | not stated | Offline with local balance; server arbitrates (G-feat-20) |
| 19 | Astha and Campaign photos (S-53/54) | not stated | not stated | Offline; upload Wi-Fi first |
| 20 | **Sync Data and Sales Deposit (S-55)** | "ডিভাইস স্ট্যাটাস অনলাইন", sync button | **online (stated); the only upload point shown** | Sync online or background; Sales Deposit queued when offline (G-28) |
| 21 | Tutorial (S-56) | empty state only | online (implied for video) | List cached; playback online |
| 22 | Task list and Resolve (S-57) | not stated | not stated | List from bundle; Resolve offline |
| 23 | Language (S-58) | not stated | local | Offline |
| 24 | PDA to Support (S-58) | instant success dialog | online (implied) | Queue offline; Wi-Fi preferred |
| 25 | Logout (S-59) | not stated | not stated | Offline; refuse wipe with pending rows |
| 26 | TSO OTP panel, Astha gift assignment (S-05, S-53) | TSO web portal | online web | Online; assignments arrive in the bundle |

Design consequence: the current app has a single explicit upload moment (end of day). The rebuild's per-sale and background sync is a behaviour change; the reconciliation screen (G-28) must still work as the manual's end-of-day check.

---

## 6. Contradictions

### 6A. Manual versus the spec (`docs/01-13`, `docs/22`, `db/schema.sql`)

| # | Manual | Spec | Resolution |
|---|---|---|---|
| 1 | Dues are settled for the whole memo, no amount field (p45) | docs/06 step 5 "mark paid (full or partial)" | G-23 |
| 2 | Outlet new/close/info select Cluster (p54-60) | docs/06 Outlet management "route" | G-22 |
| 3 | Photo capture updates outlet location at once (p27/31/42) | docs/05: routed through verification; absent from docs/13 "deliberately changed" | G-10 |
| 4 | OTP also after a new app version (p5-6) | docs/06: bind once per phone | G-17 |
| 5 | A call starts at "হ্যাঁ" (p27/31/42) | docs/06 4a: selecting the outlet opens the visit | G-08 |
| 6 | Quantity in sticks (p21, p34) | docs/13 Q8 open; lens-data M-02 assumes packs | G-01 |
| 7 | QC carries money and is deducted from the memo (p37, p44) | docs/03: QC is quantities only | G-02 |
| 8 | QC has six fault types (p37) | docs/03: two quantities | G-03 |
| 9 | No auto-applied discount line on any Review or Memo screenshot; DRP appears as a deduction | docs/06 4d "offers auto-apply"; docs/03 `memo.discount` | G-04 (unverified) |
| 10 | Task status "চলমান" / "সম্পন্ন" (p75-76) | docs/03 default 'pending' | G-27 |
| 11 | Outlet-level memo target is per brand (p52) | docs/06: memo target "All Brand" | G-25 |
| 12 | No pending-state UI for outlet requests (p53-60) | docs/06: "the SR's app shows pending state" | Spec adds it; keep (an enhancement, not a conflict) |

### 6B. Manual versus the plan drafts

| # | Manual | Plan | Resolution |
|---|---|---|---|
| 1 | 4-digit OTP | `cfg.auth.otp_length` default 6 | G-17 |
| 2 | OTP list visible to TSO | `device_otp.code_hash` (one-way) | G-18 |
| 3 | Sticks | M-02 "SRs enter packs" | G-01 |
| 4 | Memo total = gross - slide - QC | M-06 `CHECK (net = gross - discount)` | G-02 |
| 5 | Six QC types | `cfg.qc.fault_kinds` two values | G-03 |
| 6 | First edit reason "ভুল SKU নির্বাচিত।" | placeholders `qty_error`, `price_error`, `wrong_outlet` | G-24 |
| 7 | Achievement % shown uncapped (২৭১৯%) | `cfg.target.achievement_pct_cap` 1000 | Deliberate; keep, document |
| 8 | Cluster picker | F-SR-037 "Route" | G-22 |
| 9 | SR can open Sale History | F-AMO-013 only | G-12 |
| 10 | Zero sale review shows "সর্বমোট ০ ০.০০" and tapping "প্রিন্ট" completes it | F-SR-029 / G-feat-38 ask whether a zero memo exists | Evidence: a printable zero record exists; use as the answer |
| 11 | Credit checkbox and "প্রোডাক্ট QC" are separate buttons on Review, in any order | F-SR-027 "QC after credit step" | Order is free; do not enforce |
| 12 | Edit is blocked at any outlet where QC was done (p46) | DQ-16 locks only when the memo itself is QC-done | G-24 |

### 6C. Inside the SR manual

| # | Conflict | Resolution |
|---|---|---|
| 1 | Version labels 1.0.25 (login, update page) vs "Current version SR App 1.0.1" (p9) vs APK `v1` (F-01) | Installed vs offered; show real version from the build |
| 2 | Check-out "after 5" (p14 tile) vs "from 5 PM" (note); p15 accepts 05:00 PM (F-08) | Enable at >= 17:00 |
| 3 | Callout says tap "এগিয়ে যান" after choosing the shop (p22) but no such button | The button is on the sale footer (p27, 31, 34) (F-12) |
| 4 | Sale Data rows sum to 20,660.00 but the footer shows ২০,২৬০.০০ (confirmed on PDF p23; quantity 4,570 is correct) (F-14) | Apsis display or data bug; compute exactly, do not copy |
| 5 | Credit checkbox "বাকি হিসাবে" (p34-36) vs "ক্রেডিট হিসাবে" (p38-39) (F-26) | One label, localised |
| 6 | Dialog due ৬১.৫০ but checkbox "Baki ৬১ টাকা" | Show exact paisa (G-31) |
| 7 | "সেকশন:" (p38-39) vs "রুট:" (p34-36, 43) as the route line (F-29) | Same field; one label |
| 8 | Babu Store Wing "Dhaka" (p50) vs "Gaibandha" (p51-52) (F-32) | Source-data inconsistency; flag in migration |
| 9 | "বিক্রয় জমা" enables after sync (p71-72) vs after dues paid and sync (p73), while p72 allows submitting with dues (F-37) | Follow the soft warning |
| 10 | "Collect DRP Discount" (p27/31/42) vs "স্লাইড সংগ্রহ" (p34) (F-19) | One label (G-04) |
| 11 | Callout "পূর্বের সেল ডাটা দেখুন" but the button reads "Sale History" (p23) (F-48) | Same screen; one label |

### 6D. Against the other manuals (targeted search of the AMO and Web inventories)

| # | SR manual | Other manual | Resolution |
|---|---|---|---|
| 1 | OTP panel with 8 columns and 5 filters (p5-6) | Web WEB-S-44: 5 columns (Field Force Name, Username, Zone Code, Zone, OTP), no route filter | Build difference; build the superset (G-18) |
| 2 | OTP step after SR login | AMO login screen shows no OTP or device binding | plan F-SYS-003 says SR and AMO bind; confirm for AMO |
| 3 | Task card with free-text description and "OOS" tag | AMO assign form: free-text description, type "General Task", status only "চলমান" | No brand column; type list still unknown (G-27) |
| 4 | New-outlet form has Cluster only | AMO verification form shows route and cluster | Supports deriving route (G-22) |
| 5 | QC screens (p36-38) | AMO manual documents only the "প্রোডাক্ট QC" button | SR manual is the only QC source |

### 6E. Corrections to the inventory (`manual-sr.md`)

1. **Tornado fan cost is 400, not 800.** On PDF p62 the cost badge reads ৪০০; this font draws Bengali ৪ like a Latin 8 (the same glyph appears in 4,160 on p23, where 520 sticks x 8.00 = 4,160), while ৮ looks like a "b". docs/06 and docs/10 already say 400. Fix the inventory, not the spec.
2. **QC expired stock is "4 months+".** The "(8 মাস+)" reading on p37 is the same ৪ glyph.
3. **F-25 icon.** The red "eye-with-slash" at the top right of the Review header (p34-36) occupies the slot where p38-39 show a green printer; it is almost certainly the printer connection indicator (inference from position and colour), the same as the round icon on Memo, not a "hidden price" or "not synced" marker.
4. **MaxR-10S QC sample.** p37 shows 5 + 5 sticks entered (one production row, one transport row) = 10, settlement 80.00: a ready test fixture.

---

## Appendix A. Verdict for every item

Codes: C covered, P partial, M missing, X contradicts. After the code, the gap ids that cover the difference (G11 = G-man-sr-11). Every PARTIAL and MISSING message (87 in all) also points to G30, the string catalogue; G30 is printed only where no feature gap applies.


### Screens (SR-S)

- **CONTRADICTS (5):** S-24 G10,G09; S-40 G23; S-47 G22; S-48 G22; S-49 G22
- **MISSING (4):** S-11 G15; S-20 G12; S-21 G13; S-28 G04
- **PARTIAL (30):** S-03 G11; S-04 G17; S-05 G18; S-06 G19; S-07 G19; S-10 G29,G32; S-12 G21; S-13 G21; S-14 G21; S-15 G20; S-17 G01,G07; S-19 G08,G12,G13; S-25 G08,G04; S-26 G04; S-27 G04; S-29 G16; S-30 G16; S-31 G14,G16; S-32 G01,G05; S-33 G02,G06; S-35 G02,G03; S-36 G03; S-37 G06; S-39 G23; S-41 G24; S-45 G25; S-51 G26,G13; S-55 G28; S-57 G27; S-60 G29
- **COVERED (21):** S-01, S-02, S-08, S-09, S-16, S-18, S-22, S-23, S-34, S-38, S-42, S-43, S-44, S-46, S-50, S-52, S-53, S-54, S-56, S-58, S-59

### Flows (SR-F)

- **CONTRADICTS (5):** F-09 G10,G09; F-19 G23; F-24 G22; F-25 G22; F-26 G22
- **MISSING (3):** F-14 G12; F-15 G13; F-23 G15
- **PARTIAL (12):** F-01 G17,G11; F-02 G18; F-03 G19; F-04 G21; F-05 G21; F-06 G20; F-07 G01,G07; F-08 G06,G08; F-12 G04; F-13 G03,G02; F-21 G24; F-22 G25
- **COVERED (13):** F-10, F-11, F-16, F-17, F-18, F-20, F-27, F-28, F-29, F-30, F-31, F-32, F-33

### Rules (SR-R)

- **CONTRADICTS (7):** R-006 G17; R-007 G17; R-058 G10; R-073 G01; R-096 G23; R-113 G22; R-115 G22
- **MISSING (18):** R-025 G21; R-028 G21; R-041 G05; R-050 G12; R-051 G12; R-052 G13; R-053 G13; R-065 G04; R-071 G14; R-076 G02; R-089 G03; R-090 G02; R-091 G02; R-093 G02; R-145 G31; R-146 G31; R-148 G31; R-149 G31
- **PARTIAL (35):** R-003 G11; R-004 G11; R-009 G18; R-010 G18; R-011 G18; R-013 G19; R-017 G29; R-022 G15; R-024 G21; R-032 G20; R-037 G07; R-039 G07; R-040 G01,G07; R-048 G11; R-049 G12,G13; R-054 G13; R-059 G08; R-063 G04; R-064 G04; R-067 G16; R-068 G16; R-075 G02; R-079 G31; R-082 G06; R-083 G06; R-088 G03; R-094 G23; R-101 G24; R-107 G25; R-108 G25; R-121 G26; R-122 G26; R-136 G28; R-140 G27; R-147 G32
- **COVERED (90):** R-001, R-002, R-005, R-008, R-012, R-014, R-015, R-016, R-018, R-019, R-020, R-021, R-023, R-026, R-027, R-029, R-030, R-031, R-033, R-034, R-035, R-036, R-038, R-042, R-043, R-044, R-045, R-046, R-047, R-055, R-056, R-057, R-060, R-061, R-062, R-066, R-069, R-070, R-072, R-074, R-077, R-078, R-080, R-081, R-084, R-085, R-086, R-087, R-092, R-095, R-097, R-098, R-099, R-100, R-102, R-103, R-104, R-105, R-106, R-109, R-110, R-111, R-112, R-114, R-116, R-117, R-118, R-119, R-120, R-123, R-124, R-125, R-126, R-127, R-128, R-129, R-130, R-131, R-132, R-133, R-134, R-135, R-137, R-138, R-139, R-141, R-142, R-143, R-144, R-150

### Messages (SR-M)

- **MISSING (8):** M-022 G21; M-027 G21; M-030 G21; M-040 G12; M-041 G13; M-042 G13; M-047 G08; M-066 G02
- **PARTIAL (79):** M-004 G17; M-005 G17; M-006 G17; M-007 G19; M-008 G19; M-009 G19; M-010 G19; M-011 G19; M-012 G19; M-013 G19; M-016 G19; M-017 G19; M-018 G19; M-019 G19; M-020 G19; M-021 G21; M-023 G21; M-024 G21; M-025 G21; M-026 G21; M-028 G21; M-029 G21; M-036 G20; M-037 G30; M-038 G30; M-039 G30; M-043 G30; M-044 G11; M-048 G04; M-049 G04; M-050 G16; M-051 G16; M-052 G14; M-053 G16; M-054 G06; M-055 G31; M-056 G31; M-057 G31; M-058 G31; M-059 G06; M-060 G06; M-061 G06; M-062 G30; M-063 G03; M-064 G03; M-065 G03; M-067 G23; M-068 G23; M-069 G23; M-070 G23; M-071 G24; M-072 G24; M-073 G25; M-074 G22; M-075 G22; M-076 G22; M-077 G22; M-078 G22; M-079 G22; M-080 G26; M-081 G26; M-082 G26; M-083 G30; M-084 G30; M-085 G30; M-086 G30; M-087 G30; M-088 G30; M-089 G28; M-090 G28; M-091 G28; M-092 G28; M-093 G30; M-094 G27; M-095 G27; M-096 G27; M-097 G27; M-098 G30; M-099 G30
- **COVERED (12):** M-001, M-002, M-003, M-014, M-015, M-031, M-032, M-033, M-034, M-035, M-045, M-046

### Entities (SR-E)

- **MISSING (1):** E-13 G15
- **PARTIAL (13):** E-01 G18; E-02 G17,G18; E-07 G16,G32,G33; E-15 G08; E-18 G02; E-20 G12; E-21 G04; E-23 G14,G16; E-24 G02,G03; E-30 G13; E-32 G14; E-33 G27; E-38 G28
- **COVERED (25):** E-03, E-04, E-05, E-06, E-08, E-09, E-10, E-11, E-12, E-14, E-16, E-17, E-19, E-22, E-25, E-26, E-27, E-28, E-29, E-31, E-34, E-35, E-36, E-37, E-39

---

## Appendix B. PDF spot-check log (`0965d940-SR_App_User_Manual.pdf`)

| Pages | Checked | Result |
|---|---|---|
| 10-11 | Dashboard, KPI tiles, SKU target cards | Matches the inventory. ২৭১৯% uncapped bar; ADS/TADS/PADS/RADS values confirmed (500: TADS 36, RADS 45; 900: TADS 68, RADS 82). A red card is cut off under the KPI tiles. |
| 23-25 | Sale History, Points, Force Sale | Matches. Note "বিঃদ্রঃ ... মোবাইল ফোনের ডাটা অন রাখতে হবে।" confirmed. Footer total ২০,২৬০.০০ vs rows summing 20,660.00 confirmed. Digit glyph evidence: 520 x 8.00 = 4,160 drawn with the Latin-8-like ৪. |
| 34-35 | Sale entry, Review, partial payment | Matches. Three unlabelled indicators and the pack badge visible on every card. "Baki ৬১ টাকা" vs due ৬১.৫০ confirmed. Red slashed printer icon in the Review header. |
| 37-38 | QC entry, QC summary, commit dialogs | Matches; six fault rows; inputs 5 and 5; settlement ৮০.০০৳; green printer icon in the Review header on p38. |
| 45-47 | Mark paid, Memo, edit reason, edit page | Matches. Mark-paid dialog has no amount field. Edit reason dialog shows one option "ভুল SKU নির্বাচিত।". Edit cards show ২৫০০ / ৫০০০ / ৮৭৫০, ১০০%, ০. |
| 62-63 | Gift Redemption | One correction: the tornado fan cost badge is ৪০০ (inventory said ৮০০). Everything else matches. |
| 72-73 | Sales Deposit | Matches; reconciliation rows, dues dialog, success dialog. |

## Appendix C. What only the sponsor's screenshots or the live app can settle

1. Whether an outlet photo is taken on every call (G-09).
2. Meaning of the three per-SKU indicators on sale cards (G-05).
3. The other two sale-edit reasons (G-24).
4. SR Summary screen, scrolled dashboard, populated Tutorial list, rest of the gift catalogue (G-29, G-26).
5. Definitions of ADS, TADS, PADS, RADS and the max-QC rule (G-15, G-02).
6. A printed sale memo, zero-sale memo, stock memo and reprint (layout is shown nowhere in this manual; plan gap G-sync-02 already tracks it).
7. What "gift requisition" means on the Points screen (G-13).
8. What happens on "না" at the start-call prompt and at the print prompt (G-08, G-06).
