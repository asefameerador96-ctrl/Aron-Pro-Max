// Mock of the configuration surface (docs/24 s8): registry keys, versions, change requests with maker-checker, rollback, the geofence
// reports, the permission matrix, device OTPs and the web-entry route-day. In memory, reset with the mock. Used by the Playwright
// journeys and the accessibility/tier specs; it implements only what the config console reads and writes.
import { randomUUID } from "node:crypto";
import type { Problem, ProblemCode, Role } from "../src/contract/types";
import type { ConfigChange, ConfigKey, ConfigVersion, ResolvedConfigValue, PermissionMatrix, WebEntryRouteDay, DeviceOtp } from "../src/lib/admin/types";

type Json = number | boolean | string | unknown[] | Record<string, unknown>;

interface ValueRow { key: string; scope_type: string; scope_id: number; value: Json }
interface VersionRec { meta: ConfigVersion; values: ValueRow[] }

export interface ConfigStore {
  changes: ConfigChange[];
  versions: VersionRec[];
  values: ValueRow[];
  matrix: PermissionMatrix["roles"];
  otps: DeviceOtp[];
  entries: Map<string, WebEntryRouteDay>;
  nextChange: number;
  /** Permission edits wait here until a second SUPERADMIN approves (C3). */
  pendingMenus: Map<number, { role: string; menus: PermissionMatrix["roles"][number]["menus"] }>;
}

const K = (key: string, area: string, value_type: ConfigKey["value_type"], default_value: Json, risk_class: number, extra: Partial<ConfigKey> = {}): ConfigKey => ({
  key, area, kind: "S", value_type, default_value: default_value as ConfigKey["default_value"], bounds: {}, scope_levels: ["global"], risk_class, effect: "B", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "config.edit", description_en: key, ...extra,
});

export const KEYS: ConfigKey[] = [
  K("cfg.geo.radius_m", "geo", "int", 100, 3, { bounds: { min: 10, max: 5000 }, scope_levels: ["global", "territory", "zone", "route"], restrictive_dir: "down", description_en: "Geofence radius in metres" }),
  K("cfg.sync.batch_max", "sync", "int", 200, 1, { bounds: { min: 10, max: 1000 }, description_en: "Largest sync batch" }),
  K("cfg.flag.web_entry", "flags", "bool", true, 1, { description_en: "Web Entry enabled" }),
  K("cfg.print.footer", "print", "text", "Thank you", 0, { description_en: "Memo footer line" }),
  K("cfg.web.entry_classes", "web", "list", [11, 12], 1, { scope_levels: ["global", "zone"], description_en: "Web Entry classes" }),
];

const emptyMenus = (menus: string[], actions: ("view" | "create" | "edit" | "approve" | "export" | "void")[]) => menus.map((menu_id) => ({ menu_id, actions }));
const ROLES: Role[] = ["SR", "AMO", "TSO", "DMO", "WM", "TOP", "ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"];

export function freshConfigStore(): ConfigStore {
  const values: ValueRow[] = KEYS.map((k) => ({ key: k.key, scope_type: "global", scope_id: 0, value: k.default_value as Json }));
  const now = new Date().toISOString();
  return {
    changes: [],
    versions: [{ meta: { version: 318, kind: "content", committed_at: now, committed_by: 1, summary: "Initial values", max_risk_class: 0 }, values: structuredClone(values) }],
    values,
    matrix: ROLES.map((role) => ({ role, menus: role === "TSO" ? emptyMenus(["dashboard", "reports"], ["view"]) : emptyMenus(["dashboard"], ["view"]) })),
    otps: [{ user_id: 1001, username: "sr334001", full_name: "Testing Banani", zone_id: 3341, otp: "482916", device_model: "Samsung A04", created_at: now, expires_at: new Date(Date.now() + 600_000).toISOString(), attempts: 0, employee_code: "E-1001", zone_code: "zone-3341", zone_name: "Banani Zone 1" }],
    entries: new Map(),
    nextChange: 1,
    pendingMenus: new Map(),
  };
}

