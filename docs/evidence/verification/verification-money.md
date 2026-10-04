# Verification: memo money, units, QC, slide/DRP

Batch: G-man-001, 002, 003, 004, 005, 009, 010, 012, 089. Date 2026-10-04.
Method: every cited PDF page was opened (SR pp.21-23, 27-30, 34-38, 43-48; AMO pp.28-30, 41-48, 66-68; Web pp.3, 5-8; TSO p.5). Small print and icons were re-rendered at 300-400 dpi and cropped. Page numbers are PDF page numbers. Spec quotes are from /home/user/Aron-Pro-Max/docs and db/schema.sql; plan quotes are from plan/lens-*.md.
Cross-check source for money arithmetic: docs/ui-reference/sr/home.md (UI-SR-04/05/06), an independent screenshot set.

## Summary table

| ID | Verdict | One-line outcome |
|---|---|---|
| G-man-001 | PARTLY | Stick entry for cigarette and bidi is proven (stock badge 6,500 -> 650). Match unit conflict is real and also occurs inside the SR manual. Several side claims (price list changed, stepper = pack size, golden test to the paisa) are wrong or unproven. |
| G-man-002 | PARTLY | Slide and QC are deducted from the on-screen memo total and the home KPI. But the printed (paper) layout is never shown, max-QC is a money cap (taka), and AMO memo/summary rows differ from SR. |
| G-man-003 | CONFIRMED | Six fault types, two groups, picker/entry/summary/confirm screens all seen. Expiry is 4 months+ (Bengali digit 4), not 8. |
| G-man-004 | PARTLY | Offer text, dates, dialog, shortcut steps all seen. The fixture is wrong in one number (entered 10 empty packets, not 100) and the footer/gross behaviour can be inferred. C-10 should be re-opened: auto discounts do exist. |
| G-man-005 | PARTLY | Three unlabelled indicators and the pack badge are real. The "red eye-with-slash" is actually a printer-with-slash icon (printer not connected). The percent=stock hypothesis is refuted. |
| G-man-009 | CONFIRMED | Dialog rule "must be less than grand total" is in the manual (spec silent). Label truncates 61.50 to 61 (truncation, not rounding). Two labels for the same checkbox. |
| G-man-010 | CONFIRMED | Whole-memo settlement only, in both apps, no amount field. |
| G-man-012 | PARTLY | Reason text and QC lock wording seen. But docs/06 already says "outlets already QC'd cannot be edited"; only plan DQ-16 / F-SR-033 deviate. AMO Edit is grey on all three screenshots. |
| G-man-089 | CONFIRMED | Web QC entry (market and warehouse), QC report with Excel and PDF, route-wise report, 5+5 taxonomy all seen. Page shows the TSO login. |

---

## G-man-001: quantity unit (sticks) and Match/Lighter unit conflict

**Claim (register s1).** Sale and stock quantity is entered in sticks; Match/Lighter unit differs by screen. Fix: cfg.sale.qty_entry_unit=stick for cigarette and bidi, pack badge read-only = qty / pack size, stepper step = pack size, FB 1 = 2.33 on AMO p28/p43 but 28.00 on p29/p46, price list changed since the seed (MaxR-10S 8.00 vs 9.20), golden tests AMO p43 and p46 "to the paisa". Contradiction C-01, C-37, C-38.

**What I saw.**
- SR p21 (stock): MaxR-10S issue 6,500 with purple badge 650; MaxB-20S 6,000 with badge 300; category totals Cigarette 12,500, Bidi 2,400, Lighter 550, Match 400. 6,500 / 10 = 650 and 6,000 / 20 = 300, so the entered number is sticks and the badge is the pack count. Hard evidence.
- SR p34 sale: MaxR-10S stepper 10, badge 1; MaxR-20S 20, badge 1; AB-12s 12, badge 1. Review p34: MaxR-10S 20 -> 160.00, i.e. 8.00 per stick; a 10s pack costs 80.00. AMO p28: MaxR-10S 10 -> 80.00, ABS 25 -> 20.00 (0.80 per stick), Aster 1 -> 12.50 (per piece).
- Match: SR p34 review SL 12 -> 19.00 and "total match 12 -> 19.00". SR p35 review (same manual, same app): FB 1 -> 28.00, SL 1 -> 19.00, "total match 2 -> 47.00". AMO p28: FB 1 -> 2.33, SL 1 -> 1.58, total match 2 -> 3.92. AMO p29 and p46: FB 1 -> 28.00, SL 1 -> 19.00, match 2 -> 47.00. 28.00 / 12 = 2.333 and 19.00 / 12 = 1.583, so the same SKU is shown per piece on some screens and per dozen on others. The conflict is therefore also inside the SR manual (p34 vs p35), not only AMO.
- Reporting units: Web p3 tiles "Sales (Lighter in Pcs)", "Sales (Match in Dozen)"; TSO p5 "Sales (Lighter in Box)", "Sales (Match in Dozen)".
- Rounding: AMO p28 / p43 lines FB 2.33 + SL 1.58 = 3.91 but the "total match" row shows 3.92 and grand total 116.42 = 80.00 + 20.00 + 12.50 + 3.92. So totals are computed from unrounded line values (28/12 + 19/12 = 3.9167) and rounded once.

