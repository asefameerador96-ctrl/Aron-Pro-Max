// The admin API of the mock (master data, users, code lists, tutorials, definitions, feedback, outlet requests, audit): everything
// under /v1/admin/ that the config and dashboard services do not answer. server.ts only registers this module.
import type { IncomingMessage, ServerResponse } from "node:http";
import { handleTable, type Ctx, type Row } from "./tables";
import { tableDefs } from "./master-seed";
import { maskAudit } from "./audit-mask";
import { handleCustom } from "./custom";
import { handleMdm } from "./mdm";
import type { AuditEntry, Problem, Role } from "../src/contract/types";
import type { MockUser, State } from "./server";

// docs/24 s8.5: master data is readable by DMO, WM and TOP as well (the approval panel reads zones and users).
export const ADMIN_READ: Role[] = ["ADMIN", "SUPERADMIN", "SUPPORT", "ANALYST", "DMO", "WM", "TOP"];
export const ADMIN_WRITE: Role[] = ["ADMIN", "SUPERADMIN"];
export const AUDIT_READERS: Role[] = ["ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"];

export interface AdminHost {
  state: State;
  user: MockUser;
  /** The caller's token record: `piiUntil` is set while a PII reveal window is open. */
  a: { piiUntil: number | null };
  send: (res: ServerResponse, status: number, body: unknown, headers?: Record<string, string | string[]>) => void;
  problem: (status: number, code: Problem["code"], extra?: Partial<Problem>) => Problem;
  readJson: (req: IncomingMessage) => Promise<unknown>;
  audit: (state: State, user: MockUser, entity: string, entity_id: number | string, action: string, before: AuditEntry["before"], after: AuditEntry["after"], reason: string | null) => void;
}

