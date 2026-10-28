// tso-manual W1 (build contract docs/requests/tso-manual-build-2026-10.md s5 W1, step 2): the mock of the contract 1.10.0
// operations the web reads or writes beyond the support desk (mock/tickets.ts):
// - POST /v1/day/reopen (D-TSO-MAN-29): ADMIN and SUPERADMIN (cfg.day.reopen_roles default), reason 10..500, window 3 days
//   (cfg.day.reopen_window_days), idempotent by the optional client_uuid (same body: the first result; other body: 409).
// - GET /v1/me/notifications and POST /v1/me/notifications/read (D-TSO-MAN-26): own rows only, newest first, read up to an id.
// - POST /v1/dashboards/national/login-submit/users (D-TSO-MAN-12): computed from the seeded day of mock/seed.ts, so the drill
//   page (whose geo ids are the seed's) and this list agree; called from mock/national.ts.
import { createHash } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Role, Schemas } from "../src/contract/types";
import { GEO, ROUTES, routesInScope, type SeedRoute } from "./seed";
import type { Ctx } from "./tables";

type Inbox = Schemas["NotificationItem"];

export interface TsoManStore {
  /** client_uuid -> the canonical request and the first result of that reopen. */
  reopens: Map<string, { fingerprint: string; result: Schemas["DayReopenResult"] }>;
  /** zone-days ("<zone>:<date>") reopened and not submitted again: a second reopen is 409 ERR_DAY_NOT_FINAL_SUBMITTED. */
  reopened: Set<string>;
  inbox: Inbox[];
}

const DAY_MS = 86_400_000;
const dhakaToday = (now = Date.now()) => new Date(now + 6 * 3_600_000).toISOString().slice(0, 10);
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / DAY_MS);
const isDate = (s: unknown): s is string => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && new Date(`${s}T00:00:00Z`).toISOString().slice(0, 10) === s;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const chars = (s: unknown) => (typeof s === "string" ? Array.from(s.trim()).length : -1);
function canonical(v: unknown): string {
  if (Array.isArray(v)) return `[${v.map(canonical).join(",")}]`;
  if (v && typeof v === "object") return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canonical((v as Record<string, unknown>)[k])}`).join(",")}}`;
  return JSON.stringify(v);
}

/** The reopen window of the mock (cfg.day.reopen_window_days default). */
export const REOPEN_WINDOW_DAYS = 3;
/** Zones the mock knows (the seeded geography); a reopen of another zone is 404. */
const ZONE_IDS = new Set(GEO.filter((g) => g.level === "zone").map((g) => g.id));

export function freshTsoManStore(now = Date.now()): TsoManStore {
  const iso = (agoMs: number) => new Date(now - agoMs).toISOString();
  const yesterday = dhakaToday(now - DAY_MS);
  // tso334's bell (user 2001): a decided leave, a support reply and last night's auto Final Submit; the oldest is read.
  const inbox: Inbox[] = [
    { id: 11, kind: "leave_decided", ref_type: "leave_application", ref_id: "11111111-1111-4111-8111-111111111111", title_en: "Leave approved", title_bn: "ছুটি অনুমোদিত", body_en: "Your casual leave from 12 Oct is approved.", body_bn: "১২ অক্টোবর থেকে আপনার নৈমিত্তিক ছুটি অনুমোদিত হয়েছে।", created_at: iso(3 * DAY_MS), business_date: dhakaToday(now - 3 * DAY_MS), read_at: iso(2 * DAY_MS) },
    { id: 12, kind: "ticket_updated", ref_type: "support_ticket", ref_id: "7a1c0000-0000-4000-8000-000000000002", title_en: "Ticket INC100002 updated", title_bn: "টিকিট INC100002 হালনাগাদ", body_en: "Support replied to your ticket.", body_bn: "সাপোর্ট আপনার টিকিটে উত্তর দিয়েছে।", created_at: iso(5 * 3_600_000), business_date: dhakaToday(now - 5 * 3_600_000), read_at: null },
    { id: 13, kind: "final_submit_auto", ref_type: "zone_day", ref_id: `3341:${yesterday}`, title_en: "Zone auto-submitted", title_bn: "জোন স্বয়ংক্রিয়ভাবে সাবমিট হয়েছে", body_en: `Banani Zone 1 was final-submitted at midnight for ${yesterday}.`, body_bn: null, created_at: iso(2 * 3_600_000), business_date: dhakaToday(now - 2 * 3_600_000), read_at: null },
  ];
  return { reopens: new Map(), reopened: new Set(), inbox: inbox.map((i) => ({ ...i })) };
}