**Spec says.** docs/13 Q8: "Units - STD vs STT naming; bidi unit; lighters in pieces (web) vs boxes (TSO app). Needed for correct KPI math." docs/10: STD is "sticks (cigarette), pieces/dozens (lighter/match), bidi unit - confirm docs/13". db/schema.sql line 50: `unit text NOT NULL DEFAULT 'stick'` (the register cites this as docs/03; docs/03 has no unit text). docs/03 memo_line: "quantity, unit_price, discount, offer_id", no unit. Plan lens-data M-02: "ASSUMPTION: SRs enter packs ... entry_unit_default 'pack'". docs/22 P-04: volume in sticks.

**Verdict: PARTLY.**

**Corrected statement.**
1. Cigarette and bidi: the SR and AMO apps enter, store and show quantity in sticks; the pack badge is a read-only qty / pack_size indicator (SR p21: 6,500 sticks = 650 packs). Plan M-02's "SRs enter packs" is refuted; entry_unit_default must be stick for these categories. Close Q8 for cigarette and bidi.
2. Lighter (Aster): 1 = one piece = 12.50 on every screen. Reporting unit differs by surface (web Pcs, TSO app Box). That is a report_unit setting, not an entry-unit conflict.
3. Match (FB, SL): the entry/display unit is inconsistent even within the SR app (12 pieces on p34, 1 dozen on p35) and AMO (p28 vs p29). Do not infer a unit from the number. Both manuals report Match in dozens.
4. Not supported by the pages: "stepper step = pack size" (only the values 10, 20, 12, 25 are visible, all multiples of pack size, but the step is not shown) and "loose sticks may be typed" (the field is editable but no example). Keep both as MUST-CONFIRM.
5. "Price list changed since the seed" is unproven. Seed sku_catalog.csv has MaxR-10S price_outlet 9.2 and distributor 7.935; the manual's 8.00 matches neither. It may be a different price type or a test list. Use the manual numbers as fixtures only, never as prices.
6. "AMO memo p43 and summary p46 must reproduce to the paisa" holds for the totals (116.42, 289.50) only if totals are summed from unrounded line values. Summing the displayed lines gives 116.41. This is a concrete rounding rule for G-feat-46.

**Build implication.**
- Schema: memo_line stores qty_entered, unit_entered, pack_factor, qty_base (M-07 as planned) and the unit price at 1/1000 taka or as a price per pack/dozen with a rational factor (28.00 per dozen, not 2.33 per piece). Per-line display rounding is never stored.
- Rule: totals computed from unrounded line values, rounded once; golden fixtures AMO p28/p43 (116.42) and p29/p46 (289.50) plus SR p34 (360.50) and p35 (161.50).
- Config: cfg.sale.qty_entry_unit by category (stick for cigarette and bidi, piece for lighter; match = MUST-CONFIRM with the business); cfg.report.unit by surface.
- UI: every quantity cell for Match carries a visible unit label.
- Docs: Q8 answered for cigarette and bidi; plan M-02 assumption replaced.

---

## G-man-002: memo total omits slide deduction, QC settlement and max-QC

**Claim (register s1).** The manual's total = gross minus slide minus QC; spec has gross/discount/net and QC quantities only. Fixture: gross 360.50 - slide 80.00 = 280.50, quantity total unchanged. qc_entry.settlement_mtk = defect sticks x price (10 x 8.00 = 80.00); max_qc basis unknown (594.50). AMO Summary "ডিসকাউন্ট এবং অন্যান্য (-)" is probably discount plus QC; add a second totals block. Define zero-sale plus QC. Printed lines "স্লাইড", "মোট QC", "সর্বমোট".

