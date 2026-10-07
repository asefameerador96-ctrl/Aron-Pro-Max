import type { Role } from "@/contract/types";
import { OPS, TEAM_OPS, type OpKey, type TeamOpKey } from "./ops";

/** May this role call the operation from the portal? (The API repeats the check.) */
export function canOp(op: OpKey, role: Role): boolean {
  return (OPS[op].roles as readonly Role[]).includes(role);
}
export function canTeamOp(op: TeamOpKey, role: Role): boolean {
  return (TEAM_OPS[op].roles as readonly Role[]).includes(role);
}
