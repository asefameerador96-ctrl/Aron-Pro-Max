# 26 — Lane playbook (binding for every lane session)

You are one expert lane of a team building Aron in seven days (hard cap ten). Other lanes work at the same time in their own sessions. This page is how the team stays consistent. Read it in full, then `docs/24-build-spec.md`, then your rows.

## 1. Read, in this order

1. This page.
2. `docs/23-seven-day-plan.md` (scope, anti-spoofing, scale properties) and `docs/24-build-spec.md` (**binding**: stack, versions, conventions, sync, auth, config, device policy, geo integrity, money, numbering; section 14 lists the decisions already taken).
3. `contract/openapi.yaml`: the one API contract. Never invent an endpoint, field, enum or error code.
4. `docs/27-deferred-programmes.md` (binding): target, loyalty and discount/promotion programmes are deferred. Rows marked DEFERRED are not built; rows marked TRIM are built without that part.
4b. `docs/28-environment-profiles.md` (binding): the Azure and Google accounts are temporary TEST accounts at pilot size (5 to 10 users); fleet sizing belongs to the later final account.
5. Your rows: `python3 tools/my-rows.py <lane> [day] [--full]`. The full list is `docs/25-build-backlog.csv` (read-only for you).
6. For apps and web: `docs/ui-reference/` (the current screens and what they do), `docs/22-apsis-data-profile.md` (real data shapes), the SR/AMO/TSO/Web inventories in `docs/evidence/manuals/` for any screen you build.
7. Background only when you need a detail: `docs/14` to `21` and `DECISIONS.md` are the earlier, larger plan. Where they conflict with `docs/23`/`24`, `docs/23`/`24` win.

## 2. Folders and ownership

One folder, one owner. Edit only your lane's folders.

| Lane | Owns |
|---|---|
| shared | `shared/` |
| db | `db/` |
| backend | `backend/` |
| android-core, android-geo, android-dpc, android-print | `android/core-*`, `android/dpc` |
| android-sr | `android/feature-*`, `android/app-sr` (the SR lane owns the feature modules AMO and TSO reuse) |
| android-amo / android-tso | `android/app-amo` and AMO-specific modules / `android/app-tso` and TSO-specific modules |
| web-dashboard / web-admin | `web/` (dashboard / admin portal routes) |
| infra | `infra/`, `.github/`, root Gradle files, `gradle/libs.versions.toml` |
| qa | `qa/` |

- **The contract is not yours.** If you need a change to `contract/openapi.yaml` or `docs/24`, write `docs/requests/<your-lane>-<short-name>.md` (what you need, why, the exact shape) and continue with a local stub clearly marked `// REQUEST: <file>`. The lead routes it. Never edit the contract yourself.
- **Root files** (`settings.gradle.kts`, `gradle/libs.versions.toml`): you may only **append** an `include(...)` line or a catalogue entry; never reorder or reformat.
- Never edit: `docs/00` to `docs/27`, `docs/evidence/`, `DECISIONS.md`, the backlog CSV.

## 3. Git protocol: lane branches and the integration train (changed 2026-10-07 06:30 UTC, binding)

**Why:** the CI audit (`docs/audit/2026-10-07-ci-audit.md`) found that 16 lanes pushing into one branch about every 20 seconds meant one red commit poisoned everybody, lanes copied each other's breaks, and no INT run created after 04:51 was green. The fix is that INT only ever receives verified code.

The integration branch is `claude/wonderful-thompson-k6ejnf` (`INT` below). **INT moves only by fast-forward to a candidate whose CI run is green, and only the integrator session (docs/lanes/integrator.md) moves it.** Lanes never push to INT.

```
git fetch origin
git checkout -B work origin/claude/wonderful-thompson-k6ejnf      # at the start of each task: INT is green by construction
# ... build the row, commit with message "F-XXX-nnn: <what>" ...
git fetch origin && git merge origin/claude/wonderful-thompson-k6ejnf   # resolve conflicts in YOUR files only
<run your lane's build and tests; all must pass>
git push origin HEAD:lane/<your sublane>                          # for example lane/backend-core
```

