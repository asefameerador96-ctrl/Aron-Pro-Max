import type { ReactNode } from "react";
import { RowsTable } from "@/components/dash/rows-table";
import { CsvButton, PrintButton, RetryButton } from "@/components/dash/table-actions";
import { Icon } from "@/components/kit/icons";
import { Skeleton } from "@/components/kit/skeleton";
import { TileMeta } from "@/components/reports/tile-meta";
import { problemMessage, t, type Locale } from "@/lib/i18n";

export interface Col {
  header: string;
  numeric?: boolean;
}
export interface TRow {
  key: string;
  /** Display text per column (digits localised by the caller). */
  cells: string[];
  /** CSV text per column when it differs from the display (money in taka with three decimals, no grouping). */
  csv?: string[];
  href?: string;
}

/** A dashboard table on the data surface: title and rule like the tiles, CSV and print actions, the as-of stamp, and the
 *  three states every table has: empty (a message), error (the problem text and Retry), and, through `TableSkeleton` in the
 *  route's loading file, loading. The rows are the same strings the CSV carries. */
export function DataTable({ id, locale, heading, columns, rows, csvName, asOf, businessDate, today, error, note, className = "", extra }: { id: string; locale: Locale; heading: string; columns: Col[]; rows: TRow[]; csvName: string; asOf: string | null; businessDate: string; today: string; error?: string; note?: string; className?: string; extra?: ReactNode }) {
  const failed = error !== undefined;
  return (
    <section data-testid={`table-${id}`} className={`glass-data print-block flex flex-col gap-2 p-(--card-pad) text-primary ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-accent-graphic/40 pb-2">
        <h2 className="text-body-strong text-accent" id={`h-${id}`}>
          {heading}
        </h2>
        {!failed && rows.length > 0 ? (
          <div className="no-print flex items-center gap-1">
            <CsvButton msg={t(locale, "table.csv")} filename={csvName} header={columns.map((c) => c.header)} rows={rows.map((r) => r.csv ?? r.cells)} />
            <PrintButton msg={t(locale, "table.print")} />
          </div>
        ) : null}
      </div>
      {note ? <p className="text-caption text-secondary">{note}</p> : null}
      {extra}
      {failed ? (
        <div className="flex flex-wrap items-center gap-3" data-testid={`table-error-${id}`}>
          <p role="alert" className="flex items-center gap-2 text-body text-danger-on-container">
            <Icon name="error" className="text-danger" />
            {problemMessage(locale, error || undefined)}
          </p>
          <span className="no-print">
            <RetryButton msg={t(locale, "table.retry")} />
          </span>
        </div>
      ) : rows.length === 0 ? (
        <p className="text-body text-secondary" data-testid={`table-empty-${id}`}>
          {t(locale, "table.empty")}
        </p>
      ) : (
        <div role="region" aria-labelledby={`h-${id}`} tabIndex={0} className="overflow-x-auto rounded-chip focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
          <RowsTable id={id} columns={columns} rows={rows} moreMsg={t(locale, "table.more")} />
        </div>
      )}
      <TileMeta locale={locale} businessDate={businessDate} asOf={asOf} today={today} />
    </section>
  );
}

/** Loading state of a table: the card with sweeping rows, announced once by the route's status region. */
export function TableSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="glass-data flex flex-col gap-3 p-(--card-pad)" aria-hidden="true">
      <Skeleton className="h-5 w-48" />
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-8 w-full" />
      ))}
    </div>
  );
}
