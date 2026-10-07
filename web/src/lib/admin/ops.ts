// Whitelist of the admin write operations the portal may call through the BFF (POST /api/bff/admin-op).
// The browser names an operation by key; the server owns the method, the path, the roles and where the reason goes.
// A path or a body member is never taken from the browser, so the console cannot be turned into an open proxy.
import type { RoleList } from "@/lib/auth/roles";

export type OpMethod = "POST" | "PATCH" | "PUT" | "DELETE";

export interface OpDef {
  method: OpMethod;
  /** Contract path with `{name}` placeholders filled from `params` (each must match PARAM_PATTERN). */
  path: string;
  /** Roles allowed to call it from the portal (the API repeats the check; this is the first gate). */
  roles: RoleList;
  /** Body member that carries the mandatory reason (ChangeReason, 10 to 500 code points); null = the operation has none. */
  reason: string | null;
  /** The operation needs If-Match (the row `version`). */
  ifMatch?: boolean;
}

const ADMINS = ["ADMIN", "SUPERADMIN"] as const;
const SUPPORT_UP = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const;

export const OPS = {
  // F-ADM-033 working-day calendar
  "holiday.create": { method: "POST", path: "/v1/admin/calendar/holidays", roles: ADMINS, reason: "reason" },
  // F-ADM-022 / F-ADM-048 device OTP
  "device-otp.issue": { method: "POST", path: "/v1/admin/device-otps", roles: SUPPORT_UP, reason: "reason" },
  // F-ADM-013 and every config page: one change request (the registry decides applied, scheduled or pending approval)
  "config.change": { method: "POST", path: "/v1/admin/config/changes", roles: ADMINS, reason: "reason" },
} as const satisfies Record<string, OpDef>;

export type OpKey = keyof typeof OPS;

export const PARAM_PATTERN = /^[A-Za-z0-9_.:-]{1,64}$/;

export function isOpKey(k: unknown): k is OpKey {
  return typeof k === "string" && Object.prototype.hasOwnProperty.call(OPS, k);
}

/** Fill `{name}` placeholders; null when a value is missing or has a forbidden character. */
export function fillPath(template: string, params: Record<string, unknown> | undefined): string | null {
  let bad = false;
  const out = template.replace(/\{(\w+)\}/g, (_m, name: string) => {
    const v = params?.[name];
    const s = typeof v === "number" ? String(v) : typeof v === "string" ? v : "";
    if (!PARAM_PATTERN.test(s)) {
      bad = true;
      return "";
    }
    return encodeURIComponent(s);
  });
  return bad ? null : out;
}
