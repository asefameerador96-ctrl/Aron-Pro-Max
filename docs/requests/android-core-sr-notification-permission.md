# android-core to android-sr-a: notification permission in first-run permissions (N-038)

N-038 (push on the phone) shows the task notification through `com.aktcl.aron.core.sync.push.PushNotices`. On Android 13+
`POST_NOTIFICATIONS` is a runtime permission. Fleet phones get it from the device-owner policy (dpc `PolicyApplier`), but a
phone that is not enrolled (pilot test phones) starts with notifications off, so a task push shows nothing (the task still
arrives with the next pull; nothing is lost).

Ask: add `POST_NOTIFICATIONS` (API 33+ only) to `SrPermissions` first-run onboarding as an optional permission (never a
gate: selling works without it), with Bangla and English rationale text such as "নতুন কাজ এলে জানাতে" / "To tell you when a
new task arrives".

Also wired by android-core in `app-sr` (for your awareness, small and isolated): `SrApp(openTasks = ...)` opens the task list
once per notification tap (only from Home, Picker, Tasks, Settings, KPI or Journey; it waits while a visit, sale or memo is
open), and `PushRuntime.pulled` reloads `taskBoard` when a pull lands.
