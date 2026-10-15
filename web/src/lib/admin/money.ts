import { formatNumber, type Locale } from "@/lib/i18n";

/** Milli-taka (integer) as taka with all three decimals (nothing rounded): what a money approver signs off. */
export function formatMtkExact(locale: Locale, mtk: number): string {
  const abs = Math.abs(Math.trunc(mtk));
  const sign = mtk < 0 && abs > 0 ? "-" : "";
  return `${sign}${formatNumber(locale, Math.floor(abs / 1000))}.${formatNumber(locale, abs % 1000, { minimumIntegerDigits: 3, useGrouping: false })}`;
}

/** Milli-taka (integer) as taka with two decimals, using integer arithmetic only; Bangla digits in bn. */
export function formatMtk(locale: Locale, mtk: number): string {
  const abs = Math.abs(Math.trunc(mtk));
  let whole = Math.floor(abs / 1000);
  let paisa = Math.round((abs % 1000) / 10);
  if (paisa === 100) {
    whole += 1;
    paisa = 0;
  }
  const sign = mtk < 0 && (whole > 0 || paisa > 0) ? "-" : "";
  return `${sign}${formatNumber(locale, whole)}.${formatNumber(locale, paisa, { minimumIntegerDigits: 2, useGrouping: false })}`;
}
