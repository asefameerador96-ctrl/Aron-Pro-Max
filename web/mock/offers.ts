// Mock of the discount-programme API (contract 1.12.0: listOffers, getOffer, createOffer, updateOffer, setOfferStatus,
// listOfferVersions), shaped exactly by the contract until Lane A's routes (portal-discount-api) land on INT. In memory; reset with
// /__mock/reset. Rules it follows: SUPERADMIN writes; an ADMIN reads with `r`, writes with `w` on module `discounts` (modelled per
// username below); other roles 403 (the real API also lets NATIONAL/BRAND/WM/DMO/TSO read within scope: not modelled, Q9). Create
// makes a draft (version 1), a code that exists is 409, a repeat of request_uuid with the same body replays 201 and a different body is
// 409. Update is draft only (409 otherwise), needs If-Match (428-free: a bad one is 412), appends a version, refuses `status` (400).
// Status: draft -> active (SUPERADMIN, valid_to not in the past) and active -> inactive; anything else 409; replay by request_uuid.
import { createHash } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Role, Schemas } from "../src/contract/types";
import type { Ctx } from "./tables";

type Offer = Schemas["Offer"];
type OfferSkuUnit = Schemas["OfferSkuUnit"];
type OfferVersion = Schemas["OfferVersion"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The two SKUs of mock/master-seed.ts, as the server derives OfferSkuUnit (cigarettes: packet of 10 / 20 sticks). */
export const SKU_UNITS: Record<number, OfferSkuUnit> = {
  1: { sku_id: 1, base_unit: "stick", pack_factor: 10, field_unit: "packet" },
  2: { sku_id: 2, base_unit: "stick", pack_factor: 20, field_unit: "packet" },
};

type Version = OfferVersion;
interface Row { id: number; code: string; group_code: string; status: Offer["status"]; version: number; created_at: string; updated_at: string; versions: Version[] }
export interface OfferStore { rows: Row[]; nextId: number; nextVersionId: number; ops: Map<string, { fp: string; status: number; body: unknown }> }

const dhakaToday = (): string => new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);
const fp = (o: unknown): string => createHash("sha256").update(JSON.stringify(o)).digest("hex");
const chars = (s: string): number => Array.from(s.trim()).length;

function unitsOf(v: Pick<Version, "qualifying" | "reward_sku_id">): OfferSkuUnit[] {
  const ids = new Set<number>(v.qualifying.filter((q) => q.product_level === "sku").map((q) => Number(q.product_id)));
  if (v.reward_sku_id != null) ids.add(Number(v.reward_sku_id));
  return [...ids].flatMap((id) => (SKU_UNITS[id] ? [SKU_UNITS[id]] : []));
}

function version(id: number, offerId: number, no: number, w: Partial<Version> & Pick<Version, "offer_type" | "title_en" | "valid_from" | "valid_to">, reason: string, at: string): Version {
  const v: Version = { offer_version_id: id, offer_id: offerId, version_no: no, is_current: true, level: "line", title_bn: null, threshold_qty_base: null, discount_bp: null, discount_mtk_per_unit: null, reward_sku_id: null, reward_qty_base: null, qualifying: [], scope: [], stacking: "exclusive", change_reason: reason, created_at: at, ...w };
  v.sku_units = unitsOf(v);
  return v;
}

export function freshOfferStore(): OfferStore {
  const s: OfferStore = { rows: [], nextId: 1, nextVersionId: 1, ops: new Map() };
  const seed = (code: string, status: Offer["status"], w: Parameters<typeof version>[3], at: string) => {
    const id = s.nextId++;
    s.rows.push({ id, code, group_code: code.startsWith("RAMADAN") ? "RAMADAN" : code, status, version: 1, created_at: at, updated_at: at, versions: [version(s.nextVersionId++, id, 1, w, "Seed programme for the portal mock", at)] });
  };
  seed("RAMADAN-PO", "active", { offer_type: "pct_discount", title_en: "Ramadan price off", title_bn: "রমজান মূল্য ছাড়", discount_bp: 250, valid_from: "2026-11-01", valid_to: "2099-12-31", qualifying: [{ product_level: "brand", product_id: 1 }] }, "2026-10-30T05:00:00.000Z");
  seed("MAXR-PKT", "draft", { offer_type: "amount_per_unit", title_en: "Max Royal 20s price off per packet", title_bn: "ম্যাক্স রয়্যাল ২০ শলাকার প্যাকেট প্রতি বিশেষ মূল্য ছাড় কর্মসূচি, ঢাকা উইংয়ের সকল পরিবেশক ও খুচরা বিক্রেতার জন্য প্রযোজ্য", discount_mtk_per_unit: 100, valid_from: "2026-11-15", valid_to: "2099-12-31", qualifying: [{ product_level: "sku", product_id: 2 }], scope: [{ node_type: "wing", node_id: 1 }] }, "2026-11-02T05:00:00.000Z");
  seed("BUY3-GET1", "inactive", { offer_type: "free_qty", title_en: "Buy 3 packets get 1 free", threshold_qty_base: 60, reward_sku_id: 2, reward_qty_base: 20, valid_from: "2026-09-01", valid_to: "2026-09-30", qualifying: [{ product_level: "sku", product_id: 2 }] }, "2026-08-28T05:00:00.000Z");
  return s;
}

