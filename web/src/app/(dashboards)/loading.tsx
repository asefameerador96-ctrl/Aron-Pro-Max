import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

/** Shown while a dashboard or report page loads: calm skeleton tiles, announced to screen readers, no spinner loops. */
export default async function Loading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" data-testid="page-loading" className="space-y-4">
      <p className="sr-only">{t(locale, "common.loading")}</p>
      <div className="h-8 w-56 animate-pulse rounded bg-slate-200/70" />
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-28 animate-pulse rounded-lg bg-slate-200/60" />
        ))}
      </div>
    </div>
  );
}
