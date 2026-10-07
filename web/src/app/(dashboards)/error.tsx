"use client";
import { useI18n } from "@/components/i18n-provider";

/** Error boundary for dashboard pages: a calm message with a retry, never a stack trace. */
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const { t } = useI18n();
  return (
    <section role="alert" data-testid="page-error" className="rounded-lg border border-red-200 bg-red-50 p-6 text-red-900">
      <h1 className="text-lg font-semibold">{t("error.page.title")}</h1>
      <p className="mt-1 text-sm">{t("error.generic")}</p>
      <button type="button" onClick={reset} className="mt-3 rounded bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white">
        {t("error.page.retry")}
      </button>
    </section>
  );
}
