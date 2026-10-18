import { Skeleton } from "@/components/kit/skeleton";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

/** Shown while a dashboard or report page loads (web-glass s12.1): skeletons that mirror the card layout, announced to screen readers, sweep capped. */
export default async function Loading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" aria-busy="true" data-testid="page-loading" className="space-y-6">
      <p className="sr-only">{t(locale, "common.loading")}</p>
      <Skeleton className="h-10 w-56" />
      <div className="grid grid-cols-1 gap-(--gap) min-[480px]:grid-cols-2 lg:grid-cols-12" data-testid="tiles-loading">
        {Array.from({ length: 8 }, (_, i) => (
          <div key={i} className="glass-card col-span-full flex min-h-32 flex-col gap-3 p-(--card-pad) min-[480px]:col-span-1 lg:col-span-6 xl:col-span-3">
            <Skeleton className="h-4 w-28" />
            <Skeleton className="h-10 w-36" />
            <Skeleton className="h-3 w-24" />
          </div>
        ))}
        <div className="glass-data col-span-full flex flex-col gap-3 p-(--card-pad)">
          <Skeleton className="h-4 w-40" />
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-7 w-full" />
          ))}
        </div>
      </div>
    </div>
  );
}
