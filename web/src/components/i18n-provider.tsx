"use client";
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { formatDateTime, formatNumber, problemMessage, t as translate, type Locale, type MessageKey } from "@/lib/i18n";

interface Ctx {
  locale: Locale;
}
const I18nContext = createContext<Ctx>({ locale: "bn" });

export function I18nProvider({ locale, children }: { locale: Locale; children: ReactNode }) {
  const value = useMemo(() => ({ locale }), [locale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/** Client-side translation helpers bound to the active locale. */
export function useI18n() {
  const { locale } = useContext(I18nContext);
  return useMemo(
    () => ({
      locale,
      t: (key: MessageKey, vars?: Record<string, string | number>) => translate(locale, key, vars),
      problem: (code: string | undefined) => problemMessage(locale, code),
      number: (n: number) => formatNumber(locale, n),
      dateTime: (iso: string) => formatDateTime(locale, iso),
    }),
    [locale],
  );
}
