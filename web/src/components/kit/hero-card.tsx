// Hero card (web-glass s7.3, screens-web s1): the one text-hero figure of a dashboard. Money keeps the formatter's string
// (integer milli-taka, three decimals, locale digits); the decimals are set at 0.75 em and the taka sign at 0.6 em in the
// Bengali face, joined to the amount so it never wraps alone. The sparkline is optional and only drawn from real numbers.
import Link from "next/link";
import type { ReactNode } from "react";
import { STRETCHED_LINK } from "./stat-card";

export function Money({ amount, testId, className = "" }: { amount: string; testId?: string; className?: string }) {
  const dot = amount.lastIndexOf(".");
  const whole = dot < 0 ? amount : amount.slice(0, dot);
  const frac = dot < 0 ? "" : amount.slice(dot);
  return (
    <span className={`money ${className}`}>
      <span data-testid={testId}>
        {whole}
        {frac ? <span className="frac">{frac}</span> : null}
      </span>
      {" "}
      <span className="taka" lang="bn">
        ৳
      </span>
    </span>
  );
}

export function Sparkline({ points, label }: { points: readonly number[]; label: string }) {
  if (points.length < 2) return null;
  const w = 140;
  const h = 48;
  const max = Math.max(...points);
  const min = Math.min(...points);
  const span = max - min || 1;
  const xy = points.map((p, i) => [4 + (i * (w - 8)) / (points.length - 1), 4 + (h - 8) * (1 - (p - min) / span)] as const);
  const last = xy[xy.length - 1]!;
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label} className="shrink-0">
      <polyline points={xy.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(" ")} fill="none" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" className="text-secondary/50" />
      <circle cx={last[0]} cy={last[1]} r={4} className="fill-accent-graphic" stroke="var(--surface-solid)" strokeWidth={2} />
    </svg>
  );
}

export function HeroCard({ label, amount, sub, delta, sparkline, sparklineLabel, href, testId, valueTestId, caption, className = "" }: { label: string; amount: string; sub?: ReactNode; delta?: ReactNode; sparkline?: readonly number[]; sparklineLabel?: string; href?: string; testId?: string; valueTestId?: string; caption?: ReactNode; className?: string }) {
  return (
    <section data-testid={testId} className={`glass-card relative flex min-h-42 flex-col gap-2 p-(--card-pad) text-primary ${className}`}>
      <h2 className="text-label text-secondary">{href ? <Link href={href} className={STRETCHED_LINK}>{label}</Link> : label}</h2>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <p className="text-hero text-primary [overflow-wrap:anywhere]">
          <Money amount={amount} testId={valueTestId} />
        </p>
        {sparkline && sparklineLabel ? <Sparkline points={sparkline} label={sparklineLabel} /> : null}
      </div>
      {sub ? <p className="text-body text-secondary">{sub}</p> : null}
      {delta ? <div className="flex flex-wrap items-center gap-2">{delta}</div> : null}
      {caption ? <div className="mt-auto pt-1">{caption}</div> : null}
    </section>
  );
}
