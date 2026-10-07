// The role x menu x action matrix (cfg.web.menu_by_role) as the menu filter: an admin changes a role's menus in the portal and the
// menu follows on the next page load, without a deployment. The matrix endpoint is readable by ADMIN and SUPERADMIN only; for
// other roles the static role lists decide until the API serves their own menus (docs/requests/web-config-menu-matrix.md).
import { rawRequest } from "@/lib/api/raw";
import type { Role } from "@/contract/types";
import type { PermissionMatrix } from "@/lib/admin/types";

export const MATRIX_READER_ROLES: readonly Role[] = ["ADMIN", "SUPERADMIN"];

/** Menu ids with at least `view` granted to `role`, from a matrix; null when the role has no row (no filter). */
export function allowedMenuIds(matrix: PermissionMatrix, role: Role): Set<string> | null {
  const row = matrix.roles.find((r) => r.role === role);
  if (!row || row.menus.length === 0) return null;
  return new Set(row.menus.filter((m) => m.actions.includes("view")).map((m) => m.menu_id));
}

export async function loadAllowedMenus(token: string, role: Role): Promise<Set<string> | null> {
  if (!MATRIX_READER_ROLES.includes(role)) return null;
  const r = await rawRequest<PermissionMatrix>({ method: "GET", path: "/v1/admin/permissions", token });
  return r.ok ? allowedMenuIds(r.data, role) : null;
}
