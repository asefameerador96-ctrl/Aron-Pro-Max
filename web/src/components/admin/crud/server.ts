// Generic read and write operations of the CRUD engine. Server only: tokens never leave the BFF.
import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import type { AuditPage, Problem } from "@/contract/types";
import { apiClient, outcome, type ApiOutcome } from "@/lib/api/client";
import { authenticate, problemResponse } from "@/lib/api/guard";
import { rawRequest } from "@/lib/api/raw";
import { entityCanEdit, isWritable, canWrite, resolvePath, validId, type AnyEntity, type RefMeta } from "./meta";
import type { WriteRequest } from "./types";
import { actionSchema, reasonSchemaFor, toFieldErrors, valuesSchema } from "./validation";

export const PAGE_SIZE = 25;

export interface RowPage {
  items: Record<string, unknown>[];
  next_cursor: string | null;
}

export function fillItemPath(meta: AnyEntity, id: string, kind: "item" | "get" = "item"): string {
  const template = (kind === "get" ? meta.api.get : meta.api.item) ?? meta.api.item ?? "";
  return resolvePath(String(template), { ...meta.params, id, [meta.idField]: id });
}

export function collectionPath(meta: AnyEntity): string {
  return resolvePath(String(meta.api.collection), meta.params);
}

export interface RefOption {
  value: string;
  label: string;
}

const REF_PAGE = 500;
const REF_MAX_PAGES = 20; // 10,000 rows: more than any reference table of the fleet

/** Options of a `ref` field or filter: every row of the referenced table (follows the cursor), `failed` when the list call failed. */
export async function loadRefOptionsChecked(ref: RefMeta, token: string): Promise<{ options: RefOption[]; failed: boolean }> {
  const valueKey = ref.value ?? "id";
  const labelKeys = ref.label ?? ["name"];
  const options: RefOption[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < REF_MAX_PAGES; page++) {
    const r = await rawRequest<RowPage>({ method: "GET", path: resolvePath(String(ref.path), ref.params), token, query: { limit: REF_PAGE, cursor } });
    if (!r.ok) return { options, failed: true };
    for (const row of r.data.items) options.push({ value: String(row[valueKey]), label: labelKeys.map((k) => String(row[k] ?? "")).filter(Boolean).join(" · ") });
    if (!r.data.next_cursor) break;
    cursor = r.data.next_cursor;
  }
  return { options, failed: false };
}

export async function loadRefOptions(ref: RefMeta, token: string): Promise<RefOption[]> {
  return (await loadRefOptionsChecked(ref, token)).options;
}

/** Keep only filter values the contract accepts, so a typo in the URL shows an unfiltered list, not a raw 400 (Search minLength 2, Id integer). */
export function sanitizeFilters(meta: AnyEntity, raw: Record<string, string | undefined>): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {};
  for (const f of meta.filters) {
    const v = raw[f.param]?.trim();
    if (!v) continue;
    if (f.kind === "search" && Array.from(v).length >= 2) out[f.param] = v;
    else if ((f.kind === "int" || f.kind === "ref") && /^[0-9]{1,15}$/.test(v)) out[f.param] = v;
    else if (f.kind === "date" && /^\d{4}-\d{2}-\d{2}$/.test(v)) out[f.param] = v;
    else if (f.kind === "enum" && f.options?.includes(v)) out[f.param] = v;
  }
  return out;
}

