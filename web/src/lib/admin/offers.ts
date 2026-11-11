// Discount programmes (portal-discount-web, D-DISC-WEB-01; contract 1.12.0 listOffers .. setOfferStatus). Pure logic, no I/O:
// the role matrix (one place), integer-only money / percent / unit conversion, and the mapping from the form to OfferWrite /
// OfferPatch / OfferStatusChange. Money is integer milli-taka on the wire and never goes through a float: takaToMtk and
// scaledInt parse text exactly, BigInt does the display. Quantities are base units on the wire and are entered and shown in the
// SKU's field unit (units rule 5: packets, boxes of 25, dozens) using OfferSkuUnit.pack_factor.
import type { Role, Schemas } from "@/contract/types";
import { accessOf, type ModuleAccess, type ModuleGrants } from "./modules";
import { digitsOnly, latinDigits, scaledInt, takaToMtk } from "./taka";

export type Offer = Schemas["Offer"];
export type OfferVersion = Schemas["OfferVersion"];
export type OfferWrite = Schemas["OfferWrite"];
export type OfferPatch = Schemas["OfferPatch"];
export type OfferSkuUnit = Schemas["OfferSkuUnit"];
export type OfferStatus = Schemas["OfferStatus"];
export type OfferType = Offer["offer_type"];

/** ROLE MATRIX (one place). v1 gate, brief section 4 item 1: SUPERADMIN, and ADMIN with a `discounts` grant. NATIONAL, BRAND, WM, DMO and
 *  TSO may read within their scope on the server (contract 1.12.0) but are not listed in the portal yet: owner question Q9. To open
 *  the read view to them later, add the roles to OFFER_READ_ROLES and an ADMIN_PATH_ROLES entry for /admin/discounts (roles.ts). */
export const OFFER_READ_ROLES = ["SUPERADMIN", "ADMIN"] as const satisfies readonly Role[];

export function offerAccess(role: Role | undefined, grants: ModuleGrants | null): ModuleAccess {
  if (role === "SUPERADMIN") return "w";
  if (role === "ADMIN") return accessOf(grants, "discounts");
  return "none";
}
export const canReadOffers = (role: Role | undefined, grants: ModuleGrants | null): boolean => offerAccess(role, grants) !== "none";
export const canWriteOffers = (role: Role | undefined, grants: ModuleGrants | null): boolean => offerAccess(role, grants) === "w";
/** draft -> active is SUPERADMIN only (as for prices, setOfferStatus); active -> inactive needs the write grant (Q3 default). */
export const canActivateOffer = (role: Role | undefined): boolean => role === "SUPERADMIN";

/** Types the v1 form offers: price-off per unit, percentage, quantity-based. `drp_slide` is refused 400 by the API in this release;
 *  bundle has no schema type (Q1). A row of another type read from the API still displays. */
export const OFFER_TYPES = ["amount_per_unit", "pct_discount", "free_qty"] as const satisfies readonly OfferType[];
export const OFFER_STATUSES = ["draft", "active", "inactive"] as const satisfies readonly OfferStatus[];
export const STACKING = ["exclusive", "stackable"] as const;
export const LEVELS = ["line", "memo"] as const;
export const PRODUCT_LEVELS = ["category", "brand", "variant", "sku"] as const;
/** Scope node types the form offers: `channel` is refused 400 in this release; sub_channel has no picker source yet. Empty = everywhere. */
export const SCOPE_NODE_TYPES = ["wing", "division", "territory", "zone", "route"] as const;

export const REASON_MIN = 10;
export const REASON_MAX = 500;
export const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// ---------- integers only: percent, taka, quantity ----------

