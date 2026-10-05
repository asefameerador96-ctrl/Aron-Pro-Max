import Link from "next/link";
import { Forbidden } from "@/components/forbidden";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import type { AuditEntry } from "@/contract/types";
import { apiClient, outcome } from "@/lib/api/client";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { formatDateTime, problemMessage, t } from "@/lib/i18n";

const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)?.slice(0, 64) || undefined;

export default async function AuditPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const q = { entity: one(sp.entity), entity_id: one(sp.entity_id), action: one(sp.action), cursor: one(sp.cursor), limit: 50 };
  const r = await outcome(apiClient(session.at).GET("/v1/admin/audit", { params: { query: q } }));

  const controls: FilterControl[] = [
    { param: "entity", label: t(locale, "audit.col.entity"), kind: "search", value: q.entity ?? "" },
    { param: "entity_id", label: t(locale, "entity.field.id"), kind: "search", value: q.entity_id ?? "" },
    { param: "action", label: t(locale, "audit.col.action"), kind: "search", value: q.action ?? "" },
  ];
  const columns: Column<AuditEntry>[] = [
    { key: "at", header: t(locale, "audit.col.at"), render: (e) => <time dateTime={e.at}>{formatDateTime(locale, e.at)}</time> },
    { key: "who", header: t(locale, "audit.col.actor"), render: (e) => e.actor_username ?? String(e.actor_user_id ?? "—") },
    { key: "entity", header: t(locale, "audit.col.entity"), render: (e) => `${e.entity} #${e.entity_id}` },
    { key: "action", header: t(locale, "audit.col.action"), render: (e) => e.action },
    { key: "reason", header: t(locale, "audit.col.reason"), render: (e) => e.reason ?? "—" },
  ];
  const next = r.ok && r.data.next_cursor ? `/admin/audit?${new URLSearchParams({ ...(q.entity ? { entity: q.entity } : {}), ...(q.entity_id ? { entity_id: q.entity_id } : {}), ...(q.action ? { action: q.action } : {}), cursor: r.data.next_cursor }).toString()}` : null;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "audit.title")}</h1>
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/audit" />
      {r.ok ? (
        <DataTable columns={columns} rows={r.data.items} rowKey={(e) => String(e.id)} empty={t(locale, "common.empty")} caption={t(locale, "audit.title")} />
      ) : (
        <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">
          {problemMessage(locale, r.problem.code)}
        </p>
      )}
      {next ? (
        <Link href={next} className="text-brand-700 underline">
          {t(locale, "common.next")} →
        </Link>
      ) : null}
    </div>
  );
}