**What I saw.**
- SR p34 review: lines 360.50 (qty 65); slide block (offer text, MaxR-10S 10, 80.00 in red; block total 10 / 80.00); "সর্বমোট 65 / 280.50". Confirms 360.50 - 80.00 = 280.50 and the quantity does not change.
- SR p37 (QC entry): header block "সর্বোচ্চ QC: ৫৯৪.৫০৳ / QC হয়ে গেছে: ০.০০৳ / বাকি আছে: ৫৯৪.৫০৳". So the cap is a taka amount, with consumed and remaining. QC summary: MaxR-10S, "MFC Fault,MKT Fault", 10; "মোট QC নিষ্পত্তি 10" and "সেটেলমেন্ট পরিমান ৮০.০০৳". Entered 5 + 5 = 10 sticks; 10 x 8.00 = 80.00 matches the single example (formula still inferred).
- SR p38 right (review after QC): a red "QC ..." row appears among the lines above the total (partly hidden by the dialog).
- SR p44 (memo screen): "মোট ২৯১.৫০" then "মোট QC - ০.০০" then "সর্বমোট ২৯১.৫০". No discount row on this screen.
- AMO p43 memo screen: "মোট ডিসকাউন্ট - ০.০০" then "সর্বমোট"; no QC row. AMO p46 summary: "ডিসকাউন্ট এবং অন্যান্য (-) -০.০০" then "সর্বমোট ২৮৯.৫০", then a second block "মোট বিক্রয়": মোট 289.50 / ডিসকাউন্ট (-) 0.00 / সর্ব মোট 289.50.
- SR p43 (zero sale): the zero review (সর্বমোট 0 / 0.00) still offers "প্রোডাক্ট QC", so QC on a zero sale is possible and the total could go negative.
- Independent check, docs/ui-reference/sr/home.md UI-SR-04/05/06: home card gross 4,828.50 - discount 437.50 - QC 0.00 = net 4,391.00, with a separate "DRP ডিসকাউন্ট 0.00". So the slide is the DRP discount and is a separate component from the offer discount.
- Inference (arithmetic, flag as such): on SR p34 the sale screen shows MaxR-10S 10 and footer 280.50, while the review shows MaxR-10S 20 and gross 360.50. The 10-stick difference equals the slide reward, and 360.50 - 80.00 = 280.50 = the sale footer. Most likely the reward pack is added to the memo lines (quantity counted, 65 = 55 sold + 10 reward) and its value is deducted as slide. This also answers the register's open question: the footer is net payable.

**Spec says.** docs/03 memo: "gross, discount, net, is_credit, paid_amount, due_amount"; qc_entry: "production-fault and transport-fault quantities". docs/06 step 4d: "... optional credit (বাকি) with partial payment -> Product QC (production/transport fault quantities per SKU) -> print memo". Plan lens-data M-06: `CHECK (net_mtk = gross_mtk - discount_mtk)`; DQ-14 `net = gross - discount`; G-feat-05 "no replacement, credit note or stock effect".

**Verdict: PARTLY.** Core confirmed, spec and plan genuinely lack the deductions.

**Corrected statement.**
1. On-screen memo/review/KPI: net = gross - offer discount - DRP (slide) discount - QC settlement. Quantity totals are not reduced by slide or QC.
2. max-QC is a per-SKU taka cap with "done" and "remaining" shown (594.50 for MaxR-10S in the example). The basis is unknown; it is not 10 x 8.00. DQ-18 (faults <= qty sold) must not be reused.
3. The printed paper layout is not shown in any manual (manual-sr F-44: "the printed memo itself is never shown"). The lines "স্লাইড / মোট QC / সর্বমোট" are screen lines. The paper layout needs the sponsor's sample memo.
4. SR and AMO memo screens show different subsets of deduction rows (SR: QC; AMO: discount). The AMO "ডিসকাউন্ট এবং অন্যান্য (-)" reading as discount plus QC is inference only. Do not copy either subset; store all components and print all non-zero ones.
5. Zero-sale plus QC is reachable, so a negative net is possible; a rule is needed.

**Build implication.**
- Schema: memo gets offer_discount_mtk, drp_discount_mtk (slide), qc_deduction_mtk; net_mtk = gross - those three; amend CHECK in M-06 and DQ-14. qc_entry_line gets settlement_mtk (price at capture x sticks, rule MUST-CONFIRM) and qc_cap_mtk snapshot. Slide reward qty is stored on memo_line with is_free/offer_id so volume and stock include it (confirm).
- Rule: define net floor (negative allowed? cfg.memo.allow_negative_net) and who bears QC credit; G-feat-05 becomes "QC is a monetary credit on the memo, stock effect still unknown".
- UI: Review and Memo show the three deduction rows; label glossary (UI-SR-07: one Bangla term each for gross, offer discount, DRP discount, QC, net).
- Test: fixtures SR p34 (360.50 / 80.00 / 280.50 / qty 65) and SR p37 (10 sticks, 80.00).