/** Basis points as percent text with two decimals, from integers (1250 -> "12.50"). */
export function bpText(bp: number): string {
  return `${Math.trunc(bp / 100)}.${String(bp % 100).padStart(2, "0")}`;
}
/** Percent text ("12.5", "12,50" is not a number) to basis points (1 to 10000), exact; null when invalid. */
export function percentToBp(text: string): number | null {
  const v = scaledInt(text.trim(), 100);
  return v !== null && v >= 1 && v <= 10_000 ? v : null;
}
const BN_DIGITS = "০১২৩৪৫৬৭৮৯";
/** Digits of already-formatted Latin text in the UI language. */
export const localDigits = (locale: "bn" | "en", s: string): string => (locale === "bn" ? s.replace(/\d/g, (d) => BN_DIGITS[Number(d)]!) : s);
export const formatBp = (locale: "bn" | "en", bp: number): string => `${localDigits(locale, bpText(bp))}%`;

/** Milli-taka to taka text with exactly the digits needed (1500 -> "1.5", 2000 -> "2"); BigInt, nothing rounded. */
export function mtkText(mtk: number | bigint): string {
  const n = BigInt(mtk);
  const neg = n < 0n;
  const a = neg ? -n : n;
  const whole = a / 1000n;
  const frac = (a % 1000n).toString().padStart(3, "0").replace(/0+$/, "");
  return `${neg ? "-" : ""}${whole}${frac ? `.${frac}` : ""}`;
}

/** The field unit of a set of SKUs, when they all count the same way; null for none or a mix (then quantities are base units). */
export interface UnitCtx {
  packFactor: number;
  fieldUnit: OfferSkuUnit["field_unit"];
  baseUnit: OfferSkuUnit["base_unit"];
}
export function unitCtx(units: readonly OfferSkuUnit[]): UnitCtx | null {
  const first = units[0];
  if (!first) return null;
  const same = units.every((u) => u.pack_factor === first.pack_factor && u.field_unit === first.field_unit && u.base_unit === first.base_unit);
  return same ? { packFactor: first.pack_factor, fieldUnit: first.field_unit, baseUnit: first.base_unit } : null;
}

/** Field quantity text ("3", "৩") to base units: packs times pack_factor. Null when not a positive whole number or unsafe. */
export function fieldQtyToBase(text: string, pack: number): number | null {
  const d = digitsOnly(text);
  if (!d || d !== latinDigits(text).trim()) return null;
  const v = Number(d) * pack;
  return Number(d) >= 1 && Number.isSafeInteger(v) ? v : null;
}
/** Base quantity split into whole packs and a remainder in base units (65 with pack 20 -> 3 packs + 5). */
export const splitBase = (base: number, pack: number): { packs: number; rest: number } => ({ packs: Math.trunc(base / pack), rest: base % pack });

/** Milli-taka per base unit shown per field unit (integer multiply). */
export const perBaseToPerField = (mtkPerBase: number, pack: number): number => mtkPerBase * pack;
/** Taka text per FIELD unit to milli-taka per BASE unit; null when the amount does not divide exactly (nothing is rounded). */
export function perFieldTakaToPerBase(text: string, pack: number): number | null {
  const mtk = takaToMtk(text);
  if (mtk === null || mtk < 1 || mtk % pack !== 0) return null;
  return mtk / pack;
}

// ---------- the form ----------

export interface OfferForm {
  code: string;
  group_code: string;
  title_en: string;
  title_bn: string;
  offer_type: OfferType;
  level: "line" | "memo";
  stacking: "exclusive" | "stackable";
  valid_from: string;
  valid_to: string;
  /** pct_discount: percent text. */
  percent: string;
  /** amount_per_unit: taka text per field unit when the SKUs share one, else per base unit. */
  perUnit: string;
  /** free_qty: bought quantity, in field units when known, else base units. */
  threshold: string;
  rewardSku: string;
  /** Free quantity of the reward SKU, field units when its unit is known, else base. */
  rewardQty: string;
  qualifying: { product_level: (typeof PRODUCT_LEVELS)[number]; product_id: number }[];
  scope: { node_type: (typeof SCOPE_NODE_TYPES)[number] | string; node_id: number }[];
  reason: string;
}

