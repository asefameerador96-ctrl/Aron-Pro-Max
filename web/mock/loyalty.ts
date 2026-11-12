// Mock of the loyalty-programme API, shaped by contract 1.13.1 and by the server it stands for (AdminLoyalty.kt of lane portal-loyalty-api,
// D-LOY-WEB-05). It follows the server's ORDER of checks and answers exactly ONE field error (the first), with the server's pointers and
// codes; lists are newest first with numeric keyset cursors; replays answer the stored first result. In memory; reset with /__mock/reset.
// WHAT IS EQUAL and WHAT STAYS DIFFERENT is written in DECISIONS.md D-LOY-WEB-05 (the mock has no real geography, products, outlets or
// uploads: it uses a small fixed model, named below).
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
interface Enrol { id: number; programme_id: number; outlet_id: number; league_label: string | null; tier_code: string | null; base_target: string | null; valid_from: string; valid_to: string | null }
interface Gift { gift_id: number; programme_id: number; code: string; name_en: string; name_bn: string | null; points_cost: number | null; tier_codes: string[]; image_url: string | null; asset: string | null; status: "active" | "inactive"; version: number }
type Stored = { kind: "version" | "status"; fp: string; programmeId: number; versionNo?: number; status: number; body: unknown };
type StoredGift = { kind: "create" | "update"; fp: string; giftId: number; status: number; body: unknown };
type StoredBatch = { fp: string; programmeId: number; body: unknown };
export interface LoyaltyStore { rows: Row[]; enrolments: Enrol[]; gifts: Gift[]; nextId: number; nextVersionId: number; nextGiftId: number; nextEnrolId: number; uuids: Map<string, Stored>; giftUuids: Map<string, StoredGift>; batches: Map<string, StoredBatch> }

const dhakaToday = (): string => new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);
const fp = (o: unknown): string => createHash("sha256").update(JSON.stringify(o)).digest("hex");
const t = (tier_code: string, sort_order: number, label_en: string, label_bn: string | null, threshold: string | null): Tier => ({ tier_code, sort_order, label_en, label_bn, threshold, threshold_basis: "std" });

function version(id: number, pid: number, no: number, w: Partial<Version> & Pick<Version, "name_en" | "active_from" | "active_to">, reason: string, at: string): Version {
  return { programme_version_id: id, programme_id: pid, version_no: no, is_current: true, name_bn: null, period_label: null, tiers: [], qualifying: [], scope: [], change_reason: reason, created_at: at, ...w };
}

export function freshLoyaltyStore(): LoyaltyStore {
  const s: LoyaltyStore = { rows: [], enrolments: [], gifts: [], nextId: 1, nextVersionId: 1, nextGiftId: 1, nextEnrolId: 1, uuids: new Map(), giftUuids: new Map(), batches: new Map() };
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
    { id: s.nextEnrolId++, programme_id: astha, outlet_id: 101, league_label: null, tier_code: "platinum", base_target: "6000.000", valid_from: "2026-10-01", valid_to: null },
    { id: s.nextEnrolId++, programme_id: astha, outlet_id: 102, league_label: null, tier_code: "gold", base_target: "3500.250", valid_from: "2026-10-01", valid_to: null },
    { id: s.nextEnrolId++, programme_id: astha, outlet_id: 103, league_label: null, tier_code: "silver", base_target: "0.000", valid_from: "2026-10-01", valid_to: null }, // a zero target: its percent is guarded
  );
  const gift = (programme_id: number, code: string, name_en: string, name_bn: string, points_cost: number | null, tier_codes: string[]) => s.gifts.push({ gift_id: s.nextGiftId++, programme_id, code, name_en, name_bn, points_cost, tier_codes, image_url: null, asset: null, status: "active", version: 1 });
  gift(dl, "kitchen_rack", "Kitchen rack", "রান্নাঘরের র‍্যাক", 200, []);
  gift(dl, "chair", "Chair", "চেয়ার", 200, []);
  gift(dl, "tornado_fan", "Tornado fan", "টর্নেডো ফ্যান", 400, []);
  gift(astha, "rice_cooker", "Rice cooker", "রাইস কুকার", null, ["platinum", "gold"]);
  return s;
}

export interface LoyaltyCaller { role: Role; username: string }
const NATIONAL = ["SUPERADMIN", "ADMIN", "NATIONAL", "TOP"];
const READERS = [...NATIONAL, "BRAND", "WM", "DMO", "TSO"];
/** Uploaded assets the mock holds (the server checks app.admin_asset): one of purpose gift_image, one of another purpose. */
export const GIFT_IMAGE_ASSET = "0b6d6c0e-8a5b-4d56-9f0a-0000000a0001";
export const OTHER_ASSET = "0b6d6c0e-8a5b-4d56-9f0a-0000000a0002";
const ASSETS: Record<string, string> = { [GIFT_IMAGE_ASSET]: "gift_image", [OTHER_ASSET]: "sku_image" };
/** The mock's fixed geography: zone -> territory -> division -> wing, route -> zone, outlet -> zone. */
const ZONE_TERR: Record<number, number> = { 14: 334, 15: 335 };
const TERR_DIV: Record<number, number> = { 334: 10, 335: 10 };
const DIV_WING: Record<number, number> = { 10: 1 };
const ROUTE_ZONE: Record<number, number> = { 1: 14, 3: 15, 4: 15 };
const outletZone = (outlet: number): number => (outlet <= 600 ? 14 : 15);
const REACH: Record<string, number[]> = { wm1: [14, 15], dmo1: [14, 15], mdmo1: [14, 15], tso334: [14], mtso1: [14], tso335: [15], tso999: [99] };
const reachOf = (me: LoyaltyCaller): number[] => REACH[me.username] ?? [14];
const BRANDS: Record<string, number[]> = { brand1: [1] };
const GEO = ["wing", "division", "territory", "zone", "route"];

