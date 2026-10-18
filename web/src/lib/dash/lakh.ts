import { formatNumber, type Locale } from "@/lib/i18n";

export interface LakhWords {
  lakh: string;
  crore: string;
}

/** South-Asian display of a large count: 3.61 Cr, 65.07 Lakh, 28,067 (Bengali digits and words in bn). Integers only; the
 *  server value is never rounded below a lakh. */
export function formatLakhCrore(locale: Locale, value: number, words: LakhWords): string {
  if (!Number.isFinite(value)) return "—";
  const abs = Math.abs(value);
  const sign = value < 0 ? "-" : "";
  const two = { minimumFractionDigits: 2, maximumFractionDigits: 2 } as const;
  if (abs >= 1e7) return `${sign}${formatNumber(locale, abs / 1e7, two)} ${words.crore}`;
  if (abs >= 1e5) return `${sign}${formatNumber(locale, abs / 1e5, two)} ${words.lakh}`;
  return `${sign}${formatNumber(locale, abs, { maximumFractionDigits: 0 })}`;
}

/** Milli-taka to whole taka (half up on the magnitude) for the lakh/crore card figures. */
export function mtkToTaka(mtk: number): number {
  if (!Number.isSafeInteger(mtk)) return Math.sign(mtk) * Math.round(Math.abs(mtk) / 1000);
  const abs = BigInt(Math.abs(mtk)); // integer maths: (abs + 500) / 1000 is half up with no float rounding
  return Math.sign(mtk) * Number((abs + 500n) / 1000n);
}

/** Percent change between two non-negative figures; null when the base is 0 or missing (never a fake comparison). */
export function pctChange(now: number | null | undefined, before: number | null | undefined): number | null {
  if (now === null || now === undefined || before === null || before === undefined || before <= 0) return null;
  return ((now - before) / before) * 100;
}
