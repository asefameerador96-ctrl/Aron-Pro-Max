// Dashboard charts (dataviz skill, web-glass s10): inline SVG, no dependency, hues from the --viz-* tokens so light and dark
// follow the theme, every value printed as text on a solid surface and every chart reachable by a text list.
import { useId, type ReactNode } from "react";

export const VIZ = ["var(--viz-1)", "var(--viz-2)", "var(--viz-3)", "var(--viz-4)", "var(--viz-5)", "var(--viz-6)", "var(--viz-7)", "var(--viz-8)"] as const;

/** Ring gauge: a 0 to 100 value on a track, the figure printed in the middle as text. Null draws an empty track. The
 *  component is ready for a target-based percentage later (docs/27); today the callers pass a share or a rate. */
/** Ring hues: the palette members that keep 3:1 against the data surface in light and dark (WCAG 1.4.11; tests/ring-contrast.test.ts). */
export const RING_HUES = [VIZ[0], VIZ[1], VIZ[6], VIZ[7], VIZ[5]] as const;

export function Ring({ pct, valueText, label, size = 112, hue = 0, gradient = false, children, testId }: { pct: number | null | undefined; valueText: string; label: string; size?: number; hue?: number; /** blue to cyan arc, as on the sales cards of the existing dashboard */ gradient?: boolean; children?: ReactNode; testId?: string }) {
  const gid = useId();
  const v = pct === null || pct === undefined ? 0 : Math.max(0, Math.min(100, pct));
  const r = 42;
  const c = 2 * Math.PI * r;
  return (
    <div role="img" aria-label={`${label}: ${valueText}`} data-testid={testId} data-pct={pct ?? ""} className="relative shrink-0" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" className="-rotate-90">
        {gradient ? (
          <defs>
            <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="var(--ring-from)" />
              <stop offset="100%" stopColor="var(--ring-to)" />
            </linearGradient>
          </defs>
        ) : null}
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth="11" />
        {/* No arc at 0: a round cap would still paint a dot. */}
        {v > 0 ? <circle cx="50" cy="50" r={r} fill="none" stroke={gradient ? `url(#${gid})` : RING_HUES[hue % RING_HUES.length]} strokeWidth="11" strokeLinecap="round" strokeDasharray={`${(v / 100) * c} ${c}`} /> : null}
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
        <span className={`${size < 90 ? "text-body-strong" : "text-heading"} text-primary tabular-nums`}>{valueText}</span>
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
export function Donut({ slices, label, centerText, testId, size = 140, radial = false, pie = false, legendFirst = false }: { slices: readonly Slice[]; label: string; centerText?: string; testId?: string; size?: number; radial?: boolean; /** a solid pie with thin separators instead of a ring (the existing dashboard's "By Channel STD") */ pie?: boolean; /** legend at the start, chart at the end (the existing dashboard puts the list at the left) */ legendFirst?: boolean }) {
  const total = slices.reduce((s, x) => s + Math.max(0, x.value), 0);
  const r = pie ? 25 : 40;
  const sw = pie ? 50 : 16;
  const c = 2 * Math.PI * r;
  let acc = 0;
  return (
    <div className="flex flex-wrap items-center gap-3" data-testid={testId}>
      <div className={`relative shrink-0 ${legendFirst ? "order-last ms-auto" : ""}`} style={{ width: size, height: size }} role="img" aria-label={label}>
        {radial ? <RadialRings slices={slices} total={total} size={size} /> : null}
        <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" className={`-rotate-90 ${radial ? "min-[480px]:hidden" : ""}`}>
          <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth={sw} />
          {total > 0
            ? slices.map((s, i) => {
                const len = (Math.max(0, s.value) / total) * c;
                const dash = Math.max(0, len - (slices.length > 1 ? 0.8 : 0));
                const el = <circle key={s.key} cx="50" cy="50" r={r} fill="none" stroke={VIZ[i % VIZ.length]} strokeWidth={sw} strokeDasharray={`${dash} ${c}`} strokeDashoffset={-acc} />;
                acc += len;
                return el;
              })
            : null}
        </svg>
        {centerText ? <span className={`absolute inset-0 flex items-center justify-center px-[22%] ${radial ? "min-[480px]:px-[26%]" : ""} text-center tabular-nums text-primary ${centerText.length > 7 ? "text-caption font-semibold" : "text-body-strong"}`}>{centerText}</span> : null}
      </div>
      <ul aria-label={label} className="min-w-24 flex-[1_1_6rem] space-y-1">
        {slices.map((s, i) => (
          <li key={s.key} data-label={s.label} data-value={s.value} className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-2 text-body">
            <span className={`size-3 shrink-0 rounded-full ${radial ? "min-[480px]:hidden" : ""}`} style={{ background: VIZ[i % VIZ.length] }} aria-hidden="true" />
            {radial ? <span className="hidden size-3 shrink-0 rounded-full min-[480px]:block" style={{ background: RING_HUES[i % RING_HUES.length] }} aria-hidden="true" /> : null}
            {s.href ? (
              <a href={s.href} data-testid={`slice-link-${s.key}`} className="inline-flex min-h-(--ctl-h) min-w-0 flex-1 items-center truncate text-accent underline-offset-2 hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" title={s.label}>
                <span className="truncate">{s.label}</span>
              </a>
            ) : (
              <span className="min-w-0 flex-1 truncate text-primary" title={s.label}>{s.label}</span>
            )}
            <span className="col-start-2 flex flex-wrap items-baseline gap-x-2 whitespace-nowrap">
              <span className="text-body-strong text-primary tabular-nums">{s.valueText}</span>
              <span className="text-caption text-secondary tabular-nums">{s.shareText}</span>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Concentric rings, outermost first: each channel's arc is its share of the total on its own track, hues from RING_HUES (3:1 on
 *  the data surface). Shown from 480 px up; the plain donut above is the narrow-screen fallback. */
function RadialRings({ slices, total, size }: { slices: readonly Slice[]; total: number; size: number }) {
  const n = Math.max(1, slices.length);
  const gap = Math.min(10, 24 / n); // rings share the outer 24 units; the middle stays free for the total
  const stroke = Math.max(2.5, gap - 1.5);
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" data-testid="radial-rings" className="-rotate-90 hidden min-[480px]:block">
      {slices.map((s, i) => {
        const r = 46 - stroke / 2 - i * gap;
        if (r <= stroke) return null;
        const c = 2 * Math.PI * r;
        const share = total > 0 ? Math.max(0, s.value) / total : 0;
        return (
          <g key={s.key}>
            <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth={stroke} />
            {share > 0 ? <circle cx="50" cy="50" r={r} fill="none" stroke={RING_HUES[i % RING_HUES.length]} strokeWidth={stroke} strokeLinecap="round" strokeDasharray={`${share * c} ${c}`} /> : null}
          </g>
        );
      })}
    </svg>
  );
}

export interface Column {
  key: string;
  label: string;
  value: number;
  valueText: string;
}

/** Round a maximum up to 1, 2, 2.5 or 5 times a power of ten so the axis ticks fall on readable numbers. */
export function niceMax(v: number): number {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  const f = v / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
}

/** Vertical bars, one hue per bar slot, value printed above each bar, labels below (wrapped); scrolls sideways on a narrow screen.
 *  `axisText` adds a y axis (0, quarter steps, top) in the caller's unit, e.g. lakh. */
export function ColumnChart({ rows, label, testId, height = 200, axisText }: { rows: readonly Column[]; label: string; testId?: string; height?: number; axisText?: (v: number) => string }) {
  const top = axisText ? niceMax(Math.max(0, ...rows.map((r) => r.value))) : Math.max(1, ...rows.map((r) => r.value));
  const base = 44; // label block (h-10) plus one gap: where the bars start inside each column
  return (
    <div className="flex" data-testid={testId}>
      {axisText ? (
        <div className="relative mt-3 w-20 shrink-0" style={{ height: height + 56 }} aria-hidden="true" data-testid="y-axis">
          {[0, 0.25, 0.5, 0.75, 1].map((f) => (
            <span key={f} className="absolute end-2 -translate-y-1/2 whitespace-nowrap text-caption text-secondary tabular-nums" style={{ bottom: base + f * height }}>{axisText(top * f)}</span>
          ))}
        </div>
      ) : null}
      <div role="region" aria-label={label} tabIndex={0} className="relative mt-3 min-w-0 flex-1 overflow-x-auto focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
        <div className="relative" style={{ minWidth: Math.max(rows.length * 64, 0) }}>
          {axisText ? [0, 0.25, 0.5, 0.75, 1].map((f) => <span key={f} className="pointer-events-none absolute inset-x-0 border-t border-divider" style={{ bottom: base + f * height }} aria-hidden="true" />) : null}
          <ul aria-label={label} className="relative flex items-end gap-3 pt-2">
            {rows.map((r) => (
              <li key={r.key} data-label={r.label} data-value={r.value} className="flex w-16 min-w-0 flex-1 flex-col items-center justify-end gap-1" style={{ height: height + 56 }}>
                <span className="text-caption text-primary tabular-nums">{r.valueText}</span>
                <span className="w-full max-w-12 rounded-t-chip" style={{ height: Math.max(r.value > 0 ? 3 : 0, (r.value / top) * height), background: `linear-gradient(180deg, ${VIZ[rows.indexOf(r) % VIZ.length]}, color-mix(in srgb, ${VIZ[rows.indexOf(r) % VIZ.length]} 60%, white))` }} aria-hidden="true" />
                <span className="line-clamp-2 h-10 w-full text-center text-caption text-secondary [overflow-wrap:anywhere]" title={r.label}>{r.label}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
