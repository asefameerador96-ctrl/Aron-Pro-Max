// Banner (web-glass s12): opaque *-container, inline icon, text in *-on-container. `role="alert"` only after an action.
import type { ReactNode } from "react";
import { Icon, type IconName } from "./icons";

export type BannerTone = "info" | "success" | "warning" | "danger" | "offline";

const TONE: Record<BannerTone, { cls: string; icon: IconName }> = {
  info: { cls: "bg-accent-container text-accent-on-container", icon: "info" },
  success: { cls: "bg-success-container text-success-on-container", icon: "check-circle" },
  warning: { cls: "bg-warning-container text-warning-on-container", icon: "warning" },
  danger: { cls: "bg-danger-container text-danger-on-container", icon: "error" },
  offline: { cls: "bg-offline-container text-offline-on-container", icon: "offline" },
};

export function Banner({ tone, children, action, role, testId, className = "", ...rest }: { tone: BannerTone; children: ReactNode; action?: ReactNode; role?: "alert" | "status" | "note"; testId?: string; className?: string } & Record<`data-${string}`, string | undefined>) {
  const t = TONE[tone];
  return (
    <div role={role} data-testid={testId} className={`flex flex-wrap items-start gap-3 rounded-card px-4 py-3 text-body ${t.cls} ${className}`} {...rest}>
      <Icon name={t.icon} className="mt-0.5" />
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">{children}</div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}
