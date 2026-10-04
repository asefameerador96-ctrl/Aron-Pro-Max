# 06 — SR App (the priority app)

The Sales Representative app. Bangla-first, offline-first. This is the app that must switch over with zero retraining, so match the current screens and flow. Below is every screen, what it captures, its validations, and its offline behaviour. Tables in `docs/03`.

## Setup / session

- **Install & bind:** after install, login (username + password). A new phone is bound with a **device OTP issued by the TSO** before it can sync. Store the binding in `device`.
- **Permissions:** location ("while using"), camera, (microphone is requested by the current build — confirm need), Bluetooth.
- **In-app update:** a "new update available → download & install" flow (the fleet side-loads APKs; keep an update-check endpoint and a lightweight in-app updater).
- **Settings:** language (English / বাংলা), "PDA to Support" (upload the device's data/sync file to support), Logout.

## Home

Header: `SR - <name> (<code e.g. sr334001>)`, route name + date (e.g. "Apsis RouteDaily, 2026-04-22").
Tiles: Attendance, Stock, Sale, Memo, Summary, Sales Submit (বিক্রয় জমা), Outlet, Tutorial, Task Delegation (badge), Astha, Loyalty Point, Photo Capture.
KPI strip (from local data, reconciled on sync): Target & Achievement (STD progress bar %), Outlets visited x/y, Strike rate %, Issue (qty), Current stock, Non-visit, No-sale.

## Day flow (the spine — build this first, end to end, offline)

1. **Attendance check-in** — GPS + time. Check-out only enabled from 5 pm. → `attendance`.
2. **Bluetooth printer pairing** — RPP02N-class 58mm ESC/POS. Connect from Stock screen; show connection state.
3. **Stock load** — issue by SKU from the distributor; print a stock memo. → `stock_issue`.
4. **Per outlet:**
   a. Select outlet from the route list (alphabet filter; label "name (code-phone-cluster)"). → opens `visit`.
   b. **Geo check** (`docs/05`). In range → start call. Out of range → **Force Sale** (reason + outlet photo) or Refresh.
   c. **During the call:** AV/KV marketing content; POSM survey + photo (`survey_response`); DRP empty-pack/slide collection with offer (`drp_collection`).
   d. **Sale:** enter SKU quantities → offers auto-apply → review (নিরীক্ষণ) → optional credit (বাকি) with partial payment → **Product QC** (production/transport fault quantities per SKU) → print memo. → `visit`, `memo`, `memo_line`, `qc_entry`.
   e. **Zero sale:** no SKU selected → confirm "do zero sale?" → review → print → done. → `visit.is_zero_sale`.
5. **Due collection** (বাকি পরিশোধ): Memo menu → outlet → "mark paid" (full or partial) → confirm. → `due_collection`; recompute outlet due.
6. **Sale edit:** Memo menu → outlet → Edit. Rules: must be inside the outlet geofence; outlets already QC'd cannot be edited; choose 1 of 3 edit reasons → re-enter. → new `memo` row `supersedes` the old.
7. **End of day:** Sync (device-vs-server counts: outlet, sale, stock, QC, promotion) → **Sales Submit** per the day state machine (warns if any retailer still has dues) → check-out (from 5 pm). TSO later does Final Submit per zone.

## Outlet management (from the app)

- **New shop:** route, shop name, owner, mobile → "Capture GEO & photo" → Save. → `outlet_change_request(type=new)` + `outlet_photo`.
- **Permanently closed:** route, outlet → Save → confirm. → `outlet_change_request(type=close)`.
- **Info change:** route, outlet, edit name/owner/mobile → must re-capture GEO + photo → Save. → `outlet_change_request(type=info)`.
  All three go to the AMO for verification, then web approval. The SR's app shows pending state.

## Astha (loyalty program for Astha outlets)

- Tabs: Route info | Shop info. Filters: Year, Quarter (e.g. Q4 Oct–Dec), months (multi-select within quarter).
- STD target by brand: Target, Achievement, Remaining, % (brands: Maxim, Black Diamond, Abul Bidi Style, Marise, Avon, Supreme, Special Abul Bidi, Abul Bidi Gold…). Memo target: "All Brand" target/achv/remaining/%.
- Shop info: Astha retailer list (name, owner, phone, sub-channel Diamond/Gold/…, wing/division/territory/zone).
- Guard against the data bug seen in the current app: a negative target produced a −37,500% achievement — validate targets ≥ 0 and guard divide-by-zero in %.

## Loyalty / Diamond League

- **Redemption:** pick outlet → shows "Diamond League (<month>)" points remaining → gift catalog with point cost (cash back 2 Tk/point up to 199 pts; 2-step kitchen rack 200; chair 200; tornado fan 400) → +/- per gift → Confirm Redemption ("points will be deducted") → success. → `loyalty_ledger`, `redemption`.
- **Photo Capture → Astha:** gift chosen for the outlet in the TSO portal (e.g. "27 pcs Dinner Set") → capture gift-distribution photo (one per outlet; "photo already captured") → submit. → `gift_photo`.
- **Photo Capture → Campaigns:** e.g. "Diamond League (March) Gift Verify" → per-redeemed-gift photo → submit.

## Task Delegation

- AMO/TSO assign tasks (type e.g. OOS; outlet; text; due date). SR swipes a task → Resolve → status Completed. → `task`.

## Tutorial

- Video list (may be empty). Keep a simple list screen fed by the backend.

## Offline behaviour (applies to all of the above)

Every screen reads and writes the local DB. Nothing in the day flow requires a signal. Sync is background/explicit. Memo numbering must be safe offline — use the `client_uuid` as the identity and a server-assigned human memo number on ingest (or a device-prefixed local number shown on the printed memo; confirm what the retailer expects in `docs/13`).
