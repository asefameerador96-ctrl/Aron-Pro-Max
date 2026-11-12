// Loyalty programmes (portal-loyalty-web, D-LOY-WEB-01). Pure logic, no I/O: the role matrix (one place), exact decimal and integer
// helpers (BigInt, never a float: STD targets are Decimal3 strings, points are integers, money is integer milli-taka text), the
// form checks and the mapping from the form to the wire bodies. Wire shapes are the generated contract 1.13.0 types (D-LOY-WEB-03).
import type { Role, Schemas } from "@/contract/types";
import { accessOf, type ModuleAccess, type ModuleGrants } from "./modules";
import { displayUnit, type DisplayUnit } from "./offers";
import { latinDigits } from "./taka";

// ---------- wire types (generated from contract 1.13.0: a drift fails tsc) ----------

export type Programme = Schemas["Programme"];
export type ProgrammeVersion = Schemas["ProgrammeVersion"];
export type ProgrammeWrite = Schemas["ProgrammeWrite"];
export type ProgrammePatch = Schemas["ProgrammePatch"];
export type Tier = Schemas["ProgrammeTier"];
export type ScopeNode = Schemas["LoyaltyScopeNode"];
export type Gift = Schemas["Gift"];
export type GiftWrite = Schemas["GiftWrite"];
export type GiftPatch = Schemas["GiftPatch"];
export type Enrolment = Schemas["ProgrammeEnrolment"];
export type ProgrammeKind = Schemas["ProgrammeKind"];
export type ProgrammeStatus = Schemas["ProgrammeStatus"];
export const PROGRAMME_KINDS = ["astha", "diamond_league", "superstar", "campaign"] as const satisfies readonly ProgrammeKind[];
export const PROGRAMME_STATUSES = ["draft", "active", "inactive"] as const satisfies readonly ProgrammeStatus[];
/** Q1 default: thresholds are STD as stored. */
export type ThresholdBasis = "std";

// ---------- role matrix (one place) ----------

/** v1 gate as for discounts: SUPERADMIN and an ADMIN with a `loyalty` grant. NATIONAL, BRAND, WM, DMO and TSO may read within their scope
 *  on the server but the admin shell is for the portal roles only; adding them is this constant plus ADMIN_PATH_ROLES (roles.ts). */
export const LOYALTY_READ_ROLES = ["SUPERADMIN", "ADMIN"] as const satisfies readonly Role[];

export function loyaltyAccess(role: Role | undefined, grants: ModuleGrants | null): ModuleAccess {
  if (role === "SUPERADMIN") return "w";
  if (role === "ADMIN") return accessOf(grants, "loyalty");
  return "none";
}
export const canReadLoyalty = (role: Role | undefined, grants: ModuleGrants | null): boolean => loyaltyAccess(role, grants) !== "none";
/** Edit drafts, enrol outlets, end a programme. */
export const canWriteLoyalty = (role: Role | undefined, grants: ModuleGrants | null): boolean => loyaltyAccess(role, grants) === "w";
/** draft -> active is Super Admin only (Q3 default, AdminOffers precedent). */
export const canActivateProgramme = (role: Role | undefined): boolean => role === "SUPERADMIN";
/** Gift catalogue changes are Super Admin only (brief s3, Q3 default). */
export const canWriteGifts = (role: Role | undefined): boolean => role === "SUPERADMIN";

/** The status moves the buttons may offer (the server decides): draft -> active, active -> inactive. */
export function nextProgrammeStatuses(status: ProgrammeStatus, role: Role | undefined, grants: ModuleGrants | null): ("active" | "inactive")[] {
  const out: ("active" | "inactive")[] = [];
  if (status === "draft" && canActivateProgramme(role)) out.push("active");
  if (status === "active" && canWriteLoyalty(role, grants)) out.push("inactive");
  return out;
}

// ---------- exact numbers ----------

