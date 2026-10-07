import { t, formatBusinessDate, formatDateTime, type Locale, type MessageKey } from "@/lib/i18n";

export type StaleReason = "not_live_today" | "late_sync" | "no_data" | "partial";

const REASON_KEY: Record<StaleReason, MessageKey> = {
  not_live_today: "tile.reason.not_live_today",
  late_sync: "tile.reason.late_sync",
  no_data: "tile.reason.no_data",
  partial: "tile.reason.partial",
};

/** Business date, as-of stamp and, when the figure is not live today, a reason chip (F-WEB-068). Used under every tile. */
export function TileMeta({ locale, businessDate, asOf, today, reason }: { locale: Locale; businessDate: string; asOf: string | null; today: string; reason?: StaleReason }) {
  const live = businessDate === today;
  const chip: StaleReason | undefined = reason ?? (live ? undefined : "not_live_today");
  return (
    <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500" data-testid="tile-meta">
      <time dateTime={businessDate} data-testid="tile-date">
        {formatBusinessDate(locale, businessDate)}
      </time>
      <span data-testid="tile-asof">{asOf ? t(locale, "tile.as_of", { at: formatDateTime(locale, asOf) }) : t(locale, "tile.as_of_unknown")}</span>
      {chip ? (
        <span data-testid="tile-reason" data-reason={chip} className="rounded-full bg-amber-100 px-2 py-0.5 font-medium text-amber-900">
          {t(locale, REASON_KEY[chip])}
        </span>
      ) : null}
    </p>
  );
}
