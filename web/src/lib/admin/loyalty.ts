// Loyalty programmes (portal-loyalty-web, D-LOY-WEB-01). Pure logic, no I/O: the role matrix (one place), exact decimal and integer
// helpers (BigInt, never a float: STD targets are Decimal3 strings, points are integers, money is integer milli-taka text), the
// form checks and the mapping from the form to the wire bodies. Wire shapes are the contract 1.12.2 programme/gift shapes plus the
// members the brief's API sketch adds for 1.13.0 (marked ASSUMED in D-LOY-WEB-01); they are typed here until the contract lands.
import type { Role } from "@/contract/types";
import { accessOf, type ModuleAccess, type ModuleGrants } from "./modules";
import { displayUnit, type DisplayUnit } from "./offers";
import { latinDigits } from "./taka";

// ---------- wire types ----------

export const PROGRAMME_KINDS = ["astha", "diamond_league", "superstar", "campaign"] as const;
export type ProgrammeKind = (typeof PROGRAMME_KINDS)[number];
/** ASSUMED 1.13.0: `ProgrammeStatus` (draft, active, inactive) replaces `ActiveStatus` on Programme, as OfferStatus did. */
export const PROGRAMME_STATUSES = ["draft", "active", "inactive"] as const;
export type ProgrammeStatus = (typeof PROGRAMME_STATUSES)[number];
/** Outlet channels the scope may name (brief s5; docs/34 s3). */
export const CHANNELS = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"] as const;
export type Channel = (typeof CHANNELS)[number];
/** Q1 default: thresholds are STD as stored. */
export type ThresholdBasis = "std";

export interface Tier {
  tier_code: string;
  label_en: string;
  label_bn: string | null;
  /** Decimal3 text (STD as stored) or null when the owner has not supplied a threshold (nothing is invented). */
  threshold_value: string | null;
  threshold_basis: ThresholdBasis;
}
export interface Programme {
  programme_id: number;
  kind: ProgrammeKind;
  code: string;
  name_en: string;
  name_bn: string | null;
  period_label: string | null;
  active_from: string;
  active_to: string;
  points_expire_on?: string | null;
  rules?: Record<string, string | number | boolean | null>;
  status: ProgrammeStatus;
  /** Row version: also the ETag for If-Match. */
  version: number;
  /** ASSUMED 1.13.0. */
  version_no?: number;
  tiers?: Tier[];
  channels?: string[];
  change_reason?: string | null;
  created_at?: string;
  updated_at?: string;
}
export interface ProgrammeVersion {
  programme_version_id: number;
  programme_id: number;
  version_no: number;
  is_current: boolean;
  name_en: string;
  name_bn: string | null;
  active_from: string;
  active_to: string;
  tiers: Tier[];
  channels: string[];
  change_reason: string | null;
  created_at: string;
}
export interface ProgrammeWrite {
  request_uuid: string;
  kind: ProgrammeKind;
  code: string;
  name_en: string;
  name_bn: string | null;
  period_label: string | null;
  active_from: string;
  active_to: string;
  tiers: Tier[];
  channels: string[];
  change_reason: string;
}
export interface Gift {
  gift_id: number;
  programme_id: number;
  code: string;
  name_en: string;
  name_bn: string | null;
  points_cost: number | null;
  tier_codes?: string[];
  image_url?: string | null;
  status: "active" | "inactive";
  version: number;
}
export interface GiftWrite {
  request_uuid: string;
  programme_id: number;
  code: string;
  name_en: string;
  name_bn: string | null;
  points_cost: number | null;
  tier_codes: string[];
  status: "active" | "inactive";
  change_reason: string;
}
export interface Enrolment {
  programme_id: number;
  outlet_id: number;
  league_label?: string | null;
  tier_code?: string | null;
  base_target?: string | null;
  valid_from: string;
  valid_to?: string | null;
}
/** ASSUMED 1.13.0: GET /v1/admin/loyalty/programmes/{id}/progress. Targets and achievement are Decimal3 STD; points are integers. */
export interface Progress {
  tiers: { tier_code: string; enrolled_outlets: number; target_std: string; achieved_std: string }[];
  points: { earned: number; redeemed: number; expiring: number };
  /** Milli-taka per point, when the server states it (LoyaltyMath cash rate). Absent: no cash line is shown. */
  cash_rate_mtk_per_point?: number | null;
}

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
export const MAX_TIERS = 20;
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
  channels: string[];
  reason: string;
}
export const emptyForm = (): LoyaltyForm => ({ kind: "astha", code: "", name_en: "", name_bn: "", period_label: "", active_from: "", active_to: "", tiers: [], channels: [], reason: "" });
export const emptyTier = (): TierForm => ({ tier_code: "", label_en: "", label_bn: "", threshold: "" });

