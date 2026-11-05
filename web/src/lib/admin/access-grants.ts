// Portal levels, module grants and brand scope (docs/34 s1, D-ROLES-02). The seven owner levels map onto role codes; SR and AMO
// have no portal login (apps only). The grants wire types come from the contract (getUserAccess / putUserAccess).
import type { Role, Schemas } from "@/contract/types";
import type { MessageKey } from "@/lib/i18n";

export type UserRole = Role;

/** The owner's seven portal levels, in the owner's order. */
export const PORTAL_LEVELS = ["SUPERADMIN", "ADMIN", "NATIONAL", "BRAND", "WM", "DMO", "TSO"] as const satisfies readonly UserRole[];
/** Field-app roles: no portal login, so no grants and no portal scope. */
export const APP_ONLY_ROLES = ["SR", "AMO"] as const satisfies readonly UserRole[];
/** Internal service roles (docs/21) and the retired TOP: still valid values, not portal levels. */
export const SERVICE_ROLES = ["ANALYST", "SUPPORT", "TOP"] as const satisfies readonly UserRole[];
/** Every role a user can hold, portal levels first. */
export const USER_ROLE_OPTIONS = [...PORTAL_LEVELS, ...APP_ONLY_ROLES, ...SERVICE_ROLES] as const;

export const isAppOnly = (role: string): boolean => (APP_ONLY_ROLES as readonly string[]).includes(role);

/** Modules an ADMIN can be granted, read or write (docs/34 s1 row 2; fifteen, lead 2026-10-22; `support` sixteenth, R-F-11). */
export const GRANT_MODULES = ["masterdata", "routes", "outlets", "products", "prices", "users", "targets", "credentials", "mdm", "reports", "config", "data_entry", "indent", "leaderboard", "daily_tracking", "support"] as const; // tso-manual W1: + support (R-F-11)
export type GrantModule = (typeof GRANT_MODULES)[number];
export type GrantLevel = "none" | "r" | "w";

export const MODULE_KEYS: Record<GrantModule, MessageKey> = {
  masterdata: "grant.module.masterdata",
  routes: "grant.module.routes",
  outlets: "grant.module.outlets",
  products: "grant.module.products",
  prices: "grant.module.prices",
  users: "grant.module.users",
  targets: "grant.module.targets",
  credentials: "grant.module.credentials",
  mdm: "grant.module.mdm",
  reports: "grant.module.reports",
  config: "grant.module.config",
  data_entry: "grant.module.data_entry",
  indent: "grant.module.indent",
  leaderboard: "grant.module.leaderboard",
  daily_tracking: "grant.module.daily_tracking",
  support: "grant.module.support", // tso-manual W1 (R-F-11)
};

/** What a role's access page offers: module grants for ADMIN, brand scope for BRAND, nothing for the rest. */
export type AccessKind = "modules" | "brands" | "none";
export function accessKind(role: string): AccessKind {
  return role === "ADMIN" ? "modules" : role === "BRAND" ? "brands" : "none";
}

/** Wire shape of the grants of one user: `version` is the user's scope_version and the ETag. `modules` holds only granted modules. */
export type UserAccess = Schemas["UserAccess"];

export function levelOf(modules: UserAccess["modules"], m: GrantModule): GrantLevel {
  return modules[m] ?? "none";
}

/** Body of the write: only the granted modules, sorted, so equal choices give equal bodies. */
export function modulesBody(levels: Record<GrantModule, GrantLevel>): Partial<Record<GrantModule, "r" | "w">> {
  const out: Partial<Record<GrantModule, "r" | "w">> = {};
  for (const m of GRANT_MODULES) if (levels[m] !== "none") out[m] = levels[m];
  return out;
}

export const MAX_BRANDS = 200;

type Problem = { pointer: string; code: string };

/** Shape rules that need no role: member names, value sets, no duplicate brand, an empty brand scope refused (it would see nothing). */
export function accessShapeRules(body: Record<string, unknown>): Problem[] {
  const out: Problem[] = [];
  for (const k of Object.keys(body)) if (!["modules", "brand_ids", "change_reason"].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
  const m = body.modules;
  if (m !== undefined) {
    if (typeof m !== "object" || m === null || Array.isArray(m)) out.push({ pointer: "/modules", code: "invalid" });
    else
      for (const [k, v] of Object.entries(m)) {
        if (!(GRANT_MODULES as readonly string[]).includes(k)) out.push({ pointer: `/modules/${k}`, code: "unknown_member" });
        else if (v !== "r" && v !== "w") out.push({ pointer: `/modules/${k}`, code: "invalid" });
      }
  }
  const b = body.brand_ids;
  if (b !== undefined) {
    if (!Array.isArray(b) || b.length > MAX_BRANDS || !b.every((x) => typeof x === "number" && Number.isSafeInteger(x) && x >= 1) || new Set(b).size !== b.length) out.push({ pointer: "/brand_ids", code: "invalid" });
    else if (b.length === 0) out.push({ pointer: "/brand_ids", code: "required" });
  }
  if (m === undefined && b === undefined) out.push({ pointer: "/modules", code: "required" });
  if (m !== undefined && b !== undefined) out.push({ pointer: "/brand_ids", code: "role_mismatch" }); // one write carries one kind
  return out;
}

/** The same rules for a known role: modules only for ADMIN, brand_ids only for BRAND. */
export function accessRules(role: string, body: Record<string, unknown>): Problem[] {
  const out = accessShapeRules(body);
  const kind = accessKind(role);
  if (body.modules !== undefined && kind !== "modules") out.push({ pointer: "/modules", code: "role_mismatch" });
  if (body.brand_ids !== undefined && kind !== "brands") out.push({ pointer: "/brand_ids", code: "role_mismatch" });
  return out;
}
