# backend-admin: Azure Blob SAS issuer (infra lane)

`F-API-030` (PDA upload), `/v1/admin/assets`, tutorials and admin content take a `BlobSasIssuer` (interface in
`backend/masterdata/.../SupportUploadApi.kt`: `writeSas(blobPath, maxBytes, expiresAt)` write-only, `readUrl(blobPath)`). Tests use a fake. Wiring uses
`UnconfiguredBlobSasIssuer` (503) until infra supplies an Azure implementation (user-delegation SAS via the managed identity, no account key in git or logs).

## Done 2026-10-07 (infra)

`backend/app/.../AzureBlobSasIssuer.kt`, wired in `Wiring.kt` when `ARON_BLOB_ACCOUNT` is set (Azure; set by
`infra/apps.bicep` with `ARON_BLOB_CONTAINER_MEDIA=media` and the api's `AZURE_CLIENT_ID`); 503 elsewhere as before.
User-delegation SAS via the api managed identity (Storage Blob Delegator + Blob Data Contributor already granted by
`infra/modules/identities.bicep`; the account refuses shared keys). `writeSas`: create+write on that one blob, HTTPS
only, start 5 min back for clock skew, expiry = `expiresAt` (a SAS cannot cap size: keep checking `maxBytes` before
issuing and on the blob-created event). `readUrl`: read for 24 h. Delegation key cached (2 days, renewed with 25 h left).
Note for backend: the blob-created queue (`media-events`) also receives these support/ and assets/ uploads; the worker
should ignore or handle those paths. A SAS is a bearer URL: never log it. Offline tests: `AzureBlobSasIssuerTest`.

**Follow-up for backend-admin (independent checker, 2026-10-07):** `AdminContentApi.kt:511` and `:537` store
`d.blob.readUrl(...)` in `content_item.asset_url`, and `contentOf` (`:436`) returns that stored value. A read URL is a
24-hour SAS, so the stored link expires the next day. Build it when reading from `blob_path` (as `:554` and
`TutorialsApi` do), or store only the blob path.
