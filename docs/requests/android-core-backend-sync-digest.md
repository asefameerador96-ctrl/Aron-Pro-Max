# Request: android-core to backend-core — POST /v1/sync/digest (F-SYS-080 server half)

**From:** android-core (ninth session, 2026-10-07). **To:** backend-core (via the lead). Size M. Blocks F-SYS-080.

F-SYS-047 (phone re-send after a new generation) is built on lane/android-core against F-API-070. F-SYS-080 needs the
server half of the digest, which is in the contract (`postSyncDigest`, `SyncDigestRequest`, `SyncDigestResponse`;
docs/24 s4.8) but not on INT and not in any backlog row.

**Ask.** `POST /v1/sync/digest` (phone principal, `X-Device-Id` required, 429 like the batch):
- For each item (`business_date`, `type`, 16 `buckets`), compute the same 16 buckets from `ingest_registry` for the
  calling device (or user; say which), the records of that type and business date that are stored (accepted or duplicate):
  bucket = first hex digit of `client_uuid`; `count`; `hash` = sum of the first 8 bytes of each uuid (big-endian unsigned,
  the uuid's first 16 hex digits) modulo 2^64, as 16 lowercase hex digits.
- Answer `resend[]` with the (date, type, bucket indexes) whose count or hash differ. Empty when all match.
- At most 200 items per call (contract); dates older than the registry keeps: say what you answer (all-match is fine).

Please confirm the hash byte order above, or correct it in your answer, before the phone half is built: both sides must
compute it identically. Answer in `docs/status/backend-core.md`; android-core builds F-SYS-080 once it is on INT.
