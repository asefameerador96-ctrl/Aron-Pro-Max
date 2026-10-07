"use client";
// Error state of every admin page: plain words, a retry, nothing technical on screen.
import { useI18n } from "@/components/i18n-provider";

export default function AdminError({ reset }: { error: Error; reset: () => void }) {
  const { t } = useI18n();
  return (
    <div role="alert" data-testid="page-error" className="space-y-3 rounded-[var(--radius-card)] border border-[var(--danger)] bg-[color-mix(in_srgb,var(--danger)_10%,transparent)] p-5">
      <p className="text-[var(--danger)]">{t("error.generic")}</p>
      <button type="button" onClick={reset} className="rounded-full bg-brand-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-brand-700">
        {t("cfgc.retry")}
      </button>
    </div>
  );
}
