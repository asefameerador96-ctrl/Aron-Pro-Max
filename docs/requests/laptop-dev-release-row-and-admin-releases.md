# Request (laptop session → infra, backend-admin): a real published SR release on dev, and the admin releases API

**Found 2026-10-16 while preparing the Honor device-owner enrolment** (docs/runbooks/honor-device-owner.md, device-checks
"Device-owner preparation"). The QR provisioning path (D-04 path B) cannot work on dev today for two reasons outside the
laptop session's reach; path A (adb `dpm set-device-owner`) is used instead.

1. **infra:** the published SR `app_release` row on dev is still the seed row from `infra/sql/devseed-app-release.sql`
   (09b): `release_id` 1, version `0.1.516` (code 516), a placeholder sha256 and `size_bytes` 20,000,000, and
   `download_url` = the GitHub Actions run page (`https://github.com/.../actions/runs/37652960567`, which answers
   `200 text/html`). Android's provisioning downloads the APK anonymously from `PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION`,
   so the URL must be an HTTPS APK (dev Blob behind Front Door, anonymous read, immutable path per release) and the row
   must describe the APK the QR will install (today: 0.1.722, sha256 `98f571c8…ef658`, 12,542,325 bytes, cert
   `468d9b4e…a4fb`). Ask: publish 722 (or the current INT release) as a real row with a real URL, and make the release
   workflow write the row on every release-signed build (`release-app.yml`).
2. **backend-admin:** `GET`/`POST /v1/admin/releases` are in `contract/openapi.yaml` (line 3154) but not implemented
   (404 on dev); the laptop had to read the published release through `GET /v1/app/update-check?flavour=sr`. Ask:
   build the admin releases routes so a release can be published from the admin portal (docs/19, F-ADM releases).

Both are needed before the first-boot QR flow is tried on the test phones; the in-app enrolment token path (D-ENR) and the
adb device-owner path are unaffected. The Android 12+ provisioning activities are a separate, already-filed request
(`android-geo-dpc-provisioning-activities.md`).
