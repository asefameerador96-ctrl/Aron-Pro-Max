# tso-manual #4 handover (2026-11-04)

Train-71 candidate: `lane/tso-manual` = f9109e72e8f851aa5a60ddcb59bb3571b3f6cb16 (reconcile 7a58e9a8 + INT 07fc3159, docs-only merge). DECISIONS D-TSO-MAN-60..64.

## What was reconciled
The laptop's X4a fix round 4 (`ae2c886c9`) overlapped the cloud's D-TSO-MAN-57/59 and the Room-executor barrier. Merged on `lane/tso-manual-wip-reconcile`, six conflicts resolved.

## The one push (D-TSO-MAN-60, 62)
- Seam: `ConfigService.committed` (`ConfigCommitListener`, `h.afterCommit` inside `commit()`): once per committed version, after its transaction, for create, approve, the schedule tick and rollbacks; never for pending, refused, rolled-back or replayed changes. The cloud's thread-local `committing{}` is gone.
- `LatePolicyPush` (backend:sync) -> `ConfigPullNotifier` (user ids) -> `ConfigPullPush` (backend:notify, bulk broadcast). Recipients: SR and AMO users of zone-days of today or yesterday with a live Final Submit (`zoneFieldUsers`).
- Message: kind `config_pull`, urgent, data keys kind/reason/notification_id/pull_after_s/urgent only. Reason `late_sync_policy_changed` while the committed policy locks, `late_sync_policy_relaxed` when not (the phone then withdraws the "Day closed" notice). `day_state_changed` keeps pulling bundle + policy and withdrawing the notice.
- `pull_after_s` = random 0..`cfg.ops.config_pull_spread_s` (V0143; default 20; 0..900; read at send time). The phone clamps these two reasons to 900 s; other urgent pushes stay 20 s.
- Tests: `LatePolicyConfigPullTest` (listener: pending/replay/refused/rollback/other key), `ConfigCommitListenerTest`, `LatePolicyChangePushTest` (end to end: recipient set, no phone elsewhere, data-only, jitter default/0/…), `PushTest` (600 s window, 50 draws), `TsoPushReasonsTest` (withdraw, clamp), `SchemaV1aTest` (V0143 key in the ruled list).

## Harness (D-TSO-MAN-61)
Laptop `TestWork` kept, cloud barrier removed. Evidence: with a barrier-only close, `SrLockFixtureCloseTest` fails 2/2; app-sr 95 and app-amo 75 green 3 times each on the merged head (fast machine: the CI race does not reproduce locally either way, CI is the proof). Production surface: optional `parentJob` on SrDay/AmoDay (null on the phone).

## Residuals for the lead (D-TSO-MAN-62..64)
1. Ask 13 (judge a scheduled or time-limited late policy by `captured_at` in ingest): yes in principle, not done; needs a per-record as-of read of the ConfigResolver in the T1 ingest path.
2. Ask 14 fleet size: push fires on every committed change of the key, not only on a lock crossing; the two reads (zone-day users, live tokens) run on the committing thread; the listener takes a second pool connection inside `commit()` (a lost push only delays the phone to its next config delta). Filter on the crossing (needs the item's old value in the listener) and move reads and send to an executor; widen `cfg.ops.config_pull_spread_s` to minutes in the final account.
3. A policy scheduled to relax from a later time: the push at commit says `late_sync_policy_changed`; the phone unlocks at the start but its "Day closed" tray notice stays until another push. Push at the effective time, or withdraw on unlock.
4. Ask 12 kept the manual's TSO warning wording (D-TSO-MAN-63).

## Not run locally
`squawk` (not installed; V0143 is one idempotent INSERT with lock_timeout) and `assembleDebug` three times (once). Local gates otherwise: backend config/notify/platform/sync/db/app-push, android core-sync/core-database/app-sr/app-amo, ui-screenshots verify (0 failures, no golden changed), lintDebug, generators --check, date-pin, test_gates, exec bits, money-float.
