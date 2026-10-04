# Verification: targets, KPIs, till-date maths, missing SR screens

Verifier: independent re-check of register entries G-man-013, 040, 042, 045, 053, 060, 064, 067, 068 against the PDF pages and docs/ (2026-10-04).
Register: scratchpad/manuals/manual-delta-register.md (section 1 rows, section 2.7 rows C-12, C-22, C-23, C-39, C-40, C-50, I-24).
Method: every cited page rendered and read; digit-heavy crops re-rendered at 4x to 6x. Bengali digit glyphs in this app font that matter for every number below: **4 is drawn like a Latin 8 (open top), 8 is drawn like a 'b'/'V' loop, 7 is drawn like a 9, 9 is drawn like a curled 'a'**. The 4/8 swap is the main cause of the register's misreads (see G-053, G-064). Every number below was cross-checked by arithmetic (printed % against printed target and sales) wherever possible.
PDF page numbers = PDF page index (SR/AMO/TSO/Web manuals), same as the register's p-numbers.

## Verdict table

| Id | Verdict | One-line reason |
|---|---|---|
| G-man-013 | CONFIRMED | SR Sale History screen is on p23 and p34, online-only note printed on the SR page; spec has it only for AMO |
| G-man-040 | CONFIRMED | Points screen with Expiring Points and Expiry Date on p24; spec and schema have no expiry |
| G-man-042 | PARTLY | Cap, catalogue gap and dialogs confirmed; "one redemption per confirm" is wrong (a confirm carries a basket) and the rest of the catalogue is not in the PDF at all |
| G-man-045 | PARTLY | The two table shapes are per APP (SR vs AMO), not per route vs outlet; "chips ignored by the memo target" is not proven by any capture |
| G-man-053 | PARTLY | Screen and four metrics confirmed; TADS 68 is a misread of 64 (so TADS = target/14 fits both rows); rows are brand-named, not SKU-named; 2719% contradicts the 0% rows on the same account |
| G-man-060 | PARTLY | Cap and uncapped-detail rules confirmed; the proposed colour bands (green >= 90) are contradicted by 84% and 89% bars that are green |
| G-man-064 | PARTLY | CPR, "0%" zero-target and fractional targets confirmed; Black Diamond is round (218,700), so ten of ten non-zero rows fit; the factor is 15/17 (monthly x 15/17), not a bare "17" |
| G-man-067 | PARTLY | Calendar pro-rata fits TSO (26/30 exactly) and AMO Team Performance (about 25/30), but two other screens use other ratios (AMO report 15/17, SR ADS screen a 25-day month); rounding differs between roles |
| G-man-068 | PARTLY | Variant level, set header columns, WMO approval and start/end dates seen on Web p37; "WMO is level 1", the other statuses and the extra header fields are not evidenced; docs/08 already says "SKU/variant" |

---

## G-man-013 Sale History (SR and AMO), online-only

**Claim (register row 91).** A per-outlet, per-date SKU aggregate screen exists in the SR app (S-19/20 p23, S-32 p34) and the AMO app (p30); it is online-only ("mobile data must be ON"); the spec has no SR feature (only F-AMO-013); manual footer 20,260.00 vs rows summing 20,660.00.

**What I saw.**
- SR p23 ("পূর্বের সেল ডাটা দেখার প্রক্রিয়া"): outlet picker "Moin Store (DHK-344-005-[phone]-Apsis Cluster)", alphabet strip, two buttons **Sale History** and **Points**, and below them the out-of-range warning "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই" with Force Sale and Refresh. So Sale History and Points are reachable **before** the geo check passes. Right phone "সেল ডাটা": date field "November 26, 2025", sub-heading "2025-11-26", table এসকেইউ / পরিমাণ / মূল্য, 7 SKU rows, footer "মোট" with qty and value. Yellow note: "বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে" (mobile data must be kept ON). Callout: "...ইউজার যেকোন তারিখ এর সেল দেখতে পারবেন তারিখ নির্বাচন করার মাধ্যমে".
- SR p34 (sale entry after the outlet is selected): a full-width button "পূর্বের সেল ডাটা দেখুন" above the SKU list, so the screen is also reachable **after** the geo check.
- AMO p30: the same button on the AMO sale entry (route + retailer header) and the same "সেল ডাটা" screen with a date picker. The AMO page does **not** print the data-ON note.
- Table digits (4x crops): rows MaxR-10S 520 / 4,160.00; MaxR-20S 1,000 / 8,000.00; MaxB-10S 500 / 4,000.00; AB-12s 600 / 450.00; ABS 1,250 / 1,000.00; Aster 100 / 1,250.00; FB 600 / 1,800.00. Quantity column sums to 4,570 and the footer quantity reads 4,570.00 (ok). Value column sums to **20,660.00**; footer reads **20,260.00** (glyphs read twice). Per-unit prices are consistent (8.00 per stick for the three Maxim rows), so the rows are right and the footer is 400 short. The register's observation is correct.

**Spec says.**
- docs/06 Day flow step 4 (a-e) lists select outlet, geo check, call, sale, zero sale: no previous-sales view for the SR. docs/06 tile list has no history entry.
- docs/07 Sale: "Plus "View previous sale data" with a date picker." (AMO only).
- Plan side: lens-features F-AMO-013 (AMO only), F-API-025 "GET /memos?outlet=&date= ... AMO, SR".

**Verdict: CONFIRMED.**

