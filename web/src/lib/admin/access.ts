import type { Role } from "@/contract/types";
import { OPS, type OpKey } from "./ops";

/** May this role call the operation from the portal? (The API repeats the check.) */
export function canOp(op: OpKey, role: Role): boolean {
  return (OPS[op].roles as readonly Role[]).includes(role);
}
