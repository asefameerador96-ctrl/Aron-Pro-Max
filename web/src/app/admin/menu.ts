// Admin portal menu entries, contributed to the shared menu (src/lib/menu/menu.ts). Master-data tables are NOT listed one
// by one: they live on the master-data hub (/admin/master-data), grouped by section, so the menu stays short as entities grow.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

export const ADMIN_MENU: readonly MenuItem[] = [
  { id: "admin-home", labelKey: "menu.admin.home", href: "/admin", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-master-data", labelKey: "menu.admin.master_data", href: "/admin/master-data", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-audit", labelKey: "menu.admin.audit", href: "/admin/audit", roles: ADMIN_PORTAL_ROLES, group: "admin" },
];
