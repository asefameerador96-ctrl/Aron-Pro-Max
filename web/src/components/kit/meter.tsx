// Meter (web-glass s7.3, screens-web s1): 6 px track, accent-graphic fill, the value always printed by the caller and spoken
// here. No severity colouring until the owner names thresholds (Q-WD-01). A null value (no denominator) shows an empty track.
export function Meter({ pct, valueText, label, className = "" }: { pct: number | null | undefined; valueText: string; label: string; className?: string }) {
  const v = pct === null || pct === undefined ? null : Math.max(0, Math.min(100, pct));
  return (
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={v ?? undefined} aria-valuetext={valueText} className={`h-1.5 w-full overflow-hidden rounded-full bg-skeleton ${className}`}>
      <div className="h-full rounded-full bg-accent-graphic transition-[width] duration-base ease-out-soft" style={{ width: `${v ?? 0}%` }} />
    </div>
  );
}
