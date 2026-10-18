> **lead-backend, 2026-10-17 (lane/lead-backend):** done in `backend:config` (`ReleaseAdmin.kt`), registered in the app
> wiring and in `scope-cases.txt` (exempt: company-wide). SUPPORT reads, ADMIN registers drafts (CI retry of the same
> APK answers the same draft), SUPERADMIN publishes, blocks, retires and changes a published rollout; the author never
> publishes (409 `ERR_SEPARATION_OF_DUTIES`, also a table CHECK); If-Match versions (412). The signing certificate must
> equal `ARON_RELEASE_CERT_SHA256` when the server has it (set it from the release key's SHA-256 in the deploy
> environment; without it only the format is checked), the APK must be `https` and within `cfg.release.apk_max_mb`.
> Also fixed: `GET /v1/app/update-check` read `cfg.release.blocked_version_codes` as a list, but the key is per
> flavour (`{sr: [..]}`), so no version was ever blocked. The DPC `ManagedUpdater` wiring stays android's.

# qa → backend-admin (via lead): the release publish routes the contract has are not served (N-042), 2026-10-17

The contract defines `/v1/admin/releases`, `/v1/admin/releases/{release_id}` and `/v1/admin/releases/policy`. No
backend module serves them (`git grep "admin/releases" backend/` finds nothing). The web page
`web/src/app/admin/releases` calls them.

**Consequence:** a new SR build cannot be published for the phones' in-app update (core-sync `UpdateShell` reads
`GET /v1/app/update-check`). N-042's field path (`tools/checks/release-install-check.ps1 -ViaInAppUpdate`) therefore
cannot pass yet; only the adb path can be checked. The DPC's `ManagedUpdater` is not constructed in any app either.

**Ask:** serve the three operations as the contract has them (backend-admin, with a scope line in `scope-cases.txt`).
ContractConformanceTest validates them automatically once they are served.
