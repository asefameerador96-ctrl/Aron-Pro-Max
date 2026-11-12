// Mock of the loyalty-programme API: the contract 1.12.2 operations (listProgrammes, createProgramme, updateProgramme,
// listProgrammeEnrolments, putProgrammeEnrolments, listGifts, createGift, updateGift) with the members the brief's API sketch adds for
// 1.13.0 (ASSUMED, D-LOY-WEB-01): status draft/active/inactive, tiers, channels, request_uuid, and under /v1/admin/loyalty the versions,
// status and progress operations. In memory; reset with /__mock/reset. Rules it follows: SUPERADMIN writes and is the only one who
// activates a draft or changes a gift; an ADMIN reads with `r`, writes with `w` on module `loyalty` (modelled per username below);
// other roles 403. Create makes a draft (version 1); a code that exists is 409; a repeat of request_uuid with the same body replays and
// a different body is 409. Update is draft only (409 otherwise), needs a quoted If-Match (a bad one 412) and appends a version.
import { createHash } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Role } from "../src/contract/types";
import type { Ctx } from "./tables";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PCODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;
const TCODE = /^[a-z][a-z0-9_]{1,40}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const DEC3 = /^\d{1,13}(\.\d{1,3})?$/;
const KINDS = ["astha", "diamond_league", "superstar", "campaign"];
const CHANNELS = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"];

interface Tier { tier_code: string; label_en: string; label_bn: string | null; threshold_value: string | null; threshold_basis: "std" }
interface Version { programme_version_id: number; programme_id: number; version_no: number; is_current: boolean; name_en: string; name_bn: string | null; period_label: string | null; active_from: string; active_to: string; tiers: Tier[]; channels: string[]; change_reason: string | null; created_at: string }
interface Row { id: number; kind: string; code: string; status: "draft" | "active" | "inactive"; version: number; created_at: string; updated_at: string; versions: Version[] }
interface Enrol { programme_id: number; outlet_id: number; league_label: string | null; tier_code: string | null; base_target: string | null; valid_from: string; valid_to: string | null }
interface Gift { gift_id: number; programme_id: number; code: string; name_en: string; name_bn: string | null; points_cost: number | null; tier_codes: string[]; image_url: string | null; status: "active" | "inactive"; version: number }
export interface LoyaltyStore { rows: Row[]; enrolments: Enrol[]; gifts: Gift[]; achieved: Map<string, string>; nextId: number; nextVersionId: number; nextGiftId: number; ops: Map<string, { fp: string; status: number; body: unknown }> }

const dhakaToday = (): string => new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);
const fp = (o: unknown): string => createHash("sha256").update(JSON.stringify(o)).digest("hex");
const chars = (s: string): number => Array.from(s.trim()).length;
const t = (tier_code: string, label_en: string, label_bn: string | null, threshold_value: string | null): Tier => ({ tier_code, label_en, label_bn, threshold_value, threshold_basis: "std" });

function version(id: number, pid: number, no: number, w: Partial<Version> & Pick<Version, "name_en" | "active_from" | "active_to">, reason: string, at: string): Version {
  return { programme_version_id: id, programme_id: pid, version_no: no, is_current: true, name_bn: null, period_label: null, tiers: [], channels: [], change_reason: reason, created_at: at, ...w };
}