export const REASON_MIN = 10;
export const REASON_MAX = 500;
export const PROGRAMME_CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;
export const TIER_CODE = /^[a-z][a-z0-9_]{1,40}$/;
export const GIFT_CODE = /^[a-z][a-z0-9_]{1,40}$/;
/** A path id: at least 1, no leading zeros. */
export const ID_RE = /^[1-9]\d{0,14}$/;
/** A page cursor from the URL: numeric keyset value >= 1 (the server answers 400 query.cursor to anything else), else none. */
export const cursorParam = (v: string | undefined): string | undefined => (v !== undefined && /^[1-9]\d{0,17}$/.test(v) ? v : undefined);
export const MAX_TIERS = 10; // contract: tiers maxItems 10, sort_order 1..10
export const POINTS_MAX = 100_000;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Decimal3 text (up to 13 whole digits, up to 3 fraction digits, not negative) to thousandths; null when invalid. Bangla digits accepted. */
export function parseDecimal3(text: string): bigint | null {
  const s = latinDigits(text.trim());
  const m = /^(\d{1,13})(?:\.(\d{1,3}))?$/.exec(s);
  if (!m) return null;
  return BigInt(m[1]!) * 1000n + BigInt((m[2] ?? "").padEnd(3, "0"));
}
/** Thousandths to the wire's Decimal3 text, always three decimals ("1500.000"). */
export const decimal3Wire = (v: bigint): string => `${v / 1000n}.${(v % 1000n).toString().padStart(3, "0")}`;
/** Thousandths to short text with only the digits needed ("1500", "1500.5"); grouping is the caller's. */
export function decimal3Text(v: bigint): string {
  const frac = (v % 1000n).toString().padStart(3, "0").replace(/0+$/, "");
  return frac ? `${v / 1000n}.${frac}` : String(v / 1000n);
}
/** Achievement as percent text with two decimals from exact integers, or null when the target is not above zero (a negative Astha target
 *  once gave -37,500%: docs/06; every percent guards the divide). Floors; no float anywhere. */
export function guardedPercent(achieved: string, target: string): string | null {
  const a = parseDecimal3(achieved);
  const t = parseDecimal3(target);
  if (a === null || t === null || t <= 0n) return null;
  const bp = (a * 10_000n) / t;
  return `${bp / 100n}.${(bp % 100n).toString().padStart(2, "0")}`;
}
/** Remaining = target - achieved, never below zero. Thousandths text, or null when either is unreadable. */
export function remainingStd(achieved: string, target: string): string | null {
  const a = parseDecimal3(achieved);
  const t = parseDecimal3(target);
  if (a === null || t === null) return null;
  return decimal3Text(t > a ? t - a : 0n);
}
/** Cash value of points in integer milli-taka text (points x milli-taka per point), BigInt; null for a non-integer or negative input. */
export function cashValueMtk(points: number, mtkPerPoint: number): string | null {
  if (!Number.isSafeInteger(points) || points < 0 || !Number.isSafeInteger(mtkPerPoint) || mtkPerPoint < 0) return null;
  return (BigInt(points) * BigInt(mtkPerPoint)).toString();
}
/** The unit word of a threshold basis: STD for the stored basis, else the field-unit word of the SKU category (displayUnit). */
export function basisUnit(basis: ThresholdBasis | "pack" | "dozen" | "piece", categoryCode?: string): DisplayUnit | "std" {
  if (basis === "std") return "std";
  return displayUnit({ field_unit: basis, base_unit: basis === "piece" ? "piece" : "stick", category_code: categoryCode });
}

// ---------- the form ----------

export interface TierForm { tier_code: string; label_en: string; label_bn: string; threshold: string }
export interface LoyaltyForm {
  kind: ProgrammeKind;
  code: string;
  name_en: string;
  name_bn: string;
  period_label: string;
  active_from: string;
  active_to: string;
  tiers: TierForm[];
  reason: string;
}
export const emptyForm = (): LoyaltyForm => ({ kind: "astha", code: "", name_en: "", name_bn: "", period_label: "", active_from: "", active_to: "", tiers: [], reason: "" });
export const emptyTier = (): TierForm => ({ tier_code: "", label_en: "", label_bn: "", threshold: "" });

export function formFromProgramme(p: Programme): LoyaltyForm {
  return {
    kind: p.kind, code: p.code, name_en: p.name_en, name_bn: p.name_bn ?? "", period_label: p.period_label ?? "", active_from: p.active_from, active_to: p.active_to,
    tiers: [...(p.tiers ?? [])].sort((x, y) => x.sort_order - y.sort_order).map((t) => ({ tier_code: t.tier_code, label_en: t.label_en, label_bn: t.label_bn ?? "", threshold: t.threshold == null ? "" : decimal3Text(parseDecimal3(t.threshold) ?? 0n) })),
    reason: "",
  };
}

