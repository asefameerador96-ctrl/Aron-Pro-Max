// Mock of the loyalty-programme API, shaped by contract 1.13.0 (portal-loyalty-web, D-LOY-WEB-03): listProgrammes, createProgramme,
// getProgramme, updateProgramme, listProgrammeVersions, setProgrammeStatus, listProgrammeEnrolments, putProgrammeEnrolments, listGifts,
// createGift, getGift, updateGift. There is no progress operation in 1.13.0, so the mock has none. In memory; reset with /__mock/reset.
// Rules it follows: SUPERADMIN writes and is the only one who activates a draft or changes a gift; an ADMIN reads with `r`, writes
// with `w` on module `loyalty` (modelled per username below); other roles 403. Bodies are closed (an unknown member is 400). Create
// makes a draft (version 1) and refuses `status`; a code that exists is 409; a repeat of request_uuid with the same body replays and a
// different body is 409. Update is draft only (409 otherwise), needs a quoted If-Match (a bad one 412), never takes kind or code, keeps
// the members left out and appends a version.
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

interface Tier { tier_code: string; sort_order: number; label_en: string; label_bn: string | null; threshold: string | null; threshold_basis: "std" }
interface Node { node_type: string; node_id: number }
interface Ref { product_level: string; product_id: number }
interface Version { programme_version_id: number; programme_id: number; version_no: number; is_current: boolean; name_en: string; name_bn: string | null; period_label: string | null; active_from: string; active_to: string; tiers: Tier[]; qualifying: Ref[]; scope: Node[]; change_reason: string | null; created_at: string }
interface Row { id: number; kind: string; code: string; status: "draft" | "active" | "inactive"; version: number; created_at: string; updated_at: string; versions: Version[] }
interface Enrol { programme_id: number; outlet_id: number; league_label: string | null; tier_code: string | null; base_target: string | null; valid_from: string; valid_to: string | null }
interface Gift { gift_id: number; programme_id: number; code: string; name_en: string; name_bn: string | null; points_cost: number | null; tier_codes: string[]; image_url: string | null; status: "active" | "inactive"; version: number }
export interface LoyaltyStore { rows: Row[]; enrolments: Enrol[]; gifts: Gift[]; achieved: Map<string, string>; nextId: number; nextVersionId: number; nextGiftId: number; ops: Map<string, { fp: string; status: number; body: unknown }> }

const dhakaToday = (): string => new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);
const fp = (o: unknown): string => createHash("sha256").update(JSON.stringify(o)).digest("hex");
const chars = (s: string): number => Array.from(s.trim()).length;
const t = (tier_code: string, sort_order: number, label_en: string, label_bn: string | null, threshold: string | null): Tier => ({ tier_code, sort_order, label_en, label_bn, threshold, threshold_basis: "std" });

function version(id: number, pid: number, no: number, w: Partial<Version> & Pick<Version, "name_en" | "active_from" | "active_to">, reason: string, at: string): Version {
  return { programme_version_id: id, programme_id: pid, version_no: no, is_current: true, name_bn: null, period_label: null, tiers: [], qualifying: [], scope: [], change_reason: reason, created_at: at, ...w };
}