export function offerView(r: Row): Offer {
  const v = r.versions[0]!;
  const o: Offer = { id: r.id, code: r.code, group_code: r.group_code, offer_type: v.offer_type, level: v.level, title_en: v.title_en, title_bn: v.title_bn ?? null, valid_from: v.valid_from, valid_to: v.valid_to, threshold_qty_base: v.threshold_qty_base ?? null, discount_bp: v.discount_bp ?? null, discount_mtk_per_unit: v.discount_mtk_per_unit ?? null, reward_sku_id: v.reward_sku_id ?? null, reward_qty_base: v.reward_qty_base ?? null, qualifying: v.qualifying, scope: v.scope, stacking: v.stacking, status: r.status, version: r.version, offer_version_id: v.offer_version_id, version_no: v.version_no, change_reason: v.change_reason ?? null, created_at: r.created_at, updated_at: r.updated_at, sku_units: v.sku_units ?? [] };
  return o;
}

export interface OfferCaller { role: Role; username: string }
/** r / w / none of the caller on module `discounts` (mdisc1 is the ADMIN with a read grant; mgrant1 has no grant on it). */
export function offerAccess(c: OfferCaller): "none" | "r" | "w" {
  if (c.role === "SUPERADMIN") return "w";
  if (c.role !== "ADMIN") return "none";
  return c.username === "mgrant1" ? "none" : c.username === "mdisc1" ? "r" : "w";
}

const RULE_KEYS = ["offer_type", "level", "title_en", "title_bn", "valid_from", "valid_to", "threshold_qty_base", "discount_bp", "discount_mtk_per_unit", "reward_sku_id", "reward_qty_base", "qualifying", "scope", "stacking"] as const;

type Body = Record<string, unknown>;
function ruleErrors(b: Body, requireAll: boolean): { pointer: string; code: string }[] {
  const errs: { pointer: string; code: string }[] = [];
  const bad = (pointer: string, code = "invalid") => errs.push({ pointer: `/${pointer}`, code });
  if (b.title_en !== undefined && (typeof b.title_en !== "string" || !b.title_en.trim() || b.title_en.length > 200)) bad("title_en");
  if (b.title_bn != null && (typeof b.title_bn !== "string" || b.title_bn.length > 200)) bad("title_bn");
  for (const k of ["valid_from", "valid_to"] as const) if (b[k] !== undefined && (typeof b[k] !== "string" || !DAY.test(b[k] as string))) bad(k);
  if (b.offer_type !== undefined && !["pct_discount", "amount_per_unit", "free_qty", "drp_slide"].includes(String(b.offer_type))) bad("offer_type");
  if (b.offer_type === "drp_slide") bad("offer_type", "not_supported");
  if (b.level !== undefined && !["line", "memo"].includes(String(b.level))) bad("level");
  if (b.stacking !== undefined && !["exclusive", "stackable"].includes(String(b.stacking))) bad("stacking");
  const int = (k: string, min: number, max: number) => { if (b[k] != null && (!Number.isInteger(b[k]) || (b[k] as number) < min || (b[k] as number) > max)) bad(k); };
  int("discount_bp", 1, 10_000); int("discount_mtk_per_unit", 1, Number.MAX_SAFE_INTEGER); int("threshold_qty_base", 1, 2_147_483_647); int("reward_sku_id", 1, Number.MAX_SAFE_INTEGER); int("reward_qty_base", 1, 2_147_483_647);
  if (b.qualifying !== undefined && (!Array.isArray(b.qualifying) || b.qualifying.length > 100)) bad("qualifying");
  if (b.scope !== undefined && (!Array.isArray(b.scope) || b.scope.length > 200)) bad("scope");
  if (Array.isArray(b.scope) && b.scope.some((s) => (s as Body).node_type === "channel")) bad("scope", "not_supported");
  if (typeof b.change_reason !== "string" || chars(b.change_reason) < 10 || chars(b.change_reason) > 500) bad("change_reason");
  if (requireAll) {
    for (const k of ["offer_type", "level", "title_en", "valid_from", "valid_to", "qualifying", "scope", "stacking"]) if (b[k] === undefined) bad(k, "required");
  }
  return errs;
}

