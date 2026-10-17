# qa → backend-admin (via lead): the release publish routes the contract has are not served (N-042), 2026-10-17

The contract defines `/v1/admin/releases`, `/v1/admin/releases/{release_id}` and `/v1/admin/releases/policy`. No
backend module serves them (`git grep "admin/releases" backend/` finds nothing). The web page
`web/src/app/admin/releases` calls them.

**Consequence:** a new SR build cannot be published for the phones' in-app update (core-sync `UpdateShell` reads
`GET /v1/app/update-check`). N-042's field path (`tools/checks/release-install-check.ps1 -ViaInAppUpdate`) therefore
cannot pass yet; only the adb path can be checked. The DPC's `ManagedUpdater` is not constructed in any app either.

**Ask:** serve the three operations as the contract has them (backend-admin, with a scope line in `scope-cases.txt`).
ContractConformanceTest validates them automatically once they are served.
