// Status chip (web-glass s7.1, s12.5): opaque *-container with the *-on-container word and an icon, never colour alone.
// A nominal state (`quiet`: Live, Synced, Done) has no container, only a dot and the word in text-secondary.
import type { ReactNode } from "react";
import { Icon, type IconName } from "./icons";

export type ChipTone = "success" | "warning" | "danger" | "offline" | "accent" | "quiet";

const TONE: Record<ChipTone, { cls: string; icon: IconName }> = {
  success: { cls: "bg-success-container text-success-on-container", icon: "check-circle" },
  warning: { cls: "bg-warning-container text-warning-on-container", icon: "warning" },
  danger: { cls: "bg-danger-container text-danger-on-container", icon: "error" },
  offline: { cls: "bg-offline-container text-offline-on-container", icon: "offline" },
  accent: { cls: "bg-accent-container text-accent-on-container", icon: "info" },
  quiet: { cls: "text-secondary", icon: "dot" },
};

export function StatusChip({ tone, icon, children, className = "", testId, ...rest }: { tone: ChipTone; icon?: IconName | null; children: ReactNode; className?: string; testId?: string } & Record<`data-${string}`, string | undefined>) {
  const t = TONE[tone];
  const name = icon === undefined ? t.icon : icon;
  return (
    <span className={`inline-flex min-h-7 items-center gap-1.5 rounded-chip px-2.5 py-0.5 text-label ${t.cls} ${className}`} data-testid={testId} data-tone={tone} {...rest}>
      {name ? <Icon name={name} size={16} className={tone === "quiet" ? "text-success" : ""} /> : null}
      <span>{children}</span>
    </span>
  );
}
