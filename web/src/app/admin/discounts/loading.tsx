// Loading state of the discount screens (web-glass s12.1): calm skeleton blocks, announced to screen readers.
import { Skeleton } from "@/components/kit/skeleton";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

export default async function DiscountsLoading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" aria-busy="true" data-testid="page-loading" className="space-y-6">
      <span className="sr-only">{t(locale, "disc.loading")}</span>
      <Skeleton className="h-10 w-72" />
      <Skeleton className="h-14 w-full rounded-card" />
      <Skeleton className="h-64 w-full rounded-card" />
    </div>
  );
}
