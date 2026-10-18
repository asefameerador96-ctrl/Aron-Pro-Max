import { type Locale } from "@/lib/i18n";

// Milli-taka are integers; every split into taka and paisa is BigInt maths (docs/24 s7.1: no float on money), so a value above
// 2^53 passed as a bigint or a digit string stays exact. A number that is not a safe integer is refused with an em dash.
export type Mtk = number | bigint | string;

function toBig(mtk: Mtk): bigint | null {
  if (typeof mtk === "bigint") return mtk;
  const raw = typeof mtk === "number" ? (Number.isSafeInteger(mtk) ? String(mtk) : "") : mtk;
  return /^-?\d+$/.test(raw) ? BigInt(raw) : null;
}

const group = (locale: Locale, n: bigint): string => new Intl.NumberFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-US", { useGrouping: true }).format(n);
const plain = (locale: Locale, s: string): string => (locale === "bn" ? s.replace(/\d/g, (d) => "\u09E6\u09E7\u09E8\u09E9\u09EA\u09EB\u09EC\u09ED\u09EE\u09EF"[Number(d)]!) : s);

/** Milli-taka (integer) as taka with all three decimals (nothing rounded): what a money approver signs off. */
export function formatMtkExact(locale: Locale, mtk: Mtk): string {
  const v = toBig(mtk);
  if (v === null) return "\u2014";
  const abs = v < 0n ? -v : v;
  const sign = v < 0n ? "-" : "";
  return `${sign}${group(locale, abs / 1000n)}.${plain(locale, String(abs % 1000n).padStart(3, "0"))}`;
}

/** Milli-taka (integer) as taka with two decimals (half up on the magnitude), BigInt maths; Bangla digits in bn. */
export function formatMtk(locale: Locale, mtk: Mtk): string {
  const v = toBig(mtk);
  if (v === null) return "\u2014";
  const abs = v < 0n ? -v : v;
  const paisaTotal = (abs + 5n) / 10n;
  const sign = v < 0n && paisaTotal > 0n ? "-" : "";
  return `${sign}${group(locale, paisaTotal / 100n)}.${plain(locale, String(paisaTotal % 100n).padStart(2, "0"))}`;
}
