"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Icon, type IconName } from "./kit/icons";

/** One nav item (web-glass s6, s7.1 recipe `.nv`): icon plus label; active = accent container, 1.5 px accent ring, aria-current.
 *  In the icon rail the label is hidden by CSS but stays in the accessible name and in the tooltip. */
export function NavLink({ href, icon, label, children, onNavigate }: { href: string; icon?: IconName; label?: string; children?: ReactNode; onNavigate?: () => void }) {
  const path = usePathname();
  const active = href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
  const text = label ?? children;
  return (
    <Link href={href} aria-current={active ? "page" : undefined} title={label} className="nv rail-item focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus" onClick={onNavigate}>
      {icon ? <Icon name={icon} /> : null}
      <span className="rail-label min-w-0 flex-1 [overflow-wrap:anywhere]">{text}</span>
    </Link>
  );
}
