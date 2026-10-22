# Lane brief: web-admin

Session model: **Sonnet** (docs/29 s3). Owns: `web/src/app/admin` master-data pages.

Read `docs/lanes/README.md` first.

- Scope: master-data admin pages: geography, routes and assignment, product hierarchy, prices (five types, effective dated), sales plan, users and scope, outlets and classification, task types, QC fault types, tutorials, SR lifecycle wizard, outlet approval and reactivation, SR transfer, wholesale marking, retailer detail. No offers or promotion pages (docs/27).
- Use the admin CRUD generator and kit from N-010 and N-011; every write sends a reason and `If-Match`; role-gated by the permission matrix. Requests already filed by the web lane (create-reason, get-by-id, password flow, refresh cookie) are routed by the lead: check `docs/requests/` status before stubbing.
- The configuration console, device, release, audit and day-control pages belong to lane `web-config`.

## Wave 4 (owner directive 2026-10-22, docs/34 s1, s2, s4.2)

Admin pages: users with role, module grants (per module r/w, for ADMIN) and brand scope (for BRAND) against
backend-authz's API; route monthly target upload (Excel: route, SKU, month, quantity in base units; preview,
validation report, commit) and the calendar / holidays page (minimum target slice, D-TARGET-MIN-01); house
active flag and the de-duplication view; outlet channel mandatory in the outlet form and import; SKU form
fields `base_per_pack`, `entry_unit_default`, pack label, with a preview "1 packet = 20 sticks"; packshot
upload stays as built. Every write audited with a reason and `If-Match`.