export interface CfgCtx {
  method: string;
  path: string;
  url: URL;
  role: Role;
  userId: number;
  store: ConfigStore;
  body: () => Promise<unknown>;
  send: (status: number, body: unknown) => void;
  problem: (status: number, code: ProblemCode, extra?: Partial<Problem>) => Problem;
  audit: (entity: string, id: number | string, action: string, before: Record<string, string | number | boolean | null>, after: Record<string, string | number | boolean | null>, reason: string | null) => void;
}

const ADMINS: Role[] = ["ADMIN", "SUPERADMIN"];
const ADMIN_READ: Role[] = ["ADMIN", "SUPERADMIN", "SUPPORT", "ANALYST"];
const bad = (c: CfgCtx, pointer: string, code: string) => c.send(400, c.problem(400, "ERR_VALIDATION", { errors: [{ pointer, code }] }));
const blast = (value: number) => ({ zones: 6, routes: 5, outlets: 120 + value, devices: 9 });

function commit(c: CfgCtx, items: ValueRow[], kind: ConfigVersion["kind"], summary: string, risk: number, changeId: number | null, revertOf?: number): number {
  for (const it of items) {
    const row = c.store.values.find((v) => v.key === it.key && v.scope_type === it.scope_type && v.scope_id === it.scope_id);
    if (row) row.value = it.value;
    else c.store.values.push({ ...it });
  }
  const version = (c.store.versions[c.store.versions.length - 1]?.meta.version ?? 317) + 1;
  c.store.versions.push({ meta: { version, kind, committed_at: new Date().toISOString(), committed_by: c.userId, summary, max_risk_class: risk, ...(changeId ? { change_id: changeId } : {}), ...(revertOf ? { is_revert_of: revertOf } : {}) }, values: structuredClone(c.store.values) });
  return version;
}

const resolved = (v: ValueRow, version: number): ResolvedConfigValue => ({ key: v.key, value: v.value as ResolvedConfigValue["value"], scope_type: v.scope_type as ResolvedConfigValue["scope_type"], scope_id: v.scope_id, effective_from: null, config_version: version, requires_ack: false, bounds: KEYS.find((k) => k.key === v.key)?.bounds ?? null });

/** Resolve a key at a node: the most specific stored row, else global, else the registry default. */
function resolve(c: CfgCtx, key: string, nodeType: string, nodeId: number): ResolvedConfigValue {
  const rows = c.store.values.filter((v) => v.key === key);
  const exact = rows.find((v) => v.scope_type === nodeType && v.scope_id === nodeId) ?? rows.find((v) => v.scope_type === "global");
  const version = c.store.versions[c.store.versions.length - 1]!.meta.version;
  return exact ? resolved(exact, version) : { key, value: KEYS.find((k) => k.key === key)?.default_value ?? 0, scope_type: "default", scope_id: null, effective_from: null, config_version: version, requires_ack: false };
}

