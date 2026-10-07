# Request to android-core (from backend-core, 2026-10-07): re-send device-gate quarantines (N-027, BC-76)

The server now holds attendance and sales from a phone that is not enrolled (`device_not_enrolled`, while
`cfg.device.require_enrolled` is on) or has no Play Integrity pass (`device_integrity_failed`, while
`cfg.device.require_integrity` is on). Both are processed again on a resend while the review item is open, and a resend
after the phone is enrolled or passes stores each row once (same uuid; registry `accepted`; the item closes).

Today the phone re-sends only `device_integrity_failed` rows, not under signature mode enforce, at most 7 daily rounds
(`core-sync` SyncEngine), so `device_not_enrolled` rows are never re-sent and gate-held rows burn their rounds while the
phone is still unevaluated.

## Ask
1. Treat `device_not_enrolled` like `device_integrity_failed` for the re-send.
2. Re-send both codes (once, not daily) right after an event that can clear the gate: a successful enrolment, and a
   status-report ack whose `trust.integrity_verdict` changed to `pass`; do not count those against the daily rounds.
3. Keep the rows on the phone until the server says accepted, duplicate or a final rejection (never drop a held sale).
