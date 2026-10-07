// Small shared pieces of server-rendered admin pages.
import Link from "next/link";
import type { ReactNode } from "react";
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";

export const one = (v: string | string[] | undefined, max = 80): string | undefined => (Array.isArray(v) ? v[0] : v)?.slice(0, max) || undefined;
export type SearchParams = Record<string, string | string[] | undefined>;

export function PageHeading({ title, actions, intro }: { title: string; actions?: ReactNode; intro?: string }) {
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">{title}</h1>
        {actions}
      </div>
      {intro ? <p className="text-sm text-slate-600">{intro}</p> : null}
    </div>
  );
}

export function Card({ title, children, testId }: { title?: string; children: ReactNode; testId?: string }) {
  return (
    <section className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid={testId}>
      {title ? <h2 className="text-lg font-semibold">{title}</h2> : null}
      {children}
    </section>
  );
}

export function NextLink({ href, label }: { href: string | null; label: string }) {
  if (!href) return null;
  return (
    <p>
      <Link href={href} data-testid="next-page" className="text-brand-700 underline">
        {label} →
      </Link>
    </p>
  );
}

export function Stat({ label, value, testId }: { label: string; value: ReactNode; testId?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <p className="text-xs uppercase tracking-wide text-slate-500">{label}</p>
      <p className="text-2xl font-semibold" data-testid={testId}>
        {value}
      </p>
    </div>
  );
}

/** Query string from the defined entries of `q`. */
export function qs(q: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : "";
}

/** GET a contract path with the session token (server only). */
export function apiGet<T>(path: string, token: string, query?: Record<string, string | number | undefined>): Promise<ApiOutcome<T>> {
  return rawRequest<T>({ method: "GET", path, token, query });
}