---

## G-man-003: six QC fault types, picker, entry, summary and submit screens

**Claim (register s1).** Six fault types in two groups; screens: SKU carousel, entry, summary 'MFC Fault,MKT Fault', submit confirm and empty state; AMO documents only the button; expiry is "4 months+" (glyph reads like 8).

**What I saw.**
- SR p36: QC picker with info text "QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন", SKU carousel (MaxR-10S, MaxR-20S, MaxB-10S ...), section "QC সারাংশ" with empty state "QC করার জন্য কোন পণ্য নেই", button "QC জমা দিন →".
- SR p37 (zoomed): columns ফল্ট টাইপ / প্রকার / পরিমাণ. Group "উৎপাদন ত্রুটি": ড্যামেজড ও ক্রাশড - প্যাক / আউটার CBC; আউটার / প্যাক / স্টিক কম থাকা; অন্যান্য ত্রুটি. Group "পরিবহন ত্রুটি": মেয়াদোত্তীর্ণ স্টক (৪ মাস+); স্টক ড্যামেজড - রুট সার্ভিস কালীন; স্বাদ সংক্রান্ত সমস্যা. Buttons সংরক্ষণ, বাতিল, ডিলিট. Summary table uses "MFC Fault,MKT Fault".
- Digit check: the "4" in "(৪ মাস+)" is the same open "8-like" glyph as the 4 in "৫৯৪.৫০৳" on the same page, while 8 in "৮০.০০৳" renders as a "b"-like glyph. So it is Bengali 4, as the register says.
- SR p38: confirm dialog "ইনফর্মেশন / আপনি কি QC জমা দিতে চান?" (হ্যাঁ / না).
- AMO p28 / p42-43: "প্রোডাক্ট QC" button on Review only; no QC screen anywhere in the AMO manual; "কিউসি" appears only as a count row on the sales-deposit table (AMO pp.66-68).

**Spec says.** docs/03 qc_entry: "production-fault and transport-fault quantities". docs/06 4d: "Product QC (production/transport fault quantities per SKU)". Plan: cfg.qc.fault_kinds = production_fault, transport_fault.

**Verdict: CONFIRMED.** The spec's two groups are right; it lacks the six types and the screens. The semantics of বাতিল (discard edits) and ডিলিট (remove this SKU's QC) are inference from button names.

**Corrected statement.** As claimed. Add: the app group "পরিবহন ত্রুটি" (transport) is labelled MKT in English, and holds the expiry type; the web calls the same group "Marketing Fault" (see G-man-089). Keep stable codes MFC / MKT and treat the Bangla/English group names as labels.

**Build implication.**
- Schema: qc_fault_type(code, group MFC/MKT, label_bn, label_en, applies_to {app, web}, sort, active); qc_entry_line(client_uuid, qc_entry_id, fault_type_code, qty_sticks); group totals derived.
- Config: cfg.qc.fault_types (replaces fault_kinds), cfg.qc.expired_stock_months = 4.
- UI: reuse the SR screens for AMO (flavour of the same code); strings in the localisation layer.
- Sync: qc_entry_line idempotent by client_uuid; QC completion time stored for the edit lock (G-man-012).

---

## G-man-004: Slide/DRP offer rule, dialogs, memo slide block

**Claim (register s1).** Seed the first offer and fixture: "100 sticks of empty MaxR packs -> 1 pack MaxR-10S (10 sticks x 8.00 = 80.00) shown as a deduction, quantity total unchanged". Offer master needs bn/en text, valid_from/valid_to (Nov 25 2025 to Dec 30 2025), qualifying SKU or brand, ratio, reward SKU. Slide screen lists only SKUs with an active offer ('OFFER' ribbon); detail dialog 'ছাড়'; manual-quantity dialog with shortcut steps +/-1, 5, 10, 20, 50. Open: unit of 'খালি প্যাকেট', behaviour outside validity window, footer total net of slide?, one label for 'Collect DRP Discount' vs 'স্লাইড সংগ্রহ', where auto-applied discounts render.

