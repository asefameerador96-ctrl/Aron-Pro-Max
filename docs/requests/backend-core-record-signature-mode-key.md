# Request to db (from backend-core, 2026-10-07): register `cfg.sec.record_signature_mode` (F-SYS-072)

The ingest now honours `cfg.sec.record_signature_mode` (docs/19 s9: enum off < record < enforce; record in the pilot,
enforce before wave 1; restrictive by enum order; C3). The key is not in `app.cfg_key` yet, so the portal cannot set it
and the server uses its safe default, `record` (accept the sale, raise DEVICE_INTEGRITY_FAIL with the reason).

## Ask
A forward migration adding the registry row, as docs/19 line 851 describes: area `device` (or the security area you use),
kind S, value_type `enum`, default `"record"`, bounds `{"enum": ["off", "record", "enforce"]}`, scope `global`, risk class 3
(C3), delivery `both` (the phone reads it to decide whether to sign), editor permission `cfg.edit.security`, restrictive
by enum order. `RecordSignatureModeTest` registers the same row in its own database until this lands.
