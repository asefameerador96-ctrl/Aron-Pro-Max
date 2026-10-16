// Stat card (web-glass s7.3): label / value (+ unit) / optional meter with the value printed / optional delta chip / as-of caption.
// With `href` the whole card is one link (stretched, one tab stop; the label is the accessible name).
import Link from "next/link";
import type { ReactNode } from "react";
import { Meter } from "./meter";

export const STRETCHED_LINK = "after:absolute after:inset-0 after:rounded-card focus-visible:outline-none focus-visible:after:outline-(length:--focus-w) focus-visible:after:outline-offset-2 after:outline-focus";

export interface StatCardProps {
  label: string;
  value: ReactNode;
  unit?: ReactNode;
  meter?: { pct: number | null | undefined; valueText: string };
  delta?: ReactNode;
  caption?: ReactNode;
  href?: string;
  testId?: string;
  valueTestId?: string;
  className?: string;
  children?: ReactNode;
}

export function StatCard({ label, value, unit, meter, delta, caption, href, testId, valueTestId, className = "", children }: StatCardProps) {
  return (
    <section data-testid={testId} className={`glass-card @container relative flex min-h-32 flex-col gap-2 p-(--card-pad) text-primary ${className}`}>
      <h2 className="text-label text-secondary">{href ? <Link href={href} className={STRETCHED_LINK}>{label}</Link> : label}</h2>
      {/* Numbers never wrap or clip (tokens s7.3): the figure is nowrap and shrinks to the card (`fit-number`); only labels may break. */}
      <p className="stat-value fit-number flex flex-wrap items-baseline gap-x-2 text-display text-primary">
        <span className="whitespace-nowrap tabular-nums" data-testid={valueTestId}>
          {value}
        </span>
        {unit ? <span className="text-body text-secondary">{unit}</span> : null}
      </p>
      {meter ? <Meter pct={meter.pct} valueText={meter.valueText} label={label} /> : null}
      {delta ? <div className="flex flex-wrap items-center gap-2">{delta}</div> : null}
      {children}
      {caption ? <div className="mt-auto pt-1">{caption}</div> : null}
    </section>
  );
}
