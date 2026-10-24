# Owner's night package (2026-10-24 ~22:20 UTC; the owner sleeps until ~01:20 UTC)

Source: the owner's bedtime message with two Sales Submit screenshots (`docs/evidence/` not updated; the facts are below). The
owner gave blanket permission for the night ("you have all my permission"). Lanes read this file from `origin/lane/lead-docs-1022`
until it lands on INT. Rulings are DECISIONS bullets D-NIGHT-* on the same branch.

## Owner items and assignment

| # | Owner's words (condensed) | Ruling | Lane |
|---|---|---|---|
| 1 | When a phone comes online its data must sync at once; while online, sync at once; the dashboard must show it at once | D-NIGHT-SYNC-01 | android-tso #6 (app), web-dashboard #9 (refresh), backend-core #18 (projection check) |
| 2 | An SR must not check out without Sales Submit | D-NIGHT-CHECKOUT-01 | android-tso #6 (phone gate), backend-core #18 (1.9 queue row: risk signal) |
| 3 | Something is wrong with Sales Submit (screenshots) | D-NIGHT-DAYCLOSE-01 | android-tso #6 (screen), backend-core #18 (digest per device + rejected list, 1.9 row) |
| 4 | Printing works; keep 1 inch blank after the last line; Aron logo above "AKTCL" if possible | D-PRINT-08 | android-print #5 |
| 5 | The web dashboard map says no map key; wire the key (permission given) | D-NIGHT-MAPS-01 | web-dashboard #9 (deploy.sh env + BFF loader) |
| 6 | Are the data structure, database, pipelines and data lake right for later reuse? | lead review (Workflow), written answer | lead |
| 6b | Three test outlets in the dev seed: Asef Home 23.788347, 90.402357; Asef Office 23.791886, 90.420650; Asef Head Office 23.795805, 90.418034 | D-NIGHT-SEED-OUTLETS-01 | db #11 |
| 7 | MDM: lock every app except an allow-list (WhatsApp, calculator) during working hours, set from the portal | D-NIGHT-MDM-ALLOWLIST-01 | mdm #3 |
| 8 | A short, precise board deck: done, WIP, extras beyond Apsis | lead (Slides artifact, outside the codebase) | lead |
| — | "The Honor is connected to this PC through adb" | no laptop session is alive tonight (its environment was deleted on 2026-10-07); nothing in the cloud can reach adb; the morning report asks the owner to start one | lead |

## Facts from the screenshots (Sales Submit, build 1546, 2026-10-24)

- 10:46 local: mismatch banner "Sale: device 4, server 10. memo_line: device 23, server 29. Outlet: device 3, server 9. Visit ends:
  device 3, server 9." with "Sync again"; "5 records were rejected by the server."; table Outlet 3/9 Not matching, Sale 4/10 Not
  matching, Stock 4/4; Submit sales disabled; the Device/Server column headers wrap to "Devic e / Serve r".
- 12:04 local: "6 records were rejected by the server." with "Nothing to compare yet" in the table; Submit sales enabled.
- Reading: the owner installed several builds today under the same SR login; the server holds every upload of the day while the
  current install holds only its own rows, so "server > device" is expected and not an error. The rejected records (zero-price
  lines → price_invalid/schema_invalid, memo numbers from earlier installs → memo_no_duplicate, …) are shown as a bare count
  with no reason and no way forward.

## Rulings (full text in DECISIONS.md, D-NIGHT-*)

1. **D-NIGHT-SYNC-01 (sync at once, dashboard at once).** Phone: whenever the outbox has unsent rows, exactly one
   network-constrained WorkManager job stays enqueued, so the upload starts the moment connectivity returns (no polling, no
   receiver loop; a `NetworkCallback` may nudge it); while online a committed row keeps the 5 s debounce (`cfg.sync.debounce_s`),
   Sales Submit and check-out go at once as today; the periodic job stays as the safety net; docs/04 budgets hold (event-driven).
   Pulls (bundle delta, outlets, stock) run on the same trigger when their generation is stale. Server: the outbox projector and
   dirty-key rebuild run every 5 s (docs/33 C3) — verify on dev that a synced memo reaches `dw` aggregates within 15 s and say so.
   Web: dashboard tiles and tables refetch every 30 s while the tab is visible and on window focus (no polling in hidden tabs).
2. **D-NIGHT-CHECKOUT-01 (no check-out without Sales Submit).** SR and AMO: the Check-out action is disabled until the day's
   Sales Submit (`day_submit`) is committed locally (offline-capable), with the Bangla-first text "Sales Submit first" and a
   one-tap link to the Sales Submit screen; no bypass. Server: a check-out whose user has no effective `day_submit` for the
   business date raises a risk signal `CHECKOUT_WITHOUT_SUBMIT` (contract 1.9 queue row 5; `RiskSignalCode` is x-extensible);
   until the batch lands the server stores the check-out unchanged (the phone gate is the rule).
