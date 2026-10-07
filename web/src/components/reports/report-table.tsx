import { t, formatNumber, type Locale } from "@/lib/i18n";
import { PII_MASK, columnLabel, formatCell, isNumeric, type Cell, type ReportColumn } from "@/lib/reports/format";
import type { ReportResult } from "@/lib/reports/server";

/** Result table. PII columns are masked here as well as on the server unless the caller holds the PII permission (defence in depth). */
export function ReportTable({ result, locale, pii }: { result: ReportResult; locale: Locale; pii: boolean }) {
  const labels = { yes: t(locale, "common.yes"), no: t(locale, "common.no") };
  const cols: ReportColumn[] = result.columns;
  if (result.rows.length === 0) {
    return (
      <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
        {t(locale, "report.empty")}
      </p>
    );
  }
  const show = (c: ReportColumn, v: Cell) => (c.pii && !pii ? PII_MASK : formatCell(locale, c, v, labels));
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="report-table">
        <caption className="sr-only">{t(locale, "report.table")}</caption>
        <thead className="bg-slate-50">
          <tr>
            {cols.map((c) => (
              <th key={c.key} scope="col" data-col={c.key} className={`whitespace-nowrap px-3 py-2 font-semibold text-slate-700 ${isNumeric(c) ? "text-right" : "text-left"}`}>
                {columnLabel(locale, c)}
                {c.unit ? <span className="ml-1 font-normal text-slate-500">({c.unit})</span> : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {result.rows.map((r, i) => (
            <tr key={i} className="hover:bg-slate-50">
              {cols.map((c) => (
                <td key={c.key} data-col={c.key} data-pii={c.pii && !pii ? "masked" : undefined} className={`whitespace-nowrap px-3 py-2 ${isNumeric(c) ? "text-right tabular-nums" : "text-left"}`}>
                  {show(c, r[c.key] as Cell)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
        {result.totals ? (
          <tfoot className="bg-slate-50 font-semibold" data-testid="report-totals">
            <tr>
              {cols.map((c, i) => (
                <td key={c.key} data-col={c.key} className={`whitespace-nowrap px-3 py-2 ${isNumeric(c) ? "text-right tabular-nums" : "text-left"}`}>
                  {i === 0 && result.totals?.[c.key] === undefined ? t(locale, "report.totals") : show(c, result.totals?.[c.key] as Cell)}
                </td>
              ))}
            </tr>
          </tfoot>
        ) : null}
      </table>
      <p className="px-3 py-2 text-xs text-slate-500" data-testid="row-count">
        {t(locale, "report.rows", { n: formatNumber(locale, result.total_rows) })}
      </p>
    </div>
  );
}
