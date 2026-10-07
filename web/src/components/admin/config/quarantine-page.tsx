import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { QuarantinePage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { QuarantineView } from "./quarantine-view";

/** Shared body of F-ADM-030 and P14, so both list the same items. */
export async function QuarantinePageContent({ searchParams, basePath, titleKey }: { searchParams: Promise<SearchParams>; basePath: string; titleKey?: "qr.title" | "cfgp14.title" }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const st = one(sp.status, 20);
  const code = one(sp.code, 40);
  const zone = one(sp.zone_id, 15);
  const filters = {
    status: st && ["open", "accepted", "accepted_with_fix", "discarded"].includes(st) ? st : "",
    code: code && /^[a-z_]{2,40}$/.test(code) ? code : "",
    zone_id: zone && /^[1-9]\d{0,14}$/.test(zone) ? zone : "",
  };
  const r = await apiGet<QuarantinePage>("/v1/admin/quarantine", session.at, { ...filters, cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const item = one(sp.item, 15);
  const selected = item ? (r.data.items.find((q) => String(q.quarantine_id) === item) ?? null) : null;
  const nextHref = r.data.next_cursor ? `${basePath}${qs({ ...filters, cursor: r.data.next_cursor })}` : null;
  return <QuarantineView locale={locale} rows={r.data.items} filters={filters} basePath={basePath} nextHref={nextHref} canWrite={canOp("quarantine.resolve", session.user.role)} titleKey={titleKey} selected={selected} cursor={one(sp.cursor, 512)} />;
}