/** Owner of each seeded inbox row (the contract's NotificationItem has no user member). */
const INBOX_OWNER = 2001;

export interface TsoManCaller {
  user_id: number;
  role: Role;
}

/** Answers /v1/day/reopen and /v1/me/notifications/**; false for any other path. */
export async function handleTsoManual(s: TsoManStore, ctx: Ctx, me: TsoManCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown): true => {
    send(res, status, body);
    return true;
  };
  const invalid = (pointer: string, code = "invalid") => done(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer, code }] }));

  if (path === "/v1/day/reopen" && method === "POST") {
    if (me.role !== "ADMIN" && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN"));
    const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
    if (!b || typeof b !== "object" || Array.isArray(b)) return done(400, problem(400, "ERR_MALFORMED_JSON"));
    const extra = Object.keys(b).find((k) => !["zone_id", "business_date", "reason", "client_uuid"].includes(k));
    if (extra) return invalid(`/${extra}`, "unknown_member");
    if (typeof b.zone_id !== "number" || !Number.isSafeInteger(b.zone_id) || b.zone_id < 1) return invalid("/zone_id");
    if (!isDate(b.business_date)) return invalid("/business_date");
    const n = chars(b.reason);
    if (n < 10) return invalid("/reason", n < 0 ? "required" : "too_short");
    if (n > 500) return invalid("/reason", "too_long");
    if (b.client_uuid !== undefined && (typeof b.client_uuid !== "string" || !UUID_V4.test(b.client_uuid))) return invalid("/client_uuid");
    const print = createHash("sha256").update(`${me.user_id}|${canonical(b)}`).digest("hex");
    const uuid = b.client_uuid as string | undefined;
    if (uuid) {
      const prior = s.reopens.get(uuid);
      if (prior) return prior.fingerprint === print ? done(200, prior.result) : done(409, problem(409, "ERR_CONFLICT", { detail: "client_uuid was used by another reopen" }));
    }
    const age = daysBetween(b.business_date, dhakaToday());
    if (age < 0) return invalid("/business_date");
    if (age > REOPEN_WINDOW_DAYS) return invalid("/business_date", "window");
    if (!ZONE_IDS.has(b.zone_id)) return done(404, problem(404, "ERR_NOT_FOUND"));
    const key = `${b.zone_id}:${b.business_date}`;
    if (s.reopened.has(key)) return done(409, problem(409, "ERR_DAY_NOT_FINAL_SUBMITTED"));
    s.reopened.add(key);
    const result: Schemas["DayReopenResult"] = { zone_id: b.zone_id, business_date: b.business_date, reopened_at: new Date().toISOString(), reopened_by: me.user_id };
    if (uuid) s.reopens.set(uuid, { fingerprint: print, result });
    ctx.audit("zone_day", key, "zone_day.reopened", { final_submitted: true }, { final_submitted: false }, String(b.reason).trim());
    return done(200, result);
  }

  if (path === "/v1/me/notifications" && method === "GET") {
    const p = url.searchParams;
    const lim = p.get("limit");
    const before = p.get("before_id");
    if (lim !== null && (!/^\d{1,3}$/.test(lim) || Number(lim) < 1 || Number(lim) > 100)) return invalid("/limit");
    if (before !== null && (!/^\d{1,15}$/.test(before) || Number(before) < 1)) return invalid("/before_id");
    const mine = (me.user_id === INBOX_OWNER ? s.inbox : []).slice().sort((a, b) => b.id - a.id);
    const page = mine.filter((i) => before === null || i.id < Number(before));
    const items = page.slice(0, Number(lim ?? 50));
    const more = page.length > items.length;
    return done(200, { as_of: new Date().toISOString(), unread_count: mine.filter((i) => i.read_at === null).length, items, next_before_id: more ? items[items.length - 1]!.id : null } satisfies Schemas["NotificationInbox"]);
  }

  if (path === "/v1/me/notifications/read" && method === "POST") {
    const b = (await ctx.readJson(req)) as Record<string, unknown> | undefined;
    if (!b || typeof b !== "object" || Object.keys(b).some((k) => k !== "up_to_id")) return invalid("/up_to_id", "unknown_member");
    if (typeof b.up_to_id !== "number" || !Number.isSafeInteger(b.up_to_id) || b.up_to_id < 1) return invalid("/up_to_id");
    const mine = me.user_id === INBOX_OWNER ? s.inbox : [];
    const at = new Date().toISOString();
    for (const i of mine) if (i.id <= b.up_to_id && i.read_at === null) i.read_at = at; // idempotent: a repeat finds nothing unread
    return done(200, { unread_count: mine.filter((i) => i.read_at === null).length } satisfies Schemas["NotificationReadResult"]);
  }

  return false;
}