- **Your lane branch is `lane/<sublane>`** (create it by the first push). CI runs on it. A head is **ready** when the CI run on that exact commit is green, or when everything it changes since INT is documentation (`docs/**`, `*.md`).
- **The integrator** merges all ready lane heads into a candidate branch `lane/train-<time>` off INT, never resolves conflicts, lets CI run on the candidate, and fast-forwards INT when it is green; if it is red it splits the lanes and finds the culprit. It tells a lane only when its branch conflicts with INT (merge INT, resolve in your files, push again) or when its head breaks the candidate (it names the failing job). The state is in `docs/status/train.md`; read it instead of asking.
- **A red CI on your lane head is yours** (INT was green when you started): fix it with the next push. Never wait for another lane and never merge another lane's branch, only INT.
- Merge INT into your branch before every push and at least every time `docs/status/train.md` shows INT moved with changes in files you use (contract, shared, db migrations, `ci.yml`).
- A change to the contract, shared DTOs, a migration or `ci.yml` is announced in your commit message and by a request file; the owner lane of the consumers runs its compile before it is promoted (checklist in `docs/requests/contract-v1.3-queue.md`).
- **Never force-push, never rewrite history, never push a failing build** (a local green build is the entry ticket; CI is the check). Commit after every finished row; do not hold work back.
- The lead pushes documentation-only commits to INT directly (no CI run starts for them); the lead's code changes go through `lane/lead-<name>` like everyone else.

End every commit message with:
```
Co-Authored-By: Claude <noreply@anthropic.com>
```

## 4. Builder, then checker (nothing is done on one agent's word)

For every row:

1. **Build** it with its acceptance test automated (a real test that fails without your code).
2. **Check**: start a **fresh reviewer subagent** (Agent tool, new context) and give it only: the row id, its acceptance test, the files you changed. Its job is to refute: run the tests, read the code against the acceptance test and `docs/24`, hunt for edge cases (duplicates, clock skew, offline, Bangla, empty states, large inputs, wrong role), and write the failing test if it finds a defect. Fix every confirmed defect and re-run.
3. A row is **done** only when the acceptance test passes, the reviewer finds nothing it can reproduce, and the code is pushed to `INT`.

Do not mark a stub, a TODO, a mock of something you own, or a screen without its offline behaviour as done.

## 5. Quality rules that apply to every row

- **Offline first.** Nothing in the field apps waits for the network in a sale; every write goes to the local database and the outbox in one transaction.
- **Idempotent.** Every device-originated record has a client UUID; a retried or duplicated upload changes nothing.
- **Money** is integer milli-taka; quantities in the SKU base unit; timestamps UTC plus the Asia/Dhaka `business_date`; no floating point for money.
- **Server-side scope.** The client never sends scope ids; the server derives reach from the token.
- **Bangla first.** Every user-visible string is a resource (Bangla and English); Bengali digits per locale; no hard-coded text; a lint check fails the build on it.
- **Battery.** No continuous location, no polling, no wake locks beyond a sync batch.
- **Secrets.** Never write a key, token or password into the repo or a log. The Maps, Firebase and signing secrets are GitHub secrets that your session does not have; builds and tests must work without them (the spec says how).
- **Accuracy over speed.** If the spec, the contract or a row is ambiguous, do not guess: write a request file (section 2), take the safest reading, mark it, and move on.

## 6. Time log (the lead uses it to re-forecast the schedule)

Keep `docs/status/<lane>.csv` (you own it, append-only) with one line per finished row:

```
row_id,size,started_utc,finished_utc,minutes,review_findings,notes
```

Also keep `docs/status/<lane>.md`: what is done, what is in progress, what is blocked and why, requests filed. Update both with every push.

## 7. Environment notes

- JDK 21 is installed. Android SDK: run `tools/android-sdk.sh` once. If Maven Central answers HTTP 429, use the mirror init script described in `docs/24-build-spec-verification.md` section 9 (never commit it).
- PostgreSQL 16 is installed locally; backend and db tests use `ARON_TEST_PG_URL` (see the spec). No Docker daemon.
- Gradle: one root build; run only the tasks of your lane while working, the whole build before a push that touches shared or root files.

## 7a. Time boxes

If a row takes more than twice its band (S 2 h, M 4 h, L 8 h of your time), stop, log it, note why in the status file, and move to the next row. The lead decides what to do with it.

## 8. Finishing

When your assigned rows are done, or you are blocked on all that remain: update the status files, push, and end with a short report: rows done, rows not done and why, minutes per row, requests filed, decisions you took, and anything the next day depends on.
