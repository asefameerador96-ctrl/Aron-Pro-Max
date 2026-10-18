// Money is an integer number of milli-taka (1 taka = 1000 mtk); prices are stored to three decimals (docs/24 s7).
// Parsing and formatting never go through floating point.
import type { Locale } from "@/lib/i18n";

const BN = "০১২৩৪৫৬৭৮৯";
const toAscii = (s: string): string => s.replace(/[০-৯]/g, (d) => String(BN.indexOf(d)));

/** "12.5" or "১২.৫০০" taka to 12500 mtk; null when the text is not a non-negative amount with at most 3 decimals. */
export function parseTakaToMtk(input: string): number | null {
  const s = toAscii(input.trim());
  const m = /^(\d{1,12})(?:\.(\d{1,3}))?$/.exec(s);
  if (!m) return null;
  const mtk = BigInt(m[1]!) * 1000n + BigInt((m[2] ?? "").padEnd(3, "0"));
  return mtk <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(mtk) : null;
}

/** 12500 mtk to "12.500" (ASCII) with exactly three decimals. */
export function mtkToTaka(mtk: number | bigint): string {
  const v = typeof mtk === "bigint" ? mtk : Number.isSafeInteger(mtk) ? BigInt(mtk) : null;
  if (v === null) return "\u2014"; // never show a different amount for a value that is not an exact integer
  const abs = v < 0n ? -v : v;
  return `${v < 0n ? "-" : ""}${abs / 1000n}.${String(abs % 1000n).padStart(3, "0")}`;
}

/** Localised: Bengali digits for bn. */
export function formatPriceMtk(locale: Locale, mtk: number | bigint): string {
  const s = mtkToTaka(mtk);
  return locale === "bn" ? s.replace(/\d/g, (d) => BN[Number(d)] ?? d) : s;
}
