// Flat channel charts of the national dashboard (dataviz skill): concentric radial rings for "By Channel Successful Call" and a pie for
// "By Channel STD". Every value is also printed in the legend next to the chart, so the picture is never the only carrier of a number.
import { formatNumber, type Locale } from "@/lib/i18n";
import type { ChannelCode } from "@/lib/national/types";
import { CHANNEL_COLOR } from "./parts";

type Row = { code: ChannelCode; value: number };

/** Concentric rings, outermost first: each channel's arc is its share of the total on its own pale track. */
export function ChannelRadial({ rows, label, size = 130 }: { rows: Row[]; label: string; size?: number }) {
  const total = rows.reduce((s, r) => s + Math.max(0, r.value), 0);
  const gap = 7.2;
  return (
    <svg role="img" aria-label={label} viewBox="0 0 100 100" width={size} height={size} data-testid="channel-radial" className="shrink-0 -rotate-90">
      {rows.map((r, i) => {
        const rad = 46 - i * gap;
        const c = 2 * Math.PI * rad;
        const v = total > 0 ? Math.max(0, r.value) / total : 0;
        return (
          <g key={r.code}>
            <circle cx="50" cy="50" r={rad} fill="none" stroke="var(--state-skeleton)" strokeWidth="3.2" />
            {v > 0 ? <circle cx="50" cy="50" r={rad} fill="none" stroke={CHANNEL_COLOR[r.code]} strokeWidth="3.2" strokeLinecap="round" strokeDasharray={`${Math.max(1, v * c)} ${c}`} /> : null}
          </g>
        );
      })}
    </svg>
  );
}

/** A flat pie with the share printed on slices of 5% and more (the smaller ones are in the legend). */
export function ChannelPie({ rows, label, locale, size = 170 }: { rows: Row[]; label: string; locale: Locale; size?: number }) {
  const total = rows.reduce((s, r) => s + Math.max(0, r.value), 0);
  if (total <= 0) return <svg role="img" aria-label={label} viewBox="0 0 100 100" width={size} height={size}><circle cx="50" cy="50" r="46" fill="var(--state-skeleton)" /></svg>;
  const R = 46;
  const pos = rows.filter((r) => r.value > 0);
  const slices = pos.map((r, i) => {
    const frac = r.value / total;
    const a0 = -Math.PI / 2 + (pos.slice(0, i).reduce((s, x) => s + x.value, 0) / total) * 2 * Math.PI;
    const a = a0 + frac * 2 * Math.PI;
    const mid = (a0 + a) / 2;
    const pt = (ang: number, rad: number) => `${50 + rad * Math.cos(ang)} ${50 + rad * Math.sin(ang)}`;
    const d = frac >= 0.9999 ? "" : `M50 50 L${pt(a0, R)} A${R} ${R} 0 ${frac > 0.5 ? 1 : 0} 1 ${pt(a, R)} Z`;
    return { r, frac, d, lx: 50 + R * 0.62 * Math.cos(mid), ly: 50 + R * 0.62 * Math.sin(mid) };
  });
  return (
    <svg role="img" aria-label={label} viewBox="0 0 100 100" width={size} height={size} data-testid="channel-pie" className="shrink-0">
      {slices.map((s) => (s.d ? <path key={s.r.code} d={s.d} fill={CHANNEL_COLOR[s.r.code]} stroke="var(--surface-solid)" strokeWidth="0.6" /> : <circle key={s.r.code} cx="50" cy="50" r={R} fill={CHANNEL_COLOR[s.r.code]} />))}
      {slices.filter((s) => s.frac >= 0.05).map((s) => (
        <text key={`t-${s.r.code}`} x={s.lx} y={s.ly} textAnchor="middle" dominantBaseline="central" fontSize="5.5" fontWeight="700" fill="var(--on-accent, #fff)">
          {formatNumber(locale, s.frac * 100, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}%
        </text>
      ))}
    </svg>
  );
}
