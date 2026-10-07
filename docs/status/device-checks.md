# Device checks (DEVICE-PENDING rows)

Rows whose acceptance needs a real phone. Everything that can run without one is automated in the module tests; each entry says exactly what to do on the phone. Setup: `docs/setup/device-owner-test-phone.md`.

## N-021 location fix manager (android-geo-dpc)
Host proof: `ScriptedSrDayTest` (60-outlet day: 68 provider requests, at most 80; every fix stored with its mock flag).
On the Galaxy A06, with the SR app:
1. `adb shell dumpsys batterystats --reset`, then run a 60-outlet scripted day (check-in, 60 outlet opens, check-out).
2. `adb shell dumpsys location > loc.txt` and `adb shell dumpsys batterystats > bs.txt`.
3. Pass: at most 80 location requests by `com.aktcl.aron.sr`, no request still active after each outlet, GPS time at most 15 min; `geo_fix.is_mock` set on every row (export the day's records from the outbox).
4. Repeat steps with a mock-location app selected (dev phone): every fix has `is_mock = 1`.

## N-026 key attestation and Play Integrity (android-geo-dpc)
Host proof: `IntegrityCodecTest`, `IntegrityEvidenceServiceTest`, `DeviceKeySpecTest`.
On each test phone: `./gradlew :android:core-geo:connectedDebugAndroidTest --tests '*DeviceKeyStoreDeviceTest'` (key created, attestation extension present, signature verifies with the sent JWK). Play Integrity needs the Google Cloud project number in the build; on a phone without Google services the status report carries no token and the reason `no_play_services`.

## N-029 device-owner policy core (android-geo-dpc)
Host proof: `PolicyApplierTest`, `DevicePolicyContractTest`, `AndroidDpmGatewayTest`.
On an enrolled Galaxy A06 with the **prod** policy applied (last check: see the warning in the setup page):
1. Settings > About phone > tap Build number seven times: developer options do not turn on.
2. Settings > Apps > Aron SR: Uninstall and Force stop are greyed out; `adb uninstall` is impossible (USB debugging is off).
3. Settings > Apps > Aron SR > Permissions > Location: cannot be changed from "Allow".
4. Settings > General management > Reset > Factory data reset: refused ("blocked by your IT admin").
5. Reboot: after boot, `dumpsys device_policy` (from a dev phone, or the status report in the portal) shows the same restrictions.
