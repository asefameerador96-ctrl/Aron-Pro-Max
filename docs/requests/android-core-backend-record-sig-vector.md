# android-core to backend-core: shared signed record vector and key rotation (F-SYS-072 residuals b, c)

**Filed 2026-10-07 by android-core (seventh session).** Routed by the lead. No phone change waits on this; the row
F-SYS-072 closes on the phone side when both items below are on INT.

## (c) Shared signed test vector (JCS number parity)

The phone verifies this vector in `android/core-network/.../RecordSignatureVectorTest.kt`. Please add the same vector to a
backend test (`backend/sync` next to `JcsTest`), parsed with the backend's own JSON reader and checked with
`DeviceProof.verify`, so a drift in either canonicaliser fails CI on that side. The private key was discarded after signing.

- Public key (JWK): `{"kty":"EC","crv":"P-256","x":"cq08ZII0pgKMNoz1iDBej6jWE1hunj_VMbhAzZU9Ofs","y":"iJ90f0fLPNmSZObwdOgjrUOn9XS8Kp47czd8PKzp9tk"}`
- Record as sent (non-canonical on purpose: member order, `1.5e3`, `0.0000010`, `1E21`, `-0.50`, `90.40`, `12.0`, an escaped quote, Bangla):
  ```json
  {"type":"visit","client_uuid":"7d1f3c2a-5b4e-4f6a-9c8d-1e2f3a4b5c6d","ratios":[1.5e3,0.0000010,1E21,-0.50],"geo":{"mock":false,"lng":90.40,"lat":23.7925120},"note":"দোকান \"১\"","amount_mtk":1250000,"accuracy_m":12.0,"captured_at":"2026-10-05T04:36:00.000Z","business_date":"2026-10-05","sig":"WQaua1-grkZ_K22PSezYv4YfX0YRw36mFJOvv3Bc_JojzsBg-oVphU7IbTPPzFPLDFqEnZlkHdtyHTFSyforGA"}
  ```
- RFC 8785 form of the record without `sig`:
  ```json
  {"accuracy_m":12,"amount_mtk":1250000,"business_date":"2026-10-05","captured_at":"2026-10-05T04:36:00.000Z","client_uuid":"7d1f3c2a-5b4e-4f6a-9c8d-1e2f3a4b5c6d","geo":{"lat":23.792512,"lng":90.4,"mock":false},"note":"দোকান \"১\"","ratios":[1500,0.000001,1e+21,-0.5],"type":"visit"}
  ```
- SHA-256 (hex) of its UTF-8 bytes: `f519c2fcc16ba77af3e9eafda02f879086ef389a53aa5a730bddd197d60a5308`
- Message: `aron-sig-v1\nvisit\n7d1f3c2a-5b4e-4f6a-9c8d-1e2f3a4b5c6d\nf519c2fc...5308`; the `sig` above verifies over it
  (ES256, raw r||s, base64url without padding).

## (b) Key rotation: rows signed with the previous device key

IngestService step 6 verifies `sig` against `ctx.up.deviceKey`, the key of the uploading device row. A re-enrolment
creates a new Keystore key and a new device row (DeviceService refuses a second key on the same `device_uuid`). Header records signed with the old key that had not
reached the server yet then fail step 6 and are quarantined `device_integrity_failed`; the phone's BC-53 release resends
them byte-identical (same stored `sig`), so they never get in (today under any setting: `cfg.sec.record_signature_mode`
is not read by ingest yet, ask 1 of android-core-backend-record-signature-mode.md).

The phone cannot re-sign them safely: the registry hash (`Rec.hash`, step 2) covers the whole record including `sig`, so
a row the server already saw (a lost answer) and re-sent with a new `sig` is quarantined `payload_conflict`. Earlier ask 4
in android-core-backend-record-signature-mode.md (leave `sig` out of the registry hash) is still open.

Ask, either of:
1. In step 6, when the current key does not verify, also try the keys of earlier device rows of the same enrolment
   lineage (same user and hardware, retired within a grace period: a new key `cfg.sec.record_sig_key_grace_days`, android-core proposes 14), for records whose
   `captured_at` is before the new key's enrolment; or
2. Do ask 4 (sig out of `Rec.hash`), then tell android-core: the phone will re-sign unsent and quarantined header rows
   once after a key change.

Phone behaviour today: a stored `sig` is never changed (tested, `RecordSignatureTest`); a row is signed when its first
batch is assembled, so rows assembled after the key change carry the new key's `sig`; an enrolled phone whose Keystore fails holds the header rows for at most 30 minutes of elapsed
time, then sends them unsigned with `X-Last-Sync-Error: device_key_unavailable` until a signature succeeds again (tested).
On today's backend those unsigned headers are quarantined `device_integrity_failed`: they are on the server for review,
not accepted. Ask 1 of the signature-mode request (log, do not quarantine, in `record` mode) is what lets them in.
