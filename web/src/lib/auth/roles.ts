// Roles and the role-gated route groups (docs/24 s8.5, docs/23 Q6). The role strings are typed from the contract.
import type { Role } from "@/contract/types";

export const ALL_ROLES = ["SR", "AMO", "TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Roles that may sign in to the web at all. SR and AMO work in the field apps (docs/23 Q6). */
export const WEB_ROLES = ["TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Roles that may open the admin portal route group. Everyone else gets 403 on /admin/**. */
export const ADMIN_PORTAL_ROLES = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Admin pages SUPPORT has no access to (docs/24 s8.5: permission matrix, print templates, supervisory targets). */
export const ADMIN_ONLY_ROLES = ["ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Roles that must complete a TOTP step after the password (cfg.auth.mfa_required_roles, docs/24 s6.5, D24-33). */
export const MFA_ROLES = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

export type RoleList = readonly Role[];

/** Roles that read or act on outlet requests on the web (docs/24 s8.5): DMO and WM approve, TSO/TOP/ANALYST read. */
export const OUTLET_REQUEST_READ_ROLES = ["TSO", "DMO", "WM", "TOP", "ANALYST", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];
export const OUTLET_REQUEST_ACT_ROLES = ["DMO", "WM", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Admin-group paths that roles outside the portal may open (longest prefix wins). Everything else under /admin is portal only. */
/** Roles that reset a password or unlock an SR or AMO on the team page (docs/24 s8.5); a TSO only for its own zones (server side). */
export const TEAM_CREDENTIAL_ROLES = ["TSO", "SUPPORT", "ADMIN", "SUPERADMIN"] as const satisfies readonly Role[];

/** Roles that raise an outlet change request from the web (the others edit directly or have no outlet write). */
export const OUTLET_REQUEST_RAISE_ROLES = ["TSO", "DMO"] as const satisfies readonly Role[];

/** The config pages that read /v1/admin/config* (keys, changes, versions, reach, blast radius): ADMIN and SUPERADMIN only on the server (AZ-6 Q2),
 *  so SUPPORT gets 403 at the gate instead of a failing page. The config home is an exact path because /admin/config/sync-health, app-block,
 *  day, flags, audit, ... use other endpoints and stay open to SUPPORT. */
export const ADMIN_CONFIG_READ_PATHS = ["/admin/config", "/admin/config/keys", "/admin/config/rules", "/admin/config/switches", "/admin/config/changes", "/admin/config/history", "/admin/config/reach", "/admin/config/geofence", "/admin/config/app-block", "/admin/config/day", "/admin/config/flags", "/admin/calendar"] as const; // the calendar reads config keys and resolve (weekend days)

export const ADMIN_PATH_ROLES: readonly { prefix: string; roles: RoleList }[] = [
  { prefix: "/admin/audit", roles: ADMIN_ONLY_ROLES }, // the audit-log read route is not open to SUPPORT (backend-authz AZ-6); /admin/config/audit is a different page
  { prefix: "/admin/outlet-requests", roles: OUTLET_REQUEST_READ_ROLES },
  { prefix: "/api/bff/admin/outlet-requests", roles: OUTLET_REQUEST_READ_ROLES },
  { prefix: "/admin/outlet-change", roles: OUTLET_REQUEST_RAISE_ROLES },
  { prefix: "/admin/team", roles: TEAM_CREDENTIAL_ROLES },
  { prefix: "/admin/radius", roles: ["TSO"] },
];

export function hasRole(role: Role | undefined, allowed: RoleList): boolean {
  return role !== undefined && allowed.includes(role);
}

/** Reachable without a session: the login flow (its handlers check their own inputs), logout, language switch. */
const PUBLIC_PATHS = ["/login", "/api/bff/login", "/api/bff/mfa", "/api/bff/logout", "/api/bff/locale", "/api/bff/session"];

export type RouteGroup = "public" | "dashboards" | "admin";

/** The route group of a pathname. Paths not listed are dashboards (the default group needs only a web role). */
export function routeGroup(pathname: string): RouteGroup {
  if (PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return "public";
  if (pathname === "/admin" || pathname.startsWith("/admin/") || pathname.startsWith("/api/bff/admin")) return "admin";
  return "dashboards";
}

/** Dashboard pages SUPPORT keeps (docs/24 s8.5 line 777: national read of users, devices and sync health; credential and device actions;
 *  risk signals in reach, line 1247; tutorials are open to all). Its users, devices, enrolment, device OTP and entry unlock pages live in the
 *  admin portal, which this gate does not touch. The server refuses SUPPORT on every other dashboard and
 *  report endpoint, so the web never offers or serves those pages to it. Access only ever narrows here. */
export const SUPPORT_DASHBOARD_PATHS = ["/sync-health", "/credentials", "/tutorial", "/exceptions", "/device-otp", "/api/bff/password", "/api/bff/locale"] as const;
const SUPPORT_DENIED_API = ["/api/bff/reports"];

export type Access = "ok" | "unauthenticated" | "forbidden";

/** Pure policy used by the proxy, server layouts and route handlers, so all three agree. */
export function accessFor(role: Role | undefined, pathname: string): Access {
  const group = routeGroup(pathname);
  if (group === "public") return "ok";
  if (role === undefined) return "unauthenticated";
  if (group === "admin") {
    if ((ADMIN_CONFIG_READ_PATHS as readonly string[]).some((p) => pathname === p || (p !== "/admin/config" && pathname.startsWith(`${p}/`)))) return hasRole(role, ADMIN_ONLY_ROLES) ? "ok" : "forbidden";
    const override = ADMIN_PATH_ROLES.find((o) => pathname === o.prefix || pathname.startsWith(`${o.prefix}/`));
    return hasRole(role, override ? override.roles : ADMIN_PORTAL_ROLES) ? "ok" : "forbidden";
  }
  if (!hasRole(role, WEB_ROLES)) return "forbidden";
  if (role === "SUPPORT") {
    if (SUPPORT_DENIED_API.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return "forbidden";
    const isPage = !pathname.startsWith("/api/") && !pathname.startsWith("/_next/");
    if (isPage && pathname !== "/" && !SUPPORT_DASHBOARD_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return "forbidden";
  }
  return "ok";
}
