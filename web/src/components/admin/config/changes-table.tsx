import { DataTable, type Column } from "@/components/admin/kit/data-table";
import type { ConfigChange } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

/** Change requests as a table (Config home, P5). `actions` adds a last column (approve and so on). */
export function ChangesTable({ locale, rows, actions, caption }: { locale: Locale; rows: ConfigChange[]; actions?: (c: ConfigChange) => React.ReactNode; caption: string }) {
  const columns: Column<ConfigChange>[] = [
    { key: "id", header: t(locale, "cfgc.col.id"), render: (c) => formatNumber(locale, c.change_id, { useGrouping: false }) },
    { key: "status", header: t(locale, "cfgc.col.status"), render: (c) => t(locale, `cfgc.status.${c.status}` as MessageKey).replace(/\.$/, "") },
    { key: "risk", header: t(locale, "cfgh.col.risk"), render: (c) => formatNumber(locale, c.risk_class), align: "right" },
    {
      key: "keys",
      header: t(locale, "cfgh.col.keys"),
      render: (c) => (
        <ul className="space-y-0.5 font-mono text-xs">
          {c.changes.map((i) => (
            <li key={`${i.key}-${i.scope_type}-${i.scope_id}`}>
              {i.key} @ {t(locale, `cfgc.scope.${i.scope_type}` as MessageKey)}
              {i.scope_type === "global" ? "" : ` #${i.scope_id}`}: {i.old_value === undefined || i.old_value === null ? "∅" : JSON.stringify(i.old_value)} → {i.value === null ? "∅" : JSON.stringify(i.value)}
            </li>
          ))}
        </ul>
      ),
    },
    { key: "reason", header: t(locale, "cfgh.col.reason"), render: (c) => c.reason },
    { key: "at", header: t(locale, "cfgh.col.requested"), render: (c) => <time dateTime={c.requested_at}>{formatDateTime(locale, c.requested_at)}</time> },
  ];
  if (actions) columns.push({ key: "actions", header: t(locale, "common.actions"), render: actions });
  return <DataTable columns={columns} rows={rows} rowKey={(c) => String(c.change_id)} empty={t(locale, "cfgh.recent.empty")} caption={caption} />;
}
