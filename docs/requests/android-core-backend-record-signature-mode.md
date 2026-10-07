# android-core to backend-core: honour `cfg.sec.record_signature_mode` in ingest (F-SYS-072)

**Found while building F-SYS-072 on the phone (2026-10-07, android-core session 5).**

`backend/sync/.../IngestService.kt` step 6 quarantines every header record (`rule.signedHeader`) as
`DEVICE_INTEGRITY_FAILED` when the uploading device has a registered key and the record's `sig` is missing or does not
verify. There is no `cfg.sec.record_signature_mode` check. docs/19 sets the key to `record` in the pilot (`off < record <
enforce`), and the F-SYS-072 acceptance says "a bad signature is **flagged** in record mode and **rejected** in enforce mode".

Effect today: any enrolled phone running a build without record signing (every build before the android-core F-SYS-072
push) has its visits, memos, attendance, stock movements and dues quarantined. Nothing is lost on the phone (the rows are
terminal-quarantined but kept), but the sales do not reach the server.

Ask:
1. `off`: skip the check; `record`: accept the record and raise a signal (risk/integrity flag with the reason) instead of
   quarantining; `enforce`: quarantine as now.
2. Keep the message format as it is: `aron-sig-v1`, type, client_uuid, hex sha256 of JCS(record without `sig`). The
   phone signs exactly that (android-core `ProofStrings.record`, core-network `Jcs`, the same RFC 8785 vectors as
   backend `JcsTest`), once per row, and resends the same `sig` on every retry, so the batch fingerprint is stable.
3. Proposal: one shared test vector (public key JWK, record JSON, sig) verified by both sides, so a drift in either
   canonicaliser shows up in CI. The phone side already runs the backend's RFC 8785 vectors (core-network `JcsTest`);
   say if you want the signed vector and android-core will add it.