**What I saw.**
- SR p27: after the photo, the dialog "আপনি কি কল শুরু করতে চান?" (হ্যাঁ / না); sale footer has yellow "Collect DRP Discount" next to "এগিয়ে যান →" (cart total 0.00). SR p34 shows the same yellow button labelled "স্লাইড সংগ্রহ".
- SR p28: screen "স্লাইড", column header "খালি প্যাকেট" (empty packets); rows MaxR-10S and MaxR-20S only, each with an "OFFER" ribbon. Detail dialog titled "ছাড়": "100 stick worth of empty pack of MaxR get 1 pack MaxR 10s", Nov 25, 2025 -> Dec 30, 2025, button "বন্ধ করুন". The offer text is English only.
- SR p29: manual-quantity dialog: stepper 10; two columns of shortcut buttons 1, 5, 10, 20, 50 and -1, -5, -10, -20, -50; "সংরক্ষণ". The callout says entry "প্যাক অনুযায়ী" (by pack). SR p30: saved value 10 in the stepper; "জমা দিন" saves.
- SR p34 review: slide block with the offer text, MaxR-10S 10 / 80.00 (red), block total 10 / 80.00, grand total 65 / 280.50.
- SR p47 edit re-entry screen: footer has only "এগিয়ে যান →"; no slide button.
- AMO pp.66-68: "প্রমোশন" is a count row only; no AMO slide screen.
- docs/ui-reference/sr/home.md UI-SR-05: home card shows "মোট ডিসকাউন্ট 437.50" and "DRP ডিসকাউন্ট 0.00" side by side, so ordinary auto-applied discounts exist and are separate from DRP.

**Spec says.** docs/06 4c: "DRP empty-pack/slide collection with offer (drp_collection)"; 4d "offers auto-apply". docs/10: "DRP empty-pack/slide collection earns a discount." db/schema.sql: memo_line.offer_id and drp_collection.offer_id have no offer table. Plan G-feat-13 (blocker), C-10 "Undecided: no auto-applied discount line on any Review or Memo screenshot".

**Verdict: PARTLY.**

**Corrected statement.**
1. The entered value is 10 in the "খালি প্যাকেট" column, not 100. The offer text means 100 sticks' worth of empty MaxR packs; 10 empty packs of a 10s pack = 100 sticks. The callout "by pack" and this arithmetic favour packs as the entry unit, but the page does not state it. Fixture: enter 10 empty MaxR-10S packets -> reward 1 pack MaxR-10S (10 sticks x 8.00) -> slide row "MaxR-10S 10 / 80.00", slide total 80.00, memo quantity total unchanged (65).
2. The offer text names the brand family (MaxR); the slide list shows both MaxR-10S and MaxR-20S, and the reward SKU is fixed (MaxR-10S). Offer master therefore needs a qualifying brand/SKU set and a separate reward SKU.
3. "Lists only SKUs with an active offer": consistent (two SKUs, both ribboned, MaxB absent) but not proven.
4. Footer net of slide: inferred yes (see G-man-002 arithmetic); mark MUST-CONFIRM in the live app.
5. C-10 should be re-opened: auto-applied (non-DRP) discounts do exist (home card 437.50). They are not on the Review screenshots only because those examples had none.
6. Edit re-entry (p47) shows no slide button: whether an edited memo can change its slide is unknown.
7. The validity window is displayed in the dialog; what happens outside it is not shown.

**Build implication.**
- Schema: offer(id, code, text_bn, text_en, type, valid_from, valid_to, qualifying_brand_id/sku set, ratio_in, ratio_unit, reward_sku_id, reward_qty, scope); drp_collection with client_uuid, sku_id, packs_entered, offer_id, reward_qty_sticks, reward_value_mtk; memo.drp_discount_mtk (G-man-002).
- Config: cfg.drp.shortcut_steps = [1,5,10,20,50] (+/-), cfg.promo.enforce_validity.
- UI: slide screen, OFFER ribbon, "ছাড়" dialog, shortcut grid; one label for the entry button (use the Bangla "স্লাইড সংগ্রহ"; keep "Collect DRP Discount" as the English alias).
- Offline: the offer master and its validity dates ship in the bundle; the discount is computed on device.

---

## G-man-005: sale card extras (three indicators, pack badge, red icon)

**Claim (register s1).** Three unlabelled indicators on each sale card (cart number, percent, box) plus a purple pack badge, and a red eye-with-slash icon on Review. Hypotheses: cart number = available stock in sticks (AMO Avon-20S 11000 equals the lifted-stock figure on AMO p41); percent = discount or stock percentage; box = free or promo quantity; eye-slash = printer connection indicator.