**Corrected / refined statement.** SR Sale History exists, is reachable from the outlet picker (before and after the geo gate) and from the sale entry screen, shows one date at a time (SKU, qty, value, total), and the SR page prints that mobile data must be ON. The page does not say whether it covers only this SR's memos or every memo for the outlet, and the AMO page prints no online note (online-only for AMO is implied, not stated). The footer total in the sample is wrong by 400 (20,260 vs 20,660); do not use it as a golden value.

**Build implication.**
- Feature F-SR-054 as the register proposes; scope rule (this SR only vs all SRs on the outlet) goes to the AKTCL question list. Default ASSUMPTION: all memos for the outlet inside the user's scope, because the screen is titled by the outlet, not the user.
- Reachable without the geo gate: it must not require an in-range check and must not write anything. Make it a read from the local DB for the last `cfg.app.local_history_days` and `GET /memos?outlet=&date=` beyond that, with an offline banner. Never block selling on it.
- UI: label "পূর্বের সেল ডাটা দেখুন" on the button, title "সেল ডাটা", columns "এসকেইউ / পরিমাণ / মূল্য". Compute totals from the rows (integer minor units); add a test that footer == sum(rows).
- Spec gap to close in docs/06 Day flow 4 and docs/09 (F-API-025 caller list) when the plan is folded back.

---

## G-man-040 Outlet Points screen (expiring points, expiry date)

**Claim (row 118).** SR p24 and p62 show an outlet points screen with league label, balance, Expiring Points, Expiry Date and a previous-day as-of; the spec has redemption only and no expiry (loyalty_ledger has points_delta only).

**What I saw.**
- SR p24 ("Diamong League Point দেখার প্রক্রিয়া"): from the same outlet picker, the **Points** button opens a "পয়েন্ট" screen with an English dashed box "Manage retailer gift requisition and redemption." and a red button "রিডিম্পশন"; below it a card "Diamond League (April)" with a badge **110 Pts**, then "Expiring Points: **110 Pts**" and "Expiry Date: **2026-05-07**". Callout: "বিগত দিন পর্যন্ত আউটলেট এর অর্জিত Diamond League Point দেখতে Point বাটনে ক্লিক করুন" (points earned up to the previous day) and "...আউটলেট এর পয়েন্ট Expiring Point, এবং Expiry Date দেখতে পারবেন".
- SR p62 (Gift Redemption via the Loyalty Point tile > "রিডিম্পশন" tile): header strip "Diamond League (April)" with "২২০ Pts Remaining" (a different outlet, Bipu Store). No expiry shown on that screen.
- 2026-04-30 + 7 days = 2026-05-07, so the register's "month end + 7 days" reading fits the one data point.

**Spec says.**
- docs/06 Loyalty: "Redemption: pick outlet -> shows "Diamond League (<month>)" points remaining -> gift catalog with point cost ..." (no expiry).
- docs/10: "Diamond League (points): Outlets earn monthly points." No expiry rule. db/schema.sql `loyalty_ledger(id, outlet_id, points_delta, reason, program, at)` has no expiry or period column.

**Verdict: CONFIRMED.**

**Corrected / refined statement.** Add: the Points screen is a read-only per-outlet view (also reachable before the geo check), showing one card per league month with a balance badge, "Expiring Points" and "Expiry Date". Two cautions the register skips: (1) no as-of date is **printed**; the previous-day rule comes only from the callout text, so it is a rule to implement, not a field to copy; (2) in the only sample Expiring Points equals the whole balance (110 and 110), so it is unknown whether "Expiring Points" is a subset of the balance or the same number. The "Manage retailer gift requisition and redemption" sentence mentions requisition; nothing on any page shows a requisition screen.

**Build implication.**
- F-SR-055 as proposed. Schema: `program_period(redeem_until)` or `expires_on date` on each league month, nightly expiry ledger rows (`reason='expiry'`), redeem only from unexpired points. Config `cfg.loyalty.expiry_days` default 7 after month end is an ASSUMPTION from a single sample; mark "unknown; confirm with the business".
- The screen reads the bundle snapshot (points as of the previous day), so it works offline; the redemption screen must use balance minus local unsynced redemptions (G-feat-20 offline double-spend still applies).
- Seed a question: is "Expiring Points" a subset (points that lapse on the expiry date) or the full balance.

---

## G-man-042 Gift redemption: cap scope, catalogue, stepper and dialogs

**Claim (row 120).** Scope of the 199-point cap is undefined; the catalogue is only partly shown; '+' disabled when cost > remaining, '-' disabled at 0; one redemption per confirm; server rejects overdrawn balances; English dialogs "Confirm redemption of these items? Points will be deducted." and "Redemption successful"; tornado fan is 400 and the inventory's 800 is a misread.

**What I saw.**
- SR p62 left: "Diamond League (April) ২২০ Pts Remaining" (220). Cards: "প্রতি ১ পয়েন্ট এর জন্য ২ টাকা নগদ ক্যাশব্যাক।" cost badge ১; "২ স্টেপ কিচেন র‍্যাক।" ২০০; "চেয়ার।" ২০০; "টর্নেডো ফ্যান।" badge drawn as "৪০০" (the 4-glyph, same glyph as the 4 in RADS 45 on SR p11, so 400; it also equals docs/06). Stepper: '-' grey at 0, '+' teal on cash, rack and chair, **'+' grey on the fan** (400 > 220). Below the four cards the tops of **two more cards are visible** (cut off): the catalogue has at least six items.
- SR p62 right: cash stepper 20, rack stepper 1, header "0 Pts Remaining" (20 + 200 = 220), all '+' grey, '-' orange where > 0. So **one confirm carries a basket of several lines** (20 points of cash and one rack in the same screen).
- SR p62 callout: "...এবং সর্বোচ্চ ১৯৯ পয়েন্ট ক্যাশ রিডিম্পশন করতে পারবেন।" (up to 199 points as cash). Scope (per confirm, per month, per outlet) is not stated.
- SR p63: dialog "Confirm redemption of these items? Points will be deducted." with buttons "নিশ্চিত করুন" / "বাতিল"; second dialog "Redemption successful" with "ওকে". English text, inside a Bangla UI.

