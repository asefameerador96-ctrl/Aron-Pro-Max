// Whitelist of the master-data write operations behind the custom admin pages (sales plan, user scope, prices, SR transfer).
// Same idea as ops.ts (web-config): the browser names an operation by key; the server owns method, path, roles and the place of
// the mandatory reason. Path parameters and body members never come from the browser unchecked.
import type { RoleList } from "@/lib/auth/roles";
import type { OpMethod } from "./ops";

export interface MasterOpDef {
  method: OpMethod;
  path: string;
  roles: RoleList;
  /** Body member carrying the mandatory reason. */
  reason: string;
  /** Contract maximum of that member (ChangeReason 500; assignment `reason` 300). */
  reasonMax?: number;
}

const ADMINS = ["ADMIN", "SUPERADMIN"] as const;

export const MASTER_OPS = {
  "sales-plan.put": { method: "PUT", path: "/v1/admin/sales-plans/{zone_id}", roles: ADMINS, reason: "change_reason" },
  "user-scope.put": { method: "PUT", path: "/v1/admin/users/{id}/scope", roles: ADMINS, reason: "change_reason" },
  "price.preview": { method: "POST", path: "/v1/admin/prices/preview", roles: ADMINS, reason: "change_reason" },
  "price.publish": { method: "POST", path: "/v1/admin/prices", roles: ADMINS, reason: "change_reason" },
  "assignment.create": { method: "POST", path: "/v1/admin/route-assignments", roles: ADMINS, reason: "reason", reasonMax: 300 },
  // A TSO resets a password or unlocks an SR or AMO of its own zones (docs/24 s8.5); the server enforces the reach.
  "credential.manage": { method: "POST", path: "/v1/admin/users/{id}/credentials", roles: ["TSO", "SUPPORT", "ADMIN", "SUPERADMIN"], reason: "reason" },
  "assignment.end": { method: "POST", path: "/v1/admin/route-assignments/{id}/end", roles: ADMINS, reason: "reason", reasonMax: 300 },
} as const satisfies Record<string, MasterOpDef>;

export type MasterOpKey = keyof typeof MASTER_OPS;

export function isMasterOp(k: unknown): k is MasterOpKey {
  return typeof k === "string" && Object.prototype.hasOwnProperty.call(MASTER_OPS, k);
}
