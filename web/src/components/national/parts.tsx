// Building blocks of the national dashboard cards: the card shell (blue title over a thin rule, as on the original portal), the big ring,
// the colour per channel and the card error. All text comes from the caller; colours are tokens (--viz-*, --accent, --cyan).
import type { ReactNode } from "react";
import type { ChannelCode } from "@/lib/national/types";
import { CHANNELS } from "@/lib/national/types";

export const CHANNEL_COLOR: Record<ChannelCode, string> = {
  GT: "var(--viz-1)",
  DCC: "var(--viz-6)",
  Astha: "var(--viz-2)",
  RCC: "var(--viz-8)",
  MT: "var(--cyan)",
  HoReCa: "var(--viz-7)",
};
export const channelOrder = (c: string): number => {
  const i = (CHANNELS as readonly string[]).indexOf(c);
  return i < 0 ? CHANNELS.length : i;
};

/** One white card: blue title, a thin rule under it, the body, and room for the round "i" at the bottom right. */
export function NatCard({ id, title, className = "", children, titleRight }: { id: string; title: string; className?: string; children: ReactNode; titleRight?: ReactNode }) {
  return (
    <section data-testid={`nat-${id}`} aria-labelledby={`nat-${id}-h`} className={`glass-data relative flex min-h-64 flex-col gap-2 p-(--card-pad) text-primary ${className}`}>
      <div className="flex items-center justify-between gap-2 border-b border-accent-graphic/40 pb-2">
        <h2 id={`nat-${id}-h`} className="min-w-0 flex-1 truncate text-body-strong text-accent" title={title}>
          {title}
        </h2>
        {titleRight}
      </div>
      <div className="flex min-h-0 flex-1 flex-col justify-center">{children}</div>
    </section>
  );
}

/** The ring with the percentage in its middle (48.5%): a thick arc on a pale track. `tone` picks blue to cyan (sales) or green (CPR, geo). */
export function BigRing({ pct, text, label, tone = "blue", size = 150, testId }: { pct: number | null; text: string; label: string; tone?: "blue" | "green" | "plain"; size?: number; testId?: string }) {
  const v = pct === null ? 0 : Math.max(0, Math.min(100, pct));
  const r = 42;
  const c = 2 * Math.PI * r;
  const gid = `g-${tone}-${size}`;
  return (
    <div role="img" aria-label={`${label}: ${text}`} data-testid={testId} data-pct={pct ?? ""} className="relative shrink-0" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" className="-rotate-90">
        <defs>
          <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={tone === "green" ? "var(--viz-6)" : "var(--ring-from)"} />
            <stop offset="100%" stopColor={tone === "green" ? "var(--viz-3)" : "var(--ring-to)"} />
          </linearGradient>
        </defs>
        <circle cx="50" cy="50" r={r} fill="none" stroke="var(--state-skeleton)" strokeWidth="8" />
        {v > 0 ? <circle cx="50" cy="50" r={r} fill="none" stroke={`url(#${gid})`} strokeWidth="8" strokeLinecap="round" strokeDasharray={`${(v / 100) * c} ${c}`} /> : null}
      </svg>
      <span className="absolute inset-0 flex items-center justify-center px-3 text-center text-title tabular-nums text-primary">{text}</span>
    </div>
  );
}

/** A figure with its caption under it (3.64 Cr / Total Sales); `tone` colours the caption cyan as on the original. */
export function Figure({ value, caption, testId, captionTone = "cyan" }: { value: string; caption: string; testId?: string; captionTone?: "cyan" | "primary" }) {
  return (
    <div className="text-center">
      <p className="text-body-strong text-accent tabular-nums" data-testid={testId}>
        {value}
      </p>
      <p className={`text-body-strong ${captionTone === "cyan" ? "text-cyan" : "text-primary"}`}>{caption}</p>
    </div>
  );
}

export function CardError({ text, retry }: { text: string; retry: ReactNode }) {
  return (
    <p role="alert" data-testid="nat-card-error" className="flex flex-wrap items-center justify-center gap-2 text-center text-body text-danger-on-container">
      {text}
      {retry}
    </p>
  );
}
