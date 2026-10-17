# Status: lane android-tso (wave 2, started 2026-10-17)

## Done
- F-TSO-001 checked (Opus), defects fixed. DEVICE-PENDING: Galaxy A06 check in Bangla and English (not yet in device-checks.md).

## Was in progress
- F-TSO-001 (T1): dark navy login as the kit Dark mode (`TsoLoginTheme` in feature-tso, wired in `app-tso` MainActivity; status/nav bar icons follow; sunlight overrides dark). Logout dialog and reconciled-only wipe pre-exist (`ShellLogout`, `UnsentItemsDialog`, Settings confirm). Tests green locally. Opus checker running; row goes to the CSV when it reports.

## Done (cont.)
- F-TSO-008 leave list (checked by Sonnet, 4 defects fixed). Host: `TsoReadModels` (GET + cache in the encrypted per-user sync_meta, wiped with the DB), `TsoOutbox` (generic durable queue: outbox row + mirror row in one transaction, idempotent by uuid; no shared DTOs needed, payload is a JsonObject). Reuse `TsoOutbox` for every TSO write.
- F-TSO-009 leave apply done (Sonnet checker: 1 defect fixed). `rememberRetained` keeps forms across recreation.
- F-TSO-018 My Feedback form + F-TSO-029 list built and wired (categories from the bundle code_lists, offline; queue via TsoOutbox; toasts). The ONE-PHOTO part is not wired yet: needs a TSO MediaComponents shell like app-sr `MediaShell` (core-media + upload scheduler) and a `FeedbackPhotos` implementation; the form already has the port (`photosEnabled`). Checker for 018/029 pending.

## Next rows
F-TSO-018 My Feedback, F-TSO-028 chrome, F-TSO-029 feedback list, then Day 5 rows.

## Traps
- Local Gradle works online (not `--offline`): `echo sdk.dir=/opt/android-sdk > local.properties` after `tools/android-sdk.sh`; filter the `Picked up JAVA_TOOL_OPTIONS` noise.
- Decision: only the login is dark; the signed-in day stays Calm Glass light (revisit in F-TSO-028).