export type FieldErrorCode = "required" | "invalid" | "pattern" | "too_long" | "range" | "duplicate" | "not_rising" | "too_many" | "reason_short" | "reason_long" | "unknown_tier" | "not_found";
export interface FieldError { field: string; code: FieldErrorCode }
const len = (s: string): number => Array.from(s.trim()).length;
const isoDay = (s: string): boolean => DATE.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

/** Shared checks of a create and an edit (the contract's patterns and lengths; thresholds rising by tier, brief s7). A create is a
 *  ProgrammeWrite; an edit is a ProgrammePatch (kind and code never change; scope and qualifying are left out so they keep their value). */
export function validateForm(f: LoyaltyForm, mode: "create" | "edit", requestUuid: string): { errors: FieldError[]; body: ProgrammeWrite | ProgrammePatch | null } {
  const errors: FieldError[] = [];
  const err = (field: string, code: FieldErrorCode) => errors.push({ field, code });
  const code = f.code.trim();
  if (mode === "create") {
    if (!code) err("code", "required");
    else if (!PROGRAMME_CODE.test(code)) err("code", "pattern");
  }
  if (!f.name_en.trim()) err("name_en", "required");
  else if (len(f.name_en) > 120) err("name_en", "too_long");
  if (len(f.name_bn) > 120) err("name_bn", "too_long");
  if (len(f.period_label) > 40) err("period_label", "too_long");
  if (!isoDay(f.active_from)) err("active_from", f.active_from ? "invalid" : "required");
  if (!isoDay(f.active_to)) err("active_to", f.active_to ? "invalid" : "required");
  else if (isoDay(f.active_from) && f.active_to < f.active_from) err("active_to", "range");
  const r = len(f.reason);
  if (r < REASON_MIN) err("reason", "reason_short");
  else if (r > REASON_MAX) err("reason", "reason_long");
  if (f.tiers.length > MAX_TIERS) err("tiers", "too_many");
  const seen = new Set<string>();
  const tiers: Tier[] = [];
  let last: bigint | null = null;
  f.tiers.forEach((t, i) => {
    const k = (n: string) => `tier.${i}.${n}`;
    const tc = t.tier_code.trim();
    if (!tc) err(k("tier_code"), "required");
    else if (!TIER_CODE.test(tc)) err(k("tier_code"), "pattern");
    else if (seen.has(tc)) err(k("tier_code"), "duplicate");
    seen.add(tc);
    if (!t.label_en.trim()) err(k("label_en"), "required");
    else if (len(t.label_en) > 80) err(k("label_en"), "too_long");
    if (len(t.label_bn) > 80) err(k("label_bn"), "too_long");
    let value: bigint | null = null;
    if (t.threshold.trim()) {
      value = parseDecimal3(t.threshold);
      if (value === null) err(k("threshold"), "invalid");
      else if (last !== null && value <= last) err(k("threshold"), "not_rising");
      if (value !== null) last = value;
    }
    tiers.push({ tier_code: tc, sort_order: i + 1, label_en: t.label_en.trim(), label_bn: t.label_bn.trim() ? t.label_bn.trim() : null, threshold: value === null ? null : decimal3Wire(value), threshold_basis: "std" });
  });
  if (errors.length > 0) return { errors, body: null };
  const common = {
    request_uuid: requestUuid, name_en: f.name_en.trim(), name_bn: f.name_bn.trim() ? f.name_bn.trim() : null, period_label: f.period_label.trim() ? f.period_label.trim() : null,
    active_from: f.active_from, active_to: f.active_to, tiers, change_reason: f.reason.trim(),
  };
  return { errors, body: mode === "create" ? { ...common, kind: f.kind, code } : common };
}

// ---------- gift form ----------