**Spec says.**
- docs/06 Loyalty: "gift catalog with point cost (cash back 2 Tk/point up to 199 pts; 2-step kitchen rack 200; chair 200; tornado fan 400) -> +/- per gift -> Confirm Redemption ("points will be deducted") -> success. -> `loyalty_ledger`, `redemption`."
- docs/10: "cash back 2 Tk/point (<=199 pts), gifts (kitchen rack 200, chair 200, tornado fan 400). Points deducted on confirm."
- db/schema.sql `redemption(client_uuid, outlet_id, gift text, points_spent int, cash_minor, photo_url, ...)`: one gift per row.

**Verdict: PARTLY.**

**Corrected statement.** Confirmed: cap wording (199) with no scope; stepper enable rules ('+' disabled when the item's cost exceeds the points still unallocated, '-' disabled at 0); both English dialog strings; fan = 400. Wrong or incomplete: (1) "one redemption per confirm" should read **one confirm submits a basket of several gift lines** (the screen shows 20 cash points plus a rack in one confirm), so the unit of idempotency is the confirm, with several lines; (2) the "rest of the catalogue" is **not in the PDF**: only four cards are fully visible and two more are cut off, so the data must come from AKTCL, not from another screenshot of this manual; (3) "the server rejects overdrawn balances" is a design requirement, not something the manual shows.

**Build implication.**
- Model: `redemption_batch(client_uuid, outlet_id, program_period_id, confirmed_at, business_date)` + `redemption_line(batch_uuid, gift_id, qty, points_spent, cash_minor)`; the current one-row-per-gift `redemption` cannot hold a basket under one UUID. Sync upserts by the batch UUID; server re-checks balance and rejects the whole batch, returning per-line reasons.
- Rules: remaining = balance - sum(lines); '+' disabled when unit cost > remaining; cash cap `cfg.loyalty.cash_max_points` default 199 with scope `cfg.loyalty.cash_cap_scope` (per_confirm default ASSUMPTION; question to AKTCL because the cap text is ambiguous).
- Strings: seed the two English dialogs verbatim plus Bangla; keep "Gift Redemption" title as printed.
- Question list: the full catalogue with costs and stock, and what happens when a gift runs out.

---

## G-man-045 Astha views (route vs outlet shapes, month chips, empty state)

**Claim (row 123).** Route view: STD table with five columns incl. বাকি, memo target one "All Brand" row. Outlet view: STD table without বাকি, memo target per brand. Month chips are ignored by the memo target. Year and quarter start empty ("তথ্য পাওয়া যায়নি"). A -20 target shows -37,500.00%. Babu Store Wing is "Dhaka" on p50 and "Gaibandha" on p51-52.

**What I saw.**
- SR p48: Astha tile; tabs "রুটের তথ্য" (default) / "দোকান ভিত্তিক তথ্য"; dropdowns Year and Quarter empty; body "তথ্য পাওয়া যায়নি". AMO p69 same, plus a route dropdown ("Savar Bazar(Sat, Mon, Wed)").
- SR p49 (route): Year 2025, "Q-4 (Oct-Dec)", chips Oct/Nov/Dec; STD table ব্র্যান্ড / টার্গেট / অর্জন / বাকি / % (five columns): Maxim 12,480 / 33,050 / 0 / 264.82%; Black Diamond 218,010 / 2,000 / 216,010 / 0.92%; Abul Bidi Style 0 / 6,250 / 0 / 0.00%; **Marise -20 / 7,500 / 0 / -37,500.00%**; Avon 4,180 / 5,000 / 0 / 119.62% (all arithmetic holds). Memo target tab: one row "All Brand" 28 / 6 / 22 / 21.43% with Nov + Dec ticked.
- SR p50: shop cards (Babu Store Wing "Dhaka"). SR p51 and p52 (outlet Babu Store; p51 left empty, p51 right STD unfiltered, p52 left memo unfiltered, p52 right STD with Nov ticked): outlet header Wing "Gaibandha". STD table has four columns (no বাকি); outlet memo target shows **per-brand rows** (Maxim 9, Black Diamond 9, Abul Bidi Style 0, Marise 9, Avon 9 ...). Selecting only Nov (p52 right) shows the same STD targets as the unfiltered view (p51 right).
- **AMO outlet view contradicts the SR outlet view.** AMO p72 right (Zafor Store): STD table has **five columns including বাকি**; AMO p73: the outlet memo target is **one "All Brand" row** (11 / 1 / 10 / 9.09%), identical with 0 chips and with Nov + Dec ticked. AMO p70 (route): "All Brand" 28 / 5 / 23 / 17.86%.
- The callout on every page says chips let you view one month or all three: "যেকোনো একটি মাস অথবা ৩ টি মাস একসঙ্গে নির্বাচন করেও ডাটা দেখতে পারবেন".

**Spec says.**
- docs/06 Astha: "Tabs: Route info | Shop info. Filters: Year, Quarter (e.g. Q4 Oct-Dec), months (multi-select within quarter). STD target by brand: Target, Achievement, Remaining, %. ... Memo target: "All Brand" target/achv/remaining/%." and "Guard against the data bug ... validate targets >= 0 and guard divide-by-zero in %."
- docs/07: "Astha: as the SR app but with a route selector (the AMO covers several routes)."
- docs/10 Astha: "Each Astha outlet has per-brand STD targets + a memo target; apps/report show target/achievement/remaining/%."

**Verdict: PARTLY.**

**Corrected statement.** Route view (both apps): five-column STD table, memo target one "All Brand" row. Outlet view **differs by app**: the SR outlet screen has four columns (no বাকি) and per-brand memo rows; the AMO outlet screen has five columns (with বাকি) and one "All Brand" memo row. The register's "route vs outlet shapes" is really "SR outlet vs AMO outlet" and should be built as one table component with a `show_remaining` switch per role, plus a memo-target view mode per role (`all_brand` for AMO, `per_brand` for SR); this is the "same screens as today" requirement. Month chips: no capture proves "the memo target ignores chips". Evidence is two AMO captures (p73) with identical numbers for 0 and 2 chips, and SR STD targets unchanged between 0 and 1 chip, with zero achievement in both; that is also what quarterly (not monthly) targets would give. The manual text says chips work. So **treat the behaviour as unknown** and keep `cfg.astha.memo_target_month_filter` only as an open switch (default: filter achievement by the chosen months, target is the quarter's). Empty initial state, -37,500.00% for a -20 target and the Dhaka/Gaibandha wing mismatch are all confirmed.