export function freshLoyaltyStore(): LoyaltyStore {
  const s: LoyaltyStore = { rows: [], enrolments: [], gifts: [], achieved: new Map(), nextId: 1, nextVersionId: 1, nextGiftId: 1, ops: new Map() };
  const seed = (kind: string, code: string, status: Row["status"], w: Parameters<typeof version>[3], at: string) => {
    const id = s.nextId++;
    s.rows.push({ id, kind, code, status, version: 1, created_at: at, updated_at: at, versions: [version(s.nextVersionId++, id, 1, w, "Seed programme for the portal mock", at)] });
    return id;
  };
  const astha = seed("astha", "ASTHA-Q4", "active", { name_en: "Astha 2026 Q4", name_bn: "আস্থা ২০২৬ চতুর্থ ত্রৈমাসিক", period_label: "2026-Q4", active_from: "2026-10-01", active_to: "2099-12-31", channels: ["Astha"], tiers: [t("platinum", "Platinum", "প্ল্যাটিনাম", "5000.000"), t("gold", "Gold", "গোল্ড", "3000.500"), t("silver", "Silver", "সিলভার", null)] }, "2026-09-28T05:00:00.000Z");
  const dl = seed("diamond_league", "DL-OCT", "active", { name_en: "Diamond League October", name_bn: "ডায়মন্ড লিগ অক্টোবর", period_label: "2026-10", active_from: "2026-10-01", active_to: "2099-12-31", channels: ["GT", "DCC"] }, "2026-09-30T05:00:00.000Z");
  seed("superstar", "SS-NOV", "draft", { name_en: "Superstar November", name_bn: "সুপারস্টার নভেম্বর মাসের বিশেষ কর্মসূচি, ঢাকা উইংয়ের সকল পরিবেশক ও খুচরা বিক্রেতার জন্য প্রযোজ্য একটি দীর্ঘ নাম", period_label: "2026-11", active_from: "2026-11-15", active_to: "2099-12-31" }, "2026-11-02T05:00:00.000Z");
  seed("astha", "ASTHA-Q3", "inactive", { name_en: "Astha 2026 Q3", period_label: "2026-Q3", active_from: "2026-07-01", active_to: "2026-09-30", channels: ["Astha"], tiers: [t("gold", "Gold", null, "2500.000")] }, "2026-06-28T05:00:00.000Z");
  s.enrolments.push(
    { programme_id: astha, outlet_id: 101, league_label: null, tier_code: "platinum", base_target: "6000.000", valid_from: "2026-10-01", valid_to: null },
    { programme_id: astha, outlet_id: 102, league_label: null, tier_code: "gold", base_target: "3500.250", valid_from: "2026-10-01", valid_to: null },
    { programme_id: astha, outlet_id: 103, league_label: null, tier_code: "silver", base_target: "0.000", valid_from: "2026-10-01", valid_to: null }, // a zero target: its percent is guarded
  );
  s.achieved.set(`${astha}:platinum`, "3000.000");
  s.achieved.set(`${astha}:gold`, "1750.125");
  s.achieved.set(`${astha}:silver`, "400.000");
  const gift = (programme_id: number, code: string, name_en: string, name_bn: string, points_cost: number | null, tier_codes: string[]) => s.gifts.push({ gift_id: s.nextGiftId++, programme_id, code, name_en, name_bn, points_cost, tier_codes, image_url: null, status: "active", version: 1 });
  gift(dl, "kitchen_rack", "Kitchen rack", "রান্নাঘরের র‍্যাক", 200, []);
  gift(dl, "chair", "Chair", "চেয়ার", 200, []);
  gift(dl, "tornado_fan", "Tornado fan", "টর্নেডো ফ্যান", 400, []);
  gift(astha, "rice_cooker", "Rice cooker", "রাইস কুকার", null, ["platinum", "gold"]);
  return s;
}

export interface LoyaltyCaller { role: Role; username: string }
/** r / w / none of the caller on module `loyalty` (mloy1 is the ADMIN with a read grant; mgrant1 and mdisc1 have no grant on it). */
export function loyaltyAccess(c: LoyaltyCaller): "none" | "r" | "w" {
  if (c.role === "SUPERADMIN") return "w";
  if (c.role !== "ADMIN") return "none";
  return c.username === "mgrant1" || c.username === "mdisc1" ? "none" : c.username === "mloy1" ? "r" : "w";
}

type Body = Record<string, unknown>;
type Err = { pointer: string; code: string };

function view(r: Row) {
  const v = r.versions[0]!;
  return { programme_id: r.id, kind: r.kind, code: r.code, name_en: v.name_en, name_bn: v.name_bn, period_label: v.period_label, active_from: v.active_from, active_to: v.active_to, points_expire_on: null, rules: {}, status: r.status, version: r.version, version_no: v.version_no, tiers: v.tiers, channels: v.channels, change_reason: v.change_reason, created_at: r.created_at, updated_at: r.updated_at };
}