/** r / w / none of the caller on module `loyalty` (mloy1 is the ADMIN with a read grant; mgrant1 and mdisc1 have no grant on it). The roles
 *  below ADMIN read within their reach, as the server does (docs/34): NATIONAL / TOP everything, BRAND / WM / DMO / TSO a part. */
export function loyaltyAccess(c: LoyaltyCaller): "none" | "r" | "w" {
  if (c.role === "SUPERADMIN") return "w";
  if (c.role === "ADMIN") return c.username === "mgrant1" || c.username === "mdisc1" ? "none" : c.username === "mloy1" ? "r" : "w";
  return READERS.includes(c.role) ? "r" : "none";
}

type Body = Record<string, unknown>;
class Bad extends Error {
  constructor(readonly pointer: string, readonly code: string) { super(code); }
}
/** The server's adminBad: the FIRST problem ends the call. */
const bad = (pointer: string, code = "invalid_value"): never => { throw new Bad(pointer, code); };

const only = (o: Body, allowed: string[]): void => { const k = Object.keys(o).find((x) => !allowed.includes(x)); if (k) bad(`body.${k}`, "unknown_field"); };
const str = (o: Body, k: string, min: number, max: number, required: boolean, nullable = false, p = `body.${k}`): string | null => {
  const v = o[k];
  if (v === undefined) { if (required) bad(p, "required"); return null; }
  if (v === null) { if (nullable) return null; bad(p, "null_not_allowed"); }
  if (typeof v !== "string") bad(p, "not_a_string");
  const sv = v as string;
  if (Array.from(sv).length < min || Array.from(sv).length > max || (min >= 1 && !sv.trim())) bad(p, "length");
  return sv;
};
const lint = (o: Body, k: string, min: number, max: number, p = `body.${k}`, nullable = false): number | null => {
  const v = o[k];
  if (v === undefined) return null;
  if (v === null) { if (nullable) return null; bad(p, "null_not_allowed"); }
  if (typeof v !== "number" || !Number.isInteger(v)) bad(p, "not_an_integer");
  if ((v as number) < min || (v as number) > max) bad(p, "out_of_range");
  return v as number;
};
const idOf = (o: Body, k: string, required: boolean, p = `body.${k}`): number | null => {
  const v = o[k];
  if (v === undefined) { if (required) bad(p, "required"); return null; }
  if (v === null) bad(p, "null_not_allowed");
  if (typeof v !== "number" || !Number.isInteger(v)) bad(p, "not_an_integer");
  if ((v as number) < 1) bad(p, "out_of_range");
  return v as number;
};
const enumOf = (o: Body, k: string, allowed: string[], required: boolean): string | null => {
  const v = o[k];
  if (v === undefined) { if (required) bad(`body.${k}`, "required"); return null; }
  if (v === null) bad(`body.${k}`, "null_not_allowed");
  if (typeof v !== "string" || !allowed.includes(v)) bad(`body.${k}`, "not_in_enum");
  return v as string;
};
const realDay = (s: string): boolean => DAY.test(s) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);
const ldate = (o: Body, k: string, p = `body.${k}`): string | null => {
  const v = o[k];
  if (v === undefined || v === null) return null;
  if (typeof v !== "string" || !realDay(v)) bad(p, "not_a_date");
  return v as string;
};
const uuidOf = (o: Body, k: string): string => {
  const v = str(o, k, 36, 36, true)!;
  if (!UUID.test(v)) bad(`body.${k}`, "not_a_uuid");
  return v;
};
const reasonOf = (o: Body, k = "change_reason"): string => str(o, k, 10, 500, true)!;
const pathId = (raw: string): number => { const n = /^\d{1,18}$/.test(raw) ? Number(raw) : NaN; return Number.isSafeInteger(n) && n >= 1 ? n : bad("path.id"); };
const ifMatchOf = (raw: string | string[] | undefined): number => { const m = typeof raw === "string" ? /^"([0-9]{1,10})"$/.exec(raw) : null; return m ? Number(m[1]) : bad("header.If-Match"); };
const pageLimit = (q: URLSearchParams): number => { const v = q.get("limit"); if (v === null) return 100; const n = /^-?\d+$/.test(v) ? Number(v) : NaN; return n >= 1 && n <= 500 ? n : bad("query.limit", "out_of_range"); };
const pageCursor = (q: URLSearchParams): number | null => { const v = q.get("cursor"); if (v === null) return null; const n = /^\d+$/.test(v) ? Number(v) : NaN; return Number.isSafeInteger(n) && n >= 1 ? n : bad("query.cursor"); };
const queryId = (q: URLSearchParams, name: string): number | null => { const v = q.get(name); if (v === null) return null; const n = /^\d+$/.test(v) ? Number(v) : NaN; return Number.isSafeInteger(n) && n >= 1 ? n : bad(`query.${name}`); };
const queryEnum = (q: URLSearchParams, name: string, allowed: string[]): string | null => { const v = q.get(name); if (v !== null && !allowed.includes(v)) bad(`query.${name}`, "not_in_enum"); return v; };

