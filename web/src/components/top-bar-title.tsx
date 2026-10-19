"use client";
import { usePathname } from "next/navigation";
import type { NavSection } from "./sidebar";

/** The page title in the top bar (PARITY-SPEC s4): the label of the menu entry that owns the current path, longest href first,
 *  so a drill-down or detail page shows its parent's name. Nothing is shown when no entry matches. */
export function TopBarTitle({ sections }: { sections: NavSection[] }) {
  const path = usePathname();
  const items = sections.flatMap((s) => s.items).sort((a, b) => b.href.length - a.href.length);
  const hit = items.find((i) => (i.href === "/" ? path === "/" || path.startsWith("/drill/") : path === i.href || path.startsWith(`${i.href}/`)));
  if (!hit) return null;
  return (
    <p className="hidden max-w-72 truncate text-heading text-accent md:block" data-testid="top-bar-title" title={hit.label}>
      {hit.label}
    </p>
  );
}
