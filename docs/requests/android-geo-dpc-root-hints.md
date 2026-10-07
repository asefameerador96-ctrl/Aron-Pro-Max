# Request (android-geo-dpc): root hints on the device status report

**Row:** F-SYS-031 ("developer options, USB debugging and root hints are read at login and with each batch and stored on the device record").

`DeviceStatusReport` carries `dev_options_enabled`, `adb_enabled` and `mock_location_apps`, but nothing for root, hook-framework or app-clone hints, and it is `additionalProperties: false`.

Needed (contract, then backend-core to store it on the device record and feed `DEVICE_INTEGRITY_FAIL` evidence):
```yaml
root_hints:
  type: array
  maxItems: 16
  items:
    type: string
    enum: [su_binary, test_keys, ro_debuggable, ro_secure_off, root_app, hook_framework, root_mount,
           clone_app_installed, secondary_user, foreign_data_dir]
```
Optional member, empty on a clean phone. These are hints only (weight them, never block a sale alone, docs/05).

**Until then (stub):** the phone computes them (`RootHints` in `android/core-geo`, marked `REQUEST:`) and sends a `device_status` with trigger `integrity_change` when developer options, ADB, auto time, device owner or mock apps change; the root hints themselves wait for this member.
