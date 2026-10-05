"use client";
import { usePathname, useSearchParams } from "next/navigation";
import { useI18n } from "./i18n-provider";

export function LocaleSwitch() {
  const { locale, t } = useI18n();
  const path = usePathname();
  const qs = useSearchParams().toString();
  const next = encodeURIComponent(path + (qs ? `?${qs}` : ""));
  return (
    <nav aria-label={t("common.language")} className="flex items-center gap-1 text-sm">
      {(["bn", "en"] as const).map((l) => (
        <a
          key={l}
          href={`/api/bff/locale?l=${l}&next=${next}`}
          lang={l}
          aria-current={l === locale ? "true" : undefined}
          className={`rounded px-2 py-1 ${l === locale ? "bg-brand-600 text-white" : "text-slate-700 hover:bg-slate-200"}`}
        >
          {t(l === "bn" ? "common.language.bn" : "common.language.en")}
        </a>
      ))}
    </nav>
  );
}