export interface GiftForm { programme_id: string; code: string; name_en: string; name_bn: string; points: string; tier_codes: string[]; status: "active" | "inactive"; reason: string }
export const emptyGiftForm = (programmeId = ""): GiftForm => ({ programme_id: programmeId, code: "", name_en: "", name_bn: "", points: "", tier_codes: [], status: "active", reason: "" });
export const giftFormFrom = (g: Gift): GiftForm => ({ programme_id: String(g.programme_id), code: g.code, name_en: g.name_en, name_bn: g.name_bn ?? "", points: g.points_cost === null ? "" : String(g.points_cost), tier_codes: [...(g.tier_codes ?? [])], status: g.status, reason: "" });

/** points_cost is an integer 1..100000 or empty (Astha gifts are chosen, not redeemed). */
export function parsePoints(text: string): number | null | "invalid" {
  const s = latinDigits(text.trim());
  if (s === "") return null;
  if (!/^\d{1,6}$/.test(s)) return "invalid";
  const n = Number(s);
  return n >= 1 && n <= POINTS_MAX ? n : "invalid";
}

export function validateGift(f: GiftForm, mode: "create" | "edit", requestUuid: string): { errors: FieldError[]; body: GiftWrite | GiftPatch | null } {
  const errors: FieldError[] = [];
  const err = (field: string, code: FieldErrorCode) => errors.push({ field, code });
  const pidText = latinDigits(f.programme_id.trim());
  const pid = Number(pidText);
  if (!pidText) err("programme_id", "required");
  else if (!/^\d{1,15}$/.test(pidText) || pid < 1) err("programme_id", "invalid");
  const code = f.code.trim();
  if (mode === "create") {
    if (!code) err("code", "required");
    else if (!GIFT_CODE.test(code)) err("code", "pattern");
  }
  if (!f.name_en.trim()) err("name_en", "required");
  else if (len(f.name_en) > 120) err("name_en", "too_long");
  if (len(f.name_bn) > 120) err("name_bn", "too_long");
  const points = parsePoints(f.points);
  if (points === "invalid") err("points", "range");
  if (f.tier_codes.length > 10) err("tier_codes", "too_many");
  const r = len(f.reason);
  if (r < REASON_MIN) err("reason", "reason_short");
  else if (r > REASON_MAX) err("reason", "reason_long");
  if (errors.length > 0 || points === "invalid") return { errors, body: null };
  const common = { request_uuid: requestUuid, name_en: f.name_en.trim(), name_bn: f.name_bn.trim() ? f.name_bn.trim() : null, points_cost: points, tier_codes: [...f.tier_codes], status: f.status, change_reason: f.reason.trim() };
  // The code and the programme of a gift never change (GiftPatch is closed): an edit sends neither.
  return { errors, body: mode === "create" ? { ...common, programme_id: pid, code } : common };
}

// ---------- enrolment form ----------

export interface EnrolForm { outlet_id: string; tier_code: string; league_label: string; base_target: string; valid_from: string; valid_to: string; reason: string }
export const emptyEnrolForm = (): EnrolForm => ({ outlet_id: "", tier_code: "", league_label: "", base_target: "", valid_from: "", valid_to: "", reason: "" });

export function validateEnrol(f: EnrolForm, programmeId: number, batchUuid: string): { errors: FieldError[]; body: { batch_uuid: string; rows: Enrolment[]; change_reason: string } | null } {
  const errors: FieldError[] = [];
  const err = (field: string, code: FieldErrorCode) => errors.push({ field, code });
  const outlet = Number(latinDigits(f.outlet_id.trim()));
  if (!f.outlet_id.trim()) err("outlet_id", "required");
  else if (!Number.isSafeInteger(outlet) || outlet < 1) err("outlet_id", "invalid");
  if (f.tier_code && !TIER_CODE.test(f.tier_code)) err("tier_code", "pattern");
  if (len(f.league_label) > 40) err("league_label", "too_long");
  let target: bigint | null = null;
  if (f.base_target.trim()) {
    target = parseDecimal3(f.base_target);
    if (target === null) err("base_target", "invalid"); // targets are never negative (docs/06: -37,500%)
  }
  if (!isoDay(f.valid_from)) err("valid_from", f.valid_from ? "invalid" : "required");
  if (f.valid_to && !isoDay(f.valid_to)) err("valid_to", "invalid");
  else if (f.valid_to && isoDay(f.valid_from) && f.valid_to <= f.valid_from) err("valid_to", "range"); // the end is exclusive: it must be after the start
  const r = len(f.reason);
  if (r < REASON_MIN) err("reason", "reason_short");
  else if (r > REASON_MAX) err("reason", "reason_long");
  if (errors.length > 0) return { errors, body: null };
  return {
    errors,
    body: {
      batch_uuid: batchUuid,
      rows: [{ programme_id: programmeId, outlet_id: outlet, league_label: f.league_label.trim() ? f.league_label.trim() : null, tier_code: f.tier_code ? f.tier_code : null, base_target: target === null ? null : decimal3Wire(target), valid_from: f.valid_from, valid_to: f.valid_to ? f.valid_to : null }],
      change_reason: f.reason.trim(),
    },
  };
}

