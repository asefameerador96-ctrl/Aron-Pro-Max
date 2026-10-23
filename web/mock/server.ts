// Mock Aron API for web development and tests (there is no live backend on Day 1).
// Every payload is typed with the types GENERATED from contract/openapi.yaml, so a contract rename breaks `tsc` here too.
// It implements only what the web rows use: login (+MFA), refresh, logout, me, admin clusters, audit. Run: `npm run mock`.
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { handleAdmin } from "./admin";
import type { Row } from "./tables";
import { LONG_BANGLA } from "./seed";
import { seedCodeLists, seedTables } from "./master-seed";
import { freshConfigStore, handleConfig, type ConfigStore } from "./config";
import { seedCustom, type CustomState } from "./custom";
import { freshMdmStore, type MdmStore } from "./mdm";
import { freshDashStore, handleDash, type DashStore } from "./dash";
import type { AuditEntry, Cluster, LoginResponse, Me, Problem, ProblemCode, Role, ScopeSummary, TokenPair, UserSummary } from "../src/contract/types";

export interface MockUser {
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
    nat1: u(2007, "nat1", "Nasrin National", "NATIONAL", "nat-pass-1", nationalScope),
    brand1: u(2008, "brand1", "Babul Brand", "BRAND", "brand-pass-1", nationalScope),
    nodash1: u(2009, "nodash1", "Nadia NoDash", "ANALYST", "nodash-pass-1", nationalScope), // stands in for an ADMIN whose module grants omit the dashboard (an ADMIN login needs MFA): every national read is 403
    analyst1: u(2003, "analyst1", "Nusrat Jahan", "ANALYST", "analyst-pass-1", nationalScope),
    // An ADMIN with module grants (D-ROLES-02): Me.modules lists targets and masterdata at r, outlets at w.
    mgrant1: { ...u(3106, "mgrant1", "Limited Admin", "ADMIN", "grant-pass-1", nationalScope, true), master: true },
    madmin1: { ...u(3101, "madmin1", "Salma Akter", "ADMIN", "admin-pass-1", nationalScope, true), master: true },
    msuper1: { ...u(3105, "msuper1", "Rafiq Chowdhury", "SUPERADMIN", "super-pass-1", nationalScope, true), master: true },
    msupport1: { ...u(3102, "msupport1", "Tanvir Ahmed", "SUPPORT", "support-pass-1", nationalScope, true), master: true },
    mdmo1: { ...u(3104, "mdmo1", "Habib Rahman", "DMO", "dmo-pass-2", { scope_version: 4, nodes: [{ type: "division", id: 10, code: "D-10", name: "Dhaka North" }] }), master: true },
    mtso1: { ...u(3103, "mtso1", "Rahim Uddin", "TSO", "tso-pass-1", { scope_version: 7, nodes: [{ type: "territory", id: 6, code: "T-334", name: "Banani" }] }), master: true },
    admin1: u(3001, "admin1", "Salma Akter", "ADMIN", "admin-pass-1", nationalScope, true),
    super1: u(3003, "super1", "Rafiq Chowdhury", "SUPERADMIN", "super-pass-1", nationalScope, true),
    support1: u(3002, "support1", "Tanvir Ahmed", "SUPPORT", "support-pass-1", nationalScope, true),
    sr334001: u(1001, "sr334001", "Testing Banani", "SR", "sr-pass-1", { scope_version: 7, nodes: [{ type: "route", id: 10231, code: "R-334-01", name: "RouteDaily" }] }),
    locked1: u(4001, "locked1", "Locked User", "TSO", "locked-pass-1", nationalScope, false, "locked"),
    newadmin1: u(4004, "newadmin1", "Fresh Admin", "ADMIN", "newadmin-pass-1", nationalScope, true), // no authenticator yet: enrols at the TOTP step
    pwmfa1: u(4003, "pwmfa1", "New Admin", "ADMIN", "pwmfa-pass-1", nationalScope, true, "password_change"),
    pwchange1: u(4002, "pwchange1", "New User", "TSO", "pwchange-pass-1", nationalScope, false, "password_change"),
  };
}