function programmeErrors(b: Body): Err[] {
  const e: Err[] = [];
  const bad = (pointer: string, code = "invalid") => e.push({ pointer: `/${pointer}`, code });
  if (typeof b.kind !== "string" || !KINDS.includes(b.kind)) bad("kind");
  if (typeof b.code !== "string" || !PCODE.test(b.code)) bad("code");
  if (typeof b.name_en !== "string" || !b.name_en.trim() || chars(b.name_en) > 120) bad("name_en");
  if (b.name_bn != null && (typeof b.name_bn !== "string" || chars(b.name_bn) > 120)) bad("name_bn");
  if (b.period_label != null && (typeof b.period_label !== "string" || chars(b.period_label) > 40)) bad("period_label");
  for (const k of ["active_from", "active_to"]) if (typeof b[k] !== "string" || !DAY.test(b[k] as string)) bad(k);
  if (typeof b.active_from === "string" && typeof b.active_to === "string" && b.active_to < b.active_from) bad("active_to", "range");
  if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) bad("change_reason");
  if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) bad("request_uuid", "required");
  if (!Array.isArray(b.channels) || b.channels.some((c) => !CHANNELS.includes(String(c)))) bad("channels");
  if (!Array.isArray(b.tiers) || b.tiers.length > 20) bad("tiers");
  else {
    const seen = new Set<string>();
    let last = -1n;
    (b.tiers as Body[]).forEach((x, i) => {
      if (typeof x.tier_code !== "string" || !TCODE.test(x.tier_code) || seen.has(x.tier_code)) bad(`tiers/${i}/tier_code`);
      else seen.add(x.tier_code);
      if (typeof x.label_en !== "string" || !x.label_en.trim()) bad(`tiers/${i}/label_en`);
      if (x.threshold_value != null) {
        if (typeof x.threshold_value !== "string" || !DEC3.test(x.threshold_value)) bad(`tiers/${i}/threshold_value`);
        else {
          const [w, f = ""] = x.threshold_value.split(".");
          const v = BigInt(w!) * 1000n + BigInt(f.padEnd(3, "0"));
          if (v <= last) bad(`tiers/${i}/threshold_value`, "not_rising");
          last = v;
        }
      }
    });
  }
  return e;
}

