// Skeleton (web-glass s12.1): mirrors the final layout, sweep capped at 2 cycles (glass.css), static under reduced motion.
export function Skeleton({ className = "h-10 w-32" }: { className?: string }) {
  return <div aria-hidden="true" className={`skeleton rounded-chip ${className}`} />;
}
