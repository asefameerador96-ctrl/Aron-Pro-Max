# 26 — Lane playbook (binding for every lane session)

You are one expert lane of a team building Aron in seven days (hard cap ten). Other lanes work at the same time in their own sessions. This page is how the team stays consistent. Read it in full, then `docs/24-build-spec.md`, then your rows.

## 1. Read, in this order

1. This page.
2. `docs/23-seven-day-plan.md` (scope, anti-spoofing, scale properties) and `docs/24-build-spec.md` (**binding**: stack, versions, conventions, sync, auth, config, device policy, geo integrity, money, numbering; section 14 lists the decisions already taken).
3. `contract/openapi.yaml`: the one API contract. Never invent an endpoint, field, enum or error code.
4. Your rows: `python3 tools/my-rows.py <lane> [day] [--full]`. The full list is `docs/25-build-backlog.csv` (read-only for you).
5. For apps and web: `docs/ui-reference/` (the current screens and what they do), `docs/22-apsis-data-profile.md` (real data shapes), the SR/AMO/TSO/Web inventories in `docs/evidence/manuals/` for any screen you build.
6. Background only when you need a detail: `docs/14` to `21` and `DECISIONS.md` are the earlier, larger plan. Where they conflict with `docs/23`/`24`, `docs/23`/`24` win.

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
- Never edit: `docs/00` to `docs/25`, `docs/evidence/`, `DECISIONS.md`, the backlog CSV.

## 3. Git protocol (every lane pushes to the integration branch)

The integration branch is `claude/wonderful-thompson-k6ejnf` (`INT` below). There is no pull request step.

```
git fetch origin
git checkout -B work origin/claude/wonderful-thompson-k6ejnf      # at the start of each task
# ... build the row, commit with message "F-XXX-nnn: <what>" ...
git fetch origin && git merge origin/claude/wonderful-thompson-k6ejnf   # resolve conflicts in YOUR files only
<run your lane's build and tests; all must pass>
git push origin HEAD:claude/wonderful-thompson-k6ejnf
```

If the push is rejected (someone pushed first), fetch, merge, re-run your tests, push again. **Never force-push, never rewrite history, never push a failing build.** Commit after every finished row; do not hold work back.

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