export async function handleConfig(c: CfgCtx): Promise<boolean> {
  const { method, path, url, store } = c;
  const q = url.searchParams;
  // Roles outside the admin read set fall through to the mock's own role gate (403), as before this service existed.
  if (path.startsWith("/v1/admin/") && !ADMIN_READ.includes(c.role)) return false;
  const lastVersion = () => store.versions[store.versions.length - 1]!;

  if (path === "/v1/admin/config/keys" && method === "GET") {
    const area = q.get("area");
    return c.send(200, { items: KEYS.filter((k) => !area || k.area === area) }), true;
  }
  if (path === "/v1/admin/config/versions" && method === "GET") {
    const lim = Number(q.get("limit") ?? 50);
    return c.send(200, { items: store.versions.map((v) => v.meta).reverse().slice(0, lim), next_cursor: null }), true;
  }
  const vd = /^\/v1\/admin\/config\/versions\/(\d+)$/.exec(path);
  if (vd && method === "GET") {
    const rec = store.versions.find((v) => v.meta.version === Number(vd[1]));
    if (!rec) return c.send(404, c.problem(404, "ERR_NOT_FOUND")), true;
    return c.send(200, { config_version: rec.meta.version, committed_at: rec.meta.committed_at, change_id: rec.meta.change_id ?? null, values: rec.values.map((v) => resolved(v, rec.meta.version)) }), true;
  }
  const rb = /^\/v1\/admin\/config\/versions\/(\d+)\/rollback$/.exec(path);
  if (rb && method === "POST") {
    if (!ADMINS.includes(c.role)) return c.send(403, c.problem(403, "ERR_FORBIDDEN")), true;
    const b = (await c.body()) as { mode?: string; reason?: string } | undefined;
    const rec = store.versions.find((v) => v.meta.version === Number(rb[1]));
    if (!rec) return c.send(404, c.problem(404, "ERR_NOT_FOUND")), true;
    if (!b?.reason || Array.from(b.reason).length < 10) return bad(c, "/reason", "too_short"), true;
    const values = structuredClone(rec.values);
    const changeId = store.nextChange++;
    const version = commit(c, values, "rollback", `Rollback to ${rec.meta.version}`, rec.meta.max_risk_class, changeId, rec.meta.version);
    const ch: ConfigChange = { change_id: changeId, status: "applied", risk_class: rec.meta.max_risk_class, changes: [], reason: b.reason, requested_by: c.userId, requested_at: new Date().toISOString(), approved_by: c.userId, approved_at: new Date().toISOString(), config_version: version, is_revert_of: rec.meta.version, blast_radius: blast(0) };
    store.changes.push(ch);
    c.audit("config", changeId, "config.rollback", { version: rec.meta.version }, { version }, b.reason);
    return c.send(201, ch), true;
  }

  if (path === "/v1/admin/config/changes" && method === "GET") {
    const status = q.get("status");
    const key = q.get("key");
    const items = store.changes.filter((x) => (!status || x.status === status) && (!key || x.changes.some((i) => i.key === key))).reverse();
    return c.send(200, { items, next_cursor: null }), true;
  }
  if (path === "/v1/admin/config/changes" && method === "POST") {
    if (!ADMINS.includes(c.role)) return c.send(403, c.problem(403, "ERR_FORBIDDEN")), true;
    const b = (await c.body()) as { reason?: string; changes?: { key: string; scope_type: string; scope_id: number; value: Json | null }[] } | undefined;
    if (!b || !Array.isArray(b.changes) || b.changes.length < 1) return bad(c, "/changes", "required"), true;
    if (typeof b.reason !== "string" || Array.from(b.reason).length < 10) return bad(c, "/reason", "too_short"), true;
    let risk = 0;
    for (const [n, it] of b.changes.entries()) {
      const k = KEYS.find((x) => x.key === it.key);
      if (!k) return bad(c, `/changes/${n}/key`, "unknown_key"), true;
      if (typeof it.value === "number" && ((k.bounds.min != null && it.value < k.bounds.min) || (k.bounds.max != null && it.value > k.bounds.max))) return bad(c, `/changes/${n}/value`, "out_of_bounds"), true;
      risk = Math.max(risk, k.risk_class);
    }
    const old = (it: { key: string; scope_type: string; scope_id: number }) => resolve(c, it.key, it.scope_type, it.scope_id).value;
    const id = store.nextChange++;
    const base: ConfigChange = { change_id: id, status: "applied", risk_class: risk, changes: b.changes.map((it) => ({ ...(it as ConfigChange["changes"][number]), old_value: old(it) as never })), reason: b.reason, requested_by: c.userId, requested_at: new Date().toISOString(), blast_radius: blast(Number(b.changes[0]!.value) || 0) };
    if (risk >= 3) {
      base.status = "pending_approval";
      store.changes.push(base);
      c.audit("config", id, "config.change.request", {}, { risk }, b.reason);
      return c.send(202, base), true;
    }
    base.approved_by = c.userId;
    base.approved_at = base.requested_at;
    base.config_version = commit(c, b.changes.map((it) => ({ key: it.key, scope_type: it.scope_type, scope_id: it.scope_id, value: it.value as Json })), "change", `Change ${b.changes.map((i) => i.key).join(", ")}`, risk, id);
    store.changes.push(base);
    c.audit("config", id, "config.change.apply", {}, { version: base.config_version }, b.reason);
    return c.send(201, base), true;
  }
  const dec = /^\/v1\/admin\/config\/changes\/(\d+)\/decision$/.exec(path);
  if (dec && method === "POST") {
    if (!ADMINS.includes(c.role)) return c.send(403, c.problem(403, "ERR_FORBIDDEN")), true;
    const ch = store.changes.find((x) => x.change_id === Number(dec[1]));
    if (!ch) return c.send(404, c.problem(404, "ERR_NOT_FOUND")), true;
    const b = (await c.body()) as { decision?: string; note?: string } | undefined;
    if (ch.status !== "pending_approval") return c.send(409, c.problem(409, "ERR_CONFLICT")), true;
    if (!b?.decision || !["approve", "reject", "cancel", "adopt"].includes(b.decision)) return bad(c, "/decision", "invalid"), true;
    if (b.decision === "approve" && c.role !== "SUPERADMIN") return c.send(403, c.problem(403, "ERR_FORBIDDEN")), true;
    if (b.decision === "approve" && ch.requested_by === c.userId) return c.send(409, c.problem(409, "ERR_CFG_SELF_APPROVAL")), true;
    if (b.decision === "approve") {
      const pm = store.pendingMenus.get(ch.change_id);
      if (pm) store.matrix.find((r) => r.role === pm.role)!.menus = pm.menus;
      store.pendingMenus.delete(ch.change_id);
      ch.status = "applied";
      ch.approved_by = c.userId;
      ch.approved_at = new Date().toISOString();
      ch.config_version = commit(c, ch.changes.map((i) => ({ key: i.key, scope_type: i.scope_type, scope_id: i.scope_id, value: i.value as Json })), "change", `Approved change ${ch.change_id}`, ch.risk_class, ch.change_id);
    } else {
      ch.status = b.decision === "reject" ? "rejected" : "cancelled";
      store.pendingMenus.delete(ch.change_id);
    }
    c.audit("config", ch.change_id, `config.change.${b.decision}`, {}, { status: ch.status }, b.note ?? null);
    return c.send(200, ch), true;
  }

  if (path === "/v1/admin/config/resolve" && method === "GET") {
    return c.send(200, resolve(c, q.get("key") ?? "", q.get("node_type") ?? "global", Number(q.get("node_id") ?? 0))), true;
  }
  if (path === "/v1/admin/config/blast-radius" && method === "GET") return c.send(200, { zones: 6, routes: 5, outlets: 240, users: 12, devices: 9 }), true;
  if (path === "/v1/admin/config/density" && method === "GET") {
    return c.send(200, { as_of: new Date().toISOString(), radii_m: [50, 100, 200], rows: [{ node: { type: "territory", id: 334, code: "T-334", name: "Banani" }, outlets: 80, median_neighbours: [1, 3, 8], density_index_pct: 42 }] }), true;
  }
  if (path === "/v1/admin/config/calibration" && method === "GET") {
    return c.send(200, { as_of: new Date().toISOString(), rows: [{ territory_id: 334, geo_class: "urban", visits: 900, histogram: [{ upper_m: 25, visits: 500 }, { upper_m: 50, visits: 250 }, { upper_m: 100, visits: 120 }, { upper_m: 200, visits: 30 }], force_sale_pct: 3.2, suggested_radius_m: 80 }] }), true;
  }
  if (path === "/v1/admin/config/whatif" && method === "GET") {
    const value = Number(q.get("value"));
    const to_valid = Math.max(0, Math.round((value - 100) * 0.6));
    const to_invalid = Math.max(0, Math.round((100 - value) * 0.8));
    return c.send(200, { scope_type: q.get("scope_type") ?? "global", scope_id: Number(q.get("scope_id") ?? 0), value, days: Number(q.get("days") ?? 30), visits_evaluated: 900, to_valid, to_invalid, unchanged: 900 - to_valid - to_invalid }), true;
  }
  const rc = /^\/v1\/admin\/config\/reach\/(\d+)(\/pending)?$/.exec(path);
  if (rc && method === "GET") {
    if (rc[2]) return c.send(200, { items: [], next_cursor: null }), true;
    return c.send(200, { version: Number(rc[1]), committed_at: lastVersion().meta.committed_at, devices_targeted: 8, devices_applied: 8, devices_acked: 6, devices_pending: 2, p95_reach_min: 14, by_zone: [] }), true;
  }

  if (path === "/v1/admin/permissions" && method === "GET") {
    return c.send(200, { config_version: lastVersion().meta.version, roles: store.matrix, admin_roster: [{ user_id: 3001, username: "admin1", role: "ADMIN", mfa_enabled: true }, { user_id: 3003, username: "super1", role: "SUPERADMIN", mfa_enabled: true }] } satisfies PermissionMatrix), true;
  }
  const pr = /^\/v1\/admin\/permissions\/roles\/([A-Z]+)$/.exec(path);
  if (pr && method === "PUT") {
    if (c.role !== "SUPERADMIN") return c.send(403, c.problem(403, "ERR_FORBIDDEN")), true;
    const b = (await c.body()) as { menus?: { menu_id: string; actions: string[] }[]; reason?: string } | undefined;
    if (!b || !Array.isArray(b.menus)) return bad(c, "/menus", "required"), true;
    if (typeof b.reason !== "string" || Array.from(b.reason).length < 10) return bad(c, "/reason", "too_short"), true;
    const row = store.matrix.find((r) => r.role === pr[1]);
    if (!row) return c.send(404, c.problem(404, "ERR_NOT_FOUND")), true;
    const id = store.nextChange++;
    store.pendingMenus.set(id, { role: pr[1]!, menus: b.menus as typeof row.menus });
    const ch: ConfigChange = { change_id: id, status: "pending_approval", risk_class: 3, changes: [], reason: b.reason, requested_by: c.userId, requested_at: new Date().toISOString(), blast_radius: blast(0) };
    store.changes.push(ch);
    c.audit("permissions", pr[1]!, "permissions.put", {}, { menus: b.menus.length }, b.reason);
    return c.send(202, ch), true;
  }

  if (path === "/v1/admin/device-otps" && method === "GET") {
    const zone = Number(q.get("zone_id") ?? 0);
    return c.send(200, { items: store.otps.filter((o) => o.zone_id === zone), next_cursor: null }), true;
  }
  if (path === "/v1/admin/device-otps" && method === "POST") {
    const b = (await c.body()) as { user_id?: number; reason?: string } | undefined;
    if (!b?.user_id) return bad(c, "/user_id", "required"), true;
    if (typeof b.reason !== "string" || Array.from(b.reason).length < 10) return bad(c, "/reason", "too_short"), true;
    const o: DeviceOtp = { ...store.otps[0]!, user_id: b.user_id, otp: String(100000 + store.otps.length * 7919).slice(0, 6), created_at: new Date().toISOString(), expires_at: new Date(Date.now() + 600_000).toISOString() };
    store.otps.push(o);
    return c.send(201, o), true;
  }

  if (path === "/v1/web-entry/route-day") {
    if (method === "GET") {
      const key = `${q.get("route_id")}|${q.get("business_date")}`;
      return c.send(200, store.entries.get(key) ?? { route_id: Number(q.get("route_id")), business_date: q.get("business_date")!, lines: [], successful_calls: 0, target_outlets: 20, app_overlap: false, source: "web_entry", saved_by_user_id: null, saved_at: null } satisfies WebEntryRouteDay), true;
    }
    if (method === "POST") {
      const b = (await c.body()) as Partial<WebEntryRouteDay> & { client_uuid?: string; change_reason?: string } | undefined;
      if (!b?.route_id || !b.business_date || !Array.isArray(b.lines)) return bad(c, "/lines", "required"), true;
      const key = `${b.route_id}|${b.business_date}`;
      if (store.entries.has(key) && (typeof b.change_reason !== "string" || Array.from(b.change_reason).length < 10)) return bad(c, "/change_reason", "required"), true;
      const lines = b.lines.map((l) => ({ ...l, sale_qty_base: l.issue_qty_base - l.return_qty_base }));
      const entry: WebEntryRouteDay = { client_uuid: b.client_uuid ?? randomUUID(), route_id: b.route_id, business_date: b.business_date, lines, successful_calls: b.successful_calls ?? 0, target_outlets: 20, app_overlap: false, source: "web_entry", saved_by_user_id: c.userId, saved_at: new Date().toISOString() };
      store.entries.set(key, entry);
      return c.send(200, entry), true;
    }
  }
  return false;
}
