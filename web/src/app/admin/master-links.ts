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
];
