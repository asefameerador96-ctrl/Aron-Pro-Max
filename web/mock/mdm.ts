// Mock of the MDM operations (D-MDM-01, contract 1.4.0 /v1/admin/mdm/*): devices, policies, commands (idempotent by command_id,
// factory reset needs a second approver), enrolment tokens, alerts and the activity log. In memory; reset with /__mock/reset.
import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Schemas } from "../src/contract/types";
import type { Ctx } from "./tables";

type Device = Schemas["MdmDevice"];
type Policy = Schemas["MdmPolicy"];
type Command = Schemas["MdmCommand"];
type Activity = Schemas["MdmActivityEntry"];
type Flag = Schemas["MdmComplianceFlag"];

interface DeviceRow extends Omit<Device, "pending_commands" | "policy_name"> {
  enrolled_at: string;
  trust_level: "high" | "normal" | "low" | "blocked";
  integrity_verdict: "pass" | "fail" | "unevaluated" | "stale";
}

export interface MdmStore {
  devices: DeviceRow[];
  policies: Policy[];
  commands: (Command & { _hash: string; _creator_id: number })[];
  activity: Activity[];
  tokens: Record<string, unknown>[];
  nextPolicy: number;
  nextToken: number;
}

const iso = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString();

export function freshMdmStore(): MdmStore {
  const dev = (id: number, users: string[], flavour: "sr" | "amo" | "tso", extra: Partial<DeviceRow>): DeviceRow => ({
    device_id: id,
    device_uuid: `00000000-0000-4000-8000-0000000000${String(id).padStart(2, "0")}`,
    flavour,
    status: "active",
    device_owner: true,
    users,
    model: "Samsung Galaxy A04",
    app_version: "1.2.0+120",
    latest_app_version: "1.2.0+120",
    last_contact_at: iso(12),
    battery_pct: 74,
    network: "mobile",
    policy_id: 1,
    compliance_flags: [],
    enrolled_at: iso(60 * 24 * 20),
    trust_level: "normal",
    integrity_verdict: "pass",
    ...extra,
  });
  const window = (days: number[], start: string, end: string) => ({ days, start, end });
  return {
    devices: [
      dev(1, ["sr334001"], "sr", {}),
      dev(2, ["sr334002"], "sr", { app_version: "1.1.0+110", compliance_flags: ["outdated_app"], battery_pct: 18, network: "wifi", policy_id: 2 }),
      dev(3, ["tso334"], "tso", { compliance_flags: ["mock_location", "developer_options"], integrity_verdict: "fail", trust_level: "low", model: "Xiaomi Redmi 9A" }),
      dev(4, ["sr335001"], "sr", { status: "suspended", device_owner: false, compliance_flags: ["not_device_owner", "stale_contact"], last_contact_at: iso(60 * 80), network: "offline", policy_id: null }),
    ],
    policies: [
      { policy_id: 1, name: "Field day", description: "Default for SR phones", allowed_packages: ["com.whatsapp", "com.google.android.apps.maps"], block_windows: [window([1, 2, 3, 4, 5, 6], "09:00", "18:00")], kiosk: { enabled: false }, location_sampling: { mode: "on_demand" }, version: 1, device_count: 2, updated_at: iso(60 * 48) },
      { policy_id: 2, name: "Strict kiosk", description: null, allowed_packages: [], block_windows: [window([1, 2, 3, 4, 5], "22:00", "06:00")], kiosk: { enabled: true, packages: ["com.aktcl.aron.sr"] }, location_sampling: { mode: "interval", interval_min: 30 }, version: 3, device_count: 1, updated_at: iso(60 * 5) },
    ],
    commands: [
      { command_id: "11111111-1111-4111-8111-111111111111", device_id: 2, type: "message", params: { text_en: "Please update the app" }, state: "acked", reason: "Update reminder", created_at: iso(300), created_by: "admin1", approved_by: null, approved_at: null, sent_at: iso(299), acked_at: iso(290), expires_at: iso(-60 * 24), result: {}, _hash: "seed", _creator_id: 3001 },
    ],
    activity: [
      { at: iso(300), kind: "command", device_id: 2, summary: "message acknowledged", actor: "admin1", reason: "Update reminder", ref: "11111111-1111-4111-8111-111111111111" },
      { at: iso(60 * 5), kind: "policy", device_id: 2, summary: "Policy Strict kiosk assigned", actor: "admin1", reason: "Test phone", ref: null },
      { at: iso(60 * 24 * 20), kind: "enrolment", device_id: 1, summary: "Enrolled as device owner", actor: null, reason: null, ref: null },
    ],
    tokens: [],
    nextPolicy: 3,
    nextToken: 1,
  };
}

