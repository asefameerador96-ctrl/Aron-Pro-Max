import { formatNumber, t, type Locale } from "@/lib/i18n";
import type { Cell } from "@/lib/reports/format";

/** A plain horizontal bar chart (histograms, trends). One hue, value printed at the end of each bar, no colour-only encoding. */
export function BarChart({ locale, title, rows }: { locale: Locale; title: string; rows: { label: string; value: number }[] }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <figure className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm" data-testid="report-chart">
      <figcaption className="mb-2 text-sm font-semibold text-slate-700">{title}</figcaption>
      <ul className="space-y-1" aria-label={title}>
        {rows.map((r) => (
          <li key={r.label} className="flex items-center gap-2 text-xs" data-label={r.label} data-value={r.value}>
            <span className="w-28 shrink-0 truncate text-slate-600">{r.label}</span>
            <span className="h-4 rounded bg-brand-600" style={{ width: `${Math.round((r.value / max) * 100)}%`, minWidth: r.value > 0 ? "2px" : 0 }} aria-hidden="true" />
            <span className="tabular-nums">{formatNumber(locale, r.value)}</span>
          </li>
        ))}
      </ul>
      <p className="sr-only">{t(locale, "report.chart_note")}</p>
    </figure>
  );
}

/** Rows of a result as chart points, only when both columns exist and the values are numbers. */
export function chartPoints(rows: Record<string, Cell>[], label: string, value: string): { label: string; value: number }[] | null {
  if (rows.length === 0 || !(label in rows[0]!) || !(value in rows[0]!)) return null;
  const pts = rows.map((r) => ({ label: String(r[label] ?? ""), value: Number(r[value]) }));
  return pts.every((p) => Number.isFinite(p.value)) ? pts : null;
}
