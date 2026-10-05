export const LOCALES = ["bn", "en"] as const;
export type Locale = (typeof LOCALES)[number];
export const DEFAULT_LOCALE: Locale = "bn"; // Bangla first (docs/23 Q2)
export const LOCALE_COOKIE = "aron_locale";
export function isLocale(v: unknown): v is Locale {
  return v === "bn" || v === "en";
}
