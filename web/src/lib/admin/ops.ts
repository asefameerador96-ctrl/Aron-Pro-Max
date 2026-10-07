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
  /** The operation takes no request body (sent empty). */
  noBody?: boolean;
  /** Body members whose listed values need a stronger role than `roles` (the proxy answers 403). */
  forbid?: { member: string; values: readonly string[] };
}

const ADMINS = ["ADMIN", "SUPERADMIN"] as const;
const SUPERS = ["SUPERADMIN"] as const;
const SUPPORT_UP = ["SUPPORT", "ADMIN", "SUPERADMIN"] as const;

export const OPS = {
  // F-ADM-033 working-day calendar
  "holiday.create": { method: "POST", path: "/v1/admin/calendar/holidays", roles: ADMINS, reason: "reason" },
  // F-ADM-022 / F-ADM-048 device OTP
  "device-otp.issue": { method: "POST", path: "/v1/admin/device-otps", roles: SUPPORT_UP, reason: "reason" },
  // F-ADM-013 and every config page: one change request (the registry decides applied, scheduled or pending approval)
  "config.change": { method: "POST", path: "/v1/admin/config/changes", roles: ADMINS, reason: "reason" },
  // F-ADM-042 P5 approve, reject, cancel, adopt (a note is the contract's reason member here)
  "config.decide": { method: "POST", path: "/v1/admin/config/changes/{change_id}/decision", roles: ADMINS, reason: "note" },
  // F-ADM-043 P6 revert or roll back to a version (always creates a NEW version)
  "config.rollback": { method: "POST", path: "/v1/admin/config/versions/{version}/rollback", roles: ADMINS, reason: "reason" },
  // F-ADM-009 / F-ADM-048 devices: suspend, revoke, reactivate (revoke blocks sync, never local capture)
  "device.state": { method: "POST", path: "/v1/admin/devices/{device_id}/state", roles: SUPPORT_UP, reason: "reason" },
  // remote directives carry no reason member in the contract; they never change data (docs/24 s10)
  "device.directive": { method: "POST", path: "/v1/admin/devices/{device_id}/directives", roles: SUPPORT_UP, reason: null },
  // F-ADM-078 replace-device wizard
  "device.replace": { method: "POST", path: "/v1/admin/devices/{device_id}/replace", roles: SUPPORT_UP, reason: "reason" },
  // F-ADM-027 / P12 releases: register a CI-built APK (draft), change rollout, block or retire; only a SUPERADMIN publishes (docs/24 s8.5)
  "release.create": { method: "POST", path: "/v1/admin/releases", roles: ADMINS, reason: null },
  "release.update": { method: "PATCH", path: "/v1/admin/releases/{release_id}", roles: ADMINS, reason: "change_reason", ifMatch: true, forbid: { member: "status", values: ["published"] } },
  "release.publish": { method: "PATCH", path: "/v1/admin/releases/{release_id}", roles: SUPERS, reason: "change_reason", ifMatch: true },
  // F-ADM-030 / P14 quarantine review: accept, accept with fix (re-map), discard, return to device
  "quarantine.resolve": { method: "POST", path: "/v1/admin/quarantine/{quarantine_id}/resolve", roles: ADMINS, reason: "reason" },
  // N-045 enrolment QR: tokens carry a note, not a reason; the secret is shown once
  "enrolment.create": { method: "POST", path: "/v1/admin/enrolment-tokens", roles: SUPPORT_UP, reason: null },
  "enrolment.revoke": { method: "POST", path: "/v1/admin/enrolment-tokens/{token_id}/revoke", roles: SUPPORT_UP, reason: null, noBody: true },
  // F-ADM-029 / F-ADM-052 reopen a final-submitted zone-day (cfg.day.reopen_roles: admin)
  "day.reopen": { method: "POST", path: "/v1/day/reopen", roles: ADMINS, reason: "reason" },
  // F-ADM-023 / F-ADM-060 code lists (QC fault types, reasons): items replaced with a change reason; codes are never deleted
  "code-list.put": { method: "PUT", path: "/v1/admin/code-lists/{list_key}", roles: ADMINS, reason: "change_reason" },
} as const satisfies Record<string, OpDef>;

/** Web-role operations (the TSO's own pages live outside /admin): served by /api/bff/team-op, gated by the web roles. */
export const TEAM_OPS = {
  // F-WEB-051 web Final Submit (same server rule as the app: online only, once per zone and day)
  "day.final-submit": { method: "POST", path: "/v1/day/final-submit", roles: ["TSO", "DMO", "ADMIN", "SUPERADMIN"], reason: null },
  // F-WEB-051 Delete Section Data: an audited void with a reason, only before Final Submit
  "day.data-void": { method: "POST", path: "/v1/admin/data-void", roles: ["TSO", "ADMIN", "SUPERADMIN"], reason: "reason" },
} as const satisfies Record<string, OpDef>;

export type TeamOpKey = keyof typeof TEAM_OPS;
export type OpKey = keyof typeof OPS;

export const PARAM_PATTERN = /^[A-Za-z0-9_.:-]{1,64}$/;

export function isOpKey(k: unknown): k is OpKey {
  return typeof k === "string" && Object.prototype.hasOwnProperty.call(OPS, k);
}
export function isTeamOpKey(k: unknown): k is TeamOpKey {
  return typeof k === "string" && Object.prototype.hasOwnProperty.call(TEAM_OPS, k);
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
