// Display text of an offer rule (portal-discount-web): quantities in the SKU's field unit with the base figure alongside, money in
// exact taka from integer milli-taka, percent from integer basis points. Pure; used by the list, the detail and the tests.
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { formatMtkExact } from "./money";
import { formatBp, perBaseToPerField, splitBase, unitCtx, type Offer, type OfferVersion, type OfferSkuUnit, type UnitCtx } from "./offers";

type Rule = Pick<Offer, "offer_type" | "qualifying" | "discount_bp" | "discount_mtk_per_unit" | "threshold_qty_base" | "reward_sku_id" | "reward_qty_base">;

/** Unit of the qualifying products: known only when every product is a SKU and all share one pack (else base units). */
export function qualifyingUnit(r: Pick<Rule, "qualifying">, units: readonly OfferSkuUnit[] | undefined): UnitCtx | null {
  if (r.qualifying.length === 0 || r.qualifying.some((q) => q.product_level !== "sku")) return null;
  const ids = new Set(r.qualifying.map((q) => q.product_id));
  const mine = (units ?? []).filter((u) => ids.has(u.sku_id));
  return mine.length === ids.size ? unitCtx(mine) : null;
}
export function rewardUnit(r: Pick<Rule, "reward_sku_id">, units: readonly OfferSkuUnit[] | undefined): UnitCtx | null {
  const u = (units ?? []).find((x) => x.sku_id === r.reward_sku_id);
  return u ? unitCtx([u]) : null;
}

const UNIT_KEY: Record<OfferSkuUnit["field_unit"] | OfferSkuUnit["base_unit"], MessageKey> = { packet: "disc.unit.packet", box: "disc.unit.box", dozen: "disc.unit.dozen", piece: "disc.unit.piece", stick: "disc.unit.stick" };
const QTY_KEY: Record<OfferSkuUnit["field_unit"] | OfferSkuUnit["base_unit"], MessageKey> = { packet: "disc.qty.packet", box: "disc.qty.box", dozen: "disc.qty.dozen", piece: "disc.qty.piece", stick: "disc.qty.stick" };
export const unitWord = (locale: Locale, u: UnitCtx | null): string => t(locale, u ? UNIT_KEY[u.fieldUnit] : "disc.unit.base");

/** "3 packets (60 sticks)", "3 packets and 5 sticks (65 sticks)", or "60 base units" when the unit is unknown. */
export function qtyText(locale: Locale, base: number, u: UnitCtx | null): string {
  if (!u) return t(locale, "disc.qty.base", { n: base });
  const baseText = t(locale, QTY_KEY[u.baseUnit], { n: base });
  if (u.packFactor === 1) return baseText;
  const { packs, rest } = splitBase(base, u.packFactor);
  if (packs === 0) return baseText; // less than one pack: the base figure alone
  let field = packs > 0 ? t(locale, QTY_KEY[u.fieldUnit], { n: packs }) : "";
  if (rest > 0) field = field ? t(locale, "disc.qty.plus", { a: field, b: t(locale, QTY_KEY[u.baseUnit], { n: rest }) }) : t(locale, QTY_KEY[u.baseUnit], { n: rest });
  return t(locale, "disc.qty.with_base", { field, base: baseText });
}

/** The amount off per field unit (or per base unit when the unit is unknown), exact taka. */
export function amountPerUnitText(locale: Locale, mtkPerBase: number, u: UnitCtx | null): string {
  return formatMtkExact(locale, u ? perBaseToPerField(mtkPerBase, u.packFactor) : mtkPerBase);
}

/** One line for the list and the detail: "12.50% off", "৳2.000 off per packet", "Buy 3 packets (60 sticks), get 1 packet free". */
export function valueText(locale: Locale, o: Rule & { sku_units?: readonly OfferSkuUnit[] | undefined }): string {
  const q = qualifyingUnit(o, o.sku_units);
  if (o.offer_type === "pct_discount" && o.discount_bp != null) return t(locale, "disc.value.pct", { pct: formatBp(locale, o.discount_bp) });
  if (o.offer_type === "amount_per_unit" && o.discount_mtk_per_unit != null) return t(locale, "disc.value.amount", { amount: amountPerUnitText(locale, o.discount_mtk_per_unit, q), unit: unitWord(locale, q) });
  if (o.offer_type === "free_qty" && o.threshold_qty_base != null && o.reward_qty_base != null) {
    return t(locale, "disc.value.free", { qty: qtyText(locale, o.threshold_qty_base, q), reward: qtyText(locale, o.reward_qty_base, rewardUnit(o, o.sku_units)) });
  }
  return t(locale, "disc.value.none");
}

export const typeLabel = (locale: Locale, type: Offer["offer_type"] | OfferVersion["offer_type"]): string => t(locale, `disc.type.${type}`);
export const statusLabel = (locale: Locale, s: Offer["status"]): string => t(locale, `disc.status.${s}`);
export const titleOf = (locale: Locale, o: { title_en: string; title_bn?: string | null }): string => (locale === "bn" && o.title_bn ? o.title_bn : o.title_en);
export const num = (locale: Locale, n: number): string => formatNumber(locale, n);