**Build implication.**
- Targets stay >= 0 at entry; at read show '-' for target <= 0 (deliberate change from the observed 0.00% and negative %, record as a D-id).
- Defaults: year and quarter default to the current ones (improvement; keep "তথ্য পাওয়া যায়নি" only for no data); 0 chips means the whole quarter (ASSUMPTION).
- Migration check: outlet geography mismatch (Wing differs between list and detail for the same outlet) goes to the import validation report.
- Open question for AKTCL and a request for a capture where achievement differs by month.

---

## G-man-053 SR Target and Achievement screen (ADS, TADS, PADS, RADS)

**Claim (row 131).** SR p10-11: a per-SKU Target and Achievement screen with ADS, TADS, PADS and RADS; RADS = remaining / remaining selling days (500/11 -> 45, 900/11 -> 82); TADS fits 500 -> 36 (14 days) but not 900 -> 68; PADS undefined; do not cap the percentage (the card shows 2719%); spec silent.

**What I saw.**
- SR p10 (dashboard): card "টার্গেট ও অ্যাচিভমেন্ট (SKU List)", one bar labelled "এস টি ডি", printed "২৭১৯%", link "বিস্তারিত". KPI strip on the same screen: 0/37 outlets visited, 0% strike rate, 1,500 issue, 1,500 stock, 37 non-visit, 0 no-sale.
- SR p11 ("টার্গেট ও অ্যাচিভমেন্ট দেখার প্রক্রিয়া"): list screen, one sub-tab "এস টি ডি". Each card shows a pack image, a **brand-style label** (Maxim, Avon, ARIS, Marise, Black Diamond ...), then two rows of four. Row 1: টার্গেট / অ্যাচিভ / বাকি / অর্জন; row 2: ADS / TADS / PADS / RADS. Values (6x crops): Maxim 500 / 0 / 500 / 0%, ADS 0, **TADS 36**, PADS 0, **RADS 45**; Avon 900 / 0 / 900 / 0%, ADS 0, **TADS 64**, PADS 0, **RADS 82**; ARIS identical to Avon; Marise identical to Maxim.
- Digit check: the TADS of Avon is the glyph pair "6" then the open-top "8-like" glyph = **4**, i.e. **64**, the same 4-glyph as in RADS "45" of Maxim. RADS of Avon is "8" (b-shape) "2" = 82. So the register's TADS 68 is a misread.
- Arithmetic: 500/14 = 35.71 -> 36 and 900/14 = 64.29 -> 64; 500/11 = 45.45 -> 45 and 900/11 = 81.82 -> 82. All four fit round-to-nearest with **14** and **11**. 14 + 11 = 25, which is the number of working days in April 2026 if Fridays (4) and Pohela Boishakh (14 April) are off (30 - 4 - 1). But 14 elapsed + 11 remaining implies a capture date near 19 April, while the screen header says 2026-04-22, so the date is unproven.
- The 2719% on p10 sits beside zero achievement on every visible row and 0 sales on the KPI strip, so the card figure cannot come from those rows; ui-reference/sr/home.md (a later capture of the same card) shows "১%". The card is not capped at 100, but the 2719% is not trustworthy evidence of a design rule.

**Spec says.**
- docs/06 Home: "KPI strip (from local data, reconciled on sync): Target & Achievement (STD progress bar %), Outlets visited x/y, Strike rate %, ..." Nothing about a drill-down or ADS metrics. (docs/ui-reference/sr/home.md UI-SR-03 notes the drill-down but defines nothing.)
- docs/10 KPI table: no ADS/TADS/PADS/RADS.

**Verdict: PARTLY.**

**Corrected statement.** Screen and four metrics confirmed and absent from the spec. Correct the numbers: **TADS is 36 and 64 (not 68)**, so TADS = target / 14 holds for both rows (no anomaly). RADS = remaining / 11 (rounded) holds. ADS and PADS are 0 in every sample because achievement is 0; their formulas (ADS = achieved / elapsed days is the obvious reading) and what 14 is (elapsed selling days, or a fixed divisor) are unknown. Rows are labelled by **brand**, not by SKU code (the title says "SKU List"), so the data level is brand or variant (see G-man-068). The 2719% card value conflicts with the 0% rows on the same account and should not be used to justify "no cap".

