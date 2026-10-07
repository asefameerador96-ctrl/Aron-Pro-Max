# backend-admin: Azure Blob SAS issuer (infra lane)

`F-API-030` (PDA upload), `/v1/admin/assets`, tutorials and admin content take a `BlobSasIssuer` (interface in
`backend/masterdata/.../SupportUploadApi.kt`: `writeSas(blobPath, maxBytes, expiresAt)` write-only, `readUrl(blobPath)`). Tests use a fake. Wiring uses
`UnconfiguredBlobSasIssuer` (503) until infra supplies an Azure implementation (user-delegation SAS via the managed identity, no account key in git or logs).
