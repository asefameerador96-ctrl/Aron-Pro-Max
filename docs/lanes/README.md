# Lane briefs (read first, then your own file)

You are one lane of the Aron build team. The lead (session `@parent`) coordinates; you work **autonomously**.

## Read, in this order, and stop
1. This page, then `docs/lanes/<your sublane>.md`.
2. `docs/26-lane-playbook.md` (git protocol with the integration train, builder then checker, quality rules, time log) and `docs/29-model-routing-and-autonomy.md` (tiers, token hygiene, recycling).
3. Your rows: `python3 tools/my-rows.py <sublane> --todo` (add `--full` for the whole acceptance test of one row).
4. Only the spec sections your rows name in `docs/24-build-spec.md`; the contract through the generated slices in `contract/slices/` (`INDEX.md` lists every schema and operation; one small file each; regenerate with `python3 tools/slice-contract.py`; `contract/openapi.yaml` itself is 553 KB and stays the only source of truth: never edit a slice, never print the big file).
5. Binding rules that override everything older: `CLAUDE.md` (sponsor standing rules), `docs/27` (deferred programmes, targets, discounts), `docs/28` (test account, pilot size), `docs/30`, `docs/31`.

## Work loop (no go-ahead needed)
Pick the next undone row of your sublane whose dependencies are met, in (day, id) order. Build it with its acceptance test automated, run the checker as docs/29 s2 says for its tier (T1: fresh Opus subagent; T2: fresh Sonnet subagent), fix confirmed defects, commit `F-XXX-nnn: summary`, merge INT, run your tests, push to your lane branch `lane/<sublane>` (never to INT: the integrator promotes green heads, docs/26 s3), append to `docs/status/<sublane>.csv` and update `docs/status/<sublane>.md`, then take the next row. Day numbers are targets, not gates: when your list for the day is done, continue with the next day's rows.

## Stop conditions, and only these
- All your rows are done: send `@parent` a short report (rows, minutes, requests, decisions).
- Every remaining row is blocked: write `docs/requests/<sublane>-<name>.md`, mark the rows blocked in your status file, send **one** message to `@parent`.
- Context near 450 k tokens: finish the current row, complete the status `.md` (done, in progress, next three rows, traps), push, send `@parent` the words `READY TO RECYCLE`, stop.

## Rules that never bend
Offline-first; idempotent by client UUID; money in integer milli-taka; Dhaka business date; scope from the token only; Bangla and English resources for every string; no secrets in git or logs; a row without its offline behaviour and its test is not done; no hooks into deferred programmes beyond docs/27. Rows needing the owner's phone or printer: build and test everything else, then mark `DEVICE-PENDING` with exact steps in `docs/status/device-checks.md` and continue.

## Environment
JDK 21 and PostgreSQL 16 are installed; `tools/android-sdk.sh` sets up the Android SDK; if Maven Central answers 429 use the mirror init script from `docs/24-build-spec-verification.md` s9 (never commit it). The dev environment in Azure is shared: do not deploy by hand; CI deploys INT.