const ROLES = ["ADMIN", "SUPERADMIN"];
const reasonOk = (r: unknown) => typeof r === "string" && Array.from(r.trim()).length >= 3 && Array.from(r).length <= 300;
const toDevice = (s: MdmStore, d: DeviceRow): Device => {
  const { enrolled_at: _e, trust_level: _t, integrity_verdict: _i, ...rest } = d;
  return { ...rest, policy_name: s.policies.find((p) => p.policy_id === d.policy_id)?.name ?? null, pending_commands: s.commands.filter((c) => c.device_id === d.device_id && ["queued", "sent", "pending_approval"].includes(c.state)).length };
};
const policyView = (s: MdmStore, p: Policy): Policy => ({ ...p, device_count: s.devices.filter((d) => d.policy_id === p.policy_id).length });
const pub = (c: Command & { _hash: string; _creator_id: number }): Command => {
  const { _hash: _h, _creator_id: _c, ...rest } = c;
  return rest;
};
const page = <T,>(items: T[], url: URL) => {
  const limit = Math.min(500, Math.max(1, Number(url.searchParams.get("limit") ?? 100)));
  const start = Number(url.searchParams.get("cursor") ?? 0) || 0;
  const slice = items.slice(start, start + limit);
  return { items: slice, next_cursor: start + limit < items.length ? String(start + limit) : null };
};

