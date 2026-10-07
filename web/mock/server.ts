// Mock Aron API for web development and tests (there is no live backend on Day 1).
// Every payload is typed with the types GENERATED from contract/openapi.yaml, so a contract rename breaks `tsc` here too.
// It implements only what the web rows use: login (+MFA), refresh, logout, me, admin clusters, audit. Run: `npm run mock`.
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { handleTable, type Ctx, type Row } from "./tables";
import { seedCodeLists, seedTables, tableDefs } from "./master-seed";
import { handleCustom, seedCustom, type CustomState } from "./custom";
import { freshDashStore, handleDash, hasPii, type DashStore } from "./dash";
import type { AuditEntry, Cluster, LoginResponse, Me, Problem, ProblemCode, Role, ScopeSummary, TokenPair, UserSummary } from "../src/contract/types";

interface MockUser {
  summary: UserSummary;
  password: string;
  scope: ScopeSummary;
  mfa: boolean;
  state?: "locked" | "password_change";
  /** Master-data fixture user: skips the dashboard mock (mock/dash.ts) so the admin portal sees the master-data tables of mock/master-seed.ts. */
  master?: boolean;
}

const nationalScope: ScopeSummary = { scope_version: 3, nodes: [{ type: "national", id: 0, code: null, name: null }] };

function users(): Record<string, MockUser> {
  const u = (user_id: number, username: string, full_name: string, role: Role, password: string, scope: ScopeSummary, mfa = false, state?: MockUser["state"]): MockUser => ({
    summary: { user_id, username, full_name, role, designation: role, locale: "bn" },
    password,
    scope,
    mfa,
    state,
  });
  return {
    tso334: u(2001, "tso334", "Rahim Uddin", "TSO", "tso-pass-1", { scope_version: 7, nodes: [{ type: "territory", id: 334, code: "T-334", name: "Banani" }] }),
    tso335: u(2005, "tso335", "Salma Begum", "TSO", "tso-pass-2", { scope_version: 7, nodes: [{ type: "territory", id: 335, code: "T-335", name: "Gulshan" }] }),
    tso999: u(2006, "tso999", "Outside Scope", "TSO", "tso-pass-3", { scope_version: 7, nodes: [{ type: "territory", id: 999, code: "T-999", name: "Elsewhere" }] }),
    dmo1: u(2004, "dmo1", "Habib Rahman", "DMO", "dmo-pass-1", { scope_version: 4, nodes: [{ type: "division", id: 10, code: "D-10", name: "Dhaka North" }] }),
    wm1: u(2002, "wm1", "Karim Hossain", "WM", "wm-pass-1", { scope_version: 2, nodes: [{ type: "wing", id: 1, code: "W-1", name: "Dhaka Wing" }] }),
    analyst1: u(2003, "analyst1", "Nusrat Jahan", "ANALYST", "analyst-pass-1", nationalScope),
    madmin1: { ...u(3101, "madmin1", "Salma Akter", "ADMIN", "admin-pass-1", nationalScope, true), master: true },
    msupport1: { ...u(3102, "msupport1", "Tanvir Ahmed", "SUPPORT", "support-pass-1", nationalScope, true), master: true },
    mtso1: { ...u(3103, "mtso1", "Rahim Uddin", "TSO", "tso-pass-1", { scope_version: 7, nodes: [{ type: "territory", id: 6, code: "T-334", name: "Banani" }] }), master: true },
    admin1: u(3001, "admin1", "Salma Akter", "ADMIN", "admin-pass-1", nationalScope, true),
    support1: u(3002, "support1", "Tanvir Ahmed", "SUPPORT", "support-pass-1", nationalScope, true),
    sr334001: u(1001, "sr334001", "Testing Banani", "SR", "sr-pass-1", { scope_version: 7, nodes: [{ type: "route", id: 10231, code: "R-334-01", name: "RouteDaily" }] }),
    locked1: u(4001, "locked1", "Locked User", "TSO", "locked-pass-1", nationalScope, false, "locked"),
    pwchange1: u(4002, "pwchange1", "New User", "TSO", "pwchange-pass-1", nationalScope, false, "password_change"),
  };
}

/** A test-registered endpoint (tests/helpers): answers before the built-in handlers, records every call. */
export interface StubCall { method: string; path: string; query: Record<string, string>; body: unknown; headers: IncomingMessage["headers"]; role: Role }
export interface StubResult { status: number; body?: unknown; headers?: Record<string, string> }
export interface Stub { method: string; path: string | RegExp; fn: (call: StubCall, match: RegExpMatchArray | null) => StubResult | Promise<StubResult> }

