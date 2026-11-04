# tso-manual #3 handover (laptop-fix lane)

Written 2026-11-04 (UTC) by tso-manual #3 at about 290k tokens. Docs only.

## State
- Candidate: `lane/tso-manual` = `lane/tso-manual-wip-laptop-fix` = **a5f59689cf5a2cdedf4897e2a021539bff95ac35** (laptop head 7f4230ad + ten fix commits + INT 69 `e1f7e5ef` merged). Lead's static gate PASS, CI 41764306032 green on this sha; train 70 CI dispatched by the lead.
- Fix commits (one per item): docs N1; zone_final (D-53); support_desk accept (D-54); feedback photo reach (D-55); AMO hubs accept (D-56); config_pull push (D-57); lock bodies name stock (D-58); SR/AMO lock-fixture Room barrier (red fix 7b6aa2cb); core-sync test call fix; Dhaka-date push query (D-59).
- My own full post-merge local run was at ~141 of ~256 backend classes, 0 failures, when I stopped waiting; the merged head's proof is the lead's CI.

## Rulings (DECISIONS.md, append-only)
D-TSO-MAN-53 zone_final = `zoneSubmittedSql` (R-W-03). 54 `Me.support_desk` description accepted. 55 feedback photo = FeedbackApi `ownerInReach` for every FEEDBACK_READERS role except TSO. 56 AMO hubs wording accepted (strings exist en+bn). 57 late-policy `config_pull`. 58 lock bodies name sales AND stock entries. 59 checker follow-ups.

## config_pull design (ask 11)
`ConfigService.configPull` (a `ConfigPullNotifier`, backend:platform) is called AFTER the transaction by `committing {}` wrappers around `create`, `decide`, `applyDue` (per change). `commit()` records items whose key is `cfg.day.late_sync_after_final_policy` into a ThreadLocal; cleared at entry, on exception and after the flush, so a rollback, a pending/refused/expired change or an idempotent replay pushes nothing. The key is class C3 (second SUPERADMIN approval is the commit) and global-scope only (V0006), so production pushes to every active user; `notify/ConfigPullPush` also resolves wing/division/territory/zone/route (tested, not reachable today). Message: kind `config_pull`, reason `late_sync_policy_changed`, `urgent=true`, `pull_after_s` 0..20, `notification_id`; respects `cfg.ops.push_enabled`. Phone: a `config_pull` is `CONFIG_URGENT` only; `PushMessage.REASON_LATE_POLICY_CHANGED` names the reason.

## Room barrier note (red fix)
CI-only failures of SrLatePolicyTest and SrRefusalHostsTest were never reproduced here (93/93 in isolation and full). Hypothesis: a write queued on Room's executors outlived `db.close()` and died "database closed" on a Room thread, landing in the next test's uncaught-exception handler. `SrLockFixture`/`AmoLockFixture.close()` now post a latch on `queryExecutor` and `transactionExecutor` before closing. Exact only for the serial transaction executor; deterministic form if it recurs: serial query executor on the in-memory builder. SrLatePolicyTest now prints the stack of whatever escaped. If "database closed" shows again, name the test and tell the lead.

## Residual asks
- db lane: `dw.v_route_day.zone_final_submitted` still follows `agg_daily_zone` route-day states; needs a dw view-contract bump to the `zoneSubmittedSql` rule (D-53).
- Non-blocking checker items left: LatePolicyConfigPullTest's after-commit check is weak (assert the new `config_version` is visible); the test uses 4 of 5 C3 approvals per hour; `PushTest` global-push assertion depends on no other live token in the class.
- Local env note: a container needs `tools/android-sdk.sh` and `sdk.dir` in git-ignored `local.properties`, and a local PG16 role with a password for `ARON_TEST_PG_URL`. Delete `tools/ci/__pycache__` before `NoSecretsInRepoTest`.