**What I saw.**
- Each card (SR p34, p47; AMO p28): cart icon + number, floppy icon + percent, box icon + number, and a purple badge on the product image. Values SR p34: 10000 / 11000 / 3600, percent 0%, box 0.
- Badge: SR p34 badge 1 for qty 10 / 20 / 12; SR p47 badge 1 for 10 / 20 / 25; SR p21 stock badge 650 for 6,500 sticks of a 10s pack. Consistent with qty / pack size on every card seen.
- Icon (zoom of AMO p43): the red icon is a printer with a diagonal slash (print_disabled), not an eye. The same slot shows a green printer (SR p38-39, AMO p43 right, SR p44 memo header) when the printer is available. So the register's "eye-slash" is a misreading of the glyph, and its inference "printer connection indicator" is right.
- Percent: SR p47 (edit re-entry) shows 100% on all three cards while the fresh sale screens (SR p34, AMO p28) show 0%. With stock 2500 and qty 10 a stock percentage would be 0%, not 100%, so "percent = stock percentage" is refuted. Discount percent is also unlikely (100%).
- Cart number: SR p47 shows 2500 / 5000 / 8750 where SR p34 shows 10000 / 11000 / 3600 (different accounts and days). AMO p28 Avon-20S 11000 equals the AMO p41 lifted figure for SR-12237 (11,000), but ABS 10000 has no counterpart there (EAB 13,750) and Aster 550 differs from 1,000. So "available stock" is plausible but only one value matches.

**Spec says.** docs/06 4d: "enter SKU quantities". Plan F-SR-023: "Enter quantities per sellable SKU; suggested qty shown".

**Verdict: PARTLY.**

**Corrected statement.** Three unlabelled numeric indicators and a pack badge exist on each sale card. Badge = qty / pack size (confirmed on 8 cards plus the stock screen). The header icon is a printer-status indicator (red slashed printer = not connected, green printer = connected), not an eye. The percent is not stock percentage; it is 0% on new sales and 100% on edit re-entry, meaning unknown. The cart number is plausibly available stock but unproven. The box number is always 0 in the samples and unknown.

**Build implication.**
- UI: implement the printer-status icon (bound to the Bluetooth printer state) on Review and Memo; reserve three read-only slots on the sale card; do not label them until the live app or the sponsor explains them.
- Open question for the business: meaning of the percent and the box number (add to Q list); screenshot a card with a non-zero box number and a first-time (non-edit) percent.
- Data: no new stored fields until the meanings are known; the badge is derived.

---

## G-man-009: credit-sale partial-payment dialog

**Claim (register s1).** Validation: amount >= 0 (0 = full credit), strictly less than net, at most 2 decimals; show due with 2 decimals everywhere including the checkbox label (dialog "বাকিঃ ৬১.৫০" vs label "Baki ৬১ টাকা"; AMO 48.50 vs "Baki ৪৮"); "না" closes and leaves the box unticked; one label for the checkbox (বাকি vs ক্রেডিট); Bangla error strings; spec has no rule text.

**What I saw.**
- SR p35: ticking the credit box opens "পরিশোধিত টাকার পরিমাণ লিখুন / এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করেছেন?" with field "আদায়কৃত অর্থ" containing 100 and the live remainder "বাকিঃ ৬১.৫০" (total 161.50 - 100 = 61.50); buttons হ্যাঁ / না. The callout says the amount must be less than the grand total ("সর্বমোট ... এর থেকে কম হতে হবে"). While the dialog is open the checkbox behind it is unticked. After হ্যাঁ the checkbox is ticked and reads "Baki ৬১ টাকা".
- AMO p29: same dialog with 100 entered, "বাকিঃ ৪৮.৫০" (148.50 - 100), result label "Baki ৪৮ টাকা", same callout text.
- Labels: SR p34-36 and AMO p29 checkbox "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন"; SR p38-39 (and the AMO p28 callout) "ক্রেডিট হিসাবে". Same manual, two terms.
- Not shown anywhere: an error message, entering 0, a decimal limit, what "না" does.

**Spec says.** docs/06 4d: "optional credit (বাকি) with partial payment". Plan cfg.credit.partial_payment_min_pct default 0; F-SR-026 "due = net - paid".

**Verdict: CONFIRMED.** Detail: the dialog's "বাকিঃ" is the live remainder after the typed amount; the label drops .50 in both examples (61.50 -> 61, 48.50 -> 48). Round-half-up would give 62 and banker's rounding 62, so this is truncation. Rules (>= 0, 2 decimals, "না" behaviour, error strings) are the register's proposals and are not visible in the manual; the one visible rule is "amount < grand total".