**Build implication.**
- F-SR-056 computed locally from bundle targets plus local sales; golden tests: target 500 -> TADS 36, RADS 45; target 900 -> TADS 64, RADS 82 at elapsed 14, remaining 11, achieved 0, using round-half-up to integer.
- Days basis: the 14/11/25 split points at working days with a holiday calendar (docs/22 P-notes: Friday off); this is evidence **for** `cfg.kpi.tilldate_basis = working_days` on this screen, in tension with the G-man-067 calendar-day conclusion for TSO/AMO (see there). Keep one basis key per surface.
- Rows are keyed at the target's product level (brand or variant), not SKU; the label stays "SKU List" for parity.
- Do not cap the card percentage; fix the card to use the same numerator/denominator as the rows; add a bundle test that card % = sum(achieved)/sum(target).
- Ask AKTCL: ADS/TADS/PADS definitions and what a non-zero PADS looks like (request a screenshot with sales).

---

## G-man-060 Target/achievement display rules (cap, uncapped detail, bands, drill-down)

**Claim (row 138).** Summary cards and bars cap at 100% (AMO, TSO); detail tables are uncapped with two decimals (1271.19% prints as is); SR card uncapped (2719%); remaining = max(target - achievement, 0); zero target shows '-' (AMO route report shows '0%'); observed bar colours 97% green, 70% and 56% yellow, 13-14% red -> propose red < 50, yellow 50-<90, green >= 90; category-tap drill-down; each TSO card's Details opens its own scope and the TSO has no route cards.

**What I saw.**
- TSO p19 (dark theme): Territory card "Cigarette 38960/3944 100%", "Bidi 10915/1425 100%", "Lighter 800/526 100%", "Match 900/510 100%" (achieved/target; real values 988%, 766%, 152%, 176%, all printed 100%). Details table (Till Date tab): ESSE Change Mango 195 / 500 / 0 / **256.41%**; Ananda Bidi 25 245 / 1875 / 0 / 765.31%; Special Abul Bidi 25 118 / 1500 / 0 / **1271.19%**; MAX 246 / 500 / 0 / 203.25%; Maxim Double Burst 136 / 0 / 136 / 0.00% (remaining 136 when achievement is 0; remaining 0 when over target). Cards: Territory and Zone only, each with its own Details button. No route cards. No thousands separator in the TSO table.
- AMO p34-36: zone card (monthly): cigarette 2,082,820/2,972,900 **70%** (amber), bidi 13,750/102,800 **13%** (red), lighter 6,312/11,200 **56%** (amber), match 5,276/5,447 **97%** (green). Route Agrabad: cigarette 142,600/128,900 **100%** (capped, real 110.6%), bidi 5,500/6,200 **89%** (green bar and green text), lighter 99/700 **14%** (red), match 431/325 **100%**; route Motiharpol bidi 1,500/6,600 **23%** (red). Till-date tab (p35 right): cigarette 2,082,820/2,475,477 **84%** (green), bidi 13,750/85,263 **16%** (red), lighter 6,312/9,325 **68%** (amber), match 5,276/4,529 **100%** (capped, real 116.5%).
- AMO p36: tapping the bidi bar opens the Agrabad screen with only the bidi bar and a three-row item table (Existing Abul Bidi/ 42 No., Abul Bidi Gold, Special Abul Bidi 25). Callout: "শুধু বিড়ি এর টার্গেট ও অ্যাচিভমেন্ট এর বিস্তারিত তথ্য দেখতে পারবেন".
- SR p10: card figure printed "২৭১৯%" (uncapped number; see G-man-053 for its reliability).

**Spec says.**
- docs/07 Team Performance: "4 category bars (Cigarette, Bidi, Lighter, Match: achieved/target, %; green/amber/red). Details -> brand table (Item, Target, Achievement, Remaining, %)." (no thresholds, no cap, no drill-down).
- docs/10: "Achievement % | sales / target (MTD or month) | bands: >=100, 90-100, 80-90, <80" and "Guard every % against divide-by-zero and against negative targets".
- docs/08 Target Status: Territory card + Zone cards with the four bars.
- The 1000 cap is a plan default (lens-config), not in docs/.

**Verdict: PARTLY.**

**Corrected statement.** Confirmed: cards and bars cap the printed percentage at 100 (AMO and TSO, several rows); detail tables are uncapped to two decimals; remaining is clamped at 0; the AMO bar drill-down by category exists (AMO only; TSO bars are not tappable in the manual); the TSO has Territory and Zone cards, no route cards. **Wrong: the colour thresholds.** Observed colours: green at 84%, 89%, 97%, 100%; amber at 56%, 68%, 70%; red at 13%, 14%, 16%, 23%. So green starts somewhere in (70, 84], amber/red boundary in (23, 56]. The proposed "red < 50, yellow 50-<90, green >= 90" would paint two observed green bars (84%, 89%) amber. Zero-target display ('-') is a deliberate change and the "AMO route report shows 0%" example is from p76 (G-man-064); in Astha a zero target prints 0.00%.

**Build implication.**
- `cfg.kpi.bar_bands` default must be re-set: ASSUMPTION green >= 80 (the only 80 in docs/10, fits 84/89 green and 70 amber), amber >= 40, red < 40 (23 red and 56 amber bracket the red line; 40 is a placeholder); mark "unknown; confirm with the business" and ask for one capture at 25-55%.
- Keep two display rules in config: `cfg.kpi.card_pct_cap = 100` for AMO/TSO summary bars and cards; no cap on detail tables and on the SR card; remove the global 1000 default for detail tables (the register is right that it would break the 1271.19% parity).
- Add AMO bar tap -> category-only details; no TSO equivalent.
- Golden tests: 38960/3944 prints 100%; 1500/118 prints 1271.19%; 136/0 prints remaining 136 and 0.00% in the manual (plan shows '-' for a zero target, logged as a deliberate change).

