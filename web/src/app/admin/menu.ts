// Admin portal menu entries, contributed to the shared menu (src/lib/menu/menu.ts). Master-data tables are NOT listed one
// by one: they live on the master-data hub (/admin/master-data), grouped by section, so the menu stays short as entities grow.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES, OUTLET_REQUEST_READ_ROLES } from "@/lib/auth/roles";

export const ADMIN_MENU: readonly MenuItem[] = [
  { id: "admin-home", labelKey: "menu.admin.home", href: "/admin", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-master-data", labelKey: "menu.admin.master_data", href: "/admin/master-data", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-outlet-requests", labelKey: "menu.admin.outlet_requests", href: "/admin/outlet-requests", roles: OUTLET_REQUEST_READ_ROLES, group: "admin" },
  { id: "admin-wholesale", labelKey: "menu.admin.wholesale", href: "/admin/wholesale-marking", roles: ["ADMIN", "SUPERADMIN"], group: "admin" },
  { id: "admin-audit", labelKey: "menu.admin.audit", href: "/admin/audit", roles: ADMIN_PORTAL_ROLES, group: "admin" },
];