// ---- visibility (the server's loyVisSql, over the mock's fixed geography and brands)
const zonesOf = (type: string, id: number): number[] => {
  if (type === "zone") return [id];
  if (type === "territory") return Object.keys(ZONE_TERR).map(Number).filter((z) => ZONE_TERR[z] === id);
  if (type === "division") return Object.keys(ZONE_TERR).map(Number).filter((z) => TERR_DIV[ZONE_TERR[z]!] === id);
  if (type === "wing") return Object.keys(ZONE_TERR).map(Number).filter((z) => DIV_WING[TERR_DIV[ZONE_TERR[z]!]!] === id);
  if (type === "route") return ROUTE_ZONE[id] === undefined ? [] : [ROUTE_ZONE[id]!];
  return [];
};
/** A version is visible to the caller: all for ADMIN-and-up / NATIONAL / TOP; BRAND: no product named or one of its brands; WM / DMO / TSO: no geography
 *  named (channel nodes do not narrow) or a named node that contains a zone of the caller's reach. */
function visible(me: LoyaltyCaller, v: Pick<Version, "qualifying" | "scope">): boolean {
  if (NATIONAL.includes(me.role)) return true;
  if (me.role === "BRAND") {
    const brands = BRANDS[me.username] ?? [];
    return v.qualifying.length === 0 || v.qualifying.some((q) => q.product_level === "brand" && brands.includes(q.product_id));
  }
  const geo = v.scope.filter((n) => GEO.includes(n.node_type));
  if (geo.length === 0) return true;
  const reach = reachOf(me);
  return geo.some((n) => zonesOf(n.node_type, n.node_id).some((z) => reach.includes(z)));
}

function view(r: Row) {
  const v = r.versions[0]!;
  return { programme_id: r.id, programme_version_id: v.programme_version_id, kind: r.kind, code: r.code, name_en: v.name_en, name_bn: v.name_bn, period_label: v.period_label, active_from: v.active_from, active_to: v.active_to, points_expire_on: null, rules: {}, status: r.status, version: r.version, version_no: v.version_no, tiers: v.tiers, qualifying: v.qualifying, scope: v.scope, change_reason: v.change_reason, created_at: r.created_at, updated_at: r.updated_at };
}
const giftView = (g: Gift) => { const { asset, ...rest } = g; return { ...rest, image_url: asset ? `http://127.0.0.1/mock-blob/${asset}` : null }; };

const RULE_MEMBERS = ["name_en", "name_bn", "period_label", "active_from", "active_to", "points_expire_on", "rules", "tiers", "qualifying", "scope"];
const TIER_RE = TCODE;
const PRODUCT_LEVELS = ["category", "brand", "variant", "sku"];
const SCOPE_TYPES = ["wing", "division", "territory", "zone", "route", "channel", "sub_channel"];

interface Spec { nameEn: string; nameBn: string | null; period: string | null; from: string; to: string; expire: string | null; tiers: Tier[]; qualifying: Ref[]; scope: Node[] }
const specOf = (v: Version): Spec => ({ nameEn: v.name_en, nameBn: v.name_bn, period: v.period_label, from: v.active_from, to: v.active_to, expire: null, tiers: v.tiers, qualifying: v.qualifying, scope: v.scope });

