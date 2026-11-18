# Contract 1.20.0: waiting quarantine items (lane portal-loyalty-api, landed with train 118)

Contract 1.20.0 and migration V0204 are FINAL (lead, 2026-11-18, by landing order: 117 sr-server V0201..V0203 / 1.19.0, then this lane). Additive only; no request member, no phone-visible record change, no breaking change (oasdiff against INT 39db6c734: 0 breaking).

## What changes (response members of `GET /v1/admin/quarantine`, `listQuarantine`)

| member | where | meaning |
|---|---|---|
| `waiting_count` (integer >= 0, optional) | `QuarantinePage` | The open items that wait for another parent inside the caller's reach (server-side scope, the list's predicate), whatever the page, status, code, zone and date filters |
| `waiting` (boolean, optional) | `QuarantineItem` | True for an OPEN item whose first parent was accepted and whose other parent is not stored yet; `code` stays the first parent's code and is not the item's own state |
| `waits_for_uuid` (uuid or null, optional) | `QuarantineItem` | The parent the item awaits; null when not waiting |
| `waits_for_refused` (boolean, optional) | `QuarantineItem` | True when the awaited parent is refused or discarded (refreshed at every sweep pass): the item is not released automatically while it stays refused; discarding it is the way out |

## Behaviour behind the members (D-LOY-API-28 to -31, -33)

* The waiting marker is typed columns on `app.sync_quarantine` (V0204); a trigger clears it when the item is closed, re-opened or changes its code, and a CHECK refuses a marker on a non-open row.
* The sweep runs in the API process with the API role (least privilege), is switched by `cfg.sync.waiting_sweep_enabled`, `cfg.sync.waiting_sweep_interval_s`, `cfg.sync.waiting_sweep_batch` (registered by V0204) and the environment variable `ARON_WAITING_SWEEP=off`.
* A release is exactly what the reviewer's accept would have done for that child at that time (the override stored with the wait: none for a child of another uploader or device), every check that had not yet run runs, and a hash-chained audit row (via job, actor none) is written in the same transaction. A child whose own check then holds it becomes an ordinary held item (`waiting` false) with its own code and reason.

## Not changed

The sync answer (`resolutions[]`) is unchanged: the phone is told nothing final for a waiting child.
