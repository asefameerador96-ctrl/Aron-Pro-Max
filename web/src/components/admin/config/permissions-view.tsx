// F-ADM-064 role x menu x action matrix editor (data, not code) and the admin roster.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { PermissionMatrix } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { PermissionEditor } from "./permission-editor";

type RosterRow = PermissionMatrix["admin_roster"][number];

export function PermissionsView({ locale, matrix, role, canWrite }: { locale: Locale; matrix: PermissionMatrix; role: string; canWrite: boolean }) {
  const menuIds = [...new Set(matrix.roles.flatMap((r) => r.menus.map((m) => m.menu_id)))].sort();
  const current = matrix.roles.find((r) => r.role === role) ?? null;
  const roster: Column<RosterRow>[] = [
    { key: "u", header: t(locale, "cfgc.col.user"), render: (r) => r.username },
    { key: "r", header: t(locale, "pm.role"), render: (r) => t(locale, `role.${r.role}` as MessageKey) },
    { key: "m", header: t(locale, "pm.mfa"), render: (r) => t(locale, r.mfa_enabled ? "common.yes" : "common.no") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "pm.title")} intro={t(locale, "pm.intro")} />
      <p className="text-xs text-slate-500">{t(locale, "pm.version", { v: formatNumber(locale, matrix.config_version, { useGrouping: false }) })}</p>
      <nav className="flex flex-wrap gap-2" aria-label={t(locale, "pm.role")}>
        {matrix.roles.map((r) => (
          <Link key={r.role} href={`/admin/permissions?role=${r.role}`} className={`rounded border px-3 py-1 text-sm ${r.role === role ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
            {t(locale, `role.${r.role}` as MessageKey)}
          </Link>
        ))}
      </nav>
      {current ? (
        <Card title={t(locale, `role.${current.role}` as MessageKey)}>
          <PermissionEditor key={current.role} role={current.role} menuIds={menuIds} initial={current.menus} canWrite={canWrite} />
        </Card>
      ) : (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "pm.choose")}</p>
      )}
      <Card title={t(locale, "pm.roster")}>
        <DataTable columns={roster} rows={matrix.admin_roster} rowKey={(r) => String(r.user_id)} empty={t(locale, "common.empty")} caption={t(locale, "pm.roster")} />
      </Card>
    </div>
  );
}
