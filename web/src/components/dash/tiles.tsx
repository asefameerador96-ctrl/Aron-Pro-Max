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

/** One dashboard tile: title, body, and the business date / as-of / reason chip under it (F-WEB-068). */
export function Tile({ id, locale, titleKey, date, children, wide }: { id: string; locale: Locale; titleKey: MessageKey; date: TileDate; children: ReactNode; wide?: boolean }) {
  return (
    <section data-testid={`tile-${id}`} className={`rounded-lg border border-slate-200 bg-white p-4 shadow-sm ${wide ? "md:col-span-2" : ""}`}>
      <h2 className="text-sm font-semibold text-slate-600">{t(locale, titleKey)}</h2>
      <div className="mt-1">{children}</div>
      <TileMeta locale={locale} businessDate={date.businessDate} asOf={date.asOf} today={date.today} reason={date.reason} />
    </section>
  );
}

export const Big = ({ children, testId }: { children: ReactNode; testId?: string }) => (
  <p className="text-2xl font-semibold tabular-nums" data-testid={testId}>
    {children}
  </p>
);

/** Percent from the contract (0 to 100, 2 decimals, null when the denominator is 0). */
export function pctText(locale: Locale, v: number | null | undefined): string {
  return v === null || v === undefined ? "—" : `${formatNumber(locale, v, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%`;
}

export const takaText = (locale: Locale, mtk: number): string => formatTaka(locale, mtk);

export const n = (locale: Locale, v: number): string => formatNumber(locale, v);