function typeRuleErrors(v: Pick<Version, "offer_type" | "discount_bp" | "discount_mtk_per_unit" | "threshold_qty_base" | "reward_sku_id" | "reward_qty_base">): { pointer: string; code: string }[] {
  const e: { pointer: string; code: string }[] = [];
  if (v.offer_type === "pct_discount" && v.discount_bp == null) e.push({ pointer: "/discount_bp", code: "required" });
  if (v.offer_type === "amount_per_unit" && v.discount_mtk_per_unit == null) e.push({ pointer: "/discount_mtk_per_unit", code: "required" });
  if (v.offer_type === "free_qty") for (const k of ["reward_sku_id", "reward_qty_base", "threshold_qty_base"] as const) if (v[k] == null) e.push({ pointer: `/${k}`, code: "required" });
  return e;
}

export async function handleOffers(s: OfferStore, ctx: Ctx, me: OfferCaller, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const m = /^\/v1\/admin\/offers(?:\/(\d+))?(?:\/(versions|status))?$/.exec(path);
  if (!m) return false;
  const { send, problem } = ctx;
  const done = (status: number, body: unknown): true => { send(res, status, body); return true; };
  const access = offerAccess(me);
  const id = m[1] ? Number(m[1]) : null;
  const sub = m[2] ?? null;
  const isRead = method === "GET";
  if (access === "none" || (!isRead && access !== "w")) return done(403, problem(403, "ERR_FORBIDDEN"));
  const row = id === null ? null : s.rows.find((r) => r.id === id) ?? null;
  const validation = (errors: { pointer: string; code: string }[]) => done(400, problem(400, "ERR_VALIDATION", { errors } as never));

  if (id === null && method === "GET") {
    const p = url.searchParams;
    const status = p.get("status");
    if (status !== null && !["draft", "active", "inactive"].includes(status)) return validation([{ pointer: "/status", code: "invalid" }]);
    const limit = Number(p.get("limit") ?? 50);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) return validation([{ pointer: "/limit", code: "invalid" }]);
    const validOn = p.get("valid_on");
    const all = [...s.rows].reverse().filter((r) => (!status || r.status === status) && (!validOn || (r.versions[0]!.valid_from <= validOn && validOn <= r.versions[0]!.valid_to)));
    const cur = p.get("cursor");
    const start = cur ? Number(cur.replace(/^c/, "")) || 0 : 0;
    const page = all.slice(start, start + limit);
    return done(200, { items: page.map(offerView), next_cursor: start + limit < all.length ? `c${start + limit}` : null });
  }

  if (id === null && method === "POST") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    const errs = ruleErrors(b, true);
    if (typeof b.code !== "string" || !CODE.test(b.code)) errs.push({ pointer: "/code", code: "invalid" });
    if (typeof b.group_code !== "string" || b.group_code.length > 40) errs.push({ pointer: "/group_code", code: "invalid" });
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "/request_uuid", code: "required" });
    if (typeof b.valid_from === "string" && typeof b.valid_to === "string" && b.valid_to < b.valid_from) errs.push({ pointer: "/valid_to", code: "range" });
    errs.push(...typeRuleErrors(b as never));
    if (errs.length) return validation(errs);
    const key = `create:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    if (s.rows.some((r) => r.code === b.code)) return done(409, problem(409, "ERR_CONFLICT"));
    const at = new Date().toISOString();
    const oid = s.nextId++;
    const w = b as unknown as Version & { change_reason: string };
    const r: Row = { id: oid, code: String(b.code), group_code: String(b.group_code), status: "draft", version: 1, created_at: at, updated_at: at, versions: [] };
    r.versions.push(version(s.nextVersionId++, oid, 1, { ...Object.fromEntries(RULE_KEYS.filter((k) => w[k] !== undefined).map((k) => [k, w[k]])) } as never, w.change_reason, at));
    s.rows.push(r);
    ctx.audit("offer", oid, "create", {}, { code: r.code, status: "draft" }, w.change_reason);
    const body = offerView(r);
    s.ops.set(key, { fp: print, status: 201, body });
    return done(201, body);
  }

  if (!row) return done(404, problem(404, "ERR_NOT_FOUND"));

  if (sub === "versions" && method === "GET") {
    const limit = Number(url.searchParams.get("limit") ?? 50);
    return done(200, { items: row.versions.slice(0, limit), next_cursor: null });
  }

  if (sub === null && method === "GET") return send(res, 200, offerView(row), { ETag: `"${row.version}"` }), true;

  if (sub === null && method === "PATCH") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    if ("status" in b) return validation([{ pointer: "/status", code: "not_supported" }]);
    const errs = ruleErrors(b, false);
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "/request_uuid", code: "required" });
    if (Object.keys(b).filter((k) => k !== "request_uuid" && k !== "change_reason").length === 0) errs.push({ pointer: "", code: "too_short" });
    if (errs.length) return validation(errs);
    const key = `update:${b.request_uuid as string}`;
    const print = fp({ ...b, request_uuid: undefined, id });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    const ifMatch = req.headers["if-match"];
    if (typeof ifMatch !== "string" || !/^"[0-9]{1,10}"$/.test(ifMatch)) return done(400, problem(400, "ERR_VALIDATION"));
    if (ifMatch !== `"${row.version}"`) return done(412, problem(412, "ERR_PRECONDITION_FAILED"));
    if (row.status !== "draft") return done(409, problem(409, "ERR_CONFLICT"));
    const cur = row.versions[0]!;
    const next: Version = { ...cur, ...Object.fromEntries(RULE_KEYS.filter((k) => b[k] !== undefined).map((k) => [k, b[k]])), offer_version_id: s.nextVersionId++, version_no: cur.version_no + 1, change_reason: String(b.change_reason), created_at: new Date().toISOString(), is_current: true } as Version;
    if (next.valid_to < next.valid_from) return validation([{ pointer: "/valid_to", code: "range" }]);
    const te = typeRuleErrors(next);
    if (te.length) return validation(te);
    next.sku_units = unitsOf(next);
    cur.is_current = false;
    row.versions.unshift(next);
    row.version += 1;
    row.updated_at = next.created_at;
    ctx.audit("offer", row.id, "new_version", { version_no: cur.version_no }, { version_no: next.version_no }, next.change_reason ?? null);
    const body = offerView(row);
    s.ops.set(key, { fp: print, status: 200, body });
    return done(200, body);
  }

  if (sub === "status" && method === "POST") {
    const b = (await ctx.readJson(req)) as Body | undefined;
    const errs: { pointer: string; code: string }[] = [];
    if (!b || typeof b !== "object") return validation([{ pointer: "", code: "malformed" }]);
    if (typeof b.request_uuid !== "string" || !UUID.test(b.request_uuid)) errs.push({ pointer: "/request_uuid", code: "required" });
    if (b.to_status !== "active" && b.to_status !== "inactive") errs.push({ pointer: "/to_status", code: "invalid" });
    if (typeof b.reason !== "string" || chars(b.reason) < 10 || chars(b.reason) > 500) errs.push({ pointer: "/reason", code: "invalid" });
    if (errs.length) return validation(errs);
    const key = `status:${b.request_uuid as string}`;
    const print = fp({ id, to: b.to_status, reason: b.reason });
    const seen = s.ops.get(key);
    if (seen) return seen.fp === print ? done(seen.status, seen.body) : done(409, problem(409, "ERR_CONFLICT"));
    const to = b.to_status as "active" | "inactive";
    const allowed = (row.status === "draft" && to === "active") || (row.status === "active" && to === "inactive");
    if (!allowed) return done(409, problem(409, "ERR_CONFLICT"));
    if (to === "active" && me.role !== "SUPERADMIN") return done(403, problem(403, "ERR_FORBIDDEN"));
    if (to === "active" && row.versions[0]!.valid_to < dhakaToday()) return validation([{ pointer: "/valid_to", code: "past" }]);
    const from = row.status;
    row.status = to;
    row.version += 1;
    row.updated_at = new Date().toISOString();
    ctx.audit("offer", row.id, to === "active" ? "activate" : "end", { status: from }, { status: to }, String(b.reason));
    const body = offerView(row);
    s.ops.set(key, { fp: print, status: 200, body });
    return done(200, body);
  }
  return done(405, problem(405 as never, "ERR_NOT_FOUND"));
}
