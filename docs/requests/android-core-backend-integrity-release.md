# android-core to backend-core: two edges of the device_integrity_failed release (BC-53, F-SYS-072)

The phone now resends its rows quarantined `device_integrity_failed` by client_uuid (bounded: at most one round per
business date, three rounds in all, done once none is left; `SyncEngine.releaseIntegrityQuarantine`). The Opus checker
found two server-side edges this release reaches (lane/backend-core 70debbee):

1. **A discarded quarantine is released anyway (CONFIRMED).** OpsApi resolve `discard` / `return_to_device` updates only
   `sync_quarantine`; `ingest_registry` stays `quarantined` with `device_integrity_failed`, and IngestService step 2
   (~line 290) releases any such row on resend when the mode is not enforce. If the phone resends before the `discarded`
   resolution reaches it, a sale the reviewer discarded is stored, and the resolution then lands on an acked row.
   Ask: release at step 2 only while the `sync_quarantine` row is still `open` (or set the registry to rejected on discard).
2. **Old released rows fall into a new quarantine (PLAUSIBLE).** A released row older than `cfg.sync.max_backdate_days`
   meets step 3 and is quarantined `business_date_out_of_window`. Ask: skip the window check for a registry row that is
   being released (as for a parked resend), since it was inside the window when first received.

Also useful for the phone (optional): send `cfg.sec.record_signature_mode` in the bundle/config so the phone can stop
guessing (today it bounds the release in rounds because it cannot see the mode).