/** request_uuid per submission: the same body keeps the uuid across a retry, a changed body gets a new one. */
export function nextAttempt(prev: { key: string; uuid: string } | null, key: string, make: () => string = () => crypto.randomUUID()): { key: string; uuid: string } {
  return prev?.key === key ? prev : { key, uuid: make() };
}

/** Activation answered on the client so the button says why: the active window must not have ended (Asia/Dhaka date). */
export const activationBlocked = (activeTo: string, today: string): boolean => activeTo < today;

// ---------- what the server says back (D-LOY-WEB-04) ----------

/** The form field a server pointer names. The server writes "body.tiers[0].threshold", "body.rows[0].outlet_id", "header.If-Match"; the BFF's own
 *  zod errors write "/tiers/0/threshold". Both styles map to the form's field names; null = not a field (shown in the banner). */
export function pointerToField(pointer: string, form: "programme" | "gift" | "enrol"): string | null {
  const p = pointer.replace(/^\/+/, "").replace(/^body[./]?/, "").replace(/\[(\d+)\]/g, ".$1").replace(/\//g, ".");
  if (p === "" || pointer.startsWith("header.") || pointer.startsWith("query.")) return null;
  if (form === "enrol") {
    const m = /^rows\.\d+\.(\w+)$/.exec(p);
    const k = m ? m[1]! : p === "change_reason" ? "reason" : p; // a flat member names the only row of this one-row form
    return k && ["outlet_id", "tier_code", "league_label", "base_target", "valid_from", "valid_to", "reason"].includes(k) ? k : null;
  }
  if (form === "gift") {
    const k = p === "change_reason" ? "reason" : p === "points_cost" ? "points" : /^tier_codes(\.\d+)?$/.test(p) ? "tier_codes" : p;
    return ["programme_id", "code", "name_en", "name_bn", "points", "tier_codes", "status", "reason"].includes(k) ? k : null;
  }
  if (p === "change_reason") return "reason";
  const t = /^tiers\.(\d+)\.(tier_code|label_en|label_bn|threshold|sort_order)$/.exec(p);
  if (t) return `tier.${t[1]}.${t[2] === "sort_order" ? "tier_code" : t[2]}`;
  if (p === "tiers") return "tiers";
  return ["kind", "code", "name_en", "name_bn", "period_label", "active_from", "active_to"].includes(p) ? p : null;
}

/** The server's field-error code as the form's message code. */
export function serverFieldCode(code: string): FieldErrorCode {
  switch (code) {
    case "required": return "required";
    case "pattern": return "pattern";
    case "length": return "too_long";
    case "out_of_range": case "before_active_from": case "before_valid_from": case "before_active_to": case "past": return "range";
    case "thresholds_not_rising": case "not_rising": return "not_rising";
    case "duplicate": case "duplicate_tier_code": case "duplicate_sort_order": return "duplicate";
    case "unknown_tier": return "unknown_tier";
    case "not_found": return "not_found";
    case "negative": case "not_a_decimal": case "not_a_date": case "not_an_integer": case "not_a_uuid": case "wrong_purpose": return "invalid";
    default: return "invalid";
  }
}

/** Field errors of a problem body as {field: code}. A pointer that names no field of the form is returned in `rest` (for the banner). */
export function fieldErrorsOf(errors: readonly { pointer: string; code: string }[] | undefined, form: "programme" | "gift" | "enrol"): { byField: Record<string, FieldErrorCode>; rest: { pointer: string; code: string }[] } {
  const byField: Record<string, FieldErrorCode> = {};
  const rest: { pointer: string; code: string }[] = [];
  for (const e of errors ?? []) {
    const f = pointerToField(e.pointer, form);
    if (f) byField[f] ??= serverFieldCode(e.code);
    else rest.push(e);
  }
  return { byField, rest };
}

/** The member a pointer names, for the banner ("body.label_en" -> "label_en"). The server names the FLAT member inside a tier or a row for most checks
 *  (a server shortcoming being reported); such an error has no row to light, so it is said in the banner with the member name and never dropped. */
export const memberOf = (pointer: string): string => pointer.replace(/^\/+/, "").replace(/^body[./]?/, "") || pointer;
/** Banner text for the errors that name no field of the form: "member: message" joined. */
export const unplacedText = (rest: readonly { pointer: string; code: string }[], message: (c: FieldErrorCode) => string): string =>
  rest.map((e) => `${memberOf(e.pointer)}: ${message(serverFieldCode(e.code))}`).join(" · ");

export type WriteKind = "programme-create" | "programme-edit" | "status" | "enrol" | "gift";
/** The message key for a failed write: 409 and 400 codes the server uses, said for the action that failed. null = use the generic problem text. */
export function failureKey(status: number, code: string | undefined, errors: readonly { pointer: string; code: string }[] | undefined, kind: WriteKind): "loy.draft_only" | "loy.enrol.ended" | "loy.gifts.ended" | "loy.status.bad_move" | "loy.enrol.overlap" | "loy.err.duplicate_code" | "loy.err.reused" | "loy.err.no_change" | "loy.err.stale" | null {
  if (status === 400 && (errors ?? []).some((e) => e.code === "no_change")) return "loy.err.no_change";
  if (status === 412) return "loy.err.stale";
  if (status === 409 && code === "ERR_REQUEST_STATE") return kind === "programme-edit" ? "loy.draft_only" : kind === "enrol" ? "loy.enrol.ended" : kind === "gift" ? "loy.gifts.ended" : kind === "status" ? "loy.status.bad_move" : null;
  if (status === 409 && code === "ERR_MASTER_DUPLICATE_CODE") return "loy.err.duplicate_code";
  if (status === 409 && code === "ERR_MASTER_OVERLAP") return "loy.enrol.overlap";
  if (status === 409 && code === "ERR_CONFLICT") return "loy.err.reused";
  return null;
}

// ---------- "nothing changed" (the server answers 400 no_change to a save that changes nothing) ----------

const sortedTiers = (tiers: readonly Tier[]): Tier[] => [...tiers].sort((a, b) => a.sort_order - b.sort_order);
const decNorm = (v: string | null | undefined): string | null => (v == null ? null : (() => { const d = parseDecimal3(v); return d === null ? v : decimal3Wire(d); })());
/** The edit body against the programme as it is now: true when no member the form sends would change (name, period, dates, tiers). */
export function programmeUnchanged(p: Programme, body: Pick<ProgrammePatch, "name_en" | "name_bn" | "period_label" | "active_from" | "active_to" | "tiers">): boolean {
  const key = (t: Tier) => JSON.stringify([t.tier_code, t.sort_order, t.label_en, t.label_bn ?? null, decNorm(t.threshold), t.threshold_basis ?? "std"]);
  const a = sortedTiers(p.tiers ?? []).map(key);
  const b = sortedTiers(body.tiers ?? []).map(key);
  return p.name_en === body.name_en && (p.name_bn || null) === (body.name_bn || null) && (p.period_label || null) === (body.period_label || null) && p.active_from === body.active_from && p.active_to === body.active_to && a.length === b.length && a.every((x, i) => x === b[i]);
}
/** The gift edit body against the gift as it is now. */
export function giftUnchanged(g: Gift, body: Pick<GiftPatch, "name_en" | "name_bn" | "points_cost" | "tier_codes" | "status">): boolean {
  const codes = (x: readonly string[] | undefined) => [...(x ?? [])].sort().join(",");
  return g.name_en === body.name_en && (g.name_bn || null) === (body.name_bn || null) && (g.points_cost ?? null) === (body.points_cost ?? null) && codes(g.tier_codes) === codes(body.tier_codes) && g.status === body.status;
}
