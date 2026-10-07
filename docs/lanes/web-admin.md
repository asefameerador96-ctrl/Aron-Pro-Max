# Lane brief: web-admin

Session model: **Sonnet** (docs/29 s3). Owns: `web/src/app/admin` master-data pages.

Read `docs/lanes/README.md` first.

- Scope: master-data admin pages: geography, routes and assignment, product hierarchy, prices (five types, effective dated), sales plan, users and scope, outlets and classification, task types, QC fault types, tutorials, SR lifecycle wizard, outlet approval and reactivation, SR transfer, wholesale marking, retailer detail. No offers or promotion pages (docs/27).
- Use the admin CRUD generator and kit from N-010 and N-011; every write sends a reason and `If-Match`; role-gated by the permission matrix. Requests already filed by the web lane (create-reason, get-by-id, password flow, refresh cookie) are routed by the lead: check `docs/requests/` status before stubbing.
- The configuration console, device, release, audit and day-control pages belong to lane `web-config`.