export function freshLoyaltyStore(): LoyaltyStore {
  const s: LoyaltyStore = { rows: [], enrolments: [], gifts: [], achieved: new Map(), nextId: 1, nextVersionId: 1, nextGiftId: 1, ops: new Map() };
  const seed = (kind: string, code: string, status: Row["status"], w: Parameters<typeof version>[3], at: string) => {
    const id = s.nextId++;
    s.rows.push({ id, kind, code, status, version: 1, created_at: at, updated_at: at, versions: [version(s.nextVersionId++, id, 1, w, "Seed programme for the portal mock", at)] });
    return id;
  };
  const astha = seed("astha", "ASTHA-Q4", "active", { name_en: "Astha 2026 Q4", name_bn: "আস্থা ২০২৬ চতুর্থ ত্রৈমাসিক", period_label: "2026-Q4", active_from: "2026-10-01", active_to: "2099-12-31", scope: [{ node_type: "sub_channel", node_id: 11 }], tiers: [t("silver", 1, "Silver", "সিলভার", null), t("gold", 2, "Gold", "গোল্ড", "3000.500"), t("platinum", 3, "Platinum", "প্ল্যাটিনাম", "5000.000")] }, "2026-09-28T05:00:00.000Z");
  const dl = seed("diamond_league", "DL-OCT", "active", { name_en: "Diamond League October", name_bn: "ডায়মন্ড লিগ অক্টোবর", period_label: "2026-10", active_from: "2026-10-01", active_to: "2099-12-31", qualifying: [{ product_level: "brand", product_id: 1 }] }, "2026-09-30T05:00:00.000Z");
  seed("superstar", "SS-NOV", "draft", { name_en: "Superstar November", name_bn: "সুপারস্টার নভেম্বর মাসের বিশেষ কর্মসূচি, ঢাকা উইংয়ের সকল পরিবেশক ও খুচরা বিক্রেতার জন্য প্রযোজ্য একটি দীর্ঘ নাম", period_label: "2026-11", active_from: "2026-11-15", active_to: "2099-12-31", scope: [{ node_type: "sub_channel", node_id: 11 }, { node_type: "wing", node_id: 1 }], qualifying: [{ product_level: "brand", product_id: 1 }] }, "2026-11-02T05:00:00.000Z");
  seed("astha", "ASTHA-Q3", "inactive", { name_en: "Astha 2026 Q3", period_label: "2026-Q3", active_from: "2026-07-01", active_to: "2026-09-30", tiers: [t("gold", 1, "Gold", null, "2500.000")] }, "2026-06-28T05:00:00.000Z");
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
const NATIONAL = ["SUPERADMIN", "ADMIN", "NATIONAL", "TOP"];
const READERS = [...NATIONAL, "BRAND", "WM", "DMO", "TSO"];
/** Zones inside the reach of a WM / DMO / TSO user in the mock (a real user's reach comes from the token; this is the fixed mock model). */
const REACH_ZONES = [14];
const GEO = ["wing", "division", "territory", "zone", "route"];
/** r / w / none of the caller on module `loyalty` (mloy1 is the ADMIN with a read grant; mgrant1 and mdisc1 have no grant on it). The roles
 *  below ADMIN read within their reach, as the server does (docs/34): NATIONAL / TOP everything, BRAND / WM / DMO / TSO a part. */
export function loyaltyAccess(c: LoyaltyCaller): "none" | "r" | "w" {
  if (c.role === "SUPERADMIN") return "w";
  if (c.role === "ADMIN") return c.username === "mgrant1" || c.username === "mdisc1" ? "none" : c.username === "mloy1" ? "r" : "w";
  return READERS.includes(c.role) ? "r" : "none";
}

type Body = Record<string, unknown>;
type Err = { pointer: string; code: string };

function view(r: Row) {
  const v = r.versions[0]!;
  return { programme_id: r.id, programme_version_id: v.programme_version_id, kind: r.kind, code: r.code, name_en: v.name_en, name_bn: v.name_bn, period_label: v.period_label, active_from: v.active_from, active_to: v.active_to, points_expire_on: null, rules: {}, status: r.status, version: r.version, version_no: v.version_no, tiers: v.tiers, qualifying: v.qualifying, scope: v.scope, change_reason: v.change_reason, created_at: r.created_at, updated_at: r.updated_at };
}

/** A programme is in a restricted reader's reach (mock model): BRAND sees programmes whose products include its brand (1) or all products;
 *  WM / DMO / TSO see programmes whose scope names no geography outside the reach zone (a wing/division node is outside it). */
function inReach(me: LoyaltyCaller, r: Row): boolean {
  if (NATIONAL.includes(me.role)) return true;
  const v = r.versions[0]!;
  if (me.role === "BRAND") return v.qualifying.length === 0 || v.qualifying.some((q) => q.product_level === "brand" && q.product_id === 1);
  return !v.scope.some((n) => GEO.includes(n.node_type) && !(n.node_type === "zone" && REACH_ZONES.includes(n.node_id)));
}

const WRITE_KEYS = ["request_uuid", "kind", "code", "name_en", "name_bn", "period_label", "active_from", "active_to", "points_expire_on", "rules", "tiers", "qualifying", "scope", "status", "change_reason"];
const RULE_KEYS = ["name_en", "name_bn", "period_label", "active_from", "active_to", "points_expire_on", "rules", "tiers", "qualifying", "scope"];
const PATCH_KEYS = WRITE_KEYS.filter((k) => k !== "kind" && k !== "code");
const TIER_KEYS = ["tier_code", "sort_order", "label_en", "label_bn", "threshold", "threshold_basis"];

/** The closed ProgrammeWrite (create) and ProgrammePatch (update), with the server's pointers ("body.tiers[0].threshold") and codes
 *  (unknown_field, not_a_decimal, thresholds_not_rising ...): an unknown member and `status` are 400, a patch never takes kind or code. */
function programmeErrors(b: Body, mode: "create" | "patch"): Err[] {
  const e: Err[] = [];
  const bad = (pointer: string, code = "invalid") => e.push({ pointer: `body${pointer ? "." : ""}${pointer}`, code });
  const allowed = mode === "create" ? WRITE_KEYS : PATCH_KEYS;
  for (const k of Object.keys(b)) if (!allowed.includes(k)) bad(k, "unknown_field");
  if ("status" in b) bad("status", "use_status_endpoint");
  const has = (k: string) => b[k] !== undefined;
  if (mode === "create") {
    for (const k of ["kind", "code", "name_en", "active_from", "active_to", "change_reason"]) if (!has(k)) bad(k, "required");
    if (has("kind") && (typeof b.kind !== "string" || !KINDS.includes(b.kind))) bad("kind", "not_in_enum");
    if (has("code") && (typeof b.code !== "string" || !PCODE.test(b.code))) bad("code", "pattern");
  } else if (!RULE_KEYS.some((k) => has(k))) bad("", "no_change");
  if (has("name_en") && (typeof b.name_en !== "string" || !b.name_en.trim() || chars(b.name_en) > 120)) bad("name_en", "length");
  if (b.name_bn != null && (typeof b.name_bn !== "string" || chars(b.name_bn) > 120)) bad("name_bn", "length");
  if (b.period_label != null && (typeof b.period_label !== "string" || chars(b.period_label) > 40)) bad("period_label", "length");
  for (const k of ["active_from", "active_to"]) if (has(k) && (typeof b[k] !== "string" || !DAY.test(b[k] as string))) bad(k, "not_a_date");
  if (typeof b.active_from === "string" && typeof b.active_to === "string" && b.active_to < b.active_from) bad("active_to", "before_active_from");
  if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) bad("change_reason", "length");
  if (has("request_uuid") && (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid))) bad("request_uuid", "not_a_uuid");
  if (has("qualifying") && (!Array.isArray(b.qualifying) || b.qualifying.length > 100)) bad("qualifying", "out_of_range");
  if (has("scope") && (!Array.isArray(b.scope) || b.scope.length > 200)) bad("scope", "out_of_range");
  if (Array.isArray(b.scope) && b.scope.some((n) => (n as Body).node_type === "channel")) bad("scope", "channel_not_supported");
  if (has("tiers")) {
    if (!Array.isArray(b.tiers) || b.tiers.length > 10) bad("tiers", "out_of_range");
    else {
      const seen = new Set<string>();
      const orders = new Set<number>();
      const rising: { order: number; v: bigint }[] = [];
      (b.tiers as Body[]).forEach((x, i) => {
        const at = (m: string) => `tiers[${i}].${m}`;
        for (const k of Object.keys(x)) if (!TIER_KEYS.includes(k)) bad(at(k), "unknown_field");
        if (typeof x.tier_code !== "string" || !TCODE.test(x.tier_code)) bad(at("tier_code"), "pattern");
        else if (seen.has(x.tier_code)) bad("tiers", "duplicate_tier_code");
        else seen.add(x.tier_code);
        if (!Number.isInteger(x.sort_order) || (x.sort_order as number) < 1 || (x.sort_order as number) > 10) bad(at("sort_order"), "required");
        else if (orders.has(x.sort_order as number)) bad("tiers", "duplicate_sort_order");
        else orders.add(x.sort_order as number);
        if (typeof x.label_en !== "string" || !x.label_en.trim() || chars(x.label_en) > 80) bad(at("label_en"), "length");
        if (x.threshold_basis !== undefined && x.threshold_basis !== "std") bad(at("threshold_basis"), "not_in_enum");
        if (x.threshold != null) {
          if (typeof x.threshold !== "string" || !DEC3.test(x.threshold)) bad(at("threshold"), "not_a_decimal");
          else {
            const [w, f = ""] = x.threshold.split(".");
            rising.push({ order: Number(x.sort_order), v: BigInt(w!) * 1000n + BigInt(f.padEnd(3, "0")) });
          }
        }
      });
      rising.sort((p, q) => p.order - q.order);
      if (rising.some((x, i) => i > 0 && x.v <= rising[i - 1]!.v)) bad("tiers", "thresholds_not_rising");
    }
  }
  return e;
}

