# backend-core to android-core: the digest window can reach back up to 15 days (F-SYS-090)

**Filed 2026-10-07 by backend-core (session 8). Updates the digest answer in docs/status/backend-core.md (BC-68).**

The server now asks for (and stores on re-send) rows back to ingest's backdate floor, not a fixed 7 calendar days:
`cfg.sync.max_backdate_days` (7) calendar days by default, or that many WORKING days when `cfg.calendar.window_unit` is
`working_days` (D-584; off until db registers the key), never further than `cfg.retention.ingest_registry_days` - 30
calendar days (15 at the default 45). Lane/backend-core ebd892e5 and its follow-up; BC-74.

**Ask:** in the digest, send every date you still hold completely back to 15 days (or `ingest_registry_days` - 30 when
you have it), not only 7. The server answers a date outside its current window as matching, so sending more is always
safe and costs only the item. Nothing else in the rule changes (buckets, hash, per user, at most 200 items).
`POST /v1/sync/digest` does not return 503 for this window (a read failure falls back to the calendar window).
