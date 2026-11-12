// Display text of loyalty programmes (portal-loyalty-web): labels by locale, STD thresholds with their unit, guarded percent, points.
// Pure; used by the list, the detail and the tests. Exact decimals from lib/admin/loyalty.ts (BigInt), money from integer milli-taka.
import { formatNumber, t, type Locale } from "@/lib/i18n";
import { decimal3Text, parseDecimal3, type Programme, type ProgrammeKind, type ProgrammeStatus, type ScopeNode, type Tier } from "./loyalty";
import { localDigits } from "./offers";

export const statusLabel = (locale: Locale, s: ProgrammeStatus): string => t(locale, `loy.status.${s}`);
export const kindLabel = (locale: Locale, k: ProgrammeKind): string => t(locale, `loy.kind.${k}`);
export const nameOf = (locale: Locale, p: { name_en: string; name_bn?: string | null }): string => (locale === "bn" && p.name_bn ? p.name_bn : p.name_en);
export const tierName = (locale: Locale, tier: Pick<Tier, "label_en" | "label_bn">): string => (locale === "bn" && tier.label_bn ? tier.label_bn : tier.label_en);
/** A scope or qualifying node: its type word and its id (the ids of channels and sub-channels have no name source in the portal yet). */
export const scopeLabel = (locale: Locale, n: ScopeNode): string => `${t(locale, `loy.node.${n.node_type}` as "loy.node.wing")} · #${n.node_id}`;
export const qualifyingLabel = (locale: Locale, q: { product_level: string; product_id: number }): string => `${t(locale, `loy.plevel.${q.product_level}` as "loy.plevel.sku")} · #${q.product_id}`;

/** Digits grouped in the western way with the UI language's digits; decimals kept exactly as the decimal text has them. */
export function groupedDecimal(locale: Locale, text: string): string {
  const [whole = "0", frac] = text.split(".");
  const g = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return localDigits(locale, frac ? `${g}.${frac}` : g);
}
/** A Decimal3 STD figure with its unit word ("1,500 STD"); an unreadable value shows a dash. */
export function stdText(locale: Locale, wire: string | null | undefined): string {
  if (wire == null) return "—";
  const v = parseDecimal3(wire);
  return v === null ? "—" : t(locale, "loy.std_value", { value: groupedDecimal(locale, decimal3Text(v)) });
}
export const thresholdText = (locale: Locale, tier: Pick<Tier, "threshold">): string => (tier.threshold == null ? t(locale, "loy.threshold.none") : stdText(locale, tier.threshold));
export const pointsText = (locale: Locale, n: number): string => t(locale, "loy.points_value", { n: formatNumber(locale, n) });
export const percentText = (locale: Locale, pct: string | null): string => (pct === null ? "—" : `${localDigits(locale, pct)}%`);
export const programmePeriod = (p: Pick<Programme, "period_label" | "active_from" | "active_to">): string => p.period_label ?? `${p.active_from} – ${p.active_to}`;
