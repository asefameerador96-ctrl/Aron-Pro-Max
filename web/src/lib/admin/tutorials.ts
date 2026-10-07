// Tutorial content (F-ADM-026): the checks shared by the BFF handlers, the form and the tests. The browser uploads the file
// straight to the write-only SAS URL of the API, so the size, type and hash are checked here before any URL is requested.
import { ALL_ROLES } from "@/lib/auth/roles";
import { codePoints, REASON_MAX, REASON_MIN } from "@/components/admin/crud/validation";

export const TUTORIAL_KINDS = ["video", "manual"] as const;
export type TutorialKind = (typeof TUTORIAL_KINDS)[number];
export const MAX_ASSET_BYTES = 104_857_600; // AdminAssetUploadRequest.bytes
export const KIND_PURPOSE = { video: "tutorial_video", manual: "tutorial_manual" } as const;
export const KIND_MIME = { video: "video/mp4", manual: "application/pdf" } as const;
export const TUTORIAL_STATUSES = ["active", "inactive"] as const;

export const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

export interface FieldIssue {
  pointer: string;
  code: string;
}

/** Purposes this lane uploads, with the type and size limit of each (AdminAssetUploadRequest; content items at most 20 MB, images 300 KB). */
export const ASSET_RULES = {
  tutorial_video: { mimes: ["video/mp4"], max: MAX_ASSET_BYTES },
  tutorial_manual: { mimes: ["application/pdf"], max: MAX_ASSET_BYTES },
  content_av: { mimes: ["video/mp4"], max: 20_971_520 },
  content_kv: { mimes: ["image/jpeg", "image/png"], max: 307_200 },
} as const;
export type AssetPurpose = keyof typeof ASSET_RULES;

/** POST /api/bff/admin/assets: the body of AdminAssetUploadRequest for a tutorial or content file. */
export function checkAssetRequest(b: unknown): { issues: FieldIssue[]; body?: { asset_id: string; purpose: string; mime: string; bytes: number; sha256: string } } {
  const issues: FieldIssue[] = [];
  if (!isObj(b)) return { issues: [{ pointer: "", code: "invalid" }] };
  for (const k of Object.keys(b)) if (!["asset_id", "purpose", "mime", "bytes", "sha256"].includes(k)) issues.push({ pointer: `/${k}`, code: "unknown_member" });
  if (typeof b.asset_id !== "string" || !UUID_V4.test(b.asset_id)) issues.push({ pointer: "/asset_id", code: "invalid" });
  const rule = typeof b.purpose === "string" && Object.prototype.hasOwnProperty.call(ASSET_RULES, b.purpose) ? ASSET_RULES[b.purpose as AssetPurpose] : null;
  if (!rule) issues.push({ pointer: "/purpose", code: "invalid" });
  else if (typeof b.mime !== "string" || !(rule.mimes as readonly string[]).includes(b.mime)) issues.push({ pointer: "/mime", code: "invalid" });
  if (typeof b.bytes !== "number" || !Number.isSafeInteger(b.bytes) || b.bytes < 1 || b.bytes > (rule?.max ?? MAX_ASSET_BYTES)) issues.push({ pointer: "/bytes", code: "invalid" });
  if (typeof b.sha256 !== "string" || !SHA256.test(b.sha256)) issues.push({ pointer: "/sha256", code: "invalid" });
  if (issues.length) return { issues };
  return { issues, body: { asset_id: b.asset_id as string, purpose: b.purpose as string, mime: b.mime as string, bytes: b.bytes as number, sha256: b.sha256 as string } };
}

/** POST /api/bff/admin/tutorials and PATCH .../{id}: TutorialWrite, with the reason as `change_reason`. */
export function checkTutorialWrite(b: unknown): { issues: FieldIssue[]; body?: Record<string, unknown> } {
  const issues: FieldIssue[] = [];
  if (!isObj(b)) return { issues: [{ pointer: "", code: "invalid" }] };
  for (const k of Object.keys(b)) if (!["kind", "title_en", "title_bn", "asset_id", "roles", "sort", "status", "change_reason"].includes(k)) issues.push({ pointer: `/${k}`, code: "unknown_member" });
  if (!(TUTORIAL_KINDS as readonly unknown[]).includes(b.kind)) issues.push({ pointer: "/kind", code: "invalid" });
  const en = typeof b.title_en === "string" ? b.title_en.trim() : "";
  if (!en || codePoints(en) > 120) issues.push({ pointer: "/title_en", code: en ? "too_long" : "required" });
  const bn = b.title_bn === undefined || b.title_bn === null ? null : typeof b.title_bn === "string" ? b.title_bn.trim() || null : undefined;
  if (bn === undefined || (bn !== null && codePoints(bn) > 120)) issues.push({ pointer: "/title_bn", code: "invalid" });
  if (typeof b.asset_id !== "string" || !UUID_V4.test(b.asset_id)) issues.push({ pointer: "/asset_id", code: "invalid" });
  const roles = b.roles;
  if (!Array.isArray(roles) || roles.length < 1 || roles.length > ALL_ROLES.length || !roles.every((r) => (ALL_ROLES as readonly unknown[]).includes(r)) || new Set(roles).size !== roles.length) issues.push({ pointer: "/roles", code: "invalid" });
  if (typeof b.sort !== "number" || !Number.isInteger(b.sort) || b.sort < 0 || b.sort > 9999) issues.push({ pointer: "/sort", code: "invalid" });
  if (b.status !== undefined && !(TUTORIAL_STATUSES as readonly unknown[]).includes(b.status)) issues.push({ pointer: "/status", code: "invalid" });
  const reason = typeof b.change_reason === "string" ? b.change_reason.trim() : "";
  const n = codePoints(reason);
  if (n < REASON_MIN) issues.push({ pointer: "/change_reason", code: "too_short" });
  else if (n > REASON_MAX) issues.push({ pointer: "/change_reason", code: "too_long" });
  if (issues.length) return { issues };
  const body: Record<string, unknown> = { kind: b.kind, title_en: en, title_bn: bn, asset_id: b.asset_id, roles: b.roles, sort: b.sort, change_reason: reason };
  if (b.status !== undefined) body.status = b.status;
  return { issues, body };
}