---

## G-man-064 AMO Sales Summary Up To Now (CPR, fractional targets, zero-target %)

**Claim (row 142).** CPR values 24.55-91.52 look like a strike-rate percentage; zero target should show '-' not the observed '0%'; nine of eleven targets x 17 give round numbers (Marise 88,235.29 x 17 = 1,500,000; Avon 8,823.53 x 17 = 150,000; ARIS 264.71 x 17 = 4,500; "Black Diamond gives 218,768"; Ananda Bidi is 0); ask AKTCL what 17 is; Maxim - Platinum Series sales read 3,780 so its 107.1% is consistent; define the row sort order.

**What I saw.**
- AMO p75: "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ" and the STD Memo Report with date range "April 1, 2026 - April 25, 2026" (header dates on AMO dashboards read 2026-04-26).
- AMO p76 left (list): Motiharpol CPR 79.67, memo 572; Chowmohoni 33.86 / 582; T&T 24.55 / 866; Commerce College 40.93 / 937; Pathantuli 46.55 / 1,180; Beparipara 50.17 / 1,159; Badamtoli 59.6 / 1,043; Shishu Park 34.86 / 696; Agrabad 91.52 / 680. Order is neither alphabetical nor by CPR. No unit printed after the CPR number.
- AMO p76 right (Motiharpol detail, columns ব্র্যান্ড / টার্গেট / বিক্রয় / % / মেমো), 6x crops: Marise 88,235.29 / 67,120 / 76.07% / 279; Black Diamond **12,864.71** / 53,410 / 415.17% / 268; Aster 617.65 / 844 / 136.65% / 56; Salmon 54.71 / 377 / 689.09% / 58; Avon 8,823.53 / **27,400** / 310.53% / 165; ARIS 264.71 / 280 / 105.78% / 10; Special Abul Bidi 1,764.71 / 1,000 / 56.67% / 3; Existing Abul Bidi/42 No. 352.94 / 500 / 141.67% / 1; Flame Box 23.82 / 12 / 50.38% / 1; Ananda Bidi 0.00 / 1,250 / **0%** / 3; Maxim - Platinum Series 3,529.41 / **3,780** / **107.1%** / 51. Every printed % reproduces from target and sales (for example 27,400 / 8,823.53 = 310.53%; 3,780 / 3,529.41 = 107.10%). The Ananda Bidi row prints "0%" while other rows print two decimals; Maxim prints "107.1%" (trailing zero dropped): raw number formatting.
- Fraction check on all non-zero targets: x 17 gives 1,500,000.0 (Marise); 218,700.1 (Black Diamond, so it **is** round, the register's 218,768 is not what the PDF gives); 10,500.1 (Aster); 930.1 (Salmon); 150,000.0 (Avon); 4,500.1 (ARIS); 30,000.1 (Special Abul Bidi); 6,000.0 (Existing); 405.0 (Flame Box); 60,000.0 (Maxim Platinum). Ten of ten non-zero rows, not nine of eleven.
- A cleaner factor: **x 17 / 15** gives integer monthly targets for every row: Marise 100,000; Black Diamond 14,580; Aster 700; Salmon 62; Avon 10,000; ARIS 300; Special Abul Bidi 2,000; Existing 400; Flame Box 27; Maxim Platinum 4,000. Cross-checks with other pages: AMO p34 route card Motiharpol **lighter 844 / 700** (monthly) matches this table's Aster sales 844 and monthly target 700 (617.65 = 700 x 15/17); the cigarette brands in this table give 100,000 + 14,580 + 10,000 + 300 + 4,000 = 128,880 against the monthly cigarette target 129,180 on p34 (a 300 gap that fits one unseen cigarette row below the fold); cigarette sales add up to 67,120 + 53,410 + 27,400 + 280 + 3,780 = 151,990 = the Motiharpol cigarette total on p75.
- Memo-to-CPR check: Motiharpol 572 memos / 79.67% -> 718 target-outlet calls (integer), Agrabad 680 / 91.52% -> 743 (integer), Badamtoli 1,043 / 59.6% -> 1,750 (integer); other routes do not give integers, so CPR is not simply memos / target-visits; successful calls and memos differ.

**Spec says.**
- docs/07 Report: "Sales summary till now (route list with CPR + total memos -> route detail: CPR, total memo, brand table Target[fractional]/Sales/%/Memo). Guard divide-by-zero on %."
- docs/10 KPI: "CPR | successful calls / target outlets on the day's routes | "strike rate" in the apps". Plan F-SYS-034: divide-by-zero shows '-'.

**Verdict: PARTLY.**

**Corrected statement.** Confirmed: CPR numbers read as percentages (range 24.55 to 91.52, no unit printed); zero-target row prints "0%"; targets are fractional with two decimals; Maxim Platinum sales are 3,780 and 107.1% is arithmetically right; the list sort order is undefined. Corrected: all ten non-zero targets give round numbers under the factor (Black Diamond is 218,700, not 218,768), and the exact relation is **printed target = monthly route target x 15/17** (about 0.882), not a bare "17". Whether 17 is the route's scheduled days in April and 15 the elapsed ones, or something else, is unknown; it is **not** the 25/30 calendar ratio seen on the AMO Team Performance screen, so this report uses a different till-date basis (see G-man-067). The register's question to AKTCL should be "what are 17 and 15".

**Build implication.**
- Do not round targets in storage; keep monthly integer targets and compute the till-date target at read with `cfg.kpi.tilldate_basis` per surface; the report's golden test is monthly x 15/17 at 2 decimals for the sample (Marise 100,000 -> 88,235.29).
- Define CPR for the month-to-date report as sum(successful calls) / sum(target outlets) over the same trading days, printed as a percentage with the % sign (improvement; parity prints no sign), and give the list a stated sort order (default: route code ascending; the observed order is neither name nor CPR).
- Zero target prints '-' (deliberate change from "0%", D-id); percentage format: always two decimals (the observed "107.1%" and "0%" are formatting artefacts, not rules).
- Ask AKTCL: what 17 and 15 are for Motiharpol in April 2026.

---

## G-man-067 Till-date target is a calendar-day pro-rata

**Claim (row 145).** TSO p19 shows all four till-date / monthly ratios as 26.0/30; AMO p35 (digits re-read) shows about 25/30 for cigarette, bidi, lighter, match; a Friday-off working-day basis would give 21/26 = 0.808; so default `cfg.kpi.tilldate_basis = calendar_days`, days elapsed through yesterday / days in month, item targets rounded up then summed; bidi till-date 85,263 (not 82,263); golden test with TSO numbers.

**What I saw.**
- TSO p19 till-date vs monthly: cigarette 3,420 / 3,944 = 0.8671; bidi 1,236 / 1,425 = 0.8674; lighter 456 / 526 = 0.8669; match 442 / 510 = 0.8667. 26/30 = 0.8667. Per-item rounding up explains 3,420 (vs 3,418.1) and 1,236 (vs 1,235.0). **Confirmed.** No date is printed on the TSO Target Status page; other TSO captures carry 26 April.
- AMO p35 (6x crops): monthly cigarette 2,972,900, bidi 102,800, lighter 11,200, match 5,447; till-date cigarette **2,475,477**, bidi **85,263**, lighter **9,325**, match **4,529**. Ratios 0.8327, 0.8294, 0.8326, 0.8315, i.e. **0.1% to 0.5% below 25/30 (0.8333)**, not at or above it (so the AMO rounding is not "ceil per item" like the TSO). The printed 84% (cigarette) and 16% (bidi) reproduce only with 2,475,477 and 85,263. Dashboard header on the AMO captures: 2026-04-26; the sales report on p75 ends 2026-04-25. A Friday-off working-day basis gives 21/26 = 0.808 (or 20/25 = 0.800 with the Boishakh holiday), which none of the numbers fit.
- Two other surfaces use other ratios: **AMO p76** (see G-man-064) printed target = monthly x 15/17 = 0.882; **SR p11** (G-man-053) divides by 14 and 11 (a 25-day month).

**Spec says.**
- docs/10: no till-date definition ("Achievement % | sales / target (MTD or month)"). docs/07 and docs/08 name the tab "Till Date Target" with no formula.
- Plan: lens-data D-07 "Till-date target = month_target x elapsed_fraction_working (working days per dim_date), falling back to the calendar fraction when cfg.kpi.tilldate_basis = 'calendar'."

**Verdict: PARTLY.**

**Corrected statement.** Confirmed: the spec has no definition and the TSO and AMO Team Performance numbers fit a calendar pro-rata (TSO exactly 26/30; AMO about 25/30), not Friday-off working days. Not established: (1) "through yesterday" for both roles: the TSO capture date is unknown (if it was taken the same day as the AMO captures, 26/30 vs 25/30 means the two apps count differently), so the 26 vs 25 split is an inference; (2) a single basis for the product: the AMO sales report (15/17) and the SR ADS screen (14 elapsed + 11 remaining = 25 days) disagree with calendar days; (3) the rounding rule: TSO rounds up per item, AMO sits slightly below the pro-rata so it truncates or rounds differently. The register's cross-check of 85,263 and the 82,263 misread are right.

**Build implication.**
- Do not hard-code one default for the whole product. Per-surface keys: `cfg.kpi.tilldate_basis.tso`, `.amo_team`, `.amo_report`, `.sr_ads`; defaults calendar_days for TSO and AMO Team Performance (parity), working_days for SR ADS (25-day April), and `route_days` for the AMO report (15/17) until AKTCL explains 17/15. Record a D-id for each choice.
- Rounding key `cfg.kpi.tilldate_rounding` per surface (TSO ceil per item then sum; AMO nearest or floor; unknown). Golden tests: TSO monthly 3944/1425/526/510 -> 3420/1236/456/442 at 26/30 with ceil per item; AMO monthly 2,972,900 -> about 2,475,477 (within 0.1%, not exact).
- The shared view `v_target_tilldate` must take days_elapsed and days_in_period as parameters from the surface, not from dim_date only.
- Questions for AKTCL: what each screen divides by; capture dates of the TSO page; whether routes have their own scheduled-day counts.

---

## G-man-068 Target model: variant level, approved sets, WMO, start/end dates

**Claim (row 146).** Targets are per **variant** (names like "Maxim Double Burst", "ESSE Change Mango", "MAX"); every monthly target **set** is a submission with a header (name, territory, product_type 'variant', target_type 'stt', start/end date, status, source, submitted_by) and approval statuses draft/submitted/wmo_pending/approved/rejected/returned; approver role WMO and **level 1 is WMO, not DMO**; start and end dates stored; auto-name "<Territory> of <Month>-<Year>"; 'stt' = STD; TSO screen = status + details + file download; spec: docs/03/10 category/brand/SKU, approval for revisions only.

**What I saw.**
- Web p35 "Set Target" (heading "Target Settings"): Wing / Division / Territory filters, "Choose Month: April 2026", **Apply** (manual route-wise input), **Download Sample**, and an "Upload Excel Panel Section". Menu: Target > Set Target, Target Allocation Report, Target Approve List.
- Web p37 "Target Approval Panel" (sidebar "Target Approve List"): table **Target Name | Product Type | Target Type | Start Date | End Date | Approval Status | Action**; one row "Phatherhat of April-2026" | **variant** | **stt** | 2026-04-01 | 2026-04-30 | **WMO approval pending** | eye and download icons. Callout: "আপনার মাসিক সেট করা টার্গেট এর Approval Status দেখতে ..." (to see the approval status of your monthly set target). Only one status value is visible. The manual contains no approver screen.
- TSO p19 and AMO p35-36: item rows are variant-level names; the details table is a single list with no category column and mixes categories (cigarette, bidi).
- Related: TSO leave shows "DMO approval pending" (TSO manual), so DMO is the TSO's leave approver; the target set is pending at WMO for the same kind of TSO login.

**Spec says.**
- db/schema.sql: `product_level text NOT NULL, -- 'category' | 'brand' | 'sku'`; `target_revision(... status, approval_level, approved_by, is_final ...)`.
- docs/03 Targets: "`target` | scope + product + month | STD target (can be fractional) and memo target, by route or zone, per category/brand/SKU" and "`target_revision` | requested change, config, date range, approval level, approver, final flag".
- docs/10 Targets and revisions: "A revision passes multiple approval levels (config, date range, status, next-approver level, approved_by, final flag)."
- docs/08 Target Status: "Details -> item table (Item at SKU/variant level e.g. "Ananda Bidi 25", "Special Abul Bidi 25", "Maxim Double Burst"; ...)" - so docs/08 already **mentions** variant-level items; only the schema enum and docs/03/10 lack it. docs/13 Q12: "the approval levels for a revision".
- docs/10 KPI: "STD (API: STT)", consistent with 'stt' = STD.

**Verdict: PARTLY.**

**Corrected statement.** Confirmed from the pages: variant level; the target set is a named object with product type, target type, start/end dates and an approval status; "WMO approval pending" exists for a monthly set (not only for revisions); 'stt' matches docs/10's STD/STT; the TSO web side offers status, details and a file download. Not established by any page: (1) that **WMO is level 1** (the one visible status can equally be the second step after a DMO or another role; the register's instruction to change the default so level 1 is WMO is a guess); (2) the other six statuses (draft, submitted, returned ...; only "WMO approval pending" is seen); (3) header fields territory, source and submitted_by (not shown; territory is implied by the filters and the name); (4) the naming rule: "Phatherhat of April-2026" suggests "<place> of <Month>-<Year>" but it is not proven the place is a territory. Also "MAX" is a one-word name, so variant names are not always brand + pack. The schema gap (no 'variant' in `product_level`, no set header, approval only on revisions) is real; docs/08 is not silent about variant items.

