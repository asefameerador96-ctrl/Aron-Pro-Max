// Menu entries of the configuration and operations pages (web-config lane). One line per page.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

export const CONFIG_MENU: readonly MenuItem[] = [
  { id: "cfg-home", labelKey: "menu.config.home", href: "/admin/config", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-keys", labelKey: "cfgk.title", href: "/admin/config/keys", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-rules", labelKey: "cfgk.rules.title", href: "/admin/config/rules", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-switches", labelKey: "cfgk.switches.title", href: "/admin/config/switches", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-changes", labelKey: "cfgp5.title", href: "/admin/config/changes", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-history", labelKey: "cfgp6.title", href: "/admin/config/history", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-reach", labelKey: "cfgr.title", href: "/admin/config/reach", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-calendar", labelKey: "menu.config.calendar", href: "/admin/calendar", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-device-otps", labelKey: "menu.config.device_otp", href: "/admin/device-otps", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-audit", labelKey: "cfgp.audit.title", href: "/admin/config/audit", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "tso-device-otp", labelKey: "menu.main.device_otp", href: "/device-otp", roles: ["TSO"], group: "main" },
];
