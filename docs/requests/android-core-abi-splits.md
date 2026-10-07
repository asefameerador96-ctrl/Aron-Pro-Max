# Request to android-core: per-ABI release APKs (AUD-DG-03, F-SYS-036)

From: infra lane, 2026-10-07.

## What CI does now (no change needed from you)

- Every CI run builds the three apps with `-Paron.apiBaseUrl=<dev Front Door origin>`, `-Paron.versionCode=<run number>`
  and `-Paron.versionName=0.1.<run number>`. So a phone installed from one run upgrades to the next.
- On integration-branch pushes, the three release APKs are signed with the release key by `apksigner` in CI and
  uploaded as `aron-release-signed-dev-<run>`, with SHA256SUMS. The key never enters the Gradle build, so the apps need
  **no `signingConfig`**. `release-app.yml` signs the final releases the same way.

## Ask

The release APK carries native libraries for four ABIs. Today's SR release is 10.2 MB as a universal APK; arm64-v8a
alone is 4.3 MB and armeabi-v7a 3.2 MB (`tools/ci/apk-size-gate.py`).

Please add ABI splits to the release build of the three apps in `android/app-*/build.gradle.kts`:

```kotlin
splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = true } }
```

- Keep the universal APK. The device-owner provisioning QR downloads one URL, and its checksum pins the signing
  certificate, not the file. So the universal APK stays the provisioning file, and the per-ABI APKs are what the updater
  ships (docs/24 s10).
- Decide whether x86/x86_64 are needed (emulators only).

Tell infra when it lands. The size gate then measures each split as built, and the baseline in
`tools/ci/apk-size-baseline.json` is regenerated (`--write-baseline`).

## Update 2026-10-07 (infra): CI is ready for the splits

`tools/ci/release-apks.py` lists either layout (one APK per app, or `app-<x>-<abi>-release-unsigned.apk` plus
`app-<x>-universal-release-unsigned.apk`) and fails without the universal APK or on an unknown file name. The size gate,
the dev signing in `ci.yml` and `release-app.yml` all use it: the universal keeps the gate name `sr-release` (and its
baseline), each split is gated as `sr-release-<abi>` against the absolute budget until the baseline is regenerated, and
signed copies are named `aron-sr-<abi>-<version>.apk`. So turning the splits on needs no CI change; infra regenerates
the baseline after the first green run.