export async function handleLoyalty(s: LoyaltyStore, ctx: Ctx, me: LoyaltyCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const m = /^\/v1\/admin\/(?:programmes(?:\/(\d+))?(?:\/(enrolments))?|loyalty\/programmes\/(\d+)\/(versions|status|progress)|gifts(?:\/(\d+))?)$/.exec(path);
  if (!m) return false;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown, headers?: Record<string, string>): true => { send(res, status, body, headers); return true; };
  const isGift = path.startsWith("/v1/admin/gifts");
  const access = loyaltyAccess(me);
  const isRead = method === "GET";
  if (access === "none" || (!isRead && access !== "w")) return done(403, problem(403, "ERR_FORBIDDEN"));
  if (!isRead && isGift && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN"));
  const validation = (errors: Err[]) => done(400, problem(400, "ERR_VALIDATION", { errors } as never));
  const pid = m[1] ? Number(m[1]) : m[3] ? Number(m[3]) : null;
  const sub = m[2] ?? m[4] ?? null;
  const row = pid === null ? null : s.rows.find((r) => r.id === pid) ?? null;

  // ----- gifts
  if (isGift) {
    const gid = m[5] ? Number(m[5]) : null;
    if (gid === null && method === "GET") {
      const f = url.searchParams.get("programme_id");
      return done(200, { items: s.gifts.filter((g) => !f || g.programme_id === Number(f)) });
    }
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs: Err[] = [];
    if (typeof b.programme_id !== "number" || !s.rows.some((r) => r.id === b.programme_id)) errs.push({ pointer: "/programme_id", code: "invalid" });
    if (typeof b.code !== "string" || !TCODE.test(b.code)) errs.push({ pointer: "/code", code: "invalid" });
    if (typeof b.name_en !== "string" || !b.name_en.trim() || chars(b.name_en) > 120) errs.push({ pointer: "/name_en", code: "invalid" });
    if (b.points_cost != null && (!Number.isInteger(b.points_cost) || (b.points_cost as number) < 1 || (b.points_cost as number) > 100_000)) errs.push({ pointer: "/points_cost", code: "invalid" });
    if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) errs.push({ pointer: "/change_reason", code: "invalid" });
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "/request_uuid", code: "required" });
    if (!["active", "inactive"].includes(String(b.status ?? "active"))) errs.push({ pointer: "/status", code: "invalid" });
    if (errs.length) return validation(errs);
    const key = `gift:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined, gid });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    if (gid === null && method === "POST") {
      if (s.gifts.some((g) => g.code === b.code)) return done(409, problem(409, "ERR_CONFLICT"));
      const g: Gift = { gift_id: s.nextGiftId++, programme_id: b.programme_id as number, code: b.code as string, name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, points_cost: (b.points_cost as number | null) ?? null, tier_codes: (b.tier_codes as string[]) ?? [], image_url: null, status: (b.status as Gift["status"]) ?? "active", version: 1 };
      s.gifts.push(g);
      ctx.audit("gift", g.gift_id, "create", {}, { code: g.code }, String(b.change_reason));
      s.ops.set(key, { fp: print, status: 201, body: g });
      return done(201, g);
    }
    const g = s.gifts.find((x) => x.gift_id === gid);
    if (!g || method !== "PATCH") return done(g ? 405 : 404, problem((g ? 405 : 404) as never, "ERR_NOT_FOUND"));
    const ifMatch = req.headers["if-match"];
    if (typeof ifMatch !== "string" || !/^"[0-9]{1,10}"$/.test(ifMatch)) return done(400, problem(400, "ERR_VALIDATION"));
    if (ifMatch !== `"${g.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    Object.assign(g, { name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, points_cost: (b.points_cost as number | null) ?? null, tier_codes: (b.tier_codes as string[]) ?? [], status: (b.status as Gift["status"]) ?? g.status, version: g.version + 1 });
    ctx.audit("gift", g.gift_id, "update", {}, { code: g.code }, String(b.change_reason));
    s.ops.set(key, { fp: print, status: 200, body: g });
    return done(200, g);
  }

  // ----- programmes
  if (pid === null && method === "GET") return done(200, { items: [...s.rows].reverse().map(view) });

  if (pid === null && method === "POST") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs = programmeErrors(b);
    if (errs.length) return validation(errs);
    const key = `create:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    if (s.rows.some((r) => r.code === b.code)) return done(409, problem(409, "ERR_CONFLICT"));
    const at = new Date().toISOString();
    const id = s.nextId++;
    const r: Row = { id, kind: String(b.kind), code: String(b.code), status: "draft", version: 1, created_at: at, updated_at: at, versions: [] };
    r.versions.push(version(s.nextVersionId++, id, 1, { name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, period_label: (b.period_label as string | null) ?? null, active_from: String(b.active_from), active_to: String(b.active_to), tiers: b.tiers as Tier[], channels: b.channels as string[] }, String(b.change_reason), at));
    s.rows.push(r);
    ctx.audit("programme", id, "create", {}, { code: r.code, status: "draft" }, String(b.change_reason));
    const body = view(r);
    s.ops.set(key, { fp: print, status: 201, body });
    return done(201, body);
  }

  if (!row) return done(404, problem(404, "ERR_NOT_FOUND"));

  if (sub === "versions" && method === "GET") return done(200, { items: row.versions.slice(0, Number(url.searchParams.get("limit") ?? 50)) });

  if (sub === "progress" && method === "GET") {
    const v = row.versions[0]!;
    const tiers = v.tiers.map((tr) => {
      const rows = s.enrolments.filter((e) => e.programme_id === row.id && e.tier_code === tr.tier_code);
      const target = rows.reduce((a, e) => { const [w, f = ""] = (e.base_target ?? "0").split("."); return a + BigInt(w!) * 1000n + BigInt(f.padEnd(3, "0")); }, 0n);
      return { tier_code: tr.tier_code, enrolled_outlets: rows.length, target_std: `${target / 1000n}.${(target % 1000n).toString().padStart(3, "0")}`, achieved_std: s.achieved.get(`${row.id}:${tr.tier_code}`) ?? "0.000" };
    });
    const points = row.code === "DL-OCT" ? { earned: 1200, redeemed: 400, expiring: 150 } : { earned: 0, redeemed: 0, expiring: 0 };
    return done(200, { tiers, points, cash_rate_mtk_per_point: 2000 });
  }

  if (sub === "enrolments" && method === "GET") {
    const limit = Number(url.searchParams.get("limit") ?? 50);
    return done(200, { items: s.enrolments.filter((e) => e.programme_id === row.id).slice(0, limit), next_cursor: null });
  }

  if (sub === "enrolments" && method === "PUT") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs: Err[] = [];
    if (typeof b.batch_uuid !== "string" || !UUID.test(b.batch_uuid)) errs.push({ pointer: "/batch_uuid", code: "required" });
    if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) errs.push({ pointer: "/change_reason", code: "invalid" });
    const rows = Array.isArray(b.rows) ? (b.rows as Body[]) : null;
    if (!rows || rows.length < 1) errs.push({ pointer: "/rows", code: "invalid" });
    else rows.forEach((x, i) => {
      if (x.programme_id !== row.id) errs.push({ pointer: `/rows/${i}/programme_id`, code: "invalid" });
      if (!Number.isInteger(x.outlet_id) || (x.outlet_id as number) < 1) errs.push({ pointer: `/rows/${i}/outlet_id`, code: "invalid" });
      if (x.base_target != null && (typeof x.base_target !== "string" || !DEC3.test(x.base_target))) errs.push({ pointer: `/rows/${i}/base_target`, code: "invalid" }); // never negative
      if (typeof x.valid_from !== "string" || !DAY.test(x.valid_from)) errs.push({ pointer: `/rows/${i}/valid_from`, code: "invalid" });
      if (x.tier_code != null && !row.versions[0]!.tiers.some((tr) => tr.tier_code === x.tier_code)) errs.push({ pointer: `/rows/${i}/tier_code`, code: "invalid" });
    });
    if (errs.length) return validation(errs);
    const key = `enrol:${row.id}:${b.batch_uuid as string}`;
    const print = fp({ ...b, batch_uuid: undefined });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    if (row.status === "inactive") return done(409, problem(409, "ERR_CONFLICT"));
    let inserted = 0, updated = 0, unchanged = 0;
    const staged = [...s.enrolments];
    for (const x of rows!) {
      const e: Enrol = { programme_id: row.id, outlet_id: x.outlet_id as number, league_label: (x.league_label as string | null) ?? null, tier_code: (x.tier_code as string | null) ?? null, base_target: (x.base_target as string | null) ?? null, valid_from: String(x.valid_from), valid_to: (x.valid_to as string | null) ?? null };
      const same = staged.findIndex((o) => o.programme_id === row.id && o.outlet_id === e.outlet_id && o.valid_from === e.valid_from);
      const overlap = staged.some((o, i) => i !== same && o.programme_id === row.id && o.outlet_id === e.outlet_id && o.valid_from <= (e.valid_to ?? "9999-12-31") && e.valid_from <= (o.valid_to ?? "9999-12-31"));
      if (overlap) return done(409, problem(409, "ERR_CONFLICT")); // the gist EXCLUDE: no overlapping enrolment per programme and outlet
      if (same >= 0) { if (fp(staged[same]) === fp(e)) unchanged++; else { staged[same] = e; updated++; } } else { staged.push(e); inserted++; }
    }
    s.enrolments.splice(0, s.enrolments.length, ...staged);
    ctx.audit("programme", row.id, "enrol", {}, { inserted, updated }, String(b.change_reason));
    const body = { batch_uuid: b.batch_uuid, inserted, updated, unchanged };
    s.ops.set(key, { fp: print, status: 200, body });
    return done(200, body);
  }

  if (sub === null && method === "PATCH") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs = programmeErrors(b);
    if (errs.length) return validation(errs);
    const key = `update:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined, id: row.id });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    const ifMatch = req.headers["if-match"];
    if (typeof ifMatch !== "string" || !/^"[0-9]{1,10}"$/.test(ifMatch)) return done(400, problem(400, "ERR_VALIDATION"));
    if (ifMatch !== `"${row.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    if (row.status !== "draft" || b.code !== row.code) return done(409, problem(409, "ERR_CONFLICT"));
    const cur = row.versions[0]!;
    const next = version(s.nextVersionId++, row.id, cur.version_no + 1, { name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, period_label: (b.period_label as string | null) ?? null, active_from: String(b.active_from), active_to: String(b.active_to), tiers: b.tiers as Tier[], channels: b.channels as string[] }, String(b.change_reason), new Date().toISOString());
    cur.is_current = false;
    row.versions.unshift(next);
    row.version += 1;
    row.updated_at = next.created_at;
    ctx.audit("programme", row.id, "new_version", { version_no: cur.version_no }, { version_no: next.version_no }, next.change_reason);
    const body = view(row);
    s.ops.set(key, { fp: print, status: 200, body });
    return done(200, body);
  }

  if (sub === "status" && method === "POST") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs: Err[] = [];
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "/request_uuid", code: "required" });
    if (b.to_status !== "active" && b.to_status !== "inactive") errs.push({ pointer: "/to_status", code: "invalid" });
    if (typeof b.reason !== "string" || chars(b.reason) < 10 || chars(b.reason) > 500) errs.push({ pointer: "/reason", code: "invalid" });
    if (errs.length) return validation(errs);
    const key = `status:${b.request_uuid as string}`;
    const print = fp({ id: row.id, to: b.to_status, reason: b.reason });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    const to = b.to_status as "active" | "inactive";
    const allowed = (row.status === "draft" && to === "active") || (row.status === "active" && to === "inactive");
    if (!allowed) return done(409, problem(409, "ERR_CONFLICT"));
    if (to === "active" && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN"));
    if (to === "active" && row.versions[0]!.active_to < dhakaToday()) return validation([{ pointer: "/active_to", code: "past" }]);
    const from = row.status;
    row.status = to;
    row.version += 1;
    row.updated_at = new Date().toISOString();
    ctx.audit("programme", row.id, to === "active" ? "activate" : "end", { status: from }, { status: to }, String(b.reason));
    const body = view(row);
    s.ops.set(key, { fp: print, status: 200, body });
    return done(200, body);
  }
  return done(405, problem(405 as never, "ERR_NOT_FOUND"));
}
