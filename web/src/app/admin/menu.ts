// Admin portal menu entries, contributed to the shared menu (src/lib/menu/menu.ts). Master-data tables are NOT listed one
// by one: they live on the master-data hub (/admin/master-data), grouped by section, so the menu stays short as entities grow.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES, AUDIT_READ_ROLES, OUTLET_REQUEST_RAISE_ROLES, OUTLET_REQUEST_READ_ROLES, TEAM_CREDENTIAL_ROLES } from "@/lib/auth/roles";

export const ADMIN_MENU: readonly MenuItem[] = [
  { id: "admin-home", labelKey: "menu.admin.home", href: "/admin", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-master-data", labelKey: "menu.admin.master_data", href: "/admin/master-data", roles: ADMIN_PORTAL_ROLES, group: "admin" },
  { id: "admin-outlet-requests", labelKey: "menu.admin.outlet_requests", href: "/admin/outlet-requests", roles: OUTLET_REQUEST_READ_ROLES, group: "admin" },
  { id: "admin-outlet-change", labelKey: "menu.admin.outlet_change", href: "/admin/outlet-change", roles: OUTLET_REQUEST_RAISE_ROLES, group: "admin" },
  { id: "admin-team", labelKey: "menu.admin.team", href: "/admin/team", roles: TEAM_CREDENTIAL_ROLES, group: "admin" },
  { id: "admin-radius", labelKey: "menu.admin.radius", href: "/admin/radius", roles: ["TSO"], group: "admin" },
  { id: "admin-wholesale", labelKey: "menu.admin.wholesale", href: "/admin/wholesale-marking", roles: ["ADMIN", "SUPERADMIN"], group: "admin" },
  { id: "admin-mdm", labelKey: "menu.admin.mdm", href: "/admin/mdm", roles: ["ADMIN", "SUPERADMIN"], group: "admin" },
  { id: "admin-audit", labelKey: "menu.admin.audit", href: "/admin/audit", roles: AUDIT_READ_ROLES, group: "admin" },
  // tso-manual W1: the support console (TK-16). Shown only with Me.support_desk: its module `support` is withheld from the menu
  // grants without the desk permission (lib/admin/modules.ts grantsOf, D-TSO-MAN-22), and an ADMIN also needs the module grant.
  { id: "admin-tickets", labelKey: "tickets.menu", href: "/admin/tickets", roles: ADMIN_PORTAL_ROLES, group: "admin" },
];
