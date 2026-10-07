// Menu entries of the configuration and operations pages (web-config lane). One line per page.
import type { MenuItem } from "@/lib/menu/menu";
import { ADMIN_PORTAL_ROLES } from "@/lib/auth/roles";

export const CONFIG_MENU: readonly MenuItem[] = [
  { id: "cfg-calendar", labelKey: "menu.config.calendar", href: "/admin/calendar", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-device-otps", labelKey: "menu.config.device_otp", href: "/admin/device-otps", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "cfg-audit", labelKey: "cfgp.audit.title", href: "/admin/config/audit", roles: ADMIN_PORTAL_ROLES, group: "config" },
  { id: "tso-device-otp", labelKey: "menu.main.device_otp", href: "/device-otp", roles: ["TSO"], group: "main" },
];