**Build implication.**
- Schema (forward-only migration): `target_set(id, name, territory_id, product_type, target_type, start_date, end_date, status, source, submitted_by, client_uuid)`, `target.set_id`, `product_level` += 'variant'; a `variant` level between brand and SKU with `sku.variant_id`; achievement rolls up SKU -> variant -> brand -> category.
- Approval: keep `cfg.target.approval_levels` as data; default ASSUMPTION a single level role `wmo` with the status text "WMO approval pending" reproduced; add a DMO level only if AKTCL says so. Mark "unknown; confirm with the business" for the order of levels, the live target during a pending set (Q12), and who the approver is (Web manual has no approver screen).
- Statuses: seed only what is observed ("WMO approval pending"), the rest are new states by design (D-id), so the set list shows the same text as today.
- Date range: store start_date and end_date (month = default range).
- UI (parity): Set Target page with Apply / Download Sample / Upload, Target Approval Panel with the seven columns above, eye (details) and download actions.

---

## Cross-cutting consequences for the plan

| # | Consequence | Entries |
|---|---|---|
| 1 | There is no single till-date basis in the current product: TSO 26/30, AMO team about 25/30, AMO report 15/17, SR ADS 14 + 11 of 25. Make the basis a per-surface config with a D-id each; the register's "default calendar for all" must be narrowed. | G-man-067, 064, 053 |
| 2 | Digit misreads (4 vs 8, 7 vs 9) occurred in the inventory and the register (TADS 68, Black Diamond 218,768, Avon 27,800). Every golden test value taken from these manuals must be backed by an arithmetic cross-check (printed % reproduces from target and sales). | G-man-053, 064 |
| 3 | Colour bands are observed data, not the docs/10 achievement bands: green >= about 80, red below somewhere between 23 and 56. Re-set `cfg.kpi.bar_bands` and ask AKTCL for one more capture. | G-man-060 |
| 4 | Astha outlet shapes differ between SR and AMO; build one component with role-driven columns. | G-man-045 |
| 5 | Redemption is a basket under one confirm; the current `redemption` table holds one gift per row and cannot keep a single idempotency key for the confirm. | G-man-042 |
| 6 | Questions to AKTCL (new or sharpened): scope of the 199 cap; full gift catalogue; meaning of Expiring Points; what 17, 15, 14 and 11 are; ADS/TADS/PADS definitions with a non-zero sample; whether WMO is the first approver; whether Sale History covers other SRs' memos. | all |
