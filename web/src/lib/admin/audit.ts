// Audit viewer model shared by the Audit page and Config page P16, so both show the same rows for the same filter.
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import type { AuditEntry, AuditPage } from "./types";

export interface AuditFilter {
  entity?: string;
  entity_id?: string;
  actor_user_id?: string;
  action?: string;
  from?: string;
  to?: string;
}
export const AUDIT_FILTER_KEYS = ["entity", "entity_id", "actor_user_id", "action", "from", "to"] as const;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Only values the contract accepts reach the API (a bad one is dropped, not forwarded). */
export function cleanAuditFilter(raw: Record<string, string | undefined>): AuditFilter {
  const f: AuditFilter = {};
  if (raw.entity && /^[a-z_]{2,40}$/.test(raw.entity)) f.entity = raw.entity;
  if (raw.entity_id && raw.entity_id.length <= 64) f.entity_id = raw.entity_id;
  if (raw.actor_user_id && /^[1-9]\d{0,14}$/.test(raw.actor_user_id)) f.actor_user_id = raw.actor_user_id;
  if (raw.action && /^[a-z_.]{2,60}$/.test(raw.action)) f.action = raw.action;
  if (raw.from && DATE.test(raw.from)) f.from = raw.from;
  if (raw.to && DATE.test(raw.to)) f.to = raw.to;
  return f;
}

export function fetchAudit(token: string, f: AuditFilter, cursor?: string, limit = 50): Promise<ApiOutcome<AuditPage>> {
  return rawRequest<AuditPage>({ method: "GET", path: "/v1/admin/audit", token, query: { ...f, cursor, limit } });
}

export const AUDIT_EXPORT_MAX_ROWS = 10_000;

/** All rows of a filter up to the export cap (pages of 500). `truncated` says the cap cut the list. */
export async function fetchAuditForExport(token: string, f: AuditFilter): Promise<ApiOutcome<{ rows: AuditEntry[]; truncated: boolean }>> {
  const rows: AuditEntry[] = [];
  let cursor: string | undefined;
  for (let page = 0; page <= AUDIT_EXPORT_MAX_ROWS / 500; page++) {
    const r = await fetchAudit(token, f, cursor, 500);
    if (!r.ok) return r;
    rows.push(...r.data.items);
    if (!r.data.next_cursor || r.data.items.length === 0) return { ok: true, status: 200, data: { rows, truncated: false }, response: r.response };
    if (rows.length >= AUDIT_EXPORT_MAX_ROWS) return { ok: true, status: 200, data: { rows: rows.slice(0, AUDIT_EXPORT_MAX_ROWS), truncated: true }, response: r.response };
    cursor = r.data.next_cursor;
  }
  return { ok: true, status: 200, data: { rows: rows.slice(0, AUDIT_EXPORT_MAX_ROWS), truncated: true }, response: new Response(null) };
}

export const AUDIT_CSV_HEADER = ["id", "at", "actor_user_id", "actor_username", "actor_role", "via", "entity", "entity_id", "action", "reason", "before", "after", "request_id", "row_hash"] as const;
export function auditCsvRow(e: AuditEntry): unknown[] {
  return [e.id, e.at, e.actor_user_id, e.actor_username, e.actor_role, e.via, e.entity, e.entity_id, e.action, e.reason, e.before, e.after, e.request_id, e.row_hash];
}

/** `key: old → new` lines for the members that differ (a missing side shows as an empty value). */
export function diffLines(before: AuditEntry["before"], after: AuditEntry["after"]): { key: string; from: string; to: string }[] {
  const b = before ?? {};
  const a = after ?? {};
  const show = (v: unknown) => (v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v));
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])].sort();
  return keys.filter((k) => show(b[k]) !== show(a[k])).map((k) => ({ key: k, from: show(b[k]), to: show(a[k]) }));
}
