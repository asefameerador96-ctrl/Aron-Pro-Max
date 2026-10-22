// Number and time text of the national dashboard. Quantities in Lakh / Crore with two decimals, Bengali digits in Bangla mode (docs/34 s2).
import { formatLakhCrore } from "@/lib/dash/lakh";
import { formatNumber, t, type Locale } from "@/lib/i18n";

/** 3.64 Cr, 34.88 Lakh, 11,165: the card format of the original portal. Non-integers are rounded half up first. */
export function qty(locale: Locale, n: number): string {
  return formatLakhCrore(locale, Math.round(n), { lakh: t(locale, "unit.lakh"), crore: t(locale, "unit.crore") });
}

/** Percent with `digits` decimals (1 on most cards, 2 on Geo Fencing), "—" when there is nothing to divide by. */
export function pct(locale: Locale, v: number | null | undefined, digits = 1): string {
  return v === null || v === undefined || !Number.isFinite(v) ? "—" : `${formatNumber(locale, v, { minimumFractionDigits: digits, maximumFractionDigits: digits })}%`;
}

/** HH:mm (Dhaka) of an instant, localised digits; "" for a missing or invalid instant. */
export function clock(locale: Locale, iso: string | null, seconds = false): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return new Intl.DateTimeFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-GB", { timeZone: "Asia/Dhaka", hour: "2-digit", minute: "2-digit", ...(seconds ? { second: "2-digit" } : {}), hourCycle: "h23" }).format(d);
}

/** Hours between two instants with two decimals (the route map's time card); null when either is missing. */
export function hoursBetween(a: string | null, b: string | null): number | null {
  if (!a || !b) return null;
  const ms = Date.parse(b) - Date.parse(a);
  return Number.isFinite(ms) && ms >= 0 ? Math.round((ms / 3_600_000) * 100) / 100 : null;
}

/** Distance covered: metres with two decimals below 1,000 m, kilometres with one decimal from 1,000 m (docs/34 s6 Q1). */
export function distance(locale: Locale, m: number): string {
  return m < 1000 ? `${formatNumber(locale, m, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} m` : `${formatNumber(locale, m / 1000, { minimumFractionDigits: 1, maximumFractionDigits: 1 })} km`;
}
