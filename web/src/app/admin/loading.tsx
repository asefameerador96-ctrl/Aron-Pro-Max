// Loading state of every admin page (web-glass s12.1): calm skeleton blocks (sweep capped at 2 cycles), announced to screen readers.
import { Skeleton } from "@/components/kit/skeleton";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

export default async function AdminLoading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" aria-busy="true" data-testid="page-loading" className="space-y-6">
      <span className="sr-only">{t(locale, "common.loading")}</span>
      <Skeleton className="h-10 w-64" />
      <Skeleton className="h-24 w-full rounded-card" />
      <Skeleton className="h-56 w-full rounded-card" />
    </div>
  );
}
