# Request (android-geo-dpc): an explicit "Play Integrity unavailable" marker, and the requestHash encoding

**Row:** N-026. Acceptance: "a phone without Google services sends an explicit unavailable marker instead of failing".

## 1. Marker
The contract has `DeviceStatusReport.play_integrity`: `PlayIntegrityEvidence | null`. A null cannot say *why* there is no token (no Play services, offline, API error, timeout, not configured), and the server cannot tell "never tried" from "tried and Google services are missing".

Needed: one optional member on `DeviceStatusReport` (and on `EnrolDeviceRequest`):
```yaml
play_integrity_unavailable:
  oneOf:
    - type: object
      required: [reason]
      additionalProperties: false
      properties:
        reason: { type: string, enum: [no_play_services, not_configured, offline, api_error, timeout] }
        detail: { type: ['string', 'null'], maxLength: 80 }
    - type: 'null'
```
Exactly one of `play_integrity` and `play_integrity_unavailable` is non-null when the phone tried.

**Until then (stub):** the phone sends `play_integrity: null` and keeps the reason locally (`IntegrityUnavailable` in `android/core-geo`, marked `REQUEST:`).

## 2. requestHash encoding
`DeviceNonce` says requestHash = sha256(nonce + device_uuid) but not the encoding. The phone uses **lower-case hex of SHA-256 over the UTF-8 bytes of nonce followed by device_uuid** (64 characters). backend-core: please confirm or name another encoding, and decode with the same.
