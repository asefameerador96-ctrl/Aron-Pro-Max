import { TableSkeleton } from "@/components/dash/data-table";
import { Skeleton } from "@/components/kit/skeleton";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

/** Loading state of a drill-down: the title and two table cards, announced once. */
export default async function Loading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" aria-busy="true" data-testid="page-loading" className="space-y-6">
      <p className="sr-only">{t(locale, "common.loading")}</p>
      <Skeleton className="h-10 w-72" />
      <TableSkeleton />
      <TableSkeleton rows={3} />
    </div>
  );
}
