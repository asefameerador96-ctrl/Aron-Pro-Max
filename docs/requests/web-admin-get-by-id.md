# Request: GET one master-data row (lane web-admin, 2026-10-05)

**Need.** The edit page needs the current row and its `version` (for `If-Match`). The contract has `PATCH /v1/admin/clusters/{id}`
but no `GET /v1/admin/clusters/{id}` (geo nodes, routes, users and outlets do have a GET by id; clusters, SKUs,
product nodes, offers do not).

**Exact shape.** For each table that has a `PATCH` and no GET by id, add `GET /v1/admin/<table>/{id}` returning the same
schema as the list items (e.g. `Cluster`), with `ETag: "<version>"`, `404 ERR_NOT_FOUND` outside the caller's reach.

**Local stub.** `web/src/components/admin/crud/server.ts: getRow` reads the list page the user came from (cursor hint),
then scans up to 20 pages of 500 rows. Correct but wasteful for large tables. Entities with a GET set
`api.get` in their metadata and skip the scan.