/** True when the request was answered. */
export async function handleAdmin(h: AdminHost, method: string, path: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const { state, user, a, problem, readJson, audit } = h;
  // Inside this module `return send(...)` means "answered".
  const send = (r: ServerResponse, status: number, body: unknown, headers?: Record<string, string | string[]>): true => (h.send(r, status, body, headers), true);
  if (path === "/v1/admin/config/changes" && method === "POST" && user.master && user.summary.role === "TSO") {
    // A TSO proposes; cfg.geo.tso_radius_mode = propose, so the change always waits for an editor (D24-59). Own territory only.
    const b = (await readJson(req)) as { reason?: string; changes?: { key: string; scope_type: string; scope_id: number; value: number }[] } | null;
    const c = b?.changes?.[0];
    if (!b || !c || b.changes!.length !== 1 || c.key !== "cfg.geo.radius_m" || typeof c.value !== "number" || Array.from(b.reason ?? "").length < 10) return send(res, 400, problem(400, "ERR_VALIDATION"));
    if (c.scope_type !== "territory" || c.scope_id !== 6) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    audit(state, user, "config_change", state.nextId, "config.propose", {}, { value: c.value }, b.reason ?? null);
    return send(res, 201, { change_id: state.nextId++, status: "pending_approval", risk_class: c.value > 150 ? 3 : 2, changes: [c], reason: b.reason, requested_by: user.summary.user_id, requested_at: new Date().toISOString(), blast_radius: {} });
  }

  const tsoUsers = /^\/v1\/admin\/users(?:\/(\d+)(\/credentials)?)?$/.exec(path);
  if (tsoUsers && user.master && user.summary.role === "TSO") {
    // A TSO reaches SR and AMO users of its own zones only (docs/24 s8.5); anything else is not found, never "forbidden but exists".
    const reach = (r: Row) => (r.role === "SR" || r.role === "AMO") && r.home_zone_id === 14;
    const rows = state.tables.users!;
    if (method === "GET" && !tsoUsers[1]) {
      const role = url.searchParams.get("role");
      return send(res, 200, { items: rows.filter((r) => reach(r) && (!role || r.role === role)), next_cursor: null });
    }
    const target = tsoUsers[1] ? rows.find((r) => r.id === Number(tsoUsers[1]) && reach(r)) : undefined;
    if (!target) return send(res, 404, problem(404, "ERR_NOT_FOUND"));
    if (method === "GET" && !tsoUsers[2]) return send(res, 200, target);
    if (method === "POST" && tsoUsers[2]) {
      const b = (await readJson(req)) as Record<string, unknown> | null;
      if (!b || (b.action !== "reset_password" && b.action !== "unlock")) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
      const reason = typeof b.reason === "string" ? b.reason : "";
      if (Array.from(reason).length < 10) return send(res, 400, problem(400, "ERR_VALIDATION"));
      audit(state, user, "user", target.id, `user.${b.action}`, {}, {}, reason);
      const pw = b.action === "reset_password";
      return send(res, 200, { action: b.action, done_at: new Date().toISOString(), temporary_password: pw ? `Tmp-${target.id}-Reset!99` : null, temporary_password_expires_at: pw ? new Date(Date.now() + 86_400_000).toISOString() : null });
    }
    return send(res, 403, problem(403, "ERR_FORBIDDEN"));
  }

  if (path.startsWith("/v1/admin/")) {
    const write = method !== "GET";
    if (!(write ? (path.endsWith("/credentials") ? [...ADMIN_WRITE, "SUPPORT" as Role] : ADMIN_WRITE) : ADMIN_READ).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
  }

  if (path === "/v1/admin/code-lists" && method === "GET") return send(res, 200, { lists: Object.entries(state.codeLists).map(([list_key, items]) => ({ list_key, items })) });
  const cl = /^\/v1\/admin\/code-lists\/([a-z_]+)$/.exec(path);
  if (cl && method === "PUT") {
    if (!ADMIN_WRITE.includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const key = cl[1]!;
    const b = (await readJson(req)) as { items?: Record<string, unknown>[]; change_reason?: string } | undefined;
    const errors: NonNullable<Problem["errors"]> = [];
    if (!b || !Array.isArray(b.items) || b.items.length < 1) errors.push({ pointer: "/items", code: "required" });
    if (typeof b?.change_reason !== "string" || Array.from(b.change_reason).length < 10) errors.push({ pointer: "/change_reason", code: "too_short" });
    b?.items?.forEach((i, n) => {
      if (typeof i.code !== "string" || !/^[a-z][a-z0-9_]{1,40}$/.test(i.code)) errors.push({ pointer: `/items/${n}/code`, code: "pattern" });
      if (typeof i.label_en !== "string" || !i.label_en) errors.push({ pointer: `/items/${n}/label_en`, code: "required" });
    });
    if (errors.length) return send(res, 400, problem(400, "ERR_VALIDATION", { errors }));
    const before = (state.codeLists[key] ?? []) as { code: string; id?: number }[];
    // The read-only item id is the server's: items are matched by code and keep their id.
    state.codeLists[key] = b!.items!.map((i) => {
      const old = before.find((o) => o.code === i.code);
      const { id: _ignored, ...rest } = i as { id?: number };
      return old?.id !== undefined ? { ...rest, id: old.id } : rest;
    }) as unknown[] as typeof before;
    audit(state, user, "code_list", 0, `code_list.${key}.put`, { items: before.length }, { items: b!.items!.length }, b!.change_reason!);
    return send(res, 200, { list_key: key, items: state.codeLists[key] });
  }

  if (path === "/v1/admin/outlets/outlet-kind" && method === "POST") {
    if (!ADMIN_WRITE.includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const b = (await readJson(req)) as { batch_uuid?: string; outlet_kind?: string; outlet_ids?: number[]; reason?: string } | undefined;
    if (!b || !b.batch_uuid || !["retail", "wholesale"].includes(b.outlet_kind ?? "") || !Array.isArray(b.outlet_ids) || b.outlet_ids.length < 1 || typeof b.reason !== "string" || Array.from(b.reason).length < 10) return send(res, 400, problem(400, "ERR_VALIDATION"));
    const seen = state.bulkBatches.get(b.batch_uuid);
    if (seen) return send(res, 200, { ...seen, replayed: true });
    let updated = 0;
    let unchanged = 0;
    for (const id of b.outlet_ids) {
      const o = state.tables.outlets!.find((x) => x.id === id);
      if (!o) continue;
      if (o.outlet_kind === b.outlet_kind) unchanged++;
      else {
        o.outlet_kind = b.outlet_kind;
        o.version++;
        updated++;
        audit(state, user, "outlet", id, "outlet.outlet_kind", { outlet_kind: b.outlet_kind === "wholesale" ? "retail" : "wholesale" }, { outlet_kind: b.outlet_kind! }, b.reason);
      }
    }
    const result = { batch_uuid: b.batch_uuid, updated, unchanged, replayed: false };
    state.bulkBatches.set(b.batch_uuid, result);
    return send(res, 200, result);
  }

  if (path.startsWith("/v1/admin/mdm/")) {
    const mctx: Ctx = { send, problem, readJson, audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason) };
    if (await handleMdm(state.mdm, mctx, { role: user.summary.role, username: user.summary.username, user_id: user.summary.user_id }, method, url, req, res)) return true;
  }

  if (path.startsWith("/v1/admin/") && user.master) {
    const cctx: Ctx = { send, problem, readJson, audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason) };
    const canW = ADMIN_WRITE.includes(user.summary.role);
    if (await handleCustom(state.custom, cctx, canW, method, url, req, res)) return true;
  }

  const tut = /^\/v1\/admin\/(?:tutorials(?:\/(\d+))?|assets)$/.exec(path);
  if (tut && user.master) {
    if (!(method === "GET" ? ADMIN_READ : ADMIN_WRITE).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const rows = state.tables.tutorials!;
    if (path === "/v1/admin/assets" && method === "POST") {
      const b = (await readJson(req)) as Record<string, unknown> | null;
      if (!b || typeof b.asset_id !== "string") return send(res, 400, problem(400, "ERR_VALIDATION"));
      const host = req.headers.host ?? "127.0.0.1";
      return send(res, 200, { asset_id: b.asset_id, upload_url: `http://${host}/__mock/blob/${b.asset_id}?sig=mock`, blob_path: `tutorials/${b.asset_id}`, expires_at: new Date(Date.now() + 600_000).toISOString() });
    }
    if (method === "GET") return send(res, 200, { items: rows });
    const b = (await readJson(req)) as Record<string, unknown> | null;
    const reason = typeof b?.change_reason === "string" ? b.change_reason : "";
    const need = ["kind", "title_en", "asset_id", "roles", "sort"];
    if (!b || Array.from(reason).length < 10 || need.some((k) => b[k] === undefined) || !state.blobs.has(String(b.asset_id))) return send(res, 400, problem(400, "ERR_VALIDATION"));
    const fields = { kind: b.kind, title_en: b.title_en, title_bn: b.title_bn ?? null, sort: b.sort, roles: b.roles, status: b.status ?? "active", asset_id: b.asset_id };
    if (method === "POST") {
      const row = { tutorial_id: state.nextId++, url: `https://blob.example/tutorials/${b.asset_id}`, bytes: state.blobs.get(String(b.asset_id)), duration_s: null, ...fields, version: 1 } as unknown as Row;
      rows.push(row);
      audit(state, user, "tutorial", String(row.tutorial_id), "tutorial.create", {}, { title_en: String(b.title_en) }, reason);
      return send(res, 201, row);
    }
    const row = rows.find((r) => r.tutorial_id === Number(tut[1]));
    if (!row) return send(res, 404, problem(404, "ERR_NOT_FOUND"));
    if (req.headers["if-match"] !== `"${row.version}"`) return send(res, 412, problem(412, "ERR_PRECONDITION_FAILED"));
    Object.assign(row, fields, { version: (row.version as number) + 1 });
    audit(state, user, "tutorial", String(row.tutorial_id), "tutorial.update", {}, { title_en: String(b.title_en) }, reason);
    return send(res, 200, row);
  }

  const def = /^\/v1\/admin\/(surveys|rubrics|content)(?:\/(\d+))?$/.exec(path);
  if (def && user.master) {
    if (!(method === "GET" ? ADMIN_READ : ADMIN_WRITE).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const kind = def[1]!;
    const idKey = kind === "surveys" ? "survey_id" : kind === "rubrics" ? "rubric_id" : "content_id";
    const rows = state.tables[kind]!;
    if (method === "GET") return send(res, 200, { items: rows, next_cursor: null });
    const b = (await readJson(req)) as Record<string, unknown> | null;
    const reason = typeof b?.change_reason === "string" ? b.change_reason : "";
    if (!b || Array.from(reason).length < 10) return send(res, 400, problem(400, "ERR_VALIDATION"));
    const { change_reason: _r, questions, criteria, ...rest } = b;
    void _r;
    const shaped: Record<string, unknown> = { ...rest };
    if (questions) shaped.questions = (questions as Record<string, unknown>[]).map((q, i) => ({ question_id: i + 1, answer_type: q.answer_type, label_en: q.label_en, label_bn: q.label_bn ?? null, option_codes: Array.isArray(q.option_codes) ? q.option_codes : [], requires_photo: q.photo === true || q.answer_type === "photo_only", key: q.key, required: q.required === true, show_if_key: q.show_if_key ?? null, show_if_bool: q.show_if_bool ?? null, photo: q.photo === true }));
    if (criteria) shaped.criteria = (criteria as Record<string, unknown>[]).map((c, i) => ({ criterion_id: i + 1, label_en: c.label_en, label_bn: c.label_bn ?? null, answer_type: c.answer_type === "stars_1_5" ? "score_1_5" : c.answer_type, enabled: true, key: c.key }));
    if (kind === "content") {
      if (!state.blobs.has(String(b.asset_id))) return send(res, 400, problem(400, "ERR_VALIDATION"));
      Object.assign(shaped, { asset_url: `https://blob.example/content/${b.asset_id}`, sha256: "b".repeat(64), bytes: state.blobs.get(String(b.asset_id)), duration_s: null, outlet_ids: [], updated_at: new Date().toISOString() });
    }
    if (method === "POST") {
      const row = { [idKey]: state.nextId++, version: 1, status: "active", ...shaped } as unknown as Row;
      rows.push(row);
      audit(state, user, kind, String(row[idKey]), `${kind}.create`, {}, {}, reason);
      return send(res, 201, row);
    }
    const row = rows.find((r) => r[idKey] === Number(def[2]));
    if (!row) return send(res, 404, problem(404, "ERR_NOT_FOUND"));
    if (req.headers["if-match"] !== `"${row.version}"`) return send(res, 412, problem(412, "ERR_PRECONDITION_FAILED"));
    Object.assign(row, shaped, { version: (row.version as number) + 1 });
    audit(state, user, kind, String(row[idKey]), `${kind}.update`, {}, {}, reason);
    return send(res, 200, row);
  }

  const fb = /^\/v1\/feedback(?:\/([0-9a-f-]{36}))?$/.exec(path);
  if (fb && user.master) {
    const rows = state.tables.feedback!;
    const triage: Role[] = ["SUPPORT", "ADMIN", "SUPERADMIN"]; // tso-manual W1 (FB-12): the API's FEEDBACK_TRIAGE
    if (!(method === "GET" ? ADMIN_READ : triage).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    if (method === "GET" && !fb[1]) {
      const cat = url.searchParams.get("category_code");
      const st = url.searchParams.get("status");
      const from = url.searchParams.get("from"); // tso-manual W1 (FB-12): FromDate / ToDate on the business date
      const to = url.searchParams.get("to");
      const items = rows.filter((r) => (!cat || r.category_code === cat) && (!st || r.status === st) && (!from || String(r.business_date) >= from) && (!to || String(r.business_date) <= to)).sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
      return send(res, 200, { items, next_cursor: null });
    }
    if (method === "PATCH" && fb[1]) {
      const row = rows.find((r) => r.feedback_uuid === fb[1]);
      if (!row) return send(res, 404, problem(404, "ERR_NOT_FOUND"));
      const b = (await readJson(req)) as Record<string, unknown> | null;
      const reason = typeof b?.reason === "string" ? b.reason : "";
      if (!b || !["new", "in_progress", "resolved", "closed"].includes(String(b.status)) || Array.from(reason).length < 10 || Object.keys(b).some((k) => k !== "status" && k !== "reason")) return send(res, 400, problem(400, "ERR_VALIDATION"));
      const before = { status: row.status as string };
      row.status = b.status as string;
      audit(state, user, "feedback", String(fb[1]), "feedback.status", before, { status: row.status as string }, reason);
      return send(res, 200, row);
    }
  }

  // Outlets in the caller's reach (every role); the mock's reach: a TSO sees zone 14 (its territory), a DMO and national roles all.
  if (path === "/v1/outlets" && method === "GET" && user.master) {
    const q = (url.searchParams.get("q") ?? "").toLowerCase();
    const limit = Math.min(500, Number(url.searchParams.get("limit") ?? 100));
    const items = state.tables.outlets!.filter((o) => (user.summary.role !== "TSO" || o.zone_id === 14) && (!q || [o.name, o.code, o.owner_name].some((x) => String(x ?? "").toLowerCase().includes(q))));
    return send(res, 200, { items: items.slice(0, limit), next_cursor: null });
  }

  // POST /v1/outlet-requests: the web source of an outlet change request (TSO and DMO, idempotent by request_uuid).
  if (path === "/v1/outlet-requests" && method === "POST" && user.master) {
    if (!["TSO", "DMO"].includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const b = (await readJson(req)) as Record<string, unknown> | undefined;
    const proposed = b?.proposed as Record<string, unknown> | undefined;
    const members: Record<string, string[]> = { info: ["name", "name_bn", "owner_name", "contact_number", "address", "sub_channel_id"], location: ["lat", "lng"], cluster: ["cluster_id"], close: ["close_reason_code"] };
    const type = String(b?.request_type);
    const okShape = b && typeof b.request_uuid === "string" && /^[0-9a-f-]{36}$/.test(b.request_uuid) && members[type] && typeof b.outlet_id === "number" && proposed && typeof proposed === "object" && Object.keys(proposed).length > 0 && Object.keys(proposed).every((k) => members[type]!.includes(k)) && Object.keys(b).every((k) => ["request_uuid", "request_type", "outlet_id", "proposed", "note"].includes(k));
    if (!okShape) return send(res, 400, problem(400, "ERR_VALIDATION"));
    const outlet = state.tables.outlets!.find((o) => o.id === b.outlet_id);
    if (!outlet) return send(res, 404, problem(404, "ERR_NOT_FOUND"));
    if (user.summary.role === "TSO" && outlet.zone_id !== 14) return send(res, 403, problem(403, "ERR_OUT_OF_SCOPE"));
    const rows = state.tables.outletRequests!;
    const seen = rows.find((r) => r.request_uuid === b.request_uuid);
    if (seen) {
      if (seen.requested_by_user_id !== user.summary.user_id || seen.outlet_id !== b.outlet_id || seen.request_type !== type) return send(res, 409, problem(409, "ERR_CONFLICT"));
      return send(res, 201, seen);
    }
    const row = { request_uuid: b.request_uuid, request_type: type, status: "pending", outlet_id: outlet.id, outlet_name: outlet.name, route_id: outlet.route_id, cluster_id: outlet.cluster_id, requested_by_user_id: user.summary.user_id, requested_at: new Date().toISOString(), rejection_reason: null, proposed, note: b.note ?? null, request_fix: null, photos: [], events: [], version: 1 } as unknown as Row;
    rows.push(row);
    audit(state, user, "outlet_request", String(b.request_uuid), "outlet_request.create", {}, { request_type: type }, typeof b.note === "string" ? b.note : null);
    return send(res, 201, row);
  }

  if (path.startsWith("/v1/outlet-requests")) {
    const ACT: Role[] = ["DMO", "WM", "ADMIN", "SUPERADMIN"];
    const READ: Role[] = ["TSO", "DMO", "WM", "TOP", "ANALYST", "ADMIN", "SUPERADMIN"];
    if (!(method === "GET" ? READ : ACT).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const ctx2: Ctx = { send, problem, readJson, audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason) };
    if (await handleTable(tableDefs(state), ctx2, method, url, req, res, ACT.includes(user.summary.role), user.summary.role, user.summary.user_id)) return true;
  }

  if (path.startsWith("/v1/admin/")) {
    const ctx: Ctx = {
      send,
      problem,
      readJson,
      audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason),
    };
    if (await handleTable(tableDefs(state), ctx, method, url, req, res, ADMIN_WRITE.includes(user.summary.role), user.summary.role, user.summary.user_id)) return true;
  }

  // listAudit (backend AuditRead.kt): ANALYST, SUPPORT, ADMIN, SUPERADMIN nationally; filters checked (400); keyset on id desc; masked per role.
  if (path === "/v1/admin/audit" && method === "GET") {
    if (!AUDIT_READERS.includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const q = url.searchParams;
    const errors: NonNullable<Problem["errors"]> = [];
    const entity = q.get("entity"), entityId = q.get("entity_id"), action = q.get("action"), actor = q.get("actor_user_id");
    const from = q.get("from"), to = q.get("to"), lim = q.get("limit"), cursor = q.get("cursor");
    const isDate = (d: string) => /^\d{4}-\d{2}-\d{2}$/.test(d) && new Date(`${d}T00:00:00Z`).toISOString().slice(0, 10) === d;
    if (entity !== null && !/^[a-z_]{2,40}$/.test(entity)) errors.push({ pointer: "query.entity", code: "invalid_value" });
    if (entityId !== null && (entityId.length === 0 || entityId.length > 64)) errors.push({ pointer: "query.entity_id", code: "invalid_value" });
    if (action !== null && !/^[a-z_.]{2,60}$/.test(action)) errors.push({ pointer: "query.action", code: "invalid_value" });
    if (actor !== null && !/^[1-9]\d{0,17}$/.test(actor)) errors.push({ pointer: "query.actor_user_id", code: "invalid_value" });
    if (from !== null && !isDate(from)) errors.push({ pointer: "query.from", code: "invalid_value" });
    if (to !== null && !isDate(to)) errors.push({ pointer: "query.to", code: "invalid_value" });
    if (from && to && isDate(from) && isDate(to) && (to < from || (Date.parse(to) - Date.parse(from)) / 86_400_000 > 92)) errors.push({ pointer: "query.to", code: "out_of_range" });
    const limit = lim === null ? 100 : Number(lim);
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) errors.push({ pointer: "query.limit", code: "out_of_range" });
    if (cursor !== null && !/^[1-9]\d{0,17}$/.test(cursor)) errors.push({ pointer: "query.cursor", code: "invalid_value" });
    if (errors.length) return send(res, 400, problem(400, "ERR_VALIDATION", { errors }));
    const day = (at: string) => new Date(Date.parse(at) + 6 * 3_600_000).toISOString().slice(0, 10); // Asia/Dhaka business date
    const rows = state.audit
      .filter((e) => (!entity || e.entity === entity) && (!entityId || e.entity_id === entityId) && (!action || e.action === action) && (!actor || e.actor_user_id === Number(actor)))
      .filter((e) => (!from || day(e.at) >= from) && (!to || day(e.at) <= to) && (!cursor || e.id < Number(cursor)))
      .sort((a, b) => b.id - a.id)
      .slice(0, limit + 1);
    const page = rows.slice(0, limit).map((e) => maskAudit(e, user.summary.role, a.piiUntil !== null));
    return send(res, 200, { items: page, next_cursor: rows.length > limit ? String(page[page.length - 1]!.id) : null });
  }
  return false;
}
