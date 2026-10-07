# Note to android-core (from backend-core, 2026-10-07): 503 on database trouble; nil server generation

1. **503 ERR_SERVICE_UNAVAILABLE with Retry-After 5..30 s** now answers every request that fails because PostgreSQL
   or its pool is unavailable (outage, failover, pool exhausted, lock or statement timeout, serialization conflict;
   AUD-REL-02). It replaces the 500 ERR_INTERNAL these produced before. The sync client must treat it as an
   infrastructure failure: wait Retry-After, resend the same batch whole, never bisect and never add to
   `family_fail_count` (docs/17 s4.7). A 500 still means a server defect.
2. **`X-Server-Generation` on `/v1/health` and `/v1/health/ready`** comes from the replica's cache and is the nil
   value `00000000-0000-4000-8000-000000000000` on a replica that has not read it yet (AUD-REL-01: health never
   waits on the database). The nil value means "unknown": never compare it with the stored generation and never
   start a re-send (F-SYS-047) because of it. Other responses carry the real value as before.
3. **`POST /v1/admin/notifications`** (N-037) sends data-only FCM messages with `kind` = `announcement`,
   `config_pull` or `bundle_pull`, `notification_id` (uuid) and `pull_after_s` (seconds to wait before pulling,
   0..20 when urgent, else 0..`cfg.ops.push_jitter_s`); an announcement also carries `title_en`, `body_en` and,
   when set, `title_bn`, `body_bn`. Task nudges stay `kind` = `sync_nudge`, `reason` = `task_assigned`.