function readTiers(o: Body): Tier[] {
  const a = o.tiers;
  if (!Array.isArray(a)) bad("body.tiers", "not_an_array");
  if ((a as unknown[]).length > 10) bad("body.tiers", "out_of_range");
  const out = (a as unknown[]).map((e, i) => {
    if (e === null || typeof e !== "object" || Array.isArray(e)) bad(`body.tiers[${i}]`, "not_an_object");
    const t = e as Body;
    only(t, ["tier_code", "sort_order", "label_en", "label_bn", "threshold", "threshold_basis"]);
    return t;
  }).map((t, i) => {
    const code = str(t, "tier_code", 2, 41, true, false, `body.tiers[${i}].tier_code`)!;
    if (!TIER_RE.test(code)) bad(`body.tiers[${i}].tier_code`, "pattern");
    const order = lint(t, "sort_order", 1, 10, `body.tiers[${i}].sort_order`) ?? bad(`body.tiers[${i}].sort_order`, "required");
    const labelEn = str(t, "label_en", 1, 80, true, false, `body.tiers[${i}].label_en`)!;
    const labelBn = str(t, "label_bn", 0, 80, false, true, `body.tiers[${i}].label_bn`);
    if (t.threshold_basis !== undefined && t.threshold_basis !== "std") bad(`body.tiers[${i}].threshold_basis`, "not_in_enum");
    let th: string | null = null;
    if (t.threshold !== undefined && t.threshold !== null) {
      if (typeof t.threshold !== "string" || !DEC3.test(t.threshold)) bad(`body.tiers[${i}].threshold`, "not_a_decimal");
      th = decimalWire(t.threshold as string);
    }
    return { tier_code: code, sort_order: order, label_en: labelEn, label_bn: labelBn, threshold: th, threshold_basis: "std" as const };
  });
  if (new Set(out.map((t) => t.tier_code)).size !== out.length) bad("body.tiers", "duplicate_tier_code");
  if (new Set(out.map((t) => t.sort_order)).size !== out.length) bad("body.tiers", "duplicate_sort_order");
  const ths = [...out].sort((x, y) => x.sort_order - y.sort_order).flatMap((t) => (t.threshold === null ? [] : [BigInt(t.threshold.replace(".", ""))]));
  if (ths.some((v, i) => i > 0 && v <= ths[i - 1]!)) bad("body.tiers", "thresholds_not_rising");
  return out.sort((x, y) => x.sort_order - y.sort_order);
}
function readPairs(o: Body, k: "qualifying" | "scope", max: number): { a: string; b: number }[] {
  const arr = o[k];
  if (!Array.isArray(arr)) bad(`body.${k}`, "not_an_array");
  if ((arr as unknown[]).length > max) bad(`body.${k}`, "out_of_range");
  const kindKey = k === "qualifying" ? "product_level" : "node_type";
  const idKey = k === "qualifying" ? "product_id" : "node_id";
  const kinds = k === "qualifying" ? PRODUCT_LEVELS : SCOPE_TYPES;
  const seen = new Set<string>();
  return (arr as unknown[]).map((e, i) => {
    if (e === null || typeof e !== "object" || Array.isArray(e)) bad(`body.${k}[${i}]`, "not_an_object");
    const x = e as Body;
    only(x, [kindKey, idKey]);
    const kind = enumOf(x, kindKey, kinds, true)!;
    const id = idOf(x, idKey, true)!;
    if (seen.has(`${kind}:${id}`)) bad(`body.${k}[${i}]`, "duplicate");
    seen.add(`${kind}:${id}`);
    return { a: kind, b: id };
  });
}
/** readSpec: the members present in `o` over `base` (null base = a create). Order of checks as the server. */
function readSpec(o: Body, base: Spec | null): Spec {
  const has = (k: string) => o[k] !== undefined;
  const nameEn = has("name_en") ? str(o, "name_en", 1, 120, true)! : base?.nameEn ?? bad("body.name_en", "required");
  const nameBn = has("name_bn") ? str(o, "name_bn", 0, 120, false, true) : base?.nameBn ?? null;
  const period = has("period_label") ? str(o, "period_label", 0, 40, false, true) : base?.period ?? null;
  const from = has("active_from") ? ldate(o, "active_from") ?? bad("body.active_from", "required") : base?.from ?? bad("body.active_from", "required");
  const to = has("active_to") ? ldate(o, "active_to") ?? bad("body.active_to", "required") : base?.to ?? bad("body.active_to", "required");
  const expire = has("points_expire_on") ? ldate(o, "points_expire_on") : base?.expire ?? null;
  if (has("rules") && (o.rules === null || typeof o.rules !== "object" || Array.isArray(o.rules))) bad("body.rules", "invalid_type");
  const tiers = has("tiers") ? readTiers(o) : base?.tiers ?? [];
  const qualifying = has("qualifying") ? readPairs(o, "qualifying", 100).map((x) => ({ product_level: x.a, product_id: x.b })) : base?.qualifying ?? [];
  const scope = has("scope") ? readPairs(o, "scope", 200).map((x) => ({ node_type: x.a, node_id: x.b })) : base?.scope ?? [];
  if (to < from) bad("body.active_to", "before_active_from");
  if (expire !== null && expire < to) bad("body.points_expire_on", "before_active_to");
  if (scope.some((n) => n.node_type === "channel")) bad("body.scope", "channel_not_supported");
  return { nameEn, nameBn, period, from, to, expire, tiers, qualifying, scope };
}
/** refsExist: the mock knows products and places numbered 1..1000. */
function refsExist(sp: Spec): void {
  sp.qualifying.forEach((q, i) => { if (q.product_id > 1000) bad(`body.qualifying[${i}]`, "not_found"); });
  sp.scope.forEach((n, i) => { if (n.node_id > 1000) bad(`body.scope[${i}]`, "not_found"); });
}
const decimalWire = (s: string): string => { const [w, f = ""] = s.split("."); return `${BigInt(w!)}.${f.padEnd(3, "0")}`; };
const specKey = (sp: Spec): string => fp({ ...sp, tiers: [...sp.tiers].sort((a, b) => a.sort_order - b.sort_order), qualifying: [...sp.qualifying].sort((a, b) => fp(a).localeCompare(fp(b))), scope: [...sp.scope].sort((a, b) => fp(a).localeCompare(fp(b))) });

