# T1 check of the CI migrations-base fix (lane/ci-migrations-base afbbe9a052), 2026-11-25

## gate-logic: FAIL (blocking raised 1, upheld 1)

Verdict FAIL because of one blocking hole. When lane and INT history criss-cross, the new lane base can miss an edit to a migration INT has shipped (reproduced: rc 0 instead of 1), and 61 of 360 lane refs in the repo have more than one merge-base with INT.

The rest checks out against the code:
- Event and branch matching is right: push and workflow_dispatch on refs/heads/* other than main and INT count as a lane; INT, main, pull_request and non-branch refs keep the gate base.
- `mig_line="$(...)"` under the Actions default `bash -eo pipefail` makes a script failure fail the step, so nothing falls back silently.
- With fetch-depth: 0, origin/<INT> is present in the checkout, and the missing-ref and unrelated-history cases exit 1 with nothing on stdout.
- migrations-check.sh is unchanged.
- A lane that is behind or level with INT gets base == HEAD, which is harmless because everything in it is already shipped.
- No secrets are printed.
- The 7 MigrationsBase self-tests pass in a /tmp copy, which has since been deleted. One of them (the 'merged INT' case) is a fast-forward and cannot tell merge-base from HEAD; it is listed in lows.

Fix: use `git merge-base --all` and either fail when there is more than one base or check every base, then add a criss-cross self-test.

### UPHELD: When a lane and INT have more than one merge-base, the gate can compare with an older one and miss an edit to a shipped migration

tools/ci/migrations-base.sh

The script runs `mb="$(git merge-base "origin/${integ}" "${sha}")"` without `--all`. With criss-cross history (a train merges lane commit A into INT, and the lane merges an older INT commit I0), git returns more than one best common ancestor and picks one of them. It can pick A, which lacks the migrations INT shipped at I0. I reproduced this in a throwaway repo. Base: V0001..V0003. INT I0 adds V0004. INT I1 merges lane commit A. The lane merges I0 and then edits V0004. `git merge-base --all` gives A and I0, and plain merge-base picks A. migrations-base.sh printed A, and `migrations-check.sh A` printed 'checksum: 3 shipped ... order: 1 new migration(s), highest shipped version 3, migrations: ok', rc 0. The same check against I0 fails ('V0004__other.sql was shipped at 321c742 and changed since', rc 1). So a migration shipped on INT is edited and the gate stays green. The old rule (last green lane head) would have caught this once that head contained V0004, so this is a loosening. It is not hypothetical: in this repo 61 of 360 origin/lane/* refs have 2 to 7 merge-bases with origin/claude/wonderful-thompson-k6ejnf (for example lane/lead-train113, lane/train-20261018T0405-lb). lane/train-* branches are lane pushes and get this rule. Fix: take `git merge-base --all` and either fail loudly when there is more than one, or run migrations-check.sh against every base (all must pass). Then add a criss-cross case to test_gates.py MigrationsBase.

Votes: upheld: I could not refute this from the code; the defect stands. tools/ci/migrations-base.sh on origin/lane/ci-migrations-base runs `mb="$(git merge-base "origin/${integ}" "${sha}")"` without `--all`. When the history is criss-cross, git returns just one of the best common ancestors. Nothing checks how many bases there are, and nothing runs the check against each one.

ci.yml passes that single sha as MI; upheld: I could not refute it, and I reproduced it. On origin/lane/ci-migrations-base, tools/ci/migrations-base.sh runs a plain `git merge-base "origin/${integ}" "${sha}"` (no `--all`) and prints exactly one base. In ci.yml, the Migrations step on a lane push compares only with MIGRATIONS_BASE; nothing else checks the lane against CHECK_BASE or against the other merge-bases.

I rebuilt the reviewer's cris; upheld: I could not refute it; the defect is real. tools/ci/migrations-base.sh on origin/lane/ci-migrations-base runs plain `git merge-base "origin/${integ}" "${sha}"` with no `--all` and no check for more than one result. I rebuilt the reviewer's criss-cross history in a throwaway repo under /tmp, since deleted: base V0001..V0003, INT I0 adds V0004, INT I1 merges lane commit A, the lane merges I0 and the

Lows:
- test_a_lane_that_merged_the_moved_integration_branch_is_green_and_uses_the_merge_base does not test what its name says. lane_from(self.stale) followed by `git merge int_tip` is a fast-forward (stale is an ancestor of int_tip), so the lane has no merge commit and no work of its own. HEAD == int_tip, merge-base(X,X) == X, and judge(base) compares HEAD with itself. A script that wrongly returned HEAD (the fallback the design forbids) would pass this test. Use `--no-ff` and add a lane commit (with a new migration) so the base really differs from HEAD. The stale-last-green test does tell merge-base and HEAD apart, because of its empty lane commit.
- The shallow-clone branch of migrations-base.sh (unshallow once, then `::error::`) has no self-test, although the commit claims that 'shallow history' fails loudly. Reading the code, it is correct. With fetch-depth: 0 in the gates job, the branch never runs in CI.
- `git rev-parse -q --verify "origin/${integ}"` and `git merge-base "origin/${integ}"` use the short name. `refs/remotes/origin/${integ}` would be unambiguous: a local branch or tag named origin/<INT> would otherwise shadow it.
- Pre-existing, not new: the Migrations step is still gated by `needs.changes.outputs.db`, which comes from the diff against the last green lane head, not against MIGRATIONS_BASE. A lane that merges INT and resolves a conflict by keeping its own content in db/migrations, with no db file changed since its last green run, gets db=false, and the step is skipped.
- By design, but worth noting: a lane migration that has passed on an earlier green lane push and is not yet on INT is no longer protected from edits or renumbering on later lane pushes. Only what is on INT counts as shipped. This is consistent with the stated rule because deploys run from INT only.
- The `!cancelled()` contract steps also run when an earlier step failed for an unrelated reason (for example the base-choice step exiting 1, so CHECK_BASE and CONTRACT_BASE are unset, or the tool install failing). They then fail as well, which adds noise but loosens nothing.
- When GITHUB_SHA is missing from the checkout, the merge-base error says 'unrelated history?', which is misleading. It still fails, so this is only about the message.

## bypass: FAIL (blocking raised 1, upheld 1)

I read the full diff of afbbe9a052 against INT and ran the 7 MigrationsBase self-tests from the branch tree in /tmp; all pass. Confirmed sound: lanes are told apart from INT, main and pull requests correctly (exact case match; push and workflow_dispatch only; pull requests, main and INT keep CHECK_BASE unchanged). A missing origin/INT or unrelated history fails loudly: the step's `mig_line=$(...)` under bash -e fails the step, with no fallback to a weaker base. Nothing secret is printed. A red Migrations step still makes the job red: no continue-on-error anywhere in gates, and !cancelled() on the four contract steps only makes them run, it cannot turn the job green. Lanes that edit, delete or renumber a migration that is in the merge-base are red. Blocking: in a criss-cross history (INT train-merges lane commit L0 while the lane merges an older INT tip, a normal pattern here), `git merge-base` prints one of two bases. When it picks the one without the lane-shipped migration, editing that migration INT has shipped is green. I reproduced this with the branch's own scripts: migrations: ok, exit 0, while the old rule and the other merge-base are both red. I deleted my scratch repo under /tmp afterwards. Nothing in /home/user/Aron-Pro-Max was modified.

### UPHELD: Criss-cross history: the merge-base the script picks can lack a migration INT has already shipped, so a lane can edit that migration and the gate stays green (the old rule caught this)

tools/ci/migrations-base.sh

The script takes the single commit printed by `git merge-base origin/<INT> <sha>`. The train workflow produces criss-cross histories: INT merges lane commit L0 ("train: merge ...") while the lane, at the same time, merges an older INT tip I1. After that, lane tip and INT tip have two best common ancestors, L0 and I1, and `git merge-base` without `--all` prints only one of them. I reproduced this in a throwaway repo using the branch's own scripts. Setup: base has V0001. The lane adds V0003 (L0). INT adds V0002 (I1), then merges L0 (I2 = origin/INT, so V0001..V0003 are shipped). The lane merges I1, then edits the shipped V0003. Result: `git merge-base --all` prints I1 and L0. The script chooses I1, where V0003 does not exist. migrations-check.sh then reports `checksum: 2 shipped ... order: 1 new migration(s), highest shipped version 2, migrations: ok`, exit 0. That is a green gate on an edited migration that INT has shipped. On the same repo, the old rule (last green lane head, which contains V0003) is red ('V0003__lane.sql was shipped ... and changed since'), and so is the other merge-base (L0). So a shipped migration can now be edited on a lane with the gate green: a regression from the old rule. The flip side is that when git picks L0 instead, INT's V0002 counts as 'new' and can be wrongly red or green on order. Fix: protect what INT has actually shipped. Either run the check against every commit from `git merge-base --all` (red if any one is red), or add a check that every db/migrations file present at origin/<INT> and also present at HEAD is byte-identical, and that new versions are above origin/<INT>'s highest. No self-test covers a criss-cross merge.

Votes: upheld: I could not refute it, and I reproduced it. tools/ci/migrations-base.sh runs `git merge-base "origin/${integ}" "${sha}"` without `--all` and uses the single commit it prints. migrations-check.sh then treats only the files at that base as shipped. A file that is missing at the base counts as "added", and its only check is that its version is above the highest version at the base.

Repro: I built th; upheld: I could not refute it, and I reproduced it in a throwaway repo under /tmp, now deleted. I used the branch's own tools/ci/migrations-base.sh and migrations-check.sh, taken with `git show origin/lane/ci-migrations-base:...`.

Setup: the base commit has V0001. The lane adds V0003 (L0). INT adds V0002 (I1), then merges the lane (I2 = origin/INT). The lane merges I1, then edits V0003, which INT has now; upheld: I could not refute it. The defect is real. tools/ci/migrations-base.sh on origin/lane/ci-migrations-base runs `mb="$(git merge-base "origin/${integ}" "${sha}")"`. That prints a single best common ancestor and has no `--all`. ci.yml then passes that one commit as MIGRATIONS_BASE straight to tools/ci/migrations-check.sh. Nothing else checks the lane's migrations against origin/<INT>: no per-file com

Lows:
- A lane's own migrations that are not yet on INT are no longer frozen once a lane run was green. The old base (last green lane head) froze them. This fits 'shipped = on INT' and deploys run only from INT, but it is a behaviour change that DECISIONS.md does not spell out.
- A squash-merge or cherry-pick of a lane migration into INT leaves the lane's merge-base without it, so the lane can edit it and stay green until the INT push catches it. INT currently uses real merges, so this is theoretical; the same fix as the blocking item would cover it.
- The shallow-clone path (unshallow, then refuse) has no self-test. The DECISIONS line claims 'missing ref or unrelated history', so the claim is accurate, but the unshallow branch never runs in tests.
- test_the_workflow_wires_the_base_and_keeps_the_contract_steps_running pins !cancelled() on three of the four contract steps; setup-node is not checked. It also does not assert that the Migrations step has no continue-on-error and no !cancelled()/always(), which is what keeps a red Migrations step making the job red.
- migrations-base.sh runs unconditionally in 'Choose the comparison base'. If it fails on a lane run (no origin/INT), every later gate is skipped, gitleaks included. The job is still red, so nothing is masked, but the log shows only that one error.
- Pre-existing, not changed here: the changes job's db filter still diffs against the stale last-green head. db=true still fires whenever the lane touches db/migrations or merges INT migrations, so I found no green path through it. Also pre-existing: a lane that forked before INT shipped V0214 can add its own V0214 under a different name and stay green on the lane; the clash is caught only on the INT push.

## self-tests: PASS (blocking raised 0, upheld 0)

Ran the tests from a git-archive copy of origin/lane/ci-migrations-base in /tmp (deleted afterwards). `python3 -m pytest tools/ci/test_gates.py -k MigrationsBase -q`: 7 passed. squawk is not installed, so the judge used 'false' as the squawk binary. Full tools/ci/test_gates.py: 58 passed, 9 skipped. infra/tests/check_infra.py: 44 failed, 88 passed on the lane, the same 44 failures as on the integration branch (diff empty). These are environment failures; the changed gates-job test passes. None of the tests needs the real repository: each builds its own temp repo, and the workflow test reads ci.yml from the copy.

Mutation checks on migrations-base.sh:
- Lane base set to GITHUB_SHA (compares nothing): 3 tests go red, including the 'edits a shipped migration' and 'version below highest shipped' tests.
- Missing ref falling back to the gate base: caught.
- Merge-base replaced by the INT tip: caught only by the unrelated-history case.
- Shallow block removed: not caught.

The tests for 'shipped edit is red', 'below-highest is red', 'old rule kept for INT/main/PR, push and workflow_dispatch', 'stale last-green ignored' and 'missing ref fails with no stdout' assert what they claim and fail when the script is wrong.

The check_infra.py change swaps the needle '"${CHECK_BASE}" squawk' for '"${MIGRATIONS_BASE}" squawk' and adds 'tools/ci/migrations-base.sh'. That matches the new step and is no looser: MIGRATIONS_BASE is CHECK_BASE outside lanes.

No blocking problems. The gaps are in lows: the shallow path is untested despite the test name, and no test separates merge-base from the INT tip.

Lows:
- test_a_lane_run_without_the_integration_ref_or_the_history_fails_loudly does not test a shallow clone at all. 'The history' is checked only through an orphan, unrelated-history commit (the 'no merge-base' error). Mutation M1 deletes the whole shallow/unshallow block from migrations-base.sh and all 7 tests still pass. The script still fails closed (in a shallow clone git merge-base fails and the script exits 1), and CI uses fetch-depth 0. Either add a shallow-clone case (git clone --depth 1 file://...) or rename the test.
- No test separates 'merge-base with origin/INT' from 'origin/INT tip'. In tests 1-4 the lane already contains int_tip, so both give the same answer. Only the unrelated-history case catches mutation M2 (rev-parse origin/INT in place of merge-base). That mutant is stricter, not looser, so nothing unsafe slips through. Still, a case where INT moved ahead of the lane's fork point (lane not merged) would pin down the claimed rule.
- test_a_lane_that_adds_a_version_below_the_highest_shipped_is_red only checks for the word 'above'. With squawk absent the judge passes 'false' as the squawk binary, so rc==1 could also come from the lint step. 'above' happens not to appear in the squawk error text, so the test is valid today, but asserting 'not above the highest shipped version 4' would be robust.
- In test_the_workflow_wires_the_base_and_keeps_the_contract_steps_running, the last assertion searches the whole rest of the gates job after '- name: Migrations', not the Migrations step's own block. It would still pass if that condition moved to a later step. Today line 252 is the only occurrence, so the assertion holds.
- test_the_integration_branch_main_and_pull_requests_keep_the_gate_base ends with self.assertTrue(int_tip), which checks nothing.
- Not a test issue: a lane push whose head equals the integration tip gets merge-base == GITHUB_SHA, so migrations-check compares HEAD with HEAD. That is correct because the content is exactly what is shipped, but unlike CONTRACT_BASE there is no guard or comment saying so.
- Outside this lens, noted only: a lane that forked at V0213 and adds V0214 after INT has shipped V0215 stays green until it merges INT. The old last-green rule did not catch this either, so nothing got looser.
