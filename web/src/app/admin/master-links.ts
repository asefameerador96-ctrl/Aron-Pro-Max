// Master-data pages that are not generated entities or code lists but belong on the master-data hub (F-ADM-046).
import type { MasterGroup } from "@/components/admin/crud/meta";
import { ADMIN_PORTAL_ROLES, type RoleList } from "@/lib/auth/roles";
import type { MessageKey } from "@/lib/i18n";

export interface MasterLink {
  id: string;
  href: string;
  labelKey: MessageKey;
  group: MasterGroup;
  roles: RoleList;
}

export const MASTER_LINKS: readonly MasterLink[] = [
  // The working-day calendar: weekend days (a config change) and holidays, make-up and emergency off-days.
  { id: "calendar", href: "/admin/calendar", labelKey: "cal.title", group: "lists", roles: ADMIN_PORTAL_ROLES },
  { id: "prices", href: "/admin/prices", labelKey: "menu.admin.prices", group: "products", roles: ADMIN_PORTAL_ROLES },
  { id: "sales-plan", href: "/admin/sales-plan", labelKey: "menu.admin.sales_plan", group: "products", roles: ADMIN_PORTAL_ROLES },
  { id: "sr-transfer", href: "/admin/sr-transfer", labelKey: "menu.admin.sr_transfer", group: "routes", roles: ADMIN_PORTAL_ROLES },
  { id: "code-lists", href: "/admin/code-lists/qc_fault_type", labelKey: "menu.config.qc_faults", group: "lists", roles: ADMIN_PORTAL_ROLES },
  { id: "print-templates", href: "/admin/print-templates", labelKey: "menu.config.print_templates", group: "lists", roles: ADMIN_PORTAL_ROLES },
  { id: "sr-lifecycle", href: "/admin/sr-lifecycle", labelKey: "menu.admin.sr_lifecycle", group: "routes", roles: ADMIN_PORTAL_ROLES },
];
