// Empty state (web-glass s12.2): a 96 px accent-container circle with a glyph, a heading, one sentence and at most one action.
import type { ReactNode } from "react";
import { Icon, type IconName } from "./icons";

export function EmptyState({ icon = "inbox", title, body, action, testId, className = "" }: { icon?: IconName; title: string; body?: string; action?: ReactNode; testId?: string; className?: string }) {
  return (
    <section data-testid={testId} className={`glass-card flex flex-col items-center gap-3 p-(--card-pad) py-10 text-center ${className}`}>
      <span className="flex size-24 items-center justify-center rounded-full bg-accent-container text-accent-on-container" aria-hidden="true">
        <Icon name={icon} size={40} />
      </span>
      <h2 className="text-heading text-primary">{title}</h2>
      {body ? <p className="max-w-prose text-body text-secondary">{body}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </section>
  );
}
