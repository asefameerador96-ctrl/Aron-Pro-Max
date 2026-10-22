import { t, formatBusinessDate, formatDateTime, type Locale, type MessageKey } from "@/lib/i18n";
import { Icon } from "@/components/kit/icons";

export type StaleReason = "not_live_today" | "late_sync" | "no_data" | "partial";

const REASON_KEY: Record<StaleReason, MessageKey> = {
  not_live_today: "tile.reason.not_live_today",
  late_sync: "tile.reason.late_sync",
  no_data: "tile.reason.no_data",
  partial: "tile.reason.partial",
};

/** Business date, as-of stamp and, when the figure is not live today, a reason chip (F-WEB-068; web-glass s7.3). Used under every tile. */
export function TileMeta({ locale, businessDate, asOf, today, reason, quiet = false }: { locale: Locale; businessDate: string; asOf: string | null; today: string; reason?: StaleReason; /** the home shows one As-of stamp in its header: a live tile keeps its date and stamp for screen readers only and shows them when it is stale */ quiet?: boolean }) {
  const live = businessDate === today;
  const chip: StaleReason | undefined = reason ?? (live ? undefined : "not_live_today");
  return (
    <p className={quiet && !chip ? "sr-only" : "mt-auto flex flex-wrap items-center gap-x-2 gap-y-1 pt-1 text-caption text-secondary"} data-testid="tile-meta">
      <time dateTime={businessDate} data-testid="tile-date">
        {formatBusinessDate(locale, businessDate)}
      </time>
      <span data-testid="tile-asof">{asOf ? t(locale, "tile.as_of", { at: formatDateTime(locale, asOf) }) : t(locale, "tile.as_of_unknown")}</span>
      {chip ? (
        <span data-testid="tile-reason" data-reason={chip} className="inline-flex min-h-6 items-center gap-1 rounded-chip bg-warning-container px-2 text-label text-warning-on-container">
          <Icon name="clock" size={14} />
          {t(locale, REASON_KEY[chip])}
        </span>
      ) : null}
    </p>
  );
}
