// Dashboard charts (dataviz skill, web-glass s10): inline SVG, no dependency, hues from the --viz-* tokens so light and dark
// follow the theme, every value printed as text on a solid surface and every chart reachable by a text list.
import type { ReactNode } from "react";

export const VIZ = ["var(--viz-1)", "var(--viz-2)", "var(--viz-3)", "var(--viz-4)", "var(--viz-5)", "var(--viz-6)", "var(--viz-7)", "var(--viz-8)"] as const;

/** Ring gauge: a 0 to 100 value on a track, the figure printed in the middle as text. Null draws an empty track. The
 *  component is ready for a target-based percentage later (docs/27); today the callers pass a share or a rate. */
export function Ring({ pct, valueText, label, size = 112, hue = 0, children, testId }: { pct: number | null | undefined; valueText: string; label: string; size?: number; hue?: number; children?: ReactNode; testId?: string }) {
  const v = pct === null || pct === undefined ? 0 : Math.max(0, Math.min(100, pct));
  const r = 42;
  const c = 2 * Math.PI * r;
  return (
    <div role="img" aria-label={`${label}: ${valueText}`} data-testid={testId} data-pct={pct ?? ""} className="relative shrink-0" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" className="-rotate-90">
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth="11" />
        <circle cx="50" cy="50" r={r} fill="none" stroke={VIZ[hue % VIZ.length]} strokeWidth="11" strokeLinecap="round" strokeDasharray={`${(v / 100) * c} ${c}`} />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className="text-heading text-primary tabular-nums">{valueText}</span>
        {children}
      </div>
    </div>
  );
}

export interface Slice {
  key: string;
  label: string;
  value: number;
  valueText: string;
  shareText: string;
  /** Drill-down for this slice (its legend label becomes a link). */
  href?: string;
}

/** Donut with a printed legend (value and share per slice). Zero and negative slices are dropped from the arc but stay in the legend. */
export function Donut({ slices, label, centerText, testId, size = 140 }: { slices: readonly Slice[]; label: string; centerText?: string; testId?: string; size?: number }) {
  const total = slices.reduce((s, x) => s + Math.max(0, x.value), 0);
  const r = 40;
  const c = 2 * Math.PI * r;
  let acc = 0;
  return (
    <div className="flex flex-wrap items-center gap-4" data-testid={testId}>
      <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={label}>
        <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" className="-rotate-90">
          <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth="16" />
          {total > 0
            ? slices.map((s, i) => {
                const len = (Math.max(0, s.value) / total) * c;
                const dash = Math.max(0, len - (slices.length > 1 ? 0.8 : 0));
                const el = <circle key={s.key} cx="50" cy="50" r={r} fill="none" stroke={VIZ[i % VIZ.length]} strokeWidth="16" strokeDasharray={`${dash} ${c}`} strokeDashoffset={-acc} />;
                acc += len;
                return el;
              })
            : null}
        </svg>
        {centerText ? <span className={`absolute inset-0 flex items-center justify-center px-[22%] text-center tabular-nums text-primary ${centerText.length > 7 ? "text-caption font-semibold" : "text-body-strong"}`}>{centerText}</span> : null}
      </div>
      <ul aria-label={label} className="min-w-40 flex-[1_1_10rem] space-y-1.5">
        {slices.map((s, i) => (
          <li key={s.key} data-label={s.label} data-value={s.value} className="flex items-center gap-2 text-body">
            <span className="size-3 shrink-0 rounded-full" style={{ background: VIZ[i % VIZ.length] }} aria-hidden="true" />
            {s.href ? (
              <a href={s.href} data-testid={`slice-link-${s.key}`} className="inline-flex min-h-(--ctl-h) min-w-0 flex-1 items-center truncate text-accent underline-offset-2 hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" title={s.label}>
                <span className="truncate">{s.label}</span>
              </a>
            ) : (
              <span className="min-w-0 flex-1 truncate text-primary" title={s.label}>{s.label}</span>
            )}
            <span className="whitespace-nowrap text-body-strong text-primary tabular-nums">{s.valueText}</span>
            <span className="w-14 shrink-0 whitespace-nowrap text-end text-caption text-secondary tabular-nums">{s.shareText}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export interface Column {
  key: string;
  label: string;
  value: number;
  valueText: string;
}

/** Vertical bars, one hue per bar slot, value printed above each bar, labels below (wrapped); scrolls sideways on a narrow screen. */
export function ColumnChart({ rows, label, testId, height = 200 }: { rows: readonly Column[]; label: string; testId?: string; height?: number }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <div className="overflow-x-auto" data-testid={testId}>
      <ul aria-label={label} className="flex items-end gap-3 pt-2" style={{ minWidth: Math.max(rows.length * 64, 0) }}>
        {rows.map((r, i) => (
          <li key={r.key} data-label={r.label} data-value={r.value} className="flex w-16 min-w-0 flex-1 flex-col items-center justify-end gap-1" style={{ height: height + 56 }}>
            <span className="text-caption text-primary tabular-nums">{r.valueText}</span>
            <span className="w-full max-w-12 rounded-t-chip" style={{ height: Math.max(r.value > 0 ? 3 : 0, (r.value / max) * height), background: VIZ[i % VIZ.length] }} aria-hidden="true" />
            <span className="line-clamp-2 h-10 w-full text-center text-caption text-secondary [overflow-wrap:anywhere]" title={r.label}>{r.label}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
