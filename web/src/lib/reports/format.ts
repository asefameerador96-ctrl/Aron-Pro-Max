// Cell formatting by contract column type (docs/24 s12.3). Money arrives as integer milli-taka and is shown in taka with three
// decimals (7.935); percentages are 0 to 100; quantities are integers in the SKU's own unit. No float arithmetic on money.
import type { Schemas } from "@/contract/types";
import { formatBusinessDate, formatDateTime, formatNumber, type Locale } from "@/lib/i18n";

export type ReportColumn = Schemas["ReportColumn"];
export type Cell = string | number | boolean | null | undefined;

/** Integer milli-taka to a decimal string with exactly three decimals, by integer maths (never `n / 1000`). */
export function mtkToTaka(mtk: number | string | bigint): string {
  const raw = typeof mtk === "number" ? (Number.isSafeInteger(mtk) ? String(mtk) : "") : String(mtk);
  if (!/^-?\d+$/.test(raw)) return "—"; // never show a different amount for a malformed one
  const neg = raw.startsWith("-");
  const digits = raw.replace(/^-/, "").padStart(4, "0");
  const whole = digits.slice(0, -3).replace(/^0+(?=\d)/, "");
  return `${neg ? "-" : ""}${whole}.${digits.slice(-3)}`;
}

/** Localise a plain decimal string's digits (Bengali digits for bn) keeping its decimals. */
function localiseDecimal(locale: Locale, s: string): string {
  if (locale === "en") return s;
  const map = "০১২৩৪৫৬৭৮৯";
  return s.replace(/\d/g, (d) => map[Number(d)] ?? d);
}

/** Money for display: taka with exactly three decimals and locale digit grouping, from integer milli-taka (BigInt, no float). */
export function formatTaka(locale: Locale, mtk: number | string | bigint): string {
  const plain = mtkToTaka(mtk);
  if (plain === "—") return plain;
  const neg = plain.startsWith("-");
  const [whole = "0", frac = "000"] = plain.replace("-", "").split(".");
  const grouped = new Intl.NumberFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-US", { useGrouping: true }).format(BigInt(whole));
  return `${neg ? "-" : ""}${grouped}.${localiseDecimal(locale, frac)}`;
}

/** Money on cards, hero figures and ranked bars (docs/24 s7, tokens.md s7.1, web-glass s4): taka with exactly two decimals,
 *  rounded half away from zero on the integer milli-taka (never `n / 1000`), locale digit grouping. Report cells keep the
 *  three decimals of `formatTaka` (docs/24 s12.3: unit prices such as 7.935 must not be hidden). */
export function formatTakaDisplay(locale: Locale, mtk: number | string | bigint): string {
  const raw = typeof mtk === "number" ? (Number.isSafeInteger(mtk) ? String(mtk) : "") : String(mtk);
  if (!/^-?\d+$/.test(raw)) return "—";
  const neg = raw.startsWith("-");
  const paisa = (BigInt(raw.replace(/^-/, "")) + 5n) / 10n; // milli-taka to paisa, half up on the magnitude
  const whole = paisa / 100n;
  const frac = String(paisa % 100n).padStart(2, "0");
  const grouped = new Intl.NumberFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-US", { useGrouping: true }).format(whole);
  const sign = neg && paisa > 0n ? "-" : "";
  return `${sign}${grouped}.${localiseDecimal(locale, frac)}`;
}

export const PII_MASK = "••••";

export function formatCell(locale: Locale, col: ReportColumn, v: Cell, labels: { yes: string; no: string }): string {
  if (v === null || v === undefined || v === "") return "—";
  switch (col.type) {
    case "mtk":
      return formatTaka(locale, v as number | string);
    case "integer":
      return typeof v === "number" ? formatNumber(locale, v, { maximumFractionDigits: 0 }) : String(v);
    case "decimal":
      return typeof v === "number" ? formatNumber(locale, v, { minimumFractionDigits: 3, maximumFractionDigits: 3 }) : String(v);
    case "pct":
      return typeof v === "number" ? `${formatNumber(locale, v, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%` : String(v);
    case "date":
      return typeof v === "string" ? formatBusinessDate(locale, v) : String(v);
    case "timestamp":
      return typeof v === "string" ? formatDateTime(locale, v) : String(v);
    case "bool":
      return v ? labels.yes : labels.no;
    default:
      return String(v);
  }
}

export function isNumeric(col: ReportColumn): boolean {
  return col.type === "integer" || col.type === "decimal" || col.type === "mtk" || col.type === "pct";
}

export function columnLabel(locale: Locale, col: ReportColumn): string {
  return locale === "bn" ? (col.label_bn ?? col.label_en) : col.label_en;
}