const normTier = (x: Body): Tier => ({ tier_code: String(x.tier_code), sort_order: Number(x.sort_order), label_en: String(x.label_en), label_bn: (x.label_bn as string | null | undefined) ?? null, threshold: (x.threshold as string | null | undefined) == null ? null : decimalWire(String(x.threshold)), threshold_basis: "std" });
function decimalWire(s: string): string {
  const [w, f = ""] = s.split(".");
  return `${BigInt(w!)}.${f.padEnd(3, "0")}`;
}

export async function handleLoyalty(s: LoyaltyStore, ctx: Ctx, me: LoyaltyCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const m = /^\/v1\/admin\/(?:programmes(?:\/([1-9]\d{0,14}))?(?:\/(enrolments|versions|status))?|gifts(?:\/([1-9]\d{0,14}))?)$/.exec(path);
  if (!m) return false;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown, headers?: Record<string, string>): true => { send(res, status, body, headers); return true; };
  const isGift = path.startsWith("/v1/admin/gifts");
  const access = loyaltyAccess(me);
  const isRead = method === "GET";
  if (access === "none" || (!isRead && access !== "w")) return done(403, problem(403, "ERR_FORBIDDEN"));
  if (!isRead && isGift && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN"));
  const validation = (errors: Err[]) => done(400, problem(400, "ERR_VALIDATION", { errors } as never));
  const state = (what: string) => done(409, problem(409, "ERR_REQUEST_STATE", { detail: what } as never));
  const reused = (pointer: string) => done(409, problem(409, "ERR_CONFLICT", { errors: [{ pointer, code: "reused" }] } as never));
  const dup = () => done(409, problem(409, "ERR_MASTER_DUPLICATE_CODE", { errors: [{ pointer: "body.code", code: "duplicate" }] } as never));
  const pid = m[1] ? Number(m[1]) : null;
  const sub = m[2] ?? null;
  const row = pid === null ? null : s.rows.find((r) => r.id === pid) ?? null;
  const unknownQuery = (name: string, v: string | null, allowed: string[]): Err[] => (v !== null && !allowed.includes(v) ? [{ pointer: `query.${name}`, code: "not_in_enum" }] : []);
  const STATUSES = ["draft", "active", "inactive"];

  // ----- gifts
  if (isGift) {
    const gid = m[3] ? Number(m[3]) : null;
    if (gid === null && method === "GET") {
      const f = url.searchParams.get("programme_id");
      const st = url.searchParams.get("status");
      const qe = unknownQuery("status", st, ["active", "inactive"]);
      if (qe.length) return validation(qe);
      return done(200, { items: s.gifts.filter((g) => (!f || g.programme_id === Number(f)) && (!st || g.status === st) && inReach(me, s.rows.find((r) => r.id === g.programme_id)!)) });
    }
    if (gid !== null && method === "GET") {
      const g = s.gifts.find((x) => x.gift_id === gid);
      return g && inReach(me, s.rows.find((r) => r.id === g.programme_id)!) ? done(200, g, { ETag: `"${g.version}"` }) : done(404, problem(404, "ERR_NOT_FOUND"));
    }
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "body", code: "malformed" }]);
    const errs: Err[] = [];
    const bad = (p: string, code = "invalid") => errs.push({ pointer: `body.${p}`, code });
    // GiftWrite and GiftPatch are closed; the code and the programme of a gift never change (a patch refuses both).
    const giftKeys = ["request_uuid", "name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status", "change_reason", ...(gid === null ? ["programme_id", "code"] : [])];
    for (const k of Object.keys(b)) if (!giftKeys.includes(k)) bad(k, "unknown_field");
    if (gid === null) {
      if (typeof b.programme_id !== "number") bad("programme_id", "required");
      if (typeof b.code !== "string" || !TCODE.test(b.code)) bad("code", "pattern");
      if (typeof b.name_en !== "string") bad("name_en", "required");
    } else if (!["name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status"].some((k) => b[k] !== undefined)) bad("", "no_change");
    if (typeof b.name_en === "string" && (!b.name_en.trim() || chars(b.name_en) > 120)) bad("name_en", "length");
    if (b.points_cost != null && (!Number.isInteger(b.points_cost) || (b.points_cost as number) < 1 || (b.points_cost as number) > 100_000)) bad("points_cost", "out_of_range");
    if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) bad("change_reason", "length");
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) bad("request_uuid", b.request_uuid === undefined ? "required" : "not_a_uuid");
    if (b.status !== undefined && !["active", "inactive"].includes(String(b.status))) bad("status", "not_in_enum");
    if (b.tier_codes !== undefined && (!Array.isArray(b.tier_codes) || b.tier_codes.length > 10 || (b.tier_codes as unknown[]).some((c) => typeof c !== "string" || !TCODE.test(c)))) bad("tier_codes", "pattern");
    if (b.image_asset_id != null) bad("image_asset_id", "not_found"); // the mock holds no uploaded asset of purpose gift_image
    if (errs.length) return validation(errs);
    const key = `gift:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined, gid });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : reused("body.request_uuid");
    const tierCheck = (programmeId: number): Err[] => {
      const tiers = s.rows.find((r) => r.id === programmeId)!.versions[0]!.tiers.map((x) => x.tier_code);
      return tiers.length === 0 ? [] : ((b.tier_codes as string[] | undefined) ?? []).flatMap((c, i) => (tiers.includes(c) ? [] : [{ pointer: `body.tier_codes[${i}]`, code: "unknown_tier" }]));
    };
    if (gid === null && method === "POST") {
      const prog = s.rows.find((r) => r.id === b.programme_id);
      if (!prog) return validation([{ pointer: "body.programme_id", code: "not_found" }]);
      if (prog.status === "inactive") return state("an ended programme takes no new gifts");
      const te = tierCheck(prog.id);
      if (te.length) return validation(te);
      if (s.gifts.some((g) => g.programme_id === prog.id && g.code === b.code)) return dup(); // unique per programme
      const g: Gift = { gift_id: s.nextGiftId++, programme_id: prog.id, code: b.code as string, name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, points_cost: (b.points_cost as number | null) ?? null, tier_codes: (b.tier_codes as string[]) ?? [], image_url: null, status: (b.status as Gift["status"]) ?? "active", version: 1 };
      s.gifts.push(g);
      ctx.audit("gift", g.gift_id, "create", {}, { code: g.code }, String(b.change_reason));
      s.ops.set(key, { fp: print, status: 201, body: g });
      return done(201, g);
    }
    const g = s.gifts.find((x) => x.gift_id === gid);
    if (!g) return done(404, problem(404, "ERR_NOT_FOUND"));
    if (method !== "PATCH") return done(405, problem(405 as never, "ERR_NOT_FOUND"));
    const ifMatch = req.headers["if-match"];
    if (typeof ifMatch !== "string" || !/^"[0-9]{1,10}"$/.test(ifMatch)) return validation([{ pointer: "header.If-Match", code: ifMatch === undefined ? "required" : "invalid" }]);
    if (s.rows.find((r) => r.id === g.programme_id)!.status === "inactive") return state("an ended programme takes no gift changes");
    if (ifMatch !== `"${g.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    const te = tierCheck(g.programme_id);
    if (te.length) return validation(te);
    const next = { ...g, ...Object.fromEntries(["name_en", "name_bn", "points_cost", "tier_codes", "status"].filter((k) => b[k] !== undefined).map((k) => [k, b[k]])) };
    if (fp({ ...next, version: 0 }) === fp({ ...g, version: 0 })) return validation([{ pointer: "body", code: "no_change" }]);
    Object.assign(g, next, { version: g.version + 1 });
    ctx.audit("gift", g.gift_id, "update", {}, { code: g.code }, String(b.change_reason));
    s.ops.set(key, { fp: print, status: 200, body: g });
    return done(200, g);
  }

  // ----- programmes
  if (pid === null && method === "GET") {
    const st = url.searchParams.get("status");
    const kd = url.searchParams.get("kind");
    const qe = [...unknownQuery("status", st, STATUSES), ...unknownQuery("kind", kd, KINDS)];
    if (qe.length) return validation(qe);
    return done(200, { items: [...s.rows].reverse().filter((r) => (!st || r.status === st) && (!kd || r.kind === kd) && inReach(me, r)).map(view) });
  }

  if (pid === null && method === "POST") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "body", code: "malformed" }]);
    const errs = programmeErrors(b, "create");
    if (errs.length) return validation(errs);
    const key = `create:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : reused("body.request_uuid");
    if (s.rows.some((r) => r.code === b.code)) return dup();
    const at = new Date().toISOString();
    const id = s.nextId++;
    const r: Row = { id, kind: String(b.kind), code: String(b.code), status: "draft", version: 1, created_at: at, updated_at: at, versions: [] };
    r.versions.push(version(s.nextVersionId++, id, 1, { name_en: String(b.name_en), name_bn: (b.name_bn as string | null) ?? null, period_label: (b.period_label as string | null) ?? null, active_from: String(b.active_from), active_to: String(b.active_to), tiers: ((b.tiers as Body[] | undefined) ?? []).map(normTier), qualifying: (b.qualifying as Ref[] | undefined) ?? [], scope: (b.scope as Node[] | undefined) ?? [] }, String(b.change_reason), at));
    s.rows.push(r);
    ctx.audit("programme", id, "create", {}, { code: r.code, status: "draft" }, String(b.change_reason));
    const body = view(r);
    s.ops.set(key, { fp: print, status: 201, body });
    return done(201, body);
  }

  if (!row || !inReach(me, row)) return done(404, problem(404, "ERR_NOT_FOUND"));

  if (sub === "versions" && method === "GET") {
    const limit = Number(url.searchParams.get("limit") ?? 50);
    const start = Number((url.searchParams.get("cursor") ?? "c0").replace(/^c/, "")) || 0;
    return done(200, { items: row.versions.slice(start, start + limit), next_cursor: start + limit < row.versions.length ? `c${start + limit}` : null });
  }

  if (sub === null && method === "GET") return done(200, view(row), { ETag: `"${row.version}"` });

  if (sub === "enrolments" && me.role === "BRAND") return done(403, problem(403, "ERR_FORBIDDEN")); // outlet data: a BRAND user reads programmes and gifts, not outlets

  if (sub === "enrolments" && method === "GET") {
    const limit = Number(url.searchParams.get("limit") ?? 50);
    const start = Number((url.searchParams.get("cursor") ?? "c0").replace(/^c/, "")) || 0;
    const zone = url.searchParams.get("zone_id");
    if (zone !== null && !NATIONAL.includes(me.role) && !REACH_ZONES.includes(Number(zone))) return done(403, problem(403, "ERR_FORBIDDEN")); // a zone outside the reach
    const all = s.enrolments.filter((e) => e.programme_id === row.id);
    return done(200, { items: all.slice(start, start + limit), next_cursor: start + limit < all.length ? `c${start + limit}` : null });
  }

  if (sub === "enrolments" && method === "PUT") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "body", code: "malformed" }]);
    const errs: Err[] = [];
    const bad = (p: string, code = "invalid") => errs.push({ pointer: `body.${p}`, code });
    for (const k of Object.keys(b)) if (!["batch_uuid", "rows", "change_reason"].includes(k)) bad(k, "unknown_field");
    if (typeof b.batch_uuid !== "string" || !UUID.test(b.batch_uuid)) bad("batch_uuid", b.batch_uuid === undefined ? "required" : "not_a_uuid");
    if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) bad("change_reason", "length");
    const rows = Array.isArray(b.rows) ? (b.rows as Body[]) : null;
    if (!rows || rows.length < 1 || rows.length > 20_000) bad("rows", "out_of_range");
    else {
      const seenKey = new Set<string>();
      rows.forEach((x, i) => {
        const at = (m: string) => `rows[${i}].${m}`;
        for (const k of Object.keys(x)) if (!["programme_id", "outlet_id", "league_label", "tier_code", "base_target", "valid_from", "valid_to"].includes(k)) bad(at(k), "unknown_field");
        if (x.programme_id !== row.id) bad(at("programme_id"), "path_mismatch");
        if (!Number.isInteger(x.outlet_id) || (x.outlet_id as number) < 1) bad(at("outlet_id"), "required");
        if (x.tier_code != null && (typeof x.tier_code !== "string" || !TCODE.test(x.tier_code))) bad(at("tier_code"), "pattern");
        if (x.base_target != null) {
          if (typeof x.base_target !== "string" || !(/^-?\d{1,13}(\.\d{1,3})?$/.test(x.base_target))) bad(at("base_target"), "not_a_decimal");
          else if (x.base_target.startsWith("-") && Number(x.base_target) !== 0) bad(at("base_target"), "negative"); // the -37,500% bug: targets are never negative
        }
        if (typeof x.valid_from !== "string" || !DAY.test(x.valid_from)) bad(at("valid_from"), "required");
        if (x.valid_to != null && (typeof x.valid_to !== "string" || !DAY.test(x.valid_to))) bad(at("valid_to"), "not_a_date");
        else if (typeof x.valid_to === "string" && typeof x.valid_from === "string" && x.valid_to <= x.valid_from) bad(at("valid_to"), "before_valid_from"); // exclusive end
        const k = `${String(x.outlet_id)}:${String(x.valid_from)}`;
        if (seenKey.has(k)) bad(`rows[${i}]`, "duplicate");
        seenKey.add(k);
      });
    }
    if (errs.length) return validation(errs);
    const key = `enrol:${row.id}:${b.batch_uuid as string}`;
    const print = fp({ ...b, batch_uuid: undefined });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : reused("body.batch_uuid");
    if (row.status === "inactive") return state("an ended programme takes no new enrolments");
    const tiers = row.versions[0]!.tiers.map((x) => x.tier_code);
    const rowErrs: Err[] = [];
    rows!.forEach((x, i) => {
      if (x.tier_code != null && tiers.length > 0 && !tiers.includes(x.tier_code as string)) rowErrs.push({ pointer: `body.rows[${i}].tier_code`, code: "unknown_tier" });
      if ((x.outlet_id as number) > 1000) rowErrs.push({ pointer: `body.rows[${i}].outlet_id`, code: "not_found" }); // the mock knows outlets 1..1000
    });
    if (rowErrs.length) return validation(rowErrs);
    let inserted = 0, updated = 0, unchanged = 0;
    const staged = [...s.enrolments];
    for (const x of rows!) {
      const e: Enrol = { programme_id: row.id, outlet_id: x.outlet_id as number, league_label: (x.league_label as string | null) ?? null, tier_code: (x.tier_code as string | null) ?? null, base_target: x.base_target == null ? null : decimalWire(String(x.base_target)), valid_from: String(x.valid_from), valid_to: (x.valid_to as string | null) ?? null };
      const same = staged.findIndex((o) => o.programme_id === row.id && o.outlet_id === e.outlet_id && o.valid_from === e.valid_from);
      // The gist EXCLUDE: no overlapping window per programme and outlet. The end is exclusive, so back-to-back windows are allowed.
      const overlap = staged.some((o, i) => i !== same && o.programme_id === row.id && o.outlet_id === e.outlet_id && o.valid_from < (e.valid_to ?? "9999-12-31") && e.valid_from < (o.valid_to ?? "9999-12-31"));
      if (overlap) return done(409, problem(409, "ERR_MASTER_OVERLAP"));
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
    if (!b || typeof b !== "object") return validation([{ pointer: "body", code: "malformed" }]);
    const errs = programmeErrors(b, "patch");
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "body.request_uuid", code: "required" });
    const ifMatch = req.headers["if-match"];
    if (typeof ifMatch !== "string" || !/^"[0-9]{1,10}"$/.test(ifMatch)) errs.push({ pointer: "header.If-Match", code: ifMatch === undefined ? "required" : "invalid" });
    if (errs.length) return validation(errs);
    const key = `update:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined, id: row.id });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : reused("body.request_uuid");
    if (row.status !== "draft") return state(`only a draft can change; an ${row.status} programme is final`); // 409 before the ETag is judged
    if (ifMatch !== `"${row.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    const cur = row.versions[0]!;
    // Members left out keep the current version's value; tiers, qualifying and scope, when given, replace the whole list.
    const keep = Object.fromEntries(RULE_KEYS.filter((k) => b[k] !== undefined && k !== "points_expire_on" && k !== "rules").map((k) => [k, k === "tiers" ? (b.tiers as Body[]).map(normTier) : b[k]]));
    const next: Version = { ...cur, ...keep, programme_version_id: s.nextVersionId++, version_no: cur.version_no + 1, change_reason: String(b.change_reason), created_at: new Date().toISOString(), is_current: true };
    if (next.active_to < next.active_from) return validation([{ pointer: "body.active_to", code: "before_active_from" }]);
    const strip = (v: Version) => ({ ...v, programme_version_id: 0, version_no: 0, change_reason: null, created_at: "", is_current: true, tiers: [...v.tiers].sort((x, y) => x.sort_order - y.sort_order) });
    if (fp(strip(next)) === fp(strip(cur))) return validation([{ pointer: "body", code: "no_change" }]); // nothing really changed: no new version
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
    if (!b || typeof b !== "object") return validation([{ pointer: "body", code: "malformed" }]);
    const errs: Err[] = [];
    for (const k of Object.keys(b)) if (!["request_uuid", "to_status", "reason"].includes(k)) errs.push({ pointer: `body.${k}`, code: "unknown_field" });
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "body.request_uuid", code: b.request_uuid === undefined ? "required" : "not_a_uuid" });
    if (b.to_status !== "active" && b.to_status !== "inactive") errs.push({ pointer: "body.to_status", code: "not_in_enum" });
    if (typeof b.reason !== "string" || chars(b.reason) < 10 || chars(b.reason) > 500) errs.push({ pointer: "body.reason", code: "length" });
    if (errs.length) return validation(errs);
    const key = `status:${b.request_uuid as string}`;
    const print = fp({ id: row.id, to: b.to_status, reason: b.reason });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : reused("body.request_uuid");
    const to = b.to_status as "active" | "inactive";
    if (to === "active" && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN")); // an ADMIN is refused before any state check
    const allowed = (row.status === "draft" && to === "active") || (row.status === "active" && to === "inactive");
    if (!allowed) return state(`a ${row.status} programme cannot move to ${to}`);
    const im = req.headers["if-match"];
    if (typeof im === "string" && im !== `"${row.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    if (to === "active" && row.versions[0]!.active_to < dhakaToday()) return validation([{ pointer: "body.active_to", code: "past" }]);
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
