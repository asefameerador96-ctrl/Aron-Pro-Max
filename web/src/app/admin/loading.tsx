// Loading state of every admin page: calm skeleton blocks (no spinner loop), announced to screen readers.
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

export default async function AdminLoading() {
  const locale = await getLocale();
  return (
    <div role="status" aria-live="polite" aria-busy="true" data-testid="page-loading" className="space-y-4">
      <span className="sr-only">{t(locale, "common.loading")}</span>
      <div className="h-8 w-64 animate-pulse rounded-[var(--radius-chip)] bg-[var(--surface-glass)]" />
      <div className="h-24 animate-pulse rounded-[var(--radius-card)] bg-[var(--surface-glass)]" />
      <div className="h-56 animate-pulse rounded-[var(--radius-card)] bg-[var(--surface-glass)]" />
    </div>
  );
}