interface State {
  stubs: Stub[];
  calls: StubCall[];
  users: Record<string, MockUser>;
  tables: Record<string, Row[]>;
  codeLists: Record<string, unknown[]>;
  custom: CustomState;
  bulkBatches: Map<string, { batch_uuid: string; updated: number; unchanged: number; replayed: boolean }>;
  /** Alias of tables.clusters (used by tests). */
  clusters: Cluster[];
  audit: AuditEntry[];
  access: Map<string, { userId: string; exp: number }>;
  refresh: Map<string, { userId: string }>;
  mfaTokens: Map<string, string>;
  refreshCount: number;
  nextId: number;
  accessTtlS: number;
  dash: DashStore;
}

export interface MockOptions {
  port?: number;
  accessTtlS?: number;
}

export function createMock(opts: MockOptions = {}): { server: Server; state: State; reset: () => void } {
  const state: State = freshState(opts.accessTtlS);
  const reset = () => Object.assign(state, freshState(opts.accessTtlS));
  const server = createServer((req, res) => {
    handle(state, reset, req, res).catch((e) => {
      send(res, 500, problem(500, "ERR_INTERNAL", { detail: String(e) }));
    });
  });
  return { server, state, reset };
}

function freshState(accessTtlS = Number(process.env.MOCK_ACCESS_TTL_S ?? 900)): State {
  const tables = seedTables();
  return { stubs: [], calls: [], users: users(), tables, custom: { tables, ...seedCustom() }, codeLists: seedCodeLists(), bulkBatches: new Map(), clusters: tables.clusters as unknown as Cluster[], audit: [], access: new Map(), refresh: new Map(), mfaTokens: new Map(), refreshCount: 0, nextId: 100, accessTtlS, dash: freshDashStore() };
}

function problem(status: number, code: ProblemCode, extra: Partial<Problem> = {}): Problem {
  return { type: `urn:aron:problem:${code.toLowerCase()}`, title: code, status, code, request_id: randomUUID(), server_time: new Date().toISOString(), ...extra };
}

function send(res: ServerResponse, status: number, body: unknown, headers: Record<string, string | string[]> = {}): void {
  const isProblem = status >= 400;
  const text = body === undefined ? "" : JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": isProblem ? "application/problem+json" : "application/json; charset=utf-8",
    "X-Aron-Api": "1",
    "X-Request-Id": randomUUID(),
    "X-Server-Time": new Date().toISOString(),
    "X-Config-Version": "318",
    "X-Server-Generation": "00000000-0000-4000-8000-000000000001",
    ...headers,
  });
  res.end(status === 204 ? undefined : text);
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return Symbol.for("malformed");
  }
}

function unknownMembers(body: Record<string, unknown>, allowed: string[]): Problem["errors"] {
  return Object.keys(body)
    .filter((k) => !allowed.includes(k))
    .map((k) => ({ pointer: `/${k}`, code: "unknown_member" }));
}

function rtCookie(token: string, maxAgeS: number): string {
  return `aron_rt=${token}; HttpOnly; Secure; SameSite=Strict; Path=/v1/auth/refresh; Max-Age=${maxAgeS}`;
}

function newTokens(state: State, username: string) {
  const at = `at.${randomBytes(24).toString("base64url")}`;
  const rt = randomBytes(32).toString("base64url");
  const exp = Date.now() + state.accessTtlS * 1000;
  state.access.set(at, { userId: username, exp });
  state.refresh.set(rt, { userId: username });
  return { at, rt, exp };
}

function loginBody(state: State, u: MockUser, extra: Partial<LoginResponse>): LoginResponse {
  return {
    status: "ok",
    access_token: null,
    access_expires_at: null,
    refresh_token: null,
    refresh_expires_at: null,
    upload_refresh_token: null,
    bind_token: null,
    mfa_token: null,
    user: u.summary,
    scope: null,
    device: null,
    config_version: 318,
    server_time: new Date().toISOString(),
    min_app_version_code: null,
    ...extra,
  };
}

function authed(state: State, req: IncomingMessage): { user: MockUser } | { error: Problem; status: number } {
  const h = req.headers.authorization;
  const token = h?.startsWith("Bearer ") ? h.slice(7) : "";
  const rec = state.access.get(token);
  if (!rec) return { status: 401, error: problem(401, "ERR_UNAUTHENTICATED") };
  if (rec.exp < Date.now()) return { status: 401, error: problem(401, "ERR_TOKEN_EXPIRED", { retryable: true }) };
  const user = state.users[rec.userId];
  return user ? { user } : { status: 401, error: problem(401, "ERR_UNAUTHENTICATED") };
}

