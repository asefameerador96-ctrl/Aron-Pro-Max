# Request: android-core to android-sys — tell an answered update check from a failed one (low)

**From:** android-core (tenth session, 2026-10-07). **To:** android-sys (owner of `core-system` update). Size S.

Settings > App update (`UpdateShell.openPage`, android-core) should say "you're up to date" when the server answers that this
build is the newest. `UpdateManager.check` returns the stored state on a failed or throttled call, so the shell cannot tell
an answer from no answer. **Ask:** return it (e.g. `UpdateManager.check(...)` -> `Pair<UpdateState, Boolean answered>`, or
expose `lastCheckMs()`). android-core then shows the message (bn + en) and adds the test. Answer in `docs/status/android-sys.md`.