interface EnrolRow { outlet: number; league: string | null; tier: string | null; target: string | null; from: string; to: string | null }
function readEnrolRows(o: Body, programmeId: number): EnrolRow[] {
  const a = o.rows;
  if (!Array.isArray(a)) bad("body.rows", "required");
  if ((a as unknown[]).length < 1 || (a as unknown[]).length > 20_000) bad("body.rows", "out_of_range");
  const seen = new Set<string>();
  return (a as unknown[]).map((e, i) => {
    if (e === null || typeof e !== "object" || Array.isArray(e)) bad(`body.rows[${i}]`, "not_an_object");
    const r = e as Body;
    only(r, ["programme_id", "outlet_id", "league_label", "tier_code", "base_target", "valid_from", "valid_to"]);
    const pid = idOf(r, "programme_id", true, `body.rows[${i}].programme_id`)!;
    if (pid !== programmeId) bad(`body.rows[${i}].programme_id`, "path_mismatch");
    const outlet = idOf(r, "outlet_id", true, `body.rows[${i}].outlet_id`)!;
    const league = str(r, "league_label", 0, 40, false, true, `body.rows[${i}].league_label`);
    const tier = str(r, "tier_code", 0, 41, false, true, `body.rows[${i}].tier_code`)?.trim() || null;
    if (tier !== null && !TIER_RE.test(tier)) bad(`body.rows[${i}].tier_code`, "pattern");
    let target: string | null = null;
    if (r.base_target !== undefined && r.base_target !== null) {
      if (typeof r.base_target !== "string" || !/^-?\d{1,13}(\.\d{1,3})?$/.test(r.base_target)) bad(`body.rows[${i}].base_target`, "not_a_decimal");
      if ((r.base_target as string).startsWith("-") && Number(r.base_target) !== 0) bad(`body.rows[${i}].base_target`, "negative"); // the -37,500% bug
      target = decimalWire((r.base_target as string).replace(/^-/, ""));
    }
    const from = ldate(r, "valid_from", `body.rows[${i}].valid_from`) ?? bad(`body.rows[${i}].valid_from`, "required");
    const to = ldate(r, "valid_to", `body.rows[${i}].valid_to`);
    if (to !== null && to <= from) bad(`body.rows[${i}].valid_to`, "before_valid_from"); // exclusive end
    if (seen.has(`${outlet}:${from}`)) bad(`body.rows[${i}]`, "duplicate");
    seen.add(`${outlet}:${from}`);
    return { outlet, league, tier, target, from, to };
  });
}

