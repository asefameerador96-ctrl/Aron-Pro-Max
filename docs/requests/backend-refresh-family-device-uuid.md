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