// docs/24 s8.5: master data is readable by DMO, WM and TOP as well (the approval panel reads zones and users).
const ADMIN_READ: Role[] = ["ADMIN", "SUPERADMIN", "SUPPORT", "ANALYST", "DMO", "WM", "TOP"];
const ADMIN_WRITE: Role[] = ["ADMIN", "SUPERADMIN"];

function audit(state: State, user: MockUser, entity: string, entity_id: number | string, action: string, before: AuditEntry["before"], after: AuditEntry["after"], reason: string | null): void {
  const prev = state.audit[state.audit.length - 1]?.row_hash ?? "0".repeat(64);
  const entry = { id: state.audit.length + 1, at: new Date().toISOString(), actor_user_id: user.summary.user_id, actor_username: user.summary.username, actor_role: user.summary.role, via: "web" as const, entity, entity_id: String(entity_id), action, before, after, reason, request_id: randomUUID() };
  state.audit.push({ ...entry, row_hash: createHash("sha256").update(prev + JSON.stringify(entry)).digest("hex") });
}

async function handle(state: State, reset: () => void, req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", "http://mock");
  const path = url.pathname;
  const method = req.method ?? "GET";

  if (path === "/__mock/health") return send(res, 200, { ok: true }, { "X-Aron-Api": "0" });
  if (path === "/__mock/reset" && method === "POST") {
    reset();
    return send(res, 204, undefined);
  }
  if (path === "/__mock/state") return send(res, 200, { refreshCount: state.refreshCount, audit: state.audit, clusters: state.clusters, tables: state.tables, custom: state.custom, exports: state.dash.exports, actions: state.dash.actions, leave: state.dash.leave });
  if (path === "/__mock/now" && method === "POST") {
    state.dash.now = ((await readJson(req)) as { now?: string | null } | undefined)?.now ?? null;
    return send(res, 204, undefined);
  }
  if (path === "/__mock/tutorial" && method === "POST") {
    state.dash.tutorialExtra.push((await readJson(req)) as DashStore["tutorialExtra"][number]);
    return send(res, 204, undefined);
  }

  if (path === "/v1/health") return send(res, 200, { status: "ok", api: "/v1", server_time: new Date().toISOString(), generation: "00000000-0000-4000-8000-000000000001", build: "mock" });

  if (path === "/v1/auth/login" && method === "POST") {
    const body = await readJson(req);
    if (typeof body !== "object" || body === null || Array.isArray(body)) return send(res, 400, problem(400, "ERR_MALFORMED_JSON"));
    const b = body as Record<string, unknown>;
    const extra = unknownMembers(b, ["username", "password", "client", "device_uuid"]);
    if (extra?.length || typeof b.username !== "string" || typeof b.password !== "string" || b.client !== "web") return send(res, 400, problem(400, "ERR_VALIDATION", { errors: extra }));
    const u = state.users[b.username.toLowerCase()];
    if (!u || u.password !== b.password) return send(res, 401, problem(401, "ERR_AUTH_INVALID_CREDENTIALS"));
    if (u.state === "locked") return send(res, 403, problem(403, "ERR_AUTH_ACCOUNT_LOCKED", { retry_after_s: 900 }));
    if (u.state === "password_change") return send(res, 200, loginBody(state, u, { status: "password_change_required" }));
    if (u.mfa) {
      const mfa = `mfa.${randomBytes(18).toString("base64url")}`;
      state.mfaTokens.set(mfa, u.summary.username);
      return send(res, 200, loginBody(state, u, { status: "mfa_required", mfa_token: mfa }));
    }
    const t = newTokens(state, u.summary.username);
    return send(res, 200, loginBody(state, u, { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), refresh_expires_at: "2027-01-02T00:00:00.000Z", scope: u.scope }), { "Set-Cookie": rtCookie(t.rt, 86400) });
  }

  if (path === "/v1/auth/mfa/verify" && method === "POST") {
    const b = (await readJson(req)) as Record<string, unknown> | undefined;
    const username = typeof b?.mfa_token === "string" ? state.mfaTokens.get(b.mfa_token) : undefined;
    if (!b || !username) return send(res, 401, problem(401, "ERR_AUTH_MFA_INVALID"));
    if (b.code !== "123456" && b.code !== "AAAA-BBBB") return send(res, 401, problem(401, "ERR_AUTH_MFA_INVALID"));
    state.mfaTokens.delete(b.mfa_token as string);
    const u = state.users[username]!;
    const t = newTokens(state, username);
    return send(res, 200, loginBody(state, u, { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), refresh_expires_at: "2027-01-02T00:00:00.000Z", scope: u.scope }), { "Set-Cookie": rtCookie(t.rt, 86400) });
  }

  if (path === "/v1/auth/refresh" && method === "POST") {
    const m = /(?:^|;\s*)aron_rt=([^;]+)/.exec(req.headers.cookie ?? "");
    const rec = m?.[1] ? state.refresh.get(m[1]) : undefined;
    if (!m?.[1] || !rec) return send(res, 401, problem(401, "ERR_AUTH_REFRESH_INVALID"));
    state.refresh.delete(m[1]);
    state.refreshCount++;
    const t = newTokens(state, rec.userId);
    const pair: TokenPair = { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), refresh_token: null, refresh_expires_at: "2027-01-02T00:00:00.000Z", scope_version: 7, server_time: new Date().toISOString() };
    return send(res, 200, pair, { "Set-Cookie": rtCookie(t.rt, 86400) });
  }

  if (path === "/v1/auth/logout" && method === "POST") return send(res, 204, undefined);

  // Everything below needs a bearer token.
  const a = authed(state, req);
  if ("error" in a) return send(res, a.status, a.error);
  const user = a.user;

  if (path === "/v1/me") {
    const me: Me = { user: user.summary, permissions: user.summary.role === "ADMIN" ? ["admin.master.write", "admin.audit.read"] : ["dashboards.read"], scope: user.scope, pii: hasPii(user.summary.role), mfa_enabled: user.mfa, ...(user.summary.role === "TSO" ? { menus: [{ menu_id: "dashboard", actions: ["view"] as ("view")[] }] } : {}) };
    return send(res, 200, me);
  }

  for (const stub of state.stubs) {
    const match = typeof stub.path === "string" ? (stub.path === path ? [] : null) : path.match(stub.path);
    if (stub.method !== method || !match) continue;
    const body = method === "GET" ? undefined : await readJson(req);
    const call: StubCall = { method, path, query: Object.fromEntries(url.searchParams), body, headers: req.headers, role: user.summary.role };
    state.calls.push(call);
    const r = await stub.fn(call, match as RegExpMatchArray);
    return send(res, r.status, r.body, r.headers);
  }

  if (!user.master && await handleDash({
    user: { role: user.summary.role, scope: user.scope, id: user.summary.user_id, name: user.summary.full_name, password: state.dash.passwords[user.summary.user_id] ?? user.password },
    method, path, url, store: state.dash, problem,
    body: () => readJson(req),
    send: (status, body, headers) => send(res, status, body, headers),
    raw: (status, contentType, body, headers) => {
      res.writeHead(status, { "Content-Type": contentType, "X-Aron-Api": "1", "X-Request-Id": randomUUID(), ...headers });
      res.end(status === 204 ? undefined : body);
    },
  })) return;

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
    const before = state.codeLists[key] ?? [];
    state.codeLists[key] = b!.items!;
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

  if (path.startsWith("/v1/admin/") && user.master) {
    const cctx: Ctx = { send, problem, readJson, audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason) };
    const canW = ADMIN_WRITE.includes(user.summary.role);
    if (await handleCustom(state.custom, cctx, canW, method, url, req, res)) return;
  }

  if (path.startsWith("/v1/outlet-requests")) {
    const ACT: Role[] = ["DMO", "WM", "ADMIN", "SUPERADMIN"];
    const READ: Role[] = ["TSO", "DMO", "WM", "TOP", "ANALYST", "ADMIN", "SUPERADMIN"];
    if (!(method === "GET" ? READ : ACT).includes(user.summary.role)) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const ctx2: Ctx = { send, problem, readJson, audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason) };
    if (await handleTable(tableDefs(state), ctx2, method, url, req, res, ACT.includes(user.summary.role), user.summary.role, user.summary.user_id)) return;
  }

  if (path.startsWith("/v1/admin/")) {
    const ctx: Ctx = {
      send,
      problem,
      readJson,
      audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason),
    };
    if (await handleTable(tableDefs(state), ctx, method, url, req, res, ADMIN_WRITE.includes(user.summary.role), user.summary.role, user.summary.user_id)) return;
  }

  if (path === "/v1/admin/audit" && method === "GET") {
    const q = url.searchParams;
    const items = state.audit.filter((e) => (!q.get("entity") || e.entity === q.get("entity")) && (!q.get("entity_id") || e.entity_id === q.get("entity_id")) && (!q.get("action") || e.action === q.get("action"))).reverse();
    return send(res, 200, { items, next_cursor: null });
  }

  return send(res, 404, problem(404, "ERR_NOT_FOUND"));
}

// Start when run directly (`npm run mock`, Playwright webServer).
if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const port = Number(process.env.MOCK_PORT ?? 4010);
  const { server } = createMock();
  server.listen(port, "127.0.0.1", () => console.log(`mock API on http://127.0.0.1:${port}`));
}
