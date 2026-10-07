import { onApiFailure } from "@/components/admin/crud/pages";
import { one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { AUDIT_FILTER_KEYS, cleanAuditFilter, fetchAudit } from "@/lib/admin/audit";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { AuditView } from "./audit-view";

/** The one audit page body: /admin/audit and Config page P16 render exactly this, so they show the same rows. */
export async function AuditPageContent({ searchParams, basePath, titleKey }: { searchParams: Promise<SearchParams>; basePath: string; titleKey?: "audit.title" | "cfgp.audit.title" }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const raw: Record<string, string | undefined> = {};
  for (const k of AUDIT_FILTER_KEYS) raw[k] = one(sp[k], 64);
  const filter = cleanAuditFilter(raw);
  const r = await fetchAudit(session.at, filter, one(sp.cursor, 512));
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  return <AuditView locale={locale} entries={r.data.items} filter={filter} nextCursor={r.data.next_cursor} basePath={basePath} titleKey={titleKey} />;
}
