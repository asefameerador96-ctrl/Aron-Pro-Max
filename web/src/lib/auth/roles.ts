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

export type Access = "ok" | "unauthenticated" | "forbidden";

/** Pure policy used by the proxy, server layouts and route handlers, so all three agree. */
export function accessFor(role: Role | undefined, pathname: string): Access {
  const group = routeGroup(pathname);
  if (group === "public") return "ok";
  if (role === undefined) return "unauthenticated";
  if (group === "admin") return hasRole(role, ADMIN_PORTAL_ROLES) ? "ok" : "forbidden";
  return hasRole(role, WEB_ROLES) ? "ok" : "forbidden";
}
