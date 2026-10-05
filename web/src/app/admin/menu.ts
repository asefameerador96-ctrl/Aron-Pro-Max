// Admin portal menu entries, contributed to the shared menu (src/lib/menu/menu.ts). Entities add themselves.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";
import { ENTITIES } from "./_entities/registry";

export const ADMIN_MENU: readonly MenuItem[] = [
  { id: "admin-home", labelKey: "menu.admin.home", href: "/admin", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  ...ENTITIES.map((e): MenuItem => ({ id: `admin-${e.slug}`, labelKey: e.labelKey, href: `/admin/${e.slug}`, roles: e.readRoles, group: "admin" })),
  { id: "admin-audit", labelKey: "menu.admin.audit", href: "/admin/audit", roles: ADMIN_PORTAL_ROLES, group: "admin" },
];