export function listRows(meta: AnyEntity, token: string, query: Record<string, string | undefined>, cursor?: string, limit = PAGE_SIZE): Promise<ApiOutcome<RowPage>> {
  const q: Record<string, string | number | undefined> = { limit, cursor };
  const clean = sanitizeFilters(meta, query);
  for (const f of meta.filters) q[f.param] = clean[f.param];
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

/** docs/24 s8.5: a value only some roles may write (an ADMIN cannot create or promote a SUPERADMIN). */
function violatesRestriction(meta: AnyEntity, role: string, values: Record<string, unknown>): boolean {
  return (meta.restrictedValues ?? []).some((r) => r.field in values && r.values.includes(String(values[r.field])) && !(r.unlessRoles as readonly string[]).includes(role));
}

function validationProblem(errors: ReturnType<typeof toFieldErrors>): NextResponse {
  return problemResponse(400, "ERR_VALIDATION", { errors });
}

/** POST /api/bff/admin/<entity>: create. A reason is mandatory on every write. */
export async function handleCreate(req: NextRequest, meta: AnyEntity): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!canWrite(meta, auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  if (meta.canCreate === false) return problemResponse(404, "ERR_NOT_FOUND");
  const body = await readWrite(req);
  if (!body) return problemResponse(400, "ERR_MALFORMED_JSON");

  const reason = reasonSchemaFor(meta.reasonMax).safeParse(body.reason);
  const values = valuesSchema(meta, "create").safeParse(body.values);
  const errors = [...(reason.success ? [] : toFieldErrors(reason.error, "/reason")), ...(values.success ? [] : toFieldErrors(values.error, "/values"))];
  if (errors.length || !values.success || !reason.success) return validationProblem(errors);

  if (violatesRestriction(meta, auth.session.user.role, values.data as Record<string, unknown>)) return problemResponse(403, "ERR_FORBIDDEN");
  const payload: Record<string, unknown> = { ...values.data };
  // REQUEST: docs/requests/web-admin-create-reason.md. Until the contract accepts a reason on create, it is required here
  // but cannot be forwarded (the write schema is closed: sending it would be a 400).
  if (meta.reasonOnCreate) payload[meta.reasonOnCreate] = reason.data;

  const r = await rawRequest<Record<string, unknown>>({ method: "POST", path: collectionPath(meta), token: auth.session.at, body: payload });
  if (!r.ok) return auth.finish(passThrough(r));
  const cr = meta.createResult;
  const row = cr ? (r.data[cr.rowKey] as Record<string, unknown>) : r.data;
  const shown = cr ? Object.fromEntries(cr.show.map((k) => [k, r.data[k] ?? null])) : undefined;
  return auth.finish(NextResponse.json({ row, shown }, { status: 201 }));
}

/** PATCH /api/bff/admin/<entity>/<id>: update with If-Match. */
export async function handleUpdate(req: NextRequest, meta: AnyEntity, id: string): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  if (!canWrite(meta, auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  if (!entityCanEdit(meta)) return problemResponse(404, "ERR_NOT_FOUND");
  if (!/^[0-9]{1,15}$/.test(id)) return problemResponse(404, "ERR_NOT_FOUND");
  const body = await readWrite(req);
  if (!body) return problemResponse(400, "ERR_MALFORMED_JSON");
  // If-Match must match ^"[0-9]{1,10}"$ (contract): an integer of at most 10 digits, at least 1.
  if (typeof body.version !== "number" || !Number.isInteger(body.version) || body.version < 1 || body.version > 9_999_999_999) {
    return validationProblem([{ pointer: "/version", code: body.version === undefined ? "required" : "invalid" }]);
  }

  const reason = reasonSchemaFor(meta.reasonMax).safeParse(body.reason);
  const values = valuesSchema(meta, "update").safeParse(body.values);
  const errors = [...(reason.success ? [] : toFieldErrors(reason.error, "/reason")), ...(values.success ? [] : toFieldErrors(values.error, "/values"))];
  if (errors.length || !values.success || !reason.success) return validationProblem(errors);
  if (Object.keys(values.data).length === 0) return validationProblem([{ pointer: "/values", code: "too_short" }]);

  if (violatesRestriction(meta, auth.session.user.role, values.data as Record<string, unknown>)) return problemResponse(403, "ERR_FORBIDDEN");
  const writable = new Set(meta.fields.filter((f) => isWritable(f, "update")).map((f) => f.name));
  const payload: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values.data)) if (writable.has(k)) payload[k] = v;
  if (meta.reasonOnUpdate) payload[meta.reasonOnUpdate] = reason.data;

  const r = await rawRequest<Record<string, unknown>>({ method: "PATCH", path: fillItemPath(meta, id), token: auth.session.at, body: payload, ifMatch: `"${body.version}"` });
  if (!r.ok) return auth.finish(passThrough(r));
  return auth.finish(NextResponse.json({ row: r.data }));
}

/** POST /api/bff/admin/<entity>/<id>/<action>: a row action with its own small body and the mandatory reason. */
export async function handleAction(req: NextRequest, meta: AnyEntity, id: string, actionKey: string): Promise<NextResponse> {
  const auth = await authenticate(req, req.nextUrl.pathname);
  if (auth instanceof NextResponse) return auth;
  const action = meta.actions?.find((a) => a.key === actionKey);
  if (!action) return problemResponse(404, "ERR_NOT_FOUND");
  if (!(action.writeRoles ?? meta.writeRoles).includes(auth.session.user.role)) return problemResponse(403, "ERR_FORBIDDEN");
  if (!validId(meta, id)) return problemResponse(404, "ERR_NOT_FOUND");
  const body = await readWrite(req);
  if (!body) return problemResponse(400, "ERR_MALFORMED_JSON");
  if (action.ifMatch && (typeof body.version !== "number" || !Number.isInteger(body.version) || body.version < 1 || body.version > 9_999_999_999)) {
    return validationProblem([{ pointer: "/version", code: body.version === undefined ? "required" : "invalid" }]);
  }

  const reason = reasonSchemaFor(action.reasonMax ?? meta.reasonMax).safeParse(body.reason);
  const values = actionSchema(action.fields as never).safeParse(body.values);
  const errors = [...(reason.success ? [] : toFieldErrors(reason.error, "/reason")), ...(values.success ? [] : toFieldErrors(values.error, "/values"))];
  if (errors.length || !values.success || !reason.success) return validationProblem(errors);

  const payload: Record<string, unknown> = { ...action.fixed, ...values.data };
  if (action.reasonMember) payload[action.reasonMember] = reason.data;
  const r = await rawRequest<Record<string, unknown>>({ method: action.method ?? "POST", path: resolvePath(String(action.path), { ...meta.params, id, [meta.idField]: id }), token: auth.session.at, body: payload, ifMatch: action.ifMatch ? `"${body.version}"` : undefined });
  if (!r.ok) return auth.finish(passThrough(r));
  const shown = action.resultFields ? Object.fromEntries(action.resultFields.map((k) => [k, (r.data ?? {})[k] ?? null])) : undefined;
  return auth.finish(NextResponse.json({ row: r.data ?? null, shown }, { status: r.status === 204 ? 200 : r.status }));
}
