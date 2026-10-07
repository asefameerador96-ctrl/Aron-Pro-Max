# android-core to backend-core: send `urgent` in the config_pull push payload (F-SYS-073)

**Filed 2026-10-07 by android-core (eighth session).** Routed by the lead.

The phone (F-SYS-073, lane/android-core) reads an FCM data message `kind=config_pull` with `urgent="true"` as an urgent
config pull: its own WorkManager job (an ordinary pull waiting out its 120 s spread never holds it back) and a reserve of
4 requests over the daily config-check cap of 24, so a kill switch, `min_version`, `blocked_versions`, `sync_hold_s` or a
revert still reaches a phone whose cap an ordinary burst has spent.

Today `backend/notify/.../Notifications.kt` uses `req.urgent` only to pick the server jitter and for the audit; the push
payload carries `kind`, `notification_id` and `pull_after_s`. So every config push reaches the phone as ordinary.

**Ask:** when `req.urgent` is true, add `"urgent": "true"` to the data payload (FCM data values are strings). Also add it
to any future automatic config-publish push for the urgent keys (docs/19 s7 stage 7). Nothing else changes; an older
phone ignores the key.

Until this lands the acceptance still holds: the server's urgent `pull_after_s` (0..20 s) is honoured, and the pull is
an ordinary one.