/** A test-registered endpoint (tests/helpers): answers before the built-in handlers, records every call. */
export interface StubCall { method: string; path: string; query: Record<string, string>; body: unknown; headers: IncomingMessage["headers"]; role: Role }
export interface StubResult { status: number; body?: unknown; headers?: Record<string, string> }
export interface Stub { method: string; path: string | RegExp; fn: (call: StubCall, match: RegExpMatchArray | null) => StubResult | Promise<StubResult> }

export interface State {
  /** Test knob: API paths starting with this answer 500 (screenshots of the error states). */
  failPrefix: string | null;
  stubs: Stub[];
  calls: StubCall[];
  users: Record<string, MockUser>;
  tables: Record<string, Row[]>;
  /** Bytes received on the mock blob URL, by asset id. */
  blobs: Map<string, number>;
  codeLists: Record<string, unknown[]>;
  custom: CustomState;
  mdm: MdmStore;
  bulkBatches: Map<string, { batch_uuid: string; updated: number; unchanged: number; replayed: boolean }>;
  /** Alias of tables.clusters (used by tests). */
  clusters: Cluster[];
  audit: AuditEntry[];
  access: Map<string, { userId: string; exp: number; piiUntil?: number }>;
  /** PII reveal (POST /v1/auth/reauth): failed tries per user (locks the reveal at 3) and the window length in seconds. */
  reauthFails: Map<string, number>;
  piiWindowS: number;
  refresh: Map<string, { userId: string }>;
  mfaTokens: Map<string, string>;
  /** Usernames that already confirmed an authenticator; the others may enrol one. */
  mfaEnrolled: Set<string>;
  pwcTokens: Map<string, string>;
  refreshCount: number;
  nextId: number;
  accessTtlS: number;
  dash: DashStore;
  cfg: ConfigStore;
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
  return { failPrefix: null, reauthFails: new Map(), piiWindowS: 900, stubs: [], calls: [], users: users(), tables, blobs: new Map(), custom: { tables, ...seedCustom() }, mdm: freshMdmStore(), codeLists: seedCodeLists(), bulkBatches: new Map(), clusters: tables.clusters as unknown as Cluster[], audit: [], access: new Map(), refresh: new Map(), mfaTokens: new Map(), mfaEnrolled: new Set(["mgrant1", "admin1", "madmin1", "msuper1", "msupport1", "support1", "pwmfa1"]), pwcTokens: new Map(), refreshCount: 0, nextId: 100, accessTtlS, dash: freshDashStore(), cfg: freshConfigStore() };
}

function problem(status: number, code: ProblemCode, extra: Partial<Problem> = {}): Problem {
  return { type: `urn:aron:problem:${code.toLowerCase()}`, title: code, status, code, request_id: randomUUID(), server_time: new Date().toISOString(), ...extra };
}

/** Set by POST /__mock/long-names: the seeded route, user and outlet names come back as real-length Bangla names. */
let longNames = false;
const longify = (text: string): string => LONG_BANGLA.reduce((acc, [from, to]) => acc.split(from).join(to), text);

