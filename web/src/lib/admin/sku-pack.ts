// Pack sizes of a SKU (docs/34 s4.2, D-UNITS-02): the field counts cigarettes in packets (20s and 10s), bidi in packets (sticks per
// packet per SKU), lighters as a box of 25 and matches in dozens; storage stays in base units. This module derives the pack label
// and the preview sentence ("1 packet = 20 sticks") the SKU form shows, and the plain-language warnings for unusual sizes.
import type { MessageKey } from "@/lib/i18n";

export type SkuCategory = "cigarette" | "bidi" | "lighter" | "match";
export type BaseUnit = "stick" | "piece" | "dozen";

/** Entry-unit label of a category (docs/34 s4.2): packet, box or dozen. */
export const PACK_LABEL: Record<SkuCategory, "packet" | "box" | "dozen"> = { cigarette: "packet", bidi: "packet", lighter: "box", match: "dozen" };
export const BASE_UNIT_OF: Record<SkuCategory, BaseUnit> = { cigarette: "stick", bidi: "stick", lighter: "piece", match: "dozen" };

export interface PackInput {
  category: string;
  baseUnit: string;
  basePerPack: string;
  entryUnit: string;
}

export interface PackPreview {
  /** The entry-unit label of the category: packet, box or dozen (shown as "Pack label"); null when the category is unknown. */
  label: "packet" | "box" | "dozen" | null;
  /** The sentence, as a message key and its variables; null while the inputs are incomplete or invalid. */
  sentence: { key: MessageKey; vars: Record<string, string | number> } | null;
  /** Warnings (never blocking): unusual size for the category, base unit that differs from the category's, entry unit that is not the pack. */
  warnings: MessageKey[];
}

const isCategory = (c: string): c is SkuCategory => c === "cigarette" || c === "bidi" || c === "lighter" || c === "match";

export function packPreview(i: PackInput): PackPreview {
  const warnings: MessageKey[] = [];
  const n = /^\d{1,4}$/.test(i.basePerPack.trim()) ? Number(i.basePerPack) : NaN;
  if (!isCategory(i.category)) return { label: null, sentence: null, warnings };
  const label = PACK_LABEL[i.category];
  if (!Number.isInteger(n) || n < 1 || n > 1000) return { label, sentence: null, warnings };
  if (i.baseUnit !== "" && i.baseUnit !== BASE_UNIT_OF[i.category]) warnings.push("skupack.warn.base_unit");
  if (i.entryUnit !== "" && i.entryUnit !== "pack") warnings.push("skupack.warn.entry_unit");
  if (i.category === "cigarette" && n !== 10 && n !== 20) warnings.push("skupack.warn.cigarette_size");
  if (i.category === "lighter" && n !== 25) warnings.push("skupack.warn.lighter_size");
  if (i.category === "match" && n !== 1) warnings.push("skupack.warn.match_size");
  if (i.category === "match") return { label, sentence: { key: "skupack.preview.match", vars: {} }, warnings };
  return { label, sentence: { key: label === "box" ? "skupack.preview.box" : "skupack.preview.packet", vars: { n } }, warnings };
}
