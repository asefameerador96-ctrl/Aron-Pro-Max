# TSO manual build: laptop handover to the cloud lead (2026-11-04)

The owner's word to the laptop session, 2026-11-04 in chat: **hand the build over to the cloud lead**, and **late sales after
Final Submit are accepted and flagged**. This answers ALIGN-01 owner questions 1, 3 and 5
(docs/status/tso-manual-alignment-2026-11-03.md). Decision lines: D-TSO-MAN-HO-01 to HO-03 in DECISIONS.md.

From now on the laptop session pushes nothing else for this build. It never pushes the integration branch or
`lane/tso-manual`.

## The branch

`lane/tso-manual-laptop-wip` (laptop branch `tso-man/handover`) is built as follows:

- Base: `lane/tso-manual` head `86236433b`, the cloud's batch 1 including D-TSO-MAN-49 and D-TSO-MAN-50.
- Merged into it, with no conflicts:
  - lane X2: the server members for the TSO app.
  - lane X4a: the SR and AMO phones under the day lock.
- Added after the merges: the late-policy change on the phones.

The cloud integrator can merge it into `lane/tso-manual` as one head. Nothing in it rewrites cloud history.

| Piece | Laptop branch head | What | Proof |
|---|---|---|---|
| X2 | `ba26b7ccf` | R-W-03: one Final Submit formula, with an end-to-end test. R-W-04 docs. R-W-06 members: `can_submit`, `tilldate_target_achievement_bp`, `monthly_target_qty` and `working_days`, `sr_user_id`/`sr_name` on bundle routes. R-W-08 PortalLevels. R-W-09 support desk rules. R-W-17 docs. R-W-19: `monthly_target_qty` is always `<n>.000`. R-W-20: `by_category` counts sold quantities only, and the contract note says so. R-W-21: D-X2-13 and D-X2-14. D-X2-17: a feedback photo reaches no further than the feedback list. | docs/status/tso-manual-X2.md. Two independent checker rounds; the last found five lows, all fixed, each with a negative proof. |
| X4a | `1e507e96e` | R-W-11: the SR and AMO phones honour the Final Submit lock. R-W-22: `stock_movement` locked as on the server, and no stock slip after the lock. R-W-23: no capture refusal escapes a host; every refusal is worded and nothing is thrown, including a skip after the SR's own Sales Submit. | docs/status/tso-manual-X4a.md. Three independent checker rounds; every medium and high fixed, with negative proofs. |
| Late policy on the phone | fix round 4 (`20153af0c` and the status commit after it); round 3 `f3787d393` | Owner decision: V0142 sends `cfg.day.late_sync_after_final_policy` to the SR and AMO phones. `FinalSubmitLock.lockApplies` is true only for quarantine or reject; an absent key or unreadable value counts as accept_and_flag, the server's fallback. Every lock use goes through it: captures, Mark Paid, the stock slip, call guards, relaunch, banners, check-out. The "Day closed, new sales are closed" push is sent only under quarantine or reject; a close under accept_and_flag sends the silent `day_state_changed`. A time-limited policy stops at its end. Call guards read the policy at trusted time. Fix round 4: a committed change of the policy nudges the SR and AMO phones of the closed zone-days of today and yesterday, so a revert to accept_and_flag unlocks them at once and a quarantine set after a close locks them at once. `day_state_changed` also pulls the policy and takes the "Day closed" notice off the tray. The config delta now carries a later-added end. | X4a status, sections "Late policy on the phone", "Fix round 3" and "Fix round 4". Round 3's checker found one high (the push text) and one medium (an expired policy kept locking). Round 4's checker found one medium (the phones stayed locked after a revert) and four lows. The high and both mediums are fixed, with negative proofs. Of the lows, the ConfigDelta end and the doc residue are fixed; the ingest window (ask 13) and the TSO warning text (ask 12) are recorded, not changed. |

Merge fix: X2's `FinalSubmitProjectionEndToEndTest` now switches the midnight auto close on in its setUp, as
`AutoFinalSubmitJobTest` does. The cloud ships auto close off (D-TSO-MAN-49).

Test harness fix (commits `14f6119ed`..`f48773c4c`):
- **Failure.** Your all-areas run 41750277921 on `7f4230ada` failed only `:android:app-sr:testDebugUnitTest` (2 of 93).
- **Cause.** A MemoViewModel that SrLatePolicyTest created was never cleared. Its reload after Mark Paid ran after the fixture closed the database ("connection pool has been closed"). The same exception then surfaced as `UncaughtExceptionsBeforeTest` in SrRefusalHostsTest.
- **Fix.** A test-only `TestWork` in app-sr and app-amo holds every view model, every scope and the Room executor. A fixture's `close()` now:
  1. lets the work in flight finish on the open database;
  2. clears and joins it;
  3. only then closes the database.
