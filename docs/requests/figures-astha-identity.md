> **PARKED by D-LEAD-FIGURES-SIMPLE-01** (the `/v1/sync/figures` endpoint this file was written for is removed from the landing branch; the Astha identity predicate stays the loyalty lane's open question).

# Request to the loyalty lane: Astha identity answer on `POST /v1/sync/figures` (D-TKPI-14)

From: lane sr-targets-server. Status: the HOOK and the transaction sharing are built; the predicate and the redemption identity are yours (the lead assigns).

## What exists
`POST /v1/sync/figures` (contract `postSyncFigures`) builds the SR programme figures and the identity answer for the phone's memo probe in ONE read-only repeatable-read transaction. With `include_astha: true` the response also carries `astha` = the bundle's `ProgrammesSection`, built in the SAME transaction by `BundleProgrammes.section` (same handle, same snapshot as the memo figures and the probe answer). Nothing else is changed in the programmes loader.

## What the Astha section does today
It is a live snapshot through TODAY (not cut at `through_date`), reads `app.memo` by outlet, and its phone rule is the one of the programmes request: the phone adds its own memos that were not acknowledged when it sent the request and subtracts its own unacknowledged redemptions. That is the inference rule the SR figures no longer use.

## What the loyalty lane is asked to build
1. An `astha_probe` answer next to `astha`, with the same THREE states per probed memo uuid under Astha's own IN predicate: IN = stored, `status = 'active'`, not voided, `memo_kind = 'sale'`, not replaced by an active edit (`NOT EXISTS memo e WHERE e.supersedes_client_uuid = m.client_uuid AND e.status = 'active' AND e.voided_at IS NULL`), dated in the quarter window up to and including today, at an outlet of the section; VOIDED = held by the server and gone for good; NOT IN = everything else. The probe list is the one already sent (`probe`), so the phone sends no second list; the answer is computed with `SrFigureProbe`'s approach (lookup by `(client_uuid, business_date)` for the caller's own memos) in the same transaction.
2. The same identity for REDEMPTIONS (loyalty ledger rows of kind redemption): a probe entry type for redemption uuids, answered IN/VOIDED/NOT IN under the ledger's own predicate (posted or reserved), so the phone no longer subtracts "unacknowledged" redemptions by inference.
3. The phone rule for `astha`: the same three-state merge as the SR figures (`docs/requests/sr-programmes-phone-rule-b.md`), with Astha's window (through today) instead of `through_date`.
4. Tests through the real ingest, like `SrFiguresEndToEndTest`.

## Constraints
Read-only and idempotent; own records only; a foreign or unknown uuid is NOT IN and nothing else; the probe cap (300) applies to the shared list.
