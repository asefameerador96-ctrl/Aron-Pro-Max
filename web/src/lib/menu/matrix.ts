// The role x menu x action matrix (cfg.web.menu_by_role) as the menu filter: an admin changes a role's menus in the portal and the
// user's menu follows on the next page load, without a deployment. Every role reads its own resolved row from GET /v1/me (`menus`,
// contract v1.2); the admin permissions endpoint stays for the editor only.
import { rawRequest } from "@/lib/api/raw";
import type { Role } from "@/contract/types";
import type { MenuPermission } from "@/lib/admin/types";
import { grantsOf, type ModuleGrants } from "@/lib/admin/modules";

/** Menu ids with at least `view` granted; null when the API sent no menus (no filter: the static role lists decide). */
export function allowedFromMenus(menus: readonly MenuPermission[] | undefined | null): Set<string> | null {
  if (!menus || menus.length === 0) return null;
  return new Set(menus.filter((m) => m.actions.includes("view")).map((m) => m.menu_id));
}

export async function loadAllowedMenus(token: string, _role: Role): Promise<Set<string> | null> {
  const r = await rawRequest<{ menus?: MenuPermission[] }>({ method: "GET", path: "/v1/me", token });
  return r.ok ? allowedFromMenus(r.data.menus) : null;
}

/** The role x menu matrix and an ADMIN's module claim from ONE /v1/me call (the shell needs both). */
export async function loadMenuAccess(token: string, role: Role): Promise<{ allowed: Set<string> | null; grants: ModuleGrants | null }> {
  const r = await rawRequest<{ menus?: MenuPermission[]; modules?: Record<string, "r" | "w"> | null; support_desk?: boolean }>({ method: "GET", path: "/v1/me", token });
  // tso-manual W1: the "Tickets" entry shows only with Me.support_desk (D-TSO-MAN-22; absent means false, contract 1.10.0)
  return r.ok ? { allowed: allowedFromMenus(r.data.menus), grants: grantsOf(role, r.data.modules, r.data.support_desk === true) } : { allowed: null, grants: grantsOf(role, null, false) };
}
