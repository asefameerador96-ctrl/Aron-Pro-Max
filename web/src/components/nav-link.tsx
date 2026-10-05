"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

export function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const path = usePathname();
  const active = href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={`block rounded px-3 py-2 text-sm ${active ? "bg-brand-100 font-semibold text-brand-900" : "text-slate-700 hover:bg-slate-100"}`}
    >
      {children}
    </Link>
  );
}