export const emptyForm = (): OfferForm => ({ code: "", group_code: "", title_en: "", title_bn: "", offer_type: "amount_per_unit", level: "line", stacking: "exclusive", valid_from: "", valid_to: "", percent: "", perUnit: "", threshold: "", rewardSku: "", rewardQty: "", qualifying: [], scope: [], reason: "" });

export interface FormUnits {
  /** Unit of the qualifying SKUs (threshold, per-unit amount); null = base units. */
  qualifying: UnitCtx | null;
  /** Unit of the reward SKU; null = base units. */
  reward: UnitCtx | null;
}

export type FieldErrorCode = "required" | "invalid" | "pattern" | "too_long" | "range" | "not_exact" | "reason_short" | "reason_long";
export interface FieldError { field: string; code: FieldErrorCode }

const len = (s: string): number => Array.from(s.trim()).length;
const isoDay = (s: string): boolean => DATE.test(s) && !Number.isNaN(Date.parse(`${s}T00:00:00Z`)) && new Date(`${s}T00:00:00Z`).toISOString().startsWith(s);

/** Shared checks of a create and an edit (same rules as the V0003 CHECKs and the contract). */
export function validateForm(f: OfferForm, units: FormUnits, mode: "create" | "edit"): { errors: FieldError[]; body: OfferWrite | null } {
  const errors: FieldError[] = [];
  const err = (field: string, code: FieldErrorCode) => errors.push({ field, code });
  const code = f.code.trim();
  if (mode === "create") {
    if (!code) err("code", "required");
    else if (!CODE_PATTERN.test(code)) err("code", "pattern");
  }
  const group = (f.group_code.trim() || code).trim();
  if (group.length > 40) err("group_code", "too_long");
  if (!f.title_en.trim()) err("title_en", "required");
  else if (len(f.title_en) > 200) err("title_en", "too_long");
  if (len(f.title_bn) > 200) err("title_bn", "too_long");
  if (!isoDay(f.valid_from)) err("valid_from", f.valid_from ? "invalid" : "required");
  if (!isoDay(f.valid_to)) err("valid_to", f.valid_to ? "invalid" : "required");
  else if (isoDay(f.valid_from) && f.valid_to < f.valid_from) err("valid_to", "range");
  const r = len(f.reason);
  if (r < REASON_MIN) err("reason", "reason_short");
  else if (r > REASON_MAX) err("reason", "reason_long");

  let discount_bp: number | null = null;
  let discount_mtk_per_unit: number | null = null;
  let threshold_qty_base: number | null = null;
  let reward_sku_id: number | null = null;
  let reward_qty_base: number | null = null;
  if (f.offer_type === "pct_discount") {
    discount_bp = percentToBp(f.percent);
    if (discount_bp === null) err("percent", f.percent.trim() ? "range" : "required");
  } else if (f.offer_type === "amount_per_unit") {
    if (!f.perUnit.trim()) err("perUnit", "required");
    else {
      discount_mtk_per_unit = units.qualifying ? perFieldTakaToPerBase(f.perUnit, units.qualifying.packFactor) : takaToMtk(f.perUnit);
      if (discount_mtk_per_unit === null || discount_mtk_per_unit < 1) {
        discount_mtk_per_unit = null;
        err("perUnit", units.qualifying && takaToMtk(f.perUnit) !== null ? "not_exact" : "invalid");
      }
    }
  } else if (f.offer_type === "free_qty") {
    if (!f.threshold.trim()) err("threshold", "required");
    else {
      threshold_qty_base = fieldQtyToBase(f.threshold, units.qualifying?.packFactor ?? 1);
      if (threshold_qty_base === null) err("threshold", "invalid");
    }
    const sku = Number(f.rewardSku);
    if (!f.rewardSku) err("rewardSku", "required");
    else if (!Number.isInteger(sku) || sku < 1) err("rewardSku", "invalid");
    else reward_sku_id = sku;
    if (!f.rewardQty.trim()) err("rewardQty", "required");
    else {
      reward_qty_base = fieldQtyToBase(f.rewardQty, units.reward?.packFactor ?? 1);
      if (reward_qty_base === null) err("rewardQty", "invalid");
    }
  }
  if (f.qualifying.length > 100) err("qualifying", "range");
  if (f.scope.length > 200) err("scope", "range");
  if (errors.length > 0) return { errors, body: null };
  const body: OfferWrite = {
    code,
    group_code: group,
    offer_type: f.offer_type,
    level: f.level,
    title_en: f.title_en.trim(),
    title_bn: f.title_bn.trim() ? f.title_bn.trim() : null,
    valid_from: f.valid_from,
    valid_to: f.valid_to,
    threshold_qty_base,
    discount_bp,
    discount_mtk_per_unit,
    reward_sku_id,
    reward_qty_base,
    qualifying: f.qualifying.map((q) => ({ product_level: q.product_level, product_id: q.product_id })),
    scope: f.scope.map((s) => ({ node_type: s.node_type as OfferWrite["scope"][number]["node_type"], node_id: s.node_id })),
    stacking: f.stacking,
    change_reason: f.reason.trim(),
  };
  return { errors, body };
}

