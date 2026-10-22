import type { ReactNode } from "react";
import { TileMeta, type StaleReason } from "@/components/reports/tile-meta";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { formatTakaDisplay } from "@/lib/reports/format";

export interface TileDate {
  businessDate: string;
  asOf: string | null;
  today: string;
  reason?: StaleReason;
}

/** One dashboard tile on a glass card (web-glass s7.3): title, body, and the business date / as-of / reason chip under it
 *  (F-WEB-068). `data` puts the body on the near-opaque data surface (tables, charts). */
export function Tile({ id, locale, titleKey, date, children, wide, className = "", actions, heading, tip, tipAtBottom, titleSlot }: { titleSlot?: ReactNode; tipAtBottom?: boolean; id: string; tip?: ReactNode; locale: Locale; titleKey: MessageKey; heading?: string; date: TileDate; children: ReactNode; wide?: boolean; data?: boolean; className?: string; actions?: ReactNode }) {
  return (
    <section data-testid={`tile-${id}`} className={`glass-data @container/tile relative flex min-h-32 flex-col gap-2 p-(--card-pad) text-primary ${wide ? "md:col-span-2" : ""} ${className}`}>
      {tip ? <div className={`absolute end-3 ${tipAtBottom ? "bottom-2" : "top-2"}`}>{tip}</div> : null}
      <div className={`flex min-h-[3.25rem] flex-wrap items-start justify-between gap-2 border-b border-accent-graphic/40 pb-2 ${tip && !tipAtBottom ? "pe-11" : ""}`}>
        <h2 className={`min-w-0 ${titleSlot ? "" : "flex-1"} line-clamp-2 text-body-strong text-accent`} title={heading ?? t(locale, titleKey)}>{heading ?? t(locale, titleKey)}</h2>
        {titleSlot ? <div className="min-w-0 max-w-full flex-none">{titleSlot}</div> : null}
        {actions ? <div className="shrink-0">{actions}</div> : null}
      </div>
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">{children}</div>
      <TileMeta locale={locale} businessDate={date.businessDate} asOf={date.asOf} today={date.today} reason={date.reason} quiet />
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

/** Card and bar money: two decimals (docs/24 s7, tokens s7.1); report cells keep three through formatTaka. */
export const takaText = (locale: Locale, mtk: number): string => formatTakaDisplay(locale, mtk);

export const n = (locale: Locale, v: number): string => formatNumber(locale, v);