export async function handleMdm(s: MdmStore, ctx: Ctx, user: { role: string; username: string; user_id: number }, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  if (!path.startsWith("/v1/admin/mdm/")) return false;
  const send = ctx.send;
  const fail = (status: number, code: Parameters<Ctx["problem"]>[1], extra?: Parameters<Ctx["problem"]>[2]) => (send(res, status, ctx.problem(status, code, extra)), true);
  if (!ROLES.includes(user.role)) return fail(403, "ERR_FORBIDDEN");
  const q = url.searchParams;
  const sub = path.slice("/v1/admin/mdm/".length);

  if (sub === "devices" && method === "GET") {
    const flag = q.get("flag");
    const search = q.get("q")?.toLowerCase();
    const rows = s.devices
      .filter((d) => (!q.get("status") || d.status === q.get("status")) && (!q.get("flavour") || d.flavour === q.get("flavour")) && (!flag || d.compliance_flags.includes(flag as Flag)) && (!q.get("policy_id") || d.policy_id === Number(q.get("policy_id"))) && (!search || d.users.some((u) => u.toLowerCase().includes(search)) || d.device_uuid.includes(search)))
      .map((d) => toDevice(s, d));
    return send(res, 200, page(rows, url)), true;
  }

  const dev = /^devices\/(\d+)(?:\/(activity|commands|policy))?$/.exec(sub);
  if (dev) {
    const d = s.devices.find((x) => x.device_id === Number(dev[1]));
    if (!d) return fail(404, "ERR_NOT_FOUND");
    if (!dev[2] && method === "GET") {
      const pol = s.policies.find((p) => p.policy_id === d.policy_id);
      const detail: Schemas["MdmDeviceDetail"] = {
        device: toDevice(s, d),
        enrolled_at: d.enrolled_at,
        trust_level: d.trust_level,
        integrity_verdict: d.integrity_verdict,
        lockdown_level: "prod",
        zone_id: 6,
        last_report: d.last_contact_at
          ? {
              reported_at: d.last_contact_at,
              app_version: d.app_version ?? "1.0.0+1",
              device_info: { manufacturer: "Samsung", model: d.model ?? "SM-A045F", os_api_level: 33, os_version: "13", abi: "arm64-v8a", ram_mb: 3072 },
              device_owner: d.device_owner,
              lockdown_level_applied: "prod",
              policy_version_applied: 7,
              blocking_active: false,
              location_enabled: !d.compliance_flags.includes("location_off"),
              dev_options_enabled: d.compliance_flags.includes("developer_options"),
              adb_enabled: d.compliance_flags.includes("adb_enabled"),
              auto_time_enabled: true,
              mock_location_apps: d.compliance_flags.includes("mock_location") ? ["com.lexa.fakegps"] : [],
              pending_rows: 3,
              battery_pct: d.battery_pct ?? 50,
            }
          : null,
        policy: pol ? policyView(s, pol) : null,
        recent_commands: s.commands.filter((c) => c.device_id === d.device_id).slice(-20).reverse().map(pub),
      };
      return send(res, 200, detail), true;
    }
    if (dev[2] === "activity" && method === "GET") return send(res, 200, page(s.activity.filter((a) => a.device_id === d.device_id), url)), true;
    if (dev[2] === "commands" && method === "GET") return send(res, 200, page(s.commands.filter((c) => c.device_id === d.device_id).reverse().map(pub), url)), true;
    if (dev[2] === "commands" && method === "POST") {
      const b = (await ctx.readJson(req)) as { command_id?: string; type?: string; params?: Record<string, unknown>; reason?: string; ttl_h?: number } | undefined;
      const types = ["lock", "unlock", "message", "locate", "reboot", "factory_reset"];
      if (!b || typeof b.command_id !== "string" || !/^[0-9a-f-]{36}$/.test(b.command_id) || !types.includes(b.type ?? "") || !reasonOk(b.reason)) return fail(400, "ERR_VALIDATION");
      if (b.type === "message" && !b.params?.text_bn && !b.params?.text_en) return fail(400, "ERR_VALIDATION", { errors: [{ pointer: "/params", code: "required" }] });
      const hash = JSON.stringify([d.device_id, b.type, b.params ?? {}, b.reason]);
      const seen = s.commands.find((c) => c.command_id === b.command_id);
      if (seen) return seen._hash === hash ? (send(res, 200, pub(seen)), true) : fail(409, "ERR_CONFLICT");
      const now = new Date();
      const c = { command_id: b.command_id, device_id: d.device_id, type: b.type as Command["type"], params: b.params ?? {}, state: (b.type === "factory_reset" ? "pending_approval" : "queued") as Command["state"], reason: b.reason!.trim(), created_at: now.toISOString(), created_by: user.username, approved_by: null, approved_at: null, sent_at: null, acked_at: null, expires_at: new Date(now.getTime() + (b.ttl_h ?? 24) * 3_600_000).toISOString(), result: null, _hash: hash, _creator_id: user.user_id };
      s.commands.push(c);
      s.activity.unshift({ at: c.created_at, kind: "command", device_id: d.device_id, summary: `${c.type} ${c.state === "pending_approval" ? "waiting for approval" : "queued"}`, actor: user.username, reason: c.reason, ref: c.command_id });
      ctx.audit("device_command", c.command_id, `device_command.${c.type}`, {}, { device_id: d.device_id }, c.reason);
      return send(res, 201, pub(c)), true;
    }
    if (dev[2] === "policy" && method === "PUT") {
      const b = (await ctx.readJson(req)) as { policy_id?: number | null; reason?: string } | undefined;
      if (!b || !(b.policy_id === null || typeof b.policy_id === "number") || !reasonOk(b.reason)) return fail(400, "ERR_VALIDATION");
      if (b.policy_id !== null && !s.policies.some((p) => p.policy_id === b.policy_id)) return fail(400, "ERR_VALIDATION", { errors: [{ pointer: "/policy_id", code: "unknown" }] });
      d.policy_id = b.policy_id ?? null;
      s.activity.unshift({ at: new Date().toISOString(), kind: "policy", device_id: d.device_id, summary: b.policy_id === null ? "Policy cleared" : `Policy ${s.policies.find((p) => p.policy_id === b.policy_id)!.name} assigned`, actor: user.username, reason: b.reason!.trim(), ref: null });
      return handleMdm(s, ctx, user, "GET", new URL(`/v1/admin/mdm/devices/${d.device_id}`, url), req, res);
    }
  }

  const dec = /^commands\/([0-9a-f-]{36})\/decision$/.exec(sub);
  if (dec && method === "POST") {
    const c = s.commands.find((x) => x.command_id === dec[1]);
    if (!c) return fail(404, "ERR_NOT_FOUND");
    const b = (await ctx.readJson(req)) as { decision?: string; reason?: string } | undefined;
    if (!b || !["approve", "reject"].includes(b.decision ?? "") || !reasonOk(b.reason)) return fail(400, "ERR_VALIDATION");
    if (c.state !== "pending_approval" || c._creator_id === user.user_id) return fail(409, "ERR_CONFLICT");
    c.state = b.decision === "approve" ? "queued" : "rejected";
    c.approved_by = user.username;
    c.approved_at = new Date().toISOString();
    s.activity.unshift({ at: c.approved_at, kind: "command", device_id: c.device_id, summary: `${c.type} ${b.decision === "approve" ? "approved" : "rejected"}`, actor: user.username, reason: b.reason!.trim(), ref: c.command_id });
    return send(res, 200, pub(c)), true;
  }

  if (sub === "policies" && method === "GET") return send(res, 200, page(s.policies.map((p) => policyView(s, p)), url)), true;
  const writePolicy = async (existing: Policy | null) => {
    const b = (await ctx.readJson(req)) as Partial<Schemas["MdmPolicyWrite"]> | undefined;
    if (!b || typeof b.name !== "string" || Array.from(b.name.trim()).length < 2 || !Array.isArray(b.allowed_packages) || !Array.isArray(b.block_windows) || b.block_windows.length > 14 || !b.kiosk || !b.location_sampling || !reasonOk(b.reason)) return fail(400, "ERR_VALIDATION");
    if (s.policies.some((p) => p.name.toLowerCase() === b.name!.trim().toLowerCase() && p.policy_id !== existing?.policy_id)) return fail(409, "ERR_CONFLICT");
    if (existing && b.version !== existing.version) return fail(409, "ERR_CONFLICT");
    const next: Policy = { policy_id: existing?.policy_id ?? s.nextPolicy++, name: b.name.trim(), description: b.description ?? null, allowed_packages: b.allowed_packages, block_windows: b.block_windows, kiosk: b.kiosk, location_sampling: b.location_sampling, version: (existing?.version ?? 0) + 1, device_count: 0, updated_at: new Date().toISOString() };
    if (existing) s.policies[s.policies.indexOf(existing)] = next;
    else s.policies.push(next);
    s.activity.unshift({ at: next.updated_at, kind: "policy", device_id: null, summary: `Policy ${next.name} ${existing ? "updated" : "created"}`, actor: user.username, reason: b.reason!.trim(), ref: String(next.policy_id) });
    return send(res, existing ? 200 : 201, policyView(s, next)), true;
  };
  if (sub === "policies" && method === "POST") return writePolicy(null);
  const pol = /^policies\/(\d+)$/.exec(sub);
  if (pol) {
    const p = s.policies.find((x) => x.policy_id === Number(pol[1]));
    if (!p) return fail(404, "ERR_NOT_FOUND");
    if (method === "GET") return send(res, 200, policyView(s, p)), true;
    if (method === "PUT") return writePolicy(p);
  }

  if (sub === "enrolment-tokens" && method === "GET") return send(res, 200, page(s.tokens.filter((t) => !t.revoked_at), url)), true;
  if (sub === "enrolment-tokens" && method === "POST") {
    const b = (await ctx.readJson(req)) as { flavour?: string; lockdown_level?: string; max_uses?: number; expires_in_h?: number; note?: string } | undefined;
    if (!b || !["sr", "amo", "tso"].includes(b.flavour ?? "") || !["dev", "prod"].includes(b.lockdown_level ?? "") || !Number.isInteger(b.max_uses) || !Number.isInteger(b.expires_in_h)) return fail(400, "ERR_VALIDATION");
    const id = s.nextToken++;
    const secret = randomUUID().replace(/-/g, "") + randomUUID().replace(/-/g, "").slice(0, 16);
    const token = { token_id: id, token_prefix: secret.slice(0, 6), flavour: b.flavour, lockdown_level: b.lockdown_level, max_uses: b.max_uses, used_count: 0, zone_id: null, expires_at: new Date(Date.now() + b.expires_in_h! * 3_600_000).toISOString(), created_by: user.user_id, created_at: new Date().toISOString(), revoked_at: null, note: b.note ?? null };
    s.tokens.push(token);
    const payload = { "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": `com.aktcl.aron.${b.flavour}/com.aktcl.aron.dpc.AronDeviceAdminReceiver`, "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { "aron.enrolment_token": secret } };
    s.activity.unshift({ at: token.created_at, kind: "enrolment", device_id: null, summary: `Enrolment code ${token.token_prefix}… created`, actor: user.username, reason: null, ref: String(id) });
    return send(res, 201, { token, enrolment_token: secret, qr_payload: payload, qr_text: JSON.stringify(payload) }), true;
  }
  const rev = /^enrolment-tokens\/(\d+)\/revoke$/.exec(sub);
  if (rev && method === "POST") {
    const t = s.tokens.find((x) => x.token_id === Number(rev[1]));
    if (!t) return fail(404, "ERR_NOT_FOUND");
    t.revoked_at = new Date().toISOString();
    return send(res, 200, t), true;
  }

  if (sub === "alerts" && method === "GET") {
    const flag = q.get("flag");
    const rows: Schemas["MdmAlert"][] = s.devices.flatMap((d) => d.compliance_flags.filter((f) => !flag || f === flag).map((f) => ({ device_id: d.device_id, device_uuid: d.device_uuid, users: d.users, flavour: d.flavour, flag: f, since: d.last_contact_at ?? d.enrolled_at, detail: null })));
    return send(res, 200, page(rows, url)), true;
  }
  if (sub === "activity" && method === "GET") return send(res, 200, page(s.activity, url)), true;

  return fail(404, "ERR_NOT_FOUND");
}
