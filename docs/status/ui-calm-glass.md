# UI "Calm Glass" implementation (laptop session, 2026-10-15)

Owner ask (2026-10-15, evening): design the apps from the vendor screenshots in `E:\Aron SR App Screenshots` and make the
SR, AMO and TSO apps and the web dashboard look like one designed product, not plain text. Branch `lane/ui-calm-glass`
(worktree `.claude/worktrees/ui-calm-glass`), cut from INT `26cfb26a` plus `origin/lane/web-dashboard` (36 unmerged web
commits, merged in so the web work does not conflict with that lane). Nothing is pushed until the owner says so.

## What the vendor app does (55 screenshots, read 2026-10-15)

Red-to-purple gradient header on every screen, white tiles with gradient line icons on Home, a 4-column tile grid, then a
Target and Achievement card (deferred, docs/27) and the KPI strip. Flows seen: Attendance (address card, two big
check-in / check-out buttons, a hold-to-confirm sheet), Stock (SKU rows with pack photo, red minus, purple plus, a
purple category-totals bar with Save and Print), Sale (retailer drop-down, dark alphabet strip, "start the call?" yes/no,
SKU cards with an Offer ribbon, a purple bottom bar with the cart total and "এগিয়ে যান"), Review (SKU table, discount
table, grand total, "mark as credit" checkbox, Product QC and Print buttons), QC (pack carousel, fault-type table), Memo
(outlet drop-down, tables, Print and Edit, credit box with "পরিশোধিত করুন"), Sales Summary (table plus Print), Sales
Submit (device vs server count table, sync and submit buttons), Outlet menu (New shop, Permanent close, Edit info,
Cluster) with forms, Sales Journey (visit cards with duration), Astha and gift selection (deferred). Everything we
reproduce functionally is already in the screen specs `docs/design/screens-sr.md` v1; this work implements the look.

## What changes

1. **Kit (`android/core-ui`)**: page gradient with two glows (tier A/B) or flat (C, sunlight); `AronScreen` scaffold with a
   glass top bar and a solid bottom action bar; a vector icon set (`AronIcons`, our own drawings, no vendor artwork);
   tiles with icon wells, label plates, badges and the target ring; hero money card, stat cards, meter; the quiet status
   chip (synced = no container); gradient primary button with press scale; opaque secondary button; banners with icons
   and five kinds; list rows with leading and trailing slots; empty states with an illustration; screen transitions.
2. **SR app**: Home rebuilt per `screens-sr.md` s2 (header, status row, hero, two tile groups, KPI row, stock and money
   cards, device line, next-step dock); every screen gets the top bar and bottom bar frame; the sale flow restyled.
3. **AMO and TSO apps**: the same frame and a real Home (header, status, tiles per `docs/07` and `docs/08`) instead of the
   placeholder text; tile destinations are designed empty states until their lanes land the features.
4. **Web**: `tokens.css` and `glass.css` per `docs/design/web-glass.md`, the Calm Glass shell (sidebar, top bar), stat and
   hero cards on the dashboard home per `docs/design/screens-web.md`, the login card.

## Verification

- `TokenContrastTest`, `HardcodedStringScanTest`, `KitGalleryTest` and the feature Robolectric tests stay green.
- Roborazzi goldens in `android/ui-screenshots` are re-recorded (the look changed on purpose) and the PNGs are checked by eye.
- The SR debug build is installed on the Honor X5c Plus from this laptop.

## Log

See the bottom of this file (appended as the work lands).
