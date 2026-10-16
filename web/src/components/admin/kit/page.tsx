// Small shared pieces of server-rendered admin pages, restyled on the Calm Glass kit (docs/design/web-glass.md s7, s9).
import Link from "next/link";
import type { ReactNode } from "react";
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import { Icon } from "@/components/kit/icons";

export const one = (v: string | string[] | undefined, max = 80): string | undefined => (Array.isArray(v) ? v[0] : v)?.slice(0, max) || undefined;
export type SearchParams = Record<string, string | string[] | undefined>;

/** Large page title (text-display), optional one-line intro and the page's actions at the end of the row. */
export function PageHeading({ title, actions, intro, caption }: { title: string; actions?: ReactNode; intro?: string; caption?: ReactNode }) {
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-display text-primary [overflow-wrap:anywhere]">{title}</h1>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {intro ? <p className="max-w-prose text-body text-secondary">{intro}</p> : null}
      {caption ? <div className="text-caption text-secondary">{caption}</div> : null}
    </div>
  );
}

/** Glass card with an optional heading. `data` puts the content on the near-opaque data surface (tables, charts, key rows). */
export function Card({ title, children, testId, data, actions, className = "" }: { title?: string; children: ReactNode; testId?: string; data?: boolean; actions?: ReactNode; className?: string }) {
  return (
    <section className={`${data ? "glass-data" : "glass-card"} space-y-3 p-(--card-pad) text-primary ${className}`} data-testid={testId}>
      {title || actions ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          {title ? <h2 className="text-heading text-primary">{title}</h2> : null}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}

export function NextLink({ href, label }: { href: string | null; label: string }) {
  if (!href) return null;
  return (
    <p>
      <Link href={href} data-testid="next-page" className="inline-flex min-h-(--ctl-h) items-center gap-1 rounded-chip text-body-strong text-accent hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
        {label}
        <Icon name="chevron-right" size={18} />
      </Link>
    </p>
  );
}

/** A small stat: label over a numeral. For the dashboard-grade card with meter and delta use the kit's StatCard. */
export function Stat({ label, value, testId }: { label: string; value: ReactNode; testId?: string }) {
  return (
    <div className="glass-card flex min-h-24 flex-col gap-1 p-(--card-pad) text-primary">
      <p className="text-label text-secondary">{label}</p>
      <p className="text-numeral text-primary tabular-nums [overflow-wrap:anywhere]" data-testid={testId}>
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
