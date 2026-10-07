# 29 — Model routing, token budget and lane autonomy (binding, 2026-10-07)

**Sponsor goal.** Finish the whole product inside the sponsor's Claude Max 20x allowance **without lowering quality**. Quality is protected by tests and independent checkers, not by always using the biggest model. Capacity is protected by routing work to the cheapest model that can do it safely, by keeping contexts small, and by never letting a lane sit idle.

## 1. Where the tokens actually go (measured 2026-10-07)

Per-session usage (cache reads dominate): lead about 1.4 billion cache-read tokens; backend 59 M, android-core 67 M, db 91 M, infra 141 M. Cost is therefore driven by **long contexts times many turns**, then by large file reads and repeated full builds, not by the number of lines written. The levers, in order of effect:

1. Keep every lane's context small (recycle sessions, section 5).
2. Pick the model tier per row (section 2).
3. Read only what the row needs (section 4).
4. Run only your lane's tests while working; the full build before a push that touches shared or root files.

## 2. Tiers

| Tier | Used for | Builder | Independent checker (fresh context) |
|---|---|---|---|
| **T1** | anything where one defect loses or doubles money or data, breaks the offline guarantee, leaks scope, weakens anti-spoofing, or is hard to fix in the field: sync and ingest, idempotency, auth and tokens, scope, money and memo maths, geofence and plausibility, device owner and app blocking, printing renderer, migrations and partitioning, aggregation, audit, config resolution, infra and deploy, final submit and day state, dues | the lane's session model (Opus for T1 lanes) | **Opus**, always |
| **T2** | everything else: screens, list and detail pages, ordinary CRUD endpoints and admin pages, reports that read existing aggregates, forms, navigation, settings | the lane's session model (Sonnet lanes) | **Sonnet**, or Opus for the weekly sampled audit (section 3) |
| **T3** (sub-tasks only, never a whole row) | mechanical work with a mechanical check: time-log and status lines, CSV edits, boilerplate generated from the contract (DTO mirrors, API clients) when a drift test guards it, fixtures and test data generators, renaming, formatting, reading long logs and summarising, grep sweeps | **Haiku** subagent (`model: "haiku"`) | the lane runs the tests or the diff check |

Rules:
- Each backlog row carries `model_tier` (T1 or T2) and `sublane` in `docs/25-build-backlog.csv`; `python3 tools/my-rows.py <sublane>` prints them. A lane may **promote** a row to T1 (record it in the status file), never demote.
- **Never below Sonnet:** money, sync, security, geofence, migrations, infra, anything that renders Bangla text or print output, anything on the offline path. Haiku never edits those; it only reads or formats.
- **Escalate** to the next tier up when: a builder fails its acceptance test twice, a checker confirms a defect, or the row turns out to touch a T1 area. The checker for the retry is the higher tier.
- **Sampled audit:** each day the lead runs an Opus review over about 10 percent of that day's T2 rows (by diff). A defect rate above 10 percent in a lane moves that lane's next module to Opus builder until it drops.
- A row is done only with its acceptance test green, the checker's findings fixed, and the code pushed (docs/26 s4), whatever the tier.
- Fable-class models are not used for routine work. The lead may use one for the final architecture and security reviews.

## 3. Session models by lane

| Sub-lane | Session model | Why |
|---|---|---|
| db, backend-core, android-core, android-geo-dpc, android-print, infra | Opus | all T1 or mostly T1 |
| backend-reports, backend-admin, android-sr-a, android-sr-b, android-amo, android-tso, web-admin, web-config, web-dashboard, qa, shared | Sonnet | mostly T2; their T1 rows get an Opus checker |

A running session keeps its model. Lane state lives in git and in `docs/status/<lane>.md` and `.csv`, so replacing a session is cheap: the lead starts a fresh one with the same brief (`docs/lanes/`). That is also how contexts stay small.

## 4. Token hygiene for every lane (these cost nothing in quality)

1. Read in this order and stop: `docs/lanes/<your lane>.md`, your rows (`my-rows.py <sublane> --todo`), the one or two spec sections each row names. Do not open `docs/14` to `22`, `docs/evidence/` or the 553 KB contract in full; read `contract/slices/` for the contract (`INDEX.md` first) and slice the other documents (`sed -n`, grep).
2. Never print or cat large files, build logs or test output; filter to the failing lines.
3. Run only your module's tests while working. Run the wider build before a push that changes shared, contract, db or root files.
4. The checker subagent gets only: the row id, its acceptance test, the files changed. Not the whole conversation.
5. Delegate mechanical sub-tasks to Haiku (section 2, T3) instead of doing them in your own large context.
6. Commit after every finished row; no work held back; no re-doing finished work after a recycle.

## 5. Autonomy and recycling

- **No waiting for a go-ahead.** Day numbers are targets, not gates. After each row, take the next undone row of your sublane (`--todo`) whose dependencies are met and continue. Stop only when your list is finished or every remaining row is blocked.
- **Blockers:** write `docs/requests/<lane>-<name>.md`, mark the rows blocked in your status file, send **one** short message to the lead (`send_message` to `@parent`), then continue with any unblocked row. No progress chatter.
- **Device-dependent acceptance** (a real phone, the MP-58N, the owner's hands): build everything that can be tested without it, mark the row `DEVICE-PENDING` in the status file with the exact steps in `docs/status/device-checks.md`, and move on.
- **Recycle at about 450 k tokens of context:** finish the current row, make sure the status `.md` has "what is done, in progress, next three rows, traps found", push, send `@parent` the words `READY TO RECYCLE`, and stop. The lead starts a fresh session on the same brief.
- The lead checks every lane on a timer (hourly) and nudges any lane that is idle without a blocker.

## 6. Usage monitoring and throttling (lead)

- Each check records, per session, `usage.cost_usd` (an API-equivalent figure used only as a relative measure), context size and `rate_limit_info.status` in `docs/status/usage.md`. The five-hour window state is the real signal: when it is anything but `allowed`, or when it is near its reset with heavy work queued, the lead pauses T2 lanes and keeps the critical path running.
- Maximum parallel active lanes: 14 until the SR slice runs end to end, then as the remaining work requires.
- If a lane's cost per finished row is more than twice the median, the lead reads its last rows for waste (large reads, repeated builds) before anything else.
- This page changes only by the lead; a lane that thinks a tier is wrong writes a request.
