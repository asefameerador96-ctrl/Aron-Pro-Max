# backend-admin: backlog rows whose path or handler the contract and platform do not name yet

Filed by the backend-admin lane, 2026-10-07. The lane built each row against the contract's own path and kept going.

1. **Row F-API-037 names `GET and PUT /admin/config` and `GET /config/snapshot`.** The contract has no such paths. The write path is
   `POST /v1/admin/config/changes` (createConfigChange); reads are `GET /v1/admin/config/{keys,values,resolve,changes,versions}`; the
   phone reads config through the day bundle (`config.values`) and `GET /v1/config/delta`. Built exactly as the contract names them.
   The bundle's snapshot is `ConfigResolver.resolveAll(chain, at)` (one statement), for the bundle owner (backend-core, F-API-005) to call.
   No contract change is needed unless the lead wants a standalone snapshot path.
2. **Row F-API-083 `GET /config/check` has no path in the contract.** Its behaviour (304 or the delta inline, at most once per
   `cfg.sync.config_check_min_gap_min`) is the existing `GET /v1/config/delta` with `If-None-Match`; the minimum-gap cache is a client rule
   (`cfg.sync.config_check_min_gap_min`, delivered to the phone). Request: either add `GET /v1/config/check` to the contract with the
   same body as `getConfigDelta`, or close F-API-083 as covered by `getConfigDelta` (the server answers 304 whenever the phone is current).
3. **Row F-API-041 `POST /config/ack`**: the acknowledgement is the sync record `config_ack` (contract `ConfigAckRecord`), not an endpoint.
   The `app.cfg_ack` table exists (V0007); the ingest `RecordHandler` for `config_ack` belongs to the sync module (backend-core, F-API-006),
   which has no `RecordHandler` registry yet. Reach reads (`F-API-063`) will read `app.cfg_ack` and `app.device.config_version_applied`.
   Blocked on the registry; the handler is ten lines once it exists.
4. **Audit writer.** `platform` has no audit writer yet (F-SYS-059, backend-core). backend-admin writes `app.audit_log` rows through
   `com.aktcl.aron.backend.config.AuditWriter` (same columns, inside the caller's transaction). When F-SYS-059 lands, replace it by the platform class.
5. **Device OTP creation at the bind attempt (F-SYS-003, backend-core)** must seal the OTP with `masterdata.OtpCipher`
   (AES-256-GCM, AAD `device_otp:<user_id>`, label `aron-device-otp-v1`) so the TSO panel can show it.
