import type { Locale } from "@/lib/i18n";
import { groupWestern } from "@/lib/reports/format";

export interface LakhWords {
  lakh: string;
  crore: string;
}

/** South-Asian display of a large count: 3.61 Cr, 65.07 Lakh, 28,067 (Bengali digits and words in bn). Integers only; the server value
 *  is never rounded below a lakh. BigInt maths (no float division): the two decimals are half up on the integer hundredths, and the
 *  whole part is grouped in threes like every other amount (F-SYS-051). */
export function formatLakhCrore(locale: Locale, value: number, words: LakhWords): string {
  if (!Number.isSafeInteger(value)) return "—"; // never another amount for a non-integer or unsafe one
  const abs = BigInt(value < 0 ? -value : value);
  const sign = value < 0 ? "-" : "";
  const scaled = (unit: bigint): string => {
    const hundredths = (abs * 100n + unit / 2n) / unit; // half up
    const frac = String(hundredths % 100n).padStart(2, "0");
    return `${groupWestern(locale, hundredths / 100n)}.${locale === "bn" ? toBengali(frac) : frac}`;
  };
  if (abs >= 10_000_000n) return `${sign}${scaled(10_000_000n)} ${words.crore}`;
  if (abs >= 100_000n) return `${sign}${scaled(100_000n)} ${words.lakh}`;
  return `${sign}${groupWestern(locale, abs)}`;
}

const toBengali = (s: string): string => s.replace(/\d/g, (d) => "\u09E6\u09E7\u09E8\u09E9\u09EA\u09EB\u09EC\u09ED\u09EE\u09EF"[Number(d)]!);

/** Milli-taka to whole taka (half up on the magnitude) for the lakh/crore card figures. */
export function mtkToTaka(mtk: number): number {
  if (!Number.isSafeInteger(mtk)) return Number.NaN; // not shown by formatLakhCrore (an em dash), never another amount
  const abs = BigInt(mtk < 0 ? -mtk : mtk);
  const taka = Number((abs + 500n) / 1000n); // half up on the magnitude, integer maths
  return mtk < 0 ? -taka : taka;
}

/** Percent change between two non-negative figures; null when the base is 0 or missing (never a fake comparison). */
export function pctChange(now: number | null | undefined, before: number | null | undefined): number | null {
  if (now === null || now === undefined || before === null || before === undefined || before <= 0) return null;
  return ((now - before) / before) * 100;
}
