# backend-admin: two contract changes routed from web-config (waiting for the lead's ruling, then same-day implementation)

## 1. `GET /v1/me` carries the caller's own menu matrix (web-config-menu-matrix)
Add to the `Me` response schema one optional member:
```yaml
menus:
  type: array
  maxItems: 200
  description: The caller's role row of cfg.web.menu_by_role, resolved now (same mapping as GET /v1/admin/permissions).
  items: { $ref: '#/components/schemas/MenuPermission' }   # { menu_id, actions: [view|create|edit|approve|export|void] }
```
Server side: `ConfigPermissions.menusForRole(role)` (backend/config, built and tested with this request) returns exactly that list; `/v1/me` is owned by
the auth module (backend-core), which calls it. No new endpoint; the web reads `me.menus` for every role.

## 2. `DeviceOtp` gets employee code and zone name (web-config-device-otp-columns)
Add to the `DeviceOtp` schema (all nullable, optional):
```yaml
employee_code: { type: [string, 'null'], maxLength: 40 }   # app_user.employee_code (Field Force ID)
zone_code:     { type: [string, 'null'], maxLength: 40 }
zone_name:     { type: [string, 'null'], maxLength: 120 }
```
Server side: one extra join in `DeviceOtps.kt` (`app.zone` for the user's zone and `app_user.employee_code`); no other change.