- **Production.** The one production change is an optional `parentJob` on SrDay and AmoDay. It is null on the phone, so the phone behaves as before.
- **Tests.** No assertion was weakened. New `*LockFixtureCloseTest` classes pin the order of shutdown. An independent checker confirmed the root cause from the CI report.
- **Where it ran.** The race did not reproduce on Windows. Full app-sr (95) and app-amo (75) passed on the laptop. Linux proof is the next CI run.

## What ran, and where

- **Laptop (Windows, JDK 21, local PostgreSQL 16).**
  - Only the targeted test classes of each change, plus their negative proofs. The class lists are in each status file's Verify section.
  - Generator drift checks on the merged branch: slices, WireDtos and PayloadShapes are up to date.
  - Scans: money-float 13/13 allowed, date-pin 0 new, KPI corpus up to date.
- **Not run on the laptop.** No full module suite. The single full backend run, stopped after 3 hours, had passed sync, media and app, with auth failing only on RoleGrantsTest, which is fixed on this base. Also not run: assembleDebug, lint, squawk and oasdiff.
- **Linux CI on this branch.**

  | Run | Commit | Result |
  |---|---|---|
  | 41749105748 (push) | `7f4230ada` | success |
  | 41750277921 (your all-areas dispatch) | `7f4230ada` | green except app-sr 2/93; that is the harness leak, fixed above |
  | 41755403153 (push) | `67f109d40` (fix round 4) | backend, backend app, both analytics shards, repository gates, containers, release APKs and Android green; web and infra skipped by the changes filter |

- The final all-areas `workflow_dispatch` run on the branch head is the cloud lead's to trigger.

## Open for the cloud lead

1. **All-areas CI on the branch head.** The goldens: X2 and X4a add no screenshot cases and change no TSO screen. Confirm in the
   verify run that the cloud's 415 goldens still pass.
2. **Lane decisions to ratify.** They are written in the two status files: D-X2-13 to D-X2-17, D-X4a-13 to D-X4a-31. The laptop lead accepts them (D-TSO-MAN-HO-03).
3. **X2 open asks.**
   - Ask 1: the `Me.support_desk` contract description.
   - Ask 3: whether R-W-03's single formula also covers the final-submit-status report's `zone_final` and `dw.v_route_day.zone_final_submitted`.
   - Ask 5: whether plain zone readers should read feedback photos exactly as far as the feedback list. With today's data both rules agree.
4. **X4a open asks.**
   - Ask 6: the AMO hubs' text after the AMO's own supervisor-day submit.
   - Ask 7: whether `sr_lock_body` and `amo_lock_body` should name stock. This matters only under quarantine or reject.
   - Ask 11: answered by fix round 4. A committed change of the late policy now nudges the SR and AMO phones of every zone-day closed today or yesterday (D-X4a-32).
   - **Ask 14 (before the final account is sized for the fleet; the fix round 4 checker's medium):** since every zone closes every day, that after-change push reaches nearly every SR and AMO. It runs on the committing request's thread: the second SUPERADMIN's approval, or a phone's `/config/delta` when a scheduled change falls due. It makes per-user `nudge()` calls into PushNotifier's 4-thread pool with its 2,000-slot discarding queue. At 8,500 users that holds the request for tens of seconds, and nudges are dropped silently. The fix: move the fan-out to an executor, send through a bulk token path, and push only when the policy crosses between locking and non-locking. There is no effect at pilot size.
   - Lows from the same check, for a later round: (a) under the default, `day_state_changed` makes each AMO pull the config once per closed zone in its scope, and that counts toward ResumeConfigCheck's 24-a-day cap; pull only when the phone holds a locking policy. (b) The push reads the committed policy while ingest reads a 30-second DbServerConfig cache: expire the cache on that key's commit (D-X4a-36). (c) Under a locking policy, yesterday's zones get a "Day closed" notice with no date. A queued nudge for the same user, such as `task_assigned`, absorbs the policy nudge.
   - Ask 12: the TSO's Final Submit warning text ("Submitting locks the day for this zone."). Keep it as the owner's manual words it, or soften it?
   - Ask 13: should ingest judge an admin-set scheduled or time-limited late policy by the record's `captured_at` (D-X4a-36)?
5. **Ruled here (D-TSO-MAN-HO-03).** X4a ask 8 is accepted as built. Under accept_and_flag the TSO's Final Submit no longer opens the SR's check-out; the rep's own Sales Submit does, as on any day. That is the owner's "nothing changes on the phone".
6. **Device lab, s8 row 26, two variants.**
   - Default: a sale after Final Submit saves and prints with no banner and no "sales closed" notice, and the server flags it.
   - Admin-set quarantine or reject: the X4a lock text, no stock slip, and the "Day closed" notice.
   - Fix round 4, variant 3: after a locked close the admin returns the policy to accept_and_flag. Within the push spread
     (0 to 120 s, online), the banner and the "Day closed" tray notice go, and calls, sales, Mark Paid and the stock slip work again.
7. **Owner questions from ALIGN-01 still unanswered here.** Q2: confirm D-TSO-MAN-02 and -13. Q4: auto final submit on or off for the pilot. Q6: row Y.
