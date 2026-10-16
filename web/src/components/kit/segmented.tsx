// Segmented control of links (web-glass s6): the selected segment is accent-container with a 1.5 px accent ring and a check;
// unselected text-secondary. Links keep the choice in the URL, so a view can be shared and works without script.
import Link from "next/link";
import { Icon } from "./icons";

export interface Segment {
  href: string;
  label: string;
  current: boolean;
  testId?: string;
}

export function SegmentedLinks({ label, items, className = "" }: { label: string; items: readonly Segment[]; className?: string }) {
  return (
    <nav aria-label={label} className={`seg ${className}`}>
      {items.map((s) => (
        <Link key={s.href} href={s.href} aria-current={s.current ? "page" : undefined} data-testid={s.testId}>
          {s.current ? <Icon name="check" size={16} /> : null}
          {s.label}
        </Link>
      ))}
    </nav>
  );
}