/** OfferPatch for an edit: all rule members of the form (a full snapshot, so nulls clear what the new type does not use), no code. */
export function toPatch(body: OfferWrite, requestUuid: string): OfferPatch {
  const { code: _code, group_code: _group, ...rest } = body;
  return { request_uuid: requestUuid, ...rest };
}

/** The form for an existing offer (edit), the inverse of validateForm: quantities and per-unit amounts in the field unit when known. */
export function formFromOffer(o: Offer | OfferVersion, units: FormUnits, code = "", group = ""): OfferForm {
  const q = units.qualifying;
  const base: OfferForm = emptyForm();
  const perUnit = o.discount_mtk_per_unit == null ? "" : mtkText(q ? perBaseToPerField(o.discount_mtk_per_unit, q.packFactor) : o.discount_mtk_per_unit);
  return {
    ...base,
    code: "code" in o ? o.code : code,
    group_code: "group_code" in o ? o.group_code : group,
    title_en: o.title_en,
    title_bn: o.title_bn ?? "",
    offer_type: o.offer_type,
    level: o.level,
    stacking: o.stacking,
    valid_from: o.valid_from,
    valid_to: o.valid_to,
    percent: o.discount_bp == null ? "" : bpText(o.discount_bp),
    perUnit,
    threshold: o.threshold_qty_base == null ? "" : String(q ? Math.trunc(o.threshold_qty_base / q.packFactor) : o.threshold_qty_base),
    rewardSku: o.reward_sku_id == null ? "" : String(o.reward_sku_id),
    rewardQty: o.reward_qty_base == null ? "" : String(units.reward ? Math.trunc(o.reward_qty_base / units.reward.packFactor) : o.reward_qty_base),
    qualifying: o.qualifying.map((x) => ({ product_level: x.product_level, product_id: x.product_id })),
    scope: o.scope.map((s) => ({ node_type: s.node_type, node_id: s.node_id })),
    reason: "",
  };
}

/** The request needs `activate` rules (valid_to not in the past, Asia/Dhaka date) answered on the client so the button says why. */
export const activationBlocked = (validTo: string, today: string): boolean => validTo < today;

/** A fresh request_uuid per submission, kept by the caller until the submission succeeds so a retry re-sends the same one. */
export const newRequestUuid = (): string => crypto.randomUUID();

/** The status moves the buttons may offer (the server decides): draft -> active, active -> inactive. */
export function nextStatuses(status: OfferStatus, role: Role | undefined, grants: ModuleGrants | null): ("active" | "inactive")[] {
  const out: ("active" | "inactive")[] = [];
  if (status === "draft" && canActivateOffer(role)) out.push("active");
  if (status === "active" && canWriteOffers(role, grants)) out.push("inactive");
  return out;
}
