# backend-admin lane status

Updated 2026-10-07.

## Done (built, checker findings fixed, pushed)
- **F-API-037** config registry, scoped values, resolution, change workflow C0-C3, versions, audit (`backend/config`). Opus checker: 9 confirmed defects (500s on window edge cases, radius escalation by resolved value, freeze-window validation, scope node existence, late approval of future-dated keys, idempotency race, required `value`), all fixed with the checker's tests (`ConfigRefuteTest`).
- **F-API-040** `GET /v1/config/delta` (as-of-version resolution, ETag, 304, 410).
- **F-API-036 / F-TSO-019** `GET/POST /v1/admin/device-otps` (`backend/masterdata/DeviceOtps.kt`), AES-GCM, reach-scoped, audited, replay window 60 s. Opus checker pending.

## Blocked / requests
- `docs/requests/backend-admin-contract-gaps.md`: F-API-041 (needs the sync RecordHandler registry), F-API-083 (`/config/check` not in the contract), F-API-037 path names, audit writer, OTP sealing for F-SYS-003.
- F-SYS-013 needs F-SYS-012 (backend-core).

## Next three rows
F-API-035 (generic /admin CRUD), F-ADM-012 (geofence config), F-API-058/059/061/062 config tools.

## Traps
- Tests need `ARON_TEST_PG_URL`; Maven mirror init script in `~/.gradle/init.d` (not in git).
- Test clocks: two changes at one scope in one millisecond are bumped by 1 ms.
