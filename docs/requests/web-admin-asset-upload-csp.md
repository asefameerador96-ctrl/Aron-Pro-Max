# Request: blob origin for browser uploads (CSP and CORS)

Lane: web-admin (F-ADM-026, F-ADM-065, F-ADM-020).

The admin portal uploads tutorial, content and SKU files with a PUT to the write-only SAS URL from `POST /v1/admin/assets`, straight from the browser. This needs:
1. infra: set `ARON_BLOB_ORIGIN` (one https origin, the storage account) on the web app. The web CSP adds it to `connect-src` only when set (`web/src/lib/security-headers.ts`).
2. infra: a CORS rule on the storage account for the web origin, method PUT, headers `x-ms-blob-type` and `content-type`.
