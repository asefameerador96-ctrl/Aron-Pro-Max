// Cell formatting by contract column type (docs/24 s12.3). Money arrives as integer milli-taka and is shown in taka with three
// decimals (7.935); percentages are 0 to 100; quantities are integers in the SKU's own unit. No float arithmetic on money.
import type { Schemas } from "@/contract/types";
import { formatBusinessDate, formatDateTime, formatNumber, type Locale } from "@/lib/i18n";

export type ReportColumn = Schemas["ReportColumn"];
export type Cell = string | number | boolean | null | undefined;

/** Integer milli-taka to a decimal string with exactly three decimals, by integer maths (never `n / 1000`). */
export function mtkToTaka(mtk: number | string): string {
  const neg = String(mtk).startsWith("-");
  const digits = String(mtk).replace(/^-/, "").replace(/\D/g, "").padStart(4, "0");
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
export function formatTaka(locale: Locale, mtk: number | string): string {
  const plain = mtkToTaka(mtk);
  const neg = plain.startsWith("-");
  const [whole = "0", frac = "000"] = plain.replace("-", "").split(".");
  const grouped = new Intl.NumberFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-US", { useGrouping: true }).format(BigInt(whole));
  return `${neg ? "-" : ""}${grouped}.${localiseDecimal(locale, frac)}`;
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
      return typeof v === "number" ? `${formatNumber(locale, v, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%` : String(v);
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
