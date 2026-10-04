# SR App — Home screen (reference)

Images: `home-1-tiles.webp` (top of the screen, right after login) and `home-2-kpis.webp` (scrolled down). Captured 2026-10-05 08:37 on a test account (`sr334001`, "Testing Banani", route "Apsis RouteDaily"). Bangla labels are transcribed with an English reading; the English reading is mine, the labels are the app's. Compared against `docs/06-sr-app.md` and `docs/03-data-model.md`.

IDs in this file are `UI-SR-nn`. Anything marked **NEW** is not in the existing spec; the manual-extraction run should confirm what it opens.

## Header

| Element | Shown | Notes |
|---|---|---|
| Avatar | generic user icon | no profile photo |
| Title | `SR - Testing Banani (sr334001)` | `SR - <name> (<username>)`, as in `docs/06` |
| Subtitle | `Apsis RouteDaily, 2026-10-05` | `<route name>, <date>`. The vendor name appears in the test route's name. |
| Settings | gear icon top right | opens Settings (language, "PDA to Support", logout; `docs/06`) |
| Status bar | a location pin is showing | the Android location indicator. Not conclusive, but check on a real device that the home screen is not holding a location request open (R4, `docs/04`). |

## Tile grid (4 columns)

| Row | Label as shown | English reading | In `docs/06`? |
|---|---|---|---|
| 1 | অ্যাটেনডেন্স | Attendance | yes |
| 1 | স্টক | Stock | yes |
| 1 | বিক্রয় | Sale | yes |
| 1 | মেমো | Memo | yes |
| 2 | সারসংক্ষেপ | Summary | yes |
| 2 | বিক্রয় জমা | Sales Submit | yes |
| 2 | আউটলেট | Outlet | yes |
| 2 | টিউটোরিয়াল | Tutorial | yes |
| 3 | টাস্ক ডেলিগেশন | Task Delegation | yes (badge not visible here: no pending tasks) |
| 3 | আস্থা | Astha | yes (tile uses the Astha brand logo) |
| 3 | লয়্যালটি পয়েন্ট | Loyalty Point | yes |
| 3 | Photo Capture | Photo Capture | yes. Label is English; every other tile is Bangla |
| 4 | **বিক্রয় যাত্রা** | **Sales Journey** (literally "sale trip") | **NEW (UI-SR-01)** |
| 4 | **কেপিআই** | **KPI** | **NEW (UI-SR-02)** |

## Cards below the grid

**Target & Achievement card.** Title `টার্গেট ও অ্যাচিভমেন্ট (SKU List)`; one progress bar labelled `এস টি ডি` (STD) showing `১%`; a `বিস্তারিত` (Details) link. **NEW detail (UI-SR-03):** the home card is a summary with a drill-down into a per-SKU target list. `docs/06` only mentions "STD progress bar %".

**KPI strip (scrolled view).** Values are in Bengali digits.

| Shown | Label | Reading | Check |
|---|---|---|---|
| ৬/৬০ | আউটলেট ভ্রমণ | Outlets visited 6 of 60 | 60 = target outlets on today's route |
| ১০% | স্ট্রাইক রেট | Strike rate 10% | 6 ÷ 60 = 10%: successful calls ÷ target outlets (`docs/10` CPR), not ÷ visited |
| ৮০০ | ইস্যু | Issue 800 | stock issued today |
| ৪১৯ | বর্তমান স্টক | Current stock 419 | unit not shown (sticks? pieces?) |
| ৫৪ | নন ভিজিট | Non-visit 54 | 60 − 6 |
| ০ | বিক্রি নেই | No sale 0 | zero-sale calls |

**Sales summary card (UI-SR-04, NEW).** Headline `বিক্রয় (স্টিক)` = Sales (sticks) = `০`. Right side, in taka:

| Label | Reading | Value |
|---|---|---|
| ম্যাচ | Match | 141.00 |
| লাইটার | Lighter | 4,687.50 |
| মোট | Total (gross) | 4,828.50 |
| ডিসকাউন্ট | Discount | − 437.50 |
| QC | QC | − 0.00 |
| সর্বমোট | Grand total (net) | 4,391.00 |

