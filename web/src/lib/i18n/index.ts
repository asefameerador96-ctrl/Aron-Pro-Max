import { bn } from "./messages-bn";
import { en, type MessageKey } from "./messages-en";
import type { Locale } from "./types";

export type { MessageKey } from "./messages-en";
export { DEFAULT_LOCALE, LOCALES, LOCALE_COOKIE, isLocale, type Locale } from "./types";

const catalogues: Record<Locale, Record<MessageKey, string>> = { bn, en };

/** Translate a catalogue key; `{name}` placeholders are replaced from `vars`. Values are formatted per locale. */
export function t(locale: Locale, key: MessageKey, vars?: Record<string, string | number>): string {
  const raw = catalogues[locale][key] ?? en[key];
  if (!vars) return raw;
  return raw.replace(/\{(\w+)\}/g, (_m, name: string) => {
    const v = vars[name];
    return v === undefined ? `{${name}}` : typeof v === "number" ? formatNumber(locale, v) : v;
  });
}

/** Message for a problem `code`, falling back to the generic text. Clients branch on `code` only (docs/24 s3.4). */
export function problemMessage(locale: Locale, code: string | undefined): string {
  const key = `error.${code ?? ""}` as MessageKey;
  return key in en ? t(locale, key) : t(locale, "error.generic");
}

const numberLocale: Record<Locale, string> = { bn: "bn-BD-u-nu-beng", en: "en-US" };

/** Bengali digits for bn, Latin for en (docs/23 Q2). */
export function formatNumber(locale: Locale, n: number, opts?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(numberLocale[locale], opts).format(n);
}

/** A timestamp in Asia/Dhaka (UTC+6, no DST), localised digits. */
export function formatDateTime(locale: Locale, iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-GB", {
    timeZone: "Asia/Dhaka",
    dateStyle: "medium",
    timeStyle: "short",
  }).format(d);
}

/** Business date (YYYY-MM-DD, Dhaka) of an instant. */
export function businessDate(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
  return parts; // en-CA yields YYYY-MM-DD
}

export function formatBusinessDate(locale: Locale, ymd: string): string {
  const [y, m, d] = ymd.split("-").map(Number);
  if (!y || !m || !d) return ymd;
  return new Intl.DateTimeFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-GB", { timeZone: "UTC", dateStyle: "long" }).format(new Date(Date.UTC(y, m - 1, d)));
}
