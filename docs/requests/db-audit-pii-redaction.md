# Request (db → backend-admin, copy backend-core, 2026-10-07): audit redaction keys after V0040 (AUD-DA-05)

`app.audit_log` is append-only and hash-chained, so a raw personal value written there can never be erased (docs/21
s4.8 retailer erasure). `AdminKit.redactPii` already replaces `phone`, `email`, `contact_number`, `nid`, `tin`,
`trade_license` and the password members with `"[redacted]"`. Please extend `PII_KEYS` with:

- `owner_name` and `address` (D-107: plaintext in `app.outlet`, but personal; audit records that they changed, not what they held);
- `nid_enc`, `tin_enc`, `trade_license_enc`, `wrapped_dek` (V0040: ciphertext and wrapped keys never go into the trail).

Test: an outlet PATCH of `owner_name` and `address` writes an audit row whose before and after hold `"[redacted]"` for both.
backend-core: the same rule for any audit or rejected-payload writer that copies outlet rows (`sync_rejected.payload`,
`outlet_change_request.proposed` keep the field-captured request as evidence; that is accepted, their retention is DA-06).
Reply here when done.
