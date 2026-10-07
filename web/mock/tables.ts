// Table-driven mock of the contract's master-data CRUD (list with filters and cursor, GET by id, create, PATCH with
// If-Match, audit row with the change_reason). A new table is one TableDef; no handler code.
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Problem, ProblemCode } from "../src/contract/types";

export type Row = Record<string, unknown> & { id: number; version: number; created_at: string; updated_at: string };

export interface Ctx {
  send: (res: ServerResponse, status: number, body: unknown, headers?: Record<string, string | string[]>) => void;
  problem: (status: number, code: ProblemCode, extra?: Partial<Problem>) => Problem;
  readJson: (req: IncomingMessage) => Promise<unknown>;
  audit: (entity: string, id: number, action: string, before: Record<string, string | number | boolean | null>, after: Record<string, string | number | boolean | null>, reason: string | null) => void;
}

export interface TableDef {
  /** Path regex: group 1.. are path params; the optional last group is the id. */
  collection: RegExp;
  item?: RegExp;
  /** Which rows belong to this def (geo levels share one array). */
  select?: (row: Row, pathParams: string[]) => boolean;
  /** Members stamped on a created row from the path (e.g. level). */
  stamp?: (pathParams: string[]) => Record<string, unknown>;
  rows: () => Row[];
  create: { allowed: string[]; required: string[]; reason: boolean };
  patch: { allowed: string[] };
  unique?: string[][];
  maxLength?: Record<string, number>;
  patterns?: Record<string, RegExp>;
  auditEntity: string;
  filters: Record<string, (row: Row, value: string) => boolean>;
  defaults?: Record<string, unknown>;
  /** Nullable members set to null when absent on create. */
  nullables?: string[];
  nextId: () => number;
}

function fieldErrors(def: TableDef, b: Record<string, unknown>, allowed: string[], required: string[]): NonNullable<Problem["errors"]> {
  const errors: NonNullable<Problem["errors"]> = [];
  for (const k of Object.keys(b)) if (!allowed.includes(k)) errors.push({ pointer: `/${k}`, code: "unknown_member" });
  for (const k of required) if (b[k] === undefined || b[k] === null || b[k] === "") errors.push({ pointer: `/${k}`, code: "required" });
  for (const [k, max] of Object.entries(def.maxLength ?? {})) if (typeof b[k] === "string" && (b[k] as string).length > max) errors.push({ pointer: `/${k}`, code: "too_long" });
  for (const [k, re] of Object.entries(def.patterns ?? {})) if (typeof b[k] === "string" && !re.test(b[k] as string)) errors.push({ pointer: `/${k}`, code: "pattern" });
  const r = b.change_reason;
  if (r !== undefined && r !== null && (typeof r !== "string" || Array.from(r).length < 10 || Array.from(r).length > 500)) errors.push({ pointer: "/change_reason", code: "too_short" });
  return errors;
}

const scalar = (row: Row, keys: string[]): Record<string, string | number | boolean | null> => Object.fromEntries(keys.map((k) => [k, (row[k] ?? null) as string | number | boolean | null]));

/** Returns true when the request was handled. */
export async function handleTable(defs: TableDef[], ctx: Ctx, method: string, url: URL, req: IncomingMessage, res: ServerResponse, write: boolean): Promise<boolean> {
  for (const def of defs) {
    const c = def.collection.exec(url.pathname);
    const it = def.item?.exec(url.pathname);
    if (!c && !it) continue;
    const pathParams = (c ?? it)!.slice(1);
    const inScope = (r: Row) => !def.select || def.select(r, pathParams);

    if (c && method === "GET") {
      const q = url.searchParams;
      const limit = Math.min(500, Number(q.get("limit") ?? 100));
      const offset = q.get("cursor") ? Number(Buffer.from(q.get("cursor")!, "base64url").toString()) || 0 : 0;
      let rows = def.rows().filter(inScope);
      for (const [param, fn] of Object.entries(def.filters)) if (q.get(param)) rows = rows.filter((r) => fn(r, q.get(param)!));
      rows = [...rows].sort((a, b) => a.id - b.id);
      const page = rows.slice(offset, offset + limit);
      ctx.send(res, 200, { items: page, next_cursor: offset + limit < rows.length ? Buffer.from(String(offset + limit)).toString("base64url") : null });
      return true;
    }
    if (it && method === "GET") {
      const row = def.rows().find((r) => r.id === Number(it[it.length - 1]) && inScope(r));
      ctx.send(res, row ? 200 : 404, row ?? ctx.problem(404, "ERR_NOT_FOUND"), row ? { ETag: `"${row.version}"` } : {});
      return true;
    }
    if (!write) return false;

    if (c && method === "POST") {
      const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
      if (!b || typeof b !== "object") return ctx.send(res, 400, ctx.problem(400, "ERR_MALFORMED_JSON")), true;
      const allowed = [...def.create.allowed, ...(def.create.reason ? ["change_reason"] : [])];
      const errors = fieldErrors(def, b, allowed, def.create.required);
      if (errors.length) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors })), true;
      const existing = def.rows().filter(inScope);
      for (const keys of def.unique ?? []) {
        if (existing.some((r) => keys.every((k) => r[k] === b[k]))) return ctx.send(res, 409, ctx.problem(409, "ERR_MASTER_DUPLICATE_CODE")), true;
      }
      const now = new Date().toISOString();
      const row: Row = { ...(def.defaults ?? {}), ...Object.fromEntries((def.nullables ?? []).map((k) => [k, null])), ...def.stamp?.(pathParams), ...Object.fromEntries(Object.entries(b).filter(([k]) => k !== "change_reason")), id: def.nextId(), version: 1, created_at: now, updated_at: now };
      def.rows().push(row);
      ctx.audit(def.auditEntity, row.id, `${def.auditEntity}.create`, {}, scalar(row, Object.keys(b).filter((k) => k !== "change_reason")), def.create.reason ? ((b.change_reason as string | null | undefined) ?? null) : null);
      return ctx.send(res, 201, row, { ETag: `"1"` }), true;
    }
    if (it && method === "PATCH") {
      const row = def.rows().find((r) => r.id === Number(it[it.length - 1]) && inScope(r));
      if (!row) return ctx.send(res, 404, ctx.problem(404, "ERR_NOT_FOUND")), true;
      if (req.headers["if-match"] !== `"${row.version}"`) return ctx.send(res, 412, ctx.problem(412, "ERR_PRECONDITION_FAILED")), true;
      const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
      if (!b || typeof b !== "object" || Object.keys(b).length === 0) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      const errors = fieldErrors(def, b, [...def.patch.allowed, "change_reason"], []);
      if (errors.length) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors })), true;
      const keys = Object.keys(b).filter((k) => k !== "change_reason");
      const before = scalar(row, keys);
      for (const k of keys) row[k] = b[k];
      row.version++;
      row.updated_at = new Date().toISOString();
      ctx.audit(def.auditEntity, row.id, `${def.auditEntity}.update`, before, scalar(row, keys), (b.change_reason as string | null | undefined) ?? null);
      return ctx.send(res, 200, row, { ETag: `"${row.version}"` }), true;
    }
  }
  return false;
}
