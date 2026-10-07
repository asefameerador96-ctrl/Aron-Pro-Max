// Generic read and write operations of the CRUD engine. Server only: tokens never leave the BFF.
import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import type { AuditPage, Problem } from "@/contract/types";
import { apiClient, outcome, type ApiOutcome } from "@/lib/api/client";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";
import { isWritable, canWrite, resolvePath, type AnyEntity, type RefMeta } from "./meta";
import type { WriteRequest } from "./types";
import { reasonSchema, toFieldErrors, valuesSchema } from "./validation";

export const PAGE_SIZE = 25;

export interface RowPage {
  items: Record<string, unknown>[];
  next_cursor: string | null;
}

export function fillItemPath(meta: AnyEntity, id: string, kind: "item" | "get" = "item"): string {
  const template = (kind === "get" ? meta.api.get : meta.api.item) ?? meta.api.item;
  return resolvePath(String(template), { ...meta.params, id });
}

export function collectionPath(meta: AnyEntity): string {
  return resolvePath(String(meta.api.collection), meta.params);
}

export interface RefOption {
  value: string;
  label: string;
}

/** Options of a `ref` field or filter: the referenced table's rows (first 500; larger tables need a search select). */
export async function loadRefOptions(ref: RefMeta, token: string): Promise<RefOption[]> {
  const r = await rawRequest<RowPage>({ method: "GET", path: resolvePath(String(ref.path), ref.params), token, query: { limit: 500 } });
  if (!r.ok) return [];
  const valueKey = ref.value ?? "id";
  const labelKeys = ref.label ?? ["name"];
  return r.data.items.map((row) => ({ value: String(row[valueKey]), label: labelKeys.map((k) => String(row[k] ?? "")).filter(Boolean).join(" · ") }));
}

export function listRows(meta: AnyEntity, token: string, query: Record<string, string | undefined>, cursor?: string, limit = PAGE_SIZE): Promise<ApiOutcome<RowPage>> {
  const q: Record<string, string | number | undefined> = { limit, cursor };
  for (const f of meta.filters) q[f.param] = query[f.param];
  return rawRequest<RowPage>({ method: "GET", path: collectionPath(meta), token, query: q });
}

/** One row. Uses the contract's GET when it has one; otherwise the list page it came from (hint), then a bounded scan. */
export async function getRow(meta: AnyEntity, token: string, id: string, hintCursor?: string): Promise<ApiOutcome<Record<string, unknown> | null>> {
  if (meta.api.get) {
    return rawRequest<Record<string, unknown>>({ method: "GET", path: fillItemPath(meta, id, "get"), token });
  }
  const isRow = (row: Record<string, unknown>) => String(row[meta.idField]) === id;
  const scan = async (start: string | undefined, maxPages: number): Promise<ApiOutcome<Record<string, unknown> | null>> => {
    let cursor = start;
    for (let page = 0; page < maxPages; page++) {
      const r = await listRows(meta, token, {}, cursor, 500);
      if (!r.ok) return r;
      const hit = r.data.items.find(isRow);
      if (hit) return { ok: true, status: 200, data: hit, response: r.response };
      if (!r.data.next_cursor) break;
      cursor = r.data.next_cursor;
    }
    return { ok: true, status: 200, data: null, response: new Response(null) };
  };
  if (hintCursor) {
    const hinted = await scan(hintCursor, 1);
    if (!hinted.ok || hinted.data) return hinted;
  }
  return scan(undefined, 20);
}

export async function history(meta: AnyEntity, token: string, id: string): Promise<ApiOutcome<AuditPage>> {
  return outcome(apiClient(token).GET("/v1/admin/audit", { params: { query: { entity: meta.auditEntity, entity_id: id, limit: 20 } } }));
}

function passThrough(r: Extract<ApiOutcome<unknown>, { ok: false }>): NextResponse {
  return NextResponse.json(r.problem as Problem, { status: r.status, headers: { "Content-Type": "application/problem+json" } });
}

async function readWrite(req: NextRequest): Promise<WriteRequest | null> {
  const body = (await req.json().catch(() => null)) as Partial<WriteRequest> | null;
  if (!body || typeof body !== "object" || typeof body.values !== "object" || body.values === null || Array.isArray(body.values)) return null;
  return { values: body.values as Record<string, unknown>, reason: typeof body.reason === "string" ? body.reason : "", version: typeof body.version === "number" ? body.version : undefined };
}

function validationProblem(errors: ReturnType<typeof toFieldErrors>): NextResponse {
  return problemResponse(400, "ERR_VALIDATION", { errors });
}

/** POST /api/bff/admin/<entity>: create. A reason is mandatory on every write. */
export async function handleCreate(req: NextRequest, meta: AnyEntity): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!canWrite(meta, auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  const body = await readWrite(req);
  if (!body) return problemResponse(400, "ERR_MALFORMED_JSON");

  const reason = reasonSchema.safeParse(body.reason);
  const values = valuesSchema(meta, "create").safeParse(body.values);
  const errors = [...(reason.success ? [] : toFieldErrors(reason.error, "/reason")), ...(values.success ? [] : toFieldErrors(values.error, "/values"))];
  if (errors.length || !values.success || !reason.success) return validationProblem(errors);

  const payload: Record<string, unknown> = { ...values.data };
  // REQUEST: docs/requests/web-admin-create-reason.md. Until the contract accepts a reason on create, it is required here
  // but cannot be forwarded (the write schema is closed: sending it would be a 400).
  if (meta.reasonOnCreate) payload[meta.reasonOnCreate] = reason.data;

  const r = await rawRequest<Record<string, unknown>>({ method: "POST", path: collectionPath(meta), token: auth.session.at, body: payload });
  if (!r.ok) return auth.finish(passThrough(r));
  return auth.finish(NextResponse.json({ row: r.data }, { status: 201 }));
}

/** PATCH /api/bff/admin/<entity>/<id>: update with If-Match. */
export async function handleUpdate(req: NextRequest, meta: AnyEntity, id: string): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!canWrite(meta, auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  if (!/^[0-9]{1,15}$/.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const body = await readWrite(req);
  if (!body) return problemResponse(400, "ERR_MALFORMED_JSON");
  // If-Match must match ^"[0-9]{1,10}"$ (contract): an integer of at most 10 digits, at least 1.
  if (typeof body.version !== "number" || !Number.isInteger(body.version) || body.version < 1 || body.version > 9_999_999_999) {
    return validationProblem([{ pointer: "/version", code: body.version === undefined ? "required" : "invalid" }]);
  }

  const reason = reasonSchema.safeParse(body.reason);
  const values = valuesSchema(meta, "update").safeParse(body.values);
  const errors = [...(reason.success ? [] : toFieldErrors(reason.error, "/reason")), ...(values.success ? [] : toFieldErrors(values.error, "/values"))];
  if (errors.length || !values.success || !reason.success) return validationProblem(errors);
  if (Object.keys(values.data).length === 0) return validationProblem([{ pointer: "/values", code: "too_short" }]);

  const writable = new Set(meta.fields.filter((f) => isWritable(f, "update")).map((f) => f.name));
  const payload: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values.data)) if (writable.has(k)) payload[k] = v;
  payload[meta.reasonOnUpdate] = reason.data;

  const r = await rawRequest<Record<string, unknown>>({ method: "PATCH", path: fillItemPath(meta, id), token: auth.session.at, body: payload, ifMatch: `"${body.version}"` });
  if (!r.ok) return auth.finish(passThrough(r));
  return auth.finish(NextResponse.json({ row: r.data }));
}