export async function handleLoyalty(s: LoyaltyStore, ctx: Ctx, me: LoyaltyCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const m = /^\/v1\/admin\/(?:programmes(?:\/([^/]+))?(?:\/(enrolments|versions|status))?|gifts(?:\/([^/]+))?)$/.exec(path);
  if (!m) return false;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown, headers?: Record<string, string>): true => { send(res, status, body, headers); return true; };
  const isGift = path.startsWith("/v1/admin/gifts");
  const sub = m[2] ?? null;
  const isRead = method === "GET";
  const access = loyaltyAccess(me);
  // Need.READ / READ_ENROLMENTS / WRITE / SUPER, in the server's order: the role comes before anything else.
  const needSuper = isGift && !isRead;
  const needWrite = !isRead;
  if (access === "none" || (needWrite && access !== "w") || (needSuper && me.role !== "SUPERADMIN")) return done(403, problem(403, "ERR_FORBIDDEN"));
  if (sub === "enrolments" && isRead && me.role === "BRAND") return done(403, problem(403, "ERR_FORBIDDEN")); // outlet data: a BRAND user reads programmes and gifts, not outlets
  const state = (what: string) => done(409, problem(409, "ERR_REQUEST_STATE", { detail: what } as never));
  const reused = (pointer: string) => done(409, problem(409, "ERR_CONFLICT", { errors: [{ pointer, code: "reused" }] } as never));
  const dup = () => done(409, problem(409, "ERR_MASTER_DUPLICATE_CODE", { errors: [{ pointer: "body.code", code: "duplicate" }] } as never));
  const notFound = () => done(404, problem(404, "ERR_NOT_FOUND"));
  const bodyOf = async (): Promise<Body> => {
    const b = (await ctx.readJson(req)) as unknown;
    if (b === undefined || b === null || typeof b !== "object" || Array.isArray(b)) bad("body", "invalid_body");
    return b as Body;
  };
  try {
    const q = url.searchParams;

    // =============================== gifts
    if (isGift) {
      const gidRaw = m[3] ?? null;
      const visGift = (g: Gift): boolean => visible(me, s.rows.find((r) => r.id === g.programme_id)!.versions[0]!);
      if (gidRaw === null && isRead) {
        const programme = queryId(q, "programme_id");
        const st = queryEnum(q, "status", ["active", "inactive"]);
        return done(200, { items: [...s.gifts].reverse().filter((g) => (programme === null || g.programme_id === programme) && (st === null || g.status === st) && visGift(g)).map(giftView) });
      }
      if (gidRaw !== null && isRead) {
        const g = s.gifts.find((x) => x.gift_id === pathId(gidRaw));
        return g && visGift(g) ? done(200, giftView(g), { ETag: `"${g.version}"` }) : notFound();
      }
      if (gidRaw === null && method === "POST") {
        const o = await bodyOf();
        only(o, ["request_uuid", "programme_id", "code", "name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status", "change_reason"]);
        const uuid = uuidOf(o, "request_uuid");
        const programmeId = idOf(o, "programme_id", true)!;
        const code = str(o, "code", 2, 41, true)!;
        if (!TCODE.test(code)) bad("body.code", "pattern");
        const nameEn = str(o, "name_en", 1, 120, true)!;
        const nameBn = str(o, "name_bn", 0, 120, false, true);
        const cost = lint(o, "points_cost", 1, 100_000, "body.points_cost", true);
        const tiers = o.tier_codes !== undefined ? readTierCodes(o) : [];
        const asset = readImage(o);
        const status = o.status !== undefined ? enumOf(o, "status", ["active", "inactive"], true)! : "active";
        const reason = reasonOf(o);
        const print = fp({ ...o, request_uuid: undefined, k: "gift_create" });
        const prior = s.giftUuids.get(uuid);
        if (prior) return prior.fp === print && prior.kind === "create" ? done(prior.status, prior.body) : reused("body.request_uuid");
        const prog = s.rows.find((r) => r.id === programmeId) ?? bad("body.programme_id", "not_found");
        if (prog.status === "inactive") return state("an ended programme takes no new gifts");
        const have = prog.versions[0]!.tiers.map((t) => t.tier_code);
        if (have.length > 0) tiers.forEach((t, i) => { if (!have.includes(t)) bad(`body.tier_codes[${i}]`, "unknown_tier"); });
        if (asset) checkImage(asset);
        if (s.gifts.some((g) => g.programme_id === programmeId && g.code === code)) return dup();
        const g: Gift = { gift_id: s.nextGiftId++, programme_id: programmeId, code, name_en: nameEn, name_bn: nameBn, points_cost: cost, tier_codes: tiers, image_url: null, asset, status: status as Gift["status"], version: 1 };
        s.gifts.push(g);
        ctx.audit("gift", g.gift_id, "create", {}, { code: g.code }, reason);
        const out = giftView(g);
        s.giftUuids.set(uuid, { kind: "create", fp: print, giftId: g.gift_id, status: 201, body: out });
        return done(201, out);
      }
      if (gidRaw !== null && method === "PATCH") {
        const id = pathId(gidRaw);
        const im = ifMatchOf(req.headers["if-match"]);
        const o = await bodyOf();
        only(o, ["request_uuid", "name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status", "change_reason"]);
        const uuid = uuidOf(o, "request_uuid");
        const reason = reasonOf(o);
        if (!["name_en", "name_bn", "points_cost", "tier_codes", "image_asset_id", "status"].some((k) => o[k] !== undefined)) bad("body", "no_change");
        const print = fp({ ...o, request_uuid: undefined, k: "gift_patch", id });
        const nameEn = o.name_en !== undefined ? str(o, "name_en", 1, 120, true) : null;
        const nameBn = o.name_bn !== undefined ? str(o, "name_bn", 0, 120, false, true) : undefined;
        const cost = o.points_cost !== undefined ? lint(o, "points_cost", 1, 100_000, "body.points_cost", true) : undefined;
        const tiers = o.tier_codes !== undefined ? readTierCodes(o) : null;
        const asset = o.image_asset_id !== undefined ? readImage(o) : undefined;
        const status = o.status !== undefined ? enumOf(o, "status", ["active", "inactive"], true) : null;
        const prior = s.giftUuids.get(uuid);
        if (prior) return prior.fp === print && prior.giftId === id && prior.kind === "update" ? done(prior.status, prior.body) : reused("body.request_uuid");
        const g = s.gifts.find((x) => x.gift_id === id);
        if (!g) return notFound();
        if (im !== g.version) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
        const next = { ...g, name_en: nameEn ?? g.name_en, name_bn: nameBn === undefined ? g.name_bn : nameBn, points_cost: cost === undefined ? g.points_cost : cost, tier_codes: tiers ?? g.tier_codes, status: (status ?? g.status) as Gift["status"], asset: asset === undefined ? g.asset : asset };
        if (next.name_en === g.name_en && next.name_bn === g.name_bn && next.points_cost === g.points_cost && fp(next.tier_codes) === fp(g.tier_codes) && next.status === g.status && next.asset === g.asset) bad("body", "no_change");
        if (tiers) { const all = s.rows.find((r) => r.id === g.programme_id)!.versions[0]!.tiers.map((t) => t.tier_code); if (all.length > 0) tiers.forEach((t, i) => { if (!all.includes(t)) bad(`body.tier_codes[${i}]`, "unknown_tier"); }); }
        if (asset && asset !== g.asset) checkImage(asset);
        Object.assign(g, next, { version: g.version + 1 });
        ctx.audit("gift", g.gift_id, "update", {}, { code: g.code }, reason);
        const out = giftView(g);
        s.giftUuids.set(uuid, { kind: "update", fp: print, giftId: id, status: 200, body: out });
        return done(200, out);
      }
      return done(405, problem(405 as never, "ERR_NOT_FOUND"));
    }

    // =============================== programmes
    const idRaw = m[1] ?? null;
    if (idRaw === null && isRead) {
      const st = queryEnum(q, "status", ["draft", "active", "inactive"]);
      const kd = queryEnum(q, "kind", KINDS);
      return done(200, { items: [...s.rows].reverse().filter((r) => (st === null || r.status === st) && (kd === null || r.kind === kd) && visible(me, r.versions[0]!)).map(view) });
    }

    if (idRaw === null && method === "POST") {
      const o = await bodyOf();
      if (o.status !== undefined) bad("body.status", "use_status_endpoint");
      only(o, [...RULE_MEMBERS, "request_uuid", "kind", "code", "change_reason"]);
      const uuid = uuidOf(o, "request_uuid");
      const kind = enumOf(o, "kind", KINDS, true)!;
      const code = str(o, "code", 1, 40, true)!;
      if (!PCODE.test(code)) bad("body.code", "pattern");
      const reason = reasonOf(o);
      for (const k of ["name_en", "active_from", "active_to"]) if (o[k] === undefined) bad(`body.${k}`, "required");
      const sp = readSpec(o, null);
      const print = fp({ ...o, request_uuid: undefined, k: "create" });
      const prior = s.uuids.get(uuid);
      if (prior) return prior.kind === "version" && prior.fp === print && prior.versionNo === 1 ? done(prior.status, prior.body) : reused("body.request_uuid");
      if (s.rows.some((r) => r.code === code)) return dup();
      refsExist(sp);
      const at = new Date().toISOString();
      const id = s.nextId++;
      const r: Row = { id, kind, code, status: "draft", version: 1, created_at: at, updated_at: at, versions: [] };
      r.versions.push(version(s.nextVersionId++, id, 1, { name_en: sp.nameEn, name_bn: sp.nameBn, period_label: sp.period, active_from: sp.from, active_to: sp.to, tiers: sp.tiers, qualifying: sp.qualifying, scope: sp.scope }, reason, at));
      s.rows.push(r);
      ctx.audit("programme", id, "create", {}, { code, status: "draft" }, reason);
      const out = view(r);
      s.uuids.set(uuid, { kind: "version", fp: print, programmeId: id, versionNo: 1, status: 201, body: out });
      return done(201, out);
    }

    const id = pathId(idRaw!);
    const row = s.rows.find((r) => r.id === id) ?? null;

    if (sub === "versions" && isRead) {
      const limit = pageLimit(q);
      const cursor = pageCursor(q);
      if (!row || !visible(me, row.versions[0]!)) return notFound();
      const all = row.versions.filter((v) => (cursor === null || v.version_no < cursor) && visible(me, v)); // newest first; each version judged on its OWN scope
      const page = all.slice(0, limit);
      return done(200, { items: page, next_cursor: all.length > limit ? String(page[page.length - 1]!.version_no) : null });
    }

    if (sub === null && isRead) return row && visible(me, row.versions[0]!) ? done(200, view(row), { ETag: `"${row.version}"` }) : notFound();

    if (sub === "enrolments" && isRead) {
      const limit = pageLimit(q);
      const cursor = pageCursor(q);
      const zone = queryId(q, "zone_id");
      if (!row || !visible(me, row.versions[0]!)) return notFound();
      const geo = !NATIONAL.includes(me.role);
      if (geo && zone !== null && !reachOf(me).includes(zone)) return done(403, problem(403, "ERR_FORBIDDEN")); // a zone outside the reach
      const all = [...s.enrolments].reverse().filter((e) => e.programme_id === id && (cursor === null || e.id < cursor) && (zone === null || outletZone(e.outlet_id) === zone) && (!geo || reachOf(me).includes(outletZone(e.outlet_id))));
      const page = all.slice(0, limit);
      return done(200, { items: page.map(({ id: _i, ...rest }) => { void _i; return rest; }), next_cursor: all.length > limit ? String(page[page.length - 1]!.id) : null });
    }

    if (sub === "enrolments" && method === "PUT") {
      const o = await bodyOf();
      only(o, ["batch_uuid", "rows", "change_reason"]);
      const batch = uuidOf(o, "batch_uuid");
      const reason = reasonOf(o);
      const rows = readEnrolRows(o, id);
      const print = fp({ ...o, batch_uuid: undefined, k: "enrol", id });
      const prior = s.batches.get(batch);
      if (prior) return prior.fp === print && prior.programmeId === id ? done(200, prior.body) : reused("body.batch_uuid");
      if (!row) return notFound();
      if (row.status === "inactive") return state("an ended programme takes no new enrolments");
      const tiers = row.versions[0]!.tiers.map((t) => t.tier_code);
      rows.forEach((r, i) => { if (r.tier !== null && tiers.length > 0 && !tiers.includes(r.tier)) bad(`body.rows[${i}].tier_code`, "unknown_tier"); });
      rows.forEach((r, i) => { if (r.outlet > 1000) bad(`body.rows[${i}].outlet_id`, "not_found"); }); // the mock knows outlets 1..1000
      let inserted = 0, updated = 0, unchanged = 0;
      const staged = [...s.enrolments];
      let nextEnrol = s.nextEnrolId;
      for (const r of rows) {
        const e: Enrol = { id: 0, programme_id: id, outlet_id: r.outlet, league_label: r.league, tier_code: r.tier, base_target: r.target, valid_from: r.from, valid_to: r.to };
        const same = staged.findIndex((o2) => o2.programme_id === id && o2.outlet_id === r.outlet && o2.valid_from === r.from);
        // The gist EXCLUDE: no overlapping window per programme and outlet. The end is exclusive, so back-to-back windows are allowed.
        const overlap = staged.some((o2, i) => i !== same && o2.programme_id === id && o2.outlet_id === r.outlet && o2.valid_from < (r.to ?? "9999-12-31") && r.from < (o2.valid_to ?? "9999-12-31"));
        if (overlap) return done(409, problem(409, "ERR_MASTER_OVERLAP"));
        if (same >= 0) { const cur = staged[same]!; if (fp({ ...cur, id: 0 }) === fp(e)) unchanged++; else { staged[same] = { ...e, id: cur.id }; updated++; } } else { staged.push({ ...e, id: nextEnrol++ }); inserted++; }
      }
      s.nextEnrolId = nextEnrol;
      s.enrolments.splice(0, s.enrolments.length, ...staged);
      ctx.audit("programme", id, "enrol", {}, { inserted, updated }, reason);
      const out = { batch_uuid: batch, inserted, updated, unchanged };
      s.batches.set(batch, { fp: print, programmeId: id, body: out });
      return done(200, out);
    }

    if (sub === null && method === "PATCH") {
      const im = ifMatchOf(req.headers["if-match"]);
      const o = await bodyOf();
      if (o.status !== undefined) bad("body.status", "use_status_endpoint");
      only(o, [...RULE_MEMBERS, "request_uuid", "change_reason"]); // kind and code are not members of ProgrammePatch
      const uuid = uuidOf(o, "request_uuid");
      const reason = reasonOf(o);
      if (!RULE_MEMBERS.some((k) => o[k] !== undefined)) bad("body", "no_change");
      const print = fp({ ...o, request_uuid: undefined, k: "patch", id });
      const prior = s.uuids.get(uuid);
      if (prior) return prior.kind === "version" && prior.fp === print && prior.programmeId === id ? done(prior.status, prior.body) : reused("body.request_uuid");
      if (!row) return notFound();
      if (row.status !== "draft") return state(`only a draft can change; an ${row.status} programme is final`); // 409 before the ETag is judged
      if (row.version !== im) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
      const cur = row.versions[0]!;
      const sp = readSpec(o, specOf(cur)); // the full body is judged only now
      if (specKey(sp) === specKey(specOf(cur))) bad("body", "no_change");
      refsExist(sp);
      const next = version(s.nextVersionId++, id, cur.version_no + 1, { name_en: sp.nameEn, name_bn: sp.nameBn, period_label: sp.period, active_from: sp.from, active_to: sp.to, tiers: sp.tiers, qualifying: sp.qualifying, scope: sp.scope }, reason, new Date().toISOString());
      cur.is_current = false;
      row.versions.unshift(next);
      row.version += 1;
      row.updated_at = next.created_at;
      ctx.audit("programme", id, "new_version", { version_no: cur.version_no }, { version_no: next.version_no }, reason);
      const out = view(row);
      s.uuids.set(uuid, { kind: "version", fp: print, programmeId: id, versionNo: next.version_no, status: 200, body: out });
      return done(200, out);
    }

    if (sub === "status" && method === "POST") {
      const o = await bodyOf();
      only(o, ["request_uuid", "to_status", "reason"]);
      const uuid = uuidOf(o, "request_uuid");
      const to = enumOf(o, "to_status", ["active", "inactive"], true)! as "active" | "inactive";
      const reason = reasonOf(o, "reason");
      const imRaw = req.headers["if-match"];
      if (to === "active" && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN")); // an ADMIN is refused before any state check
      const print = fp({ id, to, reason });
      const prior = s.uuids.get(uuid);
      if (prior) return prior.kind === "status" && prior.fp === print && prior.programmeId === id ? done(prior.status, prior.body) : reused("body.request_uuid"); // a replay is answered before If-Match is judged
      const imGiven = imRaw !== undefined ? ifMatchOf(imRaw) : null;
      if (!row) return notFound();
      if (imGiven !== null && imGiven !== row.version) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
      const allowed = (row.status === "draft" && to === "active") || (row.status === "active" && to === "inactive");
      if (!allowed) return state(`a ${row.status} programme cannot move to ${to}`);
      if (to === "active" && row.versions[0]!.active_to < dhakaToday()) return done(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "body.active_to", code: "past" }] } as never));
      const from = row.status;
      row.status = to;
      row.version += 1;
      row.updated_at = new Date().toISOString();
      ctx.audit("programme", id, to === "active" ? "activate" : "end", { status: from }, { status: to }, reason);
      const out = view(row);
      s.uuids.set(uuid, { kind: "status", fp: print, programmeId: id, status: 200, body: out });
      return done(200, out);
    }
    return done(405, problem(405 as never, "ERR_NOT_FOUND"));
  } catch (e) {
    if (e instanceof Bad) return done(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: e.pointer, code: e.code }] } as never));
    throw e;
  }
}

function readTierCodes(o: Body): string[] {
  const a = o.tier_codes;
  if (!Array.isArray(a)) bad("body.tier_codes", "not_an_array");
  if ((a as unknown[]).length > 10) bad("body.tier_codes", "out_of_range");
  const out = (a as unknown[]).map((e, i) => {
    if (typeof e !== "string") bad(`body.tier_codes[${i}]`, "invalid_type");
    if (!TIER_RE.test(e as string)) bad(`body.tier_codes[${i}]`, "pattern");
    return e as string;
  });
  if (new Set(out).size !== out.length) bad("body.tier_codes", "duplicate");
  return out;
}
function readImage(o: Body): string | null {
  const a = str(o, "image_asset_id", 36, 36, false, true);
  if (a !== null && !UUID.test(a)) bad("body.image_asset_id", "not_a_uuid");
  return a;
}
function checkImage(asset: string): void {
  const purpose = ASSETS[asset];
  if (purpose === undefined) bad("body.image_asset_id", "unknown_asset");
  if (purpose !== "gift_image") bad("body.image_asset_id", "wrong_purpose");
}
