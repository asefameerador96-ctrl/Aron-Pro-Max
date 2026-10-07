// The menu is data: role -> items. Add an entry here (or contribute one from a route group, see ADMIN_MENU) and the
// shell renders it for the roles listed; nothing in the layout names a role (docs/23: menu driven from data).
import { ADMIN_MENU } from "@/app/admin/menu";
import type { Role } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";
import { WEB_ROLES, type RoleList } from "@/lib/auth/roles";
import { WEB_REPORTS } from "@/lib/reports/catalog";

export type MenuGroup = "main" | "reports" | "products" | "admin";

export interface MenuItem {
  id: string;
  labelKey: MessageKey;
  href: string;
  roles: RoleList;
  group: MenuGroup;
}

export const DASHBOARD_MENU: readonly MenuItem[] = [
  { id: "dashboard", labelKey: "menu.dashboard", href: "/", roles: WEB_ROLES, group: "main" },
  { id: "daily-tracking", labelKey: "menu.daily_tracking", href: "/daily-tracking", roles: WEB_ROLES, group: "main" },
  { id: "tso-daily-tracking", labelKey: "menu.tso_daily_tracking", href: "/tso-daily-tracking", roles: WEB_ROLES, group: "main" },
  { id: "final-submit", labelKey: "menu.final_submit", href: "/final-submit", roles: WEB_ROLES, group: "main" },
  { id: "sync-health", labelKey: "menu.sync_health", href: "/sync-health", roles: WEB_ROLES, group: "main" },
  { id: "exceptions", labelKey: "menu.exceptions", href: "/exceptions", roles: WEB_ROLES, group: "main" },
  { id: "leave", labelKey: "menu.leave", href: "/leave", roles: WEB_ROLES, group: "main" },
  { id: "routes", labelKey: "menu.routes", href: "/routes", roles: WEB_ROLES, group: "main" },
  { id: "tutorial", labelKey: "menu.tutorial", href: "/tutorial", roles: WEB_ROLES, group: "main" },
  { id: "credentials", labelKey: "menu.credentials", href: "/credentials", roles: WEB_ROLES, group: "main" },
  ...(["category", "segment", "brand", "variant"] as const).map((l): MenuItem => ({ id: `products-${l}`, labelKey: `menu.products.${l}`, href: `/products/${l}`, roles: WEB_ROLES, group: "products" })),
  { id: "products-tree", labelKey: "menu.products.tree", href: "/products/tree", roles: WEB_ROLES, group: "products" },
  ...WEB_REPORTS.map((r): MenuItem => ({ id: `report-${r.slug}`, labelKey: r.titleKey, href: `/reports/${r.slug}`, roles: r.roles ?? WEB_ROLES, group: "reports" })),
];

export const MENU: readonly MenuItem[] = [...DASHBOARD_MENU, ...ADMIN_MENU];

export interface MenuSection {
  group: MenuGroup;
  labelKey: MessageKey;
  items: MenuItem[];
}

const GROUP_LABEL: Record<MenuGroup, MessageKey> = { main: "menu.group.main", reports: "menu.group.reports", products: "menu.group.products", admin: "menu.group.admin" };

export function menuFor(role: Role, menu: readonly MenuItem[] = MENU): MenuSection[] {
  const sections: MenuSection[] = [];
  for (const group of ["main", "reports", "products", "admin"] as const) {
    const items = menu.filter((m) => m.group === group && m.roles.includes(role));
    if (items.length) sections.push({ group, labelKey: GROUP_LABEL[group], items });
  }
  return sections;
}
