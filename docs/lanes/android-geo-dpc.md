# Lane brief: android-geo-dpc

Session model: **Opus** (docs/29 s3). Owns: `android/core-geo`, `android/dpc`.

Read `docs/lanes/README.md` first.

- Scope: on-demand location with the mock flag, geofence on device against downloaded outlet coordinates, GNSS consistency capture, key attestation and Play Integrity token production, the device-owner policy (DPC): lock-down (developer options and USB debugging off in production, unknown sources off, uninstall blocked, permission pinning), enrolment by QR, **scheduled app blocking from check-in to check-out through `setPackagesSuspended`, applied on the phone so it works offline**, block-list delivery from config, device-proof headers, enrolled-device gate; anti-spoofing acceptance (`N-039`: at least five well-known mock-location apps and one cloning tool) and `N-042` developer-verification check.
- Spec: `docs/05`, `docs/23` s4 (geo integrity and device control), `docs/24` s11 and the device-policy sections. Be honest in your status file about what software cannot stop (RF-level simulators, modified hardware): they are flagged statistically by the server rules (backend-core).
- Testing needs the owner's factory-reset test phone; write the exact `adb` device-owner steps and scripts into `docs/setup/` and `docs/status/device-checks.md`, test everything possible on the emulator and with Robolectric or instrumented tests, and mark device parts `DEVICE-PENDING`.
- Every row is T1: Opus checker, adversarial about bypasses (clock, airplane mode, app clones, work profiles, accessibility services, ADB).
