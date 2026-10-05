# Request from backend to db: seed accounts that can log in (N-008)

**Rows waiting on this:** F-SYS-001 / F-API-001 ("POST /auth/login for the seeded SR ..."), F-SYS-005 ("A seeded TSO token ..."), N-017 ("On a seeded Sunday ...").

The backend login, refresh, scope and day-plan code is on the integration branch and tested against its own fixtures. To run the acceptance tests on the seed itself, the N-008 seed needs:

1. **Password hashes the API verifies.** `app.app_user.password_hash` as an Argon2id PHC string (`$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>`); any parameters are fine (the API reads them from the string; the server uses m=19456, t=2, p=1). No plaintext password and no shared default password in the repository: please derive the hashes at seed time from an environment variable (for example `ARON_SEED_PASSWORD`, set locally and as a GitHub secret in CI), or seed `password_hash = NULL` and let the backend's `POST /v1/admin/users/{id}/credentials` reset flow set it.
2. **`must_change_password = false`** for the test accounts that the acceptance tests use (the column defaults to true; with true, login answers `password_change_required` and no refresh grant).
3. **Users:** one SR (username matching `^[a-z][a-z0-9]{3,31}$`) with three `route_assignment` rows (primary, open-ended) on a Daily (mask 127), a 3F Sun/Tue/Thu (mask 42) and a 2F Mon/Thu (mask 36) route; one TSO with a `user_scope` row (`territory`) covering those routes' zone; one AMO with a `zone` scope row; one ADMIN.
4. **Device for the SR (after N-007/V0010):** an `active` device row and an active `device_binding` with `bind_ordinal` 0, so a dev login returns `status: ok` instead of `bind_required`. The backend reads the device by `device_uuid`; tell me the table and column names when V0010 lands and I will wire the device store (today the API runs with no device store and relies on `cfg.device.require_enrolled = false` in the dev database).
5. **Calendar:** nothing on the test Sunday and Friday is needed (Friday is the weekend by default); a holiday row for any other date is welcome for the calendar test.
6. **Stable ids or codes** for those users and routes (codes are enough), so the tests can find them.

Reply in this file or in `docs/status/db.md`.

## Answer from the db lane (2026-10-05, N-008)

Done in `db/seed` (load with `ARON_SEED_DB_URL=... ARON_SEED_PASSWORD=... ./gradlew :db:seed`):

1. **Password hashes:** `SeedLoader` sets `password_hash` to the Argon2id PHC string of `ARON_SEED_PASSWORD`
   (m=19456, t=2, p=1, 16-byte salt, 32-byte hash, argon2-jvm like `PasswordHasher`) on every test account (`pilot`)
   that has none. Without the variable the accounts have no password. Nothing secret is in the repository.
2. **`must_change_password = false`** on all seeded test accounts.
3. **Users and routes (codes are stable):** SR `sr1001`, primary and open-ended on `MIR-SR-D` (Daily, 127),
   `MIR-SR-3F` (Sun/Tue/Thu, 42) and `MIR-SR-2F` (Mon/Thu, 36), 20 outlets each (`MIR-D-001`..`MIR-2F-020`);
   TSO `tso1001` with a `territory` scope on `T-DHK-N` (zone `Z-MIR`); AMO `amo1001` with a `zone` scope on `Z-MIR`;
   ADMIN `admin1001`; also `dmo1001` (division `D-DHK`), `superadmin1001`, `support1001` (national).
4. **Device:** `app.device` (V0010), row with `device_uuid = 00000000-0000-4000-8000-000000000001`, `status`
   `active`, `flavour` `sr`; `app.device_binding (device_id, user_id, bind_ordinal, status)` holds the active binding
   of `sr1001` with `bind_ordinal` 0. Its public key is a placeholder (cannot sign proofs); dev only.
5. **Calendar:** global holiday 2026-12-16 (Victory Day) in `app.calendar_holiday`; nothing on Sundays or Fridays.
6. Dev overrides (`require_enrolled`, `lockdown_level`, `require_integrity`) are global `cfg_value` rows of their own
   config version (summary `Dev database overrides (docs/24 s9.4, seed)`).
