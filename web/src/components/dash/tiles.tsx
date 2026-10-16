import type { ReactNode } from "react";
import { TileMeta, type StaleReason } from "@/components/reports/tile-meta";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { formatTaka } from "@/lib/reports/format";

export interface TileDate {
  businessDate: string;
  asOf: string | null;
  today: string;
  reason?: StaleReason;
}

/** One dashboard tile on a glass card (web-glass s7.3): title, body, and the business date / as-of / reason chip under it
 *  (F-WEB-068). `data` puts the body on the near-opaque data surface (tables, charts). */
export function Tile({ id, locale, titleKey, date, children, wide, data, className = "", actions }: { id: string; locale: Locale; titleKey: MessageKey; date: TileDate; children: ReactNode; wide?: boolean; data?: boolean; className?: string; actions?: ReactNode }) {
  return (
    <section data-testid={`tile-${id}`} className={`${data ? "glass-data" : "glass-card"} relative flex min-h-32 flex-col gap-2 p-(--card-pad) text-primary ${wide ? "md:col-span-2" : ""} ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-label text-secondary">{t(locale, titleKey)}</h2>
        {actions}
      </div>
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">{children}</div>
      <TileMeta locale={locale} businessDate={date.businessDate} asOf={date.asOf} today={date.today} reason={date.reason} />
    </section>
  );
}

export const Big = ({ children, testId }: { children: ReactNode; testId?: string }) => (
  <p className="text-display text-primary tabular-nums" data-testid={testId}>
    {children}
  </p>
);

/** Percent from the contract (0 to 100, 2 decimals, null when the denominator is 0). */
export function pctText(locale: Locale, v: number | null | undefined): string {
  return v === null || v === undefined ? "—" : `${formatNumber(locale, v, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
}

export const takaText = (locale: Locale, mtk: number): string => formatTaka(locale, mtk);

export const n = (locale: Locale, v: number): string => formatNumber(locale, v);
