// Which supervisory nodes a role may be given (F-ADM-008, docs/24 s8.4): AMO a zone, TSO a territory, DMO a division, WM a wing;
// national roles the whole country; an SR has route assignments, never scope nodes.
import type { Role } from "@/contract/types";

export type NodeType = "national" | "wing" | "division" | "territory" | "zone";

// NATIONAL and BRAND (docs/34 s1) both see all geography (BRAND is limited by brand instead).
export const ALLOWED_SCOPE_TYPES: Record<Role, readonly NodeType[]> = {
  SR: [],
  AMO: ["zone"],
  TSO: ["territory"],
  DMO: ["division"],
  WM: ["wing"],
  TOP: ["national"],
  NATIONAL: ["national"],
  BRAND: ["national"],
  ANALYST: ["national"],
  SUPPORT: ["national"],
  ADMIN: ["national"],
  SUPERADMIN: ["national"],
};

export function scopeNodeAllowed(role: Role, type: string): boolean {
  return ((ALLOWED_SCOPE_TYPES[role] ?? []) as readonly string[]).includes(type);
}
