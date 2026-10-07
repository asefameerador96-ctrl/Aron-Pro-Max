// F-ADM-034 audit viewer (and the same view on Config page P16): filter, table with the before and after, CSV export.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import { NextLink, PageHeading, qs } from "@/components/admin/kit/page";
import { AUDIT_FILTER_KEYS, diffLines, type AuditFilter } from "@/lib/admin/audit";
import type { AuditEntry } from "@/lib/admin/types";
import { formatDateTime, t, type Locale } from "@/lib/i18n";

export function AuditView({ locale, entries, filter, nextCursor, basePath, titleKey = "audit.title" }: { locale: Locale; entries: AuditEntry[]; filter: AuditFilter; nextCursor: string | null; basePath: string; titleKey?: "audit.title" | "cfgp.audit.title" }) {
  const labels: Record<(typeof AUDIT_FILTER_KEYS)[number], string> = {
    entity: t(locale, "audit.col.entity"),
    entity_id: t(locale, "audit.filter.entity_id"),
    actor_user_id: t(locale, "audit.filter.actor"),
    action: t(locale, "audit.col.action"),
    from: t(locale, "cfgc.from"),
    to: t(locale, "cfgc.to"),
  };
  const controls: FilterControl[] = AUDIT_FILTER_KEYS.map((k) => ({ param: k, label: labels[k], kind: "search", value: filter[k] ?? "" }));
  const columns: Column<AuditEntry>[] = [
    { key: "at", header: t(locale, "audit.col.at"), render: (e) => <time dateTime={e.at}>{formatDateTime(locale, e.at)}</time> },
    { key: "who", header: t(locale, "audit.col.actor"), render: (e) => e.actor_username ?? (e.actor_user_id ? String(e.actor_user_id) : t(locale, "audit.system")) },
    { key: "entity", header: t(locale, "audit.col.entity"), render: (e) => `${e.entity} #${e.entity_id}` },
    { key: "action", header: t(locale, "audit.col.action"), render: (e) => e.action },
    {
      key: "change",
      header: t(locale, "audit.col.change"),
      render: (e) => {
        const lines = diffLines(e.before, e.after);
        return lines.length === 0 ? (
          <span className="text-slate-400">—</span>
        ) : (
          <ul className="space-y-0.5 font-mono text-xs">
            {lines.map((l) => (
              <li key={l.key}>
                {l.key}: <span className="text-[var(--danger)]">{l.from || "∅"}</span> → <span className="text-[var(--success)]">{l.to || "∅"}</span>
              </li>
            ))}
          </ul>
        );
      },
    },
    { key: "reason", header: t(locale, "audit.col.reason"), render: (e) => e.reason ?? "—" },
  ];
  const keep = { ...filter };
  const next = nextCursor ? `${basePath}${qs({ ...keep, cursor: nextCursor })}` : null;
  return (
    <div className="space-y-4">
      <PageHeading
        title={t(locale, titleKey)}
        actions={
          <a href={`/api/bff/admin-export/audit${qs(keep)}`} data-testid="audit-export" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-100">
            {t(locale, "audit.export")}
          </a>
        }
      />
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref={basePath} />
      <DataTable columns={columns} rows={entries} rowKey={(e) => String(e.id)} empty={t(locale, "common.empty")} caption={t(locale, titleKey)} />
      <NextLink href={next} label={t(locale, "common.next")} />
    </div>
  );
}
