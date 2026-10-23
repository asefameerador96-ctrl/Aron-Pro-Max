import type { Role } from "@/contract/types";
import { canWriteModule, loadGrants, moduleOfApiPath } from "./modules";
import { OPS, TEAM_OPS, type OpKey, type TeamOpKey } from "./ops";

/** May this role call the operation from the portal? (The API repeats the check.) */
export function canOp(op: OpKey, role: Role): boolean {
  return (OPS[op].roles as readonly Role[]).includes(role);
}
export function canTeamOp(op: TeamOpKey, role: Role): boolean {
  return (TEAM_OPS[op].roles as readonly Role[]).includes(role);
}

/**
 * Role check plus the ADMIN module grant (D-WEB-ADMIN-MENU-01): an ADMIN with only a read grant on the module that owns the operation's
 * API path sees the page without its write controls. Grants are read once per request; the API still answers 403 for a refused write.
 */
export async function loadOpGate(token: string, role: Role): Promise<(op: OpKey) => boolean> {
  const grants = await loadGrants(token, role);
  return (op) => canOp(op, role) && canWriteModule(grants, moduleOfApiPath(OPS[op].path));
}
