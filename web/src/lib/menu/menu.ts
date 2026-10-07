// The menu is data: role -> items. Add an entry here (or contribute one from a route group, see ADMIN_MENU) and the
// shell renders it for the roles listed; nothing in the layout names a role (docs/23: menu driven from data).
import { ADMIN_MENU } from "@/app/admin/menu";
import type { Role } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { WEB_ROLES, type RoleList } from "@/lib/auth/roles";
import { WEB_REPORTS } from "@/lib/reports/catalog";

export type MenuGroup = "main" | "reports" | "admin";

export interface MenuItem {
  id: string;
  labelKey: MessageKey;
  href: string;
  roles: RoleList;
  group: MenuGroup;
}

export const DASHBOARD_MENU: readonly MenuItem[] = [
  { id: "dashboard", labelKey: "menu.dashboard", href: "/", roles: WEB_ROLES, group: "main" },
  ...WEB_REPORTS.map((r): MenuItem => ({ id: `report-${r.slug}`, labelKey: r.titleKey, href: `/reports/${r.slug}`, roles: r.roles ?? WEB_ROLES, group: "reports" })),
];

export const MENU: readonly MenuItem[] = [...DASHBOARD_MENU, ...ADMIN_MENU];

export interface MenuSection {
  group: MenuGroup;
  labelKey: MessageKey;
  items: MenuItem[];
}

const GROUP_LABEL: Record<MenuGroup, MessageKey> = { main: "menu.group.main", reports: "menu.group.reports", admin: "menu.group.admin" };

export function menuFor(role: Role, menu: readonly MenuItem[] = MENU): MenuSection[] {
  const sections: MenuSection[] = [];
  for (const group of ["main", "reports", "admin"] as const) {
    const items = menu.filter((m) => m.group === group && m.roles.includes(role));
    if (items.length) sections.push({ group, labelKey: GROUP_LABEL[group], items });
  }
  return sections;
}