export function formFromProgramme(p: Programme): LoyaltyForm {
  return {
    kind: p.kind, code: p.code, name_en: p.name_en, name_bn: p.name_bn ?? "", period_label: p.period_label ?? "", active_from: p.active_from, active_to: p.active_to,
    tiers: (p.tiers ?? []).map((t) => ({ tier_code: t.tier_code, label_en: t.label_en, label_bn: t.label_bn ?? "", threshold: t.threshold_value === null ? "" : decimal3Text(parseDecimal3(t.threshold_value) ?? 0n) })),
    channels: [...(p.channels ?? [])], reason: "",
  };
}

export type FieldErrorCode = "required" | "invalid" | "pattern" | "too_long" | "range" | "duplicate" | "not_rising" | "too_many" | "reason_short" | "reason_long";
export interface FieldError { field: string; code: FieldErrorCode }
const len = (s: string): number => Array.from(s.trim()).length;
const isoDay = (s: string): boolean => DATE.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

/** Shared checks of a create and an edit (the contract's patterns and lengths; thresholds rising by tier, brief s7). */
export function validateForm(f: LoyaltyForm, mode: "create" | "edit", requestUuid: string): { errors: FieldError[]; body: ProgrammeWrite | null } {
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
    else if (len(t.label_en) > 60) err(k("label_en"), "too_long");
    if (len(t.label_bn) > 60) err(k("label_bn"), "too_long");
    let value: bigint | null = null;
    if (t.threshold.trim()) {
      value = parseDecimal3(t.threshold);
      if (value === null) err(k("threshold"), "invalid");
      else if (last !== null && value <= last) err(k("threshold"), "not_rising");
      if (value !== null) last = value;
    }
    tiers.push({ tier_code: tc, label_en: t.label_en.trim(), label_bn: t.label_bn.trim() ? t.label_bn.trim() : null, threshold_value: value === null ? null : decimal3Wire(value), threshold_basis: "std" });
  });
  if (errors.length > 0) return { errors, body: null };
  return {
    errors,
    body: {
      request_uuid: requestUuid, kind: f.kind, code, name_en: f.name_en.trim(), name_bn: f.name_bn.trim() ? f.name_bn.trim() : null, period_label: f.period_label.trim() ? f.period_label.trim() : null,
      active_from: f.active_from, active_to: f.active_to, tiers, channels: [...f.channels], change_reason: f.reason.trim(),
    },
  };
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

export function validateGift(f: GiftForm, mode: "create" | "edit", requestUuid: string): { errors: FieldError[]; body: GiftWrite | null } {
  const errors: FieldError[] = [];
  const err = (field: string, code: FieldErrorCode) => errors.push({ field, code });
  const pid = Number(f.programme_id);
  if (!f.programme_id) err("programme_id", "required");
  else if (!Number.isSafeInteger(pid) || pid < 1) err("programme_id", "invalid");
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
  return {
    errors,
    body: { request_uuid: requestUuid, programme_id: pid, code, name_en: f.name_en.trim(), name_bn: f.name_bn.trim() ? f.name_bn.trim() : null, points_cost: points, tier_codes: [...f.tier_codes], status: f.status, change_reason: f.reason.trim() },
  };
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
  else if (f.valid_to && isoDay(f.valid_from) && f.valid_to < f.valid_from) err("valid_to", "range");
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
