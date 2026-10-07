# Request: web-config -> backend-admin and web-dashboard: menus from the matrix for every role

Row: F-ADM-064 (role x menu x action matrix editor; "an admin changes a role's menu and the user sees the change without a deployment").

Done in `web/`: the editor (`/admin/permissions`) writes the matrix (a C3 change request); `web/src/lib/menu/matrix.ts` reads
`GET /v1/admin/permissions` and `menuFor(role, menu, allowed)` hides menu items whose `menuId` the matrix does not grant. Items carry a
`menuId` (page-registry id); items without one always show.

Gap: `GET /v1/admin/permissions` is readable only by ADMIN and SUPERADMIN, so TSO, DMO, WM and the other web roles still get their menu
from the static role lists. Needed: the user's own menus and actions from the API, for example `menus` on `GET /v1/me` (the resolved
`cfg.web.menu_by_role` row of the caller, `[{menu_id, actions}]`). When it exists, `loadAllowedMenus` calls it for every role.
The dashboard lane's menu items need a `menuId` (the page-registry id of docs/19 s5.3) to follow the matrix.
// REQUEST: web-config-menu-matrix