**Build implication.**
- Rule: collected amount strictly less than payable total; due = payable - collected, exact to the paisa; checkbox label shows the exact due (do not truncate).
- Config: cfg.credit.allow_zero_payment (default on, MUST-CONFIRM), cfg.credit.partial_payment_min_pct = 0.
- UI/i18n: one label key (use "বাকি"); error strings for amount >= total in the catalogue; "না" leaves the box unticked.
- Test: SR p35 (161.50, 100 -> 61.50) and AMO p29 (148.50, 100 -> 48.50).

---

## G-man-010: mark-as-paid settles the whole memo

**Claim (register s1).** Both apps settle the whole memo only; the spec says full or partial. Build the manual flow (whole memo, "আপনি কি নিশ্চিত?", success, due tag disappears) but write a due_collection row (client_uuid, against_memo_id, amount = remaining); partial later collection behind cfg.credit.allow_partial_collection default off.

**What I saw.**
- SR p44: Memo tile -> outlet dropdown "Tasmin (1618415) : ২৯১.৫০ ৳ বাকি" -> memo with the red panel "এই বিক্রয়টি বাকিতে করা হয়েছে।" and green button "পরিশোধিত করুন". SR p45: "আপনি কি নিশ্চিত?" (হ্যাঁ / না) -> success "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।" Callout: the due status disappears from beside the outlet. No amount field at any step.
- AMO p43-45: identical flow for Siyam (116.42 বাকি).
- Not shown: a memo with several dues, a partially paid memo on this screen, a receipt.

**Spec says.** docs/06 step 5: "mark paid (full or partial) -> confirm. -> due_collection; recompute outlet due." docs/07: "Memo menu: Print | Edit | Mark paid." docs/03: due_collection "amount collected against a prior credit memo".

**Verdict: CONFIRMED.**

**Build implication.**
- Flow: whole-memo settlement first; remaining = due at that moment (for a partially paid memo this is the remainder, unverified).
- Schema: due_collection row per action (client_uuid, memo, amount = remaining, is_full_settlement, mode); idempotent.
- Config: cfg.credit.allow_partial_collection default off (labelled enhancement).
- Open: which memo is settled if an outlet has several; imported previous-day dues (G-feat-45).

---

## G-man-012: sale edit reasons, QC lock, AMO grey Edit button

**Claim (register s1).** Replace the first placeholder reason with wrong_sku ("ভুল SKU নির্বাচিত।"); capture the other two; drop wrong_outlet (outlet read-only in edit); lock edits at outlet level once any QC was done there (SR p46), aligning DQ-16; apply the SR rules to the AMO and decide why the AMO Edit button is grey; edit creates a superseding memo.

**What I saw.**
- SR p46 callout: edit needs the outlet to be within geofencing, and "QC কৃত কোন আউটলেটে বিক্রয় এডিট করা যাবে না" (no sale can be edited at an outlet where QC was done). The wording is outlet-level; any day limit is not stated.
- SR p47: dialog "দয়া করে বিক্রয় এডিট করার কারন লিখুন" with a dropdown showing "ভুল SKU নির্বাচিত।", buttons বাতিল / জমা দিন; callout says there are three options (only one visible). After submit the sale screen reopens prefilled (MaxR-10S 10, MaxR-20S 20, AB-25s 25, footer 291.50) with the outlet name in a plain, non-dropdown field (read-only look) and no slide button.
- AMO p43-45: the Edit button is grey (disabled style) in all three screenshots (credit memo and non-credit memo, printer red and green); Print is blue. The AMO manual states no edit rule and shows no edit dialog (AMO U-45). SR Edit is yellow (SR p44-46).

**Spec says.** docs/06 step 6: "Rules: must be inside the outlet geofence; outlets already QC'd cannot be edited; choose 1 of 3 edit reasons -> re-enter -> new memo row supersedes the old." Plan F-SR-033: "memo not yet QC'd"; DQ-16: "old memo not QC-done, old memo business_date == new"; cfg.memo.edit_reasons placeholders qty_error, price_error, wrong_outlet.

**Verdict: PARTLY.**

