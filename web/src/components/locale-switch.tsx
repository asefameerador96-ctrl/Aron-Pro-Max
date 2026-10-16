"use client";
import { usePathname, useSearchParams } from "next/navigation";
import { useI18n } from "./i18n-provider";

/** Language switch as a small segmented control of links (web-glass s6: "বাংলা | EN"); the current one carries aria-current. */
export function LocaleSwitch() {
  const { locale, t } = useI18n();
  const path = usePathname();
  const qs = useSearchParams().toString();
  const next = encodeURIComponent(path + (qs ? `?${qs}` : ""));
  return (
    <nav aria-label={t("common.language")} className="seg">
      {(["bn", "en"] as const).map((l) => (
        <a key={l} href={`/api/bff/locale?l=${l}&next=${next}`} lang={l} aria-current={l === locale ? "true" : undefined}>
          {t(l === "bn" ? "common.language.bn" : "common.language.en")}
        </a>
      ))}
    </nav>
  );
}