// ---------------------------------------------------------------------------------------------------------------------------
// POST /v1/dashboards/national/login-submit/users (D-TSO-MAN-12) over the seeded day.

const USERNAMES: Record<number, string> = { 1001: "sr334001" };
const usernameOf = (r: SeedRoute) => (r.user_id === null ? null : (USERNAMES[r.user_id] ?? `${r.kind}${r.user_id}`));
const zoneName = (id: number) => GEO.find((g) => g.level === "zone" && g.id === id)?.name ?? `Zone ${id}`;
/** Contract cap of one tab (NationalLoginSubmitUsers.items maxItems); the rest is cut and `truncated` is true. */
export const LOGIN_USERS_MAX = 2000;

/** The rows of one tab for a caller, narrowed by the filter ids of the request body; null when `tab` is not a tab. */
export function loginSubmitUsers(scope: { type: string; id: number }[], filter: Record<string, unknown> | null, tab: string | null): Pick<Schemas["NationalLoginSubmitUsers"], "tab" | "route_count" | "truncated" | "items"> | null {
  if (tab !== "not_logged_in" && tab !== "not_uploaded") return null;
  const ids = (k: string) => (Array.isArray(filter?.[k]) ? (filter![k] as unknown[]).filter((v): v is number => typeof v === "number") : []);
  const pick = (k: string, of: (r: SeedRoute) => number) => (rows: SeedRoute[]) => (ids(k).length ? rows.filter((r) => ids(k).includes(of(r))) : rows);
  let reach = routesInScope(scope);
  for (const f of [pick("wing_ids", (r) => r.wing_id), pick("division_ids", (r) => r.division_id), pick("territory_ids", (r) => r.territory_id), pick("zone_ids", (r) => r.zone_id)]) reach = f(reach);
  // Target routes are the normal SR route-days; an AMO's own route is not one (it only adds AMO rows below).
  const targets = reach.filter((r) => r.kind === "sr");
  const loggedIn = (r: SeedRoute) => r.state !== "not_started";
  const submitted = (r: SeedRoute) => r.state === "sales_submitted" || r.state === "final_submitted";
  const inTab = targets.filter((r) => (tab === "not_logged_in" ? !loggedIn(r) : loggedIn(r) && !submitted(r))).sort((a, b) => a.route_name.localeCompare(b.route_name) || a.route_id - b.route_id);
  // An active AMO covers the zone of its own route-day (the mock's AMO scope); AMO rows follow the route, never the AMO's login.
  const amos = ROUTES.filter((r) => r.kind === "amo" && r.user_id !== null);
  const items: Schemas["LoginSubmitUserRow"][] = inTab.flatMap((r) => {
    const base = { route_id: r.route_id, route_code: String(r.route_id), route_name: r.route_name, zone_id: r.zone_id, dep_name: zoneName(r.zone_id) };
    const sr: Schemas["LoginSubmitUserRow"] = { ...base, user_id: r.user_id, username: usernameOf(r), display_name: r.user_name, role: "SR" };
    const amoRows = amos
      .filter((a) => a.zone_id === r.zone_id)
      .map((a): Schemas["LoginSubmitUserRow"] => ({ ...base, user_id: a.user_id, username: usernameOf(a), display_name: a.user_name, role: "AMO" }))
      .sort((x, y) => (x.username ?? "").localeCompare(y.username ?? ""));
    return [sr, ...amoRows];
  });
  return { tab, route_count: inTab.length, truncated: items.length > LOGIN_USERS_MAX, items: items.slice(0, LOGIN_USERS_MAX) };
}