3. **D-NIGHT-DAYCLOSE-01 (Sales Submit screen and digest).** (a) Compare rule: the day is in sync when every committed device
   row is acknowledged by the server (device ⊆ server); a server count above the device count is information ("other devices or
   earlier installs of this login today"), never a mismatch; a device count above the server count, or an unacknowledged device
   row, is the mismatch. (b) Rejected rows are listed, not counted: type, memo number or outlet, the reason text from
   `ReasonTexts` (bn/en), and what happens ("stored and flagged", "dropped: duplicate memo number", …); a final rejection does not
   block Sales Submit; a retryable one does until re-synced. (c) The table header fits at 360 px (short labels "Phone" /
   "Server" or a smaller header type), no wrapping mid-word. (d) States are consistent: the rejected list shows whether or not
   the digest has run; "Nothing to compare yet" only before the first digest; Submit sales is enabled only under (a). (e) Server
   (contract 1.9 queue row 4): the digest returns, per record type, the counts for this login and business date AND for this
   device, plus the list of rejected records (client_uuid, type, code, final) so the phone can show reasons it did not keep.
4. **D-PRINT-08 (owner device result 2026-10-24).** The owner confirms printing works on signed build 1546 (stock slip and sale
   memo): P0 of 05:50 UTC closed. Follow-ups: every printed document ends with 1 inch of blank paper (8 lines of feed at 203 dpi
   58 mm, `ESC d n` or equivalent) after the last line so the tear-off never cuts text; the Aron logo printed as a raster above
   the "AKTCL" header line when the printer accepts `GS v 0` (optional; text-only fallback stays).
5. **D-NIGHT-MAPS-01 (web map key).** The owner permits the Maps browser key (GitHub secret `MAPS_WEB_KEY`, never printed) to
   serve the dev web portal: `infra/deploy.sh` passes it to the web container app as a secret-backed env `MAPS_WEB_KEY`
   (server side, read by `web/src/lib/maps/guard.ts` and the capped BFF loader `/api/bff/maps/load`); the `NEXT_PUBLIC_MAPS_WEB_KEY`
   build-arg is removed so the key is never inlined into client JS (docs/requests/web-dashboard-radius-map-maps-key.md); the
   dashboard and radius maps load through the BFF. The owner restricts the key to the dev host's HTTP referrer in the Google
   console (owner-only step, morning report).
6. **D-NIGHT-SEED-OUTLETS-01 (three test outlets).** `db/seed/06_dev_gulshan_banani.sql` adds three retail outlets on route
   GUL-SR-1 with the owner's names and exact coordinates (Gulshan–Banani test geography), priced like the other seeded outlets,
   visible to sr1001 on every seeded day. Privacy note for the owner: the repository is public, so these coordinates are public;
   the lead offers to move them to a GitHub-secret-fed seed step on request.
7. **D-NIGHT-MDM-ALLOWLIST-01 (working-hours app allow-list).** Yes: on a device-owner-enrolled phone Android allows it. The
   policy gains `work_hours_allowlist`: `{enabled, packages[], schedule: working hours from the calendar (or an explicit window),
   grace_min}`; the DPC enforces it with `setPackagesSuspended` (every launcher-visible package outside the list, the Aron apps,
   the dialler and system UI always allowed) during the window and lifts it after, applied on receipt, boot and app start on
   trusted time (docs/24 s "blocking schedule"); the portal MDM section gets the editor (a typed package list until the
   packages-catalogue endpoint exists: contract 1.9 queue row 6); enrolment of the Honor as device owner is a runbook step
   (`adb shell dpm set-device-owner` on an account-free phone, else factory reset + QR provisioning) that needs the owner's
   laptop session or hands; the device-lab row proves it. Kiosk (lock task) stays the stricter alternative already designed.

## Contract 1.9 queue rows added tonight (lead batch, not lane changes)

4. Sync digest per device + rejected list (backend-core #18 writes the shape).
5. `RiskSignalCode` + `CHECKOUT_WITHOUT_SUBMIT` (backend-core #18).
6. MDM packages catalogue endpoint for the allow-list picker (mdm #3 writes the shape; moved from the 1.8 queue note).

## Reporting

Every lane: one push per slice, candidate line to the lead (full sha, lane run id, jobs RAN vs skipped, checker verdict,
DECISIONS lines added), never red; HANDOVER + READY TO RECYCLE at ~430k. The owner cannot test tonight; the morning report
names the signed build to install and the checks to run.
