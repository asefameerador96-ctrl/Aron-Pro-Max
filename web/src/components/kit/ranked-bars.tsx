// Ranked horizontal bars (web-glass s10, dataviz): one hue (viz slot 1), the value printed at the tip, colour never encodes
// rank, bars square at the baseline and rounded at the data end. The list itself is the accessible form (label, value).
import Link from "next/link";
import type { ReactNode } from "react";

export interface RankedRow {
  key: string;
  label: string;
  value: number;
  valueText: ReactNode;
  sub?: ReactNode;
  href?: string;
}

export function RankedBars({ rows, label, testId, max: maxIn, className = "" }: { rows: readonly RankedRow[]; label: string; testId?: string; max?: number; className?: string }) {
  const max = Math.max(1, maxIn ?? 0, ...rows.map((r) => r.value));
  return (
    <ul aria-label={label} data-testid={testId} className={`space-y-2 ${className}`}>
      {rows.map((r) => {
        const pct = Math.max(0, Math.min(100, (r.value / max) * 100));
        const name = <span className="line-clamp-2 text-body text-primary [overflow-wrap:anywhere]" title={r.label}>{r.label}</span>;
        return (
          <li key={r.key} data-label={r.label} data-value={r.value} className="grid grid-cols-[minmax(5.5rem,min(220px,30%))_1fr] items-center gap-x-3 gap-y-0.5">
            {r.href ? (
              <Link href={r.href} className="rounded-chip hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
                {name}
              </Link>
            ) : (
              name
            )}
            <div className="flex items-center gap-2">
              <span className="h-6 rounded-e bg-viz-1" style={{ width: `${pct}%`, minWidth: r.value > 0 ? "3px" : 0 }} aria-hidden="true" />
              <span className="shrink-0 text-body-strong text-primary tabular-nums">{r.valueText}</span>
            </div>
            {r.sub ? <span className="col-start-2 text-caption text-secondary">{r.sub}</span> : null}
          </li>
        );
      })}
    </ul>
  );
}