Arithmetic holds: 141.00 + 4,687.50 = 4,828.50; 4,828.50 − 437.50 − 0.00 = 4,391.00. Only the categories with sales (Match, Lighter) appear: confirm whether zero-sale categories are hidden or just absent here (the headline is sticks = 0, so no cigarette/bidi sold in this test).

**Second summary card (UI-SR-05, NEW).**

| Label | Reading | Value |
|---|---|---|
| মোট ডিসকাউন্ট | Total discount | 437.50 |
| DRP ডিসকাউন্ট | DRP discount | 0.00 |
| মোট QC | Total QC | 0.00 |
| সর্ব মোট | "Grand total" | 4,828.50 ৳ |
| মোট টাকা | Total taka | 4,391.00 ৳ |

Footer: `App Developed by Apsis Solutions` (vendor credit; the new app drops it).

## What this tells us (differences from the spec)

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-01 | A **Sales Journey** tile exists. The spec's tile list has no such screen. | Unknown feature. Read the SR manual section; if it is a visit plan / route-progress map, it needs a feature ID and an offline design. `MUST-CONFIRM`. | docs/06, 15 |
| UI-SR-02 | A **KPI** tile exists, separate from the home KPI strip. | Probably a fuller KPI page (target vs achievement by brand/category). Needs a definition and a source aggregate (`docs/10`). | docs/06, 10, 15 |
| UI-SR-03 | Home shows a Target & Achievement summary with a per-SKU drill-down. | The bundle must carry SKU-level targets and the running achievement; both computed offline from local data. | docs/04, 16 |
| UI-SR-04/05 | The home screen shows **live money totals for the day**: per-category value, gross, discount, DRP discount, QC deduction, net. | `memo` has only `gross`, `discount`, `net`. Needs the discount split into **offer discount** and **DRP discount** (and a **QC deduction** value), so these can be reproduced exactly and reconciled to the server. | docs/16 (schema v2) |
| UI-SR-06 | **QC is a money deduction** (`QC − 0.00` is subtracted before the net), not only a fault quantity. | The spec's `qc_entry` holds quantities only. The deduction value (quantity × price, or a rule) must be stored on the memo so the printed memo and the net match. `MUST-CONFIRM` the rule. | docs/03, 16 |
| UI-SR-07 | **The same Bangla word means different totals.** `সর্বমোট` is the *net* (4,391.00) on the first card but `সর্ব মোট` is the *gross* (4,828.50) on the second; `মোট` is gross on the first card. | Define canonical terms in the new app (gross, offer discount, DRP discount, QC deduction, net payable) and use one Bangla label for each. Retailers see these terms on the printed memo. | docs/17, localisation |
| UI-SR-08 | **Bengali digits (০–৯)** are used for every number, with a trailing `৳` for money and two decimals for taka. | Numerals are a display setting: store ASCII, render per locale. Add `cfg.locale.digits` (`bn` default, `en`). Confirm what the *printed* memo uses. | docs/19 |
| UI-SR-09 | Strike rate = successful calls ÷ target outlets (6 ÷ 60). | Confirms the CPR definition in `docs/10`. The denominator is the day's target outlets, so the day's route plan must be fixed at bundle time. | docs/10 |
| UI-SR-10 | `Issue` and `Current stock` have no unit. **Resolved by the Stock screen (`stock.md`, UI-SR-15):** 800 = 400 lighters + 400 dozen match; 419 = 25 + 394. The tiles add pieces and dozens together. | Show per category with its unit, or drop the combined figure. | docs/10, 16 |

## Assets

The tile icons, the Astha logo and the colour gradient are the current app's artwork. Before reusing any file, confirm AKTCL owns it (the Astha logo is AKTCL's brand; the icon set is probably Apsis's). Redraw or license the rest. Layout, labels and behaviour are what we reproduce.