**Corrected statement.** The register's "spec" side is wrong in one place: docs/06 itself already states the outlet-level lock ("outlets already QC'd cannot be edited") and the manual agrees; only plan F-SR-033 and DQ-16 narrowed it to per-memo, and those must be corrected, not the spec. Confirmed: the first reason is "ভুল SKU নির্বাচিত।"; outlet is read-only on re-entry (so wrong_outlet is moot); the other two reasons are not in the manual; AMO Edit is grey on every screenshot and the AMO manual gives no rule. New: the edit re-entry footer has no slide button.

**Build implication.**
- Config: cfg.memo.edit_reasons = [wrong_sku + two to capture from the live app], cfg.memo.edit_roles; QC lock key cfg.memo.lock_edit_after_qc scope = outlet (day scope MUST-CONFIRM).
- Rule: DQ-16 changed to outlet-level QC lock (any QC at that outlet, same business date unless the business says otherwise); geofence check on edit on device and server.
- UI: AMO Edit button disabled state is reproduced as disabled-until-rule-met; its trigger is MUST-CONFIRM (geofence, role or build). Edit re-entry omits the slide button until the business confirms.
- Schema: memo.edit_reason_code, supersedes_memo_id, edit fix (M-06) unchanged.

---

## G-man-089: Web QC entry, reports, 10-reason taxonomy

**Claim (register s1).** Web has market QC entry and warehouse QC entry, two QC reports (Excel and PDF), and a 10-reason fault taxonomy (5 manufacturing + 5 marketing) that overlaps with the app's six; the spec has no warehouse QC, web entry or PDF; G-feat-32 guesses the QC page is a claims review. Overlap: Damaged & Crushed Pack/Outer CBC; Outer, Pack or Stick missing; Others; Stock damaged during transport ~ app "during route service"; Shelf life expired ~ "expired stock 4 months+". Web only: Brand Mix Up, Cigarette Visual Fault, Damp Cigarette stick, Spotting on Cigarette, Others (Marketing). App only: taste-related problem.

**What I saw.**
- Web p5 "QC Entry": filters Wing / Division / Territory / House / Zone / Route / date, SKU rows (MaxR-10S ...) with number boxes (default 0), columns "Manufacturing Fault": Brand Mix Up, Cigarette Visual Fault, Damaged & Crushed Pack/Outer CBC, Outer, Pack or Stick missing, Others; "Marketing Fault": Damp Cigarette stick, Stock damaged during transport, Shelf life expired stock, Spotting on Cigarette, Others; green "Submit". Logged-in user "tso-apsis".
- Web p6 "QC Report (Market & Warehouse)": five geography filters, date range, buttons "Get Excel" and "Download PDF" (the callout mentions only Excel export).
- Web p7 "Warehouse QC Entry" (slide title says "Warehouse QC Report"): same grid, filters Wing..Zone and a single date, no route.
- Web p8 "Route Wise QC Report": filters plus "QC Type" dropdown (Market QC / Warehouse QC) and "Get Excel" only.
- Menu group QC has four items: QC Entry, QC Report (Market & Warehouse), Warehouse QC Entry, Route wise QC Report.

**Spec says.** docs/09 line 25: "Admin/master-data pages (TSO/admin ...): QC, Sales Plan, Data Entry, ..." docs/03 qc_entry: visit-bound, "production-fault and transport-fault quantities". Plan F-ADM-023: "Review QC fault quantities by SKU/route/date; (claims?)"; G-feat-32 "QC admin page content unknown".

**Verdict: CONFIRMED.**

**Corrected statement.** As claimed, with these details. The PDF button exists only on the combined QC Report; the route-wise report is Excel only. The page is a TSO-level screen (tso-apsis), which answers "who may enter" for the web. The web grid is quantity-only with no money, cap or settlement. Whether web "Market QC" entries duplicate the SR app's QC for the same route and day is not shown; the register's "one fact with a source column" is a design decision, not an observation. The app's "পরিবহন ত্রুটি" group is called MKT in the app's English summary and "Marketing Fault" on the web but holds only 3 types in the app versus 5 on the web.

**Build implication.**
- Schema: qc_fault_type holds the union (10 web + taste-related = 11 codes, with the overlap mapping above); qc_summary_entry(kind market/warehouse, zone, route nullable for warehouse, date, sku, fault_type, qty, source, entered_by) with idempotent upsert; reports 'qc' (xlsx, pdf) and 'qc-route' with QC Type filter.
- Rule: re-entry policy (replace vs add) and the double-count rule with app QC are MUST-CONFIRM; do not add web and app QC without a source column.
- Roles: TSO write rights within scope on web QC pages (C-34).
- Plan: close G-feat-32 (it is an entry grid plus reports, not a claims page); F-WEB-052 as proposed.
