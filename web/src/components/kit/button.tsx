// Buttons (web-glass s7.1). Primary is the lit accent gradient (one per view or sheet); secondary is opaque raised (never glass on
// glass); tertiary is text; destructive only inside a confirm step. Height follows --ctl-h (48 px on a coarse pointer).
// Disabled stays focusable (`aria-disabled`) so the reason beside it can be read.
import Link from "next/link";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Icon, type IconName } from "./icons";

export type ButtonVariant = "primary" | "secondary" | "text" | "destructive";

const BASE =
  "inline-flex min-h-(--ctl-h) shrink-0 items-center justify-center gap-2 rounded-full px-5 text-body-strong whitespace-nowrap transition-[background-color,box-shadow,transform,color] duration-fast ease-out-soft active:scale-[.97] focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus aria-disabled:pointer-events-none aria-disabled:bg-none aria-disabled:bg-disabled-fill aria-disabled:text-disabled-label aria-disabled:shadow-none disabled:pointer-events-none disabled:bg-none disabled:bg-disabled-fill disabled:text-disabled-label disabled:shadow-none";
const VARIANT: Record<ButtonVariant, string> = {
  primary: "bg-linear-to-b from-accent-hi to-accent-fill text-on-accent shadow-accent hover:from-accent-fill hover:to-accent-pressed",
  secondary: "border border-divider bg-raised text-accent hover:bg-pressed",
  text: "px-3 text-accent hover:underline",
  destructive: "bg-danger-fill text-on-accent",
};

/** The class recipe, for forms that render their own <button> (login, change password). */
export function buttonClass(variant: ButtonVariant = "primary", extra = ""): string {
  return `${BASE} ${VARIANT[variant]} ${extra}`.trim();
}

/** The one input recipe (web-glass s7.1): raised fill, 1 px input border, focus outline. */
export const INPUT_CLASS = "min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary placeholder:text-placeholder focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus disabled:bg-disabled-fill disabled:text-disabled-label";

type Common = { variant?: ButtonVariant; icon?: IconName; iconEnd?: IconName; className?: string; children: ReactNode; testId?: string };

export function Button({ variant = "primary", icon, iconEnd, className = "", children, testId, ...rest }: Common & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" className={buttonClass(variant, className)} data-testid={testId} {...rest}>
      {icon ? <Icon name={icon} size={18} /> : null}
      {children}
      {iconEnd ? <Icon name={iconEnd} size={18} /> : null}
    </button>
  );
}

/** A link styled as a button (same recipe, real navigation). `reload` uses a plain anchor for a full reload. */
export function ButtonLink({ href, variant = "secondary", icon, iconEnd, className = "", children, testId, reload }: Common & { href: string; reload?: boolean }) {
  const cls = buttonClass(variant, className);
  const inner = (
    <>
      {icon ? <Icon name={icon} size={18} /> : null}
      {children}
      {iconEnd ? <Icon name={iconEnd} size={18} /> : null}
    </>
  );
  if (reload)
    return (
      <a href={href} className={cls} data-testid={testId}>
        {inner}
      </a>
    );
  return (
    <Link href={href} className={cls} data-testid={testId}>
      {inner}
    </Link>
  );
}

/** A 40 px round icon button for bars (theme, menu, close). The label is the accessible name and the tooltip. */
export function IconButton({ icon, label, className = "", ...rest }: { icon: IconName; label: string; className?: string } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" aria-label={label} title={label} className={`inline-flex size-10 shrink-0 items-center justify-center rounded-full text-secondary hover:bg-pressed focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus ${className}`} {...rest}>
      <Icon name={icon} />
    </button>
  );
}
