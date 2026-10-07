# Request from backend to db: bind refresh families to the phone's device_uuid

**Row:** F-SYS-002 / F-API-002 (refresh rotation, device binding). Checker finding D2.

**Need.** `app.refresh_family` binds a phone grant only through `device_id` (FK to `app.device`, V0010). A phone with no
`device` row (dev and test phones before enrolment, `cfg.device.require_enrolled = false`) cannot be bound, so its
refresh token could be replayed from another phone. Until this lands the API issues **no refresh or upload grant** to
a phone without a device row (it logs in again when the access token expires) and refuses any phone family whose
`device_id` is null.

**Shape.** One additive column, forward-only migration:

```sql
ALTER TABLE app.refresh_family ADD COLUMN device_uuid uuid;   -- the X-Device-Id the grant was issued to (phones)
ALTER TABLE app.refresh_family ADD CONSTRAINT refresh_family_phone_bound
  CHECK (client = 'web' OR device_id IS NOT NULL OR device_uuid IS NOT NULL) NOT VALID;
```

With it, the backend stores `device_uuid` at issue and requires `X-Device-Id = device_uuid` on every refresh, so dev
phones keep the refresh and upload grants while staying bound to one phone.

## Answer from the db lane (2026-10-07): done, `V0013__refresh_family_device_uuid.sql`

- `app.refresh_family.device_uuid uuid` (nullable) and `refresh_family_phone_bound`:
  `CHECK (client = 'web' OR device_id IS NOT NULL OR device_uuid IS NOT NULL OR revoked_at IS NOT NULL)`, validated.
- One difference from the requested shape, from the checker: PostgreSQL re-checks a `NOT VALID` CHECK on every later
  UPDATE of an old row, so a pre-existing unbound phone family could not even be revoked. The migration therefore
  revokes such families first (`revoke_reason = 'device_revoked'`; those phones log in again), and the check lets a
  revoked family stay unbound so it remains updatable.
- Partial index `refresh_family_device_uuid (device_uuid) WHERE device_uuid IS NOT NULL AND revoked_at IS NULL` for
  revocation by phone; refresh lookups stay on the unique `refresh_token.token_sha256`.
