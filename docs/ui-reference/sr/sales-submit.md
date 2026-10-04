# SR App — Sales Submit / Deposit (reference)

Image: `sales-submit.png`. Test account `sr334001`. Manual references and existing gaps: G-man-031 (reconciliation rows), G-man-030 (dues and submit), G-man-006; `docs/04` day state machine, `docs/06` day-flow step 7.

## Statement by AKTCL (2026-10-05)

> This is the sales submit option that lets the whole day's sales be uploaded to the cloud at day end.

## What the screen shows

| Element | Shown | Reading |
|---|---|---|
| Title | `বিক্রয় জমা`, shield icon | Sales submit (deposit) |
| Device status | `ডিভাইস স্ট্যাটাস` + green dot + `অনলাইন` | Device status: Online |
| Table | columns `বিষয়` / `ডিভাইস` / `সার্ভার` (Subject / Device / Server) | on-device totals vs server totals |
| Row Outlet | `আউটলেট` 6 / 0 | outlets visited |
| Row Sale | `বিক্রয়` **381** / 0 | quantity |
| Row Stock | `স্টক` **800** / 0 | issued |
| Row QC | `কিউসি` 0 / 0 | |
| Row Promotion | `প্রমোশন` **437.5** / 0 | the discount amount |
| Red notice | `আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই অপশন থেকে বিক্রয় জমা সাবমিট করুন।` | "Before finishing today's work you must sync all operation data and submit the sales deposit from this option." |
| Button 1 | red `ডাটা সিঙ্ক করুন` | Sync data (active) |
| Button 2 | grey `বিক্রয় জমা` | Sales submit (disabled until synced) |

## Cross-checks

- Sale **381 = 375 lighters + 6 dozen match**: a sum across **different units**.
- Stock **800 = 400 + 400** (the issued totals, again summed across units).
- Promotion **437.5** is the discount value: "promotion" and "discount" are the same number under two labels (Home, Summary and Memo say discount).
- Server column is all zero: nothing has been uploaded yet.

## Findings

| ID | Observation | Consequence | Where |
|---|---|---|---|
| UI-SR-35 | In the current app, **upload happens when the SR taps Sync**, and the notice tells reps to sync everything before submitting. | AKTCL's requirement R5 is "offline data goes to the cloud immediately". The new app syncs in the background as soon as a connection exists, so by the time the SR opens this screen the Server column should already match. Keep the **Sync data** button (manual retry) and the notice, rewritten for the automatic case. | docs/17 |
| UI-SR-36 | The reconciliation compares **unit-less sums** (Sale 381, Stock 800). A missing memo of 6 dozen would look identical to 6 missing lighters in some cases. | Reconcile by **record counts per entity** (visits, memos, memo lines, QC, stock lines, attendance, outlet requests) and by **money per category in minor units**; show the legacy five rows for parity if the business wants them, but gate on the exact checks. | docs/16, 17 |
| UI-SR-37 | **Sales submit stays disabled until sync completes.** Enabling rule (device equals server for every row?) and what dues do (warn vs block, G-man-030) are not visible here. | Enable submit only when every pending record is acknowledged and the counts match; dues only warn (default, `cfg.day.sales_submit_dues_warning`). `MUST-CONFIRM`. | docs/19 |
| UI-SR-38 | A **device status: Online** indicator shows connectivity. | Show a connectivity state in the header on sync-related screens; derive it from the last successful server contact, not just a network flag, so "online but blocked" reads as offline. | docs/17 |
| UI-SR-39 | Notice text and button labels are fixed Bangla strings. | Move them to the string catalogue; add the failure and offline texts the manuals never show (G-man-101). | docs/17 |