function send(res: ServerResponse, status: number, body: unknown, headers: Record<string, string | string[]> = {}): void {
  const isProblem = status >= 400;
  const json = body === undefined ? "" : JSON.stringify(body);
  const text = longNames && !isProblem ? longify(json) : json;
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

function authed(state: State, req: IncomingMessage): { user: MockUser; piiUntil: number | null } | { error: Problem; status: number } {
  const h = req.headers.authorization;
  const token = h?.startsWith("Bearer ") ? h.slice(7) : "";
  const rec = state.access.get(token);
  if (!rec) return { status: 401, error: problem(401, "ERR_UNAUTHENTICATED") };
  if (rec.exp < Date.now()) return { status: 401, error: problem(401, "ERR_TOKEN_EXPIRED", { retryable: true }) };
  const user = state.users[rec.userId];
  return user ? { user, piiUntil: rec.piiUntil && rec.piiUntil > Date.now() ? rec.piiUntil : null } : { status: 401, error: problem(401, "ERR_UNAUTHENTICATED") };
}

function audit(state: State, user: MockUser, entity: string, entity_id: number | string, action: string, before: AuditEntry["before"], after: AuditEntry["after"], reason: string | null): void {
  const prev = state.audit[state.audit.length - 1]?.row_hash ?? "0".repeat(64);
  const entry = { id: state.audit.length + 1, at: new Date().toISOString(), actor_user_id: user.summary.user_id, actor_username: user.summary.username, actor_role: user.summary.role, via: "web" as const, entity, entity_id: String(entity_id), action, before, after, reason, request_id: randomUUID() };
  state.audit.push({ ...entry, row_hash: createHash("sha256").update(prev + JSON.stringify(entry)).digest("hex") });
}

async function handle(state: State, reset: () => void, req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", "http://mock");
  const path = url.pathname;
  const method = req.method ?? "GET";

  if (path.startsWith("/__mock/blob/")) {
    // Stand-in for the write-only SAS URL: the browser PUTs the file here (cross-origin, so it answers the preflight).
    const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "PUT, OPTIONS", "Access-Control-Allow-Headers": "*" };
    if (method === "OPTIONS") return send(res, 204, undefined, cors);
    if (method !== "PUT") return send(res, 405, null, cors);
    let bytes = 0;
    for await (const chunk of req) bytes += (chunk as Buffer).length;
    state.blobs.set(path.slice("/__mock/blob/".length), bytes);
    return send(res, 201, undefined, cors);
  }
  if (path === "/__mock/health") return send(res, 200, { ok: true }, { "X-Aron-Api": "0" });
  if (path === "/__mock/reset" && method === "POST") {
    reset();
    return send(res, 204, undefined);
  }
  if (path === "/__mock/state") return send(res, 200, { refreshCount: state.refreshCount, audit: state.audit, clusters: state.clusters, tables: state.tables, custom: state.custom, webEntries: [...state.cfg.entries.values()], otps: state.cfg.otps, exports: state.dash.exports, actions: state.dash.actions, leave: state.dash.leave });
  if (path === "/__mock/now" && method === "POST") {
    state.dash.now = ((await readJson(req)) as { now?: string | null } | undefined)?.now ?? null;
    return send(res, 204, undefined);
  }
  if (path === "/__mock/audit" && method === "POST") {
    // Test seed: one audit row written as the given user (e2e of listAudit's per-role masking).
    const b = (await readJson(req)) as { as: string; entity: string; entity_id: string; action: string; before: AuditEntry["before"]; after: AuditEntry["after"]; reason: string | null };
    const as = state.users[b.as];
    if (!as) return send(res, 400, problem(400, "ERR_VALIDATION"));
    audit(state, as, b.entity, b.entity_id, b.action, b.before, b.after, b.reason);
    return send(res, 204, undefined);
  }
  if (path === "/__mock/long-names" && method === "POST") {
    longNames = ((await readJson(req)) as { on?: boolean } | undefined)?.on !== false;
    return send(res, 204, undefined);
  }
  if (path === "/__mock/pii-window" && method === "POST") {
    state.piiWindowS = ((await readJson(req)) as { seconds?: number } | undefined)?.seconds ?? 900;
    return send(res, 204, undefined);
  }
  if (path === "/__mock/fail" && method === "POST") {
    state.failPrefix = ((await readJson(req)) as { prefix?: string | null } | undefined)?.prefix ?? null;
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
    if (u.state === "password_change") {
      const pwc = `pwc.${randomBytes(18).toString("base64url")}`;
      state.pwcTokens.set(pwc, u.summary.username);
      return send(res, 200, loginBody(state, u, { status: "password_change_required", password_change_token: pwc }));
    }
    if (u.mfa) {
      const mfa = `mfa.${randomBytes(18).toString("base64url")}`;
      state.mfaTokens.set(mfa, u.summary.username);
      return send(res, 200, loginBody(state, u, { status: "mfa_required", mfa_token: mfa }));
    }
    const t = newTokens(state, u.summary.username);
    return send(res, 200, loginBody(state, u, { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), refresh_expires_at: "2027-01-02T00:00:00.000Z", scope: u.scope }), { "Set-Cookie": rtCookie(t.rt, 86400) });
  }

  if (path === "/v1/auth/mfa/enrol" && method === "POST") {
    // The mfa_token of the login step is accepted (audience aron-mfa); a second enrolment is a conflict (an admin resets it).
    const tok = /^Bearer (.+)$/.exec(String(req.headers.authorization ?? ""))?.[1];
    const username = tok ? state.mfaTokens.get(tok) : undefined;
    if (!username) return send(res, 401, problem(401, "ERR_UNAUTHENTICATED"));
    if (state.mfaEnrolled.has(username)) return send(res, 409, problem(409, "ERR_CONFLICT"));
    // Like the backend: an unconfirmed enrolment is replaced; only a confirmed one (a good code at the verify step) is a conflict.
    return send(res, 200, { otpauth_uri: `otpauth://totp/Aron:${username}?secret=JBSWY3DPEHPK3PXP&issuer=Aron`, recovery_codes: Array.from({ length: 10 }, (_, i) => `AB${i}C-D${i}EF`) });
  }

  if (path === "/v1/auth/mfa/verify" && method === "POST") {
    const b = (await readJson(req)) as Record<string, unknown> | undefined;
    const username = typeof b?.mfa_token === "string" ? state.mfaTokens.get(b.mfa_token) : undefined;
    if (!b || !username) return send(res, 401, problem(401, "ERR_AUTH_MFA_INVALID"));
    if (b.code !== "123456" && b.code !== "AAAA-BBBB") return send(res, 401, problem(401, "ERR_AUTH_MFA_INVALID"));
    state.mfaTokens.delete(b.mfa_token as string);
    state.mfaEnrolled.add(username);
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

  // The forced change after login: only the password_change_token (Bearer) is accepted, and the answer continues the login.
  const bearer = req.headers.authorization?.startsWith("Bearer ") ? req.headers.authorization.slice(7) : "";
  if (path === "/v1/auth/change-password" && method === "POST" && bearer.startsWith("pwc.")) {
    const username = state.pwcTokens.get(bearer);
    const b = (await readJson(req)) as Record<string, unknown> | undefined;
    if (!username) return send(res, 401, problem(401, "ERR_UNAUTHENTICATED"));
    const u = state.users[username]!;
    if (!b || typeof b.current_password !== "string" || typeof b.new_password !== "string") return send(res, 400, problem(400, "ERR_VALIDATION"));
    if (b.current_password !== u.password) return send(res, 400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/current_password", code: "invalid" }] }));
    if (b.new_password.length < 12 || !/[A-Z]/.test(b.new_password) || !/[a-z]/.test(b.new_password) || !/\d/.test(b.new_password)) return send(res, 400, problem(400, "ERR_AUTH_PASSWORD_POLICY"));
    state.pwcTokens.delete(bearer);
    u.password = b.new_password;
    u.state = undefined;
    if (u.mfa) {
      const mfa = `mfa.${randomBytes(18).toString("base64url")}`;
      state.mfaTokens.set(mfa, u.summary.username);
      return send(res, 200, loginBody(state, u, { status: "mfa_required", mfa_token: mfa }));
    }
    const t = newTokens(state, username);
    return send(res, 200, loginBody(state, u, { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), refresh_expires_at: "2027-01-02T00:00:00.000Z", scope: u.scope }), { "Set-Cookie": rtCookie(t.rt, 86400) });
  }

  // Everything below needs a bearer token.
  const a = authed(state, req);
  if ("error" in a) return send(res, a.status, a.error);
  const user = a.user;

  // PII reveal (backend-authz, V0086): ADMIN and SUPERADMIN hold pii.reveal; their Me.pii is true only inside the window.
  // Every other role (ANALYST, SUPPORT, TSO...) is masked always; the dashboards and reports follow Me.pii.
  const revealer = user.summary.role === "ADMIN" || user.summary.role === "SUPERADMIN";
  if (path === "/v1/auth/reauth" && method === "POST") {
    if (!revealer) return send(res, 403, problem(403, "ERR_FORBIDDEN"));
    const name = user.summary.username;
    if ((state.reauthFails.get(name) ?? 0) >= 3) return send(res, 403, problem(403, "ERR_AUTH_ACCOUNT_LOCKED"), { "Retry-After": "900" });
    const b = (await readJson(req)) as { password?: unknown; code?: unknown } | undefined;
    if (!b || typeof b.password !== "string" || b.password.length < 1 || (b.code != null && (typeof b.code !== "string" || !/^[0-9]{6}$/.test(b.code))) || (user.mfa && b.code == null)) return send(res, 400, problem(400, "ERR_VALIDATION"));
    if (b.password !== user.password || (user.mfa && b.code !== "123456")) {
      state.reauthFails.set(name, (state.reauthFails.get(name) ?? 0) + 1);
      return send(res, 401, problem(401, "ERR_AUTH_INVALID_CREDENTIALS"));
    }
    state.reauthFails.delete(name);
    const t = newTokens(state, name);
    const piiUntil = Math.min(t.exp, Date.now() + state.piiWindowS * 1000);
    state.access.get(t.at)!.piiUntil = piiUntil;
    return send(res, 200, { access_token: t.at, access_expires_at: new Date(t.exp).toISOString(), pii_until: new Date(piiUntil).toISOString() });
  }

  if (path === "/v1/me") {
    const pii = revealer && a.piiUntil !== null;
    const perms = user.summary.role === "ADMIN" ? ["admin.master.write", "admin.audit.read"] : ["dashboards.read"];
    const me: Me = { user: user.summary, permissions: revealer ? [...perms, "pii.reveal"] : perms, scope: user.scope, pii, pii_until: revealer && a.piiUntil !== null ? new Date(a.piiUntil).toISOString() : null, mfa_enabled: user.mfa, ...(user.summary.role === "TSO" ? { menus: [{ menu_id: "dashboard", actions: ["view"] as ("view")[] }] } : {}), ...(user.summary.username === "mgrant1" ? { modules: { targets: "r" as const, masterdata: "r" as const, outlets: "w" as const } } : {}) };
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

  if (await handleConfig({
    method, path, url, role: user.summary.role, userId: user.summary.user_id, store: state.cfg,
    body: () => readJson(req),
    send: (status, body) => send(res, status, body),
    problem,
    audit: (entity, id, action, before, after, reason) => audit(state, user, entity, id, action, before, after, reason),
  })) return;

  if (state.failPrefix && path.startsWith(state.failPrefix)) return send(res, 500, problem(500, "ERR_INTERNAL"));

  if (!user.master && await handleDash({
    piiOpen: revealer && a.piiUntil !== null,
    user: { role: user.summary.role, scope: user.scope, id: user.summary.user_id, name: user.summary.full_name, password: state.dash.passwords[user.summary.user_id] ?? user.password },
    method, path, url, store: state.dash, problem,
    body: () => readJson(req),
    send: (status, body, headers) => send(res, status, body, headers),
    raw: (status, contentType, body, headers) => {
      res.writeHead(status, { "Content-Type": contentType, "X-Aron-Api": "1", "X-Request-Id": randomUUID(), ...headers });
      res.end(status === 204 ? undefined : body);
    },
  })) return;

  if (await handleAdmin({ state, user, a, send, problem, readJson, audit }, method, path, url, req, res)) return;

  return send(res, 404, problem(404, "ERR_NOT_FOUND"));
}

// Start when run directly (`npm run mock`, Playwright webServer).
if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const port = Number(process.env.MOCK_PORT ?? 4010);
  const { server } = createMock();
  server.listen(port, "127.0.0.1", () => console.log(`mock API on http://127.0.0.1:${port}`));
}
