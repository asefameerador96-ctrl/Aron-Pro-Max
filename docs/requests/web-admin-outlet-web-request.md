# Request: raising an outlet change request from the web (lane web-admin, 2026-10-07)

F-WEB-003 says an edit of location, name or owner "raises a request unless the editor holds approve rights". The contract has no
operation to create an outlet request from the web (requests arrive only as `outlet_change_request` sync records from phones).
The portal therefore lets only ADMIN and SUPERADMIN (approve rights, docs/24 s8.5) edit an outlet directly; other roles see no
edit control.

**Ask.** If non-approvers (TSO, DMO) must edit these fields on the web, add `POST /v1/outlet-requests` (web source, same
`OutletProposal`, `request_uuid` from the client, idempotent) so the portal can offer "propose change". State: open; no portal
code is waiting on it.
